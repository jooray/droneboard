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
#include <fstream>
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

namespace droneswipe {

namespace {

// ---- int8 dot products (n is a multiple of 16) ----

int32_t dot_i8_scalar(const int8_t* a, const int8_t* b, int n) {
    int32_t acc = 0;
    for (int i = 0; i < n; ++i) acc += (int32_t)a[i] * (int32_t)b[i];
    return acc;
}

#if defined(__aarch64__)
int32_t dot_i8_neon(const int8_t* a, const int8_t* b, int n) {
    int32x4_t acc = vdupq_n_s32(0);
    for (int i = 0; i < n; i += 16) {
        const int8x16_t x = vld1q_s8(a + i), y = vld1q_s8(b + i);
        int16x8_t p = vmull_s8(vget_low_s8(x), vget_low_s8(y));
        p = vmlal_s8(p, vget_high_s8(x), vget_high_s8(y));  // |sum of 2 products| <= 2 * 127 * 127, fits int16
        acc = vpadalq_s16(acc, p);
    }
    return vaddvq_s32(acc);
}

__attribute__((target("dotprod")))
int32_t dot_i8_sdot(const int8_t* a, const int8_t* b, int n) {
    int32x4_t acc0 = vdupq_n_s32(0), acc1 = vdupq_n_s32(0);
    int i = 0;
    for (; i + 32 <= n; i += 32) {
        acc0 = vdotq_s32(acc0, vld1q_s8(a + i), vld1q_s8(b + i));
        acc1 = vdotq_s32(acc1, vld1q_s8(a + i + 16), vld1q_s8(b + i + 16));
    }
    for (; i < n; i += 16) acc0 = vdotq_s32(acc0, vld1q_s8(a + i), vld1q_s8(b + i));
    return vaddvq_s32(vaddq_s32(acc0, acc1));
}
#endif

using DotFn = int32_t (*)(const int8_t*, const int8_t*, int);

DotFn pick_dot() {
#if defined(__aarch64__)
#if defined(__ANDROID__)
    if (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) return dot_i8_sdot;
#elif defined(__APPLE__)
    return dot_i8_sdot;  // every Apple Silicon core has dotprod
#endif
    return dot_i8_neon;
#else
    return dot_i8_scalar;
#endif
}

const DotFn kDot = pick_dot();

const char* dot_name() {
#if defined(__aarch64__)
    if (kDot == dot_i8_sdot) return "sdot";
    if (kDot == dot_i8_neon) return "neon";
#endif
    return "scalar";
}

// symmetric per-vector int8 quantization; returns the scale
float quantize(const float* x, int n, int8_t* q) {
    float mx = 0;
    for (int i = 0; i < n; ++i) mx = std::max(mx, std::fabs(x[i]));
    if (mx <= 0) {
        std::memset(q, 0, n);
        return 0;
    }
    const float s = mx / 127.0f, inv = 127.0f / mx;
    for (int i = 0; i < n; ++i) q[i] = (int8_t)std::lrintf(x[i] * inv);
    return s;
}

struct Linear {
    int out = 0, in = 0;
    std::vector<float> scale;
    std::vector<int8_t> w;
    std::vector<float> bias;  // empty = none
    // y[out] = W x (+ bias); xq: scratch of in bytes
    void apply(const float* x, float* y, int8_t* xq) const {
        const float sx = quantize(x, in, xq);
        const int8_t* row = w.data();
        for (int o = 0; o < out; ++o, row += in) {
            y[o] = (float)kDot(row, xq, in) * scale[o] * sx + (bias.empty() ? 0.0f : bias[o]);
        }
    }
};

struct Norm {
    std::vector<float> w, b;
    void apply(const float* x, float* y, int n) const {
        float mean = 0;
        for (int i = 0; i < n; ++i) mean += x[i];
        mean /= n;
        float var = 0;
        for (int i = 0; i < n; ++i) var += (x[i] - mean) * (x[i] - mean);
        var /= n;
        const float inv = 1.0f / std::sqrt(var + 1e-5f);
        for (int i = 0; i < n; ++i) y[i] = (x[i] - mean) * inv * w[i] + b[i];
    }
};

struct Reader {
    std::vector<char> data;
    size_t pos = 0;
    bool ok = true;
    bool read(void* dst, size_t n) {
        if (!ok || pos + n > data.size()) return ok = false;
        std::memcpy(dst, data.data() + pos, n);
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
        return floats(l.scale, out) && read(l.w.data(), l.w.size());
    }
};

bool is_word_token(const std::string& t) {
    if (t.empty() || t[0] == '<') return false;
    for (unsigned char c : t) {
        if (c < 0x80 && !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '\'')) return false;
    }
    return true;
}

bool starts_with(const std::string& s, const std::string& p) {
    return s.size() >= p.size() && std::memcmp(s.data(), p.data(), p.size()) == 0;
}

bool is_ascii(const std::string& s) {
    for (unsigned char c : s) if (c >= 0x80) return false;
    return true;
}

}  // namespace

