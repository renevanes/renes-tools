#!/usr/bin/env bash
# De versie en haar twee vaste registraties worden samen vastgelegd, of geen van drieën.
set -euo pipefail
publicatie_tools=$(cd "$(dirname "$0")" && pwd)
[ "$#" -eq 4 ] || { echo 'Gebruik: publicatie-push.sh basiscommit versiecommit versienummer versiecode' >&2; exit 1; }
basis=$1; publicatie=$2; naam=$3; code=$4
[[ $basis =~ ^[0-9a-f]{40}$ && $publicatie =~ ^[0-9a-f]{40}$ ]] || { echo 'Ongeldige publicatiecommit' >&2; exit 1; }
git merge-base --is-ancestor "$basis" "$publicatie" || { echo 'Publicatie staat niet boven de opgehaalde main' >&2; exit 1; }
python3 "$publicatie_tools/publicatie-versie.py" controleer "$basis" "$publicatie" "$naam" "$code"
# Geen force en geen losse pushes: een bezette versietag of gewijzigde main weigert de hele transactie.
git push --atomic origin "$publicatie:refs/heads/main" \
  "$publicatie:refs/tags/versie-$naam" "$publicatie:refs/tags/versiecode-$code"
