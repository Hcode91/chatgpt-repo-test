package com.retrotools.cciextractor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Virtual DS file tree: NitroFS files plus the contents of NARC archives and LZ10/LZ11 compressed files.
 * Every node can be read, and a node's decompressed content can be replaced to rebuild a new .nds copy.
 */
public final class NitroVfs {
    public static final int MAX_NODE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_DEPTH = 4;
    public static final String CONTENTS_SUFFIX = "_contents";

    public interface Reader { byte[] read(long offset, int length) throws IOException; }

    public static final class Node {
        public final String path;
        public final Node parent;
        public final int childIndex;
        public final NdsArchive.Entry romEntry;
        public final long rawSize;
        public final int depth;
        /** NitroLz type of the raw bytes, or TYPE_NONE. Set once probed. */
        public int compression = NitroLz.TYPE_NONE;
        public boolean probed;
        public String kind = "";
        Node(String path, Node parent, int childIndex, NdsArchive.Entry romEntry, long rawSize, int depth) {
            this.path = path; this.parent = parent; this.childIndex = childIndex;
            this.romEntry = romEntry; this.rawSize = rawSize; this.depth = depth;
        }
        public String name() { return path.substring(path.lastIndexOf('/') + 1); }
        public String folder() { int s = path.lastIndexOf('/'); return s < 0 ? "" : path.substring(0, s); }
        public boolean isContainer() { return "NARC".equals(kind); }
        public boolean isGraphic() { return "NCGR".equals(kind); }
        public boolean isPalette() { return "NCLR".equals(kind); }
        public String describe() {
            StringBuilder s = new StringBuilder();
            s.append(kind.isEmpty() ? "Data" : kind);
            if (compression == NitroLz.TYPE_LZ10) s.append(" · LZ10 compressed");
            if (compression == NitroLz.TYPE_LZ11) s.append(" · LZ11 compressed");
            return s.toString();
        }
    }

    public static final class Probe {
        public final byte[] raw, content;
        Probe(byte[] raw, byte[] content) { this.raw = raw; this.content = content; }
    }

    private NitroVfs() {}

    public static List<Node> roots(NdsArchive.Info info) {
        List<Node> out = new ArrayList<>();
        for (NdsArchive.Entry e : info.entries) {
            Node n = new Node(e.path, null, -1, e, e.size, 0);
            n.kind = kindFromName(e.path);
            out.add(n);
        }
        return out;
    }

