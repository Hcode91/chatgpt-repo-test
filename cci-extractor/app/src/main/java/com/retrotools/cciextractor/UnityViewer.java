package com.retrotools.cciextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Full-screen model viewer for a Unity mesh:
 *  - 3D: drag to rotate, pinch to zoom, double-tap to reset; each part in its material's main texture;
 *  - UV: where each part sits on its texture;
 *  - Paint: place a design over the model (move / pinch / twist, opacity) and project it onto the texture.
 */
final class UnityViewer {

    /** Everything the viewer needs, loaded off the UI thread. */
    static final class Data {
        UnityModels.Mesh mesh;
        UnityPaint.Geometry geometry;
        String[] partNames;          // material name per part
        String[] partKey;            // texture key per part ("bundle|file|pathId"), null = none
        String[] partTextureName;
        final Map<String, Bitmap> bitmaps = new HashMap<>();   // shown (premultiplied)
        final Map<String, int[]> pixels = new HashMap<>();     // straight ARGB, original or painted
        final Map<String, Object[]> texInfo = new HashMap<>(); // engine-specific, handed back to the host
        final Map<String, int[]> texSize = new HashMap<>();     // {width, height}
        final Map<String, String> texFile = new HashMap<>();    // suggested PNG name
        boolean[] partCutout;        // per part: alpha means see-through (null: every part)
        String note;
        Bitmap bitmap(int part) { return part < 0 || partKey[part] == null ? null : bitmaps.get(partKey[part]); }
    }

    interface ImageCallback { void got(int[] argb, int w, int h); }

    /** What the viewer asks of the activity. */
    interface Host {
        void save(String suggestedName, String mime, byte[] data);
        void pickImage(ImageCallback callback);
        /** A texture was painted (or undone): remember it for the next rebuild. */
        void painted(String key, Object[] texInfo, int[] pixels);
        int paintedCount();
        /** Encode every painted texture and save a new OBB. */
        void buildPainted();
    }

    static String key(UnityModels.Ref r) { return r.bundle + "|" + r.file + "|" + r.pathId; }

    /** Loads mesh, materials and textures. Call on a worker thread. {@code painted}: textures already painted. */
    static Data load(java.nio.channels.FileChannel ch, UnityObb.Info info, UnityModels.Model model, Map<String, int[]> painted) throws Exception {
        Data d = new Data();
        d.mesh = UnityModels.mesh(ch, info, model.mesh);
        d.geometry = new UnityPaint.Geometry(d.mesh);
        int parts = d.mesh.parts.length;
        d.partNames = new String[parts];
        d.partKey = new String[parts];
        d.partTextureName = new String[parts];
        Map<UnityModels.Ref, UnityModels.Material> mats = new HashMap<>();
        int missing = 0;
        for (int p = 0; p < parts; p++) {
            UnityModels.Ref mref = model.materials.isEmpty() ? null : model.materials.get(Math.min(p, model.materials.size() - 1));
            UnityModels.Material mat = null;
            if (mref != null) {
                if (!mats.containsKey(mref)) {
                    try { mats.put(mref, UnityModels.material(ch, info, mref)); } catch (Exception e) { mats.put(mref, null); }
                }
                mat = mats.get(mref);
            }
            d.partNames[p] = mat != null ? mat.name : "part " + (p + 1);
            Object[] tex = mat == null ? null : UnityObb.texture(info, mat.texture);
            if (tex == null) { missing++; continue; }
            UnityBundle.Texture t = (UnityBundle.Texture) tex[1];
            String k = key(mat.texture);
            d.partTextureName[p] = t.name + " (" + UnityTextures.name(t.format) + " " + t.width + "×" + t.height + ")";
            if (!d.texInfo.containsKey(k)) {
                d.texInfo.put(k, tex);
                d.texSize.put(k, new int[]{t.width, t.height});
                d.texFile.put(k, UnityObb.safe(t.name) + "__" + t.pathId + ".png");
                if (d.pixels.size() < 24 && UnityTextures.supported(t.format)) {
                    try {
                        int[] argb = painted != null && painted.containsKey(k) ? painted.get(k).clone()
                                : UnityTextures.decode(UnityObb.readTexture(ch, (UnityObb.BundleInfo) tex[0], t), t.format, t.width, t.height);
                        Bitmap bmp = Bitmap.createBitmap(t.width, t.height, Bitmap.Config.ARGB_8888);
                        bmp.setPixels(argb, 0, t.width, 0, 0, t.width, t.height);
                        d.pixels.put(k, argb);
                        d.bitmaps.put(k, bmp);
                    } catch (Exception | OutOfMemoryError ignored) { }
                }
            }
            if (d.bitmaps.containsKey(k)) d.partKey[p] = k; else missing++;
        }
        if (d.mesh.uv == null) d.note = "This mesh has no texture coordinates.";
        else if (missing > 0) d.note = missing + " part(s) have no texture that could be found or decoded; they're shown grey.";
        return d;
    }

