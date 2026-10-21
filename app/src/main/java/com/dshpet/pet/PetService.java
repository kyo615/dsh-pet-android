package com.dshpet.pet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 前台服务：两块透明悬浮窗。
 *
 *   1) 渲染窗：全屏、不可触摸、不可获焦，里面是 dsh-pet 的桌面渲染器（宠物在页面里用 transform 移动）。
 *   2) 触摸窗：只有宠物身体那么大，负责接手势 → 转成合成指针事件丢给页面。
 *
 * 为什么这么拆：安卓悬浮窗是矩形，整块都会吃触摸；把渲染和触摸拆开后，宠物周围那圈透明区域
 * 不再挡下层应用，菜单/对话也能铺满整屏（渲染窗是全屏的），不再被小窗口裁掉。
 */
public class PetService extends Service {
    public static final String ACTION_START = "com.dshpet.pet.START";
    public static final String ACTION_STOP = "com.dshpet.pet.STOP";
    public static final String ACTION_RELOAD = "com.dshpet.pet.RELOAD";

    private static final String TAG = "DshPet";
    private static final String CHANNEL = "pet_service";
    private static final int NOTIFY_ID = 7340;
    private static final int TOUCH_PAD_DP = 20;
    private static final int TOUCH_MIN_DP = 96;

    public static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WebView web;
    private View touchView;
    private WindowManager.LayoutParams renderLp;
    private WindowManager.LayoutParams touchLp;
    private LocalServer server;
    private PetPrefs prefs;
    private float density = 1f;
    private int screenW, screenH, insetTop, insetBottom;

    private boolean chatFocus;  // 对话打开：渲染窗可获焦（软键盘）
    private boolean touchDown;
    private boolean modalPending;

    // JS 上报的命中区（CSS px = dp）
    private volatile double hitX = -1, hitY = -1, hitW = 0, hitH = 0;
    private volatile boolean screenOn = true;

