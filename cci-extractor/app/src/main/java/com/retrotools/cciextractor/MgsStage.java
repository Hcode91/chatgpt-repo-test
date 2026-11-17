package com.retrotools.cciextractor;

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
        Entry(String folder, char group, int id, int type, long offset, int size) {
            this.folder = folder; this.group = group; this.id = id; this.type = type; this.offset = offset; this.size = size;
        }
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
        Part(String name) { this.name = name; }
        public int textures() { int n = 0; for (Entry e : entries) if (e.isTexture()) n++; return n; }
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
            int t = p.textures();
            return "🗺 " + p.name + " · " + t + " textures · " + (p.entries.size() - t) + " other files";
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
        byte[] table = read(ch, base, SECTOR);
        // Raw entries: hash, group, type, size, packed
        List<long[]> raw = new ArrayList<>();
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
                for (int i = raw.size() - 1; i >= 0; i--) {
                    long[] e = raw.get(i);
                    if (e[1] != group) break;
                    long start = e[3];
                    if (start > total) throw new IOException("Stage " + f.name + ": bad packed run");
                    e[4] = notLast ? 1 : 0;
                    e[3] = total - start;
                    total = start;
                    notLast = true;
                }
            } else {
                raw.add(new long[]{id, group, type, size, 0});
            }
        }
        long rel = SECTOR;
        for (long[] e : raw) {
            long offset = base + rel, size = e[3];
            if (size < 0 || offset + size > end) throw new IOException("Stage " + f.name + ": a file runs past the area");
            if (e[2] == 'd') {
                readDar(ch, f.name, (char) e[1], offset, size, part);
            } else {
                part.entries.add(new Entry(f.name, (char) e[1], (int) e[0], (int) e[2], offset, (int) size));
            }
            rel += size;
            if (e[4] == 0) rel = (rel + SECTOR - 1) / SECTOR * SECTOR;
        }
    }

    private static void readDar(FileChannel ch, String folder, char group, long offset, long size, Part part) throws IOException {
        long r = 0;
        while (r < size - 7) {
            byte[] h = read(ch, offset + r, 8);
            int id = u16(h, 0), type = h[2] & 0xff;
            long length = u32(h, 4);
            if (length > size - r - 8) throw new IOException("Archive in " + folder + " runs past its end");
            part.entries.add(new Entry(folder, group, id, type, offset + r + 8, (int) length));
            r += 8 + length;
        }
    }

    // ---------------------------------------------------------------- PCX

    public static boolean isPcx(byte[] b) {
        return b.length >= 128 && (b[0] & 0xff) == 0x0a && (b[1] & 0xff) == 5 && (b[2] & 0xff) == 1;
    }

    /** Decodes an MGS texture (PCX, 16 or 256 colours). Throws IOException on anything else. */
    public static Image decodePcx(byte[] b) throws IOException {
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
            if (c >= 0xc0) {
                if (p >= b.length) throw new IOException("PCX pixel data is cut short");
                int count = c & 0x3f, v = b[p++];
                for (int i = 0; i < count && got < raw.length; i++) raw[got++] = (byte) v;
            } else {
                raw[got++] = (byte) c;
            }
        }
        int[] argb = new int[width * height];
        if (paletted8) {
            // MGS: the palette follows the pixel data (then 0-3 padding bytes), not the end of the file
            int palAt = -1;
            if (p + 769 <= b.length && (b[p] & 0xff) == 0x0c) palAt = p + 1;
            else if (b.length >= 769 && (b[b.length - 769] & 0xff) == 0x0c) palAt = b.length - 768;
            if (palAt < 0) throw new IOException("PCX has no 256-colour palette");
            int[] pal = new int[256];
            for (int i = 0; i < 256; i++)
                pal[i] = 0xff000000 | (b[palAt + i * 3] & 0xff) << 16 | (b[palAt + i * 3 + 1] & 0xff) << 8 | (b[palAt + i * 3 + 2] & 0xff);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) argb[y * width + x] = pal[raw[y * bpl + x] & 0xff];
        } else {
            int[] pal = new int[16];
            for (int i = 0; i < 16; i++)
                pal[i] = 0xff000000 | (b[16 + i * 3] & 0xff) << 16 | (b[17 + i * 3] & 0xff) << 8 | (b[18 + i * 3] & 0xff);
            for (int y = 0; y < height; y++) {
                int row = y * planes * bpl;
                for (int x = 0; x < width; x++) {
                    int v = 0, bit = 7 - (x & 7);
                    for (int plane = 0; plane < 4; plane++)
                        v |= ((raw[row + plane * bpl + (x >> 3)] >> bit) & 1) << plane;
                    argb[y * width + x] = pal[v];
                }
            }
        }
        return new Image(width, height, argb);
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
