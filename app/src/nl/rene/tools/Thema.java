package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Thema van de app: Systeem (zoals de telefoon), Licht, Donker, Automatisch op tijd of van zonsondergang tot
 * zonsopkomst. De interface (web/src/script/02-thema.js) rekent alles uit en geeft hier de keuze door, bij "zon" met
 * een rooster van wissels voor de komende weken (op de telefoon berekend, zonder internet). Java gebruikt het alleen
 * waar de interface nog niet draait: de achtergrond bij het opstarten (geen witte flits 's avonds), de Telefoon-skin
 * (HomeActivity.isNight) en de neutrale versie. Widgets en meldingen volgen gewoon de telefoon.
 * De beslissing zelf is puur (getest in ThemaTest).
 */
final class Thema {

    private Thema() { }

    /** Eigen bestand, buiten de Android-backup (bij "zon" staat er een grove plek in het rooster); zie AppData. */
    static final String PREFS = "thema";
    static final String KEY = "theme";
    /** Achtergrond (--bg uit stijl.css) in licht, donker en zwart (AMOLED). */
    static final int BG_LIGHT = 0xFFF4F5F6, BG_DARK = 0xFF111619, BG_BLACK = 0xFF000000;
    private static final int MAX = 16 * 1024, MAX_SCHED = 200;

    static String get(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""); }

    /** Bewaren wat de interface doorgeeft, alleen als het klopt (bekende soort, rooster met getallen). */
    static boolean save(Context c, String json) {
        String s = clean(json);
        if (s == null) return false;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s).apply();
        return true;
    }

    /** Gecontroleerde, opnieuw opgebouwde versie van de keuze; null als het niet klopt. */
    static String clean(String json) {
        if (json == null || json.length() > MAX) return null;
        try {
            JSONObject in = new JSONObject(json), o = new JSONObject();
            String mode = in.optString("mode", "system");
            if (!mode.matches("system|light|dark|time|sun")) return null;
            o.put("mode", mode);
            o.put("amoled", in.optBoolean("amoled", false));
            JSONArray w = in.optJSONArray("win");
            if (w != null && w.length() == 2) {
                int a = w.optInt(0, -1), b = w.optInt(1, -1);
                if (a >= 0 && a <= 1440 && b >= 0 && b <= 1440) o.put("win", new JSONArray().put(a).put(b));
            }
            JSONArray s = in.optJSONArray("sched");
            if (s != null) {
                JSONArray out = new JSONArray();
                for (int i = 0; i < s.length() && i < MAX_SCHED; i++) {
                    JSONArray e = s.optJSONArray(i);
                    if (e == null || e.length() != 2) return null;
                    long t = e.optLong(0, -1);
                    if (t <= 0) return null;
                    out.put(new JSONArray().put(t).put(e.optInt(1, 0) == 1 ? 1 : 0));
                }
                o.put("sched", out);
                o.put("end", in.optLong("end", 0));
            }
            // Tijdelijk andersom (knop maan/zon in een tool): [donker 0/1, tot (ms), stand van de telefoon 0/1]
            JSONArray t = in.optJSONArray("tmp");
            if (t != null && t.length() == 3 && t.optLong(1, 0) > 0)
                o.put("tmp", new JSONArray().put(t.optInt(0, 0) != 0 ? 1 : 0).put(t.optLong(1, 0)).put(t.optInt(2, 0) != 0 ? 1 : 0));
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean dark(Context c) {
        Calendar k = Regio.cal(c, System.currentTimeMillis()); // "Automatisch op tijd" in de app-zone
        return dark(get(c), System.currentTimeMillis(), k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE), HomeActivity.night(c));
    }

    static boolean amoled(Context c) {
        try { return new JSONObject(get(c)).optBoolean("amoled", false); } catch (Exception e) { return false; }
    }

    /** Achtergrondkleur voor het opstarten. */
    static int bg(Context c) {
        if (!dark(c)) return BG_LIGHT;
        return amoled(c) ? BG_BLACK : BG_DARK;
    }

    static String hex(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF); }

    /**
     * Donker of niet. now = tijd in ms, minute = minuut van de dag (lokale tijd), sys = de telefoon staat op donker.
     * Bij "zon": de laatste wissel uit het rooster die al geweest is; na het rooster (de app lang niet geopend) het
     * venster van de laatste dag.
     */
    static boolean dark(String json, long now, int minute, boolean sys) {
        if (json == null || json.isEmpty()) return sys;
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { return sys; }
        String mode = o.optString("mode", "system");
        if ("light".equals(mode)) return false;
        if ("dark".equals(mode)) return true;
        // Tijdelijk andersom: tot de eindtijd, en bij Systeem alleen zolang de telefoon niet zelf wisselde
        JSONArray t = o.optJSONArray("tmp");
        if (t != null && t.length() == 3 && now < t.optLong(1, 0) && (!"system".equals(mode) || (t.optInt(2, 0) == 1) == sys))
            return t.optInt(0, 0) == 1;
        if (!"time".equals(mode) && !"sun".equals(mode)) return sys;
        JSONArray s = o.optJSONArray("sched");
        if (s != null && s.length() > 0 && now < o.optLong("end", 0)) {
            JSONArray first = s.optJSONArray(0);
            if (first != null && first.optLong(0, Long.MAX_VALUE) <= now) {
                boolean d = false;
                for (int i = 0; i < s.length(); i++) {
                    JSONArray e = s.optJSONArray(i);
                    if (e == null) continue;
                    if (e.optLong(0, Long.MAX_VALUE) <= now) d = e.optInt(1, 0) == 1;
                }
                return d;
            }
        }
        JSONArray w = o.optJSONArray("win");
        if (w == null || w.length() != 2) return sys;
        return inWindow(minute, w.optInt(0, 0), w.optInt(1, 0));
    }

    /** Valt minuut min in de donkere tijd van darkFrom tot lightFrom (ook over middernacht heen)? Gelijk = nooit donker. */
    static boolean inWindow(int min, int darkFrom, int lightFrom) {
        if (darkFrom == lightFrom) return false;
        if (darkFrom > lightFrom) return min >= darkFrom || min < lightFrom;
        return min >= darkFrom && min < lightFrom;
    }

    private static final Pattern DARK_MEDIA = Pattern.compile("@media\\s*\\(\\s*prefers-color-scheme\\s*:\\s*dark\\s*\\)");

    /** Een pagina zonder eigen themacode (neutraal.html) vast op donker of licht zetten. */
    static String forceCss(String html, boolean dark) {
        return DARK_MEDIA.matcher(html).replaceAll(dark ? "@media all" : "@media not all");
    }

    /**
     * De laatst bekende plek (grof, afgerond op 0,01°, ongeveer 1 km) voor zonsopkomst en -ondergang. Vraagt niets
     * nieuws op en gebruikt geen internet: alleen wat Android al weet, en alleen als de app locatie mag gebruiken.
     */
    static String lastLocation(Context c) {
        try {
            if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "";
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return "";
            Location best = null;
            for (String p : new String[]{LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                Location l;
                try { l = lm.getLastKnownLocation(p); } catch (Exception e) { l = null; }
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best == null ? "" : round(best.getLatitude(), best.getLongitude());
        } catch (Throwable t) {
            return "";
        }
    }

    static String round(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return "";
        return String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
    }
}
