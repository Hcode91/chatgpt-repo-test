package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A bounded, read-only parser for unencrypted Nintendo 3DS NCSD/NCCH/ExeFS/RomFS images. */
public final class CciArchive {
    private static final long SECTOR = 0x200;
    private static final long END = 0xffffffffL;
    private static final int MAX_TABLE = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 250000;

    public static final class Entry {
        public final String path;
        public final long offset;
        public final long size;
        Entry(String path, long offset, long size) {
            this.path = path; this.offset = offset; this.size = size;
        }
    }

    public static final class Info {
        public final List<Entry> entries;
        public final List<String> notes;
        public final int partitions;
        public final String titleId;
        Info(List<Entry> entries, List<String> notes, int partitions, String titleId) {
            this.entries = Collections.unmodifiableList(entries);
            this.notes = Collections.unmodifiableList(notes);
            this.partitions = partitions;
            this.titleId = titleId;
        }
    }

    private static final class Reader {
        final FileChannel channel;
        final long length;
        Reader(FileChannel channel) throws IOException {
            this.channel = channel; this.length = channel.size();
        }
        byte[] get(long offset, int count) throws IOException {
            if (offset < 0 || count < 0 || offset > length || count > length - offset)
                throw new IOException("Invalid offset or truncated game image at 0x" + Long.toHexString(offset));
            ByteBuffer b = ByteBuffer.allocate(count);
            while (b.hasRemaining()) {
                int n = channel.read(b, offset + b.position());
                if (n < 0) throw new IOException("Unexpected end of file");
                if (n == 0) throw new IOException("Unable to read selected game image");
            }
            return b.array();
        }
    }

