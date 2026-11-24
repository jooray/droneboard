// SPDX-License-Identifier: GPL-3.0-only
// Droneboard swipe dictionary: a trie keyed on *key paths* (base letters without diacritics or
// case, e.g. "ľahko" -> "lahko"), so that diacritic and case variants of one word never compete
// for space in the beam. After the beam search, every key path is expanded back into its
// surface variants, which are re-ranked with word frequency, a capital-letter penalty and a
// previous-word (bigram/trigram) PMI term. Benchmarked offline on Czech and Slovak gesture sets.
//
// Dictionaries ship as .kpd files (tools/dicts/pack_swipe_dict.py), supporting both v1 ("KPD1")
// and v2 ("DKPD" flat zero-copy mmap). The user's own words (typing history, personal dictionary)
// form a small extra dictionary built in memory.
#pragma once

#include <sys/types.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "swipe_decoder/itrie.h"

namespace droneswipe {

struct ScoringParams {
    float gamma = 0.5f;     // length normalization exponent of the CTC score
    float beta = 0.9866f;   // per-character bonus
    float lambda = 0.025f;  // weight of the 0-255 word frequency
    float caps = 0.0f;      // penalty for words that start with a capital letter
    float nu = 0.1f;        // weight of the context term (log P(w | previous words) - log P(w))
    float bonus = 0.0f;     // per-language bonus (e.g. for the main language)
    float history = 0.0f;   // user dictionary only: bonus for any candidate the user has written, x weight/255
    float k3 = 10.0f;       // Dirichlet prior weight of the trigram estimate (towards the bigram estimate)
    // Words with the same key path ("well" / "we'll", "its" / "it's", "byt" / "byť") are the same
    // gesture, so only the language model can tell them apart: they are ordered by
    // log P(w | previous words), and each one sits same_path x its log-probability gap below the
    // best score of the key path. 0 = off (the general score decides). 0.3: tools/swipe-replay on
    // English contractions +1 pp top-1, no change on the recordings and the Czech gesture sets
    // (0.5 already costs top-3 there).
    float same_path = 0.3f;
};

// Packed on-disk & in-memory variant record (12 bytes, 4-byte aligned).
struct FlatVariant {
    uint32_t lower;      // index into lowers
    uint32_t surf_off;   // UINT32_MAX => surface equals lowers[lower]; else byte offset into var_surf_pool
    uint16_t surf_len;   // surface byte length when surf_off != UINT32_MAX
    uint8_t freq;        // 0..255 log scale
    uint8_t flags;       // bit 0 = capitalized (subject to caps penalty; excludes "I'll", "I'm", ...)
};

struct VariantView {
    std::string_view surface;
    float freq = 0.0f;
    uint32_t lower = 0;
    bool capitalized = false;
};

struct VariantList {
    const uint32_t* data = nullptr;
    uint32_t count = 0;

    const uint32_t* begin() const { return data; }
    const uint32_t* end() const { return data + count; }
    size_t size() const { return count; }
    bool empty() const { return count == 0; }
    uint32_t operator[](size_t i) const { return data[i]; }
    explicit operator bool() const { return count > 0; }
};

class KpDictionary {
public:
    KpDictionary() = default;
    ~KpDictionary();

    KpDictionary(const KpDictionary&) = delete;
    KpDictionary& operator=(const KpDictionary&) = delete;

    // A .kpd file (v1 "KPD1" or v2 "DKPD", see tools/dicts/pack_swipe_dict.py and FORMAT.md).
    bool load_kpd(const std::string& path);
    // Load a .kpd (v1 or v2) from an open file descriptor at [offset, offset + len). Does not close fd.
    bool load_kpd_fd(int fd, off_t offset, size_t len);
    // Serialize the currently loaded dictionary as a flat zero-copy mmap-able .kpd v2 ("DKPD") file.
    bool save_v2(const std::string& path) const;
    // The user's words with weights 0..255 (higher = written more often / more recently).
    void load_user(const std::vector<std::pair<std::string, int>>& words);

    // Map the trie's base letters to the indices of the given layout. Must be called before
    // the trie is used with a layout.
    void set_layout(const std::vector<int>& layout_codepoints);

