package nl.rene.tools;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

/**
 * Concepten van Formulieren invullen: wat je tussentijds opslaat, ook als nog niet alle verplichte velden ingevuld zijn.
 * Eén concept per formulier, versleuteld met de sleutel van deze telefoon (AES-GCM, Android-sleutelopslag) in
 * formulieren/&lt;id&gt;.concept. Gegevens die uit Stamgegevens kwamen, laat de interface er bewust uit (die blijven alleen
 * in Stamgegevens en worden bij vergrendelen gewist); die haal je bij verder gaan opnieuw op.
 */
final class FormConcept {

    private FormConcept() { }

    static final String DIR = "formulieren", FORMAT = "renes-tools-formulier-concept";
    static final int VERSION = 1, MAX_FIELDS = 200, MAX_VALUE = 2000, MAX_BYTES = 200_000;

    static boolean validId(String id) { return id != null && id.matches("[a-z0-9_-]{1,40}"); }

    static File file(Context c, String id) { return new File(new File(c.getFilesDir(), DIR), id + ".concept"); }

    /** Alleen bekende vorm: veldnamen als id, waarden als korte tekst. Gooit bij iets vreemds. */
    static JSONObject clean(String id, JSONObject in) throws Exception {
        JSONObject values = in.optJSONObject("values");
        if (values == null) throw new Exception("Geen invoer om op te slaan");
        JSONObject v = new JSONObject();
        int n = 0;
        for (Iterator<String> it = values.keys(); it.hasNext(); ) {
            String k = it.next();
            Object x = values.opt(k);
            if (!(x instanceof String) || !k.matches("[A-Za-z0-9_-]{1,64}")) continue;
            if (++n > MAX_FIELDS) break;
            String s = (String) x;
            v.put(k, s.length() > MAX_VALUE ? s.substring(0, MAX_VALUE) : s);
        }
        JSONObject out = new JSONObject().put("format", FORMAT).put("version", VERSION).put("id", id).put("values", v)
                .put("step", Math.max(0, Math.min(50, in.optInt("step", 0)))).put("t", System.currentTimeMillis())
                .put("stam", in.optBoolean("stam", false));
        String naam = in.optString("naam", "");
        out.put("naam", naam.length() > 60 ? naam.substring(0, 60) : naam);
        return out;
    }

    static synchronized long save(Context c, String id, String json) throws Exception {
        if (!validId(id)) throw new Exception("Onbekend formulier");
        if (json == null || json.length() > MAX_BYTES) throw new Exception("Het concept is te groot om op te slaan");
        JSONObject o = clean(id, new JSONObject(json));
        File f = file(c, id);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Exception("Opslaan lukt niet");
        File tmp = new File(f.getPath() + ".tmp");
        Contacts.writeText(tmp, AppData.seal(Secure.deviceKey(), o.toString()), false);
        if (f.exists() && !f.delete()) { tmp.delete(); throw new Exception("Opslaan lukt niet"); }
        if (!tmp.renameTo(f)) { tmp.delete(); throw new Exception("Opslaan lukt niet"); }
        return o.optLong("t");
    }

    /** Het concept, of null als er geen is. Een onleesbaar concept (bijv. na een nieuwe telefoon) meldt zich als fout. */
    static synchronized JSONObject load(Context c, String id) throws Exception {
        if (!validId(id)) return null;
        File f = file(c, id);
        if (!f.isFile()) return null;
        JSONObject o = new JSONObject(AppData.open(Secure.deviceKey(), Contacts.readText(f, false)));
        if (!FORMAT.equals(o.optString("format")) || o.optInt("version", 0) > VERSION || !id.equals(o.optString("id"))) throw new Exception("Concept niet te lezen");
        return o;
    }

    static synchronized void delete(Context c, String id) {
        if (!validId(id)) return;
        File f = file(c, id);
        if (f.exists()) f.delete();
    }
}
