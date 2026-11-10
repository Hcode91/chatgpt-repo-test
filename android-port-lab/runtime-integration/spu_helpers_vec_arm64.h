/* spu_helpers_vec_arm64.h -- whole-register forms of the integer SPU helpers for ARM64.
 *
 * Upstream's spu_helpers_vec.h gives the register-cached lifted SPU sources
 * (spu/<image>/spu_recomp_rc.c) one full-width SSE operation per SPU
 * instruction, but only when __SSE4_1__ is defined. On ARM64 the whole header
 * dropped out, so every SPU integer op fell back to the lane-by-lane reference
 * forms in spu_helpers.h, which on the rc sources' u128 locals become scalar
 * insert/extract chains. This file is the same set of functions written with
 * the compiler's generic vector types: clang lowers each one to a NEON
 * instruction or two, and the same code builds on x86 so it can be checked
 * against the references (tools/test_spu_vec_arm64.c).
 *
 * Formulas mirror spu_helpers_vec.h one to one (same lane mapping: u128 array
 * index i is vector lane i on a little-endian host). Shift counts are masked
 * before shifting, so no vector shift is ever out of range.
 *
 * Included from spu_helpers_vec.h (Android patch) in place of the SSE block. */
#ifndef SPU_HELPERS_VEC_ARM64_H
#define SPU_HELPERS_VEC_ARM64_H
#include <stdint.h>
#include <string.h>

typedef uint32_t spu__v4u __attribute__((vector_size(16)));
typedef int32_t  spu__v4s __attribute__((vector_size(16)));
typedef uint16_t spu__v8u __attribute__((vector_size(16)));
typedef int16_t  spu__v8s __attribute__((vector_size(16)));
typedef uint8_t  spu__v16u __attribute__((vector_size(16)));
typedef int8_t   spu__v16s __attribute__((vector_size(16)));

static inline spu__v4u spu__vi(u128 a) { spu__v4u x; memcpy(&x, &a, 16); return x; }
static inline u128 spu__vo(spu__v4u x) { u128 a; memcpy(&a, &x, 16); return a; }
#define SPU__U4(x)  ((spu__v4u)(x))
#define SPU__S4(x)  ((spu__v4s)(x))
#define SPU__U8(x)  ((spu__v8u)(x))
#define SPU__S8V(x) ((spu__v8s)(x))
#define SPU__U16(x) ((spu__v16u)(x))
#define SPU__S16V(x) ((spu__v16s)(x))
#define SPU__M(c)   SPU__U4(c)   /* a vector compare result (-1 / 0 lanes) as bits */

#define SPU__V1(name, expr) static inline u128 spu__v_##name(u128 a_) { const spu__v4u A = spu__vi(a_); return spu__vo(SPU__U4(expr)); }
#define SPU__V2(name, expr) static inline u128 spu__v_##name(u128 a_, u128 b_) { const spu__v4u A = spu__vi(a_), B = spu__vi(b_); return spu__vo(SPU__U4(expr)); }
#define SPU__V3(name, expr) static inline u128 spu__v_##name(u128 a_, u128 b_, u128 c_) { const spu__v4u A = spu__vi(a_), B = spu__vi(b_), C = spu__vi(c_); return spu__vo(SPU__U4(expr)); }
#define SPU__VI(name, T, expr) static inline u128 spu__v_##name(u128 a_, T imm) { const spu__v4u A = spu__vi(a_); return spu__vo(SPU__U4(expr)); }

#define SPU__SPL32(v) ((spu__v4u){ (uint32_t)(v), (uint32_t)(v), (uint32_t)(v), (uint32_t)(v) })
#define SPU__SPL16(v) ((spu__v8u){ (uint16_t)(v), (uint16_t)(v), (uint16_t)(v), (uint16_t)(v), \
                                   (uint16_t)(v), (uint16_t)(v), (uint16_t)(v), (uint16_t)(v) })
static inline spu__v16u spu__spl8(uint8_t v) { spu__v16u r; memset(&r, v, 16); return r; }
/* sign-extended low / high halfword of each word, and the zero-extended low halfword */
static inline spu__v4s spu__lo16s(spu__v4u x) { return SPU__S4(x << 16) >> 16; }
static inline spu__v4s spu__hi16s(spu__v4u x) { return SPU__S4(x) >> 16; }
static inline spu__v4u spu__lo16u(spu__v4u x) { return x & 0xFFFFu; }

