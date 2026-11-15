package com.hcode91.sexonix;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Original SeXoniX stage .dat files are modified indexed PNG streams:
 * width, height, CRC(IHDR), metadata, PLTE x2, IDAT, without PNG signature/IHDR/IEND.
 * Rebuild either palette's PNG without altering the compressed IDAT pixels.
 */
public final class LegacyLevelDecoder {
    public static final class Images {
        public final byte[] brightPng, darkPng;
        public final int width, height;
        Images(byte[] bright, byte[] dark, int width, int height) {
            this.brightPng = bright; this.darkPng = dark;
            this.width = width; this.height = height;
        }
    }

    private static final byte[] SIGNATURE =
            {(byte)137, 80, 78, 71, 13, 10, 26, 10};

    private static int u32(byte[] b, int i) throws IOException {
        if (i < 0 || i + 4 > b.length) throw new IOException("Truncated stage data");
        return ((b[i] & 255) << 24) | ((b[i + 1] & 255) << 16)
                | ((b[i + 2] & 255) << 8) | (b[i + 3] & 255);
    }

    private static final class Chunk {
        final byte[] type, contents;
        final int next;
        Chunk(byte[] type, byte[] contents, int next) {
            this.type = type; this.contents = contents; this.next = next;
        }
    }

    private static Chunk chunk(byte[] raw, int start, String type) throws IOException {
        int size = u32(raw, start);
        if (size < 0 || size > 8 * 1024 * 1024
                || (long) start + size + 12 > raw.length)
            throw new IOException("Invalid " + type + " chunk length");
        byte[] name = Arrays.copyOfRange(raw, start + 4, start + 8);
        if (!Arrays.equals(name, type.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Expected " + type + " chunk");
        byte[] data = Arrays.copyOfRange(raw, start + 8, start + 8 + size);
        int expectedCrc = u32(raw, start + 8 + size);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        if ((int) crc.getValue() != expectedCrc)
            throw new IOException(type + " CRC mismatch");
        return new Chunk(name, data, start + size + 12);
    }

    public static Images decode(byte[] raw) throws IOException {
        if (raw == null || raw.length < 80 || raw.length > 16 * 1024 * 1024)
            throw new IOException("Invalid stage data size");
        int width = u32(raw, 0);
        int height = u32(raw, 4);
        if (width != 128 || height != 128)
            throw new IOException("Expected 128x128 SeXoniX stage, got "
                    + width + "x" + height);
        // The original 24-byte prefix stores the exact PNG IHDR CRC.
        byte[] ihdr = new byte[13];
        put32(ihdr, 0, width);
        put32(ihdr, 4, height);
        ihdr[8] = 8;  // indexed colour, 8-bit
        ihdr[9] = 3;
        CRC32 c = new CRC32();
        c.update("IHDR".getBytes(StandardCharsets.US_ASCII));
        c.update(ihdr);
        if (u32(raw, 8) != (int) c.getValue())
            throw new IOException("Invalid SeXoniX PNG IHDR signature");

        // Each stage has two 8-bit palettes followed by one shared IDAT.
        Chunk bright = chunk(raw, 24, "PLTE");
        Chunk dark = chunk(raw, bright.next, "PLTE");
        Chunk idat = chunk(raw, dark.next, "IDAT");
        if (bright.contents.length == 0 || dark.contents.length == 0
                || bright.contents.length % 3 != 0
                || dark.contents.length % 3 != 0)
            throw new IOException("Invalid palette data");
        if (idat.next != raw.length)
            throw new IOException("Unexpected data after stage image");
        return new Images(png(ihdr, bright.contents, idat.contents),
                png(ihdr, dark.contents, idat.contents), width, height);
    }

    private static void put32(byte[] arr, int index, int value) {
        arr[index] = (byte)(value >>> 24);
        arr[index + 1] = (byte)(value >>> 16);
        arr[index + 2] = (byte)(value >>> 8);
        arr[index + 3] = (byte)value;
    }

    private static void writeChunk(DataOutputStream stream, String name, byte[] data)
            throws IOException {
        byte[] key = name.getBytes(StandardCharsets.US_ASCII);
        stream.writeInt(data.length);
        stream.write(key);
        stream.write(data);
        CRC32 crc = new CRC32();
        crc.update(key);
        crc.update(data);
        stream.writeInt((int)crc.getValue());
    }

    private static byte[] png(byte[] ihdr, byte[] palette, byte[] pixels)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(SIGNATURE);
        writeChunk(out, "IHDR", ihdr);
        writeChunk(out, "PLTE", palette);
        writeChunk(out, "IDAT", pixels);
        writeChunk(out, "IEND", new byte[0]);
        out.flush();
        return bytes.toByteArray();
    }
}
