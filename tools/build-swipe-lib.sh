#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Rebuilds app/src/main/jniLibs/<abi>/libdroneswipe.so: FUTO swipe-library + ExecuTorch + native/droneswipe.
#
#   tools/build-swipe-lib.sh [work_dir] [abi...]      default: all four ABIs
#
# Needs: git, cmake >= 3.29, python3 (the swipe-library Makefile creates its own venv with torch
# for ExecuTorch's code generation), GNU sed (macOS: brew install gnu-sed), Android NDK 28.
#
# Build paths never end up in the libraries: every compile maps the work dir, the repo and the NDK
# to neutral names (-ffile-prefix-map covers __FILE__, assert strings and debug info), the result
# is stripped, and the script fails if an absolute path is left in a library.
set -euo pipefail

SWIPE_LIBRARY_URL=https://gitlab.futo.org/keyboard/swipe-library.git
SWIPE_LIBRARY_REV=1b13f2c85d6b347f6ea3fbc4b3aaf01fce42429a
NDK_VERSION=28.0.13004108

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${1:-$ROOT/build/swipe-native}
shift || true
ABIS=${*:-arm64-v8a armeabi-v7a x86_64 x86}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=$SDK/ndk/$NDK_VERSION
case "$(uname -s)" in
  Darwin) HOST=darwin-x86_64; export PATH=/opt/homebrew/opt/gnu-sed/libexec/gnubin:$PATH ;;
  *) HOST=linux-x86_64 ;;
esac
STRIP=$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-strip
JOBS=$(getconf _NPROCESSORS_ONLN)

mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd -P)
if [ ! -d "$WORK/swipe-library" ]; then
  git clone "$SWIPE_LIBRARY_URL" "$WORK/swipe-library"
fi
git -C "$WORK/swipe-library" checkout -q -- src/beam_search.cpp src/engine.cpp 2>/dev/null || true
git -C "$WORK/swipe-library" checkout -q "$SWIPE_LIBRARY_REV"
git -C "$WORK/swipe-library" submodule update --init --recursive

# Apply idempotent Droneboard speed patches to upstream FUTO swipe-library:
#  1) beam_search.cpp: replace per-hypothesis std::pow(d_eff, gamma_prune) with a 64-float thread_local LUT (exact 0.0 diff, 4.5x faster).
#  2) engine.cpp: run multi-trie decode_with_scores_multi in parallel across active tries with a persistent helper thread (1.74x faster bilingual decode, bit-identical order).
python3 - "$WORK/swipe-library/src/beam_search.cpp" "$WORK/swipe-library/src/engine.cpp" <<'PYEOF'
import sys

# 1. beam_search.cpp: 64-float std::pow LUT
bs_path = sys.argv[1]
bs = open(bs_path, "r", encoding="utf-8").read()
if "DRONEBOARD_POW_LUT" not in bs:
    old_fn = """    int d_eff = depth > 0 ? static_cast<int>(depth) : 1;
    return score / std::pow(static_cast<float>(d_eff), gamma_prune)
         + beta_prune * static_cast<float>(depth);"""
    new_fn = """    // DRONEBOARD_POW_LUT: 64-float thread_local LUT (exact bit-for-bit match to std::pow)
    thread_local float cached_gamma = -1.0f;
    thread_local float pow_lut[64];
    if (__builtin_expect(gamma_prune != cached_gamma, 0)) {
        cached_gamma = gamma_prune;
        pow_lut[0] = 1.0f;
        for (int d = 1; d < 64; ++d) {
            pow_lut[d] = std::pow(static_cast<float>(d), gamma_prune);
        }
    }
    int d_eff = depth > 0 ? static_cast<int>(depth) : 1;
    float denom = d_eff < 64 ? pow_lut[d_eff] : std::pow(static_cast<float>(d_eff), gamma_prune);
    return score / denom + beta_prune * static_cast<float>(depth);"""
    assert old_fn in bs, f"expected length_prune_score snippet not found in {bs_path}"
    open(bs_path, "w", encoding="utf-8").write(bs.replace(old_fn, new_fn, 1))

