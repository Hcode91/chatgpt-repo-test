# Full upstream boot-smoke link contract

The complete `ps3recomp_runtime` static archive compiles for ARM64 after
platform patches, including the real libucontext-based Android `cellFiber`.

The upstream synthetic boot harness omits certain host-owned symbols that a
real game-specific main would provide. `smoke_host_services.cpp` supplies:
the runner's `ps3_stdout_immediate`, diagnostic counters `g_hz`, a genuine
thread-ID check for `ps3_hz_is_main`, and explicit no-plugin/no-RSX tracking
hooks. These are only built into `ps3recomp_boot_smoke`, not the library
or Android diagnostic APK.

This does not implement missing firmware, guest game code, graphics, or
SPU plugins; `spu_plug_lookup` intentionally returns no plugin for a title
that has none. All actual HLE and LV2 implementations still come from
upstream `ps3recomp_runtime`.

A successful cross-link proves native code is assembled for Android, not
that the executable can run on host Linux (it cannot), or that inFAMOUS boots.
On-device execution and Vulkan renderer integration are further gates.
