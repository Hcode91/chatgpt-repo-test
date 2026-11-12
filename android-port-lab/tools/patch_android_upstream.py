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
for _h in ("spu_helpers_vec_arm64.h", "spu_fsimd_arm64.h", "spu_fext_arm64.h"):
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
# Scheduler switches of the SPURS runtime, changeable while running: the in-run comparison
# (eu_android_bridge.c, g_eu_feat_flip / g_eu_lean_flip) inverts one V242A_* switch by name or
# some lean-mode component bits for a stretch. Nothing changes while both are zero.
change("libs/spurs/cellSpurs.c",
"""    return on && (f->phase < 0 || (f->phase < 2 ? ((g_ab_phase & 1) == f->phase) : (((g_ab_phase >> 1) & 1) == (f->phase & 1))));
}""",
"""    int eu_r = on && (f->phase < 0 || (f->phase < 2 ? ((g_ab_phase & 1) == f->phase) : (((g_ab_phase >> 1) & 1) == (f->phase & 1))));
#if defined(__ANDROID__)
    { extern const char* volatile g_eu_feat_flip __attribute__((weak));
      const char* eu_fl = &g_eu_feat_flip ? g_eu_feat_flip : 0;
      if (__builtin_expect(eu_fl != 0, 0) && !strcmp(eu_fl, f->name)) eu_r = !eu_r; }
#endif
    return eu_r;
}""")
change("libs/spurs/cellSpurs.c",
"""            if (v242a_ncpu_cached() >= s_ak && v242a_feat(&s_v242a_audkeep, 1)) return 0;
        }
        return (s_lb & bit) != 0;""",
"""            if (v242a_ncpu_cached() >= s_ak && v242a_feat(&s_v242a_audkeep, 1)) return 0;
        }
#if defined(__ANDROID__)
        { extern volatile int g_eu_lean_flip __attribute__((weak));
          if (&g_eu_lean_flip) return ((s_lb ^ g_eu_lean_flip) & bit) != 0; }
#endif
        return (s_lb & bit) != 0;""")

# The remaining single-precision helpers (fi, the float compares and the
# int<->float conversions) as packed operations on ARM64. spu_fi alone called
# ldexpf() eight times per instruction and sits in every division the job code
# does. Appended to spu_helpers.h so the references stay defined above it.
change("runtime/spu/spu_helpers.h",
"""#ifdef __cplusplus
}
#endif

#endif /* SPU_HELPERS_H */""",
"""#include "spu_fext_arm64.h"

#ifdef __cplusplus
}
#endif

#endif /* SPU_HELPERS_H */""")
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
int g_eu_prof_on;   /* per-draw timers run on every 16th draw; the report scales them up */
static inline unsigned long long eu_prof_ns(void)
{
    if (!g_eu_prof_on) return 0;
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
    { static unsigned eu_n; g_eu_prof_on = ((eu_n++ & 15u) == 0); }
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
      /* The FIFO thread is what the game waits on: give it the fastest core (EU_FIFO_PLACE=0 = leave it). */
      static __thread int eu_placed;
      if (!eu_placed) {
          extern void eu_thread_place(const char*, int, int);
          const char* e = getenv("EU_FIFO_PLACE");
          eu_placed = 1;
          if (!(e && *e == '0')) eu_thread_place("RSX FIFO thread", -8, 1);
      }
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
    { const unsigned long long eu_t = eu_prof_ns();
      const int eu_p = eu_pull_try(&dc.fetch_plan, dc.refs, dc.n_refs);
      g_eu_prof[11] += eu_prof_ns() - eu_t;
      if (eu_p) { dc.n_verts = dc.n_refs; return; } }
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
/* Raise this thread's priority (nice value, lower = more CPU) and choose its cores.
 * mode 0: any core faster than the slowest cluster; 1: the fastest core(s) only; 2: faster cores except the
 * fastest (so whatever is given mode 1 has that core to itself). */
void eu_thread_place(const char* what, int nice_val, int mode);
/* threads given the fastest core (mode 1); the bridge keeps every other thread off that core */
int g_eu_prime_tid[4];
int g_eu_prime_n;
void eu_thread_boost(const char* what, int nice_val) { eu_thread_place(what, nice_val, 0); }
void eu_thread_place(const char* what, int nice_val, int mode)
{
    const pid_t tid = (pid_t)syscall(SYS_gettid);
    long ncpu = sysconf(_SC_NPROCESSORS_CONF);
    if (ncpu > 32) ncpu = 32;
    unsigned long fmax[32] = {0}, lo = ~0ul, top = 0;
    for (long c = 0; c < ncpu; c++) {
        char path[96];
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", c);
        FILE* f = fopen(path, "r");
        if (!f) continue;
        if (fscanf(f, "%lu", &fmax[c]) != 1) fmax[c] = 0;
        fclose(f);
        if (fmax[c] && fmax[c] < lo) lo = fmax[c];
        if (fmax[c] > top) top = fmax[c];
    }
    cpu_set_t set;
    CPU_ZERO(&set);
    int nfast = 0;
    for (long c = 0; c < ncpu; c++) {
        if (!fmax[c] || fmax[c] <= lo) continue;
        if (mode == 1 && fmax[c] != top) continue;
        if (mode == 2 && fmax[c] == top) continue;
        CPU_SET((int)c, &set);
        nfast++;
    }
    const int aff = nfast ? sched_setaffinity(tid, sizeof set, &set) : -1;
    const int aff_err = aff ? errno : 0;
    if (mode == 1 && aff == 0 && g_eu_prime_n < 4) g_eu_prime_tid[g_eu_prime_n++] = (int)tid;
    const int pr = setpriority(PRIO_PROCESS, (id_t)tid, nice_val);
    const int pr_err = pr ? errno : 0;
    fprintf(stderr, "[boost] %s (tid %d): priority %d %s, %s (%d core(s), placement %d)\\n", what, (int)tid, nice_val,
            pr == 0 ? "set" : strerror(pr_err), aff == 0 ? "cores set" : (nfast ? strerror(aff_err) : "no core info"),
            nfast, mode);
}
#endif
""")
change("libs/audio/cellAudio.c",
"""    { const char* e = getenv("AUDIO_PREPAD"); if (e) prime = atoi(e); }""",
"""#if defined(__ANDROID__)
    { const char* e = getenv("EU_AUDIO_BOOST"); if (!(e && *e == '0')) eu_thread_place("audio mixer", -16, 2); }
#endif
    { const char* e = getenv("AUDIO_PREPAD"); if (e) prime = atoi(e); }""")
change("libs/spurs/cellSpurs.c",
"""      prctl(15 /* PR_SET_NAME */, nm, 0, 0, 0); }
#endif""",
"""      prctl(15 /* PR_SET_NAME */, nm, 0, 0, 0); }
    if ((int)(si - s_inst) == 1 && k_part == 0) {   /* inFamous' audio SPU (w3) */
        extern void eu_thread_place(const char*, int, int);
        const char* e = getenv("EU_AUDIO_BOOST");
        if (!(e && *e == '0')) eu_thread_place("audio SPU kernel (inst 1)", -10, 2);
    }
#endif""")
# Indexed draws on the GPU-fetch path: the generic duplicate-vertex pass hashes
# every index. When the indices cover a dense range with one base index, map
# them directly (index - lowest) and make the vertex list that range.
change("libs/video/rsx_draw_engine.c",
"""    if (allow_remap && dc.n_refs > 1) {
        u32 unique = dc.n_refs;""",
"""#if defined(__ANDROID__)
    int eu_direct = 0;
    if (eu_fast) dc.n_source_refs = eu_fast_count;
    if (!eu_fast && allow_remap && dc.n_refs > 1 && !dc.inl && eu_vk_pull_ok && eu_vk_pull_ok()) {
        const unsigned long long eu_t = eu_prof_ns();
        eu_direct = eu_remap_direct();
        g_eu_prof[10] += eu_prof_ns() - eu_t;
    }
    if (!eu_direct && !eu_fast)
#endif
    if (allow_remap && dc.n_refs > 1) {
        u32 unique = dc.n_refs;""")
