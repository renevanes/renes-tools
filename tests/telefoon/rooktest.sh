#!/usr/bin/env bash
# Telefoontest (rooktest): installeert de APK op een draaiende Android-emulator (of telefoon via adb), start de
# app en een paar andere schermen en controleert dat er niets crasht. Draait in de workflow Telefoontest
# (.github/workflows/telefoontest.yml); in een cloudsessie kan het niet (geen emulator).
#
#   tests/telefoon/rooktest.sh Renes-Tools-vX.Y.apk [uitmap]      de test zelf (adb moet een toestel zien)
#   tests/telefoon/rooktest.sh --log logcat.txt [pakket]          alleen de crashcontrole op een logcat-bestand
#   tests/telefoon/rooktest.sh --manifest                         pakket, startscherm en Home-scherm uit het manifest
#
# Het app-slot of een eerste-startscherm mag verschijnen: de test kijkt alleen of er niets crasht.
set -uo pipefail
ZELF=$(readlink -f "$0")
MANIFEST=${MANIFEST:-$(dirname "$ZELF")/../../app/AndroidManifest.xml}
WACHT=${WACHT:-15}

# Pakket, de LAUNCHER-activity en de Home-activity (alleen als die exported is), één per regel.
manifest_info() {
  python3 - "$MANIFEST" <<'PY'
import sys, xml.etree.ElementTree as ET
A = '{http://schemas.android.com/apk/res/android}'
root = ET.parse(sys.argv[1]).getroot()
pkg = root.get('package')
def full(n): return pkg + n if n.startswith('.') else n
launcher = home = ''
for act in root.iter('activity'):
    name = full(act.get(A + 'name'))
    for f in act.findall('intent-filter'):
        acts = {a.get(A + 'name') for a in f.findall('action')}
        cats = {c.get(A + 'name') for c in f.findall('category')}
        if 'android.intent.action.MAIN' in acts and 'android.intent.category.LAUNCHER' in cats and not launcher:
            launcher = name
        if ('android.intent.action.MAIN' in acts and 'android.intent.category.HOME' in cats and not home
                and act.get(A + 'exported') == 'true'):
            home = name
if not pkg or not launcher: sys.exit('Geen pakket of startscherm in het manifest')
print(pkg); print(launcher); print(home)
PY
}

# Crashregels voor ons pakket in een logcat (formaat threadtime of brief). Drukt niets af als alles goed is.
# Herkent: Java-crash (FATAL EXCEPTION + "Process: <pakket>"), native crash (Fatal signal / tombstone) en ANR.
crashregels() {
  python3 - "$1" "$2" <<'PY'
import re, sys
path, pkg = sys.argv[1], sys.argv[2]
p = re.escape(pkg) + r'(?::[\w.]+)?'
lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
out = []
def pid_of(line):  # threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L tag: …"; brief: "L/tag( PID): …"
    m = re.match(r'\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s', line) or re.search(r'\(\s*(\d+)\):', line)
    return m.group(1) if m else None
i = 0
while i < len(lines):
    l = lines[i]
    if 'FATAL EXCEPTION' in l:
        blok = lines[i:i + 4]
        if any(re.search(r'Process: ' + p + r'(,|\s|$)', b) for b in blok):
            pid = pid_of(l)
            blok = [l]
            for b in lines[i + 1:]:
                if 'AndroidRuntime' in b and (pid is None or pid_of(b) == pid):
                    blok.append(b)
                    if len(blok) >= 40: break
            out.append(blok)
    elif re.search(r'Fatal signal \d+.*\(' + p + r'\)', l) or re.search(r'>>> ' + p + r' <<<', l):
        out.append(lines[i:i + 1] + [b for b in lines[i + 1:i + 30] if re.search(r'\bDEBUG\b|libc', b)][:20])
    elif re.search(r'ANR in ' + p + r'(\s|$|\()', l):
        out.append(lines[i:i + 6])
    i += 1
for blok in out:
    print('\n'.join(blok)); print('----')
PY
}

