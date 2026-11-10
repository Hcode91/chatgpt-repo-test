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
# Immediate-mode draws in the register-file engine. inFamous draws its Bink
# movie quad (and other 2D) with SET_VERTEX_DATA* between BEGIN_END pairs,
# which the rsx_state path collects into imm_verts but rsx_dispatch never sees
# as a packet -- the engine dropped every such draw as "no packets". The FIFO
# walker feeds the engine BEFORE rsx_process_method, so at the engine's END the
# rsx_state still holds this draw's immediate vertices; expose them read-only.
change("libs/video/cellGcmSys.c",
"void cellGcm_rsx_process_fifo(void)\n",
"""#if defined(__ANDROID__)
static rsx_state* s_eu_gcm_state;
u32 eu_gcm_imm_count(void) { return s_eu_gcm_state ? s_eu_gcm_state->imm_count : 0u; }
const float* eu_gcm_imm_attr(u32 v, u32 attr)
{
    if (!s_eu_gcm_state || v >= RSX_IMM_MAX_VERTS || attr >= RSX_MAX_VERTEX_ATTRIBS) return NULL;
    return s_eu_gcm_state->imm_verts[v][attr];
}
#endif
void cellGcm_rsx_process_fifo(void)
""")
change("libs/video/cellGcmSys.c",
"    static rsx_state s_state;\n    static int  s_inited = 0;\n",
"    static rsx_state s_state;\n    static int  s_inited = 0;\n#if defined(__ANDROID__)\n    s_eu_gcm_state = &s_state;\n#endif\n")
change("libs/video/rsx_draw_engine.c",
"static void sink_end(void* user, const rsx_dispatch* r)\n{\n    EU_DROP(0);",
"""#if defined(__ANDROID__)
extern u32 eu_gcm_imm_count(void) __attribute__((weak));
extern const float* eu_gcm_imm_attr(u32 v, u32 attr) __attribute__((weak));
/* Build the draw's vertices straight from the immediate-mode values, one
 * float4 per layout slot (the same shape rsx_vertex_fetch_one writes). */
static int eu_fill_immediate(const rsx_vertex_layout_plan* layout)
{
    const u32 n = eu_gcm_imm_count ? eu_gcm_imm_count() : 0u;
    if (!n || !layout->stride) return 0;
    dc.n_refs = dc.n_source_refs = n;
    dc.refs_remapped = 0;
    dc.n_cuts = 0;
    dc.layout = *layout;
    if (!dc_reserve_verts((u64)n * layout->stride)) return 0;
    for (u32 i = 0; i < n; i++) {
        u8* dst = dc.verts + (u64)i * layout->stride;
        for (u32 sl = 0; sl < layout->count; sl++) {
            const float* src = eu_gcm_imm_attr(i, layout->attrs[sl]);
            static const float zero4[4] = {0.0f, 0.0f, 0.0f, 1.0f};
            memcpy(dst + sl * 16u, src ? src : zero4, 16u);
        }
    }
    dc.n_verts = n;
    dc.fetch_ok = 1;
    return 1;
}
#endif
static void sink_end(void* user, const rsx_dispatch* r)
{
    EU_DROP(0);""")
change("libs/video/rsx_draw_engine.c",
"    if (!dc.n_packets) { EU_DROP(1); return; }",
"""    int eu_imm = 0;
    if (!dc.n_packets) {
#if defined(__ANDROID__)
        eu_imm = eu_gcm_imm_count && eu_gcm_imm_count() > 0;
#endif
        if (!eu_imm) { EU_DROP(1); return; }
        EU_DROP(12);   /* immediate-mode draw */
    }""")
change("libs/video/rsx_draw_engine.c",
"    dc_fetch(&layout, rebuild);\n    if (!dc.n_verts || !dc.fetch_ok) { EU_DROP(3); return; }",
"""#if defined(__ANDROID__)
    if (eu_imm) { if (!eu_fill_immediate(&layout)) { EU_DROP(3); return; } }
    else
#endif
    dc_fetch(&layout, rebuild);
    if (!dc.n_verts || !dc.fetch_ok) { EU_DROP(3); return; }""")
# Surface cache: PS3 titles reuse one block of VRAM for render targets of
# different sizes inside a frame (inFamous: 0x01AB8000 is 1280x720, 512x288 and
# 1280x180). Upstream keeps one target per address and recreates it -- empty --
# whenever the size moves, so the lighting and post passes that read a target
# back as a texture saw nothing (dark/grey frames). On Android keep one target
# per (address, size, format), stamp the most recently used, and let texture
# reads and presents pick the best match at that address.
change("libs/video/rsx_draw_engine.c",
"""    rsx_be_format fmt;
    u32 handle;
} eng_surface;""",
"""    rsx_be_format fmt;
    u32 handle;
    u32 stamp;             /* Android: last use, for picking among aliases */
} eng_surface;
#if defined(__ANDROID__)
static u32 eu_surface_tick;
#endif""")
change("libs/video/rsx_draw_engine.c",
"""    u32 slot = ENG_MAX_SURFACES;
    for (u32 i = 0; i < g.n_surfaces; i++)
        if (g.surfaces[i].location == location && g.surfaces[i].offset == offset) {
            if (g.surfaces[i].w == want_w && g.surfaces[i].h == want_h &&
                g.surfaces[i].fmt == want_fmt)
                return i;
            slot = i;
            break;
        }
""",
"""    u32 slot = ENG_MAX_SURFACES;
#if defined(__ANDROID__)
    u32 eu_any = ENG_INVALID, eu_any_stamp = 0;
    for (u32 i = 0; i < g.n_surfaces; i++) {
        eng_surface* c = &g.surfaces[i];
        if (!c->handle || c->location != location || c->offset != offset) continue;
        if (c->w == want_w && c->h == want_h && c->fmt == want_fmt) {
            c->stamp = ++eu_surface_tick;
            return i;
        }
        if (eu_any == ENG_INVALID || c->stamp >= eu_any_stamp) { eu_any = i; eu_any_stamp = c->stamp; }
    }
    /* a free slot left by an earlier failure is reused */
    for (u32 i = 0; i < g.n_surfaces && slot == ENG_MAX_SURFACES; i++)
        if (!g.surfaces[i].handle) slot = i;
#else
    for (u32 i = 0; i < g.n_surfaces; i++)
        if (g.surfaces[i].location == location && g.surfaces[i].offset == offset) {
            if (g.surfaces[i].w == want_w && g.surfaces[i].h == want_h &&
                g.surfaces[i].fmt == want_fmt)
                return i;
            slot = i;
            break;
        }
#endif
""")
change("libs/video/rsx_draw_engine.c",
"""                    slot < ENG_MAX_SURFACES ? "existing" : "absent");
        return slot < ENG_MAX_SURFACES ? slot : ENG_INVALID;""",
"""                    slot < ENG_MAX_SURFACES ? "existing" : "absent");
#if defined(__ANDROID__)
        return eu_any;
#else
        return slot < ENG_MAX_SURFACES ? slot : ENG_INVALID;
#endif""")
change("libs/video/rsx_draw_engine.c",
"""    if (slot == ENG_MAX_SURFACES) {
        if (g.n_surfaces >= ENG_MAX_SURFACES) return ENG_INVALID;
        slot = g.n_surfaces;
    }
""",
"""    if (slot == ENG_MAX_SURFACES) {
#if defined(__ANDROID__)
        if (g.n_surfaces >= ENG_MAX_SURFACES) {
            /* full: recycle the least recently used target */
            u32 lru = 0;
            for (u32 i = 1; i < g.n_surfaces; i++)
                if (g.surfaces[i].stamp < g.surfaces[lru].stamp) lru = i;
            slot = lru;
        } else
            slot = g.n_surfaces;
#else
        if (g.n_surfaces >= ENG_MAX_SURFACES) return ENG_INVALID;
        slot = g.n_surfaces;
#endif
    }
""")
change("libs/video/rsx_draw_engine.c",
"""    s->handle = handle;
    if (slot == g.n_surfaces) g.n_surfaces++;""",
"""    s->handle = handle;
#if defined(__ANDROID__)
    s->stamp = ++eu_surface_tick;
#endif
    if (slot == g.n_surfaces) g.n_surfaces++;""")
change("libs/video/rsx_draw_engine.c",
"""        int sampled = -1;
        for (u32 i = 0; i < g.n_surfaces; i++) {
            if (!g.surfaces[i].handle || g.surfaces[i].location != t.location ||
                g.surfaces[i].offset != t.offset)
                continue;""",
"""        int sampled = -1;
#if defined(__ANDROID__)
        /* Several targets can live at this address: take the one of the
         * texture's size, else the newest one at least that big; never one
         * this draw writes. Nothing fits -> guest memory, as upstream. */
        {
            u32 best_score = 0;
            for (u32 i = 0; i < g.n_surfaces; i++) {
                const eng_surface* c = &g.surfaces[i];
                if (!c->handle || c->location != t.location || c->offset != t.offset) continue;
                int own = 0;
                for (u32 k = 0; k < n_targets; k++) if (target_slots[k] == i) own = 1;
                if (own) continue;
                /* exact size first, then one that covers the texture; a
                 * smaller target is never substituted (a 256x256 picture
                 * stretched over a 1280x720 read washed the frame out) */
                u32 rank;
                if (c->w == t.width && c->h == t.height) rank = 2u;
                else if (c->w >= t.width && c->h >= t.height) rank = 1u;
                else continue;
                const u32 score = (rank << 30) | ((c->stamp & 0x3FFFFFFFu) + 1u);
                if (score > best_score) { best_score = score; sampled = (int)i; }
            }
        }
        for (u32 i = 0; i < g.n_surfaces && sampled < 0 && 0; i++) {
#else
        for (u32 i = 0; i < g.n_surfaces; i++) {
#endif
            if (!g.surfaces[i].handle || g.surfaces[i].location != t.location ||
                g.surfaces[i].offset != t.offset)
                continue;""")
