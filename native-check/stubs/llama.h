// ЗАГЛУШКА: повторяет сигнатуры, которые использует norva_llama.cpp, по include/llama.h (master, ноябрь 2026).
// Это не llama.cpp и не доказательство совместимости: см. native-check/verify_real_llama.sh.
// Заглушка с сигнатурами llama.cpp в том виде, как их знает автор (API 2025+).
// Проверяет только внутреннюю согласованность norva_llama.cpp, а не соответствие настоящему llama.h.
#pragma once
#include <cstdint>
#include <cstddef>
typedef int32_t llama_token;
struct llama_model; struct llama_context; struct llama_vocab; struct llama_sampler; struct llama_memory_i;
typedef llama_memory_i* llama_memory_t;
enum ggml_log_level { GGML_LOG_LEVEL_DEBUG = 1, GGML_LOG_LEVEL_INFO = 2, GGML_LOG_LEVEL_WARN = 3, GGML_LOG_LEVEL_ERROR = 4 };
enum ggml_type { GGML_TYPE_F16 = 1, GGML_TYPE_Q8_0 = 8 };
enum llama_flash_attn_type { LLAMA_FLASH_ATTN_TYPE_AUTO = -1, LLAMA_FLASH_ATTN_TYPE_DISABLED = 0, LLAMA_FLASH_ATTN_TYPE_ENABLED = 1 };
#define LLAMA_DEFAULT_SEED 0xFFFFFFFFu
enum llama_load_mode { LLAMA_LOAD_MODE_AUTO = -1, LLAMA_LOAD_MODE_NONE = 0, LLAMA_LOAD_MODE_MMAP = 1 };
struct llama_model_params { int32_t n_gpu_layers; enum llama_load_mode load_mode; };
struct llama_context_params {
    uint32_t n_ctx, n_batch, n_ubatch; int32_t n_threads, n_threads_batch;
    ggml_type type_k, type_v; llama_flash_attn_type flash_attn_type;
};
struct llama_sampler_chain_params { bool no_perf; };
struct llama_batch { int32_t* pos; int32_t n_tokens; llama_token* token; };
typedef void (*ggml_log_callback)(ggml_log_level, const char*, void*);
void llama_log_set(ggml_log_callback, void*);
void llama_backend_init();
llama_model_params llama_model_default_params();
llama_context_params llama_context_default_params();
llama_model* llama_model_load_from_file(const char*, llama_model_params);
void llama_model_free(llama_model*);
llama_context* llama_init_from_model(llama_model*, llama_context_params);
void llama_free(llama_context*);
const llama_vocab* llama_model_get_vocab(const llama_model*);
uint32_t llama_n_ctx(const llama_context*);
void llama_set_n_threads(llama_context*, int32_t, int32_t);
llama_memory_t llama_get_memory(const llama_context*);
void llama_memory_clear(llama_memory_t, bool);
int32_t llama_tokenize(const llama_vocab*, const char*, int32_t, llama_token*, int32_t, bool, bool);
int32_t llama_token_to_piece(const llama_vocab*, llama_token, char*, int32_t, int32_t, bool);
bool llama_vocab_is_eog(const llama_vocab*, llama_token);
llama_batch llama_batch_get_one(llama_token*, int32_t);
int32_t llama_decode(llama_context*, llama_batch);
llama_sampler_chain_params llama_sampler_chain_default_params();
llama_sampler* llama_sampler_chain_init(llama_sampler_chain_params);
void llama_sampler_chain_add(llama_sampler*, llama_sampler*);
llama_sampler* llama_sampler_init_penalties(int32_t n_vocab, int32_t penalty_last_n, float penalty_repeat, float penalty_freq, float penalty_present);
int32_t llama_vocab_n_tokens(const struct llama_vocab*);
uint32_t llama_n_batch(const struct llama_context*);
llama_sampler* llama_sampler_init_greedy();
llama_sampler* llama_sampler_init_top_k(int32_t);
llama_sampler* llama_sampler_init_top_p(float, size_t);
llama_sampler* llama_sampler_init_min_p(float, size_t);
llama_sampler* llama_sampler_init_temp(float);
llama_sampler* llama_sampler_init_dist(uint32_t);
llama_token llama_sampler_sample(llama_sampler*, llama_context*, int32_t);
void llama_sampler_free(llama_sampler*);
const char* llama_print_system_info();

void llama_set_abort_callback(llama_context*, bool (*callback)(void*), void*);
