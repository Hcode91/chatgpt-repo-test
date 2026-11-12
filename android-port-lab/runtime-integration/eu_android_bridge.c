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
 *  20  u8  lx ly rx ry l2 r2     52 u32 capture_req  (app bumps it: capture a frame)
 *                                56 u32 capture_done (runner: last capture written)
 *                                64 pixels, RGBA8888 rows (max 1280x720)
 *
 * Contains no game code. Everything here is a no-op when the variable is unset.
 */
#define _GNU_SOURCE 1
#include <errno.h>
#include <sys/resource.h>
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
    _Atomic uint32_t capture_req, capture_done;
} eu_bridge_hdr;

#include <stddef.h>
_Static_assert(offsetof(eu_bridge_hdr, buttons) == 16, "layout");
_Static_assert(offsetof(eu_bridge_hdr, axes) == 20, "layout");
_Static_assert(offsetof(eu_bridge_hdr, frame_seq) == 32, "layout");
_Static_assert(offsetof(eu_bridge_hdr, runner_alive) == 48, "layout");
_Static_assert(offsetof(eu_bridge_hdr, capture_req) == 52, "layout");
_Static_assert(offsetof(eu_bridge_hdr, capture_done) == 56, "layout");
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

/* Frame-capture request from the app's CAP button: returns the request number
 * when one is outstanding, else 0. The renderer calls eu_bridge_capture_done(n)
 * after writing the files. */
unsigned eu_bridge_capture_pending(void)
{
    eu_bridge_hdr* b = bridge();
    if (!b) return 0;
    const uint32_t req = atomic_load_explicit(&b->capture_req, memory_order_acquire);
    return req != atomic_load_explicit(&b->capture_done, memory_order_relaxed) ? req : 0u;
}

