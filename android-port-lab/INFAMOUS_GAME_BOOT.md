# Actual inFAMOUS boot preparation

The upstream inFAMOUSreborn checkout is a *static recompiler-based port* and
does not include the game-specific PPU/SPU generated source or retail assets.
Our existing v0.7 Android APK runs a synthetic PS3 guest, not inFAMOUS.

## What's required
- Your own **European inFAMOUS BCES00220** dump.
- Decrypted `PS3_GAME/USRDIR/EBOOT.elf` matching upstream installer SHA256:
  `095d851432f3b55179cd725b46838ffedb3e5eb9f73889e52b6f11f3d3f494fd`
- The remaining `PS3_GAME/USRDIR` content and supporting game data.

Run locally (do not upload proprietary files to the public repo):
`python3 android-port-lab/tools/check_infamous_dump.py "/path/to/game"`
Or pass the decrypted EBOOT.elf path directly.

The manifest `installer/infamous.toml` documents the upstream multi-stage
PPU/SPU pipeline: `ppu_loader.py`, `ppu_lifter.py`, `extract_spu_images.py`,
WWS job modules, SPU lifting, postprocessing, patching, and game asset setup.
This generated output is **not** part of this public repository.

GitHub Actions also cross-compiles the upstream **real game host source**
(`infamous-recomp/main.cpp`, `stubs.cpp`, `trainer.cpp`) using the Android
NDK against the test ABI header, to expose ARM64/Android portability blockers
before game content is available. A successful compilation is not a game boot.

Do not treat `EBOOT.BIN` as a directly runnable ELF. The current Android Port
Lab cannot interpret arbitrary PS3 machine code dynamically. After the game's
specific code is successfully recompiled, linked against the Android runtime,
and packaged locally or through a secure/private build, we can attempt an
actual inFAMOUS startup and diagnose missing HLE/RSX/SPU behavior.
