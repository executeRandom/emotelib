package com.lorem.picker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 库、最近使用、缩略图缓存 —— Activity 和浮球服务共用这一份。 */
public class Repo {

    private static Repo instance;

    public static synchronized Repo get(Context c) {
        if (instance == null) {
            instance = new Repo(c.getApplicationContext());
        }
        return instance;
    }

    public final List<Sticker> all = new ArrayList<>();
    public final List<String> recent = new ArrayList<>();
    /** 自定义分类：名字 → 表情文件名列表（顺序即加入顺序）。 */
    public final java.util.LinkedHashMap<String, List<String>> cats = new java.util.LinkedHashMap<>();
    /** 使用次数：名字 → 次数（存在库目录的 usage.json 里，删表情时一起清）。 */
    public final java.util.HashMap<String, Integer> usage = new java.util.HashMap<>();
    /** “点击预览”正在看的那张（null = 没在预览）；主界面和浮球面板共用这一份。 */
    public volatile String previewName = null;
    private volatile boolean usageSaving = false;
    public final ExecutorService pool = Executors.newFixedThreadPool(4);

    private final Context ctx;
    private final SharedPreferences sp;
    private final LruCache<String, Bitmap> cache =
            new LruCache<String, Bitmap>(20 * 1024 * 1024) {
                @Override
                protected int sizeOf(String k, Bitmap b) {
                    return b.getByteCount();
                }
            };

    private int failLogs = 0;
    private static Context appCtx;

