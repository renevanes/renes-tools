#!/usr/bin/env python3
"""Vangnet voor app/AndroidManifest.xml: alles wat de app van Android vraagt of voor andere apps openzet, staat met een
reden in tests/manifest/lijst.json.

Gecontroleerd (zelfde aanpak als play/check.py met play/basis.json, maar voor de volledige app):
- elke toestemming (uses-permission, ook maxSdkVersion);
- elk onderdeel dat andere apps kunnen aanroepen (android:exported="true", of een intent-filter zonder exported):
  welke acties, categorieen en data het aanneemt en welke android:permission ervoor nodig is;
- elke android:permission op een onderdeel (ook niet-geexporteerde, zoals BIND_JOB_SERVICE);
- elke provider met grantUriPermissions, en dat geen provider zonder reden exported is;
- elk <queries>-item (welke andere apps Tools mag zien);
- de beveiligingsinstellingen op <application> (allowBackup, usesCleartextTraffic, debuggable, …);
- dat build.sh de app niet als debugversie bouwt.

Iets nieuws, iets wat verdwenen is of iets wat anders is dan op de lijst laat de controle falen. Bijwerken = de lijst
aanpassen, met een reden (en bij een nieuwe toestemming of verbinding naar buiten ook docs/privacybeleid*.md en
docs/play-data-safety.md, zie AGENTS.md "Beveiliging").

Aanroepen: python3 tests/manifest/controle.py  (ook vanuit tests/run-ui.sh). --zelftest controleert dat de controle
zelf veranderingen opmerkt.
"""
import copy
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'app/AndroidManifest.xml'
LIJST = Path(__file__).resolve().parent / 'lijst.json'
A = '{http://schemas.android.com/apk/res/android}'
ONDERDELEN = ('activity', 'activity-alias', 'service', 'receiver', 'provider')
# Instellingen op <application> die over beveiliging gaan; afwezig = null in de lijst
APP_ATTR = ('allowBackup', 'usesCleartextTraffic', 'debuggable', 'testOnly', 'networkSecurityConfig',
            'fullBackupContent', 'dataExtractionRules', 'requestLegacyExternalStorage', 'sharedUserId')


def _reden(fouten, waar, item):
    r = item.get('reden') if isinstance(item, dict) else None
    if not isinstance(r, str) or len(r.strip()) < 10:
        fouten.append(waar + ': geen (duidelijke) reden in tests/manifest/lijst.json')


def _filters(c):
    acties, cats, data = set(), set(), set()
    for f in c.findall('intent-filter'):
        acties |= {a.get(A + 'name') for a in f.findall('action')}
        cats |= {a.get(A + 'name') for a in f.findall('category')}
        for d in f.findall('data'):
            data |= {k[len(A):] + '=' + v for k, v in d.attrib.items() if k.startswith(A)}
    return sorted(acties), sorted(cats), sorted(data)


def _intent(q):
    """Een <queries>-item als korte tekst, bijv. "intent action=… category=… data=…" of "package=…"."""
    if q.tag == 'package':
        return 'package=' + (q.get(A + 'name') or '')
    if q.tag == 'provider':
        return 'provider=' + (q.get(A + 'authorities') or '')
    delen = [q.tag]
    for e in q:
        for k, v in sorted(e.attrib.items()):
            if k.startswith(A):
                delen.append(e.tag + '.' + k[len(A):] + '=' + v)
    return ' '.join(delen)


