#!/usr/bin/env bash
# Bouwt Renes-Tools-vX.Y.apk en zet de updatebestanden klaar in update/.
# Vereist: ./setup-tools.sh (bouwgereedschap) en de ondertekeningssleutel in keys/.
set -euo pipefail
cd "$(dirname "$0")"
T=${TOOLS:-/home/claude/toolchain}
BT=$T/bt/build-tools/34.0.4
AAPT2=$T/aapt2
ANDROID_JAR=$T/ap/android-36/android.jar
source VERSION
B=build
rm -rf $B && mkdir -p $B/assets $B/gen $B/classes $B/dex

[ -f keys/release.p12 ] || { echo "Sleutel ontbreekt: ./keys/unlock.sh"; exit 1; }

# 0. Interface samenvoegen uit de onderdelen in web/src/
python3 tools/web-samenvoegen.py

# 1. Interface met versienummer en wijzigingslog
python3 - "$VERSION_NAME" "$VERSION_CODE" <<'PY'
import sys, json
name, code = sys.argv[1], sys.argv[2]
log = json.dumps(json.load(open('changelog.json')), ensure_ascii=False)
s = open('web/index.html', encoding='utf-8').read()
s = s.replace('__VERSION_NAME__', name).replace('__VERSION_CODE__', code).replace('__CHANGELOG__', log)
open('build/assets/index.html', 'w', encoding='utf-8').write(s)
PY

# 1b. Kaartbibliotheek (Leaflet) meekopieren naar de assets
mkdir -p $B/assets/vendor
cp web/vendor/leaflet.js web/vendor/leaflet.css $B/assets/vendor/
cp web/ontsleutelen.html $B/assets/
cp web/start.html $B/assets/

# 2. Versie-informatie voor de Java-code
cat > $B/gen/Version.java <<JAVA
package nl.rene.tools;
final class Version {
    static final String NAME = "$VERSION_NAME";
    static final int CODE = $VERSION_CODE;
    static final int NATIVE_LEVEL = $NATIVE_LEVEL;
    static final String UPDATE_BASE = "https://raw.githubusercontent.com/renevanes/renes-tools/main/update/";
}
JAVA

# 3. Resources en manifest
$AAPT2 compile --dir app/res -o $B/res.zip
$AAPT2 link -I $ANDROID_JAR --manifest app/AndroidManifest.xml -A $B/assets \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  --min-sdk-version 24 --target-sdk-version 36 \
  --java $B/gen -o $B/base.apk $B/res.zip

# 4. Java -> dex
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath $ANDROID_JAR:$BT/core-lambda-stubs.jar \
  -d $B/classes $(find app/src $B/gen -name '*.java') 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
[ -f $B/classes/nl/rene/tools/MainActivity.class ] || { echo "javac mislukt"; exit 1; }
java -cp $BT/lib/d8.jar com.android.tools.r8.D8 --release --min-api 24 --lib $ANDROID_JAR \
  --output $B/dex $(find $B/classes -name '*.class') 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
(cd $B/dex && zip -q -j ../base.apk classes.dex)
# 4b. Uitschrijfprogramma (whisper.cpp) als native lib; Android pakt het uit in nativeLibraryDir
(cd app && zip -q ../$B/base.apk lib/*/*.so)

# 5. Uitlijnen en ondertekenen
$BT/zipalign -f -p 4 $B/base.apk $B/aligned.apk
OUT=Renes-Tools-v$VERSION_NAME.apk
java -jar $BT/lib/apksigner.jar sign --ks keys/release.p12 --ks-type PKCS12 \
  --ks-pass file:keys/keystore.pass --ks-key-alias release --v4-signing-enabled false \
  --out $OUT $B/aligned.apk 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
java -jar $BT/lib/apksigner.jar verify -v $OUT 2>&1 | grep -E "^Verifie|^Verified using v[23]"
rm -f $OUT.idsig

# 6. Updatebestanden
mkdir -p update
cp $OUT update/Renes-Tools.apk
cp $B/assets/index.html update/index.html
cp $B/assets/start.html update/start.html
python3 - "$VERSION_NAME" "$VERSION_CODE" "$NATIVE_LEVEL" <<'PY'
import sys, json, hashlib
name, code, native = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
sha = lambda p: hashlib.sha256(open(p, 'rb').read()).hexdigest()
m = {
  "app": "Rene's Tools", "versionName": name, "versionCode": code, "nativeLevel": native,
  "web": "index.html", "webSha256": sha('update/index.html'),
  "start": "start.html", "startSha256": sha('update/start.html'),
  "apk": "Renes-Tools.apk", "apkSha256": sha('update/Renes-Tools.apk'),
  "changelog": json.load(open('changelog.json'))
}
json.dump(m, open('update/update.json', 'w'), ensure_ascii=False, indent=2)
PY
echo "OK: $OUT ($(stat -c %s $OUT) bytes), versie $VERSION_NAME ($VERSION_CODE), native $NATIVE_LEVEL"
