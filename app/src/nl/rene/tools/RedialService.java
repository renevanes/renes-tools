package nl.rene.tools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.CallLog;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import org.json.JSONObject;

/**
 * Auto redial: belt een nummer opnieuw tot er wordt opgenomen of het maximum
 * aantal pogingen is bereikt. Draait als voorgrondservice zodat hij doorloopt
 * terwijl het belscherm van de telefoon op de voorgrond staat.
 */
public class RedialService extends Service {

    public static final String ACTION_START = "nl.rene.tools.redial.START";
    public static final String ACTION_STOP = "nl.rene.tools.redial.STOP";
    public static final String ACTION_NOW = "nl.rene.tools.redial.NOW";

    static final String PREFS = "redial";
    static final String CHANNEL = "redial";
    static final int NOTIF_ID = 1001;
    static final int DONE_NOTIF_ID = 1002;

    /** Zonder toegang tot de oproepgeschiedenis: gesprek langer dan dit telt als opgenomen. */
    static final long FALLBACK_ANSWERED_MS = 60_000;
    /** Als de telefoon binnen deze tijd niet gaat bellen, telt de poging als mislukt. */
    static final long DIAL_TIMEOUT_MS = 20_000;

    /** Leeft de service nog? (Status in de voorkeuren kan achterblijven als het proces stopt.) */
    static volatile boolean alive = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TelephonyManager tm;
    private Object callback; // TelephonyCallback (API 31+) of PhoneStateListener
    private PowerManager.WakeLock wake;

    // instellingen van de lopende sessie
    private String number = "", name = "";
    private int maxAttempts = 10;      // 0 = onbeperkt
    private int intervalSec = 10;     // vaste wachttijd
    private int randomMin = 0, randomMax = 0; // > 0: willekeurige wachttijd tussen min en max
    private int waitSec = 10;         // wachttijd van de huidige pauze
    private final java.util.Random rnd = new java.util.Random();
    private boolean stopWhenAnswered = true;
    private boolean speaker = false;

