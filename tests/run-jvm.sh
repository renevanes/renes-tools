#!/usr/bin/env bash
# Draait alle Java-logicatests (tests/jvm/**/*Test.java) tegen de laatst gebouwde classes (eerst ./build.sh).
# Nieuwe tests worden vanzelf meegenomen. Stopt met foutcode 1 als er één mislukt.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
T=${TOOLS:-/home/claude/toolchain}
ANT="$T/voorlezen/ant-codecs.jar"
OVERSLAAN=""
if [ ! -f "$ANT" ]; then
  # Cloudsessie zonder Maven Central (zie tests/cloudsessie/LEESMIJ.md): de stand-ins van java-bouwen.sh, zonder de test
  # die de echte ant nodig heeft. In CI moet ant er zijn.
  [ -n "${CI:-}" ] && { echo "✗ $ANT ontbreekt (./setup-tools.sh)"; exit 1; }
  ANT=build/cloudsessie/ant-stub.jar
  # VoiceArchiveTest heeft de echte ant nodig; WebSigTest de update/update.json die alleen een volledige ./build.sh ondertekent
  OVERSLAAN="VoiceArchiveTest WebSigTest"
  echo "· ant-codecs.jar ontbreekt (cloudsessie): VoiceArchiveTest en WebSigTest overgeslagen (die draaien in CI)"
fi
NIET=()
for t in $OVERSLAAN; do NIET+=(! -name "$t.java"); done
CP="build/test:build/classes:$T/ap/android-36/android.jar:$T/voorlezen/sherpa.jar:$ANT"
[ -d build/classes/nl ] || { echo "Eerst ./build.sh (of in een cloudsessie ./tests/cloudsessie/java-bouwen.sh)"; exit 1; }
rm -rf build/test && mkdir -p build/test
compile_log=$(mktemp) || exit 1
trap 'rm -f "$compile_log"' EXIT
if ! javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$CP" -d build/test $(find tests/jvm -name '*.java' "${NIET[@]}") > "$compile_log" 2>&1; then
  cat "$compile_log"; echo "✗ tests compileren mislukt"; exit 1
fi
[ -n "$(find build/test -name '*Test.class' | head -1)" ] || { echo "✗ tests compileren mislukt"; exit 1; }
fail=0
for f in $(cd tests/jvm && find . -name '*Test.java' "${NIET[@]}" | sort); do
  cls=$(echo "${f#./}" | sed 's|\.java$||; s|/|.|g')
  out=$(timeout 180 java -cp "$CP" "$cls" 2>&1); code=$?
  out=$(echo "$out" | grep -v "^Picked up")
  if [ "$code" = 0 ]; then echo "✓ ${cls##*.}"; else echo "✗ ${cls##*.}"; echo "$out" | tail -15; fail=1; fi
done
exit $fail
