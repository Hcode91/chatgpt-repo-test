import com.retrotools.cciextractor.MgsVox;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Synthetic Metal Gear Solid VOX file (no game data): a mono clip with subtitles split across two sound chunks,
 * a stereo clip, and an empty padding sector between entries. The layout was worked out from a retail file;
 * the real file was used to check the converter during development but can't be included here.
 */
public class MgsVoxTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static final class B {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        B u8(int v) { o.write(v); return this; }
        B u16(int v) { u8(v); u8(v >> 8); return this; }
        B u32(long v) { u16((int) v); u16((int) (v >> 16)); return this; }
        B raw(byte[] b) { o.write(b, 0, b.length); return this; }
        B chunk(int type, byte[] body) { u8(type); int n = body.length + 4; u8(n); u8(n >> 8); u8(n >> 16); return raw(body); }
        B pad(int align) { while (o.size() % align != 0) o.write(0); return this; }
        int size() { return o.size(); }
        byte[] done() { return o.toByteArray(); }
    }

    /** Frames whose samples are exactly a constant: filter 0, shift 12 - log2 step, all nibbles equal. */
    static byte[] constantFrames(int frames, int nibble) {
        byte[] d = new byte[frames * 16];
        for (int f = 0; f < frames; f++) {
            d[f * 16] = 0x08;                 // filter 0, shift 8 → sample = nibble << 4
            for (int i = 2; i < 16; i++) d[f * 16 + i] = (byte) (nibble | nibble << 4);
        }
        return d;
    }
    static byte[] header(int channels) {
        return new B().u32(0x30F71D00L).u32(0x0008FF3F).u8(channels).u8(1).u16(0).u32(0).done();
    }
    static byte[] subtitles(Object[][] lines) {
        B recs = new B();
        for (Object[] l : lines) {
            byte[] text = ((String) l[2]).getBytes(StandardCharsets.ISO_8859_1);
            int len = 16 + ((text.length + 4) & ~3);
            recs.u32(len).u32((Integer) l[0]).u32((Integer) l[1]).u32(0).raw(text);
            for (int i = text.length; i < len - 16; i++) recs.u8(0);
        }
        byte[] r = recs.done();
        int lipOffset = 0x10, subOffset = 0x10 + 8;
        return new B().u32(0).u32(1000).u16(lipOffset).u16(subOffset).u32(subOffset + r.length).raw(new byte[8]).raw(r).done();
    }
    static int pcmAt(byte[] wav, int frame, int channel) {
        int channels = wav[22];
        return ByteBuffer.wrap(wav, 44 + (frame * channels + channel) * 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }

    public static void main(String[] args) throws Exception {
        B f = new B();
        // Entry 0: mono, subtitles, two sound chunks (3 and 2 frames: 140 samples), value 5<<4 = 80
        f.chunk(0x10, new B().u32(1).done()).chunk(0x02, header(1)).chunk(0x10, new B().u32(3).done()).chunk(0x04, new B().u32(1).done());
        f.chunk(0x03, subtitles(new Object[][]{{0, 10, "It's been a long time."}, {25, 5, "First line\u0080|second line"}}));
        f.chunk(0x01, constantFrames(3, 5)).chunk(0x01, constantFrames(2, 5)).chunk(0xF0, new byte[0]).pad(0x800);
        f.raw(new byte[0x800]);                                     // empty sector between entries
        // Entry 1: stereo, each sound chunk = left half (value 3<<4) then right half (value -2<<4)
        f.chunk(0x10, new B().u32(1).done()).chunk(0x02, header(2));
        for (int c = 0; c < 2; c++) f.chunk(0x01, new B().raw(constantFrames(2, 3)).raw(constantFrames(2, 14)).done());
        f.chunk(0xF0, new byte[0]).pad(0x800);

        // DEMO.DAT-style file: entry opens with 0x10 = 5 and carries numbered 0x05 animation chunks
        B demo = new B();
        demo.chunk(0x10, new B().u32(5).done());
        for (int i = 0; i < 3; i++) demo.chunk(0x05, new B().u32(i).u32(2).u32(1).raw(new byte[20]).done());
        demo.chunk(0x10, new B().u32(1).done()).chunk(0x02, header(2));
        demo.chunk(0x01, new B().raw(constantFrames(1, 3)).raw(constantFrames(1, 14)).done()).chunk(0x05, new byte[24]);
        demo.chunk(0xF0, new byte[0]).pad(0x800);

        Path file = Files.createTempFile("vox", ".dat");
        Files.write(file, demo.done());
        try (FileChannel ch = FileChannel.open(file)) {
            check(MgsVox.isVox(ch), "DEMO.DAT layout detected");
            MgsVox.Info info = MgsVox.parse(ch);
            check(info.entries.size() == 1 && info.cutscenes == 1 && info.entries.get(0).name().equals("demo_0000"), "cutscene entry");
            MgsVox.Clip c = MgsVox.convert(ch, info.entries.get(0));
            check(c.wav[22] == 2 && (c.wav.length - 44) / 4 == 28 && pcmAt(c.wav, 0, 0) == 48 && pcmAt(c.wav, 27, 1) == -32,
                    "animation chunks skipped, stereo audio intact");
        }
        // An unknown chunk type in the first entry means it isn't this format
        byte[] odd = demo.done();
        odd[8] = 0x55;
        Files.write(file, odd);
        try (FileChannel ch = FileChannel.open(file)) { check(!MgsVox.isVox(ch), "unknown chunk type rejected"); }

        try {
            Files.write(file, f.done());
            try (FileChannel ch = FileChannel.open(file)) {
                check(MgsVox.isVox(ch), "VOX detected");
                MgsVox.Info info = MgsVox.parse(ch);
                check(info.entries.size() == 2 && info.stereo == 1 && info.withSubtitles == 1, "two entries, one stereo, one with subtitles");
                MgsVox.Entry mono = info.entries.get(0), stereo = info.entries.get(1);
                check(mono.channels == 1 && mono.audioBytes == 80 && stereo.channels == 2 && stereo.audioBytes == 128, "entry sizes");
                check(MgsVox.firstLine(ch, mono).equals("It's been a long time."), "first line");

                MgsVox.Clip a = MgsVox.convert(ch, mono);
                check(a.wav[22] == 1 && ByteBuffer.wrap(a.wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == 22050, "mono 22050 Hz WAV");
                check((a.wav.length - 44) / 2 == 140 && pcmAt(a.wav, 0, 0) == 80 && pcmAt(a.wav, 139, 0) == 80, "mono samples");
                List<MgsVox.Subtitle> subs = a.subtitles;
                check(subs.size() == 2 && subs.get(1).text.equals("First line\nsecond line"), "subtitle text and line break");
                String srt = MgsVox.srt(subs);
                // 25 ticks × 896 samples / 22050 Hz = 1.016 s; 30 ticks = 1.219 s
                check(srt.contains("00:00:01,016 --> 00:00:01,219") && srt.contains("First line\r\nsecond line"), "SRT timing:\n" + srt);

                MgsVox.Clip b = MgsVox.convert(ch, stereo);
                check(b.wav[22] == 2 && (b.wav.length - 44) / 4 == 112, "stereo WAV, 4 frames per channel");
                check(pcmAt(b.wav, 0, 0) == 48 && pcmAt(b.wav, 0, 1) == -32 && pcmAt(b.wav, 111, 0) == 48 && pcmAt(b.wav, 111, 1) == -32,
                        "left/right halves " + pcmAt(b.wav, 0, 0) + " " + pcmAt(b.wav, 0, 1));
                check(b.subtitles.isEmpty(), "no subtitles in stereo clip");
            }
            // Broken chunk length → clean error
            byte[] broken = f.done();
            broken[0x1C + 1] = (byte) 0xFF; broken[0x1C + 2] = (byte) 0xFF; broken[0x1C + 3] = (byte) 0x7F;
            Files.write(file, broken);
            boolean threw = false;
            try (FileChannel ch = FileChannel.open(file)) { MgsVox.parse(ch); } catch (java.io.IOException expected) { threw = true; }
            check(threw, "broken chunk reports an error");
            Files.write(file, new byte[0x1000]);
            try (FileChannel ch = FileChannel.open(file)) { check(!MgsVox.isVox(ch), "other files are not VOX"); }
        } finally { Files.deleteIfExists(file); }
        System.out.println("Metal Gear Solid VOX tests passed");
    }
}
