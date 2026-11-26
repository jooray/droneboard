// SPDX-License-Identifier: GPL-3.0-only
#include "neural_lm.hpp"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <mutex>
#include <unordered_map>

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>
#endif
#if defined(__aarch64__) && defined(__ANDROID__)
#include <sys/auxv.h>
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1 << 20)
#endif
#endif

#include "kp_dictionary.hpp"
#include "ranker.hpp"
#include "thread_pool.hpp"

namespace droneswipe {

namespace {

// ---- IEEE-754 binary16 -> float32 (exact, including subnormals) ----

inline float fp16_to_f32(uint16_t h) {
    const uint32_t sign = (uint32_t)(h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1Fu;
    uint32_t mant = h & 0x03FFu;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) {
            bits = sign;
        } else {
            exp = 1;
            while ((mant & 0x0400u) == 0) {
                mant <<= 1;
                --exp;
            }
            mant &= 0x03FFu;
            bits = sign | ((exp + 112u) << 23) | (mant << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7F800000u | (mant << 13);
    } else {
        bits = sign | ((exp + 112u) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &bits, sizeof(f));
    return f;
}

// ---- Kernel selection (SDOT / NEON / Scalar) ----

enum class KernelKind { kScalar, kNeon, kSdot };

KernelKind detect_kernel() {
#if defined(__aarch64__)
#if defined(__ANDROID__)
    if (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) return KernelKind::kSdot;
#elif defined(__APPLE__)
    return KernelKind::kSdot;
#endif
    return KernelKind::kNeon;
#else
    return KernelKind::kScalar;
#endif
}

const KernelKind kKernel = detect_kernel();

const char* dot_name() {
    switch (kKernel) {
        case KernelKind::kSdot: return "sdot";
        case KernelKind::kNeon: return "neon";
        case KernelKind::kScalar: return "scalar";
    }
    return "scalar";
}

// ---- Symmetric per-vector int8 quantization ----

float quantize(const float* x, int n, int8_t* q) {
#if defined(__aarch64__)
    float32x4_t vmx = vdupq_n_f32(0.0f);
    int i = 0;
    for (; i + 4 <= n; i += 4) vmx = vmaxq_f32(vmx, vabsq_f32(vld1q_f32(x + i)));
    float mx = vmaxvq_f32(vmx);
    for (; i < n; ++i) mx = std::max(mx, std::fabs(x[i]));
    if (mx <= 0.0f) {
        std::memset(q, 0, (size_t)n);
        return 0.0f;
    }
    const float s = mx / 127.0f;
    const float inv = 127.0f / mx;
    const float32x4_t vinv = vdupq_n_f32(inv);
    i = 0;
    for (; i + 16 <= n; i += 16) {
        int32x4_t a0 = vcvtnq_s32_f32(vmulq_f32(vld1q_f32(x + i), vinv));
        int32x4_t a1 = vcvtnq_s32_f32(vmulq_f32(vld1q_f32(x + i + 4), vinv));
        int32x4_t a2 = vcvtnq_s32_f32(vmulq_f32(vld1q_f32(x + i + 8), vinv));
        int32x4_t a3 = vcvtnq_s32_f32(vmulq_f32(vld1q_f32(x + i + 12), vinv));
        int16x8_t b0 = vcombine_s16(vqmovn_s32(a0), vqmovn_s32(a1));
        int16x8_t b1 = vcombine_s16(vqmovn_s32(a2), vqmovn_s32(a3));
        vst1q_s8(q + i, vcombine_s8(vqmovn_s16(b0), vqmovn_s16(b1)));
    }
    for (; i < n; ++i) q[i] = (int8_t)std::lrintf(x[i] * inv);
    return s;
#else
    float mx = 0.0f;
    for (int i = 0; i < n; ++i) mx = std::max(mx, std::fabs(x[i]));
    if (mx <= 0.0f) {
        std::memset(q, 0, (size_t)n);
        return 0.0f;
    }
    const float s = mx / 127.0f, inv = 127.0f / mx;
    for (int i = 0; i < n; ++i) q[i] = (int8_t)std::lrintf(x[i] * inv);
    return s;
#endif
}

// ---- 4-row blocked INT8 matvec (no indirect fn-ptr per row!) ----

void matvec_i8_scalar(const int8_t* W, const float* scale, const float* bias,
                      const int8_t* xq, float sx, float* out, int rows, int cols,
                      float* out_max = nullptr) {
    float mx = out_max ? *out_max : -1e30f;
    for (int r = 0; r < rows; ++r) {
        const int8_t* row = W + (size_t)r * cols;
        int32_t acc = 0;
        for (int c = 0; c < cols; ++c) acc += (int32_t)row[c] * (int32_t)xq[c];
        const float v = (float)acc * scale[r] * sx + (bias ? bias[r] : 0.0f);
        out[r] = v;
        if (out_max && v > mx) mx = v;
    }
    if (out_max) *out_max = mx;
}

#if defined(__aarch64__)
void matvec_i8_neon(const int8_t* W, const float* scale, const float* bias,
                    const int8_t* xq, float sx, float* out, int rows, int cols,
                    float* out_max = nullptr) {
    float32x4_t vmx = vdupq_n_f32(out_max ? *out_max : -1e30f);
    int r = 0;
    for (; r + 4 <= rows; r += 4) {
        const int8_t* r0 = W + (size_t)r * cols;
        const int8_t* r1 = r0 + cols;
        const int8_t* r2 = r1 + cols;
        const int8_t* r3 = r2 + cols;
        int32x4_t a0 = vdupq_n_s32(0), a1 = vdupq_n_s32(0), a2 = vdupq_n_s32(0), a3 = vdupq_n_s32(0);
        for (int c = 0; c < cols; c += 16) {
            const int8x16_t xv = vld1q_s8(xq + c);
            const int8x8_t xl = vget_low_s8(xv), xh = vget_high_s8(xv);
            auto mac = [&](int32x4_t acc, const int8_t* ptr) -> int32x4_t {
                int8x16_t wv = vld1q_s8(ptr);
                int16x8_t p = vmull_s8(vget_low_s8(wv), xl);
                p = vmlal_s8(p, vget_high_s8(wv), xh);
                return vpadalq_s16(acc, p);
            };
            a0 = mac(a0, r0 + c);
            a1 = mac(a1, r1 + c);
            a2 = mac(a2, r2 + c);
            a3 = mac(a3, r3 + c);
        }
        int32x4_t dots = vpaddq_s32(vpaddq_s32(a0, a1), vpaddq_s32(a2, a3));
        float32x4_t fv = vmulq_f32(vcvtq_f32_s32(dots), vmulq_n_f32(vld1q_f32(scale + r), sx));
        if (bias) fv = vaddq_f32(fv, vld1q_f32(bias + r));
        vst1q_f32(out + r, fv);
        if (out_max) vmx = vmaxq_f32(vmx, fv);
    }
    float mx = out_max ? vmaxvq_f32(vmx) : -1e30f;
    for (; r < rows; ++r) {
        const int8_t* row = W + (size_t)r * cols;
        int32x4_t acc = vdupq_n_s32(0);
        for (int c = 0; c < cols; c += 16) {
            int8x16_t wv = vld1q_s8(row + c), xv = vld1q_s8(xq + c);
            int16x8_t p = vmull_s8(vget_low_s8(wv), vget_low_s8(xv));
            p = vmlal_s8(p, vget_high_s8(wv), vget_high_s8(xv));
            acc = vpadalq_s16(acc, p);
        }
        const float v = (float)vaddvq_s32(acc) * scale[r] * sx + (bias ? bias[r] : 0.0f);
        out[r] = v;
        if (out_max && v > mx) mx = v;
    }
    if (out_max) *out_max = mx;
}

__attribute__((always_inline, target("dotprod")))
inline int32x4_t row128_i8_sdot(const int8_t* row,
                                int8x16_t x0, int8x16_t x1, int8x16_t x2, int8x16_t x3,
                                int8x16_t x4, int8x16_t x5, int8x16_t x6, int8x16_t x7) {
    int32x4_t a0 = vdotq_s32(vdupq_n_s32(0), vld1q_s8(row + 0),  x0);
    int32x4_t a1 = vdotq_s32(vdupq_n_s32(0), vld1q_s8(row + 16), x1);
    a0 = vdotq_s32(a0, vld1q_s8(row + 32),  x2);
    a1 = vdotq_s32(a1, vld1q_s8(row + 48),  x3);
    a0 = vdotq_s32(a0, vld1q_s8(row + 64),  x4);
    a1 = vdotq_s32(a1, vld1q_s8(row + 80),  x5);
    a0 = vdotq_s32(a0, vld1q_s8(row + 96),  x6);
    a1 = vdotq_s32(a1, vld1q_s8(row + 112), x7);
    return vaddq_s32(a0, a1);
}

__attribute__((target("dotprod")))
void matvec_i8_sdot(const int8_t* W, const float* scale, const float* bias,
                    const int8_t* xq, float sx, float* out, int rows, int cols,
                    float* out_max = nullptr) {
    float32x4_t vmx = vdupq_n_f32(out_max ? *out_max : -1e30f);
    int r = 0;
    if (cols == 128) {
        const int8x16_t x0 = vld1q_s8(xq + 0),  x1 = vld1q_s8(xq + 16);
        const int8x16_t x2 = vld1q_s8(xq + 32), x3 = vld1q_s8(xq + 48);
        const int8x16_t x4 = vld1q_s8(xq + 64), x5 = vld1q_s8(xq + 80);
        const int8x16_t x6 = vld1q_s8(xq + 96), x7 = vld1q_s8(xq + 112);
        for (; r + 4 <= rows; r += 4) {
            const int8_t* r0 = W + (size_t)r * 128;
            int32x4_t a0 = row128_i8_sdot(r0,       x0, x1, x2, x3, x4, x5, x6, x7);
            int32x4_t a1 = row128_i8_sdot(r0 + 128, x0, x1, x2, x3, x4, x5, x6, x7);
            int32x4_t a2 = row128_i8_sdot(r0 + 256, x0, x1, x2, x3, x4, x5, x6, x7);
            int32x4_t a3 = row128_i8_sdot(r0 + 384, x0, x1, x2, x3, x4, x5, x6, x7);
            int32x4_t dots = vpaddq_s32(vpaddq_s32(a0, a1), vpaddq_s32(a2, a3));
            float32x4_t fv = vmulq_f32(vcvtq_f32_s32(dots), vmulq_n_f32(vld1q_f32(scale + r), sx));
            if (bias) fv = vaddq_f32(fv, vld1q_f32(bias + r));
            vst1q_f32(out + r, fv);
            if (out_max) vmx = vmaxq_f32(vmx, fv);
        }
    } else {
        for (; r + 4 <= rows; r += 4) {
            const int8_t* r0 = W + (size_t)r * cols;
            const int8_t* r1 = r0 + cols;
            const int8_t* r2 = r1 + cols;
            const int8_t* r3 = r2 + cols;
            int32x4_t a0 = vdupq_n_s32(0), a1 = vdupq_n_s32(0), a2 = vdupq_n_s32(0), a3 = vdupq_n_s32(0);
            int c = 0;
            for (; c + 32 <= cols; c += 32) {
                const int8x16_t xv0 = vld1q_s8(xq + c);
                const int8x16_t xv1 = vld1q_s8(xq + c + 16);
                a0 = vdotq_s32(vdotq_s32(a0, vld1q_s8(r0 + c), xv0), vld1q_s8(r0 + c + 16), xv1);
                a1 = vdotq_s32(vdotq_s32(a1, vld1q_s8(r1 + c), xv0), vld1q_s8(r1 + c + 16), xv1);
                a2 = vdotq_s32(vdotq_s32(a2, vld1q_s8(r2 + c), xv0), vld1q_s8(r2 + c + 16), xv1);
                a3 = vdotq_s32(vdotq_s32(a3, vld1q_s8(r3 + c), xv0), vld1q_s8(r3 + c + 16), xv1);
            }
            for (; c < cols; c += 16) {
                const int8x16_t xv = vld1q_s8(xq + c);
                a0 = vdotq_s32(a0, vld1q_s8(r0 + c), xv);
                a1 = vdotq_s32(a1, vld1q_s8(r1 + c), xv);
                a2 = vdotq_s32(a2, vld1q_s8(r2 + c), xv);
                a3 = vdotq_s32(a3, vld1q_s8(r3 + c), xv);
            }
            int32x4_t dots = vpaddq_s32(vpaddq_s32(a0, a1), vpaddq_s32(a2, a3));
            float32x4_t fv = vmulq_f32(vcvtq_f32_s32(dots), vmulq_n_f32(vld1q_f32(scale + r), sx));
            if (bias) fv = vaddq_f32(fv, vld1q_f32(bias + r));
            vst1q_f32(out + r, fv);
            if (out_max) vmx = vmaxq_f32(vmx, fv);
        }
    }
    float mx = out_max ? vmaxvq_f32(vmx) : -1e30f;
    for (; r < rows; ++r) {
        const int8_t* row = W + (size_t)r * cols;
        int32x4_t acc0 = vdupq_n_s32(0), acc1 = vdupq_n_s32(0);
        int c = 0;
        for (; c + 32 <= cols; c += 32) {
            acc0 = vdotq_s32(acc0, vld1q_s8(row + c), vld1q_s8(xq + c));
            acc1 = vdotq_s32(acc1, vld1q_s8(row + c + 16), vld1q_s8(xq + c + 16));
        }
        for (; c < cols; c += 16) acc0 = vdotq_s32(acc0, vld1q_s8(row + c), vld1q_s8(xq + c));
        const float v = (float)vaddvq_s32(vaddq_s32(acc0, acc1)) * scale[r] * sx + (bias ? bias[r] : 0.0f);
        out[r] = v;
        if (out_max && v > mx) mx = v;
    }
    if (out_max) *out_max = mx;
}
#endif

inline void matvec_i8(const int8_t* W, const float* scale, const float* bias,
                      const int8_t* xq, float sx, float* out, int rows, int cols,
                      float* out_max = nullptr) {
#if defined(__aarch64__)
    if (kKernel == KernelKind::kSdot) { matvec_i8_sdot(W, scale, bias, xq, sx, out, rows, cols, out_max); return; }
    if (kKernel == KernelKind::kNeon) { matvec_i8_neon(W, scale, bias, xq, sx, out, rows, cols, out_max); return; }
#endif
    matvec_i8_scalar(W, scale, bias, xq, sx, out, rows, cols, out_max);
}

// ---- INT4 group-32 dot products (NEON split-half nibble layout: lo = q[0..15]+8, hi = q[16..31]+8) ----

float dot_row_i4_scalar(const uint8_t* packed, const uint16_t* scales, float bias,
                        const int8_t* xq, float sx, int cols) {
    const int G = cols >> 5;
    float sum = 0.0f;
    for (int g = 0; g < G; ++g) {
        const uint8_t* p = packed + g * 16;
        const int8_t* x = xq + g * 32;
        int32_t acc = 0;
        for (int j = 0; j < 16; ++j) {
            const int32_t q_lo = (int32_t)(p[j] & 0x0Fu) - 8;
            const int32_t q_hi = (int32_t)(p[j] >> 4) - 8;
            acc += q_lo * (int32_t)x[j] + q_hi * (int32_t)x[j + 16];
        }
        sum += (float)acc * fp16_to_f32(scales[g]);
    }
    return sum * sx + bias;
}

void matvec_i4_scalar(const uint8_t* W, const uint16_t* scales, const float* bias,
                      const int8_t* xq, float sx, float* out, int rows, int cols,
                      float* out_max = nullptr) {
    const int stride_b = cols >> 1;
    const int G = cols >> 5;
    float mx = out_max ? *out_max : -1e30f;
    for (int r = 0; r < rows; ++r) {
        const float v = dot_row_i4_scalar(W + (size_t)r * stride_b, scales + (size_t)r * G,
                                          bias ? bias[r] : 0.0f, xq, sx, cols);
        out[r] = v;
        if (out_max && v > mx) mx = v;
    }
    if (out_max) *out_max = mx;
}

#if defined(__aarch64__)
__attribute__((target("dotprod")))
float dot_row_i4_sdot(const uint8_t* packed, const uint16_t* scales, float bias,
                      const int8_t* xq, float sx, int cols) {
    const uint8x16_t mask0f = vdupq_n_u8(0x0Fu);
    const int8x16_t eight = vdupq_n_s8(8);
    const int G = cols >> 5;
    float sum = 0.0f;
    for (int g = 0; g < G; ++g) {
        const uint8x16_t raw = vld1q_u8(packed + g * 16);
        const int8x16_t q_lo = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(raw, mask0f)), eight);
        const int8x16_t q_hi = vsubq_s8(vreinterpretq_s8_u8(vshrq_n_u8(raw, 4)), eight);
        int32x4_t acc = vdotq_s32(vdupq_n_s32(0), q_lo, vld1q_s8(xq + g * 32));
        acc = vdotq_s32(acc, q_hi, vld1q_s8(xq + g * 32 + 16));
        sum += (float)vaddvq_s32(acc) * fp16_to_f32(scales[g]);
    }
    return sum * sx + bias;
}

__attribute__((always_inline, target("dotprod")))
inline int32x4_t row128_i4_sdot(const uint8_t* p, uint8x16_t mask0f, int32x4_t xsum8,
                                int8x16_t x0l, int8x16_t x0h, int8x16_t x1l, int8x16_t x1h,
                                int8x16_t x2l, int8x16_t x2h, int8x16_t x3l, int8x16_t x3h) {
    const uint8x16_t u0 = vld1q_u8(p + 0),  u1 = vld1q_u8(p + 16);
    const uint8x16_t u2 = vld1q_u8(p + 32), u3 = vld1q_u8(p + 48);
    int32x4_t a0 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), vreinterpretq_s8_u8(vandq_u8(u0, mask0f)), x0l),
                             vreinterpretq_s8_u8(vshrq_n_u8(u0, 4)), x0h);
    int32x4_t a1 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), vreinterpretq_s8_u8(vandq_u8(u1, mask0f)), x1l),
                             vreinterpretq_s8_u8(vshrq_n_u8(u1, 4)), x1h);
    int32x4_t a2 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), vreinterpretq_s8_u8(vandq_u8(u2, mask0f)), x2l),
                             vreinterpretq_s8_u8(vshrq_n_u8(u2, 4)), x2h);
    int32x4_t a3 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), vreinterpretq_s8_u8(vandq_u8(u3, mask0f)), x3l),
                             vreinterpretq_s8_u8(vshrq_n_u8(u3, 4)), x3h);
    return vsubq_s32(vpaddq_s32(vpaddq_s32(a0, a1), vpaddq_s32(a2, a3)), xsum8);
}

