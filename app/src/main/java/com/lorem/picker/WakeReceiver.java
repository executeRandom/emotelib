package com.lorem.picker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 屏幕亮起 / 解锁时把浮球那套“叫醒”。
 *
 * 为什么需要它：ColorOS 会把后台的我们冻住（连前台服务也冻，日志实测：进程 0 CPU、
 * 两条心跳全停），冻住期间球不会自己回来——用户看到的就是“切出去再回来球不见了”。
 * 屏幕亮/解锁是少数还能把进程唤醒的时机，醒来立刻重判一次前台，球就能回到正确状态。
 */
public class WakeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (!Intent.ACTION_SCREEN_ON.equals(a) && !Intent.ACTION_USER_PRESENT.equals(a)) {
            return;
        }
        Repo repo = Repo.get(c);
        Repo.log("唤醒广播 " + a + " 服务在跑=" + OverlayService.running);
        if (OverlayService.running) {
            OverlayService.poke();
        } else if (repo.getInt("ballOn", 1) == 1) {
            // 球本来是开着的（用户没关），被系统清理后就顺手拉回来
            try {
                c.startForegroundService(new Intent(c, OverlayService.class));
            } catch (Exception e) {
                Repo.log("唤醒时拉起服务失败 " + e);
            }
        }
    }
}
