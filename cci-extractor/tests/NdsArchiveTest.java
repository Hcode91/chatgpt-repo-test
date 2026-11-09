import com.retrotools.cciextractor.NdsArchive;
import java.nio.file.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class NdsArchiveTest {
  static void put16(byte[] b,int offset,int value){b[offset]=(byte)value;b[offset+1]=(byte)(value>>>8);}
  static void put32(byte[] b,int offset,int value){for(int j=0;j<4;j++)b[offset+j]=(byte)(value>>>(j*8));}
  static int crc16(byte[] b,int offset,int len){int c=0xffff;for(int j=0;j<len;j++){
    c^=b[offset+j]&255;for(int k=0;k<8;k++)c=((c&1)!=0)?(c>>>1)^0xa001:c>>>1;
  }return c&0xffff;}
  static byte[] fixture() {
    byte[] b=new byte[0x1000];
    System.arraycopy("TEST GAME".getBytes(),0,b,0,9);
    System.arraycopy("GAME".getBytes(),0,b,12,4);
    for(int i=0;i<156;i++) b[0xc0+i]=(byte)(i*7+3);
    put16(b,0x15c,crc16(b,0xc0,156));
    put32(b,0x40,0x300);put32(b,0x44,0x60);
    put32(b,0x48,0x400);put32(b,0x4c,0x18);
    // Root directory (FNT index 0): 1 file and a directory.
    put32(b,0x300,16);put16(b,0x304,0);put16(b,0x306,2);
    // Child directory (FNT index 1): file id 1.
    put32(b,0x308,37);put16(b,0x30c,1);put16(b,0x30e,0xf000);
    b[0x310]=8;System.arraycopy("root.bin".getBytes(),0,b,0x311,8);
    b[0x319]=(byte)(0x80|3);System.arraycopy("gfx".getBytes(),0,b,0x31a,3);
    put16(b,0x31d,0xf001);b[0x31f]=0;
    b[0x325]=7;System.arraycopy("pic.bin".getBytes(),0,b,0x326,7);b[0x32d]=0;
    put32(b,0x400,0x500);put32(b,0x404,0x506);
    put32(b,0x408,0x510);put32(b,0x40c,0x518);
    return b;
  }
  public static void main(String[] args) throws Exception {
    Path p=Files.createTempFile("nitrofs-test",".nds");try{
      byte[] b=fixture();Files.write(p,b);
      try(FileChannel ch=FileChannel.open(p)){
        if(!NdsArchive.isNds(ch))throw new AssertionError("DS recognition failed");
        NdsArchive.Info info=NdsArchive.parse(ch);
        if(info.entries.size()!=2)throw new AssertionError("Wrong file count " + info.entries.size());
        if(!info.entries.get(0).path.equals("nitrofs/root.bin") ||
          !info.entries.get(1).path.equals("nitrofs/gfx/pic.bin"))
          throw new AssertionError("Wrong paths: " + info.entries.get(0).path + " " + info.entries.get(1).path);
      }
      b[0x310]=3;b[0x311]='.';b[0x312]='.';b[0x313]='/';Files.write(p,b);
      try(FileChannel ch=FileChannel.open(p)){
        try{NdsArchive.parse(ch);throw new AssertionError("Unsafe path accepted");}
        catch(java.io.IOException ok){}
      }
      System.out.println("Nintendo DS NitroFS parsing and path validation passed");
    }finally{Files.deleteIfExists(p);}
  }
}
