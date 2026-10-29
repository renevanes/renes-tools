package nl.rene.tools;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

/**
 * Check-in: de meldingen. Eén wekker voor het eerstvolgende moment van alle vragen samen (ongeveer op tijd, zonder
 * toestemming voor exacte wekkers, zoals SleepReminder); als hij afgaat komen de meldingen die nu aan de beurt zijn,
 * verdwijnen de meldingen waarvan de tijd om is, en wordt hij opnieuw gezet. Elk moment geeft precies één melding:
 * er wordt nooit opnieuw herinnerd. Met 2 of 3 antwoorden antwoord je in de melding (één tik, via AlarmReceiver,
 * met het geheim van deze installatie zodat geen andere app antwoorden kan invullen); met meer opent de melding een
 * klein scherm met grote knoppen.
 *
 * Stand (prefs "checkin", niet in Android-backups): last = tot waar gekeken (alleen de wekker zet die verder),
 * done = {vraag: laatste periode met een melding}, posted = {vraag: {key, end, id}}. Wat wanneer gemeld wordt, staat
 * zuiver in Checkin (lookFrom, toNotify, doneOnChange, repostOnChange) en is daar getest.
 */
final class CheckinNotify {

    private CheckinNotify() { }

    static final String ACTION = "nl.rene.tools.CHECKIN_ALARM";
    static final String ACTION_ANSWER = "nl.rene.tools.CHECKIN_ANSWER";
    static final String CHANNEL = "checkin";
    /** Eigen reeksen (elders in de app niet gebruikt): wekker, melding per vraag, tik op de melding, knoppen. */
    static final int REQ_ALARM = 410_000, NOTIF_BASE = 410_000, REQ_OPEN = 420_000, REQ_ACT = 430_000;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("checkin", Context.MODE_PRIVATE); }

    /** Het vaste nummer van een vraag (1..9999), voor melding- en verzoekcodes in de eigen reeksen. */
    static int nr(JSONObject q) { return Math.max(1, Math.min(9999, q.optInt("n", 1))); }

    static int notifId(JSONObject q) { return NOTIF_BASE + nr(q); }

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, REQ_ALARM, new Intent(c, AlarmReceiver.class).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static JSONObject obj(String s) { try { return new JSONObject(s); } catch (Exception e) { return new JSONObject(); } }

    /** Vanaf waar de wekker kijkt (zie Checkin.lookFrom). */
    static long last(Context c, long now) { return Checkin.lookFrom(prefs(c).getLong("last", 0), now); }

    /** Het meldingnummer van een melding die er staat (zoals bewaard, anders uit de vraag). */
    private static int idOf(JSONObject posted) { return posted.optInt("id", NOTIF_BASE + Math.max(1, Math.min(9999, posted.optInt("n", 1)))); }

    /** Opnieuw plannen (na een wijziging, het afgaan, herstart of een andere tijd). */
    static synchronized void arm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long now = System.currentTimeMillis(), from = last(c, now), t = 0;
            TimeZone tz = TimeZone.getDefault();
            JSONArray qs = CheckinStore.questions(c);
            for (int i = 0; i < qs.length(); i++) {
                JSONObject q = qs.optJSONObject(i);
                if (!CheckinStore.active(q)) continue;
                long n = Checkin.next(CheckinStore.rule(q), from, tz);
                if (n > 0 && (t == 0 || n < t)) t = n;
            }
            JSONObject posted = obj(prefs(c).getString("posted", "{}"));
            for (Iterator<String> it = posted.keys(); it.hasNext(); ) {
                long e = posted.optJSONObject(it.next()).optLong("end");
                if (e > 0 && (t == 0 || e < t)) t = e;
            }
            if (t == 0) { am.cancel(alarm(c)); return; }
            // Binnen tien minuten na het moment (Android mag het combineren met andere wekkers)
            am.setWindow(AlarmManager.RTC_WAKEUP, Math.max(t, now + 1000), 10 * 60_000L, alarm(c));
        } catch (Throwable e) { App.log(c, "CHECKIN", "plannen: " + e); }
    }

    /** De wekker ging af: meldingen die nu aan de beurt zijn, en weg wat te laat is. */
    static void fire(Context c) {
        try { synchronized (CheckinNotify.class) { due(c, System.currentTimeMillis()); } }
        catch (Throwable e) { App.log(c, "CHECKIN", "melding: " + e); }
        arm(c);
    }

    private static void due(Context c, long now) throws Exception {
        SharedPreferences p = prefs(c);
        long from = last(c, now);
        TimeZone tz = TimeZone.getDefault();
        JSONObject done = obj(p.getString("done", "{}")), posted = obj(p.getString("posted", "{}"));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        // Te laat: de melding weg (op Android 8+ gebeurt dat ook vanzelf)
        for (Iterator<String> it = posted.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject x = posted.optJSONObject(id);
            if (x == null || x.optLong("end") <= now) { if (x != null && nm != null) nm.cancel(idOf(x)); it.remove(); }
        }
        JSONArray qs = CheckinStore.questions(c);
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.optJSONObject(i);
            if (!CheckinStore.active(q)) continue;
            String id = q.optString("id");
            Checkin.Slot pick = Checkin.toNotify(CheckinStore.rule(q), from, now, done.optString(id), tz);
            if (pick == null) continue;
            done.put(id, pick.key); // één melding per moment, ook als hij niet getoond hoeft te worden
            List<JSONObject> have = CheckinStore.byKey(c, id).get(pick.key);
            if (have != null && !have.isEmpty()) continue; // al ingevuld in de app
            if (NotifCenter.muted(c)) continue;
            post(c, q, pick, now);
            posted.put(id, new JSONObject().put("key", pick.key).put("end", pick.end).put("id", notifId(q)));
        }
        p.edit().putLong("last", now).putString("done", done.toString()).putString("posted", posted.toString()).apply();
    }

    /**
     * Na het bewaren van een vraag: de periode die nu al loopt telt als gemeld, zodat het aanzetten (of wijzigen) niet
     * meteen een melding geeft. Daarna opnieuw plannen.
     */
    static synchronized void changed(Context c, String qid) {
        try {
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            JSONObject q = qid == null ? null : CheckinStore.question(c, qid);
            SharedPreferences p = prefs(c);
            JSONObject done = obj(p.getString("done", "{}")), posted = obj(p.getString("posted", "{}"));
            SharedPreferences.Editor e = p.edit();
            if (q != null) {
                String k = Checkin.doneOnChange(CheckinStore.rule(q), now, tz);
                if (k != null) done.put(qid, k);
                // Staat er een melding van deze vraag: opnieuw tonen met de nieuwe tekst en knoppen, of weghalen
                JSONObject x = posted.optJSONObject(qid);
                if (x != null) {
                    Checkin.Slot s = Checkin.find(CheckinStore.rule(q), x.optString("key"), now, tz);
                    if (Checkin.repostOnChange(CheckinStore.active(q), s, now) && !NotifCenter.muted(c)) {
                        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null && idOf(x) != notifId(q)) nm.cancel(idOf(x));
                        post(c, q, s, now);
                        posted.put(qid, new JSONObject().put("key", s.key).put("end", s.end).put("id", notifId(q)));
                        e.putString("posted", posted.toString());
                    } else dismiss(c, qid, null);
                }
            } else if (qid != null) { done.remove(qid); dismiss(c, qid, null); }
            e.putString("done", done.toString());
            // "last" blijft zoals hij is: alleen de wekker zet die verder (anders vallen gemiste momenten van andere
            // vragen weg als je bewaart terwijl de wekker nog moet komen). Nog nooit gekeken: vanaf nu.
            if (p.getLong("last", 0) <= 0) e.putLong("last", now);
            e.apply();
        } catch (Throwable ex) { App.log(c, "CHECKIN", "gewijzigd: " + ex); }
        arm(c);
    }

    /** Beantwoord (of weg): de melding van die vraag weg (key = null: welke periode ook). */
    static synchronized void dismiss(Context c, String qid, String key) {
        try {
            SharedPreferences p = prefs(c);
            JSONObject posted = obj(p.getString("posted", "{}")), x = posted.optJSONObject(qid);
            if (x == null || (key != null && !key.equals(x.optString("key")))) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(idOf(x));
            posted.remove(qid);
            p.edit().putString("posted", posted.toString()).apply();
        } catch (Throwable ignored) { }
    }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Check-in", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("De vraag op het moment dat je koos, met knoppen om meteen te antwoorden");
            nm.createNotificationChannel(ch);
        }
    }

    private static Notification.Builder builder(Context c) {
        channel(c);
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, CHANNEL)) : new Notification.Builder(c);
    }

    private static PendingIntent open(Context c, JSONObject q, String key) {
        Intent i = new Intent(c, MainActivity.class).putExtra("open", "checkin:" + q.optString("id") + ":" + key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.fromParts("checkin", q.optString("id") + "/" + key, null)); // uniek per vraag
        return PendingIntent.getActivity(c, REQ_OPEN + nr(q), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** De melding van een vraag. Met 2 of 3 antwoorden: een knop per antwoord. */
    static void post(Context c, JSONObject q, Checkin.Slot s, long now) {
        try {
            JSONArray opts = q.optJSONArray("options");
            int n = opts == null ? 0 : opts.length();
            String title = CheckinStore.qTitle(q);
            Notification.Builder b = builder(c);
            b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title)
                    .setContentText(n <= 3 ? "Tik op je antwoord" : "Tik om te antwoorden")
                    .setAutoCancel(true).setContentIntent(open(c, q, s.key)).setCategory(Notification.CATEGORY_REMINDER)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(builder(c).setSmallIcon(R.drawable.ic_notif).setContentTitle("Check-in").setContentText("Er is een vraag voor je").build());
            if (n <= 3) for (int i = 0; i < n; i++) {
                JSONObject o = opts.getJSONObject(i);
                String label = (o.optString("emoji") + " " + o.optString("label")).trim();
                // Met de waarde erbij: is de vraag intussen gewijzigd, dan telt een oude knop niet (zie onAnswer)
                Intent a = new Intent(c, AlarmReceiver.class).setAction(ACTION_ANSWER).putExtra("q", q.optString("id")).putExtra("p", s.key)
                        .putExtra("o", i).putExtra("v", o.optDouble("value")).putExtra("tok", App.token(c))
                        .setData(Uri.fromParts("checkin", q.optString("id") + "/" + s.key + "/" + i, null));
                PendingIntent pi = PendingIntent.getBroadcast(c, REQ_ACT + nr(q) * 4 + i, a, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification.Action.Builder ab = new Notification.Action.Builder(null, label, pi);
                if (Build.VERSION.SDK_INT >= 31) ab.setAuthenticationRequired(true); // niet te tikken op een vergrendelde telefoon
                b.addAction(ab.build());
            }
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(Math.max(60_000L, s.end - now));
            NotifCenter.post(c, notifId(q), b.build());
        } catch (Throwable e) { App.log(c, "CHECKIN", "melding maken: " + e); }
    }

    /** "Melding proberen": nu de melding van de periode die loopt (of van vandaag). "" of een melding. */
    static String preview(Context c, String qid) {
        JSONObject q = CheckinStore.question(c, qid);
        if (q == null) return "Deze vraag bestaat niet meer";
        if (NotifCenter.muted(c)) return "Alle meldingen van de app staan uit (Instellingen → Meldingen)";
        if (!NotifCenter.permissionOk(c)) return "notif";
        long now = System.currentTimeMillis();
        Checkin.Slot s = Checkin.current(CheckinStore.rule(q), now, TimeZone.getDefault());
        if (s == null) return "Er is nu geen periode om te beantwoorden";
        post(c, q, s, now);
        try {
            SharedPreferences p = prefs(c);
            JSONObject posted = obj(p.getString("posted", "{}"));
            posted.put(qid, new JSONObject().put("key", s.key).put("end", Math.max(s.end, now + 60_000L)).put("id", notifId(q)));
            p.edit().putString("posted", posted.toString()).apply();
        } catch (Exception ignored) { }
        arm(c);
        return "";
    }

    /** Een knop in de melding (AlarmReceiver): het antwoord bewaren zonder de app te openen. */
    static void onAnswer(Context c, Intent i) {
        if (!App.tokenOk(c, i)) return; // alleen vanuit onze eigen melding
        String qid = i.getStringExtra("q"), key = i.getStringExtra("p");
        int o = i.getIntExtra("o", -1);
        double v = i.getDoubleExtra("v", Double.NaN);
        if (qid == null || key == null || !Checkin.validKey(key)) return;
        JSONObject q = CheckinStore.question(c, qid);
        String title = "Check-in", text;
        boolean ok = false;
        JSONObject op0 = q == null ? null : CheckinStore.option(q, o);
        try {
            if (q != null && (op0 == null || Double.isNaN(v) || Math.abs(op0.optDouble("value") - v) > 1e-9)) text = "De vraag is gewijzigd; open de app"; else {
            JSONObject r = CheckinStore.answer(c, new JSONObject().put("q", qid).put("p", key).put("o", o).put("src", "notif"), System.currentTimeMillis());
            if (r.has("need")) text = "Was al ingevuld: " + r.optString("old");
            else if (r.has("error")) text = r.optString("error");
            else {
                ok = true;
                JSONObject op = q == null ? null : CheckinStore.option(q, o);
                title = "✓ Bewaard";
                text = (op == null ? "" : (op.optString("emoji") + " " + op.optString("label")).trim() + " · ") + (q == null ? "" : q.optString("text"));
            } }
        } catch (Exception e) { text = "Bewaren lukt niet"; App.log(c, "CHECKIN", "antwoord uit melding: " + e); }
        // Even een bevestiging in plaats van de vraag (zelfde melding, zonder geluid; verdwijnt vanzelf). Op oudere
        // Android gaat de melding gewoon weg.
        boolean replaced = false;
        if (q != null && Build.VERSION.SDK_INT >= 26 && !NotifCenter.muted(c)) try {
            Notification.Builder b = builder(c).setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
                    .setOnlyAlertOnce(true).setAutoCancel(true).setTimeoutAfter(ok ? 4000 : 8000).setContentIntent(open(c, q, key))
                    .setVisibility(Notification.VISIBILITY_PRIVATE);
            NotifCenter.post(c, notifId(q), b.build());
            replaced = true;
        } catch (Throwable ignored) { }
        if (replaced) forget(c, qid); else dismiss(c, qid, null);
        MainActivity.jsAll("onCheckin", "{}");
    }

    /** Niet meer bijhouden (de melding zelf blijft, bijv. de bevestiging die vanzelf verdwijnt). */
    private static synchronized void forget(Context c, String qid) {
        try {
            JSONObject posted = obj(prefs(c).getString("posted", "{}"));
            posted.remove(qid);
            prefs(c).edit().putString("posted", posted.toString()).apply();
        } catch (Throwable ignored) { }
    }
}
