package com.termux.api.apis;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.termux.api.R;
import com.termux.api.activities.TermuxAPIMainActivity;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Camera2 foreground service providing low-latency frames to generic transports. */
public class CameraProviderService extends Service {

    public static final String ACTION_STREAM = "stream";
    public static final String ACTION_RECORD = "record";
    public static final String ACTION_STOP = "stop";
    static final String EXTRA_RESULT_RECEIVER = "com.termux.api.camera.RESULT_RECEIVER";
    static final String RESULT_ERROR = "error";

    private static final String LOG_TAG = "CameraProviderService";
    private static final String CHANNEL_ID = "termux_api_camera_provider";
    private static final int NOTIFICATION_ID = 0x43414d;
    private static final int FRAME_MAGIC = 0x5443414d; // TCAM
    private static final int FRAME_HEADER_SIZE = 36;
    private static final int MAX_CLIENTS = 16;

    private static volatile Snapshot snapshot = Snapshot.stopped();

    private final AtomicLong frameCounter = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private MediaRecorder mediaRecorder;
    private Surface recorderSurface;
    private ThreadPoolExecutor conversionExecutor;
    private FrameDispatcher frameDispatcher;
    private Config activeConfig;
    private Intent pendingCommand;
    private int generation;
    private boolean stopping;

    public static boolean isRunning() {
        return snapshot.running;
    }

    public static Snapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        cameraThread = new HandlerThread("TermuxCameraProvider");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        startForeground(NOTIFICATION_ID, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        cameraHandler.post(() -> handleCommand(intent, startId));
        return START_NOT_STICKY;
    }

    private void handleCommand(Intent intent, int startId) {
        switch (intent.getAction()) {
            case ACTION_STREAM:
                startStream(intent, startId);
                break;
            case ACTION_RECORD:
                startRecording(intent, startId);
                break;
            case ACTION_STOP:
                stopActive();
                sendResult(intent, null);
                stopForeground(true);
                stopSelf(startId);
                break;
            default:
                sendResult(intent, "Unknown camera provider action: " + intent.getAction());
                stopSelf(startId);
        }
    }

