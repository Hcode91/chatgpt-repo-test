package com.retrotools.cciextractor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unreal Engine 4 games on Android: an OBB (stored zip) holding one or more .pak files, or a loose .pak.
 * Lists every cooked Texture2D, decodes them, and rebuilds with edited textures: each changed package is patched in
 * place (re-encoded mips are exactly the original size), written back into its pak in place
 * (see {@link UnrealPak#buildInPlace}), and the OBB keeps its exact size and zip headers.
 */
final class UnrealObb {

    static final class PakInfo { String entry; long offset, size; UnrealPak pak; String error; }

    static final class PackageInfo {
        int pak; String path;
        final List<UnrealAssets.Texture> textures = new ArrayList<>();
    }

    static final class Info {
        boolean single; String fileName;
        final List<UnityObb.Entry> zipEntries = new ArrayList<>();
        final List<PakInfo> paks = new ArrayList<>();
        final List<PackageInfo> packages = new ArrayList<>();
        final List<UnrealModels.MeshRef> meshes = new ArrayList<>();
        int textures, unsupported, failed;
        String description() {
            int files = 0;
            for (PakInfo p : paks) if (p.pak != null) files += p.pak.entries.size();
            return "Unreal Engine " + (single ? "pak" : "OBB") + " · " + paks.size() + " pak(s), " + files + " files · " + textures +
                    " textures in " + packages.size() + " packages" + (meshes.isEmpty() ? "" : " · " + meshes.size() + " 3D models") + (unsupported > 0 ? " (" + unsupported + " in formats not supported yet)" : "") +
                    (failed > 0 ? " · " + failed + " couldn't be read" : "");
        }
    }

    static boolean isUnreal(FileChannel ch) throws IOException {
        if (UnrealPak.isPak(UnityBundle.of(ch, 0, ch.size()))) return true;
        if (!UnityObb.isZip(ch)) return false;
        try {
            for (UnityObb.Entry e : UnityObb.readZip(ch))
                if (e.method == 0 && e.name.toLowerCase(Locale.ROOT).endsWith(".pak") && UnrealPak.isPak(UnityBundle.of(ch, e.dataOffset, e.csize)))
                    return true;
        } catch (IOException ignored) { }
        return false;
    }

    static Info scan(FileChannel ch, String fileName, UnityObb.Progress progress) throws IOException {
        Info info = new Info();
        info.fileName = fileName;
        if (UnrealPak.isPak(UnityBundle.of(ch, 0, ch.size()))) {
            info.single = true;
            PakInfo p = new PakInfo(); p.entry = fileName; p.offset = 0; p.size = ch.size();
            info.paks.add(p);
        } else {
            info.zipEntries.addAll(UnityObb.readZip(ch));
            for (UnityObb.Entry e : info.zipEntries) {
                if (!e.name.toLowerCase(Locale.ROOT).endsWith(".pak")) continue;
                if (e.method != 0) throw new IOException(e.name + " is compressed inside the OBB; Unreal needs it stored. Was the OBB re-zipped?");
                PakInfo p = new PakInfo(); p.entry = e.name; p.offset = e.dataOffset; p.size = e.csize;
                info.paks.add(p);
            }
            if (info.paks.isEmpty()) throw new IOException("No .pak files in this OBB");
        }
        int total = 0;
        for (PakInfo p : info.paks) {
            p.pak = UnrealPak.open(UnityBundle.of(ch, p.offset, p.size));
            total += p.pak.entries.size();
        }
        int done = 0;
        for (int pi = 0; pi < info.paks.size(); pi++) {
            UnrealPak pak = info.paks.get(pi).pak;
            for (UnrealPak.Entry e : pak.entries) {
                done++;
                if (progress != null && done % 200 == 0) {
                    if (progress.cancelled()) throw new IOException("Cancelled");
                    progress.step(done, total, e.name);
                }
                String n = e.name.toLowerCase(Locale.ROOT);
                if (!(n.endsWith(".uasset") || n.endsWith(".umap")) || e.encrypted || e.usize < 64) continue;
                try {
                    byte[] first = pak.read(e, 0, (int) Math.min(e.usize, 1024));
                    int hs = UnrealAssets.headerSize(first);
                    if (hs <= 0 || hs > e.usize || hs > 64 << 20) continue;
                    UnrealAssets.Package pkg = UnrealAssets.parse(pak.read(e, 0, hs));
                    boolean any = false;
                    for (UnrealAssets.Export x : pkg.exports) {
                        String cls = UnrealAssets.className(pkg, x.classIndex);
                        if ("Texture2D".equals(cls)) any = true;
                        else if (UnrealModels.isMeshClass(cls)) {
                            UnrealModels.MeshRef m = new UnrealModels.MeshRef();
                            m.pak = pi; m.path = e.name; m.name = x.name; m.cls = cls; m.export = x.index; m.size = x.serialSize;
                            info.meshes.add(m);
                        }
                    }
                    if (!any) continue;
                    byte[] all = pak.read(e);
                    PackageInfo pi2 = new PackageInfo(); pi2.pak = pi; pi2.path = e.name;
                    for (UnrealAssets.Export x : pkg.exports) {
                        if (!"Texture2D".equals(UnrealAssets.className(pkg, x.classIndex))) continue;
                        try {
                            UnrealAssets.Texture t = UnrealAssets.texture(all, pkg, x);
                            pi2.textures.add(t);
                            info.textures++;
                            if (t.format < 0 || !UnityTextures.supported(t.format)) info.unsupported++;
                        } catch (IOException ex) { info.failed++; }
                    }
                    if (!pi2.textures.isEmpty()) info.packages.add(pi2);
                } catch (IOException ex) { info.failed++; }
            }
        }
        // characters first, then the biggest models
        info.meshes.sort((a, b) -> a.skeletal() != b.skeletal() ? (a.skeletal() ? -1 : 1) : Integer.compare(b.size, a.size));
        return info;
    }

    static UnrealPak.Entry entry(Info info, PackageInfo p) { return info.paks.get(p.pak).pak.byName.get(p.path); }

    /** Top mip as top-down ARGB. Single-channel formats come out grey. */
    static int[] decode(Info info, PackageInfo p, UnrealAssets.Texture t) throws IOException {
        if (t.format < 0 || !UnityTextures.supported(t.format)) throw new IOException(t.name + ": " + t.pixelFormat + " isn't supported yet");
        UnrealAssets.Mip m = t.mips.get(0);
        byte[] data = info.paks.get(p.pak).pak.read(entry(info, p), m.pos, m.size);
        return UnityTextures.decode(data, t.format, m.width, m.height, false);
    }

    /** Writes an edited image (top mip size, top-down ARGB) into a package's bytes: every stored mip, same sizes. */
    static void patch(byte[] pkgBytes, UnrealAssets.Texture t, int[] argb, int w, int h) throws IOException {
        if (w != t.width || h != t.height) throw new IOException(t.name + ": image is " + w + "x" + h + ", texture is " + t.width + "x" + t.height);
        if (t.format < 0 || !UnityTextures.supported(t.format)) throw new IOException(t.name + ": can't write " + t.pixelFormat + " yet");
        for (UnrealAssets.Mip m : t.mips) {
            if (m.pos < 0) continue;
            int[] level = (m.width == w && m.height == h) ? argb : UnityTextures.downsample(argb, w, h, m.width, m.height);
            byte[] enc = UnityTextures.encodeTopDown(level, m.width, m.height, t.format);
            if (enc.length != m.size) throw new IOException(t.name + ": mip " + m.width + "x" + m.height + " encodes to " + enc.length + " bytes, expected " + m.size);
            if (m.pos + m.size > pkgBytes.length) throw new IOException("Mip outside the package");
            System.arraycopy(enc, 0, pkgBytes, m.pos, m.size);
        }
    }

    /**
     * {@code changed}: pak index → (package path → new package bytes). Writes a file of exactly the original size:
     * Android games check the OBB's size (the Play Store expansion-file check) and won't start with one that differs.
     * Every zip header stays byte-for-byte; only the pak's bytes and its CRC-32 change.
     */
    static void rebuild(FileChannel ch, Info info, Map<Integer, Map<String, byte[]>> changed, OutputStream out, UnityObb.Progress progress) throws IOException {
        List<UnrealPak.Patch> all = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, byte[]>> e : changed.entrySet()) {
            PakInfo p = info.paks.get(e.getKey());
            if (progress != null) progress.step(0, 1, "Packing " + p.entry);
            List<UnrealPak.Patch> patches = p.pak.buildInPlace(e.getValue());
            if (info.single) { all.addAll(patches); continue; }
            for (UnrealPak.Patch q : patches) all.add(new UnrealPak.Patch(p.offset + q.at, q.data));
            // the pak's CRC-32 in its local header, central directory record and (if used) data descriptor
            UnityObb.Entry z = null;
            for (UnityObb.Entry ze : info.zipEntries) if (ze.name.equals(p.entry)) z = ze;
            if (z == null) throw new IOException(p.entry + " not found in the OBB");
            long crc = crc32(UnrealPak.overlay(UnityBundle.of(ch, p.offset, p.size), 0, p.size, patches), progress, p.entry);
            byte[] c = {(byte) crc, (byte) (crc >>> 8), (byte) (crc >>> 16), (byte) (crc >>> 24)};
            all.add(new UnrealPak.Patch(z.localOffset + 14, c));
            all.add(new UnrealPak.Patch(z.directoryRecord + 16, c));
            if ((z.flags & 8) != 0) {
                long d = z.dataOffset + z.csize;
                byte[] sig = new byte[4];
                UnityBundle.of(ch, 0, ch.size()).read(d, sig, 0, 4);
                boolean hasSig = (sig[0] & 255) == 0x50 && (sig[1] & 255) == 0x4b && sig[2] == 7 && sig[3] == 8;
                all.add(new UnrealPak.Patch(d + (hasSig ? 4 : 0), c));
            }
        }
        all.sort((a, b) -> Long.compare(a.at, b.at));
        UnityObb.Content content = UnrealPak.overlay(UnityBundle.of(ch, 0, ch.size()), 0, ch.size(), all);
        byte[] buf = new byte[1 << 20];
        long done = 0, total = ch.size();
        try (java.io.InputStream in = content.open()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null && (done & ((32 << 20) - 1)) < n) {
                    if (progress.cancelled()) throw new IOException("Cancelled");
                    progress.step((int) (done >> 20), (int) (total >> 20), "Writing " + (done >> 20) + " / " + (total >> 20) + " MB");
                }
            }
        }
        out.flush();
    }

    private static long crc32(UnityObb.Content c, UnityObb.Progress progress, String name) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] buf = new byte[1 << 20];
        long done = 0;
        try (java.io.InputStream in = c.open()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
                done += n;
                if (progress != null && (done & ((32 << 20) - 1)) < n) {
                    if (progress.cancelled()) throw new IOException("Cancelled");
                    progress.step((int) (done >> 20), (int) (c.length() >> 20), "Checking " + name + " " + (done >> 20) + " MB");
                }
            }
        }
        return crc.getValue();
    }

    // ------------------------------------------------------------------ export index
    static final String INDEX_NAME = "unreal_textures.tsv";
    static final String INDEX_HEADER = "# Game Asset Extractor · Unreal texture index. Edit the PNGs (keep names), then rebuild.\n" +
            "# pak\tpackage\texport\tname\tformat\twidth\theight\tmips\tpng\tsha1\n";

    static final class Row { String pak, pkg, name, format, png, sha1; int export, width, height, mips; }

    static String row(Info info, PackageInfo p, UnrealAssets.Texture t, String png, String sha1) {
        return clean(info.paks.get(p.pak).entry) + "\t" + clean(p.path) + "\t" + t.export + "\t" + clean(t.name) + "\t" + t.pixelFormat + "\t" +
                t.width + "\t" + t.height + "\t" + t.mips.size() + "\t" + clean(png) + "\t" + sha1 + "\n";
    }
    private static String clean(String s) { return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    static List<Row> parseIndex(String text) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.replace("\r", "");
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 10) throw new IOException(INDEX_NAME + " has a broken line: " + line);
            Row r = new Row();
            try {
                r.pak = f[0]; r.pkg = f[1]; r.export = Integer.parseInt(f[2]); r.name = f[3]; r.format = f[4];
                r.width = Integer.parseInt(f[5]); r.height = Integer.parseInt(f[6]); r.mips = Integer.parseInt(f[7]); r.png = f[8]; r.sha1 = f[9];
            } catch (NumberFormatException e) { throw new IOException(INDEX_NAME + " has a broken line: " + line); }
            rows.add(r);
        }
        return rows;
    }

    /** Folder-safe PNG path: the package's folder, then the texture name (in a sub-folder if a package has several). */
    static String pngPath(PackageInfo p, UnrealAssets.Texture t) {
        String path = p.path;
        int slash = path.lastIndexOf('/'), dot = path.lastIndexOf('.');
        String dir = slash < 0 ? "" : path.substring(0, slash);
        String stem = path.substring(slash + 1, dot > slash ? dot : path.length());
        StringBuilder sb = new StringBuilder();
        for (String part : dir.split("/")) if (!part.isEmpty() && !part.equals("..")) sb.append(UnityObb.safe(part)).append('/');
        if (p.textures.size() == 1 && t.name.equals(stem)) return sb + UnityObb.safe(stem) + ".png";
        return sb + UnityObb.safe(stem) + "/" + UnityObb.safe(t.name) + "__" + t.export + ".png";
    }

    static Object[] find(Info info, Row r) {
        for (PackageInfo p : info.packages) {
            if (!p.path.equals(r.pkg) || (!info.single && !info.paks.get(p.pak).entry.equals(r.pak))) continue;
            for (UnrealAssets.Texture t : p.textures) if (t.export == r.export) return new Object[]{p, t};
        }
        return null;
    }
}
