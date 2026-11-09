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
# spu_xfk.h (v200 float post-pass, used by every register-cached WWS job
# module) is x86 SSE only. On arm64 map each *_k op to the portable spu_helpers.h
# reference op, which clamps every operand: that is upstream's own exact mode
# (-DSPU_XFK_EXACT), so results are identical to the documented semantics.
change("runtime/spu/spu_xfk.h",
"""#if defined(SPU_XFK_EXACT)
#  define SPU__XFKC(m, i, v) spu__xfq(v)""",
"""#if !defined(__x86_64__) && !defined(_M_X64) && !defined(__i386__)
#define spu_fa_k(a, b, m)      spu_fa((a), (b))
#define spu_fs_k(a, b, m)      spu_fs((a), (b))
#define spu_fm_k(a, b, m)      spu_fm((a), (b))
#define spu_fma_k(a, b, c, m)  spu_fma((a), (b), (c))
#define spu_fms_k(a, b, c, m)  spu_fms((a), (b), (c))
#define spu_fnms_k(a, b, c, m) spu_fnms((a), (b), (c))
#else /* x86: upstream definitions unchanged */
#if defined(SPU_XFK_EXACT)
#  define SPU__XFKC(m, i, v) spu__xfq(v)""")
change("runtime/spu/spu_xfk.h",
"""#define spu_fnms_k(a, b, c, m) spu__fst(_mm_fnmadd_ps(SPU__XFKC(m, 0, spu__fld(a)), SPU__XFKC(m, 1, spu__fld(b)), SPU__XFKC(m, 2, spu__fld(c))))
#endif""",
"""#define spu_fnms_k(a, b, c, m) spu__fst(_mm_fnmadd_ps(SPU__XFKC(m, 0, spu__fld(a)), SPU__XFKC(m, 1, spu__fld(b)), SPU__XFKC(m, 2, spu__fld(c))))
#endif /* x86 */
#endif""")
# Android controller input (runtime-integration/eu_android_bridge.c). Weak
# reference: targets without the bridge (smoke tests) link and behave as before.
change("libs/input/cellPad.c",
'''#include "../../runtime/ppu/ppu_memory.h"   /* GUEST_PTR, vm_write*: guest EA -> host */''',
'''#include "../../runtime/ppu/ppu_memory.h"   /* GUEST_PTR, vm_write*: guest EA -> host */
#if defined(__ANDROID__)
extern int eu_bridge_pad(unsigned* buttons, unsigned char axes[6]) __attribute__((weak));
#endif''')
change("libs/input/cellPad.c",
'''    for (int _i = 0; _i < PAD_MAX_HOST_PORTS; _i++)
        if (s_host_state[_i].buttons) PAD_LATCH_OR(_i, s_host_state[_i].buttons);
}''',
'''#if defined(__ANDROID__)
    /* Android app controller (touch controls or a real gamepad) owns port 0. */
    if (eu_bridge_pad) {
        unsigned b = 0; unsigned char ax[6];
        if (eu_bridge_pad(&b, ax)) {
            PadHostState* h = &s_host_state[0];
            h->connected = 1; h->buttons = (u16)b;
            h->analog_lx = ax[0]; h->analog_ly = ax[1]; h->analog_rx = ax[2]; h->analog_ry = ax[3];
            h->trigger_l2 = ax[4]; h->trigger_r2 = ax[5];
            h->press_right = (b & CELL_PAD_CTRL_RIGHT) ? 255 : 0;  h->press_left  = (b & CELL_PAD_CTRL_LEFT) ? 255 : 0;
            h->press_up    = (b & CELL_PAD_CTRL_UP) ? 255 : 0;     h->press_down  = (b & CELL_PAD_CTRL_DOWN) ? 255 : 0;
            h->press_triangle = (b & CELL_PAD_CTRL_TRIANGLE) ? 255 : 0; h->press_circle = (b & CELL_PAD_CTRL_CIRCLE) ? 255 : 0;
            h->press_cross    = (b & CELL_PAD_CTRL_CROSS) ? 255 : 0;    h->press_square = (b & CELL_PAD_CTRL_SQUARE) ? 255 : 0;
            h->press_l1 = (b & CELL_PAD_CTRL_L1) ? 255 : 0;        h->press_r1 = (b & CELL_PAD_CTRL_R1) ? 255 : 0;
        }
    }
#endif
    for (int _i = 0; _i < PAD_MAX_HOST_PORTS; _i++)
        if (s_host_state[_i].buttons) PAD_LATCH_OR(_i, s_host_state[_i].buttons);
}''')
# Android screen: publish each presented software-RSX frame.
change("libs/video/rsx_null_backend.c",
'''#include "rsx_vertex_fetch.h"
#include "rsx_texture_layout.h"
#include <stdlib.h>''',
'''#include "rsx_vertex_fetch.h"
#include "rsx_texture_layout.h"
#include <stdlib.h>
#if defined(__ANDROID__)
extern void eu_bridge_frame(const unsigned int* fb, unsigned w, unsigned h) __attribute__((weak));
#endif''')
change("libs/video/rsx_null_backend.c",
'''        s_soft.fb[(s_soft.height / 2) * s_soft.width + s_soft.width / 2];
    s_soft.presented++;''',
'''        s_soft.fb[(s_soft.height / 2) * s_soft.width + s_soft.width / 2];
    s_soft.presented++;
#if defined(__ANDROID__)
    if (eu_bridge_frame) eu_bridge_frame(s_soft.fb, s_soft.width, s_soft.height);
#endif''')
# Android GPU renderer (runtime-integration/eu_vk_backend.cpp): tried first;
# any failure falls back to the CPU software preview below. EU_SOFT_DRAW=0
# keeps the preview's clears but skips its triangle filling (speed test).
change("libs/video/rsx_null_backend.c",
'''#if defined(__ANDROID__)
extern void eu_bridge_frame(const unsigned int* fb, unsigned w, unsigned h) __attribute__((weak));
#endif''',
'''#if defined(__ANDROID__)
extern void eu_bridge_frame(const unsigned int* fb, unsigned w, unsigned h) __attribute__((weak));
extern int  eu_vk_rsx_init(unsigned w, unsigned h) __attribute__((weak));
extern int  eu_vk_rsx_active(void) __attribute__((weak));
extern void rsx_draw_engine_present(void);
#endif''')
change("libs/video/rsx_null_backend.c",
'''static void draw_prim(u32 prim, u32 first, u32 count)
{
    const rsx_state* st = s_soft.state;''',
'''static void draw_prim(u32 prim, u32 first, u32 count)
{
#if defined(__ANDROID__)
    { static int s_draw = -1;
      if (s_draw < 0) { const char* e = getenv("EU_SOFT_DRAW"); s_draw = !(e && *e == '0');
                        if (!s_draw) fprintf(stderr, "[RSX null] EU_SOFT_DRAW=0: triangle filling OFF (speed test)\\n"); }
      if (!s_draw) return; }
#endif
    const rsx_state* st = s_soft.state;''')
