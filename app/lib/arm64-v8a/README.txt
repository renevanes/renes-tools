whisper.cpp commit 60c0be6ac8fa71b1a2ae2dd938a31a34a508e774 (github.com/ggml-org/whisper.cpp, MIT-licentie)
Gebouwd met tools/whisper-build.sh (zig cc, statisch aarch64-linux-musl, geen NDK):
  libwhisper.so          = ./whisper-build.sh            (armv8.2-a + dotprod + fp16, snel)
  libwhisper_generic.so  = ./whisper-build.sh generic    (elke arm64-processor)
Het zijn uitvoerbare programma's met een .so-naam, zodat Android ze uitpakt in nativeLibraryDir.
