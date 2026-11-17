package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Unreal Engine 4 .pak files, versions 3–5 (UE 4.13 – 4.19 era): unencrypted index, files stored or zlib-compressed in
 * blocks. Files are read with random access (only the blocks a read touches are inflated).
 *
 * Rebuilding is done in place so the pak (and the OBB around it) keeps exactly its original size: Android games
 * check the OBB's size before starting and refuse a file that differs. A changed file is recompressed at the highest
 * zlib level into its own slot. If it doesn't fit, the files right after it are recompressed and moved up until the
 * run ends where an original file starts; everything else stays byte-for-byte. The index (same length, since every
 * entry keeps its block count) and the footer's SHA-1 are rewritten in place.
 * The older append mode ({@link #buildTail}) is kept for tests and loose paks that don't need a fixed size.
 */
final class UnrealPak {
    static final int MAGIC = 0x5A6F12E1;

    static final class Entry {
        String name;                 // path inside the pak (prefix with the mount point for the full path)
        long offset, size, usize;    // offset of the entry header; stored size; real size
        int method;                  // 0 = none, 1 = zlib
        long[] blockStart = new long[0], blockEnd = new long[0];
        boolean encrypted;
        int blockSize;
        byte[] hash = new byte[20];
        long dataStart;              // first byte of the stored data
    }

    final UnityBundle.Source src;
    final int version;
    final String mount;
    final List<Entry> entries = new ArrayList<>();
    final Map<String, Entry> byName = new LinkedHashMap<>();
    final long indexOffset, indexSize;
    final int footerSize;

    private UnrealPak(UnityBundle.Source src, int version, String mount, long io, long is, int footerSize) {
        this.src = src; this.version = version; this.mount = mount; this.indexOffset = io; this.indexSize = is; this.footerSize = footerSize;
    }

    // ------------------------------------------------------------------ little-endian cursor
    private static final class Le {
        final byte[] b; int p;
        Le(byte[] b, int p) { this.b = b; this.p = p; }
        void need(int n) throws IOException { if (n < 0 || p < 0 || p + n > b.length) throw new IOException("Pak index is truncated"); }
        int u8() throws IOException { need(1); return b[p++] & 255; }
        int i32() throws IOException { need(4); int v = (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16) | (b[p + 3] << 24); p += 4; return v; }
        long i64() throws IOException { long lo = i32() & 0xffffffffL, hi = i32() & 0xffffffffL; return lo | (hi << 32); }
        String fstr() throws IOException {
            int n = i32();
            if (n == 0) return "";
            if (n > 0) { if (n > 65536) throw new IOException("Bad string in pak index"); need(n); String s = new String(b, p, n - 1, StandardCharsets.ISO_8859_1); p += n; return s; }
            n = -n; if (n > 65536) throw new IOException("Bad string in pak index");
            need(n * 2); String s = new String(b, p, n * 2 - 2, StandardCharsets.UTF_16LE); p += n * 2; return s;
        }
    }

    /** Footer layout per version: (size, offset of the magic within it). */
    private static int footerSize(int version) { return version < 4 ? 44 : version < 7 ? 45 : 61; }

    static boolean isPak(UnityBundle.Source s) throws IOException {
        for (int fs : new int[]{44, 45, 61}) {
            if (s.size() < fs) continue;
            byte[] f = new byte[4];
            s.read(s.size() - 44, f, 0, 4);
            int magic = (f[0] & 255) | ((f[1] & 255) << 8) | ((f[2] & 255) << 16) | (f[3] << 24);
            if (magic == MAGIC) return true;
        }
        return false;
    }

    static UnrealPak open(UnityBundle.Source s) throws IOException {
        if (s.size() < 44) throw new IOException("Not an Unreal pak");
        byte[] f = new byte[44];
        s.read(s.size() - 44, f, 0, 44);
        Le r = new Le(f, 0);
        if (r.i32() != MAGIC) throw new IOException("Not an Unreal pak (no footer magic)");
        int version = r.i32();
        long io = r.i64(), is = r.i64();
        byte[] hash = new byte[20];
        System.arraycopy(f, 24, hash, 0, 20);
        if (version < 3 || version > 7) throw new IOException("Unreal pak version " + version + " isn't supported yet (3–7 are)");
        int fs = footerSize(version);
        if (version >= 4) {
            byte[] enc = new byte[1];
            s.read(s.size() - 45, enc, 0, 1);
            if (enc[0] != 0) throw new IOException("This pak's index is encrypted (AES). Encrypted paks can't be read without the game's key.");
        }
        if (io < 0 || is <= 0 || is > 512L << 20 || io + is > s.size() - fs) throw new IOException("Pak index is out of range");
        byte[] index = new byte[(int) is];
        s.read(io, index, 0, (int) is);
        if (!java.util.Arrays.equals(sha1(index, 0, index.length), hash)) throw new IOException("Pak index checksum doesn't match (damaged file?)");
        Le x = new Le(index, 0);
        UnrealPak pak = new UnrealPak(s, version, x.fstr(), io, is, fs);
        int count = x.i32();
        if (count < 0 || count > 5_000_000) throw new IOException("Bad file count in pak");
        for (int i = 0; i < count; i++) {
            Entry e = new Entry();
            e.name = x.fstr();
            readEntry(x, e, version);
            e.dataStart = e.offset + headerSize(e, version);
            if (e.offset < 0 || e.dataStart + e.size > io) throw new IOException(e.name + " lies outside the pak data");
            if (e.method != 0 && e.method != 1)
                throw new IOException(e.name + ": compression method " + e.method + " isn't supported (zlib is)");
            pak.entries.add(e);
            pak.byName.put(e.name, e);
        }
        if (x.p != index.length) throw new IOException("Pak index has " + (index.length - x.p) + " unexpected trailing bytes");
        return pak;
    }

    private static void readEntry(Le x, Entry e, int version) throws IOException {
        e.offset = x.i64(); e.size = x.i64(); e.usize = x.i64(); e.method = x.i32();
        x.need(20); System.arraycopy(x.b, x.p, e.hash, 0, 20); x.p += 20;
        if (e.method != 0) {
            int bc = x.i32();
            if (bc < 0 || bc > 10_000_000) throw new IOException("Bad compression block count");
            e.blockStart = new long[bc]; e.blockEnd = new long[bc];
            for (int k = 0; k < bc; k++) { e.blockStart[k] = x.i64(); e.blockEnd[k] = x.i64(); }
        }
        e.encrypted = x.u8() != 0;
        e.blockSize = x.i32();
        if (e.size < 0 || e.usize < 0) throw new IOException("Bad entry size");
    }

    static int headerSize(Entry e, int version) {
        return 8 * 3 + 4 + 20 + (e.method != 0 ? 4 + 16 * e.blockStart.length : 0) + 1 + 4;
    }

    /** Absolute position of a compression block (version 5+ stores them relative to the entry). */
    private long abs(Entry e, long v) { return version >= 5 ? e.offset + v : v; }

    // ------------------------------------------------------------------ reading
    private Entry cachedEntry; private int cachedBlock = -1; private byte[] cachedData;

    private byte[] block(Entry e, int k) throws IOException {
        if (e == cachedEntry && k == cachedBlock) return cachedData;
        long s = abs(e, e.blockStart[k]), en = abs(e, e.blockEnd[k]);
        if (s < e.dataStart || en < s || en > e.dataStart + e.size) throw new IOException(e.name + ": compression block out of range");
        byte[] c = new byte[(int) (en - s)];
        src.read(s, c, 0, c.length);
        long want = Math.min(e.blockSize, e.usize - (long) k * e.blockSize);
        byte[] out = inflate(c, (int) want);
        cachedEntry = e; cachedBlock = k; cachedData = out;
        return out;
    }

    static byte[] inflate(byte[] c, int want) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(c);
            byte[] out = new byte[want];
            int n = 0;
            while (n < want) {
                int got = inf.inflate(out, n, want - n);
                if (got == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break;
                n += got;
            }
            if (n != want) throw new IOException("zlib block inflated to " + n + " bytes, expected " + want);
            return out;
        } catch (DataFormatException ex) {
            throw new IOException("Corrupt zlib data in pak");
        } finally { inf.end(); }
    }

    /** Reads {@code len} bytes of a file's real (uncompressed) content starting at {@code pos}. */
    byte[] read(Entry e, long pos, int len) throws IOException {
        if (e.encrypted) throw new IOException(e.name + " is encrypted");
        if (pos < 0 || len < 0 || pos + len > e.usize) throw new IOException("Read outside " + e.name);
        byte[] out = new byte[len];
        if (e.method == 0) { src.read(e.dataStart + pos, out, 0, len); return out; }
        int at = 0;
        while (at < len) {
            long p = pos + at;
            int k = (int) (p / e.blockSize);
            if (k >= e.blockStart.length) throw new IOException(e.name + ": read past its blocks");
            byte[] b = block(e, k);
            int off = (int) (p - (long) k * e.blockSize);
            int n = Math.min(len - at, b.length - off);
            if (n <= 0) throw new IOException(e.name + ": short block");
            System.arraycopy(b, off, out, at, n);
            at += n;
        }
        return out;
    }

    byte[] read(Entry e) throws IOException {
        if (e.usize > Integer.MAX_VALUE - 16) throw new IOException(e.name + " is too big to load");
        return read(e, 0, (int) e.usize);
    }

    // ------------------------------------------------------------------ writing (append + new index)
    private void writeEntry(ByteArrayOutputStream o, Entry e, boolean inData) {
        le64(o, inData ? 0 : e.offset); le64(o, e.size); le64(o, e.usize); le32(o, e.method);
        o.write(e.hash, 0, 20);
        if (e.method != 0) {
            le32(o, e.blockStart.length);
            for (int k = 0; k < e.blockStart.length; k++) { le64(o, e.blockStart[k]); le64(o, e.blockEnd[k]); }
        }
        o.write(e.encrypted ? 1 : 0);
        le32(o, e.blockSize);
    }

    private static void le32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >>> 8); o.write(v >>> 16); o.write(v >>> 24); }
    private static void le64(ByteArrayOutputStream o, long v) { le32(o, (int) v); le32(o, (int) (v >>> 32)); }
    private static void fstr(ByteArrayOutputStream o, String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 127) { ascii = false; break; }
        if (s.isEmpty()) { le32(o, 0); return; }
        if (ascii) { byte[] b = s.getBytes(StandardCharsets.ISO_8859_1); le32(o, b.length + 1); o.write(b, 0, b.length); o.write(0); }
        else { byte[] b = s.getBytes(StandardCharsets.UTF_16LE); le32(o, -(s.length() + 1)); o.write(b, 0, b.length); o.write(0); o.write(0); }
    }

    static byte[] sha1(byte[] b, int off, int len) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-1"); md.update(b, off, len); return md.digest(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /**
     * The bytes that follow the untouched prefix [0, indexOffset): new copies of the changed files, the new index and
     * the footer. {@code changed}: path inside the pak → new real content.
     */
    byte[] buildTail(Map<String, byte[]> changed) throws IOException {
        Map<String, Entry> replaced = new LinkedHashMap<>();
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        long pos = indexOffset;
        for (Map.Entry<String, byte[]> c : changed.entrySet()) {
            Entry old = byName.get(c.getKey());
            if (old == null) throw new IOException(c.getKey() + " isn't in the pak");
            byte[] data = c.getValue();
            Entry e = new Entry();
            e.name = old.name; e.offset = pos; e.usize = data.length; e.encrypted = false;
            int bs = old.blockSize > 0 ? old.blockSize : 65536;
            // compress in blocks like the original (or store, if it was stored)
            List<byte[]> blocks = new ArrayList<>();
            if (old.method == 1) {
                e.method = 1; e.blockSize = bs;
                for (int at = 0; at < data.length || (data.length == 0 && blocks.isEmpty()); at += bs) {
                    int n = Math.min(bs, data.length - at);
                    Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
                    try {
                        d.setInput(data, at, Math.max(0, n)); d.finish();
                        ByteArrayOutputStream cb = new ByteArrayOutputStream(Math.max(64, n / 2));
                        byte[] buf = new byte[8192];
                        while (!d.finished()) { int k = d.deflate(buf); cb.write(buf, 0, k); }
                        blocks.add(cb.toByteArray());
                    } finally { d.end(); }
                    if (data.length == 0) break;
                }
                e.blockStart = new long[blocks.size()]; e.blockEnd = new long[blocks.size()];
            } else {
                e.method = 0; e.blockSize = 0;
                blocks.add(data);
            }
            long size = 0;
            for (byte[] b : blocks) size += b.length;
            e.size = size;
            int hdr = headerSize(e, version);
            long dataPos = pos + hdr;
            long at = dataPos;
            for (int k = 0; k < e.blockStart.length; k++) {
                long s = at, en = at + blocks.get(k).length;
                e.blockStart[k] = version >= 5 ? s - pos : s;
                e.blockEnd[k] = version >= 5 ? en - pos : en;
                at = en;
            }
            MessageDigest md;
            try { md = MessageDigest.getInstance("SHA-1"); } catch (Exception ex) { throw new IllegalStateException(ex); }
            for (byte[] b : blocks) md.update(b);
            e.hash = md.digest();
            e.dataStart = dataPos;
            writeEntry(tail, e, true);
            for (byte[] b : blocks) tail.write(b, 0, b.length);
            pos = dataPos + size;
            replaced.put(e.name, e);
        }
        // new index: every entry, the changed ones pointing at their new copies
        ByteArrayOutputStream idx = new ByteArrayOutputStream();
        fstr(idx, mount);
        le32(idx, entries.size());
        for (Entry e : entries) {
            Entry w = replaced.getOrDefault(e.name, e);
            fstr(idx, e.name);
            writeEntry(idx, w, false);
        }
        byte[] index = idx.toByteArray();
        long newIndexOffset = pos;
        tail.write(index, 0, index.length);
        // footer
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        if (version >= 7) f.write(new byte[16], 0, 16);
        if (version >= 4) f.write(0);
        le32(f, MAGIC); le32(f, version); le64(f, newIndexOffset); le64(f, index.length);
        byte[] h = sha1(index, 0, index.length);
        f.write(h, 0, 20);
        byte[] fb = f.toByteArray();
        tail.write(fb, 0, fb.length);
        return tail.toByteArray();
    }

    /** The rebuilt pak as zip-entry content: original prefix streamed from the source, then the tail. */
    UnityObb.Content rebuilt(byte[] tail) {
        return new UnityObb.Content() {
            public long length() { return indexOffset + tail.length; }
            public InputStream open() {
                return new InputStream() {
                    long at = 0; final long total = indexOffset + tail.length;
                    @Override public int read() throws IOException { byte[] one = new byte[1]; int n = read(one, 0, 1); return n < 0 ? -1 : one[0] & 255; }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        if (at >= total) return -1;
                        int n = (int) Math.min(len, total - at);
                        if (at < indexOffset) {
                            n = (int) Math.min(n, indexOffset - at);
                            src.read(at, b, off, n);
                        } else System.arraycopy(tail, (int) (at - indexOffset), b, off, n);
                        at += n;
                        return n;
                    }
                };
            }
        };
    }

    // ------------------------------------------------------------------ writing in place (same size)
    static final class Patch {
        final long at; final byte[] data;
        Patch(long at, byte[] data) { this.at = at; this.data = data; }
    }

    /** Most bytes one rewrite run may cover before giving up (keeps memory bounded on phones). */
    static final int MAX_RUN = 96 << 20;

    private static List<byte[]> deflateBlocks(byte[] data, int bs) {
        List<byte[]> blocks = new ArrayList<>();
        for (int at = 0; at < data.length || (data.length == 0 && blocks.isEmpty()); at += bs) {
            int n = Math.max(0, Math.min(bs, data.length - at));
            Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
            try {
                d.setInput(data, at, n); d.finish();
                ByteArrayOutputStream cb = new ByteArrayOutputStream(Math.max(64, n / 2));
                byte[] buf = new byte[16384];
                while (!d.finished()) { int k = d.deflate(buf); cb.write(buf, 0, k); }
                blocks.add(cb.toByteArray());
            } finally { d.end(); }
            if (data.length == 0) break;
        }
        return blocks;
    }

    /** The stored blocks of an entry exactly as they are in the file. */
    private List<byte[]> rawBlocks(Entry e) throws IOException {
        List<byte[]> out = new ArrayList<>();
        if (e.method == 0) {
            if (e.size > Integer.MAX_VALUE - 16) throw new IOException(e.name + " is too big to move");
            byte[] b = new byte[(int) e.size]; src.read(e.dataStart, b, 0, b.length); out.add(b);
            return out;
        }
        for (int k = 0; k < e.blockStart.length; k++) {
            long s = abs(e, e.blockStart[k]), en = abs(e, e.blockEnd[k]);
            if (s < e.dataStart || en < s || en > e.dataStart + e.size) throw new IOException(e.name + ": compression block out of range");
            byte[] b = new byte[(int) (en - s)]; src.read(s, b, 0, b.length); out.add(b);
        }
        return out;
    }

    /** An entry (header + blocks) laid out at {@code pos}; {@code into} gets its bytes. */
    private Entry layout(Entry old, int method, int bs, List<byte[]> blocks, long usize, long pos, ByteArrayOutputStream into) {
        Entry e = new Entry();
        e.name = old.name; e.offset = pos; e.usize = usize; e.method = method; e.encrypted = false;
        e.blockSize = method == 0 ? old.blockSize : bs;
        long size = 0;
        for (byte[] b : blocks) size += b.length;
        e.size = size;
        e.blockStart = new long[method == 0 ? 0 : blocks.size()]; e.blockEnd = new long[e.blockStart.length];
        long dataPos = pos + headerSize(e, version), at = dataPos;
        for (int k = 0; k < e.blockStart.length; k++) {
            long st = at, en = at + blocks.get(k).length;
            e.blockStart[k] = version >= 5 ? st - pos : st;
            e.blockEnd[k] = version >= 5 ? en - pos : en;
            at = en;
        }
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-1"); } catch (Exception ex) { throw new IllegalStateException(ex); }
        for (byte[] b : blocks) md.update(b);
        e.hash = md.digest();
        e.dataStart = dataPos;
        writeEntry(into, e, true);
        for (byte[] b : blocks) into.write(b, 0, b.length);
        return e;
    }

    private int blockSizeOf(Entry old) {
        if (old.blockSize > 0) return old.blockSize;
        return 65536;
    }

    /**
     * Same-size rebuild: byte patches (pak offsets) that turn the original pak into one with {@code changed}
     * (path → new real content). Fails with a clear message if a change can't be made to fit.
     */
    List<Patch> buildInPlace(Map<String, byte[]> changed) throws IOException {
        for (String k : changed.keySet()) if (!byName.containsKey(k)) throw new IOException(k + " isn't in the pak");
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Long.compare(a.offset, b.offset));
        Map<String, Entry> replaced = new LinkedHashMap<>();
        List<Patch> patches = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (!changed.containsKey(sorted.get(i).name)) continue;
            long runStart = sorted.get(i).offset, cursor = runStart;
            ByteArrayOutputStream run = new ByteArrayOutputStream();
            int j = i;
            while (true) {
                Entry o = sorted.get(j);
                if (o.encrypted) throw new IOException(o.name + " is encrypted and can't be moved");
                long limit = j + 1 < sorted.size() ? sorted.get(j + 1).offset : indexOffset;
                byte[] data = changed.get(o.name);
                Entry w;
                if (data != null) {
                    if (o.method == 1) w = layout(o, 1, blockSizeOf(o), deflateBlocks(data, blockSizeOf(o)), data.length, cursor, run);
                    else { List<byte[]> one = new ArrayList<>(); one.add(data); w = layout(o, 0, 0, one, data.length, cursor, run); }
                } else {
                    // an unchanged file that has to move up: recompress it tighter if that helps, else move it as is
                    List<byte[]> blocks = rawBlocks(o);
                    if (o.method == 1) {
                        List<byte[]> tighter = deflateBlocks(read(o), blockSizeOf(o));
                        long a = 0, b = 0;
                        for (byte[] x : tighter) a += x.length;
                        for (byte[] x : blocks) b += x.length;
                        if (a < b && tighter.size() == blocks.size()) blocks = tighter;
                    }
                    w = layout(o, o.method, blockSizeOf(o), blocks, o.usize, cursor, run);
                }
                replaced.put(o.name, w);
                cursor = runStart + run.size();
                if (run.size() > MAX_RUN)
                    throw new IOException("The edited " + shortName(sorted.get(i).name) + " doesn't compress as small as the original, and there's no room to make up the difference. Try a simpler edit (flat colours compress best).");
                boolean last = j + 1 >= sorted.size();
                if (cursor <= limit) {
                    // fits: pad up to where the next original file starts
                    for (long k = cursor; k < limit; k++) run.write(0);
                    cursor = limit;
                    if (last || !changed.containsKey(sorted.get(j + 1).name)) break;
                } else if (last) {
                    throw new IOException("The edited " + shortName(sorted.get(i).name) + " doesn't compress as small as the original, and there's no room left at the end of the pak. Try a simpler edit (flat colours compress best).");
                }
                j++;
            }
            patches.add(new Patch(runStart, run.toByteArray()));
            i = j;
        }
        // index (same length) and footer
        ByteArrayOutputStream idx = new ByteArrayOutputStream();
        fstr(idx, mount);
        le32(idx, entries.size());
        for (Entry e : entries) { fstr(idx, e.name); writeEntry(idx, replaced.getOrDefault(e.name, e), false); }
        byte[] index = idx.toByteArray();
        if (index.length != indexSize) throw new IOException("The new pak index came out " + index.length + " bytes instead of " + indexSize);
        patches.add(new Patch(indexOffset, index));
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        if (version >= 7) { byte[] guid = new byte[16]; src.read(src.size() - footerSize, guid, 0, 16); f.write(guid, 0, 16); }
        if (version >= 4) f.write(0);
        le32(f, MAGIC); le32(f, version); le64(f, indexOffset); le64(f, index.length);
        byte[] h = sha1(index, 0, index.length);
        f.write(h, 0, 20);
        byte[] fb = f.toByteArray();
        patches.add(new Patch(src.size() - fb.length, fb));
        patches.sort((a, b) -> Long.compare(a.at, b.at));
        for (int k = 1; k < patches.size(); k++)
            if (patches.get(k - 1).at + patches.get(k - 1).data.length > patches.get(k).at) throw new IOException("Overlapping pak changes");
        return patches;
    }

    private static String shortName(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    /** The original pak with patches applied: same length. */
    UnityObb.Content patched(List<Patch> patches) {
        return overlay(src, 0, src.size(), patches);
    }

    /** Bytes [start, start+len) of a source with patches (given in source offsets) laid over them. */
    static UnityObb.Content overlay(UnityBundle.Source s, long start, long len, List<Patch> patches) {
        return new UnityObb.Content() {
            public long length() { return len; }
            public InputStream open() {
                return new InputStream() {
                    long at = 0;
                    @Override public int read() throws IOException { byte[] one = new byte[1]; int n = read(one, 0, 1); return n < 0 ? -1 : one[0] & 255; }
                    @Override public int read(byte[] b, int off, int n) throws IOException {
                        if (at >= len) return -1;
                        n = (int) Math.min(n, len - at);
                        long pos = start + at;
                        for (Patch p : patches) {
                            long pe = p.at + p.data.length;
                            if (pos >= p.at && pos < pe) {           // inside a patch
                                n = (int) Math.min(n, pe - pos);
                                System.arraycopy(p.data, (int) (pos - p.at), b, off, n);
                                at += n; return n;
                            }
                            if (p.at > pos) { n = (int) Math.min(n, p.at - pos); break; } // stop before the next patch
                        }
                        s.read(pos, b, off, n);
                        at += n; return n;
                    }
                };
            }
        };
    }
}