    /** Unreal Engine 4: loads a mesh, its materials and textures for the viewer. {@code painted}: textures already painted, by key. */
    static Data loadUnreal(UnrealObb.Info info, UnrealModels.MeshRef ref, Map<String, int[]> painted) throws Exception {
        UnrealPak pak = info.paks.get(ref.pak).pak;
        UnrealPak.Entry entry = pak.byName.get(ref.path);
        if (entry == null) throw new IOException("Package not found: " + ref.path);
        byte[] bytes = pak.read(entry);
        UnrealAssets.Package pk = UnrealAssets.parse(bytes);
        if (ref.export < 0 || ref.export >= pk.exports.size()) throw new IOException("Mesh not found");
        UnrealModels.Loaded l = UnrealModels.mesh(bytes, pk, pk.exports.get(ref.export));
        String project = UnrealModels.project(ref.path);
        Map<String, byte[]> cache = new HashMap<>();
        UnrealModels.PackageSource src = file -> {
            if (cache.containsKey(file)) return cache.get(file);
            byte[] b = null;
            for (UnrealObb.PakInfo p : info.paks) {
                if (p.pak == null) continue;
                UnrealPak.Entry e = p.pak.byName.get(file);
                if (e != null && !e.encrypted) { b = p.pak.read(e); break; }
            }
            cache.put(file, b);
            return b;
        };

        Data d = new Data();
        d.mesh = l.mesh;
        d.geometry = new UnityPaint.Geometry(d.mesh);
        int parts = d.mesh.parts.length;
        d.partNames = new String[parts];
        d.partKey = new String[parts];
        d.partTextureName = new String[parts];
        d.partCutout = new boolean[parts];
        Map<String, UnrealModels.Material> mats = new HashMap<>();
        int missing = 0;
        for (int p = 0; p < parts; p++) {
            String mp = l.partMaterial[p];
            UnrealModels.Material mat = null;
            if (mp != null) {
                if (!mats.containsKey(mp)) mats.put(mp, UnrealModels.material(src, mp, project));
                mat = mats.get(mp);
            }
            d.partNames[p] = mat != null && mat.name != null ? mat.name : "part " + (p + 1);
            d.partCutout[p] = mat != null && mat.cutout;
            Object[] tex = mat == null || mat.texture == null ? null : UnrealModels.findTexture(info, mat.texture, project);
            if (tex == null) { missing++; continue; }
            UnrealObb.PackageInfo tp = (UnrealObb.PackageInfo) tex[0];
            UnrealAssets.Texture t = (UnrealAssets.Texture) tex[1];
            String k = UnrealModels.key(tp.pak, tp.path, t.export);
            d.partTextureName[p] = t.name + " (" + t.pixelFormat + " " + t.width + "×" + t.height + ")";
            if (!d.texInfo.containsKey(k)) {
                d.texInfo.put(k, tex);
                d.texSize.put(k, new int[]{t.width, t.height});
                d.texFile.put(k, UnityObb.safe(t.name) + ".png");
                if (d.pixels.size() < 24 && t.format >= 0 && UnityTextures.supported(t.format)) {
                    try {
                        int[] argb = painted != null && painted.containsKey(k) ? painted.get(k).clone() : UnrealObb.decode(info, tp, t);
                        Bitmap bmp = Bitmap.createBitmap(t.width, t.height, Bitmap.Config.ARGB_8888);
                        bmp.setPixels(argb, 0, t.width, 0, 0, t.width, t.height);
                        d.pixels.put(k, argb);
                        d.bitmaps.put(k, bmp);
                    } catch (Exception | OutOfMemoryError ignored) { }
                }
            }
            if (d.bitmaps.containsKey(k)) d.partKey[p] = k; else missing++;
        }
        if (missing > 0) d.note = missing + " part(s) have no texture that could be found or decoded; they're shown grey.";
        return d;
    }

