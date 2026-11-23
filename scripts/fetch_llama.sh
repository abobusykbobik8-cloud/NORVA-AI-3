#!/bin/sh
# Клонирует llama.cpp на версию из llama.cpp.version в third_party/llama.cpp.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="$(tr -d ' \n' < "$ROOT/llama.cpp.version")"
DEST="$ROOT/third_party/llama.cpp"
if [ -d "$DEST/.git" ]; then
  git -C "$DEST" fetch --depth 1 origin tag "$REF"
  git -C "$DEST" checkout -q "$REF"
else
  mkdir -p "$ROOT/third_party"
  git clone --depth 1 --branch "$REF" https://github.com/ggml-org/llama.cpp "$DEST"
fi
echo "llama.cpp: $(git -C "$DEST" describe --tags --always)"
