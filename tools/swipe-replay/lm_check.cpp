// SPDX-License-Identifier: GPL-3.0-only
// Numerical check and micro-benchmark of the neural LM runtime (native/droneswipe/neural_lm.cpp).
//
// usage: lm-check model.lmb model.lmv contexts.jsonl [app] > out.jsonl
//
// Supports both v1 (gpt256, ctx=32) and v2 (striped256/modern256, ctx=64, mixed INT8/INT4-g32,
// optional self-normalization).
//
// With "app", each call gets what the keyboard passes for a long text: the last (ctx - 1) ids
// before the cursor without <bos> (timings only, including predict_neural and 40-cand fuse_neural).
//
// Each input line is {"ids": [...]} (token ids, oldest first). The ids are fed one at a time, as when
// typing word by word (key/value cache), and the hidden state after the last one plus the top 10
// next tokens are printed as {"z": [...], "top10": [...]}. tools/lm/verify_keyboard_lm.py compares
// them with the MLX model. Timings go to stderr.
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <numeric>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "neural_lm.hpp"

using json = nlohmann::json;

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: lm-check model.lmb model.lmv contexts.jsonl [app]\n");
        return 2;
    }
    std::string err;
    auto t0 = std::chrono::steady_clock::now();
    auto lm = droneswipe::NeuralLm::load(argv[1], argv[2], &err);
    if (!lm) { std::fprintf(stderr, "%s\n", err.c_str()); return 1; }
    std::fprintf(stderr, "loaded v%d in %.1f ms (V=%d E=%d ctx=%d int8=%d), kernel %s\n",
                 lm->version(),
                 std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count(),
                 lm->vocab(), lm->dim(), lm->ctx(), lm->int8_rows(),
                 droneswipe::neural_kernel_name());
    const bool app = argc > 4 && std::string(argv[4]) == "app";
    const size_t max_window = (size_t)std::max(1, lm->ctx() - 1);
    std::ifstream in(argv[3]);
    std::string line;
    std::vector<double> step_ms, restart_ms, predict_ms, score40_ms;
    std::vector<float> lg(lm->vocab());
    std::vector<std::string> sample40;
    for (int32_t id = 3; id < lm->vocab() && sample40.size() < 40; ++id) {
        if (lm->is_word(id)) sample40.push_back(lm->token(id));
    }

    while (std::getline(in, line)) {
        if (line.empty()) continue;
        auto ids = json::parse(line)["ids"].get<std::vector<int32_t>>();
        std::lock_guard<std::mutex> lock(lm->mutex());
        // word by word: every call extends the cached window by one token
        std::vector<int32_t> prefix;
        const float* z = nullptr;
        for (size_t k = 0; k < ids.size(); ++k) {
            prefix.push_back(ids[k]);
            if (app && prefix.size() > max_window) prefix.erase(prefix.begin());
            auto s = std::chrono::steady_clock::now();
            float lse = 0.0f;
            lm->distribution(prefix, &lse);
            z = lm->hidden(prefix);
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - s).count();
            (k == 0 ? restart_ms : step_ms).push_back(ms);
            if (app) {
                std::vector<std::string> toks;
                toks.reserve(prefix.size());
                for (int32_t id : prefix) toks.push_back(lm->token(id));
                lm->mutex().unlock();
                auto s2 = std::chrono::steady_clock::now();
                droneswipe::predict_neural({{lm.get(), nullptr}}, toks, false, "", 20);
                predict_ms.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - s2).count());

                auto s3 = std::chrono::steady_clock::now();
                float unk = 0.0f;
                droneswipe::neural_word_logp({{lm.get(), nullptr}}, toks, false, sample40, 0.05f, &unk);
                score40_ms.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - s3).count());
                lm->mutex().lock();
            }
        }
        if (!z || app) continue;
        lm->logits(z, lg.data());
        std::vector<int> idx(lg.size());
        std::iota(idx.begin(), idx.end(), 0);
        std::partial_sort(idx.begin(), idx.begin() + 10, idx.end(), [&](int a, int b) { return lg[a] > lg[b]; });
        json out;
        out["z"] = std::vector<float>(z, z + lm->dim());
        out["top10"] = std::vector<int>(idx.begin(), idx.begin() + 10);
        std::printf("%s\n", out.dump().c_str());
    }
    std::fprintf(stderr, "steps %ld, window restarts %ld, cached %ld\n", lm->steps, lm->restarts, lm->cached);
    for (auto* v : {&step_ms, &restart_ms, &predict_ms, &score40_ms}) {
        if (v->empty()) continue;
        std::sort(v->begin(), v->end());
        const char* label =
            v == &step_ms ? "one more token (model step + scoring)"
            : v == &restart_ms ? "first token (model step + scoring)"
            : v == &predict_ms ? "predict_neural, cached context"
                               : "neural_word_logp (40 cands), cached context";
        std::fprintf(stderr, "%s: median %.3f ms, p95 %.3f ms\n",
                     label, (*v)[v->size() / 2], (*v)[v->size() * 95 / 100]);
    }
    return 0;
}
