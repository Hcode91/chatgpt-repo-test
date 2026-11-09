package com.retrotools.cciextractor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** General uncompressed tile scanners. Data is NOT guaranteed to be graphics. */
public final class RetroTiles {
    public enum System { SNES, MEGADRIVE }
    public enum Mode {
        SNES_2BPP(2,16,"snes_2bpp"), SNES_3BPP(3,24,"snes_3bpp"),
        SNES_4BPP(4,32,"snes_4bpp"), SNES_8BPP(8,64,"snes_8bpp"),
        MD_4BPP(4,32,"megadrive_4bpp");
        public final int bpp, tileBytes; public final String slug;
        Mode(int bpp,int tileBytes,String slug) {
            this.bpp=bpp;this.tileBytes=tileBytes;this.slug=slug;
        }
        public int pageBytes() {return tileBytes==24?3840:4096;}
        public int tileCount() {return pageBytes()/tileBytes;}
        public int width() {return 128;}
        public int height() {return tileCount()/16*8;}
    }
    public static final class Info {
        public final System system;
        public final long length, dataOffset;
        public final int snesHeaderOffset;
        public final String title;
        Info(System system, long length,long dataOffset,int snesHeaderOffset,String title) {
            this.system=system;this.length=length;this.dataOffset=dataOffset;
            this.snesHeaderOffset=snesHeaderOffset;this.title=title;
        }
        public Mode defaultMode() {return system==System.SNES?Mode.SNES_4BPP:Mode.MD_4BPP;}
        public int pageCount(Mode mode) {
            if(system==System.MEGADRIVE && mode!=Mode.MD_4BPP ||
                    system==System.SNES && mode==Mode.MD_4BPP) return 0;
            return (int)Math.min(8192,(length-dataOffset)/mode.pageBytes());
        }
        public long pageOffset(Mode mode, int page) {
            if(page<0 || page>=pageCount(mode)) throw new IllegalArgumentException("Page out of bounds");
            return dataOffset+(long)mode.pageBytes()*page;
        }
        public String description() {
            return (system==System.SNES?"SNES":"Mega Drive") + " · " + title + " · " +
                    ((length-dataOffset)/1024) + " KiB · raw/uncompressed tile scanner";
        }
    }
    private static void readExactly(FileChannel ch,ByteBuffer buf,long offset) throws IOException {
        while(buf.hasRemaining()) {
            int n=ch.read(buf,offset+buf.position());
            if(n<=0) throw new IOException("Unexpected end of ROM");
        }
    }
    private static byte[] read(FileChannel ch,long offset,int length) throws IOException {
        byte[] b=new byte[length];readExactly(ch,ByteBuffer.wrap(b),offset);return b;
    }
    public static boolean isMegaDrive(FileChannel ch) throws IOException {
        if(ch.size()<0x400) return false;
        byte[] b=read(ch,0x100,16);
        String title=new String(b,StandardCharsets.US_ASCII);
        return title.startsWith("SEGA") || title.startsWith(" SEGA");
    }
    private static int u16le(byte[] b,int i) {return (b[i]&255)|((b[i+1]&255)<<8);}
    /** SNES header presence. Header map values and checksum are heuristics, not proof of graphics. */
    private static int headerScore(FileChannel ch,long pos,int dataStart) throws IOException {
        if(pos+0x40>ch.size()) return Integer.MIN_VALUE;
        byte[] h=read(ch,pos,0x40);
        int score=0, good=0;
        for(int i=0;i<21;i++) {
            int c=h[i]&255;
            if(c==0 || c==0xff || c>=32 && c<=126) good++;
        }
        if(good<17) return Integer.MIN_VALUE;
        score+=good;
        int map=h[0x15]&255;
        if((map&0x0f)<=5) score+=6;
        int complement=u16le(h,0x1c),checksum=u16le(h,0x1e);
        if(((checksum^complement)&0xffff)==0xffff) score+=16;
        int reset=u16le(h,0x3c);
        if(reset>=0x8000) score+=4;
        // Prefer expected bank mappings when candidates score similarly.
        if(pos==dataStart+0x7fc0 && (map&1)==0) score+=3;
        if(pos==dataStart+0xffc0 && (map&1)==1) score+=3;
        return score;
    }
    public static boolean isSnes(FileChannel ch,String filename) throws IOException {
        String n=filename==null?"":filename.toLowerCase(Locale.ROOT);
        return n.endsWith(".sfc")||n.endsWith(".smc") ||
            (ch.size()>=0x10000 && findSnesHeader(ch).score>=38);
    }
    private static final class HeaderCandidate {
        final int offset,score,base;
        HeaderCandidate(int offset,int score,int base) {this.offset=offset;this.score=score;this.base=base;}
    }
    private static HeaderCandidate findSnesHeader(FileChannel ch) throws IOException {
        int base=(ch.size()%32768==512)?512:0;
        HeaderCandidate best=new HeaderCandidate(-1,Integer.MIN_VALUE,base);
        for(int rel:new int[]{0x7fc0,0xffc0,0x40ffc0}) {
            int offset=base+rel;
            int score=headerScore(ch,offset,base);
            if(score>best.score) best=new HeaderCandidate(offset,score,base);
        }
        return best;
    }
    public static Info parse(FileChannel ch,System system,String filename) throws IOException {
        if(system==System.MEGADRIVE) {
            if(filename!=null && filename.toLowerCase(Locale.ROOT).endsWith(".smd"))
                throw new IOException("Interleaved .smd files are not supported; convert to linear .bin/.md first");
            if(!isMegaDrive(ch)) throw new IOException("No SEGA Mega Drive header at 0x100; unsupported image");
            byte[] b=read(ch,0x120,48);
            String t=new String(b,StandardCharsets.US_ASCII).trim();
            return new Info(system,ch.size(),0,-1,t.isEmpty()?"Untitled":t);
        }
        HeaderCandidate h=findSnesHeader(ch);
        if(h.score<22) throw new IOException("Could not identify a credible SNES ROM header");
        byte[] b=read(ch,h.offset,21);
        String t=new String(b,StandardCharsets.US_ASCII).replace('\0',' ').trim();
        return new Info(system,ch.size(),h.base,h.offset,t);
    }
    public static byte[] readPage(FileChannel ch,Info info,Mode mode,int page) throws IOException {
        return read(ch,info.pageOffset(mode,page),mode.pageBytes());
    }
    /** Grayscale indexes, not the original game's RGB palette. */
    public static int[] decode(byte[] bytes, Mode mode) throws IOException {
        if(bytes.length!=mode.pageBytes()) throw new IOException("Incorrect raw tile page length");
        int width=mode.width(),height=mode.height();
        int[] pixels=new int[width*height];
        for(int t=0;t<mode.tileCount();t++) {
            int base=t*mode.tileBytes,tx=(t%16)*8,ty=(t/16)*8;
            for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
                int idx=0;
                if(mode==Mode.MD_4BPP) {
                    int packed=bytes[base+y*4+x/2]&255;
                    idx=(x%2==0)?(packed>>>4):(packed&15);
                } else {
                    int mask=1<<(7-x);
                    for(int bit=0;bit<mode.bpp;bit++) {
                        int off=(bit/2)*16+y*2+(bit%2);
                        if(mode==Mode.SNES_3BPP && bit==2) off=16+y;
                        idx|=((bytes[base+off]&mask)!=0?1:0)<<bit;
                    }
                }
                int v=idx*255/((1<<mode.bpp)-1);
                pixels[(ty+y)*width+tx+x]=0xff000000|(v<<16)|(v<<8)|v;
            }
        }
        return pixels;
    }
    /** Strict RGB grayscale index values and exact dimensions prevent accidental quantisation. */
    public static byte[] encode(int[] pixels,int width,int height,Mode mode) throws IOException {
        if(width!=mode.width()||height!=mode.height()||pixels.length!=width*height)
            throw new IOException("Expected "+mode.width()+"×"+mode.height()+" pixel sheet for "+mode.slug);
        int range=(1<<mode.bpp)-1;
        byte[] data=new byte[mode.pageBytes()];
        for(int t=0;t<mode.tileCount();t++) {
            int base=t*mode.tileBytes,tx=(t%16)*8,ty=(t/16)*8;
            for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
                int c=pixels[(ty+y)*width+tx+x],v=c&255;
                if(((c>>>24)&255)!=255 || ((c>>>16)&255)!=v || ((c>>>8)&255)!=v)
                    throw new IOException("Use opaque grayscale palette indexes only (pixel "+(tx+x)+","+(ty+y)+")");
                int idx=(v*range+127)/255;
                if(v!=idx*255/range)
                    throw new IOException("Invalid grayscale index "+v+" at "+(tx+x)+","+(ty+y)+
                            "; expected one of "+(range+1)+" exact gray levels");
                if(mode==Mode.MD_4BPP) {
                    int at=base+y*4+x/2;
                    if((x&1)==0) data[at]|=(byte)(idx<<4);
                    else data[at]|=(byte)idx;
                } else {
                    for(int bit=0;bit<mode.bpp;bit++) {
                        int off=(bit/2)*16+y*2+(bit&1);
                        if(mode==Mode.SNES_3BPP && bit==2) off=16+y;
                        if(((idx>>>bit)&1)!=0) data[base+off]|=(byte)(1<<(7-x));
                    }
                }
            }
        }
        return data;
    }
    /** Copy a new ROM with exactly one byte range replaced. Preserve original source. */
    public static void rebuild(FileChannel ch,OutputStream out,Info info,Mode mode,int page,byte[] replacement)
            throws IOException {
        if(replacement.length!=mode.pageBytes())throw new IOException("Wrong replacement length");
        long start=info.pageOffset(mode,page),end=start+replacement.length;
        byte[] buffer=new byte[65536];
        for(long pos=0;pos<info.length;) {
            int n=(int)Math.min(buffer.length,info.length-pos);
            readExactly(ch,ByteBuffer.wrap(buffer,0,n),pos);
            long a=Math.max(pos,start),b=Math.min(pos+n,end);
            if(a<b)java.lang.System.arraycopy(replacement,(int)(a-start),buffer,(int)(a-pos),(int)(b-a));
            out.write(buffer,0,n);pos+=n;
        }
    }
    private RetroTiles() {}
}
