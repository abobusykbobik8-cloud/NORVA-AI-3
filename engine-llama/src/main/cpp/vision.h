#pragma once
#include "llama.h"
#include <atomic>
#include <string>

void configureVisionLogging();

int prefillPhoto(llama_model* model, llama_context* ctx, const std::string& prompt,
                 const std::string& photo, const std::string& projector, int threads,
                 std::atomic<bool>& cancelled, int& tokenCount, int& nextPosition);