change("libs/video/rsx_draw_engine.c",
"""#if defined(__ANDROID__)
extern int eu_vk_pull_ok(void) __attribute__((weak));""",
"""#if defined(__ANDROID__)
static int eu_remap_direct(void)
{
    const u32 n = dc.n_refs;
    const u32 base = dc.refs[0].base_index;
    u32 lo = dc.refs[0].vertex_id, hi = lo;
    int in_order = 1;
    for (u32 i = 1; i < n; i++) {
        const u32 v = dc.refs[i].vertex_id;
        if (dc.refs[i].base_index != base) return 0;
        if (v < lo) lo = v;
        if (v > hi) hi = v;
        if (v != dc.refs[0].vertex_id + i) in_order = 0;
    }
    if (in_order) { dc.refs_remapped = 0; return 1; }      /* already one reference per vertex, in order */
    const u64 span = (u64)hi - lo + 1u;
    if (span > n) return 0;                                /* sparse: the generic pass shades fewer vertices */
    if (n > dc.ref_remap.occurrence_capacity) {
        u32* occ = (u32*)realloc(dc.ref_remap.occurrence_to_unique, (size_t)n * sizeof(u32));
        if (!occ) return 0;
        dc.ref_remap.occurrence_to_unique = occ;
        dc.ref_remap.occurrence_capacity = n;
    }
    for (u32 i = 0; i < n; i++) dc.ref_remap.occurrence_to_unique[i] = dc.refs[i].vertex_id - lo;
    for (u32 k = 0; k < (u32)span; k++) { dc.refs[k].vertex_id = lo + k; dc.refs[k].base_index = base; }
    dc.refs_remapped = 1;
    dc.n_refs = (u32)span;
    return 1;
}
extern int eu_vk_pull_ok(void) __attribute__((weak));""")
# Per-draw lookup shortcuts (EU_SHORTCUT=1; default off). The log showed 98% of
# draws repeat the previous draw's program and ~85% its textures, while the
# engine re-derives both from scratch every draw. Each shortcut remembers only
# the immediately previous draw: if every input of the lookup is unchanged, the
# previous answer is reused. EU_SHORTCUT_CHECK (default on): the first 20000
# reuses of each kind are also computed the full way and compared; a difference
# is logged as "[shortcut] MISMATCH" and the full result is used.
change("libs/video/rsx_draw_engine.c",
"""static u32 eng_pipeline_get(const rsx_vertex_layout_plan* layout,
                            const rsx_be_render_state* rs,
                            rsx_be_format rt_fmt, u32 rt_count, int* out_fixed)
{
    *out_fixed = 1;""",
"""#if defined(__ANDROID__)
static int eu_shortcut_on(void)
{
    static int on = -1;
    if (on < 0) {
        const char* e = getenv("EU_SHORTCUT");
        on = (e && *e == '0') ? 0 : 1;   /* default on since boot-20261110-224451: 20000 checked reuses, 0 mismatches */
        fprintf(stderr, "[shortcut] per-draw lookup shortcuts: %s (EU_SHORTCUT=0 turns them off)\\n", on ? "ON" : "off");
    }
    return on;
}
static int eu_shortcut_check(unsigned long long n)
{
    static int chk = -1;
    if (chk < 0) { const char* e = getenv("EU_SHORTCUT_CHECK"); chk = (e && *e == '0') ? 0 : 1; }
    return chk && n < 20000ull;
}
static unsigned long long s_sc_pipe_hit, s_sc_pipe_miss, s_sc_pipe_bad, s_sc_tex_hit, s_sc_tex_miss, s_sc_tex_bad,
                          s_sc_tex_notplain;
static void eu_shortcut_report(void)
{
    static struct timespec t0;
    struct timespec t;
    if (!g_eu_prof_on) return;
    clock_gettime(CLOCK_MONOTONIC, &t);
    if (!t0.tv_sec) { t0 = t; return; }
    if (t.tv_sec - t0.tv_sec < 10) return;
    t0 = t;
    fprintf(stderr, "[shortcut] program lookups reused %llu, done in full %llu, MISMATCHES %llu | texture set-ups reused %llu, "
            "done in full %llu (%llu of those sample a render target or depth), MISMATCHES %llu (totals)\\n",
            s_sc_pipe_hit, s_sc_pipe_miss, s_sc_pipe_bad, s_sc_tex_hit, s_sc_tex_miss, s_sc_tex_notplain, s_sc_tex_bad);
}
static u32 eng_pipeline_get_full(const rsx_vertex_layout_plan* layout, const rsx_be_render_state* rs,
                                 rsx_be_format rt_fmt, u32 rt_count, int* out_fixed);
static u32 eng_pipeline_get(const rsx_vertex_layout_plan* layout, const rsx_be_render_state* rs,
                            rsx_be_format rt_fmt, u32 rt_count, int* out_fixed)
{
    if (!eu_shortcut_on()) return eng_pipeline_get_full(layout, rs, rt_fmt, rt_count, out_fixed);
    /* the previous draw's lookup: every input, and the answer */
    static struct {
        int valid, fixed;
        u8* vp; u32 vp_bytes, vp_cap;
        u8* fp; u32 fp_bytes, fp_cap;
        u32 fp_ctrl_key, cube_mask, vtex_mask, layout_mask, layout_stride, pull, rt_count, handle;
        rsx_be_format rt_fmt;
        u64 rs_hash;
    } L;
    eu_shortcut_report();
    const u8* vp_uc = NULL;
    const u8* fp_uc = NULL;
    u32 vp_instrs = 0, fp_size = 0;
    const int guest = eng_guest_programs(&vp_uc, &vp_instrs, &fp_uc, &fp_size);
    const u32 vp_bytes = vp_instrs * 16u;
    const u32 fp_ctrl_key = rsx_dsp_shader_control(&g.rsx) & 0x40u;
    const u32 cube_mask = eng_cube_mask(), vtex_mask = eng_vtex_mask();
    const u64 rs_hash = rsx_draw_engine_hash_render_state(rs, 1469598103934665603ull);
    const int hit = guest && L.valid && L.handle && vp_uc && fp_uc && vp_bytes && fp_size &&
        L.vp_bytes == vp_bytes && L.fp_bytes == fp_size && L.fp_ctrl_key == fp_ctrl_key &&
        L.cube_mask == cube_mask && L.vtex_mask == vtex_mask && L.layout_mask == layout->mask &&
        L.layout_stride == layout->stride && L.pull == g_eu_pull.active && L.rt_fmt == rt_fmt &&
        L.rt_count == rt_count && L.rs_hash == rs_hash &&
        memcmp(L.vp, vp_uc, vp_bytes) == 0 && memcmp(L.fp, fp_uc, fp_size) == 0;
    if (hit && !eu_shortcut_check(s_sc_pipe_hit)) {
        /* identical fragment program bytes: g.fp_constants from the previous draw still holds its constants */
        s_sc_pipe_hit++;
        *out_fixed = L.fixed;
        return L.handle;
    }
    static rsx_fp_constant_block before;
    if (hit) before = g.fp_constants;
    const u32 handle = eng_pipeline_get_full(layout, rs, rt_fmt, rt_count, out_fixed);
    if (hit) {
        s_sc_pipe_hit++;
        if (handle != L.handle || *out_fixed != L.fixed || before.count != g.fp_constants.count ||
            memcmp(before.values, g.fp_constants.values, (size_t)before.count * 16u) != 0) {
            if (s_sc_pipe_bad++ < 16)
                fprintf(stderr, "[shortcut] MISMATCH program: reused handle %u fixed %d constants %u, full lookup %u %d %u\\n",
                        L.handle, L.fixed, before.count, handle, *out_fixed, g.fp_constants.count);
        }
    } else {
        s_sc_pipe_miss++;
    }
    L.valid = 0;
    if (guest && handle && vp_uc && fp_uc && vp_bytes && fp_size) {
        if (L.vp_cap < vp_bytes) { u8* n = (u8*)realloc(L.vp, vp_bytes); if (!n) return handle; L.vp = n; L.vp_cap = vp_bytes; }
        if (L.fp_cap < fp_size) { u8* n = (u8*)realloc(L.fp, fp_size); if (!n) return handle; L.fp = n; L.fp_cap = fp_size; }
        memcpy(L.vp, vp_uc, vp_bytes);
        memcpy(L.fp, fp_uc, fp_size);
        L.vp_bytes = vp_bytes; L.fp_bytes = fp_size; L.fp_ctrl_key = fp_ctrl_key;
        L.cube_mask = cube_mask; L.vtex_mask = vtex_mask;
        L.layout_mask = layout->mask; L.layout_stride = layout->stride;
        L.pull = g_eu_pull.active; L.rt_fmt = rt_fmt; L.rt_count = rt_count; L.rs_hash = rs_hash;
        L.handle = handle; L.fixed = *out_fixed;
        L.valid = 1;
    }
    return handle;
}
static u32 eng_pipeline_get_full(const rsx_vertex_layout_plan* layout,
                            const rsx_be_render_state* rs,
                            rsx_be_format rt_fmt, u32 rt_count, int* out_fixed)
#else
static u32 eng_pipeline_get(const rsx_vertex_layout_plan* layout,
                            const rsx_be_render_state* rs,
                            rsx_be_format rt_fmt, u32 rt_count, int* out_fixed)
#endif
{
    *out_fixed = 1;""")
