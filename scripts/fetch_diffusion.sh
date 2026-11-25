#!/bin/sh
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/third_party/stable-diffusion.cpp"
sd=43a70e819b9254dee0d017305d6992f6bb27f850
ggml=f5425c0ee5e582a7d64411f06139870bff3e52e0
if [ -f "$dest/.norva-revision" ] && [ "$(cat "$dest/.norva-revision")" = "$sd/$ggml" ]; then exit 0; fi
mkdir -p "$root/third_party"
stage="$(mktemp -d "$root/third_party/.diffusion-XXXXXX")"
trap 'rm -rf "$stage"' EXIT
fetch() {
    curl --fail --location --retry 3 "https://codeload.github.com/$1/tar.gz/$2" -o "$stage/src.tgz"
    mkdir -p "$3"
    tar -xzf "$stage/src.tgz" --strip-components=1 -C "$3"
}
fetch leejet/stable-diffusion.cpp "$sd" "$stage/sd"
fetch ggml-org/ggml "$ggml" "$stage/sd/ggml"
echo "$sd/$ggml" > "$stage/sd/.norva-revision"
if [ -e "$dest" ]; then echo "Unexpected existing diffusion checkout: $dest" >&2; exit 1; fi
mv "$stage/sd" "$dest"
