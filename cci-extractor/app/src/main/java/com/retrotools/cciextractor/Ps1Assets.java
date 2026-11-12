package com.retrotools.cciextractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PlayStation asset formats: TIM images (to ARGB pixels, one image per palette) and VAG sounds
 * (PS-ADPCM to 16-bit PCM). Also finds TIM images embedded in other files, since many games pack them
 * into their own archives.
 */
public final class Ps1Assets {
    /** Further palettes of a multi-palette TIM exported as extra images, at most this many in total. */
    public static final int MAX_CLUT_IMAGES = 16;
    private static final int MAX_SCAN_HITS = 2000;

    private Ps1Assets() {}

    public static final class Image {
        public final int width, height, clut;
        public final int[] argb;
        Image(int width, int height, int clut, int[] argb) { this.width = width; this.height = height; this.clut = clut; this.argb = argb; }
    }

    /** A TIM found inside another file. */
    public static final class Found {
        public final int offset, length;
        Found(int offset, int length) { this.offset = offset; this.length = length; }
    }

    // ------------------------------------------------------------------ TIM

    /**
     * Returns the total length of a valid TIM starting at {@code at}, or -1. Strict, so it can be used to scan:
     * u32 0x10, u32 flags (bits 0-2 depth 0=4bpp 1=8bpp 2=16bpp 3=24bpp, bit 3 = has palette); palette block
     * {u32 length, u16 x, u16 y, u16 colours, u16 palettes, colour data}; image block {u32 length, u16 x, u16 y,
     * u16 width in 16-bit units, u16 height, pixel data}. Block lengths include their 12-byte headers.
     */
    public static int timLength(byte[] d, int at) {
        if (at < 0 || at + 20 > d.length || u32(d, at) != 0x10) return -1;
        long flags = u32(d, at + 4);
        int depth = (int) (flags & 7);
        boolean clut = (flags & 8) != 0;
        if ((flags & ~0xFL) != 0 || depth > 3 || (depth < 2 && !clut)) return -1;
        int pos = at + 8;
        if (clut) {
            if (pos + 12 > d.length) return -1;
            long len = u32(d, pos);
            int colours = u16(d, pos + 8), count = u16(d, pos + 10);
            if (colours == 0 || count == 0 || colours > 4096 || count > 512) return -1;
            if (len != 12L + colours * count * 2L) return -1;
            if (depth == 0 && colours < 16 && count == 1) return -1;   // a 4bpp image needs 16 colours
            pos += (int) len;
        }
        if (pos + 12 > d.length) return -1;
        long len = u32(d, pos);
        int w = u16(d, pos + 8), h = u16(d, pos + 10);
        if (w == 0 || h == 0 || w > 1024 || h > 512) return -1;
        if (len != 12L + w * h * 2L || pos + len > d.length) return -1;
        return (int) (pos + len - at);
    }

    public static boolean isTim(byte[] d) { return d != null && timLength(d, 0) > 0; }

    /** Decodes a TIM: one image for each palette (at most {@link #MAX_CLUT_IMAGES}), or one for 16/24-bit. */
    public static List<Image> decodeTim(byte[] d, int at) throws IOException {
        int length = timLength(d, at);
        if (length < 0) throw new IOException("Not a valid TIM image");
        long flags = u32(d, at + 4);
        int depth = (int) (flags & 7);
        int pos = at + 8, clutAt = -1, colours = 0, palettes = 1;
        if ((flags & 8) != 0) {
            colours = u16(d, pos + 8); palettes = u16(d, pos + 10);
            clutAt = pos + 12;
            pos += (int) u32(d, pos);
        }
        int units = u16(d, pos + 8), height = u16(d, pos + 10), data = pos + 12;
        int width = depth == 0 ? units * 4 : depth == 1 ? units * 2 : depth == 2 ? units : units * 2 / 3;
        if (width == 0) throw new IOException("Empty TIM image");
        int stride = units * 2;
        List<Image> out = new ArrayList<>();
        int images = depth < 2 ? Math.min(palettes, MAX_CLUT_IMAGES) : 1;
        for (int p = 0; p < images; p++) {
            int[] argb = new int[width * height];
            int palette = clutAt + p * colours * 2;
            for (int y = 0; y < height; y++) {
                int row = data + y * stride;
                for (int x = 0; x < width; x++) {
                    int c;
                    switch (depth) {
                        case 0: {
                            int index = ((d[row + x / 2] & 255) >> ((x & 1) * 4)) & 15;
                            c = index < colours ? colour(u16(d, palette + index * 2)) : 0;
                            break;
                        }
                        case 1: {
                            int index = d[row + x] & 255;
                            c = index < colours ? colour(u16(d, palette + index * 2)) : 0;
                            break;
                        }
                        case 2: c = colour(u16(d, row + x * 2)); break;
                        default: {
                            int o = row + x * 3;
                            c = 0xff000000 | (d[o] & 255) << 16 | (d[o + 1] & 255) << 8 | (d[o + 2] & 255);
                        }
                    }
                    argb[y * width + x] = c;
                }
            }
            out.add(new Image(width, height, p, argb));
        }
        return out;
    }

