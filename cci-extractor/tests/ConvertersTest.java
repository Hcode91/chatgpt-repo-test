import com.retrotools.cciextractor.*;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** CIA ↔ CCI repackaging and standalone texture conversions. */
public class ConvertersTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static void le32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> (8 * i)); }
    static void le64(byte[] b, int o, long v) { for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> (8 * i)); }

    /** Fake NCCH partition: NCCH magic, size in media units, partition + program IDs, NoCrypto flag. */
    static byte[] ncch(int units, long id, Random r) {
        byte[] b = new byte[units * 0x200];
        r.nextBytes(b);
        System.arraycopy("NCCH".getBytes(), 0, b, 0x100, 4);
        le32(b, 0x104, units);
        le64(b, 0x108, id);
        le64(b, 0x118, id);
        b[0x18E] = 0;
        b[0x18F] = 4;
        le32(b, 0x180, 0x400);
        le32(b, 0x200 + 0x1C0, 0x80000); // exheader save data size
        return b;
    }

    static byte[] cci(List<byte[]> parts, int[] slots, long titleId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = new byte[0x4000];
        System.arraycopy("NCSD".getBytes(), 0, head, 0x100, 4);
        le64(head, 0x108, titleId);
        long cursor = 0x4000;
        for (int i = 0; i < parts.size(); i++) {
            le32(head, 0x120 + slots[i] * 8, cursor / 0x200);
            le32(head, 0x124 + slots[i] * 8, parts.get(i).length / 0x200);
            cursor += parts.get(i).length;
        }
        le32(head, 0x104, cursor / 0x200);
        out.write(head, 0, head.length);
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    static byte[] fakeCertChain() {
        byte[] c = new byte[CiaConverter.CERT_CHAIN_SIZE];
        byte[] names = "Root-CA00000003 XS0000000c CP0000000b".getBytes();
        System.arraycopy(names, 0, c, 0x400, names.length);
        return c;
    }

    static int le32(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24); }

    public static void main(String[] args) throws Exception {
        Random r = new Random(3);
        long tid = 0x0004000000123400L;
        byte[] game = ncch(40, tid, r), manual = ncch(6, tid | 0x10000000000L, r);
        byte[] cciBytes = cci(Arrays.asList(game, manual), new int[]{0, 1}, tid);
        Path cciPath = Files.createTempFile("conv", ".cci"), ciaPath = Files.createTempFile("conv", ".cia"),
                backPath = Files.createTempFile("conv-back", ".cci");
        try {
            Files.write(cciPath, cciBytes);
            check(CiaConverter.looksLikeCertChain(fakeCertChain()), "cert chain heuristic");
            CiaConverter.Result toCia;
            try (FileChannel ch = FileChannel.open(cciPath); java.io.OutputStream o = Files.newOutputStream(ciaPath)) {
                toCia = CiaConverter.cciToCia(ch, o, fakeCertChain(), null, null);
            }
            check(toCia.contents == 2 && toCia.titleId.equals("0004000000123400"), "CCI→CIA result " + toCia.titleId);
            try (FileChannel ch = FileChannel.open(ciaPath)) {
                check(CiaConverter.isCia(ch), "is CIA");
                CiaConverter.CiaLayout l = CiaConverter.parseCia(ch);
                check(l.contents.size() == 2 && l.titleId == tid, "CIA layout");
                check(l.contents.get(0)[3] == game.length && l.contents.get(1)[3] == manual.length, "content sizes");
                check(Arrays.equals(CiaConverter.certChainFromCia(ch), fakeCertChain()), "cert chain stored");
                // Verify the TMD content hash for content 0
                byte[] tmd = new byte[l.tmdSize];
                ch.read(java.nio.ByteBuffer.wrap(tmd), l.tmdOffset);
                byte[] sha = java.security.MessageDigest.getInstance("SHA-256").digest(game);
                check(Arrays.equals(Arrays.copyOfRange(tmd, 0xB04 + 0x10, 0xB04 + 0x30), sha), "TMD content hash");
                check(le32(tmd, 0x19A) == 0x80000, "save size from exheader");
            }
            CiaConverter.Result back;
            try (FileChannel ch = FileChannel.open(ciaPath); java.io.OutputStream o = Files.newOutputStream(backPath)) {
                back = CiaConverter.ciaToCci(ch, o, null, null);
            }
            byte[] rebuilt = Files.readAllBytes(backPath);
            check(back.contents == 2 && back.certChain != null, "CIA→CCI result");
            check(Arrays.equals(Arrays.copyOfRange(rebuilt, 0x4000, 0x4000 + game.length), game), "game partition intact");
            long manualStart = (le32(rebuilt, 0x128) & 0xffffffffL) * 0x200;
            check(Arrays.equals(Arrays.copyOfRange(rebuilt, (int) manualStart, (int) manualStart + manual.length), manual), "manual partition intact");
            check(new String(rebuilt, 0x100, 4).equals("NCSD") && le32(rebuilt, 0x104) * 0x200L == rebuilt.length, "NCSD header/size");
            // Title-key encrypted CIA is refused
            byte[] cia = Files.readAllBytes(ciaPath);
            CiaConverter.CiaLayout l2;
            try (FileChannel ch = FileChannel.open(ciaPath)) {
                l2 = CiaConverter.parseCia(ch);
                check(!CiaConverter.needsDecryption(ch), "decrypted CIA needs no engine");
            }
            // NCCH-level encryption (NoCrypto flag cleared) is routed to the decryption engine
            byte[] ncchEncrypted = cia.clone();
            ncchEncrypted[(int) l2.contents.get(0)[4] + 0x18F] &= ~4;
            Files.write(ciaPath, ncchEncrypted);
            try (FileChannel ch = FileChannel.open(ciaPath)) { check(CiaConverter.needsDecryption(ch), "NCCH-encrypted CIA detected"); }
            cia[(int) l2.tmdOffset + 0xB04 + 7] |= 1;
            Files.write(ciaPath, cia);
            try (FileChannel ch = FileChannel.open(ciaPath)) { check(CiaConverter.needsDecryption(ch), "title-key encrypted CIA detected"); }
            try (FileChannel ch = FileChannel.open(ciaPath)) {
                CiaConverter.ciaToCci(ch, new ByteArrayOutputStream(), null, null);
                throw new AssertionError("encrypted CIA accepted");
            } catch (java.io.IOException expected) { check(expected.getMessage().contains("encrypted"), "encrypted message"); }
            System.out.println("CIA ↔ CCI repackaging passed");
        } finally { Files.deleteIfExists(cciPath); Files.deleteIfExists(ciaPath); Files.deleteIfExists(backPath); }

        // ---------- Textures
        int w = 37, h = 21; // deliberately not multiples of 4
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            px[y * w + x] = (((x * 255 / w) & 255) << 24 | 0) | ((x * 7) & 255) << 16 | ((y * 12) & 255) << 8 | ((x + y) * 5 & 255);
        TextureFormats.Image img = new TextureFormats.Image(w, h, px);

        TextureFormats.Image rgba = TextureFormats.decodeDds(TextureFormats.encodeDds(img, TextureFormats.DdsFormat.RGBA8));
        check(Arrays.equals(rgba.argb, px), "DDS RGBA8 exact roundtrip");
        TextureFormats.Image bc3 = TextureFormats.decodeDds(TextureFormats.encodeDds(img, TextureFormats.DdsFormat.BC3));
        check(bc3.width == w && bc3.height == h && psnr(px, bc3.argb, true) > 28, "BC3 quality " + psnr(px, bc3.argb, true));
        int[] opaque = px.clone(); for (int i = 0; i < opaque.length; i++) opaque[i] |= 0xff000000;
        TextureFormats.Image bc1 = TextureFormats.decodeDds(TextureFormats.encodeDds(new TextureFormats.Image(w, h, opaque), TextureFormats.DdsFormat.BC1));
        check(psnr(opaque, bc1.argb, false) > 28, "BC1 quality " + psnr(opaque, bc1.argb, false));
        check(TextureFormats.detect(TextureFormats.encodeDds(img, TextureFormats.DdsFormat.BC1)) == TextureFormats.Kind.DDS, "detect DDS");

        TextureFormats.Image k1 = TextureFormats.decodeKtx(TextureFormats.encodeKtx(img, TextureFormats.KtxFormat.RGBA8));
        check(Arrays.equals(k1.argb, px), "KTX RGBA8 exact roundtrip");
        TextureFormats.Image k2 = TextureFormats.decodeKtx(TextureFormats.encodeKtx(new TextureFormats.Image(w, h, opaque), TextureFormats.KtxFormat.ETC1));
        check(psnr(opaque, k2.argb, false) > 26, "ETC1 quality " + psnr(opaque, k2.argb, false));
        // Hand-checked ETC1 vectors: individual mode base 8 (=136), table 0, modifier +8 → 144
        long blk = (8L << 60) | (8L << 52) | (8L << 44) | 0x0000FFFFL; // lsb bits set, msb 0, table 0
        int[] out16 = new int[16];
        java.lang.reflect.Method dec = TextureFormats.class.getDeclaredMethod("decodeEtc1Block", long.class, int[].class);
        dec.setAccessible(true);
        dec.invoke(null, blk, out16);
        check((out16[0] & 0xffffff) == 0x909090, "ETC1 individual vector " + Integer.toHexString(out16[0]));
        // Differential: R=16 (132), dR=-1 → 15 (123) in second (right) half, table 0 modifier +2
        long diffBlk = (16L << 59) | (7L << 56) | (1L << 33);
        dec.invoke(null, diffBlk, out16);
        check(((out16[0] >> 16) & 255) == 134 && ((out16[3] >> 16) & 255) == 125, "ETC1 diff vector " +
                ((out16[0] >> 16) & 255) + "," + ((out16[3] >> 16) & 255));

        // NCGR+NCLR builder: ≤15 colours are kept exactly
        int[] few = new int[16 * 16];
        int[] choices = {0xffff0000, 0xff00ff00, 0xff0000ff, 0x00000000, 0xffffffff};
        for (int i = 0; i < few.length; i++) few[i] = choices[(i / 7) % choices.length];
        TextureFormats.NitroPair pair = TextureFormats.toNitro(new TextureFormats.Image(16, 16, few), false);
        NdsGraphics.Image ds = NdsGraphics.decode(pair.ncgr, pair.nclr, 0);
        for (int i = 0; i < few.length; i++) {
            if ((few[i] >>> 24) == 0) continue;
            check((ds.argb[i] & 0xffffff) == (few[i] & 0xffffff), "NCGR colour kept at " + i);
        }
        TextureFormats.NitroPair many = TextureFormats.toNitro(new TextureFormats.Image(w, h, opaque), true);
        NdsGraphics.Image ds8 = NdsGraphics.decode(many.ncgr, many.nclr, 0);
        check(ds8.width == 40 && ds8.height == 24, "NCGR padded to tiles");

        TextureFormats.Image big = TextureFormats.resize(img, TextureFormats.Resize.DOUBLE, false);
        check(big.width == 74 && big.argb[0] == px[0] && big.argb[1] == px[0], "nearest 2× resize");
        TextureFormats.Image pot = TextureFormats.resize(img, TextureFormats.Resize.POWER_OF_TWO, true);
        check(pot.width == 64 && pot.height == 32, "power of two resize");
        System.out.println("DDS / KTX / ETC1 / NCGR / resize conversions passed");
    }

    static double psnr(int[] a, int[] b, boolean alpha) {
        double se = 0; int n = 0;
        for (int i = 0; i < a.length; i++) for (int s = alpha ? 0 : 8; s < 32; s += 8) {
            int d = ((a[i] >>> s) & 255) - ((b[i] >>> s) & 255); se += d * d; n++;
        }
        double mse = se / n;
        return mse == 0 ? 99 : 10 * Math.log10(255.0 * 255 / mse);
    }
}
