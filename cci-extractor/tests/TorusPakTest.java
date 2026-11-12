import com.retrotools.cciextractor.TorusPak;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.Deflater;

/** Builds a tiny synthetic Torus PAK (no game data) and checks the container, hunk and texture decoding. */
public class TorusPakTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static byte[] le(int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) b.putInt(v);
        return b.array();
    }
    static byte[] record(int type, int flags, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(body.length).putShort((short) type).putShort((short) flags).put(body);
        return b.array();
    }
    static byte[] assetStart(String type, String name) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[10]);
        o.write(type.getBytes(StandardCharsets.ISO_8859_1)); o.write(0);
        o.write(name.getBytes(StandardCharsets.ISO_8859_1)); o.write(0);
        return record(0x71, 4, o.toByteArray());
    }
    static byte[] zls(byte[] raw) throws Exception {
        Deflater d = new Deflater();
        d.setInput(raw); d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{'!', 'Z', 'L', 'S'}); o.write(le(raw.length));
        byte[] buf = new byte[4096];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        d.end();
        return o.toByteArray();
    }
    /** 3DS-layout ETC1 block, individual mode: both halves one colour (4 bits per channel), zero modifiers table 0. */
    static byte[] solidBlock(int r4, int g4, int b4) {
        long hi = ((long) r4 << 28) | ((long) r4 << 24) | ((long) g4 << 20) | ((long) g4 << 16) | ((long) b4 << 12) | ((long) b4 << 8);
        long block = hi << 32;                        // pixel bits 0 → modifier +2 from table 0
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(block).array();
    }
    static byte[] textureHeader(int format, int dataSize, int w, int h) {
        ByteBuffer b = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(format).putInt(dataSize).putInt(0).putShort((short) w).putShort((short) h);
        return b.array();
    }

    public static void main(String[] args) throws Exception {
        // 8x8 ETC1 texture (4 blocks) and 8x8 ETC1A4 texture (4 × alpha+colour)
        ByteArrayOutputStream etc1 = new ByteArrayOutputStream(), etc1a4 = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) etc1.write(solidBlock(15, 0, 0));
        for (int i = 0; i < 4; i++) {
            byte[] alpha = new byte[8];
            java.util.Arrays.fill(alpha, (byte) (i == 0 ? 0x00 : 0xff));
            etc1a4.write(alpha); etc1a4.write(solidBlock(0, 0, 15));
        }
        ByteArrayOutputStream hunk = new ByteArrayOutputStream();
        hunk.write(record(0x70, 4, new byte[592]));
        hunk.write(assetStart("TSETexture", "Red"));
        hunk.write(record(0x1150, 4, textureHeader(0x68cd6f34, 32, 8, 8)));
        hunk.write(record(0x1151, 0x80, etc1.toByteArray()));
        hunk.write(record(0x72, 4, new byte[0]));
        hunk.write(assetStart("TSETexture", "BlueAlpha"));
        hunk.write(record(0x1150, 4, textureHeader(0x8bfb7121, 64, 8, 8)));
        hunk.write(record(0x1151, 0x80, etc1a4.toByteArray()));
        hunk.write(record(0x72, 4, new byte[0]));
        hunk.write(assetStart("StringTable", "Common"));
        hunk.write(record(0x100f, 4, new byte[]{1, 2, 3}));
        hunk.write(record(0x0002, 4, new byte[]{4, 5}));
        hunk.write(record(0x72, 4, new byte[0]));

        byte[] loose = zls("maxassetcount 0\r\n".getBytes(StandardCharsets.ISO_8859_1));
        byte[] manifest = zls("maxassetcount 3\r\nhunkfile \"Global\"\r\n".getBytes(StandardCharsets.ISO_8859_1));
        byte[] packed = zls(hunk.toByteArray());
        int tableEnd = 8 + 3 * 12;
        int o1 = tableEnd, o2 = o1 + loose.length, o3 = o2 + manifest.length;
        ByteArrayOutputStream pak = new ByteArrayOutputStream();
        pak.write(new byte[]{'P', 'A', 'K', 0}); pak.write(le(3));
        // table is sorted by hash, not by offset
        pak.write(le(0x11111111, o3, packed.length, 0x22222222, o1, loose.length, 0x33333333, o2, manifest.length));
        pak.write(loose); pak.write(manifest); pak.write(packed);

        Path file = Files.createTempFile("toruspak", ".dat");
        try {
            Files.write(file, pak.toByteArray());
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                check(TorusPak.isPak(ch), "PAK detected");
                TorusPak.Info info = TorusPak.parse(ch);
                check(info.entryCount == 3 && info.hunks.size() == 1, "one hunk found");
                check(info.looseManifests.size() == 1, "manifest without a hunk kept separately");
                TorusPak.Hunk h = info.hunks.get(0);
                check(h.name.equals("Global") && h.manifest != null && h.manifest.contains("hunkfile"), "hunk named from its manifest");
                byte[] raw = TorusPak.read(ch, h.data);
                check(java.util.Arrays.equals(raw, hunk.toByteArray()), "hunk decompressed intact");
                List<TorusPak.Asset> assets = TorusPak.assets(raw);
                check(assets.size() == 3, "three assets");
                check(assets.get(0).type.equals("TSETexture") && assets.get(0).name.equals("Red"), "asset names");
                TorusPak.Texture red = TorusPak.texture(raw, assets.get(0));
                check(red != null && red.width == 8 && red.height == 8 && !red.alpha, "ETC1 texture size");
                for (int p : red.argb) check(p == 0xFFFF0202, "ETC1 pixel " + Integer.toHexString(p));
                TorusPak.Texture blue = TorusPak.texture(raw, assets.get(1));
                check(blue != null && blue.alpha, "ETC1A4 texture");
                check(blue.argb[0] == 0x000202FF, "transparent top-left block " + Integer.toHexString(blue.argb[0]));
                check(blue.argb[4] == 0xFF0202FF && blue.argb[63] == 0xFF0202FF, "opaque elsewhere");
                TorusPak.Asset strings = assets.get(2);
                check(strings.type.equals("StringTable") && strings.records.size() == 2, "raw asset records");
                check(TorusPak.texture(raw, strings) == null, "non-texture is not decoded");
                check(strings.end - strings.start == 8 + 3 + 8 + 2, "raw asset span covers its records");
            }
            // Corrupt and foreign files are rejected cleanly
            byte[] bad = pak.toByteArray();
            bad[o3 + 12] ^= 0x55;
            Files.write(file, bad);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                boolean threw = false;
                try {
                    TorusPak.Info info = TorusPak.parse(ch);
                    TorusPak.assets(TorusPak.read(ch, info.hunks.get(0).data));
                } catch (java.io.IOException expected) { threw = true; }
                check(threw, "corrupt hunk reports an error");
            }
            Files.write(file, "NCSD not a pak".getBytes(StandardCharsets.ISO_8859_1));
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) { check(!TorusPak.isPak(ch), "other files are not PAKs"); }
        } finally { Files.deleteIfExists(file); }
        check(TorusPak.safeName("a/b:c").equals("a_b_c") && TorusPak.safeName("..").equals("unnamed"), "safe names");
        System.out.println("Torus PAK container / hunk / ETC1 texture tests passed");
    }
}
