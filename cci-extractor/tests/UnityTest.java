package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unity OBB / bundle texture support:
 *  - ETC1 / ETC2 RGB (all modes) / ETC2 RGBA8+EAC decoding against texture2ddecoder on random blocks,
 *  - encoder round trips for every writable format, with odd sizes and full mip chains,
 *  - a synthetic OBB (fixtures/unity, built by gen_unity_fixture.py and cross-checked with UnityPy):
 *    scan, decode, patch three textures (one in a type-tree-stripped data.unity3d), rebuild, re-read,
 *  - a 2-part mesh (two vertex streams, half-float UVs, baseVertex) with its renderer's materials, one pointing
 *    at a texture in another bundle, against UnityPy's decoding,
 *  - projection painting: placement, opacity, texture alpha kept, occlusion, back faces, seam padding, mirrored UVs.
 */
public class UnityTest {
    static int checks = 0;
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "tests/fixtures/unity");
        etcVectors(new File(dir, "etc_vectors.bin"));
        roundTrips();
        eacConstant();
        obb(dir);
        models(dir);
        paint();
        System.out.println("UnityTest OK (" + checks + " checks)");
    }

    // ------------------------------------------------------------------ codecs vs texture2ddecoder
    static void etcVectors(File f) throws Exception {
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            byte[] magic = new byte[4]; in.readFully(magic);
            check(new String(magic, "ISO-8859-1").equals("ETCV"), "vector magic");
            int[] px = new int[16], alpha = new int[16];
            for (int section = 0; section < 3; section++) {
                int kind = in.readUnsignedByte();
                int n = Integer.reverseBytes(in.readInt());
                int size = kind == 3 ? 16 : 8;
                int bad = 0;
                for (int i = 0; i < n; i++) {
                    byte[] blk = new byte[size]; in.readFully(blk);
                    byte[] exp = new byte[64]; in.readFully(exp);
                    if (kind == 1) {
                        long b = 0; for (int k = 0; k < 8; k++) b = (b << 8) | (blk[k] & 255);
                        TextureFormats.decodeEtc1Block(b, px);
                        for (int k = 0; k < 16; k++) alpha[k] = 255;
                    } else if (kind == 2) {
                        UnityTextures.decodeEtc2Rgb(blk, 0, px);
                        for (int k = 0; k < 16; k++) alpha[k] = 255;
                    } else {
                        UnityTextures.decodeEacAlpha(blk, 0, alpha);
                        UnityTextures.decodeEtc2Rgb(blk, 8, px);
                    }
                    for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                        int c = px[y * 4 + x], a = alpha[x * 4 + y], o = (y * 4 + x) * 4;
                        if (((c >> 16) & 255) != (exp[o] & 255) || ((c >> 8) & 255) != (exp[o + 1] & 255) ||
                                (c & 255) != (exp[o + 2] & 255) || a != (exp[o + 3] & 255)) { bad++; break; }
                    }
                }
                check(bad == 0, "ETC vector kind " + kind + ": " + bad + " of " + n + " blocks differ");
            }
        }
    }

    static double psnr(int[] a, int[] b, boolean alpha) {
        double se = 0; int n = 0;
        for (int i = 0; i < a.length; i++) for (int s = alpha ? 0 : 8; s < 32; s += 8) {
            double d = ((a[i] >>> s) & 255) - ((b[i] >>> s) & 255); se += d * d; n++;
        }
        return se == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / (se / n));
    }

    static int[] gradient(int w, int h, boolean alpha) {
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int r = x * 255 / Math.max(1, w - 1), g = y * 255 / Math.max(1, h - 1), b = (x + y) * 255 / Math.max(1, w + h - 2);
            int a = alpha ? (x + y) * 255 / Math.max(1, w + h - 2) : 255;
            p[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return p;
    }

    static void roundTrips() throws Exception {
        int[][] cases = { // format, min PSNR, uses alpha
                {UnityTextures.RGBA32, 99, 1}, {UnityTextures.ARGB32, 99, 1}, {UnityTextures.BGRA32, 99, 1},
                {UnityTextures.RGB24, 99, 0}, {UnityTextures.RGB565, 40, 0}, {UnityTextures.ARGB4444, 30, 1},
                {UnityTextures.RGBA4444, 30, 1}, {UnityTextures.ETC_RGB4, 30, 0}, {UnityTextures.ETC2_RGB, 30, 0},
                {UnityTextures.ETC2_RGBA8, 30, 1}};
        for (int[] c : cases) {
            int w = 37, h = 21, mips = 6;
            int[] img = gradient(w, h, c[2] == 1);
            byte[] enc = UnityTextures.encode(img, w, h, c[0], mips);
            check(enc.length == UnityTextures.totalSize(c[0], w, h, mips), UnityTextures.name(c[0]) + " size");
            int[] back = UnityTextures.decode(enc, c[0], w, h);
            double p = psnr(img, back, c[2] == 1);
            check(p >= c[1], UnityTextures.name(c[0]) + " round trip PSNR " + p);
            // mip 1 sits right after mip 0 and decodes to a half-size version of the image
            long l0 = UnityTextures.levelSize(c[0], w, h);
            byte[] m1 = java.util.Arrays.copyOfRange(enc, (int) l0, enc.length);
            int[] half = UnityTextures.decode(m1, c[0], w / 2, h / 2);
            double pm = psnr(UnityTextures.downsample(img, w, h, w / 2, h / 2), half, c[2] == 1);
            check(pm >= Math.min(c[1], 25), UnityTextures.name(c[0]) + " mip 1 PSNR " + pm);
        }
        // single-channel: brightness in, value out
        int[] grey = new int[16 * 8];
        for (int i = 0; i < grey.length; i++) { int v = (i * 2) & 255; grey[i] = 0xFF000000 | v * 0x010101; }
        for (int f : new int[]{UnityTextures.ALPHA8, UnityTextures.R8}) {
            byte[] enc = UnityTextures.encode(grey, 16, 8, f, 1);
            check(java.util.Arrays.equals(UnityTextures.decode(enc, f, 16, 8), grey), UnityTextures.name(f) + " exact");
        }
    }

    static void eacConstant() {
        for (int v : new int[]{0, 1, 77, 128, 254, 255}) {
            int[] a = new int[16]; java.util.Arrays.fill(a, v);
            byte[] out = new byte[8];
            UnityTextures.encodeEacAlpha(a, out, 0);
            int[] back = new int[16];
            UnityTextures.decodeEacAlpha(out, 0, back);
            check(java.util.Arrays.equals(a, back), "EAC constant " + v);
        }
    }

    // ------------------------------------------------------------------ synthetic OBB
    static String pixelSha(UnityBundle.Texture t, int[] px) {
        boolean single = UnityTextures.singleChannel(t.format);
        byte[] buf = new byte[px.length * (single ? 1 : 4)]; int p = 0;
        for (int c : px) {
            if (single) buf[p++] = (byte) c;
            else { buf[p++] = (byte) (c >> 16); buf[p++] = (byte) (c >> 8); buf[p++] = (byte) c; buf[p++] = (byte) (c >>> 24); }
        }
        return UnityObb.sha1(buf);
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    static void obb(File dir) throws Exception {
        File zip = new File(dir, "unity_fixture.zip");
        List<String[]> expected = new ArrayList<>();
        for (String line : new String(Files.readAllBytes(new File(dir, "expected.tsv").toPath()), "UTF-8").split("\n"))
            if (!line.trim().isEmpty()) expected.add(line.split("\t"));

        Map<String, int[]> edits = new HashMap<>();
        Map<String, String> unchangedSha = new HashMap<>();
        File out = File.createTempFile("unity_rebuilt", ".obb");
        out.deleteOnExit();
        UnityObb.Info info;
        try (FileInputStream in = new FileInputStream(zip); FileChannel ch = in.getChannel()) {
            check(UnityObb.isUnity(ch), "fixture detected as Unity");
            info = UnityObb.scan(ch, zip.getName(), null);
            check(!info.single && !info.apk, "OBB, not APK");
            check(info.bundles.size() == 2 && info.broken == 0, "two readable bundles: " + info.description());
            check(info.textures == expected.size(), "texture count " + info.textures);
            Map<String, TreeMap<Long, byte[]>> patches = new HashMap<>();
            for (String[] e : expected) {
                UnityObb.Row r = new UnityObb.Row();
                r.bundle = e[0]; r.pathId = Long.parseLong(e[1]); r.name = e[2];
                UnityObb.BundleInfo[] owner = new UnityObb.BundleInfo[1];
                UnityBundle.Texture t = null;
                for (UnityObb.BundleInfo b : info.bundles) if (b.entry.equals(r.bundle))
                    for (UnityBundle.Texture x : b.textures) if (x.pathId == r.pathId) { t = x; owner[0] = b; }
                check(t != null, "found " + r.name);
                check(t.name.equals(e[2]) && t.format == Integer.parseInt(e[3]) && t.width == Integer.parseInt(e[4]) &&
                        t.height == Integer.parseInt(e[5]) && t.mips == Integer.parseInt(e[6]) && t.streamed == e[7].equals("1"),
                        "metadata of " + r.name);
                int[] px = UnityTextures.decode(UnityObb.readTexture(ch, owner[0], t), t.format, t.width, t.height);
                check(pixelSha(t, px).equals(e[8]), "pixels of " + r.name + " match texture2ddecoder/UnityPy");
                // the index row round-trips
                List<UnityObb.Row> rows = UnityObb.parseIndex(UnityObb.INDEX_HEADER + UnityObb.row(owner[0].entry, t, UnityObb.pngPath(owner[0].entry, t), "x"));
                check(rows.size() == 1 && UnityObb.find(info, rows.get(0), null) == t, "index row for " + r.name);
                if (t.name.equals("Mask")) { unchangedSha.put(t.name, e[8]); continue; }
                // edit: invert the colours, keep alpha
                int[] edited = new int[px.length];
                for (int i = 0; i < px.length; i++) edited[i] = (px[i] & 0xFF000000) | (~px[i] & 0xFFFFFF);
                edits.put(t.name, edited);
                patches.computeIfAbsent(owner[0].entry, k -> new TreeMap<>()).put(t.dataPos, UnityObb.encodeFor(t, edited, t.width, t.height));
            }
            check(UnityObb.pngPath("assets/Bundles/a.bytes", info.bundles.get(1).textures.get(0)).startsWith("assets/Bundles/a/"), "png path");
            try (FileOutputStream fo = new FileOutputStream(out)) {
                UnityObb.rebuild(ch, info, patches, out.getParentFile(), fo, null);
            }
        }

        // re-open the result and check every texture
        try (FileInputStream in = new FileInputStream(out); FileChannel ch = in.getChannel()) {
            UnityObb.Info again = UnityObb.scan(ch, out.getName(), null);
            check(again.textures == expected.size() && again.broken == 0, "rebuilt file scans: " + again.description());
            for (UnityObb.BundleInfo b : again.bundles) for (UnityBundle.Texture t : b.textures) {
                int[] px = UnityTextures.decode(UnityObb.readTexture(ch, b, t), t.format, t.width, t.height);
                if (unchangedSha.containsKey(t.name)) { check(pixelSha(t, px).equals(unchangedSha.get(t.name)), "untouched " + t.name); continue; }
                int[] want = edits.get(t.name);
                double p = psnr(want, px, true);
                boolean lossless = t.format == UnityTextures.RGBA32 || t.format == UnityTextures.RGB24;
                check(lossless ? p == 99 : p >= 28, t.name + " edit survived (PSNR " + p + ")");
            }
        }

        // zip structure: same entries in the same order, all readable with java.util.zip, CRCs right,
        // untouched entries byte-identical (the deflated one stays deflated)
        try (ZipFile a = new ZipFile(zip); ZipFile b = new ZipFile(out)) {
            List<? extends ZipEntry> ea = java.util.Collections.list(a.entries()), eb = java.util.Collections.list(b.entries());
            check(ea.size() == eb.size(), "entry count");
            for (int i = 0; i < ea.size(); i++) {
                ZipEntry x = ea.get(i), y = eb.get(i);
                check(x.getName().equals(y.getName()), "entry order " + x.getName());
                byte[] dy = readAll(b.getInputStream(y));
                CRC32 crc = new CRC32(); crc.update(dy);
                check(crc.getValue() == y.getCrc(), "CRC of " + y.getName());
                boolean bundle = y.getName().endsWith(".bytes") || y.getName().endsWith(".unity3d");
                if (!bundle) {
                    check(java.util.Arrays.equals(readAll(a.getInputStream(x)), dy), "unchanged " + y.getName());
                    check(x.getMethod() == y.getMethod(), "method kept for " + y.getName());
                } else check(y.getMethod() == ZipEntry.STORED, "bundles stored");
            }
        }

        // a loose bundle file works too
        File loose = File.createTempFile("loose", ".bundle"); loose.deleteOnExit();
        try (ZipFile a = new ZipFile(zip); FileOutputStream fo = new FileOutputStream(loose)) {
            fo.write(readAll(a.getInputStream(a.getEntry("assets/Bundles/a.bytes"))));
        }
        try (FileInputStream in = new FileInputStream(loose); FileChannel ch = in.getChannel()) {
            check(UnityObb.isUnity(ch), "loose bundle detected");
            UnityObb.Info single = UnityObb.scan(ch, "a.bytes", null);
            check(single.single && single.textures == 3, "loose bundle scan");
            UnityBundle.Texture t = single.bundles.get(0).textures.get(0);
            int[] px = UnityTextures.decode(UnityObb.readTexture(ch, single.bundles.get(0), t), t.format, t.width, t.height);
            for (int i = 0; i < px.length; i++) px[i] ^= 0x00FF0000;
            TreeMap<Long, byte[]> p = new TreeMap<>(); p.put(t.dataPos, UnityObb.encodeFor(t, px, t.width, t.height));
            Map<String, TreeMap<Long, byte[]>> patches = new HashMap<>(); patches.put(single.bundles.get(0).entry, p);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            UnityObb.rebuild(ch, single, patches, loose.getParentFile(), bo, null);
            UnityBundle nb = UnityBundle.open(UnityBundle.of(bo.toByteArray()));
            UnityBundle.Texture nt = nb.textures().get(0);
            check(java.util.Arrays.equals(UnityTextures.decode(nb.read(nt.dataPos, nt.dataSize), nt.format, nt.width, nt.height), px), "loose bundle patched");
        }

        // corrupt input fails cleanly
        byte[] bad = Files.readAllBytes(zip.toPath());
        for (int i = 200; i < 260; i++) bad[i] ^= 0x5A;
        File broken = File.createTempFile("broken", ".obb"); broken.deleteOnExit();
        Files.write(broken.toPath(), bad);
        try (FileInputStream in = new FileInputStream(broken); FileChannel ch = in.getChannel()) {
            try { UnityObb.Info i = UnityObb.scan(ch, "broken", null); check(i.broken > 0 || i.textures <= expected.size(), "corrupt scan reported"); }
            catch (java.io.IOException expectedFailure) { checks++; }
        }
        byte[] junk = new byte[4096]; new java.util.Random(7).nextBytes(junk);
        System.arraycopy("UnityFS\0".getBytes("ISO-8859-1"), 0, junk, 0, 8);
        try { UnityBundle.open(UnityBundle.of(junk)).textures(); check(false, "junk bundle should fail"); }
        catch (java.io.IOException expectedFailure) { checks++; }
    }

    // ------------------------------------------------------------------ meshes, materials, renderers
    static void models(File dir) throws Exception {
        Map<String, String> exp = new HashMap<>();
        List<String> materials = new ArrayList<>();
        for (String line : new String(Files.readAllBytes(new File(dir, "expected_mesh.txt").toPath()), "UTF-8").split("\n")) {
            String[] f = line.split("\t");
            if (f[0].equals("part")) exp.put("part" + f[1], f[2]);
            else if (f[0].equals("material")) materials.add(f[1]);
            else if (f.length > 1) exp.put(f[0], line.substring(f[0].length() + 1));
        }
        File zip = new File(dir, "unity_fixture.zip");
        try (FileInputStream in = new FileInputStream(zip); FileChannel ch = in.getChannel()) {
            UnityObb.Info info = UnityObb.scan(ch, zip.getName(), null);
            UnityObb.BundleInfo a = info.bundle("assets/Bundles/a.bytes");
            check(a != null && a.meshes == 1 && info.meshes() == 1, "one mesh counted");
            List<UnityModels.Model> models = UnityModels.models(ch, info, a);
            check(models.size() == 1, "one model listed");
            UnityModels.Model m = models.get(0);
            String[] head = exp.get("mesh").split("\t");
            check(m.name.equals(head[0]) && m.vertexCount == Integer.parseInt(head[1]) && m.parts == Integer.parseInt(head[2]), "model " + m.label());
            check(m.materials.size() == 2 && m.materials.get(0) != null && m.materials.get(1) != null, "renderer materials resolved");

            UnityModels.Material q = UnityModels.material(ch, info, m.materials.get(0));
            UnityModels.Material t = UnityModels.material(ch, info, m.materials.get(1));
            check(q.name.equals(materials.get(0)) && t.name.equals(materials.get(1)), "material names");
            check("_MainTex".equals(q.textureSlot), "main texture slot preferred over _BumpMap");
            Object[] qt = UnityObb.texture(info, q.texture), tt = UnityObb.texture(info, t.texture);
            check(qt != null && ((UnityBundle.Texture) qt[1]).name.equals("Inline"), "QuadMat → Inline");
            check(tt != null && ((UnityBundle.Texture) tt[1]).name.equals("Builtin") &&
                    ((UnityObb.BundleInfo) tt[0]).entry.equals("assets/bin/Data/data.unity3d"), "TriMat → Builtin in another bundle");

            UnityModels.Mesh mesh = UnityModels.mesh(ch, info, m.mesh);
            String[] pos = exp.get("pos").split(" "), uv = exp.get("uv").split(" ");
            for (int i = 0; i < pos.length; i++) check(Math.abs(mesh.pos[i] - Float.parseFloat(pos[i])) < 1e-6, "position " + i);
            for (int i = 0; i < uv.length; i++) check(Math.abs(mesh.uv[i] - Float.parseFloat(uv[i])) < 1e-3, "half-float uv " + i);
            check(mesh.normal != null && mesh.normal[2] == 1f, "normals");
            for (int p = 0; p < mesh.parts.length; p++) {
                StringBuilder sb = new StringBuilder();
                for (int x : mesh.parts[p]) sb.append(sb.length() == 0 ? "" : " ").append(x);
                check(sb.toString().equals(exp.get("part" + p)), "part " + p + " indices " + sb + " (with baseVertex)");
            }
            check(mesh.max[0] == 2f && mesh.min[1] == 0f, "bounds");
            String obj = UnityModels.toObj(mesh);
            check(obj.contains("v -1.000000 0.000000 0.000000") && obj.contains("g part2") && obj.contains("vt 0.500000 0.750000"), "OBJ export");
            check(UnityModels.half(0x3C00) == 1f && UnityModels.half(0xC000) == -2f && UnityModels.half(0x3800) == 0.5f, "half floats");
        }
    }

    // ------------------------------------------------------------------ projection painting
    /** Quads in the XY plane at the given z, facing +z, all mapped to UV (u0,v0)-(u1,v1). */
    static UnityModels.Mesh quads(float[] zs, float u0, float v0, float u1, float v1) {
        UnityModels.Mesh m = new UnityModels.Mesh();
        int n = zs.length;
        m.vertexCount = n * 4; m.pos = new float[n * 12]; m.normal = new float[n * 12]; m.uv = new float[n * 8];
        m.parts = new int[n][];
        float[][] corner = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        float[][] uvc = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        for (int q = 0; q < n; q++) {
            for (int c = 0; c < 4; c++) {
                int v = q * 4 + c;
                m.pos[v * 3] = corner[c][0]; m.pos[v * 3 + 1] = corner[c][1]; m.pos[v * 3 + 2] = zs[q];
                m.normal[v * 3 + 2] = 1;
                m.uv[v * 2] = uvc[c][0]; m.uv[v * 2 + 1] = uvc[c][1];
            }
            int b = q * 4;
            m.parts[q] = new int[]{b, b + 1, b + 2, b, b + 2, b + 3};
        }
        m.min = new float[]{-1, -1, Float.MAX_VALUE}; m.max = new float[]{1, 1, -Float.MAX_VALUE};
        for (float z : zs) { m.min[2] = Math.min(m.min[2], z); m.max[2] = Math.max(m.max[2], z); }
        return m;
    }

    static UnityPaint.Decal red(UnityPaint.View3D v, float sizeFrac, float opacity) {
        UnityPaint.Decal d = new UnityPaint.Decal();
        d.w = 4; d.h = 4; d.argb = new int[16]; java.util.Arrays.fill(d.argb, 0xFFFF0000);
        d.cx = v.width / 2f; d.cy = v.height / 2f; d.size = v.width * sizeFrac; d.opacity = opacity;
        return d;
    }

    static void paint() {
        int tw = 64, th = 64, grey = 0x80808080; // alpha 0x80 must survive
        // 1) one quad facing the camera, full UV square; small red design in the middle
        UnityPaint.Geometry g = new UnityPaint.Geometry(quads(new float[]{0}, 0, 0, 1, 1));
        UnityPaint.View3D v = UnityPaint.camera(0, 0, 1, g.center, g.radius, 400, 400);
        int[] px = new int[tw * th]; java.util.Arrays.fill(px, grey);
        UnityPaint.Result r = UnityPaint.stamp(g, v, UnityPaint.depth(g, v, 400), red(v, 0.1f, 1), px, tw, th, java.util.Arrays.asList(0), java.util.Arrays.asList(0));
        check(r.painted > 0 && !r.mirrored(), "stamp painted " + r.painted);
        check(px[32 * tw + 32] == 0x80FF0000, "centre texel red with texture alpha kept: " + Integer.toHexString(px[32 * tw + 32]));
        check(px[0] == grey && px[tw - 1] == grey && px[(th - 1) * tw] == grey, "corners untouched");
        // the red patch is centred on the texture (screen centre → quad centre → UV 0.5)
        int minX = tw, maxX = -1, minY = th, maxY = -1;
        for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) if (px[y * tw + x] == 0x80FF0000) {
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        check(Math.abs((minX + maxX) / 2.0 - 31.5) <= 1 && Math.abs((minY + maxY) / 2.0 - 31.5) <= 1, "patch centred: x " + minX + ".." + maxX + " y " + minY + ".." + maxY);

        // 2) opacity blends
        java.util.Arrays.fill(px, 0xFF000000);
        UnityPaint.stamp(g, v, UnityPaint.depth(g, v, 400), red(v, 0.1f, 0.5f), px, tw, th, java.util.Arrays.asList(0), java.util.Arrays.asList(0));
        int c = px[32 * tw + 32];
        check(Math.abs(((c >> 16) & 255) - 128) <= 1 && (c & 0xFFFF) == 0, "50% opacity: " + Integer.toHexString(c));

        // 3) seen from behind: the quad faces away, nothing painted
        UnityPaint.View3D back = UnityPaint.camera(180, 0, 1, g.center, g.radius, 400, 400);
        java.util.Arrays.fill(px, grey);
        r = UnityPaint.stamp(g, back, UnityPaint.depth(g, back, 400), red(back, 0.5f, 1), px, tw, th, java.util.Arrays.asList(0), java.util.Arrays.asList(0));
        check(r.painted == 0, "back faces not painted");

        // 4) occlusion + mirrored UVs: a second quad in front (z=0.5) with its own texture; the hidden one behind
        //    shares UVs with a third quad far away
        UnityPaint.Geometry g2 = new UnityPaint.Geometry(quads(new float[]{0, 0.5f}, 0, 0, 1, 1));
        UnityPaint.View3D v2 = UnityPaint.camera(0, 0, 1, g2.center, g2.radius, 400, 400);
        java.util.Arrays.fill(px, grey);
        r = UnityPaint.stamp(g2, v2, UnityPaint.depth(g2, v2, 400), red(v2, 0.1f, 1), px, tw, th, java.util.Arrays.asList(0), java.util.Arrays.asList(0));
        check(r.painted == 0, "hidden surface not painted (" + r.painted + ")");
        // painting the front one, whose texture area is also used by the hidden one 0.5 away → mirrored warning
        java.util.Arrays.fill(px, grey);
        r = UnityPaint.stamp(g2, v2, UnityPaint.depth(g2, v2, 400), red(v2, 0.1f, 1), px, tw, th, java.util.Arrays.asList(1), java.util.Arrays.asList(0, 1));
        check(r.painted > 0 && r.mirrored(), "shared UV area reported (" + r.shared + "/" + r.painted + ")");

        // 5) seam padding: island covers the middle of the texture only; painting its whole area pads 2 texels out
        UnityPaint.Geometry g3 = new UnityPaint.Geometry(quads(new float[]{0}, 0.25f, 0.25f, 0.75f, 0.75f));
        UnityPaint.View3D v3 = UnityPaint.camera(0, 0, 1, g3.center, g3.radius, 400, 400);
        java.util.Arrays.fill(px, grey);
        UnityPaint.stamp(g3, v3, UnityPaint.depth(g3, v3, 400), red(v3, 1.5f, 1), px, tw, th, java.util.Arrays.asList(0), java.util.Arrays.asList(0));
        check(px[32 * tw + 16] == 0x80FF0000, "island edge painted");
        check(px[32 * tw + 15] == 0x80FF0000 && px[32 * tw + 14] == 0x80FF0000, "2-texel padding outside the island");
        check(px[32 * tw + 13] == grey && px[0] == grey, "padding stops after 2 texels");

        // 6) the camera matches a hand-checked projection: model centre lands mid-screen
        float[] s = UnityPaint.project(v, 0, 0, 0);
        check(Math.abs(s[0] - 200) < 0.01 && Math.abs(s[1] - 200) < 0.01, "centre projects to screen centre");
        float[] right = UnityPaint.project(v, 1, 0, 0);
        check(right[0] > 200 && Math.abs(right[1] - 200) < 0.01, "+x is to the right");
    }
}
