#!/usr/bin/env bash
# Haalt het Android-bouwgereedschap op (geen Android Studio of Gradle nodig).
set -euo pipefail
T=${TOOLS:-/home/claude/toolchain}
mkdir -p $T && cd $T
if [ ! -x bt/build-tools/34.0.4/zipalign ]; then
  curl -sSL -o bt.tar.xz https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-x86_64.tar.xz
  mkdir -p bt && tar xJf bt.tar.xz -C bt && rm bt.tar.xz
fi
if [ ! -x aapt2 ]; then
  npm pack aaptjs3@2.0.1 >/dev/null 2>&1 && mkdir -p a && tar xzf aaptjs3-2.0.1.tgz -C a
  cp a/package/bin/x64/linux/aapt2 . && chmod +x aapt2 && rm -rf a aaptjs3-2.0.1.tgz
fi
if [ ! -f ap/android-36/android.jar ]; then
  git clone -q --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms ap
  (cd ap && git sparse-checkout set android-36)
fi
echo "Gereedschap staat klaar in $T"