// 4-row unrolled INT4-g32 SDOT matvec with algebraic zero-point elimination:
//   dot(u - 8, x) = dot(u, x) - 8 * sum(x_g)
// Eliminates all 8 vsubq_s8 per row (320,000 vector ops per 40k-row head pass) and tracks max_logit.
__attribute__((target("dotprod")))
void matvec_i4_sdot(const uint8_t* W, const uint16_t* scales, const float* bias,
                    const int8_t* xq, float sx, float* out, int rows, int cols,
                    float* out_max = nullptr) {
    const uint8x16_t mask0f = vdupq_n_u8(0x0Fu);
    const int stride_b = cols >> 1;
    const int G = cols >> 5;
    if (G == 4) {
        const int8x16_t x0l = vld1q_s8(xq + 0),  x0h = vld1q_s8(xq + 16);
        const int8x16_t x1l = vld1q_s8(xq + 32), x1h = vld1q_s8(xq + 48);
        const int8x16_t x2l = vld1q_s8(xq + 64), x2h = vld1q_s8(xq + 80);
        const int8x16_t x3l = vld1q_s8(xq + 96), x3h = vld1q_s8(xq + 112);

        const int8x16_t eight = vdupq_n_s8(8);
        const int32x4_t xs0 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), eight, x0l), eight, x0h);
        const int32x4_t xs1 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), eight, x1l), eight, x1h);
        const int32x4_t xs2 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), eight, x2l), eight, x2h);
        const int32x4_t xs3 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), eight, x3l), eight, x3h);
        const int32x4_t xsum8 = vpaddq_s32(vpaddq_s32(xs0, xs1), vpaddq_s32(xs2, xs3));

        float32x4_t vmx = vdupq_n_f32(out_max ? *out_max : -1e30f);
        int r = 0;
        for (; r + 4 <= rows; r += 4) {
            const uint8_t* p = W + (size_t)r * 64;
            const __fp16* sp = reinterpret_cast<const __fp16*>(scales + (size_t)r * 4);
            float32x4_t f0 = vmulq_f32(vcvtq_f32_s32(row128_i4_sdot(p + 0,   mask0f, xsum8, x0l, x0h, x1l, x1h, x2l, x2h, x3l, x3h)), vcvt_f32_f16(vld1_f16(sp + 0)));
            float32x4_t f1 = vmulq_f32(vcvtq_f32_s32(row128_i4_sdot(p + 64,  mask0f, xsum8, x0l, x0h, x1l, x1h, x2l, x2h, x3l, x3h)), vcvt_f32_f16(vld1_f16(sp + 4)));
            float32x4_t f2 = vmulq_f32(vcvtq_f32_s32(row128_i4_sdot(p + 128, mask0f, xsum8, x0l, x0h, x1l, x1h, x2l, x2h, x3l, x3h)), vcvt_f32_f16(vld1_f16(sp + 8)));
            float32x4_t f3 = vmulq_f32(vcvtq_f32_s32(row128_i4_sdot(p + 192, mask0f, xsum8, x0l, x0h, x1l, x1h, x2l, x2h, x3l, x3h)), vcvt_f32_f16(vld1_f16(sp + 12)));
            float32x4_t sums = vpaddq_f32(vpaddq_f32(f0, f1), vpaddq_f32(f2, f3));
            float32x4_t res = bias ? vfmaq_n_f32(vld1q_f32(bias + r), sums, sx) : vmulq_n_f32(sums, sx);
            vst1q_f32(out + r, res);
            if (out_max) vmx = vmaxq_f32(vmx, res);
        }
        float mx = out_max ? vmaxvq_f32(vmx) : -1e30f;
        for (; r < rows; ++r) {
            const int32x4_t dots = row128_i4_sdot(W + (size_t)r * 64, mask0f, xsum8, x0l, x0h, x1l, x1h, x2l, x2h, x3l, x3h);
            const float32x4_t sc = vcvt_f32_f16(vld1_f16(reinterpret_cast<const __fp16*>(scales + (size_t)r * 4)));
            const float v = vaddvq_f32(vmulq_f32(vcvtq_f32_s32(dots), sc)) * sx + (bias ? bias[r] : 0.0f);
            out[r] = v;
            if (out_max && v > mx) mx = v;
        }
        if (out_max) *out_max = mx;
        return;
    }
    float mx = out_max ? *out_max : -1e30f;
    for (int r = 0; r < rows; ++r) {
        const float v = dot_row_i4_sdot(W + (size_t)r * stride_b, scales + (size_t)r * G,
                                        bias ? bias[r] : 0.0f, xq, sx, cols);
        out[r] = v;
        if (out_max && v > mx) mx = v;
    }
    if (out_max) *out_max = mx;
}