change("libs/video/rsx_draw_engine.c",
"""        const eng_display_buffer* d = &g.display_buffers[buffer_id];
        for (u32 i = 0; i < g.n_surfaces; i++)
            if (g.surfaces[i].handle && g.surfaces[i].location == d->location &&
                g.surfaces[i].offset == d->offset)
                return i;""",
"""        const eng_display_buffer* d = &g.display_buffers[buffer_id];
#if defined(__ANDROID__)
        /* the most recently used target at the display buffer's address */
        u32 best = ENG_INVALID;
        for (u32 i = 0; i < g.n_surfaces; i++)
            if (g.surfaces[i].handle && g.surfaces[i].location == d->location &&
                g.surfaces[i].offset == d->offset &&
                (best == ENG_INVALID || g.surfaces[i].stamp > g.surfaces[best].stamp))
                best = i;
        if (best != ENG_INVALID) return best;
#else
        for (u32 i = 0; i < g.n_surfaces; i++)
            if (g.surfaces[i].handle && g.surfaces[i].location == d->location &&
                g.surfaces[i].offset == d->offset)
                return i;
#endif""")
# SPU speed 1: shufb on ARM. Upstream's fast path is SSE4.1 only; arm64 fell
# back to a 32-byte scalar loop and spu_shufb led every hot-spot sample.
# Same algorithm as the SSE version: tbl2 over {a,b} with the control ^3
# (SPU byte order), special selectors (>= 0x80) from a 16-entry table on the
# high nibble. Checked against the reference on 200k random inputs.
change("runtime/spu/spu_helpers.h",
"""#else
static inline u128 spu_shufb(u128 a, u128 b, u128 c) {""",
"""#elif defined(__aarch64__)
#include <arm_neon.h>
static inline u128 spu_shufb(u128 a, u128 b, u128 c) {
    uint8x16x2_t ab;
    uint8x16_t C;
    memcpy(&ab.val[0], &a, 16); memcpy(&ab.val[1], &b, 16); memcpy(&C, &c, 16);
    uint8x16_t r = vqtbl2q_u8(ab, vandq_u8(veorq_u8(C, vdupq_n_u8(3)), vdupq_n_u8(0x1F)));
    static const uint8_t eu_shufb_lut[16] = {0,0,0,0, 0,0,0,0, 0,0,0,0, 0xFF,0xFF,0x80,0x80};
    const uint8x16_t special = vqtbl1q_u8(vld1q_u8(eu_shufb_lut), vshrq_n_u8(C, 4));
    r = vbslq_u8(vcgeq_u8(C, vdupq_n_u8(0x80)), special, r);
    u128 out; memcpy(&out, &r, 16); return out;
}
#else
static inline u128 spu_shufb(u128 a, u128 b, u128 c) {""")
# SPU speed 2: bionic's setjmp() also saves the signal mask -- a
# rt_sigprocmask syscall on every SPU job/run entry (the "libc.so+0xe916c
# called from sigprocmask64" hot spot). Nothing here longjmps out of a signal
# handler, so the mask-free pair is equivalent.
for _f in ("runtime/spu/spu_drain.c", "runtime/spu/spu_channels.c"):
    change(_f, "#include <setjmp.h>\n",
           "#include <setjmp.h>\n#if defined(__ANDROID__)\n#undef setjmp\n#undef longjmp\n"
           "#define setjmp(e) _setjmp(e)\n#define longjmp(e, v) _longjmp((e), (v))\n#endif\n")
# Graphics-thread speed: texture cache lookup by hash table and sampled
# per-frame re-hashing on Android (see the comment in the replacement).
change("libs/video/rsx_draw_engine.c",
"""    u64 content_hash;
    u32 last_hash_frame;""",
"""    u64 content_hash;
    u64 full_hash;         /* Android: whole-span hash (content_hash is sampled) */
    u32 last_hash_frame;""")
