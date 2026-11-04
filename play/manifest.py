#!/usr/bin/env python3
"""Genereert het Play-manifest uit de expliciete lijst van rechten en onderdelen in basis.json."""
import re
import sys
import xml.etree.ElementTree as ET
from config import ROOT, profile

ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)
ET.register_namespace('tools', 'http://schemas.android.com/tools')
A = '{' + ANDROID + '}'


def main(uit, pakket):
    if not re.fullmatch(r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+\.play', pakket):
        raise ValueError('De Play-pakketnaam moet op .play eindigen')
    p = profile()
    tree = ET.parse(ROOT / 'app/AndroidManifest.xml')
    root = tree.getroot()
    for item in list(root):
        if item.tag.startswith('uses-permission'):
            if item.get(A + 'name') not in p['toestemmingen']:
                root.remove(item)
            elif item.get(A + 'name') == 'android.permission.SCHEDULE_EXACT_ALARM':
                item.attrib.pop(A + 'maxSdkVersion', None)
    queries = root.find('queries')
    if queries is not None:
        for query in list(queries):
            data = query.find('data')
            if data is None or data.get(A + 'mimeType') != 'application/pdf':
                queries.remove(query)
    app = root.find('application')
    for item in list(app):
        if item.tag in ('service', 'receiver', 'activity', 'activity-alias', 'provider'):
            if item.get(A + 'name') not in p['onderdelen']:
                app.remove(item)
                continue
            affinity = item.get(A + 'taskAffinity', '')
            if affinity.startswith('nl.rene.tools.'):
                item.set(A + 'taskAffinity', pakket + affinity[len('nl.rene.tools'):])
            authority = item.get(A + 'authorities', '')
            if authority.startswith('nl.rene.tools.'):
                nieuw = pakket + authority[len('nl.rene.tools'):]
                item.set(A + 'authorities', nieuw)
                print(authority, nieuw)
    app.set(A + 'fullBackupContent', '@xml/play_backup_rules')
    app.set(A + 'dataExtractionRules', '@xml/play_data_rules')
    ET.indent(tree, space='    ')
    tree.write(uit, encoding='utf-8', xml_declaration=True)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
