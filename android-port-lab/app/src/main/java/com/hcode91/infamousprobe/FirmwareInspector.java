package com.hcode91.infamousprobe;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Read-only local metadata inspection of official PS3 SCEUF/PUP update packages.
 * Never extracts encrypted firmware, and never uploads/distributes Sony files.
 */
public final class FirmwareInspector {
    private FirmwareInspector() { }

    private static final long MAX_PUP = 1024L * 1024L * 1024L;
    private static final int MAX_SEGMENTS = 32;
    private static final int MAX_TAR_FILES = 4096;

    private static byte[] get(FileChannel ch, long off, int length, long limit)
            throws IOException {
        if (off < 0 || length < 0 || off > limit || length > limit - off)
            throw new IOException("Firmware offset out of range");
        ByteBuffer b = ByteBuffer.allocate(length);
        long pos = off;
        while (b.hasRemaining()) {
            int n = ch.read(b, pos);
            if (n <= 0) throw new IOException("Truncated PS3 firmware file");
            pos += n;
        }
        return b.array();
    }

    private static long u64(byte[] b, int o) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(b, o, 8).order(ByteOrder.BIG_ENDIAN);
        long val = buffer.getLong();
        if (val < 0) throw new IOException("Invalid firmware numeric field");
        return val;
    }

    private static String asciiz(byte[] data, int off, int len) {
        int end = off;
        while (end < off + len && data[end] != 0) end++;
        return new String(data, off, end - off, StandardCharsets.US_ASCII).trim();
    }

    private static long octal(byte[] b, int off, int n) throws IOException {
        long v = 0;
        boolean seen = false;
        for (int i = off; i < off + n; i++) {
            int c = b[i] & 255;
            if (c == 0 || c == ' ') {
                if (seen) break;
                continue;
            }
            if (c < '0' || c > '7') throw new IOException("Malformed PUP tar file size");
            if (v > (Long.MAX_VALUE >>> 3))
                throw new IOException("PUP tar size overflow");
            v = (v << 3) | (c - '0');
            seen = true;
        }
        return v;
    }

    public static String inspect(ContentResolver resolver, Uri uri) throws IOException {
        // SAF generally supplies a seekable descriptor; a cloud-only document
        // provider may not. In that case report a clear diagnostic.
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("File cannot be opened");
            try (FileInputStream input = new FileInputStream(pfd.getFileDescriptor());
                 FileChannel ch = input.getChannel()) {
                long total = ch.size();
                if (total < 0x290 || total > MAX_PUP)
                    throw new IOException("Unexpected PS3 PUP length: " + total);
                byte[] header = get(ch, 0, 48, total);
                if (header[0] != 'S' || header[1] != 'C' ||
                    header[2] != 'E' || header[3] != 'U' ||
                    header[4] != 'F' || header[5] != 0 ||
                    header[6] != 0 || header[7] != 0)
                    throw new IOException("Not a PS3 SCEUF update package");

                long count = u64(header, 0x18);
                long headerSize = u64(header, 0x20);
                long payloadSize = u64(header, 0x28);
                if (count < 1 || count > MAX_SEGMENTS ||
                    headerSize < 0x30 + count * 0x40 ||
                    headerSize > total ||
                    payloadSize != total - headerSize)
                    throw new IOException("Invalid PS3 PUP header or truncated data");

                byte[] entries = get(ch, 0x30, Math.toIntExact(count * 32), total);
                long updateOff = -1, updateSize = 0;
                String version = "Not listed";
                List<String> sections = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int at = i * 32;
                    long id = u64(entries, at);
                    long offset = u64(entries, at + 8);
                    long size = u64(entries, at + 16);
                    if (offset < headerSize || size > total - offset)
                        throw new IOException("PUP segment extends beyond the update");
                    sections.add(String.format(java.util.Locale.ROOT,
                            "0x%03X: %d bytes", id, size));
                    if (id == 0x100 && size >= 4 && size <= 64) {
                        version = asciiz(get(ch, offset, (int) size, total), 0, (int)size);
                    } else if (id == 0x300) {
                        updateOff = offset;
                        updateSize = size;
                    }
                }
                if (updateOff < 0) throw new IOException("Update_Files.tar section missing");

                long pos = updateOff, end = updateOff + updateSize;
                int entriesSeen = 0, devFlashCount = 0, modulePackageCount = 0;
                StringBuilder examples = new StringBuilder();
                boolean coreOs = false;
                while (pos <= end - 512 && entriesSeen < MAX_TAR_FILES) {
                    byte[] hdr = get(ch, pos, 512, total);
                    if (hdr[0] == 0 && hdr[1] == 0 && hdr[2] == 0) break;
                    String name = asciiz(hdr, 0, 100);
                    long fileSize = octal(hdr, 124, 12);
                    if (name.isEmpty() || name.contains("..") ||
                        name.startsWith("/") || fileSize > end - pos - 512)
                        throw new IOException("Invalid firmware TAR entry");
                    if (name.startsWith("dev_flash")) devFlashCount++;
                    if (name.endsWith(".pkg")) modulePackageCount++;
                    if (name.equals("CORE_OS_PACKAGE.pkg")) coreOs = true;
                    if (name.startsWith("dev_flash") && examples.length() < 350)
                        examples.append("  ").append(name).append('\n');
                    entriesSeen++;
                    long blocks = (fileSize / 512L) + (fileSize % 512L != 0 ? 1 : 0);
                    pos += 512 + blocks * 512L;
                }
                return "PS3 FIRMWARE INSPECTION\n"
                        + "Version: " + version + "\n"
                        + "Size: " + total + " bytes\n"
                        + "Format: SCEUF PS3 PUP\n"
                        + "PUP segments: " + count + "\n"
                        + "Update archive files: " + entriesSeen + "\n"
                        + "dev_flash packages: " + devFlashCount + "\n"
                        + "Additional .pkg entries: " + modulePackageCount + "\n"
                        + "CORE_OS_PACKAGE.pkg: " + (coreOs ? "found" : "not found")
                        + "\n\nSample firmware packages:\n" + examples
                        + "\nNOTE: Package format inspected only. Sony encrypted "
                        + "firmware modules have not been installed/decrypted.\n"
                        + "The PS3 game still requires PPU/SPU execution, "
                        + "firmware import HLE and RSX graphics.\n"
                        + "Your PUP stays on your phone. No network access used.";
            }
        }
    }
}
