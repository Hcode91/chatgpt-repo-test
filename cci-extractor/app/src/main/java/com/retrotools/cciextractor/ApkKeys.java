package com.retrotools.cciextractor;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Calendar;

import javax.security.auth.x500.X500Principal;

/**
 * The key this app signs repacked APKs with: an RSA-2048 key in the Android Keystore, created on first use with a
 * 30-year self-signed certificate. It never leaves the device. Because it's always the same key, a later rebuild
 * installs over an earlier one without uninstalling (it's lost if the app is uninstalled or its data cleared).
 */
final class ApkKeys {
    static final String ALIAS = "game_asset_extractor_apk_signing";

    static final class Signer { final PrivateKey key; final X509Certificate cert; Signer(PrivateKey k, X509Certificate c) { key = k; cert = c; } }

    static Signer get() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            Calendar from = Calendar.getInstance(), to = Calendar.getInstance();
            from.add(Calendar.DAY_OF_YEAR, -1);
            to.add(Calendar.YEAR, 30);
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    // SHA-1 only for v1 signatures of apps that still support Android 4.2 and older
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=Game Asset Extractor modded build"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(from.getTime())
                    .setCertificateNotAfter(to.getTime())
                    .build());
            g.generateKeyPair();
        }
        KeyStore.PrivateKeyEntry e = (KeyStore.PrivateKeyEntry) ks.getEntry(ALIAS, null);
        return new Signer(e.getPrivateKey(), (X509Certificate) e.getCertificate());
    }
}
