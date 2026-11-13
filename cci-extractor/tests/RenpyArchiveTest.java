import com.retrotools.cciextractor.RenpyArchive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Reads the archives in tests/fixtures/rpa (indexes written by Python's own pickle module, see make_fixture.py)
 * and checks every file, then packs files with the writer and reads them back.
 */
public class RenpyArchiveTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static String crc(byte[] d) { CRC32 c = new CRC32(); c.update(d); return String.format("%08x", c.getValue()); }

    static Map<String, byte[]> readAll(Path archive, Path rpiOrNull) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (FileChannel ch = FileChannel.open(archive)) {
            RenpyArchive.Info info;
            if (rpiOrNull != null) try (FileChannel rpi = FileChannel.open(rpiOrNull)) {
                check(RenpyArchive.isRpi(rpi), "RPA-1.0 index detected");
                info = RenpyArchive.parseRpi(rpi, ch.size());
            } else {
                check(RenpyArchive.isArchive(ch), archive + " detected");
                info = RenpyArchive.parse(ch);
            }
            for (RenpyArchive.FileEntry f : info.files) out.put(f.path, RenpyArchive.read(ch, f));
        }
        return out;
    }

    static RenpyArchive.Source source(String path, byte[] data) {
        return new RenpyArchive.Source() {
            public String path() { return path; }
            public long size() { return data.length; }
            public InputStream open() { return new ByteArrayInputStream(data); }
        };
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "tests/fixtures/rpa");
        Map<String, Map<String, byte[]>> archives = new HashMap<>();
        int checked = 0;
        for (String line : Files.readAllLines(dir.resolve("expected.txt"))) {
            String[] t = line.split(" ");
            String name = t[0], path = t[1].replace("%20", " ");
            Map<String, byte[]> files = archives.get(name);
            if (files == null) {
                files = name.endsWith(".rpi") ? readAll(dir.resolve(name.replace(".rpi", ".rpa")), dir.resolve(name)) : readAll(dir.resolve(name), null);
                archives.put(name, files);
            }
            check(files.containsKey(path), name + ": " + path + " listed (have " + files.keySet() + ")");
            byte[] d = files.get(path);
            check(d.length == Integer.parseInt(t[2]) && crc(d).equals(t[3]), name + ": " + path + " content");
            checked++;
        }
        try (FileChannel ch = FileChannel.open(dir.resolve("v3-proto2.rpa"))) {
            check("RPA-3.0".equals(RenpyArchive.version(ch)), "version string");
        }

        // Writer round trip, including an empty file, nested and non-ASCII paths, and a > 2 GB-style offset value
        List<RenpyArchive.Source> sources = new ArrayList<>();
        Map<String, byte[]> wanted = new HashMap<>();
        java.util.Random r = new java.util.Random(3);
        for (String p : new String[]{"images/bg.png", "audio/music.ogg", "empty.txt", "fonts/été.ttf", "a.rpyc"}) {
            byte[] d = new byte[p.equals("empty.txt") ? 0 : 100 + r.nextInt(3000)];
            r.nextBytes(d);
            sources.add(source(p, d)); wanted.put(p, d);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long size = RenpyArchive.write(sources, out, null);
        byte[] built = out.toByteArray();
        check(size == built.length, "reported size");
        String header = new String(built, 0, 34, StandardCharsets.ISO_8859_1);
        check(header.matches("RPA-3\\.0 [0-9a-f]{16} 42424242\n"), "Ren'Py header format: " + header);
        check(new String(built, 34, 17, StandardCharsets.ISO_8859_1).equals("Made with Ren'Py."), "padding like Ren'Py's archiver");
        Path tmp = Files.createTempFile("rpa", ".rpa");
        try {
            Files.write(tmp, built);
            Map<String, byte[]> back = readAll(tmp, null);
            check(back.keySet().equals(wanted.keySet()), "round trip file list");
            for (String p : wanted.keySet()) check(java.util.Arrays.equals(back.get(p), wanted.get(p)), "round trip " + p);
            // A source whose size changes while packing must fail, not write a broken archive silently
            List<RenpyArchive.Source> liar = new ArrayList<>();
            liar.add(new RenpyArchive.Source() {
                public String path() { return "x.bin"; }
                public long size() { return 10; }
                public InputStream open() { return new ByteArrayInputStream(new byte[7]); }
            });
            boolean threw = false;
            try { RenpyArchive.write(liar, new ByteArrayOutputStream(), null); } catch (java.io.IOException expected) { threw = true; }
            check(threw, "size mismatch reported");
            // Corrupt key: offsets land outside the file → clean error
            byte[] broken = built.clone();
            broken[26] = 'f';
            Files.write(tmp, broken);
            threw = false;
            try (FileChannel ch = FileChannel.open(tmp)) { RenpyArchive.parse(ch); } catch (java.io.IOException expected) { threw = true; }
            check(threw, "wrong key reported");
            // ZiX archives explain why they can't be opened
            Files.write(tmp, "ZiX-12A 0000000000000000 00000000\n".getBytes(StandardCharsets.ISO_8859_1));
            threw = false;
            try (FileChannel ch = FileChannel.open(tmp)) { RenpyArchive.parse(ch); } catch (java.io.IOException expected) { threw = expected.getMessage().contains("loader.pyo"); }
            check(threw, "ZiX explained");
        } finally { Files.deleteIfExists(tmp); }
        System.out.println("Ren'Py archive tests passed (" + checked + " files across " + archives.size() + " archives, plus writer round trip)");
    }
}
