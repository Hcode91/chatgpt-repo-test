package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nintendo DS formats beyond plain tiles: NSBTX texture sets (also the TEX0 block inside NSBMD models),
 * NSCR tilemaps, and SDAT sound archives with their SWAR wave archives. Layouts follow GBATEK and the
 * usual Nitro tool documentation.
 */
public final class NdsMedia {
    private NdsMedia() {}

    public static final class Texture {
        public final String name;
        public final int width, height;
        public final int[] argb;
        Texture(String name, int width, int height, int[] argb) { this.name = name; this.width = width; this.height = height; this.argb = argb; }
    }

    /** A file pulled out of an SDAT or SWAR. */
    public static final class Sound {
        public final String path;
        public final byte[] data;
        Sound(String path, byte[] data) { this.path = path; this.data = data; }
    }

    // ------------------------------------------------------------------ NSBTX / TEX0

    public static boolean hasTextures(byte[] d) {
        return d != null && d.length > 0x20 && (CtrArchives.magic(d, 0, "BTX0") || CtrArchives.magic(d, 0, "BMD0")) && tex0(d) >= 0;
    }

    private static int tex0(byte[] d) {
        int blocks = u16(d, 0x0E);
        for (int i = 0; i < blocks && 0x10 + i * 4 + 4 <= d.length; i++) {
            long at = u32(d, 0x10 + i * 4);
            if (at + 0x3C <= d.length && CtrArchives.magic(d, (int) at, "TEX0")) return (int) at;
        }
        return -1;
    }

    /** A Nitro "dictionary": u8 dummy, u8 count, u16 size, an 8+4n-byte lookup section, then u16 elementSize, u16 dataSize, elements, 16-byte names. */
    private static final class Dict {
        int count, elementSize, elements, names;
        String name(byte[] d, int i) {
            int at = names + i * 16, end = at;
            while (end < at + 16 && end < d.length && d[end] != 0) end++;
            return new String(d, at, end - at, StandardCharsets.ISO_8859_1);
        }
    }
    private static Dict dict(byte[] d, int at) throws IOException {
        need(d, at, 12);
        Dict x = new Dict();
        x.count = d[at + 1] & 255;
        int data = at + 4 + 8 + x.count * 4;
        need(d, data, 4);
        x.elementSize = u16(d, data);
        x.elements = data + 4;
        x.names = x.elements + x.count * x.elementSize;
        need(d, x.names, x.count * 16L);
        return x;
    }

    /**
     * TEX0: texture info dictionary @[+0x0E], texture data @[+0x14], 4x4-compressed texture data @[+0x24] with its
     * palette-index data @[+0x28], palette dictionary @[+0x34], palette data @[+0x38]. Texture element:
     * u16 offset/8, u16 params (width = 8 << bits 4-6, height = 8 << bits 7-9, format = bits 10-12,
     * colour 0 transparent = bit 13). Palette element: u16 offset/8.
     */
    public static List<Texture> textures(byte[] d) throws IOException {
        int t = tex0(d);
        if (t < 0) throw new IOException("No TEX0 block");
        int texInfo = t + u16(d, t + 0x0E);
        long texData = t + u32(d, t + 0x14), compData = t + u32(d, t + 0x24), compInfo = t + u32(d, t + 0x28);
        long palInfo = t + u32(d, t + 0x34), palData = t + u32(d, t + 0x38);
        Dict tex = dict(d, texInfo);
        Dict pal = palInfo + 12 <= d.length && palInfo > t ? dict(d, (int) palInfo) : null;
        Map<String, Integer> palByName = new HashMap<>();
        if (pal != null) for (int i = 0; i < pal.count; i++) palByName.put(pal.name(d, i).toLowerCase(Locale.ROOT), i);
        List<Texture> out = new ArrayList<>();
        for (int i = 0; i < tex.count; i++) {
            int e = tex.elements + i * tex.elementSize;
            int offset = u16(d, e) << 3, params = u16(d, e + 2);
            int width = 8 << ((params >> 4) & 7), height = 8 << ((params >> 7) & 7), format = (params >> 10) & 7;
            boolean zeroTransparent = ((params >> 13) & 1) != 0;
            String name = tex.name(d, i);
            if (name.isEmpty()) name = String.format(Locale.ROOT, "texture_%03d", i);
            int palette = -1;
            if (pal != null && pal.count > 0) {
                String key = name.toLowerCase(Locale.ROOT);
                Integer match = palByName.get(key);
                if (match == null) match = palByName.get(key + "_pl");
                if (match == null) for (Map.Entry<String, Integer> p : palByName.entrySet())
                    if (p.getKey().startsWith(key) || key.startsWith(p.getKey().replace("_pl", ""))) { match = p.getValue(); break; }
                if (match == null) match = pal.count == tex.count ? i : 0;
                palette = (int) palData + (u16(d, pal.elements + match * pal.elementSize) << 3);
            }
            try {
                int[] argb = new int[width * height];
                if (format == 5) decode4x4(d, (int) compData + offset, (int) compInfo + offset / 2, (int) palData, palette < 0 ? (int) palData : palette, width, height, argb);
                else decodeTexture(d, (int) texData + offset, palette, format, zeroTransparent, width, height, argb);
                out.add(new Texture(name, width, height, argb));
            } catch (IOException | RuntimeException unsupported) { /* skip this texture */ }
        }
        return out;
    }

