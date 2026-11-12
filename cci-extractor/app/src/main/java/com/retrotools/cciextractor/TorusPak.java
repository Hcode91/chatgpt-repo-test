package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Torus Games "PAK" asset packs (e.g. packfile.dat in 3DS RomFS).
 *
 * <pre>
 * PAK file:   "PAK\0", u32 count, then count × { u32 nameHash, u32 offset, u32 storedSize } sorted by hash.
 * Each entry: "!ZLS", u32 rawSize, zlib stream.
 * Entries come in pairs in offset order: a small text manifest (may contain: hunkfile "Name")
 * followed by the binary hunk it describes.
 * Hunk:       a flat list of records { u32 size, u16 type, u16 flags, size bytes }.
 *             0x0070 header · 0x0071 asset start (type name, asset name) · 0x0072 asset end.
 * TSETexture: record 0x1150 = header (u32 format hash, u32 dataSize, u32 0, u16 width, u16 height …),
 *             record 0x1151 = pixel data with mipmaps, in the 3DS's native ETC1 / ETC1A4 tile layout.
 * </pre>
 * All values little-endian. Only the container and textures are understood; other assets are exported raw.
 */
public final class TorusPak {
    private static final int MAX_ENTRIES = 65536;
    private static final int MAX_RAW = 512 << 20;
    static final int REC_HEADER = 0x70, REC_ASSET_START = 0x71, REC_ASSET_END = 0x72;
    static final int REC_TEXTURE_HEADER = 0x1150, REC_TEXTURE_DATA = 0x1151;
    static final int FORMAT_ETC1 = 0x68cd6f34, FORMAT_ETC1A4 = 0x8bfb7121;
    private static final Pattern HUNKFILE = Pattern.compile("hunkfile\\s+\"([^\"]+)\"");

    private TorusPak() {}

    /** One compressed blob in the PAK. */
    public static final class Entry {
        public final int hash, storedSize;
        public final long offset;
        Entry(int hash, long offset, int storedSize) { this.hash = hash; this.offset = offset; this.storedSize = storedSize; }
    }

    /** A hunk (asset bundle) with the manifest that precedes it, if any. */
    public static final class Hunk {
        public final String name;
        public final Entry data;
        /** Manifest text for this hunk, or null. */
        public final String manifest;
        Hunk(String name, Entry data, String manifest) { this.name = name; this.data = data; this.manifest = manifest; }
    }

    public static final class Info {
        public final List<Hunk> hunks = new ArrayList<>();
        /** Manifests that have no hunk of their own (language switches and the like): name → text. */
        public final List<String[]> looseManifests = new ArrayList<>();
        public int entryCount;
        public long storedBytes;
        public String description() {
            return "Torus PAK asset pack · " + hunks.size() + " bundles · " + (storedBytes >> 20) + " MB packed";
        }
    }

    public static final class Record {
        public final int type, flags, offset, size;
        Record(int type, int flags, int offset, int size) { this.type = type; this.flags = flags; this.offset = offset; this.size = size; }
    }

    /** A named asset inside a hunk. {@code start}/{@code end} delimit its records (headers included) in the hunk. */
    public static final class Asset {
        public final String type, name;
        public final List<Record> records = new ArrayList<>();
        public int start, end;
        Asset(String type, String name) { this.type = type; this.name = name; }
    }

    public static final class Texture {
        public final int width, height;
        public final boolean alpha;
        public final int[] argb;
        Texture(int width, int height, boolean alpha, int[] argb) { this.width = width; this.height = height; this.alpha = alpha; this.argb = argb; }
    }

    // ------------------------------------------------------------------ container

    public static boolean isPak(FileChannel ch) throws IOException {
        if (ch.size() < 8) return false;
        byte[] h = read(ch, 0, 8);
        if (h[0] != 'P' || h[1] != 'A' || h[2] != 'K' || h[3] != 0) return false;
        int count = u32(h, 4);
        if (count <= 0 || count > MAX_ENTRIES || 8L + 12L * count > ch.size()) return false;
        byte[] first = read(ch, 8, 12);
        long offset = u32(first, 4) & 0xffffffffL;
        if (offset + 8 > ch.size()) return false;
        byte[] blob = read(ch, offset, 4);
        return blob[0] == '!' && blob[1] == 'Z' && blob[2] == 'L' && blob[3] == 'S';
    }

