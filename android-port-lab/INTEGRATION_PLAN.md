# Integrated Android ARM64 compatibility sprint

Our existing v0.4 clean-room PPU execution test is validated on hardware.
This sprint assesses and repairs **real upstream** runtime code, without new
APK/manual phone testing for each change.

GitHub Actions compiles 16 upstream source files spanning PPU, HLE, LV2,
SPU, RSX and input/audio with Android NDK clang. The machine-readable report
is uploaded as `infamous-android-runtime-audit`.

## First audit: 8/16 compile on Android
The fails include x86-only timestamp intrinsics, SSE MXCSR use, Linux
pthread_setaffinity_np unavailable on Android, and an optional SDL2 dependency.

## Source compatibility patches
`tools/patch_android_upstream.py` applies fail-fast, narrowly scoped changes
to a disposable cloned upstream checkout. It enables ARM64 monotonic timing,
uses Android's supported rounding-mode API (the floating-point FTZ/DAZ
behaviour still needs work), removes unsupported affinity, and retains the
normal upstream x86 paths. No fake native HLE implementation is substituted.

## Real remaining steps
Compile-checks are **not** a linkable PS3 runtime. Once all sources compile,
they must be linked into an Android game executable with Android Vulkan RSX
rendering, functioning syscalls/threads, a VFS, a reliable control/audio
backend and the full upstream synthetic smoke ELF. Proprietary game code
should only be generated locally from a user-owned supported disc.

We will test APKs on phone after integration gates, not every single patch.
