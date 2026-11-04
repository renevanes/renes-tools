#!/usr/bin/env python3
"""Overzicht: welke toestemmingen uit app/AndroidManifest.xml mogen in een Google Play-versie?

Alleen ter voorbereiding (zie docs/play-store.md). Verandert niets en draait niet in de tests.
De beleidsindeling staat in play/indeling.json. De Play-bouw gebruikt de beperktere allowlist
in play/basis.json; play/check.py controleert die tegen de werkelijk gebouwde bundel.
Gebruik: python3 .github/scripts/play-toestemmingen.py
Afsluitcode 1 als er toestemmingen in het manifest staan die nog niet zijn ingedeeld.
"""
import json
import re
import sys
from pathlib import Path

VOLGORDE = ['niet', 'streng', 'verklaring', 'ok']


def main():
    root = Path(__file__).resolve().parents[2]
    indeling = json.loads((root / 'play' / 'indeling.json').read_text(encoding='utf-8'))['toestemmingen']
    manifest = (root / 'app' / 'AndroidManifest.xml').read_text(encoding='utf-8')
    gevonden = sorted(set(re.findall(r'android\.permission\.([A-Z_]+)', manifest)))
    groepen = {k: [] for k in VOLGORDE}
    onbekend = []
    for p in gevonden:
        (groepen[indeling[p]] if p in indeling else onbekend).append(p)
    for k in VOLGORDE:
        print(f'{k} ({len(groepen[k])}): ' + (', '.join(groepen[k]) or '-'))
    if onbekend:
        print(f'\nNog niet ingedeeld ({len(onbekend)}): ' + ', '.join(onbekend))
        print('Deel ze in, in play/indeling.json en docs/play-store.md.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
