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

/** On-device 3DS, Nintendo DS, NES, SNES and Mega Drive ROM asset tools. */
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
    private static final int PICK_RETRO_FOLDER = 311;
    private static final int PICK_RETRO_PNG = 312;
    private static final int SAVE_RETRO_ROM = 313;
    private static final int PICK_NDS_DIRECTORY = 314;
    private static final String DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Uri imageUri;
    private Uri pngUri, templateUri;
    private CciArchive.Info archive;
    private NesCodec.Info nesInfo;
    private NdsArchive.Info ndsInfo;
    private RetroTiles.Info retroInfo;
    private RetroTiles.Mode retroMode;
    private int retroPage = -1;
    private Uri retroPngUri;
    private List<NdsArchive.Entry> pendingNds;
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
    private Button pngToRetro, retroFormat;
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
        summary = text("3DS · DS · NES · SNES · Mega Drive asset tools", 14, Color.DKGRAY);
        root.addView(summary);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(6), 0, dp(6), 0);
        root.addView(controls);
        open = button("Open ROM (3DS / DS / NES / SNES / MD)");
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
        retroFormat = button("SNES tile mode: 4bpp (change format)");
        root.addView(retroFormat, new LinearLayout.LayoutParams(-1, dp(48)));
        pngToRetro = button("PNG → SNES / Mega Drive ROM (raw tile page)");
        root.addView(pngToRetro, new LinearLayout.LayoutParams(-1, dp(50)));
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
        pngToRetro.setOnClickListener(v -> startRetroImport());
        retroFormat.setOnClickListener(v -> chooseRetroFormat());
        upscaleHd.setOnClickListener(v -> chooseHdUpscale());
        extractAll.setOnClickListener(v -> {
            if (retroInfo != null) beginRetroExport(-1);
            else if (nesInfo != null) beginNesExport(-1);
            else if (ndsInfo != null) beginNdsExtract(ndsInfo.entries);
            else beginExtract(archive == null ? null : archive.entries);
        });
        extractHere.setOnClickListener(v -> {
            if (ndsInfo != null) beginNdsExtract(ndsEntriesIn(activePath));
            else beginExtract(entriesIn(activePath));
        });
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
            if (retroInfo != null) {
                final int page = position;
                final RetroTiles.Mode mode = retroMode;
                new AlertDialog.Builder(this).setTitle(mode.slug + " · page " + page)
                        .setMessage("Raw ROM tile scan at byte offset 0x" +
                                Long.toHexString(retroInfo.pageOffset(mode,page)).toUpperCase(Locale.ROOT) +
                                ". Some data may be code or compressed assets rather than graphics. " +
                                "Colours are palette indexes, not the game's actual RGB colours.")
                        .setNegativeButton("Close",null)
                        .setPositiveButton("Export PNG",(d,w)->beginRetroExport(page)).show();
                return;
            }
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
            if (archive == null && ndsInfo == null) return;
            String row = displayed.get(position);
            if (row.startsWith("📁 ")) {
                String folder = row.substring(3);
                activePath = activePath.isEmpty() ? folder : activePath + "/" + folder;
                render();
            } else if (row.startsWith("📄 ")) {
                String filename = row.substring(3);
                String full = activePath.isEmpty() ? filename : activePath + "/" + filename;
                if (ndsInfo != null) {
                    for (NdsArchive.Entry entry : ndsInfo.entries) if (entry.path.equals(full)) {
                        new AlertDialog.Builder(this).setTitle(filename)
                                .setMessage("Size: " + human(entry.size) + "\n\nExtract this NitroFS file?")
                                .setNegativeButton("Cancel",null)
                                .setPositiveButton("Extract",(d,w)->beginNdsExtract(Collections.singletonList(entry))).show();
                        return;
                    }
                    return;
                }
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
            ndsInfo = null;
            retroInfo = null;
            retroMode = null;
            activePath = "";
            busy = true;
            render();
            summary.setText("Scanning game image...");
            worker.execute(() -> {
                try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r")) {
                    if (descriptor == null) throw new IOException("Cannot access this file");
                    try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
                         FileChannel channel = stream.getChannel()) {
                        String inputName = getFilename(uri);
                        if (NesCodec.isNes(channel)) {
                            NesCodec.Info parsedNes = NesCodec.parse(channel);
                            runOnUiThread(() -> {
                                nesInfo = parsedNes; archive = null; ndsInfo = null; retroInfo = null;
                                busy = false;
                                summary.setText(parsedNes.description());
                                detail.setText(parsedNes.usesChrRam() ?
                                        "Experimental: " + parsedNes.pageCount() + " PRG tile-scan pages. CHR-RAM graphics may be compressed; ROM rebuilding disabled." :
                                        "Found " + parsedNes.pageCount() + " editable CHR-ROM pages. Tap a page or export all as PNG.");
                                render();
                            });
                        } else if (NdsArchive.isNds(channel)) {
                            NdsArchive.Info parsedNds = NdsArchive.parse(channel);
                            runOnUiThread(() -> {
                                ndsInfo=parsedNds; archive=null; nesInfo=null; retroInfo=null;
                                busy=false;
                                summary.setText(parsedNds.description());
                                detail.setText("Browse NitroFS folders, select files, or extract everything. " +
                                        "Recognised Nitro files are preserved in their original form.");
                                render();
                            });
                        } else if (RetroTiles.isMegaDrive(channel) || RetroTiles.isSnes(channel,inputName)) {
                            RetroTiles.System platform = RetroTiles.isMegaDrive(channel)
                                    ? RetroTiles.System.MEGADRIVE : RetroTiles.System.SNES;
                            RetroTiles.Info parsedRetro=RetroTiles.parse(channel,platform,inputName);
                            runOnUiThread(() -> {
                                retroInfo=parsedRetro; retroMode=parsedRetro.defaultMode();
                                archive=null;nesInfo=null;ndsInfo=null;busy=false;
                                summary.setText(parsedRetro.description());
                                detail.setText("Raw tile pages are candidate graphics. No compression or palettes " +
                                        "are decoded. Do not rebuild pages unless you've identified real graphics.");
                                render();
                            });
                        } else {
                            CciArchive.Info resultInfo = CciArchive.parse(channel);
                            runOnUiThread(() -> {
                                archive = resultInfo;
                                nesInfo = null; ndsInfo = null; retroInfo = null;
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
                        busy = false; archive = null; nesInfo = null; ndsInfo = null; retroInfo = null;
                        summary.setText("Could not open game file");
                        detail.setText(message(e));
                        render();
                        new AlertDialog.Builder(this).setTitle("ROM scan failed")
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
        } else if (request == PICK_NDS_DIRECTORY && pendingNds != null) {
            List<NdsArchive.Entry> selection=pendingNds;
            pendingNds=null; extractNds(selection,uri);
        } else if (request == PICK_RETRO_FOLDER && retroInfo != null) {
            exportRetro(uri);
        } else if (request == PICK_RETRO_PNG && retroInfo != null) {
            retroPngUri=uri; selectRetroOutput();
        } else if (request == SAVE_RETRO_ROM && retroInfo != null && retroPngUri != null) {
            rebuildRetro(uri);
        }
    }

    private String getFilename(Uri uri) {
        String fallback=uri.getLastPathSegment();
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null && c.moveToFirst()) return c.getString(0);
        } catch(Exception ignored) {}
        return fallback==null?"unknown":fallback;
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

    private List<NdsArchive.Entry> ndsEntriesIn(String prefix) {
        if(ndsInfo==null) return null;
        List<NdsArchive.Entry> result=new ArrayList<>();
        String base=prefix.isEmpty()?"":prefix+"/";
        for(NdsArchive.Entry e:ndsInfo.entries) if(e.path.startsWith(base))result.add(e);
        return result;
    }
    private void beginNdsExtract(List<NdsArchive.Entry> selection) {
        if(busy || selection==null || selection.isEmpty())return;
        pendingNds=new ArrayList<>(selection);
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent,PICK_NDS_DIRECTORY);
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
        if (retroInfo != null && retroMode != null) {
            for(int i=0;i<retroInfo.pageCount(retroMode);i++)
                displayed.add(String.format(Locale.ROOT,"📄 %s page %04d",retroMode.slug,i));
        }
        if (archive != null || ndsInfo != null) {
            String prefix = activePath.isEmpty() ? "" : activePath + "/";
            Set<String> directories = new TreeSet<>();
            Set<String> files = new TreeSet<>();
            List<String> paths=new ArrayList<>();
            if(archive!=null)for(CciArchive.Entry e:archive.entries)paths.add(e.path);
            if(ndsInfo!=null)for(NdsArchive.Entry e:ndsInfo.entries)paths.add(e.path);
            for (String filePath : paths) {
                if (!filePath.startsWith(prefix)) continue;
                String remainder = filePath.substring(prefix.length());
                int slash = remainder.indexOf('/');
                if (slash >= 0) directories.add(remainder.substring(0, slash));
                else files.add(remainder);
            }
            for (String dir : directories) displayed.add("📁 " + dir);
            for (String file : files) displayed.add("📄 " + file);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        location.setText(retroInfo!=null && retroMode!=null ?
                "/"+retroInfo.system+"/"+retroMode.slug+" ("+displayed.size()+" candidate pages)" :
                nesInfo != null ?
                (nesInfo.usesChrRam() ? "/NES/PRG candidate scan" : "/NES/CHR graphics") +
                        " (" + displayed.size() + " sheets)" :
                "/" + activePath + "   (" + displayed.size() + " items)");
        open.setEnabled(!busy);
        pngToBtex.setEnabled(!busy);
        upscaleHd.setEnabled(!busy);
        pngToNes.setEnabled(!busy && nesInfo != null && !nesInfo.usesChrRam());
        pngToRetro.setEnabled(!busy && retroInfo != null);
        retroFormat.setEnabled(!busy && retroInfo != null && retroInfo.system==RetroTiles.System.SNES);
        retroFormat.setVisibility(retroInfo!=null && retroInfo.system==RetroTiles.System.SNES?View.VISIBLE:View.GONE);
        pngToRetro.setVisibility(retroInfo!=null?View.VISIBLE:View.GONE);
        if(retroMode!=null)retroFormat.setText("SNES tile format: "+retroMode.slug);
        extractAll.setText((nesInfo != null || retroInfo != null) ? "Export all tile PNGs" : "Extract all");
        extractAll.setEnabled(!busy && (archive != null || ndsInfo != null || nesInfo != null || retroInfo!=null));
        extractHere.setEnabled(!busy && (archive != null && !entriesIn(activePath).isEmpty() ||
                ndsInfo != null && !ndsEntriesIn(activePath).isEmpty()));
        up.setEnabled(!busy && (archive != null || ndsInfo!=null) && !activePath.isEmpty());
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
                        String exportPath=CciArchive.exportPath(e.path,archive.titleId);
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
                            (archive.titleId == null ? "No Title ID in header; original partition paths were kept."
                                : "For Azahar mods, copy " + archive.titleId + " folder from this extraction.") +
                            (skipped > 0 ? " " + skipped + " textures could not be converted." : ""));
                    render();
                    new AlertDialog.Builder(this).setTitle(interrupted ? "Extraction cancelled" : "Extraction complete")
                            .setMessage(complete + " original file(s) extracted. " + pngs +
                                    " BTEX texture(s) converted to PNG." + (skipped > 0 ?
                                    " " + skipped + " texture(s) could not be converted; originals preserved." : "") +
                                    "\n\n" + (archive.titleId == null ?
                                        "Title ID unavailable; original partition names preserved." :
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

    /** Extract standard NitroFS files, never modifying the selected .nds ROM. */
    private void extractNds(List<NdsArchive.Entry> selection, Uri tree) {
        if(busy || ndsInfo==null) return;
        busy=true;cancelled.set(false);render();summary.setText("Extracting DS NitroFS files...");
        worker.execute(() -> {
            int done=0, pngs=0, unsupported=0;
            Map<String,NdsArchive.Entry> allByName=new HashMap<>();
            for(NdsArchive.Entry e:ndsInfo.entries)
                allByName.put(e.path.toLowerCase(Locale.ROOT),e);
            try (ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                if(descriptor==null)throw new IOException("Cannot open DS ROM");
                try(FileInputStream source=new FileInputStream(descriptor.getFileDescriptor());
                    FileChannel channel=source.getChannel()) {
                    Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                    String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
                    Uri folder=DocumentsContract.createDocument(getContentResolver(),root,DIR_MIME,"NDS_Extract_"+stamp);
                    if(folder==null)throw new IOException("Cannot create DS output folder");
                    Map<String,Uri> folders=new HashMap<>();folders.put("",folder);
                    byte[] buffer=new byte[65536];
                    for(NdsArchive.Entry e:selection) {
                        if(cancelled.get())break;
                        String dirName=e.path.substring(0,e.path.lastIndexOf('/'));
                        String fileName=e.path.substring(e.path.lastIndexOf('/')+1);
                        Uri dir=ensureFolder(dirName,folders);
                        Uri file=DocumentsContract.createDocument(getContentResolver(),dir,"application/octet-stream",fileName);
                        if(file==null)throw new IOException("Cannot create " + e.path);
                        try(OutputStream out=getContentResolver().openOutputStream(file,"w")) {
                            if(out==null)throw new IOException("Cannot open file destination");
                            long left=e.size,offset=e.offset;
                            while(left>0) {
                                if(cancelled.get()) break;
                                int take=(int)Math.min(buffer.length,left);
                                ByteBuffer dst=ByteBuffer.wrap(buffer,0,take);
                                int n=channel.read(dst,offset);
                                if(n<=0)throw new IOException("Unexpected end of DS ROM");
                                out.write(buffer,0,n);left-=n;offset+=n;
                            }
                            if(left>0) {DocumentsContract.deleteDocument(getContentResolver(),file);break;}
                        }
                        if(fileName.toLowerCase(Locale.ROOT).endsWith(".ncgr")) {
                            String stem=e.path.substring(0,e.path.length()-5);
                            NdsArchive.Entry palette=allByName.get((stem+".nclr").toLowerCase(Locale.ROOT));
                            if(palette!=null) {
                                try {
                                    if(e.size>NdsGraphics.MAX_BYTES || palette.size>NdsGraphics.MAX_BYTES)
                                        throw new IOException("Nitro 2D image too large");
                                    NdsGraphics.Image decoded=NdsGraphics.decode(
                                            readBtex(channel,e.offset,(int)e.size),
                                            readBtex(channel,palette.offset,(int)palette.size));
                                    Bitmap bitmap=Bitmap.createBitmap(decoded.width,decoded.height,Bitmap.Config.ARGB_8888);
                                    bitmap.setPixels(decoded.argb,0,decoded.width,0,0,decoded.width,decoded.height);
                                    String outputName=fileName.substring(0,fileName.length()-5)+".png";
                                    Uri preview=DocumentsContract.createDocument(getContentResolver(),dir,"image/png",outputName);
                                    if(preview==null)throw new IOException("Cannot create Nitro PNG");
                                    try(OutputStream output=getContentResolver().openOutputStream(preview,"w")) {
                                        if(output==null||!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))
                                            throw new IOException("Nitro PNG encoding failed");
                                    } finally {bitmap.recycle();}
                                    pngs++;
                                } catch(Exception conversionFailure) {unsupported++;}
                            } else unsupported++;
                        }
                        done++;
                        final int progress=done;
                        if(done%10==0 || done==selection.size())runOnUiThread(() ->
                                detail.setText(progress+"/"+selection.size()+" NitroFS files"));
                    }
                }
                final int finished=done, previews=pngs, missed=unsupported;
                runOnUiThread(() -> {busy=false;render();
                    summary.setText("Nintendo DS: "+finished+" files, "+previews+" PNG previews"+
                            (cancelled.get()?" (cancelled)":""));
                    detail.setText("Original DS ROM preserved. Matching .ncgr/.nclr 2D graphics create PNG previews; " +
                            "unsupported or compressed images stay untouched.");
                    new AlertDialog.Builder(this).setTitle("DS extraction complete")
                            .setMessage(finished+" files copied into NDS_Extract_.../nitrofs. " +
                                    previews+" Nitro 2D graphics converted to PNG. "+missed+
                                    " NCGR files skipped (no matching palette or unsupported format). Original ROM unchanged.")
                            .setPositiveButton("OK",null).show();
                });
            } catch(Exception e) {
                runOnUiThread(() -> {busy=false;render();summary.setText("DS extraction error");detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("DS extraction error").setMessage(message(e))
                            .setPositiveButton("OK",null).show();
                });
            }
        });
    }

    private void chooseRetroFormat() {
        if(retroInfo==null || retroInfo.system!=RetroTiles.System.SNES || busy)return;
        RetroTiles.Mode[] options={RetroTiles.Mode.SNES_2BPP,RetroTiles.Mode.SNES_3BPP,
                RetroTiles.Mode.SNES_4BPP,RetroTiles.Mode.SNES_8BPP};
        String[] labels={"2bpp (4 palette indexes)","3bpp (8 indexes)",
                "4bpp (16 indexes)","8bpp (256 indexes)"};
        new AlertDialog.Builder(this).setTitle("Select SNES tile format")
                .setSingleChoiceItems(labels,java.util.Arrays.asList(options).indexOf(retroMode),
                        (d,which)->{retroMode=options[which];d.dismiss();render();})
                .setNegativeButton("Cancel",null).show();
    }

    private void beginRetroExport(int selected) {
        if(busy||retroInfo==null||retroMode==null)return;
        retroPage=selected;
        int count=selected<0?retroInfo.pageCount(retroMode):1;
        if(count>32) {
            new AlertDialog.Builder(this).setTitle("Export " + count + " raw tile sheets?")
                .setMessage("This will create "+count+" PNGs and could take a while. The ROM is being " +
                        "scanned for uncompressed graphics: some PNGs will show code, audio, or other binary data.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Export",(d,w)->pickRetroFolder()).show();
        } else pickRetroFolder();
    }
    private void pickRetroFolder() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent,PICK_RETRO_FOLDER);
    }
    private void exportRetro(Uri tree) {
        if(busy||retroInfo==null||retroMode==null)return;
        final RetroTiles.Info info=retroInfo;
        final RetroTiles.Mode mode=retroMode;
        final int single=retroPage;
        busy=true;cancelled.set(false);render();summary.setText("Exporting raw "+mode.slug+" tiles...");
        worker.execute(() -> {
            int done=0;
            try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                if(descriptor==null)throw new IOException("Cannot read selected ROM");
                try(FileInputStream src=new FileInputStream(descriptor.getFileDescriptor());
                    FileChannel channel=src.getChannel()) {
                    Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                    String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
                    Uri folder=DocumentsContract.createDocument(getContentResolver(),root,DIR_MIME,
                            (info.system==RetroTiles.System.SNES?"SNES":"MegaDrive")+"_TileScans_"+stamp);
                    if(folder==null)throw new IOException("Cannot create scan output folder");
                    for(int page=0;page<info.pageCount(mode);page++) {
                        if(single>=0 && single!=page)continue;
                        if(cancelled.get())break;
                        byte[] data=RetroTiles.readPage(channel,info,mode,page);
                        int[] pixels=RetroTiles.decode(data,mode);
                        Bitmap image=Bitmap.createBitmap(mode.width(),mode.height(),Bitmap.Config.ARGB_8888);
                        image.setPixels(pixels,0,mode.width(),0,0,mode.width(),mode.height());
                        String name=String.format(Locale.ROOT,"%s_page_%04d.png",mode.slug,page);
                        Uri file=DocumentsContract.createDocument(getContentResolver(),folder,"image/png",name);
                        if(file==null)throw new IOException("Could not create " + name);
                        try(OutputStream out=getContentResolver().openOutputStream(file,"w")) {
                            if(out==null || !image.compress(Bitmap.CompressFormat.PNG,100,out))
                                throw new IOException("Could not write " + name);
                        } finally {image.recycle();}
                        done++;
                        final int progress=done;
                        if(done%10==0 || done==1)runOnUiThread(() -> detail.setText(progress+" PNG tile scans exported"));
                    }
                    Uri notes=DocumentsContract.createDocument(getContentResolver(),folder,"text/plain","README.txt");
                    if(notes!=null)try(OutputStream out=getContentResolver().openOutputStream(notes,"w")) {
                        if(out!=null) {
                            String message="Game Asset Extractor · Raw tile scan\n"+
                                "ROM system: "+info.system+"\nFormat: "+mode.slug+"\n"+
                                "WARNING: These tiles are raw interpretations of ROM bytes. They may not be graphics.\n"+
                                "ROM graphics palettes are NOT included. PNG gray levels preserve tile indexes.\n"+
                                "Re-import requires exactly " + mode.width()+" x "+mode.height()+
                                " pixels, opaque, and exact gray-level indices.\n"+
                                "The PNG's filename encodes its page and format. Preserve it.\n"+
                                "The original ROM is never overwritten.\n";
                            out.write(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                }
                final int finished=done;
                runOnUiThread(()->{busy=false;render();summary.setText(finished+" "+mode.slug+" sheets exported");
                    detail.setText("These are candidate graphics, not automatically recognised sprites.");
                    new AlertDialog.Builder(this).setTitle("Tile scan exported")
                        .setMessage(finished+" indexed grayscale PNG sheets saved. " +
                            "Some sheets may be code or compressed data, not graphics.")
                        .setPositiveButton("OK",null).show();
                });
            } catch(Exception e) {
                runOnUiThread(()->{busy=false;render();summary.setText("Tile export failed");detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("Export error").setMessage(message(e)).setPositiveButton("OK",null).show();
                });
            }
        });
    }
    private void startRetroImport() {
        if(retroInfo==null || retroMode==null || busy)return;
        new AlertDialog.Builder(this).setTitle("Experimental raw tile replacement")
            .setMessage("Only use a PNG exported from THIS ROM and tile mode. The filename identifies the original " +
                "page. If those bytes are code or compressed data, replacing them can make the new ROM unbootable. " +
                "The original ROM will remain untouched. Continue?")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Choose PNG",(d,w)->{
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/png");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent,PICK_RETRO_PNG);
            }).show();
    }
    private int selectedRetroPage() throws IOException {
        if(retroPngUri==null || retroMode==null)throw new IOException("Choose a tile sheet PNG");
        String name=getFilename(retroPngUri);
        String prefix=retroMode.slug+"_page_";
        if(!name.startsWith(prefix)||!name.endsWith(".png"))
            throw new IOException("PNG must be an exported "+prefix+"NNNN.png sheet. " +
                    "Select the same SNES tile format used for export.");
        String number=name.substring(prefix.length(),name.length()-4);
        if(!number.matches("[0-9]{1,6}"))throw new IOException("Invalid page number in PNG filename");
        int index=Integer.parseInt(number);
        if(index>=retroInfo.pageCount(retroMode))throw new IOException("Sheet page is outside selected ROM");
        return index;
    }
    private void selectRetroOutput() {
        try {selectedRetroPage();}
        catch(Exception e) {
            new AlertDialog.Builder(this).setTitle("PNG name mismatch").setMessage(message(e))
                .setPositiveButton("OK",null).show();return;
        }
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,"modified_"+getFilename(imageUri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent,SAVE_RETRO_ROM);
    }
    private void rebuildRetro(Uri destination) {
        if(busy || retroInfo==null || retroMode==null || retroPngUri==null)return;
        final RetroTiles.Info old=retroInfo;
        final RetroTiles.Mode mode=retroMode;
        busy=true;render();summary.setText("Rebuilding raw tile ROM copy...");
        worker.execute(() -> {
            try {
                int page=selectedRetroPage();
                Bitmap png;
                try(InputStream in=getContentResolver().openInputStream(retroPngUri)) {
                    if(in==null)throw new IOException("Cannot open PNG");
                    BitmapFactory.Options opts=new BitmapFactory.Options();
                    opts.inScaled=false;
                    png=BitmapFactory.decodeStream(in,null,opts);
                    if(png==null)throw new IOException("Unsupported PNG");
                }
                int width=png.getWidth(),height=png.getHeight();
                if(width!=mode.width()||height!=mode.height()) {
                    png.recycle(); throw new IOException("Wrong PNG size for "+mode.slug);
                }
                int[] pixels=new int[width*height];png.getPixels(pixels,0,width,0,0,width,height);png.recycle();
                byte[] replacement=RetroTiles.encode(pixels,width,height,mode);
                try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                    if(descriptor==null)throw new IOException("Could not reopen original ROM");
                    try(FileInputStream src=new FileInputStream(descriptor.getFileDescriptor());
                        FileChannel channel=src.getChannel()) {
                        RetroTiles.Info latest=RetroTiles.parse(channel,old.system,getFilename(imageUri));
                        if(latest.length!=old.length || latest.dataOffset!=old.dataOffset ||
                                !latest.title.equals(old.title))
                            throw new IOException("ROM changed since selection; reopen it first");
                        try(OutputStream out=getContentResolver().openOutputStream(destination,"w")) {
                            if(out==null)throw new IOException("Cannot create modified ROM");
                            RetroTiles.rebuild(channel,out,latest,mode,page,replacement);
                        }
                    }
                }
                runOnUiThread(()->{busy=false;render();summary.setText("Modified ROM saved");
                    detail.setText(mode.slug+" page "+page+" patched in a NEW ROM. Original untouched.");
                    new AlertDialog.Builder(this).setTitle("ROM copy saved")
                        .setMessage("Replaced raw tile page "+page+" in a new ROM. " +
                                "Try it in an emulator. If those bytes weren't uncompressed graphics, the ROM may malfunction.")
                        .setPositiveButton("OK",null).show();
                });
            } catch(Exception e) {
                runOnUiThread(()->{busy=false;render();summary.setText("Raw tile rebuild failed");
                    detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("Rebuild failed").setMessage(message(e))
                        .setPositiveButton("OK",null).show();
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
        if (!busy && (archive != null || ndsInfo != null) && !activePath.isEmpty()) {
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
