package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * V.R. Technology VT1682 plug-and-play ROMs (dance mats, "N-in-1" TV games).
 *
 * The VT1682 is not NES compatible: graphics are plain 4/6/8 bits-per-pixel tiles (8x8 or 16x16) read
 * straight from ROM through "segment" registers, with 15-bit palettes. Which tiles, palettes and
 * segments make up each screen is decided by game code, so this class runs the game's own 6502 code
 * (main CPU only) with scripted button presses and records every background layer and sprite it shows.
 * Hardware behaviour follows MAME's vt1682.cpp.
 */
public final class Vt1682 {
    private Vt1682() {}

    public static final long MAX_ROM = 32L << 20;

    // ------------------------------------------------------------------ detection

    /** Boot bank (the last 8 KB of the first 512 KB) holds the vectors and writes VT1682 registers. */
    public static boolean isVt1682(FileChannel ch) throws IOException {
        long size = ch.size();
        if (size < 0x100000 || size > MAX_ROM || (size & 0x7ffff) != 0) return false;
        byte[] bank = new byte[0x2000];
        if (ch.read(ByteBuffer.wrap(bank), 0x7e000) != bank.length) return false;
        int reset = (bank[0x1ffc] & 0xff) | (bank[0x1ffd] & 0xff) << 8;
        int nmi = (bank[0x1ffa] & 0xff) | (bank[0x1ffb] & 0xff) << 8;
        if (reset < 0xe000 || nmi < 0x8000) return false;
        int vtWrites = 0; boolean bankReg = false, dmaReg = false;
        for (int i = 0; i + 2 < bank.length; i++) {
            int op = bank[i] & 0xff;
            int hi = bank[i + 2] & 0xff;
            if ((op == 0x8d || op == 0x8e || op == 0x8c) && (hi == 0x20 || hi == 0x21)) {
                int lo = bank[i + 1] & 0xff;
                vtWrites++;
                if (hi == 0x21 && (lo == 0x07 || lo == 0x08)) bankReg = true;
                if (hi == 0x21 && lo == 0x27) dmaReg = true;
            }
        }
        return vtWrites >= 12 && bankReg && dmaReg;
    }

    // ------------------------------------------------------------------ captured data

    /** One background layer plane as the game showed it. */
    public static final class Layer {
        public int which;          // 0 = BK1, 1 = BK2
        public int segment;        // graphics base = segment * 0x2000
        public int bpp;            // 4, 6 or 8
        public boolean big;        // 16x16 tiles
        public int cols, rows;     // plane size in tiles
        public int[] words;        // tile words, row-major (tile 0..11, palette row 12..15)
        public int[] palette;      // 256 ARGB colours the layer uses
        public String name;

        public int tileSize() { return big ? 16 : 8; }
        public int width() { return cols * tileSize(); }
        public int height() { return rows * tileSize(); }
        public int tileBytes() { return Vt1682.tileBytes(bpp, tileSize()); }
    }

    /** Sprites seen with one segment/size/palette: drawn as a sheet ordered by tile number. */
    public static final class SpriteSheet {
        public int segment;
        public int size;           // 8 or 16
        public int cols = 16, rows;
        public int[] tiles;        // per cell: tile number, or -1 for an empty cell
        public int[] palRow;       // per cell: palette row
        public int[] palette;      // 256 ARGB colours
        public String name;
        public int tileBytes() { return Vt1682.tileBytes(4, size); }
        public int width() { return cols * size; }
        public int height() { return rows * size; }
    }

    public static final class Capture {
        public final List<Layer> layers = new ArrayList<>();
        public final List<SpriteSheet> sprites = new ArrayList<>();
        public int frames, screens;
        public String description() {
            return String.format(Locale.ROOT, "VT1682 game: %d background layers and %d sprite sheets seen in %d frames",
                    layers.size(), sprites.size(), frames);
        }
    }

    public interface Progress { boolean update(int frame, int total); }

    static int tileBytes(int bpp, int size) { return size * size * bpp / 8; }

    // ------------------------------------------------------------------ exploration

    /** Runs the game for {@code frames} frames per pass with scripted inputs and records what it draws. */
    public static Capture explore(byte[] rom, int frames, int passes, Progress progress) {
        Capture cap = new Capture();
        Map<String, Layer> layers = new LinkedHashMap<>();
        Map<String, int[]> spriteCells = new LinkedHashMap<>(); // key -> palRow per tile (4096)
        Map<String, int[]> spritePal = new HashMap<>();
        int total = frames * passes, done = 0;
        for (int pass = 0; pass < passes; pass++) {
            Machine m = new Machine(rom);
            Random rnd = new Random(1234 + pass * 7919L);
            int held = 0;
            for (int f = 0; f < frames; f++) {
                // a short press every ~third of a second; first pass favours "start/confirm" style buttons
                if (f % 20 == 0) held = 1 << rnd.nextInt(8);
                m.input = (f % 20) < 3 ? held : 0;
                if (!m.frame()) break;
                cap.frames++; done++;
                if ((f & 3) == 0) {
                    snapshotLayers(m, layers);
                    snapshotSprites(m, spriteCells, spritePal);
                }
                if (progress != null && (done & 63) == 0 && !progress.update(done, total)) { pass = passes; break; }
            }
        }
        int n = 0;
        for (Layer l : layers.values()) {
            l.name = String.format(Locale.ROOT, "layer_%03d_bk%d_seg%03x_%dbpp", n++, l.which + 1, l.segment, l.bpp);
            cap.layers.add(l);
        }
        for (Map.Entry<String, int[]> e : spriteCells.entrySet()) {
            String[] k = e.getKey().split(",");
            int seg = Integer.parseInt(k[0]), size = Integer.parseInt(k[1]);
            int[] cells = e.getValue();
            List<Integer> usedRows = new ArrayList<>();
            for (int r = 0; r < 256; r++) for (int c = 0; c < 16; c++) if (cells[r * 16 + c] >= 0) { usedRows.add(r); break; }
            if (usedRows.isEmpty()) continue;
            SpriteSheet s = new SpriteSheet();
            s.segment = seg; s.size = size; s.rows = usedRows.size();
            s.tiles = new int[s.rows * 16]; s.palRow = new int[s.rows * 16];
            for (int i = 0; i < s.rows; i++) for (int c = 0; c < 16; c++) {
                int t = usedRows.get(i) * 16 + c;
                s.tiles[i * 16 + c] = cells[t] >= 0 ? t : -1;
                s.palRow[i * 16 + c] = Math.max(0, cells[t]);
            }
            s.palette = spritePal.get(e.getKey());
            s.name = String.format(Locale.ROOT, "sprites_seg%03x_%dx%d_%s", seg, size, size, k[2]);
            cap.sprites.add(s);
        }
        return cap;
    }

