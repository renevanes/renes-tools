#!/usr/bin/env python3
"""Controleert de meegeleverde bibliotheken en vastgezette versies op nieuwere versies en bekende kwetsbaarheden.

Dependabot kent deze niet (geen package.json of Gradle), dus dit script doet het:
  - web/vendor/leaflet.js en web/vendor/jsQR.js   → npm-register + OSV (kwetsbaarheden)
  - app/lib/*/libwhisper*.so (whisper.cpp-commit)  → nieuwste release + OSV
  - app/lib/*/libllm.so (llama.cpp-commit)          → nieuwste release + OSV
  - Playwright-versie in publiceren.yml            → npm-register
  - Android build-tools, android.jar/targetSdk, aapt2, zig, Node en Java → nieuwste versies (Google, npm, PyPI, Node-schema, Adoptium)

Schrijft een Markdown-rapport naar stdout. Afsluitcode 0 = alles bij, 10 = er is iets te doen, 1 = fout.
Alleen standaardbibliotheek; GITHUB_TOKEN (optioneel) voorkomt limieten op de GitHub-API.
Gebruik: python3 .github/scripts/onderhoud-controle.py [--json]
"""
import json
import os
import re
import sys
import time
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


def http_text(url):
    req = urllib.request.Request(url, headers={"User-Agent": "renes-tools-onderhoud"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def workflow_versies(patroon):
    uit = []
    for f in sorted((ROOT / ".github/workflows").glob("*.yml")):
        uit += [int(x) for x in re.findall(patroon, f.read_text())]
    return uit


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


# Waar elke versie staat. Ook gebruikt door "--bestanden" (zonder netwerk, in Bouwen en testen), zodat een andere
# schrijfwijze in een README meteen opvalt en niet pas bij de maandagcontrole.
LEAFLET = ("web/vendor/leaflet.js", r"Leaflet (\d+\.\d+\.\d+)")
JSQR = ("web/vendor/jsQR.js", r"jsQR (\d+\.\d+\.\d+)")
PLAYWRIGHT = (".github/workflows/publiceren.yml", r"playwright@(\d+\.\d+\.\d+)")
WHISPER = ("app/lib/arm64-v8a/README.txt", r"whisper\.cpp (?:v[\d.]+, )?commit ([0-9a-f]{40})")
LLAMA = ("app/lib/arm64-v8a/README.txt", r"llama\.cpp (?:b\d+, )?commit ([0-9a-f]{40})")
BUILDTOOLS = ("setup-tools.sh", r"build-tools/(\d+\.\d+\.\d+)/")
AAPT2 = ("setup-tools.sh", r"aaptjs3@(\d+\.\d+\.\d+)")
PLATFORM = ("setup-tools.sh", r"sparse-checkout set android-(\d+)")
TARGET = ("build.sh", r"--target-sdk-version (\d+)")
ZIG = ("tools/whisper-build.sh", r'ZIG_VERSION="(\d+\.\d+\.\d+)"')
ALLE = {"Leaflet": LEAFLET, "jsQR": JSQR, "Playwright": PLAYWRIGHT, "whisper.cpp": WHISPER,
        "llama.cpp": LLAMA, "build-tools": BUILDTOOLS, "aapt2": AAPT2, "android-platform": PLATFORM,
        "targetSdk": TARGET, "zig": ZIG, "zig (llm)": ("tools/llm-build.sh", ZIG[1])}


def versie_tuple(v):
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3])


