package com.termux.api.apis;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import com.termux.api.R;
import com.termux.api.activities.TermuxAPIMainActivity;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Foreground service that owns and cleans up the floating overlay window. */
public class OverlayService extends Service {

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_STATUS = "status";
    public static final String ACTION_SHOW = "show";
    public static final String ACTION_HIDE = "hide";
    public static final String ACTION_MOVE = "move";
    public static final String ACTION_RESIZE = "resize";
    public static final String ACTION_UPDATE = "update";
    public static final String ACTION_EVENTS = "events";
    public static final String ACTION_CLEAR_EVENTS = "clear_events";
    public static final String ACTION_CONTENT = "content";
    public static final String ACTION_CLEAR_CONTENT = "clear_content";
    public static final String ACTION_PLAY = "play";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_SEEK = "seek";
    public static final String ACTION_VOLUME = "volume";

    static final String EXTRA_RESULT_RECEIVER = "com.termux.api.overlay.RESULT_RECEIVER";
    static final String RESULT_ERROR = "error";

    private static final String LOG_TAG = "OverlayService";
    private static final String NOTIFICATION_CHANNEL_ID = "termux_api_overlay";
    private static final int NOTIFICATION_ID = 0x4f564c;
    private static final int DEFAULT_WIDTH_DP = 240;
    private static final int DEFAULT_HEIGHT_DP = 96;
    private static final int MIN_BUTTON_HEIGHT_DP = 160;
    private static final int MIN_CONTENT_HEIGHT_DP = 320;
    private static final int MAX_IMAGE_DIMENSION = 2_048;
    private static final int MAX_EVENTS = 100;

    private static final Object EVENT_LOCK = new Object();
    private static final ArrayDeque<OverlayEvent> eventQueue = new ArrayDeque<>();
    private static volatile Snapshot snapshot = Snapshot.stopped();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable playbackPositionUpdater = new Runnable() {
        @Override
        public void run() {
            if (mediaPrepared && mediaPlayer != null && "playing".equals(playbackState)) {
                try {
                    positionMs = Math.max(0, mediaPlayer.getCurrentPosition());
                    publishSnapshot();
                    mainHandler.postDelayed(this, 1_000);
                } catch (IllegalStateException e) {
                    Logger.logWarn(LOG_TAG, "Unable to update video position");
                }
            }
        }
    };

    private WindowManager windowManager;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private WindowManager.LayoutParams windowParams;
    private OverlayLayout overlayView;
    private TextView textView;
    private TextView statusView;
    private ProgressBar progressBar;
    private HorizontalScrollView buttonScroller;
    private LinearLayout buttonRow;
    private FrameLayout contentContainer;
    private WebView webView;
    private ImageView imageView;
    private Bitmap displayedBitmap;
    private TextureView videoView;
    private MediaPlayer mediaPlayer;
    private Surface videoSurface;
    private String overlayText;
    private String contentType = "none";
    private String contentSource = "";
    private String playbackState = "none";
    private int tapCount;
    private int progress = -1;
    private int buttonCount;
    private int durationMs;
    private int positionMs;
    private int pendingSeekMs = -1;
    private int volumePercent = 100;
    private int contentGeneration;
    private boolean autoplay;
    private boolean javascriptEnabled;
    private boolean focusable;
    private boolean mediaPrepared;
    private boolean overlayAttached;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = change -> {
        if ((change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) &&
                mediaPrepared && mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            positionMs = Math.max(0, mediaPlayer.getCurrentPosition());
            playbackState = "paused";
            autoplay = false;
            stopPlaybackPositionUpdates();
            if (windowParams != null) {
                enqueueEvent("video_paused", "audio_focus", windowParams.x, windowParams.y);
            }
            publishSnapshot();
        }
    };

    public static boolean isRunning() {
        return snapshot.running;
    }

    public static Snapshot getSnapshot() {
        return snapshot;
    }

