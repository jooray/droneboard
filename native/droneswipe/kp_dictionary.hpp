// SPDX-License-Identifier: GPL-3.0-only
// Droneboard swipe dictionary: a trie keyed on *key paths* (base letters without diacritics or
// case, e.g. "ľahko" -> "lahko"), so that diacritic and case variants of one word never compete
// for space in the beam. After the beam search, every key path is expanded back into its
// surface variants, which are re-ranked with word frequency, a capital-letter penalty and a
// previous-word (bigram) PMI term. Benchmarked offline on Czech and Slovak gesture sets.
//
// Dictionaries ship as .kpd files (tools/dicts/pack_swipe_dict.py), read with mmap. The user's
// own words (typing history, personal dictionary) form a small extra dictionary built in memory.
#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
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

struct Variant {
    std::string surface;
    float freq;          // 0..255 log scale
    uint32_t lower;      // id of the lowercase form
    // starts with a capital letter (gets the capital-letter penalty); not for the pronoun I and its
    // contractions ("I'll", "I'm"), which are always written that way
    bool capitalized;
};

class KpDictionary {
public:
    // A .kpd file (see tools/dicts/pack_swipe_dict.py).
    bool load_kpd(const std::string& path);
    // The user's words with weights 0..255 (higher = written more often / more recently).
    void load_user(const std::vector<std::pair<std::string, int>>& words);

    // Map the trie's base letters to the indices of the given layout. Must be called before
    // the trie is used with a layout.
    void set_layout(const std::vector<int>& layout_codepoints);

    ITrie itrie();

    // Variants of a key path, or nullptr.
    const std::vector<uint32_t>* variants_of(const std::string& keypath) const;
    const Variant& variant(uint32_t i) const { return variants_[i]; }

    // Id of a lowercase word, or -1.
    int32_t lower_id(const std::string& word_lower) const;
    // log P(w|prev) - log P(w), Dirichlet-smoothed (K=50); 0 when prev is unknown (id < 0).
    float pmi(int32_t prev, uint32_t word) const;
    // log P(w | prev2 prev) - log P(w): the trigram estimate, Dirichlet-smoothed (params.k3) towards
    // the bigram estimate, which backs off to the unigram. prev2 < 0: bigram only; prev < 0: 0.
    float pmi3(int32_t prev2, int32_t prev, uint32_t word) const;
    // log P(w | prev2 prev) with the same backoff (unigram when both are unknown).
    float logp_ctx(int32_t prev2, int32_t prev, uint32_t word) const;
    // Smoothed unigram log probability of a lowercase word (for telling languages apart).
    float logp(const std::string& word_lower) const;
    float logp_id(uint32_t word) const;

    // Next-word prediction and completion for tap typing: lowercase word ids whose word starts with
    // prefix_lower (any word when empty) with log P(w | prev2 prev), best first. Candidates are the
    // trigram and bigram continuations of the context plus the most frequent words (with the prefix).
    std::vector<std::pair<uint32_t, float>> predict(int32_t prev2, int32_t prev, const std::string& prefix_lower,
                                                    int max_results) const;
    // Most frequent surface form of a lowercase word ("bratislava" -> "Bratislava").
    const std::string& surface_of(uint32_t lower) const;
    const std::string& lower_word(uint32_t lower) const { return lowers_[lower]; }
    size_t trigram_count() const { return tri_word_.size(); }
    // User dictionary: weight 0..255 of a lowercase word, or -1.
    int user_weight(const std::string& word_lower) const;

    ScoringParams params;
    size_t word_count() const { return variants_.size(); }

    // ITrie callbacks
    int num_chars_ = 0;
    std::vector<int32_t> node_cp_;          // base letter code point per node
    std::vector<int32_t> node_key_;         // layout key index per node (-1 = not on layout)
    std::vector<uint16_t> node_depth_;
    std::vector<int32_t> node_term_;        // index into kps_ or -1
    std::vector<float> node_freq_;          // max variant frequency for terminal nodes
    std::vector<uint32_t> child_start_;     // CSR over valid children (rebuilt by set_layout)
    std::vector<uint32_t> child_list_;
    std::vector<std::string> kps_;

private:
    void add_variant(const std::string& surface, const std::string& keypath, float freq, uint32_t lower,
                     std::vector<std::vector<uint32_t>>& children, void* edges, void* kp_index);
    void begin_build();
    void finish_build(std::vector<std::vector<uint32_t>>& children);

    std::vector<uint32_t> all_child_start_; // CSR over all children
    std::vector<uint32_t> all_child_list_;
    std::vector<Variant> variants_;
    std::unordered_map<std::string, std::vector<uint32_t>> kp_variants_;
    std::vector<std::string> lowers_;
    std::unordered_map<std::string, uint32_t> lower_index_;
    std::vector<float> lower_logc_;         // ln(count) per lowercase word
    std::vector<uint8_t> user_weight_;      // user dictionary only
    std::vector<uint32_t> big_start_;       // CSR by previous word id
    std::vector<uint32_t> big_word_;
    std::vector<uint8_t> big_logc_;         // round(ln(count) * 16)
    // trigrams: contexts (prev2 << 32 | prev) sorted, CSR over continuations
    std::vector<uint64_t> tri_ctx_;
    std::vector<uint8_t> tri_ctx_logc_;     // round(ln(count(prev2 prev)) * 16)
    std::vector<uint32_t> tri_start_;
    std::vector<uint32_t> tri_word_;
    std::vector<uint8_t> tri_logc_;
    std::vector<uint32_t> best_variant_;    // per lowercase word: index of its most frequent variant
    std::vector<uint32_t> top_words_;       // most frequent lowercase words, for prediction
    double total_ = 1.0;

    float bigram_count(int32_t prev, uint32_t word) const;
    double p_bigram(int32_t prev, uint32_t word, double pu) const;
};

std::string to_lower_utf8(const std::string& s);
// Key path of a word: a-z kept, Latin letters with diacritics reduced to their base letter,
// everything else dropped ("Ľahko" -> "lahko", "don't" -> "dont").
std::string key_path_of(const std::string& s);

}  // namespace droneswipe
