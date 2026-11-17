package com.retrotools.cciextractor;

import java.util.List;

/**
 * Projection painting: stamps a 2D design onto a model's texture as seen from the current 3D view, like Blender's
 * stencil projection paint.
 *
 * For every texel of the texture that a triangle covers, its 3D position is projected to the screen; if that point
 * faces the camera, isn't hidden behind other geometry (depth buffer) and falls inside the design, the design is
 * blended into the texel. Texels just outside UV islands are padded with painted colour so mipmaps don't show seams.
 * Texels shared by far-apart triangles (mirrored UVs) are counted so the user can be warned.
 *
 * Pure Java (no Android), and the camera maths here is also what the 3D viewer draws with, so the stamp lands exactly
 * where the preview showed it. Matrices are column-major (OpenGL order).
 */
final class UnityPaint {

    // ------------------------------------------------------------------ camera
    static final class View3D {
        final float[] mvp = new float[16], mv = new float[16], model = new float[16];
        int width, height;
    }

    static float[] identity() { float[] m = new float[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }

    static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) for (int row = 0; row < 4; row++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
            r[c * 4 + row] = s;
        }
        return r;
    }

    static float[] perspective(float fovyDeg, float aspect, float near, float far) {
        float f = (float) (1 / Math.tan(Math.toRadians(fovyDeg) / 2));
        float[] m = new float[16];
        m[0] = f / aspect; m[5] = f;
        m[10] = (far + near) / (near - far); m[11] = -1;
        m[14] = 2 * far * near / (near - far);
        return m;
    }

    static float[] translate(float x, float y, float z) { float[] m = identity(); m[12] = x; m[13] = y; m[14] = z; return m; }

    static float[] rotateX(float deg) {
        float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg));
        float[] m = identity(); m[5] = c; m[6] = s; m[9] = -s; m[10] = c; return m;
    }

    static float[] rotateY(float deg) {
        float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg));
        float[] m = identity(); m[0] = c; m[2] = -s; m[8] = s; m[10] = c; return m;
    }

    /**
     * Snap angles (yaw) for the view buttons. Unity models face +z, so yaw 0 is the front. The viewer mirrors x into a
     * right-handed space, which puts the model's own left side at +x; turning by -90° brings it to the camera.
     */
    static final float YAW_FRONT = 0, YAW_LEFT = 270, YAW_BACK = 180, YAW_RIGHT = 90;

    /** The viewer's orbit camera: model rotated (pitch, then yaw) about its centre, seen from distance along +z. */
    static View3D camera(float yaw, float pitch, float zoom, float[] center, float radius, int width, int height) {
        View3D v = new View3D();
        v.width = Math.max(1, width); v.height = Math.max(1, height);
        float dist = radius / (float) Math.sin(Math.toRadians(20)) * 1.05f / zoom;
        float near = Math.max(dist - radius * 3, dist * 0.01f), far = dist + radius * 3;
        float[] proj = perspective(40, v.width / (float) v.height, near, far);
        float[] model = mul(mul(rotateX(pitch), rotateY(yaw)), translate(-center[0], -center[1], -center[2]));
        float[] mv = mul(translate(0, 0, -dist), model);
        System.arraycopy(model, 0, v.model, 0, 16);
        System.arraycopy(mv, 0, v.mv, 0, 16);
        System.arraycopy(mul(proj, mv), 0, v.mvp, 0, 16);
        return v;
    }

    // ------------------------------------------------------------------ geometry as drawn (x mirrored)
    /** A mesh as the viewer draws it: x mirrored (Unity is left-handed), smooth normals if it had none. */
    static final class Geometry {
        final float[] pos, normal, uv; final int[][] parts; final float[] center = new float[3]; final float radius;
        Geometry(UnityModels.Mesh m) {
            int n = m.vertexCount;
            pos = new float[n * 3]; normal = new float[n * 3];
            float[] src = m.normal != null ? m.normal : smoothNormals(m);
            for (int i = 0; i < n; i++) {
                pos[i * 3] = -m.pos[i * 3]; pos[i * 3 + 1] = m.pos[i * 3 + 1]; pos[i * 3 + 2] = m.pos[i * 3 + 2];
                normal[i * 3] = -src[i * 3]; normal[i * 3 + 1] = src[i * 3 + 1]; normal[i * 3 + 2] = src[i * 3 + 2];
            }
            uv = m.uv; parts = m.parts;
            center[0] = -(m.min[0] + m.max[0]) / 2; center[1] = (m.min[1] + m.max[1]) / 2; center[2] = (m.min[2] + m.max[2]) / 2;
            float dx = m.max[0] - m.min[0], dy = m.max[1] - m.min[1], dz = m.max[2] - m.min[2];
            radius = Math.max(1e-4f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        }
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

    // ------------------------------------------------------------------ depth buffer (view-space distance)
    static final class Depth { final float[] z; final int w, h; final float sx, sy; Depth(int w, int h, float sx, float sy) { this.w = w; this.h = h; this.sx = sx; this.sy = sy; z = new float[w * h]; java.util.Arrays.fill(z, Float.MAX_VALUE); } }

    /** Screen x, y (pixels, top-left origin) and view distance of a point; null if behind the camera. */
    static float[] project(View3D v, float x, float y, float z) {
        float[] m = v.mvp;
        float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (cw <= 1e-6f) return null;
        float[] mv = v.mv;
        float vz = mv[2] * x + mv[6] * y + mv[10] * z + mv[14];
        return new float[]{(cx / cw + 1) / 2 * v.width, (1 - cy / cw) / 2 * v.height, -vz};
    }

    static Depth depth(Geometry g, View3D v, int maxSide) {
        float k = Math.min(1f, maxSide / (float) Math.max(v.width, v.height));
        int w = Math.max(1, (int) (v.width * k)), h = Math.max(1, (int) (v.height * k));
        Depth d = new Depth(w, h, k, k);
        int n = g.pos.length / 3;
        float[][] sp = new float[n][];
        for (int i = 0; i < n; i++) sp[i] = project(v, g.pos[i * 3], g.pos[i * 3 + 1], g.pos[i * 3 + 2]);
        for (int[] part : g.parts) for (int t = 0; t + 2 < part.length; t += 3) {
            float[] a = sp[part[t]], b = sp[part[t + 1]], c = sp[part[t + 2]];
            if (a == null || b == null || c == null) continue;
            float ax = a[0] * k, ay = a[1] * k, bx = b[0] * k, by = b[1] * k, cx = c[0] * k, cy = c[1] * k;
            float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            if (Math.abs(area) < 1e-9f) continue;
            int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
            int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
            float ia = 1 / a[2], ib = 1 / b[2], ic = 1 / c[2];
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area;
                float w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float z = 1 / (w0 * ia + w1 * ib + w2 * ic);
                int at = y * w + x;
                if (z < d.z[at]) d.z[at] = z;
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ the stamp
    static final class Decal {
        int[] argb; int w, h;
        /** Centre (screen pixels), width on screen (pixels), rotation (radians), opacity 0..1. */
        float cx, cy, size, angle, opacity = 1;
    }

    /** Surfaces turned further than ~75° from the camera aren't painted (cosine of the angle). */
    static final float MIN_FACING = 0.25f;

    static final class Result {
        int painted;   // texels changed
        int shared;    // of those, texels also used by a far-away part of the model (mirrored UVs)
        /** Worth telling the user: a real share of the paint also shows somewhere else on the model. */
        boolean mirrored() { return painted > 0 && shared > painted / 20; }
        int backfaces; // triangles skipped because they face away
    }

    private static int sample(Decal d, float u, float v) {
        // bilinear, straight alpha
        float x = u * d.w - 0.5f, y = v * d.h - 0.5f;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        float a = 0, r = 0, g = 0, b = 0;
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
            int sx = Math.max(0, Math.min(d.w - 1, x0 + i)), sy = Math.max(0, Math.min(d.h - 1, y0 + j));
            float wgt = (i == 0 ? 1 - fx : fx) * (j == 0 ? 1 - fy : fy);
            int c = d.argb[sy * d.w + sx];
            float ca = (c >>> 24) * wgt;
            a += ca; r += ((c >> 16) & 255) * ca; g += ((c >> 8) & 255) * ca; b += (c & 255) * ca;
        }
        if (a <= 0) return 0;
        return (Math.round(a) << 24) | (Math.round(r / a) << 16) | (Math.round(g / a) << 8) | Math.round(b / a);
    }

    /**
     * Stamps the decal into {@code pixels} (top-down ARGB, w×h) on the triangles of {@code paintParts}; every part
     * in {@code textureParts} uses this texture (for coverage/seams), and all parts of the geometry occlude.
     */
    static Result stamp(Geometry g, View3D v, Depth depth, Decal d, int[] pixels, int tw, int th,
                        List<Integer> paintParts, List<Integer> textureParts) {
        return stamp(g, v, depth, d, pixels, tw, th, paintParts, textureParts, null);
    }

    /**
     * As above, limited to an area: {@code area[part][triangle]} must be true for a triangle to get paint
     * ({@code area == null}: no limit; {@code area[part] == null}: none of that part).
     */
    static Result stamp(Geometry g, View3D v, Depth depth, Decal d, int[] pixels, int tw, int th,
                        List<Integer> paintParts, List<Integer> textureParts, boolean[][] area) {
        Result res = new Result();
        if (g.uv == null) return res;
        int n = tw * th;
        int[] owner = new int[n];           // first triangle covering each texel (global id), -1 = none
        java.util.Arrays.fill(owner, -1);
        float[] ownerPos = new float[n * 3]; // the 3D point that triangle puts there
        boolean[] painted = new boolean[n];
        int[] partBase = new int[g.parts.length];
        for (int p = 0, acc = 0; p < g.parts.length; p++) { partBase[p] = acc; acc += g.parts[p].length / 3; }
        float eps = g.radius * 0.015f, sharedDist2 = (g.radius * 0.03f) * (g.radius * 0.03f);
        float cos = (float) Math.cos(d.angle), sin = (float) Math.sin(d.angle);
        float dh = d.size * d.h / Math.max(1, d.w);
        float[] mv = v.mv;

        // coverage of every part using this texture, so padding never spills into other islands
        for (int p : textureParts) {
            final int[] idx = g.parts[p];
            rasterize(g, p, tw, th, (texel, tri, w0, w1, w2) -> {
                if (owner[texel] >= 0) return;
                owner[texel] = partBase[p] + tri;
                int ia = idx[tri * 3], ib = idx[tri * 3 + 1], ic = idx[tri * 3 + 2];
                for (int k = 0; k < 3; k++) ownerPos[texel * 3 + k] = w0 * g.pos[ia * 3 + k] + w1 * g.pos[ib * 3 + k] + w2 * g.pos[ic * 3 + k];
            });
        }

        for (int p : paintParts) {
            final int part = p;
            final boolean[] allowed = area == null ? null : area[part];
            if (area != null && allowed == null) continue;
            rasterize(g, p, tw, th, (texel, tri, w0, w1, w2) -> {
                if (allowed != null && !allowed[tri]) return;
                int[] idx = g.parts[part];
                int ia = idx[tri * 3], ib = idx[tri * 3 + 1], ic = idx[tri * 3 + 2];
                float x = w0 * g.pos[ia * 3] + w1 * g.pos[ib * 3] + w2 * g.pos[ic * 3];
                float y = w0 * g.pos[ia * 3 + 1] + w1 * g.pos[ib * 3 + 1] + w2 * g.pos[ic * 3 + 1];
                float z = w0 * g.pos[ia * 3 + 2] + w1 * g.pos[ib * 3 + 2] + w2 * g.pos[ic * 3 + 2];
                float nx = w0 * g.normal[ia * 3] + w1 * g.normal[ib * 3] + w2 * g.normal[ic * 3];
                float ny = w0 * g.normal[ia * 3 + 1] + w1 * g.normal[ib * 3 + 1] + w2 * g.normal[ic * 3 + 1];
                float nz = w0 * g.normal[ia * 3 + 2] + w1 * g.normal[ib * 3 + 2] + w2 * g.normal[ic * 3 + 2];
                // facing the camera? (view space: camera at origin)
                float px = mv[0] * x + mv[4] * y + mv[8] * z + mv[12];
                float py = mv[1] * x + mv[5] * y + mv[9] * z + mv[13];
                float pz = mv[2] * x + mv[6] * y + mv[10] * z + mv[14];
                float qx = mv[0] * nx + mv[4] * ny + mv[8] * nz, qy = mv[1] * nx + mv[5] * ny + mv[9] * nz, qz = mv[2] * nx + mv[6] * ny + mv[10] * nz;
                // facing the camera, and not at a grazing angle (that smears the design along the surface)
                float facing = (qx * -px + qy * -py + qz * -pz) /
                        (float) Math.sqrt((qx * qx + qy * qy + qz * qz) * (px * px + py * py + pz * pz) + 1e-30);
                if (facing <= MIN_FACING) return;
                float[] s = project(v, x, y, z);
                if (s == null) return;
                int dx = (int) (s[0] * depth.sx), dy = (int) (s[1] * depth.sy);
                if (dx < 0 || dy < 0 || dx >= depth.w || dy >= depth.h) return;
                // hidden behind something? (deepest of the 3×3 neighbourhood, so strands like a necklace don't
                // leave hairline gaps in the design; hair, arms and other real occluders still block it)
                float zMax = 0;
                for (int oy2 = -1; oy2 <= 1; oy2++) for (int ox2 = -1; ox2 <= 1; ox2++) {
                    int sx2 = Math.max(0, Math.min(depth.w - 1, dx + ox2)), sy2 = Math.max(0, Math.min(depth.h - 1, dy + oy2));
                    zMax = Math.max(zMax, depth.z[sy2 * depth.w + sx2]);
                }
                if (s[2] > zMax + eps) return;
                // inside the design?
                float ox = s[0] - d.cx, oy = s[1] - d.cy;
                float lx = ox * cos + oy * sin, ly = -ox * sin + oy * cos;
                float du = lx / d.size + 0.5f, dv = ly / dh + 0.5f;
                if (du < 0 || du >= 1 || dv < 0 || dv >= 1 || painted[texel]) return;
                int c = sample(d, du, dv);
                float a = (c >>> 24) / 255f * d.opacity;
                if (a <= 0) return;
                int dst = pixels[texel];
                int r = Math.round(((c >> 16) & 255) * a + ((dst >> 16) & 255) * (1 - a));
                int gg = Math.round(((c >> 8) & 255) * a + ((dst >> 8) & 255) * (1 - a));
                int b = Math.round((c & 255) * a + (dst & 255) * (1 - a));
                pixels[texel] = (dst & 0xFF000000) | (r << 16) | (gg << 8) | b; // keep the texture's own alpha
                painted[texel] = true;
                res.painted++;
                int o = owner[texel], me = partBase[part] + tri;
                if (o >= 0 && o != me) {
                    float ex = ownerPos[texel * 3] - x, ey = ownerPos[texel * 3 + 1] - y, ez = ownerPos[texel * 3 + 2] - z;
                    if (ex * ex + ey * ey + ez * ez > sharedDist2) res.shared++;
                }
            });
        }

        // pad 2 texels into the gaps around UV islands so filtering/mipmaps don't pull in old colour at seams
        boolean[] done = painted;
        for (int pass = 0; pass < 2; pass++) {
            boolean[] next = done.clone();
            for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) {
                int at = y * tw + x;
                if (done[at] || owner[at] >= 0) continue;
                for (int k = 0; k < 4; k++) {
                    int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (nx < 0 || ny < 0 || nx >= tw || ny >= th) continue;
                    int src = ny * tw + nx;
                    if (!done[src]) continue;
                    pixels[at] = (pixels[at] & 0xFF000000) | (pixels[src] & 0xFFFFFF);
                    next[at] = true;
                    break;
                }
            }
            done = next;
        }
        return res;
    }

    // ------------------------------------------------------------------ areas (connected pieces)
    /**
     * Splits every part into connected pieces: triangles that share a corner position (welded, so UV seams don't
     * split a piece) belong together. Clothing layers modelled separately (a T-shirt under a hoodie) come out as
     * separate pieces. Returns piece ids per part, per triangle.
     */
    static int[][] pieces(Geometry g) {
        int n = g.pos.length / 3;
        float q = Math.max(1e-6f, g.radius * 1e-4f);
        java.util.HashMap<Long, Integer> weld = new java.util.HashMap<>();
        int[] welded = new int[n];
        for (int i = 0; i < n; i++) {
            long x = Math.round(g.pos[i * 3] / q), y = Math.round(g.pos[i * 3 + 1] / q), z = Math.round(g.pos[i * 3 + 2] / q);
            long key = (x & 0x1FFFFF) | ((y & 0x1FFFFF) << 21) | ((z & 0x1FFFFF) << 42);
            Integer id = weld.get(key);
            if (id == null) { id = weld.size(); weld.put(key, id); }
            welded[i] = id;
        }
        int[][] out = new int[g.parts.length][];
        for (int p = 0; p < g.parts.length; p++) {
            int[] idx = g.parts[p];
            int[] parent = new int[weld.size()];
            for (int i = 0; i < parent.length; i++) parent[i] = i;
            for (int t = 0; t + 2 < idx.length; t += 3) {
                int a = find(parent, welded[idx[t]]);
                union(parent, a, welded[idx[t + 1]]);
                union(parent, a, welded[idx[t + 2]]);
            }
            int[] tri = new int[idx.length / 3];
            for (int t = 0; t < tri.length; t++) tri[t] = find(parent, welded[idx[t * 3]]);
            out[p] = tri;
        }
        return out;
    }
    private static int find(int[] p, int a) { while (p[a] != a) { p[a] = p[p[a]]; a = p[a]; } return a; }
    private static void union(int[] p, int a, int b) { a = find(p, a); b = find(p, b); if (a != b) p[b] = a; }

    /** The front-most triangle under a screen point: {part, triangle}, or null if the point misses the model. */
    static int[] pick(Geometry g, View3D v, float sx, float sy) {
        int n = g.pos.length / 3;
        float[][] sp = new float[n][];
        for (int i = 0; i < n; i++) sp[i] = project(v, g.pos[i * 3], g.pos[i * 3 + 1], g.pos[i * 3 + 2]);
        int[] best = null; float bestZ = Float.MAX_VALUE;
        for (int p = 0; p < g.parts.length; p++) {
            int[] idx = g.parts[p];
            for (int t = 0; t + 2 < idx.length; t += 3) {
                float[] a = sp[idx[t]], b = sp[idx[t + 1]], c = sp[idx[t + 2]];
                if (a == null || b == null || c == null) continue;
                float area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
                if (Math.abs(area) < 1e-9f) continue;
                float w0 = ((b[0] - sx) * (c[1] - sy) - (b[1] - sy) * (c[0] - sx)) / area;
                float w1 = ((c[0] - sx) * (a[1] - sy) - (c[1] - sy) * (a[0] - sx)) / area;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float z = w0 * a[2] + w1 * b[2] + w2 * c[2];
                if (z < bestZ) { bestZ = z; best = new int[]{p, t / 3}; }
            }
        }
        return best;
    }

    /** Area mask for {@link #stamp}: only the piece containing {@code triangle} of {@code part}. */
    static boolean[][] areaOf(int[][] pieces, int part, int triangle) {
        boolean[][] area = new boolean[pieces.length][];
        int id = pieces[part][triangle];
        area[part] = new boolean[pieces[part].length];
        for (int t = 0; t < pieces[part].length; t++) area[part][t] = pieces[part][t] == id;
        return area;
    }

    interface TexelVisitor { void visit(int texel, int triangle, float w0, float w1, float w2); }

    /** Visits every texel centre inside each triangle of a part, in UV space (wrapped into the texture). */
    static void rasterize(Geometry g, int part, int tw, int th, TexelVisitor visit) {
        int[] idx = g.parts[part];
        float[] uv = g.uv;
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t], b = idx[t + 1], c = idx[t + 2];
            float au = uv[a * 2], av = uv[a * 2 + 1], bu = uv[b * 2], bv = uv[b * 2 + 1], cu = uv[c * 2], cv = uv[c * 2 + 1];
            float ou = (float) Math.floor(Math.min(au, Math.min(bu, cu))), ov = (float) Math.floor(Math.min(av, Math.min(bv, cv)));
            float ax = (au - ou) * tw, ay = (1 - (av - ov)) * th, bx = (bu - ou) * tw, by = (1 - (bv - ov)) * th, cx = (cu - ou) * tw, cy = (1 - (cv - ov)) * th;
            // tiled UVs: a triangle starting above 1 in v maps from the top; shift into range
            if (Math.max(ay, Math.max(by, cy)) <= 0) { ay += th; by += th; cy += th; }
            float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            if (Math.abs(area) < 1e-12f) continue;
            int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = Math.min(tw - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
            int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = Math.min(th - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area;
                float w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area;
                float w2 = 1 - w0 - w1;
                if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
                visit.visit(y * tw + x, t / 3, w0, w1, w2);
            }
        }
    }
}
