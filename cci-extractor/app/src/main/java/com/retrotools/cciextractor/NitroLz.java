package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Nintendo BIOS-style LZ77 codecs used by GBA and DS games.
 * Type 0x10 ("LZ10", the GBA BIOS LZ77UnComp format) and type 0x11 ("LZ11", common in DS games).
 */
public final class NitroLz {
    public static final int TYPE_NONE = 0, TYPE_LZ10 = 0x10, TYPE_LZ11 = 0x11;
    public static final int MAX_OUTPUT = 32 * 1024 * 1024;

    public static final class Result {
        public final byte[] data;
        public final int type;
        /** Number of compressed input bytes actually consumed. */
        public final int consumed;
        Result(byte[] data, int type, int consumed) { this.data = data; this.type = type; this.consumed = consumed; }
    }

    private NitroLz() {}

    public static int declaredSize(byte[] in, int offset) {
        if (offset + 4 > in.length) return -1;
        int size = (in[offset + 1] & 255) | ((in[offset + 2] & 255) << 8) | ((in[offset + 3] & 255) << 16);
        if (size == 0 && offset + 8 <= in.length) {
            size = (in[offset + 4] & 255) | ((in[offset + 5] & 255) << 8) |
                    ((in[offset + 6] & 255) << 16) | ((in[offset + 7] & 255) << 24);
        }
        return size;
    }

    /** Decompress LZ10/LZ11 data starting at {@code offset}. Throws on any malformed reference. */
    public static Result decompress(byte[] in, int offset, int maxOutput) throws IOException {
        if (offset < 0 || offset + 4 > in.length) throw new IOException("LZ data truncated");
        int type = in[offset] & 255;
        if (type != TYPE_LZ10 && type != TYPE_LZ11) throw new IOException("Not LZ10/LZ11 compressed data");
        int size = (in[offset + 1] & 255) | ((in[offset + 2] & 255) << 8) | ((in[offset + 3] & 255) << 16);
        int src = offset + 4;
        if (size == 0) {
            if (offset + 8 > in.length) throw new IOException("LZ extended header truncated");
            size = (in[offset + 4] & 255) | ((in[offset + 5] & 255) << 8) |
                    ((in[offset + 6] & 255) << 16) | ((in[offset + 7] & 255) << 24);
            src = offset + 8;
        }
        if (size <= 0 || size > maxOutput) throw new IOException("LZ output size out of range: " + size);
        byte[] out = new byte[size];
        int dst = 0;
        while (dst < size) {
            if (src >= in.length) throw new IOException("LZ stream ended early");
            int flags = in[src++] & 255;
            for (int bit = 0; bit < 8 && dst < size; bit++) {
                if ((flags & (0x80 >>> bit)) == 0) {
                    if (src >= in.length) throw new IOException("LZ literal truncated");
                    out[dst++] = in[src++];
                    continue;
                }
                int length, disp;
                if (type == TYPE_LZ10) {
                    if (src + 2 > in.length) throw new IOException("LZ10 reference truncated");
                    int b1 = in[src++] & 255, b2 = in[src++] & 255;
                    length = (b1 >>> 4) + 3;
                    disp = (((b1 & 15) << 8) | b2) + 1;
                } else {
                    if (src >= in.length) throw new IOException("LZ11 reference truncated");
                    int b1 = in[src] & 255, ind = b1 >>> 4;
                    if (ind == 0) {
                        if (src + 3 > in.length) throw new IOException("LZ11 reference truncated");
                        int b2 = in[src + 1] & 255, b3 = in[src + 2] & 255;
                        length = (((b1 & 15) << 4) | (b2 >>> 4)) + 0x11;
                        disp = (((b2 & 15) << 8) | b3) + 1;
                        src += 3;
                    } else if (ind == 1) {
                        if (src + 4 > in.length) throw new IOException("LZ11 reference truncated");
                        int b2 = in[src + 1] & 255, b3 = in[src + 2] & 255, b4 = in[src + 3] & 255;
                        length = (((b1 & 15) << 12) | (b2 << 4) | (b3 >>> 4)) + 0x111;
                        disp = (((b3 & 15) << 8) | b4) + 1;
                        src += 4;
                    } else {
                        if (src + 2 > in.length) throw new IOException("LZ11 reference truncated");
                        int b2 = in[src + 1] & 255;
                        length = ind + 1;
                        disp = (((b1 & 15) << 8) | b2) + 1;
                        src += 2;
                    }
                }
                if (disp > dst) throw new IOException("LZ back-reference before start of output");
                for (int i = 0; i < length && dst < size; i++, dst++) out[dst] = out[dst - disp];
            }
        }
        return new Result(out, type, src - offset);
    }