change("libs/video/rsx_draw_engine.c",
"""static u32 sink_bind_textures(const u32* target_slots, u32 n_targets,
                              u32 current_zslot,
                              u32 textures[RSX_BE_MAX_TEXTURES],
                              rsx_be_sampler_desc samplers[RSX_BE_MAX_TEXTURES])
{
    u32 mask = 0;""",
"""#if defined(__ANDROID__)
static u32 sink_bind_textures_full(const u32* target_slots, u32 n_targets, u32 current_zslot,
                                   u32 textures[RSX_BE_MAX_TEXTURES],
                                   rsx_be_sampler_desc samplers[RSX_BE_MAX_TEXTURES]);
/* Reuse of the previous draw's texture set-up. Only when every enabled unit read an ordinary texture (no render
 * target or depth buffer lives at any sampled address -- those paths take snapshots and copies that depend on
 * what was drawn in between), and the texture registers, the bound targets, the frame and the surface and depth
 * tables are all exactly as they were. */
static u32 sink_bind_textures(const u32* target_slots, u32 n_targets, u32 current_zslot,
                              u32 textures[RSX_BE_MAX_TEXTURES],
                              rsx_be_sampler_desc samplers[RSX_BE_MAX_TEXTURES])
{
    if (!eu_shortcut_on()) return sink_bind_textures_full(target_slots, n_targets, current_zslot, textures, samplers);
    static struct {
        int valid;
        rsx_dsp_texture t[RSX_BE_MAX_TEXTURES];
        u32 targets[RSX_BE_MAX_COLOR_TARGETS], n_targets, zslot, frame, mask;
        u64 world;
        u32 textures[RSX_BE_MAX_TEXTURES];
        rsx_be_sampler_desc samplers[RSX_BE_MAX_TEXTURES];
    } L;
    static rsx_dsp_texture cur[RSX_BE_MAX_TEXTURES];
    memset(cur, 0, sizeof cur);
    for (u32 u = 0; u < RSX_BE_MAX_TEXTURES; u++) rsx_dsp_get_texture(&g.rsx, u, &cur[u]);
    u64 world = 1469598103934665603ull ^ ((u64)g.n_surfaces << 32) ^ g.n_zdepths;
    for (u32 i = 0; i < g.n_surfaces; i++) {
        const eng_surface* c = &g.surfaces[i];
        world = (world ^ c->handle) * 1099511628211ull;
        world = (world ^ c->location) * 1099511628211ull;
        world = (world ^ c->offset) * 1099511628211ull;
        world = (world ^ (((u64)c->w << 32) | c->h)) * 1099511628211ull;
    }
    for (u32 i = 0; i < g.n_zdepths; i++) {
        world = (world ^ g.zdepths[i].location) * 1099511628211ull;
        world = (world ^ g.zdepths[i].offset) * 1099511628211ull;
    }
    int same_targets = L.n_targets == n_targets && n_targets <= RSX_BE_MAX_COLOR_TARGETS;
    for (u32 k = 0; same_targets && k < n_targets; k++) if (L.targets[k] != target_slots[k]) same_targets = 0;
    const int hit = L.valid && same_targets && L.zslot == current_zslot && L.frame == (u32)g.frames &&
                    L.world == world && memcmp(L.t, cur, sizeof cur) == 0;
    if (hit && !eu_shortcut_check(s_sc_tex_hit)) {
        s_sc_tex_hit++;
        memcpy(textures, L.textures, sizeof L.textures);
        memcpy(samplers, L.samplers, sizeof L.samplers);
        return L.mask;
    }
    const u32 mask = sink_bind_textures_full(target_slots, n_targets, current_zslot, textures, samplers);
    if (hit) {
        s_sc_tex_hit++;
        if (mask != L.mask || memcmp(textures, L.textures, sizeof L.textures) != 0 ||
            memcmp(samplers, L.samplers, sizeof L.samplers) != 0) {
            if (s_sc_tex_bad++ < 16) {
                u32 unit = 0;
                for (u32 u = 0; u < RSX_BE_MAX_TEXTURES; u++) if (textures[u] != L.textures[u]) { unit = u; break; }
                fprintf(stderr, "[shortcut] MISMATCH textures: mask reused %#x full %#x; first differing unit %u reused handle %u "
                        "full %u (sampler bytes %s)\\n", L.mask, mask, unit, L.textures[unit], textures[unit],
                        memcmp(samplers, L.samplers, sizeof L.samplers) ? "differ" : "same");
            }
        }
    } else {
        s_sc_tex_miss++;
    }
    /* remember this draw; usable next time only if it read ordinary textures alone */
    int plain = n_targets <= RSX_BE_MAX_COLOR_TARGETS;
    for (u32 u = 0; plain && u < RSX_BE_MAX_TEXTURES; u++) {
        if (!cur[u].enabled) continue;
        for (u32 i = 0; plain && i < g.n_surfaces; i++)
            if (g.surfaces[i].handle && g.surfaces[i].location == cur[u].location && g.surfaces[i].offset == cur[u].offset)
                plain = 0;
        for (u32 i = 0; plain && i < g.n_zdepths; i++)
            if (g.zdepths[i].location == cur[u].location && g.zdepths[i].offset == cur[u].offset) plain = 0;
    }
    if (!plain && !hit) s_sc_tex_notplain++;
    L.valid = plain;
    if (plain) {
        memcpy(L.t, cur, sizeof cur);
        for (u32 k = 0; k < n_targets; k++) L.targets[k] = target_slots[k];
        L.n_targets = n_targets; L.zslot = current_zslot; L.frame = (u32)g.frames; L.world = world; L.mask = mask;
        memcpy(L.textures, textures, sizeof L.textures);
        memcpy(L.samplers, samplers, sizeof L.samplers);
    }
    return mask;
}
static u32 sink_bind_textures_full(const u32* target_slots, u32 n_targets,
                              u32 current_zslot,
                              u32 textures[RSX_BE_MAX_TEXTURES],
                              rsx_be_sampler_desc samplers[RSX_BE_MAX_TEXTURES])
#else
static u32 sink_bind_textures(const u32* target_slots, u32 n_targets,
                              u32 current_zslot,
                              u32 textures[RSX_BE_MAX_TEXTURES],
                              rsx_be_sampler_desc samplers[RSX_BE_MAX_TEXTURES])
#endif
{
    u32 mask = 0;""")
