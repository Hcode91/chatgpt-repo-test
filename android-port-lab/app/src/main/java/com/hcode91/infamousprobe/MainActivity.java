package com.hcode91.infamousprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PICK_EXECUTABLE = 101;
    private static final int PICK_EU_BOOT = 104;
    private TextView output;
    private String startup;
    private int testRunCount = 0;
    private static native String nativeProbe();
    private static native String nativePpuSmoke();
    private static native String nativeElfSmoke(byte[] image);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 12, 23));
        getWindow().setNavigationBarColor(Color.rgb(9, 12, 23));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 34, 28, 28);
        root.setBackgroundColor(Color.rgb(12, 17, 29));

        TextView heading = new TextView(this);
        heading.setText("inFAMOUS | Android Port Lab");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(23);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading);

        TextView note = new TextView(this);
        note.setText("\nARM64 + Vulkan + ps3recomp runtime probe\nNOT a playable game. No PS3 code or assets are bundled.\n");
        note.setTextColor(Color.rgb(191, 204, 224));
        note.setTextSize(15);
        root.addView(note);

        Button run = new Button(this);
        run.setText("Run native runtime tests");
        root.addView(run);
        run.setOnClickListener(v -> showProbe());

        Button ppu = new Button(this);
        ppu.setText("Run recompiled PPU instructions");
        root.addView(ppu);
        ppu.setOnClickListener(v -> {
            output.setText("Executing recompiled PowerPC instructions...\n");
            try {
                output.setText(nativePpuSmoke());
            } catch (Throwable e) {
                output.setText("PPU smoke test ERROR: " + e.getMessage());
            }
        });

        Button elf = new Button(this);
        elf.setText("Boot clean-room PS3 ELF");
        root.addView(elf);
        elf.setOnClickListener(v -> {
            output.setText("Loading PS3-format ELF and resolving entry...\n");
            try (InputStream in = getAssets().open("smoke.elf")) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] part = new byte[4096];
                int n;
                while ((n = in.read(part)) != -1) {
                    if (buffer.size() + n > 1024 * 1024) {
                        throw new IOException("Bundled test ELF unexpectedly large");
                    }
                    buffer.write(part, 0, n);
                }
                output.setText(nativeElfSmoke(buffer.toByteArray()));
            } catch (Throwable e) {
                output.setText("ELF smoke test ERROR: " + e.getMessage());
            }
        });


        Button integrated = new Button(this);
        integrated.setText("BOOT REAL PS3RECOMP RUNTIME (EXPERIMENTAL)");
        root.addView(integrated);
        integrated.setOnClickListener(v -> runIntegratedBoot());

        // This button is enabled only if a PRIVATE build included the BCES00220
        // game-specific native executable, generated from the owner's own ELF.
        java.io.File privateRunner = new java.io.File(
                getApplicationInfo().nativeLibraryDir, "libps3eu.so");
        Button realGame = new Button(this);
        realGame.setText(privateRunner.isFile()
                ? "BOOT MY EUROPEAN inFAMOUS (REAL GAME ATTEMPT)"
                : "European game boot unavailable (private PPU lift required)");
        root.addView(realGame);
        realGame.setEnabled(privateRunner.isFile());
        realGame.setOnClickListener(v -> {
            Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            choose.setType("*/*");
            choose.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(choose, PICK_EU_BOOT);
        });

        Button rsxGraphics = new Button(this);
        rsxGraphics.setText("DRAW PS3 RSX TRIANGLES (GUEST CODE)");
        root.addView(rsxGraphics);
        rsxGraphics.setOnClickListener(v ->
            startActivity(new Intent(this, RsxActivity.class)));

        Button graphics = new Button(this);
        graphics.setText("TEST VULKAN GRAPHICS ON SCREEN");
        root.addView(graphics);
        graphics.setOnClickListener(v -> startActivity(new Intent(this, VulkanActivity.class)));

        Button pick = new Button(this);
        pick.setText("Inspect my EBOOT.elf");
        root.addView(pick);
        pick.setOnClickListener(v -> {
            Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            choose.setType("*/*");
            choose.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(choose, PICK_EXECUTABLE);
        });

        output = new TextView(this);
        output.setTextColor(Color.rgb(180, 239, 204));
        output.setTextSize(14);
        output.setTypeface(Typeface.MONOSPACE);
        output.setPadding(0, 20, 0, 12);
        root.addView(output);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.addView(root);
        setContentView(scroller);
        output.setText("Ready. Try DRAW PS3 RSX TRIANGLES for genuine guest-generated graphics.\n");
    }

    /**
     * Run the actual upstream Android ARM64 PS3 boot executable, in its own
     * sandboxed child process. Only the synthetic upstream test ELF is used.
     * Capture bounded stdout/stderr and kill a hung child after 25 seconds.
     * Never run untrusted user-selected ELF files through this experiment.
     */
    private void runIntegratedBoot() {
        output.setText("Starting full upstream PPU/LV2/HLE/RSX boot scaffold...\n"
                + "Synthetic program only, NOT inFAMOUS.\n");
        new Thread(() -> {
            Process child = null;
            try {
                java.io.File test = new java.io.File(getFilesDir(), "upstream-smoke.elf");
                try (InputStream src = getAssets().open("upstream-smoke.elf");
                     java.io.FileOutputStream dst = new java.io.FileOutputStream(test, false)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = src.read(buf)) >= 0) {
                        total += n;
                        if (total > 4 * 1024 * 1024)
                            throw new IOException("Upstream ELF exceeds 4 MB limit");
                        dst.write(buf, 0, n);
                    }
                }
                java.io.File runner = new java.io.File(
                        getApplicationInfo().nativeLibraryDir, "libps3boot.so");
                if (!runner.isFile() || !runner.canExecute())
                    throw new IOException("Native boot executable not extracted: " + runner);
                ProcessBuilder builder = new ProcessBuilder(
                        runner.getAbsolutePath(), test.getAbsolutePath());
                builder.directory(getFilesDir());
                builder.redirectErrorStream(true);
                builder.environment().put("LD_LIBRARY_PATH",
                        getApplicationInfo().nativeLibraryDir);
                child = builder.start();

                final Process running = child;
                final java.util.concurrent.atomic.AtomicBoolean timedOut =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                Thread watchdog = new Thread(() -> {
                    try {
                        if (!running.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)) {
                            timedOut.set(true);
                            running.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running.destroyForcibly();
                    }
                });
                watchdog.setDaemon(true);
                watchdog.start();

                java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
                try (InputStream stream = running.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = stream.read(buffer)) != -1) {
                        if (collected.size() < 65536)
                            collected.write(buffer, 0, Math.min(n, 65536 - collected.size()));
                    }
                }
                int exit = running.waitFor();
                watchdog.join(1000);
                String log = new String(collected.toByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8);
                boolean reachedBoot = log.contains("boot:init_done")
                        || log.contains("[boot] dispatching entry OPD");
                String result = "UPSTREAM PS3RECOMP BOOT REPORT\n"
                        + "ARM64 native child process: launched\n"
                        + "Runtime initialization reached: " + reachedBoot + "\n"
                        + "Process exit code: " + exit + "\n"
                        + "Timed out: " + timedOut.get() + "\n"
                        + "No inFAMOUS game data loaded.\n\n"
                        + "OUTPUT (max 64 KB):\n" + log;
                runOnUiThread(() -> output.setText(result));
            } catch (Exception e) {
                String problem = "UPSTREAM BOOT FAILED\n"
                        + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\n\nThis is an execution/integration error, not a game result.";
                runOnUiThread(() -> output.setText(problem));
            } finally {
                if (child != null && child.isAlive())
                    child.destroyForcibly();
            }
        }, "ps3recomp-boot-worker").start();
    }

    /**
     * Genuine BCES00220 boot attempt. This path requires PRIVATE locally
     * recompiled title code to be packaged as libps3eu.so; public CI does not
     * include or execute the proprietary game. No synthetic guest is passed
     * to the game runner.
     */
    private void launchPrivateEuropeanGame(Uri source) {
        java.io.File runner = new java.io.File(getApplicationInfo().nativeLibraryDir, "libps3eu.so");
        if (!runner.isFile()) {
            output.setText("EU GAME BOOT NOT AVAILABLE: This APK contains no private "
                    + "game-code lift. Use the local private BCES00220 build pipeline.");
            return;
        }
        output.setText("Checking BCES00220 ELF fingerprint and launching real PPU boot...\\n");
        new Thread(() -> {
            Process child = null;
            try {
                java.io.File game = new java.io.File(getFilesDir(), "EBOOT.elf");
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                long bytes = 0;
                try (InputStream in = getContentResolver().openInputStream(source);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(game, false)) {
                    if (in == null) throw new IOException("Cannot read private ELF");
                    byte[] b = new byte[65536];
                    for (int n; (n = in.read(b)) != -1;) {
                        bytes += n;
                        if (bytes > 32L * 1024 * 1024)
                            throw new IOException("Private EBOOT.elf exceeds 32 MiB admission bound");
                        digest.update(b, 0, n);
                        out.write(b, 0, n);
                    }
                }
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest())
                    hash.append(String.format(Locale.US, "%02x", b & 255));
                final String wanted = "095d851432f3b55179cd725b46838ffedb3e5eb9f73889e52b6f11f3d3f494fd";
                if (!wanted.equals(hash.toString()))
                    throw new IOException("BCES00220 EBOOT.elf SHA-256 mismatch: " + hash);
                if (!runner.canExecute())
                    throw new IOException("Game boot binary is not executable");
                ProcessBuilder pb = new ProcessBuilder(runner.getAbsolutePath(), game.getAbsolutePath());
                pb.redirectErrorStream(true);
                pb.directory(getFilesDir());
                pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
                // Debug-oriented boot: PS3 runtime logs every milestone.
                pb.environment().put("PS3_VERBOSE", "1");
                child = pb.start();
                final Process active = child;
                final java.util.concurrent.atomic.AtomicBoolean timedOut =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                Thread watchdog = new Thread(() -> {
                    try {
                        if (!active.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
                            timedOut.set(true);
                            active.destroyForcibly();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        active.destroyForcibly();
                    }
                });
                watchdog.setDaemon(true);
                watchdog.start();
                java.io.ByteArrayOutputStream logs = new java.io.ByteArrayOutputStream();
                try (InputStream log = child.getInputStream()) {
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = log.read(chunk)) != -1)
                        if (logs.size() < 128 * 1024)
                            logs.write(chunk, 0, Math.min(n, 128 * 1024 - logs.size()));
                }
                int exit = child.waitFor();
                watchdog.join(1500);
                String report = "REAL BCES00220 inFAMOUS BOOT ATTEMPT\\n"
                        + "Verified exact European ELF: yes\\n"
                        + "Private recompiled PPU runner: launched\\n"
                        + "Guest image size: " + bytes + "\\n"
                        + "Exit code: " + exit + "\\n"
                        + "Timeout: " + timedOut.get() + "\\n"
                        + "Startup logs (max 128 KiB):\\n"
                        + logs.toString("UTF-8")
                        + "\\nThis does not prove gameplay or graphics are functional.";
                runOnUiThread(() -> output.setText(report));
            } catch (Throwable err) {
                String failure = "REAL EUROPEAN GAME BOOT COULD NOT COMPLETE\\n"
                        + err.getClass().getSimpleName() + ": " + err.getMessage();
                runOnUiThread(() -> output.setText(failure));
            } finally {
                if (child != null && child.isAlive()) child.destroyForcibly();
            }
        }, "infamous-eu-private-boot").start();
    }

    private void showProbe() {
        testRunCount++;
        String completedAt = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date());
        try {
            startup = nativeProbe();
        } catch (Throwable e) {
            startup = "Native library ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        output.setText("TEST RUN #" + testRunCount + " FINISHED AT " + completedAt
                + "\n\n" + startup
                + "\n\nNext: port the real PPU boot scaffold and RSX renderer. This app does not execute inFAMOUS.\n");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_EU_BOOT && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            launchPrivateEuropeanGame(data.getData());
            return;
        }
        if (requestCode != PICK_EXECUTABLE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        byte[] header = new byte[64];
        int got = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open selected file");
            while (got < header.length) {
                int n = in.read(header, got, header.length - got);
                if (n < 0) break;
                got += n;
            }
            boolean elf = got >= 20 && header[0] == 0x7f && header[1] == 'E'
                    && header[2] == 'L' && header[3] == 'F';
            boolean is64 = elf && header[4] == 2;
            boolean bigEndian = elf && header[5] == 2;
            int machine = got >= 20 ? (((header[18] & 255) << 8) | (header[19] & 255)) : -1;
            boolean ppu = is64 && bigEndian && machine == 21;
            String report = "Selected: " + uri.getLastPathSegment()
                  + "\nELF header: " + (elf ? "yes" : "no")
                  + "\nELF64: " + is64
                  + "\nBig endian: " + bigEndian
                  + "\ne_machine: " + (machine >= 0 ? String.format(Locale.US, "0x%04X", machine) : "unknown")
                  + "\nPowerPC64 PS3-format candidate: " + ppu
                  + "\n\nInspection only. The file stays on your device and is not uploaded or executed.";
            output.setText(startup + "\n\n" + report);
        } catch (IOException e) {
            output.setText(startup + "\n\nFile error: " + e.getMessage());
        }
    }

    static {
        System.loadLibrary("infamousprobe");
    }
}
