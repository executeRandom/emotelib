package com.lorem.picker;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

public class SendHelper {

    private static final String TAG = "picker";
    private static final String QQ_PKG = "com.tencent.mobileqq";
    private static final String QQ_FORWARD = "com.tencent.mobileqq.activity.JumpActivity";
    private static final String ALBUM = "Pictures/表情发送";

    /** 先登记进系统媒体库（相册里的“表情发送”），再拿系统地址分享给 QQ。 */
    public static boolean send(Context ctx, Sticker s) {
        ContentResolver cr = ctx.getContentResolver();
        Uri out = null;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, s.name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            out = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (out == null) {
                throw new IllegalStateException("媒体库不给地址");
            }
            try (InputStream in = cr.openInputStream(s.uri);
                 OutputStream os = cr.openOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(out, done, null, null);
            Log.i(TAG, "已存入相册 ok");
        } catch (Exception e) {
            Log.e(TAG, "存相册失败，退回自带 provider", e);
            if (out != null) {
                try {
                    cr.delete(out, null, null);
                } catch (Exception ignored) {
                }
            }
            return sendViaSelf(ctx, s);
        }
        return shareTo(ctx, out) || shareChooser(ctx, out);
    }

    private static boolean shareTo(Context a, Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/gif");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        // 不能带 NEW_TASK：QQ 已经在运行时，系统只会把它的任务调到前台，分享意图被丢掉。
        // CLEAR_TOP 则能让它把新的分享意图交给已经在跑的那个实例。
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.setComponent(new ComponentName(QQ_PKG, QQ_FORWARD));
        try {
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "投 QQ 失败: " + e);
            return false;
        }
    }

    private static boolean shareChooser(Context a, Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/gif");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(Intent.createChooser(i, "发送到"));
            return true;
        } catch (Exception e) {
            Toast.makeText(a, "分享失败：" + e, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** 兜底：走我们自己的 provider（QQ 可能不认，权当备用）。 */
    private static boolean sendViaSelf(Context a, Sticker s) {
        ContentResolver cr = a.getContentResolver();
        java.io.File out;
        try {
            String safe = s.name.replaceAll("[^A-Za-z0-9._-]", "_");
            out = new java.io.File(ShareProvider.sendDir(a), safe);
            if (!out.exists()) {
                try (InputStream in = cr.openInputStream(s.uri);
                     OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception e) {
            Toast.makeText(a, "读取失败：" + e, Toast.LENGTH_LONG).show();
            return false;
        }
        Uri uri = Uri.parse("content://" + ShareProvider.AUTHORITY + "/" + out.getName());
        return shareTo(a, uri) || shareChooser(a, uri);
    }

    /** 清掉一天前丢进相册的临时图。 */
    public static void cleanOld(Context a) {
        try {
            long cut = System.currentTimeMillis() / 1000 - 24 * 3600;
            int n = a.getContentResolver().delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND "
                            + MediaStore.Images.Media.DATE_ADDED + " < ?",
                    new String[]{ALBUM + "%", String.valueOf(cut)});
            if (n > 0) {
                Log.i(TAG, "清掉了 " + n + " 张旧临时图");
            }
        } catch (Exception ignored) {
        }
    }
}
