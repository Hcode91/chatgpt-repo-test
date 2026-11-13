package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Ren'Py archives (.rpa), as read by Ren'Py's own loader and by unrpa.
 *
 * <pre>
 * RPA-3.0 / 3.2 / 4.0   "RPA-3.0 <index offset, hex> <key, hex>\n"; offsets and lengths in the index are XORed with key
 * ALT-1.0               "ALT-1.0 <key, hex> <index offset, hex>\n"; key is additionally XORed with 0xDABE8DF0
 * RPA-2.0               "RPA-2.0 <index offset, hex>\n"; no key
 * RPA-1.0               the index is a separate .rpi file (no header); data in the .rpa
 * </pre>
 * The index is a zlib-compressed Python pickle of {path: [(offset, length[, prefix]), ...]}. A file is the
 * concatenation of its pieces; a piece with a prefix is the prefix bytes followed by {@code length} bytes from the
 * archive, as in Ren'Py's loader. The pickle reader here only builds plain data and never runs code.
 * ZiX-12A/B archives need the game's loader.pyo decompiled to find their key and aren't supported.
 */
public final class RenpyArchive {
    private static final long MAX_INDEX = 256L << 20;
    private static final int MAX_FILES = 500000;

    private RenpyArchive() {}

    public static final class Piece {
        public final long offset, length;
        public final byte[] prefix;
        Piece(long offset, long length, byte[] prefix) { this.offset = offset; this.length = length; this.prefix = prefix; }
    }

    public static final class FileEntry {
        public final String path;
        public final List<Piece> pieces = new ArrayList<>();
        FileEntry(String path) { this.path = path; }
        public long size() {
            long n = 0;
            for (Piece p : pieces) n += p.prefix.length + p.length;
            return n;
        }
    }

    public static final class Info {
        public final String version;
        public final List<FileEntry> files = new ArrayList<>();
        public long totalBytes;
        Info(String version) { this.version = version; }
        public String description() {
            return "Ren'Py archive (" + version + ") · " + files.size() + " files";
        }
    }

    // ------------------------------------------------------------------ detection

    /** The header line names the version: RPA-2.0, RPA-3.x, RPA-4.0, ALT-1.0 or ZiX-12x. Null when not an archive. */
    public static String version(FileChannel ch) throws IOException {
        if (ch.size() < 16) return null;
        String head = new String(read(ch, 0, (int) Math.min(64, ch.size())), StandardCharsets.ISO_8859_1);
        for (String v : new String[]{"RPA-2.0 ", "RPA-3.0 ", "RPA-3.2 ", "RPA-4.0 ", "ALT-1.0 ", "ZiX-12A", "ZiX-12B"})
            if (head.startsWith(v)) return v.trim();
        return null;
    }

    public static boolean isArchive(FileChannel ch) throws IOException { return version(ch) != null; }

    /** An RPA-1.0 index (.rpi): zlib data that unpickles to a dict of lists. */
    public static boolean isRpi(FileChannel ch) throws IOException {
        if (ch.size() < 8 || ch.size() > MAX_INDEX) return false;
        byte[] h = read(ch, 0, 2);
        if ((h[0] & 255) != 0x78) return false;
        try { return index(inflate(read(ch, 0, (int) ch.size())), 0) != null; }
        catch (IOException notAnIndex) { return false; }
    }

    // ------------------------------------------------------------------ index

    public static Info parse(FileChannel ch) throws IOException {
        String version = version(ch);
        if (version == null) throw new IOException("Not a Ren'Py archive");
        if (version.startsWith("ZiX"))
            throw new IOException("This is a " + version + " archive. Its key is hidden in the game's loader.pyo and needs a " +
                    "Python decompiler to recover, so it can't be opened here (unrpa on a computer can, with loader.pyo).");
        String line = headerLine(ch);
        String[] parts = line.trim().split("\\s+");
        long offset, key = 0;
        try {
            switch (version) {
                case "RPA-2.0":
                    offset = Long.parseLong(parts[1], 16);
                    break;
                case "ALT-1.0":
                    key = Long.parseLong(parts[1], 16) ^ 0xDABE8DF0L;
                    offset = Long.parseLong(parts[2], 16);
                    break;
                default:
                    offset = Long.parseLong(parts[1], 16);
                    key = Long.parseLong(parts[2], 16);
            }
        } catch (RuntimeException e) {
            throw new IOException("Unreadable " + version + " header: " + line.trim());
        }
        if (offset <= 0 || offset >= ch.size() || ch.size() - offset > MAX_INDEX)
            throw new IOException("The archive index lies outside the file");
        Info info = new Info(version);
        fill(info, inflate(read(ch, offset, (int) (ch.size() - offset))), key, ch.size());
        return info;
    }

