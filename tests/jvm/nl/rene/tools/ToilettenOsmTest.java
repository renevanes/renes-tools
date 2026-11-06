package nl.rene.tools;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Toiletten: de tekst van een OpenStreetMap-notitie, het formulier, de antwoorden en dat er niets van Rene zelf in staat. */
public class ToilettenOsmTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        int T = Toiletten.TOILET, D = Toiletten.DOUCHE, G = Toiletten.GRATIS, B = Toiletten.BETAALD, R = Toiletten.ROLSTOEL;
        String nieuw = ToilettenOsm.tekst("toevoeging", new JSONObject().put("s", "toilet").put("f", T | G | R).put("n", "Toilet Kiosk").put("u", "Mo-Su 08:00-20:00"), null, "", "", "Sleutel bij de kiosk");
        check(nieuw.startsWith("Hier is een openbaar toilet (gratis, rolstoeltoegankelijk).") && nieuw.contains("\nNaam: Toilet Kiosk") && nieuw.contains("\nOpeningstijden: Mo-Su 08:00-20:00") && nieuw.contains("\nOpmerking: Sleutel bij de kiosk"), "toevoeging: soort, eigenschappen, naam, tijden en opmerking\n" + nieuw);
        check(nieuw.endsWith("Doorgegeven met de app Tools (https://github.com/renevanes/renes-tools)."), "met de app erbij, geen naam of account");
        String tank = ToilettenOsm.tekst("toevoeging", new JSONObject().put("s", "tank").put("f", T | Toiletten.KLANTEN), null, "", "", "");
        check(tank.startsWith("Hier is een tankstation met toilet (alleen voor klanten).") && !tank.contains("Opmerking"), "tankstation met toilet\n" + tank);
        String douche = ToilettenOsm.tekst("toevoeging", new JSONObject().put("s", "douche").put("f", D | T), null, "", "", "");
        check(douche.startsWith("Hier is een douche (met toilet)."), "douche met toilet\n" + douche);
        String wijz = ToilettenOsm.tekst("wijziging", new JSONObject().put("s", "toilet").put("f", T | G).put("n", "Markt").put("u", "24/7"),
                new JSONObject().put("s", "toilet").put("f", T | B | R).put("n", "Markt").put("u", ""), "https://www.openstreetmap.org/node/123", "", "");
        check(wijz.startsWith("Het openbare toilet hier (https://www.openstreetmap.org/node/123) klopt niet helemaal:") && wijz.contains("\n- gratis (stond: betaald)")
                && wijz.contains("\n- niet rolstoeltoegankelijk (stond: rolstoeltoegankelijk)") && wijz.contains("\n- openingstijden: 24/7 (stond: (leeg))") && !wijz.contains("naam:"), "wijziging: alleen wat anders is, met wat er stond\n" + wijz);
        String af = ToilettenOsm.tekst("afwijzing", new JSONObject().put("s", "toilet").put("f", T), null, "https://www.openstreetmap.org/way/9", "weg", "Gesloopt in 2026");
        check(af.startsWith("Het openbare toilet hier (https://www.openstreetmap.org/way/9) bestaat niet meer.") && af.contains("Opmerking: Gesloopt in 2026"), "afwijzing met reden\n" + af);
        check(ToilettenOsm.tekst("afwijzing", new JSONObject().put("s", "tank"), null, "", "klanten", "").startsWith("Het tankstation hier is alleen voor klanten."), "afwijzing: andere soort en reden");
        StringBuilder lang = new StringBuilder();
        for (int i = 0; i < 3000; i++) lang.append('x');
        check(ToilettenOsm.tekst("toevoeging", new JSONObject().put("s", "toilet").put("f", T).put("n", lang.toString()), null, "", "", lang.toString()).length() <= 2000, "hooguit 2000 tekens (grens van OpenStreetMap)");

        check(ToilettenOsm.osmUrl("n123").equals("https://www.openstreetmap.org/node/123") && ToilettenOsm.osmUrl("w5").endsWith("/way/5") && ToilettenOsm.osmUrl("r7").endsWith("/relation/7"), "link naar het object in OpenStreetMap");
        check(ToilettenOsm.osmUrl("{8494}").isEmpty() && ToilettenOsm.osmUrl("n12a").isEmpty() && ToilettenOsm.osmUrl(null).isEmpty(), "geen link voor id's van andere bronnen");

        String form = ToilettenOsm.formulier(51.9225, 4.47917, "Hier is een toilet & douche.\nOk?");
        check(form.startsWith("lat=51.922500&lon=4.479170&text=") && URLDecoder.decode(form.substring(form.indexOf("text=") + 5), "UTF-8").equals("Hier is een toilet & douche.\nOk?"), "formulier: plek met punt, tekst gecodeerd");
        check(ToilettenOsm.browserUrl(51.9225, 4.47917).equals("https://www.openstreetmap.org/note/new#map=19/51.922500/4.479170"), "moderatiezone: de website op die plek");
        check(ToilettenOsm.leesId("{\"type\":\"Feature\",\"properties\":{\"id\":4242,\"status\":\"open\"}}") == 4242, "antwoord: id van de notitie");
        check(ToilettenOsm.leesStatus("{\"properties\":{\"status\":\"closed\"}}").equals("closed") && ToilettenOsm.leesStatus("{\"properties\":{\"status\":\"raar\"}}").equals("open"), "status lezen");
        boolean fout = false;
        try { ToilettenOsm.leesId("{\"properties\":{}}"); } catch (Exception e) { fout = true; }
        check(fout, "onverwacht antwoord: fout");
        check(ToilettenOsm.notitieUrl(5).equals("https://www.openstreetmap.org/note/5"), "link naar de notitie");

        String src = new String(Files.readAllBytes(Paths.get("app/src/nl/rene/tools/ToilettenOsm.java")), StandardCharsets.UTF_8);
        check(src.contains("\"https://api.openstreetmap.org/api/0.6/notes\"") && !src.contains("Authorization") && !src.contains("oauth"), "alleen de notities-API van OpenStreetMap, zonder account");
        String br = new String(Files.readAllBytes(Paths.get("app/src/nl/rene/tools/ToilettenBridge.java")), StandardCharsets.UTF_8);
        check(br.contains("wcNotitie") && br.contains("wcDoorgeven"), "eerst de tekst laten zien (wcNotitie), dan pas versturen (wcDoorgeven)");
        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
