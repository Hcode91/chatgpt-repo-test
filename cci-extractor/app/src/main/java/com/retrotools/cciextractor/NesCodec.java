package com.retrotools.cciextractor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/** Standard iNES 1.0 parsing and lossless NES CHR two-bit planar tile conversions.
 *  For CHR-RAM titles, PRG scans are EXPLORATORY, not verified game graphics.
 *  No copyrighted assets or ROM data are bundled. */
final class NesCodec {
    static final int PAGE_BYTES = 4096;
    static final int SHEET_WIDTH = 128;
    static final int SHEET_HEIGHT = 128;
    static final int[] GRAYS = {0, 85, 170, 255};

    static final class Info {
        final int mapper, prgBanks, chrBanks;
        final long prgOffset, chrOffset, prgBytes, chrBytes, totalBytes;
        final boolean trainer;
        Info(int mapper, int prgBanks, int chrBanks, long prgOffset, long chrOffset,
             long prgBytes, long chrBytes, long totalBytes, boolean trainer) {
            this.mapper = mapper; this.prgBanks = prgBanks; this.chrBanks = chrBanks;
            this.prgOffset = prgOffset; this.chrOffset = chrOffset;
            this.prgBytes = prgBytes; this.chrBytes = chrBytes;
            this.totalBytes = totalBytes; this.trainer = trainer;
        }
        boolean usesChrRam() { return chrBytes == 0; }
        int pageCount() { return (int)((usesChrRam() ? prgBytes : chrBytes) / PAGE_BYTES); }
        long pageOffset(int page) {
            if (page < 0 || page >= pageCount()) throw new IllegalArgumentException("Invalid page " + page);
            return (usesChrRam() ? prgOffset : chrOffset) + (long)page * PAGE_BYTES;
        }
        String description() {
            return "NES · Mapper " + mapper + " · PRG " + (prgBytes / 1024) + " KiB · " +
                    (usesChrRam() ? "CHR-RAM (PRG scan only)" : "CHR-ROM " + (chrBytes / 1024) + " KiB");
        }
    }