    // 合并 move 事件（触摸事件 120Hz，evaluateJavascript 跟不上）
    private double pendMoveX, pendMoveY;
    private boolean pendMove, moveScheduled;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (web == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOn = false;
                js("document.querySelectorAll('video').forEach(function(v){v.pause()})");
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                screenOn = true;
                js("(function(){var v=document.querySelector('video.is-front');if(v){v.play()}})()");
            }
        }
    };

    /** modal 看门狗：页面若已没有菜单/对话（或干脆不响应），强制把整屏触摸交还系统。 */
    private final Runnable modalCheck = new Runnable() {
        @Override
        public void run() {
            if (!modalPending || web == null) return;
            final boolean[] answered = {false};
            try {
                web.evaluateJavascript("(window.__petModalAlive&&window.__petModalAlive())?'1':'0'", value -> {
                    answered[0] = true;
                    if (!"\"1\"".equals(value)) {
                        setModal(false, false);
                    }
                });
            } catch (Exception e) {
                setModal(false, false);
                return;
            }
            main.postDelayed(() -> {
                if (!answered[0]) setModal(false, false);
            }, 1500);
            main.postDelayed(this, 4000);
        }
    };

    private final Runnable whisperTick = new Runnable() {
        @Override
        public void run() {
            maybeWhisper();
            long delay = Math.max(1, prefs.whisperMinutes) * 60_000L;
            main.postDelayed(this, delay);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            PetPrefs.setRunning(this, false);
            main.removeCallbacks(whisperTick);
            main.removeCallbacks(modalCheck);
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs = PetPrefs.load(this);
        startForeground(NOTIFY_ID, buildNotification());
        if (web == null) {
            showOverlay();
        } else if (ACTION_RELOAD.equals(action)) {
            prefs = PetPrefs.load(this);
            scheduleWhisper();
            reload(250);
        }
        if (ACTION_START.equals(action) || intent == null) {
            running = true;
            scheduleWhisper();
        }
        return START_STICKY;
    }

    private void scheduleWhisper() {
        main.removeCallbacks(whisperTick);
        // 没配 AI 也照常定时（念内置台词），只有关掉开关才停
        if (prefs.whisperEnabled) {
            main.postDelayed(whisperTick, Math.max(1, prefs.whisperMinutes) * 60_000L);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "桌宠运行中", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL);
        b.setContentTitle("DSH 桌宠")
                .setContentText("小女仆正在屏幕上待着")
                .setSmallIcon(R.drawable.ic_pet)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    // ------------------------------------------------------------------ overlay

    private void showOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        density = dm.density > 0 ? dm.density : 1f;
        insetTop = dimen("status_bar_height");
        insetBottom = hasNavBar() ? dimen("navigation_bar_height") : 0;

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // 页面重载后旧的菜单/对话都不存在了：modal 必须复位，否则会继续整屏吃触摸
                main.post(() -> setModal(false, false));
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.w(TAG, "webview error " + errorCode + " " + description + " " + failingUrl);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidPet");

        renderLp = new WindowManager.LayoutParams(
                screenW, screenH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        renderLp.gravity = Gravity.TOP | Gravity.START;
        renderLp.x = 0;
        renderLp.y = 0;

        touchView = new View(this);
        touchView.setBackgroundColor(Color.TRANSPARENT);
        touchView.setOnTouchListener((v, e) -> onTouch(e));
        int side = (int) Math.round(prefs.sizeDp * 0.4 * density);
        touchLp = new WindowManager.LayoutParams(
                side, side,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        touchLp.gravity = Gravity.TOP | Gravity.START;
        touchLp.x = screenW - side - Math.round(12 * density);
        touchLp.y = screenH - side - Math.round(40 * density);

        try {
            wm.addView(web, renderLp);
            wm.addView(touchView, touchLp);
        } catch (Exception e) {
            Log.e(TAG, "add overlay failed", e);
            stopSelf();
            return;
        }

        // 系统栏高度以窗口实际 insets 为准（资源值在全面屏/手势导航下可能不准）
        web.post(() -> {
            readInsets();
            startPage();
        });

        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
    }

    private void readInsets() {
        try {
            WindowInsets ins = web.getRootWindowInsets();
            if (ins == null) return;
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = ins.getInsets(WindowInsets.Type.systemBars());
                top = i.top;
                bottom = i.bottom;
            } else {
                top = ins.getSystemWindowInsetTop();
                bottom = ins.getSystemWindowInsetBottom();
            }
            if (top > 0) insetTop = top;
            if (bottom > 0) insetBottom = bottom;
        } catch (Exception ignored) {
        }
    }

    private void startPage() {
        try {
            server = new LocalServer(this);
            int port = server.start();
            web.loadUrl("http://127.0.0.1:" + port + "/pet/index.html?" + query(port));
        } catch (Exception e) {
            Log.e(TAG, "local server failed", e);
            stopSelf();
        }
    }

    private String query(int port) {
        // 视口 = 整块屏幕（页面坐标就是屏幕坐标）；可活动区 = 去掉状态栏/导航栏
        int viewW = Math.round(screenW / density);
        int viewH = Math.round(screenH / density);
        int areaY = Math.round(insetTop / density);
        int areaH = Math.max(120, Math.round((screenH - insetTop - insetBottom) / density));
        String area = String.format(Locale.US, "[{\"x\":0,\"y\":%d,\"width\":%d,\"height\":%d}]", areaY, viewW, areaH);
        return "configUrl=" + enc("http://127.0.0.1:" + port + "/dsh-pet-7340/config")
                + "&scale=1&petIndex=0"
                + "&workAreaX=0&workAreaY=0"
                + "&workAreaW=" + viewW
                + "&workAreaH=" + viewH
                + "&areas=" + enc(area)
                + "&panels=" + enc(area)
                + "&workAreas=" + enc(area)
                + "&primaryIndex=0"
                + "&fps=" + Math.max(12, Math.min(24, prefs.fps))
                + "&openTarget=site";
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private int dimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private boolean hasNavBar() {
        int id = getResources().getIdentifier("config_showNavigationBar", "bool", "android");
        return id <= 0 || getResources().getBoolean(id);
    }

    private void reload(int delayMs) {
        main.postDelayed(() -> {
            if (web != null && server != null) {
                web.loadUrl("http://127.0.0.1:" + server.port() + "/pet/index.html?" + query(server.port()));
            }
        }, delayMs);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转：整块窗口按新方向重来（页面只在启动时读一次视口）
        main.postDelayed(() -> {
            if (web == null || wm == null) return;
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
            density = dm.density > 0 ? dm.density : 1f;
            renderLp.width = screenW;
            renderLp.height = screenH;
            try {
                wm.updateViewLayout(web, renderLp);
            } catch (Exception ignored) {
            }
            // 旋转时 modal 状态一并复位（否则可能保持"整屏可触摸"）
            setModal(false, false);
            reload(300);
        }, 300);
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacks(whisperTick);
        main.removeCallbacks(modalCheck);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        if (wm != null) {
            if (touchView != null) {
                try {
                    wm.removeView(touchView);
                } catch (Exception ignored) {
                }
            }
            if (web != null) {
                try {
                    wm.removeView(web);
                } catch (Exception ignored) {
                }
            }
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        if (server != null) server.stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ touch

    private boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDown = true;
                sendTouch("down", (double) e.getRawX(), (double) e.getRawY());
                return true;
            case MotionEvent.ACTION_MOVE:
                pendMoveX = e.getRawX();
                pendMoveY = e.getRawY();
                pendMove = true;
                if (!moveScheduled) {
                    moveScheduled = true;
                    main.postDelayed(() -> {
                        moveScheduled = false;
                        if (!pendMove) return;
                        pendMove = false;
                        sendTouch("move", pendMoveX, pendMoveY);
                    }, 16);
                }
                return true;
            case MotionEvent.ACTION_UP:
                touchDown = false;
                pendMove = false;
                sendTouch("up", (double) e.getRawX(), (double) e.getRawY());
                return true;
            case MotionEvent.ACTION_CANCEL:
                touchDown = false;
                pendMove = false;
                sendTouch("cancel", (double) e.getRawX(), (double) e.getRawY());
                return true;
            default:
                return false;
        }
    }

    private void sendTouch(String kind, double rawX, double rawY) {
        js(String.format(Locale.US, "window.__petTouch&&window.__petTouch('%s',%.2f,%.2f)",
                kind, rawX / density, rawY / density));
    }

    private void applyTouchBounds() {
        if (touchLp == null || wm == null || touchView == null) return;
        if (modalPending) {
            touchLp.x = 0;
            touchLp.y = 0;
            touchLp.width = screenW;
            touchLp.height = screenH;
        } else {
            int pad = Math.round(TOUCH_PAD_DP * density);
            int min = Math.round(TOUCH_MIN_DP * density);
            int w = Math.max(min, (int) Math.round(hitW * density) + pad * 2);
            int h = Math.max(min, (int) Math.round(hitH * density) + pad * 2);
            int x = hitX >= 0 ? (int) Math.round(hitX * density) - pad : touchLp.x;
            int y = hitY >= 0 ? (int) Math.round(hitY * density) - pad : touchLp.y;
            touchLp.x = x;
            touchLp.y = y;
            touchLp.width = w;
            touchLp.height = h;
        }
        try {
            wm.updateViewLayout(touchView, touchLp);
        } catch (Exception ignored) {
        }
    }

    /**
     * 菜单/对话打开 → 渲染窗切成**原生触摸**（整屏接管），小触摸窗关掉。
     *
     * 为什么不能继续用合成事件：菜单子项靠 mouseenter 展开、"点外面关闭"监听的是 document 的
     * mousedown、对话输入框要软键盘（窗口必须可触摸 + 可获焦）。这些都只有真触摸能喂出来。
     * 关闭后立刻切回「全屏不可触摸 + 小触摸窗」，所以透明区域平时不挡点击。
     */
    private void setModal(boolean on, boolean chatFlag) {
        main.post(() -> {
            if (modalPending == on && chatFocus == chatFlag) {
                if (!on) applyTouchBounds();
                return;
            }
            modalPending = on;
            chatFocus = chatFlag;
            touchDown = false;
            if (renderLp == null || web == null || wm == null || touchLp == null) return;
            if (on) {
                renderLp.flags = chatFlag ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                renderLp.softInputMode = chatFlag
                        ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
                touchLp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                renderLp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                touchLp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            }
            try {
                wm.updateViewLayout(web, renderLp);
                wm.updateViewLayout(touchView, touchLp);
            } catch (Exception ignored) {
            }
            if (on && chatFlag) {
                web.requestFocus();
                js("document.body.focus && document.body.focus()");
            } else {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
                } catch (Exception ignored) {
                }
            }
            if (!on) applyTouchBounds();
            main.removeCallbacks(modalCheck);
            if (on) main.postDelayed(modalCheck, 4000);
        });
    }

    // ------------------------------------------------------------------ bridge

    private void js(String code) {
        if (web == null) return;
        main.post(() -> {
            try {
                web.evaluateJavascript(code, null);
            } catch (Exception ignored) {
            }
        });
    }

    private void pushSay(String text, String image) {
        if (web == null || text == null) return;
        js("window.__petSay && window.__petSay('main', " + JSONObject.quote(text) + ", "
                + (image == null ? "null" : JSONObject.quote(image)) + ")");
    }

    private void maybeWhisper() {
        if (prefs == null || !prefs.whisperEnabled || !screenOn) return;
        prefs = PetPrefs.load(this);
        if (!prefs.aiConfigured()) {
            pushSay(Canned.pick(), randomMeme());
            return;
        }
        final String prompt = PetConfig.whisperPrompt(this);
        final Map<String, String> memes = PetConfig.memes(this);
        new Thread(() -> {
            AiClient.Result r = AiClient.whisper(prefs, prompt, memes);
            if (r.ok && r.text != null && !r.text.isEmpty()) {
                pushSay(r.text, r.image);
            } else {
                Log.w(TAG, "whisper failed, fallback to canned: " + r.error);
                pushSay(Canned.pick(), randomMeme());
            }
        }, "pet-whisper").start();
    }

    private String randomMeme() {
        Map<String, String> memes = PetConfig.memes(this);
        if (memes.isEmpty()) return null;
        java.util.List<String> keys = new java.util.ArrayList<>(memes.keySet());
        return keys.get(new java.util.Random().nextInt(keys.size()));
    }

    private final class Bridge {
        @JavascriptInterface
        public void setHitRect(double x, double y, double w, double h) {
            // 触摸进行中不挪窗（免得手指下的窗口被移动/改尺寸打断手势）
            if (touchDown) {
                hitX = x;
                hitY = y;
                hitW = w;
                hitH = h;
                return;
            }
            hitX = x;
            hitY = y;
            hitW = w;
            hitH = h;
            main.post(PetService.this::applyTouchBounds);
        }

        @JavascriptInterface
        public void setModal(boolean modalOn, boolean chat) {
            PetService.this.setModal(modalOn, chat);
        }

        @JavascriptInterface
        public void aiRequest(String id, String kind, String payload) {
            final PetPrefs p = PetPrefs.load(PetService.this);
            new Thread(() -> {
                String out = "{\"ok\":false}";
                try {
                    JSONObject req = new JSONObject(payload == null ? "{}" : payload);
                    AiClient.Result r;
                    if ("chat".equals(kind)) {
                        String text = req.optString("text", "");
                        if (!p.aiConfigured()) {
                            JSONObject err = new JSONObject();
                            err.put("ok", false);
                            err.put("error", "先到设置里填一下 AI 接口，我才能陪你聊天");
                            out = err.toString();
                            throw new IllegalStateException("__skip__");
                        }
                        List<String[]> history = AiClient.loadHistory(PetService.this);
                        r = AiClient.chat(p, PetConfig.whisperPrompt(PetService.this), history, text);
                        if (!r.ok) {
                            JSONObject err = new JSONObject();
                            err.put("ok", false);
                            err.put("error", r.error);
                            out = err.toString();
                        } else {
                            history.add(new String[]{"user", text});
                            history.add(new String[]{"assistant", r.text});
                            AiClient.saveHistory(PetService.this, history, p.chatRounds);
                            JSONObject ok = new JSONObject();
                            ok.put("ok", true);
                            ok.put("text", r.text);
                            out = ok.toString();
                        }
                    } else if (!p.aiConfigured()) {
                        JSONObject ok = new JSONObject();
                        ok.put("ok", true);
                        ok.put("text", Canned.pick());
                        String meme = randomMeme();
                        if (meme != null) ok.put("image", meme);
                        out = ok.toString();
                        r = null;
                    } else {
                        r = AiClient.whisper(p, PetConfig.whisperPrompt(PetService.this), PetConfig.memes(PetService.this));
                        JSONObject o = new JSONObject();
                        boolean okk = r != null && r.ok;
                        o.put("ok", okk);
                        if (okk) {
                            o.put("text", r.text);
                            if (r.image != null) o.put("image", r.image);
                        } else if (req.optBoolean("manual")) {
                            o.put("ok", true);
                            o.put("text", "（AI 没连上：" + (r == null ? "未配置" : r.error) + "，先念句内置的吧）");
                            String meme = randomMeme();
                            if (meme != null) o.put("image", meme);
                        } else {
                            o.put("ok", true);
                            o.put("text", Canned.pick());
                            String meme = randomMeme();
                            if (meme != null) o.put("image", meme);
                        }
                        out = o.toString();
                    }
                } catch (IllegalStateException skip) {
                    // out 已在上面写好（未配置 AI 的友好提示）
                } catch (Exception e) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("ok", false);
                        o.put("error", String.valueOf(e.getMessage()));
                        out = o.toString();
                    } catch (Exception ignored) {
                        out = "{\"ok\":false}";
                    }
                }
                final String result = out;
                main.post(() -> {
                    if (web == null) return;
                    try {
                        web.evaluateJavascript("window.__petNativeResult && window.__petNativeResult("
                                + JSONObject.quote(id) + ", " + JSONObject.quote(result) + ")", null);
                    } catch (Exception ignored) {
                    }
                });
            }, "pet-ai").start();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || url.contains("127.0.0.1") || url.contains("localhost")) {
                Intent i = new Intent(PetService.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }

        @JavascriptInterface
        public void log(String message) {
            Log.i(TAG, String.valueOf(message));
        }
    }
}