/* constants / immediates */
static inline u128 spu__v_splat_u32(uint32_t v) { return spu__vo(SPU__SPL32(v)); }
static inline u128 spu__v_il(int32_t imm)    { return spu__vo(SPU__SPL32((uint32_t)imm)); }
static inline u128 spu__v_ila(uint32_t i18)  { return spu__vo(SPU__SPL32(i18 & 0x3FFFF)); }
static inline u128 spu__v_ilhu(uint16_t imm) { return spu__vo(SPU__SPL32((uint32_t)imm << 16)); }
static inline u128 spu__v_ilh(uint16_t imm)  { return spu__vo(SPU__U4(SPU__SPL16(imm))); }
static inline u128 spu__v_link(uint32_t addr){ return spu__vo((spu__v4u){ addr, 0, 0, 0 }); }
static inline u128 spu__v_pref_u32(uint32_t v){ return spu__vo((spu__v4u){ v, 0, 0, 0 }); }
static inline u128 spu__v_iohl(u128 a_, uint16_t imm) { return spu__vo(spu__vi(a_) | (uint32_t)imm); }

/* add / subtract */
SPU__V2(a,   A + B)
SPU__V2(sf,  B - A)
SPU__V2(ah,  SPU__U8(A) + SPU__U8(B))
SPU__V2(sfh, SPU__U8(B) - SPU__U8(A))
SPU__VI(ai,   int32_t, A + (uint32_t)imm)
SPU__VI(sfi,  int32_t, SPU__SPL32((uint32_t)imm) - A)
SPU__VI(ahi,  int32_t, SPU__U8(A) + (uint16_t)imm)
SPU__VI(sfhi, int32_t, SPU__SPL16((uint16_t)imm) - SPU__U8(A))
SPU__V3(addx, A + B + (C & 1u))
SPU__V3(sfx,  B + ~A + (C & 1u))
/* carry: (a+b) <u a ; borrow: b >=u a */
SPU__V2(cg,  SPU__M((A + B) < A) & 1u)
SPU__V2(bg,  SPU__M(B >= A) & 1u)

/* logical */
SPU__V2(and,  A & B)
SPU__V2(or,   A | B)
SPU__V2(xor,  A ^ B)
SPU__V2(andc, A & ~B)
SPU__V2(orc,  A | ~B)
SPU__V2(nor,  ~(A | B))
SPU__V2(nand, ~(A & B))
SPU__V2(eqv,  ~(A ^ B))
SPU__V3(selb, (A & ~C) | (B & C))
SPU__VI(andi,  int32_t, A & (uint32_t)imm)
SPU__VI(ori,   int32_t, A | (uint32_t)imm)
SPU__VI(xori,  int32_t, A ^ (uint32_t)imm)
SPU__VI(andhi, int32_t, SPU__U8(A) & (uint16_t)imm)
SPU__VI(orhi,  int32_t, SPU__U8(A) | (uint16_t)imm)
SPU__VI(xorhi, int32_t, SPU__U8(A) ^ (uint16_t)imm)
SPU__VI(andbi, int32_t, SPU__U16(A) & spu__spl8((uint8_t)imm))
SPU__VI(orbi,  int32_t, SPU__U16(A) | spu__spl8((uint8_t)imm))
SPU__VI(xorbi, int32_t, SPU__U16(A) ^ spu__spl8((uint8_t)imm))

/* compares */
SPU__V2(ceq,  A == B)
SPU__V2(ceqh, SPU__U8(A) == SPU__U8(B))
SPU__V2(ceqb, SPU__U16(A) == SPU__U16(B))
SPU__V2(cgt,  SPU__S4(A) > SPU__S4(B))
SPU__V2(clgt, A > B)
SPU__V2(clgth,SPU__U8(A) > SPU__U8(B))
SPU__VI(ceqi,  int32_t, A == (uint32_t)imm)
SPU__VI(ceqhi, int32_t, SPU__U8(A) == (uint16_t)imm)
SPU__VI(ceqbi, int32_t, SPU__U16(A) == spu__spl8((uint8_t)imm))
SPU__VI(cgti,  int32_t, SPU__S4(A) > (int32_t)imm)
SPU__VI(cgthi, int32_t, SPU__S8V(A) > (int16_t)imm)
SPU__VI(cgtbi, int32_t, SPU__S16V(A) > SPU__S16V(spu__spl8((uint8_t)(int8_t)imm)))
SPU__VI(clgti, int32_t, A > (uint32_t)imm)
SPU__VI(clgthi,int32_t, SPU__U8(A) > (uint16_t)imm)
SPU__VI(clgtbi,int32_t, SPU__U16(A) > spu__spl8((uint8_t)imm))

