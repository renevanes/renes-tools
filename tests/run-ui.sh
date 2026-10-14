#!/usr/bin/env bash
# Draait alle interfacetests (Playwright, telefoonformaat 390x844, licht en donker).
# De interface draait met een nagebootste Android-brug (window.Android ontbreekt in de browser).
# Vereist: node + playwright (npm i playwright) met Chromium.
set -uo pipefail
cd "$(dirname "$0")/.."
python3 tools/check-bridge.py || exit 1
# Met de test-brug (de app zelf wordt zonder gebouwd)
./tests/ui-pagina.sh || exit 1
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
