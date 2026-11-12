package com.retrotools.cciextractor;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Bridge to the bundled native engine (ctrtool + makerom from 3DSGuy/Project_CTR, wrapped by the
 * cia3ds-android JNI layer). It decrypts an encrypted CIA on-device and rebuilds it as a CCI.
 * Decrypted CIAs never come here: {@link CiaConverter} repackages those in pure Java.
 */
final class NativeCia {
    /** Called on the converting thread. Names and signatures are looked up from native code. */
    interface ProgressCallback { void onProgress(int percent, String message); }
    interface LogCallback { void onLine(String line); }

    /** Engine result for titles with no cartridge-image form (DLC, updates, system titles, DSiWare). */
    static final int RC_NOT_CONVERTIBLE = 14;
    static final int RC_CANCELLED = 13;

    private static boolean loaded;

    private NativeCia() {}

    private static native int nativeDecryptCia(int inFd, int outFd, String seedDbPath, String tmpDir,
                                               String originalName, boolean wantCci, ProgressCallback progress,
                                               LogCallback log, Object seedFetcher);
    private static native String nativeVersion();
    private static native void nativeCancel();

    private static synchronized void load() throws IOException {
        if (loaded) return;
        try { System.loadLibrary("cia3ds"); loaded = true; }
        catch (UnsatisfiedLinkError e) {
            throw new IOException("The decryption engine isn't available on this device (" + e.getMessage() + ")");
        }
    }

    static void cancel() {
        synchronized (NativeCia.class) { if (!loaded) return; }
        try { nativeCancel(); } catch (Throwable ignored) { }
    }

    /** Copies the bundled seed database (needed for seed-encrypted titles) to the cache and returns its path. */
    private static File seedDb(Context ctx) throws IOException {
        File target = new File(ctx.getCacheDir(), "seeddb.bin");
        try (InputStream in = ctx.getAssets().open("seeddb.bin")) {
            if (target.isFile() && target.length() == in.available()) return target;
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
        }
        return target;
    }

    /**
     * Decrypts the CIA behind {@code inFd} and writes a CCI to {@code outFd}. Blocks until done.
     * No network access is used: seeds come only from the bundled database.
     *
     * @return the engine's result code; 0 means success. See {@link #describe(int)}.
     */
    static int ciaToCci(Context ctx, int inFd, int outFd, String name, ProgressCallback progress, LogCallback log)
            throws IOException {
        load();
        File work = new File(ctx.getCacheDir(), "cia-engine");
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("Cannot create the engine's work folder");
        return nativeDecryptCia(inFd, outFd, seedDb(ctx).getAbsolutePath(), work.getAbsolutePath(), name, true,
                progress, log, null);
    }

    /** Human-readable reason for a non-zero engine result. */
    static String describe(int rc) {
        switch (rc) {
            case 1: return "Couldn't create the engine's work folder (storage full, or the app cache isn't writable).";
            case 2: return "Couldn't stage the input file (storage full, or the source is no longer reachable).";
            case 4: case 8: return "Converted, but writing the result failed (storage full, or the destination isn't writable).";
            case 5: return "The contents couldn't be extracted. The file may be corrupt or truncated, or this title " +
                    "needs a key or seed that isn't available.";
            case 6: return "No NCCH partitions were found in this file (malformed, truncated, or an unusual layout).";
            case 7: return "The CCI couldn't be built from the decrypted contents (storage full, or unusual title metadata).";
            case 9: return "A partition couldn't be decrypted. The file may be corrupt or truncated, or this title " +
                    "needs a key or seed that isn't available.";
            case 10: case 11: return "Nothing convertible could be extracted from this file.";
            case 12: return "This isn't a recognised CIA (wrong file type, or corrupt).";
            case RC_CANCELLED: return "Cancelled";
            case RC_NOT_CONVERTIBLE: return "Only games and demos can become a CCI. DLC, updates, system titles and " +
                    "DSiWare have no cartridge-image form and must stay as CIA.";
            default: return "Engine error code " + rc;
        }
    }
}
