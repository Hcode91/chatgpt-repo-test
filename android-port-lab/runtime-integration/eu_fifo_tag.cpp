/* eu_fifo_tag.cpp -- which game code issued each draw.
 *
 * First step towards drawing from the game's own renderer instead of
 * reconstructing it from the RSX command stream: label every draw with the
 * guest code that wrote its command. Generic runtime code; it holds no game
 * code or addresses -- the addresses it logs are resolved against the private
 * function map offline.
 *
 * How: the runtime's PPU store observer (g_ppu_store_observer, called by the
 * lifted code's vm_write32 for one address window) is aimed at the GCM IO
 * region, where the game builds its command buffer. Every 32-bit store there
 * records the writer's guest lr in a shadow array, one word per FIFO word.
 * A SET_BEGIN_END header (the draw call) also records the guest call chain,
 * walked up the PPU stack (back chain at [sp], saved LR at [frame + 16]).
 * The FIFO drain then names the chain of the draw it is executing.
 * Words written by an SPU or by an HLE library show up as "no PPU writer". */
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "ppu_context.h"
#include "ppu_store_observer.h"

extern "C" uint8_t* vm_base;
extern "C" PPU_THREAD_LOCAL ppu_context* g_active_ctx;

namespace {

constexpr uint32_t kDepth = 6;          /* return addresses kept per draw   */
constexpr uint32_t kMaxChains = 8192;   /* distinct call chains remembered */
constexpr uint32_t kChainFlag = 0x80000000u;

struct Chain { uint32_t pc[kDepth]; uint32_t n; };

uint32_t s_base, s_words;
uint32_t* s_shadow;                     /* per FIFO word: lr, or kChainFlag|chain id */
Chain s_chains[kMaxChains];
uint32_t s_chain_hash[kMaxChains * 2];  /* open addressing: id + 1, 0 = empty */
uint32_t s_nchains;
volatile uint32_t s_lock;

inline uint32_t rd32(uint32_t ea) {
    uint32_t v;
    memcpy(&v, vm_base + ea, 4);
    return __builtin_bswap32(v);
}

uint32_t intern(const Chain& c)
{
    uint32_t h = 2166136261u;
    for (uint32_t i = 0; i < c.n; i++) h = (h ^ c.pc[i]) * 16777619u;
    while (__atomic_exchange_n(&s_lock, 1u, __ATOMIC_ACQUIRE)) {}
    uint32_t slot = h & (kMaxChains * 2 - 1), id = 0xFFFFFFFFu;
    for (uint32_t probe = 0; probe < kMaxChains * 2; probe++, slot = (slot + 1) & (kMaxChains * 2 - 1)) {
        const uint32_t e = s_chain_hash[slot];
        if (!e) {
            if (s_nchains < kMaxChains) {
                id = s_nchains++;
                s_chains[id] = c;
                s_chain_hash[slot] = id + 1;
            }
            break;
        }
        const Chain& o = s_chains[e - 1];
        if (o.n == c.n && !memcmp(o.pc, c.pc, c.n * 4)) { id = e - 1; break; }
    }
    __atomic_store_n(&s_lock, 0u, __ATOMIC_RELEASE);
    return id;
}

void on_store(uint32_t ea, uint32_t value, uint32_t lr)
{
    const uint32_t w = (ea - s_base) >> 2;
    if (w >= s_words) return;
    /* NV4097 SET_BEGIN_END header, one data word, increasing mode */
    if ((value & 0xE0031FFFu) == 0x1808u && ((value >> 18) & 0x7FFu) == 1u) {
        Chain c{};
        c.pc[c.n++] = lr;
        const ppu_context* ctx = g_active_ctx;
        if (ctx) {
            uint32_t sp = (uint32_t)ctx->gpr[1];
            for (uint32_t k = 0; k < 32 && c.n < kDepth; k++) {
                if (sp < 0x10000u || (sp & 7u)) break;
                const uint32_t back = rd32(sp + 4);          /* low word of the 64-bit back chain */
                if (back <= sp || back - sp > 0x100000u) break;
                const uint32_t ret = rd32(back + 16 + 4);    /* low word of the saved LR */
                if (ret && ret != c.pc[c.n - 1]) c.pc[c.n++] = ret;
                sp = back;
            }
        }
        const uint32_t id = intern(c);
        s_shadow[w] = id == 0xFFFFFFFFu ? lr : (kChainFlag | id);
        return;
    }
    s_shadow[w] = lr;
}

}  // namespace

extern "C" {

/* Chain id of the draw being executed (set by the FIFO drain at SET_BEGIN_END),
 * read by the renderer when it records the draw. */
int g_eu_draw_tag = -1;

/* cellGcmInit: the IO window the game builds its command buffers in. */
void eu_fifo_tag_arm(uint32_t io_ea, uint32_t io_size)
{
    const char* e = getenv("EU_FIFO_TAG");
    if (e && e[0] == '0') return;
    if (s_shadow || !io_ea || !io_size || io_size > (256u << 20)) return;
    s_shadow = (uint32_t*)calloc(io_size / 4u, 4);
    if (!s_shadow) return;
    s_base = io_ea;
    s_words = io_size / 4u;
    g_ppu_store_observer.notify = on_store;
    g_ppu_store_observer.begin = io_ea;
    __atomic_store_n(&g_ppu_store_observer.size, io_size, __ATOMIC_RELEASE);
    fprintf(stderr, "[fifo-tag] tagging command writes in 0x%08X..0x%08X\n", io_ea, io_ea + io_size);
}

/* Chain id of the draw whose BEGIN_END data word is at dea, or -1. */
int eu_fifo_tag_draw(uint32_t dea)
{
    if (!s_shadow) return -1;
    const uint32_t w = (dea - 4u - s_base) >> 2;      /* the header precedes its data */
    if (w >= s_words) return -1;
    const uint32_t v = s_shadow[w];
    if (v & kChainFlag) return (int)(v & ~kChainFlag);
    return -2 - (int)(v ? 0 : 1);                     /* -2: PPU but untracked, -3: no PPU writer */
}

/* "0x00123456<0x00234567<..." for a chain id from eu_fifo_tag_draw. */
const char* eu_fifo_tag_name(int id, char* buf, size_t cap)
{
    if (id == -3) { snprintf(buf, cap, "no-PPU-writer"); return buf; }
    if (id < 0 || (uint32_t)id >= s_nchains) { snprintf(buf, cap, "untagged"); return buf; }
    const Chain& c = s_chains[id];
    size_t o = 0;
    buf[0] = 0;
    for (uint32_t i = 0; i < c.n && o + 12 < cap; i++)
        o += (size_t)snprintf(buf + o, cap - o, i ? "<0x%08X" : "0x%08X", c.pc[i]);
    return buf;
}

}  // extern "C"
