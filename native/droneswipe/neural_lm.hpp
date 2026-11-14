// SPDX-License-Identifier: GPL-3.0-only
// Small neural next-word language model for tap typing (one per language), trained with
// tools/lm and exported by tools/lm/export_keyboard_lm.py:
//   <lang>.lmb  the transformer body (int8 weights with a scale per output row): embedding [E] ->
//               up (E->d) + learned positions -> pre-LN blocks -> LayerNorm -> down (d->E)
//   <lang>.lmv  the word list and the tied int8 embedding table, used to embed the context and to
//               score every word of the vocabulary (dot product, softmax)
// Plain C++ without ExecuTorch/XNNPACK (their KleidiAI int8 packing crashed under hardened_malloc).
// A key/value cache keeps the last window, so typing one more word computes one new position.
// Several languages are combined with a language posterior over the words before the cursor
// (predict_neural): after an English word the next one is probably English, and a word both
// languages share leaves the decision to the words before it.
#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace droneswipe {

class KpDictionary;

class NeuralLm {
public:
    ~NeuralLm();
    // nullptr on failure (the reason goes to *error)
    static std::unique_ptr<NeuralLm> load(const std::string& lmb_path, const std::string& lmv_path, std::string* error);

    int vocab() const { return V_; }
    int dim() const { return E_; }
    int ctx() const { return ctx_; }
    // id of a normalized token (lowercase word, "<num>", punctuation), 1 (<unk>) when unknown
    int32_t id_of(const std::string& token) const;
    const std::string& token(int32_t id) const { return tokens_[id]; }
    // a word the keyboard may suggest (letters and apostrophes, not <unk>, punctuation or <num>)
    bool is_word(int32_t id) const { return word_[id] != 0; }
    // the token's key path (no case, no diacritics), for accent-insensitive completion
    const std::string& key_path(int32_t id) const { return key_paths_[id]; }

    // The hidden state (dim() floats) that predicts the token after ids, oldest first (the caller adds
    // <bos> at the start of a text). Reuses the key/value cache from the longest part of the cached
    // window that ids continue (cutting it back if they diverge); when nothing fits, the window restarts
    // with the last ids (all of them if they fit and start with <bos>, otherwise the last kRestart).
    // Caller holds mutex().
    const float* hidden(const std::vector<int32_t>& ids);
    // logits[V] for hidden z (unnormalized; log P = logit - logsumexp)
    void logits(const float* z, float* out) const;
    // logits of the token after ids and their logsumexp; the last result is cached, so typing the
    // letters of one word runs the model once. Caller holds mutex().
    const float* distribution(const std::vector<int32_t>& ids, float* lse);
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
    std::vector<std::string> tokens_;
    std::vector<std::string> key_paths_;
    std::vector<uint8_t> word_;
    std::unordered_map<std::string, int32_t> index_;
    const float* scale_ = nullptr;
    const int8_t* table_ = nullptr;
    void* map_ = nullptr;
    size_t map_size_ = 0;
    std::vector<int32_t> window_;       // ids in the key/value cache
    std::vector<float> z_;              // hidden state after window_
    std::vector<float> zs_;             // hidden state after each position of window_ [ctx][E]
    std::vector<int32_t> cached_ids_;   // ids of cached_logits_
    std::vector<float> cached_logits_;
    float cached_lse_ = 0;
    std::mutex mutex_;
    void step(int32_t id);              // appends one token at position window_.size()
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

// Language posterior over the context tokens (normalized, oldest first; may include punctuation):
// a sticky HMM (switch probability `switch_p` per word) whose emission is each language's unigram
// probability of the word, estimated from the vocabulary rank (Zipf). Returns log posteriors.
std::vector<float> neural_language_posterior(const std::vector<NeuralLanguageInput>& langs,
                                             const std::vector<std::string>& context, float switch_p);

// Next-word prediction (empty prefix) or completion of prefix_lower. The prefix matches either
// the word's beginning or, when the prefix has no diacritics, its key path ("dak" -> "ďakujem").
// bos: the context starts at the beginning of the text or sentence boundary the caller knows of.
std::vector<NeuralPrediction> predict_neural(const std::vector<NeuralLanguageInput>& langs,
                                             const std::vector<std::string>& context, bool bos,
                                             const std::string& prefix_lower, int max_results,
                                             float switch_p = 0.05f);

}  // namespace droneswipe
