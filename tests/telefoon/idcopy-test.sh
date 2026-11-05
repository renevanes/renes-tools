#!/usr/bin/env bash
# Native redactietest als losse instrumentatie-APK. Geen test-ingang in de echte app.
set -euo pipefail
cd "$(dirname "$0")/../.."
PLAY_TARGET=${1:-nl.rene.tools.play}
[[ $PLAY_TARGET =~ ^[a-z][a-z0-9_.]+$ ]] || exit 2
T=${TOOLS:-/home/claude/toolchain}
B=build-play/idcopy-test/$PLAY_TARGET
BT="$T/bt/build-tools/$(cat "$T/bt/VERSION")"
mkdir -p "$B/classes" "$B/dex" "$B/res" "$B/gen"
cat > "$B/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PLAY_TARGET.idcopytest"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36"/><application android:label="ID copy CI"/><instrumentation android:name="nl.rene.tools.test.IdCopyDeviceTest" android:targetPackage="$PLAY_TARGET" android:functionalTest="true"/></manifest>
XML
"$T/aapt2" link -I "$T/ap/android-36/android.jar" --manifest "$B/AndroidManifest.xml" -o "$B/unsigned.apk"
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$T/ap/android-36/android.jar:$BT/core-lambda-stubs.jar" -d "$B/classes" tests/telefoon/idcopy/IdCopyDeviceTest.java
mapfile -t ID_CLASSES < <(find "$B/classes" -name '*.class')
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$T/ap/android-36/android.jar" --output "$B/dex" "${ID_CLASSES[@]}"
(cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks keys/release.p12 --ks-key-alias release --ks-pass file:keys/keystore.pass --out "$B/test.apk" "$B/aligned.apk"
if [ "${ID_COPY_BUILD_ONLY:-0}" = 1 ]; then exit 0; fi
adb install -r "$B/test.apk"
adb shell am instrument -w "$PLAY_TARGET.idcopytest/nl.rene.tools.test.IdCopyDeviceTest" | tee "$B/result.txt"
grep -q 'idcopy=ok' "$B/result.txt"
echo 'ID-kopie: pixels, tekstlaag, EXIF, rotatie en bronwissen gecontroleerd'
