package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Metal Gear Solid (PS1) graphics archives: STAGE.DIR (every area's textures, models and
 * scripts) and FACE.DAT (the codec portraits). Worked out from retail files and checked
 * against the open-source Codec asset browser (otac0n/Codec, MIT).
 *
 * STAGE.DIR, little-endian, 0x800-byte sectors:
 *   u32 directory size, then 12-byte entries: char[8] stage name, u32 first sector.
 *   A stage starts with u16, u16, then 8-byte file entries until a zero one:
 *     u16 name hash, u8 group (c cache, n nocache, r resident, s sound), u8 type, u32 size.
 *   File data starts one sector into the stage, each file sector-aligned. A type 0xFF
 *   entry closes a "packed" run of the same group: there the sizes above it are offsets
 *   into the run and the 0xFF entry holds the run's total size; packed files are not aligned.
 *   Type 'd' (DAR) is itself an archive: repeated u16 hash, u8 type, u8 pad, u32 size, data.
 * Textures (type 'p') are ordinary PCX files: version 5, RLE, either 16 colours (4 bit
 * planes, palette in the header) or 256 colours (palette after the pixel data). MGS pads
 * every file to 4 bytes, so the 256-colour palette is NOT at the very end of the file the
 * way the PCX spec puts it; it follows the pixel data directly.
 *
 * FACE.DAT: groups, each sector-aligned: u32 count, then count × (u16 animation flag,
 * u16 id, u32 size, u32 offset from the end of the count). A portrait is: u32 palette
 * offset, u32 × 6 image offsets (base, eyes droop, eyes blink, extra, mouth E, mouth A; 0 =
 * none), a palette of 256 PS1 colours (15 bit, 0 = transparent) and 8-bit images of
 * s8 x, s8 y, s8 w, s8 h then w × h indices. The non-base images are pieces drawn over the
 * base at (x, y). An animation is u32 frames, then frames × (u32 palette offset, u32 image
 * offset, u32 unknown); its frames are whole images.
 */
public final class MgsStage {
    private MgsStage() {}

    private static final int SECTOR = 0x800;
    private static final int MAX_DIR = 12 * 4096;
    private static final int MAX_PCX_PIXELS = 1024 * 1024;

    public static final class Image {
        public final int width, height;
        public final int[] argb;
        Image(int width, int height, int[] argb) { this.width = width; this.height = height; this.argb = argb; }
    }

    /** One file inside STAGE.DIR, or one portrait / animation inside FACE.DAT. */
    public static final class Entry {
        public final String folder;   // stage name, or FACE.DAT group number
        public final char group;      // STAGE.DIR: c, n, r or s; FACE.DAT: 'f' face, 'a' animation
        public final int id;          // 16-bit name hash
        public final int type;        // STAGE.DIR file type letter
        public final long offset;
        public final int size;
        int darPad;   // the padding byte of its DAR header, kept when rebuilding
        Entry(String folder, char group, int id, int type, long offset, int size) {
            this.folder = folder; this.group = group; this.id = id; this.type = type; this.offset = offset; this.size = size;
        }
        public boolean isModel() { return type == 'k'; }
        /** Unique within a file: used to remember edits. */
        public String key() { return folder + "|" + offset; }
        public boolean isTexture() { return type == 'p'; }
        public String extension() { return extensionOf(type); }
        /** stage/group/hash.ext, as the open-source tools name them. */
        public String path() {
            return folder + "/" + groupName(group) + "/" + String.format(Locale.ROOT, "%04x", id) + "." + extension();
        }
    }

    /** A stage (STAGE.DIR) or a portrait group (FACE.DAT): one row in the app's list. */
    public static final class Part {
        public final String name;
        public final List<Entry> entries = new ArrayList<>();
        // STAGE.DIR area layout, for rebuilding
        long base, end;
        byte[] header;                                    // the 4 bytes before the file table
        final List<Slot> slots = new ArrayList<>();       // files in table order
        final List<int[]> table = new ArrayList<>();      // {slot index} or {-1, group, run start slot, run end slot}
        Part(String name) { this.name = name; }
        public int textures() { int n = 0; for (Entry e : entries) if (e.isTexture()) n++; return n; }
        public int models() { int n = 0; for (Entry e : entries) if (e.isModel()) n++; return n; }
    }

    /** One file in an area's table: a plain file, or a DAR archive of several. */
    static final class Slot {
        int id, group, type;
        long size;
        boolean packed;              // not sector-aligned after it (inside a packed run, except the last)
        int runStart = -1;           // >= 0: index of the first slot of its packed run
        Entry entry;                 // plain file
        List<Entry> children;        // DAR
    }