/* shifts / rotates by immediate (count register form: counts >= lane width give 0 / sign) */
static inline u128 spu__v_shli(u128 a_, int imm)   { const int s = imm & 0x3F; return spu__vo(s > 31 ? SPU__SPL32(0) : spu__vi(a_) << s); }
static inline u128 spu__v_shlhi(u128 a_, int imm)  { const int s = imm & 0x1F; return spu__vo(s > 15 ? SPU__SPL32(0) : SPU__U4(SPU__U8(spu__vi(a_)) << s)); }
static inline u128 spu__v_rotmi(u128 a_, int imm)  { const int s = (0 - imm) & 0x3F; return spu__vo(s > 31 ? SPU__SPL32(0) : spu__vi(a_) >> s); }
static inline u128 spu__v_rotmai(u128 a_, int imm) { const int s = (0 - imm) & 0x3F; return spu__vo(SPU__U4(SPU__S4(spu__vi(a_)) >> (s > 31 ? 31 : s))); }
static inline u128 spu__v_rothmi(u128 a_, int imm) { const int s = (0 - imm) & 0x1F; return spu__vo(s > 15 ? SPU__SPL32(0) : SPU__U4(SPU__U8(spu__vi(a_)) >> s)); }
static inline u128 spu__v_rotmhi(u128 a_, int imm) { return spu__v_rothmi(a_, imm); }
static inline u128 spu__v_rotmahi(u128 a_, int imm){ const int s = (0 - imm) & 0x1F; return spu__vo(SPU__U4(SPU__S8V(spu__vi(a_)) >> (s > 15 ? 15 : s))); }
static inline u128 spu__v_roti(u128 a_, int imm)   { const int s = imm & 31; const spu__v4u A = spu__vi(a_); return spu__vo(s ? (A << s) | (A >> (32 - s)) : A); }

/* shifts / rotates by per-lane counts */
static inline spu__v4u spu__shlv(spu__v4u a, spu__v4u c)   /* c in 0..63: >= 32 gives 0 */
{ return (a << (c & 31u)) & SPU__M(c < 32u); }
static inline spu__v4u spu__shrv(spu__v4u a, spu__v4u c)
{ return (a >> (c & 31u)) & SPU__M(c < 32u); }
SPU__V2(shl,  spu__shlv(A, B & 0x3Fu))
SPU__V2(rotm, spu__shrv(A, (0u - B) & 0x3Fu))
static inline u128 spu__v_rotma(u128 a_, u128 b_) {
    const spu__v4u A = spu__vi(a_), c = (0u - spu__vi(b_)) & 0x3Fu;
    const spu__v4u s = (c & SPU__M(c < 32u)) | (31u & ~SPU__M(c < 32u));   /* min(c, 31) */
    return spu__vo(SPU__U4(SPU__S4(A) >> SPU__S4(s)));
}
static inline u128 spu__v_rot(u128 a_, u128 b_) {
    const spu__v4u A = spu__vi(a_), s = spu__vi(b_) & 31u;
    return spu__vo((A << s) | spu__shrv(A, 32u - s));   /* s = 0: the right half is 0, result A */
}
#define spu_shl   spu__v_shl
#define spu_rotm  spu__v_rotm
#define spu_rotma spu__v_rotma
#define spu_rot   spu__v_rot

/* sign extension */
SPU__V1(xsbh, SPU__S8V(SPU__U8(A) << 8) >> 8)
SPU__V1(xshw, SPU__S4(A << 16) >> 16)

/* 16x16 multiplies (products fit in 32 bits; unsigned arithmetic keeps the wrap defined) */
SPU__V2(mpy,   SPU__U4(spu__lo16s(A)) * SPU__U4(spu__lo16s(B)))
SPU__V2(mpyu,  spu__lo16u(A) * spu__lo16u(B))
SPU__V2(mpyh,  (SPU__U4(spu__hi16s(A)) * SPU__U4(spu__lo16s(B))) << 16)
SPU__V2(mpyhh, SPU__U4(spu__hi16s(A)) * SPU__U4(spu__hi16s(B)))
SPU__V2(mpys,  SPU__S4(SPU__U4(spu__lo16s(A)) * SPU__U4(spu__lo16s(B))) >> 16)
SPU__V3(mpya,  SPU__U4(spu__lo16s(A)) * SPU__U4(spu__lo16s(B)) + C)
SPU__VI(mpyi,  int32_t, SPU__U4(spu__lo16s(A)) * (uint32_t)(int32_t)(int16_t)imm)
SPU__VI(mpyui, int32_t, spu__lo16u(A) * (uint32_t)(uint16_t)imm)

/* form select mask from the preferred word's low 4 bits: lane i <- bit (3-i) */
static inline u128 spu__v_fsm(u128 a_) {
    const spu__v4u bits = { 8, 4, 2, 1 };
    const spu__v4u v = SPU__SPL32(a_._u32[0] & 0xF) & bits;
    return spu__vo(SPU__M(v == bits));
}