change("libs/video/rsx_draw_engine.c", 'static u32 eng_texture_slot(u32 location, u32 offset, u32 fmt, u32 w, u32 h,\n                            u32 levels, u32 pitch, int cube, u32 remap)\n{\n    if (!w || !h || w > ENG_MAX_TEX_DIM || h > ENG_MAX_TEX_DIM) return 0;\n    const u32 span = eng_texture_span(fmt, w, h, levels, pitch, cube);\n    if (!span) return 0;\n\n    for (u32 i = 0; i < g.n_textures; i++) {\n        eng_texture* e = &g.textures[i];\n        if (e->location != location || e->offset != offset ||\n            e->format != fmt || e->width != w || e->height != h ||\n            e->pitch != pitch || e->remap != remap ||\n            e->cubemap != (u32)(cube != 0))\n            continue;\n        if (e->handle && e->last_hash_frame != g.frames) {\n            int readable = 0;\n            const u64 hash = eng_texture_content_hash(location, offset, span,\n                                                      &readable);\n            e->last_hash_frame = g.frames;\n            if (readable && hash != e->content_hash) {\n                const u32 fresh = eng_texture_upload(location, offset, fmt, w, h,\n                                                     levels, pitch, cube, remap);\n                if (fresh) {\n                    g.be->texture_release(g.be->user, e->handle);\n                    e->handle = fresh;\n                    e->content_hash = hash;\n                }\n            }\n        }\n        e->last_use_serial = ++g.texture_use_serial;\n        return e->handle;\n    }\n\n    u32 index;\n    u32 evicted = 0;\n    if (g.n_textures < ENG_MAX_TEXTURES) {\n        index = g.n_textures++;\n    } else {\n        index = 0;\n        for (u32 i = 1; i < g.n_textures; i++)\n            if (g.textures[i].last_use_serial < g.textures[index].last_use_serial)\n                index = i;\n        evicted = g.textures[index].handle;\n    }\n\n    eng_texture e;\n    memset(&e, 0, sizeof(e));\n    e.location = location; e.offset = offset; e.format = fmt;\n    e.width = w; e.height = h; e.pitch = pitch;\n    e.remap = remap; e.cubemap = (u32)(cube != 0);\n    e.last_hash_frame = g.frames;\n    e.last_use_serial = ++g.texture_use_serial;\n    { int readable = 0;\n      e.content_hash = eng_texture_content_hash(location, offset, span, &readable); }\n    e.handle = eng_texture_upload(location, offset, fmt, w, h, levels, pitch,\n                                  cube, remap);\n    if (e.handle && evicted) g.be->texture_release(g.be->user, evicted);\n    if (e.handle || !evicted) g.textures[index] = e;\n    return e.handle;\n}\n\n', "#if defined(__ANDROID__)\n/* Android: the texture cache is looked up for every unit of every draw (tens\n * of thousands of times a frame) and upstream scans all 1024 entries each\n * time, then FNV-hashes each cached texture's whole span once per frame.\n * Here: an open-addressed index over the cache (rebuilt on insert, entries\n * re-validated on lookup), and for spans over 64 KiB a 1024-point sampled\n * hash every frame plus a full hash every 16th frame (staggered per entry),\n * so partial updates are still caught within 16 frames. */\n#define EU_TEX_INDEX 4096u\nstatic u32 eu_tex_index[EU_TEX_INDEX];   /* cache slot + 1; 0 = empty */\nstatic u32 eu_tex_bucket(u32 loc, u32 off, u32 fmt, u32 w, u32 h, u32 pitch, u32 remap, u32 cube)\n{\n    u32 x = off * 0x9E3779B1u ^ loc * 0x85EBCA77u ^ fmt * 0xC2B2AE3Du ^ w * 0x27D4EB2Fu ^\n            h * 0x165667B1u ^ pitch * 0xD3A2646Bu ^ remap * 0xFD7046C5u ^ cube;\n    x ^= x >> 15; x *= 0x2C1B3C6Du; x ^= x >> 12;\n    return x & (EU_TEX_INDEX - 1u);\n}\nstatic int eu_tex_matches(const eng_texture* e, u32 loc, u32 off, u32 fmt, u32 w, u32 h,\n                          u32 pitch, u32 remap, u32 cube)\n{\n    return e->location == loc && e->offset == off && e->format == fmt && e->width == w &&\n           e->height == h && e->pitch == pitch && e->remap == remap && e->cubemap == cube;\n}\nstatic void eu_tex_index_rebuild(void)\n{\n    memset(eu_tex_index, 0, sizeof eu_tex_index);\n    for (u32 i = 0; i < g.n_textures; i++) {\n        const eng_texture* e = &g.textures[i];\n        u32 b = eu_tex_bucket(e->location, e->offset, e->format, e->width, e->height, e->pitch,\n                              e->remap, e->cubemap);\n        while (eu_tex_index[b]) b = (b + 1u) & (EU_TEX_INDEX - 1u);\n        eu_tex_index[b] = i + 1u;\n    }\n}\nstatic u64 eu_fnv(const u8* p, u32 n, u64 hash)\n{\n    u32 i = 0;\n    for (; i + 8 <= n; i += 8) { u64 w; memcpy(&w, p + i, 8); hash ^= w; hash *= 1099511628211ull; }\n    for (; i < n; i++) { hash ^= p[i]; hash *= 1099511628211ull; }\n    return hash;\n}\n#define EU_TEX_SAMPLE_MIN 65536u\nstatic u64 eu_tex_hash_full(const u8* src, u32 span) { return eu_fnv(src, span, 1469598103934665603ull); }\nstatic u64 eu_tex_hash_sampled(const u8* src, u32 span)\n{\n    if (span <= EU_TEX_SAMPLE_MIN) return eu_tex_hash_full(src, span);\n    u64 hash = 1469598103934665603ull;\n    const u32 step = span / 1024u;\n    for (u32 k = 0; k < 1024u; k++) hash = eu_fnv(src + (size_t)k * step, 16u, hash);\n    return eu_fnv(src + span - 16u, 16u, hash);\n}\n\nstatic u32 eng_texture_slot(u32 location, u32 offset, u32 fmt, u32 w, u32 h,\n                            u32 levels, u32 pitch, int cube, u32 remap)\n{\n    if (!w || !h || w > ENG_MAX_TEX_DIM || h > ENG_MAX_TEX_DIM) return 0;\n    const u32 span = eng_texture_span(fmt, w, h, levels, pitch, cube);\n    if (!span) return 0;\n    const u32 cu = (u32)(cube != 0);\n\n    u32 b = eu_tex_bucket(location, offset, fmt, w, h, pitch, remap, cu);\n    for (u32 v; (v = eu_tex_index[b]) != 0; b = (b + 1u) & (EU_TEX_INDEX - 1u)) {\n        const u32 i = v - 1u;\n        if (i >= g.n_textures) continue;\n        eng_texture* e = &g.textures[i];\n        if (!eu_tex_matches(e, location, offset, fmt, w, h, pitch, remap, cu)) continue;\n        if (e->handle && e->last_hash_frame != g.frames) {\n            e->last_hash_frame = g.frames;\n            const u8* src = (const u8*)eng_guest_ptr(NULL, location, offset, span);\n            if (src) {\n                const u64 hs = eu_tex_hash_sampled(src, span);\n                const int full_due = span > EU_TEX_SAMPLE_MIN && ((g.frames + i) & 15u) == 0;\n                const u64 hf = full_due ? eu_tex_hash_full(src, span) : e->full_hash;\n                if (hs != e->content_hash || hf != e->full_hash) {\n                    const u32 fresh = eng_texture_upload(location, offset, fmt, w, h,\n                                                         levels, pitch, cube, remap);\n                    if (fresh) {\n                        g.be->texture_release(g.be->user, e->handle);\n                        e->handle = fresh;\n                        e->content_hash = hs;\n                        e->full_hash = full_due ? hf : eu_tex_hash_full(src, span);\n                    }\n                }\n            }\n        }\n        e->last_use_serial = ++g.texture_use_serial;\n        return e->handle;\n    }\n\n    u32 index;\n    u32 evicted = 0;\n    if (g.n_textures < ENG_MAX_TEXTURES) {\n        index = g.n_textures++;\n    } else {\n        index = 0;\n        for (u32 i = 1; i < g.n_textures; i++)\n            if (g.textures[i].last_use_serial < g.textures[index].last_use_serial)\n                index = i;\n        evicted = g.textures[index].handle;\n    }\n\n    eng_texture e;\n    memset(&e, 0, sizeof(e));\n    e.location = location; e.offset = offset; e.format = fmt;\n    e.width = w; e.height = h; e.pitch = pitch;\n    e.remap = remap; e.cubemap = cu;\n    e.last_hash_frame = g.frames;\n    e.last_use_serial = ++g.texture_use_serial;\n    { const u8* src = (const u8*)eng_guest_ptr(NULL, location, offset, span);\n      if (src) { e.content_hash = eu_tex_hash_sampled(src, span); e.full_hash = eu_tex_hash_full(src, span); } }\n    e.handle = eng_texture_upload(location, offset, fmt, w, h, levels, pitch,\n                                  cube, remap);\n    if (e.handle && evicted) g.be->texture_release(g.be->user, evicted);\n    if (e.handle || !evicted) g.textures[index] = e;\n    else if (index == g.n_textures - 1u && !evicted) g.n_textures--;\n    eu_tex_index_rebuild();\n    return e.handle;\n}\n#else\nstatic u32 eng_texture_slot(u32 location, u32 offset, u32 fmt, u32 w, u32 h,\n                            u32 levels, u32 pitch, int cube, u32 remap)\n{\n    if (!w || !h || w > ENG_MAX_TEX_DIM || h > ENG_MAX_TEX_DIM) return 0;\n    const u32 span = eng_texture_span(fmt, w, h, levels, pitch, cube);\n    if (!span) return 0;\n\n    for (u32 i = 0; i < g.n_textures; i++) {\n        eng_texture* e = &g.textures[i];\n        if (e->location != location || e->offset != offset ||\n            e->format != fmt || e->width != w || e->height != h ||\n            e->pitch != pitch || e->remap != remap ||\n            e->cubemap != (u32)(cube != 0))\n            continue;\n        if (e->handle && e->last_hash_frame != g.frames) {\n            int readable = 0;\n            const u64 hash = eng_texture_content_hash(location, offset, span,\n                                                      &readable);\n            e->last_hash_frame = g.frames;\n            if (readable && hash != e->content_hash) {\n                const u32 fresh = eng_texture_upload(location, offset, fmt, w, h,\n                                                     levels, pitch, cube, remap);\n                if (fresh) {\n                    g.be->texture_release(g.be->user, e->handle);\n                    e->handle = fresh;\n                    e->content_hash = hash;\n                }\n            }\n        }\n        e->last_use_serial = ++g.texture_use_serial;\n        return e->handle;\n    }\n\n    u32 index;\n    u32 evicted = 0;\n    if (g.n_textures < ENG_MAX_TEXTURES) {\n        index = g.n_textures++;\n    } else {\n        index = 0;\n        for (u32 i = 1; i < g.n_textures; i++)\n            if (g.textures[i].last_use_serial < g.textures[index].last_use_serial)\n                index = i;\n        evicted = g.textures[index].handle;\n    }\n\n    eng_texture e;\n    memset(&e, 0, sizeof(e));\n    e.location = location; e.offset = offset; e.format = fmt;\n    e.width = w; e.height = h; e.pitch = pitch;\n    e.remap = remap; e.cubemap = (u32)(cube != 0);\n    e.last_hash_frame = g.frames;\n    e.last_use_serial = ++g.texture_use_serial;\n    { int readable = 0;\n      e.content_hash = eng_texture_content_hash(location, offset, span, &readable); }\n    e.handle = eng_texture_upload(location, offset, fmt, w, h, levels, pitch,\n                                  cube, remap);\n    if (e.handle && evicted) g.be->texture_release(g.be->user, evicted);\n    if (e.handle || !evicted) g.textures[index] = e;\n    return e.handle;\n}\n\n#endif\n")
# SPU speed 3: time reads. __kernel_clock_gettime was 10-17% of the busiest
# SPU thread's samples (decrementer reads, QPC/GetTickCount shims, lockstep
# clock). Read the arm64 generic timer directly instead.
_FAST = '\n#if defined(__ANDROID__) && defined(__aarch64__)\n/* Android/arm64: read the generic timer directly (one mrs) instead of a vDSO\n * clock_gettime per call; anchored to CLOCK_MONOTONIC on first use so values\n * stay comparable with the clock_gettime ones elsewhere. */\nstatic inline unsigned long long eu_fast_mono_ns_%(tag)s(void)\n{\n    static unsigned long long s_base_ns, s_base_cnt, s_mult;\n    unsigned long long cnt;\n    __asm__ volatile("mrs %%0, cntvct_el0" : "=r"(cnt));\n    unsigned long long mult = __atomic_load_n(&s_mult, __ATOMIC_ACQUIRE);\n    if (__builtin_expect(!mult, 0)) {\n        struct timespec ts;\n        unsigned long long frq;\n        __asm__ volatile("mrs %%0, cntfrq_el0" : "=r"(frq));\n        if (!frq) frq = 19200000ull;\n        clock_gettime(CLOCK_MONOTONIC, &ts);\n        __asm__ volatile("mrs %%0, cntvct_el0" : "=r"(s_base_cnt));\n        s_base_ns = (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;\n        mult = (1000000000ull << 32) / frq;\n        __atomic_store_n(&s_mult, mult, __ATOMIC_RELEASE);\n        cnt = s_base_cnt;\n    }\n    return s_base_ns + (unsigned long long)(((unsigned __int128)(cnt - s_base_cnt) * mult) >> 32);\n}\n#endif\n'
change("runtime/platform/win32_compat.h",
"""static inline unsigned long long ps3__mono_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;
}""",
(_FAST % {"tag": "w32"}) + """static inline unsigned long long ps3__mono_ns(void)
{
#if defined(__ANDROID__) && defined(__aarch64__)
    return eu_fast_mono_ns_w32();
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;
#endif
}""")
change("runtime/spu/spu_channels.c",
"""static uint64_t spu_host_ns(void)
{""",
(_FAST % {"tag": "chan"}) + """static uint64_t spu_host_ns(void)
{
#if defined(__ANDROID__) && defined(__aarch64__)
    return (uint64_t)eu_fast_mono_ns_chan();
#endif""")
change("runtime/spu/spu_lockstep.c",
"""static uint64_t ls_now_ns(void)
{
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);""",
(_FAST % {"tag": "ls"}) + """static uint64_t ls_now_ns(void)
{
#if defined(__ANDROID__) && defined(__aarch64__)
    return (uint64_t)eu_fast_mono_ns_ls();
#endif
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);""")
# Depth read through a colour format: deferred PS3 renderers sample the zeta
# buffer as A8R8G8B8 and rebuild depth from its bytes. Upstream only maps
# DEPTH24_D8 reads onto a tracked zeta, so these reads fell through to
# (empty) guest memory and inFamous' lighting composite went black. On
# Android, pack the zeta snapshot into RGBA8 the way the PS3 lays out D24S8
# (backend: eu_vk_depth_pack) and sample that, with the texture's remap.
change("libs/video/rsx_draw_engine.c",
"""    int snapshot_valid;
} eng_zdepth;""",
"""    int snapshot_valid;
    u32 eu_packed, eu_packed_src;   /* Android: snapshot packed as A8R8G8B8 */
} eng_zdepth;
#if defined(__ANDROID__)
extern u32 eu_vk_depth_pack(u32 snapshot, u32 w, u32 h) __attribute__((weak));
#endif""")
change("libs/video/rsx_draw_engine.c",
"""            if (textures[u]) continue;
        }
        textures[u] = eng_texture_slot(t.location, t.offset, t.format,""",
"""            if (textures[u]) continue;
        }
#if defined(__ANDROID__)
        if (base_fmt == RSX_TEX_FMT_A8R8G8B8 && eu_vk_depth_pack) {
            for (u32 i = 0; i < g.n_zdepths; i++) {
                eng_zdepth* z = &g.zdepths[i];
                if (z->location != t.location || z->offset != t.offset) continue;
                const u32 snap = eng_zdepth_snapshot(i);
                if (!snap) break;
                if (!z->eu_packed || z->eu_packed_src != snap) {
                    const u32 packed = eu_vk_depth_pack(snap, z->w, z->h);
                    if (packed) {
                        if (z->eu_packed && g.be->color_target_release)
                            g.be->color_target_release(g.be->user, z->eu_packed);
                        z->eu_packed = packed;
                        z->eu_packed_src = snap;
                    }
                }
                if (z->eu_packed) {
                    const u32 view = g.be->surface_view
                        ? g.be->surface_view(g.be->user, z->eu_packed, t.remap & 0xFFFFu, t.format) : 0;
                    textures[u] = view ? view : z->eu_packed;
                }
                break;
            }
            if (textures[u]) continue;
        }
#endif
        textures[u] = eng_texture_slot(t.location, t.offset, t.format,""")
