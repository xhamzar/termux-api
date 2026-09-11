package com.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.JsonWriter;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Handles Termux commands for the floating overlay service. */
public class OverlayAPI {

    private static final String LOG_TAG = "OverlayAPI";
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_TEXT_LENGTH = 4_096;
    private static final int MAX_DIMENSION = 10_000;

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
            case "permission":
                openOverlayPermissionSettings(context);
                writeResult(out, true, null, OverlayService.getSnapshot(), context);
                return;
            case OverlayService.ACTION_START:
            case OverlayService.ACTION_SHOW:
                requireOverlayPermission(context);
                validateOptionalParameters(source);
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

    private static void requireRunningService() throws OverlayApiException {
        if (!OverlayService.isRunning()) {
            throw new OverlayApiException("Overlay service is not running; use action 'start' first");
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
        out.name("running").value(snapshot.running);
        out.name("visible").value(snapshot.visible);
        out.name("text").value(snapshot.text);
        out.name("x").value(snapshot.x);
        out.name("y").value(snapshot.y);
        out.name("width").value(snapshot.width);
        out.name("height").value(snapshot.height);
        out.name("tap_count").value(snapshot.tapCount);
    }

    private static class OverlayApiException extends Exception {
        OverlayApiException(String message) {
            super(message);
        }
    }
}
