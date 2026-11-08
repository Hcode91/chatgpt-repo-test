#!/usr/bin/env python3
"""Apply minimal and explicit Android compatibility changes to a disposable
clone of the upstream ps3recomp checkout. Fail if a source anchor drifts.
No copyrighted game data or proprietary console SDKs used.
"""
import pathlib, sys

root = pathlib.Path(sys.argv[1]).resolve()
changes=[]

def change(name, old, new, count=1):
    path=root/name
    s=path.read_text(encoding="utf-8")
    got=s.count(old)
    if got!=count:
        raise RuntimeError(f"{name}: expected {count} exact anchors, got {got}: {old[:75]!r}")
    path.write_text(s.replace(old,new),encoding="utf-8")
    changes.append(name)

change("include/ps3emu/hz_stats.h",
'''#if defined(_MSC_VER) || defined(_WIN32)
#include <intrin.h>
#else
#include <x86intrin.h>
#endif''',
'''#if defined(__aarch64__)
#include <time.h>
static inline unsigned long long ps3_arm64_host_ticks(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;
}
#define __rdtsc() ps3_arm64_host_ticks()
#elif defined(_MSC_VER) || defined(_WIN32)
#include <intrin.h>
#else
#include <x86intrin.h>
#endif''')
# ppu_loader's v156 optimisation expects this constant to be emitted by a
# game-specific generated header; the tiny clean-room ELF lacks that feature.
# Use the original default injection base for this test; later real game lifts
# must provide their own generated value.
change("runtime/ppu/ppu_loader.cpp",
'''#include "ppu_recomp.h"''',
'''#include "ppu_recomp.h"
#ifndef PPU_INJECT_BASE_C
#define PPU_INJECT_BASE_C 0x20000000u
#endif''')
change("runtime/ppu/ppu_hle.cpp",
'''#include "ppu_recomp.h"''',
'''#include "ppu_recomp.h"
#include "../platform/win32_compat.h"
#if defined(__aarch64__)
#include <time.h>
static inline unsigned long long ps3_hle_arm64_ticks(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;
}
#define __rdtsc() ps3_hle_arm64_ticks()
#endif''')
change("runtime/platform/win32_compat.c",
'''static void apply_affinity(pthread_t pt, DWORD_PTR mask)
{
#if defined(__linux__)''',
'''static void apply_affinity(pthread_t pt, DWORD_PTR mask)
{
#if defined(__linux__) && !defined(__ANDROID__)''')
change("runtime/spu/spu_channels.c",
'''#include <xmmintrin.h>''',
'''#if defined(__aarch64__)
#include <fenv.h>
#include <time.h>
static inline unsigned long long ps3_spu_arm64_ticks(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;
}
#define __rdtsc() ps3_spu_arm64_ticks()
#else
#include <xmmintrin.h>
#endif''')
change("runtime/spu/spu_channels.c",
'''    const unsigned int saved_csr = _mm_getcsr();
    if (s_fmode) _mm_setcsr((saved_csr & ~0x6000u) | 0x6000u | 0x8000u | 0x0040u);''',
'''#if defined(__aarch64__)
    /* RZ is supported by fenv on Android. FTZ/DAZ need a future FPCR
     * implementation to achieve exact PS3 SPU float semantics. */
    const int saved_round = fegetround();
    if (s_fmode) fesetround(FE_TOWARDZERO);
#else
    const unsigned int saved_csr = _mm_getcsr();
    if (s_fmode) _mm_setcsr((saved_csr & ~0x6000u) | 0x6000u | 0x8000u | 0x0040u);
#endif''')
change("runtime/spu/spu_channels.c",
'''    if (s_fmode) _mm_setcsr(saved_csr);''',
'''#if defined(__aarch64__)
    if (s_fmode) fesetround(saved_round);
#else
    if (s_fmode) _mm_setcsr(saved_csr);
#endif''')
print("Android patches applied:", len(changes), "edit anchors in", len(set(changes)), "real upstream files")
