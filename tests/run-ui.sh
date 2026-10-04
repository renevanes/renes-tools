#!/usr/bin/env bash
# Draait alle interfacetests (Playwright, telefoonformaat 390x844, licht en donker).
# De interface draait met een nagebootste Android-brug (window.Android ontbreekt in de browser).
# Vereist: node + playwright (npm i playwright) met Chromium.
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p tests/ui/ui/vendor tests/ui/shots
cp web/vendor/* tests/ui/ui/vendor/
python3 tools/web-samenvoegen.py >/dev/null
python3 - <<'PY'
import json
s = open('web/index.html', encoding='utf-8').read()
v = dict(l.strip().split('=', 1) for l in open('VERSION') if '=' in l)
s = s.replace('__VERSION_NAME__', v['VERSION_NAME']).replace('__VERSION_CODE__', v['VERSION_CODE'])
s = s.replace('__CHANGELOG__', json.dumps(json.load(open('changelog.json')), ensure_ascii=False))
open('tests/ui/ui/index.html', 'w', encoding='utf-8').write(s)
PY
fail=0
for t in tests/ui/*.test.js; do
  out=$(timeout 180 node "$t" 2>&1); code=$?
  if [ $code -ne 0 ] || ! grep -q "errors \[\]" <<<"$out" || grep -q "errors \[ '" <<<"$out"; then
    echo "✗ $(basename $t)"; echo "$out" | tail -25; fail=1
  else
    echo "✓ $(basename $t)"
  fi
done
exit $fail