float dot_row_i4_neon(const uint8_t* packed, const uint16_t* scales, float bias,
                      const int8_t* xq, float sx, int cols) {
    const uint8x16_t mask0f = vdupq_n_u8(0x0Fu);
    const int8x16_t eight = vdupq_n_s8(8);
    const int G = cols >> 5;
    float sum = 0.0f;
    for (int g = 0; g < G; ++g) {
        const uint8x16_t raw = vld1q_u8(packed + g * 16);
        const int8x16_t q_lo = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(raw, mask0f)), eight);
        const int8x16_t q_hi = vsubq_s8(vreinterpretq_s8_u8(vshrq_n_u8(raw, 4)), eight);
        const int8x16_t x_lo = vld1q_s8(xq + g * 32);
        const int8x16_t x_hi = vld1q_s8(xq + g * 32 + 16);
        int16x8_t p0 = vmull_s8(vget_low_s8(q_lo), vget_low_s8(x_lo));
        p0 = vmlal_s8(p0, vget_high_s8(q_lo), vget_high_s8(x_lo));
        int16x8_t p1 = vmull_s8(vget_low_s8(q_hi), vget_low_s8(x_hi));
        p1 = vmlal_s8(p1, vget_high_s8(q_hi), vget_high_s8(x_hi));
        int32x4_t acc = vpadalq_s16(vpaddlq_s16(p0), p1);
        sum += (float)vaddvq_s32(acc) * fp16_to_f32(scales[g]);
    }
    return sum * sx + bias;
}

void matvec_i4_neon(const uint8_t* W, const uint16_t* scales, const float* bias,
                    const int8_t* xq, float sx, float* out, int rows, int cols,
                    float* out_max = nullptr) {
    const uint8x16_t mask0f = vdupq_n_u8(0x0Fu);
    const int stride_b = cols >> 1;
    const int G = cols >> 5;
    if (G == 4) {
        const int8x16_t x0l = vld1q_s8(xq + 0),  x0h = vld1q_s8(xq + 16);
        const int8x16_t x1l = vld1q_s8(xq + 32), x1h = vld1q_s8(xq + 48);
        const int8x16_t x2l = vld1q_s8(xq + 64), x2h = vld1q_s8(xq + 80);
        const int8x16_t x3l = vld1q_s8(xq + 96), x3h = vld1q_s8(xq + 112);

        int32_t xs_arr[4];
        for (int g = 0; g < 4; ++g) {
            int32_t s = 0;
            for (int j = 0; j < 32; ++j) s += (int32_t)xq[g * 32 + j];
            xs_arr[g] = s * 8;
        }
        const int32x4_t xsum8 = vld1q_s32(xs_arr);

        auto grp_dot = [](uint8x16_t u, uint8x16_t mask, int8x16_t xl, int8x16_t xh) -> int32x4_t {
            const int8x16_t ql = vreinterpretq_s8_u8(vandq_u8(u, mask));
            const int8x16_t qh = vreinterpretq_s8_u8(vshrq_n_u8(u, 4));
            int16x8_t p0 = vmull_s8(vget_low_s8(ql), vget_low_s8(xl));
            p0 = vmlal_s8(p0, vget_high_s8(ql), vget_high_s8(xl));
            int16x8_t p1 = vmull_s8(vget_low_s8(qh), vget_low_s8(xh));
            p1 = vmlal_s8(p1, vget_high_s8(qh), vget_high_s8(xh));
            return vpadalq_s16(vpaddlq_s16(p0), p1);
        };
        auto row_dots = [&](const uint8_t* p) -> int32x4_t {
            int32x4_t a0 = grp_dot(vld1q_u8(p + 0),  mask0f, x0l, x0h);
            int32x4_t a1 = grp_dot(vld1q_u8(p + 16), mask0f, x1l, x1h);
            int32x4_t a2 = grp_dot(vld1q_u8(p + 32), mask0f, x2l, x2h);
            int32x4_t a3 = grp_dot(vld1q_u8(p + 48), mask0f, x3l, x3h);
            return vsubq_s32(vpaddq_s32(vpaddq_s32(a0, a1), vpaddq_s32(a2, a3)), xsum8);
        };

        float32x4_t vmx = vdupq_n_f32(out_max ? *out_max : -1e30f);
        int r = 0;
        for (; r + 4 <= rows; r += 4) {
            const uint8_t* p = W + (size_t)r * 64;
            const __fp16* sp = reinterpret_cast<const __fp16*>(scales + (size_t)r * 4);
            float32x4_t f0 = vmulq_f32(vcvtq_f32_s32(row_dots(p + 0)),   vcvt_f32_f16(vld1_f16(sp + 0)));
            float32x4_t f1 = vmulq_f32(vcvtq_f32_s32(row_dots(p + 64)),  vcvt_f32_f16(vld1_f16(sp + 4)));
            float32x4_t f2 = vmulq_f32(vcvtq_f32_s32(row_dots(p + 128)), vcvt_f32_f16(vld1_f16(sp + 8)));
            float32x4_t f3 = vmulq_f32(vcvtq_f32_s32(row_dots(p + 192)), vcvt_f32_f16(vld1_f16(sp + 12)));
            float32x4_t sums = vpaddq_f32(vpaddq_f32(f0, f1), vpaddq_f32(f2, f3));
            float32x4_t res = bias ? vfmaq_n_f32(vld1q_f32(bias + r), sums, sx) : vmulq_n_f32(sums, sx);
            vst1q_f32(out + r, res);
            if (out_max) vmx = vmaxq_f32(vmx, res);
        }
        float mx = out_max ? vmaxvq_f32(vmx) : -1e30f;
        for (; r < rows; ++r) {
            const int32x4_t dots = row_dots(W + (size_t)r * 64);
            const float32x4_t sc = vcvt_f32_f16(vld1_f16(reinterpret_cast<const __fp16*>(scales + (size_t)r * 4)));
            const float v = vaddvq_f32(vmulq_f32(vcvtq_f32_s32(dots), sc)) * sx + (bias ? bias[r] : 0.0f);
            out[r] = v;
            if (out_max && v > mx) mx = v;
        }
        if (out_max) *out_max = mx;
        return;
    }
    float mx = out_max ? *out_max : -1e30f;
    for (int r = 0; r < rows; ++r) {
        const float v = dot_row_i4_neon(W + (size_t)r * stride_b, scales + (size_t)r * G,
                                        bias ? bias[r] : 0.0f, xq, sx, cols);
        out[r] = v;
        if (out_max && v > mx) mx = v;
    }
    if (out_max) *out_max = mx;
}
#endif

inline void matvec_i4(const uint8_t* W, const uint16_t* scales, const float* bias,
                      const int8_t* xq, float sx, float* out, int rows, int cols,
                      float* out_max = nullptr) {
#if defined(__aarch64__)
    if (kKernel == KernelKind::kSdot) { matvec_i4_sdot(W, scales, bias, xq, sx, out, rows, cols, out_max); return; }
    if (kKernel == KernelKind::kNeon) { matvec_i4_neon(W, scales, bias, xq, sx, out, rows, cols, out_max); return; }
#endif
    matvec_i4_scalar(W, scales, bias, xq, sx, out, rows, cols, out_max);
}

// ---- Fast polynomial exp & logsumexp (LSE error < 4e-6) ----

inline float fast_exp_scalar(float x) {
    if (!(x == x)) return x;  // NaN: (int32_t)kf below would be undefined behaviour
    if (x < -87.33654475f) x = -87.33654475f;
    if (x > 88.0f) x = 88.0f;
    const float kf = std::nearbyintf(x * 1.4426950408889634f);
    const float r = (x - kf * 0.693145751953125f) - kf * 1.428606765330187e-6f;
    float p = 0.008333333767950535f * r + 0.0416666679084301f;
    p = p * r + 0.1666666716337204f;
    p = p * r + 0.5f;
    p = p * r + 1.0f;
    p = p * r + 1.0f;
    const uint32_t bits = (uint32_t)((int32_t)kf + 127) << 23;
    float pow2k;
    std::memcpy(&pow2k, &bits, sizeof(pow2k));
    return p * pow2k;
}

#if defined(__aarch64__)
inline float32x4_t fast_exp_f32x4(float32x4_t x) {
    const float32x4_t v_min = vdupq_n_f32(-87.33654475f);
    const float32x4_t v_max = vdupq_n_f32(88.0f);
    const float32x4_t v_inv_ln2 = vdupq_n_f32(1.4426950408889634f);
    const float32x4_t v_ln2_hi = vdupq_n_f32(0.693145751953125f);
    const float32x4_t v_ln2_lo = vdupq_n_f32(1.428606765330187e-6f);
    const float32x4_t c2 = vdupq_n_f32(0.5f);
    const float32x4_t c3 = vdupq_n_f32(0.1666666716337204f);
    const float32x4_t c4 = vdupq_n_f32(0.0416666679084301f);
    const float32x4_t c5 = vdupq_n_f32(0.008333333767950535f);
    const float32x4_t one = vdupq_n_f32(1.0f);

    x = vminq_f32(vmaxq_f32(x, v_min), v_max);
    const float32x4_t kf = vrndnq_f32(vmulq_f32(x, v_inv_ln2));
    float32x4_t r = vfmsq_f32(x, kf, v_ln2_hi);
    r = vfmsq_f32(r, kf, v_ln2_lo);

    float32x4_t p = vfmaq_f32(c4, c5, r);
    p = vfmaq_f32(c3, p, r);
    p = vfmaq_f32(c2, p, r);
    p = vfmaq_f32(one, p, r);
    p = vfmaq_f32(one, p, r);

    const int32x4_t ki = vcvtq_s32_f32(kf);
    const float32x4_t pow2k = vreinterpretq_f32_s32(vshlq_n_s32(vaddq_s32(ki, vdupq_n_s32(127)), 23));
    return vmulq_f32(p, pow2k);
}
#endif

float fast_logsumexp_with_max(const float* x, int n, float mx) {
    if (n <= 0) return 0.0f;
#if defined(__aarch64__)
    const float32x4_t vmx = vdupq_n_f32(mx);
    float32x4_t s0 = vdupq_n_f32(0.0f), s1 = vdupq_n_f32(0.0f);
    float32x4_t s2 = vdupq_n_f32(0.0f), s3 = vdupq_n_f32(0.0f);
    int i = 0;
    for (; i + 16 <= n; i += 16) {
        s0 = vaddq_f32(s0, fast_exp_f32x4(vsubq_f32(vld1q_f32(x + i), vmx)));
        s1 = vaddq_f32(s1, fast_exp_f32x4(vsubq_f32(vld1q_f32(x + i + 4), vmx)));
        s2 = vaddq_f32(s2, fast_exp_f32x4(vsubq_f32(vld1q_f32(x + i + 8), vmx)));
        s3 = vaddq_f32(s3, fast_exp_f32x4(vsubq_f32(vld1q_f32(x + i + 12), vmx)));
    }
    for (; i + 4 <= n; i += 4) {
        s0 = vaddq_f32(s0, fast_exp_f32x4(vsubq_f32(vld1q_f32(x + i), vmx)));
    }
    float sum = vaddvq_f32(vaddq_f32(vaddq_f32(s0, s1), vaddq_f32(s2, s3)));
    for (; i < n; ++i) sum += fast_exp_scalar(x[i] - mx);
    return mx + std::log(sum);
#else
    float sum = 0.0f;
    for (int i = 0; i < n; ++i) sum += fast_exp_scalar(x[i] - mx);
    return mx + std::log(sum);
#endif
}

