// SPDX-License-Identifier: GPL-3.0-only
#include "kp_dictionary.hpp"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <numeric>
#include <queue>
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

bool is_capitalized_surface(const std::string& surface) {
    auto scps = utf8_to_codepoints(surface);
    // "I'll", "I'm": the pronoun is always capitalized, it is not a proper noun ("H's" still is)
    const bool pronoun_i = scps.size() > 1 && scps[0] == 'I' && (scps[1] == '\'' || scps[1] == 0x2019);
    return scps.size() > 1 && lower_cp(scps[0]) != scps[0] && !pronoun_i;
}

// Precomputed LUTs eliminating std::exp() on the hot path:
//  - exp_u8[i] = exp(i / 16.0) for bigram/trigram counts
//  - exp_q_hi[hi] * exp_q_lo[lo] = exp((hi * 256 + lo) / 2048.0) for uint16 lower_logc_q
struct ExpLuts {
    double exp_u8[256];
    double exp_q_hi[256];
    double exp_q_lo[256];
    ExpLuts() {
        for (int i = 0; i < 256; ++i) {
            exp_u8[i] = std::exp(i / 16.0);
            exp_q_hi[i] = std::exp((i * 256.0) / 2048.0);
            exp_q_lo[i] = std::exp(i / 2048.0);
        }
    }
};

const ExpLuts& exp_luts() {
    static const ExpLuts luts;
    return luts;
}

// Sequential reader over an mmapped slice. All reads are bounds checked.
struct Reader {
    const uint8_t* p;
    const uint8_t* end;
    bool ok = true;
    bool need(size_t n) {
        if ((size_t)(end - p) < n) { ok = false; p = end; return false; }
        return true;
    }
    uint32_t u32() { if (!need(4)) return 0; uint32_t v; std::memcpy(&v, p, 4); p += 4; return v; }
    uint16_t u16() { if (!need(2)) return 0; uint16_t v; std::memcpy(&v, p, 2); p += 2; return v; }
    uint8_t u8() { if (!need(1)) return 0; return *p++; }
    double f64() { if (!need(8)) return 0; double v; std::memcpy(&v, p, 8); p += 8; return v; }
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

constexpr uint32_t kFlagBfsTrie = 1u << 0;
constexpr uint32_t kFlagTrieHasNonAz = 1u << 1;
constexpr uint32_t kFlagHasFooter = 1u << 2;
constexpr size_t kFooterSize = 24;

// Fast streaming 64-bit integrity hash (4-lane SplitMix/WY-style block mixer, unaligned-safe).
// Verifies a 30 MB .kpd2 file in ~1-2 ms on ARM64 and catches truncated, bit-flipped, or zeroed pages.
struct FastHash64 {
    uint64_t h0 = 0x9E3779B97F4A7C15ULL;
    uint64_t h1 = 0xBF58476D1CE4E5B9ULL;
    uint64_t h2 = 0x94D049BB133111EBULL;
    uint64_t h3 = 0xD6E8FEB86659FD93ULL;
    uint64_t total_len = 0;
    uint8_t buf[32] = {};
    size_t buf_len = 0;

    static inline uint64_t rotl(uint64_t x, int r) { return (x << r) | (x >> (64 - r)); }

    void mix_block(const uint8_t* p) {
        uint64_t w0, w1, w2, w3;
        std::memcpy(&w0, p + 0, 8);
        std::memcpy(&w1, p + 8, 8);
        std::memcpy(&w2, p + 16, 8);
        std::memcpy(&w3, p + 24, 8);
        h0 = rotl(h0 ^ (w0 * 0x9E3779B97F4A7C15ULL), 31) * 0xBF58476D1CE4E5B9ULL;
        h1 = rotl(h1 ^ (w1 * 0xBF58476D1CE4E5B9ULL), 27) * 0x94D049BB133111EBULL;
        h2 = rotl(h2 ^ (w2 * 0x94D049BB133111EBULL), 33) * 0xD6E8FEB86659FD93ULL;
        h3 = rotl(h3 ^ (w3 * 0xD6E8FEB86659FD93ULL), 29) * 0x9E3779B97F4A7C15ULL;
    }

    void update(const void* data, size_t len) {
        const auto* p = (const uint8_t*)data;
        total_len += len;
        if (buf_len > 0) {
            const size_t take = std::min(32 - buf_len, len);
            std::memcpy(buf + buf_len, p, take);
            buf_len += take;
            p += take;
            len -= take;
            if (buf_len == 32) {
                mix_block(buf);
                buf_len = 0;
            }
        }
        while (len >= 32) {
            mix_block(p);
            p += 32;
            len -= 32;
        }
        if (len > 0) {
            std::memcpy(buf, p, len);
            buf_len = len;
        }
    }

    uint64_t finish() const {
        uint64_t x = h0 ^ rotl(h1, 13) ^ rotl(h2, 29) ^ rotl(h3, 47) ^ total_len;
        for (size_t i = 0; i < buf_len; ++i) {
            x ^= (uint64_t)buf[i] + 0x9E3779B97F4A7C15ULL + (x << 6) + (x >> 2);
            x *= 0xBF58476D1CE4E5B9ULL;
        }
        x ^= x >> 30;
        x *= 0xBF58476D1CE4E5B9ULL;
        x ^= x >> 27;
        x *= 0x94D049BB133111EBULL;
        x ^= x >> 31;
        return x;
    }

    static uint64_t hash(const uint8_t* data, size_t len) {
        FastHash64 h;
        h.update(data, len);
        return h.finish();
    }
};

// Alignment-safe u64 reader for TGCT (handles 4-byte-aligned Android APK zip entries without UB;
// compiles to a single ldr instruction on ARM64/x86_64).
inline uint64_t read_u64_at(const uint8_t* base, size_t idx) {
    uint64_t v = 0;
    std::memcpy(&v, base + idx * 8, 8);
    return v;
}

inline int64_t find_tri_ctx(const uint8_t* base, uint32_t n_ctx, uint64_t key) {
    if (!base || n_ctx == 0) return -1;
    uint32_t lo = 0, hi = n_ctx;
    while (lo < hi) {
        const uint32_t mid = lo + ((hi - lo) >> 1);
        if (read_u64_at(base, mid) < key) lo = mid + 1;
        else hi = mid;
    }
    return (lo < n_ctx && read_u64_at(base, lo) == key) ? (int64_t)lo : -1;
}

}  // namespace

std::string to_lower_utf8(const std::string& s) {
    std::string out;
    for (int cp : utf8_to_codepoints(s)) append_utf8(out, lower_cp(cp));
    return out;
}

std::string key_path_of(const std::string& s) {
    std::string kp;
    for (int cp : utf8_to_codepoints(s)) {
        if (cp == 0xDF || cp == 0x1E9E) { kp += "ss"; continue; }  // German ß is swiped as "ss"
        int b = builtin_base(lower_cp(cp));
        if (b) kp += (char)b;
    }
    return kp;
}

KpDictionary::~KpDictionary() {
    clear_all();
}

void KpDictionary::clear_all() {
    if (mmap_base_) {
        munmap(mmap_base_, mmap_len_);
        mmap_base_ = nullptr;
        mmap_len_ = 0;
    }
    format_version_ = 0;
    bfs_trie_ = false;
    trie_has_non_az_ = false;
    num_chars_ = 0;
    n_nodes_ = 0;
    n_lower_ = 0;
    n_variants_ = 0;
    n_kps_ = 0;
    n_big_ = 0;
    n_tri_ctx_ = 0;
    n_tri_ = 0;
    n_top_words_ = 0;
    total_ = 1.0;

    node_char_ptr_ = nullptr;
    node_depth_ptr_ = nullptr;
    node_term_ptr_ = nullptr;
    node_freq_ptr_ = nullptr;
    child_start_ptr_ = nullptr;
    child_list_ptr_ = nullptr;
    all_child_start_ptr_ = nullptr;
    all_child_list_ptr_ = nullptr;
    lower_pool_ptr_ = nullptr;
    lower_off_ptr_ = nullptr;
    lower_logc_q_ptr_ = nullptr;
    best_variant_ptr_ = nullptr;
    kp_pool_ptr_ = nullptr;
    kp_off_ptr_ = nullptr;
    kp_var_start_ptr_ = nullptr;
    kp_var_ids_ptr_ = nullptr;
    variants_ptr_ = nullptr;
    var_surf_pool_ptr_ = nullptr;
    var_surf_pool_len_ = 0;
    big_start_ptr_ = nullptr;
    big_word_ptr_ = nullptr;
    big_logc_ptr_ = nullptr;
    big_rem_ptr_ = nullptr;
    tri_ctx_raw_ptr_ = nullptr;
    tri_ctx_logc_ptr_ = nullptr;
    tri_rem_ptr_ = nullptr;
    tri_start_ptr_ = nullptr;
    tri_word_ptr_ = nullptr;
    tri_logc_ptr_ = nullptr;
    top_words_ptr_ = nullptr;
    top_by_byte_start_ptr_ = nullptr;
    top_by_byte_ids_ptr_ = nullptr;
    byte_range_ptr_ = nullptr;
    aligned_v2_buf_.clear();

    std::fill(std::begin(ascii_to_key_), std::end(ascii_to_key_), -1);
    for (auto& a : alias_keys_) a.clear();

    child_start_vec_.clear();
    child_list_vec_.clear();
    node_char_vec_.clear();
    node_depth_vec_.clear();
    node_term_vec_.clear();
    node_freq_vec_.clear();
    all_child_start_vec_.clear();
    all_child_list_vec_.clear();
    lower_pool_vec_.clear();
    lower_off_vec_.clear();
    lower_logc_q_vec_.clear();
    best_variant_vec_.clear();
    kp_pool_vec_.clear();
    kp_off_vec_.clear();
    kp_var_start_vec_.clear();
    kp_var_ids_vec_.clear();
    variants_vec_.clear();
    var_surf_pool_vec_.clear();
    user_weight_.clear();
    big_start_vec_.clear();
    big_word_vec_.clear();
    big_logc_vec_.clear();
    big_rem_vec_.clear();
    tri_ctx_vec_.clear();
    tri_ctx_logc_vec_.clear();
    tri_rem_vec_.clear();
    tri_start_vec_.clear();
    tri_word_vec_.clear();
    tri_logc_vec_.clear();
    top_words_vec_.clear();
    top_by_byte_start_vec_.clear();
    top_by_byte_ids_vec_.clear();
    byte_range_vec_.clear();
}