    // ================================================================== screen
    static void show(Activity activity, Data d, Host host) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 24, 40));
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);
        ExecutorService work = Executors.newSingleThreadExecutor();

        TextView title = new TextView(activity);
        title.setText(d.mesh.name + " · " + d.mesh.vertexCount + " vertices · " + d.mesh.parts.length + " part" + (d.mesh.parts.length == 1 ? "" : "s"));
        title.setTextColor(Color.WHITE); title.setTextSize(15);
        title.setPadding(pad, (int) (10 * density), pad, (int) (4 * density));
        root.addView(title);

        LinearLayout bar = new LinearLayout(activity);
        Button mode = new Button(activity), part = new Button(activity), save = new Button(activity), close = new Button(activity);
        for (Button b : new Button[]{mode, part, save, close}) { b.setAllCaps(false); bar.addView(b, new LinearLayout.LayoutParams(0, (int) (46 * density), 1)); }
        part.setText("Part: all"); save.setText("Save…"); close.setText("Close");
        root.addView(bar);

        TextView info = new TextView(activity);
        info.setTextColor(Color.rgb(200, 210, 235)); info.setTextSize(12);
        info.setPadding(pad, (int) (2 * density), pad, (int) (6 * density));
        root.addView(info);

        // exact views, so stamps from several sides line up (the design stays put on screen)
        LinearLayout views = new LinearLayout(activity);
        views.setPadding((int) (4 * density), 0, (int) (4 * density), 0);
        String[] viewNames = {"Front", "Left", "Back", "Right"};
        float[] viewYaw = {UnityPaint.YAW_FRONT, UnityPaint.YAW_LEFT, UnityPaint.YAW_BACK, UnityPaint.YAW_RIGHT};
        Button[] viewButtons = new Button[4];
        for (int i = 0; i < 4; i++) {
            Button b = new Button(activity);
            b.setAllCaps(false); b.setText(viewNames[i]); b.setTextSize(12);
            views.addView(b, new LinearLayout.LayoutParams(0, (int) (40 * density), 1));
            viewButtons[i] = b;
        }
        root.addView(views);

        FrameLayout stage = new FrameLayout(activity);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        ModelView gl = new ModelView(activity, d);
        UvView uv = new UvView(activity, d);
        DecalView decal = new DecalView(activity);
        stage.addView(gl, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(uv, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(decal, new FrameLayout.LayoutParams(-1, -1));
        uv.setVisibility(View.GONE);
        decal.setVisibility(View.GONE);

        // paint controls
        LinearLayout paintPanel = new LinearLayout(activity);
        paintPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pr1 = new LinearLayout(activity);
        Button pick = new Button(activity), target = new Button(activity), apply = new Button(activity), undo = new Button(activity);
        for (Button b : new Button[]{pick, target, apply, undo}) { b.setAllCaps(false); pr1.addView(b, new LinearLayout.LayoutParams(0, (int) (46 * density), 1)); }
        pick.setText("Design…"); apply.setText("Apply"); undo.setText("Undo");
        paintPanel.addView(pr1);
        LinearLayout pr2 = new LinearLayout(activity);
        pr2.setPadding(pad, 0, pad, 0);
        TextView opLabel = new TextView(activity);
        opLabel.setTextColor(Color.WHITE); opLabel.setTextSize(13); opLabel.setText("Opacity 100%");
        SeekBar opacity = new SeekBar(activity);
        opacity.setMax(100); opacity.setProgress(100);
        pr2.addView(opLabel, new LinearLayout.LayoutParams((int) (110 * density), -2));
        pr2.addView(opacity, new LinearLayout.LayoutParams(0, -2, 1));
        paintPanel.addView(pr2);
        paintPanel.setVisibility(View.GONE);
        root.addView(paintPanel);

        TextView hint = new TextView(activity);
        hint.setTextColor(Color.rgb(150, 160, 190)); hint.setTextSize(11);
        hint.setPadding(pad, (int) (4 * density), pad, (int) (8 * density));
        root.addView(hint);

        final int[] selected = {-1};
        final int[] screen = {0};        // 0 = 3D, 1 = UV, 2 = paint
        final boolean[] busy = {false};
        final ArrayDeque<Map<String, int[]>> undoStack = new ArrayDeque<>();
        final String[] status = {null};
        Runnable refresh = () -> {
            int s = selected[0];
            int texPart = s >= 0 ? s : firstTextured(d);
            part.setText(s < 0 ? "Part: all" : "Part " + (s + 1) + "/" + d.mesh.parts.length);
            String line = s < 0 ? (d.mesh.parts.length + " parts. Pick a part to highlight it.")
                    : "Part " + (s + 1) + ": " + d.partNames[s] + " · " + (d.partTextureName[s] == null ? "no texture" : d.partTextureName[s]);
            if (screen[0] == 1 && texPart >= 0 && d.partTextureName[texPart] != null && s < 0)
                line = "Texture: " + d.partTextureName[texPart] + ". Pick a part to see others.";
            if (screen[0] == 2) line = (s < 0 ? "Painting onto every part under the design." : "Painting only part " + (s + 1) + ": " + d.partNames[s] + ".") +
                    (status[0] != null ? "\n" + status[0] : "");
            info.setText(line + (d.note == null || screen[0] == 2 ? "" : "\n" + d.note));
            hint.setText(screen[0] == 1 ? "UV view: the selected part's outline (bright) on its texture; other parts on the same texture are dim. Pinch to zoom, drag to move."
                    : screen[0] == 2 ? (decal.adjustDesign ? "Moving the design: drag, pinch to resize, twist with two fingers. Tap \"Moving\" to turn the model instead."
                    : "Turning the model: drag / pinch. Tap \"Moving\" to move the design again.") + " Apply paints what you see onto the texture."
                    : "Drag to rotate · pinch to zoom · double-tap to reset");
            gl.select(s);
            uv.select(texPart, s);
            mode.setText(screen[0] == 0 ? "View: 3D" : screen[0] == 1 ? "View: UV" : "View: Paint");
            target.setText(decal.adjustDesign ? "Moving: design" : "Moving: model");
            apply.setEnabled(!busy[0] && decal.hasDesign());
            undo.setEnabled(!busy[0] && !undoStack.isEmpty());
            pick.setEnabled(!busy[0]);
        };

        for (int i = 0; i < 4; i++) {
            final float yawTo = viewYaw[i]; final String name = viewNames[i];
            viewButtons[i].setOnClickListener(v -> {
                gl.setAngles(yawTo, 0);
                status[0] = name + " view: the model's " + name.toLowerCase() + (name.equals("Front") || name.equals("Back") ? "" : " side") + " faces you.";
                refresh.run();
            });
        }

        mode.setOnClickListener(v -> new AlertDialog.Builder(activity).setTitle("View")
                .setItems(new String[]{"3D", "UV (where parts sit on the texture)", "Paint a design onto the model"}, (dd, w) -> {
                    screen[0] = w;
                    uv.setVisibility(w == 1 ? View.VISIBLE : View.GONE);
                    gl.setVisibility(w == 1 ? View.GONE : View.VISIBLE);
                    decal.setVisibility(w == 2 ? View.VISIBLE : View.GONE);
                    paintPanel.setVisibility(w == 2 ? View.VISIBLE : View.GONE);
                    views.setVisibility(w == 1 ? View.GONE : View.VISIBLE);
                    if (w == 2 && !decal.hasDesign()) pick.performClick();
                    refresh.run();
                }).show());

        part.setOnClickListener(v -> {
            String[] items = new String[d.mesh.parts.length + 2];
            items[0] = "All parts";
            for (int i = 0; i < d.mesh.parts.length; i++)
                items[i + 1] = (i + 1) + ". " + d.partNames[i] + (d.partTextureName[i] == null ? "" : " — " + d.partTextureName[i]);
            items[items.length - 1] = "3D: see-through texture areas " + (gl.cutout() ? "ON (tap to turn off)" : "OFF (tap to turn on)");
            new AlertDialog.Builder(activity).setTitle("Highlight part").setItems(items, (dd, w) -> {
                if (w == items.length - 1) gl.toggleCutout(); else selected[0] = w - 1;
                refresh.run();
            }).show();
        });

        pick.setOnClickListener(v -> host.pickImage((argb, w, h) -> {
            decal.setDesign(argb, w, h, gl.getWidth(), gl.getHeight());
            decal.adjustDesign = true;
            refresh.run();
        }));
        target.setOnClickListener(v -> { decal.adjustDesign = !decal.adjustDesign; refresh.run(); });
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) { opLabel.setText("Opacity " + p + "%"); decal.setOpacity(p / 100f); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        apply.setOnClickListener(v -> {
            if (busy[0] || !decal.hasDesign() || d.mesh.uv == null) return;
            // which textures, and which of their parts get paint
            Map<String, List<Integer>> paintParts = new LinkedHashMap<>(), textureParts = new LinkedHashMap<>();
            for (int p = 0; p < d.mesh.parts.length; p++) {
                String k = d.partKey[p];
                if (k == null) continue;
                textureParts.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
                if (selected[0] < 0 || selected[0] == p) paintParts.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
            }
            if (paintParts.isEmpty()) { status[0] = "Nothing to paint: this part has no texture."; refresh.run(); return; }
            UnityPaint.View3D view = gl.view();
            UnityPaint.Decal dc = decal.snapshot();
            busy[0] = true; status[0] = "Painting…"; refresh.run();
            work.execute(() -> {
                Map<String, int[]> before = new HashMap<>();
                int painted = 0; boolean mirrored = false; String err = null;
                try {
                    UnityPaint.Depth depth = UnityPaint.depth(d.geometry, view, 640);
                    for (Map.Entry<String, List<Integer>> e : paintParts.entrySet()) {
                        int[] px = d.pixels.get(e.getKey());
                        int[] size = d.texSize.get(e.getKey());
                        int[] copy = px.clone();
                        UnityPaint.Result r = UnityPaint.stamp(d.geometry, view, depth, dc, px, size[0], size[1], e.getValue(), textureParts.get(e.getKey()));
                        if (r.painted > 0) before.put(e.getKey(), copy);
                        painted += r.painted; mirrored |= r.mirrored();
                    }
                } catch (Exception | OutOfMemoryError ex) { err = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
                final int fp = painted; final boolean fm = mirrored; final String ferr = err;
                activity.runOnUiThread(() -> {
                    busy[0] = false;
                    if (ferr != null) status[0] = "Painting failed: " + ferr;
                    else if (fp == 0) status[0] = "Nothing was painted: the design isn't over a textured surface facing you.";
                    else {
                        undoStack.push(before);
                        while (undoStack.size() > 8) undoStack.removeLast();
                        for (String k : before.keySet()) pushTexture(d, gl, uv, host, k);
                        status[0] = "Painted " + fp + " texels. Saved with the next rebuild (Save… → New OBB)." +
                                (fm ? "\nHeads-up: part of this texture area is also used by another spot on the model (mirrored), so it shows there too." : "");
                    }
                    refresh.run();
                });
            });
        });

        undo.setOnClickListener(v -> {
            if (busy[0] || undoStack.isEmpty()) return;
            Map<String, int[]> before = undoStack.pop();
            for (Map.Entry<String, int[]> e : before.entrySet()) {
                System.arraycopy(e.getValue(), 0, d.pixels.get(e.getKey()), 0, e.getValue().length);
                pushTexture(d, gl, uv, host, e.getKey());
            }
            status[0] = "Undone.";
            refresh.run();
        });

        save.setOnClickListener(v -> {
            List<String> options = new ArrayList<>();
            int texPart = selected[0] >= 0 ? selected[0] : firstTextured(d);
            if (host.paintedCount() > 0) options.add("New OBB with painted textures (" + host.paintedCount() + ")");
            if (texPart >= 0 && d.bitmap(texPart) != null) options.add("Texture as PNG (as shown, incl. paint)");
            options.add("Model as OBJ (with UVs)");
            if (texPart >= 0 && d.bitmap(texPart) != null && d.mesh.uv != null) options.add("UV layout PNG (texture + outlines)");
            if (texPart >= 0 && d.mesh.uv != null) options.add("UV outlines only (transparent PNG)");
            new AlertDialog.Builder(activity).setTitle("Save").setItems(options.toArray(new String[0]), (dd, w) -> {
                String o = options.get(w);
                try {
                    if (o.startsWith("New OBB")) host.buildPainted();
                    else if (o.startsWith("Texture as PNG")) {
                        String k = d.partKey[texPart];
                        int[] size = d.texSize.get(k);
                        Bitmap b = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                        b.setPremultiplied(false);
                        b.setPixels(d.pixels.get(k), 0, size[0], 0, 0, size[0], size[1]);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        b.compress(Bitmap.CompressFormat.PNG, 100, out);
                        b.recycle();
                        host.save(d.texFile.get(k), "image/png", out.toByteArray());
                    } else if (o.startsWith("Model as OBJ")) {
                        host.save(UnityObb.safe(d.mesh.name) + ".obj", "application/octet-stream",
                                UnityModels.toObj(d.mesh).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        boolean withTexture = o.startsWith("UV layout");
                        Bitmap b = uv.render(texPart, selected[0], withTexture);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        b.compress(Bitmap.CompressFormat.PNG, 100, out);
                        b.recycle();
                        String base = UnityObb.safe(d.mesh.name) + (selected[0] >= 0 ? "_part" + (selected[0] + 1) : "");
                        host.save(base + (withTexture ? "_uv.png" : "_uv_lines.png"), "image/png", out.toByteArray());
                    }
                } catch (Exception | OutOfMemoryError e) {
                    new AlertDialog.Builder(activity).setTitle("Couldn't save").setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK", null).show();
                }
            }).show();
        });
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(x -> { gl.onPause(); uv.release(); work.shutdown(); });
        dialog.setContentView(root);
        refresh.run();
        dialog.show();
    }

    /** After pixels of texture {@code key} changed: refresh the shown bitmap, GPU copy, UV view and the activity. */
    private static void pushTexture(Data d, ModelView gl, UvView uv, Host host, String key) {
        Bitmap b = d.bitmaps.get(key);
        int[] px = d.pixels.get(key);
        b.setPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        gl.updateTexture(b);
        uv.invalidate();
        host.painted(key, d.texInfo.get(key), px.clone());
    }

    static int firstTextured(Data d) {
        for (int i = 0; i < d.partKey.length; i++) if (d.partKey[i] != null) return i;
        return d.mesh.parts.length > 0 ? 0 : -1;
    }

    // ================================================================== design overlay (paint mode)
    static final class DecalView extends View {
        boolean adjustDesign = true;
        private Bitmap shown;
        private int[] argb; private int w, h;
        private float cx, cy, size, angle, opacity = 1;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX, lastY, lastDist, lastAngle; private int lastCount;

        DecalView(Context c) {
            super(c);
            frame.setStyle(Paint.Style.STROKE); frame.setColor(Color.argb(200, 255, 255, 255));
            frame.setStrokeWidth(2 * c.getResources().getDisplayMetrics().density);
            frame.setPathEffect(new DashPathEffect(new float[]{12, 10}, 0));
        }

        boolean hasDesign() { return argb != null; }

        void setDesign(int[] pixels, int width, int height, int viewW, int viewH) {
            argb = pixels; w = width; h = height;
            if (shown != null) shown.recycle();
            shown = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            shown.setPixels(argb, 0, w, 0, 0, w, h);
            int vw = viewW > 0 ? viewW : getWidth(), vh = viewH > 0 ? viewH : getHeight();
            cx = vw / 2f; cy = vh / 2f; size = Math.max(64, vw * 0.4f); angle = 0;
            invalidate();
        }

        void setOpacity(float o) { opacity = o; invalidate(); }

        UnityPaint.Decal snapshot() {
            UnityPaint.Decal d = new UnityPaint.Decal();
            d.argb = argb; d.w = w; d.h = h; d.cx = cx; d.cy = cy; d.size = size; d.angle = angle; d.opacity = opacity;
            return d;
        }

        @Override protected void onDraw(Canvas canvas) {
            if (shown == null) return;
            float hh = size * h / (float) w;
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate((float) Math.toDegrees(angle));
            paint.setAlpha(Math.round(255 * Math.max(0.15f, opacity)));
            canvas.drawBitmap(shown, null, new android.graphics.RectF(-size / 2, -hh / 2, size / 2, hh / 2), paint);
            if (adjustDesign) canvas.drawRect(-size / 2, -hh / 2, size / 2, hh / 2, frame);
            canvas.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!adjustDesign || shown == null) return false; // let the 3D view handle it
            int n = e.getPointerCount();
            if (n >= 2) {
                float dx = e.getX(1) - e.getX(0), dy = e.getY(1) - e.getY(0);
                float dist = (float) Math.hypot(dx, dy), ang = (float) Math.atan2(dy, dx);
                float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                if (lastCount >= 2 && e.getActionMasked() == MotionEvent.ACTION_MOVE && lastDist > 0) {
                    size = Math.max(16, Math.min(getWidth() * 4f, size * dist / lastDist));
                    angle += ang - lastAngle;
                    cx += mx - lastX; cy += my - lastY;
                }
                lastDist = dist; lastAngle = ang; lastX = mx; lastY = my;
            } else {
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE && lastCount == 1) { cx += e.getX() - lastX; cy += e.getY() - lastY; }
                lastX = e.getX(); lastY = e.getY();
            }
            int a = e.getActionMasked();
            lastCount = (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) ? 0
                    : a == MotionEvent.ACTION_POINTER_UP ? n - 1 : n;
            if (a == MotionEvent.ACTION_POINTER_UP) { // re-anchor on the finger that stays
                int stay = e.getActionIndex() == 0 ? 1 : 0;
                lastX = e.getX(stay); lastY = e.getY(stay);
            }
            invalidate();
            return true;
        }
    }

    // ================================================================== 3D
    static final class ModelView extends GLSurfaceView {
        private final Renderer3D renderer;
        private final ScaleGestureDetector scale;
        private final GestureDetector taps;
        private float lastX, lastY;

        ModelView(Context c, Data d) {
            super(c);
            setEGLContextClientVersion(3);
            renderer = new Renderer3D(d);
            setRenderer(renderer);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
            scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector g) {
                    renderer.zoom = Math.max(0.2f, Math.min(20f, renderer.zoom * g.getScaleFactor()));
                    requestRender(); return true;
                }
            });
            taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDoubleTap(MotionEvent e) { renderer.reset(); requestRender(); return true; }
            });
        }

        void select(int part) { renderer.selected = part; requestRender(); }
        /** Snap to an exact angle (zoom is kept). */
        void setAngles(float yaw, float pitch) { renderer.yaw = yaw; renderer.pitch = pitch; requestRender(); }
        boolean cutout() { return renderer.cutout; }
        void toggleCutout() { renderer.cutout = !renderer.cutout; requestRender(); }
        /** The exact camera the current frame is drawn with. */
        UnityPaint.View3D view() { return renderer.view(getWidth(), getHeight()); }
        void updateTexture(Bitmap b) { queueEvent(() -> renderer.reupload(b)); requestRender(); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            scale.onTouchEvent(e);
            taps.onTouchEvent(e);
            if (e.getPointerCount() == 1 && !scale.isInProgress()) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { lastX = e.getX(); lastY = e.getY(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    renderer.yaw += (e.getX() - lastX) * 0.4f;
                    renderer.pitch = Math.max(-89f, Math.min(89f, renderer.pitch + (e.getY() - lastY) * 0.4f));
                    lastX = e.getX(); lastY = e.getY();
                    requestRender();
                }
            } else { lastX = e.getX(); lastY = e.getY(); }
            return true;
        }
    }

    static final class Renderer3D implements GLSurfaceView.Renderer {
        private final Data d;
        volatile float yaw = 0, pitch = 0, zoom = 1;
        volatile int selected = -1;
        /** Discard nearly transparent texels (hair cards, eyelashes). Off for textures whose alpha means something else. */
        volatile boolean cutout = true;
        private int program, vbo, aspectW = 1, aspectH = 1;
        private int[] ibo, tex;
        private final Map<Bitmap, Integer> uploaded = new java.util.IdentityHashMap<>();

        Renderer3D(Data d) { this.d = d; }

        void reset() { yaw = 0; pitch = 0; zoom = 1; }

        UnityPaint.View3D view(int w, int h) {
            return UnityPaint.camera(yaw, pitch, zoom, d.geometry.center, d.geometry.radius, w > 0 ? w : aspectW, h > 0 ? h : aspectH);
        }

        private static final String VS = "#version 300 es\n" +
                "uniform mat4 uMvp; uniform mat4 uModel;\n" +
                "layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNorm; layout(location=2) in vec2 aUv;\n" +
                "out vec3 vNorm; out vec2 vUv;\n" +
                "void main(){ gl_Position = uMvp * vec4(aPos,1.0); vNorm = mat3(uModel) * aNorm; vUv = aUv; }\n";
        private static final String FS = "#version 300 es\nprecision mediump float;\n" +
                "uniform sampler2D uTex; uniform int uHasTex; uniform float uDim; uniform float uCut;\n" +
                "in vec3 vNorm; in vec2 vUv; out vec4 o;\n" +
                "void main(){\n" +
                "  vec4 c = uHasTex == 1 ? texture(uTex, vec2(vUv.x, 1.0 - vUv.y)) : vec4(0.72,0.72,0.75,1.0);\n" +
                "  if (c.a < uCut) discard;\n" +
                "  float l = 0.38 + 0.62 * abs(dot(normalize(vNorm), normalize(vec3(0.35,0.55,1.0))));\n" +
                "  vec3 col = (c.a > 0.0 ? c.rgb / c.a : c.rgb) * l;\n" +
                "  o = vec4(mix(vec3(0.16,0.18,0.25), col, uDim), 1.0);\n" +
                "}\n";

        private static int shader(int type, String src) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("Shader: " + GLES30.glGetShaderInfoLog(s));
            return s;
        }

        @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, shader(GLES30.GL_VERTEX_SHADER, VS));
            GLES30.glAttachShader(program, shader(GLES30.GL_FRAGMENT_SHADER, FS));
            GLES30.glLinkProgram(program);

            UnityPaint.Geometry g = d.geometry;
            int n = g.pos.length / 3;
            FloatBuffer fb = ByteBuffer.allocateDirect(n * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int i = 0; i < n; i++) {
                fb.put(g.pos[i * 3]).put(g.pos[i * 3 + 1]).put(g.pos[i * 3 + 2]);
                fb.put(g.normal[i * 3]).put(g.normal[i * 3 + 1]).put(g.normal[i * 3 + 2]);
                if (g.uv != null) fb.put(g.uv[i * 2]).put(g.uv[i * 2 + 1]); else fb.put(0).put(0);
            }
            fb.position(0);
            int[] ids = new int[1];
            GLES30.glGenBuffers(1, ids, 0); vbo = ids[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 32, fb, GLES30.GL_STATIC_DRAW);

            ibo = new int[g.parts.length];
            if (ibo.length > 0) GLES30.glGenBuffers(ibo.length, ibo, 0);
            for (int p = 0; p < g.parts.length; p++) {
                int[] idx = g.parts[p];
                IntBuffer ib = ByteBuffer.allocateDirect(Math.max(4, idx.length * 4)).order(ByteOrder.nativeOrder()).asIntBuffer();
                ib.put(idx); ib.position(0);
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[p]);
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.length * 4, ib, GLES30.GL_STATIC_DRAW);
            }

            uploaded.clear();
            tex = new int[g.parts.length];
            for (int p = 0; p < g.parts.length; p++) {
                Bitmap b = d.bitmap(p);
                if (b == null || b.isRecycled()) continue;
                Integer id = uploaded.get(b);
                if (id == null) {
                    int[] t = new int[1];
                    GLES30.glGenTextures(1, t, 0);
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
                    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT);
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT);
                    id = t[0];
                    uploaded.put(b, id);
                }
                tex[p] = id;
            }
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDisable(GLES30.GL_CULL_FACE); // show both sides
            GLES30.glClearColor(0.08f, 0.09f, 0.15f, 1f);
        }

        /** Runs on the GL thread: copy a repainted bitmap to its texture. */
        void reupload(Bitmap b) {
            Integer id = uploaded.get(b);
            if (id == null || b.isRecycled()) return;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id);
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, b);
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        }

        @Override public void onSurfaceChanged(GL10 unused, int w, int h) {
            GLES30.glViewport(0, 0, w, h);
            aspectW = Math.max(1, w); aspectH = Math.max(1, h);
        }

        @Override public void onDrawFrame(GL10 unused) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            UnityPaint.View3D v = view(aspectW, aspectH);
            GLES30.glUseProgram(program);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, v.mvp, 0);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uModel"), 1, false, v.model, 0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0);
            int uHas = GLES30.glGetUniformLocation(program, "uHasTex"), uDim = GLES30.glGetUniformLocation(program, "uDim"),
                    uCut = GLES30.glGetUniformLocation(program, "uCut");
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0);
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 32, 12);
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 32, 24);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            int sel = selected;
            for (int p = 0; p < ibo.length; p++) {
                if (d.geometry.parts[p].length == 0) continue;
                boolean has = tex[p] != 0;
                if (has) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[p]);
                GLES30.glUniform1i(uHas, has ? 1 : 0);
                GLES30.glUniform1f(uDim, sel < 0 || sel == p ? 1f : 0.18f);
                GLES30.glUniform1f(uCut, cutout && (d.partCutout == null || d.partCutout[p]) ? 0.3f : -1f);
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[p]);
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, d.geometry.parts[p].length, GLES30.GL_UNSIGNED_INT, 0);
            }
        }
    }

    // ================================================================== UV
    static final class UvView extends View {
        private final Data d;
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector scale;
        private float lastX, lastY;
        private int texPart = -1, selected = -1;
        private Bitmap overlay;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private boolean fitted;

        UvView(Context c, Data d) {
            super(c);
            this.d = d;
            setBackgroundColor(Color.rgb(40, 40, 46));
            scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector g) {
                    matrix.postScale(g.getScaleFactor(), g.getScaleFactor(), g.getFocusX(), g.getFocusY());
                    invalidate(); return true;
                }
            });
        }

        int texW() { Bitmap t = d.bitmap(texPart); return t != null ? t.getWidth() : 1024; }
        int texH() { Bitmap t = d.bitmap(texPart); return t != null ? t.getHeight() : 1024; }

        void select(int texturePart, int selectedPart) {
            boolean changed = texturePart != texPart;
            texPart = texturePart; selected = selectedPart;
            if (overlay != null) overlay.recycle();
            overlay = null;
            if (changed) fitted = false;
            invalidate();
        }

        void release() { if (overlay != null) overlay.recycle(); overlay = null; }

        /** Texture-sized image: optionally the texture, plus outlines of every part using it (selected bright). */
        Bitmap render(int texturePart, int selectedPart, boolean withTexture) {
            Bitmap tex = d.bitmap(texturePart);
            int w = tex != null ? tex.getWidth() : 1024, h = tex != null ? tex.getHeight() : 1024;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            if (withTexture && tex != null) {
                Paint p = new Paint(); p.setColor(Color.rgb(40, 40, 46)); c.drawRect(0, 0, w, h, p);
                c.drawBitmap(tex, 0, 0, null);
            }
            drawOutlines(c, w, h, texturePart, selectedPart);
            return out;
        }

        private void drawOutlines(Canvas c, int w, int h, int texturePart, int selectedPart) {
            if (d.mesh.uv == null) return;
            String key = texturePart >= 0 ? d.partKey[texturePart] : null;
            float stroke = Math.max(1f, Math.max(w, h) / 1024f);
            for (int pass = 0; pass < 2; pass++) {
                for (int p = 0; p < d.mesh.parts.length; p++) {
                    boolean sameTexture = key != null ? key.equals(d.partKey[p]) : p == texturePart;
                    if (!sameTexture) continue;
                    boolean bright = selectedPart < 0 || selectedPart == p;
                    if ((pass == 0) == bright) continue; // dim first, bright on top
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(stroke);
                    paint.setColor(bright ? partColour(p) : Color.argb(70, 255, 255, 255));
                    Path path = new Path();
                    int[] t = d.mesh.parts[p];
                    float[] uv = d.mesh.uv;
                    for (int i = 0; i + 2 < t.length; i += 3) {
                        path.moveTo(uv[t[i] * 2] * w, (1 - uv[t[i] * 2 + 1]) * h);
                        path.lineTo(uv[t[i + 1] * 2] * w, (1 - uv[t[i + 1] * 2 + 1]) * h);
                        path.lineTo(uv[t[i + 2] * 2] * w, (1 - uv[t[i + 2] * 2 + 1]) * h);
                        path.close();
                    }
                    c.drawPath(path, paint);
                }
            }
        }

        static int partColour(int p) {
            int[] c = {0xFF33FF66, 0xFFFF4D4D, 0xFF4DA6FF, 0xFFFFD633, 0xFFFF66FF, 0xFF33FFFF, 0xFFFF9933, 0xFFB3FF33};
            return c[p % c.length];
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = texW(), h = texH();
            if (!fitted && getWidth() > 0) {
                float s = Math.min(getWidth() / (float) w, getHeight() / (float) h);
                matrix.reset();
                matrix.postScale(s, s);
                matrix.postTranslate((getWidth() - w * s) / 2, (getHeight() - h * s) / 2);
                fitted = true;
            }
            if (overlay == null) {
                try {
                    // outlines drawn once at texture resolution (capped), then just scaled while zooming
                    int max = 2048;
                    float k = Math.min(1f, max / (float) Math.max(w, h));
                    overlay = Bitmap.createBitmap(Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), Bitmap.Config.ARGB_8888);
                    drawOutlines(new Canvas(overlay), overlay.getWidth(), overlay.getHeight(), texPart, selected);
                } catch (OutOfMemoryError e) { overlay = null; }
            }
            canvas.save();
            canvas.concat(matrix);
            Bitmap tex = d.bitmap(texPart);
            if (tex != null) canvas.drawBitmap(tex, 0, 0, bitmapPaint);
            if (overlay != null) {
                canvas.scale(w / (float) overlay.getWidth(), h / (float) overlay.getHeight());
                canvas.drawBitmap(overlay, 0, 0, bitmapPaint);
            }
            canvas.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            scale.onTouchEvent(e);
            if (e.getPointerCount() == 1 && !scale.isInProgress()) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { lastX = e.getX(); lastY = e.getY(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    matrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
                    lastX = e.getX(); lastY = e.getY();
                    invalidate();
                }
            } else { lastX = e.getX(); lastY = e.getY(); }
            return true;
        }
    }
}
