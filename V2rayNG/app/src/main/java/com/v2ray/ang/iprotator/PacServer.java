package com.v2ray.ang.iprotator;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Máy chủ HTTP rất nhỏ chạy cùng tiến trình V2Ray (":daemon"), chỉ khi đang chia sẻ Wi-Fi.
 *   /           trang hướng dẫn cho máy khách (mở bằng trình duyệt)
 *   /p, /proxy.pac, /wpad.dat   file PAC (khai báo "proxy tự động")
 * Ghi danh sách máy khách đã lấy PAC vào files/share_clients.txt để màn hình Chia sẻ hiển thị.
 */
public final class PacServer {
    private PacServer() {}

    public static final int PAC_PORT = 8080;
    static final String CLIENT_FILE = "share_clients.txt";

    private static volatile ServerSocket server;
    private static volatile Context appCtx;
    private static final Map<String, String> clients = new LinkedHashMap<>();

    /** Gọi khi nhân Xray vừa chạy: chia sẻ đang bật thì chạy PAC, không thì tắt. */
    public static void syncWithSettings(Context ctx) {
        if (ShareManager.isEnabled()) start(ctx); else stop();
    }

    public static synchronized void start(Context ctx) {
        appCtx = ctx.getApplicationContext();
        if (server != null && !server.isClosed()) return;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PAC_PORT));
            server = s;
            Thread t = new Thread(() -> loop(s), "pac-server");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            server = null;
        }
    }

    public static synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        synchronized (clients) { clients.clear(); }
        writeClients();
    }

    private static void loop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket c = s.accept();
                Thread t = new Thread(() -> handle(c), "pac-client");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (s.isClosed()) return;
            }
        }
    }

    private static void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String first = in.readLine();
            if (first == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* bỏ qua header */ }
            String[] parts = first.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            // IP của chính điện thoại mà máy khách đang thấy (đúng mạng điểm phát)
            String myIp = sock.getLocalAddress().getHostAddress();
            String clientIp = sock.getInetAddress().getHostAddress();
            int port = ShareManager.proxyPort();

            String body, type;
            if (path.equals("/p") || path.equals("/proxy.pac") || path.equals("/wpad.dat")) {
                body = ShareManager.pac(myIp, port);
                type = "application/x-ns-proxy-autoconfig";
                remember(clientIp);
            } else {
                body = guidePage(myIp, port);
                type = "text/html; charset=utf-8";
                remember(clientIp);
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = sock.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                    + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(b);
            out.flush();
        } catch (Exception ignored) {}
    }

    private static void remember(String ip) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (clients) {
            clients.remove(ip);
            clients.put(ip, t);
            while (clients.size() > 30) clients.remove(clients.keySet().iterator().next());
        }
        writeClients();
    }

    private static void writeClients() {
        Context ctx = appCtx;
        if (ctx == null) return;
        StringBuilder sb = new StringBuilder();
        synchronized (clients) {
            for (Map.Entry<String, String> e : clients.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        try (FileOutputStream f = new FileOutputStream(new File(ctx.getFilesDir(), CLIENT_FILE))) {
            f.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    static String guidePage(String ip, int port) {
        String pac = "http://" + ip + ":" + PAC_PORT + "/p";
        return "<!doctype html><html lang=vi><head><meta charset=utf-8>"
             + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Chia sẻ V2Ray</title>"
             + "<style>body{font-family:sans-serif;max-width:640px;margin:16px auto;padding:0 16px;line-height:1.5}"
             + "code{background:#eee;padding:2px 6px;border-radius:4px;font-size:1.05em}h2{margin-top:24px}</style></head><body>"
             + "<h1>Dùng mạng V2Ray của điện thoại</h1>"
             + "<p><b>Proxy tự động (khuyên dùng):</b> <code>" + pac + "</code></p>"
             + "<p><b>Hoặc proxy thủ công:</b> máy chủ <code>" + ip + "</code> cổng <code>" + port + "</code> (HTTP và SOCKS5 chung cổng)</p>"
             + "<h2>Điện thoại Android</h2><ol><li>Cài đặt → Wi-Fi → nhấn giữ mạng đang nối → Sửa (hoặc biểu tượng ⚙)</li>"
             + "<li>Tùy chọn nâng cao → Proxy → <b>Tự động cấu hình proxy</b></li><li>URL PAC: <code>" + pac + "</code> → Lưu</li></ol>"
             + "<h2>iPhone / iPad</h2><ol><li>Cài đặt → Wi-Fi → chạm (i) cạnh mạng đang nối</li>"
             + "<li>Định cấu hình proxy → <b>Tự động</b></li><li>URL: <code>" + pac + "</code> → Lưu</li></ol>"
             + "<h2>Windows 10/11</h2><ol><li>Cài đặt → Mạng &amp; Internet → Proxy</li>"
             + "<li>Dùng tập lệnh cài đặt → Bật, địa chỉ: <code>" + pac + "</code> → Lưu</li></ol>"
             + "<h2>macOS</h2><ol><li>Cài đặt hệ thống → Mạng → Wi-Fi → Chi tiết → Proxy</li>"
             + "<li>Cấu hình proxy tự động → URL <code>" + pac + "</code></li></ol>"
             + "<p>Chỉ cần khai báo một lần cho mạng Wi-Fi này. App nào bỏ qua proxy hệ thống (một số game) sẽ không đi qua được.</p>"
             + "</body></html>";
    }
}