void KpDictionary::bind_owned_pointers() {
    n_nodes_ = (uint32_t)node_char_vec_.size();
    n_lower_ = lower_off_vec_.empty() ? 0 : (uint32_t)(lower_off_vec_.size() - 1);
    n_variants_ = (uint32_t)variants_vec_.size();
    n_kps_ = kp_off_vec_.empty() ? 0 : (uint32_t)(kp_off_vec_.size() - 1);
    n_big_ = (uint32_t)big_word_vec_.size();
    n_tri_ctx_ = (uint32_t)tri_ctx_vec_.size();
    n_tri_ = (uint32_t)tri_word_vec_.size();
    n_top_words_ = (uint32_t)top_words_vec_.size();

    node_char_ptr_ = node_char_vec_.data();
    node_depth_ptr_ = node_depth_vec_.data();
    node_term_ptr_ = node_term_vec_.data();
    node_freq_ptr_ = node_freq_vec_.data();
    all_child_start_ptr_ = all_child_start_vec_.data();
    all_child_list_ptr_ = bfs_trie_ ? nullptr : all_child_list_vec_.data();
    child_start_ptr_ = child_start_vec_.empty() ? all_child_start_ptr_ : child_start_vec_.data();
    child_list_ptr_ = child_start_vec_.empty() ? all_child_list_ptr_ : child_list_vec_.data();

    lower_pool_ptr_ = lower_pool_vec_.data();
    lower_off_ptr_ = lower_off_vec_.data();
    lower_logc_q_ptr_ = lower_logc_q_vec_.data();
    best_variant_ptr_ = best_variant_vec_.data();
    kp_pool_ptr_ = kp_pool_vec_.data();
    kp_off_ptr_ = kp_off_vec_.data();
    kp_var_start_ptr_ = kp_var_start_vec_.data();
    kp_var_ids_ptr_ = kp_var_ids_vec_.data();
    variants_ptr_ = variants_vec_.data();
    var_surf_pool_ptr_ = var_surf_pool_vec_.data();
    var_surf_pool_len_ = var_surf_pool_vec_.size();

    big_start_ptr_ = big_start_vec_.data();
    big_word_ptr_ = big_word_vec_.data();
    big_logc_ptr_ = big_logc_vec_.data();
    big_rem_ptr_ = big_rem_vec_.data();

    tri_ctx_raw_ptr_ = reinterpret_cast<const uint8_t*>(tri_ctx_vec_.data());
    tri_ctx_logc_ptr_ = tri_ctx_logc_vec_.data();
    tri_rem_ptr_ = tri_rem_vec_.data();
    tri_start_ptr_ = tri_start_vec_.data();
    tri_word_ptr_ = tri_word_vec_.data();
    tri_logc_ptr_ = tri_logc_vec_.data();

    top_words_ptr_ = top_words_vec_.data();
    top_by_byte_start_ptr_ = top_by_byte_start_vec_.data();
    top_by_byte_ids_ptr_ = top_by_byte_ids_vec_.data();
    byte_range_ptr_ = byte_range_vec_.data();
}

void KpDictionary::build_predict_tables() {
    const uint32_t n_lower = lower_off_vec_.empty() ? 0 : (uint32_t)(lower_off_vec_.size() - 1);
    byte_range_vec_.assign(257, n_lower);
    // lowers in lower_pool_vec_ are sorted by unsigned UTF-8 bytes
    uint32_t cur = 0;
    for (int b = 0; b < 256; ++b) {
        while (cur < n_lower) {
            const uint32_t off = lower_off_vec_[cur];
            const uint32_t len = lower_off_vec_[cur + 1] - off - 1;
            const uint8_t fb = len > 0 ? (uint8_t)lower_pool_vec_[off] : 0;
            if (fb >= b) break;
            ++cur;
        }
        byte_range_vec_[b] = cur;
    }
    byte_range_vec_[256] = n_lower;

    // top_words_vec_: top 128 frequent words overall
    top_words_vec_.resize(n_lower);
    std::iota(top_words_vec_.begin(), top_words_vec_.end(), 0u);
    const size_t n_top = std::min<size_t>(n_lower, 128);
    std::partial_sort(top_words_vec_.begin(), top_words_vec_.begin() + n_top, top_words_vec_.end(),
                      [&](uint32_t a, uint32_t b) {
                          return lower_logc_q_vec_[a] != lower_logc_q_vec_[b]
                                     ? lower_logc_q_vec_[a] > lower_logc_q_vec_[b]
                                     : a < b;
                      });
    top_words_vec_.resize(n_top);

    // top_by_byte_: top 64 frequent words per leading UTF-8 byte
    top_by_byte_start_vec_.assign(257, 0);
    top_by_byte_ids_vec_.clear();
    std::vector<uint32_t> bucket;
    for (int b = 0; b < 256; ++b) {
        top_by_byte_start_vec_[b] = (uint32_t)top_by_byte_ids_vec_.size();
        const uint32_t lo = byte_range_vec_[b], hi = byte_range_vec_[b + 1];
        if (lo >= hi) continue;
        const size_t cnt = hi - lo;
        bucket.resize(cnt);
        std::iota(bucket.begin(), bucket.end(), lo);
        const size_t k = std::min<size_t>(cnt, 64);
        std::partial_sort(bucket.begin(), bucket.begin() + k, bucket.end(),
                          [&](uint32_t a, uint32_t b2) {
                              return lower_logc_q_vec_[a] != lower_logc_q_vec_[b2]
                                         ? lower_logc_q_vec_[a] > lower_logc_q_vec_[b2]
                                         : a < b2;
                          });
        top_by_byte_ids_vec_.insert(top_by_byte_ids_vec_.end(), bucket.begin(), bucket.begin() + k);
    }
    top_by_byte_start_vec_[256] = (uint32_t)top_by_byte_ids_vec_.size();
}

bool KpDictionary::load_kpd(const std::string& path) {
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size < 32) { close(fd); return false; }
    bool ok = load_kpd_fd(fd, 0, (size_t)st.st_size);
    close(fd);
    return ok;
}

bool KpDictionary::load_kpd_fd(int fd, off_t offset, size_t len) {
    clear_all();
    if (fd < 0 || offset < 0 || len < 32) return false;
    const long page_sz = sysconf(_SC_PAGESIZE);
    const off_t page = page_sz > 0 ? (off_t)page_sz : 4096;
    const off_t aligned_off = offset & ~(page - 1);
    const size_t delta = (size_t)(offset - aligned_off);
    const size_t map_len = len + delta;
    void* map = mmap(nullptr, map_len, PROT_READ, MAP_PRIVATE, fd, aligned_off);
    if (map == MAP_FAILED) return false;
    const uint8_t* base = (const uint8_t*)map + delta;

    if (len >= 4 && std::memcmp(base, "DKPD", 4) == 0) {
        // Standalone files (off=0) and Android zipalign-4 APK assets have base % 4 == 0 and run
        // 100% zero-copy mmap. If a caller passes an arbitrary unaligned offset (base % 4 != 0),
        // copy into an 8-byte-aligned buffer so dereferencing u32/f32 arrays stays strictly aligned.
        if (((uintptr_t)base & 3u) != 0) {
            aligned_v2_buf_.resize((len + 7) / 8);
            std::memcpy(aligned_v2_buf_.data(), base, len);
            munmap(map, map_len);
            if (load_v2_buffer(reinterpret_cast<const uint8_t*>(aligned_v2_buf_.data()), len)) {
                format_version_ = 2;
                return true;
            }
            clear_all();
            return false;
        }
        if (load_v2_buffer(base, len)) {
            mmap_base_ = map;
            mmap_len_ = map_len;
            format_version_ = 2;
            return true;
        }
        munmap(map, map_len);
        clear_all();
        return false;
    }

    if (len >= 4 && std::memcmp(base, "KPD1", 4) == 0) {
        bool ok = load_v1_buffer(base, len);
        munmap(map, map_len);
        if (!ok) { clear_all(); return false; }
        format_version_ = 1;
        return true;
    }

    munmap(map, map_len);
    return false;
}

