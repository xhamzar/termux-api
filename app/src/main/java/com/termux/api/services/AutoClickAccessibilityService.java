package com.termux.api.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickAccessibilityService extends AccessibilityService {

    private static final String LOG_TAG = "AutoClickService";
    private static AutoClickAccessibilityService instance;

    public static AutoClickAccessibilityService getInstance(){
        return instance;
    }

    @Override
    protected void onServiceConnected(){
        super.onServiceConnected();
        instance = this;

        // Konfigurasi service secara programmatik
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;

        setServiceInfo(info);
        Log.d(LOG_TAG, "Service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event){
        // Tidak digunakan untuk auto-click
    }

    @Override
    public void onInterrupt(){
        Log.e(LOG_TAG, "Service interrupted");
    }

    // Perubahan: Menambahkan parameter 'duration'
    public void click(int x, int y, int duration){

        if(instance == null){
            Log.e(LOG_TAG, "Service instance NULL - Pastikan Accessibility diaktifkan di setelan HP");
            return;
        }

        Log.d(LOG_TAG, "Mengeksekusi klik di: " + x + ", " + y + " durasi: " + duration + "ms");

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();

        // StrokeDescription(jalur, waktu_mulai, durasi_tekan)
        // Kita menggunakan parameter duration yang dikirim dari Termux
        builder.addStroke(
                new GestureDescription.StrokeDescription(path, 0, duration)
        );

        dispatchGesture(
                builder.build(),
                new GestureResultCallback(){
                    @Override
                    public void onCompleted(GestureDescription gestureDescription){
                        Log.d(LOG_TAG, "Gesture completed");
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription){
                        Log.e(LOG_TAG, "Gesture cancelled - Mungkin layar mati atau ada overlay");
                    }
                },
                null
        );
    }
}
