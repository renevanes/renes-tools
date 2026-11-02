package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Statistieken voor verbeteringen: tellingen en fouten, nooit inhoud. Eén keer per week naar een privé
 * GitHub-repository die Rene zelf instelt (Instellingen → Foutrapport en statistieken), zodat een agent ze kan
 * bekijken. Staat standaard aan, maar er gaat niets weg zolang er geen repository en sleutel zijn ingesteld.
 *
 * Wat erin staat (zie {@link #report}): versie, Android-versie en toestelmodel, per soort logregel (de vaste naam
 * zoals BACKUP of PODCAST) hoe vaak die voorkwam, en per vastloper alleen het soort fout en de plek in de eigen code
 * (bijv. "java.lang.IllegalStateException" bij "Podcasts.open:72"). Geen foutteksten, namen, nummers, berichten,
 * bestandsnamen of adressen: die kunnen over anderen gaan (privacyregels in AGENTS.md).
 *
 * De sleutel (een fine-grained token die alleen in die ene repository mag schrijven) staat versleuteld met
 * Secure.deviceKey en gaat niet mee in backups (AppData.PREFS_SKIP, backup_rules.xml).
 */
final class Statistiek {

    private Statistiek() { }

    static final long WEEK = 7 * 86_400_000L;
    static final int MAX_TAGS = 80, MAX_CRASHES = 30;
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");
    /** Alleen een fine-grained sleutel (github_pat_…): die kan beperkt worden tot één repository. */
    private static final Pattern TOKEN = Pattern.compile("github_pat_[A-Za-z0-9_]{20,250}");
    private static final Pattern TAG = Pattern.compile("[A-Z][A-Z0-9_ -]{0,23}");
    private static final String OWN = "nl.rene.tools.";
    /** Eén slot voor de tellingen. Nooit vasthouden tijdens het versturen: App.log wacht erop. */
    private static final Object LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean SENDING = new java.util.concurrent.atomic.AtomicBoolean(false);

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("statistiek", Context.MODE_PRIVATE); }

    // ---------- tellen (vanuit App.log) ----------

    /** Telt één logregel. Nooit een fout naar buiten: dit draait in het foutlogboek zelf. */
    static void count(Context c, String kind) {
        try {
            synchronized (LOCK) {
                SharedPreferences p = prefs(c);
                if (!p.getBoolean("on", true)) return;
                JSONObject tags = new JSONObject(p.getString("tags", "{}"));
                String k = tag(kind);
                if (tags.has(k) || tags.length() < MAX_TAGS) tags.put(k, tags.optInt(k) + 1);
                SharedPreferences.Editor e = p.edit().putString("tags", tags.toString());
                if (!p.contains("since")) e.putLong("since", System.currentTimeMillis());
                e.apply();
            }
        } catch (Throwable ignored) { }
    }

    /** Een vastloper (vanuit de foutafhandeling van App, vlak voordat het proces stopt): meteen wegschrijven. */
    static void crash(Context c, Throwable t) {
        try {
            synchronized (LOCK) {
                SharedPreferences p = prefs(c);
                if (!p.getBoolean("on", true)) return;
                JSONObject cr = new JSONObject(p.getString("crashes", "{}"));
                String key = crashKey(t);
                if (cr.has(key) || cr.length() < MAX_CRASHES) cr.put(key, cr.optInt(key) + 1);
                SharedPreferences.Editor e = p.edit().putString("crashes", cr.toString());
                if (!p.contains("since")) e.putLong("since", System.currentTimeMillis());
                e.commit(); // apply() haalt het niet: het proces stopt zo meteen
            }
        } catch (Throwable ignored) { }
    }

    /** De vaste naam van de logregel; alles wat er niet als een vaste naam uitziet telt als "ANDERS". */
    static String tag(String kind) {
        return kind != null && TAG.matcher(kind).matches() ? kind : "ANDERS";
    }

    /** "soort fout | plek in de eigen code" uit de fout zelf (nooit de fouttekst: die kan gegevens bevatten).
     *  De diepste oorzaak is de echte fout; de plek is de eerste regel in de eigen code (van de oorzaak, anders hoger). */
    static String crashKey(Throwable t) {
        if (t == null) return "onbekend | buiten de app";
        java.util.List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable x = t; x != null && chain.size() < 20 && !chain.contains(x); x = x.getCause()) chain.add(x);
        Throwable root = chain.get(chain.size() - 1);
        String plek = "buiten de app";
        for (int i = chain.size() - 1; i >= 0 && "buiten de app".equals(plek); i--) {
            for (StackTraceElement el : chain.get(i).getStackTrace()) {
                String cls = el.getClassName();
                if (cls == null || !cls.startsWith(OWN) || el.getLineNumber() < 0) continue;
                String naam = cls.substring(OWN.length());
                int d = naam.indexOf('$');
                if (d > 0) naam = naam.substring(0, d);
                plek = naam + "." + el.getMethodName() + ":" + el.getLineNumber();
                break;
            }
        }
        return root.getClass().getName() + " | " + plek;
    }

    // ---------- het rapport ----------

    /** Het weekrapport (alleen tellingen). Ook om te bekijken in de app ("Wat wordt er verstuurd?"). */
    static JSONObject report(Context c) throws Exception {
        boolean beperkt = Care.batteryProblem(c) != null;
        String id = installId(c);
        String tags, crashes;
        long since;
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            tags = p.getString("tags", "{}"); crashes = p.getString("crashes", "{}"); since = p.getLong("since", System.currentTimeMillis());
        }
        return report(new JSONObject(tags), new JSONObject(crashes), since, System.currentTimeMillis(),
                Version.NAME, Version.CODE, Version.NATIVE_LEVEL, Build.VERSION.SDK_INT, Build.MANUFACTURER + " " + Build.MODEL, id, beperkt);
    }

    /** Bouwt het rapport uit losse waarden (zonder Android: zo is het te testen). */
    static JSONObject report(JSONObject tags, JSONObject crashes, long since, long now, String app, int code, int nativeLevel,
                             int sdk, String toestel, String id, boolean beperkt) throws Exception {
        JSONObject log = new JSONObject();
        for (Iterator<String> it = tags.keys(); it.hasNext(); ) {
            String k = it.next();
            if (TAG.matcher(k).matches() || "ANDERS".equals(k)) log.put(k, Math.max(0, tags.optInt(k)));
        }
        JSONArray cr = new JSONArray();
        for (Iterator<String> it = crashes.keys(); it.hasNext(); ) {
            String k = it.next();
            String[] d = k.split(" \\| ", 2);
            cr.put(new JSONObject().put("soort", d[0]).put("plek", d.length > 1 ? d[1] : "").put("aantal", crashes.optInt(k)));
        }
        return new JSONObject()
                .put("schema", 1)
                .put("installatie", id)
                .put("van", iso(since)).put("tot", iso(now))
                .put("app", app).put("code", code).put("native", nativeLevel)
                .put("android", sdk).put("toestel", toestel == null ? "" : toestel.trim())
                .put("achtergrondBeperkt", beperkt)
                .put("logregels", log)
                .put("vastlopers", cr);
    }

    static String iso(long t) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(t));
    }

    /** Willekeurige naam van deze installatie (om toestellen uit elkaar te houden; zegt niets over wie het is). */
    static String installId(Context c) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            String id = p.getString("id", "");
            if (id.isEmpty()) { id = UUID.randomUUID().toString().substring(0, 8); p.edit().putString("id", id).apply(); }
            return id;
        }
    }

    // ---------- instellen ----------

    static boolean validRepo(String r) {
        return r != null && REPO.matcher(r).matches() && !r.contains("..") && !r.substring(r.indexOf('/') + 1).matches("\\.+");
    }
    static boolean validToken(String t) { return t != null && TOKEN.matcher(t).matches(); }

    /** {on, repo, token (alleen of hij er is), since, lastSent, lastResult} voor het instellingenscherm. */
    static JSONObject state(Context c) {
        SharedPreferences p = prefs(c);
        try {
            return new JSONObject().put("on", p.getBoolean("on", true)).put("repo", p.getString("repo", ""))
                    .put("token", !p.getString("token", "").isEmpty()).put("since", p.getLong("since", 0))
                    .put("lastSent", p.getLong("lastSent", 0)).put("lastResult", p.getString("lastResult", ""));
        } catch (Exception e) { return new JSONObject(); }
    }

    static void setOn(Context c, boolean on) {
        synchronized (LOCK) {
            SharedPreferences.Editor e = prefs(c).edit().putBoolean("on", on);
            if (!on) e.remove("tags").remove("crashes").remove("since").remove("pendingPath").remove("pendingBody"); // uit = ook de tellingen weg
            e.apply();
        }
    }

    /** Repository en sleutel; een lege sleutel laat de bestaande staan. "" bij succes, anders de reden. */
    static String setDest(Context c, String repo, String token) {
        repo = repo == null ? "" : repo.trim();
        token = token == null ? "" : token.trim();
        if (repo.isEmpty() && token.isEmpty()) { prefs(c).edit().remove("repo").remove("token").apply(); return ""; }
        if (!validRepo(repo)) return "Vul de repository in als eigenaar/naam, bijv. renevanes/renes-tools-statistiek";
        if (!token.isEmpty() && !validToken(token)) return "Gebruik een fine-grained sleutel (begint met github_pat_) die alleen in deze repository mag schrijven";
        SharedPreferences.Editor e = prefs(c).edit().putString("repo", repo);
        if (!token.isEmpty()) {
            try { e.putString("token", seal(token)); } catch (Exception x) { return "Versleuteld bewaren lukt niet"; }
        } else if (prefs(c).getString("token", "").isEmpty()) return "Vul ook de sleutel in";
        e.apply();
        return "";
    }

    // ---------- versturen ----------

    /** Vanuit het dagelijkse onderhoud (Care): eens per week versturen, als het aan staat en is ingesteld. */
    static void daily(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("on", true) || p.getString("repo", "").isEmpty() || p.getString("token", "").isEmpty()) return;
        if (System.currentTimeMillis() - p.getLong("lastSent", 0) < WEEK) return;
        send(c);
    }

    /** Verstuurt het rapport nu. "" bij succes, anders de reden (ook bewaard als lastResult). Niet op de hoofdthread.
     *  De tellingen gaan eerst over naar een "klaarstaand" rapport (onder het slot, kort); daarna wordt zonder slot
     *  verstuurd, zodat App.log nooit op het netwerk wacht. Lukt het niet, dan gaat dezelfde inhoud de volgende keer
     *  opnieuw onder dezelfde naam (een 422 = die staat er al: dan was het toch aangekomen). */
    static String send(Context c) {
        if (!SENDING.compareAndSet(false, true)) return "Er wordt al verstuurd";
        try {
            SharedPreferences p = prefs(c);
            if (!p.getBoolean("on", true)) return "Staat uit";
            String repo = p.getString("repo", ""), sealed = p.getString("token", "");
            if (!validRepo(repo) || sealed.isEmpty()) return "Stel eerst de repository en de sleutel in";
            String result;
            try {
                String path, body;
                synchronized (LOCK) { // kort: alleen voorkeuren lezen en schrijven (het slot is herintreedbaar)
                    path = p.getString("pendingPath", "");
                    body = p.getString("pendingBody", "");
                    if (path.isEmpty() || body.isEmpty()) {
                        long now = System.currentTimeMillis();
                        path = "statistiek/" + iso(now).substring(0, 10) + "-" + installId(c) + ".json";
                        body = report(c).toString(2);
                        // wat na dit moment geteld wordt, hoort bij het volgende rapport
                        p.edit().putString("pendingPath", path).putString("pendingBody", body)
                                .remove("tags").remove("crashes").putLong("since", now).commit();
                    }
                }
                int code = put(repo, open(sealed), path, body);
                if (code == 201 || code == 200 || code == 422) {
                    synchronized (LOCK) {
                        p.edit().remove("pendingPath").remove("pendingBody").putLong("lastSent", System.currentTimeMillis()).apply();
                    }
                    result = "";
                } else result = code == 401 || code == 403 ? "De sleutel werkt niet (meer), of mag niet in deze repository schrijven"
                        : code == 404 ? "Repository niet gevonden (of de sleutel mag er niet bij)" : "Versturen lukte niet (HTTP " + code + ")";
            } catch (Exception e) {
                result = "Versturen lukte niet (geen verbinding?)";
            }
            p.edit().putString("lastResult", result.isEmpty() ? "ok" : result).putLong("lastTry", System.currentTimeMillis()).apply();
            return result;
        } finally { SENDING.set(false); }
    }

    /** PUT naar de GitHub-API (vaste host, alleen https). Geeft de HTTP-status. */
    private static int put(String repo, String token, String path, String json) throws Exception {
        URL u = new URL("https://api.github.com/repos/" + repo + "/contents/" + path);
        HttpURLConnection h = (HttpURLConnection) u.openConnection();
        try {
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(15_000);
            h.setReadTimeout(30_000);
            h.setRequestMethod("PUT");
            h.setDoOutput(true);
            h.setRequestProperty("Authorization", "Bearer " + token);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            h.setRequestProperty("User-Agent", "Renes-Tools/" + Version.NAME);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = new JSONObject().put("message", "Statistiek " + path.substring(path.lastIndexOf('/') + 1))
                    .put("content", Secure.b64(json.getBytes(StandardCharsets.UTF_8))).toString().getBytes(StandardCharsets.UTF_8);
            h.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = h.getOutputStream()) { o.write(body); }
            int code = h.getResponseCode();
            try (InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream()) { drain(in); } catch (Exception ignored) { }
            return code;
        } finally { h.disconnect(); }
    }

    /** Het antwoord leeglezen (dan kan de verbinding opnieuw gebruikt worden); de inhoud doet er niet toe. */
    private static void drain(InputStream in) throws Exception {
        if (in == null) return;
        byte[] b = new byte[4096];
        int n, total = 0;
        while (total < 65536 && (n = in.read(b)) > 0) total += n;
    }

    // ---------- de sleutel versleuteld (zoals Vpn) ----------

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
}
