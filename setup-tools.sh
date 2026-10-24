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

bt_ok(){ # alles aanwezig, en zipalign draait echt (uitvoerrechten, lib64/libc++); zonder argumenten toont hij zijn gebruik
  local d="bt/build-tools/$1" out rc=0
  [ -x "$d/zipalign" ] && [ -f "$d/lib/d8.jar" ] && [ -f "$d/lib/apksigner.jar" ] && [ -f "$d/core-lambda-stubs.jar" ] || return 1
  out=$("$d/zipalign" 2>&1) || rc=$?
  [ "$rc" != 126 ] && [ "$rc" != 127 ] && grep -qiE 'usage|zip alignment' <<<"$out"
}

# Uitpakken met Python (als unzip ontbreekt), met de unix-rechten en symlinks terug, die zipfile anders kwijtraakt
unzip_py(){
  python3 -I - "$1" "$2" <<'PY'
import os, stat, sys, zipfile
zip_pad, doel = sys.argv[1], os.path.realpath(sys.argv[2])
with zipfile.ZipFile(zip_pad) as z:
    for info in z.infolist():
        pad = os.path.realpath(os.path.join(doel, info.filename))
        if pad != doel and not pad.startswith(doel + os.sep):
            sys.exit(f"onveilig pad in zip: {info.filename}")
        modus = info.external_attr >> 16
        if stat.S_ISLNK(modus):
            naar = z.read(info).decode()
            echt = os.path.realpath(os.path.join(os.path.dirname(pad), naar))
            if os.path.isabs(naar) or not echt.startswith(doel + os.sep):
                sys.exit(f"onveilige symlink in zip: {info.filename} -> {naar}")
            os.makedirs(os.path.dirname(pad), exist_ok=True)
            os.symlink(naar, pad)
            continue
        z.extract(info, doel)
        if modus and not info.is_dir():
            os.chmod(pad, stat.S_IMODE(modus))
PY
}

google_bt(){ # downloaden, controleren en uitpakken naar bt/build-tools/$BUILD_TOOLS; geeft 1 als het downloaden mislukt
  local tmp=bt-download
  # stoppen met een fout, en eerst de halve download/uitpakking weghalen
  fail(){ echo "FOUT: $1" >&2; rm -rf bt-download; exit 1; }
  rm -rf "$tmp" && mkdir -p "$tmp"
  if ! curl -fsSL --connect-timeout 20 --max-time 900 --retry 2 -o "$tmp/bt.zip" "$BUILD_TOOLS_URL"; then
    rm -rf "$tmp"; return 1
  fi
  local sum size
  sum=$(sha1sum "$tmp/bt.zip" | cut -d' ' -f1)
  size=$(wc -c < "$tmp/bt.zip" | tr -d ' ')
  if [ "$sum" != "$BUILD_TOOLS_SHA1" ] || [ "$size" != "$BUILD_TOOLS_SIZE" ]; then
    fail "build-tools $BUILD_TOOLS klopt niet (sha1 $sum, grootte $size; verwacht $BUILD_TOOLS_SHA1, $BUILD_TOOLS_SIZE)"
  fi
  mkdir -p "$tmp/x"
  # (binnen "if google_bt" werkt set -e niet: daarom overal een expliciete controle)
  if command -v unzip >/dev/null; then unzip -q "$tmp/bt.zip" -d "$tmp/x" || fail "uitpakken mislukt"
  else unzip_py "$tmp/bt.zip" "$tmp/x" || fail "uitpakken mislukt"; fi
  # De bovenste map heet niet altijd hetzelfde (vaak "android-NN"), of ontbreekt: zoek de map met lib/d8.jar
  local d8 src
  d8=$(find "$tmp/x" -maxdepth 4 -path '*/lib/d8.jar' -type f | head -1)
  [ -n "$d8" ] || fail "geen lib/d8.jar in $BUILD_TOOLS_URL"
  src=$(dirname "$(dirname "$d8")")
  rm -rf "bt/build-tools/$BUILD_TOOLS" && mkdir -p bt/build-tools
  mv "$src" "bt/build-tools/$BUILD_TOOLS" || fail "verplaatsen mislukt"
  rm -rf "$tmp"
  bt_ok "$BUILD_TOOLS" || fail "build-tools $BUILD_TOOLS onvolledig, of zipalign draait niet (zipalign, lib/d8.jar, lib/apksigner.jar, core-lambda-stubs.jar, lib64/)"
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
