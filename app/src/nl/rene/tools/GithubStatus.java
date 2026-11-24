package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publiceren en tests: de stand van de workflows (Publiceren, Bouwen en testen, Telefoontest, Onderhoud …) en de open
 * punten van de openbare repository op GitHub. Alleen lezen, zonder sleutel en zonder iets van Rene mee te sturen;
 * GitHub ziet alleen het IP-adres (zoals bij de updatecontrole). Alleen als Rene het scherm opent of verversen kiest,
 * en als Signalen een bron "Publiceren en tests" heeft. Adressen naar GitHub alleen als ze bij deze repository horen.
 */
final class GithubStatus {
    private GithubStatus() { }

    static final String REPO = "renevanes/renes-tools";
    static final String API = "https://api.github.com/repos/" + REPO;
    static final String WEB = "https://github.com/" + REPO + "/";
    static final int MAX = 4 * 1024 * 1024, MAX_RUNS = 60, MAX_PUNTEN = 40;
    /** Zo lang geldt een opgehaalde stand (GitHub staat zonder sleutel 60 vragen per uur toe). */
    static final long BEWAAR = 60_000L;

    private static JSONObject laatst;
    private static long laatstT;

    static final class Fout extends Exception {
        Fout(String m) { super(m); }
    }

    /** Eén vraag aan de API van GitHub (alleen deze repository, geen doorverwijzingen, begrensd in tijd en grootte). */
    static String haal(Context c, String pad) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(API + pad).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(15_000);
        con.setUseCaches(false);
        con.setInstanceFollowRedirects(false);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        con.setRequestProperty("User-Agent", "Renes-Tools/" + Version.NAME);
        try {
            int code;
            try { code = con.getResponseCode(); }
            catch (java.io.IOException e) { throw new Fout(Taal.t(c, "GitHub is niet bereikbaar (geen internet?)")); }
            if (code == 403 || code == 429) {
                long reset = 0;
                try { reset = Long.parseLong(String.valueOf(con.getHeaderField("X-RateLimit-Reset")).trim()) * 1000L; } catch (Exception ignored) { }
                long min = reset > 0 ? Math.max(1, (reset - System.currentTimeMillis() + 59_999) / 60_000) : 0;
                throw new Fout(min > 0 ? Taal.tn(c, (int) min, "GitHub laat even geen vragen meer toe; probeer het over een minuut opnieuw",
                        "GitHub laat even geen vragen meer toe; probeer het over {n} minuten opnieuw")
                        : Taal.t(c, "GitHub laat even geen vragen meer toe; probeer het straks opnieuw"));
            }
            if (code != 200) throw new Fout(Taal.t(c, "GitHub antwoordt met {code}", "code", String.valueOf(code)));
            return new String(Updater.readAll(new PodcastFeed.Limited(con.getInputStream(), MAX)), StandardCharsets.UTF_8);
        } finally { con.disconnect(); }
    }

    /** De stand: runs en open punten, samengevat. vers = niet de stand van de laatste minuut gebruiken. */
    static JSONObject overzicht(Context c, boolean vers) throws Exception {
        long nu = System.currentTimeMillis();
        synchronized (GithubStatus.class) {
            if (!vers && laatst != null && nu - laatstT < BEWAAR && nu >= laatstT) return new JSONObject(laatst.toString());
        }
        JSONObject runs = new JSONObject(haal(c, "/actions/runs?per_page=" + MAX_RUNS));
        JSONArray punten;
        try { punten = new JSONArray(haal(c, "/issues?state=open&per_page=" + MAX_PUNTEN + "&sort=updated")); }
        catch (Fout e) { punten = null; } // de runs zijn het belangrijkst
        JSONObject o = samenvatten(runs, punten, nu);
        synchronized (GithubStatus.class) { laatst = o; laatstT = nu; }
        return new JSONObject(o.toString());
    }

    // ---------------------------------------------------------------- zuiver (getest in GithubStatusTest)

    /** Alleen adressen van deze repository op github.com; anders leeg. */
    static String url(String u) {
        if (u == null || !u.startsWith(WEB) || u.length() > 300) return "";
        for (int i = WEB.length(); i < u.length(); i++) {
            char ch = u.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || "/-_.#?=&".indexOf(ch) >= 0)) return "";
        }
        return u.contains("..") ? "" : u;
    }

    /** ISO-tijd van GitHub ("2026-11-24T10:05:00Z") in milliseconden, of 0. */
    static long tijd(String s) {
        if (s == null || !s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")) return 0;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        f.setLenient(false);
        try { return f.parse(s).getTime(); } catch (java.text.ParseException e) { return 0; }
    }

    /** De uitkomst van een run in één woord: bezig, gelukt, mislukt, gestopt, overgeslagen of wacht. */
    static String soort(String status, String conclusion) {
        if (!"completed".equals(status)) return "action_required".equals(conclusion) ? "wacht" : "bezig";
        switch (conclusion == null ? "" : conclusion) {
            case "success": case "neutral": return "gelukt";
            case "cancelled": return "gestopt";
            case "skipped": return "overgeslagen";
            case "action_required": return "wacht";
            default: return "mislukt"; // failure, timed_out, startup_failure, stale
        }
    }

    static String kort(String s, int max) {
        s = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /**
     * Samenvatting voor het scherm: runs (nieuwste eerst), per taak de laatste run (en de laatste op main), de tellingen en
     * de open punten (zonder pull requests; die apart geteld).
     */
    static JSONObject samenvatten(JSONObject runsJson, JSONArray punten, long nu) throws JSONException {
        JSONArray in = runsJson == null ? null : runsJson.optJSONArray("workflow_runs");
        JSONArray runs = new JSONArray();
        Map<String, JSONObject[]> perTaak = new LinkedHashMap<>();
        for (int i = 0; in != null && i < in.length() && runs.length() < MAX_RUNS; i++) {
            JSONObject r = in.optJSONObject(i);
            if (r == null) continue;
            String naam = kort(r.optString("name"), 80);
            if (naam.isEmpty()) continue;
            long start = tijd(r.optString("run_started_at", r.optString("created_at"))), eind = tijd(r.optString("updated_at"));
            String tak = kort(r.optString("head_branch"), 80);
            JSONObject x = new JSONObject().put("id", r.optLong("id")).put("naam", naam).put("titel", kort(r.optString("display_title"), 160))
                    .put("tak", tak).put("main", "main".equals(tak)).put("soort", soort(r.optString("status"), r.isNull("conclusion") ? "" : r.optString("conclusion")))
                    .put("nr", r.optInt("run_number")).put("start", start).put("eind", eind).put("url", url(r.optString("html_url")))
                    .put("aanleiding", kort(r.optString("event"), 30));
            runs.put(x);
            JSONObject[] p = perTaak.get(naam);
            if (p == null) { p = new JSONObject[2]; perTaak.put(naam, p); }
            if (p[0] == null) p[0] = x;                  // de runs komen nieuwste eerst
            if (p[1] == null && x.optBoolean("main")) p[1] = x;
        }
        JSONArray taken = new JSONArray();
        int bezig = 0, mislukt = 0, gelukt = 0;
        for (int i = 0; i < runs.length(); i++) if ("bezig".equals(runs.getJSONObject(i).optString("soort"))) bezig++;
        // Taken op volgorde: Publiceren eerst, dan wat mislukte, dan de rest op naam
        List<String> namen = new ArrayList<>(perTaak.keySet());
        java.util.Collections.sort(namen, (a, b) -> {
            int pa = a.equals("Publiceren") ? 0 : 1, pb = b.equals("Publiceren") ? 0 : 1;
            if (pa != pb) return pa - pb;
            return a.compareToIgnoreCase(b);
        });
        for (String n : namen) {
            JSONObject[] p = perTaak.get(n);
            // De stand van een taak = de laatste run op main (daar gaat het om); is die er niet, dan de laatste run
            JSONObject stand = p[1] != null ? p[1] : p[0];
            String s = stand.optString("soort");
            if ("mislukt".equals(s)) mislukt++;
            else if ("gelukt".equals(s)) gelukt++;
            taken.put(new JSONObject().put("naam", n).put("stand", stand).put("laatste", p[0]));
        }
        JSONArray open = new JSONArray();
        int prs = 0;
        for (int i = 0; punten != null && i < punten.length(); i++) {
            JSONObject p = punten.optJSONObject(i);
            if (p == null) continue;
            if (p.has("pull_request")) { prs++; continue; }
            JSONArray labels = new JSONArray(), li = p.optJSONArray("labels");
            for (int j = 0; li != null && j < li.length() && labels.length() < 6; j++) {
                JSONObject l = li.optJSONObject(j);
                if (l != null && !kort(l.optString("name"), 40).isEmpty()) labels.put(kort(l.optString("name"), 40));
            }
            open.put(new JSONObject().put("nr", p.optInt("number")).put("titel", kort(p.optString("title"), 160)).put("labels", labels)
                    .put("url", url(p.optString("html_url"))).put("t", tijd(p.optString("updated_at"))));
        }
        return new JSONObject().put("t", nu).put("runs", runs).put("taken", taken)
                .put("samen", new JSONObject().put("bezig", bezig).put("mislukt", mislukt).put("gelukt", gelukt))
                .put("punten", open).put("puntenOk", punten != null).put("prs", prs)
                .put("repo", WEB);
    }
}
