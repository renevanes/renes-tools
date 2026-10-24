#!/usr/bin/env python3
"""Controleert de meegeleverde bibliotheken en vastgezette versies op nieuwere versies en bekende kwetsbaarheden.

Dependabot kent deze niet (geen package.json of Gradle), dus dit script doet het:
  - web/vendor/leaflet.js en web/vendor/jsQR.js   → npm-register + OSV (kwetsbaarheden)
  - app/lib/*/libwhisper*.so (whisper.cpp-commit)  → nieuwste release + OSV
  - app/lib/*/libllm.so (llama.cpp-commit)          → nieuwste release + OSV
  - Playwright-versie in publiceren.yml            → npm-register
  - Android build-tools, android.jar/targetSdk, aapt2, zig, Node en Java → nieuwste versies (Google, npm, PyPI, Node-schema, Adoptium)
  - AI-modellen in LocalAi.java (Hugging Face)     → nieuwere modellen van dezelfde maker in dezelfde grootte

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
BUILDTOOLS = ("setup-tools.sh", r"(?m)^BUILD_TOOLS=(\d+\.\d+\.\d+)$")
AAPT2 = ("setup-tools.sh", r"aaptjs3@(\d+\.\d+\.\d+)")
PLATFORM = ("setup-tools.sh", r"sparse-checkout set android-(\d+)")
TARGET = ("build.sh", r"--target-sdk-version (\d+)")
ZIG = ("tools/whisper-build.sh", r'ZIG_VERSION="(\d+\.\d+\.\d+)"')
# De AI-modellen: new Model("id", "label", "desc", "repo", "commit", "bestand", …)
MODELLEN = ("app/src/nl/rene/tools/LocalAi.java",
            r'new Model\("(\w+)",\s*"[^"]*",\s*"[^"]*",\s*"([^"]+)",\s*"([0-9a-f]{40})",\s*"([^"]+\.gguf)"')
ALLE = {"Leaflet": LEAFLET, "jsQR": JSQR, "Playwright": PLAYWRIGHT, "whisper.cpp": WHISPER,
        "llama.cpp": LLAMA, "build-tools": BUILDTOOLS, "aapt2": AAPT2, "android-platform": PLATFORM,
        "targetSdk": TARGET, "zig": ZIG, "zig (llm)": ("tools/llm-build.sh", ZIG[1])}


def modellen():
    tekst = (ROOT / MODELLEN[0]).read_text(errors="replace")
    gevonden = re.findall(MODELLEN[1], tekst)
    if not gevonden:
        raise RuntimeError(f"geen modellen gevonden in {MODELLEN[0]}")
    return gevonden  # [(id, repo, commit, bestand)]


def model_soort(naam):
    """Grootte en soort uit een naam: ('4b', False) of ('0.6b', True) voor een zoekmodel (embedding)."""
    m = re.search(r"(\d+(?:\.\d+)?)[bB](?![a-zA-Z])", naam)
    return (m.group(1) + "b" if m else ""), ("embed" in naam.lower())


def familie(naam):
    """'Qwen3-4B-GGUF' → (3.0, 'qwen'); 'Qwen3.5-4B' → (3.5, 'qwen'). Hoger = nieuwer."""
    m = re.match(r"([A-Za-z]+)[-_]?(\d+(?:\.\d+)?)", naam.split("/")[-1].replace("Qwen_", ""))
    return (float(m.group(2)), m.group(1).lower()) if m else (0.0, "")


def nieuwere_modellen():
    """Per model: nieuwere GGUF-modellen van dezelfde maker in dezelfde grootte (en soort) op Hugging Face."""
    uit = []
    for mid, repo, commit, bestand in modellen():
        grootte, embed = model_soort(bestand)
        nu_versie, merk = familie(bestand)
        maker = "Qwen" if merk == "qwen" else repo.split("/")[0]
        try:
            lijst = http_json(f"https://huggingface.co/api/models?author={maker}&search=GGUF&sort=createdAt&direction=-1&limit=100")
            if merk:  # ook de bekende omzetters naar GGUF (de maker zelf doet dat niet altijd)
                for omzetter in ("bartowski", "unsloth"):
                    lijst += http_json(f"https://huggingface.co/api/models?author={omzetter}&search={merk}&sort=createdAt&direction=-1&limit=100")
            info = http_json(f"https://huggingface.co/api/models/{repo}")
        except Exception as e:
            uit.append((mid, repo, commit, None, f"Hugging Face niet bereikbaar ({e}); controleer met de hand"))
            continue
        beter = []
        for x in lijst:
            naam = x.get("id", "")
            g, e = model_soort(naam)
            v, f = familie(naam)
            # Ongeveer even groot (bijv. 1.7B → 2B), dezelfde soort en maker, en een hogere versie
            past = g and grootte and 0.66 <= float(g[:-1]) / float(grootte[:-1]) <= 1.5
            if past and e == embed and f == merk and v > nu_versie and "gguf" in naam.lower() and naam not in beter:
                beter.append(naam)
        beter = [x for x in beter if "-mtp" not in x.lower()] + [x for x in beter if "-mtp" in x.lower()]  # MTP = extra koppen
        opm = ""
        if beter:
            vast = vastzetten(beter[0], bestand)
            opm = ("nieuwer: " + ", ".join(beter[:4]) + ". Overnemen = een nieuw Model met een eigen id in LocalAi.java "
                   f"met .replaces(\"{mid}\", \"waarom beter\"), vaste commit en SHA-256 (nooit het bestand van \"{mid}\" zelf wijzigen)"
                   + ("; een ander zoekmodel vraagt ook code in SmartSearch (LocalAi.EMBED, nieuwe index)" if embed else "")
                   + ". Eerst zelf proberen (Nederlands, snelheid op een telefoon met 6-8 GB)"
                   + (". Vastzetten: " + vast if vast else ""))
        elif info.get("sha") and info["sha"] != commit:
            opm = f"{repo} is bijgewerkt sinds de vaste versie (meestal alleen tekst); de app blijft de vaste versie gebruiken"
        uit.append((mid, repo, commit, beter[0] if beter else None, opm))
    return uit


def vastzetten(repo, huidig_bestand):
    """Gegevens om een kandidaat vast te zetten: commit, bestand (zelfde kwantisatie), grootte en SHA-256 (LFS)."""
    try:
        m = re.search(r"-([A-Z0-9]+(?:_[A-Z0-9]+)*)\.gguf$", huidig_bestand)
        quant = m.group(1) if m else ""
        sha = http_json(f"https://huggingface.co/api/models/{repo}")["sha"]
        boom = http_json(f"https://huggingface.co/api/models/{repo}/tree/{sha}")
        for f in boom:
            pad = f.get("path", "")
            if "/" not in pad and pad.endswith(f"-{quant}.gguf") and f.get("lfs"):
                return f'`{repo}` commit `{sha}` bestand `{pad}` grootte {f["lfs"]["size"]} sha256 `{f["lfs"]["oid"]}`'
        return f"(geen {quant}-bestand in {repo})"
    except Exception as e:
        return f"(gegevens niet op te halen: {e})"


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
        bt_zip = ""
        if google:
            blok = xml.split(f'path="build-tools;{google}"', 1)[1].split("</remotePackage>", 1)[0]
            for arch in re.findall(r"<archive>(.*?)</archive>", blok, re.S):
                if "<host-os>linux</host-os>" in arch:
                    u = re.search(r"<url>([^<]+)</url>", arch); c = re.search(r'<checksum type="sha1">([0-9a-f]{40})', arch)
                    z = re.search(r"<size>(\d+)</size>", arch)
                    if u and c:
                        bt_zip = (f" Vastzetten: https://dl.google.com/android/repository/{u.group(1)} sha1 `{c.group(1)}`"
                                  + (f" grootte {z.group(1)}" if z else ""))
        platforms = [int(x) for x in re.findall(r'path="platforms;android-(\d+)"', xml)]
        nieuwste_api = max(platforms) if platforms else None
    except Exception as e:
        nieuwste_api = None
        item("Android-gegevens van Google", "dl.google.com", "-", None, (), f"niet op te halen ({e}); controleer met de hand",
             verouderd=True)
    if google:
        item("Android build-tools (d8, apksigner, zipalign)", "setup-tools.sh", huidig, google, (),
             f"nieuwste bij Google {google}. Bijwerken: BUILD_TOOLS, BUILD_TOOLS_URL, BUILD_TOOLS_SHA1 en "
             f"BUILD_TOOLS_SIZE in setup-tools.sh; een andere d8 vraagt een test op een telefoon." + bt_zip)
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

    # AI-modellen: nieuwere versies in dezelfde grootte (de app downloadt nooit vanzelf iets nieuws; een agent of
    # Rene beslist of een nieuw model meekomt, met een melding in de app)
    for mid, repo, commit, beter, opm in nieuwere_modellen():
        # Niet bereikbaar: ook als iets om te doen melden (met de hand nakijken), niet stil "bij"
        item(f"AI-model {mid}", repo, commit[:12], beter or commit[:12], (), opm, verouderd=beter is not None or "niet bereikbaar" in opm)

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
            for mid, repo, commit, bestand in modellen():
                print(f"AI-model {mid}: {repo}@{commit[:12]} {bestand} {model_soort(bestand)} {familie(bestand)}")
        except Exception as e:
            print(f"Versie niet te lezen: {e}. Pas de schrijfwijze of het patroon in dit script aan.", file=sys.stderr)
            sys.exit(1)
        sys.exit(0)
    try:
        sys.exit(main())
    except Exception as e:
        print(f"Controle mislukt: {e}", file=sys.stderr)
        sys.exit(1)
