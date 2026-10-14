#!/usr/bin/env bash
# Eén of een paar interfacetests draaien, bijv.: ./tests/ui-een.sh calls spotify2
# (naam zonder .test.js). Geslaagd = de test drukt "errors []" af en eindigt zonder fout.
set -uo pipefail
cd "$(dirname "$0")/.."
[ $# -gt 0 ] || { echo "Gebruik: ./tests/ui-een.sh <naam> [naam …]   (zie tests/ui/*.test.js)"; exit 1; }
./tests/ui-pagina.sh || exit 1
fail=0
for t in "$@"; do
  out=$(timeout 300 node "tests/ui/${t%.test.js}.test.js" 2>&1); code=$?
  total=$(grep -o "errors \[" <<<"$out" | wc -l); empty=$(grep -o "errors \[\]" <<<"$out" | wc -l)
  if [ $code -ne 0 ] || [ "$empty" -eq 0 ] || [ "$total" -ne "$empty" ]; then echo "✗ $t"; echo "$out" | tail -25; fail=1; else echo "✓ $t"; fi
done
exit $fail
