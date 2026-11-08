#!/usr/bin/env bash
# Echte concurrerende Git-transacties in wegwerp-repositories; geen sleutel of GitHub-toegang nodig.
set -euo pipefail
publicatie_root=$(cd "$(dirname "$0")/.." && pwd)
publicatie_tmp=$(mktemp -d)
trap 'rm -rf "$publicatie_tmp"' EXIT
fout(){ echo "FOUT: $*" >&2; exit 1; }
export GIT_AUTHOR_NAME=Test GIT_AUTHOR_EMAIL=test@example.invalid GIT_COMMITTER_NAME=Test GIT_COMMITTER_EMAIL=test@example.invalid
export GIT_CONFIG_GLOBAL=/dev/null PUBLICATIE_GIT
PUBLICATIE_GIT=$(command -v git)
unset VOORBEREIDEN 2>/dev/null || true
git init -q --bare -b main "$publicatie_tmp/origin.git"
git clone -q "$publicatie_tmp/origin.git" "$publicatie_tmp/a" 2>/dev/null
cd "$publicatie_tmp/a"
mkdir update
printf 'VERSION_NAME=1.10\nVERSION_CODE=11\nNATIVE_LEVEL=5\n' > VERSION
echo '[{"version":"1.10"}]' > changelog.json
echo '{"versionName":"1.10","versionCode":11,"nativeLevel":5}' > update/update.json
git add . && git commit -qm 'Versie 1.10' && git push -q origin HEAD:main
basis=$(git rev-parse HEAD)
slot_tip=$(git commit-tree "$(git hash-object -t tree /dev/null)" -m "bezet $(date -u +%s) door Test [publicatie-test]")
git push -q origin "$slot_tip:refs/heads/publiceren-slot"
git clone -q "$publicatie_tmp/origin.git" "$publicatie_tmp/b"
git clone -q "$publicatie_tmp/origin.git" "$publicatie_tmp/historie"
maak(){ # Een publicatiecommit boven een vaste basis, met werkelijk verschillende inhoud per agent.
  git checkout -q --detach "$basis"
  printf 'VERSION_NAME=%s\nVERSION_CODE=%s\nNATIVE_LEVEL=5\n' "$1" "$2" > VERSION
  printf '[{"version":"%s","changes":["%s"]}]\n' "$1" "$3" > changelog.json
  printf '{"versionName":"%s","versionCode":%s,"nativeLevel":5}\n' "$1" "$2" > update/update.json
  git add . && git commit -qm "Versie $1 ($3)"
}
maak 1.11 12 agent-a; kandidaat_a=$(git rev-parse HEAD)
cd "$publicatie_tmp/b"; maak 1.11 12 agent-b; kandidaat_b=$(git rev-parse HEAD)

# Beide processen moeten hun controles afronden vóór de eerste push vertrekt; de server beslist de race.
mkdir "$publicatie_tmp/bin" "$publicatie_tmp/barriere"
cat > "$publicatie_tmp/bin/git" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [ "$1" = push ] && [ -n "${PUBLICATIE_BARRIERE:-}" ]; then
  touch "$PUBLICATIE_BARRIERE/$$"
  for ((i=0;i<200;i++)); do
    [ "$(find "$PUBLICATIE_BARRIERE" -type f | wc -l)" -ge 2 ] && break
    sleep .025
  done
  [ "$(find "$PUBLICATIE_BARRIERE" -type f | wc -l)" -ge 2 ] || exit 1
fi
exec "$PUBLICATIE_GIT" "$@"
SH
chmod +x "$publicatie_tmp/bin/git"
duw(){ (cd "$publicatie_tmp/$1" && PUBLICATIE_BARRIERE="$publicatie_tmp/barriere" PATH="$publicatie_tmp/bin:$PATH" \
  "$publicatie_root/tools/publicatie-push.sh" "$basis" "$2" 1.11 12 "$slot_tip" publicatie-test > "$publicatie_tmp/$1.log" 2>&1); }
duw a "$kandidaat_a" & proces_a=$!
duw b "$kandidaat_b" & proces_b=$!
uitslag_a=0; wait "$proces_a" || uitslag_a=$?
uitslag_b=0; wait "$proces_b" || uitslag_b=$?
if ! { [ "$uitslag_a" -eq 0 ] && [ "$uitslag_b" -ne 0 ]; } && ! { [ "$uitslag_b" -eq 0 ] && [ "$uitslag_a" -ne 0 ]; }; then
  cat "$publicatie_tmp/a.log" "$publicatie_tmp/b.log"; fout 'er moet precies één winnaar zijn'
fi
remote(){ git -C "$publicatie_tmp/origin.git" rev-parse "$1"; }
winnaar=$(remote main)
[ "$winnaar" = "$kandidaat_a" ] || [ "$winnaar" = "$kandidaat_b" ] || fout 'onbekende winnaar'
[ "$(remote refs/tags/versie-1.11)" = "$winnaar" ] && [ "$(remote refs/tags/versiecode-12)" = "$winnaar" ] || fout 'registraties horen niet bij dezelfde publicatie'
echo '✓ Twee agents met dezelfde versie: precies één publicatie en twee bijbehorende registraties'

