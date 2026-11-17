package com.retrotools.cciextractor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Unity asset bundles (UnityFS, bundle format 6–8, Unity 5.3 – 6) and the SerializedFiles inside them.
 *
 * Reading is random access: only the compressed blocks a read touches are decompressed (LZ4/LZ4HC; uncompressed
 * blocks too). Texture2D objects are located through each file's type tree, so field layouts don't have to be
 * hard-coded per Unity version.
 *
 * Writing is an in-place patch: replacement bytes must be exactly as long as what they replace (a texture re-encoded
 * at its original format, size and mip count always is). Blocks that a patch touches are stored uncompressed; every
 * other block is copied byte-for-byte, so nothing else in the bundle changes and no offsets move.
 */
final class UnityBundle {

    /** Random-access byte source: a whole file, a region of a file (a zip entry), or memory. */
    interface Source {
        long size();
        void read(long pos, byte[] dst, int off, int len) throws IOException;
    }

    static Source of(final byte[] data) {
        return new Source() {
            public long size() { return data.length; }
            public void read(long pos, byte[] dst, int off, int len) throws IOException {
                if (pos < 0 || pos + len > data.length) throw new IOException("Read past end of bundle");
                System.arraycopy(data, (int) pos, dst, off, len);
            }
        };
    }

    static Source of(final FileChannel ch, final long start, final long length) {
        return new Source() {
            public long size() { return length; }
            public void read(long pos, byte[] dst, int off, int len) throws IOException {
                if (pos < 0 || pos + len > length) throw new IOException("Read past end of bundle");
                ByteBuffer bb = ByteBuffer.wrap(dst, off, len);
                long at = start + pos;
                while (bb.hasRemaining()) {
                    int n = ch.read(bb, at);
                    if (n < 0) throw new IOException("Unexpected end of file");
                    at += n;
                }
            }
        };
    }

    static boolean isBundle(Source s) throws IOException {
        if (s.size() < 48) return false;
        byte[] m = new byte[8];
        s.read(0, m, 0, 8);
        return new String(m, 0, 7, StandardCharsets.ISO_8859_1).equals("UnityFS") && m[7] == 0;
    }

    static final class Block { long uOffset, cOffset; int uSize, cSize, flags; }
    static final class Node { long offset, size; int flags; String path; }

    final Source src;
    final int formatVersion;
    final String unityVersion, unityRevision;
    final int headerFlags;
    final byte[] hash = new byte[16];
    final Block[] blocks;
    final Node[] nodes;
    final long totalUncompressed;

    private UnityBundle(Source src, int formatVersion, String uv, String rev, int flags, Block[] blocks, Node[] nodes) {
        this.src = src; this.formatVersion = formatVersion; this.unityVersion = uv; this.unityRevision = rev;
        this.headerFlags = flags; this.blocks = blocks; this.nodes = nodes;
        long t = 0;
        for (Block b : blocks) t += b.uSize;
        this.totalUncompressed = t;
    }