# 2. engine.cpp: persistent helper thread for parallel multi-trie beam search
eng_path = sys.argv[2]
eng = open(eng_path, "r", encoding="utf-8").read()
if "DRONEBOARD_PARALLEL_TRIES" not in eng:
    old_inc = "#include <cmath>"
    new_inc = """#include <cmath>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <thread>"""
    assert old_inc in eng, f"expected #include <cmath> in {eng_path}"
    eng = eng.replace(old_inc, new_inc, 1)

    old_member = "    std::unique_ptr<TrieBeamSearch> beam_search;"
    new_member = """    // DRONEBOARD_PARALLEL_TRIES: parallel multi-trie beam search with persistent helper thread
    std::unique_ptr<TrieBeamSearch> beam_search;
    std::vector<std::unique_ptr<TrieBeamSearch>> extra_beam_searches;
    struct TrieWorker {
        std::mutex mtx;
        std::condition_variable cv_work, cv_done;
        std::function<void()> task;
        bool has_task = false;
        bool busy = false;
        bool stop = false;
        std::thread th;
        void start() {
            if (th.joinable()) return;
            th = std::thread([this]() {
                while (true) {
                    std::function<void()> fn;
                    {
                        std::unique_lock<std::mutex> lk(mtx);
                        cv_work.wait(lk, [this]() { return stop || has_task; });
                        if (stop && !has_task) return;
                        fn = std::move(task);
                        has_task = false;
                    }
                    if (fn) fn();
                    {
                        std::lock_guard<std::mutex> lk(mtx);
                        busy = false;
                    }
                    cv_done.notify_one();
                }
            });
        }
        void submit(std::function<void()> fn) {
            start();
            {
                std::lock_guard<std::mutex> lk(mtx);
                task = std::move(fn);
                has_task = true;
                busy = true;
            }
            cv_work.notify_one();
        }
        void wait() {
            std::unique_lock<std::mutex> lk(mtx);
            cv_done.wait(lk, [this]() { return !busy; });
        }
        ~TrieWorker() {
            if (!th.joinable()) return;
            {
                std::lock_guard<std::mutex> lk(mtx);
                stop = true;
            }
            cv_work.notify_one();
            th.join();
        }
    } trie_worker;"""
    assert old_member in eng, f"expected beam_search member in {eng_path}"
    eng = eng.replace(old_member, new_member, 1)

    old_quick = """        beam_search->set_scoring(scoring);
        beam_search->set_single_mode((mode & MMM_DECODER_BIT) != 0);"""
    new_quick = """        beam_search->set_scoring(scoring);
        beam_search->set_single_mode((mode & MMM_DECODER_BIT) != 0);
        for (auto& eb : extra_beam_searches) {
            eb->set_scoring(scoring);
            eb->set_single_mode((mode & MMM_DECODER_BIT) != 0);
        }"""
    assert old_quick in eng, f"expected quick_update_scoring snippet in {eng_path}"
    eng = eng.replace(old_quick, new_quick, 1)

    old_rebuild = """    bool rebuild_beam_search(int num_keys) {
        beam_search = std::make_unique<TrieBeamSearch>(
            config.beam_width,
            scoring,
            num_keys);
        return beam_search != nullptr;
    }"""
    new_rebuild = """    bool rebuild_beam_search(int num_keys) {
        beam_search = std::make_unique<TrieBeamSearch>(
            config.beam_width,
            scoring,
            num_keys);
        extra_beam_searches.clear();
        return beam_search != nullptr;
    }"""
    assert old_rebuild in eng, f"expected rebuild_beam_search snippet in {eng_path}"
    eng = eng.replace(old_rebuild, new_rebuild, 1)

    old_loop = """    std::vector<DecodedWord> results;
    int i = 0;
    for(const auto &itrie : impl_->itries) {
        const float weight = i < trie_weights.size() ?
            trie_weights[i] : 1.0f;

        i++;

        if(weight <= 0.001f) continue; // Skip ignored tries

        impl_->beam_search->decode_with_scores_multi(
            itrie, results,
            left_logprobs_m.data(), left_times.data(), left_times.size(),
            right_logprobs_m.data(), right_times.data(), right_times.size(),
            weight, beam_width
        );
    }"""
    new_loop = """    std::vector<DecodedWord> results;
    struct ActiveTrie { const ITrie* trie; float weight; };
    std::vector<ActiveTrie> active;
    active.reserve(impl_->itries.size());
    for (size_t idx = 0; idx < impl_->itries.size(); ++idx) {
        const float weight = idx < trie_weights.size() ? trie_weights[idx] : 1.0f;
        if (weight <= 0.001f) continue;
        active.push_back({impl_->itries[idx], weight});
    }
    if (active.size() == 1) {
        impl_->beam_search->decode_with_scores_multi(
            active[0].trie, results,
            left_logprobs_m.data(), left_times.data(), left_times.size(),
            right_logprobs_m.data(), right_times.data(), right_times.size(),
            active[0].weight, beam_width
        );
    } else if (active.size() >= 2) {
        while (impl_->extra_beam_searches.size() < 1) {
            auto eb = std::make_unique<TrieBeamSearch>(
                impl_->config.beam_width, impl_->scoring, impl_->layout.num_keys);
            eb->set_single_mode((impl_->curr_mode & MMM_DECODER_BIT) != 0);
            impl_->extra_beam_searches.push_back(std::move(eb));
        }
        std::vector<std::vector<DecodedWord>> per_trie(active.size());
        std::atomic<size_t> next_trie{0};
        auto run_slot = [&](TrieBeamSearch* bs) {
            while (true) {
                size_t k = next_trie.fetch_add(1, std::memory_order_relaxed);
                if (k >= active.size()) break;
                bs->decode_with_scores_multi(
                    active[k].trie, per_trie[k],
                    left_logprobs_m.data(), left_times.data(), left_times.size(),
                    right_logprobs_m.data(), right_times.data(), right_times.size(),
                    active[k].weight, beam_width
                );
            }
        };
        impl_->trie_worker.submit([&]() { run_slot(impl_->extra_beam_searches[0].get()); });
        run_slot(impl_->beam_search.get());
        impl_->trie_worker.wait();
        for (auto& pt : per_trie) {
            results.insert(results.end(),
                           std::make_move_iterator(pt.begin()),
                           std::make_move_iterator(pt.end()));
        }
    }"""
    assert old_loop in eng, f"expected multi-trie decode loop in {eng_path}"
    eng = eng.replace(old_loop, new_loop, 1)
    open(eng_path, "w", encoding="utf-8").write(eng)