    private static int colour(byte[] d, int at) throws IOException {
        need(d, at, 2);
        int c = u16(d, at);
        int r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
        return 0xff000000 | (r * 255 / 31) << 16 | (g * 255 / 31) << 8 | (b * 255 / 31);
    }

    private static void decodeTexture(byte[] d, int at, int palette, int format, boolean zeroTransparent,
                                      int width, int height, int[] out) throws IOException {
        int pixels = width * height;
        if (format == 0) throw new IOException("Empty texture");
        if (format != 7 && palette < 0) throw new IOException("Texture needs a palette");
        switch (format) {
            case 1: case 6: {                                  // A3I5 / A5I3
                need(d, at, pixels);
                for (int i = 0; i < pixels; i++) {
                    int b = d[at + i] & 255;
                    int index = format == 1 ? b & 31 : b & 7;
                    int alpha = format == 1 ? (b >> 5) * 255 / 7 : (b >> 3) * 255 / 31;
                    out[i] = (colour(d, palette + index * 2) & 0xffffff) | alpha << 24;
                }
                break;
            }
            case 2: case 3: case 4: {                          // 2, 4, 8 bits per pixel
                int bits = format == 2 ? 2 : format == 3 ? 4 : 8;
                need(d, at, (long) pixels * bits / 8);
                for (int i = 0; i < pixels; i++) {
                    int index = ((d[at + i * bits / 8] & 255) >> ((i * bits) % 8)) & ((1 << bits) - 1);
                    out[i] = index == 0 && zeroTransparent ? 0 : colour(d, palette + index * 2);
                }
                break;
            }
            case 7: {                                          // direct colour, bit 15 = opaque
                need(d, at, pixels * 2L);
                for (int i = 0; i < pixels; i++) {
                    int c = u16(d, at + i * 2);
                    out[i] = (c & 0x8000) != 0 ? colour(d, at + i * 2) : colour(d, at + i * 2) & 0xffffff;
                }
                break;
            }
            default: throw new IOException("Unknown texture format " + format);
        }
    }

    /** Format 5: 4x4 blocks of 2-bit texels, each block with a u16 that selects a palette offset and blend mode. */
    private static void decode4x4(byte[] d, int data, int info, int unusedBase, int paletteBase, int width, int height, int[] out)
            throws IOException {
        int blocksX = width / 4, blocksY = height / 4;
        need(d, data, blocksX * blocksY * 4L);
        need(d, info, blocksX * blocksY * 2L);
        int[] c = new int[4];
        for (int by = 0; by < blocksY; by++) for (int bx = 0; bx < blocksX; bx++) {
            int n = by * blocksX + bx;
            long texels = u32(d, data + n * 4);
            int word = u16(d, info + n * 2);
            int pal = paletteBase + (word & 0x3fff) * 4, mode = word >> 14;
            c[0] = colour(d, pal); c[1] = colour(d, pal + 2);
            switch (mode) {
                case 0: c[2] = colour(d, pal + 4); c[3] = 0; break;
                case 1: c[2] = blend(c[0], c[1], 1, 1); c[3] = 0; break;
                case 2: c[2] = colour(d, pal + 4); c[3] = colour(d, pal + 6); break;
                default: c[2] = blend(c[0], c[1], 5, 3); c[3] = blend(c[0], c[1], 3, 5); break;
            }
            for (int i = 0; i < 16; i++)
                out[(by * 4 + i / 4) * width + bx * 4 + i % 4] = c[(int) ((texels >> (i * 2)) & 3)];
        }
    }
    private static int blend(int a, int b, int wa, int wb) {
        int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / (wa + wb);
        int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / (wa + wb);
        int bl = ((a & 255) * wa + (b & 255) * wb) / (wa + wb);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }

    // ------------------------------------------------------------------ NSCR tilemaps

