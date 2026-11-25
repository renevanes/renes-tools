package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Podcasts: afgespeeld met de slaaptimer (wens van Rene, 25-11-2026). Met een slaaptimer val je vaak in slaap; wat er
 * intussen afliep telt als beluisterd, maar is misschien niet echt gehoord. Elke aflevering die afloopt terwijl de
 * slaaptimer loopt, komt hier te staan met de plek waar je zeker nog wakker was: het begin van die beurt, of later het
 * moment dat je de timer zette, verlengde, doorspoelde of weer op Afspelen drukte (PodcastService.wakker).
 *
 * Instellingen (prefs van Podcasts): slaapTerug = vragen (standaard: Podcasts vraagt wat je echt hoorde), vanzelf (na de
 * slaaptimer meteen als niet beluisterd markeren) of uit (niets bijhouden); slaapPlek = wakker (terug naar die plek) of
 * begin (helemaal opnieuw). Een download blijft staan zolang een aflevering hier nog open staat (PodcastDownloads.played).
 *
 * Bestand podcasts/slaap.json: {versie, lijst: [{key, ep, pod, van, d, t, sessie, st, auto, gezien}]}, hooguit MAX
 * regels en BEWAAR_MS lang. Op een Chromecast wordt niets bijgehouden. Het zuivere deel is getest in PodcastSlaapTest.
 */
final class PodcastSlaap {
    private PodcastSlaap() { }

    static final String VRAGEN = "vragen", VANZELF = "vanzelf", UIT = "uit";
    static final String WAKKER = "wakker", BEGIN = "begin";
    /** open = nog niet beoordeeld, terug = als niet beluisterd gemarkeerd, gehoord = klopt, beluisterd. */
    static final String OPEN = "open", TERUG = "terug", GEHOORD = "gehoord";
    static final int MAX = 30;
    static final long BEWAAR_MS = 14L * 24 * 3600_000L;
    /** Zo ver vóór het einde blijft de plek hooguit (anders telt hij meteen weer als afgelopen). */
    static final long MARGE_EIND_MS = 60_000L;

    private static final Object LOCK = new Object();

    // ---------- instellingen ----------

    static String norm(String w) { return VANZELF.equals(w) || UIT.equals(w) ? w : VRAGEN; }
    static String normPlek(String p) { return BEGIN.equals(p) ? BEGIN : WAKKER; }
    static String wijze(Context c) { return norm(Podcasts.prefs(c).getString("slaapTerug", VRAGEN)); }
    static String plek(Context c) { return normPlek(Podcasts.prefs(c).getString("slaapPlek", WAKKER)); }
    static void zet(Context c, String wijze, String plek) {
        Podcasts.prefs(c).edit().putString("slaapTerug", norm(wijze)).putString("slaapPlek", normPlek(plek)).apply();
        if (UIT.equals(norm(wijze))) { // niets meer vragen: wat nog open stond telt als gehoord (de download mag dan weg)
            List<String> open = new ArrayList<>();
            synchronized (LOCK) {
                JSONArray l = leesRuw(c);
                for (int i = 0; i < l.length(); i++) if (OPEN.equals(l.optJSONObject(i).optString("st"))) open.add(l.optJSONObject(i).optString("key"));
            }
            oordeel(c, open, GEHOORD, false);
        }
    }

    // ---------- zuiver ----------

    /** De lijst met deze regel erbij: dezelfde aflevering vervangt de oude regel, nieuwste achteraan, hooguit MAX. */
    static JSONArray erbij(JSONArray lijst, JSONObject r) { return erbij(lijst, r, null); }

    /** Idem; een regel die er door de grens uit valt en nog open stond, komt in weg (die telt dan als gehoord). */
    static JSONArray erbij(JSONArray lijst, JSONObject r, List<String> weg) {
        JSONArray b = new JSONArray();
        String key = r.optString("key");
        if (lijst != null) for (int i = 0; i < lijst.length(); i++) {
            JSONObject o = lijst.optJSONObject(i);
            if (o != null && !key.equals(o.optString("key"))) b.put(o);
        }
        b.put(r);
        while (b.length() > MAX) {
            JSONObject o = b.optJSONObject(0);
            if (weg != null && o != null && OPEN.equals(o.optString("st"))) weg.add(o.optString("key"));
            b.remove(0);
        }
        return b;
    }

    /** Wat te oud is gaat eruit; de sleutels die toen nog open stonden komen in weg (die tellen dan als gehoord). */
    static JSONArray opschonen(JSONArray lijst, long nu, List<String> weg) {
        JSONArray b = new JSONArray();
        if (lijst == null) return b;
        for (int i = 0; i < lijst.length(); i++) {
            JSONObject o = lijst.optJSONObject(i);
            if (o == null) continue;
            if (nu - o.optLong("t") > BEWAAR_MS) { if (weg != null && OPEN.equals(o.optString("st"))) weg.add(o.optString("key")); continue; }
            b.put(o);
        }
        return b;
    }

