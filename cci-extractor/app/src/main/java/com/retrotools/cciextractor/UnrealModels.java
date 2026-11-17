package com.retrotools.cciextractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 3D models in cooked Unreal Engine 4 packages (the 4.14 – 4.15 layout of the original Life is Strange on Android):
 * LOD 0 of SkeletalMesh and StaticMesh, their materials, and the diffuse texture each material uses.
 *
 * Meshes come out in the same convention as {@link UnityModels.Mesh} so the existing viewer can show and paint them:
 * Unreal is left-handed with Z up, so (x, y, z) becomes (-x, z, y), which keeps the model's front facing the
 * viewer's Front button. Unreal texture rows run top-down, so v becomes 1 - v.
 */
final class UnrealModels {

    static final class MeshRef {
        int pak; String path, name, cls; int export, size;
        boolean skeletal() { return "SkeletalMesh".equals(cls); }
        String label() {
            return name + (skeletal() ? "  · character/skinned" : "") + "\n" + path;
        }
    }

    /** A decoded mesh plus, per part, the material it uses (object path, or null). */
    static final class Loaded {
        UnityModels.Mesh mesh;
        String[] partMaterial;
        Raw raw;
    }

    static boolean isMeshClass(String cls) { return "SkeletalMesh".equals(cls) || "StaticMesh".equals(cls); }

    // ------------------------------------------------------------------ meshes
    static Loaded mesh(byte[] d, UnrealAssets.Package pk, UnrealAssets.Export x) throws IOException {
        if (x.serialOffset < 0 || x.serialSize < 0 || (long) x.serialOffset + x.serialSize > d.length)
            throw new IOException("Export outside the package");
        String cls = UnrealAssets.className(pk, x.classIndex);
        Raw raw = "SkeletalMesh".equals(cls) ? skeletal(d, pk, x) : "StaticMesh".equals(cls) ? staticMesh(d, pk, x) : null;
        if (raw == null) throw new IOException(x.name + " isn't a mesh");
        if (raw.end > x.serialOffset + x.serialSize) throw new IOException("Mesh data runs past its export");
        return convert(x.name, raw, pk);
    }

    private static final class Raw {
        int[] matObject;        // object index per material slot
        int[] sectionMat, sectionBase, sectionTris;
        int[] indices;
        float[] pos, normal, uv; // Unreal space, uv as stored
        int vertexCount, end;
        // where things are in the package, for writing edits back
        int vertAt, stride, normalAt, posInVert = -1, posAt = -1, uvAt, idxAt, idxEs, boundsAt = -1;
        boolean shortNormals, fullUv;
    }

    private static int count(UnrealAssets.Le r, int max) throws IOException {
        int n = r.i32();
        if (n < 0 || n > max) throw new IOException("Bad mesh table size " + n);
        return n;
    }

