package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Display palettes for raw tile sheets. Tile codecs work in exact grayscale index levels; a palette only
 * changes how those indexes are shown/exported, and edited PNGs are mapped back to the nearest index.
 */
public final class Palettes {
    public static final String[] PRESETS = {"Grayscale (default)", "Inverted grayscale", "Game Boy green",
            "Warm ramp", "Cool ramp", "Rainbow (distinct colours)"};

    private Palettes() {}

    /** Grey level used by the codecs for index i at the given bit depth. */
    public static int grayLevel(int index, int bpp) {
        int range = (1 << bpp) - 1;
        return index * 255 / range;
    }

    public static int[] grayscale(int bpp) {
        int n = 1 << bpp;
        int[] p = new int[n];
        for (int i = 0; i < n; i++) { int v = grayLevel(i, bpp); p[i] = 0xff000000 | (v << 16) | (v << 8) | v; }
        return p;
    }

    /** Build a preset palette sized for {@code bpp}. Index 0 of "Grayscale" returns null (codec default). */
    public static int[] preset(int which, int bpp) {
        int n = 1 << Math.min(bpp, 8);
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0 : i / (float) (n - 1);
            switch (which) {
                case 1: { int v = Math.round(255 * (1 - t)); p[i] = rgb(v, v, v); break; }
                case 2: p[i] = ramp(t, new int[]{0x0f380f, 0x306230, 0x8bac0f, 0x9bbc0f}); break;
                case 3: p[i] = ramp(t, new int[]{0x1a0a00, 0x7a2e0a, 0xe07b2a, 0xffe8b0}); break;
                case 4: p[i] = ramp(t, new int[]{0x050a24, 0x1f4e8c, 0x4fb0d6, 0xe8fbff}); break;
                case 5: p[i] = i == 0 ? rgb(0, 0, 0) : hsv((i * 0.6180339f) % 1f, 0.75f, 0.95f); break;
                default: { int v = grayLevel(i, Math.min(bpp, 8)); p[i] = rgb(v, v, v); }
            }
        }
        return p;
    }

    private static int rgb(int r, int g, int b) { return 0xff000000 | (r << 16) | (g << 8) | b; }
    private static int ramp(float t, int[] stops) {
        float pos = t * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) pos);
        float f = pos - i;
        int a = stops[i], b = stops[i + 1];
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return rgb(r, g, bl);
    }
    private static int hsv(float h, float s, float v) {
        int i = (int) (h * 6); float f = h * 6 - i;
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (i % 6) { case 0: r=v;g=t;b=p;break; case 1: r=q;g=v;b=p;break; case 2: r=p;g=v;b=t;break;
            case 3: r=p;g=q;b=v;break; case 4: r=t;g=p;b=v;break; default: r=v;g=p;b=q; }
        return rgb(Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }

    /**
     * Load a palette file: JASC-PAL (.pal text), GIMP .gpl, Adobe .act (768 bytes), DS .nclr,
     * RIFF PAL, or raw little-endian BGR555 (GBA/SNES/DS) data.
     */
    public static int[] parse(byte[] data, String filename) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (data.length >= 8 && startsWith(data, "JASC-PAL")) return parseText(data, 3);
        if (data.length >= 12 && startsWith(data, "GIMP Palette")) return parseGimp(data);
        if (data.length >= 24 && startsWith(data, "RIFF") && data[8] == 'P' && data[9] == 'A' && data[10] == 'L') {
            int count = (data[22] & 255) | ((data[23] & 255) << 8);
            if (24 + count * 4 > data.length) throw new IOException("Truncated RIFF palette");
            int[] p = new int[Math.min(256, count)];
            for (int i = 0; i < p.length; i++) p[i] = rgb(data[24 + i * 4] & 255, data[25 + i * 4] & 255, data[26 + i * 4] & 255);
            return p;
        }
        if (data.length >= 0x2a && startsWith(data, "RLCN")) {
            try { return NdsGraphics.palette(data, 4, 0); }
            catch (IOException shortPalette) { return NdsGraphics.palette(data, 3, 0); }
        }
        if (name.endsWith(".act") || data.length == 768 || data.length == 772) {
            int count = data.length == 772 ? Math.max(1, Math.min(256, ((data[768] & 255) << 8) | (data[769] & 255))) : 256;
            int[] p = new int[count];
            for (int i = 0; i < count; i++) p[i] = rgb(data[i * 3] & 255, data[i * 3 + 1] & 255, data[i * 3 + 2] & 255);
            return p;
        }
        if (data.length >= 4 && data.length <= 512 && data.length % 2 == 0) {
            int[] p = new int[data.length / 2];
            for (int i = 0; i < p.length; i++) {
                int c = (data[i * 2] & 255) | ((data[i * 2 + 1] & 255) << 8);
                int r = c & 31, g = (c >>> 5) & 31, b = (c >>> 10) & 31;
                p[i] = rgb((r << 3) | (r >>> 2), (g << 3) | (g >>> 2), (b << 3) | (b >>> 2));
            }
            return p;
        }
        throw new IOException("Unrecognised palette. Use .pal (JASC/RIFF), .gpl, .act, .nclr or raw BGR555 (≤512 bytes).");
    }

    private static boolean startsWith(byte[] d, String s) {
        if (d.length < s.length()) return false;
        for (int i = 0; i < s.length(); i++) if (d[i] != (byte) s.charAt(i)) return false;
        return true;
    }
    private static int[] parseText(byte[] data, int skipLines) throws IOException {
        String[] lines = new String(data, StandardCharsets.US_ASCII).split("\\r?\\n");
        List<Integer> colours = new ArrayList<>();
        for (int i = skipLines; i < lines.length && colours.size() < 256; i++) {
            String[] parts = lines[i].trim().split("\\s+");
            if (parts.length < 3) continue;
            try { colours.add(rgb(clamp(parts[0]), clamp(parts[1]), clamp(parts[2]))); }
            catch (NumberFormatException ignored) { }
        }
        if (colours.isEmpty()) throw new IOException("Palette file has no colours");
        int[] p = new int[colours.size()];
        for (int i = 0; i < p.length; i++) p[i] = colours.get(i);
        return p;
    }
    private static int[] parseGimp(byte[] data) throws IOException {
        String text = new String(data, StandardCharsets.US_ASCII);
        StringBuilder rows = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("GIMP") || t.startsWith("Name:") || t.startsWith("Columns:")) continue;
            rows.append(t).append('\n');
        }
        return parseText(rows.toString().getBytes(StandardCharsets.US_ASCII), 0);
    }
    private static int clamp(String s) { return Math.max(0, Math.min(255, Integer.parseInt(s))); }

    /** Fit a palette to exactly 2^bpp entries (pad with grey, truncate extras). */
    public static int[] fit(int[] palette, int bpp) {
        int n = 1 << bpp;
        int[] p = new int[n];
        int[] gray = grayscale(bpp);
        for (int i = 0; i < n; i++) p[i] = i < palette.length ? (palette[i] | 0xff000000) : gray[i];
        return p;
    }

    public static int nearest(int argb, int[] palette, int count) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        int best = 0; long bestD = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int c = palette[i];
            int dr = r - ((c >> 16) & 255), dg = g - ((c >> 8) & 255), db = b - (c & 255);
            long d = 3L * dr * dr + 4L * dg * dg + 2L * db * db;
            if (d < bestD) { bestD = d; best = i; if (d == 0) break; }
        }
        return best;
    }

    /** Recolour grayscale codec output with a palette (null palette = unchanged). */
    public static int[] recolour(int[] gray, int bpp, int[] palette) {
        if (palette == null) return gray;
        int[] p = fit(palette, bpp);
        int range = (1 << bpp) - 1;
        int[] out = new int[gray.length];
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i] & 255;
            out[i] = p[(v * range + 127) / 255];
        }
        return out;
    }

    public static final class Mapped { public final int[] gray; public final int inexact;
        Mapped(int[] gray, int inexact) { this.gray = gray; this.inexact = inexact; } }

    /** Convert an edited, palette-coloured sheet back to the codecs' exact grayscale levels. */
    public static Mapped toGray(int[] pixels, int bpp, int[] palette) {
        if (palette == null) return new Mapped(pixels, 0);
        int[] p = fit(palette, bpp);
        int n = p.length, inexact = 0;
        int[] out = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int idx = ((c >>> 24) & 255) < 128 ? 0 : nearest(c, p, n);
            if (((c >>> 24) & 255) >= 128 && ((p[idx] ^ c) & 0xffffff) != 0) inexact++;
            int v = grayLevel(idx, bpp);
            out[i] = 0xff000000 | (v << 16) | (v << 8) | v;
        }
        return new Mapped(out, inexact);
    }
}