float fast_logsumexp(const float* x, int n) {
    if (n <= 0) return 0.0f;
#if defined(__aarch64__)
    float32x4_t vmx0 = vdupq_n_f32(-1e30f), vmx1 = vdupq_n_f32(-1e30f);
    int i = 0;
    for (; i + 8 <= n; i += 8) {
        vmx0 = vmaxq_f32(vmx0, vld1q_f32(x + i));
        vmx1 = vmaxq_f32(vmx1, vld1q_f32(x + i + 4));
    }
    float mx = vmaxvq_f32(vmaxq_f32(vmx0, vmx1));
    for (; i < n; ++i) mx = std::max(mx, x[i]);
    return fast_logsumexp_with_max(x, n, mx);
#else
    float mx = x[0];
    for (int i = 1; i < n; ++i) mx = std::max(mx, x[i]);
    return fast_logsumexp_with_max(x, n, mx);
#endif
}

inline void silu_mul_f32(const float* gate, const float* up, float* out, int n) {
#if defined(__aarch64__)
    const float32x4_t one = vdupq_n_f32(1.0f);
    int i = 0;
    for (; i + 8 <= n; i += 8) {
        const float32x4_t g0 = vld1q_f32(gate + i);
        const float32x4_t g1 = vld1q_f32(gate + i + 4);
        const float32x4_t u0 = vld1q_f32(up + i);
        const float32x4_t u1 = vld1q_f32(up + i + 4);
        const float32x4_t d0 = vaddq_f32(one, fast_exp_f32x4(vnegq_f32(g0)));
        const float32x4_t d1 = vaddq_f32(one, fast_exp_f32x4(vnegq_f32(g1)));
        vst1q_f32(out + i,     vmulq_f32(vdivq_f32(g0, d0), u0));
        vst1q_f32(out + i + 4, vmulq_f32(vdivq_f32(g1, d1), u1));
    }
    for (; i + 4 <= n; i += 4) {
        const float32x4_t g0 = vld1q_f32(gate + i);
        const float32x4_t u0 = vld1q_f32(up + i);
        const float32x4_t d0 = vaddq_f32(one, fast_exp_f32x4(vnegq_f32(g0)));
        vst1q_f32(out + i, vmulq_f32(vdivq_f32(g0, d0), u0));
    }
    for (; i < n; ++i) {
        const float g = gate[i];
        out[i] = (g / (1.0f + fast_exp_scalar(-g))) * up[i];
    }
#else
    for (int i = 0; i < n; ++i) {
        const float g = gate[i];
        out[i] = (g / (1.0f + fast_exp_scalar(-g))) * up[i];
    }
#endif
}

// ---- Vectorized float helpers for attention / RMSNorm / Conv1D / SiLU ----

inline float dot_f32(const float* a, const float* b, int n) {
#if defined(__aarch64__)
    float32x4_t s0 = vdupq_n_f32(0.0f), s1 = vdupq_n_f32(0.0f);
    int i = 0;
    for (; i + 8 <= n; i += 8) {
        s0 = vfmaq_f32(s0, vld1q_f32(a + i), vld1q_f32(b + i));
        s1 = vfmaq_f32(s1, vld1q_f32(a + i + 4), vld1q_f32(b + i + 4));
    }
    for (; i + 4 <= n; i += 4) s0 = vfmaq_f32(s0, vld1q_f32(a + i), vld1q_f32(b + i));
    float s = vaddvq_f32(vaddq_f32(s0, s1));
    for (; i < n; ++i) s += a[i] * b[i];
    return s;
#else
    float s = 0.0f;
    for (int i = 0; i < n; ++i) s += a[i] * b[i];
    return s;
#endif
}

inline void axpy_f32(float* y, const float* x, float a, int n) {
#if defined(__aarch64__)
    const float32x4_t va = vdupq_n_f32(a);
    int i = 0;
    for (; i + 8 <= n; i += 8) {
        vst1q_f32(y + i, vfmaq_f32(vld1q_f32(y + i), va, vld1q_f32(x + i)));
        vst1q_f32(y + i + 4, vfmaq_f32(vld1q_f32(y + i + 4), va, vld1q_f32(x + i + 4)));
    }
    for (; i + 4 <= n; i += 4) vst1q_f32(y + i, vfmaq_f32(vld1q_f32(y + i), va, vld1q_f32(x + i)));
    for (; i < n; ++i) y[i] += a * x[i];
#else
    for (int i = 0; i < n; ++i) y[i] += a * x[i];
#endif
}

inline void rmsnorm(const float* x, const float* w, float* y, int n) {
    const float ss = dot_f32(x, x, n) / (float)n;
    const float inv = 1.0f / std::sqrt(ss + 1e-5f);
#if defined(__aarch64__)
    const float32x4_t vinv = vdupq_n_f32(inv);
    int i = 0;
    for (; i + 4 <= n; i += 4) {
        vst1q_f32(y + i, vmulq_f32(vmulq_f32(vld1q_f32(x + i), vinv), vld1q_f32(w + i)));
    }
    for (; i < n; ++i) y[i] = x[i] * inv * w[i];
#else
    for (int i = 0; i < n; ++i) y[i] = x[i] * inv * w[i];
#endif
}

inline void apply_rope_split_half(float* x, const float* cos_buf, const float* sin_buf, int half) {
#if defined(__aarch64__)
    int i = 0;
    for (; i + 4 <= half; i += 4) {
        const float32x4_t x1 = vld1q_f32(x + i);
        const float32x4_t x2 = vld1q_f32(x + i + half);
        const float32x4_t c = vld1q_f32(cos_buf + i);
        const float32x4_t s = vld1q_f32(sin_buf + i);
        vst1q_f32(x + i,        vfmsq_f32(vmulq_f32(x1, c), x2, s));
        vst1q_f32(x + i + half, vfmaq_f32(vmulq_f32(x2, c), x1, s));
    }
    for (; i < half; ++i) {
        const float x1 = x[i];
        const float x2 = x[i + half];
        const float c = cos_buf[i], s = sin_buf[i];
        x[i]        = x1 * c - x2 * s;
        x[i + half] = x2 * c + x1 * s;
    }
#else
    for (int i = 0; i < half; ++i) {
        const float x1 = x[i];
        const float x2 = x[i + half];
        const float c = cos_buf[i], s = sin_buf[i];
        x[i]        = x1 * c - x2 * s;
        x[i + half] = x2 * c + x1 * s;
    }
#endif
}

struct Linear {
    int out = 0, in = 0;
    std::vector<float> scale;
    std::vector<int8_t> w;
    std::vector<float> bias;  // empty = none

    void apply_quantized(const int8_t* xq, float sx, float* y) const {
        matvec_i8(w.data(), scale.data(), bias.empty() ? nullptr : bias.data(), xq, sx, y, out, in);
    }

    void apply(const float* x, float* y, int8_t* xq) const {
        const float sx = quantize(x, in, xq);
        apply_quantized(xq, sx, y);
    }
};

struct LayerNorm {
    std::vector<float> w, b;
    void apply(const float* x, float* y, int n) const {
        float mean = 0.0f;
        for (int i = 0; i < n; ++i) mean += x[i];
        mean /= (float)n;
        float var = 0.0f;
        for (int i = 0; i < n; ++i) {
            const float d = x[i] - mean;
            var += d * d;
        }
        var /= (float)n;
        const float inv = 1.0f / std::sqrt(var + 1e-5f);
        for (int i = 0; i < n; ++i) y[i] = (x[i] - mean) * inv * w[i] + b[i];
    }
};

struct BufReader {
    const uint8_t* data = nullptr;
    size_t size = 0;
    size_t pos = 0;
    bool ok = true;

    bool read(void* dst, size_t n) {
        if (!ok || pos + n > size) return ok = false;
        std::memcpy(dst, data + pos, n);
        pos += n;
        return true;
    }
    bool skip(size_t n) {
        if (!ok || pos + n > size) return ok = false;
        pos += n;
        return true;
    }
    bool floats(std::vector<float>& v, size_t n) {
        v.resize(n);
        return read(v.data(), n * sizeof(float));
    }
    bool linear(Linear& l, int out, int in) {
        l.out = out;
        l.in = in;
        l.w.resize((size_t)out * in);
        return floats(l.scale, (size_t)out) && read(l.w.data(), l.w.size());
    }
};

struct MmapSlice {
    void* raw = nullptr;
    size_t raw_size = 0;
    const uint8_t* data = nullptr;
    size_t size = 0;

    bool map(int fd, int64_t off, int64_t len) {
        if (fd < 0 || off < 0 || len <= 0) return false;
        const long page_sz = sysconf(_SC_PAGESIZE);
        const int64_t page = page_sz > 0 ? (int64_t)page_sz : 4096;
        const int64_t aligned_off = off & ~(page - 1);
        const size_t delta = (size_t)(off - aligned_off);
        const size_t mlen = (size_t)len + delta;
        void* p = mmap(nullptr, mlen, PROT_READ, MAP_PRIVATE, fd, (off_t)aligned_off);
        if (p == MAP_FAILED) return false;
        raw = p;
        raw_size = mlen;
        data = (const uint8_t*)p + delta;
        size = (size_t)len;
        return true;
    }
    void release_ownership() {
        raw = nullptr;
        raw_size = 0;
        data = nullptr;
        size = 0;
    }
    ~MmapSlice() {
        if (raw) munmap(raw, raw_size);
    }
};

bool is_word_token(const std::string& t) {
    if (t.empty() || t[0] == '<') return false;
    for (unsigned char c : t) {
        if (c < 0x80 && !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '\'')) return false;
    }
    return true;
}

bool is_ascii(const std::string& s) {
    for (unsigned char c : s) if (c >= 0x80) return false;
    return true;
}

}  // namespace

struct NeuralLm::Body {
    int version = 1;
    int E = 0, d = 0, layers = 0, heads = 0, kv_heads = 0, ff = 0, ctx = 0, conv_k = 0;
    bool qk_norm = false;
    float rope_theta = 10000.0f;

    Linear up, down;

    // v1 fields
    std::vector<float> pos;  // [ctx][d]
    struct BlockV1 {
        LayerNorm ln1, ln2;
        Linear q, k, v, o, ff1, ff2;
    };
    std::vector<BlockV1> blocks_v1;
    LayerNorm ln_v1;

    // v2 fields (StripedLM: GatedCausalConv1D + RoPE-GQA + SwiGLU + RMSNorm)
    struct BlockV2 {
        uint8_t type = 0;  // 0 = RoPE-GQA, 1 = GatedCausalConv1D
        int state_idx = 0;
        std::vector<float> n1_w, n2_w;
        // RoPE-GQA
        Linear qkv, wo;
        std::vector<float> q_norm_w, k_norm_w;
        // GatedCausalConv1D
        Linear in_proj, out_proj;
        std::vector<float> dw_weight;  // [conv_k][d]
        std::vector<float> dw_bias;    // [d]
        // SwiGLU
        Linear ff_gu, ff_down;
    };
    std::vector<BlockV2> blocks_v2;
    std::vector<float> ln_w_v2;
    std::vector<float> rope_inv_freq;  // [head_dim / 2]

