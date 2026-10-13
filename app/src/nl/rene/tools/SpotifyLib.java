package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Spotify-backup van je bibliotheek (naast de playlists): nummers die je leuk vindt, albums, artiesten die je volgt,
 * podcasts, bewaarde afleveringen en luisterboeken, je top-artiesten en -nummers, wat je laatst luisterde en je profiel.
 * Wat uit Spotify verdwijnt, blijft bewaard (weg-lijst) en kan terug met PUT /me/library.
 */
final class SpotifyLib {
    private SpotifyLib() { }

    /** Soort: sleutel, Nederlandse naam, adres, veld in elk item (null = het item zelf), terug te zetten. */
    static final String[][] KINDS = {
            {"tracks", "Nummers die je leuk vindt", "/me/tracks?limit=50", "track", "1"},
            {"albums", "Bewaarde albums", "/me/albums?limit=50", "album", "1"},
            {"artists", "Artiesten die je volgt", "/me/following?type=artist&limit=50", null, "1"},
            {"shows", "Podcasts die je volgt", "/me/shows?limit=50", "show", "1"},
            {"episodes", "Bewaarde afleveringen", "/me/episodes?limit=50", "episode", "1"},
            {"audiobooks", "Luisterboeken", "/me/audiobooks?limit=50", "audiobook", "1"},
    };
    static final String[][] TOPS = {
            {"top-artists-short", "Top-artiesten (4 weken)", "/me/top/artists?time_range=short_term&limit=50"},
            {"top-artists-medium", "Top-artiesten (6 maanden)", "/me/top/artists?time_range=medium_term&limit=50"},
            {"top-artists-long", "Top-artiesten (ongeveer een jaar)", "/me/top/artists?time_range=long_term&limit=50"},
            {"top-tracks-short", "Top-nummers (4 weken)", "/me/top/tracks?time_range=short_term&limit=50"},
            {"top-tracks-medium", "Top-nummers (6 maanden)", "/me/top/tracks?time_range=medium_term&limit=50"},
            {"top-tracks-long", "Top-nummers (ongeveer een jaar)", "/me/top/tracks?time_range=long_term&limit=50"},
    };

    static File dir(Context c) { File d = new File(Spotify.dir(c), "bib"); d.mkdirs(); return d; }
    static File file(Context c, String kind) { return new File(dir(c), kind + ".json"); }
    static File goneFile(Context c, String kind) { return new File(dir(c), kind + "-weg.json"); }
    static File recentFile(Context c) { return new File(dir(c), "recent.json"); }
    static File profileFile(Context c) { return new File(dir(c), "profiel.json"); }

    static String label(String kind) {
        for (String[] k : KINDS) if (k[0].equals(kind)) return k[1];
        for (String[] k : TOPS) if (k[0].equals(kind)) return k[1];
        if (kind.equals("recent")) return "Laatst geluisterd";
        return kind;
    }

    // ---------- items ----------

    /** Een Spotify-object (nummer, album, artiest, show, aflevering, boek) als T. */
    static SpotifyDiff.T item(JSONObject it, String added) {
        SpotifyDiff.T t = new SpotifyDiff.T();
        t.uri = it.optString("uri"); t.name = it.optString("name");
        StringBuilder an = new StringBuilder();
        JSONArray ar = it.optJSONArray("artists");
        if (ar == null) ar = it.optJSONArray("authors");
        if (ar != null) for (int k = 0; k < ar.length(); k++) { JSONObject x = ar.optJSONObject(k); if (x == null) continue; if (an.length() > 0) an.append(", "); an.append(x.optString("name")); }
        if (an.length() == 0) an.append(it.optString("publisher"));
        t.artists = an.toString();
        JSONObject al = it.optJSONObject("album"), show = it.optJSONObject("show");
        t.album = al != null ? al.optString("name") : show != null ? show.optString("name") : "";
        if (t.artists.isEmpty() && show != null) t.artists = show.optString("publisher", show.optString("name"));
        t.dur = it.optLong("duration_ms");
        t.added = added == null ? "" : added;
        JSONObject ids = it.optJSONObject("external_ids");
        if (ids != null) t.isrc = ids.optString("isrc");
        return t;
    }

