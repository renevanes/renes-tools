package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Synchroniseren van Agenda met een agenda op de telefoon (meestal Google): de instellingen, wat een ronde beslist en de
 * vingerafdruk van een afspraak. Zuiver rekenwerk, getest in AgendaSyncTest. Het lezen en schrijven doet AgendaSync.
 *
 * Een gekoppelde afspraak heeft in agenda.json kalender (de gekozen agenda) en ext (het id daar), plus "sync": {t, g},
 * de vingerafdruk van de kant van Tools en van Google na de vorige ronde. Zo ziet een ronde aan welke kant iets veranderde.
 */
final class AgendaSyncRegels {
    private AgendaSyncRegels() { }

    /** uit, naar = Tools → Google, van = Google → Tools, beide. */
    static final String[] RICHTINGEN = {"uit", "naar", "van", "beide"};
    /** Minuten tussen twee rondes; 0 = alleen met de hand (Bijwerken, Nu synchroniseren, bij openen). */
    static final int[] ELKE = {0, 15, 30, 60, 180, 360, 720, 1440};
    /** Dagen terug; -1 = alles. */
    static final int[] TERUG = {0, 7, 30, 90, 365, -1};
    /** Dagen vooruit; -1 = alles. */
    static final int[] VOORUIT = {30, 90, 365, 730, -1};
    /** Wat verwijderen doet: naar = weg in Tools, ook weg in Google; van = weg in Google, ook weg in Tools; beide; nooit. */
    static final String[] WISSEN = {"naar", "van", "beide", "nooit"};
    /** Allebei gewijzigd: vragen (niets doen tot Rene kiest), tools of google gaat voor. */
    static final String[] CONFLICT = {"vragen", "tools", "google"};
    /** Veiligheidsrem: hooguit zoveel afspraken in Tools verwijderen per ronde; 0 = geen rem. */
    static final int[] REM = {5, 10, 25, 50, 0};
    /** Meldingen: fouten (en conflicten), altijd (ook na een ronde met wijzigingen), nooit. */
    static final String[] MELD = {"fouten", "altijd", "nooit"};

    static final int MAX_LOG = 20;
    static final int MAX_CONFLICT = 50;
    static final int MAX_WEG = 500;
    /** Hooguit zoveel afspraken lezen of schrijven per ronde: de rest volgt de volgende ronde. */
    static final int MAX_PER_RONDE = 3000;

    static boolean in(String[] lijst, String v) {
        for (String s : lijst) if (s.equals(v)) return true;
        return false;
    }

    static boolean in(int[] lijst, int v) {
        for (int s : lijst) if (s == v) return true;
        return false;
    }

    static JSONObject standaard() {
        try {
            return new JSONObject().put("richting", "uit").put("kal", "").put("kalAccount", "").put("kalNaam", "").put("kalGid", "")
                    .put("elke", 60).put("open", true).put("laden", false).put("googleNu", true)
                    .put("terug", 30).put("vooruit", -1).put("wissen", "naar").put("conflict", "vragen")
                    .put("rem", 10).put("meld", "fouten");
        } catch (JSONException e) { return new JSONObject(); }
    }

    /** Alle instellingen, met de standaard voor wat ontbreekt of niet klopt. */
    static JSONObject normaal(JSONObject in) {
        JSONObject o = standaard();
        if (in == null) return o;
        try {
            String r = in.optString("richting", "uit");
            o.put("richting", in(RICHTINGEN, r) ? r : "uit");
            String k = in.optString("kal", "");
            o.put("kal", k.matches("[1-9]\\d{0,17}") ? k : "");
            o.put("kalAccount", Agenda.tekst(in.optString("kalAccount", ""), 200, false));
            o.put("kalNaam", Agenda.tekst(in.optString("kalNaam", ""), 200, false));
            o.put("kalGid", Agenda.tekst(in.optString("kalGid", ""), 300, false));
            int e = in.optInt("elke", 60);
            o.put("elke", in(ELKE, e) ? e : 60);
            o.put("open", in.optBoolean("open", true));
            o.put("laden", in.optBoolean("laden", false));
            o.put("googleNu", in.optBoolean("googleNu", true));
            int t = in.optInt("terug", 30);
            o.put("terug", in(TERUG, t) ? t : 30);
            int v = in.optInt("vooruit", -1);
            o.put("vooruit", in(VOORUIT, v) ? v : -1);
            String w = in.optString("wissen", "naar");
            o.put("wissen", in(WISSEN, w) ? w : "naar");
            String c = in.optString("conflict", "vragen");
            o.put("conflict", in(CONFLICT, c) ? c : "vragen");
            int rem = in.optInt("rem", 10);
            o.put("rem", in(REM, rem) ? rem : 10);
            String m = in.optString("meld", "fouten");
            o.put("meld", in(MELD, m) ? m : "fouten");
        } catch (JSONException ignored) { }
        return o;
    }

