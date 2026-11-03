package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Facturen in Google Drive zetten: Facturen/&lt;leverancier&gt;/naam.pdf in Rene's eigen Drive.
 * Koppelen zoals bij Spotify: Rene maakt eenmalig een eigen Google-"Desktop app" (Client ID + geheim), logt in de browser
 * in en Google stuurt terug naar 127.0.0.1 op de telefoon (PKCE). Alleen de rechten "drive.file": de app ziet en
 * maakt uitsluitend zijn eigen bestanden en mappen, niet de rest van je Drive.
 * De ververs-sleutel en het geheim staan versleuteld (Facturen.zetGeheim, "facturen_geheim").
 */
final class FacturenDrive {
    private FacturenDrive() { }

    static final int PORT = 43821;
    static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth", TOKEN = "https://oauth2.googleapis.com/token";
    static final String API = "https://www.googleapis.com/drive/v3", UPLOAD = "https://www.googleapis.com/upload/drive/v3";
    static final String MAP_MIME = "application/vnd.google-apps.folder";

    static SharedPreferences prefs(Context c) { return Facturen.prefs(c); }
    static boolean gekoppeld(Context c) { return Facturen.heeftGeheim(c, "gd_refresh"); }

    /** Welke rechten de Google-koppeling heeft gekregen: "drive" (opslaan in Drive) en/of "gmail" (facturen zoeken, alleen lezen). */
    static boolean heeftRecht(Context c, String recht) {
        if (!gekoppeld(c)) return false;
        String s = prefs(c).getString("gdScopes", null);
        if (s == null) return "drive".equals(recht); // oudere koppeling: alleen Drive
        return s.contains("drive".equals(recht) ? SCOPE : FacturenGmail.SCOPE);
    }

    /** Rechten (bijv. "drive,gmail") → de scopes voor Google. */
    static String scopes(String rechten) {
        StringBuilder b = new StringBuilder();
        if (rechten == null || rechten.contains("drive")) b.append(SCOPE);
        if (rechten != null && rechten.contains("gmail")) b.append(b.length() > 0 ? " " : "").append(FacturenGmail.SCOPE);
        return b.length() == 0 ? SCOPE : b.toString();
    }

    private static final Object TOKEN_LOCK = new Object();
    private static String access; private static long accessTot;

    static boolean geldigeClientId(String id) { return id != null && id.trim().matches("[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com"); }

    // ---------- koppelen ----------

    static volatile ServerSocket wacht;