    /** Reads the entry table and the (tiny) text manifests, pairing each hunk with its name. */
    public static Info parse(FileChannel ch) throws IOException {
        if (!isPak(ch)) throw new IOException("Not a Torus PAK file");
        byte[] head = read(ch, 0, 8);
        int count = u32(head, 4);
        byte[] table = read(ch, 8, 12 * count);
        List<Entry> entries = new ArrayList<>();
        Info info = new Info();
        info.entryCount = count;
        for (int i = 0; i < count; i++) {
            long offset = u32(table, i * 12 + 4) & 0xffffffffL;
            int stored = u32(table, i * 12 + 8);
            if (stored < 8 || offset + stored > ch.size()) throw new IOException("PAK entry " + i + " lies outside the file");
            entries.add(new Entry(u32(table, i * 12), offset, stored));
            info.storedBytes += stored;
        }
        Collections.sort(entries, (a, b) -> Long.compare(a.offset, b.offset));
        String pendingName = null, pendingText = null;
        for (Entry e : entries) {
            byte[] peek = read(ch, e.offset, Math.min(e.storedSize, 8));
            if (peek[0] != '!' || peek[1] != 'Z' || peek[2] != 'L' || peek[3] != 'S')
                throw new IOException("PAK entry at 0x" + Long.toHexString(e.offset) + " is not a !ZLS block");
            int raw = u32(peek, 4);
            boolean text = false;
            byte[] content = null;
            if (raw >= 0 && raw <= 65536) {             // manifests are tiny; hunks start with a 0x70 record
                content = read(ch, e);
                text = looksLikeText(content);
            }
            if (text) {
                if (pendingText != null) info.looseManifests.add(new String[]{pendingName, pendingText});
                pendingText = new String(content, StandardCharsets.ISO_8859_1);
                Matcher m = HUNKFILE.matcher(pendingText);
                pendingName = m.find() ? safeName(m.group(1)) : String.format(Locale.ROOT, "manifest_%08x", e.hash);
            } else {
                boolean named = pendingText != null && HUNKFILE.matcher(pendingText).find();
                String name = named ? pendingName : String.format(Locale.ROOT, "hunk_%08x", e.hash);
                if (pendingText != null && !named) info.looseManifests.add(new String[]{pendingName, pendingText});
                info.hunks.add(new Hunk(name, e, named ? pendingText : null));
                pendingName = null; pendingText = null;
            }
        }
        if (pendingText != null) info.looseManifests.add(new String[]{pendingName, pendingText});
        return info;
    }