    static Raw skeletal(byte[] d, UnrealAssets.Package pk, UnrealAssets.Export x) throws IOException {
        UnrealAssets.Le r = new UnrealAssets.Le(d, x.serialOffset);
        UnrealAssets.properties(r, pk);
        if (r.i32() != 0) r.skip(16);                 // object guid
        r.skip(2);                                    // strip flags
        Raw m = new Raw();
        m.boundsAt = r.p;
        r.skip(28);                                   // imported bounds
        int mats = count(r, 1000);
        m.matObject = new int[mats];
        for (int i = 0; i < mats; i++) { m.matObject[i] = r.i32(); r.skip(8 + 24); }  // material, slot name, uv density / flags
        int bones = count(r, 100_000); r.skip(bones * 12);   // reference skeleton: name + parent
        int pose = count(r, 100_000); r.skip(pose * 40);     // reference pose transforms
        int map = count(r, 100_000); r.skip(map * 12);       // bone name → index
        if (count(r, 64) < 1) throw new IOException("Mesh has no LODs");
        r.skip(2);                                            // strip flags
        int secs = count(r, 1000);
        m.sectionMat = new int[secs]; m.sectionBase = new int[secs]; m.sectionTris = new int[secs];
        for (int s = 0; s < secs; s++) {
            r.skip(2);
            m.sectionMat[s] = r.u16(); m.sectionBase[s] = r.i32(); m.sectionTris[s] = r.i32();
            r.skip(1 + 4 + 2 + 1 + 4 + 4 + 4);                // sort, disabled, cloth section/LOD, recompute tangent, shadow, base vertex
            int bm = count(r, 1 << 16); r.skip(bm * 2);
            r.i32(); r.i32();                                 // vertex count, max influences
            int a = count(r, 1 << 20); r.skip(a * 64);
            a = count(r, 1 << 20); r.skip(a * 12);
            a = count(r, 1 << 20); r.skip(a * 12);
            r.skip(4);                                        // cloth asset / submesh
        }
        r.u8();
        int es = r.i32(), n = count(r, 1 << 26);
        if (es != 2 && es != 4) throw new IOException("Bad index size " + es);
        m.idxAt = r.p; m.idxEs = es;
        m.indices = indices(r, es, n);
        int a = count(r, 1 << 20); r.skip(a * 2);                 // active bones
        r.i32();
        int nv = r.i32();
        a = count(r, 1 << 20); r.skip(a * 2);                     // required bones
        a = count(r, 1 << 24); r.skip(a * 4);                     // mesh to import vertex map
        r.i32();
        r.i32(); r.skip(2);
        int uvs = r.i32(); boolean full = r.i32() != 0;
        r.skip(24);                                               // mesh extension / origin
        es = r.i32(); n = r.i32();
        int uvSize = full ? 8 : 4;
        if (uvs < 1 || uvs > 8 || es != 20 + uvSize * uvs || n != nv || n < 0 || n > 1 << 24)
            throw new IOException("Unexpected skinned vertex layout");
        r.need(es * n);
        m.vertAt = r.p; m.stride = es; m.normalAt = 4; m.posInVert = 8; m.uvAt = 20; m.fullUv = full;
        vertices(m, d, r.p, n, es, 4, false, 8, 20, full);
        r.skip(es * n);
        m.end = r.p;
        return m;
    }

