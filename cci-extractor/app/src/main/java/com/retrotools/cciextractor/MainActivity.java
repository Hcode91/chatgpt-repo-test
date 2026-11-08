package com.retrotools.cciextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.content.ClipData;
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
    private static final int PICK_NES_FOLDER = 306;
    private static final int PICK_NES_PNG = 307;
    private static final int SAVE_NES = 308;
    private static final int PICK_HD_PNGS = 309;
    private static final int PICK_HD_FOLDER = 310;
    private static final String DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Uri imageUri;
    private Uri pngUri, templateUri;
    private CciArchive.Info archive;
    private NesCodec.Info nesInfo;
    private int nesPage = -1;
    private Uri nesPngUri;
    private final List<Uri> hdPngUris = new ArrayList<>();
    private int hdFactor = 2;
    private boolean hdSmooth = false;
    private List<CciArchive.Entry> pending;
    private final List<String> displayed = new ArrayList<>();
    private String activePath = "";
    private boolean busy = false;
    private TextView headline, summary, location, detail;
    private Button open, extractAll, extractHere, up, stop, pngToBtex, pngToNes, upscaleHd;
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

        headline = text("Game Asset Extractor", 22, Color.WHITE);
        headline.setBackgroundColor(Color.rgb(35, 45, 91));
        headline.setPadding(dp(18), dp(16), dp(14), dp(16));
        root.addView(headline);
        summary = text("3DS CCI/BTEX and NES CHR tile editing, right on your phone.", 14, Color.DKGRAY);
        root.addView(summary);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(6), 0, dp(6), 0);
        root.addView(controls);
        open = button("Open CCI / NES");
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
        root.addView(pngToBtex, new LinearLayout.LayoutParams(-1, dp(50)));
        pngToNes = button("PNG → NES (rebuild CHR-ROM graphics)");
        root.addView(pngToNes, new LinearLayout.LayoutParams(-1, dp(50)));
        upscaleHd = button("3DS HD PNG upscale (2× / 4×)");
        root.addView(upscaleHd, new LinearLayout.LayoutParams(-1, dp(50)));
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
        pngToNes.setOnClickListener(v -> startNesImport());
        upscaleHd.setOnClickListener(v -> chooseHdUpscale());
        extractAll.setOnClickListener(v -> {
            if (nesInfo != null) beginNesExport(-1);
            else beginExtract(archive == null ? null : archive.entries);
        });
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
            if (busy || position < 0 || position >= displayed.size()) return;
            if (nesInfo != null) {
                final int page = position;
                String label = nesInfo.usesChrRam() ? "Experimental PRG tile scan" : "CHR-ROM graphics";
                new AlertDialog.Builder(this).setTitle(label + " page " + page)
                        .setMessage(nesInfo.usesChrRam() ?
                                "Preview the program's bytes as 2-bit NES tiles. These may be code or compressed graphics, not real sprites. Re-import is disabled to avoid corrupting this ROM." :
                                "Export a 128×128 PNG using the four NES colour indexes, then re-import a modified PNG to rebuild a new .nes copy.")
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Export PNG", (d,w) -> beginNesExport(page))
                        .show();
                return;
            }
            if (archive == null) return;
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
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_HD_PNGS) {
            hdPngUris.clear();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri selected = clip.getItemAt(i).getUri();
                    if (selected != null && !hdPngUris.contains(selected)) hdPngUris.add(selected);
                }
            } else if (data.getData() != null) {
                hdPngUris.add(data.getData());
            }
            if (!hdPngUris.isEmpty()) {
                Intent folderPicker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                folderPicker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivityForResult(folderPicker, PICK_HD_FOLDER);
            }
            return;
        }
        if (data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_IMAGE) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            imageUri = uri;
            archive = null;
            nesInfo = null;
            activePath = "";
            busy = true;
            render();
            summary.setText("Scanning game image...");
            worker.execute(() -> {
                try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r")) {
                    if (descriptor == null) throw new IOException("Cannot access this file");
                    try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
                         FileChannel channel = stream.getChannel()) {
                        if (NesCodec.isNes(channel)) {
                            NesCodec.Info parsedNes = NesCodec.parse(channel);
                            runOnUiThread(() -> {
                                nesInfo = parsedNes; archive = null;
                                busy = false;
                                summary.setText(parsedNes.description());
                                detail.setText(parsedNes.usesChrRam() ?
                                        "Experimental: " + parsedNes.pageCount() + " PRG tile-scan pages. CHR-RAM graphics may be compressed; ROM rebuilding disabled." :
                                        "Found " + parsedNes.pageCount() + " editable CHR-ROM pages. Tap a page or export all as PNG.");
                                render();
                            });
                        } else {
                            CciArchive.Info resultInfo = CciArchive.parse(channel);
                            runOnUiThread(() -> {
                                archive = resultInfo;
                                nesInfo = null;
                                busy = false;
                                summary.setText("Found " + archive.entries.size() + " files across " +
                                        archive.partitions + " NCCH partition(s)." +
                                        (resultInfo.titleId != null ? " Title ID: " + resultInfo.titleId : ""));
                                detail.setText((resultInfo.titleId == null
                                        ? "The game has no readable Title ID; partition folders will be preserved."
                                        : "Extracted RomFS goes under " + resultInfo.titleId + "/romfs/.") +
                                        (resultInfo.notes.isEmpty() ? "" :
                                        " Scan notes: " + String.join("; ", resultInfo.notes)));
                                render();
                            });
                        }
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        busy = false; archive = null; nesInfo = null;
                        summary.setText("Could not open game file");
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
        } else if (request == PICK_NES_FOLDER && nesInfo != null) {
            exportNes(uri);
        } else if (request == PICK_NES_PNG && nesInfo != null) {
            nesPngUri = uri;
            chooseNesOutput();
        } else if (request == SAVE_NES && nesPngUri != null && nesInfo != null) {
            rebuildNes(uri);
        } else if (request == PICK_HD_FOLDER && !hdPngUris.isEmpty()) {
            upscaleHdPngs(uri);
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
        if (nesInfo != null) {
            for (int i = 0; i < nesInfo.pageCount(); i++)
                displayed.add(String.format(Locale.ROOT, "📄 %s page %02d",
                        nesInfo.usesChrRam() ? "PRG candidate" : "CHR", i));
        }
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
        location.setText(nesInfo != null ?
                (nesInfo.usesChrRam() ? "/NES/PRG candidate scan" : "/NES/CHR graphics") +
                        " (" + displayed.size() + " sheets)" :
                "/" + activePath + "   (" + displayed.size() + " items)");
        open.setEnabled(!busy);
        pngToBtex.setEnabled(!busy);
        upscaleHd.setEnabled(!busy);
        pngToNes.setEnabled(!busy && nesInfo != null && !nesInfo.usesChrRam());
        extractAll.setText(nesInfo == null ? "Extract all" : "Export tile PNGs");
        extractAll.setEnabled(!busy && (archive != null || nesInfo != null));
        extractHere.setEnabled(!busy && archive != null && !entriesIn(activePath).isEmpty());
        up.setEnabled(!busy && archive != null && !activePath.isEmpty());
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
                        // Store a main-game file under <TITLE_ID>/romfs or <TITLE_ID>/exefs,
                        // not partition_0/romfs, so the title-ID folder can be copied to
                        // Azahar's load/mods directory without reconstructing its layout.
                        String exportPath = CciArchive.exportPath(e.path, archive.titleId);
                        int index = exportPath.lastIndexOf('/');
                        String path = exportPath.substring(0, index);
                        String filename = exportPath.substring(index + 1);
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
                    detail.setText("Original BTEX templates are retained beside each PNG. " +
                            (archive.titleId == null ? "No Title ID in header, so original partition paths were kept."
                                : "For Azahar mods, copy the " + archive.titleId +
                                    " folder from this extraction into your mods directory.") +
                            (skipped > 0 ? " " + skipped + " textures could not be converted." : ""));
                    render();
                    new AlertDialog.Builder(this).setTitle(interrupted ? "Extraction cancelled" : "Extraction complete")
                            .setMessage(complete + " original file(s) extracted. " + pngs +
                                    " BTEX texture(s) converted to PNG." + (skipped > 0 ?
                                    " " + skipped + " texture(s) could not be converted; originals preserved." : "") +
                                    "\n\n" + (archive.titleId == null ?
                                        "Title ID was unavailable, so original partition names were preserved." :
                                        "Exported game path: " + archive.titleId + "/romfs/ (inside CCI_Extract_...)." +
                                        "\nCopy the Title ID folder to Azahar's mods folder.") +
                                    "\nOriginal CCI was not modified.")
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

    private void beginNesExport(int onlyPage) {
        if (busy || nesInfo == null) return;
        nesPage = onlyPage;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, PICK_NES_FOLDER);
    }

    private void exportNes(Uri tree) {
        if (busy || nesInfo == null || imageUri == null) return;
        NesCodec.Info info = nesInfo;
        int selectedPage = nesPage;
        busy = true; cancelled.set(false); render();
        summary.setText("Exporting " + (info.usesChrRam() ? "experimental PRG scans" : "NES CHR graphics") + "...");
        worker.execute(() -> {
            int done = 0;
            try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(imageUri, "r")) {
                if (descriptor == null) throw new IOException("Could not reopen NES ROM");
                try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
                     FileChannel channel = stream.getChannel()) {
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                            DocumentsContract.getTreeDocumentId(tree));
                    Uri folder = DocumentsContract.createDocument(getContentResolver(), parent,
                            DIR_MIME, "NES_Tiles_" + stamp);
                    if (folder == null) throw new IOException("Cannot create output folder");
                    for (int i = 0; i < info.pageCount(); i++) {
                        if (selectedPage >= 0 && i != selectedPage) continue;
                        if (cancelled.get()) break;
                        byte[] raw = NesCodec.readPage(channel, info, i);
                        int[] pixels = NesCodec.decodeTiles(raw);
                        Bitmap image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                        image.setPixels(pixels, 0, 128, 0, 0, 128, 128);
                        String filename = String.format(Locale.ROOT, "%s_%02d.png",
                                info.usesChrRam() ? "prg_candidate" : "chr_page", i);
                        Uri output = DocumentsContract.createDocument(getContentResolver(), folder, "image/png", filename);
                        if (output == null) throw new IOException("Cannot create " + filename);
                        try (OutputStream dst = getContentResolver().openOutputStream(output, "w")) {
                            if (dst == null || !image.compress(Bitmap.CompressFormat.PNG, 100, dst))
                                throw new IOException("PNG writing failed: " + filename);
                        } finally { image.recycle(); }
                        done++;
                        final int n = done;
                        runOnUiThread(() -> detail.setText(n + " PNG tile sheet(s) written."));
                    }
                    Uri doc = DocumentsContract.createDocument(getContentResolver(), folder,
                            "text/plain", "README.txt");
                    if (doc != null) try (OutputStream dst = getContentResolver().openOutputStream(doc, "w")) {
                        if (dst != null) {
                            String explanation = "NES 2bpp tile sheets: 16x16 tiles, each tile 8x8 pixels.\n" +
                                    "Use opaque #000000, #555555, #AAAAAA, #FFFFFF only.\n" +
                                    "The game's actual palette colours are stored elsewhere.\n" +
                                    "Mapper: " + info.mapper + "; PRG KiB: " + info.prgBytes/1024 +
                                    "; CHR KiB: " + info.chrBytes/1024 + ".\n" +
                                    (info.usesChrRam() ?
                                        "WARNING: CHR-RAM title. PRG candidate PNGs reinterpret ROM code/data as tiles. They are NOT confirmed game artwork. PNG -> NES is disabled.\n" :
                                        "Each chr_page_NN.png maps to page NN in the original CHR-ROM. Choose the same page in PNG -> NES.\n");
                            dst.write(explanation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                }
                final int count = done;
                runOnUiThread(() -> {
                    busy = false; render();
                    summary.setText(count + " NES PNG tile sheet(s) exported" + (cancelled.get() ? " (cancelled)" : ""));
                    detail.setText(info.usesChrRam() ? "Experimental raw PRG scans. These are not verified Caesars Palace graphics." :
                            "Export complete. Use PNG → NES to patch a CHR-ROM page in a NEW ROM file.");
                    new AlertDialog.Builder(this).setTitle("NES PNG export complete")
                            .setMessage(count + " PNG file(s) saved. " + (info.usesChrRam() ?
                                    "CHR-RAM mode: PRG scans are exploratory, and ROM rebuilding is disabled." :
                                    "Edit with only the four grayscale colours, then use PNG → NES."))
                            .setPositiveButton("OK",null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false; render(); summary.setText("NES export failed"); detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("NES export failed")
                            .setMessage(message(e)).setPositiveButton("OK",null).show();
                });
            }
        });
    }

    private void startNesImport() {
        if (busy || nesInfo == null) return;
        if (nesInfo.usesChrRam()) {
            new AlertDialog.Builder(this).setTitle("CHR-RAM graphics")
                    .setMessage("This NES game has no fixed CHR-ROM data. The PRG scans are only exploratory and could include executable code. " +
                            "Repacking PNGs would corrupt the ROM unless its graphics loader and encoding are reverse engineered first.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        int pages = nesInfo.pageCount();
        String[] labels = new String[pages];
        for (int i=0;i<pages;i++) labels[i] = String.format(Locale.ROOT,"CHR page %02d",i);
        new AlertDialog.Builder(this).setTitle("Choose NES graphics page")
                .setItems(labels, (dialog, which) -> {
                    nesPage = which;
                    nesPngUri = null;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/png");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, PICK_NES_PNG);
                }).setNegativeButton("Cancel",null).show();
    }

    private void chooseNesOutput() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "NES_Modified_Page_" + nesPage + ".nes");
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, SAVE_NES);
    }

    private void rebuildNes(Uri destination) {
        if (busy || nesInfo == null || nesInfo.usesChrRam() || nesPage < 0) return;
        NesCodec.Info sourceInfo = nesInfo;
        Uri source = imageUri, png = nesPngUri;
        int page = nesPage;
        busy = true; cancelled.set(false); render();
        summary.setText("Rebuilding a separate NES ROM...");
        worker.execute(() -> {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try(InputStream in = getContentResolver().openInputStream(png)) {
                    if (in == null) throw new IOException("Cannot read edited PNG");
                    BitmapFactory.decodeStream(in, null, opts);
                }
                if (opts.outWidth != 128 || opts.outHeight != 128)
                    throw new IOException("NES tile PNG must be exactly 128×128 pixels");
                opts.inJustDecodeBounds = false;
                opts.inPremultiplied = false;
                Bitmap bitmap;
                try (InputStream in = getContentResolver().openInputStream(png)) {
                    if (in == null) throw new IOException("Cannot reopen edited PNG");
                    bitmap = BitmapFactory.decodeStream(in, null, opts);
                }
                if (bitmap == null) throw new IOException("PNG decoding failed");
                int[] pixels = new int[128*128];
                try {bitmap.getPixels(pixels,0,128,0,0,128,128);} finally {bitmap.recycle();}
                byte[] patched = NesCodec.encodeTiles(pixels,128,128);
                if (cancelled.get()) throw new IOException("Cancelled");
                try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(source,"r")) {
                    if (descriptor == null) throw new IOException("Cannot read original NES ROM");
                    try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
                         FileChannel channel = input.getChannel()) {
                        NesCodec.Info info = NesCodec.parse(channel);
                        if (info.chrBytes != sourceInfo.chrBytes || info.mapper != sourceInfo.mapper ||
                                info.prgBytes != sourceInfo.prgBytes)
                            throw new IOException("The original ROM has changed; reopen it first");
                        try (OutputStream output = getContentResolver().openOutputStream(destination,"w")) {
                            if (output == null) throw new IOException("Cannot write new NES ROM");
                            NesCodec.rebuild(channel, output, info, page, patched);
                        }
                    }
                }
                runOnUiThread(() -> {
                    busy = false; render(); summary.setText("New NES ROM exported");
                    detail.setText("CHR page " + page + " replaced; original NES ROM untouched.");
                    new AlertDialog.Builder(this).setTitle("NES mod ready")
                            .setMessage("Saved a NEW NES ROM with graphics page " + page + " replaced. The original ROM was not edited.")
                            .setPositiveButton("OK",null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false; render(); summary.setText("PNG → NES failed"); detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("NES rebuild failed")
                            .setMessage(message(e) + "\n\nA new ROM is only supported for games with CHR-ROM.")
                            .setPositiveButton("OK",null).show();
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


    /**
     * The CCI's BTEX PNG exports have file names such as TitleLogo.btex.png; these
     * can be enlarged for editing but cannot be re-imported as higher-resolution BTEX.
     * For actual HD replacements use Azahar's dumped "tex1_...png" images so the
     * renderer can match each texture by its original hash.
     */
    private void chooseHdUpscale() {
        if (busy) return;
        String[] options = {"2× Sharp / pixel art", "2× Smooth", "4× Sharp / pixel art", "4× Smooth"};
        new AlertDialog.Builder(this).setTitle("Upscale 3DS PNG textures")
                .setMessage("Upscale exported PNGs or Azahar's dumped tex1_...png textures. " +
                        "Only Azahar's hash-named texture dumps can be installed directly as HD custom textures. " +
                        "Upscaled PNGs are NOT compatible with normal-size PNG → BTEX templates. " +
                        "Scaling alone does not create new image detail.")
                .setSingleChoiceItems(options, 0, (dialog, choice) -> {
                    hdFactor = choice >= 2 ? 4 : 2;
                    hdSmooth = (choice % 2) == 1;
                    dialog.dismiss();
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/png");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, PICK_HD_PNGS);
                }).setNegativeButton("Cancel", null).show();
    }

    private boolean containsPackJson(Uri root) {
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root,
                    DocumentsContract.getTreeDocumentId(root));
            try (Cursor c = getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null) while (c.moveToNext()) {
                    if ("pack.json".equalsIgnoreCase(c.getString(0))) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void upscaleHdPngs(Uri folderTree) {
        if (busy) return;
        final List<Uri> inputs = new ArrayList<>(hdPngUris);
        final int factor = hdFactor;
        final boolean smooth = hdSmooth;
        hdPngUris.clear();
        busy = true;
        cancelled.set(false);
        render();
        summary.setText("Upscaling " + inputs.size() + " PNG(s) " + factor + "×...");
        worker.execute(() -> {
            int complete = 0;
            int errors = 0;
            int hashed = 0;
            String lastError = "";
            try {
                Uri folder = DocumentsContract.buildDocumentUriUsingTree(folderTree,
                        DocumentsContract.getTreeDocumentId(folderTree));
                for (Uri inputUri : inputs) {
                    if (cancelled.get()) break;
                    try {
                        String filename = documentName(inputUri, "texture.png");
                        if (!filename.toLowerCase(Locale.ROOT).endsWith(".png"))
                            throw new IOException("Not a PNG file: " + filename);
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        try (InputStream source = getContentResolver().openInputStream(inputUri)) {
                            if (source == null) throw new IOException("Cannot read " + filename);
                            BitmapFactory.decodeStream(source, null, opts);
                        }
                        long targetW = (long) opts.outWidth * factor;
                        long targetH = (long) opts.outHeight * factor;
                        if (opts.outWidth <= 0 || opts.outHeight <= 0 ||
                                targetW > 4096 || targetH > 4096 || targetW * targetH > 12000000L)
                            throw new IOException(filename + " exceeds the safe 4096px / 12MP output limit");
                        opts.inJustDecodeBounds = false;
                        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        Bitmap original;
                        try (InputStream source = getContentResolver().openInputStream(inputUri)) {
                            if (source == null) throw new IOException("Cannot reopen " + filename);
                            original = BitmapFactory.decodeStream(source, null, opts);
                        }
                        if (original == null) throw new IOException("Unable to decode " + filename);
                        Bitmap enlarged = null;
                        try {
                            enlarged = Bitmap.createScaledBitmap(original, (int) targetW,
                                    (int) targetH, smooth);
                            Uri result = DocumentsContract.createDocument(getContentResolver(), folder,
                                    "image/png", filename);
                            if (result == null) throw new IOException("Cannot create " + filename);
                            try (OutputStream output = getContentResolver().openOutputStream(result, "w")) {
                                if (output == null || !enlarged.compress(Bitmap.CompressFormat.PNG, 100, output))
                                    throw new IOException("Cannot save " + filename);
                            } catch (Exception writeError) {
                                try { DocumentsContract.deleteDocument(getContentResolver(), result); }
                                catch (Exception ignored) { }
                                throw writeError;
                            }
                            complete++;
                            if (filename.startsWith("tex1_")) hashed++;
                            final int finished = complete;
                            runOnUiThread(() -> detail.setText(finished + "/" + inputs.size() +
                                    " upscaled PNGs; originals untouched."));
                        } finally {
                            if (enlarged != null && enlarged != original) enlarged.recycle();
                            original.recycle();
                        }
                    } catch (Exception perFile) {
                        errors++;
                        lastError = message(perFile);
                    }
                }
                if (hashed > 0 && !containsPackJson(folderTree)) {
                    // Basic Azahar configuration. Texture file names must originate from the
                    // emulator's texture dumper; BTEX filenames are not valid hash names.
                    String pack = "{\n" +
                            "  \"author\": \"Game Asset Extractor\",\n" +
                            "  \"version\": \"1.0.0\",\n" +
                            "  \"description\": \"Upscaled 3DS texture PNGs\",\n" +
                            "  \"options\": {\n" +
                            "    \"skip_mipmap\": false,\n" +
                            "    \"flip_png_files\": true,\n" +
                            "    \"use_new_hash\": true\n" +
                            "  }\n" +
                            "}\n";
                    Uri json = DocumentsContract.createDocument(getContentResolver(), folder,
                            "application/json", "pack.json");
                    if (json != null) try (OutputStream out = getContentResolver().openOutputStream(json, "w")) {
                        if (out != null) out.write(pack.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                final int done = complete, skipped = errors, hashNamed = hashed;
                final String last = lastError;
                final boolean interrupted = cancelled.get();
                runOnUiThread(() -> {
                    busy = false; render();
                    summary.setText("Upscaled " + done + " PNGs " + factor + "×" +
                            (interrupted ? " (cancelled)" : ""));
                    detail.setText(hashNamed > 0 ?
                            "Azahar dump names preserved. Use the matching game's custom textures folder and enable Custom Textures." :
                            "These are standalone enlarged PNGs. For an HD mod, use Azahar's hash-named texture dumps.");
                    new AlertDialog.Builder(this).setTitle("HD PNG export complete")
                            .setMessage("Saved " + done + " PNG(s) at " + factor + "× resolution (" +
                                    (smooth ? "smooth" : "sharp") + "). " + skipped + " skipped." +
                                    (skipped > 0 ? " Last error: " + last : "") +
                                    "\n\nTo show HD textures in Azahar, use PNGs dumped by Azahar (tex1_... names) " +
                                    "and enable Custom Textures. Normal BTEX replacements must remain at original dimensions.")
                            .setPositiveButton("OK", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false; render(); summary.setText("HD upscale failed"); detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("Upscale failed")
                            .setMessage(message(e)).setPositiveButton("OK", null).show();
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
        if (!busy && archive != null && !activePath.isEmpty()) {
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
