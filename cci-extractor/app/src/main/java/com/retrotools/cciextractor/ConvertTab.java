package com.retrotools.cciextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** The "Convert" tab: 3DS container conversion and batch texture conversion. */
final class ConvertTab {
    static final int PICK_CIA = 401, SAVE_CCI = 402, PICK_CCI = 403, SAVE_CIA = 404, PICK_CERT_CIA = 405,
            PICK_TEXTURES = 406, PICK_TEXTURE_DIR = 407, PICK_CERT_FILE = 408;

    private static final String[] OUTPUTS = {"PNG", "DDS · RGBA8 (uncompressed)", "DDS · BC1 / DXT1", "DDS · BC3 / DXT5",
            "KTX · RGBA8", "KTX · ETC1", "DS NCGR + NCLR · 4bpp (16 colours)", "DS NCGR + NCLR · 8bpp (256 colours)"};
    private static final String[] RESIZES = {"Keep size", "Half size", "2× (sharp pixels)", "4× (sharp pixels)",
            "2× (smooth)", "Pad up to power of two"};

    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Uri> textureUris = new ArrayList<>();
    private Uri sourceUri;
    private int textureOutput, textureResize;
    private boolean busy;
    private TextView status, certStatus, log;
    private Button ciaToCci, cciToCia, saveCert, textures, cancel;
    final View view;

    ConvertTab(Activity activity) {
        this.activity = activity;
        this.view = build();
        refresh();
    }

    private int dp(int n) { return (int) (n * activity.getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String s, int size, int color) {
        TextView v = new TextView(activity);
        v.setText(s); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(dp(14), dp(6), dp(14), dp(6));
        return v;
    }
    private Button button(String s) { Button b = new Button(activity); b.setText(s); b.setAllCaps(false); return b; }

    private View build() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(16));
        TextView h1 = text("3DS containers", 17, Color.rgb(35, 45, 91));
        box.addView(h1);
        box.addView(text("Decrypted CIAs are repackaged without changing their contents. Encrypted CIAs are " +
                "decrypted on this device first (games and demos only) and saved as a decrypted CCI for emulators.",
                12, Color.DKGRAY));
        ciaToCci = button("CIA → CCI (.3ds)");
        cciToCia = button("CCI (.3ds) → CIA");
        saveCert = button("Save certificate chain from a CIA");
        box.addView(ciaToCci, new LinearLayout.LayoutParams(-1, dp(52)));
        box.addView(cciToCia, new LinearLayout.LayoutParams(-1, dp(52)));
        box.addView(saveCert, new LinearLayout.LayoutParams(-1, dp(48)));
        certStatus = text("", 12, Color.DKGRAY);
        box.addView(certStatus);

        box.addView(text("Textures", 17, Color.rgb(35, 45, 91)));
        box.addView(text("Batch convert PNG/JPG, DDS (uncompressed, BC1–BC5), KTX (RGBA8/RGB8/ETC1), 3DS BTEX " +
                "and DS NCGR (picks a selected .nclr as its palette) into another format, with optional resizing.",
                12, Color.DKGRAY));
        textures = button("Convert textures…");
        box.addView(textures, new LinearLayout.LayoutParams(-1, dp(52)));

        cancel = button("Cancel");
        box.addView(cancel, new LinearLayout.LayoutParams(-1, dp(46)));
        status = text("Ready.", 14, Color.rgb(36, 49, 105));
        status.setBackgroundColor(Color.rgb(225, 231, 248));
        box.addView(status);
        log = text("", 12, Color.DKGRAY);
        box.addView(log);

