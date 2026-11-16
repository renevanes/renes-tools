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
        check("2027-03-01".equals(Signalen.datum("1 mrt", nu)[0]) && "2026-12-24".equals(Signalen.datum("24 dec", nu)[0]) && "2026-09-02".equals(Signalen.datum("2 sept", nu)[0]), "afkortingen: mrt, dec, sept; zonder jaar het dichtstbijzijnde jaar");
        String[] gepl = Signalen.datum("Gepubliceerd op 2 november. Vrijlating zaterdag 21 november om 14.00 uur", nu);
        check("2026-11-21".equals(gepl[0]) && "14:00".equals(gepl[1]) && "2026-11-02".equals(Signalen.datum("Geplaatst 2 november", nu)[0]), "de datum van plaatsing zonder jaar wordt geen datum volgend jaar en wint niet van de echte datum");
        check("2025-12-28".equals(Signalen.datum("28 december", "2026-01-03")[0]) && "2026-01-05".equals(Signalen.datum("5 januari", "2025-12-30")[0]), "rond de jaarwisseling: het dichtstbijzijnde jaar, terug en vooruit");
        check(Signalen.lijktHtml("<!DOCTYPE html><html><body>x") && Signalen.lijktHtml("\n <HTML lang=nl>") && !Signalen.lijktHtml("%PDF-1.7 ...") && !Signalen.lijktHtml(null), "alleen een webpagina wordt als tekst gelezen, geen pdf");
        java.util.List<String[]> tien = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) tien.add(new String[]{"https://www.aseal.nl/nieuws/vrijlating-" + i + "/", "Vrijlating " + i});
        Signalen.Vondst alles = Signalen.nieuw(bron("vrijlating"), tien, new HashSet<String>(), Integer.MAX_VALUE);
        check(alles.gezien.size() == 10 && Signalen.nieuw(bron("vrijlating"), tien, new HashSet<String>(alles.gezien), 6).nieuw.isEmpty(), "de eerste keer geldt alles wat er al staat als gezien: de tweede ronde meldt geen oude berichten");

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

        // ----- bronnen uit Tools zelf
        JSONObject ag = Signalen.normaal(new JSONObject().put("soort", "agenda").put("url", "https://x.nl").put("woorden", new JSONArray().put("abc")).put("vooraf", 7));
        check("agenda".equals(ag.optString("soort")) && "Agenda".equals(ag.optString("naam")) && ag.optString("url").isEmpty() && ag.optJSONArray("woorden").length() == 0 && ag.optInt("vooraf") == 24 && ag.optInt("elke") == 1 && !ag.optBoolean("wifi"), "Agenda: geen adres of trefwoorden, een dag van tevoren, elk uur, geen wifi nodig");
        check(fout(new JSONObject().put("soort", "nieuws")).contains("trefwoord") && fout(new JSONObject().put("soort", "nieuws").put("woorden", new JSONArray().put("Rotterdam"))).isEmpty(), "Nieuws: minstens één trefwoord");
        check(Signalen.normaal(new JSONObject().put("soort", "notities")).optInt("dagen") == 7 && Signalen.normaal(new JSONObject().put("soort", "notities").put("dagen", 14)).optInt("dagen") == 14 && "web".equals(Signalen.soort("vreemd")), "Notities: na een week; een onbekende soort is een website");
        check(Signalen.lokaal("agenda") && Signalen.lokaal("facturen") && Signalen.lokaal("notities") && Signalen.lokaal("taken") && !Signalen.lokaal("web") && !Signalen.lokaal("nieuws"), "alleen een website en Nieuws hebben internet nodig");
        check(Signalen.eersteStil("web") && Signalen.eersteStil("nieuws") && Signalen.eersteStil("facturen") && Signalen.eersteStil("notities") && !Signalen.eersteStil("agenda") && !Signalen.eersteStil("taken"), "de eerste keer geen melding, behalve voor een afspraak of een mislukte taak");
        check(Signalen.opnieuw(bron("x"), ag) && "notes".equals(Signalen.tool("notities")) && Signalen.tool("web").isEmpty() && Signalen.tool("taken").isEmpty(), "een andere soort is een andere bron; de tool van elke soort");
        long[] vl = Signalen.volgendeScan(new JSONArray().put(new JSONObject(ag.toString()).put("id", "s9")), new JSONObject().put("s9", new JSONObject().put("lastOk", middag).put("lastTry", middag)), middag);
        check(vl[0] == Long.MAX_VALUE && vl[1] == Long.MAX_VALUE && vl[2] == middag + uur, "plannen: een bron uit Tools loopt zonder internet, elk uur");

        ag.put("id", "s9");
        java.util.List<Agenda.Item> afs = new java.util.ArrayList<>();
        String[][] rij = {{"a1", "Tandarts", "2026-11-17", "10:00", ""}, {"a2", "Te ver weg", "2026-11-18", "09:00", ""}, {"a3", "Al begonnen", "2026-11-16", "11:00", ""}, {"a4", "Verjaardag", "2026-11-17", "", "heel"}, {"a5", "Vandaag hele dag", "2026-11-16", "", "heel"}, {"a6", "Gisteren", "2026-11-15", "", "heel"}, {"a7", "Straks", "2026-11-16", "15:30", ""}};
        for (String[] r : rij) { Agenda.Item it = new Agenda.Item(); it.id = r[0]; it.titel = r[1]; it.dag = r[2]; it.eind = r[2]; it.van = r[3]; it.heleDag = !r[4].isEmpty(); it.plek = "Coolsingel"; afs.add(it); }
        List<JSONObject> ai = SignalenIntern.agenda(afs, ag, middag, tz);
        StringBuilder at = new StringBuilder();
        for (JSONObject o : ai) at.append(o.optString("titel")).append("|");
        check("Vandaag hele dag|Straks|Verjaardag|Tandarts|".equals(at.toString()), "Agenda, een dag van tevoren om 12:00: wat nog komt binnen 24 uur, het eerste bovenaan (" + at + ")");
        check(ai.size() == 4 && "2026-11-17".equals(ai.get(3).optString("dag")) && "10:00".equals(ai.get(3).optString("tijd")) && ai.get(3).optBoolean("inAgenda") && "agenda".equals(ai.get(3).optString("tool")) && "agenda".equals(ai.get(3).optString("open")) && ai.get(2).optString("tijd").isEmpty() && "Coolsingel".equals(ai.get(3).optString("tekst")), "afspraak: dag, tijd, plek; staat al in de agenda");
        List<JSONObject> a3 = SignalenIntern.agenda(afs, new JSONObject(ag.toString()).put("vooraf", 3), middag, tz);
        check(a3.size() == 2 && "Straks".equals(a3.get(1).optString("titel")) && SignalenIntern.agenda(afs, new JSONObject(ag.toString()).put("vooraf", 3), middag - 3_600_000L, tz).size() == 1, "3 uur van tevoren, met één ronde speling: om 12:00 al de afspraak van 15:30 (niets valt tussen twee rondes door), om 11:00 nog niet");
        check(ai.get(0).optLong("gevonden") > ai.get(3).optLong("gevonden") && ai.get(3).optLong("verloopt") == Regio.toMillis(tz, "2026-11-17", "10:00") && ai.get(0).optLong("verloopt") == Regio.toMillis(tz, "2026-11-17", "00:00"), "de volgorde blijft ook op het moment van vinden; een afspraak verloopt bij het begin, een hele dag aan het eind");
        JSONObject voorbij = new JSONObject(ai.get(3).toString());
        check(Signalen.wijze(voorbij, direct, in, Regio.toMillis(tz, "2026-11-17", "09:00"), tz) == Signalen.NU && Signalen.wijze(voorbij, direct, in, Regio.toMillis(tz, "2026-11-17", "10:30"), tz) == Signalen.NIET, "een afspraak die al begonnen is wordt niet meer gemeld");
        check(Signalen.normaal(new JSONObject().put("soort", "agenda").put("elke", 12)).optInt("elke") == 1, "Agenda kijkt altijd elk uur");
        Set<String> gz = new HashSet<>();
        check(Signalen.nieuwIntern(ai, gz, 3).size() == 3 && gz.size() == 3 && Signalen.nieuwIntern(ai, gz, 6).size() == 1 && Signalen.nieuwIntern(ai, gz, 6).isEmpty(), "uit Tools: hooguit zoveel per ronde, de rest de volgende keer, daarna niets nieuws");
        check(!ai.get(0).optString("id").equals(SignalenIntern.agenda(afs, ag, middag, tz).get(1).optString("id")) && ai.get(0).optString("id").equals(SignalenIntern.agenda(afs, ag, middag + 60_000L, tz).get(0).optString("id")), "dezelfde afspraak geeft elke ronde hetzelfde bericht");

        JSONObject nb = Signalen.normaal(new JSONObject().put("soort", "nieuws").put("woorden", new JSONArray().put("haven"))).put("id", "s8");
        JSONArray arts = new JSONArray().put(new JSONObject().put("id", "n1").put("titel", "Nieuwe kade in de Haven").put("link", "https://nos.nl/a/1").put("sam", "Begin.").put("bronnaam", "NOS"))
                .put(new JSONObject().put("id", "n2").put("titel", "Weer").put("link", "https://nos.nl/a/2").put("sam", "Regen in de havenstad."))
                .put(new JSONObject().put("id", "n3").put("titel", "Sport").put("link", "https://nos.nl/a/3").put("sam", "Voetbal."))
                .put(new JSONObject().put("id", "n4").put("titel", "Haven http").put("link", "http://onveilig.nl/a"));
        List<JSONObject> ni = SignalenIntern.nieuws(arts, nb, middag);
        check(ni.size() == 3 && "https://nos.nl/a/1".equals(ni.get(0).optString("link")) && "Nieuws: NOS".equals(ni.get(0).optString("bronNaam")) && ni.get(0).optString("id").equals(Signalen.itemId("https://nos.nl/a/1")) && ni.get(2).optString("link").isEmpty(), "Nieuws: titel of begin past bij het trefwoord; alleen een https-link; hetzelfde artikel als via een website");
        check(SignalenIntern.nieuws(arts, new JSONObject(nb.toString()).put("woorden", new JSONArray()), middag).isEmpty(), "Nieuws zonder trefwoorden levert niets");

        JSONObject fb = Signalen.normaal(new JSONObject().put("soort", "facturen")).put("id", "s7");
        JSONObject dossier = new JSONObject().put("sha1", new JSONObject().put("naam", "kpn-11.pdf").put("leverancierNaam", "KPN").put("opgehaald", middag - 2 * SignalenIntern.DAG).put("meta", new JSONObject().put("nummer", "F123")))
                .put("sha2", new JSONObject().put("naam", "oud.pdf").put("leverancierNaam", "Oud").put("opgehaald", middag - 40 * SignalenIntern.DAG))
                .put("sha3", new JSONObject().put("naam", "nieuw.pdf").put("leverancierNaam", "").put("opgehaald", middag - 3_600_000L));
        List<JSONObject> fi = SignalenIntern.facturen(null, dossier, fb, middag);
        check(fi.size() == 2 && "Nieuwe factuur".equals(fi.get(0).optString("titel")) && "Nieuwe factuur van KPN".equals(fi.get(1).optString("titel")) && fi.get(1).optString("tekst").contains("F123") && "facturen".equals(fi.get(1).optString("open")), "Facturen: van de afgelopen 30 dagen, nieuwste eerst, met leverancier en nummer");

        JSONObject ntb = Signalen.normaal(new JSONObject().put("soort", "notities")).put("id", "s6");
        JSONArray notes = new JSONArray()
                .put(new JSONObject().put("id", "l1").put("title", "Boodschappen").put("updated", middag - 9 * SignalenIntern.DAG).put("items", new JSONArray().put(new JSONObject().put("text", "melk")).put(new JSONObject().put("text", "brood").put("done", true)).put(new JSONObject().put("text", "kaas"))))
                .put(new JSONObject().put("id", "l2").put("title", "Net bijgewerkt").put("updated", middag - 2 * SignalenIntern.DAG).put("items", new JSONArray().put(new JSONObject().put("text", "x"))))
                .put(new JSONObject().put("id", "l3").put("title", "Alles af").put("updated", middag - 20 * SignalenIntern.DAG).put("items", new JSONArray().put(new JSONObject().put("text", "x").put("done", true))))
                .put(new JSONObject().put("id", "l4").put("title", "Gewone notitie").put("text", "tekst").put("updated", middag - 20 * SignalenIntern.DAG));
        List<JSONObject> li = SignalenIntern.notities(null, notes, ntb, middag);
        check(li.size() == 1 && "Boodschappen: 2 open punten".equals(li.get(0).optString("titel")) && li.get(0).optString("tekst").contains("melk") && !li.get(0).optString("tekst").contains("brood") && "note:l1".equals(li.get(0).optString("open")) && "notes".equals(li.get(0).optString("tool")), "Notities: alleen een lijst met open punten die een week ligt");
        notes.getJSONObject(0).put("updated", middag - 8 * SignalenIntern.DAG);
        check(!SignalenIntern.notities(null, notes, ntb, middag).get(0).optString("id").equals(li.get(0).optString("id")), "aangeraakt en weer blijven liggen: een nieuw bericht");

        JSONObject tb = Signalen.normaal(new JSONObject().put("soort", "taken")).put("id", "s5");
        JSONObject backup = new JSONObject().put("t", middag - 3_600_000L).put("auto", true).put("sms", new JSONObject().put("ok", true)).put("wa", new JSONObject().put("ok", false)).put("notes", new JSONObject().put("ok", false));
        JSONArray levs = new JSONArray().put(new JSONObject().put("id", "kpn").put("naam", "KPN")).put(new JSONObject().put("id", "goed").put("naam", "Goed")).put(new JSONObject().put("id", "stop").put("naam", "Gestopt"));
        JSONObject ft = new JSONObject().put("kpn", new JSONObject().put("ok", false).put("lastTry", middag - 7_200_000L).put("msg", "Inloggen lukte niet"))
                .put("goed", new JSONObject().put("ok", true).put("lastTry", middag - 7_200_000L)).put("stop", new JSONObject().put("ok", false).put("lastTry", middag - 7_200_000L).put("msg", "Gestopt"));
        JSONArray wcb = new JSONArray().put(new JSONObject().put("id", "w1").put("naam", "Den Haag")).put(new JSONObject().put("id", "w2").put("naam", "Uit").put("aan", false));
        JSONObject wct = new JSONObject().put("w1", new JSONObject().put("fout", "De bron antwoordt met 500").put("lastTry", middag - 86_400_000L)).put("w2", new JSONObject().put("fout", "x").put("lastTry", middag - 1000L));
        List<JSONObject> ti = SignalenIntern.taken(null, backup, levs, ft, wcb, wct, tb, middag, tz);
        StringBuilder tt = new StringBuilder();
        for (JSONObject o : ti) tt.append(o.optString("titel")).append("|");
        check("Backup: 2 onderdelen zijn mislukt|Facturen ophalen bij KPN lukte niet|Toiletten en douches: Den Haag bijwerken lukte niet|".equals(tt.toString()), "taken: de backup, facturen ophalen en Toiletten; niet wat goed ging, gestopt is of uit staat (" + tt + ")");
        check(ti.get(1).optString("tekst").contains("Inloggen") && "backup".equals(ti.get(0).optString("open")) && "facturen".equals(ti.get(1).optString("tool")) && SignalenIntern.taken(null, new JSONObject().put("t", middag - 5 * SignalenIntern.DAG).put("wa", new JSONObject().put("ok", false)), null, null, null, null, tb, middag, tz).isEmpty()
                && SignalenIntern.taken(null, new JSONObject(backup.toString()).put("stopped", true), null, null, null, null, tb, middag, tz).isEmpty() && "wc".equals(ti.get(2).optString("tool")), "taken: de reden erbij; ouder dan 3 dagen of zelf gestopt telt niet");
        check(ti.get(1).optString("id").equals(SignalenIntern.taken(null, null, levs, new JSONObject().put("kpn", new JSONObject().put("ok", false).put("lastTry", middag - 60_000L).put("msg", "x")), null, null, tb, middag, tz).get(0).optString("id")), "dezelfde taak die dezelfde dag weer mislukt: één bericht");

        // ----- logboek: alleen aantallen en vaste woorden
        java.io.File dir = new java.io.File("app/src/nl/rene/tools");
        boolean logOk = true; int regels = 0;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("App\\.log\\(\\w+, \"SIGNAAL\", (.*)\\);");
        for (String f : new String[]{"Signalen.java", "SignalenScan.java", "SignalenJob.java", "SignalenBridge.java", "SignalenIntern.java"}) {
            String src = new String(java.nio.file.Files.readAllBytes(new java.io.File(dir, f).toPath()), "UTF-8");
            java.util.regex.Matcher m = p.matcher(src);
            while (m.find()) { regels++; logOk &= m.group(1).replaceAll("\"[^\"]*\"", "").replaceAll("[et]\\.getClass\\(\\)\\.getSimpleName\\(\\)|tel\\[0\\]|erbij|\\bn\\b|[ +]", "").isEmpty(); }
        }
        check(logOk && regels >= 4, "logsoort SIGNAAL: alleen vaste woorden, aantallen en het soort fout, nooit adressen, titels of trefwoorden (" + regels + " regels)");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
    }
}
