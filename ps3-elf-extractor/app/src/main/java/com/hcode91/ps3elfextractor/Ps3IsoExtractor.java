package com.hcode91.ps3elfextractor;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracts already-DECRYPTED PS3 ISO9660 (including Joliet and multi-extent files)
 * directly through Android's Storage Access Framework without staging the ISO.
 * Encrypted discs and UDF-only images are intentionally rejected, never copied as
 * silently corrupt game files. Incomplete exports retain an explicit marker.
 */
final class Ps3IsoExtractor {
    interface Progress { void update(String text); }
    private static final int BLOCK = 2048;
    private static final int MAX_ENTRIES = 150000;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_DIR_BYTES = 32 * 1024 * 1024;
    private static final String INCOMPLETE = "_INCOMPLETE_EXTRACT.txt";

    static final class Result {
        final String name;
        final long files, bytes;
        Result(String name, long files, long bytes) {
            this.name = name; this.files = files; this.bytes = bytes;
        }
    }

    private static final class Segment {
        final long offset, length;
        Segment(long offset, long length) { this.offset = offset; this.length = length; }
    }

    private static final class Item {
        final String path;
        final boolean dir;
        final List<Segment> segments = new ArrayList<>();
        boolean needsMore;
        Item(String path, boolean dir) { this.path = path; this.dir = dir; }
    }

    private static final class Record {
        final String name;
        final boolean dir, more;
        final long offset, length;
        Record(String name, boolean dir, boolean more, long offset, long length) {
            this.name = name; this.dir = dir; this.more = more;
            this.offset = offset; this.length = length;
        }
    }

    private static final class Reader implements AutoCloseable {
        final ParcelFileDescriptor fd;
        final FileInputStream stream;
        final FileChannel ch;
        final long size;
        Reader(ContentResolver resolver, Uri uri) throws IOException {
            fd = resolver.openFileDescriptor(uri, "r");
            if (fd == null) throw new IOException("Cannot open ISO document");
            stream = new FileInputStream(fd.getFileDescriptor());
            ch = stream.getChannel();
            try {
                size = ch.size();
                if (size < 17L * BLOCK)
                    throw new IOException("ISO too small or invalid");
                // Some cloud/document providers supply non-seekable file descriptors.
                read(16L * BLOCK, 6);
            } catch (IOException ex) {
                try { close(); } catch (IOException ignored) { }
                throw new IOException("Cannot randomly access ISO. Choose a local file "
                        + "(not a non-seekable cloud stream). " + ex.getMessage(), ex);
            }
        }
        byte[] read(long at, int len) throws IOException {
            byte[] out = new byte[len];
            readFully(at, out, 0, len);
            return out;
        }
        void readFully(long at, byte[] dst, int offset, int len) throws IOException {
            if (at < 0 || len < 0 || at > size || len > size - at)
                throw new IOException("ISO extent outside file bounds");
            ByteBuffer buf = ByteBuffer.wrap(dst, offset, len);
            int got = 0;
            while (buf.hasRemaining()) {
                int n = ch.read(buf, at + got);
                if (n <= 0) throw new IOException("Short read from ISO at " + (at + got));
                got += n;
            }
        }
        @Override public void close() throws IOException {
            try { stream.close(); } finally { fd.close(); }
        }
    }

    private final Reader reader;
    private final List<Item> dirs = new ArrayList<>();
    private final List<Item> files = new ArrayList<>();
    private final Map<String, Item> entries = new LinkedHashMap<>();
    private final Set<String> uniquePaths = new HashSet<>();
    private final Set<String> visitedDirs = new HashSet<>();
    private boolean joliet;
    private long totalBytes;

    private Ps3IsoExtractor(Reader reader) { this.reader = reader; }

