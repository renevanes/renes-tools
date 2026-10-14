#!/usr/bin/env bash
# Maakt de testpagina voor de interfacetests (tests/ui/ui/index.html): de interface met de nagebootste Android-brug.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p tests/ui/ui/vendor tests/ui/shots
cp web/vendor/* tests/ui/ui/vendor/
python3 tools/web-samenvoegen.py tests/ui/ui/samengevoegd.html >/dev/null
python3 - <<'PY'
import json
s = open('tests/ui/ui/samengevoegd.html', encoding='utf-8').read()
v = dict(l.strip().split('=', 1) for l in open('VERSION') if '=' in l)
s = s.replace('__VERSION_NAME__', v['VERSION_NAME']).replace('__VERSION_CODE__', v['VERSION_CODE'])
s = s.replace('__CHANGELOG__', json.dumps(json.load(open('changelog.json')), ensure_ascii=False))
open('tests/ui/ui/index.html', 'w', encoding='utf-8').write(s)
PY
