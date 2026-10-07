package nl.rene.tools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.TelephonyManager;

/** Opt-in voor automatisch starten, met zichtbare terugval als Android de achtergrondstart weigert. */
final class AutoCallRecording {
    private static final int NOTICE = 4702;
    private static final String CHANNEL = "auto-call-recording";
    private AutoCallRecording() { }
    static boolean enabled(Context c) { return CallRecordings.prefs(c).getBoolean("auto", false); }
    static boolean ready(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                c.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }
    static String phoneState(Context c) {
        try {
            TelephonyManager t = c.getSystemService(TelephonyManager.class);
            if (t == null) return "";
            int state = t.getCallState();
            return state == TelephonyManager.CALL_STATE_OFFHOOK ? "OFFHOOK" : state == TelephonyManager.CALL_STATE_RINGING ? "RINGING" : "IDLE";
        } catch (Exception e) { return ""; }
    }
    static synchronized String setEnabled(Context c, boolean on) {
        if (on && !ready(c)) return "Geef eerst microfoon- en telefoontoegang en schakel meldingen in";
        if (on && enabled(c)) return "";
        String state = phoneState(c);
        if (on && state.isEmpty()) return "De mobiele telefoontoestand is niet beschikbaar";
        CallRecordings.prefs(c).edit().putBoolean("auto", on).putString("lastPhoneState", state).putBoolean("autoAttempted", "OFFHOOK".equals(state)).remove("autoSession").apply();
        cancelNotice(c);
        if (!on && CallRecorderService.busy && CallRecorderService.autoSession != 0) stop(c);
        return "";
    }
    static boolean sessionActive(Context c, long session) {
        return session != 0 && enabled(c) && CallRecordings.prefs(c).getLong("autoSession", 0) == session && "OFFHOOK".equals(phoneState(c));
    }
    static void cancelNotice(Context c) { c.getSystemService(NotificationManager.class).cancel(NOTICE); }
    static void stop(Context c) {
        try { c.startService(new Intent(c, CallRecorderService.class).setAction("stop")); } catch (Exception ignored) { }
    }
    static void blocked(Context c, long session, String message) {
        if (!sessionActive(c, session)) return;
        CallRecordings.prefs(c).edit().putString("message", message).apply();
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Automatische gespreksopname", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class).putExtra("open", "recorder").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(c, NOTICE, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        try {
            manager.notify(NOTICE, builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Automatische opname niet gestart")
                    .setContentText("Open Rene’s Tools om de opname handmatig te starten").setContentIntent(pending).setAutoCancel(true).build());
        } catch (SecurityException ignored) { }
    }
}
