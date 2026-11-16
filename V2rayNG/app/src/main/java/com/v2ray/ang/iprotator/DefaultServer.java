package com.v2ray.ang.iprotator;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.v2ray.ang.BuildConfig;
import com.v2ray.ang.handler.AngConfigManager;
import com.v2ray.ang.handler.MmkvManager;

import java.util.List;

/**
 * Server VLESS mặc định, nhúng lúc build từ GitHub Secret DEFAULT_VLESS.
 * Lần đầu mở app: nếu chưa có server nào thì tự thêm và chọn server này.
 * Chỉ chạy một lần, nên nếu anh xóa server thì app không tự thêm lại.
 */
public final class DefaultServer {
    private DefaultServer() {}

    public static synchronized void ensure(Context ctx) {
        String link = BuildConfig.DEFAULT_VLESS;
        if (TextUtils.isEmpty(link)) return;
        SharedPreferences sp = ctx.getSharedPreferences("iprotator_seed", Context.MODE_PRIVATE);
        if (sp.getBoolean("seeded_v1", false)) return;
        try {
            if (MmkvManager.INSTANCE.decodeAllServerList().isEmpty()) {
                AngConfigManager.INSTANCE.importBatchConfig(link, "", true);
            }
            List<String> all = MmkvManager.INSTANCE.decodeAllServerList();
            if (TextUtils.isEmpty(MmkvManager.INSTANCE.getSelectServer()) && !all.isEmpty()) {
                MmkvManager.INSTANCE.setSelectServer(all.get(0));
            }
            sp.edit().putBoolean("seeded_v1", true).apply();
        } catch (Throwable ignored) {
        }
    }
}