# Snapshots were never released: every frame's depth clear made the next
# read create a fresh full-screen R32F copy and the old one leaked (handles
# climbed steadily across captures; GPU memory and the object table run out
# a few minutes into the 3D scenes). Release the old copy (the backend defers
# the free until the GPU is done with it) and force the packed colour copy
# to be rebuilt, since a recycled handle can equal the old snapshot's.
change("libs/video/rsx_draw_engine.c",
"""    if (z->snapshot_valid && z->snapshot) return z->snapshot;
    if (!g.be->depth_snapshot) return 0;""",
"""    if (z->snapshot_valid && z->snapshot) return z->snapshot;
    if (!g.be->depth_snapshot) return 0;
#if defined(__ANDROID__)
    if (z->snapshot && g.be->color_target_release) g.be->color_target_release(g.be->user, z->snapshot);
    z->snapshot = 0;
    z->eu_packed_src = 0;
#endif""")
change("libs/video/rsx_draw_engine.c",
"""    if (z->handle) g.be->depth_target_release(g.be->user, z->handle);
    z->location = location; z->offset = offset;""",
"""    if (z->handle) g.be->depth_target_release(g.be->user, z->handle);
#if defined(__ANDROID__)
    if (g.be->color_target_release) {
        if (z->snapshot) g.be->color_target_release(g.be->user, z->snapshot);
        if (z->eu_packed) g.be->color_target_release(g.be->user, z->eu_packed);
    }
    z->eu_packed = 0; z->eu_packed_src = 0;
#endif
    z->location = location; z->offset = offset;""")
# NV3089 blits out of a live render target. The game copies its lit scene
# (and G-buffer pieces) into other buffers with the 2D engine and samples
# those; upstream mirrors such blits only in the Windows live renderer, so on
# Android the copy happened in guest memory alone, which never holds the
# GPU-rendered image, and the composite that reads it went black. Mirror the
# blit on the GPU into a target registered at the destination address, so the
# texture read finds it like any render-to-texture surface. A later upload
# from memory into such a target drops it again.
change("libs/video/rsx_draw_engine.c",
"""    u32 stamp;             /* Android: last use, for picking among aliases */
} eng_surface;""",
"""    u32 stamp;             /* Android: last use, for picking among aliases */
    u32 eu_blit;           /* Android: created by a mirrored NV3089 blit */
} eng_surface;""")
change("libs/video/rsx_draw_engine.c",
"""    s->stamp = ++eu_surface_tick;
#endif
    if (slot == g.n_surfaces) g.n_surfaces++;""",
"""    s->stamp = ++eu_surface_tick;
    s->eu_blit = 0;
#endif
    if (slot == g.n_surfaces) g.n_surfaces++;""")
change("libs/video/rsx_draw_engine.c",
"""void rsx_draw_engine_set_backend(const rsx_draw_backend* backend)""",
"""#if defined(__ANDROID__)
extern int eu_vk_copy_surface(u32 src, u32 dst, u32 sx, u32 sy, u32 sw, u32 sh,
                              u32 dx, u32 dy, u32 dw, u32 dh) __attribute__((weak));
/* The RGBA8 target whose pitch-linear memory holds addr (newest alias wins). */
static u32 eu_surface_at(u32 location, u32 addr, u32 pitch, u32* x, u32* y)
{
    u32 best = ENG_INVALID, best_stamp = 0;
    for (u32 i = 0; i < g.n_surfaces; i++) {
        const eng_surface* s = &g.surfaces[i];
        if (!s->handle || s->location != location || s->fmt != RSX_BE_FMT_R8G8B8A8 || addr < s->offset)
            continue;
        const u32 d = addr - s->offset;
        if (d / pitch >= s->h || (d % pitch) / 4u >= s->w) continue;
        if (best == ENG_INVALID || s->stamp >= best_stamp) {
            best = i; best_stamp = s->stamp;
            *x = (d % pitch) / 4u; *y = d / pitch;
        }
    }
    return best;
}

void eu_engine_blit(int src_local, u32 src_off, u32 src_pitch, u32 u0, u32 v0,
                    int dst_local, u32 dst_off, u32 dst_pitch, u32 out_x, u32 out_y,
                    u32 out_w, u32 out_h, u32 ds_dx, u32 dt_dy)
{
    static u32 logs = 0, gpu = 0, mem = 0, dropped = 0, calls = 0, calllogs = 0;
    const u32 sloc = src_local ? RSX_LOCATION_LOCAL : RSX_LOCATION_MAIN;
    const u32 dloc = dst_local ? RSX_LOCATION_LOCAL : RSX_LOCATION_MAIN;
    /* every blit the game issues, whatever happens to it next */
    calls++;
    if (calllogs < 48 || (calls & 1023u) == 0) {
        calllogs++;
        u32 cx = 0, cy = 0;
        const u32 csi = (g.ready && src_pitch) ? eu_surface_at(sloc, src_off, src_pitch, &cx, &cy) : ENG_INVALID;
        fprintf(stderr, "[blit-call] #%u %u:0x%08X pitch %u uv %u,%u -> %u:0x%08X pitch %u at %u,%u size %ux%u"
                        " scale 0x%X/0x%X | source %s\\n",
                calls, sloc, src_off, src_pitch, u0, v0, dloc, dst_off, dst_pitch, out_x, out_y,
                out_w, out_h, ds_dx, dt_dy, csi == ENG_INVALID ? "memory" : "a drawn target");
    }
    if (!g.ready || !g.be || !eu_vk_copy_surface || !src_pitch || !dst_pitch || !out_w || !out_h) return;
    const u32 daddr = dst_off + out_y * dst_pitch + out_x * 4u;
    u32 sx = 0, sy = 0, dx = 0, dy = 0;
    const u32 si = eu_surface_at(sloc, src_off, src_pitch, &sx, &sy);
    u32 di = eu_surface_at(dloc, daddr, dst_pitch, &dx, &dy);
    if (si == ENG_INVALID) {
        /* an upload from memory: a blit-made target here would hide it */
        mem++;
        if (di != ENG_INVALID && g.surfaces[di].eu_blit && g.surfaces[di].offset == dst_off) {
            g.be->color_target_release(g.be->user, g.surfaces[di].handle);
            g.surfaces[di].handle = 0;
            dropped++;
        }
        return;
    }
    sx += u0; sy += v0;
    u32 sw = (u32)(((u64)out_w * ds_dx) >> 20), sh = (u32)(((u64)out_h * dt_dy) >> 20);
    if (!sw) sw = 1;
    if (!sh) sh = 1;
    if (di == ENG_INVALID || di == si) {
        if (out_x || out_y) return;   /* into the middle of something untracked */
        di = eng_surface_get(dloc, dst_off, out_w, out_h, RSX_BE_FMT_R8G8B8A8);
        if (di == ENG_INVALID || di == si) return;
        g.surfaces[di].eu_blit = 1;
        dx = dy = 0;
    }
    g.surfaces[di].stamp = ++eu_surface_tick;
    const int ok = eu_vk_copy_surface(g.surfaces[si].handle, g.surfaces[di].handle,
                                      sx, sy, sw, sh, dx, dy, out_w, out_h);
    gpu++;
    if (logs < 32) {
        logs++;
        fprintf(stderr, "[blit] %s %u:0x%08X (h%u %ux%u) %ux%u at %u,%u -> %u:0x%08X (h%u %ux%u) %ux%u at %u,%u"
                        " | so far %u from targets, %u from memory, %u blit targets dropped\\n",
                ok ? "mirrored" : "NOT mirrored", sloc, g.surfaces[si].offset, g.surfaces[si].handle,
                g.surfaces[si].w, g.surfaces[si].h, sw, sh, sx, sy, dloc, g.surfaces[di].offset,
                g.surfaces[di].handle, g.surfaces[di].w, g.surfaces[di].h, out_w, out_h, dx, dy,
                gpu, mem, dropped);
    }
}
#endif

void rsx_draw_engine_set_backend(const rsx_draw_backend* backend)""")
change("libs/video/cellGcmSys.c",
"""    /* Only the 32-bit colour formats are handled; anything else would need a
     * per-format converter and is better skipped loudly than written wrong. */""",
"""#if defined(__ANDROID__)
    { extern void eu_engine_blit(int, u32, u32, u32, u32, int, u32, u32, u32, u32, u32, u32, u32, u32)
          __attribute__((weak));
      if (eu_engine_blit && rsx_draw_engine_enabled())
          eu_engine_blit(s_nv3089.src_dma != 0xFEED0001u, s_nv3089.in_off, in_pitch,
                         (s_nv3089.in_uv & 0xFFFF) >> 4, (s_nv3089.in_uv >> 16) >> 4,
                         s_gcm2d.dst_dma != 0xFEED0001u, s_gcm2d.dst_offset, dst_pitch,
                         out_x, out_y, out_w, out_h, s_nv3089.ds_dx, s_nv3089.dt_dy); }
#endif
    /* Only the 32-bit colour formats are handled; anything else would need a
     * per-format converter and is better skipped loudly than written wrong. */""")