    // Caches and preallocated scratch (zero per-step heap allocations)
    std::vector<float> kc, vc;      // v1: [layers][ctx][d]; v2: [n_attn][ctx][kv_dim]
    std::vector<float> conv_u;      // v2: [n_conv][ctx][d]
    std::vector<float> e, h, a, q, y, f, att;
    std::vector<float> qkv, uv, conv_acc, gu, cos_buf, sin_buf;
    std::vector<int8_t> xq;
};

NeuralLm::~NeuralLm() {
    if (map_) munmap(map_, map_size_);
}

std::unique_ptr<NeuralLm> NeuralLm::load(const std::string& lmb_path, const std::string& lmv_path, std::string* error) {
    auto fail = [&](const std::string& msg) -> std::unique_ptr<NeuralLm> {
        if (error) *error = msg;
        return nullptr;
    };
    int lmb_fd = open(lmb_path.c_str(), O_RDONLY);
    if (lmb_fd < 0) return fail("cannot open " + lmb_path);
    struct stat st_b{};
    if (fstat(lmb_fd, &st_b) != 0) { close(lmb_fd); return fail("cannot stat " + lmb_path); }

    int lmv_fd = open(lmv_path.c_str(), O_RDONLY);
    if (lmv_fd < 0) { close(lmb_fd); return fail("cannot open " + lmv_path); }
    struct stat st_v{};
    if (fstat(lmv_fd, &st_v) != 0) { close(lmb_fd); close(lmv_fd); return fail("cannot stat " + lmv_path); }

    auto lm = load_fd(lmb_fd, 0, (int64_t)st_b.st_size, lmv_fd, 0, (int64_t)st_v.st_size, error);
    close(lmb_fd);
    close(lmv_fd);
    return lm;
}

std::unique_ptr<NeuralLm> NeuralLm::load_fd(int lmb_fd, int64_t lmb_off, int64_t lmb_len,
                                            int lmv_fd, int64_t lmv_off, int64_t lmv_len,
                                            std::string* error) {
    auto fail = [&](const std::string& msg) -> std::unique_ptr<NeuralLm> {
        if (error) *error = msg;
        return nullptr;
    };
    std::unique_ptr<NeuralLm> lm(new NeuralLm());

    // ---- 1. Vocabulary & embedding table (.lmv, kept mmapped) ----
    MmapSlice lmv_map;
    if (!lmv_map.map(lmv_fd, lmv_off, lmv_len)) return fail("mmap .lmv failed");
    const uint8_t* p = lmv_map.data;
    const size_t lmv_size = lmv_map.size;
    if (lmv_size < 24 || std::memcmp(p, "DBLM", 4) != 0) return fail("not a .lmv file");

    uint32_t lmv_ver = 0;
    std::memcpy(&lmv_ver, p + 4, 4);
    size_t words_off = 0, words_bytes = 0;

    if (lmv_ver == 1) {
        uint32_t h[5];
        std::memcpy(h, p + 4, sizeof(h));
        lm->lmv_version_ = 1;
        lm->V_ = (int)h[1];
        lm->E_ = (int)h[2];
        lm->ctx_ = (int)h[3];
        words_bytes = h[4];
        words_off = 24;
        if (lm->V_ < 3 || lm->E_ <= 0 || lm->E_ % 16 || lm->ctx_ <= 0 || words_bytes % 4)
            return fail("bad .lmv v1 header");
        const size_t need = 24 + words_bytes + (size_t)lm->V_ * 4 + (size_t)lm->V_ * lm->E_;
        if (lmv_size < need) return fail("truncated .lmv v1 file");
        lm->n_int8_ = lm->V_;
        lm->n_int4_ = 0;
        lm->group_size_ = 32;
        lm->has_bias_ = false;
        lm->out_bias_ = nullptr;
        lm->scale_i8_ = reinterpret_cast<const float*>(p + 24 + words_bytes);
        lm->table_i8_ = reinterpret_cast<const int8_t*>(p + 24 + words_bytes + (size_t)lm->V_ * 4);
    } else if (lmv_ver == 2) {
        if (lmv_size < 48) return fail("truncated .lmv v2 header");
        uint32_t h[8];
        std::memcpy(h, p + 4, sizeof(h));
        // Bytes 36..47 hold optional legacy self-norm/shortlist/reserved metadata; ignored since
        // scoring always computes exact logsumexp.

        lm->lmv_version_ = 2;
        lm->V_ = (int)h[1];
        lm->E_ = (int)h[2];
        lm->ctx_ = (int)h[3];
        words_bytes = h[4];
        lm->n_int8_ = (int)h[5];
        lm->group_size_ = (int)h[6];
        const uint32_t flags = h[7];
        lm->has_bias_ = (flags & 1u) != 0;

        if (lm->V_ < 3 || lm->E_ <= 0 || lm->E_ % 16 || lm->ctx_ <= 0 || words_bytes % 4 ||
            lm->n_int8_ < 0 || lm->n_int8_ > lm->V_) {
            return fail("bad .lmv v2 header");
        }
        lm->n_int4_ = lm->V_ - lm->n_int8_;
        if (lm->n_int4_ > 0 && (lm->group_size_ != 32 || lm->E_ % 32 != 0)) {
            return fail("unsupported .lmv v2 int4 group_size");
        }
        words_off = 48;
        size_t pos = 48 + words_bytes;
        if (pos > lmv_size) return fail("truncated .lmv v2 words");
        if (lm->has_bias_) {
            if (pos + (size_t)lm->V_ * 4 > lmv_size) return fail("truncated .lmv v2 bias");
            lm->out_bias_ = reinterpret_cast<const float*>(p + pos);
            pos += (size_t)lm->V_ * 4;
        }
        if (lm->n_int8_ > 0) {
            const size_t need8 = (size_t)lm->n_int8_ * 4 + (size_t)lm->n_int8_ * lm->E_;
            if (pos + need8 > lmv_size) return fail("truncated .lmv v2 int8 table");
            lm->scale_i8_ = reinterpret_cast<const float*>(p + pos);
            pos += (size_t)lm->n_int8_ * 4;
            lm->table_i8_ = reinterpret_cast<const int8_t*>(p + pos);
            pos += (size_t)lm->n_int8_ * lm->E_;
        }
        if (lm->n_int4_ > 0) {
            const size_t G = (size_t)(lm->E_ / 32);
            const size_t s4_bytes = (size_t)lm->n_int4_ * G * 2;
            const size_t s4_padded = (s4_bytes + 3) & ~size_t(3);
            const size_t d4_bytes = (size_t)lm->n_int4_ * (size_t)(lm->E_ / 2);
            if (pos + s4_padded + d4_bytes > lmv_size) return fail("truncated .lmv v2 int4 table");
            lm->scale_i4_ = reinterpret_cast<const uint16_t*>(p + pos);
            pos += s4_padded;
            lm->table_i4_ = p + pos;
            pos += d4_bytes;
        }
    } else {
        return fail("unsupported .lmv version");
    }

    const char* w = reinterpret_cast<const char*>(p + words_off);
    const char* wend = w + words_bytes;
    lm->tokens_.reserve(lm->V_);
    for (int i = 0; i < lm->V_; ++i) {
        if (w >= wend) return fail("bad .lmv word list");
        const size_t n = strnlen(w, (size_t)(wend - w));
        lm->tokens_.emplace_back(w, n);
        w += n + 1;
    }
    lm->index_.reserve((size_t)lm->V_ * 2);
    lm->word_.resize(lm->V_);
    lm->key_paths_.resize(lm->V_);
    for (int i = 0; i < lm->V_; ++i) {
        lm->index_.emplace(lm->tokens_[i], i);
        lm->word_[i] = i > 2 && is_word_token(lm->tokens_[i]);
        if (lm->word_[i]) lm->key_paths_[i] = key_path_of(lm->tokens_[i]);
    }
    lm->build_prefix_indices();

    // Transfer mmap ownership of .lmv to lm
    lm->map_ = lmv_map.raw;
    lm->map_size_ = lmv_map.raw_size;
    lmv_map.release_ownership();

    // ---- 2. Model body (.lmb, parsed into RAM and unmapped) ----
    MmapSlice lmb_map;
    if (!lmb_map.map(lmb_fd, lmb_off, lmb_len)) return fail("mmap .lmb failed");
    BufReader r{lmb_map.data, lmb_map.size, 0, true};
    char magic[4];
    uint32_t lmb_ver = 0;
    if (!r.read(magic, 4) || std::memcmp(magic, "DBLB", 4) != 0 || !r.read(&lmb_ver, 4))
        return fail("not a .lmb file");

    auto b = std::make_unique<Body>();
    if (lmb_ver == 1) {
        uint32_t hb[6];
        if (!r.read(hb, sizeof(hb))) return fail("bad .lmb v1 header");
        b->version = 1;
        b->E = (int)hb[0];
        b->d = (int)hb[1];
        b->layers = (int)hb[2];
        b->heads = (int)hb[3];
        b->kv_heads = b->heads;
        b->ff = (int)hb[4];
        b->ctx = (int)hb[5];
        if (b->E != lm->E_ || b->ctx != lm->ctx_ || b->d <= 0 || b->d % 16 || b->ff <= 0 || b->ff % 16 ||
            b->heads <= 0 || b->d % b->heads || b->layers <= 0 || b->layers > 64) {
            return fail(".lmb v1 does not match .lmv");
        }
        const int E = b->E, d = b->d, ff = b->ff, ctx = b->ctx;
        r.linear(b->up, d, E);
        r.floats(b->pos, (size_t)ctx * d);
        b->blocks_v1.resize(b->layers);
        for (auto& bl : b->blocks_v1) {
            r.floats(bl.ln1.w, d); r.floats(bl.ln1.b, d);
            r.linear(bl.q, d, d); r.linear(bl.k, d, d); r.linear(bl.v, d, d); r.linear(bl.o, d, d);
            r.floats(bl.ln2.w, d); r.floats(bl.ln2.b, d);
            r.linear(bl.ff1, ff, d); r.floats(bl.ff1.bias, ff);
            r.linear(bl.ff2, d, ff); r.floats(bl.ff2.bias, d);
        }
        r.floats(b->ln_v1.w, d); r.floats(b->ln_v1.b, d);
        r.linear(b->down, E, d);
        if (!r.ok || r.pos != r.size) return fail("bad .lmb v1 size");

        b->kc.assign((size_t)b->layers * ctx * d, 0.0f);
        b->vc.assign((size_t)b->layers * ctx * d, 0.0f);
        b->e.resize(E);
        b->h.resize(d); b->a.resize(d); b->q.resize(d); b->y.resize(d);
        b->f.resize(ff); b->att.resize(ctx);
        b->xq.resize(std::max({E, d, ff}));
    } else if (lmb_ver == 2) {
        uint32_t hb[9];
        float rope_theta = 10000.0f;
        if (!r.read(hb, sizeof(hb)) || !r.read(&rope_theta, 4)) return fail("bad .lmb v2 header");
        b->version = 2;
        b->E = (int)hb[0];
        b->d = (int)hb[1];
        b->layers = (int)hb[2];
        b->heads = (int)hb[3];
        b->kv_heads = (int)hb[4];
        b->ff = (int)hb[5];
        b->ctx = (int)hb[6];
        b->conv_k = (int)hb[7];
        const uint32_t flags = hb[8];
        b->qk_norm = (flags & 1u) != 0;
        b->rope_theta = rope_theta > 0.0f ? rope_theta : 10000.0f;
        if (b->E != lm->E_ || b->ctx != lm->ctx_ || b->d <= 0 || b->d % 16 || b->ff <= 0 || b->ff % 16 ||
            b->heads <= 0 || b->kv_heads <= 0 || b->d % b->heads || b->heads % b->kv_heads ||
            b->layers <= 0 || b->layers > 64 || b->conv_k < 1 || b->conv_k > 16) {
            return fail(".lmb v2 does not match .lmv");
        }
        std::vector<uint8_t> bt(b->layers);
        if (!r.read(bt.data(), bt.size())) return fail("truncated .lmb v2 block_types");
        const size_t bt_pad = (4 - (bt.size() % 4)) % 4;
        if (!r.skip(bt_pad)) return fail("truncated .lmb v2 pad");

        const int E = b->E, d = b->d, ff = b->ff, ctx = b->ctx;
        const int hd = d / b->heads;
        const int kv_dim = b->kv_heads * hd;

        r.linear(b->up, d, E);
        b->blocks_v2.resize(b->layers);
        int n_attn = 0, n_conv = 0;
        for (int i = 0; i < b->layers; ++i) {
            auto& bl = b->blocks_v2[i];
            bl.type = bt[i];
            r.floats(bl.n1_w, d);
            if (bl.type == 0) {
                bl.state_idx = n_attn++;
                r.linear(bl.qkv, d + 2 * kv_dim, d);
                if (b->qk_norm) {
                    r.floats(bl.q_norm_w, hd);
                    r.floats(bl.k_norm_w, hd);
                }
                r.linear(bl.wo, d, d);
            } else if (bl.type == 1) {
                bl.state_idx = n_conv++;
                r.linear(bl.in_proj, 2 * d, d);
                r.floats(bl.dw_weight, (size_t)b->conv_k * d);
                r.floats(bl.dw_bias, d);
                r.linear(bl.out_proj, d, d);
            } else {
                return fail("unknown .lmb v2 block type");
            }
            r.floats(bl.n2_w, d);
            r.linear(bl.ff_gu, 2 * ff, d);
            r.linear(bl.ff_down, d, ff);
        }
        r.floats(b->ln_w_v2, d);
        r.linear(b->down, E, d);
        if (!r.ok || r.pos != r.size) return fail("bad .lmb v2 size");

        const int half = hd / 2;
        b->rope_inv_freq.resize(half);
        for (int i = 0; i < half; ++i) {
            b->rope_inv_freq[i] = 1.0f / std::pow(b->rope_theta, (2.0f * (float)i) / (float)hd);
        }

        b->kc.assign((size_t)n_attn * ctx * kv_dim, 0.0f);
        b->vc.assign((size_t)n_attn * ctx * kv_dim, 0.0f);
        b->conv_u.assign((size_t)n_conv * ctx * d, 0.0f);
        b->e.resize(E);
        b->h.resize(d);
        b->a.resize(d);
        b->qkv.resize((size_t)d + 2 * kv_dim);
        b->uv.resize((size_t)2 * d);
        b->conv_acc.resize(d);
        b->y.resize(d);
        b->gu.resize((size_t)2 * ff);
        b->f.resize(ff);
        b->att.resize(ctx);
        b->cos_buf.resize(half);
        b->sin_buf.resize(half);
        b->xq.resize(std::max({E, d, ff}));
    } else {
        return fail("unsupported .lmb version");
    }

    lm->body_ = std::move(b);
    lm->z_.assign(lm->E_, 0.0f);
    lm->zs_.assign((size_t)lm->ctx_ * lm->E_, 0.0f);
    return lm;
}

