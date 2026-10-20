package com.lorem.picker;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.DocumentsContract;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;

import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 借 Shizuku 的 shell 身份，把 QQ 收藏的表情搬进表情库。
 *
 * 背景：Android 11 起第三方 app 进不去别的应用的 Android/data，
 * 而 QQ 收藏恰好在那里。只有 shell(uid 2000) 读得到，Shizuku 就是把这份身份借给 app。
 *
 * 流程：shell 复制 → <库目录>/_incoming/ → app 用现有的 SAF 读 → 转 GIF、去重、入库。
 */
public class QQImport {

    public static final int REQ_SHIZUKU = 4201;

    /** 表情库固定位置：下载/EmoteLib（SAF 的 docId 长这样）。 */
    public static final String LIB_DOC_ID = "primary:Download/EmoteLib";

    private static final String QQ_FAV =
            "/sdcard/Android/data/com.tencent.mobileqq/Tencent/QQ_Favorite";
    private static final String INCOMING = "_incoming";

    /** Shizuku 服务在跑、而且已经给过我们授权。 */
    public static boolean ready() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 服务在不在（可能还没授权）。 */
    public static boolean serviceUp() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 弹 Shizuku 的授权框。 */
    public static void requestPermission(Activity a) {
        try {
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            Repo.log("申请 Shizuku 授权失败 " + t);
        }
    }

    /**
     * 以 shell 身份跑一条命令，返回 {"code", "out", "err"}。
     * Shizuku 13.x 的 api 包里没有公开的 Shell 类（newProcess 是私有的），
     * 只能自己包一层 binder，直接调 IShizukuService。
     */
    public static String[] exec(String cmd) {
        IRemoteProcess proc = null;
        try {
            IShizukuService svc = IShizukuService.Stub.asInterface(
                    new ShizukuBinderWrapper(Shizuku.getBinder()));
            proc = svc.newProcess(new String[]{"/system/bin/sh", "-c", cmd}, null, null);
            String out = drain(proc.getInputStream());
            String err = drain(proc.getErrorStream());
            int code = proc.waitFor();
            return new String[]{String.valueOf(code), out, err};
        } catch (Throwable t) {
            Repo.log("Shizuku 执行失败 " + t);
            return new String[]{"-1", "", String.valueOf(t)};
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String drain(ParcelFileDescriptor pfd) throws Exception {
        if (pfd == null) {
            return "";
        }
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 把库的 SAF 树 URI 换成文件系统路径。
     * 只认外部存储（com.android.externalstorage.documents）：
     *   primary:Pictures/emojilib  → /sdcard/Pictures/emojilib
     *   1A2B-3C4D:Foo/Bar          → /storage/1A2B-3C4D/Foo/Bar
     */
    public static String fsPath(Uri tree) {
        if (tree == null) {
            return null;
        }
        try {
            if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) {
                return null;
            }
            String docId = DocumentsContract.getTreeDocumentId(tree);
            int colon = docId.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            String vol = docId.substring(0, colon);
            String path = docId.substring(colon + 1);
            String base = "primary".equals(vol) ? "/sdcard" : ("/storage/" + vol);
            return base + "/" + path;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String favDir() {
        return QQ_FAV;
    }

    public static String incomingDir(Uri tree) {
        String p = fsPath(tree);
        return p == null ? null : p + "/" + INCOMING;
    }

    /**
     * 交给 shell 的搬运脚本：按文件头判断格式、算 md5、**先看库里有没有**，
     * 命中就直接跳过（连临时目录都不进），没命中才按 md5 命名拷进 _incoming。
     * 库里的文件名本来就是转码前那张图的 md5，所以这里比的正是「同一张原图」。
     */
    public static String copyCommand(Uri tree) {
        String lib = fsPath(tree);
        String inc = incomingDir(tree);
        if (lib == null || inc == null) {
            return null;
        }
        return "fav='" + QQ_FAV + "'; lib='" + lib + "'; inc='" + inc + "'; "
                + "mkdir -p \"$inc\"; cd \"$fav\" || exit 2; n=0; d=0; "
                + "for f in *; do "
                + "h=$(head -c 4 \"$f\" | xxd -p); "
                + "case \"$h\" in "
                + "ffd8ff*) ext=jpg ;; 474946*) ext=gif ;; 89504e*) ext=png ;; *) continue ;; "
                + "esac; "
                + "m=$(md5sum \"$f\" | cut -c1-32); "
                + "if [ -f \"$lib/$m.gif\" ] || [ -f \"$inc/$m.$ext\" ]; then d=$((d+1)); continue; fi; "
                + "cp \"$f\" \"$inc/$m.$ext\" && n=$((n+1)); "
                + "done; echo \"copied=$n skip=$d\"";
    }

    /** 列出 _incoming 里的东西（URI → 文件名）。 */
    public static java.util.LinkedHashMap<Uri, String> listStaged(android.content.Context ctx, Uri tree) {
        java.util.LinkedHashMap<Uri, String> out = new java.util.LinkedHashMap<>();
        if (tree == null) {
            return out;
        }
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, rootId + "/" + INCOMING);
            String[] proj = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            };
            try (android.database.Cursor c = ctx.getContentResolver()
                    .query(children, proj, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String id = c.getString(0);
                        String name = c.getString(1);
                        out.put(DocumentsContract.buildDocumentUriUsingTree(tree, id), name);
                    }
                }
            }
        } catch (Throwable t) {
            Repo.log("列 _incoming 失败 " + t);
        }
        return out;
    }

    /** 删掉一个暂存文件。 */
    public static boolean deleteStaged(android.content.Context ctx, Uri doc) {
        try {
            return DocumentsContract.deleteDocument(ctx.getContentResolver(), doc);
        } catch (Throwable t) {
            Repo.log("删暂存失败 " + t);
            return false;
        }
    }
}
