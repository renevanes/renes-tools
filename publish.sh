#!/usr/bin/env bash
# Zet de gebouwde versie op GitHub: commit, tag vX.Y en push.
# Daarna halen telefoons de update automatisch op.
set -euo pipefail
cd "$(dirname "$0")"
source VERSION
[ -f update/update.json ] || { echo "Eerst ./build.sh"; exit 1; }
grep -q "\"versionCode\": $VERSION_CODE," update/update.json || { echo "update/ hoort niet bij VERSION, draai ./build.sh"; exit 1; }
git add -A
git -c user.name=renevanes -c user.email=rene.van.es@esape.nl commit -q -m "Versie $VERSION_NAME

$(python3 -c "import json;print('\n'.join('- '+c for c in json.load(open('changelog.json'))[0]['changes']))")

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XcmSGKzsXEZ3nh64hG9xd2" || true
git tag -f "v$VERSION_NAME"
git push origin HEAD:main
git push -f origin "v$VERSION_NAME"
echo "Gepubliceerd: versie $VERSION_NAME"
