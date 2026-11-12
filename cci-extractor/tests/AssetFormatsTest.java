import com.retrotools.cciextractor.AssetExpander;
import com.retrotools.cciextractor.CtrArchives;
import com.retrotools.cciextractor.CtrTextures;
import com.retrotools.cciextractor.GameAudio;
import com.retrotools.cciextractor.NdsMedia;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic-data tests for the 3DS and DS format readers. Every fixture is built here from the documented
 * layouts, so these check the readers against the specifications, not against files from real games.
 */
public class AssetFormatsTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    /** Little-endian byte builder. */
    static final class B {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        B u8(int v) { o.write(v); return this; }
        B u16(int v) { o.write(v); o.write(v >> 8); return this; }
        B u32(long v) { u16((int) v); u16((int) (v >> 16)); return this; }
        B str(String s) { byte[] b = s.getBytes(StandardCharsets.ISO_8859_1); o.write(b, 0, b.length); return this; }
        B raw(byte[] b) { o.write(b, 0, b.length); return this; }
        B zero(int n) { for (int i = 0; i < n; i++) o.write(0); return this; }
        B padTo(int size) { while (o.size() < size) o.write(0); return this; }
        int size() { return o.size(); }
        byte[] done() { return o.toByteArray(); }
    }
    static void poke32(byte[] d, int at, long v) { for (int i = 0; i < 4; i++) d[at + i] = (byte) (v >> (8 * i)); }

    static int morton(int x, int y, int width) {
        int m = (x & 1) | ((y & 1) << 1) | ((x & 2) << 1) | ((y & 2) << 2) | ((x & 4) << 2) | ((y & 4) << 3);
        return ((y >> 3) * (width >> 3) + (x >> 3)) * 64 + m;
    }
    /** RGBA8 PICA data for a stored image: every pixel gets colour(x, y). */
    interface Paint { int at(int x, int y); }
    static byte[] rgba8(int w, int h, Paint p) {
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = p.at(x, y), i = morton(x, y, w) * 4;
            out[i] = (byte) (c >>> 24); out[i + 1] = (byte) c; out[i + 2] = (byte) (c >> 8); out[i + 3] = (byte) (c >> 16);
        }
        return out;
    }
    static int colour(int x, int y) { return 0xff000000 | (x * 7 & 255) << 16 | (y * 11 & 255) << 8 | ((x + y) & 255); }

    // ---------------------------------------------------------------- archives

    static byte[] sarc(Map<String, byte[]> files, boolean named) {
        B names = new B();
        List<int[]> nodes = new ArrayList<>();
        B data = new B();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            int nameAt = names.size();
            names.str(f.getKey()).u8(0); while (names.size() % 4 != 0) names.u8(0);
            int start = data.size();
            data.raw(f.getValue());
            nodes.add(new int[]{f.getKey().hashCode(), named ? 0x01000000 | nameAt / 4 : 0, start, data.size()});
            while (data.size() % 4 != 0) data.u8(0);
        }
        int dataOffset = 0x14 + 0x0C + nodes.size() * 16 + 8 + names.size();
        B b = new B().str("SARC").u16(0x14).u16(0xFEFF).u32(dataOffset + data.size()).u32(dataOffset).u32(0x100);
        b.str("SFAT").u16(0x0C).u16(nodes.size()).u32(0x65);
        for (int[] n : nodes) b.u32(n[0] & 0xffffffffL).u32(n[1]).u32(n[2]).u32(n[3]);
        b.str("SFNT").u16(8).u16(0).raw(names.done()).raw(data.done());
        return b.done();
    }

    static byte[] utf16(String s) {
        byte[] b = new byte[s.length() * 2 + 2];
        for (int i = 0; i < s.length(); i++) { b[i * 2] = (byte) s.charAt(i); b[i * 2 + 1] = (byte) (s.charAt(i) >> 8); }
        return b;
    }
    /** darc with: root "", folder ".", folder "img" { a.bin }, b.bin */
    static byte[] darc(byte[] a, byte[] bFile) {
        String[] nameList = {"", ".", "img", "a.bin", "b.bin"};
        int[] nameOffsets = new int[5];
        B names = new B();
        for (int i = 0; i < 5; i++) { nameOffsets[i] = names.size(); names.raw(utf16(nameList[i])); }
        int table = 0x1C, tableLen = 5 * 12 + names.size();
        int dataOffset = (table + tableLen + 3) & ~3;
        int aOff = dataOffset, bOff = aOff + ((a.length + 3) & ~3);
        B b = new B().str("darc").u16(0xFEFF).u16(0x1C).u32(0x01000000).u32(bOff + bFile.length).u32(table).u32(tableLen).u32(dataOffset);
        b.u32(0x01000000L | nameOffsets[0]).u32(0).u32(5);
        b.u32(0x01000000L | nameOffsets[1]).u32(0).u32(5);
        b.u32(0x01000000L | nameOffsets[2]).u32(1).u32(4);
        b.u32(nameOffsets[3]).u32(aOff).u32(a.length);
        b.u32(nameOffsets[4]).u32(bOff).u32(bFile.length);
        b.raw(names.done()).padTo(aOff).raw(a).padTo(bOff).raw(bFile);
        return b.done();
    }

    static byte[] zar(String name, byte[] file) {
        int info = 0x20 + 16 + 4 + 4, nameAt = info + 8, offsets = nameAt + ((name.length() + 4) & ~3), data = offsets + 4;
        B b = new B().str("ZAR").u8(1).u32(data + file.length).u16(1).u16(1).u32(0x20).u32(info).u32(offsets).str("queen\0\0\0");
        b.u32(1).u32(0x30).u32(0x34).u32(0xFFFFFFFFL).u32(0).str("bin\0");
        b.u32(file.length).u32(nameAt).str(name).u8(0).padTo(offsets).u32(data).raw(file);
        return b.done();
    }

    static byte[] garc(byte[] first, byte[] second) {
        int fato = 0x1C, fatoSize = 12 + 2 * 4, fatb = fato + fatoSize, fatbSize = 12 + 2 * 16, fimb = fatb + fatbSize, data = fimb + 12;
        int secondStart = (first.length + 3) & ~3;
        B b = new B().str("CRAG").u32(0x1C).u16(0xFEFF).u16(0x0400).u32(4).u32(data).u32(data + secondStart + second.length).u32(Math.max(first.length, second.length));
        b.str("OTAF").u32(fatoSize).u16(2).u16(0xFFFF).u32(0).u32(16);
        b.str("BTAF").u32(fatbSize).u32(2).u32(1).u32(0).u32(first.length).u32(first.length).u32(1).u32(secondStart).u32(secondStart + second.length).u32(second.length);
        b.str("BMIF").u32(12).u32(secondStart + second.length).raw(first).padTo(data + secondStart).raw(second);
        return b.done();
    }

    static byte[] yaz0Literal(byte[] raw) {
        B b = new B().str("Yaz0").u8(raw.length >> 24).u8(raw.length >> 16).u8(raw.length >> 8).u8(raw.length).zero(8);
        for (int i = 0; i < raw.length; i += 8) { b.u8(0xFF); for (int j = i; j < Math.min(raw.length, i + 8); j++) b.u8(raw[j]); }
        return b.done();
    }

    // ---------------------------------------------------------------- textures

    static byte[] limFooter(boolean flim, int w, int h, int format, int swizzle, int dataSize) {
        B b = new B().str(flim ? "FLIM" : "CLIM").u16(0xFEFF).u16(0x14).u32(0x02020000).u32(dataSize + 0x28).u16(1).u16(0);
        b.str("imag").u32(0x10).u16(w).u16(h);
        if (flim) b.u16(0x80).u8(format).u8(swizzle); else b.u8(format).u8(swizzle).u16(0x80);
        return b.u32(dataSize).done();
    }

    // ---------------------------------------------------------------- audio

    static short[] pcmOfWav(byte[] wav, int channel) {
        int channels = wav[22], n = (wav.length - 44) / 2 / channels;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) { int p = 44 + (i * channels + channel) * 2; out[i] = (short) ((wav[p] & 255) | wav[p + 1] << 8); }
        return out;
    }
    static int wavRate(byte[] wav) { return ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(); }

    static byte[] bcwav(int encoding, int rate, int samples, byte[] sampleData, boolean dspInfo) {
        // INFO at 0x40: block header, encoding…, table (count 1) at +0x1C, channel info at +0x24, DSP info at +0x38
        B info = new B().str("INFO").u32(0).u8(encoding).u8(0).u16(0).u32(rate).u32(0).u32(samples).u32(0);
        info.u32(1).u16(0x7100).u16(0).u32(0x0C);                 // table @0x1C, channel info at table+0x0C
        info.u16(0x1F00).u16(0).u32(0).u16(dspInfo ? 0x0300 : 0).u16(0).u32(dspInfo ? 0x14 : 0xFFFFFFFFL).u32(0);
        if (dspInfo) { for (int i = 0; i < 16; i++) info.u16(0); info.zero(0x0E); }
        while (info.size() % 0x20 != 0) info.u8(0);
        byte[] infoBytes = info.done();
        poke32(infoBytes, 4, infoBytes.length);
        int dataAt = 0x40 + infoBytes.length;
        B b = new B().str("CWAV").u16(0xFEFF).u16(0x40).u32(0x02010000).u32(dataAt + 8 + sampleData.length).u16(2).u16(0);
        b.u16(0x7000).u16(0).u32(0x40).u32(infoBytes.length).u16(0x7001).u16(0).u32(dataAt).u32(8 + sampleData.length).padTo(0x40);
        return b.raw(infoBytes).str("DATA").u32(8 + sampleData.length).raw(sampleData).done();
    }

    static byte[] bcstmPcm16(int rate, short[][] channels, int blockSamples) {
        int ch = channels.length, n = channels[0].length;
        int blocks = (n + blockSamples - 1) / blockSamples, lastSamples = n - (blocks - 1) * blockSamples;
        B info = new B().str("INFO").u32(0);
        info.u16(0x4100).u16(0).u32(0x18).u16(0).u16(0).u32(0xFFFFFFFFL).u16(0x0101).u16(0).u32(0x18 + 0x38);
        info.u8(1).u8(0).u8(ch).u8(0).u32(rate).u32(0).u32(n).u32(blocks).u32(blockSamples * 2).u32(blockSamples)
                .u32(lastSamples * 2).u32(lastSamples).u32(lastSamples * 2).u32(4).u32(blockSamples).u16(0x1F00).u16(0).u32(0x18);
        info.u32(ch);
        for (int c = 0; c < ch; c++) info.u16(0x4102).u16(0).u32(4 + ch * 8 + c * 8);
        for (int c = 0; c < ch; c++) info.u16(0).u16(0).u32(0xFFFFFFFFL);
        while (info.size() % 0x20 != 0) info.u8(0);
        byte[] infoBytes = info.done();
        poke32(infoBytes, 4, infoBytes.length);
        B data = new B().str("DATA").u32(0).zero(0x18);
        for (int blk = 0; blk < blocks; blk++) for (int c = 0; c < ch; c++)
            for (int i = blk * blockSamples; i < Math.min(n, (blk + 1) * blockSamples); i++) data.u16(channels[c][i]);
        byte[] dataBytes = data.done();
        poke32(dataBytes, 4, dataBytes.length);
        int seekAt = 0x40 + infoBytes.length, dataAt = seekAt + 0x20;
        B b = new B().str("CSTM").u16(0xFEFF).u16(0x40).u32(0x02000000).u32(dataAt + dataBytes.length).u16(3).u16(0);
        b.u16(0x4000).u16(0).u32(0x40).u32(infoBytes.length).u16(0x4001).u16(0).u32(seekAt).u32(0x20).u16(0x4002).u16(0).u32(dataAt).u32(dataBytes.length);
        b.padTo(0x40).raw(infoBytes).str("SEEK").u32(0x20).zero(0x18).raw(dataBytes);
        return b.done();
    }

    static byte[] swavBody(int type, int rate, byte[] samples) {
        return new B().u8(type).u8(0).u16(rate).u16(0).u16(0).u32(samples.length / 4).raw(samples).done();
    }
    static byte[] swar(byte[]... bodies) {
        B b = new B().str("SWAR").u16(0xFEFF).u16(0x0100).u32(0).u16(0x10).u16(1).str("DATA").u32(0).zero(32).u32(bodies.length);
        int at = 0x3C + bodies.length * 4;
        for (byte[] body : bodies) { b.u32(at); at += body.length; }
        for (byte[] body : bodies) b.raw(body);
        return b.done();
    }
    static byte[] strmPcm8(int rate, byte[] left, byte[] right, int blockSamples) {
        int n = left.length, blocks = (n + blockSamples - 1) / blockSamples, last = n - (blocks - 1) * blockSamples;
        B b = new B().str("STRM").u16(0xFEFF).u16(0x0100).u32(0).u16(0x10).u16(2).str("HEAD").u32(0x50);
        b.u8(0).u8(0).u8(2).u8(0).u16(rate).u16(0).u32(0).u32(n).u32(0x68).u32(blocks).u32(blockSamples).u32(blockSamples).u32(last).u32(last).zero(32);
        b.str("DATA").u32(0);
        for (int blk = 0; blk < blocks; blk++) {
            int from = blk * blockSamples, to = Math.min(n, from + blockSamples);
            b.raw(Arrays.copyOfRange(left, from, to)).raw(Arrays.copyOfRange(right, from, to));
        }
        return b.done();
    }

    // ---------------------------------------------------------------- DS graphics

    static B nitroDict(B b, int elementSize, String[] names, byte[][] elements) {
        int n = names.length;
        b.u8(0).u8(n).u16(0).u16(8).u16(8 + 4 * n).u32(0x17F).zero(4 * n).u16(elementSize).u16(4 + n * elementSize);
        for (byte[] e : elements) b.raw(e);
        for (String name : names) { b.str(name); b.zero(16 - name.length()); }
        return b;
    }
    /** BTX0 with one 8x8 4-colour texture "tex" and palette "tex_pl" (colour 0 transparent). */
    static byte[] nsbtx() {
        B tex = new B().str("TEX0").u32(0).u32(0).u16(2).u16(0x3C).u32(0).u32(0).u32(0).u16(0).u16(0).u32(0).u32(0).u32(0).u32(0).u32(1).u32(0).u32(0);
        check(tex.size() == 0x3C, "TEX0 header size");
        int params = (0 << 4) | (0 << 7) | (2 << 10) | (1 << 13);
        nitroDict(tex, 8, new String[]{"tex"}, new byte[][]{new B().u16(0).u16(params).u32(0).done()});
        int palInfo = tex.size();
        nitroDict(tex, 4, new String[]{"tex_pl"}, new byte[][]{new B().u16(0).u16(0).done()});
        int texData = tex.size();
        for (int y = 0; y < 8; y++) { tex.u8(0xE4).u8(0xE4); }       // each byte: pixels 0,1,2,3
        int palData = tex.size();
        tex.u16(0x7C1F).u16(0x001F).u16(0x03E0).u16(0x7C00);         // magenta, red, green, blue
        byte[] t = tex.done();
        poke32(t, 4, t.length); poke32(t, 0x14, texData); poke32(t, 0x34, palInfo); poke32(t, 0x38, palData);
        return new B().str("BTX0").u16(0xFEFF).u16(1).u32(0x14 + t.length).u16(0x10).u16(1).u32(0x14).raw(t).done();
    }

    static byte[] nscr(int w, int h, int[] entries) {
        B b = new B().str("RCSN").u16(0xFEFF).u16(0x0100).u32(0).u16(0x10).u16(1).str("NRCS").u32(0x14 + entries.length * 2).u16(w).u16(h).u32(0).u32(entries.length * 2);
        for (int e : entries) b.u16(e);
        return b.done();
    }

    // ---------------------------------------------------------------- tests

    public static void main(String[] args) throws Exception {
        byte[] hello = "hello world, hello world!".getBytes(StandardCharsets.ISO_8859_1);
        byte[] other = new byte[37];
        for (int i = 0; i < other.length; i++) other[i] = (byte) (i * 5);

        // Yaz0: literals, and a hand-made stream with a back-reference ("abc" + copy 6 from distance 3)
        check(Arrays.equals(CtrArchives.yaz0(yaz0Literal(hello)), hello), "Yaz0 literals");
        byte[] ref = new B().str("Yaz0").u8(0).u8(0).u8(0).u8(9).zero(8).u8(0xE0).str("abc").u8(0x40).u8(0x02).done();
        check(new String(CtrArchives.yaz0(ref), StandardCharsets.ISO_8859_1).equals("abcabcabc"), "Yaz0 back-reference");

        // SARC with and without names
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("ui/hello.txt", hello); files.put("data.bin", other);
        List<CtrArchives.Item> items = CtrArchives.list(sarc(files, true));
        check(items.size() == 2 && items.get(0).path.equals("ui/hello.txt") && items.get(1).path.equals("data.bin"), "SARC names");
        byte[] sarcBytes = sarc(files, true);
        check(Arrays.equals(items.get(0).bytes(sarcBytes), hello) && Arrays.equals(items.get(1).bytes(sarcBytes), other), "SARC data");
        check(CtrArchives.list(sarc(files, false)).get(0).path.startsWith("0x"), "SARC without names uses the hash");

        // darc, ZAR, GARC
        byte[] darcBytes = darc(hello, other);
        items = CtrArchives.list(darcBytes);
        check(items.size() == 2 && items.get(0).path.equals("img/a.bin") && items.get(1).path.equals("b.bin"), "darc paths: " + items.get(0).path + ", " + items.get(1).path);
        check(Arrays.equals(items.get(0).bytes(darcBytes), hello) && Arrays.equals(items.get(1).bytes(darcBytes), other), "darc data");
        byte[] zarBytes = zar("scene/room.bin", hello);
        items = CtrArchives.list(zarBytes);
        check(items.size() == 1 && items.get(0).path.equals("scene/room.bin") && Arrays.equals(items.get(0).bytes(zarBytes), hello), "ZAR");
        byte[] garcBytes = garc(hello, other);
        items = CtrArchives.list(garcBytes);
        check(items.size() == 2 && items.get(0).path.startsWith("00000") && Arrays.equals(items.get(1).bytes(garcBytes), other), "GARC");

        // CTPK: one 16x8 RGBA8 texture
        byte[] pixels = rgba8(16, 8, AssetFormatsTest::colour);
        B ctpk = new B().str("CTPK").u16(1).u16(1).u32(0x80).u32(pixels.length).u32(0).u32(0).zero(8);
        ctpk.u32(0x44).u32(pixels.length).u32(0).u32(0).u16(16).u16(8).u8(1).u8(2).u16(0).u32(0x40).u32(0);
        ctpk.u32(pixels.length).str("art/icon.tga").u8(0).padTo(0x80).raw(pixels);
        List<CtrTextures.Texture> tex = CtrTextures.decode(ctpk.done());
        check(tex.size() == 1 && tex.get(0).name.equals("icon") && tex.get(0).width == 16 && tex.get(0).height == 8, "CTPK header");
        for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) check(tex.get(0).argb[y * 16 + x] == colour(x, y), "CTPK pixel " + x + "," + y);

        // BCLIM, 12x8 visible inside a 16x8 stored image
        byte[] clim = new B().raw(pixels).raw(limFooter(false, 12, 8, 9, 0, pixels.length)).done();
        tex = CtrTextures.decode(clim);
        check(tex.get(0).width == 12 && tex.get(0).height == 8, "BCLIM size");
        for (int y = 0; y < 8; y++) for (int x = 0; x < 12; x++) check(tex.get(0).argb[y * 12 + x] == colour(x, y), "BCLIM crop");

        // BFLIM, visible 16 wide × 8 high, stored rotated/transposed as 8 wide × 16 high
        byte[] stored = rgba8(8, 16, AssetFormatsTest::colour);
        tex = CtrTextures.decode(new B().raw(stored).raw(limFooter(true, 16, 8, 9, 8, stored.length)).done());
        check(tex.get(0).width == 16 && tex.get(0).height == 8, "BFLIM size");
        for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) check(tex.get(0).argb[y * 16 + x] == colour(y, x), "BFLIM transpose");
        tex = CtrTextures.decode(new B().raw(stored).raw(limFooter(true, 16, 8, 9, 4, stored.length)).done());
        for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) check(tex.get(0).argb[y * 16 + x] == colour(7 - y, x), "BFLIM rotate");

        // CGFX: a TXOB object with a self-relative name and data pointer
        B cgfx = new B().str("CGFX").u16(0xFEFF).u16(0x14).u32(0x05000000).u32(0).u32(1).zero(0x0C);
        int typeAt = cgfx.size();
        cgfx.u32(0x20000011).str("TXOB").u32(0).u32(0).u32(0).u32(0).u32(8).u32(16).u32(0x6752).u32(0x1401).u32(1).u32(0).u32(0).u32(0).u32(0);
        cgfx.u32(8).u32(16).u32(pixels.length).u32(0).u32(0).u32(0).u32(0);
        int nameAt = cgfx.size(); cgfx.str("COMMON_tex").u8(0).padTo((cgfx.size() + 8) & ~7);
        int dataAt = cgfx.size(); cgfx.raw(pixels);
        byte[] cgfxBytes = cgfx.done();
        int magicAt = typeAt + 4;
        poke32(cgfxBytes, magicAt + 8, nameAt - (magicAt + 8));
        poke32(cgfxBytes, magicAt + 0x44, dataAt - (magicAt + 0x44));
        tex = CtrTextures.decode(cgfxBytes);
        check(tex.size() == 1 && tex.get(0).name.equals("COMMON_tex") && tex.get(0).width == 16 && tex.get(0).argb[5 * 16 + 9] == colour(9, 5), "CGFX texture");

        // BCH: one texture described by GPU commands
        int commandsAt = 0x80, dataSection = 0x100;
        B bch = new B().str("BCH\0").u8(0x21).u8(0x21).u16(0xA000).u32(0x44).u32(0x70).u32(commandsAt).u32(dataSection).u32(0).u32(0)
                .u32(0x2C).u32(0x10).u32(0x20).u32(pixels.length).u32(0).u32(0).u32(0).u32(0).u16(0).u16(0);
        bch.padTo(commandsAt).u32((16L << 16) | 8).u32(0x000F0082).u32(0).u32(0x000F0085).u32(0).u32(0x000F008E).u32(1).u32(0x000F023D);
        bch.padTo(dataSection).raw(pixels);
        tex = CtrTextures.decode(bch.done());
        check(tex.size() == 1 && tex.get(0).width == 16 && tex.get(0).height == 8 && tex.get(0).argb[3 * 16 + 14] == colour(14, 3), "BCH texture");

        // BCWAV PCM16 and DSP ADPCM (zero coefficients: sample = nibble << scale)
        B pcm = new B();
        short[] wave = new short[50];
        for (int i = 0; i < wave.length; i++) { wave[i] = (short) (i * 601 - 15000); pcm.u16(wave[i]); }
        byte[] wav = GameAudio.toWav(bcwav(1, 22050, wave.length, pcm.done(), false));
        check(wavRate(wav) == 22050 && Arrays.equals(pcmOfWav(wav, 0), wave), "BCWAV PCM16");
        byte[] frame = {0x03, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A, (byte) 0xBC, (byte) 0xDE};
        short[] dsp = pcmOfWav(GameAudio.toWav(bcwav(2, 32000, 14, frame, true)), 0);
        int[] nibbles = {1, 2, 3, 4, 5, 6, 7, -8, -7, -6, -5, -4, -3, -2};
        for (int i = 0; i < 14; i++) check(dsp[i] == nibbles[i] * 8, "BCWAV DSP sample " + i + " = " + dsp[i]);

        // BCSTM PCM16 stereo across three blocks
        short[] left = new short[70], right = new short[70];
        for (int i = 0; i < 70; i++) { left[i] = (short) (i * 100); right[i] = (short) (-i * 37); }
        wav = GameAudio.toWav(bcstmPcm16(32728, new short[][]{left, right}, 32));
        check(wavRate(wav) == 32728 && Arrays.equals(pcmOfWav(wav, 0), left) && Arrays.equals(pcmOfWav(wav, 1), right), "BCSTM PCM16 stereo");

        // DS: STRM PCM8 stereo, SWAR with PCM16 and IMA ADPCM samples, SDAT
        byte[] l8 = new byte[20], r8 = new byte[20];
        for (int i = 0; i < 20; i++) { l8[i] = (byte) (i * 3); r8[i] = (byte) (-i * 2); }
        byte[] strm = strmPcm8(16000, l8, r8, 8);
        wav = GameAudio.toWav(strm);
        check(wavRate(wav) == 16000 && pcmOfWav(wav, 0)[19] == (short) (l8[19] << 8) && pcmOfWav(wav, 1)[9] == (short) (r8[9] << 8), "STRM PCM8 stereo");
        byte[] pcmBody = swavBody(1, 11025, new B().u16(100).u16(-200).u16(300).u16(-400).done());
        byte[] imaBody = swavBody(2, 8000, new byte[]{0x10, 0x00, 0x00, 0x00, 0x77, 0x00, (byte) 0xFF, 0x00});
        byte[] swarBytes = swar(pcmBody, imaBody);
        List<NdsMedia.Sound> waves = NdsMedia.swarWaves(swarBytes);
        check(waves.size() == 2 && Arrays.equals(pcmOfWav(waves.get(0).data, 0), new short[]{100, -200, 300, -400}), "SWAR PCM16");
        short[] ima = pcmOfWav(waves.get(1).data, 0);
        check(ima.length == 8 && ima[0] == 16 + 11 && ima[1] > ima[0] && ima[4] < ima[3], "SWAR IMA ADPCM " + Arrays.toString(ima));
        int infoSize = 0x60, symbSize = 0x60, infoAt = 0x40 + symbSize, fatAt = infoAt + infoSize, fileAt = fatAt + 12 + 32;
        B sdat = new B().str("SDAT").u16(0xFEFF).u16(0x0100).u32(0).u16(0x40).u16(4).u32(0x40).u32(symbSize).u32(infoAt).u32(infoSize).u32(fatAt).u32(12 + 32).u32(fileAt).u32(0).padTo(0x40);
        sdat.str("SYMB").u32(symbSize).zero(12).u32(0x40).zero(12).u32(0x48).padTo(0x40 + 0x40).u32(1).u32(0x50).u32(1).u32(0x58).str("WAVE_SE\0").str("BGM_01\0\0");
        sdat.padTo(infoAt).str("INFO").u32(infoSize).zero(12).u32(0x40).zero(12).u32(0x4C).padTo(infoAt + 0x40).u32(1).u32(0x48).u16(0).u16(0).u32(1).u32(0x54).u16(1).u16(0).zero(8);
        sdat.padTo(fatAt).str("FAT ").u32(12 + 32).u32(2).u32(fileAt).u32(swarBytes.length).zero(8).u32(fileAt + swarBytes.length).u32(strm.length).zero(8);
        sdat.raw(swarBytes).raw(strm);
        List<NdsMedia.Sound> sounds = NdsMedia.sdatFiles(sdat.done());
        check(sounds.size() == 2 && sounds.get(0).path.equals("WAVEARC/WAVE_SE.swar") && sounds.get(1).path.equals("STRM/BGM_01.strm"),
                "SDAT names: " + sounds.get(0).path + ", " + sounds.get(1).path);
        check(Arrays.equals(sounds.get(0).data, swarBytes) && Arrays.equals(sounds.get(1).data, strm), "SDAT data");

        // NSBTX: 4-colour texture, colour 0 transparent
        List<NdsMedia.Texture> ds = NdsMedia.textures(nsbtx());
        check(ds.size() == 1 && ds.get(0).name.equals("tex") && ds.get(0).width == 8 && ds.get(0).height == 8, "NSBTX header");
        check(ds.get(0).argb[0] == 0 && ds.get(0).argb[1] == 0xFFFF0000 && ds.get(0).argb[2] == 0xFF00FF00 && ds.get(0).argb[3] == 0xFF0000FF,
                "NSBTX pixels " + Integer.toHexString(ds.get(0).argb[1]));

        // NSCR: 16x8 map of two tiles from a 2-tile sheet, second one H+V flipped and using bank 1
        int[] sheet0 = new int[16 * 8], sheet1 = new int[16 * 8];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) { sheet0[y * 16 + x] = colour(x, y); sheet1[y * 16 + x] = colour(x, y) ^ 0xffffff; }
        byte[] map = nscr(16, 8, new int[]{1, 0 | 0x400 | 0x800 | 0x1000});
        check(Arrays.equals(NdsMedia.nscrBanks(map), new int[]{0, 1}), "NSCR palette banks");
        NdsMedia.Texture screen = NdsMedia.renderNscr(map, new int[][]{sheet0, sheet1}, 16, 8);
        check(screen.width == 16 && screen.height == 8 && screen.argb[0] == colour(8, 0) && screen.argb[2 * 16 + 3] == colour(11, 2), "NSCR plain tile");
        check(screen.argb[8] == (colour(7, 7) ^ 0xffffff) && screen.argb[7 * 16 + 15] == (colour(0, 0) ^ 0xffffff), "NSCR flipped tile with bank");

        // Expander: a SARC holding a Yaz0-compressed BCLIM, a BCWAV and a nested darc
        Map<String, byte[]> pack = new LinkedHashMap<>();
        pack.put("tex/logo.bclim.szs", yaz0Literal(clim));
        pack.put("snd/beep.bcwav", bcwav(1, 22050, wave.length, pcm.done(), false));
        pack.put("../evil/inner.arc", darcBytes);
        final List<String> written = new ArrayList<>();
        AssetExpander.Stats stats = new AssetExpander.Stats();
        AssetExpander.expand("pack.sarc", sarc(pack, true), new AssetExpander.Sink() {
            public void file(String path, byte[] data) { written.add(path); }
            public void png(String path, int w, int h, int[] argb) { written.add(path + " " + w + "x" + h); }
            public boolean cancelled() { return false; }
        }, stats);
        String all = String.join("\n", written);
        check(written.contains("pack.sarc_contents/tex/logo.bclim.szs.decompressed.png 12x8"), "expander: compressed BCLIM → PNG\n" + all);
        check(written.contains("pack.sarc_contents/snd/beep.bcwav.wav"), "expander: BCWAV → WAV\n" + all);
        check(written.contains("pack.sarc_contents/evil/inner.arc_contents/img/a.bin"), "expander: nested archive, path made safe\n" + all);
        for (String w : written) check(!w.contains(".."), "no parent-directory paths: " + w);
        check(stats.images == 1 && stats.sounds == 1 && stats.decompressed == 1 && stats.unpacked == 5, "expander stats " + stats.images + "/" + stats.sounds + "/" + stats.decompressed + "/" + stats.unpacked);
        check(AssetExpander.worthReading(sarcBytes, sarcBytes, sarcBytes.length) && AssetExpander.worthReading(Arrays.copyOf(clim, 8), clim, clim.length)
                && !AssetExpander.worthReading("plain text".getBytes(StandardCharsets.ISO_8859_1), null, 10), "sniffing");

        // Garbage and truncated inputs never throw out of the expander
        java.util.Random random = new java.util.Random(7);
        byte[][] seeds = {sarcBytes, darcBytes, zarBytes, garcBytes, clim, cgfxBytes, bch.done(), strm, swarBytes, sdat.done(), nsbtx(), ctpk.done()};
        for (byte[] seed : seeds) for (int round = 0; round < 60; round++) {
            byte[] broken = round % 2 == 0 ? Arrays.copyOf(seed, Math.max(8, random.nextInt(seed.length))) : seed.clone();
            for (int k = 0; k < 6; k++) broken[random.nextInt(broken.length)] = (byte) random.nextInt(256);
            AssetExpander.expand("fuzz.bin", broken, new AssetExpander.Sink() {
                public void file(String path, byte[] data) { }
                public void png(String path, int w, int h, int[] argb) { }
                public boolean cancelled() { return false; }
            }, new AssetExpander.Stats());
        }
        System.out.println("3DS/DS archive, texture, audio and tilemap format tests passed");
    }
}
