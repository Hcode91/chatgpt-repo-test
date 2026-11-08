# Real US PSN inFAMOUS PPU startup experiment (v0.8)

This branch adds an **honest, bounded startup trace** to the existing Android port lab, without including or uploading proprietary EBOOT/PS3 data.

## Verified locally against user-supplied NPUA80480 EBOOT.elf
- ELF64 big-endian PPC64; SHA-256 `5e22402aa3ffe695106abc1a9956fa3ea78e4977c32d80b37b6db4c3ba7110c7`.
- Entry descriptor `0x771C30` resolves code PC `0x10230` and initial TOC `0x78C0C0`.
- First 38 PPU instructions execute through a bounded interpreter, reaching a call stub at `0x55E844`.
- That stub branches to `0x39800000`, outside the user's ELF executable segments. The import resolver / LV2 dynamic linking isn't present in this minimal trace.
- This **does not boot the game**, initialize RSX or execute the full program. It establishes that on-device decoded real guest instructions can be stepped far enough to reveal a concrete startup dependency.

## Test on Android
1. Build/install the `inFAMOUS Android ARM64 probe APK` from the workflow on `feature/infamous-psn-real-ppu-boot`.
2. Tap `TRACE REAL inFAMOUS EBOOT STARTUP (EXPERIMENTAL)`.
3. Select your own previously extracted `EBOOT.elf`.
4. The app reads it locally on a worker thread, bounds-checks ELF segments, initializes an isolated guest address space and traces a strict budget of PowerPC instructions. Inspect the final `Stop:` line.

Neither the ELF nor RAP is uploaded to GitHub. **Do not** add game content to commits or Actions artifacts.

## Engineering next steps
1. Correct guest process setup: import libraries, reconstruct the PS3 process parameter block / startup argument layout and dynamic linker state.
2. Transition from the narrow interpreter to the upstream `ps3recomp` PPU lifting and runtime path for this exact PSN executable. The upstream `inFAMOUSreborn` project currently targets **BCES00220**; the US PSN build requires separate analysis and a new set of generated symbols.
3. Resolve LV2 syscalls, PPU module imports, SPU job modules, memory, and game file accesses before attempting in-game RSX rendering.
4. Require user-supplied game assets to remain on device or within a private local build environment, never public CI.

A passing CI means our app and synthetic probe compile. It does **not** imply real inFAMOUS boot success.
