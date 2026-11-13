package com.retrotools.cciextractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PS3 trophy packs (TROPHY.TRP), read and written the way RPCS3's TRPLoader reads them.
 *
 * <pre>
 * Header (0x40, big-endian): u32 magic 0xDCA24D00, u32 version, u64 file size, u32 file count, u32 entry size (0x40),
 *   u32 dev flag, u8[20] SHA-1, u8[16] padding.
 * Entries (0x40 each): char[32] name, u64 offset, u64 size, u32 flag, u8[12] padding.
 * Data: each file 16-byte aligned, zero padding; the file size is padded too.
 * Version 2 and later: SHA-1 of the whole file with the hash field zeroed (RPCS3 rejects a mismatch).
 * </pre>
 * TROPCONF.SFM / TROP.SFM / TROP_xx.SFM are XML. Their "Sce-Np-Trophy-Signature" comment is checked by a real PS3
 * but not by RPCS3. ESFM files (PS Vita / PS4 style) are encrypted and kept as they are.
 */
public final class Ps3Trophy {
    public static final int MAGIC = 0xDCA24D00;
    private static final int HEADER = 0x40, ENTRY = 0x40, MAX_FILES = 4096;

    private Ps3Trophy() {}

    public static final class FileEntry {
        public final String name;
        public final long offset, size;
        public final int flag;
        FileEntry(String name, long offset, long size, int flag) { this.name = name; this.offset = offset; this.size = size; this.flag = flag; }
    }

    public static final class Info {
        public int version;
        public boolean hashed, hashValid;
        public String commId = "", title = "";
        public int trophies;
        public final List<FileEntry> files = new ArrayList<>();
        public long totalBytes;
        public String description() {
            return "PS3 trophy pack" + (title.isEmpty() ? "" : " · " + title) + (commId.isEmpty() ? "" : " (" + commId + ")") +
                    " · " + trophies + " trophies · v" + version + (hashed ? (hashValid ? ", checksum OK" : ", CHECKSUM WRONG") : "");
        }
    }

    // ------------------------------------------------------------------ reading

    public static boolean isTrophyPack(FileChannel ch) throws IOException {
        return ch.size() >= HEADER && ByteBuffer.wrap(read(ch, 0, 4)).getInt() == MAGIC;
    }

