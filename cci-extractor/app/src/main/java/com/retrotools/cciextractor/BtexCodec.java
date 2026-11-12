package com.retrotools.cciextractor;

import java.io.IOException;
import java.util.Arrays;

/**
 * STXC/BTEX image codec for the standard Nintendo 3DS tiled texture formats.
 * Pure Java: can be tested without Android. Does not alter the 0x80-byte
 * container header, mipmap data or other trailing data during re-encoding.
 * The original BTEX acts as a required template when writing a replacement.
 */
public final class BtexCodec {
    private BtexCodec() { }
    private static final int HEADER = 0x80;
    private static final int MAX_PIXELS = 8_388_608;
    public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int[][] MODIFIERS = {
            {2, 8}, {5, 17}, {9, 29}, {13, 42},
            {18, 60}, {24, 80}, {33, 106}, {47, 183}
    };

    public static final class Image {
        public final int width, height, format;
        public final int[] argb;
        Image(int width, int height, int format, int[] argb) {
            this.width = width; this.height = height; this.format = format; this.argb = argb;
        }
    }

    public static final class Header {
        public final int format, width, height, imageSize, offset, mips;
        Header(int format, int width, int height, int imageSize, int offset, int mips) {
            this.format = format; this.width = width; this.height = height;
            this.imageSize = imageSize; this.offset = offset; this.mips = mips;
        }
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8;
    }
    private static int u32(byte[] bytes, int at) {
        return u16(bytes, at) | u16(bytes, at + 2) << 16;
    }
    private static long le64(byte[] data, int pos) {
        long v = 0;
        for (int i = 0; i < 8; ++i) v |= ((long) data[pos + i] & 255L) << (8 * i);
        return v;
    }
    private static void put64(byte[] data, int pos, long v) {
        for (int i = 0; i < 8; i++) data[pos + i] = (byte) (v >>> (8 * i));
    }
    private static int clamp(int value) { return Math.min(255, Math.max(0, value)); }
    private static int rgba(int r, int g, int b, int a) {
        return clamp(a) << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }
    private static int channel(int argb, int shift) { return (argb >>> shift) & 255; }
    private static int lum(int pixel) {
        return (77 * channel(pixel, 16) + 150 * channel(pixel, 8) + 29 * channel(pixel, 0) + 128) >>> 8;
    }
    private static int quant(int v, int max) { return (v * max + 127) / 255; }
    private static int expand(int v, int max) { return (v * 255 + max / 2) / max; }

    private static int bitsPerPixel(int fmt) {
        switch (fmt) {
            case 0: return 32;  // RGBA8
            case 1: return 24;  // RGB8
            case 2: case 3: case 4: case 5: case 6: return 16;
            case 7: case 8: case 9: case 13: return 8;
            case 10: case 11: case 12: return 4;
            default: return -1;
        }
    }

    public static Header inspect(byte[] data) throws IOException {
        if (data.length < HEADER || data[0] != 'S' || data[1] != 'T' ||
                data[2] != 'X' || data[3] != 'C')
            throw new IOException("Not an STXC BTEX file");
        int start = u16(data, 6);
        int fmt = u16(data, 8), mips = u16(data, 10);
        int w = u16(data, 12), h = u16(data, 14);
        int size = u32(data, 16);
        int bpp = bitsPerPixel(fmt);
        if (start < HEADER || start > data.length) throw new IOException("Invalid BTEX data offset");
        if (bpp < 0) throw new IOException("Unsupported BTEX format: " + fmt);
        if (w == 0 || h == 0 || w % 8 != 0 || h % 8 != 0 || (long) w * h > MAX_PIXELS)
            throw new IOException("Unsupported BTEX dimensions: " + w + "x" + h);
        if (size <= 0 || size > MAX_FILE_BYTES || (long) start + size > data.length)
            throw new IOException("Invalid or oversized BTEX image payload");
        if ((long) w * h * bpp / 8L > size)
            throw new IOException("BTEX pixel data is shorter than its dimensions require");
        if (mips != 1) throw new IOException("Multi-mip BTEX not yet supported safely");
        return new Header(fmt, w, h, size, start, mips);
    }

    /** Pixel index in a conventional 3DS 8x8 Morton-tiled texture. */
    private static int tiledIndex(int x, int y, int width) {
        int morton = (x & 1) | ((y & 1) << 1) | ((x & 2) << 1) |
                ((y & 2) << 2) | ((x & 4) << 2) | ((y & 4) << 3);
        return ((y >>> 3) * (width >>> 3) + (x >>> 3)) * 64 + morton;
    }

