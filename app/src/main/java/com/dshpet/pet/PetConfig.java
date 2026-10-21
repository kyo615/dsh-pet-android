package com.dshpet.pet;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 包内 pet 配置（由构建期 Node 生成的成品聚合）＋ 安卓侧覆盖（宠物大小 / 碎碎念开关）。 */
public final class PetConfig {
    private static String raw;

    public static synchronized String raw(Context ctx) {
        if (raw == null) {
            try {
                raw = readAsset(ctx, "pet/config.json");
            } catch (Exception e) {
                raw = "{\"main\":{\"pets\":[]}}";
            }
        }
        return raw;
    }

    /** 发给页面的 /config：成品聚合，仅覆盖用户可调的两个字段（大小、碎碎念）。 */
    public static String forRenderer(Context ctx) {
        PetPrefs p = PetPrefs.load(ctx);
        try {
            JSONObject root = new JSONObject(raw(ctx));
            JSONObject main = root.getJSONObject("main");
            JSONArray pets = main.optJSONArray("pets");
            if (pets != null && pets.length() > 0) {
                JSONObject pet = pets.getJSONObject(0);
                pet.put("size", p.sizeDp);
                pet.put("whisperEnabled", p.whisperEnabled);
                pet.put("fixedEnabled", !p.roam);
            }
            return root.toString();
        } catch (Exception e) {
            return raw(ctx);
        }
    }

    /** 碎碎念提示词（人设）。 */
    public static String whisperPrompt(Context ctx) {
        try {
            return new JSONObject(raw(ctx)).getJSONObject("main").optString("whisperPrompt", "你是主人手机上的小宠物，说一句话。");
        } catch (Exception e) {
            return "你是主人手机上的小宠物，说一句话。";
        }
    }

    /** 表情包池：名称 → 描述（与 assets/pet/memes/<名称>.png 一一对应）。 */
    public static Map<String, String> memes(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject m = new JSONObject(raw(ctx)).getJSONObject("main").optJSONObject("memes");
            if (m != null) {
                for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                    String k = it.next();
                    out.put(k, m.optString(k, ""));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static List<String> memeNames(Context ctx) {
        return new ArrayList<>(memes(ctx).keySet());
    }

    private static String readAsset(Context ctx, String path) throws Exception {
        AssetManager am = ctx.getAssets();
        try (InputStream in = am.open(path); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
