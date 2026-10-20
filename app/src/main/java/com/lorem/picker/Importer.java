package com.lorem.picker;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.List;

/**
 * 在手机上把图片转成“表情版”真 GIF（长边 ≤300、保留透明、**不限制帧数**），写进表情库。
 * 原来这一步是在电脑上用 Pillow 做的，现在不需要电脑了。
 */
public class Importer {

    public static final int LIMIT = 300;
    /** GIF 的帧延时刻度就是 10ms（centisecond），按这个步长采样不会丢帧。 */
    public static final int STEP_MS = 10;

    public interface Progress {
        void on(String msg, int done, int total);
    }

    public interface Done {
        void on(int ok, int fail, String err);
    }

    /** 调试用：不为 null 时导入结果写到这个本地目录，而不是表情库 */
    public static java.io.File DEBUG_OUT = null;

    public static void run(final Context ctx, final Repo repo, final List<Uri> srcs,
                           final Progress prog, final Done done) {
        repo.pool.execute(() -> {
            int ok = 0;
            int fail = 0;
            String err = null;
            for (int i = 0; i < srcs.size(); i++) {
                if (prog != null) {
                    prog.on("正在转换 " + (i + 1) + "/" + srcs.size(), i, srcs.size());
                }
                try {
                    importOne(ctx, repo, srcs.get(i));
                    ok++;
                } catch (Exception e) {
                    fail++;
                    err = String.valueOf(e);
                    Repo.log("导入失败 " + srcs.get(i) + " -> " + e);
                }
            }
            if (done != null) {
                done.on(ok, fail, err);
            }
        });
    }