void NeuralLm::build_prefix_indices() {
    kp_sorted_.clear();
    tok_sorted_.clear();
    kp_sorted_.reserve(V_);
    tok_sorted_.reserve(V_);
    for (int32_t id = 3; id < V_; ++id) {
        if (!word_[id]) continue;
        kp_sorted_.push_back(id);
        tok_sorted_.push_back(id);
    }
    std::sort(kp_sorted_.begin(), kp_sorted_.end(), [&](int32_t a, int32_t b) {
        const int cmp = key_paths_[a].compare(key_paths_[b]);
        return cmp != 0 ? cmp < 0 : a < b;
    });
    std::sort(tok_sorted_.begin(), tok_sorted_.end(), [&](int32_t a, int32_t b) {
        const int cmp = tokens_[a].compare(tokens_[b]);
        return cmp != 0 ? cmp < 0 : a < b;
    });
}

void NeuralLm::prefix_range(const std::string& prefix_lower, const std::string& prefix_kp, bool fold,
                            const int32_t*& out_begin, const int32_t*& out_end) const {
    const bool use_kp = fold && !prefix_kp.empty();
    const auto& vec = use_kp ? kp_sorted_ : tok_sorted_;
    const auto& strs = use_kp ? key_paths_ : tokens_;
    const std::string& key = use_kp ? prefix_kp : prefix_lower;
    const size_t klen = key.size();
    auto lo = std::lower_bound(vec.begin(), vec.end(), key, [&](int32_t id, const std::string& k) {
        return strs[id] < k;
    });
    auto hi = std::lower_bound(lo, vec.end(), key, [&](int32_t id, const std::string& k) {
        const std::string& s = strs[id];
        return s.size() >= klen && std::memcmp(s.data(), k.data(), klen) == 0;
    });
    out_begin = lo == vec.end() ? nullptr : &(*lo);
    out_end = lo == vec.end() ? nullptr : (out_begin + (hi - lo));
}

const char* neural_kernel_name() { return dot_name(); }

int32_t NeuralLm::id_of(const std::string& token) const {
    auto it = index_.find(token);
    return it == index_.end() ? 1 : it->second;
}

void NeuralLm::dequantize_emb(int32_t id, float* out) const {
    if (id < 0 || id >= V_) id = 1;
    if (id < n_int8_) {
        const int8_t* row = table_i8_ + (size_t)id * E_;
        const float sc = scale_i8_[id];
        for (int i = 0; i < E_; ++i) out[i] = (float)row[i] * sc;
    } else {
        const int r = id - n_int8_;
        const int G = E_ >> 5;
        const uint8_t* packed = table_i4_ + (size_t)r * (size_t)(E_ >> 1);
        const uint16_t* scales = scale_i4_ + (size_t)r * G;
        for (int g = 0; g < G; ++g) {
            const float sc = fp16_to_f32(scales[g]);
            const uint8_t* p = packed + g * 16;
            float* dst = out + g * 32;
            for (int j = 0; j < 16; ++j) {
                dst[j]      = (float)((int)(p[j] & 0x0Fu) - 8) * sc;
                dst[j + 16] = (float)((int)(p[j] >> 4) - 8) * sc;
            }
        }
    }
}

void NeuralLm::step(int32_t id) {
    if (body_->version == 2) step_v2(id);
    else step_v1(id);
}

void NeuralLm::step_v1(int32_t id) {
    Body& b = *body_;
    const int t = (int)window_.size();
    const int d = b.d, hd = d / b.heads;
    dequantize_emb(id, b.e.data());
    b.up.apply(b.e.data(), b.h.data(), b.xq.data());
    const float* pe = b.pos.data() + (size_t)t * d;
    for (int i = 0; i < d; ++i) b.h[i] += pe[i];
    const float inv_sqrt = 1.0f / std::sqrt((float)hd);

    for (int l = 0; l < b.layers; ++l) {
        auto& bl = b.blocks_v1[l];
        float* kc = b.kc.data() + (size_t)l * b.ctx * d;
        float* vc = b.vc.data() + (size_t)l * b.ctx * d;
        bl.ln1.apply(b.h.data(), b.a.data(), d);
        // Quantize b.a ONCE for Q, K, V
        const float sa = quantize(b.a.data(), d, b.xq.data());
        bl.q.apply_quantized(b.xq.data(), sa, b.q.data());
        bl.k.apply_quantized(b.xq.data(), sa, kc + (size_t)t * d);
        bl.v.apply_quantized(b.xq.data(), sa, vc + (size_t)t * d);

        for (int hh = 0; hh < b.heads; ++hh) {
            const float* qh = b.q.data() + hh * hd;
            float mx = -1e30f;
            for (int s = 0; s <= t; ++s) {
                const float* kh = kc + (size_t)s * d + hh * hd;
                b.att[s] = dot_f32(qh, kh, hd) * inv_sqrt;
                mx = std::max(mx, b.att[s]);
            }
            float sum = 0.0f;
            for (int s = 0; s <= t; ++s) sum += (b.att[s] = std::exp(b.att[s] - mx));
            const float inv_sum = 1.0f / sum;
            float* yh = b.y.data() + hh * hd;
            std::fill(yh, yh + hd, 0.0f);
            for (int s = 0; s <= t; ++s) {
                axpy_f32(yh, vc + (size_t)s * d + hh * hd, b.att[s] * inv_sum, hd);
            }
        }
        bl.o.apply(b.y.data(), b.a.data(), b.xq.data());
        for (int i = 0; i < d; ++i) b.h[i] += b.a[i];
        bl.ln2.apply(b.h.data(), b.a.data(), d);
        bl.ff1.apply(b.a.data(), b.f.data(), b.xq.data());
        for (int i = 0; i < b.ff; ++i) b.f[i] = 0.5f * b.f[i] * (1.0f + std::erf(b.f[i] * 0.70710678f));
        bl.ff2.apply(b.f.data(), b.a.data(), b.xq.data());
        for (int i = 0; i < d; ++i) b.h[i] += b.a[i];
    }
    b.ln_v1.apply(b.h.data(), b.a.data(), d);
    b.down.apply(b.a.data(), z_.data(), b.xq.data());
    std::memcpy(zs_.data() + (size_t)t * E_, z_.data(), sizeof(float) * E_);
    window_.push_back(id);
    ++steps;
}