        ciaToCci.setOnClickListener(v -> pick(PICK_CIA, "*/*", false));
        cciToCia.setOnClickListener(v -> {
            if (loadCertChain() == null) {
                new AlertDialog.Builder(activity).setTitle("Certificate chain needed")
                        .setMessage("A CIA must contain the standard 3DS certificate chain. It isn't bundled with " +
                                "the app, but can be copied from any CIA you own (encrypted ones work too), or loaded " +
                                "from a 2560-byte cert chain file.")
                        .setPositiveButton("Pick a CIA", (d, w) -> pick(PICK_CERT_CIA, "*/*", false))
                        .setNeutralButton("Pick cert file", (d, w) -> pick(PICK_CERT_FILE, "*/*", false))
                        .setNegativeButton("Cancel", null).show();
                return;
            }
            pick(PICK_CCI, "*/*", false);
        });
        saveCert.setOnClickListener(v -> pick(PICK_CERT_CIA, "*/*", false));
        textures.setOnClickListener(v -> pick(PICK_TEXTURES, "*/*", true));
        cancel.setOnClickListener(v -> { cancelled.set(true); NativeCia.cancel(); status.setText("Cancelling…"); });

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        return scroll;
    }

    private void refresh() {
        ciaToCci.setEnabled(!busy); cciToCia.setEnabled(!busy); saveCert.setEnabled(!busy); textures.setEnabled(!busy);
        cancel.setEnabled(busy);
        certStatus.setText(loadCertChain() != null ? "Certificate chain: saved ✓ (CCI → CIA is ready)"
                : "Certificate chain: not saved yet (needed for CCI → CIA)");
    }

    private void pick(int request, String type, boolean multiple) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivityForResult(intent, request);
    }

    private void save(int request, String name) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        activity.startActivityForResult(intent, request);
    }

    private String filename(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n; }
        } catch (Exception ignored) { }
        String s = uri.getLastPathSegment();
        return s == null ? "file" : s.substring(s.lastIndexOf('/') + 1);
    }
    private static String stem(String name) { int d = name.lastIndexOf('.'); return d > 0 ? name.substring(0, d) : name; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    // ---------------------------------------------------------------- results
    boolean handles(int request) { return request >= 401 && request <= 408; }

    void onResult(int request, Intent data) {
        if (request == PICK_TEXTURES) {
            textureUris.clear();
            ClipData clip = data.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) textureUris.add(clip.getItemAt(i).getUri());
            else if (data.getData() != null) textureUris.add(data.getData());
            if (textureUris.isEmpty()) return;
            new AlertDialog.Builder(activity).setTitle("Convert " + textureUris.size() + " file(s) to")
                    .setItems(OUTPUTS, (d, which) -> {
                        textureOutput = which;
                        new AlertDialog.Builder(activity).setTitle("Resize")
                                .setItems(RESIZES, (d2, r) -> {
                                    textureResize = r;
                                    Intent tree = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                    tree.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                                    activity.startActivityForResult(tree, PICK_TEXTURE_DIR);
                                }).show();
                    }).show();
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;
        switch (request) {
            case PICK_CIA: sourceUri = uri; save(SAVE_CCI, stem(filename(uri)) + ".3ds"); break;
            case SAVE_CCI: runCiaToCci(sourceUri, uri); break;
            case PICK_CCI: sourceUri = uri; save(SAVE_CIA, stem(filename(uri)) + ".cia"); break;
            case SAVE_CIA: runCciToCia(sourceUri, uri); break;
            case PICK_CERT_CIA: saveCertFromCia(uri); break;
            case PICK_CERT_FILE: saveCertFile(uri); break;
            case PICK_TEXTURE_DIR: runTextures(uri); break;
        }
    }

    // ---------------------------------------------------------------- certificate chain (kept privately)
    private File certFile() { return new File(activity.getFilesDir(), "3ds_cert_chain.bin"); }

    private byte[] loadCertChain() {
        try {
            File f = certFile();
            if (!f.isFile()) return null;
            byte[] c = Files.readAllBytes(f.toPath());
            return CiaConverter.looksLikeCertChain(c) ? c : null;
        } catch (IOException e) { return null; }
    }

    private void storeCertChain(byte[] chain) throws IOException {
        if (!CiaConverter.looksLikeCertChain(chain)) throw new IOException("That doesn't look like a 3DS certificate chain");
        Files.write(certFile().toPath(), chain);
    }

    private void saveCertFromCia(Uri uri) {
        try (ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(uri, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor()); FileChannel ch = in.getChannel()) {
            storeCertChain(CiaConverter.certChainFromCia(ch));
            refresh();
            status.setText("Certificate chain saved from " + filename(uri));
        } catch (Exception e) { error("Couldn't read certificate chain", e); }
    }

    private void saveCertFile(Uri uri) {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open file");
            byte[] data = readAll(in, 8192);
            storeCertChain(data);
            refresh();
            status.setText("Certificate chain loaded from " + filename(uri));
        } catch (Exception e) { error("Couldn't load certificate chain", e); }
    }

    // ---------------------------------------------------------------- conversions
    private interface Job { String run(FileChannel source, OutputStream out) throws Exception; }

    private void runContainer(String label, Uri source, Uri dest, Job job) {
        busy = true; cancelled.set(false); refresh();
        status.setText(label + "…"); log.setText("");
        worker.execute(() -> {
            try (ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(source, "r")) {
                if (pfd == null) throw new IOException("Cannot open source file");
                String summary;
                try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor()); FileChannel ch = in.getChannel();
                     OutputStream out = activity.getContentResolver().openOutputStream(dest, "w")) {
                    if (out == null) throw new IOException("Cannot write output file");
                    summary = job.run(ch, new java.io.BufferedOutputStream(out, 1 << 20) {
                        @Override public void close() throws IOException { flush(); }
                    });
                    out.flush();
                }
                activity.runOnUiThread(() -> { busy = false; refresh(); status.setText(label + " done"); log.setText(summary); });
            } catch (Exception e) {
                try { DocumentsContract.deleteDocument(activity.getContentResolver(), dest); } catch (Exception ignored) { }
                activity.runOnUiThread(() -> { busy = false; refresh(); error(label + " failed", e); });
            }
        });
    }

    private CiaConverter.Progress progress() {
        final long[] last = {0};
        return (done, total) -> {
            long now = System.currentTimeMillis();
            if (now - last[0] < 250) return;
            last[0] = now;
            int pct = total <= 0 ? 0 : (int) (done * 100 / total);
            activity.runOnUiThread(() -> status.setText("Working… " + pct + "%"));
        };
    }

    /** Encrypted CIAs (title-key or NCCH level) need the native engine; decrypted ones are repackaged in Java. */
    private boolean needsEngine(Uri source) {
        try (ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(source, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor()); FileChannel ch = in.getChannel()) {
            if (!CiaConverter.isCia(ch)) return false;      // let the Java path report "not a CIA"
            try { return CiaConverter.needsDecryption(ch); }
            catch (IOException unusualLayout) { return true; } // e.g. DLC with many contents: the engine explains why
        } catch (Exception e) { return false; }
    }

    /** Encrypted CIA → decrypted CCI through the bundled ctrtool/makerom engine. */
    private void runEncryptedCiaToCci(Uri source, Uri dest) {
        final String label = "CIA → CCI (decrypting)";
        final String name = filename(source);
        busy = true; cancelled.set(false); refresh();
        status.setText(label + "…"); log.setText("");
        worker.execute(() -> {
            final List<String> errors = new ArrayList<>();
            final String[] titleId = {null};
            try (ParcelFileDescriptor in = activity.getContentResolver().openFileDescriptor(source, "r");
                 ParcelFileDescriptor out = activity.getContentResolver().openFileDescriptor(dest, "rwt")) {
                if (in == null) throw new IOException("Cannot open source file");
                if (out == null) throw new IOException("Cannot write output file");
                int rc = NativeCia.ciaToCci(activity, in.getFd(), out.getFd(), name,
                        (pct, msg) -> activity.runOnUiThread(() -> { if (busy) status.setText(msg + "… " + pct + "%"); }),
                        line -> {
                            String t = line.trim();
                            if (t.startsWith("META: title_id=") && t.length() > 15) titleId[0] = t.substring(15).toUpperCase(Locale.ROOT);
                            if (t.startsWith("ERR:") && errors.size() < 4) errors.add(t.substring(4).trim());
                        });
                if (rc != 0) {
                    if (rc == NativeCia.RC_CANCELLED || cancelled.get()) throw new IOException("Cancelled");
                    String detail = errors.isEmpty() ? "" : "\n\nDetails: " + String.join(" ", errors);
                    throw new IOException(NativeCia.describe(rc) + (rc == NativeCia.RC_NOT_CONVERTIBLE ? "" : detail));
                }
                long bytes = out.getStatSize();
                String cert = "";
                if (loadCertChain() == null) {
                    try (FileInputStream fin = new FileInputStream(in.getFileDescriptor()); FileChannel ch = fin.getChannel()) {
                        storeCertChain(CiaConverter.certChainFromCia(ch));
                        cert = "\nCertificate chain saved for CCI → CIA.";
                    } catch (IOException ignored) { }
                }
                final String summary = (titleId[0] == null ? "" : "Title " + titleId[0] + " · ") +
                        (bytes > 0 ? (bytes >> 20) + " MB · " : "") + "decrypted on this device" + cert;
                activity.runOnUiThread(() -> { busy = false; refresh(); status.setText("CIA → CCI done"); log.setText(summary); });
            } catch (Exception e) {
                try { DocumentsContract.deleteDocument(activity.getContentResolver(), dest); } catch (Exception ignored) { }
                activity.runOnUiThread(() -> { busy = false; refresh(); error("CIA → CCI failed", e); });
            }
        });
    }

    private void runCiaToCci(Uri source, Uri dest) {
        if (needsEngine(source)) { runEncryptedCiaToCci(source, dest); return; }
        runContainer("CIA → CCI", source, dest, (ch, out) -> {
            CiaConverter.Result r = CiaConverter.ciaToCci(ch, out, progress(), cancelled);
            out.flush();
            String cert = "";
            if (r.certChain != null && loadCertChain() == null) {
                try { storeCertChain(r.certChain); cert = "\nCertificate chain saved for CCI → CIA."; } catch (IOException ignored) { }
                activity.runOnUiThread(this::refresh);
            }
            return "Title " + r.titleId + " · " + r.contents + " partition(s) · " + (r.bytes >> 20) + " MB" + cert +
                    (r.notes.isEmpty() ? "" : "\n• " + String.join("\n• ", r.notes));
        });
    }

    private void runCciToCia(Uri source, Uri dest) {
        byte[] chain = loadCertChain();
        runContainer("CCI → CIA", source, dest, (ch, out) -> {
            CiaConverter.Result r = CiaConverter.cciToCia(ch, out, chain, progress(), cancelled);
            out.flush();
            return "Title " + r.titleId + " · " + r.contents + " content(s) · " + (r.bytes >> 20) + " MB" +
                    (r.notes.isEmpty() ? "" : "\n• " + String.join("\n• ", r.notes));
        });
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > max) throw new IOException("File is too large (limit " + (max >> 20) + " MB)");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private TextureFormats.Image decodeAny(byte[] data, String name, byte[] palette) throws IOException {
        switch (TextureFormats.detect(data)) {
            case DDS: return TextureFormats.decodeDds(data);
            case KTX: return TextureFormats.decodeKtx(data);
            case BTEX: { BtexCodec.Image b = BtexCodec.decode(data); return new TextureFormats.Image(b.width, b.height, b.argb); }
            case NCGR: {
                byte[] nclr = palette != null ? palette : NdsGraphics.grayNclr(NdsGraphics.layout(data).depth);
                NdsGraphics.Image n = NdsGraphics.decode(data, nclr, 0);
                return new TextureFormats.Image(n.width, n.height, n.argb);
            }
            case NCLR: throw new IOException("palette file (used for NCGRs in the same batch)");
            default: {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false; o.inPremultiplied = false; o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bmp == null) throw new IOException("unrecognised image format");
                try {
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    if ((long) w * h > TextureFormats.MAX_PIXELS) throw new IOException("image too large");
                    int[] px = new int[w * h];
                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                    return new TextureFormats.Image(w, h, px);
                } finally { bmp.recycle(); }
            }
        }
    }

    private void writeFile(Uri dir, String name, String mime, byte[] data) throws IOException {
        Uri file = DocumentsContract.createDocument(activity.getContentResolver(), dir, mime, name);
        if (file == null) throw new IOException("Cannot create " + name);
        try (OutputStream out = activity.getContentResolver().openOutputStream(file, "w")) {
            if (out == null) throw new IOException("Cannot write " + name);
            out.write(data);
        }
    }

    private void runTextures(Uri tree) {
        final List<Uri> inputs = new ArrayList<>(textureUris);
        final int output = textureOutput, resize = textureResize;
        busy = true; cancelled.set(false); refresh();
        status.setText("Converting " + inputs.size() + " texture(s)…"); log.setText("");
        worker.execute(() -> {
            StringBuilder report = new StringBuilder();
            int ok = 0, failed = 0;
            try {
                Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                Uri folder = DocumentsContract.createDocument(activity.getContentResolver(), root,
                        DocumentsContract.Document.MIME_TYPE_DIR, "Converted_" + stamp);
                if (folder == null) throw new IOException("Cannot create output folder");
                // First pass: find a palette for any NCGRs.
                byte[] palette = null;
                List<byte[]> blobs = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (Uri u : inputs) {
                    String name = filename(u);
                    try (InputStream in = activity.getContentResolver().openInputStream(u)) {
                        if (in == null) throw new IOException("cannot open");
                        byte[] data = readAll(in, 64 * 1024 * 1024);
                        if (TextureFormats.detect(data) == TextureFormats.Kind.NCLR && palette == null) palette = data;
                        blobs.add(data); names.add(name);
                    } catch (Exception e) { blobs.add(null); names.add(name); report.append("✗ ").append(name).append(": ").append(message(e)).append('\n'); failed++; }
                }
                for (int i = 0; i < blobs.size(); i++) {
                    if (cancelled.get()) { report.append("Cancelled.\n"); break; }
                    byte[] data = blobs.get(i);
                    String name = names.get(i);
                    if (data == null) continue;
                    final int at = i + 1;
                    activity.runOnUiThread(() -> status.setText("Converting " + at + "/" + blobs.size() + "…"));
                    try {
                        if (TextureFormats.detect(data) == TextureFormats.Kind.NCLR) continue;
                        TextureFormats.Image img = decodeAny(data, name, palette);
                        switch (resize) {
                            case 1: img = TextureFormats.resize(img, TextureFormats.Resize.HALF, true); break;
                            case 2: img = TextureFormats.resize(img, TextureFormats.Resize.DOUBLE, false); break;
                            case 3: img = TextureFormats.resize(img, TextureFormats.Resize.QUADRUPLE, false); break;
                            case 4: img = TextureFormats.resize(img, TextureFormats.Resize.DOUBLE, true); break;
                            case 5: img = TextureFormats.resize(img, TextureFormats.Resize.POWER_OF_TWO, true); break;
                            default: break;
                        }
                        String base = stem(name);
                        String made;
                        switch (output) {
                            case 0: {
                                Bitmap bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888);
                                bmp.setPixels(img.argb, 0, img.width, 0, 0, img.width, img.height);
                                ByteArrayOutputStream png = new ByteArrayOutputStream();
                                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, png)) throw new IOException("PNG encoding failed");
                                bmp.recycle();
                                writeFile(folder, base + ".png", "image/png", png.toByteArray());
                                made = base + ".png"; break;
                            }
                            case 1: case 2: case 3: {
                                TextureFormats.DdsFormat f = output == 1 ? TextureFormats.DdsFormat.RGBA8 :
                                        output == 2 ? TextureFormats.DdsFormat.BC1 : TextureFormats.DdsFormat.BC3;
                                writeFile(folder, base + ".dds", "application/octet-stream", TextureFormats.encodeDds(img, f));
                                made = base + ".dds"; break;
                            }
                            case 4: case 5: {
                                TextureFormats.KtxFormat f = output == 4 ? TextureFormats.KtxFormat.RGBA8 : TextureFormats.KtxFormat.ETC1;
                                writeFile(folder, base + ".ktx", "application/octet-stream", TextureFormats.encodeKtx(img, f));
                                made = base + ".ktx"; break;
                            }
                            default: {
                                TextureFormats.NitroPair p = TextureFormats.toNitro(img, output == 7);
                                writeFile(folder, base + ".ncgr", "application/octet-stream", p.ncgr);
                                writeFile(folder, base + ".nclr", "application/octet-stream", p.nclr);
                                made = base + ".ncgr + .nclr (" + p.colours + " colours used)";
                            }
                        }
                        report.append("✓ ").append(name).append(" → ").append(made)
                                .append(" (").append(img.width).append('×').append(img.height).append(")\n");
                        ok++;
                    } catch (Exception e) {
                        report.append("✗ ").append(name).append(": ").append(message(e)).append('\n');
                        failed++;
                    }
                }
                final int good = ok, bad = failed;
                activity.runOnUiThread(() -> {
                    busy = false; refresh();
                    status.setText("Textures: " + good + " converted" + (bad > 0 ? ", " + bad + " failed" : "") +
                            " · saved in Converted_" + stamp);
                    log.setText(report.toString());
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> { busy = false; refresh(); error("Texture conversion failed", e); });
            }
        });
    }

    private void error(String title, Exception e) {
        status.setText(title);
        log.setText(message(e));
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message(e)).setPositiveButton("OK", null).show();
    }
}
