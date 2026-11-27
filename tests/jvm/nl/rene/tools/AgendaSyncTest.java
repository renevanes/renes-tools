package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Agenda synchroniseren: instellingen, wie er wint, het bereik, herkennen van dezelfde afspraak en de veiligheidsrem. */
public class AgendaSyncTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static JSONObject cfg(String richting, String conflict, String wissen) throws Exception {
        return AgendaSyncRegels.normaal(new JSONObject().put("richting", richting).put("conflict", conflict).put("wissen", wissen).put("kal", "3"));
    }

    static JSONObject afspraak(String titel, String dag, String van, String tot) throws Exception {
        return new JSONObject().put("titel", titel).put("dag", dag).put("eind", dag).put("van", van).put("tot", tot).put("heleDag", false)
                .put("plek", "").put("notitie", "").put("herhaal", "").put("totDag", "");
    }

    public static void main(String[] args) throws Exception {
        // instellingen
        JSONObject std = AgendaSyncRegels.normaal(null);
        check("uit".equals(std.optString("richting")) && std.optInt("elke") == 60 && "naar".equals(std.optString("wissen"))
                && "vragen".equals(std.optString("conflict")) && std.optInt("rem") == 10 && std.optBoolean("open") && !std.optBoolean("laden")
                && std.optBoolean("googleNu") && std.optInt("terug") == 30 && std.optInt("vooruit") == -1 && "fouten".equals(std.optString("meld")),
                "standaard: uit, elk uur, verwijderen alleen Tools → Google, vragen bij een conflict, rem bij 10");
        JSONObject raar = AgendaSyncRegels.normaal(new JSONObject().put("richting", "overal").put("elke", 7).put("kal", "abc").put("terug", 12)
                .put("wissen", "alles").put("conflict", "gok").put("rem", 3).put("meld", "soms").put("vooruit", 5));
        check("uit".equals(raar.optString("richting")) && raar.optInt("elke") == 60 && raar.optString("kal").isEmpty() && raar.optInt("terug") == 30
                && "naar".equals(raar.optString("wissen")) && "vragen".equals(raar.optString("conflict")) && raar.optInt("rem") == 10
                && "fouten".equals(raar.optString("meld")) && raar.optInt("vooruit") == -1, "onbekende waarden worden de standaard");
        JSONObject goed = AgendaSyncRegels.normaal(new JSONObject().put("richting", "beide").put("elke", 15).put("kal", "12").put("terug", -1)
                .put("vooruit", 365).put("wissen", "nooit").put("conflict", "google").put("rem", 0).put("meld", "altijd").put("laden", true));
        check("beide".equals(goed.optString("richting")) && goed.optInt("elke") == 15 && "12".equals(goed.optString("kal")) && goed.optInt("terug") == -1
                && goed.optInt("vooruit") == 365 && "nooit".equals(goed.optString("wissen")) && "google".equals(goed.optString("conflict"))
                && goed.optInt("rem") == 0 && "altijd".equals(goed.optString("meld")) && goed.optBoolean("laden"), "geldige keuzes blijven staan");
        check(AgendaSyncRegels.normaal(new JSONObject().put("elke", 0)).optInt("elke") == 0, "alleen met de hand (0) mag");

        // richting en verwijderen
        check(AgendaSyncRegels.naarGoogle(cfg("naar", "vragen", "naar")) && !AgendaSyncRegels.vanGoogle(cfg("naar", "vragen", "naar")), "Tools → Google");
        check(!AgendaSyncRegels.naarGoogle(cfg("van", "vragen", "naar")) && AgendaSyncRegels.vanGoogle(cfg("van", "vragen", "naar")), "Google → Tools");
        check(AgendaSyncRegels.wisInGoogle(cfg("beide", "vragen", "naar")) && !AgendaSyncRegels.wisInTools(cfg("beide", "vragen", "naar")), "wissen naar: alleen in Google weg");
        check(!AgendaSyncRegels.wisInGoogle(cfg("beide", "vragen", "van")) && AgendaSyncRegels.wisInTools(cfg("beide", "vragen", "van")), "wissen van: alleen in Tools weg");
        check(AgendaSyncRegels.wisInGoogle(cfg("beide", "vragen", "beide")) && AgendaSyncRegels.wisInTools(cfg("beide", "vragen", "beide")), "wissen beide");
        check(!AgendaSyncRegels.wisInGoogle(cfg("beide", "vragen", "nooit")) && !AgendaSyncRegels.wisInTools(cfg("beide", "vragen", "nooit")), "wissen nooit");
        check(!AgendaSyncRegels.wisInTools(cfg("naar", "vragen", "beide")), "Tools → Google verwijdert nooit iets in Tools");
        check(!AgendaSyncRegels.wisInGoogle(cfg("van", "vragen", "beide")), "Google → Tools verwijdert nooit iets in Google");

        // wie wint
        JSONObject beide = cfg("beide", "vragen", "naar");
        check(AgendaSyncRegels.besluit(beide, "a", "a", "a", "a") == AgendaSyncRegels.NIETS, "niets veranderd: niets doen");
        check(AgendaSyncRegels.besluit(beide, "b", "b", "a", "a") == AgendaSyncRegels.KOPPEL, "aan beide kanten hetzelfde veranderd: alleen onthouden");
        check(AgendaSyncRegels.besluit(beide, "b", "a", "a", "a") == AgendaSyncRegels.NAAR_GOOGLE, "alleen in Tools veranderd: naar Google");
        check(AgendaSyncRegels.besluit(beide, "a", "b", "a", "a") == AgendaSyncRegels.NAAR_TOOLS, "alleen in Google veranderd: naar Tools");
        check(AgendaSyncRegels.besluit(beide, "b", "c", "a", "a") == AgendaSyncRegels.CONFLICTEERT, "allebei anders veranderd: vragen");
        check(AgendaSyncRegels.besluit(cfg("beide", "tools", "naar"), "b", "c", "a", "a") == AgendaSyncRegels.NAAR_GOOGLE, "conflict, Tools gaat voor");
        check(AgendaSyncRegels.besluit(cfg("beide", "google", "naar"), "b", "c", "a", "a") == AgendaSyncRegels.NAAR_TOOLS, "conflict, Google gaat voor");
        check(AgendaSyncRegels.besluit(cfg("naar", "vragen", "naar"), "a", "b", "a", "a") == AgendaSyncRegels.NAAR_GOOGLE, "Tools → Google: een wijziging in Google wordt teruggezet");
        check(AgendaSyncRegels.besluit(cfg("van", "vragen", "naar"), "b", "a", "a", "a") == AgendaSyncRegels.NAAR_TOOLS, "Google → Tools: Tools volgt Google");
        check(AgendaSyncRegels.besluit(beide, "a", "b", "", "") == AgendaSyncRegels.NAAR_TOOLS, "oude koppeling zonder vingerafdruk: Google gaat voor (Tools schreef altijd meteen)");
        check(AgendaSyncRegels.besluit(beide, "a", "a", "", "") == AgendaSyncRegels.KOPPEL, "oude koppeling, gelijk: alleen onthouden");
        check(AgendaSyncRegels.besluit(cfg("uit", "vragen", "naar"), "a", "b", "a", "a") == AgendaSyncRegels.NIETS, "uit: niets");
        check(AgendaSyncRegels.besluit(cfg("naar", "vragen", "naar"), "a", "b", "a", "b") == AgendaSyncRegels.NIETS
                && AgendaSyncRegels.besluit(cfg("van", "vragen", "naar"), "a", "b", "a", "b") == AgendaSyncRegels.NIETS
                && AgendaSyncRegels.besluit(beide, "a", "b", "a", "b") == AgendaSyncRegels.NIETS,
                "blijvend verschil in vorm, niets veranderd sinds de vorige ronde: niet elke ronde opnieuw schrijven");

        check(AgendaSyncRegels.besluit(beide, "a", "b", "", "", true) == AgendaSyncRegels.CONFLICTEERT
                && AgendaSyncRegels.besluit(cfg("beide", "tools", "naar"), "a", "b", "", "", true) == AgendaSyncRegels.NAAR_GOOGLE
                && AgendaSyncRegels.besluit(cfg("beide", "google", "naar"), "a", "b", "", "", true) == AgendaSyncRegels.NAAR_TOOLS,
                "net gekoppeld en de rest verschilt: de keuze bij een conflict beslist, niet stil één kant overschrijven");
        check(AgendaSyncRegels.besluit(beide, "a", "a", "", "", true) == AgendaSyncRegels.KOPPEL, "net gekoppeld en gelijk: alleen onthouden");

        // vingerafdruk
        JSONObject a = afspraak("Tandarts", "2026-12-01", "14:00", "14:30");
        JSONObject b = new JSONObject(a.toString()).put("kleur", "#123456").put("kalender", "3").put("ext", 99).put("id", "aanders");
        check(AgendaSyncRegels.vinger(a).equals(AgendaSyncRegels.vinger(b)), "kleur, agenda en id tellen niet mee");
        check(!AgendaSyncRegels.vinger(a).equals(AgendaSyncRegels.vinger(new JSONObject(a.toString()).put("plek", "Rotterdam"))), "een andere plek telt wel");
        check(!AgendaSyncRegels.vinger(a).equals(AgendaSyncRegels.vinger(new JSONObject(a.toString()).put("van", "15:00"))), "een andere tijd telt wel");
        JSONObject heel1 = new JSONObject(a.toString()).put("heleDag", true), heel2 = new JSONObject(heel1.toString()).put("van", "").put("tot", "");
        check(AgendaSyncRegels.vinger(heel1).equals(AgendaSyncRegels.vinger(heel2)), "hele dag: tijden tellen niet");
        check(AgendaSyncRegels.vinger(new JSONObject(a.toString()).put("notitie", "regel\r\nregel")).equals(AgendaSyncRegels.vinger(new JSONObject(a.toString()).put("notitie", "regel\nregel"))),
                "regeleinden in de notitie tellen niet");
        check(AgendaSyncRegels.vinger(a).length() == 16, "vingerafdruk is 16 tekens");
        // dezelfde vorm aan beide kanten: geen schijnwijziging na schrijven en teruglezen
        JSONObject nacht = afspraak("Feest", "2026-12-31", "22:00", "00:00").put("eind", "2027-01-01");
        JSONObject nachtTelefoon = afspraak("Feest", "2026-12-31", "22:00", "00:00"); // zo leest de telefoon het terug: eind = de begindag
        check(AgendaSyncRegels.vinger(nacht).equals(AgendaSyncRegels.vinger(nachtTelefoon)), "tot middernacht: eind de dag erna, aan beide kanten hetzelfde");
        check("2027-01-01".equals(AgendaSyncRegels.vorm(nachtTelefoon).optString("eind")), "vorm: een afspraak tot middernacht eindigt de dag erna");
        StringBuilder lang = new StringBuilder();
        for (int i = 0; i < 5000; i++) lang.append('x');
        JSONObject lange = new JSONObject(a.toString()).put("notitie", lang.toString()).put("titel", lang.toString());
        JSONObject ingekort = new JSONObject(a.toString()).put("notitie", lang.substring(0, Agenda.MAX_NOTITIE)).put("titel", lang.substring(0, Agenda.MAX_TITEL));
        check(AgendaSyncRegels.vinger(lange).equals(AgendaSyncRegels.vinger(ingekort)), "een lange notitie en titel uit Google tellen zoals Tools ze bewaart");
        check(AgendaSyncRegels.vinger(new JSONObject(a.toString()).put("titel", "Tand\u0007arts")).equals(AgendaSyncRegels.vinger(a)), "stuurtekens tellen niet");

        // bereik
        String[] v = AgendaSyncRegels.venster(AgendaSyncRegels.normaal(new JSONObject().put("terug", 30).put("vooruit", 90)), "2026-11-27");
        check("2026-10-28".equals(v[0]) && "2027-02-25".equals(v[1]), "bereik: 30 dagen terug en 90 vooruit (" + v[0] + ".." + v[1] + ")");
        String[] alles = AgendaSyncRegels.venster(AgendaSyncRegels.normaal(new JSONObject().put("terug", -1).put("vooruit", -1)), "2026-11-27");
        check("1900-01-01".equals(alles[0]) && "2199-12-31".equals(alles[1]), "alles");
        check(AgendaSyncRegels.inVenster(afspraak("x", "2026-11-01", "09:00", "10:00"), v), "binnen het bereik");
        check(!AgendaSyncRegels.inVenster(afspraak("x", "2026-01-01", "09:00", "10:00"), v), "te oud");
        check(!AgendaSyncRegels.inVenster(afspraak("x", "2027-06-01", "09:00", "10:00"), v), "te ver vooruit");
        check(AgendaSyncRegels.inVenster(afspraak("x", "2025-01-06", "09:00", "10:00").put("herhaal", "w"), v), "een wekelijkse reeks van vorig jaar loopt nog door");
        check(!AgendaSyncRegels.inVenster(afspraak("x", "2025-01-06", "09:00", "10:00").put("herhaal", "w").put("totDag", "2025-06-01"), v), "een afgelopen reeks niet");
        check(!AgendaSyncRegels.inVenster(afspraak("x", "geen", "09:00", "10:00"), v), "onleesbare datum niet");

        // dezelfde afspraak herkennen
        check(AgendaSyncRegels.zelfde(a, afspraak("tandarts ", "2026-12-01", "14:00", "15:00")), "zelfde titel (hoofdletters, spatie), dag en begintijd");
        check(!AgendaSyncRegels.zelfde(a, afspraak("Tandarts", "2026-12-01", "15:00", "15:30")), "andere begintijd is een andere afspraak");
        check(!AgendaSyncRegels.zelfde(a, afspraak("Huisarts", "2026-12-01", "14:00", "14:30")), "andere titel");
        check(AgendaSyncRegels.zelfde(new JSONObject(a.toString()).put("heleDag", true).put("van", ""), new JSONObject(a.toString()).put("heleDag", true).put("van", "09:00")),
                "hele dag: tijd telt niet");
        check(!AgendaSyncRegels.zelfde(a, null), "niets is niet hetzelfde");

        // herhalingen die Tools niet precies kan tonen
        check(!AgendaSyncRegels.complex("") && !AgendaSyncRegels.complex("FREQ=WEEKLY") && !AgendaSyncRegels.complex("FREQ=DAILY;UNTIL=20261231T235959Z")
                && !AgendaSyncRegels.complex("FREQ=MONTHLY;INTERVAL=1;WKST=MO"), "eenvoudige herhaling");
        check(AgendaSyncRegels.complex("FREQ=WEEKLY;BYDAY=TU,TH") && AgendaSyncRegels.complex("FREQ=WEEKLY;INTERVAL=2") && AgendaSyncRegels.complex("FREQ=DAILY;COUNT=5")
                && AgendaSyncRegels.complex("FREQ=HOURLY"), "dinsdag en donderdag, om de week, vijf keer, elk uur");
        check(!AgendaSyncRegels.complex("FREQ=WEEKLY;WKST=SU;BYDAY=TU", "2026-12-01"), "zoals Google een wekelijkse afspraak op dinsdag schrijft: eenvoudig");
        check(AgendaSyncRegels.complex("FREQ=WEEKLY;BYDAY=WE", "2026-12-01"), "wekelijks op een andere dag dan het begin: niet eenvoudig");
        check(!AgendaSyncRegels.complex("FREQ=MONTHLY;BYMONTHDAY=1", "2026-12-01") && !AgendaSyncRegels.complex("FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=1", "2026-12-01"),
                "maandelijks en jaarlijks op de begindag: eenvoudig");
        check(AgendaSyncRegels.complex("FREQ=MONTHLY;BYDAY=1TU", "2026-12-01"), "elke eerste dinsdag van de maand: niet eenvoudig");

        // veiligheidsrem
        check(!AgendaSyncRegels.remRaakt(10, 10) && AgendaSyncRegels.remRaakt(11, 10) && !AgendaSyncRegels.remRaakt(500, 0), "rem: meer dan het getal, 0 = geen rem");
        check(AgendaSyncRegels.remRaakt(3, 0, 0) && AgendaSyncRegels.remRaakt(4, 10, 0) && !AgendaSyncRegels.remRaakt(4, 10, 20) && !AgendaSyncRegels.remRaakt(2, 0, 0),
                "rem: een agenda die opeens leeg is stopt altijd (vanaf 3), ook zonder rem");
        check(!AgendaSyncRegels.remRaakt(0, 5, 0), "niets verdwenen: geen rem");

        // logboek
        JSONArray log = new JSONArray();
        for (int i = 0; i < 25; i++) log = AgendaSyncRegels.logErbij(log, AgendaSyncRegels.logRegel(i, "plan", 5, new int[]{i, 0, 0, 0, 0, 0, 0, 0, 0, 0}, ""));
        check(log.length() == AgendaSyncRegels.MAX_LOG && log.optJSONObject(0).optLong("tijd") == 24 && log.optJSONObject(0).optInt("naarNieuw") == 24,
                "logboek: nieuwste eerst, hooguit " + AgendaSyncRegels.MAX_LOG);
        JSONObject regel = AgendaSyncRegels.logRegel(1, "hand", 3, new int[10], "recht");
        check("recht".equals(regel.optString("fout")) && !regel.toString().contains("Tandarts"), "logregel: alleen aantallen en een vaste foutcode");
        check(!AgendaSyncRegels.gewijzigd(new int[10]) && AgendaSyncRegels.gewijzigd(new int[]{0, 0, 1, 0, 0, 0, 0, 0, 0, 0})
                && !AgendaSyncRegels.gewijzigd(new int[]{0, 0, 0, 0, 0, 0, 0, 3, 0, 0}), "gewijzigd: alleen echte wijzigingen, een conflict niet");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