    private static void snapshotLayers(Machine m, Map<String, Layer> out) {
        int sel = m.reg(0x200f);
        for (int which = 0; which < 2; which++) {
            int ctl = m.reg(which == 0 ? 0x2013 : 0x2017);
            if ((ctl & 0x80) == 0 || (ctl & 0x02) != 0) continue; // disabled or line mode
            int scroll = m.reg(which == 0 ? 0x2012 : 0x2016);
            int seg = which == 0 ? m.reg(0x201c) | (m.reg(0x201d) & 15) << 8 : m.reg(0x201e) | (m.reg(0x201f) & 15) << 8;
            int bppCode = (ctl >> 2) & 3;
            int bpp = bppCode == 3 ? 8 : bppCode == 2 ? 6 : 4;
            boolean big = (ctl & 1) != 0;
            int psel = which == 0 ? sel & 3 : (sel >> 2) & 3;
            int palBase = (psel & 1) != 0 ? 0x1e00 : 0x1c00;
            int hs = (scroll >> 2) & 1, vs = (scroll >> 3) & 1, y8x8 = ((scroll >> 1) & 1) << 1 | (scroll & 1);
            int[] pages = pageBases(big, vs, hs, y8x8, which);
            int pageTiles = big ? 16 : 32;
            int cols = pageTiles * (hs + 1), rows = pageTiles * (vs + 1);
            int[] words = new int[cols * rows];
            boolean any = false;
            for (int py = 0; py <= vs; py++) for (int px = 0; px <= hs; px++) {
                int base = pages[py * 2 + px];
                for (int y = 0; y < pageTiles; y++) for (int x = 0; x < pageTiles; x++) {
                    int a = base + (y * pageTiles + x) * 2;
                    int w = m.vram[a & 0xffff] & 0xff | (m.vram[(a + 1) & 0xffff] & 0xff) << 8;
                    words[(py * pageTiles + y) * cols + px * pageTiles + x] = w;
                    if ((w & 0xfff) != 0) any = true;
                }
            }
            if (!any) continue;
            int[] pal = palette(m, palBase);
            StringBuilder key = new StringBuilder();
            key.append(which).append(',').append(seg).append(',').append(bpp).append(',').append(big).append(',').append(cols).append(',');
            key.append(Arrays.hashCode(words));
            String k = key.toString();
            Layer old = out.get(k);
            if (old != null) {
                // fades reuse the same layer with darker colours: keep the brightest palette
                if (brightness(pal) > brightness(old.palette)) old.palette = pal;
                continue;
            }
            Layer l = new Layer();
            l.which = which; l.segment = seg; l.bpp = bpp; l.big = big; l.cols = cols; l.rows = rows;
            l.words = words; l.palette = pal;
            out.put(k, l);
        }
    }

    /** MAME setup_video_pages, reduced to the plane without scroll rotation. */
    static int[] pageBases(boolean big, int vs, int hs, int y8x8, int which) {
        int off = which == 0 ? 0 : 0x800;
        if (big) {
            int vh = vs << 1 | hs;
            if (vh == 0) return new int[]{off + y8x8 * 0x200, 0, 0, 0};
            if (vh == 1) return new int[]{off, off + 0x200, 0, 0};
            if (vh == 2) return new int[]{off, 0, off + 0x200, 0};
            return new int[]{off, off + 0x200, off + 0x400, off + 0x600};
        }
        int vh = vs << 1 | hs;
        if (vh == 0) return new int[]{y8x8 == 0 ? 0 : 0x800, 0, 0, 0};
        if (vh == 1) return new int[]{0, 0x800, 0, 0};
        if (vh == 2) return new int[]{0, 0, 0x800, 0};
        return new int[]{0, 0, 0, 0};
    }