    /** Inloggen starten: geeft het adres voor de browser. done komt op een achtergrondthread. */
    static String start(Context c, String clientId, String geheim, String rechten, Spotify.Done done) throws Exception {
        final String scope = scopes(rechten);
        final String cid = clientId == null ? "" : clientId.trim();
        if (!geldigeClientId(cid)) throw new Exception("Dat lijkt geen Client ID van Google (eindigt op .apps.googleusercontent.com)");
        final String sec = geheim == null || geheim.trim().isEmpty() ? Facturen.geheim(c, "gd_secret") : geheim.trim();
        if (sec == null || sec.isEmpty()) throw new Exception("Vul ook het Client secret in");
        ServerSocket oud = wacht;
        wacht = null; // eerst loslaten: de oude poging stopt dan stil (geen "afgebroken" midden in de nieuwe)
        if (oud != null) try { oud.close(); } catch (Exception ignored) { }
        final ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        try { ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4); } // alleen de telefoon zelf
        catch (Exception e) { try { ss.close(); } catch (Exception ignored) { } throw new Exception("Poort " + PORT + " is bezet; probeer het zo opnieuw"); }
        ss.setSoTimeout(300_000);
        wacht = ss;
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        final String verifier = Spotify.randomString(rnd, 64), state = Spotify.randomString(rnd, 24);
        byte[] dig = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = android.util.Base64.encodeToString(dig, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String err = null, naam = null;
            Socket browser = null;
            try {
                Object[] got;
                try { got = Spotify.awaitCode(ss, state); }
                catch (java.net.SocketTimeoutException e) { throw new Spotify.LoginEnd("Geen antwoord van Google binnen 5 minuten; probeer opnieuw"); }
                catch (java.net.SocketException e) { throw new Spotify.LoginEnd(wacht == ss ? "Koppelen afgebroken" : null); }
                browser = (Socket) got[1];
                JSONObject t = form("grant_type=authorization_code&code=" + enc((String) got[0]) + "&redirect_uri=" + enc(REDIRECT)
                        + "&client_id=" + enc(cid) + "&client_secret=" + enc(sec) + "&code_verifier=" + enc(verifier));
                if (t.has("error")) throw new Exception("Google: " + t.optString("error_description", t.optString("error")));
                String refresh = t.optString("refresh_token", ""), acc = t.optString("access_token", "");
                if (refresh.isEmpty() || acc.isEmpty()) throw new Exception("Google gaf geen blijvende toegang; probeer opnieuw");
                // Eerst kijken of het echt werkt (Drive API / Gmail API aan?), pas dan bewaren
                String gekregen = t.optString("scope", scope);
                if (scope.contains(SCOPE) && !gekregen.contains(SCOPE)) throw new Exception("Je gaf geen toestemming voor Google Drive; vink die aan bij Google");
                if (scope.contains(FacturenGmail.SCOPE) && !gekregen.contains(FacturenGmail.SCOPE)) throw new Exception("Je gaf geen toestemming voor Gmail; vink die aan bij Google");
                JSONObject over = gekregen.contains(SCOPE) ? metToken(acc, API + "/about?fields=user(emailAddress,displayName)") : null;
                if (gekregen.contains(FacturenGmail.SCOPE)) {
                    JSONObject p = metToken(acc, FacturenGmail.API + "/profile");
                    if (over == null) over = new JSONObject().put("user", new JSONObject().put("emailAddress", p.optString("emailAddress")));
                }
                synchronized (TOKEN_LOCK) {
                    Facturen.zetGeheim(app, "gd_secret", sec);
                    Facturen.zetGeheim(app, "gd_refresh", refresh);
                    prefs(app).edit().putString("gdClientId", cid).putString("gdScopes", gekregen).remove("gdMappen").apply();
                    access = acc; accessTot = System.currentTimeMillis() + Math.max(0, t.optLong("expires_in", 3600) - 120) * 1000L;
                }
                JSONObject u = over.optJSONObject("user");
                naam = u == null ? "" : u.optString("emailAddress", u.optString("displayName", ""));
                prefs(app).edit().putString("gdGebruiker", naam).apply();
                Spotify.reply(browser, 200, "✓ Google Drive is gekoppeld met Tools" + (naam.isEmpty() ? "" : " (" + Spotify.esc(naam) + ")") + ". Je kunt dit tabblad sluiten en terug naar de app.");
            } catch (Spotify.LoginEnd e) { err = e.getMessage();
            } catch (Throwable e) {
                err = e.getMessage() == null ? "Koppelen lukt niet" : e.getMessage().replace("Spotify: ", "Google: ");
                if (browser != null) Spotify.reply(browser, 200, "Niet gekoppeld: " + Spotify.esc(err) + ". Probeer het opnieuw vanuit de app.");
            } finally {
                if (browser != null) try { browser.close(); } catch (Exception ignored) { }
                try { ss.close(); } catch (Exception ignored) { }
                if (wacht == ss) wacht = null;
            }
            if (err != null || naam != null) done.done(err, naam);
        }, "facturen-drive-login").start();
        return AUTH + "?response_type=code&client_id=" + enc(cid) + "&scope=" + enc(scope) + "&redirect_uri=" + enc(REDIRECT)
                + "&state=" + enc(state) + "&code_challenge_method=S256&code_challenge=" + enc(challenge)
                + "&access_type=offline&prompt=consent";
    }

    /** Ontkoppelen (knop): ook bij Google intrekken, op de achtergrond. Het secret blijft: opnieuw koppelen zonder overtypen. */
    static void ontkoppel(Context c) {
        ServerSocket ss = wacht; wacht = null; if (ss != null) try { ss.close(); } catch (Exception ignored) { }
        final String refresh;
        synchronized (TOKEN_LOCK) {
            refresh = Facturen.geheim(c, "gd_refresh");
            access = null; accessTot = 0;
            Facturen.geheim(c).edit().remove("gd_refresh").apply();
            prefs(c).edit().remove("gdGebruiker").remove("gdMappen").remove("gdScopes").apply();
        }
        if (refresh != null) new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL("https://oauth2.googleapis.com/revoke?token=" + enc(refresh)).openConnection();
                h.setConnectTimeout(15_000); h.setReadTimeout(15_000); h.setRequestMethod("POST"); h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); h.setFixedLengthStreamingMode(0);
                h.getOutputStream().close(); h.getResponseCode(); h.disconnect();
            } catch (Throwable ignored) { }
        }, "facturen-drive-intrekken").start();
    }

    /** Eén GET met een gegeven token (bij het koppelen, voordat er iets bewaard is). */
    private static JSONObject metToken(String token, String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(20_000); h.setReadTimeout(30_000);
            h.setRequestProperty("Authorization", "Bearer " + token);
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            String t = in == null ? "" : new String(Facturen.lees(in, 1_000_000), StandardCharsets.UTF_8);
            JSONObject o = t.trim().startsWith("{") ? new JSONObject(t) : new JSONObject();
            if (code >= 400) {
                JSONObject e = o.optJSONObject("error");
                String m = e == null ? "" : e.optString("message", "");
                boolean gm = url.startsWith(FacturenGmail.API);
                if (code == 403 && (m.isEmpty() || m.toLowerCase(java.util.Locale.ROOT).contains("has not been used") || m.toLowerCase(java.util.Locale.ROOT).contains("disabled")))
                    m = gm ? "zet eerst de Gmail API aan in je Google-project (stap 2)" : "zet eerst de Google Drive API aan in je Google-project (stap 2)";
                throw new Exception((gm ? "Gmail: " : "Google Drive: ") + (m.isEmpty() ? "fout " + code : m));
            }
            return o;
        } finally { h.disconnect(); }
    }

    // ---------- toegang ----------

    static String token(Context c) throws Exception {
        synchronized (TOKEN_LOCK) {
            if (access != null && System.currentTimeMillis() < accessTot) return access;
            String refresh = Facturen.geheim(c, "gd_refresh"), sec = Facturen.geheim(c, "gd_secret");
            if (refresh == null || sec == null) throw new Exception("Koppel Google Drive (opnieuw) in Facturen");
            JSONObject t = form("grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(prefs(c).getString("gdClientId", "")) + "&client_secret=" + enc(sec));
            if ("invalid_grant".equals(t.optString("error"))) {
                if (refresh.equals(Facturen.geheim(c, "gd_refresh"))) { // niet een net opnieuw gekoppelde sleutel weghalen
                    Facturen.geheim(c).edit().remove("gd_refresh").apply();
                    prefs(c).edit().remove("gdGebruiker").apply();
                }
                throw new Exception("Google vraagt om opnieuw koppelen (staat je Google-app op 'In productie'?)");
            }
            if (t.has("error") || t.optString("access_token").isEmpty()) throw new Exception("Google: " + t.optString("error_description", t.optString("error", "geen toegang")));
            access = t.getString("access_token");
            accessTot = System.currentTimeMillis() + Math.max(0, t.optLong("expires_in", 3600) - 120) * 1000L;
            return access;
        }
    }

    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; } }

    private static JSONObject form(String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(TOKEN).openConnection();
        try {
            h.setConnectTimeout(20_000); h.setReadTimeout(30_000); h.setRequestMethod("POST"); h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            h.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = h.getOutputStream()) { o.write(b); }
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            String t = in == null ? "" : new String(Facturen.lees(in, 1_000_000), StandardCharsets.UTF_8);
            return t.trim().startsWith("{") ? new JSONObject(t) : new JSONObject().put("error", "fout " + code);
        } finally { h.disconnect(); }
    }

    /** Eén Drive-verzoek; body = JSON (metadata) of null. Bij 401 één keer met een nieuw token. */
    static JSONObject api(Context c, String methode, String url, JSONObject body) throws Exception {
        return stuur(c, methode, url, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8", 5_000_000L);
    }

    /** Een GET met een groot antwoord (bijlage uit Gmail: base64 van een PDF tot Facturen.MAX_PDF). */
    static JSONObject apiGroot(Context c, String url) throws Exception {
        return stuur(c, "GET", url, null, null, Facturen.MAX_PDF * 4 / 3 + 65_536);
    }

    private static JSONObject stuur(Context c, String methode, String url, byte[] body, String type) throws Exception {
        return stuur(c, methode, url, body, type, 5_000_000L);
    }

    private static JSONObject stuur(Context c, String methode, String url, byte[] body, String type, long max) throws Exception {
        for (int poging = 0; ; poging++) {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            try {
                h.setConnectTimeout(20_000); h.setReadTimeout(120_000); h.setRequestMethod(methode);
                h.setRequestProperty("Authorization", "Bearer " + token(c));
                if (body != null) {
                    h.setDoOutput(true); h.setRequestProperty("Content-Type", type); h.setFixedLengthStreamingMode(body.length);
                    try (OutputStream o = h.getOutputStream()) { o.write(body); }
                }
                int code = h.getResponseCode();
                if (code == 401 && poging == 0) { synchronized (TOKEN_LOCK) { accessTot = 0; } continue; }
                InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
                String t = in == null ? "" : new String(Facturen.lees(in, max), StandardCharsets.UTF_8);
                JSONObject o = t.trim().startsWith("{") ? new JSONObject(t) : new JSONObject();
                if (code >= 400) {
                    if (code == 404) throw new Exception("niet (meer) gevonden");
                    JSONObject e = o.optJSONObject("error");
                    String m = e == null ? "" : e.optString("message", "");
                    if (code == 403 && m.toLowerCase(java.util.Locale.ROOT).contains("api has not been used")) m = url.startsWith(FacturenGmail.API) ? "zet de Gmail API aan in je Google-project" : "zet de Google Drive API aan in je Google-project";
                    throw new Exception((url.startsWith(FacturenGmail.API) ? "Gmail: " : "Google Drive: ") + (m.isEmpty() ? "fout " + code : m));
                }
                return o;
            } finally { h.disconnect(); }
        }
    }

    // ---------- mappen en uploaden ----------

    static String q(String s) { return s.replace("\\", "\\\\").replace("'", "\\'"); }

    /** Map-id van Facturen/sub (aanmaken als hij er niet is); onthouden. */
    static String map(Context c, String sub) throws Exception {
        JSONObject cache;
        try { cache = new JSONObject(prefs(c).getString("gdMappen", "{}")); } catch (Exception e) { cache = new JSONObject(); }
        String pad = Facturen.MAP + "/" + sub;
        if (!cache.optString(pad).isEmpty()) {
            try { // bestaat hij nog (niet in de prullenbak)?
                JSONObject f = api(c, "GET", API + "/files/" + enc(cache.optString(pad)) + "?fields=id,trashed", null);
                if (!f.optBoolean("trashed")) return cache.optString(pad);
            } catch (Exception ignored) { }
        }
        String ouder = "root";
        java.util.List<String> delen = new java.util.ArrayList<>();
        delen.add(Facturen.MAP);
        for (String d : sub.split("/")) if (!d.trim().isEmpty()) delen.add(d.trim()); // ook Facturen/<leverancier>/<afzender>
        for (String naam : delen) {
            String zoek = "name='" + q(naam) + "' and mimeType='" + MAP_MIME + "' and '" + q(ouder) + "' in parents and trashed=false";
            JSONArray l = api(c, "GET", API + "/files?fields=files(id)&spaces=drive&q=" + enc(zoek), null).optJSONArray("files");
            if (l != null && l.length() > 0) ouder = l.getJSONObject(0).getString("id");
            else ouder = api(c, "POST", API + "/files?fields=id", new JSONObject().put("name", naam).put("mimeType", MAP_MIME)
                    .put("parents", new JSONArray().put(ouder))).getString("id");
        }
        cache.put(pad, ouder);
        prefs(c).edit().putString("gdMappen", cache.toString()).apply();
        return ouder;
    }

    /** Een factuur in Drive zetten (staat er al een met dezelfde naam in de map: niets doen). */
    /** Geeft de id van het bestand in Drive. */
    static String upload(Context c, String sub, String naam, byte[] data) throws Exception {
        if (gekoppeld(c) && !heeftRecht(c, "drive")) throw new Exception("geef Tools toegang tot Drive (Facturen → Koppelingen → Google)");
        String map = map(c, sub);
        String zoek = "name='" + q(naam) + "' and '" + q(map) + "' in parents and trashed=false";
        JSONArray l = api(c, "GET", API + "/files?fields=files(id)&spaces=drive&q=" + enc(zoek), null).optJSONArray("files");
        if (l != null && l.length() > 0) return l.getJSONObject(0).optString("id");
        String grens = "tools" + Long.toHexString(System.nanoTime());
        byte[] meta = new JSONObject().put("name", naam).put("mimeType", "application/pdf").put("parents", new JSONArray().put(map)).toString().getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(data.length + meta.length + 512);
        b.write(("--" + grens + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        b.write(meta);
        b.write(("\r\n--" + grens + "\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        b.write(data);
        b.write(("\r\n--" + grens + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return stuur(c, "POST", UPLOAD + "/files?uploadType=multipart&fields=id", b.toByteArray(), "multipart/related; boundary=" + grens).optString("id", "");
    }

    /** Koppeling testen. */
    static String test(Context c) throws Exception {
        JSONObject u = api(c, "GET", API + "/about?fields=user(emailAddress)", null).optJSONObject("user");
        return u == null ? "" : u.optString("emailAddress");
    }
}
