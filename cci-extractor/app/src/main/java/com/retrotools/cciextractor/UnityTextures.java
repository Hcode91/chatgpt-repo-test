package com.retrotools.cciextractor;

import java.io.IOException;

/**
 * Unity Texture2D pixel formats: decode the top mip level to ARGB, and re-encode an edited image back to the exact
 * original format and mip count (so the result is byte-for-byte the same size as the original data).
 *
 * Unity stores rows bottom-up; images here are always top-down, flipping happens inside.
 * ETC/EAC blocks are 64-bit big-endian, blocks in rows left to right. ETC2 RGB decoding covers all modes
 * (individual, differential, T, H, planar). The encoder writes ETC1-compatible blocks (valid ETC2) plus EAC alpha.
 */
final class UnityTextures {
    static final int ALPHA8 = 1, ARGB4444 = 2, RGB24 = 3, RGBA32 = 4, ARGB32 = 5, RGB565 = 7, RGBA4444 = 13,
            BGRA32 = 14, ETC_RGB4 = 34, ETC2_RGB = 45, ETC2_RGBA8 = 47, R8 = 63;

    static String name(int f) {
        switch (f) {
            case ALPHA8: return "Alpha8"; case ARGB4444: return "ARGB4444"; case RGB24: return "RGB24";
            case RGBA32: return "RGBA32"; case ARGB32: return "ARGB32"; case RGB565: return "RGB565";
            case RGBA4444: return "RGBA4444"; case BGRA32: return "BGRA32"; case ETC_RGB4: return "ETC_RGB4";
            case ETC2_RGB: return "ETC2_RGB"; case ETC2_RGBA8: return "ETC2_RGBA8"; case R8: return "R8";
            case 10: return "DXT1"; case 12: return "DXT5"; case 46: return "ETC2_RGBA1";
            case 28: return "DXT1Crunched"; case 29: return "DXT5Crunched"; case 64: return "ETC_RGB4Crunched";
            case 65: return "ETC2_RGBA8Crunched";
            default:
                if (f >= 48 && f <= 59) return "ASTC_" + f;
                if (f >= 30 && f <= 33) return "PVRTC_" + f;
                return "format " + f;
        }
    }

    static boolean supported(int f) {
        switch (f) {
            case ALPHA8: case ARGB4444: case RGB24: case RGBA32: case ARGB32: case RGB565: case RGBA4444: case BGRA32:
            case ETC_RGB4: case ETC2_RGB: case ETC2_RGBA8: case R8: return true;
            default: return false;
        }
    }

    /** True if the format only stores one channel (exported as a greyscale PNG). */
    static boolean singleChannel(int f) { return f == ALPHA8 || f == R8; }

    private static int blockBytes(int f) { return f == ETC2_RGBA8 ? 16 : (f == ETC_RGB4 || f == ETC2_RGB) ? 8 : 0; }
    private static int pixelBytes(int f) {
        switch (f) {
            case ALPHA8: case R8: return 1;
            case ARGB4444: case RGB565: case RGBA4444: return 2;
            case RGB24: return 3;
            case RGBA32: case ARGB32: case BGRA32: return 4;
            default: return 0;
        }
    }

    static long levelSize(int f, int w, int h) {
        int bb = blockBytes(f);
        if (bb > 0) return (long) ((w + 3) / 4) * ((h + 3) / 4) * bb;
        return (long) w * h * pixelBytes(f);
    }

    static long totalSize(int f, int w, int h, int mips) {
        long t = 0;
        for (int l = 0; l < Math.max(1, mips); l++) t += levelSize(f, Math.max(1, w >> l), Math.max(1, h >> l));
        return t;
    }

    // ---------------------------------------------------------------- decode
    /** Decodes mip 0 into top-down ARGB. Single-channel formats come out as opaque grey (value → brightness). */
    static int[] decode(byte[] data, int f, int w, int h) throws IOException { return decode(data, f, w, h, true); }

