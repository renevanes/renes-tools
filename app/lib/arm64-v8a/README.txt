whisper.cpp v1.9.5, commit d1be6fde11ac6e0407606b4e42fe72d34add8037 van 2026-10-06 (github.com/ggml-org/whisper.cpp, MIT-licentie)
Gebouwd op 2026-10-23.
Gebouwd met tools/whisper-build.sh (zig cc, statisch aarch64-linux-musl, geen NDK):
  libwhisper.so          = ./whisper-build.sh            (armv8.2-a + dotprod + fp16, snel)
  libwhisper_generic.so  = ./whisper-build.sh generic    (elke arm64-processor)
Het zijn uitvoerbare programma's met een .so-naam, zodat Android ze uitpakt in nativeLibraryDir.

llama.cpp b11500, commit 097f5b5332f46559f1ee11d9d64e48a5065b2989 (github.com/ggml-org/llama.cpp, MIT-licentie)
  libllm.so = ./tools/llm-build.sh  (tools/llm/llm.cpp op llama.cpp; armv8.2-a + dotprod + fp16, statisch musl)
  De lokale AI-motor van Tools: praat via stdin/stdout met de app (geen netwerk, geen server).
  Ondersteunt ook hybride modellen (Qwen3.5: lineaire aandacht + gewone aandacht): een vervolgvraag gaat verder
  vanaf een momentopname van de recurrente toestand, omdat dat geheugen niet achteraan in te korten is.
  Gebouwd op 2026-10-24 met zig 0.17.0 (pip ziglang), cmake 3.28; getest met ./tools/llm/test.sh (x86_64, ook met
  een piepklein Qwen3.5-model) en onder qemu-aarch64.
