package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Random;

public class NesCodecTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        byte[] tiles = new byte[4096];new Random(42).nextBytes(tiles);
        int[] pixels=NesCodec.decodeTiles(tiles);
        byte[] encoded=NesCodec.encodeTiles(pixels, 128, 128);
        check(Arrays.equals(tiles,encoded), "Tile roundtrip failed");
        check(pixels.length == 16384 && pixels[0] >>> 24 == 255, "Pixel dimensions wrong");
        int[] wrong=pixels.clone();wrong[3]=0xff112233;
        try {NesCodec.encodeTiles(wrong,128,128);throw new AssertionError("Unexpectedly accepted non-2bpp colour");}
        catch(IOException ok) {check(ok.getMessage().contains("unsupported colour"),"Incorrect error");}
        Path chrRom=Files.createTempFile("nes-test-chr", ".nes");
        byte[] data=new byte[16+512+16384+8192];
        data[0]='N';data[1]='E';data[2]='S';data[3]=0x1a;data[4]=1;data[5]=1;data[6]=0x04;
        System.arraycopy(tiles,0,data,16+512+16384,tiles.length);
        try {
            Files.write(chrRom,data);
            try (RandomAccessFile file = new RandomAccessFile(chrRom.toFile(),"r")) {
                NesCodec.Info info=NesCodec.parse(file.getChannel());
                check(!info.usesChrRam() && info.pageCount()==2 && info.trainer,"bad CHR header");
                check(Arrays.equals(NesCodec.readPage(file.getChannel(),info,0),tiles),"bad CHR offset");
                byte[] replacement=tiles.clone();replacement[7]^=(byte)0x5a;
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                NesCodec.rebuild(file.getChannel(),out,info,0,replacement);
                byte[] rebuilt=out.toByteArray();
                check(rebuilt.length == data.length,"ROM size changed");
                for(int i=0;i<data.length;i++) if(i < info.chrOffset || i >= info.chrOffset+4096)
                    check(data[i]==rebuilt[i],"Untargeted byte changed "+i);
                check(rebuilt[(int)info.chrOffset+7]==replacement[7],"Patched byte missing");
            }
        }finally{Files.deleteIfExists(chrRom);}
        byte[] rom= { 'N','E','S',0x1a,8,0,0x21,0,0,0,0,0,0,0,0,0};
        Path chrRam=Files.createTempFile("nes-test-caesars", ".nes");
        try {
            Files.write(chrRam,Arrays.copyOf(rom,131088));
            try(RandomAccessFile file=new RandomAccessFile(chrRam.toFile(),"r")) {
                NesCodec.Info info=NesCodec.parse(file.getChannel());
                check(info.mapper==2 && info.usesChrRam() && info.pageCount()==32,"CHR-RAM Mapper 2 parsing failed");
                try{NesCodec.rebuild(file.getChannel(),new ByteArrayOutputStream(),info,0,tiles);
                    throw new AssertionError("CHR-RAM rewrite unexpectedly accepted");}
                catch(IOException ok){check(ok.getMessage().contains("CHR-RAM"),"incorrect safeguard");}
            }
        }finally{Files.deleteIfExists(chrRam);}
        if(args.length>0){
            try(RandomAccessFile file=new RandomAccessFile(args[0],"r")) {
                NesCodec.Info i=NesCodec.parse(file.getChannel());
                check(i.mapper==2 && i.usesChrRam() && i.prgBytes==131072 && i.pageCount()==32,"actual Caesar game wrong");
                byte[] first=NesCodec.readPage(file.getChannel(),i,0);
                check(Arrays.equals(first,NesCodec.encodeTiles(NesCodec.decodeTiles(first),128,128)),"Caesar PRG tile decode mismatch");
                System.out.println("Actual Caesars Palace: "+i.description()+", 32 candidate PRG pages");
            }
        }
        System.out.println("NES codec smoke tests passed");
    }
}
