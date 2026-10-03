package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Oproepen-backup: leest de eigen oproepgeschiedenis van de telefoon (inkomend, uitgaand,
 * gemist), toont die in de app en exporteert naar de gekozen backup-map: een herstelbaar
 * XML-bestand in het formaat van "SMS Backup & Restore", een CSV voor Excel en een leesbare
 * HTML-pagina. Alleen lezen; er wordt niets op de telefoon gewijzigd.
 */
final class Calls {

    private Calls() { }

    static final String PREFS = "calls";
    static final String DIR = "Oproepen backup";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static final class Call {
        String number, cachedName;
        long date;
        int duration, type, presentation = 1;
    }

    static List<Call> read(Context c, int limit) {
        List<Call> out = new ArrayList<>();
        String[] cols = {CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE,
                CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER_PRESENTATION};
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols, null, null, CallLog.Calls.DATE + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && (limit <= 0 || out.size() < limit)) {
                Call k = new Call();
                k.number = cur.getString(0);
                k.date = cur.isNull(1) ? 0 : cur.getLong(1);
                k.duration = cur.isNull(2) ? 0 : cur.getInt(2);
                k.type = cur.isNull(3) ? 0 : cur.getInt(3);
                k.cachedName = cur.getString(4);
                k.presentation = cur.isNull(5) ? 1 : cur.getInt(5);
                out.add(k);
            }
        }
        return out;
    }

    static int countTotal(Context c) {
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls._ID}, null, null, null)) {
            return cur == null ? 0 : cur.getCount();
        } catch (Exception e) { return 0; }
    }

    /** Groep van een oproeptype: in, out, missed of other. */
    static String kind(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return "in";
            case CallLog.Calls.OUTGOING_TYPE: return "out";
            case CallLog.Calls.MISSED_TYPE: return "missed";
            case 5: return "rejected";   // REJECTED_TYPE
            case 6: return "blocked";    // BLOCKED_TYPE
            case 4: return "voicemail";  // VOICEMAIL_TYPE
            default: return "other";
        }
    }

    static String label(int type) {
        switch (kind(type)) {
            case "in": return "Inkomend";
            case "out": return "Uitgaand";
            case "missed": return "Gemist";
            case "rejected": return "Geweigerd";
            case "blocked": return "Geblokkeerd";
            case "voicemail": return "Voicemail";
            default: return "Overig";
        }
    }

    static String name(Context c, Call k) {
        if (k.number == null || k.number.isEmpty() || k.presentation != 1) return "Afgeschermd nummer";
        String n = Sms.nameFor(c, k.number);
        if (n.equals(k.number) && k.cachedName != null && !k.cachedName.isEmpty()) return k.cachedName;
        return n;
    }

    static String dur(int s) {
        if (s <= 0) return "";
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ---------- weergave in de app ----------

    /** Oproepen als JSON, nieuwste eerst. filter: all, in, out, missed. */
    static String listJson(Context c, String filter, String q, int limit) throws Exception {
        List<Call> all = read(c, 0);
        String f = filter == null ? "all" : filter;
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        JSONArray arr = new JSONArray();
        int nIn = 0, nOut = 0, nMissed = 0;
        long secIn = 0, secOut = 0;
        for (Call k : all) {
            String kd = kind(k.type);
            if (kd.equals("in")) { nIn++; secIn += k.duration; }
            else if (kd.equals("out")) { nOut++; secOut += k.duration; }
            else if (kd.equals("missed") || kd.equals("rejected")) nMissed++;
            boolean match = f.equals("all") || kd.equals(f) || (f.equals("missed") && kd.equals("rejected"));
            if (!match || arr.length() >= limit) continue;
            String nm = name(c, k);
            if (!needle.isEmpty() && !nm.toLowerCase(Locale.ROOT).contains(needle)
                    && (k.number == null || !k.number.contains(needle))) continue;
            JSONObject o = new JSONObject();
            o.put("name", nm);
            o.put("number", k.presentation == 1 && k.number != null ? k.number : "");
            o.put("date", k.date);
            o.put("duration", k.duration);
            o.put("kind", kd);
            o.put("label", label(k.type));
            arr.put(o);
        }
        JSONObject r = new JSONObject();
        r.put("calls", arr);
        r.put("total", all.size());
        r.put("in", nIn).put("out", nOut).put("missed", nMissed);
        r.put("secIn", secIn).put("secOut", secOut);
        return r.toString();
    }

    // ---------- export ----------

    static String csv(String s) {
        if (s == null) return "";
        String t = s.replace("\r", " ").replace("\n", " ");
        // Voorkom dat Excel een veld als formule uitvoert.
        if (!t.isEmpty() && "=+-@".indexOf(t.charAt(0)) >= 0 && !t.matches("\\+?[0-9 ]+")) t = "'" + t;
        if (t.contains(";") || t.contains("\"") || t.contains(",")) t = "\"" + t.replace("\"", "\"\"") + "\"";
        return t;
    }

    /** Exporteert de oproepgeschiedenis: XML (herstelbaar), CSV en een leesbare HTML-pagina. */
    static int export(Context c, Sms.Progress p) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map (bij WhatsApp backup)");
        List<Call> all = read(c, 0);
        if (all.isEmpty()) throw new Exception("Geen oproepen gevonden op deze telefoon");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(DIR, true);

        List<String> names = new ArrayList<>(all.size());
        for (Call k : all) names.add(name(c, k));

        SimpleDateFormat rd = new SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.US);
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        SimpleDateFormat full = new SimpleDateFormat("EEE d MMM yyyy HH:mm", new Locale("nl", "NL"));
        String day = stamp.format(new Date());
        int total = all.size() * 3, step = 0;

        // 1. XML in het formaat van SMS Backup & Restore
        try (Writer w = Sms.open(dest, dir, "oproepen-" + day + ".xml", "text/xml")) {
            w.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n");
            w.write("<calls count=\"" + all.size() + "\" backup_set=\"renes-tools\" backup_date=\"" + System.currentTimeMillis() + "\">\n");
            for (int i = 0; i < all.size(); i++) {
                Call k = all.get(i);
                w.write("  <call number=\"" + Sms.esc(k.number == null ? "" : k.number) + "\" duration=\"" + k.duration
                        + "\" date=\"" + k.date + "\" type=\"" + k.type + "\" presentation=\"" + k.presentation
                        + "\" subscription_id=\"null\" post_dial_digits=\"\" subscription_component_name=\"null\""
                        + " readable_date=\"" + Sms.esc(rd.format(new Date(k.date)))
                        + "\" contact_name=\"" + (k.presentation == 1 && !names.get(i).equals(k.number) ? Sms.esc(names.get(i)) : "(Unknown)") + "\" />\n");
                if ((++step % 200) == 0) p.step(step, total);
            }
            w.write("</calls>\n");
        }

        // 2. CSV (puntkomma, met BOM zodat Excel UTF-8 herkent)
        try (Writer w = Sms.open(dest, dir, "oproepen-" + day + ".csv", "text/csv")) {
            w.write("﻿Datum;Richting;Naam;Nummer;Duur (sec);Duur\r\n");
            for (int i = 0; i < all.size(); i++) {
                Call k = all.get(i);
                w.write(iso.format(new Date(k.date)) + ";" + label(k.type) + ";" + csv(names.get(i)) + ";"
                        + (k.presentation == 1 && k.number != null && k.number.matches("\\+?[0-9 #*]+") ? "=\"" + k.number + "\""
                            : csv(k.presentation == 1 && k.number != null ? k.number : "")) + ";" + k.duration + ";" + dur(k.duration) + "\r\n");
                if ((++step % 200) == 0) p.step(step, total);
            }
        }

        // 3. Leesbare HTML-pagina
        try (Writer w = Sms.open(dest, dir, "oproepen.html", "text/html")) {
            w.write("<!DOCTYPE html><html lang=nl><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<title>Oproepen</title><style>" + CSS + "</style><header><h1>Oproepen</h1><small>Gemaakt door Rene's Tools op "
                    + Sms.esc(full.format(new Date())) + " · " + all.size() + " oproepen</small></header><main><table>"
                    + "<tr><th>Datum</th><th></th><th>Naam / nummer</th><th>Duur</th></tr>");
            for (int i = 0; i < all.size(); i++) {
                Call k = all.get(i);
                String kd = kind(k.type);
                String num = k.presentation == 1 && k.number != null && !names.get(i).equals(k.number) ? "<small>" + Sms.esc(k.number) + "</small>" : "";
                w.write("<tr class=" + kd + "><td>" + Sms.esc(full.format(new Date(k.date))) + "</td><td>" + Sms.esc(label(k.type))
                        + "</td><td>" + Sms.esc(names.get(i)) + num + "</td><td>" + dur(k.duration) + "</td></tr>");
                if ((++step % 200) == 0) p.step(step, total);
            }
            w.write("</table></main></html>");
        }

        p.step(total, total);
        prefs(c).edit().putLong("lastExport", System.currentTimeMillis()).putInt("lastCount", all.size()).apply();
        return all.size();
    }

    static final String CSS = "body{font:15px/1.4 system-ui,sans-serif;background:#f4f6f9;margin:0;color:#111}"
            + "header{background:#1E5AA8;color:#fff;padding:14px 18px;position:sticky;top:0}header h1{margin:0;font-size:19px}"
            + "header small{opacity:.85}main{max-width:900px;margin:0 auto;padding:12px}"
            + "table{width:100%;border-collapse:collapse;background:#fff;border-radius:9px;overflow:hidden}"
            + "th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #e6e9ee;vertical-align:top}th{background:#eef2f7;font-size:13px}"
            + "td small{display:block;color:#777}tr.in td:nth-child(2){color:#1a7f37}tr.out td:nth-child(2){color:#1E5AA8}"
            + "tr.missed td:nth-child(2),tr.rejected td:nth-child(2){color:#c62828;font-weight:600}";
}
