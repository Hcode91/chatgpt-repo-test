/* test_spu_fext_arm64.c -- checks runtime-integration/spu_fext_arm64.h against the reference helpers.
 *
 * The header uses NEON intrinsics, so unlike test_spu_vec_arm64.py this builds for ARM64 only. On an x86
 * machine with clang, lld, an aarch64 libc and qemu-user:
 *
 *   python3 tools/patch_android_upstream.py <disposable ps3recomp clone>
 *   clang --target=aarch64-linux-gnu -fuse-ld=lld -O2 -w -static -I<clone>/runtime/spu -I<clone>/runtime \
 *         -I<clone>/include -DSPU_FEXT_ARM64_DISABLE tools/test_spu_fext_arm64.c -lm -o t && qemu-aarch64 ./t
 *
 * (and again with -O0). -DSPU_FEXT_ARM64_DISABLE keeps spu_helpers.h from switching the helpers over, so the
 * r_* functions below are the references; the header is then included by hand. Random and edge-case inputs in
 * all four rounding modes; exit status 0 = every result bit-identical. */
#include "spu_helpers.h"
#undef SPU_FEXT_ARM64_DISABLE
#undef SPU_FEXT_ARM64_H
#include <stdio.h>
#include <stdlib.h>
#include <fenv.h>
#define REF(n) static __attribute__((noinline)) u128 r_##n(u128 a, u128 b) { return spu_##n(a, b); }
REF(fi) REF(fcgt) REF(fceq) REF(fcmgt) REF(fcmeq)
#define REFI(n) static __attribute__((noinline)) u128 r_##n(u128 a, int i) { return spu_##n(a, i); }
REFI(csflt) REFI(cuflt) REFI(cflts) REFI(cfltu)
REF(shlqby) REF(rotqby) REF(rotqmby) REF(shlqbybi) REF(rotqbybi) REF(rotqmbybi) REFI(shlqbyi) REFI(rotqbyi) REFI(rotqmbyi) 
static __attribute__((noinline)) u128 r_frest(u128 a) { return spu_frest(a); }
static __attribute__((noinline)) u128 r_frsqest(u128 a) { return spu_frsqest(a); }
#include "spu_fext_arm64.h"
int g_spu_xfloat = 1;
static uint64_t s = 88172645463325252ull;
static uint32_t rnd(void) { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return (uint32_t)s; }
static const uint32_t edge[] = { 0, 1, 2, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0xFFFF, 0x10000, 0x7F800000, 0xFF800000, 0x7F7FFFFF, 0xFF7FFFFF,
  0x7FC00000, 0xFFC00001, 0x00000001, 0x80000001, 0x3F800000, 0xBF800000, 0x00800000, 0x80800000, 0x007FFFFF, 0x4F000000, 0xCF000000, 0x4F800000, 0x4EFFFFFF, 0xCF000001, 0x4F7FFFFF,
  0x06FFFFFF, 0x07000000, 0x06800000, 0x86FFFFFF, 0x80000400, 0x800003FF, 0x000003FF, 0x3F7FFFFF, 0x3FFFFFFF, 0x33800000, 0x34000000 };
#define NEDGE (sizeof edge / sizeof edge[0])
static u128 rv(void) { u128 r; for (int i = 0; i < 4; i++) { uint32_t k = rnd(); r._u32[i] = (k & 3) == 0 ? edge[rnd() % NEDGE] : (k & 3) == 1 ? (rnd() & 0x807FFFFF) | ((rnd() % 40 + 100) << 23) : rnd(); } return r; }
static int fails; static long n;
static void chk(const char* nm, u128 a, u128 b, int im, u128 x, u128 y) { n++;
    if (memcmp(&x, &y, 16) && fails++ < 30) printf("MISMATCH %s a %08X %08X %08X %08X b %08X.. im %d: ref %08X %08X %08X %08X new %08X %08X %08X %08X\n", nm, a._u32[0], a._u32[1], a._u32[2], a._u32[3], b._u32[0], im, x._u32[0], x._u32[1], x._u32[2], x._u32[3], y._u32[0], y._u32[1], y._u32[2], y._u32[3]); }
int main(void) {
  static const int modes[] = { FE_TONEAREST, FE_TOWARDZERO, FE_UPWARD, FE_DOWNWARD };
  for (int m = 0; m < 4; m++) { fesetround(modes[m]);
    for (int it = 0; it < 400000; it++) {
      u128 a = rv(), b = rv(); int im = rnd() % 256; if (rnd() & 1) im = (rnd() & 1) ? 155 + (int)(rnd() % 33) - 32 : 173 - (int)(rnd() % 33);
      if (it & 1) b = r_frest(b); else if (it & 2) b = r_frsqest(b);
      { u128 c = b; if (rnd() & 1) c._u32[0] = (rnd() & 1) ? rnd() % 300 : (uint32_t)-(int)(rnd() % 300); int qi = (int)(rnd() % 300) - 150;
        chk("shlqby", a, c, 0, r_shlqby(a, c), spu_shlqby(a, c));
        chk("rotqby", a, c, 0, r_rotqby(a, c), spu_rotqby(a, c));
        chk("rotqmby", a, c, 0, r_rotqmby(a, c), spu_rotqmby(a, c));
        chk("shlqbybi", a, c, 0, r_shlqbybi(a, c), spu_shlqbybi(a, c));
        chk("rotqbybi", a, c, 0, r_rotqbybi(a, c), spu_rotqbybi(a, c));
        chk("rotqmbybi", a, c, 0, r_rotqmbybi(a, c), spu_rotqmbybi(a, c));
        chk("shlqbyi", a, c, qi, r_shlqbyi(a, qi), spu_shlqbyi(a, qi));
        chk("rotqbyi", a, c, qi, r_rotqbyi(a, qi), spu_rotqbyi(a, qi));
        chk("rotqmbyi", a, c, qi, r_rotqmbyi(a, qi), spu_rotqmbyi(a, qi));
      }
      chk("fi", a, b, 0, r_fi(a, b), spu_fi(a, b));
      chk("fcgt", a, b, 0, r_fcgt(a, b), spu_fcgt(a, b)); chk("fceq", a, b, 0, r_fceq(a, b), spu_fceq(a, b));
      chk("fcmgt", a, b, 0, r_fcmgt(a, b), spu_fcmgt(a, b)); chk("fcmeq", a, b, 0, r_fcmeq(a, b), spu_fcmeq(a, b));
      chk("csflt", a, b, im, r_csflt(a, im), spu_csflt(a, im)); chk("cuflt", a, b, im, r_cuflt(a, im), spu_cuflt(a, im));
      chk("cflts", a, b, im, r_cflts(a, im), spu_cflts(a, im)); chk("cfltu", a, b, im, r_cfltu(a, im), spu_cfltu(a, im));
    } }
  printf("%ld checks, %d mismatches\n", n, fails); return fails != 0; }