    private static void snapshotSprites(Machine m, Map<String, int[]> cells, Map<String, int[]> pals) {
        int spregs = m.reg(0x2018);
        if ((spregs & 0x04) == 0) return; // sprites disabled
        int seg = m.reg(0x201a) | (m.reg(0x201b) & 15) << 8;
        int size = (spregs & 3) == 3 ? 16 : 8; // sheets only for square sizes
        if ((spregs & 3) == 1 || (spregs & 3) == 2) return;
        boolean both = (spregs & 0x08) != 0;
        for (int i = 0; i < 240; i++) {
            int o = i * 8;
            int tile = (m.spriteRam[o] & 0xff) | (m.spriteRam[o + 1] & 0x0f) << 8;
            if (tile == 0) continue;
            int pal = (m.spriteRam[o + 1] & 0xf0) >> 4;
            int attr2 = m.spriteRam[o + 5] & 0xff;
            int palBase = both || (attr2 & 2) == 0 ? 0x1e00 : 0x1c00;
            int[] p = palette(m, palBase);
            String key = seg + "," + size + "," + Integer.toHexString(Arrays.hashCode(p) & 0xffff);
            int[] c = cells.get(key);
            if (c == null) { c = new int[4096]; Arrays.fill(c, -1); cells.put(key, c); pals.put(key, p); }
            if (c[tile] < 0) c[tile] = pal;
        }
    }

