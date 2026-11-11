import com.retrotools.cciextractor.*;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** LZ10/LZ11, NARC, nested NitroFS replacement, NDS rebuild and NCGR re-encoding. */
public class NitroContainersTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static void put16(byte[] b,int o,int v){b[o]=(byte)v;b[o+1]=(byte)(v>>>8);}
    static void put32(byte[] b,int o,int v){for(int i=0;i<4;i++)b[o+i]=(byte)(v>>>(8*i));}
    static int u32(byte[] b,int o){return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|((b[o+3]&255)<<24);}

    static byte[] ncgr4(int tiles, Random r) {
        byte[] b=new byte[0x30+tiles*32];
        System.arraycopy("RGCN".getBytes(),0,b,0,4);System.arraycopy("RAHC".getBytes(),0,b,0x10,4);
        put16(b,0x18,0xffff);put16(b,0x1a,0xffff);put32(b,0x1c,3);put32(b,0x28,tiles*32);
        for(int i=0x30;i<b.length;i++) b[i]=(byte)(r.nextInt(4)*0x11);
        return b;
    }
    static byte[] nclr() {
        byte[] b=new byte[0x28+512];
        System.arraycopy("RLCN".getBytes(),0,b,0,4);System.arraycopy("TTLP".getBytes(),0,b,0x10,4);
        put32(b,0x20,512);put32(b,0x24,0x10);
        for(int n=0;n<256;n++)put16(b,0x28+n*2,((n*3)&31)|(((n*5)&31)<<5)|(((n*7)&31)<<10));
        return b;
    }
    static int crc16(byte[] b,int off,int len){int c=0xffff;for(int j=0;j<len;j++){c^=b[off+j]&255;
        for(int k=0;k<8;k++)c=((c&1)!=0)?(c>>>1)^0xa001:c>>>1;}return c&0xffff;}

    /** Minimal DS ROM with one NitroFS file "gfx.carc" (LZ11-compressed NARC). */
    static byte[] rom(byte[] fileData) {
        int fileStart=0x1000;
        byte[] b=new byte[fileStart+((fileData.length+0x1ff)&~0x1ff)];
        System.arraycopy("TEST".getBytes(),0,b,0,4);System.arraycopy("TEST".getBytes(),0,b,12,4);
        for(int i=0;i<156;i++) b[0xc0+i]=(byte)(i*7+3);
        put16(b,0x15c,crc16(b,0xc0,156));
        put32(b,0x40,0x300);put32(b,0x44,0x20);put32(b,0x48,0x400);put32(b,0x4c,8);
        put32(b,0x80,fileStart+fileData.length);
        put32(b,0x300,8);put16(b,0x304,0);put16(b,0x306,1);
        b[0x308]=8;System.arraycopy("gfx.carc".getBytes(),0,b,0x309,8);b[0x311]=0;
        put32(b,0x400,fileStart);put32(b,0x404,fileStart+fileData.length);
        System.arraycopy(fileData,0,b,fileStart,fileData.length);
        return b;
    }

    public static void main(String[] args) throws Exception {
        Random r=new Random(7);
        // --- LZ round trips (repetitive and random data, both types)
        for(int type:new int[]{NitroLz.TYPE_LZ10,NitroLz.TYPE_LZ11}) {
            for(int trial=0;trial<4;trial++) {
                byte[] data=new byte[1000+r.nextInt(70000)];
                for(int i=0;i<data.length;i++) data[i]=(byte)(trial%2==0 ? r.nextInt(256) : (i/37)%5);
                byte[] packed=NitroLz.compress(data,type);
                NitroLz.Result back=NitroLz.decompress(packed,0,NitroLz.MAX_OUTPUT);
                check(Arrays.equals(back.data,data),"LZ roundtrip "+type);
                check(NitroLz.tryWhole(packed)!=null,"tryWhole accepts own output");
                if(trial%2==1) check(packed.length<data.length/4,"LZ should compress repetitive data");
            }
        }
        check(NitroLz.tryWhole(new byte[]{0x10,(byte)0xff,(byte)0xff,0,(byte)0x80,0x00,0x05})==null,"rejects bad backref");
        System.out.println("LZ10/LZ11 round trips passed");

        // --- NARC parse/replace
        byte[] gfx=ncgr4(8,r), pal=nclr();
        byte[] other=new byte[]{1,2,3,4,5};
        byte[] narc=NarcArchive.build(Arrays.asList(pal,NitroLz.compressLz10(gfx),other));
        NarcArchive.Archive a=NarcArchive.parse(narc);
        check(a.count()==3 && Arrays.equals(a.file(2),other),"NARC parse");
        byte[] bigger=new byte[99];Arrays.fill(bigger,(byte)9);
        NarcArchive.Archive a2=NarcArchive.parse(NarcArchive.replace(narc,2,bigger));
        check(Arrays.equals(a2.file(2),bigger)&&Arrays.equals(a2.file(0),pal),"NARC replace");
        System.out.println("NARC parse/replace passed");

        // --- Full chain: ROM -> LZ11 carc -> NARC -> LZ10 NCGR; edit graphic; rebuild ROM; read back
        byte[] carc=NitroLz.compressLz11(narc);
        byte[] romBytes=rom(carc);
        Path p=Files.createTempFile("vfs",".nds");Path outP=Files.createTempFile("vfs-out",".nds");
        try {
            Files.write(p,romBytes);
            NdsGraphics.Image edited;
            try(FileChannel ch=FileChannel.open(p)) {
                NdsArchive.Info info=NdsArchive.parse(ch);
                NitroVfs.Reader reader=NitroVfs.channelReader(ch);
                List<NitroVfs.Node> nodes=new ArrayList<>(NitroVfs.roots(info));
                NitroVfs.Node top=nodes.get(0);
                NitroVfs.Probe pr=NitroVfs.probe(top,reader);
                check(top.compression==NitroLz.TYPE_LZ11 && top.isContainer(),"top is LZ11 NARC: "+top.describe());
                List<NitroVfs.Node> kids=NitroVfs.expand(top,pr.content);
                nodes.addAll(kids);
                check(kids.size()==3,"3 children");
                NitroVfs.Node g=kids.get(1);
                check(g.isGraphic() && g.compression==NitroLz.TYPE_LZ10,"child graphic LZ10: "+g.path);
                check(g.path.equals("nitrofs/gfx.carc_contents/0001.ncgr"),"child path "+g.path);
                NitroVfs.Node palNode=NitroVfs.pairPalette(g,nodes);
                check(palNode==kids.get(0),"nearest palette paired");
                byte[] gContent=NitroVfs.probe(g,reader).content;
                check(Arrays.equals(gContent,gfx),"child content decompressed");
                NdsGraphics.Image img=NdsGraphics.decode(gContent,NitroVfs.probe(palNode,reader).content,0);
                // Edit: flip every pixel to palette colour 5, and add one off-palette pixel.
                int[] pal16=NdsGraphics.palette(pal,3,0);
                int[] px=img.argb.clone();
                for(int i=0;i<px.length;i++) px[i]=pal16[5];
                px[3]=(pal16[7]&0xfffefefe);
                NdsGraphics.Encoded enc=NdsGraphics.encode(px,img.width,img.height,gContent,pal,0);
                check(enc.inexact==1,"one inexact pixel, got "+enc.inexact);
                edited=NdsGraphics.decode(enc.ncgr,pal,0);
                NitroVfs.Patch patch=NitroVfs.replace(g,enc.ncgr,reader);
                try(java.io.OutputStream o=Files.newOutputStream(outP)) { NitroVfs.writeRom(ch,o,patch); }
            }
            byte[] out=Files.readAllBytes(outP);
            check(crc16(out,0,0x15E)==((out[0x15E]&255)|((out[0x15F]&255)<<8)),"header CRC updated");
            try(FileChannel ch=FileChannel.open(outP)) {
                NdsArchive.Info info=NdsArchive.parse(ch);
                NitroVfs.Reader reader=NitroVfs.channelReader(ch);
                NitroVfs.Node top=NitroVfs.roots(info).get(0);
                NitroVfs.Probe pr=NitroVfs.probe(top,reader);
                check(top.compression==NitroLz.TYPE_LZ11,"rebuilt top still LZ11");
                NitroVfs.Node g=NitroVfs.expand(top,pr.content).get(1);
                byte[] c=NitroVfs.probe(g,reader).content;
                NdsGraphics.Image again=NdsGraphics.decode(c,pal,0);
                check(Arrays.equals(again.argb,edited.argb),"edited graphic survives ROM rebuild");
                check(u32(out,0x80)>=u32(out,0x404)||u32(out,0x404)<=out.length,"used size/FAT consistent");
            }
            System.out.println("NitroFS -> LZ11 NARC -> LZ10 NCGR edit and ROM rebuild passed");
        } finally { Files.deleteIfExists(p); Files.deleteIfExists(outP); }

        // --- NCGR encode exact roundtrip, 8bpp
        byte[] g8=new byte[0x30+64*4];
        System.arraycopy("RGCN".getBytes(),0,g8,0,4);System.arraycopy("RAHC".getBytes(),0,g8,0x10,4);
        put16(g8,0x18,2);put16(g8,0x1a,2);put32(g8,0x1c,4);put32(g8,0x28,256);
        int[] p256=NdsGraphics.palette(pal,4,0);
        // choose indexes whose colours are unique in this palette
        Map<Integer,Integer> firstIndex=new HashMap<>();
        for(int i=0;i<256;i++) firstIndex.putIfAbsent(p256[i],i);
        Integer[] uniq=firstIndex.values().toArray(new Integer[0]);
        for(int i=0;i<256;i++) g8[0x30+i]=(byte)(int)uniq[r.nextInt(uniq.length)];
        NdsGraphics.Image im8=NdsGraphics.decode(g8,pal,0);
        NdsGraphics.Encoded e8=NdsGraphics.encode(im8.argb,im8.width,im8.height,g8,pal,0);
        check(Arrays.equals(e8.ncgr,g8) && e8.inexact==0,"8bpp NCGR exact roundtrip");
        try { NdsGraphics.encode(im8.argb,im8.width+8,im8.height,g8,pal,0); throw new AssertionError("size accepted"); }
        catch(java.io.IOException expected) {}
        System.out.println("NCGR PNG import encoder passed");
    }
}
