package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spotify-playlists backuppen en bijhouden, via de Web API met een eigen "developer app" van de gebruiker
 * (eigen Client ID; inloggen met PKCE, terugkomen via http://127.0.0.1:poort/callback op de telefoon zelf).
 *
 * Sinds februari 2026 geeft Spotify alleen de inhoud van playlists die je zelf maakte of waaraan je meewerkt;
 * van gevolgde playlists bewaren we naam, eigenaar en link. Versies: per playlist een bestand per snapshot_id
 * (alleen als hij veranderde), plus een lijst van backups (versies.json) en een leesbaar logboek.
 * In de backup-map: Spotify/spotify-backup.json (alles, terug te lezen), per playlist een CSV, wijzigingen.txt.
 */
final class Spotify {
    private Spotify() { }

    static final String API = "https://api.spotify.com/v1", ACCOUNTS = "https://accounts.spotify.com";
    static final String SCOPES = "playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public";
    static final String DIR = "Spotify";
    /** Vaste poort: zo klopt het adres altijd precies met wat je bij de developer app invult. */
    static final int PORT = 43819;
    static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("spotify", Context.MODE_PRIVATE); }
    static File dir(Context c) { File d = new File(c.getFilesDir(), "spotify"); d.mkdirs(); return d; }

    static boolean linked(Context c) { return prefs(c).contains("refresh"); }

    static boolean validClientId(String id) { return id != null && id.trim().matches("[0-9a-fA-F]{32}"); }

    // ---------- tokens (de refresh-token versleuteld met een sleutel uit de sleutelopslag van de telefoon) ----------

    private static String seal(String plain) throws Exception {
        javax.crypto.Cipher ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(javax.crypto.Cipher.ENCRYPT_MODE, Secure.deviceKey());
        return Secure.b64(ci.getIV()) + ":" + Secure.b64(ci.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String open(String sealed) throws Exception {
        String[] p = sealed.split(":");
        javax.crypto.Cipher ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(javax.crypto.Cipher.DECRYPT_MODE, Secure.deviceKey(), new javax.crypto.spec.GCMParameterSpec(128, Secure.unb64(p[0])));
        return new String(ci.doFinal(Secure.unb64(p[1])), StandardCharsets.UTF_8);
    }

    private static volatile String access;
    private static volatile long accessUntil;

    private static void saveTokens(Context c, JSONObject t) throws Exception {
        access = t.getString("access_token");
        accessUntil = System.currentTimeMillis() + Math.max(60, t.optLong("expires_in", 3600) - 60) * 1000L;
        String r = t.optString("refresh_token");
        if (!r.isEmpty()) prefs(c).edit().putString("refresh", seal(r)).apply(); // geen nieuwe? dan de oude blijft
    }

    private static final Object TOKEN = new Object();

    /** Geldige toegangssleutel (vernieuwt hem als dat nodig is). Eigen slot: het scherm hoeft er nooit op te wachten. */
    static String token(Context c) throws Exception {
        synchronized (TOKEN) { return tokenLocked(c); }
    }

    private static String tokenLocked(Context c) throws Exception {
        if (access != null && System.currentTimeMillis() < accessUntil) return access;
        String sealed = prefs(c).getString("refresh", null);
        if (sealed == null) throw new Exception("Niet gekoppeld met Spotify");
        String refresh;
        try { refresh = open(sealed); }
        catch (Exception e) { prefs(c).edit().remove("refresh").apply(); throw new Exception("Koppel Spotify opnieuw (de sleutel op deze telefoon is veranderd)"); }
        JSONObject t = form(ACCOUNTS + "/api/token", "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(prefs(c).getString("clientId", "")));
        if (t.has("error")) {
            if ("invalid_grant".equals(t.optString("error"))) { prefs(c).edit().remove("refresh").apply(); throw new Exception("Spotify vraagt om opnieuw koppelen"); }
            throw new Exception("Spotify: " + t.optString("error_description", t.optString("error")));
        }
        saveTokens(c, t);
        return access;
    }

    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    private static JSONObject form(String url, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(15000); h.setReadTimeout(30000);
            h.setRequestMethod("POST"); h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = h.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            String s = in == null ? "{}" : new String(SelfTest.readAll(new PodcastFeed.Limited(in, 1_000_000)), StandardCharsets.UTF_8);
            try { return new JSONObject(s); } catch (Exception e) { return new JSONObject().put("error", "HTTP " + code); }
        } finally { h.disconnect(); }
    }

    /** Web API-aanroep (met vernieuwen bij 401 en wachten bij 429). */
    static JSONObject api(Context c, String method, String path, JSONObject body) throws Exception {
        boolean refreshed = false;
        for (int attempt = 0; attempt < 6; attempt++) {
            // "next"-adressen komen van Spotify zelf; de sleutel gaat nooit naar een ander adres
            if (path.startsWith("https://") && !path.startsWith(API + "/")) throw new Exception("Onverwacht adres van Spotify");
            HttpURLConnection h = (HttpURLConnection) new URL(path.startsWith("https://") ? path : API + path).openConnection();
            try {
                h.setConnectTimeout(15000); h.setReadTimeout(30000);
                h.setRequestMethod(method);
                h.setRequestProperty("Authorization", "Bearer " + token(c));
                if (body != null) {
                    h.setDoOutput(true);
                    h.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                }
                int code = h.getResponseCode();
                if (code == 401 && !refreshed) { refreshed = true; synchronized (TOKEN) { accessUntil = 0; } continue; }
                // Opnieuw proberen: bij 429 altijd; bij een serverfout alleen als dat niets dubbel kan doen (niet bij POST)
                if (code == 429 || ((code == 502 || code == 503) && !"POST".equals(method))) {
                    long wait = 2;
                    try { wait = Long.parseLong(h.getHeaderField("Retry-After")); } catch (Exception ignored) { }
                    if (wait > 60) throw new Exception("Spotify vraagt even te wachten (" + wait + " s); probeer het later opnieuw");
                    Thread.sleep(Math.max(1, wait) * 1000L + attempt * 500L);
                    continue;
                }
                InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
                String s = in == null ? "" : new String(SelfTest.readAll(new PodcastFeed.Limited(in, 20_000_000)), StandardCharsets.UTF_8);
                if (code == 403 && (path.startsWith("/me") || path.startsWith(API + "/me"))) throw new Exception("Spotify weigert de toegang (403). Staat je account bij 'User Management' van je developer app, en heb je Premium?");
                if (code == 403 || code == 404) throw new Gone(code, "Spotify: " + (code == 404 ? "niet gevonden" : "geen toegang"));
                if (code >= 400) {
                    String msg = "";
                    try { msg = new JSONObject(s).optJSONObject("error").optString("message"); } catch (Exception ignored) { }
                    throw new Exception("Spotify: " + (msg.isEmpty() ? "fout " + code : msg));
                }
                return s.trim().isEmpty() ? new JSONObject() : new JSONObject(s);
            } finally { h.disconnect(); }
        }
        throw new Exception("Spotify reageert nu niet; probeer het later opnieuw");
    }

    /** 403/404 op één playlist: die overslaan, niet de hele backup laten mislukken. */
    static final class Gone extends Exception { final int code; Gone(int code, String m) { super(m); this.code = code; } }

    static volatile boolean cancel;

    /** Inloggen stopt zonder browserverbinding (verlopen of opnieuw gestart). */
    static final class LoginEnd extends Exception { LoginEnd(String m) { super(m); } }

    // ---------- koppelen ----------

    interface Done { void done(String error, String name); }

    static volatile ServerSocket waiting;

    /**
     * Inloggen starten: geeft de adres om in de browser te openen; luistert op 127.0.0.1 tot Spotify terugstuurt
     * (hooguit 5 minuten). done komt op een achtergrondthread.
     */
    static String startLogin(Context c, String clientId, Done done) throws Exception {
        clientId = clientId.trim();
        if (!validClientId(clientId)) throw new Exception("Dat lijkt geen Client ID (32 tekens, cijfers en a–f)");
        ServerSocket old = waiting;
        if (old != null) try { old.close(); } catch (Exception ignored) { }
        final ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        try { ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4); } // alleen de telefoon zelf
        catch (Exception e) { try { ss.close(); } catch (Exception ignored) { } throw new Exception("Poort " + PORT + " is bezet; probeer het zo opnieuw"); }
        ss.setSoTimeout(300_000);
        waiting = ss;
        final String redirect = REDIRECT;
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        final String verifier = randomString(rnd, 64), state = randomString(rnd, 24);
        byte[] dig = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = android.util.Base64.encodeToString(dig, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        final String cid = clientId;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String err = null, name = null;
            Socket browser = null;
            try {
                Object[] got;
                try { got = awaitCode(ss, state); }
                catch (java.net.SocketTimeoutException e) { throw new LoginEnd("Geen antwoord van Spotify binnen 5 minuten; probeer opnieuw"); }
                catch (java.net.SocketException e) { throw new LoginEnd(waiting == ss ? "Koppelen afgebroken" : null); }
                String code = (String) got[0];
                browser = (Socket) got[1];
                JSONObject t = form(ACCOUNTS + "/api/token", "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirect)
                        + "&client_id=" + enc(cid) + "&code_verifier=" + enc(verifier));
                if (t.has("error")) throw new Exception("Spotify: " + t.optString("error_description", t.optString("error")));
                prefs(app).edit().putString("clientId", cid).apply();
                saveTokens(app, t);
                JSONObject me = api(app, "GET", "/me", null);
                name = me.optString("display_name", me.optString("id"));
                prefs(app).edit().putString("userId", me.optString("id")).putString("user", name).apply();
                reply(browser, 200, "✓ Spotify is gekoppeld met Rene's Tools" + (name.isEmpty() ? "" : " (" + esc(name) + ")") + ". Je kunt dit tabblad sluiten en terug naar de app.");
            } catch (Gone e) { err = "Spotify weigert de toegang (403). Staat je account bij 'User Management' van je developer app, en heb je Premium?";
                if (browser != null) reply(browser, 200, "Niet gekoppeld: " + esc(err));
            } catch (LoginEnd e) { err = e.getMessage(); // null = opnieuw gestart: stil
            } catch (Exception e) {
                err = e.getMessage() == null ? "Koppelen lukt niet" : e.getMessage();
                if (browser != null) reply(browser, 200, "Niet gekoppeld: " + esc(err) + ". Probeer het opnieuw vanuit de app.");
            }
            finally {
                if (browser != null) try { browser.close(); } catch (Exception ignored) { }
                try { ss.close(); } catch (Exception ignored) { } if (waiting == ss) waiting = null;
            }
            if (err != null || name != null) done.done(err, name);
        }, "spotify-login").start();
        return ACCOUNTS + "/authorize?response_type=code&client_id=" + enc(clientId) + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(redirect) + "&state=" + enc(state) + "&code_challenge_method=S256&code_challenge=" + enc(challenge);
    }

    private static String randomString(java.security.SecureRandom r, int n) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(a.charAt(r.nextInt(a.length())));
        return b.toString();
    }

    /** Wacht op de browser die terugkomt op /callback; geeft {code, socket} (het antwoord volgt na het inwisselen). */
    private static Object[] awaitCode(ServerSocket ss, String state) throws Exception {
        while (true) {
            Socket s = ss.accept();
            boolean keep = false;
            try {
                s.setSoTimeout(5_000);
                String target = readRequest(s.getInputStream());
                if (target == null || !target.startsWith("/callback")) { reply(s, 404, "Niet gevonden"); continue; }
                Uri u = Uri.parse("http://127.0.0.1" + target);
                if (!state.equals(u.getQueryParameter("state"))) { reply(s, 400, "Deze aanvraag hoort niet bij Rene's Tools. Probeer opnieuw vanuit de app."); continue; }
                String err = u.getQueryParameter("error");
                if (err != null) {
                    reply(s, 200, "Niet gekoppeld (" + esc(err) + "). Je kunt dit tabblad sluiten en terug naar Rene's Tools.");
                    throw new Exception("access_denied".equals(err) ? "Je hebt het koppelen geweigerd" : "Spotify: " + err);
                }
                String code = u.getQueryParameter("code");
                if (code == null) { reply(s, 400, "Geen code ontvangen"); continue; }
                keep = true;
                return new Object[]{code, s};
            } catch (java.io.IOException e) {
                // Een verbinding die niets stuurt (bijv. het browser-voorwerk): negeren en verder wachten
            } finally { if (!keep) try { s.close(); } catch (Exception ignored) { } }
        }
    }

    /** Leest de verzoekregel en de koppen (tot de lege regel, hooguit 16 kB); geeft het pad of null. */
    private static String readRequest(InputStream in) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        String first = null;
        int ch, total = 0;
        while ((ch = in.read()) != -1 && total++ < 16_384) {
            if (ch == '\n') {
                String l = line.toString().trim();
                if (first == null) first = l; else if (l.isEmpty()) break;
                line.setLength(0);
            } else line.append((char) ch);
        }
        if (first == null) return null;
        String[] parts = first.split(" ");
        return parts.length >= 2 && "GET".equals(parts[0]) ? parts[1] : null;
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static void reply(Socket s, int code, String text) {
        try {
            String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>Rene's Tools</title>"
                    + "<body style='font-family:sans-serif;padding:28px;font-size:18px;line-height:1.5'><h2>Rene's Tools</h2><p>" + text + "</p></body>";
            byte[] b = html.getBytes(StandardCharsets.UTF_8);
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                    + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.write(b);
            o.flush();
        } catch (Exception ignored) { }
    }

    static void unlink(Context c) {
        ServerSocket ss = waiting; if (ss != null) try { ss.close(); } catch (Exception ignored) { }
        access = null; accessUntil = 0;
        prefs(c).edit().remove("refresh").remove("user").remove("userId").apply(); // backups blijven staan
    }

    // ---------- backup ----------

    static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean();
    static volatile String progress = "";

    static File versionsFile(Context c) { return new File(dir(c), "versies.json"); }
    static File logFile(Context c) { return new File(dir(c), "wijzigingen.txt"); }
    static File snapFile(Context c, String plId, String snap) {
        File d = new File(new File(dir(c), "pl"), Restore.safeId(plId, "x"));
        d.mkdirs();
        return new File(d, Podcasts.hash(snap) + ".json");
    }

    private static String vCacheKey;
    private static String vCache;

    /** Alle backups (oudste eerst). Onthouden zolang het bestand niet verandert (het scherm vraagt er vaak om). */
    static synchronized JSONArray versions(Context c) {
        try {
            File f = versionsFile(c);
            if (!f.isFile()) return new JSONArray();
            String key = f.lastModified() + ":" + f.length();
            if (!key.equals(vCacheKey)) { vCache = Contacts.readText(f, false); vCacheKey = key; }
            return new JSONArray(vCache);
        } catch (Exception e) { return new JSONArray(); }
    }

    static JSONObject latest(Context c) { JSONArray v = versions(c); return v.length() == 0 ? null : v.optJSONObject(v.length() - 1); }

    static JSONObject trackJson(SpotifyDiff.T t) throws Exception {
        JSONObject o = new JSONObject().put("u", t.uri).put("n", t.name).put("a", t.artists).put("al", t.album).put("d", t.dur).put("t", t.added);
        if (!t.isrc.isEmpty()) o.put("i", t.isrc);
        if (t.local) o.put("l", true);
        return o;
    }

    static SpotifyDiff.T fromJson(JSONObject o) {
        SpotifyDiff.T t = new SpotifyDiff.T(o.optString("u"), o.optString("n"), o.optString("a"));
        t.album = o.optString("al"); t.dur = o.optLong("d"); t.added = o.optString("t"); t.isrc = o.optString("i"); t.local = o.optBoolean("l");
        return t;
    }

    static List<SpotifyDiff.T> readSnap(Context c, String plId, String snap) {
        List<SpotifyDiff.T> l = new ArrayList<>();
        try {
            File f = snapFile(c, plId, snap);
            if (!f.isFile()) return null;
            JSONArray a = new JSONArray(Contacts.readText(f, true));
            for (int i = 0; i < a.length(); i++) l.add(fromJson(a.getJSONObject(i)));
            return l;
        } catch (Exception e) { return null; }
    }

    /** Alle nummers van een eigen playlist ophalen. */
    static List<SpotifyDiff.T> fetchItems(Context c, String plId) throws Exception {
        List<SpotifyDiff.T> l = new ArrayList<>();
        String next = "/playlists/" + enc(plId) + "/items?limit=100&additional_types=track,episode";
        int guard = 0;
        while (next != null && !next.isEmpty() && guard++ < 200) { // hooguit 20.000
            JSONObject page = api(c, "GET", next, null);
            JSONArray items = page.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject e = items.optJSONObject(i);
                if (e == null) continue;
                JSONObject it = e.optJSONObject("item");
                if (it == null) it = e.optJSONObject("track"); // oude vorm
                if (it == null) continue; // niet meer beschikbaar
                SpotifyDiff.T t = new SpotifyDiff.T();
                t.uri = it.optString("uri"); t.name = it.optString("name");
                JSONArray ar = it.optJSONArray("artists");
                StringBuilder an = new StringBuilder();
                if (ar != null) for (int k = 0; k < ar.length(); k++) { JSONObject x = ar.optJSONObject(k); if (x == null) continue; if (an.length() > 0) an.append(", "); an.append(x.optString("name")); }
                t.artists = an.toString();
                JSONObject al = it.optJSONObject("album"), show = it.optJSONObject("show");
                t.album = al != null ? al.optString("name") : show != null ? show.optString("name") : "";
                if (t.artists.isEmpty() && show != null) t.artists = show.optString("name");
                t.dur = it.optLong("duration_ms");
                t.added = e.optString("added_at");
                JSONObject ids = it.optJSONObject("external_ids");
                if (ids != null) t.isrc = ids.optString("isrc");
                t.local = e.optBoolean("is_local") || it.optBoolean("is_local");
                l.add(t);
            }
            String n = page.optString("next", "");
            next = "null".equals(n) ? null : n;
            progress = "Nummers ophalen… (" + l.size() + ")";
        }
        return l;
    }

    /**
     * Een backup maken. Geeft {playlists, own, followed, tracks, changed, lines:[…]} of gooit een fout.
     * Alleen playlists die veranderden (snapshot_id) worden opnieuw opgehaald en opnieuw bewaard.
     */
    static JSONObject backup(Context c) throws Exception { return backup(c, false); }

    /** plainOk: ook in de backup-map als versleutelen aan staat (de gebruiker heeft dat net zelf bevestigd). */
    static JSONObject backup(Context c, boolean plainOk) throws Exception {
        if (!BUSY.compareAndSet(false, true)) throw new Exception("Er loopt al een backup");
        cancel = false;
        try {
            progress = "Playlists ophalen…";
            String me = prefs(c).getString("userId", "");
            if (me.isEmpty()) { JSONObject m = api(c, "GET", "/me", null); me = m.optString("id"); prefs(c).edit().putString("userId", me).putString("user", m.optString("display_name", me)).apply(); }
            // Alle playlists (eigen en gevolgd)
            List<JSONObject> pls = new ArrayList<>();
            String next = "/me/playlists?limit=50";
            int guard = 0;
            while (next != null && !next.isEmpty() && guard++ < 100) {
                JSONObject page = api(c, "GET", next, null);
                JSONArray items = page.optJSONArray("items");
                if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject p = items.optJSONObject(i); if (p != null && !p.optString("id").isEmpty()) pls.add(p); }
                String n = page.optString("next", "");
                next = "null".equals(n) ? null : n;
            }
            JSONObject prev = latest(c);
            Map<String, JSONObject> before = new HashMap<>();
            if (prev != null) { JSONArray a = prev.optJSONArray("pl"); if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject p = a.optJSONObject(i); if (p != null) before.put(p.optString("id"), p); } }
            JSONArray now = new JSONArray();
            List<String> lines = new ArrayList<>();
            int own = 0, followed = 0, tracks = 0, k = 0;
            boolean changed = prev == null;
            Map<String, List<SpotifyDiff.T>> fresh = new HashMap<>();
            for (JSONObject p : pls) {
                k++;
                String id = p.optString("id"), name = p.optString("name", "Playlist"), snap = p.optString("snapshot_id");
                JSONObject owner = p.optJSONObject("owner");
                String ownerId = owner == null ? "" : owner.optString("id");
                boolean mine = me.equals(ownerId) || p.optBoolean("collaborative");
                JSONObject e = new JSONObject().put("id", id).put("name", name).put("snap", snap).put("own", mine)
                        .put("owner", owner == null ? "" : owner.optString("display_name", ownerId)).put("coll", p.optBoolean("collaborative")).put("pub", p.optBoolean("public"))
                        .put("desc", PodcastFeed.cut(p.optString("description"), 500));
                JSONObject ext = p.optJSONObject("external_urls");
                if (ext != null) e.put("url", ext.optString("spotify"));
                JSONArray imgs = p.optJSONArray("images");
                if (imgs != null && imgs.length() > 0 && imgs.optJSONObject(0) != null) e.put("img", imgs.optJSONObject(0).optString("url"));
                JSONObject before1 = before.remove(id);
                if (cancel) throw new Exception("Gestopt door Android; de volgende keer verder");
                List<SpotifyDiff.T> items = null;
                if (mine) {
                    progress = "Playlist " + k + " van " + pls.size() + ": " + name;
                    items = readSnap(c, id, snap); // deze versie al bewaard (ook als hij eerder niet de laatste was)
                    if (items == null) {
                        try { items = fetchItems(c, id); }
                        catch (Gone g) {
                            if (!me.equals(ownerId)) { mine = false; e.put("own", false); } // samenwerk-playlist van een ander waar je niet (meer) aan meewerkt: alleen naam en link
                            else if (g.code == 404) { // eigen playlist net verwijderd: telt hieronder als verwijderd (de vorige versie blijft bewaard)
                                if (before1 != null) before.put(id, before1);
                                continue;
                            } else throw new Exception("Spotify geeft geen toegang tot je playlist \"" + name + "\" (403). Heb je nog Premium, en staat je account bij User Management?");
                        }
                    }
                }
                if (mine) {
                    own++;
                    boolean wasStored = before1 != null && snap.equals(before1.optString("snap"));
                    if (!wasStored) {
                        if (!snapFile(c, id, snap).isFile()) {
                            JSONArray arr = new JSONArray();
                            for (SpotifyDiff.T t : items) arr.put(trackJson(t));
                            Contacts.writeText(snapFile(c, id, snap), arr.toString(), true);
                        }
                        List<SpotifyDiff.T> old = before1 == null ? null : readSnap(c, id, before1.optString("snap"));
                        if (before1 == null) { lines.add("Nieuw: " + name + " (" + items.size() + ")"); changed = true; }
                        else {
                            SpotifyDiff.Change ch = SpotifyDiff.diff(old == null ? new ArrayList<SpotifyDiff.T>() : old, items);
                            if (!ch.empty()) { lines.add(name + ": " + SpotifyDiff.summary(ch, 3)); changed = true; }
                            else if (!before1.optString("snap").equals(snap)) changed = true; // alleen volgorde of omschrijving
                        }
                    }
                    if (before1 != null && !before1.optString("name").equals(name)) { lines.add("Andere naam: " + before1.optString("name") + " → " + name); changed = true; }
                    e.put("n", items.size());
                    tracks += items.size();
                    fresh.put(id, items);
                } else {
                    followed++;
                    JSONObject tot = p.optJSONObject("items"); if (tot == null) tot = p.optJSONObject("tracks");
                    if (tot != null) e.put("n", tot.optInt("total", 0));
                    if (before1 == null) { lines.add("Gevolgd: " + name + (e.optString("owner").isEmpty() ? "" : " (van " + e.optString("owner") + ")")); changed = true; }
                }
                now.put(e);
            }
            for (JSONObject gone : before.values()) {
                lines.add((gone.optBoolean("own") ? "Verwijderd uit Spotify: " : "Niet meer gevolgd: ") + gone.optString("name") + (gone.optBoolean("own") ? " (staat nog in de backup)" : ""));
                if (gone.optBoolean("own")) keepGone(c, gone);
                changed = true;
            }
            // Weer terug in Spotify (zelfde id): niet meer bij "weg"
            JSONObject kept = keptGone(c);
            boolean keptChanged = false;
            for (int i = 0; i < now.length(); i++) if (kept.has(now.getJSONObject(i).optString("id"))) { kept.remove(now.getJSONObject(i).optString("id")); keptChanged = true; }
            if (keptChanged) saveKept(c, kept);
            long t = System.currentTimeMillis();
            if (changed) {
                synchronized (Spotify.class) {
                    JSONArray v = versions(c);
                    v.put(new JSONObject().put("t", t).put("pl", now).put("lines", new JSONArray(lines)));
                    while (v.length() > 150) v.remove(0);
                    Contacts.writeText(versionsFile(c), v.toString(), false);
                    pruneSnaps(c, v);
                }
                appendLog(c, t, lines);
            }
            prefs(c).edit().putLong("lastAt", t).putString("lastMsg", "").putInt("versions", versions(c).length()).putInt("lastOwn", own).putInt("lastFollowed", followed).putInt("lastTracks", tracks).apply();
            // Naar de backup-map (als die gekozen is)
            String folder = "";
            try {
                if (WaBackup.destUri(c) != null) {
                    if (Secure.on(c) && !plainOk) folder = "encrypted"; // versleutelen staat aan: geen leesbare bestanden zonder te vragen
                    else { progress = "In de backup-map zetten…"; writeFolder(c, now, fresh); folder = "ok"; }
                }
            }
            catch (Exception ex) { folder = ex.getMessage() == null ? "Backup-map: schrijven lukt niet" : "Backup-map: " + ex.getMessage(); }
            return new JSONObject().put("own", own).put("followed", followed).put("tracks", tracks).put("changed", changed)
                    .put("lines", new JSONArray(lines)).put("folder", folder).put("t", t);
        } catch (Exception e) {
            if (!cancel) prefs(c).edit().putLong("lastFailAt", System.currentTimeMillis()).putString("lastMsg", e.getMessage() == null ? "Backup mislukt" : e.getMessage()).apply();
            throw e;
        } finally { progress = ""; BUSY.set(false); }
    }

    /** Snapshot-bestanden die in geen enkele versie meer voorkomen, weggooien. */
    private static void pruneSnaps(Context c, JSONArray v) {
        Set<String> keep = new HashSet<>();
        JSONObject kept = keptGone(c);
        for (java.util.Iterator<String> it = kept.keys(); it.hasNext(); ) { JSONObject p = kept.optJSONObject(it.next()); if (p != null) keep.add(Restore.safeId(p.optString("id"), "x") + "/" + Podcasts.hash(p.optString("snap")) + ".json"); }
        for (int i = 0; i < v.length(); i++) {
            JSONArray pl = v.optJSONObject(i) == null ? null : v.optJSONObject(i).optJSONArray("pl");
            if (pl != null) for (int j = 0; j < pl.length(); j++) { JSONObject p = pl.optJSONObject(j); if (p != null) keep.add(Restore.safeId(p.optString("id"), "x") + "/" + Podcasts.hash(p.optString("snap")) + ".json"); }
        }
        File[] ds = new File(dir(c), "pl").listFiles();
        if (ds != null) for (File d : ds) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (!keep.contains(d.getName() + "/" + f.getName())) f.delete();
            String[] left = d.list();
            if (left != null && left.length == 0) d.delete();
        }
    }

    private static synchronized void appendLog(Context c, long t, List<String> lines) {
        try {
            File f = logFile(c);
            String old = f.isFile() ? Contacts.readText(f, false) : "";
            StringBuilder b = new StringBuilder();
            String when = new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("nl", "NL")).format(new Date(t));
            b.append(when).append(lines.isEmpty() ? " · (alleen volgorde of omschrijving)\n" : "\n");
            for (String l : lines) b.append("  ").append(l).append('\n');
            String all = b + old;
            if (all.length() > 400_000) all = all.substring(0, 400_000);
            Contacts.writeText(f, all, false);
        } catch (Exception ignored) { }
    }

    // ---------- playlists die uit Spotify verdwenen (blijven altijd bewaard, buiten de versie-rotatie) ----------

    static File keptFile(Context c) { return new File(dir(c), "weg.json"); }

    static synchronized JSONObject keptGone(Context c) {
        try { File f = keptFile(c); return f.isFile() ? new JSONObject(Contacts.readText(f, false)) : new JSONObject(); } catch (Exception e) { return new JSONObject(); }
    }

    private static synchronized void saveKept(Context c, JSONObject o) { try { Contacts.writeText(keptFile(c), o.toString(), false); } catch (Exception ignored) { } }

    private static synchronized void keepGone(Context c, JSONObject p) {
        try { JSONObject k = keptGone(c); k.put(p.optString("id"), new JSONObject(p.toString()).put("t", System.currentTimeMillis())); saveKept(c, k); } catch (Exception ignored) { }
    }

    /** Spotify/spotify-backup.json, per eigen playlist een CSV en wijzigingen.txt in de backup-map. */
    static void writeFolder(Context c, JSONArray now, Map<String, List<SpotifyDiff.T>> items) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) return;
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir d = dest.dir(DIR, true);
        JSONArray all = new JSONArray();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < now.length(); i++) {
            JSONObject p = new JSONObject(now.getJSONObject(i).toString());
            List<SpotifyDiff.T> l = items.get(p.optString("id"));
            if (l != null) {
                JSONArray arr = new JSONArray();
                for (SpotifyDiff.T t : l) arr.put(trackJson(t));
                p.put("items", arr);
                String base = WaBackup.safeName(p.optString("name", "Playlist")).trim();
                if (base.isEmpty()) base = "Playlist";
                if (base.length() > 80) base = base.substring(0, 80);
                String file = base + ".csv";
                for (int n = 2; used.contains(file.toLowerCase(Locale.ROOT)); n++) file = base + " (" + n + ").csv";
                used.add(file.toLowerCase(Locale.ROOT));
                try (Sms.Out w = Sms.open(dest, d, file, "text/csv")) { w.write(SpotifyDiff.csv(l)); w.done(); }
            }
            all.put(p);
        }
        // Verdwenen eigen playlists gaan ook mee (anders zijn ze op een nieuwe telefoon weg)
        JSONObject kept = keptGone(c);
        for (java.util.Iterator<String> it = kept.keys(); it.hasNext(); ) {
            JSONObject p = kept.optJSONObject(it.next());
            if (p == null) continue;
            List<SpotifyDiff.T> l = readSnap(c, p.optString("id"), p.optString("snap"));
            if (l == null) continue;
            JSONArray arr = new JSONArray();
            for (SpotifyDiff.T t : l) arr.put(trackJson(t));
            all.put(new JSONObject(p.toString()).put("gone", true).put("items", arr));
        }
        JSONObject root = new JSONObject().put("app", "Rene's Tools").put("soort", "spotify-backup").put("versie", 1)
                .put("gemaakt", System.currentTimeMillis()).put("gebruiker", prefs(c).getString("user", "")).put("playlists", all);
        try (Sms.Out w = Sms.open(dest, d, "spotify-backup.json", "application/json")) { w.write(root.toString()); w.done(); }
        File log = logFile(c);
        if (log.isFile()) try (Sms.Out w = Sms.open(dest, d, "wijzigingen.txt", "text/plain")) { w.write(Contacts.readText(log, false).replace("\n", "\r\n")); w.done(); }
    }

    /**
     * Na een nieuwe telefoon: de laatste backup uit de backup-map overnemen als versie (alleen als hier nog niets is).
     * Geeft het aantal playlists.
     */
    static int importFolder(Context c) throws Exception {
        if (!BUSY.compareAndSet(false, true)) throw new Exception("Er loopt al een backup");
        try { return importLocked(c); } finally { BUSY.set(false); }
    }

    private static int importLocked(Context c) throws Exception {
        if (versions(c).length() > 0) throw new Exception("Er staan hier al backups; overnemen is alleen voor een nieuwe telefoon");
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map (Instellingen)");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir d = dest.dir(DIR, false);
        WaBackup.Child ch = d == null ? null : d.kids.get("spotify-backup.json");
        if (ch == null || ch.dir) throw new Exception("Geen Spotify-backup gevonden in de backup-map");
        if (ch.size > 50_000_000) throw new Exception("Het backupbestand is te groot");
        String s;
        try (InputStream in = c.getContentResolver().openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId))) {
            if (in == null) throw new Exception("Backupbestand niet te lezen");
            s = new String(SelfTest.readAll(new PodcastFeed.Limited(in, 50_000_000)), StandardCharsets.UTF_8);
        }
        JSONObject root = new JSONObject(s);
        if (!"spotify-backup".equals(root.optString("soort"))) throw new Exception("Dit is geen Spotify-backup van Rene's Tools");
        JSONArray pls = root.optJSONArray("playlists");
        if (pls == null) throw new Exception("Lege backup");
        JSONArray now = new JSONArray();
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.getJSONObject(i);
            String id = p.optString("id"), snap = p.optString("snap");
            if (id.isEmpty() || !id.matches("[A-Za-z0-9]{1,64}")) continue;
            if (snap.isEmpty()) snap = "import-" + root.optLong("gemaakt");
            JSONArray items = p.optJSONArray("items");
            if (items != null) {
                JSONArray clean = new JSONArray();
                for (int k = 0; k < items.length(); k++) { JSONObject t = items.optJSONObject(k); if (t != null) clean.put(trackJson(fromJson(t))); }
                Contacts.writeText(snapFile(c, id, snap), clean.toString(), true);
                p.put("n", clean.length());
            }
            p.remove("items");
            p.put("snap", snap);
            if (p.optBoolean("gone")) { p.remove("gone"); keepGone(c, p); continue; } // was al uit Spotify verdwenen
            now.put(p);
        }
        synchronized (Spotify.class) {
            JSONArray v = versions(c);
            long t = root.optLong("gemaakt", System.currentTimeMillis());
            List<String> lines = new ArrayList<>();
            lines.add("Overgenomen uit de backup-map");
            v.put(new JSONObject().put("t", t).put("pl", now).put("lines", new JSONArray(lines)).put("import", true));
            Contacts.writeText(versionsFile(c), v.toString(), false);
            appendLog(c, System.currentTimeMillis(), lines);
        }
        return now.length();
    }

    // ---------- terugzetten ----------

    /**
     * Een versie van een playlist terug naar Spotify. mode "new" = als nieuwe (privé) playlist; "replace" = de
     * bestaande eigen playlist krijgt weer precies deze inhoud. Geeft {id, added, skipped, name}.
     */
    static JSONObject restore(Context c, String plId, String snap, String mode) throws Exception {
        if (!BUSY.compareAndSet(false, true)) throw new Exception("Er loopt al een backup of terugzetten");
        try {
            JSONObject meta = null;
            JSONArray v = versions(c);
            for (int i = v.length() - 1; i >= 0 && meta == null; i--) {
                JSONArray pl = v.optJSONObject(i) == null ? null : v.optJSONObject(i).optJSONArray("pl");
                if (pl != null) for (int j = 0; j < pl.length(); j++) { JSONObject p = pl.optJSONObject(j); if (p != null && plId.equals(p.optString("id")) && snap.equals(p.optString("snap"))) { meta = p; break; } }
            }
            if (meta == null) { JSONObject k = keptGone(c).optJSONObject(plId); if (k != null && snap.equals(k.optString("snap"))) meta = k; }
            if (meta == null) throw new Exception("Deze versie staat niet (meer) in de backup");
            List<SpotifyDiff.T> items = readSnap(c, plId, snap);
            if (items == null) throw new Exception("De inhoud van deze versie is niet meer te lezen");
            List<String> uris = new ArrayList<>();
            int skipped = 0;
            for (SpotifyDiff.T t : items) if (SpotifyDiff.restorable(t)) uris.add(t.uri); else skipped++;
            String target;
            String name = meta.optString("name", "Playlist");
            if ("replace".equals(mode)) {
                if (!meta.optBoolean("own")) throw new Exception("Alleen je eigen playlists kunnen worden overschreven");
                target = plId;
                progress = "Terugzetten: " + name;
                // Eerst de eerste 100 (vervangt alles), dan de rest erbij
                api(c, "PUT", "/playlists/" + enc(target) + "/items", new JSONObject().put("uris", new JSONArray(uris.subList(0, Math.min(100, uris.size())))));
                for (int i = 100; i < uris.size(); i += 100) {
                    progress = "Terugzetten: " + Math.min(uris.size(), i) + " van " + uris.size();
                    api(c, "POST", "/playlists/" + enc(target) + "/items", new JSONObject().put("uris", new JSONArray(uris.subList(i, Math.min(i + 100, uris.size())))));
                }
            } else {
                progress = "Playlist maken…";
                String when = new SimpleDateFormat("d-M-yyyy", new Locale("nl", "NL")).format(new Date());
                JSONObject made = api(c, "POST", "/me/playlists", new JSONObject().put("name", PodcastFeed.cut(name + " (hersteld " + when + ")", 100))
                        .put("public", false).put("description", PodcastFeed.cut("Teruggezet door Rene's Tools uit een backup. " + meta.optString("desc"), 300)));
                target = made.optString("id");
                if (target.isEmpty()) throw new Exception("Spotify maakte geen playlist");
                for (int i = 0; i < uris.size(); i += 100) {
                    progress = "Nummers toevoegen: " + i + " van " + uris.size();
                    api(c, "POST", "/playlists/" + enc(target) + "/items", new JSONObject().put("uris", new JSONArray(uris.subList(i, Math.min(i + 100, uris.size())))));
                }
            }
            return new JSONObject().put("id", target).put("added", uris.size()).put("skipped", skipped).put("name", name)
                    .put("url", "https://open.spotify.com/playlist/" + target);
        } finally { progress = ""; BUSY.set(false); }
    }
}