    public static Image decode(byte[] data) throws IOException {
        Header h = inspect(data);
        return decode(data, h);
    }

    /** Bits per pixel of a PICA200 texture format (0–13), or -1 when unknown. */
    static int picaBitsPerPixel(int format) { return bitsPerPixel(format); }

    /**
     * Decodes raw PICA200 texture data (native 8x8 Morton tile order) found at {@code offset}.
     * Width and height must be multiples of 8. Used by the CTPK / BCLIM / BFLIM / CGFX / BCH readers.
     */
    static int[] decodeTiled(byte[] data, int offset, int width, int height, int format) throws IOException {
        int bpp = bitsPerPixel(format);
        if (bpp < 0) throw new IOException("Unsupported texture format " + format);
        if (width <= 0 || height <= 0 || width % 8 != 0 || height % 8 != 0 || (long) width * height > MAX_PIXELS)
            throw new IOException("Unsupported texture dimensions " + width + "x" + height);
        long need = (long) width * height * bpp / 8L;
        if (offset < 0 || offset + need > data.length) throw new IOException("Texture data is truncated");
        return decode(data, new Header(format, width, height, (int) need, offset, 1)).argb;
    }

    private static Image decode(byte[] data, Header h) throws IOException {
        int[] pixels = new int[h.width * h.height];
        if (h.format == 12 || h.format == 13) {
            decodeETC(data, h, pixels);
        } else {
            for (int y = 0; y < h.height; y++) for (int x = 0; x < h.width; x++) {
                int i = tiledIndex(x, y, h.width);
                int off, p, a, l, r, g, b;
                switch (h.format) {
                    case 0: // 3DS stores texture RGBA8 as A, B, G, R
                        off = h.offset + i * 4;
                        pixels[y*h.width+x] = rgba(data[off+3]&255, data[off+2]&255,
                                data[off+1]&255, data[off]&255);
                        break;
                    case 1:
                        off = h.offset + i * 3;
                        pixels[y*h.width+x] = rgba(data[off+2]&255, data[off+1]&255,
                                data[off]&255, 255);
                        break;
                    case 2: // RGBA5551
                        off = h.offset + i * 2; p = u16(data, off);
                        pixels[y*h.width+x] = rgba(expand((p>>>11)&31,31), expand((p>>>6)&31,31),
                                expand((p>>>1)&31,31), (p&1)!=0?255:0);
                        break;
                    case 3: // RGB565
                        off = h.offset + i * 2; p = u16(data, off);
                        pixels[y*h.width+x] = rgba(expand((p>>>11)&31,31), expand((p>>>5)&63,63),
                                expand(p&31,31), 255);
                        break;
                    case 4: // RGBA4444
                        off = h.offset + i * 2; p = u16(data, off);
                        pixels[y*h.width+x] = rgba(((p>>>12)&15)*17, ((p>>>8)&15)*17,
                                ((p>>>4)&15)*17, (p&15)*17);
                        break;
                    case 5: // LA8
                        off = h.offset + i * 2; a = data[off]&255; l = data[off+1]&255;
                        pixels[y*h.width+x] = rgba(l,l,l,a);
                        break;
                    case 6: // HILO8
                        off = h.offset + i * 2;
                        pixels[y*h.width+x] = rgba(data[off+1]&255, data[off]&255, 0, 255);
                        break;
                    case 7:
                        l = data[h.offset+i]&255; pixels[y*h.width+x] = rgba(l,l,l,255); break;
                    case 8:
                        a = data[h.offset+i]&255; pixels[y*h.width+x] = rgba(0,0,0,a); break;
                    case 9:
                        p = data[h.offset+i]&255; l = (p>>>4)*17; a = (p&15)*17;
                        pixels[y*h.width+x] = rgba(l,l,l,a); break;
                    case 10:
                        p = data[h.offset+(i>>>1)]&255; l = ((p>>>(4*(i&1)))&15)*17;
                        pixels[y*h.width+x] = rgba(l,l,l,255); break;
                    case 11:
                        p = data[h.offset+(i>>>1)]&255; a = ((p>>>(4*(i&1)))&15)*17;
                        pixels[y*h.width+x] = rgba(0,0,0,a); break;
                    default: throw new IOException("Unsupported BTEX format " + h.format);
                }
            }
        }
        return new Image(h.width, h.height, h.format, pixels);
    }

