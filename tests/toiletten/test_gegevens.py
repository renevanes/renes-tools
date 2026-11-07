"""Publicatiegrenzen en broncontrole, zonder downloads of Android."""
import copy
import datetime
import hashlib
import json
import pathlib
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/toiletten'))
import ophalen
import controleren
import bronnen_controleren


class GegevensTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.pad = pathlib.Path(self.tmp.name)
        regels = ophalen.lees_regels()
        elements = json.loads((ROOT / 'tools/toiletten/voorbeeld.json').read_text())['elements']
        self.data = ophalen.maak(elements, regels, '2026-11-07')
        ophalen.schrijf(self.data, self.pad)

    def meta(self, **wijzig):
        p = self.pad / 'meta.json'
        d = json.loads(p.read_text())
        d.update(wijzig)
        p.write_text(json.dumps(d))

    def wijzig(self, actie):
        data = copy.deepcopy(self.data)
        actie(data)
        ophalen.schrijf(data, self.pad)

    def test_voorbeeld_met_echte_app_grenzen(self):
        meta = controleren.controleer(self.pad, 1)
        self.assertEqual(meta['aantal'], len(self.data['plekken']))
        with self.assertRaisesRegex(ValueError, 'aantal'):
            controleren.controleer(self.pad)

    def test_metadata_en_controlegetal(self):
        for wijzig, fout in [({'sha256': '0' * 64}, 'sha256'), ({'grootte': 1}, 'grootte'), ({'aantal': 1}, 'aantal'), ({'formaat': True}, 'formaat'), ({'datum': '2026-11-06'}, 'datum'), ({'soorten': {}}, 'soortentelling')]:
            with self.subTest(wijzig=wijzig):
                ophalen.schrijf(self.data, self.pad)
                self.meta(**wijzig)
                with self.assertRaisesRegex(ValueError, fout):
                    controleren.controleer(self.pad, 1)

    def test_onvolledig_gzip_wordt_ook_met_passend_controlegetal_geweigerd(self):
        gz = (self.pad / 'toiletten-nl.json.gz').read_bytes()[:-4]
        (self.pad / 'toiletten-nl.json.gz').write_bytes(gz)
        self.meta(sha256=hashlib.sha256(gz).hexdigest(), grootte=len(gz))
        with self.assertRaisesRegex(ValueError, 'onvolledig'):
            controleren.controleer(self.pad, 1)

    def test_rijen_en_bronvermelding(self):
        cases = [
            (lambda d: d['plekken'].append(d['plekken'][0]), 'dubbel'),
            (lambda d: d['plekken'][0].__setitem__(1, 100), 'coördinaten'),
            (lambda d: d['plekken'][0].__setitem__(2, float('nan')), 'coördinaten'),
            (lambda d: d['plekken'][0].__setitem__(4, 4096), 'kenmerken'),
            (lambda d: d['plekken'][0].__setitem__(5, 'a' * 81), 'naam'),
            (lambda d: d.__setitem__('licentie', ''), 'licentie'),
        ]
        for actie, fout in cases:
            with self.subTest(fout=fout):
                self.wijzig(actie)
                with self.assertRaisesRegex(ValueError, fout):
                    controleren.controleer(self.pad, 1)


class BronnenTest(unittest.TestCase):
    def setUp(self):
        self.bron = {'sjabloon': 'test', 'naam': 'Gemeentelijke bron', 'soort': 'geojson', 'url': 'https://example.org/toiletten', 'licentie': 'CC0', 'velden': {'naam': 'omschrijving|adres'}, 'regels': [{'veld': 'alleen_mannen', 'patroon': '^ja$', 'zet': 'urinoir'}]}
        self.geo = {'type': 'FeatureCollection', 'features': [{'properties': {'adres': 'Plein', 'alleen_mannen': 'nee'}, 'geometry': {'type': 'Point', 'coordinates': [4.3, 52.1]}}]}

    def test_veldkoppeling_en_regels(self):
        self.assertEqual(bronnen_controleren.beoordeel(self.bron, self.geo)['aantal'], 1)
        del self.geo['features'][0]['properties']['alleen_mannen']
        with self.assertRaisesRegex(ValueError, 'regel: alleen_mannen'):
            bronnen_controleren.beoordeel(self.bron, self.geo)

    def test_geen_lege_of_andere_respons_als_geslaagd(self):
        for data in [{'type': 'FeatureCollection', 'features': []}, {'error': 'verdwenen'}, '<html>']:
            with self.subTest(data=data), self.assertRaises(ValueError):
                bronnen_controleren.beoordeel(self.bron, data)

    def test_geometrie_moet_door_de_app_gelezen_kunnen_worden(self):
        for geometry in [{'type': 'Point', 'coordinates': [92300, 437000]}, {'type': 'GeometryCollection', 'geometries': []}, {'type': 'Point', 'coordinates': [float('nan'), 52.1]}, {'type': 'Polygon', 'coordinates': [[]]}]:
            with self.subTest(geometry=geometry):
                data = copy.deepcopy(self.geo)
                data['features'][0]['geometry'] = geometry
                with self.assertRaises(ValueError):
                    bronnen_controleren.beoordeel(self.bron, data)
        data = copy.deepcopy(self.geo)
        data['features'].append({'geometry': {'type': 'Point', 'coordinates': [92300, 437000]}, 'properties': {}})
        with self.assertRaisesRegex(ValueError, 'graden'):
            bronnen_controleren.beoordeel(self.bron, data)
        self.assertTrue(bronnen_controleren.geometrie({'type': 'Polygon', 'coordinates': [[[4.3, 52.1], [4.4, 52.1], [4.3, 52.1]]]}))

    def test_beperkte_toegang_is_geen_sluiting(self):
        for status in [403, 429]:
            with patch.object(bronnen_controleren, 'haal', side_effect=urllib.error.HTTPError(self.bron['url'], status, 'beperkt', {}, None)):
                result = bronnen_controleren.controleer(self.bron)
                self.assertEqual(result['status'], 'beperkt')
                self.assertIn('geen conclusie', result['opmerking'])

    def test_oude_momentopname_wordt_gemeld(self):
        bron = dict(self.bron, soort='tools')
        data = {'formaat': 1, 'sha256': 'a' * 64, 'aantal': 5000, 'datum': (datetime.datetime.now(datetime.timezone.utc).date() - datetime.timedelta(days=11)).isoformat()}
        with self.assertRaisesRegex(ValueError, 'niet actueel'):
            bronnen_controleren.beoordeel(bron, data)


if __name__ == '__main__':
    unittest.main()