    /** 写日志到 App 自己的文件（ColorOS 的 logcat 缓冲太小，抢不过它）。 */
    public static void log(String msg) {
        android.util.Log.i("picker", msg);
        Context c = appCtx;
        if (c == null) {
            return;
        }
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "dbg.log");
            if (f.length() > 300 * 1024) {
                f.delete();
            }
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date())
                        + " " + msg + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private Repo(Context c) {
        appCtx = c;
        ctx = c;
        sp = c.getSharedPreferences("picker", Context.MODE_PRIVATE);
        String rec = sp.getString("recent", "");
        if (rec != null && rec.length() > 0) {
            Collections.addAll(recent, rec.split("\n"));
        }
    }

    public Context ctx() {
        return ctx;
    }

    public Uri tree() {
        String s = sp.getString("tree", null);
        return s == null ? null : Uri.parse(s);
    }

    public void setTree(Uri u) {
        sp.edit().putString("tree", u.toString()).apply();
    }

    public int getInt(String k, int d) {
        return sp.getInt(k, d);
    }

    public void putInt(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    public interface Done {
        void run(int count, Exception err);
    }

    /** 后台扫目录，回调在线程里跑，调用方自己 post 到主线程。 */
    public void load(final Done done) {
        final Uri t = tree();
        if (t == null) {
            if (done != null) {
                done.run(0, null);
            }
            return;
        }
        pool.execute(() -> {
            try {
                loadCats();     // 换了库目录之后分类也要跟着重读，否则筛选项里会空一截
                loadUsage();
                final List<Sticker> list = Lib.scan(ctx, t);
                log("扫库: " + list.size() + " 张, tree=" + t);
                synchronized (all) {
                    all.clear();
                    all.addAll(list);
                }
                if (done != null) {
                    done.run(list.size(), null);
                }
            } catch (final Exception e) {
                if (done != null) {
                    done.run(0, e);
                }
            }
        });
    }

    public String getString(String k, String d) {
        return sp.getString(k, d);
    }

    public void putString(String k, String v) {
        sp.edit().putString(k, v).apply();
    }

    // ---------- 自定义分类（存在库目录的 categories.json 里，重装也不丢）----------

    /** 从库目录读分类（后台线程调用）。 */
    public void loadCats() {
        Uri t = tree();
        java.util.LinkedHashMap<String, List<String>> m = new java.util.LinkedHashMap<>();
        if (t != null) {
            org.json.JSONObject root = LibOps.readJson(ctx, t, "categories.json");
            org.json.JSONArray arr = root.optJSONArray("cats");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                org.json.JSONObject c = arr.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                String n = c.optString("name");
                if (n == null || n.length() == 0) {
                    continue;
                }
                List<String> its = new ArrayList<>();
                org.json.JSONArray a2 = c.optJSONArray("items");
                for (int j = 0; a2 != null && j < a2.length(); j++) {
                    its.add(a2.optString(j));
                }
                m.put(n, its);
            }
        }
        synchronized (cats) {
            cats.clear();
            cats.putAll(m);
        }
    }

    /** 把分类写回库目录（后台线程调用）。 */
    public void saveCats() {
        Uri t = tree();
        if (t == null) {
            return;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            org.json.JSONArray arr = new org.json.JSONArray();
            synchronized (cats) {
                for (java.util.Map.Entry<String, List<String>> e : cats.entrySet()) {
                    org.json.JSONObject c = new org.json.JSONObject();
                    c.put("name", e.getKey());
                    org.json.JSONArray its = new org.json.JSONArray();
                    for (String s : e.getValue()) {
                        its.put(s);
                    }
                    c.put("items", its);
                    arr.put(c);
                }
            }
            root.put("cats", arr);
            LibOps.writeJson(ctx, t, "categories.json", root);
            Repo.log("分类已保存：" + cats.size() + " 个");
        } catch (Exception e) {
            Repo.log("保存分类失败 " + e);
        }
    }

    /** 被分到任意一个分类里的表情名集合（“默认”＝没出现在这里的那些）。 */
    public java.util.HashSet<String> groupedNames() {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        synchronized (cats) {
            for (List<String> l : cats.values()) {
                s.addAll(l);
            }
        }
        return s;
    }

    /** 建分类时不能跟内置筛选重名。 */
    public static final List<String> RESERVED = java.util.Arrays.asList(
            "全部", "动图", "静图", "最近", "未分类");

    public boolean createCat(String name) {
        synchronized (cats) {
            if (name == null || name.trim().length() == 0 || cats.containsKey(name.trim())) {
                return false;
            }
            cats.put(name.trim(), new ArrayList<String>());
        }
        return true;
    }

    public void deleteCat(String name) {
        synchronized (cats) {
            cats.remove(name);
        }
    }

    public boolean renameCat(String old, String nw) {
        String n = nw == null ? "" : nw.trim();
        if (n.length() == 0) {
            return false;
        }
        synchronized (cats) {
            if (!cats.containsKey(old) || cats.containsKey(n)) {
                return false;
            }
            java.util.LinkedHashMap<String, List<String>> copy =
                    new java.util.LinkedHashMap<>(cats);
            cats.clear();
            for (java.util.Map.Entry<String, List<String>> e : copy.entrySet()) {
                cats.put(e.getKey().equals(old) ? n : e.getKey(), e.getValue());
            }
        }
        return true;
    }

    public void addToCat(String name, List<Sticker> items) {
        synchronized (cats) {
            List<String> l = cats.get(name);
            if (l == null) {
                return;
            }
            for (Sticker st : items) {
                if (!l.contains(st.name)) {
                    l.add(st.name);
                }
            }
        }
    }

    public void removeFromCat(String name, List<Sticker> items) {
        synchronized (cats) {
            List<String> l = cats.get(name);
            if (l == null) {
                return;
            }
            for (Sticker st : items) {
                l.remove(st.name);
            }
        }
    }

    public void markUsed(String name) {
        recent.remove(name);
        recent.add(0, name);
        while (recent.size() > 48) {
            recent.remove(recent.size() - 1);
        }
        sp.edit().putString("recent", TextUtils.join("\n", recent)).apply();
        synchronized (usage) {
            Integer v = usage.get(name);
            usage.put(name, v == null ? 1 : v + 1);
        }
        scheduleUsageSave();
    }

    /** “常用”排序用：这张被点过多少次。 */
    public int useCount(String name) {
        synchronized (usage) {
            Integer v = usage.get(name);
            return v == null ? 0 : v;
        }
    }

    /** 读库目录的 usage.json（后台线程调用）。 */
    public void loadUsage() {
        Uri t = tree();
        java.util.HashMap<String, Integer> m = new java.util.HashMap<>();
        if (t != null) {
            org.json.JSONObject root = LibOps.readJson(ctx, t, "usage.json");
            org.json.JSONObject c = root.optJSONObject("counts");
            if (c != null) {
                java.util.Iterator<String> it = c.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    m.put(k, c.optInt(k, 0));
                }
            }
        }
        synchronized (usage) {
            usage.clear();
            usage.putAll(m);
        }
        Repo.log("使用次数已读：" + m.size() + " 条");
    }

    /** 攒 1.5 秒再写一次，别每发一张就写一遍库目录。 */
    private void scheduleUsageSave() {
        if (usageSaving) {
            return;
        }
        usageSaving = true;
        pool.execute(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            usageSaving = false;
            saveUsage();
        });
    }

    /** 把使用次数写回库目录的 usage.json（后台线程调用）。 */
    public void saveUsage() {
        Uri t = tree();
        if (t == null) {
            return;
        }
        try {
            org.json.JSONObject c = new org.json.JSONObject();
            synchronized (usage) {
                for (java.util.Map.Entry<String, Integer> e : usage.entrySet()) {
                    c.put(e.getKey(), e.getValue());
                }
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("counts", c);
            LibOps.writeJson(ctx, t, "usage.json", root);
        } catch (Exception e) {
            Repo.log("写 usage.json 失败 " + e);
        }
    }

    /** 表情被删掉后，它的使用次数也一起清掉。 */
    public void forgetUsage(java.util.Set<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        synchronized (usage) {
            for (String n : names) {
                usage.remove(n);
            }
        }
        scheduleUsageSave();
    }

    /** 预览模式下点一张：第一下只预览（返回 false），再点同一张才该发（返回 true）。 */
    public boolean tap(Sticker st) {
        if (getInt("preview", 0) != 1) {
            previewName = null;
            return true;
        }
        if (st.name.equals(previewName)) {
            return true;
        }
        previewName = st.name;
        return false;
    }

    public void clearPreview() {
        previewName = null;
    }

    public boolean previewing(String name) {
        return name != null && name.equals(previewName);
    }

    /** 表情被删掉后，从"最近使用"里也清出去。 */
    public void forget(java.util.Set<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        recent.removeAll(names);
        sp.edit().putString("recent", TextUtils.join("\n", recent)).apply();
    }

    public Bitmap cached(String name) {
        return cache.get(name);
    }

    public Bitmap thumb(Sticker st) {
        Bitmap b = cache.get(st.name);
        if (b != null) {
            return b;
        }
        b = decode(st);
        if (b != null) {
            cache.put(st.name, b);
        }
        return b;
    }

    private Bitmap decode(Sticker st) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(st.uri)) {
                BitmapFactory.decodeStream(in, null, probe);
            }
            int sample = 1;
            int max = Math.max(probe.outWidth, probe.outHeight);
            while (max / (sample * 2) >= 192 && sample < 32) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in = ctx.getContentResolver().openInputStream(st.uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Throwable t) {
            if (failLogs < 8) {
                failLogs++;
                log("缩略图解码失败 " + st.name + " -> " + t);
            }
            return null;
        }
    }
}
