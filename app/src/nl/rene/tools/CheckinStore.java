package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Check-in: de vragen en antwoorden, alleen op de telefoon (files/checkin, niet in Android-backups; wel als privé
 * onderdeel van Alles back-uppen als Rene dat aanvinkt). questions.json = {seeded, next, questions:[…]},
 * answers.json = [{id, q, p, t, o, v, l, e, note, labels, late, src}]. Schrijven gaat altijd via een tijdelijk
 * bestand (Contacts.writeText). Het rekenwerk (periodes, reeks, tellen) staat in Checkin.
 */
final class CheckinStore {

    private CheckinStore() { }

    static final int FORMAT = 1;
    static final String DIR = "Check-in", FILE = "checkin.json";
    static final String[] SCALES = {"buttons2", "buttons3", "smileys5", "score10", "yesno", "custom"};
    static final String[] COLORS = {"primary", "blue", "green", "orange", "red", "purple", "grey"};
    static final int MAX_QUESTIONS = 40, MAX_ANSWERS = 50_000;

    static File dir(Context c) { File d = new File(c.getFilesDir(), "checkin"); d.mkdirs(); return d; }
    static File qFile(Context c) { return new File(dir(c), "questions.json"); }
    static File aFile(Context c) { return new File(dir(c), "answers.json"); }

    private static JSONObject db;      // {seeded, next, questions}
    private static JSONArray answers;
    private static volatile int version;

    static int version() { return version; }

    // ---------- lezen en schrijven ----------

    /** Een bestand lezen; null als het er niet is. Lukt lezen niet (geen geldige JSON is iets anders), dan een fout. */
    private static String read(File f) {
        if (!f.isFile()) return null;
        try { return Contacts.readText(f, false); }
        catch (Exception e) { throw new IllegalStateException("Check-in is nu niet te lezen; probeer het zo opnieuw"); }
    }

    /**
     * Inlezen. Lukt het lezen zelf niet (bijv. even geen toegang), dan niets veranderen en het later opnieuw proberen.
     * Is een bestand kapot (geen geldige JSON), dan gaat het apart (naam.kapot-tijd, niet stilletjes overschreven) en
     * begint Check-in leeg, zonder voorstellen; Alles back-uppen laat de vorige backup dan staan (brokenAside).
     */
    private static synchronized void load(Context c) {
        if (db != null && answers != null) return;
        String qt = read(qFile(c)), at = read(aFile(c));
        JSONObject d;
        JSONArray a;
        try { d = qt == null ? new JSONObject() : new JSONObject(qt); }
        catch (org.json.JSONException e) {
            App.log(c, "CHECKIN", "vragen kapot, apart gezet: " + e);
            qFile(c).renameTo(new File(dir(c), "questions.json.kapot-" + System.currentTimeMillis()));
            d = new JSONObject();
            try { d.put("seeded", true); } catch (Exception ignored) { } // geen voorstellen over een kapot bestand heen
        }
        try { a = at == null ? new JSONArray() : new JSONArray(at); }
        catch (org.json.JSONException e) {
            App.log(c, "CHECKIN", "antwoorden kapot, apart gezet: " + e);
            aFile(c).renameTo(new File(dir(c), "answers.json.kapot-" + System.currentTimeMillis()));
            a = new JSONArray();
        }
        if (d.optJSONArray("questions") == null) try { d.put("questions", new JSONArray()); } catch (Exception ignored) { }
        db = d; answers = a;
    }