# Diagnostic: a large colour texture read straight from guest memory (no drawn
# target at its address). Say where it lives, what is drawn near it, and
# whether anything ever writes that memory, so the frame's final composite
# (reading a 1280x720 image nothing drew) can be traced to its writer.
change("libs/video/rsx_draw_engine.c",
"""#endif
        textures[u] = eng_texture_slot(t.location, t.offset, t.format,
                                       t.width, t.height, t.mipmaps, t.pitch,
                                       (int)t.cubemap, t.remap & 0xFFFFu);
    }
    return mask;""",
"""#endif
        textures[u] = eng_texture_slot(t.location, t.offset, t.format,
                                       t.width, t.height, t.mipmaps, t.pitch,
                                       (int)t.cubemap, t.remap & 0xFFFFu);
#if defined(__ANDROID__)
        /* screen-sized A8R8G8B8 only: ordinary textures filled the log's slots at boot */
        if (t.width >= 640 && t.height >= 360 && !t.cubemap &&
            (t.format & RSX_TEX_FMT_BASE_MASK & ~(u32)RSX_TEX_FMT_UNNORM) == RSX_TEX_FMT_A8R8G8B8)
            eu_texmiss_log(&t, textures[u]);
#endif
    }
    return mask;""")
change("libs/video/rsx_draw_engine.c",
"""static u32 sink_bind_textures(const u32* target_slots, u32 n_targets,""",
"""#if defined(__ANDROID__)
static void eu_texmiss_log(const rsx_dsp_texture* t, u32 handle)
{
    enum { MAXA = 32 };
    static struct { u32 loc, off, pitch, seen, last_nz; } a[MAXA];
    static u32 na;
    u32 k = 0;
    while (k < na && !(a[k].loc == t->location && a[k].off == t->offset && a[k].pitch == t->pitch)) k++;
    if (k == na) {
        if (na == MAXA) return;
        a[na].loc = t->location; a[na].off = t->offset; a[na].pitch = t->pitch;
        a[na].seen = 0; a[na].last_nz = 0xFFFFFFFFu;
        na++;
    }
    a[k].seen++;
    /* sample 1024 words spread over the image: how much of it is non-zero */
    const u32 pitch = t->pitch ? t->pitch : t->width * 4u;
    const u32 span = pitch * (t->height - 1u) + t->width * 4u;
    const u8* p = eng_guest_ptr(NULL, t->location, t->offset, span);
    u32 nz = 0;
    u64 sum = 0;
    if (p) {
        for (u32 i = 0; i < 1024u; i++) {
            const u32 y = (i / 32u) * t->height / 32u, x = (i % 32u) * t->width / 32u;
            u32 w;
            memcpy(&w, p + (u64)y * pitch + x * 4u, 4);
            if (w) nz++;
            sum += w;
        }
    }
    /* first sighting, then whenever the memory's fill changes (at most every 64th) */
    if (a[k].seen != 1 && (nz == a[k].last_nz || (a[k].seen & 63u))) return;
    a[k].last_nz = nz;
    char line[1400];
    int o = snprintf(line, sizeof line,
        "[texmiss] h%u %ux%u fmt 0x%02X at %u:0x%08X pitch %u (read %u times) | memory %s, %u of 1024 samples non-zero, sum 0x%llX | drawn nearby:",
        handle, t->width, t->height, t->format & 0xFFu, t->location, t->offset, pitch, a[k].seen,
        p ? "mapped" : "NOT mapped", nz, (unsigned long long)sum);
    int shown = 0;
    for (u32 i = 0; i < g.n_surfaces && shown < 8 && o < (int)sizeof line - 120; i++) {
        const eng_surface* c = &g.surfaces[i];
        if (!c->handle || c->location != t->location) continue;
        const long long d = (long long)c->offset - (long long)t->offset;
        if (d < -(16ll << 20) || d > (16ll << 20)) continue;
        o += snprintf(line + o, sizeof line - o, " h%u %ux%u fmt %d at %+lld%s age %u;", c->handle, c->w, c->h,
                      (int)c->fmt, d, (d >= 0 && (u64)d < span) ? " (inside)" : "",
                      eu_surface_tick - c->stamp);
        shown++;
    }
    for (u32 i = 0; i < g.n_zdepths && o < (int)sizeof line - 80; i++) {
        const eng_zdepth* z = &g.zdepths[i];
        if (!z->handle || z->location != t->location) continue;
        const long long d = (long long)z->offset - (long long)t->offset;
        if (d < -(16ll << 20) || d > (16ll << 20)) continue;
        o += snprintf(line + o, sizeof line - o, " depth h%u %ux%u at %+lld;", z->handle, z->w, z->h, d);
    }
    if (!shown) o += snprintf(line + o, sizeof line - o, " nothing within 16 MB");
    fprintf(stderr, "%s\\n", line);
}
#endif

static u32 sink_bind_textures(const u32* target_slots, u32 n_targets,""")
# Label each draw with the game code that wrote its command
# (runtime-integration/eu_fifo_tag.cpp): arm the store observer on the IO
# window at cellGcmInit, and name the draw when the drain reaches BEGIN_END.
change("libs/video/cellGcmSys.c",
"""    s_config.ioAddress       = ioAddress;
    s_config.ioSize          = ioSize;""",
"""    s_config.ioAddress       = ioAddress;
    s_config.ioSize          = ioSize;
#if defined(__ANDROID__)
    { extern void eu_fifo_tag_arm(u32, u32) __attribute__((weak));
      if (eu_fifo_tag_arm) eu_fifo_tag_arm(ioAddress, ioSize); }
#endif""")
change("libs/video/cellGcmSys.c",
"""                  if (eng < 0) eng = rsx_draw_engine_enabled();
                  if (eng) rsx_draw_engine_method((subch << 13) | m, vm_read32(dea)); }""",
"""                  if (eng < 0) eng = rsx_draw_engine_enabled();
#if defined(__ANDROID__)
                  { extern int eu_fifo_tag_draw(u32) __attribute__((weak));
                    extern int g_eu_draw_tag __attribute__((weak));
                    if (eng && !subch && (m & 0x1FFCu) == 0x1808u && eu_fifo_tag_draw && &g_eu_draw_tag &&
                        vm_read32(dea)) g_eu_draw_tag = eu_fifo_tag_draw(dea); }
#endif
                  if (eng) rsx_draw_engine_method((subch << 13) | m, vm_read32(dea)); }""")
# Feedback reads: a pass that samples the very target it is drawing into (the
# frame's final composite reads the lit scene at its own colour target's
# address). On the PS3 that reads the pixels already rendered there; here the
# "never sample a target this draw writes" rule sent it to guest memory, which
# is all zeros for a GPU-only target -> the black final picture. Give such reads
# a copy of the target taken when the pass's target set was bound (one copy per
# pass, not per draw).
change("libs/video/rsx_draw_engine.c",
"""    u32 eu_blit;           /* Android: created by a mirrored NV3089 blit */""",
"""    u32 eu_blit;           /* Android: created by a mirrored NV3089 blit */
    u32 eu_fb, eu_fb_serial; /* Android: copy of this target for passes that sample it */""")
change("libs/video/rsx_draw_engine.c",
"""    if (s->handle) g.be->color_target_release(g.be->user, s->handle);
    s->location = location;""",
"""    if (s->handle) g.be->color_target_release(g.be->user, s->handle);
#if defined(__ANDROID__)
    if (slot < g.n_surfaces && s->eu_fb) g.be->color_target_release(g.be->user, s->eu_fb);
    s->eu_fb = 0; s->eu_fb_serial = 0;
#endif
    s->location = location;""")
change("libs/video/rsx_draw_engine.c",
"""static u32 sink_bind_textures(const u32* target_slots, u32 n_targets,""",
"""#if defined(__ANDROID__)
extern int eu_vk_copy_surface(u32 src, u32 dst, u32 sx, u32 sy, u32 sw, u32 sh,
                              u32 dx, u32 dy, u32 dw, u32 dh) __attribute__((weak));
static u32 eu_fb_serial_now;
/* The copy of surface si for this pass (made on first use in the pass). */
static u32 eu_feedback_copy(u32 si)
{
    static u32 made, logs;
    eng_surface* s = &g.surfaces[si];
    if (!eu_vk_copy_surface || s->fmt != RSX_BE_FMT_R8G8B8A8 || !s->handle) return 0;
    if (!s->eu_fb) {
        s->eu_fb = g.be->color_target_create(g.be->user, s->fmt, s->w, s->h, NULL, 0);
        s->eu_fb_serial = 0;
        if (!s->eu_fb) return 0;
    }
    if (s->eu_fb_serial != eu_fb_serial_now) {
        if (!eu_vk_copy_surface(s->handle, s->eu_fb, 0, 0, s->w, s->h, 0, 0, s->w, s->h)) return 0;
        s->eu_fb_serial = eu_fb_serial_now;
        made++;
        if (logs < 8) {
            logs++;
            fprintf(stderr, "[feedback] pass samples its own target h%u (%ux%u at %u:0x%08X): reading copy h%u (%u copies so far)\\n",
                    s->handle, s->w, s->h, s->location, s->offset, s->eu_fb, made);
        }
    }
    return s->eu_fb;
}
#endif

static u32 sink_bind_textures(const u32* target_slots, u32 n_targets,""")
change("libs/video/rsx_draw_engine.c",
"""        int sampled = -1;
#if defined(__ANDROID__)""",
"""        int sampled = -1;
#if defined(__ANDROID__)
        int eu_own_match = -1;
        { static u32 last_key = 0xFFFFFFFFu;
          u32 key = n_targets * 2654435761u;
          for (u32 k = 0; k < n_targets; k++) key = key * 31u + target_slots[k] + 1u;
          if (key != last_key) { last_key = key; eu_fb_serial_now++; } }""")
