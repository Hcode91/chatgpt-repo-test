# IPSW Toolkit for Android (v0.2)

Offline Android app for exploring iPhone/iPod restore IPSWs and decrypting supported historical root filesystem DMGs.

## Device and firmware key picker

- Choose original iPhone, iPhone 3G, iPhone 3GS, iPhone 4 (GSM, Rev A, CDMA), iPhone 4S, or iPod touch generations 1–4.
- Auto-select the device and firmware version/build from IPSW metadata or filenames when available.
- Includes **27 curated historical filesystem key records**, not an exhaustive collection for all Apple firmware.
- Keys auto-populate only after the **device, firmware version, build, and exact root filesystem DMG name** match.
- Tap *Reapply matching offline key* to replace a manually modified key.
- Import additional keys in JSON format to extend the offline catalogue. Imported records stay in the app's private device storage.
- Paste a key manually if the firmware is not in the catalogue.

## Importing more keys

Create a UTF-8 JSON document shaped like:

```json
{
  "keys": [
    {
      "model": "iPod1,1",
      "version": "1.1.5",
      "build": "4B1",
      "filesystem": "018-3755-1.dmg",
      "key": "c7973558e8f6af22e38d4573737d1533f1d5eec202bf86a32d941975d76f8906c7f0afe4"
    }
  ]
}
```

The app validates 72-hex-character VFDecrypt keys, supported model identifiers, and component filenames. Importing keys does not verify that the keys actually decrypt an image, so use reliable firmware-specific sources.

## What this build supports

- Select an IPSW through Android's file picker and browse archive components.
- Export individual components unchanged.
- Decrypt legacy `encrcdsa` v2 AES-CBC/HMAC-SHA1 protected root filesystem DMGs using a matching VFDecrypt key.
- Save output with Android's document picker.
- No network permission or account required.

**Not included:** complete key coverage, automatic online lookup, IMG2/IMG3/IMG4 decryption, AEA archives, HFS+ file browsing, or iOS emulation.

## Building the APK

This repository builds with GitHub Actions on the `feature/ipsw-toolkit` branch. Open Actions → Build Android APK → latest successful run and download the `IPSW-Toolkit-debug` artifact containing `app-debug.apk`.

You can build with Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Java 17, and Android SDK 35 using `gradle :app:assembleDebug`.

Firmware source/attribution: [The Apple Wiki firmware keys](https://theapplewiki.com/wiki/Firmware_Keys). Preserve attribution and applicable source licensing for any reuse. The embedded keys are published historical firmware keys, not device-specific private secrets.