    /**
     * True when the bytes decompress cleanly and the compressed stream uses (almost) the entire input.
     * Used to avoid treating arbitrary data that happens to start with 0x10/0x11 as compressed.
     */
    public static Result tryWhole(byte[] in) {
        if (in == null || in.length < 5) return null;
        int t = in[0] & 255;
        if (t != TYPE_LZ10 && t != TYPE_LZ11) return null;
        int declared = declaredSize(in, 0);
        if (declared < 4 || declared > MAX_OUTPUT || declared < in.length / 9) return null;
        try {
            Result r = decompress(in, 0, MAX_OUTPUT);
            // Files are padded to 4 bytes at most in practice; allow a little slack.
            if (in.length - r.consumed > 16) return null;
            return r;
        } catch (IOException e) {
            return null;
        }
    }

    /** Compress with the same type as the original. */
    public static byte[] compress(byte[] data, int type) {
        if (type == TYPE_LZ11) return compressLz11(data);
        if (type == TYPE_LZ10) return compressLz10(data);
        throw new IllegalArgumentException("Unknown LZ type " + type);
    }

    private static final int WINDOW = 4096, HASH = 1 << 15, MAX_CHAIN = 256;

    /** Simple hash-chain match finder. minDisp=2 keeps output safe for GBA VRAM 16-bit decompression. */
    private static final class Matcher {
        final byte[] d; final int[] head = new int[HASH]; final int[] prev;
        Matcher(byte[] d) { this.d = d; prev = new int[Math.max(1, d.length)]; java.util.Arrays.fill(head, -1); }
        int hash(int p) { return (((d[p] & 255) << 10) ^ ((d[p + 1] & 255) << 5) ^ (d[p + 2] & 255)) & (HASH - 1); }
        void insert(int p) { if (p + 2 < d.length) { int h = hash(p); prev[p] = head[h]; head[h] = p; } }
        /** returns (length<<16)|disp or 0. */
        long find(int p, int maxLen, int minDisp) {
            if (p + 2 >= d.length) return 0;
            int limit = Math.min(maxLen, d.length - p);
            int bestLen = 0, bestDisp = 0, cand = head[hash(p)], steps = 0;
            while (cand >= 0 && steps++ < MAX_CHAIN) {
                int disp = p - cand;
                if (disp > WINDOW) break;
                if (disp >= minDisp) {
                    int l = 0;
                    while (l < limit && d[cand + l] == d[p + l]) l++;
                    if (l > bestLen) { bestLen = l; bestDisp = disp; if (l == limit) break; }
                }
                cand = prev[cand];
            }
            return bestLen >= 3 ? (((long) bestLen) << 16) | bestDisp : 0;
        }
    }

    private static void header(ByteArrayOutputStream out, int type, int size) {
        if (size > 0xFFFFFF) {
            out.write(type); out.write(0); out.write(0); out.write(0);
            out.write(size); out.write(size >>> 8); out.write(size >>> 16); out.write(size >>> 24);
        } else {
            out.write(type); out.write(size); out.write(size >>> 8); out.write(size >>> 16);
        }
    }

    public static byte[] compressLz10(byte[] data) { return compressImpl(data, TYPE_LZ10); }
    public static byte[] compressLz11(byte[] data) { return compressImpl(data, TYPE_LZ11); }

    private static byte[] compressImpl(byte[] data, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 16);
        header(out, type, data.length);
        Matcher m = new Matcher(data);
        int maxLen = type == TYPE_LZ10 ? 18 : 0x10110;
        int p = 0;
        byte[] block = new byte[8 * 4];
        while (p < data.length) {
            int flags = 0, used = 0;
            for (int bit = 0; bit < 8 && p < data.length; bit++) {
                long match = m.find(p, maxLen, 2);
                if (match != 0) {
                    int len = (int) (match >>> 16), disp = (int) (match & 0xFFFF) - 1;
                    flags |= 0x80 >>> bit;
                    if (type == TYPE_LZ10) {
                        block[used++] = (byte) (((len - 3) << 4) | (disp >>> 8));
                        block[used++] = (byte) disp;
                    } else if (len <= 0x10) {
                        block[used++] = (byte) (((len - 1) << 4) | (disp >>> 8));
                        block[used++] = (byte) disp;
                    } else if (len <= 0x110) {
                        int l = len - 0x11;
                        block[used++] = (byte) (l >>> 4);
                        block[used++] = (byte) (((l & 15) << 4) | (disp >>> 8));
                        block[used++] = (byte) disp;
                    } else {
                        int l = len - 0x111;
                        block[used++] = (byte) (0x10 | (l >>> 12));
                        block[used++] = (byte) (l >>> 4);
                        block[used++] = (byte) (((l & 15) << 4) | (disp >>> 8));
                        block[used++] = (byte) disp;
                    }
                    for (int i = 0; i < len; i++) m.insert(p + i);
                    p += len;
                } else {
                    block[used++] = data[p];
                    m.insert(p);
                    p++;
                }
            }
            out.write(flags);
            out.write(block, 0, used);
        }
        while (out.size() % 4 != 0) out.write(0);
        return out.toByteArray();
    }
}
