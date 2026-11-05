#!/usr/bin/env bash
# Twee pakketten naast elkaar; oude gevoelige voorkeuren mogen in Play niet herleven.
set -euo pipefail
cd "$(dirname "$0")/../.."
source VERSION
adb install -r "Renes-Tools-v$VERSION_NAME.apk"
adb install -r build-play/play-test.apk
adb shell am force-stop nl.rene.tools.play

# Google APIs-emulators zijn userdebug: herstel oude voorkeuren vóór het starten,
# zoals na toesteloverdracht. Geen toegang tot een gebruikersapparaat of echte gegevens.
adb root
adb wait-for-device
PLAY_PREFS=/data/user/0/nl.rene.tools.play/shared_prefs
PLAY_UID=$(adb shell stat -c %u /data/user/0/nl.rene.tools.play | tr -d '\r')
[[ $PLAY_UID =~ ^[0-9]+$ ]] || { echo 'Play-appgebruiker niet gevonden'; exit 1; }
PLAY_SEED=$(mktemp -d)
trap 'rm -rf "$PLAY_SEED"' EXIT
cat > "$PLAY_SEED/call_recordings.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name="auto" value="true" /></map>
XML
cat > "$PLAY_SEED/gebruik.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name="punlock">aan</string><boolean name="photoFail" value="true" /></map>
XML
cat > "$PLAY_SEED/auto.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name="rules">beschadigde oude regels</string></map>
XML
adb shell "mkdir -p '$PLAY_PREFS'"
for pref in call_recordings gebruik auto; do
  adb push "$PLAY_SEED/$pref.xml" /data/local/tmp/play-pref.xml
  adb shell "cp /data/local/tmp/play-pref.xml '$PLAY_PREFS/$pref.xml'; chown '$PLAY_UID:$PLAY_UID' '$PLAY_PREFS/$pref.xml'; chmod 600 '$PLAY_PREFS/$pref.xml'"
done
adb shell "chown '$PLAY_UID:$PLAY_UID' '$PLAY_PREFS'; chmod 700 '$PLAY_PREFS'; restorecon -R '$PLAY_PREFS'"
MANIFEST=build-play/test-manifest.xml bash tests/telefoon/rooktest.sh build-play/play-test.apk build-play/telefoon
adb shell cat "$PLAY_PREFS/auto.xml" | grep -F 'name="rules">[]</string>'
for pref in call_recordings gebruik; do
  if adb shell cat "$PLAY_PREFS/$pref.xml" | grep -q 'name='; then
    echo "Uitgesloten voorkeur na herstel nog actief: $pref"; exit 1
  fi
done
adb shell pm path nl.rene.tools | grep -q package:
adb shell pm path nl.rene.tools.play | grep -q package:
echo 'Play-herstelcontrole: geslaagd'

bash tests/telefoon/idcopy-test.sh nl.rene.tools.play
bash tests/telefoon/idcopy-test.sh nl.rene.tools
