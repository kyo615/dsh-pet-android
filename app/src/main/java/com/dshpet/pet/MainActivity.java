package com.dshpet.pet;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：卡片式布局，零第三方依赖（自绘 Toggle / 圆角卡片 / 状态点）。 */
public class MainActivity extends Activity {
    private static final int BG = 0xFFF4F5F8;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF1B2333;
    private static final int SUB = 0xFF7A8296;
    private static final int PRIMARY = 0xFF4C6FFF;
    private static final int GREEN = 0xFF22A06B;
    private static final int ORANGE = 0xFFE08A00;
    private static final int RED = 0xFFE85D5D;

    private PetPrefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private TextView statusChip;
    private TextView statusLine;
    private TextView sizeValue;
    private TextView whisperValue;
    private TextView fpsValue;
    private TextView testResult;
    private LinearLayout primaryBtn;
    private TextView primaryLabel;
    private GradientDrawable primaryBg;
    private LinearLayout permsCard;

    private EditText baseUrl;
    private EditText apiKey;
    private EditText model;

    private Toggle roamToggle;
    private Toggle bootToggle;
    private Toggle whisperToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PetPrefs.load(this);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().getDecorView().setBackgroundColor(BG);
        setContentView(buildTree());
        applyInsets(findViewById(android.R.id.content));
        refresh();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 11);
        }
    }

    @Override
    protected void onPause() {
        saveAi();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs = PetPrefs.load(this);
        refresh();
    }

    // ------------------------------------------------------------------ build

    private View buildTree() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        content.setPadding(p, p, p, dp(28));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(header());

        section("桌宠");
        LinearLayout pet = card();
        LinearLayout sizeRow = row(pet, "显示大小", "拖动滑块调整，立即生效");
        sizeValue = valueText(prefs.sizeDp + " dp");
        sizeRow.addView(sizeValue);
        sizeRow.addView(stepButton("−", v -> {
            prefs.sizeDp = Math.max(80, prefs.sizeDp - 10);
            prefs.save(this);
            sizeValue.setText(prefs.sizeDp + " dp");
            reloadPetSoon();
        }));
        sizeRow.addView(stepButton("+", v -> {
            prefs.sizeDp = Math.min(280, prefs.sizeDp + 10);
            prefs.save(this);
            sizeValue.setText(prefs.sizeDp + " dp");
            reloadPetSoon();
        }));

        roamToggle = toggleRow(pet, "屏幕漫游", "关掉时它待在原地，更省电", prefs.roam, on -> {
            prefs.roam = on;
            prefs.save(this);
            reloadPetSoon();
        });
        bootToggle = toggleRow(pet, "开机自动启动", "重启手机后自动出现在屏幕上", prefs.autoStart, on -> {
            prefs.autoStart = on;
            prefs.save(this);
        });

        LinearLayout fpsRow = row(pet, "帧率上限", "越高越顺滑、越费电；24 等于原画质");
        fpsValue = valueText(prefs.fps + " fps");
        fpsRow.addView(fpsValue);
        fpsRow.addView(stepButton("−", v -> {
            prefs.fps = stepFps(prefs.fps, -1);
            prefs.save(this);
            fpsValue.setText(prefs.fps + " fps");
            reloadPetSoon();
        }));
        fpsRow.addView(stepButton("+", v -> {
            prefs.fps = stepFps(prefs.fps, 1);
            prefs.save(this);
            fpsValue.setText(prefs.fps + " fps");
            reloadPetSoon();
        }));

        pet.addView(divider());
        primaryBtn = primaryButton();
        pet.addView(primaryBtn);

        section("权限");
        permsCard = card();
        tipText("vivo / iQOO：i管家 → 自启动允许本应用；设置 → 电池 → 后台耗电管理 → 允许后台高耗电。不做也能用，只是锁屏久了可能被系统回收。");

        section("后台保活");
        LinearLayout keep = card();
        OemGuide.Brand brand = OemGuide.detect();
        keep.addView(label(brand.name + "：下面这条会直接跳到系统的自启动管理页；如果系统改版跳不准，点「查看手动路径」照着开一次就行。"));
        View bootRow = clickRow(keep, "去系统里允许自启动 / 后台运行", "让宠物在锁屏很久后也活着", "去开启", () -> {
            boolean ok = OemGuide.openAutostart(this, brand);
            if (!ok) showTextDialog("手动设置路径（" + brand.name + "）", brand.steps);
        });
        clickRow(keep, "查看手动设置路径", "跳转失败时按这里的步骤开", "查看", () ->
                showTextDialog("手动设置路径（" + brand.name + "）", brand.steps));

        section("AI 助手（可选）");
        LinearLayout ai = card();
        ai.addView(label("不填也能用：碎碎念会念内置台词，动画与互动完全不受影响。填好之后可以聊天、定时唠叨。"));
        baseUrl = addInput(ai, "接口地址", "https://api.deepseek.com/v1", prefs.baseUrl, InputType.TYPE_TEXT_VARIATION_URI);
        apiKey = addInput(ai, "密钥", "sk-…", prefs.apiKey, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        model = addInput(ai, "模型名", "deepseek-chat", prefs.model, InputType.TYPE_CLASS_TEXT);

        LinearLayout aiButtons = new LinearLayout(this);
        aiButtons.setOrientation(LinearLayout.HORIZONTAL);
        aiButtons.setPadding(0, dp(10), 0, 0);
        View test = secondaryButton("测试连接");
        test.setOnClickListener(v -> testAi());
        View clear = secondaryButton("清空对话记忆");
        clear.setOnClickListener(v -> {
            AiClient.clearHistory(this);
            setTestResult("对话记忆已清空", false);
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp1.rightMargin = dp(10);
        aiButtons.addView(test, lp1);
        aiButtons.addView(clear, new LinearLayout.LayoutParams(0, dp(44), 1f));
        ai.addView(aiButtons);

        testResult = new TextView(this);
        testResult.setTextSize(12.5f);
        testResult.setTextColor(SUB);
        testResult.setPadding(0, dp(10), 0, 0);
        testResult.setVisibility(View.GONE);
        ai.addView(testResult);

        section("碎碎念");
        LinearLayout whisper = card();
        whisperToggle = toggleRow(whisper, "定时冒一句话", "关掉后仍可从长按菜单手动触发", prefs.whisperEnabled, on -> {
            prefs.whisperEnabled = on;
            prefs.save(this);
            reloadPetSoon();
        });
        LinearLayout wRow = row(whisper, "间隔", "每隔多久自言自语一次");
        whisperValue = valueText(prefs.whisperMinutes + " 分钟");
        wRow.addView(whisperValue);
        wRow.addView(stepButton("−", v -> {
            prefs.whisperMinutes = Math.max(1, prefs.whisperMinutes - 1);
            prefs.save(this);
            whisperValue.setText(prefs.whisperMinutes + " 分钟");
            reloadPetSoon();
        }));
        wRow.addView(stepButton("+", v -> {
            prefs.whisperMinutes = Math.min(120, prefs.whisperMinutes + 1);
            prefs.save(this);
            whisperValue.setText(prefs.whisperMinutes + " 分钟");
            reloadPetSoon();
        }));

        section("怎么玩");
        LinearLayout help = card();
        help.addView(helpLine("拖动", "按住宠物直接拖，甩出去会按抛物线飞、撞屏幕边缘反弹"));
        help.addView(helpLine("长按", "按住不动约 0.7 秒弹出菜单：碎碎念、对话、动作点播、回到原位"));
        help.addView(helpLine("点击", "点一下有回应动画 + Q 弹；宠物身体以外的地方完全不挡操作"));

        section("关于");
        LinearLayout about = card();
        about.addView(label("DSH 桌宠  v" + versionName() + "\n离线运行，不收集任何数据，无需登录。"));
        clickRow(about, "开源许可与致谢", "代码 MIT；素材授权见说明", "查看", this::showLicense);
        return scroll;
    }

    private View header() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(roundRect(CARD, 18));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setElevation(dp(1));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.logo);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(52), dp(52));
        ip.rightMargin = dp(14);
        card.addView(icon, ip);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("DSH 桌宠");
        title.setTextSize(19);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        statusLine = new TextView(this);
        statusLine.setTextSize(12.5f);
        statusLine.setTextColor(SUB);
        statusLine.setPadding(0, dp(3), 0, 0);
        col.addView(statusLine);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusChip = new TextView(this);
        statusChip.setTextSize(12);
        statusChip.setTypeface(Typeface.DEFAULT_BOLD);
        statusChip.setPadding(dp(10), dp(5), dp(10), dp(5));
        card.addView(statusChip);
        return card;
    }

    private void section(String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        t.setTextColor(SUB);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(6), dp(20), 0, dp(8));
        content.addView(t);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(CARD, 16));
        card.setPadding(dp(16), dp(6), dp(16), dp(10));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(2);
        content.addView(card, p);
        return card;
    }

    private LinearLayout row(LinearLayout parent, String title, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));
        row.setMinimumHeight(dp(56));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(TEXT);
        col.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(SUB);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        return row;
    }

    private Toggle toggleRow(LinearLayout parent, String title, String sub, boolean checked, Toggle.OnChange cb) {
        LinearLayout row = row(parent, title, sub);
        Toggle toggle = new Toggle(this);
        toggle.setChecked(checked);
        toggle.setOnChange(cb);
        toggle.setOnClickListener(v -> {
            toggle.setChecked(!toggle.isChecked(), true);
            cb.onChanged(toggle.isChecked());
        });
        row.addView(toggle, new LinearLayout.LayoutParams(dp(50), dp(32)));
        return toggle;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFEEF0F4);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        p.topMargin = dp(2);
        p.bottomMargin = dp(2);
        v.setLayoutParams(p);
        return v;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(SUB);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(8), 0, dp(10));
        return t;
    }

    private void tipText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11.5f);
        t.setTextColor(SUB);
        t.setPadding(dp(6), dp(8), dp(6), 0);
        t.setLineSpacing(dp(2), 1f);
        content.addView(t);
    }

    private TextView valueText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(8), 0, dp(2), 0);
        return t;
    }

    private View stepButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(18);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(roundRect(0xFFF1F3F7, 12)));
        b.setClickable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(36), dp(36));
        p.leftMargin = dp(6);
        b.setLayoutParams(p);
        return b;
    }

    private LinearLayout primaryButton() {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        primaryBg = roundRect(PRIMARY, 14);
        btn.setBackground(ripple(primaryBg));
        btn.setClickable(true);
        btn.setOnClickListener(v -> {
            if (PetService.running) stopPet();
            else startPet();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        p.topMargin = dp(10);
        p.bottomMargin = dp(6);
        btn.setLayoutParams(p);

        primaryLabel = new TextView(this);
        primaryLabel.setText("启动桌宠");
        primaryLabel.setTextSize(15);
        primaryLabel.setTextColor(Color.WHITE);
        primaryLabel.setTypeface(Typeface.DEFAULT_BOLD);
        btn.addView(primaryLabel);
        return btn;
    }

    private View secondaryButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(13.5f);
        b.setTextColor(PRIMARY);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(roundRect(0xFFEEF2FF, 12)));
        b.setClickable(true);
        return b;
    }

    private EditText addInput(LinearLayout parent, String hint, String placeholder, String value, int inputType) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(0, dp(8), 0, 0);

        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextSize(12);
        h.setTextColor(SUB);
        h.setPadding(dp(2), 0, 0, dp(5));
        holder.addView(h);

        EditText e = new EditText(this);
        e.setHint(placeholder);
        e.setText(value == null ? "" : value);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFFB4BAC7);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(roundRect(0xFFF5F6FA, 10));
        e.setTag(hint);
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) saveAi();
        });
        holder.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(holder);
        return e;
    }

    private void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                top = i.top;
                bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        } else {
            getWindow().setStatusBarColor(BG);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    // ------------------------------------------------------------------ actions

    private void startPet() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("先授予「显示在其他应用上层」权限");
            openOverlaySettings();
            return;
        }
        saveAi();
        prefs.running = true;
        prefs.save(this);
        Intent i = new Intent(this, PetService.class).setAction(PetService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        ui.postDelayed(this::refresh, 350);
    }

    private void stopPet() {
        prefs.running = false;
        prefs.save(this);
        stopService(new Intent(this, PetService.class));
        ui.postDelayed(this::refresh, 300);
    }

    private void reloadPetSoon() {
        if (!PetService.running) return;
        Intent i = new Intent(this, PetService.class).setAction(PetService.ACTION_RELOAD);
        startService(i);
    }

    private void saveAi() {
        if (baseUrl == null) return;
        prefs.baseUrl = baseUrl.getText().toString().trim();
        prefs.apiKey = apiKey.getText().toString().trim();
        prefs.model = model.getText().toString().trim();
        prefs.save(this);
    }

    private void testAi() {
        saveAi();
        final PetPrefs p = PetPrefs.load(this);
        if (!p.aiConfigured()) {
            setTestResult("还没填接口地址或模型名", true);
            return;
        }
        setTestResult("正在连接…", false);
        new Thread(() -> {
            final String msg = AiClient.test(p);
            ui.post(() -> setTestResult(msg, msg.startsWith("连接失败")));
        }).start();
    }

    private void setTestResult(String msg, boolean bad) {
        testResult.setVisibility(View.VISIBLE);
        testResult.setText(msg);
        testResult.setTextColor(bad ? 0xFFD9534F : GREEN);
    }

    private void refresh() {
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean notify = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;

        boolean running = PetService.running;
        statusChip.setText(running ? "运行中" : "已停止");
        statusChip.setTextColor(running ? GREEN : SUB);
        statusChip.setBackground(roundRect(running ? 0xFFE6F6EE : 0xFFF0F2F6, 10));
        statusLine.setText(running ? "小女仆正在屏幕上待着" : (prefs.aiConfigured() ? "AI 已配置" : "AI 未配置（可选）"));
        primaryLabel.setText(running ? "停止桌宠" : "启动桌宠");
        if (primaryBg != null) primaryBg.setColor(running ? RED : PRIMARY);

        permsCard.removeAllViews();
        permRow("悬浮窗权限", "显示在其他应用上层", overlay, this::openOverlaySettings);
        permRow("电池优化白名单", "防止后台被系统杀掉", battery, this::openBatterySettings);
        permRow("通知权限", "常驻通知与提醒", notify, () -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 11);
            }
        });

        if (roamToggle != null) roamToggle.setChecked(prefs.roam);
        if (bootToggle != null) bootToggle.setChecked(prefs.autoStart);
        if (whisperToggle != null) whisperToggle.setChecked(prefs.whisperEnabled);
        if (sizeValue != null) sizeValue.setText(prefs.sizeDp + " dp");
        if (whisperValue != null) whisperValue.setText(prefs.whisperMinutes + " 分钟");
        if (fpsValue != null) fpsValue.setText(prefs.fps + " fps");
        if (baseUrl != null && !baseUrl.hasFocus()) baseUrl.setText(prefs.baseUrl);
        if (apiKey != null && !apiKey.hasFocus()) apiKey.setText(prefs.apiKey);
        if (model != null && !model.hasFocus()) model.setText(prefs.model);
    }

    private void permRow(String title, String sub, boolean ok, Runnable action) {
        if (permsCard.getChildCount() > 0) permsCard.addView(divider());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setClickable(true);
        row.setBackground(ripple(roundRect(0x00000000, 10)));
        row.setOnClickListener(v -> action.run());

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ok ? GREEN : ORANGE);
        dot.setBackground(d);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(dp(8), dp(8));
        dp2.rightMargin = dp(12);
        row.addView(dot, dp2);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTextColor(TEXT);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11.5f);
        s.setTextColor(SUB);
        s.setPadding(0, dp(2), 0, 0);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action2 = new TextView(this);
        action2.setText(ok ? "已开启" : "去开启");
        action2.setTextSize(12.5f);
        action2.setTextColor(ok ? SUB : PRIMARY);
        action2.setTypeface(ok ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        row.addView(action2);
        permsCard.addView(row);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception ignored) {
                toast("请到系统设置 → 应用 → 特殊权限 里开启悬浮窗");
            }
        }
    }

    private void openBatterySettings() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("已在白名单里");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast("打不开电池设置，请手动到系统设置里关闭本应用的电池优化");
            }
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ utils

    private static final int[] FPS_STEPS = {12, 15, 18, 20, 24};

    private int stepFps(int current, int dir) {
        int idx = 0;
        for (int i = 0; i < FPS_STEPS.length; i++) {
            if (FPS_STEPS[i] <= current) idx = i;
        }
        idx = Math.max(0, Math.min(FPS_STEPS.length - 1, idx + dir));
        return FPS_STEPS[idx];
    }

    private View clickRow(LinearLayout parent, String title, String sub, String action, Runnable onClick) {
        if (parent.getChildCount() > 0) parent.addView(divider());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setClickable(true);
        row.setBackground(ripple(roundRect(0x00000000, 10)));
        row.setOnClickListener(v -> onClick.run());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTextColor(TEXT);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11.5f);
        s.setTextColor(SUB);
        s.setPadding(0, dp(2), 0, 0);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView a = new TextView(this);
        a.setText(action);
        a.setTextSize(12.5f);
        a.setTextColor(PRIMARY);
        row.addView(a);
        parent.addView(row);
        return row;
    }

    private void showLicense() {
        showTextDialog("开源许可与致谢", getString(R.string.license_text));
    }

    private void showTextDialog(String title, String body) {
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(13);
        tv.setTextColor(TEXT);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(dp(20), dp(16), dp(20), dp(8));
        tv.setTextIsSelectable(true);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    private View helpLine(String key, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextSize(12.5f);
        k.setTextColor(PRIMARY);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        k.setMinWidth(dp(44));
        row.addView(k);

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(SUB);
        t.setLineSpacing(dp(2), 1f);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private RippleDrawable ripple(GradientDrawable base) {
        return new RippleDrawable(ColorStateList.valueOf(0x1F000000), base, null);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
