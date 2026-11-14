// SPDX-License-Identifier: GPL-3.0-only
#include "kp_dictionary.hpp"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <unordered_map>

#include "swipe_decoder/layout.hpp"

namespace droneswipe {

using swipe_decoder::utf8_to_codepoints;

namespace {

// Base letters of U+00C0..U+017F (Unicode NFD, lowercased); '_' = no a-z base.
const char kLatinBase[] =
    "aaaaaa_ceeeeiiii_nooooo__uuuuy__aaaaaa_ceeeeiiii_nooooo__uuuuy_yaaaaaaccccccccdd__eeeeeeeeee"
    "gggggggghh__iiiiiiiii___jjkk_llllll____nnnnnn___oooooo__rrrrrrsssssssstttt__uuuuuuuuuuuuwwyyy"
    "zzzzzz_";

int lower_cp(int cp) {
    // lowercase for ASCII, Latin-1 and Latin Extended-A (enough for the languages we ship);
    // tools/dicts/pack_swipe_dict.py mirrors this
    if (cp >= 'A' && cp <= 'Z') return cp + 32;
    if (cp >= 0xC0 && cp <= 0xDE && cp != 0xD7) return cp + 32;
    if (cp >= 0x100 && cp <= 0x17F) {
        // mostly even = upper, odd = lower; 0x139-0x148 and 0x179-0x17E are shifted by one
        bool shifted = (cp >= 0x139 && cp <= 0x148) || (cp >= 0x179 && cp <= 0x17E);
        if (shifted ? (cp % 2 == 1) : (cp % 2 == 0)) return cp + 1;
    }
    return cp;
}

int upper_cp(int cp) {
    if (cp >= 'a' && cp <= 'z') return cp - 32;
    if (cp >= 0xE0 && cp <= 0xFE && cp != 0xF7) return cp - 32;
    if (cp >= 0x100 && cp <= 0x17F) {
        bool shifted = (cp >= 0x139 && cp <= 0x148) || (cp >= 0x179 && cp <= 0x17E);
        if (shifted ? (cp % 2 == 0) : (cp % 2 == 1)) return cp - 1;
    }
    return cp;
}

void append_utf8(std::string& out, int cp) {
    if (cp < 0x80) out += (char)cp;
    else if (cp < 0x800) { out += (char)(0xC0 | (cp >> 6)); out += (char)(0x80 | (cp & 0x3F)); }
    else if (cp < 0x10000) { out += (char)(0xE0 | (cp >> 12)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
    else { out += (char)(0xF0 | (cp >> 18)); out += (char)(0x80 | ((cp >> 12) & 0x3F)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
}

int builtin_base(int cp) {
    if (cp >= 'a' && cp <= 'z') return cp;
    if (cp >= 0xC0 && cp <= 0x17F) {
        char b = kLatinBase[cp - 0xC0];
        return b == '_' ? 0 : b;
    }
    return 0;
}

std::string derive_keypath(const std::string& lower, const std::unordered_map<int, char>& map) {
    std::string kp;
    for (int cp : utf8_to_codepoints(lower)) {
        if (cp >= 'a' && cp <= 'z') { kp += (char)cp; continue; }
        auto it = map.find(cp);
        if (it != map.end()) kp += it->second;
    }
    return kp;
}

std::string capitalize(const std::string& lower) {
    auto cps = utf8_to_codepoints(lower);
    std::string out;
    for (size_t i = 0; i < cps.size(); ++i) append_utf8(out, i == 0 ? upper_cp(cps[i]) : cps[i]);
    return out;
}

// Sequential reader over the mmapped file. All reads are bounds checked; a truncated or
// corrupt file sets ok = false and yields zeros.
struct Reader {
    const uint8_t* p;
    const uint8_t* end;
    bool ok = true;
    bool need(size_t n) {
        if ((size_t)(end - p) < n) { ok = false; p = end; return false; }
        return true;
    }
    uint32_t u32() { if (!need(4)) return 0; uint32_t v; memcpy(&v, p, 4); p += 4; return v; }
    uint16_t u16() { if (!need(2)) return 0; uint16_t v; memcpy(&v, p, 2); p += 2; return v; }
    uint8_t u8() { if (!need(1)) return 0; return *p++; }
    double f64() { if (!need(8)) return 0; double v; memcpy(&v, p, 8); p += 8; return v; }
    uint64_t varint() {
        uint64_t v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (!need(1)) return 0;
            uint8_t b = *p++;
            v |= (uint64_t)(b & 0x7F) << shift;
            if (!(b & 0x80)) return v;
        }
        ok = false;
        return 0;
    }
    std::string bytes(size_t n) {
        if (!need(n)) return {};
        std::string s((const char*)p, n);
        p += n;
        return s;
    }
    std::string front(const std::string& prev) {
        size_t shared = (size_t)varint();
        size_t n = (size_t)varint();
        if (shared > prev.size()) { ok = false; return {}; }
        return prev.substr(0, shared) + bytes(n);
    }
};

struct Mix {
    size_t operator()(uint64_t k) const { k ^= k >> 33; k *= 0xff51afd7ed558ccdULL; k ^= k >> 33; return (size_t)k; }
};
using EdgeMap = std::unordered_map<uint64_t, uint32_t, Mix>;
using KpIndex = std::unordered_map<std::string, int32_t>;

}  // namespace

std::string to_lower_utf8(const std::string& s) {
    std::string out;
    for (int cp : utf8_to_codepoints(s)) append_utf8(out, lower_cp(cp));
    return out;
}

std::string key_path_of(const std::string& s) {
    std::string kp;
    for (int cp : utf8_to_codepoints(s)) {
        int b = builtin_base(lower_cp(cp));
        if (b) kp += (char)b;
    }
    return kp;
}

void KpDictionary::begin_build() {
    node_cp_.assign(1, -1);
    node_depth_.assign(1, 0);
    node_term_.assign(1, -1);
    node_freq_.assign(1, 0.0f);
    kps_.clear();
    variants_.clear();
    kp_variants_.clear();
}

void KpDictionary::add_variant(const std::string& surface, const std::string& keypath, float freq, uint32_t lower,
                               std::vector<std::vector<uint32_t>>& children, void* edges, void* kp_index) {
    auto& edge = *(EdgeMap*)edges;
    auto& index = *(KpIndex*)kp_index;
    Variant v;
    v.surface = surface;
    v.freq = freq;
    v.lower = lower;
    auto scps = utf8_to_codepoints(surface);
    v.capitalized = scps.size() > 1 && lower_cp(scps[0]) != scps[0];
    uint32_t vid = (uint32_t)variants_.size();
    variants_.push_back(std::move(v));
    kp_variants_[keypath].push_back(vid);

    auto it = index.find(keypath);
    int32_t term;
    if (it == index.end()) {
        term = (int32_t)kps_.size();
        kps_.push_back(keypath);
        index.emplace(keypath, term);
    } else term = it->second;

    uint32_t n = 0;
    for (size_t i = 0; i < keypath.size(); ++i) {
        int c = (unsigned char)keypath[i];
        uint64_t e = ((uint64_t)n << 21) | (uint32_t)c;
        auto ei = edge.find(e);
        if (ei == edge.end()) {
            uint32_t id = (uint32_t)node_cp_.size();
            node_cp_.push_back(c);
            node_depth_.push_back((uint16_t)(i + 1));
            node_term_.push_back(-1);
            node_freq_.push_back(0.0f);
            children.emplace_back();
            children[n].push_back(id);
            edge.emplace(e, id);
            n = id;
        } else n = ei->second;
    }
    node_term_[n] = term;
    if (freq > node_freq_[n]) node_freq_[n] = freq;
}

void KpDictionary::finish_build(std::vector<std::vector<uint32_t>>& children) {
    all_child_start_.assign(children.size() + 1, 0);
    for (size_t i = 0; i < children.size(); ++i) all_child_start_[i + 1] = all_child_start_[i] + (uint32_t)children[i].size();
    all_child_list_.clear();
    all_child_list_.reserve(all_child_start_.back());
    for (auto& c : children) all_child_list_.insert(all_child_list_.end(), c.begin(), c.end());
    node_key_.assign(node_cp_.size(), -1);
    child_start_ = all_child_start_;
    child_list_ = all_child_list_;
}

bool KpDictionary::load_kpd(const std::string& path) {
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size < 32) { close(fd); return false; }
    size_t size = (size_t)st.st_size;
    void* map = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return false;
    const uint8_t* base = (const uint8_t*)map;
    Reader r{base, base + size};

    bool ok = false;
    do {
        // header and section directory (FORMAT.md); unknown sections are skipped, so optional
        // sections (e.g. trigrams) can be added without breaking older readers
        if (r.bytes(4) != "KPD1" || r.u32() != 2) break;
        uint32_t n_lower = r.u32(), n_variants = r.u32(), n_prevs = r.u32(), n_big = r.u32();
        total_ = std::max(1.0, r.f64());
        uint32_t n_sections = r.u32();
        std::unordered_map<std::string, Reader> sec;
        for (uint32_t i = 0; i < n_sections && r.ok; ++i) {
            std::string tag = r.bytes(4);
            uint32_t off = r.u32(), len = r.u32();
            if ((uint64_t)off + len > size) { r.ok = false; break; }
            sec.emplace(tag, Reader{base + off, base + off + len});
        }
        if (!r.ok) break;
        bool complete = true;
        for (const char* t : {"CMAP", "LOWR", "LOGC", "WREC", "FREQ"}) complete = complete && sec.count(t);
        if (!complete) break;

        Reader& cm = sec.at("CMAP");
        uint32_t n_map = cm.u32();
        std::unordered_map<int, char> cmap;
        for (uint32_t i = 0; i < n_map && cm.ok; ++i) {
            int cp = (int)cm.u32();
            cmap[cp] = (char)cm.u8();
        }

        Reader& lw = sec.at("LOWR");
        lowers_.clear();
        lowers_.reserve(n_lower);
        lower_index_.clear();
        lower_index_.reserve(n_lower);
        std::string prev;
        for (uint32_t i = 0; i < n_lower && lw.ok; ++i) {
            prev = lw.front(prev);
            lower_index_.emplace(prev, i);
            lowers_.push_back(prev);
        }
        Reader& lc = sec.at("LOGC");
        lower_logc_.resize(n_lower);
        for (uint32_t i = 0; i < n_lower; ++i) lower_logc_[i] = lc.u16() / 2048.0f;
        if (!cm.ok || !lw.ok || !lc.ok) break;

        struct Pending { std::string surface, keypath; uint32_t lower; };
        std::vector<Pending> pending;
        pending.reserve(n_variants);
        Reader& wr = sec.at("WREC");
        for (uint32_t i = 0; i < n_lower && wr.ok; ++i) {
            uint64_t spec = wr.varint();
            const std::string& low = lowers_[i];
            std::string kp = (spec & 4) ? wr.bytes((size_t)wr.varint()) : derive_keypath(low, cmap);
            if (spec & 1) pending.push_back({low, kp, i});
            if (spec & 2) pending.push_back({capitalize(low), kp, i});
            for (uint64_t k = 0, n = spec >> 3; k < n && wr.ok; ++k) pending.push_back({wr.bytes((size_t)wr.varint()), kp, i});
        }
        Reader& fq = sec.at("FREQ");
        if (!wr.ok || pending.size() != n_variants || !fq.need(n_variants)) break;

        begin_build();
        std::vector<std::vector<uint32_t>> children(1);
        EdgeMap edge;
        edge.reserve(n_variants * 3);
        KpIndex kp_index;
        variants_.reserve(n_variants);
        for (auto& pv : pending) {
            float f = (float)fq.u8();
            if (!pv.keypath.empty()) add_variant(pv.surface, pv.keypath, f, pv.lower, children, &edge, &kp_index);
        }
        finish_build(children);

        big_start_.assign(n_lower + 1, 0);
        big_word_.clear();
        big_word_.reserve(n_big);
        big_logc_.clear();
        big_logc_.reserve(n_big);
        std::vector<std::pair<uint32_t, uint32_t>> ranges;  // prev id -> [start, end)
        auto bi = sec.find("BIGR");  // optional
        Reader empty{base, base};
        Reader& bg = bi == sec.end() ? empty : bi->second;
        uint32_t p = 0;
        for (uint32_t i = 0; bi != sec.end() && i < n_prevs && bg.ok; ++i) {
            p += (uint32_t)bg.varint();
            uint32_t n = (uint32_t)bg.varint();
            uint32_t start = (uint32_t)big_word_.size();
            uint32_t w = 0;
            for (uint32_t k = 0; k < n && bg.ok; ++k) {
                w += (uint32_t)bg.varint();
                big_word_.push_back(w);
                big_logc_.push_back(bg.u8());
            }
            if (p < n_lower) ranges.emplace_back(p, start);
        }
        if (!bg.ok) break;

        // optional trigrams (FORMAT.md): contexts sorted by (prev2, prev)
        tri_ctx_.clear();
        tri_ctx_logc_.clear();
        tri_start_.assign(1, 0);
        tri_word_.clear();
        tri_logc_.clear();
        auto ti = sec.find("TRIG");
        if (ti != sec.end()) {
            Reader& tg = ti->second;
            uint32_t n_ctx = tg.u32(), n_tri = tg.u32();
            tri_ctx_.reserve(n_ctx);
            tri_ctx_logc_.reserve(n_ctx);
            tri_start_.reserve(n_ctx + 1);
            tri_word_.reserve(n_tri);
            tri_logc_.reserve(n_tri);
            uint32_t w1 = 0, w2 = 0;
            for (uint32_t i = 0; i < n_ctx && tg.ok; ++i) {
                uint32_t d1 = (uint32_t)tg.varint();
                w1 += d1;
                w2 = d1 ? (uint32_t)tg.varint() : w2 + (uint32_t)tg.varint();
                tri_ctx_logc_.push_back(tg.u8());
                uint32_t n = (uint32_t)tg.varint();
                uint32_t w = 0;
                for (uint32_t k = 0; k < n && tg.ok; ++k) {
                    w += (uint32_t)tg.varint();
                    tri_word_.push_back(w);
                    tri_logc_.push_back(tg.u8());
                }
                tri_ctx_.push_back(((uint64_t)w1 << 32) | w2);
                tri_start_.push_back((uint32_t)tri_word_.size());
            }
            if (!tg.ok || w1 >= n_lower) {  // a corrupt section drops only the trigrams
                tri_ctx_.clear();
                tri_ctx_logc_.clear();
                tri_start_.assign(1, 0);
                tri_word_.clear();
                tri_logc_.clear();
            }
        }

        // CSR: big_start_[id] .. big_start_[id + 1]; prevs come sorted, so fill gaps backwards
        uint32_t next = (uint32_t)big_word_.size();
        size_t ri = ranges.size();
        for (int64_t id = (int64_t)n_lower; id >= 0; --id) {
            if (id < (int64_t)n_lower && ri > 0 && ranges[ri - 1].first == (uint32_t)id) next = ranges[--ri].second;
            big_start_[(size_t)id] = next;
        }
        big_start_[n_lower] = (uint32_t)big_word_.size();

        best_variant_.assign(n_lower, UINT32_MAX);
        for (uint32_t v = 0; v < (uint32_t)variants_.size(); ++v) {
            uint32_t& b = best_variant_[variants_[v].lower];
            if (b == UINT32_MAX || variants_[v].freq > variants_[b].freq) b = v;
        }
        top_words_.resize(n_lower);
        for (uint32_t i = 0; i < n_lower; ++i) top_words_[i] = i;
        const size_t n_top = std::min<size_t>(n_lower, 300);
        std::partial_sort(top_words_.begin(), top_words_.begin() + n_top, top_words_.end(),
                          [&](uint32_t a, uint32_t b) { return lower_logc_[a] > lower_logc_[b]; });
        top_words_.resize(n_top);
        ok = !variants_.empty();
    } while (false);
    munmap(map, size);
    return ok;
}

void KpDictionary::load_user(const std::vector<std::pair<std::string, int>>& words) {
    begin_build();
    lowers_.clear();
    lower_index_.clear();
    lower_logc_.clear();
    user_weight_.clear();
    big_start_.assign(1, 0);
    big_word_.clear();
    big_logc_.clear();
    tri_ctx_.clear();
    tri_ctx_logc_.clear();
    tri_start_.assign(1, 0);
    tri_word_.clear();
    tri_logc_.clear();
    best_variant_.clear();
    top_words_.clear();
    total_ = 1.0;
    std::vector<std::vector<uint32_t>> children(1);
    EdgeMap edge;
    KpIndex kp_index;
    std::unordered_map<std::string, bool> seen;
    for (const auto& [surface, weight] : words) {
        if (surface.empty() || !seen.emplace(surface, true).second) continue;
        std::string kp = key_path_of(surface);
        if (kp.size() < 2) continue;
        std::string low = to_lower_utf8(surface);
        uint8_t w = (uint8_t)std::clamp(weight, 0, 255);
        auto it = lower_index_.find(low);
        uint32_t id;
        if (it == lower_index_.end()) {
            id = (uint32_t)lowers_.size();
            lower_index_.emplace(low, id);
            lowers_.push_back(low);
            lower_logc_.push_back(0.0f);
            user_weight_.push_back(w);
        } else {
            id = it->second;
            user_weight_[id] = std::max(user_weight_[id], w);
        }
        add_variant(surface, kp, (float)w, id, children, &edge, &kp_index);
    }
    finish_build(children);
    big_start_.assign(lowers_.size() + 1, 0);
}

void KpDictionary::set_layout(const std::vector<int>& layout_codepoints) {
    num_chars_ = (int)layout_codepoints.size();
    std::unordered_map<int, int> idx;
    for (int i = 0; i < (int)layout_codepoints.size(); ++i) idx.emplace(layout_codepoints[i], i);
    for (size_t n = 1; n < node_cp_.size(); ++n) {
        auto it = idx.find(node_cp_[n]);
        node_key_[n] = it == idx.end() ? -1 : it->second;
    }
    // keep only children that can be reached on this layout (and whose parents can)
    child_start_.assign(node_cp_.size() + 1, 0);
    child_list_.clear();
    for (size_t n = 0; n < node_cp_.size(); ++n) {
        child_start_[n] = (uint32_t)child_list_.size();
        if (n == 0 || node_key_[n] >= 0) {
            for (uint32_t i = all_child_start_[n]; i < all_child_start_[n + 1]; ++i) {
                uint32_t c = all_child_list_[i];
                if (node_key_[c] >= 0) child_list_.push_back(c);
            }
        }
    }
    child_start_[node_cp_.size()] = (uint32_t)child_list_.size();
}

const std::vector<uint32_t>* KpDictionary::variants_of(const std::string& keypath) const {
    auto it = kp_variants_.find(keypath);
    return it == kp_variants_.end() ? nullptr : &it->second;
}

int32_t KpDictionary::lower_id(const std::string& word_lower) const {
    auto it = lower_index_.find(word_lower);
    return it == lower_index_.end() ? -1 : (int32_t)it->second;
}

float KpDictionary::bigram_count(int32_t prev, uint32_t word) const {
    if ((size_t)prev + 1 >= big_start_.size()) return 0.0f;
    auto b = big_word_.begin() + big_start_[prev], e = big_word_.begin() + big_start_[prev + 1];
    auto it = std::lower_bound(b, e, word);
    return it != e && *it == word ? (float)std::exp(big_logc_[it - big_word_.begin()] / 16.0) : 0.0f;
}

// P(w | prev), Dirichlet-smoothed towards the unigram pu (K = 50)
double KpDictionary::p_bigram(int32_t prev, uint32_t word, double pu) const {
    const double K = 50.0;
    return ((double)bigram_count(prev, word) + K * pu) / (std::exp((double)lower_logc_[prev]) + K);
}

float KpDictionary::pmi(int32_t prev, uint32_t word) const {
    if (prev < 0 || (size_t)prev >= lower_logc_.size() || word >= lower_logc_.size()) return 0.0f;
    const double pu = (std::exp((double)lower_logc_[word]) + 0.5) / total_;
    return (float)(std::log(p_bigram(prev, word, pu)) - std::log(pu));
}

float KpDictionary::logp_ctx(int32_t prev2, int32_t prev, uint32_t word) const {
    if (word >= lower_logc_.size()) return -30.0f;
    const double pu = (std::exp((double)lower_logc_[word]) + 0.5) / total_;
    if (prev < 0 || (size_t)prev >= lower_logc_.size()) return (float)std::log(pu);
    double p = p_bigram(prev, word, pu);
    if (prev2 >= 0 && !tri_ctx_.empty()) {
        const uint64_t key = ((uint64_t)(uint32_t)prev2 << 32) | (uint32_t)prev;
        auto it = std::lower_bound(tri_ctx_.begin(), tri_ctx_.end(), key);
        if (it != tri_ctx_.end() && *it == key) {
            const size_t c = (size_t)(it - tri_ctx_.begin());
            auto b = tri_word_.begin() + tri_start_[c], e = tri_word_.begin() + tri_start_[c + 1];
            auto wt = std::lower_bound(b, e, word);
            const double c3 = wt != e && *wt == word ? std::exp(tri_logc_[wt - tri_word_.begin()] / 16.0) : 0.0;
            const double cctx = std::exp(tri_ctx_logc_[c] / 16.0);
            const double K3 = std::max(0.01f, params.k3);
            p = (c3 + K3 * p) / (cctx + K3);
        }
    }
    return (float)std::log(p);
}

float KpDictionary::pmi3(int32_t prev2, int32_t prev, uint32_t word) const {
    if (prev < 0 || (size_t)prev >= lower_logc_.size() || word >= lower_logc_.size()) return 0.0f;
    return logp_ctx(prev2, prev, word) - logp_id(word);
}

float KpDictionary::logp_id(uint32_t word) const {
    double c = word < lower_logc_.size() ? std::exp((double)lower_logc_[word]) : 0.0;
    return (float)std::log((c + 0.5) / total_);
}

float KpDictionary::logp(const std::string& word_lower) const {
    int32_t id = lower_id(word_lower);
    double c = id < 0 ? 0.0 : std::exp((double)lower_logc_[id]);
    return (float)std::log((c + 0.5) / total_);
}

const std::string& KpDictionary::surface_of(uint32_t lower) const {
    if (lower < best_variant_.size() && best_variant_[lower] != UINT32_MAX) return variants_[best_variant_[lower]].surface;
    return lowers_[lower];
}

std::vector<std::pair<uint32_t, float>> KpDictionary::predict(int32_t prev2, int32_t prev, const std::string& prefix_lower,
                                                              int max_results) const {
    std::vector<uint32_t> cand;
    auto has_prefix = [&](uint32_t w) {
        const std::string& s = lowers_[w];
        return s.size() >= prefix_lower.size() && s.compare(0, prefix_lower.size(), prefix_lower) == 0;
    };
    // continuations of the context
    if (prev >= 0 && (size_t)prev + 1 < big_start_.size())
        for (uint32_t i = big_start_[prev]; i < big_start_[prev + 1]; ++i) cand.push_back(big_word_[i]);
    if (prev2 >= 0 && prev >= 0 && !tri_ctx_.empty()) {
        const uint64_t key = ((uint64_t)(uint32_t)prev2 << 32) | (uint32_t)prev;
        auto it = std::lower_bound(tri_ctx_.begin(), tri_ctx_.end(), key);
        if (it != tri_ctx_.end() && *it == key) {
            const size_t c = (size_t)(it - tri_ctx_.begin());
            for (uint32_t i = tri_start_[c]; i < tri_start_[c + 1]; ++i) cand.push_back(tri_word_[i]);
        }
    }
    if (!prefix_lower.empty()) {
        cand.erase(std::remove_if(cand.begin(), cand.end(), [&](uint32_t w) { return !has_prefix(w); }), cand.end());
        // words with the prefix: lowers_ is sorted by UTF-8 bytes (LOWR), so they form one range
        auto lo = std::lower_bound(lowers_.begin(), lowers_.end(), prefix_lower);
        std::string hi_key = prefix_lower;
        hi_key.push_back('\xff');
        auto hi = std::upper_bound(lo, lowers_.end(), hi_key);
        const size_t n = (size_t)(hi - lo);
        const uint32_t first = (uint32_t)(lo - lowers_.begin());
        if (n <= 4000) {
            for (size_t i = 0; i < n; ++i) cand.push_back(first + (uint32_t)i);
        } else {
            // too many: the most frequent ones (context continuations are already in)
            std::vector<uint32_t> range(n);
            for (size_t i = 0; i < n; ++i) range[i] = first + (uint32_t)i;
            const size_t k = 2000;
            std::partial_sort(range.begin(), range.begin() + k, range.end(),
                              [&](uint32_t a, uint32_t b) { return lower_logc_[a] > lower_logc_[b]; });
            cand.insert(cand.end(), range.begin(), range.begin() + k);
        }
    } else {
        cand.insert(cand.end(), top_words_.begin(), top_words_.end());
    }
    std::sort(cand.begin(), cand.end());
    cand.erase(std::unique(cand.begin(), cand.end()), cand.end());
    std::vector<std::pair<uint32_t, float>> out;
    out.reserve(cand.size());
    for (uint32_t w : cand) {
        if (w >= lowers_.size() || lowers_[w] == prefix_lower) continue;  // the typed word itself is not a completion
        out.emplace_back(w, logp_ctx(prev2, prev, w));
    }
    const size_t k = std::min<size_t>(out.size(), (size_t)std::max(0, max_results));
    std::partial_sort(out.begin(), out.begin() + k, out.end(),
                      [](const auto& a, const auto& b) { return a.second != b.second ? a.second > b.second : a.first < b.first; });
    out.resize(k);
    return out;
}

int KpDictionary::user_weight(const std::string& word_lower) const {
    int32_t id = lower_id(word_lower);
    return id < 0 || (size_t)id >= user_weight_.size() ? -1 : user_weight_[id];
}

static int T_num_chars(void* s) { return ((KpDictionary*)s)->num_chars_; }
static TrieId T_root(void*) { return 0; }
static int T_char(void* s, TrieId id) { return ((KpDictionary*)s)->node_key_[id]; }
static uint32_t T_cc(void* s, TrieId id) {
    auto* d = (KpDictionary*)s;
    return d->child_start_[id + 1] - d->child_start_[id];
}
static TrieId T_child(void* s, TrieId id, uint32_t i) {
    auto* d = (KpDictionary*)s;
    return d->child_list_[d->child_start_[id] + i];
}
static bool T_is_word(void* s, TrieId id) { return ((KpDictionary*)s)->node_term_[id] >= 0; }
static float T_freq(void* s, TrieId id) { return ((KpDictionary*)s)->node_freq_[id]; }
static uint16_t T_depth(void* s, TrieId id) { return ((KpDictionary*)s)->node_depth_[id]; }
static const char* T_word(void* s, TrieId id) {
    auto* d = (KpDictionary*)s;
    int32_t t = d->node_term_[id];
    return t >= 0 ? d->kps_[t].c_str() : "";
}
static void T_end(void*) {}

static const ITrieVTable kVTable = {T_num_chars, T_root, T_char, T_cc, T_child, T_is_word, T_freq, T_depth, T_word, T_end};

ITrie KpDictionary::itrie() { return ITrie{this, &kVTable}; }

}  // namespace droneswipe
