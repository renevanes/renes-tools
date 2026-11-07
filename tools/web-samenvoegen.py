#!/usr/bin/env python3
"""Voegt de onderdelen in web/src/ (volgorde in web/src/volgorde.txt) samen tot web/index.html.

De interface is één pagina voor de WebView, maar wordt bewerkt in losse onderdelen:
  web/src/stijl.css            alle opmaak
  web/src/schermen/NN-*.html   één bestand per scherm
  web/src/vensters.html        meldingen, keuzemenu, dialogen, vergrendelscherm
  web/src/script/NN-*.js       de code, per tool (00-begin en 29-start horen bij elkaar)
  web/src/taal/en.json         Engelse vertalingen (Nederlands → Engels), als <script type="application/json" id="taal-en">
Gebruik: tools/web-samenvoegen.py [--zonder-testbrug] [uitvoerbestand]   (standaard web/index.html)
  --zonder-testbrug   zonder script/01-testbrug.js (de nagebootste Android-brug is alleen voor tests in de browser)
"""
import os, sys
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
src = os.path.join(root, 'web', 'src')
args = sys.argv[1:]
no_mock = '--zonder-testbrug' in args
args = [a for a in args if a != '--zonder-testbrug']
bad = [a for a in args if a.startswith('-')]
if bad: sys.exit('Onbekende optie: ' + ' '.join(bad) + ' (zie de uitleg bovenaan dit script)')
if len(args) > 1: sys.exit('Hooguit één uitvoerbestand')
out = args[0] if args else os.path.join(root, 'web', 'index.html')
parts = [l.strip() for l in open(os.path.join(src, 'volgorde.txt'), encoding='utf-8') if l.strip() and not l.startswith('#')]
on_disk = set()
for d, _, files in os.walk(src):
    for f in files:
        rel = os.path.relpath(os.path.join(d, f), src).replace(os.sep, '/')
        if rel != 'volgorde.txt': on_disk.add(rel)
missing = [p for p in parts if p not in on_disk]
extra = sorted(on_disk - set(parts))
if missing: sys.exit('Ontbreekt in web/src: ' + ', '.join(missing))
if extra: sys.exit('Staat niet in web/src/volgorde.txt: ' + ', '.join(extra))
use = [p for p in parts if not (no_mock and p == 'script/01-testbrug.js')]
def part(p):
    t = open(os.path.join(src, p), encoding='utf-8', newline='').read()
    if p == 'script/01-testbrug.js':
        # De testbrug gebruikt dezelfde sjablonen als de APK; geen tweede, verouderende lijst.
        import json as _j
        with open(os.path.join(root, 'web', 'toiletten-bronnen.json'), encoding='utf-8') as f:
            sjablonen = _j.load(f)['sjablonen']
        t = t.replace('/* TOILETTEN_SJABLONEN */ []', _j.dumps(sjablonen, ensure_ascii=False).replace('<', '\\u003c'))
    if p.endswith('.json'):
        # Woordenboek (taal/en.json): als gegevens in de pagina, pas gelezen als die taal gekozen is (02-taal.js).
        # Een "<" (bijv. "</script>" of "<!--") mag het script-blok niet afsluiten: in JSON-tekst is "\u003c" hetzelfde.
        import json as _j
        _j.loads(t)  # kapotte JSON = bouwfout, niet pas op de telefoon
        tid = 'taal-' + os.path.splitext(os.path.basename(p))[0]
        return '<script type="application/json" id="%s">' % tid + t.replace('<', '\\u003c') + '</script>\n'
    return t
html = ''.join(part(p) for p in use)
tmp = out + '.tmp'
open(tmp, 'w', encoding='utf-8', newline='').write(html)
os.replace(tmp, out)
print('%s: %d onderdelen, %d regels%s' % (os.path.relpath(out, root), len(use), html.count('\n'), ' (zonder testbrug)' if no_mock else ''))
