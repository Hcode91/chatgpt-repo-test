# European BCES00220: real Android game boot workstream

**Status: private game boot build target implemented; NOT a successfully booted game.**

The verified user's EBOOT.elf has SHA-256:
`095d851432f3b55179cd725b46838ffedb3e5eb9f73889e52b6f11f3d3f494fd`.
The upstream `whompay/inFAMOUSreborn` installer manifest targets this exact ELF.

## Why this is different from previous Android smoke APKs

The public APK embeds a synthetic game and tests a reusable PS3 runtime.
It cannot execute a real title merely because the user selects EBOOT.elf.
The game needs its **title-specific, ahead-of-time recompiled PPU function table**.

This branch adds:
- `tools/prepare_eu_game_lift.py`: verify the exact ELF, compare the upstream
  installer manifest, invoke **upstream ppu_loader.py and ppu_lifter.py** against
  the user's ELF, and put the generated PPU sources in a private directory.
- `runtime-integration/CMakeLists.txt`: optional `INFAMOUS_PRIVATE_RECOMP`
  target for the real game's generated native code, linked with the actual
  upstream Android runtime/HLE and PPU boot scaffold.
- `tools/build_private_eu_apk.sh`: run the lift and local cross-build, then
  package `libps3eu.so` into an APK without committing/uploading game code.
- Android UI button for an actual boot attempt, **only enabled if the private
  game-code executable is present**. Its picker verifies the exact ELF hash
  before launching the game runtime and collecting logs.

**No game-derived code can be committed to this public repository.**
The private build's output contains copyrighted, recompiled game instructions.
Public GitHub Actions may compile the target with *synthetic generated code*
to check CMake and linker integration, but cannot produce a real game APK.

## Private Android build instructions (Linux / Shadow PC with Linux SDK)

Requires approximately 10 GB of free space, 16 GB RAM recommended, Android
SDK Platform 35, NDK 27.2.12479018, Java 17, Gradle 8.9, CMake, Ninja,
Python 3.11+ and git. The upstream inFAMOUSreborn installer is primarily
for Windows; this branch uses its platform-independent PPU lifter.

```bash
cd android-port-lab
git clone https://github.com/whompay/inFAMOUSreborn.git upstream
mkdir -p third_party
git clone --depth 1 --branch release-2.30.12 \
    https://github.com/libsdl-org/SDL.git third_party/SDL2-src
git clone https://github.com/kaniini/libucontext.git third_party/libucontext
git -C third_party/libucontext checkout 49e671dd52ff6791295d8161ad3b6da7dc5f6f9d

export EU_ELF="/PRIVATE/PS3_GAME/USRDIR/EBOOT.elf"
export EU_PRIVATE_OUT="/PRIVATE/infamous-eu-android-build"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
bash tools/build_private_eu_apk.sh
```

The generated APK, if it compiles, is saved to
`$EU_PRIVATE_OUT/infamous-eu-private-boot.apk`.
This script does **not** upload the game ELF or code to GitHub.

On-device, install that private APK. The **BOOT MY EUROPEAN inFAMOUS**
button selects and verifies the same EBOOT.elf before running the native
game-specific PPU executable in a bounded child process. It may report
missing game assets, HLE dependencies, SPU jobs, graphics or a crash.
The first private boot may need further Android-specific compiler and
linker fixes before the APK can be produced.

## Not yet implemented or validated

- Running the full game-specific PPU lift on this Android toolchain.
- SPU image/job/plugin lifting and runtime registration, as described by
  upstream `installer/infamous.toml`.
- The title-specific patches in `installer/patches/`, including variants
  that may be required for game logic and asset loading.
- A working inFAMOUS RSX renderer for Android Vulkan.
- Android SAF VFS mapping for the rest of the game's `PS3_GAME/USRDIR`.
- An actual on-device boot, intro screen or gameplay.

Passing public CI validates only synthetic integration, not a real game.
