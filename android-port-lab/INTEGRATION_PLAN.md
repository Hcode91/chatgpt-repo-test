# Android integration sprint (all workstreams in one pass)

Target: convert the currently working ARM64 recompilation/ELF proof into an
actual Android game runtime, without implying that a game boots before it does.

## Workstreams investigated in the same CI build
1. PPU image loading, dispatch, imports, filesystem.
2. LV2 syscalls and guest threading.
3. SPU and synchronization.
4. RSX/GCM command stream and renderer dependencies.
5. Android input/audio capabilities and eventual game-file handling.

The workflow compiles **real upstream source files** with the Android NDK and
writes a machine-readable audit and human-readable blocker report. This is an
integration audit, not a series of APK smoke tests. The existing v0.4 APK
continues to compile but is not claimed to execute the upstream runtime.

## Why not claim a playable APK yet?

inFAMOUSreborn's source does not include generated game-specific recompilation
files or game data; those must be produced from a user's own compatible PS3
copy. Windows D3D12 and Apple Metal backends cannot be run as-is on Android.
A Vulkan RSX implementation, HLE/LV2 integration, and runtime port are major
workstreams. For actual game boot these must all work together, not just pass
hardware probes.

## Completion gate
No new user APK needs testing for each compile fix. Request a device test
only when the actual upstream loader + thread/runtime + initial graphics
presentation form a runnable milestone.
