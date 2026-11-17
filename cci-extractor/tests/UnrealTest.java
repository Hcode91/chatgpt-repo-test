package com.retrotools.cciextractor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unreal Engine 4 OBB / pak support on a synthetic OBB (fixtures/unreal, built by gen_unreal_fixture.py and re-read by
 * repak): scan, decode against texture2ddecoder, patch textures in a stored and a multi-block zlib package, rebuild the
 * OBB and a loose pak, then re-read everything, check hashes and that untouched files are byte-identical.
 */
public class UnrealTest {
    static int checks = 0;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checks++; }

    static String pixelSha(UnrealAssets.Texture t, int[] px) {
        boolean single = UnityTextures.singleChannel(t.format);
        byte[] buf = new byte[px.length * (single ? 1 : 4)]; int p = 0;
        for (int c : px) {
            if (single) buf[p++] = (byte) c;
            else { buf[p++] = (byte) (c >> 16); buf[p++] = (byte) (c >> 8); buf[p++] = (byte) c; buf[p++] = (byte) (c >>> 24); }
        }
        return UnityObb.sha1(buf);
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "tests/fixtures/unreal");
        File obb = new File(dir, "unreal_fixture.obb");
        List<String[]> expected = new ArrayList<>();
        for (String line : new String(Files.readAllBytes(new File(dir, "expected.tsv").toPath()), "UTF-8").split("\n"))
            if (!line.trim().isEmpty()) expected.add(line.split("\t"));

        File outObb = File.createTempFile("unreal_rebuilt", ".obb"); outObb.deleteOnExit();
        Map<String, int[]> edits = new HashMap<>();
        try (FileInputStream in = new FileInputStream(obb); FileChannel ch = in.getChannel()) {
            check(UnrealObb.isUnreal(ch), "fixture detected as Unreal");
            check(!UnityObb.isUnity(ch), "not mistaken for Unity");
            UnrealObb.Info info = UnrealObb.scan(ch, obb.getName(), null);
            check(!info.single && info.paks.size() == 1 && info.paks.get(0).entry.endsWith(".pak"), "one pak inside the OBB");
            check(info.paks.get(0).pak.version == 3 && info.paks.get(0).pak.entries.size() == 5, "pak v3 with 5 files");
            check(info.textures == expected.size() && info.packages.size() == 4 && info.failed == 0, info.description());
            for (String[] e : expected) {
                UnrealObb.Row r = new UnrealObb.Row();
                r.pak = info.paks.get(0).entry; r.pkg = e[0]; r.export = Integer.parseInt(e[1]);
                Object[] f = UnrealObb.find(info, r);
                check(f != null, "found " + e[2]);
                UnrealObb.PackageInfo p = (UnrealObb.PackageInfo) f[0];
                UnrealAssets.Texture t = (UnrealAssets.Texture) f[1];
                check(t.name.equals(e[2]) && t.pixelFormat.equals(e[3]) && t.width == Integer.parseInt(e[4]) &&
                        t.height == Integer.parseInt(e[5]) && t.mips.size() == Integer.parseInt(e[6]), "metadata of " + t.name);
                int[] px = UnrealObb.decode(info, p, t);
                check(pixelSha(t, px).equals(e[7]), "pixels of " + t.name + " match texture2ddecoder");
                String row = UnrealObb.row(info, p, t, UnrealObb.pngPath(p, t), "x");
                check(UnrealObb.parseIndex(UnrealObb.INDEX_HEADER + row).get(0).export == t.export, "index row " + t.name);
            }
            // png naming: a single texture named like its package → package folder + name; several → sub-folder
            UnrealObb.PackageInfo grey = null, level = null;
            for (UnrealObb.PackageInfo p : info.packages) { if (p.path.endsWith("T_Grey.uasset")) grey = p; if (p.path.endsWith("Level.umap")) level = p; }
            check(UnrealObb.pngPath(grey, grey.textures.get(0)).equals("Game/Content/Tex/T_Grey.png"), "png path single");
            check(UnrealObb.pngPath(level, level.textures.get(1)).equals("Game/Content/Maps/Level/T_LevelB__1.png"), "png path multi");

            // edit: stored G8 package, multi-block ETC2 package, and one of two textures in the map
            Map<Integer, Map<String, byte[]>> changed = new LinkedHashMap<>();
            for (UnrealObb.PackageInfo p : info.packages) for (UnrealAssets.Texture t : p.textures) {
                if (!(t.name.equals("T_Grey") || t.name.equals("T_Etc") || t.name.equals("T_LevelA"))) continue;
                int[] px = UnrealObb.decode(info, p, t);
                for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xFF000000) | (~px[i] & 0xFFFFFF);
                byte[] pkg = changed.computeIfAbsent(p.pak, k -> new LinkedHashMap<>()).computeIfAbsent(p.path,
                        k -> { try { return info.paks.get(0).pak.read(UnrealObb.entry(info, p)); } catch (Exception ex) { throw new RuntimeException(ex); } });
                UnrealObb.patch(pkg, t, px, t.width, t.height);
                UnrealAssets.Mip m = t.mips.get(0);
                edits.put(t.name, UnityTextures.decode(java.util.Arrays.copyOfRange(pkg, m.pos, m.pos + m.size), t.format, t.width, t.height, false));
            }
            try (FileOutputStream fo = new FileOutputStream(outObb)) { UnrealObb.rebuild(ch, info, changed, fo, null); }

            // the rebuilt OBB: same zip entries, pak re-reads (index SHA-1 checked on open), edits in place
            try (FileInputStream in2 = new FileInputStream(outObb); FileChannel c2 = in2.getChannel()) {
                UnrealObb.Info again = UnrealObb.scan(c2, "again", null);
                check(again.textures == expected.size() && again.failed == 0, "rebuilt OBB scans: " + again.description());
                UnrealPak np = again.paks.get(0).pak, op = info.paks.get(0).pak;
                check(np.indexOffset > op.indexOffset, "new data appended after the old index position");
                for (UnrealPak.Entry e : op.entries) {
                    UnrealPak.Entry ne = np.byName.get(e.name);
                    byte[] stored = new byte[(int) ne.size];
                    again.paks.get(0).pak.src.read(ne.dataStart, stored, 0, stored.length);
                    check(java.util.Arrays.equals(UnrealPak.sha1(stored, 0, stored.length), ne.hash), "entry hash of " + e.name);
                    boolean edited = changed.get(0).containsKey(e.name);
                    if (!edited) {
                        check(ne.offset == e.offset && java.util.Arrays.equals(np.read(ne), op.read(e)), "unchanged " + e.name);
                    } else check(java.util.Arrays.equals(np.read(ne), changed.get(0).get(e.name)), "new content of " + e.name);
                }
                for (UnrealObb.PackageInfo p : again.packages) for (UnrealAssets.Texture t : p.textures) {
                    int[] px = UnrealObb.decode(again, p, t);
                    if (edits.containsKey(t.name)) check(java.util.Arrays.equals(px, edits.get(t.name)), "edit of " + t.name + " read back");
                    else for (String[] e : expected) if (e[2].equals(t.name)) check(pixelSha(t, px).equals(e[7]), "untouched " + t.name);
                }
                // smaller mips were re-encoded too and still decode at their sizes
                for (UnrealObb.PackageInfo p : again.packages) for (UnrealAssets.Texture t : p.textures) for (UnrealAssets.Mip m : t.mips) {
                    byte[] d = np.read(UnrealObb.entry(again, p), m.pos, m.size);
                    check(UnityTextures.decode(d, t.format, m.width, m.height, false).length == m.width * m.height, "mip " + m.width + "x" + m.height + " of " + t.name);
                }
            }
            // zip level: same entries in the same order, only the pak changed
            try (java.util.zip.ZipFile a = new java.util.zip.ZipFile(obb); java.util.zip.ZipFile b = new java.util.zip.ZipFile(outObb)) {
                List<? extends java.util.zip.ZipEntry> ea = java.util.Collections.list(a.entries()), eb = java.util.Collections.list(b.entries());
                check(ea.size() == eb.size(), "zip entry count");
                for (int i = 0; i < ea.size(); i++) {
                    check(ea.get(i).getName().equals(eb.get(i).getName()) && eb.get(i).getMethod() == java.util.zip.ZipEntry.STORED, "zip entry " + i);
                    if (!ea.get(i).getName().endsWith(".pak")) check(ea.get(i).getCrc() == eb.get(i).getCrc(), "unchanged " + ea.get(i).getName());
                }
            }

            // a loose pak works too
            File loose = File.createTempFile("loose", ".pak"); loose.deleteOnExit();
            UnrealObb.PakInfo pi = info.paks.get(0);
            byte[] pakBytes = new byte[(int) pi.size];
            UnityBundle.of(ch, pi.offset, pi.size).read(0, pakBytes, 0, pakBytes.length);
            Files.write(loose.toPath(), pakBytes);
            try (FileInputStream li = new FileInputStream(loose); FileChannel lc = li.getChannel()) {
                check(UnrealObb.isUnreal(lc), "loose pak detected");
                UnrealObb.Info single = UnrealObb.scan(lc, "loose.pak", null);
                check(single.single && single.textures == expected.size(), "loose pak scan");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                UnrealObb.rebuild(lc, single, changed, bo, null);
                byte[] rebuilt = bo.toByteArray();
                check(java.util.Arrays.equals(java.util.Arrays.copyOf(rebuilt, (int) single.paks.get(0).pak.indexOffset),
                        java.util.Arrays.copyOf(pakBytes, (int) single.paks.get(0).pak.indexOffset)), "original bytes before the index kept");
                UnrealPak rp = UnrealPak.open(UnityBundle.of(rebuilt));
                check(java.util.Arrays.equals(rp.read(rp.byName.get("Game/Content/Tex/T_Etc.uasset")), changed.get(0).get("Game/Content/Tex/T_Etc.uasset")), "loose pak rebuilt");
            }

            // damage: a flipped byte in the index fails the SHA-1 check; random bytes aren't a pak
            byte[] bad = pakBytes.clone();
            bad[(int) info.paks.get(0).pak.indexOffset + 10] ^= 1;
            try { UnrealPak.open(UnityBundle.of(bad)); check(false, "damaged index accepted"); }
            catch (java.io.IOException expectedFailure) { check(expectedFailure.getMessage().contains("checksum"), "index checksum reported"); }
            byte[] junk = new byte[2048]; new java.util.Random(3).nextBytes(junk);
            check(!UnrealPak.isPak(UnityBundle.of(junk)), "junk isn't a pak");
            try { UnrealAssets.parse(junk); check(false, "junk package accepted"); } catch (java.io.IOException expectedFailure) { checks++; }
        }
        System.out.println("UnrealTest OK (" + checks + " checks)");
    }
}
