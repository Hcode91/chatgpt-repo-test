package com.retrotools.cciextractor;

import java.io.IOException;

/**
 * Audio decoding to 16-bit PCM WAV for Nintendo 3DS streams/waves (BCSTM, BCWAV) and Nintendo DS
 * sound data (STRM, SWAV, and the samples inside SWAR wave archives).
 * Layouts follow 3dbrew (CSTM/CWAV) and GBATEK (SDAT family).
 */
public final class GameAudio {
    private static final long MAX_SAMPLES = 48L << 20;   // per file, all channels together (~96 MB of PCM)

    private GameAudio() {}

    public enum Kind { BCWAV, BCSTM, STRM, SWAV, NONE }

    public static Kind kind(byte[] d) {
        if (d == null || d.length < 0x40) return Kind.NONE;
        if (CtrArchives.magic(d, 0, "CWAV")) return Kind.BCWAV;
        if (CtrArchives.magic(d, 0, "CSTM")) return Kind.BCSTM;
        if (CtrArchives.magic(d, 0, "STRM") && CtrArchives.magic(d, 0x10, "HEAD")) return Kind.STRM;
        if (CtrArchives.magic(d, 0, "SWAV") && CtrArchives.magic(d, 0x10, "DATA")) return Kind.SWAV;
        return Kind.NONE;
    }

    /** Converts a supported audio file to a WAV file. */
    public static byte[] toWav(byte[] d) throws IOException {
        switch (kind(d)) {
            case BCWAV: return bcwav(d);
            case BCSTM: return bcstm(d);
            case STRM: return strm(d);
            case SWAV: return swavSample(d, 0x18, d.length - 0x18);
            default: throw new IOException("Not a supported audio file");
        }
    }

    // ------------------------------------------------------------------ WAV output

    /** Builds a 16-bit PCM WAV from per-channel sample arrays (all the same length). */
    public static byte[] wav(short[][] channels, int sampleRate) {
        int n = channels.length == 0 ? 0 : channels[0].length, ch = Math.max(1, channels.length);
        int dataBytes = n * ch * 2;
        byte[] out = new byte[44 + dataBytes];
        put(out, 0, "RIFF"); le32(out, 4, 36 + dataBytes); put(out, 8, "WAVE"); put(out, 12, "fmt ");
        le32(out, 16, 16); le16(out, 20, 1); le16(out, 22, ch); le32(out, 24, sampleRate);
        le32(out, 28, sampleRate * ch * 2); le16(out, 32, ch * 2); le16(out, 34, 16);
        put(out, 36, "data"); le32(out, 40, dataBytes);
        int p = 44;
        for (int i = 0; i < n; i++) for (int c = 0; c < ch; c++) { le16(out, p, channels[c][i]); p += 2; }
        return out;
    }

    // ------------------------------------------------------------------ codecs

