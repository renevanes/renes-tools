#!/usr/bin/env python3
"""Controleert de meegeleverde bibliotheken en vastgezette versies op nieuwere versies en bekende kwetsbaarheden.

Dependabot kent deze niet (geen package.json of Gradle), dus dit script doet het:
  - web/vendor/leaflet.js en web/vendor/jsQR.js   → npm-register + OSV (kwetsbaarheden)
  - app/lib/*/libwhisper*.so (whisper.cpp-commit)  → nieuwste release + OSV
  - Playwright-versie in publiceren.yml            → npm-register
  - Android build-tools in setup-tools.sh          → nieuwste release van androidide-tools

Schrijft een Markdown-rapport naar stdout. Afsluitcode 0 = alles bij, 10 = er is iets te doen, 1 = fout.
Alleen standaardbibliotheek; GITHUB_TOKEN (optioneel) voorkomt limieten op de GitHub-API.
Gebruik: python3 .github/scripts/onderhoud-controle.py [--json]
"""
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def http_json(url, data=None, github=False):
    headers = {"User-Agent": "renes-tools-onderhoud", "Accept": "application/json"}
    if github and os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    body = json.dumps(data).encode() if data is not None else None
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def lees(pad, patroon):
    tekst = (ROOT / pad).read_text(errors="replace")
    m = re.search(patroon, tekst)
    if not m:
        raise RuntimeError(f"versie niet gevonden in {pad} (patroon {patroon})")
    return m.group(1)


def npm_nieuwste(naam):
    return http_json(f"https://registry.npmjs.org/{naam}/latest")["version"]


def osv(query):
    try:
        res = http_json("https://api.osv.dev/v1/query", query)
    except Exception as e:  # OSV onbereikbaar: niet stil "veilig" melden
        return [f"(OSV niet bereikbaar: {e})"]
    uit = []
    for v in res.get("vulns", []):
        ids = [v["id"]] + [a for a in v.get("aliases", []) if a.startswith("CVE-")]
        uit.append(f"{', '.join(ids)}: {v.get('summary', '').strip() or 'geen samenvatting'}")
    return uit


def versie_tuple(v):
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3])


def main():
    items = []

    def item(naam, waar, huidig, nieuwst, kwetsbaar=(), opmerking=""):
        verouderd = nieuwst is not None and huidig != nieuwst and (
            not re.match(r"^\d", str(huidig)) or versie_tuple(nieuwst) > versie_tuple(huidig))
        items.append({"naam": naam, "waar": waar, "huidig": huidig, "nieuwst": nieuwst,
                      "verouderd": bool(verouderd), "kwetsbaar": list(kwetsbaar), "opmerking": opmerking})

    # Leaflet en jsQR (meegeleverd in web/vendor)
    for naam, npm, pad, patroon in [
        ("Leaflet", "leaflet", "web/vendor/leaflet.js", r"Leaflet (\d+\.\d+\.\d+)"),
        ("jsQR", "jsqr", "web/vendor/jsQR.js", r"jsQR (\d+\.\d+\.\d+)"),
    ]:
        huidig = lees(pad, patroon)
        item(naam, pad, huidig, npm_nieuwste(npm),
             osv({"package": {"name": npm, "ecosystem": "npm"}, "version": huidig}))

    # Playwright (alleen tests; vastgezet in de publicatie-workflow)
    huidig = lees(".github/workflows/publiceren.yml", r"playwright@(\d+\.\d+\.\d+)")
    item("Playwright (tests)", ".github/workflows/publiceren.yml", huidig, npm_nieuwste("playwright"))

    # whisper.cpp (zelf gebouwde libwhisper*.so)
    commit = lees("app/lib/arm64-v8a/README.txt", r"whisper\.cpp commit ([0-9a-f]{40})")
    rel = http_json("https://api.github.com/repos/ggml-org/whisper.cpp/releases/latest", github=True)
    tag = rel["tag_name"]
    try:
        cmp = http_json(f"https://api.github.com/repos/ggml-org/whisper.cpp/compare/{commit}...{tag}", github=True)
        achter = cmp.get("ahead_by", 0)
    except Exception:
        achter = None
    item("whisper.cpp", "app/lib/*/libwhisper*.so", commit[:12], tag if achter else None,
         osv({"commit": commit}),
         f"{achter} commits achter op {tag}; opnieuw bouwen met tools/whisper-build.sh" if achter else "")
    if achter:
        items[-1]["verouderd"] = True

    # Android build-tools (bouwgereedschap, komt niet in de app)
    huidig = lees("setup-tools.sh", r"build-tools/(\d+\.\d+\.\d+)/")
    rel = http_json("https://api.github.com/repos/AndroidIDEOfficial/androidide-tools/releases/latest", github=True)
    item("Android build-tools", "setup-tools.sh", huidig, rel["tag_name"].lstrip("v"))

    te_doen = [i for i in items if i["verouderd"] or i["kwetsbaar"]]
    if "--json" in sys.argv:
        print(json.dumps({"items": items, "te_doen": len(te_doen)}, indent=2))
    else:
        print("| Onderdeel | Waar | Nu | Nieuwste | Bekende kwetsbaarheden |")
        print("|---|---|---|---|---|")
        for i in items:
            status = "**" + str(i["nieuwst"]) + "**" if i["verouderd"] else "bij"
            kw = "<br>".join(i["kwetsbaar"]) or "geen"
            print(f"| {i['naam']} | `{i['waar']}` | {i['huidig']} | {status} | {kw} |")
        for i in items:
            if i["opmerking"]:
                print(f"\n- {i['naam']}: {i['opmerking']}")
    return 10 if te_doen else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print(f"Controle mislukt: {e}", file=sys.stderr)
        sys.exit(1)
