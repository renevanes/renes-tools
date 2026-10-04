#!/usr/bin/env python3
"""Voegt de onderdelen in web/src/ (volgorde in web/src/volgorde.txt) samen tot web/index.html.

De interface is één pagina voor de WebView, maar wordt bewerkt in losse onderdelen:
  web/src/stijl.css            alle opmaak
  web/src/schermen/NN-*.html   één bestand per scherm
  web/src/vensters.html        meldingen, keuzemenu, dialogen, vergrendelscherm
  web/src/script/NN-*.js       de code, per tool (00-begin en 29-start horen bij elkaar)
Gebruik: tools/web-samenvoegen.py [uitvoerbestand]   (standaard web/index.html)
"""
import os, sys
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
src = os.path.join(root, 'web', 'src')
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, 'web', 'index.html')
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
html = ''.join(open(os.path.join(src, p), encoding='utf-8', newline='').read() for p in parts)
tmp = out + '.tmp'
open(tmp, 'w', encoding='utf-8', newline='').write(html)
os.replace(tmp, out)
print('web/index.html: %d onderdelen, %d regels' % (len(parts), html.count('\n')))
