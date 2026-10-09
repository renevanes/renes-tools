package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wat buiten de grote onderdelen valt, maar wel weg is na een nieuwe telefoon:
 * automatiseringen (met opgenomen tikstappen), radiofavorieten en de radiowekker, geplande Auto redial,
 * notitieherinneringen, de keuzes voor meldingen en backups, tekstgrootte en de instellingen van de interface.
 * Plus: transcripties (volledig, terug te zetten), routes als GPX, en terugzetten van herkende muziek.
 *
 * Terugzetten voegt alleen toe wat er nog niet is; bestaande instellingen worden nooit overschreven.
 * Geheimen (AudD-sleutel, WhatsApp-sleutel, sleutels van het app-slot en de versleuteling) gaan nooit mee.
 */
final class SettingsBackup {

    private SettingsBackup() { }

    static final String DIR = "Instellingen", FILE = "instellingen.json", FORMAT = "renes-tools-settings";
    static final String TX_FILE = "transcripties.json", TX_FORMAT = "renes-tools-transcripts";
    static final String ROUTES_DIR = "Routes";

    /** prefs-bestand → sleutels (null = alle sleutels behalve de genoemde uitzonderingen). */
    static final String[][] KEEP = {
            {"auto", "rules"},
            {"radio", "favs"},
            {"alarm", null},
            {"redialplan", "plan"},
            {"reminders", null},
            {"notifcenter", null},
            {"allbackup", null},
            {"ui", "textZoom"},
            {"webstore", "all"},
    };
    /** Nooit meenemen: toestand die bij deze installatie of dit moment hoort. */
    static final Set<String> SKIP = new HashSet<>(java.util.Arrays.asList("nextAt", "snoozeAt", "last", "next", "missed", "missedAt"));

    static boolean keep(String key, String only) {
        if (only != null) return only.equals(key);
        return !SKIP.contains(key);
    }