    /**
     * Is een bestand kapot apart gezet en sindsdien niet opnieuw bewaard? Dan maakt Alles back-uppen geen nieuwe
     * backup van Check-in (die zou de goede vervangen door een lege). Zoals AiMemory.brokenAside.
     */
    static boolean brokenAside(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            File cur = n.startsWith("questions.json.kapot-") ? qFile(c) : n.startsWith("answers.json.kapot-") ? aFile(c) : null;
            if (cur == null) continue;
            if (!cur.isFile()) return true;
            long when = f.lastModified();
            try { when = Math.max(when, Long.parseLong(n.substring(n.lastIndexOf('-') + 1))); } catch (Exception ignored) { }
            if (when > cur.lastModified()) return true;
        }
        return false;
    }

    private static void saveQ(Context c) throws Exception { Contacts.writeText(qFile(c), db.toString(), false); version++; }
    private static void saveA(Context c) throws Exception { Contacts.writeText(aFile(c), answers.toString(), false); version++; }

    /** De echte lijst (alleen binnen deze klasse, onder het slot). */
    private static JSONArray qs(Context c) { load(c); return db.optJSONArray("questions"); }

    private static JSONObject qLive(Context c, String id) {
        JSONArray qs = qs(c);
        for (int i = 0; i < qs.length(); i++) { JSONObject q = qs.optJSONObject(i); if (q != null && q.optString("id").equals(id)) return q; }
        return null;
    }

    private static JSONArray copy(JSONArray a) { try { return new JSONArray(a.toString()); } catch (Exception e) { return new JSONArray(); } }

    /** Een kopie: wie hem buiten het slot doorloopt, merkt niets van een gelijktijdige wijziging. */
    static synchronized JSONArray questions(Context c) { return copy(qs(c)); }

    static synchronized JSONObject question(Context c, String id) {
        JSONObject q = qLive(c, id);
        try { return q == null ? null : new JSONObject(q.toString()); } catch (Exception e) { return null; }
    }

    static synchronized JSONArray answers(Context c) { load(c); return copy(answers); }

    /** Alle antwoorden van één vraag per periode. */
    static synchronized Map<String, List<JSONObject>> byKey(Context c, String qid) {
        Map<String, List<JSONObject>> m = new HashMap<>();
        load(c);
        JSONArray a = answers;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null || !qid.equals(x.optString("q"))) continue;
            List<JSONObject> l = m.get(x.optString("p"));
            if (l == null) { l = new ArrayList<>(); m.put(x.optString("p"), l); }
            l.add(x);
        }
        return m;
    }

    // ---------- voorstellen ----------

    static JSONArray defaultOptions(String scale) throws Exception {
        String[][] o;
        switch (scale) {
            case "buttons2": o = new String[][]{{"👎", "Niet goed", "1"}, {"👍", "Goed", "2"}}; break;
            case "buttons3": o = new String[][]{{"😕", "Slecht", "1"}, {"😐", "Gaat wel", "2"}, {"🙂", "Goed", "3"}}; break;
            case "smileys5": o = new String[][]{{"😞", "Heel slecht", "1"}, {"🙁", "Slecht", "2"}, {"😐", "Gaat wel", "3"}, {"🙂", "Goed", "4"}, {"😄", "Heel goed", "5"}}; break;
            case "yesno": o = new String[][]{{"✗", "Nee", "0"}, {"✓", "Ja", "1"}}; break;
            case "score10": { o = new String[10][]; for (int i = 0; i < 10; i++) o[i] = new String[]{"", String.valueOf(i + 1), String.valueOf(i + 1)}; break; }
            default: o = new String[][]{{"", "Keuze 1", "1"}, {"", "Keuze 2", "2"}, {"", "Keuze 3", "3"}};
        }
        JSONArray a = new JSONArray();
        for (String[] x : o) a.put(new JSONObject().put("emoji", x[0]).put("label", x[1]).put("value", Double.parseDouble(x[2])));
        return a;
    }

    static JSONObject template(String id, String text, String emoji, String color, String scale, String rhythm, String time, String[] labels) throws Exception {
        JSONArray days = new JSONArray();
        for (int d = 1; d <= 7; d++) days.put(d);
        JSONArray ls = new JSONArray();
        for (String l : labels) ls.put(l);
        return new JSONObject().put("id", id).put("text", text).put("emoji", emoji).put("color", color).put("scale", scale)
                .put("options", defaultOptions(scale)).put("rhythm", rhythm).put("days", days).put("wday", 5).put("dom", 1)
                .put("times", new JSONArray().put(time)).put("until", "23:59").put("quietWeekend", false).put("pauseUntil", "")
                .put("on", false).put("archived", false).put("tpl", true).put("perPeriod", "one").put("editable", true).put("backfill", true)
                .put("note", true).put("voice", true).put("labels", true).put("labelList", ls);
    }

    /** De eerste keer: twee voorstellen, die uit staan tot Rene ze aanzet (er wordt niets gepland). */
    static synchronized boolean seedOnce(Context c) {
        load(c);
        if (db.optBoolean("seeded")) return false;
        try {
            JSONArray qs = db.optJSONArray("questions");
            if (qs.length() == 0) {
                long now = System.currentTimeMillis();
                JSONObject a = template("voel", "Hoe voel je je?", "🙂", "blue", "smileys5", "daily", "21:00", new String[]{"slecht geslapen", "moe", "druk", "ontspannen", "gesport"});
                JSONObject b = template("werk", "Hoe was je werk?", "💼", "green", "buttons3", "workdays", "17:30", new String[]{"druk", "rustig", "veel overleg", "thuisgewerkt"});
                b.put("voice", false);
                int n = db.optInt("next", 1);
                a.put("n", n++).put("created", now).put("updated", now);
                b.put("n", n++).put("created", now).put("updated", now);
                qs.put(a).put(b);
                db.put("next", n);
            }
            db.put("seeded", true);
            saveQ(c);
            return true;
        } catch (Exception e) { App.log(c, "CHECKIN", "voorstellen: " + e); return false; }
    }

    // ---------- een vraag opschonen ----------

    static String cleanText(String s, int max) {
        String x = s == null ? "" : s.replaceAll("[\\p{Cntrl}&&[^\n]]", "").replace('\n', ' ').trim().replaceAll("\\s{2,}", " ");
        return x.length() > max ? x.substring(0, max).trim() : x;
    }

    private static boolean in(String v, String[] list) { for (String x : list) if (x.equals(v)) return true; return false; }

    /** Een vraag uit de interface controleren; old = de huidige versie (of null bij een nieuwe). Gooit met een melding. */
    static JSONObject clean(JSONObject in, JSONObject old) throws Exception {
        JSONObject q = new JSONObject();
        String text = cleanText(in.optString("text"), 120);
        if (text.isEmpty()) throw new Exception("Vul de vraag in");
        q.put("text", text).put("emoji", cleanText(in.optString("emoji"), 16));
        String color = in.optString("color", "primary");
        q.put("color", in(color, COLORS) ? color : "primary");
        String scale = in.optString("scale");
        if (!in(scale, SCALES)) throw new Exception("Kies een soort antwoord");
        q.put("scale", scale);
        JSONArray opts = in.optJSONArray("options"), clean = new JSONArray();
        if (opts == null) opts = defaultOptions(scale);
        int min = "custom".equals(scale) ? 2 : opts.length(), max = "custom".equals(scale) ? 7 : 10;
        if (opts.length() < Math.max(2, min) || opts.length() > max) throw new Exception("custom".equals(scale) ? "Kies 2 tot 7 antwoorden" : "Ongeldige antwoorden");
        Set<Double> values = new HashSet<>();
        for (int i = 0; i < opts.length(); i++) {
            JSONObject o = opts.optJSONObject(i);
            if (o == null) throw new Exception("Ongeldige antwoorden");
            String label = cleanText(o.optString("label"), 40), emoji = cleanText(o.optString("emoji"), 16);
            if (label.isEmpty() && emoji.isEmpty()) throw new Exception("Geef elk antwoord een naam of emoji");
            double v = o.optDouble("value", Double.NaN);
            if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 1e6) throw new Exception("Geef elk antwoord een getal als waarde");
            v = Math.round(v * 100) / 100.0;
            if (!values.add(v)) throw new Exception("Geef elk antwoord een eigen waarde");
            clean.put(new JSONObject().put("label", label).put("emoji", emoji).put("value", v));
        }
        q.put("options", clean);
        String rhythm = in.optString("rhythm");
        if (!in(rhythm, Checkin.RHYTHMS)) throw new Exception("Kies hoe vaak");
        q.put("rhythm", rhythm);
        JSONArray days = new JSONArray(), din = in.optJSONArray("days");
        boolean[] seen = new boolean[8];
        if (din != null) for (int i = 0; i < din.length(); i++) { int d = din.optInt(i); if (d >= 1 && d <= 7 && !seen[d]) { seen[d] = true; } }
        for (int d = 1; d <= 7; d++) if (seen[d]) days.put(d);
        if (("weekdays".equals(rhythm) || "multi".equals(rhythm)) && days.length() == 0) throw new Exception("Kies minstens één dag");
        q.put("days", days).put("wday", Math.max(1, Math.min(7, in.optInt("wday", 5)))).put("dom", Math.max(1, Math.min(31, in.optInt("dom", 1))));
        JSONArray tin = in.optJSONArray("times"), times = new JSONArray();
        List<String> ts = new ArrayList<>();
        if (tin != null) for (int i = 0; i < tin.length(); i++) { String t = tin.optString(i); if (Checkin.validTime(t) && !ts.contains(t)) ts.add(t); }
        java.util.Collections.sort(ts);
        if (ts.isEmpty()) throw new Exception("Kies een tijd");
        if (!"multi".equals(rhythm)) ts = ts.subList(0, 1);
        if (ts.size() > 8) throw new Exception("Hooguit 8 tijden per dag");
        for (String t : ts) times.put(t);
        q.put("times", times);
        String until = in.optString("until", "23:59");
        q.put("until", Checkin.validTime(until) ? until : "23:59");
        q.put("quietWeekend", in.optBoolean("quietWeekend"));
        String pause = in.optString("pauseUntil", "");
        q.put("pauseUntil", Checkin.validDate(pause) ? pause : "");
        q.put("on", in.optBoolean("on")).put("archived", in.optBoolean("archived"));
        q.put("tpl", old != null && old.optBoolean("tpl") && in.optBoolean("tpl", true) && !in.optBoolean("on"));
        q.put("perPeriod", "many".equals(in.optString("perPeriod")) ? "many" : "one");
        q.put("editable", in.optBoolean("editable", true)).put("backfill", in.optBoolean("backfill", true));
        q.put("note", in.optBoolean("note")).put("voice", in.optBoolean("voice")).put("labels", in.optBoolean("labels"));
        JSONArray lin = in.optJSONArray("labelList"), labels = new JSONArray();
        Set<String> ls = new HashSet<>();
        if (lin != null) for (int i = 0; i < lin.length() && labels.length() < 16; i++) {
            String l = cleanText(lin.optString(i), 30);
            if (!l.isEmpty() && ls.add(l.toLowerCase(Locale.ROOT))) labels.put(l);
        }
        q.put("labelList", labels);
        return q;
    }

    static Checkin.Rule rule(JSONObject q) {
        Checkin.Rule r = new Checkin.Rule();
        r.rhythm = q.optString("rhythm", "daily");
        r.days = new boolean[8];
        JSONArray d = q.optJSONArray("days");
        if (d != null) for (int i = 0; i < d.length(); i++) { int x = d.optInt(i); if (x >= 1 && x <= 7) r.days[x] = true; }
        r.wday = q.optInt("wday", 5);
        r.dom = q.optInt("dom", 1);
        JSONArray t = q.optJSONArray("times");
        List<Integer> mins = new ArrayList<>();
        if (t != null) for (int i = 0; i < t.length(); i++) { int m = Checkin.minutes(t.optString(i)); if (m >= 0) mins.add(m); }
        r.times = new int[mins.size()];
        for (int i = 0; i < mins.size(); i++) r.times[i] = mins.get(i);
        int u = Checkin.minutes(q.optString("until", "23:59"));
        r.until = u < 0 ? 23 * 60 + 59 : u;
        r.quietWeekend = q.optBoolean("quietWeekend");
        r.pauseUntil = q.optString("pauseUntil", "");
        r.created = q.optLong("created");
        return r;
    }

    /** Doet hij mee (aan, niet gearchiveerd)? */
    static boolean active(JSONObject q) { return q != null && q.optBoolean("on") && !q.optBoolean("archived"); }

    // ---------- vragen bewaren, ordenen, wissen ----------

    /** Bewaren (nieuw als er geen id is). Geeft de id. */
    static synchronized String save(Context c, JSONObject in) throws Exception {
        load(c);
        JSONArray qs = db.optJSONArray("questions");
        String id = in.optString("id");
        JSONObject old = id.isEmpty() ? null : qLive(c, id);
        if (!id.isEmpty() && old == null) throw new Exception("Deze vraag bestaat niet meer");
        JSONObject q = clean(in, old);
        long now = System.currentTimeMillis();
        if (old == null) {
            if (qs.length() >= MAX_QUESTIONS) throw new Exception("Hooguit " + MAX_QUESTIONS + " vragen");
            id = "q" + Long.toString(now, 36) + Integer.toString((int) (Math.random() * 1296), 36);
            int n = db.optInt("next", 1);
            q.put("id", id).put("n", n).put("created", now);
            db.put("next", n + 1);
            qs.put(q.put("updated", now));
        } else {
            q.put("id", id).put("n", old.optInt("n")).put("created", old.optLong("created", now)).put("updated", now);
            for (int i = 0; i < qs.length(); i++) if (id.equals(qs.optJSONObject(i).optString("id"))) qs.put(i, q);
        }
        saveQ(c);
        return id;
    }

    static synchronized void move(Context c, String id, int dir) throws Exception {
        JSONArray qs = qs(c);
        int i = -1;
        for (int k = 0; k < qs.length(); k++) if (id.equals(qs.optJSONObject(k).optString("id"))) i = k;
        int j = i + (dir < 0 ? -1 : 1);
        if (i < 0 || j < 0 || j >= qs.length()) return;
        Object a = qs.get(i);
        qs.put(i, qs.get(j));
        qs.put(j, a);
        saveQ(c);
    }

    static synchronized String duplicate(Context c, String id) throws Exception {
        JSONObject q = question(c, id);
        if (q == null) throw new Exception("Deze vraag bestaat niet meer");
        JSONObject x = new JSONObject(q.toString());
        x.remove("id");
        x.put("text", cleanText(q.optString("text") + " (kopie)", 120)).put("on", false).put("archived", false).put("tpl", false);
        return save(c, x);
    }

    /** Vraag en al zijn antwoorden weg. */
    static synchronized void delete(Context c, String id) throws Exception {
        load(c);
        JSONArray qs = db.optJSONArray("questions"), keep = new JSONArray();
        for (int i = 0; i < qs.length(); i++) if (!id.equals(qs.optJSONObject(i).optString("id"))) keep.put(qs.get(i));
        db.put("questions", keep);
        saveQ(c);
        JSONArray ka = new JSONArray();
        for (int i = 0; i < answers.length(); i++) { JSONObject a = answers.optJSONObject(i); if (a != null && !id.equals(a.optString("q"))) ka.put(a); }
        answers = ka;
        saveA(c);
    }

    // ---------- antwoorden ----------

    static JSONObject option(JSONObject q, int i) { JSONArray o = q.optJSONArray("options"); return o == null ? null : o.optJSONObject(i); }

    static JSONArray cleanLabels(JSONObject q, JSONArray in) {
        JSONArray out = new JSONArray(), list = q.optJSONArray("labelList");
        if (in == null || list == null) return out;
        Set<String> allowed = new HashSet<>();
        for (int i = 0; i < list.length(); i++) allowed.add(list.optString(i));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < in.length(); i++) { String l = in.optString(i); if (allowed.contains(l) && seen.add(l)) out.put(l); }
        return out;
    }

    /**
     * Een antwoord geven of wijzigen. req = {q, p ("" = nu), o (keuze), note, labels, voice, replace, id (wijzigen), src}.
     * Geeft {ok, id, key, late} of {error} of {need:"replace", old} (er staat al een antwoord: eerst bevestigen).
     */
    static synchronized JSONObject answer(Context c, JSONObject req, long now) throws Exception {
        load(c);
        JSONObject q = qLive(c, req.optString("q"));
        if (q == null) return err("Deze vraag bestaat niet meer");
        if (q.optBoolean("archived")) return err("Deze vraag is gearchiveerd");
        TimeZone tz = TimeZone.getDefault();
        String note = q.optBoolean("note") || q.optBoolean("voice") ? cleanNote(req.optString("note")) : "";
        JSONArray labels = q.optBoolean("labels") ? cleanLabels(q, req.optJSONArray("labels")) : new JSONArray();
        String aid = req.optString("id");
        if (!aid.isEmpty()) { // wijzigen
            JSONObject a = null;
            for (int i = 0; i < answers.length(); i++) if (aid.equals(answers.optJSONObject(i).optString("id"))) a = answers.optJSONObject(i);
            if (a == null || !a.optString("q").equals(q.optString("id"))) return err("Dit antwoord bestaat niet meer");
            boolean fresh = now - a.optLong("t") < 15 * 60_000L; // net gegeven: aanvullen mag altijd
            if (!q.optBoolean("editable", true) && !fresh) return err("Wijzigen staat uit bij deze vraag");
            if (req.has("o")) {
                JSONObject o = option(q, req.optInt("o", -1));
                if (o == null) return err("Onbekend antwoord");
                a.put("o", req.optInt("o")).put("v", o.optDouble("value")).put("l", o.optString("label")).put("e", o.optString("emoji"));
            }
            if (req.has("note")) a.put("note", note);
            if (req.has("labels")) a.put("labels", labels);
            if (req.optBoolean("voice")) a.put("voice", true);
            a.put("edited", now);
            saveA(c);
            return new JSONObject().put("ok", true).put("id", aid).put("key", a.optString("p")).put("late", a.optBoolean("late"));
        }
        JSONObject o = option(q, req.optInt("o", -1));
        if (o == null) return err("Onbekend antwoord");
        Checkin.Rule r = rule(q);
        String key = req.optString("p");
        Checkin.Slot s = key.isEmpty() ? Checkin.current(r, now, tz) : Checkin.find(r, key, now, tz);
        if (s == null) return err(key.isEmpty() ? "Er staat nu geen vraag open" : "Deze periode hoort niet bij de vraag");
        if (s.at > now && !Checkin.dateKey(s.at, tz).equals(Checkin.dateKey(now, tz))) return err("Deze periode is nog niet begonnen");
        boolean late = now >= s.end;
        if (late && !q.optBoolean("backfill", true)) return err("Achteraf invullen staat uit bij deze vraag");
        List<JSONObject> have = byKey(c, q.optString("id")).get(s.key);
        boolean one = !"many".equals(q.optString("perPeriod"));
        if (answers.length() >= MAX_ANSWERS) return err("Er zijn te veel antwoorden bewaard"); // vóór het weghalen van het oude
        if (one && have != null && !have.isEmpty()) {
            if (!q.optBoolean("editable", true)) return err("Al ingevuld (wijzigen staat uit bij deze vraag)");
            if (!req.optBoolean("replace")) {
                JSONObject h = have.get(have.size() - 1);
                return new JSONObject().put("need", "replace").put("old", (h.optString("e") + " " + h.optString("l")).trim());
            }
            JSONArray keep = new JSONArray();
            for (int i = 0; i < answers.length(); i++) { JSONObject x = answers.optJSONObject(i); if (!(x.optString("q").equals(q.optString("id")) && x.optString("p").equals(s.key))) keep.put(x); }
            answers = keep;
        }
        String id = "a" + Long.toString(now, 36) + Integer.toString((int) (Math.random() * 46656), 36);
        String src = "notif".equals(req.optString("src")) ? "notif" : "app";
        JSONObject a = new JSONObject().put("id", id).put("q", q.optString("id")).put("p", s.key).put("t", now).put("o", req.optInt("o"))
                .put("v", o.optDouble("value")).put("l", o.optString("label")).put("e", o.optString("emoji"))
                .put("note", note).put("labels", labels).put("late", late).put("src", src);
        if (req.optBoolean("voice") && !note.isEmpty()) a.put("voice", true);
        answers.put(a);
        saveA(c);
        return new JSONObject().put("ok", true).put("id", id).put("key", s.key).put("late", late);
    }

    static String cleanNote(String s) {
        String x = s == null ? "" : s.replaceAll("[\\p{Cntrl}&&[^\n]]", "").trim();
        return x.length() > 2000 ? x.substring(0, 2000) : x;
    }

    static JSONObject err(String m) { try { return new JSONObject().put("error", m); } catch (Exception e) { return new JSONObject(); } }

    static synchronized String deleteAnswer(Context c, String id) throws Exception {
        load(c);
        JSONArray keep = new JSONArray();
        boolean found = false;
        for (int i = 0; i < answers.length(); i++) {
            JSONObject a = answers.optJSONObject(i);
            if (a != null && id.equals(a.optString("id"))) {
                JSONObject q = qLive(c, a.optString("q"));
                if (q != null && !q.optBoolean("editable", true) && System.currentTimeMillis() - a.optLong("t") > 15 * 60_000L) return "Wijzigen staat uit bij deze vraag";
                found = true;
                continue;
            }
            keep.put(a);
        }
        if (!found) return "Dit antwoord bestaat niet meer";
        answers = keep;
        saveA(c);
        return "";
    }

    // ---------- voor het scherm ----------

    static JSONObject slotJson(Checkin.Rule r, Checkin.Slot s, List<JSONObject> as, long now) throws Exception {
        JSONArray a = new JSONArray();
        if (as != null) for (JSONObject x : as) a.put(x);
        return new JSONObject().put("key", s.key).put("at", s.at).put("end", s.end).put("quiet", s.quiet)
                .put("status", Checkin.status(r, s, as != null && !as.isEmpty(), now)).put("answers", a);
    }

    /** De reeks: periodes van het afgelopen jaar, van nieuw naar oud. */
    static int streak(Checkin.Rule r, Map<String, List<JSONObject>> by, long now, TimeZone tz) {
        List<Checkin.Slot> ss = Checkin.slots(r, now - 400 * 86_400_000L, now + 86_400_000L, tz);
        String[] st = new String[ss.size()];
        for (int i = 0; i < ss.size(); i++) {
            Checkin.Slot s = ss.get(ss.size() - 1 - i);
            List<JSONObject> l = by.get(s.key);
            st[i] = Checkin.status(r, s, l != null && !l.isEmpty(), now);
        }
        return Checkin.streak(st);
    }

    /** Alles voor het hoofdscherm en de tegel. */
    static synchronized JSONObject state(Context c, long now) throws Exception {
        boolean first = seedOnce(c);
        TimeZone tz = TimeZone.getDefault();
        JSONArray out = new JSONArray(), qs = questions(c);
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = new JSONObject(qs.getJSONObject(i).toString());
            Checkin.Rule r = rule(q);
            Map<String, List<JSONObject>> by = byKey(c, q.optString("id"));
            int total = 0;
            for (List<JSONObject> l : by.values()) total += l.size();
            q.put("total", total);
            if (!q.optBoolean("archived")) {
                Checkin.Slot s = Checkin.current(r, now, tz);
                if (s != null) q.put("cur", slotJson(r, s, by.get(s.key), now));
                q.put("next", Checkin.next(r, now, tz));
                q.put("streak", streak(r, by, now, tz));
                int missed = 0;
                for (Checkin.Slot x : Checkin.slots(r, now - 7 * 86_400_000L, now, tz)) {
                    List<JSONObject> l = by.get(x.key);
                    if ("missed".equals(Checkin.status(r, x, l != null && !l.isEmpty(), now))) missed++;
                }
                q.put("missed7", missed);
            }
            out.put(q);
        }
        return new JSONObject().put("questions", out).put("first", first).put("now", now);
    }

    /** Terugkijken: alle periodes van [from, to) ("yyyy-MM-dd", to niet meer) met hun antwoorden, en een samenvatting. */
    static synchronized JSONObject history(Context c, String qid, String from, String to, long now) throws Exception {
        JSONObject q = question(c, qid);
        if (q == null) return err("Deze vraag bestaat niet meer");
        TimeZone tz = TimeZone.getDefault();
        Calendar f = Checkin.day(from, tz), t = Checkin.day(to, tz);
        if (f == null || t == null || !t.after(f)) return err("Ongeldige periode");
        Checkin.Rule r = rule(q);
        Map<String, List<JSONObject>> by = byKey(c, qid);
        long a = Checkin.at(f, 0), b = Checkin.at(t, 0);
        JSONArray slots = new JSONArray();
        Set<String> keys = new HashSet<>();
        List<Double> vals = new ArrayList<>();
        int answered = 0, missed = 0;
        for (Checkin.Slot s : Checkin.slots(r, a, b, tz)) {
            JSONObject sj = slotJson(r, s, by.get(s.key), now);
            slots.put(sj);
            keys.add(s.key);
            String st = sj.optString("status");
            if ("answered".equals(st)) answered++;
            else if ("missed".equals(st)) missed++;
            List<JSONObject> l = by.get(s.key);
            if (l != null) for (JSONObject x : l) vals.add(x.optDouble("v"));
        }
        // Antwoorden die niet (meer) bij een periode horen, bijv. na het wijzigen van het ritme: wel laten zien
        JSONArray extra = new JSONArray();
        for (Map.Entry<String, List<JSONObject>> e : by.entrySet()) {
            if (keys.contains(e.getKey())) continue;
            String d = Checkin.keyDay(e.getKey());
            if (d.isEmpty() || d.compareTo(from) < 0 || d.compareTo(to) >= 0) continue;
            for (JSONObject x : e.getValue()) { extra.put(x); vals.add(x.optDouble("v")); }
        }
        JSONArray opts = q.optJSONArray("options");
        double[] ov = new double[opts == null ? 0 : opts.length()], av = new double[vals.size()];
        for (int i = 0; i < ov.length; i++) ov[i] = opts.getJSONObject(i).optDouble("value");
        for (int i = 0; i < av.length; i++) av[i] = vals.get(i);
        JSONArray counts = new JSONArray();
        for (int n : Checkin.counts(ov, av)) counts.put(n);
        double avg = Checkin.average(av);
        JSONObject sum = new JSONObject().put("counts", counts).put("answered", answered).put("missed", missed).put("n", av.length)
                .put("streak", streak(r, by, now, tz));
        if (!Double.isNaN(avg)) sum.put("avg", Math.round(avg * 100) / 100.0);
        return new JSONObject().put("q", q).put("slots", slots).put("extra", extra).put("summary", sum);
    }

    // ---------- slaap en agenda per dag ----------

    static boolean calendarOk(Context c) { return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED; }

    /**
     * Per dag (yyyy-MM-dd) in [from, to): de nacht ervoor uit Slaappraat (hoe lang hij luisterde, hoe vaak iets gehoord
     * of gepraat) en, als je toestemming gaf, je agenda (aantal afspraken, uren bezet). Alleen lezen, op de telefoon.
     */
    static JSONObject days(Context c, String from, String to) throws Exception {
        TimeZone tz = TimeZone.getDefault();
        Calendar f = Checkin.day(from, tz), t = Checkin.day(to, tz);
        JSONObject days = new JSONObject();
        boolean cal = calendarOk(c);
        JSONObject out = new JSONObject().put("cal", cal).put("days", days);
        if (f == null || t == null || !t.after(f) || t.getTimeInMillis() - f.getTimeInMillis() > 400 * 86_400_000L) return out;
        // Een nacht hoort bij de ochtend erna: gestart om 23:00 op donderdag = de nacht voor vrijdag
        JSONArray ns = SleepTalk.nights(c);
        for (int i = 0; i < ns.length(); i++) {
            JSONObject n = ns.optJSONObject(i);
            if (n == null || n.optLong("end") <= n.optLong("start")) continue;
            String d = Checkin.dateKey(n.optLong("start") + 12 * 3_600_000L, tz);
            if (d.compareTo(from) < 0 || d.compareTo(to) >= 0) continue;
            JSONObject x = day(days, d);
            JSONObject s = x.optJSONObject("sleep");
            long min = (n.optLong("end") - n.optLong("start")) / 60_000L;
            int talk = n.optInt("talk"), heard = n.optInt("talk") + n.optInt("noise") + n.optInt("open");
            if (s != null) { min += s.optLong("min"); talk += s.optInt("talk"); heard += s.optInt("heard"); }
            x.put("sleep", new JSONObject().put("min", min).put("talk", talk).put("heard", heard));
        }
        if (cal) try { calendar(c, f, t, tz, days); } catch (Exception e) { App.log(c, "CHECKIN", "agenda: " + e); out.put("calError", true); }
        return out;
    }

    private static JSONObject day(JSONObject days, String d) throws Exception {
        JSONObject x = days.optJSONObject(d);
        if (x == null) { x = new JSONObject(); days.put(d, x); }
        return x;
    }

    private static void calendar(Context c, Calendar f, Calendar t, TimeZone tz, JSONObject days) throws Exception {
        long a = f.getTimeInMillis(), b = t.getTimeInMillis();
        Uri.Builder ub = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(ub, a - 86_400_000L);
        android.content.ContentUris.appendId(ub, b + 86_400_000L);
        Map<String, List<long[]>> busy = new HashMap<>();
        Map<String, int[]> count = new HashMap<>(); // [afspraken, hele dag]
        try (Cursor cur = c.getContentResolver().query(ub.build(), new String[]{CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.SELF_ATTENDEE_STATUS},
                CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                long begin = cur.getLong(0), end = cur.getLong(1);
                boolean allDay = cur.getInt(2) == 1;
                if (cur.getInt(3) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue; // afgezegd
                if (allDay) { begin -= tz.getOffset(begin); end -= tz.getOffset(end); } // hele dag: in UTC opgeslagen
                if (end <= begin) end = begin + 1;
                Calendar d = Calendar.getInstance(tz);
                d.setTimeInMillis(Math.max(begin, a));
                d.set(Calendar.HOUR_OF_DAY, 0); d.set(Calendar.MINUTE, 0); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
                for (int guard = 0; guard < 400 && d.getTimeInMillis() < Math.min(end, b); guard++) {
                    long ds = d.getTimeInMillis();
                    Calendar nx = (Calendar) d.clone(); nx.add(Calendar.DAY_OF_MONTH, 1);
                    long de = nx.getTimeInMillis();
                    if (end > ds && begin < de) {
                        String k = Checkin.dateKey(d);
                        int[] n = count.get(k);
                        if (n == null) { n = new int[2]; count.put(k, n); }
                        if (allDay) n[1]++;
                        else {
                            n[0]++;
                            List<long[]> l = busy.get(k);
                            if (l == null) { l = new ArrayList<>(); busy.put(k, l); }
                            l.add(new long[]{Math.max(begin, ds), Math.min(end, de)});
                        }
                    }
                    d = nx;
                }
            }
        }
        for (Map.Entry<String, int[]> e : count.entrySet()) {
            List<long[]> l = busy.get(e.getKey());
            long ms = l == null ? 0 : unionMs(l);
            day(days, e.getKey()).put("cal", new JSONObject().put("n", e.getValue()[0]).put("allDay", e.getValue()[1]).put("busy", ms / 60_000L));
        }
    }

    /** Totale tijd van overlappende stukken, zonder dubbel te tellen. Zuiver (getest). */
    static long unionMs(List<long[]> l) {
        List<long[]> s = new ArrayList<>(l);
        java.util.Collections.sort(s, (x, y) -> Long.compare(x[0], y[0]));
        long total = 0, cs = Long.MIN_VALUE, ce = Long.MIN_VALUE;
        for (long[] x : s) {
            if (x[0] > ce) { if (ce > cs) total += ce - cs; cs = x[0]; ce = x[1]; }
            else ce = Math.max(ce, x[1]);
        }
        if (ce > cs) total += ce - cs;
        return total;
    }

    // ---------- zoeken en de assistent ----------

    static String qTitle(JSONObject q) { return (q.optString("emoji") + " " + q.optString("text")).trim(); }

    /** Zoeken in je notities en labels bij de antwoorden (voor Zoeken): [{q, p, t, title, text}], nieuwste eerst. */
    static synchronized JSONArray search(Context c, String ql) throws Exception {
        load(c);
        JSONArray out = new JSONArray(), a = answers;
        if (ql == null || ql.isEmpty()) return out;
        List<JSONObject> hits = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            String labels = join(x.optJSONArray("labels"));
            String hay = (x.optString("note") + " " + labels).toLowerCase(Locale.ROOT);
            if (hay.contains(ql)) hits.add(x);
        }
        java.util.Collections.sort(hits, (x, y) -> Long.compare(y.optLong("t"), x.optLong("t")));
        for (JSONObject x : hits) {
            JSONObject q = question(c, x.optString("q"));
            if (q == null) continue;
            String labels = join(x.optJSONArray("labels"));
            String text = (x.optString("e") + " " + x.optString("l")).trim() + (labels.isEmpty() ? "" : " · " + labels) + (x.optString("note").isEmpty() ? "" : " · " + x.optString("note"));
            out.put(new JSONObject().put("q", x.optString("q")).put("p", x.optString("p")).put("t", x.optLong("t")).put("title", qTitle(q)).put("text", text));
            if (out.length() >= 200) break;
        }
        return out;
    }

    static String join(JSONArray a) {
        StringBuilder b = new StringBuilder();
        if (a != null) for (int i = 0; i < a.length(); i++) { if (b.length() > 0) b.append(", "); b.append(a.optString(i)); }
        return b.toString();
    }

    private static final String[] MONTHS = {"januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december"};
    private static final String[] DOW = {"", "ma", "di", "wo", "do", "vr", "za", "zo"};

    static String monthName(String ym) { return MONTHS[Integer.parseInt(ym.substring(5, 7)) - 1] + " " + ym.substring(0, 4); }

    /** "do 29 okt", "week 44", "oktober 2026" of "do 29 okt 21:00". */
    static String keyLabel(String key) {
        if (key == null) return "";
        if (key.matches("\\d{4}-W\\d{2}")) return "week " + Integer.parseInt(key.substring(6)) + " van " + key.substring(0, 4);
        if (key.length() == 7) return monthName(key);
        Calendar k = Checkin.day(key.substring(0, Math.min(10, key.length())), TimeZone.getTimeZone("UTC"));
        if (k == null) return key;
        String s = DOW[Checkin.isoDow(k)] + " " + k.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[k.get(Calendar.MONTH)].substring(0, 3);
        return key.length() == 16 ? s + " " + key.substring(11) : s;
    }

    /**
     * Voor slim zoeken en de assistent: per vraag per maand een stuk tekst met een samenvatting en elk antwoord.
     * [id, titel, regels, tijden, laatste tijd]
     */
    static synchronized List<Object[]> monthDocs(Context c) throws Exception {
        List<Object[]> out = new ArrayList<>();
        JSONArray qs = questions(c);
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.getJSONObject(i);
            Map<String, List<JSONObject>> by = byKey(c, q.optString("id"));
            Map<String, List<JSONObject>> months = new java.util.TreeMap<>();
            for (Map.Entry<String, List<JSONObject>> e : by.entrySet()) {
                String d = Checkin.keyDay(e.getKey());
                if (d.isEmpty()) continue;
                String m = d.substring(0, 7);
                List<JSONObject> l = months.get(m);
                if (l == null) { l = new ArrayList<>(); months.put(m, l); }
                l.addAll(e.getValue());
            }
            JSONArray opts = q.optJSONArray("options");
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            if (opts != null) for (int k = 0; k < opts.length(); k++) { double v = opts.getJSONObject(k).optDouble("value"); lo = Math.min(lo, v); hi = Math.max(hi, v); }
            for (Map.Entry<String, List<JSONObject>> e : months.entrySet()) {
                List<JSONObject> l = e.getValue();
                java.util.Collections.sort(l, (x, y) -> x.optString("p").compareTo(y.optString("p")) != 0 ? x.optString("p").compareTo(y.optString("p")) : Long.compare(x.optLong("t"), y.optLong("t")));
                double[] vs = new double[l.size()];
                Map<String, Integer> n = new java.util.LinkedHashMap<>();
                if (opts != null) for (int k = opts.length() - 1; k >= 0; k--) n.put((opts.getJSONObject(k).optString("emoji") + " " + opts.getJSONObject(k).optString("label")).trim(), 0);
                long last = 0;
                List<String> lines = new ArrayList<>();
                List<Long> times = new ArrayList<>();
                for (int k = 0; k < l.size(); k++) {
                    JSONObject a = l.get(k);
                    vs[k] = a.optDouble("v");
                    String lab = (a.optString("e") + " " + a.optString("l")).trim();
                    n.put(lab, (n.containsKey(lab) ? n.get(lab) : 0) + 1);
                    last = Math.max(last, a.optLong("t"));
                }
                StringBuilder head = new StringBuilder("Check-in \"" + q.optString("text") + "\" in " + monthName(e.getKey()) + ": " + l.size() + " keer ingevuld.");
                StringBuilder cnt = new StringBuilder();
                for (Map.Entry<String, Integer> x : n.entrySet()) if (x.getValue() > 0) cnt.append(cnt.length() > 0 ? ", " : " ").append(x.getValue()).append("× ").append(x.getKey());
                head.append(cnt);
                double avg = Checkin.average(vs);
                // "Hoger is beter" alleen bij de vaste soorten (bij zelf gekozen antwoorden weet de app dat niet zeker)
                if (!Double.isNaN(avg) && hi > lo) head.append(". Gemiddeld ").append(String.format(Locale.ROOT, "%.1f", avg).replace('.', ',')).append(" (op een schaal van ")
                        .append(fmt(lo)).append(" tot ").append(fmt(hi)).append("custom".equals(q.optString("scale")) ? ")" : ", hoger is beter)");
                lines.add(head.append('.').toString());
                times.add(last);
                for (JSONObject a : l) {
                    String labels = join(a.optJSONArray("labels"));
                    lines.add("- " + keyLabel(a.optString("p")) + ": " + (a.optString("e") + " " + a.optString("l")).trim()
                            + (labels.isEmpty() ? "" : " · " + labels) + (a.optString("note").isEmpty() ? "" : " · notitie: " + a.optString("note"))
                            + (a.optBoolean("late") ? " (achteraf ingevuld)" : ""));
                    times.add(a.optLong("t"));
                }
                out.add(new Object[]{q.optString("id") + "|" + e.getKey(), q.optString("text") + " · " + monthName(e.getKey()), lines, times, last});
            }
        }
        return out;
    }

    static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v).replace('.', ','); }

    /** Vingerafdruk voor slim zoeken: verandert bij elke wijziging. */
    static String stamp(Context c) { return aFile(c).lastModified() + "/" + aFile(c).length() + "/" + qFile(c).lastModified(); }

    // ---------- Alles back-uppen (privé onderdeel) ----------

    /** Voor de keuze bij Alles back-uppen: aantallen en grootte. */
    static synchronized void sizes(Context c, JSONObject o) {
        try { load(c); o.put("ciQ", qs(c).length()).put("ciA", answers.length()).put("ciBytes", qFile(c).length() + aFile(c).length()); } catch (Exception ignored) { }
    }

    /** Vragen en antwoorden naar Check-in/checkin.json. Geeft het aantal antwoorden (of vragen); -1 = niets te bewaren. */
    static int export(Context c) throws Exception {
        JSONArray qs, as;
        synchronized (CheckinStore.class) {
            if (brokenAside(c)) throw new Exception("Check-in was niet te lezen (apart gezet); de vorige backup blijft staan");
            qs = questions(c); as = answers(c);
        }
        if (qs.length() == 0 && as.length() == 0) return -1;
        JSONObject o = new JSONObject().put("format", FORMAT).put("kind", "checkin").put("t", System.currentTimeMillis()).put("questions", qs).put("answers", as);
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
        WaBackup.DestDir dir = dest.dir(DIR, true);
        try (Sms.Out w = Sms.open(dest, dir, FILE, "application/json")) { w.write(o.toString()); w.done(); }
        return as.length() > 0 ? as.length() : qs.length();
    }

    private static JSONArray pendingQ, pendingA;

    static String sig(JSONObject a) { return a.optString("q") + "|" + a.optString("p") + "|" + a.optLong("t") + "|" + a.optDouble("v"); }

    /**
     * Een voorstel dat nog onaangeroerd is (tpl, geen antwoorden): mag plaatsmaken voor de vraag met hetzelfde id uit
     * een backup (op een nieuwe telefoon staan "voel" en "werk" er al als voorstel).
     */
    private static boolean untouchedTemplate(JSONObject q) {
        if (q == null || !q.optBoolean("tpl")) return false;
        for (int i = 0; i < answers.length(); i++) { JSONObject a = answers.optJSONObject(i); if (a != null && q.optString("id").equals(a.optString("q"))) return false; }
        return true;
    }

    /** Overzicht van wat er terug zou komen (er wordt niets overschreven; alleen een onaangeroerd voorstel maakt plaats). */
    static synchronized JSONObject preview(Context c, String text) throws Exception {
        JSONObject o = AiSleepBackup.parse(text, "checkin", "Check-in-backup (checkin.json)");
        JSONArray inQ = o.optJSONArray("questions"), inA = o.optJSONArray("answers");
        if (inQ == null) inQ = new JSONArray();
        if (inA == null) inA = new JSONArray();
        load(c);
        JSONArray qs = qs(c);
        Set<String> known = new HashSet<>(), oneQ = new HashSet<>();
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.getJSONObject(i);
            known.add(q.optString("id"));
            if (!"many".equals(q.optString("perPeriod"))) oneQ.add(q.optString("id"));
        }
        JSONArray freshQ = new JSONArray(), sample = new JSONArray();
        Set<String> seenIn = new HashSet<>();
        int qDup = 0, qBad = 0;
        for (int i = 0; i < inQ.length(); i++) {
            JSONObject x = inQ.optJSONObject(i);
            String id = x == null ? "" : x.optString("id");
            if (!id.matches("[A-Za-z0-9_-]{1,40}")) { qBad++; continue; }
            if (!seenIn.add(id)) { qDup++; continue; }
            boolean replace = known.contains(id) && untouchedTemplate(qLive(c, id));
            if (known.contains(id) && !replace) { qDup++; continue; }
            JSONObject cq;
            try { cq = clean(x, null); } catch (Exception e) { qBad++; continue; }
            // Teruggezette vragen staan eerst uit: pas meldingen als Rene ze zelf aanzet
            cq.put("id", id).put("on", false).put("tpl", false).put("created", x.optLong("created", System.currentTimeMillis()));
            if (replace) cq.put("replaces", true);
            freshQ.put(cq);
            known.add(id);
            if ("many".equals(cq.optString("perPeriod"))) oneQ.remove(id); else oneQ.add(id);
            if (sample.length() < 6) sample.put(qTitle(cq));
        }
        Set<String> haveIds = new HashSet<>(), haveSigs = new HashSet<>(), haveQP = new HashSet<>();
        for (int i = 0; i < answers.length(); i++) {
            JSONObject a = answers.getJSONObject(i);
            haveIds.add(a.optString("id")); haveSigs.add(sig(a)); haveQP.add(a.optString("q") + "|" + a.optString("p"));
        }
        List<String> ids = new ArrayList<>(), sigs = new ArrayList<>(), qq = new ArrayList<>(), keys = new ArrayList<>();
        for (int i = 0; i < inA.length(); i++) {
            JSONObject a = inA.optJSONObject(i);
            ids.add(a == null ? "" : a.optString("id")); sigs.add(a == null ? "" : sig(a)); qq.add(a == null ? "" : a.optString("q")); keys.add(a == null ? "" : a.optString("p"));
        }
        int[] plan = Checkin.answerPlan(haveIds, haveSigs, known, oneQ, haveQP, ids, sigs, qq, keys);
        JSONArray freshA = new JSONArray();
        int aDup = 0, aBad = 0;
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] == Checkin.DUP) { aDup++; continue; }
            if (plan[i] == Checkin.BAD) { aBad++; continue; }
            JSONObject a = inA.getJSONObject(i);
            JSONArray labels = new JSONArray(), li = a.optJSONArray("labels");
            if (li != null) for (int k = 0; k < li.length() && labels.length() < 16; k++) { String l = cleanText(li.optString(k), 30); if (!l.isEmpty()) labels.put(l); }
            freshA.put(new JSONObject().put("id", a.optString("id")).put("q", a.optString("q")).put("p", a.optString("p")).put("t", a.optLong("t"))
                    .put("o", a.optInt("o")).put("v", a.optDouble("v", 0)).put("l", cleanText(a.optString("l"), 40)).put("e", cleanText(a.optString("e"), 16))
                    .put("note", cleanNote(a.optString("note"))).put("labels", labels).put("late", a.optBoolean("late")).put("src", "backup"));
        }
        pendingQ = freshQ; pendingA = freshA;
        return new JSONObject().put("kind", "checkin").put("qTotal", inQ.length()).put("qFresh", freshQ.length()).put("qDup", qDup)
                .put("aTotal", inA.length()).put("aFresh", freshA.length()).put("aDup", aDup).put("bad", qBad + aBad)
                .put("total", inQ.length() + inA.length()).put("fresh", freshQ.length() + freshA.length()).put("sample", sample);
    }

    static synchronized void cancel() { pendingQ = null; pendingA = null; }

    /** Toevoegen wat nieuw is (nogmaals nagekeken: intussen kan er iets bijgekomen zijn). Geeft het aantal. */
    static synchronized int apply(Context c) throws Exception {
        JSONArray inQ = pendingQ, inA = pendingA;
        pendingQ = null; pendingA = null;
        if (inQ == null || inA == null) throw new Exception("Niets om terug te zetten");
        load(c);
        JSONArray qs = db.optJSONArray("questions");
        int n = 0, next = db.optInt("next", 1);
        for (int i = 0; i < inQ.length(); i++) {
            JSONObject q = new JSONObject(inQ.getJSONObject(i).toString());
            boolean replace = q.optBoolean("replaces");
            q.remove("replaces");
            int at = -1;
            for (int k = 0; k < qs.length(); k++) if (q.optString("id").equals(qs.getJSONObject(k).optString("id"))) at = k;
            if (at >= 0) {
                // Alleen over een voorstel dat nog steeds onaangeroerd is (zelfde nummer voor de meldingen; staat uit)
                if (!replace || !untouchedTemplate(qs.getJSONObject(at))) continue;
                qs.put(at, q.put("n", qs.getJSONObject(at).optInt("n")).put("on", false).put("updated", System.currentTimeMillis()));
            } else {
                if (qs.length() >= MAX_QUESTIONS) continue;
                qs.put(q.put("n", next++).put("updated", System.currentTimeMillis()));
            }
            n++;
        }
        db.put("next", next).put("seeded", true);
        saveQ(c);
        Set<String> have = new HashSet<>(), oneQ = new HashSet<>(), ids = new HashSet<>(), sigs = new HashSet<>(), qp = new HashSet<>();
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.getJSONObject(i);
            have.add(q.optString("id"));
            if (!"many".equals(q.optString("perPeriod"))) oneQ.add(q.optString("id"));
        }
        for (int i = 0; i < answers.length(); i++) { JSONObject a = answers.getJSONObject(i); ids.add(a.optString("id")); sigs.add(sig(a)); qp.add(a.optString("q") + "|" + a.optString("p")); }
        for (int i = 0; i < inA.length() && answers.length() < MAX_ANSWERS; i++) {
            JSONObject a = inA.getJSONObject(i);
            String k = a.optString("q") + "|" + a.optString("p");
            if (!have.contains(a.optString("q")) || ids.contains(a.optString("id")) || sigs.contains(sig(a)) || (oneQ.contains(a.optString("q")) && qp.contains(k))) continue;
            answers.put(a); ids.add(a.optString("id")); sigs.add(sig(a)); qp.add(k);
            n++;
        }
        saveA(c);
        return n;
    }
}