    // toestand
    private boolean running = false;
    private int attempt = 0;
    private String phase = "idle";     // idle, dialing, incall, checking, waiting, done
    private String result = "";        // answered, maxed, stopped, error
    private String message = "";
    private long attemptStart = 0, offhookAt = 0, nextAt = 0;
    private boolean sawOffhook = false;
    private int lastState = TelephonyManager.CALL_STATE_IDLE;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;
        tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        createChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(a)) {
            number = intent.getStringExtra("number");
            name = intent.getStringExtra("name");
            if (name == null) name = "";
            maxAttempts = intent.getIntExtra("attempts", 10);
            intervalSec = Math.max(3, intent.getIntExtra("interval", 10));
            randomMin = Math.max(0, intent.getIntExtra("randomMin", 0));
            randomMax = Math.max(0, intent.getIntExtra("randomMax", 0));
            if (randomMin > 0) {
                randomMin = Math.max(3, randomMin);
                if (randomMax < randomMin) randomMax = randomMin;
            }
            stopWhenAnswered = intent.getBooleanExtra("stopWhenAnswered", true);
            speaker = intent.getBooleanExtra("speaker", false);
            goForeground();
            begin();
        } else if (ACTION_STOP.equals(a)) {
            finish("stopped", "Gestopt");
        } else if (ACTION_NOW.equals(a)) {
            if (running && "waiting".equals(phase)) { h.removeCallbacksAndMessages(null); dial(); }
        } else if (!running) {
            // Door het systeem herstart zonder opdracht: niets te doen.
            stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    private void goForeground() {
        Notification n = buildNotification("Auto redial start…", false);
        Fg.start(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, 34);
    }

    private void begin() {
        h.removeCallbacksAndMessages(null);
        running = true;
        attempt = 0;
        result = "";
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(DONE_NOTIF_ID);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (wake == null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RenesTools:redial");
            wake.setReferenceCounted(false);
        }
        wake.acquire(6 * 60 * 60 * 1000L);
        registerCallState();
        dial();
    }

    // ---------- bellen ----------

    private void dial() {
        if (!running) return;
        if (lastState != TelephonyManager.CALL_STATE_IDLE) {
            // Er loopt al een gesprek (bijv. inkomend). Even wachten.
            phase = "waiting";
            message = "Wacht tot het huidige gesprek klaar is";
            nextAt = System.currentTimeMillis() + 3000;
            save();
            h.postDelayed(this::dial, 3000);
            return;
        }
        attempt++;
        phase = "dialing";
        message = "Bellen…";
        sawOffhook = false;
        attemptStart = System.currentTimeMillis();
        save();
        try {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                finish("error", "Geen toestemming om te bellen");
                return;
            }
            TelecomManager telecom = (TelecomManager) getSystemService(TELECOM_SERVICE);
            Bundle extras = new Bundle();
            if (speaker) extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true);
            telecom.placeCall(Uri.fromParts("tel", number, null), extras);
        } catch (Exception e) {
            finish("error", "Bellen lukt niet: " + e.getMessage());
            return;
        }
        final int thisAttempt = attempt;
        h.postDelayed(() -> {
            if (running && attempt == thisAttempt && "dialing".equals(phase) && !sawOffhook) {
                // Telefoon is niet gaan bellen (geen bereik, geweigerd, …)
                afterCall(false, "Bellen is niet gestart");
            }
        }, DIAL_TIMEOUT_MS);
    }

    private void onCallState(int state) {
        int prev = lastState;
        lastState = state;
        if (!running) return;
        if (state == TelephonyManager.CALL_STATE_OFFHOOK && "dialing".equals(phase)) {
            sawOffhook = true;
            offhookAt = System.currentTimeMillis();
            phase = "incall";
            message = "Gaat over…";
            save();
        } else if (state == TelephonyManager.CALL_STATE_IDLE && prev != TelephonyManager.CALL_STATE_IDLE
                && "incall".equals(phase)) {
            final long dur = System.currentTimeMillis() - offhookAt;
            phase = "checking";
            message = "Controleren of er is opgenomen…";
            save();
            checkAnswered(dur, 0);
        }
    }

    /** Kijkt in de oproepgeschiedenis of het laatste uitgaande gesprek verbonden was. */
    private void checkAnswered(final long offhookDur, final int tries) {
        h.postDelayed(() -> {
            if (!running) return;
            Boolean answered = null;
            if (hasCallLog(this)) answered = callLogAnswered();
            if (answered == null && hasCallLog(this) && tries < 4) {
                checkAnswered(offhookDur, tries + 1); // oproeplog nog niet bijgewerkt
                return;
            }
            if (answered == null) answered = offhookDur >= FALLBACK_ANSWERED_MS;
            afterCall(answered, null);
        }, 1200);
    }

    private Boolean callLogAnswered() {
        Cursor c = null;
        try {
            c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.DURATION, CallLog.Calls.DATE},
                    CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.DATE + ">=?",
                    new String[]{String.valueOf(CallLog.Calls.OUTGOING_TYPE), String.valueOf(attemptStart - 5000)},
                    CallLog.Calls.DATE + " DESC");
            if (c != null && c.moveToFirst()) return c.getLong(0) > 0;
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private void afterCall(boolean answered, String why) {
        if (!running) return;
        h.removeCallbacksAndMessages(null);
        if (answered && stopWhenAnswered) {
            finish("answered", "Opgenomen bij poging " + attempt);
            return;
        }
        if (maxAttempts > 0 && attempt >= maxAttempts) {
            finish("maxed", (answered ? "Klaar" : "Niet opgenomen") + " na " + attempt + " pogingen");
            return;
        }
        phase = "waiting";
        waitSec = randomMin > 0 ? randomMin + rnd.nextInt(randomMax - randomMin + 1) : intervalSec;
        nextAt = System.currentTimeMillis() + waitSec * 1000L;
        message = why != null ? why : (answered ? "Opgenomen" : "Niet opgenomen");
        save();
        tick();
    }

    private void tick() {
        if (!running || !"waiting".equals(phase)) return;
        long left = nextAt - System.currentTimeMillis();
        if (left <= 0) { dial(); return; }
        save();
        h.postDelayed(this::tick, Math.min(1000, left));
    }

    private void finish(String res, String msg) {
        boolean was = running;
        running = false;
        h.removeCallbacksAndMessages(null);
        phase = "done";
        result = res;
        message = msg;
        save();
        unregisterCallState();
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(true);
        if (was && !"stopped".equals(res)) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (!NotifCenter.muted(this)) nm.notify(DONE_NOTIF_ID, buildDone(msg));
        }
        stopSelfResult(lastStartId); // een start die net binnenkwam niet afbreken
    }

    private int lastStartId;

    @Override
    public void onDestroy() {
        if (running) { running = false; phase = "done"; result = "stopped"; message = "Gestopt"; save(); }
        h.removeCallbacksAndMessages(null);
        unregisterCallState();
        if (wake != null && wake.isHeld()) wake.release();
        alive = false;
        super.onDestroy();
    }

    // ---------- gespreksstatus volgen ----------

    private void registerCallState() {
        if (callback != null || tm == null) return;
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                Cb31 cb = new Cb31(this);
                tm.registerTelephonyCallback(getMainExecutor(), cb);
                callback = cb;
            } else {
                PhoneStateListener l = new OldListener(this);
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE);
                callback = l;
            }
            lastState = tm.getCallState();
        } catch (Exception ignored) {
            lastState = TelephonyManager.CALL_STATE_IDLE;
        }
    }

    private void unregisterCallState() {
        if (callback == null || tm == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) tm.unregisterTelephonyCallback((TelephonyCallback) callback);
            else tm.listen((PhoneStateListener) callback, PhoneStateListener.LISTEN_NONE);
        } catch (Exception ignored) { }
        callback = null;
    }

    @SuppressWarnings("deprecation")
    static final class OldListener extends PhoneStateListener {
        private final RedialService s;
        OldListener(RedialService svc) { s = svc; }
        @Override public void onCallStateChanged(int state, String nr) { s.onCallState(state); }
    }

    static final class Cb31 extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        private final RedialService s;
        Cb31(RedialService svc) { s = svc; }
        @Override public void onCallStateChanged(int state) { s.onCallState(state); }
    }

    // ---------- status & meldingen ----------

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("number", number);
            o.put("name", name);
            o.put("attempt", attempt);
            o.put("maxAttempts", maxAttempts);
            o.put("interval", intervalSec);
            o.put("wait", waitSec);
            o.put("randomMin", randomMin);
            o.put("randomMax", randomMax);
            o.put("phase", phase);
            o.put("result", result);
            o.put("message", message);
            o.put("nextAt", nextAt);
            o.put("now", System.currentTimeMillis());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", o.toString()).apply();
        } catch (Exception ignored) { }
        if (running) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(notifText(), "waiting".equals(phase)));
        }
    }

    private String notifText() {
        String p = "Poging " + attempt + (maxAttempts > 0 ? " van " + maxAttempts : "");
        if ("waiting".equals(phase)) {
            long s = Math.max(0, (nextAt - System.currentTimeMillis() + 999) / 1000);
            return p + " · " + message + " · opnieuw over " + s + " s";
        }
        return p + " · " + message;
    }

    private String who() { return name.isEmpty() ? number : name + " (" + number + ")"; }

    private Notification buildNotification(String text, boolean canSkip) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NotifCenter.ch(this, CHANNEL)) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Auto redial: " + who())
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(this))
                .addAction(new Notification.Action.Builder(null, "Stoppen", svc(ACTION_STOP, 1)).build());
        if (canSkip) b.addAction(new Notification.Action.Builder(null, "Nu bellen", svc(ACTION_NOW, 2)).build());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private Notification buildDone(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NotifCenter.ch(this, CHANNEL)) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Auto redial: " + who())
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(this))
                .build();
    }

    private PendingIntent svc(String action, int req) {
        Intent i = new Intent(this, RedialService.class).setAction(action);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).putExtra("open", "redial")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Auto redial", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Voortgang van Auto redial");
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    static boolean hasCallLog(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    static String status(Context c) {
        String s = c.getSharedPreferences(PREFS, MODE_PRIVATE).getString("status", null);
        if (s == null) return "{\"running\":false,\"phase\":\"idle\"}";
        try {
            JSONObject o = new JSONObject(s);
            o.put("now", System.currentTimeMillis());
            if (o.optBoolean("running") && !alive) {
                o.put("running", false); o.put("phase", "done"); o.put("result", "stopped"); o.put("message", "Gestopt");
            }
            return o.toString();
        } catch (Exception e) { return s; }
    }
}