    static Raw staticMesh(byte[] d, UnrealAssets.Package pk, UnrealAssets.Export x) throws IOException {
        UnrealAssets.Le r = new UnrealAssets.Le(d, x.serialOffset);
        List<UnrealAssets.Prop> props = UnrealAssets.properties(r, pk);
        Raw m = new Raw();
        UnrealAssets.Prop sm = UnrealAssets.find(props, "StaticMaterials");
        List<Integer> mats = new ArrayList<>();
        if (sm != null) for (List<UnrealAssets.Prop> el : UnrealAssets.structArray(d, pk, sm)) {
            UnrealAssets.Prop mi = UnrealAssets.find(el, "MaterialInterface");
            mats.add(mi == null ? 0 : UnrealAssets.i32At(d, mi.pos));
        }
        if (r.i32() != 0) r.skip(16);
        r.skip(2);
        if (r.i32() != 1) throw new IOException("Mesh isn't cooked");
        r.i32(); r.i32(); r.skip(16);                               // lighting guid
        int sockets = count(r, 100_000); r.skip(sockets * 4);
        if (count(r, 64) < 1) throw new IOException("Mesh has no LODs");
        r.skip(2);
        int secs = count(r, 1000);
        m.sectionMat = new int[secs]; m.sectionBase = new int[secs]; m.sectionTris = new int[secs];
        for (int s = 0; s < secs; s++) {
            m.sectionMat[s] = r.i32(); m.sectionBase[s] = r.i32(); m.sectionTris[s] = r.i32();
            r.skip(16);                                             // min/max vertex, collision, shadow
        }
        r.skip(4);                                                  // max deviation
        int stride = r.i32(), nv = r.i32(), es = r.i32(), n = r.i32();
        if (stride != 12 || es != 12 || n != nv || n < 0 || n > 1 << 24) throw new IOException("Unexpected position buffer");
        r.need(12 * n);
        int posAt = r.p; r.skip(12 * n);
        r.skip(2);
        int uvs = r.i32(), stride2 = r.i32(), nv2 = r.i32(); boolean full = r.i32() != 0, hp = r.i32() != 0;
        es = r.i32(); n = r.i32();
        int tb = hp ? 16 : 8;
        if (n != nv || nv2 != nv || es != stride2 || uvs < 1 || uvs > 8 || es != tb + (full ? 8 : 4) * uvs)
            throw new IOException("Unexpected static vertex layout");
        r.need(es * n);
        m.posAt = posAt; m.vertAt = r.p; m.stride = es; m.normalAt = hp ? 8 : 4; m.shortNormals = hp; m.uvAt = tb; m.fullUv = full;
        m.pos = new float[nv * 3];
        for (int i = 0; i < nv * 3; i++) m.pos[i] = Float.intBitsToFloat(le32(d, posAt + i * 4));
        vertices(m, d, r.p, n, es, hp ? 8 : 4, hp, -1, tb, full);
        r.skip(es * n);
        r.skip(2);
        r.i32(); int colours = r.i32();
        if (colours > 0) { int ces = r.i32(), cn = count(r, 1 << 24); r.skip(ces * cn); }
        boolean b32 = r.i32() != 0;
        if (r.i32() != 1) throw new IOException("Unexpected index buffer");
        int bytes = count(r, 1 << 28);
        m.idxAt = r.p; m.idxEs = b32 ? 4 : 2;
        m.indices = indices(r, b32 ? 4 : 2, bytes / (b32 ? 4 : 2));
        m.matObject = new int[mats.size()];
        for (int i = 0; i < mats.size(); i++) m.matObject[i] = mats.get(i);
        // Cooked builds leave MaterialInterface out of the property copy; the native copy at the very end of the export has it:
        // count, then per slot: material, slot name, UV channel data (initialised flag, override flag, 4 densities) = 36 bytes.
        int end = x.serialOffset + x.serialSize, n2 = mats.size(), at = end - 4 - 36 * n2;
        if (n2 > 0 && at >= r.p && UnrealAssets.i32At(d, at) == n2)
            for (int i = 0; i < n2; i++) if (m.matObject[i] == 0) m.matObject[i] = UnrealAssets.i32At(d, at + 4 + 36 * i);
        m.end = r.p;
        return m;
    }

    private static int le32(byte[] d, int p) { return (d[p] & 255) | ((d[p + 1] & 255) << 8) | ((d[p + 2] & 255) << 16) | (d[p + 3] << 24); }

    private static int[] indices(UnrealAssets.Le r, int es, int n) throws IOException {
        r.need(es * n);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = es == 2 ? r.u16() : r.i32();
        return out;
    }

    /** Normals (packed bytes or shorts) and the first UV set; positions too when {@code posAt >= 0}. */
    private static void vertices(Raw m, byte[] d, int at, int n, int stride, int normalAt, boolean shortNormals, int posAt, int uvAt, boolean fullUv) {
        m.vertexCount = n;
        if (posAt >= 0) m.pos = new float[n * 3];
        m.normal = new float[n * 3]; m.uv = new float[n * 2];
        for (int i = 0; i < n; i++) {
            int v = at + i * stride;
            for (int k = 0; k < 3; k++) {
                m.normal[i * 3 + k] = shortNormals ? (short) ((d[v + normalAt + 2 * k] & 255) | (d[v + normalAt + 2 * k + 1] << 8)) / 32767f
                        : (d[v + normalAt + k] & 255) / 127.5f - 1f;
                if (posAt >= 0) m.pos[i * 3 + k] = Float.intBitsToFloat(le32(d, v + posAt + 4 * k));
            }
            for (int k = 0; k < 2; k++)
                m.uv[i * 2 + k] = fullUv ? Float.intBitsToFloat(le32(d, v + uvAt + 4 * k))
                        : UnityModels.half((d[v + uvAt + 2 * k] & 255) | ((d[v + uvAt + 2 * k + 1] & 255) << 8));
        }
    }

