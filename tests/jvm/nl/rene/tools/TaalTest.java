package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Taal (Java-kant van Engels): woordenboek lezen, vertalen met plaatshouders en meervoud, teksten met ingevulde delen
 *  herkennen (meldingen, widgets), en de Engelse systeemtekst van de assistent. */
public class TaalTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static void eq(String got, String want, String what) { check(want.equals(got), what + ": " + got + (want.equals(got) ? "" : " (verwacht " + want + ")")); }

    public static void main(String[] a) throws Exception {
        Map<String, Object> d = Taal.parse("{\"Opgeslagen\": \"Saved\", \"{n} berichten gewist\": \"{n} messages deleted\","
                + " \"{n} keer iets gehoord\": [\"Heard something once\", \"Heard something {n} times\"],"
                + " \"Auto redial: {wie}\": \"Auto redial: {wie}\", \"Poging {n} van {totaal}\": \"Attempt {n} of {totaal}\", \"Thuis\": \"Home\","
                + " \"kapot\": 3, \"x\": [\"a\"]}");
        check(d.get("Opgeslagen") instanceof String && d.get("{n} keer iets gehoord") instanceof String[] && !d.containsKey("kapot") && !d.containsKey("x"), "woordenboek lezen: tekst, [één, meer], rest overgeslagen");
        check(Taal.parse("geen json").isEmpty(), "kapot woordenboek: leeg (dan blijft alles Nederlands)");

        // Vertalen
        eq(Taal.tr(d, true, "Opgeslagen"), "Saved", "Engels");
        eq(Taal.tr(d, false, "Opgeslagen"), "Opgeslagen", "Nederlands: brontekst");
        eq(Taal.tr(d, true, "  Opgeslagen\n"), "  Saved\n", "witruimte aan de randen blijft");
        eq(Taal.tr(d, true, "Op­ge­sla­gen"), "Saved", "zachte afbreekstreepjes tellen niet mee");
        eq(Taal.tr(d, true, "Bestaat niet {x}", "x", 1), "Bestaat niet 1", "zonder vertaling: Nederlands, wel ingevuld");
        eq(Taal.tr(d, true, "{n} berichten gewist", "n", 5), "5 messages deleted", "plaatshouder");
        eq(Taal.tr(d, false, "{n} berichten gewist", "n", 5), "5 berichten gewist", "plaatshouder in het Nederlands");
        eq(Taal.tr(d, true, "{n} keer iets gehoord", "n", 1), "Heard something once", "meervoud uit het woordenboek: één");
        eq(Taal.tr(d, true, "{n} keer iets gehoord", "n", 3), "Heard something 3 times", "meervoud uit het woordenboek: meer");
        eq(Taal.fill("{a} en {b} en {c}", "a", 1, "b", "x"), "1 en x en {c}", "onbekende plaatshouder blijft staan");
        eq(Taal.fill("{a}", "a", "$1\\"), "$1\\", "invullen met $ en \\ (geen regex-vervanging)");
        eq(Taal.norm("  a \n\t b  "), "a b", "sleutel normaliseren");
        eq(Taal.tr(null, true, "Opgeslagen"), "Opgeslagen", "geen woordenboek: brontekst");

        // Teksten met ingevulde delen (meldingen en widgets die Java zelf samenstelt)
        List<Object[]> ps = Taal.patterns(d);
        eq(Taal.tx(d, ps, "Opgeslagen"), "Saved", "tx: precies een sleutel");
        eq(Taal.tx(d, ps, "Poging 2 van 5"), "Attempt 2 of 5", "tx: sleutel met plaatshouders");
        eq(Taal.tx(d, ps, "Auto redial: Thuis"), "Auto redial: Home", "tx: ingevuld deel dat zelf een sleutel is, ook vertaald");
        eq(Taal.tx(d, ps, "Iets anders"), "Iets anders", "tx: niets gevonden → zoals het was");
        eq(Taal.tx(d, ps, ""), "", "tx: leeg");
        check(ps.stream().noneMatch(p -> ((Integer) p[3]) < 4), "patronen zonder vaste tekst doen niet mee");

        // Het echte woordenboek: geldig, met een paar vaste vertalingen, zonder het verboden woord
        String json = new String(Files.readAllBytes(Paths.get("web/src/taal/en.json")), StandardCharsets.UTF_8);
        Map<String, Object> en = Taal.parse(json);
        check(en.size() > 3000, "web/src/taal/en.json gelezen: " + en.size() + " vertalingen");
        eq((String) en.get("Instellingen"), "Settings", "Instellingen");
        eq(Taal.tr(en, true, "Stil (alle meldingen uit in de app)"), (String) en.get("Stil (alle meldingen uit in de app)"), "kanaalnaam uit Java staat erin");
        check(en.get("Stil (alle meldingen uit in de app)") != null, "meldingskanaal 'Stil' vertaald");
        boolean schoon = true;
        for (Object v : en.values()) for (String s : v instanceof String[] ? (String[]) v : new String[]{(String) v}) if (s.toLowerCase().matches("(?s).*\\b(f" + "ake|n" + "ep)\\b.*")) schoon = false;
        check(schoon, "geen verboden woord in de vertalingen");
        List<Object[]> eps = Taal.patterns(en);
        check(eps.size() > 300, "patronen uit het echte woordenboek: " + eps.size());

        // De assistent in het Engels: systeemtekst
        String sys = LocalAi.buildSystemEn("Wednesday 4 November 2026", "kort", "nl", "I live in Rotterdam", "", Arrays.asList(), "");
        check(sys.startsWith("You are the offline assistant") && sys.contains("Always answer in English") && sys.contains("short") && sys.contains("Today is Wednesday 4 November 2026") && sys.contains("I live in Rotterdam"), "Engelse systeemtekst: " + sys.substring(0, 60) + "…");
        check(LocalAi.buildSystemEn("x", "", "auto", "", "", null, "").contains("same language as the question"), "taal 'auto': antwoord in de taal van de vraag");
        check(!LocalAi.buildSystem("x", "", "nl", "", "").contains("English"), "Nederlandse systeemtekst blijft Nederlands");

        // Regio: Engels als taalkeuze
        check(Arrays.stream(Regio.LANGS).anyMatch(l -> "en".equals(l[0]) && "English".equals(l[1])), "Regio.LANGS heeft English");
        check("nl".equals(Regio.parse("").lang), "standaard Nederlands (de app volgt de taal van de telefoon niet)");
        check("en".equals(Regio.parse("{\"lang\":\"en\"}").lang), "Engels bewaard in de regio-instellingen");

        // T() is alleen een markering
        eq(Taal.T("Bellen"), "Bellen", "Taal.T geeft de tekst terug");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
