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
[[ $JOBS =~ ^[0-9]+$ ]] || JOBS=2
[ "$JOBS" -gt 4 ] && JOBS=4
[ "$JOBS" -lt 1 ] && JOBS=1
UI_BASE_WACHT=${UI_WACHT:-1}
[[ $UI_BASE_WACHT =~ ^[0-9]+(\.[0-9]+)?$ ]] || UI_BASE_WACHT=1
export UI_BASE_WACHT
RES=$(mktemp -d) || exit 1
trap 'rm -rf "$RES"' EXIT
export RES PRELOAD="$PWD/tests/ui/_traag.js"

een() {
  local t=$1 n out code total empty poging w
  n=$(basename "$t")
  for poging in 1 2; do
    w=$(awk -v a="$UI_BASE_WACHT" -v b="$poging" 'BEGIN{print a*b}')
    out=$(UI_WACHT=$w timeout -k 15 $((240 * poging)) node -r "$PRELOAD" "$t" 2>&1); code=$?
    # Geslaagd = exitcode 0, minstens één "errors []" en geen enkele niet-lege foutenlijst
    # (een lange fout drukt node af als "errors [" + nieuwe regel, dus elke "errors [" moet direct "]" hebben)
    total=$(grep -o "errors \[" <<<"$out" | wc -l); empty=$(grep -o "errors \[\]" <<<"$out" | wc -l)
    if [ $code -eq 0 ] && [ "$empty" -gt 0 ] && [ "$total" -eq "$empty" ]; then
      if [ "$poging" -eq 1 ]; then echo "✓ $n" > "$RES/$n"; else echo "↻ $n (slaagde pas bij de tweede poging, met langere wachttijden)" > "$RES/$n"; fi
      echo "· $(head -c 3 "$RES/$n") $n" >&2  # voortgang (met "·" ervoor: telt niet mee in de uitslag)
      return 0
    fi
  done
  { echo "✗ $n"; echo "$out" | tail -25; } > "$RES/$n"
  echo "· ✗ $n" >&2
}
export -f een

# Voortgang gaat naar stderr; de uitslag hieronder (op volgorde) naar stdout
printf '%s\0' tests/ui/*.test.js | xargs -0 -P "$JOBS" -n1 bash -c 'een "$1"' _ >/dev/null

fail=0; flaky=0; ok=0; nfail=0
for t in tests/ui/*.test.js; do
  n=$(basename "$t")
  if [ ! -f "$RES/$n" ]; then echo "✗ $n (geen uitslag)"; fail=1; nfail=$((nfail+1)); continue; fi
  cat "$RES/$n"
  case "$(head -c 3 "$RES/$n")" in "✓"*) ok=$((ok+1));; "↻"*) flaky=$((flaky+1));; *) fail=1; nfail=$((nfail+1));; esac
done
echo "Interfacetests: $ok geslaagd, $flaky pas bij de tweede poging, $nfail mislukt ($JOBS tegelijk)"
exit $fail
