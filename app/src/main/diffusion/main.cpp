#include "stable-diffusion.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
#ifdef __ANDROID__
#include <signal.h>
#include <sys/prctl.h>
#include <unistd.h>
#endif

// One process per inference. Native weights, intermediate tensors and worker threads
// are reclaimed by the OS even after cancellation or allocator failure.
int main(int argc, char** argv) {
    #ifdef __ANDROID__
    const auto parent = getppid();
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent || parent == 1) return 7;
#endif
    const bool video = (argc == 6 || argc == 7) && std::string(argv[1]) == "video";
    const bool image = argc == 4 && std::string(argv[1]) == "image";
    if (!video && !image) return 2;
    std::string prompt;
    std::getline(std::cin, prompt);
    if (prompt.empty() || prompt.size() > 8192) return 2;
    const int width = video ? 256 : 384, height = video ? 256 : 384;
    const int count = video ? 9 : 1;
    sd_ctx_params_t ctx_params;
    sd_ctx_params_init(&ctx_params);
    ctx_params.n_threads = std::max(1, std::min(4, sd_get_num_physical_cores()));
    ctx_params.free_params_immediately = true;
    ctx_params.vae_decode_only = image;
    if (image) ctx_params.model_path = argv[3];
    else {
        ctx_params.diffusion_model_path = argv[3];
        ctx_params.t5xxl_path = argv[4];
        ctx_params.vae_path = argv[5];
        ctx_params.flow_shift = 3.f;
    }
    sd_set_log_callback([](sd_log_level_t level, const char* msg, void*) {
        if (level == SD_LOG_ERROR && msg) std::fprintf(stderr, "ENGINE %.300s\n", msg);
    }, nullptr);
    sd_set_progress_callback([](int step, int steps, float, void*) {
        std::printf("STEP %d %d\n", step, steps); std::fflush(stdout);
    }, nullptr);
    sd_ctx_t* ctx = new_sd_ctx(&ctx_params);
    if (!ctx) return 3;
    sd_image_t* frames = nullptr;
    int actual = 1;
    auto seed = std::chrono::steady_clock::now().time_since_epoch().count() & 0x7fffffff;
    if (image) {
        sd_img_gen_params_t p;
        sd_img_gen_params_init(&p);
        p.prompt = prompt.c_str(); p.width = width; p.height = height; p.seed = seed;
        p.sample_params.sample_method = EULER_A_SAMPLE_METHOD;
        p.sample_params.scheduler = sd_get_default_scheduler(ctx);
        p.sample_params.sample_steps = 16; p.sample_params.guidance.txt_cfg = 6.f;
        frames = generate_image(ctx, &p);
    } else {
        sd_vid_gen_params_t p;
        sd_vid_gen_params_init(&p);
        std::vector<uint8_t> reference;
        if (argc == 7) {
            reference.resize(width * height * 3);
            std::ifstream source(argv[6], std::ios::binary);
            if (!source.read(reinterpret_cast<char*>(reference.data()), reference.size())) return 2;
            p.init_image = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 3, reference.data()};
        }
        p.prompt = prompt.c_str(); p.width = width; p.height = height; p.seed = seed; p.video_frames = count;
        p.sample_params.sample_method = EULER_SAMPLE_METHOD;
        p.sample_params.scheduler = sd_get_default_scheduler(ctx);
        p.sample_params.sample_steps = 12; p.sample_params.guidance.txt_cfg = 5.f;
        frames = generate_video(ctx, &p, &actual);
    }
    if (!frames || actual != count) return 4;
    std::ofstream out(argv[2], std::ios::binary);
    for (int i = 0; i < count; ++i) {
        const auto& frame = frames[i];
        if (!frame.data || frame.width != width || frame.height != height || frame.channel != 3) return 5;
        out.write(reinterpret_cast<const char*>(frame.data), width * height * 3);
        std::free(frame.data);
    }
    out.close();
    std::free(frames);
    free_sd_ctx(ctx);
    return out ? 0 : 6;
}
