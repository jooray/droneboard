// SPDX-License-Identifier: GPL-3.0-only
#include "ranker.hpp"

#include <algorithm>
#include <cmath>
#include <unordered_map>

namespace droneswipe {

std::vector<float> language_bonus(const std::vector<KpDictionary*>& dicts,
                                  const std::vector<std::string>& context_lower,
                                  const std::vector<float>& prior, float kappa, float cap) {
    const size_t n = dicts.size();
    std::vector<float> bonus(n, 0.0f);
    if (n <= 1 || kappa == 0.0f) return bonus;
    std::vector<double> lp(n);
    for (size_t d = 0; d < n; ++d) {
        double p = d < prior.size() && prior[d] > 0 ? prior[d] : 1.0 / (double)n;
        lp[d] = std::log(p);
    }
    for (const auto& w : context_lower) {
        if (w.empty()) continue;
        // floor, so that one misspelled or rare context word cannot decide the language alone
        for (size_t d = 0; d < n; ++d) lp[d] += std::max(-14.0f, dicts[d]->logp(w));
    }
    double mx = *std::max_element(lp.begin(), lp.end());
    double sum = 0;
    for (double v : lp) sum += std::exp(v - mx);
    const double lse = mx + std::log(sum);
    for (size_t d = 0; d < n; ++d) bonus[d] = std::max(-cap, (float)(kappa * (lp[d] - lse)));
    return bonus;
}

std::vector<Ranked> rank_candidates(const std::vector<swipe_decoder::DecodedWord>& beam,
                                    const std::vector<KpDictionary*>& dicts,
                                    const std::vector<float>& lang_bonus,
                                    const std::vector<std::string>& context_lower, int max_results,
                                    const KpDictionary* user, float tie_margin) {
    std::unordered_map<std::string, Ranked> best;
    const size_t nc = context_lower.size();
    const std::string prev_lower = nc ? context_lower[nc - 1] : std::string();
    const std::string prev2_lower = nc > 1 ? context_lower[nc - 2] : std::string();
    std::vector<int32_t> prev_id(dicts.size(), -1), prev2_id(dicts.size(), -1);
    for (size_t di = 0; di < dicts.size(); ++di) {
        if (!prev_lower.empty()) prev_id[di] = dicts[di]->lower_id(prev_lower);
        if (!prev2_lower.empty() && prev_id[di] >= 0) prev2_id[di] = dicts[di]->lower_id(prev2_lower);
    }
    std::vector<float> scores, lps, ctxs;
    for (const auto& r : beam) {
        const float L = std::max(1.0f, r.word_length);
        for (int di = 0; di < (int)dicts.size(); ++di) {
            auto* d = dicts[di];
            const auto* vs = d->variants_of(r.word);
            if (!vs) continue;
            const auto& p = d->params;
            const float lb = di < (int)lang_bonus.size() ? lang_bonus[di] : 0.0f;
            const float base = r.ctc_score / std::pow(L, p.gamma) + p.beta * L + p.bonus + lb;
            scores.clear();
            lps.clear();
            ctxs.clear();
            float top = -1e30f, top_lp = -1e30f;
            const bool by_context = p.same_path > 0.0f && vs->size() > 1 && d != user;
            for (uint32_t vi : *vs) {
                const auto& v = d->variant(vi);
                float sc = base + p.lambda * v.freq + (v.capitalized ? p.caps : 0.0f);
                const float c = prev_id[di] >= 0 && p.nu != 0.0f ? d->pmi3(prev2_id[di], prev_id[di], v.lower) : 0.0f;
                sc += p.nu * c;
                ctxs.push_back(c);
                scores.push_back(sc);
                top = std::max(top, sc);
                if (by_context) {
                    lps.push_back(d->logp_ctx(prev2_id[di], prev_id[di], v.lower));
                    top_lp = std::max(top_lp, lps.back());
                }
            }
            for (size_t k = 0; k < vs->size(); ++k) {
                const auto& v = d->variant((*vs)[k]);
                // same gesture: the context decides ("we'll" after "so", "well" after "as"); the
                // general score only breaks ties (capitalization of the same word)
                float sc = by_context ? top - p.same_path * (top_lp - lps[k]) + 1e-3f * (scores[k] - top) : scores[k];
                auto it = best.find(v.surface);
                if (it == best.end() || sc > it->second.score)
                    best[v.surface] = {v.surface, sc, di, r.ctc_score, L, v.freq, ctxs[k]};
            }
        }
    }
    // one entry per lowercase form: "hello" and sentence-initial "Hello" are the same word for the
    // user (capitalization follows the shift state), and duplicates waste suggestion slots
    std::unordered_map<std::string, Ranked> by_lower;
    for (auto& kv : best) {
        std::string lower = to_lower_utf8(kv.first);
        auto it = by_lower.find(lower);
        if (it == by_lower.end() || kv.second.score > it->second.score) by_lower[lower] = kv.second;
    }
    std::vector<Ranked> out;
    out.reserve(by_lower.size());
    for (auto& kv : by_lower) {
        // words the user has written before get a bonus, whichever dictionary proposed them
        if (user && user->params.history != 0.0f) {
            int w = user->user_weight(kv.first);
            if (w > 0) kv.second.score += user->params.history * (float)w / 255.0f;
        }
        out.push_back(kv.second);
    }
    std::sort(out.begin(), out.end(), [](const Ranked& a, const Ranked& b) {
        return a.score != b.score ? a.score > b.score : a.word < b.word;
    });
    if (tie_margin > 0.0f && out.size() > 1 && !prev_lower.empty()) {
        const int lang = previous_language(dicts, prev_lower, user);
        auto in_lang = [&](const Ranked& r) { return lang >= 0 && dicts[lang]->lower_id(to_lower_utf8(r.word)) >= 0; };
        if (lang >= 0 && !in_lang(out[0])) {
            for (size_t i = 1; i < out.size() && out[i].score >= out[0].score - tie_margin; ++i) {
                if (!in_lang(out[i])) continue;
                Ranked r = out[i];
                out.erase(out.begin() + (long)i);
                out.insert(out.begin(), r);
                break;
            }
        }
    }
    if ((int)out.size() > max_results) out.resize(max_results);
    return out;
}

int previous_language(const std::vector<KpDictionary*>& dicts, const std::string& word_lower,
                      const KpDictionary* user, float min_gap) {
    int best = -1;
    float best_lp = -1e30f, second = -1e30f;
    for (int d = 0; d < (int)dicts.size(); ++d) {
        if (dicts[d] == user) continue;
        // a word the dictionary does not know counts as very unlikely there
        const float lp = dicts[d]->lower_id(word_lower) >= 0 ? dicts[d]->logp(word_lower) : -1e29f;
        if (lp > best_lp) { second = best_lp; best_lp = lp; best = d; }
        else if (lp > second) second = lp;
    }
    return best >= 0 && best_lp > -1e28f && best_lp - second >= min_gap ? best : -1;
}

std::vector<Prediction> predict_words(const std::vector<KpDictionary*>& dicts,
                                      const std::vector<std::string>& context_lower,
                                      const std::string& prefix_lower, int max_results) {
    std::vector<Prediction> out;
    if (dicts.empty() || max_results <= 0) return out;
    // language posterior from the context (uniform prior; the same unigram evidence as language_bonus)
    auto lb = language_bonus(dicts, context_lower, {}, 1.0f, 30.0f);
    const size_t nc = context_lower.size();
    std::unordered_map<std::string, Prediction> best;
    for (int d = 0; d < (int)dicts.size(); ++d) {
        auto* dict = dicts[d];
        const int32_t prev = nc ? dict->lower_id(context_lower[nc - 1]) : -1;
        const int32_t prev2 = nc > 1 && prev >= 0 ? dict->lower_id(context_lower[nc - 2]) : -1;
        for (auto& [id, lp] : dict->predict(prev2, prev, prefix_lower, max_results * 2)) {
            const float sc = lp + (d < (int)lb.size() ? lb[d] : 0.0f);
            const std::string& low = dict->lower_word(id);
            auto it = best.find(low);
            if (it == best.end() || sc > it->second.score) best[low] = {dict->surface_of(id), sc, d};
        }
    }
    out.reserve(best.size());
    for (auto& kv : best) out.push_back(kv.second);
    std::sort(out.begin(), out.end(), [](const Prediction& a, const Prediction& b) {
        return a.score != b.score ? a.score > b.score : a.word < b.word;
    });
    if ((int)out.size() > max_results) out.resize(max_results);
    return out;
}

}  // namespace droneswipe