    public static List<OverlayEvent> getEvents(boolean clear) {
        synchronized (EVENT_LOCK) {
            List<OverlayEvent> events = new ArrayList<>(eventQueue);
            if (clear) eventQueue.clear();
            updateSnapshotEventCount();
            return events;
        }
    }

    public static void clearEvents() {
        synchronized (EVENT_LOCK) {
            eventQueue.clear();
            updateSnapshotEventCount();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.logDebug(LOG_TAG, "onCreate");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        startForeground(NOTIFICATION_ID, createNotification());
        clearEvents();
        snapshot = new Snapshot(true, false, "", 0, 0, 0, 0, 0, -1, 0, 0,
            "none", "", "none", 0, 0, 100, false, false);
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
                case ACTION_UPDATE:
                    requireOverlayView();
                    applyUpdates(intent);
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
                case ACTION_CONTENT:
                    requireOverlayView();
                    showContent(intent);
                    break;
                case ACTION_CLEAR_CONTENT:
                case "clear-content":
                    requireOverlayView();
                    clearContent();
                    break;
                case ACTION_PLAY:
                    controlPlayback(true);
                    break;
                case ACTION_PAUSE:
                    controlPlayback(false);
                    break;
                case ACTION_SEEK:
                    seekVideo(intent.getIntExtra("position_ms", 0));
                    break;
                case ACTION_VOLUME:
                    setVideoVolume(intent.getIntExtra("volume", 100));
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
        } catch (IllegalArgumentException | IllegalStateException e) {
            Logger.logWarn(LOG_TAG, "Overlay command rejected: " + e.getMessage());
            error = e.getMessage();
            if (ACTION_CONTENT.equals(action) && overlayAttached) resetFailedContent();
            if (!overlayAttached) {
                snapshot = Snapshot.stopped();
                stopAfterResult = true;
            }
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply overlay command", e);
            error = "Android rejected the overlay operation";
            if (ACTION_CONTENT.equals(action) && overlayAttached) resetFailedContent();
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

        applyUpdates(intent);
        overlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
        publishSnapshot();
    }

    private void applyUpdates(Intent intent) {
        requireOverlayView();

        if (intent.hasExtra("text")) overlayText = intent.getStringExtra("text");
        if (overlayText == null || overlayText.isEmpty()) {
            overlayText = getString(R.string.overlay_default_text);
        }
        textView.setText(overlayText);

        if (intent.hasExtra("x")) windowParams.x = intent.getIntExtra("x", 0);
        if (intent.hasExtra("y")) windowParams.y = intent.getIntExtra("y", 0);
        if (intent.hasExtra("width")) windowParams.width = intent.getIntExtra("width", 1);
        if (intent.hasExtra("height")) windowParams.height = intent.getIntExtra("height", 1);
        if (intent.hasExtra("progress")) updateProgress(intent.getIntExtra("progress", -1));
        if (intent.hasExtra("focusable")) {
            setWindowFocusable(intent.getBooleanExtra("focusable", false));
        }
        if (intent.hasExtra("buttons")) {
            updateButtons(intent.getStringExtra("buttons"), !intent.hasExtra("height"));
        }
        clampPosition();
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
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        contentContainer = new FrameLayout(this);
        contentContainer.setVisibility(View.GONE);
        contentContainer.setContentDescription(getString(R.string.overlay_media_description));
        overlayView.addView(contentContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        progressBar.setContentDescription(getString(R.string.overlay_progress_description));
        overlayView.addView(progressBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonScroller = new HorizontalScrollView(this);
        buttonScroller.setHorizontalScrollBarEnabled(false);
        buttonScroller.setFillViewport(true);
        buttonScroller.setVisibility(View.GONE);
        buttonScroller.addView(buttonRow, new HorizontalScrollView.LayoutParams(
            HorizontalScrollView.LayoutParams.WRAP_CONTENT,
            HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        overlayView.addView(buttonScroller, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

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
            enqueueEvent("tap", "overlay", windowParams.x, windowParams.y);
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
                        if (!dragging) {
                            view.performClick();
                        } else {
                            enqueueEvent("move", "overlay", windowParams.x, windowParams.y);
                            publishSnapshot();
                        }
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
        if (!visible && "video".equals(contentType) && mediaPrepared &&
                mediaPlayer != null && mediaPlayer.isPlaying()) {
            controlPlayback(false);
        }
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

    private void updateProgress(int value) {
        progress = value;
        if (value < 0) {
            progressBar.setVisibility(View.GONE);
        } else {
            progressBar.setProgress(value);
            progressBar.setVisibility(View.VISIBLE);
        }
    }

    private void updateButtons(String buttonsJson, boolean growForButtons) {
        buttonRow.removeAllViews();
        buttonCount = 0;
        if (buttonsJson == null) {
            buttonScroller.setVisibility(View.GONE);
            return;
        }
        try {
            JSONArray buttons = new JSONArray(buttonsJson);
            for (int i = 0; i < buttons.length(); i++) {
                JSONObject definition = buttons.getJSONObject(i);
                String id = definition.getString("id").trim();
                String label = definition.getString("label").trim();
                Button button = new Button(this);
                button.setAllCaps(false);
                button.setText(label);
                button.setContentDescription(label);
                button.setOnClickListener(view -> {
                    enqueueEvent("button", id, windowParams.x, windowParams.y);
                    statusView.setText(getString(R.string.overlay_button_status, label));
                    publishSnapshot();
                });
                buttonRow.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
                buttonCount++;
            }
            buttonScroller.setVisibility(buttonCount == 0 ? View.GONE : View.VISIBLE);
            if (buttonCount > 0 && growForButtons && windowParams.height < dpToPixels(MIN_BUTTON_HEIGHT_DP)) {
                windowParams.height = dpToPixels(MIN_BUTTON_HEIGHT_DP);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid button definitions", e);
        }
    }

    private void showContent(Intent intent) {
        String type = intent.getStringExtra("type");
        String source = intent.getStringExtra("source");
        if (type == null || source == null) {
            throw new IllegalArgumentException("Content type and source are required");
        }

        applyUpdates(intent);
        releaseContentResources();
        durationMs = 0;
        positionMs = 0;
        pendingSeekMs = -1;
        javascriptEnabled = false;
        contentType = type;
        contentSource = source;
        playbackState = "loading";
        autoplay = intent.getBooleanExtra("autoplay", true);
        setWindowFocusable(intent.getBooleanExtra("focusable", false));
        if (!intent.hasExtra("height") && windowParams.height < dpToPixels(MIN_CONTENT_HEIGHT_DP)) {
            windowParams.height = dpToPixels(MIN_CONTENT_HEIGHT_DP);
        }
        contentContainer.setVisibility(View.VISIBLE);

        switch (type) {
            case "image":
                showImage(source);
                break;
            case "web":
                showWeb(source, intent.getBooleanExtra("javascript", false));
                break;
            case "video":
                showVideo(source);
                break;
            default:
                resetContentState();
                throw new IllegalArgumentException("Unsupported content type: " + type);
        }
        clampPosition();
        windowManager.updateViewLayout(overlayView, windowParams);
        publishSnapshot();
    }

    private void clearContent() {
        releaseContentResources();
        resetContentState();
        setWindowFocusable(false);
        publishSnapshot();
    }

    private void resetContentState() {
        contentType = "none";
        contentSource = "";
        playbackState = "none";
        durationMs = 0;
        positionMs = 0;
        pendingSeekMs = -1;
        mediaPrepared = false;
        javascriptEnabled = false;
        if (contentContainer != null) contentContainer.setVisibility(View.GONE);
    }

    private void resetFailedContent() {
        releaseContentResources();
        resetContentState();
        focusable = false;
        if (windowParams != null) {
            windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        publishSnapshot();
    }

    private void showImage(String source) {
        final int generation = contentGeneration;
        imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setContentDescription(getString(R.string.overlay_image_description));
        contentContainer.addView(imageView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        Thread decoder = new Thread(() -> {
            Bitmap bitmap = decodeSampledBitmap(source);
            OverlayLayout target = overlayView;
            if (target == null) {
                if (bitmap != null) bitmap.recycle();
                return;
            }
            mainHandler.post(() -> {
                if (generation != contentGeneration || imageView == null) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    playbackState = "error";
                    statusView.setText(R.string.overlay_image_error);
                    enqueueEvent("image_error", "decode", windowParams.x, windowParams.y);
                } else {
                    displayedBitmap = bitmap;
                    imageView.setImageBitmap(bitmap);
                    playbackState = "ready";
                    statusView.setText(R.string.overlay_image_ready);
                    enqueueEvent("image_ready", "image", windowParams.x, windowParams.y);
                }
                publishSnapshot();
            });
        }, "TermuxApiOverlayImage");
        decoder.start();
    }

    private Bitmap decodeSampledBitmap(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;

        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > MAX_IMAGE_DIMENSION ||
                bounds.outHeight / sampleSize > MAX_IMAGE_DIMENSION) {
            sampleSize *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(path, options);
        } catch (OutOfMemoryError e) {
            Logger.logError(LOG_TAG, "Not enough memory to decode overlay image");
            return null;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showWeb(String source, boolean javascriptEnabled) {
        this.javascriptEnabled = javascriptEnabled;
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(javascriptEnabled);
        settings.setDomStorageEnabled(javascriptEnabled);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);

        final int generation = contentGeneration;
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isHttps(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isHttps(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (generation != contentGeneration || view != webView) return;
                playbackState = "ready";
                statusView.setText(R.string.overlay_web_ready);
                enqueueEvent("web_loaded", "page", windowParams.x, windowParams.y);
                publishSnapshot();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                    WebResourceError error) {
                if (!request.isForMainFrame() || generation != contentGeneration ||
                        view != webView) return;
                playbackState = "error";
                statusView.setText(R.string.overlay_web_error);
                enqueueEvent("web_error", String.valueOf(error.getErrorCode()),
                    windowParams.x, windowParams.y);
                publishSnapshot();
            }
        });
        contentContainer.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        webView.loadUrl(source);
    }

    private boolean isHttps(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
    }

    private void showVideo(String source) {
        videoView = new TextureView(this);
        videoView.setContentDescription(getString(R.string.overlay_video_description));
        final int generation = contentGeneration;
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                if (generation == contentGeneration) prepareVideo(source, texture, generation);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                if (generation == contentGeneration) releaseMediaPlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
        contentContainer.addView(videoView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void prepareVideo(String source, SurfaceTexture texture, int generation) {
        releaseMediaPlayer();
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        videoSurface = new Surface(texture);
        player.setSurface(videoSurface);
        player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
        float volume = volumePercent / 100f;
        player.setVolume(volume, volume);
        player.setOnPreparedListener(prepared -> {
            if (generation != contentGeneration || prepared != mediaPlayer) return;
            mediaPrepared = true;
            durationMs = Math.max(0, prepared.getDuration());
            if (pendingSeekMs >= 0) prepared.seekTo(pendingSeekMs);
            boolean startNow = autoplay && requestPlaybackAudioFocus();
            playbackState = startNow ? "playing" : "ready";
            if (startNow) {
                prepared.start();
                startPlaybackPositionUpdates();
            } else if (autoplay) {
                autoplay = false;
                enqueueEvent("video_paused", "audio_focus", windowParams.x, windowParams.y);
            }
            statusView.setText(R.string.overlay_video_ready);
            enqueueEvent("video_ready", "video", windowParams.x, windowParams.y);
            publishSnapshot();
        });
        player.setOnCompletionListener(completed -> {
            if (generation != contentGeneration || completed != mediaPlayer) return;
            playbackState = "completed";
            positionMs = durationMs;
            stopPlaybackPositionUpdates();
            abandonPlaybackAudioFocus();
            enqueueEvent("video_complete", "video", windowParams.x, windowParams.y);
            publishSnapshot();
        });
        player.setOnErrorListener((failed, what, extra) -> {
            if (generation != contentGeneration || failed != mediaPlayer) return true;
            playbackState = "error";
            statusView.setText(R.string.overlay_video_error);
            enqueueEvent("video_error", what + ":" + extra, windowParams.x, windowParams.y);
            publishSnapshot();
            return true;
        });
        try {
            player.setDataSource(source);
            player.prepareAsync();
            playbackState = "preparing";
        } catch (IOException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to load overlay video", e);
            releaseMediaPlayer();
            playbackState = "error";
            statusView.setText(R.string.overlay_video_error);
            enqueueEvent("video_error", "source", windowParams.x, windowParams.y);
            publishSnapshot();
        }
    }

    private void controlPlayback(boolean play) {
        if (!"video".equals(contentType) || mediaPlayer == null) {
            throw new IllegalStateException("No video content is loaded");
        }
        autoplay = play;
        if (!mediaPrepared) {
            playbackState = "preparing";
        } else if (play) {
            if (!requestPlaybackAudioFocus()) {
                autoplay = false;
                playbackState = "paused";
                enqueueEvent("video_paused", "audio_focus", windowParams.x, windowParams.y);
                publishSnapshot();
                return;
            }
            mediaPlayer.start();
            playbackState = "playing";
            startPlaybackPositionUpdates();
        } else {
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            positionMs = Math.max(0, mediaPlayer.getCurrentPosition());
            playbackState = "paused";
            stopPlaybackPositionUpdates();
            abandonPlaybackAudioFocus();
        }
        publishSnapshot();
    }

    private void seekVideo(int requestedPositionMs) {
        if (!"video".equals(contentType) || mediaPlayer == null) {
            throw new IllegalStateException("No video content is loaded");
        }
        pendingSeekMs = requestedPositionMs;
        positionMs = requestedPositionMs;
        if (mediaPrepared) mediaPlayer.seekTo(requestedPositionMs);
        publishSnapshot();
    }

    private void setVideoVolume(int requestedVolume) {
        if (!"video".equals(contentType)) {
            throw new IllegalStateException("No video content is loaded");
        }
        volumePercent = requestedVolume;
        if (mediaPlayer != null) {
            float volume = requestedVolume / 100f;
            mediaPlayer.setVolume(volume, volume);
        }
        publishSnapshot();
    }

    private void setWindowFocusable(boolean enabled) {
        focusable = enabled;
        if (windowParams == null) return;
        if (enabled) {
            windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (overlayAttached) windowManager.updateViewLayout(overlayView, windowParams);
    }

    private void releaseContentResources() {
        contentGeneration++;
        releaseMediaPlayer();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.setWebViewClient(null);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        if (imageView != null) {
            imageView.setImageDrawable(null);
            imageView = null;
        }
        if (displayedBitmap != null) {
            displayedBitmap.recycle();
            displayedBitmap = null;
        }
        videoView = null;
        if (contentContainer != null) contentContainer.removeAllViews();
    }

    private void releaseMediaPlayer() {
        stopPlaybackPositionUpdates();
        abandonPlaybackAudioFocus();
        mediaPrepared = false;
        if (mediaPlayer != null) {
            MediaPlayer player = mediaPlayer;
            mediaPlayer = null;
            try {
                player.setOnPreparedListener(null);
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                player.release();
            } catch (RuntimeException e) {
                Logger.logWarn(LOG_TAG, "Unable to release overlay video cleanly");
            }
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
    }

    private void startPlaybackPositionUpdates() {
        mainHandler.removeCallbacks(playbackPositionUpdater);
        mainHandler.postDelayed(playbackPositionUpdater, 1_000);
    }

    private void stopPlaybackPositionUpdates() {
        mainHandler.removeCallbacks(playbackPositionUpdater);
    }

    @SuppressWarnings("deprecation")
    private boolean requestPlaybackAudioFocus() {
        if (audioManager == null || volumePercent == 0) return true;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build();
            }
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(audioFocusChangeListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private void abandonPlaybackAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
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
        synchronized (EVENT_LOCK) {
            snapshot = new Snapshot(
                true,
                overlayView.getVisibility() == View.VISIBLE,
                overlayText == null ? "" : overlayText,
                windowParams.x,
                windowParams.y,
                windowParams.width,
                windowParams.height,
                tapCount,
                progress,
                buttonCount,
                eventQueue.size(),
                contentType,
                contentSource,
                playbackState,
                positionMs,
                durationMs,
                volumePercent,
                javascriptEnabled,
                focusable);
        }
    }

    private static void enqueueEvent(String type, String id, int x, int y) {
        synchronized (EVENT_LOCK) {
            if (eventQueue.size() == MAX_EVENTS) eventQueue.removeFirst();
            eventQueue.addLast(new OverlayEvent(type, id, System.currentTimeMillis(), x, y));
        }
    }

    private static int getEventCount() {
        synchronized (EVENT_LOCK) {
            return eventQueue.size();
        }
    }

    private static void updateSnapshotEventCount() {
        Snapshot current = snapshot;
        snapshot = new Snapshot(
            current.running,
            current.visible,
            current.text,
            current.x,
            current.y,
            current.width,
            current.height,
            current.tapCount,
            current.progress,
            current.buttonCount,
            eventQueue.size(),
            current.contentType,
            current.contentSource,
            current.playbackState,
            current.positionMs,
            current.durationMs,
            current.volume,
            current.javascriptEnabled,
            current.focusable);
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
        releaseContentResources();
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
        progressBar = null;
        buttonScroller = null;
        buttonRow = null;
        contentContainer = null;
        windowParams = null;
        overlayText = null;
        tapCount = 0;
        progress = -1;
        buttonCount = 0;
        resetContentState();
        focusable = false;
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
        public final int progress;
        public final int buttonCount;
        public final int eventCount;
        public final String contentType;
        public final String contentSource;
        public final String playbackState;
        public final int positionMs;
        public final int durationMs;
        public final int volume;
        public final boolean javascriptEnabled;
        public final boolean focusable;

        Snapshot(boolean running, boolean visible, String text, int x, int y,
                int width, int height, int tapCount, int progress, int buttonCount,
                int eventCount, String contentType, String contentSource,
                String playbackState, int positionMs, int durationMs, int volume,
                boolean javascriptEnabled, boolean focusable) {
            this.running = running;
            this.visible = visible;
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.tapCount = tapCount;
            this.progress = progress;
            this.buttonCount = buttonCount;
            this.eventCount = eventCount;
            this.contentType = contentType;
            this.contentSource = contentSource;
            this.playbackState = playbackState;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.volume = volume;
            this.javascriptEnabled = javascriptEnabled;
            this.focusable = focusable;
        }

        static Snapshot stopped() {
            return new Snapshot(false, false, "", 0, 0, 0, 0, 0, -1, 0,
                getEventCount(), "none", "", "none", 0, 0, 100, false, false);
        }
    }

    public static final class OverlayEvent {
        public final String type;
        public final String id;
        public final long timestamp;
        public final int x;
        public final int y;

        OverlayEvent(String type, String id, long timestamp, int x, int y) {
            this.type = type;
            this.id = id;
            this.timestamp = timestamp;
            this.x = x;
            this.y = y;
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
