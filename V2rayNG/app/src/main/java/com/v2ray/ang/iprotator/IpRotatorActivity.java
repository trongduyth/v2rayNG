package com.v2ray.ang.iprotator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import com.v2ray.ang.core.LauncherManager;
import com.v2ray.ang.handler.MmkvManager;
import com.v2ray.ang.handler.SettingsManager;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Bật/tắt 4G (cần ROOT), dò IP sau mỗi lần bật lại, khớp mẫu IP thì dừng.
 */
public class IpRotatorActivity extends Activity {

    /** Danh sách IP mục tiêu mặc định (khớp 2 octet đầu, vd 10.30 = 10.30.x.x). */
    static final String DEFAULT_TARGETS =
            "10.30;10.31;10.32;10.33;10.34;10.35;10.36;10.37;10.38;10.39;10.40;"
          + "10.41;10.42;10.43;10.44;10.45;10.46;10.47;10.48;10.49;10.50;"
          + "10.140;10.141;10.142;10.143;10.144;10.145;10.146;10.147;10.149;10.150;"
          + "10.110;10.111;10.112;10.113;10.114;10.115;10.116;10.117;10.118;10.119;10.120;10.121";

    private EditText edTarget, edOff, edWait, edMax;
    private RadioButton rbPublic, rbLocal, rbAssist, rbData, rbAirplane;
    private CheckBox cbV2ray;
    private TextView tvIp, tvLog;
    private ScrollView logScroll;
    private Button btnStart, btnStop;

    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        loadPrefs();
    }

    // ---------------- Giao diện ----------------
    private void buildUi() {
        int pad = dp(14);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Đổi IP 4G → V2Ray");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        root.addView(label("IP mục tiêu (nhiều mẫu cách nhau dấu phẩy; dùng * hoặc tiền tố, vd: 113.22.*, 27.72.10.5, 171.)"));
        edTarget = new EditText(this);
        edTarget.setHint("10.30;10.31;...");
        edTarget.setSingleLine(false);
        edTarget.setMaxLines(4);
        root.addView(edTarget);

        root.addView(label("Loại IP cần dò"));
        RadioGroup g1 = new RadioGroup(this);
        g1.setOrientation(RadioGroup.HORIZONTAL);
        rbPublic = radio("IP public (Internet)");
        rbLocal = radio("IP card 4G (nội bộ)");
        g1.addView(rbPublic); g1.addView(rbLocal);
        rbLocal.setChecked(true);
        root.addView(g1);

        root.addView(label("Cách ngắt mạng"));
        RadioGroup g2 = new RadioGroup(this);
        g2.setOrientation(RadioGroup.VERTICAL);
        rbAssist = radio("Trợ lý kỹ thuật số – chế độ máy bay (KHÔNG cần root)");
        rbData = radio("Root – tắt/bật dữ liệu di động");
        rbAirplane = radio("Root – chế độ máy bay");
        g2.addView(rbAssist); g2.addView(rbData); g2.addView(rbAirplane);
        rbAssist.setChecked(true);
        root.addView(g2);
        Button btnDefault = new Button(this);
        btnDefault.setText("Nạp lại danh sách IP mặc định (10.30…10.121)");
        btnDefault.setTextSize(12);
        btnDefault.setOnClickListener(v -> { edTarget.setText(DEFAULT_TARGETS); rbLocal.setChecked(true); });
        root.addView(btnDefault, 3);
        Button btnAssist = new Button(this);
        btnAssist.setText("Đặt app làm trợ lý kỹ thuật số mặc định");
        btnAssist.setTextSize(12);
        btnAssist.setOnClickListener(v -> openAssistSettings());
        root.addView(btnAssist);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        edOff = num("Tắt (giây)", "3");
        edWait = num("Chờ mạng tối đa (giây)", "30");
        edMax = num("Số lần tối đa (0 = vô hạn)", "50");
        row.addView(wrap("Tắt (s)", edOff));
        row.addView(wrap("Chờ mạng (s)", edWait));
        row.addView(wrap("Số lần (0=∞)", edMax));
        root.addView(row);

        cbV2ray = new CheckBox(this);
        cbV2ray.setText("Khớp IP → tự kết nối V2Ray (server đang chọn trong v2rayNG)");
        cbV2ray.setChecked(true);
        root.addView(cbV2ray);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = new Button(this); btnStart.setText("BẮT ĐẦU");
        btnStop = new Button(this); btnStop.setText("DỪNG"); btnStop.setEnabled(false);
        Button btnCheck = new Button(this); btnCheck.setText("Xem IP");
        Button btnRoot = new Button(this); btnRoot.setText("Kiểm tra root");
        for (Button x : new Button[]{btnStart, btnStop, btnCheck, btnRoot}) {
            btns.addView(x, new LinearLayout.LayoutParams(0, -2, 1));
            x.setTextSize(12);
        }
        root.addView(btns);

        tvIp = new TextView(this);
        tvIp.setText("IP hiện tại: —");
        tvIp.setTextSize(18);
        tvIp.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvIp.setGravity(Gravity.CENTER);
        tvIp.setPadding(0, dp(8), 0, dp(8));
        root.addView(tvIp);

        logScroll = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(12);
        logScroll.addView(tvLog);
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        btnStart.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop("Người dùng dừng."));
        btnCheck.setOnClickListener(v -> new Thread(() -> {
            String ip = getIp();
            showIp(ip);
            log("IP: " + (ip == null ? "không lấy được" : ip));
        }).start());
        btnRoot.setOnClickListener(v -> new Thread(() -> {
            String r = su("id");
            log(r != null && r.contains("uid=0") ? "Có quyền root ✔" : "KHÔNG có root ✘ – app không tự tắt/bật 4G được.");
        }).start());
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }
    private RadioButton radio(String s) {
        RadioButton r = new RadioButton(this);
        r.setText(s);
        r.setId(View.generateViewId());
        r.setTextSize(13);
        return r;
    }
    private EditText num(String hint, String def) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(def);
        e.setHint(hint);
        return e;
    }
    private LinearLayout wrap(String lb, EditText e) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(label(lb));
        l.addView(e);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return l;
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ---------------- Vòng lặp chính ----------------
    private void start() {
        final List<String> patterns = parsePatterns(edTarget.getText().toString());
        if (patterns.isEmpty()) { toast("Nhập IP mục tiêu trước."); return; }
        final int offSec = intOf(edOff, 3);
        final int waitSec = intOf(edWait, 30);
        final int max = intOf(edMax, 50);
        final int mode = rbAssist.isChecked() ? 0 : (rbData.isChecked() ? 1 : 2);
        savePrefs();

        final boolean connect = cbV2ray.isChecked();
        if (connect) {
            if (MmkvManager.INSTANCE.getSelectServer() == null) {
                toast("Chưa chọn server trong v2rayNG. Mở v2rayNG, thêm link vless:// và chọn server trước.");
                return;
            }
            if (SettingsManager.INSTANCE.isVpnMode()) {
                Intent vpn = VpnService.prepare(this);
                if (vpn != null) { startActivityForResult(vpn, REQ_VPN); return; }
            }
        }

        running = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        acquireWake();

        worker = new Thread(() -> {
            try {
                if (mode == 0) {
                    if (Build.VERSION.SDK_INT < 23) { log("✘ Cách trợ lý cần Android 6 trở lên."); finishRun(); return; }
                    if (AssistService.instance == null) {
                        log("✘ App chưa là trợ lý kỹ thuật số mặc định. Bấm nút 'Đặt app làm trợ lý...', chọn 'Đổi IP 4G', rồi chạy lại.");
                        finishRun();
                        return;
                    }
                } else {
                    String rootId = su("id");
                    if (rootId == null || !rootId.contains("uid=0")) {
                        log("✘ Không có quyền root. Hãy dùng cách 'Trợ lý kỹ thuật số'.");
                        finishRun();
                        return;
                    }
                }
                if (connect) {
                    LauncherManager.INSTANCE.stopService(IpRotatorActivity.this);
                    log("Đã ngắt V2Ray (nếu đang chạy) trước khi đổi IP");
                    sleepSec(1);
                }
                log("Bắt đầu. Mẫu: " + patterns + " | " + (mode == 0 ? "trợ lý – máy bay" : mode == 1 ? "root – dữ liệu" : "root – máy bay"));

                String ip = getIp();
                showIp(ip);
                log("IP ban đầu: " + ip);
                if (matches(ip, patterns)) { done(ip, 0, connect); return; }

                int n = 0;
                Set<String> seen = new LinkedHashSet<>();
                if (ip != null) seen.add(ip);
                while (running && (max == 0 || n < max)) {
                    n++;
                    log("— Lần " + n + ": ngắt mạng");
                    setNetwork(false, mode);
                    sleepSec(offSec);
                    if (!running) { setNetwork(true, mode); log("   đã bật lại mạng trước khi dừng"); break; }
                    log("   bật lại mạng");
                    setNetwork(true, mode);

                    ip = waitForIp(waitSec);
                    showIp(ip);
                    if (ip == null) { log("   ✘ không có mạng/IP sau " + waitSec + "s"); continue; }
                    boolean isNew = seen.add(ip);
                    log("   IP: " + ip + (isNew ? "" : " (trùng)"));
                    if (matches(ip, patterns)) { done(ip, n, connect); return; }
                }
                if (running) {
                    log("Hết " + max + " lần, chưa khớp. Đã gặp " + seen.size() + " IP khác nhau. Thoát app.");
                    finishRun();
                    exitApp();
                    return;
                }
            } catch (Exception e) {
                log("Lỗi: " + e);
            }
            finishRun();
        });
        worker.start();
    }

    private void done(String ip, int n, boolean connect) {
        log("✔ KHỚP: " + ip + " sau " + n + " lần." + (connect ? " Kết nối V2Ray rồi thoát." : " Thoát."));
        ui.post(() -> {
            toast("Khớp IP " + ip + (connect ? " – đang kết nối V2Ray" : ""));
            if (connect) {
                try {
                    if (!LauncherManager.INSTANCE.startServiceFromToggle(IpRotatorActivity.this))
                        log("✘ Không bật được V2Ray – kiểm tra server trong v2rayNG");
                } catch (Exception e) { log("✘ Lỗi bật V2Ray: " + e); }
            }
            try {
                android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100);
                tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 600);
            } catch (Exception ignored) {}
        });
        finishRun();
        exitApp();
    }

    /** Chạy xong: đóng hẳn app, xóa khỏi danh sách đa nhiệm, không để vòng lặp chạy ngầm. */
    private void exitApp() {
        ui.postDelayed(() -> {
            running = false;
            releaseWake();
            if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask(); else finish();
        }, 2500);
    }

    private void stop(String why) {
        running = false;
        if (worker != null) worker.interrupt();
        log(why);
    }

    private void finishRun() {
        running = false;
        releaseWake();
        ui.post(() -> { btnStart.setEnabled(true); btnStop.setEnabled(false); });
    }

    // ---------------- Mạng ----------------
    /** mode 0 = trợ lý (máy bay), 1 = root dữ liệu, 2 = root máy bay. on = có mạng. */
    private void setNetwork(boolean on, int mode) {
        if (mode == 0) {
            // máy bay BẬT = mất mạng
            if (!AssistService.setAirplane(!on)) log("   ✘ mất kết nối trợ lý – kiểm tra lại cài đặt trợ lý mặc định");
            sleepSec(1);
            return;
        }
        if (mode == 2) {
            if (Build.VERSION.SDK_INT >= 30) {
                su("cmd connectivity airplane-mode " + (on ? "disable" : "enable"));
            } else {
                su("settings put global airplane_mode_on " + (on ? 0 : 1)
                        + " && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + (!on));
            }
        } else {
            su("svc data " + (on ? "enable" : "disable"));
        }
    }

    /** Chờ có mạng rồi lấy IP, tối đa waitSec giây. */
    private String waitForIp(int waitSec) {
        long end = System.currentTimeMillis() + waitSec * 1000L;
        sleepSec(2);
        while (running && System.currentTimeMillis() < end) {
            if (rbLocal.isChecked() || isConnected()) {
                String ip = getIp();
                if (ip != null) return ip;
            }
            sleepSec(1);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private boolean isConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private String getIp() {
        return rbLocal.isChecked() ? localMobileIp() : publicIp();
    }

    private String publicIp() {
        String[] urls = {"https://api.ipify.org", "https://ipv4.icanhazip.com", "https://checkip.amazonaws.com"};
        for (String u : urls) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setUseCaches(false);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                String s = r.readLine();
                r.close();
                c.disconnect();
                if (s != null && s.trim().matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return s.trim();
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** IPv4 của card mạng di động (rmnet / ccmni / pdp...). */
    private String localMobileIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName().toLowerCase(Locale.US);
                if (!ni.isUp() || ni.isLoopback()) continue;
                if (!(n.contains("rmnet") || n.contains("ccmni") || n.contains("pdp") || n.contains("seth")
                        || n.contains("wwan") || n.contains("qmi"))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---------------- So khớp ----------------
    private static List<String> parsePatterns(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("[,;\\s]+")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** "10.30" = 10.30.x.x; "10.30." tiền tố; "113.22.*" wildcard; "27.72.10.5" chính xác. */
    static boolean matches(String ip, List<String> pats) {
        if (ip == null) return false;
        for (String p : pats) {
            if (p.contains("*")) {
                String rx = p.replace(".", "\\.").replace("*", "\\d*");
                if (ip.matches(rx) || ip.matches(rx + "(\\.\\d+)*")) return true;
            } else if (p.endsWith(".")) {
                if (ip.startsWith(p)) return true;
            } else if (p.split("\\.").length < 4) {
                if (ip.startsWith(p + ".")) return true;   // 10.30 -> 10.30.x.x (không khớp 10.300)
            } else if (ip.equals(p)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- Root ----------------
    private String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- Tiện ích ----------------
    private void log(String s) {
        final String line = tf.format(new Date()) + "  " + s + "\n";
        ui.post(() -> {
            tvLog.append(line);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    private void showIp(String ip) { ui.post(() -> tvIp.setText("IP hiện tại: " + (ip == null ? "—" : ip))); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private void sleepSec(int s) { try { Thread.sleep(s * 1000L); } catch (InterruptedException ignored) {} }
    private int intOf(EditText e, int def) {
        try { return Math.max(0, Integer.parseInt(e.getText().toString().trim())); } catch (Exception x) { return def; }
    }

    private void acquireWake() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iprotator:loop");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
    }
    private void releaseWake() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }

    private void savePrefs() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("t", edTarget.getText().toString())
                .putString("o", edOff.getText().toString())
                .putString("w", edWait.getText().toString())
                .putString("m", edMax.getText().toString())
                .putBoolean("local", rbLocal.isChecked())
                .putBoolean("v2", cbV2ray.isChecked())
                .putInt("mode", rbAssist.isChecked() ? 0 : rbData.isChecked() ? 1 : 2)
                .apply();
    }
    private void loadPrefs() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        edTarget.setText(p.getString("t", DEFAULT_TARGETS));
        edOff.setText(p.getString("o", "3"));
        edWait.setText(p.getString("w", "30"));
        edMax.setText(p.getString("m", "50"));
        (p.getBoolean("local", true) ? rbLocal : rbPublic).setChecked(true);
        cbV2ray.setChecked(p.getBoolean("v2", true));
        int md = p.getInt("mode", 0);
        (md == 1 ? rbData : md == 2 ? rbAirplane : rbAssist).setChecked(true);
    }

    private void openAssistSettings() {
        String[] acts = {"android.settings.VOICE_INPUT_SETTINGS", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "android.settings.SETTINGS"};
        for (String a : acts) {
            try { startActivity(new android.content.Intent(a)); toast("Chọn Trợ lý kỹ thuật số → Đổi IP 4G"); return; }
            catch (Exception ignored) {}
        }
    }

    private static final int REQ_VPN = 7101;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) start();
            else toast("Cần cho phép VPN để tự kết nối V2Ray.");
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        releaseWake();
        super.onDestroy();
    }
}
