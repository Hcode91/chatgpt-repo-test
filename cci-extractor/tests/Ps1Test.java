import com.retrotools.cciextractor.Ps1Assets;
import com.retrotools.cciextractor.Ps1Disc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

/**
 * Checks the PS1 disc reader and TIM/VAG decoders against tests/fixtures/ps1, a disc built by
 * make_fixture.py with an independent ISO 9660 writer (pycdlib). Expected values are in expected.txt.
 */
public class Ps1Test {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static String crc(byte[] d) { CRC32 c = new CRC32(); c.update(d); return String.format("%08x", c.getValue()); }
    static String crcArgb(int[] px) {
        ByteBuffer b = ByteBuffer.allocate(px.length * 4);
        for (int p : px) b.putInt(p);
        return crc(b.array());
    }
    static byte[] gunzip(Path p) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }
    static Map<String, byte[]> extractAll(Path image, Ps1Disc.Info[] infoOut) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (FileChannel ch = FileChannel.open(image)) {
            check(Ps1Disc.isDisc(ch), image + " detected as a disc");
            Ps1Disc.Info info = Ps1Disc.parse(ch);
            infoOut[0] = info;
            for (Ps1Disc.FileEntry f : info.files) out.put(f.path, Ps1Disc.read(ch, info.layout, f));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "tests/fixtures/ps1");
        byte[] raw = gunzip(dir.resolve("test-disc.bin.gz")), iso = gunzip(dir.resolve("test-disc.iso.gz"));
        // 2336-byte Mode 2 variant: the same sectors without sync and header
        ByteArrayOutputStream m2 = new ByteArrayOutputStream();
        for (int s = 0; s < raw.length / 2352; s++) m2.write(raw, s * 2352 + 16, 2336);
        Path rawFile = Files.createTempFile("ps1", ".bin"), isoFile = Files.createTempFile("ps1", ".iso"), m2File = Files.createTempFile("ps1", ".img");
        try {
            Files.write(rawFile, raw); Files.write(isoFile, iso); Files.write(m2File, m2.toByteArray());
            Ps1Disc.Info[] info = new Ps1Disc.Info[1];
            Map<String, byte[]> files = extractAll(rawFile, info);
            check(info[0].layout.sectorSize == 2352 && info[0].layout.dataOffset == 24, "raw Mode 2 layout");
            check(info[0].isPlayStation(), "system id PLAYSTATION");
            Map<String, Boolean> form2 = new HashMap<>();
            for (Ps1Disc.FileEntry f : info[0].files) form2.put(f.path, f.form2);

            int checked = 0;
            for (String line : Files.readAllLines(dir.resolve("expected.txt"))) {
                String[] t = line.split(" ");
                switch (t[0]) {
                    case "file":
                        check(files.containsKey(t[1]), "file listed: " + t[1]);
                        check(files.get(t[1]).length == Integer.parseInt(t[2]) && crc(files.get(t[1])).equals(t[3]), "file content: " + t[1]);
                        check(!form2.get(t[1]), "not Form 2: " + t[1]);
                        break;
                    case "form2":
                        check(form2.get(t[1]), "Form 2 detected: " + t[1]);
                        check(files.get(t[1]).length == Integer.parseInt(t[2]) && crc(files.get(t[1])).equals(t[3]), "raw sectors: " + t[1]);
                        break;
                    case "tim": {
                        List<Ps1Assets.Image> images = Ps1Assets.decodeTim(files.get(t[1]), 0);
                        int clut = Integer.parseInt(t[2].substring(4));
                        check(clut < images.size(), "palette " + clut + " of " + t[1]);
                        Ps1Assets.Image img = images.get(clut);
                        check((img.width + "x" + img.height).equals(t[3]), "TIM size " + t[1]);
                        check(crcArgb(img.argb).equals(t[4]), "TIM pixels " + t[1] + " palette " + clut);
                        break;
                    }
                    case "scan": {
                        List<Ps1Assets.Found> found = Ps1Assets.scanTims(files.get(t[1]));
                        check(found.size() == 2 && found.get(0).offset == Integer.parseInt(t[2]) && found.get(1).offset == Integer.parseInt(t[3]),
                                "embedded TIMs found at " + t[2] + ", " + t[3] + " (got " + found.size() + ")");
                        break;
                    }
                    case "vag": {
                        byte[] wav = Ps1Assets.vagToWav(files.get(t[1]));
                        int rate = ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                        byte[] pcm = java.util.Arrays.copyOfRange(wav, 44, wav.length);
                        check(rate == Integer.parseInt(t[2]) && pcm.length / 2 == Integer.parseInt(t[3]), "VAG rate/length " + rate + " " + pcm.length / 2);
                        check(crc(pcm).equals(t[4]), "VAG samples");
                        break;
                    }
                    case "boot": check(t[1].equals(info[0].bootFile), "boot file " + info[0].bootFile); break;
                    case "volume": check(t[1].equals(info[0].volumeId), "volume id " + info[0].volumeId); break;
                    default: throw new AssertionError("unknown expectation " + line);
                }
                checked++;
            }

            // 2048-byte .iso: same data files; Form 2 files can only be read as their 2048-byte extents
            Map<String, byte[]> isoFiles = extractAll(isoFile, info);
            check(info[0].layout.sectorSize == 2048, "ISO layout");
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                if (e.getKey().startsWith("MOVIE/")) check(isoFiles.get(e.getKey()).length == 4096, "ISO Form 2 extent");
                else check(java.util.Arrays.equals(isoFiles.get(e.getKey()), e.getValue()), "ISO matches raw: " + e.getKey());
            }
            // 2336-byte Mode 2: identical output, Form 2 sectors rebuilt to 2352 bytes
            Map<String, byte[]> m2Files = extractAll(m2File, info);
            check(info[0].layout.sectorSize == 2336, "2336 layout");
            for (Map.Entry<String, byte[]> e : files.entrySet())
                check(java.util.Arrays.equals(m2Files.get(e.getKey()), e.getValue()), "2336 image matches raw: " + e.getKey());

            // Things that aren't PS1 discs
            byte[] notDisc = new byte[64 * 2352];
            System.arraycopy("SEGA MEGA DRIVE ".getBytes(), 0, notDisc, 0x100, 16);
            Files.write(isoFile, notDisc);
            try (FileChannel ch = FileChannel.open(isoFile)) { check(!Ps1Disc.isDisc(ch), "Mega Drive-like file is not a disc"); }
            check(!Ps1Assets.isTim(new byte[]{0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}), "empty TIM rejected");
            // Corrupt disc: directory pointing outside the image must fail cleanly
            byte[] broken = raw.clone();
            int pvd = 16 * 2352 + 24;
            broken[pvd + 156 + 2] = (byte) 0xF0; broken[pvd + 156 + 3] = (byte) 0xFF;
            Files.write(isoFile, broken);
            boolean threw = false;
            try (FileChannel ch = FileChannel.open(isoFile)) { Ps1Disc.parse(ch); } catch (java.io.IOException expected) { threw = true; }
            check(threw, "corrupt root directory reports an error");
            System.out.println("PS1 disc (2352/2336/2048), TIM and VAG tests passed (" + checked + " expectations)");
        } finally {
            Files.deleteIfExists(rawFile); Files.deleteIfExists(isoFile); Files.deleteIfExists(m2File);
        }
    }
}