    static boolean aan(JSONObject cfg) { return cfg != null && !"uit".equals(cfg.optString("richting", "uit")); }
    static boolean naarGoogle(JSONObject cfg) { String r = cfg == null ? "" : cfg.optString("richting"); return "naar".equals(r) || "beide".equals(r); }
    static boolean vanGoogle(JSONObject cfg) { String r = cfg == null ? "" : cfg.optString("richting"); return "van".equals(r) || "beide".equals(r); }

    /** Weg in Tools: ook weg in Google? */
    static boolean wisInGoogle(JSONObject cfg) {
        if (!naarGoogle(cfg)) return false;
        String w = cfg.optString("wissen");
        return "naar".equals(w) || "beide".equals(w);
    }

    /** Weg in Google: ook weg in Tools? */
    static boolean wisInTools(JSONObject cfg) {
        if (!vanGoogle(cfg)) return false;
        String w = cfg.optString("wissen");
        return "van".equals(w) || "beide".equals(w);
    }

    /** Het bereik van de afspraken die meedoen: {van, tot}, inclusief. */
    static String[] venster(JSONObject cfg, String vandaag) {
        int t = cfg == null ? 30 : cfg.optInt("terug", 30), v = cfg == null ? -1 : cfg.optInt("vooruit", -1);
        String van = t < 0 || !Agenda.dagOk(vandaag) ? "1900-01-01" : Regio.dayAdd(vandaag, -t);
        String tot = v < 0 || !Agenda.dagOk(vandaag) ? "2199-12-31" : Regio.dayAdd(vandaag, v);
        if (!Agenda.dagOk(van)) van = "1900-01-01";
        if (!Agenda.dagOk(tot)) tot = "2199-12-31";
        return new String[]{van, tot};
    }

    /** Raakt de afspraak (of een herhaling ervan) het bereik? */
    static boolean inVenster(JSONObject it, String[] v) {
        if (it == null || v == null) return false;
        String dag = it.optString("dag");
        if (!Agenda.dagOk(dag)) return false;
        String eind = it.optString("eind");
        if (!Agenda.dagOk(eind) || eind.compareTo(dag) < 0) eind = dag;
        String her = it.optString("herhaal");
        String laatst = eind;
        if (!her.isEmpty()) {
            String tot = it.optString("totDag");
            laatst = Agenda.dagOk(tot) ? (tot.compareTo(eind) > 0 ? tot : eind) : "9999-12-31";
        }
        return laatst.compareTo(v[0]) >= 0 && dag.compareTo(v[1]) <= 0;
    }

    /**
     * Dezelfde vorm aan beide kanten: de grenzen en het opschonen van Tools (Agenda.schoon), en een afspraak met een tijd
     * die om middernacht of later op de dag eindigt, eindigt de dag erna. Zo geeft een verschil in vorm geen schijnwijziging.
     */
    static JSONObject vorm(JSONObject it) {
        JSONObject o = new JSONObject();
        if (it == null) return o;
        try {
            boolean heel = it.optBoolean("heleDag");
            String dag = it.optString("dag"), eind = it.optString("eind");
            if (!Agenda.dagOk(eind) || eind.compareTo(dag) < 0) eind = dag;
            String van = heel ? "" : it.optString("van"), tot = heel ? "" : it.optString("tot");
            if (!heel && eind.equals(dag) && Agenda.hmOk(van) && Agenda.hmOk(tot) && tot.compareTo(van) <= 0 && Agenda.dagOk(dag)) eind = Regio.dayAdd(dag, 1);
            String her = it.optString("herhaal");
            o.put("titel", Agenda.tekst(it.optString("titel"), Agenda.MAX_TITEL, false)).put("plek", Agenda.tekst(it.optString("plek"), Agenda.MAX_PLEK, false))
                    .put("notitie", Agenda.tekst(it.optString("notitie").replace("\r\n", "\n"), Agenda.MAX_NOTITIE, true))
                    .put("dag", dag).put("eind", eind).put("van", van).put("tot", tot).put("heleDag", heel)
                    .put("herhaal", her).put("totDag", her.isEmpty() ? "" : it.optString("totDag"));
        } catch (JSONException ignored) { }
        return o;
    }

