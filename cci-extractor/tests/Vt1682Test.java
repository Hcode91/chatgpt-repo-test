import com.retrotools.cciextractor.Vt1682;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Synthetic VT1682 ROM: a hand-assembled 6502 boot program that loads a palette and a tilemap through
 * the video RAM port, selects graphics segments, sets one 16x16 sprite, does a DMA and waits on the
 * sound-CPU flag. Graphics are made-up patterns, no game data.
 */
public class Vt1682Test {
    static ByteArrayOutputStream code = new ByteArrayOutputStream();
    static void b(int... v) { for (int x : v) code.write(x); }
    static void sta(int addr, int value) { b(0xa9, value, 0x8d, addr & 0xff, addr >> 8); }
    static int pc() { return 0xe000 + code.size(); }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("ok - " + what);
    }

    public static void main(String[] args) throws Exception {
        byte[] rom = new byte[0x100000];
        // palette: 16 colours (15-bit) at palette RAM 0x1c00, 16 more for sprite row 1
        int[] pal = new int[32];
        for (int i = 0; i < 32; i++) pal[i] = ((i * 2) & 31) << 10 | ((31 - i) & 31) << 5 | ((i * 5) & 31);
        // graphics: BK1 segment 0x10 (0x20000), tiles 1 and 2; sprite segment 0x11 (0x22000), tile 3
        for (int t = 1; t <= 2; t++) for (int i = 0; i < 128; i++) rom[0x20000 + t * 128 + i] = (byte) ((i * 7 + t * 3) | 0x11);
        for (int i = 0; i < 128; i++) rom[0x22000 + 3 * 128 + i] = (byte) (i * 13 + 5);

        // ---- boot program at $E000 (ROM 0x7e000)
        b(0x78);                       // SEI
        sta(0x2107, 0);                // PRG bank
        sta(0x2005, 0x00); sta(0x2006, 0x1c);
        int palTable = 0xe200;
        b(0xa2, 0x00); int loop = pc();  // LDX #0
        b(0xbd, palTable & 0xff, palTable >> 8, 0x8d, 0x07, 0x20, 0xe8, 0xe0, 64, 0xd0); b((loop - (pc() + 1)) & 0xff);
        sta(0x2005, 0x00); sta(0x2006, 0x00);
        sta(0x2007, 0x01); sta(0x2007, 0x00); sta(0x2007, 0x02); sta(0x2007, 0x00); // tiles 1, 2 (palette row 0)
        sta(0x201c, 0x10); sta(0x201d, 0x00);   // BK1 segment
        sta(0x2012, 0x00); sta(0x200f, 0x02); sta(0x2013, 0x81); // BK1: enabled, 4bpp, 16x16
        sta(0x201a, 0x11); sta(0x201b, 0x00);   // sprite segment
        sta(0x2002, 0x00); sta(0x2003, 0x00);
        int[] spr = {3, 0x10, 10, 0, 20, 0};    // tile 3, palette row 1
        for (int v : spr) sta(0x2004, v);
        sta(0x2018, 0x07);                      // sprites on, 16x16
        sta(0x2122, 0x00); sta(0x2123, 0x03); sta(0x2124, 0x00); sta(0x2125, 0x02); sta(0x2127, 0x01); // RAM DMA
        sta(0x1fa2, 0x01);                      // ask the "sound CPU"
        int wait = pc(); b(0xad, 0xa2, 0x1f, 0xd0); b((wait - (pc() + 1)) & 0xff);
        sta(0x0400, 0x5a);                      // reached only if the handshake was released
        int self = pc(); b(0x4c, self & 0xff, self >> 8);
        int rti = pc(); b(0x40);
        byte[] prog = code.toByteArray();
        System.arraycopy(prog, 0, rom, 0x7e000, prog.length);
        for (int i = 0; i < 32; i++) { rom[0x7e200 + i * 2] = (byte) pal[i]; rom[0x7e201 + i * 2] = (byte) (pal[i] >> 8); }
        rom[0x7fffa] = (byte) rti; rom[0x7fffb] = (byte) (rti >> 8);
        rom[0x7fffc] = 0x00; rom[0x7fffd] = (byte) 0xe0;
        rom[0x7fffe] = (byte) rti; rom[0x7ffff] = (byte) (rti >> 8);

        File f = File.createTempFile("vt1682", ".bin");
        f.deleteOnExit();
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) { raf.write(rom); }
        try (FileChannel ch = new RandomAccessFile(f, "r").getChannel()) { check(Vt1682.isVt1682(ch), "synthetic ROM detected"); }
        byte[] noise = new byte[0x100000];
        new java.util.Random(5).nextBytes(noise);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) { raf.setLength(0); raf.write(noise); }
        try (FileChannel ch = new RandomAccessFile(f, "r").getChannel()) { check(!Vt1682.isVt1682(ch), "random data not detected"); }

        Vt1682.Capture cap = Vt1682.explore(rom, 12, 1, null);
        check(cap.layers.size() == 1, "one background layer captured (" + cap.layers.size() + ")");
        Vt1682.Layer l = cap.layers.get(0);
        check(l.segment == 0x10 && l.bpp == 4 && l.big && l.cols == 16 && l.rows == 16, "layer segment, depth and size");
        check(l.words[0] == 1 && l.words[1] == 2 && l.words[2] == 0, "tilemap words");
        int[] img = Vt1682.render(rom, l);
        // pixel (0,0): first nibble of tile 1
        int v = (rom[0x20000 + 128] & 0xff) & 15;
        int w = pal[v];
        int r = (w >> 10) & 31, g = (w >> 5) & 31, bb = w & 31;
        int expect = 0xff000000 | (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (bb << 3 | bb >> 2);
        check(img[0] == expect, "pixel colour matches palette entry " + v);
        check((img[40] >>> 24) == 0, "empty tile is transparent");

        check(cap.sprites.size() == 1, "one sprite sheet captured");
        Vt1682.SpriteSheet s = cap.sprites.get(0);
        check(s.segment == 0x11 && s.size == 16 && s.tiles[3] == 3 && s.palRow[3] == 1 && s.rows == 1, "sprite tile 3 with palette row 1");

        // map round trip
        Vt1682.Capture m = Vt1682.readMap(Vt1682.writeMap(cap));
        check(m.layers.size() == 1 && Arrays.equals(m.layers.get(0).words, l.words)
                && Arrays.equals(m.layers.get(0).palette, l.palette), "map file round trip");
        boolean damaged = false;
        try { Vt1682.readMap(Vt1682.writeMap(cap).replace("words 1 2", "words zz")); } catch (java.io.IOException e) { damaged = true; }
        check(damaged, "damaged map rejected");

        // edit: paint the top-left 8x8 of tile 1 with palette colour 7 and clear a row of tile 2
        byte[] out = rom.clone();
        int[] edit = img.clone();
        int w7 = pal[7]; int r7 = (w7 >> 10) & 31, g7 = (w7 >> 5) & 31, b7 = w7 & 31;
        int c7 = 0xff000000 | (r7 << 3 | r7 >> 2) << 16 | (g7 << 3 | g7 >> 2) << 8 | (b7 << 3 | b7 >> 2);
        int W = l.width();
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) edit[y * W + x] = c7;
        for (int x = 16; x < 32; x++) edit[5 * W + x] = 0;
        Vt1682.Patch p = new Vt1682.Patch();
        Map<Long, Integer> written = new HashMap<>();
        Vt1682.applyEdit(out, l, edit, W, l.height(), p, written);
        check(p.tilesWritten == 2 && p.offPalette == 0, "two tiles written, colours on palette");
        check(Arrays.equals(Vt1682.render(out, l), edit), "re-render equals the edit");
        int changed = 0;
        for (int i = 0; i < rom.length; i++) if (rom[i] != out[i]) { changed++; if (i < 0x20080 || i >= 0x20180) throw new AssertionError("byte outside tiles 1-2 changed: " + i); }
        check(changed > 0, "only tile bytes changed (" + changed + ")");
        // sprite edit
        int[] si = Vt1682.render(out, s);
        int[] sedit = si.clone();
        for (int i = 0; i < sedit.length; i++) if ((i % s.width()) >= 48 && (i % s.width()) < 64 && (i / s.width()) < 4) sedit[i] = 0;
        Vt1682.applyEdit(out, s, sedit, s.width(), s.height(), p, written);
        check(Arrays.equals(Vt1682.render(out, s), sedit), "sprite edit re-renders exactly");
        boolean sizeError = false;
        try { Vt1682.applyEdit(out, l, edit, W - 1, l.height(), p, written); } catch (IllegalArgumentException e) { sizeError = true; }
        check(sizeError, "resized picture refused");
        System.out.println("All VT1682 tests passed");
    }
}