    /** Alle pagina's van een lijst ophalen (offset- of cursor-paginering). */
    static List<SpotifyDiff.T> fetch(Context c, String path, String field) throws Exception {
        List<SpotifyDiff.T> l = new ArrayList<>();
        String next = path;
        int guard = 0;
        while (next != null && !next.isEmpty() && guard++ < 1000) { // hooguit 50.000
            JSONObject page = Spotify.api(c, "GET", next, null);
            if (page.has("artists") && page.optJSONObject("artists") != null) page = page.getJSONObject("artists"); // /me/following
            JSONArray items = page.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject e = items.optJSONObject(i);
                if (e == null) continue;
                JSONObject it = field == null ? e : e.optJSONObject(field);
                if (it == null && field != null && e.has("uri")) it = e; // luisterboeken komen zonder omhulsel
                if (it == null || it.optString("uri").isEmpty()) continue;
                l.add(item(it, e.optString("added_at", "")));
            }
            String n = page.optString("next", "");
            next = n.isEmpty() || "null".equals(n) ? null : n;
            Spotify.progress = "Bibliotheek ophalen… (" + l.size() + ")";
            if (Spotify.cancel) throw new Exception("Gestopt door Android; de volgende keer verder");
        }
        return l;
    }

    static JSONArray toJson(List<SpotifyDiff.T> l) throws Exception {
        JSONArray a = new JSONArray();
        for (SpotifyDiff.T t : l) a.put(Spotify.trackJson(t));
        return a;
    }

    static List<SpotifyDiff.T> fromJson(JSONArray a) {
        List<SpotifyDiff.T> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) l.add(Spotify.fromJson(o)); }
        return l;
    }

    static JSONObject read(File f) {
        try { return f.isFile() ? new JSONObject(Contacts.readText(f, true)) : null; } catch (Exception e) { return null; }
    }

    static List<SpotifyDiff.T> stored(Context c, String kind) {
        JSONObject o = read(file(c, kind));
        return o == null ? null : fromJson(o.optJSONArray("items"));
    }

    // ---------- backup ----------

    /**
     * De bibliotheek bewaren. lines krijgt de wijzigingen. Geeft {kinds:{kind:n}, errors:[], relink:bool}.
     * Gooit alleen bij stoppen; een mislukte soort wordt overgeslagen (de vorige versie blijft).
     */
    static JSONObject backup(Context c, List<String> lines) throws Exception {
        JSONObject res = new JSONObject(), counts;
        try { counts = new JSONObject(Spotify.prefs(c).getString("libCounts", "{}")); } catch (Exception e) { counts = new JSONObject(); } // mislukte soort houdt zijn vorige telling
        JSONArray errors = new JSONArray();
        boolean relink = false;
        if (!Spotify.hasLibScopes(c)) { // oude koppeling: eerst opnieuw koppelen (geen zinloze aanroepen)
            Spotify.prefs(c).edit().putBoolean("libRelink", true).apply();
            return res.put("kinds", counts).put("errors", errors).put("relink", true);
        }
        // profiel
        try {
            JSONObject me = Spotify.api(c, "GET", "/me", null);
            JSONObject p = new JSONObject().put("id", me.optString("id")).put("naam", me.optString("display_name")).put("uri", me.optString("uri"));
            JSONObject ext = me.optJSONObject("external_urls");
            if (ext != null) p.put("url", ext.optString("spotify"));
            JSONArray imgs = me.optJSONArray("images");
            if (imgs != null && imgs.length() > 0 && imgs.optJSONObject(0) != null) p.put("foto", imgs.optJSONObject(0).optString("url"));
            Contacts.writeText(profileFile(c), p.put("t", System.currentTimeMillis()).toString(), true);
        } catch (Spotify.Scope s) { relink = true; } catch (Exception e) { if (Spotify.cancel) throw e; errors.put("Profiel: " + PodcastFeed.cut(String.valueOf(e.getMessage()), 120)); }
        // lijsten met verschillen
        for (String[] k : KINDS) {
            Spotify.progress = label(k[0]) + " ophalen…";
            List<SpotifyDiff.T> now;
            try { now = fetch(c, k[2], k[3]); }
            catch (Spotify.Scope s) { relink = true; continue; }
            catch (Exception e) {
                if (Spotify.cancel) throw e;
                if (!"audiobooks".equals(k[0])) errors.put(k[1] + ": " + PodcastFeed.cut(String.valueOf(e.getMessage()), 120)); // luisterboeken zijn er niet in elk land
                continue;
            }
            if (Spotify.cancel) throw new Exception("Gestopt door Android; de volgende keer verder");
            List<SpotifyDiff.T> before = stored(c, k[0]);
            if (before != null) {
                SpotifyDiff.Change ch = SpotifyDiff.diff(before, now);
                if (!ch.empty()) lines.add(k[1] + ": " + SpotifyDiff.summary(ch, 3));
                if (!ch.removed.isEmpty()) keepGone(c, k[0], ch.removed);
                if (!ch.added.isEmpty()) unGone(c, k[0], ch.added);
            } else if (!now.isEmpty()) lines.add(k[1] + ": " + now.size() + " bewaard");
            Contacts.writeText(file(c, k[0]), new JSONObject().put("t", System.currentTimeMillis()).put("items", toJson(now)).toString(), true);
            counts.put(k[0], now.size());
        }
        // top (alleen de laatste stand)
        for (String[] k : TOPS) {
            Spotify.progress = label(k[0]) + " ophalen…";
            try {
                JSONObject page = Spotify.api(c, "GET", k[2], null);
                JSONArray items = page.optJSONArray("items");
                List<SpotifyDiff.T> l = new ArrayList<>();
                if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject it = items.optJSONObject(i); if (it != null) l.add(item(it, "")); }
                Contacts.writeText(file(c, k[0]), new JSONObject().put("t", System.currentTimeMillis()).put("items", toJson(l)).toString(), true);
                counts.put(k[0], l.size());
            } catch (Spotify.Scope s) { relink = true; }
            catch (Exception e) { if (Spotify.cancel) throw e; errors.put(k[1] + ": " + PodcastFeed.cut(String.valueOf(e.getMessage()), 120)); }
        }
        // laatst geluisterd: bijhouden, zo groeit je luistergeschiedenis bij elke backup
        try {
            Spotify.progress = "Laatst geluisterd ophalen…";
            JSONObject page = Spotify.api(c, "GET", "/me/player/recently-played?limit=50", null);
            JSONArray items = page.optJSONArray("items");
            int added = mergeRecent(c, items);
            // (geen logregel: dat zou elke dag een wijziging zijn)
            JSONObject r = read(recentFile(c));
            counts.put("recent", r == null ? 0 : r.optJSONArray("items") == null ? 0 : r.optJSONArray("items").length());
        } catch (Spotify.Scope s) { relink = true; }
        catch (Exception e) { if (Spotify.cancel) throw e; errors.put("Laatst geluisterd: " + PodcastFeed.cut(String.valueOf(e.getMessage()), 120)); }
        Spotify.prefs(c).edit().putString("libCounts", counts.toString()).putBoolean("libRelink", relink).putLong("libAt", System.currentTimeMillis()).apply();
        return res.put("kinds", counts).put("errors", errors).put("relink", relink);
    }

    /** Nieuwe "laatst geluisterd" erbij (op tijdstip), hooguit 20.000. Geeft het aantal nieuwe. */
    static synchronized int mergeRecent(Context c, JSONArray items) throws Exception {
        if (items == null) return 0;
        JSONObject r = read(recentFile(c));
        JSONArray old = r == null ? new JSONArray() : r.optJSONArray("items");
        if (old == null) old = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < old.length(); i++) { JSONObject o = old.optJSONObject(i); if (o != null) seen.add(o.optString("p", o.optString("t")) + "|" + o.optString("u")); }
        List<JSONObject> add = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject e = items.optJSONObject(i);
            JSONObject tr = e == null ? null : e.optJSONObject("track");
            if (tr == null) continue;
            String played = e.optString("played_at");
            SpotifyDiff.T t = item(tr, played);
            if (seen.add(played + "|" + t.uri)) add.add(Spotify.trackJson(t).put("p", played));
        }
        if (add.isEmpty()) return 0;
        // nieuwste eerst
        JSONArray out = new JSONArray();
        java.util.Collections.sort(add, (a, b) -> b.optString("p").compareTo(a.optString("p")));
        for (JSONObject o : add) out.put(o);
        for (int i = 0; i < old.length() && out.length() < 20_000; i++) out.put(old.get(i));
        Contacts.writeText(recentFile(c), new JSONObject().put("t", System.currentTimeMillis()).put("items", out).toString(), true);
        return add.size();
    }

    // ---------- wat uit Spotify verdween ----------

    static synchronized void keepGone(Context c, String kind, List<SpotifyDiff.T> removed) throws Exception {
        JSONObject o = read(goneFile(c, kind));
        JSONArray a = o == null ? new JSONArray() : o.optJSONArray("items");
        if (a == null) a = new JSONArray();
        Set<String> have = new HashSet<>();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null) have.add(x.optString("u")); }
        String when = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
        for (SpotifyDiff.T t : removed) if (!t.uri.isEmpty() && have.add(t.uri)) a.put(Spotify.trackJson(t).put("weg", when));
        while (a.length() > 20_000) a.remove(0);
        Contacts.writeText(goneFile(c, kind), new JSONObject().put("items", a).toString(), true);
        setGoneCount(c, kind, a.length());
    }

    static synchronized void setGoneCount(Context c, String kind, int n) {
        try { JSONObject g = new JSONObject(Spotify.prefs(c).getString("libGone", "{}")); g.put(kind, n); Spotify.prefs(c).edit().putString("libGone", g.toString()).apply(); } catch (Exception ignored) { }
    }

    /** Weer in Spotify (opnieuw bewaard of teruggezet): niet meer bij "weg". */
    static synchronized void unGone(Context c, String kind, List<SpotifyDiff.T> back) throws Exception {
        JSONObject o = read(goneFile(c, kind));
        JSONArray a = o == null ? null : o.optJSONArray("items");
        if (a == null) return;
        Set<String> uris = new HashSet<>();
        for (SpotifyDiff.T t : back) uris.add(t.uri);
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null && !uris.contains(x.optString("u"))) out.put(x); }
        Contacts.writeText(goneFile(c, kind), new JSONObject().put("items", out).toString(), true);
        setGoneCount(c, kind, out.length());
    }

    static List<SpotifyDiff.T> gone(Context c, String kind) {
        JSONObject o = read(goneFile(c, kind));
        return o == null ? new ArrayList<>() : fromJson(o.optJSONArray("items"));
    }

    // ---------- overzicht voor het scherm ----------

    /** Overzicht uit de bewaarde aantallen (snel: geen bestanden openen). */
    static JSONObject summary(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONObject counts = new JSONObject(Spotify.prefs(c).getString("libCounts", "{}"));
            JSONObject goneN = new JSONObject(Spotify.prefs(c).getString("libGone", "{}"));
            if (counts.length() == 0 && file(c, "tracks").isFile()) { counts = countFiles(c); Spotify.prefs(c).edit().putString("libCounts", counts.toString()).apply(); } // na overnemen
            JSONArray kinds = new JSONArray();
            for (String[] k : KINDS) kinds.put(new JSONObject().put("kind", k[0]).put("name", k[1]).put("n", counts.optInt(k[0], -1)).put("gone", goneN.optInt(k[0], 0)).put("restore", true));
            for (String[] k : TOPS) kinds.put(new JSONObject().put("kind", k[0]).put("name", k[1]).put("n", counts.optInt(k[0], -1)).put("gone", 0).put("restore", false));
            kinds.put(new JSONObject().put("kind", "recent").put("name", "Laatst geluisterd").put("n", counts.optInt("recent", -1)).put("gone", 0).put("restore", false));
            out.put("kinds", kinds).put("relink", !Spotify.hasLibScopes(c) || Spotify.prefs(c).getBoolean("libRelink", false)).put("at", Spotify.prefs(c).getLong("libAt", 0));
        } catch (Exception ignored) { }
        return out;
    }

    private static JSONObject countFiles(Context c) throws Exception {
        JSONObject n = new JSONObject(), g = new JSONObject();
        for (String[] k : KINDS) { List<SpotifyDiff.T> l = stored(c, k[0]); if (l != null) n.put(k[0], l.size()); g.put(k[0], gone(c, k[0]).size()); }
        for (String[] k : TOPS) { List<SpotifyDiff.T> l = stored(c, k[0]); if (l != null) n.put(k[0], l.size()); }
        JSONObject r = read(recentFile(c));
        if (r != null && r.optJSONArray("items") != null) n.put("recent", r.optJSONArray("items").length());
        Spotify.prefs(c).edit().putString("libGone", g.toString()).apply();
        return n;
    }

    /** De items van één soort voor het scherm (hooguit 2000); gone: de weggehaalde. */
    static JSONObject list(Context c, String kind, boolean wantGone) throws Exception {
        List<SpotifyDiff.T> l;
        if (wantGone) l = gone(c, kind);
        else if (kind.equals("recent")) { // met het tijdstip van luisteren
            JSONObject r = read(recentFile(c));
            JSONArray src = r == null ? null : r.optJSONArray("items"), a = new JSONArray();
            for (int i = 0; src != null && i < src.length() && i < 2000; i++) a.put(src.get(i));
            return new JSONObject().put("total", src == null ? 0 : src.length()).put("items", a);
        }
        else { l = stored(c, kind); if (l == null) l = new ArrayList<>(); }
        JSONArray a = new JSONArray();
        for (int i = 0; i < l.size() && i < 2000; i++) a.put(Spotify.trackJson(l.get(i)));
        return new JSONObject().put("total", l.size()).put("items", a);
    }

    // ---------- terugzetten ----------

    /** Terug naar je Spotify-bibliotheek (PUT /me/library, 40 per keer). mode "gone" = alleen weggehaalde, "all" = alles uit de backup. */
    static JSONObject restore(Context c, String kind, String mode) throws Exception {
        boolean ok = false;
        for (String[] k : KINDS) ok |= k[0].equals(kind);
        if (!ok) throw new Exception("Dit kan niet terug naar Spotify");
        if (!Spotify.BUSY.compareAndSet(false, true)) throw new Exception("Er loopt al een backup of terugzetten");
        try {
            List<SpotifyDiff.T> l = new ArrayList<>(gone(c, kind));
            if (!"gone".equals(mode)) { List<SpotifyDiff.T> st = stored(c, kind); if (st != null) l.addAll(st); } // alles: ook wat er al uit verdween (bijv. nieuw account)
            if (l.isEmpty()) throw new Exception("Niets om terug te zetten");
            // oudste eerst, zodat "toegevoegd op" in dezelfde volgorde komt (Spotify geeft de nieuwste eerst)
            List<String> uris = new ArrayList<>();
            Set<String> seenU = new HashSet<>();
            List<SpotifyDiff.T> ordered = new ArrayList<>(l);
            java.util.Collections.sort(ordered, (x, y) -> x.added.compareTo(y.added));
            int skipped = 0;
            for (SpotifyDiff.T t : ordered) {
                if (!seenU.add(t.uri)) continue;
                if (t.uri.startsWith("spotify:") && !t.uri.startsWith("spotify:local:")) uris.add(t.uri); else skipped++; // lokale bestanden kunnen niet terug
            }
            int done = 0;
            for (int i = 0; i < uris.size(); i += 40) {
                Spotify.progress = "Terugzetten… (" + Math.min(uris.size(), i + 40) + " van " + uris.size() + ")";
                StringBuilder q = new StringBuilder();
                for (String u : uris.subList(i, Math.min(uris.size(), i + 40))) { if (q.length() > 0) q.append(','); q.append(u); }
                try { Spotify.api(c, "PUT", "/me/library?uris=" + Spotify.enc(q.toString()), new JSONObject()); }
                catch (Spotify.Scope s) { throw new Exception("Koppel Spotify opnieuw: terugzetten naar je bibliotheek mag nog niet"); }
                done += Math.min(40, uris.size() - i);
            }
            unGone(c, kind, l);
            return new JSONObject().put("kind", kind).put("name", label(kind)).put("done", done).put("skipped", skipped);
        } finally { Spotify.progress = ""; Spotify.BUSY.set(false); }
    }

    // ---------- naar de backup-map ----------

    /** Spotify/Bibliotheek/*.csv en het geheel als JSON-object (voor spotify-backup.json). */
    static JSONObject writeFolder(Context c, WaBackup.Dest dest, WaBackup.DestDir spotifyDir) throws Exception {
        JSONObject all = new JSONObject();
        WaBackup.DestDir d = null;
        List<String[]> kinds = new ArrayList<>();
        for (String[] k : KINDS) kinds.add(new String[]{k[0], k[1]});
        for (String[] k : TOPS) kinds.add(new String[]{k[0], k[1]});
        kinds.add(new String[]{"recent", "Laatst geluisterd"});
        for (String[] k : kinds) {
            List<SpotifyDiff.T> l;
            JSONArray raw = null;
            if (k[0].equals("recent")) { JSONObject r = read(recentFile(c)); raw = r == null ? null : r.optJSONArray("items"); l = raw == null ? null : fromJson(raw); }
            else l = stored(c, k[0]);
            if (l == null) continue;
            if (d == null) d = dest.dir(Spotify.DIR + "/Bibliotheek", true);
            try (Sms.Out w = Sms.open(dest, d, k[1] + ".csv", "text/csv")) { w.write(SpotifyDiff.csv(l)); w.done(); }
            JSONObject entry = new JSONObject().put("naam", k[1]).put("items", raw != null ? raw : toJson(l)); // laatst geluisterd: met tijdstip
            List<SpotifyDiff.T> g = gone(c, k[0]);
            if (!g.isEmpty()) entry.put("weg", toJson(g));
            all.put(k[0], entry);
        }
        JSONObject p = read(profileFile(c));
        if (p != null) all.put("profiel", p);
        return all;
    }

    static boolean known(String kind) {
        if ("recent".equals(kind)) return true;
        for (String[] k : KINDS) if (k[0].equals(kind)) return true;
        for (String[] k : TOPS) if (k[0].equals(kind)) return true;
        return false;
    }

    /** Uit spotify-backup.json (nieuwe telefoon): de bibliotheek terugzetten in de app (niet in Spotify). */
    static int importFrom(Context c, JSONObject lib) throws Exception {
        if (lib == null) return 0;
        int n = 0;
        for (java.util.Iterator<String> it = lib.keys(); it.hasNext(); ) {
            String kind = it.next();
            if (kind.equals("profiel")) { JSONObject p = lib.optJSONObject(kind); if (p != null) Contacts.writeText(profileFile(c), p.toString(), true); continue; }
            if (!known(kind)) continue;
            JSONObject e = lib.optJSONObject(kind);
            if (e == null) continue;
            JSONArray items = toJson(fromJson(e.optJSONArray("items")));
            File f = kind.equals("recent") ? recentFile(c) : file(c, kind);
            if (kind.equals("recent")) { // tijdstip van luisteren meenemen
                JSONArray src = e.optJSONArray("items"), out = new JSONArray();
                for (int i = 0; src != null && i < src.length(); i++) { JSONObject o = src.optJSONObject(i); if (o != null) out.put(Spotify.trackJson(Spotify.fromJson(o)).put("p", o.optString("p", o.optString("t")))); }
                items = out;
            }
            Contacts.writeText(f, new JSONObject().put("t", System.currentTimeMillis()).put("items", items).toString(), true);
            if (e.optJSONArray("weg") != null) Contacts.writeText(goneFile(c, kind), new JSONObject().put("items", toJson(fromJson(e.optJSONArray("weg")))).toString(), true);
            n++;
        }
        Spotify.prefs(c).edit().remove("libCounts").apply(); // opnieuw tellen
        return n;
    }
}
