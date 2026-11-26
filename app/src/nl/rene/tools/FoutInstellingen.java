package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Keuzes van Instellingen → Foutrapport en statistieken die Rene zelf maakt (wens van Rene, 26-11-2026): hoe vaak de
 * statistieken gaan, het tempo van het foutlogboek, alleen via wifi, en of tools een diagnose vastleggen en hoe lang.
 * Eigen instellingenbestand "foutrapport" (gaat mee in de backup); de toestand en de sleutel blijven in "statistiek"
 * (gaat bewust niet mee). Het zuivere deel ({@link #normaal}, {@link #per}) is getest in FoutInstellingenTest.
 */
final class FoutInstellingen {
    private FoutInstellingen() { }

    static final String PREFS = "foutrapport";
    static final long DAG = 86_400_000L;

    /** Standaardwaarden: zoals het was vóór deze keuzes. */
    static final int ELKE = 7, BEWAAR_MIN = 60;
    static final String TEMPO = "snel";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Statistieken: elke zoveel dagen (1, 7 of 30). */
    static int elke(Context c) { return Integer.parseInt(waarde(prefs(c), "elke")); }
    /** Vanzelf versturen (statistieken, foutlogboek, diagnose) alleen via wifi of een ander onbeperkt netwerk. */
    static boolean wifi(Context c) { return "true".equals(waarde(prefs(c), "wifi")); }
    /** Foutlogboek: "snel" (na een probleem binnen een kwartier, na een vastloper na een paar minuten), "uur" of "dag". */
    static String tempo(Context c) { return waarde(prefs(c), "tempo"); }
    /** Leggen tools een diagnose vast (alleen in het geheugen)? */
    static boolean diagLeg(Context c) { return !"false".equals(waarde(prefs(c), "diagLeg")); }
    /** Zo lang blijft een diagnose hooguit in het geheugen, in minuten (15, 60 of 240). */
    static int diagBewaar(Context c) { return Integer.parseInt(waarde(prefs(c), "diagBewaar")); }

    private static String waarde(SharedPreferences p, String sleutel) {
        String v = normaal(sleutel, p.getString(sleutel, null));
        return v != null ? v : standaard(sleutel);
    }

    static String standaard(String sleutel) {
        switch (sleutel) {
            case "elke": return String.valueOf(ELKE);
            case "wifi": return "false";
            case "tempo": return TEMPO;
            case "diagLeg": return "true";
            case "diagBewaar": return String.valueOf(BEWAAR_MIN);
            default: return null;
        }
    }

    /** Een toegestane waarde voor deze sleutel, of null (onbekende sleutel of waarde). */
    static String normaal(String sleutel, String v) {
        if (sleutel == null || v == null) return null;
        v = v.trim();
        switch (sleutel) {
            case "elke": return v.equals("1") || v.equals("7") || v.equals("30") ? v : null;
            case "wifi": case "diagLeg": return v.equals("true") || v.equals("false") ? v : null;
            case "tempo": return v.equals("snel") || v.equals("uur") || v.equals("dag") ? v : null;
            case "diagBewaar": return v.equals("15") || v.equals("60") || v.equals("240") ? v : null;
            default: return null;
        }
    }

    /** Hooguit eens per zoveel ms het foutlogboek versturen, bij dit tempo (mis = een melding over iets wat misging). */
    static long per(String tempo, boolean mis) {
        if ("dag".equals(tempo)) return DAG;
        if ("uur".equals(tempo)) return Logboek.UUR;
        return mis ? Logboek.KWARTIER : Logboek.UUR;
    }

    /** Statistieken weer versturen? Een paar uur speling: het dagelijkse onderhoud loopt niet elke dag op hetzelfde moment. */
    static boolean statAanDeBeurt(long laatst, long nu, int elke) {
        return laatst <= 0 || laatst > nu || nu - laatst >= elke * DAG - 3 * 3_600_000L;
    }

    /** Een keuze bewaren; "" als het lukte, anders de reden. Zet ook in gang wat ervan afhangt. */
    static String zet(Context c, String sleutel, String v) {
        String n = normaal(sleutel, v);
        if (n == null) return "Onbekende keuze";
        prefs(c).edit().putString(sleutel, n).apply();
        if (sleutel.equals("diagLeg") && n.equals("false")) Diagnose.wis();
        if (sleutel.equals("diagBewaar")) Diagnose.houdbaar = Integer.parseInt(n) * 60_000L;
        if (sleutel.equals("tempo") || sleutel.equals("wifi")) Logboek.herplan(c);
        return "";
    }

    /** {elke, wifi, tempo, diagLeg, diagBewaar} voor het scherm. */
    static JSONObject staat(Context c) {
        try {
            return new JSONObject().put("elke", elke(c)).put("wifi", wifi(c)).put("tempo", tempo(c))
                    .put("diagLeg", diagLeg(c)).put("diagBewaar", diagBewaar(c));
        } catch (Exception e) { return new JSONObject(); }
    }

    /** Mag er nu vanzelf iets weg? Met "alleen via wifi" niet over een netwerk dat Android als beperkt meldt (mobiele data). */
    static boolean netOk(Context c) {
        if (!wifi(c)) return true;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected() && !cm.isActiveNetworkMetered();
        } catch (Throwable t) { return false; }
    }
}