    ITrie itrie();
    // Layout key of a trie node or of its twin through a key with a diacritic (see set_layout).
    int key_of(uint32_t id) const;
    static constexpr uint32_t kAliasShift = 29;
    static constexpr uint32_t kAliasBase = 1u << kAliasShift;  // node ids stay below this
    static constexpr int kMaxAliases = 7;                       // keys per base letter ("e": é è ê ...)

    // Variants of a key path (empty / falsy when absent).
    VariantList variants_of(std::string_view keypath) const;
    VariantView variant(uint32_t i) const;

    // Id of a lowercase word, or -1.
    int32_t lower_id(std::string_view word_lower) const;
    // log P(w|prev) - log P(w), unallocated-mass backoff; 0 when prev is unknown (id < 0).
    float pmi(int32_t prev, uint32_t word) const;
    // log P(w | prev2 prev) - log P(w): the trigram estimate smoothed towards the bigram estimate,
    // which backs off to the unigram. prev2 < 0: bigram only; prev < 0: 0.
    float pmi3(int32_t prev2, int32_t prev, uint32_t word) const;
    // log P(w | prev2 prev) with the same backoff (unigram when both are unknown).
    float logp_ctx(int32_t prev2, int32_t prev, uint32_t word) const;
    // Smoothed unigram log probability of a lowercase word (for telling languages apart).
    float logp(std::string_view word_lower) const;
    float logp_id(uint32_t word) const;

    // Next-word prediction and completion for tap typing: lowercase word ids whose word starts with
    // prefix_lower (any word when empty) with log P(w | prev2 prev), best first.
    std::vector<std::pair<uint32_t, float>> predict(int32_t prev2, int32_t prev, const std::string& prefix_lower,
                                                    int max_results) const;
    // Most frequent surface form of a lowercase word ("bratislava" -> "Bratislava").
    std::string_view surface_of(uint32_t lower) const;
    std::string_view lower_word(uint32_t lower) const;
    std::string_view kp_word(uint32_t kp_id) const;
    size_t trigram_count() const { return n_tri_; }
    // User dictionary: weight 0..255 of a lowercase word, or -1.
    int user_weight(std::string_view word_lower) const;

    ScoringParams params;
    size_t word_count() const { return n_variants_; }
    int format_version() const { return format_version_; }

    // ITrie callback state (raw pointers into either mmap or owned vectors)
    int num_chars_ = 0;
    uint32_t n_nodes_ = 0;
    const uint8_t* node_char_ptr_ = nullptr;
    const uint16_t* node_depth_ptr_ = nullptr;
    const int32_t* node_term_ptr_ = nullptr;
    const uint8_t* node_freq_ptr_ = nullptr;
    const uint32_t* child_start_ptr_ = nullptr;
    const uint32_t* child_list_ptr_ = nullptr;  // nullptr => BFS implicit children [child_start[n], child_start[n+1])
    const char* kp_pool_ptr_ = nullptr;
    const uint32_t* kp_off_ptr_ = nullptr;
    int32_t ascii_to_key_[256] = {};
    std::vector<int32_t> alias_keys_[26];  // per base letter a-z: layout keys of its letters with diacritics

private:
    void clear_all();
    bool load_v1_buffer(const uint8_t* base, size_t size);
    bool load_v2_buffer(const uint8_t* base, size_t size);
    void build_predict_tables();
    void bind_owned_pointers();

    double lower_count(uint32_t word) const;
    double bigram_count_in(uint32_t b_start, uint32_t b_end, uint32_t word) const;
    double p_bigram(int32_t prev, uint32_t word, double pu) const;

    // Persistent mmap state (used when loading .kpd v2)
    void* mmap_base_ = nullptr;
    size_t mmap_len_ = 0;
    int format_version_ = 0;
    bool bfs_trie_ = false;
    bool trie_has_non_az_ = false;

    // Active flat views (point into mmap_base_ for v2, or owned vectors below for v1 / user)
    uint32_t n_lower_ = 0;
    uint32_t n_variants_ = 0;
    uint32_t n_kps_ = 0;
    uint32_t n_big_ = 0;
    uint32_t n_tri_ctx_ = 0;
    uint32_t n_tri_ = 0;
    uint32_t n_top_words_ = 0;
    double total_ = 1.0;

