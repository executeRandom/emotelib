package com.lorem.picker;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lib {

    /** 扫一个 SAF 目录，顺便读它的 index.json（手机上就不用再算动图帧数了）。 */
    public static List<Sticker> scan(Context ctx, Uri tree) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);

        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };

        List<String[]> rows = new ArrayList<>();
        Uri indexUri = null;
        try (Cursor c = cr.query(children, proj, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String docId = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.isNull(3) ? 0 : c.getLong(3);
                    long mod = c.isNull(4) ? 0 : c.getLong(4);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        continue;
                    }
                    if (name == null) {
                        continue;
                    }
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".json") || lower.endsWith(".tmp")) {
                        // 元数据文件（index.json / categories.json 等）不算表情
                        if (lower.equals("index.json")) {
                            indexUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                        }
                        continue;
                    }
                    if (mime == null || !mime.startsWith("image/")) {
                        continue; // 只收图片
                    }
                    Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                    rows.add(new String[]{name, u.toString(), String.valueOf(size), String.valueOf(mod)});
                }
            }
        }

        Map<String, Integer> frames = new HashMap<>();
        if (indexUri != null) {
            try (InputStream in = cr.openInputStream(indexUri)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                JSONObject o = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
                JSONArray arr = o.optJSONArray("items");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject it = arr.getJSONObject(i);
                    frames.put(it.optString("name"), it.optInt("frames", 1));
                }
            } catch (Exception ignored) {
            }
        }

        List<Sticker> out = new ArrayList<>();
        for (String[] r : rows) {
            String name = r[0];
            Integer f = frames.get(name);
            out.add(new Sticker(name, Uri.parse(r[1]), f == null ? 1 : f,
                    Long.parseLong(r[2]), Long.parseLong(r[3])));
        }
        return out;
    }
}