    /** {@code bottomUp}: rows stored bottom row first (Unity); false for top-down storage (Unreal). */
    static int[] decode(byte[] data, int f, int w, int h, boolean bottomUp) throws IOException {
        if (!supported(f)) throw new IOException("Unsupported texture format " + name(f));
        if (w <= 0 || h <= 0 || (long) w * h > 64L << 20) throw new IOException("Bad texture size " + w + "x" + h);
        long need = levelSize(f, w, h);
        if (data.length < need) throw new IOException("Texture data truncated");
        int[] out = new int[w * h];
        int bb = blockBytes(f);
        if (bb > 0) {
            int bw = (w + 3) / 4, bh = (h + 3) / 4;
            int[] px = new int[16], alpha = new int[16];
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                int at = (by * bw + bx) * bb;
                if (f == ETC2_RGBA8) { decodeEacAlpha(data, at, alpha); decodeEtc2Rgb(data, at + 8, px); }
                else decodeEtc2Rgb(data, at, px);
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                    int sx = bx * 4 + x, sy = by * 4 + y;
                    if (sx >= w || sy >= h) continue;
                    int c = px[y * 4 + x];
                    if (f == ETC2_RGBA8) c = (c & 0xFFFFFF) | (alpha[x * 4 + y] << 24);
                    out[(bottomUp ? h - 1 - sy : sy) * w + sx] = c;
                }
            }
            return out;
        }
        int pb = pixelBytes(f);
        for (int sy = 0; sy < h; sy++) for (int x = 0; x < w; x++) {
            int p = (sy * w + x) * pb, c;
            int b0 = data[p] & 255, b1 = pb > 1 ? data[p + 1] & 255 : 0, b2 = pb > 2 ? data[p + 2] & 255 : 0, b3 = pb > 3 ? data[p + 3] & 255 : 0;
            switch (f) {
                case ALPHA8: case R8: c = 0xFF000000 | (b0 * 0x010101); break;
                case RGB24: c = 0xFF000000 | (b0 << 16) | (b1 << 8) | b2; break;
                case RGBA32: c = (b3 << 24) | (b0 << 16) | (b1 << 8) | b2; break;
                case ARGB32: c = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3; break;
                case BGRA32: c = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0; break;
                case RGB565: { int v = b0 | (b1 << 8); c = 0xFF000000 | (x5(v >> 11) << 16) | (x6((v >> 5) & 63) << 8) | x5(v & 31); break; }
                case ARGB4444: { int v = b0 | (b1 << 8); c = ((v >> 12) * 17 << 24) | (((v >> 8) & 15) * 17 << 16) | (((v >> 4) & 15) * 17 << 8) | ((v & 15) * 17); break; }
                case RGBA4444: { int v = b0 | (b1 << 8); c = ((v & 15) * 17 << 24) | ((v >> 12) * 17 << 16) | (((v >> 8) & 15) * 17 << 8) | (((v >> 4) & 15) * 17); break; }
                default: throw new IOException("Unsupported texture format " + name(f));
            }
            out[(bottomUp ? h - 1 - sy : sy) * w + x] = c;
        }
        return out;
    }
    private static int x5(int v) { return (v << 3) | (v >> 2); }
    private static int x6(int v) { return (v << 2) | (v >> 4); }

    private static final int[][] EAC = {
            {-3, -6, -9, -15, 2, 5, 8, 14}, {-3, -7, -10, -13, 2, 6, 9, 12}, {-2, -5, -8, -13, 1, 4, 7, 12},
            {-2, -4, -6, -13, 1, 3, 5, 12}, {-3, -6, -8, -12, 2, 5, 7, 11}, {-3, -7, -9, -11, 2, 6, 8, 10},
            {-4, -7, -8, -11, 3, 6, 7, 10}, {-3, -5, -8, -11, 2, 4, 7, 10}, {-2, -6, -8, -10, 1, 5, 7, 9},
            {-2, -5, -8, -10, 1, 4, 7, 9}, {-2, -4, -8, -10, 1, 3, 7, 9}, {-2, -5, -7, -10, 1, 4, 6, 9},
            {-3, -4, -7, -10, 2, 3, 6, 9}, {-1, -2, -3, -10, 0, 1, 2, 9}, {-4, -6, -8, -9, 3, 5, 7, 8},
            {-3, -5, -7, -9, 2, 4, 6, 8}};
    private static final int[] T_DIST = {3, 6, 11, 16, 23, 32, 41, 64};

    /** EAC 8-bit alpha block; out[i] for pixel i = x*4+y. */
    static void decodeEacAlpha(byte[] d, int at, int[] out) {
        int base = d[at] & 255, mult = (d[at + 1] >> 4) & 15;
        int[] t = EAC[d[at + 1] & 15];
        long bits = 0;
        for (int i = 2; i < 8; i++) bits = (bits << 8) | (d[at + i] & 255);
        for (int i = 0; i < 16; i++) {
            int idx = (int) ((bits >>> (45 - 3 * i)) & 7);
            out[i] = clamp(base + t[idx] * mult);
        }
    }

    private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }
    private static int sign3(int v) { return v >= 4 ? v - 8 : v; }

    /** ETC2 RGB block (all five modes); px[y*4+x] = opaque ARGB. */
    static void decodeEtc2Rgb(byte[] d, int at, int[] px) {
        int s0 = d[at] & 255, s1 = d[at + 1] & 255, s2 = d[at + 2] & 255, s3 = d[at + 3] & 255;
        long block = 0;
        for (int i = 0; i < 8; i++) block = (block << 8) | (d[at + i] & 255);
        boolean diff = (s3 & 2) != 0;
        if (diff) {
            int r = (s0 >> 3) + sign3(s0 & 7), g = (s1 >> 3) + sign3(s1 & 7), b = (s2 >> 3) + sign3(s2 & 7);
            int idxBits = (int) block;
            if (r < 0 || r > 31) { // T mode
                int r1 = ((s0 >> 1) & 0xC) | (s0 & 3), g1 = (s1 >> 4) & 15, b1 = s1 & 15;
                int r2 = (s2 >> 4) & 15, g2 = s2 & 15, b2 = (s3 >> 4) & 15;
                int dist = T_DIST[((s3 >> 1) & 6) | (s3 & 1)];
                int[] c1 = {r1 * 17, g1 * 17, b1 * 17}, c2 = {r2 * 17, g2 * 17, b2 * 17};
                int[] paint = {rgb(c1, 0), rgb(c2, dist), rgb(c2, 0), rgb(c2, -dist)};
                paintBlock(idxBits, paint, px);
                return;
            }
            if (g < 0 || g > 31) { // H mode
                int r1 = (s0 >> 3) & 15, g1 = ((s0 << 1) & 0xE) | ((s1 >> 4) & 1), b1 = (s1 & 8) | ((s1 << 1) & 6) | (s2 >> 7);
                int r2 = (s2 >> 3) & 15, g2 = ((s2 << 1) & 0xE) | (s3 >> 7), b2 = (s3 >> 3) & 15;
                int v1 = (r1 << 8) | (g1 << 4) | b1, v2 = (r2 << 8) | (g2 << 4) | b2;
                int dist = T_DIST[(s3 & 4) | ((s3 << 1) & 2) | (v1 >= v2 ? 1 : 0)];
                int[] c1 = {r1 * 17, g1 * 17, b1 * 17}, c2 = {r2 * 17, g2 * 17, b2 * 17};
                int[] paint = {rgb(c1, dist), rgb(c1, -dist), rgb(c2, dist), rgb(c2, -dist)};
                paintBlock(idxBits, paint, px);
                return;
            }
            if (b < 0 || b > 31) { // planar
                int s4 = d[at + 4] & 255, s5 = d[at + 5] & 255, s6 = d[at + 6] & 255, s7 = d[at + 7] & 255;
                int ro = (s0 >> 1) & 0x3F, go = ((s0 & 1) << 6) | ((s1 >> 1) & 0x3F);
                int bo = ((s1 & 1) << 5) | (s2 & 0x18) | ((s2 << 1) & 6) | ((s3 >> 7) & 1);
                int rh = ((s3 >> 1) & 0x3E) | (s3 & 1), gh = (s4 >> 1) & 0x7F, bh = ((s4 & 1) << 5) | ((s5 >> 3) & 0x1F);
                int rv = ((s5 & 7) << 3) | ((s6 >> 5) & 7), gv = ((s6 & 0x1F) << 2) | ((s7 >> 6) & 3), bv = s7 & 0x3F;
                ro = e6(ro); rh = e6(rh); rv = e6(rv); bo = e6(bo); bh = e6(bh); bv = e6(bv);
                go = e7(go); gh = e7(gh); gv = e7(gv);
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                    int R = clamp((x * (rh - ro) + y * (rv - ro) + 4 * ro + 2) >> 2);
                    int G = clamp((x * (gh - go) + y * (gv - go) + 4 * go + 2) >> 2);
                    int B = clamp((x * (bh - bo) + y * (bv - bo) + 4 * bo + 2) >> 2);
                    px[y * 4 + x] = 0xFF000000 | (R << 16) | (G << 8) | B;
                }
                return;
            }
        }
        TextureFormats.decodeEtc1Block(block, px);
    }
    private static int e6(int v) { return (v << 2) | (v >> 4); }
    private static int e7(int v) { return (v << 1) | (v >> 6); }
    private static int rgb(int[] c, int d) { return 0xFF000000 | (clamp(c[0] + d) << 16) | (clamp(c[1] + d) << 8) | clamp(c[2] + d); }
    private static void paintBlock(int bits, int[] paint, int[] px) {
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) {
            int i = x * 4 + y;
            int idx = (((bits >>> (16 + i)) & 1) << 1) | ((bits >>> i) & 1);
            px[y * 4 + x] = paint[idx];
        }
    }

    // ---------------------------------------------------------------- encode
    /**
     * Encodes a top-down ARGB image as {@code mips} levels of format {@code f}. For single-channel formats the
     * image's brightness is used (exported PNGs are greyscale).
     */
    static byte[] encode(int[] argb, int w, int h, int f, int mips) throws IOException {
        if (!supported(f)) throw new IOException("Can't write texture format " + name(f));
        if (argb.length != w * h) throw new IOException("Image size mismatch");
        mips = Math.max(1, mips);
        long total = totalSize(f, w, h, mips);
        if (total > Integer.MAX_VALUE - 16) throw new IOException("Texture too large");
        byte[] out = new byte[(int) total];
        int at = 0;
        int[] level = argb; int lw = w, lh = h;
        for (int l = 0; l < mips; l++) {
            int tw = Math.max(1, w >> l), th = Math.max(1, h >> l);
            if (l > 0) { level = downsample(level, lw, lh, tw, th); lw = tw; lh = th; }
            at = encodeLevel(level, lw, lh, f, out, at, true);
        }
        if (at != out.length) throw new IOException("Internal error: encoded size mismatch");
        return out;
    }

    /** Box-filter downscale (each target pixel averages the source pixels it covers). */
    static int[] downsample(int[] src, int sw, int sh, int tw, int th) {
        int[] out = new int[tw * th];
        for (int y = 0; y < th; y++) {
            int y0 = (int) ((long) y * sh / th), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / th));
            for (int x = 0; x < tw; x++) {
                int x0 = (int) ((long) x * sw / tw), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / tw));
                long a = 0, r = 0, g = 0, b = 0; int n = 0;
                for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) {
                    int c = src[yy * sw + xx];
                    a += c >>> 24; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[y * tw + x] = (int) (((a + n / 2) / n) << 24 | ((r + n / 2) / n) << 16 | ((g + n / 2) / n) << 8 | ((b + n / 2) / n));
            }
        }
        return out;
    }

    private static int lum(int c) { return (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114 + 500) / 1000; }

    /** One level, rows top-down (Unreal). */
    static byte[] encodeTopDown(int[] argb, int w, int h, int f) throws IOException {
        if (!supported(f)) throw new IOException("Can't write texture format " + name(f));
        if (argb.length != w * h) throw new IOException("Image size mismatch");
        byte[] out = new byte[(int) levelSize(f, w, h)];
        encodeLevel(argb, w, h, f, out, 0, false);
        return out;
    }

    private static int encodeLevel(int[] img, int w, int h, int f, byte[] out, int at, boolean bottomUp) {
        int bb = blockBytes(f);
        if (bb > 0) {
            int bw = (w + 3) / 4, bh = (h + 3) / 4;
            int[] px = new int[16], alpha = new int[16];
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                    int sx = Math.min(w - 1, bx * 4 + x), sy = Math.min(h - 1, by * 4 + y); // edge pixels repeat
                    int c = img[(bottomUp ? h - 1 - sy : sy) * w + sx];
                    px[y * 4 + x] = c | 0xFF000000;
                    alpha[x * 4 + y] = c >>> 24;
                }
                if (f == ETC2_RGBA8) { encodeEacAlpha(alpha, out, at); at += 8; }
                long block = TextureFormats.encodeEtc1Block(px);
                for (int i = 0; i < 8; i++) out[at + i] = (byte) (block >>> (56 - 8 * i));
                at += 8;
            }
            return at;
        }
        for (int sy = 0; sy < h; sy++) for (int x = 0; x < w; x++) {
            int c = img[(bottomUp ? h - 1 - sy : sy) * w + x];
            int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            switch (f) {
                case ALPHA8: case R8: out[at++] = (byte) lum(c); break;
                case RGB24: out[at++] = (byte) r; out[at++] = (byte) g; out[at++] = (byte) b; break;
                case RGBA32: out[at++] = (byte) r; out[at++] = (byte) g; out[at++] = (byte) b; out[at++] = (byte) a; break;
                case ARGB32: out[at++] = (byte) a; out[at++] = (byte) r; out[at++] = (byte) g; out[at++] = (byte) b; break;
                case BGRA32: out[at++] = (byte) b; out[at++] = (byte) g; out[at++] = (byte) r; out[at++] = (byte) a; break;
                case RGB565: { int v = ((r * 31 + 127) / 255 << 11) | ((g * 63 + 127) / 255 << 5) | ((b * 31 + 127) / 255); out[at++] = (byte) v; out[at++] = (byte) (v >> 8); break; }
                case ARGB4444: { int v = (q4(a) << 12) | (q4(r) << 8) | (q4(g) << 4) | q4(b); out[at++] = (byte) v; out[at++] = (byte) (v >> 8); break; }
                case RGBA4444: { int v = (q4(r) << 12) | (q4(g) << 8) | (q4(b) << 4) | q4(a); out[at++] = (byte) v; out[at++] = (byte) (v >> 8); break; }
                default: throw new IllegalStateException();
            }
        }
        return at;
    }
    private static int q4(int v) { return (v * 15 + 127) / 255; }

    /** EAC alpha encoder: searches every table with the best-fitting multiplier/base around the block's range. */
    static void encodeEacAlpha(int[] a, byte[] out, int at) {
        int lo = 255, hi = 0;
        for (int v : a) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        int bestBase = lo, bestMult = 1, bestTable = 13; long bestBits = 0; long bestErr = Long.MAX_VALUE;
        if (lo == hi) {
            // table 13 index 4 is a 0 modifier, so any constant is exact
            bestBase = lo; bestMult = 1; bestTable = 13;
            for (int i = 0; i < 16; i++) bestBits |= 4L << (45 - 3 * i);
            bestErr = 0;
        }
        for (int t = 0; t < 16 && bestErr > 0; t++) {
            int[] tb = EAC[t];
            int tmin = tb[3], tmax = tb[7];
            int m0 = Math.round((hi - lo) / (float) (tmax - tmin));
            for (int m = Math.max(1, m0 - 1); m <= Math.min(15, m0 + 1); m++) {
                int b0 = Math.round((lo + hi) / 2f - (tmax + tmin) * m / 2f);
                for (int base = Math.max(0, b0 - 2); base <= Math.min(255, b0 + 2); base++) {
                    long err = 0, bits = 0;
                    for (int i = 0; i < 16 && err < bestErr; i++) {
                        int v = a[i], bi = 0, be = Integer.MAX_VALUE;
                        for (int k = 0; k < 8; k++) {
                            int e = clamp(base + tb[k] * m) - v; e *= e;
                            if (e < be) { be = e; bi = k; }
                        }
                        err += be; bits |= (long) bi << (45 - 3 * i);
                    }
                    if (err < bestErr) { bestErr = err; bestBase = base; bestMult = m; bestTable = t; bestBits = bits; }
                }
            }
        }
        out[at] = (byte) bestBase;
        out[at + 1] = (byte) ((bestMult << 4) | bestTable);
        for (int i = 0; i < 6; i++) out[at + 2 + i] = (byte) (bestBits >>> (40 - 8 * i));
    }
}
