package com.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.services.AutoClickAccessibilityService;

public class AutoClickAPI {

    private static final String LOG_TAG = "AutoClickAPI";

    public static void onReceive(TermuxApiReceiver receiver,
                                 Context context,
                                 Intent intent) {

        AutoClickAccessibilityService service = 
                AutoClickAccessibilityService.getInstance();

        if (service == null) {
            Log.e(LOG_TAG, "Accessibility service belum aktif!");
            return;
        }

        // Ambil 'method' dari intent, defaultnya adalah CLICK
        String method = intent.getStringExtra("method");
        if (method == null) method = "CLICK";

        if ("CLICK".equals(method)) {
            int x = intent.getIntExtra("x", 500);
            int y = intent.getIntExtra("y", 900);
            int duration = intent.getIntExtra("duration", 100);
            
            // PERBAIKAN: Ganti service.click menjadi service.performGesture
            // Kita masukkan koordinat yang sama (x,y) ke (x,y) agar menjadi klik titik
            service.performGesture(x, y, x, y, duration);

        } else if ("SWIPE".equals(method)) {
            int x1 = intent.getIntExtra("x1", 0);
            int y1 = intent.getIntExtra("y1", 0);
            int x2 = intent.getIntExtra("x2", 0);
            int y2 = intent.getIntExtra("y2", 0);
            int duration = intent.getIntExtra("duration", 500);
            service.performGesture(x1, y1, x2, y2, duration);

        } else if ("CLICK_TEXT".equals(method)) {
            String text = intent.getStringExtra("text");
            if (text != null) service.clickText(text);
        }
    }
}
