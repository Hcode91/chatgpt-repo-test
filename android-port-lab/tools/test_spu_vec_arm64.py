#!/usr/bin/env python3
"""Check the ARM64 SPU helper forms against upstream's scalar references.

    python3 tools/test_spu_vec_arm64.py <upstream ps3recomp checkout>

runtime-integration/spu_helpers_vec_arm64.h (integer ops, local-store access)
and runtime-integration/spu_fsimd_arm64.h (single-precision ops) use the
compiler's generic vector types, so they build on the host too. This compiles
both next to spu_helpers.h's lane-by-lane references with clang and compares
them on random and edge-case inputs (at -O0 and -O2; floats in round-to-nearest
and round-toward-zero). Exit status 0 = every result bit-identical.
"""
import pathlib, subprocess, sys, tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent / "runtime-integration"

V1 = "xsbh xshw fsm".split()
V2 = ("a sf ah sfh cg bg and or xor andc orc nor nand eqv ceq ceqh ceqb cgt clgt clgth "
      "shl rotm rotma rot mpy mpyu mpyh mpyhh mpys").split()
V3 = "addx sfx selb mpya".split()
VI = ("ai sfi ahi sfhi andi ori xori andhi orhi xorhi andbi orbi xorbi ceqi ceqhi ceqbi cgti "
      "cgthi cgtbi clgti clgthi clgtbi mpyi mpyui shli shlhi rotmi rotmai rothmi rotmhi rotmahi roti").split()
CONST = [("splat_u32", "uint32_t"), ("il", "int32_t"), ("ila", "uint32_t"), ("ilhu", "uint16_t"),
         ("ilh", "uint16_t"), ("link", "uint32_t"), ("pref_u32", "uint32_t")]

COMMON = r'''
static uint64_t s = 88172645463325252ull;
static uint32_t rnd(void) { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return (uint32_t)s; }
static const uint32_t edge[] = { 0, 1, 2, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0xFFFF, 0x10000, 0x8000, 0x7FFF,
    0xFF, 0x80, 0x7F, 31, 32, 33, 63, 64, 0xFFFFFFE0, 0xFFFFFFC1, 16, 15, 17, 0x8000FFFF, 0xFFFF8000,
    0x7F800000, 0xFF800000, 0x7F7FFFFF, 0xFF7FFFFF, 0x7FC00000, 0x00000001, 0x80000001, 0x3F800000, 0x00800000 };
#define NEDGE (sizeof edge / sizeof edge[0])
static u128 rv(void) { u128 r; for (int i = 0; i < 4; i++) { uint32_t k = rnd();
    r._u32[i] = (k & 3) == 0 ? edge[rnd() % NEDGE] : rnd(); } return r; }
static int fails;
static void chk(const char* n, u128 x, u128 y) {
    if (memcmp(&x, &y, 16) && fails++ < 30)
        printf("MISMATCH %s %08X %08X %08X %08X vs %08X %08X %08X %08X\n", n, x._u32[0], x._u32[1], x._u32[2],
               x._u32[3], y._u32[0], y._u32[1], y._u32[2], y._u32[3]);
}
'''


def int_test() -> str:
    c = ['#include "spu_helpers.h"', "#include <stdio.h>", "#include <stdlib.h>"]
    for n in V1: c.append(f"static u128 r_{n}(u128 a) {{ return spu_{n}(a); }}")
    for n in V2: c.append(f"static u128 r_{n}(u128 a, u128 b) {{ return spu_{n}(a, b); }}")
    for n in V3: c.append(f"static u128 r_{n}(u128 a, u128 b, u128 d) {{ return spu_{n}(a, b, d); }}")
    for n in VI: c.append(f"static u128 r_{n}(u128 a, int i) {{ return spu_{n}(a, i); }}")
    for n, t in CONST: c.append(f"static u128 r_{n}({t} x) {{ return spu_{n}(x); }}")
    c.append("static u128 r_iohl(u128 a, uint16_t x) { return spu_iohl(a, x); }")
    c.append('#include "spu_helpers_vec_arm64.h"')
    c.append(COMMON)
    c.append("int main(void) { for (int it = 0; it < 300000; it++) {")
    c.append(" u128 a = rv(), b = rv(), d = rv(); int im = (int)(rnd() % 3 == 0 ? edge[rnd() % NEDGE] : rnd());")
    c.append(" if (rnd() & 1) im = (int)(rnd() % 130) - 64;")
    for n in V1: c.append(f' chk("{n}", r_{n}(a), spu__v_{n}(a));')
    for n in V2: c.append(f' chk("{n}", r_{n}(a, b), spu__v_{n}(a, b));')
    for n in V3: c.append(f' chk("{n}", r_{n}(a, b, d), spu__v_{n}(a, b, d));')
    for n in VI: c.append(f' chk("{n}", r_{n}(a, im), spu__v_{n}(a, im));')
    for n, t in CONST: c.append(f' chk("{n}", r_{n}(({t})im), spu__v_{n}(({t})im));')
    c.append(' chk("iohl", r_iohl(a, (uint16_t)im), spu__v_iohl(a, (uint16_t)im));')
    c.append(r''' { static uint8_t ls[256 * 1024]; for (int k = 0; k < 64; k++) ls[rnd() % sizeof ls] = (uint8_t)rnd();
   uint32_t lsa = rnd(), o = lsa & (SPU_LS_MASK & ~0xFu); u128 x = spu__v_ls_rd(ls, lsa), y, z;
   for (int w = 0; w < 4; w++) y._u32[w] = ((uint32_t)ls[o+4*w] << 24) | ((uint32_t)ls[o+4*w+1] << 16) | ((uint32_t)ls[o+4*w+2] << 8) | ls[o+4*w+3];
   chk("ls_rd", x, y); spu__v_ls_wr(ls, lsa, a);
   for (int w = 0; w < 4; w++) z._u32[w] = ((uint32_t)ls[o+4*w] << 24) | ((uint32_t)ls[o+4*w+1] << 16) | ((uint32_t)ls[o+4*w+2] << 8) | ls[o+4*w+3];
   chk("ls_wr", z, a); }''')
    c.append(' }\n printf("integer ops: %d mismatches\\n", fails); return fails != 0; }')
    return "\n".join(c)


