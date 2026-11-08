package com.retrotools.cciextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal Android UI: browse decrypted NCSD images, inspect their files, extract one or all. */
public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 301;
    private static final int PICK_DIRECTORY = 302;
    private static final int PICK_PNG = 303;
    private static final int PICK_BTEX_TEMPLATE = 304;
    private static final int SAVE_BTEX = 305;
    private static final String DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Uri imageUri;
    private Uri pngUri, templateUri;
    private CciArchive.Info archive;
    private List<CciArchive.Entry> pending;
    private final List<String> displayed = new ArrayList<>();
    private String activePath = "";
    private boolean busy = false;
    private TextView headline, summary, location, detail;
    private Button open, extractAll, extractHere, up, stop, pngToBtex;
    private ListView list;
    private ArrayAdapter<String> adapter;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(24, 32, 60));
        getWindow().setNavigationBarColor(Color.rgb(24, 32, 60));
        buildUi();
        render();
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(dp(14), dp(8), dp(14), dp(8));
        return v;
    }
    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false);
        return b;
    }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        setContentView(root);

        headline = text("CCI Extractor + Texture Tools", 22, Color.WHITE);
        headline.setBackgroundColor(Color.rgb(35, 45, 91));
        headline.setPadding(dp(18), dp(16), dp(14), dp(16));
        root.addView(headline);
        summary = text("Extract a decrypted CCI. BTEX textures automatically become editable PNGs.", 14, Color.DKGRAY);
        root.addView(summary);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(6), 0, dp(6), 0);
        root.addView(controls);
        open = button("Open CCI");
        extractAll = button("Extract all");
        controls.addView(open, new LinearLayout.LayoutParams(0, dp(54), 1));
        controls.addView(extractAll, new LinearLayout.LayoutParams(0, dp(54), 1));
        LinearLayout controls2 = new LinearLayout(this);
        controls2.setPadding(dp(6), 0, dp(6), 0);
        root.addView(controls2);
        up = button("Up");
        extractHere = button("Extract folder");
        stop = button("Cancel");
        controls2.addView(up, new LinearLayout.LayoutParams(0, dp(48), 0.75f));
        controls2.addView(extractHere, new LinearLayout.LayoutParams(0, dp(48), 1.6f));
        controls2.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 0.85f));
        pngToBtex = button("PNG → BTEX (choose original BTEX template)");
        root.addView(pngToBtex, new LinearLayout.LayoutParams(-1, dp(52)));
        location = text("/", 14, Color.rgb(36, 49, 105));
        location.setBackgroundColor(Color.rgb(225, 231, 248));
        root.addView(location);
        list = new ListView(this);
        list.setDividerHeight(dp(1));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayed);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        detail = text("Read-only source. Files are copied to a folder you choose.", 12, Color.DKGRAY);
        detail.setMaxLines(4);
        root.addView(detail);

        open.setOnClickListener(v -> openCci());
        pngToBtex.setOnClickListener(v -> choosePng());
        extractAll.setOnClickListener(v -> beginExtract(archive == null ? null : archive.entries));
        extractHere.setOnClickListener(v -> beginExtract(entriesIn(activePath)));
        up.setOnClickListener(v -> {
            if (!activePath.isEmpty()) {
                int index = activePath.lastIndexOf('/');
                activePath = index < 0 ? "" : activePath.substring(0, index);
                render();
            }
        });
        stop.setOnClickListener(v -> { cancelled.set(true); detail.setText("Cancelling after the current file..."); });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (archive == null || busy || position < 0 || position >= displayed.size()) return;
            String row = displayed.get(position);
            if (row.startsWith("📁 ")) {
                String folder = row.substring(3);
                activePath = activePath.isEmpty() ? folder : activePath + "/" + folder;
                render();
            } else if (row.startsWith("📄 ")) {
                String filename = row.substring(3);
                String full = activePath.isEmpty() ? filename : activePath + "/" + filename;
                for (CciArchive.Entry entry : archive.entries) {
                    if (entry.path.equals(full)) {
                        new AlertDialog.Builder(this).setTitle(filename)
                                .setMessage("Size: " + human(entry.size) + "\n\nExtract this file?")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Extract", (d, w) -> beginExtract(Collections.singletonList(entry)))
                                .show();
                        return;
                    }
                }
            }
        });
    }

    private String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.UK, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.UK, "%.1f MB", bytes / 1048576.0);
    }

    private void openCci() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_IMAGE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_IMAGE) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            imageUri = uri;
            archive = null;
            activePath = "";
            busy = true;
            render();
            summary.setText("Scanning game image...");
            worker.execute(() -> {
                try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r")) {
                    if (descriptor == null) throw new IOException("Cannot access this file");
                    try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
                         FileChannel channel = stream.getChannel()) {
                        CciArchive.Info resultInfo = CciArchive.parse(channel);
                        runOnUiThread(() -> {
                            archive = resultInfo;
                            busy = false;
                            summary.setText("Found " + archive.entries.size() + " files across " +
                                    archive.partitions + " NCCH partition(s).");
                            detail.setText(resultInfo.notes.isEmpty() ?
                                    "Tap a folder to browse or a file to extract." :
                                    "Scan notes: " + String.join("; ", resultInfo.notes));
                            render();
                        });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        busy = false; archive = null;
                        summary.setText("Could not open image");
                        detail.setText(message(e));
                        render();
                        new AlertDialog.Builder(this).setTitle("CCI scan failed")
                                .setMessage(message(e)).setPositiveButton("OK", null).show();
                    });
                }
            });
        } else if (request == PICK_DIRECTORY && pending != null) {
            List<CciArchive.Entry> selection = pending;
            pending = null;
            extract(selection, uri);
        } else if (request == PICK_PNG) {
            pngUri = uri;
            chooseBtexTemplate();
        } else if (request == PICK_BTEX_TEMPLATE) {
            templateUri = uri;
            chooseOutputBtex();
        } else if (request == SAVE_BTEX && pngUri != null && templateUri != null) {
            encodeSelectedPng(uri);
        }
    }

    private String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private List<CciArchive.Entry> entriesIn(String prefix) {
        if (archive == null) return null;
        List<CciArchive.Entry> result = new ArrayList<>();
        String path = prefix.isEmpty() ? "" : prefix + "/";
        for (CciArchive.Entry e : archive.entries) if (e.path.startsWith(path)) result.add(e);
        return result;
    }

    private void beginExtract(List<CciArchive.Entry> selection) {
        if (busy || selection == null || selection.isEmpty()) return;
        pending = new ArrayList<>(selection);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, PICK_DIRECTORY);
    }

    private void render() {
        displayed.clear();
        if (archive != null) {
            String prefix = activePath.isEmpty() ? "" : activePath + "/";
            Set<String> directories = new TreeSet<>();
            Set<String> files = new TreeSet<>();
            for (CciArchive.Entry e : archive.entries) {
                if (!e.path.startsWith(prefix)) continue;
                String remainder = e.path.substring(prefix.length());
                int slash = remainder.indexOf('/');
                if (slash >= 0) directories.add(remainder.substring(0, slash));
                else files.add(remainder);
            }
            for (String dir : directories) displayed.add("📁 " + dir);
            for (String file : files) displayed.add("📄 " + file);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        location.setText("/" + activePath + "   (" + displayed.size() + " items)");
        open.setEnabled(!busy);
        pngToBtex.setEnabled(!busy);
        extractAll.setEnabled(!busy && archive != null);
        extractHere.setEnabled(!busy && archive != null && !entriesIn(activePath).isEmpty());
        up.setEnabled(!busy && !activePath.isEmpty());
        stop.setEnabled(busy);
    }

    /** Always preserve the .btex template alongside the PNG, for safe reversible edits. */
    private void extract(List<CciArchive.Entry> selection, Uri tree) {
        if (busy) return;
        cancelled.set(false);
        busy = true;
        render();
        summary.setText("Extracting " + selection.size() + " files, converting BTEX textures...");
        worker.execute(() -> {
            int count = 0, converted = 0, failed = 0;
            long written = 0;
            try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(imageUri, "r")) {
                if (descriptor == null) throw new IOException("Unable to reopen selected CCI");
                try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
                     FileChannel channel = stream.getChannel()) {
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                            DocumentsContract.getTreeDocumentId(tree));
                    Uri destination = DocumentsContract.createDocument(getContentResolver(), parent,
                            DIR_MIME, "CCI_Extract_" + stamp);
                    if (destination == null) throw new IOException("Unable to create extraction folder");
                    Map<String, Uri> folders = new HashMap<>();
                    folders.put("", destination);
                    byte[] buffer = new byte[131072];
                    for (CciArchive.Entry e : selection) {
                        if (cancelled.get()) break;
                        int index = e.path.lastIndexOf('/');
                        String path = e.path.substring(0, index);
                        String filename = e.path.substring(index + 1);
                        Uri dir = ensureFolder(path, folders);
                        // Preserve the original BTEX file, which the PNG -> BTEX feature uses as a template.
                        Uri target = DocumentsContract.createDocument(getContentResolver(), dir,
                                "application/octet-stream", filename);
                        if (target == null) throw new IOException("Unable to create " + e.path);
                        try (OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                            if (out == null) throw new IOException("Cannot write " + e.path);
                            long position = e.offset, remain = e.size;
                            while (remain > 0) {
                                if (cancelled.get()) break;
                                int desired = (int) Math.min(buffer.length, remain);
                                ByteBuffer b = ByteBuffer.wrap(buffer, 0, desired);
                                int read = channel.read(b, position);
                                if (read <= 0) throw new IOException("Unexpected EOF at " + e.path);
                                out.write(buffer, 0, read);
                                position += read; remain -= read;
                                written += read;
                            }
                            if (remain > 0) {
                                DocumentsContract.deleteDocument(getContentResolver(), target);
                                break;
                            }
                        }
                        if (!cancelled.get() && filename.toLowerCase(Locale.ROOT).endsWith(".btex")) {
                            try {
                                if (e.size > BtexCodec.MAX_FILE_BYTES)
                                    throw new IOException("BTEX is too large for conversion");
                                byte[] raw = readBtex(channel, e.offset, (int) e.size);
                                BtexCodec.Image im = BtexCodec.decode(raw);
                                Bitmap bitmap = Bitmap.createBitmap(im.width, im.height, Bitmap.Config.ARGB_8888);
                                bitmap.setPremultiplied(false);
                                bitmap.setPixels(im.argb, 0, im.width, 0, 0, im.width, im.height);
                                String pngName = filename.substring(0, filename.length() - 5) + ".png";
                                Uri png = DocumentsContract.createDocument(getContentResolver(), dir, "image/png", pngName);
                                if (png == null) throw new IOException("Cannot create " + pngName);
                                try (OutputStream pngOut = getContentResolver().openOutputStream(png, "w")) {
                                    if (pngOut == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut))
                                        throw new IOException("Failed to save PNG " + pngName);
                                } catch (Exception error) {
                                    DocumentsContract.deleteDocument(getContentResolver(), png);
                                    throw error;
                                } finally { bitmap.recycle(); }
                                converted++;
                            } catch (Exception conversionError) {
                                // Never abort an entire extraction for one unsupported/broken texture.
                                failed++;
                            }
                        }
                        count++;
                        final int done = count;
                        final int pngDone = converted;
                        final String lastName = filename;
                        if (count % 5 == 0 || count == selection.size()) {
                            runOnUiThread(() -> detail.setText(done + "/" + selection.size() +
                                    " files · " + pngDone + " PNGs · " + lastName));
                        }
                    }
                }
                final int complete = count, pngs = converted, skipped = failed;
                final long bytes = written;
                final boolean interrupted = cancelled.get();
                runOnUiThread(() -> {
                    busy = false;
                    summary.setText((interrupted ? "Stopped: " : "Finished: ") + complete +
                            " files (" + human(bytes) + ") · " + pngs + " textures → PNG");
                    detail.setText("Original BTEX templates are retained beside each PNG." +
                            (skipped > 0 ? " " + skipped + " textures could not be converted." : ""));
                    render();
                    new AlertDialog.Builder(this).setTitle(interrupted ? "Extraction cancelled" : "Extraction complete")
                            .setMessage(complete + " original file(s) extracted. " + pngs +
                                    " BTEX texture(s) converted to PNG." + (skipped > 0 ?
                                    " " + skipped + " texture(s) could not be converted; originals preserved." : "") +
                                    "\n\nOriginal CCI was not modified.")
                            .setPositiveButton("OK", null).show();
                });
            } catch (Exception e) {
                final int copied = count;
                runOnUiThread(() -> {
                    busy = false; render();
                    summary.setText("Extraction stopped after " + copied + " files");
                    detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("Extraction failed")
                            .setMessage(message(e) + "\n\nAlready extracted files remain in the chosen folder.")
                            .setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private static byte[] readBtex(FileChannel channel, long start, int size) throws IOException {
        if (size < 0 || size > BtexCodec.MAX_FILE_BYTES) throw new IOException("BTEX too large");
        ByteBuffer b = ByteBuffer.allocate(size);
        while (b.hasRemaining()) {
            int n = channel.read(b, start + b.position());
            if (n <= 0) throw new IOException("Truncated BTEX");
        }
        return b.array();
    }

    private void choosePng() {
        if (busy) return;
        pngUri = null;
        templateUri = null;
        new AlertDialog.Builder(this).setTitle("PNG → BTEX")
                .setMessage("Choose an edited PNG, then its matching original .btex file from the extraction. " +
                        "The template preserves game-specific dimensions and texture format.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose PNG", (d,w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/png");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, PICK_PNG);
                }).show();
    }

    private void chooseBtexTemplate() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_BTEX_TEMPLATE);
    }

    private String documentName(Uri uri, String fallback) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private void chooseOutputBtex() {
        String suggested = documentName(templateUri, "texture.btex");
        if (!suggested.toLowerCase(Locale.ROOT).endsWith(".btex")) suggested += ".btex";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, suggested);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, SAVE_BTEX);
    }

    private static byte[] readBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[32768];
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (n == 0) continue;
            if (buffer.size() > maxBytes - n) throw new IOException("Original BTEX is too large");
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private void encodeSelectedPng(Uri destination) {
        if (busy) return;
        Uri png = pngUri, template = templateUri;
        busy = true;
        cancelled.set(false);
        render();
        summary.setText("Converting PNG back to BTEX...");
        worker.execute(() -> {
            try {
                byte[] original;
                try (InputStream in = getContentResolver().openInputStream(template)) {
                    if (in == null) throw new IOException("Cannot open original BTEX");
                    original = readBytes(in, BtexCodec.MAX_FILE_BYTES);
                }
                BtexCodec.Header header = BtexCodec.inspect(original);
                if (cancelled.get()) throw new IOException("Cancelled");
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(png)) {
                    if (in == null) throw new IOException("Cannot open PNG");
                    BitmapFactory.decodeStream(in, null, opts);
                }
                if (opts.outWidth != header.width || opts.outHeight != header.height)
                    throw new IOException("PNG is " + opts.outWidth + "x" + opts.outHeight +
                            ", but BTEX template is " + header.width + "x" + header.height +
                            ". Resize the PNG to match exactly.");
                opts.inJustDecodeBounds = false;
                opts.inPremultiplied = false;
                Bitmap image;
                try (InputStream in = getContentResolver().openInputStream(png)) {
                    if (in == null) throw new IOException("Cannot reopen PNG");
                    image = BitmapFactory.decodeStream(in, null, opts);
                }
                if (image == null) throw new IOException("Could not decode PNG image");
                int[] pixels = new int[header.width * header.height];
                try { image.getPixels(pixels, 0, header.width, 0, 0, header.width, header.height); }
                finally { image.recycle(); }
                if (cancelled.get()) throw new IOException("Cancelled");
                byte[] out = BtexCodec.encode(pixels, header.width, header.height, original);
                if (cancelled.get()) throw new IOException("Cancelled");
                try (OutputStream stream = getContentResolver().openOutputStream(destination, "w")) {
                    if (stream == null) throw new IOException("Cannot write output BTEX");
                    stream.write(out);
                }
                runOnUiThread(() -> {
                    busy = false; render();
                    summary.setText("BTEX saved successfully");
                    detail.setText("Output preserves original BTEX header, size and format " + header.format +
                            ". Compressed ETC1/ETC1A4 is re-encoded with lossy compression.");
                    new AlertDialog.Builder(this).setTitle("BTEX created")
                            .setMessage("Your modified .btex is ready. To test it in Azahar, replace the corresponding " +
                                    "romfs file in the game's mod directory. Original game file was not changed.")
                            .setPositiveButton("OK", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false; render();
                    summary.setText("PNG conversion failed");
                    detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("Could not create BTEX")
                            .setMessage(message(e) + "\n\nChoose a PNG and the original matching .btex file.")
                            .setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private Uri ensureFolder(String path, Map<String, Uri> cache) throws Exception {
        if (cache.containsKey(path)) return cache.get(path);
        int slash = path.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : path.substring(0, slash);
        Uri parent = ensureFolder(parentPath, cache);
        String name = path.substring(slash + 1);
        Uri created = DocumentsContract.createDocument(getContentResolver(), parent, DIR_MIME, name);
        if (created == null) throw new IOException("Could not create directory " + path);
        cache.put(path, created);
        return created;
    }

    @Override public void onBackPressed() {
        if (!busy && !activePath.isEmpty()) {
            int slash = activePath.lastIndexOf('/');
            activePath = slash < 0 ? "" : activePath.substring(0, slash);
            render();
        } else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        cancelled.set(true);
        worker.shutdownNow();
        super.onDestroy();
    }
}