    /** Wat beide kanten gemeen hebben, in de vorm van Tools, vast op een rij. Kleur, agenda en id tellen niet mee. */
    static String inhoud(JSONObject ruw) {
        if (ruw == null) return "";
        JSONObject it = vorm(ruw);
        StringBuilder b = new StringBuilder();
        for (String k : new String[]{"titel", "plek", "notitie", "dag", "eind", "van", "tot"}) b.append(it.optString(k)).append('\u0001');
        b.append(it.optBoolean("heleDag") ? '1' : '0').append('\u0001').append(it.optString("herhaal")).append('\u0001').append(it.optString("totDag"));
        return b.toString();
    }

    /** Korte vingerafdruk (SHA-256, 16 tekens) van de inhoud. */
    static String vinger(JSONObject it) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(inhoud(it).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format(Locale.ROOT, "%02x", h[i] & 0xff));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(inhoud(it).hashCode()); }
    }

    static final int NIETS = 0, NAAR_GOOGLE = 1, NAAR_TOOLS = 2, CONFLICTEERT = 3, KOPPEL = 4;

    /**
     * Wat er met een gekoppelde afspraak gebeurt. t en g: de vingerafdruk nu (Tools, Google); tOud en gOud: na de vorige
     * ronde (leeg = nooit samen gesynchroniseerd, bijv. met de hand in deze agenda gezet).
     * KOPPEL = alleen de vingerafdrukken bijwerken (de inhoud is al gelijk).
     */
    static int besluit(JSONObject cfg, String t, String g, String tOud, String gOud) { return besluit(cfg, t, g, tOud, gOud, false); }

    /**
     * vers: net voor het eerst gekoppeld (dezelfde titel, dag en tijd aan beide kanten gevonden). Verschilt de rest, dan
     * is bij beide kanten niet te zeggen welke de goede is: dan beslist de keuze bij een conflict.
     */
    static int besluit(JSONObject cfg, String t, String g, String tOud, String gOud, boolean vers) {
        if (!aan(cfg)) return NIETS;
        boolean gelijk = t.equals(g);
        boolean oud = !tOud.isEmpty() && !gOud.isEmpty();
        if (gelijk) return oud && t.equals(tOud) && g.equals(gOud) ? NIETS : KOPPEL;
        // sinds de vorige ronde aan geen van beide kanten iets veranderd: een blijvend verschil in vorm (bijv. een notitie
        // die in Tools korter moet) niet elke ronde opnieuw schrijven
        if (oud && t.equals(tOud) && g.equals(gOud)) return NIETS;
        String r = cfg.optString("richting");
        if ("naar".equals(r)) return NAAR_GOOGLE; // Google volgt Tools
        if ("van".equals(r)) return NAAR_TOOLS;   // Tools volgt Google
        if (!oud && !vers) return NAAR_TOOLS; // oudere koppeling: Tools schreef zelf altijd meteen, dus het verschil komt van Google
        if (!oud) {
            String c = cfg.optString("conflict");
            return "tools".equals(c) ? NAAR_GOOGLE : "google".equals(c) ? NAAR_TOOLS : CONFLICTEERT;
        }
        boolean tAnders = !t.equals(tOud), gAnders = !g.equals(gOud);
        if (tAnders && !gAnders) return NAAR_GOOGLE;
        if (gAnders && !tAnders) return NAAR_TOOLS;
        if (!tAnders) return KOPPEL; // kan niet (gelijk is al afgevangen), voor de zekerheid niets overschrijven
        String c = cfg.optString("conflict");
        if ("tools".equals(c)) return NAAR_GOOGLE;
        if ("google".equals(c)) return NAAR_TOOLS;
        return CONFLICTEERT;
    }

    /** Dezelfde afspraak aan beide kanten (na terugzetten op een nieuwe telefoon, of opnieuw aanzetten): titel, dag, tijd. */
    static boolean zelfde(JSONObject a, JSONObject b) {
        if (a == null || b == null) return false;
        if (!Agenda.vouw(a.optString("titel")).trim().equals(Agenda.vouw(b.optString("titel")).trim())) return false;
        if (!a.optString("dag").equals(b.optString("dag"))) return false;
        boolean ha = a.optBoolean("heleDag"), hb = b.optBoolean("heleDag");
        if (ha != hb) return false;
        return ha || a.optString("van").equals(b.optString("van"));
    }

    private static final String[] WEEKDAG = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};

    /** Een herhaling die Tools niet precies kan tonen (bijv. elke dinsdag en donderdag, of elke 2 weken). */
    static boolean complex(String rrule) { return complex(rrule, ""); }

    /**
     * Zoals complex(rrule), maar met de begindag: BYDAY gelijk aan de weekdag van het begin (zo schrijft Google een gewone
     * wekelijkse herhaling), of BYMONTHDAY/BYMONTH gelijk aan de dag en maand van het begin, is nog steeds eenvoudig.
     */
    static boolean complex(String rrule, String dag) {
        if (rrule == null || rrule.trim().isEmpty()) return false;
        String code = Agenda.herhaalCode(rrule);
        if (code.isEmpty()) return true;
        int[] d = Agenda.ymd(dag);
        for (String deel : rrule.toUpperCase(Locale.ROOT).split(";")) {
            String x = deel.trim();
            if (x.isEmpty() || x.startsWith("FREQ=") || x.startsWith("UNTIL=") || x.startsWith("WKST=") || x.equals("INTERVAL=1")) continue;
            if (d != null && Agenda.dagOk(dag)) {
                if ("w".equals(code) && x.equals("BYDAY=" + WEEKDAG[Agenda.jsDow(dag)])) continue;
                if (("m".equals(code) || "y".equals(code)) && x.equals("BYMONTHDAY=" + d[2])) continue;
                if ("y".equals(code) && x.equals("BYMONTH=" + d[1])) continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Stopt de veiligheidsrem? verdwenen: gekoppelde afspraken die niet meer in de agenda staan; gelezen: hoeveel afspraken
     * de agenda nu gaf. Meer dan rem (0 = geen rem), of de agenda is opeens helemaal leeg (dan is er eerder iets mis met
     * de telefoon dan dat Rene alles weggooide).
     */
    static boolean remRaakt(int verdwenen, int rem, int gelezen) {
        if (verdwenen <= 0) return false;
        if (gelezen == 0 && verdwenen >= 3) return true;
        return rem > 0 && verdwenen > rem;
    }

    static boolean remRaakt(int teWissen, int rem) { return remRaakt(teWissen, rem, -1); }

    /** Een regel voor het logboek van de rondes: alleen tijden, aantallen en vaste woorden. */
    static JSONObject logRegel(long tijd, String waarom, long duurMs, int[] n, String fout) throws JSONException {
        JSONObject o = new JSONObject().put("tijd", tijd).put("waarom", waarom == null ? "" : waarom).put("duur", Math.max(0, duurMs));
        String[] namen = {"naarNieuw", "naarBij", "vanNieuw", "vanBij", "wegTools", "wegGoogle", "los", "conflict", "mislukt", "rem"};
        for (int i = 0; i < namen.length && n != null && i < n.length; i++) o.put(namen[i], n[i]);
        if (fout != null && !fout.isEmpty()) o.put("fout", fout);
        return o;
    }

    /** Nieuwste eerst, hooguit MAX_LOG. */
    static JSONArray logErbij(JSONArray log, JSONObject regel) {
        JSONArray uit = new JSONArray();
        if (regel != null) uit.put(regel);
        if (log != null) for (int i = 0; i < log.length() && uit.length() < MAX_LOG; i++) {
            JSONObject r = log.optJSONObject(i);
            if (r != null) uit.put(r);
        }
        return uit;
    }

    /** Is er iets veranderd in deze ronde (voor een melding met "altijd")? */
    static boolean gewijzigd(int[] n) {
        if (n == null) return false;
        for (int i = 0; i <= 6 && i < n.length; i++) if (n[i] > 0) return true;
        return false;
    }
}
