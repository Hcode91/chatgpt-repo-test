# IPSW Toolkit for Android

Native Android firmware browser and legacy encrypted DMG decryptor. Select an IPSW using the Android file picker. Browse its ZIP entries, export individual components, or decrypt a legacy encrcdsa v2 root filesystem disk image using a matching 72-hex-character VFDecrypt key.

Test sample: iPod1,1_1.1.5_4B1_Restore.ipsw. The published key is available from The Apple Wiki. Its root DMG decrypted and the first zlib block decompressed correctly in a separate reference test.

Uses only the Android SDK and built-in JDK cryptography. No network permission. Files and keys remain on the device.

Limitations: no IMG2/IMG3/IMG4 or AEA payload decryption; no HFS+ directory browsing; no iPhone emulation. The APK still needs device testing.

## Build
GitHub Actions > Build Android APK. The Actions artifact IPSW-Toolkit-debug contains the installable app-debug.apk.
