import com.retrotools.cciextractor.MgsStage;

import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

/**
 * Synthetic Metal Gear Solid STAGE.DIR and FACE.DAT (no game data), built from the documented
 * layout: two areas, a packed cache run closed by a 0xFF entry, a DAR archive holding a
 * 16-colour and a 256-colour PCX (the latter with the 1-3 padding bytes MGS adds after the
 * palette), plus a portrait with an expression piece and a two-frame animation.
 * The retail files were used to check the reader during development but can't be included.
 */
public class MgsStageTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static final class B {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        B u8(int v) { o.write(v); return this; }
        B u16(int v) { u8(v); u8(v >> 8); return this; }
        B u32(long v) { u16((int) v); u16((int) (v >> 16)); return this; }
        B raw(byte[] b) { o.write(b, 0, b.length); return this; }
        B str8(String s) { for (int i = 0; i < 8; i++) u8(i < s.length() ? s.charAt(i) : 0); return this; }
        B pad(int align) { while (o.size() % align != 0) o.write(0); return this; }
        B padTo(int size) { while (o.size() < size) o.write(0); return this; }
        int size() { return o.size(); }
        byte[] done() { return o.toByteArray(); }
    }

    static long name(int id, char group, int type) { return (id & 0xffffL) | (long) group << 16 | (long) (type & 0xff) << 24; }

    /** PCX header: version 5, RLE. */
    static B pcxHeader(int bpp, int planes, int w, int h, int bpl, int[] pal16) {
        B b = new B().u8(0x0a).u8(5).u8(1).u8(bpp).u16(0).u16(0).u16(w - 1).u16(h - 1).u16(72).u16(72);
        for (int i = 0; i < 16; i++) { int c = pal16 == null ? 0 : pal16[i]; b.u8(c >> 16).u8(c >> 8).u8(c); }
        b.u8(0).u8(planes).u16(bpl).u16(1).u16(640).u16(480);
        return b.padTo(128);
    }

    /** Plain RLE: runs for repeats, escaped single bytes >= 0xC0. */
    static void rle(B b, byte[] line) {
        for (int i = 0; i < line.length; ) {
            int v = line[i] & 0xff, n = 1;
            while (i + n < line.length && (line[i + n] & 0xff) == v && n < 63) n++;
            if (n > 1 || v >= 0xc0) { b.u8(0xc0 | n).u8(v); } else b.u8(v);
            i += n;
        }
    }

    static int[] pal16() { int[] p = new int[16]; for (int i = 0; i < 16; i++) p[i] = (i * 17) << 16 | (255 - i * 17) << 8 | (i * 5); return p; }

    /** 16-colour, 4 bit planes: pixel (x, y) = (x + y) & 15. Width 13 to test partial bytes. */
    static byte[] pcx16(int w, int h) {
        int bpl = (w + 7) / 8 + 1;   // padded line, as encoders often do
        B b = pcxHeader(1, 4, w, h, bpl, pal16());
        for (int y = 0; y < h; y++)
            for (int plane = 0; plane < 4; plane++) {
                byte[] line = new byte[bpl];
                for (int x = 0; x < w; x++) if ((((x + y) & 15) >> plane & 1) != 0) line[x >> 3] |= (byte) (0x80 >> (x & 7));
                rle(b, line);
            }
        return b.pad(4).done();
    }

    /** 256-colour: pixel (x, y) = (x * 7 + y * 3) & 255, palette i -> (i, 255 - i, i / 2). */
    static byte[] pcx256(int w, int h, int extraPad) {
        B b = pcxHeader(8, 1, w, h, w, null);
        for (int y = 0; y < h; y++) {
            byte[] line = new byte[w];
            for (int x = 0; x < w; x++) line[x] = (byte) ((x * 7 + y * 3) & 255);
            rle(b, line);
        }
        b.u8(0x0c);
        for (int i = 0; i < 256; i++) b.u8(i).u8(255 - i).u8(i / 2);
        for (int i = 0; i < extraPad; i++) b.u8(0);
        return b.done();
    }

    static byte[] stageDir() {
        byte[] tex16 = pcx16(13, 5), tex256 = pcx256(20, 6, 3);
        B dar = new B();
        dar.u16(0x1111).u8('p').u8(0).u32(tex16.length).raw(tex16);
        dar.u16(0x2222).u8('p').u8(0).u32(tex256.length).raw(tex256);
        byte[] kmd = {1, 2, 3, 4, 5, 6, 7}, lit = {9, 9, 9}, gcx = new byte[40], wvx = new byte[100];

        B f = new B();
        // Directory: two areas at sectors 1 and 5
        f.u32(24).str8("s00a").u32(1).str8("init").u32(5).padTo(0x800);
        // Area s00a: packed cache run (kmd, lit, gcx: sizes are offsets, 0xFF holds the total), then a DAR, then sound
        f.u16(1).u16(4);
        f.u32(name(0xaaaa, 'c', 'k')).u32(0);
        f.u32(name(0xbbbb, 'c', 'l')).u32(kmd.length);
        f.u32(name(0xcccc, 'c', 'g')).u32(kmd.length + lit.length);
        f.u32(name(0, 'c', 0xff)).u32(kmd.length + lit.length + gcx.length);
        f.u32(name(0, 'n', 'd')).u32(dar.size());
        f.u32(name(0xdddd, 's', 'w')).u32(wvx.length);
        f.u32(0).u32(0).padTo(0x1000);
        f.raw(kmd).raw(lit).raw(gcx).pad(0x800);   // packed run, aligned only at its end
        f.raw(dar.done()).pad(0x800);
        f.raw(wvx).pad(0x800);
        check(f.size() == 5 * 0x800, "fixture layout: second area at sector 5, got " + f.size());
        // Area init: one resident texture outside any DAR
        f.u16(1).u16(2).u32(name(0xeeee, 'r', 'p')).u32(tex16.length).u32(0).u32(0).padTo(6 * 0x800);
        f.raw(tex16).pad(0x800);
        return f.done();
    }

    static byte[] faceDat() {
        // Palette: index i -> 15-bit (i & 31, (i >> 2) & 31, 31 - (i & 31)); index 0 = 0 = transparent
        B pal = new B();
        for (int i = 0; i < 256; i++) pal.u16(i == 0 ? 0 : (i & 31) | ((i >> 2) & 31) << 5 | (31 - (i & 31)) << 10);
        byte[] palette = pal.done();
        // Portrait: base 6x4 of index 7, eyes-blink piece 2x2 at (1, 1) of index 9 with one transparent pixel
        B face = new B();
        int palAt = 28, baseAt = palAt + 512, blinkAt = baseAt + 4 + 24;
        face.u32(palAt).u32(baseAt).u32(0).u32(blinkAt).u32(0).u32(0).u32(0).raw(palette);
        face.u8(0).u8(0).u8(6).u8(4); for (int i = 0; i < 24; i++) face.u8(7);
        face.u8(1).u8(1).u8(2).u8(2).u8(9).u8(0).u8(9).u8(9);
        // Animation: two frames sharing a palette, plus an empty slot
        B anim = new B();
        int aPal = 4 + 3 * 12, f0 = aPal + 512, f1 = f0 + 4 + 4;
        anim.u32(3).u32(aPal).u32(f0).u32(0).u32(0).u32(0).u32(0).u32(aPal).u32(f1).u32(0).raw(palette);
        anim.u8(0).u8(0).u8(2).u8(2).u8(3).u8(3).u8(3).u8(3);
        anim.u8(0).u8(0).u8(2).u8(2).u8(4).u8(4).u8(4).u8(4);
        byte[] fb = face.done(), ab = anim.done();

        B f = new B();
        f.u32(2);
        int dataAt = 2 * 12;
        f.u16(0).u16(0x1234).u32(fb.length).u32(dataAt);
        f.u16(1).u16(0x5678).u32(ab.length).u32(dataAt + fb.length);
        f.raw(fb).raw(ab).pad(0x800);
        // A second group with the same portrait
        f.u32(1).u16(0).u16(0x9abc).u32(fb.length).u32(12).raw(fb).pad(0x800);
        return f.done();
    }

    static int psx(int v) {
        int r = (v & 31) * 255 / 31, g = (v >> 5 & 31) * 255 / 31, b = (v >> 10 & 31) * 255 / 31;
        return (v == 0 ? 0 : 0xff000000) | r << 16 | g << 8 | b;
    }

    static int palColour(int i) { return psx(i == 0 ? 0 : (i & 31) | ((i >> 2) & 31) << 5 | (31 - (i & 31)) << 10); }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mgsstage");
        Path stage = dir.resolve("STAGE.DIR"), face = dir.resolve("FACE.DAT"), junk = dir.resolve("junk.bin");
        Files.write(stage, stageDir());
        Files.write(face, faceDat());
        byte[] random = new byte[0x4000];
        new Random(5).nextBytes(random);
        Files.write(junk, random);

        try (FileChannel ch = FileChannel.open(stage)) {
            check(MgsStage.isStageDir(ch), "STAGE.DIR detected");
            check(!MgsStage.isFaceDat(ch), "STAGE.DIR is not FACE.DAT");
            MgsStage.Info info = MgsStage.parseStageDir(ch);
            check(info.parts.size() == 2 && info.parts.get(0).name.equals("s00a") && info.parts.get(1).name.equals("init"), "areas");
            MgsStage.Part s00a = info.parts.get(0);
            check(s00a.entries.size() == 6, "s00a files: " + s00a.entries.size());
            check(info.textures == 3 && info.files == 7, "counts " + info.textures + "/" + info.files);
            // Packed run: kmd at the area's data start, lit and gcx straight after it (not sector-aligned)
            MgsStage.Entry kmd = s00a.entries.get(0), lit = s00a.entries.get(1), gcx = s00a.entries.get(2);
            check(kmd.type == 'k' && kmd.size == 7 && kmd.offset == 0x1000, "kmd " + kmd.offset + "/" + kmd.size);
            check(lit.type == 'l' && lit.size == 3 && lit.offset == 0x1007, "lit " + lit.offset + "/" + lit.size);
            check(gcx.type == 'g' && gcx.size == 40 && gcx.offset == 0x100a, "gcx " + gcx.offset + "/" + gcx.size);
            check(MgsStage.read(ch, kmd)[6] == 7 && MgsStage.read(ch, lit)[0] == 9, "packed data");
            check(gcx.path().equals("s00a/cache/cccc.gcx"), "path " + gcx.path());
            // DAR contents and the aligned sound file after it
            MgsStage.Entry t16 = s00a.entries.get(3), t256 = s00a.entries.get(4), wvx = s00a.entries.get(5);
            check(t16.isTexture() && t16.id == 0x1111 && t16.group == 'n', "DAR texture 1");
            check(t256.isTexture() && t256.id == 0x2222, "DAR texture 2");
            check(wvx.type == 'w' && wvx.offset == 0x2000 && wvx.size == 100 && wvx.path().equals("s00a/sound/dddd.wvx"), "sound file " + wvx.offset);

            MgsStage.Image a = MgsStage.decodePcx(MgsStage.read(ch, t16));
            check(a.width == 13 && a.height == 5, "16-colour size");
            int[] p16 = pal16();
            for (int y = 0; y < 5; y++) for (int x = 0; x < 13; x++)
                check(a.argb[y * 13 + x] == (0xff000000 | p16[(x + y) & 15]), "16-colour pixel " + x + "," + y);
            MgsStage.Image b = MgsStage.decodePcx(MgsStage.read(ch, t256));
            check(b.width == 20 && b.height == 6, "256-colour size");
            for (int y = 0; y < 6; y++) for (int x = 0; x < 20; x++) {
                int i = (x * 7 + y * 3) & 255;
                check(b.argb[y * 20 + x] == (0xff000000 | i << 16 | (255 - i) << 8 | i / 2), "256-colour pixel " + x + "," + y);
            }
            MgsStage.Entry resident = info.parts.get(1).entries.get(0);
            check(resident.group == 'r' && resident.offset == 6 * 0x800 && MgsStage.decodePcx(MgsStage.read(ch, resident)).width == 13, "resident texture");
            check(info.label(0).contains("2 textures") && info.label(0).contains("4 other"), "label " + info.label(0));
        }

        try (FileChannel ch = FileChannel.open(face)) {
            check(MgsStage.isFaceDat(ch), "FACE.DAT detected");
            check(!MgsStage.isStageDir(ch), "FACE.DAT is not STAGE.DIR");
            MgsStage.Info info = MgsStage.parseFaceDat(ch);
            check(info.face && info.parts.size() == 2 && info.files == 3, "face groups");
            MgsStage.Entry portrait = info.parts.get(0).entries.get(0), anim = info.parts.get(0).entries.get(1);
            check(portrait.group == 'f' && portrait.id == 0x1234 && anim.group == 'a', "face entries");
            Map<String, MgsStage.Image> parts = MgsStage.decodeFace(portrait, MgsStage.read(ch, portrait));
            check(parts.keySet().toString().equals("[base, eyes-blink]"), "portrait parts " + parts.keySet());
            MgsStage.Image base = parts.get("base"), blink = parts.get("eyes-blink");
            check(base.width == 6 && base.height == 4 && base.argb[0] == palColour(7), "base");
            // Piece drawn at (1, 1): its transparent pixel leaves the base showing
            check(blink.argb[1 * 6 + 1] == palColour(9), "piece pixel");
            check(blink.argb[1 * 6 + 2] == palColour(7), "transparent piece pixel keeps base");
            check(blink.argb[2 * 6 + 2] == palColour(9) && blink.argb[0] == palColour(7), "piece placement");
            Map<String, MgsStage.Image> frames = MgsStage.decodeFace(anim, MgsStage.read(ch, anim));
            check(frames.keySet().toString().equals("[frame00, frame02]"), "frames " + frames.keySet());
            check(frames.get("frame02").argb[3] == palColour(4), "frame pixels");
        }

        try (FileChannel ch = FileChannel.open(junk)) {
            check(!MgsStage.isStageDir(ch) && !MgsStage.isFaceDat(ch), "random data rejected");
        }
        // Corrupt input fails cleanly
        byte[] cut = stageDir();
        cut[0x800 + 4 + 4 * 8 + 4 + 2] = (byte) 0x7f;   // the DAR's size, far past the area
        Files.write(junk, cut);
        try (FileChannel ch = FileChannel.open(junk)) {
            try { MgsStage.parseStageDir(ch); check(false, "corrupt STAGE.DIR accepted"); } catch (java.io.IOException expected) { }
        }
        byte[] tex = pcx256(8, 8, 0);
        try { MgsStage.decodePcx(java.util.Arrays.copyOf(tex, 140)); check(false, "short PCX accepted"); } catch (java.io.IOException expected) { }

        System.out.println("MgsStageTest OK");
    }
}
