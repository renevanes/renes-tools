#!/usr/bin/env bash
# Bewijst dat versiecommits wel en ongeteste bronwijzigingen nooit langs de publicatiecontrole komen.
set -euo pipefail
scan_root=$(cd "$(dirname "$0")/.." && pwd)
scan_repo=$(mktemp -d)
trap 'rm -rf "$scan_repo"' EXIT
cd "$scan_repo"
git init -q
git config user.name Test
git config user.email test@example.invalid
mkdir -p app update wijzigingen tests
echo bron > app/bron.txt
git add . && git commit -qm bron
TESTED_SOURCE_SHA=$(git rev-parse HEAD)
export TESTED_SOURCE_SHA
"$scan_root/tools/check-publish-source.sh" >/dev/null
echo versie > VERSION
echo uitvoer > update/index.html
echo wijziging > wijzigingen/test.txt
git add . && git commit -qm versie
"$scan_root/tools/check-publish-source.sh" >/dev/null
echo nieuw >> app/bron.txt
git add . && git commit -qm ongeteste-bron
if "$scan_root/tools/check-publish-source.sh" >/dev/null 2>&1; then echo 'Ongeteste bron ten onrechte toegelaten'; exit 1; fi
TESTED_SOURCE_SHA=$(git rev-parse HEAD)
"$scan_root/tools/check-publish-source.sh" >/dev/null
echo controle > tests/controle.txt
git add . && git commit -qm gewijzigde-controle
if "$scan_root/tools/check-publish-source.sh" >/dev/null 2>&1; then echo 'Gewijzigde controles ten onrechte overgeslagen'; exit 1; fi
TESTED_SOURCE_SHA=ongeldig
if "$scan_root/tools/check-publish-source.sh" >/dev/null 2>&1; then echo 'Ongeldige broncommit toegelaten'; exit 1; fi
echo 'Publicatiecontrole geslaagd: versiecommits toegestaan, bron- en testwijzigingen geblokkeerd'
