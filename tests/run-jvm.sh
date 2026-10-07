#!/usr/bin/env bash
# Draait alle Java-logicatests (tests/jvm/**/*Test.java) tegen de laatst gebouwde classes (eerst ./build.sh).
# Nieuwe tests worden vanzelf meegenomen. Stopt met foutcode 1 als er één mislukt.
set -uo pipefail
cd "$(dirname "$0")/.."
T=${TOOLS:-/home/claude/toolchain}
CP="build/test:build/classes:$T/ap/android-36/android.jar"
[ -d build/classes/nl ] || { echo "Eerst ./build.sh"; exit 1; }
rm -rf build/test && mkdir -p build/test
javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$CP" -d build/test $(find tests/jvm -name '*.java') 2>&1 | grep -v "^Picked up\|^Note:\|warning: \[options\]\|^1 warning\|^3 warnings" || true
[ -n "$(find build/test -name '*Test.class' | head -1)" ] || { echo "✗ tests compileren mislukt"; exit 1; }
fail=0
for f in $(cd tests/jvm && find . -name '*Test.java' | sort); do
  cls=$(echo "${f#./}" | sed 's|\.java$||; s|/|.|g')
  out=$(timeout 180 java -cp "$CP" "$cls" 2>&1); code=$?
  out=$(echo "$out" | grep -v "^Picked up")
  if [ "$code" = 0 ]; then echo "✓ ${cls##*.}"; else echo "✗ ${cls##*.}"; echo "$out" | tail -15; fail=1; fi
done
exit $fail
