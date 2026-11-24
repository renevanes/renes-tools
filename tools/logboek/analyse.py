#!/usr/bin/env python3
"""Analyse van het foutlogboek (map logboek/ van Rene's privé statistiekrepository, zie Logboek.java).

Leest alle logboekbestanden (<tijd UTC>-<installatie>.txt), haalt de meldingen eruit, voegt gelijke meldingen samen
(cijfers tellen niet mee) en zet ze op een rij: wat nieuw is, wat in de nieuwste versie nog voorkomt en wat stil werd.
Voor het wekelijkse onderhoud en Claude op verzoek van Rene (AGENTS.md, "Foutlogboek nalopen").

  python3 tools/logboek/analyse.py <map logboek/> [--sinds 2026-11-24T142715Z] [--openbaar] [--json]

--sinds    alleen bestanden ná dit bestand (tijd uit de bestandsnaam, UTC) tellen als nieuw
--openbaar alleen soort, kenmerk, aantallen, versies en stand: geschikt voor een openbaar issue (geen meldingstekst)
--json     alles als JSON

Dit script bevat geen gegevens; de logboekbestanden zelf blijven in de privé repository.
"""
import argparse
import hashlib
import json
import os
import re
import sys

KOP = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) \[([^\]\n]{1,40})\] v(\S+) \((\d+)\)$", re.M)
BESTAND = re.compile(r"^(\d{4}-\d\d-\d\dT\d{6}Z)-([A-Za-z0-9_-]{1,40})\.txt$")
# Zelfde woorden als Logboek.MIS (Java): een melding over iets wat misging
MIS = re.compile(r"(?i)mislukt|niet gelukt|lukt(?:e)? niet|niet herkend|niet te (?:openen|lezen)|niet op te halen"
                 r"|(?<!\b0 )fout|exception|error|vastgelopen|liep vast|kapot|geweigerd|weigert|onleesbaar|beschadigd|ontbreekt|verlopen|teruggedraaid")
STAPEL = re.compile(r"^\s*at (nl\.rene\.tools\.[\w$.]+)\(([\w$]+\.java):(\d+)\)", re.M)
SOORT_OK = re.compile(r"^[A-Z][A-Z0-9_]{0,23}$")
PLEK_OK = re.compile(r"^\w{1,80} @ nl\.rene\.tools\.[\w$.]{1,160}\(\w{1,80}\.java\)$")
VERSIE_OK = re.compile(r"^\d{1,4}(?:\.\d{1,5}){0,3}$")
SINDS_OK = re.compile(r"^\d{4}-\d\d-\d\d(T\d{6}Z)?$")
UITZONDERING = re.compile(r"\b((?:[a-z]\w*\.)+[A-Z]\w*(?:Exception|Error)|[A-Z]\w*(?:Exception|Error))\b")


# Niet meer gezien in zoveel dagen (gerekend vanaf de nieuwste melding) = stil: misschien opgelost
RECENT_DAGEN = 3


def dagen_tussen(a, b):
    """Dagen tussen twee tijden "JJJJ-MM-DD UU:MM:SS" (telefoontijd; alleen voor de stand, dus zonder tijdzone)."""
    import datetime
    f = "%Y-%m-%d %H:%M:%S"
    try:
        return (datetime.datetime.strptime(b, f) - datetime.datetime.strptime(a, f)).total_seconds() / 86400.0
    except ValueError:
        return 0.0


def probleem(soort, tekst):
    if soort in ("CRASH", "DRAAD"):
        return True
    return bool(MIS.search((tekst or "")[:2000]))


def sleutel(soort, tekst):
    """Gelijke meldingen samen: de eerste regel zonder cijfers; bij een vastloper de soort fout en de eerste eigen plek."""
    eerste = (tekst or "").strip().split("\n", 1)[0]
    if soort in ("CRASH", "DRAAD") or STAPEL.search(tekst or ""):
        u = UITZONDERING.search(tekst or "")
        p = STAPEL.search(tekst or "")
        plek = "%s(%s)" % (p.group(1), p.group(2)) if p else "?"
        return "%s @ %s" % (u.group(1).rsplit(".", 1)[-1] if u else "?", plek)
    k = re.sub(r"\d+", "#", eerste)
    k = re.sub(r"\s+", " ", k).strip()
    return k[:160]


def kenmerk(soort, s):
    return hashlib.sha256(("%s|%s" % (soort, s)).encode("utf-8")).hexdigest()[:8]