    /** PlayStation 15-bit colour: red in the low bits. Black with the STP bit clear is transparent. */
    static int colour(int c) {
        if (c == 0) return 0;
        int r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
        return 0xff000000 | (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
    }

    /** Finds TIM images inside a larger file (4-byte aligned, as the format requires). */
    public static List<Found> scanTims(byte[] d) {
        List<Found> out = new ArrayList<>();
        for (int at = 0; at + 20 <= d.length && out.size() < MAX_SCAN_HITS; at += 4) {
            if (d[at] != 0x10 || d[at + 1] != 0 || d[at + 2] != 0 || d[at + 3] != 0) continue;
            int len = timLength(d, at);
            if (len > 0) { out.add(new Found(at, len)); at += ((len + 3) & ~3) - 4; }
        }
        return out;
    }

    // ------------------------------------------------------------------ VAG

    public static boolean isVag(byte[] d) {
        return d != null && d.length >= 0x40 && d[0] == 'V' && d[1] == 'A' && d[2] == 'G' && d[3] == 'p';
    }

    private static final int[][] FILTERS = {{0, 0}, {60, 0}, {115, -52}, {98, -55}, {122, -60}};

    /**
     * VAG: "VAGp", big-endian u32 version @4, data size @0x0C, sample rate @0x10, name @0x20, PS-ADPCM data @0x30.
     * Each 16-byte frame: shift (low nibble) and filter (high nibble), flags, 28 four-bit samples low nibble first.
     * Decoding stops at the end flag (7) or the declared size.
     */
    public static byte[] vagToWav(byte[] d) throws IOException {
        if (!isVag(d)) throw new IOException("Not a VAG sound");
        long size = be32(d, 0x0C), rate = be32(d, 0x10);
        if (rate < 1000 || rate > 96000) throw new IOException("Unusual VAG sample rate " + rate);
        int end = (int) Math.min(d.length, 0x30 + (size > 0 ? size : d.length));
        int frames = Math.max(0, (end - 0x30) / 16);
        short[] pcm = new short[frames * 28];
        int n = 0, h1 = 0, h2 = 0;
        for (int f = 0; f < frames; f++) {
            int at = 0x30 + f * 16;
            int shift = d[at] & 15, filter = Math.min(4, (d[at] >> 4) & 15), flags = d[at + 1] & 255;
            if (flags == 7) break;
            for (int i = 0; i < 28; i++) {
                int b = d[at + 2 + i / 2] & 255;
                int nibble = (i & 1) == 0 ? b & 15 : b >> 4;
                int s = (short) (nibble << 12) >> shift;
                s += (h1 * FILTERS[filter][0] + h2 * FILTERS[filter][1] + 32) >> 6;
                s = Math.max(-32768, Math.min(32767, s));
                h2 = h1; h1 = s;
                pcm[n++] = (short) s;
            }
        }
        return GameAudio.wav(new short[][]{java.util.Arrays.copyOf(pcm, n)}, (int) rate);
    }

    // ------------------------------------------------------------------ helpers

    private static int u16(byte[] d, int o) { return (d[o] & 255) | (d[o + 1] & 255) << 8; }
    private static long u32(byte[] d, int o) { return u16(d, o) | ((long) u16(d, o + 2) << 16); }
    private static long be32(byte[] d, int o) { return (d[o] & 255L) << 24 | (d[o + 1] & 255) << 16 | (d[o + 2] & 255) << 8 | (d[o + 3] & 255); }
}
