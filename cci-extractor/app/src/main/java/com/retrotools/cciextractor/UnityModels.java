package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unity meshes and what they're drawn with: Mesh decoding (vertex streams, every vertex format, submeshes),
 * and Renderer → Material → main texture resolution, following references into other bundles of the same OBB.
 */
final class UnityModels {
    static final int CLASS_GAMEOBJECT = 1, CLASS_MATERIAL = 21, CLASS_MESHRENDERER = 23, CLASS_MESHFILTER = 33,
            CLASS_MESH = 43, CLASS_SKINNEDMESHRENDERER = 137;

    /** Where an object lives: bundle (zip entry), file inside the bundle, path id. */
    static final class Ref {
        final String bundle, file; final long pathId;
        Ref(String bundle, String file, long pathId) { this.bundle = bundle; this.file = file; this.pathId = pathId; }
        @Override public boolean equals(Object o) {
            return o instanceof Ref && ((Ref) o).pathId == pathId && ((Ref) o).file.equals(file) && ((Ref) o).bundle.equals(bundle);
        }
        @Override public int hashCode() { return Long.hashCode(pathId) * 31 + file.hashCode(); }
    }

    static final class Model {
        Ref mesh; String name; int vertexCount, parts;
        /** One per renderer material slot (may hold nulls for references that can't be followed). */
        final List<Ref> materials = new ArrayList<>();
        String label() { return name + " · " + vertexCount + " vertices" + (parts > 1 ? " · " + parts + " parts" : ""); }
    }

    static final class Mesh {
        String name; int vertexCount;
        float[] pos, normal, uv;   // 3n, 3n (or null), 2n (or null)
        int[][] parts;             // triangle list indices per submesh
        float[] min = new float[3], max = new float[3];
    }

    static final class Material {
        String name; Ref texture; String textureSlot;
    }

    // ------------------------------------------------------------------ type trees for stripped files
    /** "major.minor#classId" → type tree, from any file in the OBB that has one. */
    static Map<String, UnityBundle.TypeNode> classTrees(FileChannel ch, UnityObb.Info info) {
        if (info.classTrees != null) return info.classTrees;
        Map<String, UnityBundle.TypeNode> out = new HashMap<>();
        int[] wanted = {CLASS_GAMEOBJECT, CLASS_MATERIAL, CLASS_MESHRENDERER, CLASS_MESHFILTER, CLASS_MESH, CLASS_SKINNEDMESHRENDERER};
        for (UnityObb.BundleInfo b : info.bundles) {
            if (b.error != null) continue;
            try {
                UnityBundle ub = bundle(ch, b);
                for (UnityBundle.Node n : ub.nodes) {
                    if (n.path.endsWith(".resS") || n.path.endsWith(".resource")) continue;
                    UnityBundle.SerializedFile sf = ub.serializedFile(n);
                    if (sf == null || !sf.typeTrees) continue;
                    String mm = UnityBundle.majorMinor(sf.unityVersion);
                    for (UnityBundle.ObjectInfo o : sf.objects)
                        for (int w : wanted) if (o.classId == w && o.tree != null) out.putIfAbsent(mm + "#" + w, o.tree);
                }
            } catch (IOException | RuntimeException ignored) { }
            if (out.size() >= wanted.length) break;
        }
        info.classTrees = out;
        return out;
    }

    static UnityBundle bundle(FileChannel ch, UnityObb.BundleInfo b) throws IOException {
        return UnityBundle.open(UnityBundle.of(ch, b.offset, b.size));
    }