    /** RPA-1.0: index from the .rpi, data in the .rpa. */
    public static Info parseRpi(FileChannel rpi, long dataSize) throws IOException {
        Info info = new Info("RPA-1.0");
        fill(info, inflate(read(rpi, 0, (int) rpi.size())), 0, dataSize);
        return info;
    }

    private static void fill(Info info, byte[] pickle, long key, long dataSize) throws IOException {
        Map<Object, Object> index = index(pickle, key);
        if (index == null) throw new IOException("The archive index isn't in the expected form");
        for (Map.Entry<Object, Object> e : index.entrySet()) {
            if (info.files.size() >= MAX_FILES) throw new IOException("Too many files in this archive");
            FileEntry f = new FileEntry(path(e.getKey()));
            for (Object o : (List<?>) e.getValue()) {
                Object[] t = (Object[]) o;
                long off = num(t[0]) ^ key, len = num(t[1]) ^ key;
                byte[] prefix = t.length > 2 ? bytes(t[2]) : new byte[0];
                if (off < 0 || len < 0 || off + len > dataSize)
                    throw new IOException("Entry " + f.path + " lies outside the archive (wrong key or corrupt index)");
                f.pieces.add(new Piece(off, len, prefix));
            }
            info.files.add(f);
            info.totalBytes += f.size();
        }
    }

