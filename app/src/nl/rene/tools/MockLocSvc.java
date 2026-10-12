package nl.rene.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Geeft elke seconde de gekozen plek door als locatie (gps, netwerk en "fused"), tot je stopt. */
public class MockLocSvc extends Service {

    static final String START = "nl.rene.tools.MOCKLOC_START", STOP = "nl.rene.tools.MOCKLOC_STOP";
    static final String CHANNEL = "mockloc";
    static final int NOTIF_ID = 4801;

    static volatile MockLocSvc inst;
    static volatile boolean running;
    static volatile String error;

    final Handler h = new Handler(Looper.getMainLooper());
    private final List<String> providers = new ArrayList<>();
    private LocationManager lm;
    private double baseLat, baseLng, dLat, dLng;
    private final java.util.Random rnd = new java.util.Random();
    private final Runnable push = this::push;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() { super.onCreate(); inst = this; lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean fg = startFg();
        String a = intent == null ? START : intent.getAction();
        if (STOP.equals(a)) { stopMock(); return START_NOT_STICKY; }
        if (!fg) { error = "Android liet Locatie kiezen niet doorlopen (vanaf de achtergrond)"; stopMock(); return START_NOT_STICKY; }
        baseLat = MockLoc.lat(this); baseLng = MockLoc.lng(this);
        if (!MockLoc.validLatLng(baseLat, baseLng)) { stopMock(); return START_NOT_STICKY; }
        dLat = 0; dLng = 0;
        if (providers.isEmpty() && !addProviders()) { stopMock(); return START_NOT_STICKY; }
        running = true; error = null;
        h.removeCallbacks(push);
        push();
        notifyChanged();
        return START_STICKY;
    }

    private boolean startFg() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Locatie kiezen", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        return Fg.start(this, NOTIF_ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, 29);
    }

    private Notification notif() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NotifCenter.ch(this, CHANNEL)) : new Notification.Builder(this);
        String name = MockLoc.prefs(this).getString("name", "");
        String where = name.isEmpty() ? String.format(java.util.Locale.ROOT, "%.5f, %.5f", MockLoc.lat(this), MockLoc.lng(this)) : name;
        Intent open = new Intent(this, MainActivity.class).putExtra("open", "loc").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent stop = PendingIntent.getService(this, 4802, new Intent(this, MockLocSvc.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setSmallIcon(R.drawable.ic_route).setContentTitle("Locatie kiezen staat aan").setContentText(where)
                .setContentIntent(PendingIntent.getActivity(this, 4803, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Stoppen", stop).build());
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private boolean addProviders() {
        List<String> want = new ArrayList<>();
        want.add(LocationManager.GPS_PROVIDER);
        want.add(LocationManager.NETWORK_PROVIDER);
        if (Build.VERSION.SDK_INT >= 31) want.add(LocationManager.FUSED_PROVIDER);
        for (String p : want) {
            try {
                try { lm.removeTestProvider(p); } catch (Exception ignored) { } // van een vorige keer
                if (Build.VERSION.SDK_INT >= 31) {
                    lm.addTestProvider(p, new android.location.provider.ProviderProperties.Builder()
                            .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
                            .setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_LOW)
                            .setAccuracy(android.location.provider.ProviderProperties.ACCURACY_FINE).build());
                } else {
                    lm.addTestProvider(p, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                }
                lm.setTestProviderEnabled(p, true);
                providers.add(p);
            } catch (SecurityException e) {
                error = "Kies eerst Rene's Tools bij \"App voor neplocatie selecteren\" (Ontwikkelaarsopties)";
                removeProviders();
                return false;
            } catch (Exception e) {
                App.log(this, "LOC", "provider " + p + ": " + e.getClass().getSimpleName());
            }
        }
        if (providers.isEmpty()) { error = "Android liet het kiezen van een locatie niet toe"; return false; }
        return true;
    }

    private void removeProviders() {
        for (String p : providers) {
            try { lm.setTestProviderEnabled(p, false); } catch (Exception ignored) { }
            try { lm.removeTestProvider(p); } catch (Exception ignored) { }
        }
        providers.clear();
    }

    /** Plek doorgeven (elke seconde; met "een beetje bewegen" een paar meter heen en weer). */
    private int pushes;

    private void push() {
        if (!running) return;
        if (++pushes % 10 == 0 && !MockLoc.allowed(this)) { error = "Rene's Tools staat niet meer bij \"App voor neplocatie selecteren\""; stopMock(); return; }
        double lat = baseLat, lng = baseLng;
        if (MockLoc.prefs(this).getBoolean("jitter", true)) {
            dLat = clamp(dLat + (rnd.nextDouble() - .5) * 0.000012, 0.00003);
            dLng = clamp(dLng + (rnd.nextDouble() - .5) * 0.000018, 0.000045);
            lat += dLat; lng += dLng;
        }
        for (String p : providers) {
            Location l = new Location(p);
            l.setLatitude(lat); l.setLongitude(lng);
            l.setAccuracy(LocationManager.NETWORK_PROVIDER.equals(p) ? 20f : 4f + rnd.nextFloat() * 3f);
            l.setAltitude(5); l.setSpeed(0); l.setBearing(0);
            l.setTime(System.currentTimeMillis());
            l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (Build.VERSION.SDK_INT >= 26) { l.setVerticalAccuracyMeters(3f); l.setSpeedAccuracyMetersPerSecond(0.5f); l.setBearingAccuracyDegrees(30f); }
            try { lm.setTestProviderLocation(p, l); }
            catch (SecurityException e) { error = "Rene's Tools staat niet meer bij \"App voor neplocatie selecteren\""; stopMock(); return; }
            catch (Exception ignored) { }
        }
        h.postDelayed(push, 1000);
    }

    private static double clamp(double v, double m) { return Math.max(-m, Math.min(m, v)); }

    void stopMock() {
        running = false;
        h.removeCallbacks(push);
        removeProviders();
        MockLoc.prefs(this).edit().putBoolean("active", false).apply();
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true); } catch (Exception ignored) { }
        notifyChanged();
        stopSelf();
    }

    private void notifyChanged() {
        MainActivity m = MainActivity.live;
        if (m != null) m.js("onLocChanged", "");
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacks(push);
        removeProviders();
        if (inst == this) inst = null;
        super.onDestroy();
    }
}
