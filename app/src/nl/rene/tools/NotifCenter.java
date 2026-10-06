package nl.rene.tools;

import android.Manifest;
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
    };

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
            o.put("allowed", nm.areNotificationsEnabled()).put("perm", permissionOk(c));
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
