package com.v2ray.ang.iprotator;

import android.os.Bundle;
import android.service.voice.VoiceInteractionService;

/** Dịch vụ trợ lý. Hệ thống chạy nó khi app được chọn làm trợ lý kỹ thuật số mặc định. */
public class AssistService extends VoiceInteractionService {
    static volatile AssistService instance;
    static volatile Boolean pendingAirplane; // trạng thái máy bay cần đặt

    @Override public void onReady() { super.onReady(); instance = this; }
    @Override public void onShutdown() { instance = null; super.onShutdown(); }

    /** Bật (true) / tắt (false) chế độ máy bay. Trả về false nếu app chưa là trợ lý mặc định. */
    static boolean setAirplane(boolean on) {
        AssistService s = instance;
        if (s == null) return false;
        pendingAirplane = on;
        Bundle b = new Bundle();
        b.putBoolean("airplane", on);
        s.showSession(b, 0);
        return true;
    }
}
