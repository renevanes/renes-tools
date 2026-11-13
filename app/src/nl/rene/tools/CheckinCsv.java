package nl.rene.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Check-in als CSV (puntkomma, met BOM, voor een rekenblad): één regel per antwoord en per opmerking, oudste eerst.
 * Zuiver (getest in CheckinCsvTest). Het bestand is leesbaar; de interface zegt dat vooraf en Rene kiest zelf de plek.
 * Tijden zijn machinale notatie (jjjj-mm-dd uu:mm) in de app-zone; koppen blijven Nederlands, net als bij Facturen.
 */
final class CheckinCsv {

    private CheckinCsv() { }

    static final String KOP = "Soort;Vraag;Periode;Tijdstip;Score;Antwoord;Labels;Notitie";

    /** Aantal regels (antwoorden en opmerkingen) dat in het bestand komt. */
    static int aantal(JSONArray answers, JSONArray remarks) { return (answers == null ? 0 : answers.length()) + (remarks == null ? 0 : remarks.length()); }

    static String csv(JSONArray questions, JSONArray answers, JSONArray remarks, TimeZone tz) {
        Map<String, String> vraag = new HashMap<>();
        if (questions != null) for (int i = 0; i < questions.length(); i++) { JSONObject q = questions.optJSONObject(i); if (q != null) vraag.put(q.optString("id"), q.optString("text")); }
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        df.setTimeZone(tz == null ? TimeZone.getTimeZone("UTC") : tz);
        List<Object[]> l = new ArrayList<>(); // {tijd, volgorde, regel}
        int nr = 0;
        if (answers != null) for (int i = 0; i < answers.length(); i++) {
            JSONObject a = answers.optJSONObject(i);
            if (a == null) continue;
            StringBuilder labels = new StringBuilder();
            JSONArray ls = a.optJSONArray("labels");
            if (ls != null) for (int k = 0; k < ls.length(); k++) { String s = ls.optString(k); if (!s.isEmpty()) labels.append(labels.length() > 0 ? ", " : "").append(s); }
            double v = a.optDouble("v");
            l.add(new Object[] { a.optLong("t"), nr++, regel("Antwoord", vraag.get(a.optString("q")), a.optString("p"), df.format(new Date(a.optLong("t"))), getal(v), a.optString("l"), labels.toString(), a.optString("note")) });
        }
        if (remarks != null) for (int i = 0; i < remarks.length(); i++) {
            JSONObject r = remarks.optJSONObject(i);
            if (r == null) continue;
            l.add(new Object[] { r.optLong("t"), nr++, regel("Opmerking", vraag.get(r.optString("q")), r.optString("p"), df.format(new Date(r.optLong("t"))), "", "", "", r.optString("text")) });
        }
        Collections.sort(l, (x, y) -> { int c = Long.compare((Long) x[0], (Long) y[0]); return c != 0 ? c : Integer.compare((Integer) x[1], (Integer) y[1]); });
        StringBuilder b = new StringBuilder("﻿").append(KOP).append("\r\n");
        for (Object[] x : l) b.append((String) x[2]).append("\r\n");
        return b.toString();
    }

    /** Een score zonder overbodige nullen, met een komma (zoals een Nederlands rekenblad hem leest); leeg als er geen is. */
    static String getal(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return Long.toString((long) v);
        return String.format(Locale.US, "%.2f", v).replaceAll("0+$", "").replace('.', ',');
    }

    private static String regel(String soort, String vraag, String periode, String tijd, String score, String antwoord, String labels, String notitie) {
        return soort + ';' + FacturenDossier.cel(vraag == null ? "(verwijderde vraag)" : vraag) + ';' + FacturenDossier.cel(periode) + ';' + tijd + ';' + score + ';'
                + FacturenDossier.cel(antwoord) + ';' + FacturenDossier.cel(labels) + ';' + FacturenDossier.cel(notitie);
    }
}
