#!/usr/bin/env python3
"""Vangnet: vaste adressen in de code van de app gaan via https.

Zoekt in de broncode van de app (app/ en web/, zonder web/vendor/ en het gegenereerde web/index.html) naar elk vast
adres met een onversleuteld schema (http://, ws://, ftp://) en een vaste naam erachter. Zo'n adres mag alleen als het
met een reden in tests/adressen/lijst.json staat:
- "namen": een naam die overal mag (XML-namespaces en parserinstellingen die nooit opgehaald worden, het toestel zelf);
- "plekken": één naam in één bestand (bijv. een voorbeeld in codecommentaar).

Niet gecontroleerd (bewust): een los schema zonder naam ("http://" om een adres te herkennen of aan te vullen) en
adressen die Rene zelf kiest (radiozenders, podcasts, apparaten in het thuisnetwerk); daarvoor staat
usesCleartextTraffic aan (AGENTS.md, Beveiliging, gebied 6).

Een uitzondering die niet meer nodig is, laat de controle ook falen (zo blijft de lijst kort en klopt hij).

Aanroepen: python3 tests/adressen/controle.py  (ook vanuit tests/run-ui.sh). --zelftest controleert dat de controle
zelf een nieuw adres opmerkt.
"""
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LIJST = Path(__file__).resolve().parent / 'lijst.json'
MAPPEN = ('app', 'web')
SOORTEN = {'.java', '.kt', '.js', '.mjs', '.html', '.htm', '.css', '.json', '.xml', '.svg'}
OVERSLAAN = ('web/vendor/', 'web/index.html')
# Schema zonder versleuteling, gevolgd door een naam (letters, cijfers, punt, streepje) of een IPv6-adres
ADRES = re.compile(r'(?i)\b(http|ws|ftp)://(\[[0-9a-f:.]+\]|[a-z0-9][a-z0-9.-]*)')


def bestanden(root):
    for map_ in MAPPEN:
        d = root / map_
        if not d.is_dir():
            continue
        for p in sorted(d.rglob('*')):
            if not p.is_file() or p.suffix.lower() not in SOORTEN:
                continue
            rel = p.relative_to(root).as_posix()
            if any(rel == o or rel.startswith(o) for o in OVERSLAAN):
                continue
            yield rel, p


def vondsten(root):
    """[(bestand, regelnummer, schema, naam)] voor elk vast onversleuteld adres."""
    uit = []
    for rel, p in bestanden(root):
        try:
            tekst = p.read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        for nr, regel in enumerate(tekst.splitlines(), 1):
            for m in ADRES.finditer(regel):
                naam = m.group(2).lower().rstrip('.-')
                if naam:
                    uit.append((rel, nr, m.group(1).lower(), naam))
    return uit


def _reden(item):
    r = item.get('reden') if isinstance(item, dict) else None
    return isinstance(r, str) and len(r.strip()) >= 10


def controleer(root, lijst):
    fouten = []
    namen = lijst.get('namen', {})
    plekken = lijst.get('plekken', [])
    for naam, item in namen.items():
        if not _reden(item):
            fouten.append('namen.' + naam + ': geen (duidelijke) reden in tests/adressen/lijst.json')
    for i, item in enumerate(plekken):
        if not _reden(item) or not item.get('bestand') or not item.get('naam'):
            fouten.append('plekken[' + str(i) + ']: bestand, naam en een duidelijke reden zijn nodig')
    gebruikt_naam, gebruikt_plek = set(), set()
    for rel, nr, schema, naam in vondsten(root):
        if naam in namen:
            gebruikt_naam.add(naam)
            continue
        plek = next((i for i, x in enumerate(plekken) if x.get('bestand') == rel and str(x.get('naam', '')).lower() == naam), None)
        if plek is not None:
            gebruikt_plek.add(plek)
            continue
        fouten.append(rel + ':' + str(nr) + ': vast adres zonder versleuteling (' + schema + '://' + naam
                      + '). Gebruik https, of zet het met een reden in tests/adressen/lijst.json')
    for naam in namen:
        if naam not in gebruikt_naam:
            fouten.append('namen.' + naam + ': staat op de lijst maar komt niet meer voor; haal hem weg')
    for i, x in enumerate(plekken):
        if i not in gebruikt_plek:
            fouten.append('plekken[' + str(i) + '] (' + str(x.get('bestand')) + ', ' + str(x.get('naam'))
                          + '): staat op de lijst maar komt niet meer voor; haal hem weg')
    return fouten


def zelftest():
    """De controle moet een nieuw adres, een ongebruikte uitzondering en een ontbrekende reden opmerken."""
    with tempfile.TemporaryDirectory() as t:
        r = Path(t)
        (r / 'app/src').mkdir(parents=True)
        (r / 'web/vendor').mkdir(parents=True)
        bron = r / 'app/src/A.java'
        lijst = {'namen': {'www.w3.org': {'reden': 'XML-namespace, wordt nooit opgehaald'}},
                 'plekken': [{'bestand': 'app/src/A.java', 'naam': '192.168.1.20', 'reden': 'Voorbeeld in commentaar'}]}
        goed = ('String ns = "http://www.w3.org/2000/svg"; // http://192.168.1.20/x\n'
                'String a = "https://example.org/", b = "http://" + host; if (u.startsWith("http://")) {}\n')
        bron.write_text(goed, encoding='utf-8')
        (r / 'web/vendor/x.js').write_text('var u = "http://vendor.example/";', encoding='utf-8')
        (r / 'web/index.html').write_text('<a href="http://gegenereerd.example/">', encoding='utf-8')
        assert controleer(r, lijst) == [], controleer(r, lijst)
        for slecht in ('URL u = new URL("http://api.example.org/v1");', "fetch('HTTP://Example.org')",
                       'new WebSocket("ws://chat.example.org")', 'x = "ftp://files.example.org/a"',
                       'y = "http://[::1]:8080/"'):
            bron.write_text(goed + slecht + '\n', encoding='utf-8')
            assert any('vast adres zonder versleuteling' in f for f in controleer(r, lijst)), slecht
        (r / 'web/b.json').write_text('{"url": "http://feed.example.org/rss"}', encoding='utf-8')
        assert any('web/b.json' in f for f in controleer(r, lijst))
        (r / 'web/b.json').unlink()
        bron.write_text('String ns = "http://www.w3.org/2000/svg";\n', encoding='utf-8')
        assert any('komt niet meer voor' in f for f in controleer(r, lijst))
        bron.write_text(goed, encoding='utf-8')
        lijst['namen']['www.w3.org']['reden'] = ''
        assert any('geen (duidelijke) reden' in f for f in controleer(r, lijst))
    print('adressen: zelftest in orde')


def main():
    if '--zelftest' in sys.argv:
        zelftest()
        return 0
    lijst = json.loads(LIJST.read_text(encoding='utf-8'))
    fouten = controleer(ROOT, lijst)
    if fouten:
        print('Vaste adressen: ' + str(len(fouten)) + ' probleem/problemen')
        for f in fouten:
            print('  ' + f)
        return 1
    print('Vaste adressen: alles via https, of met een reden op de lijst (' + str(len(vondsten(ROOT))) + ' plekken zonder https)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
