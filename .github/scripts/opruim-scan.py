#!/usr/bin/env python3
"""Opruimscan: zoekt dode code en plekken om te versimpelen. Schrijft een Markdown-rapport naar stdout.

Gebruik:  python3 .github/scripts/opruim-scan.py            (rapport)
          python3 .github/scripts/opruim-scan.py --json     (zelfde, als JSON)
De workflow Onderhoud zet het rapport in het issue "Opruimen: dode code en versimpelen"; de maandelijkse
opruimronde (AGENTS.md) werkt het af. Alles is een *kandidaat*: de scan zoekt op naam, dus een naam die alleen in een
tekenreeks wordt opgebouwd ('ci-' + x, "on" + naam, reflectie) ziet hij niet. Controleer altijd met grep vóór het weghalen.
Bekende uitzonderingen (bewust laten staan) in .github/opruim-uitzonderingen.txt: één naam per regel, # = opmerking.

Wat hij zoekt:
  1. JavaScript-functies in web/src en web/start.html die nergens worden aangeroepen (of alleen in de tests)
  2. Java-classes en -methoden in app/src die nergens worden gebruikt (of alleen in tests/jvm)
  3. Brugfuncties (@JavascriptInterface) die de interface niet gebruikt
  4. CSS-klassen die in geen enkel scherm of script voorkomen
  5. Versimpelen: de grootste bestanden, de langste functies en herhaalde blokken Java-code
Stopt nooit met een fout door de bevindingen zelf (exitcode 0); 1 = de scan zelf liep vast.
"""
import glob, json, os, re, sys
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
MAX = 40  # zoveel per lijst in het rapport


def lees(p):
    with open(os.path.join(ROOT, p), encoding='utf-8', errors='replace') as f:
        return f.read()


def bestanden(*patronen):
    out = []
    for p in patronen:
        out += sorted(os.path.relpath(x, ROOT) for x in glob.glob(os.path.join(ROOT, p), recursive=True))
    return out


def uitzonderingen():
    p = os.path.join(ROOT, '.github', 'opruim-uitzonderingen.txt')
    if not os.path.exists(p):
        return set()
    return {l.split('#')[0].strip() for l in open(p, encoding='utf-8') if l.split('#')[0].strip()}


def woorden(tekst):
    """Hoe vaak elk woord voorkomt."""
    n = defaultdict(int)
    for w in re.findall(r'[A-Za-z_$][\w$]*', tekst):
        n[w] += 1
    return n


