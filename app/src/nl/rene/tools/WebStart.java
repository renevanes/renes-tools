package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Startbewaking van een gedownloade interface. De pagina meldt zich bij het starten (Android.uiReady). Blijft dat bij een
 * gedownloade versie twee keer achter elkaar uit, dan is die versie afgekeurd: de app gebruikt de vorige volledige versie
 * (of de meegeleverde) tot er een nieuwere is. De afgekeurde versie wordt niet opnieuw gedownload.
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

    /** De afgekeurde interfaceversie (versiecode), of 0. */
    static int afgekeurd(Context c) { return prefs(c).getInt("webAfgekeurd", 0); }

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
        if (weg) e.putInt("webAfgekeurd", code);
        e.commit();
        App.log(c, "UPDATE", weg ? "interface " + code + " start niet: terug naar de vorige versie" : "interface " + code + " meldde zich niet");
        return weg;
    }
}
