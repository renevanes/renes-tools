#!/usr/bin/env python3
"""Controleert sjabloonadressen en veldkoppelingen zonder locatiegegevens te versturen.

Gebruik: python3 tools/toiletten/bronnen_controleren.py [--uit rapport.json]
Een rapport is een onderhoudssignaal: bereikbaarheid bewijst geen beschikbaarheid van een toilet.
Er worden geen sjablonen of appgegevens automatisch gewijzigd.
"""
import argparse
import concurrent.futures
import datetime
import json
import math
import pathlib
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
UA = 'renes-tools-bronnencontrole/1 (+https://github.com/renevanes/renes-tools)'
MAX = 40 * 1024 * 1024


def haal(url):
    if not url.startswith('https://'):
        raise ValueError('alleen https')
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json, application/geo+json'})
    with urllib.request.urlopen(req, timeout=30) as response:
        if not response.url.startswith('https://'):
            raise ValueError('doorverwijzing zonder https')
        raw = response.read(MAX + 1)
        if len(raw) > MAX:
            raise ValueError('bestand groter dan 40 MB')
    return json.loads(raw)


def beoordeel(bron, data):
    if bron['soort'] == 'tools':
        if not isinstance(data, dict) or data.get('formaat') != 1 or not isinstance(data.get('sha256'), str) or len(data['sha256']) != 64 or type(data.get('aantal')) is not int or data['aantal'] < 1000:
            raise ValueError('meta.json heeft niet het verwachte formaat')
        datum = datetime.date.fromisoformat(data['datum'])
        leeftijd = (datetime.datetime.now(datetime.timezone.utc).date() - datum).days
        if leeftijd > 10 or leeftijd < -1:
            raise ValueError('momentopname is niet actueel (%d dagen oud)' % leeftijd)
        return {'aantal': data['aantal'], 'datum': data['datum']}
    if not isinstance(data, dict) or data.get('type') != 'FeatureCollection' or not isinstance(data.get('features'), list):
        raise ValueError('geen GeoJSON FeatureCollection')
    features = data['features']
    if not features:
        raise ValueError('geen plekken in de bron')
    props = [f.get('properties') or {} for f in features if isinstance(f, dict)]
    velden = {str(k).lower() for p in props if isinstance(p, dict) for k in p}
    if not any(geometrie(f.get('geometry')) for f in features if isinstance(f, dict)):
        raise ValueError('geen bruikbare geometrie in de bron')
    # Ook een latere rij kan een andere projectie hebben: de app stopt dan de hele scan.
    for feature in features:
        if isinstance(feature, dict):
            geometrie(feature.get('geometry'))
    ontbreekt = [doel for doel, spec in bron.get('velden', {}).items() if spec and not any(k.strip().lower() in velden for k in spec.split('|'))]
    ontbreekt += ['regel: ' + r['veld'] for r in bron.get('regels', []) if r.get('veld') and not any(k.strip().lower() in velden for k in r['veld'].split('|'))]
    if ontbreekt:
        raise ValueError('veld ontbreekt: ' + ', '.join(ontbreekt))
    return {'aantal': len(features)}


def geometrie(g):
    """De vormen die ToilettenScan.midden leest; RD-coördinaten zijn geen WGS84-graden."""
    if not isinstance(g, dict):
        return False
    c, soort = g.get('coordinates'), g.get('type')
    if not isinstance(c, list) or not c:
        return False
    if soort == 'Point':
        punten = [c]
    elif soort in ('MultiPoint', 'LineString'):
        punten = c
    elif soort == 'Polygon':
        punten = c[0]
    elif soort == 'MultiPolygon':
        punten = c[0][0] if isinstance(c[0], list) and c[0] else []
    else:
        return False
    if not isinstance(punten, list) or not punten:
        return False
    for punt in punten:
        if not isinstance(punt, list) or len(punt) < 2 or any(type(v) not in (int, float) or not math.isfinite(v) for v in punt[:2]):
            return False
        if abs(punt[0]) > 180 or abs(punt[1]) > 90:
            raise ValueError('coördinaten zijn niet in graden (WGS84 vereist)')
    return True


def controleer(bron):
    url = bron.get('metaUrl') if bron['soort'] == 'tools' else bron['url']
    row = {'sjabloon': bron['sjabloon'], 'naam': bron['naam'], 'url': url, 'licentie': bron['licentie'], 'uitgeschakeld': bron.get('aan') is False}
    try:
        row.update(beoordeel(bron, haal(url)))
        row.update(status='bereikbaar', opmerking='Formaat en veldnamen gecontroleerd; geen bevestiging van actuele toegang ter plaatse.')
    except urllib.error.HTTPError as fout:
        row.update(status='beperkt' if fout.code in (401, 403, 429) else 'controleren', opmerking='HTTP %d; geen conclusie over sluiting van locaties.' % fout.code)
    except (OSError, ValueError, KeyError, TypeError) as fout:
        row.update(status='controleren', opmerking=str(fout)[:250])
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--uit')
    args = parser.parse_args()
    sjablonen = json.loads((ROOT / 'web/toiletten-bronnen.json').read_text(encoding='utf-8'))['sjablonen']
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        rows = list(pool.map(controleer, sjablonen))
    rapport = {'gecontroleerd': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'bronnen': rows}
    if args.uit:
        pathlib.Path(args.uit).write_text(json.dumps(rapport, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('| Bron | Controle | Aantal | Opmerking |')
    print('|---|---|---:|---|')
    for r in rows:
        def cel(v):
            return str(v).replace('|', '/').replace('\n', ' ')
        print('| %s | %s%s | %s | %s |' % (cel(r['naam']), r['status'], ' (staat uit)' if r['uitgeschakeld'] else '', r.get('aantal', '—'), cel(r['opmerking'])))
    return int(any(r['status'] != 'bereikbaar' and not r['uitgeschakeld'] for r in rows))


if __name__ == '__main__':
    raise SystemExit(main())
