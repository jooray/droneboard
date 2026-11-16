// SPDX-License-Identifier: GPL-3.0-only
// Evaluates next-word prediction and word completion of Droneboard's language models
// (native/droneswipe: the n-gram predict_words and the neural predict_neural) on held-out text.
//
// usage: lm-eval --dict sk:sk.kpd [--dict en:en.kpd] [--k3 10] [--context 3]
//                [--neural sk:dir --neural en:dir] [--mode ngram|neural|interp] [--lambda 0.7]
//                [--tokens] in.jsonl...
//
// Input: the swipe-replay evaluation format (only "target", "context" and "lang" are used), so the
// same held-out sentences serve both. For every item it predicts the target from the words before
// it with no letters typed, and after typing its first one, two and three letters. Keystroke
// savings (KSS) count the letters not typed thanks to the earliest top-3 hit, over letters + spaces.
//
// --neural lang:dir loads dir/<lang>.lm.pte and dir/<lang>.lmv (tools/lm/export_keyboard_lm.py) next
// to the dictionary of the same language. --mode neural ranks with the neural model (words its
// dictionary knows), interp mixes P = lambda P_neural + (1 - lambda) P_ngram. --tokens gives the
// neural model the item's "tokens" (the text before the target, punctuation included, with "bos")
// instead of the last --context words. tools/lm/make_lm_eval_set.py writes such items.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "kp_dictionary.hpp"
#include "neural_lm.hpp"
#include "ranker.hpp"
#include "swipe_decoder/layout.hpp"

using json = nlohmann::json;