    public static Info parse(FileChannel ch) throws IOException {
        if (!isTrophyPack(ch)) throw new IOException("Not a PS3 trophy pack (TROPHY.TRP)");
        ByteBuffer h = ByteBuffer.wrap(read(ch, 0, HEADER));
        Info info = new Info();
        h.getInt();
        info.version = h.getInt();
        long fileSize = h.getLong();
        int count = h.getInt(), entrySize = h.getInt();
        if (count < 0 || count > MAX_FILES || entrySize < 0x30 || entrySize > 0x100 || HEADER + (long) count * entrySize > ch.size())
            throw new IOException("Invalid trophy pack header");
        byte[] table = read(ch, HEADER, count * entrySize);
        for (int i = 0; i < count; i++) {
            int e = i * entrySize;
            int end = e;
            while (end < e + 32 && table[end] != 0) end++;
            String name = new String(table, e, end - e, StandardCharsets.US_ASCII);
            ByteBuffer b = ByteBuffer.wrap(table, e + 32, 20);
            long offset = b.getLong(), size = b.getLong();
            int flag = b.getInt();
            if (offset < 0 || size < 0 || offset + size > ch.size()) throw new IOException(name + " lies outside the trophy pack");
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals("..")) throw new IOException("Invalid file name in trophy pack");
            info.files.add(new FileEntry(name, offset, size, flag));
            info.totalBytes += size;
        }
        if (info.version >= 2) {
            info.hashed = true;
            if (fileSize >= HEADER && fileSize <= ch.size() && fileSize <= Integer.MAX_VALUE) {
                byte[] all = read(ch, 0, (int) fileSize);
                byte[] stored = java.util.Arrays.copyOfRange(all, 0x1C, 0x30);
                java.util.Arrays.fill(all, 0x1C, 0x30, (byte) 0);
                info.hashValid = java.util.Arrays.equals(sha1(all), stored);
            }
        }
        // Title, communication id and trophy count from the text (TROP.SFM) or config (TROPCONF.SFM)
        FileEntry conf = find(info, "TROP.SFM");
        if (conf == null) conf = find(info, "TROPCONF.SFM");
        if (conf != null && conf.size < (4 << 20)) {
            String xml = new String(read(ch, conf), StandardCharsets.UTF_8);
            info.commId = tag(xml, "npcommid");
            info.title = tag(xml, "title-name");
            Matcher m = Pattern.compile("<trophy\\s").matcher(xml);
            while (m.find()) info.trophies++;
        }
        return info;
    }

    private static FileEntry find(Info info, String name) {
        for (FileEntry f : info.files) if (f.name.equalsIgnoreCase(name)) return f;
        return null;
    }
    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    public static byte[] read(FileChannel ch, FileEntry f) throws IOException {
        if (f.size > Integer.MAX_VALUE) throw new IOException(f.name + " is too large");
        return read(ch, f.offset, (int) f.size);
    }

    // ------------------------------------------------------------------ writing

    /** One file to pack: its name (no folders) and contents. */
    public interface Source { String name(); long size(); InputStream open() throws IOException; }

    /**
     * The order Sony's packs use: TROPCONF.SFM, TROP.SFM, TROP_xx.SFM, then everything else (ICON0.PNG, …) and
     * finally TROPnnn.PNG in number order.
     */
    static int rank(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (n.equals("TROPCONF.SFM") || n.equals("TROPCONF.ESFM")) return 0;
        if (n.equals("TROP.SFM") || n.equals("TROP.ESFM")) return 1;
        if (n.matches("TROP_\\d+\\.E?SFM")) return 2;
        if (n.matches("TROP\\d+\\.PNG")) return 4;
        return 3;
    }

    /** SFM files carry flag 1 in Sony's PS3 packs, everything else 0. */
    static int flag(String name) { return name.toUpperCase(Locale.ROOT).endsWith(".SFM") ? 1 : 0; }

    /**
     * Writes a pack. Version 1 has no checksum; version 2 and 3 get the SHA-1 RPCS3 checks. Because the hash covers
     * the whole file, the file is assembled in memory (trophy packs are a few MB).
     */
    public static byte[] build(List<Source> sources, int version) throws IOException {
        List<Source> files = new ArrayList<>(sources);
        Collections.sort(files, (a, b) -> {
            int r = Integer.compare(rank(a.name()), rank(b.name()));
            return r != 0 ? r : a.name().toUpperCase(Locale.ROOT).compareTo(b.name().toUpperCase(Locale.ROOT));
        });
        if (files.isEmpty()) throw new IOException("Nothing to pack");
        if (files.size() > MAX_FILES) throw new IOException("Too many files");
        long pos = HEADER + (long) ENTRY * files.size();
        long[] offsets = new long[files.size()];
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            Source f = files.get(i);
            byte[] nameBytes = f.name().getBytes(StandardCharsets.US_ASCII);
            if (nameBytes.length == 0 || nameBytes.length > 31) throw new IOException("File name must be 1–31 characters: " + f.name());
            if (!seen.add(f.name().toUpperCase(Locale.ROOT))) throw new IOException("Duplicate file: " + f.name());
            pos = align(pos);
            offsets[i] = pos;
            pos += f.size();
        }
        long total = align(pos);
        if (total > 256L << 20) throw new IOException("Trophy pack would be unreasonably large");
        ByteBuffer out = ByteBuffer.allocate((int) total);
        out.putInt(MAGIC).putInt(version).putLong(total).putInt(files.size()).putInt(ENTRY).putInt(0);
        for (int i = 0; i < files.size(); i++) {
            Source f = files.get(i);
            out.position(HEADER + i * ENTRY);
            out.put(f.name().getBytes(StandardCharsets.US_ASCII));
            out.position(HEADER + i * ENTRY + 32);
            out.putLong(offsets[i]).putLong(f.size()).putInt(flag(f.name()));
        }
        byte[] buf = new byte[65536];
        for (int i = 0; i < files.size(); i++) {
            Source f = files.get(i);
            out.position((int) offsets[i]);
            long copied = 0;
            try (InputStream in = f.open()) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (copied + n > f.size()) throw new IOException(f.name() + " changed size while being packed");
                    out.put(buf, 0, n);
                    copied += n;
                }
            }
            if (copied != f.size()) throw new IOException(f.name() + " changed size while being packed");
        }
        byte[] pack = out.array();
        if (version >= 2) System.arraycopy(sha1(pack), 0, pack, 0x1C, 20);
        return pack;
    }

    public static void write(List<Source> sources, int version, OutputStream out) throws IOException { out.write(build(sources, version)); }

    // ------------------------------------------------------------------ helpers

    private static long align(long v) { return (v + 15) & ~15L; }
    static byte[] sha1(byte[] data) {
        try { return MessageDigest.getInstance("SHA-1").digest(data); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] read(FileChannel ch, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) if (ch.read(buf, offset + buf.position()) < 0) throw new IOException("Unexpected end of file");
        return buf.array();
    }
}
