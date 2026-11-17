package com.retrotools.cciextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Full-screen model viewer for a Unity mesh: 3D (drag to rotate, pinch to zoom, double-tap to reset) with each
 * part drawn in its material's main texture, and a UV view that outlines where each part sits on its texture.
 */
final class UnityViewer {

    /** Everything the viewer needs, loaded off the UI thread. */
    static final class Data {
        UnityModels.Mesh mesh;
        String[] partNames;          // material name per part
        Bitmap[] partTexture;        // texture per part (shared objects), null = none
        String[] partTextureName;
        String note;                 // anything that couldn't be loaded
    }

    interface Saver { void save(String suggestedName, String mime, byte[] data); }

    /** Loads mesh, materials and textures. Call on a worker thread. */
    static Data load(java.nio.channels.FileChannel ch, UnityObb.Info info, UnityModels.Model model) throws Exception {
        Data d = new Data();
        d.mesh = UnityModels.mesh(ch, info, model.mesh);
        int parts = d.mesh.parts.length;
        d.partNames = new String[parts];
        d.partTexture = new Bitmap[parts];
        d.partTextureName = new String[parts];
        java.util.Map<UnityModels.Ref, Bitmap> cache = new java.util.HashMap<>();
        java.util.Map<UnityModels.Ref, UnityModels.Material> mats = new java.util.HashMap<>();
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
            d.partTextureName[p] = t.name + " (" + UnityTextures.name(t.format) + " " + t.width + "×" + t.height + ")";
            if (!cache.containsKey(mat.texture)) {
                Bitmap bmp = null;
                if (cache.size() < 24 && UnityTextures.supported(t.format)) {
                    try {
                        int[] argb = UnityTextures.decode(UnityObb.readTexture(ch, (UnityObb.BundleInfo) tex[0], t), t.format, t.width, t.height);
                        bmp = Bitmap.createBitmap(t.width, t.height, Bitmap.Config.ARGB_8888);
                        bmp.setPixels(argb, 0, t.width, 0, 0, t.width, t.height);
                    } catch (Exception | OutOfMemoryError e) { bmp = null; }
                }
                cache.put(mat.texture, bmp);
            }
            d.partTexture[p] = cache.get(mat.texture);
            if (d.partTexture[p] == null) missing++;
        }
        if (d.mesh.uv == null) d.note = "This mesh has no texture coordinates.";
        else if (missing > 0) d.note = missing + " part(s) have no texture that could be found or decoded; they're shown grey.";
        return d;
    }

    static void show(Activity activity, Data d, Saver saver) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 24, 40));
        float density = activity.getResources().getDisplayMetrics().density;

        TextView title = new TextView(activity);
        title.setText(d.mesh.name + " · " + d.mesh.vertexCount + " vertices · " + d.mesh.parts.length + " part" + (d.mesh.parts.length == 1 ? "" : "s"));
        title.setTextColor(Color.WHITE); title.setTextSize(15);
        title.setPadding((int) (12 * density), (int) (10 * density), (int) (12 * density), (int) (4 * density));
        root.addView(title);

        LinearLayout bar = new LinearLayout(activity);
        Button mode = new Button(activity), part = new Button(activity), save = new Button(activity), close = new Button(activity);
        for (Button b : new Button[]{mode, part, save, close}) { b.setAllCaps(false); bar.addView(b, new LinearLayout.LayoutParams(0, (int) (46 * density), 1)); }
        mode.setText("UV view"); part.setText("Part: all"); save.setText("Save…"); close.setText("Close");
        root.addView(bar);

        TextView info = new TextView(activity);
        info.setTextColor(Color.rgb(200, 210, 235)); info.setTextSize(12);
        info.setPadding((int) (12 * density), (int) (2 * density), (int) (12 * density), (int) (6 * density));
        root.addView(info);

        FrameLayout stage = new FrameLayout(activity);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        ModelView gl = new ModelView(activity, d);
        UvView uv = new UvView(activity, d);
        stage.addView(gl, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(uv, new FrameLayout.LayoutParams(-1, -1));
        uv.setVisibility(View.GONE);

        TextView hint = new TextView(activity);
        hint.setTextColor(Color.rgb(150, 160, 190)); hint.setTextSize(11);
        hint.setPadding((int) (12 * density), (int) (4 * density), (int) (12 * density), (int) (8 * density));
        root.addView(hint);

        final int[] selected = {-1};
        final boolean[] uvMode = {false};
        Runnable refresh = () -> {
            int s = selected[0];
            int texPart = s >= 0 ? s : firstTextured(d);
            part.setText(s < 0 ? "Part: all" : "Part " + (s + 1) + "/" + d.mesh.parts.length);
            String line = s < 0 ? (d.mesh.parts.length + " parts. Pick a part to highlight it.")
                    : "Part " + (s + 1) + ": " + d.partNames[s] + " · " + (d.partTextureName[s] == null ? "no texture" : d.partTextureName[s]);
            if (uvMode[0] && texPart >= 0 && d.partTextureName[texPart] != null && s < 0) line = "Texture: " + d.partTextureName[texPart] + ". Pick a part to see others.";
            info.setText(line + (d.note == null ? "" : "\n" + d.note));
            hint.setText(uvMode[0] ? "UV view: the selected part's outline (bright) on its texture; other parts on the same texture are dim. Pinch to zoom, drag to move."
                    : "Drag to rotate · pinch to zoom · double-tap to reset");
            gl.select(s);
            uv.select(texPart, s);
            mode.setText(uvMode[0] ? "3D view" : "UV view");
        };
        mode.setOnClickListener(v -> {
            uvMode[0] = !uvMode[0];
            uv.setVisibility(uvMode[0] ? View.VISIBLE : View.GONE);
            gl.setVisibility(uvMode[0] ? View.GONE : View.VISIBLE);
            refresh.run();
        });
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
        save.setOnClickListener(v -> {
            List<String> options = new ArrayList<>();
            options.add("Model as OBJ (with UVs)");
            int texPart = selected[0] >= 0 ? selected[0] : firstTextured(d);
            if (texPart >= 0 && d.partTexture[texPart] != null && d.mesh.uv != null) options.add("UV layout PNG (texture + outlines)");
            if (texPart >= 0 && d.mesh.uv != null) options.add("UV outlines only (transparent PNG)");
            new AlertDialog.Builder(activity).setTitle("Save").setItems(options.toArray(new String[0]), (dd, w) -> {
                try {
                    if (w == 0) saver.save(UnityObb.safe(d.mesh.name) + ".obj", "application/octet-stream",
                            UnityModels.toObj(d.mesh).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    else {
                        boolean withTexture = options.get(w).startsWith("UV layout");
                        Bitmap b = uv.render(texPart, selected[0], withTexture);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        b.compress(Bitmap.CompressFormat.PNG, 100, out);
                        b.recycle();
                        String base = UnityObb.safe(d.mesh.name) + (selected[0] >= 0 ? "_part" + (selected[0] + 1) : "");
                        saver.save(base + (withTexture ? "_uv.png" : "_uv_lines.png"), "image/png", out.toByteArray());
                    }
                } catch (Exception | OutOfMemoryError e) {
                    new AlertDialog.Builder(activity).setTitle("Couldn't save").setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK", null).show();
                }
            }).show();
        });
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(x -> { gl.onPause(); uv.release(); });
        dialog.setContentView(root);
        refresh.run();
        dialog.show();
    }

    static int firstTextured(Data d) {
        for (int i = 0; i < d.partTexture.length; i++) if (d.partTexture[i] != null) return i;
        return d.mesh.parts.length > 0 ? 0 : -1;
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
            setPreserveEGLContextOnPause(false);
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
        boolean cutout() { return renderer.cutout; }
        void toggleCutout() { renderer.cutout = !renderer.cutout; requestRender(); }

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
        volatile float yaw = 25, pitch = 10, zoom = 1;
        volatile int selected = -1;
        /** Discard nearly transparent texels (hair cards, eyelashes). Off for textures whose alpha means something else. */
        volatile boolean cutout = true;
        private int program, vbo, aspectW = 1, aspectH = 1;
        private int[] ibo, tex;
        private float[] center = new float[3]; private float radius = 1;
        private final float[] proj = new float[16], view = new float[16], model = new float[16], mvp = new float[16], tmp = new float[16];

        Renderer3D(Data d) {
            this.d = d;
            UnityModels.Mesh m = d.mesh;
            for (int k = 0; k < 3; k++) center[k] = (m.min[k] + m.max[k]) / 2;
            center[0] = -center[0]; // x is mirrored below (Unity is left-handed)
            float dx = m.max[0] - m.min[0], dy = m.max[1] - m.min[1], dz = m.max[2] - m.min[2];
            radius = Math.max(1e-4f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        }

        void reset() { yaw = 25; pitch = 10; zoom = 1; }

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
                "  vec3 col = c.rgb * l;\n" +
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

            UnityModels.Mesh m = d.mesh;
            int n = m.vertexCount;
            float[] normals = m.normal != null ? m.normal : smoothNormals(m);
            FloatBuffer fb = ByteBuffer.allocateDirect(n * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int i = 0; i < n; i++) {
                fb.put(-m.pos[i * 3]).put(m.pos[i * 3 + 1]).put(m.pos[i * 3 + 2]);
                fb.put(-normals[i * 3]).put(normals[i * 3 + 1]).put(normals[i * 3 + 2]);
                if (m.uv != null) fb.put(m.uv[i * 2]).put(m.uv[i * 2 + 1]); else fb.put(0).put(0);
            }
            fb.position(0);
            int[] ids = new int[1];
            GLES30.glGenBuffers(1, ids, 0); vbo = ids[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 32, fb, GLES30.GL_STATIC_DRAW);

            ibo = new int[m.parts.length];
            if (ibo.length > 0) GLES30.glGenBuffers(ibo.length, ibo, 0);
            for (int p = 0; p < m.parts.length; p++) {
                int[] idx = m.parts[p];
                IntBuffer ib = ByteBuffer.allocateDirect(Math.max(4, idx.length * 4)).order(ByteOrder.nativeOrder()).asIntBuffer();
                ib.put(idx); ib.position(0);
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[p]);
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.length * 4, ib, GLES30.GL_STATIC_DRAW);
            }

            tex = new int[m.parts.length];
            java.util.Map<Bitmap, Integer> uploaded = new java.util.IdentityHashMap<>();
            for (int p = 0; p < m.parts.length; p++) {
                Bitmap b = d.partTexture[p];
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

        static float[] smoothNormals(UnityModels.Mesh m) {
            float[] n = new float[m.vertexCount * 3];
            for (int[] part : m.parts) for (int i = 0; i + 2 < part.length; i += 3) {
                int a = part[i], b = part[i + 1], c = part[i + 2];
                float ux = m.pos[b * 3] - m.pos[a * 3], uy = m.pos[b * 3 + 1] - m.pos[a * 3 + 1], uz = m.pos[b * 3 + 2] - m.pos[a * 3 + 2];
                float vx = m.pos[c * 3] - m.pos[a * 3], vy = m.pos[c * 3 + 1] - m.pos[a * 3 + 1], vz = m.pos[c * 3 + 2] - m.pos[a * 3 + 2];
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                for (int v : new int[]{a, b, c}) { n[v * 3] += nx; n[v * 3 + 1] += ny; n[v * 3 + 2] += nz; }
            }
            return n;
        }

        @Override public void onSurfaceChanged(GL10 unused, int w, int h) {
            GLES30.glViewport(0, 0, w, h);
            aspectW = Math.max(1, w); aspectH = Math.max(1, h);
        }

        @Override public void onDrawFrame(GL10 unused) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            float dist = radius / (float) Math.sin(Math.toRadians(20)) * 1.05f / zoom;
            android.opengl.Matrix.perspectiveM(proj, 0, 40, aspectW / (float) aspectH, Math.max(dist - radius * 3, dist * 0.01f), dist + radius * 3);
            android.opengl.Matrix.setLookAtM(view, 0, 0, 0, dist, 0, 0, 0, 0, 1, 0);
            android.opengl.Matrix.setIdentityM(model, 0);
            android.opengl.Matrix.rotateM(model, 0, pitch, 1, 0, 0);
            android.opengl.Matrix.rotateM(model, 0, yaw, 0, 1, 0);
            android.opengl.Matrix.translateM(model, 0, -center[0], -center[1], -center[2]);
            android.opengl.Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
            android.opengl.Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);

            GLES30.glUseProgram(program);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uModel"), 1, false, model, 0);
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
                if (d.mesh.parts[p].length == 0) continue;
                boolean has = tex[p] != 0;
                if (has) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[p]);
                GLES30.glUniform1i(uHas, has ? 1 : 0);
                GLES30.glUniform1f(uDim, sel < 0 || sel == p ? 1f : 0.18f);
                GLES30.glUniform1f(uCut, cutout ? 0.3f : -1f);
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[p]);
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, d.mesh.parts[p].length, GLES30.GL_UNSIGNED_INT, 0);
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

        int size() {
            Bitmap t = texPart >= 0 ? d.partTexture[texPart] : null;
            return t != null ? Math.max(t.getWidth(), t.getHeight()) : 1024;
        }
        int texW() { Bitmap t = texPart >= 0 ? d.partTexture[texPart] : null; return t != null ? t.getWidth() : 1024; }
        int texH() { Bitmap t = texPart >= 0 ? d.partTexture[texPart] : null; return t != null ? t.getHeight() : 1024; }

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
            Bitmap tex = texturePart >= 0 ? d.partTexture[texturePart] : null;
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
            Bitmap tex = texturePart >= 0 ? d.partTexture[texturePart] : null;
            float stroke = Math.max(1f, Math.max(w, h) / 1024f);
            for (int pass = 0; pass < 2; pass++) {
                for (int p = 0; p < d.mesh.parts.length; p++) {
                    boolean sameTexture = tex != null ? d.partTexture[p] == tex : p == texturePart;
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
                        float ax = uv[t[i] * 2] * w, ay = (1 - uv[t[i] * 2 + 1]) * h;
                        path.moveTo(ax, ay);
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
            Bitmap tex = texPart >= 0 ? d.partTexture[texPart] : null;
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