    // ------------------------------------------------------------------ listing
    private static long num(Object o) { return o instanceof Long ? (Long) o : o instanceof Double ? (long) (double) (Double) o : -1; }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }

    /** PPtr → Ref, resolving fileIDs through the file's externals to the bundle that holds that file. */
    static Ref resolve(UnityObb.Info info, String bundle, UnityBundle.SerializedFile sf, Object pptr) {
        Map<String, Object> p = map(pptr);
        if (p == null) return null;
        long fileId = num(p.get("m_FileID")), pathId = num(p.get("m_PathID"));
        if (pathId == 0) return null;
        if (fileId == 0) return new Ref(bundle, sf.node.path, pathId);
        if (fileId < 0 || fileId > sf.externals.size()) return null;
        String ext = sf.externals.get((int) fileId - 1);
        String leaf = ext.substring(ext.lastIndexOf('/') + 1);
        for (UnityObb.BundleInfo b : info.bundles) if (b.files.contains(leaf)) return new Ref(b.entry, leaf, pathId);
        return null; // e.g. Unity's built-in resources
    }

    /** Every mesh in a bundle, with the materials its renderer draws it with. */
    static List<Model> models(FileChannel ch, UnityObb.Info info, UnityObb.BundleInfo b) throws IOException {
        Map<String, UnityBundle.TypeNode> trees = classTrees(ch, info);
        UnityBundle ub = bundle(ch, b);
        Map<Ref, Model> byMesh = new LinkedHashMap<>();
        List<Object[]> renderers = new ArrayList<>();          // {Ref mesh, List<Ref> materials}
        Map<Ref, List<Ref>> rendererByGo = new HashMap<>();    // MeshRenderer materials by GameObject
        Map<Ref, Ref> filterByGo = new HashMap<>();            // MeshFilter mesh by GameObject
        for (UnityBundle.Node n : ub.nodes) {
            if (n.path.endsWith(".resS") || n.path.endsWith(".resource")) continue;
            UnityBundle.SerializedFile sf = ub.serializedFile(n);
            if (sf == null) continue;
            for (UnityBundle.ObjectInfo o : sf.objects) {
                int c = o.classId;
                if (c != CLASS_MESH && c != CLASS_SKINNEDMESHRENDERER && c != CLASS_MESHRENDERER && c != CLASS_MESHFILTER) continue;
                Map<String, Object> v;
                try { v = ub.readObject(sf, o, trees, null); } catch (IOException e) { continue; }
                if (c == CLASS_MESH) {
                    Model m = new Model();
                    m.mesh = new Ref(b.entry, n.path, o.pathId);
                    Object name = v.get("m_Name"); m.name = name instanceof String && !((String) name).isEmpty() ? (String) name : "mesh " + o.pathId;
                    Map<String, Object> vd = map(v.get("m_VertexData"));
                    m.vertexCount = vd == null ? 0 : (int) num(vd.get("m_VertexCount"));
                    m.parts = list(v.get("m_SubMeshes")).size();
                    byMesh.put(m.mesh, m);
                } else {
                    List<Ref> mats = new ArrayList<>();
                    for (Object p : list(v.get("m_Materials"))) mats.add(resolve(info, b.entry, sf, p));
                    Ref go = resolve(info, b.entry, sf, v.get("m_GameObject"));
                    if (c == CLASS_SKINNEDMESHRENDERER) renderers.add(new Object[]{resolve(info, b.entry, sf, v.get("m_Mesh")), mats});
                    else if (c == CLASS_MESHRENDERER) { if (go != null) rendererByGo.put(go, mats); }
                    else { Ref mesh = resolve(info, b.entry, sf, v.get("m_Mesh")); if (go != null && mesh != null) filterByGo.put(go, mesh); }
                }
            }
        }
        for (Map.Entry<Ref, Ref> f : filterByGo.entrySet()) {
            List<Ref> mats = rendererByGo.get(f.getKey());
            if (mats != null) renderers.add(new Object[]{f.getValue(), mats});
        }
        for (Object[] r : renderers) {
            Model m = byMesh.get((Ref) r[0]);
            @SuppressWarnings("unchecked") List<Ref> mats = (List<Ref>) r[1];
            if (m != null && m.materials.isEmpty()) m.materials.addAll(mats);
        }
        List<Model> out = new ArrayList<>(byMesh.values());
        out.sort((x, y) -> Integer.compare(y.vertexCount, x.vertexCount));
        return out;
    }

    // ------------------------------------------------------------------ materials
    private static final String[] MAIN_SLOTS = {"_MainTex", "_BaseMap", "_BaseColorMap", "_Albedo", "_AlbedoMap", "_Diffuse",
            "_DiffuseMap", "_BaseTex", "_ColorMap", "_Tex"};
    private static final String[] NOT_COLOUR = {"bump", "normal", "mask", "spec", "occlusion", "metal", "rough", "emiss",
            "detail", "light", "height", "parallax", "gloss", "noise", "ramp", "cube", "reflect", "_n", "tears"};

    static UnityBundle.SerializedFile file(UnityBundle ub, String path) throws IOException {
        UnityBundle.Node n = ub.node(path);
        return n == null ? null : ub.serializedFile(n);
    }

    static Material material(FileChannel ch, UnityObb.Info info, Ref ref) throws IOException {
        UnityObb.BundleInfo b = info.bundle(ref.bundle);
        if (b == null) return null;
        UnityBundle ub = bundle(ch, b);
        UnityBundle.SerializedFile sf = file(ub, ref.file);
        if (sf == null) return null;
        UnityBundle.ObjectInfo o = sf.object(ref.pathId);
        if (o == null || o.classId != CLASS_MATERIAL) return null;
        Map<String, Object> v = ub.readObject(sf, o, classTrees(ch, info), null);
        Material m = new Material();
        m.name = String.valueOf(v.get("m_Name"));
        Map<String, Object> props = map(v.get("m_SavedProperties"));
        Map<String, Object> slots = new LinkedHashMap<>();
        if (props != null) for (Object e : list(props.get("m_TexEnvs"))) {
            Map<String, Object> pair = map(e);
            if (pair == null) continue;
            Object key = pair.get("first");
            if (map(key) != null) key = map(key).get("name"); // older Unity: FastPropertyName
            Map<String, Object> env = map(pair.get("second"));
            if (key instanceof String && env != null) slots.put((String) key, env.get("m_Texture"));
        }
        String slot = null;
        for (String s : MAIN_SLOTS) if (slots.containsKey(s) && pathId(slots.get(s)) != 0) { slot = s; break; }
        if (slot == null) for (String s : slots.keySet()) {
            if (pathId(slots.get(s)) == 0) continue;
            String l = s.toLowerCase(Locale.ROOT);
            boolean other = false;
            for (String x : NOT_COLOUR) if (l.contains(x)) { other = true; break; }
            if (!other) { slot = s; break; }
        }
        if (slot != null) { m.textureSlot = slot; m.texture = resolve(info, ref.bundle, sf, slots.get(slot)); }
        return m;
    }

    private static long pathId(Object pptr) { Map<String, Object> p = map(pptr); return p == null ? 0 : num(p.get("m_PathID")); }

    // ------------------------------------------------------------------ mesh decoding
    private static final int[] FORMAT_SIZE = {4, 2, 1, 1, 2, 2, 1, 1, 2, 2, 4, 4}; // Unity 2019+ VertexFormat

    static Mesh mesh(FileChannel ch, UnityObb.Info info, Ref ref) throws IOException {
        UnityObb.BundleInfo b = info.bundle(ref.bundle);
        if (b == null) throw new IOException("Bundle not found: " + ref.bundle);
        UnityBundle ub = bundle(ch, b);
        UnityBundle.SerializedFile sf = file(ub, ref.file);
        UnityBundle.ObjectInfo o = sf == null ? null : sf.object(ref.pathId);
        if (o == null || o.classId != CLASS_MESH) throw new IOException("Mesh not found");
        byte[][] raw = new byte[1][];
        Map<String, Object> v = ub.readObject(sf, o, classTrees(ch, info), raw);
        return decodeMesh(v, raw[0], sf, ub);
    }

    static Mesh decodeMesh(Map<String, Object> v, byte[] obj, UnityBundle.SerializedFile sf, UnityBundle ub) throws IOException {
        Mesh m = new Mesh();
        m.name = String.valueOf(v.get("m_Name"));
        Map<String, Object> vd = map(v.get("m_VertexData"));
        if (vd == null) throw new IOException("Mesh has no vertex data (Unity versions before 5 aren't supported)");
        int vc = (int) num(vd.get("m_VertexCount"));
        if (vc <= 0 && num(v.get("m_MeshCompression")) > 0)
            throw new IOException("This mesh is compressed (Mesh Compression in Unity), which isn't supported yet");
        if (vc < 0 || vc > 20_000_000) throw new IOException("Bad vertex count");
        m.vertexCount = vc;
        int major = 0;
        try { major = Integer.parseInt(sf.unityVersion.substring(0, sf.unityVersion.indexOf('.'))); } catch (RuntimeException ignored) { }

        // vertex bytes: inline, or streamed from the bundle's .resS
        byte[] data;
        Object ds = vd.get("m_DataSize");
        UnityBundle.Blob blob = ds instanceof UnityBundle.Blob ? (UnityBundle.Blob) ds : null;
        if (blob != null && blob.count > 0) data = java.util.Arrays.copyOfRange(obj, blob.offset, blob.offset + blob.count);
        else {
            Map<String, Object> st = map(v.get("m_StreamData"));
            String path = st == null ? null : (String) st.get("path");
            long off = st == null ? 0 : num(st.get("offset")), size = st == null ? 0 : num(st.get("size"));
            if (path == null || path.isEmpty() || size <= 0) data = new byte[0];
            else {
                UnityBundle.Node res = ub.node(path.substring(path.lastIndexOf('/') + 1));
                if (res == null || off < 0 || off + size > res.size) throw new IOException("Mesh data is in another file (" + path + ")");
                data = ub.read(res.offset + off, (int) size);
            }
        }

        // channels → streams (each stream holds its channels interleaved; streams are 16-byte aligned)
        List<Object> chans = list(vd.get("m_Channels"));
        int nch = chans.size();
        int[][] ch = new int[nch][4]; // stream, offset, format, dimension
        int streams = 0;
        for (int i = 0; i < nch; i++) {
            Map<String, Object> c = map(chans.get(i));
            ch[i][0] = (int) num(c.get("stream")); ch[i][1] = (int) num(c.get("offset"));
            int f = (int) num(c.get("format"));
            if (major < 2019) f = f == 0 ? 0 : f == 1 ? 1 : f == 2 ? 2 : f == 3 ? 6 : f == 4 ? 10 : f; // old VertexChannelFormat
            ch[i][2] = f; ch[i][3] = (int) num(c.get("dimension")) & 0xF;
            if (ch[i][3] > 0) {
                if (f < 0 || f >= FORMAT_SIZE.length) throw new IOException("Unknown vertex format " + f);
                streams = Math.max(streams, ch[i][0] + 1);
            }
        }
        long[] streamOffset = new long[streams]; int[] stride = new int[streams];
        long offset = 0;
        for (int s = 0; s < streams; s++) {
            for (int[] c : ch) if (c[0] == s && c[3] > 0) stride[s] += c[3] * FORMAT_SIZE[c[2]];
            streamOffset[s] = offset;
            offset += (long) vc * stride[s];
            if (offset % 16 != 0) offset += 16 - offset % 16;
        }
        boolean modern = nch >= 14;
        int posCh = 0, normCh = 1, uvCh = modern ? 4 : 3;
        m.pos = channel(data, ch, posCh, vc, streamOffset, stride, 3);
        if (m.pos == null) throw new IOException("Mesh has no vertex positions");
        m.normal = nch > normCh ? channel(data, ch, normCh, vc, streamOffset, stride, 3) : null;
        m.uv = nch > uvCh ? channel(data, ch, uvCh, vc, streamOffset, stride, 2) : null;
        for (int k = 0; k < 3; k++) { m.min[k] = Float.MAX_VALUE; m.max[k] = -Float.MAX_VALUE; }
        for (int i = 0; i < vc; i++) for (int k = 0; k < 3; k++) {
            float x = m.pos[i * 3 + k];
            if (x < m.min[k]) m.min[k] = x;
            if (x > m.max[k]) m.max[k] = x;
        }

        // indices per submesh
        Object ib = v.get("m_IndexBuffer");
        UnityBundle.Blob ibb = ib instanceof UnityBundle.Blob ? (UnityBundle.Blob) ib : null;
        int idxSize = num(v.get("m_IndexFormat")) == 1 ? 4 : 2;
        List<Object> subs = list(v.get("m_SubMeshes"));
        m.parts = new int[subs.size()][];
        for (int s = 0; s < subs.size(); s++) {
            Map<String, Object> sm = map(subs.get(s));
            long first = num(sm.get("firstByte")), count = num(sm.get("indexCount")), topo = num(sm.get("topology"));
            long base = sm.containsKey("baseVertex") ? num(sm.get("baseVertex")) : 0;
            if (topo != 0 || ibb == null) { m.parts[s] = new int[0]; continue; } // only triangle lists are drawn
            if (first < 0 || count < 0 || first + count * idxSize > ibb.bytes()) throw new IOException("Submesh indices out of range");
            int[] idx = new int[(int) (count - count % 3)];
            int at = ibb.offset + (int) first;
            for (int i = 0; i < idx.length; i++) {
                long x = idxSize == 2 ? ((obj[at] & 255) | ((obj[at + 1] & 255) << 8))
                        : ((obj[at] & 255) | ((obj[at + 1] & 255) << 8) | ((obj[at + 2] & 255) << 16) | ((long) (obj[at + 3] & 255) << 24));
                if (!sf.little) x = idxSize == 2 ? Integer.reverseBytes((int) x) >>> 16 : Integer.reverseBytes((int) x) & 0xffffffffL;
                x += base;
                if (x < 0 || x >= vc) throw new IOException("Vertex index out of range");
                idx[i] = (int) x;
                at += idxSize;
            }
            m.parts[s] = idx;
        }
        return m;
    }

    private static float[] channel(byte[] d, int[][] ch, int c, int vc, long[] so, int[] stride, int want) throws IOException {
        if (c >= ch.length || ch[c][3] == 0) return null;
        int s = ch[c][0], f = ch[c][2], dim = ch[c][3], fs = FORMAT_SIZE[f];
        float[] out = new float[vc * want];
        for (int i = 0; i < vc; i++) {
            long at = so[s] + (long) i * stride[s] + ch[c][1];
            if (at + (long) dim * fs > d.length) throw new IOException("Vertex data is truncated");
            for (int k = 0; k < Math.min(dim, want); k++) out[i * want + k] = component(d, (int) at + k * fs, f);
        }
        return out;
    }

    static float component(byte[] d, int at, int f) {
        switch (f) {
            case 0: return Float.intBitsToFloat((d[at] & 255) | ((d[at + 1] & 255) << 8) | ((d[at + 2] & 255) << 16) | ((d[at + 3] & 255) << 24));
            case 1: return half((d[at] & 255) | ((d[at + 1] & 255) << 8));
            case 2: return (d[at] & 255) / 255f;
            case 3: return Math.max(d[at] / 127f, -1f);
            case 4: return ((d[at] & 255) | ((d[at + 1] & 255) << 8)) / 65535f;
            case 5: return Math.max((short) ((d[at] & 255) | ((d[at + 1] & 255) << 8)) / 32767f, -1f);
            case 6: return d[at] & 255;
            case 7: return d[at];
            case 8: return (d[at] & 255) | ((d[at + 1] & 255) << 8);
            case 9: return (short) ((d[at] & 255) | ((d[at + 1] & 255) << 8));
            case 10: return ((d[at] & 255) | ((d[at + 1] & 255) << 8) | ((d[at + 2] & 255) << 16) | ((long) (d[at + 3] & 255) << 24));
            case 11: return (d[at] & 255) | ((d[at + 1] & 255) << 8) | ((d[at + 2] & 255) << 16) | ((d[at + 3]) << 24);
            default: return 0;
        }
    }

    static float half(int h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
        float v;
        if (e == 0) v = (float) (m * Math.pow(2, -24));
        else if (e == 31) v = m == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = (float) ((1 + m / 1024.0) * Math.pow(2, e - 15));
        return s == 1 ? -v : v;
    }

    /** Wavefront OBJ of a mesh (one group per part), handy for checking and for other tools. */
    /**
     * Welds vertices that sit at exactly the same position (the copies a model has along UV and normal seams).
     * Returns the welded id per vertex; the count is {@code max + 1}.
     */
    static int[] weld(Mesh m) {
        int[] out = new int[m.vertexCount];
        java.util.HashMap<String, Integer> ids = new java.util.HashMap<>();
        for (int i = 0; i < m.vertexCount; i++) {
            String k = Float.floatToIntBits(m.pos[i * 3]) + "," + Float.floatToIntBits(m.pos[i * 3 + 1]) + "," + Float.floatToIntBits(m.pos[i * 3 + 2]);
            Integer id = ids.get(k);
            if (id == null) { id = ids.size(); ids.put(k, id); }
            out[i] = id;
        }
        return out;
    }

    /**
     * OBJ for editing in a 3D program: one point per welded position (so sculpting doesn't tear seams open) and one
     * UV per original vertex, so "Import edited OBJ" can map every corner back. {@code weld} comes from the
     * unedited model so ids stay stable across edits; positions come from {@code m}.
     */
    static String toObj(Mesh m, int[] weld) {
        int count = 0;
        for (int w : weld) count = Math.max(count, w + 1);
        float[] wp = new float[count * 3];
        boolean[] set = new boolean[count];
        for (int i = 0; i < m.vertexCount; i++) if (!set[weld[i]]) {
            set[weld[i]] = true;
            System.arraycopy(m.pos, i * 3, wp, weld[i] * 3, 3);
        }
        StringBuilder s = new StringBuilder("# " + m.name + "\n# Game Asset Extractor editable OBJ: keep the point count; move, sculpt or delete faces only\n");
        s.append("o ").append(m.name.replaceAll("\\s+", "_")).append('\n');
        for (int i = 0; i < count; i++)
            s.append(String.format(Locale.ROOT, "v %.6f %.6f %.6f\n", -wp[i * 3], wp[i * 3 + 1], wp[i * 3 + 2]));
        boolean uv = m.uv != null;
        if (uv) for (int i = 0; i < m.vertexCount; i++)
            s.append(String.format(Locale.ROOT, "vt %.6f %.6f\n", m.uv[i * 2], m.uv[i * 2 + 1]));
        s.append("s 1\n");
        for (int p = 0; p < m.parts.length; p++) {
            s.append("g part").append(p + 1).append('\n').append("usemtl part").append(p + 1).append('\n');
            int[] t = m.parts[p];
            for (int i = 0; i + 2 < t.length; i += 3) {
                int[] c = {t[i], t[i + 2], t[i + 1]};    // x is mirrored, so flip the winding too
                s.append('f');
                for (int v : c) {
                    s.append(' ').append(weld[v] + 1);
                    if (uv) s.append('/').append(v + 1);
                }
                s.append('\n');
            }
        }
        return s.toString();
    }

    static String toObj(Mesh m) {
        StringBuilder s = new StringBuilder("# " + m.name + "\n");
        for (int i = 0; i < m.vertexCount; i++)
            s.append(String.format(Locale.ROOT, "v %.6f %.6f %.6f\n", -m.pos[i * 3], m.pos[i * 3 + 1], m.pos[i * 3 + 2]));
        if (m.uv != null) for (int i = 0; i < m.vertexCount; i++)
            s.append(String.format(Locale.ROOT, "vt %.6f %.6f\n", m.uv[i * 2], m.uv[i * 2 + 1]));
        for (int p = 0; p < m.parts.length; p++) {
            s.append("g part").append(p + 1).append('\n');
            int[] t = m.parts[p];
            for (int i = 0; i + 2 < t.length; i += 3) {
                // x is mirrored (Unity is left-handed), so flip the winding too
                int a = t[i] + 1, b = t[i + 2] + 1, c = t[i + 1] + 1;
                if (m.uv != null) s.append("f ").append(a).append('/').append(a).append(' ').append(b).append('/').append(b).append(' ').append(c).append('/').append(c).append('\n');
                else s.append("f ").append(a).append(' ').append(b).append(' ').append(c).append('\n');
            }
        }
        return s.toString();
    }
}
