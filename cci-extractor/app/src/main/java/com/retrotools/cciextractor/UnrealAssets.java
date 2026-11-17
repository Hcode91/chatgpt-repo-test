package com.retrotools.cciextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Cooked Unreal Engine 4 packages (.uasset / .umap) of the 4.14 – 4.15 era, as shipped in unversioned pak builds,
 * and the Texture2D objects inside them.
 *
 * The package summary has no reliable version, so the reader follows the layout these builds use and checks it as it
 * goes: the summary must end exactly where the name table starts, every export must lie inside the file, and a texture
 * object must parse to exactly its stored size. Anything else is rejected rather than guessed at.
 */
final class UnrealAssets {
    static final int TAG = 0x9E2A83C1;

    static final class Package {
        List<String> names = new ArrayList<>();
        List<String[]> imports = new ArrayList<>(); // {class name, object name}
        List<Export> exports = new ArrayList<>();
        int totalHeaderSize;
    }

    static final class Export { int index, classIndex, serialSize, serialOffset; String name; }

    static final class Mip { int pos, size, width, height; }   // pos: offset of the pixel data in the package

    static final class Texture {
        String name; int export; String pixelFormat; int format; // format: UnityTextures code, or -1
        int width, height;                                       // the largest stored mip
        final List<Mip> mips = new ArrayList<>();
    }

    private static final class Le {
        final byte[] b; int p;
        Le(byte[] b, int p) { this.b = b; this.p = p; }
        void need(int n) throws IOException { if (n < 0 || p < 0 || p + n > b.length) throw new IOException("Unexpected end of package"); }
        int u8() throws IOException { need(1); return b[p++] & 255; }
        int i32() throws IOException { need(4); int v = (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16) | (b[p + 3] << 24); p += 4; return v; }
        long i64() throws IOException { long lo = i32() & 0xffffffffL, hi = i32() & 0xffffffffL; return lo | (hi << 32); }
        void skip(int n) throws IOException { need(n); p += n; }
        String fstr() throws IOException {
            int n = i32();
            if (n == 0) return "";
            if (n > 0) { if (n > 1 << 16) throw new IOException("Bad string"); need(n); String s = new String(b, p, n - 1, StandardCharsets.ISO_8859_1); p += n; return s; }
            n = -n; if (n > 1 << 16) throw new IOException("Bad string");
            need(n * 2); String s = new String(b, p, n * 2 - 2, StandardCharsets.UTF_16LE); p += n * 2; return s;
        }
    }

    static boolean isPackage(byte[] head) {
        return head.length >= 4 && ((head[0] & 255) | ((head[1] & 255) << 8) | ((head[2] & 255) << 16) | (head[3] << 24)) == TAG;
    }

    /** Size of the header to read before calling {@link #parse} (the TotalHeaderSize field), or -1. */
    static int headerSize(byte[] first) {
        try {
            Le r = new Le(first, 0);
            if (r.i32() != TAG) return -1;
            int legacy = r.i32();
            if (legacy != -7 && legacy != -6) return -1;
            r.i32(); r.i32(); r.i32();
            int custom = r.i32();
            if (custom < 0 || custom > 1000) return -1;
            r.skip(custom * 20);
            return r.i32();
        } catch (IOException e) { return -1; }
    }

    /** Parses the summary, names, imports and exports from the first TotalHeaderSize bytes of a package. */
    static Package parse(byte[] h) throws IOException {
        Le r = new Le(h, 0);
        if (r.i32() != TAG) throw new IOException("Not an Unreal package");
        int legacy = r.i32();
        if (legacy != -7 && legacy != -6) throw new IOException("Unreal package version " + legacy + " isn't supported");
        r.i32(); int ue4 = r.i32(); r.i32();
        int custom = r.i32();
        if (custom < 0 || custom > 1000) throw new IOException("Bad custom version count");
        r.skip(custom * 20);
        Package pk = new Package();
        pk.totalHeaderSize = r.i32();
        r.fstr();                       // folder name
        r.i32();                        // package flags
        int nameCount = r.i32(), nameOffset = r.i32();
        r.i32(); r.i32();               // gatherable text
        int exportCount = r.i32(), exportOffset = r.i32(), importCount = r.i32(), importOffset = r.i32();
        r.i32();                        // depends
        r.i32(); r.i32();               // string asset references
        r.i32();                        // searchable names
        r.i32();                        // thumbnail table
        r.skip(16);                     // guid
        int gens = r.i32();
        if (gens < 0 || gens > 10000) throw new IOException("Bad generation count");
        r.skip(gens * 8);
        for (int k = 0; k < 2; k++) { r.skip(10); r.fstr(); }  // saved-by and compatible engine versions
        r.i32();                        // compression flags
        if (r.i32() != 0) throw new IOException("Compressed packages aren't supported");
        r.i32();                        // package source
        int extra = r.i32();
        if (extra < 0 || extra > 10000) throw new IOException("Bad package list");
        for (int k = 0; k < extra; k++) r.fstr();
        r.i32();                        // asset registry data
        r.i64();                        // bulk data start
        r.i32();                        // world tile info
        int chunks = r.i32();
        if (chunks < 0 || chunks > 10000) throw new IOException("Bad chunk list");
        r.skip(chunks * 4);
        r.i32(); r.i32();               // preload dependencies
        if (r.p != nameOffset) throw new IOException("Unexpected package layout (summary ends at " + r.p + ", names at " + nameOffset + ")");
        if (nameCount < 0 || nameCount > 1_000_000 || exportCount < 0 || exportCount > 1_000_000 || importCount < 0 || importCount > 1_000_000)
            throw new IOException("Bad table sizes");
        for (int i = 0; i < nameCount; i++) { pk.names.add(r.fstr()); r.i32(); } // + 2 name hashes
        r.p = importOffset;
        for (int i = 0; i < importCount; i++) {
            r.skip(8);                  // class package
            String cls = name(pk, r.i32(), r.i32());
            r.i32();                    // outer
            String obj = name(pk, r.i32(), r.i32());
            pk.imports.add(new String[]{cls, obj});
        }
        r.p = exportOffset;
        for (int i = 0; i < exportCount; i++) {
            int start = r.p;
            Export x = new Export();
            x.index = i;
            x.classIndex = r.i32(); r.i32(); r.i32(); r.i32();   // class, super, template, outer
            x.name = name(pk, r.i32(), r.i32());
            r.i32();                                           // object flags
            x.serialSize = r.i32(); x.serialOffset = r.i32();
            r.p = start + 96;
            pk.exports.add(x);
        }
        return pk;
    }

