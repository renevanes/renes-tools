package nl.rene.tools;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * De agenda's op de telefoon (CalendarContract). Een Google-agenda schrijft de agenda-app van de telefoon zelf
 * naar Google; Tools praat niet met Google. Afspraken zonder agenda blijven alleen in Tools.
 */
final class AgendaCal {
    private AgendaCal() { }

    static boolean lezen(Context c) { return Launcher.has(c, Manifest.permission.READ_CALENDAR); }
    static boolean schrijven(Context c) { return lezen(c) && Launcher.has(c, Manifest.permission.WRITE_CALENDAR); }

    /** Alle agenda's op de telefoon. Leeg zonder toestemming of als het lezen mislukt. */
    static JSONArray kalenders(Context c) {
        JSONArray a = new JSONArray();
        if (!lezen(c)) return a;
        try (Cursor cur = c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, new String[]{
                CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CALENDAR_COLOR},
                null, null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE")) {
            if (cur == null) return a;
            while (cur.moveToNext() && a.length() < 40) {
                long id = cur.getLong(0);
                if (id <= 0) continue;
                int level = cur.getInt(4);
                String type = cur.getString(3) == null ? "" : cur.getString(3);
                JSONObject k = new JSONObject();
                k.put("id", String.valueOf(id));
                k.put("naam", cur.getString(1) == null ? "" : cur.getString(1));
                k.put("account", cur.getString(2) == null ? "" : cur.getString(2));
                k.put("google", google(type));
                k.put("schrijf", level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR);
                k.put("kleur", kleur(cur, 5));
                a.put(k);
            }
        } catch (Throwable t) {
            App.log(c, "AGENDA", "agenda's: " + t.getClass().getSimpleName());
        }
        return a;
    }

    static JSONObject kalender(JSONArray lijst, String id) {
        if (lijst == null || id == null || id.isEmpty()) return null;
        for (int i = 0; i < lijst.length(); i++) {
            JSONObject k = lijst.optJSONObject(i);
            if (k != null && id.equals(k.optString("id"))) return k;
        }
        return null;
    }