    /** 转一张源图并存进库，返回入库的文件名（md5(源)+".gif"）。 */
    public static String importOne(Context ctx, Repo repo, Uri src) throws Exception {
        byte[] raw = readAll(ctx, src);
        if (raw.length == 0) {
            throw new Exception("源文件读取为空");
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        AnimatedGifEncoder e = new AnimatedGifEncoder();
        e.start(bo);
        e.setRepeat(0);
        int frames = feed(raw, e);
        if (frames == 0) {
            throw new Exception("源文件不能解析");
        }
        e.finish();
        byte[] gif = bo.toByteArray();
        if (gif.length < 32) {
            throw new Exception("GIF转码异常");
        }
        String name = md5(raw) + ".gif";
        saveToLib(ctx, repo, name, gif, frames);
        return name;
    }

    // ---------------- 解码 + 编码（流式，不在内存里囤帧） ----------------

    /**
     * 把源图逐帧喂给编码器，返回写了几帧。
     * 动图：系统的 GIF 解码器（Movie）给总时长，按 10ms 采样，相邻画面相同就合并成"这一帧更久"。
     * 这样源里每一帧都会被原样取到，延时也对得上，且不设帧数上限。
     */
    private static int feed(byte[] raw, AnimatedGifEncoder e) {
        boolean isGif = raw.length > 10 && raw[0] == 'G' && raw[1] == 'I' && raw[2] == 'F';
        if (isGif) {
            Movie m = Movie.decodeByteArray(raw, 0, raw.length);
            if (m != null && m.width() > 0 && m.height() > 0 && m.duration() > 0) {
                Bitmap work = Bitmap.createBitmap(m.width(), m.height(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(work);
                Bitmap prev = null;
                int hold = 0;
                int written = 0;
                for (int t = 0; t < m.duration(); t += STEP_MS) {
                    work.eraseColor(0);
                    m.setTime(t);
                    m.draw(c, 0, 0);
                    Bitmap cur = work.copy(Bitmap.Config.ARGB_8888, false);
                    if (prev != null && cur.sameAs(prev)) {
                        hold += STEP_MS; // 画面没变 → 这一帧多停 10ms
                        cur.recycle();
                        continue;
                    }
                    if (prev != null) {
                        e.setDelay(hold);
                        if (e.addFrame(scale(prev))) {
                            written++;
                        }
                        prev.recycle();
                    }
                    prev = cur;
                    hold = STEP_MS;
                }
                if (prev != null) {
                    e.setDelay(hold);
                    if (e.addFrame(scale(prev))) {
                        written++;
                    }
                    prev.recycle();
                }
                work.recycle();
                Repo.log("feed: 动图 " + m.width() + "x" + m.height() + " 时长" + m.duration()
                        + "ms → 输出 " + written + " 帧");
                return written;
            }
            Repo.log("feed: Movie 解不出来（" + raw.length + " 字节），退回静态");
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (b == null) {
            return 0;
        }
        e.setDelay(100);
        if (!e.addFrame(scale(b))) {
            return 0;
        }
        Repo.log("feed: 静态图 → 1 帧");
        return 1;
    }

    /** 长边缩到 LIMIT 以内；返回的一定是新对象（编码器会 recycle 交给它的帧） */
    private static Bitmap scale(Bitmap src) {
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= LIMIT) {
            return src.copy(Bitmap.Config.ARGB_8888, false);
        }
        float s = LIMIT / (float) max;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(src.getWidth() * s)),
                Math.max(1, Math.round(src.getHeight() * s)), true);
    }

    // ---------------- 写进表情库 ----------------

    private static void saveToLib(Context ctx, Repo repo, String name, byte[] gif, int frames)
            throws Exception {
        if (DEBUG_OUT != null) {
            DEBUG_OUT.mkdirs();
            java.io.File f = new java.io.File(DEBUG_OUT, name);
            try (OutputStream os = new java.io.FileOutputStream(f)) {
                os.write(gif);
            }
            Repo.log("调试导入 " + f.getAbsolutePath() + " " + gif.length + " 字节 " + frames + " 帧");
            return;
        }
        Uri tree = repo.tree();
        if (tree == null) {
            throw new Exception("未选择表情库路径");
        }
        ContentResolver cr = ctx.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        Uri same = DocumentsContract.buildDocumentUriUsingTree(tree, rootId + "/" + name);
        // DocumentsProvider 遇到重名只会改名成 "xxx (1).gif"，所以先删掉旧的
        try {
            DocumentsContract.deleteDocument(cr, same);
        } catch (Exception ignored) {
        }
        Uri out = DocumentsContract.createDocument(cr, dir, "image/gif", name);
        if (out == null) {
            throw new Exception("写入失败，请检查表情库目录读写权限");
        }
        try (OutputStream os = cr.openOutputStream(out, "wt")) {
            os.write(gif);
            os.flush();
        }
        JSONObject root = LibOps.readJson(ctx, tree, "index.json");
        JSONArray items = root.optJSONArray("items");
        JSONArray keep = new JSONArray();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && !name.equals(it.optString("name"))) {
                keep.put(it);
            }
        }
        JSONObject ni = new JSONObject();
        ni.put("name", name);
        ni.put("frames", frames);
        ni.put("size", gif.length);
        ni.put("mtime", System.currentTimeMillis());
        keep.put(ni);
        root.put("items", keep);
        root.put("count", keep.length());
        root.put("limit", root.optInt("limit", LIMIT));
        root.put("maxframes", 0); // 0 = 不限帧数
        LibOps.writeJson(ctx, tree, "index.json", root);
        Repo.log("导入成功 " + name + " " + gif.length + " 字节 " + frames + " 帧");
    }

    // ---------------- 小工具 ----------------

    private static byte[] readAll(Context ctx, Uri u) throws Exception {
        if ("file".equals(u.getScheme())) {
            try (InputStream in = new java.io.FileInputStream(u.getPath())) {
                return drain(in);
            }
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
            if (in == null) {
                throw new Exception("读取文件失败");
            }
            return drain(in);
        }
    }

    private static byte[] drain(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private static String md5(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(b)) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
