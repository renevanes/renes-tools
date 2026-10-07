#!/usr/bin/env bash
# Draait alle interfacetests (Playwright, telefoonformaat 390x844, licht en donker).
# De interface draait met een nagebootste Android-brug (window.Android ontbreekt in de browser).
# Vereist: node + playwright (npm i playwright) met Chromium.
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p tests/ui/ui/vendor tests/ui/shots
cp web/vendor/* tests/ui/ui/vendor/
python3 tools/check-bridge.py || exit 1
# Met de nep-brug (de app zelf wordt zonder gebouwd)
python3 tools/web-samenvoegen.py tests/ui/ui/samengevoegd.html >/dev/null || exit 1
python3 - <<'PY'
import json
s = open('tests/ui/ui/samengevoegd.html', encoding='utf-8').read()
v = dict(l.strip().split('=', 1) for l in open('VERSION') if '=' in l)
s = s.replace('__VERSION_NAME__', v['VERSION_NAME']).replace('__VERSION_CODE__', v['VERSION_CODE'])
s = s.replace('__CHANGELOG__', json.dumps(json.load(open('changelog.json')), ensure_ascii=False))
open('tests/ui/ui/index.html', 'w', encoding='utf-8').write(s)
PY
fail=0
for t in tests/ui/*.test.js; do
  out=$(timeout 180 node "$t" 2>&1); code=$?
  # Geslaagd = exitcode 0, minstens één "errors []" en geen enkele niet-lege foutenlijst
  # (een lange fout drukt node af als "errors [" + nieuwe regel, dus elke "errors [" moet direct "]" hebben)
  total=$(grep -o "errors \[" <<<"$out" | wc -l); empty=$(grep -o "errors \[\]" <<<"$out" | wc -l)
  if [ $code -ne 0 ] || [ "$empty" -eq 0 ] || [ "$total" -ne "$empty" ]; then
    echo "✗ $(basename $t)"; echo "$out" | tail -25; fail=1
  else
    echo "✓ $(basename $t)"
  fi
done
exit $fail