    static JSONObject snapshot(Context c) throws Exception {
        JSONObject prefs = new JSONObject();
        for (String[] k : KEEP) {
            SharedPreferences p = c.getSharedPreferences(k[0], Context.MODE_PRIVATE);
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (!keep(e.getKey(), k[1])) continue;
                Object v = e.getValue();
                String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Float ? "f" : v instanceof String ? "s" : null;
                if (t == null) continue; // sets e.d. niet nodig
                o.put(e.getKey(), new JSONObject().put("t", t).put("v", v));
            }
            if (o.length() > 0) prefs.put(k[0], o);
        }
        return new JSONObject().put("format", FORMAT).put("version", 1).put("exportedAt", System.currentTimeMillis()).put("prefs", prefs)
                .put("podcasts", Podcasts.backupJson(c));
    }

    static int export(Context c) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        JSONObject o = snapshot(c);
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(DIR, true);
        try (Sms.Out w = Sms.open(dest, dir, FILE, "application/json")) { w.write(o.toString(2)); w.done(); }
        return count(o);
    }

    static int count(JSONObject o) {
        JSONObject p = o.optJSONObject("prefs");
        if (p == null) return 0;
        int n = 0;
        n += arrLen(p, "auto", "rules");
        n += arrLen(p, "radio", "favs");
        if (p.optJSONObject("alarm") != null) n++;
        if (p.optJSONObject("redialplan") != null) n++;
        JSONObject r = p.optJSONObject("reminders");
        if (r != null) n += r.length();
        return Math.max(n, 1);
    }

    static int arrLen(JSONObject p, String file, String key) {
        try {
            JSONObject f = p.optJSONObject(file);
            JSONObject e = f == null ? null : f.optJSONObject(key);
            return e == null ? 0 : new JSONArray(e.optString("v", "[]")).length();
        } catch (Exception ex) { return 0; }
    }

    /** Overzicht: {kind, total, fresh, dup, rules, favs, alarm, plan, reminders, sample}. */
    static JSONObject preview(Context c, String text) throws Exception {
        JSONObject o;
        try { o = new JSONObject(text.trim()); } catch (Exception e) { throw new Exception("Dit is geen instellingen-backup (instellingen.json)"); }
        if (!FORMAT.equals(o.optString("format"))) throw new Exception("Dit is geen instellingen-backup (instellingen.json)");
        Plan p = plan(c, o);
        return new JSONObject().put("kind", "settings").put("total", p.total).put("fresh", p.fresh).put("dup", p.total - p.fresh)
                .put("rules", p.rules).put("favs", p.favs).put("alarm", p.alarm).put("plan", p.redial).put("reminders", p.reminders)
                .put("sample", p.sample);
    }

    static final class Plan {
        int total, fresh, rules, favs, reminders;
        boolean alarm, redial;
        JSONArray sample = new JSONArray();
        JSONArray newRules = new JSONArray(), newFavs = new JSONArray();
        JSONObject scalars = new JSONObject(); // file → {key: {t,v}} alleen ontbrekende
        Set<String> replace = new HashSet<>(); // bestanden die als geheel worden teruggezet
        String webstore = null;
        JSONObject podcasts = null; // abonnementen en waar je was
    }

    /** Wat er toegevoegd zou worden (alleen wat ontbreekt). */
    static Plan plan(Context c, JSONObject o) throws Exception {
        Plan pl = new Plan();
        JSONObject prefs = o.optJSONObject("prefs");
        if (prefs == null) return pl;
        // Automatiseringen per id
        JSONArray inRules = arr(prefs, "auto", "rules"), cur = Auto.rules(c);
        Set<String> have = new HashSet<>();
        for (int i = 0; i < cur.length(); i++) have.add(cur.optJSONObject(i) == null ? "" : cur.optJSONObject(i).optString("id"));
        for (int i = 0; i < inRules.length(); i++) {
            JSONObject r = inRules.optJSONObject(i);
            if (r == null) continue;
            pl.total++;
            String id = Restore.safeId(r.optString("id"), "a");
            if (have.contains(id)) continue;
            r.put("id", id);
            have.add(id);
            pl.newRules.put(r); pl.rules++; pl.fresh++;
            if (pl.sample.length() < 8) pl.sample.put(r.optString("name", "Automatisering"));
        }
        // Favorieten per adres
        JSONArray inFavs = arr(prefs, "radio", "favs"), curFavs = Radio.favorites(c);
        Set<String> urls = new HashSet<>();
        for (int i = 0; i < curFavs.length(); i++) urls.add(curFavs.optJSONObject(i) == null ? "" : curFavs.optJSONObject(i).optString("url"));
        for (int i = 0; i < inFavs.length(); i++) {
            JSONObject s = inFavs.optJSONObject(i);
            if (s == null) continue;
            pl.total++;
            String url = s.optString("url");
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue;
            if (urls.contains(url)) continue;
            urls.add(url);
            pl.newFavs.put(s); pl.favs++; pl.fresh++;
            if (pl.sample.length() < 8) pl.sample.put("📻 " + s.optString("name"));
        }
        // Podcasts die je hier nog niet volgt (en waar je was in afleveringen)
        JSONObject pc = o.optJSONObject("podcasts");
        if (pc != null) {
            JSONArray subs = pc.optJSONArray("subs");
            boolean any = pc.optJSONObject("progress") != null && pc.optJSONObject("progress").length() > 0;
            if (subs != null) for (int i = 0; i < subs.length(); i++) {
                JSONObject s = subs.optJSONObject(i);
                if (s == null || !Podcasts.httpUrl(s.optString("feed"))) continue;
                pl.total++;
                if (Podcasts.isSub(c, Podcasts.podId(s.optString("feed")))) continue;
                pl.fresh++; any = true;
                if (pl.sample.length() < 8) pl.sample.put("🎧 " + s.optString("title"));
            }
            if (any) pl.podcasts = pc;
        }
        // Interface-instellingen: altijd aanbieden; de interface neemt alleen over wat daar ontbreekt
        JSONObject ws = prefs.optJSONObject("webstore");
        if (ws != null && ws.optJSONObject("all") != null) { pl.webstore = ws.getJSONObject("all").optString("v", ""); pl.total++; pl.fresh++; }
        long now = System.currentTimeMillis();
        // Losse instellingen: alleen als ze hier nog niet bestaan
        for (String[] k : KEEP) {
            if (("auto".equals(k[0]) && "rules".equals(k[1])) || ("radio".equals(k[0]) && "favs".equals(k[1])) || "webstore".equals(k[0])) continue;
            JSONObject f = prefs.optJSONObject(k[0]);
            if (f == null) continue;
            SharedPreferences p = c.getSharedPreferences(k[0], Context.MODE_PRIVATE);
            // De radiowekker is één geheel: alleen terugzetten als hij hier nog niet is ingesteld (uit en zonder zender)
            if ("alarm".equals(k[0])) {
                if (p.getBoolean("on", false) || p.contains("station")) { pl.total++; continue; }
                pl.total++; pl.fresh++; pl.alarm = true; pl.sample.put("⏰ Radiowekker");
                JSONObject all = new JSONObject();
                java.util.Iterator<String> it = f.keys();
                while (it.hasNext()) { String key = it.next(); if (keep(key, null)) all.put(key, f.get(key)); }
                pl.scalars.put("alarm", all); pl.replace.add("alarm");
                continue;
            }
            JSONObject add = new JSONObject();
            java.util.Iterator<String> it = f.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (!keep(key, k[1])) continue;
                pl.total++;
                if (p.contains(key)) continue;
                Object v = f.get(key);
                if ("reminders".equals(k[0])) {
                    // Verlopen eenmalige herinneringen overslaan; herhalende naar het eerstvolgende moment
                    try {
                        JSONObject r = new JSONObject(((JSONObject) v).optString("v"));
                        long t = r.optLong("t");
                        String rep = r.optString("rep");
                        if (t < now) {
                            if (rep.isEmpty()) continue;
                            r.put("t", Reminders.next(t, rep, now, r.optInt("dom", 0)));
                            v = new JSONObject().put("t", "s").put("v", r.toString());
                        }
                    } catch (Exception e) { continue; }
                }
                add.put(key, v);
                pl.fresh++;
            }
            if (add.length() == 0) continue;
            pl.scalars.put(k[0], add);
            if ("redialplan".equals(k[0])) { pl.redial = true; pl.sample.put("🔁 Geplande Auto redial"); }
            if ("reminders".equals(k[0])) pl.reminders = add.length();
        }
        return pl;
    }

    static JSONArray arr(JSONObject prefs, String file, String key) {
        try {
            JSONObject f = prefs.optJSONObject(file);
            JSONObject e = f == null ? null : f.optJSONObject(key);
            return e == null ? new JSONArray() : new JSONArray(e.optString("v", "[]"));
        } catch (Exception ex) { return new JSONArray(); }
    }

    static int apply(Context c, String text) throws Exception {
        Plan pl = plan(c, new JSONObject(text.trim()));
        if (pl.newRules.length() > 0) {
            JSONArray all = Auto.rules(c);
            for (int i = 0; i < pl.newRules.length(); i++) all.put(pl.newRules.get(i));
            Auto.prefs(c).edit().putString("rules", all.toString()).apply();
        }
        if (pl.newFavs.length() > 0) {
            JSONArray all = Radio.favorites(c);
            for (int i = 0; i < pl.newFavs.length(); i++) all.put(pl.newFavs.get(i));
            Radio.prefs(c).edit().putString("favs", all.toString()).apply();
        }
        java.util.Iterator<String> files = pl.scalars.keys();
        while (files.hasNext()) {
            String file = files.next();
            JSONObject add = pl.scalars.getJSONObject(file);
            SharedPreferences.Editor e = c.getSharedPreferences(file, Context.MODE_PRIVATE).edit();
            if (pl.replace.contains(file)) e.clear();
            java.util.Iterator<String> keys = add.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject v = add.getJSONObject(k);
                switch (v.optString("t")) {
                    case "b": e.putBoolean(k, v.optBoolean("v")); break;
                    case "i": e.putInt(k, v.optInt("v")); break;
                    case "l": e.putLong(k, v.optLong("v")); break;
                    case "f": e.putFloat(k, (float) v.optDouble("v")); break;
                    case "s": e.putString(k, v.optString("v")); break;
                    default: break;
                }
            }
            e.commit();
        }
        if (pl.webstore != null && !pl.webstore.isEmpty())
            c.getSharedPreferences("webstore", Context.MODE_PRIVATE).edit().putString("restoreAll", pl.webstore).putBoolean("pending", true).apply();
        if (pl.podcasts != null) try { Podcasts.restore(c, pl.podcasts); } catch (Exception e) { App.log(c, "PODCAST", "terugzetten: " + e.getMessage()); }
        // Alles opnieuw inplannen
        try { Auto.armPlaces(c, true); } catch (Exception ignored) { }
        try { RadioAlarm.schedule(c); } catch (Exception ignored) { }
        try { RedialPlan.arm(c); } catch (Exception ignored) { }
        try { Reminders.rearm(c); } catch (Exception ignored) { }
        try { RadioWidget.refresh(c); } catch (Exception ignored) { }
        try { AllBackupJob.ensureScheduled(c); } catch (Exception ignored) { }
        return pl.fresh;
    }

    // ---------- transcripties ----------

    /** Alle transcripties volledig (met tijden en bijbehorend gesprek) in één bestand, naast de leesbare .txt-bestanden. */
    static int exportTranscripts(Context c) throws Exception {
        File[] ts = Transcribe.transcriptDir(c).listFiles();
        JSONArray all = new JSONArray();
        if (ts != null) for (File tf : ts) {
            if (!tf.getName().endsWith(".json")) continue;
            JSONObject t = Transcribe.loadTranscript(c, tf.getName().replace(".json", ""));
            if (t != null) all.put(t);
        }
        if (all.length() == 0) return -1;
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
        WaBackup.DestDir dir = dest.dir(Transcribe.DIR, true);
        JSONObject o = new JSONObject().put("format", TX_FORMAT).put("version", 1).put("exportedAt", System.currentTimeMillis()).put("transcripts", all);
        try (Sms.Out w = Sms.open(dest, dir, TX_FILE, "application/json")) { w.write(o.toString()); w.done(); }
        return all.length();
    }

    static JSONArray pendingTx;

    static JSONObject previewTranscripts(Context c, String text) throws Exception {
        JSONObject o;
        try { o = new JSONObject(text.trim()); } catch (Exception e) { throw new Exception("Dit is geen transcriptie-backup (transcripties.json)"); }
        if (!TX_FORMAT.equals(o.optString("format"))) throw new Exception("Dit is geen transcriptie-backup (transcripties.json)");
        JSONArray in = o.optJSONArray("transcripts"), fresh = new JSONArray(), sample = new JSONArray();
        if (in == null) in = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject t = in.optJSONObject(i);
            if (t == null) continue;
            String id = Restore.safeId(t.optString("id"), "t");
            if (Transcribe.transcriptFile(c, id).isFile()) continue;
            t = cleanTranscript(c, t, id);
            fresh.put(t);
            if (sample.length() < 8) sample.put(t.optString("name", t.optString("file", "Gesprek")));
        }
        pendingTx = fresh;
        return new JSONObject().put("kind", "transcripts").put("total", in.length()).put("fresh", fresh.length())
                .put("dup", in.length() - fresh.length()).put("sample", sample);
    }

    /**
     * Een transcript uit een backupbestand opnieuw opbouwen met alleen bekende velden en de juiste soorten
     * (getallen als getal): een gewijzigd bestand kan zo geen code of vreemde paden het scherm in krijgen.
     */
    static JSONObject cleanTranscript(Context c, JSONObject t, String id) throws Exception {
        JSONObject o = new JSONObject().put("id", id);
        for (String k : new String[]{"file", "model", "lang", "text", "number", "name", "kind"}) if (t.has(k)) o.put(k, t.optString(k));
        for (String k : new String[]{"size", "mtime", "duration", "created", "callDate"}) if (t.has(k)) o.put(k, t.optLong(k));
        String path = t.optString("path");
        if (Transcribe.allowedRecording(c, path, false)) o.put("path", path);
        JSONArray segs = new JSONArray(), in = t.optJSONArray("segments");
        if (in != null) for (int i = 0; i < in.length(); i++) {
            JSONObject s = in.optJSONObject(i);
            if (s != null) segs.put(new JSONObject().put("from", Math.max(0, s.optLong("from"))).put("to", Math.max(0, s.optLong("to"))).put("text", s.optString("text")));
        }
        return o.put("segments", segs);
    }

    static int applyTranscripts(Context c) throws Exception {
        JSONArray l = pendingTx;
        pendingTx = null;
        if (l == null) return 0;
        int n = 0;
        for (int i = 0; i < l.length(); i++) {
            JSONObject t = l.getJSONObject(i);
            Contacts.writeText(Transcribe.transcriptFile(c, t.getString("id")), t.toString(), false);
            Transcribe.saveMeta(c, t);
            n++;
        }
        return n;
    }

    // ---------- herkende muziek ----------

    static JSONArray pendingMusic;

    static JSONObject previewMusic(Context c, String text) throws Exception {
        JSONArray in;
        try { in = new JSONArray(text.trim()); } catch (Exception e) { throw new Exception("Dit is geen muziek-backup (herkende-nummers.json)"); }
        JSONArray cur = Music.history(c), fresh = new JSONArray(), sample = new JSONArray();
        Set<String> have = new HashSet<>();
        for (int i = 0; i < cur.length(); i++) { JSONObject r = cur.optJSONObject(i); if (r != null) have.add(musicKey(r)); }
        for (int i = 0; i < in.length(); i++) {
            JSONObject r = in.optJSONObject(i);
            if (r == null || r.optString("title").isEmpty()) continue;
            if (!have.add(musicKey(r))) continue;
            JSONObject clean = new JSONObject();
            // Alleen bekende velden, als tekst; het tijdstip als getal (dit gaat later het scherm in)
            for (String k : new String[]{"artist", "title", "album", "date", "label", "link", "spotify", "apple", "art", "source", "station"})
                if (r.has(k)) clean.put(k, r.optString(k));
            if (r.has("release") && !clean.has("date")) clean.put("date", r.optString("release"));
            if (r.has("image") && !clean.has("art")) clean.put("art", r.optString("image"));
            clean.put("t", r.optLong("t"));
            fresh.put(clean);
            if (sample.length() < 8) sample.put(r.optString("artist") + " – " + r.optString("title"));
        }
        pendingMusic = fresh;
        return new JSONObject().put("kind", "music").put("total", in.length()).put("fresh", fresh.length())
                .put("dup", in.length() - fresh.length()).put("sample", sample);
    }

    static String musicKey(JSONObject r) { return r.optLong("t") + "|" + r.optString("artist") + "|" + r.optString("title"); }

    static int applyMusic(Context c) throws Exception {
        JSONArray add = pendingMusic;
        pendingMusic = null;
        if (add == null || add.length() == 0) return 0;
        synchronized (Music.class) {
            JSONArray cur = Music.history(c);
            List<JSONObject> all = new java.util.ArrayList<>();
            for (int i = 0; i < cur.length(); i++) all.add(cur.getJSONObject(i));
            for (int i = 0; i < add.length(); i++) all.add(add.getJSONObject(i));
            java.util.Collections.sort(all, (a, b) -> Long.compare(b.optLong("t"), a.optLong("t")));
            JSONArray out = new JSONArray();
            for (JSONObject r : all) { if (out.length() >= 500) break; out.put(r); }
            Contacts.writeText(Music.historyFile(c), out.toString(), false);
        }
        return add.length();
    }

    // ---------- routes ----------

    /** Elke route als GPX in Routes/ (bestaat hij al met dezelfde naam, dan overslaan). */
    static int exportRoutes(Context c) throws Exception {
        List<File> l = Tracks.saved(c);
        if (l.isEmpty()) return -1;
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
        WaBackup.DestDir dir = dest.dir(ROUTES_DIR, true);
        int n = 0;
        for (File trk : l) {
            String name = Tracks.gpxName(trk);
            if (dest.zip == null && dir.kids.containsKey(name)) { n++; continue; } // al eerder bewaard (in het versleutelde archief altijd alles)
            try (Sms.Out w = Sms.open(dest, dir, name, "application/gpx+xml")) {
                Tracks.writeGpx(w, Tracks.title(trk).isEmpty() ? "Route" : Tracks.title(trk), Tracks.backupPoints(trk));
                w.done(); // pas nu vervangt het nieuwe bestand het oude
            }
            n++;
        }
        return n;
    }
}
