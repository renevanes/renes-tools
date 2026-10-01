package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Mijn routes: GPS-routeopname (zoals het vroegere My Tracks). Punten worden tijdens het
 * opnemen in een regelbestand bewaard, en bij het stoppen samengevat en als GPX opgeslagen.
 * Afstand via haversine; de routevorm wordt als SVG-pad getekend (geen kaarttegels nodig).
 *
 * Alleen standaard Java in de rekenfuncties, zodat alles ook op een pc te testen is.
 */
final class Tracks {

    private Tracks() { }

    static final String PREFS = "tracks";

    static File dir(Context c) { File d = new File(c.getFilesDir(), "tracks"); d.mkdirs(); return d; }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---------- punt ----------

    static final class Pt {
        final double lat, lon;
        final double ele;       // meter, NaN = onbekend
        final long t;           // ms
        final float acc;        // meter, nauwkeurigheid
        final float speed;      // m/s, -1 = onbekend
        Pt(double lat, double lon, double ele, long t, float acc, float speed) {
            this.lat = lat; this.lon = lon; this.ele = ele; this.t = t; this.acc = acc; this.speed = speed;
        }

        /** Regel in het opnamebestand: "lat,lon,ele,t,acc,speed" (ele/speed leeg = onbekend). */
        String line() {
            return lat + "," + lon + "," + (Double.isNaN(ele) ? "" : ele) + "," + t + "," + acc + "," + (speed < 0 ? "" : speed);
        }

        static Pt parse(String s) {
            String[] p = s.split(",", -1);
            if (p.length < 4) return null;
            try {
                return new Pt(Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                        p[2].isEmpty() ? Double.NaN : Double.parseDouble(p[2]),
                        Long.parseLong(p[3]),
                        p.length > 4 && !p[4].isEmpty() ? Float.parseFloat(p[4]) : 0f,
                        p.length > 5 && !p[5].isEmpty() ? Float.parseFloat(p[5]) : -1f);
            } catch (Exception e) { return null; }
        }
    }

    // ---------- afstand & statistiek ----------

    static final double R = 6371000.0;

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    static final class Stats {
        double distance;        // meter
        long movingMs, totalMs; // bewegende tijd, totale tijd
        double maxSpeed;        // m/s
        double eleGain, eleLoss;// meter
        double minEle = Double.NaN, maxEle = Double.NaN;
        int points;
        long startT, endT;

        double avgSpeed() { return movingMs > 0 ? distance / (movingMs / 1000.0) : 0; }
    }

    /** Berekent statistieken uit de punten. Negeert sprongen door slechte nauwkeurigheid. */
    static Stats stats(List<Pt> pts) {
        Stats s = new Stats();
        s.points = pts.size();
        if (pts.isEmpty()) return s;
        s.startT = pts.get(0).t;
        s.endT = pts.get(pts.size() - 1).t;
        s.totalMs = Math.max(0, s.endT - s.startT);
        double eleSmooth = Double.NaN;
        Pt prev = null;
        for (Pt p : pts) {
            if (!Double.isNaN(p.ele)) {
                s.minEle = Double.isNaN(s.minEle) ? p.ele : Math.min(s.minEle, p.ele);
                s.maxEle = Double.isNaN(s.maxEle) ? p.ele : Math.max(s.maxEle, p.ele);
                if (Double.isNaN(eleSmooth)) eleSmooth = p.ele;
                else {
                    double d = p.ele - eleSmooth;
                    if (Math.abs(d) >= 4) { // drempel tegen GPS-hoogteruis
                        if (d > 0) s.eleGain += d; else s.eleLoss += -d;
                        eleSmooth = p.ele;
                    }
                }
            }
            if (prev != null) {
                double d = haversine(prev.lat, prev.lon, p.lat, p.lon);
                long dt = p.t - prev.t;
                // sla onrealistische sprongen over (slecht signaal): >50 m/s
                if (dt > 0 && d / (dt / 1000.0) <= 50) {
                    s.distance += d;
                    double v = p.speed >= 0 ? p.speed : d / (dt / 1000.0);
                    if (v > s.maxSpeed && v <= 50) s.maxSpeed = v;
                    if (d >= 1.0) s.movingMs += dt; // beweegt als er meetbaar verplaatst is
                }
            }
            prev = p;
        }
        if (s.movingMs == 0) s.movingMs = s.totalMs;
        return s;
    }

    // ---------- SVG-tekening ----------

