package com.retrotools.cciextractor;

import com.android.apksig.ApkVerifier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * APK repack: replace one file inside an APK, drop the old signature, sign with apksig, then verify the result with
 * apksig's ApkVerifier (the same checks Android's package installer runs) and check every entry.
 * Usage: ApkRepackTest <keystore.p12> <password>
 */
public class ApkRepackTest {
    static int checks = 0;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checks++; }

    static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    static void put(ZipOutputStream z, String name, byte[] data, boolean stored) throws Exception {
        ZipEntry e = new ZipEntry(name);
        if (stored) {
            CRC32 c = new CRC32(); c.update(data);
            e.setMethod(ZipEntry.STORED); e.setSize(data.length); e.setCompressedSize(data.length); e.setCrc(c.getValue());
        }
        z.putNextEntry(e); z.write(data); z.closeEntry();
    }

    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(a[0])) { ks.load(in, a[1].toCharArray()); }
        String alias = ks.aliases().nextElement();
        PrivateKey key = (PrivateKey) ks.getKey(alias, a[1].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        // a fake "APK": manifest isn't real binary XML, so minSdk falls back to 21 (v1 + v2 + v3)
        File dir = Files.createTempDirectory("apkrepack").toFile();
        File apk = new File(dir, "game.apk");
        byte[] obb = new byte[300_000]; new java.util.Random(1).nextBytes(obb);
        byte[] text = "keep me\n".repeat(400).getBytes("UTF-8");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(apk))) {
            put(z, "AndroidManifest.xml", new byte[]{3, 0, 8, 0, 0, 0, 0, 0}, false);
            put(z, "classes.dex", text, false);
            put(z, "resources.arsc", new byte[1001], true);   // odd size so later stored data needs aligning
            put(z, "assets/1", obb, true);
            put(z, "assets/notes.txt", text, false);
            put(z, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes("UTF-8"), false);
            put(z, "META-INF/OLDKEY.SF", "old".getBytes("UTF-8"), false);
            put(z, "META-INF/OLDKEY.RSA", "old".getBytes("UTF-8"), false);
            put(z, "META-INF/services/keep.txt", "service".getBytes("UTF-8"), false);
        }

        File replacement = new File(dir, "1_modded");
        byte[] modded = obb.clone(); for (int i = 0; i < 5000; i++) modded[i * 7] ^= 0x5A;
        Files.write(replacement.toPath(), modded);

        try (FileInputStream in = new FileInputStream(apk); FileChannel ch = in.getChannel()) {
            List<UnityObb.Entry> list = ApkRepack.entriesBySize(ch);
            check(list.get(0).name.equals("assets/1"), "largest entry listed first: " + list.get(0).name);
        }
        check(ApkRepack.isOldSignature("META-INF/CERT.RSA") && ApkRepack.isOldSignature("META-INF/MANIFEST.MF")
                && !ApkRepack.isOldSignature("META-INF/services/keep.txt") && !ApkRepack.isOldSignature("assets/x.RSA"), "signature-file filter");

        File out = new File(dir, "game_modded.apk");
        try (FileInputStream in = new FileInputStream(apk); FileChannel ch = in.getChannel()) {
            ApkRepack.repack(ch, "assets/1", replacement, dir, out, key, cert, null);
        }

        // verify like the installer would
        ApkVerifier.Result r = new ApkVerifier.Builder(out).setMinCheckedPlatformVersion(21).build().verify();
        StringBuilder errs = new StringBuilder();
        for (ApkVerifier.IssueWithParams i : r.getErrors()) errs.append(i).append("; ");
        check(r.isVerified(), "apksig verifies the new APK: " + errs);
        check(r.isVerifiedUsingV1Scheme() && r.isVerifiedUsingV2Scheme() && r.isVerifiedUsingV3Scheme(), "v1 + v2 + v3 signatures");
        check(r.getSignerCertificates().size() == 1 && r.getSignerCertificates().get(0).equals(cert), "signed with our certificate");

        try (ZipFile o = new ZipFile(out); ZipFile i = new ZipFile(apk)) {
            check(java.util.Arrays.equals(read(o.getInputStream(o.getEntry("assets/1"))), modded), "replaced file inside");
            check(o.getEntry("assets/1").getMethod() == ZipEntry.STORED, "replacement stored");
            for (String n : new String[]{"classes.dex", "resources.arsc", "assets/notes.txt", "AndroidManifest.xml", "META-INF/services/keep.txt"}) {
                check(java.util.Arrays.equals(read(o.getInputStream(o.getEntry(n))), read(i.getInputStream(i.getEntry(n)))), "unchanged " + n);
                check(o.getEntry(n).getMethod() == i.getEntry(n).getMethod(), "compression kept for " + n);
            }
            check(o.getEntry("META-INF/OLDKEY.SF") == null && o.getEntry("META-INF/OLDKEY.RSA") == null, "old signature removed");
        }
        // stored entries aligned to 4 bytes (Android requires it to map them directly)
        try (RandomAccessFile raf = new RandomAccessFile(out, "r"); FileChannel ch = raf.getChannel()) {
            for (UnityObb.Entry e : UnityObb.readZip(ch))
                if (e.method == 0 && !e.dir()) check(e.dataOffset % 4 == 0, e.name + " aligned (" + e.dataOffset + ")");
        }
        System.out.println("ApkRepackTest OK (" + checks + " checks)");
    }
}
