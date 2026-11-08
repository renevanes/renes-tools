#!/usr/bin/env bash
# Main, twee vaste versieregistraties en het slot worden samen vastgelegd, of geen van vieren.
set -euo pipefail
publicatie_tools=$(cd "$(dirname "$0")" && pwd)
[ "$#" -eq 6 ] || { echo 'Gebruik: publicatie-push.sh basiscommit versiecommit versienummer versiecode slotcommit slothouder' >&2; exit 1; }
basis=$1; publicatie=$2; naam=$3; code=$4; slot_tip=$5; slothouder=$6
[[ $basis =~ ^[0-9a-f]{40}$ && $publicatie =~ ^[0-9a-f]{40}$ && $slot_tip =~ ^[0-9a-f]{40}$ ]] || { echo 'Ongeldige publicatiecommit' >&2; exit 1; }
git merge-base --is-ancestor "$basis" "$publicatie" || { echo 'Publicatie staat niet boven de opgehaalde main' >&2; exit 1; }
python3 "$publicatie_tools/publicatie-versie.py" controleer "$basis" "$publicatie" "$naam" "$code"
slot_bericht=$(git log -1 --format=%s "$slot_tip")
[[ -n $slothouder && $slot_bericht == bezet\ * && $slot_bericht == *"[$slothouder]"* ]] || { echo 'Publicatie zonder eigen slot geweigerd' >&2; exit 1; }
slot_naam=$(git log -1 --format=%cn "$publicatie")
slot_email=$(git log -1 --format=%ce "$publicatie")
if ! slot_nieuw=$(git -c "user.name=$slot_naam" -c "user.email=$slot_email" commit-tree \
  "$(git hash-object -t tree /dev/null)" -p "$slot_tip" -m "$slot_bericht"); then
  echo 'Slotcommit maken mislukt; publicatie gestopt' >&2; exit 1
fi
[[ $slot_nieuw =~ ^[0-9a-f]{40}$ ]] || { echo 'Ongeldige slotcommit; publicatie gestopt' >&2; exit 1; }
# Main gaat alleen vooruit. De lease geldt uitsluitend voor het slot, ook als het ná de laatste controle is overgenomen.
git push --atomic --force-with-lease="refs/heads/publiceren-slot:$slot_tip" origin \
  "$publicatie:refs/heads/main" "$publicatie:refs/tags/versie-$naam" \
  "$publicatie:refs/tags/versiecode-$code" "$slot_nieuw:refs/heads/publiceren-slot"