# One indexed batch on the GPU-fetch path: read the indices once, straight into
# the index map, instead of one reference per index followed by three passes.
# Anything unusual (restart index hit, sparse indices, several batches, inline
# data) returns 0 before touching the draw and the generic code runs.
change("libs/video/rsx_draw_engine.c",
"""        for (u32 b = 0; b < dc.n_idx && dc.fetch_ok; b++) {
            const u32 first = dc.idx[b].first, count = dc.idx[b].count;""",
"""#if defined(__ANDROID__)
        { const unsigned long long eu_t = eu_prof_ns();
          eu_fast = eu_index_fast(&ia, esz, ia_loc, base_index, restart_en, restart_val, allow_remap, &eu_fast_count);
          g_eu_prof[10] += eu_prof_ns() - eu_t; }
        if (!eu_fast)
#endif
        for (u32 b = 0; b < dc.n_idx && dc.fetch_ok; b++) {
            const u32 first = dc.idx[b].first, count = dc.idx[b].count;""")
change("libs/video/rsx_draw_engine.c",
"""static void dc_fetch(const rsx_vertex_layout_plan* layout, int allow_remap)
{
    for (u32 b = 0; b < dc.n_arr && dc.fetch_ok; b++)""",
"""#if defined(__ANDROID__)
static int eu_index_fast(const rsx_dsp_index_array* ia, u32 esz, u32 ia_loc, u32 base_index, int restart_en,
                         u32 restart_val, int allow_remap, u32* out_count)
{
    static int on = -1;
    if (on < 0) { const char* e = getenv("EU_INDEX_FAST"); on = (e && *e == '0') ? 0 : 1; }
    { extern volatile int g_eu_live_off __attribute__((weak)); if (&g_eu_live_off && (g_eu_live_off & 8)) return 0; }
    if (!on || !allow_remap || dc.inl || dc.n_arr || dc.n_idx != 1 || dc.n_refs || !eu_vk_pull_ok || !eu_vk_pull_ok()) return 0;
    const u32 first = dc.idx[0].first, count = dc.idx[0].count;
    if (count < 2 || count > (1u << 24)) return 0;
    const u64 start = (u64)ia->offset + (u64)first * esz;
    if (start + (u64)count * esz > 0x100000000ull) return 0;
    const u8* run = (const u8*)eng_guest_ptr(NULL, ia_loc, (u32)start, count * esz);
    if (!run) return 0;
    if (count > dc.ref_remap.occurrence_capacity) {
        u32* grown = (u32*)realloc(dc.ref_remap.occurrence_to_unique, (size_t)count * sizeof(u32));
        if (!grown) return 0;
        dc.ref_remap.occurrence_to_unique = grown;
        dc.ref_remap.occurrence_capacity = count;
    }
    u32* occ = dc.ref_remap.occurrence_to_unique;
    u32 lo = 0xFFFFFFFFu, hi = 0;
    if (esz == 2) {
        for (u32 i = 0; i < count; i++) {
            u16 w; memcpy(&w, run + (size_t)i * 2, 2);
            const u32 v = __builtin_bswap16(w);
            if (restart_en && v == restart_val) return 0;
            occ[i] = v;
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
    } else {
        for (u32 i = 0; i < count; i++) {
            u32 w; memcpy(&w, run + (size_t)i * 4, 4);
            const u32 v = __builtin_bswap32(w);
            if (restart_en && v == restart_val) return 0;
            occ[i] = v;
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
    }
    const u64 span = (u64)hi - lo + 1u;
    if (span > count) return 0;                       /* sparse: the generic pass shades fewer vertices */
    while (dc.cap_refs < (u32)span) {
        const u32 next = dc.cap_refs ? dc.cap_refs * 2u : 4096u;
        rsx_vertex_ref* r = (rsx_vertex_ref*)realloc(dc.refs, (size_t)next * sizeof(*r));
        if (!r) return 0;
        dc.refs = r;
        dc.cap_refs = next;
    }
    for (u32 i = 0; i < count; i++) occ[i] -= lo;
    for (u32 k = 0; k < (u32)span; k++) { dc.refs[k].vertex_id = lo + k; dc.refs[k].base_index = base_index; }
    dc.n_refs = (u32)span;
    dc.refs_remapped = 1;
    *out_count = count;
    return 1;
}
#endif
static void dc_fetch(const rsx_vertex_layout_plan* layout, int allow_remap)
{
#if defined(__ANDROID__)
    int eu_fast = 0;
    u32 eu_fast_count = 0;
#endif
    for (u32 b = 0; b < dc.n_arr && dc.fetch_ok; b++)""")
# Idle SPURS kernel threads on Android. The kick machinery (a kernel thread
# parks on its own event, work publication wakes it) is compiled for Windows
# only; elsewhere an idle kernel thread just slept 250 us per empty pass, i.e.
# 4000 wake-ups a second each. boot-20261111-091739: 12 kernel threads used
# ~235% of a core, most of it nine or ten idle ones at 10-19% each, while the
# game's main thread waited 58-62% of the time for a core.
# Now (EU_SPURS_PARK, default 1; 0 = old 250 us naps): the kick slots, their
# events and the SetEvent calls exist on Android too; a kernel thread idle for
# more than 5 ms waits on its kick event for 1 ms per empty pass, more than
# 50 ms -> EU_SPURS_PARK_MS (default 2). ReadyCountStore already kicks the
# owning kernels; the other entry points that publish work kick as well.
change("libs/spurs/cellSpurs.c",
"""#ifdef _WIN32
            SetEvent(s_kick_ev[i]);
#endif""",
"""#if defined(_WIN32) || defined(__ANDROID__)
            SetEvent(s_kick_ev[i]);
#endif""", count=2)
change("libs/spurs/cellSpurs.c",
"""    int kick_slot = -1;         /* v82 SPURS_KICK slot */
#ifdef _WIN32
    {   /* v83: always register (TASKWAIT_BOOST uses the same wake path) */""",
"""    int kick_slot = -1;         /* v82 SPURS_KICK slot */
#if defined(_WIN32) || defined(__ANDROID__)
    {   /* v83: always register (TASKWAIT_BOOST uses the same wake path) */""")
change("libs/spurs/cellSpurs.c",
"""                else if (wait_us >= 1000) Sleep((DWORD)(wait_us / 1000));
                else {""",
"""#if defined(__ANDROID__)
                else if (kick_slot >= 0 && wait_us > 0 && eu_spurs_park_ms() > 0 &&
                         now.QuadPart - t_last_busy > s_freq / 200) {
                    const DWORD ms = now.QuadPart - t_last_busy > s_freq / 20 ? (DWORD)eu_spurs_park_ms() : 1u;
                    InterlockedExchange(&s_kick_waiting[kick_slot], 1);
                    InterlockedIncrement(&s_kick_parked);
                    const DWORD r = WaitForSingleObject(s_kick_ev[kick_slot], ms);
                    InterlockedDecrement(&s_kick_parked);
                    InterlockedIncrement(&s_eu_park_n);
                    if (r == WAIT_OBJECT_0) InterlockedIncrement(&s_eu_park_kicked);
                    else if (InterlockedExchange(&s_kick_waiting[kick_slot], 0) == 0)
                        WaitForSingleObject(s_kick_ev[kick_slot], 0);   /* a kicker claimed the slot after the timeout */
                    {   /* one line per 10 s, from whichever kernel gets there first */
                        static volatile LONGLONG s_rep;
                        const LONGLONG last = s_rep;
                        if (now.QuadPart - last > s_freq * 10 &&
                            InterlockedCompareExchange64(&s_rep, now.QuadPart, last) == last && last)
                            fprintf(stderr, "[park] 10s: idle kernel threads parked %ld times, %ld ended by a kick\\n",
                                    InterlockedExchange(&s_eu_park_n, 0), InterlockedExchange(&s_eu_park_kicked, 0));
                    }
                }
#endif
                else if (wait_us >= 1000) Sleep((DWORD)(wait_us / 1000));
                else {""")
