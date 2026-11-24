// JNI-мост к llama.cpp. Сверен вручную с include/llama.h (master, ноябрь 2026, тег b12591 в llama.cpp.version).
// Поля и функции, менявшиеся между версиями (load_mode/use_mmap, flash_attn_type/flash_attn, число аргументов
// llama_sampler_init_penalties), выбираются на этапе компиляции через SFINAE. Настоящая проверка:
// native-check/verify_real_llama.sh и job llama-api-check в CI.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include "llama.h"
#include "utf8_stream.h"
#include "vision.h"

#define TAG "NorvaLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

enum Rc : jint {
    RC_EOS = 0,
    RC_LENGTH = 1,
    RC_CANCELLED = 2,
    RC_INTERNAL = -1,
    RC_PROMPT_TOO_LONG = -2,
    RC_DECODE = -3,
};

struct Engine {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int nCtx = 0;
    int threads = 4;
    int nBatch = 512;
    std::atomic<bool> cancel{false};
};

// Реестр живых движков: nativeCancel из другого потока не трогает уже освобождённую память.
std::mutex g_mu;
std::set<Engine*> g_live;

using Clock = std::chrono::steady_clock;

jlong msSince(Clock::time_point t) {
    return static_cast<jlong>(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t).count());
}

void llamaLog(ggml_log_level level, const char* text, void*) {
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    const int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                   : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                                   : ANDROID_LOG_INFO;
    __android_log_write(prio, TAG, text);
}

std::string pieceOf(const llama_vocab* vocab, llama_token tok) {
    char buf[128];
    int n = llama_token_to_piece(vocab, tok, buf, static_cast<int32_t>(sizeof(buf)), 0, true);
    if (n >= 0) return std::string(buf, static_cast<size_t>(n));
    std::string big(static_cast<size_t>(-n), '\0');
    n = llama_token_to_piece(vocab, tok, &big[0], static_cast<int32_t>(big.size()), 0, true);
    if (n < 0) return std::string();
    big.resize(static_cast<size_t>(n));
    return big;
}

// --- совместимость версий llama.h ---
// Загрузка через mmap: новое поле load_mode (0 = NONE, 1 = MMAP) или старое use_mmap.
template <class P>
auto setMmap(P& p, bool on, int) -> decltype((void)p.load_mode, void()) {
    p.load_mode = static_cast<decltype(p.load_mode)>(on ? 1 : 0);
}
template <class P>
auto setMmap(P& p, bool on, long) -> decltype((void)p.use_mmap, void()) {
    p.use_mmap = on;
}

// Flash attention обязателен для квантованного V-кэша: flash_attn_type (1 = ENABLED) или старое bool-поле flash_attn.
template <class P>
auto enableFlashAttn(P& p, int) -> decltype((void)p.flash_attn_type, void()) {
    p.flash_attn_type = static_cast<decltype(p.flash_attn_type)>(1);
}
template <class P>
auto enableFlashAttn(P& p, long) -> decltype((void)p.flash_attn, void()) {
    p.flash_attn = true;
}

// В новом API первым аргументом идёт n_vocab, в старом его нет.
template <class V, class I>
auto makePenalties(const V* vocab, I lastN, float repeat, float present, int)
    -> decltype(llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), lastN, repeat, 0.0f, present)) {
    return llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), lastN, repeat, 0.0f, present);
}
template <class V, class I>
auto makePenalties(const V*, I lastN, float repeat, float present, long)
    -> decltype(llama_sampler_init_penalties(lastN, repeat, 0.0f, present)) {
    return llama_sampler_init_penalties(lastN, repeat, 0.0f, present);
}