    private static int sign3(int bits) { return (bits & 4) != 0 ? bits - 8 : bits; }
    private static int etcPixel(int base, int magnitude, int sign) {
        return clamp(base + (sign != 0 ? -magnitude : magnitude));
    }
    private static void decodeETC(byte[] data, Header h, int[] pixels) {
        boolean alpha = h.format == 13;
        int pos = h.offset;
        for (int ty = 0; ty < h.height; ty += 8) for (int tx = 0; tx < h.width; tx += 8)
            for (int by = 0; by < 2; by++) for (int bx = 0; bx < 2; bx++) {
                long alphaBits = alpha ? le64(data, pos) : -1L;
                if (alpha) pos += 8;
                long block = le64(data, pos); pos += 8;
                boolean diff = ((block >>> 33) & 1) != 0;
                boolean splitHorizontal = ((block >>> 32) & 1) != 0;
                int[] c1 = new int[3], c2 = new int[3];
                if (diff) {
                    int[] first = {(int)((block>>>59)&31),(int)((block>>>51)&31),(int)((block>>>43)&31)};
                    int[] delta = {sign3((int)((block>>>56)&7)), sign3((int)((block>>>48)&7)),
                            sign3((int)((block>>>40)&7))};
                    for (int k=0;k<3;k++) {
                        c1[k] = expand(first[k],31);
                        c2[k] = expand(Math.max(0,Math.min(31,first[k]+delta[k])),31);
                    }
                } else {
                    c1[0] = (int)((block>>>60)&15)*17;
                    c2[0] = (int)((block>>>56)&15)*17;
                    c1[1] = (int)((block>>>52)&15)*17;
                    c2[1] = (int)((block>>>48)&15)*17;
                    c1[2] = (int)((block>>>44)&15)*17;
                    c2[2] = (int)((block>>>40)&15)*17;
                }
                int table1 = (int)((block>>>37)&7), table2 = (int)((block>>>34)&7);
                for (int py=0;py<4;py++) for (int px=0;px<4;px++) {
                    int bit = px*4+py;
                    boolean second = splitHorizontal ? py>=2 : px>=2;
                    int[] c = second ? c2 : c1;
                    int t = second ? table2 : table1;
                    int amount = MODIFIERS[t][(int)((block>>>bit)&1)];
                    int sign = (int)((block>>>(16+bit))&1);
                    int a = alpha ? (int)((alphaBits>>>(4*bit))&15)*17 : 255;
                    int x=tx+bx*4+px, y=ty+by*4+py;
                    pixels[y*h.width+x]=rgba(etcPixel(c[0],amount,sign),
                            etcPixel(c[1],amount,sign),etcPixel(c[2],amount,sign),a);
                }
            }
    }

    /** Encode replacement pixels using the exact original STXC dimensions, format and metadata. */
    public static byte[] encode(int[] pixels, int width, int height, byte[] original)
            throws IOException {
        Header h = inspect(original);
        if (width != h.width || height != h.height || pixels.length != width * height)
            throw new IOException("PNG dimensions " + width + "x" + height + " do not match the original " +
                    h.width + "x" + h.height + " BTEX. Resize the PNG first.");
        byte[] result = Arrays.copyOf(original, original.length);
        if (h.format == 12 || h.format == 13) {
            encodeETC(pixels, h, result);
            return result;
        }
        for (int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int pixel=pixels[y*width+x], i=tiledIndex(x,y,width);
            int r=channel(pixel,16), g=channel(pixel,8), b=channel(pixel,0), a=channel(pixel,24);
            int off, packed, l=lum(pixel);
            switch(h.format) {
                case 0:
                    off=h.offset+i*4; result[off]=(byte)a; result[off+1]=(byte)b;
                    result[off+2]=(byte)g; result[off+3]=(byte)r; break;
                case 1:
                    off=h.offset+i*3; result[off]=(byte)b; result[off+1]=(byte)g;
                    result[off+2]=(byte)r; break;
                case 2:
                    packed=(quant(r,31)<<11)|(quant(g,31)<<6)|(quant(b,31)<<1)|(a>=128?1:0);
                    off=h.offset+i*2; result[off]=(byte)packed; result[off+1]=(byte)(packed>>>8); break;
                case 3:
                    packed=(quant(r,31)<<11)|(quant(g,63)<<5)|quant(b,31);
                    off=h.offset+i*2; result[off]=(byte)packed; result[off+1]=(byte)(packed>>>8); break;
                case 4:
                    packed=(quant(r,15)<<12)|(quant(g,15)<<8)|(quant(b,15)<<4)|quant(a,15);
                    off=h.offset+i*2; result[off]=(byte)packed; result[off+1]=(byte)(packed>>>8); break;
                case 5:
                    off=h.offset+i*2; result[off]=(byte)a;result[off+1]=(byte)l;break;
                case 6:
                    off=h.offset+i*2; result[off]=(byte)g;result[off+1]=(byte)r;break;
                case 7: result[h.offset+i]=(byte)l;break;
                case 8: result[h.offset+i]=(byte)a;break;
                case 9: result[h.offset+i]=(byte)((quant(l,15)<<4)|quant(a,15));break;
                case 10: case 11:
                    off=h.offset+(i>>>1); packed=(h.format==10?quant(l,15):quant(a,15));
                    if((i&1)==0)result[off]=(byte)((result[off]&0xf0)|packed);
                    else result[off]=(byte)((result[off]&0x0f)|(packed<<4));break;
                default: throw new IOException("Unsupported BTEX format: "+h.format);
            }
        }
        return result;
    }