    private static long u32(byte[] b, int p) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }
    private static long u64(byte[] b, int p) throws IOException {
        long v = ByteBuffer.wrap(b, p, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (v < 0) throw new IOException("Unsupported 64-bit offset");
        return v;
    }
    private static String ascii(byte[] b, int pos, int length) {
        int end = pos;
        while (end < pos + length && b[end] != 0) end++;
        return new String(b, pos, end - pos, StandardCharsets.US_ASCII);
    }
    private static String magic(byte[] b, int at) { return ascii(b, at, 4); }
    private static long mul(long a, long b) throws IOException {
        if (a < 0 || b < 0 || (b > 0 && a > Long.MAX_VALUE / b))
            throw new IOException("Invalid media unit multiplier");
        return a * b;
    }
    private static void range(long start, long len, long limit) throws IOException {
        if (start < 0 || len < 0 || start > limit || len > limit - start)
            throw new IOException("Invalid region range in image");
    }
    private static int checkedCount(long value) throws IOException {
        if (value < 0 || value > MAX_TABLE) throw new IOException("Unreasonable metadata table size");
        return (int) value;
    }
    private static String name(byte[] bytes, int start, int len) throws IOException {
        if ((len & 1) != 0 || len > 1024 || start < 0 || start > bytes.length || len > bytes.length - start)
            throw new IOException("Invalid RomFS name");
        String n = new String(bytes, start, len, StandardCharsets.UTF_16LE);
        if (n.length() == 0 || n.equals(".") || n.equals("..") || n.indexOf('/') >= 0 ||
                n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0 || n.indexOf(':') >= 0)
            throw new IOException("Unsafe filename in RomFS");
        return n;
    }

    private static String titleId(byte[] bytes, int offset) {
        StringBuilder value = new StringBuilder(16);
        boolean nonzero=false, notAllFF=false;
        final char[] hex="0123456789ABCDEF".toCharArray();
        for(int i=7;i>=0;i--) {
            int x=bytes[offset+i]&255;
            nonzero|=x!=0;notAllFF|=x!=255;
            value.append(hex[x>>>4]).append(hex[x&15]);
        }
        return nonzero && notAllFF?value.toString():null;
    }
    public static String exportPath(String internalPath, String gameTitleId) {
        if(internalPath==null || !internalPath.startsWith("partition_"))
            throw new IllegalArgumentException("Invalid partition path");
        int slash=internalPath.indexOf('/');
        if(slash<0 || slash==internalPath.length()-1)
            throw new IllegalArgumentException("Invalid extraction path");
        String partition=internalPath.substring(0,slash);
        String remainder=internalPath.substring(slash+1);
        if(!partition.matches("partition_[0-7]") || remainder.startsWith("/") ||
            remainder.contains("..") || remainder.contains("\\"))
            throw new IllegalArgumentException("Unsafe extraction path");
        if(partition.equals("partition_0")) {
            if(gameTitleId!=null && gameTitleId.matches("[0-9A-F]{16}"))
                return gameTitleId+"/"+remainder;
            return internalPath;
        }
        return "other_partitions/"+internalPath;
    }
    public static Info parse(FileChannel channel) throws IOException {
        Reader r = new Reader(channel);
        if (r.length < 0x4000) throw new IOException("Not a valid CCI: image is too small");
        byte[] ncsd = r.get(0, 0x200);
        if (!magic(ncsd, 0x100).equals("NCSD"))
            throw new IOException("Unsupported file: expected a Nintendo 3DS CCI/NCSD image");

        int ncsdUnitShift = ncsd[0x18e] & 0xff;
        if (ncsdUnitShift > 10) throw new IOException("Invalid NCSD media unit");
        long ncsdUnit = SECTOR << ncsdUnitShift;
        List<Entry> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int partitions = 0;
        String titleId = titleId(ncsd,0x108);
        for (int i = 0; i < 8; i++) {
            long startUnits = u32(ncsd, 0x120 + 8 * i);
            long sizeUnits = u32(ncsd, 0x124 + 8 * i);
            if (sizeUnits == 0) continue;
            long ncchStart = mul(startUnits, ncsdUnit);
            long ncchSize = mul(sizeUnits, ncsdUnit);
            range(ncchStart, ncchSize, r.length);
            if (ncchSize < 0x200) throw new IOException("Partition " + i + " is too small");
            byte[] ncch = r.get(ncchStart, 0x200);
            if (!magic(ncch, 0x100).equals("NCCH")) {
                notes.add("Partition " + i + ": not a recognised NCCH container");
                continue;
            }
            partitions++;
            if(i==0) {
                String programId=titleId(ncch,0x118);
                if(programId!=null) {
                    if(titleId!=null && !titleId.equals(programId))
                        notes.add("NCSD media ID differs from application NCCH Program ID; using Program ID");
                    titleId=programId;
                }
            }
            int shift = ncch[0x18e] & 0xff;
            if (shift > 10) throw new IOException("Invalid NCCH media unit");
            long unit = SECTOR << shift;
            boolean noCrypto = (ncch[0x18f] & 4) != 0;
            String base = "partition_" + i;
            long exefsUnits = u32(ncch, 0x1a0);
            long exefsCount = u32(ncch, 0x1a4);
            long romfsUnits = u32(ncch, 0x1b0);
            long romfsCount = u32(ncch, 0x1b4);
            boolean found = false;

            if (exefsUnits > 0 && exefsCount > 0) {
                long pos = ncchStart + mul(exefsUnits, unit);
                long len = mul(exefsCount, unit);
                range(pos, len, Math.min(r.length, ncchStart + ncchSize));
                if (noCrypto) {
                    try { found |= parseExeFs(r, pos, len, base + "/exefs", out); }
                    catch (IOException e) { notes.add("Partition " + i + " ExeFS: " + e.getMessage()); }
                } else notes.add("Partition " + i + " ExeFS: encrypted or missing NoCrypto flag");
            }
            if (romfsUnits > 0 && romfsCount > 0) {
                long pos = ncchStart + mul(romfsUnits, unit);
                long len = mul(romfsCount, unit);
                range(pos, len, Math.min(r.length, ncchStart + ncchSize));
                if (len >= 0x1000 && magic(r.get(pos, 4), 0).equals("IVFC")) {
                    try { found |= parseRomFs(r, pos, len, base + "/romfs", out); }
                    catch (IOException e) { notes.add("Partition " + i + " RomFS: " + e.getMessage()); }
                } else notes.add("Partition " + i + " RomFS: encrypted or unsupported (IVFC header not found)");
            }
            if (!found) notes.add("Partition " + i + ": no extractable files detected");
        }
        if (out.isEmpty()) {
            throw new IOException("No extractable files found. This version requires decrypted CCI images. " +
                    (notes.isEmpty() ? "" : notes.get(0)));
        }
        if(titleId==null)notes.add("Game Title ID unavailable; partition folders will be retained");
        return new Info(out, notes, partitions, titleId);
    }

    private static boolean parseExeFs(Reader r, long start, long length, String folder, List<Entry> out)
            throws IOException {
        if (length < 0x200) throw new IOException("ExeFS header is too short");
        byte[] header = r.get(start, 0x200);
        boolean any = false;
        for (int i = 0; i < 10; i++) {
            int at = i * 16;
            String file = ascii(header, at, 8);
            if (file.isEmpty()) continue;
            if (file.indexOf('/') >= 0 || file.indexOf('\\') >= 0 || file.equals("..")) continue;
            long offset = u32(header, at + 8);
            long size = u32(header, at + 12);
            range(0x200 + offset, size, length);
            out.add(new Entry(folder + "/" + file, start + 0x200 + offset, size));
            any = true;
        }
        return any;
    }

    private static boolean parseRomFs(Reader r, long start, long length, String folder, List<Entry> out)
            throws IOException {
        byte[] ivfc = r.get(start, 0x5c);
        if (!magic(ivfc, 0).equals("IVFC")) throw new IOException("Missing IVFC header");
        long hashLength = u32(ivfc, 8);
        long level3Rel = ((0x60L + hashLength + 0xfffL) / 0x1000L) * 0x1000L;
        long level3Length = u64(ivfc, 0x44);
        range(level3Rel, level3Length, length);
        long level3 = start + level3Rel;
        byte[] header = r.get(level3, 0x28);
        if (u32(header, 0) != 0x28) throw new IOException("Unsupported RomFS level 3 header");
        long dirOffset = u32(header, 0x0c), dirLength = u32(header, 0x10);
        long fileOffset = u32(header, 0x1c), fileLength = u32(header, 0x20);
        long dataOffset = u32(header, 0x24);
        range(dirOffset, dirLength, level3Length);
        range(fileOffset, fileLength, level3Length);
        range(dataOffset, 0, level3Length);
        byte[] dirs = r.get(level3 + dirOffset, checkedCount(dirLength));
        byte[] files = r.get(level3 + fileOffset, checkedCount(fileLength));
        if (dirs.length < 0x18) throw new IOException("Missing root directory");
        int before = out.size();
        walkFolder(0, folder, dirs, files, level3 + dataOffset,
                level3Length - dataOffset, out, new HashSet<Long>(), 0);
        return out.size() > before;
    }

    private static void walkFolder(long off, String path, byte[] dirs, byte[] files,
                                   long dataStart, long dataLength, List<Entry> out,
                                   Set<Long> visited, int depth) throws IOException {
        if (depth > 64 || visited.size() >= MAX_ENTRIES) throw new IOException("Excessive RomFS directory nesting");
        if (!visited.add(off)) throw new IOException("Cyclic RomFS directory metadata");
        if (off < 0 || off > dirs.length - 0x18) throw new IOException("Invalid directory pointer");
        int p = (int) off;
        long sibling = u32(dirs, p + 4);
        long firstChild = u32(dirs, p + 8);
        long firstFile = u32(dirs, p + 12);
        int nameBytes = checkedCount(u32(dirs, p + 20));
        if (off != 0) path += "/" + name(dirs, p + 0x18, nameBytes);

        long ff = firstFile;
        Set<Long> seenFiles = new HashSet<>();
        while (ff != END) {
            if (!seenFiles.add(ff) || out.size() >= MAX_ENTRIES)
                throw new IOException("Cyclic or oversized file table");
            if (ff < 0 || ff > files.length - 0x20) throw new IOException("Invalid file pointer");
            int f = (int) ff;
            long next = u32(files, f + 4);
            long offset = u64(files, f + 8);
            long length = u64(files, f + 16);
            int bytes = checkedCount(u32(files, f + 28));
            String filename = name(files, f + 0x20, bytes);
            range(offset, length, dataLength);
            out.add(new Entry(path + "/" + filename, dataStart + offset, length));
            ff = next;
        }
        long child = firstChild;
        Set<Long> siblingSet = new HashSet<>();
        while (child != END) {
            if (!siblingSet.add(child)) throw new IOException("Cyclic sibling directories");
            if (child < 0 || child > dirs.length - 0x18) throw new IOException("Invalid child directory pointer");
            long next = u32(dirs, (int) child + 4);
            walkFolder(child, path, dirs, files, dataStart, dataLength, out, visited, depth + 1);
            child = next;
        }
    }

    private CciArchive() {}
}
