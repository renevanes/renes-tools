#!/usr/bin/env python3
"""Voegt de onderdelen in web/src/ (volgorde in web/src/volgorde.txt) samen tot web/index.html.

De interface is één pagina voor de WebView, maar wordt bewerkt in losse onderdelen:
  web/src/stijl.css            alle opmaak
  web/src/schermen/NN-*.html   één bestand per scherm
  web/src/vensters.html        meldingen, keuzemenu, dialogen, vergrendelscherm
  web/src/script/NN-*.js       de code, per tool (00-begin en 29-start horen bij elkaar)
Gebruik: tools/web-samenvoegen.py [--zonder-nepbrug] [uitvoerbestand]   (standaard web/index.html)
  --zonder-nepbrug   zonder script/01-nepbrug.js (de nagebootste Android-brug is alleen voor tests in de browser)
"""
import os, sys
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
src = os.path.join(root, 'web', 'src')
args = sys.argv[1:]
no_mock = '--zonder-nepbrug' in args
args = [a for a in args if a != '--zonder-nepbrug']
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
use = [p for p in parts if not (no_mock and p == 'script/01-nepbrug.js')]
html = ''.join(open(os.path.join(src, p), encoding='utf-8', newline='').read() for p in use)
tmp = out + '.tmp'
open(tmp, 'w', encoding='utf-8', newline='').write(html)
os.replace(tmp, out)
print('%s: %d onderdelen, %d regels%s' % (os.path.relpath(out, root), len(use), html.count('\n'), ' (zonder nepbrug)' if no_mock else ''))