    public static String kindFromMagic(byte[] b) {
        if (b == null || b.length < 4) return "";
        String m = new String(b, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        switch (m) {
            case "NARC": return "NARC";
            case "RGCN": return "NCGR";
            case "RLCN": return "NCLR";
            case "RCSN": return "NSCR";
            case "RECN": return "NCER";
            case "RNAN": return "NANR";
            case "BTX0": return "NSBTX";
            case "BMD0": return "NSBMD";
            case "SDAT": return "SDAT";
            default: return "";
        }
    }

    public static String kindFromName(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        if (n.endsWith(".narc") || n.endsWith(".carc")) return "NARC";
        if (n.endsWith(".ncgr")) return "NCGR";
        if (n.endsWith(".nclr")) return "NCLR";
        if (n.endsWith(".nscr")) return "NSCR";
        if (n.endsWith(".ncer")) return "NCER";
        return "";
    }

    static String extensionFor(String kind) {
        switch (kind) {
            case "NARC": return ".narc";
            case "NCGR": return ".ncgr";
            case "NCLR": return ".nclr";
            case "NSCR": return ".nscr";
            case "NCER": return ".ncer";
            case "NANR": return ".nanr";
            case "NSBTX": return ".nsbtx";
            case "NSBMD": return ".nsbmd";
            case "SDAT": return ".sdat";
            default: return ".bin";
        }
    }

    public static byte[] raw(Node n, Reader r) throws IOException {
        if (n.parent == null) {
            if (n.rawSize > MAX_NODE_BYTES) throw new IOException(n.name() + " is too large to unpack");
            return r.read(n.romEntry.offset, (int) n.rawSize);
        }
        NarcArchive.Archive parent = NarcArchive.parse(probe(n.parent, r).content);
        if (n.childIndex >= parent.count()) throw new IOException("Archive changed; reopen the ROM");
        return parent.file(n.childIndex);
    }

    /** Read a node and decompress it when it is a cleanly-formed LZ10/LZ11 stream. */
    public static Probe probe(Node n, Reader r) throws IOException {
        byte[] raw = raw(n, r);
        byte[] content = raw;
        NitroLz.Result lz = NitroLz.tryWhole(raw);
        if (lz != null) {
            String innerKind = kindFromMagic(lz.data);
            String lower = n.path.toLowerCase(Locale.ROOT);
            // Accept compression if the result is a known Nitro format, or the name says it's compressed,
            // or the raw file is not itself a recognisable format.
            if (!innerKind.isEmpty() || lower.endsWith(".lz") || lower.endsWith(".carc") ||
                    lower.endsWith(".cmp") || kindFromMagic(raw).isEmpty()) {
                content = lz.data;
                n.compression = lz.type;
            }
        }
        String k = kindFromMagic(content);
        if (!k.isEmpty()) n.kind = k;
        n.probed = true;
        return new Probe(raw, content);
    }

    /** Small cache so reading many children of one archive doesn't re-decompress the parent each time. */
    public static final class Cache {
        private final java.util.Map<Node, byte[]> contents = new java.util.LinkedHashMap<Node, byte[]>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<Node, byte[]> e) { return size() > 8; }
        };
        private final java.util.Map<Node, NarcArchive.Archive> narcs = new java.util.HashMap<>();
    }

    /** Decompressed content of a node, using the cache for parent archives. */
    public static byte[] content(Node n, Reader r, Cache c) throws IOException {
        byte[] hit = c.contents.get(n);
        if (hit != null) return hit;
        byte[] result;
        if (n.parent == null) result = probe(n, r).content;
        else {
            NarcArchive.Archive a = c.narcs.get(n.parent);
            if (a == null) {
                a = NarcArchive.parse(content(n.parent, r, c));
                if (c.narcs.size() > 8) c.narcs.clear();
                c.narcs.put(n.parent, a);
            }
            if (n.childIndex >= a.count()) throw new IOException("Archive changed; reopen the ROM");
            byte[] raw = a.file(n.childIndex);
            result = raw;
            if (n.compression != NitroLz.TYPE_NONE) result = NitroLz.decompress(raw, 0, NitroLz.MAX_OUTPUT).data;
            n.probed = true;
        }
        if (NarcArchive.isNarc(result)) c.contents.put(n, result);
        return result;
    }

    /** Children of a NARC node (folder path is node path + "_contents"). */
    public static List<Node> expand(Node n, byte[] content) throws IOException {
        List<Node> out = new ArrayList<>();
        if (!NarcArchive.isNarc(content) || n.depth >= MAX_DEPTH) return out;
        NarcArchive.Archive a = NarcArchive.parse(content);
        String base = n.path + CONTENTS_SUFFIX + "/";
        for (int i = 0; i < a.count(); i++) {
            byte[] file = a.file(i);
            String kind = kindFromMagic(file);
            int comp = NitroLz.TYPE_NONE;
            if (kind.isEmpty()) {
                NitroLz.Result lz = NitroLz.tryWhole(file);
                if (lz != null) { kind = kindFromMagic(lz.data); comp = lz.type; }
            }
            String name = a.names[i] != null ? a.names[i] :
                    String.format(Locale.ROOT, "%04d%s", i, extensionFor(kind));
            Node child = new Node(base + name, n, i, null, file.length, n.depth + 1);
            child.kind = kind.isEmpty() ? kindFromName(name) : kind;
            child.compression = comp;
            out.add(child);
        }
        return out;
    }

    /** Best palette for a graphic: same stem first, then nearest palette listed before (or after) it. */
    public static Node pairPalette(Node graphic, List<Node> all) {
        String folder = graphic.folder();
        String stem = graphic.path.contains(".") ? graphic.path.substring(0, graphic.path.lastIndexOf('.')) : graphic.path;
        List<Node> siblings = new ArrayList<>();
        int at = -1;
        for (Node n : all) {
            if (!n.folder().equals(folder)) continue;
            if (n == graphic) at = siblings.size();
            siblings.add(n);
        }
        for (Node n : siblings) {
            if (n.isPalette() && n.path.toLowerCase(Locale.ROOT).equals((stem + ".nclr").toLowerCase(Locale.ROOT))) return n;
        }
        if (at < 0) return null;
        for (int d = 1; d < siblings.size(); d++) {
            if (at - d >= 0 && siblings.get(at - d).isPalette()) return siblings.get(at - d);
            if (at + d < siblings.size() && siblings.get(at + d).isPalette()) return siblings.get(at + d);
        }
        return null;
    }

    public static final class Patch {
        public final NdsArchive.Entry entry;
        public final byte[] data;
        Patch(NdsArchive.Entry entry, byte[] data) { this.entry = entry; this.data = data; }
    }

    /**
     * Replace a node's decompressed content. Re-compresses (same LZ type) and rebuilds parent NARCs,
     * returning the new bytes for the top-level NitroFS file.
     */
    public static Patch replace(Node n, byte[] newContent, Reader r) throws IOException {
        if (!n.probed) probe(n, r);
        byte[] newRaw = n.compression == NitroLz.TYPE_NONE ? newContent : NitroLz.compress(newContent, n.compression);
        if (n.parent == null) return new Patch(n.romEntry, newRaw);
        Probe parent = probe(n.parent, r);
        byte[] rebuilt = NarcArchive.replace(parent.content, n.childIndex, newRaw);
        return replace(n.parent, rebuilt, r);
    }

    // ---------------------------------------------------------------- ROM rebuild

    private static int u32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }
    private static void put32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> (8 * i)); }

    static int crc16(byte[] data, int start, int len) {
        int crc = 0xffff;
        for (int i = 0; i < len; i++) {
            crc ^= (data[start + i] & 255);
            for (int b = 0; b < 8; b++) crc = ((crc & 1) != 0) ? (crc >>> 1) ^ 0xa001 : (crc >>> 1);
        }
        return crc & 0xffff;
    }

    /** Write a new .nds copy with one NitroFS file replaced. Larger files are appended to the end. */
    public static String writeRom(FileChannel ch, OutputStream out, Patch patch) throws IOException {
        long romLength = ch.size();
        byte[] header = new byte[0x200];
        readFully(ch, header, 0);
        long fat = u32(header, 0x48) & 0xFFFFFFFFL;
        int id = patch.entry.id;
        long fatEntry = fat + 8L * id;
        byte[] fatBytes = new byte[8];
        readFully(ch, fatBytes, fatEntry);
        long oldStart = u32(fatBytes, 0) & 0xFFFFFFFFL, oldEnd = u32(fatBytes, 4) & 0xFFFFFFFFL;
        if (oldStart != patch.entry.offset || oldEnd - oldStart != patch.entry.size)
            throw new IOException("ROM changed since it was opened; reopen it");
        int newLen = patch.data.length;
        boolean inPlace = newLen <= oldEnd - oldStart;
        long newStart = inPlace ? oldStart : ((romLength + 0x1FF) & ~0x1FFL);
        long newEnd = newStart + newLen;
        if (newEnd > 0x20000000L) throw new IOException("Modified ROM would exceed 512 MB");
        put32(fatBytes, 0, newStart);
        put32(fatBytes, 4, newEnd);
        long usedSize = u32(header, 0x80) & 0xFFFFFFFFL;
        if (!inPlace && newEnd > usedSize) put32(header, 0x80, newEnd);
        int capacity = header[0x14] & 255;
        long finalSize = Math.max(romLength, newEnd);
        while (capacity < 15 && (0x20000L << capacity) < finalSize) capacity++;
        header[0x14] = (byte) capacity;
        int crc = crc16(header, 0, 0x15E);
        header[0x15E] = (byte) crc; header[0x15F] = (byte) (crc >>> 8);

        byte[] buffer = new byte[1 << 16];
        for (long pos = 0; pos < romLength; ) {
            int n = (int) Math.min(buffer.length, romLength - pos);
            readFully(ch, buffer, pos, n);
            overlay(buffer, pos, n, header, 0);
            overlay(buffer, pos, n, fatBytes, fatEntry);
            if (inPlace) {
                overlay(buffer, pos, n, patch.data, oldStart);
                // Blank the unused tail of the old slot.
                long tailStart = oldStart + newLen, tailEnd = oldEnd;
                long a = Math.max(pos, tailStart), b = Math.min(pos + n, tailEnd);
                for (long i = a; i < b; i++) buffer[(int) (i - pos)] = (byte) 0xFF;
            }
            out.write(buffer, 0, n);
            pos += n;
        }
        if (!inPlace) {
            for (long p = romLength; p < newStart; p++) out.write(0xFF);
            out.write(patch.data);
        }
        return inPlace ? "replaced in place" : "appended at 0x" + Long.toHexString(newStart).toUpperCase(Locale.ROOT);
    }

    private static void overlay(byte[] buffer, long pos, int n, byte[] patch, long at) {
        long a = Math.max(pos, at), b = Math.min(pos + n, at + patch.length);
        if (a < b) System.arraycopy(patch, (int) (a - at), buffer, (int) (a - pos), (int) (b - a));
    }

    private static void readFully(FileChannel ch, byte[] b, long pos) throws IOException { readFully(ch, b, pos, b.length); }
    private static void readFully(FileChannel ch, byte[] b, long pos, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(b, 0, len);
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos + buf.position());
            if (n <= 0) throw new IOException("Unexpected end of DS ROM");
        }
    }

    public static Reader channelReader(FileChannel ch) {
        return (offset, length) -> {
            byte[] b = new byte[length];
            readFully(ch, b, offset);
            return b;
        };
    }
}
