#!/usr/bin/env bash
# Bouwt de Google Play-basis (.aab) in build-play/; de gewone APK en update/ worden niet gewijzigd.
# Zonder uploadsleutel is dit een proefbouw. Zie docs/play-store.md voor de Console-stappen.
#
# Gebruik: ./build-play.sh
#   Zonder sleutel: een niet-ondertekende .aab (goed om te controleren, niet om te uploaden).
#   Met uploadsleutel: PLAY_KS=pad/naar/upload.p12 PLAY_KS_PASS_FILE=pad/naar/wachtwoord ./build-play.sh
# Vereist: ./setup-tools.sh (bouwgereedschap). bundletool wordt opgehaald en gecontroleerd (vaste versie en SHA-256).
set -euo pipefail
cd "$(dirname "$0")"
T=${TOOLS:-/home/claude/toolchain}
python3 tools/ocr-verify.py
BTV=${BT_VERSION:-$(cat "$T/bt/VERSION" 2>/dev/null || true)}
[ -n "$BTV" ] && [ -d "$T/bt/build-tools/$BTV" ] || BTV=$(ls "$T/bt/build-tools" 2>/dev/null | sort -V | tail -1)
[ -n "$BTV" ] || { echo "Build-tools ontbreken: ./setup-tools.sh"; exit 1; }
BT=$T/bt/build-tools/$BTV
AAPT2=$T/aapt2
ANDROID_JAR=$T/ap/android-36/android.jar
PAKKET=${PLAY_PAKKET:-nl.rene.tools.play}

# bundletool: vaste versie en controlegetal (van github.com/google/bundletool/releases)
BUNDLETOOL_VERSION=1.18.3
BUNDLETOOL_SHA256=a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29
BUNDLETOOL=$T/bundletool-$BUNDLETOOL_VERSION.jar
if [ ! -f "$BUNDLETOOL" ]; then
  curl -fsSL --retry 2 -o "$BUNDLETOOL.tmp" \
    "https://github.com/google/bundletool/releases/download/$BUNDLETOOL_VERSION/bundletool-all-$BUNDLETOOL_VERSION.jar"
  echo "$BUNDLETOOL_SHA256  $BUNDLETOOL.tmp" | sha256sum -c --quiet - || { rm -f "$BUNDLETOOL.tmp"; echo "bundletool: controlegetal klopt niet"; exit 1; }
  mv "$BUNDLETOOL.tmp" "$BUNDLETOOL"
fi

echo "$BUNDLETOOL_SHA256  $BUNDLETOOL" | sha256sum -c --quiet - || { echo "bundletool: controlegetal klopt niet"; exit 1; }
# shellcheck source=/dev/null
source VERSION
B=build-play
rm -rf $B && mkdir -p $B/assets $B/gen $B/classes $B/dex $B/src $B/module
node tools/tool-register.js "$B/gen/ToolRegister.java"

run() { local log=$B/step.log; "$@" > $log 2>&1; local c=$?; grep -v JAVA_TOOL_OPTIONS $log || true; return $c; }

# 1. Manifest voor Play (zonder toestemmingen en onderdelen die Play niet toestaat; eigen namen voor providers)
python3 play/manifest.py $B/AndroidManifest.xml "$PAKKET" > $B/authorities.txt
python3 play/config.py $B/gen/PlayFeatures.java

# 2. Java-bron (kopie) met de nieuwe providernamen
cp -r app/src/. $B/src/
while read -r oud nieuw; do
  grep -rl --include='*.java' "\"$oud\"" $B/src | xargs -r sed -i "s/\"${oud//./\\.}\"/\"$nieuw\"/g" || true
done < $B/authorities.txt

