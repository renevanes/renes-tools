package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Eigen afspraken in agenda/agenda.json (gaan mee in de backup) en de weergave in de instellingen "agenda". */
final class AgendaStore {
    static final String DIR = "agenda";
    static final String PREFS = "agenda";
    private static final String FILE = "agenda.json";

    private AgendaStore() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static File bestand(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, FILE);
    }

    /** null als het bestand er is maar niet te lezen is (dan nooit overschrijven). Ontbrekend = leeg. */
    static synchronized JSONObject lees(Context c) {
        File f = bestand(c);
        if (!f.isFile()) {
            try { return new JSONObject().put("format", Agenda.FORMAAT).put("items", new JSONArray()); }
            catch (Exception e) { return null; }
        }
        try {
            JSONObject o = new JSONObject(Contacts.readText(f, false));
            if (o.optInt("format", Agenda.FORMAAT) > Agenda.FORMAAT) return null;
            if (o.optJSONArray("items") == null) return null;
            return o;
        } catch (Exception e) { return null; }
    }

    static synchronized JSONArray items(Context c) {
        JSONObject o = lees(c);
        return o == null ? null : o.optJSONArray("items");
    }

    /** Agenda's die Rene uit heeft gezet. Leeg = allemaal aan. */
    static Set<String> uit(Context c) {
        Set<String> s = prefs(c).getStringSet("uit", null);
        return s == null ? Collections.<String>emptySet() : new HashSet<>(s);
    }

    static String view(Context c) {
        String v = prefs(c).getString("view", "week");
        return "dag".equals(v) || "maand".equals(v) || "lijst".equals(v) || "week".equals(v) ? v : "week";
    }

    static String standaard(Context c) {
        String s = prefs(c).getString("standaard", "");
        return s != null && s.matches("[1-9]\\d{0,17}") ? s : "";
    }

    static synchronized void zet(Context c, String view, String standaard, Set<String> uit) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (view != null) e.putString("view", view(view));
        if (standaard != null) e.putString("standaard", standaard.matches("[1-9]\\d{0,17}") ? standaard : "");
        if (uit != null) e.putStringSet("uit", uit);
        e.apply();
    }

    private static String view(String v) {
        return "dag".equals(v) || "maand".equals(v) || "lijst".equals(v) ? v : "week";
    }

    /** Zichtbare eigen afspraken. null = bestand onleesbaar. */
    static JSONArray zichtbaar(Context c) {
        JSONArray all = items(c);
        if (all == null) return null;
        Set<String> uit = uit(c);
        JSONArray o = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject it = all.optJSONObject(i);
            if (it == null) continue;
            String k = it.optString("kalender");
            if (!k.isEmpty() && uit.contains(k)) continue;
            o.put(it);
        }
        return o;
    }

    static synchronized String bewaar(Context c, JSONArray items) {
        if (items == null) return "opslaan";
        File f = bestand(c);
        if (f.isFile() && lees(c) == null) return "onleesbaar";
        try {
            JSONObject o = new JSONObject().put("format", Agenda.FORMAAT).put("items", items);
            Contacts.writeText(f, o.toString(), false);
            return "";
        } catch (Exception e) { return "opslaan"; }
    }

    static synchronized JSONObject vind(Context c, String id) {
        JSONArray a = items(c);
        if (a == null || id == null || id.isEmpty()) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it != null && id.equals(it.optString("id"))) return it;
        }
        return null;
    }

    /** Vervang de afspraak met dit id, of voeg hem toe. Geeft een foutcode of "". */
    static synchronized String zetItem(Context c, JSONObject item) {
        JSONArray a = items(c);
        if (a == null) return "onleesbaar";
        String id = item.optString("id");
        JSONArray uit = new JSONArray();
        boolean gevonden = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) continue;
            if (id.equals(it.optString("id"))) { uit.put(item); gevonden = true; }
            else uit.put(it);
        }
        if (!gevonden) {
            if (uit.length() >= Agenda.MAX_ITEMS) return "vol";
            uit.put(item);
        }
        return bewaar(c, uit);
    }

    static synchronized String wis(Context c, String id) {
        JSONArray a = items(c);
        if (a == null) return "onleesbaar";
        JSONArray uit = new JSONArray();
        boolean gevonden = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) continue;
            if (id.equals(it.optString("id"))) gevonden = true;
            else uit.put(it);
        }
        if (!gevonden) return "weg";
        return bewaar(c, uit);
    }
}
