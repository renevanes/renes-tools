#!/usr/bin/env python3
"""Maakt de momentopname van toiletten en douches in Nederland uit OpenStreetMap.

  ophalen.py --pbf netherlands-latest.osm.pbf --uit map/      wekelijks (workflow Toiletten-gegevens; osmium-tool nodig)
  ophalen.py --overpass --uit map/                            om te ontwikkelen (zwaar voor Overpass: niet vaak doen)
  ophalen.py --voorbeeld tools/toiletten/voorbeeld.json [--uit map/]
                                                              test zonder netwerk: controleert elke plek met "verwacht"

Uitvoer: toiletten-nl.json.gz ({"formaat", "datum", "licentie", "plekken": [[id, lat, lon, soort, bits, naam, uren], ...]})
en meta.json (datum, sha256, grootte, aantal). De app (ToilettenScan.java) leest precies dit formaat: verander je het,
verhoog dan "formaat" in regels.json en in Toiletten.FORMAAT.
"""
import argparse
import datetime
import gzip
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

HIER = os.path.dirname(os.path.abspath(__file__))
UA = "renes-tools-toiletten/1 (+https://github.com/renevanes/renes-tools)"
LICENTIE = "ODbL 1.0, © OpenStreetMap-bijdragers"
LICENTIE_URL = "https://www.openstreetmap.org/copyright"
NL = (50.5, 54.0, 2.5, 7.5)  # zuid, noord, west, oost
MAX_TEKST = 120


def lees_regels():
    with open(os.path.join(HIER, "regels.json"), encoding="utf-8") as f:
        return json.load(f)


def kort(s, n=MAX_TEKST):
    s = " ".join(str(s or "").split())
    return s if len(s) <= n else s[: n - 1].rstrip() + "…"


def tag_past(tags, kv):
    k, v = kv.split("=", 1)
    return tags.get(k) == v


def soort_van(tags, R):
    for s in R["soorten"]:
        if tag_past(tags, s["tag"]):
            return s["soort"]
    return None


def onbemand(tags, R):
    o = R["onbemand"]
    if any(tag_past(tags, kv) for kv in o["tags"]):
        return True
    for veld in ("brand", "name", "operator"):
        w = (tags.get(veld) or "").strip().lower()
        if w and any(w == m.lower() or w.startswith(m.lower() + " ") for m in o["merken"]):
            return True
    return False


def meestal_regel(tags, soort, R):
    for r in R["meestal"]:
        if r["soort"] != soort:
            continue
        if "tag" in r and not tag_past(tags, r["tag"]):
            continue
        if "merken" in r:
            merk = (tags.get("brand") or tags.get("name") or "").strip().lower()
            if not any(merk == m.lower() or merk.startswith(m.lower() + " ") for m in r["merken"]):
                continue
        return r
    return None


def eigenschappen(tags, B, voor_toilet):
    """Bits voor gratis/betaald, rolstoel, verschoontafel en 24/7. Bij een plek die geen toilet zelf is
    (tankstation met toilets=yes) gelden eerst de toilets:-tags."""
    def tg(k):
        return (tags.get("toilets:" + k) if not voor_toilet else None) or tags.get(k) or ""
    b = 0
    fee = tg("fee")
    if fee == "no":
        b |= B["gratis"]
    elif fee and fee not in ("donation", "unknown"):
        b |= B["betaald"]
    rol = tg("wheelchair")
    if rol in ("yes", "designated"):
        b |= B["rolstoel"]
    elif rol == "limited":
        b |= B["rolstoelBeperkt"]
    if tg("changing_table") == "yes":
        b |= B["verschoon"]
    if tg("opening_hours").strip() == "24/7":
        b |= B["altijd"]
    return b