void NeuralLm::step_v2(int32_t id) {
    Body& b = *body_;
    const int d = b.d, ff = b.ff, ctx = b.ctx;
    const int hd = d / b.heads;
    const int half = hd / 2;
    const int kv_dim = b.kv_heads * hd;
    const int gqa_group = b.heads / b.kv_heads;
    const int64_t abs_pos = start_pos_ + (int64_t)window_.size();
    const int slot = (int)(abs_pos % ctx);
    const int cur_len = (int)window_.size() + 1;
    const float inv_sqrt = 1.0f / std::sqrt((float)hd);

    // Precompute RoPE cos/sin for abs_pos once across all attention layers
    const float pos_f = (float)abs_pos;
    for (int i = 0; i < half; ++i) {
        const float theta = pos_f * b.rope_inv_freq[i];
        b.cos_buf[i] = std::cos(theta);
        b.sin_buf[i] = std::sin(theta);
    }

    dequantize_emb(id, b.e.data());
    b.up.apply(b.e.data(), b.h.data(), b.xq.data());

    for (int l = 0; l < b.layers; ++l) {
        auto& bl = b.blocks_v2[l];
        rmsnorm(b.h.data(), bl.n1_w.data(), b.a.data(), d);

        if (bl.type == 0) {
            // RoPE-GQA Attention
            bl.qkv.apply(b.a.data(), b.qkv.data(), b.xq.data());
            float* q = b.qkv.data();
            float* kc_layer = b.kc.data() + (size_t)bl.state_idx * ctx * kv_dim;
            float* vc_layer = b.vc.data() + (size_t)bl.state_idx * ctx * kv_dim;
            float* k_dst = kc_layer + (size_t)slot * kv_dim;
            float* v_dst = vc_layer + (size_t)slot * kv_dim;
            std::memcpy(k_dst, b.qkv.data() + d, sizeof(float) * kv_dim);
            std::memcpy(v_dst, b.qkv.data() + d + kv_dim, sizeof(float) * kv_dim);

            if (b.qk_norm) {
                for (int hh = 0; hh < b.heads; ++hh)
                    rmsnorm(q + hh * hd, bl.q_norm_w.data(), q + hh * hd, hd);
                for (int kh = 0; kh < b.kv_heads; ++kh)
                    rmsnorm(k_dst + kh * hd, bl.k_norm_w.data(), k_dst + kh * hd, hd);
            }
            for (int hh = 0; hh < b.heads; ++hh)
                apply_rope_split_half(q + hh * hd, b.cos_buf.data(), b.sin_buf.data(), half);
            for (int kh = 0; kh < b.kv_heads; ++kh)
                apply_rope_split_half(k_dst + kh * hd, b.cos_buf.data(), b.sin_buf.data(), half);

            for (int hh = 0; hh < b.heads; ++hh) {
                const int kvh = hh / gqa_group;
                const float* qh = q + hh * hd;
                float mx = -1e30f;
                for (int s = 0; s < cur_len; ++s) {
                    const int s_slot = (int)((start_pos_ + s) % ctx);
                    const float* kh = kc_layer + (size_t)s_slot * kv_dim + kvh * hd;
                    const float sc = dot_f32(qh, kh, hd) * inv_sqrt;
                    b.att[s] = sc;
                    if (sc > mx) mx = sc;
                }
                float sum = 0.0f;
#if defined(__aarch64__)
                {
                    const float32x4_t vmx = vdupq_n_f32(mx);
                    float32x4_t vs = vdupq_n_f32(0.0f);
                    int s = 0;
                    for (; s + 4 <= cur_len; s += 4) {
                        const float32x4_t e = fast_exp_f32x4(vsubq_f32(vld1q_f32(b.att.data() + s), vmx));
                        vst1q_f32(b.att.data() + s, e);
                        vs = vaddq_f32(vs, e);
                    }
                    sum = vaddvq_f32(vs);
                    for (; s < cur_len; ++s) sum += (b.att[s] = fast_exp_scalar(b.att[s] - mx));
                }
#else
                for (int s = 0; s < cur_len; ++s) sum += (b.att[s] = fast_exp_scalar(b.att[s] - mx));
#endif
                const float inv_sum = 1.0f / sum;
                float* yh = b.y.data() + hh * hd;
                std::fill(yh, yh + hd, 0.0f);
                for (int s = 0; s < cur_len; ++s) {
                    const int s_slot = (int)((start_pos_ + s) % ctx);
                    const float* vh = vc_layer + (size_t)s_slot * kv_dim + kvh * hd;
                    axpy_f32(yh, vh, b.att[s] * inv_sum, hd);
                }
            }
            bl.wo.apply(b.y.data(), b.a.data(), b.xq.data());
            for (int i = 0; i < d; ++i) b.h[i] += b.a[i];
        } else {
            // Gated Causal Depthwise Conv1D (k=4, ring buffer state)
            bl.in_proj.apply(b.a.data(), b.uv.data(), b.xq.data());
            const float* u = b.uv.data();
            const float* v = b.uv.data() + d;
            float* u_ring = b.conv_u.data() + (size_t)bl.state_idx * ctx * d;
            std::memcpy(u_ring + (size_t)slot * d, u, sizeof(float) * d);

            const float* dw0 = bl.dw_weight.data();
            const float* dwb = bl.dw_bias.data();
            float* acc = b.conv_acc.data();
            for (int i = 0; i < d; ++i) acc[i] = dwb[i] + u[i] * dw0[i];

            const int taps = std::min(b.conv_k, cur_len);
            for (int j = 1; j < taps; ++j) {
                const int s_slot = (int)((abs_pos - j) % ctx);
                const float* uj = u_ring + (size_t)s_slot * d;
                const float* dwj = bl.dw_weight.data() + (size_t)j * d;
#if defined(__aarch64__)
                int i = 0;
                for (; i + 4 <= d; i += 4) {
                    vst1q_f32(acc + i, vfmaq_f32(vld1q_f32(acc + i), vld1q_f32(uj + i), vld1q_f32(dwj + i)));
                }
                for (; i < d; ++i) acc[i] += uj[i] * dwj[i];
#else
                for (int i = 0; i < d; ++i) acc[i] += uj[i] * dwj[i];
#endif
            }
            silu_mul_f32(v, acc, acc, d);
            bl.out_proj.apply(acc, b.a.data(), b.xq.data());
            for (int i = 0; i < d; ++i) b.h[i] += b.a[i];
        }

        // SwiGLU FFN
        rmsnorm(b.h.data(), bl.n2_w.data(), b.a.data(), d);
        bl.ff_gu.apply(b.a.data(), b.gu.data(), b.xq.data());
        const float* gate = b.gu.data();
        const float* up = b.gu.data() + ff;
        silu_mul_f32(gate, up, b.f.data(), ff);
        bl.ff_down.apply(b.f.data(), b.a.data(), b.xq.data());
        for (int i = 0; i < d; ++i) b.h[i] += b.a[i];
    }

    rmsnorm(b.h.data(), b.ln_w_v2.data(), b.a.data(), d);
    b.down.apply(b.a.data(), z_.data(), b.xq.data());
    std::memcpy(zs_.data() + (size_t)slot * E_, z_.data(), sizeof(float) * E_);
    window_.push_back(id);
    ++steps;
}

const float* NeuralLm::hidden(const std::vector<int32_t>& ids_in) {
    if (ids_in.empty()) return nullptr;
    size_t n = ids_in.size();
    const int32_t* ids = ids_in.data();
    const bool is_v2 = body_->version == 2;

    // For v2 RoPE ring buffer, clamp view to the last ctx_ tokens directly.
    if (is_v2 && n > (size_t)ctx_) {
        ids += (n - (size_t)ctx_);
        n = (size_t)ctx_;
    }

    const size_t w = window_.size();
    size_t best_shift = 0, best_start = 0, best_keep = 0, best_cost = SIZE_MAX;

    // Case 1 (v1 & v2): prefix match against window_[0..]
    for (size_t st = 0; w > 0 && st < n; ++st) {
        if (ids[st] != window_[0] || n - st > (size_t)ctx_) continue;
        if (st > 0 && n - st < (size_t)kRestart) continue;
        size_t m = 0;
        while (m < w && st + m < n && ids[st + m] == window_[m]) ++m;
        const size_t cost = n - st - m;
        if (m > 0 && (cost < best_cost || (cost == best_cost && m > best_keep))) {
            best_shift = 0; best_start = st; best_keep = m; best_cost = cost;
        }
    }

    // Case 2 (v2 shift-invariant RoPE ring slide): slide window_ forward by `shift > 0` without restarting!
    if (is_v2 && w > 0 && n >= (size_t)kRestart) {
        for (size_t shift = 1; shift < w; ++shift) {
            if (window_[shift] != ids[0]) continue;
            size_t m = 0;
            while (shift + m < w && m < n && window_[shift + m] == ids[m]) ++m;
            if (m < (size_t)kRestart) continue;
            const size_t cost = n - m;
            if (cost < best_cost || (cost == best_cost && m > best_keep)) {
                best_shift = shift; best_start = 0; best_keep = m; best_cost = cost;
            }
        }
    }

    const size_t restart = is_v2
                               ? std::min(n, (size_t)ctx_)
                               : ((ids[0] == 2 && n <= (size_t)ctx_) ? n : std::min(n, (size_t)kRestart));

    if (best_cost != SIZE_MAX && best_cost < restart) {
        if (best_shift == 0 && best_keep == w && best_cost == 0) {
            ++cached;
            return z_.data();
        }
        if (best_shift > 0) {
            window_.erase(window_.begin(), window_.begin() + (ptrdiff_t)best_shift);
            start_pos_ += (int64_t)best_shift;
        }
        window_.resize(best_keep);
        if (best_cost == 0) {
            const int slot = (int)((start_pos_ + (int64_t)best_keep - 1) % ctx_);
            std::memcpy(z_.data(), zs_.data() + (size_t)slot * E_, sizeof(float) * E_);
            return z_.data();
        }
        for (size_t i = best_start + best_keep; i < n; ++i) step(ids[i]);
        return z_.data();
    }

    ++restarts;
    window_.clear();
    start_pos_ = 0;
    for (size_t i = n - restart; i < n; ++i) step(ids[i]);
    return z_.data();
}

float NeuralLm::logits_with_max(const float* z, float* out) const {
    int8_t stack_zq[512];
    std::vector<int8_t> heap_zq;
    int8_t* zq = E_ <= 512 ? stack_zq : (heap_zq.resize(E_), heap_zq.data());
    const float sz = quantize(z, E_, zq);
    float mx = -1e30f;
    if (n_int8_ > 0) {
        matvec_i8(table_i8_, scale_i8_, out_bias_, zq, sz, out, n_int8_, E_, &mx);
    }
    if (n_int4_ > 0) {
        matvec_i4(table_i4_, scale_i4_, out_bias_ ? (out_bias_ + n_int8_) : nullptr,
                  zq, sz, out + n_int8_, n_int4_, E_, &mx);
    }
    return mx;
}

void NeuralLm::logits(const float* z, float* out) const {
    int8_t stack_zq[512];
    std::vector<int8_t> heap_zq;
    int8_t* zq = E_ <= 512 ? stack_zq : (heap_zq.resize(E_), heap_zq.data());
    const float sz = quantize(z, E_, zq);
    if (n_int8_ > 0) {
        matvec_i8(table_i8_, scale_i8_, out_bias_, zq, sz, out, n_int8_, E_);
    }
    if (n_int4_ > 0) {
        matvec_i4(table_i4_, scale_i4_, out_bias_ ? (out_bias_ + n_int8_) : nullptr,
                  zq, sz, out + n_int8_, n_int4_, E_);
    }
}

const float* NeuralLm::distribution(const std::vector<int32_t>& ids, float* lse) {
    if (ids.empty()) return nullptr;
    if (ids != cached_ids_ || cached_logits_.empty()) {
        const float* z = hidden(ids);
        if (!z) return nullptr;
        cached_logits_.resize(V_);
        const float mx = logits_with_max(z, cached_logits_.data());
        cached_lse_ = fast_logsumexp_with_max(cached_logits_.data(), V_, mx);
        cached_ids_ = ids;
    }
    *lse = cached_lse_;
    return cached_logits_.data();
}

void NeuralLm::prefetch(const std::vector<int32_t>& ids) {
    if (ids.empty()) return;
    // Always warm full calibrated logits + exact fast_logsumexp so both tap completions and
    // bilingual swipe fusion hit the exact probability cache in <0.1 ms with zero P>1 drift.
    float lse = 0.0f;
    distribution(ids, &lse);
}

