package nl.rene.tools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.TelephonyManager;

/** Het systeem meldt mobiele oproepen; VoIP-apps worden niet gevolgd. */
public final class CallRecordingReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (intent == null) return;
        SharedPreferences prefs = CallRecordings.prefs(c);
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Herstarten begint nooit zelf een opname en ruimt een oude oproepovergang op.
            prefs.edit().putString("lastPhoneState", AutoCallRecording.phoneState(c)).putBoolean("autoAttempted", "OFFHOOK".equals(AutoCallRecording.phoneState(c))).remove("autoSession").apply();
            AutoCallRecording.cancelNotice(c);
            return;
        }
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        // Controleer de echte telefoontoestand; een expliciet intent mag geen opname uitlokken.
        if (!AutoRecordingPolicy.valid(state) || !state.equals(AutoCallRecording.phoneState(c))) return;
        String before = prefs.getString("lastPhoneState", "");
        int action = AutoRecordingPolicy.action(before, state, AutoCallRecording.enabled(c), AutoCallRecording.ready(c),
                CallRecorderService.busy, CallRecorderService.autoSession != 0, prefs.getBoolean("autoAttempted", false));
        prefs.edit().putString("lastPhoneState", state).apply();
        if ("IDLE".equals(state)) {
            prefs.edit().remove("autoSession").putBoolean("autoAttempted", false).apply();
            AutoCallRecording.cancelNotice(c);
            if (action == AutoRecordingPolicy.STOP) AutoCallRecording.stop(c);
            return;
        }
        if (action != AutoRecordingPolicy.START) return;
        long session = System.currentTimeMillis();
        prefs.edit().putLong("autoSession", session).putBoolean("autoAttempted", true).apply();
        if ("recording".equals(Music.state)) {
            AutoCallRecording.blocked(c, session, "Automatische opname niet gestart: de microfoon wordt gebruikt voor muziekherkenning.");
            return;
        }
        if (!CallRecorderService.reserveAutomatic(session)) return;
        Intent start = new Intent(c, CallRecorderService.class).putExtra("autoSession", session);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(start); else c.startService(start);
        } catch (Exception e) {
            CallRecorderService.busy = false; CallRecorderService.autoSession = 0;
            AutoCallRecording.blocked(c, session, "Android blokkeert automatisch opnemen vanuit de achtergrond. Open de app en start handmatig.");
        }
    }
}
