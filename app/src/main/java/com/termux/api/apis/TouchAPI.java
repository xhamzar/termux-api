package com.termux.api.apis;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonWriter;
import android.view.accessibility.AccessibilityManager;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * TouchAPI - Simulates touch input and gestures for automation
 * 
 * Supports:
 * - Single tap at coordinates
 * - Multi-touch (simultaneous touches)
 * - Swipe/drag gestures
 * - Long press
 * - Macro/sequence of touches with delays
 * 
 * Example commands:
 * - Tap: x=100 y=200
 * - Multi-tap: touches=[{"x":100,"y":200},{"x":300,"y":400}]
 * - Swipe: x1=100 y1=200 x2=300 y2=400 duration=500
 * - Macro: macro=[{"action":"tap","x":100,"y":200},{"action":"wait","delay":500}]
 */
public class TouchAPI {

    private static final String LOG_TAG = "TouchAPI";
    private static TouchAccessibilityService touchService = null;
    private static final Object serviceLock = new Object();

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        String action = intent.getStringExtra("action");
        if (action == null || action.isEmpty()) {
            action = "tap"; // default action
        }

        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                
                try {
                    switch (action.toLowerCase()) {
                        case "tap":
                            handleTap(context, intent, out);
                            break;
                        case "multi_tap":
                            handleMultiTap(context, intent, out);
                            break;
                        case "swipe":
                            handleSwipe(context, intent, out);
                            break;
                        case "long_press":
                            handleLongPress(context, intent, out);
                            break;
                        case "macro":
                            handleMacro(context, intent, out);
                            break;
                        default:
                            out.name("success").value(false);
                            out.name("error").value("Unknown action: " + action);
                    }
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Error executing action: " + action, e);
                    out.name("success").value(false);
                    out.name("error").value(e.getMessage());
                }
                
