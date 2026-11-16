package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/** Signalen: links van een pagina, trefwoorden, datums uit Nederlandse tekst, wat nieuw is, en wanneer er gemeld wordt. */
public class SignalenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static JSONObject bron(String... woorden) throws Exception {
        JSONArray w = new JSONArray();
        for (String x : woorden) w.put(x);
        return Signalen.normaal(new JSONObject().put("url", "https://www.aseal.nl/nieuws").put("woorden", w).put("alleen", "https://www.aseal.nl/nieuws/"));
    }

    static String fout(JSONObject in) { try { Signalen.normaal(in); return ""; } catch (Exception e) { return String.valueOf(e.getMessage()); } }

    public static void main(String[] args) throws Exception {
        // ----- bron nakijken
        JSONObject b = Signalen.normaal(new JSONObject().put("url", "aseal.nl/nieuws").put("woorden", new JSONArray().put(" Vrijlating ").put("vrijlating").put("x").put("open dag")).put("elke", 5).put("melden", "raar"));
        check("https://aseal.nl/nieuws".equals(b.optString("url")) && "aseal.nl".equals(b.optString("naam")), "adres zonder https:// wordt https, de naam is de site");
        check(b.optJSONArray("woorden").length() == 2 && "Vrijlating".equals(b.optJSONArray("woorden").optString(0)), "trefwoorden: zonder spaties eromheen, geen dubbele, geen losse letter");
        check(b.optInt("elke") == 6 && "direct".equals(b.optString("melden")) && b.optBoolean("wifi") && b.optBoolean("aan") && !b.optBoolean("belangrijk"), "begin: elke 6 uur, meteen melden, alleen wifi, aan");
        check(fout(new JSONObject().put("url", "http://aseal.nl")).contains("https") && fout(new JSONObject().put("url", "https://192.168.1.1/")).contains("https") && fout(new JSONObject().put("url", "https://localhost/x")).contains("https"), "geen http, geen thuisnetwerk, geen toestel zelf");
        check(fout(new JSONObject().put("url", "https://aseal.nl/nieuws").put("alleen", "https://anders.nl/")).contains("dezelfde site"), "\"alleen links die beginnen met\" moet op dezelfde site zijn");
        check(Signalen.opnieuw(bron("vrijlating"), bron("zeehond")) && !Signalen.opnieuw(bron("vrijlating"), bron("vrijlating").put("elke", 24)) && Signalen.opnieuw(null, bron()), "andere trefwoorden = opnieuw beginnen; alleen vaker nakijken niet");
        JSONObject inst = Signalen.inst(new JSONObject().put("stilVan", "25:00").put("overzicht", "08:15"));
        check("22:00".equals(inst.optString("stilVan")) && "07:30".equals(inst.optString("stilTot")) && "08:15".equals(inst.optString("overzicht")) && inst.optBoolean("stilAan"), "instellingen: een tijd die niet bestaat wordt de standaard");
        for (int i = 0; i < Signalen.sjablonen().length(); i++) check(fout(Signalen.sjablonen().getJSONObject(i)).isEmpty(), "sjabloon " + Signalen.sjablonen().getJSONObject(i).optString("sjabloon") + " is een geldige bron");

        // ----- links van een pagina
        String html = "<html><head><script>var a='<a href=\"/nieuws/in-script/\">x</a>';</script><style>a{}</style></head><body>"
                + "<a class=\"k\" href=\"/nieuws/vrijlating-zeehond-op-zaterdag-14-november-2026/\"><img src=x><h3>Vrijlating zeehond op zaterdag 14&nbsp;november 2026</h3></a>"
                + "<A HREF='https://www.aseal.nl/nieuws/openingstijden-wintertijd/'>Openingstijden <b>wintertijd</b></A>"
                + "<a href=\"/nieuws/vrijlating-zeehond-op-zaterdag-14-november-2026/#lees\">Lees meer</a>"
                + "<a href=\"https://elders.example.org/nieuws/vrijlating\">Elders</a><a href=\"mailto:info@aseal.nl\">Mail</a><a href=\"javascript:alert(1)\">Js</a>"
                + "<a href=\"http://www.aseal.nl/nieuws/onveilig/\">Http</a><a href=#top>Top</a><a data-href=\"/nieuws/nee/\">Geen link</a><a href = /nieuws/2915/ >Herfstvakantie</a>"
                + "<abbr title=\"x\">geen link</abbr><a href=\"/nieuws\">Nieuws</a></body></html>";
        List<String[]> links = Signalen.links(html, "https://www.aseal.nl/nieuws");
        StringBuilder s = new StringBuilder();
        for (String[] l : links) s.append(l[0]).append(" = ").append(l[1]).append("\n");
        check(links.size() == 3, "drie links naar andere pagina's van de site (geen andere site, mail, javascript, http, anker, de pagina zelf)\n" + s);
        check(links.size() > 0 && links.get(0)[0].endsWith("14-november-2026/") && "Vrijlating zeehond op zaterdag 14 november 2026".equals(links.get(0)[1]), "de tekst van de link zonder tags; hetzelfde adres met #anker telt één keer, met de langste tekst");
        check(links.size() > 1 && "Openingstijden wintertijd".equals(links.get(1)[1]) && links.size() > 2 && links.get(2)[0].endsWith("/nieuws/2915/"), "hoofdletters in de tag, enkele aanhalingstekens en een adres zonder aanhalingstekens");
        check(!s.toString().contains("in-script"), "een link in een script telt niet");
        check("Tekst en meer".equals(Signalen.tekstVan("<p>Tekst<!-- weg --> en <script>x<1</script><b>meer</b></p>", 100)) && Signalen.tekstVan("<p>abc def</p>", 5).length() == 5, "platte tekst: zonder script en commentaar, begrensd");
        check("vrijlating zeehond".equals(Signalen.slak("https://x.nl/nieuws/vrijlating-zeehond/")) && Signalen.slak("geen adres").isEmpty(), "het laatste stuk van een adres als woorden");

        // De tijd blijft begrensd, ook bij een pagina die daarop gemaakt is
        StringBuilder groot = new StringBuilder();
        for (int i = 0; i < 200_000; i++) groot.append("<a href=\"/x").append(i).append("\">");
        long t0 = System.nanoTime();
        int n = Signalen.links(groot.toString(), "https://www.aseal.nl/").size();
        Signalen.tekstVan(groot.toString(), 60_000);
        Signalen.datum(groot.toString(), "2026-11-16");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(n <= Signalen.MAX_LINKS && ms < 3000, "200.000 links zonder einde: hooguit " + Signalen.MAX_LINKS + " links en binnen 3 seconden (" + n + ", " + ms + " ms)");

        // ----- trefwoorden
        JSONArray w = new JSONArray().put("vrijlating").put("Café");
        check(Signalen.past("VRIJLATING zeehond", w) && Signalen.past("in het cafe", w) && !Signalen.past("openingstijden", w) && Signalen.past("alles", new JSONArray()) && Signalen.past("alles", null), "trefwoorden: hoofdletters en accenten maken niet uit; zonder trefwoorden telt alles");
        check(Signalen.stuk("Een lange inleiding over het centrum. Dan volgt de vrijlating van de zeehond op het strand bij Renesse en nog veel meer tekst erna.", w, 60).contains("vrijlating"), "het stuk tekst ligt rond het trefwoord");

        // ----- wat is nieuw
        Set<String> gezien = new HashSet<>();
        Signalen.Vondst v = Signalen.nieuw(bron("vrijlating"), links, gezien, 6);
        check(v.nieuw.size() == 1 && v.nieuw.get(0)[0].endsWith("14-november-2026/") && v.gezien.size() == 3 && v.links == 3, "nieuw: alleen de link met het trefwoord; de andere links gelden als gezien");
        gezien.addAll(v.gezien);
        check(Signalen.nieuw(bron("vrijlating"), links, gezien, 6).nieuw.isEmpty(), "een tweede keer is er niets nieuw");
        check(Signalen.nieuw(bron(), links, new HashSet<String>(), 2).nieuw.size() == 2 && Signalen.nieuw(bron(), links, new HashSet<String>(), 2).gezien.size() == 2, "hooguit zoveel per ronde: de rest is nog niet gezien en komt de volgende keer");
        check(Signalen.nieuw(Signalen.normaal(new JSONObject().put("url", "https://www.aseal.nl/nieuws").put("alleen", "https://www.aseal.nl/nieuws/vrij")), links, new HashSet<String>(), 6).nieuw.size() == 1, "alleen links die beginnen met het gekozen adres");
        check(Signalen.nieuw(bron("14 november"), links, new HashSet<String>(), 6).nieuw.size() == 1, "het trefwoord mag ook alleen in het adres staan (streepjes zijn spaties)");
        check(Signalen.itemId("https://a.nl/x").equals(Signalen.itemId("https://a.nl/x")) && !Signalen.itemId("https://a.nl/x").equals(Signalen.itemId("https://a.nl/y")) && Signalen.itemId("https://a.nl/x").matches("i[0-9a-f]{16}"), "hetzelfde adres geeft hetzelfde item (geen dubbele via twee bronnen)");

        // ----- datums
        String nu = "2026-11-16";
        check("2026-11-14".equals(Signalen.datum("Vrijlating zeehond op zaterdag 14 november 2026", nu)[0]), "zaterdag 14 november 2026");
        check("2026-11-21".equals(Signalen.datum("Op 21 nov. laten we Bobby vrij om 14.00 uur bij Renesse", nu)[0]) && "14:00".equals(Signalen.datum("Op 21 nov. laten we Bobby vrij om 14.00 uur bij Renesse", nu)[1]), "21 nov. om 14.00 uur: dit jaar, met de tijd");
        check("2027-01-03".equals(Signalen.datum("Nieuwjaarsduik op 3 januari", nu)[0]), "zonder jaar en al voorbij: volgend jaar");
        check("2026-11-12".equals(Signalen.datum("Afgelopen 12 november was het druk", nu)[0]), "zonder jaar, hooguit een week geleden: dit jaar");
        check("2026-12-05".equals(Signalen.datum("Geplaatst op 11-11-2026. De vrijlating is op 5 december 2026 om 10:30.", nu)[0]) && "10:30".equals(Signalen.datum("Geplaatst op 11-11-2026. De vrijlating is op 5 december 2026 om 10:30.", nu)[1]), "een datum vanaf vandaag gaat voor op de datum van plaatsing");
        check("2026-11-11".equals(Signalen.datum("Geplaatst op 11-11-2026, verder niets", nu)[0]) && Signalen.datum("Geplaatst op 11-11-2026, verder niets", nu)[1].isEmpty(), "alleen een datum in het verleden: die datum, zonder tijd");
        check(Signalen.datum("Kom op 31 februari of 0 maart", nu)[0].isEmpty() && Signalen.datum("Er zijn 14 zeehonden en 3 pups", nu)[0].isEmpty() && Signalen.datum(null, nu)[0].isEmpty() && Signalen.datum("14 november", "geen")[0].isEmpty(), "geen datum: een dag die niet bestaat, losse getallen, niets");
        check("2028-02-29".equals(Signalen.datum("29 februari 2028", nu)[0]) && Signalen.datum("29 februari 2027", nu)[0].isEmpty(), "29 februari alleen in een schrikkeljaar");
        check("2026-11-28".equals(Signalen.datum("28 NOVEMBER vanaf 13 uur", nu)[0]) && "13:00".equals(Signalen.datum("28 NOVEMBER vanaf 13 uur", nu)[1]), "hoofdletters en \"13 uur\"");
        check(Signalen.datum("Op 20 november 2026 kost het € 14.50 per persoon", nu)[1].isEmpty(), "een bedrag is geen tijd");
        check("2027-03-01".equals(Signalen.datum("1 mrt", nu)[0]) && "2026-12-24".equals(Signalen.datum("24 dec", nu)[0]) && "2027-09-02".equals(Signalen.datum("2 sept", nu)[0]), "afkortingen: mrt, dec, sept");

        // ----- stille uren en hoe er gemeld wordt
        TimeZone tz = TimeZone.getTimeZone("Europe/Amsterdam");
        JSONObject in = Signalen.inst(null);
        check(Signalen.stil(in, 23 * 60) && Signalen.stil(in, 3 * 60) && Signalen.stil(in, 7 * 60 + 29) && !Signalen.stil(in, 7 * 60 + 30) && !Signalen.stil(in, 12 * 60) && !Signalen.stil(in, 21 * 60 + 59), "stille uren 22:00 tot 07:30, over middernacht");
        check(!Signalen.stil(Signalen.inst(new JSONObject().put("stilAan", false)), 23 * 60) && !Signalen.stil(Signalen.inst(new JSONObject().put("stilVan", "08:00").put("stilTot", "08:00")), 8 * 60)
                && Signalen.stil(Signalen.inst(new JSONObject().put("stilVan", "13:00").put("stilTot", "14:00")), 13 * 60 + 30), "stille uren uit, van en tot gelijk, en overdag");
        long middag = Regio.toMillis(tz, "2026-11-16", "12:00"), nacht = Regio.toMillis(tz, "2026-11-16", "23:30"), avond = Regio.toMillis(tz, "2026-11-16", "19:00");
        JSONObject item = new JSONObject().put("id", "i1").put("bron", "s1").put("status", "nieuw").put("gemeld", false).put("later", 0);
        JSONObject direct = bron(), overzicht = bron().put("melden", "overzicht"), inbox = bron().put("melden", "inbox"), belangrijk = bron().put("belangrijk", true);
        check(Signalen.wijze(item, direct, in, middag, tz) == Signalen.NU && Signalen.wijze(item, direct, in, nacht, tz) == Signalen.WACHT, "meteen melden: overdag nu, 's nachts wachten");
        check(Signalen.wijze(item, belangrijk, in, nacht, tz) == Signalen.NU, "een belangrijke bron meldt ook tijdens de stille uren");
        check(Signalen.wijze(item, overzicht, in, middag, tz) == Signalen.OVERZICHT && Signalen.wijze(item, inbox, in, middag, tz) == Signalen.STIL && Signalen.wijze(item, null, in, middag, tz) == Signalen.NU, "dagoverzicht, alleen in Signalen, en een bron die weg is");
        check(Signalen.wijze(new JSONObject(item.toString()).put("gemeld", true), direct, in, middag, tz) == Signalen.NIET && Signalen.wijze(new JSONObject(item.toString()).put("status", "klaar"), direct, in, middag, tz) == Signalen.NIET, "al gemeld of afgehandeld: niets");
        JSONObject later = new JSONObject(item.toString()).put("later", avond);
        check(Signalen.wijze(later, inbox, in, middag, tz) == Signalen.WACHT && Signalen.wijze(later, inbox, in, avond + 1000, tz) == Signalen.NU && Signalen.wijze(later, overzicht, in, nacht, tz) == Signalen.WACHT, "later herinneren: een eigen melding op dat moment, ook bij een bron zonder meldingen; niet tijdens de stille uren");

        // ----- het volgende moment om te melden
        JSONObject per = new JSONObject().put("s1", direct);
        JSONArray items = new JSONArray().put(item);
        long ochtend = Regio.toMillis(tz, "2026-11-17", "07:30");
        check(Signalen.volgendeMelding(items, per, in, "", nacht, tz) == ochtend, "wacht op het einde van de stille uren: 07:30 de volgende ochtend");
        check(Signalen.volgendeMelding(items, per, in, "", middag, tz) == middag + 60_000L, "overdag: meteen");
        check(Signalen.volgendeMelding(new JSONArray().put(later), per, in, "", middag, tz) == avond, "een herinnering: op het gekozen moment");
        check(Signalen.volgendeMelding(new JSONArray().put(new JSONObject(item.toString()).put("later", nacht + 60_000L)), per, in, "", middag, tz) == ochtend, "een herinnering die in de stille uren valt: daarna");
        JSONObject perO = new JSONObject().put("s1", overzicht);
        check(Signalen.volgendeMelding(items, perO, in, "", middag, tz) == Regio.toMillis(tz, "2026-11-16", "18:00"), "dagoverzicht: vandaag om 18:00");
        check(Signalen.volgendeMelding(items, perO, in, "", avond, tz) == avond + 60_000L, "na 18:00 en vandaag nog geen overzicht: meteen");
        check(Signalen.volgendeMelding(items, perO, in, "2026-11-16", avond, tz) == Regio.toMillis(tz, "2026-11-17", "18:00"), "vandaag al een overzicht gehad: morgen om 18:00");
        check(Signalen.volgendeMelding(new JSONArray().put(new JSONObject(item.toString()).put("gemeld", true)), per, in, "", middag, tz) == Long.MAX_VALUE && Signalen.volgendeMelding(new JSONArray(), per, in, "", middag, tz) == Long.MAX_VALUE, "niets te melden: geen moment");
        check(Signalen.overzichtNu(in, "", avond, tz) && !Signalen.overzichtNu(in, "", middag, tz) && !Signalen.overzichtNu(in, "2026-11-16", avond, tz), "dagoverzicht: na 18:00, één keer per dag");

        // ----- wanneer een bron aan de beurt is
        long uur = 3_600_000L;
        check(Signalen.aanDeBeurt(direct, null, middag) == middag, "een nieuwe bron: meteen");
        check(Signalen.aanDeBeurt(direct, new JSONObject().put("lastOk", middag - uur).put("lastTry", middag - uur), middag) == middag + 5 * uur, "gelukt een uur geleden, elke 6 uur: over 5 uur");
        check(Signalen.aanDeBeurt(direct, new JSONObject().put("lastOk", middag - 9 * uur).put("lastTry", middag - 9 * uur), middag) == middag, "te laat: meteen");
        long naFout = Signalen.aanDeBeurt(direct, new JSONObject().put("lastOk", middag - 9 * uur).put("lastTry", middag - 600_000L).put("fouten", 1), middag);
        check(naFout == middag - 600_000L + uur, "na een fout: een uur na de poging opnieuw");
        long naVeel = Signalen.aanDeBeurt(direct, new JSONObject().put("lastOk", 0).put("lastTry", middag - 600_000L).put("fouten", 50), middag);
        check(naVeel == middag - 600_000L + 6 * uur, "blijft het misgaan: steeds later, maar nooit later dan het gewone tempo");
        check(Signalen.aanDeBeurt(direct, new JSONObject().put("lastOk", middag + 99 * uur).put("lastTry", middag + 99 * uur), middag) == middag, "de klok is teruggezet: meteen");
        long[] vs = Signalen.volgendeScan(new JSONArray().put(bron().put("id", "s1")).put(bron().put("id", "s2").put("wifi", false)).put(bron().put("id", "s3").put("aan", false)),
                new JSONObject().put("s1", new JSONObject().put("lastOk", middag).put("lastTry", middag)).put("s2", new JSONObject().put("lastOk", middag - 2 * uur).put("lastTry", middag - 2 * uur)), middag);
        check(vs[1] == middag + 6 * uur && vs[0] == middag + 4 * uur, "plannen: wifi-bronnen en andere apart; een bron die uit staat telt niet");
        check(Signalen.volgendeScan(new JSONArray(), new JSONObject(), middag)[0] == Long.MAX_VALUE, "geen bronnen: niets te plannen");

        // ----- logboek: alleen aantallen en vaste woorden
        java.io.File dir = new java.io.File("app/src/nl/rene/tools");
        boolean logOk = true; int regels = 0;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("App\\.log\\(\\w+, \"SIGNAAL\", (.*)\\);");
        for (String f : new String[]{"Signalen.java", "SignalenScan.java", "SignalenJob.java", "SignalenBridge.java"}) {
            String src = new String(java.nio.file.Files.readAllBytes(new java.io.File(dir, f).toPath()), "UTF-8");
            java.util.regex.Matcher m = p.matcher(src);
            while (m.find()) { regels++; logOk &= m.group(1).replaceAll("\"[^\"]*\"", "").replaceAll("[et]\\.getClass\\(\\)\\.getSimpleName\\(\\)|v\\.links|erbij|\\bn\\b|[ +]", "").isEmpty(); }
        }
        check(logOk && regels >= 4, "logsoort SIGNAAL: alleen vaste woorden, aantallen en het soort fout, nooit adressen, titels of trefwoorden (" + regels + " regels)");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
