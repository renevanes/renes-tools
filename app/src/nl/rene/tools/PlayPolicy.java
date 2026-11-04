package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Dezelfde grenzen voor schermen, native ingangen en teruggezette instellingen van de Play-uitvoering. */
final class PlayPolicy {
    private PlayPolicy() { }

    private static final Set<String> TOOLS = new HashSet<>(Arrays.asList(PlayFeatures.TOOLS));
    static final String UNAVAILABLE = Taal.T("Deze functie zit niet in de Play-versie");

    static boolean allows(String pkg, String feature) { return !Updater.isPlayPackage(pkg) || TOOLS.contains(feature); }
    static boolean allows(Context c, String feature) { return allows(c.getPackageName(), feature); }
    static String unavailable(Context c) { return Taal.t(c, UNAVAILABLE); }

    /** Niet meenemen uit een gewone of oude installatie; ook Android-herstel wordt bij de start opgeschoond. */
    static boolean keepPreference(String pkg, String file, String key) {
        if (!Updater.isPlayPackage(pkg)) return true;
        if ("call_recordings".equals(file) || "redial".equals(file) || "redialplan".equals(file)) return false;
        return !"gebruik".equals(file) || !("punlock".equals(key) || key.startsWith("photo"));
    }

    static boolean allowsRule(String pkg, JSONObject rule) {
        if (!allows(pkg, "auto")) return false;
        if (allows(pkg, "autoButtons")) return true;
        JSONArray steps = rule.optJSONArray("steps");
        return steps == null || steps.length() == 0;
    }

    /** Bewaar de regel zodat hij niet verloren gaat, maar activeer uitgesloten stappen nooit na terugzetten. */
    static String sanitizeRules(String pkg, String json) throws Exception {
        if (!Updater.isPlayPackage(pkg)) return json;
        if (!allows(pkg, "auto")) return "[]";
        JSONArray rules = new JSONArray(json);
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule != null && !allowsRule(pkg, rule)) rule.put("on", false);
        }
        return rules.toString();
    }

    static void sanitizePreferences(Context c) {
        if (!Updater.viaPlay(c)) return;
        for (String file : new String[]{"call_recordings", "redial", "redialplan", "gebruik"}) {
            SharedPreferences p = c.getSharedPreferences(file, Context.MODE_PRIVATE);
            SharedPreferences.Editor e = p.edit();
            for (String key : p.getAll().keySet()) if (!keepPreference(c.getPackageName(), file, key)) e.remove(key);
            e.apply();
        }
        SharedPreferences p = Auto.prefs(c);
        String before = p.getString("rules", "[]");
        try {
            String after = sanitizeRules(c.getPackageName(), before);
            if (!before.equals(after)) p.edit().putString("rules", after).apply();
        } catch (Exception e) { p.edit().putString("rules", "[]").apply(); }
    }
}