bool KpDictionary::load_v2_buffer(const uint8_t* base, size_t size) {
    // Header: "DKPD" (4), u32 version=2, u32 flags, u32 n_lower, u32 n_variants, u32 n_kps,
    // u32 n_nodes, u32 n_big, u32 n_tri_ctx, u32 n_tri, f64 total, u32 n_sections, u32 reserved
    // Total fixed header = 56 bytes.
    if (size < 56 || std::memcmp(base, "DKPD", 4) != 0) return false;
    uint32_t hdr[9];
    std::memcpy(hdr, base + 4, sizeof(hdr));
    const uint32_t version = hdr[0];
    const uint32_t flags = hdr[1];
    if (version != 2) return false;
    n_lower_ = hdr[2];
    n_variants_ = hdr[3];
    n_kps_ = hdr[4];
    n_nodes_ = hdr[5];
    n_big_ = hdr[6];
    n_tri_ctx_ = hdr[7];
    n_tri_ = hdr[8];
    double tot = 1.0;
    std::memcpy(&tot, base + 40, sizeof(double));
    total_ = std::max(1.0, tot);
    uint32_t n_sections = 0;
    std::memcpy(&n_sections, base + 48, 4);
    if (n_lower_ == 0 || n_variants_ == 0 || n_kps_ == 0 || n_nodes_ == 0 || n_sections > 64) return false;
    if ((size_t)n_lower_ > size || (size_t)n_variants_ > size || (size_t)n_kps_ > size ||
        (size_t)n_nodes_ > size || (size_t)n_big_ > size || (size_t)n_tri_ctx_ > size || (size_t)n_tri_ > size) {
        return false;
    }

    size_t payload_end = size;
    if ((flags & kFlagHasFooter) != 0) {
        if (size < 56 + kFooterSize) return false;
        const uint8_t* ft = base + size - kFooterSize;
        if (std::memcmp(ft, "DKPF", 4) != 0) return false;
        uint64_t ft_size = 0, ft_hash = 0;
        std::memcpy(&ft_size, ft + 8, 8);
        std::memcpy(&ft_hash, ft + 16, 8);
        if (ft_size != (uint64_t)size) return false;
        if (ft_hash != FastHash64::hash(base, size - kFooterSize)) return false;
        payload_end = size - kFooterSize;
    }
    if (56 + (size_t)n_sections * 12 > payload_end) return false;

    struct Sec { const uint8_t* p; uint32_t off; size_t len; };
    std::unordered_map<uint32_t, Sec> sec;
    sec.reserve(n_sections * 2);
    for (uint32_t i = 0; i < n_sections; ++i) {
        const uint8_t* e = base + 56 + (size_t)i * 12;
        uint32_t tag = 0, off = 0, slen = 0;
        std::memcpy(&tag, e, 4);
        std::memcpy(&off, e + 4, 4);
        std::memcpy(&slen, e + 8, 4);
        if ((uint64_t)off + slen > payload_end) return false;
        sec.emplace(tag, Sec{base + off, off, slen});
    }

    auto tag4 = [](const char t[4]) -> uint32_t {
        uint32_t v = 0;
        std::memcpy(&v, t, 4);
        return v;
    };
    auto get_sec = [&](const char t[4], size_t min_bytes, size_t align, const uint8_t*& out_p, size_t& out_len) -> bool {
        auto it = sec.find(tag4(t));
        if (it == sec.end() || it->second.len < min_bytes) return false;
        if (align > 1 && (it->second.off % align) != 0) return false;
        out_p = it->second.p;
        out_len = it->second.len;
        return true;
    };

    const uint8_t* p = nullptr;
    size_t sl = 0;

    // Trie sections
    bfs_trie_ = (flags & kFlagBfsTrie) != 0;
    trie_has_non_az_ = (flags & kFlagTrieHasNonAz) != 0;
    if (!get_sec("TCHS", ((size_t)n_nodes_ + 1) * 4, 4, p, sl)) return false;
    all_child_start_ptr_ = (const uint32_t*)p;
    if (!bfs_trie_) {
        if (!get_sec("TCHL", (size_t)all_child_start_ptr_[n_nodes_] * 4, 4, p, sl)) return false;
        all_child_list_ptr_ = (const uint32_t*)p;
    } else {
        if (all_child_start_ptr_[n_nodes_] != n_nodes_) return false;
        all_child_list_ptr_ = nullptr;
    }
    if ((flags & kFlagHasFooter) == 0) {
        // Legacy v2 files without a checksum footer: still validate TCHS monotonicity.
        const uint32_t max_edge = all_child_start_ptr_[n_nodes_];
        for (uint32_t i = 0; i < n_nodes_; ++i) {
            if (all_child_start_ptr_[i] > all_child_start_ptr_[i + 1] || all_child_start_ptr_[i + 1] > max_edge) {
                return false;
            }
        }
    }
    if (!get_sec("TNCH", n_nodes_, 1, p, sl)) return false;
    node_char_ptr_ = p;
    if (!get_sec("TNTM", (size_t)n_nodes_ * 4, 4, p, sl)) return false;
    node_term_ptr_ = (const int32_t*)p;
    if (!get_sec("TNFQ", n_nodes_, 1, p, sl)) return false;
    node_freq_ptr_ = p;
    if (!get_sec("TNDP", (size_t)n_nodes_ * 2, 2, p, sl)) return false;
    node_depth_ptr_ = (const uint16_t*)p;

    child_start_ptr_ = all_child_start_ptr_;
    child_list_ptr_ = all_child_list_ptr_;

    // Lowercase strings + logc + best_variant
    if (!get_sec("LWOF", ((size_t)n_lower_ + 1) * 4, 4, p, sl)) return false;
    lower_off_ptr_ = (const uint32_t*)p;
    const uint32_t lower_pool_need = lower_off_ptr_[n_lower_];
    if (!get_sec("LWPL", lower_pool_need, 1, p, sl)) return false;
    lower_pool_ptr_ = (const char*)p;
    if (!get_sec("LOGC", (size_t)n_lower_ * 2, 2, p, sl)) return false;
    lower_logc_q_ptr_ = (const uint16_t*)p;
    if (!get_sec("BEST", (size_t)n_lower_ * 4, 4, p, sl)) return false;
    best_variant_ptr_ = (const uint32_t*)p;

    // Key paths + variants
    if (!get_sec("KPOF", ((size_t)n_kps_ + 1) * 4, 4, p, sl)) return false;
    kp_off_ptr_ = (const uint32_t*)p;
    const uint32_t kp_pool_need = kp_off_ptr_[n_kps_];
    if (!get_sec("KPPL", kp_pool_need, 1, p, sl)) return false;
    kp_pool_ptr_ = (const char*)p;
    if (!get_sec("KPVS", ((size_t)n_kps_ + 1) * 4, 4, p, sl)) return false;
    kp_var_start_ptr_ = (const uint32_t*)p;
    const uint32_t kp_var_need = kp_var_start_ptr_[n_kps_];
    if (!get_sec("KPVI", (size_t)kp_var_need * 4, 4, p, sl)) return false;
    kp_var_ids_ptr_ = (const uint32_t*)p;
    if (!get_sec("VARS", (size_t)n_variants_ * sizeof(FlatVariant), 4, p, sl)) return false;
    variants_ptr_ = (const FlatVariant*)p;
    if (get_sec("VRPL", 0, 1, p, sl)) {
        var_surf_pool_ptr_ = (const char*)p;
        var_surf_pool_len_ = sl;
    }

    // Bigrams
    if (!get_sec("BGST", ((size_t)n_lower_ + 1) * 4, 4, p, sl)) return false;
    big_start_ptr_ = (const uint32_t*)p;
    if (big_start_ptr_[n_lower_] != n_big_) return false;
    if (n_big_ > 0) {
        if (!get_sec("BGWD", (size_t)n_big_ * 4, 4, p, sl)) return false;
        big_word_ptr_ = (const uint32_t*)p;
        if (!get_sec("BGLC", n_big_, 1, p, sl)) return false;
        big_logc_ptr_ = p;
    }
    if (!get_sec("BGRM", (size_t)n_lower_ * 4, 4, p, sl)) return false;
    big_rem_ptr_ = (const float*)p;

    // Trigrams (optional when n_tri_ctx_ == 0)
    if (n_tri_ctx_ > 0) {
        if (!get_sec("TGCT", (size_t)n_tri_ctx_ * 8, 8, p, sl)) return false;
        tri_ctx_raw_ptr_ = p;
        if (!get_sec("TGCL", n_tri_ctx_, 1, p, sl)) return false;
        tri_ctx_logc_ptr_ = p;
        if (!get_sec("TGRM", (size_t)n_tri_ctx_ * 4, 4, p, sl)) return false;
        tri_rem_ptr_ = (const float*)p;
        if (!get_sec("TGST", ((size_t)n_tri_ctx_ + 1) * 4, 4, p, sl)) return false;
        tri_start_ptr_ = (const uint32_t*)p;
        if (tri_start_ptr_[n_tri_ctx_] != n_tri_) return false;
        if (n_tri_ > 0) {
            if (!get_sec("TGWD", (size_t)n_tri_ * 4, 4, p, sl)) return false;
            tri_word_ptr_ = (const uint32_t*)p;
            if (!get_sec("TGLC", n_tri_, 1, p, sl)) return false;
            tri_logc_ptr_ = p;
        }
    }

    // Fast predict tables
    if (!get_sec("TOPW", 0, 4, p, sl)) return false;
    top_words_ptr_ = (const uint32_t*)p;
    n_top_words_ = (uint32_t)(sl / 4);
    if (!get_sec("TPBS", 257 * 4, 4, p, sl)) return false;
    top_by_byte_start_ptr_ = (const uint32_t*)p;
    if (!get_sec("TPBI", (size_t)top_by_byte_start_ptr_[256] * 4, 4, p, sl)) return false;
    top_by_byte_ids_ptr_ = (const uint32_t*)p;
    if (!get_sec("BYRG", 257 * 4, 4, p, sl)) return false;
    byte_range_ptr_ = (const uint32_t*)p;

    return true;
}