# Zelfcontrole van crashregels() met voorbeeldregels (draait ook in de workflow, vóór de emulator start).
zelftest() {
  local d; d=$(mktemp -d)
  cat > "$d/goed.txt" <<'EOF'
10-24 10:00:01.000  1234  1234 I ActivityManager: Start proc 4321:nl.rene.tools/u0a90 for activity nl.rene.tools/.MainActivity
10-24 10:00:02.000  4321  4321 D chromium: [INFO:CONSOLE(1)] "klaar"
10-24 10:00:03.000  5555  5555 E AndroidRuntime: FATAL EXCEPTION: main
10-24 10:00:03.000  5555  5555 E AndroidRuntime: Process: com.android.anders, PID: 5555
10-24 10:00:03.000  5555  5555 E AndroidRuntime: java.lang.RuntimeException: niet van ons
10-24 10:00:04.000  6666  6666 E AndroidRuntime: FATAL EXCEPTION: main
10-24 10:00:04.000  6666  6666 E AndroidRuntime: Process: nl.rene.toolsx, PID: 6666
10-24 10:00:05.000  1234  1300 E ActivityManager: ANR in com.android.anders
EOF
  cat > "$d/java.txt" <<'EOF'
10-24 10:00:01.000  4321  4321 I ActivityManager: Start proc 4321:nl.rene.tools/u0a90
10-24 10:00:03.100  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
10-24 10:00:03.100  4321  4321 E AndroidRuntime: Process: nl.rene.tools, PID: 4321
10-24 10:00:03.100  4321  4321 E AndroidRuntime: java.lang.NoSuchMethodError: No virtual method foo()
10-24 10:00:03.100  1234  1234 I ActivityManager: iets anders
10-24 10:00:03.100  4321  4321 E AndroidRuntime: 	at nl.rene.tools.MainActivity.onCreate(MainActivity.java:42)
EOF
  cat > "$d/native.txt" <<'EOF'
10-24 10:00:03.000  4321  4400 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 4400 (pool-1), pid 4321 (nl.rene.tools)
10-24 10:00:03.200  7000  7000 F DEBUG   : pid: 4321, tid: 4400, name: pool-1  >>> nl.rene.tools <<<
EOF
  cat > "$d/anr.txt" <<'EOF'
10-24 10:00:09.000  1234  1300 E ActivityManager: ANR in nl.rene.tools (nl.rene.tools/.MainActivity)
10-24 10:00:09.000  1234  1300 E ActivityManager: PID: 4321
EOF
  cat > "$d/brief.txt" <<'EOF'
E/AndroidRuntime( 4321): FATAL EXCEPTION: main
E/AndroidRuntime( 4321): Process: nl.rene.tools, PID: 4321
E/AndroidRuntime( 4321): java.lang.IllegalStateException: kapot
EOF
  local fout=0 f n
  [ -z "$(crashregels "$d/goed.txt" nl.rene.tools)" ] || { echo "zelftest: goed.txt gaf ten onrechte een crash"; fout=1; }
  for f in java native anr brief; do
    n=$(crashregels "$d/$f.txt" nl.rene.tools)
    [ -n "$n" ] || { echo "zelftest: crash in $f.txt niet herkend"; fout=1; }
  done
  n=$(crashregels "$d/java.txt" nl.rene.tools)
  echo "$n" | grep -q 'MainActivity.java:42' || { echo "zelftest: stapelregel ontbreekt"; fout=1; }
  echo "$n" | grep -q 'iets anders' && { echo "zelftest: regel van een ander proces meegenomen"; fout=1; }
  rm -rf "$d"
  [ $fout = 0 ] && echo "zelftest crashcontrole: goed"
  return $fout
}

case "${1:-}" in
  --manifest) manifest_info; exit $? ;;
  --log) crashregels "$2" "${3:-$(manifest_info | sed -n 1p)}"; exit 0 ;;
  --zelftest) zelftest; exit $? ;;
  ''|-*) sed -n '2,10p' "$ZELF"; exit 2 ;;
esac

