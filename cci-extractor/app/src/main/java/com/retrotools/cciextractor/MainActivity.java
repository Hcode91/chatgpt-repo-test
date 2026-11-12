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
import android.widget.ImageView;
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
    private static final int PICK_NDS_PNG = 315;
    private static final int SAVE_NDS = 316;
    private static final int PICK_PALETTE = 317;
    private static final int PICK_PAK_FOLDER = 318;
    private static final String DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Uri imageUri;
    private Uri pngUri, templateUri;
    private CciArchive.Info archive;
    private NesCodec.Info nesInfo;
    private NdsArchive.Info ndsInfo;
    private RetroTiles.Info retroInfo;
    private TorusPak.Info pakInfo;
    /** Index of the single bundle to extract, or -1 for the whole PAK. */
    private int pakOnly = -1;
    private RetroTiles.Mode retroMode;
    private int retroPage = -1;
    private Uri retroPngUri;
    private List<NitroVfs.Node> pendingNds;
    private final List<NitroVfs.Node> ndsNodes = new ArrayList<>();
    private final Set<String> expandedNds = new HashSet<>();
    private NitroVfs.Node ndsImportNode;
    private int ndsImportBank = 0;
    private Uri ndsPngUri;
    /** Display palette for raw tile sheets; null = grayscale indexes (the classic export format). */
    private int[] viewPalette;
    private String viewPaletteName = "Grayscale";
    private int lzScanTileBytes = 0;
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
    private Button pngToRetro, retroFormat, paletteButton;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private ConvertTab convertTab;
    private LinearLayout extractPanel;
    private Button tabExtract, tabConvert;

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
    private void showTab(boolean convert) {
        extractPanel.setVisibility(convert ? View.GONE : View.VISIBLE);
        convertTab.view.setVisibility(convert ? View.VISIBLE : View.GONE);
        tabExtract.setAlpha(convert ? 0.6f : 1f);
        tabConvert.setAlpha(convert ? 1f : 0.6f);
        tabExtract.setTypeface(null, convert ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        tabConvert.setTypeface(null, convert ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
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
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackgroundColor(Color.rgb(35, 45, 91));
        tabs.setPadding(dp(6), 0, dp(6), dp(6));
        tabExtract = button("Extract");
        tabConvert = button("Convert");
        tabs.addView(tabExtract, new LinearLayout.LayoutParams(0, dp(46), 1));
        tabs.addView(tabConvert, new LinearLayout.LayoutParams(0, dp(46), 1));
        root.addView(tabs);
        LinearLayout outer = root;
        extractPanel = new LinearLayout(this);
        extractPanel.setOrientation(LinearLayout.VERTICAL);
        outer.addView(extractPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        convertTab = new ConvertTab(this);
        outer.addView(convertTab.view, new LinearLayout.LayoutParams(-1, 0, 1));
        root = extractPanel;
        summary = text("3DS · DS · NES · SNES · Mega Drive · Game Boy · GBA asset tools", 14, Color.DKGRAY);
        root.addView(summary);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(6), 0, dp(6), 0);
        root.addView(controls);
        open = button("Open ROM (3DS / DS / NES / SNES / MD / GB / GBA) or PAK");
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
        LinearLayout tileRow = new LinearLayout(this);
        tileRow.setPadding(dp(6), 0, dp(6), 0);
        root.addView(tileRow);
        retroFormat = button("Tile format");
        paletteButton = button("Palette: Grayscale");
        tileRow.addView(retroFormat, new LinearLayout.LayoutParams(0, dp(48), 1));
        tileRow.addView(paletteButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        pngToRetro = button("PNG → ROM (patch tile page + fix checksum)");
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

        tabExtract.setOnClickListener(v -> showTab(false));
        tabConvert.setOnClickListener(v -> showTab(true));
        showTab(false);
        open.setOnClickListener(v -> openCci());
        pngToBtex.setOnClickListener(v -> choosePng());
        pngToNes.setOnClickListener(v -> startNesImport());
        pngToRetro.setOnClickListener(v -> startRetroImport());
        retroFormat.setOnClickListener(v -> chooseRetroFormat());
        paletteButton.setOnClickListener(v -> choosePalette());
        upscaleHd.setOnClickListener(v -> chooseHdUpscale());
        extractAll.setOnClickListener(v -> {
            if (pakInfo != null) beginPakExtract(-1);
            else if (retroInfo != null) beginRetroExport(-1);
            else if (nesInfo != null) beginNesExport(-1);
            else if (ndsInfo != null) beginNdsExtract(ndsNodes);
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
            if (retroInfo != null || nesInfo != null) {
                showTilePreview(position);
                return;
            }
            if (pakInfo != null) {
                if (position >= pakInfo.hunks.size()) return;
                TorusPak.Hunk hunk = pakInfo.hunks.get(position);
                new AlertDialog.Builder(this).setTitle(hunk.name)
                        .setMessage("Packed size: " + human(hunk.data.storedSize) + "\n\nExtract this bundle?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Extract", (d, w) -> beginPakExtract(position))
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
                    for (NitroVfs.Node node : ndsNodes) if (node.path.equals(full)) { showNdsNode(node); return; }
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
        if (convertTab != null && convertTab.handles(request)) { convertTab.onResult(request, data); return; }
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
            pakInfo = null;
            retroMode = null;
            ndsNodes.clear();
            expandedNds.clear();
            lzScanTileBytes = 0;
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
                                ndsNodes.clear(); ndsNodes.addAll(NitroVfs.roots(parsedNds)); expandedNds.clear();
                                busy=false;
                                summary.setText(parsedNds.description());
                                detail.setText("Tap a .narc/.carc to open it, tap an .ncgr to preview or import a PNG. " +
                                        "Extraction also unpacks NARC archives and LZ-compressed files.");
                                render();
                            });
                        } else if (RetroTiles.isGba(channel) || RetroTiles.isGameBoy(channel) ||
                                RetroTiles.isMegaDrive(channel) || RetroTiles.isSnes(channel,inputName)) {
                            RetroTiles.System platform = RetroTiles.isGba(channel) ? RetroTiles.System.GBA :
                                    RetroTiles.isGameBoy(channel) ? RetroTiles.System.GB :
                                    RetroTiles.isMegaDrive(channel) ? RetroTiles.System.MEGADRIVE : RetroTiles.System.SNES;
                            RetroTiles.Info parsedRetro=RetroTiles.parse(channel,platform,inputName);
                            runOnUiThread(() -> {
                                retroInfo=parsedRetro; retroMode=parsedRetro.defaultMode();
                                archive=null;nesInfo=null;ndsInfo=null;busy=false;
                                summary.setText(parsedRetro.description());
                                detail.setText("Tap a page to preview it. Pages are candidate graphics" +
                                        (parsedRetro.system==RetroTiles.System.GBA ? "; try Tile format → LZ77 for compressed art. " : ". ") +
                                        "Only rebuild pages you've identified as real graphics.");
                                render();
                            });
                        } else if (TorusPak.isPak(channel)) {
                            TorusPak.Info parsedPak = TorusPak.parse(channel);
                            runOnUiThread(() -> {
                                pakInfo = parsedPak; archive = null; nesInfo = null; ndsInfo = null; retroInfo = null;
                                busy = false;
                                summary.setText(parsedPak.description());
                                detail.setText("Extract all, or tap a bundle to extract just that one. Textures are saved " +
                                        "as PNG; models, animations, sounds and other assets are saved as raw data.");
                                render();
                            });
                        } else if (CiaConverter.isCia(channel)) {
                            throw new IOException("This is a CIA, which can't be browsed directly. Go to the Convert tab, " +
                                    "use CIA → CCI (.3ds), then open the .3ds it saves here.");
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
                        busy = false; archive = null; nesInfo = null; ndsInfo = null; retroInfo = null; pakInfo = null;
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
            List<NitroVfs.Node> selection=pendingNds;
            pendingNds=null; extractNds(selection,uri);
        } else if (request == PICK_NDS_PNG && ndsImportNode != null) {
            ndsPngUri = uri;
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            save.addCategory(Intent.CATEGORY_OPENABLE);
            save.setType("application/octet-stream");
            String base = getFilename(imageUri);
            if (base.toLowerCase(Locale.ROOT).endsWith(".nds")) base = base.substring(0, base.length() - 4);
            save.putExtra(Intent.EXTRA_TITLE, base + "_modified.nds");
            save.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(save, SAVE_NDS);
        } else if (request == SAVE_NDS && ndsImportNode != null && ndsPngUri != null) {
            rebuildNds(uri);
        } else if (request == PICK_PAK_FOLDER && pakInfo != null) {
            extractPak(uri);
        } else if (request == PICK_PALETTE) {
            loadPaletteFile(uri);
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

    private List<NitroVfs.Node> ndsEntriesIn(String prefix) {
        if(ndsInfo==null) return null;
        List<NitroVfs.Node> result=new ArrayList<>();
        String base=prefix.isEmpty()?"":prefix+"/";
        for(NitroVfs.Node e:ndsNodes) if(e.path.startsWith(base))result.add(e);
        return result;
    }
    private void beginNdsExtract(List<NitroVfs.Node> selection) {
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
                displayed.add(retroMode.compressed ?
                        String.format(Locale.ROOT,"📄 LZ77 block %04d @0x%06X · %d tiles",i,
                                retroInfo.lzBlocks.get(i).offset,retroInfo.lzBlocks.get(i).size/retroMode.tileBytes) :
                        String.format(Locale.ROOT,"📄 %s page %04d",retroMode.slug,i));
        }
        if (pakInfo != null) {
            for (TorusPak.Hunk h : pakInfo.hunks)
                displayed.add("📦 " + h.name + " · " + human(h.data.storedSize) + " packed");
        }
        if (archive != null || ndsInfo != null) {
            String prefix = activePath.isEmpty() ? "" : activePath + "/";
            Set<String> directories = new TreeSet<>();
            Set<String> files = new TreeSet<>();
            List<String> paths=new ArrayList<>();
            if(archive!=null)for(CciArchive.Entry e:archive.entries)paths.add(e.path);
            if(ndsInfo!=null)for(NitroVfs.Node e:ndsNodes)paths.add(e.path);
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
        if (pakInfo != null) location.setText("/PAK   (" + displayed.size() + " bundles)");
        open.setEnabled(!busy);
        pngToBtex.setEnabled(!busy);
        upscaleHd.setEnabled(!busy);
        pngToNes.setEnabled(!busy && nesInfo != null && !nesInfo.usesChrRam());
        pngToRetro.setEnabled(!busy && retroInfo != null);
        boolean multiFormat = retroInfo!=null && RetroTiles.Mode.forSystem(retroInfo.system).size()>1;
        retroFormat.setEnabled(!busy && multiFormat);
        retroFormat.setVisibility(multiFormat?View.VISIBLE:View.GONE);
        paletteButton.setEnabled(!busy);
        paletteButton.setVisibility(retroInfo!=null || nesInfo!=null?View.VISIBLE:View.GONE);
        paletteButton.setText("Palette: "+viewPaletteName);
        pngToRetro.setVisibility(retroInfo!=null?View.VISIBLE:View.GONE);
        if(retroMode!=null)retroFormat.setText("Format: "+retroMode.slug);
        extractAll.setText((nesInfo != null || retroInfo != null) ? "Export all tile PNGs" : "Extract all");
        extractAll.setEnabled(!busy && (archive != null || ndsInfo != null || nesInfo != null || retroInfo!=null || pakInfo != null));
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
            final AssetExpander.Stats expanded = new AssetExpander.Stats();
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
                        if (!cancelled.get() && e.size >= 8 && e.size <= AssetExpander.MAX_INPUT) {
                            // Archives, compressed files, textures and audio in known formats are unpacked/converted
                            // next to the original. A file that can't be parsed is simply left as it is.
                            try {
                                byte[] head = readBtex(channel, e.offset, (int) Math.min(16, e.size));
                                byte[] tail = e.size >= 0x28 ? readBtex(channel, e.offset + e.size - 0x28, 0x28) : null;
                                if (AssetExpander.worthReading(head, tail, e.size))
                                    AssetExpander.expand(filename, readBtex(channel, e.offset, (int) e.size),
                                            expanderSink(path, folders), expanded);
                            } catch (Exception | OutOfMemoryError unreadable) { /* original already saved */ }
                        }
                        count++;
                        final int done = count;
                        final int pngDone = converted + expanded.images;
                        final String lastName = filename;
                        if (count % 5 == 0 || count == selection.size()) {
                            runOnUiThread(() -> detail.setText(done + "/" + selection.size() +
                                    " files · " + pngDone + " PNGs · " + lastName));
                        }
                    }
                }
                final int complete = count, pngs = converted, skipped = failed;
                final long bytes = written;
                final String extras = expanded.total() == 0 ? "" : "\n\nAlso unpacked " + expanded.unpacked +
                        " file(s) from archives into *_contents folders, decompressed " + expanded.decompressed +
                        ", and converted " + expanded.images + " more texture(s) to PNG and " + expanded.sounds +
                        " sound(s) to WAV.";
                final boolean interrupted = cancelled.get();
                runOnUiThread(() -> {
                    busy = false;
                    summary.setText((interrupted ? "Stopped: " : "Finished: ") + complete +
                            " files (" + human(bytes) + ") · " + (pngs + expanded.images) + " textures → PNG" +
                            (expanded.sounds > 0 ? " · " + expanded.sounds + " sounds → WAV" : ""));
                    detail.setText("Original BTEX templates are retained beside each PNG. " +
                            (archive.titleId == null ? "No Title ID in header; original partition paths were kept."
                                : "For Azahar mods, copy " + archive.titleId + " folder from this extraction.") +
                            (skipped > 0 ? " " + skipped + " textures could not be converted." : ""));
                    render();
                    new AlertDialog.Builder(this).setTitle(interrupted ? "Extraction cancelled" : "Extraction complete")
                            .setMessage(complete + " original file(s) extracted. " + pngs +
                                    " BTEX texture(s) converted to PNG." + (skipped > 0 ?
                                    " " + skipped + " texture(s) could not be converted; originals preserved." : "") +
                                    extras +
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

    /** Extract NitroFS files, unpacking NARC archives and LZ files; never modifies the selected .nds ROM. */
    private void extractNds(List<NitroVfs.Node> chosen, Uri tree) {
        if(busy || ndsInfo==null) return;
        // Skip nodes whose ancestor is also selected: the ancestor's unpacking already covers them.
        Set<NitroVfs.Node> chosenSet=new HashSet<>(chosen);
        List<NitroVfs.Node> selection=new ArrayList<>();
        for(NitroVfs.Node n:chosen) {
            boolean covered=false;
            for(NitroVfs.Node p=n.parent;p!=null;p=p.parent) if(chosenSet.contains(p)) {covered=true;break;}
            if(!covered) selection.add(n);
        }
        final List<NitroVfs.Node> known=new ArrayList<>(ndsNodes);
        busy=true;cancelled.set(false);render();summary.setText("Extracting DS files and unpacking archives...");
        worker.execute(() -> {
            int[] stats=new int[5]; // files, pngs, unpacked children, unconverted graphics, sounds
            try (ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                if(descriptor==null)throw new IOException("Cannot open DS ROM");
                try(FileInputStream source=new FileInputStream(descriptor.getFileDescriptor());
                    FileChannel channel=source.getChannel()) {
                    NitroVfs.Reader reader=NitroVfs.channelReader(channel);
                    NitroVfs.Cache cache=new NitroVfs.Cache();
                    Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                    String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
                    Uri folder=DocumentsContract.createDocument(getContentResolver(),root,DIR_MIME,"NDS_Extract_"+stamp);
                    if(folder==null)throw new IOException("Cannot create DS output folder");
                    Map<String,Uri> folders=new HashMap<>();folders.put("",folder);
                    byte[] buffer=new byte[65536];
                    for(NitroVfs.Node node:selection) {
                        if(cancelled.get())break;
                        Uri dir=ensureFolder(node.folder(),folders);
                        byte[] content=null;
                        if(node.parent==null) {
                            // Stream the original bytes, then decide whether it's worth unpacking.
                            NdsArchive.Entry e=node.romEntry;
                            Uri file=DocumentsContract.createDocument(getContentResolver(),dir,"application/octet-stream",node.name());
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
                            if(worthUnpacking(node,channel)) {
                                try {
                                    content=NitroVfs.content(node,reader,cache);
                                    if(node.compression!=NitroLz.TYPE_NONE && !node.isContainer())
                                        writeDoc(dir,node.name()+".decompressed","application/octet-stream",content);
                                } catch(Exception unreadable) { content=null; }
                            }
                        } else {
                            content=NitroVfs.content(node,reader,cache);
                            writeDoc(dir,node.name(),"application/octet-stream",content);
                        }
                        stats[0]++;
                        if(content!=null) unpackExtras(node,content,dir,known,reader,cache,folders,stats);
                        final int progress=stats[0];
                        if(stats[0]%10==0 || stats[0]==selection.size())runOnUiThread(() ->
                                detail.setText(progress+"/"+selection.size()+" files · "+stats[1]+" PNGs · "+stats[2]+" unpacked"+
                                        (stats[4]>0?" · "+stats[4]+" WAVs":"")));
                    }
                }
                final boolean wasCancelled=cancelled.get();
                runOnUiThread(() -> {busy=false;render();
                    summary.setText("Nintendo DS: "+stats[0]+" files, "+stats[2]+" unpacked, "+stats[1]+" PNGs"+
                            (stats[4]>0?", "+stats[4]+" WAVs":"")+
                            (wasCancelled?" (cancelled)":""));
                    detail.setText("Original DS ROM preserved. NARC contents go in *_contents folders; LZ files get a " +
                            ".decompressed copy; .ncgr graphics get PNG previews using the nearest .nclr palette.");
                    new AlertDialog.Builder(this).setTitle("DS extraction complete")
                            .setMessage(stats[0]+" files copied into NDS_Extract_.../nitrofs.\n"+
                                    stats[2]+" archive/compressed entries unpacked.\n"+
                                    stats[1]+" graphics converted to PNG (tiles, tilemap screens, NSBTX textures).\n"+
                                    stats[4]+" sounds converted to WAV (SDAT wave archives and streams).\n"+
                                    stats[3]+" graphics skipped (unsupported format).\n\nOriginal ROM unchanged.")
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

    /** Only read whole files into memory when they look like Nitro containers, graphics or LZ data. */
    private boolean worthUnpacking(NitroVfs.Node node, FileChannel channel) throws IOException {
        if(node.rawSize<5 || node.rawSize>NitroVfs.MAX_NODE_BYTES) return false;
        if(!node.kind.isEmpty()) return true;
        byte[] head=readBtex(channel,node.romEntry.offset,4);
        int t=head[0]&255;
        return t==NitroLz.TYPE_LZ10 || t==NitroLz.TYPE_LZ11 || !NitroVfs.kindFromMagic(head).isEmpty()
                || AssetExpander.worthReading(head,null,node.rawSize) || (head[0]=='R'&&head[1]=='C'&&head[2]=='S'&&head[3]=='N');
    }

    /** Writes {@link AssetExpander} output below {@code basePath} (a folder path already known to {@code folders}). */
    private AssetExpander.Sink expanderSink(final String basePath, final Map<String, Uri> folders) {
        return new AssetExpander.Sink() {
            private Uri dirFor(String relative) throws IOException {
                int slash = relative.lastIndexOf('/');
                String sub = slash < 0 ? "" : relative.substring(0, slash);
                String full = sub.isEmpty() ? basePath : basePath.isEmpty() ? sub : basePath + "/" + sub;
                try { return ensureFolder(full, folders); }
                catch (IOException e) { throw e; }
                catch (Exception e) { throw new IOException(String.valueOf(e.getMessage())); }
            }
            @Override public void file(String path, byte[] data) throws IOException {
                writeDoc(dirFor(path), path.substring(path.lastIndexOf('/') + 1), "application/octet-stream", data);
            }
            @Override public void png(String path, int width, int height, int[] argb) throws IOException {
                writePng(dirFor(path), path.substring(path.lastIndexOf('/') + 1), width, height, argb);
            }
            @Override public boolean cancelled() { return cancelled.get(); }
        };
    }

    private void writeDoc(Uri dir,String name,String mime,byte[] data) throws IOException {
        Uri file=DocumentsContract.createDocument(getContentResolver(),dir,mime,name);
        if(file==null) throw new IOException("Cannot create "+name);
        try(OutputStream out=getContentResolver().openOutputStream(file,"w")) {
            if(out==null) throw new IOException("Cannot write "+name);
            out.write(data);
        }
    }

    private void writePng(Uri dir,String name,int width,int height,int[] argb) throws IOException {
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        bitmap.setPixels(argb,0,width,0,0,width,height);
        Uri file=DocumentsContract.createDocument(getContentResolver(),dir,"image/png",name);
        if(file==null) {bitmap.recycle(); throw new IOException("Cannot create "+name);}
        try(OutputStream out=getContentResolver().openOutputStream(file,"w")) {
            if(out==null || !bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("PNG encoding failed");
        } finally {bitmap.recycle();}
    }

    /** PNG previews for graphics and recursive unpacking of NARC contents. */
    private void unpackExtras(NitroVfs.Node node,byte[] content,Uri dir,List<NitroVfs.Node> siblings,
                              NitroVfs.Reader reader,NitroVfs.Cache cache,Map<String,Uri> folders,int[] stats) {
        if(cancelled.get()) return;
        try {
            if(node.isGraphic()) {
                NitroVfs.Node pal=NitroVfs.pairPalette(node,siblings);
                try {
                    byte[] palette=pal==null?NdsGraphics.grayNclr(NdsGraphics.layout(content).depth):NitroVfs.content(pal,reader,cache);
                    NdsGraphics.Image im=NdsGraphics.decode(content,palette,0);
                    String stem=node.name().contains(".")?node.name().substring(0,node.name().lastIndexOf('.')):node.name();
                    writePng(dir,stem+(pal==null?"_gray.png":".png"),im.width,im.height,im.argb);
                    stats[1]++;
                } catch(Exception unsupported) {stats[3]++;}
            }
            if(NdsMedia.isNscr(content)) {
                renderScreen(node,content,dir,siblings,reader,cache,stats);
            } else if(!node.isGraphic() && !node.isPalette() && !node.isContainer()) {
                // NSBTX/NSBMD textures, SDAT sound archives, SWAR/SWAV/STRM audio.
                AssetExpander.Stats extra=new AssetExpander.Stats();
                AssetExpander.expandDecompressed(node.name(),content,expanderSink(node.folder(),folders),extra);
                stats[1]+=extra.images; stats[2]+=extra.unpacked; stats[4]+=extra.sounds;
            }
            if(node.isContainer() && NarcArchive.isNarc(content)) {
                List<NitroVfs.Node> kids=NitroVfs.expand(node,content);
                if(kids.isEmpty()) return;
                Uri sub=ensureFolder(node.path+NitroVfs.CONTENTS_SUFFIX,folders);
                NarcArchive.Archive archive=NarcArchive.parse(content);
                for(NitroVfs.Node kid:kids) {
                    if(cancelled.get()) return;
                    byte[] raw=archive.file(kid.childIndex);
                    byte[] kidContent=kid.compression==NitroLz.TYPE_NONE?raw:NitroLz.decompress(raw,0,NitroLz.MAX_OUTPUT).data;
                    kid.probed=true;
                    Uri kidDir=kid.folder().equals(node.path+NitroVfs.CONTENTS_SUFFIX)?sub:ensureFolder(kid.folder(),folders);
                    writeDoc(kidDir,kid.name(),"application/octet-stream",kidContent);
                    stats[2]++;
                    unpackExtrasChild(kid,kidContent,kidDir,kids,reader,cache,folders,stats);
                }
            }
        } catch(Exception skipped) {
            // An unreadable archive never aborts the whole extraction; its raw file is already saved.
        }
    }
    /** NSCR tilemap → assembled PNG, using the matching (or nearest) NCGR tile sheet and its palette. */
    private void renderScreen(NitroVfs.Node node,byte[] nscr,Uri dir,List<NitroVfs.Node> siblings,
                              NitroVfs.Reader reader,NitroVfs.Cache cache,int[] stats) {
        try {
            String stem=node.path.contains(".")?node.path.substring(0,node.path.lastIndexOf('.')):node.path;
            NitroVfs.Node tiles=null;
            for(NitroVfs.Node n:siblings) {
                if(!n.isGraphic() || !n.folder().equals(node.folder())) continue;
                String other=n.path.contains(".")?n.path.substring(0,n.path.lastIndexOf('.')):n.path;
                if(other.equalsIgnoreCase(stem)) { tiles=n; break; }
                if(tiles==null) tiles=n;
            }
            if(tiles==null) { stats[3]++; return; }
            byte[] ncgr=NitroVfs.content(tiles,reader,cache);
            NdsGraphics.Layout layout=NdsGraphics.layout(ncgr);
            NitroVfs.Node pal=NitroVfs.pairPalette(tiles,siblings);
            byte[] palette=pal==null?NdsGraphics.grayNclr(layout.depth):NitroVfs.content(pal,reader,cache);
            int available=NdsGraphics.paletteBanks(palette,layout.depth);
            int[][] sheets=new int[16][];
            for(int bank:NdsMedia.nscrBanks(nscr)) {
                if(bank>=available) continue;
                try { sheets[bank]=NdsGraphics.decode(ncgr,palette,bank).argb; } catch(Exception missingBank) { sheets[bank]=null; }
            }
            boolean any=false;
            for(int[] sheet:sheets) if(sheet!=null) { any=true; break; }
            if(!any) sheets[0]=NdsGraphics.decode(ncgr,palette,0).argb;
            NdsMedia.Texture screen=NdsMedia.renderNscr(nscr,sheets,layout.width,layout.height);
            String name=node.name().contains(".")?node.name().substring(0,node.name().lastIndexOf('.')):node.name();
            writePng(dir,name+"_screen.png",screen.width,screen.height,screen.argb);
            stats[1]++;
        } catch(Exception unsupported) { stats[3]++; }
    }
    private void unpackExtrasChild(NitroVfs.Node kid,byte[] content,Uri dir,List<NitroVfs.Node> siblings,
                                   NitroVfs.Reader reader,NitroVfs.Cache cache,Map<String,Uri> folders,int[] stats) {
        // Children's palettes are their siblings; read them from the parent archive bytes via the cache.
        unpackExtras(kid,content,dir,siblings,reader,cache,folders,stats);
    }

    /** Tap on a DS file: describe it and offer unpack / preview / extract. */
    private void showNdsNode(NitroVfs.Node node) {
        busy=true;render();detail.setText("Reading "+node.name()+"...");
        worker.execute(() -> {
            try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                if(descriptor==null) throw new IOException("Cannot open DS ROM");
                try(FileInputStream source=new FileInputStream(descriptor.getFileDescriptor());
                    FileChannel channel=source.getChannel()) {
                    String info;
                    if(node.rawSize<=NitroVfs.MAX_NODE_BYTES) {
                        byte[] content=NitroVfs.content(node,NitroVfs.channelReader(channel),new NitroVfs.Cache());
                        info=node.describe()+"\nStored size: "+human(node.rawSize)+
                                (content.length!=node.rawSize?"\nUnpacked size: "+human(content.length):"");
                        if(node.isContainer()) info+="\nFiles inside: "+NarcArchive.parse(content).count();
                    } else info="Large file · "+human(node.rawSize);
                    final String message=info;
                    runOnUiThread(() -> {
                        busy=false;render();detail.setText(node.path);
                        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(node.name()).setMessage(message)
                                .setNegativeButton("Extract",(d,w)->beginNdsExtract(Collections.singletonList(node)));
                        if(node.isContainer()) b.setPositiveButton("Open contents",(d,w)->expandNds(node));
                        else if(node.isGraphic()) b.setPositiveButton("Preview / import PNG",(d,w)->showNcgrPreview(node,0));
                        else b.setPositiveButton("Close",null);
                        b.show();
                    });
                }
            } catch(Exception e) {
                runOnUiThread(() -> {busy=false;render();detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle(node.name()).setMessage("Could not read: "+message(e))
                            .setPositiveButton("OK",null).show();});
            }
        });
    }

    private void expandNds(NitroVfs.Node node) {
        String folder=node.path+NitroVfs.CONTENTS_SUFFIX;
        if(expandedNds.contains(node.path)) {activePath=folder;render();return;}
        busy=true;render();detail.setText("Unpacking "+node.name()+"...");
        worker.execute(() -> {
            try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                if(descriptor==null) throw new IOException("Cannot open DS ROM");
                try(FileInputStream source=new FileInputStream(descriptor.getFileDescriptor());
                    FileChannel channel=source.getChannel()) {
                    byte[] content=NitroVfs.content(node,NitroVfs.channelReader(channel),new NitroVfs.Cache());
                    List<NitroVfs.Node> kids=NitroVfs.expand(node,content);
                    runOnUiThread(() -> {
                        busy=false;
                        if(kids.isEmpty()) {render();detail.setText("Nothing to unpack in "+node.name());return;}
                        ndsNodes.addAll(kids);expandedNds.add(node.path);
                        activePath=folder;render();
                        detail.setText(kids.size()+" files inside "+node.name()+
                                ". Unnamed files are numbered and given an extension from their contents.");
                    });
                }
            } catch(Exception e) {
                runOnUiThread(() -> {busy=false;render();detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("Cannot unpack").setMessage(message(e))
                            .setPositiveButton("OK",null).show();});
            }
        });
    }

    private ImageView previewImage(Bitmap bitmap) {
        ImageView view=new ImageView(this);
        int scale=Math.max(1,Math.min(4,(getResources().getDisplayMetrics().widthPixels-dp(64))/Math.max(1,bitmap.getWidth())));
        Bitmap big=Bitmap.createScaledBitmap(bitmap,bitmap.getWidth()*scale,bitmap.getHeight()*scale,false);
        view.setImageBitmap(big);
        view.setAdjustViewBounds(true);
        view.setPadding(dp(8),dp(8),dp(8),dp(8));
        return view;
    }

    /** Preview an NCGR with its paired palette; cycle palette banks; import an edited PNG into a new ROM. */
    private void showNcgrPreview(NitroVfs.Node node,int bank) {
        busy=true;render();
        worker.execute(() -> {
            try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                if(descriptor==null) throw new IOException("Cannot open DS ROM");
                try(FileInputStream source=new FileInputStream(descriptor.getFileDescriptor());
                    FileChannel channel=source.getChannel()) {
                    NitroVfs.Reader reader=NitroVfs.channelReader(channel);
                    NitroVfs.Cache cache=new NitroVfs.Cache();
                    byte[] ncgr=NitroVfs.content(node,reader,cache);
                    NitroVfs.Node palNode=NitroVfs.pairPalette(node,ndsNodes);
                    NdsGraphics.Layout layout=NdsGraphics.layout(ncgr);
                    byte[] nclr=palNode==null?NdsGraphics.grayNclr(layout.depth):NitroVfs.content(palNode,reader,cache);
                    int banks=NdsGraphics.paletteBanks(nclr,layout.depth);
                    int useBank=bank%Math.max(1,banks);
                    NdsGraphics.Image im=NdsGraphics.decode(ncgr,nclr,useBank);
                    Bitmap bitmap=Bitmap.createBitmap(im.width,im.height,Bitmap.Config.ARGB_8888);
                    bitmap.setPixels(im.argb,0,im.width,0,0,im.width,im.height);
                    runOnUiThread(() -> {
                        busy=false;render();
                        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
                        box.addView(previewImage(bitmap));
                        box.addView(text(im.width+"×"+im.height+" · "+(layout.depth==3?"4bpp":"8bpp")+" · palette: "+
                                (palNode==null?"none found (grayscale)":palNode.name()+(banks>1?" bank "+useBank+"/"+(banks-1):"")),
                                13,Color.DKGRAY));
                        box.addView(text("To edit: Extract to get the PNG, edit it keeping the same size and colours, " +
                                "then Import. The new ROM is a separate copy.",12,Color.GRAY));
                        ScrollView scroll=new ScrollView(this);scroll.addView(box);
                        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(node.name()).setView(scroll)
                                .setNegativeButton("Close",null)
                                .setPositiveButton("Import PNG…",(d,w)->{
                                    ndsImportNode=node;ndsImportBank=useBank;ndsPngUri=null;
                                    Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                    intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/png");
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    startActivityForResult(intent,PICK_NDS_PNG);
                                });
                        if(banks>1) b.setNeutralButton("Next palette bank",(d,w)->showNcgrPreview(node,useBank+1));
                        b.show();
                    });
                }
            } catch(Exception e) {
                runOnUiThread(() -> {busy=false;render();
                    new AlertDialog.Builder(this).setTitle(node.name()).setMessage("Cannot preview: "+message(e))
                            .setPositiveButton("OK",null).show();});
            }
        });
    }

    private int[] readPngPixels(Uri png,int[] size) throws IOException {
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inScaled=false;opts.inPremultiplied=false;opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try(InputStream in=getContentResolver().openInputStream(png)) {
            if(in==null) throw new IOException("Cannot open PNG");
            bitmap=BitmapFactory.decodeStream(in,null,opts);
        }
        if(bitmap==null) throw new IOException("Could not decode PNG");
        try {
            size[0]=bitmap.getWidth();size[1]=bitmap.getHeight();
            if((long)size[0]*size[1]>16_000_000L) throw new IOException("PNG too large");
            int[] px=new int[size[0]*size[1]];
            bitmap.getPixels(px,0,size[0],0,0,size[0],size[1]);
            return px;
        } finally {bitmap.recycle();}
    }

    /** PNG → NCGR → (re-compress, rebuild NARCs) → new .nds copy. */
    private void rebuildNds(Uri destination) {
        final NitroVfs.Node node=ndsImportNode;final int bank=ndsImportBank;final Uri png=ndsPngUri;
        final List<NitroVfs.Node> known=new ArrayList<>(ndsNodes);
        busy=true;render();summary.setText("Building modified DS ROM...");
        worker.execute(() -> {
            try {
                int[] size=new int[2];
                int[] pixels=readPngPixels(png,size);
                String result;int inexact;
                try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                    if(descriptor==null) throw new IOException("Cannot open DS ROM");
                    try(FileInputStream source=new FileInputStream(descriptor.getFileDescriptor());
                        FileChannel channel=source.getChannel()) {
                        NitroVfs.Reader reader=NitroVfs.channelReader(channel);
                        NitroVfs.Cache cache=new NitroVfs.Cache();
                        byte[] ncgr=NitroVfs.content(node,reader,cache);
                        NitroVfs.Node palNode=NitroVfs.pairPalette(node,known);
                        byte[] nclr=palNode==null?NdsGraphics.grayNclr(NdsGraphics.layout(ncgr).depth):NitroVfs.content(palNode,reader,cache);
                        NdsGraphics.Encoded enc=NdsGraphics.encode(pixels,size[0],size[1],ncgr,nclr,bank);
                        inexact=enc.inexact;
                        NitroVfs.Patch patch=NitroVfs.replace(node,enc.ncgr,reader);
                        try(OutputStream out=getContentResolver().openOutputStream(destination,"w")) {
                            if(out==null) throw new IOException("Cannot write new ROM");
                            result=NitroVfs.writeRom(channel,out,patch);
                        }
                    }
                }
                final String how=result;final int off=inexact;
                runOnUiThread(() -> {busy=false;render();summary.setText("Modified DS ROM saved");
                    detail.setText(node.name()+" imported; "+how+". Original ROM untouched.");
                    new AlertDialog.Builder(this).setTitle("DS ROM copy saved")
                            .setMessage(node.name()+" was replaced inside a NEW .nds file ("+how+"). " +
                                    (node.compression!=NitroLz.TYPE_NONE||node.parent!=null?
                                            "Compression and archives were rebuilt automatically. ":"") +
                                    (off>0?off+" pixel(s) weren't exact palette colours and were snapped to the nearest one. ":"") +
                                    "Test it in an emulator such as melonDS.")
                            .setPositiveButton("OK",null).show();});
            } catch(Exception e) {
                runOnUiThread(() -> {busy=false;render();summary.setText("DS import failed");detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("DS import failed").setMessage(message(e))
                            .setPositiveButton("OK",null).show();});
            }
        });
    }

    private void chooseRetroFormat() {
        if(retroInfo==null || busy)return;
        List<RetroTiles.Mode> modes=RetroTiles.Mode.forSystem(retroInfo.system);
        if(modes.size()<2) return;
        String[] labels=new String[modes.size()];
        for(int i=0;i<labels.length;i++) labels[i]=modes.get(i).label();
        new AlertDialog.Builder(this).setTitle("Select "+retroInfo.systemName()+" tile format")
                .setSingleChoiceItems(labels,modes.indexOf(retroMode),
                        (d,which)->{d.dismiss();setRetroMode(modes.get(which));})
                .setNegativeButton("Cancel",null).show();
    }

    private void setRetroMode(RetroTiles.Mode mode) {
        if(!mode.compressed) {retroMode=mode;render();return;}
        if(lzScanTileBytes==mode.tileBytes) {retroMode=mode;render();return;}
        final RetroTiles.Info info=retroInfo;
        busy=true;render();summary.setText("Scanning ROM for LZ77-compressed graphics...");
        worker.execute(() -> {
            try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                if(descriptor==null) throw new IOException("Cannot read ROM");
                byte[] rom;
                try(FileInputStream src=new FileInputStream(descriptor.getFileDescriptor());
                    FileChannel channel=src.getChannel()) {
                    if(channel.size()>RetroTiles.MAX_REBUILD_BYTES) throw new IOException("ROM too large to scan");
                    rom=readBtexLarge(channel,(int)channel.size());
                }
                List<RetroTiles.LzBlock> blocks=RetroTiles.scanLz77(rom,mode.tileBytes,4096);
                runOnUiThread(() -> {
                    busy=false;info.lzBlocks=blocks;lzScanTileBytes=mode.tileBytes;retroMode=mode;
                    summary.setText(info.description());
                    detail.setText(blocks.size()+" LZ77 blocks that decompress to whole "+(mode.bpp)+"bpp tiles. " +
                            "Some may be maps or other data rather than graphics.");
                    render();
                });
            } catch(Exception e) {
                runOnUiThread(() -> {busy=false;render();summary.setText("LZ77 scan failed");detail.setText(message(e));});
            }
        });
    }

    private static byte[] readBtexLarge(FileChannel channel,int size) throws IOException {
        ByteBuffer b=ByteBuffer.allocate(size);
        while(b.hasRemaining()) {
            int n=channel.read(b,b.position());
            if(n<=0) throw new IOException("Unexpected end of ROM");
        }
        return b.array();
    }

    private int previewBpp() {
        if(retroMode!=null) return retroMode.bpp;
        return 2; // NES
    }

    private void choosePalette() {
        if(busy) return;
        String[] options=new String[Palettes.PRESETS.length+1];
        System.arraycopy(Palettes.PRESETS,0,options,0,Palettes.PRESETS.length);
        options[options.length-1]="Load palette file… (.pal / .gpl / .act / .nclr / BGR555)";
        new AlertDialog.Builder(this).setTitle("Tile palette")
                .setItems(options,(d,which)->{
                    if(which==options.length-1) {
                        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivityForResult(intent,PICK_PALETTE);
                        return;
                    }
                    if(which==0) {viewPalette=null;viewPaletteName="Grayscale";}
                    else {viewPalette=Palettes.preset(which,8);viewPaletteName=Palettes.PRESETS[which];}
                    // Presets are generated at 256 entries; re-fit per sheet depth when used.
                    if(which>0) viewPaletteName=Palettes.PRESETS[which];
                    presetIndex=which;
                    render();
                    detail.setText("Palette changes previews and exported PNGs. Import with the SAME palette selected.");
                }).setNegativeButton("Cancel",null).show();
    }
    private int presetIndex=0;

    /** Palette for a given bit depth: presets are regenerated per depth, loaded files are used as-is. */
    private int[] paletteFor(int bpp) {
        if(viewPalette==null) return null;
        if(presetIndex>0) return Palettes.preset(presetIndex,bpp);
        return viewPalette;
    }

    private void loadPaletteFile(Uri uri) {
        try(InputStream in=getContentResolver().openInputStream(uri)) {
            if(in==null) throw new IOException("Cannot open palette");
            byte[] data=readBytes(in,1024*1024);
            int[] pal=Palettes.parse(data,getFilename(uri));
            viewPalette=pal;presetIndex=0;
            String name=getFilename(uri);
            viewPaletteName=name.length()>18?name.substring(0,18)+"…":name;
            render();
            detail.setText("Loaded "+pal.length+" colours from "+name+". Import edited PNGs with this palette still selected.");
        } catch(Exception e) {
            new AlertDialog.Builder(this).setTitle("Palette not loaded").setMessage(message(e))
                    .setPositiveButton("OK",null).show();
        }
    }

    /** Decoded, palette-coloured sheet for a retro or NES page: [width, height] in size. */
    private int[] renderSheet(FileChannel channel,int page,int[] size) throws IOException {
        if(nesInfo!=null) {
            int[] gray=NesCodec.decodeTiles(NesCodec.readPage(channel,nesInfo,page));
            size[0]=128;size[1]=128;
            return Palettes.recolour(gray,2,paletteFor(2));
        }
        byte[] data=RetroTiles.readPage(channel,retroInfo,retroMode,page);
        int[] gray=RetroTiles.decodeAny(data,retroMode);
        size[0]=retroMode.width();size[1]=retroMode.heightFor(data.length);
        return Palettes.recolour(gray,retroMode.bpp,paletteFor(retroMode.bpp));
    }

    private int previewPageCount() {
        if(nesInfo!=null) return nesInfo.pageCount();
        return retroInfo==null||retroMode==null?0:retroInfo.pageCount(retroMode);
    }

    /** In-app tile sheet viewer with previous/next paging and palette switching. */
    private void showTilePreview(int startPage) {
        final int[] page={startPage};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        ImageView image=new ImageView(this);
        image.setAdjustViewBounds(true);image.setPadding(dp(8),dp(8),dp(8),dp(8));
        TextView caption=text("",13,Color.DKGRAY);
        LinearLayout nav=new LinearLayout(this);
        Button prev=button("◀ Prev"),next=button("Next ▶"),pal=button("Palette");
        nav.addView(prev,new LinearLayout.LayoutParams(0,dp(46),1));
        nav.addView(pal,new LinearLayout.LayoutParams(0,dp(46),1));
        nav.addView(next,new LinearLayout.LayoutParams(0,dp(46),1));
        box.addView(image);box.addView(caption);box.addView(nav);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        Runnable draw=() -> {
            int count=previewPageCount();
            if(count==0) return;
            page[0]=Math.max(0,Math.min(count-1,page[0]));
            try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                if(descriptor==null) throw new IOException("Cannot read ROM");
                try(FileInputStream src=new FileInputStream(descriptor.getFileDescriptor());
                    FileChannel channel=src.getChannel()) {
                    int[] size=new int[2];
                    int[] px=renderSheet(channel,page[0],size);
                    Bitmap bmp=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
                    bmp.setPixels(px,0,size[0],0,0,size[0],size[1]);
                    int scale=Math.max(1,Math.min(4,(getResources().getDisplayMetrics().widthPixels-dp(80))/size[0]));
                    image.setImageBitmap(Bitmap.createScaledBitmap(bmp,size[0]*scale,size[1]*scale,false));
                    bmp.recycle();
                    String where;
                    if(nesInfo!=null) where=(nesInfo.usesChrRam()?"PRG candidate":"CHR")+" page "+page[0];
                    else where=retroMode.slug+" page "+page[0]+" @0x"+
                            Long.toHexString(retroInfo.pageOffset(retroMode,page[0])).toUpperCase(Locale.ROOT);
                    caption.setText(where+" of "+(count-1)+" · palette: "+viewPaletteName+
                            "\nRaw ROM bytes shown as tiles: may be code or data rather than graphics.");
                }
            } catch(Exception e) {caption.setText("Cannot draw page: "+message(e));}
            prev.setEnabled(page[0]>0);next.setEnabled(page[0]<count-1);
        };
        prev.setOnClickListener(v->{page[0]--;draw.run();});
        next.setOnClickListener(v->{page[0]++;draw.run();});
        pal.setOnClickListener(v->{
            String[] options=Palettes.PRESETS;
            new AlertDialog.Builder(this).setTitle("Preview palette").setItems(options,(d,which)->{
                presetIndex=which;
                viewPalette=which==0?null:Palettes.preset(which,8);
                viewPaletteName=which==0?"Grayscale":Palettes.PRESETS[which];
                render();draw.run();
            }).show();
        });
        draw.run();
        new AlertDialog.Builder(this).setTitle(nesInfo!=null?"NES tile viewer":retroInfo.systemName()+" tile viewer")
                .setView(scroll)
                .setNegativeButton("Close",null)
                .setPositiveButton("Export this page",(d,w)->{
                    if(nesInfo!=null) beginNesExport(page[0]); else beginRetroExport(page[0]);
                }).show();
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
        final int[] exportPalette=paletteFor(mode.bpp);
        final String exportPaletteName=viewPaletteName;
        busy=true;cancelled.set(false);render();summary.setText("Exporting "+mode.slug+" tiles...");
        worker.execute(() -> {
            int done=0;
            try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                if(descriptor==null)throw new IOException("Cannot read selected ROM");
                try(FileInputStream src=new FileInputStream(descriptor.getFileDescriptor());
                    FileChannel channel=src.getChannel()) {
                    Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                    String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
                    Uri folder=DocumentsContract.createDocument(getContentResolver(),root,DIR_MIME,
                            info.system.name()+"_TileScans_"+stamp);
                    if(folder==null)throw new IOException("Cannot create scan output folder");
                    for(int page=0;page<info.pageCount(mode);page++) {
                        if(single>=0 && single!=page)continue;
                        if(cancelled.get())break;
                        byte[] data=RetroTiles.readPage(channel,info,mode,page);
                        int sheetHeight=mode.heightFor(data.length);
                        int[] pixels=Palettes.recolour(RetroTiles.decodeAny(data,mode),mode.bpp,exportPalette);
                        Bitmap image=Bitmap.createBitmap(mode.width(),sheetHeight,Bitmap.Config.ARGB_8888);
                        image.setPixels(pixels,0,mode.width(),0,0,mode.width(),sheetHeight);
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
                                "Display palette: "+exportPaletteName+" (not the game's own colours).\n"+
                                "Re-import with the SAME palette selected in the app, at the same size\n"+
                                "(128 px wide; "+(mode.compressed?"height varies per LZ77 block":mode.height()+" px tall")+").\n"+
                                "Off-palette colours are snapped to the nearest palette entry"+
                                (exportPalette==null?"; with Grayscale, exact gray levels are required":"")+".\n"+
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
                    "Select the same tile format used for export.");
        String number=name.substring(prefix.length(),name.length()-4);
        if(!number.matches("[0-9]{1,6}"))throw new IOException("Invalid page number in PNG filename");
        int index=Integer.parseInt(number);
        if(index>=retroInfo.pageCount(retroMode))throw new IOException("Sheet page is outside selected ROM" +
                (retroMode.compressed?" (re-run the LZ77 scan in the same format)":""));
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
        final int[] importPalette=paletteFor(mode.bpp);
        busy=true;render();summary.setText("Rebuilding raw tile ROM copy...");
        worker.execute(() -> {
            try {
                int page=selectedRetroPage();
                int[] size=new int[2];
                int[] pixels=readPngPixels(retroPngUri,size);
                Palettes.Mapped mapped=Palettes.toGray(pixels,mode.bpp,importPalette);
                RetroTiles.Rebuilt rebuilt;
                try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(imageUri,"r")) {
                    if(descriptor==null)throw new IOException("Could not reopen original ROM");
                    try(FileInputStream src=new FileInputStream(descriptor.getFileDescriptor());
                        FileChannel channel=src.getChannel()) {
                        RetroTiles.Info latest=RetroTiles.parse(channel,old.system,getFilename(imageUri));
                        if(latest.length!=old.length || latest.dataOffset!=old.dataOffset ||
                                !latest.title.equals(old.title))
                            throw new IOException("ROM changed since selection; reopen it first");
                        latest.lzBlocks=old.lzBlocks;
                        int byteLength=mode.compressed?latest.lzBlocks.get(page).size:mode.pageBytes();
                        if(size[0]!=mode.width()||size[1]!=mode.heightFor(byteLength))
                            throw new IOException("PNG is "+size[0]+"×"+size[1]+"; this page needs "+
                                    mode.width()+"×"+mode.heightFor(byteLength));
                        byte[] replacement=RetroTiles.encodeAny(mapped.gray,size[0],size[1],mode,byteLength);
                        rebuilt=RetroTiles.rebuildFixed(channel,latest,mode,page,replacement);
                    }
                }
                try(OutputStream out=getContentResolver().openOutputStream(destination,"w")) {
                    if(out==null)throw new IOException("Cannot create modified ROM");
                    out.write(rebuilt.rom);
                }
                final String notes=rebuilt.notes+(mapped.inexact>0?" "+mapped.inexact+" pixel(s) snapped to the nearest palette colour.":"");
                runOnUiThread(()->{busy=false;render();summary.setText("Modified ROM saved");
                    detail.setText(mode.slug+" page "+page+" patched in a NEW ROM. Original untouched.");
                    new AlertDialog.Builder(this).setTitle("ROM copy saved")
                        .setMessage("Replaced tile page "+page+" in a new ROM. "+notes+
                                "\n\nTry it in an emulator. If those bytes weren't graphics, the ROM may malfunction.")
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

    private void beginPakExtract(int onlyHunk) {
        if (busy || pakInfo == null) return;
        pakOnly = onlyHunk;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, PICK_PAK_FOLDER);
    }

    /** Torus PAK: one folder per bundle, assets grouped by type. Textures → PNG, everything else raw. */
    private void extractPak(Uri tree) {
        if (busy || pakInfo == null || imageUri == null) return;
        final TorusPak.Info info = pakInfo;
        final int only = pakOnly;
        busy = true; cancelled.set(false); render();
        summary.setText("Extracting PAK assets...");
        worker.execute(() -> {
            final int[] stats = new int[4]; // bundles, textures → PNG, raw assets, textures left raw
            try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(imageUri, "r")) {
                if (descriptor == null) throw new IOException("Could not reopen the PAK file");
                try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
                     FileChannel channel = stream.getChannel()) {
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                    Uri folder = DocumentsContract.createDocument(getContentResolver(), parent, DIR_MIME, "PAK_Extract_" + stamp);
                    if (folder == null) throw new IOException("Cannot create output folder");
                    Map<String, Uri> folders = new HashMap<>();
                    folders.put("", folder);
                    if (only < 0) for (String[] loose : info.looseManifests)
                        writeDoc(folder, loose[0] + ".files.txt", "text/plain", loose[1].getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    for (int index = 0; index < info.hunks.size(); index++) {
                        if (only >= 0 && index != only) continue;
                        if (cancelled.get()) break;
                        TorusPak.Hunk hunk = info.hunks.get(index);
                        final int position = index + 1;
                        runOnUiThread(() -> summary.setText("Extracting " + hunk.name +
                                (only < 0 ? " (" + position + "/" + info.hunks.size() + ")" : "") + "..."));
                        byte[] raw = TorusPak.read(channel, hunk.data);
                        String base = TorusPak.safeName(hunk.name);
                        Uri hunkDir = ensureFolder(base, folders);
                        if (hunk.manifest != null)
                            writeDoc(hunkDir, base + ".files.txt", "text/plain", hunk.manifest.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                        for (TorusPak.Asset asset : TorusPak.assets(raw)) {
                            if (cancelled.get()) break;
                            Uri dir = ensureFolder(base + "/" + TorusPak.safeName(asset.type), folders);
                            String name = TorusPak.safeName(asset.name);
                            TorusPak.Texture texture = null;
                            if (asset.type.equals("TSETexture")) {
                                // Never abort a whole extraction for one unsupported/broken texture.
                                try { texture = TorusPak.texture(raw, asset); } catch (Exception broken) { texture = null; }
                            }
                            if (texture != null) {
                                writePng(dir, name + ".png", texture.width, texture.height, texture.argb);
                                stats[1]++;
                            } else {
                                writeDoc(dir, name + ".bin", "application/octet-stream", TorusPak.slice(raw, asset));
                                if (asset.type.equals("TSETexture")) stats[3]++; else stats[2]++;
                            }
                            final int written = stats[1] + stats[2] + stats[3];
                            if (written % 20 == 0) runOnUiThread(() -> detail.setText(written + " assets written..."));
                        }
                        stats[0]++;
                    }
                    writeDoc(folder, "README.txt", "text/plain", ("Torus PAK extraction.\n" +
                            "One folder per bundle (hunk), then one folder per asset type.\n" +
                            "TSETexture: decoded from 3DS ETC1 / ETC1A4 to PNG (largest mip level only).\n" +
                            "Everything else (.bin): the asset's raw records, each as u32 size, u16 type, u16 flags, data " +
                            "(little-endian). These formats are not decoded.\n" +
                            "*.files.txt: the bundle's original text manifest.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> {
                    busy = false; render();
                    String result = stats[0] + " bundle(s): " + stats[1] + " textures saved as PNG, " + stats[2] +
                            " other assets saved as raw data" + (stats[3] > 0 ? ", " + stats[3] + " textures left raw (unknown format)" : "") + ".";
                    summary.setText("PAK extraction " + (cancelled.get() ? "cancelled" : "complete"));
                    detail.setText(result);
                    new AlertDialog.Builder(this).setTitle(cancelled.get() ? "PAK extraction cancelled" : "PAK extraction complete")
                            .setMessage(result + "\n\nOriginal file unchanged.").setPositiveButton("OK", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false; render(); summary.setText("PAK extraction failed"); detail.setText(message(e));
                    new AlertDialog.Builder(this).setTitle("PAK extraction failed")
                            .setMessage(message(e)).setPositiveButton("OK", null).show();
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
        final int[] nesPalette = paletteFor(2);
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
                        int[] pixels = Palettes.recolour(NesCodec.decodeTiles(raw), 2, nesPalette);
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
                                    (nesPalette == null ? "Use opaque #000000, #555555, #AAAAAA, #FFFFFF only.\n" :
                                        "Exported with display palette " + viewPaletteName + ": re-import with it selected.\n") +
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
        final int[] nesPalette = paletteFor(2);
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
                byte[] patched = NesCodec.encodeTiles(Palettes.toGray(pixels, 2, nesPalette).gray,128,128);
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
            if (buffer.size() > maxBytes - n) throw new IOException("File is too large");
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
