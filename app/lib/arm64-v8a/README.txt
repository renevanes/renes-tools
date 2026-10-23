whisper.cpp commit 60c0be6ac8fa71b1a2ae2dd938a31a34a508e774 (github.com/ggml-org/whisper.cpp, MIT-licentie)
Gebouwd met tools/whisper-build.sh (zig cc, statisch aarch64-linux-musl, geen NDK):
  libwhisper.so          = ./whisper-build.sh            (armv8.2-a + dotprod + fp16, snel)
  libwhisper_generic.so  = ./whisper-build.sh generic    (elke arm64-processor)
Het zijn uitvoerbare programma's met een .so-naam, zodat Android ze uitpakt in nativeLibraryDir.

llama.cpp b11500, commit 097f5b5332f46559f1ee11d9d64e48a5065b2989 (github.com/ggml-org/llama.cpp, MIT-licentie)
  libllm.so = ./tools/llm-build.sh  (tools/llm/llm.cpp op llama.cpp; armv8.2-a + dotprod + fp16, statisch musl)
  De lokale AI-motor van Tools: praat via stdin/stdout met de app (geen netwerk, geen server).
  Gebouwd met zig 0.17.0 (pip ziglang), cmake 3.28; getest met ./tools/llm/test.sh (x86_64) en onder qemu-aarch64.
