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
# ARM64 SPU lane profiler uses a monotonic nanosecond time base instead
# of x86 RDTSCP; it is only a diagnostic, not a virtual PS3 clock.
change("runtime/spu/spu_laneprof.h",
'''#if defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <x86intrin.h>
#endif''',
'''#if defined(__aarch64__)
#  include <time.h>
static inline unsigned long long spu_lp_arm64_ticks(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;
}
#  define __rdtsc() spu_lp_arm64_ticks()
#elif defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <x86intrin.h>
#endif''')
change("runtime/spu/spu_helpers.h",
'''#include <xmmintrin.h>
#define SPU__DP_BEGIN { unsigned _spu_dpc = _mm_getcsr(); _mm_setcsr(_spu_dpc & ~0xE040u);
#define SPU__DP_END   _mm_setcsr(_spu_dpc); }''',
'''#if defined(__aarch64__)
#include <fenv.h>
#define SPU__DP_BEGIN { int _spu_dpc = fegetround(); fesetround(FE_TONEAREST);
#define SPU__DP_END   fesetround(_spu_dpc); }
#else
#include <xmmintrin.h>
#define SPU__DP_BEGIN { unsigned _spu_dpc = _mm_getcsr(); _mm_setcsr(_spu_dpc & ~0xE040u);
#define SPU__DP_END   _mm_setcsr(_spu_dpc); }
#endif''')
# The profiler's macro now supplies ARM64 ticks to spu_channels too.
change("runtime/spu/spu_channels.c",
'''#define __rdtsc() ps3_spu_arm64_ticks()
#else
#include <xmmintrin.h>
#endif''',
'''#else
#include <xmmintrin.h>
#endif''')
# The PAD_FILE controls are used in the shared GetData path, but upstream
# declares them inside the Windows-only XInput branch. Lift just these data
# declarations to portable scope; keep XInput-specific code under its guard.
change("libs/input/cellPad.c",
'''#if PAD_BACKEND_XINPUT

/* Deadzone for analog sticks''',
'''static unsigned char g_pf_stick[4] = {128, 128, 128, 128};
volatile unsigned g_pad_file_held = 0;
static int g_pf_stick_on = 0;

#if PAD_BACKEND_XINPUT

/* Deadzone for analog sticks''')
change("libs/input/cellPad.c",
'''static unsigned char g_pf_stick[4] = {128, 128, 128, 128};
volatile unsigned g_pad_file_held = 0;   /* v219: PAD_FILE mask being held (rsx_ui_overlay.c dialogs/menu) */
static int g_pf_stick_on = 0;

#ifdef _WIN32''',
'''/* PAD_FILE portable state is declared above the XInput-only branch. */

#ifdef _WIN32''')
# Full upstream library build: SDL2 is provided as an in-tree Android target
# by runtime-integration/CMakeLists.txt rather than a preinstalled host package.
change("CMakeLists.txt",
'''    find_package(SDL2 QUIET)
    if(SDL2_FOUND)''',
'''    if(TARGET SDL2::SDL2)
        set(SDL2_FOUND TRUE)
    else()
        find_package(SDL2 QUIET)
    endif()
    if(SDL2_FOUND)''')
# x86-specific compiler flag is invalid on Android ARM64. Retain it for x86.
change("CMakeLists.txt",
'''        -msse4.1
''',
'''        $<$<NOT:$<BOOL:${ANDROID}>>:-msse4.1>
''')
# Full runtime build blockers found by the Android CMake integration.
change("libs/system/cellSysutil.c",
'''__declspec(selectany) void (*g_sysutil_lang_hook)(int) = NULL;''',
'''#if defined(__ANDROID__)
__attribute__((weak)) void (*g_sysutil_lang_hook)(int) = NULL;
#else
__declspec(selectany) void (*g_sysutil_lang_hook)(int) = NULL;
#endif''')

change("runtime/spu/spurs_policy.c",
'''struct timespec ts; timespec_get(&ts, TIME_UTC);   /* C11; precise on UCRT and glibc */''',
'''struct timespec ts;
#if defined(__ANDROID__)
    clock_gettime(CLOCK_MONOTONIC, &ts);
#else
    timespec_get(&ts, TIME_UTC);
#endif''')

