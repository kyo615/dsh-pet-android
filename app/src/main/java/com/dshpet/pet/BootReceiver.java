package com.dshpet.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** 开机 / 覆盖安装后拉起桌宠（仅当用户开了「开机自动启动」且已授予悬浮窗权限）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        PetPrefs p = PetPrefs.load(ctx);
        if (!p.running || !p.autoStart) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return;
        Intent i = new Intent(ctx, PetService.class).setAction(PetService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }
}
