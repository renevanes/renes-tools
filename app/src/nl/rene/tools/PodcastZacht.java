package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import org.json.JSONObject;

/**
 * Zacht beginnen (wens van Rene, 28-11-2026): begint een luisterbeurt in de avond (of altijd), dan zet de speler het
 * volume eerst op een zacht niveau: zacht, maar nog net hoorbaar. Alleen aan het begin van een beurt (er speelde een
 * tijd niets): daarna is het volume van Rene, ook als de speler vanzelf doorgaat met de volgende aflevering. Het volume
 * gaat alleen omlaag, of van stil naar het zachte niveau; wat al zachter staat blijft zo. Niet in de auto en niet als
 * het volume op het toestel vast staat. Het zuivere deel is getest in PodcastZachtTest.
 */
final class PodcastZacht {
    private PodcastZacht() { }

    static final String AVOND = "avond", ALTIJD = "altijd", UIT = "uit";
    static final int VAN = 20 * 60, TOT = 7 * 60, PCT = 7, PCT_MIN = 1, PCT_MAX = 40;
    /** Zo lang niets gespeeld = een nieuwe luisterbeurt. */
    static final long PAUZE_MS = 30 * 60_000L;

    // ---- zuiver ----

    static String wijze(String w) { return ALTIJD.equals(w) || UIT.equals(w) ? w : AVOND; }
    static int minuut(int m, int std) { return m < 0 || m >= 24 * 60 ? std : m; }
    static int pct(int p) { return p < PCT_MIN || p > PCT_MAX ? PCT : p; }

    /** Geldt zacht beginnen op dit moment van de dag (minuten sinds middernacht)? Van 20:00 tot 07:00 loopt over de nacht. */
    static boolean nu(String wijze, int van, int tot, int minuutVanDag) {
        wijze = wijze(wijze);
        if (UIT.equals(wijze)) return false;
        if (ALTIJD.equals(wijze)) return true;
        if (van == tot) return true;
        return van < tot ? minuutVanDag >= van && minuutVanDag < tot : minuutVanDag >= van || minuutVanDag < tot;
    }

    /** Een nieuwe luisterbeurt: er speelde nog niets, of langer dan PAUZE_MS niet (of de klok is teruggezet). */
    static boolean nieuweBeurt(long nu, long speeldeTot) { return speeldeTot <= 0 || speeldeTot > nu || nu - speeldeTot >= PAUZE_MS; }

    /** Het zachte niveau op een schaal van 0..max: minstens 1 (hoorbaar). */
    static int niveau(int max, int pct) { return max <= 0 ? 0 : Math.max(1, Math.min(max, Math.round(max * pct(pct) / 100f))); }

    /** Waar het volume heen moet, of -1 als het zo blijft: alleen omlaag, of van stil naar het zachte niveau. */
    static int doel(int nu, int max, int pct) {
        int z = niveau(max, pct);
        if (max <= 0 || z <= 0) return -1;
        return nu > z || nu <= 0 ? z : -1;
    }

    // ---- instellingen (in de instellingen van Podcasts) ----

    static JSONObject staat(Context c) {
        SharedPreferences p = Podcasts.prefs(c);
        JSONObject o = new JSONObject();
        try {
            int[] s = FijnVolume.schuif(c);
            o.put("wijze", wijze(p.getString("zacht", AVOND))).put("van", minuut(p.getInt("zachtVan", VAN), VAN)).put("tot", minuut(p.getInt("zachtTot", TOT), TOT))
                    .put("pct", pct(p.getInt("zachtPct", PCT))).put("luidspreker", p.getBoolean("zachtLuidspreker", false))
                    .put("min", PCT_MIN).put("max", PCT_MAX).put("nuPct", s[1] > 0 ? Math.round(s[0] * 100f / s[1]) : -1);
        } catch (Exception ignored) { }
        return o;
    }

    static void zet(Context c, JSONObject o) {
        JSONObject nu = staat(c);
        Podcasts.prefs(c).edit().putString("zacht", wijze(o.optString("wijze", nu.optString("wijze"))))
                .putInt("zachtVan", minuut(o.optInt("van", nu.optInt("van")), VAN)).putInt("zachtTot", minuut(o.optInt("tot", nu.optInt("tot")), TOT))
                .putInt("zachtPct", pct(o.optInt("pct", nu.optInt("pct")))).putBoolean("zachtLuidspreker", o.optBoolean("luidspreker", nu.optBoolean("luidspreker"))).apply();
    }

    /** De speler speelt (of hield net op): vanaf hier telt de pauze. */
    static void speelde(Context c) { Podcasts.prefs(c).edit().putLong("speeldeTot", System.currentTimeMillis()).apply(); }

    /**
     * Aan het begin van een luisterbeurt aanroepen (hoofdthread, vóór het starten). Geeft true als het volume zacht gezet is.
     */
    static boolean begin(Context c) {
        try {
            SharedPreferences p = Podcasts.prefs(c);
            long nu = System.currentTimeMillis();
            if (!nieuweBeurt(nu, p.getLong("speeldeTot", 0))) return false;
            String w = wijze(p.getString("zacht", AVOND));
            if (!nu(w, minuut(p.getInt("zachtVan", VAN), VAN), minuut(p.getInt("zachtTot", TOT), TOT), Regio.minuteOfDay(Regio.zone(c), nu))) return false;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null || am.isVolumeFixed()) return false;
            android.app.UiModeManager ui = (android.app.UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            if (ui != null && ui.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_CAR) return false;
            if (p.getBoolean("zachtLuidspreker", false) && !opLuidspreker(am)) return false;
            int[] s = FijnVolume.schuif(c);
            int doel = doel(s[0], s[1], p.getInt("zachtPct", PCT));
            if (doel < 0) return false;
            FijnVolume.schuifNaar(c, doel, s[1], false);
            return true;
        } catch (Throwable e) { return false; }
    }

    /** Gaat het geluid nu naar de telefoon zelf (geen koptelefoon, Bluetooth of USB)? */
    private static boolean opLuidspreker(AudioManager am) {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && t != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && t != AudioDeviceInfo.TYPE_TELEPHONY
                    && (Build.VERSION.SDK_INT < 30 || t != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) && t != AudioDeviceInfo.TYPE_REMOTE_SUBMIX) return false;
        }
        return true;
    }
}