std::vector<std::pair<float, int32_t>> NeuralLm::top_words(const std::vector<int32_t>& ids,
                                                           const std::string& prefix_lower,
                                                           const std::string& prefix_kp,
                                                           bool fold,
                                                           size_t keep) {
    std::vector<std::pair<float, int32_t>> heap;
    if (ids.empty() || keep == 0) return heap;
    heap.reserve(keep + 1);
    auto worse = [](const std::pair<float, int32_t>& a, const std::pair<float, int32_t>& b) {
        return a.first > b.first;
    };
    auto push_cand = [&](float score, int32_t id) {
        if (heap.size() < keep) {
            heap.emplace_back(score, id);
            std::push_heap(heap.begin(), heap.end(), worse);
        } else if (score > heap.front().first) {
            std::pop_heap(heap.begin(), heap.end(), worse);
            heap.back() = {score, id};
            std::push_heap(heap.begin(), heap.end(), worse);
        }
    };

    // Always use exact calibrated distribution() (4-row SDOT + NEON INT4-g32 + NEON fast_logsumexp,
    // cached per context) so bilingual language mixing and 0.7/0.3 neural+ngram interpolation use
    // exact log probabilities summing to 1.0 (never P > 1 and zero shortlist misses).
    float lse = 0.0f;
    const float* lg = distribution(ids, &lse);
    if (!lg) return heap;
    if (!prefix_lower.empty()) {
        const int32_t* beg = nullptr;
        const int32_t* end = nullptr;
        prefix_range(prefix_lower, prefix_kp, fold, beg, end);
        for (const int32_t* it = beg; it < end; ++it) {
            const int32_t id = *it;
            if (heap.size() == keep && lg[id] <= heap.front().first) continue;
            if (tokens_[id] == prefix_lower) continue;
            push_cand(lg[id], id);
        }
        for (auto& e : heap) e.first -= lse;
    } else {
        for (int32_t id = 3; id < V_; ++id) {
            if (!word_[id]) continue;
            if (heap.size() == keep && lg[id] <= heap.front().first) continue;
            push_cand(lg[id], id);
        }
        for (auto& e : heap) e.first -= lse;
    }

    std::sort(heap.begin(), heap.end(), worse);
    return heap;
}

namespace {

// Unigram log probability of a token: Zipf rank in the 50k LM, falling back to the 300k+ KpDictionary
// before returning log(1e-7) so double-OOV context words still identify the active language.
float emission_logp(const NeuralLanguageInput& in, const std::string& tok) {
    if (in.model) {
        const int32_t id = in.model->id_of(tok);
        if (id > 2) {
            const float h = std::log((float)in.model->vocab()) + 0.5772f;
            return -std::log((float)(id - 2)) - std::log(h);
        }
    }
    if (in.dict && in.dict->lower_id(tok) >= 0) return in.dict->logp(tok);
    return std::log(1e-7f);
}

bool is_language_neutral(const std::string& tok) {
    return tok.empty() || tok == "<num>" || tok == "<bos>" || !(std::isalpha((unsigned char)tok[0]) || (unsigned char)tok[0] >= 0x80);
}

std::vector<int32_t> build_context_ids(const NeuralLm& lm, const std::vector<std::string>& context, bool bos) {
    std::vector<int32_t> ids;
    ids.reserve(context.size() + 1);
    if (bos || context.empty()) ids.push_back(2);
    for (const auto& t : context) ids.push_back(lm.id_of(t));
    return ids;
}

}  // namespace

void prefetch_neural(const std::vector<NeuralLm*>& models,
                     const std::vector<std::string>& context, bool bos) {
    if (models.empty()) return;
    helper_pool().parallel_for(models.size(), [&](size_t k) {
        NeuralLm* lm = models[k];
        if (!lm) return;
        const auto ids = build_context_ids(*lm, context, bos);
        std::lock_guard<std::mutex> lock(lm->mutex());
        lm->prefetch(ids);
    });
}

std::vector<float> neural_language_posterior(const std::vector<NeuralLanguageInput>& langs,
                                             const std::vector<std::string>& context, float switch_p) {
    const size_t L = langs.size();
    std::vector<float> post(L, 1.0f / (float)L);
    if (L < 2) return std::vector<float>(L, 0.0f);
    for (const auto& tok : context) {
        if (is_language_neutral(tok)) continue;
        std::vector<float> lp(L);
        float mx = -1e30f;
        for (size_t k = 0; k < L; ++k) {
            const float prior = (1 - switch_p) * post[k] + switch_p * (1 - post[k]) / (float)(L - 1);
            lp[k] = std::log(std::max(prior, 1e-9f)) + emission_logp(langs[k], tok);
            mx = std::max(mx, lp[k]);
        }
        float sum = 0;
        for (size_t k = 0; k < L; ++k) sum += (post[k] = std::exp(lp[k] - mx));
        for (auto& p : post) p /= sum;
    }
    // prior for the next word (it may switch language)
    std::vector<float> out(L);
    for (size_t k = 0; k < L; ++k)
        out[k] = std::log(std::max((1 - switch_p) * post[k] + switch_p * (1 - post[k]) / (float)(L - 1), 1e-9f));
    return out;
}

std::vector<NeuralPrediction> predict_neural(const std::vector<NeuralLanguageInput>& langs,
                                             const std::vector<std::string>& context, bool bos,
                                             const std::string& prefix_lower, int max_results, float switch_p) {
    std::vector<NeuralPrediction> out;
    if (langs.empty() || max_results <= 0) return out;
    const auto lpost = neural_language_posterior(langs, context, switch_p);
    const bool fold = !prefix_lower.empty() && is_ascii(prefix_lower);
    const std::string prefix_kp = fold ? key_path_of(prefix_lower) : std::string();
    const size_t keep = (size_t)max_results * 3;

    struct PerLangCand {
        std::string lower;
        std::string surface;
        float score;
    };
    std::vector<std::vector<PerLangCand>> per_lang(langs.size());

    helper_pool().parallel_for(langs.size(), [&](size_t k) {
        NeuralLm* lm = langs[k].model;
        if (!lm) return;
        const auto ids = build_context_ids(*lm, context, bos);
        std::vector<std::pair<float, int32_t>> top;
        {
            std::lock_guard<std::mutex> lock(lm->mutex());
            top = lm->top_words(ids, prefix_lower, prefix_kp, fold, keep);
        }
        auto& dst = per_lang[k];
        dst.reserve(top.size());
        for (const auto& [lp, id] : top) {
            const std::string& w = lm->token(id);
            std::string surface = w;
            if (langs[k].dict) {
                const int32_t did = langs[k].dict->lower_id(w);
                if (did < 0) continue;
                surface = std::string(langs[k].dict->surface_of((uint32_t)did));
            }
            dst.push_back({w, std::move(surface), lp + lpost[k]});
        }
    });

    // Deterministic merge across languages in order 0..L-1
    struct Cand { float score; int lang; std::string surface; };
    std::unordered_map<std::string, Cand> best;
    for (size_t k = 0; k < langs.size(); ++k) {
        for (auto& c : per_lang[k]) {
            auto it = best.find(c.lower);
            if (it == best.end()) {
                best.emplace(std::move(c.lower), Cand{c.score, (int)k, std::move(c.surface)});
            } else {
                const float m = std::max(it->second.score, c.score);
                const float merged = m + std::log(std::exp(it->second.score - m) + std::exp(c.score - m));
                if (c.score > it->second.score) {
                    it->second.lang = (int)k;
                    it->second.surface = std::move(c.surface);
                }
                it->second.score = merged;
            }
        }
    }

    out.reserve(best.size());
    for (auto& kv : best) out.push_back({std::move(kv.second.surface), kv.second.score, kv.second.lang});
    std::sort(out.begin(), out.end(), [](const NeuralPrediction& a, const NeuralPrediction& b) {
        return a.score != b.score ? a.score > b.score : a.word < b.word;
    });
    if ((int)out.size() > max_results) out.resize(max_results);
    return out;
}

std::vector<float> neural_word_logp(const std::vector<NeuralLanguageInput>& langs,
                                    const std::vector<std::string>& context, bool bos,
                                    const std::vector<std::string>& words_lower, float switch_p,
                                    float* unk_logp) {
    std::vector<float> out(words_lower.size(), -INFINITY);
    float unk = -INFINITY;
    auto add = [](float& o, float lp) {
        if (o == -INFINITY) o = lp;
        else { const float m = std::max(o, lp); o = m + std::log(std::exp(o - m) + std::exp(lp - m)); }
    };
    if (langs.empty() || words_lower.empty()) {
        if (unk_logp) *unk_logp = unk;
        return out;
    }
    const auto lpost = neural_language_posterior(langs, context, switch_p);
    const size_t W = words_lower.size();

    struct LangResult {
        float unk = -INFINITY;
        std::vector<float> lp;
    };
    std::vector<LangResult> results(langs.size());

    helper_pool().parallel_for(langs.size(), [&](size_t k) {
        NeuralLm* lm = langs[k].model;
        if (!lm) return;
        const auto ids = build_context_ids(*lm, context, bos);
        auto& res = results[k];
        res.lp.assign(W, -INFINITY);

        std::lock_guard<std::mutex> lock(lm->mutex());
        float lse = 0.0f;
        const float* lg = lm->distribution(ids, &lse);
        if (!lg) return;
        const float offset = lpost[k] - lse;
        res.unk = lg[1] + offset;
        for (size_t i = 0; i < W; ++i) {
            const int32_t id = lm->id_of(words_lower[i]);
            if (id <= 2 || !lm->is_word(id)) continue;
            res.lp[i] = lg[id] + offset;
        }
    });

    // Deterministic merge in order k = 0..L-1
    for (size_t k = 0; k < langs.size(); ++k) {
        if (results[k].lp.empty()) continue;
        add(unk, results[k].unk);
        for (size_t i = 0; i < W; ++i) {
            if (results[k].lp[i] != -INFINITY) add(out[i], results[k].lp[i]);
        }
    }
    if (unk_logp) *unk_logp = unk;
    return out;
}

void fuse_neural(std::vector<Ranked>& ranked, const std::vector<KpDictionary*>& dicts, const KpDictionary* user,
                 const std::vector<NeuralLanguageInput>& langs, const std::vector<std::string>& tokens, bool bos,
                 const NeuralFusion& f) {
    if (ranked.empty() || langs.empty()) return;
    const bool context = !tokens.empty();
    float w = context ? f.weight : f.weight_no_context;
    if (context && ranked.size() > 1 && f.margin_scale > 0)
        w += (f.weight_max - f.weight) * std::exp(-std::max(0.0f, ranked[0].score - ranked[1].score) / f.margin_scale);
    if (w == 0.0f) return;
    std::vector<std::string> lows;
    lows.reserve(ranked.size());
    for (const auto& r : ranked) lows.push_back(to_lower_utf8(r.word));
    float unk = -INFINITY;
    const auto lp = neural_word_logp(langs, tokens, bos, lows, 0.05f, &unk);
    if (!std::isfinite(unk)) unk = std::log(1e-3f);
    for (size_t i = 0; i < ranked.size(); ++i) {
        auto& r = ranked[i];
        float l = lp[i];
        if (!std::isfinite(l)) {
            float ug = -30.0f;
            const KpDictionary* d = r.dict >= 0 && r.dict < (int)dicts.size() ? dicts[r.dict] : nullptr;
            if (d && d != user) {
                const int32_t id = d->lower_id(lows[i]);
                if (id >= 0) ug = d->logp_id((uint32_t)id);
            } else {
                ug = -16.0f;
                for (const auto* o : dicts) {
                    if (o == user) continue;
                    const int32_t id = o->lower_id(lows[i]);
                    if (id >= 0) ug = std::max(ug, o->logp_id((uint32_t)id));
                }
            }
            l = unk + ug + f.oov;
        }
        r.score += w * l;
        if (context) r.score += f.lambda_delta * r.freq + f.nu_delta * r.ctx;
    }
    std::stable_sort(ranked.begin(), ranked.end(), [](const Ranked& a, const Ranked& b) { return a.score > b.score; });
}

}  // namespace droneswipe
