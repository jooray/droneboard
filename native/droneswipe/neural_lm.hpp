// SPDX-License-Identifier: GPL-3.0-only
// Small neural next-word language model for tap typing (one per language), trained with
// tools/lm and exported by tools/lm/export_keyboard_lm.py:
//   <lang>.lmb  the model body (v1 TinyGPT or v2 StripedLM: 2x GatedCausalConv1D + 2x RoPE-GQA + SwiGLU)
//   <lang>.lmv  the word list and tied embedding table (v1 INT8, or v2 mixed INT8 + INT4-g32 +
//               optional vocab bias)
// Plain C++ without ExecuTorch/XNNPACK (their KleidiAI int8 packing crashed under hardened_malloc).
// A key/value cache (v1 window, v2 RoPE ring buffer with zero restarts) keeps context history, so
// typing one more word computes one new position.
#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace droneswipe {

class KpDictionary;
struct Ranked;

class NeuralLm {
public:
    ~NeuralLm();
    // nullptr on failure (the reason goes to *error)
    static std::unique_ptr<NeuralLm> load(const std::string& lmb_path, const std::string& lmv_path, std::string* error);
    // Load from open file descriptors at [off, off + len) using page-aligned mmap. Does not close fds.
    static std::unique_ptr<NeuralLm> load_fd(int lmb_fd, int64_t lmb_off, int64_t lmb_len,
                                             int lmv_fd, int64_t lmv_off, int64_t lmv_len,
                                             std::string* error);

    int vocab() const { return V_; }
    int dim() const { return E_; }
    int ctx() const { return ctx_; }
    int version() const { return lmv_version_; }
    int int8_rows() const { return n_int8_; }

    // id of a normalized token (lowercase word, "<num>", punctuation), 1 (<unk>) when unknown
    int32_t id_of(const std::string& token) const;
    const std::string& token(int32_t id) const { return tokens_[id]; }
    // a word the keyboard may suggest (letters and apostrophes, not <unk>, punctuation or <num>)
    bool is_word(int32_t id) const { return word_[id] != 0; }
    // the token's key path (no case, no diacritics), for accent-insensitive completion
    const std::string& key_path(int32_t id) const { return key_paths_[id]; }

    // The hidden state (dim() floats) that predicts the token after ids, oldest first (the caller adds
    // <bos> at the start of a text). Reuses the KV cache (v1: longest prefix match; v2: shift-invariant
    // RoPE ring buffer with zero restarts on streaming text). Caller holds mutex().
    const float* hidden(const std::vector<int32_t>& ids);
    // logits[V] for hidden z (unnormalized; log P = logit - logsumexp; full vocab for v1 and v2)
    void logits(const float* z, float* out) const;
    // logits of the token after ids and their logsumexp; the last result is cached. Caller holds mutex().
    const float* distribution(const std::vector<int32_t>& ids, float* lse);
    // Warm KV cache and cached exact logits/logsumexp for upcoming inference.
    // Caller holds mutex().
    void prefetch(const std::vector<int32_t>& ids);

    // Top-k word ids matching prefix_lower (using sorted prefix index and exact logsumexp).
    // Returns pairs (log_prob, word_id) sorted descending by log_prob.
    // Caller holds mutex().
    std::vector<std::pair<float, int32_t>> top_words(const std::vector<int32_t>& ids,
                                                     const std::string& prefix_lower,
                                                     const std::string& prefix_kp,
                                                     bool fold,
                                                     size_t keep);

    // ids currently in the key/value cache (for tests)
    const std::vector<int32_t>& window() const { return window_; }

    std::mutex& mutex() { return mutex_; }

    static constexpr int kRestart = 24;
    // statistics: model steps run, window restarts, calls answered from the cache
    long steps = 0, restarts = 0, cached = 0;

private:
    NeuralLm() = default;
    struct Body;
    std::unique_ptr<Body> body_;
    int V_ = 0, E_ = 0, ctx_ = 0;
    int lmv_version_ = 1;
    int n_int8_ = 0, n_int4_ = 0, group_size_ = 32;
    bool has_bias_ = false;

