/* spu_fsimd_arm64.h -- the SPU single-precision ops as one packed operation each on ARM64.
 *
 * spu_helpers.h has a packed SSE form of fa / fs / fm / fma / fms / fnms (its
 * "v63" block) but only for x86; on ARM64 each op ran as a four-lane scalar
 * loop behind a per-lane xfloat branch. These are the same ops on the
 * compiler's generic float vectors (NEON fadd / fsub / fmul / fmla on ARM64).
 *
 * Bit-identical to the scalar references: the same IEEE single-precision
 * operations in the same rounding mode (the SPU thread's FPCR, round toward
 * zero on Android), fused multiply-adds for fma / fms / fnms exactly like
 * fmaf, and the same approximate-xfloat clamp (operands with exponent 255
 * become +-FLT_MAX), written as an unsigned and a signed integer minimum the
 * way upstream's SSE4.1 path does it. Checked against the references by
 * tools/test_spu_vec_arm64.py.
 *
 * Included from spu_helpers.h (Android patch). Requires u128 from that header. */
#ifndef SPU_FSIMD_ARM64_H
#define SPU_FSIMD_ARM64_H
#include <stdint.h>
#include <string.h>

typedef float    spu__f4  __attribute__((vector_size(16)));
typedef uint32_t spu__fu4 __attribute__((vector_size(16)));
typedef int32_t  spu__fs4 __attribute__((vector_size(16)));

static inline spu__f4 spu__fld(u128 a) { spu__f4 v; memcpy(&v, &a, 16); return v; }
static inline u128 spu__fst(spu__f4 v) { u128 r; memcpy(&r, &v, 16); return r; }

/* Exponent-255 operands -> +-FLT_MAX. Positive 0x7F800000..0x7FFFFFFF -> 0x7F7FFFFF
 * (signed min), negative 0xFF800000..0xFFFFFFFF -> 0xFF7FFFFF (unsigned min), all
 * other patterns unchanged. Always on, as upstream's default x86 build (its v171 constant
 * limits): SPU_XFLOAT=0 breaks the game's skinning and audio anyway. */
static inline spu__f4 spu__xfq(spu__f4 v)
{
    spu__fu4 x = (spu__fu4)v;
    const spu__fu4 lu = { 0xFF7FFFFFu, 0xFF7FFFFFu, 0xFF7FFFFFu, 0xFF7FFFFFu };
    const spu__fu4 mu = (spu__fu4)(x < lu);
    x = (x & mu) | (lu & ~mu);
    const spu__fs4 xs = (spu__fs4)x;
    const spu__fs4 ls = { 0x7F7FFFFF, 0x7F7FFFFF, 0x7F7FFFFF, 0x7F7FFFFF };
    const spu__fu4 ms = (spu__fu4)(xs < ls);
    x = (x & ms) | ((spu__fu4)ls & ~ms);
    return (spu__f4)x;
}

static inline u128 spu_fa(u128 a, u128 b) { return spu__fst(spu__xfq(spu__fld(a)) + spu__xfq(spu__fld(b))); }
static inline u128 spu_fs(u128 a, u128 b) { return spu__fst(spu__xfq(spu__fld(a)) - spu__xfq(spu__fld(b))); }
static inline u128 spu_fm(u128 a, u128 b) { return spu__fst(spu__xfq(spu__fld(a)) * spu__xfq(spu__fld(b))); }
static inline u128 spu_fma(u128 a, u128 b, u128 c)
{ return spu__fst(__builtin_elementwise_fma(spu__xfq(spu__fld(a)), spu__xfq(spu__fld(b)), spu__xfq(spu__fld(c)))); }
static inline u128 spu_fms(u128 a, u128 b, u128 c)
{ return spu__fst(__builtin_elementwise_fma(spu__xfq(spu__fld(a)), spu__xfq(spu__fld(b)), -spu__xfq(spu__fld(c)))); }
static inline u128 spu_fnms(u128 a, u128 b, u128 c)
{ return spu__fst(__builtin_elementwise_fma(spu__xfq(spu__fld(a)), -spu__xfq(spu__fld(b)), spu__xfq(spu__fld(c)))); }

#endif /* SPU_FSIMD_ARM64_H */
