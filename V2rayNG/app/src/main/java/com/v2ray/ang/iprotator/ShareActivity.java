package com.v2ray.ang.iprotator;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.v2ray.ang.core.LauncherManager;
import com.v2ray.ang.handler.SettingsManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.EnumMap;
import java.util.Map;

/** Màn hình "Chia sẻ Wi-Fi": một nút bật, hiện địa chỉ PAC + mã QR cho máy khác. */
public class ShareActivity extends Activity {

    private static final int REQ_VPN = 7201;

    private TextView tvStatus, tvAddr, tvClients;
    private ImageView ivQr;
    private Button btnToggle, btnV2ray;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String lastQrText = "";
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 2000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Chia sẻ Wi-Fi cho máy khác");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(14);
        tvStatus.setPadding(0, dp(10), 0, dp(10));
        root.addView(tvStatus);

        btnToggle = new Button(this);
        btnToggle.setTextSize(16);
        btnToggle.setOnClickListener(v -> {
            boolean on = !ShareManager.isEnabled();
            ShareManager.setEnabled(this, on);
            toast(on ? "Đã bật chia sẻ" : "Đã tắt chia sẻ");
            ui.postDelayed(this::refresh, 2500);
            refresh();
        });
        root.addView(btnToggle);

        btnV2ray = new Button(this);
        btnV2ray.setText("Kết nối V2Ray");
        btnV2ray.setOnClickListener(v -> startV2ray());
        root.addView(btnV2ray);

        Button btnHotspot = new Button(this);
        btnHotspot.setText("Mở cài đặt điểm phát Wi-Fi");
        btnHotspot.setOnClickListener(v -> openHotspotSettings());
        root.addView(btnHotspot);

        tvAddr = new TextView(this);
        tvAddr.setTextSize(15);
        tvAddr.setTypeface(Typeface.MONOSPACE);
        tvAddr.setPadding(0, dp(14), 0, dp(6));
        tvAddr.setTextIsSelectable(true);
        root.addView(tvAddr);

        Button btnCopy = new Button(this);
        btnCopy.setText("Sao chép địa chỉ proxy tự động");
        btnCopy.setOnClickListener(v -> copyPac());
        root.addView(btnCopy);

        ivQr = new ImageView(this);
        ivQr.setAdjustViewBounds(true);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(240), dp(240));
        qp.gravity = Gravity.CENTER_HORIZONTAL;
        qp.topMargin = dp(8);
        root.addView(ivQr, qp);

        TextView guide = new TextView(this);
        guide.setTextSize(13);
        guide.setPadding(0, dp(10), 0, dp(10));
        guide.setText("Máy khác chỉ cần:\n"
                + "1. Nối Wi-Fi điểm phát của điện thoại này.\n"
                + "2. Quét mã QR (hoặc mở trình duyệt vào địa chỉ trang hướng dẫn) để xem cách khai báo cho Android, iPhone, Windows, macOS.\n"
                + "3. Khai báo một lần: Proxy → Tự động → dán " + PacServer.fixedPacUrl()
                + ". Điểm phát đổi dải IP cũng không phải sửa lại.\n\n"
                + "Lưu ý: app nào bỏ qua proxy hệ thống (một số game) sẽ không đi qua được.");
        root.addView(guide);

        tvClients = new TextView(this);
        tvClients.setTextSize(13);
        tvClients.setTypeface(Typeface.MONOSPACE);
        root.addView(tvClients);

        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { ui.removeCallbacks(tick); super.onPause(); }

    private void refresh() {
        new Thread(() -> {
            boolean share = ShareManager.isEnabled();
            int port = ShareManager.proxyPort();
            boolean v2 = ShareManager.portOpen(port);
            boolean pac = ShareManager.portOpen(PacServer.PAC_PORT);
            String ip = ShareManager.mainHotspotIp();
            String clients = readClients();
            ui.post(() -> render(share, v2, pac, ip, port, clients));
        }).start();
    }

    private void render(boolean share, boolean v2, boolean pac, String ip, int port, String clients) {
        StringBuilder st = new StringBuilder();
        st.append(share ? "✔ Chia sẻ: BẬT" : "✘ Chia sẻ: TẮT").append('\n');
        st.append(v2 ? "✔ V2Ray: đang chạy" : "✘ V2Ray: chưa chạy").append('\n');
        st.append(ip != null ? "✔ Điểm phát Wi-Fi: " + ip : "✘ Điểm phát Wi-Fi: chưa bật").append('\n');
        if (share && v2) st.append(pac ? "✔ Proxy tự động (PAC): sẵn sàng" : "… Proxy tự động (PAC): đang khởi động");
        tvStatus.setText(st.toString().trim());

        btnToggle.setText(share ? "TẮT CHIA SẺ" : "BẬT CHIA SẺ");
        btnV2ray.setVisibility(v2 ? Button.GONE : Button.VISIBLE);

        if (ip != null && share) {
            String pacUrl = "http://" + ip + ":" + PacServer.PAC_PORT + "/p";
            String guideUrl = "http://" + ip + ":" + PacServer.PAC_PORT + "/";
            tvAddr.setText("Proxy tự động (khai báo một lần, dùng mãi):\n" + PacServer.fixedPacUrl()
                    + "\n\nMáy không nhận địa chỉ trên thì dùng:\n" + pacUrl
                    + "\n\nProxy thủ công (HTTP/SOCKS5):\n" + ip + " : " + port
                    + "\n\nTrang hướng dẫn cho máy khác:\n" + guideUrl);
            if (!guideUrl.equals(lastQrText)) {
                Bitmap bm = qr(guideUrl, dp(240));
                if (bm != null) ivQr.setImageBitmap(bm);
                lastQrText = guideUrl;
            }
            ivQr.setVisibility(ImageView.VISIBLE);
        } else {
            tvAddr.setText(share ? "Bật điểm phát Wi-Fi để hiện địa chỉ cho máy khác." : "Bấm BẬT CHIA SẺ để máy khác dùng chung V2Ray.");
            ivQr.setVisibility(ImageView.GONE);
            lastQrText = "";
        }
        tvClients.setText(clients.isEmpty() ? "" : "Máy khách đã nhận cấu hình:\n" + clients);
    }

    private String readClients() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(new File(getFilesDir(), PacServer.CLIENT_FILE)))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length == 2) sb.append("  ").append(p[0]).append("   lúc ").append(p[1]).append('\n');
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private void copyPac() {
        String url = PacServer.fixedPacUrl();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("PAC", url));
        toast("Đã sao chép: " + url);
    }

    private void startV2ray() {
        if (SettingsManager.INSTANCE.isVpnMode()) {
            Intent vpn = VpnService.prepare(this);
            if (vpn != null) { startActivityForResult(vpn, REQ_VPN); return; }
        }
        LauncherManager.INSTANCE.startServiceFromToggle(this);
        ui.postDelayed(this::refresh, 2500);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN && res == RESULT_OK) startV2ray();
    }

    private void openHotspotSettings() {
        String[][] targets = {
                {"com.android.settings", "com.android.settings.TetherSettings"},
                {"com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setClassName(t[0], t[1]);
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        try { startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)); }
        catch (Exception e) { toast("Không mở được cài đặt điểm phát"); }
    }

    private static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
            h.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, h);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            bm.setPixels(px, 0, size, 0, 0, size, size);
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
