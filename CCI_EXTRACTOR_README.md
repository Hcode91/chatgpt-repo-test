# Game Asset Extractor v0.3

This branch contains the Android 3DS CCI and NES graphics extraction app under `cci-extractor/`.

The APK is built by `.github/workflows/cci-extractor.yml`. It contains NO game ROMs or copyrighted graphic assets.

NES CHR-ROM games can export/import 128x128 four-colour tile sheets. CHR-RAM games (including Caesars Palace NES) export exploratory PRG candidates only; ROM rebuilding is disabled for those games.

From v0.8 the Convert tab can also turn encrypted CIAs into decrypted CCIs. That uses native code under `cci-extractor/native/` (ctrtool and makerom from 3DSGuy/Project_CTR plus the cia3ds-android JNI layer, MIT; mbedTLS, Apache-2.0). Licences and notices are kept alongside the sources.
