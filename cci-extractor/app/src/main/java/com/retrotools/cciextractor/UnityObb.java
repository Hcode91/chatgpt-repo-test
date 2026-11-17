package com.retrotools.cciextractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Unity games packed as a zip: split-binary OBBs (main.N.package.obb, which Unity requires to be stored, not
 * deflated) and APKs with bundles under assets/. Also handles a single loose UnityFS bundle file.
 *
 * Scan lists every Texture2D in every bundle. Rebuild writes a new zip where only the bundles holding edited
 * textures change (patched in place, see {@link UnityBundle#writePatched}); every other entry is copied byte for
 * byte with its original CRC.
 */
final class UnityObb {

    static final class Entry {
        String name; int method, flags, time, date, extAttr; long crc, csize, usize, localOffset, dataOffset;
        boolean dir() { return name.endsWith("/"); }
    }

    static final class BundleInfo {
        String entry; long offset, size;
        final List<UnityBundle.Texture> textures = new ArrayList<>();
        String error;
    }

    static final class Info {
        boolean single, apk;
        String singleName;
        long fileSize;
        final List<Entry> entries = new ArrayList<>();
        final List<BundleInfo> bundles = new ArrayList<>();
        int textures, unsupported, broken;
        Map<String, UnityBundle.TypeNode> fallbackTrees = new java.util.HashMap<>();
        String description() {
            return (single ? "Unity asset bundle" : apk ? "Unity APK" : "Unity OBB") + " · " + bundles.size() +
                    " bundle(s) · " + textures + " textures" + (unsupported > 0 ? " (" + unsupported + " in formats not supported yet)" : "") +
                    (broken > 0 ? " · " + broken + " bundle(s) couldn't be read" : "");
        }
    }

    interface Progress { void step(int done, int total, String what); boolean cancelled(); }

    // ------------------------------------------------------------------ zip reading
    private static int le16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    private static long le32(byte[] b, int o) { return le16(b, o) | ((long) le16(b, o + 2) << 16); }
    private static long le64(byte[] b, int o) { return le32(b, o) | (le32(b, o + 4) << 32); }

    private static void readFully(FileChannel ch, long pos, byte[] dst) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(dst);
        while (bb.hasRemaining()) {
            int n = ch.read(bb, pos + bb.position());
            if (n < 0) throw new IOException("Unexpected end of file");
        }
    }

    static List<Entry> readZip(FileChannel ch) throws IOException {
        long size = ch.size();
        if (size < 22) throw new IOException("Not a zip file");
        int tail = (int) Math.min(size, 65557);
        byte[] t = new byte[tail];
        readFully(ch, size - tail, t);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) if (le32(t, i) == 0x06054b50L) { eocd = i; break; }
        if (eocd < 0) throw new IOException("Not a zip file (no end-of-directory record)");
        long count = le16(t, eocd + 10), cdSize = le32(t, eocd + 12), cdOffset = le32(t, eocd + 16);
        if (eocd >= 20 && le32(t, eocd - 20) == 0x07064b50L) {
            long z64 = le64(t, eocd - 20 + 8);
            byte[] r = new byte[56];
            readFully(ch, z64, r);
            if (le32(r, 0) != 0x06064b50L) throw new IOException("Broken zip64 directory");
            count = le64(r, 32); cdSize = le64(r, 40); cdOffset = le64(r, 48);
        }
        if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > size || cdSize > 512L << 20 || count > 1_000_000)
            throw new IOException("Zip directory is out of range");
        byte[] cd = new byte[(int) cdSize];
        readFully(ch, cdOffset, cd);
        List<Entry> out = new ArrayList<>();
        int p = 0;
        byte[] lh = new byte[30];
        for (long i = 0; i < count; i++) {
            if (p + 46 > cd.length || le32(cd, p) != 0x02014b50L) throw new IOException("Broken zip directory");
            Entry e = new Entry();
            e.flags = le16(cd, p + 8); e.method = le16(cd, p + 10); e.time = le16(cd, p + 12); e.date = le16(cd, p + 14);
            e.crc = le32(cd, p + 16); e.csize = le32(cd, p + 20); e.usize = le32(cd, p + 24);
            int nl = le16(cd, p + 28), xl = le16(cd, p + 30), cl = le16(cd, p + 32);
            e.extAttr = (int) le32(cd, p + 38); e.localOffset = le32(cd, p + 42);
            if (p + 46 + nl + xl + cl > cd.length) throw new IOException("Broken zip directory");
            e.name = new String(cd, p + 46, nl, (e.flags & 0x800) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
            int x = p + 46 + nl, xe = x + xl;
            while (x + 4 <= xe) {
                int id = le16(cd, x), len = le16(cd, x + 2), q = x + 4, end = Math.min(xe, q + len);
                if (id == 1) { // zip64 extended sizes/offset, present only for fields set to 0xFFFFFFFF
                    if (e.usize == 0xFFFFFFFFL && q + 8 <= end) { e.usize = le64(cd, q); q += 8; }
                    if (e.csize == 0xFFFFFFFFL && q + 8 <= end) { e.csize = le64(cd, q); q += 8; }
                    if (e.localOffset == 0xFFFFFFFFL && q + 8 <= end) { e.localOffset = le64(cd, q); }
                }
                x += 4 + len;
            }
            p += 46 + nl + xl + cl;
            readFully(ch, e.localOffset, lh);
            if (le32(lh, 0) != 0x04034b50L) throw new IOException("Broken zip entry " + e.name);
            e.dataOffset = e.localOffset + 30 + le16(lh, 26) + le16(lh, 28);
            if (e.dataOffset + e.csize > size) throw new IOException("Zip entry " + e.name + " is truncated");
            out.add(e);
        }
        return out;
    }

    static boolean isZip(FileChannel ch) throws IOException {
        if (ch.size() < 30) return false;
        byte[] m = new byte[4];
        readFully(ch, 0, m);
        return le32(m, 0) == 0x04034b50L;
    }

    /** A zip (OBB/APK) that contains at least one stored UnityFS bundle, or a loose bundle file. */
    static boolean isUnity(FileChannel ch) throws IOException {
        if (UnityBundle.isBundle(UnityBundle.of(ch, 0, ch.size()))) return true;
        if (!isZip(ch)) return false;
        List<Entry> entries;
        try { entries = readZip(ch); } catch (IOException e) { return false; }
        boolean guid = false;
        for (Entry e : entries) {
            if (e.name.equals("unity_obb_guid") || e.name.startsWith("assets/bin/Data/")) guid = true;
            if (e.method == 0 && !e.dir() && e.usize >= 48 && isBundleEntry(ch, e)) return true;
        }
        return guid;
    }

    private static boolean isBundleEntry(FileChannel ch, Entry e) throws IOException {
        if (e.method != 0) return false;
        return UnityBundle.isBundle(UnityBundle.of(ch, e.dataOffset, e.csize));
    }

    static Info scan(FileChannel ch, String fileName, Progress progress) throws IOException {
        Info info = new Info();
        info.fileSize = ch.size();
        List<BundleInfo> todo = new ArrayList<>();
        if (UnityBundle.isBundle(UnityBundle.of(ch, 0, ch.size()))) {
            info.single = true; info.singleName = fileName;
            BundleInfo b = new BundleInfo(); b.entry = fileName; b.offset = 0; b.size = ch.size();
            todo.add(b);
        } else {
            info.entries.addAll(readZip(ch));
            int compressedBundles = 0;
            for (Entry e : info.entries) {
                if (e.name.equals("AndroidManifest.xml")) info.apk = true;
                if (e.dir() || e.csize < 48) continue;
                if (e.method != 0) {
                    if (e.name.startsWith("assets/") && looksDeflatedBundle(e)) compressedBundles++;
                    continue;
                }
                if (isBundleEntry(ch, e)) {
                    BundleInfo b = new BundleInfo(); b.entry = e.name; b.offset = e.dataOffset; b.size = e.csize;
                    todo.add(b);
                }
            }
            if (todo.isEmpty())
                throw new IOException(compressedBundles > 0 ? "The bundles in this zip are deflate-compressed, so they can't be patched in place. " +
                        "Unity OBBs are normally stored uncompressed — was this file re-zipped?" : "No Unity asset bundles found in this zip");
        }
        java.util.HashMap<String, UnityBundle.TypeNode> trees = new java.util.HashMap<>();
        List<BundleInfo> stripped = new ArrayList<>();
        for (int i = 0; i < todo.size(); i++) {
            if (progress != null) { if (progress.cancelled()) throw new IOException("Cancelled"); progress.step(i, todo.size(), todo.get(i).entry); }
            BundleInfo b = todo.get(i);
            try {
                UnityBundle ub = UnityBundle.open(UnityBundle.of(ch, b.offset, b.size));
                String[] v = new String[1];
                UnityBundle.TypeNode tree = ub.textureTypeTree(v);
                if (tree != null && !trees.containsKey(v[0])) trees.put(v[0], tree);
                b.textures.addAll(ub.textures(null));
            } catch (IOException | RuntimeException e) {
                b.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (b.error.contains("no type trees")) stripped.add(b);
            }
            info.bundles.add(b);
        }
        // second pass: stripped files (e.g. data.unity3d) with a Texture2D layout borrowed from the other bundles
        for (BundleInfo b : stripped) {
            try {
                b.textures.addAll(UnityBundle.open(UnityBundle.of(ch, b.offset, b.size)).textures(trees));
                b.error = null;
            } catch (IOException | RuntimeException e) {
                b.textures.clear();
                b.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        info.fallbackTrees = trees;
        for (BundleInfo b : info.bundles) {
            if (b.error != null) info.broken++;
            for (UnityBundle.Texture t : b.textures) {
                info.textures++;
                if (!UnityTextures.supported(t.format)) info.unsupported++;
            }
        }
        return info;
    }

    private static boolean looksDeflatedBundle(Entry e) {
        String n = e.name.toLowerCase(Locale.ROOT);
        return n.endsWith(".bundle") || n.endsWith(".unity3d") || n.endsWith(".bytes") || n.endsWith(".ab") || n.endsWith(".assets");
    }

    static byte[] readTexture(FileChannel ch, BundleInfo b, UnityBundle.Texture t) throws IOException {
        UnityBundle ub = UnityBundle.open(UnityBundle.of(ch, b.offset, b.size));
        return ub.read(t.dataPos, t.dataSize);
    }

    // ------------------------------------------------------------------ export index
    static final String INDEX_NAME = "unity_textures.tsv";
    static final String INDEX_HEADER = "# Game Asset Extractor · Unity texture index. Edit the PNGs (keep names), then rebuild.\n" +
            "# bundle\tfile\tpath_id\tname\tformat\twidth\theight\tmips\tpng\tsha1\n";

    static final class Row {
        String bundle, file, name, png, sha1; long pathId; int format, width, height, mips;
    }

    static String row(String bundle, UnityBundle.Texture t, String png, String sha1) {
        return clean(bundle) + "\t" + clean(t.file) + "\t" + t.pathId + "\t" + clean(t.name) + "\t" + t.format + "\t" +
                t.width + "\t" + t.height + "\t" + t.mips + "\t" + clean(png) + "\t" + sha1 + "\n";
    }
    private static String clean(String s) { return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    static List<Row> parseIndex(String text) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.replace("\r", "");
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 10) throw new IOException("unity_textures.tsv has a broken line: " + line);
            Row r = new Row();
            try {
                r.bundle = f[0]; r.file = f[1]; r.pathId = Long.parseLong(f[2]); r.name = f[3];
                r.format = Integer.parseInt(f[4]); r.width = Integer.parseInt(f[5]); r.height = Integer.parseInt(f[6]);
                r.mips = Integer.parseInt(f[7]); r.png = f[8]; r.sha1 = f[9];
            } catch (NumberFormatException e) { throw new IOException("unity_textures.tsv has a broken line: " + line); }
            rows.add(r);
        }
        return rows;
    }

    /** Folder-safe PNG path for a texture: bundle path without extension / name__pathId.png */
    static String pngPath(String bundle, UnityBundle.Texture t) {
        String base = bundle;
        int slash = base.lastIndexOf('/'), dot = base.lastIndexOf('.');
        if (dot > slash) base = base.substring(0, dot);
        StringBuilder sb = new StringBuilder();
        for (String part : base.split("/")) { if (part.isEmpty()) continue; sb.append(safe(part)).append('/'); }
        return sb + safe(t.name) + "__" + t.pathId + ".png";
    }

    static String safe(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == ' ' ? c : '_');
        String r = b.toString().trim();
        if (r.isEmpty() || r.equals(".") || r.equals("..")) r = "unnamed";
        return r;
    }

    static String sha1(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(String.format(Locale.ROOT, "%02x", x & 255));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ patching + rebuild
    /** Finds the texture a row refers to in the scanned info. */
    static UnityBundle.Texture find(Info info, Row r, BundleInfo[] bundleOut) {
        for (BundleInfo b : info.bundles) {
            if (!b.entry.equals(r.bundle) && !(info.single)) continue;
            for (UnityBundle.Texture t : b.textures)
                if (t.pathId == r.pathId && t.file.equals(r.file)) { if (bundleOut != null) bundleOut[0] = b; return t; }
        }
        return null;
    }

    /**
     * Encodes an edited image (top-down ARGB, already at the texture's original size) into the texture's original
     * format and mip count. The result is always exactly the original data size.
     */
    static byte[] encodeFor(UnityBundle.Texture t, int[] argb, int w, int h) throws IOException {
        if (w != t.width || h != t.height) throw new IOException(t.name + ": image is " + w + "x" + h + ", texture is " + t.width + "x" + t.height);
        if (!UnityTextures.supported(t.format)) throw new IOException(t.name + ": can't write " + UnityTextures.name(t.format) + " yet");
        long expect = UnityTextures.totalSize(t.format, t.width, t.height, t.mips);
        if (expect != t.dataSize) throw new IOException(t.name + ": data size " + t.dataSize + " doesn't match " +
                UnityTextures.name(t.format) + " " + t.width + "x" + t.height + " with " + t.mips + " mips (" + expect + ")");
        byte[] enc = UnityTextures.encode(argb, w, h, t.format, t.mips);
        if (enc.length != t.dataSize) throw new IOException("Internal error: encoded size differs");
        return enc;
    }

    /**
     * Writes the rebuilt file. {@code patches}: bundle entry name → (uncompressed offset → bytes).
     * Patched bundles are staged in {@code tempDir} so huge OBBs never sit in memory.
     */
    static void rebuild(FileChannel src, Info info, Map<String, TreeMap<Long, byte[]>> patches, File tempDir,
                        OutputStream out, Progress progress) throws IOException {
        if (info.single) {
            BundleInfo b = info.bundles.get(0);
            TreeMap<Long, byte[]> p = patches.get(b.entry);
            UnityBundle.open(UnityBundle.of(src, 0, src.size())).writePatched(p == null ? new TreeMap<>() : p, out);
            return;
        }
        Map<String, File> staged = new LinkedHashMap<>();
        try {
            int n = 0;
            for (Map.Entry<String, TreeMap<Long, byte[]>> e : patches.entrySet()) {
                if (progress != null) { if (progress.cancelled()) throw new IOException("Cancelled"); progress.step(n++, patches.size(), "Patching " + e.getKey()); }
                BundleInfo b = null;
                for (BundleInfo x : info.bundles) if (x.entry.equals(e.getKey())) b = x;
                if (b == null) throw new IOException("Bundle not found: " + e.getKey());
                File tmp = File.createTempFile("bundle", ".tmp", tempDir);
                staged.put(e.getKey(), tmp);
                try (FileOutputStream fo = new FileOutputStream(tmp);
                     java.io.BufferedOutputStream bo = new java.io.BufferedOutputStream(fo, 1 << 16)) {
                    UnityBundle.open(UnityBundle.of(src, b.offset, b.size)).writePatched(e.getValue(), bo);
                }
                // verify the patched bundle opens and decodes before it goes anywhere near the output
                try (FileInputStream fi = new FileInputStream(tmp); FileChannel tc = fi.getChannel()) {
                    UnityBundle nb = UnityBundle.open(UnityBundle.of(tc, 0, tc.size()));
                    for (Map.Entry<Long, byte[]> pe : e.getValue().entrySet()) {
                        byte[] back = nb.read(pe.getKey(), pe.getValue().length);
                        if (!java.util.Arrays.equals(back, pe.getValue())) throw new IOException("Verification failed for " + e.getKey());
                    }
                    nb.textures(info.fallbackTrees);
                }
            }
            writeZip(src, info.entries, staged, out, progress);
        } finally {
            for (File f : staged.values()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    private static final class Counting extends OutputStream {
        final OutputStream out; long n;
        Counting(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { out.write(b); n++; }
        @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); n += l; }
        @Override public void flush() throws IOException { out.flush(); }
    }

    private static void w16(OutputStream o, int v) throws IOException { o.write(v); o.write(v >>> 8); }
    private static void w32(OutputStream o, long v) throws IOException { w16(o, (int) v & 0xFFFF); w16(o, (int) (v >>> 16) & 0xFFFF); }

    /** Stored-only zip writer (what Unity OBBs require). Entries keep their order, names, dates and attributes. */
    static void writeZip(FileChannel src, List<Entry> entries, Map<String, File> replace, OutputStream rawOut, Progress progress) throws IOException {
        Counting out = new Counting(rawOut);
        long[] offsets = new long[entries.size()], crcs = new long[entries.size()], sizes = new long[entries.size()];
        int[] methods = new int[entries.size()]; long[] usizes = new long[entries.size()];
        byte[] buf = new byte[1 << 16];
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (progress != null && i % 50 == 0) { if (progress.cancelled()) throw new IOException("Cancelled"); progress.step(i, entries.size(), "Writing " + e.name); }
            File rep = replace.get(e.name);
            long crc = e.crc, size = e.csize, usize = e.usize; int method = e.method;
            if (rep != null) {
                CRC32 c = new CRC32();
                try (FileInputStream in = new FileInputStream(rep)) { int n; while ((n = in.read(buf)) > 0) c.update(buf, 0, n); }
                crc = c.getValue(); size = rep.length(); usize = size; method = 0;
            }
            if (out.n > 0xFFFFFFFFL || size > 0xFFFFFFFFL) throw new IOException("Output is over 4 GB, which this zip writer doesn't support");
            byte[] name = e.name.getBytes((e.flags & 0x800) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
            offsets[i] = out.n; crcs[i] = crc; sizes[i] = size; usizes[i] = usize; methods[i] = method;
            w32(out, 0x04034b50L); w16(out, method == 0 ? 10 : 20); w16(out, e.flags & 0x800); w16(out, method);
            w16(out, e.time); w16(out, e.date); w32(out, crc); w32(out, size); w32(out, usize);
            w16(out, name.length); w16(out, 0); out.write(name);
            if (rep != null) {
                try (FileInputStream in = new FileInputStream(rep)) { int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }
            } else {
                long at = e.dataOffset, left = e.csize;
                while (left > 0) {
                    int n = (int) Math.min(left, buf.length);
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                    while (bb.hasRemaining()) { if (src.read(bb, at + bb.position()) < 0) throw new IOException("Unexpected end of source"); }
                    out.write(buf, 0, n);
                    at += n; left -= n;
                }
            }
        }
        long cdStart = out.n;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            byte[] name = e.name.getBytes((e.flags & 0x800) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
            w32(out, 0x02014b50L); w16(out, 63); w16(out, methods[i] == 0 ? 10 : 20); w16(out, e.flags & 0x800); w16(out, methods[i]);
            w16(out, e.time); w16(out, e.date); w32(out, crcs[i]); w32(out, sizes[i]); w32(out, usizes[i]);
            w16(out, name.length); w16(out, 0); w16(out, 0); w16(out, 0); w16(out, 0); w32(out, e.extAttr & 0xffffffffL);
            w32(out, offsets[i]); out.write(name);
        }
        long cdSize = out.n - cdStart;
        if (entries.size() > 0xFFFF || cdStart > 0xFFFFFFFFL) throw new IOException("Too many entries / too large for this zip writer");
        w32(out, 0x06054b50L); w16(out, 0); w16(out, 0); w16(out, entries.size()); w16(out, entries.size());
        w32(out, cdSize); w32(out, cdStart); w16(out, 0);
        out.flush();
    }
}
