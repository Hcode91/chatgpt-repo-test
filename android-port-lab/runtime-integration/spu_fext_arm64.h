/* spu_fext_arm64.h -- more SPU single-precision helpers as packed operations on ARM64.
 *
 * spu_fsimd_arm64.h covers fa / fs / fm / fma / fms / fnms. The rest of the float set still ran lane by lane
 * through the reference helpers, and one of them was far more expensive than it looks: spu_fi (the second half
 * of every reciprocal and reciprocal square root, so of every division) calls ldexpf() twice per lane -- eight
 * libm calls per instruction. In the job that builds the animation poses that was a third of all the time
 * spent in job code.
 *
 * Each helper here returns exactly what the reference in spu_helpers.h returns when that file is built by
 * clang for ARM64 the way this port builds it, in any rounding mode:
 *   fi            base - step * y as ONE fused operation, which is what clang makes of the reference's
 *                 expression on ARM64 (-ffp-contract=on). Lanes whose step would be subnormal go through the
 *                 reference.
 *   fcgt fceq fcmgt fcmeq   compares after the approximate-xfloat clamp, as the reference.
 *   csflt cuflt   integer -> float once (FPCR rounding), then an exact power-of-two scale; scales that could
 *                 leave the normal range go through the reference.
 *   cflts cfltu   exact power-of-two scale, then a truncating, saturating conversion; ditto.
 *   shlqbyi rotqbyi rotqmbyi shlqby rotqby rotqmby shlqbybi rotqbybi rotqmbybi   one table lookup.
 * Checked against the references by tools/test_spu_fext_arm64.c (under qemu) and against recordings of real
 * job runs made on a phone, replayed through an ARM64 build.
 *
 * Included at the end of spu_helpers.h (Android patch): the references are defined above and stay available
 * under their own names inside this file; the #defines at the bottom redirect every later use. */
#ifndef SPU_FEXT_ARM64_H
#define SPU_FEXT_ARM64_H
#if defined(__aarch64__) && !defined(SPU_FEXT_ARM64_DISABLE) && SPU__FSIMD
#include <arm_neon.h>

static inline uint32x4_t spu__xu(u128 a) { uint32x4_t v; memcpy(&v, &a, 16); return v; }
static inline u128 spu__xst(uint32x4_t v) { u128 r; memcpy(&r, &v, 16); return r; }
static inline float32x4_t spu__xclamp(u128 a) { float32x4_t v; spu__f4 c = spu__xfq(spu__fld(a)); memcpy(&v, &c, 16); return v; }

static inline u128 spu__x_fi(u128 a, u128 b)
{
    const uint32x4_t rb = spu__xu(b), ra = spu__xu(a);
    const uint32x4_t exp = vandq_u32(vshrq_n_u32(rb, 23), vdupq_n_u32(0xFF));
    /* step = step_frac * 2^(exp - 140): exact as long as 2^(exp - 140) is a normal float (exp >= 14) */
    if (__builtin_expect(vminvq_u32(exp) < 14u, 0)) return spu_fi(a, b);
    const float32x4_t base = vreinterpretq_f32_u32(vandq_u32(rb, vdupq_n_u32(0xFFFFFC00u)));
    const float32x4_t scale = vreinterpretq_f32_u32(vshlq_n_u32(vsubq_u32(exp, vdupq_n_u32(13)), 23));
    float32x4_t step = vmulq_f32(vcvtq_f32_u32(vandq_u32(rb, vdupq_n_u32(0x3FF))), scale);
    /* if (base < 0) step = -step */
    step = vreinterpretq_f32_u32(veorq_u32(vreinterpretq_u32_f32(step), vandq_u32(vcltzq_f32(base), vdupq_n_u32(0x80000000u))));
    const float32x4_t y = vmulq_f32(vcvtq_f32_u32(vandq_u32(ra, vdupq_n_u32(0x7FFFF))), vdupq_n_f32(0x1p-19f));
    return spu__xst(vreinterpretq_u32_f32(vfmsq_f32(base, step, y)));   /* base - step * y, fused */
}

static inline u128 spu__x_fcgt(u128 a, u128 b) { return spu__xst(vcgtq_f32(spu__xclamp(a), spu__xclamp(b))); }
static inline u128 spu__x_fceq(u128 a, u128 b) { return spu__xst(vceqq_f32(spu__xclamp(a), spu__xclamp(b))); }
static inline u128 spu__x_fcmgt(u128 a, u128 b) { return spu__xst(vcagtq_f32(spu__xclamp(a), spu__xclamp(b))); }
static inline u128 spu__x_fcmeq(u128 a, u128 b) { return spu__xst(vceqq_f32(vabsq_f32(spu__xclamp(a)), vabsq_f32(spu__xclamp(b)))); }

static inline float32x4_t spu__x_p2(int e) { return vreinterpretq_f32_u32(vdupq_n_u32((uint32_t)(127 + e) << 23)); }

