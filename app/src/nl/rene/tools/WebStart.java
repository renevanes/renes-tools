package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Startbewaking van een gedownloade interface. De pagina meldt zich bij het starten (Android.uiReady). Blijft dat bij een
 * gedownloade versie twee keer achter elkaar uit, dan is die versie afgekeurd: de app gebruikt de vorige volledige versie
 * (of de meegeleverde) tot er een nieuwere is. De afgekeurde versie wordt niet opnieuw gedownload. De lijst met afgekeurde
 * versies groeit alleen: twee versies die allebei niet starten wisselen elkaar dus nooit af, dan komt de meegeleverde.
 * Het zuivere deel (stand lezen en tellen) is getest in WebStartTest.
 */
final class WebStart {

    private WebStart() { }

    /** Zo vaak mag een versie niet starten. */
    static final int MAX = 2;
    /** Zo lang krijgt de pagina om zich te melden (ruim: een oude telefoon leest eerst een paar MB in). */
    static final long WACHT_MS = 15_000L;

    /** Aantal mislukte starts van deze versie in de stand "code:aantal"; een stand van een andere versie telt niet. */
    static int mislukt(String stand, int code) {
        if (stand == null || code <= 0) return 0;
        int i = stand.indexOf(':');
        if (i <= 0) return 0;
        try {
            if (Integer.parseInt(stand.substring(0, i)) != code) return 0;
            int n = Integer.parseInt(stand.substring(i + 1));
            return n < 0 ? 0 : Math.min(n, MAX);
        } catch (NumberFormatException e) { return 0; }
    }

    static String naMislukt(String stand, int code) { return code + ":" + Math.min(MAX, mislukt(stand, code) + 1); }

    static boolean afgekeurd(String stand, int code) { return code > 0 && mislukt(stand, code) >= MAX; }

    private static SharedPreferences prefs(Context c) { return Updater.prefs(c); }

    /** Welke versiecodes in de bewaarde lijst ("12,14") staan; alleen geldige getallen boven de meegeleverde versie tellen. Zuiver. */
    static java.util.Set<Integer> lijst(String bewaard, int meegeleverd) {
        java.util.Set<Integer> s = new java.util.TreeSet<>();
        if (bewaard != null) for (String d : bewaard.split(",")) {
            try { int c = Integer.parseInt(d.trim()); if (c > meegeleverd && c > 0) s.add(c); } catch (NumberFormatException ignored) { }
        }
        return s;
    }

    /** De lijst met versie code erbij; de lijst groeit alleen (wat de meegeleverde versie heeft ingehaald valt eraf). Zuiver. */
    static String metAfgekeurd(String bewaard, int code, int meegeleverd) {
        java.util.Set<Integer> s = lijst(bewaard, meegeleverd);
        if (code > meegeleverd && code > 0) s.add(code);
        StringBuilder b = new StringBuilder();
        for (int c : s) { if (b.length() > 0) b.append(','); b.append(c); }
        return b.toString();
    }

    /** De afgekeurde interfaceversies (versiecodes). Een versie blijft afgekeurd tot de app zelf nieuwer is. */
    static java.util.Set<Integer> afgekeurd(Context c) { return lijst(prefs(c).getString("webAfgekeurd2", ""), Version.CODE); }

    /** De pagina van deze versie heeft zich gemeld: de teller begint opnieuw. */
    static void gemeld(Context c, int code) {
        SharedPreferences p = prefs(c);
        if (code > 0 && mislukt(p.getString("webStart", ""), code) > 0) p.edit().remove("webStart").apply();
    }

    /** De pagina van deze versie meldde zich niet. Geeft true als de versie daarmee is afgekeurd. */
    static synchronized boolean nietGestart(Context c, int code) {
        if (code <= 0) return false;
        SharedPreferences p = prefs(c);
        String stand = naMislukt(p.getString("webStart", ""), code);
        boolean weg = afgekeurd(stand, code);
        SharedPreferences.Editor e = p.edit().putString("webStart", stand);
        if (weg) e.putString("webAfgekeurd2", metAfgekeurd(p.getString("webAfgekeurd2", ""), code, Version.CODE));
        e.commit();
        App.log(c, "UPDATE", weg ? "interface " + code + " start niet: terug naar de vorige versie" : "interface " + code + " meldde zich niet");
        return weg;
    }
}
