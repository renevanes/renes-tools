#!/usr/bin/env bash
# Publiceert een nieuwe versie. Telefoons halen hem daarna automatisch op.
# Werkt ook als meerdere agents (Claude, Codex, …) of mensen tegelijk werken; zie AGENTS.md.
#
#   ./publish.sh                    wijzigingsregels komen uit wijzigingen/*.txt (één regel per wijziging)
#   ./publish.sh "regel" "regel"    regels direct meegeven (wordt eerst een bestand in wijzigingen/)
#   MAJOR=1 ./publish.sh            1.9 wordt 2.0
#   AGENT=Codex ./publish.sh        wie publiceert (komt in de commit); Claude-sessies geven CLAUDE_SESSION_URL mee
#   SKIP_TESTS=1 / ALLOW_GROW=1     tests overslaan / een flink grotere APK toestaan (alleen als je weet waarom)
#   VOORBEREIDEN=bestand            alleen samenvoegen en versie en wijzigingslog klaarzetten zoals bij publiceren (zonder
#                                   sleutel, slot of push; alleen in een wegwerp-checkout: de werkmap blijft
#                                   samengevoegd en met de nieuwe versie achter); in het bestand komt de vingerafdruk (of "geen"). Zo draait
#                                   Publiceren de interfacetests naast de telefoontest; UI_GETEST=<vingerafdruk> slaat
#                                   ze daarna over, alleen als bron, versie en wijzigingslog precies gelijk zijn
#
# Wat het doet:
#  1. je werk vastleggen in een gewone commit (alles behalve VERSION, changelog.json en update/)
#  2. het publicatieslot op GitHub nemen (branch "publiceren-slot"); publiceert iemand anders, dan wacht je
#  3. de nieuwste main ophalen en samenvoegen (merge; nooit geschiedenis herschrijven)
#  4. versienummer bepalen vanaf main (NATIVE_LEVEL gaat vanzelf omhoog als iets in de APK veranderde)
#  5. wijzigingslog schrijven, bouwen, alle tests, "Versie X.Y" committen en naar main pushen
#  6. versie, main en unieke versietags atomair vastleggen; bij een botsing opnieuw vanaf stap 3
set -euo pipefail
cd "$(dirname "$0")"
LOCK=refs/heads/publiceren-slot
# Vingerafdruk van het echte ondertekeningscertificaat (openbaar; staat ook in elke APK)
CERT_SHA256="F7:8F:8F:09:83:20:FB:85:37:2E:4A:35:A4:5E:44:84:29:B3:CF:F3:33:E2:AD:30:E3:A7:1F:2D:75:AC:CC:C7"
# Wat in de APK zit (en niet in update/): verandert dat, dan moet NATIVE_LEVEL omhoog
APK_PATHS=(app web/vendor web/neutraal.html web/ontsleutelen.html web/facturen-robot.js web/facturen-leveranciers.json web/bank-robot.js web/bank-banken.json web/src/script/00-tools.js tools/tool-register.js build.sh)
EMPTY_TREE=$(git hash-object -t tree /dev/null)
WHO=${AGENT:-$([ -n "${CLAUDE_SESSION_URL:-}" ] && echo Claude || echo "$(git config user.name 2>/dev/null || echo onbekend)")}
TOKEN="$WHO $$@$(hostname 2>/dev/null || echo host)-$RANDOM"
if [ "${GITHUB_ACTIONS:-}" = "true" ]; then # publicatie door GitHub zelf (.github/workflows/publiceren.yml)
  GIT_ID=(-c user.name=renes-tools-release -c "user.email=41898282+github-actions[bot]@users.noreply.github.com")
else
  GIT_ID=(-c user.name=renevanes -c user.email=rene.van.es@esape.nl)
fi
mkdir -p build wijzigingen