    // ------------------------------------------------------------------ big-endian cursor
    private static final class Be {
        final byte[] b; int p;
        Be(byte[] b, int p) { this.b = b; this.p = p; }
        void need(int n) throws IOException { if (p < 0 || p + n > b.length) throw new IOException("Bundle header is truncated"); }
        int u8() throws IOException { need(1); return b[p++] & 255; }
        int u16() throws IOException { need(2); int v = ((b[p] & 255) << 8) | (b[p + 1] & 255); p += 2; return v; }
        int i32() throws IOException { need(4); int v = ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16) | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255); p += 4; return v; }
        long i64() throws IOException { long hi = i32() & 0xffffffffL; return (hi << 32) | (i32() & 0xffffffffL); }
        String cstr() throws IOException {
            int s = p;
            while (true) { need(1); if (b[p++] == 0) break; if (p - s > 4096) throw new IOException("Bad string in bundle header"); }
            return new String(b, s, p - s - 1, StandardCharsets.UTF_8);
        }
    }

    static UnityBundle open(Source src) throws IOException {
        if (!isBundle(src)) throw new IOException("Not a UnityFS bundle");
        int headLen = (int) Math.min(src.size(), 1024);
        byte[] head = new byte[headLen];
        src.read(0, head, 0, headLen);
        Be r = new Be(head, 8);
        int version = r.i32();
        if (version < 6 || version > 8) throw new IOException("Unsupported UnityFS bundle version " + version);
        String uv = r.cstr(), rev = r.cstr();
        long size = r.i64();
        int cInfo = r.i32(), uInfo = r.i32(), flags = r.i32();
        if (cInfo < 0 || uInfo < 0 || uInfo > 256 << 20 || size > src.size() + 16)
            throw new IOException("Bundle header sizes are out of range");
        long pos = r.p;
        if (version >= 7) pos = align(pos, 16);
        long infoAt = (flags & 0x80) != 0 ? src.size() - cInfo : pos;
        if (infoAt < 0 || infoAt + cInfo > src.size()) throw new IOException("Bundle block table is outside the file");
        byte[] cin = new byte[cInfo];
        src.read(infoAt, cin, 0, cInfo);
        byte[] info = decompress(cin, 0, cInfo, uInfo, flags & 0x3F);
        long dataStart = (flags & 0x80) != 0 ? pos : pos + cInfo;
        if ((flags & 0x200) != 0) dataStart = align(dataStart, 16);

        Be b = new Be(info, 16);
        int blockCount = b.i32();
        if (blockCount < 0 || blockCount > 1_000_000) throw new IOException("Bad block count");
        Block[] blocks = new Block[blockCount];
        long u = 0, c = dataStart;
        for (int i = 0; i < blockCount; i++) {
            Block k = new Block();
            k.uSize = b.i32(); k.cSize = b.i32(); k.flags = b.u16();
            if (k.uSize < 0 || k.cSize < 0) throw new IOException("Bad block size");
            k.uOffset = u; k.cOffset = c;
            u += k.uSize; c += k.cSize;
            blocks[i] = k;
        }
        if (c > src.size()) throw new IOException("Bundle data is truncated (" + c + " > " + src.size() + ")");
        int nodeCount = b.i32();
        if (nodeCount < 0 || nodeCount > 100_000) throw new IOException("Bad file count in bundle");
        Node[] nodes = new Node[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            Node n = new Node();
            n.offset = b.i64(); n.size = b.i64(); n.flags = b.i32(); n.path = b.cstr();
            if (n.offset < 0 || n.size < 0 || n.offset + n.size > u) throw new IOException("File " + n.path + " is outside the bundle data");
            nodes[i] = n;
        }
        UnityBundle bundle = new UnityBundle(src, version, uv, rev, flags, blocks, nodes);
        System.arraycopy(info, 0, bundle.hash, 0, 16);
        return bundle;
    }

    static long align(long v, int a) { long m = v % a; return m == 0 ? v : v + a - m; }

    static byte[] decompress(byte[] in, int off, int len, int outLen, int type) throws IOException {
        switch (type) {
            case 0:
                if (len != outLen) throw new IOException("Stored block size mismatch");
                byte[] copy = new byte[len];
                System.arraycopy(in, off, copy, 0, len);
                return copy;
            case 2: case 3: {
                byte[] out = new byte[outLen];
                lz4(in, off, len, out);
                return out;
            }
            case 1: throw new IOException("This bundle is LZMA-compressed, which isn't supported yet (LZ4 and uncompressed bundles are)");
            default: throw new IOException("Unknown bundle compression type " + type);
        }
    }

    /** Raw LZ4 block decoder (no frame), output size known in advance. */
    static void lz4(byte[] s, int sp, int sLen, byte[] d) throws IOException {
        int end = sp + sLen, dp = 0;
        try {
            while (sp < end) {
                int token = s[sp++] & 255;
                int lit = token >>> 4;
                if (lit == 15) { int x; do { x = s[sp++] & 255; lit += x; } while (x == 255); }
                if (lit > 0) {
                    if (sp + lit > end || dp + lit > d.length) throw new IOException("LZ4 literal overrun");
                    System.arraycopy(s, sp, d, dp, lit); sp += lit; dp += lit;
                }
                if (sp >= end) break;
                int offset = (s[sp] & 255) | ((s[sp + 1] & 255) << 8); sp += 2;
                int len = token & 15;
                if (len == 15) { int x; do { x = s[sp++] & 255; len += x; } while (x == 255); }
                len += 4;
                int from = dp - offset;
                if (offset == 0 || from < 0 || dp + len > d.length) throw new IOException("LZ4 match out of range");
                if (offset >= len) { System.arraycopy(d, from, d, dp, len); dp += len; }
                else for (int i = 0; i < len; i++) d[dp++] = d[from + i];
            }
        } catch (ArrayIndexOutOfBoundsException e) { throw new IOException("LZ4 data is corrupt"); }
        if (dp != d.length) throw new IOException("LZ4 block decoded to " + dp + " bytes, expected " + d.length);
    }

    // ------------------------------------------------------------------ uncompressed view
    private final Map<Integer, byte[]> cache = new LinkedHashMap<Integer, byte[]>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> e) { return size() > 6; }
    };

    private int blockAt(long upos) {
        int lo = 0, hi = blocks.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Block k = blocks[mid];
            if (upos < k.uOffset) hi = mid - 1;
            else if (upos >= k.uOffset + k.uSize) lo = mid + 1;
            else return mid;
        }
        return -1;
    }

    private byte[] block(int i) throws IOException {
        byte[] got = cache.get(i);
        if (got != null) return got;
        Block k = blocks[i];
        byte[] c = new byte[k.cSize];
        src.read(k.cOffset, c, 0, k.cSize);
        got = decompress(c, 0, k.cSize, k.uSize, k.flags & 0x3F);
        cache.put(i, got);
        return got;
    }

    /** Reads from the bundle's uncompressed data stream (all files back to back). */
    void read(long upos, byte[] dst, int off, int len) throws IOException {
        while (len > 0) {
            int i = blockAt(upos);
            if (i < 0) throw new IOException("Read outside the bundle data");
            Block k = blocks[i];
            byte[] data = block(i);
            int at = (int) (upos - k.uOffset);
            int n = Math.min(len, k.uSize - at);
            System.arraycopy(data, at, dst, off, n);
            upos += n; off += n; len -= n;
        }
    }

    byte[] read(long upos, int len) throws IOException {
        if (len < 0) throw new IOException("Negative read");
        byte[] out = new byte[len];
        read(upos, out, 0, len);
        return out;
    }

    Node node(String path) {
        for (Node n : nodes) if (n.path.equals(path)) return n;
        return null;
    }

    // ------------------------------------------------------------------ patched copy
    private byte[] headerBytes(long totalSize, int infoLen, int flags) {
        java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream();
        try {
            h.write("UnityFS".getBytes(StandardCharsets.ISO_8859_1)); h.write(0);
            h.write(be32(formatVersion));
            h.write(unityVersion.getBytes(StandardCharsets.UTF_8)); h.write(0);
            h.write(unityRevision.getBytes(StandardCharsets.UTF_8)); h.write(0);
            h.write(be64(totalSize));
            h.write(be32(infoLen)); h.write(be32(infoLen)); h.write(be32(flags));
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return h.toByteArray();
    }

    /**
     * Writes a copy of this bundle with {@code patches} (uncompressed offset → replacement bytes) applied.
     * Returns the number of bytes written.
     */
    long writePatched(TreeMap<Long, byte[]> patches, OutputStream out) throws IOException {
        for (Map.Entry<Long, byte[]> p : patches.entrySet())
            if (p.getKey() < 0 || p.getKey() + p.getValue().length > totalUncompressed) throw new IOException("Patch outside bundle data");
        boolean[] touched = new boolean[blocks.length];
        for (Map.Entry<Long, byte[]> p : patches.entrySet()) {
            long a = p.getKey(), e = a + p.getValue().length;
            if (e == a) continue;
            for (int i = blockAt(a), last = blockAt(e - 1); i <= last; i++) touched[i] = true;
        }
        // block/directory info, stored uncompressed
        java.io.ByteArrayOutputStream info = new java.io.ByteArrayOutputStream();
        info.write(hash);
        info.write(be32(blocks.length));
        for (int i = 0; i < blocks.length; i++) {
            Block k = blocks[i];
            info.write(be32(k.uSize));
            info.write(be32(touched[i] ? k.uSize : k.cSize));
            int f = touched[i] ? (k.flags & ~0x3F) : k.flags;
            info.write((f >>> 8) & 255); info.write(f & 255);
        }
        info.write(be32(nodes.length));
        for (Node n : nodes) {
            info.write(be64(n.offset)); info.write(be64(n.size)); info.write(be32(n.flags));
            info.write(n.path.getBytes(StandardCharsets.UTF_8)); info.write(0);
        }
        byte[] infoBytes = info.toByteArray();
        int flags = headerFlags & ~0x3F & ~0x80;   // info uncompressed, placed right after the header

        long dataLen = 0;
        for (int i = 0; i < blocks.length; i++) dataLen += touched[i] ? blocks[i].uSize : blocks[i].cSize;
        int headLen = headerBytes(0, 0, flags).length;
        long pos = headLen;
        if (formatVersion >= 7) pos = align(pos, 16);
        pos += infoBytes.length;
        if ((flags & 0x200) != 0) pos = align(pos, 16);
        long total = pos + dataLen;

        long written = 0;
        byte[] head = headerBytes(total, infoBytes.length, flags);
        out.write(head); written += head.length;
        if (formatVersion >= 7) written += pad(out, written, 16);
        out.write(infoBytes); written += infoBytes.length;
        if ((flags & 0x200) != 0) written += pad(out, written, 16);

        byte[] buf = new byte[1 << 16];
        for (int i = 0; i < blocks.length; i++) {
            Block k = blocks[i];
            if (touched[i]) {
                byte[] data = block(i).clone();
                long bs = k.uOffset, be = bs + k.uSize;
                Map.Entry<Long, byte[]> first = patches.floorEntry(bs);
                Iterable<Map.Entry<Long, byte[]>> span = (first != null ? patches.tailMap(first.getKey(), true) : patches).entrySet();
                for (Map.Entry<Long, byte[]> p : span) {
                    long ps = p.getKey(), pe = ps + p.getValue().length;
                    if (ps >= be) break;
                    long s = Math.max(ps, bs), e = Math.min(pe, be);
                    if (s < e) System.arraycopy(p.getValue(), (int) (s - ps), data, (int) (s - bs), (int) (e - s));
                }
                out.write(data); written += data.length;
            } else {
                long at = k.cOffset; int left = k.cSize;
                while (left > 0) {
                    int n = Math.min(left, buf.length);
                    src.read(at, buf, 0, n);
                    out.write(buf, 0, n);
                    at += n; left -= n; written += n;
                }
            }
        }
        if (written != total) throw new IOException("Internal error: bundle size mismatch");
        return written;
    }

    private static long pad(OutputStream out, long at, int a) throws IOException {
        long n = align(at, a) - at;
        for (long i = 0; i < n; i++) out.write(0);
        return n;
    }
    static byte[] be32(int v) { return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v}; }
    static byte[] be64(long v) { byte[] b = new byte[8]; for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (56 - 8 * i)); return b; }

    // ================================================================== SerializedFile + type trees

    static final class TypeNode {
        String type, name; int level, byteSize, meta, typeFlags;
        final List<TypeNode> kids = new ArrayList<>();
    }

    static final class ObjectInfo { long pathId, start; int size, classId; TypeNode tree; }

    static final class SerializedFile {
        Node node; int version; boolean little; String unityVersion; boolean typeTrees;
        final List<ObjectInfo> objects = new ArrayList<>();
        /** External file paths, for PPtr fileIDs 1..n (e.g. "archive:/CAB-…/CAB-…"). */
        final List<String> externals = new ArrayList<>();
        ObjectInfo object(long pathId) { for (ObjectInfo o : objects) if (o.pathId == pathId) return o; return null; }
    }

    /** Unity's built-in type-tree string table (offsets with the top bit set point here). */
    private static final Map<Integer, String> COMMON_AT = new HashMap<>();
    static {
        String all = "AABB\0AnimationClip\0AnimationCurve\0AnimationState\0Array\0Base\0BitField\0bitset\0bool\0char\0" +
            "ColorRGBA\0Component\0data\0deque\0double\0dynamic_array\0FastPropertyName\0first\0float\0Font\0" +
            "GameObject\0Generic Mono\0GradientNEW\0GUID\0GUIStyle\0int\0list\0long long\0map\0Matrix4x4f\0" +
            "MdFour\0MonoBehaviour\0MonoScript\0m_ByteSize\0m_Curve\0m_EditorClassIdentifier\0m_EditorHideFlags\0" +
            "m_Enabled\0m_ExtensionPtr\0m_GameObject\0m_Index\0m_IsArray\0m_IsStatic\0m_MetaFlag\0m_Name\0" +
            "m_ObjectHideFlags\0m_PrefabInternal\0m_PrefabParentObject\0m_Script\0m_StaticEditorFlags\0m_Type\0" +
            "m_Version\0Object\0pair\0PPtr<Component>\0PPtr<GameObject>\0PPtr<Material>\0PPtr<MonoBehaviour>\0" +
            "PPtr<MonoScript>\0PPtr<Object>\0PPtr<Prefab>\0PPtr<Sprite>\0PPtr<TextAsset>\0PPtr<Texture>\0" +
            "PPtr<Texture2D>\0PPtr<Transform>\0Prefab\0Quaternionf\0Rectf\0RectInt\0RectOffset\0second\0set\0" +
            "short\0size\0SInt16\0SInt32\0SInt64\0SInt8\0staticvector\0string\0TextAsset\0TextMesh\0Texture\0" +
            "Texture2D\0Transform\0TypelessData\0UInt16\0UInt32\0UInt64\0UInt8\0unsigned int\0" +
            "unsigned long long\0unsigned short\0vector\0Vector2f\0Vector3f\0Vector4f\0" +
            "m_ScriptingClassIdentifier\0Gradient\0Type*\0int2_storage\0int3_storage\0BoundsInt\0" +
            "m_CorrespondingSourceObject\0m_PrefabInstance\0m_PrefabAsset\0FileSize\0Hash128\0" +
            "RenderingLayerMask\0fixed_array\0EntityId\0LoadableObjectId\0LoadableSceneId\0";
        int at = 0;
        while (at < all.length()) {
            int end = all.indexOf('\0', at);
            COMMON_AT.put(at, all.substring(at, end));
            at = end + 1;
        }
    }

    // little/big-endian cursor over a byte array, positions relative to the array start
    private static final class Cur {
        final byte[] b; int p; final boolean le;
        Cur(byte[] b, int p, boolean le) { this.b = b; this.p = p; this.le = le; }
        void need(int n) throws IOException { if (n < 0 || p < 0 || p + n > b.length) throw new IOException("Unexpected end of Unity data"); }
        int u8() throws IOException { need(1); return b[p++] & 255; }
        int u16() throws IOException { need(2); int a = b[p] & 255, c = b[p + 1] & 255; p += 2; return le ? a | (c << 8) : (a << 8) | c; }
        int i32() throws IOException {
            need(4); int v;
            if (le) v = (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16) | ((b[p + 3] & 255) << 24);
            else v = ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16) | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255);
            p += 4; return v;
        }
        long i64() throws IOException {
            long a = i32() & 0xffffffffL, c = i32() & 0xffffffffL;
            return le ? a | (c << 32) : (a << 32) | c;
        }
        String cstr() throws IOException {
            int s = p;
            while (true) { need(1); if (b[p++] == 0) break; }
            return new String(b, s, p - s - 1, StandardCharsets.UTF_8);
        }
        void align4() { p = (p + 3) & ~3; }
        void skip(long n) throws IOException { if (n < 0 || p + n > b.length) throw new IOException("Unexpected end of Unity data"); p += (int) n; }
    }

    /** Parses one SerializedFile's header and object table. Returns null if the node isn't a SerializedFile. */
    SerializedFile serializedFile(Node node) throws IOException {
        if (node.size < 48) return null;
        byte[] h = read(node.offset, 48);
        Cur c = new Cur(h, 0, false);
        long metaSize = c.i32() & 0xffffffffL;
        long fileSize = c.i32() & 0xffffffffL;
        int version = c.i32();
        long dataOffset = c.i32() & 0xffffffffL;
        if (version < 9 || version > 50) return null;
        int endian = c.u8(); c.skip(3);
        int headerLen = 20;
        if (version >= 22) {
            metaSize = c.i32() & 0xffffffffL; fileSize = c.i64(); dataOffset = c.i64(); c.i64();
            headerLen = 48;
        }
        if (fileSize != node.size || dataOffset > fileSize || metaSize > fileSize || metaSize > 64 << 20) return null;
        byte[] meta = read(node.offset, (int) (headerLen + metaSize));
        Cur m = new Cur(meta, headerLen, endian == 0);
        SerializedFile sf = new SerializedFile();
        sf.node = node; sf.version = version; sf.little = endian == 0;
        sf.unityVersion = m.cstr();
        m.i32(); // target platform
        sf.typeTrees = version < 13 || m.u8() != 0;
        int typeCount = m.i32();
        if (typeCount < 0 || typeCount > 100_000) throw new IOException("Bad type count");
        int[] classIds = new int[typeCount];
        TypeNode[] trees = new TypeNode[typeCount];
        for (int i = 0; i < typeCount; i++) {
            int classId = m.i32();
            if (version >= 16) m.u8();
            if (version >= 17) m.u16();
            if (version >= 13) {
                if ((version < 16 && classId < 0) || (version >= 16 && classId == 114)) m.skip(16);
                m.skip(16);
            }
            classIds[i] = classId;
            if (sf.typeTrees) {
                trees[i] = readTypeTree(m, version);
                if (version >= 21) { int deps = m.i32(); if (deps < 0) throw new IOException("Bad type dependencies"); m.skip(4L * deps); }
            }
        }
        if (version >= 7 && version < 14) m.i32();
        int objectCount = m.i32();
        if (objectCount < 0 || objectCount > 10_000_000) throw new IOException("Bad object count");
        for (int i = 0; i < objectCount; i++) {
            ObjectInfo o = new ObjectInfo();
            if (version >= 14) { m.align4(); o.pathId = m.i64(); } else o.pathId = m.i32();
            o.start = version >= 22 ? m.i64() : m.i32() & 0xffffffffL;
            o.start += dataOffset;
            o.size = m.i32();
            int typeIndex = m.i32();
            if (version < 16) { o.classId = m.u16(); m.u16(); if (version == 15 || version == 16) m.u8(); if (version < 11) m.u16(); }
            else {
                if (typeIndex < 0 || typeIndex >= typeCount) throw new IOException("Bad object type index");
                o.classId = classIds[typeIndex];
                o.tree = trees[typeIndex];
            }
            if (o.start < 0 || o.size < 0 || o.start + o.size > node.size) throw new IOException("Object outside its file");
            sf.objects.add(o);
        }
        // script types, then the externals table (needed to follow references into other files)
        try {
            if (version >= 11) {
                int scripts = m.i32();
                if (scripts < 0 || scripts > 1_000_000) throw new IOException("bad script count");
                for (int i = 0; i < scripts; i++) { m.i32(); if (version < 14) m.i32(); else { m.align4(); m.i64(); } }
            }
            int ext = m.i32();
            if (ext < 0 || ext > 100_000) throw new IOException("bad externals count");
            for (int i = 0; i < ext; i++) {
                if (version >= 6) m.cstr();
                if (version >= 5) { m.skip(16); m.i32(); }
                sf.externals.add(m.cstr());
            }
        } catch (IOException e) {
            sf.externals.clear(); // textures don't need it; references to other files just won't resolve
        }
        return sf;
    }

    private static TypeNode readTypeTree(Cur m, int version) throws IOException {
        int count = m.i32(), strLen = m.i32();
        if (count <= 0 || count > 200_000 || strLen < 0 || strLen > 16 << 20) throw new IOException("Bad type tree");
        int nodeSize = version >= 19 ? 32 : 24;
        int at = m.p;
        m.skip((long) count * nodeSize);
        byte[] strings = new byte[strLen];
        m.need(strLen); System.arraycopy(m.b, m.p, strings, 0, strLen); m.p += strLen;
        int save = m.p;
        m.p = at;
        TypeNode root = null;
        List<TypeNode> stack = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TypeNode n = new TypeNode();
            m.u16(); n.level = m.u8(); n.typeFlags = m.u8();
            int typeOff = m.i32(), nameOff = m.i32();
            n.byteSize = m.i32(); m.i32(); n.meta = m.i32();
            if (version >= 19) m.i64();
            n.type = str(strings, typeOff); n.name = str(strings, nameOff);
            if (i == 0) { root = n; stack.add(n); continue; }
            while (stack.size() > n.level) stack.remove(stack.size() - 1);
            if (stack.isEmpty()) throw new IOException("Malformed type tree");
            stack.get(stack.size() - 1).kids.add(n);
            stack.add(n);
        }
        m.p = save;
        return root;
    }

    private static String str(byte[] strings, int off) {
        if (off < 0) {
            String s = COMMON_AT.get(off & 0x7fffffff);
            return s == null ? "?" + (off & 0x7fffffff) : s;
        }
        int e = off;
        while (e < strings.length && strings[e] != 0) e++;
        return off >= strings.length ? "?" : new String(strings, off, e - off, StandardCharsets.UTF_8);
    }

    // ================================================================== generic object values

    /** A run of fixed-size array elements, left in the object's bytes (offset into the object data). */
    static final class Blob {
        final int offset, count, elemSize; final String type;
        Blob(int offset, int count, int elemSize, String type) { this.offset = offset; this.count = count; this.elemSize = elemSize; this.type = type; }
        int bytes() { return count * elemSize; }
    }

    /**
     * Reads a whole object through its type tree: structs become maps (field name → value), arrays of structs
     * become lists, strings become String, numbers Long/Double, byte runs and big fixed-size arrays {@link Blob}s.
     * The object must parse to exactly its stored size.
     */
    static Map<String, Object> readObject(byte[] data, boolean little, TypeNode root) throws IOException {
        Cur c = new Cur(data, 0, little);
        Map<String, Object> out = new LinkedHashMap<>();
        for (TypeNode k : root.kids) out.put(k.name, value(c, k));
        if ((root.meta & 0x4000) != 0) c.align4();
        if (c.p != data.length) throw new IOException(root.type + " layout doesn't match: parsed " + c.p + " of " + data.length + " bytes");
        return out;
    }

    private static Object value(Cur c, TypeNode n) throws IOException {
        boolean align = (n.meta & 0x4000) != 0;
        Object v;
        if (n.type.equals("string")) {
            int len = c.i32();
            if (len < 0) throw new IOException("Bad string length");
            c.need(len);
            v = new String(c.b, c.p, len, StandardCharsets.UTF_8);
            c.skip(len);
            if (!n.kids.isEmpty() && (n.kids.get(0).meta & 0x4000) != 0) align = true;
        } else if (n.type.equals("TypelessData")) {
            int len = c.i32();
            if (len < 0) throw new IOException("Bad data length");
            v = new Blob(c.p, len, 1, "UInt8");
            c.skip(len);
        } else if (isArrayNode(n)) {
            TypeNode arr = n.kids.get(0);
            if ((arr.meta & 0x4000) != 0) align = true;
            int count = c.i32();
            if (count < 0) throw new IOException("Bad array length");
            TypeNode elem = arr.kids.size() > 1 ? arr.kids.get(1) : null;
            if (elem == null) throw new IOException("Array without element type");
            long fixed = fixedSize(elem);
            if (fixed > 0 && (elem.kids.isEmpty() || count > 65536)) {
                if (fixed * count > c.b.length - c.p) throw new IOException("Array runs past the object");
                v = new Blob(c.p, count, (int) fixed, elem.type);
                c.skip(fixed * count);
            } else {
                List<Object> list = new ArrayList<>(Math.min(count, 4096));
                for (int i = 0; i < count; i++) list.add(value(c, elem));
                v = list;
            }
        } else if (n.kids.isEmpty()) {
            switch (n.byteSize) {
                case 1: v = (long) (n.type.equals("SInt8") ? (byte) c.u8() : c.u8()); break;
                case 2: { int x = c.u16(); v = (long) (n.type.startsWith("U") || n.type.startsWith("unsigned") ? x : (short) x); break; }
                case 4: { int x = c.i32();
                    if (n.type.equals("float")) v = (double) Float.intBitsToFloat(x);
                    else v = n.type.startsWith("U") || n.type.startsWith("unsigned") ? x & 0xffffffffL : (long) x;
                    break; }
                case 8: { long x = c.i64(); v = n.type.equals("double") ? Double.longBitsToDouble(x) : (Object) x; break; }
                default: throw new IOException("Unknown field size for " + n.type);
            }
        } else {
            Map<String, Object> map = new LinkedHashMap<>();
            for (TypeNode k : n.kids) map.put(k.name, value(c, k));
            v = map;
        }
        if (align) c.align4();
        return v;
    }

    /** Reads one object of a SerializedFile in this bundle (type tree, or a fallback for stripped files). */
    Map<String, Object> readObject(SerializedFile sf, ObjectInfo o, Map<String, TypeNode> fallback, byte[][] bytesOut) throws IOException {
        TypeNode tree = o.tree;
        if (tree == null && fallback != null) tree = fallback.get(majorMinor(sf.unityVersion) + "#" + o.classId);
        if (tree == null) throw new IOException("No type tree for class " + o.classId + " in " + sf.node.path);
        byte[] obj = read(sf.node.offset + o.start, o.size);
        if (bytesOut != null) bytesOut[0] = obj;
        return readObject(obj, sf.little, tree);
    }

    /** Values collected while walking an object: numbers, strings, and (offset, length) of byte blobs. */
    static final class Fields {
        final Map<String, Object> v = new HashMap<>();
        int consumed;
        long num(String k) { Object o = v.get(k); return o instanceof Long ? (Long) o : -1; }
        String text(String k) { Object o = v.get(k); return o instanceof String ? (String) o : null; }
        long[] blob(String k) { Object o = v.get(k); return o instanceof long[] ? (long[]) o : null; }
    }

    /** Walks an object with its type tree, recording leaf values up to {@code depth} levels deep. */
    static Fields walk(byte[] data, boolean little, TypeNode root, int depth) throws IOException {
        Cur c = new Cur(data, 0, little);
        Fields f = new Fields();
        for (TypeNode k : root.kids) walkNode(c, k, k.name, 1, depth, f);
        if ((root.meta & 0x4000) != 0) c.align4();
        f.consumed = c.p;
        return f;
    }

    private static boolean isArrayNode(TypeNode n) { return !n.kids.isEmpty() && n.kids.get(0).type.equals("Array"); }

    private static long fixedSize(TypeNode n) {
        if (n.kids.isEmpty()) return n.byteSize > 0 ? n.byteSize : -1;
        if (n.type.equals("string") || isArrayNode(n) || n.type.equals("TypelessData")) return -1;
        if ((n.meta & 0x4000) != 0) return -1;
        long t = 0;
        for (TypeNode k : n.kids) { long s = fixedSize(k); if (s < 0) return -1; t += s; }
        return t;
    }

    private static void walkNode(Cur c, TypeNode n, String path, int lvl, int depth, Fields f) throws IOException {
        boolean align = (n.meta & 0x4000) != 0;
        if (n.type.equals("string")) {
            int len = c.i32();
            if (len < 0) throw new IOException("Bad string length");
            c.need(len);
            if (lvl <= depth) f.v.put(path, new String(c.b, c.p, len, StandardCharsets.UTF_8));
            c.skip(len);
            if (!n.kids.isEmpty() && (n.kids.get(0).meta & 0x4000) != 0) align = true;
        } else if (n.type.equals("TypelessData")) {
            int len = c.i32();
            if (len < 0) throw new IOException("Bad data length");
            if (lvl <= depth) f.v.put(path, new long[]{c.p, len});
            c.skip(len);
        } else if (isArrayNode(n)) {
            TypeNode arr = n.kids.get(0);
            if ((arr.meta & 0x4000) != 0) align = true;
            int count = c.i32();
            if (count < 0) throw new IOException("Bad array length");
            TypeNode elem = arr.kids.size() > 1 ? arr.kids.get(1) : null;
            if (elem == null) throw new IOException("Array without element type");
            long fixed = fixedSize(elem);
            if (lvl <= depth && fixed == 1) f.v.put(path, new long[]{c.p, count});
            if (fixed >= 0) c.skip(fixed * count);
            else for (int i = 0; i < count; i++) walkNode(c, elem, path + "[]", lvl + 1, 0, f);
        } else if (n.kids.isEmpty()) {
            int sz = n.byteSize;
            if (sz < 0 || sz > 8) throw new IOException("Unknown field size for " + n.type);
            if (lvl <= depth) {
                long v;
                switch (sz) {
                    case 1: v = c.u8(); break;
                    case 2: v = c.u16(); break;
                    case 4: v = n.type.startsWith("U") || n.type.startsWith("unsigned") ? c.i32() & 0xffffffffL : c.i32(); break;
                    case 8: v = c.i64(); break;
                    default: c.skip(sz); v = 0;
                }
                f.v.put(path, v);
            } else c.skip(sz);
        } else {
            for (TypeNode k : n.kids) walkNode(c, k, path + "." + k.name, lvl + 1, depth, f);
        }
        if (align) c.align4();
    }

    // ================================================================== Texture2D lookup

    static final int CLASS_TEXTURE2D = 28;

    static final class Texture {
        String name; long pathId; String file;
        int format, width, height, mips;
        /** Where the pixel data (all mip levels) sits in the bundle's uncompressed stream. */
        long dataPos; int dataSize;
        boolean streamed;
    }

    /** A Texture2D type tree from this bundle (null if it has none), keyed for reuse by Unity version. */
    TypeNode textureTypeTree(String[] versionOut) throws IOException {
        for (Node n : nodes) {
            if (n.path.endsWith(".resS") || n.path.endsWith(".resource")) continue;
            SerializedFile sf = serializedFile(n);
            if (sf == null) continue;
            for (ObjectInfo o : sf.objects)
                if (o.classId == CLASS_TEXTURE2D && o.tree != null) { if (versionOut != null) versionOut[0] = majorMinor(sf.unityVersion); return o.tree; }
        }
        return null;
    }

    /**
     * Every Texture2D in every SerializedFile of this bundle. Files whose type trees were stripped at build time
     * (typically data.unity3d) use {@code fallback}: Texture2D type trees taken from other bundles built with the
     * same Unity version. Every object must parse to exactly its stored size, so a wrong layout is rejected.
     */
    List<Texture> textures(Map<String, TypeNode> fallback) throws IOException {
        List<Texture> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n.path.endsWith(".resS") || n.path.endsWith(".resource")) continue;
            SerializedFile sf;
            try { sf = serializedFile(n); } catch (IOException e) { throw new IOException(n.path + ": " + e.getMessage(), e); }
            if (sf == null) continue;
            for (ObjectInfo o : sf.objects) {
                if (o.classId != CLASS_TEXTURE2D) continue;
                TypeNode tree = o.tree;
                if (tree == null && fallback != null) tree = fallback.get(majorMinor(sf.unityVersion));
                if (tree == null) throw new IOException("This bundle has no type trees (Unity " + sf.unityVersion +
                        "), so its textures can't be located safely");
                byte[] obj = read(n.offset + o.start, o.size);
                Fields f;
                try { f = walk(obj, sf.little, tree, 2); }
                catch (IOException e) { throw new IOException("Texture2D layout doesn't match (" + e.getMessage() + ")"); }
                if (f.consumed != obj.length)
                    throw new IOException("Texture2D layout doesn't match: parsed " + f.consumed + " of " + obj.length + " bytes");
                Texture t = new Texture();
                t.name = f.text("m_Name"); if (t.name == null) t.name = "texture";
                t.pathId = o.pathId; t.file = n.path;
                t.format = (int) f.num("m_TextureFormat");
                t.width = (int) f.num("m_Width"); t.height = (int) f.num("m_Height");
                long mips = f.num("m_MipCount"); t.mips = mips > 0 ? (int) mips : 1;
                long[] image = f.blob("image data");
                String streamPath = f.text("m_StreamData.path");
                long sOff = f.num("m_StreamData.offset"), sSize = f.num("m_StreamData.size");
                if (image != null && image[1] > 0) {
                    t.dataPos = n.offset + o.start + image[0]; t.dataSize = (int) image[1];
                } else if (streamPath != null && !streamPath.isEmpty() && sSize > 0) {
                    String leaf = streamPath.substring(streamPath.lastIndexOf('/') + 1);
                    Node res = node(leaf);
                    if (res == null) throw new IOException(t.name + ": pixel data is in another file (" + streamPath + ")");
                    if (sOff < 0 || sOff + sSize > res.size) throw new IOException(t.name + ": pixel data outside " + leaf);
                    t.dataPos = res.offset + sOff; t.dataSize = (int) sSize; t.streamed = true;
                }
                out.add(t);
            }
        }
        return out;
    }

    List<Texture> textures() throws IOException { return textures(null); }

    /** "2021.3.58f1" → "2021.3": Texture2D's layout only changes between minor versions. */
    static String majorMinor(String v) {
        if (v == null) return "";
        int a = v.indexOf('.'), b = a < 0 ? -1 : v.indexOf('.', a + 1);
        return b < 0 ? v : v.substring(0, b);
    }
}
