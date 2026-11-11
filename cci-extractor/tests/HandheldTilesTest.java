import com.retrotools.cciextractor.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Game Boy / GBA detection, LZ77 block scanning, checksum repair and palette mapping. */
public class HandheldTilesTest {
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    static final int[] LOGO={0xCE,0xED,0x66,0x66,0xCC,0x0D,0x00,0x0B};

    static byte[] gb() {
        byte[] b=new byte[0x8000];
        for(int i=0;i<LOGO.length;i++)b[0x104+i]=(byte)LOGO[i];
        System.arraycopy("TESTGAME".getBytes(),0,b,0x134,8);
        int x=0;for(int i=0x134;i<=0x14C;i++)x=x-(b[i]&255)-1;b[0x14D]=(byte)x;
        return b;
    }
    static byte[] gba(int size) {
        byte[] b=new byte[size];
        System.arraycopy("TESTGBA".getBytes(),0,b,0xA0,7);System.arraycopy("ATST".getBytes(),0,b,0xAC,4);
        b[0xB2]=(byte)0x96;
        int c=0;for(int i=0xA0;i<=0xBC;i++)c-=b[i]&255;b[0xBD]=(byte)((c-0x19)&255);
        return b;
    }
    static RetroTiles.Info open(byte[] rom,RetroTiles.System s) throws Exception {
        Path p=Files.createTempFile("handheld",".bin");Files.write(p,rom);
        try(FileChannel ch=FileChannel.open(p)) {
            if(s==RetroTiles.System.GB) check(RetroTiles.isGameBoy(ch),"GB detect");
            if(s==RetroTiles.System.GBA) check(RetroTiles.isGba(ch)&&!RetroTiles.isGameBoy(ch),"GBA detect");
            return RetroTiles.parse(ch,s,p.toString());
        } finally {Files.delete(p);}
    }

