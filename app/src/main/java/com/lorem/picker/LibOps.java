package com.lorem.picker;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 对表情库目录的写操作：读/写 index.json、删除表情。 */
public class LibOps {

    public static String rootId(Uri tree) {
        return DocumentsContract.getTreeDocumentId(tree);
    }

    public static Uri dirUri(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, rootId(tree));
    }

    public static Uri docUri(Uri tree, String name) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, rootId(tree) + "/" + name);
    }

    /** 读库目录里的一个 json（读不到就返回空对象，App 不会因此罢工）。 */
    public static JSONObject readJson(Context ctx, Uri tree, String name) {
        JSONObject root = new JSONObject();
        try (InputStream in = ctx.getContentResolver().openInputStream(docUri(tree, name))) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            root = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            Repo.log("读 " + name + " 失败（当作空）：" + e);
        }
        return root;
    }

    /** 写回库目录里的一个 json：先写临时文件再替换，中途出错不会把原来的弄丢。 */
    public static void writeJson(Context ctx, Uri tree, String name, JSONObject root)
            throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        try {
            DocumentsContract.deleteDocument(cr, docUri(tree, name + ".tmp"));
        } catch (Exception ignored) {
        }
        Uri tmp = DocumentsContract.createDocument(cr, dirUri(tree), "application/json", name + ".tmp");
        if (tmp == null) {
            throw new Exception("写不进 " + name + "（是不是没给写入权限？）");
        }
        try (OutputStream os = cr.openOutputStream(tmp, "wt")) {
            os.write(root.toString().getBytes("UTF-8"));
            os.flush();
        }
        try {
            DocumentsContract.deleteDocument(cr, docUri(tree, name));
        } catch (Exception ignored) {
        }
        DocumentsContract.renameDocument(cr, tmp, name);
    }

    /** 删除若干张表情：文件 + index.json 里的记录 + 最近使用，一起清掉。 */
    public static void delete(Context ctx, Repo repo, List<Sticker> victims) throws Exception {
        Uri tree = repo.tree();
        if (tree == null) {
            throw new Exception("未选择表情库路径");
        }
        ContentResolver cr = ctx.getContentResolver();
        Set<String> gone = new HashSet<>();
        for (Sticker st : victims) {
            try {
                if (DocumentsContract.deleteDocument(cr, st.uri)) {
                    gone.add(st.name);
                } else {
                    Repo.log("删除失败（provider 说不）：" + st.name);
                }
            } catch (Exception e) {
                Repo.log("删除失败 " + st.name + " -> " + e);
            }
        }

        JSONObject root = readJson(ctx, tree, "index.json");
        JSONArray items = root.optJSONArray("items");
        JSONArray keep = new JSONArray();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && !gone.contains(it.optString("name"))) {
                keep.put(it);
            }
        }
        root.put("items", keep);
        root.put("count", keep.length());
        writeJson(ctx, tree, "index.json", root);

        // 分类里也要把删掉的名字摘出去
        if (!gone.isEmpty()) {
            JSONObject cats = readJson(ctx, tree, "categories.json");
            JSONArray arr = cats.optJSONArray("cats");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                JSONArray its = c.optJSONArray("items");
                JSONArray k2 = new JSONArray();
                for (int j = 0; its != null && j < its.length(); j++) {
                    String s = its.optString(j);
                    if (!gone.contains(s)) {
                        k2.put(s);
                    }
                }
                c.put("items", k2);
            }
            if (arr != null) {
                cats.put("cats", arr);
                writeJson(ctx, tree, "categories.json", cats);
            }
        }
        repo.cats.clear();
        repo.loadCats();

        repo.forget(gone);
        repo.forgetUsage(gone);
        Repo.log("删除完成 " + gone.size() + "/" + victims.size() + " 张");
    }
}
