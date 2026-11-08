# PS3 ELF Extractor for Android (alpha)

Standalone Android ARM64 proof-of-concept for decrypting **personally supplied PS3 SELF/EBOOT.BIN** files to an ELF using [naehrwert/scetool](https://github.com/naehrwert/scetool), with RPCS3's [unself.cpp](https://github.com/RPCS3/rpcs3/blob/master/rpcs3/Crypto/unself.cpp) serving as a reference for SELF/NPDRM processing.

## Status

- Functional app UI with Android document pickers, content-ID detection, RAP size checks, local file staging, native-process invocation, timeout protection, logs and ELF64 big-endian PowerPC verification.
- The GitHub Actions build fetches and compiles the pinned scetool sources into an executable packaged as `libps3elf.so` (Android native library extraction lets the application execute it).
- **Real-world decryption remains unverified until tested with a permitted EBOOT, matching RAP, and compatible scetool keyset.** We must not claim an ELF was extracted solely because the APK compiled.
- Users supply their **own scetool-format `keys` file** (including a compatible SELF keyset and NPDRM entries); the 16-byte RAP alone is not enough. Keys are not embedded, uploaded, or committed.
- Output is private in the Android app sandbox until the user saves it through Android's document provider. No INTERNET permission.

**Important:** The packaged `scetool-source-gplv2.tar.gz` contains source code, not the PS3 `keys` keyset. The keyset must be a separate plaintext scetool-compatible file. v0.2 adds `libc++_shared.so` to address the Android executable loader error observed on-device.

## Workflow

1. Install the APK artifact from the `PS3 ELF Extractor Android APK` GitHub Action.
2. Select `EBOOT.BIN` (SCE SELF with embedded PPC64 ELF header).
3. Select the corresponding 16-byte `.rap` licence file. The app matches the content ID embedded in EBOOT to the selected RAP filename when known, and stages it under the required name for scetool.
4. Select a **scetool-compatible keys file** from your own legally obtained configuration.
5. Tap Extract ELF. The app runs scetool in a private working directory. If and only if it produces a verifiable ELF, select Save ELF to export.

### inFAMOUS compatibility

The uploaded US PlayStation Network Welcome Back executable is identified by `UP9000-NPUA80480_00-WELCOMEBACKOFFER`, while the separate existing inFAMOUS recompilation project currently only accepts the European `BCES00220` ELF fingerprint. Successful extraction does **not** mean the game can be run on Android, or that the recompilation pipeline supports the US PSN build.

## Building

```
# GitHub Actions is the supported repeatable build path.
# Workflow: .github/workflows/ps3-elf-extractor.yml
# APK artifact: ps3-elf-extractor-arm64-apk
```

GitHub Actions clones a pinned revision of scetool and invokes Android NDK clang/clang++ (API 26), then Gradle 8.9 / AGP 8.7.3 builds an ARM64 APK. No proprietary game files, RAPs or keysets enter CI.

### Licensing

scetool is GPLv2, and the derivative binary must be distributed under its GPLv2 terms, with corresponding source and notices. This project is for research and compatibility work. The APK bundles the executable compiled from an externally fetched pinned GPLv2 source. When publishing an APK, include the corresponding scetool source (or compliant equivalent) and license notices for downstream recipients. Do not redistribute third-party game binaries, licences or private keysets.

### Known limits

- The native engine may require additional compatibility fixes for Android. Watch the CI compile and process logs.
- Some SELF revisions or NPDRM licensing modes may be unsupported by this version of scetool.
- A matching RAP filename/content ID and a valid ELF header do not alone prove that the whole executable decrypted correctly.
- This is a separate app, deliberately leaving the existing inFAMOUS runtime untouched.
