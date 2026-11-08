package com.hcode91.ps3elfextractor;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int PICK_EBOOT = 11, PICK_RAP = 12, PICK_KEYS = 13, SAVE_ELF = 14;
    private static final long MAX_EBOOT = 384L * 1024 * 1024;
    private final Uri[] selected = new Uri[3];
    private final String[] selectedNames = new String[]{"Not selected", "Not selected", "Not selected"};
    private TextView report, ebootStatus, rapStatus, keysStatus;
    private Button extract, save;
    private File verifiedElf;
    private volatile boolean busy;
    private static final Pattern CONTENT_ID = Pattern.compile("[A-Z]{2}[0-9]{4}-[A-Z0-9]{9}_[0-9]{2}-[A-Z0-9]{16}");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff080d18);
        getWindow().setNavigationBarColor(0xff080d18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 30, 30, 32);
        root.setBackgroundColor(0xff0e1522);

        TextView title = new TextView(this);
        title.setText("PS3 ELF Extractor");
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);
        addText(root, "Local, ARM64, scetool-based. Files stay on your phone.", 14, 0xffaebdd6);

        ebootStatus = addText(root, "EBOOT: Not selected", 14, 0xffd6e4f9);
        button(root, "1. Select EBOOT.BIN", () -> choose(PICK_EBOOT));
        rapStatus = addText(root, "RAP: Not selected", 14, 0xffd6e4f9);
        button(root, "2. Select matching .rap licence", () -> choose(PICK_RAP));
        keysStatus = addText(root, "Keys: Not selected", 14, 0xffd6e4f9);
        button(root, "3. Select your scetool keyset file", () -> choose(PICK_KEYS));

        extract = button(root, "Decrypt and verify ELF", this::extract);
        save = button(root, "Save verified EBOOT.elf", this::save);
        save.setEnabled(false);

        report = addText(root, "Ready. Select all three inputs. A RAP alone does not contain the PS3 SELF keyset.", 14, 0xffa9f1c9);
        report.setTypeface(Typeface.MONOSPACE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView addText(LinearLayout root, String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, 12, 0, 12);
        root.addView(t);
        return t;
    }

    private Button button(LinearLayout root, String label, Runnable listener) {
        Button b = new Button(this);
        b.setText(label);
        root.addView(b);
        b.setOnClickListener(v -> listener.run());
        return b;
    }

    private void choose(int request) {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == SAVE_ELF) {
            if (verifiedElf == null || !verifiedElf.isFile()) { message("No verified ELF to save."); return; }
            try (InputStream in = new FileInputStream(verifiedElf);
                 OutputStream out = getContentResolver().openOutputStream(data.getData(), "w")) {
                if (out == null) throw new IOException("Document provider returned no output stream");
                transfer(in, out, 1024L * 1024 * 1024);
                message("Saved verified EBOOT.elf to selected location.");
            } catch (Exception e) { message("Save failed: " + e.getMessage()); }
            return;
        }
        int idx = request == PICK_EBOOT ? 0 : request == PICK_RAP ? 1 : request == PICK_KEYS ? 2 : -1;
        if (idx < 0) return;
        selected[idx] = data.getData();
        selectedNames[idx] = displayName(data.getData());
        try { getContentResolver().takePersistableUriPermission(
                data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        ebootStatus.setText("EBOOT: " + selectedNames[0]);
        rapStatus.setText("RAP: " + selectedNames[1]);
        keysStatus.setText("Keys: " + selectedNames[2]);
        verifiedElf = null;
        save.setEnabled(false);
        message("Selected: " + selectedNames[idx]);
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int x = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (x >= 0 && !c.isNull(x)) return c.getString(x);
            }
        } catch (Exception ignored) { }
        return "selected_file";
    }

    private void message(String txt) { report.setText(txt); }

    private void extract() {
        if (busy) return;
        if (selected[0] == null || selected[1] == null || selected[2] == null) {
            message("Please select EBOOT.BIN, matching .rap and your own scetool keys file.");
            return;
        }
        busy = true;
        extract.setEnabled(false);
        save.setEnabled(false);
        verifiedElf = null;
        message("Reading selected files and validating headers...");
        new Thread(() -> {
            String outcome;
            File result = null;
            try {
                File work = new File(getFilesDir(), "work");
                if (!work.exists() && !work.mkdirs()) throw new IOException("Cannot create private directory");
                // Remove outputs from any earlier run. Never accept stale files as success.
                File bin = new File(work, "EBOOT.BIN");
                File keys = new File(work, "keys");
                File out = new File(work, "EBOOT.elf");
                if (out.exists() && !out.delete()) throw new IOException("Cannot remove old ELF");
                copy(selected[0], bin, MAX_EBOOT);
                if (bin.length() < 0x100) throw new IOException("SELF file is too small");
                byte[] head = readHeader(bin, 65536);
                if (head.length < 0x94 || head[0] != 'S' || head[1] != 'C' ||
                    head[2] != 'E' || head[3] != 0)
                    throw new IOException("Not a PS3 SCE SELF container");
                // Embedded big-endian ELF64/PowerPC signature at SCE SELF ELF offset.
                long embeddedOffset = be64(head, 0x30);
                if (embeddedOffset > head.length - 20 || embeddedOffset < 0)
                    throw new IOException("Embedded ELF header outside inspected SELF header range");
                int eo = (int) embeddedOffset;
                if (!elfHeader(head, eo))
                    throw new IOException("Embedded ELF header is not 64-bit big-endian PowerPC");
                Matcher m = CONTENT_ID.matcher(new String(head, StandardCharsets.ISO_8859_1));
                if (!m.find()) throw new IOException("Could not locate NPDRM content ID in SELF header");
                String contentId = m.group();
                String rapName = selectedNames[1];
                if (!rapName.equalsIgnoreCase(contentId + ".rap"))
                    throw new IOException("RAP name does not match EBOOT content ID " + contentId
                            + "\nSelected: " + rapName);
                File rap = new File(work, contentId + ".rap");
                copy(selected[1], rap, 32);
                if (rap.length() != 16)
                    throw new IOException("PS3 RAP must be exactly 16 bytes, got " + rap.length());
                copy(selected[2], keys, 1024L * 1024);
                if (keys.length() < 30) throw new IOException("Missing or empty scetool keyset");
                String keyPrefix = new String(readHeader(keys, 4096), StandardCharsets.ISO_8859_1);
                if (!keyPrefix.contains("[") || !keyPrefix.contains("]"))
                    throw new IOException("Keyset must be in scetool's text INI-style keys format");
                File binary = new File(getApplicationInfo().nativeLibraryDir, "libps3elf.so");
                if (!binary.canExecute()) throw new IOException("Native scetool executable missing: " + binary);
                ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(),
                        "--decrypt", bin.getAbsolutePath(), out.getAbsolutePath());
                pb.directory(work);
                pb.environment().put("PS3", work.getAbsolutePath());
                pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                ByteArrayOutputStream logBuffer = new ByteArrayOutputStream();
                Thread drain = new Thread(() -> {
                    try (InputStream input = proc.getInputStream()) {
                        byte[] buffer = new byte[2048];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (logBuffer.size() < 256 * 1024)
                                logBuffer.write(buffer, 0, Math.min(n, 256 * 1024 - logBuffer.size()));
                        }
                    } catch (IOException ignored) { }
                });
                drain.setDaemon(true);
                drain.start();
                boolean ended = proc.waitFor(90, TimeUnit.SECONDS);
                if (!ended) { proc.destroyForcibly(); throw new IOException("Decryption timed out after 90 seconds"); }
                drain.join(2000);
                String log = logBuffer.toString("UTF-8");
                if (proc.exitValue() != 0)
                    throw new IOException("scetool exit " + proc.exitValue() + "\n" + log);
                if (!out.isFile() || out.length() < 64)
                    throw new IOException("scetool generated no ELF. Check keyset and RAP.\n" + log);
                if (!elfHeader(readHeader(out, 64), 0))
                    throw new IOException("Output is not ELF64 big-endian PowerPC.\n" + log);
                // Not proof of full game compatibility, but rejects truncated or incorrectly formatted output.
                try (FileInputStream check = new FileInputStream(out)) {
                    byte[] elf = new byte[64];
                    if (check.read(elf) != 64) throw new IOException("Truncated ELF");
                    if (be64(elf, 32) == 0 || be16(elf, 56) == 0)
                        throw new IOException("ELF has no program headers");
                }
                result = out;
                outcome = "ELF extracted and header verified!\n"
                        + "NPDRM Content ID: " + contentId + "\n"
                        + "Output bytes: " + out.length() + "\n"
                        + "SHA-256: " + sha256(out) + "\n\n"
                        + "Engine log:\n" + log + "\n"
                        + "This does not by itself establish game compatibility.";
            } catch (Exception e) {
                outcome = "Extraction not completed.\n" + e.getMessage() +
                    "\n\nYour files remain on this device. Confirm that your keys file includes appropriate NPDRM and SELF keysets.";
            }
            File finalResult = result;
            String finalOutcome = outcome;
            runOnUiThread(() -> {
                busy = false;
                verifiedElf = finalResult;
                extract.setEnabled(true);
                save.setEnabled(finalResult != null);
                message(finalOutcome);
            });
        }, "elf-extractor-worker").start();
    }

    private static boolean elfHeader(byte[] data, int at) {
        return at >= 0 && at + 20 <= data.length &&
            (data[at] & 255) == 0x7f && data[at + 1] == 'E' &&
            data[at + 2] == 'L' && data[at + 3] == 'F' &&
            data[at + 4] == 2 && data[at + 5] == 2 && data[at + 6] == 1 &&
            be16(data, at + 18) == 21;
    }
    private static int be16(byte[] b, int p) {
        return ((b[p] & 255) << 8) | (b[p + 1] & 255);
    }
    private static long be64(byte[] b, int p) {
        long r = 0;
        for (int i = 0; i < 8; i++) r = (r << 8) | (b[p + i] & 255);
        return r;
    }
    private static byte[] readHeader(File file, int amount) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buf = new byte[amount];
            int filled = 0, n;
            while (filled < buf.length && (n = stream.read(buf, filled, buf.length - filled)) > 0)
                filled += n;
            if (filled == buf.length) return buf;
            byte[] small = new byte[filled];
            System.arraycopy(buf, 0, small, 0, filled);
            return small;
        }
    }
    private void copy(Uri from, File dest, long max) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(from);
             FileOutputStream out = new FileOutputStream(dest, false)) {
            if (in == null) throw new IOException("Input document not readable");
            transfer(in, out, max);
        }
    }
    private static void transfer(InputStream in, OutputStream out, long max) throws IOException {
        byte[] b = new byte[32768];
        long total = 0;
        int n;
        while ((n = in.read(b)) != -1) {
            total += n;
            if (total > max) throw new IOException("Input exceeds size limit");
            out.write(b, 0, n);
        }
    }
    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[32768]; int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    private void save() {
        if (verifiedElf == null || !verifiedElf.isFile()) { message("No verified ELF available"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/octet-stream");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE, "EBOOT.elf");
        startActivityForResult(i, SAVE_ELF);
    }
}
