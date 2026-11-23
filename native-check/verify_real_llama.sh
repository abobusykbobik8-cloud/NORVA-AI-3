#!/bin/sh
# Проверка norva_llama.cpp против НАСТОЯЩИХ llama.h и jni.h (только синтаксис и типы, без линковки).
# Заглушкой остаётся лишь android/log.h. Это единственная проверка, которая ловит расхождение с API llama.cpp.
# Использование: sh native-check/verify_real_llama.sh third_party/llama.cpp
set -e
LLAMA="${1:?укажите путь к checkout llama.cpp}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../engine-llama/src/main/cpp"

if [ -n "$JAVA_HOME" ]; then JH="$JAVA_HOME"; else
  JAVAC="$(command -v javac || true)"
  [ -n "$JAVAC" ] || { echo "нужен полный JDK (javac) или JAVA_HOME"; exit 2; }
  JH="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")"
fi
[ -f "$JH/include/jni.h" ] || { echo "нет $JH/include/jni.h: установите полный JDK"; exit 2; }
[ -f "$LLAMA/include/llama.h" ] || { echo "нет $LLAMA/include/llama.h: это не checkout llama.cpp"; exit 2; }

g++ -std=c++17 -Wall -Wextra -fsyntax-only \
  -I"$LLAMA/include" -I"$LLAMA/ggml/include" \
  -I"$JH/include" -I"$JH/include/linux" \
  -I"$HERE/android-log-stub" -I"$CPP" \
  "$CPP/norva_llama.cpp"
echo "norva_llama.cpp компилируется против llama.cpp: $(git -C "$LLAMA" describe --tags --always 2>/dev/null || echo неизвестная версия)"
