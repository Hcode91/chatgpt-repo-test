package com.retrotools.cciextractor;

import com.android.apksig.ApkSigner;
import com.android.apksig.apk.MinSdkVersionException;
import com.android.apksig.util.DataSink;
import com.android.apksig.util.DataSource;
import com.android.apksig.util.DataSources;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Puts a modified file back into an APK and signs the result so Android will install it.
 *
 * Step 1 writes an unsigned copy: every entry byte-for-byte (same compression), the chosen entry replaced, the old
 * signature files in META-INF dropped. Step 2 signs it with Google's apksig (the library behind the SDK's apksigner):
 * v1 (JAR) where the app's minSdk needs it, plus v2 and v3, with uncompressed entries aligned the way Android requires.
 *
 * The result is signed with this app's own key, not the game's, so Android treats it as a different signer: the
 * original game has to be uninstalled before installing it (later rebuilds signed with the same key update in place).
 */
final class ApkRepack {

    /** JAR signature files of the old signer, which must not survive into the new APK. */
    static boolean isOldSignature(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0) return false;
        return n.equals("META-INF/MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC");
    }

    /** Entries largest first (what the user most likely wants to swap is a big data file). */
    static List<UnityObb.Entry> entriesBySize(FileChannel apk) throws IOException {
        List<UnityObb.Entry> list = new ArrayList<>(UnityObb.readZip(apk));
        list.removeIf(UnityObb.Entry::dir);
        Collections.sort(list, (a, b) -> Long.compare(b.usize, a.usize));
        return list;
    }

    /** Writes the APK with {@code entryName} replaced by {@code replacement} (stored) and old signatures removed. */
    static void writeUnsigned(FileChannel apk, String entryName, File replacement, OutputStream out, UnityObb.Progress progress) throws IOException {
        writeUnsigned(apk, entryName, UnityObb.of(replacement), out, progress);
    }

    static void writeUnsigned(FileChannel apk, String entryName, UnityObb.Content replacement, OutputStream out, UnityObb.Progress progress) throws IOException {
        List<UnityObb.Entry> keep = new ArrayList<>();
        boolean found = false;
        for (UnityObb.Entry e : UnityObb.readZip(apk)) {
            if (isOldSignature(e.name)) continue;
            if (e.name.equals(entryName)) found = true;
            keep.add(e);
        }
        if (!found) throw new IOException(entryName + " isn't in this APK");
        Map<String, UnityObb.Content> replace = new HashMap<>();
        replace.put(entryName, replacement);
        UnityObb.writeZipContent(apk, keep, replace, out, progress);
    }

    /**
     * Signs {@code unsigned} into {@code out} (written through {@code outSink}, read back through {@code outSource},
     * both the same file). minSdk is read from the APK's manifest; {@code fallbackMinSdk} is used only if it can't be.
     */
    static void sign(DataSource unsigned, DataSink outSink, DataSource outSource, PrivateKey key, X509Certificate cert,
                     int fallbackMinSdk) throws Exception {
        List<X509Certificate> chain = Collections.singletonList(cert);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("CERT", key, chain).build();
        try {
            build(signer, unsigned, outSink, outSource, null).sign();
        } catch (MinSdkVersionException e) {
            build(signer, unsigned, outSink, outSource, fallbackMinSdk).sign();
        }
    }

    private static ApkSigner build(ApkSigner.SignerConfig signer, DataSource in, DataSink sink, DataSource source, Integer minSdk) {
        ApkSigner.Builder b = new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(in)
                .setOutputApk(sink, source)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setCreatedBy("Game Asset Extractor");
        if (minSdk != null) b.setMinSdkVersion(minSdk);
        return b.build();
    }

    /** File-to-file convenience (tests, and the fallback when the destination can't be read back). */
    static void sign(File unsigned, File out, PrivateKey key, X509Certificate cert, int fallbackMinSdk) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        try (RandomAccessFile in = new RandomAccessFile(unsigned, "r");
             RandomAccessFile o = new RandomAccessFile(out, "rw")) {
            sign(DataSources.asDataSource(in), com.android.apksig.util.DataSinks.asDataSink(o), DataSources.asDataSource(o),
                    key, cert, fallbackMinSdk);
        }
    }

    /** Stages the unsigned APK in {@code tempDir}, then signs it into {@code signedOut}. */
    static void repack(FileChannel apk, String entryName, File replacement, File tempDir, File signedOut,
                       PrivateKey key, X509Certificate cert, UnityObb.Progress progress) throws Exception {
        repack(apk, entryName, UnityObb.of(replacement), tempDir, signedOut, key, cert, progress);
    }

    static void repack(FileChannel apk, String entryName, UnityObb.Content replacement, File tempDir, File signedOut,
                       PrivateKey key, X509Certificate cert, UnityObb.Progress progress) throws Exception {
        File unsigned = File.createTempFile("unsigned", ".apk", tempDir);
        try {
            try (FileOutputStream fo = new FileOutputStream(unsigned);
                 java.io.BufferedOutputStream bo = new java.io.BufferedOutputStream(fo, 1 << 20)) {
                writeUnsigned(apk, entryName, replacement, bo, progress);
            }
            if (progress != null) progress.step(1, 1, "Signing…");
            sign(unsigned, signedOut, key, cert, 21);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            unsigned.delete();
        }
    }
}
