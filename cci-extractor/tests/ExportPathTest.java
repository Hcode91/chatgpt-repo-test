import com.retrotools.cciextractor.CciArchive;
public class ExportPathTest {
    static void expect(String raw,String title,String wanted) {
        String actual=CciArchive.exportPath(raw,title);
        if(!wanted.equals(actual))throw new AssertionError(actual+" != "+wanted);
    }
    public static void main(String[] args) {
        String id="0004000000112D00";
        expect("partition_0/romfs/system/TitleLogo.btex",id,id+"/romfs/system/TitleLogo.btex");
        expect("partition_1/romfs/manual.bin",id,"other_partitions/partition_1/romfs/manual.bin");
        expect("partition_0/exefs/icon",id,id+"/exefs/icon");
        expect("partition_0/romfs/x",null,"partition_0/romfs/x");
        try{CciArchive.exportPath("partition_0/../x",id);throw new AssertionError("bad path");}
        catch(IllegalArgumentException expected){}
        System.out.println("3DS Title ID layout passed");
    }
}
