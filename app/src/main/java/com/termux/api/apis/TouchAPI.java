package com.termux.api.apis;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonWriter;
import android.view.accessibility.AccessibilityEvent;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Simulates touch gestures through an accessibility service enabled by the user. */
public class TouchAPI {

    private static final String LOG_TAG = "TouchAPI";
    private static final long DEFAULT_SWIPE_DURATION = 500;
    private static final long DEFAULT_LONG_PRESS_DURATION = 1000;
    private static final long MAX_GESTURE_DURATION = 60_000;
    private static final long CALLBACK_TIMEOUT_BUFFER = 5_000;
    private static final int MAX_STROKES = 10;

    private static volatile TouchAccessibilityService touchService;

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        String requestedAction = intent.getStringExtra("action");
        final String action = requestedAction == null || requestedAction.trim().isEmpty()
            ? "tap" : requestedAction.trim().toLowerCase(Locale.ROOT);

        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                try {
                    if ("status".equals(action)) {
                        writeStatus(out);
                    } else {
                        requireTouchService();
                        switch (action) {
                            case "tap":
                                handleTap(intent, out);
                                break;
                            case "multi_tap":
                            case "multi-tap":
                                handleMultiTap(intent, out);
                                break;
                            case "swipe":
                                handleSwipe(intent, out);
                                break;
                            case "long_press":
                            case "long-press":
                                handleLongPress(intent, out);
                                break;
                            case "macro":
                                handleMacro(intent, out);
                                break;
                            default:
                                throw new TouchApiException("Unknown action: " + action);
                        }
                    }
                } catch (TouchApiException e) {
                    out.name("success").value(false);
                    out.name("error").value(e.getMessage());
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Error executing action: " + action, e);
                    out.name("success").value(false);
                    out.name("error").value("Failed to execute touch gesture");
                }
                out.endObject();
            }
        });
    }

    private static void writeStatus(JsonWriter out) throws Exception {
        boolean enabled = touchService != null;
        out.name("success").value(true);
        out.name("action").value("status");
        out.name("enabled").value(enabled);
        if (!enabled) {
            out.name("message").value(
                "Enable Termux:API Touch Service in Android Settings > Accessibility");
        }
    }

    private static void handleTap(Intent intent, JsonWriter out) throws Exception {
        int x = requireCoordinate(intent, "x");
        int y = requireCoordinate(intent, "y");
        performGesture(createTap(x, y, 50));

        out.name("success").value(true);
        out.name("action").value("tap");
        out.name("x").value(x);
        out.name("y").value(y);
    }

    private static void handleMultiTap(Intent intent, JsonWriter out) throws Exception {
        String touchesJson = intent.getStringExtra("touches");
        if (touchesJson == null || touchesJson.trim().isEmpty()) {
            throw new TouchApiException("Missing 'touches' parameter");
        }

        List<TouchPoint> touches = parseTouches(touchesJson);
        boolean simultaneous = intent.getBooleanExtra("simultaneous", false);
        if (simultaneous) {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            for (TouchPoint touch : touches) {
                builder.addStroke(createStroke(touch.x, touch.y, touch.x, touch.y, 50));
            }
            performGesture(builder.build());
        } else {
            for (TouchPoint touch : touches) {
                performGesture(createTap(touch.x, touch.y, 50));
            }
        }

        out.name("success").value(true);
        out.name("action").value("multi_tap");
        out.name("count").value(touches.size());
        out.name("simultaneous").value(simultaneous);
    }

    private static void handleSwipe(Intent intent, JsonWriter out) throws Exception {
        int x1 = requireCoordinate(intent, "x1");
        int y1 = requireCoordinate(intent, "y1");
        int x2 = requireCoordinate(intent, "x2");
        int y2 = requireCoordinate(intent, "y2");
        long duration = requireDuration(intent.getLongExtra("duration", DEFAULT_SWIPE_DURATION));

        performGesture(createGesture(x1, y1, x2, y2, duration));
        out.name("success").value(true);
        out.name("action").value("swipe");
        out.name("from_x").value(x1);
        out.name("from_y").value(y1);
        out.name("to_x").value(x2);
        out.name("to_y").value(y2);
        out.name("duration").value(duration);
    }

    private static void handleLongPress(Intent intent, JsonWriter out) throws Exception {
        int x = requireCoordinate(intent, "x");
        int y = requireCoordinate(intent, "y");
        long duration = requireDuration(intent.getLongExtra("duration", DEFAULT_LONG_PRESS_DURATION));

        performGesture(createTap(x, y, duration));
        out.name("success").value(true);
        out.name("action").value("long_press");
        out.name("x").value(x);
        out.name("y").value(y);
        out.name("duration").value(duration);
    }

    private static void handleMacro(Intent intent, JsonWriter out) throws Exception {
        String macroJson = intent.getStringExtra("macro");
        if (macroJson == null || macroJson.trim().isEmpty()) {
            throw new TouchApiException("Missing 'macro' parameter");
        }

        List<MacroAction> actions = parseMacro(macroJson);
        for (MacroAction action : actions) {
            switch (action.action) {
                case "wait":
                    try {
                        Thread.sleep(action.delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new TouchApiException("Touch macro was interrupted");
                    }
                    break;
                case "tap":
                    performGesture(createTap(action.x, action.y, 50));
                    break;
                case "long_press":
                case "long-press":
                    performGesture(createTap(action.x, action.y, action.duration));
                    break;
                case "swipe":
                    performGesture(createGesture(
                        action.x1, action.y1, action.x2, action.y2, action.duration));
                    break;
                default:
                    throw new TouchApiException("Unknown macro action: " + action.action);
            }
        }

        out.name("success").value(true);
        out.name("action").value("macro");
        String name = intent.getStringExtra("name");
        if (name != null && !name.isEmpty()) out.name("name").value(name);
        out.name("steps").value(actions.size());
    }

    private static GestureDescription createTap(int x, int y, long duration) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(createStroke(x, y, x, y, duration));
        return builder.build();
    }

    private static GestureDescription createGesture(int x1, int y1, int x2, int y2, long duration) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(createStroke(x1, y1, x2, y2, duration));
        return builder.build();
    }

    private static GestureDescription.StrokeDescription createStroke(
        int x1, int y1, int x2, int y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        return new GestureDescription.StrokeDescription(path, 0, duration);
    }

    private static void performGesture(GestureDescription gesture) throws TouchApiException {
        TouchAccessibilityService service = requireTouchService();
        if (!service.dispatchGestureAndWait(gesture)) {
            throw new TouchApiException("Android cancelled or rejected the touch gesture");
        }
    }

    private static TouchAccessibilityService requireTouchService() throws TouchApiException {
        TouchAccessibilityService service = touchService;
        if (service == null) {
            throw new TouchApiException(
                "Touch service is disabled. Enable Termux:API Touch Service in Android Settings > Accessibility");
        }
        return service;
    }

    private static int requireCoordinate(Intent intent, String name) throws TouchApiException {
        if (!intent.hasExtra(name)) throw new TouchApiException("Missing '" + name + "' parameter");
        int value = intent.getIntExtra(name, -1);
        if (value < 0) throw new TouchApiException("'" + name + "' must be zero or greater");
        return value;
    }

    private static int requireCoordinate(JSONObject object, String name) throws TouchApiException {
        if (!object.has(name)) throw new TouchApiException("Missing '" + name + "' parameter");
        int value;
        try {
            value = object.getInt(name);
        } catch (JSONException e) {
            throw new TouchApiException("'" + name + "' must be an integer");
        }
        if (value < 0) throw new TouchApiException("'" + name + "' must be zero or greater");
        return value;
    }

    private static long requireDuration(long duration) throws TouchApiException {
        if (duration < 1 || duration > MAX_GESTURE_DURATION) {
            throw new TouchApiException("'duration' must be between 1 and 60000 milliseconds");
        }
        return duration;
    }

    private static List<TouchPoint> parseTouches(String json) throws TouchApiException {
        try {
            JSONArray array = new JSONArray(json);
            if (array.length() == 0 || array.length() > MAX_STROKES) {
                throw new TouchApiException("'touches' must contain between 1 and 10 points");
            }
            List<TouchPoint> touches = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                touches.add(new TouchPoint(
                    requireCoordinate(object, "x"), requireCoordinate(object, "y")));
            }
            return touches;
        } catch (JSONException e) {
            throw new TouchApiException("'touches' must be a JSON array of {\"x\",\"y\"} objects");
        }
    }

    private static List<MacroAction> parseMacro(String json) throws TouchApiException {
        try {
            JSONArray array = new JSONArray(json);
            if (array.length() == 0) throw new TouchApiException("'macro' must not be empty");
            List<MacroAction> actions = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String action = object.optString("action", "").trim().toLowerCase(Locale.ROOT);
                if (action.isEmpty()) throw new TouchApiException("Missing macro 'action' at index " + i);

                MacroAction macroAction = new MacroAction(action);
                switch (action) {
                    case "wait":
                        macroAction.delay = requireNonNegativeDelay(object);
                        break;
                    case "tap":
                        macroAction.x = requireCoordinate(object, "x");
                        macroAction.y = requireCoordinate(object, "y");
                        break;
                    case "long_press":
                    case "long-press":
                        macroAction.x = requireCoordinate(object, "x");
                        macroAction.y = requireCoordinate(object, "y");
                        macroAction.duration = requireDuration(
                            object.optLong("duration", DEFAULT_LONG_PRESS_DURATION));
                        break;
                    case "swipe":
                        macroAction.x1 = requireCoordinate(object, "x1");
                        macroAction.y1 = requireCoordinate(object, "y1");
                        macroAction.x2 = requireCoordinate(object, "x2");
                        macroAction.y2 = requireCoordinate(object, "y2");
                        macroAction.duration = requireDuration(
                            object.optLong("duration", DEFAULT_SWIPE_DURATION));
                        break;
                    default:
                        throw new TouchApiException("Unknown macro action: " + action);
                }
                actions.add(macroAction);
            }
            return actions;
        } catch (JSONException e) {
            throw new TouchApiException("'macro' must be a valid JSON array of action objects");
        }
    }

    private static long requireNonNegativeDelay(JSONObject object) throws TouchApiException {
        if (!object.has("delay")) throw new TouchApiException("Missing 'delay' parameter");
        long delay = object.optLong("delay", -1);
        if (delay < 0 || delay > MAX_GESTURE_DURATION) {
            throw new TouchApiException("'delay' must be between 0 and 60000 milliseconds");
        }
        return delay;
    }

    private static class TouchPoint {
        final int x;
        final int y;

        TouchPoint(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class MacroAction {
        final String action;
        int x;
        int y;
        int x1;
        int y1;
        int x2;
        int y2;
        long delay;
        long duration;

        MacroAction(String action) {
            this.action = action;
        }
    }

    private static class TouchApiException extends Exception {
        TouchApiException(String message) {
            super(message);
        }
    }

    /** Accessibility service that owns the privileged gesture dispatch API. */
    public static class TouchAccessibilityService extends AccessibilityService {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        @Override
        protected void onServiceConnected() {
            super.onServiceConnected();
            touchService = this;
            Logger.logInfo(LOG_TAG, "Touch accessibility service connected");
        }

        @Override
        public boolean onUnbind(Intent intent) {
            clearServiceReference();
            return super.onUnbind(intent);
        }

        @Override
        public void onDestroy() {
            clearServiceReference();
            super.onDestroy();
        }

        private void clearServiceReference() {
            if (touchService == this) touchService = null;
        }

        boolean dispatchGestureAndWait(GestureDescription gesture) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);
            long timeout = getGestureDuration(gesture) + CALLBACK_TIMEOUT_BUFFER;

            mainHandler.post(() -> {
                boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        success.set(true);
                        completed.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        completed.countDown();
                    }
                }, null);
                if (!accepted) completed.countDown();
            });

            try {
                return completed.await(timeout, TimeUnit.MILLISECONDS) && success.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private long getGestureDuration(GestureDescription gesture) {
            long duration = 0;
            for (int i = 0; i < gesture.getStrokeCount(); i++) {
                GestureDescription.StrokeDescription stroke = gesture.getStroke(i);
                duration = Math.max(duration, stroke.getStartTime() + stroke.getDuration());
            }
            return duration;
        }

        @Override
        public void onAccessibilityEvent(AccessibilityEvent event) {
            // Event contents are intentionally ignored; this service only dispatches gestures.
        }

        @Override
        public void onInterrupt() {
            // No event processing to interrupt.
        }
    }
}
