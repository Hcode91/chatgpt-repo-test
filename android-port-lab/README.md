# inFAMOUS Android Port Lab (experimental, NOT PLAYABLE)

This is a real Android ARM64 JNI/C++ APK build probe for the native-port feasibility of [inFAMOUSreborn](https://github.com/whompay/inFAMOUSreborn).

**This is not an Android port of the full game.** It neither executes lifted game functions nor renders inFAMOUS; no original game code or data is included. Do not mistake a successful APK build for game compatibility.

## What is tested
- ARM64 Android native code compilation with the NDK
- One genuine upstream ps3recomp runtime module: `ps3recomp/runtime/milestone.c` and its header
- Reserve (without physically allocating) a 4 GiB guest virtual-address range and commit/read/write one page
- Vulkan instance and GPU enumeration
- Read-only examination of a user-selected decrypted `EBOOT.elf` header, no upload or game execution

## What is still missing
- The regenerated game-specific PPU and SPU code from the player's PS3 disc (not included in upstream)
- Android boot scaffold, HLE integration, complete RSX -> Vulkan renderer, audio, input and storage mapping
- Performance validation / game boot / gameplay

## Build
1. Clone the official `whompay/inFAMOUSreborn` repository into `android-port-lab/upstream`.
2. Install JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1 and Gradle 8.9.
3. From `android-port-lab`, run `gradle assembleDebug`.
4. APK: `app/build/outputs/apk/debug/app-debug.apk`.

The repository GitHub Actions workflow builds the APK, and stores it as an Actions artifact.

The code is intentionally isolated in a branch of a test repository. It does not alter the upstream game repository. The upstream ps3recomp runtime retains its own MIT license and copyright.

Next meaningful milestone: integrate and compile an Android-compatible subsection of the actual PPU/HLE runtime, then boot a freely distributable smoke-test PS3 ELF. Only after that consider proprietary game-data import.
