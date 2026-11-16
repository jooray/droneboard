// SPDX-License-Identifier: GPL-3.0-only
// JNI glue between Droneboard (helium314.keyboard.latin.swipe.FutoSwipe) and the FUTO Swipe
// engine (swipe-library, ExecuTorch encoder "honorable_sturgeon").
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "kp_dictionary.hpp"
#include "neural_lm.hpp"
#include "ranker.hpp"
#include "swipe_decoder/engine.hpp"
#include "swipe_decoder/layout.hpp"

#define TAG "DroneSwipe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using droneswipe::KpDictionary;
using swipe_decoder::SwipeEngine;

namespace {

struct Session {
    SwipeEngine engine;
    std::string encoder_path;
    bool initialized = false;
    std::vector<KpDictionary*> dicts;
    std::vector<ITrie> itries;
    // the user's own words (typing history, personal dictionary); searched as one more dictionary
    std::unique_ptr<KpDictionary> user;
    // the previous user dictionary stays alive until the next nativeSetLayout, because the engine
    // still holds tries pointing into it
    std::unique_ptr<KpDictionary> retired_user;
    float last_ms = 0;
    float neural_ms = 0;  // of last_ms: the neural fusion
    float tie_margin = 0.0f;  // language continuity tie-breaker (ranker.hpp), 0 = off
};

void set_params(JNIEnv* env, KpDictionary* d, jfloatArray params) {
    if (!d || !params) return;
    jsize n = env->GetArrayLength(params);
    if (n < 6) return;
    float p[7] = {0, 0, 0, 0, 0, 0, 0};
    env->GetFloatArrayRegion(params, 0, std::min<jsize>(n, 7), p);
    d->params = {p[0], p[1], p[2], p[3], p[4], p[5], p[6]};
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string utf8_of(const std::vector<int>& cps) {
    std::string out;
    for (int cp : cps) {
        if (cp < 0x80) out += (char)cp;
        else if (cp < 0x800) { out += (char)(0xC0 | (cp >> 6)); out += (char)(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out += (char)(0xE0 | (cp >> 12)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
        else { out += (char)(0xF0 | (cp >> 18)); out += (char)(0x80 | ((cp >> 12) & 0x3F)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeCreate(JNIEnv* env, jclass, jstring encoderPath) {
    auto* s = new Session();
    s->encoder_path = jstr(env, encoderPath);
    return (jlong)(uintptr_t)s;
}

JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete (Session*)(uintptr_t)h;
}

JNIEXPORT jlong JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeLoadDictionary(JNIEnv* env, jclass, jstring path, jfloatArray params) {
    auto t0 = std::chrono::steady_clock::now();
    auto* d = new KpDictionary();
    if (!d->load_kpd(jstr(env, path))) {
        LOGE("could not load dictionary %s", jstr(env, path).c_str());
        delete d;
        return 0;
    }
    set_params(env, d, params);
    LOGI("dictionary %s: %zu words in %.0f ms", jstr(env, path).c_str(), d->word_count(),
         std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    return (jlong)(uintptr_t)d;
}

// The user's words with weights 0..255 (null or empty clears them); takes effect with the next nativeSetLayout.
JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeSetUserWords(JNIEnv* env, jclass, jlong h, jobjectArray words,
                                                                 jintArray weights, jfloatArray params) {
    auto* s = (Session*)(uintptr_t)h;
    if (!s) return;
    std::vector<std::pair<std::string, int>> list;
    if (words && weights) {
        jsize n = std::min(env->GetArrayLength(words), env->GetArrayLength(weights));
        std::vector<jint> w(n);
        env->GetIntArrayRegion(weights, 0, n, w.data());
        list.reserve(n);
        for (jsize i = 0; i < n; ++i) {
            auto js = (jstring)env->GetObjectArrayElement(words, i);
            list.emplace_back(jstr(env, js), w[i]);
            if (js) env->DeleteLocalRef(js);
        }
    }
    // if a dictionary is already retired, the engine refers to that one, not to the current one
    if (s->retired_user) s->user.reset();
    else if (s->user) s->retired_user = std::move(s->user);
    if (list.empty()) return;
    auto d = std::make_unique<KpDictionary>();
    d->load_user(list);
    set_params(env, d.get(), params);
    LOGI("user words: %zu", d->word_count());
    s->user = d->word_count() ? std::move(d) : nullptr;
}

JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeFreeDictionary(JNIEnv*, jclass, jlong d) {
    delete (KpDictionary*)(uintptr_t)d;
}

JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeSetDictionaryParams(JNIEnv* env, jclass, jlong dh, jfloatArray params) {
    set_params(env, (KpDictionary*)(uintptr_t)dh, params);
}

// letters: code points of the letter keys; xs/ys: normalized key centers; dicts: KpDictionary handles
JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeSetLayout(JNIEnv* env, jclass, jlong h, jintArray letters,
                                                             jfloatArray xs, jfloatArray ys, jlongArray dicts) {
    auto* s = (Session*)(uintptr_t)h;
    if (!s) return JNI_FALSE;
    jsize n = env->GetArrayLength(letters);
    std::vector<int> cps(n);
    std::vector<float> cx(n), cy(n);
    env->GetIntArrayRegion(letters, 0, n, cps.data());
    env->GetFloatArrayRegion(xs, 0, n, cx.data());
    env->GetFloatArrayRegion(ys, 0, n, cy.data());
    jsize nd = env->GetArrayLength(dicts);
    std::vector<jlong> dh(nd);
    env->GetLongArrayRegion(dicts, 0, nd, dh.data());

    s->dicts.clear();
    s->itries.clear();
    s->itries.reserve(nd);
    for (jlong x : dh) {
        auto* d = (KpDictionary*)(uintptr_t)x;
        if (!d) continue;
        d->set_layout(cps);
        s->dicts.push_back(d);
        s->itries.push_back(d->itrie());
    }
    if (s->user && !s->dicts.empty()) {
        s->user->set_layout(cps);
        s->dicts.push_back(s->user.get());
        s->itries.push_back(s->user->itrie());
    }
    std::vector<ITrie*> tries;
    for (auto& t : s->itries) tries.push_back(&t);
    auto layout = swipe_decoder::make_layout(utf8_of(cps), cx.data(), cy.data(), n);
    if (!s->initialized) {
        SwipeEngine::Config cfg;
        cfg.encoder_path = s->encoder_path;
        cfg.layout = layout;
        // the largest beam recognize() may ask for (a wider request is cut to this); the final decode
        // uses the user's "swipe search width" (50-400), the live preview 32
        cfg.beam_width = 400;
        cfg.top_k = 32;
        cfg.num_threads = 2;
        cfg.dictionaries = tries;
        if (!s->engine.init(cfg)) {
            LOGE("engine init failed (encoder %s)", s->encoder_path.c_str());
            return JNI_FALSE;
        }
        s->initialized = true;
    } else {
        SwipeEngine::Mode m;
        m.layout = layout;
        m.dictionaries = tries;
        if (!s->engine.setMode(m)) {
            LOGE("setMode failed");
            return JNI_FALSE;
        }
    }
    s->retired_user.reset();  // the engine no longer refers to it
    return JNI_TRUE;
}

// Returns the best words (surface forms) and writes their scores and dictionary indices.
// context: up to a few words before the cursor, oldest first (the last one is the previous word);
// prior: per-dictionary language prior (may be null); kappa/cap: weight and floor of the
// context language bonus (see ranker.hpp).
// models: neural models (neural_lm.hpp) of the layout's dictionaries in their order, or null; with
// them, the candidates are reranked with fuse_neural from the normalized tokens before the cursor
// (bos: they start at the beginning of the text) and fusion = NeuralFusion's fields in order (null =
// defaults). A 0 handle (model not loaded) switches the fusion off.
JNIEXPORT jobjectArray JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeRecognize(JNIEnv* env, jclass, jlong h, jfloatArray jx,
                                                             jfloatArray jy, jfloatArray jt, jobjectArray jcontext,
                                                             jfloatArray jprior, jfloat kappa, jfloat cap,
                                                             jint beam, jint maxResults, jfloatArray outScores,
                                                             jintArray outDict, jlongArray models, jobjectArray jtokens,
                                                             jboolean bos, jfloatArray jfusion) {
    auto* s = (Session*)(uintptr_t)h;
    jclass strCls = env->FindClass("java/lang/String");
    if (!s || !s->initialized || s->dicts.empty()) return env->NewObjectArray(0, strCls, nullptr);
    jsize n = env->GetArrayLength(jx);
    if (n < 2) return env->NewObjectArray(0, strCls, nullptr);
    std::vector<float> x(n), y(n), t(n);
    env->GetFloatArrayRegion(jx, 0, n, x.data());
    env->GetFloatArrayRegion(jy, 0, n, y.data());
    env->GetFloatArrayRegion(jt, 0, n, t.data());
    std::vector<std::string> context;
    if (jcontext) {
        jsize nc = env->GetArrayLength(jcontext);
        for (jsize i = 0; i < nc; ++i) {
            auto js = (jstring)env->GetObjectArrayElement(jcontext, i);
            context.push_back(droneswipe::to_lower_utf8(jstr(env, js)));
            if (js) env->DeleteLocalRef(js);
        }
    }
    std::vector<float> prior;
    if (jprior) {
        prior.resize(env->GetArrayLength(jprior));
        env->GetFloatArrayRegion(jprior, 0, (jsize)prior.size(), prior.data());
    }

    KpDictionary* user = s->user && s->dicts.back() == s->user.get() ? s->user.get() : nullptr;
    // neural fusion: one model per language dictionary (the user dictionary is last and has none)
    std::vector<droneswipe::NeuralLanguageInput> nin;
    std::vector<std::string> tokens;
    droneswipe::NeuralFusion fusion;
    if (models) {
        const size_t nlang = s->dicts.size() - (user ? 1 : 0);
        jsize nm = env->GetArrayLength(models);
        std::vector<jlong> mh(nm);
        env->GetLongArrayRegion(models, 0, nm, mh.data());
        if ((size_t)nm == nlang && std::all_of(mh.begin(), mh.end(), [](jlong m) { return m != 0; })) {
            for (size_t i = 0; i < nlang; ++i) nin.push_back({(droneswipe::NeuralLm*)(uintptr_t)mh[i], s->dicts[i]});
        }
        if (jtokens) {
            jsize nt = env->GetArrayLength(jtokens);
            for (jsize i = 0; i < nt; ++i) {
                auto js = (jstring)env->GetObjectArrayElement(jtokens, i);
                tokens.push_back(jstr(env, js));
                if (js) env->DeleteLocalRef(js);
            }
        }
        if (jfusion && env->GetArrayLength(jfusion) >= 7) {
            float p[7];
            env->GetFloatArrayRegion(jfusion, 0, 7, p);
            fusion = {p[0], p[1], p[2], p[3], p[4], p[5], p[6]};
        }
    }

    auto t0 = std::chrono::steady_clock::now();
    // the whole final beam is ranked below; the engine's own top-k would cut it by its internal score
    auto res = s->engine.recognize(x.data(), y.data(), t.data(), (size_t)n, {}, std::max(beam, 32), beam);
    auto bonus = droneswipe::language_bonus(s->dicts, context, prior, kappa, cap);
    // fusion reranks a longer list (tools/swipe-replay tuned it on the 40 best)
    auto cands = droneswipe::rank_candidates(res, s->dicts, bonus, context, nin.empty() ? maxResults : std::max(maxResults, 40),
                                             user, s->tie_margin);
    if (!nin.empty()) {
        auto t1 = std::chrono::steady_clock::now();
        droneswipe::fuse_neural(cands, s->dicts, user, nin, tokens, bos, fusion);
        s->neural_ms = (float)std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count();
        if ((int)cands.size() > maxResults) cands.resize(maxResults);
    } else {
        s->neural_ms = 0;
    }
    s->last_ms = (float)std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();

    jobjectArray out = env->NewObjectArray((jsize)cands.size(), strCls, nullptr);
    std::vector<float> scores(cands.size());
    std::vector<jint> dicts(cands.size());
    for (size_t i = 0; i < cands.size(); ++i) {
        jstring js = env->NewStringUTF(cands[i].word.c_str());
        env->SetObjectArrayElement(out, (jsize)i, js);
        env->DeleteLocalRef(js);
        scores[i] = cands[i].score;
        dicts[i] = cands[i].dict;
    }
    if (outScores) {
        jsize m = std::min((jsize)scores.size(), env->GetArrayLength(outScores));
        env->SetFloatArrayRegion(outScores, 0, m, scores.data());
    }
    if (outDict) {
        jsize m = std::min((jsize)dicts.size(), env->GetArrayLength(outDict));
        env->SetIntArrayRegion(outDict, 0, m, dicts.data());
    }
    return out;
}

JNIEXPORT jfloat JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeLastMillis(JNIEnv*, jclass, jlong h) {
    auto* s = (Session*)(uintptr_t)h;
    return s ? s->last_ms : 0.0f;
}

JNIEXPORT jfloat JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeLastNeuralMillis(JNIEnv*, jclass, jlong h) {
    auto* s = (Session*)(uintptr_t)h;
    return s ? s->neural_ms : 0.0f;
}

JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeSetTieMargin(JNIEnv*, jclass, jlong h, jfloat margin) {
    auto* s = (Session*)(uintptr_t)h;
    if (s) s->tie_margin = margin;
}

// Next-word prediction / completion for tap typing over the given dictionaries (the enabled
// languages). context: words before the cursor, oldest first; prefix: the letters typed of the
// current word (may be empty). Returns words best first and writes their scores (log probability
// plus language log posterior) and dictionary indices. Only reads the dictionaries, so it can run
// next to a swipe decode.
JNIEXPORT jobjectArray JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativePredict(JNIEnv* env, jclass, jlongArray dicts, jobjectArray jcontext,
                                                           jstring prefix, jint maxResults, jfloatArray outScores,
                                                           jintArray outDict) {
    jclass strCls = env->FindClass("java/lang/String");
    std::vector<KpDictionary*> ds;
    if (dicts) {
        jsize nd = env->GetArrayLength(dicts);
        std::vector<jlong> dh(nd);
        env->GetLongArrayRegion(dicts, 0, nd, dh.data());
        for (jlong x : dh) if (x) ds.push_back((KpDictionary*)(uintptr_t)x);
    }
    if (ds.empty() || maxResults <= 0) return env->NewObjectArray(0, strCls, nullptr);
    std::vector<std::string> context;
    if (jcontext) {
        jsize nc = env->GetArrayLength(jcontext);
        for (jsize i = 0; i < nc; ++i) {
            auto js = (jstring)env->GetObjectArrayElement(jcontext, i);
            context.push_back(droneswipe::to_lower_utf8(jstr(env, js)));
            if (js) env->DeleteLocalRef(js);
        }
    }
    auto preds = droneswipe::predict_words(ds, context, droneswipe::to_lower_utf8(jstr(env, prefix)), maxResults);
    jobjectArray out = env->NewObjectArray((jsize)preds.size(), strCls, nullptr);
    std::vector<float> scores(preds.size());
    std::vector<jint> idx(preds.size());
    for (size_t i = 0; i < preds.size(); ++i) {
        jstring js = env->NewStringUTF(preds[i].word.c_str());
        env->SetObjectArrayElement(out, (jsize)i, js);
        env->DeleteLocalRef(js);
        scores[i] = preds[i].score;
        idx[i] = preds[i].dict;
    }
    if (outScores) env->SetFloatArrayRegion(outScores, 0, std::min((jsize)scores.size(), env->GetArrayLength(outScores)), scores.data());
    if (outDict) env->SetIntArrayRegion(outDict, 0, std::min((jsize)idx.size(), env->GetArrayLength(outDict)), idx.data());
    return out;
}

// Neural next-word model (neural_lm.hpp): <lang>.lmb + <lang>.lmv. Returns 0 on failure.
JNIEXPORT jlong JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeLoadNeural(JNIEnv* env, jclass, jstring lmbPath, jstring lmvPath) {
    std::string err;
    auto t0 = std::chrono::steady_clock::now();
    auto lm = droneswipe::NeuralLm::load(jstr(env, lmbPath), jstr(env, lmvPath), &err);
    if (!lm) {
        LOGE("neural LM: %s", err.c_str());
        return 0;
    }
    LOGI("neural LM loaded: %d words, %s kernel, %.0f ms", lm->vocab(), droneswipe::neural_kernel_name(),
         std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    return (jlong)(uintptr_t)lm.release();
}

JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativeFreeNeural(JNIEnv*, jclass, jlong h) {
    delete (droneswipe::NeuralLm*)(uintptr_t)h;
}

// Next-word prediction / completion with the neural models of the enabled languages (models[i]
// with the .kpd dictionary dicts[i] of the same language, 0 = none). context: normalized tokens
// before the cursor, oldest first (lowercase words, "<num>", punctuation); bos: they start at the
// beginning of the text. Writes scores (log probability plus language log posterior) and model
// indices like nativePredict.
JNIEXPORT jobjectArray JNICALL
Java_helium314_keyboard_latin_swipe_FutoSwipe_nativePredictNeural(JNIEnv* env, jclass, jlongArray models, jlongArray dicts,
                                                                 jobjectArray jcontext, jboolean bos, jstring prefix,
                                                                 jint maxResults, jfloatArray outScores, jintArray outLang) {
    jclass strCls = env->FindClass("java/lang/String");
    std::vector<droneswipe::NeuralLanguageInput> langs;
    if (models) {
        jsize n = env->GetArrayLength(models);
        std::vector<jlong> mh(n), dh(n, 0);
        env->GetLongArrayRegion(models, 0, n, mh.data());
        if (dicts && env->GetArrayLength(dicts) >= n) env->GetLongArrayRegion(dicts, 0, n, dh.data());
        for (jsize i = 0; i < n; ++i)
            langs.push_back({(droneswipe::NeuralLm*)(uintptr_t)mh[i], (const KpDictionary*)(uintptr_t)dh[i]});
    }
    for (auto& l : langs) if (!l.model) return env->NewObjectArray(0, strCls, nullptr);
    if (langs.empty() || maxResults <= 0) return env->NewObjectArray(0, strCls, nullptr);
    std::vector<std::string> context;
    if (jcontext) {
        jsize nc = env->GetArrayLength(jcontext);
        for (jsize i = 0; i < nc; ++i) {
            auto js = (jstring)env->GetObjectArrayElement(jcontext, i);
            context.push_back(jstr(env, js));
            if (js) env->DeleteLocalRef(js);
        }
    }
    auto preds = droneswipe::predict_neural(langs, context, bos, droneswipe::to_lower_utf8(jstr(env, prefix)), maxResults);
    static int calls = 0;
    if (++calls % 50 == 0) {
        for (auto& l : langs)
            LOGI("neural LM %d words: %ld steps, %ld window restarts, %ld cached, window %zu, context %zu%s", l.model->vocab(),
                 l.model->steps, l.model->restarts, l.model->cached, l.model->window().size(), context.size(), bos ? " +bos" : "");
    }
    jobjectArray out = env->NewObjectArray((jsize)preds.size(), strCls, nullptr);
    std::vector<float> scores(preds.size());
    std::vector<jint> idx(preds.size());
    for (size_t i = 0; i < preds.size(); ++i) {
        jstring js = env->NewStringUTF(preds[i].word.c_str());
        env->SetObjectArrayElement(out, (jsize)i, js);
        env->DeleteLocalRef(js);
        scores[i] = preds[i].score;
        idx[i] = preds[i].lang;
    }
    if (outScores) env->SetFloatArrayRegion(outScores, 0, std::min((jsize)scores.size(), env->GetArrayLength(outScores)), scores.data());
    if (outLang) env->SetIntArrayRegion(outLang, 0, std::min((jsize)idx.size(), env->GetArrayLength(outLang)), idx.data());
    return out;
}

}  // extern "C"
