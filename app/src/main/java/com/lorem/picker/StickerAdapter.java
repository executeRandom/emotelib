package com.lorem.picker;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

public class StickerAdapter extends BaseAdapter {

    private final Context ctx;
    private final Repo repo;
    private final List<Sticker> data;
    private final int cell;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public StickerAdapter(Context c, Repo r, List<Sticker> data, int cellPx) {
        ctx = c;
        repo = r;
        this.data = data;
        cell = cellPx;
    }

    public int getCount() {
        return data.size();
    }

    public Object getItem(int i) {
        return data.get(i);
    }

    public long getItemId(int i) {
        return i;
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** 选择模式：格子变暗，被选中的加蓝框 + 左上角对勾 */
    public boolean selectMode = false;
    public final java.util.Set<String> selected = new java.util.HashSet<>();

    private static class H {
        ImageView iv;
        TextView tag;
        TextView use;
        View ring;
        TextView pick;
        View pRing;
        TextView pTag;
    }

    public View getView(int pos, View cv, ViewGroup parent) {
        H h;
        if (cv == null) {
            FrameLayout box = new FrameLayout(ctx);
            box.setLayoutParams(new AbsListView.LayoutParams(cell, cell));
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(5), dp(5), dp(5), dp(5));
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            TextView tag = new TextView(ctx);
            tag.setText("动图");
            tag.setTextSize(9);
            tag.setTextColor(0xE6FFFFFF);
            tag.setBackgroundColor(0xA6404040);
            tag.setPadding(dp(3), 0, dp(3), 0);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.END | Gravity.BOTTOM);
            lp.setMargins(0, 0, dp(3), dp(3));
            box.addView(tag, lp);

            // 左下角的使用次数（“更多”里的开关打开时才显示）
            TextView use = new TextView(ctx);
            use.setTextSize(9);
            use.setTextColor(0xE6FFFFFF);
            use.setBackgroundColor(0xA6404040);
            use.setPadding(dp(3), 0, dp(3), 0);
            FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.START | Gravity.BOTTOM);
            ulp.setMargins(dp(3), 0, 0, dp(3));
            box.addView(use, ulp);

            View ring = new View(ctx);
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(0x00000000);
            g.setStroke(dp(3), 0xFF5FB0FF);
            g.setCornerRadius(dp(6));
            ring.setBackground(g);
            box.addView(ring, new FrameLayout.LayoutParams(-1, -1));

