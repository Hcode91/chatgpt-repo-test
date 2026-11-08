import com.retrotools.cciextractor.CciArchive;

public final class ExportPathTest {
    private static void expect(String original, String title, String expected) {
        String actual = CciArchive.exportPath(original, title);
        if (!expected.equals(actual))
            throw new AssertionError(original + ": expected " + expected + ", got " + actual);
    }
    private static void reject(String path, String title) {
        try { CciArchive.exportPath(path, title); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsafe path accepted: " + path);
    }
    public static void main(String[] args) {
        String id = "0004000000112D00";
        expect("partition_0/romfs/system/TitleLogo.btex", id,
               id + "/romfs/system/TitleLogo.btex");
        expect("partition_0/exefs/icon", id, id + "/exefs/icon");
        expect("partition_1/romfs/manual.bin", id,
               "other_partitions/partition_1/romfs/manual.bin");
        expect("partition_0/romfs/system/a.btex", null,
               "partition_0/romfs/system/a.btex");
        expect("partition_0/romfs/system/a.btex", "INVALID",
               "partition_0/romfs/system/a.btex");
        reject("partition_8/romfs/x", id);
        reject("partition_0/../bad", id);
        reject("partition_0/romfs\\\\bad", id);
        reject("romfs/x", id);
        System.out.println("Title ID export path tests passed");
    }
}