    /** Unreal → viewer convention, one part per section, with checked index ranges. */
    private static Loaded convert(String name, Raw raw, UnrealAssets.Package pk) throws IOException {
        UnityModels.Mesh mesh = new UnityModels.Mesh();
        int n = raw.vertexCount;
        mesh.name = name; mesh.vertexCount = n;
        mesh.pos = new float[n * 3]; mesh.normal = new float[n * 3]; mesh.uv = new float[n * 2];
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < n; i++) {
            float x = raw.pos[i * 3], y = raw.pos[i * 3 + 1], z = raw.pos[i * 3 + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) throw new IOException("Bad vertex position");
            mesh.pos[i * 3] = -x; mesh.pos[i * 3 + 1] = z; mesh.pos[i * 3 + 2] = y;
            mesh.normal[i * 3] = -raw.normal[i * 3]; mesh.normal[i * 3 + 1] = raw.normal[i * 3 + 2]; mesh.normal[i * 3 + 2] = raw.normal[i * 3 + 1];
            mesh.uv[i * 2] = raw.uv[i * 2]; mesh.uv[i * 2 + 1] = 1f - raw.uv[i * 2 + 1];
            for (int k = 0; k < 3; k++) { min[k] = Math.min(min[k], mesh.pos[i * 3 + k]); max[k] = Math.max(max[k], mesh.pos[i * 3 + k]); }
        }
        if (n == 0) { min = new float[3]; max = new float[3]; }
        mesh.min = min; mesh.max = max;
        int secs = raw.sectionMat.length;
        mesh.parts = new int[secs][];
        Loaded out = new Loaded();
        out.raw = raw;
        out.partMaterial = new String[secs];
        for (int s = 0; s < secs; s++) {
            long base = raw.sectionBase[s] & 0xffffffffL, len = (raw.sectionTris[s] & 0xffffffffL) * 3;
            if (base + len > raw.indices.length) throw new IOException("Section outside the index buffer");
            int[] part = new int[(int) len];
            for (int i = 0; i < len; i++) {
                int v = raw.indices[(int) base + i];
                if (v < 0 || v >= n) throw new IOException("Index outside the vertex buffer");
                part[i] = v;
            }
            mesh.parts[s] = part;
            int mi = raw.sectionMat[s];
            out.partMaterial[s] = mi >= 0 && mi < raw.matObject.length ? UnrealAssets.importPath(pk, raw.matObject[mi]) : null;
        }
        out.mesh = mesh;
        return out;
    }

    // ------------------------------------------------------------------ writing a model edit back
    /**
     * Writes an {@link ObjEdit.Edit} (viewer convention, for this mesh's LOD 0) into the package in place:
     * positions, normals (the packed tangent-Z; tangent-X and skin weights stay), the first UV set, and deleted
     * faces as zero-area triangles. Skinned meshes also get their bounds widened if the edit grew the model.
     * The package keeps its exact size.
     */
    static void writeEdit(byte[] d, UnrealAssets.Package pk, UnrealAssets.Export x, ObjEdit.Edit e) throws IOException {
        Loaded l = mesh(d, pk, x);
        Raw m = l.raw;
        int n = m.vertexCount;
        if (e.pos.length != n * 3 || e.hidden.length != m.sectionMat.length) throw new IOException("The edit doesn't match this model");
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < n; i++) {
            // viewer (-x, z, y) back to Unreal (x, y, z)
            float ux = -e.pos[i * 3], uy = e.pos[i * 3 + 2], uz = e.pos[i * 3 + 1];
            float nx = -e.normal[i * 3], ny = e.normal[i * 3 + 2], nz = e.normal[i * 3 + 1];
            float[] up = {ux, uy, uz};
            for (int k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], up[k]); hi[k] = Math.max(hi[k], up[k]); }
            int v = m.vertAt + i * m.stride;
            int pAt = m.posInVert >= 0 ? v + m.posInVert : m.posAt + i * 12;
            for (int k = 0; k < 3; k++) putF(d, pAt + 4 * k, up[k]);
            float[] nn = {nx, ny, nz};
            for (int k = 0; k < 3; k++) {
                if (m.shortNormals) {
                    int q = Math.round(Math.max(-1f, Math.min(1f, nn[k])) * 32767f);
                    d[v + m.normalAt + 2 * k] = (byte) q; d[v + m.normalAt + 2 * k + 1] = (byte) (q >> 8);
                } else d[v + m.normalAt + k] = (byte) Math.round((Math.max(-1f, Math.min(1f, nn[k])) + 1f) * 127.5f);
            }
            if (e.uv != null) {
                float u = e.uv[i * 2], vv = 1f - e.uv[i * 2 + 1];
                if (m.fullUv) { putF(d, v + m.uvAt, u); putF(d, v + m.uvAt + 4, vv); }
                else { putH(d, v + m.uvAt, u); putH(d, v + m.uvAt + 2, vv); }
            }
        }
        for (int s = 0; s < e.hidden.length; s++) {
            if (e.hidden[s].length != m.sectionTris[s]) throw new IOException("The edit doesn't match this model's parts");
            for (int t = 0; t < e.hidden[s].length; t++) {
                if (!e.hidden[s][t]) continue;
                int at = m.idxAt + (m.sectionBase[s] + t * 3) * m.idxEs;
                int first = m.indices[m.sectionBase[s] + t * 3];
                for (int k = 1; k < 3; k++) {
                    if (m.idxEs == 2) { d[at + 2 * k] = (byte) first; d[at + 2 * k + 1] = (byte) (first >> 8); }
                    else putI(d, at + 4 * k, first);
                }
            }
        }
        if (m.boundsAt >= 0 && n > 0) {
            // FBoxSphereBounds: origin, box extent, sphere radius; widen to cover the edit
            float[] o = new float[3], ex = new float[3];
            for (int k = 0; k < 3; k++) { o[k] = getF(d, m.boundsAt + 4 * k); ex[k] = getF(d, m.boundsAt + 12 + 4 * k); }
            float r = getF(d, m.boundsAt + 24);
            boolean grow = false;
            for (int k = 0; k < 3; k++) if (lo[k] < o[k] - ex[k] || hi[k] > o[k] + ex[k]) grow = true;
            if (grow) {
                float r2 = 0;
                for (int k = 0; k < 3; k++) {
                    float a = Math.min(lo[k], o[k] - ex[k]), b = Math.max(hi[k], o[k] + ex[k]);
                    o[k] = (a + b) / 2; ex[k] = (b - a) / 2; r2 += ex[k] * ex[k];
                }
                for (int k = 0; k < 3; k++) { putF(d, m.boundsAt + 4 * k, o[k]); putF(d, m.boundsAt + 12 + 4 * k, ex[k]); }
                putF(d, m.boundsAt + 24, Math.max(r, (float) Math.sqrt(r2)));
            }
        }
    }

    private static void putI(byte[] d, int p, int v) { d[p] = (byte) v; d[p + 1] = (byte) (v >> 8); d[p + 2] = (byte) (v >> 16); d[p + 3] = (byte) (v >> 24); }
    private static void putF(byte[] d, int p, float f) { putI(d, p, Float.floatToIntBits(f)); }
    private static float getF(byte[] d, int p) { return Float.intBitsToFloat(le32(d, p)); }
    private static void putH(byte[] d, int p, float f) { int h = toHalf(f); d[p] = (byte) h; d[p + 1] = (byte) (h >> 8); }

    /** float → IEEE half, round to nearest even. */
    static int toHalf(float f) {
        int b = Float.floatToIntBits(f), sign = (b >>> 16) & 0x8000, exp = ((b >>> 23) & 0xff) - 127 + 15, mant = b & 0x7fffff;
        if (((b >>> 23) & 0xff) == 0xff) return sign | 0x7c00 | (mant != 0 ? 0x200 : 0);
        if (exp >= 31) return sign | 0x7c00;
        if (exp <= 0) {
            if (exp < -10) return sign;
            mant |= 0x800000;
            int shift = 14 - exp, half = mant >> shift, rem = mant & ((1 << shift) - 1), mid = 1 << (shift - 1);
            if (rem > mid || (rem == mid && (half & 1) != 0)) half++;
            return sign | half;
        }
        int half = (exp << 10) | (mant >> 13), rem = mant & 0x1fff;
        if (rem > 0x1000 || (rem == 0x1000 && (half & 1) != 0)) half++;
        return sign | half;
    }

    // ------------------------------------------------------------------ materials
    /** A material's diffuse texture (object path) and whether its alpha means "see-through". */
    static final class Material { String name, texture; boolean cutout; }

    /** Package file for an object path: /Game/A/B/Obj → Project/Content/A/B.uasset, /Engine/… → Engine/Content/…. */
    static String packagePath(String objectPath, String project) {
        if (objectPath == null) return null;
        int slash = objectPath.lastIndexOf('/');
        if (slash <= 0) return null;
        String pkg = objectPath.substring(0, slash);
        if (pkg.startsWith("/Game/")) return project + "/Content/" + pkg.substring(6) + ".uasset";
        if (pkg.startsWith("/Engine/")) return "Engine/Content/" + pkg.substring(8) + ".uasset";
        return null;
    }

    /** "LiS" from "LiS/Content/…". */
    static String project(String packagePath) {
        int i = packagePath.indexOf("/Content/");
        return i > 0 ? packagePath.substring(0, i) : "";
    }

    static String objectName(String objectPath) { return objectPath.substring(objectPath.lastIndexOf('/') + 1); }

    private static final String[] NOT_DIFFUSE = {"normal", "spec", "rough", "mask", "rgb", "emis", "opacity", "detail", "cube", "env"};
    private static final String[] NOT_DIFFUSE_SUFFIX = {"_n", "_s", "_m", "_r", "_e"};

    /** Picks the colour texture: a parameter named like Diffuse / BaseColor, then a *_D texture, then anything not obviously a data map. */
    static String pickDiffuse(List<String[]> candidates) { // {parameter name or null, texture object path}
        for (String[] c : candidates) {
            String p = c[0] == null ? "" : c[0].toLowerCase(Locale.ROOT);
            if (p.contains("diffuse") || p.contains("basecolor") || p.contains("base color") || p.contains("albedo")) return c[1];
        }
        for (String[] c : candidates) if (objectName(c[1]).toLowerCase(Locale.ROOT).endsWith("_d")) return c[1];
        outer:
        for (String[] c : candidates) {
            String s = ((c[0] == null ? "" : c[0]) + " " + objectName(c[1])).toLowerCase(Locale.ROOT);
            for (String k : NOT_DIFFUSE) if (s.contains(k)) continue outer;
            String obj = objectName(c[1]).toLowerCase(Locale.ROOT);
            for (String k : NOT_DIFFUSE_SUFFIX) if (obj.endsWith(k)) continue outer;
            return c[1];
        }
        return null;
    }

    interface PackageSource { byte[] read(String packagePath) throws IOException; }

    /** Follows instance → parent until a diffuse texture and a blend mode are known. */
    static Material material(PackageSource src, String objectPath, String project) {
        Material m = new Material();
        m.name = objectPath == null ? null : objectName(objectPath);
        String blend = null; boolean blendKnown = false;
        String path = objectPath;
        for (int depth = 0; path != null && depth < 8; depth++) {
            byte[] d;
            UnrealAssets.Package pk;
            try {
                String file = packagePath(path, project);
                if (file == null) break;
                d = src.read(file);
                if (d == null) break;
                pk = UnrealAssets.parse(d);
            } catch (IOException e) { break; }
            String want = objectName(path), next = null;
            for (UnrealAssets.Export x : pk.exports) {
                String cls = UnrealAssets.className(pk, x.classIndex);
                boolean instance = "MaterialInstanceConstant".equals(cls), base = "Material".equals(cls);
                if (!instance && !base) continue;
                if (!x.name.equals(want) && pk.exports.size() > 1) continue;
                try {
                    UnrealAssets.Le r = new UnrealAssets.Le(d, x.serialOffset);
                    List<UnrealAssets.Prop> props = UnrealAssets.properties(r, pk);
                    if (instance) {
                        UnrealAssets.Prop o = UnrealAssets.find(props, "BasePropertyOverrides");
                        if (o != null && !blendKnown) {
                            List<UnrealAssets.Prop> ov = UnrealAssets.properties(new UnrealAssets.Le(d, o.pos), pk);
                            UnrealAssets.Prop on = UnrealAssets.find(ov, "bOverride_BlendMode"), bm = UnrealAssets.find(ov, "BlendMode");
                            if (on != null && on.boolValue != 0) { blendKnown = true; blend = bm == null ? "BLEND_Opaque" : UnrealAssets.enumValue(d, pk, bm); }
                        }
                        if (m.texture == null) {
                            UnrealAssets.Prop tp = UnrealAssets.find(props, "TextureParameterValues");
                            List<String[]> cands = new ArrayList<>();
                            if (tp != null) for (List<UnrealAssets.Prop> el : UnrealAssets.structArray(d, pk, tp)) {
                                UnrealAssets.Prop pn = UnrealAssets.find(el, "ParameterName"), pv = UnrealAssets.find(el, "ParameterValue");
                                if (pv == null) continue;
                                String tex = UnrealAssets.importPath(pk, UnrealAssets.i32At(d, pv.pos));
                                if (tex == null) continue;
                                String pname = null;
                                if (pn != null && pn.size == 8) { UnrealAssets.Le nr = new UnrealAssets.Le(d, pn.pos); pname = UnrealAssets.name(pk, nr.i32(), nr.i32()); }
                                cands.add(new String[]{pname, tex});
                            }
                            m.texture = pickDiffuse(cands);
                        }
                        UnrealAssets.Prop parent = UnrealAssets.find(props, "Parent");
                        if (parent != null) next = UnrealAssets.importPath(pk, UnrealAssets.i32At(d, parent.pos));
                    } else {
                        if (!blendKnown) {
                            UnrealAssets.Prop bm = UnrealAssets.find(props, "BlendMode");
                            blendKnown = true; blend = bm == null ? "BLEND_Opaque" : UnrealAssets.enumValue(d, pk, bm);
                        }
                        if (m.texture == null) {
                            List<String[]> cands = new ArrayList<>();
                            for (int i = 0; i < pk.imports.size(); i++)
                                if ("Texture2D".equals(pk.imports.get(i)[0])) {
                                    String tex = UnrealAssets.importPath(pk, -i - 1);
                                    if (tex != null) cands.add(new String[]{null, tex});
                                }
                            m.texture = pickDiffuse(cands);
                        }
                    }
                } catch (IOException ignored) { }
                break;
            }
            if (m.texture != null && blendKnown) break;
            path = next;
        }
        m.cutout = blend != null && !blend.equals("BLEND_Opaque");
        return m;
    }

    // ------------------------------------------------------------------ viewer data
    static String key(int pak, String path, int export) { return pak + "|" + path + "|" + export; }

    /** Finds a texture by object path among the scanned packages. Returns {PackageInfo, Texture} or null. */
    static Object[] findTexture(UnrealObb.Info info, String objectPath, String project) {
        String file = packagePath(objectPath, project);
        if (file == null) return null;
        String name = objectName(objectPath);
        for (UnrealObb.PackageInfo p : info.packages) {
            if (!p.path.equalsIgnoreCase(file)) continue;
            for (UnrealAssets.Texture t : p.textures) if (t.name.equals(name)) return new Object[]{p, t};
            if (p.textures.size() == 1) return new Object[]{p, p.textures.get(0)};
        }
        return null;
    }
}
