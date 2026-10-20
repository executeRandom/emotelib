package com.lorem.picker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 贴在屏幕边上的浮球：点开是表情网格，点一张直接发给 QQ。 */
public class OverlayService extends Service {

    public static volatile boolean running = false;
    private static volatile OverlayService inst = null;   // 给唤醒广播 poke 用

    private static final String TAG = "picker";
    private static final String CHANNEL = "picker_ball";
    private static final int PANEL_W = 340;
    private static final int PANEL_H = 470;
    private static final int BALL = 50;

    private WindowManager wm;
    private Repo repo;
    private View ball;
    private WindowManager.LayoutParams ballLp;
    private WindowManager.LayoutParams panelLp;

    /** 面板只在展开时存在：点开现搭，收起整块拆掉。 */
    private View panel;
    private GridView grid;
    private StickerAdapter adapter;
    private TextView[] tabs;
    private View head;
    private int cell;

    private final List<Sticker> shown = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String filter = "all"; // all/anim/recent/def/cat:名字
    private int ballX, ballY;
    private LinearLayout chipsRow; // 分类小标签行

    // ---- “只在 QQ 前台显示”----
    // 快切应用时球跟不上手（看着就像“不显示”）根子在这。原来平时 0.7 秒一拍，
    // 实测切出去要将近一秒球才反应。现在平时 0.3 秒，察觉切换后两秒内提速到 0.12 秒：
    // 切过去那一下要快，稳定待着的时候省电（0.3 秒实测约 1% 单核，0.2 秒要 1.5%）。
    private static final long POLL_MS = 300;
    private static final long POLL_FAST_MS = 120;
    private static final long POLL_WAIT_MS = 300;     // 球正藏着、你在别的应用里：等着盼你回 QQ
    private static final long POLL_IDLE_MS = 2000;    // 球藏着 + 屏幕关着：没什么可看的，省电
    private static final long BURST_MS = 2000;
    private static final long POLL_STUCK_MS = 3000;   // 一拍超这么久没回来，允许重发
    private static final String QQ_PKG = "com.tencent.mobileqq";
    private boolean gateOn = true;            // 用户开了“只在 QQ 前台显示”
    private boolean usageOk = false;          // 有没有“使用情况访问”权限
    private boolean qqFront = true;           // 上次判定：QQ 在前台
    private volatile boolean allowed = true;  // 现在准不准球露面
    private volatile long burstUntil = 0;     // 提速轮询的截止时刻
    private volatile long pollStart = 0;
    private String fgPkg = null;              // 上次扫到的前台包名
    private long lastEventTs = 0;             // 那个前台事件自己的时间戳（诊断用）
    private String evTail = "";               // 最近几个前台事件（诊断用）
    private int nullStreak = 0;               // 连续几拍读不到前台事件
    // 记住最近一次读到的前台包名。系统的事件窗口过期后 queryEvents 会返回空，
    // 这时不能当成“不知道”，否则球会在桌面上莫名其妙冒出来。
    private volatile String lastKnownPkg = null;
    private volatile long lastTickAt = 0;     // 主线程上一次轮询的时刻（诊断用）