    public static boolean isNscr(byte[] d) {
        return d != null && d.length > 0x24 && CtrArchives.magic(d, 0, "RCSN") && CtrArchives.magic(d, 0x10, "NRCS");
    }

    /** Palette banks an NSCR refers to (bits 12-15 of each map entry). */
    public static int[] nscrBanks(byte[] nscr) throws IOException {
        boolean[] used = new boolean[16];
        int entries = nscrEntryCount(nscr);
        for (int i = 0; i < entries; i++) used[u16(nscr, 0x24 + i * 2) >> 12] = true;
        int n = 0;
        for (boolean u : used) if (u) n++;
        int[] out = new int[n];
        n = 0;
        for (int i = 0; i < 16; i++) if (used[i]) out[n++] = i;
        return out;
    }
    private static int nscrEntryCount(byte[] nscr) throws IOException {
        if (!isNscr(nscr)) throw new IOException("Not an NSCR tilemap");
        int width = u16(nscr, 0x18), height = u16(nscr, 0x1A);
        if (width == 0 || height == 0 || width % 8 != 0 || height % 8 != 0 || width > 4096 || height > 4096)
            throw new IOException("Unsupported NSCR size " + width + "x" + height);
        int entries = (width / 8) * (height / 8);
        need(nscr, 0x24, entries * 2L);
        return entries;
    }

    /**
     * Assembles an NSCR screen from tile sheets. NSCR: "NRCS" section @0x10 — u16 width @0x18, u16 height @0x1A,
     * u32 dataSize @0x20, then u16 entries: tile index (bits 0-9), H flip (10), V flip (11), palette bank (12-15).
     * {@code sheets[bank]} is the NCGR decoded with that palette bank (pixels, row-major, {@code sheetWidth} wide);
     * banks that are not supplied fall back to the first available sheet.
     */
    public static Texture renderNscr(byte[] nscr, int[][] sheets, int sheetWidth, int sheetHeight) throws IOException {
        int entries = nscrEntryCount(nscr);
        int width = u16(nscr, 0x18), height = u16(nscr, 0x1A);
        int tilesPerRow = sheetWidth / 8, tileCount = tilesPerRow * (sheetHeight / 8);
        if (tilesPerRow == 0 || tileCount == 0) throw new IOException("Tile sheet is empty");
        int[] fallback = null;
        for (int[] s : sheets) if (s != null) { fallback = s; break; }
        if (fallback == null) throw new IOException("No tile sheet for this tilemap");
        int[] out = new int[width * height];
        int columns = width / 8;
        for (int i = 0; i < entries; i++) {
            int e = u16(nscr, 0x24 + i * 2);
            int tile = e & 0x3ff, bank = e >> 12;
            boolean hflip = (e & 0x400) != 0, vflip = (e & 0x800) != 0;
            if (tile >= tileCount) continue;
            int[] sheet = bank < sheets.length && sheets[bank] != null ? sheets[bank] : fallback;
            int sx = (tile % tilesPerRow) * 8, sy = (tile / tilesPerRow) * 8;
            int dx = (i % columns) * 8, dy = (i / columns) * 8;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
                out[(dy + y) * width + dx + x] = sheet[(sy + (vflip ? 7 - y : y)) * sheetWidth + sx + (hflip ? 7 - x : x)];
        }
        return new Texture("", width, height, out);
    }

    // ------------------------------------------------------------------ SDAT / SWAR

    public static boolean isSdat(byte[] d) { return d != null && d.length > 0x40 && CtrArchives.magic(d, 0, "SDAT"); }
    public static boolean isSwar(byte[] d) { return d != null && d.length > 0x3C && CtrArchives.magic(d, 0, "SWAR") && CtrArchives.magic(d, 0x10, "DATA"); }

