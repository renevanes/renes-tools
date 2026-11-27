#!/usr/bin/env bash
# Alleen de Java-code compileren naar build/classes (geen APK, niets in update/), voor een cloudsessie waar ./build.sh
# stopt omdat de proxy de voorleesbibliotheken (Maven Central) weigert. Ontbreekt ant-codecs.jar, dan compileert dit
# tegen de lege stand-ins in tests/cloudsessie/ant-stub (alleen om te compileren; VoiceArchiveTest slaat run-jvm.sh dan over).
# Daarna: ./tests/run-jvm.sh. Nooit in CI: daar bouwt ./build.sh alles echt. Zie LEESMIJ.md.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ -n "${CI:-}" ] && { echo "Niet in CI: daar bouwt ./build.sh"; exit 1; }
T=${TOOLS:-/home/claude/toolchain}
BTV=$(cat "$T/bt/VERSION" 2>/dev/null || true)
[ -n "$BTV" ] && [ -d "$T/bt/build-tools/$BTV" ] || BTV=$(find "$T/bt/build-tools" -mindepth 1 -maxdepth 1 -printf '%f\n' 2>/dev/null | sort -V | tail -1 || true)
[ -n "$BTV" ] || { echo "Build-tools ontbreken: ./tests/cloudsessie/voorbereiden.sh"; exit 1; }
BT=$T/bt/build-tools/$BTV
ANDROID_JAR=$T/ap/android-36/android.jar
[ -f "$ANDROID_JAR" ] && [ -x "$T/aapt2" ] && [ -f "$T/voorlezen/sherpa.jar" ] || { echo "Gereedschap ontbreekt: ./tests/cloudsessie/voorbereiden.sh"; exit 1; }
B=build
ANT="$T/voorlezen/ant-codecs.jar"
if [ ! -f "$ANT" ]; then
  echo "ant-codecs.jar ontbreekt: compileren tegen de stand-ins in tests/cloudsessie/ant-stub"
  rm -rf "$B/cloudsessie" && mkdir -p "$B/cloudsessie/ant"
  # shellcheck disable=SC2046
  javac -nowarn -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$B/cloudsessie/ant" $(find tests/cloudsessie/ant-stub -name '*.java') 2>&1 | grep -v "^Picked up" || true
  (cd "$B/cloudsessie/ant" && jar cf ../ant-stub.jar .)
  ANT="$B/cloudsessie/ant-stub.jar"
fi
# shellcheck disable=SC1091
source VERSION
rm -rf "$B/classes" "$B/gen" "$B/cloudsessie/res" && mkdir -p "$B/classes" "$B/gen" "$B/cloudsessie/res" "$B/cloudsessie/assets"
node tools/tool-register.js "$B/gen/ToolRegister.java"
cat > "$B/gen/Version.java" <<JAVA
package nl.rene.tools;
final class Version {
    static final String NAME = "$VERSION_NAME";
    static final int CODE = $VERSION_CODE;
    static final int NATIVE_LEVEL = $NATIVE_LEVEL;
    static final String UPDATE_BASE = "https://raw.githubusercontent.com/renevanes/renes-tools/main/update/";
}
JAVA
python3 play/config.py "$B/gen/PlayFeatures.java"
"$T/aapt2" compile --dir app/res -o "$B/cloudsessie/res.zip"
"$T/aapt2" link -I "$ANDROID_JAR" --manifest app/AndroidManifest.xml -A "$B/cloudsessie/assets" \
  --min-sdk-version 24 --target-sdk-version 36 --java "$B/gen" -o "$B/cloudsessie/base.apk" "$B/cloudsessie/res.zip"
# shellcheck disable=SC2046
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" \
  -classpath "$T/voorlezen/sherpa.jar:$ANT:app/ocr/tesseract4android.jar" -d "$B/classes" $(find app/src "$B/gen" -name '*.java') 2>&1 | grep -v "^Picked up" || true
[ -f "$B/classes/nl/rene/tools/MainActivity.class" ] || { echo "✗ javac mislukt (zie boven)"; exit 1; }
anon=0
for f in "$B"/classes/nl/rene/tools/*.class; do
  if javap -v "$f" 2>/dev/null | grep -q "<no name>"; then echo "ANON $f"; anon=1; fi
done
[ "$anon" = 0 ] || echo "!! Nakijken: classes zonder naam voor een parameter (anonieme of niet-statische binnenklassen; d8 kan erop vastlopen, zie AGENTS.md). Stonden ze er op main al, dan zijn ze niet van jou."
echo "✓ Java gecompileerd naar $B/classes (geen APK). Nu: ./tests/run-jvm.sh"
