package com.v2ray.ang.iprotator;

import android.content.Context;

import com.v2ray.ang.AppConfig;
import com.v2ray.ang.core.LauncherManager;
import com.v2ray.ang.handler.MmkvManager;
import com.v2ray.ang.handler.SettingsManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Chia sẻ V2Ray cho máy khác qua điểm phát Wi-Fi (không cần root).
 * Cách làm: mở cổng proxy của Xray (SOCKS + HTTP chung một cổng) ra mạng LAN,
 * kèm máy chủ PAC nhỏ để máy khách chỉ cần khai báo một địa chỉ "proxy tự động".
 */
public final class ShareManager {
    private ShareManager() {}

    /** Cổng proxy cố định để máy khách khai báo một lần là xong. */
    public static final int PROXY_PORT = 10808;

    public static boolean isEnabled() {
        return MmkvManager.INSTANCE.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false);
    }

    /**
     * Bật/tắt chia sẻ. Bật thì: mở cổng ra LAN, cổng cố định 10808, bật proxy cục bộ, cho UDP qua SOCKS.
     * Nếu V2Ray đang chạy thì khởi động lại để nhận cấu hình mới; máy chủ PAC chạy cùng V2Ray (PacServer).
     */
    public static void setEnabled(Context ctx, boolean on) {
        MmkvManager.INSTANCE.encodeSettings(AppConfig.PREF_PROXY_SHARING, on);
        if (on) {
            MmkvManager.INSTANCE.encodeSettings(AppConfig.PREF_ENABLE_LOCAL_PROXY, true);
            MmkvManager.INSTANCE.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false);
            MmkvManager.INSTANCE.encodeSettings(AppConfig.PREF_SOCKS_PORT, String.valueOf(PROXY_PORT));
            MmkvManager.INSTANCE.encodeSettings(AppConfig.PREF_SOCKS_ENABLE_UDP, true);
        }
        // Chỉ khởi động lại nếu đang chạy (không tự bật khi đang tắt)
        try { LauncherManager.INSTANCE.restartService(ctx); } catch (Exception ignored) {}
    }

    /** Cổng có đang mở trên máy không (kiểm tra từ tiến trình giao diện). */
    public static boolean portOpen(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static int proxyPort() {
        try { return SettingsManager.INSTANCE.getSocksPort(); } catch (Throwable t) { return PROXY_PORT; }
    }

    /** Địa chỉ IPv4 của điểm phát Wi-Fi / chia sẻ USB (wlan, ap, swlan, softap, rndis, ncm). */
    public static List<String> hotspotIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.US);
                boolean share = n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")
                        || n.startsWith("softap") || n.startsWith("rndis") || n.startsWith("ncm")
                        || n.startsWith("bt-pan") || n.startsWith("eth");
                if (!share) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** IP hay gặp nhất để hiển thị: ưu tiên 192.168.x.1 (điểm phát). */
    public static String mainHotspotIp() {
        List<String> ips = hotspotIps();
        for (String ip : ips) if (ip.endsWith(".1")) return ip;
        return ips.isEmpty() ? null : ips.get(0);
    }

    /** Nội dung file PAC: mọi trang đi qua proxy của điện thoại, trừ mạng nội bộ. */
    public static String pac(String ip, int port) {
        return "function FindProxyForURL(url, host) {\n"
             + "  if (isPlainHostName(host) || host == \"localhost\" || shExpMatch(host, \"*.local\")) return \"DIRECT\";\n"
             + "  if (/^\\d+\\.\\d+\\.\\d+\\.\\d+$/.test(host) && (isInNet(host, \"10.0.0.0\", \"255.0.0.0\") ||\n"
             + "      isInNet(host, \"172.16.0.0\", \"255.240.0.0\") || isInNet(host, \"192.168.0.0\", \"255.255.0.0\") ||\n"
             + "      isInNet(host, \"127.0.0.0\", \"255.0.0.0\"))) return \"DIRECT\";\n"
             + "  return \"PROXY " + ip + ":" + port + "; SOCKS5 " + ip + ":" + port + "\";\n"
             + "}\n";
    }
}
