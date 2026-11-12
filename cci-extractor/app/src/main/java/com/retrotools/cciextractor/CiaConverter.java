package com.retrotools.cciextractor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Repackages Nintendo 3DS titles between CIA (installable) and CCI/.3ds (cartridge image) containers.
 * Only the container changes: NCCH partitions are copied byte-for-byte. Title-key encrypted CIA contents
 * are rejected; nothing is decrypted or encrypted here. Encrypted CIAs are detected with
 * {@link #needsDecryption} and handed to the native engine ({@code NativeCia}) by the Convert tab instead.
 */
public final class CiaConverter {
    public static final int CERT_CHAIN_SIZE = 0xA00;
    private static final int MEDIA_UNIT = 0x200;
    private static final long FIRST_PARTITION = 0x4000;
    private static final int TMD_CONTENT_INFO = 0x204;      // after RSA-2048 signature block + header
    private static final int TMD_CHUNKS = TMD_CONTENT_INFO + 0x900;

    public interface Progress { void update(long done, long total); }

    public static final class Result {
        public final String titleId;
        public final int contents;
        public final long bytes;
        public final byte[] certChain;
        public final List<String> notes;
        Result(String titleId, int contents, long bytes, byte[] certChain, List<String> notes) {
            this.titleId = titleId; this.contents = contents; this.bytes = bytes;
            this.certChain = certChain; this.notes = notes;
        }
    }

    private CiaConverter() {}

    // ---------------------------------------------------------------- helpers
    private static long align(long v, long a) { return (v + a - 1) / a * a; }
    private static int le32(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24); }
    private static long le64(byte[] b, int o) { return (le32(b, o) & 0xffffffffL) | ((long) le32(b, o + 4) << 32); }
    private static int be16(byte[] b, int o) { return ((b[o] & 255) << 8) | (b[o + 1] & 255); }
    private static int be32(byte[] b, int o) { return ((b[o] & 255) << 24) | ((b[o + 1] & 255) << 16) | ((b[o + 2] & 255) << 8) | (b[o + 3] & 255); }
    private static long be64(byte[] b, int o) { return ((long) be32(b, o) << 32) | (be32(b, o + 4) & 0xffffffffL); }
    private static void putLe32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> (8 * i)); }
    private static void putLe64(byte[] b, int o, long v) { for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> (8 * i)); }
    private static void putBe16(byte[] b, int o, int v) { b[o] = (byte) (v >>> 8); b[o + 1] = (byte) v; }
    private static void putBe32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> (24 - 8 * i)); }
    private static void putBe64(byte[] b, int o, long v) { for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> (56 - 8 * i)); }
    private static boolean magic(byte[] b, int o, String m) {
        for (int i = 0; i < m.length(); i++) if (b[o + i] != (byte) m.charAt(i)) return false;
        return true;
    }
    static String hex(long v) { return String.format(Locale.ROOT, "%016X", v); }

    private static byte[] read(FileChannel ch, long pos, int len) throws IOException {
        if (pos < 0 || len < 0 || pos + len > ch.size()) throw new IOException("File is truncated at 0x" + Long.toHexString(pos));
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) if (ch.read(b, pos + b.position()) <= 0) throw new IOException("Unexpected end of file");
        return b.array();
    }

    private static void copy(FileChannel ch, long pos, long len, OutputStream out, MessageDigest digest,
                             long[] done, long total, Progress p, AtomicBoolean cancel) throws IOException {
        byte[] buf = new byte[1 << 20];
        while (len > 0) {
            if (cancel != null && cancel.get()) throw new IOException("Cancelled");
            int n = (int) Math.min(buf.length, len);
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
            while (bb.hasRemaining()) if (ch.read(bb, pos + bb.position()) <= 0) throw new IOException("Unexpected end of file");
            if (out != null) out.write(buf, 0, n);
            if (digest != null) digest.update(buf, 0, n);
            pos += n; len -= n; done[0] += n;
            if (p != null) p.update(done[0], total);
        }
    }
    private static void zeros(OutputStream out, long n) throws IOException {
        byte[] z = new byte[(int) Math.min(n, 65536)];
        while (n > 0) { int k = (int) Math.min(z.length, n); out.write(z, 0, k); n -= k; }
    }

    // ---------------------------------------------------------------- CIA parsing
    public static final class CiaLayout {
        public long certOffset, ticketOffset, tmdOffset, contentOffset, metaOffset;
        public int certSize, ticketSize, tmdSize, metaSize;
        public long contentSize;
        public long titleId;
        public int tmdHeader; // offset of TMD header inside the TMD
        public final List<long[]> contents = new ArrayList<>(); // {id, index, type, size, fileOffset}
    }

    private static int signatureBlock(int type) throws IOException {
        switch (type) {
            case 0x10000: case 0x10003: return 0x200 + 0x3C + 4;
            case 0x10001: case 0x10004: return 0x100 + 0x3C + 4;
            case 0x10002: case 0x10005: return 0x3C + 0x40 + 4;
            default: throw new IOException("Unknown signature type 0x" + Integer.toHexString(type));
        }
    }

    public static boolean isCia(FileChannel ch) throws IOException {
        if (ch.size() < 0x2040) return false;
        byte[] h = read(ch, 0, 0x20);
        return le32(h, 0) == 0x2020 && le32(h, 0x8) > 0 && le32(h, 0x10) > 0;
    }

    public static CiaLayout parseCia(FileChannel ch) throws IOException {
        byte[] h = read(ch, 0, 0x2020);
        if (le32(h, 0) != 0x2020) throw new IOException("Not a CIA file (header size isn't 0x2020)");
        CiaLayout l = new CiaLayout();
        l.certSize = le32(h, 0x8); l.ticketSize = le32(h, 0xC); l.tmdSize = le32(h, 0x10); l.metaSize = le32(h, 0x14);
        l.contentSize = le64(h, 0x18);
        if (l.certSize < 0 || l.ticketSize < 0 || l.tmdSize <= 0 || l.tmdSize > 0x100000 || l.contentSize < 0)
            throw new IOException("CIA header sizes are invalid");
        l.certOffset = align(0x2020, 64);
        l.ticketOffset = align(l.certOffset + l.certSize, 64);
        l.tmdOffset = align(l.ticketOffset + l.ticketSize, 64);
        l.contentOffset = align(l.tmdOffset + l.tmdSize, 64);
        l.metaOffset = align(l.contentOffset + l.contentSize, 64);
        if (l.contentOffset + l.contentSize > ch.size()) throw new IOException("CIA is truncated (content runs past end of file)");
        byte[] tmd = read(ch, l.tmdOffset, l.tmdSize);
        l.tmdHeader = signatureBlock(be32(tmd, 0));
        if (l.tmdHeader + 0xC4 > tmd.length) throw new IOException("TMD is truncated");
        l.titleId = be64(tmd, l.tmdHeader + 0x4C);
        int count = be16(tmd, l.tmdHeader + 0x9E);
        int chunks = l.tmdHeader + 0xC4 + 0x900;
        if (count <= 0 || count > 64 || chunks + count * 0x30 > tmd.length) throw new IOException("TMD content list is invalid");
        long pos = l.contentOffset;
        for (int i = 0; i < count; i++) {
            int at = chunks + i * 0x30;
            int index = be16(tmd, at + 4);
            // Only contents flagged in the CIA's content index bitmap are actually present.
            if ((h[0x20 + index / 8] & (0x80 >>> (index % 8))) == 0) continue;
            long size = be64(tmd, at + 8);
            l.contents.add(new long[]{be32(tmd, at) & 0xffffffffL, index, be16(tmd, at + 6), size, pos});
            pos += align(size, 64);
        }
        if (l.contents.isEmpty()) throw new IOException("CIA contains no contents");
        return l;
    }

    /**
     * True when a CIA can't simply be repackaged: a content is title-key encrypted, or an NCCH partition
     * isn't flagged NoCrypto. Such files go through the native decryption engine instead of {@link #ciaToCci}.
     */
    public static boolean needsDecryption(FileChannel ch) throws IOException {
        CiaLayout l = parseCia(ch);
        for (long[] c : l.contents) {
            if ((c[2] & 1) != 0) return true;
            if (c[3] < 0x200) continue;
            byte[] ncch = read(ch, c[4], 0x200);
            if (magic(ncch, 0x100, "NCCH") && (ncch[0x18F] & 4) == 0) return true;
        }
        return false;
    }

    /** CIA → CCI. Content index 0 becomes partition 0 (game), 1 → manual, 2 → download play child. */
    public static Result ciaToCci(FileChannel ch, OutputStream out, Progress p, AtomicBoolean cancel) throws IOException {
        CiaLayout l = parseCia(ch);
        List<String> notes = new ArrayList<>();
        long[][] parts = new long[8][]; // {fileOffset, size, partitionId}
        for (long[] c : l.contents) {
            int index = (int) c[1];
            if ((c[2] & 1) != 0)
                throw new IOException("This CIA is title-key encrypted (content " + index + "). Only decrypted CIAs " +
                        "can be converted: build a decrypted CIA or CCI on your own console with GodMode9.");
            if (index > 7) { notes.add("Skipped content index " + index + " (no matching CCI partition)"); continue; }
            byte[] ncch = read(ch, c[4], 0x200);
            if (!magic(ncch, 0x100, "NCCH")) throw new IOException("Content " + index + " isn't an NCCH partition");
            if ((ncch[0x18F] & 4) == 0) notes.add("Partition " + index + " keeps its NCCH-level encryption (copied unchanged)");
            parts[index] = new long[]{c[4], c[3], le64(ncch, 0x108)};
        }
        if (parts[0] == null) throw new IOException("CIA has no main application content (index 0)");

        byte[] head = new byte[(int) FIRST_PARTITION];
        System.arraycopy("NCSD".getBytes(StandardCharsets.US_ASCII), 0, head, 0x100, 4);
        putLe64(head, 0x108, l.titleId);
        long cursor = FIRST_PARTITION, total = 0;
        for (int i = 0; i < 8; i++) {
            if (parts[i] == null) continue;
            putLe32(head, 0x120 + i * 8, cursor / MEDIA_UNIT);
            putLe32(head, 0x124 + i * 8, align(parts[i][1], MEDIA_UNIT) / MEDIA_UNIT);
            putLe64(head, 0x190 + i * 8, parts[i][2]);
            cursor += align(parts[i][1], MEDIA_UNIT);
            total += parts[i][1];
        }
        putLe32(head, 0x104, cursor / MEDIA_UNIT);
        head[0x188 + 5] = 1; // platform: CTR
        head[0x188 + 6] = 1; // media type: Card1
        head[0x188 + 7] = 0; // media unit 0x200
        putLe32(head, 0x200, 0xFFFFFFFFL); // writable (save) address: none, card1 uses separate save chip
        // Card info: copy of the main NCCH header, used by some tools as the "initial data" mirror.
        byte[] main = read(ch, parts[0][0], 0x200);
        System.arraycopy(main, 0x100, head, 0x1100, 0x100);
        out.write(head);
        long[] done = {0};
        for (int i = 0; i < 8; i++) {
            if (parts[i] == null) continue;
            copy(ch, parts[i][0], parts[i][1], out, null, done, total, p, cancel);
            zeros(out, align(parts[i][1], MEDIA_UNIT) - parts[i][1]);
        }
        byte[] cert = l.certSize == CERT_CHAIN_SIZE ? read(ch, l.certOffset, l.certSize) : null;
        int n = 0; for (long[] x : parts) if (x != null) n++;
        return new Result(hex(l.titleId), n, cursor, cert, notes);
    }

    // ---------------------------------------------------------------- CCI → CIA
    private static final String TICKET_ISSUER = "Root-CA00000003-XS0000000c";
    private static final String TMD_ISSUER = "Root-CA00000003-CP0000000b";

    /** Standard "all contents" rights block found at the end of retail-style tickets. */
    private static final byte[] TICKET_CONTENT_INDEX = {
            0x00, 0x01, 0x00, 0x14, 0x00, 0x00, 0x00, (byte) 0xAC, 0x00, 0x00, 0x00, 0x14, 0x00, 0x01, 0x00, 0x14,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x28, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, (byte) 0x84,
            0x00, 0x00, 0x00, (byte) 0x84, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    static byte[] buildTicket(long titleId) {
        byte[] t = new byte[0x350];
        putBe32(t, 0, 0x10004);
        byte[] issuer = TICKET_ISSUER.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(issuer, 0, t, 0x140, issuer.length);
        t[0x1BC] = 1;                    // format version
        putBe64(t, 0x1DC, titleId);
        t[0x1F0] = 0;                    // license type
        t[0x1F1] = 0;                    // common key index (unused: contents aren't encrypted)
        System.arraycopy(TICKET_CONTENT_INDEX, 0, t, 0x2A4, TICKET_CONTENT_INDEX.length);
        for (int i = 0x2A4 + TICKET_CONTENT_INDEX.length; i < 0x2A4 + 0xAC; i++) t[i] = (byte) 0xFF;
        return t;
    }

    static byte[] buildTmd(long titleId, int saveSize, int titleVersion, List<long[]> contents, List<byte[]> hashes)
            throws IOException {
        int count = contents.size();
        byte[] t = new byte[TMD_CHUNKS + 0x30 * count];
        putBe32(t, 0, 0x10004);
        byte[] issuer = TMD_ISSUER.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(issuer, 0, t, 0x140, issuer.length);
        t[0x180] = 1;
        putBe64(t, 0x18C, titleId);
        putBe32(t, 0x194, 0x40);
        putLe32(t, 0x19A, saveSize & 0xffffffffL);
        putBe16(t, 0x1DC, titleVersion);
        putBe16(t, 0x1DE, count);
        putBe16(t, 0x1E0, 0);
        for (int i = 0; i < count; i++) {
            long[] c = contents.get(i); // {index, size}
            int at = TMD_CHUNKS + i * 0x30;
            putBe32(t, at, c[0]);           // content id = index
            putBe16(t, at + 4, (int) c[0]);
            putBe16(t, at + 6, c[0] == 0 ? 0 : 0x4000); // optional flag for manual / DLP
            putBe64(t, at + 8, c[1]);
            System.arraycopy(hashes.get(i), 0, t, at + 0x10, 0x20);
        }
        MessageDigest sha = sha256();
        putBe16(t, TMD_CONTENT_INFO, 0);
        putBe16(t, TMD_CONTENT_INFO + 2, count);
        sha.update(t, TMD_CHUNKS, 0x30 * count);
        System.arraycopy(sha.digest(), 0, t, TMD_CONTENT_INFO + 4, 0x20);
        sha.reset();
        sha.update(t, TMD_CONTENT_INFO, 0x900);
        System.arraycopy(sha.digest(), 0, t, 0x1E4, 0x20);
        return t;
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable"); }
    }

    /**
     * CCI → CIA (unsigned, unencrypted contents: install with signature patches, e.g. Luma3DS + FBI).
     * Partitions 0–2 become contents 0–2; update partitions are dropped. Needs the 3DS certificate chain,
     * which the app keeps from any CIA you've converted (it isn't bundled).
     */
    public static Result cciToCia(FileChannel ch, OutputStream out, byte[] certChain, Progress p, AtomicBoolean cancel)
            throws IOException {
        if (certChain == null || certChain.length != CERT_CHAIN_SIZE)
            throw new IOException("A 3DS certificate chain is needed first. Use \"Save cert chain from a CIA\" " +
                    "with any CIA you own (encrypted ones work for this), or convert a decrypted CIA to CCI once.");
        byte[] ncsd = read(ch, 0, 0x200);
        if (!magic(ncsd, 0x100, "NCSD")) throw new IOException("Not a 3DS CCI/.3ds image (no NCSD header)");
        int shift = ncsd[0x18E] & 255;
        if (shift > 10) throw new IOException("Invalid NCSD media unit");
        long unit = (long) MEDIA_UNIT << shift;
        List<String> notes = new ArrayList<>();
        List<long[]> contents = new ArrayList<>();   // {index, size}
        List<Long> offsets = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long start = (le32(ncsd, 0x120 + i * 8) & 0xffffffffL) * unit;
            long size = (le32(ncsd, 0x124 + i * 8) & 0xffffffffL) * unit;
            if (size == 0) continue;
            if (i > 2) { notes.add("Dropped partition " + i + " (system update data isn't part of a CIA)"); continue; }
            if (start + size > ch.size()) throw new IOException("Partition " + i + " runs past the end of the image");
            byte[] ncch = read(ch, start, 0x200);
            if (!magic(ncch, 0x100, "NCCH")) throw new IOException("Partition " + i + " isn't NCCH");
            // Use the NCCH's own size rather than the NCSD slot (which may include padding).
            long ncchSize = (le32(ncch, 0x104) & 0xffffffffL) * ((long) MEDIA_UNIT << (ncch[0x18E] & 255));
            if (ncchSize > 0 && ncchSize <= size) size = ncchSize;
            contents.add(new long[]{i, size});
            offsets.add(start);
        }
        if (contents.isEmpty() || contents.get(0)[0] != 0) throw new IOException("CCI has no main application partition");
        byte[] main = read(ch, offsets.get(0), 0x200);
        long titleId = le64(main, 0x118);
        int saveSize = 0;
        boolean noCrypto = (main[0x18F] & 4) != 0;
        if (le32(main, 0x180) >= 0x400 && noCrypto) {
            byte[] exheader = read(ch, offsets.get(0) + 0x200, 0x400);
            saveSize = le32(exheader, 0x1C0);
        } else if (!noCrypto) notes.add("NCCH is encrypted, so the save-data size couldn't be read (set to 0)");

        long total = 0;
        for (long[] c : contents) total += c[1];
        // Pass 1: hash every content (TMD comes before the contents in a CIA).
        List<byte[]> hashes = new ArrayList<>();
        long[] done = {0};
        MessageDigest sha = sha256();
        for (int i = 0; i < contents.size(); i++) {
            sha.reset();
            copy(ch, offsets.get(i), contents.get(i)[1], null, sha, done, total * 2, p, cancel);
            hashes.add(sha.digest());
        }
        byte[] ticket = buildTicket(titleId);
        byte[] tmd = buildTmd(titleId, saveSize, 0, contents, hashes);
        long contentSize = 0;
        for (long[] c : contents) contentSize = align(contentSize, 64) + c[1];

        byte[] header = new byte[0x2020];
        putLe32(header, 0, 0x2020);
        putLe32(header, 0x8, certChain.length);
        putLe32(header, 0xC, ticket.length);
        putLe32(header, 0x10, tmd.length);
        putLe32(header, 0x14, 0);
        putLe64(header, 0x18, contentSize);
        for (long[] c : contents) header[0x20 + (int) c[0] / 8] |= (byte) (0x80 >>> ((int) c[0] % 8));

        long written = 0;
        out.write(header); written += header.length;
        zeros(out, align(written, 64) - written); written = align(written, 64);
        out.write(certChain); written += certChain.length;
        zeros(out, align(written, 64) - written); written = align(written, 64);
        out.write(ticket); written += ticket.length;
        zeros(out, align(written, 64) - written); written = align(written, 64);
        out.write(tmd); written += tmd.length;
        zeros(out, align(written, 64) - written); written = align(written, 64);
        // Pass 2: contents.
        for (int i = 0; i < contents.size(); i++) {
            if (i > 0) { long pad = align(written, 64) - written; zeros(out, pad); written += pad; }
            copy(ch, offsets.get(i), contents.get(i)[1], out, null, done, total * 2, p, cancel);
            written += contents.get(i)[1];
        }
        notes.add("Unsigned CIA: install with signature patches enabled (Luma3DS) using FBI or similar.");
        return new Result(hex(titleId), contents.size(), written, null, notes);
    }

    /** Read just the certificate chain from any CIA (encrypted or not) so CCI → CIA can use it. */
    public static byte[] certChainFromCia(FileChannel ch) throws IOException {
        byte[] h = read(ch, 0, 0x20);
        if (le32(h, 0) != 0x2020) throw new IOException("Not a CIA file");
        int size = le32(h, 0x8);
        if (size != CERT_CHAIN_SIZE) throw new IOException("Unexpected certificate chain size " + size);
        return read(ch, align(0x2020, 64), size);
    }

    /** Sanity-check a certificate chain blob: three certs with the expected issuer names. */
    public static boolean looksLikeCertChain(byte[] c) {
        if (c == null || c.length != CERT_CHAIN_SIZE) return false;
        String s = new String(c, StandardCharsets.ISO_8859_1);
        return s.contains("CA00000003") && s.contains("XS0000000c") && s.contains("CP0000000b");
    }
}
