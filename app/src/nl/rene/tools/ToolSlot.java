package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Extra ontgrendelen per tool: welke tools bij het openen eerst de schermvergrendeling van de telefoon vragen, net als
 * Stamgegevens. Aanzetten mag meteen; uitzetten pas na bevestigen met de schermvergrendeling (de brug regelt dat).
 * Welke schermen bij een tool horen en wanneer hij weer op slot gaat, regelt de interface (26b-extra-ontgrendelen.js).
 */
final class ToolSlot {

    private ToolSlot() { }

    static final String PREFS = "toolslot";

    /** Zo lang mag een eigen scherm van de app (kiezer, PDF-viewer, de vraag om te ontgrendelen) open staan zonder dat de tool op slot gaat. */
    static final long KIES_MS = 120_000L;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Alleen tools uit het tooloverzicht; gesorteerd, zonder dubbele. */
    static Set<String> geldig(Collection<String> in) {
        Set<String> out = new TreeSet<>();
        if (in != null) for (String t : in) if (Solo.known(t)) out.add(t);
        return out;
    }

    static Set<String> aan(Context c) { return geldig(prefs(c).getStringSet("tools", Collections.<String>emptySet())); }

    /** De nieuwe lijst na aan- of uitzetten van één tool (een onbekende tool verandert niets). */
    static Set<String> met(Collection<String> huidig, String tool, boolean on) {
        Set<String> s = geldig(huidig);
        if (!Solo.known(tool)) return s;
        if (on) s.add(tool); else s.remove(tool);
        return s;
    }

    static void zet(Context c, String tool, boolean on) {
        prefs(c).edit().putStringSet("tools", met(aan(c), tool, on)).apply();
    }

    /** Naam van de tool voor de vraag om te ontgrendelen: korte, gewone tekst. */
    static String naam(String s) {
        String n = s == null ? "" : s.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    /** Moet de tool op slot na terugkomen in de app? Alleen als hij weg was via een eigen scherm en dat te lang duurde. */
    static boolean teLang(boolean intern, long weg, long nu) { return intern && weg > 0 && nu - weg > KIES_MS; }
}
