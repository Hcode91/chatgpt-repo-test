/*
 * Android <-> game-runner bridge: one shared-memory file, two directions.
 *
 * The runner is a separate process (crash isolation from the app), so it has no
 * Android window and no input events of its own. The app creates a file, maps
 * it, and passes its path in PS3_ANDROID_BRIDGE. Through it:
 *   - the app writes controller state (on-screen controls or a real gamepad),
 *     which ps3recomp's cellPad reads on every poll (patched in by
 *     tools/patch_android_upstream.py) -- held buttons and analog sticks;
 *   - the runner publishes each presented RSX frame, which the app draws.
 *
 * Layout (little-endian; must match EuBridge.java):
 *   0  u32 magic 'EUBR'          32 u32 frame_seq (seqlock: odd = writing)
 *   4  u32 version (1)           36 u32 frame_w
 *   8  u32 pad_seq               40 u32 frame_h
 *  12  u32 pad_connected         44 u32 frames_published
 *  16  u32 buttons (CELL_PAD_CTRL_*)  48 u32 runner_alive
 *  20  u8  lx ly rx ry l2 r2     64 pixels, RGBA8888 rows (max 1280x720)
 *
 * Contains no game code. Everything here is a no-op when the variable is unset.
 */
#define _GNU_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define EU_BRIDGE_MAGIC   0x52425545u   /* "EUBR" */
#define EU_BRIDGE_VERSION 1u
#define EU_BRIDGE_HDR     64u
#define EU_BRIDGE_MAX_W   1280u
#define EU_BRIDGE_MAX_H   720u

typedef struct {
    uint32_t magic, version;
    _Atomic uint32_t pad_seq, pad_connected, buttons;
    _Atomic uint8_t axes[6];
    uint8_t _pad0[6];
    _Atomic uint32_t frame_seq, frame_w, frame_h, frames_published, runner_alive;
} eu_bridge_hdr;

#include <stddef.h>
_Static_assert(offsetof(eu_bridge_hdr, buttons) == 16, "layout");
_Static_assert(offsetof(eu_bridge_hdr, axes) == 20, "layout");
_Static_assert(offsetof(eu_bridge_hdr, frame_seq) == 32, "layout");
_Static_assert(offsetof(eu_bridge_hdr, runner_alive) == 48, "layout");
_Static_assert(sizeof(eu_bridge_hdr) <= 64, "bridge header must fit in 64 bytes");

static uint8_t* s_map;
static size_t s_size;
static int s_state; /* 0 = not tried, 1 = mapped, -1 = unavailable */

static eu_bridge_hdr* bridge(void)
{
    if (s_state == 1) return (eu_bridge_hdr*)s_map;
    if (s_state < 0) return NULL;
    s_state = -1;
    const char* path = getenv("PS3_ANDROID_BRIDGE");
    if (!path || !*path) return NULL;
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "[EU bridge] cannot open %s (errno %d) -- no screen/input\n", path, errno);
        return NULL;
    }
    struct stat st;
    size_t want = EU_BRIDGE_HDR + (size_t)EU_BRIDGE_MAX_W * EU_BRIDGE_MAX_H * 4u;
    if (fstat(fd, &st) != 0 || (size_t)st.st_size < want) {
        fprintf(stderr, "[EU bridge] %s is too small (%lld < %zu)\n", path,
                (long long)st.st_size, want);
        close(fd);
        return NULL;
    }
    void* m = mmap(NULL, want, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (m == MAP_FAILED) {
        fprintf(stderr, "[EU bridge] mmap failed (errno %d)\n", errno);
        return NULL;
    }
    eu_bridge_hdr* h = (eu_bridge_hdr*)m;
    if (h->magic != EU_BRIDGE_MAGIC || h->version != EU_BRIDGE_VERSION) {
        fprintf(stderr, "[EU bridge] bad header (magic 0x%08X version %u)\n", h->magic, h->version);
        munmap(m, want);
        return NULL;
    }
    s_map = (uint8_t*)m;
    s_size = want;
    s_state = 1;
    atomic_store(&h->runner_alive, 1u);
    fprintf(stderr, "[EU bridge] mapped %s: Android screen + controller channel ready\n", path);
    return h;
}

/* Called by cellPad (weak reference) on every host poll. Returns 1 and fills
 * the outputs when the app reports a connected controller. */