change("runtime/spu/spu_interp.c",
'''#include <xmmintrin.h>
#include "spu_interp.h"''',
'''#if defined(__aarch64__)
#include <fenv.h>
#include <time.h>
#define SPU_INTERP_NO_VEC 1
static inline unsigned long long spu_interp_arm64_ticks(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;
}
#define __rdtsc() spu_interp_arm64_ticks()
#else
#include <xmmintrin.h>
#endif
#include "spu_interp.h"''')
change("runtime/spu/spu_interp.c",
'''#define SPU_DP(expr) do { unsigned _c = _mm_getcsr(); _mm_setcsr(_c & ~0xE040u); DST = (expr); _mm_setcsr(_c); } while (0)''',
'''#if defined(__aarch64__)
#define SPU_DP(expr) do { int _c = fegetround(); fesetround(FE_TONEAREST); DST = (expr); fesetround(_c); } while (0)
#else
#define SPU_DP(expr) do { unsigned _c = _mm_getcsr(); _mm_setcsr(_c & ~0xE040u); DST = (expr); _mm_setcsr(_c); } while (0)
#endif''')
change("runtime/spu/spu_interp.c",
'''#if defined(_MSC_VER) && !defined(__clang__)
    return __rdtsc();
#else
    return __builtin_ia32_rdtsc();
#endif''',
'''#if defined(__aarch64__)
    return spu_interp_arm64_ticks();
#elif defined(_MSC_VER) && !defined(__clang__)
    return __rdtsc();
#else
    return __builtin_ia32_rdtsc();
#endif''')
change("libs/spurs/cellSpurs.c",
'''#if defined(_MSC_VER)
#include <intrin.h>
#else
#include <x86intrin.h>
#endif''',
'''#if defined(__aarch64__)
#include <time.h>
static inline unsigned long long spurs_arm64_ticks(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;
}
#define __rdtsc() spurs_arm64_ticks()
#define _mm_pause() __asm__ __volatile__("yield")
#elif defined(_MSC_VER)
#include <intrin.h>
#else
#include <x86intrin.h>
#endif''')
# Android Bionic declares ucontext_t for signal handlers but omits the
# POSIX user-space coroutine functions. Use the separately linked,
# architecture-specific libucontext ABI just for cellFiber.
change("libs/spurs/cellFiber.c",
'''#include <ucontext.h>

#if defined(__APPLE__)''',
'''#if defined(__ANDROID__)
#include <libucontext/libucontext.h>
#define ucontext_t libucontext_ucontext_t
#define getcontext libucontext_getcontext
#define makecontext libucontext_makecontext
#define setcontext libucontext_setcontext
#define swapcontext libucontext_swapcontext
#else
#include <ucontext.h>
#endif

#if defined(__APPLE__)''')
# The Windows-only W16PROF sampling state is referenced by the generic
# taskwait entry, even when the sampler is disabled on Android.
change("libs/spurs/cellSpurs.c",
'''#else
static inline void w16p_begin(int wid) { (void)wid; }
static inline void w16p_end(void) {}
#endif''',
'''#else
static volatile int s_w16p_mode = 0;
static inline void w16p_begin(int wid) { (void)wid; }
static inline void w16p_end(void) {}
#endif''')
# Real synthetic PS3 guest geometry. The PPU program issues NV4097 draw
# commands into the same FIFO used by the successful v0.5 boot test.
# The headless RSX software backend rasterizes an actual triangle. Bitmap
# capture is opt-in via the upstream NULLSW_DUMP environment setting.
change("runtime/ppu/tests/smoke/smoke_recomp.c",
'''#define SMOKE_IO_SIZE       0x00200000u''',
'''#define SMOKE_IO_SIZE       0x00200000u
#define SMOKE_VTX_OFFSET    0x00010000u''')
change("runtime/ppu/tests/smoke/smoke_recomp.c",
'''/* ---------------------------------------------------------------------------
 * Firmware import stubs.''',
'''/* Three geometries/colors are emitted by lifted synthetic PS3 guest code.
 * Each vertex holds float4 position followed by float4 color at stride 32.
 * Memory and FIFO writes go through the real guest vm_write32 path.
 */
static void smoke_draw_rsx_triangle(unsigned frame)
{
    static const float xy[3][4] = {
        { -0.70f, -0.65f, 0.0f, 1.0f },
        {  0.70f, -0.65f, 0.0f, 1.0f },
        {  0.00f,  0.75f, 0.0f, 1.0f },
    };
    static const float colors[3][4] = {
        { 1.0f, 0.86f, 0.16f, 1.0f },
        { 0.16f, 0.92f, 0.95f, 1.0f },
        { 0.98f, 0.25f, 0.65f, 1.0f },
    };
    for (unsigned i = 0; i < 3; ++i) {
        for (unsigned k = 0; k < 8; ++k) {
            const float value = k < 4 ? xy[i][k] : colors[frame % 3][k - 4];
            uint32_t bits;
            memcpy(&bits, &value, sizeof bits);
            vm_write32(SMOKE_IO_ADDR + SMOKE_VTX_OFFSET + i * 32u + k * 4u, bits);
        }
    }
    const uint32_t format = 2u | (4u << 4) | (32u << 8);
    smoke_emit(NV4097_SET_VERTEX_DATA_ARRAY_OFFSET + 0u, 0x80000000u | SMOKE_VTX_OFFSET);
    smoke_emit(NV4097_SET_VERTEX_DATA_ARRAY_FORMAT + 0u, format);
    smoke_emit(NV4097_SET_VERTEX_DATA_ARRAY_OFFSET + 12u, 0x80000000u | (SMOKE_VTX_OFFSET + 16u));
    smoke_emit(NV4097_SET_VERTEX_DATA_ARRAY_FORMAT + 12u, format);
    smoke_emit(NV4097_SET_BEGIN_END, RSX_PRIMITIVE_TRIANGLES);
    smoke_emit(NV4097_DRAW_ARRAYS, (2u << 24));
    smoke_emit(NV4097_SET_BEGIN_END, 0u);
}

/* ---------------------------------------------------------------------------
 * Firmware import stubs.''')
change("runtime/ppu/tests/smoke/smoke_recomp.c",
'''        smoke_emit(NV4097_CLEAR_SURFACE,         0xF0u);
        vm_write32(ctrl + 0, g_fifo_len);''',
'''        smoke_emit(NV4097_CLEAR_SURFACE,         0xF0u);
        smoke_draw_rsx_triangle((unsigned)frame);
        vm_write32(ctrl + 0, g_fifo_len);''')
print("Android patches applied:", len(changes), "edit anchors in", len(set(changes)), "real upstream files")