    private static final class SubBlock {
        final int r,g,b, table;
        final long selectors;
        final long cost;
        SubBlock(int r,int g,int b,int table,long selectors,long cost) {
            this.r=r;this.g=g;this.b=b;this.table=table;this.selectors=selectors;this.cost=cost;
        }
    }

    /** Simple ETC1 individual-mode encoder. It preserves format, but recompression is lossy. */
    private static void encodeETC(int[] pixels, Header h, byte[] output) {
        int off = h.offset;
        boolean alpha = h.format == 13;
        for (int ty=0;ty<h.height;ty+=8) for(int tx=0;tx<h.width;tx+=8)
            for(int by=0;by<2;by++) for(int bx=0;bx<2;bx++) {
                int sx=tx+bx*4, sy=ty+by*4;
                if(alpha) {
                    long alphas=0;
                    for(int y=0;y<4;y++) for(int x=0;x<4;x++)
                        alphas|=((long)quant(channel(pixels[(sy+y)*h.width+sx+x],24),15)) << (4*(x*4+y));
                    put64(output,off,alphas);off+=8;
                }
                long bestCost=Long.MAX_VALUE, bestBits=0;
                for(int flip=0;flip<2;flip++) {
                    SubBlock left=encodeSub(pixels,h.width,sx,sy,flip,0);
                    SubBlock right=encodeSub(pixels,h.width,sx,sy,flip,1);
                    long cost=left.cost+right.cost;
                    if(cost<bestCost) {
                        bestCost=cost;
                        bestBits=((long)left.r<<60)|((long)right.r<<56)|
                                ((long)left.g<<52)|((long)right.g<<48)|
                                ((long)left.b<<44)|((long)right.b<<40)|
                                ((long)left.table<<37)|((long)right.table<<34)|
                                ((long)flip<<32)|left.selectors|right.selectors;
                    }
                }
                put64(output,off,bestBits);off+=8;
            }
    }

    private static SubBlock encodeSub(int[] img,int stride,int sx,int sy,int horizontal,int half) {
        int rr=0,gg=0,bb=0;
        for(int y=0;y<4;y++) for(int x=0;x<4;x++) {
            if((horizontal!=0?y/2:x/2)!=half)continue;
            int c=img[(sy+y)*stride+sx+x];rr+=channel(c,16);gg+=channel(c,8);bb+=channel(c,0);
        }
        int r=quant((rr+4)/8,15),g=quant((gg+4)/8,15),b=quant((bb+4)/8,15);
        int br=r*17,bg=g*17,bl=b*17;
        long best=Long.MAX_VALUE,bits=0;int table=0;
        for(int t=0;t<8;t++) {
            long error=0, selectors=0;
            for(int y=0;y<4;y++) for(int x=0;x<4;x++) {
                if((horizontal!=0?y/2:x/2)!=half)continue;
                int c=img[(sy+y)*stride+sx+x];
                int pr=channel(c,16),pg=channel(c,8),pb=channel(c,0);
                long minError=Long.MAX_VALUE;int sel=0;
                for(int s=0;s<4;s++) {
                    int magnitude=MODIFIERS[t][s&1]; if((s&2)!=0)magnitude=-magnitude;
                    int dr=pr-clamp(br+magnitude),dg=pg-clamp(bg+magnitude),db=pb-clamp(bl+magnitude);
                    long e=(long)dr*dr+(long)dg*dg+(long)db*db;
                    if(e<minError){minError=e;sel=s;}
                }
                error+=minError;
                int idx=x*4+y;
                selectors|=((long)(sel&1))<<idx;
                selectors|=((long)((sel>>>1)&1))<<(16+idx);
            }
            if(error<best){best=error;bits=selectors;table=t;}
        }
        return new SubBlock(r,g,b,table,bits,best);
    }
}