int eu_bridge_pad(unsigned* buttons, unsigned char axes[6])
{
    eu_bridge_hdr* h = bridge();
    if (!h) return 0;
    if (!atomic_load_explicit(&h->pad_connected, memory_order_acquire)) return 0;
    *buttons = atomic_load_explicit(&h->buttons, memory_order_relaxed) & 0xFFFFu;
    for (int i = 0; i < 6; i++) axes[i] = atomic_load_explicit(&h->axes[i], memory_order_relaxed);
    static int said;
    if (!said) {
        said = 1;
        fprintf(stderr, "[EU bridge] Android controller connected on pad port 0\n");
    }
    /* Log button changes (bounded) so a boot log shows that input arrived. */
    static unsigned last, logged;
    if (*buttons != last && logged < 200) {
        logged++;
        fprintf(stderr, "[EU bridge] pad buttons 0x%04X\n", *buttons);
    }
    last = *buttons;
    return 1;
}

static uint64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

/* Publish rows that are already R,G,B,A bytes (the Vulkan renderer's readback). */
void eu_bridge_frame_rgba(const uint8_t* rgba, unsigned w, unsigned h, unsigned pitch)
{
    eu_bridge_hdr* b = bridge();
    if (!b || !rgba || w == 0 || h == 0 || w > EU_BRIDGE_MAX_W || h > EU_BRIDGE_MAX_H) return;
    uint32_t seq = atomic_load_explicit(&b->frame_seq, memory_order_relaxed);
    atomic_store_explicit(&b->frame_seq, seq + 1u, memory_order_release);
    atomic_thread_fence(memory_order_seq_cst);
    uint8_t* dst = s_map + EU_BRIDGE_HDR;
    for (unsigned y = 0; y < h; y++) {
        uint8_t* row = dst + (size_t)y * w * 4u;
        memcpy(row, rgba + (size_t)y * pitch, (size_t)w * 4u);
        for (unsigned x = 0; x < w; x++) row[x * 4u + 3u] = 0xFF;   /* opaque on screen */
    }
    atomic_store_explicit(&b->frame_w, w, memory_order_relaxed);
    atomic_store_explicit(&b->frame_h, h, memory_order_relaxed);
    atomic_thread_fence(memory_order_seq_cst);
    atomic_store_explicit(&b->frame_seq, seq + 2u, memory_order_release);
    uint32_t n_pub = atomic_fetch_add(&b->frames_published, 1u) + 1u;
    if (n_pub == 1)
        fprintf(stderr, "[EU bridge] first frame published to the Android screen (%ux%u, GPU)\n", w, h);
}

/* Called by the RSX backend on each present. fb holds 0x00RRGGBB pixels.
 * Rate-limited to ~30 publishes/s so the copy stays off the critical path. */
void eu_bridge_frame(const uint32_t* fb, unsigned w, unsigned h)
{
    eu_bridge_hdr* b = bridge();
    if (!b || !fb || w == 0 || h == 0 || w > EU_BRIDGE_MAX_W || h > EU_BRIDGE_MAX_H) return;
    static uint64_t next;
    uint64_t t = now_ms();
    if (t < next) return;
    next = t + 33;
    uint32_t seq = atomic_load_explicit(&b->frame_seq, memory_order_relaxed);
    atomic_store_explicit(&b->frame_seq, seq + 1u, memory_order_release); /* odd: writing */
    atomic_thread_fence(memory_order_seq_cst);
    uint32_t* dst = (uint32_t*)(s_map + EU_BRIDGE_HDR);
    const size_t n = (size_t)w * h;
    for (size_t i = 0; i < n; i++) {
        const uint32_t p = fb[i];
        /* 0x00RRGGBB -> bytes R,G,B,A (Android ARGB_8888 memory order). */
        dst[i] = 0xFF000000u | ((p >> 16) & 0xFFu) | (p & 0xFF00u) | ((p & 0xFFu) << 16);
    }
    atomic_store_explicit(&b->frame_w, w, memory_order_relaxed);
    atomic_store_explicit(&b->frame_h, h, memory_order_relaxed);
    atomic_thread_fence(memory_order_seq_cst);
    atomic_store_explicit(&b->frame_seq, seq + 2u, memory_order_release); /* even: stable */
    uint32_t n_pub = atomic_fetch_add(&b->frames_published, 1u) + 1u;
    if (n_pub == 1)
        fprintf(stderr, "[EU bridge] first frame published to the Android screen (%ux%u)\n", w, h);
}
