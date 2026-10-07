package nl.rene.tools;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Map;

/**
 * Herinneringen bij notities: een melding op het gekozen tijdstip; tikken opent de notitie.
 * Meerdere per notitie, eventueel herhalend (elke dag, werkdagen, elke week, elke maand),
 * met knoppen in de melding: "Over 10 min", "Over 1 uur" en "Klaar".
 *
 * Opslag (prefs "reminders"): sleutel = herinnering-id (de notitie-id voor de eerste, anders notitie~n),
 * waarde = {t, title, note, rep}.
 */
final class Reminders {

    private Reminders() { }

    static final String ACTION = "nl.rene.tools.NOTE_REMINDER";
    static final String ACTION_SNOOZE = "nl.rene.tools.NOTE_REMINDER_SNOOZE";
    static final String ACTION_DONE = "nl.rene.tools.NOTE_REMINDER_DONE";
    static final String CHANNEL = "reminders";
    static final String[] REPEATS = {"", "day", "weekdays", "week", "month"};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("reminders", Context.MODE_PRIVATE); }

    static PendingIntent op(Context c, String rid) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION).putExtra("id", rid)
                .setData(android.net.Uri.fromParts("note", rid, null)); // uniek per herinnering
        return PendingIntent.getBroadcast(c, 5000, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void arm(Context c, String rid, long t) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op(c, rid));
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op(c, rid));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op(c, rid));
        }
    }

    static String noteOf(String rid, JSONObject o) {
        String n = o == null ? "" : o.optString("note");
        if (!n.isEmpty()) return n;
        int i = rid.indexOf('~');
        return i > 0 ? rid.substring(0, i) : rid;
    }

    static JSONObject get(Context c, String rid) {
        String raw = prefs(c).getString(rid, null);
        try { return raw == null ? null : new JSONObject(raw); } catch (Exception e) { return null; }
    }

    static void put(Context c, String rid, String note, String title, long t, String rep) {
        JSONObject old = get(c, rid);
        int dom = old != null && old.optInt("dom", 0) > 0 && rep != null && !rep.isEmpty() ? old.optInt("dom") : dayOfMonth(t);
        try {
            prefs(c).edit().putString(rid, new JSONObject().put("t", t).put("title", title == null ? "" : title)
                    .put("note", note).put("rep", rep == null ? "" : rep).put("dom", dom).toString()).apply();
        } catch (Exception ignored) { }
        arm(c, rid, t);
    }

    static void remove(Context c, String rid) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(op(c, rid));
        prefs(c).edit().remove(rid).apply();
    }

    /** Eerste herinnering van een notitie (oude aanroep): t = 0 haalt hem weg. */
    static void set(Context c, String id, String title, long t) {
        if (id == null || id.isEmpty()) return;
        if (t <= 0) { remove(c, id); return; }
        JSONObject old = get(c, id);
        put(c, id, id, title, t, old == null ? "" : old.optString("rep"));
    }

    /** Nieuwe herinnering bij een notitie. Geeft de herinnering-id. */
    static String add(Context c, String note, String title, long t, String rep) {
        if (note == null || note.isEmpty() || t <= 0) return "";
        if (!java.util.Arrays.asList(REPEATS).contains(rep == null ? "" : rep)) rep = "";
        String rid = note;
        for (int k = 2; prefs(c).contains(rid); k++) rid = note + "~" + k;
        put(c, rid, note, title, t, rep);
        return rid;
    }

    /** Titel bijwerken bij alle herinneringen van een notitie (na hernoemen). */
    static void retitle(Context c, String note, String title) {
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            JSONObject o = get(c, e.getKey());
            if (o == null || !note.equals(noteOf(e.getKey(), o)) || title.equals(o.optString("title"))) continue;
            try { prefs(c).edit().putString(e.getKey(), o.put("title", title).toString()).apply(); } catch (Exception ignored) { }
        }
    }

    /** Oud formaat: notitie → eerste tijdstip (voor het bolletje in de lijst en het overzicht). */
    static String all(Context c) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            JSONObject r = get(c, e.getKey());
            if (r == null) continue;
            String note = noteOf(e.getKey(), r);
            long t = r.optLong("t");
            try { if (!o.has(note) || o.optLong(note) > t) o.put(note, t); } catch (Exception ignored) { }
        }
        return o.toString();
    }

    /** Alle herinneringen van één notitie: [{id, t, rep}] op tijd. */
    static String forNote(Context c, String note) {
        java.util.List<JSONObject> l = new java.util.ArrayList<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            JSONObject r = get(c, e.getKey());
            if (r == null || !note.equals(noteOf(e.getKey(), r))) continue;
            try { l.add(new JSONObject().put("id", e.getKey()).put("t", r.optLong("t")).put("rep", r.optString("rep"))); } catch (Exception ignored) { }
        }
        java.util.Collections.sort(l, (a, b) -> Long.compare(a.optLong("t"), b.optLong("t")));
        return new JSONArray(l).toString();
    }

    /** Hoeveel herinneringen er vandaag nog komen (voor het overzicht). */
    static int count(Context c) { return prefs(c).getAll().size(); }

    static int dayOfMonth(long t) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(t); return c.get(Calendar.DAY_OF_MONTH); }

    static long next(long t, String rep, long after) { return next(t, rep, after, 0); }

    /** Volgende keer voor een herhaling, na 'after'. dom = gewenste dag van de maand (31 → laatste dag in korte maanden). */
    static long next(long t, String rep, long after, int dom) {
        if (rep == null || rep.isEmpty()) return 0;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        for (int guard = 0; guard < 2000 && cal.getTimeInMillis() <= after; guard++) {
            switch (rep) {
                case "day": cal.add(Calendar.DAY_OF_MONTH, 1); break;
                case "week": cal.add(Calendar.WEEK_OF_YEAR, 1); break;
                case "month":
                    cal.set(Calendar.DAY_OF_MONTH, 1);
                    cal.add(Calendar.MONTH, 1);
                    int want = dom > 0 ? dom : dayOfMonth(t);
                    cal.set(Calendar.DAY_OF_MONTH, Math.min(want, cal.getActualMaximum(Calendar.DAY_OF_MONTH)));
                    break;
                case "weekdays":
                    do { cal.add(Calendar.DAY_OF_MONTH, 1); }
                    while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY);
                    break;
                default: return 0;
            }
        }
        return cal.getTimeInMillis();
    }

    /** Na herstarten van de telefoon alles opnieuw plannen (gemiste meteen melden). */
    static void rearm(Context c) {
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            JSONObject r = get(c, e.getKey());
            if (r == null) continue;
            arm(c, e.getKey(), Math.max(r.optLong("t"), System.currentTimeMillis() + 5000));
        }
    }

    static int notifId(String rid) { return 7000 + (rid.hashCode() & 0xffff); }

    static void fire(Context c, String rid) {
        if (rid == null) return;
        JSONObject r = get(c, rid);
        if (r == null) return;
        String title = r.optString("title"), note = noteOf(rid, r), rep = r.optString("rep");
        long nxt = next(r.optLong("t"), rep, System.currentTimeMillis(), r.optInt("dom", 0));
        if (nxt > 0) put(c, rid, note, title, nxt, rep); else prefs(c).edit().remove(rid).apply();
        show(c, rid, note, title, nxt);
    }

    static void show(Context c, String rid, String note, String title, long nxt) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Herinneringen", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(c, MainActivity.class).putExtra("open", "note:" + note).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(android.net.Uri.fromParts("note", rid, null));
        PendingIntent pi = PendingIntent.getActivity(c, 6000 + (rid.hashCode() & 0xffff), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, CHANNEL)) : new Notification.Builder(c);
        String sub = nxt > 0 ? "Volgende keer: " + new java.text.SimpleDateFormat("EEE d MMM HH:mm", new java.util.Locale("nl", "NL")).format(new java.util.Date(nxt)) : "Herinnering uit je notities";
        b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title.isEmpty() ? "Herinnering" : title)
                .setContentText(sub).setAutoCancel(true).setContentIntent(pi)
                .setCategory(Notification.CATEGORY_REMINDER)
                .addAction(new Notification.Action.Builder(null, "Over 10 min", action(c, ACTION_SNOOZE, rid, note, title, 10)).build())
                .addAction(new Notification.Action.Builder(null, "Over 1 uur", action(c, ACTION_SNOOZE, rid, note, title, 60)).build())
                .addAction(new Notification.Action.Builder(null, "Klaar", action(c, ACTION_DONE, rid, note, title, 0)).build());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        NotifCenter.post(c, notifId(rid), b.build());
    }

    static PendingIntent action(Context c, String act, String rid, String note, String title, int minutes) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(act).putExtra("id", rid).putExtra("note", note)
                .putExtra("title", title).putExtra("min", minutes)
                .setData(android.net.Uri.fromParts("note", rid + "/" + act + "/" + minutes, null));
        return PendingIntent.getBroadcast(c, 5100, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Knop in de melding. Uitstellen maakt een losse herinnering; een herhaling loopt gewoon door. */
    static void onAction(Context c, Intent i) {
        String rid = i.getStringExtra("id"), note = i.getStringExtra("note"), title = i.getStringExtra("title");
        if (rid == null || note == null) return;
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(notifId(rid));
        if (ACTION_SNOOZE.equals(i.getAction())) {
            int min = Math.max(1, Math.min(24 * 60, i.getIntExtra("min", 10)));
            long t = System.currentTimeMillis() + min * 60_000L;
            // Bestaat de herinnering nog (herhaling, of intussen een nieuwe onder dezelfde id), dan nooit overschrijven: losse erbij
            if (get(c, rid) == null) put(c, rid, note, title, t, "");
            else add(c, note, title, t, "");
        }
    }
}
