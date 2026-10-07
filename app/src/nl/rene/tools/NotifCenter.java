package nl.rene.tools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Overzicht van alle soorten meldingen van Rene's Tools, met dezelfde namen als in Android.
 * Zo zie je in de app in één lijst wat aan en uit staat, en tik je door naar precies die soort in Android
 * (een app mag zelf geen soort aan- of uitzetten; dat beslis jij in Android).
 */
final class NotifCenter {

    private NotifCenter() { }

    /** id, naam, uitleg, standaard-belangrijkheid. Zelfde id's als in de diensten zelf. */
    static final String[][] KINDS = {
        {Reminders.CHANNEL, "Herinneringen", "Herinneringen die je bij een notitie zet", "4"},
        {RedialPlan.CHANNEL, "Auto redial op tijd", "Als Auto redial op het geplande moment begint", "4"},
        {Auto.CHANNEL, "Automatiseringen", "Vragen en meldingen van je automatiseringen, zoals parkeren starten", "4"},
        {Care.CHANNEL, "Onderhoud (backup-controle, batterij)", "Maandelijkse backup-controle en als Android de app op de achtergrond beperkt", "3"},
        {WaBackupService.CHANNEL, "WhatsApp backup", "Voortgang en resultaat van de WhatsApp-backup, en als een nachtelijke backup mislukt", "2"},
        {RedialService.CHANNEL, "Auto redial", "Voortgang van Auto redial", "2"},
        {RadioService.CHANNEL, "Radio", "Bediening van de radio (afspelen, pauzeren, stoppen)", "2"},
        {TracksService.CHANNEL, "Route opnemen", "Toont de voortgang tijdens het opnemen van een route", "2"},
        {TranscribeService.CHANNEL, "Gesprekken uitschrijven", "Voortgang van het uitschrijven", "2"},
        {Car.CHANNEL, "Mijn auto", "Als een rit niet vanzelf kon starten", "3"},
        {"auto-call-recording", "Automatische gespreksopname", "Als een automatische opname niet kon starten", "3"},
        {"call-recording", "Gespreksopname", "Zichtbaar zolang je opneemt, met een stopknop. Blijft ook zichtbaar als alle meldingen uit staan", "2"},
    };

    /** Stil-kanaal: zonder geluid, trillen, pop-up, icoon in de statusbalk of stip. Alleen gebruikt als alles uit staat. */
    static final String QUIET = "quiet";

    static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("notifcenter", Context.MODE_PRIVATE); }

    /** Alle meldingen van deze app uit (de app-eigen schakelaar, los van Android). */
    static boolean muted(Context c) { return prefs(c).getBoolean("muted", false); }

    /**
     * Alles uit/aan. Bij uit verdwijnen ook de meldingen die er al staan.
     * Een dienst die nú draait (radio, route opnemen, Auto redial, backup, uitschrijven) moet van Android een melding tonen;
     * die gaat dan via het stille kanaal: geen geluid, geen pop-up, geen icoon in de statusbalk.
     */
    static void setMuted(Context c, boolean on) {
        prefs(c).edit().putBoolean("muted", on).apply();
        if (!on) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) {
                for (android.service.notification.StatusBarNotification sb : nm.getActiveNotifications())
                    if ((sb.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) == 0) nm.cancel(sb.getTag(), sb.getId());
            }
        } catch (Exception ignored) { }
    }

    /** Kanaal voor een melding: het eigen kanaal, of het stille als alles uit staat. */
    static String ch(Context c, String id) {
        if (Build.VERSION.SDK_INT < 26 || !muted(c)) return id;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(QUIET) == null) {
                NotificationChannel q = new NotificationChannel(QUIET, "Stil (alle meldingen uit in de app)", NotificationManager.IMPORTANCE_MIN);
                q.setDescription("Alleen voor wat Android verplicht toont terwijl iets draait, zoals de radio");
                q.setSound(null, null); q.enableVibration(false); q.enableLights(false); q.setShowBadge(false);
                q.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
                nm.createNotificationChannel(q);
            }
            return QUIET;
        } catch (Exception e) { return id; }
    }

    /** Losse melding plaatsen, tenzij alles uit staat. */
    static void post(Context c, int id, Notification n) {
        if (muted(c)) return;
        try { ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, n); } catch (Exception ignored) { }
    }

    /** Alle soorten aanmaken (met nette namen en uitleg), zodat ze allemaal in Android te zien en in te stellen zijn. */
    static void ensureAll(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            for (String[] k : KINDS) {
                // Alleen aanmaken wat er nog niet is: de diensten zelf houden naam en uitleg bij, en de
                // belangrijkheid blijft zoals jij hem in Android hebt gezet
                if (nm.getNotificationChannel(k[0]) != null) continue;
                NotificationChannel ch = new NotificationChannel(k[0], k[1], Integer.parseInt(k[3]));
                ch.setDescription(k[2]);
                nm.createNotificationChannel(ch);
            }
        } catch (Exception ignored) { }
    }

    static boolean permissionOk(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** {allowed, perm, kinds:[{id, name, desc, on, level}]}; level: 0 uit, 1 stil, 2 laag, 3 normaal, 4 met geluid/pop-up. */
    static String state(Context c) {
        JSONObject o = new JSONObject();
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            ensureAll(c);
            o.put("muted", muted(c)).put("allowed", nm.areNotificationsEnabled()).put("perm", permissionOk(c));
            JSONArray a = new JSONArray();
            for (String[] k : KINDS) {
                int level = Integer.parseInt(k[3]);
                if (Build.VERSION.SDK_INT >= 26) {
                    NotificationChannel ch = nm.getNotificationChannel(k[0]);
                    if (ch != null) level = ch.getImportance();
                }
                a.put(new JSONObject().put("id", k[0]).put("name", k[1]).put("desc", k[2]).put("on", level > 0).put("level", Math.max(0, Math.min(level, 4))));
            }
            o.put("kinds", a);
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** Android-scherm van één soort (of van alle meldingen van de app als id leeg is). */
    static Intent settingsIntent(Context c, String id) {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent i = id == null || id.isEmpty()
                    ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    : new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, id);
            return i.putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        }
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", c.getPackageName(), null));
    }
}