def float_test() -> str:
    # The lane-by-lane references from spu_helpers.h's non-SIMD branch, with the
    # xfloat clamp on (the default).
    return r'''#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <fenv.h>
typedef union { uint32_t _u32[4]; int32_t _s32[4]; float _f32[4]; } u128;
#include "spu_fsimd_arm64.h"
static float xf(float x) { uint32_t u; memcpy(&u, &x, 4);
    if ((u & 0x7F800000u) == 0x7F800000u) { u = (u & 0x80000000u) | 0x7F7FFFFFu; memcpy(&x, &u, 4); } return x; }
static u128 xv(u128 a) { for (int i = 0; i < 4; i++) a._f32[i] = xf(a._f32[i]); return a; }
static u128 r_fa(u128 a, u128 b) { u128 r; a = xv(a); b = xv(b); for (int i = 0; i < 4; i++) r._f32[i] = a._f32[i] + b._f32[i]; return r; }
static u128 r_fs(u128 a, u128 b) { u128 r; a = xv(a); b = xv(b); for (int i = 0; i < 4; i++) r._f32[i] = a._f32[i] - b._f32[i]; return r; }
static u128 r_fm(u128 a, u128 b) { u128 r; a = xv(a); b = xv(b); for (int i = 0; i < 4; i++) r._f32[i] = a._f32[i] * b._f32[i]; return r; }
static u128 r_fma(u128 a, u128 b, u128 c) { u128 r; a = xv(a); b = xv(b); c = xv(c); for (int i = 0; i < 4; i++) r._f32[i] = fmaf(a._f32[i], b._f32[i], c._f32[i]); return r; }
static u128 r_fms(u128 a, u128 b, u128 c) { u128 r; a = xv(a); b = xv(b); c = xv(c); for (int i = 0; i < 4; i++) r._f32[i] = fmaf(a._f32[i], b._f32[i], -c._f32[i]); return r; }
static u128 r_fnms(u128 a, u128 b, u128 c) { u128 r; a = xv(a); b = xv(b); c = xv(c); for (int i = 0; i < 4; i++) r._f32[i] = fmaf(a._f32[i], -b._f32[i], c._f32[i]); return r; }
''' + COMMON + r'''
/* NaN results compare by NaN-ness only: the payload a NaN carries is not part of the SPU result
 * (the SPU has no NaN; it only arises here from FLT_MAX - FLT_MAX style inputs). */
static void fchk(const char* n, u128 x, u128 y) {
    for (int i = 0; i < 4; i++) if (isnan(x._f32[i]) && isnan(y._f32[i])) { x._u32[i] = 0; y._u32[i] = 0; }
    chk(n, x, y);
}
int main(void) {
    const int modes[2] = { FE_TONEAREST, FE_TOWARDZERO };
    for (int m = 0; m < 2; m++) { fesetround(modes[m]);
        for (int it = 0; it < 400000; it++) { u128 a = rv(), b = rv(), c = rv();
            fchk("fa", r_fa(a, b), spu_fa(a, b)); fchk("fs", r_fs(a, b), spu_fs(a, b)); fchk("fm", r_fm(a, b), spu_fm(a, b));
            fchk("fma", r_fma(a, b, c), spu_fma(a, b, c)); fchk("fms", r_fms(a, b, c), spu_fms(a, b, c));
            fchk("fnms", r_fnms(a, b, c), spu_fnms(a, b, c)); } }
    printf("float ops: %d mismatches\n", fails); return fails != 0;
}
'''


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    up = pathlib.Path(sys.argv[1]).resolve()
    inc = ["-I", str(HERE), "-I", str(up / "runtime" / "spu"), "-I", str(up / "include")]
    ok = True
    with tempfile.TemporaryDirectory() as td:
        for name, src, extra in (("int", int_test(), []), ("float", float_test(), ["-frounding-math", "-lm"])):
            c = pathlib.Path(td) / f"{name}.c"
            c.write_text(src)
            for opt in ("-O0", "-O2"):
                exe = pathlib.Path(td) / f"{name}{opt}"
                subprocess.run(["clang", opt, "-ffp-contract=off", *inc, str(c), "-o", str(exe), *extra], check=True)
                r = subprocess.run([str(exe)], capture_output=True, text=True)
                print(f"{name} {opt}: {r.stdout.strip().splitlines()[-1] if r.stdout.strip() else r.returncode}")
                if r.returncode:
                    print(r.stdout)
                    ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