    std::vector<std::string> tokens_;
    std::vector<std::string> key_paths_;
    std::vector<uint8_t> word_;
    std::unordered_map<std::string, int32_t> index_;
    // Sorted word-id indices for O(log V + M) prefix completions (key_path folded + exact token)
    std::vector<int32_t> kp_sorted_;
    std::vector<int32_t> tok_sorted_;

    const float* out_bias_ = nullptr;
    const float* scale_i8_ = nullptr;
    const int8_t* table_i8_ = nullptr;
    const uint16_t* scale_i4_ = nullptr;
    const uint8_t* table_i4_ = nullptr;
    void* map_ = nullptr;
    size_t map_size_ = 0;

    std::vector<int32_t> window_;       // ids currently in the KV/conv cache
    int64_t start_pos_ = 0;             // absolute position of window_[0] (always 0 for v1; slides in v2)
    std::vector<float> z_;              // hidden state after window_
    std::vector<float> zs_;             // ring of hidden states [ctx][E] indexed by (abs_pos % ctx)
    std::vector<int32_t> cached_ids_;   // ids of cached_logits_
    std::vector<float> cached_logits_;
    float cached_lse_ = 0;
    std::mutex mutex_;

    void step_v1(int32_t id);
    void step_v2(int32_t id);
    void step(int32_t id);
    float logits_with_max(const float* z, float* out) const;
    void dequantize_emb(int32_t id, float* out) const;
    void build_prefix_indices();
    void prefix_range(const std::string& prefix_lower, const std::string& prefix_kp, bool fold,
                      const int32_t*& out_begin, const int32_t*& out_end) const;
};

// "sdot", "neon" or "scalar": the int8 dot product kernel picked for this CPU
const char* neural_kernel_name();

struct NeuralPrediction {
    std::string word;  // surface form (from the language's dictionary when given)
    float score;       // log P(w | context, language) + log P(language | context)
    int lang;          // index into the models
};

struct NeuralLanguageInput {
    NeuralLm* model;
    const KpDictionary* dict;  // optional: only words it knows are suggested, with its capitalization
};

// Warm KV cache and exact logits/logsumexp across all models in parallel.
void prefetch_neural(const std::vector<NeuralLm*>& models,
                     const std::vector<std::string>& context, bool bos);

// Language posterior over the context tokens (normalized, oldest first; may include punctuation):
// a sticky HMM (switch probability `switch_p` per word) whose emission is each language's unigram
// probability of the word, falling back to KpDictionary when OOV in the 50k LM. Returns log posteriors.
std::vector<float> neural_language_posterior(const std::vector<NeuralLanguageInput>& langs,
                                             const std::vector<std::string>& context, float switch_p);

// Next-word prediction (empty prefix) or completion of prefix_lower. The prefix matches either
// the word's beginning or, when the prefix has no diacritics, its key path ("dak" -> "ďakujem").
// bos: the context starts at the beginning of the text or sentence boundary the caller knows of.
std::vector<NeuralPrediction> predict_neural(const std::vector<NeuralLanguageInput>& langs,
                                             const std::vector<std::string>& context, bool bos,
                                             const std::string& prefix_lower, int max_results,
                                             float switch_p = 0.05f);

// log P(w | context) of each lowercase word under the languages' models mixed by the language
// posterior (the probability predict_neural ranks by); -INFINITY when no model has the word.
// unk_logp (optional): log P(<unk> | context), the mass of all words outside the vocabularies.
std::vector<float> neural_word_logp(const std::vector<NeuralLanguageInput>& langs,
                                    const std::vector<std::string>& context, bool bos,
                                    const std::vector<std::string>& words_lower, float switch_p = 0.05f,
                                    float* unk_logp = nullptr);

struct NeuralFusion {
    float weight = 0.2f;
    float weight_max = 0.4f;
    float margin_scale = 4.0f;
    float weight_no_context = 0.1f;
    float oov = 3.0f;
    float lambda_delta = -0.015f;
    float nu_delta = -0.05f;
};

// Rescores and re-sorts candidates of rank_candidates (with their score parts).
void fuse_neural(std::vector<Ranked>& ranked, const std::vector<KpDictionary*>& dicts, const KpDictionary* user,
                 const std::vector<NeuralLanguageInput>& langs, const std::vector<std::string>& tokens, bool bos,
                 const NeuralFusion& f);

}  // namespace droneswipe