VOORBEREIDEN=${VOORBEREIDEN:-}
if [ -z "$VOORBEREIDEN" ]; then
# De echte sleutel, en geen testsleutel
[ -f keys/release.p12 ] && [ -f keys/keystore.pass ] || { echo "De ondertekeningssleutel ontbreekt. Pak hem uit met: PASSPHRASE=… ./keys/unlock.sh (de wachtwoordzin krijg je van Rene; nooit in de repository, op GitHub alleen als geheim SIGNING_PASSPHRASE). Zonder sleutel: push naar main, dan publiceert GitHub het"; exit 1; }
FP=$(openssl pkcs12 -in keys/release.p12 -passin file:keys/keystore.pass -nokeys -clcerts 2>/dev/null | openssl x509 -noout -fingerprint -sha256 2>/dev/null | sed 's/.*=//' || true)
[ "$FP" = "$CERT_SHA256" ] || { echo "keys/release.p12 is niet de echte sleutel van Rene's Tools (vingerafdruk ${FP:-onleesbaar}). Met een andere sleutel kunnen telefoons de update niet installeren."; exit 1; }
# Alleen met de vastgezette build-tools van Google (niet met de reserve voor lokaal testen); build.sh gebruikt BT_VERSION
BT_VERSION=$(sed -n 's/^BUILD_TOOLS=\([0-9.]*\)$/\1/p' setup-tools.sh)
[ -n "$BT_VERSION" ] && [ -f "${TOOLS:-/home/claude/toolchain}/bt/build-tools/$BT_VERSION/lib/d8.jar" ] || { echo "Build-tools ${BT_VERSION:-?} (Google) ontbreken in ${TOOLS:-/home/claude/toolchain}: ./setup-tools.sh met toegang tot dl.google.com. Publiceren met andere build-tools gebeurt niet."; exit 1; }
export BT_VERSION
fi

# update/ maakt ./build.sh bij elke bouw opnieuw: wat daar lokaal staat telt niet
git checkout -q HEAD -- update 2>/dev/null || true
# Versie en wijzigingslog maakt alleen dit script
if ! git diff --quiet HEAD -- VERSION changelog.json; then
  echo "VERSION of changelog.json is met de hand veranderd. Zet ze terug met: git checkout HEAD -- VERSION changelog.json wijzigingen"
  echo "en schrijf je wijzigingsregels in wijzigingen/<onderwerp>.txt."; exit 1
fi
# Alleen bekende plekken; onbekende nieuwe bestanden eerst zelf bekijken
UNKNOWN=$(git status --porcelain --untracked-files=all | sed -n 's/^?? //p' | grep -vE '^(app/|web/|tests/|tools/|docs/|\.github/|wijzigingen/|package(-lock)?\.json$|README\.md$|AGENTS\.md$|CLAUDE\.md$|CONTRIBUTING\.md$|SECURITY\.md$|LICENSE$|[a-z-]+\.sh$|keys/unlock\.sh$)' || true)
[ -z "$UNKNOWN" ] || { echo "Onbekende nieuwe bestanden (niet gepubliceerd):"; echo "$UNKNOWN"; exit 1; }

