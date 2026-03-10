package com.termux.api.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class AutoClickAccessibilityService extends AccessibilityService {

    private static final String LOG_TAG = "AutoClickService";
    private static AutoClickAccessibilityService instance;

    public static AutoClickAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        // Konfigurasi agar bisa membaca konten jendela (Window Content)
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                     AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;

        setServiceInfo(info);
        Log.d(LOG_TAG, "Super API Service Connected");
    }

    // --- FUNGSI 1: KLIK & SWIPE (GESTURE) ---
    public void performGesture(int x1, int y1, int x2, int y2, int duration) {
        if (instance == null) return;

        Path path = new Path();
        path.moveTo(x1, y1);
        // Jika koordinat tujuan berbeda, maka akan menjadi Swipe
        if (x1 != x2 || y1 != y2) {
            path.lineTo(x2, y2);
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));

        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(LOG_TAG, "Gesture Berhasil");
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.e(LOG_TAG, "Gesture Dibatalkan");
            }
        }, null);
    }

    // --- FUNGSI 2: KLIK TOMBOL BERDASARKAN TEKS ---
    public boolean clickText(String text) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return false;

        List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                // Coba klik node itu sendiri atau parent-nya
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                
                AccessibilityNodeInfo parent = node.getParent();
                while (parent != null) {
                    if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                    parent = parent.getParent();
                }
            }
        }
        return false;
    }

    // --- FUNGSI 3: SCROLL OTOMATIS ---
    public void scroll(boolean forward) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            rootNode.performAction(forward ? 
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : 
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Mendeteksi notifikasi baru yang muncul di layar
        if (event.getEventType() == AccessibilityEvent.TYPES_ALL_MASK && event.getText() != null) {
            Log.d(LOG_TAG, "Event Terdeteksi: " + event.getText().toString());
        }
    }

    @Override
    public void onInterrupt() {
        Log.e(LOG_TAG, "Service interrupted");
    }
}