// Порядок как в llama-server: штрафы, top-k, top-p, min-p, температура, выбор токена.
llama_sampler* makeSampler(const llama_vocab* vocab, float temp, float topP, int topK, float minP,
                           float repeat, float presence, int seed) {
    llama_sampler* s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeat > 1.0f || presence != 0.0f) {
        llama_sampler_chain_add(s, makePenalties(vocab, 256, repeat > 1.0f ? repeat : 1.0f, presence, 0));
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(s, llama_sampler_init_greedy());
        return s;
    }
    if (topK > 0) llama_sampler_chain_add(s, llama_sampler_init_top_k(topK));
    if (topP < 1.0f) llama_sampler_chain_add(s, llama_sampler_init_top_p(topP, 1));
    if (minP > 0.0f) llama_sampler_chain_add(s, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(s, llama_sampler_init_temp(temp));
    const uint32_t sd = seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
    llama_sampler_chain_add(s, llama_sampler_init_dist(sd));
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_ai_norva_engine_LlamaNative_nativeLoad(
    JNIEnv* env, jobject, jstring jpath, jint nCtx, jint nThreads, jint nThreadsBatch,
    jboolean useMmap, jboolean kvQ8) {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(llamaLog, nullptr);
        configureVisionLogging();
        llama_backend_init();
    });

    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if (!cpath) return 0;
    const std::string path(cpath);
    env->ReleaseStringUTFChars(jpath, cpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // только CPU; GPU (OpenCL/Adreno) включаем после замеров
    setMmap(mp, useMmap == JNI_TRUE, 0);
    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("llama_model_load_from_file failed: %s", path.c_str());
        return 0;
    }

    // Квантованный KV-кэш экономит память, но требует flash attention. Если комбинация не поддержана
    // для этой архитектуры, пробуем обычный f16-кэш, а не отказываем в загрузке.
    auto makeCtx = [&](bool quantKv) -> llama_context* {
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(nCtx);
        cp.n_batch = nCtx <= 2048 ? 128 : 512;
        cp.n_ubatch = nCtx <= 2048 ? 64 : 256;  // меньше ubatch - меньше буфер вычислений в памяти
        cp.n_threads = nThreads;
        cp.n_threads_batch = nThreadsBatch;
        if (quantKv) {
            cp.type_k = GGML_TYPE_Q8_0;
            cp.type_v = GGML_TYPE_Q8_0;
            enableFlashAttn(cp, 0);
        }
        return llama_init_from_model(model, cp);
    };
    llama_context* ctx = makeCtx(kvQ8 == JNI_TRUE);
    if (!ctx && kvQ8 == JNI_TRUE) {
        LOGW("контекст с q8_0 KV-кэшем не создан, повтор с f16 (n_ctx=%d)", static_cast<int>(nCtx));
        ctx = makeCtx(false);
    }
    if (!ctx) {
        LOGE("llama_init_from_model failed (n_ctx=%d)", static_cast<int>(nCtx));
        llama_model_free(model);
        return 0;
    }

    auto* e = new Engine();
    llama_set_abort_callback(ctx, [](void* p) { return static_cast<Engine*>(p)->cancel.load(); }, e);
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->nCtx = static_cast<int>(llama_n_ctx(ctx));
    e->nBatch = static_cast<int>(llama_n_batch(ctx));
    {
        std::lock_guard<std::mutex> g(g_mu);
        g_live.insert(e);
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL Java_ai_norva_engine_LlamaNative_nativeSetThreads(
    JNIEnv*, jobject, jlong handle, jint nThreads, jint nThreadsBatch) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e && e->ctx) { e->threads = nThreadsBatch; llama_set_n_threads(e->ctx, nThreads, nThreadsBatch); }
}

JNIEXPORT void JNICALL Java_ai_norva_engine_LlamaNative_nativeCancel(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    std::lock_guard<std::mutex> g(g_mu);
    if (g_live.count(e)) e->cancel.store(true);
}