    /** De plek om naar terug te gaan (ms): waar je nog wakker was, of het begin; nooit vlak bij het einde. */
    static long terugPlek(JSONObject r, String plek) {
        if (BEGIN.equals(plek)) return 0;
        long van = Math.max(0, r.optLong("van")), d = r.optLong("d");
        if (d > 0 && van > d - MARGE_EIND_MS) van = Math.max(0, d - MARGE_EIND_MS);
        return van;
    }

    /** De regels van deze sessie die nog open staan (voor vanzelf terugzetten). */
    static List<String> openVan(JSONArray lijst, long sessie) {
        List<String> k = new ArrayList<>();
        if (lijst == null || sessie <= 0) return k;
        for (int i = 0; i < lijst.length(); i++) {
            JSONObject o = lijst.optJSONObject(i);
            if (o != null && o.optLong("sessie") == sessie && OPEN.equals(o.optString("st"))) k.add(o.optString("key"));
        }
        return k;
    }

    /** Eén regel, opgeschoond (komt uit een bestand dat ook uit een backup kan komen, en gaat naar het scherm); of null. */
    static JSONObject schoon(JSONObject in) {
        try {
            if (in == null || !Podcasts.validKey(in.optString("key"))) return null;
            JSONObject ep = Podcasts.cleanEp(in.optJSONObject("ep"));
            if (ep == null || !in.optString("key").equals(ep.optString("key"))) return null;
            String st = in.optString("st");
            if (!TERUG.equals(st) && !GEHOORD.equals(st)) st = OPEN;
            return new JSONObject().put("key", ep.optString("key")).put("ep", ep).put("pod", Podcasts.cleanPod(in.optJSONObject("pod")))
                    .put("van", Math.max(0, in.optLong("van"))).put("d", Math.max(0, in.optLong("d"))).put("t", Math.max(0, in.optLong("t")))
                    .put("sessie", Math.max(0, in.optLong("sessie"))).put("st", st)
                    .put("auto", in.optBoolean("auto")).put("gezien", in.optBoolean("gezien"));
        } catch (Exception e) { return null; }
    }

    static JSONArray schoon(JSONArray in) {
        JSONArray b = new JSONArray();
        if (in != null) for (int i = 0; i < in.length() && b.length() < MAX; i++) { JSONObject o = schoon(in.optJSONObject(i)); if (o != null) b.put(o); }
        return b;
    }

    // ---------- opslag ----------

    static File bestand(Context c) { return new File(Podcasts.dir(c), "slaap.json"); }

    private static JSONArray leesRuw(Context c) {
        JSONObject o = Podcasts.readJson(bestand(c));
        return schoon(o == null ? null : o.optJSONArray("lijst"));
    }

    private static void schrijf(Context c, JSONArray lijst) {
        try { Contacts.writeText(bestand(c), new JSONObject().put("versie", 1).put("lijst", lijst).toString(), false); }
        catch (Exception e) { App.log(c, "PODCAST", "slaaptimer: bewaren mislukt (" + e.getClass().getSimpleName() + ")"); }
    }

    /** De lijst (oudste eerst), opgeschoond; wat te oud werd telt als gehoord (download mag dan weg). */
    static JSONArray lijst(Context c) {
        List<String> weg = new ArrayList<>();
        JSONArray l;
        synchronized (LOCK) {
            JSONArray ruw = leesRuw(c);
            l = opschonen(ruw, System.currentTimeMillis(), weg);
            if (l.length() != ruw.length()) schrijf(c, l);
        }
        downloadsWeg(c, weg);
        return l;
    }

    /** Staat deze aflevering hier nog open? Dan blijft de download staan tot Rene het zegt. */
    static boolean open(Context c, String key) {
        if (key == null) return false;
        synchronized (LOCK) {
            JSONArray l = leesRuw(c);
            for (int i = 0; i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (key.equals(o.optString("key"))) return OPEN.equals(o.optString("st")); }
        }
        return false;
    }

    /** Telt dit mee? Niet als Rene vlak voor het einde nog wakker was (dan hoorde hij het einde). Zuiver. */
    static boolean telt(long wakker, long dur) { return dur <= 0 || wakker < dur - MARGE_EIND_MS; }

    /** Een aflevering liep af terwijl de slaaptimer liep. Vóór het markeren als beluisterd aanroepen (de download blijft dan staan). */
    static void liepAf(Context c, String epJson, String podJson, long wakker, long dur, long sessie) {
        try {
            if (UIT.equals(wijze(c))) return;
            JSONObject ep = Podcasts.cleanEp(new JSONObject(epJson));
            if (ep == null) return;
            if (!telt(wakker, dur)) { wakkerAf(c, ep.optString("key")); return; } // het einde nog gehoord
            JSONObject pod = podJson == null ? new JSONObject() : Podcasts.cleanPod(new JSONObject(podJson));
            JSONObject r = new JSONObject().put("key", ep.optString("key")).put("ep", ep).put("pod", pod)
                    .put("van", Math.max(0, wakker)).put("d", Math.max(0, dur)).put("t", System.currentTimeMillis())
                    .put("sessie", Math.max(0, sessie)).put("st", OPEN).put("auto", false).put("gezien", false);
            List<String> weg = new ArrayList<>();
            synchronized (LOCK) { schrijf(c, erbij(opschonen(leesRuw(c), System.currentTimeMillis(), weg), r, weg)); }
            weg.remove(ep.optString("key"));
            downloadsWeg(c, weg);
            App.log(c, "PODCAST", "slaaptimer: aflevering afgelopen");
        } catch (Throwable t) { App.log(c, "PODCAST", "slaaptimer: bijhouden mislukt (" + t.getClass().getSimpleName() + ")"); }
    }

