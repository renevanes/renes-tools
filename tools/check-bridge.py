#!/usr/bin/env python3
"""Controleert dat de interface alleen Android-functies aanroept die echt bestaan.

- web/src (de app): elke Android.xxx moet een @JavascriptInterface in MainActivity.java of FeatureBridge.java zijn
  en ook in de nep-brug (script/01-nepbrug.js) staan, anders werken de tests met iets wat de app niet heeft.
- web/start.html (telefoon-skin): elke Android.xxx moet in HomeActivity.java staan.
Uitzonderingen: aanroepen die achter `typeof Android.xxx === 'function'` staan (bewust optioneel).
Stopt met foutcode 1 bij een probleem.
"""
import os, re, sys, glob
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
def read(p): return open(os.path.join(root, p), encoding='utf-8').read()
def bridge(java):
    return set(re.findall(r'@JavascriptInterface\s+public\s+[\w<>\[\]]+\s+(\w+)\s*\(', read(java)))
def calls(text):
    return set(re.findall(r'\bAndroid\.(\w+)\s*\(', text))
def optional(text):
    return set(re.findall(r"typeof\s+Android\.(\w+)\s*[!=]==?\s*'function'", text))
main = bridge('app/src/nl/rene/tools/MainActivity.java') | bridge('app/src/nl/rene/tools/FeatureBridge.java')
home = bridge('app/src/nl/rene/tools/HomeActivity.java')
web = ''.join(read(os.path.relpath(p, root)) for p in sorted(glob.glob(os.path.join(root, 'web/src/script/*.js'))) if not p.endswith('01-nepbrug.js'))
web += ''.join(read(os.path.relpath(p, root)) for p in sorted(glob.glob(os.path.join(root, 'web/src/schermen/*.html'))))
web += read('web/src/vensters.html')
mock_src = read('web/src/script/01-nepbrug.js')
mock = set(re.findall(r'(?m)(?:^|[\s,{])(\w+)\s*\([^)]*\)\s*\{', mock_src)) | set(re.findall(r'(?m)(?:^|[\s,{])(\w+)\s*:\s*(?:function|\()', mock_src))
problems = []
for f in sorted(calls(web) - main - optional(web)):
    problems.append('Interface roept Android.%s aan, maar MainActivity heeft die niet' % f)
for f in sorted((calls(web) & main) - mock):
    problems.append('Android.%s ontbreekt in de nep-brug (01-nepbrug.js)' % f)
start = read('web/start.html')
for f in sorted(calls(start) - home - optional(start)):
    problems.append('Telefoon-skin roept Android.%s aan, maar HomeActivity heeft die niet' % f)
unused = sorted(main - calls(web) - {'toast'})
if unused: print('Niet gebruikt door de interface (ter info): ' + ', '.join(unused))
if problems:
    print('\n'.join('✗ ' + p for p in problems)); sys.exit(1)
print('✓ brug klopt: %d functies in de app, %d in de skin' % (len(main), len(home)))
