package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only Nintendo DS NitroFS FNT/FAT parser. No BIOS or decryption required. */
public final class NdsArchive {
    private static final long MAX_TABLE_SIZE = 32L * 1024 * 1024;
    private static final int MAX_FILES = 100000;
    private static final int MAX_DEPTH = 64;
    public static final class Entry {
        public final String path;
        public final long offset, size;
        public final int id;
        Entry(String path, long offset, long size, int id) {
            this.path = path; this.offset = offset; this.size = size; this.id = id;
        }
    }
    public static final class Info {
        public final String gameCode, title;
        public final List<Entry> entries;
        Info(String gameCode, String title, List<Entry> entries) {
            this.gameCode = gameCode; this.title = title;
            this.entries = Collections.unmodifiableList(entries);
        }
        public String description() {
            return "Nintendo DS · " + title + " [" + gameCode + "] · " + entries.size() + " NitroFS files";
        }
    }
    private static long u32(byte[] b, int o) {
        return ((long)b[o]&255L) | (((long)b[o+1]&255L)<<8) |
                (((long)b[o+2]&255L)<<16) | (((long)b[o+3]&255L)<<24);
    }
    private static int u16(byte[] b, int o) {return (b[o]&255)|((b[o+1]&255)<<8);}
    private static void readExact(FileChannel ch, ByteBuffer dst, long pos) throws IOException {
        while(dst.hasRemaining()) {
            int n=ch.read(dst,pos+dst.position());
            if(n<=0) throw new IOException("Truncated DS ROM");
        }
    }
    private static byte[] read(FileChannel ch, long pos, int len) throws IOException {
        byte[] b=new byte[len]; readExact(ch,ByteBuffer.wrap(b),pos); return b;
    }
    private static boolean safeRange(long offset, long length, long end) {
        return offset>=0 && length>=0 && offset<=end && length<=end-offset;
    }
    /** Check the official Nintendo logo checksum and bounded FNT/FAT pointers. */
    public static boolean isNds(FileChannel ch) throws IOException {
        if(ch.size()<0x200) return false;
        byte[] h=read(ch,0,0x200);
        long fnt=u32(h,0x40), fntSize=u32(h,0x44), fat=u32(h,0x48), fatSize=u32(h,0x4c);
        if(fntSize<8 || fatSize<8 || (fatSize&7)!=0 || fntSize>MAX_TABLE_SIZE || fatSize>MAX_TABLE_SIZE ||
                !safeRange(fnt,fntSize,ch.size()) || !safeRange(fat,fatSize,ch.size())) return false;
        // DS Nintendo logo CRC16/CCITT (stored at 0x15c) guards against misdetecting arbitrary ROMs.
        return crc16(h,0xc0,156)==u16(h,0x15c);
    }
    private static int crc16(byte[] data, int start, int len) {
        int crc=0xffff;
        for(int i=0;i<len;i++) {
            crc^=(data[start+i]&255);
            for(int b=0;b<8;b++) crc=((crc&1)!=0)?(crc>>>1)^0xa001:(crc>>>1);
        }
        return crc&0xffff;
    }
    public static Info parse(FileChannel ch) throws IOException {
        if (!isNds(ch)) throw new IOException("Unrecognised Nintendo DS ROM header / NitroFS tables");
        byte[] h=read(ch,0,0x200);
        int fntSize=(int)u32(h,0x44), fatSize=(int)u32(h,0x4c);
        byte[] fnt=read(ch,u32(h,0x40),fntSize), fat=read(ch,u32(h,0x48),fatSize);
        int dirCount=u16(fnt,6);
        if(dirCount<1 || dirCount>4096 || 8L*dirCount>fnt.length)
            throw new IOException("Invalid DS FNT directory table");
        List<Entry> out=new ArrayList<>();
        Set<Integer> visiting=new HashSet<>(), visited=new HashSet<>(), fileIds=new HashSet<>();
        parseDir(0,"",fnt,fat,dirCount,ch.size(),out,visiting,visited,fileIds,0);
        if(out.isEmpty()) throw new IOException("DS NitroFS has no named files");
        String title=new String(h,0,12,StandardCharsets.US_ASCII).replace('\0',' ').trim();
        String code=new String(h,12,4,StandardCharsets.US_ASCII);
        return new Info(code,title,out);
    }
    private static void parseDir(int id, String prefix, byte[] fnt, byte[] fat, int count,
                                  long romSize, List<Entry> out, Set<Integer> visiting,
                                  Set<Integer> visited, Set<Integer> fileIds, int depth) throws IOException {
        if(depth>MAX_DEPTH || id<0 || id>=count || visiting.contains(id) || visited.contains(id))
            throw new IOException("Invalid/cyclic DS NitroFS directory tree");
        visiting.add(id);
        long posLong=u32(fnt,id*8);
        if(posLong<8L*count || posLong>=fnt.length) throw new IOException("Invalid NitroFS folder offset");
        int cursor=(int)posLong, fileId=u16(fnt,id*8+4);
        while(true) {
            if(cursor>=fnt.length) throw new IOException("Unterminated DS directory");
            int flags=fnt[cursor++]&255;
            if(flags==0) break;
            int len=flags&0x7f;
            if(len<1 || cursor+len>fnt.length) throw new IOException("Malformed NitroFS entry");
            String name=new String(fnt,cursor,len,StandardCharsets.US_ASCII);
            cursor+=len;
            if(name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf(0)>=0)
                throw new IOException("Unsafe NitroFS filename");
            if((flags&0x80)!=0) {
                if(cursor+2>fnt.length) throw new IOException("Malformed NitroFS subdirectory");
                int child=u16(fnt,cursor)&0xfff;cursor+=2;
                parseDir(child,prefix+name+"/",fnt,fat,count,romSize,out,visiting,visited,fileIds,depth+1);
            } else {
                if(!fileIds.add(fileId) || fileId>=fat.length/8) throw new IOException("Invalid NitroFS file ID");
                long start=u32(fat,fileId*8),end=u32(fat,fileId*8+4);
                if(end<start || !safeRange(start,end-start,romSize))
                    throw new IOException("NitroFS file outside ROM: " + name);
                if(out.size()>=MAX_FILES) throw new IOException("Too many NitroFS files");
                out.add(new Entry("nitrofs/"+prefix+name,start,end-start,fileId));
                fileId++;
            }
        }
        visiting.remove(id);visited.add(id);
    }
    private NdsArchive() {}
}