def main():
    items = []

    def item(naam, waar, huidig, nieuwst, kwetsbaar=(), opmerking="", verouderd=None):
        if verouderd is None:
            verouderd = nieuwst is not None and versie_tuple(nieuwst) > versie_tuple(huidig)
        items.append({"naam": naam, "waar": waar, "huidig": huidig, "nieuwst": nieuwst,
                      "verouderd": bool(verouderd), "kwetsbaar": list(kwetsbaar), "opmerking": opmerking})

    # Leaflet en jsQR (meegeleverd in web/vendor)
    for naam, npm, pad, patroon in [
        ("Leaflet", "leaflet", *LEAFLET),
        ("jsQR", "jsqr", *JSQR),
    ]:
        huidig = lees(pad, patroon)
        item(naam, pad, huidig, npm_nieuwste(npm),
             osv({"package": {"name": npm, "ecosystem": "npm"}, "version": huidig}))

    # Playwright (alleen tests; vastgezet in de publicatie-workflow, bouwen-en-testen.yml gebruikt playwright@1)
    huidig = lees(*PLAYWRIGHT)
    item("Playwright (tests)", ".github/workflows/publiceren.yml", huidig, npm_nieuwste("playwright"))

    # whisper.cpp (zelf gebouwde libwhisper*.so)
    commit = lees(*WHISPER)
    rel = http_json("https://api.github.com/repos/ggml-org/whisper.cpp/releases/latest", github=True)
    tag = rel["tag_name"]
    try:
        cmp = http_json(f"https://api.github.com/repos/ggml-org/whisper.cpp/compare/{commit}...{tag}", github=True)
        achter = cmp.get("ahead_by", 0)
        opm = f"{achter} commits achter op {tag}; opnieuw bouwen met tools/whisper-build.sh" if achter else ""
    except Exception as e:  # niet stil "bij" melden
        achter, opm = None, f"vergelijking met {tag} mislukt ({e}); controleer met de hand"
    item("whisper.cpp", "app/lib/*/libwhisper*.so", commit[:12], tag, osv({"commit": commit}), opm,
         verouderd=achter != 0)

    # llama.cpp (zelf gebouwde libllm.so, de lokale AI). Er komt bijna elke dag een release uit: alleen melden als
    # het echt ver achterloopt of als er een kwetsbaarheid bekend is.
    commit = lees(*LLAMA)
    rel = http_json("https://api.github.com/repos/ggml-org/llama.cpp/releases/latest", github=True)
    tag = rel["tag_name"]
    try:
        cmp = http_json(f"https://api.github.com/repos/ggml-org/llama.cpp/compare/{commit}...{tag}", github=True)
        achter = cmp.get("ahead_by", 0)
        opm = f"{achter} commits achter op {tag}; opnieuw bouwen met tools/llm-build.sh (vaste TAG/COMMIT daarin aanpassen)" if achter > 1500 else ""
    except Exception as e:
        achter, opm = None, f"vergelijking met {tag} mislukt ({e}); controleer met de hand"
    item("llama.cpp (lokale AI)", "app/lib/*/libllm.so", commit[:12], tag, osv({"commit": commit}), opm,
         verouderd=achter is None or achter > 1500)

    # Android-bouwgereedschap (komt niet zelf in de app, maar bepaalt wel waarmee hij gebouwd wordt)
    huidig = lees(*BUILDTOOLS)
    google = None
    try:
        xml = http_text("https://dl.google.com/android/repository/repository2-3.xml")
        bt = [v for v in re.findall(r'path="build-tools;([0-9.]+)"', xml)]
        google = max(bt, key=versie_tuple) if bt else None
        platforms = [int(x) for x in re.findall(r'path="platforms;android-(\d+)"', xml)]
        nieuwste_api = max(platforms) if platforms else None
    except Exception as e:
        nieuwste_api = None
        item("Android-gegevens van Google", "dl.google.com", "-", None, (), f"niet op te halen ({e}); controleer met de hand",
             verouderd=True)
    try:
        ide = http_json("https://api.github.com/repos/AndroidIDEOfficial/androidide-tools/releases/latest",
                        github=True)["tag_name"].lstrip("v")
    except Exception:
        ide = "?"
    if google:
        item("Android build-tools (d8, apksigner, zipalign)", "setup-tools.sh", huidig, google, (),
             f"nieuwste bij Google {google}; de bron androidide-tools heeft {ide}. Bijwerken: setup-tools.sh naar "
             f"Google's build-tools laten wijzen of een nieuwere androidide-release kiezen")
    platform = lees(*PLATFORM)
    target = lees(*TARGET)
    if nieuwste_api:
        item("Android-platform (android.jar)", "setup-tools.sh", platform, str(nieuwste_api), (),
             "android.jar komt uit Sable/android-platforms; kijk of die map daar al is")
        item("targetSdk", "build.sh", target, str(nieuwste_api), (),
             "Google Play eist elk jaar een nieuwere targetSdk; testen op gedragswijzigingen van die Android-versie")

    # aapt2 (uit het npm-pakket aaptjs3)
    huidig = lees(*AAPT2)
    item("aapt2 (aaptjs3)", "setup-tools.sh", huidig, npm_nieuwste("aaptjs3"))

    # zig (compiler voor whisper.cpp en llama.cpp; vastgezet in de bouwscripts)
    huidig = lees(*ZIG)
    item("zig (bouwen whisper/llama)", "tools/whisper-build.sh, tools/llm-build.sh", huidig,
         http_json("https://pypi.org/pypi/ziglang/json")["info"]["version"], (),
         "nieuwe zig: whisper en llama opnieuw bouwen en testen (tools/llm/test.sh, qemu)")

    # Node en Java in de workflows
    try:
        sched = http_json("https://raw.githubusercontent.com/nodejs/Release/main/schedule.json")
        vandaag = time.strftime("%Y-%m-%d")
        lts = [int(k.lstrip("v")) for k, v in sched.items()
               if "lts" in v and v["lts"] <= vandaag < v["end"] and k.lstrip("v").isdigit()]
        nieuwste_lts = max(lts) if lts else None
        for major in sorted(set(workflow_versies(r"node-version:\s*'?(\d+)"))):
            einde = sched.get(f"v{major}", {}).get("end", "?")
            item(f"Node {major} (workflows)", ".github/workflows", str(major), str(nieuwste_lts), (),
                 f"ondersteund tot {einde}" + ("; NIET MEER ONDERSTEUND" if einde != "?" and einde <= vandaag else ""),
                 verouderd=(einde != "?" and einde <= vandaag) or (nieuwste_lts is not None and major < nieuwste_lts))
    except Exception as e:
        item("Node (workflows)", ".github/workflows", "?", None, (), f"niet te controleren ({e})", verouderd=True)
    try:
        lts = http_json("https://api.adoptium.net/v3/info/available_releases")["most_recent_lts"]
        for major in sorted(set(workflow_versies(r"java-version:\s*'?(\d+)"))):
            item(f"Java {major} (workflows)", ".github/workflows", str(major), str(lts), (),
                 "bouwen gebeurt met -source/-target 8 (Android); een nieuwere JDK alleen voor het gereedschap",
                 verouderd=major < lts)
    except Exception as e:
        item("Java (workflows)", ".github/workflows", "?", None, (), f"niet te controleren ({e})", verouderd=True)

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
    if "--bestanden" in sys.argv:  # alleen de versies uit de bestanden lezen, zonder netwerk
        try:
            for naam, (pad, patroon) in ALLE.items():
                print(f"{naam}: {lees(pad, patroon)}")
        except Exception as e:
            print(f"Versie niet te lezen: {e}. Pas de schrijfwijze of het patroon in dit script aan.", file=sys.stderr)
            sys.exit(1)
        sys.exit(0)
    try:
        sys.exit(main())
    except Exception as e:
        print(f"Controle mislukt: {e}", file=sys.stderr)
        sys.exit(1)
