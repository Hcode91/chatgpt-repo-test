import com.retrotools.cciextractor.Ps3Trophy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Synthetic PS3 trophy pack (no game data). During development the writer also rebuilt a retail TROPHY.TRP
 * byte-for-byte from its extracted files; that file can't be included here.
 */
public class Ps3TrophyTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static Ps3Trophy.Source src(String name, byte[] d) {
        return new Ps3Trophy.Source() {
            public String name() { return name; }
            public long size() { return d.length; }
            public InputStream open() { return new ByteArrayInputStream(d); }
        };
    }
    static byte[] xml(boolean text) {
        StringBuilder sb = new StringBuilder("<!--Sce-Np-Trophy-Signature: 00-->\n<trophyconf version=\"1.1\">\n <npcommid>NPWR99999_00</npcommid>\n");
        if (text) sb.append(" <title-name>TEST GAME</title-name>\n");
        for (int i = 0; i < 3; i++) sb.append(String.format(" <trophy id=\"%03d\" hidden=\"no\" ttype=\"B\" pid=\"-1\"/>\n", i));
        return sb.append("</trophyconf>\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        java.util.Random r = new java.util.Random(5);
        List<Ps3Trophy.Source> in = new ArrayList<>();
        String[] names = {"TROP010.PNG", "ICON0.PNG", "TROP.SFM", "TROP000.PNG", "TROP_01.SFM", "TROPCONF.SFM", "TROP001.PNG"};
        java.util.Map<String, byte[]> data = new java.util.HashMap<>();
        for (String n : names) {
            byte[] d = n.endsWith(".SFM") ? xml(!n.equals("TROPCONF.SFM")) : new byte[100 + r.nextInt(500)];
            if (!n.endsWith(".SFM")) r.nextBytes(d);
            data.put(n, d); in.add(src(n, d));
        }
        for (int version : new int[]{1, 3}) {
            byte[] pack = Ps3Trophy.build(in, version);
            ByteBuffer b = ByteBuffer.wrap(pack);
            check(b.getInt(0) == 0xDCA24D00 && b.getInt(4) == version && b.getLong(8) == pack.length && b.getInt(16) == 7 && b.getInt(20) == 0x40, "header");
            check(pack.length % 16 == 0, "file size padded to 16");
            String[] order = {"TROPCONF.SFM", "TROP.SFM", "TROP_01.SFM", "ICON0.PNG", "TROP000.PNG", "TROP001.PNG", "TROP010.PNG"};
            long expectedOffset = 0x40 + 7 * 0x40;
            for (int i = 0; i < 7; i++) {
                int e = 0x40 + i * 0x40;
                String name = new String(pack, e, 32, StandardCharsets.US_ASCII).replace("\0", "");
                long off = b.getLong(e + 32), size = b.getLong(e + 40);
                int flag = b.getInt(e + 48);
                check(name.equals(order[i]), "order " + i + ": " + name);
                check(off == expectedOffset && off % 16 == 0, "offset " + name);
                check(flag == (name.endsWith(".SFM") ? 1 : 0), "flag " + name);
                check(Arrays.equals(Arrays.copyOfRange(pack, (int) off, (int) (off + size)), data.get(name)), "data " + name);
                expectedOffset = (off + size + 15) & ~15L;
            }
            byte[] zeroed = pack.clone();
            Arrays.fill(zeroed, 0x1C, 0x30, (byte) 0);
            byte[] want = version >= 2 ? MessageDigest.getInstance("SHA-1").digest(zeroed) : new byte[20];
            check(Arrays.equals(Arrays.copyOfRange(pack, 0x1C, 0x30), want), "checksum field v" + version);

            Path f = Files.createTempFile("trp", ".trp");
            try {
                Files.write(f, pack);
                try (FileChannel ch = FileChannel.open(f)) {
                    check(Ps3Trophy.isTrophyPack(ch), "detected");
                    Ps3Trophy.Info info = Ps3Trophy.parse(ch);
                    check(info.files.size() == 7 && info.trophies == 3 && info.title.equals("TEST GAME") && info.commId.equals("NPWR99999_00"), "info");
                    check(info.hashed == (version >= 2) && (version < 2 || info.hashValid), "checksum verified");
                }
                if (version >= 2) {
                    pack[(int) b.getLong(0x40 + 3 * 0x40 + 32)] ^= 1;          // flip a byte in ICON0.PNG
                    Files.write(f, pack);
                    try (FileChannel ch = FileChannel.open(f)) { check(!Ps3Trophy.parse(ch).hashValid, "tampering detected"); }
                }
            } finally { Files.deleteIfExists(f); }
        }
        boolean threw = false;
        try { Ps3Trophy.build(List.of(src("THIS_NAME_IS_FAR_TOO_LONG_FOR_A_TRP.PNG", new byte[1])), 1); } catch (java.io.IOException e) { threw = true; }
        check(threw, "long names rejected");
        System.out.println("PS3 trophy pack tests passed");
    }
}
