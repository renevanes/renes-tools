#!/usr/bin/env bash
# Test de AI-motor op de computer (x86_64-versie) met piepkleine modellen met willekeurige gewichten:
# het antwoord is onzin, maar het protocol (stukjes, klaar, stoppen, inkorten, zoekgetallen) wordt zo gecontroleerd.
# Gebruik: ./tools/llm-build.sh && ./tools/llm/test.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; L="$HERE/../llm-out/llm-x86_64"; T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
python3 "$HERE/mk-testmodel.py" "$T/chat.gguf" >/dev/null; python3 "$HERE/mk-testmodel.py" "$T/embed.gguf" 3 >/dev/null
fail=0; ok(){ if eval "$2"; then echo "✓ $1"; else echo "✗ $1"; fail=1; fi; }
out=$(printf '%s\n' '{"op":"chat","messages":[{"role":"user","content":"Hallo"}],"max":5}' 'kapot' | "$L" -m "$T/chat.gguf" -c 512 -t 2 --nothink 2>/dev/null)
ok "klaar-melding als eerste regel" 'head -1 <<<"$out" | grep -q "\"ready\":true"'
ok "precies 5 stukjes" '[ "$(grep -c "\"t\"" <<<"$out")" -le 5 ] && grep -q "\"n\":5" <<<"$out"'
ok "kapotte opdracht: nette fout" 'grep -q "Ongeldige opdracht" <<<"$out"'
big=$(python3 -c "print('woord '*3000)")
out=$(printf '{"op":"chat","messages":[{"role":"system","content":"S"},{"role":"user","content":"%s"},{"role":"assistant","content":"ok"},{"role":"user","content":"kort"}],"max":10}\n' "$big" | "$L" -m "$T/chat.gguf" -c 512 -t 2 2>/dev/null)
ok "te lang gesprek: oudste berichten weggelaten" 'grep -q "\"cut\":1" <<<"$out" && grep -q "\"done\":true" <<<"$out"'
out=$( (printf '%s\n' '{"op":"chat","messages":[{"role":"user","content":"Hallo"}],"max":400}'; sleep 0.05; echo '{"op":"cancel"}'; sleep 1) | "$L" -m "$T/chat.gguf" -c 1024 -t 1 2>/dev/null)
ok "stoppen halverwege" 'grep -q "\"cancelled\":true" <<<"$out"'
out=$(printf '%s\n' '{"op":"chat","messages":[{"role":"user","content":"A"}],"max":3}' '{"op":"chat","messages":[{"role":"user","content":"A"},{"role":"assistant","content":"B"},{"role":"user","content":"C"}],"max":3}' | "$L" -m "$T/chat.gguf" -c 512 -t 2 2>/dev/null)
ok "vervolgvraag in hetzelfde gesprek" '[ "$(grep -c "\"done\":true" <<<"$out")" = 2 ]'
out=$(printf '%s\n' '{"op":"embed","texts":["Hallo wereld","Iets anders",""],"dims":8}' | "$L" -m "$T/embed.gguf" --embed -t 2 2>/dev/null)
ok "zoekgetallen: 3 reeksen van 8" 'python3 -c "import json,sys; e=json.loads(sys.argv[1].splitlines()[1])[\"e\"]; assert len(e)==3 and all(len(x)==8 for x in e); assert abs(sum(v*v for v in e[0])-1)<1e-3" "$out"'
out=$(printf '%s\n' '{"op":"embed","id":42,"texts":["a"],"dims":4}' | "$L" -m "$T/embed.gguf" --embed -t 2 2>/dev/null)
ok "antwoord draagt het id van de vraag" 'grep -q "\"id\":42" <<<"$out"'
out=$(printf '{"op":"chat","id":7,"messages":[{"role":"user","content":"VRAAG %s"}],"max":10}\n' "$big" | "$L" -m "$T/chat.gguf" -c 512 -t 2 2>/dev/null)
ok "te lang bericht: ingekort (begin blijft), met id" 'grep -q "\"trim\":[1-9]" <<<"$out" && grep -q "\"done\":true.*\"id\":7\|\"id\":7.*\"done\":true" <<<"$out"'
out=$(printf '%s\n' '{"op":"embed","texts":["x"]}' | "$L" -m "$T/chat.gguf" -t 2 2>/dev/null)
ok "zoekopdracht aan het gespreksmodel: fout" 'grep -q "Onbekende opdracht" <<<"$out"'
out=$("$L" -m /bestaat/niet.gguf 2>/dev/null || true)
ok "geen model: fout" 'grep -q "niet geladen" <<<"$out"'
exit $fail