    static long brightness(int[] pal) {
        long b = 0;
        for (int c : pal) b += ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255);
        return b;
    }

    static int[] palette(Machine m, int base) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            int w = m.vram[base + i * 2] & 0xff | (m.vram[base + i * 2 + 1] & 0xff) << 8;
            p[i] = rgb555(w);
        }
        return p;
    }

    static int rgb555(int w) {
        int r = (w >> 10) & 31, g = (w >> 5) & 31, b = w & 31;
        return 0xff000000 | (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
    }

    // ------------------------------------------------------------------ tile codec

    /** Pixel values (palette indices within the tile's range) of one tile, row-major. */
    public static int[] decodeTile(byte[] rom, long address, int bpp, int size) {
        int[] out = new int[size * size];
        int lineBytes = size * bpp / 8, groupBytes = bpp / 2, shift = bpp, mask = (1 << bpp) - 1;
        for (int y = 0; y < size; y++) {
            long p = address + (long) y * lineBytes;
            for (int x = 0; x < size; x += 4) {
                int v = 0;
                for (int i = 0; i < groupBytes; i++) v |= (rom[(int) ((p + i) % rom.length)] & 0xff) << (8 * i);
                p += groupBytes;
                for (int i = 0; i < 4; i++) out[y * size + x + i] = (v >> (shift * i)) & mask;
            }
        }
        return out;
    }

    public static byte[] encodeTile(int[] pixels, int bpp, int size) {
        int lineBytes = size * bpp / 8, groupBytes = bpp / 2, mask = (1 << bpp) - 1;
        byte[] out = new byte[lineBytes * size];
        int o = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x += 4) {
            int v = 0;
            for (int i = 0; i < 4; i++) v |= (pixels[y * size + x + i] & mask) << (bpp * i);
            for (int i = 0; i < groupBytes; i++) out[o++] = (byte) (v >> (8 * i));
        }
        return out;
    }

    /** First palette index a tile with this word uses, and how many colours it can use. */
    static int[] palRange(int bpp, int palRow) {
        if (bpp == 8) return new int[]{0, 256};
        if (bpp == 6) return new int[]{(palRow & 0xc) * 16, 64};
        return new int[]{palRow * 16, 16};
    }

    public static long tileAddress(int segment, int tile, int tileBytes) {
        return (long) segment * 0x2000 + (long) tile * tileBytes;
    }

    // ------------------------------------------------------------------ rendering

    public static int[] render(byte[] rom, Layer l) {
        int ts = l.tileSize(), w = l.width();
        int[] argb = new int[w * l.height()];
        for (int ty = 0; ty < l.rows; ty++) for (int tx = 0; tx < l.cols; tx++) {
            int word = l.words[ty * l.cols + tx], tile = word & 0xfff;
            if (tile == 0) continue;
            int[] range = palRange(l.bpp, word >> 12);
            int[] px = decodeTile(rom, tileAddress(l.segment, tile, l.tileBytes()), l.bpp, ts);
            for (int y = 0; y < ts; y++) for (int x = 0; x < ts; x++) {
                int v = px[y * ts + x];
                if (v != 0) argb[(ty * ts + y) * w + tx * ts + x] = l.palette[(range[0] + v) & 255];
            }
        }
        return argb;
    }

    public static int[] render(byte[] rom, SpriteSheet s) {
        int ts = s.size, w = s.width();
        int[] argb = new int[w * s.height()];
        for (int c = 0; c < s.tiles.length; c++) {
            if (s.tiles[c] < 0) continue;
            int tile = s.tiles[c], tx = c % s.cols, ty = c / s.cols;
            int[] px = decodeTile(rom, tileAddress(s.segment, tile, s.tileBytes()), 4, ts);
            for (int y = 0; y < ts; y++) for (int x = 0; x < ts; x++) {
                int v = px[y * ts + x];
                if (v != 0) argb[(ty * ts + y) * w + tx * ts + x] = s.palette[(s.palRow[c] * 16 + v) & 255];
            }
        }
        return argb;
    }

    // ------------------------------------------------------------------ map file (for re-import)

    public static String writeMap(Capture cap) {
        StringBuilder sb = new StringBuilder("# Game Asset Extractor VT1682 map v1\n");
        for (Layer l : cap.layers) {
            sb.append("layer ").append(l.name).append(' ').append(l.segment).append(' ').append(l.bpp).append(' ')
                    .append(l.big ? 16 : 8).append(' ').append(l.cols).append(' ').append(l.rows).append('\n');
            sb.append("words"); for (int w : l.words) sb.append(' ').append(Integer.toHexString(w)); sb.append('\n');
            sb.append("pal"); for (int c : l.palette) sb.append(' ').append(Integer.toHexString(c & 0xffffff)); sb.append('\n');
        }
        for (SpriteSheet s : cap.sprites) {
            sb.append("sprites ").append(s.name).append(' ').append(s.segment).append(' ').append(s.size).append(' ')
                    .append(s.cols).append(' ').append(s.rows).append('\n');
            sb.append("tiles"); for (int t : s.tiles) sb.append(' ').append(t); sb.append('\n');
            sb.append("rows"); for (int r : s.palRow) sb.append(' ').append(r); sb.append('\n');
            sb.append("pal"); for (int c : s.palette) sb.append(' ').append(Integer.toHexString(c & 0xffffff)); sb.append('\n');
        }
        return sb.toString();
    }

    public static Capture readMap(String text) throws IOException {
        Capture cap = new Capture();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] f = lines[i].trim().split(" ");
            try {
                if (f[0].equals("layer") && i + 2 < lines.length) {
                    Layer l = new Layer();
                    l.name = f[1]; l.segment = Integer.parseInt(f[2]); l.bpp = Integer.parseInt(f[3]);
                    l.big = Integer.parseInt(f[4]) == 16; l.cols = Integer.parseInt(f[5]); l.rows = Integer.parseInt(f[6]);
                    l.words = hexList(lines[++i], "words", l.cols * l.rows);
                    l.palette = colours(hexList(lines[++i], "pal", 256));
                    checkLayer(l);
                    cap.layers.add(l);
                } else if (f[0].equals("sprites") && i + 2 < lines.length) {
                    SpriteSheet s = new SpriteSheet();
                    s.name = f[1]; s.segment = Integer.parseInt(f[2]); s.size = Integer.parseInt(f[3]);
                    s.cols = Integer.parseInt(f[4]); s.rows = Integer.parseInt(f[5]);
                    if (s.cols != 16 || s.rows <= 0 || s.rows > 256) throw new IOException("bad sprite sheet");
                    s.tiles = intList(lines[++i], "tiles", s.cols * s.rows);
                    s.palRow = intList(lines[++i], "rows", s.cols * s.rows);
                    s.palette = colours(hexList(lines[++i], "pal", 256));
                    if ((s.size != 8 && s.size != 16) || s.segment < 0 || s.segment > 0xfff) throw new IOException("bad sprite sheet");
                    for (int k = 0; k < s.tiles.length; k++)
                        if (s.tiles[k] < -1 || s.tiles[k] > 4095 || s.palRow[k] < 0 || s.palRow[k] > 15) throw new IOException("bad sprite cell");
                    cap.sprites.add(s);
                }
            } catch (RuntimeException e) {
                throw new IOException("vt1682_map.txt line " + (i + 1) + " is damaged");
            }
        }
        if (cap.layers.isEmpty() && cap.sprites.isEmpty()) throw new IOException("vt1682_map.txt lists nothing");
        return cap;
    }

    private static void checkLayer(Layer l) throws IOException {
        if ((l.bpp != 4 && l.bpp != 6 && l.bpp != 8) || l.segment < 0 || l.segment > 0xfff
                || l.cols <= 0 || l.rows <= 0 || l.cols > 64 || l.rows > 64) throw new IOException("bad layer " + l.name);
    }

    private static int[] hexList(String line, String tag, int count) throws IOException {
        String[] f = line.trim().split(" ");
        if (!f[0].equals(tag) || f.length - 1 != count) throw new IOException("bad " + tag);
        int[] v = new int[count];
        for (int i = 0; i < count; i++) v[i] = Integer.parseInt(f[i + 1], 16);
        return v;
    }

    private static int[] intList(String line, String tag, int count) throws IOException {
        String[] f = line.trim().split(" ");
        if (!f[0].equals(tag) || f.length - 1 != count) throw new IOException("bad " + tag);
        int[] v = new int[count];
        for (int i = 0; i < count; i++) v[i] = Integer.parseInt(f[i + 1]);
        return v;
    }

    private static int[] colours(int[] rgb) { for (int i = 0; i < rgb.length; i++) rgb[i] |= 0xff000000; return rgb; }

    // ------------------------------------------------------------------ re-import

    public static final class Patch {
        public int tilesWritten, imagesChanged, conflicts, offPalette;
        public final List<String> notes = new ArrayList<>();
    }

    /**
     * Writes the tiles that differ between {@code edited} and the original render into {@code rom}.
     * A tile that several edited pictures share keeps the first edit (counted as a conflict).
     */
    public static int applyEdit(byte[] rom, Layer l, int[] edited, int w, int h, Patch patch, Map<Long, Integer> written) {
        if (w != l.width() || h != l.height()) throw new IllegalArgumentException(l.name + ".png must stay " + l.width() + "x" + l.height());
        int[] original = render(rom, l);
        int ts = l.tileSize(), changed = 0;
        for (int ty = 0; ty < l.rows; ty++) for (int tx = 0; tx < l.cols; tx++) {
            int word = l.words[ty * l.cols + tx], tile = word & 0xfff;
            if (tile == 0) continue;
            if (!cellChanged(original, edited, w, tx * ts, ty * ts, ts)) continue;
            int[] range = palRange(l.bpp, word >> 12);
            long addr = tileAddress(l.segment, tile, l.tileBytes());
            changed += writeTile(rom, addr, l.bpp, ts, original, edited, w, tx * ts, ty * ts, l.palette, range, patch,
                    written, l.name.hashCode() * 31 + ty * l.cols + tx);
        }
        if (changed > 0) patch.imagesChanged++;
        return changed;
    }

    public static int applyEdit(byte[] rom, SpriteSheet s, int[] edited, int w, int h, Patch patch, Map<Long, Integer> written) {
        if (w != s.width() || h != s.height()) throw new IllegalArgumentException(s.name + ".png must stay " + s.width() + "x" + s.height());
        int[] original = render(rom, s);
        int ts = s.size, changed = 0;
        for (int c = 0; c < s.tiles.length; c++) {
            if (s.tiles[c] < 0) continue;
            int tx = c % s.cols, ty = c / s.cols;
            if (!cellChanged(original, edited, w, tx * ts, ty * ts, ts)) continue;
            long addr = tileAddress(s.segment, s.tiles[c], s.tileBytes());
            changed += writeTile(rom, addr, 4, ts, original, edited, w, tx * ts, ty * ts, s.palette,
                    new int[]{s.palRow[c] * 16, 16}, patch, written, s.name.hashCode() * 31 + c);
        }
        if (changed > 0) patch.imagesChanged++;
        return changed;
    }

    private static boolean cellChanged(int[] a, int[] b, int w, int x0, int y0, int ts) {
        for (int y = 0; y < ts; y++) for (int x = 0; x < ts; x++) {
            int i = (y0 + y) * w + x0 + x;
            if (norm(a[i]) != norm(b[i])) return true;
        }
        return false;
    }

    private static int norm(int c) { return (c >>> 24) < 128 ? 0 : (c | 0xff000000); }

    private static int writeTile(byte[] rom, long addr, int bpp, int ts, int[] original, int[] edited, int w, int x0, int y0,
                                 int[] palette, int[] range, Patch patch, Map<Long, Integer> written, int owner) {
        if (addr < 0 || addr + tileBytes(bpp, ts) > rom.length) return 0;
        Integer prev = written.get(addr);
        if (prev != null && prev != owner) { patch.conflicts++; return 0; }
        int[] px = decodeTile(rom, addr, bpp, ts);
        for (int y = 0; y < ts; y++) for (int x = 0; x < ts; x++) {
            int i = (y0 + y) * w + x0 + x;
            int e = norm(edited[i]);
            if (e == norm(original[i])) continue; // keep the original index
            if (e == 0) { px[y * ts + x] = 0; continue; }
            int best = 1, bestD = Integer.MAX_VALUE;
            for (int v = 1; v < range[1]; v++) {
                int d = dist(e, palette[(range[0] + v) & 255]);
                if (d < bestD) { bestD = d; best = v; }
            }
            if (bestD > 0) patch.offPalette++;
            px[y * ts + x] = best;
        }
        byte[] enc = encodeTile(px, bpp, ts);
        System.arraycopy(enc, 0, rom, (int) addr, enc.length);
        written.put(addr, owner);
        patch.tilesWritten++;
        return 1;
    }

    private static int dist(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db;
    }

    // ------------------------------------------------------------------ the machine

    /** VT1682 main CPU (6502) with banking, DMA, video RAM and sprite RAM. Sound CPU is not run. */
    static final class Machine {
        final byte[] rom; final int romMask;
        final byte[] ram = new byte[0x2000];
        final int[] regs = new int[0x200];
        final byte[] vram = new byte[0x10000];
        final byte[] spriteRam = new byte[0x800];
        int a, x, y, sp = 0xfd, p = 0x24, pc;
        long cycles; int line;
        int input;
        int b1r0, b1r1, b0r4, b0r5;
        // sound-CPU handshake: a tight loop polling shared RAM gets its flag released
        int spinPc = -1, spinAddr = -1, spinCount;
        boolean dead;

        Machine(byte[] rom) {
            this.rom = rom;
            int n = Integer.highestOneBit(rom.length);
            romMask = n - 1;
            pc = r16(0xfffc);
        }

        int reg(int a) { return regs[a - 0x2000]; }

        int prg0sel(int t) {
            int b = reg(0x210b) & 7, r3 = reg(0x210a);
            switch (b) {
                case 0: return (r3 & 0xc0) | (t & 0x3f);
                case 1: return (r3 & 0xe0) | (t & 0x1f);
                case 2: return (r3 & 0xf0) | (t & 0x0f);
                case 3: return (r3 & 0xf8) | (t & 0x07);
                case 4: return (r3 & 0xfc) | (t & 0x03);
                case 5: return (r3 & 0xfe) | (t & 0x01);
                case 6: return r3;
                default: return t & 0xff;
            }
        }

        int tr8(int addr) {
            int pq2 = (reg(0x210b) >> 6) & 1, com = (reg(0x2105) >> 6) & 1, s = (addr >> 13) & 3;
            if (com != 0 && (s == 0 || s == 2)) s = 2 - s;
            int t, h; int r3 = reg(0x2100);
            if (s == 0) { t = reg(0x2107); h = b1r0; }
            else if (s == 1) { t = reg(0x2108); h = b1r1; }
            else if (s == 2) { if (pq2 != 0) { t = reg(0x2109); h = reg(0x210c); } else { t = 0xfe; h = r3; } }
            else { t = 0xff; h = r3; }
            if ((reg(0x211c) & 0x20) != 0) h = r3;
            return ((addr & 0x1fff) | prg0sel(t) << 13 | (h & 15) << 21) & romMask;
        }

        int tr4(int addr) {
            int r45 = reg(0x2118), t, h;
            if ((addr & 0x2000) == 0) { t = b0r4; h = r45 & 15; } else { t = b0r5; h = r45 >> 4; }
            return ((addr & 0x1fff) | prg0sel(t) << 13 | h << 21) & romMask;
        }

        int vaddr() { return regs[5] | regs[6] << 8; }
        void setV(int v) { regs[5] = v & 0xff; regs[6] = (v >> 8) & 0xff; }
        int spAddr() { return (regs[2] & 7) | regs[3] << 3; }
        void setSp(int v) { regs[2] = v & 7; regs[3] = (v >> 3) & 0xff; }
        void spWrite(int v) {
            int a = spAddr();
            spriteRam[a & 0x7ff] = (byte) v;
            a++;
            if ((a & 7) >= 6) { a += 8; a &= ~7; }
            setSp(a);
        }

        int rd(int a) {
            if (a < 0x2000) {
                if (a >= 0x1000) {
                    if (a == spinAddr && pc == spinPc) {
                        if (++spinCount > 400) { ram[a] = (byte) ((ram[a] & 0xff) != 0 ? 0 : 0xff); spinCount = 0; }
                    } else { spinAddr = a; spinPc = pc; spinCount = 0; }
                }
                return ram[a] & 0xff;
            }
            if (a < 0x2200) {
                switch (a) {
                    case 0x2001: return line > 239 ? 0x80 : 0;
                    case 0x2007: { int v = vram[vaddr()] & 0xff; setV(vaddr() + 1); return v; }
                    case 0x2127: return 0;
                    case 0x2129: case 0x212a: case 0x2149: return (~input) & 0xff;
                    default: return regs[a - 0x2000];
                }
            }
            if (a < 0x4000) return 0;
            if (a < 0x8000) return rom[tr4(a)] & 0xff;
            return rom[tr8(a)] & 0xff;
        }

        void wr(int a, int v) {
            if (a < 0x2000) { ram[a] = (byte) v; return; }
            if (a >= 0x2200) return;
            regs[a - 0x2000] = v;
            switch (a) {
                case 0x2004: spWrite(v); break;
                case 0x2007: { int va = vaddr(); vram[va] = (byte) v; setV(va + 1); break; }
                case 0x2110: b1r0 = v & 15; break;
                case 0x2111: b1r1 = v & 15; break;
                case 0x2112: b0r4 = v; break;
                case 0x2113: b0r5 = v; break;
                case 0x2127: dma(v); break;
                default: break;
            }
        }

        void dma(int v) {
            int count = v * 2; if (count == 0) count = 0x200;
            int srcReg = regs[0x124] | regs[0x125] << 8;
            boolean ext = (srcReg & 0x8000) != 0; int src = srcReg & 0x7fff;
            int bank = (regs[0x126] | regs[0x128] << 8) & 0x3ff;
            int dst = (regs[0x122] | regs[0x123] << 8) & 0x7fff;
            boolean video = dst == 0x2004 || dst == 0x2007;
            for (int i = 0; i < count; i++) {
                int b = ext ? rom[(src | bank << 15) & romMask] & 0xff : rd(src);
                src = (src + 1) & 0x7fff;
                if (dst == 0x2007) { int va = vaddr(); vram[va] = (byte) b; setV(va + 1); }
                else if (dst == 0x2004) spWrite(b);
                else { wr(dst, b); dst = (dst + 1) & 0x7fff; }
            }
            regs[0x124] = src & 0xff; regs[0x125] = (regs[0x125] & 0x80) | (src >> 8);
            if (!video) { regs[0x122] = dst & 0xff; regs[0x123] = (regs[0x123] & 0x80) | (dst >> 8); }
        }

        int r16(int a) { return rd(a) | rd((a + 1) & 0xffff) << 8; }
        void push(int v) { ram[0x100 + sp] = (byte) v; sp = (sp - 1) & 0xff; }
        int pop() { sp = (sp + 1) & 0xff; return ram[0x100 + sp] & 0xff; }
        int nz(int v) { p = (p & 0x7d) | (v & 0x80) | (v == 0 ? 2 : 0); return v; }
        void interrupt(int vector, boolean brk) {
            push(pc >> 8); push(pc & 0xff); push((p | 0x20 | (brk ? 0x10 : 0)) & 0xff);
            p |= 4; pc = r16(vector);
        }

        /** Runs one frame (262 lines, NMI at line 240 when enabled). False if the CPU hit an invalid opcode. */
        boolean frame() {
            for (line = 0; line < 262; line++) {
                if (line == 240 && (regs[0] & 1) != 0) interrupt(0xfffa, false);
                long target = cycles + 341;
                while (cycles < target) { if (!step()) { dead = true; return false; } }
            }
            return true;
        }

        private int imm() { int v = rd((pc + 1) & 0xffff); pc = (pc + 2) & 0xffff; return v; }
        private int zp() { int v = rd((pc + 1) & 0xffff); pc = (pc + 2) & 0xffff; return v; }
        private int zpx() { return (zp() + x) & 0xff; }
        private int zpy() { return (zp() + y) & 0xff; }
        private int abs() { int v = r16((pc + 1) & 0xffff); pc = (pc + 3) & 0xffff; return v; }
        private int abx() { return (abs() + x) & 0xffff; }
        private int aby() { return (abs() + y) & 0xffff; }
        private int izx() { int z = (zp() + x) & 0xff; return (ram[z] & 0xff) | (ram[(z + 1) & 0xff] & 0xff) << 8; }
        private int izy() { int z = zp(); return (((ram[z] & 0xff) | (ram[(z + 1) & 0xff] & 0xff) << 8) + y) & 0xffff; }

        private void adc(int v) {
            int c = p & 1, r = a + v + c;
            boolean ov = ((~(a ^ v)) & (a ^ r) & 0x80) != 0;
            p = (p & ~0x41) | (r > 0xff ? 1 : 0) | (ov ? 0x40 : 0);
            a = nz(r & 0xff);
        }
        private void cmp(int r, int v) { p = (p & ~1) | (r >= v ? 1 : 0); nz((r - v) & 0xff); }
        private int asl(int v) { p = (p & ~1) | (v >> 7); return nz((v << 1) & 0xff); }
        private int lsr(int v) { p = (p & ~1) | (v & 1); return nz(v >> 1); }
        private int rol(int v) { int c = p & 1; p = (p & ~1) | (v >> 7); return nz(((v << 1) | c) & 0xff); }
        private int ror(int v) { int c = p & 1; p = (p & ~1) | (v & 1); return nz((v >> 1) | (c << 7)); }
        private void branch(boolean cond) {
            int off = (byte) rd((pc + 1) & 0xffff);
            pc = (pc + 2) & 0xffff;
            if (cond) { pc = (pc + off) & 0xffff; cycles++; }
        }
        private void bit(int v) { p = (p & 0x3d) | (v & 0xc0) | ((a & v) == 0 ? 2 : 0); }

        boolean step() {
            int op = rd(pc), t;
            cycles += 3;
            switch (op) {
                // loads
                case 0xa9: a = nz(imm()); break; case 0xa5: a = nz(rd(zp())); break; case 0xb5: a = nz(rd(zpx())); break;
                case 0xad: a = nz(rd(abs())); break; case 0xbd: a = nz(rd(abx())); break; case 0xb9: a = nz(rd(aby())); break;
                case 0xa1: a = nz(rd(izx())); break; case 0xb1: a = nz(rd(izy())); break;
                case 0xa2: x = nz(imm()); break; case 0xa6: x = nz(rd(zp())); break; case 0xb6: x = nz(rd(zpy())); break;
                case 0xae: x = nz(rd(abs())); break; case 0xbe: x = nz(rd(aby())); break;
                case 0xa0: y = nz(imm()); break; case 0xa4: y = nz(rd(zp())); break; case 0xb4: y = nz(rd(zpx())); break;
                case 0xac: y = nz(rd(abs())); break; case 0xbc: y = nz(rd(abx())); break;
                // stores
                case 0x85: wr(zp(), a); break; case 0x95: wr(zpx(), a); break; case 0x8d: wr(abs(), a); break;
                case 0x9d: wr(abx(), a); break; case 0x99: wr(aby(), a); break; case 0x81: wr(izx(), a); break; case 0x91: wr(izy(), a); break;
                case 0x86: wr(zp(), x); break; case 0x96: wr(zpy(), x); break; case 0x8e: wr(abs(), x); break;
                case 0x84: wr(zp(), y); break; case 0x94: wr(zpx(), y); break; case 0x8c: wr(abs(), y); break;
                // arithmetic / logic
                case 0x69: adc(imm()); break; case 0x65: adc(rd(zp())); break; case 0x75: adc(rd(zpx())); break; case 0x6d: adc(rd(abs())); break;
                case 0x7d: adc(rd(abx())); break; case 0x79: adc(rd(aby())); break; case 0x61: adc(rd(izx())); break; case 0x71: adc(rd(izy())); break;
                case 0xe9: adc(imm() ^ 0xff); break; case 0xe5: adc(rd(zp()) ^ 0xff); break; case 0xf5: adc(rd(zpx()) ^ 0xff); break; case 0xed: adc(rd(abs()) ^ 0xff); break;
                case 0xfd: adc(rd(abx()) ^ 0xff); break; case 0xf9: adc(rd(aby()) ^ 0xff); break; case 0xe1: adc(rd(izx()) ^ 0xff); break; case 0xf1: adc(rd(izy()) ^ 0xff); break;
                case 0x29: a = nz(a & imm()); break; case 0x25: a = nz(a & rd(zp())); break; case 0x35: a = nz(a & rd(zpx())); break; case 0x2d: a = nz(a & rd(abs())); break;
                case 0x3d: a = nz(a & rd(abx())); break; case 0x39: a = nz(a & rd(aby())); break; case 0x21: a = nz(a & rd(izx())); break; case 0x31: a = nz(a & rd(izy())); break;
                case 0x09: a = nz(a | imm()); break; case 0x05: a = nz(a | rd(zp())); break; case 0x15: a = nz(a | rd(zpx())); break; case 0x0d: a = nz(a | rd(abs())); break;
                case 0x1d: a = nz(a | rd(abx())); break; case 0x19: a = nz(a | rd(aby())); break; case 0x01: a = nz(a | rd(izx())); break; case 0x11: a = nz(a | rd(izy())); break;
                case 0x49: a = nz(a ^ imm()); break; case 0x45: a = nz(a ^ rd(zp())); break; case 0x55: a = nz(a ^ rd(zpx())); break; case 0x4d: a = nz(a ^ rd(abs())); break;
                case 0x5d: a = nz(a ^ rd(abx())); break; case 0x59: a = nz(a ^ rd(aby())); break; case 0x41: a = nz(a ^ rd(izx())); break; case 0x51: a = nz(a ^ rd(izy())); break;
                case 0xc9: cmp(a, imm()); break; case 0xc5: cmp(a, rd(zp())); break; case 0xd5: cmp(a, rd(zpx())); break; case 0xcd: cmp(a, rd(abs())); break;
                case 0xdd: cmp(a, rd(abx())); break; case 0xd9: cmp(a, rd(aby())); break; case 0xc1: cmp(a, rd(izx())); break; case 0xd1: cmp(a, rd(izy())); break;
                case 0xe0: cmp(x, imm()); break; case 0xe4: cmp(x, rd(zp())); break; case 0xec: cmp(x, rd(abs())); break;
                case 0xc0: cmp(y, imm()); break; case 0xc4: cmp(y, rd(zp())); break; case 0xcc: cmp(y, rd(abs())); break;
                case 0x24: bit(rd(zp())); break; case 0x2c: bit(rd(abs())); break;
                // shifts
                case 0x0a: a = asl(a); pc = (pc + 1) & 0xffff; break; case 0x4a: a = lsr(a); pc = (pc + 1) & 0xffff; break;
                case 0x2a: a = rol(a); pc = (pc + 1) & 0xffff; break; case 0x6a: a = ror(a); pc = (pc + 1) & 0xffff; break;
                case 0x06: t = zp(); wr(t, asl(rd(t))); break; case 0x16: t = zpx(); wr(t, asl(rd(t))); break;
                case 0x0e: t = abs(); wr(t, asl(rd(t))); break; case 0x1e: t = abx(); wr(t, asl(rd(t))); break;
                case 0x46: t = zp(); wr(t, lsr(rd(t))); break; case 0x56: t = zpx(); wr(t, lsr(rd(t))); break;
                case 0x4e: t = abs(); wr(t, lsr(rd(t))); break; case 0x5e: t = abx(); wr(t, lsr(rd(t))); break;
                case 0x26: t = zp(); wr(t, rol(rd(t))); break; case 0x36: t = zpx(); wr(t, rol(rd(t))); break;
                case 0x2e: t = abs(); wr(t, rol(rd(t))); break; case 0x3e: t = abx(); wr(t, rol(rd(t))); break;
                case 0x66: t = zp(); wr(t, ror(rd(t))); break; case 0x76: t = zpx(); wr(t, ror(rd(t))); break;
                case 0x6e: t = abs(); wr(t, ror(rd(t))); break; case 0x7e: t = abx(); wr(t, ror(rd(t))); break;
                case 0xe6: t = zp(); wr(t, nz((rd(t) + 1) & 0xff)); break; case 0xf6: t = zpx(); wr(t, nz((rd(t) + 1) & 0xff)); break;
                case 0xee: t = abs(); wr(t, nz((rd(t) + 1) & 0xff)); break; case 0xfe: t = abx(); wr(t, nz((rd(t) + 1) & 0xff)); break;
                case 0xc6: t = zp(); wr(t, nz((rd(t) - 1) & 0xff)); break; case 0xd6: t = zpx(); wr(t, nz((rd(t) - 1) & 0xff)); break;
                case 0xce: t = abs(); wr(t, nz((rd(t) - 1) & 0xff)); break; case 0xde: t = abx(); wr(t, nz((rd(t) - 1) & 0xff)); break;
                // register ops
                case 0xe8: x = nz((x + 1) & 0xff); pc++; break; case 0xc8: y = nz((y + 1) & 0xff); pc++; break;
                case 0xca: x = nz((x - 1) & 0xff); pc++; break; case 0x88: y = nz((y - 1) & 0xff); pc++; break;
                case 0xaa: x = nz(a); pc++; break; case 0xa8: y = nz(a); pc++; break; case 0x8a: a = nz(x); pc++; break;
                case 0x98: a = nz(y); pc++; break; case 0xba: x = nz(sp); pc++; break; case 0x9a: sp = x; pc++; break;
                case 0x48: push(a); pc++; break; case 0x08: push(p | 0x30); pc++; break;
                case 0x68: a = nz(pop()); pc++; break; case 0x28: p = (pop() & 0xef) | 0x20; pc++; break;
                // flow
                case 0x10: branch((p & 0x80) == 0); break; case 0x30: branch((p & 0x80) != 0); break;
                case 0x50: branch((p & 0x40) == 0); break; case 0x70: branch((p & 0x40) != 0); break;
                case 0x90: branch((p & 1) == 0); break; case 0xb0: branch((p & 1) != 0); break;
                case 0xd0: branch((p & 2) == 0); break; case 0xf0: branch((p & 2) != 0); break;
                case 0x4c: pc = r16((pc + 1) & 0xffff); break;
                case 0x6c: { int ad = r16((pc + 1) & 0xffff); pc = rd(ad) | rd((ad & 0xff00) | ((ad + 1) & 0xff)) << 8; break; }
                case 0x20: { int target = r16((pc + 1) & 0xffff); int ret = (pc + 2) & 0xffff; push(ret >> 8); push(ret & 0xff); pc = target; break; }
                case 0x60: pc = ((pop() | pop() << 8) + 1) & 0xffff; break;
                case 0x40: p = (pop() & 0xef) | 0x20; pc = pop() | pop() << 8; break;
                case 0x00: pc = (pc + 2) & 0xffff; interrupt(0xfffe, true); break;
                case 0x18: p &= ~1; pc++; break; case 0x38: p |= 1; pc++; break;
                case 0x58: p &= ~4; pc++; break; case 0x78: p |= 4; pc++; break;
                case 0xb8: p &= ~0x40; pc++; break; case 0xd8: p &= ~8; pc++; break; case 0xf8: p |= 8; pc++; break;
                case 0xea: pc++; break;
                default: return false;
            }
            pc &= 0xffff;
            return true;
        }
    }
}