bool KpDictionary::load_v1_buffer(const uint8_t* base, size_t size) {
    Reader r{base, base + size};
    if (r.bytes(4) != "KPD1" || r.u32() != 2) return false;
    const uint32_t n_lower = r.u32(), n_variants = r.u32(), n_prevs = r.u32(), n_big = r.u32();
    total_ = std::max(1.0, r.f64());
    const uint32_t n_sections = r.u32();
    std::unordered_map<std::string, Reader> sec;
    for (uint32_t i = 0; i < n_sections && r.ok; ++i) {
        std::string tag = r.bytes(4);
        uint32_t off = r.u32(), slen = r.u32();
        if ((uint64_t)off + slen > size) { r.ok = false; break; }
        sec.emplace(tag, Reader{base + off, base + off + slen});
    }
    if (!r.ok) return false;
    for (const char* t : {"CMAP", "LOWR", "LOGC", "WREC", "FREQ"}) {
        if (!sec.count(t)) return false;
    }

    Reader& cm = sec.at("CMAP");
    const uint32_t n_map = cm.u32();
    std::unordered_map<int, char> cmap;
    for (uint32_t i = 0; i < n_map && cm.ok; ++i) {
        int cp = (int)cm.u32();
        cmap[cp] = (char)cm.u8();
    }

    Reader& lw = sec.at("LOWR");
    lower_off_vec_.clear();
    lower_off_vec_.reserve(n_lower + 1);
    lower_pool_vec_.clear();
    lower_pool_vec_.reserve((size_t)n_lower * 10);
    std::string prev;
    for (uint32_t i = 0; i < n_lower && lw.ok; ++i) {
        prev = lw.front(prev);
        lower_off_vec_.push_back((uint32_t)lower_pool_vec_.size());
        lower_pool_vec_.insert(lower_pool_vec_.end(), prev.begin(), prev.end());
        lower_pool_vec_.push_back('\0');
    }
    lower_off_vec_.push_back((uint32_t)lower_pool_vec_.size());

    Reader& lc = sec.at("LOGC");
    lower_logc_q_vec_.resize(n_lower);
    for (uint32_t i = 0; i < n_lower; ++i) lower_logc_q_vec_[i] = lc.u16();
    if (!cm.ok || !lw.ok || !lc.ok) return false;

    // Decode WREC + FREQ into flat variants and keypath->variant map
    struct Pending { std::string surface, keypath; uint32_t lower; uint8_t freq; };
    std::vector<Pending> pending;
    pending.reserve(n_variants);
    Reader& wr = sec.at("WREC");
    Reader& fq = sec.at("FREQ");
    for (uint32_t i = 0; i < n_lower && wr.ok; ++i) {
        uint64_t spec = wr.varint();
        const uint32_t off = lower_off_vec_[i];
        const uint32_t len = lower_off_vec_[i + 1] - off - 1;
        std::string low(lower_pool_vec_.data() + off, len);
        std::string kp = (spec & 4) ? wr.bytes((size_t)wr.varint()) : derive_keypath(low, cmap);
        if (spec & 1) pending.push_back({low, kp, i, 0});
        if (spec & 2) pending.push_back({capitalize(low), kp, i, 0});
        for (uint64_t k = 0, n = spec >> 3; k < n && wr.ok; ++k) {
            pending.push_back({wr.bytes((size_t)wr.varint()), kp, i, 0});
        }
    }
    if (!wr.ok || pending.size() != n_variants || !fq.need(n_variants)) return false;
    for (auto& pv : pending) pv.freq = fq.u8();

    // Build flat variants + sorted keypath map + trie
    variants_vec_.clear();
    variants_vec_.reserve(n_variants);
    var_surf_pool_vec_.clear();
    std::unordered_map<std::string, std::vector<uint32_t>> kp_map;
    kp_map.reserve(n_lower);
    for (auto& pv : pending) {
        if (pv.keypath.empty()) continue;
        FlatVariant fv{};
        fv.lower = pv.lower;
        fv.freq = pv.freq;
        fv.flags = is_capitalized_surface(pv.surface) ? 1 : 0;
        const uint32_t loff = lower_off_vec_[pv.lower];
        const uint32_t llen = lower_off_vec_[pv.lower + 1] - loff - 1;
        if (pv.surface.size() == llen && std::memcmp(pv.surface.data(), lower_pool_vec_.data() + loff, llen) == 0) {
            fv.surf_off = UINT32_MAX;
            fv.surf_len = 0;
        } else {
            fv.surf_off = (uint32_t)var_surf_pool_vec_.size();
            fv.surf_len = (uint16_t)std::min<size_t>(pv.surface.size(), 65535);
            var_surf_pool_vec_.insert(var_surf_pool_vec_.end(), pv.surface.begin(), pv.surface.end());
            var_surf_pool_vec_.push_back('\0');
        }
        const uint32_t vid = (uint32_t)variants_vec_.size();
        variants_vec_.push_back(fv);
        kp_map[pv.keypath].push_back(vid);
        // Collapsed-double key-path aliases for common English 'll contractions ("I'll" -> "il", "we'll" -> "wel", ...)
        // because swipe trajectories rarely dwell twice on the same 'l' key.
        if (pv.keypath.size() >= 3 &&
            pv.keypath[pv.keypath.size() - 1] == 'l' && pv.keypath[pv.keypath.size() - 2] == 'l') {
            std::string_view low_sv(lower_pool_vec_.data() + loff, llen);
            if (low_sv == "i'll" || low_sv == "we'll" || low_sv == "he'll" ||
                low_sv == "she'll" || low_sv == "you'll" || low_sv == "they'll") {
                kp_map[pv.keypath.substr(0, pv.keypath.size() - 1)].push_back(vid);
            }
        }
    }
    if (variants_vec_.empty()) return false;

    std::vector<std::string> sorted_kps;
    sorted_kps.reserve(kp_map.size());
    for (auto& kv : kp_map) sorted_kps.push_back(kv.first);
    std::sort(sorted_kps.begin(), sorted_kps.end());

    kp_off_vec_.clear();
    kp_off_vec_.reserve(sorted_kps.size() + 1);
    kp_pool_vec_.clear();
    kp_var_start_vec_.clear();
    kp_var_start_vec_.reserve(sorted_kps.size() + 1);
    kp_var_ids_vec_.clear();
    kp_var_ids_vec_.reserve(variants_vec_.size());

    node_char_vec_.assign(1, 0);
    node_depth_vec_.assign(1, 0);
    node_term_vec_.assign(1, -1);
    node_freq_vec_.assign(1, 0);
    std::vector<std::vector<uint32_t>> children(1);
    EdgeMap edge;
    edge.reserve(variants_vec_.size() * 3);
    trie_has_non_az_ = false;

    for (uint32_t kpid = 0; kpid < (uint32_t)sorted_kps.size(); ++kpid) {
        const std::string& kp = sorted_kps[kpid];
        kp_off_vec_.push_back((uint32_t)kp_pool_vec_.size());
        kp_pool_vec_.insert(kp_pool_vec_.end(), kp.begin(), kp.end());
        kp_pool_vec_.push_back('\0');

        const auto& vids = kp_map[kp];
        kp_var_start_vec_.push_back((uint32_t)kp_var_ids_vec_.size());
        uint8_t max_f = 0;
        for (uint32_t vid : vids) {
            kp_var_ids_vec_.push_back(vid);
            if (variants_vec_[vid].freq > max_f) max_f = variants_vec_[vid].freq;
        }

        uint32_t n = 0;
        for (size_t i = 0; i < kp.size(); ++i) {
            const uint8_t c = (uint8_t)kp[i];
            if (c < 'a' || c > 'z') trie_has_non_az_ = true;
            const uint64_t e = ((uint64_t)n << 21) | c;
            auto ei = edge.find(e);
            if (ei == edge.end()) {
                const uint32_t id = (uint32_t)node_char_vec_.size();
                node_char_vec_.push_back(c);
                node_depth_vec_.push_back((uint16_t)(i + 1));
                node_term_vec_.push_back(-1);
                node_freq_vec_.push_back(0);
                children.emplace_back();
                children[n].push_back(id);
                edge.emplace(e, id);
                n = id;
            } else {
                n = ei->second;
            }
        }
        node_term_vec_[n] = (int32_t)kpid;
        if (max_f > node_freq_vec_[n]) node_freq_vec_[n] = max_f;
    }
    kp_off_vec_.push_back((uint32_t)kp_pool_vec_.size());
    kp_var_start_vec_.push_back((uint32_t)kp_var_ids_vec_.size());

    // BFS-relabel the trie so every node's children occupy a contiguous range [start, end),
    // eliminating the explicit child-list array for compact, cache-friendly DKPD v2 caches.
    const uint32_t old_n = (uint32_t)node_char_vec_.size();
    std::vector<uint8_t> bfs_char(old_n);
    std::vector<uint16_t> bfs_depth(old_n);
    std::vector<int32_t> bfs_term(old_n);
    std::vector<uint8_t> bfs_freq(old_n);
    all_child_start_vec_.assign((size_t)old_n + 1, 0);
    all_child_list_vec_.clear();
    std::vector<uint32_t> order;
    order.reserve(old_n);
    order.push_back(0);
    for (uint32_t qi = 0; qi < old_n; ++qi) {
        const uint32_t u = order[qi];
        bfs_char[qi] = node_char_vec_[u];
        bfs_depth[qi] = node_depth_vec_[u];
        bfs_term[qi] = node_term_vec_[u];
        bfs_freq[qi] = node_freq_vec_[u];
        all_child_start_vec_[qi] = (uint32_t)order.size();
        auto& ch = children[u];
        std::sort(ch.begin(), ch.end(), [&](uint32_t a, uint32_t b) { return node_char_vec_[a] < node_char_vec_[b]; });
        for (uint32_t v : ch) order.push_back(v);
    }
    all_child_start_vec_[old_n] = (uint32_t)order.size();
    node_char_vec_.swap(bfs_char);
    node_depth_vec_.swap(bfs_depth);
    node_term_vec_.swap(bfs_term);
    node_freq_vec_.swap(bfs_freq);
    bfs_trie_ = true;

    // Bigrams
    big_start_vec_.assign(n_lower + 1, 0);
    big_word_vec_.clear();
    big_word_vec_.reserve(n_big);
    big_logc_vec_.clear();
    big_logc_vec_.reserve(n_big);
    std::vector<std::pair<uint32_t, uint32_t>> ranges;
    auto bi = sec.find("BIGR");
    Reader empty{base, base};
    Reader& bg = bi == sec.end() ? empty : bi->second;
    uint32_t p = 0;
    for (uint32_t i = 0; bi != sec.end() && i < n_prevs && bg.ok; ++i) {
        p += (uint32_t)bg.varint();
        uint32_t n = (uint32_t)bg.varint();
        uint32_t start = (uint32_t)big_word_vec_.size();
        uint32_t w = 0;
        for (uint32_t k = 0; k < n && bg.ok; ++k) {
            w += (uint32_t)bg.varint();
            big_word_vec_.push_back(w);
            big_logc_vec_.push_back(bg.u8());
        }
        if (p < n_lower) ranges.emplace_back(p, start);
    }
    if (!bg.ok) return false;

    uint32_t next = (uint32_t)big_word_vec_.size();
    size_t ri = ranges.size();
    for (int64_t id = (int64_t)n_lower; id >= 0; --id) {
        if (id < (int64_t)n_lower && ri > 0 && ranges[ri - 1].first == (uint32_t)id) next = ranges[--ri].second;
        big_start_vec_[(size_t)id] = next;
    }
    big_start_vec_[n_lower] = (uint32_t)big_word_vec_.size();

    // Precompute unallocated-mass backoff big_rem_[prev] = max(50.0, max(0.15 * c_prev, c_prev - big_sum))
    const auto& luts = exp_luts();
    big_rem_vec_.resize(n_lower);
    for (uint32_t id = 0; id < n_lower; ++id) {
        const uint16_t q = lower_logc_q_vec_[id];
        const double c_prev = luts.exp_q_hi[q >> 8] * luts.exp_q_lo[q & 0xFF];
        double big_sum = 0.0;
        for (uint32_t j = big_start_vec_[id]; j < big_start_vec_[id + 1]; ++j) {
            big_sum += luts.exp_u8[big_logc_vec_[j]];
        }
        const double rem = std::max(0.15 * c_prev, c_prev - big_sum);
        big_rem_vec_[id] = (float)std::max(50.0, rem);
    }

    // Optional trigrams
    tri_ctx_vec_.clear();
    tri_ctx_logc_vec_.clear();
    tri_rem_vec_.clear();
    tri_start_vec_.assign(1, 0);
    tri_word_vec_.clear();
    tri_logc_vec_.clear();
    auto ti = sec.find("TRIG");
    if (ti != sec.end()) {
        Reader& tg = ti->second;
        const uint32_t n_ctx = tg.u32(), n_tri = tg.u32();
        tri_ctx_vec_.reserve(n_ctx);
        tri_ctx_logc_vec_.reserve(n_ctx);
        tri_rem_vec_.reserve(n_ctx);
        tri_start_vec_.reserve(n_ctx + 1);
        tri_word_vec_.reserve(n_tri);
        tri_logc_vec_.reserve(n_tri);
        uint32_t w1 = 0, w2 = 0;
        for (uint32_t i = 0; i < n_ctx && tg.ok; ++i) {
            uint32_t d1 = (uint32_t)tg.varint();
            w1 += d1;
            w2 = d1 ? (uint32_t)tg.varint() : w2 + (uint32_t)tg.varint();
            uint8_t ctx_q = tg.u8();
            tri_ctx_logc_vec_.push_back(ctx_q);
            uint32_t n = (uint32_t)tg.varint();
            uint32_t w = 0;
            double tri_sum = 0.0;
            for (uint32_t k = 0; k < n && tg.ok; ++k) {
                w += (uint32_t)tg.varint();
                uint8_t q3 = tg.u8();
                tri_word_vec_.push_back(w);
                tri_logc_vec_.push_back(q3);
                tri_sum += luts.exp_u8[q3];
            }
            const double cctx = luts.exp_u8[ctx_q];
            tri_rem_vec_.push_back((float)std::max(0.15 * cctx, cctx - tri_sum));
            tri_ctx_vec_.push_back(((uint64_t)w1 << 32) | w2);
            tri_start_vec_.push_back((uint32_t)tri_word_vec_.size());
        }
        if (!tg.ok || w1 >= n_lower) {
            tri_ctx_vec_.clear();
            tri_ctx_logc_vec_.clear();
            tri_rem_vec_.clear();
            tri_start_vec_.assign(1, 0);
            tri_word_vec_.clear();
            tri_logc_vec_.clear();
        }
    }

    best_variant_vec_.assign(n_lower, UINT32_MAX);
    for (uint32_t v = 0; v < (uint32_t)variants_vec_.size(); ++v) {
        uint32_t& b = best_variant_vec_[variants_vec_[v].lower];
        if (b == UINT32_MAX || variants_vec_[v].freq > variants_vec_[b].freq) b = v;
    }
    build_predict_tables();
    bind_owned_pointers();
    return true;
}

