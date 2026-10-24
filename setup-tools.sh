#!/usr/bin/env bash
# Haalt het Android-bouwgereedschap op (geen Android Studio of Gradle nodig).
set -euo pipefail
T=${TOOLS:-/home/claude/toolchain}
mkdir -p $T && cd $T

# Android build-tools (d8, apksigner, zipalign, core-lambda-stubs.jar): de officiële van Google, vastgezet.
# URL, sha1 en grootte staan in Google's repository2-3.xml (de workflow Onderhoud meldt een nieuwere versie).
BUILD_TOOLS=37.0.0
BUILD_TOOLS_URL=https://dl.google.com/android/repository/build-tools_r37_linux.zip
BUILD_TOOLS_SHA1=70954e99f4c3d9d46ee70fa32624672fe7cd6ebe
BUILD_TOOLS_SIZE=66135704
# Alleen om lokaal te testen als Google niet bereikbaar is (bijv. in een afgeschermde cloudsessie); nooit in CI.
FALLBACK_BT=34.0.4
FALLBACK_URL=https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-x86_64.tar.xz

bt_ok(){ [ -x "bt/build-tools/$1/zipalign" ] && [ -f "bt/build-tools/$1/lib/d8.jar" ] && [ -f "bt/build-tools/$1/lib/apksigner.jar" ] && [ -f "bt/build-tools/$1/core-lambda-stubs.jar" ]; }

google_bt(){ # downloaden, controleren en uitpakken naar bt/build-tools/$BUILD_TOOLS; geeft 1 als het downloaden mislukt
  local tmp=bt-download
  rm -rf "$tmp" && mkdir -p "$tmp"
  if ! curl -fsSL --connect-timeout 20 --max-time 900 --retry 2 -o "$tmp/bt.zip" "$BUILD_TOOLS_URL"; then
    rm -rf "$tmp"; return 1
  fi
  local sum size
  sum=$(sha1sum "$tmp/bt.zip" | cut -d' ' -f1)
  size=$(wc -c < "$tmp/bt.zip" | tr -d ' ')
  if [ "$sum" != "$BUILD_TOOLS_SHA1" ] || [ "$size" != "$BUILD_TOOLS_SIZE" ]; then
    echo "FOUT: build-tools $BUILD_TOOLS klopt niet (sha1 $sum, grootte $size; verwacht $BUILD_TOOLS_SHA1, $BUILD_TOOLS_SIZE)" >&2
    rm -rf "$tmp"; exit 1
  fi
  mkdir -p "$tmp/x"
  # (binnen "if google_bt" werkt set -e niet: daarom overal een expliciete controle)
  if command -v unzip >/dev/null; then unzip -q "$tmp/bt.zip" -d "$tmp/x" || { echo "FOUT: uitpakken mislukt" >&2; exit 1; }
  else python3 -I -m zipfile -e "$tmp/bt.zip" "$tmp/x" || { echo "FOUT: uitpakken mislukt" >&2; exit 1; }; fi
  # De bovenste map heet niet altijd hetzelfde (vaak "android-NN"), of ontbreekt: zoek de map met lib/d8.jar
  local d8 src
  d8=$(find "$tmp/x" -maxdepth 4 -path '*/lib/d8.jar' -type f | head -1)
  [ -n "$d8" ] || { echo "FOUT: geen lib/d8.jar in $BUILD_TOOLS_URL" >&2; rm -rf "$tmp"; exit 1; }
  src=$(dirname "$(dirname "$d8")")
  rm -rf "bt/build-tools/$BUILD_TOOLS" && mkdir -p bt/build-tools
  mv "$src" "bt/build-tools/$BUILD_TOOLS" || { echo "FOUT: verplaatsen mislukt" >&2; exit 1; }
  rm -rf "$tmp"
  # Python's zipfile bewaart geen uitvoerrechten
  chmod +x "bt/build-tools/$BUILD_TOOLS/zipalign" 2>/dev/null || true
  bt_ok "$BUILD_TOOLS" || { echo "FOUT: build-tools $BUILD_TOOLS onvolledig (zipalign, lib/d8.jar, lib/apksigner.jar of core-lambda-stubs.jar ontbreekt)" >&2; exit 1; }
}

if bt_ok "$BUILD_TOOLS"; then
  BT_USED=$BUILD_TOOLS
elif google_bt; then
  BT_USED=$BUILD_TOOLS
elif [ -n "${CI:-}" ]; then
  echo "FOUT: build-tools $BUILD_TOOLS niet te downloaden van $BUILD_TOOLS_URL" >&2; exit 1
else
  echo "WAARSCHUWING: Google niet bereikbaar: build-tools $FALLBACK_BT alleen om lokaal te testen; publiceren gebeurt met $BUILD_TOOLS" >&2
  if ! bt_ok "$FALLBACK_BT"; then
    curl -fsSL -o bt.tar.xz "$FALLBACK_URL"
    mkdir -p bt && tar xJf bt.tar.xz -C bt && rm bt.tar.xz
  fi
  BT_USED=$FALLBACK_BT
fi
mkdir -p bt && echo "$BT_USED" > bt/VERSION

if [ ! -x aapt2 ]; then
  npm pack aaptjs3@2.0.2 >/dev/null 2>&1 && mkdir -p a && tar xzf aaptjs3-2.0.2.tgz -C a
  cp a/package/bin/x64/linux/aapt2 . && chmod +x aapt2 && rm -rf a aaptjs3-2.0.2.tgz
fi
if [ ! -f ap/android-36/android.jar ]; then
  git clone -q --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms ap
  (cd ap && git sparse-checkout set android-36)
fi
echo "Gereedschap staat klaar in $T (build-tools $BT_USED)"