namespace {

std::string first_chars(const std::string& w, size_t n) {
    auto cps = swipe_decoder::utf8_to_codepoints(w);
    std::string out;
    for (size_t i = 0; i < cps.size() && i < n; ++i) {
        int cp = cps[i];
        if (cp < 0x80) out += (char)cp;
        else if (cp < 0x800) { out += (char)(0xC0 | (cp >> 6)); out += (char)(0x80 | (cp & 0x3F)); }
        else { out += (char)(0xE0 | (cp >> 12)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
    }
    return out;
}

struct Stats {
    int n = 0;
    int hit[4][2] = {{0, 0}, {0, 0}, {0, 0}, {0, 0}};  // [letters typed 0..3][top1, top3]
    long chars = 0, saved = 0;
    void print(const char* name) const {
        if (!n) return;
        auto pct = [&](int k) { return 100.0 * k / n; };
        std::printf("%-9s n=%5d  next word %5.1f / %5.1f   after 1 letter %5.1f / %5.1f   after 2 letters %5.1f / %5.1f   KSS %5.1f\n",
                    name, n, pct(hit[0][0]), pct(hit[0][1]), pct(hit[1][0]), pct(hit[1][1]), pct(hit[2][0]), pct(hit[2][1]),
                    100.0 * saved / std::max(chars, 1L));
    }
};

}  // namespace

int main(int argc, char** argv) {
    std::vector<std::unique_ptr<droneswipe::KpDictionary>> dicts;
    std::vector<std::string> dict_langs;
    std::vector<std::pair<std::string, std::string>> neural_specs;
    std::vector<std::string> inputs;
    std::string mode = "ngram";
    float k3 = -1.0f, lambda = 0.7f;
    int context_n = 3;
    bool use_tokens = false;
    std::string dump_path;  // --dump: the app's model candidates for every prefix, for the end-to-end eval
    int max_typed = 3;
    bool fold_prefix = false;  // --fold: the letters are typed without accents (their key path)
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() { return i + 1 < argc ? std::string(argv[++i]) : std::string(); };
        if (a == "--dict") {
            auto s = next();
            auto d = std::make_unique<droneswipe::KpDictionary>();
            if (!d->load_kpd(s.substr(s.find(':') + 1))) { std::fprintf(stderr, "cannot load %s\n", s.c_str()); return 1; }
            dicts.push_back(std::move(d));
            dict_langs.push_back(s.substr(0, s.find(':')));
        } else if (a == "--k3") k3 = std::stof(next());
        else if (a == "--context") context_n = std::stoi(next());
        else if (a == "--neural") { auto s = next(); neural_specs.push_back({s.substr(0, s.find(':')), s.substr(s.find(':') + 1)}); }
        else if (a == "--mode") mode = next();
        else if (a == "--lambda") lambda = std::stof(next());
        else if (a == "--tokens") use_tokens = true;
        else if (a == "--dump") dump_path = next();
        else if (a == "--max-typed") max_typed = std::stoi(next());
        else if (a == "--fold") fold_prefix = true;
        else inputs.push_back(a);
    }
    if (dicts.empty() || inputs.empty()) {
        std::fprintf(stderr, "usage: lm-eval --dict lang:dict.kpd ... in.jsonl\n");
        return 2;
    }
    std::vector<droneswipe::KpDictionary*> raw;
    for (auto& d : dicts) {
        if (k3 >= 0) d->params.k3 = k3;
        raw.push_back(d.get());
    }
    // neural models in the dictionaries' order
    std::vector<std::unique_ptr<droneswipe::NeuralLm>> neural;
    std::vector<droneswipe::NeuralLanguageInput> nin;
    for (size_t i = 0; i < dict_langs.size(); ++i) {
        for (auto& [l, dir] : neural_specs) {
            if (l != dict_langs[i]) continue;
            std::string err;
            auto t0 = std::chrono::steady_clock::now();
            auto m = droneswipe::NeuralLm::load(dir + "/" + l + ".lmb", dir + "/" + l + ".lmv", &err);
            if (!m) { std::fprintf(stderr, "neural %s: %s\n", l.c_str(), err.c_str()); return 1; }
            std::fprintf(stderr, "neural %s loaded in %.0f ms\n", l.c_str(),
                         std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
            nin.push_back({m.get(), raw[i]});
            neural.push_back(std::move(m));
        }
    }
    if (mode != "ngram" && nin.size() != raw.size()) {
        std::fprintf(stderr, "--mode %s needs --neural for every --dict\n", mode.c_str());
        return 2;
    }
    std::map<std::string, Stats> by_lang;
    Stats all;
    double ms = 0;
    long calls = 0;
    std::vector<double> next_ms, completion_ms;  // per call: no letters typed (new context) / letters typed
    std::ofstream dump;
    if (!dump_path.empty()) dump.open(dump_path);
    const int n_out = dump_path.empty() ? 3 : 8;  // the app asks the model for 8 words (DictionaryFacilitatorImpl)
    const int n_typed = dump_path.empty() ? 4 : max_typed + 1;
    for (auto& path : inputs) {
        std::ifstream in(path);
        std::string line;
        while (std::getline(in, line)) {
            if (line.empty()) continue;
            json j = json::parse(line);
            std::vector<std::string> ctx;
            if (j.contains("context")) for (auto& w : j["context"]) ctx.push_back(droneswipe::to_lower_utf8(w));
            if ((int)ctx.size() > context_n) ctx.erase(ctx.begin(), ctx.end() - context_n);
            std::vector<std::string> toks = ctx;
            bool bos = false;
            if (use_tokens && j.contains("tokens")) {
                toks.clear();
                for (auto& w : j["tokens"]) toks.push_back(w);
                bos = j.value("bos", false);
            }
            const std::string target = droneswipe::to_lower_utf8(j["target"]);
            const std::string lang = j.contains("lang") ? std::string(j["lang"]) : std::string("all");
            const int target_len = (int)swipe_decoder::utf8_to_codepoints(target).size();
            for (Stats* s : {&all, &by_lang[lang]}) { s->n++; s->chars += target_len + 1; }
            int saved = -1;
            json dump_item = json::array();
            for (int typed = 0; typed < n_typed; ++typed) {
                std::string prefix = first_chars(target, (size_t)typed);
                if (fold_prefix) prefix = droneswipe::key_path_of(prefix);  // typed without accents
                if (typed > 0 && prefix == target) {  // the word is typed, not predicted
                    if (typed < 4) for (Stats* s : {&all, &by_lang[lang]}) { s->hit[typed][0]++; s->hit[typed][1]++; }
                    if (!dump_path.empty()) break;
                    continue;
                }
                auto t0 = std::chrono::steady_clock::now();
                std::vector<droneswipe::Prediction> pred;
                if (mode == "ngram") {
                    pred = droneswipe::predict_words(raw, ctx, prefix, 3);
                } else if (mode == "neural") {
                    for (auto& p : droneswipe::predict_neural(nin, toks, bos, prefix, 3)) pred.push_back({p.word, p.score, p.lang});
                } else {
                    // P = lambda P_neural + (1 - lambda) P_ngram over the union of their candidates
                    std::map<std::string, std::pair<double, double>> pr;
                    std::map<std::string, droneswipe::Prediction> info;
                    for (auto& p : droneswipe::predict_words(raw, ctx, prefix, 20)) {
                        auto key = droneswipe::to_lower_utf8(p.word);
                        pr[key].second += std::exp((double)p.score);
                        info.emplace(key, p);
                    }
                    for (auto& p : droneswipe::predict_neural(nin, toks, bos, prefix, 20)) {
                        auto key = droneswipe::to_lower_utf8(p.word);
                        pr[key].first += std::exp((double)p.score);
                        info.emplace(key, droneswipe::Prediction{p.word, p.score, p.lang});
                    }
                    for (auto& [k, v] : pr) {
                        auto p = info[k];
                        p.score = (float)std::log(lambda * v.first + (1 - lambda) * v.second + 1e-30);
                        pred.push_back(p);
                    }
                    std::sort(pred.begin(), pred.end(), [](const auto& a, const auto& b) { return a.score > b.score; });
                    if ((int)pred.size() > n_out) pred.resize(n_out);
                }
                if (!dump_path.empty()) {
                    json row = json::array();
                    for (auto& p : pred) row.push_back({p.word, p.score, dict_langs[p.dict]});
                    dump_item.push_back({{"t", prefix}, {"p", row}});
                    if (pred.size() > 3) pred.resize(3);
                }
                const double dt = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
                ms += dt;
                calls++;
                (typed == 0 ? next_ms : completion_ms).push_back(dt);
                int rank = -1;
                for (int r = 0; r < (int)pred.size(); ++r)
                    if (droneswipe::to_lower_utf8(pred[r].word) == target) { rank = r; break; }
                if (typed < 4) for (Stats* s : {&all, &by_lang[lang]}) {
                    if (rank == 0) s->hit[typed][0]++;
                    if (rank >= 0) s->hit[typed][1]++;
                }
                if (rank >= 0 && saved < 0) saved = target_len - typed;
            }
            for (Stats* s : {&all, &by_lang[lang]}) s->saved += std::max(saved, 0);
            if (dump.is_open()) dump << dump_item.dump() << "\n";
        }
    }
    for (auto& [l, s] : by_lang) if (l != "all") s.print(l.c_str());
    all.print("ALL");
    if (calls) std::printf("%.2f ms per prediction\n", ms / calls);
    for (auto* v : {&next_ms, &completion_ms}) {
        if (v->empty()) continue;
        std::sort(v->begin(), v->end());
        std::printf("%s: median %.2f ms, p95 %.2f ms, max %.2f ms\n", v == &next_ms ? "next word" : "completion",
                    (*v)[v->size() / 2], (*v)[v->size() * 95 / 100], v->back());
    }
    return 0;
}
