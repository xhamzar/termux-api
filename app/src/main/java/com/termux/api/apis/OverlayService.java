package com.termux.api.apis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.api.R;
import com.termux.api.activities.TermuxAPIMainActivity;
import com.termux.shared.logger.Logger;

/** Foreground service that owns and cleans up the floating overlay window. */
public class OverlayService extends Service {

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_STATUS = "status";
    public static final String ACTION_SHOW = "show";
    public static final String ACTION_HIDE = "hide";
    public static final String ACTION_MOVE = "move";
    public static final String ACTION_RESIZE = "resize";

    static final String EXTRA_RESULT_RECEIVER = "com.termux.api.overlay.RESULT_RECEIVER";
    static final String RESULT_ERROR = "error";

    private static final String LOG_TAG = "OverlayService";
    private static final String NOTIFICATION_CHANNEL_ID = "termux_api_overlay";
    private static final int NOTIFICATION_ID = 0x4f564c;
    private static final int DEFAULT_WIDTH_DP = 240;
    private static final int DEFAULT_HEIGHT_DP = 96;

    private static volatile Snapshot snapshot = Snapshot.stopped();

    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private OverlayLayout overlayView;
    private TextView textView;
    private TextView statusView;
    private String overlayText;
    private int tapCount;
    private boolean overlayAttached;

    public static boolean isRunning() {
        return snapshot.running;
    }

