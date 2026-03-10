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

        // Mengambil koordinat X, Y, dan Durasi (default durasi 100ms)
        int x = intent.getIntExtra("x", 500);
        int y = intent.getIntExtra("y", 900);
        int duration = intent.getIntExtra("duration", 100); 

        Log.d(LOG_TAG, "Request diterima: X=" + x + " Y=" + y + " Durasi=" + duration + "ms");

        AutoClickAccessibilityService service =
                AutoClickAccessibilityService.getInstance();

        if (service == null) {
            Log.e(LOG_TAG, "Accessibility service belum aktif di Pengaturan HP!");
            return;
        }

        // Memanggil fungsi klik dengan durasi kustom
        service.click(x, y, duration);
    }
}