    @SuppressLint("MissingPermission")
    private void startStream(Intent intent, int startId) {
        stopActive();
        pendingCommand = intent;
        activeConfig = Config.forStream(intent);
        int currentGeneration = ++generation;
        frameCounter.set(0);
        droppedFrames.set(0);
        try {
            frameDispatcher = new FrameDispatcher(this, activeConfig, droppedFrames,
                this::onClientsChanged);
            frameDispatcher.start();
            imageReader = ImageReader.newInstance(activeConfig.width, activeConfig.height,
                ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(
                reader -> onImageAvailable(reader, currentGeneration), cameraHandler);
            conversionExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "TermuxCameraConverter");
                    thread.setDaemon(true);
                    return thread;
                });
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            manager.openCamera(activeConfig.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (currentGeneration != generation) {
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                    configureStreamSession(currentGeneration);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    failStartIfCurrent("Camera disconnected", startId, currentGeneration);
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    failStartIfCurrent("Unable to open camera (error " + error + ")",
                        startId, currentGeneration);
                }
            }, cameraHandler);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to start camera stream", e);
            failStart("Unable to start camera stream: " + safeMessage(e), startId);
        }
    }

    private void configureStreamSession(int currentGeneration) {
        try {
            Surface surface = imageReader.getSurface();
            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        if (currentGeneration != generation || cameraDevice == null) {
                            session.close();
                            return;
                        }
                        captureSession = session;
                        try {
                            CaptureRequest.Builder request = cameraDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                            request.addTarget(surface);
                            applyCaptureSettings(request, activeConfig);
                            session.setRepeatingRequest(request.build(), null, cameraHandler);
                            snapshot = Snapshot.fromConfig(activeConfig, "stream", true,
                                frameDispatcher.getClientCount(), 0, 0);
                            sendPendingResult(null);
                        } catch (CameraAccessException | IllegalArgumentException e) {
                            failStartIfCurrent("Unable to start frame capture: " + safeMessage(e),
                                0, currentGeneration);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        session.close();
                        failStartIfCurrent("Camera does not support the requested stream", 0,
                            currentGeneration);
                    }
                }, cameraHandler);
        } catch (CameraAccessException e) {
            failStartIfCurrent("Unable to configure camera stream: " + safeMessage(e), 0,
                currentGeneration);
        }
    }

    private void onImageAvailable(ImageReader reader, int currentGeneration) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || currentGeneration != generation) return;
            YuvFrame yuv = YuvFrame.copyOf(image);
            Runnable conversion = () -> convertAndPublish(yuv, currentGeneration);
            if (!conversionExecutor.getQueue().offer(conversion)) {
                conversionExecutor.getQueue().poll();
                droppedFrames.incrementAndGet();
                conversionExecutor.getQueue().offer(conversion);
            }
            conversionExecutor.prestartCoreThread();
        } catch (RuntimeException e) {
            droppedFrames.incrementAndGet();
            Logger.logWarn(LOG_TAG, "Dropping invalid camera frame: " + safeMessage(e));
        } finally {
            if (image != null) image.close();
        }
    }

    private void convertAndPublish(YuvFrame yuv, int currentGeneration) {
        if (currentGeneration != generation || frameDispatcher == null) return;
        try {
            byte[] payload = FrameConverter.encode(yuv, activeConfig.format, activeConfig.quality);
            long sequence = frameCounter.incrementAndGet();
            frameDispatcher.publish(new FramePacket(activeConfig.format, yuv.width, yuv.height,
                sequence, yuv.timestampNanos, payload));
            publishProgress();
        } catch (RuntimeException | IOException e) {
            droppedFrames.incrementAndGet();
            Logger.logWarn(LOG_TAG, "Unable to convert camera frame: " + safeMessage(e));
        }
    }

    @SuppressLint("MissingPermission")
    private void startRecording(Intent intent, int startId) {
        stopActive();
        pendingCommand = intent;
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(
                intent.getStringExtra("camera"));
            StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(MediaRecorder.class);
            Size selected = chooseClosestSize(sizes, intent.getIntExtra("width", 1280),
                intent.getIntExtra("height", 720));
            if (selected == null) throw new IllegalStateException("No video output size available");
            activeConfig = Config.forRecord(intent, selected);
            int currentGeneration = ++generation;

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(activeConfig.width, activeConfig.height);
            mediaRecorder.setVideoFrameRate(activeConfig.fps);
            mediaRecorder.setVideoEncodingBitRate(activeConfig.bitrate);
            mediaRecorder.setOutputFile(activeConfig.file);
            mediaRecorder.prepare();
            recorderSurface = mediaRecorder.getSurface();

            manager.openCamera(activeConfig.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (currentGeneration != generation) {
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                    configureRecordSession(currentGeneration);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    failStartIfCurrent("Camera disconnected", startId, currentGeneration);
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    failStartIfCurrent("Unable to open camera (error " + error + ")",
                        startId, currentGeneration);
                }
            }, cameraHandler);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to start video recording", e);
            failStart("Unable to start video recording: " + safeMessage(e), startId);
        }
    }

    private void configureRecordSession(int currentGeneration) {
        try {
            cameraDevice.createCaptureSession(Collections.singletonList(recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        if (currentGeneration != generation || cameraDevice == null) {
                            session.close();
                            return;
                        }
                        captureSession = session;
                        try {
                            CaptureRequest.Builder request = cameraDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_RECORD);
                            request.addTarget(recorderSurface);
                            applyCaptureSettings(request, activeConfig);
                            session.setRepeatingRequest(request.build(), null, cameraHandler);
                            mediaRecorder.start();
                            snapshot = Snapshot.fromConfig(activeConfig, "record", true, 0, 0, 0);
                            sendPendingResult(null);
                        } catch (CameraAccessException | RuntimeException e) {
                            failStartIfCurrent("Unable to start recording: " + safeMessage(e),
                                0, currentGeneration);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        session.close();
                        failStartIfCurrent("Camera does not support the requested recording", 0,
                            currentGeneration);
                    }
                }, cameraHandler);
        } catch (CameraAccessException e) {
            failStartIfCurrent("Unable to configure video recording: " + safeMessage(e), 0,
                currentGeneration);
        }
    }

    private void applyCaptureSettings(CaptureRequest.Builder request, Config config) {
        request.set(CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        if (config.fpsRange != null) {
            request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.fpsRange);
        }
    }

    private void failStart(String error, int startId) {
        Logger.logWarn(LOG_TAG, error);
        if (frameDispatcher != null) frameDispatcher.publishError(error);
        sendPendingResult(error);
        stopActive();
        stopForeground(true);
        if (startId > 0) stopSelf(startId); else stopSelf();
    }

    private void failStartIfCurrent(String error, int startId, int expectedGeneration) {
        if (expectedGeneration == generation) failStart(error, startId);
    }

    private void sendPendingResult(@Nullable String error) {
        Intent command = pendingCommand;
        pendingCommand = null;
        if (command != null) sendResult(command, error);
    }

    private void sendResult(Intent intent, @Nullable String error) {
        ResultReceiver receiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
        if (receiver == null) return;
        Bundle result = new Bundle();
        if (error != null) result.putString(RESULT_ERROR, error);
        receiver.send(error == null ? 0 : 1, result);
    }

    private void publishProgress() {
        Config config = activeConfig;
        if (config == null || !snapshot.running) return;
        int clients = frameDispatcher == null ? 0 : frameDispatcher.getClientCount();
        snapshot = Snapshot.fromConfig(config, snapshot.mode, true, clients,
            frameCounter.get(), droppedFrames.get());
    }

    private void onClientsChanged() {
        publishProgress();
        Config config = activeConfig;
        FrameDispatcher dispatcher = frameDispatcher;
        if (!stopping && snapshot.running && config != null && dispatcher != null &&
                config.outputs.size() == 1 && config.outputs.contains("pipe") &&
                dispatcher.getClientCount() == 0) {
            cameraHandler.post(() -> {
                if (!stopping && activeConfig == config && frameDispatcher != null &&
                        frameDispatcher.getClientCount() == 0) {
                    stopActive();
                    stopForeground(true);
                    stopSelf();
                }
            });
        }
    }

    private void stopActive() {
        stopping = true;
        generation++;
        pendingCommand = null;
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            } catch (CameraAccessException | IllegalStateException ignored) {
            }
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (conversionExecutor != null) {
            conversionExecutor.shutdownNow();
            conversionExecutor = null;
        }
        if (mediaRecorder != null) {
            try {
                if (snapshot.running && "record".equals(snapshot.mode)) mediaRecorder.stop();
            } catch (RuntimeException e) {
                Logger.logWarn(LOG_TAG, "Video recorder stopped before a valid file was produced");
            }
            try {
                mediaRecorder.reset();
            } catch (RuntimeException ignored) {
            }
            try {
                mediaRecorder.release();
            } catch (RuntimeException ignored) {
            }
            mediaRecorder = null;
        }
        if (recorderSurface != null) {
            recorderSurface.release();
            recorderSurface = null;
        }
        if (frameDispatcher != null) {
            frameDispatcher.close();
            frameDispatcher = null;
        }
        activeConfig = null;
        frameCounter.set(0);
        snapshot = Snapshot.stopped();
        stopping = false;
    }

    @Override
    public void onDestroy() {
        stopActive();
        stopForeground(true);
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.camera_provider_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.camera_provider_notification_channel_description));
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, TermuxAPIMainActivity.class), pendingIntentFlags());
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, CameraProviderService.class).setAction(ACTION_STOP),
            pendingIntentFlags());
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder
            .setSmallIcon(R.drawable.ic_photo_camera_black_24dp)
            .setContentTitle(getString(R.string.camera_provider_notification_title))
            .setContentText(getString(R.string.camera_provider_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(0,
                getString(R.string.camera_provider_notification_stop), stop).build())
            .build();
    }

    private int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    static Size chooseClosestSize(@Nullable Size[] sizes, int requestedWidth,
            int requestedHeight) {
        if (sizes == null || sizes.length == 0) return null;
        double requestedAspect = requestedWidth / (double) requestedHeight;
        long requestedArea = (long) requestedWidth * requestedHeight;
        return Collections.min(Arrays.asList(sizes), Comparator.comparingDouble(size -> {
            double aspect = size.getWidth() / (double) size.getHeight();
            long area = (long) size.getWidth() * size.getHeight();
            return Math.abs(Math.log(area / (double) requestedArea)) +
                Math.abs(aspect - requestedAspect) * 4;
        }));
    }

    static Range<Integer> chooseFpsRange(@Nullable Range<Integer>[] ranges, int requestedFps) {
        if (ranges == null || ranges.length == 0) return null;
        return Collections.min(Arrays.asList(ranges), Comparator.comparingInt(range -> {
            if (range.contains(requestedFps)) return range.getUpper() - range.getLower();
            return Math.min(Math.abs(requestedFps - range.getLower()),
                Math.abs(requestedFps - range.getUpper())) * 1000 +
                range.getUpper() - range.getLower();
        }));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
            ? throwable.getClass().getSimpleName() : message;
    }

    private static final class Config {
        final String cameraId;
        final int width;
        final int height;
        final int fps;
        final Range<Integer> fpsRange;
        final String format;
        final Set<String> outputs;
        final String protocol;
        final int quality;
        final int tcpPort;
        final String socketName;
        final String socketOutput;
        final String directory;
        final String file;
        final int bitrate;
        final long startedAt;

        private Config(Intent intent, Size selected, Range<Integer> fpsRange, String format,
                Set<String> outputs) {
            cameraId = intent.getStringExtra("camera");
            width = selected.getWidth();
            height = selected.getHeight();
            this.fpsRange = fpsRange;
            fps = fpsRange == null ? intent.getIntExtra("fps", 30)
                : Math.max(fpsRange.getLower(),
                    Math.min(intent.getIntExtra("fps", 30), fpsRange.getUpper()));
            this.format = format;
            this.outputs = outputs;
            protocol = intent.getStringExtra("protocol") == null
                ? "framed" : intent.getStringExtra("protocol");
            quality = intent.getIntExtra("quality", 85);
            tcpPort = intent.getIntExtra("port", 9000);
            socketName = intent.getStringExtra("socket_name") == null
                ? "termux.camera.frames" : intent.getStringExtra("socket_name");
            socketOutput = intent.getStringExtra("socket_output");
            directory = intent.getStringExtra("directory") == null
                ? "" : intent.getStringExtra("directory");
            file = intent.getStringExtra("file") == null ? "" : intent.getStringExtra("file");
            bitrate = intent.getIntExtra("bitrate", 8_000_000);
            startedAt = System.currentTimeMillis();
        }

        static Config forStream(Intent intent) {
            try {
                CameraManager manager = (CameraManager) ResultReturner.context.getSystemService(
                    Context.CAMERA_SERVICE);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(
                    intent.getStringExtra("camera"));
                StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size selected = chooseClosestSize(
                    map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888),
                    intent.getIntExtra("width", 1280), intent.getIntExtra("height", 720));
                if (selected == null) throw new IllegalStateException("No YUV stream size available");
                Range<Integer> range = chooseFpsRange(characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES),
                    intent.getIntExtra("fps", 30));
                Set<String> outputs = new HashSet<>(Arrays.asList(
                    intent.getStringExtra("output").split(",")));
                return new Config(intent, selected, range, intent.getStringExtra("format"), outputs);
            } catch (CameraAccessException e) {
                throw new IllegalStateException("Unable to read camera configuration", e);
            }
        }

        static Config forRecord(Intent intent, Size selected) {
            try {
                CameraManager manager = (CameraManager) ResultReturner.context.getSystemService(
                    Context.CAMERA_SERVICE);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(
                    intent.getStringExtra("camera"));
                Range<Integer> range = chooseFpsRange(characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES),
                    intent.getIntExtra("fps", 30));
                return new Config(intent, selected, range, "mp4", Collections.emptySet());
            } catch (CameraAccessException e) {
                throw new IllegalStateException("Unable to read camera configuration", e);
            }
        }

        String outputNames() {
            if (outputs.isEmpty()) return "file";
            List<String> names = new ArrayList<>(outputs);
            Collections.sort(names);
            return android.text.TextUtils.join(",", names);
        }
    }

    public static final class Snapshot {
        public final boolean running;
        public final String mode;
        public final String cameraId;
        public final int width;
        public final int height;
        public final int fps;
        public final String format;
        public final String outputs;
        public final String protocol;
        public final int tcpPort;
        public final String socketName;
        public final String file;
        public final String directory;
        public final long frames;
        public final long droppedFrames;
        public final int clients;
        public final long startedAt;

        private Snapshot(boolean running, String mode, String cameraId, int width, int height,
                int fps, String format, String outputs, String protocol, int tcpPort,
                String socketName, String file, String directory, long frames,
                long droppedFrames, int clients, long startedAt) {
            this.running = running;
            this.mode = mode;
            this.cameraId = cameraId;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.format = format;
            this.outputs = outputs;
            this.protocol = protocol;
            this.tcpPort = tcpPort;
            this.socketName = socketName;
            this.file = file;
            this.directory = directory;
            this.frames = frames;
            this.droppedFrames = droppedFrames;
            this.clients = clients;
            this.startedAt = startedAt;
        }

        static Snapshot fromConfig(Config config, String mode, boolean running, int clients,
                long frames, long droppedFrames) {
            return new Snapshot(running, mode, config.cameraId, config.width, config.height,
                config.fps, config.format, config.outputNames(), config.protocol, config.tcpPort,
                config.socketName, config.file, config.directory, frames, droppedFrames, clients,
                config.startedAt);
        }

        static Snapshot stopped() {
            return new Snapshot(false, "none", "", 0, 0, 0, "none", "", "framed", 0,
                "", "", "", 0, 0, 0, 0);
        }
    }

    private static final class YuvFrame {
        final int width;
        final int height;
        final long timestampNanos;
        final byte[] i420;

        private YuvFrame(int width, int height, long timestampNanos, byte[] i420) {
            this.width = width;
            this.height = height;
            this.timestampNanos = timestampNanos;
            this.i420 = i420;
        }

        static YuvFrame copyOf(Image image) {
            int width = image.getWidth();
            int height = image.getHeight();
            int chromaWidth = (width + 1) / 2;
            int chromaHeight = (height + 1) / 2;
            byte[] i420 = new byte[width * height + chromaWidth * chromaHeight * 2];
            copyPlane(image.getPlanes()[0], width, height, i420, 0);
            copyPlane(image.getPlanes()[1], chromaWidth, chromaHeight, i420, width * height);
            copyPlane(image.getPlanes()[2], chromaWidth, chromaHeight, i420,
                width * height + chromaWidth * chromaHeight);
            return new YuvFrame(width, height, image.getTimestamp(), i420);
        }

        private static void copyPlane(Image.Plane plane, int width, int height, byte[] target,
                int targetOffset) {
            ByteBuffer buffer = plane.getBuffer().duplicate();
            int base = buffer.position();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            for (int row = 0; row < height; row++) {
                int rowStart = base + row * rowStride;
                for (int column = 0; column < width; column++) {
                    target[targetOffset++] = buffer.get(rowStart + column * pixelStride);
                }
            }
        }
    }

    private static final class FrameConverter {
        static byte[] encode(YuvFrame frame, String format, int quality) throws IOException {
            switch (format) {
                case "yuv420":
                    return frame.i420;
                case "rgb":
                    return toRgb(frame);
                case "jpeg":
                    return compress(frame, Bitmap.CompressFormat.JPEG, quality);
                case "png":
                    return compress(frame, Bitmap.CompressFormat.PNG, 100);
                default:
                    throw new IOException("Unsupported frame format: " + format);
            }
        }

        private static byte[] compress(YuvFrame frame, Bitmap.CompressFormat format, int quality)
                throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (format == Bitmap.CompressFormat.JPEG) {
                byte[] nv21 = toNv21(frame);
                YuvImage image = new YuvImage(nv21, ImageFormat.NV21,
                    frame.width, frame.height, null);
                if (!image.compressToJpeg(new Rect(0, 0, frame.width, frame.height), quality, output)) {
                    throw new IOException("JPEG encoder rejected frame");
                }
                return output.toByteArray();
            }
            int[] colors = toArgb(frame);
            Bitmap bitmap = Bitmap.createBitmap(colors, frame.width, frame.height,
                Bitmap.Config.ARGB_8888);
            try {
                if (!bitmap.compress(format, quality, output)) {
                    throw new IOException("PNG encoder rejected frame");
                }
                return output.toByteArray();
            } finally {
                bitmap.recycle();
            }
        }

        private static byte[] toNv21(YuvFrame frame) {
            int ySize = frame.width * frame.height;
            int chromaSize = ((frame.width + 1) / 2) * ((frame.height + 1) / 2);
            byte[] nv21 = new byte[ySize + chromaSize * 2];
            System.arraycopy(frame.i420, 0, nv21, 0, ySize);
            int uOffset = ySize;
            int vOffset = ySize + chromaSize;
            int target = ySize;
            for (int i = 0; i < chromaSize; i++) {
                nv21[target++] = frame.i420[vOffset + i];
                nv21[target++] = frame.i420[uOffset + i];
            }
            return nv21;
        }

        private static byte[] toRgb(YuvFrame frame) {
            int[] argb = toArgb(frame);
            byte[] rgb = new byte[frame.width * frame.height * 3];
            int target = 0;
            for (int color : argb) {
                rgb[target++] = (byte) ((color >> 16) & 0xff);
                rgb[target++] = (byte) ((color >> 8) & 0xff);
                rgb[target++] = (byte) (color & 0xff);
            }
            return rgb;
        }

        private static int[] toArgb(YuvFrame frame) {
            int width = frame.width;
            int height = frame.height;
            int ySize = width * height;
            int chromaWidth = (width + 1) / 2;
            int chromaSize = chromaWidth * ((height + 1) / 2);
            int[] colors = new int[ySize];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int yValue = (frame.i420[y * width + x] & 0xff) - 16;
                    int chromaIndex = (y / 2) * chromaWidth + x / 2;
                    int u = (frame.i420[ySize + chromaIndex] & 0xff) - 128;
                    int v = (frame.i420[ySize + chromaSize + chromaIndex] & 0xff) - 128;
                    int c = Math.max(0, yValue) * 298;
                    int red = clamp((c + 409 * v + 128) >> 8);
                    int green = clamp((c - 100 * u - 208 * v + 128) >> 8);
                    int blue = clamp((c + 516 * u + 128) >> 8);
                    colors[y * width + x] = 0xff000000 | red << 16 | green << 8 | blue;
                }
            }
            return colors;
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }
    }

    private static final class FramePacket {
        final String format;
        final int width;
        final int height;
        final long sequence;
        final long timestampNanos;
        final byte[] payload;

        FramePacket(String format, int width, int height, long sequence, long timestampNanos,
                byte[] payload) {
            this.format = format;
            this.width = width;
            this.height = height;
            this.sequence = sequence;
            this.timestampNanos = timestampNanos;
            this.payload = payload;
        }

        int formatCode() {
            switch (format) {
                case "jpeg": return 1;
                case "png": return 2;
                case "rgb": return 3;
                case "yuv420": return 4;
                default: return 0;
            }
        }
    }

    private static final class FrameDispatcher implements Closeable {
        private final Context context;
        private final Config config;
        private final AtomicLong droppedFrames;
        private final Runnable stateChanged;
        private final CopyOnWriteArrayList<FrameClient> clients = new CopyOnWriteArrayList<>();
        private final List<Closeable> servers = new ArrayList<>();
        private FileFrameSink fileSink;

        FrameDispatcher(Context context, Config config, AtomicLong droppedFrames,
                Runnable stateChanged) {
            this.context = context;
            this.config = config;
            this.droppedFrames = droppedFrames;
            this.stateChanged = stateChanged;
        }

        void start() throws IOException {
            if (config.outputs.contains("file")) fileSink = new FileFrameSink(config.directory);
            if (config.outputs.contains("pipe")) {
                LocalSocket socket = new LocalSocket();
                socket.connect(ResultReturner.getApiLocalSocketAddress(context, "camera output",
                    config.socketOutput));
                addClient(socket.getOutputStream(), socket, false,
                    "mjpeg".equals(config.protocol));
            }
            if (config.outputs.contains("tcp")) {
                TcpFrameServer server = new TcpFrameServer(config.tcpPort,
                    "mjpeg".equals(config.protocol));
                servers.add(server);
                server.start();
            }
            if (config.outputs.contains("unix")) {
                UnixFrameServer server = new UnixFrameServer(config.socketName,
                    "mjpeg".equals(config.protocol));
                servers.add(server);
                server.start();
            }
        }

        void publish(FramePacket frame) {
            if (fileSink != null) {
                try {
                    fileSink.write(frame);
                } catch (IOException e) {
                    droppedFrames.incrementAndGet();
                    Logger.logWarn(LOG_TAG, "Unable to write frame file: " + safeMessage(e));
                }
            }
            for (FrameClient client : clients) client.offer(frame);
        }

        void publishError(String error) {
            byte[] payload = ("{\"error\":\"" + escapeJson(error) + "\"}").getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
            FramePacket packet = new FramePacket("error", 0, 0, 0,
                System.nanoTime(), payload);
            for (FrameClient client : clients) client.offer(packet);
        }

        int getClientCount() {
            return clients.size();
        }

        private void addClient(OutputStream output, Closeable socket, boolean httpMjpeg,
                boolean rawMjpeg) throws IOException {
            if (clients.size() >= MAX_CLIENTS) {
                socket.close();
                return;
            }
            FrameClient[] holder = new FrameClient[1];
            FrameClient client = new FrameClient(output, socket, httpMjpeg, rawMjpeg,
                droppedFrames, () -> {
                    clients.remove(holder[0]);
                    stateChanged.run();
                });
            holder[0] = client;
            clients.add(client);
            client.start();
            stateChanged.run();
        }

        @Override
        public void close() {
            for (Closeable server : servers) closeQuietly(server);
            servers.clear();
            for (FrameClient client : clients) client.close();
            clients.clear();
        }

        private final class TcpFrameServer extends Thread implements Closeable {
            private final ServerSocket server;
            private final boolean mjpeg;

            TcpFrameServer(int port, boolean mjpeg) throws IOException {
                super("TermuxCameraTcpServer");
                this.mjpeg = mjpeg;
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    MAX_CLIENTS);
                setDaemon(true);
            }

            @Override
            public void run() {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        socket.setTcpNoDelay(true);
                        socket.setSendBufferSize(64 * 1024);
                        addClient(new BufferedOutputStream(socket.getOutputStream(), 32 * 1024),
                            socket, mjpeg, false);
                    } catch (IOException e) {
                        if (!server.isClosed()) Logger.logWarn(LOG_TAG,
                            "TCP camera client failed: " + safeMessage(e));
                    }
                }
            }

            @Override
            public void close() {
                closeQuietly(server);
            }
        }

        private final class UnixFrameServer extends Thread implements Closeable {
            private final LocalServerSocket server;
            private final boolean mjpeg;
            private volatile boolean closed;

            UnixFrameServer(String name, boolean mjpeg) throws IOException {
                super("TermuxCameraUnixServer");
                this.mjpeg = mjpeg;
                server = new LocalServerSocket(name);
                setDaemon(true);
            }

            @Override
            public void run() {
                while (!closed) {
                    try {
                        LocalSocket socket = server.accept();
                        socket.setSendBufferSize(64 * 1024);
                        addClient(new BufferedOutputStream(socket.getOutputStream(), 32 * 1024),
                            socket, false, mjpeg);
                    } catch (IOException e) {
                        if (!closed) Logger.logWarn(LOG_TAG,
                            "Unix camera client failed: " + safeMessage(e));
                    }
                }
            }

            @Override
            public void close() {
                closed = true;
                try {
                    server.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class FileFrameSink {
        private final File directory;
        private long nextSequence = 1;

        FileFrameSink(String path) {
            directory = new File(path);
        }

        void write(FramePacket frame) throws IOException {
            File target;
            do {
                target = new File(directory, String.format(Locale.ROOT, "frame%06d.%s",
                    nextSequence++, extension(frame.format)));
            } while (!target.createNewFile());
            try (FileOutputStream output = new FileOutputStream(target)) {
                output.write(frame.payload);
            }
        }

        private String extension(String format) {
            if ("jpeg".equals(format)) return "jpg";
            if ("rgb".equals(format)) return "rgb";
            if ("yuv420".equals(format)) return "yuv";
            return format;
        }
    }

    private static final class FrameClient extends Thread implements Closeable {
        private final OutputStream output;
        private final Closeable socket;
        private final boolean httpMjpeg;
        private final boolean rawMjpeg;
        private final AtomicLong droppedFrames;
        private final Runnable onClose;
        private final AtomicReference<FramePacket> pending = new AtomicReference<>();
        private final Object signal = new Object();
        private volatile boolean closed;

        FrameClient(OutputStream output, Closeable socket, boolean httpMjpeg, boolean rawMjpeg,
                AtomicLong droppedFrames, Runnable onClose) {
            super("TermuxCameraFrameClient");
            this.output = output;
            this.socket = socket;
            this.httpMjpeg = httpMjpeg;
            this.rawMjpeg = rawMjpeg;
            this.droppedFrames = droppedFrames;
            this.onClose = onClose;
            setDaemon(true);
        }

        void offer(FramePacket frame) {
            FramePacket replaced = pending.getAndSet(frame);
            if (replaced != null) droppedFrames.incrementAndGet();
            synchronized (signal) {
                signal.notifyAll();
            }
        }

        @Override
        public void run() {
            try {
                if (httpMjpeg) writeHttpHeader(output);
                while (!closed) {
                    FramePacket frame = waitForFrame();
                    if (frame == null) continue;
                    if (httpMjpeg) writeHttpMjpegFrame(output, frame);
                    else if (rawMjpeg) writeRawMjpegFrame(output, frame);
                    else writeFramedFrame(output, frame);
                    output.flush();
                }
            } catch (IOException e) {
                if (!closed) Logger.logDebug(LOG_TAG,
                    "Camera stream client disconnected: " + safeMessage(e));
            } finally {
                close();
                onClose.run();
            }
        }

        private FramePacket waitForFrame() {
            FramePacket frame = pending.getAndSet(null);
            if (frame != null || closed) return frame;
            synchronized (signal) {
                try {
                    signal.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    closed = true;
                }
            }
            return pending.getAndSet(null);
        }

        @Override
        public void close() {
            closed = true;
            synchronized (signal) {
                signal.notifyAll();
            }
            closeQuietly(output);
            closeQuietly(socket);
        }
    }

    private static void writeFramedFrame(OutputStream rawOutput, FramePacket frame)
            throws IOException {
        DataOutputStream output = new DataOutputStream(rawOutput);
        output.writeInt(FRAME_MAGIC);
        output.writeByte(1);
        output.writeByte(frame.formatCode());
        output.writeShort(FRAME_HEADER_SIZE);
        output.writeInt(frame.width);
        output.writeInt(frame.height);
        output.writeLong(frame.sequence);
        output.writeLong(frame.timestampNanos);
        output.writeInt(frame.payload.length);
        output.write(frame.payload);
    }

    private static void writeHttpHeader(OutputStream output) throws IOException {
        output.write(("HTTP/1.1 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=termux-camera\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeHttpMjpegFrame(OutputStream output, FramePacket frame)
            throws IOException {
        if (!"jpeg".equals(frame.format)) return;
        output.write(("--termux-camera\r\nContent-Type: image/jpeg\r\nContent-Length: " +
            frame.payload.length + "\r\nX-Sequence: " + frame.sequence + "\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(frame.payload);
        output.write("\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeRawMjpegFrame(OutputStream output, FramePacket frame)
            throws IOException {
        if ("jpeg".equals(frame.format)) output.write(frame.payload);
    }

    private static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
