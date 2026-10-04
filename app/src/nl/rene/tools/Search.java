package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Overal zoeken vanaf het startscherm: contacten, sms, WhatsApp-chats, oproepen, uitgeschreven gesprekken
 * en herkende muziek (notities zoekt de interface zelf). Per soort de eerste paar treffers en het totaal.
 * Een nieuwere zoekopdracht maakt een lopende overbodig: die stopt dan tussen twee bronnen.
 */
final class Search {

    private Search() { }

    static final int PER = 5;
    static final java.util.concurrent.atomic.AtomicInteger latest = new java.util.concurrent.atomic.AtomicInteger();

    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    interface Sink { void part(JSONObject r); }

    /** Eén zoekopdracht tegelijk; opdrachten in de wachtrij die al achterhaald zijn, worden overgeslagen. */
    static final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Bronnen van snel naar traag; na elke bron gaat er een tussenstand naar de interface. */
    static final int[] ORDER = {0, 5, 4, 3, 1, 2};

    static void run(Context c, String q, int id, Sink out) {
        String ql = q.trim().toLowerCase(Locale.ROOT);
        if (ql.length() < 2) return;
        for (int i : ORDER) {
            if (latest.get() != id) return;
            JSONObject res = new JSONObject();
            try {
                res.put("id", id).put("q", q);
                try { source(c, i, q.trim(), ql, res); }
                catch (Throwable e) { App.log(c, "SEARCH", "bron " + i + ": " + e); }
                if (i == ORDER[ORDER.length - 1]) res.put("done", true);
            } catch (Exception ignored) { }
            if (latest.get() != id) return;
            if (res.length() > 2 || res.has("done")) out.part(res);
        }
    }

    static void group(JSONObject res, String kind, JSONArray items, int total) throws Exception {
        group(res, kind, items, total, Integer.MAX_VALUE);
    }

    /** cap: de bron stopt bij zoveel treffers; dan is het totaal "minstens". */
    static void group(JSONObject res, String kind, JSONArray items, int total, int cap) throws Exception {
        if (total > 0) res.put(kind, new JSONObject().put("items", items).put("total", total).put("capped", total >= cap));
    }

    static void source(Context c, int i, String q, String ql, JSONObject res) throws Exception {
        switch (i) {
            case 0: { // contacten
                if (!has(c, Manifest.permission.READ_CONTACTS)) return;
                JSONArray all = new JSONObject(Contacts.listJson(c, q)).optJSONArray("contacts");
                if (all == null) return;
                JSONArray a = new JSONArray();
                for (int k = 0; k < all.length() && k < PER; k++) a.put(all.getJSONObject(k));
                group(res, "contacts", a, all.length());
                return;
            }
            case 1: { // sms
                if (!has(c, Manifest.permission.READ_SMS)) return;
                JSONArray all = new JSONArray(Sms.searchJson(c, q));
                group(res, "sms", first(all), all.length(), 200);
                return;
            }
            case 2: { // WhatsApp (alleen als de chats leesbaar gemaakt zijn)
                String s;
                try { s = WaChats.searchJson(c, q); } catch (Exception e) { return; }
                if (!s.startsWith("[")) return;
                JSONArray all = new JSONArray(s);
                group(res, "wa", first(all), all.length(), 200);
                return;
            }
            case 3: { // oproepen
                if (!has(c, Manifest.permission.READ_CALL_LOG)) return;
                JSONObject o = new JSONObject(Calls.listJson(c, new JSONObject().put("q", q).toString(), PER));
                group(res, "calls", o.optJSONArray("calls"), o.optInt("count"));
                return;
            }
            case 4: { // gesprekken
                JSONArray hits = new JSONObject(Transcribe.searchJson(c, q)).optJSONArray("results");
                if (hits == null) return;
                // Per gesprek één treffer (de eerste plek), geteld in gesprekken.
                JSONArray all = new JSONArray();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int k = 0; k < hits.length(); k++) if (seen.add(hits.getJSONObject(k).optString("id"))) all.put(hits.getJSONObject(k));
                group(res, "tx", first(all), all.length(), hits.length() >= 300 ? all.length() : Integer.MAX_VALUE);
                return;
            }
            default: { // muziek
                JSONArray h = Music.history(c), a = new JSONArray();
                int n = 0;
                for (int k = 0; k < h.length(); k++) {
                    JSONObject r = h.getJSONObject(k);
                    String hay = (r.optString("title") + " " + r.optString("artist") + " " + r.optString("album")).toLowerCase(Locale.ROOT);
                    if (!hay.contains(ql)) continue;
                    if (n++ < PER) a.put(r);
                }
                group(res, "music", a, n);
            }
        }
    }

    static JSONArray first(JSONArray all) throws Exception {
        JSONArray a = new JSONArray();
        for (int k = 0; k < all.length() && k < PER; k++) a.put(all.get(k));
        return a;
    }
}