def controleer(root, lijst, build_sh=''):
    fouten = []
    # Toestemmingen
    gezien = {}
    for e in root:
        if e.tag in ('uses-permission', 'uses-permission-sdk-23'):
            gezien[e.get(A + 'name')] = e.get(A + 'maxSdkVersion')
    verwacht = lijst['toestemmingen']
    for naam, max_sdk in gezien.items():
        if naam not in verwacht:
            fouten.append('Nieuwe toestemming zonder reden: ' + naam)
            continue
        _reden(fouten, 'Toestemming ' + naam, verwacht[naam])
        if (verwacht[naam].get('maxSdk') or None) != max_sdk:
            fouten.append('Toestemming ' + naam + ': maxSdkVersion is ' + str(max_sdk) + ', op de lijst ' + str(verwacht[naam].get('maxSdk')))
    for naam in verwacht:
        if naam not in gezien:
            fouten.append('Toestemming staat op de lijst maar niet meer in het manifest (lijst bijwerken): ' + naam)

    app = root.find('application')
    if app is None:
        return fouten + ['Geen <application> in het manifest']
    # Instellingen op <application>
    for a in APP_ATTR:
        v = app.get(A + a)
        item = lijst['application'].get(a)
        if item is None:
            fouten.append('application/' + a + ' staat niet op de lijst')
            continue
        if item.get('waarde') != v:
            fouten.append('application/' + a + ' is ' + str(v) + ', op de lijst ' + str(item.get('waarde')))
        _reden(fouten, 'application/' + a, item)
    if app.get(A + 'debuggable') == 'true' or app.get(A + 'testOnly') == 'true':
        fouten.append('De app mag nooit debuggable of testOnly zijn')
    if re.search(r'--debug-mode\b', build_sh):
        fouten.append('build.sh bouwt met aapt2 --debug-mode (debuggable)')

    # Onderdelen
    exp, perms, grant = {}, {}, {}
    for c in app:
        if c.tag not in ONDERDELEN:
            continue
        naam = c.get(A + 'name') or c.get(A + 'targetActivity') or '?'
        ex = c.get(A + 'exported')
        heeft_filter = c.find('intent-filter') is not None
        if ex is None and heeft_filter:
            fouten.append(c.tag + ' ' + naam + ': intent-filter zonder android:exported (zet het expliciet)')
        if ex == 'true' or (ex is None and heeft_filter):
            acties, cats, data = _filters(c)
            exp[naam] = {'soort': c.tag, 'acties': acties, 'categorieen': cats, 'data': data,
                         'permissie': c.get(A + 'permission')}
        if c.get(A + 'permission'):
            perms[naam] = c.get(A + 'permission')
        if c.tag == 'provider' and c.get(A + 'grantUriPermissions') == 'true':
            grant[naam] = c.get(A + 'authorities')
    lexp = lijst['geexporteerd']
    for naam, o in exp.items():
        if naam not in lexp:
            fouten.append('Nieuw geexporteerd onderdeel zonder reden: ' + o['soort'] + ' ' + naam)
            continue
        l = lexp[naam]
        _reden(fouten, 'Geexporteerd ' + naam, l)
        for k in ('soort', 'acties', 'categorieen', 'data', 'permissie'):
            lv = l.get(k)
            if k in ('acties', 'categorieen', 'data'):
                lv = sorted(lv or [])
            if lv != o[k]:
                fouten.append('Geexporteerd ' + naam + ': ' + k + ' is ' + json.dumps(o[k]) + ', op de lijst ' + json.dumps(lv))
    for naam in lexp:
        if naam not in exp:
            fouten.append('Staat als geexporteerd op de lijst maar is dat niet (meer) (lijst bijwerken): ' + naam)

    lperm = lijst['onderdeelpermissies']
    bekend = {}
    for perm, item in lperm.items():
        _reden(fouten, 'android:permission ' + perm, item)
        for n in item.get('onderdelen', []):
            bekend[n] = perm
    for naam, perm in perms.items():
        if bekend.get(naam) != perm:
            fouten.append('android:permission op ' + naam + ' is ' + perm + ', op de lijst ' + str(bekend.get(naam)))
    for naam, perm in bekend.items():
        if naam not in perms:
            fouten.append('android:permission ' + perm + ' op ' + naam + ' staat op de lijst maar niet in het manifest (lijst bijwerken)')

    lgrant = lijst['grantUriPermissions']
    for naam, auth in grant.items():
        if naam not in lgrant:
            fouten.append('Provider met grantUriPermissions zonder reden: ' + naam)
            continue
        _reden(fouten, 'grantUriPermissions ' + naam, lgrant[naam])
        if lgrant[naam].get('authorities') != auth:
            fouten.append('Provider ' + naam + ': authorities is ' + str(auth) + ', op de lijst ' + str(lgrant[naam].get('authorities')))
    for naam in lgrant:
        if naam not in grant:
            fouten.append('grantUriPermissions op de lijst maar niet in het manifest (lijst bijwerken): ' + naam)

    # <queries>
    qs = []
    for q in root.findall('queries'):
        qs += [_intent(x) for x in q]
    lq = lijst['queries']
    for s in qs:
        if s not in lq:
            fouten.append('Nieuw <queries>-item zonder reden: ' + s)
        else:
            _reden(fouten, '<queries> ' + s, lq[s])
    for s in lq:
        if s not in qs:
            fouten.append('<queries>-item op de lijst maar niet in het manifest (lijst bijwerken): ' + s)
    return fouten