change("libs/spurs/cellSpurs.c",
"""static DWORD WINAPI spurs_kernel_thread(LPVOID p)
{""",
"""#if defined(__ANDROID__)
static volatile long s_eu_park_n, s_eu_park_kicked;
extern volatile int g_eu_live_off __attribute__((weak));   /* eu_android_bridge.c: bit 4 = parking switched off for now */
static int eu_spurs_park_ms(void)
{
    static int ms = -1;
    if (&g_eu_live_off && (g_eu_live_off & 4)) return 0;
    if (ms < 0) {
        const char* e = getenv("EU_SPURS_PARK"); const char* m = getenv("EU_SPURS_PARK_MS");
        int v = (m && *m) ? atoi(m) : 2;
        if (v < 1) v = 1;
        if (v > 8) v = 8;
        if (e && *e == '0') v = 0;
        fprintf(stderr, "[park] idle SPURS kernel threads wait on their kick event: %s, up to %d ms (EU_SPURS_PARK, EU_SPURS_PARK_MS)\\n",
                v ? "on" : "off", v);
        ms = v;
        return v;
    }
    return ms;
}
#endif
static DWORD WINAPI spurs_kernel_thread(LPVOID p)
{""")
for sig in ("s32 cellSpursSendSignal(CellSpursTaskset* taskset, CellSpursTaskId taskId)\n{",
            "s32 cellSpursWakeUp(CellSpurs* spurs)\n{",
            "s32 cellSpursEventFlagSet(CellSpursEventFlag* eventFlag, u16 bits)\n{",
            "s32 _cellSpursSendSignal(void* taskset, u32 taskId)\n{",
            "s32 cellSpursRunJobChain(u64 jc_ea)\n{",
            "s32 cellSpursKickJobChain(u64 jc_ea, u32 numReadyCount)\n{",
            "s32 cellSpursQueuePushBody(u64 queue_ea, u64 data_ea, u32 isBlocking)\n{"):
    change("libs/spurs/cellSpurs.c", sig, sig + """
#if defined(__ANDROID__)
    { extern void ps3_spurs_kick(void); ps3_spurs_kick(); }   /* work may be about to appear: wake parked kernels */
#endif""")
# The lock every SPU thread takes around shared guest memory. Outside Windows it was a bare busy loop:
# with 44 SPU threads on 8 cores the holder is often not running, and each waiter then burns its whole
# turn on a core spinning (boot-20261111-102445: up to 23 of 40 samples of a kernel thread in
# spu_lockline_lock_ea). Spin briefly, then give the core away, as the Windows path does.
change("runtime/spu/spu_coherency.c",
"""static inline void ll_acq(volatile long* l) { while (__atomic_exchange_n(l, 1, __ATOMIC_ACQUIRE)) { while (__atomic_load_n(l, __ATOMIC_RELAXED)) { } } }""",
"""#if defined(__ANDROID__)
#include <sched.h>
#include <unistd.h>
extern volatile int g_eu_live_off __attribute__((weak));   /* eu_android_bridge.c: bit 32 = back to the bare loop for now */
volatile unsigned long g_eu_ll_yields;                     /* times a waiter gave its core away (reported in [summary]) */
static int eu_ll_yield_on(void)
{
    static int on = -1;
    if (on < 0) {
        const char* e = getenv("EU_LL_YIELD");
        on = (e && *e == '0') ? 0 : 1;
        fprintf(stderr, "[lock] SPU shared-memory lock: waiters give their core away after a short spin: %s (EU_LL_YIELD)\\n", on ? "on" : "off");
    }
    return on && !(&g_eu_live_off && (g_eu_live_off & 32));
}
static inline void ll_acq(volatile long* l)
{
    unsigned spins = 0;
    while (__atomic_exchange_n(l, 1, __ATOMIC_ACQUIRE)) {
        while (__atomic_load_n(l, __ATOMIC_RELAXED)) {
#if defined(__aarch64__)
            __asm__ __volatile__("yield");
#endif
            if (++spins < 200 || !eu_ll_yield_on()) continue;
            __atomic_fetch_add(&g_eu_ll_yields, 1, __ATOMIC_RELAXED);
            if (spins < 200 + 32) sched_yield();   /* holder is probably waiting for a core: let it run */
            else usleep(100);                       /* still held: stop competing with it altogether */
        }
    }
}
#else
static inline void ll_acq(volatile long* l) { while (__atomic_exchange_n(l, 1, __ATOMIC_ACQUIRE)) { while (__atomic_load_n(l, __ATOMIC_RELAXED)) { } } }
#endif""")
# One SPU thread per workload at a time, as the game asks for. Every inFAMOUS workload is created with
# maxContention=1; the PC port runs each workload's five lanes on five threads at once anyway ("parallel lane
# mode") because a desktop has cores to spare. On an 8-core phone that is 32 extra threads competing with the
# 12 kernel threads, the main thread and the renderer. EU_SPU_SERIAL=1 (default off, it measured slower) takes the port's own serial
# path instead: the owning kernel thread visits the lanes in turn and the lane threads stay asleep.
# EU_SPU_SERIAL_WIDS=0,5,7 limits it to those workloads.
change("libs/spurs/cellSpurs.c",
"""static int spurs_parallel_sweep_allowed(u32 wid, u32 lanes, u32 maxcont)
{
    if (lanes <= 1 || !spurs_par_lanes_wid(wid)) return 0;
""",
"""#if defined(__ANDROID__)
static volatile long s_eu_serial_passes, s_eu_parallel_passes;
static int eu_spu_serial(u32 wid)
{
    extern volatile int g_eu_live_off __attribute__((weak));   /* eu_android_bridge.c: bit 256 = parallel again for now */
    static int on = -1; static u32 mask = 0xFFFFu;
    if (on < 0) {
        const char* e = getenv("EU_SPU_SERIAL"); const char* m = getenv("EU_SPU_SERIAL_WIDS");
        if (m && *m) { mask = 0;
            for (const char* q = m; *q; ) { while (*q == ',' || *q == ' ') q++; if (!*q) break;
                int w = atoi(q); if (w >= 0 && w < 16) mask |= 1u << w; while (*q && *q != ',') q++; } }
        /* Default off since boot-20261111-182540: with it on 5.0-7.4 frames/s, in the stretches with it off
         * 7.7 and 8.8. The five lanes do share real work; one thread at a time just queues it. */
        const int v = (e && *e == '1') ? 1 : 0;
        fprintf(stderr, "[serial] one SPU thread per workload at a time (the game's own limit), workload mask 0x%04X: %s (EU_SPU_SERIAL)\\n",
                (unsigned)mask, v ? "on" : "off");
        on = v;
    }
    if (!on || wid >= 16 || !((mask >> wid) & 1u)) return 0;
    return !(&g_eu_live_off && (g_eu_live_off & 256));
}
#endif
static int spurs_parallel_sweep_allowed(u32 wid, u32 lanes, u32 maxcont)
{
    if (lanes <= 1 || !spurs_par_lanes_wid(wid)) return 0;
#if defined(__ANDROID__)
    if (eu_spu_serial(wid)) { InterlockedIncrement(&s_eu_serial_passes); return 0; }
    InterlockedIncrement(&s_eu_parallel_passes);
#endif
""")
# report the split in the existing [park] line
change("libs/spurs/cellSpurs.c",
"""                            fprintf(stderr, "[park] 10s: idle kernel threads parked %ld times, %ld ended by a kick\\n",
                                    InterlockedExchange(&s_eu_park_n, 0), InterlockedExchange(&s_eu_park_kicked, 0));""",
"""                            fprintf(stderr, "[park] 10s: idle kernel threads parked %ld times, %ld ended by a kick | workload passes: %ld one thread at a time, %ld on lane threads\\n",
                                    InterlockedExchange(&s_eu_park_n, 0), InterlockedExchange(&s_eu_park_kicked, 0),
                                    InterlockedExchange(&s_eu_serial_passes, 0), InterlockedExchange(&s_eu_parallel_passes, 0));""")