change("libs/video/rsx_draw_engine.c",
"""                if (own) continue;""",
"""                if (own) {
                    if (c->w == t.width && c->h == t.height) eu_own_match = (int)i;
                    continue;
                }""")
change("libs/video/rsx_draw_engine.c",
"""        for (u32 i = 0; i < g.n_surfaces && sampled < 0 && 0; i++) {""",
"""        if (sampled < 0 && eu_own_match >= 0) {
            const u32 fb = eu_feedback_copy((u32)eu_own_match);
            if (fb) {
                const u32 view = g.be->surface_view
                    ? g.be->surface_view(g.be->user, fb, t.remap & 0xFFFFu, t.format) : 0;
                textures[u] = view ? view : fb;
                continue;
            }
        }
        for (u32 i = 0; i < g.n_surfaces && sampled < 0 && 0; i++) {""")
# Whole-register SPU integer helpers on ARM64. spu_helpers_vec.h only exists
# for SSE4.1 hosts, so on ARM64 the register-cached SPU job code ran every
# integer op lane by lane through the reference helpers. Use the generic-vector
# twin (runtime-integration/spu_helpers_vec_arm64.h; checked bit-for-bit against
# the references by tools/test_spu_vec_arm64.py) instead.
import shutil as _shutil
for _h in ("spu_helpers_vec_arm64.h", "spu_fsimd_arm64.h"):
    _shutil.copyfile(pathlib.Path(__file__).resolve().parent.parent / "runtime-integration" / _h,
                     root / "runtime" / "spu" / _h)
