#!/usr/bin/env bash
# Echte synthese met vast model op API 24/36, plus ownershipcontrole op AudioTrack.
set -euo pipefail
cd "$(dirname "$0")/../.."
TARGET_PACKAGE=${1:-nl.rene.tools.play}
[[ $TARGET_PACKAGE =~ ^[a-z][a-z0-9_.]+$ ]] || exit 2
T=${TOOLS:-/home/claude/toolchain}
B=build-play/voorlezen-test/$TARGET_PACKAGE
BT="$T/bt/build-tools/$(cat "$T/bt/VERSION")"
VOICE_TEST_FILE="$T/voorlezen/voice-ci.tar.bz2"
VOICE_SHA=eb16022c9c8ee48b75dc833e8a8b04e08730929ea68cde9b67e2dc712f988978
if ! [ -f "$VOICE_TEST_FILE" ] || ! echo "$VOICE_SHA  $VOICE_TEST_FILE" | sha256sum -c --quiet; then
 curl -fsSL --retry 2 --max-time 180 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-nl_NL-ronnie-medium-int8.tar.bz2' -o "$VOICE_TEST_FILE.part"
 echo "$VOICE_SHA  $VOICE_TEST_FILE.part" | sha256sum -c --quiet
 mv "$VOICE_TEST_FILE.part" "$VOICE_TEST_FILE"
fi
mkdir -p "$B/classes" "$B/dex" "$B/assets"
cp "$VOICE_TEST_FILE" "$B/assets/voice.tar.bz2"
cat > "$B/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$TARGET_PACKAGE.voicetest"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36"/><application android:label="Voice CI"/><instrumentation android:name="nl.rene.tools.test.VoiceDeviceTest" android:targetPackage="$TARGET_PACKAGE" android:functionalTest="true"/></manifest>
XML
"$T/aapt2" link -I "$T/ap/android-36/android.jar" --manifest "$B/AndroidManifest.xml" -A "$B/assets" -o "$B/unsigned.apk"
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$T/ap/android-36/android.jar:$BT/core-lambda-stubs.jar" -d "$B/classes" tests/telefoon/voorlezen/VoiceDeviceTest.java
mapfile -t VOICE_CLASSES < <(find "$B/classes" -name '*.class')
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$T/ap/android-36/android.jar" --output "$B/dex" "${VOICE_CLASSES[@]}"
(cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks keys/release.p12 --ks-key-alias release --ks-pass file:keys/keystore.pass --out "$B/test.apk" "$B/aligned.apk"
if [ "${VOICE_BUILD_ONLY:-0}" = 1 ]; then exit 0; fi
adb install -r "$B/test.apk"
adb shell am instrument -w "$TARGET_PACKAGE.voicetest/nl.rene.tools.test.VoiceDeviceTest" | tee "$B/result.txt"
grep -q 'voorlezen=ok' "$B/result.txt"
echo 'Lokale stem: modelinstallatie, echte audio en stale bediening op Android gecontroleerd'
