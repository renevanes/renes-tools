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
# Sessie-link in de commit: van de sessie die publiceert (CLAUDE_SESSION_URL), anders zonder.
SESSION_LINE=""
[ -n "${CLAUDE_SESSION_URL:-}" ] && SESSION_LINE="
Claude-Session: $CLAUDE_SESSION_URL"
git add -A
git -c user.name=renevanes -c user.email=rene.van.es@esape.nl commit -q -m "Versie $VERSION_NAME

$(python3 -c "import json;print('\n'.join('- '+c for c in json.load(open('changelog.json'))[0]['changes']))")

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>$SESSION_LINE" || true
git push origin HEAD:main
echo "Gepubliceerd: versie $VERSION_NAME"
