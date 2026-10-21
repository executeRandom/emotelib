package com.lorem.picker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_TREE = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_NOTIF = 1003;
    private static final int REQ_IMPORT = 1004;
    private static final int REQ_PICK = 1005;
    private static final int NUM_COLS = 4;

    private Repo repo;
    private GridView grid;
    private TextView status;
    private LinearLayout emptyBox;
    private TextView emptyTitle, emptyHint;
    /** 记住这次要导哪一份；Shizuku 授权回来后自动接着跑。 */
    private String pendingSrc, pendingStamp, pendingLabel;
    private boolean pendingFull;

    /** 引导弹窗自己得能被里面的链接关掉，用一个单元素数组当引用。 */
    private final android.app.AlertDialog[] dlgHolder = new android.app.AlertDialog[1];
    private TextView statusHint;    // 下面那行操作提示
    private Spinner spFilter, spSort;
    private Button btBall, btImport;
    private View selBar, selBar2;
    private TextView selCount;
    private boolean selMode = false;
    private final java.util.Set<String> sel = new java.util.HashSet<>();
    private StickerAdapter adapter;
    private final List<Sticker> shown = new ArrayList<>();
    private int cell;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        repo = Repo.get(this);
        cell = getResources().getDisplayMetrics().widthPixels / NUM_COLS;
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setPadding(dp(6), dp(6), dp(6), 0);
        Button btReload = new Button(this);
        btReload.setText("刷新");
        btImport = new Button(this);
        btImport.setText("导入");
        Button btMore = new Button(this);
        btMore.setText("更多");
        btBall = new Button(this);
        btBall.setText("悬浮");
        row1.addView(btReload, lp(0, -2, 1f));
        row1.addView(btImport, lp(0, -2, 1f));
        row1.addView(btMore, lp(0, -2, 1f));
        row1.addView(btBall, lp(0, -2, 1f));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(dp(6), dp(4), dp(6), dp(2));
        spFilter = new Spinner(this);   // 内容在 rebuildFilterAdapter() 里填（含自定义分类）
        spSort = new Spinner(this);
        spSort.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"新→旧", "旧→新", "文件名", "最近"}));
        btMore.setOnClickListener(v -> showMore());
        row2.addView(spFilter, lp(0, -2, 1f));
        row2.addView(spSort, lp(0, -2, 1f));
        root.addView(row2);

        grid = new GridView(this);
        grid.setNumColumns(NUM_COLS);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new StickerAdapter(this, repo, shown, cell);
        grid.setAdapter(adapter);
        // 空状态：库是空的时候就教人怎么看使用提示
        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyTitle = new TextView(this);
        emptyTitle.setTextSize(16);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyHint = new TextView(this);
        emptyHint.setTextSize(13);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(0, dp(6), 0, 0);
        emptyBox.addView(emptyTitle);
        emptyBox.addView(emptyHint);
        root.addView(emptyBox, new LinearLayout.LayoutParams(-1, 0, 1f));
        emptyBox.setOnClickListener(v -> {
            if (repo.all.isEmpty()) {
                showWelcome(false);
            }
        });
        emptyBox.setVisibility(View.GONE);
        grid.setEmptyView(emptyBox);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 选择模式下才出现的操作条
        // 一行四个（无背景：267px 宽的整块都是点击区，够按）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), 0, dp(4), 0);
        Button btAll = flat("全选");
        Button btCat = flat("分类");
        Button btDel = flat("删除");
        Button btCancel = flat("返回");
        int barH = dp(52);
        bar.addView(btAll, lp(0, barH, 1f));
        bar.addView(btCat, lp(0, barH, 1f));
        bar.addView(btDel, lp(0, barH, 1f));
        bar.addView(btCancel, lp(0, barH, 1f));
        bar.setVisibility(View.GONE);
        selBar = bar;
        root.addView(bar);

        // 第二行：左边是已选计数，右边是“按使用次数批量选”
        LinearLayout bar2 = new LinearLayout(this);
        bar2.setOrientation(LinearLayout.HORIZONTAL);
        bar2.setGravity(Gravity.CENTER_VERTICAL);
        bar2.setPadding(dp(4), 0, dp(4), dp(4));
        selCount = new TextView(this);
        selCount.setTextSize(12);
        selCount.setPadding(dp(8), 0, dp(8), 0);
        Button btLess = flat("使用次数少于…");
        bar2.addView(selCount, lp(0, -2, 1f));
        bar2.addView(btLess, lp(-2, -2, 0f));
        bar2.setVisibility(View.GONE);
        selBar2 = bar2;
        root.addView(bar2);
        btLess.setOnClickListener(v -> askLessCount());
        btAll.setOnClickListener(v -> {
            sel.clear();
            for (Sticker st : shown) {
                sel.add(st.name);
            }
            paintSel();
        });
        btCancel.setOnClickListener(v -> exitSel());
        btCat.setOnClickListener(v -> pickCategory());
        btDel.setOnClickListener(v -> confirmDelete());

        // 一行统计：总数 + 当前分类，点一下就打开详情
        status = new TextView(this);
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(dp(8), dp(4), dp(8), 0);
        status.setOnClickListener(v -> showDetails());
        root.addView(status);
        statusHint = new TextView(this);
        statusHint.setTextSize(12);
        statusHint.setPadding(dp(8), dp(2), dp(8), dp(8));
        root.addView(statusHint);

        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top;
            int bottom;
            int left;
            int right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = ins.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = ins.getSystemWindowInsetLeft();
                top = ins.getSystemWindowInsetTop();
                right = ins.getSystemWindowInsetRight();
                bottom = ins.getSystemWindowInsetBottom();
            }
            v.setPadding(left + dp(4), top + dp(4), right + dp(4), bottom);
            return ins;
        });

        btImport.setOnClickListener(v -> showImportMenu());
        btReload.setOnClickListener(v -> load());
        btBall.setOnClickListener(v -> toggleBall());

        spSort.setSelection(repo.getInt("sort", 0));
        spFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                repo.putString("filterKey", filterKeyAt(pos));
                applyFilter();
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        spSort.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                repo.putInt("sort", pos);
                applyFilter();
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        grid.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) {
                return;
            }
            Sticker st = shown.get(pos);
            if (selMode) {
                if (!sel.remove(st.name)) {
                    sel.add(st.name);
                }
                paintSel();
                return;
            }
            if (!repo.tap(st)) {
                // 开了“点击预览”：第一下只把这张演出来，再点同一张才发送
                adapter.notifyDataSetChanged();
                refreshStatus();
                return;
            }
            doSend(st);
        });
        // 长按也能进入选择模式（顺手，符合直觉）
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < shown.size()) {
                if (!selMode) {
                    enterSel(shown.get(pos));
                } else {
                    if (!sel.remove(shown.get(pos).name)) {
                        sel.add(shown.get(pos).name);
                    }
                    paintSel();
                }
            }
            return true;
        });

        repo.pool.execute(() -> SendHelper.cleanOld(this));
        repo.putString("filterKey", "def"); // 启动时默认打开“默认”（未归类）
        repo.pool.execute(() -> {
            repo.loadCats();
            runOnUiThread(this::rebuildFilterAdapter);
        });

        // 调试后门（不影响正常使用）：
        //   --es import_test 'SAF:前缀'  → 从表情库里挑名字以该前缀开头的文件导入
        //   --es import_test '/某个目录' → 从该本地目录导入
        // 结果写到 App 内部 files/out/，可以用 adb exec-out 拉出来看
        final String testArg = getIntent() == null ? null : getIntent().getStringExtra("import_test");
        if (testArg != null) {
            repo.pool.execute(() -> {
                try {
                    List<Uri> srcs = new ArrayList<>();
                    if (testArg.startsWith("SAF:")) {
                        String prefix = testArg.substring(4);
                        List<Sticker> all = Lib.scan(this, repo.tree());
                        for (Sticker st : all) {
                            if (st.name.startsWith(prefix)) {
                                srcs.add(st.uri);
                            }
                        }
                        Importer.DEBUG_OUT = new java.io.File(getFilesDir(), "out");
                    } else {
                        java.io.File inDir = new java.io.File(testArg);
                        java.io.File[] fs = inDir.listFiles();
                        if (fs != null) {
                            java.util.Arrays.sort(fs);
                            for (java.io.File f : fs) {
                                if (f.isFile() && !f.getName().startsWith(".")) {
                                    srcs.add(Uri.fromFile(f));
                                }
                            }
                        }
                        Importer.DEBUG_OUT = new java.io.File(inDir, "out");
                    }
                    Repo.log("调试导入 文件数=" + srcs.size() + " 输出目录=" + Importer.DEBUG_OUT);
                    final long t0 = System.currentTimeMillis();
                    Importer.run(this, repo, srcs, null, (ok, fail, err) -> Repo.log(
                            "调试导入完成 ok=" + ok + " fail=" + fail + " 用时"
                                    + (System.currentTimeMillis() - t0) / 1000 + "秒 " + err));
                } catch (Exception e) {
                    Repo.log("调试导入异常 " + e);
                }
            });
        }

        // 另一个调试后门：--es ball on / off → 不用手点也能起停浮球（测前台检测用）
        final String ballArg = getIntent() == null ? null : getIntent().getStringExtra("ball");
        if (ballArg != null) {
            try {
                if ("off".equals(ballArg)) {
                    stopService(new Intent(this, OverlayService.class));
                } else {
                    startForegroundService(new Intent(this, OverlayService.class));
                    repo.putInt("ballOn", 1);
                }
                Repo.log("调试：浮球 " + ballArg);
            } catch (Exception e) {
                Repo.log("调试：浮球起停失败 " + e);
            }
        }

        if (repo.getInt("guided", 0) != 1) {
            repo.putInt("guided", 1);
            showWelcome(true);
        }

        if (repo.tree() == null) {
            setStatus("请先在“更多”里授权 EmoteLib 访问存储");
        } else {
            load();
        }
    }

    /** Shizuku 授权结果：点了“始终允许”就自动接着导入，不用再点一次。 */
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            (requestCode, grantResult) -> {
                if (requestCode == QQImport.REQ_SHIZUKU
                        && grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    if (pendingSrc != null) {
                        runOnUiThread(() -> startQqImport(pendingSrc, pendingFull, pendingStamp, pendingLabel));
                    }
                }
            };

    @Override
    protected void onDestroy() {
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (selMode) {
            exitSel();   // 选择模式下按返回 = 退出选择，不会把人卡住
            return;
        }
        if (repo.previewName != null) {
            repo.clearPreview();   // 预览中按返回 = 取消预览（不会发出去）
            adapter.notifyDataSetChanged();
            refreshStatus();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paintBallButton();
    }

    private void paintBallButton() {
        boolean can = Settings.canDrawOverlays(this);
        if (!can) {
            btBall.setText("悬浮（需要授权）");
        } else if (OverlayService.running) {
            btBall.setText("悬浮（开）");
        } else {
            btBall.setText("悬浮（关）");
        }
    }

    /** 无背景的扁平按钮：只有文字 + 按下时系统自带的水波纹，不占视觉重量。 */
    private Button flat(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(6), 0, dp(6), 0);
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true)
                && tv.resourceId != 0) {
            b.setBackground(getDrawable(tv.resourceId));
        } else {
            b.setBackgroundColor(0x00000000);
        }
        return b;
    }

    /** w=0 表示"宽度交给 weight 分配"；要让它按内容撑开就传 -2。 */
    private ViewGroup.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    // ---------- 浮球 ----------
    private void toggleBall() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授权应用显示在其他应用上层", Toast.LENGTH_LONG).show();
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            } catch (Exception e) {
                Toast.makeText(this, "授权页打开失败：" + e, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (OverlayService.running) {
            repo.putInt("ballOn", 0);   // 记着"用户自己关了球"，唤醒广播别把它又拉起来
            stopService(new Intent(this, OverlayService.class));
            btBall.postDelayed(this::paintBallButton, 400);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        try {
            startForegroundService(new Intent(this, OverlayService.class));
            repo.putInt("ballOn", 1);   // 记着"球是开着的"，被系统清掉后唤醒广播会把它拉回来
            Toast.makeText(this, "悬浮球已启动，拖拽以改变位置", Toast.LENGTH_SHORT).show();
            if (repo.getInt("ballqq", 1) == 1 && !OverlayService.hasUsageAccess(this)) {
                Toast.makeText(this, "想让悬浮球只在 QQ 前台出现的话，去开“使用情况访问”权限"
                        + "（先是一直显示的）", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "悬浮球启动失败：" + e, Toast.LENGTH_LONG).show();
        }
        btBall.postDelayed(this::paintBallButton, 600);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        paintBallButton();
    }

    /**
     * 说明页格式：富文本 + 可滚动容器。
     * showIcon=true 时用图标代替标题（只给使用引导用），否则走普通标题。
     */
    private android.app.AlertDialog showInfoDialog(CharSequence body, String title,
                                                    boolean showIcon, String positive,
                                                    android.content.DialogInterface.OnClickListener onOk,
                                                    String negative,
                                                    android.content.DialogInterface.OnClickListener onNegative) {
        final TextView tv = new TextView(this);
        tv.setTextSize(14);
        tv.setLineSpacing(dp(3), 1f);
        int pad = dp(20);
        tv.setPadding(dp(24), dp(4), dp(24), dp(8));
        tv.setText(body);
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (showIcon) {
            final android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(R.drawable.ic_launcher_foreground);
            int side = dp(72);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(side, side);
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            ilp.topMargin = dp(16);
            icon.setLayoutParams(ilp);
            box.addView(icon);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        box.addView(sv);

        final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton(positive, onOk);
        if (title != null) {
            b.setTitle(title);
        }
        if (negative != null) {
            b.setNegativeButton(negative, onNegative);
        }
        return b.show();
    }

    /** 使用引导：首次启动自动弹，也可以从“更多”里再看一次。图标代替标题。 */
    private void showWelcome(final boolean firstRun) {
        final String t = "欢迎使用 EmoteLib！\n\n"
                + "这是什么？\n"
                + "EmoteLib 是一个本地表情管理库，可以让您从这个应用中选择表情并直接发送。"
                + "同时，您还可以通过此应用管理您的表情，对其进行分类、删除与导入、"
                + "按使用频率排序、检查使用频率等操作。"
                + "EmoteLib 还提供一个悬浮球，点击后会展开一个面板，以此更便捷地发送表情。"
                + "EmoteLib 存储的表情将会转码为 GIF 格式，避免出现消息尺寸过大的问题。\n\n"
                + "如何使用？\n"
                + "要使用 EmoteLib，您首先需要向应用授权文件夹权限。点击这里按提示选择表情库路径。"
                + "这是因为自 Android 11 起，应用访问目录需要用户授权才能正常读写。"
                + "此后该应用的所有表情文件都将在此目录下存储。\n\n"
                + "之后您可以向库中导入表情。单击界面顶部的“导入”按钮，"
                + "可以从相册导入，也可以从 QQ 表情收藏导入，"
                + "或者扫描缓存找回已删除的表情。从 QQ 导入需要 Shizuku。\n\n"
                + "您随时可以在“更多”菜单中点击“使用引导”来查看这条提示。";
        final android.text.SpannableString body = new android.text.SpannableString(t);
        for (String h : new String[]{"这是什么？", "如何使用？"}) {
            int at = t.indexOf(h);
            if (at >= 0) {
                body.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        at, at + h.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        final String linkText = "点击这里";
        int link = t.indexOf(linkText);
        if (link >= 0) {
            body.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(android.view.View v) {
                    if (dlgHolder[0] != null) {
                        dlgHolder[0].dismiss();
                    }
                    showGrantGuide();
                }

                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setColor(0xFF5FB0FF);
                    ds.setUnderlineText(false);
                }
            }, link, link + linkText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        dlgHolder[0] = showInfoDialog(body, null, true,
                firstRun ? "去授权" : "好",
                (d, w) -> {
                    if (firstRun) {
                        showGrantGuide();
                    }
                },
                firstRun ? "稍后" : null, null);
    }
    /** 打开文件选择器之前的说明：告诉用户要去哪个目录。 */
    private void showGrantGuide() {
        final String t = "EmoteLib 需要您的授权来在指定的目录中管理文件。\n\n"
                + "接下来将弹出文件管理器，请前往：\n"
                + "存储 → Download → EmoteLib\n\n"
                + "并给予权限。";
        showInfoDialog(t, "授权访问存储", false, "继续",
                (d, w) -> pickFolder(), "取消", null);
    }

    /**
     * 首次使用时在「下载」里把 EmoteLib 目录建出来。
     * SAF 选择器里如果目录不存在，用户就没得选；用 MediaStore 插一个占位文件，
     * 系统会顺带建出目录，而且这一步不需要任何存储权限。
     */
    private void ensureLibDirExists() {
        try {
            final android.net.Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            final String rel = android.os.Environment.DIRECTORY_DOWNLOADS + "/EmoteLib/";
            final String[] proj = {android.provider.MediaStore.MediaColumns._ID};
            try (android.database.Cursor c = getContentResolver().query(collection, proj,
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{rel + "%"}, null)) {
                if (c != null && c.getCount() > 0) {
                    return;   // 目录已经在了
                }
            }
            final android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, ".keep");
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel);
            getContentResolver().insert(collection, cv);
            Repo.log("已建库目录 " + rel);
        } catch (Throwable t) {
            Repo.log("建库目录失败 " + t);
        }
    }
    // ---------- 选文件夹 / 读库 ----------
    private void pickFolder() {
        ensureLibDirExists();   // 先把目录建出来，不然选择器里选不到
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // 直接打开到“下载”，省得用户在文件树里找
        try {
            i.putExtra("android.provider.extra.INITIAL_URI",
                    android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Download"));
        } catch (Throwable ignored) {
        }
        // 要写入权限：导入新表情时要往这个目录里写文件
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "打开文件选择器失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY) {
            paintBallButton();
            return;
        }
        if (req == REQ_IMPORT) {
            if (res == RESULT_OK && data != null) {
                startImport(data);
            }
            return;
        }
        if (req == REQ_PICK) {
            handlePick(res, data);
            return;
        }
        if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            String docId = null;
            try {
                docId = android.provider.DocumentsContract.getTreeDocumentId(tree);
            } catch (Throwable ignored) {
            }
            // 表情库位置是定死的：下载/EmoteLib
            if (!QQImport.LIB_DOC_ID.equals(docId)) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("表情库位置")
                        .setMessage("EmoteLib 的表情库固定放在“下载/EmoteLib”。\n\n"
                                + "请在文件选择器里进入“下载”，新建一个 EmoteLib 文件夹，"
                                + "选中它再点“使用此文件夹”。")
                        .setPositiveButton("好", null)
                        .show();
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            repo.setTree(tree);
            load();
        }
    }

    /** “导入”按钮：两条来路，二选一。 */
    private void showImportMenu() {
        final String[] items = {
                "从相册导入…",
                "从 QQ 表情收藏导入…",
                "找回已删除的表情（扫描缓存）…"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("导入")
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        pickImages();
                    } else if (w == 1) {
                        startQqImport(QQImport.SRC_FAV, false, ".stamp-fav", "QQ 表情收藏");
                    } else {
                        startQqImport(QQImport.SRC_FAV, true, ".stamp-fav", "QQ 表情缓存");
                    }
                })
                .show();
    }
    // ---------- 导入新表情（手机上直接转成 GIF 存进库）----------
    private void pickImages() {
        if (repo.tree() == null) {
            Toast.makeText(this, "请先在“更多”里授权 EmoteLib 访问存储", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "打开文件选择器失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    private void startImport(Intent data) {
        final List<Uri> srcs = new ArrayList<>();
        if (data.getClipData() != null) {
            android.content.ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                srcs.add(cd.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            srcs.add(data.getData());
        }
        if (srcs.isEmpty()) {
            return;
        }
        for (Uri u : srcs) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        setStatus("正在转换 " + srcs.size() + " 张…");
        final long t0 = System.currentTimeMillis();
        Importer.run(this, repo, srcs,
                (msg, idx, total) -> runOnUiThread(() -> setStatus(msg)),
                (ok, fail, err) -> runOnUiThread(() -> {
                    long sec = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
                    String s = "导入完成：" + ok + " 张成功"
                            + (fail > 0 ? "，" + fail + " 张失败（" + err + "）" : "")
                            + "，用了 " + sec + " 秒";
                    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
                    load();
                }));
    }

    // ---------- 选择 / 删除 ----------
    private void enterSel(Sticker first) {
        selMode = true;
        sel.clear();
        repo.clearPreview();
        if (first != null) {
            sel.add(first.name);
        }
        adapter.selectMode = true;
        paintSel();
    }

    private void exitSel() {
        selMode = false;
        sel.clear();
        adapter.selectMode = false;
        adapter.selected.clear();
        adapter.notifyDataSetChanged();
        paintSel();
        applyFilter();   // 把状态文字恢复成列表统计
    }

    private void paintSel() {
        adapter.selected.clear();
        adapter.selected.addAll(sel);
        adapter.notifyDataSetChanged();
        selBar.setVisibility(selMode ? View.VISIBLE : View.GONE);
        selBar2.setVisibility(selMode ? View.VISIBLE : View.GONE);
        if (selMode) {
            selCount.setText("已选 " + sel.size() + " 张");
            statusHint.setText("返回键退出选择");
        }
    }

    /** 按使用次数批量选中：次数少于 N 的全选上。 */
    private void askLessCount() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint("例如 3");
        et.setPadding(dp(20), dp(8), dp(20), dp(8));
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择使用次数少于")
                .setMessage("把发送次数少于这个数字的表情全部选中。")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    final int n;
                    try {
                        n = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception e) {
                        Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (n <= 0) {
                        Toast.makeText(this, "请输入大于 0 的数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    sel.clear();
                    for (Sticker st : shown) {
                        if (repo.useCount(st.name) < n) {
                            sel.add(st.name);
                        }
                    }
                    paintSel();
                    Toast.makeText(this, "已选中 " + sel.size() + " 张", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    private void confirmDelete() {
        final List<Sticker> victims = new ArrayList<>();
        for (Sticker st : shown) {
            if (sel.contains(st.name)) {
                victims.add(st);
            }
        }
        if (victims.isEmpty()) {
            Toast.makeText(this, "请选择表情", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除表情")
                .setMessage("将永久删除选中的 " + victims.size()
                        + " 张表情，并从所有分类和最近使用中一并移除。"
                        + "此操作无法撤销，是否继续？")
                .setPositiveButton("删除", (d, w) -> doDelete(victims))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(final List<Sticker> victims) {
        setStatus("正在删除 " + victims.size() + " 张…");
        repo.pool.execute(() -> {
            String err = null;
            try {
                LibOps.delete(this, repo, victims);
            } catch (Exception e) {
                err = String.valueOf(e);
                Repo.log("删除出错 " + e);
            }
            final String e2 = err;
            runOnUiThread(() -> {
                Toast.makeText(this, e2 == null
                        ? "已删除 " + victims.size() + " 张"
                        : "删除出错：" + e2, Toast.LENGTH_LONG).show();
                exitSel();
                load();
            });
        });
    }

    // ---------- 自定义分类 ----------
    private List<String> filterEntries() {
        List<String> l = new ArrayList<>();
        l.add("全部");
        l.add("动图");
        l.add("静图");
        l.add("最近");
        l.add("未分类"); // 虚拟分类：还没归类的那些
        synchronized (repo.cats) {
            for (String n : repo.cats.keySet()) {
                l.add(n); // 用户写什么就显示什么，不加前缀
            }
        }
        return l;
    }

    private String filterKeyAt(int pos) {
        if (pos <= 0) {
            return "all";
        }
        if (pos == 1) {
            return "anim";
        }
        if (pos == 2) {
            return "still";
        }
        if (pos == 3) {
            return "recent";
        }
        if (pos == 4) {
            return "def";
        }
        List<String> l = filterEntries();
        if (pos < l.size()) {
            return "cat:" + l.get(pos);
        }
        return "all";
    }

    private void rebuildFilterAdapter() {
        List<String> l = filterEntries();
        String key = repo.getString("filterKey", "all");
        int sel = 0;
        for (int i = 0; i < l.size(); i++) {
            if (filterKeyAt(i).equals(key)) {
                sel = i;
                break;
            }
        }
        spFilter.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, l));
        spFilter.setSelection(sel);
    }

    private List<Sticker> selectedStickers() {
        List<Sticker> out = new ArrayList<>();
        for (Sticker st : shown) {
            if (sel.contains(st.name)) {
                out.add(st);
            }
        }
        return out;
    }

    /** 选中的图要放到哪个分类：也可以新建 / 管理 */
    private void pickCategory() {
        final List<Sticker> items = selectedStickers();
        if (items.isEmpty()) {
            Toast.makeText(this, "请选择表情", Toast.LENGTH_SHORT).show();
            return;
        }
        final String cur = repo.getString("filterKey", "all").startsWith("cat:")
                ? repo.getString("filterKey", "all").substring(4) : null;
        final List<String> names;
        synchronized (repo.cats) {
            names = new ArrayList<>(repo.cats.keySet());
        }
        final List<String> rows = new ArrayList<>();
        if (cur != null) {
            rows.add("从“" + cur + "”移出");
        }
        for (String n : names) {
            rows.add("加入“" + n + "”");
        }
        rows.add("＋ 新建分类…");
        rows.add("管理分类…");
        final int off = cur != null ? 1 : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle("选中的 " + items.size() + " 张")
                .setItems(rows.toArray(new String[0]), (d, w) -> {
                    if (off == 1 && w == 0) {
                        repo.removeFromCat(cur, items);
                        afterCatChange("已从“" + cur + "”移出 " + items.size() + " 张");
                    } else if (w - off < names.size()) {
                        String n = names.get(w - off);
                        repo.addToCat(n, items);
                        afterCatChange("已把 " + items.size() + " 张加入“" + n + "”");
                    } else if (w - off == names.size()) {
                        askName("新建分类", null, name -> {
                            if (Repo.RESERVED.contains(name)) {
                                Toast.makeText(this, "不能与内置筛选项重名",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (name.length() == 0) {
                                Toast.makeText(this, "名字不能空着", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (!repo.createCat(name)) {
                                Toast.makeText(this, "“" + name + "”已存在",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            repo.addToCat(name, items);
                            afterCatChange("已新建“" + name + "”并加入 " + items.size() + " 张");
                        });
                    } else {
                        manageCats();
                    }
                })
                .show();
    }

    private void afterCatChange(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        exitSel();
        repo.pool.execute(repo::saveCats);
        rebuildFilterAdapter();
        applyFilter();
    }

    private void manageCats() {
        final List<String> names;
        synchronized (repo.cats) {
            names = new ArrayList<>(repo.cats.keySet());
        }
        if (names.isEmpty()) {
            Toast.makeText(this, "还没有分类", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("管理分类")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    final String n = names.get(w);
                    int cnt;
                    synchronized (repo.cats) {
                        List<String> l = repo.cats.get(n);
                        cnt = l == null ? 0 : l.size();
                    }
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(n + "（" + cnt + " 张）")
                            .setItems(new String[]{"重命名", "删除该分类"}, (d2, w2) -> {
                                if (w2 == 0) {
                                    askName("重命名分类", n, nn -> {
                                        String n2 = nn == null ? "" : nn.trim();
                                        if (n2.length() == 0) {
                                            Toast.makeText(this, "名字不能空着",
                                                    Toast.LENGTH_SHORT).show();
                                        } else if (repo.renameCat(n, n2)) {
                                            afterCatChange("已改名为“" + n2 + "”");
                                        } else {
                                            Toast.makeText(this, "“" + n2 + "”已存在",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                } else {
                                    repo.deleteCat(n);
                                    afterCatChange("已删除分类“" + n + "”，表情仍保留");
                                }
                            })
                            .show();
                })
                .show();
    }

    // ---------- 更多 / 关于 ----------
    /** 导出诊断信息：设备、库状态、最近的日志。用户遇到问题时可以把它发回来。 */
    private void exportLog() {
        if (repo.tree() == null) {
            Toast.makeText(this, "请先在“更多”里授权 EmoteLib 访问存储", Toast.LENGTH_LONG).show();
            return;
        }
        final String name = "日志-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + ".txt";
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        repo.pool.execute(() -> {
            final String report = buildReport();
            final boolean ok = LibOps.writeText(this, repo.tree(), name, report);
            runOnUiThread(() -> {
                if (!ok) {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("导出失败")
                            .setMessage("写不进表情库目录（是不是没给写入权限？）。"
                                    + "可以先从“更多”重新授权一次存储。")
                            .setPositiveButton("好", null)
                            .show();
                    return;
                }
                new android.app.AlertDialog.Builder(this)
                        .setTitle("日志已导出")
                        .setMessage("文件已保存到“下载/EmoteLib/" + name + "”。\n\n"
                                + "可以直接把文件发给我们，也可以现在选择发送方式。")
                        .setPositiveButton("发送", (d, w) -> shareLog(report, name))
                        .setNegativeButton("好", null)
                        .show();
            });
        });
    }

    private void shareLog(String report, String name) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "EmoteLib " + name);
        i.putExtra(Intent.EXTRA_TEXT, report);
        try {
            startActivity(Intent.createChooser(i, "把日志发到…"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的发送方式", Toast.LENGTH_SHORT).show();
        }
    }

    /** 拼一份能看懂的诊断报告。 */
    private String buildReport() {
        StringBuilder b = new StringBuilder();
        String ver = "?";
        int code = 0;
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
            code = pi.versionCode;
        } catch (Exception ignored) {
        }
        int n = 0;
        synchronized (repo.all) {
            n = repo.all.size();
        }
        int nc = 0;
        synchronized (repo.cats) {
            nc = repo.cats.size();
        }
        b.append("EmoteLib 诊断信息\n");
        b.append("==============\n\n");
        b.append("版本：").append(ver).append("（").append(code).append("）\n");
        b.append("导出时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date())).append("\n");
        b.append("设备：").append(android.os.Build.MANUFACTURER).append(" ")
                .append(android.os.Build.MODEL).append("\n");
        b.append("系统：Android ").append(android.os.Build.VERSION.RELEASE)
                .append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）\n");
        try {
            b.append("架构：").append(android.os.Build.SUPPORTED_ABIS[0]).append("\n");
        } catch (Throwable ignored) {
        }
        b.append("\n");
        b.append("表情库：").append(n).append(" 张，").append(nc).append(" 个分类\n");
        b.append("库目录：").append(repo.tree()).append("\n");
        b.append("悬浮球：").append(OverlayService.running ? "运行中" : "未运行").append("\n");
        b.append("Shizuku：").append(QQImport.serviceUp() ? "已连接" : "未连接").append("\n");
        b.append("Shizuku 授权：").append(QQImport.ready() ? "已授权" : "未授权").append("\n");
        b.append("\n---------------- 操作日志 ----------------\n");
        b.append(tailLog(120 * 1024));
        return b.toString();
    }

    /** 取日志文件的最后一段。 */
    private String tailLog(int maxBytes) {
        java.io.File f = new java.io.File(getFilesDir(), "dbg.log");
        if (!f.exists()) {
            return "（没有日志）\n";
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long from = Math.max(0, len - maxBytes);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "（读取日志失败：" + e + "）\n";
        }
    }
    private void showMore() {
        final boolean pv = repo.getInt("preview", 0) == 1;
        final boolean bq = repo.getInt("ballqq", 1) == 1;
        final boolean su = repo.getInt("showUsage", 0) == 1;
        final boolean exempt = OverlayService.batteryExempt(this);
        final String[] items = {"关于 EmoteLib", "使用引导", "使用提示",
                "管理分类…",
                "授权 EmoteLib 访问存储…",
                pv ? "点击预览：开（单击预览）" : "点击预览：关（单击发送）",
                su ? "显示使用次数：开" : "显示使用次数：关",
                bq ? "悬浮球显示：只在 QQ 前台" : "悬浮球显示：一直显示",
                exempt ? "后台保活：已加入省电白名单" : "后台保活：加入省电白名单…",
                "导出日志"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("更多")
                .setItems(items, (d, w) -> {
                    // 按文字分发，以后增删条目不用再数下标
                    final String picked = items[w];
                    if (picked.startsWith("关于")) {
                        showAbout();
                    } else if (picked.startsWith("使用引导")) {
                        showWelcome(false);
                    } else if (picked.startsWith("管理分类")) {
                        manageCats();
                    } else if (picked.startsWith("授权 EmoteLib")) {
                        showGrantGuide();
                    } else if (picked.startsWith("导出日志")) {
                        exportLog();
                    } else if (picked.startsWith("使用提示")) {
                        showTips();
                    } else if (picked.startsWith("点击预览")) {
                        repo.putInt("preview", pv ? 0 : 1);
                        repo.clearPreview();
                        refreshStatus();
                        Toast.makeText(this, pv
                                ? "点击预览：关（单击发送）"
                                : "点击预览：开（单击预览，再次单击发送）",
                                Toast.LENGTH_LONG).show();
                        showMore();
                    } else if (picked.startsWith("显示使用次数")) {
                        repo.putInt("showUsage", su ? 0 : 1);
                        adapter.notifyDataSetChanged();
                        showMore();
                    } else if (picked.startsWith("悬浮球显示")) {
                        final int nv = bq ? 0 : 1;
                        repo.putInt("ballqq", nv);   // 球在跑的话，下一拍就生效，不用重启
                        if (nv == 1 && !OverlayService.hasUsageAccess(this)) {
                            Toast.makeText(this, "悬浮球要“只在 QQ 前台”出现，得先开"
                                    + "“使用情况访问”权限，我给你打开设置页了",
                                    Toast.LENGTH_LONG).show();
                            OverlayService.openUsageAccessSettings(this);
                        } else {
                            Toast.makeText(this, nv == 1
                                    ? "悬浮球改成只在 QQ 前台出现了"
                                    : "悬浮球改成一直显示了", Toast.LENGTH_LONG).show();
                            showMore();
                        }
                    } else if (picked.startsWith("后台保活")) {
                        if (exempt) {
                            Toast.makeText(this, "已经在省电白名单里了", Toast.LENGTH_SHORT).show();
                        } else {
                            new android.app.AlertDialog.Builder(this)
                                    .setTitle("后台保活")
                                    .setMessage("系统会冻结后台的应用，被冻住的时候悬浮球"
                                            + "不会自己回来。把它加进省电白名单"
                                            + "（忽略电池优化）就能避免。")
                                    .setPositiveButton("去申请", (d2, w2) ->
                                            OverlayService.requestBatteryExempt(this))
                                    .setNegativeButton("算了", null)
                                    .show();
                        }
                    } else {
                        manageCats();
                    }
                })
                .show();
    }

    /** 没装/没启动 Shizuku 时的说明：序号加粗，“官方源下载”链到发布页。 */
    private void showShizukuGuide() {
        final String t = "从 QQ 收藏导入需要使用 Shizuku。若您看到这条提示，可能是没有安装 Shizuku，"
                + "或 Shizuku 服务没有正常运行。若您尚未安装，请去官方源下载并安装最新版本，"
                + "之后进行以下步骤：\n\n"
                + "1. 根据情况，按给出的步骤启动 Shizuku 服务。\n\n"
                + "2. 回到 EmoteLib，再次点击“从 QQ 收藏导入…”。若 Shizuku 正常运行，"
                + "将会询问“允许 EmoteLib 使用 Shizuku”，选择始终允许。\n\n"
                + "3. EmoteLib 将只读取 QQ 的收藏表情，将其导出到临时目录下，并进行转码"
                + "以便作为表情发送，完成后删除临时文件。根据收藏表情的数量和大小，"
                + "可能会花费一点时间，期间将会提示进度。";
        android.text.SpannableString ss = new android.text.SpannableString(t);
        for (String n : new String[]{"1.", "2.", "3."}) {
            int at = t.indexOf(n);
            if (at >= 0) {
                ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        at, at + n.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        final String linkText = "官方源下载";
        int link = t.indexOf(linkText);
        if (link >= 0) {
            ss.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(android.view.View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                                "https://github.com/RikkaApps/Shizuku/releases")));
                    } catch (Throwable e) {
                        Toast.makeText(MainActivity.this, "打不开浏览器：" + e,
                                Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setColor(0xFF5FB0FF);
                    ds.setUnderlineText(false);
                }
            }, link, link + linkText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        showInfoDialog(ss, "需要 Shizuku", false, "知道了", null, null, null);
    }

    /** 从 shell 输出里取 copied / skip 两个数。 */
    private static int[] parseCopyCounts(String raw) {
        int copied = 0;
        int skipped = 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("copied=(\\d+)\\s+skip=(\\d+)").matcher(raw == null ? "" : raw);
            if (m.find()) {
                copied = Integer.parseInt(m.group(1));
                skipped = Integer.parseInt(m.group(2));
            }
        } catch (Throwable ignored) {
        }
        return new int[]{copied, skipped};
    }
    /** shell 吐的是 copied=29 skip=601，翻成人话。 */
    private static String parseCopyResult(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "已复制 0 项，跳过 0 项";
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("copied=(\\d+)\\s+skip=(\\d+)").matcher(raw);
            if (m.find()) {
                return "已复制 " + m.group(1) + " 项，跳过 " + m.group(2) + " 项";
            }
        } catch (Throwable ignored) {
        }
        return raw;
    }
    /** 从 QQ 收藏导入。先用 Shizuku 把文件搬进库目录，再由 app 转码入库。 */
    private void startQqImport(String src, boolean fullScan, String stampName, String label) {
        pendingSrc = src;
        pendingFull = fullScan;
        pendingStamp = stampName;
        pendingLabel = label;
        if (repo.tree() == null) {
            Toast.makeText(this, "请先去“更多”中授权 EmoteLib 访问存储", Toast.LENGTH_LONG).show();
            return;
        }
        if (!QQImport.serviceUp()) {
            showShizukuGuide();
            return;
        }
        if (!QQImport.ready()) {
            QQImport.requestPermission(this);
            return;
        }
        final String dir = QQImport.favDir();
        final String cmd = QQImport.copyCommand(repo.tree(), src, fullScan, stampName);
        if (cmd == null) {
            Toast.makeText(this, "表情库路径无效，请从“更多”按要求重新授权", Toast.LENGTH_LONG).show();
            return;
        }
        // 带进度条和图内取消按钮的弹窗
        final TextView msg = new TextView(this);
        msg.setTextSize(14);
        msg.setPadding(dp(20), dp(10), dp(20), dp(6));
        msg.setText("正在从" + label + "导入到暂存目录…");
        final android.widget.ProgressBar bar = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setPadding(dp(20), 0, dp(20), dp(10));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(msg);
        box.addView(bar);
        final boolean[] stop = {false};
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(label)
                .setView(box)
                .setNegativeButton("取消", (d, w) -> {
                    stop[0] = true;
                    Toast.makeText(this, "正在停止…", Toast.LENGTH_SHORT).show();
                })
                .setCancelable(false)
                .show();
        repo.pool.execute(() -> {
            // 1. shell 导出到暂存（顺带按 md5 去掉重复）。调试可跳过：--ez qq_skip_copy true
            final boolean skipCopy = getIntent() != null
                    && getIntent().getBooleanExtra("qq_skip_copy", false);
            final String pass;
            if (skipCopy) {
                pass = "skip copy";
            } else {
                final String[] r = QQImport.exec(cmd);
                pass = r[1] == null ? "" : r[1].trim();
            }
            // shell 已经在搬之前查过库了：命中重复的直接跳过、不进临时目录
            final int[] counts = parseCopyCounts(pass);
            final int copied = counts[0];
            final int skipped = counts[1];
            final int found = copied + skipped;

            // 2. 暂存目录里现在只剩「库里没有的」
            final java.util.LinkedHashMap<android.net.Uri, String> staged =
                    QQImport.listStaged(this, repo.tree());
            final java.util.HashSet<String> have = new java.util.HashSet<>();
            synchronized (repo.all) {
                for (Sticker s : repo.all) {
                    have.add(s.name);
                }
            }
            final int total = staged.size();

            if (staged.isEmpty()) {
                runOnUiThread(() -> {
                    dlg.dismiss();
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("没有新表情")
                            .setMessage("QQ 收藏里的 " + found + " 项都已经在库中，无需导入。")
                            .setPositiveButton("好", null)
                            .show();
                });
                return;
            }

            runOnUiThread(() -> {
                dlg.dismiss();
                Intent pick = new Intent(this, PickImportActivity.class);
                pick.putExtra("label", label);
                startActivityForResult(pick, REQ_PICK);
            });
        });
    }

    /** 预览页回来了：选中的进转换，没选中的直接从暂存删掉。 */
    private void handlePick(int res, Intent data) {
        final java.util.LinkedHashMap<Uri, String> staged = QQImport.listStaged(this, repo.tree());
        final java.util.HashSet<String> picked = new java.util.HashSet<>();
        if (res == RESULT_OK && data != null) {
            String[] arr = data.getStringArrayExtra(PickImportActivity.EXTRA_NAMES);
            if (arr != null) {
                for (String s : arr) {
                    picked.add(s);
                }
            }
        }
        final java.util.LinkedHashMap<Uri, String> keep = new java.util.LinkedHashMap<>();
        final int dropped = 0;
        for (java.util.Map.Entry<Uri, String> e : staged.entrySet()) {
            if (picked.contains(e.getValue())) {
                keep.put(e.getKey(), e.getValue());
            }
        }
        repo.pool.execute(() -> {
            int removed = 0;
            for (java.util.Map.Entry<Uri, String> e : staged.entrySet()) {
                if (!picked.contains(e.getValue())) {
                    QQImport.deleteStaged(this, e.getKey());
                    removed++;
                }
            }
            Repo.log("预览结果：保留 " + keep.size() + " 项，删除 " + removed + " 项");
            runOnUiThread(() -> {
                if (keep.isEmpty()) {
                    Toast.makeText(this, "没有选中任何项目，暂存已清空", Toast.LENGTH_LONG).show();
                    return;
                }
                startConvert(keep);
            });
        });
    }

    /** 弹出进度框，开始逐项转码。 */
    private void startConvert(final java.util.LinkedHashMap<Uri, String> staged) {
        final java.util.HashSet<String> have = new java.util.HashSet<>();
        synchronized (repo.all) {
            for (Sticker s : repo.all) {
                have.add(s.name);
            }
        }
        final int total = staged.size();
        final TextView msg = new TextView(this);
        msg.setTextSize(14);
        msg.setPadding(dp(20), dp(10), dp(20), dp(6));
        msg.setText("准备转换…");
        final android.widget.ProgressBar bar = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setPadding(dp(20), 0, dp(20), dp(10));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(msg);
        box.addView(bar);
        final boolean[] stop = {false};
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("导入中")
                .setView(box)
                .setNegativeButton("取消", (d, w) -> {
                    stop[0] = true;
                    Toast.makeText(this, "正在停止…", Toast.LENGTH_SHORT).show();
                })
                .setCancelable(false)
                .show();
        runConvert(dlg, msg, bar, stop, staged, have, total);
    }
    /** 把暂存目录里的东西逐项转码入库。 */
    private void runConvert(final android.app.AlertDialog dlg, final TextView msg,
                            final android.widget.ProgressBar bar, final boolean[] stop,
                            final java.util.LinkedHashMap<android.net.Uri, String> staged,
                            final java.util.HashSet<String> have, final int total) {
        repo.pool.execute(() -> {
            final int[] n = {0, 0, 0};   // 入库 / 跳过 / 失败
            int i = 0;
            for (java.util.Map.Entry<android.net.Uri, String> e : staged.entrySet()) {
                if (stop[0]) {
                    break;
                }
                i++;
                final int at = i;
                final String nm = e.getValue() == null ? "" : e.getValue();
                final String target = targetOf(nm);
                if (have.contains(target)) {
                    QQImport.deleteStaged(this, e.getKey());
                    n[1]++;
                } else {
                    try {
                        Importer.importOne(this, repo, e.getKey());
                        have.add(target);
                        n[0]++;
                    } catch (Exception ex) {
                        n[2]++;
                        Repo.log("QQ 导入失败 " + nm + " -> " + ex);
                    }
                    QQImport.deleteStaged(this, e.getKey());
                }
                if (at % 3 == 0 || at == total) {
                    final int pct = total == 0 ? 100 : (int) (at * 100L / total);
                    final String line = "共 " + total + " 项待处理，正在处理第 " + at + " 项（" + pct + "%）\n"
                            + "已成功 " + n[0] + "，已存在 " + n[1] + "，失败 " + n[2];
                    runOnUiThread(() -> {
                        msg.setText(line);
                        bar.setProgress(pct);
                    });
                }
            }

            final boolean canceled = stop[0];
            runOnUiThread(() -> {
                dlg.dismiss();
                load();
                new android.app.AlertDialog.Builder(this)
                        .setTitle(canceled ? "已取消" : "导入完成")
                        .setMessage("新增 " + n[0] + " 项 ／ 跳过 " + n[1] + " 项 ／ 失败 " + n[2] + " 项"
                                + (canceled ? "\n\n剩余项目保留在暂存目录，下次导入会继续处理。" : ""))
                        .setPositiveButton("好", null)
                        .show();
            });
        });
    }

    /** md5.xxx → md5.gif，用来和库里的名字比对。 */
    private static String targetOf(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + ".gif";
    }
    private void showAbout() {
        String ver = "0.1";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        int n;
        synchronized (repo.all) {
            n = repo.all.size();
        }
        int nc;
        synchronized (repo.cats) {
            nc = repo.cats.size();
        }
        final String mine = "EmoteLib  v" + ver + "\n\n"
                + "本地表情库：图片全在这台手机里，不上云。\n"
                + "当前 " + n + " 张，分 " + nc + " 个分类。\n\n"
                + "开发 · 洛勒姆（洛然 / Lorem Ipsum）\n"
                + "这是我写的第一个安卓应用。\n\n"
                + "—— 2026 年 10 月，给 Hiro";
        final String author = "接触 AI 之后的第二个和洛勒姆一起完成的小应用。"
                + "起初只是因为 QQ 的 500 个表情上限完全不够用，"
                + "所以突然冒出来了一个“能不能做一个外接的存表情的地方”的想法。\n\n"
                + "其实一开始是在电脑上让洛勒姆写了个比较简略的程序，"
                + "后来发现手机上也得有一个，于是就问洛勒姆："
                + "“你会写安卓的应用吗？按这个程序的模样写一个示例看看？”\n\n"
                + "非常惊人地，他直接写出了一个基础但非常简洁而清晰的界面。"
                + "我承认有些许愿式编程了，但这个界面看上去确实像那么回事。\n\n"
                + "不过它还缺了很多功能，于是就开始给洛勒姆提更多详细的要求，"
                + "一遍遍改，不断完善，从功能到界面再到（存在感不高的）图标、文本等细节。"
                + "很多功能除了日常使用的时候发现“这里不方便”以外，"
                + "其实更多是突然冒出来的“我需要这个！”，然后就给洛勒姆说了。\n\n"
                + "过程中也有他自己的想法，加入了一些好用的小东西，效果都很棒。"
                + "一起修 bug 的时候就像是和他坐在一起捣鼓一样，很好玩。说：谢谢洛勒姆！\n\n"
                + "目前还在测试，如果有 bug 或者不顺手的地方记得联系我！祝你使用愉快！"
                + "（你能拿到这个版本的话大概率你是知道该怎么联系我的）";
        final String all = mine + "\n\n——————————\n\n" + author;

        showInfoDialog(all, "关于", false, "好", null, null, null);
    }

    private void showTips() {
        String s = "导入表情\n"
                + "单击“导入”后根据情况选择从相册导入，或从 QQ 表情收藏导入。"
                + "若从 QQ 导入需要 Shizuku 服务，请按提示步骤进行。"
                + "部分系统可能不支持此功能。\n"
                + "导入后，程序将其统一转码为 GIF，便于在 QQ 界面以表情格式发送。"
                + "根据数量和大小，可能需要一些时间才能完成导入。\n\n"
                + "发送表情\n"
                + "单击表情跳转到 QQ 的“发送给”页，选择聊天窗口发送。\n"
                + "“点击预览”打开时，单击将会播放该表情，再次单击以发送。\n\n"
                + "管理表情\n"
                + "长按表情进入选择模式，可对表情进行多选、删除、归类等操作，按返回键退出。\n"
                + "选中后单击“分类”，将表情放进已有分类，或新建分类。一张表情可以同时属于多个分类。\n\n"
                + "悬浮球\n"
                + "单击“悬浮”打开悬浮球，打开后单击悬浮球展开面板，发送操作与在应用中时相同。\n"
                + "在“更多”中可以切换“悬浮球显示”。“只在 QQ 前台”打开时，悬浮球在 QQ 以外的界面将自动隐藏。";
        android.text.SpannableString body = new android.text.SpannableString(s);
        for (String h : new String[]{"导入表情", "发送表情", "管理表情", "悬浮球"}) {
            int at = s.indexOf(h);
            if (at >= 0) {
                body.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        at, at + h.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        showInfoDialog(body, "使用提示", false, "好", null, null, null);
    }

    private void askName(String title, String initial,
                         java.util.function.Consumer<String> cb) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("分类名");
        if (initial != null) {
            et.setText(initial);
            et.setSelection(initial.length());
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> cb.accept(et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 真的送出去：记一次使用（“常用”排序和“最近”都靠它），再交给 QQ。 */
    private void doSend(Sticker st) {
        repo.markUsed(st.name);
        final boolean recentTab = spFilter.getSelectedItemPosition() == 3;
        final boolean sent = SendHelper.send(this, st);
        repo.clearPreview();
        if (sent && recentTab) {
            applyFilter();
        } else {
            adapter.notifyDataSetChanged();
            refreshStatus();
        }
    }

    private void load() {
        if (repo.tree() == null) {
            return;
        }
        setStatus("正在读取…");
        repo.load((n, e) -> runOnUiThread(() -> {
            if (e != null) {
                setStatus("读取失败：" + e);
            } else {
                rebuildFilterAdapter();
                applyFilter();
            }
        }));
    }

    private void applyFilter() {
        repo.clearPreview();   // 列表一重排，预览的那张就作废
        final String key = repo.getString("filterKey", "all");
        final int s = spSort.getSelectedItemPosition();
        List<Sticker> src;
        synchronized (repo.all) {
            src = new ArrayList<>(repo.all);
        }
        final String catName = key.startsWith("cat:") ? key.substring(4) : null;
        List<String> catItems = null;
        if (catName != null) {
            synchronized (repo.cats) {
                List<String> l = repo.cats.get(catName);
                catItems = l == null ? new ArrayList<String>() : new ArrayList<>(l);
            }
        }
        java.util.HashSet<String> grouped = key.equals("def") ? repo.groupedNames() : null;
        shown.clear();
        for (Sticker st : src) {
            if (key.equals("anim") && !st.isAnim()) {
                continue;
            }
            if (key.equals("still") && st.isAnim()) {
                continue;
            }
            if (key.equals("recent") && !repo.recent.contains(st.name)) {
                continue;
            }
            if (catItems != null && !catItems.contains(st.name)) {
                continue;
            }
            if (grouped != null && grouped.contains(st.name)) {
                continue;
            }
            shown.add(st);
        }
        if (key.equals("recent")) {
            Collections.sort(shown, (a, b) -> repo.recent.indexOf(a.name) - repo.recent.indexOf(b.name));
        } else if (s == 1) {
            Collections.sort(shown, (a, b) -> Long.compare(a.mtime, b.mtime));
        } else if (s == 2) {
            Collections.sort(shown, (a, b) -> a.name.compareTo(b.name));
        } else if (s == 3) {
            // 常用：点得多的排前面，一样多就新的在前
            Collections.sort(shown, (a, b) -> {
                int c = repo.useCount(b.name) - repo.useCount(a.name);
                return c != 0 ? c : Long.compare(b.mtime, a.mtime);
            });
        } else {
            Collections.sort(shown, (a, b) -> Long.compare(b.mtime, a.mtime));
        }
        adapter.notifyDataSetChanged();
        refreshStatus();
    }

    /** 点底部统计文字打开的详情。时间取的是文件最后修改时间。 */
    private void showDetails() {
        final String key = repo.getString("filterKey", "all");
        List<Sticker> src;
        synchronized (repo.all) {
            src = new ArrayList<>(repo.all);
        }
        int anim = 0;
        long libTime = 0;
        for (Sticker st : src) {
            if (st.isAnim()) {
                anim++;
            }
            if (st.mtime > libTime) {
                libTime = st.mtime;
            }
        }
        int ncat;
        synchronized (repo.cats) {
            ncat = repo.cats.size();
        }
        long shownTime = 0;
        for (Sticker st : shown) {
            if (st.mtime > shownTime) {
                shownTime = st.mtime;
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("共 ").append(src.size()).append(" 张（")
                .append(anim).append(" 张动图，").append(src.size() - anim).append(" 张静图）\n")
                .append("自定义分类：").append(ncat).append(" 个\n")
                .append("表情库上次修改时间：").append(dayOf(libTime));
        if (!key.equals("all")) {
            b.append("\n").append("该分类上次修改时间：").append(dayOf(shownTime));
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("详情")
                .setMessage(b.toString())
                .setPositiveButton("好", null)
                .show();
    }

    private static String dayOf(long ms) {
        if (ms <= 0) {
            return "—";
        }
        return new java.text.SimpleDateFormat("yyyy/MM/dd",
                java.util.Locale.getDefault()).format(new java.util.Date(ms));
    }

    /** 行内显示用：超过六个字就截断加省略号，长得离谱的分类名也不会把这一行撑坏。 */
    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 6 ? s : s.substring(0, 6) + "…";
    }

    /** 一行式状态：只写左侧那句，下面的提示一起清掉。 */
    private void setStatus(String left) {
        status.setText(left);
        statusHint.setText("");
    }

    /** 底部那行状态：预览中的话，先讲清楚怎么收场。 */
    private void refreshStatus() {
        if (repo.previewName != null) {
            setStatus("预览中，再次单击发送到 QQ，返回键取消");
            return;
        }
        final String key = repo.getString("filterKey", "all");
        final String catName = key.startsWith("cat:") ? key.substring(4) : null;
        List<Sticker> src;
        synchronized (repo.all) {
            src = new ArrayList<>(repo.all);
        }
        int anim = 0;
        for (Sticker st : src) {
            if (st.isAnim()) {
                anim++;
            }
        }
        int ncat;
        synchronized (repo.cats) {
            ncat = repo.cats.size();
        }
        // 内置筛选项各有各的名字，别一律写成“全部”
        String where;
        if (catName != null) {
            where = catName;
        } else if (key.equals("def")) {
            where = "未分类";
        } else if (key.equals("anim")) {
            where = "动图";
        } else if (key.equals("still")) {
            where = "静图";
        } else if (key.equals("recent")) {
            where = "最近";
        } else {
            where = "全部";
        }
        status.setText("共 " + src.size() + " 张　" + shorten(where)
                + " 共 " + shown.size() + " 张");
        if (emptyTitle != null) {
            if (src.isEmpty()) {
                emptyTitle.setText("还没有表情");
                emptyHint.setText("点击查看如何使用");
            } else {
                emptyTitle.setText("没有符合条件的表情");
                emptyHint.setText("");
            }
        }
        statusHint.setText(repo.getInt("preview", 0) == 1
                ? "单击预览，再次单击发送到 QQ，长按管理"
                : "单击选中发送到 QQ，长按管理");
    }
}
