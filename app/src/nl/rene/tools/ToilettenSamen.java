package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Toiletten en douches samenvoegen en zoeken (zonder Android: getest in ToilettenSamenTest).
 * Dezelfde plek uit twee bronnen (zelfde groep, hooguit 25 m uit elkaar) wordt één plek; de eerste bron in de lijst gaat
 * voor. Daarna de eigen laag: afgewezen plekken weg, bewerkingen eroverheen, eigen toevoegingen erbij. Zoeken dichtbij
 * gaat via een raster van 0,01 graad (ongeveer 1 km), ring voor ring.
 */
final class ToilettenSamen {
    private ToilettenSamen() { }

    static final double SAMEN_M = 25;
    private static final double CEL = 0.01;

    /** Eén plek zoals een bron hem geeft. */
    static final class Plek {
        final String e, s, n, u;
        final double la, lo;
        final int f;
        Plek(String e, double la, double lo, String s, int f, String n, String u) {
            this.e = e; this.la = la; this.lo = lo; this.s = s; this.f = f; this.n = n == null ? "" : n; this.u = u == null ? "" : u;
        }
        boolean anders(Plek o) {
            return Math.abs(la - o.la) > 2e-5 || Math.abs(lo - o.lo) > 2e-5 || !s.equals(o.s) || f != o.f || !n.equals(o.n) || !u.equals(o.u);
        }
    }

    /** Een bron met zijn plekken (volgorde = voorrang). */
    static final class Bron {
        final String id, naam;
        final List<Plek> plekken;
        Bron(String id, String naam, List<Plek> plekken) { this.id = id; this.naam = naam; this.plekken = plekken; }
    }

    /** Een plek na samenvoegen en de eigen laag. */
    static final class Punt {
        String k, s, n, u, notitie = "", eigen = "", inBron = "", wijzigSleutel = "", afSleutel = "";
        double la, lo;
        int f;
        boolean afgewezen, weg;
        JSONObject bronNu, uitBron;
        final List<String> keys = new ArrayList<>(), bronnen = new ArrayList<>();
        String groep() { return Toiletten.groep(s, f); }
    }

    static final class Index {
        final List<Punt> punten = new ArrayList<>();
        final Map<Long, List<Punt>> raster = new HashMap<>();
        final Map<String, Punt> opSleutel = new HashMap<>();
        final List<JSONObject> afgewezen = new ArrayList<>();
        void voegToe(Punt p) {
            punten.add(p);
            List<Punt> l = raster.get(cel(p.la, p.lo));
            if (l == null) { l = new ArrayList<>(); raster.put(cel(p.la, p.lo), l); }
            l.add(p);
            for (String k : p.keys) opSleutel.put(k, p);
        }
        Punt dichtbij(double la, double lo, String groep, String nietBron, String welBron, boolean ookAfgewezen) {
            Punt best = null;
            double bd = SAMEN_M;
            long cy = (long) Math.floor(la / CEL), cx = (long) Math.floor(lo / CEL);
            for (long dy = -1; dy <= 1; dy++) for (long dx = -1; dx <= 1; dx++) {
                List<Punt> l = raster.get(sleutel(cy + dy, cx + dx));
                if (l == null) continue;
                for (Punt p : l) {
                    if ((p.afgewezen && !ookAfgewezen) || !p.groep().equals(groep)) continue;
                    if (nietBron != null && p.bronnen.contains(nietBron)) continue;
                    if (welBron != null && !p.bronnen.contains(welBron)) continue;
                    double d = meters(la, lo, p.la, p.lo);
                    if (d <= bd) { bd = d; best = p; }
                }
            }
            return best;
        }
    }

    private static volatile Index cache;
    private static final Object BOUW = new Object();
    // Gewist tijdens het bouwen: dan is wat er gebouwd werd al oud en wordt het niet bewaard
    private static final java.util.concurrent.atomic.AtomicLong GENERATIE = new java.util.concurrent.atomic.AtomicLong();

    static void wis() { GENERATIE.incrementAndGet(); cache = null; }

    static long sleutel(long cy, long cx) { return (cy + 9000) * 100_000L + (cx + 18000); }
    static long cel(double la, double lo) { return sleutel((long) Math.floor(la / CEL), (long) Math.floor(lo / CEL)); }

