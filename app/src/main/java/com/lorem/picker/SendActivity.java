package com.lorem.picker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * 悬浮球分享的中转页。
 *
 * 从 Service 启动 Activity 必须带 NEW_TASK，而带了这个标志投递给 QQ 时，系统只会把 QQ 的
 * 任务调到前台、分享意图被丢掉。所以由这个不带界面的 Activity 来发分享意图：调用方仍然是
 * Activity，可以只用 CLEAR_TOP，QQ 在后台运行时也能正常弹出“选择聊天”。
 */
public class SendActivity extends Activity {

    public static final String EXTRA_URI = "send_uri";
    public static final String EXTRA_NAME = "send_name";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent in = getIntent();
            String raw = in == null ? null : in.getStringExtra(EXTRA_URI);
            String name = in == null ? null : in.getStringExtra(EXTRA_NAME);
            if (raw == null || name == null) {
                Repo.log("发送中转：参数缺失");
                return;
            }
            SendHelper.send(this, new Sticker(name, Uri.parse(raw), 1, 0, 0));
        } catch (Throwable t) {
            Repo.log("发送中转失败 " + t);
        } finally {
            finish();
        }
    }
}
