package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Extra ontgrendelen per tool: welke tools bij het openen eerst de schermvergrendeling van de telefoon vragen, net als
 * Stamgegevens. Aanzetten mag meteen; uitzetten pas na bevestigen met de schermvergrendeling (de brug regelt dat).
 * Welke schermen bij een tool horen en wanneer hij weer op slot gaat, regelt de interface (26b-extra-ontgrendelen.js).
 * Kluis, Bankzaken en Stamgegevens (EIGEN) hebben een eigen ontgrendeling: die staat standaard aan en wordt apart
 * uitgezet ("eigenUit"). Uit = de tool opent zonder vraag; de sleutels van Bankzaken en Stamgegevens werken dan nog steeds
 * alleen kort na het ontgrendelen van de telefoon (Ontgrendeld).
 */
final class ToolSlot {

    private ToolSlot() { }

    static final String PREFS = "toolslot";

    static final Set<String> EIGEN = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList("bank", "kluis", "masterdata")));

    /** Zo lang mag een eigen scherm van de app (kiezer, PDF-viewer, de vraag om te ontgrendelen) open staan zonder dat de tool op slot gaat. */
    static final long KIES_MS = 120_000L;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Alleen tools uit het tooloverzicht; gesorteerd, zonder dubbele. */
    static Set<String> geldig(Collection<String> in) {
        Set<String> out = new TreeSet<>();
        if (in != null) for (String t : in) if (Solo.known(t)) out.add(t);
        return out;
    }

    /** De tools met extra ontgrendelen: de gekozen tools, plus de tools met een eigen ontgrendeling die niet uit staan. */
    static Set<String> samen(Collection<String> tools, Collection<String> eigenUit) {
        Set<String> s = geldig(tools);
        s.removeAll(EIGEN);
        for (String e : EIGEN) if (Solo.known(e) && (eigenUit == null || !eigenUit.contains(e))) s.add(e);
        return s;
    }

    static Set<String> aan(Context c) {
        SharedPreferences p = prefs(c);
        return samen(p.getStringSet("tools", Collections.<String>emptySet()), p.getStringSet("eigenUit", Collections.<String>emptySet()));
    }

    /** Opent deze tool met een eigen ontgrendeling zonder te vragen? */
    static boolean eigenUit(Context c, String tool) { return EIGEN.contains(tool) && !aan(c).contains(tool); }

    /** De nieuwe lijst na aan- of uitzetten van één tool (een onbekende tool verandert niets). */
    static Set<String> met(Collection<String> huidig, String tool, boolean on) {
        Set<String> s = geldig(huidig);
        if (!Solo.known(tool)) return s;
        if (on) s.add(tool); else s.remove(tool);
        return s;
    }

    static synchronized void zet(Context c, String tool, boolean on) {
        SharedPreferences p = prefs(c);
        if (EIGEN.contains(tool)) {
            Set<String> uit = new TreeSet<>(p.getStringSet("eigenUit", Collections.<String>emptySet()));
            uit.retainAll(EIGEN);
            if (on) uit.remove(tool); else uit.add(tool);
            p.edit().putStringSet("eigenUit", uit).apply();
            return;
        }
        Set<String> tools = met(p.getStringSet("tools", Collections.<String>emptySet()), tool, on);
        tools.removeAll(EIGEN);
        p.edit().putStringSet("tools", tools).apply();
    }

    /** Naam van de tool voor de vraag om te ontgrendelen: korte, gewone tekst. */
    static String naam(String s) {
        String n = s == null ? "" : s.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    /** Moet de tool op slot na terugkomen in de app? Alleen als hij weg was via een eigen scherm en dat te lang duurde. */
    static boolean teLang(boolean intern, long weg, long nu) { return intern && weg > 0 && nu - weg > KIES_MS; }
}
