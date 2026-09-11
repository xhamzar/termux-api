package com.termux.api.apis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.JsonWriter;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Handles Termux commands for the floating overlay service. */
public class OverlayAPI {

    private static final String LOG_TAG = "OverlayAPI";
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_TEXT_LENGTH = 4_096;
    private static final int MAX_DIMENSION = 10_000;
    private static final int MAX_BUTTONS = 8;
    private static final int MAX_BUTTON_ID_LENGTH = 64;
    private static final int MAX_BUTTON_LABEL_LENGTH = 48;
    private static final int MAX_SOURCE_LENGTH = 4_096;
    private static final int MAX_DRAW_TEXT_LENGTH = 256;
    private static final long MAX_IMAGE_FILE_BYTES = 25L * 1024 * 1024;
    private static final String[] COLOR_PARAMETERS = {
        "background_color", "text_color", "status_color", "border_color", "button_color",
        "button_text_color"
    };

    public static void onReceive(TermuxApiReceiver receiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        ResultReturner.returnData(receiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                try {
                    execute(context.getApplicationContext(), intent, out);
                } catch (OverlayApiException e) {
                    out.name("success").value(false);
                    out.name("error").value(e.getMessage());
                    out.name("permission_granted").value(Settings.canDrawOverlays(context));
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Overlay command failed", e);
                    out.name("success").value(false);
                    out.name("error").value("Failed to execute overlay command");
                }
                out.endObject();
            }
        });
    }

    private static void execute(Context context, Intent source, JsonWriter out) throws Exception {
        String requestedAction = source.getStringExtra("action");
        String action = requestedAction == null || requestedAction.trim().isEmpty()
            ? OverlayService.ACTION_STATUS
            : requestedAction.trim().toLowerCase(Locale.ROOT);

        switch (action) {
            case OverlayService.ACTION_STATUS:
                writeResult(out, true, null, OverlayService.getSnapshot(), context);
                return;
            case OverlayService.ACTION_CAMERA_STATUS:
                writeResult(out, true, null, OverlayService.getSnapshot(), context);
                return;
            case OverlayService.ACTION_EVENTS:
                boolean clear = getOptionalBooleanExtra(source, "clear", true);
                List<OverlayService.OverlayEvent> events = OverlayService.getEvents(clear);
                writeResult(out, true, null, OverlayService.getSnapshot(), context);
                writeEvents(out, events);
                return;
            case OverlayService.ACTION_CLEAR_EVENTS:
            case "clear-events":
                OverlayService.clearEvents();
                writeResult(out, true, null, OverlayService.getSnapshot(), context);
                return;
            case "permission":
                openOverlayPermissionSettings(context);
                writeResult(out, true, null, OverlayService.getSnapshot(), context);
                return;
            case OverlayService.ACTION_START:
            case OverlayService.ACTION_SHOW:
                requireOverlayPermission(context);
                validateOptionalParameters(source);
                break;
            case OverlayService.ACTION_UPDATE:
                requireRunningService();
                validateOptionalParameters(source);
                break;
            case OverlayService.ACTION_STYLE:
                requireRunningService();
                validateOptionalStyle(source);
                break;
            case OverlayService.ACTION_RESET_STYLE:
            case "reset-style":
                requireRunningService();
                break;
            case OverlayService.ACTION_CONTENT:
                requireRunningService();
                validateContent(source);
                break;
            case OverlayService.ACTION_CLEAR_CONTENT:
            case "clear-content":
            case OverlayService.ACTION_PLAY:
            case OverlayService.ACTION_PAUSE:
                requireRunningService();
                break;
            case OverlayService.ACTION_SEEK:
                requireRunningService();
                requireNonNegativeInteger(source, "position_ms");
                break;
            case OverlayService.ACTION_VOLUME:
                requireRunningService();
                int volume = getIntExtra(source, "volume");
                if (volume < 0 || volume > 100) {
                    throw new OverlayApiException("'volume' must be between 0 and 100");
                }
                break;
            case OverlayService.ACTION_CAMERA_START:
                requireOverlayPermission(context);
                validateOptionalParameters(source);
                validateSocketName(source);
                break;
            case OverlayService.ACTION_CAMERA_STOP:
                if (!OverlayService.getCameraSnapshot().running) {
                    writeResult(out, true, null, OverlayService.getSnapshot(), context);
                    return;
                }
                break;
            case OverlayService.ACTION_CAMERA_RESIZE:
                requireCameraOverlay();
                requireDimension(source, "width");
                requireDimension(source, "height");
                break;
            case OverlayService.ACTION_CAMERA_POSITION:
                requireCameraOverlay();
                requireCoordinate(source, "x");
                requireCoordinate(source, "y");
                break;
            case OverlayService.ACTION_DRAW_TEXT:
                requireCameraOverlay();
                validateDrawText(source);
                break;
            case OverlayService.ACTION_DRAW_BOX:
                requireCameraOverlay();
                validateDrawBox(source);
                break;
            case OverlayService.ACTION_DRAW_CLEAR:
                requireCameraOverlay();
                break;
            case OverlayService.ACTION_MOVE:
                requireRunningService();
                requireCoordinate(source, "x");
                requireCoordinate(source, "y");
                break;
            case OverlayService.ACTION_RESIZE:
                requireRunningService();
                requireDimension(source, "width");
                requireDimension(source, "height");
                break;
            case OverlayService.ACTION_HIDE:
                requireRunningService();
                break;
            case OverlayService.ACTION_STOP:
                if (!OverlayService.isRunning()) {
                    writeResult(out, true, null, OverlayService.getSnapshot(), context);
                    return;
                }
                break;
            default:
                throw new OverlayApiException("Unknown overlay action: " + action);
        }

        Bundle result = dispatchCommand(context, source, action);
        String error = result.getString(OverlayService.RESULT_ERROR);
        writeResult(out, error == null, error, OverlayService.getSnapshot(), context);
    }

    private static Bundle dispatchCommand(Context context, Intent source, String action)
            throws OverlayApiException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
        ResultReceiver resultReceiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                result.set(resultData == null ? Bundle.EMPTY : resultData);
                completed.countDown();
            }
        };

        Intent serviceIntent = new Intent(context, OverlayService.class)
            .setAction(action)
            .putExtra(OverlayService.EXTRA_RESULT_RECEIVER, resultReceiver);
        copyExtra(source, serviceIntent, "text");
        copyExtra(source, serviceIntent, "x");
        copyExtra(source, serviceIntent, "y");
        copyExtra(source, serviceIntent, "width");
        copyExtra(source, serviceIntent, "height");
        copyExtra(source, serviceIntent, "progress");
        copyExtra(source, serviceIntent, "buttons");
        copyExtra(source, serviceIntent, "type");
        copyExtra(source, serviceIntent, "source");
        copyExtra(source, serviceIntent, "javascript");
        copyExtra(source, serviceIntent, "autoplay");
        copyExtra(source, serviceIntent, "focusable");
        copyExtra(source, serviceIntent, "position_ms");
        copyExtra(source, serviceIntent, "volume");
        copyExtra(source, serviceIntent, "background_color");
        copyExtra(source, serviceIntent, "background_opacity");
        copyExtra(source, serviceIntent, "text_color");
        copyExtra(source, serviceIntent, "status_color");
        copyExtra(source, serviceIntent, "border_color");
        copyExtra(source, serviceIntent, "button_color");
        copyExtra(source, serviceIntent, "button_text_color");
        copyExtra(source, serviceIntent, "opacity");
        copyExtra(source, serviceIntent, "text_size");
        copyExtra(source, serviceIntent, "corner_radius");
        copyExtra(source, serviceIntent, "border_width");
        copyExtra(source, serviceIntent, "padding");
        copyExtra(source, serviceIntent, "elevation");
        copyExtra(source, serviceIntent, "text_align");
        copyExtra(source, serviceIntent, "draggable");
        copyExtra(source, serviceIntent, "touchable");
        copyExtra(source, serviceIntent, "show_status");
        copyExtra(source, serviceIntent, "socket_name");
        copyExtra(source, serviceIntent, "label");
        copyExtra(source, serviceIntent, "confidence");
        copyExtra(source, serviceIntent, "color");
        copyExtra(source, serviceIntent, "stroke_width");

        try {
            if (!OverlayService.isRunning() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start overlay service", e);
            throw new OverlayApiException("Android did not allow the overlay service to start");
        }

        try {
            if (!completed.await(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new OverlayApiException("Timed out waiting for the overlay service");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OverlayApiException("Overlay command was interrupted");
        }
        return result.get() == null ? Bundle.EMPTY : result.get();
    }

    private static void copyExtra(Intent source, Intent target, String name) {
        Bundle extras = source.getExtras();
        if (extras == null || !extras.containsKey(name)) return;
        Object value = extras.get(name);
        if (value instanceof String) target.putExtra(name, (String) value);
        else if (value instanceof Integer) target.putExtra(name, (Integer) value);
        else if (value instanceof Boolean) target.putExtra(name, (Boolean) value);
    }

    private static void validateOptionalParameters(Intent intent) throws OverlayApiException {
        String text = intent.getStringExtra("text");
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            throw new OverlayApiException("'text' must not exceed 4096 characters");
        }
        validateOptionalCoordinate(intent, "x");
        validateOptionalCoordinate(intent, "y");
        validateOptionalDimension(intent, "width");
        validateOptionalDimension(intent, "height");
        validateOptionalProgress(intent);
        validateOptionalButtons(intent);
        validateOptionalBoolean(intent, "focusable");
        validateOptionalStyle(intent);
    }

    private static void validateOptionalStyle(Intent intent) throws OverlayApiException {
        for (String name : COLOR_PARAMETERS) validateOptionalColor(intent, name);
        validateOptionalRange(intent, "background_opacity", 0, 100);
        validateOptionalRange(intent, "opacity", 1, 100);
        validateOptionalRange(intent, "text_size", 8, 72);
        validateOptionalRange(intent, "corner_radius", 0, 100);
        validateOptionalRange(intent, "border_width", 0, 16);
        validateOptionalRange(intent, "padding", 0, 64);
        validateOptionalRange(intent, "elevation", 0, 32);
        validateOptionalBoolean(intent, "draggable");
        validateOptionalBoolean(intent, "touchable");
        validateOptionalBoolean(intent, "show_status");
        validateOptionalBoolean(intent, "focusable");

        if (intent.hasExtra("text_align")) {
            String rawAlignment = intent.getStringExtra("text_align");
            String alignment = rawAlignment == null ? "" :
                rawAlignment.trim().toLowerCase(Locale.ROOT);
            if (!(alignment.equals("start") ||
                    alignment.equals("center") || alignment.equals("end"))) {
                throw new OverlayApiException("'text_align' must be start, center, or end");
            }
            intent.putExtra("text_align", alignment);
        }
        if (intent.hasExtra("touchable") && intent.hasExtra("focusable") &&
                !getOptionalBooleanExtra(intent, "touchable", true) &&
                getOptionalBooleanExtra(intent, "focusable", false)) {
            throw new OverlayApiException("'focusable' cannot be true when 'touchable' is false");
        }
    }

    private static void validateSocketName(Intent intent) throws OverlayApiException {
        String name = intent.hasExtra("socket_name")
            ? requireStringExtra(intent, "socket_name").trim() : "termux.camera.frames";
        if (name.length() > 90 || name.contains("/")) {
            throw new OverlayApiException(
                "'socket_name' must be an abstract Unix socket name without '/'");
        }
        intent.putExtra("socket_name", name);
    }

    private static void validateDrawText(Intent intent) throws OverlayApiException {
        String text = requireStringExtra(intent, "text");
        if (text.length() > MAX_DRAW_TEXT_LENGTH) {
            throw new OverlayApiException("Draw text must not exceed 256 characters");
        }
        requireCoordinate(intent, "x");
        requireCoordinate(intent, "y");
        normalizeDrawColor(intent);
        if (!intent.hasExtra("text_size")) intent.putExtra("text_size", 18);
        validateOptionalRange(intent, "text_size", 8, 72);
    }

    private static void validateDrawBox(Intent intent) throws OverlayApiException {
        requireCoordinate(intent, "x");
        requireCoordinate(intent, "y");
        requireDimension(intent, "width");
        requireDimension(intent, "height");
        String label = intent.hasExtra("label")
            ? requireStringExtra(intent, "label") : "Object";
        if (label.length() > MAX_DRAW_TEXT_LENGTH) {
            throw new OverlayApiException("Box label must not exceed 256 characters");
        }
        intent.putExtra("label", label);
        if (intent.hasExtra("confidence")) {
            int confidence = getIntExtra(intent, "confidence");
            if (confidence < 0 || confidence > 100) {
                throw new OverlayApiException("'confidence' must be between 0 and 100");
            }
        } else {
            intent.putExtra("confidence", -1);
        }
        normalizeDrawColor(intent);
        if (!intent.hasExtra("stroke_width")) intent.putExtra("stroke_width", 2);
        validateOptionalRange(intent, "stroke_width", 1, 16);
    }

    private static void normalizeDrawColor(Intent intent) throws OverlayApiException {
        if (!intent.hasExtra("color")) intent.putExtra("color", "#00FF00");
        validateOptionalColor(intent, "color");
    }

    private static void validateOptionalColor(Intent intent, String name)
            throws OverlayApiException {
        if (!intent.hasExtra(name)) return;
        String value = intent.getStringExtra(name);
        if (value == null || !value.matches("^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")) {
            throw new OverlayApiException(
                "'" + name + "' must use #RRGGBB or #AARRGGBB format");
        }
    }

    private static void validateOptionalRange(Intent intent, String name, int min, int max)
            throws OverlayApiException {
        if (!intent.hasExtra(name)) return;
        int value = getIntExtra(intent, name);
        if (value < min || value > max) {
            throw new OverlayApiException(
                "'" + name + "' must be between " + min + " and " + max);
        }
    }

    private static void validateOptionalCoordinate(Intent intent, String name)
            throws OverlayApiException {
        if (intent.hasExtra(name)) requireCoordinate(intent, name);
    }

    private static void validateOptionalDimension(Intent intent, String name)
            throws OverlayApiException {
        if (intent.hasExtra(name)) requireDimension(intent, name);
    }

    private static int requireCoordinate(Intent intent, String name) throws OverlayApiException {
        int value = getIntExtra(intent, name);
        if (value < 0) throw new OverlayApiException("'" + name + "' must be zero or greater");
        return value;
    }

    private static int requireDimension(Intent intent, String name) throws OverlayApiException {
        int value = getIntExtra(intent, name);
        if (value < 1 || value > MAX_DIMENSION) {
            throw new OverlayApiException("'" + name + "' must be between 1 and 10000 pixels");
        }
        return value;
    }

    private static int getIntExtra(Intent intent, String name) throws OverlayApiException {
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey(name) || !(extras.get(name) instanceof Integer)) {
            throw new OverlayApiException("Missing or invalid integer '" + name + "' parameter");
        }
        return extras.getInt(name);
    }

    private static boolean getOptionalBooleanExtra(Intent intent, String name, boolean defaultValue)
            throws OverlayApiException {
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey(name)) return defaultValue;
        if (!(extras.get(name) instanceof Boolean)) {
            throw new OverlayApiException("'" + name + "' must be a boolean");
        }
        return extras.getBoolean(name);
    }

    private static void validateOptionalBoolean(Intent intent, String name)
            throws OverlayApiException {
        getOptionalBooleanExtra(intent, name, false);
    }

    private static void validateContent(Intent intent) throws OverlayApiException {
        validateOptionalParameters(intent);
        String type = requireStringExtra(intent, "type").trim().toLowerCase(Locale.ROOT);
        String source = requireStringExtra(intent, "source").trim();
        if (source.length() > MAX_SOURCE_LENGTH) {
            throw new OverlayApiException("'source' must not exceed 4096 characters");
        }
        validateOptionalBoolean(intent, "javascript");
        validateOptionalBoolean(intent, "autoplay");
        validateOptionalBoolean(intent, "focusable");

        switch (type) {
            case "image":
                intent.putExtra("source", validateLocalFile(source, true));
                break;
            case "web":
                requireHttpsUrl(source, "Web content");
                break;
            case "video":
                if (isHttpsUrl(source)) {
                    requireHttpsUrl(source, "Remote video");
                } else {
                    intent.putExtra("source", validateLocalFile(source, false));
                }
                break;
            default:
                throw new OverlayApiException("'type' must be image, web, or video");
        }
        intent.putExtra("type", type);
    }

    private static String requireStringExtra(Intent intent, String name)
            throws OverlayApiException {
        Bundle extras = intent.getExtras();
        if (extras == null || !(extras.get(name) instanceof String)) {
            throw new OverlayApiException("Missing or invalid string '" + name + "' parameter");
        }
        String value = extras.getString(name, "");
        if (value.trim().isEmpty()) {
            throw new OverlayApiException("'" + name + "' must not be empty");
        }
        return value;
    }

    private static String validateLocalFile(String path, boolean enforceImageLimit)
            throws OverlayApiException {
        if (!new File(path).isAbsolute()) {
            throw new OverlayApiException("Local media source must use an absolute path");
        }
        File file;
        try {
            file = new File(path).getCanonicalFile();
        } catch (IOException e) {
            throw new OverlayApiException("Invalid local file path");
        }
        if (!file.isAbsolute() || !file.isFile() || !file.canRead()) {
            throw new OverlayApiException("Local media source must be an absolute, readable file");
        }
        if (enforceImageLimit && file.length() > MAX_IMAGE_FILE_BYTES) {
            throw new OverlayApiException("Image file must not exceed 25 MiB");
        }
        return file.getAbsolutePath();
    }

    private static boolean isHttpsUrl(String source) {
        return "https".equalsIgnoreCase(Uri.parse(source).getScheme());
    }

    private static void requireHttpsUrl(String source, String label) throws OverlayApiException {
        Uri uri = Uri.parse(source);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                uri.getHost().trim().isEmpty() || uri.getUserInfo() != null) {
            throw new OverlayApiException(label + " source must be a valid HTTPS URL");
        }
    }

    private static int requireNonNegativeInteger(Intent intent, String name)
            throws OverlayApiException {
        int value = getIntExtra(intent, name);
        if (value < 0) throw new OverlayApiException("'" + name + "' must be zero or greater");
        return value;
    }

    private static void validateOptionalProgress(Intent intent) throws OverlayApiException {
        if (!intent.hasExtra("progress")) return;
        int progress = getIntExtra(intent, "progress");
        if (progress < -1 || progress > 100) {
            throw new OverlayApiException("'progress' must be -1 (hidden) or between 0 and 100");
        }
    }

    private static void validateOptionalButtons(Intent intent) throws OverlayApiException {
        if (!intent.hasExtra("buttons")) return;
        String buttons = intent.getStringExtra("buttons");
        if (buttons == null) throw new OverlayApiException("'buttons' must be a JSON string");
        try {
            JSONArray array = new JSONArray(buttons);
            if (array.length() > MAX_BUTTONS) {
                throw new OverlayApiException("'buttons' must contain at most 8 buttons");
            }
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject button = array.getJSONObject(i);
                if (!(button.opt("id") instanceof String) ||
                        !(button.opt("label") instanceof String)) {
                    throw new OverlayApiException(
                        "Button 'id' and 'label' must be strings at index " + i);
                }
                String id = button.optString("id", "").trim();
                String label = button.optString("label", "").trim();
                if (id.isEmpty() || id.length() > MAX_BUTTON_ID_LENGTH) {
                    throw new OverlayApiException(
                        "Button 'id' must contain between 1 and 64 characters at index " + i);
                }
                if (label.isEmpty() || label.length() > MAX_BUTTON_LABEL_LENGTH) {
                    throw new OverlayApiException(
                        "Button 'label' must contain between 1 and 48 characters at index " + i);
                }
                if (!ids.add(id)) throw new OverlayApiException("Duplicate button id: " + id);
            }
        } catch (JSONException e) {
            throw new OverlayApiException(
                "'buttons' must be a JSON array of {\"id\",\"label\"} objects");
        }
    }

    private static void requireRunningService() throws OverlayApiException {
        if (!OverlayService.isRunning()) {
            throw new OverlayApiException("Overlay service is not running; use action 'start' first");
        }
    }

    private static void requireCameraOverlay() throws OverlayApiException {
        if (!OverlayService.getCameraSnapshot().running) {
            throw new OverlayApiException(
                "Camera overlay is not running; use 'camera start' first");
        }
    }

    private static void requireOverlayPermission(Context context) throws OverlayApiException {
        if (!Settings.canDrawOverlays(context)) {
            throw new OverlayApiException(
                "Display over other apps permission is required. Run action 'permission' or enable it in Android settings");
        }
    }

    private static void openOverlayPermissionSettings(Context context) throws OverlayApiException {
        if (Settings.canDrawOverlays(context)) return;
        Intent settingsIntent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.getPackageName()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(settingsIntent);
        } catch (RuntimeException e) {
            throw new OverlayApiException(
                "Unable to open overlay settings; grant Display over other apps permission manually");
        }
    }

    private static void writeResult(JsonWriter out, boolean success, String error,
            OverlayService.Snapshot snapshot, Context context) throws Exception {
        out.name("success").value(success);
        if (error != null) out.name("error").value(error);
        out.name("permission_granted").value(Settings.canDrawOverlays(context));
        out.name("camera_permission_granted").value(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED);
        out.name("accessibility_required").value(false);
        out.name("running").value(snapshot.running);
        out.name("visible").value(snapshot.visible);
        out.name("text").value(snapshot.text);
        out.name("x").value(snapshot.x);
        out.name("y").value(snapshot.y);
        out.name("width").value(snapshot.width);
        out.name("height").value(snapshot.height);
        out.name("tap_count").value(snapshot.tapCount);
        out.name("progress").value(snapshot.progress);
        out.name("button_count").value(snapshot.buttonCount);
        out.name("event_count").value(snapshot.eventCount);
        out.name("content_type").value(snapshot.contentType);
        out.name("content_source").value(snapshot.contentSource);
        out.name("playback_state").value(snapshot.playbackState);
        out.name("position_ms").value(snapshot.positionMs);
        out.name("duration_ms").value(snapshot.durationMs);
        out.name("volume").value(snapshot.volume);
        out.name("javascript_enabled").value(snapshot.javascriptEnabled);
        out.name("focusable").value(snapshot.focusable);
        writeCameraStatus(out, OverlayService.getCameraSnapshot());
        writeStyle(out, snapshot.style);
    }

    private static void writeCameraStatus(JsonWriter out,
            OverlayService.CameraSnapshot camera) throws Exception {
        out.name("camera").beginObject();
        out.name("running").value(camera.running);
        out.name("connected").value(camera.connected);
        out.name("state").value(camera.state);
        out.name("socket_name").value(camera.socketName);
        out.name("width").value(camera.frameWidth);
        out.name("height").value(camera.frameHeight);
        out.name("format").value(camera.format);
        out.name("sequence").value(camera.sequence);
        out.name("timestamp_nanos").value(camera.timestampNanos);
        out.name("received_frames").value(camera.receivedFrames);
        out.name("decoded_frames").value(camera.decodedFrames);
        out.name("dropped_frames").value(camera.droppedFrames);
        out.name("draw_marks").value(camera.drawMarks);
        if (!camera.error.isEmpty()) out.name("error").value(camera.error);
        out.endObject();
    }

    private static void writeStyle(JsonWriter out, OverlayService.StyleSnapshot style)
            throws Exception {
        out.name("style").beginObject();
        out.name("background_color").value(colorToHex(style.backgroundColor));
        out.name("background_opacity").value(style.backgroundOpacity);
        out.name("text_color").value(colorToHex(style.textColor));
        out.name("status_color").value(colorToHex(style.statusColor));
        out.name("border_color").value(colorToHex(style.borderColor));
        out.name("button_color").value(colorToHex(style.buttonColor));
        out.name("button_text_color").value(colorToHex(style.buttonTextColor));
        out.name("opacity").value(style.opacity);
        out.name("text_size").value(style.textSize);
        out.name("corner_radius").value(style.cornerRadius);
        out.name("border_width").value(style.borderWidth);
        out.name("padding").value(style.padding);
        out.name("elevation").value(style.elevation);
        out.name("text_align").value(style.textAlign);
        out.name("draggable").value(style.draggable);
        out.name("touchable").value(style.touchable);
        out.name("show_status").value(style.showStatus);
        out.endObject();
    }

    private static String colorToHex(int color) {
        return String.format(Locale.ROOT, "#%08X", color);
    }

    private static void writeEvents(JsonWriter out, List<OverlayService.OverlayEvent> events)
            throws Exception {
        out.name("events").beginArray();
        for (OverlayService.OverlayEvent event : events) {
            out.beginObject();
            out.name("type").value(event.type);
            out.name("id").value(event.id);
            out.name("timestamp").value(event.timestamp);
            out.name("x").value(event.x);
            out.name("y").value(event.y);
            out.endObject();
        }
        out.endArray();
    }

    private static class OverlayApiException extends Exception {
        OverlayApiException(String message) {
            super(message);
        }
    }
}
