package nl.rene.tools;

import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/** Brugfuncties voor de Spotify-backup. Zit tussen PlayerBridge en MainActivity.Bridge. */
public class SpotifyBridge extends PlayerBridge {

    SpotifyBridge(MainActivity act) { super(act); }

    private static String msg(Exception e, String fb) {
        return e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException ? "Geen internetverbinding"
                : e.getMessage() == null ? fb : e.getMessage();
    }

    @JavascriptInterface public String spotifyState() {
        try {
            android.content.SharedPreferences p = Spotify.prefs(ctx);
            boolean has = Spotify.versionsFile(ctx).isFile();
            return new JSONObject().put("linked", Spotify.linked(ctx)).put("clientId", p.getString("clientId", "")).put("user", p.getString("user", ""))
                    .put("auto", SpotifyJob.mode(ctx)).put("busy", Spotify.BUSY.get()).put("progress", Spotify.progress)
                    .put("lastAt", p.getLong("lastAt", 0)).put("lastFailAt", p.getLong("lastFailAt", 0)).put("lastMsg", p.getString("lastMsg", ""))
                    .put("own", p.getInt("lastOwn", 0)).put("followed", p.getInt("lastFollowed", 0)).put("tracks", p.getInt("lastTracks", 0))
                    .put("versions", p.getInt("versions", 0)).put("hasBackup", has).put("encrypted", Secure.on(ctx))
                    .put("folder", WaBackup.destUri(ctx) != null).toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Koppelen starten: opent de inlogpagina van Spotify in de browser. Antwoord via onSpotifyLinked({error|name}). */
    @JavascriptInterface public String spotifyLink(String clientId) {
        try {
            String url = Spotify.startLogin(ctx, clientId == null ? "" : clientId, (err, name) -> {
                try {
                    if (err == null) SpotifyJob.schedule(ctx);
                    a.js("onSpotifyLinked", (err != null ? new JSONObject().put("error", err) : new JSONObject().put("name", name)).toString());
                } catch (Exception ignored) { }
            });
            a.h.post(() -> { try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { a.js("onSpotifyLinked", "{\"error\":\"Geen browser gevonden\"}"); } });
            return "";
        } catch (Exception e) { return msg(e, "Koppelen lukt niet"); }
    }

    @JavascriptInterface public void spotifyUnlink() { Spotify.unlink(ctx); SpotifyJob.schedule(ctx); }

    @JavascriptInterface public void spotifyAuto(String mode) {
        if (!"off".equals(mode) && !"day".equals(mode) && !"week".equals(mode)) return;
        Spotify.prefs(ctx).edit().putString("auto", mode).apply();
        SpotifyJob.schedule(ctx);
    }

    /** Nu een backup maken; antwoord via onSpotifyDone({…} of {error}). */
    /** plainOk: ook leesbaar in de backup-map als versleutelen aan staat (net bevestigd). */
    @JavascriptInterface public void spotifyBackup(boolean plainOk) {
        new Thread(() -> {
            JSONObject r;
            try { r = Spotify.backup(ctx, plainOk); } catch (Exception e) { r = new JSONObject(); try { r.put("error", msg(e, "Backup mislukt")); } catch (Exception ignored) { } }
            a.js("onSpotifyDone", r.toString());
        }, "spotify-backup").start();
    }

    /** De playlists uit de laatste backup (met aantallen). */
    @JavascriptInterface public String spotifyPlaylists() {
        JSONObject l = Spotify.latest(ctx);
        JSONArray pl = l == null ? null : l.optJSONArray("pl");
        return pl == null ? "[]" : pl.toString();
    }

    /** Eigen playlists die uit Spotify verdwenen (blijven altijd in de backup). */
    @JavascriptInterface public String spotifyGone() {
        try {
            JSONObject k = Spotify.keptGone(ctx);
            JSONArray out = new JSONArray();
            for (java.util.Iterator<String> it = k.keys(); it.hasNext(); ) { JSONObject p = k.optJSONObject(it.next()); if (p != null) out.put(p); }
            return out.toString();
        } catch (Exception e) { return "[]"; }
    }

    /** Versies van één playlist (nieuwste eerst): [{t, snap, n, name, plus, min}]. */
    @JavascriptInterface public String spotifyVersions(String id) {
        try {
            JSONArray v = Spotify.versions(ctx), out = new JSONArray();
            String lastSnap = null;
            java.util.List<JSONObject> list = new java.util.ArrayList<>();
            java.util.List<SpotifyDiff.T> prev = null;
            for (int i = 0; i < v.length(); i++) {
                JSONObject ver = v.getJSONObject(i);
                JSONArray pl = ver.optJSONArray("pl");
                if (pl == null) continue;
                for (int j = 0; j < pl.length(); j++) {
                    JSONObject p = pl.getJSONObject(j);
                    if (!id.equals(p.optString("id")) || !p.optBoolean("own") || p.optString("snap").equals(lastSnap)) continue;
                    lastSnap = p.optString("snap");
                    java.util.List<SpotifyDiff.T> items = Spotify.readSnap(ctx, id, lastSnap);
                    JSONObject e = new JSONObject().put("t", ver.optLong("t")).put("snap", lastSnap).put("n", p.optInt("n")).put("name", p.optString("name"));
                    if (prev != null && items != null) { SpotifyDiff.Change ch = SpotifyDiff.diff(prev, items); e.put("plus", ch.added.size()).put("min", ch.removed.size()); }
                    if (items != null) prev = items;
                    e.put("ok", items != null);
                    list.add(e);
                }
            }
            for (int i = list.size() - 1; i >= 0; i--) out.put(list.get(i));
            if (out.length() == 0) { // uit Spotify verdwenen en al uit de oudere backups geroteerd: de bewaarde laatste versie
                JSONObject k = Spotify.keptGone(ctx).optJSONObject(id);
                if (k != null) out.put(new JSONObject().put("t", k.optLong("t")).put("snap", k.optString("snap")).put("n", k.optInt("n")).put("name", k.optString("name")).put("ok", Spotify.readSnap(ctx, id, k.optString("snap")) != null));
            }
            return out.toString();
        } catch (Exception e) { return "[]"; }
    }

    /** De nummers van één versie (hooguit 2000 voor het scherm). */
    @JavascriptInterface public String spotifyTracks(String id, String snap) {
        try {
            java.util.List<SpotifyDiff.T> l = Spotify.readSnap(ctx, id, snap);
            JSONArray a = new JSONArray();
            if (l != null) for (int i = 0; i < l.size() && i < 2000; i++) a.put(Spotify.trackJson(l.get(i)));
            return new JSONObject().put("total", l == null ? 0 : l.size()).put("items", a).toString();
        } catch (Exception e) { return "{\"total\":0,\"items\":[]}"; }
    }

    /** Terugzetten; mode "new" of "replace". Antwoord via onSpotifyRestored({…} of {error}). */
    @JavascriptInterface public void spotifyRestore(String id, String snap, String mode) {
        new Thread(() -> {
            JSONObject r;
            try { r = Spotify.restore(ctx, id, snap, "replace".equals(mode) ? "replace" : "new"); }
            catch (Exception e) { r = new JSONObject(); try { r.put("error", msg(e, "Terugzetten lukt niet")); } catch (Exception ignored) { } }
            a.js("onSpotifyRestored", r.toString());
        }, "spotify-restore").start();
    }

    /** Het logboek van wijzigingen (nieuwste boven). */
    @JavascriptInterface public String spotifyLog() {
        try { java.io.File f = Spotify.logFile(ctx); return f.isFile() ? Contacts.readText(f, false) : ""; } catch (Exception e) { return ""; }
    }

    /** Laatste backup uit de backup-map overnemen (nieuwe telefoon). Antwoord via onSpotifyImported. */
    @JavascriptInterface public void spotifyImport() {
        new Thread(() -> {
            JSONObject r = new JSONObject();
            try { r.put("count", Spotify.importFolder(ctx)); } catch (Exception e) { try { r.put("error", msg(e, "Overnemen lukt niet")); } catch (Exception ignored) { } }
            a.js("onSpotifyImported", r.toString());
        }, "spotify-import").start();
    }

    /** Het adres om bij de developer app in te vullen, naar het klembord. */
    @JavascriptInterface public void spotifyCopyRedirect() {
        a.h.post(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Redirect URI", Spotify.REDIRECT));
            } catch (Exception ignored) { }
        });
    }
}