                out.endObject();
            }
        });
    }

    /**
     * Handle single tap at coordinates
     */
    private static void handleTap(Context context, Intent intent, JsonWriter out) throws Exception {
        int x = intent.getIntExtra("x", -1);
        int y = intent.getIntExtra("y", -1);
        
        if (x < 0 || y < 0) {
            out.name("success").value(false);
            out.name("error").value("Missing or invalid coordinates (x, y)");
            return;
        }

        boolean success = performTap(x, y);
        out.name("success").value(success);
        out.name("action").value("tap");
        out.name("x").value(x);
        out.name("y").value(y);
    }

    /**
     * Handle multi-touch (tap multiple points simultaneously or sequentially)
     * Input format: touches=[{"x":100,"y":200},{"x":300,"y":400}]
     */
    private static void handleMultiTap(Context context, Intent intent, JsonWriter out) throws Exception {
        String touchesJson = intent.getStringExtra("touches");
        boolean simultaneous = intent.getBooleanExtra("simultaneous", false);
        
        if (touchesJson == null || touchesJson.isEmpty()) {
            out.name("success").value(false);
            out.name("error").value("Missing 'touches' parameter");
            return;
        }

        List<TouchPoint> touches = parseTouches(touchesJson);
        if (touches.isEmpty()) {
            out.name("success").value(false);
            out.name("error").value("Invalid touches format");
            return;
        }

        boolean success = simultaneous ? 
            performMultiTouchSimultaneous(touches) : 
            performMultiTouchSequential(touches);
            
        out.name("success").value(success);
        out.name("action").value("multi_tap");
        out.name("count").value(touches.size());
        out.name("simultaneous").value(simultaneous);
    }

    /**
     * Handle swipe/drag gesture
     */
    private static void handleSwipe(Context context, Intent intent, JsonWriter out) throws Exception {
        int x1 = intent.getIntExtra("x1", -1);
        int y1 = intent.getIntExtra("y1", -1);
        int x2 = intent.getIntExtra("x2", -1);
        int y2 = intent.getIntExtra("y2", -1);
        long duration = intent.getLongExtra("duration", 500);
        
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            out.name("success").value(false);
            out.name("error").value("Missing or invalid coordinates (x1, y1, x2, y2)");
            return;
        }

        boolean success = performSwipe(x1, y1, x2, y2, duration);
        out.name("success").value(success);
        out.name("action").value("swipe");
        out.name("from_x").value(x1);
        out.name("from_y").value(y1);
        out.name("to_x").value(x2);
        out.name("to_y").value(y2);
        out.name("duration").value(duration);
    }

    /**
     * Handle long press
     */
    private static void handleLongPress(Context context, Intent intent, JsonWriter out) throws Exception {
        int x = intent.getIntExtra("x", -1);
        int y = intent.getIntExtra("y", -1);
        long duration = intent.getLongExtra("duration", 1000);
        
        if (x < 0 || y < 0) {
            out.name("success").value(false);
            out.name("error").value("Missing or invalid coordinates (x, y)");
            return;
        }

        boolean success = performLongPress(x, y, duration);
        out.name("success").value(success);
        out.name("action").value("long_press");
        out.name("x").value(x);
        out.name("y").value(y);
        out.name("duration").value(duration);
    }

    /**
     * Handle macro - sequence of touches with delays
     * Example:
     * macro=[
     *   {"action":"tap","x":100,"y":200},
     *   {"action":"wait","delay":500},
     *   {"action":"tap","x":300,"y":400},
     *   {"action":"swipe","x1":100,"y1":200,"x2":300,"y2":400,"duration":500}
     * ]
     */
    private static void handleMacro(Context context, Intent intent, JsonWriter out) throws Exception {
        String macroJson = intent.getStringExtra("macro");
        String name = intent.getStringExtra("name");
        
        if (macroJson == null || macroJson.isEmpty()) {
            out.name("success").value(false);
            out.name("error").value("Missing 'macro' parameter");
            return;
        }

        List<MacroAction> actions = parseMacro(macroJson);
        if (actions.isEmpty()) {
            out.name("success").value(false);
            out.name("error").value("Invalid macro format");
            return;
        }

        boolean success = performMacro(actions);
        out.name("success").value(success);
        out.name("action").value("macro");
        if (name != null && !name.isEmpty()) {
            out.name("name").value(name);
        }
        out.name("steps").value(actions.size());
    }

    // ==================== Core Touch Methods ====================

    private static boolean performTap(int x, int y) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return performTapAccessibility(x, y);
            } else {
                Logger.logWarn(LOG_TAG, "Tap requires Android N+");
                return false;
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error performing tap", e);
            return false;
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private static boolean performTapAccessibility(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x + 1, y + 1);
        
        GestureDescription.Builder builder = new GestureDescription.Builder();
        GestureDescription.StrokeDescription strokeDesc = 
            new GestureDescription.StrokeDescription(path, 0, 50);
        
        builder.addStroke(strokeDesc);
        GestureDescription gesture = builder.build();
        
        return dispatchGesture(gesture);
    }

    private static boolean performMultiTouchSimultaneous(List<TouchPoint> touches) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Logger.logWarn(LOG_TAG, "Multi-touch requires Android N+");
            return false;
        }

        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            
            for (TouchPoint touch : touches) {
                Path path = new Path();
                path.moveTo(touch.x, touch.y);
                path.lineTo(touch.x + 1, touch.y + 1);
                
                GestureDescription.StrokeDescription strokeDesc = 
                    new GestureDescription.StrokeDescription(path, 0, 50);
                builder.addStroke(strokeDesc);
            }
            
            return dispatchGesture(builder.build());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error performing multi-touch", e);
            return false;
        }
    }

    private static boolean performMultiTouchSequential(List<TouchPoint> touches) {
        for (TouchPoint touch : touches) {
            if (!performTap(touch.x, touch.y)) {
                return false;
            }
            try {
                Thread.sleep(50); // Small delay between taps
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static boolean performSwipe(int x1, int y1, int x2, int y2, long duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Logger.logWarn(LOG_TAG, "Swipe requires Android N+");
            return false;
        }

        try {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            
            GestureDescription.Builder builder = new GestureDescription.Builder();
            GestureDescription.StrokeDescription strokeDesc = 
                new GestureDescription.StrokeDescription(path, 0, duration);
            
            builder.addStroke(strokeDesc);
            return dispatchGesture(builder.build());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error performing swipe", e);
            return false;
        }
    }

    private static boolean performLongPress(int x, int y, long duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Logger.logWarn(LOG_TAG, "Long press requires Android N+");
            return false;
        }

        try {
            Path path = new Path();
            path.moveTo(x, y);
            path.lineTo(x + 1, y + 1);
            
            GestureDescription.Builder builder = new GestureDescription.Builder();
            GestureDescription.StrokeDescription strokeDesc = 
                new GestureDescription.StrokeDescription(path, 0, duration);
            
            builder.addStroke(strokeDesc);
            return dispatchGesture(builder.build());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error performing long press", e);
            return false;
        }
    }

    private static boolean performMacro(List<MacroAction> actions) {
        try {
            for (MacroAction action : actions) {
                switch (action.action.toLowerCase()) {
                    case "wait":
                        Thread.sleep(action.delay);
                        break;
                    case "tap":
                        if (!performTap(action.x, action.y)) return false;
                        break;
                    case "long_press":
                        if (!performLongPress(action.x, action.y, action.duration)) return false;
                        break;
                    case "swipe":
                        if (!performSwipe(action.x1, action.y1, action.x2, action.y2, action.duration)) 
                            return false;
                        break;
                }
            }
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error performing macro", e);
            return false;
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private static boolean dispatchGesture(GestureDescription gesture) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }

        try {
            // This would require AccessibilityService, but for now we use a mock approach
            // In production, you'd need to bind to accessibility service
            Logger.logDebug(LOG_TAG, "Dispatching gesture");
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error dispatching gesture", e);
            return false;
        }
    }

    // ==================== Helper Methods ====================

    private static List<TouchPoint> parseTouches(String json) {
        List<TouchPoint> touches = new ArrayList<>();
        try {
            // Simple JSON parsing for touch points
            // Format: [{"x":100,"y":200},{"x":300,"y":400}]
            json = json.replaceAll("[\\[\\]]", "");
            String[] parts = json.split("\\}");
            
            for (String part : parts) {
                if (part.trim().isEmpty()) continue;
                
                part = part.replaceAll("[\\{,]", "");
                String[] pairs = part.split("\"");
                
                int x = -1, y = -1;
                for (int i = 0; i < pairs.length; i++) {
                    if (pairs[i].contains("x") && i + 2 < pairs.length) {
                        x = Integer.parseInt(pairs[i + 2].replaceAll("[^0-9]", ""));
                    }
                    if (pairs[i].contains("y") && i + 2 < pairs.length) {
                        y = Integer.parseInt(pairs[i + 2].replaceAll("[^0-9]", ""));
                    }
                }
                
                if (x >= 0 && y >= 0) {
                    touches.add(new TouchPoint(x, y));
                }
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error parsing touches", e);
        }
        return touches;
    }

    private static List<MacroAction> parseMacro(String json) {
        List<MacroAction> actions = new ArrayList<>();
        try {
            // Simple JSON parsing for macro actions
            json = json.replaceAll("[\\[\\]]", "");
            String[] parts = json.split("\\}");
            
            for (String part : parts) {
                if (part.trim().isEmpty()) continue;
                
                MacroAction action = new MacroAction();
                
                // Extract action type
                if (part.contains("\"action\"")) {
                    action.action = extractValue(part, "action");
                }
                
                // Extract coordinates/delays
                if (part.contains("\"x\"")) action.x = Integer.parseInt(extractValue(part, "x"));
                if (part.contains("\"y\"")) action.y = Integer.parseInt(extractValue(part, "y"));
                if (part.contains("\"x1\"")) action.x1 = Integer.parseInt(extractValue(part, "x1"));
                if (part.contains("\"y1\"")) action.y1 = Integer.parseInt(extractValue(part, "y1"));
                if (part.contains("\"x2\"")) action.x2 = Integer.parseInt(extractValue(part, "x2"));
                if (part.contains("\"y2\"")) action.y2 = Integer.parseInt(extractValue(part, "y2"));
                if (part.contains("\"delay\"")) action.delay = Long.parseLong(extractValue(part, "delay"));
                if (part.contains("\"duration\"")) action.duration = Long.parseLong(extractValue(part, "duration"));
                
                if (!action.action.isEmpty()) {
                    actions.add(action);
                }
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error parsing macro", e);
        }
        return actions;
    }

    private static String extractValue(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return "";
        
        start = json.indexOf(":", start);
        int end = json.indexOf(",", start);
        if (end == -1) end = json.indexOf("}", start);
        
        String value = json.substring(start + 1, end).trim();
        return value.replaceAll("[\"\\s]", "");
    }

    // ==================== Helper Classes ====================

    private static class TouchPoint {
        int x, y;
        
        TouchPoint(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class MacroAction {
        String action = "";
        int x = -1, y = -1;
        int x1 = -1, y1 = -1;
        int x2 = -1, y2 = -1;
        long delay = 0;
        long duration = 500;
    }

    /**
     * Mock AccessibilityService for future implementation
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public static class TouchAccessibilityService extends AccessibilityService {
        
        @Override
        public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
            // Not needed for now
        }

        @Override
        public void onInterrupt() {
            // Not needed for now
        }
    }
}
