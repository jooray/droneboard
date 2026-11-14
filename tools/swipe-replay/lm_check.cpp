// SPDX-License-Identifier: GPL-3.0-only
// Numerical check and micro-benchmark of the neural LM runtime (native/droneswipe/neural_lm.cpp).
//
// usage: lm-check model.lmb model.lmv contexts.jsonl [app] > out.jsonl
//
// With "app", each call gets what the keyboard passes for a long text: the last 31 ids before the
// cursor without <bos> (timings only).
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
        std::fprintf(stderr, "usage: lm-check model.lmb model.lmv contexts.jsonl\n");
        return 2;
    }
    std::string err;
    auto t0 = std::chrono::steady_clock::now();
    auto lm = droneswipe::NeuralLm::load(argv[1], argv[2], &err);
    if (!lm) { std::fprintf(stderr, "%s\n", err.c_str()); return 1; }
    std::fprintf(stderr, "loaded in %.1f ms, kernel %s\n",
                 std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count(),
                 droneswipe::neural_kernel_name());
    const bool app = argc > 4 && std::string(argv[4]) == "app";
    std::ifstream in(argv[3]);
    std::string line;
    std::vector<double> step_ms, restart_ms, predict_ms;
    std::vector<float> lg(lm->vocab());
    while (std::getline(in, line)) {
        if (line.empty()) continue;
        auto ids = json::parse(line)["ids"].get<std::vector<int32_t>>();
        std::lock_guard<std::mutex> lock(lm->mutex());
        // word by word: every call extends the cached window by one token
        std::vector<int32_t> prefix;
        const float* z = nullptr;
        for (size_t k = 0; k < ids.size(); ++k) {
            prefix.push_back(ids[k]);
            if (app && prefix.size() > 31) prefix.erase(prefix.begin());
            auto s = std::chrono::steady_clock::now();
            float lse;
            lm->distribution(prefix, &lse);
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - s).count();
            (k == 0 ? restart_ms : step_ms).push_back(ms);
            if (app) {
                // the whole next-word prediction as the keyboard runs it (one language, no dictionary)
                std::vector<std::string> toks;
                for (int32_t id : prefix) toks.push_back(lm->token(id));
                lm->mutex().unlock();
                auto s2 = std::chrono::steady_clock::now();
                droneswipe::predict_neural({{lm.get(), nullptr}}, toks, false, "", 20);
                predict_ms.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - s2).count());
                lm->mutex().lock();
            }
            z = lm->hidden(prefix);
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
    for (auto* v : {&step_ms, &restart_ms, &predict_ms}) {
        if (v->empty()) continue;
        std::sort(v->begin(), v->end());
        std::fprintf(stderr, "%s: median %.3f ms, p95 %.3f ms\n",
                     v == &step_ms ? "one more token (model step + vocabulary scoring)"
                     : v == &restart_ms ? "first token (model step + vocabulary scoring)" : "predict_neural, cached context",
                     (*v)[v->size() / 2], (*v)[v->size() * 95 / 100]);
    }
    return 0;
}
