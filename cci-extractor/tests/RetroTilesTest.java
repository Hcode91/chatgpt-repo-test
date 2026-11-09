import com.retrotools.cciextractor.RetroTiles;
import java.util.Random;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.*;

public class RetroTilesTest {
    public static void main(String[] args) throws Exception {
        Random random=new Random(1045);
        for(RetroTiles.Mode mode:RetroTiles.Mode.values()) {
            byte[] input=new byte[mode.pageBytes()];random.nextBytes(input);
            int[] pixels=RetroTiles.decode(input,mode);
            byte[] encoded=RetroTiles.encode(pixels,mode.width(),mode.height(),mode);
            if(!java.util.Arrays.equals(input,encoded))throw new AssertionError("tile roundtrip: "+mode);
            try {RetroTiles.encode(pixels,mode.width()+1,mode.height(),mode);throw new AssertionError("Accepted wrong dimensions");}
            catch(IOException good) {}
            pixels[0]=0xffff00ff;
            try {RetroTiles.encode(pixels,mode.width(),mode.height(),mode);throw new AssertionError("Accepted nonindexed pixel");}
            catch(IOException good) {}
            System.out.println("Passed " + mode.slug);
        }
        Path p=Files.createTempFile("test-mega-drive",".bin");
        byte[] md=new byte[8192];System.arraycopy("SEGA MEGA DRIVE".getBytes(),0,md,0x100,15);
        Files.write(p,md);
        try(FileChannel ch=FileChannel.open(p)) {
            RetroTiles.Info i=RetroTiles.parse(ch,RetroTiles.System.MEGADRIVE,p.toString());
            if(i.pageCount(RetroTiles.Mode.MD_4BPP)!=2) throw new AssertionError("MD pages");
            byte[] repl=new byte[4096];java.util.Arrays.fill(repl,(byte)0x55);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            RetroTiles.rebuild(ch,out,i,RetroTiles.Mode.MD_4BPP,1,repl);
            byte[] rebuilt=out.toByteArray();
            if(rebuilt.length!=md.length||rebuilt[0]!=md[0]||rebuilt[4096]!=(byte)0x55)
                throw new AssertionError("MD rebuilt incorrectly");
        } finally {Files.deleteIfExists(p);}
    }
}