    public static final class Info {
        public final boolean face;
        public final List<Part> parts;
        public final int files, textures;
        Info(boolean face, List<Part> parts) {
            this.face = face; this.parts = parts;
            int f = 0, t = 0;
            for (Part p : parts) { f += p.entries.size(); t += face ? p.entries.size() : p.textures(); }
            files = f; textures = t;
        }
        public String description() {
            return face ? "Metal Gear Solid codec portraits (FACE.DAT) · " + parts.size() + " groups · " + files + " portraits and animations"
                    : "Metal Gear Solid stage archive (STAGE.DIR) · " + parts.size() + " areas · " + textures + " textures · " +
                    (files - textures) + " other files";
        }
        public String label(int index) {
            Part p = parts.get(index);
            if (face) {
                int anims = 0;
                for (Entry e : p.entries) if (e.group == 'a') anims++;
                return "🙂 Group " + p.name + " · " + (p.entries.size() - anims) + " portraits" + (anims > 0 ? " · " + anims + " animations" : "");
            }
            int t = p.textures(), m = p.models();
            return "🗺 " + p.name + " · " + t + " textures · " + m + " models · " + (p.entries.size() - t - m) + " other files";
        }
    }

    public static String extensionOf(int type) {
        switch (type) {
            case 'a': return "azm";
            case 'b': return "bin";
            case 'c': return "con";
            case 'd': return "dar";
            case 'e': return "efx";
            case 'g': return "gcx";
            case 'h': return "hzm";
            case 'i': return "img";
            case 'k': return "kmd";
            case 'l': return "lit";
            case 'm': return "mdx";
            case 'n': return "nar";
            case 'o': return "oar";
            case 'p': return "pcx";
            case 'r': return "res";
            case 's': return "sgt";
            case 'w': return "wvx";
            case 'z': return "zmd";
            default: return String.format(Locale.ROOT, "x%02x", type & 0xff);
        }
    }

    static String groupName(char group) {
        switch (group) {
            case 'c': return "cache";
            case 'n': return "nocache";
            case 'r': return "resident";
            case 's': return "sound";
            case 'f': return "faces";
            case 'a': return "animations";
            default: return "other";
        }
    }

    // ---------------------------------------------------------------- reading helpers