def plek(el, R):
    """Eén OSM-object -> [id, lat, lon, soort, bits, naam, uren], of None als het er niet in hoort."""
    tags = el.get("tags") or {}
    lat, lon = el.get("lat"), el.get("lon")
    if lat is None or lon is None or not (NL[0] <= lat <= NL[1] and NL[2] <= lon <= NL[3]):
        return None
    B = R["bits"]
    niet = set(R["nietOpenbaar"])
    access = tags.get("access", "")
    soort = soort_van(tags, R)
    bits = 0
    uren = ""
    if tags.get("amenity") in ("toilets", "shower"):
        if access in niet:
            return None
        bits |= B["toilet"] if tags["amenity"] == "toilets" else B["douche"]
        if tags.get("toilets") == "yes":
            bits |= B["toilet"]
        if tags.get("shower") in ("yes", "hot", "cold"):
            bits |= B["douche"]
        if access == "customers":
            bits |= B["klanten"]
        if tags.get("toilets:position") == "urinal":
            bits |= B["urinoir"]
        bits |= eigenschappen(tags, B, True)
        uren = tags.get("opening_hours", "")
    else:
        if access in niet:
            return None
        tacc = tags.get("toilets:access", "")
        heeft_t = tags.get("toilets") == "yes" and tacc not in niet
        heeft_d = tags.get("shower") in ("yes", "hot", "cold") and tags.get("shower:access", "") not in niet
        if heeft_t or heeft_d:
            if heeft_t:
                bits |= B["toilet"]
            if heeft_d:
                bits |= B["douche"]
            if tacc == "customers" or access == "customers":
                bits |= B["klanten"]
            bits |= eigenschappen(tags, B, False)
            uren = tags.get("toilets:opening_hours") or tags.get("opening_hours", "")
            soort = soort or "overig"
        else:
            if tags.get("toilets") == "no" or soort is None:
                return None
            if soort == "tank" and onbemand(tags, R):
                return None
            r = meestal_regel(tags, soort, R)
            if not r:
                return None
            bits |= B["toilet"] | B["meestal"]
            if r.get("douche"):
                bits |= B["douche"]
            if r.get("klanten"):
                bits |= B["klanten"]
            uren = tags.get("opening_hours", "")
            if uren.strip() == "24/7":
                bits |= B["altijd"]
    naam = tags.get("name") or (tags.get("brand") if soort in ("tank", "horeca", "winkel") else "") or ""
    return [el["id"], round(lat, 5), round(lon, 5), soort, bits, kort(naam, 80), kort(uren)]


def maak(elementen, R, datum):
    gezien, plekken = set(), []
    for el in elementen:
        p = plek(el, R)
        if p and p[0] not in gezien:
            gezien.add(p[0])
            plekken.append(p)
    plekken.sort(key=lambda p: p[0])
    return {"formaat": R["formaat"], "datum": datum, "bron": "OpenStreetMap", "licentie": LICENTIE,
            "licentieUrl": LICENTIE_URL, "plekken": plekken}