    static boolean isNes(FileChannel channel) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(4);
        if (channel.read(head, 0) != 4) return false;
        return Arrays.equals(head.array(), new byte[] {'N', 'E', 'S', 0x1a});
    }

    static Info parse(FileChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(16);
        readExact(channel, header, 0);
        byte[] b = header.array();
        if (b[0] != 'N' || b[1] != 'E' || b[2] != 'S' || b[3] != 0x1a)
            throw new IOException("Not an iNES .nes ROM");
        int flags6 = b[6] & 255, flags7 = b[7] & 255;
        if ((flags7 & 0x0c) == 0x08)
            throw new IOException("NES 2.0 headers are not supported yet. No files were changed.");
        int prg = b[4] & 255, chr = b[5] & 255;
        if (prg == 0) throw new IOException("Invalid iNES ROM: zero PRG banks");
        int mapper = (flags6 >>> 4) | (flags7 & 0xf0);
        long po = 16L + ((flags6 & 4) != 0 ? 512L : 0);
        long pb = prg * 16384L, cb = chr * 8192L;
        if (po + pb + cb > channel.size())
            throw new IOException("Truncated iNES ROM: program/graphics data missing");
        return new Info(mapper, prg, chr, po, po + pb, pb, cb,
                channel.size(), (flags6 & 4) != 0);
    }

    private static void readExact(FileChannel channel, ByteBuffer destination, long offset) throws IOException {
        while (destination.hasRemaining()) {
            int read = channel.read(destination, offset + destination.position());
            if (read <= 0) throw new IOException("Unexpected end of NES ROM");
        }
    }

    static byte[] readPage(FileChannel channel, Info info, int page) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(PAGE_BYTES);
        readExact(channel, buf, info.pageOffset(page));
        return buf.array();
    }

    /** 256 tiles x 16 bytes per tile, placed in a 16x16 tile, 128x128 pixel sheet. */
    static int[] decodeTiles(byte[] data) throws IOException {
        if (data.length != PAGE_BYTES) throw new IOException("Expected a 4096-byte CHR tile page");
        int[] pixels = new int[SHEET_WIDTH * SHEET_HEIGHT];
        for (int tile = 0; tile < 256; tile++) {
            int tx = (tile % 16) * 8, ty = (tile / 16) * 8;
            for (int y = 0; y < 8; y++) {
                int lo = data[tile * 16 + y] & 255;
                int hi = data[tile * 16 + y + 8] & 255;
                for (int x = 0; x < 8; x++) {
                    int colorIndex = ((lo >>> (7 - x)) & 1) | (((hi >>> (7 - x)) & 1) << 1);
                    int c = GRAYS[colorIndex];
                    pixels[(ty + y) * SHEET_WIDTH + tx + x] = 0xff000000 | (c << 16) | (c << 8) | c;
                }
            }
        }
        return pixels;
    }

    /** Strictly indexed grayscale import; converting arbitrary colours would destroy palette indices. */
    static byte[] encodeTiles(int[] pixels, int width, int height) throws IOException {
        if (width != SHEET_WIDTH || height != SHEET_HEIGHT || pixels.length != width * height)
            throw new IOException("NES tile sheets must be 128 × 128 pixels (16 × 16 tiles)");
        byte[] result = new byte[PAGE_BYTES];
        for (int tile = 0; tile < 256; tile++) {
            int tx = (tile % 16) * 8, ty = (tile / 16) * 8;
            for (int y = 0; y < 8; y++) {
                int lo = 0, hi = 0;
                for (int x = 0; x < 8; x++) {
                    int p = pixels[(ty + y) * SHEET_WIDTH + tx + x];
                    int a = (p >>> 24) & 255;
                    int r = (p >>> 16) & 255, g = (p >>> 8) & 255, b = p & 255;
                    if (a != 255 || r != g || g != b || (r != 0 && r != 85 && r != 170 && r != 255))
                        throw new IOException("PNG contains unsupported colour at pixel " + (tx+x) + "," + (ty+y) +
                                ". Use only opaque grayscale #000000, #555555, #AAAAAA and #FFFFFF. " +
                                "NES tile palette colours are stored elsewhere.");
                    int index = r / 85;
                    lo |= (index & 1) << (7-x);
                    hi |= (index >>> 1) << (7-x);
                }
                result[tile*16+y] = (byte)lo;
                result[tile*16+y+8] = (byte)hi;
            }
        }
        return result;
    }

    /** Copy unchanged bytes, replacing only the selected tile page. Never overwrite input. */
    static void rebuild(FileChannel channel, OutputStream output, Info info,
                        int page, byte[] replacement) throws IOException {
        if (info.usesChrRam())
            throw new IOException("CHR-RAM games cannot be rebuilt from a raw PRG scan safely. " +
                    "Their graphics may be compressed or mixed with program code.");
        if (replacement.length != PAGE_BYTES) throw new IOException("Wrong replacement page size");
        long patchStart = info.pageOffset(page), patchEnd = patchStart + PAGE_BYTES;
        byte[] buffer = new byte[65536];
        for (long position = 0; position < info.totalBytes; ) {
            int amount = (int)Math.min(buffer.length, info.totalBytes - position);
            ByteBuffer dst = ByteBuffer.wrap(buffer, 0, amount);
            readExact(channel, dst, position);
            long overlapStart = Math.max(position, patchStart);
            long overlapEnd = Math.min(position + amount, patchEnd);
            if (overlapStart < overlapEnd)
                System.arraycopy(replacement, (int)(overlapStart - patchStart),
                        buffer, (int)(overlapStart - position), (int)(overlapEnd - overlapStart));
            output.write(buffer, 0, amount);
            position += amount;
        }
    }
    private NesCodec() {}
}
