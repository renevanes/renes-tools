package nl.rene.tools;

import android.content.Context;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

/**
 * Doorgeven aan de gemeenschap: een OpenStreetMap-notitie (openbaar, zonder account; OpenStreetMap ziet het IP-adres).
 * Rene ziet eerst precies deze tekst en bevestigt; er gaat geen naam, account of locatie van hem mee, alleen de plek
 * en wat hij invulde. In een moderatiezone (403) opent de website op die plek. De status volgt de app bij het scannen.
 */
final class ToilettenOsm {
    private ToilettenOsm() { }

    static final String API = "https://api.openstreetmap.org/api/0.6/notes";
    static final int MAX_PER_DAG = 20;
    static final String[] WAT = {"toevoeging", "wijziging", "afwijzing"};

    /** Hier mag alleen iemand met een account een notitie maken: dan via de website. */
    static final class Zone extends Exception {
        Zone() { super(Taal.T("Hier kan het alleen via de website van OpenStreetMap")); }
    }

    /* taal-vast-begin: de notitie gaat naar OpenStreetMap Nederland, altijd in het Nederlands */
    static String soortTekst(String s) {
        switch (s == null ? "" : s) {
            case "toilet": return "openbaar toilet";
            case "douche": return "douche";
            case "tank": return "tankstation";
            case "verzorg": return "verzorgingsplaats";
            case "rust": return "rustplaats";
            case "camping": return "camping";
            case "haven": return "jachthaven";
            case "station": return "station";
            case "horeca": return "horecazaak";
            case "winkel": return "winkelcentrum";
            case "biblio": return "bibliotheek";
            case "zorg": return "ziekenhuis";
            case "sport": return "sportplek";
            case "strand": return "strand";
            default: return "plek";
        }
    }

    static String betalen(int f) { return (f & Toiletten.GRATIS) != 0 ? "gratis" : (f & Toiletten.BETAALD) != 0 ? "betaald" : "onbekend"; }
    static String rolstoel(int f) { return (f & Toiletten.ROLSTOEL) != 0 ? "rolstoeltoegankelijk" : (f & Toiletten.ROLSTOEL_BEPERKT) != 0 ? "beperkt rolstoeltoegankelijk" : "niet rolstoeltoegankelijk"; }

    static String eigenschappen(String s, int f) {
        StringBuilder b = new StringBuilder();
        boolean toiletPlek = "toilet".equals(s);
        if (!toiletPlek && (f & Toiletten.TOILET) != 0) lid(b, "met toilet");
        if (!"douche".equals(s) && (f & Toiletten.DOUCHE) != 0) lid(b, "met douche");
        if ((f & (Toiletten.GRATIS | Toiletten.BETAALD)) != 0) lid(b, betalen(f));
        if ((f & (Toiletten.ROLSTOEL | Toiletten.ROLSTOEL_BEPERKT)) != 0) lid(b, rolstoel(f));
        if ((f & Toiletten.VERSCHOON) != 0) lid(b, "met verschoontafel");
        if ((f & Toiletten.KLANTEN) != 0) lid(b, "alleen voor klanten");
        if ((f & Toiletten.URINOIR) != 0) lid(b, "alleen een urinoir");
        return b.toString();
    }

    private static void lid(StringBuilder b, String s) { if (b.length() > 0) b.append(", "); b.append(s); }

    static String reden(String r) {
        switch (r == null ? "" : r) {
            case "weg": return "bestaat niet meer";
            case "dicht": return "is gesloten";
            case "prive": return "is niet openbaar";
            case "klanten": return "is alleen voor klanten";
            default: return "klopt niet";
        }
    }

    /** OpenStreetMap-adres van een object uit de momentopname ("n123" = node 123). Leeg als het geen OSM-id is. */
    static String osmUrl(String extId) {
        if (extId == null || !extId.matches("[nwr]\\d{1,15}")) return "";
        String t = extId.charAt(0) == 'n' ? "node" : extId.charAt(0) == 'w' ? "way" : "relation";
        return "https://www.openstreetmap.org/" + t + "/" + extId.substring(1);
    }

