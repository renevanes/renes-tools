#!/usr/bin/env python3
"""Controleert een volledige momentopname vóór vrijgave; gebruikt ook de echte app-grenzen."""
import argparse
import datetime
import hashlib
import json
import math
import pathlib
import re
import zlib

from ophalen import LICENTIE, LICENTIE_URL, NL, lees_regels

MAX_GZ = 30 * 1024 * 1024
MAX_JSON = 40 * 1024 * 1024
MAX_META = 100_000
MAX_PLEKKEN = 100_000


def controleer(mapnaam, minimum=1000):
    pad = pathlib.Path(mapnaam)
    bestand, metapad = pad / 'toiletten-nl.json.gz', pad / 'meta.json'
    if bestand.stat().st_size > MAX_GZ or metapad.stat().st_size > MAX_META:
        raise ValueError('bestand of meta.json te groot')
    gz = bestand.read_bytes()
    meta = json.loads(metapad.read_text(encoding='utf-8'))
    if not isinstance(meta, dict):
        raise ValueError('meta.json moet een object zijn')
    if meta.get('sha256') != hashlib.sha256(gz).hexdigest():
        raise ValueError('sha256 klopt niet')
    if type(meta.get('grootte')) is not int or meta['grootte'] != len(gz):
        raise ValueError('grootte klopt niet')
    if meta.get('bestand') != bestand.name:
        raise ValueError('onverwachte bestandsnaam')
    decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
    ruw = decoder.decompress(gz, MAX_JSON + 1)
    if len(ruw) > MAX_JSON or decoder.unconsumed_tail:
        raise ValueError('uitgepakt groter dan 40 MB')
    if not decoder.eof or decoder.unused_data:
        raise ValueError('onvolledig gzip-bestand of extra gegevens')
    data = json.loads(ruw)
    regels = lees_regels()
    if not isinstance(data, dict) or any(type(o.get('formaat')) is not int or o['formaat'] != regels['formaat'] for o in (data, meta)):
        raise ValueError('onbekend formaat')
    for sleutel, verwacht in [('bron', 'OpenStreetMap'), ('licentie', LICENTIE), ('licentieUrl', LICENTIE_URL)]:
        if data.get(sleutel) != verwacht or meta.get(sleutel) != verwacht:
            raise ValueError('bron of licentie ontbreekt of wijkt af')
    datum = data.get('datum')
    if not isinstance(datum, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', datum) or meta.get('datum') != datum:
        raise ValueError('datum ontbreekt of wijkt af')
    datetime.date.fromisoformat(datum)
    plekken = data.get('plekken')
    if not isinstance(plekken, list) or not minimum <= len(plekken) <= MAX_PLEKKEN:
        raise ValueError('onverwacht aantal plekken')
    if type(meta.get('aantal')) is not int or meta['aantal'] != len(plekken):
        raise ValueError('aantal in meta.json klopt niet')
    soorten = {s['soort'] for s in regels['soorten']} | {'overig'}
    alle_bits = sum(regels['bits'].values())
    gezien, telling = set(), {}
    for i, p in enumerate(plekken):
        if not isinstance(p, list) or len(p) != 7:
            raise ValueError('ongeldige rij op positie %d' % i)
        sleutel, lat, lon, soort, bits, naam, uren = p
        if not isinstance(sleutel, str) or not re.fullmatch(r'[nwr][1-9]\d*', sleutel) or sleutel in gezien:
            raise ValueError('ongeldig of dubbel OpenStreetMap-id op positie %d' % i)
        gezien.add(sleutel)
        if any(type(v) not in (int, float) or not math.isfinite(v) for v in (lat, lon)) or not (NL[0] <= lat <= NL[1] and NL[2] <= lon <= NL[3]):
            raise ValueError('ongeldige coördinaten op positie %d' % i)
        if not isinstance(soort, str) or soort not in soorten or type(bits) is not int or bits < 0 or bits & ~alle_bits or not bits & 3:
            raise ValueError('onbekende soort of kenmerken op positie %d' % i)
        if not isinstance(naam, str) or len(naam) > 80 or not isinstance(uren, str) or len(uren) > 120:
            raise ValueError('ongeldige naam of tijden op positie %d' % i)
        telling[soort] = telling.get(soort, 0) + 1
    if meta.get('soorten') != telling:
        raise ValueError('soortentelling in meta.json klopt niet')
    return meta


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('map')
    parser.add_argument('--min', type=int, default=1000)
    args = parser.parse_args()
    try:
        meta = controleer(args.map, args.min)
    except (OSError, ValueError, zlib.error) as fout:
        parser.exit(1, 'Niet vrijgegeven: %s\n' % fout)
    print('In orde: %d plekken, %d bytes, datum %s' % (meta['aantal'], meta['grootte'], meta['datum']))


if __name__ == '__main__':
    main()