# 3. Interface en assets (zoals build.sh)
python3 tools/web-samenvoegen.py --zonder-testbrug
python3 - "$VERSION_NAME" "$VERSION_CODE" "$B" <<'PY'
import sys, json
name, code, b = sys.argv[1:4]
log = json.dumps(json.load(open('changelog.json')), ensure_ascii=False)
s = open('web/index.html', encoding='utf-8').read()
s = s.replace('__VERSION_NAME__', name).replace('__VERSION_CODE__', code).replace('__CHANGELOG__', log)
open(b + '/assets/index.html', 'w', encoding='utf-8').write(s)
PY
mkdir -p $B/assets/vendor $B/assets/licenties $B/assets/taal
cp web/src/taal/en.json $B/assets/taal/
cp web/vendor/leaflet.js web/vendor/leaflet.css web/vendor/jsQR.js $B/assets/vendor/
cp web/ontsleutelen.html web/start.html web/neutraal.html $B/assets/
cp web/facturen-robot.js web/facturen-leveranciers.json web/bank-robot.js web/bank-banken.json $B/assets/
mkdir -p $B/assets/formulieren
cp app/formulieren/*.pdf $B/assets/formulieren/
cp LICENSE $B/assets/licenties/GPL-3.0.txt
cp web/vendor/LEAFLET-LICENSE $B/assets/licenties/leaflet.txt
cp web/vendor/JSQR-LICENSE $B/assets/licenties/jsqr.txt
cp app/lib/arm64-v8a/LICENSE-whisper.cpp.txt $B/assets/licenties/whisper.cpp.txt
cp app/lib/arm64-v8a/LICENSE-llama.cpp.txt $B/assets/licenties/llama.cpp.txt
cp app/lib/arm64-v8a/LICENSE-musl.txt $B/assets/licenties/musl.txt
mkdir -p $B/assets/ocr $B/assets/licenties/ocr
cp app/ocr/eng.traineddata $B/assets/ocr/
cp app/ocr/licenses/*.txt $B/assets/licenties/ocr/

cat > $B/gen/Version.java <<JAVA
package nl.rene.tools;
final class Version {
    static final String NAME = "$VERSION_NAME";
    static final int CODE = $VERSION_CODE;
    static final int NATIVE_LEVEL = $NATIVE_LEVEL;
    static final String UPDATE_BASE = "https://raw.githubusercontent.com/renevanes/renes-tools/main/update/";
}
JAVA

# 4. Resources in het formaat voor een app-bundel (proto)
$AAPT2 compile --dir app/res -o $B/res.zip
$AAPT2 link --proto-format -I "$ANDROID_JAR" --manifest $B/AndroidManifest.xml -A $B/assets \
  --rename-manifest-package "$PAKKET" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --min-sdk-version 24 --target-sdk-version 36 \
  --java $B/gen -o $B/base-proto.apk $B/res.zip

# 5. Java -> dex
run javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" \
  -classpath app/ocr/tesseract4android.jar -d $B/classes $(find $B/src $B/gen -name '*.java') || { echo "javac mislukt"; exit 1; }
run java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$ANDROID_JAR" \
  --output $B/dex app/ocr/tesseract4android.jar $(find $B/classes -name '*.class') || { echo "d8 mislukt"; exit 1; }

# 6. Module samenstellen: manifest/, dex/, res/, assets/, lib/, resources.pb
(cd $B/module && unzip -q ../base-proto.apk && mkdir -p manifest dex && mv AndroidManifest.xml manifest/ && cp ../dex/classes.dex dex/)
# Offline OCR is ook beschikbaar in de Play-app zonder de zware AI-programma's.
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  mkdir -p "$B/module/lib/$abi"
  cp "app/lib/$abi/"{libjpeg,libleptonica,libpngx,libtesseract}.so "$B/module/lib/$abi/"
done
if python3 -c 'import sys; sys.path.insert(0,"play"); from config import profile; sys.exit(0 if profile()["native"] else 1)'; then
  mkdir -p $B/module/lib
  cp -r app/lib/arm64-v8a $B/module/lib/
  find $B/module/lib -type f ! -name '*.so' -delete
fi
(cd $B/module && zip -q -r ../base.zip .)
OUT=$B/Renes-Tools-play-v$VERSION_NAME.aab
# De app start whisper/llama als programma uit nativeLibraryDir: die moeten dus uitgepakt worden (extractNativeLibs)
echo '{"optimizations":{"uncompressNativeLibraries":{"enabled":false}}}' > $B/bundle-config.json
run java -jar "$BUNDLETOOL" build-bundle --modules=$B/base.zip --config=$B/bundle-config.json --output="$OUT" || { echo "bundletool mislukt"; exit 1; }
java -jar "$BUNDLETOOL" validate --bundle="$OUT" > $B/validate.log 2>&1 || { grep -v JAVA_TOOL_OPTIONS $B/validate.log; echo "bundel ongeldig"; exit 1; }
python3 play/check.py "$OUT" "$BUNDLETOOL" "$PAKKET"

# 7. Ondertekenen met de uploadsleutel (alleen als die is opgegeven)
if [ -n "${PLAY_KS:-}" ]; then
  run jarsigner -keystore "$PLAY_KS" -storetype PKCS12 -storepass:file "${PLAY_KS_PASS_FILE:?PLAY_KS_PASS_FILE ontbreekt}" \
    -sigalg SHA256withRSA -digestalg SHA-256 "$OUT" "${PLAY_KS_ALIAS:-upload}" || { echo "ondertekenen mislukt"; exit 1; }
  run jarsigner -verify "$OUT" || { echo "handtekeningcontrole mislukt"; exit 1; }
  echo "OK: $OUT (ondertekend met de uploadsleutel), pakket $PAKKET, versie $VERSION_NAME ($VERSION_CODE)"
else
  echo "OK: $OUT (niet ondertekend: proefbouw), pakket $PAKKET, versie $VERSION_NAME ($VERSION_CODE)"
fi
