package com.termux.api.apis;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reconnecting, latest-frame-only consumer for the universal TCAM framed protocol.
 * Consumers perform decoding in {@link Listener#onFrame(Frame)}, away from the socket reader.
 */
public final class CameraStreamClient implements Closeable {

    private static final String LOG_TAG = "CameraStreamClient";
    private static final int FRAME_MAGIC = 0x5443414d;
    private static final int FRAME_HEADER_SIZE = 36;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private static final long RECONNECT_DELAY_MILLIS = 500;

    public interface Listener {
        void onConnectionChanged(boolean connected, @Nullable String error);
        void onFrame(Frame frame);
    }

    public static final class Frame {
        public final int format;
        public final int width;
        public final int height;
        public final long sequence;
        public final long timestampNanos;
        public final byte[] payload;

        Frame(int format, int width, int height, long sequence, long timestampNanos,
                byte[] payload) {
            this.format = format;
            this.width = width;
            this.height = height;
            this.sequence = sequence;
            this.timestampNanos = timestampNanos;
            this.payload = payload;
        }

        public String formatName() {
            switch (format) {
                case 1: return "jpeg";
                case 2: return "png";
                case 3: return "rgb";
                case 4: return "yuv420";
                default: return "error";
            }
        }
    }

    private final String socketName;
    private final Listener listener;
    private final AtomicReference<Frame> pending = new AtomicReference<>();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();
    private final Object frameSignal = new Object();
    private volatile boolean closed;
    private volatile LocalSocket activeSocket;
    private Thread readerThread;
    private Thread dispatchThread;
    private long lastSequence;

    public CameraStreamClient(String socketName, Listener listener) {
        if (socketName == null || socketName.isEmpty() || socketName.contains("/")) {
            throw new IllegalArgumentException("An abstract Unix socket name is required");
        }
        this.socketName = socketName;
        this.listener = listener;
    }

    public synchronized void start() {
        if (readerThread != null) return;
        readerThread = new Thread(this::readLoop, "TermuxCameraStreamReader");
        dispatchThread = new Thread(this::dispatchLoop, "TermuxCameraStreamDecoder");
        readerThread.setDaemon(true);
        dispatchThread.setDaemon(true);
        dispatchThread.start();
        readerThread.start();
    }

    public long getReceivedFrames() {
        return receivedFrames.get();
    }

    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    private void readLoop() {
        while (!closed) {
            LocalSocket socket = new LocalSocket();
            activeSocket = socket;
            try {
                socket.connect(new LocalSocketAddress(socketName,
                    LocalSocketAddress.Namespace.ABSTRACT));
                socket.setReceiveBufferSize(64 * 1024);
                listener.onConnectionChanged(true, null);
                readFrames(new DataInputStream(socket.getInputStream()));
                if (!closed) listener.onConnectionChanged(false, "Camera stream closed");
            } catch (IOException | RuntimeException e) {
                if (!closed) {
                    Logger.logDebug(LOG_TAG, "Camera stream reconnect: " + safeMessage(e));
                    listener.onConnectionChanged(false, safeMessage(e));
                }
            } finally {
                closeSocket(socket);
                if (activeSocket == socket) activeSocket = null;
            }
            if (!closed) sleepBeforeReconnect();
        }
    }

    private void readFrames(DataInputStream input) throws IOException {
        while (!closed) {
            int magic;
            try {
                magic = input.readInt();
            } catch (EOFException e) {
                return;
            }
            int version = input.readUnsignedByte();
            int format = input.readUnsignedByte();
            int headerSize = input.readUnsignedShort();
            int width = input.readInt();
            int height = input.readInt();
            long sequence = input.readLong();
            long timestamp = input.readLong();
            int length = input.readInt();
            if (magic != FRAME_MAGIC || version != 1 || headerSize < FRAME_HEADER_SIZE) {
                throw new IOException("Unsupported TCAM frame header");
            }
            if (headerSize > FRAME_HEADER_SIZE) skipFully(input, headerSize - FRAME_HEADER_SIZE);
            if (format < 0 || format > 4 || width < 0 || height < 0 ||
                    length < 0 || length > MAX_PAYLOAD_BYTES) {
                throw new IOException("Invalid TCAM frame metadata");
            }
            byte[] payload = new byte[length];
            input.readFully(payload);
            if (format == 0) {
                throw new IOException(new String(payload,
                    java.nio.charset.StandardCharsets.UTF_8));
            }
            receivedFrames.incrementAndGet();
            if (lastSequence > 0 && sequence > lastSequence + 1) {
                droppedFrames.addAndGet(sequence - lastSequence - 1);
            }
            lastSequence = sequence;
            Frame replaced = pending.getAndSet(
                new Frame(format, width, height, sequence, timestamp, payload));
            if (replaced != null) droppedFrames.incrementAndGet();
            synchronized (frameSignal) {
                frameSignal.notifyAll();
            }
        }
    }

    private void dispatchLoop() {
        while (!closed) {
            Frame frame = pending.getAndSet(null);
            if (frame == null) {
                synchronized (frameSignal) {
                    try {
                        frameSignal.wait(1_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }
            try {
                listener.onFrame(frame);
            } catch (RuntimeException e) {
                Logger.logWarn(LOG_TAG, "Camera consumer rejected frame: " + safeMessage(e));
            }
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed = true;
        }
    }

    private static void skipFully(DataInputStream input, int bytes) throws IOException {
        int remaining = bytes;
        while (remaining > 0) {
            int skipped = input.skipBytes(remaining);
            if (skipped <= 0) throw new EOFException("Incomplete TCAM extension header");
            remaining -= skipped;
        }
    }

    @Nullable
    public static Bitmap decodeBitmap(Frame frame) {
        try {
            switch (frame.format) {
                case 1:
                case 2:
                    return BitmapFactory.decodeByteArray(frame.payload, 0, frame.payload.length);
                case 3:
                    return decodeRgb(frame);
                case 4:
                    return decodeI420(frame);
                default:
                    return null;
            }
        } catch (IllegalArgumentException | OutOfMemoryError e) {
            Logger.logWarn(LOG_TAG, "Unable to decode camera frame: " + safeMessage(e));
            return null;
        }
    }

    private static Bitmap decodeRgb(Frame frame) {
        long expected = (long) frame.width * frame.height * 3;
        if (frame.width < 1 || frame.height < 1 || expected != frame.payload.length) return null;
        int[] colors = new int[frame.width * frame.height];
        int source = 0;
        for (int i = 0; i < colors.length; i++) {
            int red = frame.payload[source++] & 0xff;
            int green = frame.payload[source++] & 0xff;
            int blue = frame.payload[source++] & 0xff;
            colors[i] = 0xff000000 | (red << 16) | (green << 8) | blue;
        }
        return Bitmap.createBitmap(colors, frame.width, frame.height, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap decodeI420(Frame frame) {
        int chromaWidth = (frame.width + 1) / 2;
        int chromaHeight = (frame.height + 1) / 2;
        long expected = (long) frame.width * frame.height +
            (long) chromaWidth * chromaHeight * 2;
        if (frame.width < 1 || frame.height < 1 || expected != frame.payload.length) return null;
        int ySize = frame.width * frame.height;
        int chromaSize = chromaWidth * chromaHeight;
        int[] colors = new int[ySize];
        for (int y = 0; y < frame.height; y++) {
            for (int x = 0; x < frame.width; x++) {
                int chromaIndex = (y / 2) * chromaWidth + x / 2;
                int yValue = (frame.payload[y * frame.width + x] & 0xff) - 16;
                int u = (frame.payload[ySize + chromaIndex] & 0xff) - 128;
                int v = (frame.payload[ySize + chromaSize + chromaIndex] & 0xff) - 128;
                int c = Math.max(0, yValue) * 298;
                int red = clamp((c + 409 * v + 128) >> 8);
                int green = clamp((c - 100 * u - 208 * v + 128) >> 8);
                int blue = clamp((c + 516 * u + 128) >> 8);
                colors[y * frame.width + x] =
                    0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
        return Bitmap.createBitmap(colors, frame.width, frame.height, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
            ? throwable.getClass().getSimpleName() : message;
    }

    private static void closeSocket(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        LocalSocket socket = activeSocket;
        if (socket != null) closeSocket(socket);
        synchronized (frameSignal) {
            frameSignal.notifyAll();
        }
        if (readerThread != null) readerThread.interrupt();
        if (dispatchThread != null) dispatchThread.interrupt();
        pending.set(null);
    }
}
