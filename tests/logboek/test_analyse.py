"""Tests voor tools/logboek/analyse.py, met verzonnen meldingen (nooit echte uit het logboek)."""
import importlib.util
import io
import json
import os
import re
import tempfile
import unittest
from contextlib import redirect_stdout

HIER = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("analyse", os.path.join(HIER, "..", "..", "tools", "logboek", "analyse.py"))
analyse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analyse)


def melding(tijd, soort, versie, code, tekst):
    return "%s [%s] v%s (%d)\n%s\n\n" % (tijd, soort, versie, code, tekst)


KOP = "Logboek Tools 1.2 (3), onderdeel 1\nToestel: Voorbeeld X · Android 16 (API 36)\nInstallatie: ab12cd34\n\n"


class AnalyseTest(unittest.TestCase):
    def setUp(self):
        self.map = tempfile.mkdtemp()
        self.schrijf("2026-11-20T100000Z-ab12cd34.txt",
                     melding("2026-11-20 11:00:00", "FACTUREN", "1.10", 11, "ophalen mislukt (website): tijd om")
                     + melding("2026-11-20 14:00:00", "FACTUREN", "1.10", 11, "ophalen mislukt (website): tijd om")
                     + melding("2026-11-20 15:00:00", "NIEUWS", "1.10", 11, "bijgewerkt: 4 van 5 bronnen")
                     + melding("2026-11-18 16:00:00", "TOILET", "1.10", 11, "scan mislukt: Exception"))
        self.schrijf("2026-11-22T100000Z-ab12cd34.txt",
                     melding("2026-11-22 11:00:00", "FACTUREN", "1.12", 13, "ophalen mislukt (website): tijd om")
                     + melding("2026-11-22 11:05:00", "NIEUWS", "1.12", 13, "bijgewerkt: 5 van 5 bronnen")
                     + melding("2026-11-22 11:10:00", "BACKUP", "1.12", 13, "backup-map gecontroleerd: 17 punten, 0 fouten")
                     + melding("2026-11-22 11:20:00", "DRAAD", "1.12", 13,
                               "thread voorbeeld\njava.lang.IllegalStateException: iets\n\tat android.os.Handler.x(Handler.java:1)\n"
                               "\tat nl.rene.tools.Voorbeeld.doe(Voorbeeld.java:42)\n\tat nl.rene.tools.Voorbeeld.run(Voorbeeld.java:7)")
                     # dezelfde melding nog een keer verstuurd: één keer tellen
                     + melding("2026-11-22 11:00:00", "FACTUREN", "1.12", 13, "ophalen mislukt (website): tijd om"))
        self.schrijf("nietlogboek.txt", "geen logboek")

    def schrijf(self, naam, inhoud):
        with open(os.path.join(self.map, naam), "w", encoding="utf-8") as f:
            f.write(KOP + inhoud)

    def groep(self, r, soort):
        return [g for g in r["groepen"] if g["soort"] == soort]

    def test_samenvoegen_en_stand(self):
        r = analyse.analyseer(self.map, "2026-11-21")
        self.assertEqual(r["bestanden"], 2)
        self.assertEqual(r["meldingen"], 8)
        self.assertEqual(r["nieuwsteVersie"], "1.12")
        fac = self.groep(r, "FACTUREN")
        self.assertEqual(len(fac), 1)
        self.assertEqual((fac[0]["aantal"], fac[0]["nieuw"], fac[0]["stand"]), (3, 1, "aanhoudend"))
        nieuws = self.groep(r, "NIEUWS")
        self.assertEqual(len(nieuws), 1, "cijfers tellen niet mee")
        self.assertFalse(nieuws[0]["probleem"])
        self.assertEqual(self.groep(r, "TOILET")[0]["stand"], "stil sinds 2026-11-18 (1.10)")
        self.assertEqual(nieuws[0]["stand"], "recent")
        draad = self.groep(r, "DRAAD")[0]
        self.assertEqual(draad["melding"], "IllegalStateException @ nl.rene.tools.Voorbeeld.doe(Voorbeeld.java)")
        self.assertEqual(draad["stand"], "nieuw")
        self.assertFalse(self.groep(r, "BACKUP")[0]["probleem"], "0 fouten is geen probleem")
        # Problemen eerst, nieuw voor aanhoudend
        self.assertTrue(r["groepen"][0]["probleem"])
        self.assertEqual(r["groepen"][0]["soort"], "DRAAD")

    def test_openbaar_zonder_tekst(self):
        uit = io.StringIO()
        with redirect_stdout(uit):
            self.assertEqual(analyse.main([self.map, "--sinds", "2026-11-21T000000Z", "--openbaar"]), 0)
        t = uit.getvalue()
        self.assertIn("FACTUREN", t)
        self.assertNotIn("ophalen mislukt", t)
        self.assertNotIn("iets", t, "geen tekst uit een vastloper, alleen de plek")
        self.assertNotIn("thread voorbeeld", t)
        uit = io.StringIO()
        with redirect_stdout(uit):
            analyse.main([self.map, "--openbaar", "--json"])
        j = json.loads(uit.getvalue())
        self.assertTrue(all("melding" not in g and "voorbeeld" not in g for g in j["groepen"]))

    def test_openbaar_vreemde_kop_en_plek(self):
        # Een regel in de tekst die op een kopregel lijkt: de soort mag niet openbaar worden
        self.schrijf("2026-11-23T100000Z-ab12cd34.txt",
                     melding("2026-11-23 09:00:00", "Jan Jansen woont hier", "geheim", 13, "iets mislukt"))
        uit = io.StringIO()
        with redirect_stdout(uit):
            analyse.main([self.map, "--openbaar"])
        t = uit.getvalue()
        self.assertNotIn("Jan", t)
        self.assertNotIn("geheim", t)
        self.assertNotIn("09:00", t, "geen tijden op de minuut")
        self.assertIn("ANDERS", t)
        self.assertIn("IllegalStateException @ nl.rene.tools.Voorbeeld.doe(Voorbeeld.java)", t, "plek van een vastloper mag wel")
        self.assertNotIn("ab12cd34", t, "geen installatie-id")

    def test_sinds_controle_en_map(self):
        os.mkdir(os.path.join(self.map, "2026-11-25T100000Z-map.txt"))
        uit = io.StringIO()
        with redirect_stdout(uit):
            self.assertEqual(analyse.main([self.map]), 0, "een map met een bestandsnaam wordt overgeslagen")
        self.assertEqual(analyse.main([self.map, "--sinds", "2026-11-24 14:27"]), 2)
        self.assertEqual(analyse.main([os.path.join(self.map, "bestaat-niet")]), 2)

    def test_privé_overzicht_met_tekst(self):
        uit = io.StringIO()
        with redirect_stdout(uit):
            analyse.main([self.map])
        self.assertIn("ophalen mislukt (website): tijd om", uit.getvalue())
        self.assertIn("## Problemen (3)", uit.getvalue())

    def test_zelfde_woorden_als_java(self):
        """Logboek.MIS (Java) en MIS (Python) horen dezelfde woorden te herkennen."""
        with open(os.path.join(HIER, "..", "..", "app", "src", "nl", "rene", "tools", "Logboek.java"), encoding="utf-8") as f:
            java = f.read()
        m = re.search(r"Pattern MIS = Pattern\.compile\((.*?)\);", java, re.S)
        self.assertIsNotNone(m, "Logboek.MIS gevonden")
        patroon = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))).replace("\\\\", "\\")
        self.assertEqual(patroon, analyse.MIS.pattern)
        for woord in ("mislukt", "niet herkend", "vastgelopen", "liep vast", "geweigerd", "onleesbaar", "beschadigd", "ontbreekt", "verlopen", "teruggedraaid", "exception"):
            self.assertTrue(analyse.probleem("X", "iets " + woord))
        for gewoon in ("bijgewerkt: 5 van 5 bronnen", "17 punten, 0 fouten", "vanzelf downloaden (periode): 1"):
            self.assertFalse(analyse.probleem("X", gewoon))


if __name__ == "__main__":
    unittest.main()
