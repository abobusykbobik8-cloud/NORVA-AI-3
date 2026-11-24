#include "vision.h"
#include "mtmd-helper.h"

// mtmd's default debug logger includes the entire user prompt.
void configureVisionLogging() {
    mtmd_helper_log_set([](ggml_log_level, const char*, void*) {}, nullptr);
}

int prefillPhoto(llama_model* model, llama_context* ctx, const std::string& prompt,
                 const std::string& photo, const std::string& projector, int threads,
                 std::atomic<bool>& cancelled, int& tokenCount, int& nextPosition) {
    auto params = mtmd_context_params_default();
    params.use_gpu = false;
    params.warmup = false;
    params.n_threads = threads;
    // Small screenshots otherwise collapse to too few visual patches to read short labels.
    params.image_min_tokens = 384;
    params.image_max_tokens = 768;
    params.media_marker = "<__norva_photo__>";
    params.progress_callback = [](float, void* p) { return !static_cast<std::atomic<bool>*>(p)->load(); };
    params.progress_callback_user_data = &cancelled;
    mtmd::context_ptr vision(mtmd_init_from_file(projector.c_str(), model, params));
    if (cancelled.load()) return 2;
    if (!vision || !mtmd_support_vision(vision.get())) return -4;
    auto media = mtmd_helper_bitmap_init_from_file(vision.get(), photo.c_str(), false, mtmd_helper_init_opt_default());
    mtmd::bitmap_ptr bitmap(media.bitmap);
    if (media.video_ctx) { mtmd_helper_video_free(media.video_ctx); return -4; }
    if (!bitmap) return -4;
    mtmd::input_chunks_ptr chunks(mtmd_input_chunks_init());
    const mtmd_bitmap* input = bitmap.get();
    mtmd_input_text text{prompt.data(), prompt.size(), true, true};
    if (!chunks || mtmd_tokenize(vision.get(), chunks.get(), &text, &input, 1)) return -4;
    tokenCount = static_cast<int>(mtmd_helper_get_n_tokens(chunks.get()));
    if (tokenCount + 8 > static_cast<int>(llama_n_ctx(ctx))) return -2;
    for (size_t i = 0; i < mtmd_input_chunks_size(chunks.get()); ++i) {
        if (cancelled.load()) return 2;
        auto* chunk = mtmd_input_chunks_get(chunks.get(), i);
        bool last = i + 1 == mtmd_input_chunks_size(chunks.get());
        if (mtmd_helper_eval_chunk_single(vision.get(), ctx, chunk, nextPosition, 0,
                                          llama_n_batch(ctx), last, &nextPosition)) return -4;
    }
    return 0;
}