void eu_bridge_capture_done(unsigned n)
{
    eu_bridge_hdr* b = bridge();
    if (b) atomic_store_explicit(&b->capture_done, n, memory_order_release);
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

/* ======================================================================
 * Diagnostics for on-device boot logs (only when launched by the app,
 * i.e. PS3_ANDROID_BRIDGE is set). Both start before main():
 *  - crash reporter: signal, fault address, pc/lr/sp, thread, and a
 *    symbolised backtrace. Installed first, so upstream's own guest-pointer
 *    fault handlers (ppu_loader.cpp, win32_compat.c), which chain to their
 *    predecessor, still run and then reach this one.
 *  - CPU sampler: every 10 s, the busiest threads by CPU time.
 * ====================================================================== */
#include <dirent.h>
#include <sched.h>
#include <dlfcn.h>
#include <pthread.h>
#include <signal.h>
#include <sys/prctl.h>
#include <ucontext.h>
#include <unwind.h>

/* ---- own symbol table: the runner's .symtab (kept in the APK) names the
 * internal functions dladdr cannot see. Loaded once before main(), so the
 * crash handler only reads memory. */
#include <elf.h>
typedef struct { uintptr_t addr, size; uint32_t name; } eu_fsym;
static eu_fsym* s_syms;
static size_t s_nsyms;
static char* s_strtab;
static uintptr_t s_self_base;
static uintptr_t s_self_end;   /* end of the highest function, relative */
/* executable sections, so an address no symbol covers can still be placed
 * (e.g. .plt stubs or hand-written assembly) */
typedef struct { uintptr_t addr, size; char name[24]; } eu_sect;
static eu_sect s_sects[48];
static int s_nsects;

static int eu_fsym_cmp(const void* a, const void* b)
{
    const eu_fsym* x = (const eu_fsym*)a;
    const eu_fsym* y = (const eu_fsym*)b;
    return x->addr < y->addr ? -1 : x->addr > y->addr;
}

static void eu_load_symtab(void)
{
    Dl_info di;
    if (!dladdr((void*)&eu_load_symtab, &di) || !di.dli_fname) return;
    s_self_base = (uintptr_t)di.dli_fbase;
    FILE* f = fopen(di.dli_fname, "rb");
    if (!f) return;
    Elf64_Ehdr eh;
    Elf64_Shdr* sh = NULL;
    if (fread(&eh, sizeof eh, 1, f) != 1 || memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0 ||
        eh.e_ident[EI_CLASS] != ELFCLASS64 || eh.e_shentsize != sizeof(Elf64_Shdr) || !eh.e_shnum)
        goto out;
    sh = (Elf64_Shdr*)calloc(eh.e_shnum, sizeof *sh);
    if (!sh || fseeko(f, (off_t)eh.e_shoff, SEEK_SET) != 0 || fread(sh, sizeof *sh, eh.e_shnum, f) != eh.e_shnum)
        goto out;
    if (eh.e_shstrndx < eh.e_shnum) {
        const Elf64_Shdr* ss = &sh[eh.e_shstrndx];
        char* names = (char*)malloc(ss->sh_size + 1);
        if (names && fseeko(f, (off_t)ss->sh_offset, SEEK_SET) == 0 && fread(names, 1, ss->sh_size, f) == ss->sh_size) {
            names[ss->sh_size] = 0;
            for (unsigned i = 0; i < eh.e_shnum && s_nsects < 48; i++) {
                if (!(sh[i].sh_flags & SHF_EXECINSTR) || !sh[i].sh_size || sh[i].sh_name >= ss->sh_size) continue;
                s_sects[s_nsects].addr = sh[i].sh_addr;
                s_sects[s_nsects].size = sh[i].sh_size;
                snprintf(s_sects[s_nsects].name, sizeof s_sects[s_nsects].name, "%s", names + sh[i].sh_name);
                s_nsects++;
            }
        }
        free(names);
    }
    for (unsigned i = 0; i < eh.e_shnum; i++) {
        if (sh[i].sh_type != SHT_SYMTAB || sh[i].sh_link >= eh.e_shnum) continue;
        const Elf64_Shdr* st = &sh[sh[i].sh_link];
        const size_t n = sh[i].sh_size / sizeof(Elf64_Sym);
        Elf64_Sym* syms = (Elf64_Sym*)malloc(sh[i].sh_size);
        s_strtab = (char*)malloc(st->sh_size + 1);
        s_syms = (eu_fsym*)malloc(n * sizeof(eu_fsym));
        if (!syms || !s_strtab || !s_syms) { free(syms); goto out; }
        if (fseeko(f, (off_t)sh[i].sh_offset, SEEK_SET) != 0 || fread(syms, 1, sh[i].sh_size, f) != sh[i].sh_size ||
            fseeko(f, (off_t)st->sh_offset, SEEK_SET) != 0 || fread(s_strtab, 1, st->sh_size, f) != st->sh_size) {
            free(syms);
            goto out;
        }
        s_strtab[st->sh_size] = 0;
        for (size_t k = 0; k < n; k++) {
            const int ty = ELF64_ST_TYPE(syms[k].st_info);
            if ((ty != STT_FUNC && ty != STT_NOTYPE) || !syms[k].st_value) continue;
            if (syms[k].st_name >= st->sh_size) continue;
            if (ty == STT_NOTYPE) {
                /* assembly labels count; mapping symbols ($x/$d) and locals (.L) don't */
                const char* nm = s_strtab + syms[k].st_name;
                if (!nm[0] || nm[0] == '$' || nm[0] == '.') continue;
                if (syms[k].st_shndx == SHN_UNDEF || syms[k].st_shndx >= eh.e_shnum ||
                    !(sh[syms[k].st_shndx].sh_flags & SHF_EXECINSTR)) continue;
            }
            s_syms[s_nsyms].addr = syms[k].st_value;
            s_syms[s_nsyms].size = syms[k].st_size;
            s_syms[s_nsyms].name = syms[k].st_name;
            if (syms[k].st_value + syms[k].st_size > s_self_end) s_self_end = syms[k].st_value + syms[k].st_size;
            s_nsyms++;
        }
        free(syms);
        qsort(s_syms, s_nsyms, sizeof(eu_fsym), eu_fsym_cmp);
        break;
    }
out:
    free(sh);
    fclose(f);
    fprintf(stderr, "[EU bridge] symbol table: %zu functions%s\n", s_nsyms,
            s_nsyms ? "" : " (stripped -- crash/hot-spot names limited to exported symbols)");
}

/* Where an unnamed address sits: section, nearest symbol below it. */
static void eu_place(uintptr_t pc, char* out, size_t cap)
{
    out[0] = 0;
    if (pc < s_self_base) return;
    const uintptr_t rel = pc - s_self_base;
    const char* sect = "?";
    for (int i = 0; i < s_nsects; i++)
        if (rel >= s_sects[i].addr && rel < s_sects[i].addr + s_sects[i].size) { sect = s_sects[i].name; break; }
    size_t lo = 0, hi = s_nsyms;
    while (lo < hi) {
        size_t mid = (lo + hi) / 2;
        if (s_syms[mid].addr <= rel) lo = mid + 1; else hi = mid;
    }
    if (lo)
        snprintf(out, cap, "[%s, after %s+0x%lx]", sect, s_strtab + s_syms[lo - 1].name,
                 (unsigned long)(rel - s_syms[lo - 1].addr));
    else
        snprintf(out, cap, "[%s]", sect);
}

/* Name of the function containing pc in this binary, or NULL. */
static const char* eu_own_symbol(uintptr_t pc, uintptr_t* off)
{
    if (!s_nsyms || pc < s_self_base) return NULL;
    const uintptr_t rel = pc - s_self_base;
    if (rel >= s_self_end) return NULL;   /* another module (libc, vdso, ...) */
    size_t lo = 0, hi = s_nsyms;
    while (lo < hi) {
        size_t mid = (lo + hi) / 2;
        if (s_syms[mid].addr <= rel) lo = mid + 1; else hi = mid;
    }
    if (!lo) return NULL;
    const eu_fsym* s = &s_syms[lo - 1];
    if (s->size && rel >= s->addr + s->size) return NULL;
    *off = rel - s->addr;
    return s_strtab + s->name;
}

static void eu_sym(const char* tag, uintptr_t pc)
{
    {
        uintptr_t off = 0;
        const char* own = eu_own_symbol(pc, &off);
        if (own) {
            fprintf(stderr, "[crash] %s 0x%016lx %s+0x%lx (libps3eu.so+0x%lx)\n", tag, (unsigned long)pc, own,
                    (unsigned long)off, (unsigned long)(pc - s_self_base));
            return;
        }
    }
    Dl_info di;
    memset(&di, 0, sizeof di);
    if (pc && dladdr((void*)pc, &di) && di.dli_fname) {
        const char* f = strrchr(di.dli_fname, '/');
        f = f ? f + 1 : di.dli_fname;
        if (di.dli_sname)
            fprintf(stderr, "[crash] %s 0x%016lx %s+0x%lx (%s+0x%lx)\n", tag, (unsigned long)pc, di.dli_sname,
                    (unsigned long)(pc - (uintptr_t)di.dli_saddr), f, (unsigned long)(pc - (uintptr_t)di.dli_fbase));
        else
            fprintf(stderr, "[crash] %s 0x%016lx (%s+0x%lx)\n", tag, (unsigned long)pc, f,
                    (unsigned long)(pc - (uintptr_t)di.dli_fbase));
    } else {
        fprintf(stderr, "[crash] %s 0x%016lx (unknown module)\n", tag, (unsigned long)pc);
    }
}

typedef struct { int n; } eu_bt_state;

static _Unwind_Reason_Code eu_bt_step(struct _Unwind_Context* ctx, void* arg)
{
    eu_bt_state* st = (eu_bt_state*)arg;
    uintptr_t pc = (uintptr_t)_Unwind_GetIP(ctx);
    if (!pc) return _URC_END_OF_STACK;
    char tag[16];
    snprintf(tag, sizeof tag, "#%02d", st->n);
    eu_sym(tag, pc);
    return (++st->n >= 40) ? _URC_END_OF_STACK : _URC_NO_REASON;
}

static volatile sig_atomic_t s_in_crash;

static void eu_crash(int sig, siginfo_t* si, void* uctx)
{
    if (!s_in_crash) {
        s_in_crash = 1;
        char tname[17] = {0};
        prctl(PR_GET_NAME, tname, 0, 0, 0);
        fprintf(stderr, "\n[crash] ===== signal %d (%s) code %d, fault address %p, thread '%s' tid %d =====\n",
                sig, sig == SIGSEGV ? "SIGSEGV" : sig == SIGBUS ? "SIGBUS" : sig == SIGILL ? "SIGILL"
                   : sig == SIGFPE ? "SIGFPE" : sig == SIGABRT ? "SIGABRT" : "?",
                si ? si->si_code : 0, si ? si->si_addr : NULL, tname, (int)gettid());
#if defined(__aarch64__)
        if (uctx) {
            const ucontext_t* uc = (const ucontext_t*)uctx;
            eu_sym("pc ", (uintptr_t)uc->uc_mcontext.pc);
            eu_sym("lr ", (uintptr_t)uc->uc_mcontext.regs[30]);
            fprintf(stderr, "[crash] sp 0x%016lx x0 0x%016lx x1 0x%016lx x2 0x%016lx\n",
                    (unsigned long)uc->uc_mcontext.sp, (unsigned long)uc->uc_mcontext.regs[0],
                    (unsigned long)uc->uc_mcontext.regs[1], (unsigned long)uc->uc_mcontext.regs[2]);
        }
#endif
        eu_bt_state st = {0};
        _Unwind_Backtrace(eu_bt_step, &st);
        fprintf(stderr, "[crash] ===== end of report =====\n");
        fflush(stderr);
    }
    /* Do not swallow it: restore the default action and let the fault re-run
     * (synchronous signals) or re-raise (abort), so the process dies as before. */
    struct sigaction dfl;
    memset(&dfl, 0, sizeof dfl);
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(sig, &dfl, NULL);
    if (sig == SIGABRT) raise(sig);
}

static uint64_t eu_ticks(const char* stat_line)
{
    /* Fields after ") ": state ppid ... utime(14) stime(15) counted from 1. */
    const char* p = strrchr(stat_line, ')');
    if (!p) return 0;
    unsigned long long ut = 0, stt = 0;
    if (sscanf(p + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu", &ut, &stt) != 2) return 0;
    return ut + stt;
}

/* ---- hot-spot sampling: interrupt one busy thread with a real-time signal
 * and record where it was. */
#include <sys/syscall.h>
#define EU_PROF_SIG (SIGRTMIN + 9)
static volatile uintptr_t s_prof_pc, s_prof_lr;
static volatile int s_prof_done;

static void eu_prof_handler(int sig, siginfo_t* si, void* uctx)
{
    (void)sig; (void)si;
#if defined(__aarch64__)
    const ucontext_t* uc = (const ucontext_t*)uctx;
    s_prof_pc = (uintptr_t)uc->uc_mcontext.pc;
    s_prof_lr = (uintptr_t)uc->uc_mcontext.regs[30];
#else
    (void)uctx;
    s_prof_pc = s_prof_lr = 0;
#endif
    __atomic_store_n(&s_prof_done, 1, __ATOMIC_RELEASE);
}

typedef struct { const void* sym; const char* name; const char* mod; uintptr_t off, lr; int hits; } eu_hot;

static const char* eu_basename(const char* p)
{
    if (!p) return "?";
    const char* f = strrchr(p, '/');
    return f ? f + 1 : p;
}

/* Whole-run totals for the busiest thread: self time by function, and the
 * function it was called from, so the rewrite targets are the ones that add
 * up over minutes rather than the noise of one 40-sample window. */
enum { EU_TOT_MAX = 256 };
typedef struct { const void* key; char name[72]; int hits; } eu_tot;
static eu_tot s_tot_self[EU_TOT_MAX], s_tot_from[EU_TOT_MAX];
static int s_tot_nself, s_tot_nfrom, s_tot_samples;

static void eu_tot_add(eu_tot* t, int* n, const void* key, const char* name, uintptr_t off, const char* mod)
{
    for (int i = 0; i < *n; i++) if (t[i].key == key) { t[i].hits++; return; }
    if (*n >= EU_TOT_MAX) return;
    t[*n].key = key;
    if (off) snprintf(t[*n].name, sizeof t[*n].name, "%s+0x%lx", mod, (unsigned long)off);
    else snprintf(t[*n].name, sizeof t[*n].name, "%s", name);
    t[*n].hits = 1;
    (*n)++;
}

static void eu_tot_print(const char* tag, eu_tot* t, int n)
{
    if (!s_tot_samples) return;
    int idx[EU_TOT_MAX];
    for (int i = 0; i < n; i++) idx[i] = i;
    for (int a = 0; a < n; a++)
        for (int b = a + 1; b < n; b++)
            if (t[idx[b]].hits > t[idx[a]].hits) { int x = idx[a]; idx[a] = idx[b]; idx[b] = x; }
    char line[1400];
    int o = snprintf(line, sizeof line, "[%s] %d samples so far:", tag, s_tot_samples);
    for (int k = 0; k < n && k < 15 && o < (int)sizeof line - 100; k++)
        o += snprintf(line + o, sizeof line - o, " %s %.1f%%", t[idx[k]].name,
                      100.0 * t[idx[k]].hits / s_tot_samples);
    fprintf(stderr, "%s\n", line);
}

static void eu_profile_thread(int tid, const char* label, int total)
{
    enum { SAMPLES = 40, MAXSYM = 64 };
    eu_hot hot[MAXSYM];
    int nh = 0, taken = 0;
    for (int k = 0; k < SAMPLES; k++) {
        __atomic_store_n(&s_prof_done, 0, __ATOMIC_RELEASE);
        if (syscall(SYS_tgkill, getpid(), tid, EU_PROF_SIG) != 0) return;   /* thread gone */
        for (int w = 0; w < 200 && !__atomic_load_n(&s_prof_done, __ATOMIC_ACQUIRE); w++) usleep(100);
        if (!__atomic_load_n(&s_prof_done, __ATOMIC_ACQUIRE)) continue;
        taken++;
        Dl_info di;
        memset(&di, 0, sizeof di);
        const uintptr_t pc = s_prof_pc;
        const uintptr_t lr = s_prof_lr;
        const void* key = (const void*)pc;
        const char* name = "?";
        const char* mod = "?";
        uintptr_t off = pc;
        uintptr_t own_off = 0;
        const char* own = eu_own_symbol(pc, &own_off);
        if (own) { key = (const void*)(pc - own_off); name = own; off = 0; }
        else if (dladdr((void*)pc, &di)) {
            if (di.dli_sname) { key = di.dli_saddr; name = di.dli_sname; off = 0; }
            else if (di.dli_fbase) {
                key = (const void*)(pc & ~(uintptr_t)0xFFF);
                off = pc - (uintptr_t)di.dli_fbase;
                mod = eu_basename(di.dli_fname);
            }
        }
        int j = 0;
        while (j < nh && hot[j].sym != key) j++;
        if (j == nh && nh < MAXSYM) { hot[nh].sym = key; hot[nh].name = name; hot[nh].off = off; hot[nh].mod = mod; hot[nh].lr = lr; hot[nh].hits = 0; nh++; }
        if (j < nh) hot[j].hits++;
        if (total) {
            s_tot_samples++;
            eu_tot_add(s_tot_self, &s_tot_nself, key, name, off, mod);
            uintptr_t lo = 0;
            const char* caller = eu_own_symbol(lr, &lo);
            Dl_info ci;
            memset(&ci, 0, sizeof ci);
            if (caller) eu_tot_add(s_tot_from, &s_tot_nfrom, (const void*)(lr - lo), caller, 0, "");
            else if (lr && dladdr((void*)lr, &ci) && ci.dli_sname) eu_tot_add(s_tot_from, &s_tot_nfrom, ci.dli_saddr, ci.dli_sname, 0, "");
            else eu_tot_add(s_tot_from, &s_tot_nfrom, (const void*)(uintptr_t)1, "(unknown)", 0, "");
        }
        usleep(5000);
    }
    for (int a = 0; a < nh; a++)
        for (int b = a + 1; b < nh; b++)
            if (hot[b].hits > hot[a].hits) { eu_hot t = hot[a]; hot[a] = hot[b]; hot[b] = t; }
    char line[1024];
    int o = snprintf(line, sizeof line, "[hot] %s tid %d (%d samples):", label, tid, taken);
    for (int a = 0; a < nh && a < 6 && o < (int)sizeof line - 96; a++) {
        if (hot[a].off) o += snprintf(line + o, sizeof line - o, " %s+0x%lx x%d", hot[a].mod, (unsigned long)hot[a].off, hot[a].hits);
        else o += snprintf(line + o, sizeof line - o, " %s x%d", hot[a].name, hot[a].hits);
    }
    fprintf(stderr, "%s\n", line);
    /* unnamed hot spots: which library, and who called in */
    for (int a = 0; a < nh && a < 6; a++) {
        if (!hot[a].off || hot[a].hits < 4) continue;
        char where[160] = "", from[200];
        Dl_info pi;
        memset(&pi, 0, sizeof pi);
        const uintptr_t pc = (uintptr_t)hot[a].sym;   /* page of the first sample */
        if (dladdr((void*)pc, &pi) && pi.dli_fbase == (void*)s_self_base) eu_place(s_self_base + hot[a].off, where, sizeof where);
        uintptr_t lo = 0;
        const char* caller = eu_own_symbol(hot[a].lr, &lo);
        Dl_info li;
        memset(&li, 0, sizeof li);
        if (caller) snprintf(from, sizeof from, "%s+0x%lx", caller, (unsigned long)lo);
        else if (hot[a].lr && dladdr((void*)hot[a].lr, &li) && li.dli_fbase)
            snprintf(from, sizeof from, "%s+0x%lx (%s)", eu_basename(li.dli_fname),
                     (unsigned long)(hot[a].lr - (uintptr_t)li.dli_fbase), li.dli_sname ? li.dli_sname : "no symbol");
        else snprintf(from, sizeof from, "0x%lx (no module)", (unsigned long)hot[a].lr);
        fprintf(stderr, "[hot]   %s+0x%lx%s%s called from %s\n", hot[a].mod, (unsigned long)hot[a].off,
                where[0] ? " " : "", where, from);
    }
}

#define EU_MAX_THREADS 512
typedef struct { int tid; uint64_t ticks; char name[20]; } eu_tsample;

static int eu_sample(eu_tsample* out, int cap)
{
    DIR* d = opendir("/proc/self/task");
    if (!d) return 0;
    int n = 0;
    struct dirent* e;
    while ((e = readdir(d)) && n < cap) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        char path[64], buf[512];
        snprintf(path, sizeof path, "/proc/self/task/%s/stat", e->d_name);
        FILE* f = fopen(path, "r");
        if (!f) continue;
        size_t got = fread(buf, 1, sizeof buf - 1, f);
        fclose(f);
        buf[got] = 0;
        const char* l = strchr(buf, '(');
        const char* r = strrchr(buf, ')');
        out[n].tid = atoi(e->d_name);
        out[n].ticks = eu_ticks(buf);
        out[n].name[0] = 0;
        if (l && r && r > l) {
            size_t len = (size_t)(r - l - 1);
            if (len > sizeof out[n].name - 1) len = sizeof out[n].name - 1;
            memcpy(out[n].name, l + 1, len);
            out[n].name[len] = 0;
        }
        n++;
    }
    closedir(d);
    return n;
}

/* Why a busy thread is not getting CPU: is the phone slowing its cores (heat), or are too many threads
 * waiting for the same cores? Every 10 s:
 *  [cores]   each core's current and currently allowed top speed against its design top speed (a lowered
 *            allowed speed is the phone throttling), plus whatever temperatures the app may read;
 *  [waiting] for the busiest threads: the core each last ran on and the share of the 10 s it spent ready
 *            to run but waiting for a core (from the kernel's own per-thread scheduling statistics). */
static long eu_read_long(const char* path)
{
    FILE* f = fopen(path, "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}
static void eu_cores_report(const eu_tsample* cur, const int* idx, const uint64_t* dt, int nc)
{
    char line[1024];
    int off = snprintf(line, sizeof line, "[cores] MHz now/allowed/design:");
    long ncpu = sysconf(_SC_NPROCESSORS_CONF);
    if (ncpu > 16) ncpu = 16;
    for (long c = 0; c < ncpu && off < (int)sizeof line - 40; c++) {
        char path[96];
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/scaling_cur_freq", c);
        const long now = eu_read_long(path);
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/scaling_max_freq", c);
        const long cap = eu_read_long(path);
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", c);
        const long top = eu_read_long(path);
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/online", c);
        const long on = eu_read_long(path);
        if (on == 0) off += snprintf(line + off, sizeof line - off, " cpu%ld OFF", c);
        else off += snprintf(line + off, sizeof line - off, " cpu%ld %ld/%ld/%ld", c, now / 1000, cap / 1000, top / 1000);
    }
    const long batt = eu_read_long("/sys/class/power_supply/battery/temp");
    if (batt > 0) off += snprintf(line + off, sizeof line - off, " | battery %.1f C", batt / 10.0);
    int shown = 0;
    for (int z = 0; z < 100 && shown < 6 && off < (int)sizeof line - 48; z++) {
        char path[96], type[40] = "";
        snprintf(path, sizeof path, "/sys/class/thermal/thermal_zone%d/type", z);
        FILE* f = fopen(path, "r");
        if (!f) { if (z > 8 && !shown) break; continue; }
        if (!fgets(type, sizeof type, f)) type[0] = 0;
        fclose(f);
        type[strcspn(type, "\n")] = 0;
        if (!strstr(type, "cpu") && !strstr(type, "gpu") && !strstr(type, "skin") && !strstr(type, "soc")) continue;
        snprintf(path, sizeof path, "/sys/class/thermal/thermal_zone%d/temp", z);
        const long t = eu_read_long(path);
        if (t <= 0) continue;
        off += snprintf(line + off, sizeof line - off, " %s %.0f C", type, t > 1000 ? t / 1000.0 : (double)t);
        shown++;
    }
    fprintf(stderr, "%s\n", line);

    static struct { int tid; unsigned long long wait_ns; } prev[16];
    static int nprev;
    struct { int tid; unsigned long long wait_ns; } now[16];
    int nnow = 0;
    off = snprintf(line, sizeof line, "[waiting] ready but waiting for a core, share of the last 10 s:");
    for (int k = 0; k < nc && k < 8 && off < (int)sizeof line - 64; k++) {
        if (!dt[idx[k]]) break;
        const int tid = cur[idx[k]].tid;
        char path[64], buf[512];
        unsigned long long run_ns = 0, wait_ns = 0;
        snprintf(path, sizeof path, "/proc/self/task/%d/schedstat", tid);
        FILE* f = fopen(path, "r");
        if (!f) continue;
        const int got = fscanf(f, "%llu %llu", &run_ns, &wait_ns);
        fclose(f);
        if (got != 2) continue;
        int core = -1;
        snprintf(path, sizeof path, "/proc/self/task/%d/stat", tid);
        f = fopen(path, "r");
        if (f) {
            const size_t n = fread(buf, 1, sizeof buf - 1, f);
            fclose(f);
            buf[n] = 0;
            const char* q = strrchr(buf, ')');
            /* field 39 (processor) is the 37th field after the closing bracket */
            for (int fld = 0; q && fld < 37; fld++) { q = strchr(q + 1, ' '); }
            if (q) core = atoi(q + 1);
        }
        unsigned long long before = wait_ns;
        for (int j = 0; j < nprev; j++) if (prev[j].tid == tid) { before = prev[j].wait_ns; break; }
        errno = 0;
        const int nice_now = getpriority(PRIO_PROCESS, (id_t)tid);
        off += snprintf(line + off, sizeof line - off, " %s/%d %.0f%% (cpu%d, pri %d)", cur[idx[k]].name, tid,
                        (double)(wait_ns - before) / 1e8, core, errno ? 99 : nice_now);
        now[nnow].tid = tid;
        now[nnow].wait_ns = wait_ns;
        nnow++;
    }
    memcpy(prev, now, sizeof now);
    nprev = nnow;
    fprintf(stderr, "%s\n", line);
}

/* The RSX FIFO thread is pinned to the fastest core, but everything else was still allowed there and it
 * spent 32-46% of its time waiting for it (boot-20261111-090128). Take that core out of every other
 * thread's allowed set. Threads start all the time, so this is repeated. EU_PRIME_EXCLUSIVE=0 = off. */
extern int g_eu_prime_tid[4] __attribute__((weak));
extern int g_eu_prime_n __attribute__((weak));
/* Settings the in-run comparison ([ab]) has switched off right now, one bit each. Read (weakly) by the
 * patched upstream files and the Vulkan backend as well. */
volatile int g_eu_live_off;
#define EU_LIVE_CORE_SPLIT 1
#define EU_LIVE_MAIN_BOOST 2
#define EU_LIVE_SPURS_PARK 4
#define EU_LIVE_INDEX_FAST 8
#define EU_LIVE_CB_USED 16
#define EU_LIVE_LL_YIELD 32
#define EU_LIVE_SPU_NICE 64
#define EU_LIVE_PERF_HINT 128
#define EU_LIVE_SPU_SERIAL 256
#define EU_LIVE_SHORT_SPIN 512
#define EU_LIVE_N205 1024
#define EU_LIVE_PPU_INLINE 2048
static int s_main_nice;   /* 0 = main thread not boosted */
static void eu_prime_sweep(void)
{
    static int on = -1;
    static cpu_set_t prime;
    static int have_prime;
    static unsigned long long moved, sweeps;
    if (on < 0) {
        const char* e = getenv("EU_PRIME_EXCLUSIVE");
        on = (e && *e == '0') ? 0 : 1;
        long ncpu = sysconf(_SC_NPROCESSORS_CONF);
        if (ncpu > 32) ncpu = 32;
        long top = 0, f[32];
        for (long c = 0; c < ncpu; c++) {
            char path[96];
            snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", c);
            f[c] = eu_read_long(path);
            if (f[c] > top) top = f[c];
        }
        CPU_ZERO(&prime);
        int nprime = 0;
        for (long c = 0; c < ncpu; c++) if (top > 0 && f[c] == top) { CPU_SET((int)c, &prime); nprime++; }
        have_prime = nprime > 0 && nprime < ncpu;
        fprintf(stderr, "[boost] fastest core reserved for the RSX FIFO thread: %s (EU_PRIME_EXCLUSIVE), %d such core(s)\n",
                on && have_prime ? "on" : "off", nprime);
    }
    {   /* The game's main thread was ready to run but waiting for a core 58-66% of the time while using a
         * third of one (boot-20261111-100119): everything else is paced by it. Give it priority over the
         * SPU threads. EU_MAIN_BOOST=0 = leave it; EU_MAIN_NICE sets the value (default -14). */
        static int done;
        if (!done) {
            done = 1;
            const char* e = getenv("EU_MAIN_BOOST");
            const char* n = getenv("EU_MAIN_NICE");
            if (!(e && *e == '0')) {
                int nv = (n && *n) ? atoi(n) : -14;   /* working SPU threads run at -10 (boot-20261111-115416): -6 and -10 changed nothing */
                if (nv < -19) nv = -19;
                if (nv > 0) nv = 0;
                s_main_nice = nv;
                const int r = setpriority(PRIO_PROCESS, (id_t)getpid(), nv);
                fprintf(stderr, "[boost] game main thread (tid %d): priority %d %s (EU_MAIN_BOOST)\n", (int)getpid(), nv,
                        r == 0 ? "set" : strerror(errno));
            }
        }
    }
    if (!on || !have_prime || !&g_eu_prime_n || g_eu_prime_n <= 0) return;
    DIR* d = opendir("/proc/self/task");
    if (!d) return;
    struct dirent* e;
    unsigned long long now_moved = 0;
    while ((e = readdir(d))) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        const int tid = atoi(e->d_name);
        int is_prime = 0;
        for (int k = 0; k < g_eu_prime_n && k < 4; k++) if (g_eu_prime_tid[k] == tid) is_prime = 1;
        if (is_prime) continue;
        cpu_set_t set;
        CPU_ZERO(&set);
        if (sched_getaffinity(tid, sizeof set, &set) != 0) continue;
        int touched = 0, left = 0;
        for (int c = 0; c < 32; c++) {
            if (!CPU_ISSET(c, &set)) continue;
            if (CPU_ISSET(c, &prime)) { CPU_CLR(c, &set); touched = 1; }
            else left++;
        }
        if (touched && left && sched_setaffinity(tid, sizeof set, &set) == 0) now_moved++;
    }
    closedir(d);
    moved += now_moved;
    if (now_moved && (++sweeps <= 3 || sweeps % 20 == 0))
        fprintf(stderr, "[boost] kept %llu more thread(s) off the fastest core (%llu so far)\n", now_moved, moved);
}

/* ---- what the game's main thread waits for ----
 * The main thread is busy only about a third of the time; for the rest it is ready but waiting for a core
 * (17-29%) or blocked (about 40%), and nothing so far shows what on. About 10 times a second: read whether
 * it is asleep, interrupt it, and walk its call chain (frame pointers, read with process_vm_readv so a bad
 * pointer is an error and not a crash). [main-wait] every 10 s: share of samples asleep, and the call
 * chains it was asleep in, most frequent first; [main-wait-total] every minute for the whole run.
 * EU_MAIN_WAIT=0 = off. */
#include <sys/uio.h>
#define EU_WAIT_SIG (SIGRTMIN + 10)
#define EU_WAIT_FRAMES 14
static volatile uintptr_t s_wait_pcs[EU_WAIT_FRAMES];
static volatile int s_wait_n, s_wait_done;
static int eu_wait_peek(uintptr_t addr, uintptr_t* out2)
{
    struct iovec l = { out2, 2 * sizeof(uintptr_t) }, r = { (void*)addr, 2 * sizeof(uintptr_t) };
    return syscall(SYS_process_vm_readv, getpid(), &l, 1, &r, 1, 0) == (long)(2 * sizeof(uintptr_t));
}
static void eu_wait_handler(int sig, siginfo_t* si, void* uctx)
{
    (void)sig; (void)si;
    int n = 0;
#if defined(__aarch64__)
    const int saved = errno;
    const ucontext_t* uc = (const ucontext_t*)uctx;
    s_wait_pcs[n++] = (uintptr_t)uc->uc_mcontext.pc;
    s_wait_pcs[n++] = (uintptr_t)uc->uc_mcontext.regs[30];
    uintptr_t fp = (uintptr_t)uc->uc_mcontext.regs[29];
    const uintptr_t sp = (uintptr_t)uc->uc_mcontext.sp;
    while (n < EU_WAIT_FRAMES && fp >= sp && fp - sp < (64u << 20) && !(fp & 15)) {
        uintptr_t w[2];
        if (!eu_wait_peek(fp, w)) break;
        if (w[1] && w[1] != s_wait_pcs[n - 1]) s_wait_pcs[n++] = w[1];
        if (w[0] <= fp) break;
        fp = w[0];
    }
    errno = saved;
#elif defined(__x86_64__)   /* desktop test builds only */
    const ucontext_t* uc = (const ucontext_t*)uctx;
    s_wait_pcs[n++] = (uintptr_t)uc->uc_mcontext.gregs[16 /* REG_RIP */];
    uintptr_t fp = (uintptr_t)uc->uc_mcontext.gregs[10 /* REG_RBP */];
    const uintptr_t sp = (uintptr_t)uc->uc_mcontext.gregs[15 /* REG_RSP */];
    while (n < EU_WAIT_FRAMES && fp >= sp && fp - sp < (64u << 20) && !(fp & 7)) {
        uintptr_t w[2];
        if (!eu_wait_peek(fp, w)) break;
        if (w[1]) s_wait_pcs[n++] = w[1];
        if (w[0] <= fp) break;
        fp = w[0];
    }
#else
    (void)uctx;
#endif
    s_wait_n = n;
    __atomic_store_n(&s_wait_done, 1, __ATOMIC_RELEASE);
}
typedef struct { char chain[300]; int hits, hits_total; } eu_wait_site;
enum { EU_WAIT_SITES = 96 };
static eu_wait_site s_wait_asleep[EU_WAIT_SITES], s_wait_awake[EU_WAIT_SITES];
static void eu_wait_add(eu_wait_site* t, const char* chain)
{
    int free_slot = -1;
    for (int i = 0; i < EU_WAIT_SITES; i++) {
        if (!t[i].chain[0]) { if (free_slot < 0) free_slot = i; continue; }
        if (!strcmp(t[i].chain, chain)) { t[i].hits++; t[i].hits_total++; return; }
    }
    if (free_slot < 0) free_slot = EU_WAIT_SITES - 1;   /* table full: the last slot collects the rest */
    snprintf(t[free_slot].chain, sizeof t[free_slot].chain, "%s", free_slot == EU_WAIT_SITES - 1 ? "(other)" : chain);
    t[free_slot].hits++; t[free_slot].hits_total++;
}
static void eu_wait_print(const char* tag, eu_wait_site* t, int total_mode, int denom, int top)
{
    if (denom <= 0) return;
    for (int shown = 0; shown < top; shown++) {
        int best = -1;
        for (int i = 0; i < EU_WAIT_SITES; i++) {
            const int h = total_mode ? t[i].hits_total : t[i].hits;
            if (h > 0 && (best < 0 || h > (total_mode ? t[best].hits_total : t[best].hits))) best = i;
        }
        if (best < 0) break;
        const int h = total_mode ? t[best].hits_total : t[best].hits;
        fprintf(stderr, "[%s]   %4.1f%%  %s\n", tag, 100.0 * h / denom, t[best].chain);
        if (total_mode) t[best].hits_total = -t[best].hits_total; else t[best].hits = 0;
    }
    for (int i = 0; i < EU_WAIT_SITES; i++) { if (total_mode) { if (t[i].hits_total < 0) t[i].hits_total = -t[i].hits_total; } else t[i].hits = 0; }
}
/* ---- where the main thread's own work goes ([main-prof]) ----
 * Once the SPU jobs got cheaper the main thread stopped waiting and became the limit: it is awake about nine
 * tenths of the time, and the call-chain table above cannot say in what (too many distinct chains). The same
 * samples, taken more often (EU_MAIN_PROF_HZ, default 50 a second), counted three ways while it is awake:
 *   self       the function the thread was in
 *   inclusive  every function on its call chain, once per sample
 *   addresses  the exact instruction, for finding the hot spot inside a large translated function
 * Printed each minute for the whole run. EU_MAIN_PROF=0 = off (the [main-wait] tables stay). */
typedef struct { uintptr_t key; char name[60]; int self, incl; } eu_prof_fn;
typedef struct { uintptr_t pc; int n; } eu_prof_pc;
enum { EU_PROF_FNS = 8192, EU_PROF_PCS = 32768 };
static eu_prof_fn* s_mp_fn;
static eu_prof_pc* s_mp_pc;
static int s_mp_samples, s_mp_on = -1, s_mp_fn_full, s_mp_pc_full;
static eu_prof_fn* eu_prof_lookup(uintptr_t pc)
{
    uintptr_t off = 0, key; char nm[60];
    const char* own = eu_own_symbol(pc, &off);
    if (own) { key = pc - off; snprintf(nm, sizeof nm, "%.59s", own); }
    else {
        Dl_info di; memset(&di, 0, sizeof di);
        if (dladdr((void*)pc, &di) && di.dli_sname) { key = (uintptr_t)di.dli_saddr; snprintf(nm, sizeof nm, "%.40s (%.16s)", di.dli_sname, eu_basename(di.dli_fname)); }
        else if (di.dli_fname) { key = pc & ~(uintptr_t)0xFFF; snprintf(nm, sizeof nm, "%.30s+0x%lx", eu_basename(di.dli_fname), (unsigned long)(key - (uintptr_t)di.dli_fbase)); }
        else { key = 1; snprintf(nm, sizeof nm, "(unknown address)"); }
    }
    for (unsigned h = (unsigned)((key >> 2) * 2654435761u) % EU_PROF_FNS, probe = 0; probe < 64; probe++, h = (h + 1) % EU_PROF_FNS) {
        if (s_mp_fn[h].key == key) return &s_mp_fn[h];
        if (!s_mp_fn[h].key) { s_mp_fn[h].key = key; memcpy(s_mp_fn[h].name, nm, sizeof nm); return &s_mp_fn[h]; }
    }
    s_mp_fn_full++;
    return NULL;
}
static void eu_prof_sample(int np)
{
    if (s_mp_on < 0) {
        const char* e = getenv("EU_MAIN_PROF");
        s_mp_on = !(e && e[0] == '0');
        if (s_mp_on) {
            s_mp_fn = (eu_prof_fn*)calloc(EU_PROF_FNS, sizeof *s_mp_fn);
            s_mp_pc = (eu_prof_pc*)calloc(EU_PROF_PCS, sizeof *s_mp_pc);
            if (!s_mp_fn || !s_mp_pc) s_mp_on = 0;
        }
    }
    if (!s_mp_on || np <= 0) return;
    s_mp_samples++;
    eu_prof_fn* seen[EU_WAIT_FRAMES]; int ns = 0;
    for (int k = 0; k < np && k < EU_WAIT_FRAMES; k++) {
        eu_prof_fn* f = eu_prof_lookup(s_wait_pcs[k] - (k ? 4 : 0));
        if (!f) continue;
        if (k == 0) f->self++;
        int dup = 0;
        for (int j = 0; j < ns; j++) if (seen[j] == f) dup = 1;
        if (!dup) { f->incl++; seen[ns++] = f; }
    }
    const uintptr_t pc = s_wait_pcs[0];
    for (unsigned h = (unsigned)((pc >> 2) * 2654435761u) % EU_PROF_PCS, probe = 0; ; probe++, h = (h + 1) % EU_PROF_PCS) {
        if (probe >= 64) { s_mp_pc_full++; break; }
        if (s_mp_pc[h].pc == pc) { s_mp_pc[h].n++; break; }
        if (!s_mp_pc[h].pc) { s_mp_pc[h].pc = pc; s_mp_pc[h].n = 1; break; }
    }
}
static void eu_prof_print(void)
{
    if (s_mp_on <= 0 || s_mp_samples < 50) return;
    const int top = 45;
    fprintf(stderr, "[main-prof] whole run, %d samples of the main thread while awake (%d + %d not counted: tables full)\n", s_mp_samples, s_mp_fn_full, s_mp_pc_full);
    for (int mode = 0; mode < 2; mode++) {
        fprintf(stderr, "[main-prof] %s:\n", mode ? "inclusive (function anywhere on the call chain)" : "self (function the thread was in)");
        int last = 0x7FFFFFFF; uintptr_t last_key = 0;
        for (int shown = 0; shown < top; shown++) {
            int best = -1;
            for (int i = 0; i < EU_PROF_FNS; i++) {
                if (!s_mp_fn[i].key) continue;
                const int v = mode ? s_mp_fn[i].incl : s_mp_fn[i].self;
                if (v <= 0 || v > last || (v == last && s_mp_fn[i].key <= last_key)) continue;
                if (best < 0) { best = i; continue; }
                const int bv = mode ? s_mp_fn[best].incl : s_mp_fn[best].self;
                if (v > bv || (v == bv && s_mp_fn[i].key < s_mp_fn[best].key)) best = i;
            }
            if (best < 0) break;
            last = mode ? s_mp_fn[best].incl : s_mp_fn[best].self; last_key = s_mp_fn[best].key;
            fprintf(stderr, "[main-prof]   %5.1f%%  %s\n", 100.0 * last / s_mp_samples, s_mp_fn[best].name);
        }
    }
    fprintf(stderr, "[main-prof] addresses:\n");
    int last = 0x7FFFFFFF; uintptr_t last_pc = 0;
    for (int shown = 0; shown < top; shown++) {
        int best = -1;
        for (int i = 0; i < EU_PROF_PCS; i++) {
            if (!s_mp_pc[i].pc) continue;
            const int v = s_mp_pc[i].n;
            if (v > last || (v == last && s_mp_pc[i].pc <= last_pc)) continue;
            if (best < 0 || v > s_mp_pc[best].n || (v == s_mp_pc[best].n && s_mp_pc[i].pc < s_mp_pc[best].pc)) best = i;
        }
        if (best < 0 || s_mp_pc[best].n < 2) break;
        last = s_mp_pc[best].n; last_pc = s_mp_pc[best].pc;
        uintptr_t off = 0; const char* own = eu_own_symbol(last_pc, &off);
        if (own) fprintf(stderr, "[main-prof]   %5.2f%%  %.56s+0x%lx  (file offset 0x%lx)\n", 100.0 * last / s_mp_samples, own, (unsigned long)off, (unsigned long)(last_pc - s_self_base));
        else { eu_prof_fn* f = eu_prof_lookup(last_pc); fprintf(stderr, "[main-prof]   %5.2f%%  %s\n", 100.0 * last / s_mp_samples, f ? f->name : "?"); }
    }
}

static void* eu_wait_thread(void* arg)
{
    (void)arg;
    prctl(PR_SET_NAME, "eu-wait-prof", 0, 0, 0);
    const int tid = (int)getpid();
    sleep(20);
    fprintf(stderr, "[main-wait] sampling what the game's main thread waits for, about 10 times a second (EU_MAIN_WAIT)\n");
    int n = 0, asleep = 0, lost = 0, n_total = 0, asleep_total = 0, rounds = 0;
    struct timespec t0;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int hz = 50;
    { const char* e = getenv("EU_MAIN_PROF_HZ"); if (e && atoi(e) > 0) hz = atoi(e); if (hz > 500) hz = 500;
      const char* o = getenv("EU_MAIN_PROF"); if (o && o[0] == '0') hz = 10; }
    const useconds_t period = (useconds_t)(1000000 / hz) - (hz > 20 ? 300 : 3000);
    for (;;) {
        usleep(period);
        char path[64], line[256] = "";
        snprintf(path, sizeof path, "/proc/self/task/%d/stat", tid);
        FILE* f = fopen(path, "r");
        if (!f) break;
        if (!fgets(line, sizeof line, f)) line[0] = 0;
        fclose(f);
        const char* rp = strrchr(line, ')');
        const char state = (rp && rp[1] == ' ') ? rp[2] : '?';
        __atomic_store_n(&s_wait_done, 0, __ATOMIC_RELEASE);
        if (syscall(SYS_tgkill, getpid(), tid, EU_WAIT_SIG) != 0) break;
        for (int w = 0; w < 400 && !__atomic_load_n(&s_wait_done, __ATOMIC_ACQUIRE); w++) usleep(50);
        if (!__atomic_load_n(&s_wait_done, __ATOMIC_ACQUIRE)) { lost++; continue; }
        /* the chain, innermost first: a libc name for the first frame if there is one, then up to five of
         * this program's own functions (and up to three named ones of other libraries) */
        char chain[300]; int o = 0, own_frames = 0, lib_frames = 0;
        const int np = s_wait_n;
        for (int k = 0; k < np && own_frames < 5 && o < (int)sizeof chain - 80; k++) {
            const uintptr_t pc = s_wait_pcs[k] - (k ? 4 : 0);
            uintptr_t off = 0;
            const char* own = eu_own_symbol(pc, &off);
            if (own) { o += snprintf(chain + o, sizeof chain - o, "%s%.56s", o ? " < " : "", own); own_frames++; continue; }
            /* other libraries: by name where they have one; the innermost frame always shows something */
            Dl_info di; memset(&di, 0, sizeof di);
            if (lib_frames >= 3 || !dladdr((void*)pc, &di)) continue;
            if (di.dli_sname) { o += snprintf(chain + o, sizeof chain - o, "%s%.40s", o ? " < " : "", di.dli_sname); lib_frames++; }
            else if (!o) { o += snprintf(chain + o, sizeof chain - o, "%.24s+0x%lx", eu_basename(di.dli_fname), (unsigned long)(pc - (uintptr_t)di.dli_fbase)); lib_frames++; }
        }
        if (!o) snprintf(chain, sizeof chain, "(no names)");
        const int is_asleep = state == 'S' || state == 'D';
        n++; n_total++;
        if (is_asleep) { asleep++; asleep_total++; eu_wait_add(s_wait_asleep, chain); }
        else { eu_wait_add(s_wait_awake, chain); eu_prof_sample(np); }
        struct timespec t1;
        clock_gettime(CLOCK_MONOTONIC, &t1);
        if (t1.tv_sec - t0.tv_sec >= 10) {
            t0 = t1; rounds++;
            fprintf(stderr, "[main-wait] 10s: %d samples of the main thread: asleep (blocked) %d%%, awake %d%% (%d not answered) | asleep in:\n",
                    n, n ? 100 * asleep / n : 0, n ? 100 - 100 * asleep / n : 0, lost);
            eu_wait_print("main-wait", s_wait_asleep, 0, n, 6);
            fprintf(stderr, "[main-wait] awake in:\n");
            eu_wait_print("main-wait", s_wait_awake, 0, n, 4);
            if (rounds % 6 == 0) {
                fprintf(stderr, "[main-wait-total] whole run, %d samples: asleep (blocked) %d%% | asleep in:\n", n_total, 100 * asleep_total / n_total);
                eu_wait_print("main-wait-total", s_wait_asleep, 1, n_total, 12);
                fprintf(stderr, "[main-wait-total] awake in:\n");
                eu_wait_print("main-wait-total", s_wait_awake, 1, n_total, 8);
                eu_prof_print();
            }
            n = asleep = lost = 0;
        }
    }
    return NULL;
}

/* 44 SPU threads share 8 cores with the game's main thread, which was ready but waiting for a core 63-66%
 * of the time (boot-20261111-100119). Only about six of the 44 do real work; the rest wake up, find
 * nothing and sleep again, ~6% of a core each -- and the runtime runs all of them at priority -10
 * (boot-20261111-115416), level with or above everything else. Every 3 s each SPU thread is judged:
 *   polling: under 15% of a core and never running for more than 0.3 ms at a stretch
 *   working: over 50%, or running for more than 0.5 ms at a stretch, or 25%+ on a faster core
 * and two independent things are done with that:
 *   EU_CORE_SPLIT=1 (default off, it never measured faster): polling threads go to the slow cores, working ones and the main thread to
 *     the faster cores;
 *   EU_SPU_NICE (default on): polling threads drop to normal priority (EU_SPU_POLL_NICE, default 0), so a
 *     poll no longer interrupts the main thread; back to their own priority when they start working.
 * The audio SPU kernel and the RSX FIFO thread are left alone. */
static void eu_core_split(void)
{
    static int on_split = -1, on_nice, poll_nice, avail, applied, tick;
    static cpu_set_t slow, fast, both;
    static struct { int tid; uint64_t ticks; unsigned long long run_ns, slices;
                    signed char where, seen, cls, niced; int nice0; } st[EU_MAX_THREADS];
    static int nst;
    static eu_tsample cur[EU_MAX_THREADS];
    static unsigned long long to_slow, to_fast, renice_again;
    static int reports;
    if (on_split < 0) {
        const char* e = getenv("EU_CORE_SPLIT");
        const char* ne = getenv("EU_SPU_NICE");
        const char* pn = getenv("EU_SPU_POLL_NICE");
        on_split = (e && *e == '1') ? 1 : 0;   /* default off: no gain in four runs, 9.8 frames/s without it against 6.3-7.1 with (boot-20261111-182540) */
        on_nice = (ne && *ne == '0') ? 0 : 1;
        poll_nice = (pn && *pn) ? atoi(pn) : 0;
        if (poll_nice < -10) poll_nice = -10;
        if (poll_nice > 19) poll_nice = 19;
        long ncpu = sysconf(_SC_NPROCESSORS_CONF);
        if (ncpu > 32) ncpu = 32;
        long top = 0, low = 0, f[32];
        for (long c = 0; c < ncpu; c++) {
            char path[96];
            snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", c);
            f[c] = eu_read_long(path);
            if (f[c] > top) top = f[c];
            if (f[c] > 0 && (!low || f[c] < low)) low = f[c];
        }
        CPU_ZERO(&slow); CPU_ZERO(&fast); CPU_ZERO(&both);
        int nslow = 0, nmid = 0, nfast = 0;
        for (long c = 0; c < ncpu; c++) if (f[c] > 0 && f[c] > low && f[c] < top) nmid++;
        for (long c = 0; c < ncpu; c++) {
            if (f[c] <= 0) continue;
            if (f[c] == low && low < top) { CPU_SET((int)c, &slow); CPU_SET((int)c, &both); nslow++; }
            else if (f[c] < top || !nmid) { CPU_SET((int)c, &fast); CPU_SET((int)c, &both); nfast++; }
            /* the fastest core stays out of both sets when there are middle cores: it is the FIFO thread's */
        }
        avail = nslow > 0 && nfast > 0;
        fprintf(stderr, "[split] polling SPU threads on the %d slow core(s), working ones and the main thread on %d faster "
                "core(s): %s (EU_CORE_SPLIT) | polling SPU threads at priority %d: %s (EU_SPU_NICE)\n", nslow, nfast,
                !avail ? "not possible on this phone" : on_split ? "on" : "off", poll_nice, on_nice ? "on" : "off");
    }
    if (!on_split && !on_nice) return;
    const int want_split = on_split && avail && !(g_eu_live_off & EU_LIVE_CORE_SPLIT);
    const int want_nice = on_nice && !(g_eu_live_off & EU_LIVE_SPU_NICE);
    if (!want_split && applied) {   /* switched off by the comparison: everything back where it may run anywhere */
        for (int i = 0; i < nst; i++)
            if (st[i].where) { sched_setaffinity(st[i].tid, sizeof both, &both); st[i].where = 0; }
        sched_setaffinity(getpid(), sizeof both, &both);
        applied = 0;
    }
    if (!want_nice)
        for (int i = 0; i < nst; i++)
            if (st[i].niced) { setpriority(PRIO_PROCESS, (id_t)st[i].tid, st[i].nice0); st[i].niced = 0; }
    if (tick++ % 3) return;
    const long hz = sysconf(_SC_CLK_TCK) > 0 ? sysconf(_SC_CLK_TCK) : 100;
    const int nc = eu_sample(cur, EU_MAX_THREADS);
    for (int i = 0; i < nst; i++) st[i].seen = 0;
    int n_poll = 0, n_work = 0, n_slow = 0, n_niced = 0;
    for (int i = 0; i < nc; i++) {
        /* Lane threads only. Kernel threads are left where they are: they pick up work within a millisecond
         * and being judged every 3 s left them on a slow core at low priority when it arrived (446 moves
         * there and 329 back in five minutes, boot-20261111-141558). */
        if (strncmp(cur[i].name, "spu w", 5)) continue;
        const int tid = cur[i].tid;
        int k = 0;
        for (; k < nst; k++) if (st[k].tid == tid) break;
        unsigned long long run_ns = 0, wait_ns = 0, slices = 0;
        {
            char path[64];
            snprintf(path, sizeof path, "/proc/self/task/%d/schedstat", tid);
            FILE* f = fopen(path, "r");
            if (f) { if (fscanf(f, "%llu %llu %llu", &run_ns, &wait_ns, &slices) != 3) run_ns = slices = 0; fclose(f); }
        }
        if (k == nst) {   /* first sight: judge it next time */
            if (nst >= EU_MAX_THREADS) continue;
            memset(&st[nst], 0, sizeof st[nst]);
            st[nst].tid = tid; st[nst].ticks = cur[i].ticks; st[nst].run_ns = run_ns; st[nst].slices = slices;
            st[nst].seen = 1;
            nst++;
            continue;
        }
        const double pct = 100.0 * (double)(cur[i].ticks - st[k].ticks) / (double)(hz * 3);
        const unsigned long long ds = slices - st[k].slices;
        const double slice_us = ds ? (double)(run_ns - st[k].run_ns) / (double)ds / 1000.0 : 0.0;
        st[k].ticks = cur[i].ticks; st[k].run_ns = run_ns; st[k].slices = slices; st[k].seen = 1;
        int skip = 0;
        if (&g_eu_prime_n) for (int j = 0; j < g_eu_prime_n && j < 4; j++) if (g_eu_prime_tid[j] == tid) skip = 1;
        /* The audio SPU kernel is placed by its own boost. (It cannot be told apart by priority: the runtime
         * raises every SPU thread, which made the first version of this skip all of them --
         * boot-20261111-105043, "0 on the slow cores".) */
        if (!strncmp(cur[i].name, "spurs i1 k0", 11)) skip = 1;
        if (skip) continue;
        if (st[k].cls != 2) st[k].cls = (pct < 15.0 && slice_us < 300.0) ? 2 : 1;
        else if (pct > 50.0 || slice_us > 500.0 || (st[k].where != 2 && pct >= 25.0)) st[k].cls = 1;
        if (want_split) {
            if (st[k].cls == 2 && st[k].where != 2) {
                if (sched_setaffinity(tid, sizeof slow, &slow) == 0) { st[k].where = 2; to_slow++; }
            } else if (st[k].cls == 1 && st[k].where != 1) {
                const int was = st[k].where;
                if (sched_setaffinity(tid, sizeof fast, &fast) == 0) { st[k].where = 1; if (was == 2) to_fast++; }
            }
        }
        if (want_nice) {
            if (st[k].cls == 2) {
                errno = 0;
                const int cur_nice = getpriority(PRIO_PROCESS, (id_t)tid);
                if (!errno && cur_nice != poll_nice) {
                    if (!st[k].niced) st[k].nice0 = cur_nice; else renice_again++;   /* the runtime put it back */
                    if (cur_nice < poll_nice && setpriority(PRIO_PROCESS, (id_t)tid, poll_nice) == 0) st[k].niced = 1;
                }
            } else if (st[k].niced) {
                setpriority(PRIO_PROCESS, (id_t)tid, st[k].nice0);
                st[k].niced = 0;
            }
        }
        if (st[k].cls == 2) n_poll++; else n_work++;
        if (st[k].where == 2) n_slow++;
        if (st[k].niced) n_niced++;
    }
    {   /* forget threads that ended */
        int o = 0;
        for (int i = 0; i < nst; i++) if (st[i].seen) st[o++] = st[i];
        nst = o;
    }
    if (want_split && !applied) { sched_setaffinity(getpid(), sizeof fast, &fast); applied = 1; }
    if (++reports <= 4 || reports % 10 == 0)
        fprintf(stderr, "[split] SPU lane threads: %d polling, %d working | on the slow cores %d (moves: %llu there, %llu back) | "
                "at lowered priority %d (put back by the runtime %llu times)\n", n_poll, n_work, n_slow, to_slow, to_fast,
                n_niced, renice_again);
}

/* Android's performance hints: name the threads a frame depends on and how long a frame takes against how
 * long it should; the system may then favour them when it picks cores and speeds. Done through
 * libandroid.so (Android 13+), started a minute in. EU_PERF_HINT=0 = off. */
extern volatile unsigned long long g_eu_frames_total __attribute__((weak));
static void eu_perf_hint_tick(void)
{
    static int state, t;   /* 0 not tried, 1 running, -1 unavailable */
    static void* session;
    static void* (*get_mgr)(void);
    static void* (*create)(void*, const int32_t*, size_t, int64_t);
    static int (*report)(void*, int64_t);
    static void (*close_s)(void*);
    static void* mgr;
    static unsigned long long f_prev;
    static unsigned long long reports_ok, reports_bad;
    if (state < 0 || !&g_eu_frames_total) return;
    if (++t < 60) return;
    if (!state) {
        const char* e = getenv("EU_PERF_HINT");
        if (e && *e == '0') { state = -1; fprintf(stderr, "[hint] Android performance hints: off (EU_PERF_HINT)\n"); return; }
        fprintf(stderr, "[hint] asking Android for a performance-hint session (if the game stops right after this line, "
                "set EU_PERF_HINT=0)\n");
        void* lib = dlopen("libandroid.so", RTLD_NOW);
        if (lib) {
            get_mgr = (void* (*)(void))dlsym(lib, "APerformanceHint_getManager");
            create = (void* (*)(void*, const int32_t*, size_t, int64_t))dlsym(lib, "APerformanceHint_createSession");
            report = (int (*)(void*, int64_t))dlsym(lib, "APerformanceHint_reportActualWorkDuration");
            close_s = (void (*)(void*))dlsym(lib, "APerformanceHint_closeSession");
        }
        if (!lib || !get_mgr || !create || !report || !close_s || !(mgr = get_mgr())) {
            state = -1;
            fprintf(stderr, "[hint] Android performance hints: not available on this phone (%s)\n",
                    !lib ? "no libandroid.so" : !get_mgr || !create ? "Android older than 13" : "no hint manager");
            return;
        }
        state = 1;
    }
    const int want = !(g_eu_live_off & EU_LIVE_PERF_HINT);
    if (!want) { if (session) { close_s(session); session = NULL; } return; }
    if (!session) {
        static eu_tsample cur[EU_MAX_THREADS];
        int32_t tids[8];
        size_t n = 0;
        tids[n++] = (int32_t)getpid();
        if (&g_eu_prime_n) for (int j = 0; j < g_eu_prime_n && j < 2; j++) tids[n++] = g_eu_prime_tid[j];
        const int nc = eu_sample(cur, EU_MAX_THREADS);
        for (int i = 0; i < nc && n < 8; i++)
            if (!strncmp(cur[i].name, "vk encode", 9) || !strncmp(cur[i].name, "spurs i1 k0", 11)) tids[n++] = cur[i].tid;
        session = create(mgr, tids, n, 33333333LL);
        static int told;
        if (!told) {
            told = 1;
            fprintf(stderr, "[hint] Android performance hints: %s for %d thread(s), target 33 ms a frame (EU_PERF_HINT)\n",
                    session ? "session started" : "the system refused a session", (int)n);
        }
        if (!session) { state = -1; return; }
        f_prev = g_eu_frames_total;
        return;
    }
    const unsigned long long f = g_eu_frames_total;
    if (f > f_prev) {
        long long ns = 1000000000LL / (long long)(f - f_prev);
        if (report(session, ns) == 0) reports_ok++; else reports_bad++;
    } else if (report(session, 1000000000LL) == 0) reports_ok++; else reports_bad++;
    f_prev = f;
    if ((reports_ok + reports_bad) % 60 == 59)
        fprintf(stderr, "[hint] frame times reported to Android: %llu accepted, %llu rejected\n", reports_ok, reports_bad);
}

/* In-run comparison. Instead of one run per setting: every EU_AB_SECS (default 20) seconds one of the listed
 * settings is switched off, then everything is on again, then the next one is off, and so on. Frame rate and
 * the main thread's waiting share are measured for each stretch (its first 4 s are left out, things settle),
 * so both states see the same scenes and the same phone temperature.
 * EU_AB=name,name,...  (default EU_PPU_INLINE,EU_N205; also EU_SHORT_SPIN, EU_SPU_NICE, EU_LL_YIELD, EU_MAIN_BOOST, EU_PERF_HINT, EU_SPU_SERIAL,
 *                        EU_CORE_SPLIT, EU_SPURS_PARK, EU_INDEX_FAST, EU_VK_CB_USED). Which one goes first varies from run to run.
 * EU_AB=0 = no switching. */
#define EU_AB_MAX 11
/* EU_PPU_INLINE: the translated game code reads and writes guest memory inline (CMake INFAMOUS_PPU_INLINE,
 * -DPPU_INLINE_LOADS) and only calls vm_read / vm_write for the special cases. The runtime gates that with
 * limits it recomputes in ppu_vm_gate_update(); zero limits send every access out of line again, which is
 * exactly how builds before this one ran. Used by the in-run comparison. */
extern int g_vm_fast_inline __attribute__((weak));
extern void ppu_vm_gate_update(void) __attribute__((weak));
static void eu_ppu_inline_set(int on)
{
    static int saved = -1;
    if (!&g_vm_fast_inline || !ppu_vm_gate_update) return;
    if (!on) { if (saved < 0) saved = g_vm_fast_inline; g_vm_fast_inline = 0; }
    else if (saved >= 0) { g_vm_fast_inline = saved; saved = -1; }
    ppu_vm_gate_update();
}
static struct { const char* name; int bit; } s_ab_known[] = {
    {"EU_CORE_SPLIT", EU_LIVE_CORE_SPLIT}, {"EU_MAIN_BOOST", EU_LIVE_MAIN_BOOST}, {"EU_SPURS_PARK", EU_LIVE_SPURS_PARK},
    {"EU_INDEX_FAST", EU_LIVE_INDEX_FAST}, {"EU_VK_CB_USED", EU_LIVE_CB_USED}, {"EU_LL_YIELD", EU_LIVE_LL_YIELD},
    {"EU_SPU_NICE", EU_LIVE_SPU_NICE}, {"EU_PERF_HINT", EU_LIVE_PERF_HINT},
    {"EU_SPU_SERIAL", EU_LIVE_SPU_SERIAL}, {"EU_SHORT_SPIN", EU_LIVE_SHORT_SPIN},
    {"EU_N205", EU_LIVE_N205}, {"EU_PPU_INLINE", EU_LIVE_PPU_INLINE},
};
extern volatile long long g_eu_job_watch_ns __attribute__((weak));
extern volatile long g_eu_job_watch_runs __attribute__((weak));
extern volatile unsigned long g_eu_n205_boxes __attribute__((weak)), g_eu_n205_items __attribute__((weak)), g_eu_n205_runs __attribute__((weak));
extern volatile unsigned long g_eu_short_spins __attribute__((weak));
extern volatile unsigned long g_eu_ll_yields __attribute__((weak));
static int s_ab_n, s_ab_item[EU_AB_MAX];
static struct { double fps_sum, wait_sum; int n; } s_ab_acc[EU_AB_MAX + 1];   /* [0] all on, [1+i] item i off */
static unsigned long long eu_main_wait_ns(unsigned long long* run)
{
    char path[64];
    unsigned long long r = 0, w = 0;
    snprintf(path, sizeof path, "/proc/self/task/%d/schedstat", (int)getpid());
    FILE* f = fopen(path, "r");
    if (f) { if (fscanf(f, "%llu %llu", &r, &w) != 2) r = w = 0; fclose(f); }
    if (run) *run = r;
    return w;
}
static int eu_ab_table(char* line, int cap)
{
    int off = 0;
    for (int i = 0; i <= s_ab_n && off < cap - 64; i++) {
        if (!s_ab_acc[i].n) continue;
        off += snprintf(line + off, cap - off, "%s%s%s %.1f frames/s, main waiting %.0f%% (%d)", off ? " | " : "",
                        i ? s_ab_known[s_ab_item[i - 1]].name : "all on", i ? " off" : "",
                        s_ab_acc[i].fps_sum / s_ab_acc[i].n, s_ab_acc[i].wait_sum / s_ab_acc[i].n, s_ab_acc[i].n);
    }
    if (!off) off = snprintf(line, cap, "nothing measured yet");
    return off;
}
static void eu_ab_tick(void)
{
    static int init, secs = 20, t = -45, seg, first;   /* the first 45 s (start-up) are not compared */
    static unsigned long long f0, w0;
    static long long jn0; static long jr0;
    if (!init) {
        init = 1;
        const char* e = getenv("EU_AB");
        const char* sv = getenv("EU_AB_SECS");
        if (sv && atoi(sv) >= 8) secs = atoi(sv);
        if (secs > 300) secs = 300;
        if (!e || !*e) e = "EU_PPU_INLINE,EU_N205";
        if (&g_eu_frames_total && strcmp(e, "0")) {
            for (unsigned k = 0; k < sizeof s_ab_known / sizeof s_ab_known[0] && s_ab_n < EU_AB_MAX; k++) {
                const char* hit = strstr(e, s_ab_known[k].name);
                const char* own = getenv(s_ab_known[k].name);
                if (hit && !(own && *own == '0')) s_ab_item[s_ab_n++] = (int)k;   /* off for the whole run: nothing to compare */
            }
        }
        if (s_ab_n) first = (int)((unsigned long)time(NULL) % (unsigned long)s_ab_n);
        char list[160] = "";
        for (int i = 0, o = 0; i < s_ab_n; i++) o += snprintf(list + o, sizeof list - o, "%s%s", i ? ", " : "", s_ab_known[s_ab_item[i]].name);
        if (s_ab_n) fprintf(stderr, "[ab] in-run comparison: every %d s one of [%s] is switched off, then all on again "
                            "(EU_AB=0 stops this; EU_AB=name,name picks; EU_AB_SECS)\n", secs, list);
        else fprintf(stderr, "[ab] in-run comparison: off\n");
    }
    if (!s_ab_n) return;
    if (++t <= 0) return;
    const int at = (t - 1) % secs;   /* second within the stretch */
    if (at == 0) {
        seg = (t - 1) / secs;
        const int off_item = (seg & 1) ? (seg / 2 + first) % s_ab_n : -1;
        const int old = g_eu_live_off;
        const int now = off_item < 0 ? 0 : s_ab_known[s_ab_item[off_item]].bit;
        g_eu_live_off = now;
        if (s_main_nice && ((old ^ now) & EU_LIVE_MAIN_BOOST))
            setpriority(PRIO_PROCESS, (id_t)getpid(), (now & EU_LIVE_MAIN_BOOST) ? 0 : s_main_nice);
        if ((old ^ now) & EU_LIVE_PPU_INLINE) eu_ppu_inline_set(!(now & EU_LIVE_PPU_INLINE));
    }
    if (at == 4) { f0 = g_eu_frames_total; w0 = eu_main_wait_ns(NULL);
                   if (&g_eu_job_watch_ns) { jn0 = g_eu_job_watch_ns; jr0 = g_eu_job_watch_runs; } }
    if (at == secs - 1) {
        const double span = (double)(secs - 5);
        const double fps = (double)(g_eu_frames_total - f0) / span;
        const double wait = (double)(eu_main_wait_ns(NULL) - w0) / (span * 1e7);
        const int off_item = (seg & 1) ? (seg / 2 + first) % s_ab_n : -1;
        const int slot = off_item < 0 ? 0 : 1 + off_item;
        s_ab_acc[slot].fps_sum += fps; s_ab_acc[slot].wait_sum += wait; s_ab_acc[slot].n++;
        char line[900];
        eu_ab_table(line, sizeof line);
        char job[96] = "";
        if (&g_eu_job_watch_ns) {
            const long long dn = g_eu_job_watch_ns - jn0; const long dr = g_eu_job_watch_runs - jr0;
            if (dr > 0) snprintf(job, sizeof job, ", visibility job %.0f us a run (%ld runs, %.0f%% of a core)",
                                 (double)dn / 1000.0 / (double)dr, dr, (double)dn / (span * 1e7));
        }
        fprintf(stderr, "[ab] %d s with %s%s: %.1f frames/s, main thread waiting %.0f%%%s | averages so far (stretches): %s\n",
                secs, off_item < 0 ? "everything on" : s_ab_known[s_ab_item[off_item]].name, off_item < 0 ? "" : " OFF",
                fps, wait, job, line);
    }
}

/* Once a minute: the handful of numbers that say how the run is going, so the end of the log can be read
 * without the rest. */
static void eu_summary(long hz, uint64_t all_ticks, uint64_t spu_ticks, int nspu)
{
    static unsigned long long f_prev, f_first, run_prev, wait_prev;
    static int minutes, have;
    static double best;
    unsigned long long run = 0;
    const unsigned long long wait = eu_main_wait_ns(&run);
    const unsigned long long frames = &g_eu_frames_total ? g_eu_frames_total : 0;
    if (!have) { have = 1; f_prev = f_first = frames; run_prev = run; wait_prev = wait; return; }
    minutes++;
    const double fps = (double)(frames - f_prev) / 60.0;
    if (fps > best) best = fps;
    fprintf(stderr, "[summary] ---- minute %d ----\n", minutes + 1);
    fprintf(stderr, "[summary] frames per second: %.1f this minute, %.1f best minute, %.1f whole run\n",
            fps, best, (double)(frames - f_first) / (60.0 * minutes));
    fprintf(stderr, "[summary] game main thread: running %.0f%% of the time, ready but waiting for a core %.0f%%\n",
            (double)(run - run_prev) / 6e8, (double)(wait - wait_prev) / 6e8);
    fprintf(stderr, "[summary] processor use: %.0f%% of one core in total, SPU threads %.0f%% (%d threads)\n",
            100.0 * (double)all_ticks / (double)(hz * 60), 100.0 * (double)spu_ticks / (double)(hz * 60), nspu);
    {
        long ncpu = sysconf(_SC_NPROCESSORS_CONF), best_top = 0, best_cap = 0, hottest = 0;
        if (ncpu > 32) ncpu = 32;
        for (long c = 0; c < ncpu; c++) {
            char path[96];
            snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", c);
            const long top = eu_read_long(path);
            snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/scaling_max_freq", c);
            const long cap = eu_read_long(path);
            if (top > best_top) { best_top = top; best_cap = cap; }
        }
        for (int z = 0; z < 100; z++) {
            char path[96], type[40] = "";
            snprintf(path, sizeof path, "/sys/class/thermal/thermal_zone%d/type", z);
            FILE* f = fopen(path, "r");
            if (!f) { if (z > 8 && !hottest) break; continue; }
            if (!fgets(type, sizeof type, f)) type[0] = 0;
            fclose(f);
            if (!strstr(type, "cpu") && !strstr(type, "gpu") && !strstr(type, "soc")) continue;
            snprintf(path, sizeof path, "/sys/class/thermal/thermal_zone%d/temp", z);
            long tv = eu_read_long(path);
            if (tv > 1000) tv /= 1000;
            if (tv > hottest && tv < 200) hottest = tv;
        }
        fprintf(stderr, "[summary] fastest core allowed %ld of %ld MHz%s; hottest chip sensor %ld C\n", best_cap / 1000,
                best_top / 1000, best_cap > 0 && best_cap < best_top ? " (phone is holding it back)" : "", hottest);
    }
    if (&g_eu_n205_runs) {
        static unsigned long b_prev, i_prev, r_prev;
        const unsigned long b = g_eu_n205_boxes, i = g_eu_n205_items, r = g_eu_n205_runs;
        fprintf(stderr, "[summary] visibility job, native code: %lu box tests and %lu item placements in %lu entries this minute\n",
                b - b_prev, i - i_prev, r - r_prev);
        b_prev = b; i_prev = i; r_prev = r;
    }
    if (&g_eu_short_spins) {
        static unsigned long sp_prev;
        const unsigned long sp = g_eu_short_spins;
        fprintf(stderr, "[summary] main thread's short job-poll sleeps answered by spinning: %lu this minute\n", sp - sp_prev);
        sp_prev = sp;
    }
    if (&g_eu_ll_yields) {
        static unsigned long y_prev;
        const unsigned long y = g_eu_ll_yields;
        fprintf(stderr, "[summary] SPU shared-memory lock: waiters gave their core away %lu times this minute\n", y - y_prev);
        y_prev = y;
    }
    if (s_ab_n) {
        char line[900];
        eu_ab_table(line, sizeof line);
        fprintf(stderr, "[summary] comparison, averages (number of stretches): %s\n", line);
    }
    f_prev = frames; run_prev = run; wait_prev = wait;
}

/* EU_CORES=3-7 (or 3,4,5,6 or 0-2,7): the whole game may use only these cores, like switching cores off in
 * Winlator. Applied last, once a second, on top of every other placement: a thread keeps whichever of its
 * allowed cores are in the list, and gets the whole list if none are. Not set = no limit. */
static void eu_cores_limit(void)
{
    static int init, n_allowed;
    static cpu_set_t allowed;
    static unsigned long long changed;
    static int reports;
    if (!init) {
        init = 1;
        const char* e = getenv("EU_CORES");
        long ncpu = sysconf(_SC_NPROCESSORS_CONF);
        if (ncpu > 32) ncpu = 32;
        CPU_ZERO(&allowed);
        for (const char* p = e; p && *p;) {
            while (*p == ',' || *p == ' ') p++;
            if (*p < '0' || *p > '9') break;
            char* end;
            long a = strtol(p, &end, 10), b = a;
            if (*end == '-') b = strtol(end + 1, &end, 10);
            for (long c = a; c <= b && c < ncpu; c++) if (c >= 0 && !CPU_ISSET((int)c, &allowed)) { CPU_SET((int)c, &allowed); n_allowed++; }
            p = end;
        }
        if (e && *e) {
            char list[80] = "";
            for (int c = 0, o = 0; c < 32 && o < 70; c++) if (CPU_ISSET(c, &allowed)) o += snprintf(list + o, sizeof list - o, " %d", c);
            if (n_allowed) fprintf(stderr, "[cores-limit] the game is confined to core(s)%s (EU_CORES=%s)\n", list, e);
            else fprintf(stderr, "[cores-limit] EU_CORES=%s names no core of this phone: ignored\n", e);
        }
    }
    if (!n_allowed) return;
    DIR* d = opendir("/proc/self/task");
    if (!d) return;
    struct dirent* e;
    unsigned long long now_changed = 0;
    while ((e = readdir(d))) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        const int tid = atoi(e->d_name);
        cpu_set_t set, want;
        CPU_ZERO(&set);
        if (sched_getaffinity(tid, sizeof set, &set) != 0) continue;
        CPU_ZERO(&want);
        int keep = 0, outside = 0;
        for (int c = 0; c < 32; c++) {
            if (!CPU_ISSET(c, &set)) continue;
            if (CPU_ISSET(c, &allowed)) { CPU_SET(c, &want); keep++; } else outside++;
        }
        if (!outside) continue;
        if (sched_setaffinity(tid, sizeof want, keep ? &want : &allowed) == 0) now_changed++;
    }
    closedir(d);
    changed += now_changed;
    if (now_changed && (++reports <= 3 || reports % 30 == 0))
        fprintf(stderr, "[cores-limit] moved %llu thread(s) onto the allowed cores (%llu so far)\n", now_changed, changed);
}

/* All CPU use, grouped by thread name with digits dropped ("spu w0 lane3" -> "spu w lane"): the [cpu] line
 * lists only the ten busiest threads, and ~250% of a core was unaccounted for across ~100 others. */
static void eu_cpu_groups(const eu_tsample* cur, const uint64_t* dt, int nc, long hz)
{
    static struct { char name[20]; uint64_t ticks; int n; } g[96];
    int ng = 0;
    for (int i = 0; i < nc; i++) {
        if (!dt[i]) continue;
        char key[20];
        int o = 0;
        for (const char* p = cur[i].name; *p && o < (int)sizeof key - 1; p++)
            if (*p < '0' || *p > '9') key[o++] = *p;
        key[o] = 0;
        int k = 0;
        for (; k < ng; k++) if (strcmp(g[k].name, key) == 0) break;
        if (k == ng) {
            if (ng >= 96) continue;
            snprintf(g[ng].name, sizeof g[ng].name, "%s", key);
            g[ng].ticks = 0;
            g[ng].n = 0;
            ng++;
        }
        g[k].ticks += dt[i];
        g[k].n++;
    }
    for (int a = 0; a < ng; a++)
        for (int b = a + 1; b < ng; b++)
            if (g[b].ticks > g[a].ticks) { __typeof__(g[0]) t = g[a]; g[a] = g[b]; g[b] = t; }
    char line[1024];
    int off = snprintf(line, sizeof line, "[cpu-groups] 10s, %% of one core by thread name (threads):");
    double rest = 0;
    for (int k = 0; k < ng; k++) {
        const double pct = 100.0 * (double)g[k].ticks / (double)(hz * 10);
        if (pct >= 3.0 && off < (int)sizeof line - 48)
            off += snprintf(line + off, sizeof line - off, " %s %.0f%% (%d)", g[k].name, pct, g[k].n);
        else rest += pct;
    }
    snprintf(line + off, sizeof line - off, " | everything else %.0f%%", rest);
    fprintf(stderr, "%s\n", line);
}

static void* eu_cpu_thread(void* arg)
{
    (void)arg;
    prctl(PR_SET_NAME, "eu-cpu-sampler", 0, 0, 0);
    static eu_tsample prev[EU_MAX_THREADS], cur[EU_MAX_THREADS];
    int np = eu_sample(prev, EU_MAX_THREADS);
    const long hz = sysconf(_SC_CLK_TCK) > 0 ? sysconf(_SC_CLK_TCK) : 100;
    for (;;) {
        for (int sec = 0; sec < 10; sec++) { sleep(1); eu_ab_tick(); eu_core_split(); eu_prime_sweep(); eu_cores_limit(); eu_perf_hint_tick(); }
        int nc = eu_sample(cur, EU_MAX_THREADS);
        /* Delta per thread, top 10. */
        int idx[EU_MAX_THREADS];
        uint64_t dt[EU_MAX_THREADS];
        uint64_t total = 0;
        for (int i = 0; i < nc; i++) {
            uint64_t before = 0;
            for (int j = 0; j < np; j++) if (prev[j].tid == cur[i].tid) { before = prev[j].ticks; break; }
            dt[i] = cur[i].ticks >= before ? cur[i].ticks - before : 0;
            total += dt[i];
            idx[i] = i;
        }
        for (int a = 0; a < nc; a++)
            for (int b = a + 1; b < nc; b++)
                if (dt[idx[b]] > dt[idx[a]]) { int t = idx[a]; idx[a] = idx[b]; idx[b] = t; }
        char line[1024];
        int off = snprintf(line, sizeof line, "[cpu] 10s: %d threads, total %.0f%% of one core; busiest:",
                           nc, 100.0 * (double)total / (double)(hz * 10));
        for (int k = 0; k < nc && k < 10 && off < (int)sizeof line - 48; k++) {
            if (!dt[idx[k]]) break;
            off += snprintf(line + off, sizeof line - off, " %s/%d %.0f%%", cur[idx[k]].name, cur[idx[k]].tid,
                            100.0 * (double)dt[idx[k]] / (double)(hz * 10));
        }
        fprintf(stderr, "%s\n", line);
        eu_cores_report(cur, idx, dt, nc);
        eu_cpu_groups(cur, dt, nc, hz);
        /* Where the two busiest threads spend their time. */
        for (int k = 0; k < nc && k < 2; k++) {
            if (dt[idx[k]] * 2 < (uint64_t)(hz * 10)) break;   /* only threads above 50% */
            char label[24];
            snprintf(label, sizeof label, "busiest#%d", k + 1);
            eu_profile_thread(cur[idx[k]].tid, label, k == 0);
        }
        {   /* The SPU side is ~470% of a core spread over 44 threads, most of them small. Sample two of the
             * small ones each cycle -- a kernel thread and a lane thread in the 3-20% range, a different one
             * each time -- to see what a mostly idle one spends its time on. */
            static int s_pick;
            int cand_k[64], cand_l[64], nk = 0, nl = 0;
            for (int i = 0; i < nc; i++) {
                const double pct = 100.0 * (double)dt[i] / (double)(hz * 10);
                if (pct < 3.0 || pct > 20.0) continue;
                if (!strncmp(cur[i].name, "spurs i", 7) && nk < 64) cand_k[nk++] = i;
                else if (!strncmp(cur[i].name, "spu w", 5) && nl < 64) cand_l[nl++] = i;
            }
            s_pick++;
            if (nk) eu_profile_thread(cur[cand_k[s_pick % nk]].tid, "small-kernel", 0);
            if (nl) eu_profile_thread(cur[cand_l[s_pick % nl]].tid, "small-lane", 0);
        }
        { extern void eu_jobtime_report(double) __attribute__((weak)); if (&eu_jobtime_report) eu_jobtime_report(10.0); }
        /* every minute: the busiest thread's whole-run totals */
        static int s_cycles;
        static uint64_t s_min_all, s_min_spu;
        int nspu = 0;
        s_min_all += total;
        for (int i = 0; i < nc; i++)
            if (!strncmp(cur[i].name, "spurs i", 7) || !strncmp(cur[i].name, "spu w", 5)) { s_min_spu += dt[i]; nspu++; }
        if (++s_cycles % 6 == 0) {
            eu_summary(hz, s_min_all, s_min_spu, nspu);
            s_min_all = s_min_spu = 0;
            eu_tot_print("hot-total", s_tot_self, s_tot_nself);
            eu_tot_print("hot-callers", s_tot_from, s_tot_nfrom);
        }
        memcpy(prev, cur, sizeof(eu_tsample) * (size_t)nc);
        np = nc;
    }
    return NULL;
}

__attribute__((constructor)) static void eu_diagnostics_init(void)
{
    if (!getenv("PS3_ANDROID_BRIDGE")) return;
    eu_load_symtab();
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = eu_crash;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    const int sigs[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT};
    for (unsigned i = 0; i < sizeof sigs / sizeof sigs[0]; i++) sigaction(sigs[i], &sa, NULL);
    struct sigaction pa;
    memset(&pa, 0, sizeof pa);
    pa.sa_sigaction = eu_prof_handler;
    pa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&pa.sa_mask);
    sigaction(EU_PROF_SIG, &pa, NULL);
    pthread_t t;
    if (pthread_create(&t, NULL, eu_cpu_thread, NULL) == 0) pthread_detach(t);
    {
        const char* e = getenv("EU_MAIN_WAIT");
        if (!(e && *e == '0')) {
            struct sigaction wa;
            memset(&wa, 0, sizeof wa);
            wa.sa_sigaction = eu_wait_handler;
            wa.sa_flags = SA_SIGINFO | SA_RESTART;
            sigemptyset(&wa.sa_mask);
            sigaction(EU_WAIT_SIG, &wa, NULL);
            pthread_t w;
            if (pthread_create(&w, NULL, eu_wait_thread, NULL) == 0) pthread_detach(w);
        }
    }
}
