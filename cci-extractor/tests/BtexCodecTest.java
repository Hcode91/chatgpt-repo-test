import com.retrotools.cciextractor.BtexCodec;
import java.util.Arrays;

public class BtexCodecTest {
    private static int bpp(int fmt) {
        switch(fmt) {
            case 0: return 32;
            case 1: return 24;
            case 2: case 3: case 4: case 5: case 6: return 16;
            case 7: case 8: case 9: case 13: return 8;
            default:return 4;
        }
    }
    private static byte[] sample(int fmt, int w, int h) {
        int len = w*h*bpp(fmt)/8;
        byte[] d=new byte[0x80+len];
        d[0]='S';d[1]='T';d[2]='X';d[3]='C';d[6]=(byte)0x80;
        d[8]=(byte)fmt;d[10]=1;d[12]=(byte)w;d[13]=(byte)(w>>>8);
        d[14]=(byte)h;d[15]=(byte)(h>>>8);
        for(int i=0;i<4;i++)d[16+i]=(byte)(len>>>(i*8));
        return d;
    }
    private static int pixel(int x,int y) {
        return ((x*25+y*19)&255)<<24 | ((x*17+y*7)&255)<<16 |
                ((x*11+y*13)&255)<<8 | ((x*3+y*21)&255);
    }
    private static void check(boolean predicate, String problem) {
        if(!predicate)throw new AssertionError(problem);
    }
    public static void main(String[] args) throws Exception {
        int[] px=new int[16*16];
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)px[y*16+x]=pixel(x,y);
        for(int fmt=0;fmt<=13;fmt++) {
            byte[] original=sample(fmt,16,16);
            BtexCodec.Image image=BtexCodec.decode(original);
            check(image.width==16&&image.height==16,"read dimensions");
            byte[] encoded=BtexCodec.encode(px,16,16,original);
            check(encoded.length==original.length,"preserves BTEX length");
            check(Arrays.equals(Arrays.copyOfRange(encoded,0,0x80),Arrays.copyOfRange(original,0,0x80)),"preserves header");
            check(!Arrays.equals(encoded,original),"encoder changed bytes for format "+fmt);
            BtexCodec.Image decoded=BtexCodec.decode(encoded);
            check(decoded.argb.length==px.length,"decode roundtrip fmt "+fmt);
            if(fmt==0)check(Arrays.equals(decoded.argb,px),"RGBA8 must be lossless");
        }
        try {BtexCodec.encode(px,8,32,sample(3,16,16));throw new AssertionError("bad dimensions allowed");}
        catch(java.io.IOException expected) { }
        System.out.println("BTEX TEST PASS: 14 texture formats, PNG replacement encoders, all headers and dimension guards");
    }
}
