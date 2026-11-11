package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone texture conversions: DDS (uncompressed, BC1–BC5), KTX 1 (RGBA8/RGB8/ETC1), DS NCGR+NCLR,
 * plus resizing and palette quantisation. Images are ARGB int arrays.
 */
public final class TextureFormats {
    public static final int MAX_PIXELS = 16_777_216;

    public static final class Image {
        public final int width, height; public final int[] argb;
        public Image(int width, int height, int[] argb) { this.width = width; this.height = height; this.argb = argb; }
    }

    public enum Kind { DDS, KTX, BTEX, NCGR, NCLR, UNKNOWN }

    private TextureFormats() {}

    public static Kind detect(byte[] d) {
        if (d.length >= 128 && d[0] == 'D' && d[1] == 'D' && d[2] == 'S' && d[3] == ' ') return Kind.DDS;
        if (d.length >= 64 && (d[0] & 255) == 0xAB && d[1] == 'K' && d[2] == 'T' && d[3] == 'X') return Kind.KTX;
        if (d.length >= 4 && d[0] == 'R' && d[1] == 'G' && d[2] == 'C' && d[3] == 'N') return Kind.NCGR;
        if (d.length >= 4 && d[0] == 'R' && d[1] == 'L' && d[2] == 'C' && d[3] == 'N') return Kind.NCLR;
        try { BtexCodec.inspect(d); return Kind.BTEX; } catch (Exception notBtex) { return Kind.UNKNOWN; }
    }

