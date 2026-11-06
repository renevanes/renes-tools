package nl.rene.tools;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

/** Toiletten en douches: bronnen, zoeken dichtbij, de eigen laag en doorgeven aan OpenStreetMap. */
public class ToilettenBridge extends MasterBridge {
    ToilettenBridge(MainActivity act) { super(act); }

    private static String fout(Throwable e, String fb) {
        try { return new JSONObject().put("error", e.getMessage() == null ? fb : e.getMessage()).toString(); } catch (Exception x) { return "{\"error\":\"" + fb + "\"}"; }
    }

    private void plan() { a.h.post(() -> { try { ToilettenJob.schedule(ctx); } catch (Throwable ignored) { } }); }

    /** Bron-id -> bron (voor namen, licenties en of het OpenStreetMap is). */
    private static JSONObject perId(JSONArray bronnen) throws Exception {
        JSONObject o = new JSONObject();
        for (int i = 0; i < bronnen.length(); i++) o.put(bronnen.getJSONObject(i).getString("id"), bronnen.getJSONObject(i));
        return o;
    }

    @JavascriptInterface public String wcState() {
        try {
            JSONArray bs = Toiletten.bronnen(ctx);
            JSONObject st = Toiletten.toestand(ctx), uit = new JSONObject();
            JSONArray lijst = new JSONArray();
            long laatste = 0;
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = new JSONObject(bs.getJSONObject(i).toString());
                JSONObject t = st.optJSONObject(b.getString("id"));
                b.put("toestand", t == null ? new JSONObject() : t);
                b.put("volgende", "uit".equals(b.optString("schema")) || !b.optBoolean("aan", true) ? 0 : Facturen.gepland(b, System.currentTimeMillis(), true, Regio.zone(ctx)));
                if (t != null) laatste = Math.max(laatste, t.optLong("lastOk"));
                lijst.put(b);
            }
            JSONObject e = Toiletten.eigen(ctx);
            JSONObject g = ToilettenOsm.gedeeld(ctx);
            int open = 0;
            for (Iterator<String> it = g.keys(); it.hasNext(); ) { JSONObject o = g.optJSONObject(it.next()); if (o != null && "open".equals(o.optString("status"))) open++; }
            uit.put("ingesteld", Toiletten.ingesteld(ctx)).put("bezig", ToilettenScan.BEZIG.get()).put("bronnen", lijst).put("laatste", laatste)
                    .put("sjablonen", Toiletten.sjablonen(ctx))
                    .put("eigen", new JSONObject().put("aangepast", e == null ? 0 : e.optJSONObject("wijzig").length())
                            .put("afgewezen", e == null ? 0 : e.optJSONObject("afgewezen").length())
                            .put("toegevoegd", e == null ? 0 : e.optJSONObject("toegevoegd").length()).put("leesbaar", e != null))
                    .put("gedeeld", new JSONObject().put("aantal", g.length()).put("open", open));
            boolean data = false;
            for (int i = 0; i < bs.length(); i++) if (Toiletten.cacheFile(ctx, bs.getJSONObject(i).getString("id")).isFile()) data = true;
            uit.put("gegevens", data);
            return uit.toString();
        } catch (Throwable e) { return fout(e, "Lukt niet"); }
    }

    /** De dichtstbijzijnde plekken (hooguit 25, binnen 50 km) die bij het filter passen. */
    @JavascriptInterface public String wcNear(double lat, double lon, String filters) {
        try {
            JSONObject f = filters == null || filters.isEmpty() ? new JSONObject() : new JSONObject(filters);
            ToilettenSamen.Index ix = ToilettenSamen.index(ctx);
            int max = Math.max(1, Math.min(50, f.optInt("max", 25)));
            List<ToilettenSamen.Treffer> l = ToilettenSamen.zoek(ix, lat, lon, f, max, 50_000);
            JSONArray a = new JSONArray();
            for (ToilettenSamen.Treffer t : l) a.put(ToilettenSamen.kort(t.p, t.d));
            return new JSONObject().put("plekken", a).put("totaal", ToilettenSamen.aantal(ix)).toString();
        } catch (Throwable e) { return fout(e, "Zoeken lukt niet"); }
    }

    @JavascriptInterface public String wcPlek(String k) {
        try {
            ToilettenSamen.Index ix = ToilettenSamen.index(ctx);
            ToilettenSamen.Punt p = ix.opSleutel.get(k);
            if (p == null) return fout(new Exception(Taal.t(ctx, "Deze plek staat er niet meer")), "Lukt niet");
            JSONObject o = ToilettenSamen.kort(p, -1).put("notitie", p.notitie);
            JSONObject bron = perId(Toiletten.bronnen(ctx));
            JSONArray bs = new JSONArray();
            String osm = "";
            for (String key : p.keys) {
                String id = ToilettenSamen.bronVan(key);
                JSONObject b = bron.optJSONObject(id);
                String ext = key.substring(key.indexOf(':') + 1);
                JSONObject x = new JSONObject().put("id", id).put("e", ext);
                if (b != null) {
                    x.put("naam", b.optString("naam")).put("licentie", b.optString("licentie")).put("info", b.optString("info"));
                    if ("tools".equals(b.optString("soort")) && osm.isEmpty()) osm = ToilettenOsm.osmUrl(ext);
                }
                if (!key.startsWith("eigen:")) bs.put(x);
            }
            o.put("bronnen", bs).put("osm", osm);
            if (p.uitBron != null) o.put("uitBron", p.uitBron);
            if (p.bronNu != null) o.put("bronNu", p.bronNu);
            JSONObject g = ToilettenOsm.gedeeld(ctx);
            JSONArray gd = new JSONArray();
            for (String w : ToilettenOsm.WAT) { JSONObject x = g.optJSONObject(k + "|" + w); if (x != null) gd.put(new JSONObject(x.toString()).put("url", ToilettenOsm.notitieUrl(x.optLong("id"))).put("sleutel", k + "|" + w)); }
            o.put("gedeeld", gd);
            JSONObject e = Toiletten.eigen(ctx);
            if (e != null && e.optJSONObject("afgewezen").has(k)) o.put("afwijzing", e.optJSONObject("afgewezen").optJSONObject(k));
            return o.toString();
        } catch (Throwable e) { return fout(e, "Lukt niet"); }
    }

    @JavascriptInterface public String wcBronnen() {
        try { return new JSONObject().put("bronnen", Toiletten.bronnen(ctx)).put("sjablonen", Toiletten.sjablonen(ctx)).toString(); }
        catch (Throwable e) { return fout(e, "Lukt niet"); }
    }

    @JavascriptInterface public String wcBronBewaar(String json) {
        try {
            JSONObject b = Toiletten.bewaarBron(ctx, new JSONObject(json));
            ToilettenSamen.wis();
            plan();
            return b.toString();
        } catch (Throwable e) { return fout(e, "Bewaren lukt niet"); }
    }

    @JavascriptInterface public String wcBronWeg(String id) {
        try { Toiletten.verwijderBron(ctx, id); plan(); return ""; } catch (Throwable e) { return fout(e, "Verwijderen lukt niet"); }
    }

    /** Nu scannen (id leeg = alle bronnen die aan staan); resultaat via onWcScan. */
    @JavascriptInterface public String wcScan(String id) {
        if (ToilettenScan.BEZIG.get()) return Taal.t(ctx, "Er loopt al een scan");
        new Thread(() -> {
            String r;
            try {
                JSONArray l = ToilettenScan.scan(ctx, id == null ? "" : id, false);
                r = l == null ? new JSONObject().put("bezig", true).toString() : new JSONObject().put("id", id == null ? "" : id).put("resultaat", l).toString();
            } catch (Throwable e) { r = fout(e, "Scannen lukt niet"); }
            a.js("onWcScan", r);
            plan();
        }, "toiletten-scan").start();
        return "";
    }

    // ================= eigen laag =================

    private JSONObject eigenOfFout() throws Exception {
        JSONObject e = Toiletten.eigen(ctx);
        if (e == null) throw new Exception(Taal.t(ctx, "Je eigen wijzigingen zijn niet leesbaar; er is niets overschreven"));
        return e;
    }

    /** Wat een bron zegt over een plek (zonder jouw bewerking), om "was" te bewaren. */
    private static JSONObject bronWaarden(ToilettenSamen.Punt p) throws Exception {
        JSONObject o = p.uitBron != null ? new JSONObject(p.uitBron.toString()) : ToilettenSamen.waarden(p);
        return o.put("la", p.la).put("lo", p.lo);
    }

    @JavascriptInterface public String wcBewaar(String json) {
        try {
            JSONObject in = new JSONObject(json);
            String k;
            synchronized (Toiletten.LOCK) {
                JSONObject e = eigenOfFout();
                String key = in.optString("k", "");
                JSONObject bron = null;
                if (!key.isEmpty() && !key.startsWith("eigen:")) {
                    ToilettenSamen.Punt p = ToilettenSamen.index(ctx).opSleutel.get(key);
                    if (p != null) { bron = bronWaarden(p); if (!p.wijzigSleutel.isEmpty()) in.put("k", p.wijzigSleutel); }
                }
                k = Toiletten.bewaarPlek(e, in, bron, System.currentTimeMillis());
                Toiletten.bewaarEigen(ctx, e);
            }
            App.log(ctx, "TOILET", "eigen wijziging bewaard");
            return new JSONObject().put("k", k).toString();
        } catch (Throwable e) { return fout(e, "Bewaren lukt niet"); }
    }

    @JavascriptInterface public String wcAfwijzen(String k, String reden, String tekst) {
        try {
            synchronized (Toiletten.LOCK) {
                JSONObject e = eigenOfFout();
                ToilettenSamen.Punt p = ToilettenSamen.index(ctx).opSleutel.get(k);
                Toiletten.afwijzen(e, p != null && !p.wijzigSleutel.isEmpty() ? p.wijzigSleutel : k, reden, tekst, p == null ? null : bronWaarden(p), System.currentTimeMillis());
                Toiletten.bewaarEigen(ctx, e);
            }
            App.log(ctx, "TOILET", "plek afgewezen");
            return "";
        } catch (Throwable e) { return fout(e, "Lukt niet"); }
    }

    /** wat: wijzig (bewerking vergeten = de bron overnemen), afgewezen (terugzetten) of toegevoegd (eigen plek weghalen). */
    @JavascriptInterface public String wcTerug(String k, String wat) {
        try {
            synchronized (Toiletten.LOCK) {
                JSONObject e = eigenOfFout();
                ToilettenSamen.Punt p = ToilettenSamen.index(ctx).opSleutel.get(k);
                String key = "wijzig".equals(wat) && p != null && !p.wijzigSleutel.isEmpty() ? p.wijzigSleutel : k;
                if (!Toiletten.terug(e, key, wat)) return "";
                Toiletten.bewaarEigen(ctx, e);
            }
            return "";
        } catch (Throwable e) { return fout(e, "Lukt niet"); }
    }

    @JavascriptInterface public String wcEigen() {
        try {
            JSONObject e = eigenOfFout();
            ToilettenSamen.Index ix = ToilettenSamen.index(ctx);
            JSONArray aangepast = new JSONArray(), toegevoegd = new JSONArray(), afgewezen = new JSONArray();
            for (ToilettenSamen.Punt p : ix.punten) {
                if ("aangepast".equals(p.eigen)) aangepast.put(ToilettenSamen.kort(p, -1).put("t", e.optJSONObject("wijzig").optJSONObject(p.wijzigSleutel) == null ? 0 : e.optJSONObject("wijzig").optJSONObject(p.wijzigSleutel).optLong("t")));
                else if ("toegevoegd".equals(p.eigen)) toegevoegd.put(ToilettenSamen.kort(p, -1).put("t", e.optJSONObject("toegevoegd").optJSONObject(p.k) == null ? 0 : e.optJSONObject("toegevoegd").optJSONObject(p.k).optLong("t")));
            }
            for (JSONObject a2 : ix.afgewezen) afgewezen.put(a2);
            JSONArray gd = new JSONArray();
            JSONObject g = ToilettenOsm.gedeeld(ctx);
            for (Iterator<String> it = g.keys(); it.hasNext(); ) {
                String s = it.next();
                JSONObject x = g.optJSONObject(s);
                if (x != null) gd.put(new JSONObject(x.toString()).put("sleutel", s).put("url", ToilettenOsm.notitieUrl(x.optLong("id"))));
            }
            return new JSONObject().put("aangepast", aangepast).put("afgewezen", afgewezen).put("toegevoegd", toegevoegd).put("gedeeld", gd).toString();
        } catch (Throwable e) { return fout(e, "Lukt niet"); }
    }

    // ================= gemeenschap (OpenStreetMap-notities) =================

    /** De notitie zoals hij naar OpenStreetMap gaat (Rene ziet hem eerst): {tekst, la, lo, wat} of {error}. */
    private JSONObject notitie(String k, String wat) throws Exception {
        JSONObject e = eigenOfFout();
        ToilettenSamen.Index ix = ToilettenSamen.index(ctx);
        ToilettenSamen.Punt p = ix.opSleutel.get(k);
        JSONObject bron = perId(Toiletten.bronnen(ctx));
        String osm = "";
        double la, lo;
        if (p != null) for (String key : p.keys) {
            JSONObject b = bron.optJSONObject(ToilettenSamen.bronVan(key));
            if (b != null && "tools".equals(b.optString("soort"))) { osm = ToilettenOsm.osmUrl(key.substring(key.indexOf(':') + 1)); if (!osm.isEmpty()) break; }
        }
        String tekst;
        if ("afwijzing".equals(wat)) {
            JSONObject af = e.optJSONObject("afgewezen").optJSONObject(p != null && !p.wijzigSleutel.isEmpty() ? p.wijzigSleutel : k);
            if (af == null) throw new Exception(Taal.t(ctx, "Wijs de plek eerst af"));
            if (osm.isEmpty()) throw new Exception(Taal.t(ctx, "Deze plek staat niet in OpenStreetMap"));
            la = af.optDouble("la"); lo = af.optDouble("lo");
            tekst = ToilettenOsm.tekst(wat, new JSONObject().put("s", af.optString("s")).put("f", p == null ? 0 : p.f), null, osm, af.optString("reden"), af.optString("tekst"));
        } else {
            if (p == null) throw new Exception(Taal.t(ctx, "Deze plek staat er niet meer"));
            la = p.la; lo = p.lo;
            JSONObject nu = ToilettenSamen.waarden(p);
            if ("wijziging".equals(wat)) {
                if (!"aangepast".equals(p.eigen)) throw new Exception(Taal.t(ctx, "Pas de plek eerst aan"));
                if (osm.isEmpty()) wat = "toevoeging";
            } else if (!"toegevoegd".equals(p.eigen)) throw new Exception(Taal.t(ctx, "Alleen je eigen plekken"));
            tekst = ToilettenOsm.tekst(wat, nu, p.uitBron, osm, "", p.notitie);
        }
        return new JSONObject().put("tekst", tekst).put("la", la).put("lo", lo).put("wat", wat).put("osm", osm);
    }

    @JavascriptInterface public String wcNotitie(String k, String wat) {
        try { return notitie(k, wat).toString(); } catch (Throwable e) { return fout(e, "Lukt niet"); }
    }

    /** Na bevestiging: de notitie maken (resultaat via onWcGedeeld). In een moderatiezone gaat de tekst naar het klembord. */
    @JavascriptInterface public String wcDoorgeven(String k, String wat) {
        final JSONObject n;
        try { n = notitie(k, wat); } catch (Throwable e) { return fout(e, "Lukt niet"); }
        new Thread(() -> {
            String r;
            try {
                JSONObject o = ToilettenOsm.deel(ctx, k, n.optString("wat"), n.optDouble("la"), n.optDouble("lo"), n.optString("tekst"));
                if (o.optBoolean("zone")) {
                    String t = n.optString("tekst");
                    a.h.post(() -> {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("OpenStreetMap", t));
                        } catch (Throwable ignored) { }
                    });
                }
                r = o.put("k", k).toString();
            } catch (Throwable e) { r = fout(e, "Doorgeven lukt niet"); }
            a.js("onWcGedeeld", r);
        }, "toiletten-osm").start();
        return "";
    }

    @JavascriptInterface public String wcVergeet(String sleutel) {
        try { ToilettenOsm.vergeet(ctx, sleutel); return ""; } catch (Throwable e) { return fout(e, "Lukt niet"); }
    }
}
