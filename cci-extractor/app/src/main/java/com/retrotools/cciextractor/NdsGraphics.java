package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Basic Nintendo DS 2D Nitro NCGR + NCLR preview conversion (no tilemaps or NSBTX). */
public final class NdsGraphics {
    public static final int MAX_BYTES=16*1024*1024;
    public static final class Image {
        public final int width,height;
        public final int[] argb;
        Image(int width,int height,int[] argb) {this.width=width;this.height=height;this.argb=argb;}
    }
    private static int u16(byte[] b,int i) {return (b[i]&255)|((b[i+1]&255)<<8);}
    private static long u32(byte[] b,int i) {
        return ((long)b[i]&255L)|(((long)b[i+1]&255L)<<8)|(((long)b[i+2]&255L)<<16)|(((long)b[i+3]&255L)<<24);
    }
    private static void require(boolean valid,String message) throws IOException {
        if(!valid)throw new IOException(message);
    }
    private static boolean magic(byte[] b,int i,String s) {
        if(i+s.length()>b.length)return false;
        for(int j=0;j<s.length();j++)if(b[i+j]!=(byte)s.charAt(j))return false;
        return true;
    }
    public static Image decode(byte[] ncgr,byte[] nclr) throws IOException { return decode(ncgr,nclr,0); }