    private static int le16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    private static int le32(byte[] b, int o) { return le16(b, o) | (le16(b, o + 2) << 16); }
    private static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); }
    private static void put32(byte[] b, int o, int v) { put16(b, o, v); put16(b, o + 2, v >>> 16); }
    private static void check(boolean ok, String msg) throws IOException { if (!ok) throw new IOException(msg); }
    private static int argb(int a, int r, int g, int b) { return (a << 24) | (r << 16) | (g << 8) | b; }
    private static int c565(int v) {
        int r = (v >>> 11) & 31, g = (v >>> 5) & 63, b = v & 31;
        return argb(255, (r << 3) | (r >>> 2), (g << 2) | (g >>> 4), (b << 3) | (b >>> 2));
    }
    private static void dims(int w, int h) throws IOException {
        check(w > 0 && h > 0 && (long) w * h <= MAX_PIXELS, "Image size " + w + "×" + h + " is out of range");
    }

    // ================================================================ DDS
    private static final int FOURCC_DXT1 = 0x31545844, FOURCC_DXT2 = 0x32545844, FOURCC_DXT3 = 0x33545844,
            FOURCC_DXT4 = 0x34545844, FOURCC_DXT5 = 0x35545844, FOURCC_ATI1 = 0x31495441, FOURCC_BC4U = 0x55344342,
            FOURCC_ATI2 = 0x32495441, FOURCC_BC5U = 0x55354342, FOURCC_DX10 = 0x30315844;

    public static String ddsDescribe(byte[] d) {
        try {
            int fourcc = le32(d, 84);
            if (fourcc == FOURCC_DX10) return "DDS DXGI format " + le32(d, 128);
            if ((le32(d, 80) & 4) != 0) return "DDS " + new String(d, 84, 4, java.nio.charset.StandardCharsets.US_ASCII);
            return "DDS " + le32(d, 88) + "-bit uncompressed";
        } catch (RuntimeException e) { return "DDS"; }
    }

    /** Decode the top mip level (first face/array slice) of a DDS. */
    public static Image decodeDds(byte[] d) throws IOException {
        check(d.length >= 128 && le32(d, 4) == 124, "Not a DDS file");
        int height = le32(d, 12), width = le32(d, 16);
        dims(width, height);
        int pfFlags = le32(d, 80), fourcc = le32(d, 84);
        int data = 128;
        if ((pfFlags & 4) != 0) {
            int block;
            if (fourcc == FOURCC_DX10) {
                check(d.length >= 148, "DDS DX10 header truncated");
                int dxgi = le32(d, 128);
                data = 148;
                switch (dxgi) {
                    case 71: case 72: block = 1; break;
                    case 74: case 75: block = 2; break;
                    case 77: case 78: block = 3; break;
                    case 80: case 81: block = 4; break;
                    case 83: case 84: block = 5; break;
                    case 28: case 29: return decodeMasked(d, data, width, height, 32, 0xFF, 0xFF00, 0xFF0000, 0xFF000000);
                    case 87: case 91: return decodeMasked(d, data, width, height, 32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000);
                    case 88: case 93: return decodeMasked(d, data, width, height, 32, 0xFF0000, 0xFF00, 0xFF, 0);
                    case 61: return decodeMasked(d, data, width, height, 8, 0xFF, 0xFF, 0xFF, 0);
                    case 94: case 95: case 96: throw new IOException("BC6H (HDR) DDS textures aren't supported yet");
                    case 97: case 98: case 99: throw new IOException("BC7 DDS textures aren't supported yet. Convert them " +
                            "to BC3 or RGBA8 on PC first (e.g. Compressonator).");
                    default: throw new IOException("Unsupported DXGI format " + dxgi);
                }
            } else if (fourcc == FOURCC_DXT1) block = 1;
            else if (fourcc == FOURCC_DXT2 || fourcc == FOURCC_DXT3) block = 2;
            else if (fourcc == FOURCC_DXT4 || fourcc == FOURCC_DXT5) block = 3;
            else if (fourcc == FOURCC_ATI1 || fourcc == FOURCC_BC4U) block = 4;
            else if (fourcc == FOURCC_ATI2 || fourcc == FOURCC_BC5U) block = 5;
            else throw new IOException("Unsupported DDS compression " + ddsDescribe(d));
            return decodeBc(d, data, width, height, block);
        }
        int bits = le32(d, 88);
        int a = (pfFlags & 1) != 0 ? le32(d, 104) : 0;
        if ((pfFlags & 0x20000) != 0 && (pfFlags & 0x40) == 0) // luminance
            return decodeMasked(d, data, width, height, bits, le32(d, 92), le32(d, 92), le32(d, 92), a);
        if ((pfFlags & 2) != 0 && (pfFlags & 0x40) == 0) // alpha only
            return decodeMasked(d, data, width, height, bits, 0, 0, 0, le32(d, 104));
        return decodeMasked(d, data, width, height, bits, le32(d, 92), le32(d, 96), le32(d, 100), a);
    }

    private static int extract(long px, int mask) {
        if (mask == 0) return -1;
        long m = mask & 0xffffffffL;
        int shift = Long.numberOfTrailingZeros(m);
        long max = m >>> shift;
        long v = (px & m) >>> shift;
        return (int) ((v * 255 + max / 2) / max);
    }

    private static Image decodeMasked(byte[] d, int data, int w, int h, int bits, int rm, int gm, int bm, int am)
            throws IOException {
        check(bits == 8 || bits == 16 || bits == 24 || bits == 32, "Unsupported DDS bit depth " + bits);
        int bpp = bits / 8;
        int pitch = w * bpp;
        check(data + (long) pitch * h <= d.length, "DDS pixel data is truncated");
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int at = data + y * pitch + x * bpp;
            long px = 0;
            for (int i = 0; i < bpp; i++) px |= (long) (d[at + i] & 255) << (8 * i);
            int r = extract(px, rm), g = extract(px, gm), b = extract(px, bm), a = extract(px, am);
            if (r < 0) r = 0; if (g < 0) g = r; if (b < 0) b = r;
            out[y * w + x] = argb(a < 0 ? 255 : a, r, g, b);
        }
        return new Image(w, h, out);
    }

    private static void bc1Colors(int c0, int c1, int[] pal, boolean allowTransparent) {
        int a = c565(c0), b = c565(c1);
        pal[0] = a; pal[1] = b;
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        if (c0 > c1 || !allowTransparent) {
            pal[2] = argb(255, (2 * ar + br) / 3, (2 * ag + bg) / 3, (2 * ab + bb) / 3);
            pal[3] = argb(255, (ar + 2 * br) / 3, (ag + 2 * bg) / 3, (ab + 2 * bb) / 3);
        } else {
            pal[2] = argb(255, (ar + br) / 2, (ag + bg) / 2, (ab + bb) / 2);
            pal[3] = 0;
        }
    }

    private static void alphaBlock(byte[] d, int at, int[] out) {
        int a0 = d[at] & 255, a1 = d[at + 1] & 255;
        int[] pal = new int[8];
        pal[0] = a0; pal[1] = a1;
        if (a0 > a1) for (int i = 1; i < 7; i++) pal[i + 1] = ((7 - i) * a0 + i * a1) / 7;
        else { for (int i = 1; i < 5; i++) pal[i + 1] = ((5 - i) * a0 + i * a1) / 5; pal[6] = 0; pal[7] = 255; }
        long bits = 0;
        for (int i = 0; i < 6; i++) bits |= (long) (d[at + 2 + i] & 255) << (8 * i);
        for (int i = 0; i < 16; i++) out[i] = pal[(int) ((bits >>> (3 * i)) & 7)];
    }

    private static Image decodeBc(byte[] d, int data, int w, int h, int bc) throws IOException {
        int bw = (w + 3) / 4, bh = (h + 3) / 4;
        int blockBytes = (bc == 1 || bc == 4) ? 8 : 16;
        check(data + (long) bw * bh * blockBytes <= d.length, "DDS compressed data is truncated");
        int[] out = new int[w * h];
        int[] pal = new int[4], alpha = new int[16], second = new int[16];
        for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
            int at = data + (by * bw + bx) * blockBytes;
            int[] px = new int[16];
            if (bc == 4 || bc == 5) {
                alphaBlock(d, at, alpha);
                if (bc == 5) alphaBlock(d, at + 8, second);
                for (int i = 0; i < 16; i++) {
                    if (bc == 4) px[i] = argb(255, alpha[i], alpha[i], alpha[i]);
                    else {
                        // Normal map: reconstruct Z for a viewable image.
                        double nx = alpha[i] / 127.5 - 1, ny = second[i] / 127.5 - 1;
                        double nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                        px[i] = argb(255, alpha[i], second[i], (int) Math.round((nz + 1) * 127.5));
                    }
                }
            } else {
                int colour = bc == 1 ? at : at + 8;
                int c0 = le16(d, colour), c1 = le16(d, colour + 2);
                bc1Colors(c0, c1, pal, bc == 1);
                int idx = le32(d, colour + 4);
                if (bc == 3) alphaBlock(d, at, alpha);
                for (int i = 0; i < 16; i++) {
                    int c = pal[(idx >>> (2 * i)) & 3];
                    if (bc == 2) {
                        int nib = ((d[at + i / 2] & 255) >>> ((i & 1) * 4)) & 15;
                        c = (c & 0xffffff) | ((nib * 17) << 24);
                    } else if (bc == 3) c = (c & 0xffffff) | (alpha[i] << 24);
                    px[i] = c;
                }
            }
            for (int i = 0; i < 16; i++) {
                int x = bx * 4 + (i & 3), y = by * 4 + (i >> 2);
                if (x < w && y < h) out[y * w + x] = px[i];
            }
        }
        return new Image(w, h, out);
    }

    public enum DdsFormat { RGBA8, BC1, BC3 }

    public static byte[] encodeDds(Image img, DdsFormat format) throws IOException {
        dims(img.width, img.height);
        int w = img.width, h = img.height;
        byte[] body;
        byte[] header = new byte[128];
        header[0] = 'D'; header[1] = 'D'; header[2] = 'S'; header[3] = ' ';
        put32(header, 4, 124);
        put32(header, 12, h);
        put32(header, 16, w);
        put32(header, 28, 1);
        put32(header, 76, 32);
        put32(header, 108, 0x1000); // DDSCAPS_TEXTURE
        if (format == DdsFormat.RGBA8) {
            put32(header, 8, 0x1 | 0x2 | 0x4 | 0x8 | 0x1000);
            put32(header, 20, w * 4);
            put32(header, 80, 0x41);
            put32(header, 88, 32);
            put32(header, 92, 0x00FF0000); put32(header, 96, 0x0000FF00);
            put32(header, 100, 0x000000FF); put32(header, 104, 0xFF000000);
            body = new byte[w * h * 4];
            for (int i = 0; i < w * h; i++) {
                int c = img.argb[i];
                body[i * 4] = (byte) c; body[i * 4 + 1] = (byte) (c >> 8);
                body[i * 4 + 2] = (byte) (c >> 16); body[i * 4 + 3] = (byte) (c >>> 24);
            }
        } else {
            int bw = (w + 3) / 4, bh = (h + 3) / 4;
            int blockBytes = format == DdsFormat.BC1 ? 8 : 16;
            body = new byte[bw * bh * blockBytes];
            put32(header, 8, 0x1 | 0x2 | 0x4 | 0x1000 | 0x80000);
            put32(header, 20, body.length);
            put32(header, 80, 0x4);
            put32(header, 84, format == DdsFormat.BC1 ? FOURCC_DXT1 : FOURCC_DXT5);
            int[] block = new int[16];
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                for (int i = 0; i < 16; i++) {
                    int x = Math.min(w - 1, bx * 4 + (i & 3)), y = Math.min(h - 1, by * 4 + (i >> 2));
                    block[i] = img.argb[y * w + x];
                }
                int at = (by * bw + bx) * blockBytes;
                if (format == DdsFormat.BC1) encodeBc1(block, body, at, true);
                else { encodeAlpha(block, body, at); encodeBc1(block, body, at + 8, false); }
            }
        }
        byte[] out = new byte[128 + body.length];
        System.arraycopy(header, 0, out, 0, 128);
        System.arraycopy(body, 0, out, 128, body.length);
        return out;
    }

    private static int to565(int r, int g, int b) {
        return ((Math.min(31, (r * 31 + 127) / 255)) << 11) | ((Math.min(63, (g * 63 + 127) / 255)) << 5) | Math.min(31, (b * 31 + 127) / 255);
    }

    /** Simple BC1 encoder: endpoints from the extremes along the block's main colour axis. */
    static void encodeBc1(int[] px, byte[] out, int at, boolean punchThrough) {
        boolean transparent = false;
        double mr = 0, mg = 0, mb = 0; int n = 0;
        for (int c : px) {
            if (punchThrough && (c >>> 24) < 128) { transparent = true; continue; }
            mr += (c >> 16) & 255; mg += (c >> 8) & 255; mb += c & 255; n++;
        }
        if (n == 0) { put16(out, at, 0); put16(out, at + 2, 0xFFFF); put32(out, at + 4, -1); return; }
        mr /= n; mg /= n; mb /= n;
        // Covariance → principal axis by power iteration.
        double[] cov = new double[6];
        for (int c : px) {
            if (punchThrough && (c >>> 24) < 128) continue;
            double r = ((c >> 16) & 255) - mr, g = ((c >> 8) & 255) - mg, b = (c & 255) - mb;
            cov[0] += r * r; cov[1] += r * g; cov[2] += r * b; cov[3] += g * g; cov[4] += g * b; cov[5] += b * b;
        }
        double vr = 1, vg = 1, vb = 1;
        for (int k = 0; k < 8; k++) {
            double nr = cov[0] * vr + cov[1] * vg + cov[2] * vb;
            double ng = cov[1] * vr + cov[3] * vg + cov[4] * vb;
            double nb = cov[2] * vr + cov[4] * vg + cov[5] * vb;
            double len = Math.sqrt(nr * nr + ng * ng + nb * nb);
            if (len < 1e-9) break;
            vr = nr / len; vg = ng / len; vb = nb / len;
        }
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int c : px) {
            if (punchThrough && (c >>> 24) < 128) continue;
            double t = (((c >> 16) & 255) - mr) * vr + (((c >> 8) & 255) - mg) * vg + ((c & 255) - mb) * vb;
            lo = Math.min(lo, t); hi = Math.max(hi, t);
        }
        int e0 = to565(clamp(mr + vr * hi), clamp(mg + vg * hi), clamp(mb + vb * hi));
        int e1 = to565(clamp(mr + vr * lo), clamp(mg + vg * lo), clamp(mb + vb * lo));
        boolean threeColour = transparent;
        int c0, c1;
        if (threeColour) { c0 = Math.min(e0, e1); c1 = Math.max(e0, e1); }
        else {
            c0 = Math.max(e0, e1); c1 = Math.min(e0, e1);
            if (c0 == c1) { put16(out, at, c0); put16(out, at + 2, c1); put32(out, at + 4, 0); return; }
        }
        int[] pal = new int[4];
        bc1Colors(c0, c1, pal, true);
        int indices = 0;
        for (int i = 0; i < 16; i++) {
            int c = px[i], best = 0;
            if (threeColour && (c >>> 24) < 128) best = 3;
            else {
                long bestD = Long.MAX_VALUE;
                for (int k = 0; k < (threeColour ? 3 : 4); k++) {
                    int p = pal[k];
                    long dr = ((c >> 16) & 255) - ((p >> 16) & 255), dg = ((c >> 8) & 255) - ((p >> 8) & 255), db = (c & 255) - (p & 255);
                    long dd = dr * dr + dg * dg + db * db;
                    if (dd < bestD) { bestD = dd; best = k; }
                }
            }
            indices |= best << (2 * i);
        }
        put16(out, at, c0); put16(out, at + 2, c1); put32(out, at + 4, indices);
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    static void encodeAlpha(int[] px, byte[] out, int at) {
        int lo = 255, hi = 0;
        for (int c : px) { int a = c >>> 24; lo = Math.min(lo, a); hi = Math.max(hi, a); }
        if (hi == lo) { out[at] = (byte) hi; out[at + 1] = (byte) lo; for (int i = 2; i < 8; i++) out[at + i] = 0; return; }
        int[] pal = new int[8];
        pal[0] = hi; pal[1] = lo;
        for (int i = 1; i < 7; i++) pal[i + 1] = ((7 - i) * hi + i * lo) / 7;
        long bits = 0;
        for (int i = 0; i < 16; i++) {
            int a = px[i] >>> 24, best = 0, bestD = 999;
            for (int k = 0; k < 8; k++) { int dd = Math.abs(a - pal[k]); if (dd < bestD) { bestD = dd; best = k; } }
            bits |= (long) best << (3 * i);
        }
        out[at] = (byte) hi; out[at + 1] = (byte) lo;
        for (int i = 0; i < 6; i++) out[at + 2 + i] = (byte) (bits >>> (8 * i));
    }

    // ================================================================ ETC1 (standard block order, big-endian blocks)
    private static final int[][] ETC_TABLE = {{2, 8}, {5, 17}, {9, 29}, {13, 42}, {18, 60}, {24, 80}, {33, 106}, {47, 183}};

    static void decodeEtc1Block(long block, int[] px) {
        boolean diff = ((block >>> 33) & 1) != 0, flip = ((block >>> 32) & 1) != 0;
        int t0 = (int) ((block >>> 37) & 7), t1 = (int) ((block >>> 34) & 7);
        int[][] base = new int[2][3];
        for (int ch = 0; ch < 3; ch++) {
            int shift = 59 - ch * 8;
            if (diff) {
                int a = (int) ((block >>> shift) & 31);
                int dd = (int) ((block >>> (shift - 3)) & 7);
                if (dd >= 4) dd -= 8;
                int b = a + dd;
                base[0][ch] = (a << 3) | (a >>> 2);
                base[1][ch] = ((b & 31) << 3) | ((b & 31) >>> 2);
            } else {
                int a = (int) ((block >>> (shift + 1)) & 15), b = (int) ((block >>> (shift - 3)) & 15);
                base[0][ch] = a * 17; base[1][ch] = b * 17;
            }
        }
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) {
            int i = x * 4 + y; // bit index: column-major
            int sub = flip ? (y >= 2 ? 1 : 0) : (x >= 2 ? 1 : 0);
            int msb = (int) ((block >>> (16 + i)) & 1), lsb = (int) ((block >>> i) & 1);
            int[] t = ETC_TABLE[sub == 0 ? t0 : t1];
            int mod = msb == 0 ? (lsb == 0 ? t[0] : t[1]) : (lsb == 0 ? -t[0] : -t[1]);
            int[] b = base[sub];
            px[y * 4 + x] = argb(255, clampI(b[0] + mod), clampI(b[1] + mod), clampI(b[2] + mod));
        }
    }
    private static int clampI(int v) { return Math.max(0, Math.min(255, v)); }

    static long encodeEtc1Block(int[] px) {
        long best = 0; long bestErr = Long.MAX_VALUE;
        for (int flip = 0; flip < 2; flip++) {
            int[][] avg = new int[2][3];
            for (int sub = 0; sub < 2; sub++) {
                long r = 0, g = 0, b = 0;
                for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) {
                    int s = flip == 1 ? (y >= 2 ? 1 : 0) : (x >= 2 ? 1 : 0);
                    if (s != sub) continue;
                    int c = px[y * 4 + x]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
                }
                avg[sub][0] = (int) (r / 8); avg[sub][1] = (int) (g / 8); avg[sub][2] = (int) (b / 8);
            }
            // Try differential mode first (5-bit + 3-bit delta), else individual (4-bit each).
            for (int diffMode = 1; diffMode >= 0; diffMode--) {
                long block = 0;
                int[][] base = new int[2][3];
                boolean ok = true;
                for (int ch = 0; ch < 3; ch++) {
                    int shift = 59 - ch * 8;
                    if (diffMode == 1) {
                        int a = Math.round(avg[0][ch] * 31 / 255f), b = Math.round(avg[1][ch] * 31 / 255f);
                        int dd = b - a;
                        if (dd < -4 || dd > 3) { ok = false; break; }
                        block |= ((long) a << shift) | ((long) (dd & 7) << (shift - 3));
                        base[0][ch] = (a << 3) | (a >>> 2); base[1][ch] = (b << 3) | (b >>> 2);
                    } else {
                        int a = Math.round(avg[0][ch] * 15 / 255f), b = Math.round(avg[1][ch] * 15 / 255f);
                        block |= ((long) a << (shift + 1)) | ((long) b << (shift - 3));
                        base[0][ch] = a * 17; base[1][ch] = b * 17;
                    }
                }
                if (!ok) continue;
                if (diffMode == 1) block |= 1L << 33;
                if (flip == 1) block |= 1L << 32;
                long err = 0;
                for (int sub = 0; sub < 2; sub++) {
                    int bestTable = 0; long bestSubErr = Long.MAX_VALUE; int bestBits = 0;
                    for (int t = 0; t < 8; t++) {
                        long e = 0; int bits = 0;
                        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) {
                            int s = flip == 1 ? (y >= 2 ? 1 : 0) : (x >= 2 ? 1 : 0);
                            if (s != sub) continue;
                            int c = px[y * 4 + x];
                            long be = Long.MAX_VALUE; int bm = 0;
                            for (int m = 0; m < 4; m++) {
                                int mod = m == 0 ? ETC_TABLE[t][0] : m == 1 ? ETC_TABLE[t][1] : m == 2 ? -ETC_TABLE[t][0] : -ETC_TABLE[t][1];
                                long dr = ((c >> 16) & 255) - clampI(base[sub][0] + mod);
                                long dg = ((c >> 8) & 255) - clampI(base[sub][1] + mod);
                                long db = (c & 255) - clampI(base[sub][2] + mod);
                                long dd = dr * dr + dg * dg + db * db;
                                if (dd < be) { be = dd; bm = m; }
                            }
                            e += be;
                            int i = x * 4 + y;
                            int msb = bm >= 2 ? 1 : 0, lsb = (bm & 1);
                            bits |= (msb << (16 + i)) | (lsb << i);
                        }
                        if (e < bestSubErr) { bestSubErr = e; bestTable = t; bestBits = bits; }
                    }
                    block |= (long) bestTable << (sub == 0 ? 37 : 34);
                    block |= bestBits & 0xffffffffL;
                    err += bestSubErr;
                }
                if (err < bestErr) { bestErr = err; best = block; }
            }
        }
        return best;
    }

    // ================================================================ KTX 1
    private static final byte[] KTX_ID = {(byte) 0xAB, 'K', 'T', 'X', ' ', '1', '1', (byte) 0xBB, '\r', '\n', 0x1A, '\n'};
    private static final int GL_RGBA8 = 0x8058, GL_RGB8 = 0x8051, GL_ETC1 = 0x8D64, GL_ETC2_RGB = 0x9274,
            GL_RGBA = 0x1908, GL_RGB = 0x1907, GL_UNSIGNED_BYTE = 0x1401;

    public static Image decodeKtx(byte[] d) throws IOException {
        check(d.length >= 64 && Arrays.equals(Arrays.copyOf(d, 12), KTX_ID), "Not a KTX 1 file");
        check(le32(d, 12) == 0x04030201, "Big-endian KTX files aren't supported");
        int glType = le32(d, 16), glFormat = le32(d, 24), internal = le32(d, 28);
        int w = le32(d, 36), h = Math.max(1, le32(d, 40));
        dims(w, h);
        int kv = le32(d, 60);
        int at = 64 + kv;
        check(at + 4 <= d.length, "KTX data truncated");
        int size = le32(d, at); at += 4;
        check(size >= 0 && at + (long) size <= d.length, "KTX image data truncated");
        int[] out = new int[w * h];
        if (internal == GL_ETC1 || internal == GL_ETC2_RGB) {
            int bw = (w + 3) / 4, bh = (h + 3) / 4;
            check(size >= bw * bh * 8, "KTX ETC data truncated");
            int[] px = new int[16];
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                long block = 0;
                int o = at + (by * bw + bx) * 8;
                for (int i = 0; i < 8; i++) block = (block << 8) | (d[o + i] & 255);
                decodeEtc1Block(block, px);
                for (int i = 0; i < 16; i++) {
                    int x = bx * 4 + (i & 3), y = by * 4 + (i >> 2);
                    if (x < w && y < h) out[y * w + x] = px[i];
                }
            }
            return new Image(w, h, out);
        }
        if (glType == GL_UNSIGNED_BYTE && (glFormat == GL_RGBA || glFormat == GL_RGB)) {
            int bpp = glFormat == GL_RGBA ? 4 : 3;
            int pitch = (w * bpp + 3) & ~3;
            check(size >= pitch * h, "KTX pixel data truncated");
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int o = at + y * pitch + x * bpp;
                out[y * w + x] = argb(bpp == 4 ? d[o + 3] & 255 : 255, d[o] & 255, d[o + 1] & 255, d[o + 2] & 255);
            }
            return new Image(w, h, out);
        }
        throw new IOException("Unsupported KTX format 0x" + Integer.toHexString(internal) +
                " (supported: RGBA8, RGB8, ETC1; ETC2 RGB is read as ETC1)");
    }

    public enum KtxFormat { RGBA8, ETC1 }

    public static byte[] encodeKtx(Image img, KtxFormat format) throws IOException {
        dims(img.width, img.height);
        int w = img.width, h = img.height;
        byte[] body;
        byte[] head = new byte[64];
        System.arraycopy(KTX_ID, 0, head, 0, 12);
        put32(head, 12, 0x04030201);
        if (format == KtxFormat.RGBA8) {
            put32(head, 16, GL_UNSIGNED_BYTE); put32(head, 20, 1); put32(head, 24, GL_RGBA);
            put32(head, 28, GL_RGBA8); put32(head, 32, GL_RGBA);
            body = new byte[w * h * 4];
            for (int i = 0; i < w * h; i++) {
                int c = img.argb[i];
                body[i * 4] = (byte) (c >> 16); body[i * 4 + 1] = (byte) (c >> 8);
                body[i * 4 + 2] = (byte) c; body[i * 4 + 3] = (byte) (c >>> 24);
            }
        } else {
            put32(head, 16, 0); put32(head, 20, 1); put32(head, 24, 0);
            put32(head, 28, GL_ETC1); put32(head, 32, GL_RGB);
            int bw = (w + 3) / 4, bh = (h + 3) / 4;
            body = new byte[bw * bh * 8];
            int[] px = new int[16];
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                for (int i = 0; i < 16; i++) {
                    int x = Math.min(w - 1, bx * 4 + (i & 3)), y = Math.min(h - 1, by * 4 + (i >> 2));
                    px[i] = img.argb[y * w + x];
                }
                long block = encodeEtc1Block(px);
                int o = (by * bw + bx) * 8;
                for (int i = 0; i < 8; i++) body[o + i] = (byte) (block >>> (56 - 8 * i));
            }
        }
        put32(head, 36, w); put32(head, 40, h); put32(head, 44, 0); put32(head, 48, 0);
        put32(head, 52, 1); put32(head, 56, 1); put32(head, 60, 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + 4 + body.length);
        out.write(head, 0, 64);
        byte[] size = new byte[4]; put32(size, 0, body.length);
        out.write(size, 0, 4);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    // ================================================================ Resizing
    public enum Resize { NONE, HALF, DOUBLE, QUADRUPLE, POWER_OF_TWO }

    public static Image resize(Image img, Resize mode, boolean smooth) throws IOException {
        int w = img.width, h = img.height;
        switch (mode) {
            case HALF: w = Math.max(1, w / 2); h = Math.max(1, h / 2); break;
            case DOUBLE: w *= 2; h *= 2; break;
            case QUADRUPLE: w *= 4; h *= 4; break;
            case POWER_OF_TWO: w = Integer.highestOneBit(w) == w ? w : Integer.highestOneBit(w) << 1;
                h = Integer.highestOneBit(h) == h ? h : Integer.highestOneBit(h) << 1; break;
            default: return img;
        }
        return resizeTo(img, w, h, smooth);
    }

    public static Image resizeTo(Image img, int w, int h, boolean smooth) throws IOException {
        dims(w, h);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (!smooth) {
                out[y * w + x] = img.argb[(y * img.height / h) * img.width + (x * img.width / w)];
                continue;
            }
            double sx = (x + 0.5) * img.width / w - 0.5, sy = (y + 0.5) * img.height / h - 0.5;
            int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
            double fx = sx - x0, fy = sy - y0;
            double[] acc = new double[4];
            for (int k = 0; k < 4; k++) {
                int xx = Math.max(0, Math.min(img.width - 1, x0 + (k & 1)));
                int yy = Math.max(0, Math.min(img.height - 1, y0 + (k >> 1)));
                double wgt = ((k & 1) == 1 ? fx : 1 - fx) * ((k >> 1) == 1 ? fy : 1 - fy);
                int c = img.argb[yy * img.width + xx];
                acc[0] += wgt * (c >>> 24); acc[1] += wgt * ((c >> 16) & 255);
                acc[2] += wgt * ((c >> 8) & 255); acc[3] += wgt * (c & 255);
            }
            out[y * w + x] = argb(clamp(acc[0]), clamp(acc[1]), clamp(acc[2]), clamp(acc[3]));
        }
        return new Image(w, h, out);
    }

    // ================================================================ Palette quantisation + NCGR/NCLR
    /** Reduce to at most {@code max} colours (median cut). Transparent pixels map to index 0. */
    public static int[] quantise(int[] argb, int max) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int c : argb) if ((c >>> 24) >= 128) counts.merge(snap555(c), 1, Integer::sum);
        List<Integer> colours = new ArrayList<>(counts.keySet());
        int slots = max - 1; // index 0 reserved for transparency
        if (colours.size() <= slots) {
            int[] pal = new int[max];
            pal[0] = 0xff000000;
            for (int i = 0; i < colours.size(); i++) pal[i + 1] = colours.get(i);
            for (int i = colours.size() + 1; i < max; i++) pal[i] = 0xff000000;
            return pal;
        }
        List<List<Integer>> boxes = new ArrayList<>();
        boxes.add(colours);
        while (boxes.size() < slots) {
            int pick = -1, bestRange = -1, axis = 0;
            for (int b = 0; b < boxes.size(); b++) {
                List<Integer> box = boxes.get(b);
                if (box.size() < 2) continue;
                for (int ax = 0; ax < 3; ax++) {
                    int lo = 255, hi = 0, sh = 16 - ax * 8;
                    for (int c : box) { int v = (c >> sh) & 255; lo = Math.min(lo, v); hi = Math.max(hi, v); }
                    if (hi - lo > bestRange) { bestRange = hi - lo; pick = b; axis = ax; }
                }
            }
            if (pick < 0) break;
            List<Integer> box = boxes.remove(pick);
            final int sh = 16 - axis * 8;
            box.sort((a, b) -> ((a >> sh) & 255) - ((b >> sh) & 255));
            // Split at the weighted median.
            long total = 0; for (int c : box) total += counts.get(c);
            long acc = 0; int cut = 1;
            for (int i = 0; i < box.size() - 1; i++) { acc += counts.get(box.get(i)); if (acc * 2 >= total) { cut = i + 1; break; } cut = i + 1; }
            boxes.add(new ArrayList<>(box.subList(0, cut)));
            boxes.add(new ArrayList<>(box.subList(cut, box.size())));
        }
        int[] pal = new int[max];
        pal[0] = 0xff000000;
        for (int b = 0; b < boxes.size(); b++) {
            long r = 0, g = 0, bl = 0, n = 0;
            for (int c : boxes.get(b)) { int k = counts.get(c); r += k * ((c >> 16) & 255); g += k * ((c >> 8) & 255); bl += k * (c & 255); n += k; }
            pal[b + 1] = snap555(argb(255, (int) (r / n), (int) (g / n), (int) (bl / n)));
        }
        for (int i = boxes.size() + 1; i < max; i++) pal[i] = 0xff000000;
        return pal;
    }

    /** Round a colour to what the DS can show (BGR555). */
    static int snap555(int c) {
        int r = ((c >> 16) & 255) * 31 / 255, g = ((c >> 8) & 255) * 31 / 255, b = (c & 255) * 31 / 255;
        r = (((c >> 16) & 255) * 31 + 127) / 255; g = (((c >> 8) & 255) * 31 + 127) / 255; b = ((c & 255) * 31 + 127) / 255;
        return argb(255, (r << 3) | (r >>> 2), (g << 3) | (g >>> 2), (b << 3) | (b >>> 2));
    }

    public static final class NitroPair { public final byte[] ncgr, nclr; public final int colours;
        NitroPair(byte[] ncgr, byte[] nclr, int colours) { this.ncgr = ncgr; this.nclr = nclr; this.colours = colours; } }

    /** Build a new tiled NCGR + NCLR from an image (padded to multiples of 8). */
    public static NitroPair toNitro(Image src, boolean eightBit) throws IOException {
        int w = (src.width + 7) / 8 * 8, h = (src.height + 7) / 8 * 8;
        check(w / 8 <= 0xFFFF && h / 8 <= 0xFFFF, "Image too large for NCGR");
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            px[y * w + x] = x < src.width && y < src.height ? src.argb[y * src.width + x] : 0;
        int colours = eightBit ? 256 : 16;
        int[] pal = quantise(px, colours);
        int depth = eightBit ? 4 : 3;
        int tileBytes = eightBit ? 64 : 32;
        int tiles = (w / 8) * (h / 8);
        byte[] ncgr = new byte[0x30 + tiles * tileBytes];
        ncgr[0] = 'R'; ncgr[1] = 'G'; ncgr[2] = 'C'; ncgr[3] = 'N';
        ncgr[4] = (byte) 0xFF; ncgr[5] = (byte) 0xFE; ncgr[6] = 1; ncgr[7] = 1;
        put32(ncgr, 8, ncgr.length); put16(ncgr, 0xC, 0x10); put16(ncgr, 0xE, 1);
        ncgr[0x10] = 'R'; ncgr[0x11] = 'A'; ncgr[0x12] = 'H'; ncgr[0x13] = 'C';
        put32(ncgr, 0x14, ncgr.length - 0x10);
        put16(ncgr, 0x18, h / 8); put16(ncgr, 0x1A, w / 8);
        put32(ncgr, 0x1C, depth); put32(ncgr, 0x20, 0); put32(ncgr, 0x24, 0);
        put32(ncgr, 0x28, tiles * tileBytes); put32(ncgr, 0x2C, 0x18);
        int cols = w / 8;
        int used = 1;
        for (int t = 0; t < tiles; t++) {
            int tx = (t % cols) * 8, ty = (t / cols) * 8;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int c = px[(ty + y) * w + tx + x];
                int idx = (c >>> 24) < 128 ? 0 : 1 + Palettes.nearest(c, Arrays.copyOfRange(pal, 1, colours), colours - 1);
                used = Math.max(used, idx + 1);
                if (eightBit) ncgr[0x30 + t * 64 + y * 8 + x] = (byte) idx;
                else ncgr[0x30 + t * 32 + (y * 8 + x) / 2] |= (byte) ((x & 1) == 0 ? idx : idx << 4);
            }
        }
        int palBytes = 512; // a full 256-entry block works for both depths
        byte[] nclr = new byte[0x28 + palBytes];
        nclr[0] = 'R'; nclr[1] = 'L'; nclr[2] = 'C'; nclr[3] = 'N';
        nclr[4] = (byte) 0xFF; nclr[5] = (byte) 0xFE; nclr[6] = 0; nclr[7] = 1;
        put32(nclr, 8, nclr.length); put16(nclr, 0xC, 0x10); put16(nclr, 0xE, 1);
        nclr[0x10] = 'T'; nclr[0x11] = 'T'; nclr[0x12] = 'L'; nclr[0x13] = 'P';
        put32(nclr, 0x14, nclr.length - 0x10);
        put32(nclr, 0x18, depth); put32(nclr, 0x1C, 0);
        put32(nclr, 0x20, palBytes); put32(nclr, 0x24, 0x10);
        for (int i = 0; i < colours; i++) {
            int c = pal[i];
            int r = ((c >> 16) & 255) >> 3, g = ((c >> 8) & 255) >> 3, b = (c & 255) >> 3;
            put16(nclr, 0x28 + i * 2, r | (g << 5) | (b << 10));
        }
        return new NitroPair(ncgr, nclr, used);
    }
}