    private static final int[] IMA_INDEX = {-1, -1, -1, -1, 2, 4, 6, 8};
    private static final int[] IMA_STEP = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107,
            118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876,
            963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
            5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385,
            24623, 27086, 29794, 32767};

    /** Running state shared by the ADPCM decoders so a stream can be decoded block by block. */
    private static final class State { int h1, h2, index; }

    /** IMA ADPCM, low nibble first. Writes up to {@code count} samples. */
    private static int ima(byte[] d, int offset, int bytes, short[] out, int at, int count, State s) {
        int written = 0;
        for (int i = 0; i < bytes * 2 && written < count && at + written < out.length; i++) {
            int b = d[offset + (i >> 1)] & 255;
            int nibble = (i & 1) == 0 ? b & 15 : b >> 4;
            int step = IMA_STEP[s.index];
            int diff = step >> 3;
            if ((nibble & 1) != 0) diff += step >> 2;
            if ((nibble & 2) != 0) diff += step >> 1;
            if ((nibble & 4) != 0) diff += step;
            s.h1 = (nibble & 8) != 0 ? Math.max(-32768, s.h1 - diff) : Math.min(32767, s.h1 + diff);
            s.index = Math.max(0, Math.min(88, s.index + IMA_INDEX[nibble & 7]));
            out[at + written++] = (short) s.h1;
        }
        return written;
    }

    /** Nintendo DSP ADPCM: 8-byte frames (header + 14 nibbles) with 8 pairs of 16-bit coefficients. */
    private static int dsp(byte[] d, int offset, int bytes, short[] out, int at, int count, int[] coef, State s) {
        int written = 0;
        for (int frame = 0; frame + 8 <= bytes + 7 && written < count; frame += 8) {
            if (offset + frame >= d.length) break;
            int header = d[offset + frame] & 255;
            int scale = header & 15, predictor = Math.min(7, header >> 4);
            int c1 = coef[predictor * 2], c2 = coef[predictor * 2 + 1];
            for (int n = 0; n < 14 && written < count && at + written < out.length; n++) {
                int pos = offset + frame + 1 + (n >> 1);
                if (pos >= d.length || pos >= offset + bytes) return written;
                int b = d[pos] & 255;
                int nibble = (n & 1) == 0 ? b >> 4 : b & 15;
                if (nibble >= 8) nibble -= 16;
                int sample = (((nibble << scale) << 11) + 1024 + c1 * s.h1 + c2 * s.h2) >> 11;
                sample = Math.max(-32768, Math.min(32767, sample));
                s.h2 = s.h1; s.h1 = sample;
                out[at + written++] = (short) sample;
            }
        }
        return written;
    }

    private static int pcm16(byte[] d, int offset, int bytes, short[] out, int at, int count) {
        int n = Math.min(count, Math.min(bytes / 2, (d.length - offset) / 2));
        for (int i = 0; i < n && at + i < out.length; i++) out[at + i] = (short) ((d[offset + i * 2] & 255) | d[offset + i * 2 + 1] << 8);
        return Math.max(0, n);
    }
    private static int pcm8(byte[] d, int offset, int bytes, short[] out, int at, int count) {
        int n = Math.min(count, Math.min(bytes, d.length - offset));
        for (int i = 0; i < n && at + i < out.length; i++) out[at + i] = (short) (d[offset + i] << 8);
        return Math.max(0, n);
    }

    // ------------------------------------------------------------------ BCWAV

    private static int[] dspCoefficients(byte[] d, int at) throws IOException {
        need(d, at, 0x2C);
        int[] coef = new int[16];
        for (int i = 0; i < 16; i++) coef[i] = (short) u16(d, at + i * 2);
        return coef;
    }

    /**
     * CWAV: INFO block at [0x18], DATA block at [0x24]. INFO+8: u8 encoding (0 PCM8, 1 PCM16, 2 DSP ADPCM,
     * 3 IMA ADPCM), u8 loop, u32 sampleRate @+0xC, u32 loopStart, u32 sampleCount @+0x14, channel reference
     * table @+0x1C. Channel info: sample data reference (relative to DATA+8), ADPCM info reference
     * (relative to the channel info).
     */
    private static byte[] bcwav(byte[] d) throws IOException {
        if ((d[4] & 255) != 0xFF) throw new IOException("Big-endian wave files are not supported");
        int info = (int) u32(d, 0x18), data = (int) u32(d, 0x24);
        need(d, info, 0x24); need(d, data, 8);
        int encoding = d[info + 8] & 255;
        int rate = (int) u32(d, info + 0x0C);
        long samples = u32(d, info + 0x14);
        int table = info + 0x1C;
        int channels = (int) u32(d, table);
        if (channels < 1 || channels > 8) throw new IOException("Unreasonable channel count " + channels);
        if (samples * channels > MAX_SAMPLES) throw new IOException("Wave is too long to convert");
        short[][] pcm = new short[channels][(int) samples];
        for (int c = 0; c < channels; c++) {
            need(d, table + 4 + c * 8, 8);
            int channel = table + (int) u32(d, table + 8 + c * 8);
            need(d, channel, 0x10);
            int start = data + 8 + (int) u32(d, channel + 4);
            long adpcmOffset = u32(d, channel + 0x0C);
            int adpcm = channel + (int) adpcmOffset;
            int bytes = d.length - start;
            if (start < 0 || start > d.length) throw new IOException("Sample data lies outside the file");
            State s = new State();
            switch (encoding) {
                case 0: pcm8(d, start, bytes, pcm[c], 0, (int) samples); break;
                case 1: pcm16(d, start, bytes, pcm[c], 0, (int) samples); break;
                case 2: {
                    int[] coef = dspCoefficients(d, adpcm);
                    s.h1 = (short) u16(d, adpcm + 0x22); s.h2 = (short) u16(d, adpcm + 0x24);
                    dsp(d, start, bytes, pcm[c], 0, (int) samples, coef, s);
                    break;
                }
                case 3: {
                    need(d, adpcm, 4);
                    s.h1 = (short) u16(d, adpcm); s.index = Math.min(88, d[adpcm + 2] & 255);
                    ima(d, start, bytes, pcm[c], 0, (int) samples, s);
                    break;
                }
                default: throw new IOException("Unknown wave encoding " + encoding);
            }
        }
        return wav(pcm, rate);
    }

    // ------------------------------------------------------------------ BCSTM

    /**
     * CSTM: INFO block at [0x18], DATA block at [0x30]. INFO+8 holds three references relative to INFO+8:
     * stream info, track table, channel table. Stream info: u8 encoding, u8 loop, u8 channels, u32 sampleRate @+4,
     * u32 sampleCount @+0xC, u32 blockCount @+0x10, u32 blockSize, u32 blockSamples, u32 lastBlockSize,
     * u32 lastBlockSamples, u32 lastBlockPaddedSize, …, sample data reference @+0x30 (relative to DATA+8).
     * Sample data is stored block by block, one block per channel in turn.
     */
    private static byte[] bcstm(byte[] d) throws IOException {
        if ((d[4] & 255) != 0xFF) throw new IOException("Big-endian streams are not supported");
        int info = (int) u32(d, 0x18), data = (int) u32(d, 0x30);
        need(d, info, 0x20); need(d, data, 8);
        int base = info + 8;
        int stream = base + (int) u32(d, base + 4);
        need(d, stream, 0x38);
        int encoding = d[stream] & 255, channels = d[stream + 2] & 255;
        int rate = (int) u32(d, stream + 4);
        long samples = u32(d, stream + 0x0C);
        int blockCount = (int) u32(d, stream + 0x10), blockSize = (int) u32(d, stream + 0x14);
        int blockSamples = (int) u32(d, stream + 0x18), lastSize = (int) u32(d, stream + 0x1C);
        int lastSamples = (int) u32(d, stream + 0x20), lastPadded = (int) u32(d, stream + 0x24);
        long dataOffset = u32(d, stream + 0x34);
        if (channels < 1 || channels > 8) throw new IOException("Unreasonable channel count " + channels);
        if (blockCount < 1 || blockSize <= 0 || blockSamples <= 0) throw new IOException("Invalid stream block layout");
        long total = (long) (blockCount - 1) * blockSamples + lastSamples;
        if (samples <= 0 || samples > total) samples = total;
        if (samples * channels > MAX_SAMPLES) throw new IOException("Stream is too long to convert");
        int[][] coef = new int[channels][];
        State[] states = new State[channels];
        long channelRef = u32(d, base + 0x14);
        int channelTable = base + (int) channelRef;
        for (int c = 0; c < channels; c++) {
            states[c] = new State();
            if (encoding == 2) {
                need(d, channelTable + 4 + c * 8, 8);
                int channelInfo = channelTable + (int) u32(d, channelTable + 8 + c * 8);
                need(d, channelInfo, 8);
                int adpcm = channelInfo + (int) u32(d, channelInfo + 4);
                coef[c] = dspCoefficients(d, adpcm);
                states[c].h1 = (short) u16(d, adpcm + 0x22); states[c].h2 = (short) u16(d, adpcm + 0x24);
            }
        }
        short[][] pcm = new short[channels][(int) samples];
        long pos = data + 8 + dataOffset;
        int done = 0;
        for (int b = 0; b < blockCount && done < samples; b++) {
            boolean last = b == blockCount - 1;
            int size = last ? lastSize : blockSize, stride = last ? Math.max(lastPadded, lastSize) : blockSize;
            int want = (int) Math.min(last ? lastSamples : blockSamples, samples - done);
            for (int c = 0; c < channels; c++) {
                long at = pos + (long) c * stride;
                if (at >= d.length) break;
                int bytes = (int) Math.min(size, d.length - at);
                switch (encoding) {
                    case 0: pcm8(d, (int) at, bytes, pcm[c], done, want); break;
                    case 1: pcm16(d, (int) at, bytes, pcm[c], done, want); break;
                    case 2: dsp(d, (int) at, bytes, pcm[c], done, want, coef[c], states[c]); break;
                    case 3: ima(d, (int) at, bytes, pcm[c], done, want, states[c]); break;
                    default: throw new IOException("Unknown stream encoding " + encoding);
                }
            }
            pos += (long) stride * channels;
            done += want;
        }
        return wav(pcm, rate);
    }

    // ------------------------------------------------------------------ Nintendo DS

    /**
     * STRM: "HEAD" @0x10 — u8 type (0 PCM8, 1 PCM16, 2 IMA ADPCM) @0x18, u8 loop, u8 channels, u16 sampleRate @0x1C,
     * u32 sampleCount @0x24, u32 dataOffset @0x28, u32 blockCount @0x2C, u32 blockSize @0x30, u32 blockSamples @0x34,
     * u32 lastBlockSize @0x38, u32 lastBlockSamples @0x3C. Blocks alternate between channels; every ADPCM block
     * starts with a 4-byte header (s16 sample, u8 step index).
     */
    private static byte[] strm(byte[] d) throws IOException {
        need(d, 0, 0x68);
        int type = d[0x18] & 255, channels = d[0x1A] & 255, rate = u16(d, 0x1C);
        long samples = u32(d, 0x24);
        long pos = u32(d, 0x28);
        int blockCount = (int) u32(d, 0x2C), blockSize = (int) u32(d, 0x30), blockSamples = (int) u32(d, 0x34);
        int lastSize = (int) u32(d, 0x38), lastSamples = (int) u32(d, 0x3C);
        if (channels < 1 || channels > 2 || blockCount < 1 || blockSize <= 0) throw new IOException("Invalid STRM header");
        long total = (long) (blockCount - 1) * blockSamples + lastSamples;
        if (samples <= 0 || samples > total) samples = total;
        if (samples * channels > MAX_SAMPLES) throw new IOException("Stream is too long to convert");
        short[][] pcm = new short[channels][(int) samples];
        int done = 0;
        for (int b = 0; b < blockCount && done < samples; b++) {
            boolean last = b == blockCount - 1;
            int size = last ? lastSize : blockSize;
            // A single-block ADPCM stream stores its block size without the 4-byte ADPCM header.
            if (blockCount == 1 && type == 2) size += 4;
            int want = (int) Math.min(last ? lastSamples : blockSamples, samples - done);
            for (int c = 0; c < channels; c++) {
                if (pos >= d.length) break;
                int bytes = (int) Math.min(size, d.length - pos);
                decodeDs(d, (int) pos, bytes, type, pcm[c], done, want);
                pos += size;
            }
            pos = (pos + 3) & ~3L;                              // each group of blocks is padded to 4 bytes
            done += want;
        }
        return wav(pcm, rate);
    }

    private static void decodeDs(byte[] d, int at, int bytes, int type, short[] out, int done, int want) throws IOException {
        switch (type) {
            case 0: pcm8(d, at, bytes, out, done, want); break;
            case 1: pcm16(d, at, bytes, out, done, want); break;
            case 2: {
                if (bytes < 4) return;
                State s = new State();
                s.h1 = (short) u16(d, at); s.index = Math.min(88, d[at + 2] & 0x7f);
                ima(d, at + 4, bytes - 4, out, done, want, s);
                break;
            }
            default: throw new IOException("Unknown DS wave type " + type);
        }
    }

    /**
     * One DS sample: u8 type, u8 loop, u16 sampleRate, u16 timer, u16 loopStart (words), u32 length (words),
     * then the sample data. This is the body of a SWAV file and of every entry in a SWAR.
     */
    static byte[] swavSample(byte[] d, int at, int length) throws IOException {
        need(d, at, 12);
        int type = d[at] & 255, rate = u16(d, at + 2);
        int bytes = Math.min(length - 12, d.length - at - 12);
        if (bytes <= 0 || rate == 0) throw new IOException("Empty DS sample");
        int count = type == 0 ? bytes : type == 1 ? bytes / 2 : (bytes - 4) * 2;
        if (count <= 0 || count > MAX_SAMPLES) throw new IOException("Unreasonable DS sample length");
        short[][] pcm = new short[1][count];
        decodeDs(d, at + 12, bytes, type, pcm[0], 0, count);
        return wav(pcm, rate);
    }

    // ------------------------------------------------------------------ helpers

    private static void need(byte[] d, long offset, long length) throws IOException {
        if (offset < 0 || offset + length > d.length) throw new IOException("Audio header lies outside the file");
    }
    private static int u16(byte[] d, int o) { return (d[o] & 255) | (d[o + 1] & 255) << 8; }
    private static long u32(byte[] d, int o) { return u16(d, o) | ((long) u16(d, o + 2) << 16); }
    private static void le16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
    private static void le32(byte[] d, int o, int v) { le16(d, o, v); le16(d, o + 2, v >>> 16); }
    private static void put(byte[] d, int o, String s) { for (int i = 0; i < s.length(); i++) d[o + i] = (byte) s.charAt(i); }
}
