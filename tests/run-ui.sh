#!/usr/bin/env bash
# Draait alle interfacetests (Playwright, telefoonformaat 390x844, licht en donker).
# De interface draait met een nagebootste Android-brug (window.Android ontbreekt in de browser).
# Vereist: node + playwright (npm i playwright) met Chromium.
#
# Sneller en minder wankel:
#  - tests draaien naast elkaar (UI_JOBS, standaard het aantal processorkernen, hooguit 4);
#  - mislukt een test, dan nog één keer, met alle vaste wachttijden twee keer zo lang (UI_WACHT). Slaagt hij dan,
#    dan staat er "↻" bij: dat is een wankele test (te krappe wachttijd), niet iets wat stuk is, maar wel iets om
#    te verbeteren. Mislukt hij twee keer, dan is het echt mis.
#  - UI_WACHT=1.5 ./tests/run-ui.sh maakt alle wachttijden langer (bijv. op een trage machine).
set -uo pipefail
cd "$(dirname "$0")/.."
python3 tools/check-bridge.py || exit 1
# Met de test-brug (de app zelf wordt zonder gebouwd)
./tests/ui-pagina.sh || exit 1
JOBS=${UI_JOBS:-$(nproc 2>/dev/null || echo 2)}
[ "$JOBS" -gt 4 ] && JOBS=4
[ "$JOBS" -lt 1 ] && JOBS=1
export UI_BASE_WACHT=${UI_WACHT:-1}
RES=$(mktemp -d); trap 'rm -rf "$RES"' EXIT
export RES PRELOAD="$PWD/tests/ui/_traag.js"

een() {
  local t=$1 n out code total empty poging w
  n=$(basename "$t")
  for poging in 1 2; do
    w=$(python3 -c "print($UI_BASE_WACHT * $poging)")
    out=$(UI_WACHT=$w timeout 300 node -r "$PRELOAD" "$t" 2>&1); code=$?
    # Geslaagd = exitcode 0, minstens één "errors []" en geen enkele niet-lege foutenlijst
    # (een lange fout drukt node af als "errors [" + nieuwe regel, dus elke "errors [" moet direct "]" hebben)
    total=$(grep -o "errors \[" <<<"$out" | wc -l); empty=$(grep -o "errors \[\]" <<<"$out" | wc -l)
    if [ $code -eq 0 ] && [ "$empty" -gt 0 ] && [ "$total" -eq "$empty" ]; then
      if [ "$poging" -eq 1 ]; then echo "✓ $n" > "$RES/$n"; else echo "↻ $n (slaagde pas bij de tweede poging, met langere wachttijden)" > "$RES/$n"; fi
      return 0
    fi
  done
  { echo "✗ $n"; echo "$out" | tail -25; } > "$RES/$n"
}
export -f een

ls tests/ui/*.test.js | xargs -P "$JOBS" -I{} bash -c 'een "$@"' _ {}

fail=0; flaky=0; ok=0
for t in tests/ui/*.test.js; do
  n=$(basename "$t")
  if [ ! -f "$RES/$n" ]; then echo "✗ $n (geen uitslag)"; fail=1; continue; fi
  cat "$RES/$n"
  case "$(head -c 3 "$RES/$n")" in "✓"*) ok=$((ok+1));; "↻"*) flaky=$((flaky+1));; *) fail=1;; esac
done
echo "Interfacetests: $ok geslaagd, $flaky pas bij de tweede poging, $([ $fail -eq 0 ] && echo 0 || grep -l "^✗" "$RES"/* | wc -l) mislukt ($JOBS tegelijk)"
exit $fail