    public static Snapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.logDebug(LOG_TAG, "onCreate");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(NOTIFICATION_ID, createNotification());
        snapshot = new Snapshot(true, false, "", 0, 0, 0, 0, 0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        Logger.logDebug(LOG_TAG, "onStartCommand: " + action);

        String error = null;
        boolean stopAfterResult = false;
        try {
            switch (action) {
                case ACTION_START:
                    createOrUpdateOverlay(intent, true);
                    break;
                case ACTION_SHOW:
                    createOrUpdateOverlay(intent, true);
                    break;
                case ACTION_HIDE:
                    setOverlayVisible(false);
                    break;
                case ACTION_MOVE:
                    moveOverlay(intent.getIntExtra("x", 0), intent.getIntExtra("y", 0));
                    break;
                case ACTION_RESIZE:
                    resizeOverlay(intent.getIntExtra("width", 1), intent.getIntExtra("height", 1));
                    break;
                case ACTION_STOP:
                    removeOverlay();
                    snapshot = Snapshot.stopped();
                    stopAfterResult = true;
                    break;
                default:
                    error = "Unknown overlay action: " + action;
                    stopAfterResult = !overlayAttached;
            }
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply overlay command", e);
            error = "Android rejected the overlay operation";
            if (!overlayAttached) {
                snapshot = Snapshot.stopped();
                stopAfterResult = true;
            }
        }

        sendResult(intent, error);
        if (stopAfterResult) {
            stopForeground(true);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void createOrUpdateOverlay(Intent intent, boolean visible) {
        if (overlayView == null) createOverlayView();

        if (intent.hasExtra("text")) overlayText = intent.getStringExtra("text");
        if (overlayText == null || overlayText.isEmpty()) {
            overlayText = getString(R.string.overlay_default_text);
        }
        textView.setText(overlayText);

        if (intent.hasExtra("x")) windowParams.x = intent.getIntExtra("x", 0);
        if (intent.hasExtra("y")) windowParams.y = intent.getIntExtra("y", 0);
        if (intent.hasExtra("width")) windowParams.width = intent.getIntExtra("width", 1);
        if (intent.hasExtra("height")) windowParams.height = intent.getIntExtra("height", 1);
        clampPosition();
        overlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
        windowManager.updateViewLayout(overlayView, windowParams);
        publishSnapshot();
    }

    private void createOverlayView() {
        int width = dpToPixels(DEFAULT_WIDTH_DP);
        int height = dpToPixels(DEFAULT_HEIGHT_DP);
        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        windowParams = new WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = 0;
        windowParams.y = dpToPixels(80);

        overlayView = new OverlayLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setGravity(Gravity.CENTER_VERTICAL);
        overlayView.setPadding(dpToPixels(16), dpToPixels(10), dpToPixels(16), dpToPixels(10));
        overlayView.setElevation(dpToPixels(8));
        overlayView.setContentDescription(getString(R.string.overlay_content_description));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(235, 32, 33, 36));
        background.setCornerRadius(dpToPixels(12));
        background.setStroke(dpToPixels(1), Color.argb(220, 120, 170, 255));
        overlayView.setBackground(background);

        textView = new TextView(this);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(16);
        textView.setMaxLines(4);
        overlayView.addView(textView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        statusView = new TextView(this);
        statusView.setTextColor(Color.LTGRAY);
        statusView.setTextSize(12);
        statusView.setText(R.string.overlay_drag_hint);
        overlayView.addView(statusView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        configureInputHandling();
        windowManager.addView(overlayView, windowParams);
        overlayAttached = true;
    }

    private void configureInputHandling() {
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        overlayView.setOnClickListener(view -> {
            tapCount++;
            statusView.setText(getResources().getQuantityString(
                R.plurals.overlay_tap_status, tapCount, tapCount));
            publishSnapshot();
        });
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int downWindowX;
            private int downWindowY;
            private boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downWindowX = windowParams.x;
                        downWindowY = windowParams.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - downRawX;
                        float deltaY = event.getRawY() - downRawY;
                        if (!dragging && (Math.abs(deltaX) > touchSlop || Math.abs(deltaY) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            windowParams.x = downWindowX + Math.round(deltaX);
                            windowParams.y = downWindowY + Math.round(deltaY);
                            clampPosition();
                            windowManager.updateViewLayout(overlayView, windowParams);
                            publishSnapshot();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void setOverlayVisible(boolean visible) {
        requireOverlayView();
        overlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
        publishSnapshot();
    }

    private void moveOverlay(int x, int y) {
        requireOverlayView();
        windowParams.x = x;
        windowParams.y = y;
        clampPosition();
        windowManager.updateViewLayout(overlayView, windowParams);
        publishSnapshot();
    }

    private void resizeOverlay(int width, int height) {
        requireOverlayView();
        windowParams.width = width;
        windowParams.height = height;
        clampPosition();
        windowManager.updateViewLayout(overlayView, windowParams);
        publishSnapshot();
    }

    private void requireOverlayView() {
        if (!overlayAttached || overlayView == null) {
            throw new IllegalStateException("Overlay has not been created");
        }
    }

    private void clampPosition() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - windowParams.width);
        int maxY = Math.max(0, metrics.heightPixels - windowParams.height);
        windowParams.x = Math.max(0, Math.min(windowParams.x, maxX));
        windowParams.y = Math.max(0, Math.min(windowParams.y, maxY));
    }

    private void publishSnapshot() {
        if (!overlayAttached || overlayView == null) return;
        snapshot = new Snapshot(
            true,
            overlayView.getVisibility() == View.VISIBLE,
            overlayText == null ? "" : overlayText,
            windowParams.x,
            windowParams.y,
            windowParams.width,
            windowParams.height,
            tapCount);
    }

    private void sendResult(Intent intent, @Nullable String error) {
        ResultReceiver receiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
        if (receiver == null) return;
        Bundle data = new Bundle();
        if (error != null) data.putString(RESULT_ERROR, error);
        receiver.send(error == null ? 0 : 1, data);
    }

    private Notification createNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.overlay_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.overlay_notification_channel_description));
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, TermuxAPIMainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this, 0, openIntent, pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));
        Intent stopIntent = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setSmallIcon(R.drawable.ic_event_note_black_24dp)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(
                0, getString(R.string.overlay_notification_stop), stopPendingIntent).build())
            .build();
    }

    private int pendingIntentFlags(int flags) {
        return flags | PendingIntent.FLAG_IMMUTABLE;
    }

    private int dpToPixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void removeOverlay() {
        if (overlayAttached && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (IllegalArgumentException e) {
                Logger.logWarn(LOG_TAG, "Overlay view was already removed");
            }
        }
        overlayView = null;
        overlayAttached = false;
        textView = null;
        statusView = null;
        windowParams = null;
        overlayText = null;
        tapCount = 0;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlayAttached && overlayView != null) {
            clampPosition();
            windowManager.updateViewLayout(overlayView, windowParams);
            publishSnapshot();
        }
    }

    @Override
    public void onDestroy() {
        Logger.logDebug(LOG_TAG, "onDestroy");
        removeOverlay();
        snapshot = Snapshot.stopped();
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static final class Snapshot {
        public final boolean running;
        public final boolean visible;
        public final String text;
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final int tapCount;

        Snapshot(boolean running, boolean visible, String text, int x, int y,
                int width, int height, int tapCount) {
            this.running = running;
            this.visible = visible;
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.tapCount = tapCount;
        }

        static Snapshot stopped() {
            return new Snapshot(false, false, "", 0, 0, 0, 0, 0);
        }
    }

    private static class OverlayLayout extends LinearLayout {
        OverlayLayout(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
