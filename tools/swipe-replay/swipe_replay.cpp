// SPDX-License-Identifier: GPL-3.0-only
// Replays recorded swipes through the same decoder and ranking as the keyboard (FUTO Swipe encoder
// + native/droneswipe key-path dictionaries + ranker) and reports accuracy.
//
// usage: swipe-replay --encoder model_int8.pte --dict sk:sk.kpd [--dict en:en.kpd]
//          [--params sk=gamma,beta,lambda,caps,nu,bonus] [--beam 200] [--topk 1000]
//          [--history words.tsv (word \t weight 0-255)] [--history-params lambda,history]
//          [--kappa 0] [--cap 4] [--prior sk=0.5,en=0.5] [--context 3] [--k3 2] [--tie 0] [--same-path w]
//          [--out pred.jsonl] in.jsonl...
//   --k3: trigram smoothing weight (KpDictionary params.k3); --tie: language tie-breaker margin
//   (rank_candidates tie_margin, 0 = off); --same-path: ScoringParams::same_path of every dictionary
//   --neural lang:dir: the neural model dir/<lang>.lmb + .lmv of a dictionary's language; with models,
//   the ranking fuses them like the keyboard (fuse_neural, NeuralFusion defaults) unless --no-fuse;
//   --fuse w,w_max,margin,w_no_context,oov,lambda_delta,nu_delta overrides the fusion parameters
//   --dump out.jsonl: the 40 best candidates before fusion with their score parts, the neural and
//   n-gram log probabilities (for offline tuning)
// The neural model gets the item's "tokens" and "bos" when present (tools/lm/make_lm_eval_set.py,
// synth.py --items / --tokens), otherwise its context words, like the keyboard without editor text.
//
// Input: one gesture per line, as written by the keyboard's swipe recorder (or the bench tools):
//   {"target": "ďakujem", "layout": [{"c": "q", "x": 0.05, "y": 0.17}, ...],
//    "x": [...], "y": [...], "t": [...], "context": ["dobrý", "deň"]}
// x/y are normalized like the keyboard does it (letter rows at 1/6, 1/2, 5/6), t in ms.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "kp_dictionary.hpp"
#include "neural_lm.hpp"
#include "ranker.hpp"
#include "swipe_decoder/engine.hpp"
#include "swipe_decoder/layout.hpp"

using json = nlohmann::json;
using droneswipe::KpDictionary;