def lees_bestand(pad):
    naam = os.path.basename(pad)
    m = BESTAND.match(naam)
    if not m:
        return None
    with open(pad, encoding="utf-8", errors="replace") as f:
        tekst = f.read()
    kop = {"bestand": naam, "tijd": m.group(1), "installatie": m.group(2)}
    meldingen = []
    koppen = list(KOP.finditer(tekst))
    for i, k in enumerate(koppen):
        einde = koppen[i + 1].start() if i + 1 < len(koppen) else len(tekst)
        inhoud = tekst[k.end():einde].strip()
        meldingen.append({"tijd": k.group(1), "soort": k.group(2), "versie": k.group(3), "code": int(k.group(4)),
                          "tekst": inhoud, "bestand": naam, "bestandTijd": m.group(1), "installatie": m.group(2)})
    return kop, meldingen


def analyseer(map_, sinds=""):
    bestanden = sorted(f for f in os.listdir(map_) if BESTAND.match(f) and os.path.isfile(os.path.join(map_, f)))
    alle, gezien = [], set()
    for b in bestanden:
        r = lees_bestand(os.path.join(map_, b))
        if not r:
            continue
        for x in r[1]:
            dubbel = (x["installatie"], x["tijd"], x["soort"], x["tekst"])
            if dubbel in gezien:  # dezelfde melding twee keer verstuurd: één keer tellen
                continue
            gezien.add(dubbel)
            alle.append(x)
    nieuwste = max((x["code"] for x in alle), default=0)
    nieuwste_versie = next((x["versie"] for x in alle if x["code"] == nieuwste), "")
    groepen = {}
    for x in alle:
        s = sleutel(x["soort"], x["tekst"])
        g = groepen.setdefault((x["soort"], s), {
            "soort": x["soort"], "melding": s, "kenmerk": kenmerk(x["soort"], s), "probleem": False,
            "aantal": 0, "nieuw": 0, "eerst": x["tijd"], "laatst": x["tijd"], "eersteVersie": x["versie"],
            "laatsteVersie": x["versie"], "laatsteCode": x["code"], "installaties": set(), "dagen": set(), "voorbeeld": x["tekst"][:600]})
        g["aantal"] += 1
        g["probleem"] = g["probleem"] or probleem(x["soort"], x["tekst"])
        if sinds and x["bestandTijd"] > sinds:
            g["nieuw"] += 1
        if x["tijd"] < g["eerst"]:
            g["eerst"], g["eersteVersie"] = x["tijd"], x["versie"]
        if x["tijd"] >= g["laatst"]:
            g["laatst"], g["laatsteVersie"], g["laatsteCode"], g["voorbeeld"] = x["tijd"], x["versie"], x["code"], x["tekst"][:600]
        g["installaties"].add(x["installatie"])
        g["dagen"].add(x["tijd"][:10])
    uit = []
    tot = max((x["tijd"] for x in alle), default="")
    for g in groepen.values():
        g["installaties"] = len(g["installaties"])
        g["dagen"] = len(g["dagen"])
        recent = bool(tot) and dagen_tussen(g["laatst"], tot) <= RECENT_DAGEN
        if sinds and g["nieuw"] == g["aantal"]:
            g["stand"] = "nieuw"
        elif recent:
            g["stand"] = "aanhoudend" if g["aantal"] >= 3 else "recent"
        else:
            g["stand"] = "stil sinds " + g["laatst"][:10] + " (" + g["laatsteVersie"] + ")"
        uit.append(g)
    volgorde = {"nieuw": 0, "aanhoudend": 1, "recent": 2}
    uit.sort(key=lambda g: (not g["probleem"], volgorde.get(g["stand"], 3), -g["nieuw"], -g["aantal"], g["soort"], g["melding"]))
    return {
        "bestanden": len(bestanden), "meldingen": len(alle),
        "nieuweBestanden": sum(1 for b in bestanden if sinds and BESTAND.match(b).group(1) > sinds),
        "laatsteBestand": BESTAND.match(bestanden[-1]).group(1) if bestanden else "",
        "van": min((x["tijd"] for x in alle), default=""), "tot": max((x["tijd"] for x in alle), default=""),
        "nieuwsteVersie": nieuwste_versie, "nieuwsteCode": nieuwste,
        "installaties": len({x["installatie"] for x in alle}), "groepen": uit, "sinds": sinds,
    }


