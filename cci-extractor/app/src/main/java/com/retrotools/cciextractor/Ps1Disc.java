package com.retrotools.cciextractor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only access to the ISO 9660 file system on a PlayStation disc image: raw .bin (2352-byte sectors),
 * Mode 2 images without sync/header (2336-byte sectors) and plain .iso (2048-byte sectors).
 * <p>
 * Files stored in Mode 2 Form 2 sectors (XA audio, STR video) can't be represented as 2048-byte data, so they
 * are exported sector by sector in raw 2352-byte form, which tools such as jPSXdec read directly.
 */
public final class Ps1Disc {
    private static final byte[] SYNC = {0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0};
    private static final int MAX_FILES = 100000;
    private static final int MAX_DEPTH = 32;

    private Ps1Disc() {}

    /** How sectors are stored in the image. */
    public static final class Layout {
        /** Bytes per sector in the image, and where the 2048 user-data bytes start inside one. */
        public final int sectorSize, dataOffset;
        Layout(int sectorSize, int dataOffset) { this.sectorSize = sectorSize; this.dataOffset = dataOffset; }
        /** True when every sector carries a Mode 2 subheader, so Form 2 data can be recovered. */
        public boolean mode2() { return dataOffset == 24 || dataOffset == 8; }
    }

    public static final class FileEntry {
        public final String path;
        public final long lba, size;
        /** Stored in Form 2 sectors (XA audio, STR video): exported as raw 2352-byte sectors. */
        public final boolean form2;
        FileEntry(String path, long lba, long size, boolean form2) { this.path = path; this.lba = lba; this.size = size; this.form2 = form2; }
        public long sectors() { return (size + 2047) / 2048; }
        /** Bytes written on export. */
        public long exportSize() { return form2 ? sectors() * 2352 : size; }
    }

    public static final class Info {
        public final Layout layout;
        public final String volumeId, systemId;
        /** The boot executable named in SYSTEM.CNF, e.g. SLUS_012.34, or null. */
        public String bootFile;
        public final List<FileEntry> files = new ArrayList<>();
        public long totalBytes;
        Info(Layout layout, String volumeId, String systemId) { this.layout = layout; this.volumeId = volumeId; this.systemId = systemId; }
        public boolean isPlayStation() { return systemId.startsWith("PLAYSTATION"); }
        public String title() {
            if (bootFile != null) return bootFile;
            return volumeId.isEmpty() ? "disc" : volumeId;
        }
        public String description() {
            return (isPlayStation() ? "PlayStation disc" : "CD image (ISO 9660)") +
                    (volumeId.isEmpty() ? "" : " · " + volumeId) + (bootFile == null ? "" : " · " + bootFile) +
                    " · " + files.size() + " files";
        }
    }

    // ------------------------------------------------------------------ detection

    /** Finds the sector layout by looking for the ISO 9660 descriptor at sector 16, or returns null. */
    public static Layout layout(FileChannel ch) throws IOException {
        int[][] candidates = {{2352, 24}, {2352, 16}, {2048, 0}, {2336, 8}};
        for (int[] c : candidates) {
            long at = 16L * c[0] + c[1];
            if (at + 6 > ch.size()) continue;
            byte[] pvd = read(ch, at, 6);
            if (pvd[0] != 1 || pvd[1] != 'C' || pvd[2] != 'D' || pvd[3] != '0' || pvd[4] != '0' || pvd[5] != '1') continue;
            if (c[0] == 2352) {
                byte[] head = read(ch, 16L * 2352, 16);
                for (int i = 0; i < 12; i++) if (head[i] != SYNC[i]) return null;
                int mode = head[15] & 255;
                if ((mode == 2) != (c[1] == 24)) continue;      // Mode 2 has an 8-byte subheader, Mode 1 doesn't
            }
            return new Layout(c[0], c[1]);
        }
        return null;
    }

    public static boolean isDisc(FileChannel ch) throws IOException { return layout(ch) != null; }

    // ------------------------------------------------------------------ file system

