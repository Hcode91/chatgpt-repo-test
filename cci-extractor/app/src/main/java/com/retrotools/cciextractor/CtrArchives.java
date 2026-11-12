package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only parsers for the common Nintendo 3DS archive containers and Yaz0 compression.
 * Every parser works on a whole file in memory and returns the files it contains as byte ranges.
 * Layouts follow the public documentation (3dbrew) and open-source tools (Kuriimu).
 */
public final class CtrArchives {
    private static final int MAX_ITEMS = 65536;
    static final int MAX_OUTPUT = 256 << 20;

    private CtrArchives() {}

    /** One file inside an archive: a path and a byte range of the archive itself. */
    public static final class Item {
        public final String path;
        public final int offset, length;
        Item(String path, int offset, int length) { this.path = path; this.offset = offset; this.length = length; }
        public byte[] bytes(byte[] archive) {
            byte[] out = new byte[length];
            System.arraycopy(archive, offset, out, 0, length);
            return out;
        }
    }

    public enum Kind { SARC, DARC, ZAR, GARC, NONE }

    public static Kind kind(byte[] d) {
        if (d == null || d.length < 0x1C) return Kind.NONE;
        if (magic(d, 0, "SARC")) return Kind.SARC;
        if (magic(d, 0, "darc")) return Kind.DARC;
        if (magic(d, 0, "ZAR\u0001")) return Kind.ZAR;
        if (magic(d, 0, "CRAG")) return Kind.GARC;
        return Kind.NONE;
    }

    /** Lists the files in a supported archive, or throws when the archive is malformed. */
    public static List<Item> list(byte[] d) throws IOException {
        switch (kind(d)) {
            case SARC: return sarc(d);
            case DARC: return darc(d);
            case ZAR: return zar(d);
            case GARC: return garc(d);
            default: throw new IOException("Not a supported 3DS archive");
        }
    }

    // ------------------------------------------------------------------ Yaz0

    public static boolean isYaz0(byte[] d) { return d != null && d.length > 16 && magic(d, 0, "Yaz0"); }

