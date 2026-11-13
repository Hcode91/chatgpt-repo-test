package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Metal Gear Solid (PS1) voice archives: VOX.DAT. Worked out from a retail VOX file.
 *
 * <pre>
 * The file is a run of entries, each starting on a 2048-byte boundary. An entry is a stream of chunks:
 *   u8 type, u24 length (including this 4-byte header), payload.
 *   0x10  8 bytes, u32 parameter
 *   0x02  20 bytes: u32 id, u32, u8 channels (1 or 2) …
 *   0x04  8 bytes
 *   0x03  subtitles: u32 0, u32 scene length, u16 lip-sync offset, u16 subtitle offset (both from the payload
 *         start), u32 size; subtitle records { u32 recordLength, u32 start, u32 duration, u32 0, text }.
 *         Times count 512-byte units of one channel's ADPCM (896 samples). "\x80|" in text is a line break.
 *   0x01  sound: PS-ADPCM, 22050 Hz. Mono: the payload is one run of frames. Stereo: the first half of each
 *         payload is left, the second half right.
 *   0xF0  end of entry.
 * </pre>
 * The sample rate isn't stored; 22050 Hz puts the voices at normal speaking pitch. Unknown chunk types are skipped.
 */
public final class MgsVox {
    public static final int SAMPLE_RATE = 22050;
    static final int SAMPLES_PER_TICK = 896;
    private static final int MAX_ENTRY_AUDIO = 32 << 20;

    private MgsVox() {}

    public static final class Subtitle {
        public final long start, duration;
        public final String text;
        Subtitle(long start, long duration, String text) { this.start = start; this.duration = duration; this.text = text; }
    }

    public static final class Entry {
        public final int index;
        public final long offset, end;
        public int channels = 1;
        public long audioBytes;
        public int subtitleChunks;
        Entry(int index, long offset, long end) { this.index = index; this.offset = offset; this.end = end; }
        public double seconds() { return audioBytes / 16.0 * 28 / channels / SAMPLE_RATE; }
        public String name() { return String.format(Locale.ROOT, "vox_%04d", index); }
    }

    public static final class Info {
        public final List<Entry> entries = new ArrayList<>();
        public long audioBytes;
        public int stereo, withSubtitles;
        public String description() {
            return "Metal Gear Solid voice archive · " + entries.size() + " clips · " +
                    String.format(Locale.ROOT, "%.1f", audioBytes / 16.0 * 28 / SAMPLE_RATE / 3600) + " h of audio";
        }
    }

    /** Converted clip: a WAV and, when the entry has subtitles, an .srt and the subtitle list. */
    public static final class Clip {
        public final byte[] wav;
        public final List<Subtitle> subtitles;
        Clip(byte[] wav, List<Subtitle> subtitles) { this.wav = wav; this.subtitles = subtitles; }
    }

    // ------------------------------------------------------------------ detection and index

    /** The first entry starts with a 0x10 chunk of 8 bytes holding 1, followed by a 20-byte 0x02 header. */
    public static boolean isVox(FileChannel ch) throws IOException {
        if (ch.size() < 0x800) return false;
        byte[] h = read(ch, 0, 12);
        return h[0] == 0x10 && h[1] == 8 && h[2] == 0 && h[3] == 0 && h[4] == 1 && h[5] == 0 && h[6] == 0 && h[7] == 0
                && h[8] == 2 && h[9] == 0x14 && h[10] == 0 && h[11] == 0;
    }

    /** Walks every entry, reading only chunk headers. */
    public static Info parse(FileChannel ch) throws IOException {
        if (!isVox(ch)) throw new IOException("Not a Metal Gear Solid VOX file");
        Info info = new Info();
        long size = ch.size(), p = 0;
        byte[] head = new byte[4];
        ByteBuffer hb = ByteBuffer.wrap(head);
        while (p + 4 <= size) {
            byte[] start = read(ch, p, 4);
            if (start[0] != 0x10 || start[1] != 8) {
                if (isZeroSector(ch, p)) { p += 0x800; continue; }
                throw new IOException(String.format(Locale.ROOT, "Unexpected data at 0x%X (entry %d)", p, info.entries.size()));
            }
            long entryStart = p;
            Entry e = null;
            boolean ended = false;
            int channels = 1, subChunks = 0;
            long audio = 0;
            while (p + 4 <= size) {
                hb.clear();
                while (hb.hasRemaining()) if (ch.read(hb, p + hb.position()) < 0) throw new IOException("Unexpected end of file");
                int type = head[0] & 255;
                long len = (head[1] & 255) | (head[2] & 255) << 8 | (long) (head[3] & 255) << 16;
                if (len < 4 || p + len > size) throw new IOException(String.format(Locale.ROOT, "Broken chunk at 0x%X", p));
                if (type == 0x02 && len >= 13) channels = Math.max(1, Math.min(2, read(ch, p + 12, 1)[0] & 255));
                else if (type == 0x01) audio += len - 4;
                else if (type == 0x03) subChunks++;
                p += len;
                if (type == 0xF0) { ended = true; break; }
            }
            if (!ended) throw new IOException(String.format(Locale.ROOT, "Entry at 0x%X has no end marker", entryStart));
            e = new Entry(info.entries.size(), entryStart, p);
            e.channels = channels; e.audioBytes = audio; e.subtitleChunks = subChunks;
            info.entries.add(e);
            info.audioBytes += audio;
            if (channels == 2) info.stereo++;
            if (subChunks > 0) info.withSubtitles++;
            p = (p + 0x7FF) & ~0x7FFL;
        }
        return info;
    }