            TextView pick = new TextView(ctx);
            pick.setText("✓");
            pick.setTextSize(11);
            pick.setTextColor(0xFFFFFFFF);
            pick.setPadding(dp(5), 0, dp(5), 0);
            android.graphics.drawable.GradientDrawable pg =
                    new android.graphics.drawable.GradientDrawable();
            pg.setColor(0xFF3A8FD9);
            pg.setCornerRadius(dp(4));
            pick.setBackground(pg);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.START | Gravity.TOP);
            plp.setMargins(dp(3), dp(3), 0, 0);
            box.addView(pick, plp);

            // 预览那张：琥珀色边框 + 左上角“预览”（跟选择模式的蓝框区分开）
            View pRing = new View(ctx);
            android.graphics.drawable.GradientDrawable pg2 =
                    new android.graphics.drawable.GradientDrawable();
            pg2.setColor(0x00000000);
            pg2.setStroke(dp(3), 0xFFFFB300);
            pg2.setCornerRadius(dp(6));
            pRing.setBackground(pg2);
            box.addView(pRing, new FrameLayout.LayoutParams(-1, -1));

            TextView pTag = new TextView(ctx);
            pTag.setText("预览");
            pTag.setTextSize(9);
            pTag.setTextColor(0xFF241A00);
            pTag.setPadding(dp(3), 0, dp(3), 0);
            android.graphics.drawable.GradientDrawable ptg =
                    new android.graphics.drawable.GradientDrawable();
            ptg.setColor(0xFFFFB300);
            ptg.setCornerRadius(dp(4));
            pTag.setBackground(ptg);
            FrameLayout.LayoutParams ptlp = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.START | Gravity.TOP);
            ptlp.setMargins(dp(3), dp(3), 0, 0);
            box.addView(pTag, ptlp);
            ring.setVisibility(View.GONE);
            pick.setVisibility(View.GONE);
            pRing.setVisibility(View.GONE);
            pTag.setVisibility(View.GONE);

            h = new H();
            h.iv = iv;
            h.tag = tag;
            h.use = use;
            h.ring = ring;
            h.pick = pick;
            h.pRing = pRing;
            h.pTag = pTag;
            box.setTag(h);
            cv = box;
        }
        h = (H) cv.getTag();
        final Sticker st = data.get(pos);
        h.tag.setVisibility(st.isAnim() ? View.VISIBLE : View.GONE);
        final boolean showUse = repo.getInt("showUsage", 0) == 1;
        h.use.setVisibility(showUse ? View.VISIBLE : View.GONE);
        if (showUse) {
            h.use.setText(String.valueOf(repo.useCount(st.name)));
        }
        boolean sel = selectMode && selected.contains(st.name);
        cv.setAlpha(selectMode && !sel ? 0.4f : 1f);
        h.ring.setVisibility(sel ? View.VISIBLE : View.GONE);
        h.pick.setVisibility(sel ? View.VISIBLE : View.GONE);
        h.iv.setTag(st.name);
        boolean pv = !selectMode && repo.previewing(st.name);
        h.pRing.setVisibility(pv ? View.VISIBLE : View.GONE);
        h.pTag.setVisibility(pv ? View.VISIBLE : View.GONE);
        if (pv) {
            // 预览：这张要在自己的格子里演起来（动图就动起来）
            stopAnim(h.iv);
            h.iv.setImageBitmap(null);
            final ImageView pTarget = h.iv;
            repo.pool.execute(() -> {
                final android.graphics.drawable.Drawable d = decodeFull(st);
                ui.post(() -> {
                    if (d == null || !st.name.equals(pTarget.getTag())
                            || !repo.previewing(st.name)) {
                        return;
                    }
                    stopAnim(pTarget);
                    pTarget.setImageDrawable(d);
                    if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
                        android.graphics.drawable.AnimatedImageDrawable a =
                                (android.graphics.drawable.AnimatedImageDrawable) d;
                        try {
                            a.setRepeatCount(android.graphics.drawable.AnimatedImageDrawable
                                    .REPEAT_INFINITE);
                            a.start();
                        } catch (Throwable ignored) {
                        }
                    }
                });
            });
            return cv;
        }
        stopAnim(h.iv);
        Bitmap b = repo.cached(st.name);
        if (b != null) {
            h.iv.setImageBitmap(b);
        } else {
            h.iv.setImageBitmap(null);
            final ImageView target = h.iv;
            repo.pool.execute(() -> {
                final Bitmap bm = repo.thumb(st);
                Repo.log("thumb " + st.name + " -> "
                        + (bm == null ? "null" : bm.getWidth() + "x" + bm.getHeight()));
                if (bm == null) {
                    return;
                }
                ui.post(() -> {
                    boolean ok = st.name.equals(target.getTag());
                    Repo.log("set thumb " + st.name + " applied=" + ok
                            + " w=" + target.getWidth() + " h=" + target.getHeight());
                    if (ok) {
                        target.setImageBitmap(bm);
                    }
                });
            });
        }
        return cv;
    }

    /** 格子要换内容了：先把上一份动图停下来，别让它在后台空转。 */
    private void stopAnim(ImageView iv) {
        android.graphics.drawable.Drawable d = iv.getDrawable();
        if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
            try {
                ((android.graphics.drawable.AnimatedImageDrawable) d).stop();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 预览用：交给系统整张解码（动图得到 AnimatedImageDrawable，它自己会播）。 */
    private android.graphics.drawable.Drawable decodeFull(Sticker st) {
        android.graphics.drawable.Drawable d = decodePreview(st, true);
        if (d == null) {
            d = decodePreview(st, false);   // 带缩放这一步出问题就退回原始尺寸再试
        }
        if (d != null) {
            return d;
        }
        Bitmap b = repo.thumb(st);   // 实在不行，至少把静帧摆上
        return b == null ? null
                : new android.graphics.drawable.BitmapDrawable(ctx.getResources(), b);
    }

    private android.graphics.drawable.Drawable decodePreview(Sticker st, boolean limit) {
        try {
            android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder
                    .createSource(ctx.getContentResolver(), st.uri);
            final int side = Math.max(dp(48), cell);
            android.graphics.drawable.Drawable d = android.graphics.ImageDecoder.decodeDrawable(
                    src, (decoder, info, s) -> {
                        decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                        if (limit) {
                            decoder.setTargetSize(side, side);
                        }
                    });
            Repo.log("预览解码" + (limit ? "(缩放)" : "(原图)") + " " + st.name + " -> "
                    + (d == null ? "null" : d.getClass().getSimpleName()));
            return d;
        } catch (Throwable t) {
            Repo.log("预览解码失败" + (limit ? "(缩放)" : "(原图)") + " " + st.name + " -> " + t);
            return null;
        }
    }
}