# Regels uit de argumenten
if [ $# -gt 0 ]; then
  f="wijzigingen/$(date +%Y%m%d-%H%M%S)-$$.txt"; printf '%s\n' "$@" > "$f"; echo "Wijzigingsregels in $f"
fi
notes(){ cat wijzigingen/*.txt 2>/dev/null | sed 's/\r$//; s/^[-•*] *//' | grep -v '^\s*$' || true; }
check_nep(){ if notes | grep -qiE '(^|[^[:alpha:]])n[e]p'; then echo "Het woord n-e-p (fake) mag niet in de wijzigingsregels (wens van Rene). Pas wijzigingen/*.txt aan."; exit 1; fi; }
check_nep

# 1. Eigen werk vastleggen (los van de versiecommit); bij voorbereiden moet alles al vastgelegd zijn
if [ -n "$VOORBEREIDEN" ] && [ -n "$(git status --porcelain)" ]; then
  echo "Voorbereiden kan alleen met een schone werkmap:"; git status --short; exit 1
fi
git add -A -- . ':!VERSION' ':!changelog.json' ':!update'
if ! git diff --cached --quiet; then
  first=$(notes | head -1); git "${GIT_ID[@]}" commit -q -m "Werk: ${first:-wijzigingen} ($WHO)"
  echo "Werk vastgelegd: $(git log -1 --format=%h)"
fi
[ -z "$(git status --porcelain)" ] || { echo "Er staan nog niet-vastgelegde wijzigingen:"; git status --short; exit 1; }

# 2. Publicatieslot: een branch die alleen vooruit gaat. Nemen en vrijgeven zijn gewone pushes met
#    --force-with-lease als controle (compare-and-swap); de branch wordt nooit verwijderd.
DIRTY=""
restore(){ git checkout -q HEAD -- VERSION changelog.json update 2>/dev/null || true; git checkout -q HEAD -- wijzigingen 2>/dev/null || true; }
# shellcheck source=tools/publicatie-slot.sh
source tools/publicatie-slot.sh
cleanup(){ local rc=$?; [ -n "$DIRTY" ] && restore; slot_free || true; exit "$rc"; }
trap cleanup EXIT
[ -n "$VOORBEREIDEN" ] || slot_take
# Wat de interfacetests zien: de bron (HEAD, schone werkmap) plus de nieuwe VERSION en changelog.json
vingerafdruk(){ { git rev-parse 'HEAD^{tree}'; sha256sum < VERSION; sha256sum < changelog.json; } | sha256sum | cut -c1-64; }
voorbereid(){ echo "$1" > "$VOORBEREIDEN"; DIRTY=""; echo "Voorbereid: $1"; exit 0; }

for try in 1 2 3 4 5; do
  slot_refresh
  # 3. Nieuwste main samenvoegen
  git fetch -q origin main
  if [ "$(git rev-parse --is-shallow-repository)" = true ]; then git fetch -q --unshallow origin main; fi
  BASE=$(git rev-parse FETCH_HEAD)
  if ! git merge-base --is-ancestor "$BASE" HEAD; then
    git "${GIT_ID[@]}" merge -q --no-edit -m "Samenvoegen met main" "$BASE" || {
      git merge --abort 2>/dev/null || true
      echo "Je werk botst met wat er intussen op main staat. Los het op (git merge origin/main), test, en publiceer opnieuw."; exit 1; }
  fi
  # De emulator heeft deze bron getest. Een nieuwere bron vraagt een nieuwe preflight, ook bij een pushretry.
  if [ -n "${TESTED_SOURCE_SHA:-}" ] || [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    ./tools/check-publish-source.sh
  fi
  check_nep # ook regels die via main (pull requests) binnenkwamen
  # Vorige versie = laatste commit die update/update.json veranderde (dat doet alleen dit script)
  LASTREL=$(git log -1 --format=%H "$BASE" -- update/update.json)
  if git diff --quiet "$LASTREL" HEAD -- app web tools build.sh; then
    # Alleen documentatie, tests of instellingen: zonder nieuwe versie naar main
    [ -n "$VOORBEREIDEN" ] && voorbereid geen
    if ! git diff --quiet "$BASE" HEAD; then
      git push -q origin HEAD:main && { echo "Zonder nieuwe versie naar main gezet (geen wijziging aan de app)"; exit 0; }
      continue
    fi
    echo "Niets nieuws om te publiceren"; exit 0
  fi
  # Op GitHub zonder wijzigingsregels: de onderwerpen van de nieuwe commits gebruiken
  if [ -z "$(notes)" ] && [ -n "${AUTO_NOTES:-}" ]; then
    git log --no-merges --format=%s "$LASTREL..HEAD" -- app web tools build.sh | grep -vE '^(Werk:|Versie [0-9]|Samenvoegen)' | grep -viE '(^|[^[:alpha:]])n[e]p' | sed 's/ *\[skip ci\]//' | head -8 > wijzigingen/automatisch.txt || true
    [ -s wijzigingen/automatisch.txt ] || echo "Kleine verbeteringen" > wijzigingen/automatisch.txt
    check_nep
  fi
  [ -n "$(notes)" ] || { echo "Schrijf eerst in wijzigingen/<onderwerp>.txt wat er voor Rene verandert (één regel per wijziging, in het Nederlands)."; exit 1; }

  # 4. Gepubliceerde nummers blijven verbruikt, ook als iemand VERSION terugzet.
  git fetch -q --no-tags --no-write-fetch-head origin \
    'refs/tags/versie-*:refs/tags/versie-*' 'refs/tags/versiecode-*:refs/tags/versiecode-*'
  NEXT=$(python3 tools/publicatie-versie.py volgende "$BASE" "${MAJOR:-0}")
  read -r NAME CODE NAT <<< "$NEXT"
  git diff --quiet "$LASTREL" HEAD -- "${APK_PATHS[@]}" || NAT=$((NAT+1))
  DIRTY=1
  sed -i "s/^VERSION_NAME=.*/VERSION_NAME=$NAME/; s/^VERSION_CODE=.*/VERSION_CODE=$CODE/; s/^NATIVE_LEVEL=.*/NATIVE_LEVEL=$NAT/" VERSION
  notes | python3 -c "
import sys, json, datetime
changes = [l.strip() for l in sys.stdin if l.strip()]
log = json.load(open('changelog.json'))
log.insert(0, {'version': '$NAME', 'date': datetime.date.today().isoformat(), 'changes': changes})
json.dump(log, open('changelog.json', 'w'), ensure_ascii=False, indent=2)"
  git rm -q --cached --ignore-unmatch -- 'wijzigingen/*.txt' >/dev/null; rm -f wijzigingen/*.txt
  echo "Versie $NAME (code $CODE, native $NAT) door $WHO"
  AFDRUK=$(vingerafdruk)
  [ -n "$VOORBEREIDEN" ] && voorbereid "$AFDRUK"

  # 5. Bouwen en testen (bij een fout zet de opruimfunctie VERSION, changelog en wijzigingen terug)
  ./build.sh > build/publish-build.log 2>&1 || { tail -30 build/publish-build.log; echo "Bouwen mislukt: niet gepubliceerd"; exit 1; }
  if [ "${SKIP_TESTS:-}" != "1" ]; then
    ./tests/run-jvm.sh > build/publish-jvm.log 2>&1 || { grep -v "^✓" build/publish-jvm.log | tail -20; echo "Java-tests mislukt: niet gepubliceerd"; exit 1; }
    # De vingerafdruk gaat over HEAD^{tree}: verder mag de werkmap alleen de versie, het wijzigingslog, update/ (bouwen)
    # en de weggehaalde wijzigingsregels anders hebben, anders hier opnieuw testen
    ANDERS=""
    if [ -n "${UI_GETEST:-}" ]; then
      ST=$(git status --porcelain --no-renames -unormal 2>/dev/null) || ST="?? (git status mislukt)"
      ANDERS=$(printf '%s\n' "$ST" | grep -Ev '^(.. (VERSION|changelog\.json|update/.*|wijzigingen/.*))?$' || true)
    fi
    if [ -n "${UI_GETEST:-}" ] && [ "$UI_GETEST" = "$AFDRUK" ] && [ "$AFDRUK" = "$(vingerafdruk)" ] && [ -z "$ANDERS" ]; then
      echo "Interfacetests: al geslaagd op precies deze bron, versie en wijzigingslog ($UI_GETEST)"
    else
      if [ -n "$ANDERS" ]; then echo "Werkmap anders dan de geteste bron ($(echo "$ANDERS" | head -3 | tr '\n' ' ')): hier opnieuw"
      elif [ -n "${UI_GETEST:-}" ]; then echo "Bron, versie of wijzigingslog anders dan bij de interfacetests ernaast ($UI_GETEST, hier $AFDRUK): hier opnieuw"; fi
      ./tests/run-ui.sh > build/publish-ui.log 2>&1 || { grep -Ev "^(✓|· |    eerste poging:)" build/publish-ui.log | tail -30; echo "Interfacetests mislukt: niet gepubliceerd"; exit 1; }
      grep -E '^(↻|    eerste poging:|Interfacetests:)' build/publish-ui.log || true # wankele tests (pas bij de tweede poging) zichtbaar in het logboek
    fi
  fi
  PREV_APK=$(git cat-file -s "$BASE:update/Renes-Tools.apk" 2>/dev/null || echo 0); NEW_APK=$(stat -c %s update/Renes-Tools.apk)
  if [ "$PREV_APK" -gt 0 ] && [ $((NEW_APK * 10)) -gt $((PREV_APK * 11)) ] && [ "${ALLOW_GROW:-}" != "1" ]; then
    echo "De APK is meer dan 10% groter dan de vorige ($PREV_APK → $NEW_APK bytes). Klopt dat? Dan ALLOW_GROW=1"; exit 1
  fi

  # 6. Alleen een nog geldige slothouder mag de versie vastleggen.
  slot_refresh
  TRAILER=""
  if [ -n "${CLAUDE_SESSION_URL:-}" ]; then TRAILER="Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: $CLAUDE_SESSION_URL"
  elif [ -n "${AGENT:-}" ]; then TRAILER="Gepubliceerd-door: $AGENT"; fi
  git add -A -- VERSION changelog.json update wijzigingen
  git "${GIT_ID[@]}" commit -q -m "Versie $NAME

$(python3 -c "import json;print('\n'.join('- '+c for c in json.load(open('changelog.json'))[0]['changes']))")
${TRAILER:+
$TRAILER}"
  DIRTY=""
  if tools/publicatie-push.sh "$BASE" "$(git rev-parse HEAD)" "$NAME" "$CODE"; then echo "Gepubliceerd: versie $NAME"; exit 0; fi
  echo "Iemand publiceerde net buiten het slot om; opnieuw samenvoegen en bouwen (poging $((try+1)))"
  git reset -q --hard HEAD~1 # alleen de eigen, niet-gepubliceerde versiecommit; de werkmap was schoon
done
echo "Publiceren lukte na 5 pogingen niet"; exit 1