def laad():
    return ET.parse(MANIFEST).getroot(), json.loads(LIJST.read_text(encoding='utf-8'))


def zelftest():
    """De controle moet elke soort verandering opmerken (en het echte manifest goedkeuren)."""
    root, lijst = laad()
    build = (ROOT / 'build.sh').read_text(encoding='utf-8')
    assert controleer(root, lijst, build) == [], 'het echte manifest moet goed zijn'
    app = root.find('application')

    def faalt(wijzig, wat, b=build):
        r = copy.deepcopy(root)
        wijzig(r)
        assert controleer(r, lijst, b), 'niet opgemerkt: ' + wat

    def nieuwe_perm(r):
        r.insert(0, ET.Element('uses-permission', {A + 'name': 'android.permission.READ_PHONE_NUMBERS'}))
    faalt(nieuwe_perm, 'nieuwe toestemming')

    def max_sdk_weg(r):
        for e in r.findall('uses-permission'):
            e.attrib.pop(A + 'maxSdkVersion', None)
    faalt(max_sdk_weg, 'maxSdkVersion weg')

    def exporteer(r):
        for c in r.find('application').findall('activity'):
            if c.get(A + 'exported') == 'false':
                c.set(A + 'exported', 'true')
                return
    faalt(exporteer, 'onderdeel opengezet')

    def filter_zonder_exported(r):
        c = r.find('application').find('service')
        c.attrib.pop(A + 'exported', None)
        f = ET.SubElement(c, 'intent-filter')
        ET.SubElement(f, 'action', {A + 'name': 'nl.rene.tools.IETS'})
    faalt(filter_zonder_exported, 'intent-filter zonder exported')

    def extra_actie(r):
        for c in r.find('application'):
            if c.get(A + 'name') == '.MainActivity':
                f = ET.SubElement(c, 'intent-filter')
                ET.SubElement(f, 'action', {A + 'name': 'android.intent.action.VIEW'})
                ET.SubElement(f, 'data', {A + 'scheme': 'https'})
    faalt(extra_actie, 'nieuwe intent-filter op een geexporteerd onderdeel')

    def perm_weg(r):
        for c in r.find('application'):
            if c.get(A + 'name') == '.VpnSvc':
                c.attrib.pop(A + 'permission')
    faalt(perm_weg, 'android:permission weggehaald')

    def provider_open(r):
        for c in r.find('application').findall('provider'):
            c.set(A + 'exported', 'true')
            return
    faalt(provider_open, 'provider opengezet')

    def grant(r):
        p = ET.SubElement(r.find('application'), 'provider',
                          {A + 'name': '.Nieuw', A + 'authorities': 'nl.rene.tools.nieuw', A + 'exported': 'false',
                           A + 'grantUriPermissions': 'true'})
        assert p is not None
    faalt(grant, 'nieuwe provider met grantUriPermissions')

    def query(r):
        q = r.find('queries')
        ET.SubElement(q, 'package', {A + 'name': 'com.voorbeeld.app'})
    faalt(query, 'nieuw <queries>-item')

    def debug(r):
        r.find('application').set(A + 'debuggable', 'true')
    faalt(debug, 'debuggable')

    def backup_uit_regels(r):
        r.find('application').attrib.pop(A + 'fullBackupContent', None)
    faalt(backup_uit_regels, 'backupregels weg')

    faalt(lambda r: None, 'aapt2 --debug-mode', build.replace('$AAPT2 link', '$AAPT2 link --debug-mode', 1))

    l2 = copy.deepcopy(lijst)
    l2['toestemmingen']['android.permission.CAMERA']['reden'] = ''
    assert controleer(root, l2, build), 'niet opgemerkt: lege reden'
    assert app is not None
    print('Zelftest manifestcontrole: alle veranderingen opgemerkt')


def main():
    if '--zelftest' in sys.argv:
        zelftest()
        return 0
    root, lijst = laad()
    fouten = controleer(root, lijst, (ROOT / 'build.sh').read_text(encoding='utf-8'))
    if fouten:
        print('Manifestcontrole: het manifest wijkt af van tests/manifest/lijst.json')
        for f in fouten:
            print('  - ' + f)
        print('Klopt de wijziging? Zet hem met de reden in tests/manifest/lijst.json (AGENTS.md, "Beveiliging").')
        return 1
    print('Manifestcontrole: toestemmingen, geexporteerde onderdelen, permissies, providers, queries en '
          'application-instellingen kloppen met de lijst')
    return 0


if __name__ == '__main__':
    sys.exit(main())