bool KpDictionary::save_v2(const std::string& path) const {
    if (n_lower_ == 0 || n_variants_ == 0 || n_kps_ == 0 || n_nodes_ == 0) return false;
    struct SecSpec {
        char tag[4];
        const void* data;
        uint32_t len;
        uint32_t align;
    };
    std::vector<SecSpec> specs;
    specs.reserve(32);
    auto add = [&](const char tag[4], const void* data, size_t len, uint32_t align) {
        SecSpec s{};
        std::memcpy(s.tag, tag, 4);
        s.data = data;
        s.len = (uint32_t)len;
        s.align = align;
        specs.push_back(s);
    };

    const uint32_t flags = (bfs_trie_ ? kFlagBfsTrie : 0u) | (trie_has_non_az_ ? kFlagTrieHasNonAz : 0u) | kFlagHasFooter;
    add("TCHS", all_child_start_ptr_, ((size_t)n_nodes_ + 1) * 4, 4);
    if (!bfs_trie_) {
        add("TCHL", all_child_list_ptr_, (size_t)all_child_start_ptr_[n_nodes_] * 4, 4);
    }
    add("TNCH", node_char_ptr_, n_nodes_, 1);
    add("TNTM", node_term_ptr_, (size_t)n_nodes_ * 4, 4);
    add("TNFQ", node_freq_ptr_, n_nodes_, 1);
    add("TNDP", node_depth_ptr_, (size_t)n_nodes_ * 2, 2);

    add("LWOF", lower_off_ptr_, ((size_t)n_lower_ + 1) * 4, 4);
    add("LWPL", lower_pool_ptr_, lower_off_ptr_[n_lower_], 1);
    add("LOGC", lower_logc_q_ptr_, (size_t)n_lower_ * 2, 2);
    add("BEST", best_variant_ptr_, (size_t)n_lower_ * 4, 4);

    add("KPOF", kp_off_ptr_, ((size_t)n_kps_ + 1) * 4, 4);
    add("KPPL", kp_pool_ptr_, kp_off_ptr_[n_kps_], 1);
    add("KPVS", kp_var_start_ptr_, ((size_t)n_kps_ + 1) * 4, 4);
    add("KPVI", kp_var_ids_ptr_, (size_t)kp_var_start_ptr_[n_kps_] * 4, 4);
    add("VARS", variants_ptr_, (size_t)n_variants_ * sizeof(FlatVariant), 4);
    if (var_surf_pool_len_ > 0 && var_surf_pool_ptr_) add("VRPL", var_surf_pool_ptr_, var_surf_pool_len_, 1);

    add("BGST", big_start_ptr_, ((size_t)n_lower_ + 1) * 4, 4);
    if (n_big_ > 0) {
        add("BGWD", big_word_ptr_, (size_t)n_big_ * 4, 4);
        add("BGLC", big_logc_ptr_, n_big_, 1);
    }
    add("BGRM", big_rem_ptr_, (size_t)n_lower_ * 4, 4);

    if (n_tri_ctx_ > 0) {
        add("TGCT", tri_ctx_raw_ptr_, (size_t)n_tri_ctx_ * 8, 8);
        add("TGCL", tri_ctx_logc_ptr_, n_tri_ctx_, 1);
        add("TGRM", tri_rem_ptr_, (size_t)n_tri_ctx_ * 4, 4);
        add("TGST", tri_start_ptr_, ((size_t)n_tri_ctx_ + 1) * 4, 4);
        if (n_tri_ > 0) {
            add("TGWD", tri_word_ptr_, (size_t)n_tri_ * 4, 4);
            add("TGLC", tri_logc_ptr_, n_tri_, 1);
        }
    }

    add("TOPW", top_words_ptr_, (size_t)n_top_words_ * 4, 4);
    add("TPBS", top_by_byte_start_ptr_, 257 * 4, 4);
    add("TPBI", top_by_byte_ids_ptr_, (size_t)top_by_byte_start_ptr_[256] * 4, 4);
    add("BYRG", byte_range_ptr_, 257 * 4, 4);

    const uint32_t n_sec = (uint32_t)specs.size();
    uint32_t cur_off = 56u + n_sec * 12u;
    std::vector<uint32_t> sec_offs(n_sec);
    for (uint32_t i = 0; i < n_sec; ++i) {
        const uint32_t a = specs[i].align;
        if (a > 1) cur_off = (cur_off + a - 1u) & ~(a - 1u);
        sec_offs[i] = cur_off;
        cur_off += specs[i].len;
    }

    FILE* fp = std::fopen(path.c_str(), "wb");
    if (!fp) return false;

    FastHash64 hasher;
    auto write_hashed = [&](const void* data, size_t len) -> bool {
        if (len == 0) return true;
        hasher.update(data, len);
        return std::fwrite(data, 1, len, fp) == len;
    };

    uint8_t hdr[56] = {};
    std::memcpy(hdr, "DKPD", 4);
    const uint32_t fields[9] = {2u, flags, n_lower_, n_variants_, n_kps_, n_nodes_, n_big_, n_tri_ctx_, n_tri_};
    std::memcpy(hdr + 4, fields, sizeof(fields));
    std::memcpy(hdr + 40, &total_, sizeof(double));
    std::memcpy(hdr + 48, &n_sec, 4);
    bool ok = write_hashed(hdr, 56);

    for (uint32_t i = 0; i < n_sec && ok; ++i) {
        uint8_t entry[12];
        std::memcpy(entry, specs[i].tag, 4);
        std::memcpy(entry + 4, &sec_offs[i], 4);
        std::memcpy(entry + 8, &specs[i].len, 4);
        ok = write_hashed(entry, 12);
    }

    uint32_t pos = 56u + n_sec * 12u;
    static const uint8_t kZeros[8] = {};
    for (uint32_t i = 0; i < n_sec && ok; ++i) {
        if (sec_offs[i] > pos) {
            const uint32_t pad = sec_offs[i] - pos;
            ok = write_hashed(kZeros, pad);
            pos += pad;
        }
        if (ok && specs[i].len > 0) {
            ok = write_hashed(specs[i].data, specs[i].len);
            pos += specs[i].len;
        }
    }
    if (ok) {
        uint8_t footer[kFooterSize] = {};
        const uint64_t total_size = (uint64_t)pos + kFooterSize;
        const uint64_t digest = hasher.finish();
        std::memcpy(footer, "DKPF", 4);
        std::memcpy(footer + 8, &total_size, 8);
        std::memcpy(footer + 16, &digest, 8);
        ok = std::fwrite(footer, 1, kFooterSize, fp) == kFooterSize;
    }
    if (ok && std::fflush(fp) != 0) ok = false;
    if (ok && ::fsync(fileno(fp)) != 0) ok = false;
    if (std::fclose(fp) != 0) ok = false;
    return ok;
}

