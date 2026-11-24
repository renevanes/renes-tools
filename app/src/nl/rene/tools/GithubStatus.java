package nl.rene.tools;

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
    static final int MAX = 4 * 1024 * 1024, MAX_RUNS = 100, MAX_PUNTEN = 40, MAX_LOS = 8;
    /** Zo lang geldt een opgehaalde stand (GitHub staat zonder sleutel 60 vragen per uur toe). */
    static final long BEWAAR = 60_000L;

    private static JSONObject laatst;
    private static long laatstT;
    /** Workflows zonder run op main (die alleen vanuit een andere workflow draaien): even niet opnieuw vragen. */
    private static final Map<Long, Long> LEEG = new java.util.concurrent.ConcurrentHashMap<>();
    static final long LEEG_BEWAAR = 30 * 60_000L;

    static final class Fout extends Exception {
        Fout(String m) { super(m); }
    }

    /**
     * Eén vraag aan de API van GitHub (alleen deze repository, geen doorverwijzingen, begrensd in tijd en grootte). Een
     * fout is een Fout met een Nederlandse zin; wie hem toont, vertaalt hem met Taal.tx (zoals Signalen dat doet).
     */
    static String haal(String pad) throws Fout {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(API + pad).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setUseCaches(false);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            con.setRequestProperty("User-Agent", "Renes-Tools/" + Version.NAME);
            int code;
            try { code = con.getResponseCode(); }
            catch (java.io.IOException e) { throw new Fout(Taal.T("GitHub is niet bereikbaar (geen internet?)")); }
            if (code == 429 || (code == 403 && "0".equals(String.valueOf(con.getHeaderField("X-RateLimit-Remaining")).trim()))) {
                long reset = 0;
                try { reset = Long.parseLong(String.valueOf(con.getHeaderField("X-RateLimit-Reset")).trim()) * 1000L; } catch (Exception ignored) { }
                long min = reset > 0 ? Math.max(1, (reset - System.currentTimeMillis() + 59_999) / 60_000) : 0;
                throw new Fout(min == 0 ? Taal.T("GitHub laat even geen vragen meer toe; probeer het straks opnieuw")
                        : min == 1 ? Taal.T("GitHub laat even geen vragen meer toe; probeer het over een minuut opnieuw")
                        : Taal.fill(Taal.T("GitHub laat even geen vragen meer toe; probeer het over {n} minuten opnieuw"), "n", String.valueOf(min)));
            }
            if (code != 200) throw new Fout(Taal.fill(Taal.T("GitHub antwoordt met {code}"), "code", String.valueOf(code)));
            try { return new String(Updater.readAll(new PodcastFeed.Limited(con.getInputStream(), MAX)), StandardCharsets.UTF_8); }
            catch (Exception e) { throw new Fout(Taal.T("Het antwoord van GitHub kwam niet helemaal binnen")); }
        } catch (Fout e) { throw e; }
        catch (Exception e) { throw new Fout(Taal.T("GitHub is niet bereikbaar (geen internet?)")); }
        finally { if (con != null) con.disconnect(); }
    }

    static JSONObject json(String s) throws Fout {
        try { return new JSONObject(s); } catch (JSONException e) { throw new Fout(Taal.T("Het antwoord van GitHub was niet te lezen")); }
    }

    /**
     * De stand: runs (de nieuwste 100), per taak de laatste run op main (voor taken die daar niet bij zitten, zoals een
     * wekelijkse taak, hooguit MAX_LOS losse vragen), en de open punten. vers = niet de stand van de laatste minuut.
     */
    static JSONObject overzicht(boolean vers) throws Fout {
        long nu = System.currentTimeMillis();
        synchronized (GithubStatus.class) {
            if (!vers && laatst != null && nu - laatstT < BEWAAR && nu >= laatstT) return kopie(laatst);
        }
        JSONObject runs = json(haal("/actions/runs?per_page=" + MAX_RUNS));
        // Taken die in deze runs niet voorkomen: hun laatste run op main los ophalen (de rest van het scherm gaat voor)
        JSONArray extra = new JSONArray();
        try {
            JSONArray wf = json(haal("/actions/workflows?per_page=100")).optJSONArray("workflows");
            java.util.Set<String> gezien = new java.util.HashSet<>();
            JSONArray rs = runs.optJSONArray("workflow_runs");
            // Alleen een run op main telt als gezien: anders bepaalt een run op een andere tak de stand van die taak
            for (int i = 0; rs != null && i < rs.length(); i++) {
                JSONObject r = rs.optJSONObject(i);
                if (r != null && "main".equals(r.optString("head_branch")) && eigen(r)) gezien.add(r.optString("name"));
            }
            int los = 0;
            for (int i = 0; wf != null && i < wf.length() && los < MAX_LOS; i++) {
                JSONObject w = wf.optJSONObject(i);
                if (w == null || !"active".equals(w.optString("state")) || gezien.contains(w.optString("name")) || w.optLong("id") <= 0) continue;
                if (w.optString("path").startsWith("dynamic/")) continue; // Dependabot e.d.: geen eigen workflow
                Long leegT = LEEG.get(w.optLong("id"));
                if (leegT != null && nu - leegT < LEEG_BEWAAR && nu >= leegT) continue; // had onlangs geen run op main
                los++;
                JSONArray een = json(haal("/actions/workflows/" + w.optLong("id") + "/runs?branch=main&per_page=1")).optJSONArray("workflow_runs");
                if (een != null && een.length() > 0) extra.put(een.get(0));
                else LEEG.put(w.optLong("id"), nu);
            }
        } catch (Exception ignored) { } // dan zonder: het scherm zegt welke periode de runs beslaan
        JSONArray punten;
        try { punten = new JSONArray(haal("/issues?state=open&per_page=" + MAX_PUNTEN + "&sort=updated")); }
        catch (Exception e) { punten = null; } // de runs zijn het belangrijkst
        JSONObject o;
        try { o = samenvatten(runs, extra, punten, nu); }
        catch (JSONException e) { throw new Fout(Taal.T("Het antwoord van GitHub was niet te lezen")); }
        synchronized (GithubStatus.class) { laatst = o; laatstT = nu; }
        return kopie(o);
    }

    /** Voor Signalen: alleen de mislukte runs op main (klein antwoord: geen open punten, geen geslaagde runs). */
    static JSONArray mislukt() throws Fout {
        // status=completed (niet failure): ook een run die te lang duurde of niet kon starten telt als mislukt
        JSONObject r = json(haal("/actions/runs?branch=main&status=completed&per_page=30"));
        try { return samenvatten(r, null, null, System.currentTimeMillis()).optJSONArray("runs"); }
        catch (JSONException e) { throw new Fout(Taal.T("Het antwoord van GitHub was niet te lezen")); }
    }

    private static JSONObject kopie(JSONObject o) {
        try { return new JSONObject(o.toString()); } catch (JSONException e) { return new JSONObject(); }
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

    /**
     * Hoort deze run bij main van deze repository zelf? Niet bij een pull request (ook niet uit een fork met een tak die
     * toevallig main heet): alleen een push, een geplande of een met de hand gestarte run, uit deze repository.
     */
    static boolean eigen(JSONObject r) {
        JSONObject hr = r.optJSONObject("head_repository");
        String ev = r.optString("event");
        return hr != null && REPO.equals(hr.optString("full_name"))
                && ("push".equals(ev) || "schedule".equals(ev) || "workflow_dispatch".equals(ev) || "workflow_run".equals(ev));
    }

    static String kort(String s, int max) {
        s = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /**
     * Samenvatting voor het scherm: runs (nieuwste eerst), per taak de laatste run (en de laatste op main), de tellingen en
     * de open punten (zonder pull requests; die apart geteld).
     */
    static JSONObject samenvatten(JSONObject runsJson, JSONArray extra, JSONArray punten, long nu) throws JSONException {
        JSONArray in = new JSONArray(), rs = runsJson == null ? null : runsJson.optJSONArray("workflow_runs");
        for (int i = 0; rs != null && i < rs.length(); i++) in.put(rs.get(i));
        int gewoon = in.length();
        for (int i = 0; extra != null && i < extra.length(); i++) in.put(extra.get(i));
        JSONArray runs = new JSONArray();
        Map<String, JSONObject[]> perTaak = new LinkedHashMap<>();
        long sinds = 0;
        for (int i = 0; i < in.length() && runs.length() < MAX_RUNS + MAX_LOS; i++) {
            JSONObject r = in.optJSONObject(i);
            if (r == null) continue;
            String naam = kort(r.optString("name"), 80);
            if (naam.isEmpty()) continue;
            long start = tijd(r.optString("run_started_at", r.optString("created_at"))), eind = tijd(r.optString("updated_at"));
            String tak = kort(r.optString("head_branch"), 80);
            JSONObject x = new JSONObject().put("id", r.optLong("id")).put("naam", naam).put("titel", kort(r.optString("display_title"), 160))
                    .put("tak", tak).put("main", "main".equals(tak) && eigen(r)).put("los", i >= gewoon).put("soort", soort(r.optString("status"), r.isNull("conclusion") ? "" : r.optString("conclusion")))
                    .put("nr", r.optInt("run_number")).put("start", start).put("eind", eind).put("url", url(r.optString("html_url")))
                    .put("aanleiding", kort(r.optString("event"), 30));
            if (i < gewoon) { runs.put(x); if (start > 0) sinds = sinds == 0 ? start : Math.min(sinds, start); }
            if ("dynamic".equals(r.optString("event"))) continue; // Dependabot e.d.: wel bij de runs, geen eigen taak
            JSONObject[] p = perTaak.get(naam);
            if (p == null) { p = new JSONObject[2]; perTaak.put(naam, p); }
            if (p[0] == null) p[0] = x;                  // de runs komen nieuwste eerst
            if (p[1] == null && x.optBoolean("main")) p[1] = x;
        }
        JSONArray taken = new JSONArray();
        int bezig = 0, mislukt = 0, gelukt = 0;
        for (int i = 0; i < runs.length(); i++) if ("bezig".equals(runs.getJSONObject(i).optString("soort"))) bezig++;
        // Een losse run (van een taak die niet bij de nieuwste runs zat) telt ook als hij nog bezig is
        for (JSONObject[] p : perTaak.values()) if (p[0] != null && p[0].optBoolean("los") && "bezig".equals(p[0].optString("soort"))) bezig++;
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
        return new JSONObject().put("t", nu).put("runs", runs).put("taken", taken).put("sinds", sinds)
                .put("samen", new JSONObject().put("bezig", bezig).put("mislukt", mislukt).put("gelukt", gelukt))
                .put("punten", open).put("puntenOk", punten != null).put("prs", prs)
                .put("repo", WEB);
    }
}