    /** Routevorm als SVG-pad binnen een vak van w x h (met marge), of "" bij te weinig punten. */
    static String svgPath(List<Pt> pts, double w, double h, double pad) {
        if (pts.size() < 2) return "";
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (Pt p : pts) {
            minLat = Math.min(minLat, p.lat); maxLat = Math.max(maxLat, p.lat);
            minLon = Math.min(minLon, p.lon); maxLon = Math.max(maxLon, p.lon);
        }
        double midLat = (minLat + maxLat) / 2;
        double kx = Math.cos(Math.toRadians(midLat)); // lengtegraden korter naar de polen
        double spanX = Math.max(1e-9, (maxLon - minLon) * kx);
        double spanY = Math.max(1e-9, (maxLat - minLat));
        double scale = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
        double offX = (w - spanX * scale) / 2, offY = (h - spanY * scale) / 2;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pts.size(); i++) {
            Pt p = pts.get(i);
            double x = offX + ((p.lon - minLon) * kx) * scale;
            double y = h - (offY + (p.lat - minLat) * scale); // y omkeren: noord boven
            b.append(i == 0 ? "M" : "L").append(round(x)).append(' ').append(round(y)).append(' ');
        }
        return b.toString().trim();
    }

    private static String round(double v) { return String.valueOf(Math.round(v * 10) / 10.0); }

    private static double round6(double v) { return Math.round(v * 1e6) / 1e6; }

    // ---------- opslag ----------

    static File liveFile(Context c) { return new File(dir(c), "live.trk"); }

    static List<Pt> readPoints(File f) {
        List<Pt> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                Pt p = Pt.parse(line);
                if (p != null) out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String title(File f) {
        // bestandsnaam: <tijd>__<titel>.trk
        String n = f.getName().replaceFirst("\\.trk$", "");
        int i = n.indexOf("__");
        return i >= 0 ? n.substring(i + 2).replace('_', ' ') : n;
    }

    static long tsOf(File f) {
        String n = f.getName();
        int i = n.indexOf("__");
        try { return Long.parseLong(i >= 0 ? n.substring(0, i) : n.replaceFirst("\\.trk$", "")); }
        catch (Exception e) { return f.lastModified(); }
    }

    static List<File> saved(Context c) {
        List<File> l = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".trk") && !f.getName().equals("live.trk")) l.add(f);
        Collections.sort(l, (a, b) -> Long.compare(tsOf(b), tsOf(a)));
        return l;
    }

    static String safeTitle(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : s.trim().toCharArray()) b.append(Character.isLetterOrDigit(ch) || ch == '-' ? ch : '_');
        String r = b.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        return r.length() > 60 ? r.substring(0, 60) : r;
    }

    /** Verplaatst het opnamebestand naar een blijvende route met titel. Geeft het nieuwe bestand terug. */
    static File finalize(Context c, long startT, String titleText) throws Exception {
        File live = liveFile(c);
        String t = safeTitle(titleText);
        File out = new File(dir(c), startT + (t.isEmpty() ? "" : "__" + t) + ".trk");
        if (live.exists()) {
            if (out.exists()) out.delete();
            if (!live.renameTo(out)) {
                // Hernoemen mislukt: kopiëren en daarna het opnamebestand opruimen, zodat niets verloren gaat.
                try (java.io.InputStream in = new java.io.FileInputStream(live);
                     java.io.OutputStream o = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                live.delete();
            }
        }
        return out;
    }

    static boolean rename(Context c, File f, String newTitle) {
        String t = safeTitle(newTitle);
        File out = new File(dir(c), tsOf(f) + (t.isEmpty() ? "" : "__" + t) + ".trk");
        return f.renameTo(out);
    }

    // ---------- GPX ----------

    static String gpxName(File trk) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US);
        String t = title(trk);
        return f.format(new Date(tsOf(trk))) + (t.isEmpty() ? "" : "_" + safeTitle(t)) + ".gpx";
    }

    static void writeGpx(Writer w, String name, List<Pt> pts) throws Exception {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<gpx version=\"1.1\" creator=\"Rene's Tools\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        w.write("<trk><name>" + esc(name) + "</name><trkseg>\n");
        for (Pt p : pts) {
            w.write("<trkpt lat=\"" + p.lat + "\" lon=\"" + p.lon + "\">");
            if (!Double.isNaN(p.ele)) w.write("<ele>" + p.ele + "</ele>");
            w.write("<time>" + iso.format(new Date(p.t)) + "</time>");
            w.write("</trkpt>\n");
        }
        w.write("</trkseg></trk></gpx>\n");
    }

    static File writeGpxFile(Context c, File trk) throws Exception {
        File out = new File(dir(c), gpxName(trk));
        try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(out), StandardCharsets.UTF_8)) {
            writeGpx(w, title(trk).isEmpty() ? "Route" : title(trk), readPoints(trk));
        }
        return out;
    }

    /** Exporteert de GPX naar de gekozen backup-map onder "Routes/". Geeft true bij succes. */
    static boolean exportToBackup(Context c, File trk) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) return false;
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir("Routes", true);
        String name = gpxName(trk);
        WaBackup.Child ch = dir.kids.get(name);
        Uri u;
        if (ch != null && !ch.dir) u = DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId);
        else {
            u = DocumentsContract.createDocument(dest.cr, dir.uri, "application/gpx+xml", name);
            if (u == null) throw new Exception("Bestand maken lukt niet");
        }
        OutputStream o;
        try { o = dest.cr.openOutputStream(u, "wt"); } catch (Exception e) { o = dest.cr.openOutputStream(u, "w"); }
        if (o == null) throw new Exception("Schrijven lukt niet");
        try (Writer w = new OutputStreamWriter(o, StandardCharsets.UTF_8)) {
            writeGpx(w, title(trk).isEmpty() ? "Route" : title(trk), readPoints(trk));
        }
        return true;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- JSON voor de app ----------

    static JSONObject statsJson(Stats s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("distance", s.distance);
        o.put("movingMs", s.movingMs);
        o.put("totalMs", s.totalMs);
        o.put("maxSpeed", s.maxSpeed);
        o.put("avgSpeed", s.avgSpeed());
        o.put("eleGain", s.eleGain);
        o.put("eleLoss", s.eleLoss);
        if (!Double.isNaN(s.minEle)) o.put("minEle", s.minEle);
        if (!Double.isNaN(s.maxEle)) o.put("maxEle", s.maxEle);
        o.put("points", s.points);
        o.put("startT", s.startT);
        o.put("endT", s.endT);
        return o;
    }

    static String listJson(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (File f : saved(c)) {
            List<Pt> pts = readPoints(f);
            Stats s = stats(pts);
            JSONObject o = new JSONObject();
            o.put("id", f.getName());
            o.put("title", title(f));
            o.put("t", tsOf(f));
            o.put("stats", statsJson(s));
            o.put("path", svgPath(pts, 300, 160, 10));
            a.put(o);
        }
        return a.toString();
    }

    static File byId(Context c, String id) {
        if (id == null || id.contains("/") || id.contains("..")) return null;
        File f = new File(dir(c), id);
        return f.getName().endsWith(".trk") && f.isFile() ? f : null;
    }

    static String detailJson(Context c, String id, double w, double h) throws Exception {
        File f = byId(c, id);
        if (f == null) throw new Exception("Route niet gevonden");
        List<Pt> pts = readPoints(f);
        Stats s = stats(pts);
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title(f));
        o.put("t", tsOf(f));
        o.put("stats", statsJson(s));
        o.put("path", svgPath(pts, w, h, 12));
        // echte lat/lon-punten voor de kaart (afgevlakt tot max ~800 punten)
        JSONArray line = new JSONArray();
        int lstep = Math.max(1, pts.size() / 800);
        for (int i = 0; i < pts.size(); i += lstep) {
            Pt p = pts.get(i);
            line.put(new JSONArray().put(round6(p.lat)).put(round6(p.lon)));
        }
        if (!pts.isEmpty() && (pts.size() - 1) % lstep != 0) {
            Pt last = pts.get(pts.size() - 1);
            line.put(new JSONArray().put(round6(last.lat)).put(round6(last.lon)));
        }
        o.put("line", line);
        // hoogteprofiel: afstand (x) tegen hoogte (y), vereenvoudigd
        JSONArray ele = new JSONArray();
        double dist = 0; Pt prev = null;
        int step = Math.max(1, pts.size() / 200);
        for (int i = 0; i < pts.size(); i++) {
            Pt p = pts.get(i);
            if (prev != null) dist += haversine(prev.lat, prev.lon, p.lat, p.lon);
            prev = p;
            if (!Double.isNaN(p.ele) && i % step == 0) ele.put(new JSONArray().put(Math.round(dist)).put(Math.round(p.ele)));
        }
        o.put("ele", ele);
        return o.toString();
    }
}
