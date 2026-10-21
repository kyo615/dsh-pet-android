package com.dshpet.pet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * OpenAI 兼容的对话调用（/chat/completions）。
 *
 * 端点在 APK 内配置：baseUrl 可以写 https://api.deepseek.com/v1、https://your-gateway/v1、
 * 或完整的 .../chat/completions。密钥只落在 SharedPreferences，不进日志、不进崩溃上报。
 */
public final class AiClient {

    public static final class Say {
        public String text;
        public String image;
    }

    public static final class Result {
        public boolean ok;
        public String text;
        public String image;
        public String error;
    }

    private static final Random RND = new Random();

    private AiClient() {}

    private static String endpoint(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/chat/completions")) return u;
        return u + "/chat/completions";
    }

    private static JSONObject post(String baseUrl, String apiKey, String model, JSONArray messages, int maxTokens) throws Exception {
        URL url = new URL(endpoint(baseUrl));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(90000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.9);
            body.put("max_tokens", maxTokens);
            body.put("stream", false);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(in);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " " + snippet(text));
            }
            JSONObject raw = new JSONObject(text);
            JSONArray choices = raw.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new IllegalStateException("响应没有 choices");
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            String content = msg == null ? null : msg.optString("content", "");
            if (content == null || content.trim().isEmpty()) throw new IllegalStateException("响应内容为空");
            JSONObject out = new JSONObject();
            out.put("text", clean(content));
            return out;
        } finally {
            c.disconnect();
        }
    }

    private static String clean(String s) {
        String t = s.trim();
        // 去掉常见的包裹引号/前缀（模型偶尔会写成「"..."」）
        if (t.length() > 1 && (t.startsWith("\"") && t.endsWith("\""))) t = t.substring(1, t.length() - 1);
        if (t.startsWith("碎碎念：")) t = t.substring(4);
        return t.trim();
    }

    /** 碎碎念：随机抽一张表情包，把它的描述一并给模型，返回 (一句话, 表情包名)。 */
    public static Result whisper(PetPrefs p, String personPrompt, Map<String, String> memes) {
        Result r = new Result();
        if (!p.aiConfigured()) {
            r.error = "未配置 AI 端点";
            return r;
        }
        String image = null;
        String desc = null;
        if (!memes.isEmpty()) {
            List<String> keys = new ArrayList<>(memes.keySet());
            image = keys.get(RND.nextInt(keys.size()));
            desc = memes.get(image);
        }
        try {
            JSONArray messages = new JSONArray();
            String sys = personPrompt;
            if (desc != null && !desc.isEmpty()) {
                sys += "\n（这会配一张表情包，画面是：" + desc + "，请让这句话和画面呼应，但不要描述图片本身。）";
            }
            messages.put(new JSONObject().put("role", "system").put("content", sys));
            messages.put(new JSONObject().put("role", "user").put("content", "现在随口说一句（20 字以内，不要引号、不要解释）。"));
            JSONObject out = post(p.baseUrl, p.apiKey, p.model, messages, 120);
            r.ok = true;
            r.text = out.optString("text", "");
            r.image = image;
            return r;
        } catch (Exception e) {
            r.error = brief(e);
            return r;
        }
    }

    /** 对话：带最近 N 轮记忆，回复写回历史。 */
    public static Result chat(PetPrefs p, String personPrompt, List<String[]> history, String userText) {
        Result r = new Result();
        if (!p.aiConfigured()) {
            r.error = "未配置 AI 端点";
            return r;
        }
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content",
                    personPrompt + "\n你正在手机桌面上和主人聊天：口语化、简短（不超过 40 字），不解释自己是 AI。"));
            for (String[] h : history) {
                messages.put(new JSONObject().put("role", h[0]).put("content", h[1]));
            }
            messages.put(new JSONObject().put("role", "user").put("content", userText));
            JSONObject out = post(p.baseUrl, p.apiKey, p.model, messages, 300);
            r.ok = true;
            r.text = out.optString("text", "");
            return r;
        } catch (Exception e) {
            r.error = brief(e);
            return r;
        }
    }

    /** 设置页「测试」：最省 token 的一次真实请求。 */
    public static String test(PetPrefs p) {
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", "只回复两个字：在的"));
            JSONObject out = post(p.baseUrl, p.apiKey, p.model, messages, 20);
            return "连接成功：" + out.optString("text", "");
        } catch (Exception e) {
            return "连接失败：" + brief(e);
        }
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 180 ? m.substring(0, 180) : m;
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) : t;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /** 记忆读写（SharedPreferences 里的 JSON 数组）。 */
    public static List<String[]> loadHistory(android.content.Context ctx) {
        List<String[]> out = new ArrayList<>();
        String raw = ctx.getSharedPreferences("dsh_pet_memory", android.content.Context.MODE_PRIVATE)
                .getString("history", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new String[]{o.optString("role", "user"), o.optString("content", "")});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static void saveHistory(android.content.Context ctx, List<String[]> history, int rounds) {
        int keep = Math.max(1, rounds) * 2;
        int from = Math.max(0, history.size() - keep);
        JSONArray arr = new JSONArray();
        try {
            for (int i = from; i < history.size(); i++) {
                arr.put(new JSONObject().put("role", history.get(i)[0]).put("content", history.get(i)[1]));
            }
        } catch (JSONException ignored) {
        }
        ctx.getSharedPreferences("dsh_pet_memory", android.content.Context.MODE_PRIVATE)
                .edit().putString("history", arr.toString()).apply();
    }

    public static void clearHistory(android.content.Context ctx) {
        ctx.getSharedPreferences("dsh_pet_memory", android.content.Context.MODE_PRIVATE)
                .edit().remove("history").apply();
    }
}