def openbaar_maken(r):
    """Alleen wat in een openbaar issue mag (AGENTS.md): soort, kenmerk, aantallen, versies, datums en de plek in de code.
    Geen meldingstekst, geen tijden op de minuut, en een soort die geen gewone logsoort is wordt ANDERS."""
    def versie(v):
        return v if VERSIE_OK.match(v or "") else "?"
    r["van"], r["tot"] = r["van"][:10], r["tot"][:10]
    r["nieuwsteVersie"] = versie(r["nieuwsteVersie"])
    for g in r["groepen"]:
        g["eersteVersie"], g["laatsteVersie"] = versie(g["eersteVersie"]), versie(g["laatsteVersie"])
        if PLEK_OK.match(g["melding"]) and g["soort"] in ("CRASH", "DRAAD"):
            g["plek"] = g["melding"]
        if not SOORT_OK.match(g["soort"]):
            g["soort"] = "ANDERS"
        g["eerst"], g["laatst"] = g["eerst"][:10], g["laatst"][:10]
        if g["stand"].startswith("stil sinds "):
            g["stand"] = "stil sinds " + g["laatst"] + " (" + g["laatsteVersie"] + ")"
        g.pop("melding", None)
        g.pop("voorbeeld", None)
    return r


def md_cel(t):
    return t.replace("|", "\\|").replace("\n", " ")


def markdown(r, openbaar=False):
    regels = ["# Logboek-analyse", "",
              "%d bestanden, %d meldingen van %s tot %s (telefoontijd), %d installatie(s); nieuwste versie %s."
              % (r["bestanden"], r["meldingen"], r["van"] or "-", r["tot"] or "-", r["installaties"], r["nieuwsteVersie"] or "-")]
    if r["sinds"]:
        regels.append("Nieuw sinds %s: %d bestand(en); laatste bestand %s." % (r["sinds"], r["nieuweBestanden"], r["laatsteBestand"] or "-"))
    else:
        regels.append("Laatste bestand: %s." % (r["laatsteBestand"] or "-"))
    for titel, filt in (("Problemen", lambda g: g["probleem"]), ("Gewone meldingen", lambda g: not g["probleem"])):
        groep = [g for g in r["groepen"] if filt(g)]
        regels += ["", "## %s (%d)" % (titel, len(groep)), ""]
        if not groep:
            regels.append("Geen.")
            continue
        if openbaar:
            regels += ["| Stand | Soort | Kenmerk | Plek | Nieuw | Totaal | Dagen | Versies |", "|---|---|---|---|---|---|---|---|"]
            for g in groep[:40]:
                regels.append("| %s | %s | %s | %s | %d | %d | %d | %s–%s |" % (g["stand"], g["soort"], g["kenmerk"], g.get("plek", ""), g["nieuw"],
                                                                          g["aantal"], g["dagen"], g["eersteVersie"], g["laatsteVersie"]))
        else:
            regels += ["| Stand | Soort | Melding | Nieuw | Totaal | Dagen | Eerst | Laatst | Versies |", "|---|---|---|---|---|---|---|---|---|"]
            for g in groep[:60]:
                regels.append("| %s | %s | %s | %d | %d | %d | %s | %s | %s–%s |" % (
                    g["stand"], md_cel(g["soort"]), md_cel(g["melding"]), g["nieuw"], g["aantal"], g["dagen"],
                    g["eerst"][:16], g["laatst"][:16], g["eersteVersie"], g["laatsteVersie"]))
        if len(groep) > (40 if openbaar else 60):
            regels.append("… en nog %d" % (len(groep) - (40 if openbaar else 60)))
    return "\n".join(regels) + "\n"


def main(argv=None):
    a = argparse.ArgumentParser(description="Analyse van het foutlogboek (map logboek/)")
    a.add_argument("map")
    a.add_argument("--sinds", default="", help="tijd uit de bestandsnaam (UTC), bijv. 2026-11-24T142715Z of 2026-11-24")
    a.add_argument("--openbaar", action="store_true", help="geen meldingstekst: alleen soort, kenmerk en aantallen")
    a.add_argument("--json", action="store_true")
    o = a.parse_args(argv)
    if not os.path.isdir(o.map):
        print("Map niet gevonden: " + o.map, file=sys.stderr)
        return 2
    if o.sinds and not SINDS_OK.match(o.sinds):
        print("--sinds: gebruik de tijd uit een bestandsnaam (2026-11-24T142715Z) of een datum (2026-11-24)", file=sys.stderr)
        return 2
    r = analyseer(o.map, o.sinds)
    if o.openbaar:
        r = openbaar_maken(r)
    if o.json:
        print(json.dumps(r, ensure_ascii=False, indent=1))
    else:
        sys.stdout.write(markdown(r, o.openbaar))
    return 0


if __name__ == "__main__":
    sys.exit(main())
