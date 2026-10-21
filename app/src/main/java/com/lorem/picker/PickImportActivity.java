package com.lorem.picker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 导入预览：把暂存目录里待转换的文件摆出来，由用户决定转哪些。
 *
 * 默认全选。因为源文件还在 QQ 里，取消掉的项直接删掉暂存即可，不会丢东西。
 */
public class PickImportActivity extends Activity {

    public static final String EXTRA_NAMES = "picked_names";

    private Repo repo;
    private final List<Sticker> items = new ArrayList<>();
    private final LinkedHashSet<String> picked = new LinkedHashSet<>();
    private GridView grid;
    private Adapter adapter;
    private TextView counter;
    private int cell;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        repo = Repo.get(this);
        cell = (int) (getResources().getDisplayMetrics().density * 88);

        Map<Uri, String> staged = QQImport.listStaged(this, repo.tree());
        for (Map.Entry<Uri, String> e : staged.entrySet()) {
            items.add(new Sticker(e.getValue(), e.getKey(), 0, 0, 0));
            picked.add(e.getValue());
        }

        root();
        Repo.log("预览页 待选 " + items.size() + " 项");
    }

    private void root() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121214);

        // 顶部：全选 / 全不选 / 计数
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(6), dp(6), dp(6), 0);
        top.addView(flat("全选", v -> {
            picked.clear();
            for (Sticker s : items) {
                picked.add(s.name);
            }
            refresh();
        }));
        top.addView(flat("全不选", v -> {
            picked.clear();
            refresh();
        }));
        counter = new TextView(this);
        counter.setTextSize(13);
        counter.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        counter.setPadding(0, 0, dp(10), 0);
        top.addView(counter, new LinearLayout.LayoutParams(0, dp(40), 1f));
        root.addView(top);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(6), dp(4), dp(6), dp(8));
        bottom.addView(flat("取消", v -> cancelAll()));
        Button ok = flat("导入选中", v -> done());
        bottom.addView(ok, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(bottom);

        // 顶栏别钻到状态栏里，底栏别压到导航条上
        root.setPadding(0, dimen("status_bar_height"), 0, dimen("navigation_bar_height"));

        setContentView(root);
        refresh();
    }

    private void refresh() {
        counter.setText("已选 " + picked.size() + " / " + items.size() + " 张");
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void done() {
        if (picked.isEmpty()) {
            cancelAll();
            return;
        }
        Intent out = new Intent();
        out.putExtra(EXTRA_NAMES, picked.toArray(new String[0]));
        setResult(RESULT_OK, out);
        finish();
    }

    private void cancelAll() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private Button flat(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setAllCaps(false);
        b.setBackgroundColor(0x00000000);
        b.setOnClickListener(l);
        return b;
    }

    private int dimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View reuse, ViewGroup parent) {
            FrameLayout box;
            ImageView img;
            TextView mark;
            if (reuse == null) {
                box = new FrameLayout(PickImportActivity.this);
                img = new ImageView(PickImportActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setLayoutParams(new FrameLayout.LayoutParams(cell, cell));
                mark = new TextView(PickImportActivity.this);
                mark.setTextSize(16);
                FrameLayout.LayoutParams mp =
                        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                mp.setMargins(0, dp(2), dp(4), 0);
                box.addView(img);
                box.addView(mark, mp);
                box.setTag(new Object[]{img, mark});
            } else {
                box = (FrameLayout) reuse;
                Object[] t = (Object[]) box.getTag();
                img = (ImageView) t[0];
                mark = (TextView) t[1];
            }
            final Sticker st = items.get(i);
            final boolean on = picked.contains(st.name);
            mark.setText(on ? "✓" : "");
            mark.setTextColor(0xFF7CE38B);
            box.setBackgroundColor(on ? 0xFF1D3A28 : 0xFF1C1C1F);
            box.setOnClickListener(v -> {
                if (picked.contains(st.name)) {
                    picked.remove(st.name);
                } else {
                    picked.add(st.name);
                }
                refresh();
            });

            final ImageView target = img;
            Bitmap bm = repo.cached(st.name);
            if (bm != null) {
                target.setImageBitmap(bm);
            } else {
                target.setImageDrawable(null);
                target.setTag(st.name);
                repo.pool.execute(() -> {
                    final Bitmap b2 = repo.thumb(st);
                    runOnUiThread(() -> {
                        if (b2 != null && st.name.equals(target.getTag())) {
                            target.setImageBitmap(b2);
                        }
                    });
                });
            }
            return box;
        }
    }
}