change("runtime/spu/spu_helpers_vec.h",
"""#if defined(__SSE4_1__) && !defined(SPU_HELPERS_VEC_DISABLE)
#include <smmintrin.h>""",
"""#if defined(__aarch64__) && !defined(SPU_HELPERS_VEC_DISABLE) && !defined(SPU_VEC_ARM64_DISABLE)
#include "spu_helpers_vec_arm64.h"
#elif defined(__SSE4_1__) && !defined(SPU_HELPERS_VEC_DISABLE)
#include <smmintrin.h>""")
# Name the SPU host threads so the [cpu] / [hot] lines say which SPURS
# instance, kernel part and workload lane each busy thread is.
change("libs/spurs/cellSpurs.c",
"""    struct SpursKArg* ka = (struct SpursKArg*)p;
    struct SpursInst* si = ka->si;
    const u32 k_part = ka->part, k_nparts = ka->nparts;""",
"""    struct SpursKArg* ka = (struct SpursKArg*)p;
    struct SpursInst* si = ka->si;
    const u32 k_part = ka->part, k_nparts = ka->nparts;
#if defined(__ANDROID__)
    { extern int prctl(int, ...); char nm[16];
      snprintf(nm, sizeof nm, "spurs i%d k%u", (int)(si - s_inst), (unsigned)k_part);
      prctl(15 /* PR_SET_NAME */, nm, 0, 0, 0); }
#endif""")
change("libs/spurs/cellSpurs.c",
"""            CreateThread(NULL, 1u << 20, spurs_lane_worker, wa, 0, NULL);""",
"""#if defined(__ANDROID__)
            { HANDLE th = CreateThread(NULL, 1u << 20, spurs_lane_worker, wa, 0, NULL);
              if (th) { wchar_t nm[16]; swprintf(nm, 16, L"spu w%u lane%u", (unsigned)wid, (unsigned)(i + 1));
                        SetThreadDescription(th, nm); } }
#else
            CreateThread(NULL, 1u << 20, spurs_lane_worker, wa, 0, NULL);
#endif""")
# The SPU single-precision ops (fa fs fm fma fms fnms) as one packed operation
# each on ARM64, as upstream already does with SSE on x86. The hottest job
# kernels (e.g. the skinning / blend loops) are made of these.
change("runtime/spu/spu_helpers.h",
"""#else
#define SPU__FSIMD 0
#endif""",
"""#elif defined(__aarch64__) && !defined(SPU_FSIMD_ARM64_DISABLE)
#define SPU__FSIMD 1
#include "spu_fsimd_arm64.h"
#else
#define SPU__FSIMD 0
#endif""")
# Sound. The audio module only has WASAPI (Windows) and SDL2 outputs; the
# Android app never starts SDL, so cellAudio "continued silently": the game
# mixed every block and threw it away. Android output through AAudio (loaded
# at run time from libaaudio.so, Android 8+), same small interface the mixer
# already drives: submit / room / queued / wait. A disconnected stream (e.g.
# headphones pulled) is reopened from the mixer thread, at most once a second.
change("libs/audio/cellAudio.c",
"""#elif defined(_WIN32)
  #define AUDIO_BACKEND_SDL2    0
  #define AUDIO_BACKEND_WASAPI  1
#else""",
"""#elif defined(_WIN32)
  #define AUDIO_BACKEND_SDL2    0
  #define AUDIO_BACKEND_WASAPI  1
#elif defined(__ANDROID__)
  #define AUDIO_BACKEND_SDL2    0
  #define AUDIO_BACKEND_WASAPI  0
  #define AUDIO_BACKEND_AAUDIO  1
#else""")
change("libs/audio/cellAudio.c",
"""#endif /* AUDIO_BACKEND_SDL2 */
""",
"""#endif /* AUDIO_BACKEND_SDL2 */

#if defined(AUDIO_BACKEND_AAUDIO) && AUDIO_BACKEND_AAUDIO
#include <dlfcn.h>
typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;
enum { EU_AA_DIR_OUTPUT = 0, EU_AA_FMT_FLOAT = 2, EU_AA_SHARED = 1, EU_AA_PERF_LOW_LATENCY = 12,
       EU_AA_USAGE_GAME = 14, EU_AA_STATE_DISCONNECTED = 13, EU_AA_ERR_DISCONNECTED = -899 };
static struct {
    int loaded;
    int32_t (*create_builder)(AAudioStreamBuilder**);
    void (*set_direction)(AAudioStreamBuilder*, int32_t);
    void (*set_rate)(AAudioStreamBuilder*, int32_t);
    void (*set_channels)(AAudioStreamBuilder*, int32_t);
    void (*set_format)(AAudioStreamBuilder*, int32_t);
    void (*set_perf)(AAudioStreamBuilder*, int32_t);
    void (*set_sharing)(AAudioStreamBuilder*, int32_t);
    void (*set_usage)(AAudioStreamBuilder*, int32_t);
    int32_t (*open)(AAudioStreamBuilder*, AAudioStream**);
    int32_t (*delete_builder)(AAudioStreamBuilder*);
    int32_t (*start)(AAudioStream*);
    int32_t (*close)(AAudioStream*);
    int32_t (*write)(AAudioStream*, const void*, int32_t, int64_t);
    int64_t (*frames_written)(AAudioStream*);
    int64_t (*frames_read)(AAudioStream*);
    int32_t (*set_buffer)(AAudioStream*, int32_t);
    int32_t (*get_buffer)(AAudioStream*);
    int32_t (*get_capacity)(AAudioStream*);
    int32_t (*get_rate)(AAudioStream*);
    int32_t (*get_state)(AAudioStream*);
    const char* (*text)(int32_t);
} s_aa;
static AAudioStream* s_aa_stream = NULL;
static int32_t s_aa_buf = 0;
static uint64_t s_aa_retry_ns = 0;
static unsigned long long s_aa_short_writes = 0, s_aa_reopens = 0;

static uint64_t aa_now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static int aa_load(void)
{
    if (s_aa.loaded) return s_aa.loaded > 0;
    void* lib = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
    s_aa.loaded = -1;
    if (!lib) { fprintf(stderr, "[cellAudio] AAudio not available: %s\\n", dlerror()); return 0; }
#define EU_AA_SYM(field, name) *(void**)&s_aa.field = dlsym(lib, name)
    EU_AA_SYM(create_builder, "AAudio_createStreamBuilder");
    EU_AA_SYM(set_direction, "AAudioStreamBuilder_setDirection");
    EU_AA_SYM(set_rate, "AAudioStreamBuilder_setSampleRate");
    EU_AA_SYM(set_channels, "AAudioStreamBuilder_setChannelCount");
    EU_AA_SYM(set_format, "AAudioStreamBuilder_setFormat");
    EU_AA_SYM(set_perf, "AAudioStreamBuilder_setPerformanceMode");
    EU_AA_SYM(set_sharing, "AAudioStreamBuilder_setSharingMode");
    EU_AA_SYM(set_usage, "AAudioStreamBuilder_setUsage");   /* Android 9+; optional */
    EU_AA_SYM(open, "AAudioStreamBuilder_openStream");
    EU_AA_SYM(delete_builder, "AAudioStreamBuilder_delete");
    EU_AA_SYM(start, "AAudioStream_requestStart");
    EU_AA_SYM(close, "AAudioStream_close");
    EU_AA_SYM(write, "AAudioStream_write");
    EU_AA_SYM(frames_written, "AAudioStream_getFramesWritten");
    EU_AA_SYM(frames_read, "AAudioStream_getFramesRead");
    EU_AA_SYM(set_buffer, "AAudioStream_setBufferSizeInFrames");
    EU_AA_SYM(get_buffer, "AAudioStream_getBufferSizeInFrames");
    EU_AA_SYM(get_capacity, "AAudioStream_getBufferCapacityInFrames");
    EU_AA_SYM(get_rate, "AAudioStream_getSampleRate");
    EU_AA_SYM(get_state, "AAudioStream_getState");
    EU_AA_SYM(text, "AAudio_convertResultToText");
#undef EU_AA_SYM
    if (!s_aa.create_builder || !s_aa.set_direction || !s_aa.set_rate || !s_aa.set_channels || !s_aa.set_format ||
        !s_aa.set_perf || !s_aa.set_sharing || !s_aa.open || !s_aa.delete_builder || !s_aa.start || !s_aa.close ||
        !s_aa.write || !s_aa.frames_written || !s_aa.frames_read || !s_aa.set_buffer || !s_aa.get_buffer ||
        !s_aa.get_capacity || !s_aa.get_rate || !s_aa.get_state) {
        fprintf(stderr, "[cellAudio] AAudio: missing entry points\\n");
        return 0;
    }
    s_aa.loaded = 1;
    return 1;
}

static const char* aa_text(int32_t r) { return s_aa.text ? s_aa.text(r) : "?"; }

static int aa_open(void)
{
    AAudioStreamBuilder* b = NULL;
    int32_t r = s_aa.create_builder(&b);
    if (r != 0 || !b) { fprintf(stderr, "[cellAudio] AAudio builder failed: %s\\n", aa_text(r)); return -1; }
    s_aa.set_direction(b, EU_AA_DIR_OUTPUT);
    s_aa.set_rate(b, CELL_AUDIO_SAMPLE_RATE);
    s_aa.set_channels(b, 2);
    s_aa.set_format(b, EU_AA_FMT_FLOAT);
    s_aa.set_sharing(b, EU_AA_SHARED);
    s_aa.set_perf(b, EU_AA_PERF_LOW_LATENCY);
    if (s_aa.set_usage) s_aa.set_usage(b, EU_AA_USAGE_GAME);
    AAudioStream* st = NULL;
    r = s_aa.open(b, &st);
    s_aa.delete_builder(b);
    if (r != 0 || !st) { fprintf(stderr, "[cellAudio] AAudio open failed: %s\\n", aa_text(r)); return -1; }
    /* The mixer keeps the device fed a block (256 frames) at a time and its own FIFO absorbs the
     * game's stalls; a device buffer of 8 blocks (43 ms) rides out a mixer thread held off the CPU. */
    const int32_t cap = s_aa.get_capacity(st);
    int32_t want = CELL_AUDIO_BLOCK_SAMPLES * 8;
    if (cap > 0 && want > cap) want = cap;
    s_aa.set_buffer(st, want);
    s_aa_buf = s_aa.get_buffer(st);
    r = s_aa.start(st);
    if (r != 0) { fprintf(stderr, "[cellAudio] AAudio start failed: %s\\n", aa_text(r)); s_aa.close(st); return -1; }
    s_aa_stream = st;
    fprintf(stderr, "[cellAudio] AAudio output open: %d Hz stereo float, buffer %d frames (capacity %d)\\n",
            (int)s_aa.get_rate(st), (int)s_aa_buf, (int)cap);
    return 0;
}

static int audio_backend_init(void)
{
    if (!aa_load()) return -1;
    return aa_open();
}

static void audio_backend_shutdown(void)
{
    if (s_aa_stream) { s_aa.close(s_aa_stream); s_aa_stream = NULL; }
}

/* Stream gone (output device changed): close it and try again, at most once a second. */
static void aa_check(void)
{
    if (s_aa_stream && s_aa.get_state(s_aa_stream) == EU_AA_STATE_DISCONNECTED) {
        s_aa.close(s_aa_stream);
        s_aa_stream = NULL;
        fprintf(stderr, "[cellAudio] AAudio stream disconnected; reopening\\n");
    }
    if (!s_aa_stream && s_aa.loaded > 0) {
        const uint64_t now = aa_now_ns();
        if (now >= s_aa_retry_ns) {
            s_aa_retry_ns = now + 1000000000ull;
            if (aa_open() == 0) s_aa_reopens++;
        }
    }
}

static int audio_backend_queued(void)
{
    if (!s_aa_stream) return -1;
    const int64_t q = s_aa.frames_written(s_aa_stream) - s_aa.frames_read(s_aa_stream);
    return q < 0 ? 0 : (int)q;
}

static u32 audio_backend_queued_samples(void)
{
    const int q = audio_backend_queued();
    return q > 0 ? (u32)q : 0u;
}

static int audio_backend_room(void)
{
    aa_check();
    if (!s_aa_stream) return -1;
    const int q = audio_backend_queued();
    const int room = (int)s_aa_buf - (q > 0 ? q : 0);
    return room > 0 ? room : 0;
}

static void audio_backend_submit(const float* stereo_samples, u32 num_samples)
{
    if (!s_aa_stream) return;
    const int32_t r = s_aa.write(s_aa_stream, stereo_samples, (int32_t)num_samples, 0);
    if (r == EU_AA_ERR_DISCONNECTED) { aa_check(); return; }
    if (r >= 0 && (u32)r < num_samples) s_aa_short_writes++;
}

__attribute__((unused)) static void audio_backend_wait(void) { usleep(1000); }
#endif /* AUDIO_BACKEND_AAUDIO */
""")
# FIFO-thread timers (measurement only, no behaviour change). g_eu_prof[] in
# nanoseconds: [0] FIFO walk, [1] draws (sink_end), [2] vertex fetch,
# [3] texture binding, [4] pipeline lookup, [5] backend draw call,
# [6] frame submit (eu_vk_backend.cpp), [8] draws, [9] walks. The Vulkan
# backend prints and resets them with its 5 s report ("[fifo-prof]").
change("libs/video/rsx_draw_engine.c",
"""/* ---- render state -------------------------------------------------------- */
""",
"""#if defined(__ANDROID__)
#include <time.h>
unsigned long long g_eu_prof[16];
/* GPU vertex fetch ("pulling"): instead of decoding every vertex on the FIFO thread, the draw hands the
 * backend the raw guest bytes of each attribute and one (vertex_id, base_index) pair per vertex; the vertex
 * shader reads and converts the attributes itself. Same layout in eu_vk_backend.cpp. */
typedef struct eu_pull_attr {
    const unsigned char* src;   /* host pointer of element `first` */
    unsigned int bytes, location, stride, type, size, mod, freq, first, last, valid;
    float def[4];
} eu_pull_attr;
typedef struct eu_pull_draw {
    unsigned int active, attr_bytes;
    eu_pull_attr attr[16];
    /* self-check (EU_VK_PULL_CHECK): the CPU decoder's result for the same draw */
    const unsigned char* check_verts;
    unsigned int check_stride, nslots;
    unsigned char slot_attr[16];
} eu_pull_draw;
eu_pull_draw g_eu_pull = { 0, (unsigned int)sizeof(eu_pull_attr) };
static inline unsigned long long eu_prof_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull + (unsigned long long)ts.tv_nsec;
}
#endif
/* ---- render state -------------------------------------------------------- */
""")
change("libs/video/rsx_draw_engine.c",
"""static void sink_end(void* user, const rsx_dispatch* r)
{
    EU_DROP(0);""",
"""#if defined(__ANDROID__)
static void eu_sink_end_inner(void* user, const rsx_dispatch* r);
static void sink_end(void* user, const rsx_dispatch* r)
{
    const unsigned long long t0 = eu_prof_ns();
    g_eu_pull.active = 0;
    eu_sink_end_inner(user, r);
    g_eu_prof[1] += eu_prof_ns() - t0;
    g_eu_prof[8]++;
}
static void eu_sink_end_inner(void* user, const rsx_dispatch* r)
#else
static void sink_end(void* user, const rsx_dispatch* r)
#endif
{
    EU_DROP(0);""")
change("libs/video/rsx_draw_engine.c",
"""    dc_fetch(&layout, rebuild);
    if (!dc.n_verts || !dc.fetch_ok) { EU_DROP(3); return; }""",
"""    { const unsigned long long eu_t = eu_prof_ns(); dc_fetch(&layout, rebuild); g_eu_prof[2] += eu_prof_ns() - eu_t; }
    if (!dc.n_verts || !dc.fetch_ok) { EU_DROP(3); return; }""")
change("libs/video/rsx_draw_engine.c",
"""    const u32 tex_mask = sink_bind_textures(targets, n_targets, zslot,
                                            textures, samplers);
    const u32 vtex_mask = sink_bind_vertex_textures(eng_vtex_mask(), vtextures,
                                                    vsamplers);""",
"""    const unsigned long long eu_tt = eu_prof_ns();
    const u32 tex_mask = sink_bind_textures(targets, n_targets, zslot,
                                            textures, samplers);
    const u32 vtex_mask = sink_bind_vertex_textures(eng_vtex_mask(), vtextures,
                                                    vsamplers);
    g_eu_prof[3] += eu_prof_ns() - eu_tt;""")
change("libs/video/rsx_draw_engine.c",
"""    int pipeline_is_fixed = 1;
    const u32 pipeline = eng_pipeline_get(&layout, &rs,
                                          eng_surface_format(sf.color_format),
                                          n_targets, &pipeline_is_fixed);""",
"""    int pipeline_is_fixed = 1;
    const unsigned long long eu_tp = eu_prof_ns();
    const u32 pipeline = eng_pipeline_get(&layout, &rs,
                                          eng_surface_format(sf.color_format),
                                          n_targets, &pipeline_is_fixed);
    g_eu_prof[4] += eu_prof_ns() - eu_tp;""")
change("libs/video/rsx_draw_engine.c",
"""    g.be->draw(g.be->user, topology, dc.verts, uploaded, layout.stride,
               indexed ? g.indices : NULL, indexed ? n_draw : 0);""",
"""    { const unsigned long long eu_td = eu_prof_ns();
      g.be->draw(g.be->user, topology, dc.verts, uploaded, g_eu_pull.active ? 8u : layout.stride,
                 indexed ? g.indices : NULL, indexed ? n_draw : 0);
      g_eu_prof[5] += eu_prof_ns() - eu_td; }""")