    static Result extract(ContentResolver resolver, Uri iso, Uri destinationTree,
                          String isoName, Progress progress) throws Exception {
        try (Reader reader = new Reader(resolver, iso)) {
            Ps3IsoExtractor extractor = new Ps3IsoExtractor(reader);
            progress.update("Scanning ISO9660 directory tree...");
            extractor.scan();
            extractor.validateDecryptedGame();
            // All safety and format checks happen BEFORE any output directory is made.
            String folder = folderName(isoName);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(destinationTree,
                    DocumentsContract.getTreeDocumentId(destinationTree));
            Uri base = create(resolver, parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, folder);
            Uri incomplete = create(resolver, base, "text/plain", INCOMPLETE);
            try (OutputStream marker = resolver.openOutputStream(incomplete, "w")) {
                if (marker == null) throw new IOException("Cannot write progress marker");
                marker.write(("Extraction incomplete. Do not use this JB folder until the "
                        + "application reports success. Partial files may remain.\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            Map<String, Uri> folders = new HashMap<>();
            folders.put("", base);
            for (Item dir : extractor.dirs) {
                String parentPath = parent(dir.path);
                Uri parentDoc = folders.get(parentPath);
                if (parentDoc == null) throw new IOException("Missing output parent " + parentPath);
                folders.put(dir.path, create(resolver, parentDoc,
                        DocumentsContract.Document.MIME_TYPE_DIR, basename(dir.path)));
            }
            byte[] buffer = new byte[256 * 1024];
            long done = 0;
            long lastUpdate = 0;
            int number = 0;
            for (Item file : extractor.files) {
                Uri parentDoc = folders.get(parent(file.path));
                if (parentDoc == null) throw new IOException("Missing output parent for " + file.path);
                Uri dest = create(resolver, parentDoc, "application/octet-stream",
                        basename(file.path));
                try (OutputStream out = resolver.openOutputStream(dest, "w")) {
                    if (out == null) throw new IOException("Cannot write: " + file.path);
                    for (Segment segment : file.segments) {
                        long position = segment.offset, remaining = segment.length;
                        while (remaining > 0) {
                            int n = (int) Math.min(buffer.length, remaining);
                            reader.readFully(position, buffer, 0, n);
                            out.write(buffer, 0, n);
                            position += n;
                            remaining -= n;
                            done += n;
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (now - lastUpdate >= 500) {
                                progress.update("Extracting " + (number + 1) + "/"
                                        + extractor.files.size() + " files\n" + file.path
                                        + "\n" + done / (1024 * 1024) + " / "
                                        + extractor.totalBytes / (1024 * 1024) + " MiB");
                                lastUpdate = now;
                            }
                        }
                    }
                }
                number++;
            }
            if (!DocumentsContract.deleteDocument(resolver, incomplete))
                throw new IOException("All files written but could not remove incomplete marker");
            progress.update("Complete: " + extractor.files.size() + " files, "
                    + done / (1024 * 1024) + " MiB");
            return new Result(folder, number, done);
        }
    }

    private void scan() throws IOException {
        byte[] pvd = reader.read(16L * BLOCK, BLOCK);
        if (pvd[0] != 1 || !magic(pvd))
            throw new IOException("No readable ISO9660 volume descriptor. Encrypted "
                    + "raw-disc dumps must first be decrypted using the matching "
                    + "disc key; UDF-only images are not yet supported.");
        if (le16(pvd, 128) != BLOCK)
            throw new IOException("Unsupported ISO logical block size");
        byte[] descriptor = pvd;
        for (int i = 17; i < 33; i++) {
            byte[] d = reader.read((long) i * BLOCK, BLOCK);
            if (!magic(d)) break;
            if (d[0] == (byte) 255) break;
            if (d[0] == 2 && d[88] == '%' && d[89] == '/'
                    && (d[90] == '@' || d[90] == 'C' || d[90] == 'E')) {
                descriptor = d;
                joliet = true;
                break;
            }
        }
        Record root = parseRecord(descriptor, 156);
        if (root == null || !root.dir) throw new IOException("No ISO root directory");
        walk("", root, 0);
        if (entries.size() == 0) throw new IOException("Empty ISO filesystem");
    }

    private void walk(String path, Record dir, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Directory nesting exceeds safe limit");
        String key = dir.offset + ":" + dir.length;
        if (!visitedDirs.add(key)) throw new IOException("Repeated/cyclic directory extent");
        if (dir.length > MAX_DIR_BYTES) throw new IOException("Directory listing too large");
        Item awaiting = null;
        for (long sectorPos = 0; sectorPos < dir.length; sectorPos += BLOCK) {
            int amount = (int) Math.min(BLOCK, dir.length - sectorPos);
            byte[] data = reader.read(dir.offset + sectorPos, amount);
            int at = 0;
            while (at < amount) {
                int recordLen = data[at] & 255;
                if (recordLen == 0) break; // remaining sector is padding
                if (recordLen < 34 || recordLen > amount - at)
                    throw new IOException("Malformed ISO9660 directory record");
                // ISO '.' and '..' use one-byte identifiers 0 and 1.
                int idLen = data[at + 32] & 255;
                if (idLen == 1 && (data[at + 33] == 0 || data[at + 33] == 1)) {
                    at += recordLen;
                    continue;
                }
                Record record = parseRecord(data, at);
                if (record == null) throw new IOException("Invalid directory record");
                String child = path.isEmpty() ? record.name : path + "/" + record.name;
                String normalized = child.toLowerCase(Locale.ROOT);
                if (awaiting != null) {
                    if (!awaiting.path.equals(child) || record.dir)
                        throw new IOException("Missing multi-extent continuation for "
                                + awaiting.path);
                    awaiting.segments.add(new Segment(record.offset, record.length));
                    totalBytes += record.length;
                    awaiting.needsMore = record.more;
                    if (!record.more) awaiting = null;
                } else {
                    if (entries.size() >= MAX_ENTRIES)
                        throw new IOException("Too many ISO entries");
                    if (!uniquePaths.add(normalized))
                        throw new IOException("Duplicate ISO path: " + child);
                    Item item = new Item(child, record.dir);
                    entries.put(child, item);
                    if (record.dir) {
                        if (record.more) throw new IOException("Multi-extent directory unsupported");
                        dirs.add(item);
                        walk(child, record, depth + 1);
                    } else {
                        item.segments.add(new Segment(record.offset, record.length));
                        item.needsMore = record.more;
                        files.add(item);
                        totalBytes += record.length;
                        if (record.more) awaiting = item;
                    }
                }
                at += recordLen;
            }
        }
        if (awaiting != null) throw new IOException("Incomplete multi-extent file " + awaiting.path);
    }

    private Record parseRecord(byte[] data, int at) throws IOException {
        if (at < 0 || at + 34 > data.length) throw new IOException("Truncated ISO record");
        int len = data[at] & 255;
        int nameLen = data[at + 32] & 255;
        if (len < 34 || at + len > data.length || 33 + nameLen > len)
            throw new IOException("Malformed ISO record length");
        long extent = le32(data, at + 2);
        long size = le32(data, at + 10);
        int attrBlocks = data[at + 1] & 255;
        long offset = (extent + attrBlocks) * BLOCK;
        if (offset < 0 || offset > reader.size || size > reader.size - offset)
            throw new IOException("Directory record extent outside ISO");
        boolean dir = (data[at + 25] & 2) != 0;
        boolean more = (data[at + 25] & 0x80) != 0;
        byte[] nameBytes = new byte[nameLen];
        System.arraycopy(data, at + 33, nameBytes, 0, nameLen);
        String name;
        if (joliet) {
            if ((nameLen & 1) != 0) throw new IOException("Bad Joliet filename");
            name = new String(nameBytes, StandardCharsets.UTF_16BE);
        } else name = new String(nameBytes, StandardCharsets.ISO_8859_1);
        name = name.replaceFirst(";[0-9]+$", "");
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.equals(INCOMPLETE) || name.contains("/")
                || name.contains("\\") || name.indexOf(':') >= 0
                || name.length() > 200)
            throw new IOException("Unsafe filename in ISO: " + name);
        for (int c = 0; c < name.length(); c++) {
            if (Character.isISOControl(name.charAt(c)))
                throw new IOException("Control character in ISO filename");
        }
        return new Record(name, dir, more, offset, size);
    }

    private void validateDecryptedGame() throws IOException {
        Item gameDir = find("PS3_GAME");
        Item sfo = find("PS3_GAME/PARAM.SFO");
        Item eboot = find("PS3_GAME/USRDIR/EBOOT.BIN");
        if (gameDir == null || !gameDir.dir || sfo == null || sfo.dir
                || eboot == null || eboot.dir)
            throw new IOException("Not a standard PS3 disc folder: PS3_GAME/PARAM.SFO "
                    + "and PS3_GAME/USRDIR/EBOOT.BIN are required.");
        checkHeader(sfo, new byte[]{0, 'P', 'S', 'F'}, "PARAM.SFO");
        checkHeader(eboot, new byte[]{'S', 'C', 'E', 0}, "EBOOT.BIN");
        Item license = find("PS3_GAME/LICDIR/LIC.DAT");
        if (license != null && !license.dir)
            checkHeader(license, "PS3LICDA".getBytes(StandardCharsets.US_ASCII), "LIC.DAT");
    }

    private Item find(String name) {
        for (Map.Entry<String, Item> e : entries.entrySet())
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    private void checkHeader(Item item, byte[] expected, String label) throws IOException {
        if (item.segments.isEmpty() || item.segments.get(0).length < expected.length)
            throw new IOException(label + " appears truncated");
        byte[] actual = reader.read(item.segments.get(0).offset, expected.length);
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i])
                throw new IOException(label + " is not decrypted or has an invalid header. "
                        + "Encrypted PS3 ISOs require disc-key decryption before JB extraction. "
                        + "Nothing has been exported.");
        }
    }

    private static boolean magic(byte[] b) {
        return b.length >= 6 && b[1] == 'C' && b[2] == 'D'
                && b[3] == '0' && b[4] == '0' && b[5] == '1';
    }
    private static int le16(byte[] b, int i) {
        return (b[i] & 255) | (b[i + 1] & 255) << 8;
    }
    private static long le32(byte[] b, int i) {
        return (b[i] & 255L) | ((b[i + 1] & 255L) << 8)
                | ((b[i + 2] & 255L) << 16) | ((b[i + 3] & 255L) << 24);
    }
    private static String parent(String path) {
        int x = path.lastIndexOf('/');
        return x < 0 ? "" : path.substring(0, x);
    }
    private static String basename(String path) {
        int x = path.lastIndexOf('/');
        return x < 0 ? path : path.substring(x + 1);
    }
    private static String folderName(String filename) {
        String value = filename.replaceFirst("(?i)\\.iso$", "")
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..")) value = "PS3 Game";
        if (value.length() > 70) value = value.substring(0, 70);
        return value + " [JB]";
    }
    private static Uri create(ContentResolver resolver, Uri parent, String mime, String name)
            throws IOException {
        Uri result = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (result == null) throw new IOException("Storage provider refused to create " + name);
        return result;
    }
}
