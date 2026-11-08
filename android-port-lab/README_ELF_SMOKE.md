# Milestone 0.4: ELF64 PowerPC loading and registered native execution

This builds on the successful register-only PPU instruction test in v0.3.

The APK includes an original `smoke.elf` generated at build time. It is a
big-endian ELF64/PowerPC image with two loadable segments and a PS3-style OPD
function descriptor. The native loader validates all offsets and segments,
copies PT_LOAD data and zeros BSS in a bounded guest-memory allocation, reads
the OPD to locate the entry function, checks that the ELF code bytes match the
separately compiled output of upstream ps3recomp's PPU lifter, and executes the
registered ARM64 function.

Expected: input r3=5, output r3=71, zero unexpected HLE/memory calls.

The GitHub workflow tests a successful ELF load/dispatch on Linux and verifies
that a mutated guest instruction is rejected. Android builds use the same
source and generated ELF. No commercial game code, firmware, or proprietary
data is bundled.

**Limits**: This is a deliberately small custom ELF parser, not upstream
`ppu_load_elf`, not general PS3 emulation, and not an inFAMOUS game boot. The
ahead-of-time lifted function matches exactly one original test program.

Next: integrate the upstream PPU loader, then the real boot scaffold and HLE
dependencies, using the upstream synthetic smoke test before working with a
user-owned game executable.
