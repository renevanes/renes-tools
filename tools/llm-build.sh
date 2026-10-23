#!/usr/bin/env bash
# Bouwt de lokale AI-motor (tools/llm/llm.cpp op llama.cpp) als volledig statisch programma voor arm64 Android,
# met zig (geen NDK), net als tools/whisper-build.sh. Ook een x86_64-versie om hier te testen.
# Gebruik: ./tools/llm-build.sh            Vereist: pip install ziglang ; cmake ; ninja ; git
# Uitvoer: tools/llm-out/libllm.so  (kopieer naar app/lib/arm64-v8a/libllm.so)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="${LLAMA_TAG:-b11500}"                       # vaste versie van llama.cpp
COMMIT="097f5b5332f46559f1ee11d9d64e48a5065b2989" # moet bij die versie horen (controle)
SRC="$HERE/lcpp"
ARCH="${1:-generic+v8_2a+dotprod+fullfp16}"   # zig -mcpu-notatie, zoals whisper
WRAP="$HERE/zigwrap"
OUT="$HERE/llm-out"
mkdir -p "$WRAP" "$OUT"

if [ ! -d "$SRC/.git" ]; then git clone -q --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp "$SRC"; fi
got="$(git -C "$SRC" rev-parse HEAD)"
[ "$got" = "$COMMIT" ] || { echo "llama.cpp $TAG heeft commit $got, verwacht $COMMIT" >&2; exit 1; }

for t in aarch64-linux-musl x86_64-linux-musl; do
  printf '#!/bin/sh\nexec python3 -m ziglang cc  -target %s "$@"\n' "$t" > "$WRAP/$t-cc"
  printf '#!/bin/sh\nexec python3 -m ziglang c++ -target %s "$@"\n' "$t" > "$WRAP/$t-c++"
done
for t in ar ranlib; do printf '#!/bin/sh\nexec python3 -m ziglang %s "$@"\n' "$t" > "$WRAP/zig-$t"; done
chmod +x "$WRAP"/*

COMMON=(
  -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLAMA_SRC="$SRC"
  -DBUILD_SHARED_LIBS=OFF -DGGML_STATIC=ON
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_METAL=OFF -DGGML_BLAS=OFF -DGGML_LLAMAFILE=ON
  -DGGML_VULKAN=OFF -DGGML_CUDA=OFF -DGGML_BACKEND_DL=OFF -DGGML_CCACHE=OFF
  -DCMAKE_AR="$WRAP/zig-ar" -DCMAKE_RANLIB="$WRAP/zig-ranlib"
  -DCMAKE_EXE_LINKER_FLAGS="-static -s"
)

# --- arm64 Android (statisch musl) ---
B="$HERE/build-llm-arm64"
cmake -S "$HERE/llm" -B "$B" "${COMMON[@]}" \
  -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_C_COMPILER="$WRAP/aarch64-linux-musl-cc" -DCMAKE_CXX_COMPILER="$WRAP/aarch64-linux-musl-c++" \
  -DCMAKE_C_FLAGS="-mcpu=$ARCH" -DCMAKE_CXX_FLAGS="-mcpu=$ARCH"
cmake --build "$B" --target llm -j"$(nproc)"
cp "$B/llm" "$OUT/libllm.so"

# --- x86_64 (statisch musl) om hier te testen ---
if [ "${SKIP_X86:-0}" != 1 ]; then
  BX="$HERE/build-llm-x86_64"
  cmake -S "$HERE/llm" -B "$BX" "${COMMON[@]}" \
    -DCMAKE_C_COMPILER="$WRAP/x86_64-linux-musl-cc" -DCMAKE_CXX_COMPILER="$WRAP/x86_64-linux-musl-c++" \
    -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON
  cmake --build "$BX" --target llm -j"$(nproc)"
  cp "$BX/llm" "$OUT/llm-x86_64"
fi
file "$OUT"/* ; ls -l "$OUT"