# Processor time per SPU job module, over every SPU thread ([jobs] line every 10 s, printed by the bridge's
# sampler). The job manager streams one module at a time into LS 0x4000 and branches into it; every dispatch
# path already calls spu_wws_job_refresh_ovl with the branch target, so "target inside the job code buffer"
# / "target below it" are the edges of a job. Thread CPU time between the edges goes to the module resident
# at the exit edge (two clock reads per job). A job that returns to the manager through a plain C return is
# only closed at the manager's next dispatched branch, so a little manager time lands on the job.
# This is the list native replacements are chosen from. EU_JOBTIME=0 = off.
change("runtime/spu/spu_channels.c",
"""static inline __attribute__((always_inline)) void spu_wws_job_refresh_ovl_fast(spu_context* ctx, uint32_t pc)
{
""",
"""#if defined(__ANDROID__)
/* ---- job recorder ----
 * Saves complete runs of one job module (EU_JOBREC=434ec20243de is the costliest one in boot-20261111-182540) to <game folder>/eu_logs/jobrec-<sig>.bin: registers and local store at entry, every
 * memory transfer and channel access during the run, registers and local store at exit. A native version
 * of the job is then written and checked against these on a desktop before it runs on a phone.
 *   EU_JOBREC=<first hex digits of the module signature> (0 = off), EU_JOBREC_FIRST (300), EU_JOBREC_EVERY
 *   (2000), EU_JOBREC_MAX (24 runs).
 * File: "EUJR0001", then records; each record = tagged chunks {u32 tag, u32 bytes, data}:
 *   'HEAD' {u32 run, u32 pc, u32 overlay, u32 image, u8 sig[16]}   'CTX0' raw spu_context   'REG0' 128 x 16
 *   'LS_0' 256 KB (first record) or 'LSX0' xor against the first record's LS_0
 *   'DMA_' {u32 cmd, u32 lsa, u64 ea, u32 size, i32 rc} + size bytes of local store after the transfer
 *   'WRCH' {u32 ch, u8 v[16]}   'RDCH' {u32 ch, u8 v[16]}   'ATOM' {u32 lsa, u32 ea} + 128 bytes (after an atomic status read)
 *   'TAIL' {u32 exit pc}   'REG1'   'LSX1' xor against this record's entry local store   'END_' */
volatile int g_eu_jobrec_live;                 /* some thread is recording right now (read by spu_dma.h) */
static SPU_THREAD_LOCAL spu_context* t_eu_rec_ctx;
static uint8_t* s_eu_rec_buf; static size_t s_eu_rec_len, s_eu_rec_cap;
static uint8_t* s_eu_rec_ls0; static uint8_t* s_eu_rec_first;
static int s_eu_rec_on = -1, s_eu_rec_n, s_eu_rec_max = 24, s_eu_rec_first_run = 300, s_eu_rec_every = 2000;
static uint8_t s_eu_rec_sig[16]; static int s_eu_rec_siglen;
static volatile long s_eu_rec_seen;
static void eu_rec_put(uint32_t tag, const void* a, uint32_t na, const void* b, uint32_t nb)
{
    const size_t need = s_eu_rec_len + 8 + na + nb;
    if (need > s_eu_rec_cap) {
        size_t cap = s_eu_rec_cap ? s_eu_rec_cap * 2 : (1u << 20);
        while (cap < need) cap *= 2;
        uint8_t* nbuf = (uint8_t*)realloc(s_eu_rec_buf, cap);
        if (!nbuf) return;
        s_eu_rec_buf = nbuf; s_eu_rec_cap = cap;
    }
    const uint32_t bytes = na + nb;
    memcpy(s_eu_rec_buf + s_eu_rec_len, &tag, 4); memcpy(s_eu_rec_buf + s_eu_rec_len + 4, &bytes, 4);
    if (na) memcpy(s_eu_rec_buf + s_eu_rec_len + 8, a, na);
    if (nb) memcpy(s_eu_rec_buf + s_eu_rec_len + 8 + na, b, nb);
    s_eu_rec_len = need;
}
#define EU_TAG(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
void eu_jobrec_dma(spu_context* ctx, uint32_t lsa, uint64_t ea, uint32_t size, uint32_t cmd, int rc)
{
    if (t_eu_rec_ctx != ctx) return;
    struct { uint32_t cmd, lsa; uint64_t ea; uint32_t size; int32_t rc; } h = { cmd, lsa, ea, size, rc };
    const uint32_t at = lsa & (SPU_LS_SIZE - 1);
    const uint32_t n = (size <= 0x4000 && at + size <= SPU_LS_SIZE) ? size : 0;
    eu_rec_put(EU_TAG('D', 'M', 'A', '_'), &h, sizeof h, ctx->ls + at, n);
}
static void eu_jobrec_ch(spu_context* ctx, uint32_t tag, uint32_t ch, const u128* v)
{
    if (t_eu_rec_ctx != ctx) return;
    eu_rec_put(tag, &ch, 4, v, 16);
    /* Atomic transfers (GETLLAR / PUTLLC) do not go through mfc_do_transfer. Their status read is the
     * point where the 128-byte line is settled in the local store: save it there. The first recordings
     * lacked this, and every run that produced output could not be replayed past it. */
    if (ch == 27 /* MFC_RdAtomicStat */ && tag == EU_TAG('R', 'D', 'C', 'H')) {
        struct { uint32_t lsa, ea; } h = { ctx->mfc_lsa, ctx->mfc_eal };
        const uint32_t at = ctx->mfc_lsa & (SPU_LS_SIZE - 1) & ~127u;
        eu_rec_put(EU_TAG('A', 'T', 'O', 'M'), &h, sizeof h, ctx->ls + at, 128);
    }
}
static void eu_jobrec_begin(spu_context* ctx, uint32_t pc)
{
    if (s_eu_rec_on < 0) {
        const char* e = getenv("EU_JOBREC"); const char* a = getenv("EU_JOBREC_FIRST");
        const char* b = getenv("EU_JOBREC_EVERY"); const char* c = getenv("EU_JOBREC_MAX");
        if (!e || !*e) e = "0";   /* off unless asked for: the 24 runs of 434ec20243de are in hand */
        int n = 0;
        if (strcmp(e, "0")) for (; n < 16 && e[2 * n] && e[2 * n + 1]; n++) {
            unsigned v = 0; if (sscanf(e + 2 * n, "%2x", &v) != 1) break; s_eu_rec_sig[n] = (uint8_t)v; }
        if (a && atoi(a) > 0) s_eu_rec_first_run = atoi(a);
        if (b && atoi(b) > 0) s_eu_rec_every = atoi(b);
        if (c && *c) s_eu_rec_max = atoi(c);
        s_eu_rec_siglen = n;
        fprintf(stderr, "[jobrec] recording %d runs of job module %s (run %d, then every %d): %s (EU_JOBREC)\\n", s_eu_rec_max, e,
                s_eu_rec_first_run, s_eu_rec_every, n >= 4 ? "on" : "off");
        s_eu_rec_on = n >= 4;
    }
    if (!s_eu_rec_on || s_eu_rec_n >= s_eu_rec_max) return;
    if (memcmp(ctx->ls + 0x4000, s_eu_rec_sig, (size_t)s_eu_rec_siglen)) return;
    const long seen = __atomic_add_fetch(&s_eu_rec_seen, 1, __ATOMIC_RELAXED);
    if (seen < s_eu_rec_first_run || (seen - s_eu_rec_first_run) % s_eu_rec_every) return;
    if (__atomic_exchange_n(&g_eu_jobrec_live, 1, __ATOMIC_ACQUIRE)) return;   /* one recording at a time */
    if (!s_eu_rec_ls0) { s_eu_rec_ls0 = (uint8_t*)malloc(SPU_LS_SIZE); s_eu_rec_first = (uint8_t*)malloc(SPU_LS_SIZE); }
    if (!s_eu_rec_ls0 || !s_eu_rec_first) { __atomic_store_n(&g_eu_jobrec_live, 0, __ATOMIC_RELEASE); s_eu_rec_on = 0; return; }
    s_eu_rec_len = 0;
    struct { uint32_t run, pc, ovl, img; uint8_t sig[16]; } h = { (uint32_t)seen, pc, (uint32_t)ctx->resident_ovl, (uint32_t)ctx->image_id, {0} };
    memcpy(h.sig, ctx->ls + 0x4000, 16);
    eu_rec_put(EU_TAG('H', 'E', 'A', 'D'), &h, sizeof h, NULL, 0);
    eu_rec_put(EU_TAG('C', 'T', 'X', '0'), ctx, sizeof *ctx, NULL, 0);
    eu_rec_put(EU_TAG('R', 'E', 'G', '0'), ctx->gpr, sizeof ctx->gpr, NULL, 0);
    memcpy(s_eu_rec_ls0, ctx->ls, SPU_LS_SIZE);
    if (s_eu_rec_n == 0) {
        memcpy(s_eu_rec_first, ctx->ls, SPU_LS_SIZE);
        eu_rec_put(EU_TAG('L', 'S', '_', '0'), ctx->ls, SPU_LS_SIZE, NULL, 0);
    } else {
        uint8_t* x = (uint8_t*)malloc(SPU_LS_SIZE);
        if (x) { for (uint32_t i = 0; i < SPU_LS_SIZE; i++) x[i] = ctx->ls[i] ^ s_eu_rec_first[i];
                 eu_rec_put(EU_TAG('L', 'S', 'X', '0'), x, SPU_LS_SIZE, NULL, 0); free(x); }
    }
    t_eu_rec_ctx = ctx;
}
static void eu_jobrec_end(spu_context* ctx, uint32_t pc)
{
    if (t_eu_rec_ctx != ctx) return;
    t_eu_rec_ctx = NULL;
    eu_rec_put(EU_TAG('T', 'A', 'I', 'L'), &pc, 4, NULL, 0);
    eu_rec_put(EU_TAG('R', 'E', 'G', '1'), ctx->gpr, sizeof ctx->gpr, NULL, 0);
    {
        uint8_t* x = (uint8_t*)malloc(SPU_LS_SIZE);
        if (x) { for (uint32_t i = 0; i < SPU_LS_SIZE; i++) x[i] = ctx->ls[i] ^ s_eu_rec_ls0[i];
                 eu_rec_put(EU_TAG('L', 'S', 'X', '1'), x, SPU_LS_SIZE, NULL, 0); free(x); }
    }
    eu_rec_put(EU_TAG('E', 'N', 'D', '_'), NULL, 0, NULL, 0);
    const char* root = getenv("PS3_VFS_ROOT");
    char path[512], sig[16];
    snprintf(sig, sizeof sig, "%02x%02x%02x%02x%02x%02x", s_eu_rec_sig[0], s_eu_rec_sig[1], s_eu_rec_sig[2], s_eu_rec_sig[3], s_eu_rec_sig[4], s_eu_rec_sig[5]);
    snprintf(path, sizeof path, "%s/eu_logs/jobrec-%s.bin", root && *root ? root : ".", sig);
    FILE* f = fopen(path, s_eu_rec_n == 0 ? "wb" : "ab");
    int ok = 0;
    if (f) {
        if (s_eu_rec_n == 0) fwrite("EUJR0002", 1, 8, f);
        ok = fwrite(s_eu_rec_buf, 1, s_eu_rec_len, f) == s_eu_rec_len;
        fclose(f);
    }
    s_eu_rec_n++;
    fprintf(stderr, "[jobrec] run %d of %d %s: %zu bytes, left the job at pc 0x%05X (%s)\\n", s_eu_rec_n, s_eu_rec_max,
            ok ? "saved" : "NOT saved", s_eu_rec_len, pc, path);
    if (!ok) s_eu_rec_on = 0;
    __atomic_store_n(&g_eu_jobrec_live, 0, __ATOMIC_RELEASE);
}
#define EU_JOBTIME_SLOTS 66   /* 0..63 = overlay ids 200..263, 64 = job code that is not a lifted module, 65 = spare */
volatile long long g_eu_job_watch_ns; volatile long g_eu_job_watch_runs;
static volatile long long s_eu_job_ns[EU_JOBTIME_SLOTS];
static volatile long s_eu_job_runs[EU_JOBTIME_SLOTS];
static int s_eu_jobtime = -1;
static SPU_THREAD_LOCAL int t_eu_in_job;
static SPU_THREAD_LOCAL long long t_eu_job_t0;
void eu_jobtime_edge(spu_context* ctx, uint32_t pc)
{
    if (__builtin_expect(s_eu_jobtime < 0, 0)) { const char* e = getenv("EU_JOBTIME"); s_eu_jobtime = (e && *e == '0') ? 0 : 1; }
    if (ctx->image_id != 1 && ctx->image_id != 2) return;
    const int in = pc >= 0x4000 && pc < 0x3E000;
    if (in == t_eu_in_job) return;
    if (in) eu_jobrec_begin(ctx, pc); else if (g_eu_jobrec_live) eu_jobrec_end(ctx, pc);
    if (!s_eu_jobtime) { t_eu_in_job = in; return; }
    struct timespec ts;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    const long long now = (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
    if (in) t_eu_job_t0 = now;
    else {
        const int id = ctx->resident_ovl;
        const int slot = (id >= 200 && id < 264) ? id - 200 : 64;
        __atomic_fetch_add(&s_eu_job_ns[slot], now - t_eu_job_t0, __ATOMIC_RELAXED);
        __atomic_fetch_add(&s_eu_job_runs[slot], 1, __ATOMIC_RELAXED);
        /* running totals of the visibility job (signature 43 4e c2 02), never reset: the bridge's in-run
         * comparison reads them at the ends of a stretch */
        if (ctx->ls[0x4000] == 0x43 && ctx->ls[0x4001] == 0x4e && ctx->ls[0x4002] == 0xc2 && ctx->ls[0x4003] == 0x02) {
            __atomic_fetch_add(&g_eu_job_watch_ns, now - t_eu_job_t0, __ATOMIC_RELAXED);
            __atomic_fetch_add(&g_eu_job_watch_runs, 1, __ATOMIC_RELAXED);
        }
    }
    t_eu_in_job = in;
}
#endif
static inline __attribute__((always_inline)) void spu_wws_job_refresh_ovl_fast(spu_context* ctx, uint32_t pc)
{
#if defined(__ANDROID__)
    eu_jobtime_edge(ctx, pc);
#endif
""")
change("runtime/spu/spu_channels.c",
"""void spu_wws_job_refresh_ovl(spu_context* ctx, uint32_t pc)
{
    if ((ctx->image_id != 1 && ctx->image_id != 2) || pc < 0x4000 || pc >= 0x3E000) return;
""",
"""void spu_wws_job_refresh_ovl(spu_context* ctx, uint32_t pc)
{
#if defined(__ANDROID__)
    eu_jobtime_edge(ctx, pc);
#endif
    if ((ctx->image_id != 1 && ctx->image_id != 2) || pc < 0x4000 || pc >= 0x3E000) return;
""")
change("runtime/spu/spu_dispatch_mt.c",
"""          if (pc >= 0x4000) spu_wws_job_refresh_ovl(ctx, pc); }
        spu_dispatch_fn fn = ctx->resident_ovl ? spu_lookup(pc, ctx->resident_ovl) : 0;""",
"""          if (pc >= 0x4000) spu_wws_job_refresh_ovl(ctx, pc);
#if defined(__ANDROID__)
          else { extern void eu_jobtime_edge(spu_context*, uint32_t); eu_jobtime_edge(ctx, pc); }
#endif
        }
        spu_dispatch_fn fn = ctx->resident_ovl ? spu_lookup(pc, ctx->resident_ovl) : 0;""")