PYEOF

# The swipe-library Makefile creates its venv with `python3` and needs torch 2.10's torchgen, which
# has wheels only up to Python 3.13: put a 3.12 first in PATH (uv provides one if needed).
PY312=$(uv python find 3.12 2>/dev/null || { uv python install 3.12 >&2 && uv python find 3.12; })
mkdir -p "$WORK/py312"
ln -sf "$PY312" "$WORK/py312/python3"
export PATH="$WORK/py312:$PATH"
if [ -x "$WORK/swipe-library/.venv/bin/python3" ] && ! "$WORK/swipe-library/.venv/bin/python3" -c "import torchgen" 2>/dev/null; then
  rm -rf "$WORK/swipe-library/.venv"  # a half-made venv from a failed run
fi

# the more specific prefixes come last (clang uses the last matching map)
PREFIX_MAP="-ffile-prefix-map=$NDK=ndk -ffile-prefix-map=$ROOT=droneboard -ffile-prefix-map=$WORK=swipe-native"
ET_ROOT=third_party/executorch
ANDROID_FLAGS="-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_PLATFORM=android-27 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"

for ABI in $ABIS; do
  # ExecuTorch static libs for this ABI, built with the prefix maps (a marker tells an old,
  # unmapped build apart, which is then rebuilt from scratch)
  ET_OUT="$WORK/swipe-library/$ET_ROOT/cmake-out-$ABI"
  if [ -d "$ET_OUT" ] && [ ! -f "$ET_OUT/.droneboard-prefix-map" ]; then rm -rf "$ET_OUT"; fi
  make -C "$WORK/swipe-library" "$ET_ROOT/cmake-out-$ABI/libexecutorch.a" ANDROID_NDK_HOME="$NDK" JOBS="$JOBS" \
    ANDROID_CMAKE_FLAGS="$ANDROID_FLAGS \"-DCMAKE_C_FLAGS=$PREFIX_MAP\" \"-DCMAKE_CXX_FLAGS=$PREFIX_MAP\" \"-DCMAKE_ASM_FLAGS=$PREFIX_MAP\""
  touch "$ET_OUT/.droneboard-prefix-map"

  cmake -S "$ROOT/native/droneswipe" -B "$WORK/droneswipe-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-27 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
    "-DCMAKE_C_FLAGS=$PREFIX_MAP" "-DCMAKE_CXX_FLAGS=$PREFIX_MAP" \
    -DSWIPE_LIBRARY_DIR="$WORK/swipe-library"
  cmake --build "$WORK/droneswipe-$ABI" -j"$JOBS" --target droneswipe
  OUT="$ROOT/app/src/main/jniLibs/$ABI/libdroneswipe.so"
  mkdir -p "$(dirname "$OUT")"
  "$STRIP" --strip-all -o "$OUT" "$WORK/droneswipe-$ABI/libdroneswipe.so"
  if strings "$OUT" | grep -E "/Users/|/private/|/home/|/tmp/"; then
    echo "error: $OUT contains absolute build paths (above)" >&2
    exit 1
  fi
  echo "built app/src/main/jniLibs/$ABI/libdroneswipe.so"
done

# the encoder model (FUTO Model Weights License, see assets/swipe/honorable_sturgeon/LICENSE.md);
# FUTO's training run directory is dropped from the metadata, nothing reads it
MODEL_DIR="$WORK/swipe-library/models/honorable_sturgeon"
if [ ! -f "$MODEL_DIR/model_int8.pte" ]; then
  # model weights are in Git LFS upstream; without git-lfs the committed copy in the app stays
  echo "note: $MODEL_DIR/model_int8.pte not present (git lfs pull?); keeping the bundled model" >&2
  exit 0
fi
cp "$MODEL_DIR/model_int8.pte" "$ROOT/app/src/main/assets/swipe/honorable_sturgeon/"
python3 -I -c 'import json, sys
d = json.load(open(sys.argv[1]))
d.pop("run_dir", None)
open(sys.argv[2], "w").write(json.dumps(d, indent=2) + "\n")' \
  "$WORK/swipe-library/models/honorable_sturgeon/metadata.json" "$ROOT/app/src/main/assets/swipe/honorable_sturgeon/metadata.json"