    /**
     * De tekst van de notitie. wat: toevoeging (nu = de plek), wijziging (nu = jouw waarden, was = wat de bron zei),
     * afwijzing (nu = de plek, reden). Geen naam of account van jou: alleen de plek en wat je zelf invulde.
     */
    static String tekst(String wat, JSONObject nu, JSONObject was, String osm, String reden, String opmerking) {
        String s = nu.optString("s", "toilet");
        int f = nu.optInt("f");
        StringBuilder b = new StringBuilder();
        String waar = osm == null || osm.isEmpty() ? "" : " (" + osm + ")";
        if ("afwijzing".equals(wat)) {
            b.append("Het ").append(lidwoordloos(s)).append(" hier").append(waar).append(' ').append(reden(reden)).append('.');
        } else if ("wijziging".equals(wat) && was != null) {
            b.append("Het ").append(lidwoordloos(s)).append(" hier").append(waar).append(" klopt niet helemaal:");
            int wf = was.optInt("f");
            if (!s.equals(was.optString("s"))) regel(b, "het is een " + soortTekst(s), soortTekst(was.optString("s")));
            if ((f & Toiletten.TOILET) != (wf & Toiletten.TOILET)) regel(b, (f & Toiletten.TOILET) != 0 ? "er is een toilet" : "er is geen toilet", null);
            if ((f & Toiletten.DOUCHE) != (wf & Toiletten.DOUCHE)) regel(b, (f & Toiletten.DOUCHE) != 0 ? "er is een douche" : "er is geen douche", null);
            if (!betalen(f).equals(betalen(wf))) regel(b, betalen(f), betalen(wf));
            if (!rolstoel(f).equals(rolstoel(wf))) regel(b, rolstoel(f), rolstoel(wf));
            if ((f & Toiletten.VERSCHOON) != (wf & Toiletten.VERSCHOON)) regel(b, (f & Toiletten.VERSCHOON) != 0 ? "met verschoontafel" : "geen verschoontafel", null);
            if ((f & Toiletten.KLANTEN) != (wf & Toiletten.KLANTEN)) regel(b, (f & Toiletten.KLANTEN) != 0 ? "alleen voor klanten" : "voor iedereen", null);
            if ((f & Toiletten.URINOIR) != (wf & Toiletten.URINOIR)) regel(b, (f & Toiletten.URINOIR) != 0 ? "alleen een urinoir" : "niet alleen een urinoir", null);
            if (!nu.optString("n").equals(was.optString("n"))) regel(b, "naam: " + leeg(nu.optString("n")), leeg(was.optString("n")));
            if (!nu.optString("u").equals(was.optString("u"))) regel(b, "openingstijden: " + leeg(nu.optString("u")), leeg(was.optString("u")));
        } else {
            boolean metToilet = !"toilet".equals(s) && !"douche".equals(s) && (f & Toiletten.TOILET) != 0;
            String e = eigenschappen(s, metToilet ? f & ~Toiletten.TOILET : f);
            b.append("Hier is een ").append(soortTekst(s)).append(metToilet ? " met toilet" : "");
            if (!e.isEmpty()) b.append(" (").append(e).append(')');
            b.append('.');
            if (!nu.optString("n").isEmpty()) b.append("\nNaam: ").append(nu.optString("n"));
            if (!nu.optString("u").isEmpty()) b.append("\nOpeningstijden: ").append(nu.optString("u"));
        }
        if (opmerking != null && !opmerking.trim().isEmpty()) b.append("\nOpmerking: ").append(Toiletten.kort(opmerking, 300));
        b.append("\n\nDoorgegeven met de app Tools (https://github.com/renevanes/renes-tools).");
        String t = b.toString();
        return t.length() > 2000 ? t.substring(0, 2000) : t;
    }

    private static String lidwoordloos(String s) { String t = soortTekst(s); return "openbaar toilet".equals(t) ? "openbare toilet" : t; }
    private static String leeg(String s) { return s == null || s.isEmpty() ? "(leeg)" : s; }
    private static void regel(StringBuilder b, String nu, String was) {
        b.append("\n- ").append(nu);
        if (was != null) b.append(" (stond: ").append(was).append(')');
    }
    /* taal-vast-eind */

    static String formulier(double la, double lo, String tekst) throws Exception {
        return "lat=" + String.format(Locale.ROOT, "%.6f", la) + "&lon=" + String.format(Locale.ROOT, "%.6f", lo)
                + "&text=" + URLEncoder.encode(tekst, "UTF-8");
    }

    static String browserUrl(double la, double lo) {
        return String.format(Locale.ROOT, "https://www.openstreetmap.org/note/new#map=19/%.6f/%.6f", la, lo);
    }

    static String notitieUrl(long id) { return "https://www.openstreetmap.org/note/" + id; }

    static long leesId(String json) throws Exception {
        JSONObject p = new JSONObject(json).optJSONObject("properties");
        long id = p == null ? 0 : p.optLong("id");
        if (id <= 0) throw new Exception(Taal.T("Onverwacht antwoord van OpenStreetMap"));
        return id;
    }

    static String leesStatus(String json) throws Exception {
        JSONObject p = new JSONObject(json).optJSONObject("properties");
        String s = p == null ? "" : p.optString("status");
        return "open".equals(s) || "closed".equals(s) || "hidden".equals(s) ? s : "open";
    }

    // ================= netwerk =================