git clone -q "$publicatie_tmp/origin.git" "$publicatie_tmp/c"
cd "$publicatie_tmp/c"; basis=$winnaar; slot_tip=$(remote refs/heads/publiceren-slot)
maak 1.11 13 hergebruik
if "$publicatie_root/tools/publicatie-push.sh" "$basis" "$(git rev-parse HEAD)" 1.11 13 "$slot_tip" publicatie-test > "$publicatie_tmp/hergebruik.log" 2>&1; then fout 'versienaam opnieuw gebruikt'; fi

# De kandidaat kent deze concurrerende registratie nog niet. Alleen de atomaire servercontrole voorkomt een deelpublicatie.
maak 1.12 13 naam-botsing
git -C "$publicatie_tmp/origin.git" update-ref refs/tags/versie-1.12 "$winnaar"
if "$publicatie_root/tools/publicatie-push.sh" "$basis" "$(git rev-parse HEAD)" 1.12 13 "$slot_tip" publicatie-test > "$publicatie_tmp/naam.log" 2>&1; then fout 'bezette versienaam overschreven'; fi
[ "$(remote main)" = "$winnaar" ] && ! remote refs/tags/versiecode-13 >/dev/null 2>&1 || fout 'gedeeltelijke publicatie na naambotsing'
git fetch -q --no-tags origin 'refs/tags/versie-*:refs/tags/versie-*'
maak 1.13 13 code-botsing
git -C "$publicatie_tmp/origin.git" update-ref refs/tags/versiecode-13 "$winnaar"
if "$publicatie_root/tools/publicatie-push.sh" "$basis" "$(git rev-parse HEAD)" 1.13 13 "$slot_tip" publicatie-test > "$publicatie_tmp/code.log" 2>&1; then fout 'bezette versiecode overschreven'; fi
[ "$(remote main)" = "$winnaar" ] && ! remote refs/tags/versie-1.13 >/dev/null 2>&1 || fout 'gedeeltelijke publicatie na codebotsing'
git fetch -q --no-tags origin 'refs/tags/versiecode-*:refs/tags/versiecode-*'
[ "$(python3 "$publicatie_root/tools/publicatie-versie.py" volgende "$basis" 0)" = '1.13 14 5' ] || fout 'bezette nummers niet overgeslagen'
[ "$(python3 "$publicatie_root/tools/publicatie-versie.py" volgende "$basis" 1)" = '2.0 14 5' ] || fout 'hoofdversie niet correct'
maak 1.13 14 opnieuw
"$publicatie_root/tools/publicatie-push.sh" "$basis" "$(git rev-parse HEAD)" 1.13 14 "$slot_tip" publicatie-test > "$publicatie_tmp/opnieuw.log" 2>&1
[ "$(remote main)" = "$(remote refs/tags/versie-1.13)" ] && [ "$(remote main)" = "$(remote refs/tags/versiecode-14)" ] || fout 'volgende vrije versie niet gepubliceerd'
echo '✓ Botsingen wijzigen niets; naam en versiecode blijven vast en de volgende vrije versie werkt'

basis=$(git rev-parse HEAD); slot_tip=$(remote refs/heads/publiceren-slot)
maak 1.14 15 gepauzeerde-agent
nieuwe_houder=$(git commit-tree "$(git hash-object -t tree /dev/null)" -p "$slot_tip" -m "bezet $(date -u +%s) door Test [agent-b]")
git push -q origin "$nieuwe_houder:refs/heads/publiceren-slot"
if "$publicatie_root/tools/publicatie-push.sh" "$basis" "$(git rev-parse HEAD)" 1.14 15 "$slot_tip" publicatie-test > "$publicatie_tmp/oud-slot.log" 2>&1; then fout 'oude slothouder kon alsnog publiceren'; fi
[ "$(remote main)" = "$basis" ] && [ "$(remote refs/heads/publiceren-slot)" = "$nieuwe_houder" ] && ! remote refs/tags/versie-1.14 >/dev/null 2>&1 && ! remote refs/tags/versiecode-15 >/dev/null 2>&1 || fout 'slotverlies gaf een gedeeltelijke publicatie'
echo '✓ Overgenomen slot na de laatste controle weigert de volledige publicatietransactie'
slot_tip=$nieuwe_houder

# De lease op het slot mag nooit een force-push van main toestaan.
(cd "$publicatie_tmp/b" && git fetch -q origin main && git checkout -q --detach FETCH_HEAD && \
  echo nieuwe-bron > bron.txt && git add . && git commit -qm 'Andere bron tijdens de bouw' && git push -q origin HEAD:main)