    /** Reads and decompresses one entry. */
    public static byte[] read(FileChannel ch, Entry e) throws IOException {
        byte[] stored = read(ch, e.offset, e.storedSize);
        if (stored[0] != '!' || stored[1] != 'Z' || stored[2] != 'L' || stored[3] != 'S')
            throw new IOException("Not a !ZLS block");
        int raw = u32(stored, 4);
        if (raw < 0 || raw > MAX_RAW) throw new IOException("Unreasonable uncompressed size " + (raw & 0xffffffffL));
        byte[] out = new byte[raw];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(stored, 8, stored.length - 8);
            int done = 0;
            while (done < raw) {
                int n = inflater.inflate(out, done, raw - done);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break;
                }
                done += n;
            }
            if (done != raw) throw new IOException("Compressed data ended early (" + done + " of " + raw + " bytes)");
        } catch (DataFormatException ex) {
            throw new IOException("Corrupt zlib data: " + ex.getMessage());
        } finally { inflater.end(); }
        return out;
    }

    // ------------------------------------------------------------------ hunk

    public static boolean isHunk(byte[] d) {
        return d.length >= 8 && u16(d, 4) == REC_HEADER && (u32(d, 0) & 0xffffffffL) + 8 <= d.length;
    }

    /** Lists the named assets in a decompressed hunk. */
    public static List<Asset> assets(byte[] hunk) throws IOException {
        if (!isHunk(hunk)) throw new IOException("Not a hunk file");
        List<Asset> result = new ArrayList<>();
        Asset current = null;
        int pos = 0;
        while (pos + 8 <= hunk.length) {
            long size = u32(hunk, pos) & 0xffffffffL;
            int type = u16(hunk, pos + 4), flags = u16(hunk, pos + 6);
            if (pos + 8 + size > hunk.length) throw new IOException("Hunk record at 0x" + Integer.toHexString(pos) + " overruns the file");
            int body = pos + 8, len = (int) size;
            if (type == REC_ASSET_START) {
                // u16[5] header, then two NUL-terminated strings: asset type, asset name
                int s = body + 10;
                String typeName = cString(hunk, s, body + len);
                String assetName = cString(hunk, Math.min(body + len, s + typeName.length() + 1), body + len);
                current = new Asset(typeName.isEmpty() ? "Unknown" : typeName, assetName.isEmpty() ? "unnamed" : assetName);
                current.start = body + len;
                current.end = current.start;
                result.add(current);
            } else if (type == REC_ASSET_END) {
                if (current != null) current.end = pos;
                current = null;
            } else if (current != null) {
                current.records.add(new Record(type, flags, body, len));
                current.end = body + len;
            }
            pos = body + len;
        }
        return result;
    }

    /** Decodes a TSETexture asset's top mip level, or returns null when the format isn't one we know. */
    public static Texture texture(byte[] hunk, Asset asset) throws IOException {
        Record header = null, data = null;
        for (Record r : asset.records) {
            if (r.type == REC_TEXTURE_HEADER && header == null) header = r;
            else if (r.type == REC_TEXTURE_DATA && data == null) data = r;
        }
        if (header == null || data == null || header.size < 16) return null;
        int format = u32(hunk, header.offset);
        int width = u16(hunk, header.offset + 12), height = u16(hunk, header.offset + 14);
        boolean alpha;
        if (format == FORMAT_ETC1) alpha = false;
        else if (format == FORMAT_ETC1A4) alpha = true;
        else return null;
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return null;
        int tilesX = (width + 7) / 8, tilesY = (height + 7) / 8;
        long need = (long) tilesX * tilesY * 4 * (alpha ? 16 : 8);
        if (need > data.size) throw new IOException("Texture " + asset.name + " is truncated");
        int[] out = new int[width * height];
        int[] px = new int[16];
        int pos = data.offset;
        for (int ty = 0; ty < tilesY; ty++) for (int tx = 0; tx < tilesX; tx++)
            for (int b = 0; b < 4; b++) {
                int bx = tx * 8 + (b & 1) * 4, by = ty * 8 + (b >> 1) * 4;
                long alphaBits = 0;
                if (alpha) { alphaBits = le64(hunk, pos); pos += 8; }
                TextureFormats.decodeEtc1Block(le64(hunk, pos), px);
                pos += 8;
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                    int ox = bx + x, oy = by + y;
                    if (ox >= width || oy >= height) continue;
                    int a = alpha ? (int) ((alphaBits >>> ((x * 4 + y) * 4)) & 15) * 17 : 255;
                    out[oy * width + ox] = (a << 24) | (px[y * 4 + x] & 0xffffff);
                }
            }
        return new Texture(width, height, alpha, out);
    }

    /** File-system-safe version of an asset or hunk name. */
    public static String safeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 120; i++) {
            char c = name.charAt(i);
            sb.append(c < 0x20 || c > 0x7e || "\\/:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String s = sb.toString().trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) return "unnamed";
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean looksLikeText(byte[] d) {
        if (d.length == 0) return false;
        for (byte b : d) { int c = b & 0xff; if (c != 9 && c != 10 && c != 13 && (c < 0x20 || c > 0x7e)) return false; }
        return true;
    }
    private static String cString(byte[] d, int from, int limit) {
        int end = from;
        while (end < limit && d[end] != 0) end++;
        return from >= limit ? "" : new String(d, from, end - from, StandardCharsets.ISO_8859_1);
    }
    private static byte[] read(FileChannel ch, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        long pos = offset;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) throw new IOException("Unexpected end of file");
            pos += n;
        }
        return buf.array();
    }
    private static int u16(byte[] d, int o) { return (d[o] & 255) | (d[o + 1] & 255) << 8; }
    private static int u32(byte[] d, int o) { return u16(d, o) | u16(d, o + 2) << 16; }
    private static long le64(byte[] d, int o) { return (u32(d, o) & 0xffffffffL) | ((long) u32(d, o + 4) << 32); }

    /** Convenience for tests and callers that want one asset's raw records as a standalone blob. */
    static byte[] slice(byte[] hunk, Asset a) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, a.end - a.start));
        out.write(hunk, a.start, Math.max(0, a.end - a.start));
        return out.toByteArray();
    }
}
