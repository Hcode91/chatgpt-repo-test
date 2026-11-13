import com.retrotools.cciextractor.Ps3Trophy;
import com.retrotools.cciextractor.Ps3TrophyMod;
import com.retrotools.cciextractor.Ps3TrophyProgress;

import java.io.ByteArrayInputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Adds trophies to a synthetic pack, exports them as a mod, and applies the mod to a fresh copy. */
public class Ps3TrophyModTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static byte[] png(int seed) { byte[] d = new byte[64]; new java.util.Random(seed).nextBytes(d); d[0] = (byte) 0x89; d[1] = 'P'; d[2] = 'N'; d[3] = 'G'; return d; }
    static String sfm(boolean text) {
        StringBuilder sb = new StringBuilder("<!--Sce-Np-Trophy-Signature: 00-->\r\n<trophyconf version=\"1.1\">\r\n <npcommid>NPWR99999_00</npcommid>\r\n");
        if (text) sb.append(" <title-name>TEST GAME</title-name>\r\n");
        for (int i = 0; i < 2; i++) sb.append(String.format(" <trophy id=\"%03d\" hidden=\"no\" ttype=\"B\" pid=\"-1\"%s\r\n", i, text ? "><name>n</name><detail>d</detail></trophy>" : "/>"));
        return sb.append("</trophyconf>\r\n").toString();
    }
    static Ps3TrophyMod.Pack fresh() throws Exception {
        Path f = Files.createTempFile("trp", ".trp");
        try {
            java.util.List<Ps3Trophy.Source> src = new java.util.ArrayList<>();
            String[] names = {"TROPCONF.SFM", "TROP.SFM", "TROP_01.SFM", "ICON0.PNG", "TROP000.PNG", "TROP001.PNG"};
            for (String n : names) {
                byte[] d = n.endsWith(".SFM") ? sfm(!n.equals("TROPCONF.SFM")).getBytes(StandardCharsets.UTF_8) : png(n.hashCode());
                src.add(new Ps3Trophy.Source() { public String name() { return n; } public long size() { return d.length; } public java.io.InputStream open() { return new ByteArrayInputStream(d); } });
            }
            Files.write(f, Ps3Trophy.build(src, 3));
            try (FileChannel ch = FileChannel.open(f)) { return Ps3TrophyMod.Pack.load(ch); }
        } finally { Files.deleteIfExists(f); }
    }

    public static void main(String[] args) throws Exception {
        Ps3TrophyMod.Pack pack = fresh();
        check(pack.count() == 2 && pack.gameTitle().equals("TEST GAME") && pack.version == 3, "loaded");
        Ps3TrophyMod.Trophy a = new Ps3TrophyMod.Trophy();
        a.name = "Rock & <Roll>"; a.detail = "Win \"twice\"."; a.grade = 'G'; a.hidden = true; a.iconPng = png(1);
        Ps3TrophyMod.Trophy b = new Ps3TrophyMod.Trophy();
        b.name = "Second"; b.detail = ""; b.grade = 'S'; b.iconPng = png(2);
        check(pack.add(a) == 2 && pack.add(b) == 3, "ids continue from the pack");
        String conf = pack.conf(), text = new String(pack.files.get("TROP_01.SFM"), StandardCharsets.UTF_8);
        check(conf.contains(" <trophy id=\"002\" hidden=\"yes\" ttype=\"G\" pid=\"-1\"/>\r\n <trophy id=\"003\" hidden=\"no\" ttype=\"S\" pid=\"-1\"/>\r\n</trophyconf>"), "TROPCONF lines:\n" + conf);
        check(text.contains("<name>Rock &amp; &lt;Roll&gt;</name>") && text.contains("<detail>Win &quot;twice&quot;.</detail>") && text.contains("Sce-Np-Trophy-Signature"), "language file text, escaped");
        check(java.util.Arrays.equals(pack.files.get("TROP002.PNG"), png(1)) && pack.files.containsKey("TROP003.PNG"), "icons");

        // The rebuilt pack is valid and its checksum is right; the progress file grows and keeps earned trophies
        Path f = Files.createTempFile("trp", ".trp");
        try {
            Files.write(f, pack.toTrp());
            try (FileChannel ch = FileChannel.open(f)) {
                Ps3Trophy.Info info = Ps3Trophy.parse(ch);
                check(info.trophies == 4 && info.files.size() == 8 && info.hashValid, "rebuilt pack");
                check(info.files.get(info.files.size() - 1).name.equals("TROP003.PNG"), "icon order");
            }
        } finally { Files.deleteIfExists(f); }
        Ps3TrophyProgress old = Ps3TrophyProgress.forConfig(sfm(false), null);
        old.unlock(1, 5000);
        Ps3TrophyProgress grown = Ps3TrophyProgress.parse(pack.toTropusr(old.toBytes()));
        check(grown.trophies.size() == 4 && grown.trophies.get(1).unlocked && !grown.trophies.get(2).unlocked && grown.trophies.get(2).grade == 2 && grown.trophies.get(2).pid == -1, "progress file");
        check(Ps3TrophyProgress.parse(pack.toTropusr(null)).trophies.size() == 4, "fresh progress file");

        // Export → import → apply to a fresh pack gives the same result
        pack.added.title = "My pack"; pack.added.author = "me & you";
        byte[] zip = pack.added.toZip();
        Ps3TrophyMod mod = Ps3TrophyMod.fromZip(new ByteArrayInputStream(zip));
        check(mod.trophies.size() == 2 && mod.commId.equals("NPWR99999_00") && mod.baseCount == 2 && mod.author.equals("me & you")
                && mod.trophies.get(0).name.equals("Rock & <Roll>") && mod.trophies.get(0).hidden && mod.trophies.get(0).grade == 'G', "mod round trip");
        Ps3TrophyMod.Pack other = fresh();
        other.apply(mod);
        check(java.util.Arrays.equals(other.toTrp(), pack.toTrp()), "applying the mod reproduces the edited pack exactly");
        boolean threw = false;
        try { other.apply(mod); } catch (java.io.IOException e) { threw = true; }
        check(threw, "mod refused on an already modified pack");
        mod.commId = "NPWR00000_00";
        threw = false;
        try { fresh().apply(mod); } catch (java.io.IOException e) { threw = true; }
        check(threw, "mod refused on another game");
        Ps3TrophyMod.Trophy bad = new Ps3TrophyMod.Trophy();
        bad.name = "x"; bad.grade = 'P'; bad.iconPng = png(3);
        threw = false;
        try { fresh().add(bad); } catch (java.io.IOException e) { threw = true; }
        check(threw, "platinum can't be added");
        System.out.println("PS3 trophy editor and mod tests passed");
    }
}