struct NeuralLm::Body {
    int E = 0, d = 0, layers = 0, heads = 0, ff = 0, ctx = 0;
    Linear up, down;
    std::vector<float> pos;  // [ctx][d]
    struct Block {
        Norm ln1, ln2;
        Linear q, k, v, o, ff1, ff2;
    };
    std::vector<Block> blocks;
    Norm ln;
    // key/value cache [layer][ctx][d] and scratch
    std::vector<float> kc, vc;
    std::vector<float> h, a, q, y, f, att;
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
    std::unique_ptr<NeuralLm> lm(new NeuralLm());
    // vocabulary and embedding table (mmap)
    int fd = open(lmv_path.c_str(), O_RDONLY);
    if (fd < 0) return fail("cannot open " + lmv_path);
    struct stat st {};
    if (fstat(fd, &st) != 0) { close(fd); return fail("cannot stat " + lmv_path); }
    lm->map_size_ = (size_t)st.st_size;
    lm->map_ = mmap(nullptr, lm->map_size_, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (lm->map_ == MAP_FAILED) { lm->map_ = nullptr; return fail("mmap failed"); }
    const char* p = (const char*)lm->map_;
    uint32_t h[5];
    if (lm->map_size_ < 24 || std::memcmp(p, "DBLM", 4) != 0) return fail("not a .lmv file");
    std::memcpy(h, p + 4, sizeof(h));
    if (h[0] != 1) return fail("unsupported .lmv version");
    lm->V_ = (int)h[1];
    lm->E_ = (int)h[2];
    lm->ctx_ = (int)h[3];
    const size_t words_bytes = h[4];
    if (lm->V_ < 3 || lm->E_ <= 0 || lm->E_ % 16 || lm->ctx_ <= 0 || words_bytes % 4) return fail("bad .lmv header");
    const size_t need = 24 + words_bytes + (size_t)lm->V_ * 4 + (size_t)lm->V_ * lm->E_;
    if (lm->map_size_ < need) return fail("truncated .lmv file");
    const char* w = p + 24;
    const char* wend = p + 24 + words_bytes;
    lm->tokens_.reserve(lm->V_);
    for (int i = 0; i < lm->V_; ++i) {
        if (w >= wend) return fail("bad .lmv word list");
        size_t n = strnlen(w, wend - w);
        lm->tokens_.emplace_back(w, n);
        w += n + 1;
    }
    lm->scale_ = (const float*)(p + 24 + words_bytes);
    lm->table_ = (const int8_t*)(p + 24 + words_bytes + (size_t)lm->V_ * 4);
    lm->index_.reserve(lm->V_ * 2);
    lm->word_.resize(lm->V_);
    lm->key_paths_.resize(lm->V_);
    for (int i = 0; i < lm->V_; ++i) {
        lm->index_.emplace(lm->tokens_[i], i);
        lm->word_[i] = i > 2 && is_word_token(lm->tokens_[i]);
        if (lm->word_[i]) lm->key_paths_[i] = key_path_of(lm->tokens_[i]);
    }

    // body (copied to memory)
    std::ifstream in(lmb_path, std::ios::binary);
    if (!in) return fail("cannot open " + lmb_path);
    Reader r;
    r.data.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    char magic[4];
    uint32_t hb[7];
    if (!r.read(magic, 4) || std::memcmp(magic, "DBLB", 4) != 0 || !r.read(hb, sizeof(hb))) return fail("not a .lmb file");
    if (hb[0] != 1) return fail("unsupported .lmb version");
    auto b = std::make_unique<Body>();
    b->E = (int)hb[1]; b->d = (int)hb[2]; b->layers = (int)hb[3]; b->heads = (int)hb[4]; b->ff = (int)hb[5]; b->ctx = (int)hb[6];
    if (b->E != lm->E_ || b->ctx != lm->ctx_ || b->d <= 0 || b->d % 16 || b->ff % 16 || b->heads <= 0 ||
        b->d % b->heads || b->layers <= 0 || b->layers > 64)
        return fail(".lmb does not match .lmv");
    const int E = b->E, d = b->d, ff = b->ff, ctx = b->ctx;
    r.linear(b->up, d, E);
    r.floats(b->pos, (size_t)ctx * d);
    b->blocks.resize(b->layers);
    for (auto& bl : b->blocks) {
        r.floats(bl.ln1.w, d); r.floats(bl.ln1.b, d);
        r.linear(bl.q, d, d); r.linear(bl.k, d, d); r.linear(bl.v, d, d); r.linear(bl.o, d, d);
        r.floats(bl.ln2.w, d); r.floats(bl.ln2.b, d);
        r.linear(bl.ff1, ff, d); r.floats(bl.ff1.bias, ff);
        r.linear(bl.ff2, d, ff); r.floats(bl.ff2.bias, d);
    }
    r.floats(b->ln.w, d); r.floats(b->ln.b, d);
    r.linear(b->down, E, d);
    if (!r.ok || r.pos != r.data.size()) return fail("bad .lmb size");
    b->kc.assign((size_t)b->layers * ctx * d, 0.0f);
    b->vc.assign((size_t)b->layers * ctx * d, 0.0f);
    b->h.resize(d); b->a.resize(d); b->q.resize(d); b->y.resize(d);
    b->f.resize(ff); b->att.resize(ctx);
    b->xq.resize(std::max({E, d, ff}));
    lm->body_ = std::move(b);
    lm->z_.assign(lm->E_, 0.0f);
    lm->zs_.assign((size_t)lm->ctx_ * lm->E_, 0.0f);
    return lm;
}

const char* neural_kernel_name() { return dot_name(); }

int32_t NeuralLm::id_of(const std::string& token) const {
    auto it = index_.find(token);
    return it == index_.end() ? 1 : it->second;
}

void NeuralLm::step(int32_t id) {
    Body& b = *body_;
    const int t = (int)window_.size();  // position of the new token
    const int d = b.d, E = b.E, hd = d / b.heads;
    if (id < 0 || id >= V_) id = 1;
    // embedding (int8 row) -> up + position
    std::vector<float> e(E);
    const int8_t* row = table_ + (size_t)id * E;
    for (int i = 0; i < E; ++i) e[i] = row[i] * scale_[id];
    b.up.apply(e.data(), b.h.data(), b.xq.data());
    const float* pe = b.pos.data() + (size_t)t * d;
    for (int i = 0; i < d; ++i) b.h[i] += pe[i];
    const float inv_sqrt = 1.0f / std::sqrt((float)hd);
    for (int l = 0; l < b.layers; ++l) {
        auto& bl = b.blocks[l];
        float* kc = b.kc.data() + (size_t)l * b.ctx * d;
        float* vc = b.vc.data() + (size_t)l * b.ctx * d;
        bl.ln1.apply(b.h.data(), b.a.data(), d);
        bl.q.apply(b.a.data(), b.q.data(), b.xq.data());
        bl.k.apply(b.a.data(), kc + (size_t)t * d, b.xq.data());
        bl.v.apply(b.a.data(), vc + (size_t)t * d, b.xq.data());
        for (int hh = 0; hh < b.heads; ++hh) {
            const float* qh = b.q.data() + hh * hd;
            float mx = -1e30f;
            for (int s = 0; s <= t; ++s) {
                const float* kh = kc + (size_t)s * d + hh * hd;
                float dot = 0;
                for (int i = 0; i < hd; ++i) dot += qh[i] * kh[i];
                b.att[s] = dot * inv_sqrt;
                mx = std::max(mx, b.att[s]);
            }
            float sum = 0;
            for (int s = 0; s <= t; ++s) sum += (b.att[s] = std::exp(b.att[s] - mx));
            float* yh = b.y.data() + hh * hd;
            std::fill(yh, yh + hd, 0.0f);
            for (int s = 0; s <= t; ++s) {
                const float wgt = b.att[s] / sum;
                const float* vh = vc + (size_t)s * d + hh * hd;
                for (int i = 0; i < hd; ++i) yh[i] += wgt * vh[i];
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
    b.ln.apply(b.h.data(), b.a.data(), d);
    b.down.apply(b.a.data(), z_.data(), b.xq.data());
    std::memcpy(zs_.data() + (size_t)t * E_, z_.data(), sizeof(float) * E_);
    window_.push_back(id);
    ++steps;
}

const float* NeuralLm::hidden(const std::vector<int32_t>& ids_in) {
    if (ids_in.empty()) return nullptr;
    const size_t n = ids_in.size();
    const int32_t* ids = ids_in.data();
    // Reuse the key/value cache: find where the cached window starts in ids, keep its longest common
    // part with ids (dropping cached positions after it, e.g. a word that was being typed) and run the
    // rest. Ids before that start are older context that no longer fits (at least kRestart ids remain).
    const size_t w = window_.size();
    size_t best_start = 0, best_keep = 0, best_cost = SIZE_MAX;
    for (size_t st = 0; w > 0 && st < n; ++st) {
        if (ids[st] != window_[0] || n - st > (size_t)ctx_) continue;
        // skipping older ids is only worth it when at least as much context remains as a restart keeps
        if (st > 0 && n - st < (size_t)kRestart) continue;
        size_t m = 0;
        while (m < w && st + m < n && ids[st + m] == window_[m]) ++m;
        const size_t cost = n - st - m;
        if (m > 0 && (cost < best_cost || (cost == best_cost && m > best_keep))) {
            best_cost = cost; best_start = st; best_keep = m;
        }
    }
    // a restart runs everything when it fits and starts the text, otherwise the last kRestart ids
    const size_t restart = (ids[0] == 2 && n <= (size_t)ctx_) ? n : std::min(n, (size_t)kRestart);
    if (best_cost != SIZE_MAX && best_cost < restart) {
        if (best_keep == w && best_cost == 0) { ++cached; return z_.data(); }
        window_.resize(best_keep);
        if (best_cost == 0) {
            std::memcpy(z_.data(), zs_.data() + (best_keep - 1) * E_, sizeof(float) * E_);
            return z_.data();
        }
        for (size_t i = best_start + best_keep; i < n; ++i) step(ids[i]);
        return z_.data();
    }
    ++restarts;
    window_.clear();
    for (size_t i = n - restart; i < n; ++i) step(ids[i]);
    return z_.data();
}

const float* NeuralLm::distribution(const std::vector<int32_t>& ids, float* lse) {
    if (ids.empty()) return nullptr;
    if (ids != cached_ids_ || cached_logits_.empty()) {
        const float* z = hidden(ids);
        if (!z) return nullptr;
        cached_logits_.resize(V_);
        logits(z, cached_logits_.data());
        float mx = *std::max_element(cached_logits_.begin(), cached_logits_.end());
        float sum = 0;
        for (float v : cached_logits_) sum += std::exp(v - mx);
        cached_lse_ = mx + (float)std::log(sum);
        cached_ids_ = ids;
    }
    *lse = cached_lse_;
    return cached_logits_.data();
}

void NeuralLm::logits(const float* z, float* out) const {
    std::vector<int8_t> zq(E_);
    const float sz = quantize(z, E_, zq.data());
    const int8_t* row = table_;
    for (int v = 0; v < V_; ++v, row += E_) out[v] = (float)kDot(row, zq.data(), E_) * scale_[v] * sz;
}

namespace {

// unigram log probability of a token from its frequency rank (vocabularies are sorted by count)
float zipf_logp(const NeuralLm& lm, const std::string& tok) {
    const int32_t id = lm.id_of(tok);
    if (id <= 2) return std::log(1e-7f);
    const float h = std::log((float)lm.vocab()) + 0.5772f;
    return -std::log((float)(id - 2)) - std::log(h);
}

bool is_language_neutral(const std::string& tok) {
    return tok.empty() || tok == "<num>" || tok == "<bos>" || !(std::isalpha((unsigned char)tok[0]) || (unsigned char)tok[0] >= 0x80);
}

}  // namespace

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
            lp[k] = std::log(std::max(prior, 1e-9f)) + zipf_logp(*langs[k].model, tok);
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
    struct Cand { float score; int lang; std::string surface; };
    std::unordered_map<std::string, Cand> best;
    for (size_t k = 0; k < langs.size(); ++k) {
        NeuralLm* lm = langs[k].model;
        if (!lm) continue;
        std::vector<int32_t> ids;
        ids.reserve(context.size() + 1);
        if (bos || context.empty()) ids.push_back(2);
        for (const auto& t : context) ids.push_back(lm->id_of(t));
        std::lock_guard<std::mutex> lock(lm->mutex());
        float lse = 0;
        const float* lgp = lm->distribution(ids, &lse);
        if (!lgp) continue;
        const float* lg = lgp;
        // candidates: the best words matching the prefix (one pass, min-heap of the top `keep`)
        const size_t keep = (size_t)max_results * 3;
        std::vector<std::pair<float, int32_t>> heap;
        heap.reserve(keep + 1);
        auto worse = [](const std::pair<float, int32_t>& a, const std::pair<float, int32_t>& b) { return a.first > b.first; };
        for (int32_t id = 3; id < lm->vocab(); ++id) {
            if (!lm->is_word(id)) continue;
            if (heap.size() == keep && lg[id] <= heap.front().first) continue;
            if (!prefix_lower.empty()) {
                const std::string& t = lm->token(id);
                if (t == prefix_lower) continue;  // the typed word itself is not a completion
                if (!starts_with(t, prefix_lower) && !(fold && starts_with(lm->key_path(id), prefix_kp))) continue;
            }
            heap.emplace_back(lg[id], id);
            std::push_heap(heap.begin(), heap.end(), worse);
            if (heap.size() > keep) {
                std::pop_heap(heap.begin(), heap.end(), worse);
                heap.pop_back();
            }
        }
        std::sort(heap.begin(), heap.end(), worse);
        std::vector<int32_t> cand;
        for (auto& e : heap) cand.push_back(e.second);
        for (int32_t id : cand) {
            const std::string& w = lm->token(id);
            std::string surface = w;
            if (langs[k].dict) {
                const int32_t did = langs[k].dict->lower_id(w);
                if (did < 0) continue;
                surface = langs[k].dict->surface_of((uint32_t)did);
            }
            const float sc = lg[id] - lse + lpost[k];
            auto it = best.find(w);
            if (it == best.end()) {
                best.emplace(w, Cand{sc, (int)k, surface});
            } else {
                // a word of both languages: P = sum over languages
                const float m = std::max(it->second.score, sc);
                const float merged = m + std::log(std::exp(it->second.score - m) + std::exp(sc - m));
                if (sc > it->second.score) { it->second.lang = (int)k; it->second.surface = surface; }
                it->second.score = merged;
            }
        }
    }
    out.reserve(best.size());
    for (auto& kv : best) out.push_back({kv.second.surface, kv.second.score, kv.second.lang});
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
    if (langs.empty() || words_lower.empty()) return out;
    const auto lpost = neural_language_posterior(langs, context, switch_p);
    for (size_t k = 0; k < langs.size(); ++k) {
        NeuralLm* lm = langs[k].model;
        if (!lm) continue;
        std::vector<int32_t> ids;
        ids.reserve(context.size() + 1);
        if (bos || context.empty()) ids.push_back(2);
        for (const auto& t : context) ids.push_back(lm->id_of(t));
        std::lock_guard<std::mutex> lock(lm->mutex());
        float lse = 0;
        const float* lg = lm->distribution(ids, &lse);
        if (!lg) continue;
        add(unk, lg[1] - lse + lpost[k]);
        for (size_t i = 0; i < words_lower.size(); ++i) {
            const int32_t id = lm->id_of(words_lower[i]);
            if (id <= 2 || !lm->is_word(id)) continue;
            add(out[i], lg[id] - lse + lpost[k]);
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
            // unigram log probability in the candidate's dictionary; a word only the user has written:
            // the best of the languages' dictionaries, or a rare word's
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
