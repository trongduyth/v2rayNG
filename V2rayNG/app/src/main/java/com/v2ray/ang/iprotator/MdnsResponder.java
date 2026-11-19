package com.v2ray.ang.iprotator;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Trả lời mDNS (chuẩn tên ".local" trong mạng LAN) để máy khách trong điểm phát Wi-Fi
 * gọi điện thoại bằng tên cố định HOST, dù dải IP điểm phát đổi mỗi lần bật.
 * Không cần data: mọi thứ diễn ra trong mạng Wi-Fi giữa hai máy.
 * Chạy cùng PacServer (tiến trình ":daemon"), chỉ khi đang chia sẻ.
 */
public final class MdnsResponder {
    private MdnsResponder() {}

    /** Tên hiển thị cho máy khách khai báo. */
    public static final String HOST = "namphuong.local";
    /** Các tên được trả lời (không phân biệt hoa thường). */
    private static final String[] NAMES = {"namphuong.local", "nam.phuong.local"};

    private static final int PORT = 5353;
    private static final int TTL = 60;

    private static volatile MulticastSocket sock;
    private static WifiManager.MulticastLock lock;
    private static final Set<String> joined = new HashSet<>();

    public static synchronized void start(Context ctx) {
        if (sock != null && !sock.isClosed()) return;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("v2ray-mdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) {}
        try {
            MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            s.setTimeToLive(255);
            s.setSoTimeout(5000);
            sock = s;
            synchronized (joined) { joined.clear(); }
            Thread t = new Thread(() -> loop(s), "mdns");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            sock = null;
            releaseLock();
        }
    }

    public static synchronized void stop() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        sock = null;
        releaseLock();
    }

    private static void releaseLock() {
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        lock = null;
    }

    private static InetAddress group() throws Exception {
        return InetAddress.getByName("224.0.0.251");
    }

    /** Tham gia nhóm multicast trên các card điểm phát (card có thể xuất hiện sau khi bật điểm phát). */
    private static void joinAll(MulticastSocket s) {
        try {
            InetSocketAddress g = new InetSocketAddress(group(), PORT);
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                if (!ShareManager.isShareIface(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String key = ni.getName() + "/" + a.getHostAddress();
                    synchronized (joined) {
                        if (joined.contains(key)) continue;
                        try { s.joinGroup(g, ni); } catch (Exception ignored) { /* đã tham gia */ }
                        joined.add(key);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void loop(MulticastSocket s) {
        byte[] buf = new byte[1500];
        long lastJoin = 0;
        while (!s.isClosed()) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastJoin > 5000) { joinAll(s); lastJoin = now; }
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (Exception e) {
                if (s.isClosed()) return;
                continue;
            }
            try { handle(s, p); } catch (Exception ignored) {}
        }
    }

    private static void handle(MulticastSocket s, DatagramPacket p) throws Exception {
        byte[] d = p.getData();
        int len = p.getLength();
        if (len < 12) return;
        if ((u16(d, 2) & 0x8000) != 0) return; // gói trả lời, bỏ qua
        int qd = u16(d, 4);
        int off = 12;
        for (int i = 0; i < qd; i++) {
            StringBuilder name = new StringBuilder();
            off = readName(d, len, off, name);
            if (off < 0 || off + 4 > len) return;
            int qtype = u16(d, off);
            off += 4;
            if (qtype != 1 && qtype != 255) continue; // chỉ trả lời A / ANY
            if (!matches(name.toString())) continue;
            String ip = ipFor(p.getAddress());
            if (ip == null) return;
            reply(s, p, u16(d, 0), name.toString(), ip);
            return;
        }
    }

    private static boolean matches(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        for (String x : NAMES) if (x.equals(n)) return true;
        return false;
    }

    /** IP điểm phát cùng dải /24 với máy khách đang hỏi. */
    private static String ipFor(InetAddress client) {
        String c = client.getHostAddress();
        int k = c.lastIndexOf('.');
        if (k > 0) {
            String prefix = c.substring(0, k + 1);
            for (String ip : ShareManager.hotspotIps()) if (ip.startsWith(prefix)) return ip;
        }
        return ShareManager.mainHotspotIp();
    }

    private static void reply(MulticastSocket s, DatagramPacket q, int id, String name, String ip) throws Exception {
        boolean legacy = q.getPort() != PORT; // máy hỏi kiểu DNS thường từ cổng khác: trả lời kèm ID và câu hỏi
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w16(o, legacy ? id : 0);
        w16(o, 0x8400);          // trả lời, có thẩm quyền
        w16(o, legacy ? 1 : 0);  // số câu hỏi
        w16(o, 1);               // số câu trả lời
        w16(o, 0);
        w16(o, 0);
        if (legacy) { writeName(o, name); w16(o, 1); w16(o, 1); }
        writeName(o, name);
        w16(o, 1);                          // A
        w16(o, legacy ? 1 : 0x8001);        // IN (+ cache-flush)
        w16(o, TTL >>> 16); w16(o, TTL & 0xffff);
        w16(o, 4);
        o.write(InetAddress.getByName(ip).getAddress());
        byte[] b = o.toByteArray();

        synchronized (s) {
            try {
                NetworkInterface ni = NetworkInterface.getByInetAddress(InetAddress.getByName(ip));
                if (ni != null) s.setNetworkInterface(ni);
            } catch (Exception ignored) {}
            s.send(new DatagramPacket(b, b.length, q.getSocketAddress()));          // gửi thẳng máy hỏi
            if (!legacy) s.send(new DatagramPacket(b, b.length, group(), PORT));     // và gửi cả nhóm
        }
    }

    private static int readName(byte[] d, int len, int off, StringBuilder out) {
        int end = -1, jumps = 0;
        while (true) {
            if (off >= len) return -1;
            int l = d[off] & 0xff;
            if (l == 0) { off++; break; }
            if ((l & 0xC0) == 0xC0) {
                if (off + 1 >= len || ++jumps > 10) return -1;
                if (end < 0) end = off + 2;
                off = ((l & 0x3f) << 8) | (d[off + 1] & 0xff);
                continue;
            }
            if (off + 1 + l > len) return -1;
            if (out.length() > 0) out.append('.');
            out.append(new String(d, off + 1, l, StandardCharsets.US_ASCII));
            off += 1 + l;
        }
        return end >= 0 ? end : off;
    }

    private static void writeName(ByteArrayOutputStream o, String name) {
        for (String part : name.split("\\.")) {
            if (part.isEmpty()) continue;
            byte[] b = part.getBytes(StandardCharsets.US_ASCII);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
    }

    private static int u16(byte[] d, int off) { return ((d[off] & 0xff) << 8) | (d[off + 1] & 0xff); }

    private static void w16(ByteArrayOutputStream o, int v) { o.write((v >>> 8) & 0xff); o.write(v & 0xff); }
}
