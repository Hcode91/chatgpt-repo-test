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
            PICK_TEXTURES = 406, PICK_TEXTURE_DIR = 407, PICK_CERT_FILE = 408, PICK_RPA_SOURCE = 409, SAVE_RPA = 410,
            PICK_TRP_SOURCE = 411, SAVE_TRP = 412, PICK_TRP_EDIT = 413, PICK_TROPHY_ICON = 414, SAVE_TRP_EDIT = 415,
            PICK_TROPUSR = 416, SAVE_TROPUSR = 417, SAVE_TROPHY_MOD = 418, PICK_TRP_FOR_MOD = 419, PICK_TROPHY_MOD = 420;

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
    private Button ciaToCci, cciToCia, saveCert, textures, folderToRpa, folderToTrp, addTrophies, applyTrophyMod, cancel;
    // Trophy editor session
    private Ps3TrophyMod.Pack trophyPack;
    private boolean trophyFromMod;
    private byte[] draftIcon, existingTropusr;
    private String draftName = "", draftDetail = "";
    private int draftGrade;
    private boolean draftHidden;
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

        box.addView(text("Ren'Py", 17, Color.rgb(35, 45, 91)));
        box.addView(text("Pack a folder back into a Ren'Py archive (.rpa, RPA-3.0) after editing files extracted from one. " +
                "Paths inside the archive are the paths inside the folder you pick.", 12, Color.DKGRAY));
        folderToRpa = button("Folder → RPA");
        box.addView(folderToRpa, new LinearLayout.LayoutParams(-1, dp(52)));

        box.addView(text("PS3 trophies", 17, Color.rgb(35, 45, 91)));
        box.addView(text("Rebuild a TROPHY.TRP from a folder of its files (TROPCONF.SFM, TROP.SFM, ICON0.PNG, TROPnnn.PNG …) " +
                "after editing. Works in RPCS3; a real PS3 rejects edited trophy files.", 12, Color.DKGRAY));
        folderToTrp = button("Folder → TROPHY.TRP");
        box.addView(folderToTrp, new LinearLayout.LayoutParams(-1, dp(52)));
        box.addView(text("Add your own trophies to a game's TROPHY.TRP for RPCS3, or apply a trophy mod someone shared. " +
                "Custom trophies never count towards the platinum. The game won't award them by itself: unlocking needs a " +
                "game patch or a manual unlock.", 12, Color.DKGRAY));
        addTrophies = button("Add custom trophies…");
        box.addView(addTrophies, new LinearLayout.LayoutParams(-1, dp(52)));
        applyTrophyMod = button("Apply a trophy mod (.zip)…");
        box.addView(applyTrophyMod, new LinearLayout.LayoutParams(-1, dp(52)));

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
        addTrophies.setOnClickListener(v -> pick(PICK_TRP_EDIT, "*/*", false));
        applyTrophyMod.setOnClickListener(v -> pick(PICK_TRP_FOR_MOD, "*/*", false));
        folderToTrp.setOnClickListener(v -> {
            if (busy) return;
            Intent tree = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            tree.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(tree, PICK_TRP_SOURCE);
        });
        folderToRpa.setOnClickListener(v -> {
            if (busy) return;
            Intent tree = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            tree.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(tree, PICK_RPA_SOURCE);
        });
        cancel.setOnClickListener(v -> { cancelled.set(true); NativeCia.cancel(); status.setText("Cancelling…"); });

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        return scroll;
    }

    private void refresh() {
        ciaToCci.setEnabled(!busy); cciToCia.setEnabled(!busy); saveCert.setEnabled(!busy); textures.setEnabled(!busy);
        folderToRpa.setEnabled(!busy); folderToTrp.setEnabled(!busy); addTrophies.setEnabled(!busy); applyTrophyMod.setEnabled(!busy);
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
    boolean handles(int request) { return request >= 401 && request <= 420; }

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
            case PICK_RPA_SOURCE: sourceUri = uri; save(SAVE_RPA, rpaName(uri)); break;
            case SAVE_RPA: runFolderToRpa(sourceUri, uri); break;
            case PICK_TRP_SOURCE: sourceUri = uri; save(SAVE_TRP, "TROPHY.TRP"); break;
            case SAVE_TRP: runFolderToTrp(sourceUri, uri); break;
            case PICK_TRP_EDIT: loadTrophyPack(uri, false); break;
            case PICK_TRP_FOR_MOD: loadTrophyPack(uri, true); break;
            case PICK_TROPHY_MOD: applyMod(uri); break;
            case PICK_TROPHY_ICON: pickedIcon(uri); break;
            case SAVE_TRP_EDIT: saveEditedPack(uri); break;
            case PICK_TROPUSR: pickedTropusr(uri); break;
            case SAVE_TROPUSR: saveTropusr(uri); break;
            case SAVE_TROPHY_MOD: saveTrophyMod(uri); break;
        }
    }

    // ---------------------------------------------------------------- Ren'Py archive packing

    /** Suggested archive name: "archive.rpa" for a folder called RPA_archive_20261113_101500, else "<folder>.rpa". */
    private String rpaName(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        String name = id.substring(id.lastIndexOf('/') + 1);
        name = name.substring(name.lastIndexOf(':') + 1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("RPA_(.+)_\\d{8}_\\d{6}").matcher(name);
        if (m.matches()) name = m.group(1);
        return (name.isEmpty() ? "archive" : name) + ".rpa";
    }

    /** Every file under a picked folder, with its path relative to that folder. */
    private List<RenpyArchive.Source> listTree(Uri tree) throws IOException {
        List<RenpyArchive.Source> out = new ArrayList<>();
        ArrayList<String[]> pending = new ArrayList<>();
        pending.add(new String[]{DocumentsContract.getTreeDocumentId(tree), ""});
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        while (!pending.isEmpty()) {
            String[] dir = pending.remove(pending.size() - 1);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir[0]);
            try (Cursor c = activity.getContentResolver().query(children, columns, null, null, null)) {
                if (c == null) throw new IOException("Cannot list the folder");
                while (c.moveToNext()) {
                    String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    String path = dir[1].isEmpty() ? name : dir[1] + "/" + name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) { pending.add(new String[]{id, path}); continue; }
                    final Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    long known = c.isNull(3) ? -1 : c.getLong(3);
                    if (known < 0) {
                        // Some storage providers don't report sizes in listings; ask the file itself.
                        try (ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(doc, "r")) {
                            known = pfd == null ? -1 : pfd.getStatSize();
                        }
                    }
                    if (known < 0) throw new IOException("Can't tell the size of " + path);
                    final long size = known;
                    out.add(new RenpyArchive.Source() {
                        public String path() { return path; }
                        public long size() { return size; }
                        public InputStream open() throws IOException {
                            InputStream in = activity.getContentResolver().openInputStream(doc);
                            if (in == null) throw new IOException("Cannot read " + path);
                            return in;
                        }
                    });
                }
            }
        }
        return out;
    }

    private void runFolderToRpa(Uri tree, Uri dest) {
        busy = true; cancelled.set(false); refresh();
        status.setText("Packing Ren'Py archive…"); log.setText("");
        worker.execute(() -> {
            try {
                List<RenpyArchive.Source> files = listTree(tree);
                if (files.isEmpty()) throw new IOException("The folder is empty");
                long total = 0;
                for (RenpyArchive.Source f : files) total += Math.max(0, f.size());
                final long bytes = total;
                long size;
                try (OutputStream raw = activity.getContentResolver().openOutputStream(dest, "w")) {
                    if (raw == null) throw new IOException("Cannot write the archive");
                    java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(raw, 1 << 20);
                    size = RenpyArchive.write(files, out, new RenpyArchive.Progress() {
                        public void file(int index, int count, String path) {
                            activity.runOnUiThread(() -> { status.setText("Packing " + index + "/" + count + "…"); log.setText(path); });
                        }
                        public boolean cancelled() { return cancelled.get(); }
                    });
                    out.flush();
                }
                final long archiveSize = size;
                final int count = files.size();
                activity.runOnUiThread(() -> {
                    busy = false; refresh();
                    status.setText("Folder → RPA done");
                    log.setText(count + " files · " + (bytes >> 20) + " MB of data · archive " + (archiveSize >> 20) + " MB\n" +
                            "Put the .rpa in the game's game/ folder in place of the original (keep a backup of the original).");
                });
            } catch (Exception e) {
                try { DocumentsContract.deleteDocument(activity.getContentResolver(), dest); } catch (Exception ignored) { }
                activity.runOnUiThread(() -> { busy = false; refresh(); error("Folder → RPA failed", e); });
            }
        });
    }

    // ---------------------------------------------------------------- PS3 trophy editor

    private byte[] readUri(Uri uri, int max) throws IOException {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open file");
            return readAll(in, max);
        }
    }
    private void writeUri(Uri uri, byte[] data) throws IOException {
        try (OutputStream out = activity.getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Cannot write file");
            out.write(data);
        }
    }

    private void loadTrophyPack(Uri uri, boolean forMod) {
        try (ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(uri, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor()); FileChannel ch = in.getChannel()) {
            trophyPack = Ps3TrophyMod.Pack.load(ch);
            trophyPack.conf();
            trophyFromMod = forMod;
            existingTropusr = null;
            status.setText(trophyPack.gameTitle() + " · " + trophyPack.count() + " trophies");
            log.setText("");
            if (forMod) {
                new AlertDialog.Builder(activity).setTitle("Now pick the trophy mod")
                        .setMessage("Pack loaded: " + trophyPack.gameTitle() + " (" + trophyPack.count() + " trophies). Next, pick the mod's .zip file.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Pick mod", (d, w) -> pick(PICK_TROPHY_MOD, "*/*", false)).show();
            } else {
                draftName = ""; draftDetail = ""; draftGrade = 0; draftHidden = false; draftIcon = null;
                showTrophyDialog(null);
            }
        } catch (Exception e) { trophyPack = null; error("Couldn't open the trophy pack", e); }
    }

    /** Name, description, grade, hidden and icon for one new trophy. Reopens with the draft after picking an icon. */
    private void showTrophyDialog(String problem) {
        if (trophyPack == null) return;
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        if (problem != null) form.addView(text(problem, 13, Color.rgb(170, 30, 30)));
        final android.widget.EditText name = new android.widget.EditText(activity);
        name.setHint("Trophy name"); name.setSingleLine(true); name.setText(draftName);
        final android.widget.EditText detail = new android.widget.EditText(activity);
        detail.setHint("Description (how to earn it)"); detail.setText(draftDetail);
        final android.widget.Spinner grade = new android.widget.Spinner(activity);
        grade.setAdapter(new android.widget.ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Bronze", "Silver", "Gold"}));
        grade.setSelection(draftGrade);
        final android.widget.CheckBox hidden = new android.widget.CheckBox(activity);
        hidden.setText("Hidden until earned"); hidden.setChecked(draftHidden);
        Button icon = button(draftIcon == null ? "Choose icon (optional)" : "Icon chosen ✓ (change)");
        form.addView(name); form.addView(detail); form.addView(grade); form.addView(hidden); form.addView(icon);
        form.addView(text("Icons are cropped square and resized to 240×240. Without one, a plain icon is made.", 12, Color.DKGRAY));
        final Runnable keep = () -> {
            draftName = name.getText().toString(); draftDetail = detail.getText().toString();
            draftGrade = grade.getSelectedItemPosition(); draftHidden = hidden.isChecked();
        };
        int next;
        try { next = trophyPack.count(); } catch (IOException e) { next = 0; }
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(String.format(Locale.ROOT, "New trophy #%03d", next))
                .setView(form)
                .setNegativeButton(trophyPack.added.trophies.isEmpty() ? "Cancel" : "Finish", (d, w) -> finishTrophyEditing())
                .setPositiveButton("Add trophy", (d, w) -> {
                    keep.run();
                    try {
                        Ps3TrophyMod.Trophy t = new Ps3TrophyMod.Trophy();
                        t.name = draftName; t.detail = draftDetail; t.grade = "BSG".charAt(draftGrade); t.hidden = draftHidden;
                        t.iconPng = draftIcon != null ? draftIcon : defaultIcon(t.grade);
                        int id = trophyPack.add(t);
                        draftName = ""; draftDetail = ""; draftIcon = null; draftHidden = false;
                        new AlertDialog.Builder(activity).setTitle("Trophy added")
                                .setMessage(String.format(Locale.ROOT, "\"%s\" is trophy #%03d. %d added so far.", t.name.trim(), id, trophyPack.added.trophies.size()))
                                .setNegativeButton("Add another", (d2, w2) -> showTrophyDialog(null))
                                .setPositiveButton("Finish & save", (d2, w2) -> finishTrophyEditing())
                                .setCancelable(false).show();
                    } catch (Exception e) { showTrophyDialog(e.getMessage()); }
                })
                .setCancelable(false).create();
        icon.setOnClickListener(v -> { keep.run(); dialog.dismiss(); pick(PICK_TROPHY_ICON, "image/*", false); });
        dialog.show();
    }

    private void pickedIcon(Uri uri) {
        try {
            byte[] raw = readUri(uri, 32 << 20);
            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (src == null) throw new IOException("That file isn't an image this device can read");
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            Bitmap scaled = Bitmap.createScaledBitmap(square, Ps3TrophyMod.ICON_SIZE, Ps3TrophyMod.ICON_SIZE, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            draftIcon = out.toByteArray();
            showTrophyDialog(null);
        } catch (Exception | OutOfMemoryError e) {
            showTrophyDialog("Couldn't use that image: " + (e.getMessage() == null ? "not enough memory" : e.getMessage()));
        }
    }

    /** A plain icon in the grade's colour, for trophies added without one. */
    private byte[] defaultIcon(char grade) {
        int size = Ps3TrophyMod.ICON_SIZE;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        c.drawColor(Color.rgb(28, 32, 64));
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(grade == 'G' ? Color.rgb(240, 196, 60) : grade == 'S' ? Color.rgb(200, 204, 214) : Color.rgb(196, 128, 72));
        c.drawCircle(size / 2f, size / 2f, size * 0.36f, p);
        p.setColor(Color.rgb(28, 32, 64));
        c.drawCircle(size / 2f, size / 2f, size * 0.24f, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private void applyMod(Uri uri) {
        if (trophyPack == null) return;
        try {
            Ps3TrophyMod mod;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot open the mod file");
                mod = Ps3TrophyMod.fromZip(in);
            }
            trophyPack.apply(mod);
            StringBuilder list = new StringBuilder();
            for (Ps3TrophyMod.Trophy t : mod.trophies) list.append("\n• ").append(t.name);
            new AlertDialog.Builder(activity).setTitle(mod.title.isEmpty() ? "Trophy mod" : mod.title)
                    .setMessage((mod.author.isEmpty() ? "" : "By " + mod.author + "\n\n") + mod.trophies.size() + " trophies will be added:" + list)
                    .setNegativeButton("Cancel", (d, w) -> trophyPack = null)
                    .setPositiveButton("Save pack", (d, w) -> finishTrophyEditing()).show();
        } catch (Exception e) { trophyPack = null; error("Couldn't apply the trophy mod", e); }
    }

    private void finishTrophyEditing() {
        if (trophyPack == null || trophyPack.added.trophies.isEmpty()) { trophyPack = null; status.setText("No trophies added."); return; }
        save(SAVE_TRP_EDIT, "TROPHY.TRP");
    }

    private void saveEditedPack(Uri dest) {
        if (trophyPack == null) return;
        try {
            writeUri(dest, trophyPack.toTrp());
            status.setText("Trophy pack saved · " + trophyPack.count() + " trophies");
            log.setText("Replace PS3_GAME/TROPDIR/" + trophyPack.added.commId + "/TROPHY.TRP in the game folder (keep a backup of the original).");
            new AlertDialog.Builder(activity).setTitle("Update the progress file")
                    .setMessage("RPCS3 keeps earned trophies in TROPUSR.DAT, sized for the old trophy count, so it needs rebuilding. " +
                            "It's in RPCS3's folder: dev_hdd0/home/00000001/trophy/" + trophyPack.added.commId + "/\n\n" +
                            "Pick your current one to keep what you've earned, or make a fresh one with everything locked.")
                    .setPositiveButton("Pick my TROPUSR.DAT", (d, w) -> pick(PICK_TROPUSR, "*/*", false))
                    .setNeutralButton("Fresh one", (d, w) -> { existingTropusr = null; save(SAVE_TROPUSR, "TROPUSR.DAT"); })
                    .setNegativeButton("Skip", (d, w) -> offerModExport())
                    .setCancelable(false).show();
        } catch (Exception e) {
            try { DocumentsContract.deleteDocument(activity.getContentResolver(), dest); } catch (Exception ignored) { }
            error("Couldn't save the trophy pack", e);
        }
    }

    private void pickedTropusr(Uri uri) {
        try {
            byte[] d = readUri(uri, 4 << 20);
            if (!Ps3TrophyProgress.isProgress(d)) throw new IOException("That isn't a TROPUSR.DAT file");
            Ps3TrophyProgress.parse(d);
            existingTropusr = d;
            save(SAVE_TROPUSR, "TROPUSR.DAT");
        } catch (Exception e) { error("Couldn't read the progress file", e); offerModExport(); }
    }

    private void saveTropusr(Uri dest) {
        if (trophyPack == null) return;
        try {
            byte[] out = trophyPack.toTropusr(existingTropusr);
            writeUri(dest, out);
            int earned = 0;
            for (Ps3TrophyProgress.Trophy t : Ps3TrophyProgress.parse(out).trophies) if (t.unlocked) earned++;
            log.setText(log.getText() + "\nProgress file saved (" + earned + " earned trophies kept). With RPCS3 closed, put it in " +
                    "dev_hdd0/home/00000001/trophy/" + trophyPack.added.commId + "/ as TROPUSR.DAT.");
        } catch (Exception e) {
            try { DocumentsContract.deleteDocument(activity.getContentResolver(), dest); } catch (Exception ignored) { }
            error("Couldn't save the progress file", e);
        }
        offerModExport();
    }

    private void offerModExport() {
        if (trophyPack == null || trophyFromMod || trophyPack.added.trophies.isEmpty()) { trophyPack = null; return; }
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        final android.widget.EditText title = new android.widget.EditText(activity);
        title.setHint("Mod title"); title.setSingleLine(true);
        title.setText(trophyPack.gameTitle().isEmpty() ? "" : trophyPack.gameTitle() + " custom trophies");
        final android.widget.EditText author = new android.widget.EditText(activity);
        author.setHint("Your name (optional)"); author.setSingleLine(true);
        form.addView(text("Save your " + trophyPack.added.trophies.size() + " new trophies as a mod file others can apply to their " +
                "own copy of the game. It contains only your names, descriptions and icons, nothing from the game.", 13, Color.DKGRAY));
        form.addView(title); form.addView(author);
        new AlertDialog.Builder(activity).setTitle("Share as a trophy mod?").setView(form)
                .setNegativeButton("Not now", (d, w) -> trophyPack = null)
                .setPositiveButton("Save mod", (d, w) -> {
                    trophyPack.added.title = title.getText().toString().trim();
                    trophyPack.added.author = author.getText().toString().trim();
                    save(SAVE_TROPHY_MOD, trophyPack.added.commId + "_trophy_mod.zip");
                }).setCancelable(false).show();
    }

    private void saveTrophyMod(Uri dest) {
        if (trophyPack == null) return;
        try {
            writeUri(dest, trophyPack.added.toZip());
            log.setText(log.getText() + "\nTrophy mod saved.");
        } catch (Exception e) {
            try { DocumentsContract.deleteDocument(activity.getContentResolver(), dest); } catch (Exception ignored) { }
            error("Couldn't save the trophy mod", e);
        }
        trophyPack = null;
    }

    // ---------------------------------------------------------------- PS3 trophy packing

    /** Folder → TROPHY.TRP: the files at the top of the folder. Version from a "TRP_..._v3_..." folder name, else 1. */
    private void runFolderToTrp(Uri tree, Uri dest) {
        busy = true; cancelled.set(false); refresh();
        status.setText("Building trophy pack…"); log.setText("");
        worker.execute(() -> {
            try {
                List<Ps3Trophy.Source> files = new ArrayList<>();
                boolean hasConf = false;
                for (RenpyArchive.Source f : listTree(tree)) {
                    if (f.path().contains("/") || f.path().startsWith(".")) continue;
                    if (f.path().equalsIgnoreCase("TROPCONF.SFM")) hasConf = true;
                    files.add(new Ps3Trophy.Source() {
                        public String name() { return f.path(); }
                        public long size() { return f.size(); }
                        public InputStream open() throws IOException { return f.open(); }
                    });
                }
                if (!hasConf) throw new IOException("The folder has no TROPCONF.SFM, so it isn't an extracted trophy pack");
                String id = DocumentsContract.getTreeDocumentId(tree);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("_v(\\d)_\\d{8}_\\d{6}$").matcher(id);
                int version = m.find() ? Integer.parseInt(m.group(1)) : 1;
                byte[] pack = Ps3Trophy.build(files, version);
                try (OutputStream out = activity.getContentResolver().openOutputStream(dest, "w")) {
                    if (out == null) throw new IOException("Cannot write the trophy pack");
                    out.write(pack);
                }
                final int count = files.size(), v = version;
                activity.runOnUiThread(() -> {
                    busy = false; refresh();
                    status.setText("Folder → TROPHY.TRP done");
                    log.setText(count + " files · version " + v + (v >= 2 ? " (checksum written)" : "") + " · " + (pack.length >> 10) + " KB\n" +
                            "Replace PS3_GAME/TROPDIR/<id>/TROPHY.TRP in the game folder (keep a backup). RPCS3 reinstalls it the " +
                            "next time the game starts.");
                });
            } catch (Exception e) {
                try { DocumentsContract.deleteDocument(activity.getContentResolver(), dest); } catch (Exception ignored) { }
                activity.runOnUiThread(() -> { busy = false; refresh(); error("Folder → TROPHY.TRP failed", e); });
            }
        });
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
