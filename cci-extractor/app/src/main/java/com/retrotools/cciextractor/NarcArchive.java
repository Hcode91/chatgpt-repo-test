package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Nintendo DS NARC container (BTAF/BTNF/GMIF) reader and same-layout rebuilder. */
public final class NarcArchive {
    public static final int MAX_FILES = 20000;

    public static final class Archive {
        public final byte[] source;
        public final int btaf, btnf, gmif, dataStart;
        public final int[] starts, ends;
        /** Relative paths when the archive stores names; otherwise null entries. */
        public final String[] names;
        Archive(byte[] source, int btaf, int btnf, int gmif, int[] starts, int[] ends, String[] names) {
            this.source = source; this.btaf = btaf; this.btnf = btnf; this.gmif = gmif;
            this.dataStart = gmif + 8; this.starts = starts; this.ends = ends; this.names = names;
        }
        public int count() { return starts.length; }
        public byte[] file(int i) { return Arrays.copyOfRange(source, dataStart + starts[i], dataStart + ends[i]); }
        public boolean hasNames() { for (String n : names) if (n != null) return true; return false; }
    }

    private NarcArchive() {}

    private static int u16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    private static int u32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }
    private static void put32(byte[] b, int o, int v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> (8 * i)); }
    private static boolean magic(byte[] b, int o, String m) {
        if (o < 0 || o + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) if (b[o + i] != (byte) m.charAt(i)) return false;
        return true;
    }

    public static boolean isNarc(byte[] b) { return b != null && b.length >= 0x1C && magic(b, 0, "NARC"); }

    public static Archive parse(byte[] b) throws IOException {
        if (!isNarc(b)) throw new IOException("Not a NARC archive");
        int headerSize = u16(b, 0x0C);
        if (headerSize < 0x10 || headerSize > b.length) throw new IOException("Bad NARC header size");
        int btaf = headerSize;
        if (!magic(b, btaf, "BTAF")) throw new IOException("NARC missing BTAF section");
        int btafSize = u32(b, btaf + 4);
        int count = u16(b, btaf + 8);
        if (btafSize < 12 || count > MAX_FILES || btaf + btafSize > b.length || 12 + 8L * count > btafSize)
            throw new IOException("Bad NARC BTAF section");
        int btnf = btaf + btafSize;
        if (!magic(b, btnf, "BTNF")) throw new IOException("NARC missing BTNF section");
        int btnfSize = u32(b, btnf + 4);
        if (btnfSize < 8 || btnf + btnfSize > b.length) throw new IOException("Bad NARC BTNF section");
        int gmif = btnf + btnfSize;
        if (!magic(b, gmif, "GMIF")) throw new IOException("NARC missing GMIF section");
        int gmifSize = u32(b, gmif + 4);
        int dataLen = b.length - (gmif + 8);
        if (gmifSize >= 8 && gmifSize - 8 <= dataLen) dataLen = gmifSize - 8;
        int[] starts = new int[count], ends = new int[count];
        for (int i = 0; i < count; i++) {
            int s = u32(b, btaf + 12 + i * 8), e = u32(b, btaf + 16 + i * 8);
            if (s < 0 || e < s || e > dataLen) throw new IOException("NARC file " + i + " outside data block");
            starts[i] = s; ends[i] = e;
        }
        String[] names = new String[count];
        try { readNames(b, btnf + 8, btnfSize - 8, names); } catch (RuntimeException | IOException ignored) {
            Arrays.fill(names, null);
        }
        return new Archive(b, btaf, btnf, gmif, starts, ends, names);
    }

    /** Parse the optional DS-style file name table inside BTNF. */
    private static void readNames(byte[] b, int fnt, int len, String[] names) throws IOException {
        if (len < 8) return;
        int firstOffset = u32(b, fnt);
        int dirCount = u16(b, fnt + 6);
        // Unnamed archives store a single root entry with an immediately terminated (or absent) list.
        if (dirCount < 1 || dirCount > 4096 || 8L * dirCount > len || firstOffset >= len) return;
        walk(b, fnt, len, 0, "", dirCount, names, 0);
    }

    private static void walk(byte[] b, int fnt, int len, int dir, String prefix, int dirCount, String[] names, int depth)
            throws IOException {
        if (depth > 32 || dir < 0 || dir >= dirCount) throw new IOException("Bad NARC name tree");
        int cursor = fnt + u32(b, fnt + dir * 8);
        int fileId = u16(b, fnt + dir * 8 + 4);
        int end = fnt + len;
        while (cursor < end) {
            int flags = b[cursor++] & 255;
            if (flags == 0) return;
            int n = flags & 0x7F;
            if (cursor + n > end) throw new IOException("Bad NARC name");
            String name = new String(b, cursor, n, StandardCharsets.US_ASCII);
            cursor += n;
            if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
                throw new IOException("Unsafe NARC name");
            if ((flags & 0x80) != 0) {
                if (cursor + 2 > end) throw new IOException("Bad NARC subdir");
                int child = u16(b, cursor) & 0xFFF; cursor += 2;
                walk(b, fnt, len, child, prefix + name + "/", dirCount, names, depth + 1);
            } else {
                if (fileId < names.length) names[fileId] = prefix + name;
                fileId++;
            }
        }
    }

    /** Return a new NARC with file {@code index} replaced. Data is re-laid out with 4-byte alignment. */
    public static byte[] replace(byte[] original, int index, byte[] replacement) throws IOException {
        Archive a = parse(original);
        if (index < 0 || index >= a.count()) throw new IOException("NARC index out of range");
        ByteArrayOutputStream data = new ByteArrayOutputStream(original.length + replacement.length);
        int[] starts = new int[a.count()], ends = new int[a.count()];
        for (int i = 0; i < a.count(); i++) {
            while (data.size() % 4 != 0) data.write(0xFF);
            byte[] file = i == index ? replacement : a.file(i);
            starts[i] = data.size();
            data.write(file, 0, file.length);
            ends[i] = data.size();
        }
        while (data.size() % 4 != 0) data.write(0xFF);
        byte[] head = Arrays.copyOfRange(original, 0, a.gmif + 8);
        for (int i = 0; i < a.count(); i++) {
            put32(head, a.btaf + 12 + i * 8, starts[i]);
            put32(head, a.btaf + 16 + i * 8, ends[i]);
        }
        byte[] body = data.toByteArray();
        put32(head, a.gmif + 4, body.length + 8);
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        put32(out, 8, out.length);
        return out;
    }

    /** Build an unnamed NARC from files (used by tests). */
    public static byte[] build(List<byte[]> files) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        List<int[]> ranges = new ArrayList<>();
        for (byte[] f : files) {
            while (data.size() % 4 != 0) data.write(0xFF);
            int s = data.size(); data.write(f, 0, f.length); ranges.add(new int[]{s, data.size()});
        }
        while (data.size() % 4 != 0) data.write(0xFF);
        byte[] body = data.toByteArray();
        int btafSize = 12 + 8 * files.size(), btnfSize = 16;
        int total = 0x10 + btafSize + btnfSize + 8 + body.length;
        byte[] out = new byte[total];
        System.arraycopy("NARC".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        out[4] = (byte) 0xFE; out[5] = (byte) 0xFF; out[6] = 0; out[7] = 1;
        put32(out, 8, total); out[0x0C] = 0x10; out[0x0E] = 3;
        int p = 0x10;
        System.arraycopy("BTAF".getBytes(StandardCharsets.US_ASCII), 0, out, p, 4);
        put32(out, p + 4, btafSize); out[p + 8] = (byte) files.size(); out[p + 9] = (byte) (files.size() >>> 8);
        for (int i = 0; i < ranges.size(); i++) { put32(out, p + 12 + i * 8, ranges.get(i)[0]); put32(out, p + 16 + i * 8, ranges.get(i)[1]); }
        p += btafSize;
        System.arraycopy("BTNF".getBytes(StandardCharsets.US_ASCII), 0, out, p, 4);
        put32(out, p + 4, btnfSize); put32(out, p + 8, 4); out[p + 14] = 1;
        p += btnfSize;
        System.arraycopy("GMIF".getBytes(StandardCharsets.US_ASCII), 0, out, p, 4);
        put32(out, p + 4, body.length + 8);
        System.arraycopy(body, 0, out, p + 8, body.length);
        return out;
    }
}