#define spu_splat_u32 spu__v_splat_u32
#define spu_il     spu__v_il
#define spu_ila    spu__v_ila
#define spu_ilhu   spu__v_ilhu
#define spu_ilh    spu__v_ilh
#define spu_link   spu__v_link
#define spu_pref_u32 spu__v_pref_u32
#define spu_iohl   spu__v_iohl
#define spu_a      spu__v_a
#define spu_sf     spu__v_sf
#define spu_ah     spu__v_ah
#define spu_sfh    spu__v_sfh
#define spu_ai     spu__v_ai
#define spu_sfi    spu__v_sfi
#define spu_ahi    spu__v_ahi
#define spu_sfhi   spu__v_sfhi
#define spu_addx   spu__v_addx
#define spu_sfx    spu__v_sfx
#define spu_cg     spu__v_cg
#define spu_bg     spu__v_bg
#define spu_and    spu__v_and
#define spu_or     spu__v_or
#define spu_xor    spu__v_xor
#define spu_andc   spu__v_andc
#define spu_orc    spu__v_orc
#define spu_nor    spu__v_nor
#define spu_nand   spu__v_nand
#define spu_eqv    spu__v_eqv
#define spu_selb   spu__v_selb
#define spu_andi   spu__v_andi
#define spu_ori    spu__v_ori
#define spu_xori   spu__v_xori
#define spu_andhi  spu__v_andhi
#define spu_orhi   spu__v_orhi
#define spu_xorhi  spu__v_xorhi
#define spu_andbi  spu__v_andbi
#define spu_orbi   spu__v_orbi
#define spu_xorbi  spu__v_xorbi
#define spu_ceq    spu__v_ceq
#define spu_ceqh   spu__v_ceqh
#define spu_ceqb   spu__v_ceqb
#define spu_cgt    spu__v_cgt
#define spu_clgt   spu__v_clgt
#define spu_clgth  spu__v_clgth
#define spu_ceqi   spu__v_ceqi
#define spu_ceqhi  spu__v_ceqhi
#define spu_ceqbi  spu__v_ceqbi
#define spu_cgti   spu__v_cgti
#define spu_cgthi  spu__v_cgthi
#define spu_cgtbi  spu__v_cgtbi
#define spu_clgti  spu__v_clgti
#define spu_clgthi spu__v_clgthi
#define spu_clgtbi spu__v_clgtbi
#define spu_shli   spu__v_shli
#define spu_shlhi  spu__v_shlhi
#define spu_rotmi  spu__v_rotmi
#define spu_rotmai spu__v_rotmai
#define spu_rothmi spu__v_rothmi
#define spu_rotmhi spu__v_rotmhi
#define spu_rotmahi spu__v_rotmahi
#define spu_roti   spu__v_roti
#define spu_xsbh   spu__v_xsbh
#define spu_xshw   spu__v_xshw
#define spu_mpy    spu__v_mpy
#define spu_mpyu   spu__v_mpyu
#define spu_mpyh   spu__v_mpyh
#define spu_mpyhh  spu__v_mpyhh
#define spu_mpys   spu__v_mpys
#define spu_mpya   spu__v_mpya
#define spu_mpyi   spu__v_mpyi
#define spu_mpyui  spu__v_mpyui
#define spu_fsm    spu__v_fsm

/* Local-store access without the diagnostic hooks, as in spu_helpers_vec.h:
 * a 16-byte load or store with each word byte-swapped (one rev32 on ARM64). */
#if !defined(SPU_RC_LS_PROBES)
static inline spu__v16u spu__bswap32x4(spu__v16u x) {
    return __builtin_shufflevector(x, x, 3, 2, 1, 0, 7, 6, 5, 4, 11, 10, 9, 8, 15, 14, 13, 12);
}
static inline u128 spu__v_ls_rd(const uint8_t* ls, uint32_t lsa) {
    spu__v16u x;
    memcpy(&x, ls + (lsa & (SPU_LS_MASK & ~0xFu)), 16);
    return spu__vo(SPU__U4(spu__bswap32x4(x)));
}
static inline void spu__v_ls_wr(uint8_t* ls, uint32_t lsa, u128 val) {
    const spu__v16u x = spu__bswap32x4(SPU__U16(spu__vi(val)));
    memcpy(ls + (lsa & (SPU_LS_MASK & ~0xFu)), &x, 16);
}
#define spu_ls_read128(c, lsa)       spu__v_ls_rd(spu_rc_ls, (lsa))
#define spu_ls_write128(c, lsa, val) spu__v_ls_wr(spu_rc_ls, (lsa), (val))
#endif

#endif /* SPU_HELPERS_VEC_ARM64_H */