    /**
     * Afspraken uit de gekozen agenda's die het bereik raken. uit = agenda-id's die uit staan (leeg = allemaal aan).
     * woorden beperkt de zoekopdracht (titel, plek, notitie); leeg = alles in het bereik.
     */
    static JSONArray instanties(Context c, String van, String tot, Set<String> uit, String[] woorden, int max) {
        JSONArray a = new JSONArray();
        if (!lezen(c) || !Agenda.dagOk(van) || !Agenda.dagOk(tot) || max <= 0) return a;
        JSONArray kals = kalenders(c);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < kals.length(); i++) {
            JSONObject k = kals.optJSONObject(i);
            if (k == null) continue;
            String id = k.optString("id");
            if (id.isEmpty() || (uit != null && uit.contains(id))) continue;
            ids.add(id);
        }
        if (ids.isEmpty()) return a;
        TimeZone z = Regio.zone(c);
        long begin = Regio.toMillis(z, Regio.dayAdd(van, -1), "00:00");
        long end = Regio.toMillis(z, Regio.dayAdd(tot, 2), "00:00");
        if (begin < 0 || end <= begin) return a;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, begin);
        ContentUris.appendId(b, end);
        StringBuilder sel = new StringBuilder(CalendarContract.Instances.CALENDAR_ID).append(" IN (");
        List<String> args = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sel.append(',');
            sel.append('?');
            args.add(ids.get(i));
        }
        sel.append(')');
        if (woorden != null) for (String w : woorden) {
            String like = like(w);
            if (like.isEmpty()) continue;
            sel.append(" AND (").append(CalendarContract.Instances.TITLE).append(" LIKE ? OR ")
                    .append(CalendarContract.Instances.DESCRIPTION).append(" LIKE ? OR ")
                    .append(CalendarContract.Instances.EVENT_LOCATION).append(" LIKE ?)");
            args.add(like); args.add(like); args.add(like);
        }
        try (Cursor cur = c.getContentResolver().query(b.build(), new String[]{
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.RRULE},
                sel.toString(), args.toArray(new String[0]), CalendarContract.Instances.BEGIN + " ASC")) {
            if (cur == null) return a;
            while (cur.moveToNext() && a.length() < max) {
                JSONObject it = vanRij(c, cur, kals);
                if (it != null) a.put(it);
            }
        } catch (Throwable t) {
            App.log(c, "AGENDA", "lezen: " + t.getClass().getSimpleName());
        }
        return a;
    }

    /** Eén afspraak uit de agenda van de telefoon. null = weg. {"fout":true} = niet kunnen kijken. */
    static JSONObject event(Context c, long id) {
        if (id <= 0) return null;
        if (!lezen(c)) return fout();
        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{
                CalendarContract.Events.TITLE, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.RRULE, CalendarContract.Events.DELETED,
                CalendarContract.Events.DURATION}, null, null, null)) {
            if (cur == null) return fout();
            if (!cur.moveToNext() || cur.getInt(8) == 1) return null;
            long begin = cur.getLong(3), end = cur.getLong(4);
            boolean all = cur.getInt(5) == 1;
            if (end <= begin) end = begin + duur(cur.getString(9), all);
            String[] dag = all ? Agenda.alleDag(begin, end) : null;
            String d, e, van = "", tot = "";
            if (all) {
                if (dag == null) return null;
                d = dag[0]; e = dag[1];
            } else {
                TimeZone z = Regio.zone(c);
                d = Regio.dayKey(z, begin);
                e = Regio.dayKey(z, Math.max(begin, end - 60_000L));
                van = hm(z, begin); tot = hm(z, end);
                if (e.compareTo(d) < 0) e = d;
            }
            String rule = cur.getString(7) == null ? "" : cur.getString(7);
            String kal = String.valueOf(cur.getLong(6));
            JSONObject k = kalender(kalenders(c), kal);
            JSONObject o = new JSONObject();
            o.put("titel", leeg(c, cur.getString(0))).put("plek", cur.getString(1) == null ? "" : cur.getString(1));
            o.put("notitie", cur.getString(2) == null ? "" : cur.getString(2));
            o.put("dag", d).put("eind", e).put("van", van).put("tot", tot).put("heleDag", all);
            o.put("herhaal", Agenda.herhaalCode(rule)).put("totDag", Agenda.totUitRrule(rule));
            o.put("kalender", kal).put("ext", id);
            if (k != null) {
                o.put("naam", k.optString("naam"));
                if (Agenda.kleurOk(k.optString("kleur"))) o.put("kleur", k.optString("kleur"));
            }
            return o;
        } catch (Throwable t) {
            App.log(c, "AGENDA", "afspraak: " + t.getClass().getSimpleName());
            return fout();
        }
    }

    /** Schrijf of werk bij. &gt;0 = id, -1 = mislukt, -2 = alleen-lezen, -3 = geen toestemming. */
    static long schrijf(Context c, JSONObject it) {
        if (it == null) return -1;
        if (!schrijven(c)) return -3;
        String kal = it.optString("kalender");
        JSONObject k = kalender(kalenders(c), kal);
        if (k == null || !k.optBoolean("schrijf")) return -2;
        try {
            ContentValues cv = waarden(c, it, kal);
            long ext = it.optLong("ext");
            if (ext > 0) {
                int n = c.getContentResolver().update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ext), cv, null, null);
                if (n > 0) return ext;
            }
            Uri uri = c.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, cv);
            return uri == null ? -1 : ContentUris.parseId(uri);
        } catch (Throwable t) {
            App.log(c, "AGENDA", "schrijven: " + t.getClass().getSimpleName());
            return -1;
        }
    }

    /** 1 = weg (of stond er al niet), 0 = geen toestemming, -1 = mislukt. */
    static int wis(Context c, long id) {
        if (id <= 0) return 1;
        if (!schrijven(c)) return 0;
        try {
            c.getContentResolver().delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null);
            return 1;
        } catch (Throwable t) {
            App.log(c, "AGENDA", "wissen: " + t.getClass().getSimpleName());
            return -1;
        }
    }

    private static ContentValues waarden(Context c, JSONObject it, String kal) {
        boolean all = it.optBoolean("heleDag");
        String dag = it.optString("dag"), eind = it.optString("eind");
        if (!Agenda.dagOk(eind) || eind.compareTo(dag) < 0) eind = dag;
        TimeZone z = all ? TimeZone.getTimeZone("UTC") : Regio.zone(c);
        long ds = Regio.toMillis(z, dag, all ? "00:00" : it.optString("van", "09:00"));
        long de = all ? Regio.toMillis(z, Regio.dayAdd(eind, 1), "00:00") : Regio.toMillis(z, eind, it.optString("tot", "10:00"));
        if (ds < 0) ds = System.currentTimeMillis();
        if (de <= ds) de = ds + (all ? 86_400_000L : 3_600_000L);
        ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Events.CALENDAR_ID, Long.parseLong(kal));
        cv.put(CalendarContract.Events.TITLE, it.optString("titel"));
        cv.put(CalendarContract.Events.EVENT_LOCATION, it.optString("plek"));
        cv.put(CalendarContract.Events.DESCRIPTION, it.optString("notitie"));
        cv.put(CalendarContract.Events.DTSTART, ds);
        cv.put(CalendarContract.Events.DTEND, de);
        cv.put(CalendarContract.Events.EVENT_TIMEZONE, z.getID());
        cv.put(CalendarContract.Events.ALL_DAY, all ? 1 : 0);
        cv.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED);
        cv.put(CalendarContract.Events.HAS_ALARM, 0);
        String rule = Agenda.rrule(it.optString("herhaal"), it.optString("totDag"), all);
        if (rule.isEmpty()) cv.putNull(CalendarContract.Events.RRULE);
        else cv.put(CalendarContract.Events.RRULE, rule);
        return cv;
    }

    private static JSONObject vanRij(Context c, Cursor cur, JSONArray kals) throws Exception {
        long begin = cur.getLong(1), end = cur.getLong(2);
        boolean all = cur.getInt(3) == 1;
        String dag, eind, van = "", tot = "";
        if (all) {
            String[] r = Agenda.alleDag(begin, end);
            if (r == null) return null;
            dag = r[0]; eind = r[1];
        } else {
            if (end <= begin) end = begin + 3_600_000L;
            TimeZone z = Regio.zone(c);
            dag = Regio.dayKey(z, begin);
            eind = Regio.dayKey(z, Math.max(begin, end - 60_000L));
            van = hm(z, begin); tot = hm(z, end);
            if (!Agenda.dagOk(dag)) return null;
            if (!Agenda.dagOk(eind) || eind.compareTo(dag) < 0) eind = dag;
        }
        long ext = cur.getLong(6);
        String kal = String.valueOf(cur.getLong(7));
        JSONObject k = kalender(kals, kal);
        String rule = cur.getString(9) == null ? "" : cur.getString(9);
        JSONObject o = new JSONObject();
        o.put("id", "x" + ext + "_" + dag.replace("-", ""));
        o.put("titel", leeg(c, cur.getString(0))).put("plek", cur.getString(4) == null ? "" : cur.getString(4));
        o.put("notitie", cur.getString(5) == null ? "" : cur.getString(5));
        o.put("dag", dag).put("eind", eind).put("van", van).put("tot", tot).put("heleDag", all);
        // herhaal leeg: een instantie is al één moment. reeks is alleen voor het bewerken van de hele reeks.
        o.put("herhaal", "").put("reeks", Agenda.herhaalCode(rule)).put("totDag", Agenda.totUitRrule(rule));
        o.put("kalender", kal).put("ext", ext).put("naam", k == null ? "" : k.optString("naam"));
        String kleur = kleur(cur, 8);
        if (!Agenda.kleurOk(kleur) && k != null) kleur = k.optString("kleur");
        if (Agenda.kleurOk(kleur)) o.put("kleur", kleur);
        return o;
    }

    private static String leeg(Context c, String titel) {
        return titel == null || titel.trim().isEmpty() ? Taal.t(c, "Zonder titel") : titel;
    }

    private static String hm(TimeZone z, long t) {
        Calendar k = Calendar.getInstance(z, Locale.ROOT);
        k.setTimeInMillis(t);
        int h = k.get(Calendar.HOUR_OF_DAY), m = k.get(Calendar.MINUTE);
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    private static long duur(String d, boolean all) {
        if (d == null) return all ? 86_400_000L : 3_600_000L;
        int dag = 0, uur = 0, min = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?)?").matcher(d);
        if (m.matches()) {
            if (m.group(1) != null) dag = Integer.parseInt(m.group(1));
            if (m.group(2) != null) uur = Integer.parseInt(m.group(2));
            if (m.group(3) != null) min = Integer.parseInt(m.group(3));
        }
        long ms = dag * 86_400_000L + uur * 3_600_000L + min * 60_000L;
        return ms > 0 ? ms : (all ? 86_400_000L : 3_600_000L);
    }

    private static String like(String w) {
        if (w == null) return "";
        String s = w.replace("%", "").replace("_", "").trim();
        return s.length() < 2 ? "" : "%" + s + "%";
    }

    private static String kleur(Cursor cur, int i) {
        if (cur.isNull(i)) return "";
        return String.format(Locale.ROOT, "#%06X", cur.getInt(i) & 0xFFFFFF);
    }

    private static boolean google(String type) {
        return "com.google".equals(type) || (type != null && type.startsWith("com.google."));
    }

    private static JSONObject fout() {
        try { return new JSONObject().put("fout", true); }
        catch (Exception e) { return new JSONObject(); }
    }
}