change("libs/video/rsx_null_backend.c",
'''int rsx_null_backend_init(u32 width, u32 height, const char* title)
{
    memset(&s_soft, 0, sizeof(s_soft));''',
'''int rsx_null_backend_init(u32 width, u32 height, const char* title)
{
#if defined(__ANDROID__)
    if (eu_vk_rsx_init && eu_vk_rsx_init(width ? width : 1280, height ? height : 720) == 0) return 0;
#endif
    memset(&s_soft, 0, sizeof(s_soft));''')
change("libs/video/rsx_null_backend.c",
'''void rsx_null_backend_present(void) { nullsw_present(NULL, 0); }''',
'''void rsx_null_backend_present(void)
{
#if defined(__ANDROID__)
    if (eu_vk_rsx_active && eu_vk_rsx_active()) { rsx_draw_engine_present(); return; }
#endif
    nullsw_present(NULL, 0);
}''')
# Draw-drop counters in the register-file engine (Android diagnostics).
change("libs/video/rsx_draw_engine.c",
'static void sink_end(void* user, const rsx_dispatch* r)\n{',
'#if defined(__ANDROID__)\n/* Android diagnostics: why the register-file engine drops a draw. Read and\n * printed by eu_vk_backend.cpp. [0] calls [1] no packets [2] topology\n * [3] vertex fetch [4] rebuild [5] zero count [6] no colour target\n * [7] no pipeline [8] index reserve [9] FP constants [10] drawn\n * [11] target-set lookup found no surface. */\nunsigned g_eu_eng_drop[16];\n#define EU_DROP(i) (g_eu_eng_drop[(i)]++)\n#else\n#define EU_DROP(i) ((void)0)\n#endif\nstatic void sink_end(void* user, const rsx_dispatch* r)\n{\n    EU_DROP(0);')
change("libs/video/rsx_draw_engine.c",
'    if (!g.ready || !dc.n_packets) return;\n\n    const u32 prim = g.rsx.current_primitive;',
'    if (!g.ready) return;\n    if (!dc.n_packets) { EU_DROP(1); return; }\n\n    const u32 prim = g.rsx.current_primitive;')
change("libs/video/rsx_draw_engine.c",
'        if (rsx_primitive_needs_expansion(prim)) return;',
'        if (rsx_primitive_needs_expansion(prim)) { EU_DROP(2); return; }')
change("libs/video/rsx_draw_engine.c",
'        if (topology == RSX_TOPOLOGY_UNSUPPORTED) return;',
'        if (topology == RSX_TOPOLOGY_UNSUPPORTED) { EU_DROP(2); return; }')
change("libs/video/rsx_draw_engine.c",
'    if (!dc.n_verts || !dc.fetch_ok) return;',
'    if (!dc.n_verts || !dc.fetch_ok) { EU_DROP(3); return; }')
change("libs/video/rsx_draw_engine.c",
'        if (!eng_topology_rebuild(prim, dc.refs_remapped, &indexed)) return;',
'        if (!eng_topology_rebuild(prim, dc.refs_remapped, &indexed)) { EU_DROP(4); return; }')
change("libs/video/rsx_draw_engine.c",
'    if (!n_draw) return;\n\n    rsx_be_render_state rs;',
'    if (!n_draw) { EU_DROP(5); return; }\n\n    rsx_be_render_state rs;')
change("libs/video/rsx_draw_engine.c",
'    if (!n_targets) return;\n    const u32 target = targets[0];',
'    if (!n_targets) { EU_DROP(6); return; }\n    const u32 target = targets[0];')
change("libs/video/rsx_draw_engine.c",
'    if (!pipeline) return;\n\n    if (indexed) {',
'    if (!pipeline) { EU_DROP(7); return; }\n\n    if (indexed) {')
change("libs/video/rsx_draw_engine.c",
'        if (!dc_reserve_indices(n_draw)) return;',
'        if (!dc_reserve_indices(n_draw)) { EU_DROP(8); return; }')
change("libs/video/rsx_draw_engine.c",
'    if (g.fp_cb_cap < fp_bytes) {\n        u8* n = (u8*)realloc(g.fp_cb, fp_bytes);\n        if (!n) return;',
'    if (g.fp_cb_cap < fp_bytes) {\n        u8* n = (u8*)realloc(g.fp_cb, fp_bytes);\n        if (!n) { EU_DROP(9); return; }')
change("libs/video/rsx_draw_engine.c",
'    if (!pipeline_is_fixed) g.guest_draws++;',
'    EU_DROP(10);\n    if (!pipeline_is_fixed) g.guest_draws++;')
print("Android patches applied:", len(changes), "edit anchors in", len(set(changes)), "real upstream files")
