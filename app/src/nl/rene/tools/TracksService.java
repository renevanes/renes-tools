package nl.rene.tools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Neemt een route op met GPS als voorgrondservice, zodat het doorloopt met het scherm uit.
 * Elke locatie wordt aan het opnamebestand toegevoegd; live-statistieken staan in de
 * voorkeuren en in de melding.
 */
public class TracksService extends Service implements LocationListener {

    static final String ACTION_START = "nl.rene.tools.track.START";
    static final String ACTION_PAUSE = "nl.rene.tools.track.PAUSE";
    static final String ACTION_RESUME = "nl.rene.tools.track.RESUME";
    static final String ACTION_STOP = "nl.rene.tools.track.STOP";
    static final String CHANNEL = "tracks";
    static final int NOTIF_ID = 3001;

    static volatile boolean running = false;
    static volatile boolean paused = false;

    private LocationManager lm;
    private PowerManager.WakeLock wake;
    private BufferedWriter writer;
    private final Handler h = new Handler(Looper.getMainLooper());

    private long startT;
    private final List<Tracks.Pt> pts = new ArrayList<>();
    private Tracks.Pt last;
    private double distance;
    private double maxSpeed;
    private long movingMs;
    private int routePts;        // aantal routepunten na het ruisfilter
    private float fixAcc = -1f;  // nauwkeurigheid van de laatste meting (ook als die is afgewezen)

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        createChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) {
            // Niets aan het opnemen (bijv. nieuwe dienst na het opruimen van de app): niets stoppen of wissen
            if (!running) { stopSelf(); return START_NOT_STICKY; }
            autoSaveReq = intent.getBooleanExtra("autoSave", false);
            stopRecording(); return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(a)) { paused = true; save(); updateNotif(); return START_STICKY; }
        if (ACTION_RESUME.equals(a)) { paused = false; last = null; save(); updateNotif(); return START_STICKY; }
        if (!ACTION_START.equals(a)) {
            // Herstart door Android na het opruimen van de app: de lopende route niet kwijtraken
            if (!running) { Tracks.recoverInterrupted(this); stopSelf(); }
            return START_NOT_STICKY;
        }

        if (running) {
            // Loopt al (bijv. tegelijk gestart door een automatisering): een rit-start maakt er een rit van
            if (intent.getBooleanExtra("trip", false)) Tracks.prefs(this).edit().putBoolean("trip", true).apply();
            return START_STICKY;
        }

        // Toestand opnieuw instellen vóór de melding, zodat die meteen klopt.
        running = true;
        Tracks.prefs(this).edit().putBoolean("trip", intent.getBooleanExtra("trip", false)).apply();
        paused = false;
        startT = System.currentTimeMillis();
        pts.clear(); last = null; distance = 0; maxSpeed = 0; movingMs = 0; routePts = 0; fixAcc = -1f;

        boolean hasPerm = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        // startForeground is verplicht na startForegroundService. Met het type "location" eist
        // Android 14+ dat de toestemming er is; zonder toestemming starten we zonder type en stoppen.
        Notification n = buildNotif();
        try {
            if (Build.VERSION.SDK_INT >= 34 && hasPerm)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else
                startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            running = false;
            Tracks.prefs(this).edit().putString("error", "Starten van de opname lukt niet").remove("trip").apply();
            Car.prefs(this).edit().remove("tripStart").apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!hasPerm) {
            running = false;
            Tracks.prefs(this).edit().putString("error", "Geen locatietoestemming").remove("trip").apply();
            Car.prefs(this).edit().remove("tripStart").apply();
            stopForeground(true); stopSelf(); return START_NOT_STICKY;
        }

        Tracks.prefs(this).edit().remove("error").putLong("startT", startT).apply();
        try {
            File live = Tracks.liveFile(this);
            // Een onderbroken of nog niet opgeslagen route nooit wissen: eerst bewaren als route
            if (live.exists() && !Tracks.readPoints(live).isEmpty()) {
                android.content.SharedPreferences tp = Tracks.prefs(this);
                Tracks.finalize(this, tp.getLong("lastStartT", tp.getLong("startT", live.lastModified())), "Onderbroken route");
                tp.edit().remove("justStopped").remove("interrupted").apply();
            }
            if (live.exists()) live.delete();
            writer = new BufferedWriter(new FileWriter(live, true));
        } catch (Exception e) {
            Tracks.prefs(this).edit().putString("error", "Opslaan lukt niet").apply();
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RenesTools:track");
        wake.setReferenceCounted(false);
        wake.acquire(12 * 60 * 60 * 1000L);

        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0f, this, Looper.getMainLooper());
            else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000, 0f, this, Looper.getMainLooper());
            else
                Tracks.prefs(this).edit().putString("error", "Zet locatie (GPS) aan op je telefoon").apply();
        } catch (SecurityException e) {
            Tracks.prefs(this).edit().putString("error", "Geen locatietoestemming").apply();
        }
        save();
        ticker();
        return START_STICKY;
    }

    private void ticker() {
        if (!running) return;
        save();
        h.postDelayed(this::ticker, 1000);
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (!running || paused) return;
        double ele = loc.hasAltitude() ? loc.getAltitude() : Double.NaN;
        float sp = loc.hasSpeed() ? loc.getSpeed() : -1f;
        float acc = loc.hasAccuracy() ? loc.getAccuracy() : 999f;
        Tracks.Pt p = new Tracks.Pt(loc.getLatitude(), loc.getLongitude(), ele, System.currentTimeMillis(), acc, sp);
        fixAcc = acc;
        // Alle metingen bewaren, zodat het ruisfilter later over alle gegevens beschikt.
        pts.add(p);
        if (writer != null) {
            try { writer.write(p.line()); writer.write("\n"); writer.flush(); } catch (Exception ignored) { }
        }
        // De huidige positie (voor de kaart) alleen verzetten bij een redelijk nauwkeurige meting,
        // zodat een uitschieter de kaart niet laat springen.
        if (acc <= Tracks.MAX_ACC || last == null) last = p;
        // Live-cijfers met exact hetzelfde filter als bij het terugkijken, zodat ze overeenkomen.
        Tracks.Stats st = Tracks.stats(pts);
        distance = st.distance;
        maxSpeed = st.maxSpeed;
        movingMs = st.movingMs;
        routePts = st.points;
        save();
    }

    @Override public void onProviderDisabled(String provider) {
        Tracks.prefs(this).edit().putString("error", "Locatie staat uit").apply();
    }
    @Override public void onProviderEnabled(String provider) {
        Tracks.prefs(this).edit().remove("error").apply();
    }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private boolean autoSaveReq;

    private void stopRecording() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
        if (writer != null) { try { writer.flush(); writer.close(); } catch (Exception ignored) { } writer = null; }
        if (wake != null && wake.isHeld()) wake.release();
        // samenvatting voor de app, zodat het opslaan-scherm meteen de stats heeft
        try {
            Tracks.Stats s = Tracks.stats(pts);
            if (Tracks.prefs(this).getBoolean("trip", false)) {
                // Rit van Mijn auto: vanzelf opslaan, geen vraag
                Tracks.prefs(this).edit().remove("trip").apply();
                Car.tripDone(this, startT, s);
                stopForeground(true); stopSelf();
                return;
            }
            if (autoSaveReq) {
                // Gestopt door een automatisering: vanzelf opslaan
                autoSaveReq = false;
                if (s.points >= 2) Tracks.finalize(this, startT, "Route " + new java.text.SimpleDateFormat("d MMM HH:mm", new java.util.Locale("nl", "NL")).format(new java.util.Date(startT)));
                else Tracks.liveFile(this).delete();
                stopForeground(true); stopSelf();
                return;
            }
            Tracks.prefs(this).edit()
                    .putBoolean("justStopped", true).remove("interrupted")
                    .putInt("lastPoints", s.points)
                    .putString("lastStats", Tracks.statsJson(s).toString())
                    .putLong("lastStartT", startT)
                    .apply();
        } catch (Exception ignored) { }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running) { running = false; try { lm.removeUpdates(this); } catch (Exception ignored) { } }
        h.removeCallbacksAndMessages(null);
        if (writer != null) { try { writer.close(); } catch (Exception ignored) { } }
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("paused", paused);
            o.put("startT", startT);
            o.put("distance", distance);
            o.put("maxSpeed", maxSpeed);
            o.put("movingMs", movingMs);
            o.put("points", routePts);
            o.put("now", System.currentTimeMillis());
            if (fixAcc >= 0) o.put("acc", fixAcc);
            if (last != null) {
                o.put("lat", last.lat); o.put("lon", last.lon);
                if (!Double.isNaN(last.ele)) o.put("ele", last.ele);
            }
            o.put("error", Tracks.prefs(this).getString("error", ""));
            o.put("path", Tracks.svgPath(pts, 300, 160, 8));
            Tracks.prefs(this).edit().putString("status", o.toString()).apply();
        } catch (Exception ignored) { }
        if (running) updateNotif();
    }

    static String status(Context c) {
        String s = Tracks.prefs(c).getString("status", null);
        if (s == null) return "{\"running\":false}";
        try {
            JSONObject o = new JSONObject(s);
            if (o.optBoolean("running") && !running) o.put("running", false);
            o.put("now", System.currentTimeMillis());
            return o.toString();
        } catch (Exception e) { return "{\"running\":false}"; }
    }

    // ---------- melding ----------

    private void updateNotif() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotif());
    }

    private Notification buildNotif() {
        String txt = paused ? "Gepauzeerd" : String.format(java.util.Locale.GERMANY, "%.2f km", distance / 1000.0);
        long el = (System.currentTimeMillis() - startT) / 1000;
        txt += " · " + (el / 60) + " min";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, CHANNEL)) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_route)
                .setContentTitle(paused ? "Route gepauzeerd" : "Route opnemen")
                .setContentText(txt)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setContentIntent(open(this));
        if (paused) b.addAction(new Notification.Action.Builder(null, "Hervatten", svc(ACTION_RESUME, 31)).build());
        else b.addAction(new Notification.Action.Builder(null, "Pauze", svc(ACTION_PAUSE, 32)).build());
        b.addAction(new Notification.Action.Builder(null, "Stoppen", svc(ACTION_STOP, 33)).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private PendingIntent svc(String action, int req) {
        return PendingIntent.getService(this, req, new Intent(this, TracksService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class).putExtra("open", "tracks").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 30, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Route opnemen", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Toont de voortgang tijdens het opnemen van een route");
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }
}
