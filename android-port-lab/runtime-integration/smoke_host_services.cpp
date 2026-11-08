// Host-owned services for the REAL upstream synthetic PS3 boot scaffold.
// The portable runtime builds without a title-specific main.cpp. These
// services belong to a host runner and are defined here ONLY for boot_smoke.
// No fake LV2 syscalls, SPU execution or RSX graphics are provided.
#include <stdint.h>
#include <atomic>
#include "ppu_recomp.h"

struct spu_context; // typed, opaque: only used for optional plugin lookup

extern "C" {
extern PPU_THREAD_LOCAL ppu_context* g_active_ctx;

// Performance counters are host-runner owned. The PPU loader and HLE layer
// use them to measure actual host time; they must be atomic when updated.
volatile long long g_hz[32] = {};
int ps3_hz_is_main(void) {
    return g_active_ctx && g_active_ctx->thread_id == 1;
}

// The smoke title's output is short; keep stdout flushing immediate.
int ps3_stdout_immediate(void) { return 1; }

// No generated SPU plugins are bundled with a synthetic test program.
// An absent plugin must return NULL, so the normal interpreter can be used.
// This is NOT an SPU emulator implementation.
void (*spu_plug_lookup(spu_context*, uint32_t))(spu_context*) {
    return nullptr;
}

// Tracks RSX write-watch invalidations in a host-side generation counter.
// Not a substitute for RSX rendering; the synthetic title has no graphics.
static std::atomic<uint64_t> s_rsx_ww_generation{0};
void rsx_ww_bump(void) {
    s_rsx_ww_generation.fetch_add(1, std::memory_order_relaxed);
}
}