    public static void main(String[] args) throws Exception {
        Random r=new Random(99);
        // GB: page rebuild repairs header + global checksum
        byte[] gbRom=gb();
        RetroTiles.Info gi=open(gbRom,RetroTiles.System.GB);
        check(gi.title.startsWith("TESTGAME") && gi.pageCount(RetroTiles.Mode.GB_2BPP)==8,"GB info "+gi.title);
        byte[] page=new byte[4096];r.nextBytes(page);
        RetroTiles.Rebuilt gbOut=RetroTiles.rebuildFixed(gbRom.clone(),gi,RetroTiles.Mode.GB_2BPP,3,page);
        int sum=0;for(int i=0;i<gbOut.rom.length;i++)if(i!=0x14E&&i!=0x14F)sum+=gbOut.rom[i]&255;
        check(((sum>>>8)&255)==(gbOut.rom[0x14E]&255)&&(sum&255)==(gbOut.rom[0x14F]&255),"GB global checksum");
        // Replacing page 0 (header) still yields a valid header checksum
        RetroTiles.Rebuilt gb0=RetroTiles.rebuildFixed(gbRom.clone(),gi,RetroTiles.Mode.GB_2BPP,0,page);
        int x=0;for(int i=0x134;i<=0x14C;i++)x=x-(gb0.rom[i]&255)-1;
        check((x&255)==(gb0.rom[0x14D]&255),"GB header checksum");
        System.out.println("Game Boy detection and checksum repair passed");

        // GBA: raw 4bpp/8bpp codec roundtrip (GBA nibble order)
        for(RetroTiles.Mode m:new RetroTiles.Mode[]{RetroTiles.Mode.GBA_4BPP,RetroTiles.Mode.GBA_8BPP}) {
            byte[] in=new byte[4096];r.nextBytes(in);
            int[] px=RetroTiles.decode(in,m);
            check(Arrays.equals(RetroTiles.encode(px,128,m.height(),m),in),"GBA roundtrip "+m);
        }
        byte[] t=new byte[32];t[0]=0x21; // pixel0=1 pixel1=2 (low nibble first)
        int[] tp=RetroTiles.decodeAny(t,RetroTiles.Mode.GBA_4BPP);
        check((tp[0]&255)==17 && (tp[1]&255)==34,"GBA low nibble first");

        // GBA LZ77 scan and in-place / relocated rebuild
        byte[] gfx=new byte[32*64];
        for(int i=0;i<gfx.length;i++)gfx[i]=(byte)((i/64)%3==0?0x11:0x22);
        byte[] packed=NitroLz.compressLz10(gfx);
        byte[] rom=gba(0x10000);
        int at=0x4000;
        System.arraycopy(packed,0,rom,at,packed.length);
        long ptr=0x08000000L+at;
        for(int k=0;k<4;k++)rom[0x200+k]=(byte)(ptr>>>(8*k));
        int c=0;for(int i=0xA0;i<=0xBC;i++)c-=rom[i]&255;rom[0xBD]=(byte)((c-0x19)&255);
        RetroTiles.Info ai=open(rom,RetroTiles.System.GBA);
        ai.lzBlocks=RetroTiles.scanLz77(rom,32,100);
        check(ai.pageCount(RetroTiles.Mode.GBA_LZ_4BPP)==1 && ai.lzBlocks.get(0).offset==at,"LZ77 block found: "+ai.lzBlocks.size());
        // Small edit -> fits in place
        byte[] edit=new byte[gfx.length];java.util.Arrays.fill(edit,(byte)0x11); // simpler art compresses smaller
        RetroTiles.Rebuilt inPlace=RetroTiles.rebuildFixed(rom.clone(),ai,RetroTiles.Mode.GBA_LZ_4BPP,0,edit);
        check(Arrays.equals(NitroLz.decompress(inPlace.rom,at,1<<20).data,edit),"in-place LZ77 rebuild: "+inPlace.notes);
        // Noisy edit -> grows, gets moved and re-pointed
        byte[] noisy=new byte[gfx.length];r.nextBytes(noisy);
        RetroTiles.Rebuilt moved=RetroTiles.rebuildFixed(rom.clone(),ai,RetroTiles.Mode.GBA_LZ_4BPP,0,noisy);
        long np=(moved.rom[0x200]&255L)|((moved.rom[0x201]&255L)<<8)|((moved.rom[0x202]&255L)<<16)|((moved.rom[0x203]&255L)<<24);
        check(np!=ptr && Arrays.equals(NitroLz.decompress(moved.rom,(int)(np-0x08000000L),1<<20).data,noisy),
                "relocated LZ77 block: "+moved.notes);
        int cc=0;for(int i=0xA0;i<=0xBC;i++)cc-=moved.rom[i]&255;
        check(((cc-0x19)&255)==(moved.rom[0xBD]&255),"GBA complement");
        System.out.println("GBA tiles, LZ77 scan and rebuild passed");

        // SNES checksum on a 1.5 MB-style (non power of two) image is self-consistent
        byte[] snes=new byte[0x18000];r.nextBytes(snes);
        long s1=0;for(byte b:snes)s1+=b&255;
        // mirror: 64K + 32K*2
        long expect=0;for(int i=0;i<0x10000;i++)expect+=snes[i]&255;
        long rest=0;for(int i=0x10000;i<0x18000;i++)rest+=snes[i]&255;
        expect+=rest*2;
        java.lang.reflect.Method m=RetroTiles.class.getDeclaredMethod("snesSum",byte[].class,int.class,int.class);
        m.setAccessible(true);
        check((long)m.invoke(null,snes,0,snes.length)==expect,"SNES mirrored sum");
        System.out.println("SNES checksum mirroring passed");

        // Palettes: preset recolour -> edited -> back to exact gray
        for(int bpp:new int[]{2,4}) {
            int[] pal=Palettes.preset(5,bpp);
            byte[] data=new byte[bpp==2?4096:4096];r.nextBytes(data);
            RetroTiles.Mode mode=bpp==2?RetroTiles.Mode.GB_2BPP:RetroTiles.Mode.GBA_4BPP;
            int[] gray=RetroTiles.decode(data,mode);
            int[] coloured=Palettes.recolour(gray,bpp,pal);
            Palettes.Mapped back=Palettes.toGray(coloured,bpp,pal);
            check(back.inexact==0 && Arrays.equals(RetroTiles.encode(back.gray,128,mode.height(),mode),data),"palette roundtrip "+bpp);
        }
        int[] jasc=Palettes.parse("JASC-PAL\n0100\n2\n255 0 0\n0 255 0\n".getBytes(),"x.pal");
        check(jasc.length==2 && jasc[0]==0xffff0000,"JASC parse");
        int[] bgr=Palettes.parse(new byte[]{(byte)0x1f,0,(byte)0xe0,(byte)0x03},"x.bin");
        check(bgr[0]==0xffff0000 && bgr[1]==0xff00ff00,"BGR555 parse");
        System.out.println("Palette presets and palette file parsing passed");
    }
}