    /** Rene luisterde een aflevering die hier nog open stond wakker uit (zonder slaaptimer): dan hoorde hij hem. Vóór het markeren als beluisterd. */
    static void wakkerAf(Context c, String key) {
        try {
            JSONArray l;
            boolean veranderd = false;
            synchronized (LOCK) {
                l = leesRuw(c);
                for (int i = 0; i < l.length(); i++) {
                    JSONObject o = l.optJSONObject(i);
                    // Open, of eerder (vanzelf) teruggezet: nu wakker uitgeluisterd, dus gehoord
                    if (key != null && key.equals(o.optString("key")) && !GEHOORD.equals(o.optString("st"))) { o.put("st", GEHOORD).put("auto", false).put("gezien", true); veranderd = true; }
                }
                if (veranderd) schrijf(c, l);
            }
        } catch (Throwable t) { App.log(c, "PODCAST", "slaaptimer: bijwerken mislukt (" + t.getClass().getSimpleName() + ")"); }
    }

    /** De slaaptimer is klaar (de speler stopte). Bij "vanzelf": wat in deze sessie afliep meteen terugzetten. */
    static void sessieKlaar(Context c, long sessie) {
        try {
            if (!VANZELF.equals(wijze(c)) || sessie <= 0) return;
            List<String> keys;
            synchronized (LOCK) { keys = openVan(leesRuw(c), sessie); }
            if (!keys.isEmpty()) { oordeel(c, keys, TERUG, true); App.log(c, "PODCAST", "slaaptimer: vanzelf teruggezet: " + keys.size()); }
        } catch (Throwable t) { App.log(c, "PODCAST", "slaaptimer: terugzetten mislukt (" + t.getClass().getSimpleName() + ")"); }
    }

    /**
     * Rene's oordeel over deze afleveringen: TERUG (niet beluisterd, terug naar de plek), GEHOORD (klopt; de download mag
     * weg als dat zo is ingesteld) of "gezien" (de melding van vanzelf terugzetten weg). Geeft het aantal regels dat veranderde.
     */
    static int oordeel(Context c, List<String> keys, String st, boolean auto) {
        if (keys == null || keys.isEmpty()) return 0;
        boolean gezien = "gezien".equals(st);
        if (!gezien && !TERUG.equals(st) && !GEHOORD.equals(st)) return 0;
        String plek = plek(c);
        List<String> gehoord = new ArrayList<>();
        int n = 0;
        synchronized (LOCK) {
            JSONArray l = leesRuw(c);
            for (int i = 0; i < l.length(); i++) {
                JSONObject o = l.optJSONObject(i);
                String key = o.optString("key");
                if (!keys.contains(key)) continue;
                try {
                    if (gezien) { o.put("gezien", true); n++; continue; }
                    JSONObject p = Podcasts.progressOf(c, key);
                    boolean nuDone = p == null || p.optBoolean("done");
                    if (TERUG.equals(st)) {
                        // Alleen als hij nog als beluisterd staat: luisterde Rene hem intussen opnieuw, dan die plek houden
                        if (nuDone) Podcasts.saveProgress(c, key, terugPlek(o, plek), o.optLong("d"), false);
                    } else {
                        // Toch gehoord na terugzetten: weer beluisterd. Stond hij nog open en luisterde Rene hem intussen
                        // opnieuw, dan die plek houden
                        if (!nuDone && TERUG.equals(o.optString("st"))) Podcasts.saveProgress(c, key, 0, o.optLong("d"), true);
                        gehoord.add(key);
                    }
                    o.put("st", st).put("auto", auto).put("gezien", !auto);
                    n++;
                } catch (Exception ignored) { }
            }
            if (n > 0) schrijf(c, l);
        }
        downloadsWeg(c, gehoord);
        return n;
    }

    /** Gehoord: de download mag weg (als Beluisterde afleveringen vanzelf verwijderen aan staat). */
    private static void downloadsWeg(Context c, List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        final Context app = c.getApplicationContext();
        final List<String> k = new ArrayList<>(keys);
        Podcasts.IO.execute(() -> {
            try {
                boolean any = false;
                for (String key : k) {
                    JSONObject p = Podcasts.progressOf(app, key);
                    if (p != null && !p.optBoolean("done")) continue; // intussen opnieuw begonnen: de download blijft
                    if (PodcastDownloads.played(app, key)) any = true;
                }
                if (any) MainActivity.jsAll("onPodDl", "");
            } catch (Throwable ignored) { }
        });
    }
}