    /** SWAR: "DATA" @0x10, u32 size, 32 reserved bytes, u32 count @0x38, u32 offsets[count] to SWAV sample bodies. */
    public static List<Sound> swarWaves(byte[] d) throws IOException {
        if (!isSwar(d)) throw new IOException("Not a SWAR wave archive");
        long count = u32(d, 0x38);
        if (count > 65536) throw new IOException("Unreasonable SWAR sample count");
        need(d, 0x3C, count * 4);
        List<Sound> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long start = u32(d, 0x3C + i * 4), end = i + 1 < count ? u32(d, 0x3C + (i + 1) * 4) : d.length;
            if (start + 12 > d.length || end > d.length || end <= start) continue;
            try { out.add(new Sound(String.format(Locale.ROOT, "%04d.wav", i), GameAudio.swavSample(d, (int) start, (int) (end - start)))); }
            catch (IOException broken) { /* skip this sample */ }
        }
        return out;
    }

    private static final String[] SDAT_FOLDER = {"SEQ", "SEQARC", "BANK", "WAVEARC", "PLAYER", "GROUP", "PLAYER2", "STRM"};
    private static final String[] SDAT_EXT = {".sseq", ".ssar", ".sbnk", ".swar", "", "", "", ".strm"};

    /**
     * SDAT: header — SYMB offset @0x10, INFO offset @0x18, FAT offset @0x20. FAT: u32 count @+8, entries
     * { u32 offset, u32 size, 8 reserved } @+0xC. INFO: eight record offsets @+8 (sequences, sequence archives,
     * banks, wave archives, players, groups, stream players, streams); a record is u32 count + u32 entry offsets,
     * and each entry starts with its u16 file id. SYMB mirrors the records with name offsets. Returns every file
     * in the FAT, named from SYMB where possible.
     */
    public static List<Sound> sdatFiles(byte[] d) throws IOException {
        if (!isSdat(d)) throw new IOException("Not an SDAT sound archive");
        long symb = u32(d, 0x10), info = u32(d, 0x18), fat = u32(d, 0x20);
        need(d, fat, 12);
        long count = u32(d, (int) fat + 8);
        if (count > 65536) throw new IOException("Unreasonable SDAT file count");
        need(d, fat + 12, count * 16);
        String[] names = new String[(int) count];
        if (info > 0 && info + 0x28 <= d.length && CtrArchives.magic(d, (int) info, "INFO")) {
            for (int type : new int[]{0, 1, 2, 3, 7}) {
                try {
                    long recordOffset = u32(d, (int) info + 8 + type * 4);
                    if (recordOffset < 0x28) continue;                 // record not present
                    long record = info + recordOffset;
                    long entries = u32(d, (int) record);
                    for (int i = 0; i < entries && i < 65536; i++) {
                        long entryOffset = u32(d, (int) record + 4 + i * 4);
                        if (entryOffset == 0) continue;
                        int fileId = u16(d, (int) (info + entryOffset));
                        if (fileId >= count || names[fileId] != null) continue;
                        String symbol = sdatSymbol(d, symb, type, i);
                        names[fileId] = SDAT_FOLDER[type] + "/" + (symbol != null ? symbol : String.format(Locale.ROOT, "%04d", i)) + SDAT_EXT[type];
                    }
                } catch (RuntimeException malformedRecord) { /* fall back to numbered names for this type */ }
            }
        }
        List<Sound> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long offset = u32(d, (int) fat + 12 + i * 16), size = u32(d, (int) fat + 16 + i * 16);
            if (size == 0 || offset + size > d.length) continue;
            String name = names[i];
            if (name == null) {
                String magic = new String(d, (int) offset, (int) Math.min(4, size), StandardCharsets.ISO_8859_1);
                String ext = magic.equals("SSEQ") ? ".sseq" : magic.equals("SSAR") ? ".ssar" : magic.equals("SBNK") ? ".sbnk"
                        : magic.equals("SWAR") ? ".swar" : magic.equals("STRM") ? ".strm" : ".bin";
                name = String.format(Locale.ROOT, "FILES/%04d%s", i, ext);
            }
            byte[] file = new byte[(int) size];
            System.arraycopy(d, (int) offset, file, 0, (int) size);
            out.add(new Sound(name, file));
        }
        return out;
    }

    private static String sdatSymbol(byte[] d, long symb, int type, int index) {
        try {
            if (symb <= 0 || !CtrArchives.magic(d, (int) symb, "SYMB")) return null;
            long recordOffset = u32(d, (int) symb + 8 + type * 4);
            if (recordOffset < 0x28) return null;
            long record = symb + recordOffset;
            long entries = u32(d, (int) record);
            if (index >= entries) return null;
            long nameOffset = u32(d, (int) record + 4 + index * (type == 1 ? 8 : 4));
            if (nameOffset == 0) return null;
            int at = (int) (symb + nameOffset), end = at;
            while (end < d.length && d[end] != 0 && end - at < 64) end++;
            String name = new String(d, at, end - at, StandardCharsets.ISO_8859_1);
            return name.isEmpty() || !name.matches("[\\x21-\\x7e]+") ? null : name;
        } catch (RuntimeException malformed) { return null; }
    }

    // ------------------------------------------------------------------ helpers

    private static void need(byte[] d, long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > d.length) throw new IOException("Data lies outside the file");
    }
    private static int u16(byte[] d, int o) { return (d[o] & 255) | (d[o + 1] & 255) << 8; }
    private static long u32(byte[] d, int o) { return u16(d, o) | ((long) u16(d, o + 2) << 16); }
}