    /** Parsed NCGR layout shared by decode and encode. */
    public static final class Layout {
        public final int depth,bytesPerTile,dataStart,dataLength,count,cols,rows,width,height;
        Layout(int depth,int dataLength,int count,int cols,int rows) {
            this.depth=depth;this.bytesPerTile=depth==3?32:64;this.dataStart=0x30;this.dataLength=dataLength;
            this.count=count;this.cols=cols;this.rows=rows;this.width=cols*8;this.height=rows*8;
        }
        public int colours() {return depth==3?16:256;}
    }
    public static Layout layout(byte[] ncgr) throws IOException {
        require(ncgr.length>=0x30 && ncgr.length<=MAX_BYTES,"Nitro image is truncated/too large");
        require(magic(ncgr,0,"RGCN") && magic(ncgr,0x10,"RAHC"),"Unsupported NCGR container");
        int depth=(int)u32(ncgr,0x1c);
        require(depth==3||depth==4,"Supported Nitro tile depths: 4bpp and 8bpp");
        int bytesPerTile=depth==3?32:64;
        int dataLength=(int)u32(ncgr,0x28);
        require(dataLength>0 && dataLength%bytesPerTile==0 &&
                dataLength <= ncgr.length-0x30,"Invalid NCGR tile length");
        require((u32(ncgr,0x24)&255)==0,"NCGR linear image layout not yet supported");
        int count=dataLength/bytesPerTile;
        int rows=u16(ncgr,0x18),cols=u16(ncgr,0x1a);
        if(rows==0xffff||cols==0xffff||cols==0||rows==0||((long)rows*cols)<count) {
            cols=Math.min(16,count); rows=(count+cols-1)/cols;
        }
        require(cols>0&&rows>0&&((long)cols*rows*64)<=8_000_000L,"NCGR dimensions out of range");
        return new Layout(depth,dataLength,count,cols,rows);
    }
    /** Number of palette banks available for this tile depth (16 colours each for 4bpp). */
    public static int paletteBanks(byte[] nclr,int depth) throws IOException {
        long[] r=paletteRange(nclr);
        int bankBytes=depth==3?32:512;
        return (int)Math.max(1,Math.min(depth==3?16:16,r[1]/bankBytes));
    }
    private static long[] paletteRange(byte[] nclr) throws IOException {
        require(nclr.length>=0x2a && nclr.length<=MAX_BYTES,"Nitro palette is truncated/too large");
        require(magic(nclr,0,"RLCN") && magic(nclr,0x10,"TTLP"),"Unsupported NCLR palette container");
        long palLength=u32(nclr,0x20), palOffset=0x18L+u32(nclr,0x24);
        require(palOffset>=0x28 && palOffset<nclr.length,"NCLR palette offset invalid");
        if(palLength==0 || palOffset+palLength>nclr.length)palLength=nclr.length-palOffset;
        return new long[]{palOffset,palLength};
    }
    public static int[] palette(byte[] nclr,int depth,int bank) throws IOException {
        long[] r=paletteRange(nclr);
        int size=depth==3?16:256;
        long start=r[0]+(long)bank*size*2;
        require(bank>=0 && (long)bank*size*2+size*2<=r[1],"NCLR palette too short");
        int[] palette=new int[size];
        for(int i=0;i<size;i++) {
            int color=u16(nclr,(int)start+i*2);
            int red=color&31,g=(color>>>5)&31,b=(color>>>10)&31;
            red=(red<<3)|(red>>>2);g=(g<<3)|(g>>>2);b=(b<<3)|(b>>>2);
            palette[i]=0xff000000|(red<<16)|(g<<8)|b;
        }
        return palette;
    }
    public static Image decode(byte[] ncgr,byte[] nclr,int bank) throws IOException {
        Layout l=layout(ncgr);
        int[] palette=palette(nclr,l.depth,bank);
        int[] pixels=new int[l.width*l.height];
        for(int t=0;t<l.count;t++) {
            int tx=(t%l.cols)*8,ty=(t/l.cols)*8;
            for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
                int idx;
                // 4bpp: two adjacent pixels per byte, low nibble is first.
                if(l.depth==3) {
                    int packed=ncgr[l.dataStart+t*32+(y*8+x)/2]&255;
                    idx=(x&1)==0?packed&15:packed>>>4;
                } else idx=ncgr[l.dataStart+t*64+y*8+x]&255;
                pixels[(ty+y)*l.width+tx+x]=palette[idx];
            }
        }
        return new Image(l.width,l.height,pixels);
    }

    /** Stand-in 256-entry grayscale NCLR for graphics that have no palette file nearby. */
    public static byte[] grayNclr(int depth) {
        byte[] b=new byte[0x28+512];
        b[0]='R';b[1]='L';b[2]='C';b[3]='N';b[0x10]='T';b[0x11]='T';b[0x12]='L';b[0x13]='P';
        b[0x20]=0;b[0x21]=2;b[0x24]=0x10;
        int n=depth==3?16:256;
        for(int i=0;i<256;i++) {
            int v=(i%n)*31/(n-1);
            int c=v|(v<<5)|(v<<10);
            b[0x28+i*2]=(byte)c;b[0x29+i*2]=(byte)(c>>>8);
        }
        return b;
    }

    public static final class Encoded {
        public final byte[] ncgr; public final int inexact;
        Encoded(byte[] ncgr,int inexact){this.ncgr=ncgr;this.inexact=inexact;}
    }
    /**
     * Rebuild an NCGR from an edited PNG. Each pixel is matched to the nearest colour in the chosen
     * palette bank; fully transparent pixels become index 0. Header and trailing sections are preserved.
     */
    public static Encoded encode(int[] argb,int width,int height,byte[] template,byte[] nclr,int bank) throws IOException {
        Layout l=layout(template);
        require(width==l.width && height==l.height && argb.length==width*height,
                "PNG must be "+l.width+"×"+l.height+" to match this NCGR (got "+width+"×"+height+")");
        int[] palette=palette(nclr,l.depth,bank);
        byte[] out=template.clone();
        java.util.Arrays.fill(out,l.dataStart,l.dataStart+l.dataLength,(byte)0);
        int inexact=0;
        for(int t=0;t<l.count;t++) {
            int tx=(t%l.cols)*8,ty=(t/l.cols)*8;
            for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
                int c=argb[(ty+y)*width+tx+x];
                int idx;
                if(((c>>>24)&255)<128) idx=0;
                else {
                    idx=Palettes.nearest(c,palette,palette.length);
                    if(((palette[idx]^c)&0xffffff)!=0) inexact++;
                }
                if(l.depth==3) {
                    int at=l.dataStart+t*32+(y*8+x)/2;
                    out[at]|=(byte)((x&1)==0?idx:(idx<<4));
                } else out[l.dataStart+t*64+y*8+x]=(byte)idx;
            }
        }
        return new Encoded(out,inexact);
    }
    private NdsGraphics(){}
}