JNIEXPORT void JNICALL Java_ai_norva_engine_LlamaNative_nativeUnload(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    {
        std::lock_guard<std::mutex> g(g_mu);
        if (g_live.erase(e) == 0) return;
    }
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

JNIEXPORT jstring JNICALL Java_ai_norva_engine_LlamaNative_nativeSystemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// stats: [promptTokens, generatedTokens, promptMillis, decodeMillis]
JNIEXPORT jint JNICALL Java_ai_norva_engine_LlamaNative_nativeGenerate(
    JNIEnv* env, jobject, jlong handle, jbyteArray jprompt, jint maxTokens, jfloat temp, jfloat topP,
    jint topK, jfloat minP, jfloat repeatPenalty, jfloat presencePenalty, jint seed, jobject sink,
    jlongArray jstats, jstring jphoto, jstring jprojector) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || !e->ctx) return RC_INTERNAL;
    e->cancel.store(false);

    auto setStats = [&](jlong pTok, jlong gTok, jlong pMs, jlong dMs) {
        const jlong v[4] = {pTok, gTok, pMs, dMs};
        env->SetLongArrayRegion(jstats, 0, 4, v);
    };
    setStats(0, 0, 0, 0);

    // Промпт приходит байтами UTF-8: NewStringUTF/GetStringUTFChars работают с modified UTF-8 и ломают эмодзи.
    jbyte* pb = env->GetByteArrayElements(jprompt, nullptr);
    if (!pb) return RC_INTERNAL;
    const jsize plen = env->GetArrayLength(jprompt);
    const std::string prompt(reinterpret_cast<const char*>(pb), static_cast<size_t>(plen));
    env->ReleaseByteArrayElements(jprompt, pb, JNI_ABORT);

    jclass cls = env->GetObjectClass(sink);
    jmethodID mid = env->GetMethodID(cls, "onToken", "([B)Z");
    if (!mid) return RC_INTERNAL;

    auto emit = [&](const std::string& s) -> bool {
        if (s.empty()) return true;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
        if (!arr) return false;
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
        const jboolean cont = env->CallBooleanMethod(sink, mid, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return cont == JNI_TRUE;
    };

    llama_memory_clear(llama_get_memory(e->ctx), true);  // без повторного использования KV-префикса

    int nPrompt = 0;
    int nextPosition = 0;
    const auto tPrefill = Clock::now();
    if (jphoto) {
        if (!jprojector) return -4;
        auto path = [&](jstring value) {
            const char* bytes = env->GetStringUTFChars(value, nullptr);
            if (!bytes) return std::string();
            std::string result(bytes);
            env->ReleaseStringUTFChars(value, bytes);
            return result;
        };
        int rc = prefillPhoto(e->model, e->ctx, prompt, path(jphoto), path(jprojector),
                              e->threads, e->cancel, nPrompt, nextPosition);
        if (e->cancel.load()) rc = RC_CANCELLED;
        if (rc != 0) { setStats(nPrompt, 0, msSince(tPrefill), 0); return rc; }
    } else {
        const int32_t needed = -llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                               nullptr, 0, true, true);
        if (needed <= 0) return RC_INTERNAL;
        std::vector<llama_token> toks(static_cast<size_t>(needed));
        if (llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), toks.data(),
                           static_cast<int32_t>(toks.size()), true, true) < 0) {
            return RC_INTERNAL;
        }
        nPrompt = static_cast<int>(toks.size());
        if (nPrompt + 8 > e->nCtx) {
            setStats(nPrompt, 0, 0, 0);
            return RC_PROMPT_TOO_LONG;
        }

        for (int i = 0; i < nPrompt; i += e->nBatch) {
            if (e->cancel.load()) {
                setStats(nPrompt, 0, msSince(tPrefill), 0);
                return RC_CANCELLED;
            }
            const int n = std::min(e->nBatch, nPrompt - i);
            llama_batch b = llama_batch_get_one(toks.data() + i, n);
            if (llama_decode(e->ctx, b) != 0) {
                LOGE("prefill decode failed at %d", i);
                setStats(nPrompt, 0, msSince(tPrefill), 0);
                return e->cancel.load() ? RC_CANCELLED : RC_DECODE;
            }
        }
    }
    const int budget = std::min<int>(maxTokens, e->nCtx - nPrompt);
    const jlong promptMs = msSince(tPrefill);

    llama_sampler* smpl = makeSampler(e->vocab, temp, topP, topK, minP, repeatPenalty, presencePenalty, seed);
    Utf8Stream utf8;
    jint rc = RC_LENGTH;
    int nGen = 0;
    const auto tDecode = Clock::now();
    while (nGen < budget) {
        if (e->cancel.load()) { rc = RC_CANCELLED; break; }
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) { rc = RC_EOS; break; }
        nGen++;
        const std::string piece = pieceOf(e->vocab, tok);
        if (!emit(utf8.push(piece.data(), piece.size()))) { rc = RC_CANCELLED; break; }
        llama_batch b = llama_batch_get_one(&tok, 1);
        int32_t pos[4] = {nextPosition, nextPosition, nextPosition, 0};
        if (jphoto) { b.pos = pos; ++nextPosition; }
        if (llama_decode(e->ctx, b) != 0) {
            LOGE("decode failed at token %d", nGen);
            rc = e->cancel.load() ? RC_CANCELLED : RC_DECODE;
            break;
        }
    }
    if (rc == RC_LENGTH || rc == RC_EOS) emit(utf8.flush());
    llama_sampler_free(smpl);
    setStats(nPrompt, nGen, promptMs, msSince(tDecode));
    return rc;
}

}  // extern "C"