void KpDictionary::load_user(const std::vector<std::pair<std::string, int>>& words) {
    clear_all();
    struct Entry { std::string surface, kp, low; uint8_t weight; };
    std::vector<Entry> entries;
    std::unordered_map<std::string, bool> seen;
    std::unordered_map<std::string, uint8_t> low_w;
    for (const auto& [surface, weight] : words) {
        if (surface.empty() || !seen.emplace(surface, true).second) continue;
        std::string kp = key_path_of(surface);
        if (kp.size() < 2) continue;
        std::string low = to_lower_utf8(surface);
        uint8_t w = (uint8_t)std::clamp(weight, 0, 255);
        auto it = low_w.find(low);
        if (it == low_w.end()) low_w.emplace(low, w);
        else it->second = std::max(it->second, w);
        entries.push_back({surface, std::move(kp), std::move(low), w});
    }

    std::vector<std::string> sorted_lows;
    sorted_lows.reserve(low_w.size());
    for (auto& kv : low_w) sorted_lows.push_back(kv.first);
    std::sort(sorted_lows.begin(), sorted_lows.end());

    std::unordered_map<std::string, uint32_t> low_id;
    low_id.reserve(sorted_lows.size() * 2);
    lower_off_vec_.reserve(sorted_lows.size() + 1);
    lower_logc_q_vec_.assign(sorted_lows.size(), 0);
    user_weight_.resize(sorted_lows.size(), 0);
    for (uint32_t i = 0; i < (uint32_t)sorted_lows.size(); ++i) {
        low_id.emplace(sorted_lows[i], i);
        user_weight_[i] = low_w[sorted_lows[i]];
        lower_off_vec_.push_back((uint32_t)lower_pool_vec_.size());
        lower_pool_vec_.insert(lower_pool_vec_.end(), sorted_lows[i].begin(), sorted_lows[i].end());
        lower_pool_vec_.push_back('\0');
    }
    lower_off_vec_.push_back((uint32_t)lower_pool_vec_.size());

    std::unordered_map<std::string, std::vector<uint32_t>> kp_map;
    variants_vec_.reserve(entries.size());
    for (const auto& e : entries) {
        uint32_t lid = low_id.at(e.low);
        FlatVariant fv{};
        fv.lower = lid;
        fv.freq = e.weight;
        fv.flags = is_capitalized_surface(e.surface) ? 1 : 0;
        if (e.surface == e.low) {
            fv.surf_off = UINT32_MAX;
            fv.surf_len = 0;
        } else {
            fv.surf_off = (uint32_t)var_surf_pool_vec_.size();
            fv.surf_len = (uint16_t)std::min<size_t>(e.surface.size(), 65535);
            var_surf_pool_vec_.insert(var_surf_pool_vec_.end(), e.surface.begin(), e.surface.end());
            var_surf_pool_vec_.push_back('\0');
        }
        uint32_t vid = (uint32_t)variants_vec_.size();
        variants_vec_.push_back(fv);
        kp_map[e.kp].push_back(vid);
    }

    std::vector<std::string> sorted_kps;
    sorted_kps.reserve(kp_map.size());
    for (auto& kv : kp_map) sorted_kps.push_back(kv.first);
    std::sort(sorted_kps.begin(), sorted_kps.end());

    node_char_vec_.assign(1, 0);
    node_depth_vec_.assign(1, 0);
    node_term_vec_.assign(1, -1);
    node_freq_vec_.assign(1, 0);
    std::vector<std::vector<uint32_t>> children(1);
    EdgeMap edge;

    for (uint32_t kpid = 0; kpid < (uint32_t)sorted_kps.size(); ++kpid) {
        const std::string& kp = sorted_kps[kpid];
        kp_off_vec_.push_back((uint32_t)kp_pool_vec_.size());
        kp_pool_vec_.insert(kp_pool_vec_.end(), kp.begin(), kp.end());
        kp_pool_vec_.push_back('\0');

        const auto& vids = kp_map[kp];
        kp_var_start_vec_.push_back((uint32_t)kp_var_ids_vec_.size());
        uint8_t max_f = 0;
        for (uint32_t vid : vids) {
            kp_var_ids_vec_.push_back(vid);
            if (variants_vec_[vid].freq > max_f) max_f = variants_vec_[vid].freq;
        }
        uint32_t n = 0;
        for (size_t i = 0; i < kp.size(); ++i) {
            const uint8_t c = (uint8_t)kp[i];
            const uint64_t ek = ((uint64_t)n << 21) | c;
            auto ei = edge.find(ek);
            if (ei == edge.end()) {
                const uint32_t id = (uint32_t)node_char_vec_.size();
                node_char_vec_.push_back(c);
                node_depth_vec_.push_back((uint16_t)(i + 1));
                node_term_vec_.push_back(-1);
                node_freq_vec_.push_back(0);
                children.emplace_back();
                children[n].push_back(id);
                edge.emplace(ek, id);
                n = id;
            } else {
                n = ei->second;
            }
        }
        node_term_vec_[n] = (int32_t)kpid;
        if (max_f > node_freq_vec_[n]) node_freq_vec_[n] = max_f;
    }
    kp_off_vec_.push_back((uint32_t)kp_pool_vec_.size());
    kp_var_start_vec_.push_back((uint32_t)kp_var_ids_vec_.size());

    bfs_trie_ = false;
    all_child_start_vec_.assign(children.size() + 1, 0);
    for (size_t i = 0; i < children.size(); ++i) {
        all_child_start_vec_[i + 1] = all_child_start_vec_[i] + (uint32_t)children[i].size();
    }
    for (auto& c : children) all_child_list_vec_.insert(all_child_list_vec_.end(), c.begin(), c.end());

    const uint32_t n_lower = (uint32_t)sorted_lows.size();
    big_start_vec_.assign(n_lower + 1, 0);
    big_rem_vec_.assign(n_lower, 50.0f);
    best_variant_vec_.assign(n_lower, UINT32_MAX);
    for (uint32_t v = 0; v < (uint32_t)variants_vec_.size(); ++v) {
        uint32_t& b = best_variant_vec_[variants_vec_[v].lower];
        if (b == UINT32_MAX || variants_vec_[v].freq > variants_vec_[b].freq) b = v;
    }
    build_predict_tables();
    bind_owned_pointers();
}

void KpDictionary::set_layout(const std::vector<int>& layout_codepoints) {
    num_chars_ = (int)layout_codepoints.size();
    std::fill(std::begin(ascii_to_key_), std::end(ascii_to_key_), -1);
    for (int i = 0; i < (int)layout_codepoints.size(); ++i) {
        int cp = layout_codepoints[i];
        if (cp >= 0 && cp < 256 && ascii_to_key_[cp] < 0) ascii_to_key_[cp] = i;
    }
    // Keys of letters with diacritics on the layout itself (Spanish ñ, German ü ö ä): the trie only
    // has base letters, so every node of the base letter also gets a twin reached through that key
    // (n through ñ, u through ü).
    bool has_aliases = false;
    for (auto& a : alias_keys_) a.clear();
    for (int i = 0; i < (int)layout_codepoints.size(); ++i) {
        int cp = layout_codepoints[i];
        if (cp >= 'a' && cp <= 'z') continue;
        int b = builtin_base(lower_cp(cp));
        if (b && (int)alias_keys_[b - 'a'].size() < kMaxAliases && ascii_to_key_[b] >= 0) {
            alias_keys_[b - 'a'].push_back(i);
            has_aliases = true;
        }
    }

    bool all_az_present = true;
    for (int c = 'a'; c <= 'z'; ++c) {
        if (ascii_to_key_[c] < 0) { all_az_present = false; break; }
    }

    // Fast zero-copy path: standard 26-letter layout with BFS-ordered v2 trie (or non-BFS trie)
    if (!has_aliases && all_az_present && !trie_has_non_az_) {
        child_start_vec_.clear();
        child_list_vec_.clear();
        child_start_ptr_ = all_child_start_ptr_;
        child_list_ptr_ = all_child_list_ptr_;
        return;
    }

    // Filtered children (alias twins or missing letters)
    child_start_vec_.assign((size_t)n_nodes_ + 1, 0);
    child_list_vec_.clear();
    if (all_child_start_ptr_) child_list_vec_.reserve(all_child_start_ptr_[n_nodes_]);
    for (uint32_t n = 0; n < n_nodes_; ++n) {
        child_start_vec_[n] = (uint32_t)child_list_vec_.size();
        if (n == 0 || ascii_to_key_[node_char_ptr_[n]] >= 0) {
            const uint32_t s = all_child_start_ptr_[n], e = all_child_start_ptr_[n + 1];
            for (uint32_t i = s; i < e; ++i) {
                const uint32_t c = all_child_list_ptr_ ? all_child_list_ptr_[i] : i;
                const uint8_t ch = node_char_ptr_[c];
                if (ascii_to_key_[ch] < 0) continue;
                child_list_vec_.push_back(c);
                if (ch >= 'a' && ch <= 'z' && c < kAliasBase) {
                    const auto& al = alias_keys_[ch - 'a'];
                    for (size_t k = 0; k < al.size(); ++k) {
                        child_list_vec_.push_back(c | ((uint32_t)(k + 1) << kAliasShift));
                    }
                }
            }
        }
    }
    child_start_vec_[n_nodes_] = (uint32_t)child_list_vec_.size();
    child_start_ptr_ = child_start_vec_.data();
    child_list_ptr_ = child_list_vec_.data();
}

int KpDictionary::key_of(uint32_t id) const {
    const uint32_t alias = id >> kAliasShift;
    const uint32_t n = id & (kAliasBase - 1);
    const uint8_t c = node_char_ptr_[n];
    if (alias == 0) return ascii_to_key_[c];
    return (c >= 'a' && c <= 'z' && alias <= alias_keys_[c - 'a'].size()) ? alias_keys_[c - 'a'][alias - 1] : -1;
}

std::string_view KpDictionary::lower_word(uint32_t lower) const {
    const uint32_t off = lower_off_ptr_[lower];
    const uint32_t next = lower_off_ptr_[lower + 1];
    return std::string_view(lower_pool_ptr_ + off, next > off ? (size_t)(next - off - 1) : 0);
}

std::string_view KpDictionary::kp_word(uint32_t kp_id) const {
    const uint32_t off = kp_off_ptr_[kp_id];
    const uint32_t next = kp_off_ptr_[kp_id + 1];
    return std::string_view(kp_pool_ptr_ + off, next > off ? (size_t)(next - off - 1) : 0);
}