    /** Reads the volume descriptor and walks every directory. */
    public static Info parse(FileChannel ch) throws IOException {
        Layout layout = layout(ch);
        if (layout == null) throw new IOException("Not a CD image with an ISO 9660 file system");
        byte[] pvd = sector(ch, layout, 16);
        String system = text(pvd, 8, 32), volume = text(pvd, 40, 32);
        Info info = new Info(layout, volume, system);
        long rootLba = u32(pvd, 156 + 2), rootSize = u32(pvd, 156 + 10);
        Set<Long> visited = new HashSet<>();
        walk(ch, layout, info, rootLba, rootSize, "", 0, visited);
        for (FileEntry f : info.files) {
            info.totalBytes += f.exportSize();
            if (f.path.equalsIgnoreCase("SYSTEM.CNF") && f.size < 4096 && !f.form2) {
                String cnf = new String(read(ch, layout, f), StandardCharsets.ISO_8859_1);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("BOOT\\s*=\\s*cdrom:\\\\?([^;\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(cnf);
                if (m.find()) info.bootFile = m.group(1).replace('\\', '/');
            }
        }
        return info;
    }

    private static void walk(FileChannel ch, Layout layout, Info info, long lba, long size, String prefix, int depth,
                             Set<Long> visited) throws IOException {
        if (depth > MAX_DEPTH || !visited.add(lba)) return;
        long sectors = (size + 2047) / 2048;
        if (sectors > 4096) throw new IOException("Directory at sector " + lba + " is unreasonably large");
        for (long s = 0; s < sectors; s++) {
            byte[] d = sector(ch, layout, lba + s);
            int pos = 0;
            while (pos < 2048) {
                int len = d[pos] & 255;
                if (len == 0) break;                                // rest of this sector is padding
                if (len < 34 || pos + len > 2048) break;
                long fileLba = u32(d, pos + 2), fileSize = u32(d, pos + 10);
                int flags = d[pos + 25] & 255, nameLen = d[pos + 32] & 255;
                if (33 + nameLen > len) break;
                boolean special = nameLen == 1 && (d[pos + 33] == 0 || d[pos + 33] == 1);
                if (!special) {
                    String name = cleanName(new String(d, pos + 33, nameLen, StandardCharsets.ISO_8859_1));
                    if ((flags & 2) != 0) {
                        walk(ch, layout, info, fileLba, fileSize, prefix + name + "/", depth + 1, visited);
                    } else {
                        if (info.files.size() >= MAX_FILES) throw new IOException("Too many files on this disc");
                        boolean form2 = layout.mode2() && isForm2(ch, layout, d, pos, len, nameLen, fileLba, fileSize);
                        info.files.add(new FileEntry(prefix + name, fileLba, fileSize, form2));
                    }
                }
                pos += len;
            }
        }
    }

    /**
     * Form 2 detection: the XA extension of the directory record (attributes 0x1000 Form 2, 0x2000 interleaved,
     * 0x4000 CD audio), or failing that the subheader of the file's first sector (submode bit 5).
     */
    private static boolean isForm2(FileChannel ch, Layout layout, byte[] d, int pos, int len, int nameLen, long lba, long size)
            throws IOException {
        int su = pos + 33 + nameLen + ((nameLen & 1) == 0 ? 1 : 0);
        if (su + 14 <= pos + len && d[su + 6] == 'X' && d[su + 7] == 'A') {
            int attr = (d[su + 4] & 255) << 8 | (d[su + 5] & 255);
            if ((attr & 0x7000) != 0) return true;
        }
        if (size == 0) return false;
        long at = lba * layout.sectorSize + layout.dataOffset - 8;
        if (at < 0 || at + 8 > ch.size()) return false;
        byte[] sub = read(ch, at, 8);
        return (sub[2] & 0x20) != 0 && (sub[2] & 255) == (sub[6] & 255);
    }

    // ------------------------------------------------------------------ reading files

    /** Reads a whole (non-Form-2) file into memory. */
    public static byte[] read(FileChannel ch, Layout layout, FileEntry f) throws IOException {
        if (f.size > Integer.MAX_VALUE - 16) throw new IOException(f.path + " is too large to load");
        byte[] out = new byte[(int) f.exportSize()];
        copy(ch, layout, f, new OutputStream() {
            int at;
            @Override public void write(int b) { out[at++] = (byte) b; }
            @Override public void write(byte[] b, int off, int len) { System.arraycopy(b, off, out, at, len); at += len; }
        });
        return out;
    }

    /**
     * Streams a file to {@code out}: user data for normal files, whole 2352-byte sectors for Form 2 files
     * (sync and header are rebuilt when the image is 2336-byte Mode 2). Reads in batches of sectors.
     */
    public static void copy(FileChannel ch, Layout layout, FileEntry f, OutputStream out) throws IOException {
        final int batch = 64;
        long remaining = f.size, sectors = f.sectors();
        ByteBuffer buf = ByteBuffer.allocate(batch * layout.sectorSize);
        byte[] raw = new byte[2352];
        for (long s = 0; s < sectors; s += batch) {
            int n = (int) Math.min(batch, sectors - s);
            buf.clear();
            buf.limit(n * layout.sectorSize);
            long pos = (f.lba + s) * layout.sectorSize;
            while (buf.hasRemaining()) {
                int r = ch.read(buf, pos + buf.position());
                if (r < 0) throw new IOException(f.path + " runs past the end of the disc image");
            }
            byte[] b = buf.array();
            for (int i = 0; i < n; i++) {
                int base = i * layout.sectorSize;
                if (f.form2) {
                    if (layout.sectorSize == 2352) out.write(b, base, 2352);
                    else {
                        System.arraycopy(SYNC, 0, raw, 0, 12);
                        msf(f.lba + s + i, raw);
                        raw[15] = 2;
                        System.arraycopy(b, base, raw, 16, 2336);
                        out.write(raw, 0, 2352);
                    }
                } else {
                    int take = (int) Math.min(2048, remaining);
                    out.write(b, base + layout.dataOffset, take);
                    remaining -= take;
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Sector header address: minutes, seconds, frames in BCD, with the 2-second lead-in. */
    private static void msf(long lba, byte[] raw) {
        long a = lba + 150;
        int m = (int) (a / 4500), sec = (int) (a / 75 % 60), fr = (int) (a % 75);
        raw[12] = (byte) ((m / 10) << 4 | m % 10);
        raw[13] = (byte) ((sec / 10) << 4 | sec % 10);
        raw[14] = (byte) ((fr / 10) << 4 | fr % 10);
    }

    static String cleanName(String name) {
        int semi = name.indexOf(';');
        if (semi >= 0) name = name.substring(0, semi);
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) sb.append(c < 0x20 || c > 0x7e || "\\/:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        String s = sb.toString().trim();
        return s.isEmpty() || s.equals("..") ? String.format(Locale.ROOT, "unnamed") : s;
    }

    private static byte[] sector(FileChannel ch, Layout layout, long lba) throws IOException {
        long at = lba * layout.sectorSize + layout.dataOffset;
        if (lba < 0 || at + 2048 > ch.size()) throw new IOException("Sector " + lba + " lies outside the disc image");
        return read(ch, at, 2048);
    }
    private static byte[] read(FileChannel ch, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            int n = ch.read(buf, offset + buf.position());
            if (n < 0) throw new IOException("Unexpected end of disc image");
        }
        return buf.array();
    }
    private static String text(byte[] d, int at, int len) { return new String(d, at, len, StandardCharsets.ISO_8859_1).trim(); }
    private static long u32(byte[] d, int o) {
        return (d[o] & 255L) | (d[o + 1] & 255L) << 8 | (d[o + 2] & 255L) << 16 | (d[o + 3] & 255L) << 24;
    }

    // ------------------------------------------------------------------ writing a file back

    // EDC/ECC as in ECMA-130 (the tables and walk match Neill Corlett's ECM tools)
    private static final int[] ECC_F = new int[256], ECC_B = new int[256], EDC = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int j = (i << 1) ^ ((i & 0x80) != 0 ? 0x11D : 0);
            ECC_F[i] = j;
            ECC_B[i ^ j] = i;
            int edc = i;
            for (int k = 0; k < 8; k++) edc = (edc >>> 1) ^ ((edc & 1) != 0 ? 0xD8018001 : 0);
            EDC[i] = edc;
        }
    }

    static int edc(byte[] b, int off, int len) {
        int edc = 0;
        for (int i = 0; i < len; i++) edc = (edc >>> 8) ^ EDC[(edc ^ b[off + i]) & 0xFF];
        return edc;
    }

    /** One ECC block (P or Q) over the 4 address bytes (zeros if {@code zeroAddress}) and the data from 0x10. */
    private static void eccBlock(byte[] s, boolean zeroAddress, int majorCount, int minorCount, int majorMult, int minorInc, int dest) {
        int size = majorCount * minorCount;
        for (int major = 0; major < majorCount; major++) {
            int index = (major >> 1) * majorMult + (major & 1);
            int a = 0, b = 0;
            for (int minor = 0; minor < minorCount; minor++) {
                int t = index < 4 ? (zeroAddress ? 0 : s[0x0C + index] & 0xFF) : s[0x10 + index - 4] & 0xFF;
                index += minorInc;
                if (index >= size) index -= size;
                a ^= t;
                b ^= t;
                a = ECC_F[a];
            }
            a = ECC_B[ECC_F[a] ^ b];
            s[dest + major] = (byte) a;
            s[dest + major + majorCount] = (byte) (a ^ b);
        }
    }

    private static void put32(byte[] b, int at, int v) {
        b[at] = (byte) v; b[at + 1] = (byte) (v >> 8); b[at + 2] = (byte) (v >> 16); b[at + 3] = (byte) (v >>> 24);
    }

    /**
     * Recomputes the EDC and ECC of a raw 2352-byte sector after its data changed: Mode 1, or
     * Mode 2 Form 1 (Form 2 has no ECC, and its EDC is optional; neither is touched here).
     */
    static void fixSector(byte[] s) throws IOException {
        int mode = s[15] & 0xFF;
        if (mode == 1) {
            put32(s, 0x810, edc(s, 0, 0x810));
            for (int i = 0x814; i < 0x81C; i++) s[i] = 0;
            eccBlock(s, false, 86, 24, 2, 86, 0x81C);
            eccBlock(s, false, 52, 43, 86, 88, 0x81C + 0xAC);
        } else if (mode == 2) {
            if ((s[0x12] & 0x20) != 0) throw new IOException("Sector is Mode 2 Form 2; only Form 1 data can be replaced");
            put32(s, 0x818, edc(s, 0x10, 0x808));
            eccBlock(s, true, 86, 24, 2, 86, 0x81C);
            eccBlock(s, true, 52, 43, 86, 88, 0x81C + 0xAC);
        } else {
            throw new IOException("Unknown sector mode " + mode);
        }
    }

    /** Progress in bytes written. */
    public interface Progress { void at(long written, long total); }

    /**
     * Writes a copy of the disc image to {@code out} with {@code f}'s contents replaced by
     * {@code data}, which must be exactly the same size. Error codes of the changed sectors are
     * recomputed; every other byte is copied unchanged.
     */
    public static void replaceFile(FileChannel disc, Layout layout, FileEntry f, FileChannel data, OutputStream out, Progress progress)
            throws IOException {
        if (f.form2) throw new IOException(f.path + " is stored as XA audio / video sectors and can't be replaced");
        if (data.size() != f.size)
            throw new IOException("The new " + f.path + " is " + data.size() + " bytes; it has to be exactly " + f.size + " bytes");
        long total = disc.size();
        long first = f.lba, last = f.lba + f.sectors();     // sectors [first, last)
        if (last * layout.sectorSize > total) throw new IOException(f.path + " runs past the end of the disc image");
        byte[] buffer = new byte[layout.sectorSize * 256];
        long pos = 0, next = 0;
        // Before the file
        while (pos < first * layout.sectorSize) {
            int n = (int) Math.min(buffer.length, first * layout.sectorSize - pos);
            readFully(disc, pos, buffer, n);
            out.write(buffer, 0, n);
            pos += n;
            if (progress != null && pos >= next) { progress.at(pos, total); next = pos + (16 << 20); }
        }
        // The file, sector by sector
        byte[] raw = new byte[2352], user = new byte[2048];
        byte[] sector = new byte[layout.sectorSize];
        for (long lba = first; lba < last; lba++) {
            readFully(disc, lba * layout.sectorSize, sector, layout.sectorSize);
            long fileOffset = (lba - first) * 2048;
            int n = (int) Math.min(2048, f.size - fileOffset);
            readFully(data, fileOffset, user, n);
            System.arraycopy(user, 0, sector, layout.dataOffset, n);
            if (layout.sectorSize == 2352) {
                fixSector(sector);
            } else if (layout.sectorSize == 2336) {
                // No sync/header in the image: rebuild them around it just to compute the codes
                java.util.Arrays.fill(raw, 0, 16, (byte) 0);
                raw[15] = 2;
                System.arraycopy(sector, 0, raw, 16, 2336);
                fixSector(raw);
                System.arraycopy(raw, 16, sector, 0, 2336);
            }
            out.write(sector);
            pos += layout.sectorSize;
            if (progress != null && pos >= next) { progress.at(pos, total); next = pos + (16 << 20); }
        }
        // After the file
        while (pos < total) {
            int n = (int) Math.min(buffer.length, total - pos);
            readFully(disc, pos, buffer, n);
            out.write(buffer, 0, n);
            pos += n;
            if (progress != null && pos >= next) { progress.at(pos, total); next = pos + (16 << 20); }
        }
    }

    private static void readFully(FileChannel ch, long at, byte[] into, int n) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(into, 0, n);
        while (bb.hasRemaining()) if (ch.read(bb, at + bb.position()) < 0) throw new IOException("Unexpected end of file");
    }
}