def schrijf(data, uit):
    os.makedirs(uit, exist_ok=True)
    ruw = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0, compresslevel=9) as g:
        g.write(ruw)
    gz = buf.getvalue()
    with open(os.path.join(uit, "toiletten-nl.json.gz"), "wb") as f:
        f.write(gz)
    soorten = {}
    for p in data["plekken"]:
        soorten[p[3]] = soorten.get(p[3], 0) + 1
    meta = {"formaat": data["formaat"], "datum": data["datum"], "bron": data["bron"], "licentie": LICENTIE,
            "licentieUrl": LICENTIE_URL, "bestand": "toiletten-nl.json.gz", "grootte": len(gz),
            "sha256": hashlib.sha256(gz).hexdigest(), "aantal": len(data["plekken"]), "soorten": soorten}
    with open(os.path.join(uit, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")
    return meta


def midden(geom):
    t, c = geom.get("type"), geom.get("coordinates")
    if t == "Point":
        return c[1], c[0]
    if t == "LineString":
        pts = c
    elif t == "Polygon":
        pts = c[0][:-1] or c[0]
    elif t == "MultiPolygon":
        pts = max((p[0] for p in c), key=len)[:-1]
    else:
        return None, None
    if not pts:
        return None, None
    return sum(p[1] for p in pts) / len(pts), sum(p[0] for p in pts) / len(pts)


def osm_id(i):
    """osmium geeft vlakken een eigen id (a = 2 x weg, of 2 x relatie + 1); de app linkt naar het echte object."""
    if i.startswith("a") and i[1:].isdigit():
        n = int(i[1:])
        return "w%d" % (n // 2) if n % 2 == 0 else "r%d" % ((n - 1) // 2)
    return i


def uit_pbf(pbf, R):
    with tempfile.TemporaryDirectory() as tmp:
        klein = os.path.join(tmp, "filter.osm.pbf")
        subprocess.run(["osmium", "tags-filter", "--overwrite", "-o", klein, pbf] + R["osmiumFilter"], check=True)
        seq = os.path.join(tmp, "uit.geojsonseq")
        subprocess.run(["osmium", "export", "--overwrite", "-f", "geojsonseq", "--add-unique-id=type_id",
                        "--geometry-types=point,linestring,polygon", "-o", seq, klein], check=True)
        datum = ""
        try:
            r = subprocess.run(["osmium", "fileinfo", "-g", "header.option.osmosis_replication_timestamp", pbf],
                               check=True, capture_output=True, text=True)
            datum = r.stdout.strip()[:10]
        except (subprocess.CalledProcessError, OSError):
            pass
        elementen = []
        with open(seq, encoding="utf-8") as f:
            for regel in f:
                regel = regel.strip().lstrip("\x1e")
                if not regel:
                    continue
                ft = json.loads(regel)
                lat, lon = midden(ft.get("geometry") or {})
                elementen.append({"id": osm_id(str(ft.get("id"))), "lat": lat, "lon": lon, "tags": ft.get("properties") or {}})
    return elementen, datum


def uit_overpass(R):
    q = '[out:json][timeout:900];area["ISO3166-1"="NL"][admin_level=2]->.nl;(' + "".join(R["overpass"]) + ");out center tags;"
    data = urllib.parse.urlencode({"data": q}).encode()
    for poging in range(3):
        try:
            req = urllib.request.Request("https://overpass-api.de/api/interpreter", data=data, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=1000) as r:
                js = json.load(r)
            break
        except urllib.error.HTTPError as e:
            if e.code not in (429, 502, 503, 504) or poging == 2:
                raise
            time.sleep(60)
    elementen = []
    for e in js.get("elements", []):
        c = e if e.get("type") == "node" else (e.get("center") or {})
        elementen.append({"id": e["type"][0] + str(e["id"]), "lat": c.get("lat"), "lon": c.get("lon"), "tags": e.get("tags") or {}})
    return elementen, (js.get("osm3s") or {}).get("timestamp_osm_base", "")[:10]


def controleer(elementen, R):
    B = R["bits"]
    fout = 0
    for el in elementen:
        if "verwacht" not in el:
            continue
        p, v = plek(el, R), el["verwacht"]
        if v is None:
            ok = p is None
        else:
            want = sum(B[b] for b in v["bits"])
            ok = p is not None and p[3] == v["soort"] and p[4] == want and p[5] == v.get("naam", p[5])
        print(("ok   " if ok else "FOUT ") + el["id"] + " " + json.dumps(p, ensure_ascii=False))
        fout += not ok
    return fout


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    bron = ap.add_mutually_exclusive_group(required=True)
    bron.add_argument("--pbf")
    bron.add_argument("--overpass", action="store_true")
    bron.add_argument("--voorbeeld")
    ap.add_argument("--uit")
    ap.add_argument("--min", type=int, default=None, help="minimaal aantal plekken (anders niet publiceren)")
    a = ap.parse_args()
    R = lees_regels()
    if a.voorbeeld:
        with open(a.voorbeeld, encoding="utf-8") as f:
            elementen = json.load(f)["elements"]
        datum = "2026-01-01"
        if controleer(elementen, R):
            sys.exit("Voorbeeld klopt niet met regels.json")
        minimum = a.min or 1
    elif a.pbf:
        elementen, datum = uit_pbf(a.pbf, R)
        minimum = 3000 if a.min is None else a.min
    else:
        elementen, datum = uit_overpass(R)
        minimum = 3000 if a.min is None else a.min
    data = maak(elementen, R, datum or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"))
    if len(data["plekken"]) < minimum:
        sys.exit("Te weinig plekken (%d < %d): niet gepubliceerd" % (len(data["plekken"]), minimum))
    if a.uit:
        meta = schrijf(data, a.uit)
        print(json.dumps(meta, ensure_ascii=False))
    else:
        print("%d plekken" % len(data["plekken"]))


if __name__ == "__main__":
    main()