namespace {

std::vector<std::string> split(const std::string& s, char c) {
    std::vector<std::string> out;
    std::stringstream ss(s);
    std::string item;
    while (std::getline(ss, item, c)) out.push_back(item);
    return out;
}

// base letters a-z of a word (diacritics and case removed) for Latin scripts we ship
std::string keypath(const std::string& w) {
    static const std::map<std::string, char> fold = {
        {"á", 'a'}, {"ä", 'a'}, {"č", 'c'}, {"ď", 'd'}, {"é", 'e'}, {"ě", 'e'}, {"í", 'i'}, {"ĺ", 'l'},
        {"ľ", 'l'}, {"ň", 'n'}, {"ó", 'o'}, {"ô", 'o'}, {"ŕ", 'r'}, {"ř", 'r'}, {"š", 's'}, {"ť", 't'},
        {"ú", 'u'}, {"ů", 'u'}, {"ý", 'y'}, {"ž", 'z'}};
    std::string lower = droneswipe::to_lower_utf8(w), out;
    for (size_t i = 0; i < lower.size();) {
        unsigned char c = lower[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : 4;
        std::string ch = lower.substr(i, len);
        i += len;
        if (len == 1) { if (c >= 'a' && c <= 'z') out += (char)c; continue; }
        auto it = fold.find(ch);
        if (it != fold.end()) out += it->second;
    }
    return out;
}

struct Stats {
    int n = 0, top1 = 0, top3 = 0, diacritics = 0, wrong_lang = 0, not_in_beam = 0, other = 0;
    void print(const char* name) const {
        if (!n) return;
        auto pct = [&](int k) { return 100.0 * k / n; };
        std::fprintf(stderr, "%-8s n=%5d  top1 %5.1f  top3 %5.1f | errors: diacritics %4.1f  wrong language %4.1f  "
                             "not in beam %4.1f  other %4.1f\n",
                     name, n, pct(top1), pct(top3), pct(diacritics), pct(wrong_lang), pct(not_in_beam), pct(other));
    }
};

}  // namespace

int main(int argc, char** argv) {
    std::string encoder, out_path;
    std::vector<std::pair<std::string, std::string>> dict_specs;
    std::map<std::string, std::vector<float>> params;
    std::map<std::string, float> prior_map;
    int beam = 200, topk = 1000, context_n = 3;
    float kappa = 0.0f, cap = 4.0f, k3 = -1.0f, tie = 0.0f, same_path = -1.0f;
    std::vector<std::string> inputs;
    std::string history_path, dump_path;
    std::vector<std::pair<std::string, std::string>> neural_specs;
    droneswipe::NeuralFusion fusion;  // the app's defaults
    bool fuse = true;
    std::vector<float> history_params = {0.025f, 1.0f};
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() { return i + 1 < argc ? std::string(argv[++i]) : std::string(); };
        if (a == "--encoder") encoder = next();
        else if (a == "--dict") { auto s = next(); auto p = s.find(':'); dict_specs.push_back({s.substr(0, p), s.substr(p + 1)}); }
        else if (a == "--params") {
            auto s = next(); auto p = s.find('=');
            std::vector<float> v;
            for (auto& x : split(s.substr(p + 1), ',')) v.push_back(std::stof(x));
            params[s.substr(0, p)] = v;
        }
        else if (a == "--prior") for (auto& kv : split(next(), ',')) { auto p = kv.find('='); prior_map[kv.substr(0, p)] = std::stof(kv.substr(p + 1)); }
        else if (a == "--beam") beam = std::stoi(next());
        else if (a == "--topk") topk = std::stoi(next());
        else if (a == "--kappa") kappa = std::stof(next());
        else if (a == "--k3") k3 = std::stof(next());
        else if (a == "--tie") tie = std::stof(next());
        else if (a == "--same-path") same_path = std::stof(next());
        else if (a == "--cap") cap = std::stof(next());
        else if (a == "--context") context_n = std::stoi(next());
        else if (a == "--out") out_path = next();
        else if (a == "--history") history_path = next();
        else if (a == "--neural") { auto s = next(); auto p = s.find(':'); neural_specs.push_back({s.substr(0, p), s.substr(p + 1)}); }
        else if (a == "--dump") dump_path = next();
        else if (a == "--no-fuse") fuse = false;
        else if (a == "--fuse") {
            auto v = split(next(), ',');
            float* f[] = {&fusion.weight, &fusion.weight_max, &fusion.margin_scale, &fusion.weight_no_context,
                          &fusion.oov, &fusion.lambda_delta, &fusion.nu_delta};
            for (size_t k = 0; k < v.size() && k < 7; ++k) *f[k] = std::stof(v[k]);
        }
        else if (a == "--history-params") { history_params.clear(); for (auto& x : split(next(), ',')) history_params.push_back(std::stof(x)); }
        else inputs.push_back(a);
    }
    if (encoder.empty() || dict_specs.empty() || inputs.empty()) {
        std::fprintf(stderr, "usage: swipe-replay --encoder model.pte --dict lang:dict.kpd ... in.jsonl\n");
        return 2;
    }