    private static boolean isZeroSector(FileChannel ch, long p) throws IOException {
        if (p + 0x800 > ch.size()) return false;
        for (byte b : read(ch, p, 0x800)) if (b != 0) return false;
        return true;
    }

    // ------------------------------------------------------------------ conversion

    public static Clip convert(FileChannel ch, Entry e) throws IOException {
        if (e.audioBytes > MAX_ENTRY_AUDIO) throw new IOException("Clip is too large to convert");
        byte[] d = read(ch, e.offset, (int) (e.end - e.offset));
        return convert(d, e.channels);
    }

    /** Converts one entry's bytes (from its first chunk to its end marker). */
    static Clip convert(byte[] d, int channels) throws IOException {
        java.io.ByteArrayOutputStream left = new java.io.ByteArrayOutputStream(), right = new java.io.ByteArrayOutputStream();
        List<Subtitle> subs = new ArrayList<>();
        int p = 0;
        while (p + 4 <= d.length) {
            int type = d[p] & 255, len = (d[p + 1] & 255) | (d[p + 2] & 255) << 8 | (d[p + 3] & 255) << 16;
            if (len < 4 || p + len > d.length) throw new IOException("Broken chunk in clip");
            int body = p + 4, bodyLen = len - 4;
            if (type == 0x01) {
                if (channels == 2) {
                    int half = bodyLen / 2;
                    left.write(d, body, half);
                    right.write(d, body + half, half);
                } else left.write(d, body, bodyLen);
            } else if (type == 0x03) {
                subtitles(d, body, bodyLen, subs);
            }
            p += len;
            if (type == 0xF0) break;
        }
        short[] l = adpcm(left.toByteArray());
        short[][] pcm = channels == 2 ? new short[][]{l, java.util.Arrays.copyOf(adpcm(right.toByteArray()), l.length)} : new short[][]{l};
        return new Clip(GameAudio.wav(pcm, SAMPLE_RATE), subs);
    }

    private static void subtitles(byte[] d, int body, int len, List<Subtitle> out) {
        if (len < 16) return;
        int q = body + u16(d, body + 10), end = body + len;
        while (q + 16 <= end) {
            long recLen = u32(d, q);
            if (recLen < 16 || q + recLen > end) break;
            long start = u32(d, q + 4), dur = u32(d, q + 8);
            int t = q + 16, stop = t;
            while (stop < q + recLen && d[stop] != 0) stop++;
            StringBuilder sb = new StringBuilder();
            for (int i = t; i < stop; i++) {
                int c = d[i] & 255;
                if (c == 0x80 && i + 1 < stop && d[i + 1] == '|') { sb.append('\n'); i++; }
                else if (c == '|') sb.append('\n');
                else sb.append((char) c);
            }
            out.add(new Subtitle(start, dur, sb.toString().trim()));
            q += (int) recLen;
        }
    }

    private static final int[][] FILTERS = {{0, 0}, {60, 0}, {115, -52}, {98, -55}, {122, -60}};

    /** PS-ADPCM, 16-byte frames of 28 samples; one continuous channel. */
    static short[] adpcm(byte[] d) {
        int frames = d.length / 16;
        short[] out = new short[frames * 28];
        int n = 0, h1 = 0, h2 = 0;
        for (int f = 0; f < frames; f++) {
            int at = f * 16;
            int shift = d[at] & 15, filter = Math.min(4, (d[at] >> 4) & 15);
            for (int i = 0; i < 28; i++) {
                int b = d[at + 2 + i / 2] & 255;
                int nibble = (i & 1) == 0 ? b & 15 : b >> 4;
                int s = ((short) (nibble << 12)) >> shift;
                s += (h1 * FILTERS[filter][0] + h2 * FILTERS[filter][1] + 32) >> 6;
                s = Math.max(-32768, Math.min(32767, s));
                h2 = h1; h1 = s;
                out[n++] = (short) s;
            }
        }
        return out;
    }

    /** SubRip subtitles timed against the clip's WAV. */
    public static String srt(List<Subtitle> subs) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Subtitle s : subs) {
            sb.append(n++).append("\r\n").append(time(s.start)).append(" --> ").append(time(s.start + s.duration)).append("\r\n")
                    .append(s.text.replace("\n", "\r\n")).append("\r\n\r\n");
        }
        return sb.toString();
    }
    private static String time(long ticks) {
        long ms = Math.round(ticks * (double) SAMPLES_PER_TICK * 1000 / SAMPLE_RATE);
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    /** Short label for the list: first subtitle line, if any. */
    public static String firstLine(FileChannel ch, Entry e) {
        try {
            if (e.subtitleChunks == 0) return "";
            long p = e.offset;
            while (p + 4 <= e.end) {
                byte[] h = read(ch, p, 4);
                int len = (h[1] & 255) | (h[2] & 255) << 8 | (h[3] & 255) << 16;
                if (len < 4) return "";
                if ((h[0] & 255) == 0x03) {
                    List<Subtitle> subs = new ArrayList<>();
                    subtitles(read(ch, p + 4, len - 4), 0, len - 4, subs);
                    return subs.isEmpty() ? "" : subs.get(0).text.replace('\n', ' ');
                }
                p += len;
            }
        } catch (IOException ignored) { }
        return "";
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] read(FileChannel ch, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) if (ch.read(buf, offset + buf.position()) < 0) throw new IOException("Unexpected end of file");
        return buf.array();
    }
    private static int u16(byte[] d, int o) { return (d[o] & 255) | (d[o + 1] & 255) << 8; }
    private static long u32(byte[] d, int o) { return u16(d, o) | ((long) u16(d, o + 2) << 16); }
}
