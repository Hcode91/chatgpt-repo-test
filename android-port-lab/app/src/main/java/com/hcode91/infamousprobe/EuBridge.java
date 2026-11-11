package com.hcode91.infamousprobe;

import android.graphics.Bitmap;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * App side of the shared-memory channel to the game runner process.
 * Layout must match runtime-integration/eu_android_bridge.c.
 */
final class EuBridge {
    static final int MAGIC = 0x52425545; // "EUBR"
    static final int VERSION = 1;
    static final int HDR = 64;
    static final int MAX_W = 1280, MAX_H = 720;
    static final long SIZE = HDR + (long) MAX_W * MAX_H * 4L;

    // CELL_PAD_CTRL_* (DIGITAL1 low byte, DIGITAL2 high byte)
    static final int SELECT = 1, L3 = 1 << 1, R3 = 1 << 2, START = 1 << 3,
            UP = 1 << 4, RIGHT = 1 << 5, DOWN = 1 << 6, LEFT = 1 << 7,
            L2 = 1 << 8, R2 = 1 << 9, L1 = 1 << 10, R1 = 1 << 11,
            TRIANGLE = 1 << 12, CIRCLE = 1 << 13, CROSS = 1 << 14, SQUARE = 1 << 15;

    /** The bridge of the boot currently running (or last run), shared between screens. */
    static volatile EuBridge current;
    /** What is drawing the frames, for the game screen's label. */
    static volatile String rendererLabel = "";

    final File file;
    private final MappedByteBuffer buf;

    private EuBridge(File file, MappedByteBuffer buf) {
        this.file = file;
        this.buf = buf;
    }

    /** Create (or reset) the bridge file before launching the runner. */
    static EuBridge create(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(SIZE);
            MappedByteBuffer b = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            b.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < HDR; i += 4) b.putInt(i, 0);
            b.putInt(0, MAGIC);
            b.putInt(4, VERSION);
            b.putInt(20, 0x80808080); // lx ly rx ry centred
            b.putShort(24, (short) 0); // l2 r2 released
            b.force();
            return new EuBridge(f, b);
        }
    }

    /** Publish the combined controller state (touch + physical gamepad). */
    synchronized void setPad(boolean connected, int buttons, int lx, int ly, int rx, int ry,
                             int l2, int r2) {
        buf.putInt(16, buttons & 0xFFFF);
        buf.put(20, (byte) clamp(lx));
        buf.put(21, (byte) clamp(ly));
        buf.put(22, (byte) clamp(rx));
        buf.put(23, (byte) clamp(ry));
        buf.put(24, (byte) clamp(l2));
        buf.put(25, (byte) clamp(r2));
        buf.putInt(12, connected ? 1 : 0);
        buf.putInt(8, buf.getInt(8) + 1);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** Ask the runner to capture the next frame (CAP button). Returns the request number. */
    synchronized int requestCapture() {
        int n = buf.getInt(52) + 1;
        buf.putInt(52, n);
        return n;
    }

    int captureRequested() {
        return buf.getInt(52);
    }

    int captureDone() {
        return buf.getInt(56);
    }

    int framesPublished() {
        return buf.getInt(44);
    }

    boolean runnerConnected() {
        return buf.getInt(48) != 0;
    }

    int frameWidth() {
        return buf.getInt(36);
    }

    int frameHeight() {
        return buf.getInt(40);
    }

    /**
     * Copy the latest frame into bmp (which must be frameWidth x frameHeight ARGB_8888).
     * Returns false if the runner was mid-write; the caller just tries again next vsync.
     */
    boolean readFrame(Bitmap bmp) {
        int s1 = buf.getInt(32);
        if ((s1 & 1) != 0 || s1 == 0) return false;
        int w = buf.getInt(36), h = buf.getInt(40);
        if (w != bmp.getWidth() || h != bmp.getHeight() || w <= 0 || h <= 0
                || w > MAX_W || h > MAX_H) return false;
        ByteBuffer px = buf.duplicate();
        px.position(HDR);
        px.limit(HDR + w * h * 4);
        bmp.copyPixelsFromBuffer(px);
        return buf.getInt(32) == s1;
    }
}
