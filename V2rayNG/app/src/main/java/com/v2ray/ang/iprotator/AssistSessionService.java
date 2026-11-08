package com.v2ray.ang.iprotator;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class AssistSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new VoiceInteractionSession(this) {
            @Override
            public void onShow(Bundle a, int flags) {
                super.onShow(a, flags);
                Boolean on = (a != null && a.containsKey("airplane")) ? a.getBoolean("airplane") : AssistService.pendingAirplane;
                if (on == null) { hide(); return; }
                try {
                    Intent i = new Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE);
                    i.addCategory(Intent.CATEGORY_VOICE);
                    i.putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, on.booleanValue());
                    startVoiceActivity(i);
                } catch (Exception ignored) {}
                AssistService.pendingAirplane = null;
                new Handler(Looper.getMainLooper()).postDelayed(this::hide, 800);
            }
        };
    }
}