VariantView KpDictionary::variant(uint32_t i) const {
    const FlatVariant& fv = variants_ptr_[i];
    std::string_view surf = (fv.surf_off == UINT32_MAX)
                                ? lower_word(fv.lower)
                                : std::string_view(var_surf_pool_ptr_ + fv.surf_off, fv.surf_len);
    return VariantView{surf, (float)fv.freq, fv.lower, (fv.flags & 1) != 0};
}

VariantList KpDictionary::variants_of(std::string_view keypath) const {
    if (keypath.empty() || n_kps_ == 0) return {};
    uint32_t lo = 0, hi = n_kps_;
    while (lo < hi) {
        const uint32_t mid = lo + ((hi - lo) >> 1);
        if (kp_word(mid) < keypath) lo = mid + 1;
        else hi = mid;
    }
    if (lo < n_kps_ && kp_word(lo) == keypath) {
        const uint32_t s = kp_var_start_ptr_[lo], e = kp_var_start_ptr_[lo + 1];
        return VariantList{kp_var_ids_ptr_ + s, e - s};
    }
    return {};
}

int32_t KpDictionary::lower_id(std::string_view word_lower) const {
    if (word_lower.empty() || n_lower_ == 0) return -1;
    const uint8_t b0 = (uint8_t)word_lower[0];
    uint32_t lo = byte_range_ptr_[b0], hi = byte_range_ptr_[b0 + 1];
    while (lo < hi) {
        const uint32_t mid = lo + ((hi - lo) >> 1);
        if (lower_word(mid) < word_lower) lo = mid + 1;
        else hi = mid;
    }
    return (lo < n_lower_ && lower_word(lo) == word_lower) ? (int32_t)lo : -1;
}

double KpDictionary::lower_count(uint32_t word) const {
    const uint16_t q = lower_logc_q_ptr_[word];
    const auto& luts = exp_luts();
    return luts.exp_q_hi[q >> 8] * luts.exp_q_lo[q & 0xFF];
}

double KpDictionary::bigram_count_in(uint32_t b_start, uint32_t b_end, uint32_t word) const {
    if (b_start >= b_end) return 0.0;
    const uint32_t* b = big_word_ptr_ + b_start;
    const uint32_t* e = big_word_ptr_ + b_end;
    const uint32_t* it = std::lower_bound(b, e, word);
    return (it != e && *it == word) ? exp_luts().exp_u8[big_logc_ptr_[it - big_word_ptr_]] : 0.0;
}

// P(w | prev), using unallocated-mass backoff so pruned bigrams back off cleanly to ~0.15..0.5 * pu
// instead of 1e-5 * pu after high-frequency words ("v", "a", "na").
double KpDictionary::p_bigram(int32_t prev, uint32_t word, double pu) const {
    const double c_prev = lower_count((uint32_t)prev);
    const double bg = bigram_count_in(big_start_ptr_[prev], big_start_ptr_[prev + 1], word);
    const double k_eff = (double)big_rem_ptr_[prev];
    return std::min(1.0, (bg + k_eff * pu) / (c_prev + 50.0));
}

float KpDictionary::pmi(int32_t prev, uint32_t word) const {
    if (prev < 0 || (uint32_t)prev >= n_lower_ || word >= n_lower_) return 0.0f;
    const double pu = (lower_count(word) + 0.5) / total_;
    return (float)(std::log(p_bigram(prev, word, pu)) - std::log(pu));
}

float KpDictionary::logp_ctx(int32_t prev2, int32_t prev, uint32_t word) const {
    if (word >= n_lower_) return -30.0f;
    const double pu = (lower_count(word) + 0.5) / total_;
    if (prev < 0 || (uint32_t)prev >= n_lower_) return (float)std::log(pu);
    double p = p_bigram(prev, word, pu);
    if (prev2 >= 0 && n_tri_ctx_ > 0) {
        const uint64_t key = ((uint64_t)(uint32_t)prev2 << 32) | (uint32_t)prev;
        const int64_t ci = find_tri_ctx(tri_ctx_raw_ptr_, n_tri_ctx_, key);
        if (ci >= 0) {
            const size_t c = (size_t)ci;
            const uint32_t* tb = tri_word_ptr_ + tri_start_ptr_[c];
            const uint32_t* te = tri_word_ptr_ + tri_start_ptr_[c + 1];
            const uint32_t* wt = std::lower_bound(tb, te, word);
            const auto& luts = exp_luts();
            const double c3 = (wt != te && *wt == word) ? luts.exp_u8[tri_logc_ptr_[wt - tri_word_ptr_]] : 0.0;
            const double cctx = luts.exp_u8[tri_ctx_logc_ptr_[c]];
            const double K3 = std::max(0.01f, params.k3);
            const double k3_eff = std::max(K3, (double)tri_rem_ptr_[c]);
            p = std::min(1.0, (c3 + k3_eff * p) / (cctx + K3));
        }
    }
    return (float)std::log(p);
}

float KpDictionary::pmi3(int32_t prev2, int32_t prev, uint32_t word) const {
    if (prev < 0 || (uint32_t)prev >= n_lower_ || word >= n_lower_) return 0.0f;
    return logp_ctx(prev2, prev, word) - logp_id(word);
}

float KpDictionary::logp_id(uint32_t word) const {
    const double c = word < n_lower_ ? lower_count(word) : 0.0;
    return (float)std::log((c + 0.5) / total_);
}

float KpDictionary::logp(std::string_view word_lower) const {
    const int32_t id = lower_id(word_lower);
    const double c = id < 0 ? 0.0 : lower_count((uint32_t)id);
    return (float)std::log((c + 0.5) / total_);
}

std::string_view KpDictionary::surface_of(uint32_t lower) const {
    if (lower < n_lower_ && best_variant_ptr_[lower] != UINT32_MAX) {
        return variant(best_variant_ptr_[lower]).surface;
    }
    return lower_word(lower);
}

