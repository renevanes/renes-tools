#!/usr/bin/env python3
"""Maakt het manifest voor de Play-proefbouw uit app/AndroidManifest.xml (dat zelf niet verandert).

- laat toestemmingen weg die in play/indeling.json op 'niet' of 'streng' staan;
- laat onderdelen weg waarvan android:permission in 'onderdelen_weg' staat;
- haalt maxSdkVersion weg bij SCHEDULE_EXACT_ALARM (de vervanger van USE_EXACT_ALARM);
- geeft de content providers een eigen naam (nl.rene.tools.X -> <pakket>.X), zodat de Play-versie naast de
  GitHub-versie op één telefoon kan staan.
Gebruik: play/manifest.py <uit-manifest> <pakketnaam>   (drukt de hernoemde authorities af, één per regel: oud nieuw)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEG_STATUS = {'niet', 'streng'}


def blokken(tekst, tag):
    """Hele <tag ...>...</tag> of <tag .../> elementen (niet genest binnen dezelfde tag)."""
    return re.finditer(r'<%s\b(?:[^>]*/>|.*?</%s>)' % (tag, tag), tekst, re.S)


def main(uit, pakket):
    ind = json.loads((ROOT / 'play' / 'indeling.json').read_text(encoding='utf-8'))
    weg_perm = {p for p, s in ind['toestemmingen'].items() if s in WEG_STATUS}
    weg_onderdeel = {k for k in ind['onderdelen_weg'] if not k.startswith('_')}
    m = (ROOT / 'app' / 'AndroidManifest.xml').read_text(encoding='utf-8')

    def perm_regel(mt):
        naam = re.search(r'android:name="android\.permission\.([A-Z_]+)"', mt.group(0))
        if naam and naam.group(1) in weg_perm:
            return ''
        if naam and naam.group(1) == 'SCHEDULE_EXACT_ALARM':
            return re.sub(r'\s+android:maxSdkVersion="\d+"', '', mt.group(0))
        return mt.group(0)
    m = re.sub(r'[ \t]*<uses-permission\b[^>]*/>[ \t]*\n?', perm_regel, m)

    for tag in ('service', 'receiver'):
        for b in list(blokken(m, tag)):
            p = re.search(r'android:permission="([^"]+)"', b.group(0))
            if p and p.group(1) in weg_onderdeel:
                m = m.replace(b.group(0), '')

    hernoemd = []
    def auth(mt):
        oud = mt.group(1)
        nieuw = pakket + oud[len('nl.rene.tools'):] if oud.startswith('nl.rene.tools.') else oud
        if nieuw != oud:
            hernoemd.append((oud, nieuw))
        return 'android:authorities="%s"' % nieuw
    m = re.sub(r'android:authorities="([^"]+)"', auth, m)

    over = sorted(weg_perm & set(re.findall(r'android\.permission\.([A-Z_]+)', m)))
    if over:
        sys.exit('Nog in het Play-manifest: ' + ', '.join(over))
    Path(uit).write_text(m, encoding='utf-8')
    for oud, nieuw in hernoemd:
        print(oud, nieuw)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