change("libs/video/cellGcmSys.c",
"""    AcquireSRWLockExclusive(&s_gcm_fifo_lock);
    gcm_rsx_process_fifo_unlocked();""",
"""    AcquireSRWLockExclusive(&s_gcm_fifo_lock);
#if defined(__ANDROID__)
    { extern unsigned long long g_eu_prof[16];
      struct timespec a, b; clock_gettime(CLOCK_MONOTONIC, &a);
      gcm_rsx_process_fifo_unlocked();
      clock_gettime(CLOCK_MONOTONIC, &b);
      g_eu_prof[0] += (unsigned long long)((long long)(b.tv_sec - a.tv_sec) * 1000000000ll + (b.tv_nsec - a.tv_nsec));
      g_eu_prof[9]++; }
#else
    gcm_rsx_process_fifo_unlocked();
#endif""")
# GPU vertex fetch, engine side (see the eu_pull_draw note above): a draw whose
# attributes each have one readable range and a supported format skips the
# per-vertex decode loop and passes refs + raw ranges; anything else (inline
# streams, unreadable ranges, unknown formats) keeps the CPU loop. The pipeline
# key includes the mode. Off when the backend says so (EU_VK_PULL=0).
change("libs/video/rsx_draw_engine.c",
"""    for (u32 i = 0; i < dc.n_refs; i++) {
        u8* dst = layout->stride ? dc.verts + (u64)i * layout->stride : NULL;
        if (!rsx_vertex_fetch_one(&dc.fetch_plan, &dc.refs[i], dst)) {""",
"""#if defined(__ANDROID__)
    g_eu_pull.active = 0;
    if (eu_pull_try(&dc.fetch_plan, dc.refs, dc.n_refs)) { dc.n_verts = dc.n_refs; return; }
#endif
    for (u32 i = 0; i < dc.n_refs; i++) {
        u8* dst = layout->stride ? dc.verts + (u64)i * layout->stride : NULL;
        if (!rsx_vertex_fetch_one(&dc.fetch_plan, &dc.refs[i], dst)) {""")
change("libs/video/rsx_draw_engine.c",
"""static void dc_fetch(const rsx_vertex_layout_plan* layout, int allow_remap)
{""",
"""#if defined(__ANDROID__)
extern int eu_vk_pull_ok(void) __attribute__((weak));
static int eu_pull_try(const rsx_vertex_fetch_plan* plan, const rsx_vertex_ref* refs, u32 n)
{
    if (!eu_vk_pull_ok || !eu_vk_pull_ok() || !n || !plan->layout.count || plan->inline_data) return 0;
    eu_pull_draw* d = &g_eu_pull;
    memset(d->attr, 0, sizeof d->attr);
    for (u32 slot = 0; slot < plan->layout.count; slot++) {
        const u32 a = plan->layout.attrs[slot];
        const rsx_vertex_fetch_attr* f = &plan->attr[a];
        eu_pull_attr* o = &d->attr[a];
        memcpy(o->def, f->default_value, 16);
        if (!f->desc.type || !f->desc.size) continue;          /* the attribute's default */
        switch (f->desc.type) {
        case RSX_VTX_TYPE_FLOAT: case RSX_VTX_TYPE_HALF: case RSX_VTX_TYPE_UNORM8: case RSX_VTX_TYPE_UINT8:
        case RSX_VTX_TYPE_SNORM16: case RSX_VTX_TYPE_SINT16: case RSX_VTX_TYPE_CMP32: break;
        default: return 0;
        }
        if (!f->stable_base || f->stable_last < f->stable_first || !f->elem_size) return 0;
        const u64 bytes = (u64)(f->stable_last - f->stable_first) * f->stride + f->elem_size;
        if (bytes > (64u << 20)) return 0;
        o->src = f->stable_base;
        o->bytes = (unsigned int)bytes;
        o->location = f->desc.location;
        o->stride = f->stride;
        o->type = f->desc.type;
        o->size = f->desc.size < 4 ? f->desc.size : 4;
        o->mod = (plan->divider_mask >> a) & 1u;
        o->freq = f->desc.frequency;
        o->first = f->stable_first;
        o->last = f->stable_last;
        o->valid = 1;
    }
    d->nslots = plan->layout.count;
    for (u32 slot = 0; slot < plan->layout.count && slot < 16; slot++) d->slot_attr[slot] = plan->layout.attrs[slot];
    d->check_verts = NULL;
    {   /* EU_VK_PULL_CHECK (default 1 while GPU fetch is on): the first 400 pulled draws are also decoded on
         * the CPU; the backend compares that with what the GPU will read and logs any difference. */
        static int chk = -1;
        static unsigned char* cv = NULL;
        static u64 cv_cap = 0;
        static unsigned n_chk = 0;
        if (chk < 0) { const char* e = getenv("EU_VK_PULL_CHECK"); chk = (e && *e == '0') ? 0 : 1; }
        const u64 need = (u64)n * plan->layout.stride;
        if (chk && n_chk < 400 && need && need <= (16u << 20)) {
            if (need > cv_cap) { unsigned char* c = (unsigned char*)realloc(cv, (size_t)need); if (c) { cv = c; cv_cap = need; } }
            if (need <= cv_cap) {
                int ok = 1;
                for (u32 i = 0; i < n && ok; i++)
                    ok = rsx_vertex_fetch_one(plan, &refs[i], cv + (u64)i * plan->layout.stride);
                if (ok) { d->check_verts = cv; d->check_stride = plan->layout.stride; n_chk++; }
            }
        }
    }
    if (!dc_reserve_verts((u64)n * 8u)) return 0;
    memcpy(dc.verts, refs, (size_t)n * 8u);   /* rsx_vertex_ref: { vertex_id, base_index } */
    d->active = 1;
    return 1;
}
#endif
static void dc_fetch(const rsx_vertex_layout_plan* layout, int allow_remap)
{""")
change("libs/video/rsx_draw_engine.c",
"""    key = eng_fnv1a(&layout->stride, sizeof layout->stride, key);""",
"""    key = eng_fnv1a(&layout->stride, sizeof layout->stride, key);
#if defined(__ANDROID__)
    { const unsigned int eu_pk = g_eu_pull.active; key = eng_fnv1a(&eu_pk, sizeof eu_pk, key); }
#endif""")
# Audio threads on Android: the game's audio is mixed by the single-SPU SPURS
# instance (inst 1) and played out by the cellAudio mixer thread. On Windows
# both get raised priority / P-cores; on Android they ran at default priority
# wherever the scheduler put them, and under gameplay load the guest audio
# fell behind (hundreds of late blocks per 5 s = crackle). Raise their priority
# and keep them on the faster cores. eu_thread_boost() logs what took effect.
change("libs/audio/cellAudio.c",
"""#include <time.h>    /* v145 */
""",
"""#include <time.h>    /* v145 */
#if defined(__ANDROID__)
#include <errno.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
/* Raise this thread's priority (nice value, lower = more CPU) and keep it off the slowest core cluster. */
void eu_thread_boost(const char* what, int nice_val)
{
    const pid_t tid = (pid_t)syscall(SYS_gettid);
    long ncpu = sysconf(_SC_NPROCESSORS_CONF);
    if (ncpu > 32) ncpu = 32;
    unsigned long fmax[32] = {0}, lo = ~0ul;
    for (long c = 0; c < ncpu; c++) {
        char path[96];
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", c);
        FILE* f = fopen(path, "r");
        if (!f) continue;
        if (fscanf(f, "%lu", &fmax[c]) != 1) fmax[c] = 0;
        fclose(f);
        if (fmax[c] && fmax[c] < lo) lo = fmax[c];
    }
    cpu_set_t set;
    CPU_ZERO(&set);
    int nfast = 0;
    for (long c = 0; c < ncpu; c++) if (fmax[c] && fmax[c] > lo) { CPU_SET((int)c, &set); nfast++; }
    const int aff = nfast ? sched_setaffinity(tid, sizeof set, &set) : -1;
    const int aff_err = aff ? errno : 0;
    const int pr = setpriority(PRIO_PROCESS, (id_t)tid, nice_val);
    const int pr_err = pr ? errno : 0;
    fprintf(stderr, "[boost] %s (tid %d): priority %d %s, %s (%d faster cores)\\n", what, (int)tid, nice_val,
            pr == 0 ? "set" : strerror(pr_err), aff == 0 ? "kept on the faster cores" : (nfast ? strerror(aff_err) : "no core info"),
            nfast);
}
#endif
""")
change("libs/audio/cellAudio.c",
"""    { const char* e = getenv("AUDIO_PREPAD"); if (e) prime = atoi(e); }""",
"""#if defined(__ANDROID__)
    { const char* e = getenv("EU_AUDIO_BOOST"); if (!(e && *e == '0')) eu_thread_boost("audio mixer", -16); }
#endif
    { const char* e = getenv("AUDIO_PREPAD"); if (e) prime = atoi(e); }""")
change("libs/spurs/cellSpurs.c",
"""      prctl(15 /* PR_SET_NAME */, nm, 0, 0, 0); }
#endif""",
"""      prctl(15 /* PR_SET_NAME */, nm, 0, 0, 0); }
    if ((int)(si - s_inst) == 1 && k_part == 0) {   /* inFamous' audio SPU (w3) */
        extern void eu_thread_boost(const char*, int);
        const char* e = getenv("EU_AUDIO_BOOST");
        if (!(e && *e == '0')) eu_thread_boost("audio SPU kernel (inst 1)", -10);
    }
#endif""")
print("Android patches applied:", len(changes), "edit anchors in", len(set(changes)), "real upstream files")
