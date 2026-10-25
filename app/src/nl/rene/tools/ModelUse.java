package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

/** Wanneer een model (taalmodel, zoekmodel, spraakmodel) voor het laatst gebruikt is: voor Modellen beheren. */
final class ModelUse {

    private ModelUse() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("models", Context.MODE_PRIVATE); }

    /** Gebruikt: hooguit eens per uur wegschrijven (wordt bij elke vraag aangeroepen). */
    static void touch(Context c, String key) {
        try {
            long now = System.currentTimeMillis();
            if (now - prefs(c).getLong(key, 0) > 3_600_000L) prefs(c).edit().putLong(key, now).apply();
        } catch (Throwable ignored) { }
    }

    static long last(Context c, String key) { return prefs(c).getLong(key, 0); }

    /** Weg (na verwijderen): niet meer "laatst gebruikt" tonen als hij later opnieuw gedownload wordt. */
    static void forget(Context c, String key) { prefs(c).edit().remove(key).apply(); }
}
