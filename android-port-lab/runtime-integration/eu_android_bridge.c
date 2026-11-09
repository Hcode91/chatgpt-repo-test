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
            if (ELF64_ST_TYPE(syms[k].st_info) != STT_FUNC || !syms[k].st_value) continue;
            if (syms[k].st_name >= st->sh_size) continue;
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

typedef struct { const void* sym; const char* name; uintptr_t off; int hits; } eu_hot;

static void eu_profile_thread(int tid, const char* label)
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
        const void* key = (const void*)pc;
        const char* name = "?";
        uintptr_t off = pc;
        uintptr_t own_off = 0;
        const char* own = eu_own_symbol(pc, &own_off);
        if (own) { key = (const void*)(pc - own_off); name = own; off = 0; }
        else if (dladdr((void*)pc, &di)) {
            if (di.dli_sname) { key = di.dli_saddr; name = di.dli_sname; off = 0; }
            else if (di.dli_fbase) { key = (const void*)(pc & ~(uintptr_t)0xFFF); off = pc - (uintptr_t)di.dli_fbase; }
        }
        int j = 0;
        while (j < nh && hot[j].sym != key) j++;
        if (j == nh && nh < MAXSYM) { hot[nh].sym = key; hot[nh].name = name; hot[nh].off = off; hot[nh].hits = 0; nh++; }
        if (j < nh) hot[j].hits++;
        usleep(5000);
    }
    for (int a = 0; a < nh; a++)
        for (int b = a + 1; b < nh; b++)
            if (hot[b].hits > hot[a].hits) { eu_hot t = hot[a]; hot[a] = hot[b]; hot[b] = t; }
    char line[1024];
    int o = snprintf(line, sizeof line, "[hot] %s tid %d (%d samples):", label, tid, taken);
    for (int a = 0; a < nh && a < 6 && o < (int)sizeof line - 96; a++) {
        if (hot[a].off) o += snprintf(line + o, sizeof line - o, " libps3eu+0x%lx x%d", (unsigned long)hot[a].off, hot[a].hits);
        else o += snprintf(line + o, sizeof line - o, " %s x%d", hot[a].name, hot[a].hits);
    }
    fprintf(stderr, "%s\n", line);
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

static void* eu_cpu_thread(void* arg)
{
    (void)arg;
    prctl(PR_SET_NAME, "eu-cpu-sampler", 0, 0, 0);
    static eu_tsample prev[EU_MAX_THREADS], cur[EU_MAX_THREADS];
    int np = eu_sample(prev, EU_MAX_THREADS);
    const long hz = sysconf(_SC_CLK_TCK) > 0 ? sysconf(_SC_CLK_TCK) : 100;
    for (;;) {
        sleep(10);
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
        /* Where the two busiest threads spend their time. */
        for (int k = 0; k < nc && k < 2; k++) {
            if (dt[idx[k]] * 2 < (uint64_t)(hz * 10)) break;   /* only threads above 50% */
            char label[24];
            snprintf(label, sizeof label, "busiest#%d", k + 1);
            eu_profile_thread(cur[idx[k]].tid, label);
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
}
