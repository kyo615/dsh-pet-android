package com.dshpet.pet;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只监听 127.0.0.1 的迷你静态服务器，把包内 pet 资源按桌面助手（Electron 主进程）的路径暴露出来：
 *   /dsh-pet-7340/config                  → 成品配置（含安卓侧覆盖）
 *   /dsh-pet-7340/thumb/<root>/<x>.webm   → assets/pet/webm/<x>.webm
 *   /dsh-pet-7340/font/<x>.ttf            → assets/pet/fonts/上首软糖体.ttf
 *   /dsh-pet-7340/pic/memes/<root>/<x>.png → assets/pet/memes/<x>.png
 *   /dsh-pet-7340/pic/<x>.png             → assets/pet/pic/<x>.png
 *   /pet/...                              → assets/pet/...
 * 支持 Range（<video> 需要；素材都在 2MB 内，整段读入再切片即可）。
 */
public final class LocalServer {
    private static final Pattern THUMB = Pattern.compile("^/dsh-pet-7340/thumb/[^/]+/(.+\\.(webm|mov))$");
    private static final Pattern FONT = Pattern.compile("^/dsh-pet-7340/font/(.+)\\.ttf$");
    private static final Pattern MEME = Pattern.compile("^/dsh-pet-7340/pic/memes/(?:[^/]+/)?(.+\\.png)$");
    private static final Pattern PIC = Pattern.compile("^/dsh-pet-7340/pic/([^/]+\\.png)$");

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket socket;
    private String fontAsset;
    private volatile int port = -1;

    public LocalServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        try {
            String[] fonts = ctx.getAssets().list("pet/fonts");
            if (fonts != null && fonts.length > 0) fontAsset = "pet/fonts/" + fonts[0];
        } catch (IOException ignored) {
        }
    }

    public int start() throws IOException {
        socket = new ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"));
        port = socket.getLocalPort();
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    final Socket s = socket.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    if (!socket.isClosed()) continue;
                }
            }
        }, "pet-http");
        t.setDaemon(true);
        t.start();
        return port;
    }

    public int port() {
        return port;
    }

    public void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            long rangeStart = -1, rangeEnd = -1;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String name = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(idx + 1).trim();
                    if ("range".equals(name)) {
                        Matcher m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(value);
                        if (m.find()) {
                            if (!m.group(1).isEmpty()) rangeStart = Long.parseLong(m.group(1));
                            if (!m.group(2).isEmpty()) rangeEnd = Long.parseLong(m.group(2));
                        }
                    }
                }
            }

            String path;
            try {
                path = URLDecoder.decode(target.replace("+", "%2B"), "UTF-8");
            } catch (Exception e) {
                path = target;
            }
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if ("/dsh-pet-7340/config".equals(path)) {
                byte[] body = PetConfig.forRenderer(ctx).getBytes(StandardCharsets.UTF_8);
                send(out, 200, "application/json; charset=utf-8", body, -1, -1, method.equals("HEAD"));
                return;
            }
            if ("/dsh-pet-7340/state".equals(path)) {
                byte[] body = "{\"sections\":{},\"pets\":{}}".getBytes(StandardCharsets.UTF_8);
                send(out, 200, "application/json; charset=utf-8", body, -1, -1, method.equals("HEAD"));
                return;
            }

            Matcher m = THUMB.matcher(path);
            if (m.matches()) {
                // 页面仍按 .webm 拼地址，实际发的是「RGB+alpha 并排」的 H.264（见 android-shim 的渲染说明）
                String name = m.group(1).replaceAll("\\.(webm|mov)$", ".mp4");
                serveAsset(out, "pet/webm/" + name, "video/mp4", rangeStart, rangeEnd, method);
                return;
            }
            m = FONT.matcher(path);
            if (m.matches() && fontAsset != null) {
                serveAsset(out, fontAsset, "font/ttf", rangeStart, rangeEnd, method);
                return;
            }
            m = MEME.matcher(path);
            if (m.matches()) {
                serveAsset(out, "pet/memes/" + m.group(1), "image/png", rangeStart, rangeEnd, method);
                return;
            }
            m = PIC.matcher(path);
            if (m.matches()) {
                serveAsset(out, "pet/pic/" + m.group(1), "image/png", rangeStart, rangeEnd, method);
                return;
            }
            if (path.startsWith("/pet/") && !path.contains("..")) {
                String rel = path.substring(1);
                String ctype = contentType(rel);
                serveAsset(out, rel, ctype, rangeStart, rangeEnd, method);
                return;
            }
            send(out, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8), -1, -1, false);
        } catch (Exception ignored) {
            // 单连接失败不影响服务
        }
    }

    private void serveAsset(OutputStream out, String asset, String ctype, long start, long end, String method) throws IOException {
        byte[] data = readAsset(asset);
        if (data == null) {
            send(out, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8), -1, -1, false);
            return;
        }
        send(out, start >= 0 ? 206 : 200, ctype, data, start, end, method.equals("HEAD"));
    }

    private byte[] readAsset(String asset) {
        AssetManager am = ctx.getAssets();
        try (InputStream is = am.open(asset); ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(8192, is.available()))) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void send(OutputStream out, int status, String ctype, byte[] data, long start, long end, boolean head) throws IOException {
        long size = data.length;
        long from = 0;
        long to = size - 1;
        if (status == 206) {
            from = Math.max(0, Math.min(start, size - 1));
            to = end >= 0 ? Math.min(end, size - 1) : size - 1;
            if (to < from) to = size - 1;
        }
        long len = to - from + 1;
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(status == 206 ? " Partial Content" : " OK").append("\r\n");
        h.append("Content-Type: ").append(ctype).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n");
        if (status == 206) h.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(size).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (!head) out.write(data, (int) from, (int) len);
        out.flush();
    }

    private static String contentType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
            any = true;
        }
        if (!any && c == -1) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