    /** Unpickles and checks the shape {path: [tuple, ...]}; returns null when the shape is different. */
    private static Map<Object, Object> index(byte[] pickle, long key) throws IOException {
        Object root = new Unpickler(pickle).load();
        if (!(root instanceof Map)) return null;
        @SuppressWarnings("unchecked") Map<Object, Object> map = (Map<Object, Object>) root;
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            if (!(e.getKey() instanceof String || e.getKey() instanceof byte[])) return null;
            if (!(e.getValue() instanceof List)) return null;
            for (Object o : (List<?>) e.getValue()) {
                if (!(o instanceof Object[])) return null;
                Object[] t = (Object[]) o;
                if (t.length < 2 || t.length > 3 || !(t[0] instanceof Long || t[0] instanceof BigInteger)
                        || !(t[1] instanceof Long || t[1] instanceof BigInteger)) return null;
            }
        }
        return map;
    }

    private static String path(Object key) {
        String s = key instanceof String ? (String) key : new String((byte[]) key, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String part : s.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            for (char c : part.toCharArray()) sb.append(c < 0x20 || ":*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        return sb.length() == 0 ? "unnamed" : sb.toString();
    }
    private static long num(Object o) { return o instanceof Long ? (Long) o : ((BigInteger) o).longValue(); }
    private static byte[] bytes(Object o) {
        if (o == null) return new byte[0];
        if (o instanceof byte[]) return (byte[]) o;
        if (o instanceof String) return ((String) o).getBytes(StandardCharsets.ISO_8859_1);   // as Ren'Py: latin-1
        return new byte[0];
    }

    // ------------------------------------------------------------------ reading files

    public static void copy(FileChannel ch, FileEntry f, OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(1 << 20);
        for (Piece p : f.pieces) {
            out.write(p.prefix);
            long pos = p.offset, left = p.length;
            while (left > 0) {
                buf.clear();
                buf.limit((int) Math.min(buf.capacity(), left));
                int n = ch.read(buf, pos);
                if (n <= 0) throw new IOException(f.path + " runs past the end of the archive");
                out.write(buf.array(), 0, n);
                pos += n; left -= n;
            }
        }
    }

    public static byte[] read(FileChannel ch, FileEntry f) throws IOException {
        if (f.size() > Integer.MAX_VALUE - 16) throw new IOException(f.path + " is too large to load");
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.size());
        copy(ch, f, out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ writing (repacking)

    /** One file to pack: its path inside the archive (with '/'), its size, and a way to read it. */
    public interface Source {
        String path();
        long size();
        java.io.InputStream open() throws IOException;
    }

    /** Ren'Py's archiver uses this fixed key so rebuilt archives differ as little as possible. */
    static final long WRITE_KEY = 0x42424242L;
    private static final byte[] PADDING = "Made with Ren'Py.".getBytes(StandardCharsets.ISO_8859_1);
    private static final int HEADER_LENGTH = 34;            // "RPA-3.0 %016x %08x\n"

    /** Progress and cancellation for {@link #write}. */
    public interface Progress { void file(int index, int total, String path); boolean cancelled(); }

    /**
     * Writes an RPA-3.0 archive in one pass, laid out like Ren'Py's own archiver: header, then each file preceded
     * by "Made with Ren'Py.", then the zlib-compressed index. The index is a protocol-2 pickle, which both
     * Python 2 (Ren'Py 7 and earlier) and Python 3 (Ren'Py 8) builds read. Files are written in path order.
     * Returns the archive size.
     */
    public static long write(List<Source> sources, OutputStream out, Progress progress) throws IOException {
        List<Source> files = new ArrayList<>(sources);
        java.util.Collections.sort(files, (x, y) -> x.path().compareTo(y.path()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        long[] offsets = new long[files.size()];
        long pos = HEADER_LENGTH;
        for (int i = 0; i < files.size(); i++) {
            Source f = files.get(i);
            if (!seen.add(f.path())) throw new IOException("Two files would have the same path: " + f.path());
            if (f.size() < 0) throw new IOException("Unknown size for " + f.path());
            offsets[i] = pos + PADDING.length;
            pos = offsets[i] + f.size();
        }
        long indexOffset = pos;
        out.write(String.format(Locale.ROOT, "RPA-3.0 %016x %08x\n", indexOffset, WRITE_KEY).getBytes(StandardCharsets.ISO_8859_1));
        byte[] buf = new byte[1 << 20];
        for (int i = 0; i < files.size(); i++) {
            Source f = files.get(i);
            if (progress != null) {
                if (progress.cancelled()) throw new IOException("Cancelled");
                progress.file(i + 1, files.size(), f.path());
            }
            out.write(PADDING);
            long copied = 0;
            try (java.io.InputStream in = f.open()) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    copied += n;
                    if (copied > f.size()) break;
                    out.write(buf, 0, n);
                }
            }
            if (copied != f.size()) throw new IOException(f.path() + " changed size while being packed");
        }
        byte[] index = deflate(indexPickle(files, offsets));
        out.write(index);
        return indexOffset + index.length;
    }

    /** {path: [(offset ^ key, length ^ key, "")]} as a protocol-2 pickle. */
    static byte[] indexPickle(List<Source> files, long[] offsets) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x80); p.write(2);            // PROTO 2
        p.write('}');                         // EMPTY_DICT
        p.write('(');                         // MARK
        for (int i = 0; i < files.size(); i++) {
            byte[] name = files.get(i).path().getBytes(StandardCharsets.UTF_8);
            p.write('X'); le32(p, name.length); p.write(name, 0, name.length);         // BINUNICODE
            p.write(']');                                                                // EMPTY_LIST
            pickleInt(p, offsets[i] ^ WRITE_KEY);
            pickleInt(p, files.get(i).size() ^ WRITE_KEY);
            p.write('U'); p.write(0);                                                    // SHORT_BINSTRING ""
            p.write(0x87);                                                               // TUPLE3
            p.write('a');                                                                // APPEND
        }
        p.write('u');                         // SETITEMS
        p.write('.');                         // STOP
        return p.toByteArray();
    }

    private static void pickleInt(ByteArrayOutputStream p, long v) {
        if (v >= 0 && v <= Integer.MAX_VALUE) { p.write('J'); le32(p, (int) v); return; }
        byte[] be = BigInteger.valueOf(v).toByteArray();                                 // LONG1, little-endian
        p.write(0x8a); p.write(be.length);
        for (int i = be.length - 1; i >= 0; i--) p.write(be[i]);
    }
    private static void le32(ByteArrayOutputStream p, int v) { for (int i = 0; i < 4; i++) p.write(v >>> (8 * i)); }

    private static byte[] deflate(byte[] data) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(9);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 64);
            byte[] buf = new byte[65536];
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
            return out.toByteArray();
        } finally { deflater.end(); }
    }

    // ------------------------------------------------------------------ helpers

    private static String headerLine(FileChannel ch) throws IOException {
        byte[] b = read(ch, 0, (int) Math.min(256, ch.size()));
        int end = 0;
        while (end < b.length && b[end] != '\n') end++;
        return new String(b, 0, end, StandardCharsets.ISO_8859_1);
    }

    static byte[] inflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 4));
            byte[] buf = new byte[65536];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > MAX_INDEX) throw new IOException("Archive index is unreasonably large");
            }
            if (!inflater.finished()) throw new IOException("The archive index is truncated");
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("The archive index isn't valid zlib data");
        } finally { inflater.end(); }
    }

    private static byte[] read(FileChannel ch, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) if (ch.read(buf, offset + buf.position()) < 0) throw new IOException("Unexpected end of file");
        return buf.array();
    }

    /**
     * A data-only pickle reader (protocols 0–5): dicts, lists, tuples, numbers, text, bytes, None, booleans, and the
     * two globals pickle uses to encode bytes for older protocols (_codecs.encode, builtins.bytes). Anything that
     * would construct other objects is rejected.
     */
    static final class Unpickler {
        private static final Object MARK = new Object();
        private final byte[] d;
        private int pos;
        private final ArrayList<Object> stack = new ArrayList<>();
        private final ArrayList<Integer> marks = new ArrayList<>();
        private final Map<Long, Object> memo = new HashMap<>();

        Unpickler(byte[] d) { this.d = d; }

        Object load() throws IOException {
            try {
                while (true) {
                    int op = u8();
                    switch (op) {
                        case 0x80: u8(); break;                                         // PROTO
                        case 0x95: pos += 8; break;                                     // FRAME
                        case '.': return pop();                                         // STOP
                        case '(': marks.add(stack.size()); break;                       // MARK
                        case '}': stack.add(new LinkedHashMap<>()); break;
                        case ']': stack.add(new ArrayList<>()); break;
                        case ')': stack.add(new Object[0]); break;
                        case 'N': stack.add(null); break;
                        case 0x88: stack.add(Boolean.TRUE); break;
                        case 0x89: stack.add(Boolean.FALSE); break;
                        case 'J': stack.add((long) le(4)); break;                       // BININT
                        case 'K': stack.add((long) u8()); break;                        // BININT1
                        case 'M': stack.add((long) (u8() | u8() << 8)); break;          // BININT2
                        case 0x8a: stack.add(bigint(u8())); break;                      // LONG1
                        case 0x8b: stack.add(bigint((int) le(4))); break;               // LONG4
                        case 'I': {                                                     // INT (text)
                            String s = line();
                            if (s.equals("00")) stack.add(Boolean.FALSE);
                            else if (s.equals("01")) stack.add(Boolean.TRUE);
                            else stack.add(Long.parseLong(s));
                            break;
                        }
                        case 'L': {                                                     // LONG (text)
                            String s = line();
                            if (s.endsWith("L")) s = s.substring(0, s.length() - 1);
                            stack.add(normal(new BigInteger(s)));
                            break;
                        }
                        case 'X': stack.add(utf8((int) le(4))); break;                  // BINUNICODE
                        case 0x8c: stack.add(utf8(u8())); break;                        // SHORT_BINUNICODE
                        case 0x8d: stack.add(utf8(len8())); break;                      // BINUNICODE8
                        case 'V': stack.add(rawUnicodeEscape(line())); break;           // UNICODE (text)
                        case 'T': stack.add(raw((int) le(4))); break;                   // BINSTRING (py2 str)
                        case 'U': stack.add(raw(u8())); break;                          // SHORT_BINSTRING
                        case 'B': stack.add(raw((int) le(4))); break;                   // BINBYTES
                        case 'C': stack.add(raw(u8())); break;                          // SHORT_BINBYTES
                        case 0x8e: stack.add(raw(len8())); break;                       // BINBYTES8
                        case 0x96: stack.add(raw(len8())); break;                       // BYTEARRAY8
                        case 'S': stack.add(quoted(line())); break;                     // STRING (text)
                        case 0x85: { Object a = pop(); stack.add(new Object[]{a}); break; }
                        case 0x86: { Object b = pop(), a = pop(); stack.add(new Object[]{a, b}); break; }
                        case 0x87: { Object c = pop(), b = pop(), a = pop(); stack.add(new Object[]{a, b, c}); break; }
                        case 't': stack.add(popMark().toArray()); break;                // TUPLE
                        case 'l': stack.add(popMark()); break;                          // LIST
                        case 'd': {                                                     // DICT
                            List<Object> items = popMark();
                            Map<Object, Object> m = new LinkedHashMap<>();
                            for (int i = 0; i + 1 < items.size(); i += 2) m.put(key(items.get(i)), items.get(i + 1));
                            stack.add(m);
                            break;
                        }
                        case 's': { Object v = pop(), k = pop(); map(peek()).put(key(k), v); break; }   // SETITEM
                        case 'u': {                                                     // SETITEMS
                            List<Object> items = popMark();
                            Map<Object, Object> m = map(peek());
                            for (int i = 0; i + 1 < items.size(); i += 2) m.put(key(items.get(i)), items.get(i + 1));
                            break;
                        }
                        case 'a': { Object v = pop(); list(peek()).add(v); break; }      // APPEND
                        case 'e': { List<Object> items = popMark(); list(peek()).addAll(items); break; }   // APPENDS
                        case 'p': memo.put(Long.parseLong(line()), peek()); break;      // PUT
                        case 'q': memo.put((long) u8(), peek()); break;                 // BINPUT
                        case 'r': memo.put(le(4) & 0xffffffffL, peek()); break;         // LONG_BINPUT
                        case 0x94: memo.put((long) memo.size(), peek()); break;         // MEMOIZE
                        case 'g': stack.add(memo(Long.parseLong(line()))); break;       // GET
                        case 'h': stack.add(memo(u8())); break;                         // BINGET
                        case 'j': stack.add(memo(le(4) & 0xffffffffL)); break;          // LONG_BINGET
                        case 'c': {                                                     // GLOBAL
                            String module = line(), name = line();
                            stack.add(global(module, name));
                            break;
                        }
                        case 0x93: {                                                    // STACK_GLOBAL
                            Object name = pop(), module = pop();
                            stack.add(global(String.valueOf(module), String.valueOf(name)));
                            break;
                        }
                        case 'R': {                                                     // REDUCE
                            Object[] args = (Object[]) pop();
                            Object fn = pop();
                            stack.add(call(fn, args));
                            break;
                        }
                        default:
                            throw new IOException(String.format(Locale.ROOT, "Unsupported pickle opcode 0x%02X at %d", op, pos - 1));
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException("The archive index pickle is malformed (" + e.getClass().getSimpleName() + ")");
            }
        }

        private static final String CODECS_ENCODE = "_codecs.encode", BYTES = "bytes";
        private static Object global(String module, String name) throws IOException {
            if (module.equals("_codecs") && name.equals("encode")) return CODECS_ENCODE;
            if ((module.equals("__builtin__") || module.equals("builtins")) && name.equals("bytes")) return BYTES;
            throw new IOException("The archive index refers to " + module + "." + name + ", which isn't plain data");
        }
        private static Object call(Object fn, Object[] args) throws IOException {
            if (fn == CODECS_ENCODE && args.length >= 1 && args[0] instanceof String) {
                String enc = args.length > 1 ? String.valueOf(args[1]) : "utf-8";
                return ((String) args[0]).getBytes(enc.equalsIgnoreCase("latin1") || enc.equalsIgnoreCase("latin-1")
                        ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
            }
            if (fn == BYTES && args.length == 0) return new byte[0];
            throw new IOException("The archive index calls code, which isn't plain data");
        }

        private Object key(Object k) { return k instanceof byte[] ? new String((byte[]) k, StandardCharsets.UTF_8) : k; }
        @SuppressWarnings("unchecked") private static Map<Object, Object> map(Object o) { return (Map<Object, Object>) o; }
        @SuppressWarnings("unchecked") private static List<Object> list(Object o) { return (List<Object>) o; }
        private Object memo(long k) throws IOException {
            if (!memo.containsKey(k)) throw new IOException("Pickle refers to a missing memo entry");
            return memo.get(k);
        }
        private int u8() { return d[pos++] & 255; }
        private long le(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) v |= (long) (d[pos++] & 255) << (8 * i);
            return n == 4 ? (int) v : v;
        }
        private int len8() throws IOException {
            long n = le(8);
            if (n < 0 || n > d.length) throw new IOException("Pickle string length out of range");
            return (int) n;
        }
        private byte[] raw(int n) throws IOException {
            if (n < 0 || pos + n > d.length) throw new IOException("Pickle data truncated");
            byte[] b = new byte[n];
            System.arraycopy(d, pos, b, 0, n);
            pos += n;
            return b;
        }
        private String utf8(int n) throws IOException { return new String(raw(n), StandardCharsets.UTF_8); }
        private String line() {
            int start = pos;
            while (d[pos] != '\n') pos++;
            return new String(d, start, pos++ - start, StandardCharsets.ISO_8859_1);
        }
        private Object bigint(int n) throws IOException {
            byte[] le = raw(n);
            if (n == 0) return 0L;
            byte[] be = new byte[n];
            for (int i = 0; i < n; i++) be[i] = le[n - 1 - i];
            return normal(new BigInteger(be));
        }
        private static Object normal(BigInteger v) { return v.bitLength() < 64 ? (Object) v.longValue() : v; }
        private Object pop() { return stack.remove(stack.size() - 1); }
        private Object peek() { return stack.get(stack.size() - 1); }
        private List<Object> popMark() {
            int m = marks.remove(marks.size() - 1);
            List<Object> items = new ArrayList<>(stack.subList(m, stack.size()));
            while (stack.size() > m) stack.remove(stack.size() - 1);
            return items;
        }
        /** Python 2 'S' strings: a quoted repr. */
        private static byte[] quoted(String s) throws IOException {
            if (s.length() < 2 || (s.charAt(0) != '\'' && s.charAt(0) != '"')) throw new IOException("Bad quoted pickle string");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 1; i < s.length() - 1; i++) {
                char c = s.charAt(i);
                if (c != '\\') { out.write(c); continue; }
                char e = s.charAt(++i);
                switch (e) {
                    case 'n': out.write('\n'); break;
                    case 'r': out.write('\r'); break;
                    case 't': out.write('\t'); break;
                    case 'x': out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; break;
                    default:
                        if (e >= '0' && e <= '7') {
                            int j = i, v = 0;
                            while (j < s.length() - 1 && j < i + 3 && s.charAt(j) >= '0' && s.charAt(j) <= '7') v = v * 8 + s.charAt(j++) - '0';
                            out.write(v); i = j - 1;
                        } else out.write(e);
                }
            }
            return out.toByteArray();
        }
        private static String rawUnicodeEscape(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 5 < s.length() + 0 && s.charAt(i + 1) == 'u') {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16)); i += 5;
                } else if (c == '\\' && i + 9 < s.length() + 0 && s.charAt(i + 1) == 'U') {
                    sb.appendCodePoint(Integer.parseInt(s.substring(i + 2, i + 10), 16)); i += 9;
                } else sb.append(c);
            }
            return sb.toString();
        }
    }
}