    static byte[] read(FileChannel ch, long offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > ch.size()) throw new IOException("Read outside the file");
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (ch.read(buffer, offset + buffer.position()) < 0) throw new IOException("Unexpected end of file");
        }
        return buffer.array();
    }

    public static byte[] read(FileChannel ch, Entry e) throws IOException { return read(ch, e.offset, e.size); }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    // ---------------------------------------------------------------- STAGE.DIR

    private static final class Folder { String name; long sector; }

    private static List<Folder> readDirectory(FileChannel ch) throws IOException {
        long size = ch.size();
        if (size < SECTOR * 2L || size % SECTOR != 0) return null;
        byte[] head = read(ch, 0, 4);
        long dirSize = u32(head, 0);
        if (dirSize < 12 || dirSize > MAX_DIR || dirSize % 12 != 0 || dirSize + 4 > SECTOR) return null;
        byte[] dir = read(ch, 4, (int) dirSize);
        List<Folder> folders = new ArrayList<>();
        long last = 0;
        for (int o = 0; o < dir.length; o += 12) {
            StringBuilder name = new StringBuilder();
            boolean ended = false;
            for (int i = 0; i < 8; i++) {
                int c = dir[o + i] & 0xff;
                if (c == 0) { ended = true; continue; }
                if (ended || !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) return null;
                name.append((char) c);
            }
            long sector = u32(dir, o + 8);
            if (name.length() == 0) continue;
            if (sector < 1 || sector <= last || sector * SECTOR >= size) return null;
            last = sector;
            Folder f = new Folder();
            f.name = name.toString();
            f.sector = sector;
            folders.add(f);
        }
        return folders.isEmpty() ? null : folders;
    }

    public static boolean isStageDir(FileChannel ch) {
        try {
            List<Folder> folders = readDirectory(ch);
            if (folders == null) return false;
            // The first area's file table has to make sense too
            readStage(ch, folders.get(0), folders.size() > 1 ? folders.get(1).sector * SECTOR : ch.size(), new Part(folders.get(0).name));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static Info parseStageDir(FileChannel ch) throws IOException {
        List<Folder> folders = readDirectory(ch);
        if (folders == null) throw new IOException("Not a Metal Gear Solid STAGE.DIR");
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < folders.size(); i++) {
            Folder f = folders.get(i);
            long end = i + 1 < folders.size() ? folders.get(i + 1).sector * SECTOR : ch.size();
            Part part = new Part(f.name);
            readStage(ch, f, end, part);
            parts.add(part);
        }
        return new Info(false, parts);
    }

    private static void readStage(FileChannel ch, Folder f, long end, Part part) throws IOException {
        long base = f.sector * SECTOR;
        part.base = base;
        part.end = end;
        byte[] table = read(ch, base, SECTOR);
        part.header = java.util.Arrays.copyOf(table, 4);
        List<Slot> raw = part.slots;
        int p = 4;
        while (true) {
            if (p + 8 > SECTOR) throw new IOException("Stage " + f.name + ": file table does not end");
            long name = u32(table, p);
            long size = u32(table, p + 4);
            p += 8;
            if (name == 0) break;
            int id = (int) (name & 0xffff), group = (int) (name >> 16 & 0xff), type = (int) (name >>> 24);
            if (type == 0xff) {
                // Closes a packed run: sizes above are offsets into it, this one is the total
                long total = size;
                boolean notLast = false;
                int first = raw.size();
                for (int i = raw.size() - 1; i >= 0; i--) {
                    Slot e = raw.get(i);
                    if (e.group != group) break;
                    long start = e.size;
                    if (start > total) throw new IOException("Stage " + f.name + ": bad packed run");
                    e.packed = notLast;
                    e.size = total - start;
                    total = start;
                    notLast = true;
                    first = i;
                }
                for (int i = first; i < raw.size(); i++) raw.get(i).runStart = first;
                part.table.add(new int[]{-1, group, first, raw.size()});
            } else {
                Slot slot = new Slot();
                slot.id = id; slot.group = group; slot.type = type; slot.size = size;
                part.table.add(new int[]{raw.size()});
                raw.add(slot);
            }
        }
        long rel = SECTOR;
        for (Slot e : raw) {
            long offset = base + rel, size = e.size;
            if (size < 0 || offset + size > end) throw new IOException("Stage " + f.name + ": a file runs past the area");
            if (e.type == 'd') {
                e.children = readDar(ch, f.name, (char) e.group, offset, size, part);
            } else {
                e.entry = new Entry(f.name, (char) e.group, e.id, e.type, offset, (int) size);
                part.entries.add(e.entry);
            }
            rel += size;
            if (!e.packed) rel = (rel + SECTOR - 1) / SECTOR * SECTOR;
        }
    }

    private static List<Entry> readDar(FileChannel ch, String folder, char group, long offset, long size, Part part) throws IOException {
        List<Entry> children = new ArrayList<>();
        long r = 0;
        while (r < size - 7) {
            byte[] h = read(ch, offset + r, 8);
            int id = u16(h, 0), type = h[2] & 0xff;
            long length = u32(h, 4);
            if (length > size - r - 8) throw new IOException("Archive in " + folder + " runs past its end");
            Entry e = new Entry(folder, group, id, type, offset + r + 8, (int) length);
            e.darPad = h[3] & 0xff;
            children.add(e);
            part.entries.add(e);
            r += 8 + length;
        }
        if (r != size) throw new IOException("Archive in " + folder + " has " + (size - r) + " stray bytes");
        return children;
    }

    // ---------------------------------------------------------------- PCX

    public static boolean isPcx(byte[] b) {
        return b.length >= 128 && (b[0] & 0xff) == 0x0a && (b[1] & 0xff) == 5 && (b[2] & 0xff) == 1;
    }

    /** A decoded MGS texture: colour indices, its palette (RGB) and what the game makes of it. */
    static final class Pcx {
        int width, height, bpl, colours;   // colours: how many palette entries the game loads
        boolean eightBit;
        int[] index, palette;              // palette: 0xRRGGBB
        int pixelEnd, paletteAt;           // byte positions in the file
    }

    static Pcx parsePcx(byte[] b) throws IOException {
        if (!isPcx(b)) throw new IOException("Not a PCX image");
        int bpp = b[3] & 0xff, planes = b[65] & 0xff, bpl = u16(b, 66);
        int width = u16(b, 8) - u16(b, 4) + 1, height = u16(b, 10) - u16(b, 6) + 1;
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PCX_PIXELS) throw new IOException("Bad PCX size");
        boolean paletted8 = bpp == 8 && planes == 1, planar4 = bpp == 1 && planes == 4;
        if (!paletted8 && !planar4) throw new IOException("Unsupported PCX layout (" + bpp + " bits, " + planes + " planes)");
        int minBpl = paletted8 ? width : (width + 7) / 8;
        if (bpl < minBpl || bpl > 4096) throw new IOException("Bad PCX line length");
        long need = (long) bpl * planes * height;
        if (need > 64L << 20) throw new IOException("PCX too large");
        byte[] raw = new byte[(int) need];
        int got = 0, p = 128;
        while (got < raw.length) {
            if (p >= b.length) throw new IOException("PCX pixel data is cut short");
            int c = b[p++] & 0xff;
            // As the game reads it (libdg/loader.c, PCX_RLE_CODE 0xC0): 0xC0 itself is a literal
            if (c > 0xc0) {
                if (p >= b.length) throw new IOException("PCX pixel data is cut short");
                int count = c & 0x3f, v = b[p++];
                for (int i = 0; i < count && got < raw.length; i++) raw[got++] = (byte) v;
            } else {
                raw[got++] = (byte) c;
            }
        }
        Pcx x = new Pcx();
        x.width = width; x.height = height; x.bpl = bpl; x.eightBit = paletted8; x.pixelEnd = p;
        x.index = new int[width * height];
        if (paletted8) {
            // MGS: the palette follows the pixel data (then 0-3 padding bytes), not the end of the file
            int palAt = -1;
            if (p + 769 <= b.length && (b[p] & 0xff) == 0x0c) palAt = p + 1;
            else if (b.length >= 769 && (b[b.length - 769] & 0xff) == 0x0c) palAt = b.length - 768;
            if (palAt < 0) throw new IOException("PCX has no 256-colour palette");
            x.paletteAt = palAt;
            x.palette = new int[256];
            for (int i = 0; i < 256; i++)
                x.palette[i] = (b[palAt + i * 3] & 0xff) << 16 | (b[palAt + i * 3 + 1] & 0xff) << 8 | (b[palAt + i * 3 + 2] & 0xff);
            for (int y = 0; y < height; y++)
                for (int i = 0; i < width; i++) x.index[y * width + i] = raw[y * bpl + i] & 0xff;
        } else {
            x.paletteAt = 16;
            x.palette = new int[16];
            for (int i = 0; i < 16; i++)
                x.palette[i] = (b[16 + i * 3] & 0xff) << 16 | (b[17 + i * 3] & 0xff) << 8 | (b[18 + i * 3] & 0xff);
            for (int y = 0; y < height; y++) {
                int row = y * planes * bpl;
                for (int i = 0; i < width; i++) {
                    int v = 0, bit = 7 - (i & 7);
                    for (int plane = 0; plane < 4; plane++)
                        v |= ((raw[row + plane * bpl + (i >> 3)] >> bit) & 1) << plane;
                    x.index[y * width + i] = v;
                }
            }
        }
        // MGS header extension ("90" magic at 0x4A): how many palette entries the game loads
        int max = x.palette.length;
        x.colours = u16(b, 0x4a) == 12345 ? Math.max(1, Math.min(max, u16(b, 0x56))) : max;
        return x;
    }

    /**
     * Colour of a palette entry as the game shows it: the game turns pure black (0,0,0) into the
     * PS1's "don't draw" colour (libdg/loader.c, LoadPalette), so it's see-through here too.
     */
    static int shown(int rgb) { return rgb == 0 ? 0 : 0xff000000 | rgb; }

    /** Decodes an MGS texture (PCX, 16 or 256 colours). Black is see-through, as in the game. */
    public static Image decodePcx(byte[] b) throws IOException {
        Pcx x = parsePcx(b);
        int[] argb = new int[x.index.length];
        for (int i = 0; i < argb.length; i++) argb[i] = shown(x.palette[x.index[i]]);
        return new Image(x.width, x.height, argb);
    }

    /**
     * Re-encodes an edited texture into the original's format: same size, same header (which
     * says where the game puts it in video memory) and same palette. Each pixel gets the
     * nearest of the colours the game loads; see-through pixels get a black entry. Pixels
     * left exactly as they were keep their original index, so untouched areas compress the same.
     */
    public static byte[] encodePcx(byte[] original, int[] argb, int width, int height) throws IOException {
        Pcx x = parsePcx(original);
        if (width != x.width || height != x.height)
            throw new IOException("The edited image is " + width + "×" + height + "; this texture must stay " + x.width + "×" + x.height);
        int clear = -1;
        for (int i = 0; i < x.colours; i++) if (x.palette[i] == 0) { clear = i; break; }
        int[] index = new int[x.index.length];
        Map<Integer, Integer> nearest = new java.util.HashMap<>();
        for (int i = 0; i < index.length; i++) {
            int c = argb[i], orig = x.index[i];
            if (orig < x.colours && shown(x.palette[orig]) == ((c >>> 24) < 128 ? 0 : (c | 0xff000000))) { index[i] = orig; continue; }
            if ((c >>> 24) < 128) {
                if (clear < 0) throw new IOException("This texture has no see-through colour, so it can't have transparent pixels");
                index[i] = clear;
                continue;
            }
            int rgb = c & 0xffffff;
            Integer hit = nearest.get(rgb);
            if (hit == null) {
                int best = -1;
                long bestD = Long.MAX_VALUE;
                for (int k = 0; k < x.colours; k++) {
                    int q = x.palette[k];
                    if (q == 0) continue;   // black would turn see-through
                    long dr = (q >> 16 & 255) - (rgb >> 16 & 255), dg = (q >> 8 & 255) - (rgb >> 8 & 255), db = (q & 255) - (rgb & 255);
                    long dist = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
                    if (dist < bestD) { bestD = dist; best = k; }
                }
                if (best < 0) throw new IOException("This texture's palette has no visible colours");
                hit = best;
                nearest.put(rgb, hit);
            }
            index[i] = hit;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(original.length + 256);
        out.write(original, 0, 128);
        int bpl = x.bpl;
        // Runs may cross lines in 256-colour textures (the game decodes width × height in one go)
        // and cross planes within a row in 16-colour ones (decoded a row at a time), as the game reads them
        if (x.eightBit) {
            byte[] all = new byte[bpl * height];
            for (int y = 0; y < height; y++)
                for (int i = 0; i < width; i++) all[y * bpl + i] = (byte) index[y * width + i];
            rle(out, all);
            out.write(0x0c);
            out.write(original, x.paletteAt, 768);
        } else {
            byte[] row = new byte[bpl * 4];
            for (int y = 0; y < height; y++) {
                java.util.Arrays.fill(row, (byte) 0);
                for (int plane = 0; plane < 4; plane++)
                    for (int i = 0; i < width; i++)
                        if ((index[y * width + i] >> plane & 1) != 0) row[plane * bpl + (i >> 3)] |= (byte) (0x80 >> (i & 7));
                rle(out, row);
            }
        }
        while (out.size() % 4 != 0) out.write(0);   // MGS keeps every texture 4-byte aligned
        return out.toByteArray();
    }

    /** PCX run-length coding. Values from 0xC0 up go out as runs, which every PCX reader agrees on. */
    private static void rle(ByteArrayOutputStream out, byte[] line) {
        for (int i = 0; i < line.length; ) {
            int v = line[i] & 0xff, n = 1;
            while (i + n < line.length && (line[i + n] & 0xff) == v && n < 63) n++;
            if (n > 1 || v >= 0xc0) { out.write(0xc0 | n); out.write(v); } else out.write(v);
            i += n;
        }
    }

    // ---------------------------------------------------------------- models (KMD)

    /** A model ready to show: one part per texture, four corners per face split into two triangles. */
    public static final class Model {
        public final Entry entry;
        public float[] pos, uv;               // 3 per vertex (y up), 2 per vertex (v = 0 at the bottom)
        public int[][] parts;                 // triangle indices per part
        public int[] partTexture;             // texture id (name hash) per part
        public final float[] min = new float[3], max = new float[3];
        public int faces;
        Model(Entry entry) { this.entry = entry; }
        public String label() {
            return String.format(Locale.ROOT, "%04x", entry.id) + " · " + faces + " faces · " + parts.length + " texture" + (parts.length == 1 ? "" : "s");
        }
    }

    /**
     * Reads a KMD model. Layout as in the open-source Codec browser; texture coordinates are
     * 0-255 across the texture, whatever its size (libdg/opack.c: u * width / 256), and a
     * face's corners go round its edge.
     */
    public static Model readModel(Entry e, byte[] b) throws IOException {
        if (b.length < 32) throw new IOException("Model too short");
        long meshCount = u32(b, 4);
        if (meshCount < 1 || meshCount > 1024 || 32 + meshCount * 88 > b.length) throw new IOException("Bad model header");
        int n = (int) meshCount;
        int[][] origin = new int[n][3];
        List<float[]> pos = new ArrayList<>(), uv = new ArrayList<>();
        Map<Integer, List<Integer>> byTexture = new LinkedHashMap<>();
        int faces = 0;
        for (int m = 0; m < n; m++) {
            int o = 32 + m * 88;
            long faceCount = u32(b, o + 4);
            int[] rel = {(int) u32(b, o + 32), (int) u32(b, o + 36), (int) u32(b, o + 40)};
            int parent = (int) u32(b, o + 44);
            long vc = u32(b, o + 52), vo = u32(b, o + 56), vio = u32(b, o + 60), tco = u32(b, o + 76), to = u32(b, o + 80);
            if (faceCount > 65536 || vc > 65536) throw new IOException("Bad model part");
            if (vo + vc * 8 > b.length || vio + faceCount * 4 > b.length || tco + faceCount * 8 > b.length || to + faceCount * 2 > b.length)
                throw new IOException("Model part data outside the file");
            int[] base = parent >= 0 && parent < m ? origin[parent] : new int[3];
            for (int k = 0; k < 3; k++) origin[m][k] = base[k] + rel[k];
            for (int f = 0; f < faceCount; f++) {
                int tex = u16(b, (int) to + f * 2);
                List<Integer> list = byTexture.computeIfAbsent(tex, k -> new ArrayList<>());
                int first = pos.size();
                for (int c = 0; c < 4; c++) {
                    int vi = b[(int) vio + f * 4 + c] & 0xff;
                    if (vi >= vc) throw new IOException("Model face uses a missing vertex");
                    int vp = (int) vo + vi * 8;
                    short x = (short) u16(b, vp), y = (short) u16(b, vp + 2), z = (short) u16(b, vp + 4);
                    // PS1: y points down
                    pos.add(new float[]{x + origin[m][0], -(y + origin[m][1]), z + origin[m][2]});
                    int u = b[(int) tco + f * 8 + c * 2] & 0xff, v = b[(int) tco + f * 8 + c * 2 + 1] & 0xff;
                    uv.add(new float[]{u / 256f, 1f - v / 256f});
                }
                list.add(first); list.add(first + 1); list.add(first + 2);
                list.add(first); list.add(first + 2); list.add(first + 3);
                faces++;
            }
        }
        if (faces == 0) throw new IOException("Model has no faces");
        Model model = new Model(e);
        model.faces = faces;
        model.pos = new float[pos.size() * 3];
        model.uv = new float[uv.size() * 2];
        java.util.Arrays.fill(model.min, Float.MAX_VALUE);
        java.util.Arrays.fill(model.max, -Float.MAX_VALUE);
        for (int i = 0; i < pos.size(); i++) {
            float[] v = pos.get(i);
            for (int k = 0; k < 3; k++) {
                model.pos[i * 3 + k] = v[k];
                model.min[k] = Math.min(model.min[k], v[k]);
                model.max[k] = Math.max(model.max[k], v[k]);
            }
            model.uv[i * 2] = uv.get(i)[0];
            model.uv[i * 2 + 1] = uv.get(i)[1];
        }
        model.parts = new int[byTexture.size()][];
        model.partTexture = new int[byTexture.size()];
        int p = 0;
        for (Map.Entry<Integer, List<Integer>> t : byTexture.entrySet()) {
            model.partTexture[p] = t.getKey();
            int[] idx = new int[t.getValue().size()];
            for (int i = 0; i < idx.length; i++) idx[i] = t.getValue().get(i);
            model.parts[p++] = idx;
        }
        return model;
    }

    /** The texture a model uses: the same area's first, then anywhere in the file (shared ones). */
    public static Entry findTexture(Info info, String area, int id) {
        Entry elsewhere = null;
        for (Part p : info.parts)
            for (Entry e : p.entries)
                if (e.isTexture() && e.id == id) {
                    if (p.name.equals(area)) return e;
                    if (elsewhere == null) elsewhere = e;
                }
        return elsewhere;
    }

    // ---------------------------------------------------------------- rebuilding STAGE.DIR

    /** Rebuilds one area's bytes with some files replaced; throws if they no longer fit. */
    static byte[] buildArea(FileChannel ch, Part part, Map<String, byte[]> replace) throws IOException {
        int slots = part.slots.size();
        byte[][] data = new byte[slots][];
        for (int i = 0; i < slots; i++) {
            Slot s = part.slots.get(i);
            if (s.children != null) {
                ByteArrayOutputStream dar = new ByteArrayOutputStream();
                for (Entry c : s.children) {
                    byte[] body = replace.containsKey(c.key()) ? replace.get(c.key()) : read(ch, c);
                    dar.write(c.id & 0xff); dar.write(c.id >> 8 & 0xff); dar.write(c.type); dar.write(c.darPad);
                    for (int k = 0; k < 4; k++) dar.write((int) ((long) body.length >> (8 * k) & 0xff));
                    dar.write(body, 0, body.length);
                }
                data[i] = dar.toByteArray();
            } else {
                data[i] = replace.containsKey(s.entry.key()) ? replace.get(s.entry.key()) : read(ch, s.entry);
            }
        }
        // Lay the files out the way the game reads them
        long[] at = new long[slots];
        long rel = SECTOR;
        for (int i = 0; i < slots; i++) {
            at[i] = rel;
            rel += data[i].length;
            if (!part.slots.get(i).packed) rel = (rel + SECTOR - 1) / SECTOR * SECTOR;
        }
        long length = part.end - part.base;
        if (rel > length) {
            throw new IOException("Area " + part.name + " is " + (rel - length) + " bytes too big after the edits (it has room for " +
                    "the original layout only). Edit fewer of its textures, or use fewer new colours and less fine detail.");
        }
        byte[] out = new byte[(int) length];
        System.arraycopy(part.header, 0, out, 0, 4);
        int p = 4;
        for (int[] item : part.table) {
            long name, size;
            if (item[0] >= 0) {
                Slot s = part.slots.get(item[0]);
                name = (s.id & 0xffffL) | (long) s.group << 16 | (long) s.type << 24;
                size = s.runStart >= 0 ? at[item[0]] - at[s.runStart] : data[item[0]].length;
            } else {
                name = (long) item[1] << 16 | 0xffL << 24;
                size = at[item[3] - 1] + data[item[3] - 1].length - at[item[2]];
            }
            if (p + 8 > SECTOR) throw new IOException("Area " + part.name + ": file table too long");
            for (int k = 0; k < 4; k++) { out[p + k] = (byte) (name >> (8 * k)); out[p + 4 + k] = (byte) (size >> (8 * k)); }
            p += 8;
        }
        for (int i = 0; i < slots; i++) System.arraycopy(data[i], 0, out, (int) at[i], data[i].length);
        return out;
    }

    /** Areas that would no longer fit with these edits, as messages; empty when everything fits. */
    public static List<String> checkFit(FileChannel ch, Info info, Map<String, byte[]> edits) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Part part : info.parts) {
            boolean touched = false;
            for (Entry e : part.entries) if (edits.containsKey(e.key())) { touched = true; break; }
            if (!touched) continue;
            try { buildArea(ch, part, edits); } catch (IOException tooBig) { problems.add(tooBig.getMessage()); }
        }
        return problems;
    }

    /** Finds an entry by its key. */
    public static Entry byKey(Info info, String key) {
        for (Part p : info.parts) for (Entry e : p.entries) if (e.key().equals(key)) return e;
        return null;
    }

    /**
     * The PNG name extraction gives each texture ("area/group/hash.png", or hash_2.png when a
     * name repeats), mapped back to the texture's key.
     */
    public static Map<String, String> extractedNames(Info info) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Part part : info.parts)
            for (Entry e : part.entries) {
                String path = e.path();
                String dir = path.substring(0, path.lastIndexOf('/'));
                String stem = path.substring(path.lastIndexOf('/') + 1, path.lastIndexOf('.'));
                String ext = e.isTexture() ? ".png" : "." + e.extension();
                String name = stem + ext;
                for (int n = 2; !used.add(dir + "/" + name); n++) name = stem + "_" + n + ext;
                if (e.isTexture()) out.put(dir + "/" + name, e.key());
            }
        return out;
    }

    /** Progress for long rebuilds. */
    public interface Progress { void step(String what); }

    /**
     * Writes a new STAGE.DIR to {@code out}: the original, with the areas that hold edited files
     * rebuilt in place. {@code edits}: new file contents by {@link Entry#key()}. The file keeps
     * its size and every area keeps its position, so the game finds everything where it was.
     */
    public static void rebuild(FileChannel ch, Info info, Map<String, byte[]> edits, java.io.OutputStream out, Progress progress) throws IOException {
        if (info.face) throw new IOException("Only STAGE.DIR can be rebuilt");
        Map<Part, byte[]> areas = new LinkedHashMap<>();
        for (Part part : info.parts) {
            boolean touched = false;
            for (Entry e : part.entries) if (edits.containsKey(e.key())) { touched = true; break; }
            if (!touched) continue;
            if (progress != null) progress.step("Rebuilding area " + part.name + "...");
            areas.put(part, buildArea(ch, part, edits));
        }
        long pos = 0, size = ch.size();
        byte[] buffer = new byte[1 << 20];
        for (Map.Entry<Part, byte[]> a : areas.entrySet()) {
            copy(ch, pos, a.getKey().base - pos, out, buffer);
            out.write(a.getValue());
            pos = a.getKey().end;
        }
        copy(ch, pos, size - pos, out, buffer);
    }

    private static void copy(FileChannel ch, long from, long length, java.io.OutputStream out, byte[] buffer) throws IOException {
        while (length > 0) {
            int n = (int) Math.min(buffer.length, length);
            ByteBuffer bb = ByteBuffer.wrap(buffer, 0, n);
            while (bb.hasRemaining()) if (ch.read(bb, from + bb.position()) < 0) throw new IOException("Unexpected end of file");
            out.write(buffer, 0, n);
            from += n;
            length -= n;
        }
    }

    // ---------------------------------------------------------------- FACE.DAT

    public static boolean isFaceDat(FileChannel ch) {
        try {
            return walkFace(ch) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static Info parseFaceDat(FileChannel ch) throws IOException {
        List<Part> parts = walkFace(ch);
        if (parts == null) throw new IOException("Not a Metal Gear Solid FACE.DAT");
        return new Info(true, parts);
    }

    /** Null unless the whole file is a chain of portrait groups that ends exactly at its end. */
    private static List<Part> walkFace(FileChannel ch) throws IOException {
        long size = ch.size();
        if (size < SECTOR || size % SECTOR != 0) return null;
        List<Part> parts = new ArrayList<>();
        long pos = 0;
        while (pos < size) {
            long count = u32(read(ch, pos, 4), 0);
            if (count == 0 || count > 1024 || pos + 4 + count * 12 > size) return null;
            byte[] table = read(ch, pos + 4, (int) count * 12);
            long base = pos + 4, max = 0;
            Part part = new Part(Integer.toString(parts.size()));
            for (int i = 0; i < count; i++) {
                int anim = u16(table, i * 12), id = u16(table, i * 12 + 2);
                long length = u32(table, i * 12 + 4), offset = u32(table, i * 12 + 8);
                if (anim > 1 || length < 32 || offset < count * 12 || base + offset + length > size) return null;
                part.entries.add(new Entry(part.name, anim != 0 ? 'a' : 'f', id, 0, base + offset, (int) length));
                max = Math.max(max, offset + length);
            }
            parts.add(part);
            pos = (base + max + SECTOR - 1) / SECTOR * SECTOR;
        }
        return pos == size && !parts.isEmpty() ? parts : null;
    }

    private static final String[] FACE_PARTS = {"base", "eyes-droop", "eyes-blink", "extra", "mouth-e", "mouth-a"};

    private static int[] facePalette(byte[] b, long at) throws IOException {
        if (at < 0 || at + 512 > b.length) throw new IOException("Portrait palette outside its data");
        int[] pal = new int[256];
        for (int i = 0; i < 256; i++) {
            int v = u16(b, (int) at + i * 2);
            int r = (v & 31) * 255 / 31, g = (v >> 5 & 31) * 255 / 31, bl = (v >> 10 & 31) * 255 / 31;
            pal[i] = (v == 0 ? 0 : 0xff000000) | r << 16 | g << 8 | bl;
        }
        return pal;
    }

    /** x, y, then the image. */
    private static Object[] faceImage(byte[] b, long at, int[] pal) throws IOException {
        if (at < 0 || at + 4 > b.length) throw new IOException("Portrait image outside its data");
        int x = b[(int) at], y = b[(int) at + 1], w = b[(int) at + 2], h = b[(int) at + 3];
        if (w <= 0 || h <= 0 || at + 4 + (long) w * h > b.length) throw new IOException("Bad portrait image");
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) argb[i] = pal[b[(int) at + 4 + i] & 0xff];
        return new Object[]{x, y, new Image(w, h, argb)};
    }

    /**
     * A portrait as named images: "base", then each expression piece drawn over the base
     * ("eyes-blink", "mouth-a", ...). An animation as "frame00", "frame01", ...
     */
    public static Map<String, Image> decodeFace(Entry e, byte[] b) throws IOException {
        Map<String, Image> out = new LinkedHashMap<>();
        if (e.group == 'a') {
            long frames = u32(b, 0);
            if (frames == 0 || frames > 256 || 4 + frames * 12 > b.length) throw new IOException("Bad animation");
            for (int i = 0; i < frames; i++) {
                long pal = u32(b, 4 + i * 12), img = u32(b, 8 + i * 12);
                if (pal == 0 || img == 0) continue;
                out.put(String.format(Locale.ROOT, "frame%02d", i), (Image) faceImage(b, img, facePalette(b, pal))[2]);
            }
            return out;
        }
        if (b.length < 28) throw new IOException("Bad portrait");
        int[] pal = facePalette(b, u32(b, 0));
        long baseAt = u32(b, 4);
        if (baseAt == 0) throw new IOException("Portrait has no base image");
        Image base = (Image) faceImage(b, baseAt, pal)[2];
        out.put(FACE_PARTS[0], base);
        for (int k = 1; k < FACE_PARTS.length; k++) {
            long at = u32(b, 4 + k * 4);
            if (at == 0) continue;
            Object[] piece = faceImage(b, at, pal);
            int px = (Integer) piece[0], py = (Integer) piece[1];
            Image over = (Image) piece[2];
            int[] argb = base.argb.clone();
            for (int y = 0; y < over.height; y++) {
                int ty = py + y;
                if (ty < 0 || ty >= base.height) continue;
                for (int x = 0; x < over.width; x++) {
                    int tx = px + x, c = over.argb[y * over.width + x];
                    if (tx < 0 || tx >= base.width || (c >>> 24) == 0) continue;
                    argb[ty * base.width + tx] = c;
                }
            }
            out.put(FACE_PARTS[k], new Image(base.width, base.height, argb));
        }
        return out;
    }
}
