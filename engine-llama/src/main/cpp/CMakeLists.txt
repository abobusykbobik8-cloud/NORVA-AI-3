cmake_minimum_required(VERSION 3.22.1)
project(norva_llama LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp клонируется скриптом scripts/fetch_llama.sh на тег из llama.cpp.version в third_party/llama.cpp.
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp не найден в ${LLAMA_DIR}. Выполните: sh scripts/fetch_llama.sh")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)   # OpenSSL нет в стабильном NDK API
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)   # llamafile не поддерживает Android
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)

# Набор инструкций ARM. Если на устройстве случится SIGILL, понизить до armv8.2-a+dotprod (см. docs/ENGINE.md).
if(NOT DEFINED NORVA_CPU_ARCH OR "${NORVA_CPU_ARCH}" STREQUAL "")
    set(NORVA_CPU_ARCH "armv8.2-a+dotprod+fp16+i8mm")
endif()
set(GGML_CPU_ARM_ARCH "${NORVA_CPU_ARCH}" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(norva_llama SHARED norva_llama.cpp)
target_include_directories(norva_llama PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include)
target_link_libraries(norva_llama PRIVATE llama log)
# Android 15+ с 16 КБ страницами памяти
target_link_options(norva_llama PRIVATE -Wl,-z,max-page-size=16384)
