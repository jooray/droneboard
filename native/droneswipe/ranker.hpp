// SPDX-License-Identifier: GPL-3.0-only
// Final ranking of FUTO Swipe beam results against Droneboard's key-path dictionaries.
// Shared by the JNI library and the desktop replay tool (tools/swipe-replay), so that
// offline tuning measures exactly what the keyboard does.
#pragma once

#include <string>
#include <vector>

#include "kp_dictionary.hpp"
#include "swipe_decoder/beam_search.hpp"

namespace droneswipe {

struct Ranked {
    std::string word;   // surface form
    float score;
    int dict;           // index into the dictionaries
};

// Per-language score offsets (<= 0) from the words before the cursor and a prior per language
// (e.g. how often the user wrote in it lately). Each dictionary acts as a unigram language model;
// the bonus is kappa * log P(language | context), floored at -cap.
std::vector<float> language_bonus(const std::vector<KpDictionary*>& dicts,
                                  const std::vector<std::string>& context_lower,
                                  const std::vector<float>& prior, float kappa, float cap);

// Expands every beam key path into the surface variants of every dictionary, scores them and
// returns at most max_results words, best first, one per lowercase form. context_lower: the words
// before the cursor, oldest first; the last two feed the trigram/bigram term (nu x pmi3). With a
// user dictionary (typing history, personal dictionary; it may also be one of dicts, so that the
// user's own words are searched too), every candidate the user has written gets
// user->params.history x weight/255.
// tie_margin > 0: language continuity as a tie-breaker. When the previous word clearly belongs to
// one language (see previous_language) and the best candidate is not a word of that language, a
// candidate of that language within tie_margin of the best one moves to the top ("kedy" over "left"
// after a Slovak word). It never decides between clearly different scores.
std::vector<Ranked> rank_candidates(const std::vector<swipe_decoder::DecodedWord>& beam,
                                    const std::vector<KpDictionary*>& dicts,
                                    const std::vector<float>& lang_bonus,
                                    const std::vector<std::string>& context_lower, int max_results,
                                    const KpDictionary* user = nullptr, float tie_margin = 0.0f);

// Index of the dictionary the word clearly belongs to: its unigram log probability there is at
// least min_gap above every other dictionary (user dictionaries are ignored); -1 if none.
int previous_language(const std::vector<KpDictionary*>& dicts, const std::string& word_lower,
                      const KpDictionary* user, float min_gap = 2.0f);

struct Prediction {
    std::string word;   // most frequent surface form
    float score;        // log P(language | context) + log P(word | context) in that language
    int dict;
};

// Next-word prediction / completion of prefix_lower over the given languages (tap typing). The
// language posterior from the context words keeps predictions in the language being written,
// but every given language can contribute.
std::vector<Prediction> predict_words(const std::vector<KpDictionary*>& dicts,
                                      const std::vector<std::string>& context_lower,
                                      const std::string& prefix_lower, int max_results);

}  // namespace droneswipe