    private final Runnable poller = new Runnable() {
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Repo.log("前台检测出错（忽略） " + t);
            }
        }
    };

    private volatile boolean polling = false;   // 一次检测还没回来就别再发一次
    private int tickN = 0;

    /** 看一次：该不该让球露面。问系统的那步丢到后台线程，别卡住浮窗。 */
    private void tick() {
        long now = System.currentTimeMillis();
        // 节奏：刚察觉切换 → 0.2 秒抢速度；球正藏着（在别的应用里）→ 0.3 秒，早点盼到 QQ；
        // 球藏着且屏幕关着 → 2 秒省电；其余 0.7 秒。
        long period;
        if (now < burstUntil) {
            period = POLL_FAST_MS;
        } else if (!allowed) {
            period = screenOn() ? POLL_WAIT_MS : POLL_IDLE_MS;
        } else {
            period = POLL_MS;
        }
        ui.postDelayed(poller, period);
        lastTickAt = now;
        if (tickN > 0 && tickN % 40 == 0) {          // 心跳：日志里能看出轮询还活着
            Repo.log("心跳 第" + tickN + "拍 " + shortPkg(fgPkg) + " allowed=" + allowed
                    + (now < burstUntil ? "（提速中）" : ""));
        }
        if (repo.getInt("ballqq", 1) != 1) {   // 没开这个限制 → 一直显示
            gateOn = false;
            if (!allowed) {
                allowed = true;
                applyBall();
            }
            return;
        }
        gateOn = true;
        heal();                                 // 窗口被系统摘掉就自救，否则球永远不出现
        if (polling && now - pollStart < POLL_STUCK_MS) {
            return;
        }
        polling = true;
        pollStart = now;
        final boolean checkPerm = (tickN++ % 5 == 0);   // 权限那步 3.5 秒查一次就够
        repo.pool.execute(() -> {
            boolean ok = usageOk;
            String pkg = fgPkg;
            long ts = lastEventTs;
            try {
                if (checkPerm) {
                    ok = hasUsageAccess(OverlayService.this);
                }
                pkg = lastForegroundPkg();
                ts = lastEventTs;
            } catch (Throwable t) {
                Repo.log("前台检测出错（忽略） " + t);
            }
            final boolean fok = ok;
            final String fpkg = pkg;
            final long fts = ts;
            ui.post(() -> {
                polling = false;
                usageOk = fok;
                boolean changed = fpkg != null && !fpkg.equals(fgPkg);
                if (fpkg != null) {
                    fgPkg = fpkg;          // 读不到时别把上次记住的包名抹成 null
                }
                if (changed) {
                    // 刚发现切换：接下来三秒提速轮询，把这一轮来回切的状态尽快追平
                    burstUntil = System.currentTimeMillis() + BURST_MS;
                    Repo.log("前台变化 → " + shortPkg(fpkg) + "（事件落盘延迟 "
                            + (System.currentTimeMillis() - fts) + "ms）" + evTail);
                }
                boolean qq;
                if (fpkg == null) {
                    // 读不到事件就维持上次判断。以前是连着三拍读不到就把球放出来，
                    // 结果人停在桌面十几分钟、系统事件窗口过期，球就自己冒出来赖着不走。
                    nullStreak++;
                    qq = qqFront;
                    if (nullStreak == 3) {
                        Repo.log("前台连续读不到（3 拍）→ 维持上次判断 qq=" + qq);
                    }
                } else {
                    nullStreak = 0;
                    qq = isQq(fpkg);
                    qqFront = qq;
                }
                boolean before = allowed;
                allowed = !fok || qq;
                if (allowed != before) {
                    Repo.log("浮球显示 → " + (allowed ? "显示" : "藏起来（当前不在 QQ）")
                            + " 依据=" + shortPkg(fpkg));
                }
                if (!allowed && panel != null) {
                    hidePanel();
                }
                applyBall();
            });
        });
    }

    /** 球该露面时才露面（面板开着时球本来就藏着）。 */
    private void applyBall() {
        if (ball == null) {
            return;
        }
        try {
            boolean panelUp = panel != null && panel.getParent() != null;
            ball.setVisibility(allowed && !panelUp ? View.VISIBLE : View.GONE);
        } catch (Exception ignored) {
        }
    }

    /** 自查：窗口被系统摘掉（快速切换应用时可能发生）就自己挂回来，否则球会永远不出现。 */
    private void heal() {
        try {
            if (panel != null && panel.getParent() == null) {
                Repo.log("面板窗口已不在（自愈，把球放出来）");
                panel = null;
                grid = null;
                adapter = null;
                tabs = null;
                head = null;
            }
            if (ball != null && ball.getParent() == null) {
                wm.addView(ball, ballLp);
                Repo.log("球窗口被摘掉（自愈，重新挂回）");
            }
        } catch (Throwable t) {
            Repo.log("自愈失败（忽略） " + t);
        }
    }

    /** 最近一个“切到前台”的是谁：先看 6 秒窗口，空就退到 60 秒，再空就用上次记住的。
     *  读不到返回 null 只在冷启动、一次都没读到过时发生。 */
    private String lastForegroundPkg() {
        String last = lastForeground(6000);
        if (last == null) {
            last = lastForeground(60 * 1000);
        }
        if (last != null) {
            lastKnownPkg = last;
            return last;
        }
        return lastKnownPkg;
    }

    private static boolean isQq(String pkg) {
        return pkg != null && (pkg.startsWith(QQ_PKG) || pkg.equals("com.tencent.qqlite")
                || pkg.equals("com.tencent.tim"));
    }

    private static String shortPkg(String pkg) {
        if (pkg == null) {
            return "null";
        }
        return pkg.startsWith("com.tencent.") ? pkg.substring("com.tencent.".length()) : pkg;
    }

    /** 屏幕亮着吗（关屏时没必要勤查前台，省点电）。 */
    private boolean screenOn() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 取最近一个“切到前台”事件是谁（顺带记下事件时间与最近几个事件，方便事后查）。 */
    private String lastForeground(long windowMs) {
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                    getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = usm.queryEvents(now - windowMs, now);
            if (ev == null) {
                return null;
            }
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String last = null;
            java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                if (t == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                        || t == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = e.getPackageName();
                    lastEventTs = e.getTimeStamp();
                    recent.addFirst(shortPkg(last) + "@" + (now - lastEventTs) + "ms");
                    while (recent.size() > 4) {
                        recent.removeLast();
                    }
                }
            }
            evTail = recent.isEmpty() ? "" : "；最近事件 " + String.join(" ", recent);
            return last;
        } catch (Throwable t) {
            return null;
        }
    }

    /** “使用情况访问”是特殊权限，没开就只能一直显示球。 */
    public static boolean hasUsageAccess(Context c) {
        try {
            android.app.AppOpsManager aom = (android.app.AppOpsManager)
                    c.getSystemService(Context.APP_OPS_SERVICE);
            if (aom != null) {
                int mode = aom.unsafeCheckOpNoThrow("android:get_usage_stats",
                        android.os.Process.myUid(), c.getPackageName());
                if (mode == android.app.AppOpsManager.MODE_ALLOWED) {
                    return true;
                }
            }
            // 有些 ROM 的 op 判定不准，再用事实复核一次：能读到别的事件就算给了
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                    c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = usm.queryEvents(now - 60 * 60 * 1000, now);
            return ev != null && ev.hasNextEvent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把用户送去“使用情况访问”设置页（让他自己找 EmojiLib 那一项）。 */
    public static void openUsageAccessSettings(Context c) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Repo.log("打不开使用情况访问设置页 " + e);
        }
    }
    /** true = 球在上半屏，面板向下长（表头在顶）；false = 球在下半屏，面板向上长（表头在底，贴着球）。 */
    private boolean growDown = true;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        inst = this;
        // 崩溃前先把堆栈写进日志，方便事后查
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Repo.log("!!! 未捕获异常 " + e + "\n" + Log.getStackTraceString(e));
            if (prev != null) {
                prev.uncaughtException(t, e);
            }
        });
        repo = Repo.get(this);
        gateOn = repo.getInt("ballqq", 1) == 1;
        usageOk = hasUsageAccess(this);
        // 冷启动先补一次长时间窗，别拿“一次都没读到”当“不在 QQ”。
        // 放后台线程：12 小时的 queryEvents 在 ColorOS 上可能要几百毫秒。
        repo.pool.execute(() -> {
            try {
                String seed = lastForeground(12 * 60 * 60 * 1000);
                if (seed != null) {
                    lastKnownPkg = seed;
                    Repo.log("冷启动补到前台: " + shortPkg(seed));
                }
            } catch (Throwable ignored) {
            }
        });
        filter = repo.getString("ovfilterKey", "all");
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(1, buildNotification());
        makeBall();
        repo.pool.execute(repo::loadCats);
        repo.load((n, e) -> {
            Repo.log("浮球读库返回 count=" + n + " err=" + e);
            if (e != null) {
                Log.e(TAG, "浮球读库失败", e);
            }
        });
        ui.postDelayed(poller, 500);
        // 进程级心跳（独立线程）：它要是也停了，说明整个 App 被系统冻住了，
        // 而不是我们的轮询逻辑有问题——排查“球不显示”先看这两条心跳。
        Thread hb = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    return;
                }
                Repo.log("心跳(线程) 活着 tick=" + tickN + " 上次轮询=" + lastTickAt);
            }
        }, "heartbeat");
        hb.setDaemon(true);
        hb.start();
        Repo.log("浮球已启动 gateOn=" + gateOn + " 有使用情况访问权限=" + usageOk);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        // 从“被冻住/被清掉又拉起来”的状态回来时，立刻重判一次，别等下一拍
        poke();
        return START_STICKY;
    }

    /** 立刻重判一次前台（唤醒广播、服务重启时用）。 */
    public static void poke() {
        OverlayService s = inst;
        if (s != null) {
            s.ui.removeCallbacks(s.poller);
            s.ui.post(s.poller);
        }
    }

    /** 有没有拿到“忽略电池优化”豁免——ColorOS 就是拿这个决定要不要冻我们的。 */
    public static boolean batteryExempt(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把用户送去“忽略电池优化”的确认框；国产 ROM 上这是球能不能一直活着的关键。 */
    public static void requestBatteryExempt(Context c) {
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Repo.log("申请电池优化豁免失败，改开设置列表 " + e);
            try {
                Intent i = new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception e2) {
                Repo.log("连设置列表也打不开 " + e2);
            }
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (inst == this) {
            inst = null;
        }
        ui.removeCallbacks(poller);
        hidePanel();
        try {
            if (ball != null) {
                wm.removeView(ball);
                ball = null;
            }
        } catch (Exception ignored) {
        }
        Repo.log("浮球已停止");
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "EmoteLib 悬浮球",
                    NotificationManager.IMPORTANCE_MIN));
        }
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("EmoteLib")
                .setContentText("悬浮球开启，点击悬浮球展开表情选择界面")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)
                .build();
    }

    // ---------- 浮球 ----------
    private void makeBall() {
        // 球上放图标（原来写的是"表情"两个字）
        ImageView t = new ImageView(this);
        t.setImageResource(R.drawable.ball_icon);
        t.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ipad = dp(9); // 50dp 的球里放 32dp 图标（96px @3.0 密度）
        t.setPadding(ipad, ipad, ipad, ipad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC2A2A2A);
        bg.setCornerRadius(dp(BALL) / 2f);
        bg.setStroke(dp(1), 0x55FFFFFF);
        t.setBackground(bg);

        ballLp = new WindowManager.LayoutParams(dp(BALL), dp(BALL),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.START;
        ballX = repo.getInt("ballx", getResources().getDisplayMetrics().widthPixels - dp(BALL + 6));
        ballY = repo.getInt("bally", dp(420));
        ballLp.x = ballX;
        ballLp.y = ballY;

        t.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = ballLp.x;
                        startY = ballLp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int nx = startX + (int) (e.getRawX() - downX);
                        int ny = startY + (int) (e.getRawY() - downY);
                        if (Math.abs(e.getRawX() - downX) > dp(6)
                                || Math.abs(e.getRawY() - downY) > dp(6)) {
                            moved = true;
                        }
                        ballLp.x = nx;
                        ballLp.y = ny;
                        try {
                            wm.updateViewLayout(ball, ballLp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            ballX = ballLp.x;
                            ballY = ballLp.y;
                            repo.putInt("ballx", ballX);
                            repo.putInt("bally", ballY);
                        } else {
                            try {
                                togglePanel();
                            } catch (Exception ex) {
                                Repo.log("切换面板出错 " + ex);
                            }
                        }
                        return true;
                }
                return false;
            }
        });
        ball = t;
        wm.addView(ball, ballLp);
        applyBall();
    }

    // ---------- 面板 ----------
    private void togglePanel() {
        if (panel != null) {
            hidePanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        repo.pool.execute(() -> {
            repo.loadCats();
            ui.post(() -> {
                if (panel != null) {
                    fillChips();
                    retab();
                }
            });
        });
        fill();
        growDown = ballLp.y + dp(BALL) / 2
                < (getResources().getDisplayMetrics().heightPixels - statusBarHeight()) / 2;
        buildPanel();
        placePanel();
        // 面板展开期间把球藏起来，收起时再放出来
        applyBall();
        try {
            wm.addView(panel, panelLp);
        } catch (Exception e) {
            Repo.log("面板添加失败 " + e);
            panel = null;
            return;
        }
        Repo.log("面板已添加 shown=" + shown.size() + " cell=" + cell);
        // 捕获当时的引用：如果这 500ms 里面板被收起，这里就安静跳过（否则空指针崩溃）
        final GridView g = grid;
        final View root = panel;
        ui.postDelayed(() -> {
            try {
                if (panel == null || root != panel || g == null) {
                    return;
                }
                int n = g.getChildCount();
                Repo.log("面板复查 root=" + root.getWidth() + "x" + root.getHeight()
                        + " | head top=" + head.getTop() + " h=" + head.getHeight()
                        + " | grid top=" + g.getTop() + " h=" + g.getHeight()
                        + " 子项=" + n);
            } catch (Exception e) {
                Repo.log("复查异常(可忽略) " + e);
            }
        }, 500);
    }

    /** 方案 A：面板贴着球展开——球在上半屏就向下长，在下半屏就向上长。 */
    private void placePanel() {
        int usable = getResources().getDisplayMetrics().heightPixels - statusBarHeight();
        int ph = dp(PANEL_H);
        int ballCy = ballLp.y + dp(BALL) / 2;
        int y = growDown ? ballLp.y : ballLp.y + dp(BALL) - ph;
        if (y + ph > usable) {
            y = usable - ph;
        }
        if (y < 0) {
            y = 0;
        }
        panelLp.y = y;
        Repo.log("面板定位 球y=" + ballLp.y + " 球中心=" + ballCy + " 可用高=" + usable
                + " 状态栏=" + statusBarHeight() + " → 面板y=" + y
                + (growDown ? " (球在上，表头在顶，向下展开)" : " (球在下，表头在底，向上展开)"));
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(28);
    }

    private void hidePanel() {
        applyBall();   // 收起时把球放出来（写在早退之前，免得一次空收起把球永久藏掉）
        if (panel == null) {
            return;
        }
        try {
            wm.removeView(panel);
        } catch (Exception ignored) {
        }
        panel = null;
        applyBall();
        grid = null;
        adapter = null;
        tabs = null;
        Repo.log("面板已收起");
    }

    private void buildPanel() {
        // 绝对定位：每块都给明确尺寸和偏移，不用 weight / wrap_content，
        // 因为浮窗里 LinearLayout 的测量结果不可靠（表头会被撑到面板中间，后面的子视图全不画）
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1C);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x44FFFFFF);
        box.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        tabs = new TextView[3];
        final String[] keys = {"all", "anim", "recent"};
        String[] names = {"全部", "动图", "最近"};
        for (int i = 0; i < names.length; i++) {
            final String key = keys[i];
            TextView b = new TextView(this);
            b.setText(names[i]);
            b.setTextSize(13);
            b.setPadding(dp(10), dp(5), dp(10), dp(5));
            b.setOnClickListener(v -> {
                filter = key;
                repo.putString("ovfilterKey", key);
                paintTabs();
                retab();
            });
            tabs[i] = b;
        }
        TextView close = new TextView(this);
        close.setText("收起");
        close.setTextSize(13);
        close.setPadding(dp(10), dp(5), dp(10), dp(5));
        close.setOnClickListener(v -> hidePanel());
        // 收起放在靠球的那一侧：球在右半边就靠右，在左半边就靠左
        boolean ballRight = ballLp.x + dp(BALL) / 2
                >= getResources().getDisplayMetrics().widthPixels / 2;
        LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(-2, -2);
        spacerLp.weight = 1;
        if (ballRight) {
            for (TextView b : tabs) {
                head.addView(b);
            }
            head.addView(new View(this), spacerLp);
            head.addView(close);
        } else {
            head.addView(close);
            head.addView(new View(this), spacerLp);
            for (TextView b : tabs) {
                head.addView(b);
            }
        }
        int pad = dp(8);
        int headH = dp(46);
        int headTop = growDown ? dp(4) : dp(PANEL_H) - dp(4) - headH;
        android.widget.FrameLayout.LayoutParams hlp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, headH);
        hlp.leftMargin = pad;
        hlp.rightMargin = pad;
        hlp.topMargin = headTop;
        box.addView(head, hlp);
        this.head = head;

        // 分类小标签：默认 + 用户分类（多的话横向滑）
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chipsRow);
        int chipsH = dp(36);
        // 向下展开时分类行在表头下面；翻转时（表头在底）它要待在表头上方
        int chipsTop = growDown ? headTop + headH + dp(2)
                : dp(PANEL_H) - dp(4) - headH - dp(2) - chipsH;
        android.widget.FrameLayout.LayoutParams chlp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, chipsH);
        chlp.leftMargin = pad;
        chlp.rightMargin = pad;
        chlp.topMargin = chipsTop;
        box.addView(hs, chlp);
        fillChips();

        cell = (dp(PANEL_W) - dp(16) - dp(2) * 3) / 4;
        GridView g = new GridView(this);
        g.setNumColumns(4);
        g.setHorizontalSpacing(dp(2));
        g.setVerticalSpacing(dp(2));
        g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new StickerAdapter(this, repo, shown, cell);
        g.setAdapter(adapter);
        g.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            if (pos < 0 || pos >= shown.size()) {
                return;
            }
            Sticker st = shown.get(pos);
            if (!repo.tap(st)) {
                // 开了“点击预览”：先在这格里演一遍，再点同一张才发
                adapter.notifyDataSetChanged();
                return;
            }
            repo.markUsed(st.name);
            repo.clearPreview();
            hidePanel();
            SendHelper.send(this, st);
        });
        grid = g;
        // 表头（+ 分类行）贴着球那一头，网格占剩下的地方
        int gridTop = growDown ? dp(4) + headH + dp(2) + chipsH + dp(4) : dp(4);
        int gridH = growDown ? dp(PANEL_H) - gridTop - dp(8) : chipsTop - dp(4) - dp(8);
        android.widget.FrameLayout.LayoutParams glp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, gridH);
        glp.leftMargin = pad;
        glp.rightMargin = pad;
        glp.topMargin = gridTop;
        box.addView(g, glp);

        panelLp = new WindowManager.LayoutParams(dp(PANEL_W), dp(PANEL_H),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;

        // 点面板外面任意地方也能收起（事件不消费，后面的界面照常响应）
        box.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                Repo.log("点了面板外面 → 收起");
                hidePanel();
            }
            return false;
        });

        panel = box;
        paintTabs();
    }

    /** 换档：数据变了就整块换 adapter，逼它重排（GridView 在浮窗里不会自己刷新）。 */
    private void retab() {
        if (panel == null || grid == null) {
            return;
        }
        fill();
        adapter = new StickerAdapter(this, repo, shown, cell);
        grid.setAdapter(adapter);
        Repo.log("换档 filter=" + filter + " shown=" + shown.size());
        final GridView g = grid;
        ui.postDelayed(() -> {
            try {
                if (grid == g && g != null) {
                    Repo.log("换档复查 子项=" + g.getChildCount()
                            + " 首个可见位置=" + g.getFirstVisiblePosition());
                }
            } catch (Exception e) {
                Repo.log("换档复查异常(可忽略) " + e);
            }
        }, 400);
    }

    /** 重建"默认 + 各分类"那行标签。 */
    private void fillChips() {
        if (chipsRow == null) {
            return;
        }
        chipsRow.removeAllViews();
        List<String> names = new ArrayList<>();
        names.add("未分类");
        synchronized (repo.cats) {
            names.addAll(repo.cats.keySet());
        }
        for (String n : names) {
            final String key = n.equals("未分类") ? "def" : "cat:" + n;
            TextView b = new TextView(this);
            b.setText(n);
            b.setTextSize(12);
            b.setPadding(dp(10), dp(4), dp(10), dp(4));
            b.setTag(key);
            b.setOnClickListener(v -> {
                filter = key;
                repo.putString("ovfilterKey", key);
                paintTabs();
                retab();
            });
            chipsRow.addView(b);
        }
        paintTabs();
    }

    private void paintTabs() {
        final String[] keys = {"all", "anim", "recent"};
        if (tabs != null) {
            for (int i = 0; i < tabs.length; i++) {
                boolean on = keys[i].equals(filter);
                tabs[i].setTextColor(on ? 0xFF7FD4FF : 0x99FFFFFF);
                tabs[i].getPaint().setFakeBoldText(on);
                tabs[i].invalidate();
            }
        }
        if (chipsRow != null) {
            for (int i = 0; i < chipsRow.getChildCount(); i++) {
                TextView b = (TextView) chipsRow.getChildAt(i);
                boolean on = filter.equals(b.getTag());
                b.setTextColor(on ? 0xFF7FD4FF : 0x99FFFFFF);
                b.getPaint().setFakeBoldText(on);
                b.invalidate();
            }
        }
    }

    /** 只算数据，不碰视图。 */
    private void fill() {
        List<Sticker> src;
        synchronized (repo.all) {
            src = new ArrayList<>(repo.all);
        }
        java.util.HashSet<String> grouped = filter.equals("def") ? repo.groupedNames() : null;
        List<String> catItems = null;
        if (filter.startsWith("cat:")) {
            synchronized (repo.cats) {
                List<String> l = repo.cats.get(filter.substring(4));
                catItems = l == null ? new ArrayList<String>() : new ArrayList<>(l);
            }
        }
        shown.clear();
        for (Sticker st : src) {
            if (filter.equals("anim") && !st.isAnim()) {
                continue;
            }
            if (filter.equals("recent") && !repo.recent.contains(st.name)) {
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
        final int s = repo.getInt("sort", 0);
        if (filter.equals("recent")) {
            Collections.sort(shown, (a, b) -> repo.recent.indexOf(a.name) - repo.recent.indexOf(b.name));
        } else if (s == 3) {
            // 跟主界面“常用”一致：点得多的在前，一样多就新的在前
            Collections.sort(shown, (a, b) -> {
                int c = repo.useCount(b.name) - repo.useCount(a.name);
                return c != 0 ? c : Long.compare(b.mtime, a.mtime);
            });
        } else if (s == 1) {
            Collections.sort(shown, (a, b) -> Long.compare(a.mtime, b.mtime));
        } else if (s == 2) {
            Collections.sort(shown, (a, b) -> a.name.compareTo(b.name));
        } else {
            Collections.sort(shown, (a, b) -> Long.compare(b.mtime, a.mtime));
        }
    }
}
