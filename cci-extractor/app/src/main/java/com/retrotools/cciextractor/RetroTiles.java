package com.retrotools.cciextractor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** General tile scanners for SNES, Mega Drive, Game Boy and GBA ROMs. Data is NOT guaranteed to be graphics. */
public final class RetroTiles {
    public enum System { SNES, MEGADRIVE, GB, GBA }
    public enum Mode {
        SNES_2BPP(System.SNES,2,16,"snes_2bpp",false), SNES_3BPP(System.SNES,3,24,"snes_3bpp",false),
        SNES_4BPP(System.SNES,4,32,"snes_4bpp",false), SNES_8BPP(System.SNES,8,64,"snes_8bpp",false),
        MD_4BPP(System.MEGADRIVE,4,32,"megadrive_4bpp",false),
        GB_2BPP(System.GB,2,16,"gb_2bpp",false),
        GBA_4BPP(System.GBA,4,32,"gba_4bpp",false), GBA_8BPP(System.GBA,8,64,"gba_8bpp",false),
        GBA_LZ_4BPP(System.GBA,4,32,"gba_lz77_4bpp",true), GBA_LZ_8BPP(System.GBA,8,64,"gba_lz77_8bpp",true);
        public final System system;
        public final int bpp, tileBytes; public final String slug;
        public final boolean compressed;
        Mode(System system,int bpp,int tileBytes,String slug,boolean compressed) {
            this.system=system;this.bpp=bpp;this.tileBytes=tileBytes;this.slug=slug;this.compressed=compressed;
        }
        /** Nominal raw page size. LZ77 pages vary: use {@link #heightFor(int)}. */
        public int pageBytes() {return tileBytes==24?3840:4096;}
        public int tileCount() {return pageBytes()/tileBytes;}
        public int width() {return 128;}
        public int height() {return tileCount()/16*8;}
        public int heightFor(int byteLength) {return ((byteLength/tileBytes)+15)/16*8;}
        public String label() {
            switch(this) {
                case SNES_2BPP: return "2bpp (4 colours)";
                case SNES_3BPP: return "3bpp (8 colours)";
                case SNES_4BPP: return "4bpp (16 colours)";
                case SNES_8BPP: return "8bpp (256 colours)";
                case GBA_4BPP: return "Raw 4bpp (16 colours)";
                case GBA_8BPP: return "Raw 8bpp (256 colours)";
                case GBA_LZ_4BPP: return "LZ77 compressed blocks · 4bpp";
                case GBA_LZ_8BPP: return "LZ77 compressed blocks · 8bpp";
                default: return slug;
            }
        }
        public static List<Mode> forSystem(System s) {
            List<Mode> out=new ArrayList<>();
            for(Mode m:values()) if(m.system==s) out.add(m);
            return out;
        }
    }
    /** A GBA LZ77 block found by {@link #scanLz77}: offset, compressed length and decompressed size. */
    public static final class LzBlock {
        public final long offset; public final int compressedLength, size;
        LzBlock(long offset,int compressedLength,int size){this.offset=offset;this.compressedLength=compressedLength;this.size=size;}
    }
    public static final class Info {
        public final System system;
        public final long length, dataOffset;
        public final int snesHeaderOffset;
        public final String title;
        public List<LzBlock> lzBlocks=Collections.emptyList();
        Info(System system, long length,long dataOffset,int snesHeaderOffset,String title) {
            this.system=system;this.length=length;this.dataOffset=dataOffset;
            this.snesHeaderOffset=snesHeaderOffset;this.title=title;
        }
        public Mode defaultMode() {
            switch(system) {
                case SNES: return Mode.SNES_4BPP;
                case GB: return Mode.GB_2BPP;
                case GBA: return Mode.GBA_4BPP;
                default: return Mode.MD_4BPP;
            }
        }
        public int pageCount(Mode mode) {
            if(mode.system!=system) return 0;
            if(mode.compressed) return lzBlocks.size();
            return (int)Math.min(8192,(length-dataOffset)/mode.pageBytes());
        }
        public long pageOffset(Mode mode, int page) {
            if(page<0 || page>=pageCount(mode)) throw new IllegalArgumentException("Page out of bounds");
            if(mode.compressed) return lzBlocks.get(page).offset;
            return dataOffset+(long)mode.pageBytes()*page;
        }
        public String systemName() {
            switch(system) {
                case SNES: return "SNES";
                case GB: return "Game Boy";
                case GBA: return "Game Boy Advance";
                default: return "Mega Drive";
            }
        }
        public String description() {
            return systemName() + " · " + title + " · " +
                    ((length-dataOffset)/1024) + " KiB · tile scanner";
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
    private static final int[] GB_LOGO_START={0xCE,0xED,0x66,0x66,0xCC,0x0D,0x00,0x0B};
    public static boolean isGameBoy(FileChannel ch) throws IOException {
        if(ch.size()<0x8000) return false;
        byte[] h=read(ch,0,0x150);
        for(int i=0;i<GB_LOGO_START.length;i++) if((h[0x104+i]&255)!=GB_LOGO_START[i]) return false;
        return gbHeaderChecksum(h)==(h[0x14D]&255);
    }
    static int gbHeaderChecksum(byte[] rom) {
        int x=0;
        for(int i=0x134;i<=0x14C;i++) x=x-(rom[i]&255)-1;
        return x&255;
    }
    public static boolean isGba(FileChannel ch) throws IOException {
        if(ch.size()<0x200) return false;
        byte[] h=read(ch,0,0xC0);
        return (h[0xB2]&255)==0x96 && gbaComplement(h)==(h[0xBD]&255);
    }
    static int gbaComplement(byte[] rom) {
        int c=0;
        for(int i=0xA0;i<=0xBC;i++) c-=rom[i]&255;
        return (c-0x19)&255;
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
    private static String ascii(byte[] b) {
        StringBuilder s=new StringBuilder();
        for(byte x:b){int c=x&255; s.append(c>=32&&c<127?(char)c:' ');}
        String t=s.toString().trim();
        return t.isEmpty()?"Untitled":t;
    }
    public static Info parse(FileChannel ch,System system,String filename) throws IOException {
        if(system==System.MEGADRIVE) {
            if(filename!=null && filename.toLowerCase(Locale.ROOT).endsWith(".smd"))
                throw new IOException("Interleaved .smd files are not supported; convert to linear .bin/.md first");
            if(!isMegaDrive(ch)) throw new IOException("No SEGA Mega Drive header at 0x100; unsupported image");
            return new Info(system,ch.size(),0,-1,ascii(read(ch,0x120,48)));
        }
        if(system==System.GB) {
            if(!isGameBoy(ch)) throw new IOException("No valid Game Boy cartridge header");
            byte[] h=read(ch,0,0x150);
            boolean color=(h[0x143]&0x80)!=0;
            byte[] t=new byte[color?15:16];java.lang.System.arraycopy(h,0x134,t,0,t.length);
            return new Info(system,ch.size(),0,-1,ascii(t)+(color?" (GBC)":""));
        }
        if(system==System.GBA) {
            if(!isGba(ch)) throw new IOException("No valid Game Boy Advance cartridge header");
            byte[] h=read(ch,0xA0,16);
            byte[] t=new byte[12];java.lang.System.arraycopy(h,0,t,0,12);
            byte[] code=new byte[4];java.lang.System.arraycopy(h,12,code,0,4);
            return new Info(system,ch.size(),0,-1,ascii(t)+" ["+ascii(code)+"]");
        }
        HeaderCandidate h=findSnesHeader(ch);
        if(h.score<22) throw new IOException("Could not identify a credible SNES ROM header");
        byte[] b=read(ch,h.offset,21);
        String t=new String(b,StandardCharsets.US_ASCII).replace('\0',' ').trim();
        return new Info(system,ch.size(),h.base,h.offset,t);
    }

    /**
     * Find likely LZ77 (BIOS type 0x10) graphics blocks in a GBA ROM: 4-byte aligned, decompressing
     * cleanly to a whole number of tiles. Results can still include non-graphics data.
     */
    public static List<LzBlock> scanLz77(byte[] rom,int tileBytes,int maxResults) {
        List<LzBlock> out=new ArrayList<>();
        for(int pos=0;pos+4<=rom.length && out.size()<maxResults;pos+=4) {
            if(rom[pos]!=0x10) continue;
            int size=(rom[pos+1]&255)|((rom[pos+2]&255)<<8)|((rom[pos+3]&255)<<16);
            if(size<tileBytes*4 || size>0x40000 || size%tileBytes!=0) continue;
            try {
                NitroLz.Result r=NitroLz.decompress(rom,pos,0x40000);
                if(r.consumed>=size) continue; // not actually compressing anything
                out.add(new LzBlock(pos,r.consumed,size));
                pos+=((r.consumed+3)&~3)-4;
            } catch(IOException notLz) { /* keep scanning */ }
        }
        return out;
    }

    public static byte[] readPage(FileChannel ch,Info info,Mode mode,int page) throws IOException {
        if(mode.compressed) {
            LzBlock b=info.lzBlocks.get(page);
            byte[] packed=read(ch,b.offset,b.compressedLength);
            return NitroLz.decompress(packed,0,0x40000).data;
        }
        return read(ch,info.pageOffset(mode,page),mode.pageBytes());
    }

    /** Grayscale indexes, not the original game's RGB palette. Exact nominal page length required. */
    public static int[] decode(byte[] bytes, Mode mode) throws IOException {
        if(bytes.length!=mode.pageBytes()) throw new IOException("Incorrect raw tile page length");
        return decodeAny(bytes,mode);
    }
    /** Decode any whole number of tiles into a 128-pixel-wide sheet (height = mode.heightFor(length)). */
    public static int[] decodeAny(byte[] bytes, Mode mode) throws IOException {
        if(bytes.length==0 || bytes.length%mode.tileBytes!=0) throw new IOException("Data is not a whole number of tiles");
        int width=mode.width(),height=mode.heightFor(bytes.length);
        int tiles=bytes.length/mode.tileBytes;
        int[] pixels=new int[width*height];
        java.util.Arrays.fill(pixels,0xff000000);
        for(int t=0;t<tiles;t++) {
            int base=t*mode.tileBytes,tx=(t%16)*8,ty=(t/16)*8;
            for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
                int idx=indexAt(bytes,base,x,y,mode);
                int v=idx*255/((1<<mode.bpp)-1);
                pixels[(ty+y)*width+tx+x]=0xff000000|(v<<16)|(v<<8)|v;
            }
        }
        return pixels;
    }
    private static boolean planar(Mode m) {
        return m==Mode.SNES_2BPP||m==Mode.SNES_3BPP||m==Mode.SNES_4BPP||m==Mode.SNES_8BPP||m==Mode.GB_2BPP;
    }
    private static int indexAt(byte[] bytes,int base,int x,int y,Mode mode) {
        if(mode==Mode.MD_4BPP) {
            int packed=bytes[base+y*4+x/2]&255;
            return (x%2==0)?(packed>>>4):(packed&15);
        }
        if(mode.bpp==4 && mode.system==System.GBA) {
            int packed=bytes[base+y*4+x/2]&255;
            return (x%2==0)?(packed&15):(packed>>>4);
        }
        if(mode.bpp==8 && mode.system==System.GBA) return bytes[base+y*8+x]&255;
        int idx=0, mask=1<<(7-x);
        for(int bit=0;bit<mode.bpp;bit++) {
            int off=(bit/2)*16+y*2+(bit%2);
            if(mode==Mode.SNES_3BPP && bit==2) off=16+y;
            idx|=((bytes[base+off]&mask)!=0?1:0)<<bit;
        }
        return idx;
    }
    /** Strict RGB grayscale index values and exact dimensions prevent accidental quantisation. */
    public static byte[] encode(int[] pixels,int width,int height,Mode mode) throws IOException {
        if(width!=mode.width()||height!=mode.height()||pixels.length!=width*height)
            throw new IOException("Expected "+mode.width()+"×"+mode.height()+" pixel sheet for "+mode.slug);
        return encodeAny(pixels,width,height,mode,mode.pageBytes());
    }
    public static byte[] encodeAny(int[] pixels,int width,int height,Mode mode,int byteLength) throws IOException {
        if(width!=mode.width()||height!=mode.heightFor(byteLength)||pixels.length!=width*height)
            throw new IOException("Expected "+mode.width()+"×"+mode.heightFor(byteLength)+" pixel sheet for "+mode.slug);
        int range=(1<<mode.bpp)-1;
        byte[] data=new byte[byteLength];
        int tiles=byteLength/mode.tileBytes;
        for(int t=0;t<tiles;t++) {
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
                } else if(mode.system==System.GBA && mode.bpp==4) {
                    int at=base+y*4+x/2;
                    if((x&1)==0) data[at]|=(byte)idx;
                    else data[at]|=(byte)(idx<<4);
                } else if(mode.system==System.GBA && mode.bpp==8) {
                    data[base+y*8+x]=(byte)idx;
                } else if(planar(mode)) {
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
    /** Copy a new ROM with exactly one byte range replaced. Preserve original source. Checksums unchanged. */
    public static void rebuild(FileChannel ch,OutputStream out,Info info,Mode mode,int page,byte[] replacement)
            throws IOException {
        if(mode.compressed) throw new IOException("Use rebuildFixed for compressed pages");
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

    public static final class Rebuilt {
        public final byte[] rom; public final String notes;
        Rebuilt(byte[] rom,String notes){this.rom=rom;this.notes=notes;}
    }
    public static final int MAX_REBUILD_BYTES=64*1024*1024;

    /**
     * Patch one page into an in-memory copy, re-compress LZ77 pages (relocating and re-pointing them if they
     * grow), and repair the system's header/ROM checksums.
     */
    public static Rebuilt rebuildFixed(FileChannel ch,Info info,Mode mode,int page,byte[] newData) throws IOException {
        if(info.length>MAX_REBUILD_BYTES) throw new IOException("ROM too large to rebuild in memory");
        byte[] rom=read(ch,0,(int)info.length);
        return rebuildFixed(rom,info,mode,page,newData);
    }
    public static Rebuilt rebuildFixed(byte[] rom,Info info,Mode mode,int page,byte[] newData) throws IOException {
        StringBuilder notes=new StringBuilder();
        if(!mode.compressed) {
            if(newData.length!=mode.pageBytes()) throw new IOException("Wrong replacement length");
            java.lang.System.arraycopy(newData,0,rom,(int)info.pageOffset(mode,page),newData.length);
        } else {
            LzBlock block=info.lzBlocks.get(page);
            if(newData.length!=block.size) throw new IOException("Edited block must keep its original size ("+block.size+" bytes)");
            byte[] packed=NitroLz.compressLz10(newData);
            if(packed.length<=block.compressedLength) {
                java.lang.System.arraycopy(packed,0,rom,(int)block.offset,packed.length);
                for(int i=packed.length;i<block.compressedLength;i++) rom[(int)block.offset+i]=0;
                notes.append("LZ77 block re-compressed in place. ");
            } else {
                int newOffset=(rom.length+3)&~3;
                if(newOffset+packed.length>32*1024*1024)
                    throw new IOException("Re-compressed block is larger and the ROM has no room to grow past 32 MB");
                long oldPtr=0x08000000L+block.offset, newPtr=0x08000000L+newOffset;
                int repointed=0;
                for(int i=0;i+4<=rom.length;i+=4) {
                    long v=(rom[i]&255L)|((rom[i+1]&255L)<<8)|((rom[i+2]&255L)<<16)|((rom[i+3]&255L)<<24);
                    if(v==oldPtr) {
                        for(int k=0;k<4;k++) rom[i+k]=(byte)(newPtr>>>(8*k));
                        repointed++;
                    }
                }
                if(repointed==0) throw new IOException("Edited block compresses larger than the original and no " +
                        "pointer to it was found, so it can't be moved. Simplify the edit (fewer unique details) and retry.");
                byte[] grown=new byte[newOffset+packed.length];
                java.lang.System.arraycopy(rom,0,grown,0,rom.length);
                java.util.Arrays.fill(grown,rom.length,newOffset,(byte)0xFF);
                java.lang.System.arraycopy(packed,0,grown,newOffset,packed.length);
                rom=grown;
                notes.append("LZ77 block grew, so it was moved to 0x").append(Integer.toHexString(newOffset).toUpperCase(Locale.ROOT))
                        .append(" and ").append(repointed).append(" pointer(s) updated. ");
            }
        }
        notes.append(fixChecksums(rom,info));
        return new Rebuilt(rom,notes.toString().trim());
    }

    /** Recompute header/ROM checksums. Returns a short description. */
    public static String fixChecksums(byte[] rom,Info info) {
        switch(info.system) {
            case SNES: {
                int h=info.snesHeaderOffset;
                if(h<0 || h+0x20>rom.length) return "SNES checksum not updated (header not found).";
                rom[h+0x1C]=(byte)0xFF;rom[h+0x1D]=(byte)0xFF;rom[h+0x1E]=0;rom[h+0x1F]=0;
                int base=(int)info.dataOffset;
                long sum=snesSum(rom,base,rom.length-base);
                int checksum=(int)(sum&0xFFFF), complement=checksum^0xFFFF;
                rom[h+0x1C]=(byte)complement;rom[h+0x1D]=(byte)(complement>>>8);
                rom[h+0x1E]=(byte)checksum;rom[h+0x1F]=(byte)(checksum>>>8);
                return "SNES checksum updated to 0x"+hex4(checksum)+".";
            }
            case MEGADRIVE: {
                if(rom.length<0x200) return "";
                int sum=0;
                for(int i=0x200;i+1<rom.length;i+=2) sum+=((rom[i]&255)<<8)|(rom[i+1]&255);
                sum&=0xFFFF;
                rom[0x18E]=(byte)(sum>>>8);rom[0x18F]=(byte)sum;
                return "Mega Drive checksum updated to 0x"+hex4(sum)+".";
            }
            case GB: {
                rom[0x14D]=(byte)gbHeaderChecksum(rom);
                int sum=0;
                for(int i=0;i<rom.length;i++) if(i!=0x14E && i!=0x14F) sum+=rom[i]&255;
                sum&=0xFFFF;
                rom[0x14E]=(byte)(sum>>>8);rom[0x14F]=(byte)sum;
                return "Game Boy header and global checksums updated.";
            }
            case GBA: {
                rom[0xBD]=(byte)gbaComplement(rom);
                return "GBA header checksum updated.";
            }
            default: return "";
        }
    }
    private static String hex4(int v){return String.format(Locale.ROOT,"%04X",v);}
    private static long plainSum(byte[] b,int start,int len){long s=0;for(int i=0;i<len;i++)s+=b[start+i]&255;return s;}
    /** SNES checksum sums non-power-of-two ROMs by mirroring the remainder up to the next power of two. */
    static long snesSum(byte[] b,int start,int len) {
        if(len<=0) return 0;
        int p=Integer.highestOneBit(len);
        if(p==len) return plainSum(b,start,len);
        int rest=len-p;
        long restSum=snesSum(b,start+p,rest);
        long repeats=p/Math.max(1,Integer.highestOneBit(rest)==rest?rest:p);
        if(Integer.highestOneBit(rest)!=rest) return plainSum(b,start,p)+restSum;
        return plainSum(b,start,p)+restSum*repeats;
    }
    private RetroTiles() {}
}
