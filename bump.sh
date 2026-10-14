#!/usr/bin/env bash
# Schrijft wijzigingsregels voor de volgende versie in wijzigingen/ (één bestand per keer; botst niet met
# andere agents). Het versienummer zelf bepaalt ./publish.sh bij het publiceren.
#   ./bump.sh "Wijziging 1" "Wijziging 2" ...
set -euo pipefail
cd "$(dirname "$0")"
[ $# -gt 0 ] || { echo "Geef minstens één regel voor het wijzigingslog"; exit 1; }
mkdir -p wijzigingen
f="wijzigingen/$(date +%Y%m%d-%H%M%S)-$$.txt"
printf '%s\n' "$@" > "$f"
echo "Wijzigingsregels in $f; publiceren met ./publish.sh"
