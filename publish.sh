#!/usr/bin/env bash
# Zet de gebouwde versie op GitHub: één commit "Versie X.Y" en push.
# Daarna halen telefoons de update automatisch op.
set -euo pipefail
cd "$(dirname "$0")"
source VERSION
[ -f update/update.json ] || { echo "Eerst ./build.sh"; exit 1; }
grep -q "\"versionCode\": $VERSION_CODE," update/update.json || { echo "update/ hoort niet bij VERSION, draai ./build.sh"; exit 1; }
grep -q '"webSig2"' update/update.json || { echo "update.json is niet ondertekend, draai ./build.sh"; exit 1; }

# Werken er meerdere sessies tegelijk? Dan eerst controleren of niemand anders intussen publiceerde.
git fetch -q origin main
if ! git merge-base --is-ancestor FETCH_HEAD HEAD; then
  echo "Er is intussen een nieuwere versie op GitHub gezet. Haal die eerst op (git pull), en verhoog daarna opnieuw de versie."
  exit 1
fi
REMOTE_CODE=$(git show FETCH_HEAD:VERSION 2>/dev/null | sed -n 's/^VERSION_CODE=//p')
if [ -n "$REMOTE_CODE" ] && [ "$VERSION_CODE" -le "$REMOTE_CODE" ]; then
  echo "Versiecode $VERSION_CODE is niet hoger dan die op GitHub ($REMOTE_CODE). Draai eerst ./bump.sh."
  exit 1
fi
# Klopt wat er live gaat? (Telefoons halen de update direct na de push op; CI draait pas daarna.)
# Met SKIP_TESTS=1 alleen als je weet waarom.
if [ "${SKIP_TESTS:-}" != "1" ]; then
  ./tests/run-jvm.sh > build/publish-jvm.log 2>&1 || { grep -v "^✓" build/publish-jvm.log | tail -20; echo "Java-tests mislukt: niet gepubliceerd"; exit 1; }
  ./tests/run-ui.sh > build/publish-ui.log 2>&1 || { grep -v "^✓" build/publish-ui.log | tail -30; echo "Interfacetests mislukt: niet gepubliceerd"; exit 1; }
fi
# De APK moet bij de huidige bronnen horen (na ./build.sh niets meer veranderd)
NEWEST_SRC=$(find app web/src web/start.html -type f -newer update/update.json | head -1)
[ -z "$NEWEST_SRC" ] || { echo "Na de laatste ./build.sh is nog iets veranderd ($NEWEST_SRC): eerst opnieuw bouwen"; exit 1; }
# App-code veranderd? Dan moet NATIVE_LEVEL omhoog (anders krijgen telefoons een interface die functies mist)
PREV_NATIVE=$(git show HEAD:VERSION 2>/dev/null | sed -n 's/^NATIVE_LEVEL=//p')
if [ -n "$PREV_NATIVE" ] && ! git diff --quiet HEAD -- app && [ "$NATIVE_LEVEL" -le "$PREV_NATIVE" ]; then
  echo "app/ is veranderd maar NATIVE_LEVEL niet verhoogd: gebruik NATIVE=1 ./bump.sh"; exit 1
fi
# Groeit de APK ineens flink? Dan eerst bevestigen (ALLOW_GROW=1)
PREV_APK=$(git cat-file -s HEAD:update/Renes-Tools.apk 2>/dev/null || echo 0)
NEW_APK=$(stat -c %s update/Renes-Tools.apk)
if [ "$PREV_APK" -gt 0 ] && [ $((NEW_APK * 10)) -gt $((PREV_APK * 11)) ] && [ "${ALLOW_GROW:-}" != "1" ]; then
  echo "De APK is meer dan 10% groter dan de vorige ($PREV_APK → $NEW_APK bytes). Klopt dat? Dan ALLOW_GROW=1"; exit 1
fi
# Alleen wat bij de app hoort meenemen; onbekende nieuwe bestanden eerst zelf bekijken
UNKNOWN=$(git status --porcelain --untracked-files=all | sed -n 's/^?? //p' | grep -vE '^(app/|web/|tests/|tools/|update/|\.github/|README\.md$|changelog\.json$|VERSION$|[a-z-]+\.sh$)' || true)
[ -z "$UNKNOWN" ] || { echo "Onbekende nieuwe bestanden (niet gepubliceerd):"; echo "$UNKNOWN"; exit 1; }
# Sessie-link in de commit: van de sessie die publiceert (CLAUDE_SESSION_URL), anders zonder.
SESSION_LINE=""
[ -n "${CLAUDE_SESSION_URL:-}" ] && SESSION_LINE="
Claude-Session: $CLAUDE_SESSION_URL"
git add -A -- app web tests tools update .github README.md changelog.json VERSION ./*.sh
git diff --cached --quiet && { echo "Niets nieuws om te publiceren"; exit 1; }
git -c user.name=renevanes -c user.email=rene.van.es@esape.nl commit -q -m "Versie $VERSION_NAME

$(python3 -c "import json;print('\n'.join('- '+c for c in json.load(open('changelog.json'))[0]['changes']))")

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>$SESSION_LINE"
git push origin HEAD:main
echo "Gepubliceerd: versie $VERSION_NAME"
