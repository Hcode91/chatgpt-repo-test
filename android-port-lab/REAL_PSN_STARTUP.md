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

## PS3 firmware 4.93 support experiment

The user's PS3UPDAT.PUP was inspected locally (not committed):
- SCEUF version string 4.93, 206,197,916 bytes and 9 outer segments.
- The inner Update_Files TAR contains 50 entries, including 23 numbered `dev_flash_000` through `dev_flash_022` packages, an additional `dev_flash3` package, and `CORE_OS_PACKAGE.pkg`.
- Sony's packages remain encrypted. This prototype **does not** decrypt or execute those firmware modules.

The Android app now includes `INSPECT MY PS3UPDAT.PUP FIRMWARE (LOCAL)`.
It uses an Android document picker and a bounded streaming SCEUF+TAR header scanner that does not extract anything or transmit user files.

The real ELF has 13 PRX import libraries and 198 named import entries. The first observed stop is the
`sysPrxForUser` NID `0x744680A2`, `sys_initialize_tls`, stub VA `0x55E844`. Its imported
trampoline is not a valid executable target until the PS3 runtime resolves it.
A separate PPU startup probe HLE handler has been added for this import. This remains a targeted
startup experiment and is not a substitute for complete PS3 firmware HLE, module linking, thread creation,
memory system, SPU or RSX.

**Next device check**: select the previously extracted EBOOT.elf in TRACE REAL inFAMOUS EBOOT STARTUP, record
the PPU trace and stopping reason. Then optionally inspect PS3UPDAT.PUP for version/packaging details.
Neither file should be committed or uploaded into a public CI workflow.