    const uint32_t* all_child_start_ptr_ = nullptr;
    const uint32_t* all_child_list_ptr_ = nullptr;  // nullptr when bfs_trie_ is true
    const char* lower_pool_ptr_ = nullptr;
    const uint32_t* lower_off_ptr_ = nullptr;
    const uint16_t* lower_logc_q_ptr_ = nullptr;
    const uint32_t* best_variant_ptr_ = nullptr;
    const uint32_t* kp_var_start_ptr_ = nullptr;
    const uint32_t* kp_var_ids_ptr_ = nullptr;
    const FlatVariant* variants_ptr_ = nullptr;
    const char* var_surf_pool_ptr_ = nullptr;
    size_t var_surf_pool_len_ = 0;
    const uint32_t* big_start_ptr_ = nullptr;
    const uint32_t* big_word_ptr_ = nullptr;
    const uint8_t* big_logc_ptr_ = nullptr;
    const float* big_rem_ptr_ = nullptr;
    const uint8_t* tri_ctx_raw_ptr_ = nullptr;
    const uint8_t* tri_ctx_logc_ptr_ = nullptr;
    const float* tri_rem_ptr_ = nullptr;
    const uint32_t* tri_start_ptr_ = nullptr;
    const uint32_t* tri_word_ptr_ = nullptr;
    const uint8_t* tri_logc_ptr_ = nullptr;
    const uint32_t* top_words_ptr_ = nullptr;
    const uint32_t* top_by_byte_start_ptr_ = nullptr;
    const uint32_t* top_by_byte_ids_ptr_ = nullptr;
    const uint32_t* byte_range_ptr_ = nullptr;

    // Fallback aligned buffer only if load_kpd_fd receives a non-4-byte-aligned offset
    std::vector<uint64_t> aligned_v2_buf_;

    // Layout-filtered children (only populated when layout has diacritic alias keys, missing keys, or non-BFS trie)
    std::vector<uint32_t> child_start_vec_;
    std::vector<uint32_t> child_list_vec_;

    // Owned storage (populated for v1 and load_user; empty for v2!)
    std::vector<uint8_t> node_char_vec_;
    std::vector<uint16_t> node_depth_vec_;
    std::vector<int32_t> node_term_vec_;
    std::vector<uint8_t> node_freq_vec_;
    std::vector<uint32_t> all_child_start_vec_;
    std::vector<uint32_t> all_child_list_vec_;
    std::vector<char> lower_pool_vec_;
    std::vector<uint32_t> lower_off_vec_;
    std::vector<uint16_t> lower_logc_q_vec_;
    std::vector<uint32_t> best_variant_vec_;
    std::vector<char> kp_pool_vec_;
    std::vector<uint32_t> kp_off_vec_;
    std::vector<uint32_t> kp_var_start_vec_;
    std::vector<uint32_t> kp_var_ids_vec_;
    std::vector<FlatVariant> variants_vec_;
    std::vector<char> var_surf_pool_vec_;
    std::vector<uint8_t> user_weight_;  // user dictionary only
    std::vector<uint32_t> big_start_vec_;
    std::vector<uint32_t> big_word_vec_;
    std::vector<uint8_t> big_logc_vec_;
    std::vector<float> big_rem_vec_;
    std::vector<uint64_t> tri_ctx_vec_;
    std::vector<uint8_t> tri_ctx_logc_vec_;
    std::vector<float> tri_rem_vec_;
    std::vector<uint32_t> tri_start_vec_;
    std::vector<uint32_t> tri_word_vec_;
    std::vector<uint8_t> tri_logc_vec_;
    std::vector<uint32_t> top_words_vec_;
    std::vector<uint32_t> top_by_byte_start_vec_;
    std::vector<uint32_t> top_by_byte_ids_vec_;
    std::vector<uint32_t> byte_range_vec_;
};

std::string to_lower_utf8(const std::string& s);
// Key path of a word: a-z kept, Latin letters with diacritics reduced to their base letter,
// everything else dropped ("Ľahko" -> "lahko", "don't" -> "dont").
std::string key_path_of(const std::string& s);

}  // namespace droneswipe
