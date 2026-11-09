package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Bronnen van toiletten en douches ophalen en lezen. Soorten: "tools" (de wekelijkse OpenStreetMap-momentopname met
 * meta.json en SHA-256), "geojson", "arcgis" (een ArcGIS-laag als GeoJSON) en "csv". Alleen https, hooguit 30 MB,
 * met een eigen User-Agent en nooit met je locatie. Mislukt het, dan blijft de vorige goede versie staan.
 */
final class ToilettenScan {
    private ToilettenScan() { }

    static final long MAX = 30L * 1024 * 1024, MAX_UIT = 40L * 1024 * 1024;
    static final int MAX_PLEKKEN = 100_000, MAX_PAGINAS = 50;

    /** De fout zoals hij bewaard wordt (Nederlands; de brug vertaalt hem bij het tonen). */
    static String kort(Throwable e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException)
            return Taal.T("Geen verbinding");
        return Facturen.kort(e);
    }
    static final String UA = "renes-tools-toiletten/1 (Android; +https://github.com/renevanes/renes-tools)";
    static final AtomicBoolean BEZIG = new AtomicBoolean();
    private static final Pattern GETAL = Pattern.compile("(\\d+)(?:[.,](\\d+))?");
    private static final Map<String, Pattern> PATRONEN = new java.util.concurrent.ConcurrentHashMap<>();

    /** Zoekpatroon van een regel, één keer gecompileerd (een scan gaat over tienduizenden plekken). */
    static boolean past(String patroon, String w) {
        Pattern p = PATRONEN.get(patroon);
        if (p == null) {
            p = Pattern.compile(patroon, Pattern.CASE_INSENSITIVE);
            if (PATRONEN.size() > 200) PATRONEN.clear();
            PATRONEN.put(patroon, p);
        }
        return p.matcher(w.length() > Toiletten.VELD_MAX ? w.substring(0, Toiletten.VELD_MAX) : w).find();
    }

    // ================= lezen (zonder Android: getest in ToilettenTest) =================

    /** Een plek controleren: binnen bereik, bekende soort, geen vreemde bits, teksten kort. Null = overslaan. */
    static ToilettenSamen.Plek plek(String e, double la, double lo, String s, int f, String n, String u) {
        if (e == null || e.isEmpty() || e.length() > 80 || Double.isNaN(la) || Double.isNaN(lo)) return null;
        if (la < -90 || la > 90 || lo < -180 || lo > 180 || (la == 0 && lo == 0)) return null;
        if (!Facturen.in(s, Toiletten.SOORTEN)) s = "overig";
        f &= Toiletten.ALLE_BITS;
        if ((f & (Toiletten.TOILET | Toiletten.DOUCHE)) == 0) f |= "douche".equals(s) ? Toiletten.DOUCHE : Toiletten.TOILET;
        return new ToilettenSamen.Plek(e.replaceAll("\\s+", "_"), Math.round(la * 1e6) / 1e6, Math.round(lo * 1e6) / 1e6, s, f,
                Toiletten.kort(n, 80), Toiletten.kort(u, Toiletten.MAX_TEKST));
    }

    /** De momentopname van Tools (tools/toiletten/ophalen.py). */
    static List<ToilettenSamen.Plek> leesTools(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        if (o.optInt("formaat", 0) > Toiletten.FORMAAT) throw new Exception(Taal.T("Nieuwer formaat: werk de app bij"));
        JSONArray a = o.optJSONArray("plekken");
        if (a == null) throw new Exception(Taal.T("Geen plekken in het bestand"));
        if (a.length() > MAX_PLEKKEN) throw new Exception(Taal.T("Te veel plekken"));
        List<ToilettenSamen.Plek> uit = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONArray r = a.optJSONArray(i);
            if (r == null) continue;
            ToilettenSamen.Plek p = plek(r.optString(0), r.optDouble(1, Double.NaN), r.optDouble(2, Double.NaN), r.optString(3), r.optInt(4), r.optString(5), r.optString(6));
            if (p != null) uit.add(p);
        }
        return uit;
    }

    /** Een veld uit de eigenschappen; "A|B" = het eerste dat er is (ook als de hoofdletters anders zijn). */
    static String veld(JSONObject props, String spec) {
        if (props == null || spec == null || spec.isEmpty()) return "";
        for (String k : spec.split("\\|")) {
            k = k.trim();
            if (k.isEmpty()) continue;
            Object v = props.opt(k);
            if (v == null) {
                for (Iterator<String> it = props.keys(); it.hasNext(); ) { String x = it.next(); if (x.equalsIgnoreCase(k)) { v = props.opt(x); break; } }
            }
            if (v == null || v == JSONObject.NULL || v instanceof JSONObject || v instanceof JSONArray) continue;
            String s = v instanceof Double && ((Double) v) == Math.rint((Double) v) ? String.valueOf(((Double) v).longValue()) : String.valueOf(v);
            s = s.trim();
            if (!s.isEmpty()) return s.length() > 300 ? s.substring(0, 300) : s;
        }
        return "";
    }

    /** ja/nee in de vormen die gemeenten gebruiken; null = onbekend. */
    static Boolean jaNee(String v) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        if (s.matches("ja|yes|true|1|j|y|aanwezig|wel")) return Boolean.TRUE;
        if (s.matches("nee|no|false|0|n|geen|niet aanwezig|niet")) return Boolean.FALSE;
        return null;
    }

    /** Prijs ("€ 0,50", "0,00 euro", "gratis", 0): true = gratis, false = betaald, null = onbekend. */
    static Boolean gratisPrijs(String v) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.contains("gratis") || s.contains("free")) return Boolean.TRUE;
        Matcher m = GETAL.matcher(s);
        if (!m.find()) return null;
        double d = Double.parseDouble(m.group(1) + (m.group(2) == null ? "" : "." + m.group(2)));
        return d == 0;
    }

    /** Eén plek uit een GeoJSON-, ArcGIS- of CSV-bron, via de veldkoppeling en regels van de bron. Null = overslaan. */
    static ToilettenSamen.Plek uitVelden(JSONObject props, double la, double lo, JSONObject bron, String reserveId) {
        JSONObject v = bron.optJSONObject("velden");
        if (v == null) v = new JSONObject();
        String soort = bron.optString("plek", "toilet");
        int f = "douche".equals(soort) ? Toiletten.DOUCHE : Toiletten.TOILET;
        JSONArray ex = bron.optJSONArray("extra");
        for (int i = 0; ex != null && i < ex.length(); i++) f |= Toiletten.bit(ex.optString(i));
        if (Boolean.TRUE.equals(jaNee(veld(props, v.optString("rolstoel"))))) f |= Toiletten.ROLSTOEL;
        if (Boolean.TRUE.equals(jaNee(veld(props, v.optString("verschoon"))))) f |= Toiletten.VERSCHOON;
        if (Boolean.TRUE.equals(jaNee(veld(props, v.optString("douche"))))) f |= Toiletten.DOUCHE;
        Boolean g = jaNee(veld(props, v.optString("gratis")));
        if (g == null) g = gratisPrijs(veld(props, v.optString("prijs")));
        if (g != null) f |= g ? Toiletten.GRATIS : Toiletten.BETAALD;
        String u = veld(props, v.optString("uren"));
        if (u.matches("(?i)\\s*(24/7|24 uur.*|altijd( open)?|dag en nacht)\\s*")) f |= Toiletten.ALTIJD;
        JSONArray rg = bron.optJSONArray("regels");
        for (int i = 0; rg != null && i < rg.length(); i++) {
            JSONObject r = rg.optJSONObject(i);
            if (r == null) continue;
            String w = veld(props, r.optString("veld"));
            if (w.isEmpty()) continue;
            boolean past;
            try { past = past(r.optString("patroon"), w); }
            catch (Exception e) { continue; }
            if (!past) continue;
            String zet = r.optString("zet");
            if ("overslaan".equals(zet)) return null;
            int b = Toiletten.bit(zet);
            if (b == Toiletten.GRATIS) f &= ~Toiletten.BETAALD;
            if (b == Toiletten.BETAALD) f &= ~Toiletten.GRATIS;
            f |= b;
        }
        String id = veld(props, v.optString("id"));
        if (id.isEmpty()) id = reserveId;
        if (id == null || id.isEmpty()) id = String.format(Locale.ROOT, "%.5f,%.5f", la, lo);
        return plek(id, la, lo, soort, f, veld(props, v.optString("naam")), u);
    }

    /** Midden van een GeoJSON-geometrie (punt, of het gemiddelde van de buitenrand). */
    static double[] midden(JSONObject g) {
        if (g == null) return null;
        if (g.has("x") && g.has("y")) return new double[]{g.optDouble("y"), g.optDouble("x")}; // ArcGIS (f=json)
        JSONArray c = g.optJSONArray("coordinates");
        String t = g.optString("type");
        if (c == null) return null;
        JSONArray pts;
        if ("Point".equals(t)) return new double[]{c.optDouble(1, Double.NaN), c.optDouble(0, Double.NaN)};
        else if ("MultiPoint".equals(t) || "LineString".equals(t)) pts = c;
        else if ("Polygon".equals(t)) pts = c.optJSONArray(0);
        else if ("MultiPolygon".equals(t)) { JSONArray p = c.optJSONArray(0); pts = p == null ? null : p.optJSONArray(0); }
        else return null;
        if (pts == null || pts.length() == 0) return null;
        double la = 0, lo = 0;
        int n = 0;
        for (int i = 0; i < pts.length(); i++) { JSONArray p = pts.optJSONArray(i); if (p == null) continue; lo += p.optDouble(0); la += p.optDouble(1); n++; }
        return n == 0 ? null : new double[]{la / n, lo / n};
    }

    /** GeoJSON (ook ArcGIS met f=geojson of f=json) of een JSON-lijst met records (Opendatasoft "exports/json"). */
    static List<ToilettenSamen.Plek> leesGeojson(String json, JSONObject bron) throws Exception {
        List<ToilettenSamen.Plek> uit = new ArrayList<>();
        String t = json.trim();
        JSONArray fs;
        boolean records = t.startsWith("[");
        if (records) fs = new JSONArray(t);
        else {
            JSONObject o = new JSONObject(t);
            if (o.has("error")) throw new Exception(Taal.fill(Taal.T("De bron geeft een fout: {fout}"), "fout", Toiletten.kort(o.optJSONObject("error") != null ? o.optJSONObject("error").optString("message") : o.optString("error"), 100)));
            fs = o.optJSONArray("features");
            if (fs == null) throw new Exception(Taal.T("Geen GeoJSON (er staat geen \"features\" in)"));
        }
        if (fs.length() > MAX_PLEKKEN) throw new Exception(Taal.T("Te veel plekken"));
        JSONObject v = bron.optJSONObject("velden");
        for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            if (f == null) continue;
            JSONObject props;
            double[] c;
            if (records) { props = f; c = coordUit(f, v); }
            else {
                props = f.optJSONObject("properties");
                if (props == null) props = f.optJSONObject("attributes");
                c = midden(f.optJSONObject("geometry"));
                if (c == null) c = coordUit(props, v);
            }
            if (c == null) continue;
            if (Math.abs(c[0]) > 90 || Math.abs(c[1]) > 180) throw new Exception(Taal.T("De coördinaten zijn niet in graden (vraag om WGS84, bij ArcGIS outSR=4326)"));
            Object fid = f.opt("id");
            ToilettenSamen.Plek p = uitVelden(props, c[0], c[1], bron, fid == null || fid == JSONObject.NULL ? null : String.valueOf(fid));
            if (p != null) uit.add(p);
        }
        return uit;
    }

    static double getal(String s) {
        if (s == null) return Double.NaN;
        String t = s.trim().replace(',', '.');
        try { return Double.parseDouble(t); } catch (Exception e) { return Double.NaN; }
    }

    /** Coördinaten uit losse velden (lat/lon), een "geo_point_2d" ({lat, lon} of "52.1, 4.3") of het veld "punt". */
    static double[] coordUit(JSONObject props, JSONObject velden) {
        if (props == null) return null;
        String latSpec = velden == null ? "" : velden.optString("lat", ""), lonSpec = velden == null ? "" : velden.optString("lon", "");
        double la = getal(veld(props, latSpec.isEmpty() ? "lat|latitude" : latSpec)), lo = getal(veld(props, lonSpec.isEmpty() ? "lon|lng|longitude" : lonSpec));
        if (!Double.isNaN(la) && !Double.isNaN(lo)) return new double[]{la, lo};
        String puntSpec = velden == null ? "" : velden.optString("punt", "");
        for (String k : (puntSpec.isEmpty() ? "geo_point_2d|geopoint|punt|locatie" : puntSpec).split("\\|")) {
            Object o = props.opt(k);
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                la = j.optDouble("lat", Double.NaN); lo = j.optDouble("lon", j.optDouble("lng", Double.NaN));
                if (!Double.isNaN(la) && !Double.isNaN(lo)) return new double[]{la, lo};
            } else if (o instanceof String) {
                String[] d = ((String) o).split("[,;]\\s*|\\s+");
                if (d.length == 2) { la = getal(d[0]); lo = getal(d[1]); if (!Double.isNaN(la) && !Double.isNaN(lo)) return new double[]{la, lo}; }
            }
        }
        return null;
    }

    /** CSV met kopregel; scheidingsteken ; , of tab (vanzelf), velden tussen aanhalingstekens mogen regels bevatten. */
    static List<List<String>> csv(String tekst) {
        String t = tekst.startsWith("\uFEFF") ? tekst.substring(1) : tekst;
        int eind = t.indexOf('\n');
        String kop = eind < 0 ? t : t.substring(0, eind);
        char sep = ';';
        int nP = kop.split(";", -1).length, nK = kop.split(",", -1).length, nT = kop.split("\t", -1).length;
        if (nK > nP && nK >= nT) sep = ','; else if (nT > nP && nT > nK) sep = '\t';
        List<List<String>> rijen = new ArrayList<>();
        List<String> rij = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (q) {
                if (ch == '"') { if (i + 1 < t.length() && t.charAt(i + 1) == '"') { b.append('"'); i++; } else q = false; }
                else b.append(ch);
            } else if (ch == '"' && b.length() == 0) q = true;
            else if (ch == sep) { rij.add(b.toString()); b.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') i++;
                rij.add(b.toString()); b.setLength(0);
                if (!(rij.size() == 1 && rij.get(0).isEmpty())) rijen.add(rij);
                rij = new ArrayList<>();
                if (rijen.size() > MAX_PLEKKEN + 1) break;
            } else b.append(ch);
        }
        if (b.length() > 0 || !rij.isEmpty()) { rij.add(b.toString()); rijen.add(rij); }
        return rijen;
    }

    static List<ToilettenSamen.Plek> leesCsv(String tekst, JSONObject bron) throws Exception {
        List<List<String>> rijen = csv(tekst);
        if (rijen.size() < 2) throw new Exception(Taal.T("Geen gegevens in de CSV"));
        if (rijen.size() > MAX_PLEKKEN + 1) throw new Exception(Taal.T("Te veel plekken"));
        List<String> kop = rijen.get(0);
        JSONObject v = bron.optJSONObject("velden");
        List<ToilettenSamen.Plek> uit = new ArrayList<>();
        for (int r = 1; r < rijen.size(); r++) {
            JSONObject props = new JSONObject();
            List<String> rij = rijen.get(r);
            for (int i = 0; i < kop.size() && i < rij.size(); i++) props.put(kop.get(i).trim(), rij.get(i));
            double[] c = coordUit(props, v);
            if (c == null) continue;
            ToilettenSamen.Plek p = uitVelden(props, c[0], c[1], bron, "r" + r);
            if (p != null) uit.add(p);
        }
        if (uit.isEmpty() && rijen.size() > 1) throw new Exception(Taal.T("Geen coördinaten gevonden: koppel de kolommen voor breedte- en lengtegraad"));
        return uit;
    }

    /** Verschil met de vorige keer: {nieuw, gewijzigd, weg}. */
    static int[] verschil(List<ToilettenSamen.Plek> oud, List<ToilettenSamen.Plek> nieuw) {
        Map<String, ToilettenSamen.Plek> o = new HashMap<>();
        for (ToilettenSamen.Plek p : oud) o.put(p.e, p);
        int n = 0, g = 0;
        for (ToilettenSamen.Plek p : nieuw) {
            ToilettenSamen.Plek q = o.remove(p.e);
            if (q == null) n++; else if (q.anders(p)) g++;
        }
        return new int[]{n, g, o.size()};
    }

    // ================= opslag (gzip) =================

    static String cacheJson(String datum, List<ToilettenSamen.Plek> lijst) throws Exception {
        JSONArray a = new JSONArray();
        for (ToilettenSamen.Plek p : lijst)
            a.put(new JSONArray().put(p.e).put(p.la).put(p.lo).put(p.s).put(p.f).put(p.n).put(p.u));
        return new JSONObject().put("formaat", Toiletten.FORMAAT).put("datum", datum == null ? "" : datum).put("plekken", a).toString();
    }

    static void schrijfCache(File f, String datum, List<ToilettenSamen.Plek> lijst) throws Exception {
        File d = f.getParentFile();
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException(Taal.T("Opslagmap maken lukt niet"));
        File tmp = new File(f.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp); GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(cacheJson(datum, lijst).getBytes(StandardCharsets.UTF_8));
                gz.finish();
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) throw new IOException(Taal.T("Opslaan lukt niet; de vorige gegevens zijn bewaard"));
        } finally { //noinspection ResultOfMethodCallIgnored
            tmp.delete(); }
    }

    static List<ToilettenSamen.Plek> leesCache(File f) {
        if (!f.isFile()) return new ArrayList<>();
        try (InputStream in = new GZIPInputStream(new FileInputStream(f))) {
            return leesTools(new String(Facturen.lees(in, MAX_UIT), StandardCharsets.UTF_8));
        } catch (Throwable e) { return new ArrayList<>(); }
    }

    static byte[] gunzip(byte[] b) throws Exception {
        if (b.length < 2 || (b[0] & 0xff) != 0x1f || (b[1] & 0xff) != 0x8b) return b;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) { return Facturen.lees(in, MAX_UIT); }
    }

    // ================= ophalen =================

    static byte[] haal(String url, long max) throws Exception {
        URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol())) throw new Exception(Taal.T("Alleen https"));
        HttpURLConnection h = (HttpURLConnection) u.openConnection();
        try {
            h.setConnectTimeout(20_000);
            h.setReadTimeout(60_000);
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("User-Agent", UA);
            h.setRequestProperty("Accept", "application/json, application/geo+json, text/csv, */*");
            int code = h.getResponseCode();
            if (!"https".equalsIgnoreCase(h.getURL().getProtocol())) throw new Exception(Taal.T("Alleen https"));
            if (code == 404) throw new Exception(Taal.T("Niet gevonden (404): klopt het adres nog?"));
            if (code != 200) throw new Exception(Taal.fill(Taal.T("De bron antwoordt met {code}"), "code", code));
            long len = h.getContentLengthLong();
            if (len > max) throw new Exception(Taal.T("Bestand te groot"));
            try (InputStream in = h.getInputStream()) { return Facturen.lees(in, max); }
        } finally { h.disconnect(); }
    }

    /** ArcGIS geeft per keer een deel (exceededTransferLimit): de rest met resultOffset ophalen. */
    static String haalArcgis(String url) throws Exception {
        JSONArray alles = new JSONArray();
        String eerste = null;
        for (int p = 0; p < MAX_PAGINAS; p++) {
            String u = p == 0 ? url : url + "&resultOffset=" + alles.length();
            String t = new String(gunzip(haal(u, MAX)), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(t);
            if (o.has("error")) return t;
            if (eerste == null) eerste = t;
            JSONArray fs = o.optJSONArray("features");
            if (fs == null || fs.length() == 0) break;
            for (int i = 0; i < fs.length(); i++) alles.put(fs.get(i));
            JSONObject props = o.optJSONObject("properties");
            boolean meer = o.optBoolean("exceededTransferLimit") || (props != null && props.optBoolean("exceededTransferLimit"));
            if (!meer || alles.length() > MAX_PLEKKEN) break;
        }
        return eerste == null ? "{\"features\":[]}" : new JSONObject().put("type", "FeatureCollection").put("features", alles).toString();
    }

    /** Lezen wat een bron geeft, met controle tegen meta.json bij de momentopname. Geeft {datum, sha, plekken}. */
    static Object[] lees(JSONObject bron, String vorigeSha, boolean cacheBestaat) throws Exception {
        String soort = bron.optString("soort");
        String url = bron.optString("url");
        if ("tools".equals(soort)) {
            String sha = "", datum = "";
            if (!bron.optString("metaUrl").isEmpty()) {
                JSONObject meta = new JSONObject(new String(haal(bron.optString("metaUrl"), 200_000), StandardCharsets.UTF_8));
                if (meta.optInt("formaat") > Toiletten.FORMAAT) throw new Exception(Taal.T("Nieuwer formaat: werk de app bij"));
                sha = meta.optString("sha256").toLowerCase(Locale.ROOT);
                datum = meta.optString("datum");
                if (!sha.matches("[0-9a-f]{64}")) throw new Exception(Taal.T("meta.json zonder controlegetal"));
                if (sha.equals(vorigeSha) && cacheBestaat) return new Object[]{datum, sha, null};
            }
            byte[] b = haal(url, MAX);
            if (!sha.isEmpty() && !sha.equals(Facturen.sha(b))) throw new Exception(Taal.T("Controle mislukt: het bestand klopt niet met meta.json (later opnieuw)"));
            String t = new String(gunzip(b), StandardCharsets.UTF_8);
            if (datum.isEmpty()) datum = new JSONObject(t).optString("datum");
            return new Object[]{datum, sha.isEmpty() ? Facturen.sha(b) : sha, leesTools(t)};
        }
        String t = "arcgis".equals(soort) ? haalArcgis(url) : new String(gunzip(haal(url, MAX)), StandardCharsets.UTF_8);
        String sha = Facturen.sha(t.getBytes(StandardCharsets.UTF_8));
        List<ToilettenSamen.Plek> l = "csv".equals(soort) ? leesCsv(t, bron) : leesGeojson(t, bron);
        return new Object[]{"", sha, l};
    }

    // ================= scannen =================

    /** Eén bron scannen en de toestand bijwerken. Geeft {id, ok, aantal, nieuw, gewijzigd, weg, fout}. */
    @SuppressWarnings("unchecked")
    static JSONObject scanBron(Context c, JSONObject bron) throws Exception {
        String id = bron.optString("id");
        long nu = System.currentTimeMillis();
        JSONObject alle = Toiletten.toestand(c);
        JSONObject t = alle.optJSONObject(id);
        if (t == null) t = new JSONObject();
        t.put("lastTry", nu);
        JSONObject r = new JSONObject().put("id", id);
        File cf = Toiletten.cacheFile(c, id);
        try {
            Object[] uit = lees(bron, t.optString("sha"), cf.isFile());
            List<ToilettenSamen.Plek> nieuw = (List<ToilettenSamen.Plek>) uit[2];
            if (nieuw == null) {
                t.put("lastOk", nu).put("fout", "").put("nieuw", 0).put("gewijzigd", 0).put("weg", 0).put("datum", uit[0]);
                r.put("ok", true).put("ongewijzigd", true).put("aantal", t.optInt("aantal"));
            } else {
                if (nieuw.isEmpty()) throw new Exception(Taal.T("Geen plekken gevonden in deze bron"));
                int[] v = verschil(leesCache(cf), nieuw);
                schrijfCache(cf, (String) uit[0], nieuw);
                t.put("lastOk", nu).put("fout", "").put("aantal", nieuw.size()).put("nieuw", v[0]).put("gewijzigd", v[1]).put("weg", v[2])
                        .put("datum", uit[0]).put("sha", uit[1]).put("grootte", cf.length());
                r.put("ok", true).put("aantal", nieuw.size()).put("nieuw", v[0]).put("gewijzigd", v[1]).put("weg", v[2]);
            }
            App.log(c, "TOILET", "scan: " + t.optInt("aantal") + " plekken, " + t.optInt("nieuw") + " nieuw, " + t.optInt("weg") + " weg");
        } catch (Throwable e) {
            t.put("fout", kort(e));
            r.put("ok", false).put("fout", kort(e));
            App.log(c, "TOILET", "scan mislukt: " + e.getClass().getSimpleName());
        }
        JSONArray log = t.optJSONArray("log");
        JSONArray nl = new JSONArray();
        nl.put(new JSONObject().put("t", nu).put("ok", r.optBoolean("ok")).put("aantal", t.optInt("aantal")).put("nieuw", r.optInt("nieuw"))
                .put("gewijzigd", r.optInt("gewijzigd")).put("weg", r.optInt("weg")).put("fout", r.optString("fout")));
        for (int i = 0; log != null && i < log.length() && nl.length() < 10; i++) nl.put(log.get(i));
        t.put("log", nl);
        synchronized (Toiletten.LOCK) {
            if (Toiletten.bron(c, id) == null) {
                // Tijdens het scannen weggehaald: niets achterlaten
                //noinspection ResultOfMethodCallIgnored
                cf.delete();
            } else {
                alle = Toiletten.toestand(c);
                alle.put(id, t);
                Toiletten.bewaarToestand(c, alle);
            }
        }
        ToilettenSamen.wis();
        return r;
    }

    /**
     * Scannen: één bron (id) of alle bronnen die aan staan (id leeg; vanzelf = alleen die aan de beurt zijn en, op mobiel
     * internet, niet de bronnen die op wifi wachten). Daarna de status van wat je met OpenStreetMap deelde.
     */
    static JSONArray scan(Context c, String id, boolean vanzelf) {
        JSONArray uit = new JSONArray();
        if (!BEZIG.compareAndSet(false, true)) return null;
        try {
            JSONArray bs = Toiletten.bronnen(c);
            if (!Toiletten.ingesteld(c)) Toiletten.bewaarLijst(c, bs);
            long nu = System.currentTimeMillis();
            boolean betaald = vanzelf && opMobiel(c);
            JSONObject st = Toiletten.toestand(c);
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.optJSONObject(i);
                if (b == null || !(id.isEmpty() ? b.optBoolean("aan", true) : id.equals(b.optString("id")))) continue;
                if (vanzelf) {
                    JSONObject t = st.optJSONObject(b.optString("id"));
                    if (t == null) t = new JSONObject();
                    if (b.optBoolean("wifi") && betaald) continue;
                    if (!Facturen.aanDeBeurt(b, t.optLong("lastOk"), t.optLong("lastTry"), nu, Regio.zone(c))) continue;
                }
                uit.put(scanBron(c, b));
            }
            ToilettenOsm.volg(c, nu);
        } catch (Throwable e) {
            App.log(c, "TOILET", "scan mislukt: " + e.getClass().getSimpleName());
        } finally { BEZIG.set(false); }
        return uit;
    }

    static boolean opMobiel(Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Throwable e) { return true; }
    }
}