APK=$1
UIT=${2:-build/telefoon}
[ -f "$APK" ] || { echo "::error title=Telefoontest::APK niet gevonden: $APK"; exit 1; }
command -v adb >/dev/null || PATH="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools:$PATH"
mkdir -p "$UIT"
mapfile -t INFO < <(manifest_info)
PKG=${INFO[0]:-}; LAUNCHER=${INFO[1]:-}; HOME_ACT=${INFO[2]:-}
[ -n "$PKG" ] && [ -n "$LAUNCHER" ] || { echo "::error title=Telefoontest::Manifest niet te lezen"; exit 1; }
echo "Pakket $PKG, startscherm $LAUNCHER, Home ${HOME_ACT:-(geen)}"
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Android API $SDK"
FOUTEN=0

# Annotatie in GitHub (één regel; % en regeleinden gecodeerd zoals Actions dat wil)
meld() {
  local titel=$1 tekst=$2
  tekst=${tekst//'%'/'%25'}; tekst=${tekst//$'\r'/}; tekst=${tekst//$'\n'/'%0A'}
  echo "::error title=Telefoontest $titel (API $SDK)::$tekst"
}

draait() {
  local pid
  pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')
  [ -n "$pid" ] && return 0
  # Android 8+ wil "ps -A"; Android 7 kent dat niet en toont alles met "ps"
  adb shell 'ps -A 2>/dev/null; ps 2>/dev/null' | tr -d '\r' | awk -v p="$PKG" '$NF == p { found = 1 } END { exit !found }'
}

# Eén stap: start een scherm, wacht, schermafdruk, controleer proces en logcat.
stap() {
  local naam=$1 moet_draaien=$2; shift 2
  echo "== $naam: am start $*"
  adb shell am start -W "$@" 2>&1 | tr -d '\r' | tee "$UIT/$naam-am.txt"
  sleep "$WACHT"
  adb exec-out screencap -p > "$UIT/$naam.png" 2>/dev/null || echo "(geen schermafdruk)"
  adb logcat -d -v threadtime > "$UIT/logcat-$naam.txt" 2>&1
  local crash; crash=$(crashregels "$UIT/logcat-$naam.txt" "$PKG")
  if [ -n "$crash" ]; then
    echo "!! $naam: crash"; echo "$crash"
    meld "$naam: crash" "$(echo "$crash" | head -30)"
    FOUTEN=$((FOUTEN + 1))
  elif grep -qE '^Error:|Exception' "$UIT/$naam-am.txt"; then
    meld "$naam: niet te starten" "$(cat "$UIT/$naam-am.txt")"
    FOUTEN=$((FOUTEN + 1))
  elif [ "$moet_draaien" = ja ] && ! draait; then
    meld "$naam: app draait niet meer" "Het proces $PKG is gestopt zonder crashmelding in logcat (zie het artifact)."
    FOUTEN=$((FOUTEN + 1))
  else
    echo "ok: $naam"
  fi
  # Crashes van deze stap niet nog eens bij de volgende melden
  adb logcat -c 2>/dev/null || true
}

adb shell input keyevent 82 >/dev/null 2>&1 || true   # scherm ontgrendelen
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
echo "== installeren"
adb install -r -g "$APK" > "$UIT/install.txt" 2>&1 || true
if ! grep -q Success "$UIT/install.txt"; then
  # -g (alle rechten) niet nodig voor deze test; zonder proberen als het oude Android het weigert
  adb install -r "$APK" >> "$UIT/install.txt" 2>&1 || true
  grep -q Success "$UIT/install.txt" || { meld installeren "$(cat "$UIT/install.txt")"; exit 1; }
fi
adb logcat -c 2>/dev/null || true

stap 1-start ja -n "$PKG/$LAUNCHER" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
[ -n "$HOME_ACT" ] && stap 2-home ja -n "$PKG/$HOME_ACT"
# Een paar andere schermen die van buitenaf te openen zijn
stap 3-pdf-zonder-bestand ja -n "$PKG/.PdfViewActivity" -a android.intent.action.VIEW
stap 4-delen-pdf ja -n "$PKG/.PdfShareActivity" -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "Rooktest"
# Tot slot terug naar het gewone startscherm van Tools: draait de app nog?
stap 5-terug ja -n "$PKG/$LAUNCHER" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER

if [ "$FOUTEN" -gt 0 ]; then
  echo "Telefoontest (API $SDK): $FOUTEN stap(pen) mislukt; logcat en schermafdrukken staan in het artifact."
  exit 1
fi
echo "Telefoontest (API $SDK): geslaagd"