change("runtime/spu/spu_drain.c",
"""              if (pc >= 0x4000) spu_wws_job_refresh_ovl(ctx, pc); }""",
"""              if (pc >= 0x4000) spu_wws_job_refresh_ovl(ctx, pc);
#if defined(__ANDROID__)
              else { extern void eu_jobtime_edge(spu_context*, uint32_t); eu_jobtime_edge(ctx, pc); }
#endif
            }""")
change("runtime/spu/spu_channels.c",
"""            (g[0] + g[1]) ? 100.0 * g[4] / (double)(g[0] + g[1]) : 0.0, g[5] / s / 1000.0, g[6] / s / 1000.0);
}
""",
"""            (g[0] + g[1]) ? 100.0 * g[4] / (double)(g[0] + g[1]) : 0.0, g[5] / s / 1000.0, g[6] / s / 1000.0);
}

#if defined(__ANDROID__)
/* Called every 10 s by the bridge's sampler thread. */
void eu_jobtime_report(double secs)
{
    if (s_eu_jobtime <= 0 || secs <= 0) return;
    long long ns[EU_JOBTIME_SLOTS]; long runs[EU_JOBTIME_SLOTS]; long long total = 0; long total_runs = 0;
    for (int i = 0; i < EU_JOBTIME_SLOTS; i++) {
        ns[i] = __atomic_exchange_n(&s_eu_job_ns[i], 0, __ATOMIC_RELAXED);
        runs[i] = __atomic_exchange_n(&s_eu_job_runs[i], 0, __ATOMIC_RELAXED);
        total += ns[i]; total_runs += runs[i];
    }
    if (!total_runs) return;
    char line[1500]; int off = 0;
    off += snprintf(line + off, sizeof line - off, "[jobs] %.0fs: SPU job code %.0f%% of one core in %ld runs |", secs,
                    (double)total / (secs * 1e7), total_runs);
    for (int shown = 0; shown < 14; shown++) {
        int best = -1;
        for (int i = 0; i < EU_JOBTIME_SLOTS; i++) if (ns[i] > 0 && (best < 0 || ns[i] > ns[best])) best = i;
        if (best < 0 || off > (int)sizeof line - 120) break;
        char name[24] = "unlifted";
        if (best < 64)
            for (int k = 0; k < s_ovl_src_count; k++)
                if (s_ovl_src[k].has_sig && s_ovl_src[k].image_id == 200 + best) {
                    const uint8_t* g = s_ovl_src[k].sig;
                    snprintf(name, sizeof name, "%d:%02x%02x%02x%02x%02x%02x", 200 + best, g[0], g[1], g[2], g[3], g[4], g[5]);
                    break;
                }
        off += snprintf(line + off, sizeof line - off, " %s %.1f%% %ldx %.0fus", name, (double)ns[best] / (secs * 1e7),
                        runs[best], runs[best] ? (double)ns[best] / 1000.0 / (double)runs[best] : 0.0);
        ns[best] = 0;
    }
    fprintf(stderr, "%s\\n", line);
}
#endif
""")
# Job recorder hooks (see the [jobrec] block in spu_channels.c above).
change("runtime/spu/spu_channels.c",
"""void spu_wrch(spu_context* ctx, uint32_t channel, u128 value)
{
""",
"""#if defined(__ANDROID__)
static void eu_jobrec_ch(spu_context* ctx, uint32_t tag, uint32_t ch, const u128* v);
extern volatile int g_eu_jobrec_live;
#endif
void spu_wrch(spu_context* ctx, uint32_t channel, u128 value)
{
#if defined(__ANDROID__)
    if (__builtin_expect(g_eu_jobrec_live, 0)) eu_jobrec_ch(ctx, 0x48435257u /* 'WRCH' */, channel, &value);
#endif
""")
change("runtime/spu/spu_channels.c",
"""    u128 r = spu_rdch_impl(ctx, channel);
    spu_lp_end(t0, LP_RDCH);
    return r;
""",
"""    u128 r = spu_rdch_impl(ctx, channel);
    spu_lp_end(t0, LP_RDCH);
#if defined(__ANDROID__)
    if (__builtin_expect(g_eu_jobrec_live, 0)) eu_jobrec_ch(ctx, 0x48434452u /* 'RDCH' */, channel, &r);
#endif
    return r;
""")
change("runtime/spu/spu_dma.h",
"""static inline int mfc_do_transfer(spu_context* spu, uint32_t lsa, uint64_t ea,
                                   uint32_t size, uint32_t cmd)
{
""",
"""static inline int mfc_do_transfer_eu_inner(spu_context* spu, uint32_t lsa, uint64_t ea,
                                            uint32_t size, uint32_t cmd);
static inline int mfc_do_transfer(spu_context* spu, uint32_t lsa, uint64_t ea,
                                   uint32_t size, uint32_t cmd)
{
    const int rc = mfc_do_transfer_eu_inner(spu, lsa, ea, size, cmd);
#if defined(__ANDROID__)
    { extern volatile int g_eu_jobrec_live; extern void eu_jobrec_dma(spu_context*, uint32_t, uint64_t, uint32_t, uint32_t, int);
      if (__builtin_expect(g_eu_jobrec_live, 0)) eu_jobrec_dma(spu, lsa, ea, size, cmd, rc); }
#endif
    return rc;
}
static inline int mfc_do_transfer_eu_inner(spu_context* spu, uint32_t lsa, uint64_t ea,
                                            uint32_t size, uint32_t cmd)
{
""")
# The game's main thread waits for SPU jobs by polling: check a counter, sys_timer_usleep(1) (5 at one
# site), check again (func_002CFFE0 / func_001167C8). On Windows the runtime answers sleeps of 50 us or less
# with a PAUSE spin to the deadline (its v54 note: with a real yield "the main PPU thread spent about 30% of
# wall time here"). Everything else fell through to nanosleep: on Android that is 50 us of timer slack at
# best, and the thread gives its core away each time and queues for another on a phone whose cores are all
# busy. boot-20261111-234138: a third of all main-thread samples were inside these sleeps and the thread
# was ready-but-waiting for a core 39-47% of the time. Same answer as on Windows, for the main thread only
# (a background thread that polls forever must not turn into a spinning core).
# EU_SHORT_SPIN=0 = nanosleep as before.
change("runtime/syscalls/sys_timer.c",
"""#else
    if (usec > 0) {
        struct timespec ts;
        ts.tv_sec  = (time_t)(usec / 1000000);
        ts.tv_nsec = (long)((usec % 1000000) * 1000);
        nanosleep(&ts, NULL);
    }
#endif

    return CELL_OK;
}""",
"""#else
    if (usec > 0) {
#if defined(__ANDROID__)
        if (usec <= 50) {
            extern volatile int g_eu_live_off __attribute__((weak));   /* eu_android_bridge.c: bit 512 = sleep for now */
            static int s_on = -1;
            static __thread int t_is_main = -1;
            if (s_on < 0) {
                const char* e = getenv("EU_SHORT_SPIN");
                s_on = (e && *e == '0') ? 0 : 1;
                fprintf(stderr, "[timer] main-thread sleeps of 50 us or less are spun to their deadline, not slept: %s (EU_SHORT_SPIN)\\n", s_on ? "on" : "off");
            }
            if (t_is_main < 0) t_is_main = (gettid() == getpid());
            if (s_on && t_is_main && !(&g_eu_live_off && (g_eu_live_off & 512))) {
                struct timespec t0, t1;
                clock_gettime(CLOCK_MONOTONIC, &t0);
                const long long deadline = (long long)t0.tv_sec * 1000000000LL + t0.tv_nsec + (long long)usec * 1000LL;
                do {
#if defined(__aarch64__)
                    __asm__ __volatile__("yield"); __asm__ __volatile__("yield"); __asm__ __volatile__("yield"); __asm__ __volatile__("yield");
#endif
                    clock_gettime(CLOCK_MONOTONIC, &t1);
                } while ((long long)t1.tv_sec * 1000000000LL + t1.tv_nsec < deadline);
                { extern volatile unsigned long g_eu_short_spins; g_eu_short_spins = g_eu_short_spins + 1; }
                return CELL_OK;
            }
        }
#endif
        struct timespec ts;
        ts.tv_sec  = (time_t)(usec / 1000000);
        ts.tv_nsec = (long)((usec % 1000000) * 1000);
        nanosleep(&ts, NULL);
    }
#endif

    return CELL_OK;
}
#if defined(__ANDROID__)
volatile unsigned long g_eu_short_spins;   /* main-thread short sleeps answered by spinning (reported in [summary]) */
#endif""")
print("Android patches applied:", len(changes), "edit anchors in", len(set(changes)), "real upstream files")
