# Real upstream runtime integration attempt

The Android diagnostic APK v0.4 runs a tiny game-code function but not the
upstream `ps3recomp` runtime. This CI gate moves beyond single-object audits:
it configures SDL2 for Android, builds the complete game-agnostic
`ps3recomp_runtime` static archive and attempts to link the upstream
`ps3recomp_boot_smoke` executable with the genuine PPU boot scaffold.

It runs only on the GitHub Android NDK runner. This is not a playable APK.
Compiler and linker blockers are recorded in `infamous-android-full-runtime-integration`
regardless of success or failure. No licensed game data are present.

The next truly user-testable milestone will require the full runtime linked
into the Android app, guest threading/HLE working and native Vulkan presentation,
not another clean-room arithmetic test.
