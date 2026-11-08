package com.v2ray.ang.iprotator;

import android.content.Intent;
import android.speech.RecognitionService;

/** Bắt buộc phải có để Android cho chọn app làm trợ lý; không làm gì. */
public class AssistRecognitionService extends RecognitionService {
    @Override protected void onStartListening(Intent intent, Callback cb) {}
    @Override protected void onCancel(Callback cb) {}
    @Override protected void onStopListening(Callback cb) {}
}
