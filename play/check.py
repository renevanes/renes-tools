#!/usr/bin/env python3
"""Controleert de werkelijk gebouwde AAB, niet alleen het invoermanifest."""
import subprocess
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from config import profile

def require(condition, message):
    if not condition:
        raise ValueError(message)


A = '{http://schemas.android.com/apk/res/android}'


def check(xml, package, names):
    p = profile()
    root = ET.fromstring(xml)
    require(root.get('package') == package, 'Verkeerde Play-pakketnaam')
    require(int(root.find('uses-sdk').get(A + 'targetSdkVersion')) >= 36, 'targetSdk is te laag')
    permissions = {e.get(A + 'name') for e in root if e.tag.startswith('uses-permission')}
    require(permissions == set(p['toestemmingen']), 'Afwijkende rechten: ' + str(permissions ^ set(p['toestemmingen'])))
    app = root.find('application')
    expected = {'nl.rene.tools' + c for c in p['onderdelen']}
    actual = {c.get(A + 'name') for c in app if c.tag in ('activity', 'activity-alias', 'service', 'receiver', 'provider')}
    require(actual == expected, 'Afwijkende Android-onderdelen: ' + str(actual ^ expected))
    for c in app.findall('provider'):
        require(all(a.startswith(package + '.') for a in c.get(A + 'authorities', '').split(';')), 'Provider gebruikt de gewone pakketnaam')
    if not p['native']:
        require(not any(n.endswith('.so') for n in names), 'Onverwachte native programma\'s in de basisversie')


if __name__ == '__main__':
    bundle, tool, package = sys.argv[1:]
    xml = subprocess.check_output(['java', '-jar', tool, 'dump', 'manifest', '--bundle=' + bundle, '--module=base'])
    with ZipFile(bundle) as z:
        check(xml, package, z.namelist())
    print('Play-bundel gecontroleerd: pakket, rechten, onderdelen, providers en native bestanden kloppen')
