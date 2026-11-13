import com.retrotools.cciextractor.Ps3TrophyProgress;
import java.util.Arrays;

/** Synthetic check of TROPUSR.DAT handling. During development the writer reproduced a real RPCS3 file byte-for-byte. */
public class Ps3TrophyProgressTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) throws Exception {
        String conf = "<trophyconf>\n <trophy id=\"000\" hidden=\"no\" ttype=\"P\" pid=\"-1\"/>\n <trophy id=\"001\" hidden=\"no\" ttype=\"G\" pid=\"0\"/>\n"
                + " <trophy id=\"002\" hidden=\"yes\" ttype=\"S\" pid=\"0\"/>\n</trophyconf>";
        Ps3TrophyProgress p = Ps3TrophyProgress.forConfig(conf, null);
        byte[] d = p.toBytes();
        check(d.length == 0x70 + 3 * 0x60 + 3 * 0x70 && Ps3TrophyProgress.isProgress(d), "size and magic");
        p.unlock(2, 1000L);
        Ps3TrophyProgress back = Ps3TrophyProgress.parse(p.toBytes());
        check(back.trophies.size() == 3 && back.trophies.get(0).grade == 1 && back.trophies.get(1).grade == 2 && back.trophies.get(1).pid == 0
                && back.trophies.get(2).grade == 3 && back.trophies.get(2).unlocked && !back.trophies.get(1).unlocked, "round trip");
        check(back.trophies.get(2).timestamp == 62135596800000000L + 1000000L, "timestamp in PS3 ticks");
        String bigger = conf.replace("</trophyconf>", " <trophy id=\"003\" hidden=\"no\" ttype=\"B\" pid=\"-1\"/>\n</trophyconf>");
        Ps3TrophyProgress grown = Ps3TrophyProgress.forConfig(bigger, back);
        check(grown.trophies.size() == 4 && grown.trophies.get(2).unlocked && grown.trophies.get(2).timestamp == back.trophies.get(2).timestamp
                && !grown.trophies.get(3).unlocked && grown.trophies.get(3).grade == 4 && grown.trophies.get(3).pid == -1, "grow keeps earned trophies");
        check(Arrays.equals(Ps3TrophyProgress.parse(grown.toBytes()).toBytes(), grown.toBytes()), "stable");
        boolean threw = false;
        try { p.unlock(0, 0); } catch (java.io.IOException e) { threw = true; }
        check(threw, "platinum can't be unlocked directly");
        System.out.println("PS3 trophy progress (TROPUSR.DAT) tests passed");
    }
}
