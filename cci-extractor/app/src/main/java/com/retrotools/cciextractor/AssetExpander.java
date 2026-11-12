package com.retrotools.cciextractor;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Looks inside an extracted file and writes out anything it can unpack or convert:
 * compressed files (Yaz0, LZ10/LZ11) are decompressed, archives (SARC, darc, ZAR, GARC, SDAT, SWAR) are unpacked
 * into a {@code <name>_contents} folder, textures become PNG and audio becomes WAV. Runs recursively, so a
 * compressed archive of textures ends up as PNGs. Nothing here touches Android APIs, so tests can drive it.
 */
public final class AssetExpander {
    /** Files larger than this are left as they are (everything is processed in memory). */
    public static final int MAX_INPUT = 64 << 20;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_ARCHIVE_ITEMS = 20000;

    private AssetExpander() {}

    /** Receives output files. Paths use '/' and are relative to the folder of the file being expanded. */
    public interface Sink {
        void file(String path, byte[] data) throws IOException;
        void png(String path, int width, int height, int[] argb) throws IOException;
        boolean cancelled();
    }

    /** What an expansion produced; added up across calls. */
    public static final class Stats {
        public int images, sounds, unpacked, decompressed;
        public int total() { return images + sounds + unpacked + decompressed; }
    }

    /**
     * Cheap test on a file's first and last bytes (the tail may be shorter than 0x28 for small files):
     * is it worth reading this whole file into memory for {@link #expand}?
     */
    public static boolean worthReading(byte[] head, byte[] tail, long size) {
        if (head == null || head.length < 4) return false;
        String m = new String(head, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        switch (m) {
            case "SARC": case "darc": case "CRAG": case "Yaz0": case "CTPK": case "CGFX": case "BCH\0":
            case "CWAV": case "CSTM": case "SDAT": case "SWAR": case "STRM": case "SWAV": case "BTX0": case "BMD0":
                return true;
            default: break;
        }
        if (m.startsWith("ZAR")) return true;
        if (tail != null && tail.length >= 0x28) {
            String t = new String(tail, tail.length - 0x28, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (t.equals("CLIM") || t.equals("FLIM")) return true;
        }
        // LZ10/LZ11: only when the declared output size is believable for a file of this size.
        int first = head[0] & 255;
        if ((first != 0x10 && first != 0x11) || head.length < 5 || size > (48 << 20)) return false;
        long declared = (head[1] & 255) | (head[2] & 255) << 8 | (head[3] & 255) << 16;
        return declared >= 16 && declared >= size / 9 && declared <= size * 300;
    }

    /** Expands one file. {@code name} is its file name; outputs are written next to it through the sink. */
    public static void expand(String name, byte[] data, Sink sink, Stats stats) {
        expand("", name, data, sink, stats, 0, true);
    }

    /** As {@link #expand}, but with LZ10/LZ11 detection off for callers that have already decompressed the file. */
    public static void expandDecompressed(String name, byte[] data, Sink sink, Stats stats) {
        expand("", name, data, sink, stats, 0, false);
    }

    private static void expand(String folder, String name, byte[] data, Sink sink, Stats stats, int depth, boolean allowLz) {
        if (data == null || data.length < 8 || depth > MAX_DEPTH || sink.cancelled()) return;
        try {
            // ---- compression
            if (CtrArchives.isYaz0(data)) {
                byte[] inner = CtrArchives.yaz0(data);
                String out = name + ".decompressed";
                sink.file(folder + out, inner); stats.decompressed++;
                expand(folder, out, inner, sink, stats, depth + 1, true);
                return;
            }
            if (allowLz && ((data[0] & 255) == 0x10 || (data[0] & 255) == 0x11)) {
                NitroLz.Result lz = NitroLz.tryWhole(data);
                if (lz != null && lz.data.length >= 8) {
                    String out = name + ".decompressed";
                    sink.file(folder + out, lz.data); stats.decompressed++;
                    expand(folder, out, lz.data, sink, stats, depth + 1, true);
                    return;
                }
            }
            // ---- archives
            if (CtrArchives.kind(data) != CtrArchives.Kind.NONE) {
                List<CtrArchives.Item> items = CtrArchives.list(data);
                if (items.size() > MAX_ARCHIVE_ITEMS) return;
                String base = folder + name + "_contents/";
                for (CtrArchives.Item item : items) {
                    if (sink.cancelled()) return;
                    String path = safePath(item.path);
                    byte[] bytes = item.bytes(data);
                    sink.file(base + path, bytes); stats.unpacked++;
                    int slash = path.lastIndexOf('/');
                    expand(base + (slash < 0 ? "" : path.substring(0, slash + 1)), slash < 0 ? path : path.substring(slash + 1),
                            bytes, sink, stats, depth + 1, true);
                }
                return;
            }
            if (NdsMedia.isSdat(data) || NdsMedia.isSwar(data)) {
                List<NdsMedia.Sound> files = NdsMedia.isSdat(data) ? NdsMedia.sdatFiles(data) : NdsMedia.swarWaves(data);
                boolean waves = NdsMedia.isSwar(data);
                String base = folder + name + "_contents/";
                for (NdsMedia.Sound f : files) {
                    if (sink.cancelled()) return;
                    String path = safePath(f.path);
                    sink.file(base + path, f.data);
                    if (waves) stats.sounds++; else stats.unpacked++;
                    if (!waves) {
                        int slash = path.lastIndexOf('/');
                        expand(base + (slash < 0 ? "" : path.substring(0, slash + 1)), slash < 0 ? path : path.substring(slash + 1),
                                f.data, sink, stats, depth + 1, false);
                    }
                }
                return;
            }
            // ---- textures
            if (CtrTextures.kind(data) != CtrTextures.Kind.NONE) {
                List<CtrTextures.Texture> textures = CtrTextures.decode(data);
                boolean single = textures.size() == 1;
                for (int i = 0; i < textures.size(); i++) {
                    if (sink.cancelled()) return;
                    CtrTextures.Texture t = textures.get(i);
                    String label = t.name.isEmpty() ? String.format(Locale.ROOT, "%03d", i) : safeName(t.name);
                    sink.png(folder + name + (single && t.name.isEmpty() ? "" : "." + label) + ".png", t.width, t.height, t.argb);
                    stats.images++;
                }
                return;
            }
            if (NdsMedia.hasTextures(data)) {
                for (NdsMedia.Texture t : NdsMedia.textures(data)) {
                    if (sink.cancelled()) return;
                    sink.png(folder + name + "." + safeName(t.name) + ".png", t.width, t.height, t.argb);
                    stats.images++;
                }
                return;
            }
            // ---- audio
            if (GameAudio.kind(data) != GameAudio.Kind.NONE) {
                sink.file(folder + name + ".wav", GameAudio.toWav(data));
                stats.sounds++;
            }
        } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
            // The original file is already saved; a file we can't parse is simply left as it is.
        }
    }

    static String safeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 120; i++) {
            char c = name.charAt(i);
            sb.append(c < 0x20 || c == 0x7f || "\\/:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String s = sb.toString().trim();
        return s.isEmpty() || s.equals(".") || s.equals("..") ? "unnamed" : s;
    }

    /** Sanitises an archive-internal path: no absolute paths, no "..", no characters Android storage rejects. */
    static String safePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(safeName(part));
        }
        return sb.length() == 0 ? "unnamed" : sb.toString();
    }
}