static inline u128 spu__x_csflt(u128 a, int i8)
{
    const int e = i8 - 155;
    if (__builtin_expect(e < -90 || e > 90, 0)) return spu_csflt(a, i8);
    float32x4_t f = vcvtq_f32_s32(vreinterpretq_s32_u32(spu__xu(a)));
    if (e) f = vmulq_f32(f, spu__x_p2(e));
    return spu__xst(vreinterpretq_u32_f32(f));
}
static inline u128 spu__x_cuflt(u128 a, int i8)
{
    const int e = i8 - 155;
    if (__builtin_expect(e < -90 || e > 90, 0)) return spu_cuflt(a, i8);
    float32x4_t f = vcvtq_f32_u32(spu__xu(a));
    if (e) f = vmulq_f32(f, spu__x_p2(e));
    return spu__xst(vreinterpretq_u32_f32(f));
}
static inline u128 spu__x_cflts(u128 a, int i8)
{
    const int e = 173 - i8;
    if (__builtin_expect(e < -120 || e > 120, 0)) return spu_cflts(a, i8);
    float32x4_t f = spu__xclamp(a);
    if (e) f = vmulq_f32(f, spu__x_p2(e));
    return spu__xst(vreinterpretq_u32_s32(vcvtq_s32_f32(f)));
}
static inline u128 spu__x_cfltu(u128 a, int i8)
{
    const int e = 173 - i8;
    if (__builtin_expect(e < -120 || e > 120, 0)) return spu_cfltu(a, i8);
    float32x4_t f = spu__xclamp(a);
    if (e) f = vmulq_f32(f, spu__x_p2(e));
    return spu__xst(vcvtq_u32_f32(f));
}


/* Whole-register byte shifts and rotates. spu_helpers.h has table-lookup forms of these only for x86 (SSSE3);
 * here they were sixteen-step byte loops. One table lookup each: SPU byte P lives at host byte P ^ 3
 * (SPU_W), so the lookup index for host byte j is (source SPU byte of SPU byte j ^ 3) ^ 3, and any index
 * of 16 or more -- which stays 16 or more under ^ 3 -- reads as zero. */
static inline u128 spu__x_qtbl(u128 a, uint8x16_t spu_idx)
{
    const uint8x16_t a8 = vreinterpretq_u8_u32(spu__xu(a));
    const uint8x16_t hidx = veorq_u8(vrev32q_u8(spu_idx), vdupq_n_u8(3));
    return spu__xst(vreinterpretq_u32_u8(vqtbl1q_u8(a8, hidx)));
}
static inline uint8x16_t spu__x_iota(void) { static const uint8_t k[16] = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 }; return vld1q_u8(k); }
static inline u128 spu__x_qshl(u128 a, int sh) { return spu__x_qtbl(a, vaddq_u8(spu__x_iota(), vdupq_n_u8((uint8_t)sh))); }                          /* sh 0..31 */
static inline u128 spu__x_qrot(u128 a, int sh) { return spu__x_qtbl(a, vandq_u8(vaddq_u8(spu__x_iota(), vdupq_n_u8((uint8_t)sh)), vdupq_n_u8(15))); } /* sh 0..15 */
static inline u128 spu__x_qshr(u128 a, int sh) { return spu__x_qtbl(a, vsubq_u8(spu__x_iota(), vdupq_n_u8((uint8_t)sh))); }                          /* sh 0..31 */

static inline u128 spu__x_shlqbyi(u128 a, int sh)     { return spu__x_qshl(a, sh & 0x1F); }
static inline u128 spu__x_rotqbyi(u128 a, int sh)     { return spu__x_qrot(a, sh & 0x0F); }
static inline u128 spu__x_rotqmbyi(u128 a, int i7)    { return spu__x_qshr(a, (0 - i7) & 0x1F); }
static inline u128 spu__x_shlqby(u128 a, u128 b)      { return spu__x_qshl(a, b._u32[0] & 0x1F); }
static inline u128 spu__x_rotqby(u128 a, u128 b)      { return spu__x_qrot(a, b._u32[0] & 0x0F); }
static inline u128 spu__x_rotqmby(u128 a, u128 b)     { return spu__x_qshr(a, (0 - (int)b._u32[0]) & 0x1F); }
static inline u128 spu__x_shlqbybi(u128 a, u128 b)    { return spu__x_qshl(a, (b._u32[0] >> 3) & 0x1F); }
static inline u128 spu__x_rotqbybi(u128 a, u128 b)    { return spu__x_qrot(a, (b._u32[0] >> 3) & 0x0F); }
static inline u128 spu__x_rotqmbybi(u128 a, u128 b)   { return spu__x_qshr(a, (0 - ((int)b._u32[0] >> 3)) & 0x1F); }

#define spu_fi     spu__x_fi
#define spu_fcgt   spu__x_fcgt
#define spu_fceq   spu__x_fceq
#define spu_fcmgt  spu__x_fcmgt
#define spu_fcmeq  spu__x_fcmeq
#define spu_csflt  spu__x_csflt
#define spu_cuflt  spu__x_cuflt
#define spu_cflts  spu__x_cflts
#define spu_cfltu  spu__x_cfltu
#undef  spu_shlqbyi
#define spu_shlqbyi spu__x_shlqbyi
#undef  spu_rotqbyi
#define spu_rotqbyi spu__x_rotqbyi
#undef  spu_rotqmbyi
#define spu_rotqmbyi spu__x_rotqmbyi
#undef  spu_shlqby
#define spu_shlqby spu__x_shlqby
#undef  spu_rotqby
#define spu_rotqby spu__x_rotqby
#undef  spu_rotqmby
#define spu_rotqmby spu__x_rotqmby
#undef  spu_shlqbybi
#define spu_shlqbybi spu__x_shlqbybi
#undef  spu_rotqbybi
#define spu_rotqbybi spu__x_rotqbybi
#undef  spu_rotqmbybi
#define spu_rotqmbybi spu__x_rotqmbybi
#endif
#endif /* SPU_FEXT_ARM64_H */
