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

    // ---------- ruisfilter ----------

    /** Metingen met een slechtere nauwkeurigheid dan dit (in meter) tellen niet mee. */
    static final float MAX_ACC = 30f;
    /** Kleinste verplaatsing die als een nieuw routepunt telt (in meter). */
    static final double MIN_MOVE = 5.0;
    /** Aantal metingen dat wordt gemiddeld tegen losse sprongen. */
    static final int WIN = 5;

    /**
     * Ruisfilter voor gps. Stilstaand springt de gemeten positie (vooral binnen) vaak 5-20 m
     * heen en weer. Daarom:
     *  1. metingen met een onnauwkeurigheid groter dan MAX_ACC worden overgeslagen;
     *  2. de laatste paar metingen worden gemiddeld, zodat losse sprongen uitmiddelen;
     *  3. er komt pas een nieuw routepunt als dat gemiddelde duidelijk verschoven is ten
     *     opzichte van het vorige routepunt (minstens MIN_MOVE, meer bij slecht signaal of
     *     als gps zelf stilstand meldt). Zo gaat langzaam lopen niet verloren, maar telt ruis niet mee.
     * De Smoother verwerkt de metingen één voor één en geeft een nieuw routepunt terug, of null.
     */
    static final class Smoother {
        private final Pt[] win = new Pt[WIN];
        private int n = 0, head = 0;
        private Pt last;

        Pt add(Pt p) {
            if (p.acc > MAX_ACC) return null;
            win[head] = p; head = (head + 1) % WIN; if (n < WIN) n++;
            if (n < 3) return null; // eerst een paar metingen verzamelen
            double la = 0, lo = 0, ac = 0, el = 0; int ne = 0, ns = 0; float sp = 0;
            for (int i = 0; i < n; i++) {
                Pt q = win[i];
                la += q.lat; lo += q.lon; ac += Math.max(1f, q.acc);
                if (!Double.isNaN(q.ele)) { el += q.ele; ne++; }
                if (q.speed >= 0) { sp += q.speed; ns++; }
            }
            Pt c = new Pt(la / n, lo / n, ne > 0 ? el / ne : Double.NaN, p.t, (float) (ac / n), ns > 0 ? sp / ns : -1f);
            if (last == null) { last = c; return c; }
            long dt = c.t - last.t;
            if (dt <= 0) return null;
            double d = haversine(last.lat, last.lon, c.lat, c.lon);
            if (d / (dt / 1000.0) > 50) return null; // onmogelijke sprong
            double need = Math.max(MIN_MOVE, c.acc * 0.6);
            if (c.speed < 0.8f) need *= 2; // gps meldt (bijna) stilstand of geen snelheid
            if (d < need) return null;
            last = c;
            return c;
        }
    }

    /** Alleen de routepunten die echte beweging zijn (voor statistiek, tekening, kaart en GPX). */
    static List<Pt> filter(List<Pt> raw) {
        List<Pt> out = new ArrayList<>();
        Smoother sm = new Smoother();
        for (Pt p : raw) { Pt k = sm.add(p); if (k != null) out.add(k); }
        return out;
    }

    /** Hoogste snelheid over stukken van minstens 10 seconden; losse uitschieters tellen zo niet mee. */
    static double maxSpeed(List<Pt> pts) {
        if (pts.size() < 2) return 0;
        double[] cum = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++)
            cum[i] = cum[i - 1] + haversine(pts.get(i - 1).lat, pts.get(i - 1).lon, pts.get(i).lat, pts.get(i).lon);
        double best = 0;
        int j = 0;
        for (int i = 1; i < pts.size(); i++) {
            while (j < i - 1 && pts.get(i).t - pts.get(j + 1).t >= 10000) j++;
            long dt = pts.get(i).t - pts.get(j).t;
            if (dt >= 10000) {
                double v = (cum[i] - cum[j]) / (dt / 1000.0);
                if (v > best && v <= 50) best = v;
            }
        }
        return best;
    }

    /** Berekent statistieken; ruis door stilstaan telt niet mee. */
    static Stats stats(List<Pt> raw) {
        Stats s = new Stats();
        if (raw.isEmpty()) return s;
        s.startT = raw.get(0).t;
        s.endT = raw.get(raw.size() - 1).t;
        s.totalMs = Math.max(0, s.endT - s.startT);
        List<Pt> pts = filter(raw);
        s.points = pts.size();
        double eleSmooth = Double.NaN;
        Pt prev = null;
        for (Pt p : pts) {
            if (!Double.isNaN(p.ele)) {
                s.minEle = Double.isNaN(s.minEle) ? p.ele : Math.min(s.minEle, p.ele);
                s.maxEle = Double.isNaN(s.maxEle) ? p.ele : Math.max(s.maxEle, p.ele);
                if (Double.isNaN(eleSmooth)) eleSmooth = p.ele;
                else {
                    double d = p.ele - eleSmooth;
                    if (Math.abs(d) >= 10) { // drempel tegen gps-hoogteruis (gps-hoogte is grof)
                        if (d > 0) s.eleGain += d; else s.eleLoss += -d;
                        eleSmooth = p.ele;
                    }
                }
            }
            if (prev != null) {
                s.distance += haversine(prev.lat, prev.lon, p.lat, p.lon);
                s.movingMs += p.t - prev.t;
            }
            prev = p;
        }
        s.maxSpeed = maxSpeed(pts);
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

    // ---------- GPX inlezen (terugzetten uit de backup, of een route uit een andere app) ----------

    static final java.util.regex.Pattern GPX_PT = java.util.regex.Pattern.compile(
            "<(?:\\w+:)?(trkpt|rtept)\\b([^>]*?)(/>|>(.*?)</(?:\\w+:)?\\1>)", java.util.regex.Pattern.DOTALL);
    static final java.util.regex.Pattern GPX_ATTR = java.util.regex.Pattern.compile("\\b(lat|lon)\\s*=\\s*[\"']([-0-9.eE+]+)[\"']");

    static final java.util.regex.Pattern GPX_ELE = tagPattern("ele"), GPX_TIME = tagPattern("time"), GPX_NAME = tagPattern("name");
    static final java.util.regex.Pattern ISO = java.util.regex.Pattern.compile(
            "\\s*(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d+))?(Z|[+-]\\d{2}:?\\d{2})?\\s*");

    static java.util.regex.Pattern tagPattern(String name) {
        return java.util.regex.Pattern.compile("<(?:\\w+:)?" + name + ">(.*?)</(?:\\w+:)?" + name + ">", java.util.regex.Pattern.DOTALL);
    }

    /** ISO-8601 tijd (met Z, +02:00 of zonder zone = UTC, met willekeurige fractie). 0 als onleesbaar. */
    static long parseIso(String s) {
        java.util.regex.Matcher m = ISO.matcher(s);
        if (!m.matches()) return 0;
        java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        long t = cal.getTimeInMillis();
        if (m.group(7) != null) { String f = (m.group(7) + "000").substring(0, 3); t += Integer.parseInt(f); }
        String z = m.group(8);
        if (z != null && !"Z".equals(z)) {
            String d = z.replace(":", "");
            int off = Integer.parseInt(d.substring(1, 3)) * 60 + Integer.parseInt(d.substring(3, 5));
            t -= (z.charAt(0) == '-' ? -1 : 1) * off * 60_000L;
        }
        return t;
    }

    /** Punten uit een GPX-bestand (trkpt of rtept). Ontbreekt een tijd ergens, dan voor de hele route 1 seconde per punt. */
    static List<Pt> parseGpx(String xml) {
        List<Pt> out = new ArrayList<>();
        java.util.regex.Matcher m = GPX_PT.matcher(xml);
        long base = 0;
        boolean allTimes = true;
        while (m.find() && out.size() < 500_000) {
            double lat = Double.NaN, lon = Double.NaN;
            java.util.regex.Matcher a = GPX_ATTR.matcher(m.group(2));
            while (a.find()) { if ("lat".equals(a.group(1))) lat = Double.parseDouble(a.group(2)); else lon = Double.parseDouble(a.group(2)); }
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
            String body = m.group(4) == null ? "" : m.group(4);
            double ele = Double.NaN;
            java.util.regex.Matcher em = GPX_ELE.matcher(body);
            if (em.find()) try { ele = Double.parseDouble(em.group(1).trim()); } catch (Exception ignored) { }
            long t = 0;
            java.util.regex.Matcher tm = GPX_TIME.matcher(body);
            if (tm.find()) t = parseIso(tm.group(1));
            if (t == 0) allTimes = false;
            out.add(new Pt(lat, lon, ele, t, 5f, -1f));
        }
        if (!allTimes) {
            // Vaste begintijd (1 jan 2000 + plek in het bestand niet nodig): zelfde bestand → zelfde route, zodat dubbel importeren herkend wordt
            base = 946_684_800_000L + (xml.hashCode() & 0x7fffffffL) * 1000L;
            List<Pt> fixed = new ArrayList<>(out.size());
            for (int i = 0; i < out.size(); i++) { Pt p = out.get(i); fixed.add(new Pt(p.lat, p.lon, p.ele, base + i * 1000L, 5f, -1f)); }
            out = fixed;
        }
        return out;
    }

    static String tag(String body, String name) {
        java.util.regex.Matcher m = ("name".equals(name) ? GPX_NAME : tagPattern(name)).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    static String gpxTitle(String xml) {
        int trk = Math.max(xml.indexOf("<trk>"), xml.indexOf("<trk "));
        String name = tag(trk >= 0 ? xml.substring(trk) : xml, "name");
        if (name == null) name = tag(xml, "name");
        if (name == null) return "Geïmporteerde route";
        name = name.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
        return name.isEmpty() ? "Geïmporteerde route" : name;
    }

    static JSONObject previewGpx(String xml) throws Exception {
        List<Pt> pts = parseGpx(xml);
        if (pts.size() < 2) throw new Exception("Geen route gevonden in dit bestand (GPX met trackpunten)");
        Stats s = stats(pts);
        return new JSONObject().put("kind", "route").put("total", 1).put("fresh", 1).put("dup", 0)
                .put("title", gpxTitle(xml)).put("points", pts.size()).put("stats", statsJson(s))
                .put("sample", new JSONArray().put(gpxTitle(xml)));
    }

    /** Bewaart een GPX als route. Bestaat er al een route met hetzelfde begintijdstip, dan niets doen (0). */
    static int importGpx(Context c, String xml) throws Exception {
        List<Pt> pts = parseGpx(xml);
        if (pts.size() < 2) throw new Exception("Geen route gevonden in dit bestand");
        long start = pts.get(0).t;
        // Al aanwezig? (GPX bewaart tijden per seconde: binnen een minuut van hetzelfde begin = dezelfde route)
        for (File f : saved(c)) if (Math.abs(tsOf(f) - start) < 60_000L) return 0;
        String t = safeTitle(gpxTitle(xml));
        File out = new File(dir(c), start + (t.isEmpty() ? "" : "__" + t) + ".trk");
        try (java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), StandardCharsets.UTF_8))) {
            for (Pt p : pts) { w.write(p.line()); w.write('\n'); }
        }
        return 1;
    }

    /**
     * Opname afgebroken (Android stopte de app): het opnamebestand staat er nog, maar de dienst loopt niet.
     * Dan bieden we het aan als "net gestopt", zodat je de route kunt opslaan in plaats van hem kwijt te raken.
     * Geeft true als er zo een route klaarstaat.
     */
    static synchronized boolean recoverInterrupted(Context c) {
        if (TracksService.running) return false;
        SharedPreferences p = prefs(c);
        if (p.getBoolean("trip", false) || Car.prefs(c).getLong("tripStart", 0) > 0) {
            p.edit().remove("trip").apply(); // een afgebroken rit wordt een gewone route om op te slaan
            Car.prefs(c).edit().remove("tripStart").apply();
        }
        if (p.getBoolean("justStopped", false)) return true;
        File live = liveFile(c);
        if (!live.exists()) return false;
        List<Pt> pts = readPoints(live);
        if (pts.isEmpty()) { live.delete(); return false; }
        try {
            Stats s = stats(pts);
            long start = p.getLong("startT", pts.get(0).t);
            p.edit().putBoolean("justStopped", true).putBoolean("interrupted", true).putInt("lastPoints", s.points)
                    .putString("lastStats", statsJson(s).toString()).putLong("lastStartT", start).apply();
            return true;
        } catch (Exception e) { return false; }
    }

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
            writeGpx(w, title(trk).isEmpty() ? "Route" : title(trk), filter(readPoints(trk)));
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
            writeGpx(w, title(trk).isEmpty() ? "Route" : title(trk), filter(readPoints(trk)));
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
            List<Pt> raw = readPoints(f);
            Stats s = stats(raw);
            List<Pt> pts = filter(raw);
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

    /** Lat/lon-punten van de lopende opname (afgevlakt), voor de live kaart. */
    static String liveLineJson(Context c) {
        try {
            List<Pt> pts = filter(readPoints(liveFile(c)));
            JSONArray a = new JSONArray();
            int step = Math.max(1, pts.size() / 500);
            for (int i = 0; i < pts.size(); i += step) {
                Pt p = pts.get(i);
                a.put(new JSONArray().put(round6(p.lat)).put(round6(p.lon)));
            }
            return a.toString();
        } catch (Exception e) { return "[]"; }
    }

    static String detailJson(Context c, String id, double w, double h) throws Exception {
        File f = byId(c, id);
        if (f == null) throw new Exception("Route niet gevonden");
        List<Pt> raw = readPoints(f);
        Stats s = stats(raw);
        List<Pt> pts = filter(raw);
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