    static long maak(double la, double lo, String tekst) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(API + ".json").openConnection();
        try {
            h.setConnectTimeout(20_000);
            h.setReadTimeout(30_000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setInstanceFollowRedirects(false);
            h.setRequestProperty("User-Agent", ToilettenScan.UA);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] body = formulier(la, lo, tekst).getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = h.getOutputStream()) { o.write(body); }
            int code = h.getResponseCode();
            if (code == 403) throw new Zone();
            if (code == 429) throw new Exception(Taal.T("Even te veel notities: probeer het later"));
            if (code != 200) throw new Exception("OpenStreetMap antwoordt met " + code);
            try (java.io.InputStream in = h.getInputStream()) { return leesId(new String(Facturen.lees(in, 200_000), StandardCharsets.UTF_8)); }
        } finally { h.disconnect(); }
    }

    static String status(long id) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(API + "/" + id + ".json").openConnection();
        try {
            h.setConnectTimeout(20_000);
            h.setReadTimeout(30_000);
            h.setRequestProperty("User-Agent", ToilettenScan.UA);
            int code = h.getResponseCode();
            if (code == 404 || code == 410) return "hidden";
            if (code != 200) throw new Exception("OpenStreetMap antwoordt met " + code);
            try (java.io.InputStream in = h.getInputStream()) { return leesStatus(new String(Facturen.lees(in, 500_000), StandardCharsets.UTF_8)); }
        } finally { h.disconnect(); }
    }

    // ================= met Android =================

    private static void checkObject(String t) throws Exception { new JSONObject(t); }

    static JSONObject gedeeld(Context c) {
        synchronized (Toiletten.LOCK) {
            try { return new JSONObject(JsonFileStore.read(Toiletten.gedeeldFile(c), ToilettenOsm::checkObject)); } catch (Throwable e) { return new JSONObject(); }
        }
    }

    static void bewaarGedeeld(Context c, JSONObject g) throws Exception {
        synchronized (Toiletten.LOCK) { JsonFileStore.write(Toiletten.gedeeldFile(c), g.toString(), ToilettenOsm::checkObject); }
    }

    static int vandaag(JSONObject g, long nu) {
        int n = 0;
        for (Iterator<String> it = g.keys(); it.hasNext(); ) { JSONObject o = g.optJSONObject(it.next()); if (o != null && nu - o.optLong("t") < 86_400_000L) n++; }
        return n;
    }

    /** Notitie maken en bewaren. Geeft {ok, id, url}, of {zone: true, url} (dan via de website). */
    static JSONObject deel(Context c, String k, String wat, double la, double lo, String tekst) throws Exception {
        if (!Facturen.in(wat, WAT)) throw new Exception(Taal.T("Onbekend"));
        long nu = System.currentTimeMillis();
        JSONObject g = gedeeld(c);
        if (vandaag(g, nu) >= MAX_PER_DAG) throw new Exception("Vandaag al " + MAX_PER_DAG + " keer doorgegeven: morgen weer");
        try {
            long id = maak(la, lo, tekst);
            synchronized (Toiletten.LOCK) {
                g = gedeeld(c);
                g.put(k + "|" + wat, new JSONObject().put("k", k).put("wat", wat).put("id", id).put("t", nu).put("status", "open")
                        .put("gecontroleerd", nu).put("la", la).put("lo", lo));
                bewaarGedeeld(c, g);
            }
            App.log(c, "TOILET", "doorgegeven aan OpenStreetMap");
            return new JSONObject().put("ok", true).put("id", id).put("url", notitieUrl(id));
        } catch (Zone z) {
            App.log(c, "TOILET", "OpenStreetMap: moderatiezone");
            return new JSONObject().put("zone", true).put("url", browserUrl(la, lo));
        }
    }

    /** Status van open notities bijwerken (hooguit één keer per dag per notitie, hooguit 20 per keer). */
    static void volg(Context c, long nu) {
        JSONObject g = gedeeld(c);
        int n = 0;
        boolean anders = false;
        for (Iterator<String> it = g.keys(); it.hasNext() && n < 20; ) {
            String k = it.next();
            JSONObject o = g.optJSONObject(k);
            if (o == null || !"open".equals(o.optString("status")) || nu - o.optLong("gecontroleerd") < 20 * 3_600_000L) continue;
            n++;
            try {
                String s = status(o.optLong("id"));
                o.put("gecontroleerd", nu);
                if (!s.equals(o.optString("status"))) { o.put("status", s).put("veranderd", nu); }
                anders = true;
            } catch (Throwable ignored) { }
        }
        if (!anders) return;
        try {
            synchronized (Toiletten.LOCK) {
                JSONObject nieuw = gedeeld(c);
                for (Iterator<String> it = g.keys(); it.hasNext(); ) { String k = it.next(); if (nieuw.has(k)) nieuw.put(k, g.get(k)); }
                bewaarGedeeld(c, nieuw);
            }
        } catch (Throwable ignored) { }
    }

    static boolean vergeet(Context c, String sleutel) throws Exception {
        synchronized (Toiletten.LOCK) {
            JSONObject g = gedeeld(c);
            boolean weg = g.remove(sleutel) != null;
            if (weg) bewaarGedeeld(c, g);
            return weg;
        }
    }
}