std::vector<std::pair<uint32_t, float>> KpDictionary::predict(int32_t prev2, int32_t prev, const std::string& prefix_lower,
                                                              int max_results) const {
    std::vector<std::pair<uint32_t, float>> out;
    if (max_results <= 0 || n_lower_ == 0) return out;

    const auto& luts = exp_luts();
    const double inv_total = 1.0 / total_;
    const bool has_prev = (prev >= 0 && (uint32_t)prev < n_lower_);

    // Hoist bigram context lookup out of candidate loop
    uint32_t big_b = 0, big_e = 0;
    double big_inv_denom = 0.0, big_k_eff = 0.0, big_backoff = 0.0;
    if (has_prev) {
        big_b = big_start_ptr_[prev];
        big_e = big_start_ptr_[prev + 1];
        const double c_prev = lower_count((uint32_t)prev);
        big_inv_denom = 1.0 / (c_prev + 50.0);
        big_k_eff = (double)big_rem_ptr_[prev];
        big_backoff = big_k_eff * big_inv_denom;
    }

    // Hoist trigram context lookup out of candidate loop
    uint32_t tri_b = 0, tri_e = 0;
    bool has_tri = false;
    double tri_inv_denom = 0.0, tri_k_eff = 0.0, tri_backoff = 0.0;
    if (has_prev && prev2 >= 0 && n_tri_ctx_ > 0) {
        const uint64_t key = ((uint64_t)(uint32_t)prev2 << 32) | (uint32_t)prev;
        const int64_t ci = find_tri_ctx(tri_ctx_raw_ptr_, n_tri_ctx_, key);
        if (ci >= 0) {
            const size_t c = (size_t)ci;
            tri_b = tri_start_ptr_[c];
            tri_e = tri_start_ptr_[c + 1];
            const double cctx = luts.exp_u8[tri_ctx_logc_ptr_[c]];
            const double K3 = std::max(0.01f, params.k3);
            tri_inv_denom = 1.0 / (cctx + K3);
            tri_k_eff = std::max(K3, (double)tri_rem_ptr_[c]);
            tri_backoff = tri_k_eff * tri_inv_denom;
            has_tri = true;
        }
    }

    // Determine contiguous word-id range [pref_lo, pref_hi) matching prefix_lower
    uint32_t pref_lo = 0, pref_hi = n_lower_;
    int32_t exact_id = -1;
    if (!prefix_lower.empty()) {
        const uint8_t b0 = (uint8_t)prefix_lower[0];
        pref_lo = byte_range_ptr_[b0];
        pref_hi = byte_range_ptr_[b0 + 1];
        if (prefix_lower.size() > 1 && pref_lo < pref_hi) {
            uint32_t lo = pref_lo, hi = pref_hi;
            while (lo < hi) {
                const uint32_t mid = lo + ((hi - lo) >> 1);
                if (lower_word(mid) < prefix_lower) lo = mid + 1;
                else hi = mid;
            }
            pref_lo = lo;
            lo = pref_lo;
            hi = pref_hi;
            const size_t plen = prefix_lower.size();
            while (lo < hi) {
                const uint32_t mid = lo + ((hi - lo) >> 1);
                std::string_view sv = lower_word(mid);
                if (sv.size() >= plen && std::memcmp(sv.data(), prefix_lower.data(), plen) == 0) lo = mid + 1;
                else hi = mid;
            }
            pref_hi = lo;
        }
        if (pref_lo >= pref_hi) return out;
        if (lower_word(pref_lo) == prefix_lower) exact_id = (int32_t)pref_lo;
    }

    // Slice sorted bigram and trigram continuations to [pref_lo, pref_hi) in O(log deg)
    uint32_t big_lo = big_b, big_hi = big_e;
    if (!prefix_lower.empty() && big_b < big_e) {
        big_lo = (uint32_t)(std::lower_bound(big_word_ptr_ + big_b, big_word_ptr_ + big_e, pref_lo) - big_word_ptr_);
        big_hi = (uint32_t)(std::lower_bound(big_word_ptr_ + big_lo, big_word_ptr_ + big_e, pref_hi) - big_word_ptr_);
    }
    uint32_t tri_lo = tri_b, tri_hi = tri_e;
    if (!prefix_lower.empty() && tri_b < tri_e) {
        tri_lo = (uint32_t)(std::lower_bound(tri_word_ptr_ + tri_b, tri_word_ptr_ + tri_e, pref_lo) - tri_word_ptr_);
        tri_hi = (uint32_t)(std::lower_bound(tri_word_ptr_ + tri_lo, tri_word_ptr_ + tri_e, pref_hi) - tri_word_ptr_);
    }

    // Min-heap of top max_results candidates scored in linear probability space (log applied only to winners)
    struct Cand { uint32_t w; double p; };
    const size_t keep = (size_t)max_results;
    std::vector<Cand> heap;
    heap.reserve(keep + 1);
    auto worse = [](const Cand& a, const Cand& b) { return a.p != b.p ? a.p > b.p : a.w < b.w; };
    auto consider_dedup = [&](uint32_t w, double p, bool check_dup) {
        if (heap.size() == keep && !(p > heap.front().p || (p == heap.front().p && w < heap.front().w))) return;
        if (check_dup) {
            for (const auto& c : heap) if (c.w == w) return;
        }
        if (heap.size() < keep) {
            heap.push_back({w, p});
            std::push_heap(heap.begin(), heap.end(), worse);
        } else {
            std::pop_heap(heap.begin(), heap.end(), worse);
            heap.back() = {w, p};
            std::push_heap(heap.begin(), heap.end(), worse);
        }
    };

    auto score_prob = [&](uint32_t w, double c_bg, double c_tri) -> double {
        const uint16_t q = lower_logc_q_ptr_[w];
        const double pu = (luts.exp_q_hi[q >> 8] * luts.exp_q_lo[q & 0xFF] + 0.5) * inv_total;
        if (!has_prev) return pu;
        double p = c_bg > 0.0 ? std::min(1.0, (c_bg + big_k_eff * pu) * big_inv_denom)
                              : std::min(1.0, pu * big_backoff);
        if (has_tri) {
            p = c_tri > 0.0 ? std::min(1.0, (c_tri + tri_k_eff * p) * tri_inv_denom)
                            : std::min(1.0, p * tri_backoff);
        }
        return p;
    };

    // Pass 1: trigram continuations (always small, 0..~50 entries)
    for (uint32_t j = tri_lo; j < tri_hi; ++j) {
        const uint32_t w = tri_word_ptr_[j];
        if (w >= n_lower_ || (int32_t)w == exact_id) continue;
        const double c_bg = bigram_count_in(big_lo, big_hi, w);
        const double c_tri = luts.exp_u8[tri_logc_ptr_[j]];
        consider_dedup(w, score_prob(w, c_bg, c_tri), false);
    }

    // Pass 2: precomputed frequent words (top-128 empty / top-64 1-byte / prefix span).
    // Evaluating these BEFORE scanning bigrams primes `heap` with high-probability words so Pass 3
    // can prune 99%+ of low-count bigrams with a single 1-byte `big_logc[i] < q_min` check!
    bool all_prefix_scored = false;
    double max_unseen_pu = 1.0;
    auto eval_freq = [&](uint32_t w) {
        if (w >= n_lower_ || (int32_t)w == exact_id) return;
        if (tri_lo < tri_hi && std::binary_search(tri_word_ptr_ + tri_lo, tri_word_ptr_ + tri_hi, w)) return;
        const double c_bg = bigram_count_in(big_lo, big_hi, w);
        consider_dedup(w, score_prob(w, c_bg, 0.0), false);
    };

    if (prefix_lower.empty()) {
        for (uint32_t i = 0; i < n_top_words_; ++i) eval_freq(top_words_ptr_[i]);
        if (n_top_words_ >= n_lower_) {
            all_prefix_scored = true;
        } else if (n_top_words_ > 0) {
            const uint16_t q_last = lower_logc_q_ptr_[top_words_ptr_[n_top_words_ - 1]];
            max_unseen_pu = (luts.exp_q_hi[q_last >> 8] * luts.exp_q_lo[q_last & 0xFF] + 0.5) * inv_total;
        }
    } else if (prefix_lower.size() == 1) {
        const uint8_t b0 = (uint8_t)prefix_lower[0];
        const uint32_t s = top_by_byte_start_ptr_[b0], e = top_by_byte_start_ptr_[b0 + 1];
        for (uint32_t i = s; i < e; ++i) eval_freq(top_by_byte_ids_ptr_[i]);
        if (e - s >= pref_hi - pref_lo) {
            all_prefix_scored = true;
        } else if (e > s) {
            const uint16_t q_last = lower_logc_q_ptr_[top_by_byte_ids_ptr_[e - 1]];
            max_unseen_pu = (luts.exp_q_hi[q_last >> 8] * luts.exp_q_lo[q_last & 0xFF] + 0.5) * inv_total;
        }
    } else {
        const uint32_t span = pref_hi - pref_lo;
        if (span <= 128) {
            for (uint32_t w = pref_lo; w < pref_hi; ++w) eval_freq(w);
            all_prefix_scored = true;
        } else {
            // Fixed-size stack min-heap of top 64 frequent words in [pref_lo, pref_hi): O(span), 0 heap allocs
            constexpr size_t kTopSpan = 64;
            std::pair<uint16_t, uint32_t> top_q[kTopSpan];
            size_t nt = 0;
            auto q_worse = [](const std::pair<uint16_t, uint32_t>& a, const std::pair<uint16_t, uint32_t>& b) {
                return a.first != b.first ? a.first > b.first : a.second < b.second;
            };
            for (uint32_t w = pref_lo; w < pref_hi; ++w) {
                if ((int32_t)w == exact_id) continue;
                const uint16_t q = lower_logc_q_ptr_[w];
                if (nt < kTopSpan) {
                    top_q[nt++] = {q, w};
                    std::push_heap(top_q, top_q + nt, q_worse);
                } else if (q > top_q[0].first) {
                    std::pop_heap(top_q, top_q + nt, q_worse);
                    top_q[nt - 1] = {q, w};
                    std::push_heap(top_q, top_q + nt, q_worse);
                }
            }
            if (nt > 0) {
                const uint16_t q_last = top_q[0].first;
                max_unseen_pu = (luts.exp_q_hi[q_last >> 8] * luts.exp_q_lo[q_last & 0xFF] + 0.5) * inv_total;
                for (size_t i = 0; i < nt; ++i) eval_freq(top_q[i].second);
            }
        }
    }

    // Pass 3: remaining bigram continuations with exact 1-byte upper-bound pruning.
    // For any word not already evaluated in Pass 2, pu <= max_unseen_pu, so its bigram score cannot
    // reach heap.front().p unless big_logc[i] >= q_min.
    if (!all_prefix_scored && big_lo < big_hi) {
        const double inv_scale = (has_tri && tri_backoff > 0.0) ? (1.0 / (tri_backoff * big_inv_denom))
                                                                : (1.0 / big_inv_denom);
        const double max_unseen_term = big_k_eff * max_unseen_pu;
        auto compute_q_min = [&]() -> int {
            if (heap.size() < keep) return 0;
            const double c_need = heap.front().p * inv_scale - max_unseen_term;
            if (c_need <= 1.0) return 0;
            return std::max(0, (int)std::floor(16.0 * std::log(c_need)) - 1);
        };
        int q_min = compute_q_min();
        if (q_min <= 255) {
            const uint8_t* blc = big_logc_ptr_;
            for (uint32_t i = big_lo; i < big_hi; ++i) {
                if ((int)blc[i] < q_min) continue;
                const uint32_t w = big_word_ptr_[i];
                if (w >= n_lower_ || (int32_t)w == exact_id) continue;
                if (tri_lo < tri_hi && std::binary_search(tri_word_ptr_ + tri_lo, tri_word_ptr_ + tri_hi, w)) continue;
                const double prev_floor = heap.size() == keep ? heap.front().p : -1.0;
                consider_dedup(w, score_prob(w, luts.exp_u8[blc[i]], 0.0), true);
                if (heap.size() == keep && heap.front().p > prev_floor) {
                    q_min = compute_q_min();
                    if (q_min > 255) break;
                }
            }
        }
    }

    std::sort(heap.begin(), heap.end(), worse);
    out.reserve(heap.size());
    for (const auto& c : heap) out.emplace_back(c.w, (float)std::log(c.p));
    return out;
}

int KpDictionary::user_weight(std::string_view word_lower) const {
    const int32_t id = lower_id(word_lower);
    return id < 0 || (size_t)id >= user_weight_.size() ? -1 : user_weight_[id];
}

static int T_num_chars(void* s) { return ((KpDictionary*)s)->num_chars_; }
static TrieId T_root(void*) { return 0; }
// a twin node (alias key, see set_layout) behaves like its base node except for its key
static inline TrieId base_of(TrieId id) { return id & (KpDictionary::kAliasBase - 1); }
static int T_char(void* s, TrieId id) { return ((KpDictionary*)s)->key_of(id); }
static uint32_t T_cc(void* s, TrieId id) {
    auto* d = (KpDictionary*)s;
    id = base_of(id);
    return d->child_start_ptr_[id + 1] - d->child_start_ptr_[id];
}
static TrieId T_child(void* s, TrieId id, uint32_t i) {
    auto* d = (KpDictionary*)s;
    const uint32_t idx = d->child_start_ptr_[base_of(id)] + i;
    return d->child_list_ptr_ ? d->child_list_ptr_[idx] : idx;
}
static bool T_is_word(void* s, TrieId id) { return ((KpDictionary*)s)->node_term_ptr_[base_of(id)] >= 0; }
static float T_freq(void* s, TrieId id) { return (float)((KpDictionary*)s)->node_freq_ptr_[base_of(id)]; }
static uint16_t T_depth(void* s, TrieId id) { return ((KpDictionary*)s)->node_depth_ptr_[base_of(id)]; }
static const char* T_word(void* s, TrieId id) {
    auto* d = (KpDictionary*)s;
    const int32_t t = d->node_term_ptr_[base_of(id)];
    return t >= 0 ? (d->kp_pool_ptr_ + d->kp_off_ptr_[t]) : "";
}
static void T_end(void*) {}

static const ITrieVTable kVTable = {T_num_chars, T_root, T_char, T_cc, T_child, T_is_word, T_freq, T_depth, T_word, T_end};

ITrie KpDictionary::itrie() { return ITrie{this, &kVTable}; }

}  // namespace droneswipe
