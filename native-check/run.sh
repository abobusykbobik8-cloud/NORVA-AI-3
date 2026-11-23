#!/bin/sh
# 1) настоящий тест UTF-8 буфера; 2) проверка синтаксиса JNI-файла против заглушек (не против настоящего llama.h).
set -e
cd "$(dirname "$0")"
CPP=../engine-llama/src/main/cpp
g++ -std=c++17 -Wall -Wextra -O1 -I"$CPP" -o /tmp/utf8_stream_test utf8_stream_test.cpp
/tmp/utf8_stream_test
g++ -std=c++17 -Wall -Wextra -fsyntax-only -Istubs -I"$CPP" "$CPP/norva_llama.cpp"
echo "norva_llama.cpp: syntax OK (against stubs)"
