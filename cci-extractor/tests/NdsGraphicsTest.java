import com.retrotools.cciextractor.NdsGraphics;
import java.util.Arrays;

public class NdsGraphicsTest {
  static void put16(byte[] x,int pos,int val){x[pos]=(byte)val;x[pos+1]=(byte)(val>>>8);}
  static void put32(byte[] x,int pos,int val){for(int k=0;k<4;k++)x[pos+k]=(byte)(val>>>(8*k));}
  static void put(byte[] data,int pos,String val){System.arraycopy(val.getBytes(),0,data,pos,val.length());}
  static byte[] palette(){byte[] b=new byte[0x28+512];put(b,0,"RLCN");put(b,0x10,"TTLP");put32(b,0x20,512);put32(b,0x24,0x10);
    for(int n=0;n<256;n++)put16(b,0x28+n*2,((n&31)<<10)|((n&31)<<5)|(n&31));return b;}
  static byte[] image(boolean is4){int tileSize=is4?32:64;byte[] b=new byte[0x30+tileSize];put(b,0,"RGCN");put(b,0x10,"RAHC");
    put16(b,0x18,1);put16(b,0x1a,1);put32(b,0x1c,is4?3:4);put32(b,0x28,tileSize);
    for(int i=0;i<tileSize;i++)b[0x30+i]=(byte)(is4?0x12:i);return b;}
  public static void main(String[] args)throws Exception{
    NdsGraphics.Image four=NdsGraphics.decode(image(true),palette());
    if(four.width!=8||four.height!=8||four.argb[0]==four.argb[1])throw new AssertionError("4bpp NCGR");
    NdsGraphics.Image eight=NdsGraphics.decode(image(false),palette());
    if(eight.width!=8||eight.height!=8||eight.argb[0]==eight.argb[1])throw new AssertionError("8bpp NCGR");
    try{NdsGraphics.decode(image(true),new byte[20]);throw new AssertionError("Truncated palette accepted");}
    catch(java.io.IOException expected){}
    System.out.println("Nintendo DS NCGR 4bpp/8bpp + NCLR previews passed");
  }
}