_JAVA_TOKEN = re.compile(r'"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'|/\*.*?\*/|//[^\n]*', re.S)


def zonder_commentaar_java(t):
    """Commentaar weg (regelnummers blijven kloppen); tekenreeksen blijven staan, ook als er "*/*" in staat."""
    return _JAVA_TOKEN.sub(lambda m: m.group() if m.group()[0] in '"\'' else '\n' * m.group().count('\n'), t)


def blok_lengte(regels, start):
    """Aantal regels van het blok dat op regel `start` met { begint (grof: telt accolades, slaat tekenreeksen over)."""
    diepte, gezien = 0, False
    for i in range(start, len(regels)):
        r = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`(?:\\.|[^`\\])*`', '""', regels[i])
        r = re.sub(r'(^|\s)//.*', '', r)  # commentaar; niet in een regex als /^https:\/\//
        for ch in r:
            if ch == '{':
                diepte += 1; gezien = True
            elif ch == '}':
                diepte -= 1
                if gezien and diepte <= 0:
                    return i - start + 1
    return len(regels) - start


def main():
    skip = uitzonderingen()
    js_app = [p for p in bestanden('web/src/script/*.js') if not p.endswith('01-testbrug.js')]
    html = bestanden('web/src/*.html', 'web/src/schermen/*.html') + ['web/start.html']
    css = bestanden('web/src/*.css')
    java = bestanden('app/src/**/*.java')
    java_tests = bestanden('tests/jvm/**/*.java')
    ui_tests = bestanden('tests/ui/*.js')
    res = bestanden('app/AndroidManifest.xml', 'app/res/**/*.xml')

    tekst = {p: lees(p) for p in js_app + html + css + java + java_tests + ui_tests + res + ['web/src/script/01-testbrug.js']}
    app_web = ''.join(tekst[p] for p in js_app + html)
    vendor = ''.join(lees(p) for p in bestanden('web/vendor/*'))  # Leaflet zet zelf klassen als leaflet-container
    app_java = ''.join(zonder_commentaar_java(tekst[p]) for p in java)
    w_app = woorden(app_web + app_java + ''.join(tekst[p] for p in res))
    w_tests = woorden(''.join(tekst[p] for p in java_tests + ui_tests) + tekst['web/src/script/01-testbrug.js'])
    rapport = {}

    # 1. JavaScript-functies
    js_dood, js_test = [], []
    for p in js_app + ['web/start.html']:
        for m in re.finditer(r'(?m)(?:^|[;}\s])function\s+([A-Za-z_$][\w$]*)\s*\(', tekst[p]):
            naam = m.group(1)
            if naam in skip:
                continue
            regel = tekst[p].count('\n', 0, m.start(1)) + 1
            if w_app[naam] <= 1:
                (js_test if w_tests[naam] else js_dood).append(f'`{naam}` — {p}:{regel}')
    rapport['js_dood'], rapport['js_alleen_tests'] = js_dood, js_test

    # 2. Java-classes en -methoden
    cls_dood, meth_dood, meth_test = [], [], []
    for p in java:
        naam = os.path.basename(p)[:-5]
        if naam not in skip and w_app[naam] <= tekst[p].count(naam) and not re.search(r'\b' + naam + r'\b', ''.join(tekst[r] for r in res)):
            # alleen in zijn eigen bestand genoemd en niet in het manifest
            buiten = sum(zonder_commentaar_java(tekst[q]).count(naam) for q in java if q != p)
            if buiten == 0:
                cls_dood.append(f'`{naam}` — {p}' + (' (alleen in tests)' if w_tests[naam] else ''))
        bron = zonder_commentaar_java(tekst[p])
        for m in re.finditer(r'(?m)^[ \t]*((?:@\w+(?:\([^)]*\))?\s+)*)((?:(?:public|private|protected|static|final|synchronized|native)\s+)*)'
                             r'(?:<[^>]+>\s+)?[\w.$<>\[\],? ]+?\s+([a-z]\w*)\s*\([^;{]*\)\s*(?:throws [\w., ]+)?\{', bron):
            ann, mods, mnaam = m.group(1), m.group(2), m.group(3)
            if mnaam in skip or 'Override' in ann or 'JavascriptInterface' in ann or mnaam in ('main', 'run', 'call', 'apply', 'accept', 'test', 'get', 'compare', 'equals', 'hashCode', 'toString', 'close'):
                continue
            if re.match(r'on[A-Z]', mnaam) and 'static' not in mods:
                continue  # Android-terugroepfuncties (onCreate, onReceive, …) worden door het systeem aangeroepen
            if mnaam in ('if', 'for', 'while', 'switch', 'catch', 'synchronized', 'return', 'new', 'else'):
                continue
            if w_app[mnaam] <= 1:
                regel = bron.count('\n', 0, m.start()) + 1
                (meth_test if w_tests[mnaam] else meth_dood).append(f'`{naam}.{mnaam}` — {p}:{regel}')
    rapport['java_classes'], rapport['java_methoden'], rapport['java_alleen_tests'] = cls_dood, meth_dood, meth_test

    # 3. Brugfuncties die de interface niet gebruikt
    brug = set()
    for p in java:
        if p.endswith(('Bridge.java', 'MainActivity.java')):
            brug |= set(re.findall(r'@JavascriptInterface\s+public\s+[\w<>\[\]]+\s+(\w+)\s*\(', tekst[p]))
    aangeroepen = set(re.findall(r'\bAndroid\.(\w+)', app_web))
    rapport['brug'] = sorted(f'`{f}`' for f in brug - aangeroepen - {'toast'} - skip)
    # Telefoon-skin: HomeActivity tegen web/start.html (zonder de eigen nabootsing daarin: `naam: () =>` / `naam() {`)
    home = set(re.findall(r'@JavascriptInterface\s+public\s+[\w<>\[\]]+\s+(\w+)\s*\(', tekst['app/src/nl/rene/tools/HomeActivity.java']))
    start = set(re.findall(r'\bAndroid\.(\w+)', tekst['web/start.html']))
    rapport['brug'] += sorted(f'`{f}` (Telefoon-skin)' for f in home - start - skip)

    # 4. CSS-klassen
    gebruik = app_web + app_java + vendor
    # klassen die in stukken worden opgebouwd: 'sf-' + x, "sfrow sf-'+esc(…)", `ci-${x}`
    dyn = set(re.findall(r'''([a-z][\w]*-)['"`]\s*\+''', gebruik)) | set(re.findall(r'([a-z][\w]*-)\$\{', gebruik))
    css_dood = defaultdict(list)
    for p in css:
        for k in sorted(set(re.findall(r'\.([a-zA-Z][\w-]*)(?=[\s.,:{>\[)+~])', re.sub(r'/\*.*?\*/', '', tekst[p], flags=re.S)))):
            if k in skip or re.match(r'^\d', k):
                continue
            if any(k.startswith(d) for d in dyn):
                continue
            if not re.search(r'(?<![\w-])' + re.escape(k) + r'(?![\w-])', gebruik):
                css_dood[k].append(p)
    rapport['css'] = [f'`.{k}` — ' + ', '.join(ps) for k, ps in sorted(css_dood.items())]

    # 5. Versimpelen
    groot = sorted(((len(tekst[p].splitlines()), len(tekst[p]), p) for p in java + js_app), reverse=True)
    rapport['grote_bestanden'] = [f'{p}: {r} regels, {t // 1024} kB' for r, t, p in groot[:15]]
    lang = []
    for p in java:
        regels = zonder_commentaar_java(tekst[p]).splitlines()
        for i, r in enumerate(regels):
            m = re.match(r'\s*(?:(?:public|private|protected|static|final|synchronized)\s+)+[\w.$<>\[\],? ]+?\s+(\w+)\s*\(.*\)\s*(?:throws [\w., ]+)?\{\s*$', r)
            if m:
                n = blok_lengte(regels, i)
                if n >= 150:
                    lang.append((n, f'`{m.group(1)}` — {p}:{i + 1} ({n} regels)'))
    for p in js_app:
        regels = tekst[p].splitlines()
        for i, r in enumerate(regels):
            m = re.match(r'\s*(?:async\s+)?function\s+(\w+)\s*\(', r)
            if m:
                n = blok_lengte(regels, i)
                tekens = len('\n'.join(regels[i:i + n]))
                if n >= 80 or tekens >= 8000:
                    lang.append((max(n, tekens // 100), f'`{m.group(1)}` — {p}:{i + 1} ({n} regels, {tekens // 1000} k tekens)'))
    rapport['lange_functies'] = [s for _, s in sorted(lang, reverse=True)[:25]]

    # herhaalde blokken: 8 opeenvolgende niet-triviale regels Java die elders precies zo voorkomen
    venster, gezien = 8, defaultdict(list)
    for p in java:
        regels = [(i + 1, re.sub(r'\s+', ' ', r).strip()) for i, r in enumerate(zonder_commentaar_java(tekst[p]).splitlines())]
        regels = [(i, r) for i, r in regels if len(r) > 12 and not r.startswith(('import ', '@', '}'))]
        for k in range(len(regels) - venster + 1):
            blok = '\n'.join(r for _, r in regels[k:k + venster])
            gezien[blok].append((p, regels[k][0]))
    dubbel, al = [], set()
    for blok, plekken in gezien.items():
        uniek = sorted(set(plekken))
        if len(uniek) < 2:
            continue
        sleutel = tuple((p, r // 20) for p, r in uniek)
        if sleutel in al:
            continue
        al.add(sleutel)
        dubbel.append(f'{len(uniek)}× — ' + ', '.join(f'{p}:{r}' for p, r in uniek[:4]) + (' …' if len(uniek) > 4 else ''))
    rapport['herhaald'] = dubbel[:20]
    rapport['_aantal_herhaald'] = len(dubbel)
    return rapport


def markdown(r):
    def lijst(titel, items, uitleg=''):
        print(f'\n### {titel} ({len(items)})')
        if uitleg:
            print(uitleg)
        if not items:
            print('Geen.')
        for i in items[:MAX]:
            print('- ' + i)
        if len(items) > MAX:
            print(f'- … en nog {len(items) - MAX}')

    print('Kandidaten: de scan zoekt op naam. Controleer met grep (ook op tekenreeksen, `onclick`, `evaluateJavascript`, '
          'het manifest en reflectie) vóór het weghalen. Bewust laten staan: zet de naam in `.github/opruim-uitzonderingen.txt`.')
    print('\n## Dode code')
    lijst('JavaScript-functies die nergens worden aangeroepen', r['js_dood'])
    lijst('JavaScript-functies die alleen de tests gebruiken', r['js_alleen_tests'], 'Weg (met de test) of juist gebruiken.')
    lijst('Java-classes die nergens worden gebruikt', r['java_classes'])
    lijst('Java-methoden die nergens worden aangeroepen', r['java_methoden'])
    lijst('Java-methoden die alleen de tests gebruiken', r['java_alleen_tests'], 'Vaak bewust (testbare logica); alleen weg als de app ze echt niet meer nodig heeft.')
    lijst('Brugfuncties die de interface niet gebruikt', r['brug'], 'Weghalen = ook uit 01-testbrug.js; let op de Telefoon-skin.')
    lijst('CSS-klassen die nergens voorkomen', r['css'])
    print('\n## Versimpelen')
    lijst('Grootste bestanden', r['grote_bestanden'], 'Kandidaten om op te splitsen, langs de grenzen van de tools.')
    lijst('Langste functies', r['lange_functies'], 'Kandidaten om in kleinere, geteste stukken te verdelen.')
    lijst('Herhaalde blokken Java-code (8+ gelijke regels)', r['herhaald'],
          'Kandidaten voor één gedeelde hulpfunctie.' + (f' (de eerste 20 van {r["_aantal_herhaald"]})' if r['_aantal_herhaald'] > 20 else ''))


if __name__ == '__main__':
    try:
        r = main()
    except Exception as e:
        print(f'Opruimscan mislukt: {e}', file=sys.stderr)
        sys.exit(1)
    if '--json' in sys.argv:
        print(json.dumps(r, indent=2, ensure_ascii=False))
    else:
        markdown(r)
