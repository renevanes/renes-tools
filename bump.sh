#!/usr/bin/env bash
# Verhoogt het versienummer. Gebruik: ./bump.sh "Wijziging 1" "Wijziging 2" ...
#   NATIVE=1 ./bump.sh ...   -> ook NATIVE_LEVEL omhoog (Java/manifest gewijzigd: nieuwe APK nodig)
#   MAJOR=1 ./bump.sh ...    -> 1.9 wordt 2.0
set -euo pipefail
cd "$(dirname "$0")"
source VERSION
[ $# -gt 0 ] || { echo "Geef minstens één regel voor het wijzigingslog"; exit 1; }
MAJ=${VERSION_NAME%%.*}; MIN=${VERSION_NAME#*.}
if [ "${MAJOR:-0}" = 1 ]; then MAJ=$((MAJ+1)); MIN=0; else MIN=$((MIN+1)); fi
NEWNAME="$MAJ.$MIN"; NEWCODE=$((VERSION_CODE+1)); NEWNATIVE=$NATIVE_LEVEL
[ "${NATIVE:-0}" = 1 ] && NEWNATIVE=$((NATIVE_LEVEL+1))
sed -i "s/^VERSION_NAME=.*/VERSION_NAME=$NEWNAME/; s/^VERSION_CODE=.*/VERSION_CODE=$NEWCODE/; s/^NATIVE_LEVEL=.*/NATIVE_LEVEL=$NEWNATIVE/" VERSION
python3 - "$NEWNAME" "$@" <<'PY'
import sys, json, datetime
name, changes = sys.argv[1], sys.argv[2:]
log = json.load(open('changelog.json'))
log.insert(0, {"version": name, "date": datetime.date.today().isoformat(), "changes": changes})
json.dump(log, open('changelog.json', 'w'), ensure_ascii=False, indent=2)
PY
echo "Nieuwe versie: $NEWNAME (code $NEWCODE, native $NEWNATIVE)"