    static double meters(double la1, double lo1, double la2, double lo2) {
        double r = 6_371_000, p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Eigenschappen samenvoegen: de eerste bron gaat voor, de tweede vult aan wat ontbreekt. */
    static int samenBits(int a, int b) {
        int f = a | (b & (Toiletten.TOILET | Toiletten.DOUCHE | Toiletten.ROLSTOEL | Toiletten.VERSCHOON | Toiletten.ALTIJD));
        if ((a & (Toiletten.GRATIS | Toiletten.BETAALD)) == 0) f |= b & (Toiletten.GRATIS | Toiletten.BETAALD);
        if ((b & Toiletten.MEESTAL) == 0) f &= ~Toiletten.MEESTAL;
        if ((f & Toiletten.ROLSTOEL) != 0) f &= ~Toiletten.ROLSTOEL_BEPERKT;
        return f;
    }

    static JSONObject waarden(Punt p) {
        try { return new JSONObject().put("s", p.s).put("f", p.f).put("n", p.n).put("u", p.u); } catch (Exception e) { return new JSONObject(); }
    }

    static String bronVan(String sleutel) { int i = sleutel.indexOf(':'); return i > 0 ? sleutel.substring(0, i) : ""; }

    /** Plek van een sleutel uit de eigen laag: dezelfde sleutel, of (heeft de bron nieuwe id's) dezelfde groep binnen 25 m in die bron. */
    static Punt vind(Index ix, String k, JSONObject o) {
        Punt p = ix.opSleutel.get(k);
        if (p != null) return p;
        if (!o.has("la") || !o.has("lo")) return null;
        JSONObject was = o.optJSONObject("was");
        int f = was != null ? was.optInt("f") : o.optInt("f");
        return ix.dichtbij(o.optDouble("la"), o.optDouble("lo"), Toiletten.groep(o.optString("s"), f), null, bronVan(k), true);
    }

    static Index bouw(List<Bron> bronnen, JSONObject eigen) {
        Index ix = new Index();
        for (Bron b : bronnen) {
            for (Plek p : b.plekken) {
                String k = b.id + ":" + p.e;
                if (ix.opSleutel.containsKey(k)) continue;
                Punt q = ix.dichtbij(p.la, p.lo, Toiletten.groep(p.s, p.f), b.id, null, false);
                if (q != null) {
                    q.keys.add(k); q.bronnen.add(b.id);
                    q.f = samenBits(q.f, p.f);
                    if (q.n.isEmpty()) q.n = p.n;
                    if (q.u.isEmpty()) q.u = p.u;
                    ix.opSleutel.put(k, q);
                    continue;
                }
                Punt n = new Punt();
                n.k = k; n.la = p.la; n.lo = p.lo; n.s = p.s; n.f = p.f; n.n = p.n; n.u = p.u;
                n.keys.add(k); n.bronnen.add(b.id);
                ix.voegToe(n);
            }
        }
        if (eigen == null) return ix;
        JSONObject af = eigen.optJSONObject("afgewezen"), wz = eigen.optJSONObject("wijzig"), tv = eigen.optJSONObject("toegevoegd");
        for (Iterator<String> it = af == null ? Collections.<String>emptyIterator() : af.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject a = af.optJSONObject(k);
            if (a == null) continue;
            Punt p = vind(ix, k, a);
            if (p != null) { p.afgewezen = true; p.afSleutel = k; }
            try { ix.afgewezen.add(new JSONObject(a.toString()).put("k", k).put("gevonden", p != null)); } catch (Exception ignored) { }
        }
        for (Iterator<String> it = wz == null ? Collections.<String>emptyIterator() : wz.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject w = wz.optJSONObject(k), v = w == null ? null : w.optJSONObject("v");
            if (v == null) continue;
            Punt p = vind(ix, k, w);
            if (p == null) {
                p = new Punt();
                p.k = k; p.la = w.optDouble("la"); p.lo = w.optDouble("lo"); p.weg = true;
                p.keys.add(k); p.bronnen.add(bronVan(k));
                p.s = w.optString("s"); p.f = v.optInt("f"); p.n = ""; p.u = "";
                ix.voegToe(p);
            }
            JSONObject nu = waarden(p), was = w.optJSONObject("was");
            if (!p.weg && was != null && !Toiletten.zelfde(nu, was)) p.bronNu = nu;
            p.uitBron = p.weg ? was : nu;
            p.s = v.optString("s", p.s); p.f = v.optInt("f", p.f); p.n = v.optString("n"); p.u = v.optString("u");
            p.notitie = v.optString("notitie"); p.eigen = "aangepast"; p.wijzigSleutel = k;
        }
        List<Punt> eigenPunten = new ArrayList<>();
        for (Iterator<String> it = tv == null ? Collections.<String>emptyIterator() : tv.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject t = tv.optJSONObject(k);
            if (t == null || !t.has("la") || !t.has("lo")) continue;
            Punt p = new Punt();
            p.k = k; p.la = t.optDouble("la"); p.lo = t.optDouble("lo"); p.s = t.optString("s", "toilet"); p.f = t.optInt("f", Toiletten.TOILET);
            p.n = t.optString("n"); p.u = t.optString("u"); p.notitie = t.optString("notitie"); p.eigen = "toegevoegd";
            p.keys.add(k);
            Punt b = ix.dichtbij(p.la, p.lo, p.groep(), null, null, false);
            if (b != null) p.inBron = b.k;
            eigenPunten.add(p);
        }
        for (Punt p : eigenPunten) ix.voegToe(p);
        return ix;
    }

    static boolean past(Punt p, JSONObject filter) {
        if (p.afgewezen) return false;
        if (!filter.optBoolean("meestal") && (p.f & Toiletten.MEESTAL) != 0) return false;
        if (filter.optBoolean("toilet") && (p.f & Toiletten.TOILET) == 0) return false;
        if (filter.optBoolean("douche") && (p.f & Toiletten.DOUCHE) == 0) return false;
        if (filter.optBoolean("gratis") && (p.f & Toiletten.GRATIS) == 0) return false;
        return !filter.optBoolean("rolstoel") || (p.f & Toiletten.ROLSTOEL) != 0;
    }

    static final class Treffer {
        final Punt p;
        final double d;
        Treffer(Punt p, double d) { this.p = p; this.d = d; }
    }

    /** De dichtstbijzijnde plekken die bij het filter passen, ring voor ring (hooguit straal meter ver). */
    static List<Treffer> zoek(Index ix, double la, double lo, JSONObject filter, int max, double straal) {
        List<Treffer> uit = new ArrayList<>();
        long cy = (long) Math.floor(la / CEL), cx = (long) Math.floor(lo / CEL);
        double celM = Math.min(111_320 * CEL, 111_320 * CEL * Math.cos(Math.toRadians(la)));
        int maxRing = (int) Math.ceil(straal / Math.max(100, celM)) + 1;
        for (int r = 0; r <= maxRing; r++) {
            for (long dy = -r; dy <= r; dy++) for (long dx = -r; dx <= r; dx++) {
                if (Math.max(Math.abs(dy), Math.abs(dx)) != r) continue;
                List<Punt> l = ix.raster.get(sleutel(cy + dy, cx + dx));
                if (l == null) continue;
                for (Punt p : l) {
                    if (!past(p, filter)) continue;
                    double d = meters(la, lo, p.la, p.lo);
                    if (d <= straal) uit.add(new Treffer(p, d));
                }
            }
            if (uit.size() >= max) {
                Collections.sort(uit, (a, b) -> Double.compare(a.d, b.d));
                if (uit.get(max - 1).d <= r * celM) break;
            }
        }
        Collections.sort(uit, (a, b) -> Double.compare(a.d, b.d));
        return uit.size() > max ? new ArrayList<>(uit.subList(0, max)) : uit;
    }

    static JSONObject kort(Punt p, double d) throws Exception {
        JSONObject o = new JSONObject().put("k", p.k).put("la", p.la).put("lo", p.lo).put("s", p.s).put("f", p.f).put("n", p.n).put("u", p.u);
        if (d >= 0) o.put("d", Math.round(d));
        o.put("b", new JSONArray(p.bronnen));
        if (!p.eigen.isEmpty()) o.put("e", p.eigen);
        if (p.bronNu != null) o.put("nu", true);
        if (!p.inBron.isEmpty()) o.put("ib", p.inBron);
        if (p.weg) o.put("weg", true);
        if (p.afgewezen) o.put("af", true);
        return o;
    }

    // ================= met Android =================

    static Index index(Context c) {
        Index ix = cache;
        if (ix != null) return ix;
        synchronized (BOUW) {
            if (cache != null) return cache;
            long gen = GENERATIE.get();
            List<Bron> lijst = new ArrayList<>();
            JSONArray bs = Toiletten.bronnen(c);
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.optJSONObject(i);
                if (b == null || !b.optBoolean("aan", true)) continue;
                lijst.add(new Bron(b.optString("id"), b.optString("naam"), ToilettenScan.leesCache(Toiletten.cacheFile(c, b.optString("id")))));
            }
            JSONObject e = Toiletten.eigen(c);
            Index nieuw = bouw(lijst, e);
            if (GENERATIE.get() == gen) cache = nieuw;
            return nieuw;
        }
    }

    static int aantal(Index ix) {
        int n = 0;
        for (Punt p : ix.punten) if (!p.afgewezen) n++;
        return n;
    }
}