    private static String name(Package pk, int index, int number) throws IOException {
        if (index < 0 || index >= pk.names.size()) throw new IOException("Bad name reference");
        String n = pk.names.get(index);
        return number == 0 ? n : n + "_" + (number - 1);
    }

    static String className(Package pk, int classIndex) {
        if (classIndex < 0 && -classIndex - 1 < pk.imports.size()) return pk.imports.get(-classIndex - 1)[1];
        if (classIndex > 0 && classIndex - 1 < pk.exports.size()) return pk.exports.get(classIndex - 1).name;
        return null;
    }

    static int formatOf(String pf) {
        switch (pf) {
            case "PF_ETC2_RGB": return UnityTextures.ETC2_RGB;
            case "PF_ETC2_RGBA": return UnityTextures.ETC2_RGBA8;
            case "PF_ETC1": return UnityTextures.ETC_RGB4;
            case "PF_G8": return UnityTextures.R8;
            case "PF_A8": return UnityTextures.ALPHA8;
            case "PF_B8G8R8A8": return UnityTextures.BGRA32;
            case "PF_R8G8B8A8": return UnityTextures.RGBA32;
            default: return -1;
        }
    }

    // ------------------------------------------------------------------ Texture2D
    private static void skipProperties(Le r, Package pk) throws IOException {
        for (int guard = 0; guard < 100_000; guard++) {
            String n = name(pk, r.i32(), r.i32());
            if (n.equals("None")) return;
            String type = name(pk, r.i32(), r.i32());
            int size = r.i32();
            r.i32();                                   // array index
            switch (type) {
                case "StructProperty": r.skip(8); r.skip(16); break;
                case "BoolProperty": r.skip(1); break;
                case "ByteProperty": case "EnumProperty": case "ArrayProperty": case "SetProperty": r.skip(8); break;
                case "MapProperty": r.skip(16); break;
                default: break;
            }
            if (r.u8() != 0) r.skip(16);               // property guid
            if (size < 0) throw new IOException("Bad property size");
            r.skip(size);
        }
        throw new IOException("Too many properties");
    }

    /** Parses a cooked Texture2D export from the whole package bytes. Fails unless it ends exactly at the export end. */
    static Texture texture(byte[] pkgBytes, Package pk, Export x) throws IOException {
        if (x.serialOffset < 0 || x.serialSize < 0 || (long) x.serialOffset + x.serialSize > pkgBytes.length)
            throw new IOException("Export outside the package");
        Le r = new Le(pkgBytes, x.serialOffset);
        skipProperties(r, pk);
        if (r.i32() != 0) r.skip(16);                  // object guid
        r.skip(4);                                     // strip flags (UTexture, UTexture2D)
        if (r.i32() != 1) throw new IOException("Texture isn't cooked");
        Texture t = new Texture();
        t.name = x.name; t.export = x.index;
        int formats = 0;
        while (true) {
            String pf = name(pk, r.i32(), r.i32());
            if (pf.equals("None")) break;
            int skip = r.i32();
            r.i32(); r.i32(); r.i32();                 // full size and slices
            String pfs = r.fstr();
            r.i32();                                   // first mip
            int mips = r.i32();
            if (mips < 0 || mips > 32) throw new IOException("Bad mip count");
            List<Mip> list = new ArrayList<>();
            for (int m = 0; m < mips; m++) {
                r.i32();                               // cooked
                int flags = r.i32();
                r.i32();                               // element count
                int disk = r.i32();
                long off = r.i64();
                Mip mip = new Mip();
                if ((flags & 1) != 0) throw new IOException("Pixel data stored outside the package isn't supported");
                if ((flags & 0x20) == 0) {
                    if (off != r.p) throw new IOException("Mip data isn't where its header says");
                    mip.pos = r.p; mip.size = disk; r.skip(disk);
                } else { mip.pos = -1; mip.size = 0; }
                mip.width = r.i32(); mip.height = r.i32();
                list.add(mip);
            }
            if (r.p != skip) throw new IOException("Texture platform data doesn't end where expected");
            if (formats++ == 0) {
                t.pixelFormat = pfs.isEmpty() ? pf : pfs;
                t.format = formatOf(pf);
                t.mips.addAll(list);
            }
        }
        if (r.p != x.serialOffset + x.serialSize) throw new IOException("Texture doesn't parse to its stored size");
        if (t.mips.isEmpty() || t.mips.get(0).pos < 0) throw new IOException("Texture has no pixel data");
        t.width = t.mips.get(0).width; t.height = t.mips.get(0).height;
        return t;
    }
}