    /** Yaz0: "Yaz0", u32 BE uncompressed size, 8 reserved bytes, then LZ groups (flag bits, MSB first). */
    public static byte[] yaz0(byte[] d) throws IOException {
        if (!isYaz0(d)) throw new IOException("Not Yaz0 data");
        long size = ((d[4] & 255L) << 24) | ((d[5] & 255) << 16) | ((d[6] & 255) << 8) | (d[7] & 255);
        if (size > MAX_OUTPUT) throw new IOException("Yaz0 output too large");
        byte[] out = new byte[(int) size];
        int in = 16, pos = 0;
        while (pos < out.length) {
            if (in >= d.length) throw new IOException("Yaz0 data ended early");
            int flags = d[in++] & 255;
            for (int bit = 0; bit < 8 && pos < out.length; bit++, flags <<= 1) {
                if ((flags & 0x80) != 0) {
                    if (in >= d.length) throw new IOException("Yaz0 data ended early");
                    out[pos++] = d[in++];
                } else {
                    if (in + 1 >= d.length) throw new IOException("Yaz0 data ended early");
                    int b1 = d[in++] & 255, b2 = d[in++] & 255;
                    int distance = ((b1 & 0x0f) << 8 | b2) + 1;
                    int count = b1 >> 4;
                    if (count == 0) {
                        if (in >= d.length) throw new IOException("Yaz0 data ended early");
                        count = (d[in++] & 255) + 0x12;
                    } else count += 2;
                    if (distance > pos) throw new IOException("Yaz0 back-reference before start of data");
                    for (int i = 0; i < count && pos < out.length; i++, pos++) out[pos] = out[pos - distance];
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ SARC

    /**
     * SARC: "SARC", u16 headerSize, u16 BOM, u32 fileSize, u32 dataOffset, u32 unknown.
     * SFAT: "SFAT", u16 headerSize, u16 nodeCount, u32 hashKey; nodes { u32 nameHash, u32 nameAttr, u32 start, u32 end }.
     * SFNT: "SFNT", u16 headerSize, u16 reserved; NUL-terminated names, 4-byte aligned.
     */
    private static List<Item> sarc(byte[] d) throws IOException {
        boolean be = (d[6] & 255) == 0xFE && (d[7] & 255) == 0xFF;
        int headerSize = u16(d, 4, be);
        long dataOffset = u32(d, 0x0C, be);
        int sfat = headerSize;
        need(d, sfat, 12);
        if (!magic(d, sfat, "SFAT")) throw new IOException("SARC has no SFAT table");
        int nodeCount = u16(d, sfat + 6, be);
        int nodes = sfat + u16(d, sfat + 4, be);
        int sfnt = nodes + nodeCount * 16;
        need(d, sfnt, 8);
        if (!magic(d, sfnt, "SFNT")) throw new IOException("SARC has no SFNT table");
        int names = sfnt + u16(d, sfnt + 4, be);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            int n = nodes + i * 16;
            long hash = u32(d, n, be), attr = u32(d, n + 4, be), start = u32(d, n + 8, be), end = u32(d, n + 12, be);
            long off = dataOffset + start, len = end - start;
            if (len < 0 || off + len > d.length) throw new IOException("SARC entry " + i + " lies outside the file");
            String name = null;
            if ((attr & 0x01000000L) != 0) {
                int at = names + (int) (attr & 0xffff) * 4;
                if (at < d.length) name = cString(d, at, d.length);
            }
            if (name == null || name.isEmpty())
                name = String.format(Locale.ROOT, "0x%08X%s", hash, sniffExtension(d, (int) off, (int) len));
            out.add(new Item(name, (int) off, (int) len));
        }
        return out;
    }

    // ------------------------------------------------------------------ darc

    /**
     * darc: "darc", u16 BOM, u16 headerSize, u32 version, u32 fileSize, u32 tableOffset, u32 tableLength, u32 dataOffset.
     * Table entries { u32 nameOffset | folderFlag<<24, u32 offset, u32 size }; the first is the root folder whose size
     * is the entry count. A folder's size is the index one past its last entry. Names are UTF-16LE after the entries;
     * file offsets are absolute.
     */
    private static List<Item> darc(byte[] d) throws IOException {
        long table = u32(d, 0x10, false);
        need(d, table, 12);
        int t = (int) table;
        long count = u32(d, t + 8, false);
        if (count <= 0 || count > MAX_ITEMS) throw new IOException("Unreasonable darc entry count");
        need(d, t, count * 12);
        int names = t + (int) count * 12;
        String[] prefix = new String[(int) count];
        java.util.Arrays.fill(prefix, "");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int e = t + i * 12;
            long nameField = u32(d, e, false), offset = u32(d, e + 4, false), size = u32(d, e + 8, false);
            boolean folder = (nameField >>> 24) == 1;
            String name = utf16(d, names + (int) (nameField & 0xffffff));
            if (folder) {
                if (name.isEmpty() || name.equals(".")) continue;
                for (int j = i + 1; j < size && j < count; j++) prefix[j] += name + "/";
            } else {
                if (offset + size > d.length) throw new IOException("darc entry " + i + " lies outside the file");
                out.add(new Item(prefix[i] + name, (int) offset, (int) size));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ZAR

    /**
     * ZAR (Ocarina of Time 3D / Majora's Mask 3D): "ZAR\1", u32 fileSize, u16 typeCount, u16 fileCount,
     * u32 typesOffset, u32 fileInfoOffset, u32 offsetListOffset, char[8]. File info entries
     * { u32 size, u32 nameOffset }; the offset list holds one absolute u32 offset per file, in the same order.
     */
    private static List<Item> zar(byte[] d) throws IOException {
        int fileCount = u16(d, 0x0A, false);
        long info = u32(d, 0x10, false), offsets = u32(d, 0x14, false);
        need(d, info, fileCount * 8L);
        need(d, offsets, fileCount * 4L);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            long size = u32(d, (int) info + i * 8, false), nameOffset = u32(d, (int) info + i * 8 + 4, false);
            long offset = u32(d, (int) offsets + i * 4, false);
            if (offset + size > d.length || nameOffset >= d.length) throw new IOException("ZAR entry " + i + " lies outside the file");
            String name = cString(d, (int) nameOffset, d.length);
            if (name.isEmpty()) name = String.format(Locale.ROOT, "%04d.bin", i);
            out.add(new Item(name, (int) offset, (int) size));
        }
        return out;
    }

    // ------------------------------------------------------------------ GARC

    /**
     * GARC (Pokémon): "CRAG", u32 headerSize, u16 BOM, u16 version, u32 chunkCount, u32 dataOffset, u32 fileSize, …
     * "OTAF": u32 size, u16 count, u16 padding, u32 offsets[count] into the BTAF entries.
     * "BTAF": u32 size, u32 count; entries { u32 bits, then per set bit: u32 start, u32 end, u32 length }.
     * Files have no names; they are numbered and given an extension from their contents.
     */
    private static List<Item> garc(byte[] d) throws IOException {
        long headerSize = u32(d, 4, false), dataOffset = u32(d, 0x10, false);
        need(d, headerSize, 12);
        int fato = (int) headerSize;
        if (!magic(d, fato, "OTAF")) throw new IOException("GARC has no FATO table");
        long fatoSize = u32(d, fato + 4, false);
        int count = u16(d, fato + 8, false);
        need(d, fato + 12L, count * 4L);
        long fatbAt = fato + fatoSize;
        need(d, fatbAt, 12);
        int fatb = (int) fatbAt;
        if (!magic(d, fatb, "BTAF")) throw new IOException("GARC has no FATB table");
        int entries = fatb + 12;
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long e = entries + u32(d, fato + 12 + i * 4, false);
            need(d, e, 4);
            long bits = u32(d, (int) e, false);
            int at = (int) e + 4;
            int subFiles = Long.bitCount(bits);
            for (int bit = 0; bit < 32; bit++) {
                if ((bits & (1L << bit)) == 0) continue;
                need(d, at, 12);
                long start = u32(d, at, false), end = u32(d, at + 4, false);
                at += 12;
                long off = dataOffset + start, len = end - start;
                if (len < 0 || off + len > d.length) throw new IOException("GARC entry " + i + " lies outside the file");
                String name = subFiles == 1 ? String.format(Locale.ROOT, "%05d", i) : String.format(Locale.ROOT, "%05d/%02d", i, bit);
                out.add(new Item(name + sniffExtension(d, (int) off, (int) len), (int) off, (int) len));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** A file extension guessed from well-known magic numbers, for archives that store no names. */
    public static String sniffExtension(byte[] d, int off, int len) {
        if (len >= 4) {
            String m = new String(d, off, 4, StandardCharsets.ISO_8859_1);
            switch (m) {
                case "SARC": return ".sarc";
                case "darc": return ".darc";
                case "CRAG": return ".garc";
                case "CTPK": return ".ctpk";
                case "CGFX": return ".bcres";
                case "BCH\0": return ".bch";
                case "CWAV": return ".bcwav";
                case "CSTM": return ".bcstm";
                case "CFNT": return ".bcfnt";
                case "FFNT": return ".bffnt";
                case "MsgS": return ".msbt";
                case "Yaz0": return ".szs";
                case "SMDH": return ".icn";
                case "NARC": return ".narc";
                default: break;
            }
            if (m.startsWith("ZAR")) return ".zar";
            if (len >= 0x28) {
                String tail = new String(d, off + len - 0x28, 4, StandardCharsets.ISO_8859_1);
                if (tail.equals("CLIM")) return ".bclim";
                if (tail.equals("FLIM")) return ".bflim";
            }
            int first = d[off] & 255;
            if (first == 0x11 || first == 0x10) return ".lz";
        }
        return ".bin";
    }

    private static void need(byte[] d, long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > d.length) throw new IOException("Archive table lies outside the file");
    }
    static boolean magic(byte[] d, int o, String m) {
        if (o < 0 || o + m.length() > d.length) return false;
        for (int i = 0; i < m.length(); i++) if ((d[o + i] & 255) != m.charAt(i)) return false;
        return true;
    }
    private static int u16(byte[] d, int o, boolean be) {
        return be ? (d[o] & 255) << 8 | (d[o + 1] & 255) : (d[o] & 255) | (d[o + 1] & 255) << 8;
    }
    private static long u32(byte[] d, int o, boolean be) {
        return be ? ((long) u16(d, o, true) << 16) | u16(d, o + 2, true) : u16(d, o, false) | ((long) u16(d, o + 2, false) << 16);
    }
    private static String cString(byte[] d, int from, int limit) {
        int end = from;
        while (end < limit && d[end] != 0 && end - from < 1024) end++;
        return new String(d, from, end - from, StandardCharsets.UTF_8);
    }
    private static String utf16(byte[] d, int from) {
        StringBuilder sb = new StringBuilder();
        for (int p = from; p + 1 < d.length && sb.length() < 512; p += 2) {
            int c = (d[p] & 255) | (d[p + 1] & 255) << 8;
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }
}
