package com.dshpet.pet;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 国产 ROM 的后台保活适配。
 *
 * 原则：**能跳就跳，跳不过去就给准确的文字路径**。厂商的设置页组件名随系统版本变，
 * 所以每家准备多个候选（从新到旧），逐个 try，全部失败就落到「应用详情页 + 该品牌的手动路径」。
 * 文字路径按 2025 年的主流版本写（OriginOS 5 / ColorOS 15 / HyperOS 2-3 / MagicOS 9 / One UI 7）。
 */
public final class OemGuide {

    public static final class Brand {
        public final String id;
        public final String name;
        public final String[] components;
        public final String steps;

        Brand(String id, String name, String[] components, String steps) {
            this.id = id;
            this.name = name;
            this.components = components;
            this.steps = steps;
        }
    }

    private OemGuide() {
    }

    public static Brand detect() {
        String manu = safe(Build.MANUFACTURER);
        String brand = safe(Build.BRAND);
        String all = (manu + " " + brand).toLowerCase(Locale.ROOT);

        if (all.contains("xiaomi") || all.contains("redmi") || all.contains("poco")) {
            return new Brand("xiaomi", "小米 / Redmi（HyperOS）", new String[]{
                    "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
                    "com.miui.securitycenter/com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                    "com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            }, "① 安全中心 → 应用管理 → 权限 → 自启动：允许\n" +
                    "② 设置 → 应用设置 → 应用管理 → 本应用 → 省电策略：无限制\n" +
                    "③ 设置 → 应用设置 → 特殊权限设置 → 显示在其他应用上层：允许");
        }
        if (all.contains("oppo") || all.contains("realme") || all.contains("oneplus") || all.contains("oplus")) {
            return new Brand("oppo", "OPPO / realme / 一加（ColorOS）", new String[]{
                    "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
                    "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
                    "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
            }, "① 手机管家 → 权限隐私 → 自启动管理：允许本应用\n" +
                    "② 设置 → 应用 → 应用管理 → 本应用 → 耗电管理：允许后台活动\n" +
                    "③ 设置 → 应用 → 特殊权限 → 显示在其他应用上层：允许");
        }
        if (all.contains("vivo") || all.contains("iqoo")) {
            return new Brand("vivo", "vivo / iQOO（OriginOS）", new String[]{
                    "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                    "com.vivo.abe/com.vivo.abe.ui.ExcessivePowerManagerActivity",
            }, "① i管家 → 应用管理 → 权限管理 → 自启动：允许\n" +
                    "② 设置 → 电池 → 后台耗电管理 → 本应用 → 允许后台高耗电\n" +
                    "③ 设置 → 应用与权限 → 权限管理 → 悬浮窗：允许");
        }
        if (all.contains("honor")) {
            return new Brand("honor", "荣耀（MagicOS）", new String[]{
                    "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.hihonor.systemmanager/com.hihonor.systemmanager.optimize.process.ProtectActivity",
            }, "① 设置 → 应用 → 应用启动管理 → 本应用：改为「手动管理」，三项全开\n" +
                    "② 设置 → 应用 → 权限管理 → 悬浮窗：允许\n" +
                    "③ 设置 → 电池 → 更多电池设置 → 休眠时保持连接：开");
        }
        if (all.contains("huawei")) {
            return new Brand("huawei", "华为（HarmonyOS / EMUI）", new String[]{
                    "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
            }, "① 手机管家 → 应用启动管理 → 本应用：手动管理，三项全开\n" +
                    "② 设置 → 应用 → 权限管理 → 悬浮窗：允许\n" +
                    "③ 设置 → 电池 → 更多电池设置 → 休眠时保持网络连接：开");
        }
        if (all.contains("samsung")) {
            return new Brand("samsung", "三星（One UI）", new String[]{
                    "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
                    "com.samsung.android.sm/com.samsung.android.sm.ui.battery.BatteryActivity",
            }, "① 设置 → 电池 → 后台使用限制 → 把本应用加入「不受限制」\n" +
                    "② 设置 → 应用 → 本应用 → 电池 → 不受限制\n" +
                    "③ 设置 → 应用 → 特殊访问权限 → 显示在其他应用上层：允许");
        }
        if (all.contains("meizu")) {
            return new Brand("meizu", "魅族（Flyme）", new String[]{
                    "com.meizu.safe/com.meizu.safe.permission.SSApplicationManageActivity",
                    "com.meizu.safe/com.meizu.safe.security.SHOW_APPSEC",
            }, "① 手机管家 → 权限管理 → 后台管理：允许后台运行\n" +
                    "② 手机管家 → 自启动管理：允许本应用\n" +
                    "③ 设置 → 应用管理 → 本应用 → 权限 → 悬浮窗：允许");
        }
        if (all.contains("lenovo") || all.contains("motorola") || all.contains("zte") || all.contains("nubia")) {
            return new Brand("others", "其他安卓机型", new String[]{},
                    "① 设置 → 应用 → 本应用 → 电池：不限制 / 允许后台活动\n" +
                            "② 设置 → 应用 → 特殊权限 → 显示在其他应用上层：允许\n" +
                            "③ 若有「手机管家 / 安全中心」，在里面允许本应用自启动");
        }
        return new Brand("others", "其他安卓机型", new String[]{
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        }, "① 设置 → 应用 → 本应用 → 电池：不限制 / 允许后台活动\n" +
                "② 设置 → 应用 → 特殊权限 → 显示在其他应用上层：允许\n" +
                "③ 若有「手机管家 / 安全中心」，在里面允许本应用自启动");
    }

    /** 依次尝试厂商的自启动管理页；全部打不开返回 false（调用方再落到应用详情页 + 文字指引）。 */
    public static boolean openAutostart(Context ctx, Brand brand) {
        List<String> candidates = new ArrayList<>();
        for (String c : brand.components) candidates.add(c);
        for (String comp : candidates) {
            int slash = comp.indexOf('/');
            if (slash <= 0) continue;
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(comp.substring(0, slash), comp.substring(slash + 1)));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Exception ignored) {
                // 换下一个候选
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
