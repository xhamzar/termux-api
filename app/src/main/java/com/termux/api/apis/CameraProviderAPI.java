package com.termux.api.apis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.JsonWriter;
import android.util.Range;
import android.util.Size;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Entry point for the universal photo, video, and realtime camera provider. */
public final class CameraProviderAPI {

    private static final String LOG_TAG = "CameraProviderAPI";
    private static final long COMMAND_TIMEOUT_MILLIS = 15_000;
    private static final Set<String> STREAM_FORMATS = new HashSet<>(
        Arrays.asList("jpeg", "png", "rgb", "yuv420"));
    private static final Set<String> OUTPUTS = new HashSet<>(
        Arrays.asList("pipe", "file", "tcp", "unix"));

    private CameraProviderAPI() {}

    public static String getRequestedAction(Intent intent) {
        String requested = intent.getStringExtra("action");
        return requested == null || requested.trim().isEmpty()
            ? "info" : requested.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean requiresCameraPermission(Intent intent) {
        String action = getRequestedAction(intent);
        return "photo".equals(action) || "record".equals(action) || "stream".equals(action) ||
            "control".equals(action);
    }

    public static void onReceive(TermuxApiReceiver receiver, Context context, Intent intent) {
        String action = getRequestedAction(intent);
        if ("photo".equals(action)) {
            handlePhoto(receiver, context, intent);
            return;
        }

        if ("stream".equals(action) && containsOutput(intent, "pipe")) {
            try {
                validateStreamRequest(context, intent);
                startPipeStream(context, intent);
            } catch (CameraProviderException e) {
                writeImmediateError(receiver, intent, e.getMessage());
            }
            return;
        }

        ResultReturner.returnData(receiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                try {
                    switch (action) {
                        case "info":
                            writeCameraInfo(context, intent, out);
                            break;
                        case "status":
                            writeSnapshot(out, true, null, CameraProviderService.getSnapshot(),
                                context);
                            break;
                        case "stream":
                            validateStreamRequest(context, intent);
                            writeServiceResult(context, intent, CameraProviderService.ACTION_STREAM, out);
                            break;
                        case "record":
                            validateRecordRequest(context, intent);
                            writeServiceResult(context, intent, CameraProviderService.ACTION_RECORD, out);
                            break;
                        case "control":
                            validateControlRequest(context, intent);
                            writeServiceResult(context, intent, CameraProviderService.ACTION_CONTROL, out);
                            break;
                        case "stop":
                        case "record_stop":
                        case "record-stop":
                            if (!CameraProviderService.isRunning()) {
                                writeSnapshot(out, true, null,
                                    CameraProviderService.getSnapshot(), context);
                            } else {
                                writeServiceResult(context, intent, CameraProviderService.ACTION_STOP, out);
                            }
                            break;
                        default:
                            throw new CameraProviderException("Unknown camera action: " + action);
                    }
                } catch (CameraProviderException e) {
                    out.name("success").value(false);
                    out.name("error").value(e.getMessage());
                }
                out.endObject();
            }
        });
    }

    private static void handlePhoto(TermuxApiReceiver receiver, Context context, Intent intent) {
        try {
            String format = getString(intent, "format", "jpeg").toLowerCase(Locale.ROOT);
            if (!"jpeg".equals(format)) {
                throw new CameraProviderException("Photo mode currently requires format 'jpeg'");
            }
            String cameraId = getString(intent, "camera", "0");
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (!Arrays.asList(manager.getCameraIdList()).contains(cameraId)) {
                throw new CameraProviderException("Unknown camera id: " + cameraId);
            }
            File file = canonicalFile(requireString(intent, "file"), "Photo file");
            File parent = file.getParentFile();
            if (parent == null || !parent.isDirectory() || !parent.canWrite()) {
                throw new CameraProviderException(
                    "Photo parent directory must exist and be writable");
            }
            if (file.exists()) {
                throw new CameraProviderException("Photo output file already exists");
            }
            intent.putExtra("camera", cameraId);
            intent.putExtra("file", file.getAbsolutePath());
        } catch (android.hardware.camera2.CameraAccessException e) {
            writeImmediateError(receiver, intent, "Unable to enumerate cameras");
            return;
        } catch (CameraProviderException e) {
            writeImmediateError(receiver, intent, e.getMessage());
            return;
        }
        CameraPhotoAPI.onReceive(receiver, context, intent);
    }

    private static void writeImmediateError(TermuxApiReceiver receiver, Intent intent, String error) {
        ResultReturner.returnData(receiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                out.name("success").value(false);
                out.name("error").value(error);
                out.endObject();
            }
        });
    }

    private static void startPipeStream(Context context, Intent source)
            throws CameraProviderException {
        Intent serviceIntent = createServiceIntent(context, source, CameraProviderService.ACTION_STREAM);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to start pipe camera stream", e);
            throw new CameraProviderException("Android did not allow the camera provider to start");
        }
    }

    private static void writeServiceResult(Context context, Intent source, String action,
            JsonWriter out) throws Exception {
        Bundle result = dispatchServiceCommand(context, source, action);
        writeSnapshot(out, result.getString(CameraProviderService.RESULT_ERROR) == null,
            result.getString(CameraProviderService.RESULT_ERROR),
            CameraProviderService.getSnapshot(), context);
    }

    private static Bundle dispatchServiceCommand(Context context, Intent source, String action)
            throws CameraProviderException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
        ResultReceiver resultReceiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                result.set(resultData == null ? Bundle.EMPTY : resultData);
                completed.countDown();
            }
        };
        Intent serviceIntent = createServiceIntent(context, source, action)
            .putExtra(CameraProviderService.EXTRA_RESULT_RECEIVER, resultReceiver);
        try {
            if (!CameraProviderService.isRunning() &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !CameraProviderService.ACTION_STOP.equals(action)) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to dispatch camera command", e);
            throw new CameraProviderException("Android did not allow the camera provider command");
        }
        try {
            if (!completed.await(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new CameraProviderException("Timed out waiting for the camera provider");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CameraProviderException("Camera provider command was interrupted");
        }
        return result.get() == null ? Bundle.EMPTY : result.get();
    }

    private static Intent createServiceIntent(Context context, Intent source, String action) {
        Intent target = new Intent(context, CameraProviderService.class).setAction(action);
        Bundle extras = source.getExtras();
        if (extras != null) target.putExtras(extras);
        return target;
    }

    private static void validateStreamRequest(Context context, Intent intent)
            throws CameraProviderException {
        validateCameraAndCaptureParameters(context, intent);
        String format = getString(intent, "format", "jpeg").toLowerCase(Locale.ROOT);
        if (!STREAM_FORMATS.contains(format)) {
            throw new CameraProviderException("'format' must be jpeg, png, rgb, or yuv420");
        }
        intent.putExtra("format", format);
        Set<String> outputs = parseOutputs(intent);
        if (outputs.contains("file")) {
            String directory = requireString(intent, "directory");
            File file = canonicalFile(directory, "Stream directory");
            if (!file.isDirectory() || !file.canWrite()) {
                throw new CameraProviderException("Stream directory must exist and be writable");
            }
            intent.putExtra("directory", file.getAbsolutePath());
        }
        if (outputs.contains("pipe")) {
            String socketOutput = intent.getStringExtra("socket_output");
            if (socketOutput == null || socketOutput.isEmpty()) {
                throw new CameraProviderException("Pipe output requires the Termux output socket");
            }
        }
        if (outputs.contains("tcp")) validateRange(intent, "port", 1, 65_535, 9_000);
        if (outputs.contains("unix")) {
            String name = getString(intent, "socket_name", "termux.camera.frames").trim();
            if (name.isEmpty() || name.length() > 90 || name.contains("/")) {
                throw new CameraProviderException(
                    "'socket_name' must be an abstract Unix socket name without '/'");
            }
            intent.putExtra("socket_name", name);
        }
        String protocol = getString(intent, "protocol", "framed").toLowerCase(Locale.ROOT);
        if (!("framed".equals(protocol) || "mjpeg".equals(protocol))) {
            throw new CameraProviderException("'protocol' must be framed or mjpeg");
        }
        if ("mjpeg".equals(protocol) && !"jpeg".equals(format)) {
            throw new CameraProviderException("MJPEG protocol requires JPEG frame format");
        }
        intent.putExtra("protocol", protocol);
        validateRange(intent, "quality", 1, 100, 85);
    }

    private static void validateRecordRequest(Context context, Intent intent)
            throws CameraProviderException {
        validateCameraAndCaptureParameters(context, intent);
        String filePath = requireString(intent, "file");
        File file = canonicalFile(filePath, "Video file");
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.canWrite()) {
            throw new CameraProviderException("Video parent directory must exist and be writable");
        }
        if (file.exists()) throw new CameraProviderException("Video output file already exists");
        intent.putExtra("file", file.getAbsolutePath());
        validateRange(intent, "bitrate", 100_000, 100_000_000, 8_000_000);
    }

    private static void validateControlRequest(Context context, Intent intent)
            throws CameraProviderException {
        if (!CameraProviderService.isRunning()) {
            throw new CameraProviderException("Camera provider is not running");
        }
        boolean hasControl = false;
        if (intent.hasExtra("camera")) {
            String cameraId = requireString(intent, "camera");
            try {
                CameraManager manager = (CameraManager) context.getSystemService(
                    Context.CAMERA_SERVICE);
                if (!Arrays.asList(manager.getCameraIdList()).contains(cameraId)) {
                    throw new CameraProviderException("Unknown camera id: " + cameraId);
                }
            } catch (android.hardware.camera2.CameraAccessException e) {
                throw new CameraProviderException("Unable to enumerate cameras");
            }
            intent.putExtra("camera", cameraId);
            hasControl = true;
        }
        for (String parameter : new String[]{"width", "height", "fps"}) {
            if (!intent.hasExtra(parameter)) continue;
            int maximum = "fps".equals(parameter) ? 240 : 10_000;
            validateRange(intent, parameter, 1, maximum, 1);
            hasControl = true;
        }
        if (intent.hasExtra("zoom")) {
            Object value = intent.getExtras() == null ? null : intent.getExtras().get("zoom");
            if (!(value instanceof Float)) {
                throw new CameraProviderException("'zoom' must be a floating-point value");
            }
            float zoom = intent.getFloatExtra("zoom", 1f);
            if (Float.isNaN(zoom) || Float.isInfinite(zoom) || zoom < 1f || zoom > 100f) {
                throw new CameraProviderException("'zoom' must be between 1 and 100");
            }
            hasControl = true;
        }
        if (intent.hasExtra("autofocus")) {
            String autofocus = requireString(intent, "autofocus").toLowerCase(Locale.ROOT);
            if (!("continuous".equals(autofocus) || "auto".equals(autofocus) ||
                    "off".equals(autofocus))) {
                throw new CameraProviderException(
                    "'autofocus' must be continuous, auto, or off");
            }
            intent.putExtra("autofocus", autofocus);
            hasControl = true;
        }
        if (intent.hasExtra("flash")) {
            String flash = requireString(intent, "flash").toLowerCase(Locale.ROOT);
            if (!("off".equals(flash) || "torch".equals(flash))) {
                throw new CameraProviderException("'flash' must be off or torch");
            }
            intent.putExtra("flash", flash);
            hasControl = true;
        }
        if (intent.hasExtra("exposure")) {
            validateRange(intent, "exposure", -100, 100, 0);
            hasControl = true;
        }
        if (!hasControl) {
            throw new CameraProviderException("At least one camera control is required");
        }
    }

    private static void validateCameraAndCaptureParameters(Context context, Intent intent)
            throws CameraProviderException {
        String cameraId = getString(intent, "camera", "0");
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (!Arrays.asList(manager.getCameraIdList()).contains(cameraId)) {
                throw new CameraProviderException("Unknown camera id: " + cameraId);
            }
        } catch (android.hardware.camera2.CameraAccessException e) {
            throw new CameraProviderException("Unable to enumerate cameras");
        }
        intent.putExtra("camera", cameraId);
        validateRange(intent, "width", 1, 10_000, 1280);
        validateRange(intent, "height", 1, 10_000, 720);
        validateRange(intent, "fps", 1, 240, 30);
    }

    private static Set<String> parseOutputs(Intent intent) throws CameraProviderException {
        String value = getString(intent, "output", "pipe").toLowerCase(Locale.ROOT);
        Set<String> outputs = new HashSet<>();
        for (String item : value.split(",")) {
            String output = item.trim();
            if (!OUTPUTS.contains(output)) {
                throw new CameraProviderException("'output' entries must be pipe, file, tcp, or unix");
            }
            outputs.add(output);
        }
        if (outputs.isEmpty()) throw new CameraProviderException("At least one output is required");
        intent.putExtra("output", android.text.TextUtils.join(",", outputs));
        return outputs;
    }

    private static boolean containsOutput(Intent intent, String expected) {
        String outputs = getString(intent, "output", "pipe");
        for (String output : outputs.split(",")) {
            if (expected.equalsIgnoreCase(output.trim())) return true;
        }
        return false;
    }

    private static void validateRange(Intent intent, String name, int min, int max,
            int defaultValue) throws CameraProviderException {
        int value = intent.getIntExtra(name, defaultValue);
        if (value < min || value > max) {
            throw new CameraProviderException(
                "'" + name + "' must be between " + min + " and " + max);
        }
        intent.putExtra(name, value);
    }

    private static String requireString(Intent intent, String name) throws CameraProviderException {
        String value = intent.getStringExtra(name);
        if (value == null || value.trim().isEmpty()) {
            throw new CameraProviderException("Missing '" + name + "' parameter");
        }
        return value.trim();
    }

    private static String getString(Intent intent, String name, String defaultValue) {
        String value = intent.getStringExtra(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static File canonicalFile(String path, String label) throws CameraProviderException {
        File file = new File(path);
        if (!file.isAbsolute()) throw new CameraProviderException(label + " must use an absolute path");
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            throw new CameraProviderException(label + " path is invalid");
        }
    }

    private static void writeCameraInfo(Context context, Intent intent, JsonWriter out)
            throws Exception {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String requestedId = intent.getStringExtra("camera");
        String format = getString(intent, "format", "jpeg").toLowerCase(Locale.ROOT);
        if (!STREAM_FORMATS.contains(format)) {
            throw new CameraProviderException("'format' must be jpeg, png, rgb, or yuv420");
        }
        if (requestedId != null &&
                !Arrays.asList(manager.getCameraIdList()).contains(requestedId)) {
            throw new CameraProviderException("Unknown camera id: " + requestedId);
        }
        out.name("success").value(true);
        out.name("camera_permission_granted").value(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED);
        out.name("cameras").beginArray();
        for (String cameraId : manager.getCameraIdList()) {
            if (requestedId != null && !requestedId.equals(cameraId)) continue;
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
            Size selected = CameraProviderService.chooseClosestSize(sizes,
                intent.getIntExtra("width", 1280), intent.getIntExtra("height", 720));
            Range<Integer> fpsRange = CameraProviderService.chooseFpsRange(
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES),
                intent.getIntExtra("fps", 30));
            out.beginObject();
            out.name("camera_id").value(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            out.name("facing").value(lensFacingName(facing));
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            out.name("sensor_orientation").value(orientation == null ? 0 : orientation);
            out.name("width").value(selected == null ? 0 : selected.getWidth());
            out.name("height").value(selected == null ? 0 : selected.getHeight());
            out.name("fps").value(fpsRange == null ? 0 :
                Math.max(fpsRange.getLower(),
                    Math.min(intent.getIntExtra("fps", 30), fpsRange.getUpper())));
            out.name("fps_range").beginArray();
            if (fpsRange != null) {
                out.value(fpsRange.getLower());
                out.value(fpsRange.getUpper());
            }
            out.endArray();
            out.name("format").value(format);
            out.name("formats").beginArray();
            for (String supported : new String[]{"jpeg", "png", "rgb", "yuv420"}) {
                out.value(supported);
            }
            out.endArray();
            out.name("yuv_output_sizes").beginArray();
            if (sizes != null) {
                Arrays.sort(sizes, Comparator.comparingLong(
                    size -> (long) size.getWidth() * size.getHeight()));
                for (Size size : sizes) {
                    out.beginObject();
                    out.name("width").value(size.getWidth());
                    out.name("height").value(size.getHeight());
                    out.endObject();
                }
            }
            out.endArray();
            out.endObject();
        }
        out.endArray();
    }

    private static String lensFacingName(Integer facing) {
        if (facing == null) return "unknown";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "front";
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "back";
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "external";
        return "unknown";
    }

    private static void writeSnapshot(JsonWriter out, boolean success, String error,
            CameraProviderService.Snapshot snapshot, Context context) throws Exception {
        out.name("success").value(success);
        if (error != null) out.name("error").value(error);
        out.name("camera_permission_granted").value(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED);
        out.name("running").value(snapshot.running);
        out.name("mode").value(snapshot.mode);
        out.name("camera_id").value(snapshot.cameraId);
        out.name("width").value(snapshot.width);
        out.name("height").value(snapshot.height);
        out.name("fps").value(snapshot.fps);
        out.name("format").value(snapshot.format);
        out.name("outputs").value(snapshot.outputs);
        out.name("protocol").value(snapshot.protocol);
        out.name("tcp_port").value(snapshot.tcpPort);
        out.name("socket_name").value(snapshot.socketName);
        out.name("file").value(snapshot.file);
        out.name("directory").value(snapshot.directory);
        out.name("frames").value(snapshot.frames);
        out.name("dropped_frames").value(snapshot.droppedFrames);
        out.name("clients").value(snapshot.clients);
        out.name("started_at").value(snapshot.startedAt);
        out.name("zoom").value(snapshot.zoom);
        out.name("max_zoom").value(snapshot.maxZoom);
        out.name("autofocus").value(snapshot.autofocus);
        out.name("flash").value(snapshot.flash);
        out.name("exposure").value(snapshot.exposure);
    }

    private static final class CameraProviderException extends Exception {
        CameraProviderException(String message) {
            super(message);
        }
    }
}