andere_bron=$(remote main)
if "$publicatie_root/tools/publicatie-push.sh" "$basis" "$(git rev-parse HEAD)" 1.14 15 "$slot_tip" agent-b > "$publicatie_tmp/main.log" 2>&1; then fout 'main werd teruggeschreven'; fi
[ "$(remote main)" = "$andere_bron" ] && [ "$(remote refs/heads/publiceren-slot)" = "$slot_tip" ] && ! remote refs/tags/versie-1.14 >/dev/null 2>&1 && ! remote refs/tags/versiecode-15 >/dev/null 2>&1 || fout 'gewijzigde main gaf een gedeeltelijke publicatie'
echo '✓ Een gewijzigde main wordt nooit overschreven, ook niet met leasecontrole op het slot'
maak 1.14 15 verkeerde-metadata
sed -i 's/"versionCode":15/"versionCode":14/' update/update.json
git commit -qam 'Andere metadata'
if "$publicatie_root/tools/publicatie-push.sh" "$basis" "$(git rev-parse HEAD)" 1.14 15 "$slot_tip" agent-b > "$publicatie_tmp/metadata.log" 2>&1; then fout 'verschillende versies in metadata toegelaten'; fi
grep -q 'verschillende versies' "$publicatie_tmp/metadata.log" || fout 'metadata niet afzonderlijk gecontroleerd'
cd "$publicatie_tmp/historie"
printf 'VERSION_NAME=1.0\nVERSION_CODE=1\nNATIVE_LEVEL=1\n' > VERSION
echo '[]' > changelog.json
git commit -qam 'VERSION per ongeluk teruggezet'
[ "$(python3 "$publicatie_root/tools/publicatie-versie.py" volgende "$(git rev-parse HEAD)" 0)" = '1.11 12 5' ] || fout 'historische versies opnieuw uitgegeven'
echo '✓ Teruggezette versiebestanden en afwijkende metadata kunnen geen nummer hergebruiken'

slot_omgeving(){
  LOCK=refs/heads/publiceren-slot; WHO=Test; TOKEN=$1
  EMPTY_TREE=$(git hash-object -t tree /dev/null); GIT_ID=(-c user.name=Test -c user.email=test@example.invalid)
  # shellcheck source=tools/publicatie-slot.sh
  source "$publicatie_root/tools/publicatie-slot.sh"
}
slot_werk(){ (
  cd "$publicatie_tmp/$1"; slot_omgeving "$1"
  # Alleen wachttijden verkorten, niet de Git-transacties of de slotcontrole.
  sleep(){ command sleep .025; }
  slot_take
  mkdir "$publicatie_tmp/kritiek" || { touch "$publicatie_tmp/dubbel-slot"; exit 1; }
  command sleep .2
  slot_refresh
  rmdir "$publicatie_tmp/kritiek"
  slot_free
); }
cd "$publicatie_tmp/a"; slot_omgeving agent-b; SLOT_TIP=$nieuwe_houder; slot_free > /dev/null
slot_werk a > "$publicatie_tmp/slot-a.log" 2>&1 & proces_a=$!
slot_werk b > "$publicatie_tmp/slot-b.log" 2>&1 & proces_b=$!
wait "$proces_a" || { cat "$publicatie_tmp/slot-a.log"; fout 'eerste slothouder mislukt'; }
wait "$proces_b" || { cat "$publicatie_tmp/slot-b.log"; fout 'tweede slothouder mislukt'; }
[ ! -e "$publicatie_tmp/dubbel-slot" ] || fout 'twee slothouders tegelijk'

cd "$publicatie_tmp/a"; slot_omgeving agent-a
slot_take > /dev/null
oude_tip=$SLOT_TIP
TOKEN=agent-b; slot_push "$oude_tip" "bezet $(date -u +%s) door Test [$TOKEN]"; andere_tip=$SLOT_TIP
TOKEN=agent-a; SLOT_TIP=$oude_tip
if slot_refresh > "$publicatie_tmp/verloren.log" 2>&1; then fout 'verloren slot toegelaten'; fi
slot_free > /dev/null
[ "$(remote "$LOCK")" = "$andere_tip" ] || fout 'slot van andere agent vrijgegeven'
TOKEN=agent-b; SLOT_TIP=$andere_tip
# Verloren antwoord op een eigen push: de nieuwe slotcommit blijft aantoonbaar van dezelfde houder.
slot_push "$andere_tip" "bezet $(date -u +%s) door Test [$TOKEN]"
SLOT_TIP=$andere_tip
slot_refresh > /dev/null
andere_tip=$SLOT_TIP
git(){ for arg in "$@"; do [ "$arg" = commit-tree ] && return 1; done; command git "$@"; }
if slot_refresh > "$publicatie_tmp/slotcommit.log" 2>&1; then fout 'slotcommit-fout toegelaten'; fi
unset -f git
[ "$(remote "$LOCK")" = "$andere_tip" ] || fout 'slotcommit-fout wijzigde of verwijderde de slotref'
git(){ if [ "$1" = push ]; then return 1; else command git "$@"; fi; }
if slot_refresh > "$publicatie_tmp/vernieuwen.log" 2>&1; then fout 'mislukte vernieuwing toegelaten'; fi
unset -f git
slot_free > /dev/null
git remote set-url origin "$publicatie_tmp/ontbreekt.git"
if slot_read > "$publicatie_tmp/onbereikbaar.log" 2>&1; then fout 'onbereikbare server als vrij slot gezien'; fi
echo '✓ Het slot laat één agent tegelijk toe; verlies, onbereikbaarheid en mislukte vernieuwing stoppen publicatie'
