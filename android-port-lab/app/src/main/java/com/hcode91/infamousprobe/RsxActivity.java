package com.hcode91.infamousprobe;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Displays actual framebuffer pixels created by the upstream synthetic PS3
 * guest's NV4097 FIFO/RSX commands. The software backend rasterizes triangles.
 * This is not a native RSX-to-Vulkan shader port or inFAMOUS game graphics.
 */
public final class RsxActivity extends Activity {
    private TextView status;
    private ImageView preview;
    private TextView counter;
    private Button prev, next;
    private final ArrayList<Bitmap> frames = new ArrayList<>();
    private int selected = 0;
    private volatile Process running;
    private volatile boolean closing = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.rgb(10, 14, 26));

        TextView title = new TextView(this);
        title.setText("PS3 RSX | Guest triangle render");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("Actual PS3-style guest FIFO commands → RSX parser → software rasterizer → Android framebuffer preview. No game files.");
        note.setTextColor(Color.rgb(186, 204, 227));
        note.setTextSize(13);
        note.setPadding(0, 12, 0, 12);
        root.addView(note);

        preview = new ImageView(this);
        preview.setBackgroundColor(Color.BLACK);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams pictureParams =
                new LinearLayout.LayoutParams(-1,
                        (int)(235 * getResources().getDisplayMetrics().density));
        root.addView(preview, pictureParams);

        counter = new TextView(this);
        counter.setText("Launching guest PS3 graphics pipeline...");
        counter.setTextColor(Color.WHITE);
        counter.setGravity(Gravity.CENTER);
        counter.setPadding(0, 10, 0, 10);
        root.addView(counter);

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        prev = new Button(this); prev.setText("Previous frame");
        next = new Button(this); next.setText("Next frame");
        navigation.addView(prev, new LinearLayout.LayoutParams(0, -2, 1));
        navigation.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        prev.setEnabled(false); next.setEnabled(false);
        root.addView(navigation);
        prev.setOnClickListener(v -> showFrame(selected - 1));
        next.setOnClickListener(v -> showFrame(selected + 1));

        status = new TextView(this);
        status.setTextColor(Color.rgb(182, 235, 196));
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(12);
        status.setText("Waiting for guest program...");
        status.setPadding(0, 16, 0, 10);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        new Thread(this::runGuest, "guest-rsx-graphics").start();
    }

    private void showFrame(int idx) {
        if (idx < 0 || idx >= frames.size()) return;
        selected = idx;
        preview.setImageBitmap(frames.get(idx));
        counter.setText("PS3 RSX framebuffer: " + (idx + 1) + " of " + frames.size());
        prev.setEnabled(idx > 0);
        next.setEnabled(idx + 1 < frames.size());
    }

    private void runGuest() {
        Process process = null;
        File dir = new File(getCacheDir(), "guest-rsx-frames");
        ArrayList<Bitmap> decoded = new ArrayList<>();
        try {
            if (!dir.isDirectory() && !dir.mkdirs())
                throw new IOException("Cannot create private frame directory");
            File[] existing = dir.listFiles();
            if (existing != null) for (File old : existing) {
                if (old.getName().startsWith("nullsw_") &&
                        old.getName().endsWith(".bmp")) old.delete();
            }
            File elf = new File(dir, "upstream-smoke.elf");
            try (InputStream src = getAssets().open("upstream-smoke.elf");
                 FileOutputStream dst = new FileOutputStream(elf, false)) {
                byte[] buf = new byte[8192];
                int n; int count = 0;
                while ((n = src.read(buf)) >= 0) {
                    count += n;
                    if (count > 4 * 1024 * 1024)
                        throw new IOException("ELF exceeds 4 MB safety limit");
                    dst.write(buf, 0, n);
                }
            }
            File runner = new File(getApplicationInfo().nativeLibraryDir, "libps3boot.so");
            if (!runner.isFile()) throw new IOException("Upstream runtime binary missing");
            ProcessBuilder builder = new ProcessBuilder(runner.getAbsolutePath(),
                    elf.getAbsolutePath());
            builder.directory(dir);
            builder.redirectErrorStream(true);
            builder.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
            builder.environment().put("NULLSW_DUMP", "1");
            builder.environment().put("RSX_W", "640");
            builder.environment().put("RSX_H", "360");
            process = builder.start();
            running = process;
            final Process child = process;
            final AtomicBoolean timedOut = new AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                try {
                    if (!child.waitFor(30, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        child.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    child.destroyForcibly();
                }
            }, "rsx-process-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            ByteArrayOutputStream logs = new ByteArrayOutputStream();
            try (InputStream stdout = child.getInputStream()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = stdout.read(chunk)) != -1) {
                    if (logs.size() < 262144)
                        logs.write(chunk, 0, Math.min(n, 262144 - logs.size()));
                }
            }
            int exit = child.waitFor();
            watchdog.join(1000);
            String log = new String(logs.toByteArray(), StandardCharsets.UTF_8);
            boolean guestPassed = log.contains("[smoke] PASS");
            int distinct = 0;
            int priorCenter = 0;
            for (int i = 1; i <= 3; ++i) {
                File bmp = new File(dir, String.format(java.util.Locale.US, "nullsw_%05d.bmp", i));
                if (!bmp.isFile()) continue;
                Bitmap frame = decodeBmp24(bmp);
                decoded.add(frame);
                int center = frame.getPixel(frame.getWidth()/2, frame.getHeight()/2);
                if (i == 1 || center != priorCenter) distinct++;
                priorCenter = center;
            }
            boolean enough = decoded.size() == 3 && distinct == 3 && guestPassed
                    && !timedOut.get();
            String heading = "Guest PPU execution: " + (guestPassed ? "PASS" : "FAIL") + "\n"
                    + "RSX FIFO output bitmaps: " + decoded.size() + "/3\n"
                    + "Distinct centre colours: " + distinct + "/3\n"
                    + "Expected synthetic process exit: 39; actual: " + exit + "\n"
                    + "Timed out: " + timedOut.get() + "\n"
                    + "PS3 RSX TRIANGLE DRAW: " + (enough ? "PASS" : "NOT VERIFIED") + "\n"
                    + "Renderer: upstream software RSX rasterizer, NOT Vulkan RSX.\n"
                    + "This is not inFAMOUS game graphics.\n\n";
            String tail = log.length() > 5000 ? log.substring(log.length()-5000) : log;
            if (closing) return;
            runOnUiThread(() -> {
                if (closing) return;
                frames.addAll(decoded);
                if (!frames.isEmpty()) showFrame(0);
                status.setText(heading + tail);
            });
        } catch (Exception ex) {
            for (Bitmap bm : decoded) bm.recycle();
            if (!closing) runOnUiThread(() ->
                status.setText("Guest RSX render failed: " + ex.toString()));
        } finally {
            running = null;
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static int le16(byte[] x, int p) {
        return (x[p] & 255) | ((x[p+1] & 255) << 8);
    }
    private static int le32(byte[] x, int p) {
        return (x[p] & 255) | ((x[p+1] & 255) << 8)
                | ((x[p+2] & 255) << 16) | ((x[p+3] & 255) << 24);
    }
    private static Bitmap decodeBmp24(File file) throws IOException {
        long length = file.length();
        if (length < 54 || length > 12 * 1024 * 1024) throw new IOException("Invalid BMP size");
        byte[] bytes = new byte[(int)length];
        try (FileInputStream input = new FileInputStream(file)) {
            int at = 0, n;
            while (at < bytes.length && (n = input.read(bytes, at, bytes.length-at)) > 0)
                at += n;
            if (at != bytes.length) throw new IOException("Truncated RSX BMP");
        }
        if (bytes[0] != 'B' || bytes[1] != 'M' ||
                le32(bytes, 14) < 40 || le16(bytes, 26) != 1 ||
                le16(bytes, 28) != 24 || le32(bytes, 30) != 0)
            throw new IOException("Unsupported RSX BMP format");
        int offset = le32(bytes, 10);
        int width = le32(bytes, 18), signedHeight = le32(bytes, 22);
        if (width < 1 || width > 1920 || signedHeight == 0 ||
            signedHeight == Integer.MIN_VALUE || Math.abs(signedHeight) > 1080)
            throw new IOException("Unexpected RSX framebuffer resolution");
        int height = Math.abs(signedHeight);
        long rowSize = ((width * 3L + 3L) / 4L) * 4L;
        if (offset < 54 || offset + rowSize * height > length)
            throw new IOException("Incomplete RSX framebuffer pixels");
        int[] pixels = new int[width * height];
        for (int row = 0; row < height; ++row) {
            int destY = signedHeight > 0 ? (height - row - 1) : row;
            int pos = offset + (int)(rowSize * row);
            for (int x = 0; x < width; ++x) {
                int b = bytes[pos++] & 255, g = bytes[pos++] & 255,
                    r = bytes[pos++] & 255;
                pixels[destY * width + x] = Color.rgb(r, g, b);
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    @Override protected void onDestroy() {
        closing = true;
        Process p = running;
        if (p != null) p.destroyForcibly();
        for (Bitmap bm : frames) bm.recycle();
        super.onDestroy();
    }
}
