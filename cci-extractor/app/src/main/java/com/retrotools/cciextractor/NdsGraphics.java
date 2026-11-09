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
    public static Image decode(byte[] ncgr,byte[] nclr) throws IOException {
        require(ncgr.length>=0x30 && ncgr.length<=MAX_BYTES && nclr.length>=0x2a && nclr.length<=MAX_BYTES,
                "Nitro image or palette is truncated/too large");
        require(magic(ncgr,0,"RGCN") && magic(ncgr,0x10,"RAHC"),"Unsupported NCGR container");
        require(magic(nclr,0,"RLCN") && magic(nclr,0x10,"TTLP"),"Unsupported NCLR palette container");
        int depth=(int)u32(ncgr,0x1c);
        require(depth==3||depth==4,"Supported Nitro tile depths: 4bpp and 8bpp");
        int bytesPerTile=depth==3?32:64;
        int dataLength=(int)u32(ncgr,0x28);
        int dataStart=0x30;
        require(dataLength>0 && dataLength%bytesPerTile==0 &&
                dataLength <= ncgr.length-dataStart,"Invalid NCGR tile length");
        require((u32(ncgr,0x24)&255)==0,"NCGR linear image layout not yet supported");
        int count=dataLength/bytesPerTile;
        int rows=u16(ncgr,0x18),cols=u16(ncgr,0x1a);
        if(rows==0xffff||cols==0xffff||cols==0||rows==0||((long)rows*cols)<count) {
            cols=Math.min(16,count); rows=(count+cols-1)/cols;
        }
        int width=cols*8,height=rows*8;
        require(width>0&&height>0&&((long)width*height)<=8_000_000L,"NCGR dimensions out of range");
        long palLength=u32(nclr,0x20), palOffset=0x18L+u32(nclr,0x24);
        if(palLength==0 || palLength>nclr.length)palLength=nclr.length-palOffset;
        require(palLength>= (depth==3?32:512) && palOffset>=0x28 &&
                palOffset+palLength<=nclr.length,"NCLR palette too short");
        int paletteSize=depth==3?16:256;
        int[] palette=new int[paletteSize];
        for(int i=0;i<paletteSize;i++) {
            int color=u16(nclr,(int)palOffset+i*2);
            int r=color&31,g=(color>>>5)&31,b=(color>>>10)&31;
            r=(r<<3)|(r>>>2);g=(g<<3)|(g>>>2);b=(b<<3)|(b>>>2);
            palette[i]=0xff000000|(r<<16)|(g<<8)|b;
        }
        int[] pixels=new int[width*height];
        for(int t=0;t<count;t++) {
            int tx=(t%cols)*8,ty=(t/cols)*8;
            for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
                int at;
                // 4bpp: two adjacent pixels per byte, low nibble is first.
                int idx;
                if(depth==3) {
                    at=dataStart+t*bytesPerTile+(y*8+x)/2;
                    int packed=ncgr[at]&255;
                    idx=(x&1)==0?packed&15:packed>>>4;
                } else {
                    at=dataStart+t*bytesPerTile+y*8+x;
                    idx=ncgr[at]&255;
                }
                pixels[(ty+y)*width+tx+x]=palette[idx];
            }
        }
        return new Image(width,height,pixels);
    }
    private NdsGraphics(){}
}
