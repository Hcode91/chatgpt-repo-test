import com.retrotools.cciextractor.Ps1Disc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

/**
 * Putting a changed file back into a PS1 disc image (used for Metal Gear Solid's STAGE.DIR):
 *  - EDC/ECC of fixed Mode 1 and Mode 2 Form 1 sectors match values computed with Neill Corlett's
 *    ECM tools (reconstruct_sector), an independent implementation;
 *  - replacing a file in the test disc (tests/fixtures/ps1, built with pycdlib) changes only that
 *    file's sectors, the file reads back as the new data, and its sectors carry valid codes.
 */
public class Ps1ReplaceTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static byte[] sector(int mode) {
        byte[] s = new byte[2352];
        for (int i = 0; i < s.length; i++) s[i] = (byte) (i * 31 + 7);
        s[0] = 0;
        for (int i = 1; i < 11; i++) s[i] = (byte) 0xff;
        s[11] = 0;
        s[12] = 0x00; s[13] = 0x02; s[14] = 0x16; s[15] = (byte) mode;
        if (mode == 2) { byte[] sub = {0, 0, 8, 0, 0, 0, 8, 0}; System.arraycopy(sub, 0, s, 0x10, 8); }
        return s;
    }

    static long crc(byte[] b, int off, int len) { CRC32 c = new CRC32(); c.update(b, off, len); return c.getValue(); }

    static void fix(byte[] s) throws Exception {
        java.lang.reflect.Method m = Ps1Disc.class.getDeclaredMethod("fixSector", byte[].class);
        m.setAccessible(true);
        m.invoke(null, (Object) s);
    }

    public static void main(String[] args) throws Exception {
        // Reference values from ECM's reconstruct_sector on the same two sectors
        byte[] m2 = sector(2), m1 = sector(1);
        fix(m2); fix(m1);
        check(crc(m2, 0, 2352) == 0x1048af35L, "Mode 2 Form 1 EDC/ECC: " + Long.toHexString(crc(m2, 0, 2352)));
        check(crc(m1, 0, 2352) == 0xe62b18e7L, "Mode 1 EDC/ECC: " + Long.toHexString(crc(m1, 0, 2352)));

        Path dir = Paths.get(args.length > 0 ? args[0] : "tests/fixtures/ps1");
        byte[] disc;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(dir.resolve("test-disc.bin.gz")))) { disc = in.readAllBytes(); }
        Path tmp = Files.createTempDirectory("ps1replace");
        Path original = tmp.resolve("disc.bin"), replacement = tmp.resolve("new.dat"), edited = tmp.resolve("edited.bin");
        Files.write(original, disc);

        try (FileChannel ch = FileChannel.open(original)) {
            Ps1Disc.Info info = Ps1Disc.parse(ch);
            Ps1Disc.FileEntry target = null;
            for (Ps1Disc.FileEntry f : info.files) if (!f.form2 && f.size % 2048 != 0 && (target == null || f.size > target.size)) target = f;
            check(target != null, "a data file with a partial last sector in the fixture");
            byte[] data = new byte[(int) target.size];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 13 + 5);
            Files.write(replacement, data);

            try (FileChannel nc = FileChannel.open(replacement); java.io.OutputStream out = Files.newOutputStream(edited)) {
                Ps1Disc.replaceFile(ch, info.layout, target, nc, out, null);
            }
            byte[] result = Files.readAllBytes(edited);
            check(result.length == disc.length, "same size");
            try (FileChannel ec = FileChannel.open(edited)) {
                Ps1Disc.Info again = Ps1Disc.parse(ec);
                Ps1Disc.FileEntry back = null;
                for (Ps1Disc.FileEntry f : again.files) if (f.path.equals(target.path)) back = f;
                check(back != null && java.util.Arrays.equals(Ps1Disc.read(ec, again.layout, back), data), "file reads back as the new data");
            }
            long first = target.lba, last = target.lba + target.sectors();
            for (long s = 0; s < disc.length / 2352; s++) {
                int at = (int) (s * 2352);
                if (s < first || s >= last) {
                    check(java.util.Arrays.equals(java.util.Arrays.copyOfRange(disc, at, at + 2352),
                            java.util.Arrays.copyOfRange(result, at, at + 2352)), "sector " + s + " untouched");
                } else {
                    byte[] sec = java.util.Arrays.copyOfRange(result, at, at + 2352);
                    byte[] again = sec.clone();
                    fix(again);
                    check(java.util.Arrays.equals(sec, again), "sector " + s + " carries valid EDC/ECC");
                    check(java.util.Arrays.equals(java.util.Arrays.copyOfRange(disc, at, at + 24), java.util.Arrays.copyOfRange(sec, 0, 24)),
                            "sector " + s + " keeps its sync, address and subheader");
                }
            }
            // A file of a different size is refused
            Files.write(replacement, new byte[data.length + 1]);
            try (FileChannel nc = FileChannel.open(replacement)) {
                Ps1Disc.replaceFile(ch, info.layout, target, nc, new ByteArrayOutputStream(), null);
                check(false, "different size accepted");
            } catch (java.io.IOException expected) { }
        }
        System.out.println("Ps1ReplaceTest OK");
    }
}
