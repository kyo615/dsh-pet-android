package com.dshpet.pet;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地设置（AI 端点、宠物大小、碎碎念开关等）。全部存在 SharedPreferences，不写任何密钥到日志。 */
public final class PetPrefs {
    private static final String FILE = "dsh_pet";

    public boolean running;
    public int sizeDp;
    public String baseUrl;
    public String apiKey;
    public String model;
    public boolean whisperEnabled;
    public int whisperMinutes;
    public int chatRounds;
    public boolean softwareRender;
    public boolean autoStart;
    public boolean roam;
    /** 渲染帧率上限（12/15/18/20/24），越低越省电 */
    public int fps;

    private PetPrefs() {}

    public static PetPrefs load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        PetPrefs p = new PetPrefs();
        p.running = sp.getBoolean("running", false);
        p.sizeDp = sp.getInt("sizeDp", 160);
        p.baseUrl = sp.getString("baseUrl", "");
        p.apiKey = sp.getString("apiKey", "");
        p.model = sp.getString("model", "");
        p.whisperEnabled = sp.getBoolean("whisperEnabled", true);
        p.whisperMinutes = sp.getInt("whisperMinutes", 5);
        p.chatRounds = sp.getInt("chatRounds", 5);
        p.fps = sp.getInt("fps", 24);
        p.autoStart = sp.getBoolean("autoStart", true);
        p.roam = sp.getBoolean("roam", false);
        p.fps = sp.getInt("fps", 24);
        return p;
    }

    public void save(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit();
        e.putBoolean("running", running);
        e.putInt("sizeDp", sizeDp);
        e.putString("baseUrl", baseUrl);
        e.putString("apiKey", apiKey);
        e.putString("model", model);
        e.putBoolean("whisperEnabled", whisperEnabled);
        e.putInt("whisperMinutes", whisperMinutes);
        e.putInt("chatRounds", chatRounds);
        e.putBoolean("softwareRender", softwareRender);
        e.putBoolean("autoStart", autoStart);
        e.putBoolean("roam", roam);
        e.putInt("fps", fps);
        e.apply();
    }

    public boolean aiConfigured() {
        return baseUrl != null && !baseUrl.trim().isEmpty() && model != null && !model.trim().isEmpty();
    }

    public static void setRunning(Context ctx, boolean running) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("running", running).apply();
    }
}
