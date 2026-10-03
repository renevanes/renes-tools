#!/usr/bin/env bash
# Cross-compile a fully static whisper-cli for arm64 Android using zig (no NDK).
# Usage: ./whisper-build.sh [CPU]   zig -mcpu syntax; default generic+v8_2a+dotprod+fullfp16
#        (safe baseline for any arm64: ./whisper-build.sh generic)
# Requires: pip install ziglang ; cmake ; ninja
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/wcpp"
ARCH="${1:-generic+v8_2a+dotprod+fullfp16}"
TAG="${ARCH//+/_}"
WRAP="$HERE/zigwrap"
OUT="$HERE/whisper-out"
mkdir -p "$WRAP" "$OUT"

# zig compiler wrappers (cmake wants a single executable path)
for t in aarch64-linux-musl x86_64-linux-musl; do
  cat > "$WRAP/$t-cc"  <<EOF
#!/bin/sh
exec python3 -m ziglang cc  -target $t "\$@"
EOF
  cat > "$WRAP/$t-c++" <<EOF
#!/bin/sh
exec python3 -m ziglang c++ -target $t "\$@"
EOF
done
for t in ar ranlib; do
  printf '#!/bin/sh\nexec python3 -m ziglang %s "$@"\n' "$t" > "$WRAP/zig-$t"
done
chmod +x "$WRAP"/*

COMMON=(
  -G Ninja -DCMAKE_BUILD_TYPE=Release
  -DBUILD_SHARED_LIBS=OFF -DGGML_STATIC=ON
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF -DWHISPER_BUILD_EXAMPLES=ON
  -DWHISPER_CURL=OFF -DWHISPER_SDL2=OFF -DWHISPER_FFMPEG=OFF
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_METAL=OFF -DGGML_BLAS=OFF
  -DGGML_VULKAN=OFF -DGGML_CUDA=OFF -DGGML_BACKEND_DL=OFF -DGGML_CCACHE=OFF
  -DCMAKE_AR="$WRAP/zig-ar" -DCMAKE_RANLIB="$WRAP/zig-ranlib"
  -DCMAKE_EXE_LINKER_FLAGS="-static -s"
)

# --- arm64 Android (static musl) ---
B="$HERE/build-arm64-$TAG"
cmake -S "$SRC" -B "$B" "${COMMON[@]}" \
  -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_C_COMPILER="$WRAP/aarch64-linux-musl-cc" \
  -DCMAKE_CXX_COMPILER="$WRAP/aarch64-linux-musl-c++" \
  -DCMAKE_C_FLAGS="-mcpu=$ARCH" -DCMAKE_CXX_FLAGS="-mcpu=$ARCH"
cmake --build "$B" --target whisper-cli -j"$(nproc)"
cp "$B/bin/whisper-cli" "$OUT/whisper-cli-arm64-$TAG"
cp "$B/bin/whisper-cli" "$OUT/libwhisper.so"   # APK: lib/arm64-v8a/libwhisper.so

# --- x86_64 (static musl) for local functional testing ---
if [ "${SKIP_X86:-0}" != 1 ]; then
  BX="$HERE/build-x86_64"
  cmake -S "$SRC" -B "$BX" "${COMMON[@]}" \
    -DCMAKE_C_COMPILER="$WRAP/x86_64-linux-musl-cc" \
    -DCMAKE_CXX_COMPILER="$WRAP/x86_64-linux-musl-c++" \
    -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON
  cmake --build "$BX" --target whisper-cli -j"$(nproc)"
  cp "$BX/bin/whisper-cli" "$OUT/whisper-cli-x86_64"
fi
file "$OUT"/* ; ls -l "$OUT"
