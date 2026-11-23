package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Voorgronddienst van {@link Doorwerken}: houdt de app actief zolang er werk loopt dat Rene startte, met een stille melding
 * zonder inhoud ("Tools maakt iets af"). Stopt zichzelf zodra er niets meer loopt. Soort dataSync ("lokale bestanden
 * verwerken, importeren en exporteren, back-up en terugzetten"); Android 15+ stopt zo'n dienst na 6 uur per dag (onTimeout).
 */
public class DoorwerkDienst extends Service {
    private static volatile DoorwerkDienst actief;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        maakKanaal(this);
        // Altijd eerst de melding (Android eist dat binnen enkele seconden na het starten), dan pas kijken of er nog werk is.
        boolean ok = Fg.start(this, Doorwerken.NOTIF_ID, melding(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, 29);
        actief = this;
        if (!ok || Doorwerken.bezig() == 0) stop();
        return START_NOT_STICKY;
    }

    /** Het laatste werk is klaar (of de dienst mocht niet). */
    static void klaar() {
        DoorwerkDienst d = actief;
        if (d != null && Doorwerken.bezig() == 0) d.stop();
    }

    private void stop() {
        if (actief == this) actief = null;
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) { }
        stopSelf();
    }

    /** Android 15+: de tijd voor dataSync is op. Het werk loopt door zolang de app dat mag; de melding gaat weg. */
    @Override
    public void onTimeout(int startId, int fgsType) { stop(); }

    @Override
    public void onDestroy() { if (actief == this) actief = null; super.onDestroy(); }

    private Notification melding() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, Doorwerken.CHANNEL)) : new Notification.Builder(this);
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_notif).setContentTitle(Taal.t(this, "Tools maakt iets af"))
                .setContentText(Taal.t(this, "Je kunt de telefoon wegleggen; het gaat door met het scherm uit."))
                .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
                .setContentIntent(PendingIntent.getActivity(this, 4911, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    static void maakKanaal(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(Doorwerken.CHANNEL) != null) return;
            NotificationChannel ch = new NotificationChannel(Doorwerken.CHANNEL, Taal.t(c, "Bezig op de achtergrond"), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(Taal.t(c, "Zichtbaar zolang de app iets afmaakt wat je startte (een document herkennen, een backup, een export), ook met het scherm uit"));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
