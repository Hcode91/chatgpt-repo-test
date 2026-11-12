package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decoders for the common Nintendo 3DS texture containers: CTPK, BCLIM, BFLIM, and the textures inside
 * CGFX (.bcres/.bcmdl) and BCH files. All of them hold standard PICA200 pixel data, decoded by
 * {@link BtexCodec#decodeTiled}. Only the largest mip level of each texture is returned.
 */
public final class CtrTextures {
    private static final int MAX_TEXTURES = 4096;
    /** BCLIM/BFLIM format number → PICA200 format number (the numbering CTPK, CGFX and BCH use). */
    private static final int[] LIM_TO_PICA = {7, 8, 9, 5, 6, 3, 1, 2, 4, 0, 12, 13, 10, 11, -1, -1, -1, -1, 10, 11};

    private CtrTextures() {}

    public static final class Texture {
        public final String name;
        public final int width, height;
        public final int[] argb;
        Texture(String name, int width, int height, int[] argb) { this.name = name; this.width = width; this.height = height; this.argb = argb; }
    }

    public enum Kind { CTPK, BCLIM, BFLIM, CGFX, BCH, NONE }

    public static Kind kind(byte[] d) {
        if (d == null || d.length < 0x28) return Kind.NONE;
        if (CtrArchives.magic(d, 0, "CTPK")) return Kind.CTPK;
        if (CtrArchives.magic(d, 0, "CGFX")) return Kind.CGFX;
        if (CtrArchives.magic(d, 0, "BCH\0")) return Kind.BCH;
        int footer = d.length - 0x28;
        if (CtrArchives.magic(d, footer, "CLIM") && CtrArchives.magic(d, footer + 0x14, "imag")) return Kind.BCLIM;
        if (CtrArchives.magic(d, footer, "FLIM") && CtrArchives.magic(d, footer + 0x14, "imag")) return Kind.BFLIM;
        return Kind.NONE;
    }

    /** Decodes every texture in the file. Textures in formats we can't read are skipped. */
    public static List<Texture> decode(byte[] d) throws IOException {
        switch (kind(d)) {
            case CTPK: return ctpk(d);
            case BCLIM: return lim(d, false);
            case BFLIM: return lim(d, true);
            case CGFX: return cgfx(d);
            case BCH: return bch(d);
            default: throw new IOException("Not a supported 3DS texture file");
        }
    }

    // ------------------------------------------------------------------ shared pixel decoding

    private static int pow2(int v) { int p = 8; while (p < v) p <<= 1; return p; }
    private static int round8(int v) { return Math.max(8, (v + 7) & ~7); }

    /**
     * Decodes a texture whose visible size is width × height. The stored image is padded (normally to powers
     * of two). {@code orientation} is the BFLIM/BCLIM swizzle byte: 0 = as is, 4 = rotated 90°, 8 = transposed.
     */
    static int[] decodePica(byte[] d, int offset, int available, int width, int height, int format, int orientation)
            throws IOException {
        int bpp = BtexCodec.picaBitsPerPixel(format);
        if (bpp < 0) throw new IOException("Unsupported texture format " + format);
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) throw new IOException("Unreasonable texture size");
        boolean swapped = orientation == 4 || orientation == 8;
        int padW = pow2(width), padH = pow2(height);
        if ((long) padW * padH * bpp / 8 > available) { padW = round8(width); padH = round8(height); }
        if ((long) padW * padH * bpp / 8 > available) throw new IOException("Texture data is truncated");
        int storedW = swapped ? padH : padW, storedH = swapped ? padW : padH;
        int[] stored = BtexCodec.decodeTiled(d, offset, storedW, storedH, format);
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int sx, sy;
            if (orientation == 8) { sx = y; sy = x; }
            else if (orientation == 4) { sx = padH - 1 - y; sy = x; }
            else { sx = x; sy = y; }
            out[y * width + x] = stored[sy * storedW + sx];
        }
        return out;
    }

    // ------------------------------------------------------------------ CTPK

    /**
     * CTPK: "CTPK", u16 version, u16 count, u32 textureDataOffset, u32 textureDataSize, u32 hashOffset, u32 infoOffset.
     * Entries at 0x20, 0x20 bytes each: u32 nameOffset, u32 dataSize, u32 dataOffset (in the texture section),
     * u32 format, u16 width, u16 height, u8 mipLevels, u8 type, …
     */
    private static List<Texture> ctpk(byte[] d) throws IOException {
        int count = u16(d, 6);
        long section = u32(d, 8);
        if (count > MAX_TEXTURES || 0x20 + count * 0x20L > d.length) throw new IOException("Invalid CTPK header");
        List<Texture> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int e = 0x20 + i * 0x20;
            long nameOffset = u32(d, e), size = u32(d, e + 4), offset = section + u32(d, e + 8);
            int format = (int) u32(d, e + 12), width = u16(d, e + 16), height = u16(d, e + 18);
            if (offset >= d.length) continue;
            int available = (int) Math.min(size > 0 ? size : Integer.MAX_VALUE, d.length - offset);
            String name = nameOffset < d.length ? leaf(cString(d, (int) nameOffset)) : "";
            if (name.isEmpty()) name = String.format(Locale.ROOT, "texture_%03d", i);
            try { out.add(new Texture(name, width, height, decodePica(d, (int) offset, available, width, height, format, 0))); }
            catch (IOException unsupported) { /* skip this texture, keep the rest */ }
        }
        return out;
    }

    // ------------------------------------------------------------------ BCLIM / BFLIM

    /**
     * Both keep the pixel data first and a 0x28-byte footer: "CLIM"/"FLIM", u16 BOM, u16 headerSize, u32 version,
     * u32 fileSize, u16 blocks, u16 pad, then "imag", u32 size, and
     * BCLIM: u16 width, u16 height, u8 format, u8 swizzle, u16 alignment, u32 dataSize;
     * BFLIM: u16 width, u16 height, u16 alignment, u8 format, u8 swizzle, u32 dataSize.
     */
    private static List<Texture> lim(byte[] d, boolean flim) throws IOException {
        int footer = d.length - 0x28;
        if ((d[footer + 4] & 255) != 0xFF || (d[footer + 5] & 255) != 0xFE)
            throw new IOException("Big-endian (Wii U) layout images are not supported");
        int image = footer + 0x1C;
        int width = u16(d, image), height = u16(d, image + 2);
        int format = d[image + (flim ? 6 : 4)] & 255, swizzle = d[image + (flim ? 7 : 5)] & 255;
        if (format >= LIM_TO_PICA.length || LIM_TO_PICA[format] < 0) throw new IOException("Unsupported image format " + format);
        int orientation = (swizzle & 0x0C) == 4 ? 4 : (swizzle & 0x0C) == 8 ? 8 : 0;
        List<Texture> out = new ArrayList<>();
        out.add(new Texture("", width, height, decodePica(d, 0, footer, width, height, LIM_TO_PICA[format], orientation)));
        return out;
    }

    // ------------------------------------------------------------------ CGFX

    /**
     * CGFX textures are "TXOB" objects. Relative to the magic: -4 type flags, +8 name (self-relative),
     * +0x14 height, +0x18 width, +0x24 mip levels, +0x30 PICA format, +0x38 height, +0x3C width,
     * +0x40 data size, +0x44 data offset (self-relative). Found by scanning, so textures embedded in
     * model files are picked up too.
     */
    private static List<Texture> cgfx(byte[] d) {
        List<Texture> out = new ArrayList<>();
        for (int m = 4; m + 0x48 <= d.length && out.size() < MAX_TEXTURES; m += 4) {
            if (d[m] != 'T' || d[m + 1] != 'X' || d[m + 2] != 'O' || d[m + 3] != 'B') continue;
            long height = u32(d, m + 0x14), width = u32(d, m + 0x18), format = u32(d, m + 0x30);
            if (u32(d, m + 0x38) != height || u32(d, m + 0x3C) != width) continue;       // not a plain image texture
            long size = u32(d, m + 0x40), offset = m + 0x44 + u32(d, m + 0x44);
            if (width == 0 || height == 0 || width > 4096 || height > 4096 || offset >= d.length) continue;
            long nameAt = m + 8 + u32(d, m + 8);
            String name = nameAt > 0 && nameAt < d.length ? leaf(cString(d, (int) nameAt)) : "";
            if (name.isEmpty()) name = String.format(Locale.ROOT, "texture_%03d", out.size());
            int available = (int) Math.min(size > 0 ? size : Integer.MAX_VALUE, d.length - offset);
            try { out.add(new Texture(name, (int) width, (int) height, decodePica(d, (int) offset, available, (int) width, (int) height, (int) format, 0))); }
            catch (IOException unsupported) { /* skip */ }
        }
        return out;
    }

    // ------------------------------------------------------------------ BCH

    /**
     * BCH: "BCH\0", u8 backwardCompat, u8 forwardCompat, u16 version, u32 mainHeaderOffset, u32 nameTableOffset,
     * u32 gpuCommandsOffset, u32 dataOffset, [u32 dataExtOffset when backwardCompat > 0x20], u32 relocOffset,
     * u32 mainHeaderSize, u32 nameTableSize, u32 gpuCommandsSize, u32 dataSize, …
     * Each texture is described by a run of GPU commands (u32 parameter, u32 header; register id in the low 16 bits)
     * ending with register 0x23D. Register 0x82 = size (width<<16 | height), 0x85 = data address relative to
     * dataOffset, 0x8E = PICA format.
     */
    private static List<Texture> bch(byte[] d) throws IOException {
        int backward = d[4] & 255;
        int p = 8;
        long mainHeader = u32(d, p), nameTable = u32(d, p + 4), commands = u32(d, p + 8), data = u32(d, p + 12);
        p += 16;
        if (backward > 0x20) p += 4;
        p += 4;                                   // relocation table offset
        p += 8;                                   // main header size, name table size
        long commandsSize = u32(d, p), dataSize = u32(d, p + 4);
        if (dataSize == 0) return new ArrayList<>();
        if (commands + commandsSize > d.length || data >= d.length) throw new IOException("Invalid BCH section table");
        List<String> names = bchTextureNames(d, mainHeader, nameTable);
        List<Texture> out = new ArrayList<>();
        long size = 0, address = -1, format = -1;
        for (long c = commands; c + 8 <= commands + commandsSize && out.size() < MAX_TEXTURES; c += 8) {
            long param = u32(d, (int) c);
            int id = u16(d, (int) c + 4);
            if (id == 0x82) size = param;
            else if (id == 0x85) address = param;
            else if (id == 0x8E) format = param;
            else if (id == 0x23D) {
                int width = (int) (size >>> 16), height = (int) (size & 0xffff);
                if (width > 0 && height > 0 && address >= 0 && format >= 0 && data + address < d.length) {
                    int index = out.size();
                    String name = index < names.size() ? names.get(index) : String.format(Locale.ROOT, "texture_%03d", index);
                    int offset = (int) (data + address);
                    try { out.add(new Texture(name, width, height, decodePica(d, offset, d.length - offset, width, height, (int) format, 0))); }
                    catch (IOException unsupported) { /* skip */ }
                }
                size = 0; address = -1; format = -1;
            }
        }
        return out;
    }

    /** Best effort: texture names from the main header's texture table. Returns an empty list when the layout differs. */
    private static List<String> bchTextureNames(byte[] d, long mainHeader, long nameTable) {
        List<String> names = new ArrayList<>();
        try {
            long table = mainHeader + u32(d, (int) mainHeader + 9 * 4), count = u32(d, (int) mainHeader + 10 * 4);
            if (count > MAX_TEXTURES) return names;
            for (int i = 0; i < count; i++) {
                long entry = mainHeader + u32(d, (int) table + i * 4);
                long nameAt = nameTable + u32(d, (int) entry + 0x1C);
                String name = nameAt < d.length ? leaf(cString(d, (int) nameAt)) : "";
                if (name.isEmpty() || !name.matches("[\\x20-\\x7e]+")) return new ArrayList<>();
                names.add(name);
            }
        } catch (RuntimeException layoutDiffers) { return new ArrayList<>(); }
        return names;
    }

    // ------------------------------------------------------------------ helpers

    private static String leaf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        for (String ext : new String[]{".tga", ".png", ".bmp", ".tif", ".dds"})
            if (name.toLowerCase(Locale.ROOT).endsWith(ext)) return name.substring(0, name.length() - ext.length());
        return name;
    }
    private static String cString(byte[] d, int from) {
        int end = from;
        while (end < d.length && d[end] != 0 && end - from < 256) end++;
        return new String(d, from, end - from, StandardCharsets.ISO_8859_1);
    }
    private static int u16(byte[] d, int o) { return (d[o] & 255) | (d[o + 1] & 255) << 8; }
    private static long u32(byte[] d, int o) { return u16(d, o) | ((long) u16(d, o + 2) << 16); }
}