    std::vector<std::unique_ptr<KpDictionary>> dicts;
    std::vector<std::string> langs;
    for (auto& [lang, files] : dict_specs) {
        auto d = std::make_unique<KpDictionary>();
        auto t0 = std::chrono::steady_clock::now();
        if (!d->load_kpd(files)) { std::fprintf(stderr, "cannot load %s\n", files.c_str()); return 1; }
        auto pit = params.find(lang);
        if (pit != params.end() && pit->second.size() >= 6) {
            auto& v = pit->second;
            d->params = {v[0], v[1], v[2], v[3], v[4], v[5]};
        }
        if (k3 >= 0.0f) d->params.k3 = k3;
        if (same_path >= 0.0f) d->params.same_path = same_path;
        std::fprintf(stderr, "%s: %zu words in %.0f ms\n", lang.c_str(), d->word_count(),
                     std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
        langs.push_back(lang);
        dicts.push_back(std::move(d));
    }
    KpDictionary* user = nullptr;
    if (!history_path.empty()) {
        std::vector<std::pair<std::string, int>> words;
        std::ifstream hf(history_path);
        std::string hl;
        while (std::getline(hf, hl)) {
            auto f = split(hl, '\t');
            if (!f.empty() && !f[0].empty()) words.emplace_back(f[0], f.size() > 1 ? std::stoi(f[1]) : 128);
        }
        auto d = std::make_unique<KpDictionary>();
        d->load_user(words);
        d->params.lambda = history_params.size() > 0 ? history_params[0] : 0.025f;
        d->params.history = history_params.size() > 1 ? history_params[1] : 1.0f;
        d->params.caps = 0.0f;
        std::fprintf(stderr, "user: %zu words\n", d->word_count());
        user = d.get();
        langs.push_back("user");
        dicts.push_back(std::move(d));
    }
    std::vector<KpDictionary*> raw;
    for (auto& d : dicts) raw.push_back(d.get());
    // neural models (dir/<lang>.lmb, .lmv) in the dictionaries' order
    std::vector<std::unique_ptr<droneswipe::NeuralLm>> neural;
    std::vector<droneswipe::NeuralLanguageInput> nin;
    for (size_t i = 0; i < langs.size(); ++i)
        for (auto& [l, dir] : neural_specs) {
            if (l != langs[i]) continue;
            std::string err;
            auto m = droneswipe::NeuralLm::load(dir + "/" + l + ".lmb", dir + "/" + l + ".lmv", &err);
            if (!m) { std::fprintf(stderr, "neural %s: %s\n", l.c_str(), err.c_str()); return 1; }
            nin.push_back({m.get(), raw[i]});
            neural.push_back(std::move(m));
        }
    std::ofstream dump;
    if (!dump_path.empty()) dump.open(dump_path);
    std::vector<float> prior;
    for (auto& l : langs) prior.push_back(prior_map.count(l) ? prior_map[l] : 1.0f / langs.size());

    swipe_decoder::SwipeEngine engine;
    bool inited = false;
    std::string layout_sig;
    std::vector<ITrie> itries;
    itries.reserve(raw.size());
    std::ofstream out;
    if (!out_path.empty()) out.open(out_path);

    std::map<std::string, Stats> by_lang;
    Stats all;
    double total_ms = 0, neural_ms = 0;
    for (auto& path : inputs) {
        std::ifstream in(path);
        std::string line;
        while (std::getline(in, line)) {
            if (line.empty()) continue;
            json j = json::parse(line);
            std::string letters, sig;
            std::vector<float> cx, cy;
            std::vector<int> cps;
            for (auto& k : j["layout"]) {
                std::string c = k["c"];
                letters += c;
                cx.push_back(k["x"]);
                cy.push_back(k["y"]);
                sig += c + std::to_string((float)k["x"]) + "," + std::to_string((float)k["y"]) + ";";
            }
            if (sig != layout_sig) {
                for (int cp : swipe_decoder::utf8_to_codepoints(letters)) cps.push_back(cp);
                itries.clear();
                for (auto* d : raw) { d->set_layout(cps); itries.push_back(d->itrie()); }
                std::vector<ITrie*> tries;
                for (auto& t : itries) tries.push_back(&t);
                auto layout = swipe_decoder::make_layout(letters, cx.data(), cy.data(), (int)cx.size());
                if (!inited) {
                    swipe_decoder::SwipeEngine::Config cfg;
                    cfg.encoder_path = encoder;
                    cfg.layout = layout;
                    cfg.beam_width = std::max(beam, 200);
                    cfg.top_k = 32;
                    cfg.num_threads = 2;
                    cfg.dictionaries = tries;
                    if (!engine.init(cfg)) { std::fprintf(stderr, "engine init failed\n"); return 1; }
                    inited = true;
                } else {
                    swipe_decoder::SwipeEngine::Mode m;
                    m.layout = layout;
                    m.dictionaries = tries;
                    if (!engine.setMode(m)) { std::fprintf(stderr, "setMode failed\n"); return 1; }
                }
                layout_sig = sig;
            }
            std::vector<float> x = j["x"], y = j["y"], t = j["t"];
            std::vector<std::string> ctx;
            if (j.contains("context")) for (auto& w : j["context"]) ctx.push_back(droneswipe::to_lower_utf8(w));
            if ((int)ctx.size() > context_n) ctx.erase(ctx.begin(), ctx.end() - context_n);
            std::string prev = ctx.empty() ? std::string() : ctx.back();

            auto t0 = std::chrono::steady_clock::now();
            auto res = engine.recognize(x.data(), y.data(), t.data(), x.size(), {}, topk, beam);
            auto bonus = droneswipe::language_bonus(raw, ctx, prior, kappa, cap);
            auto ranked = droneswipe::rank_candidates(res, raw, bonus, ctx, dump.is_open() || fuse ? 40 : 18, user, tie);
            // neural context: the item's tokens (held-out sets) or the context words, like the app
            std::vector<std::string> ntok;
            bool nbos = false;
            if (j.contains("tokens")) { for (auto& w : j["tokens"]) ntok.push_back(w); nbos = j.value("bos", false); }
            else {
                if (j.contains("context")) for (auto& w : j["context"]) ntok.push_back(droneswipe::to_lower_utf8(w));
                nbos = ntok.empty();
            }
            std::vector<float> nlp;
            float unk = -INFINITY;
            if (!nin.empty() && dump.is_open()) {
                std::vector<std::string> lows;
                for (auto& r : ranked) lows.push_back(droneswipe::to_lower_utf8(r.word));
                nlp = droneswipe::neural_word_logp(nin, ntok, nbos, lows, 0.05f, &unk);
            }
            if (dump.is_open()) {
                json o;
                o["target"] = j["target"];
                o["lang"] = j.contains("lang") ? j["lang"] : json("all");
                std::string tkp0 = keypath(j["target"]);
                o["unk"] = std::isfinite(unk) ? unk : -99.0f;
                o["ntok"] = ntok.size();
                o["in_beam"] = std::any_of(res.begin(), res.end(), [&](auto& r) { return r.word == tkp0; });
                json c = json::array();
                for (size_t i = 0; i < ranked.size(); ++i) {
                    auto& r = ranked[i];
                    auto* d = raw[r.dict];
                    const std::string low = droneswipe::to_lower_utf8(r.word);
                    const size_t nc = ctx.size();
                    int32_t p1 = nc ? d->lower_id(ctx[nc - 1]) : -1, p2 = nc > 1 && p1 >= 0 ? d->lower_id(ctx[nc - 2]) : -1;
                    int32_t wid = d->lower_id(low);
                    float ng = wid >= 0 ? d->logp_ctx(p2, p1, (uint32_t)wid) : -30.0f;
                    float nn = i < nlp.size() && std::isfinite(nlp[i]) ? nlp[i] : -99.0f;
                    float ug = wid >= 0 ? d->logp_id((uint32_t)wid) : -30.0f;
                    c.push_back({r.word, r.dict, r.score, r.ctc, r.len, r.freq, r.ctx, nn, ng, ug});
                }
                o["c"] = c;
                dump << o.dump() << "\n";
            }
            if (fuse && !nin.empty()) {
                auto t1 = std::chrono::steady_clock::now();
                droneswipe::fuse_neural(ranked, raw, user, nin, ntok, nbos, fusion);
                neural_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count();
            }
            if ((int)ranked.size() > 18) ranked.resize(18);
            total_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();

            std::string target = j["target"];
            std::string tl = droneswipe::to_lower_utf8(target), tkp = keypath(target);
            int rank = -1;
            for (int i = 0; i < (int)ranked.size(); ++i)
                if (droneswipe::to_lower_utf8(ranked[i].word) == tl) { rank = i; break; }
            std::string lang = j.contains("lang") ? std::string(j["lang"]) : std::string("all");
            for (Stats* s : {&all, &by_lang[lang]}) {
                s->n++;
                if (rank == 0) s->top1++;
                if (rank >= 0 && rank < 3) s->top3++;
                if (rank != 0) {
                    bool in_beam = std::any_of(res.begin(), res.end(), [&](auto& r) { return r.word == tkp; });
                    std::string top = ranked.empty() ? "" : ranked[0].word;
                    int target_dict = -1;
                    for (int d = 0; d < (int)raw.size(); ++d) if (raw[d]->variants_of(tkp)) { target_dict = d; break; }
                    if (!in_beam) s->not_in_beam++;
                    else if (!top.empty() && keypath(top) == tkp) s->diacritics++;
                    else if (!ranked.empty() && target_dict >= 0 && !raw[ranked[0].dict]->variants_of(tkp)
                             && raw[ranked[0].dict] != raw[target_dict]) s->wrong_lang++;
                    else s->other++;
                }
            }
            if (out.is_open()) {
                json o;
                o["target"] = target;
                o["rank"] = rank;
                json p = json::array();
                for (int i = 0; i < std::min(10, (int)ranked.size()); ++i)
                    p.push_back({ranked[i].word, langs[ranked[i].dict], ranked[i].score});
                o["pred"] = p;
                if (j.contains("lang")) o["lang"] = j["lang"];
                out << o.dump() << "\n";
            }
        }
    }
    for (auto& [l, s] : by_lang) if (l != "all") s.print(l.c_str());
    all.print("ALL");
    if (all.n) std::fprintf(stderr, "decode+rank %.1f ms per swipe (Mac), of which neural fusion %.1f ms\n",
                            total_ms / all.n, neural_ms / all.n);
    return 0;
}
