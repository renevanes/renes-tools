package nl.rene.tools;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Houdt telefoonfuncties bij zolang het proces leeft: camera en microfoon in gebruik, wifi/mobiele data, Bluetooth en
 * locatie. Android laat deze meldingen alleen toe zolang een onderdeel van de app leeft; daarom start dit vanuit de
 * meldingenluisteraar (HistoryListener, die Android gebonden houdt), de toegankelijkheidsdienst en MainActivity.
 * Eén keer per proces registreren (start is onschadelijk als het al loopt). Alles blijft op de telefoon.
 */
final class GebruikLive {

    private GebruikLive() { }

    private static boolean on;
    private static Handler bg;
    private static CameraManager.AvailabilityCallback camCb;
    private static CameraManager camMgr;
    private static Object audioCb; // AudioManager.AudioRecordingCallback (API 24+)
    private static AudioManager audioMgr;
    private static ConnectivityManager.NetworkCallback netCb;
    private static ConnectivityManager connMgr;
    private static Object gnssCb; // GnssStatus.Callback (API 24+)
    private static LocationManager locMgr;
    private static BroadcastReceiver recv;

    static synchronized void start(Context c0) {
        if (on) return;
        final Context c = c0.getApplicationContext();
        if (!GebruikStore.collecting(c)) return;
        on = true;
        try { Handler h = new Handler(Looper.getMainLooper()); bg = h; } catch (Throwable ignored) { }
        startCamera(c);
        startAudio(c);
        startConnectivity(c);
        startGnss(c);
        startReceiver(c);
    }

    static synchronized void stop() {
        on = false;
        try { if (camMgr != null && camCb != null) camMgr.unregisterAvailabilityCallback(camCb); } catch (Throwable ignored) { }
        try { if (Build.VERSION.SDK_INT >= 24 && audioMgr != null && audioCb != null) audioMgr.unregisterAudioRecordingCallback((AudioManager.AudioRecordingCallback) audioCb); } catch (Throwable ignored) { }
        try { if (connMgr != null && netCb != null) connMgr.unregisterNetworkCallback(netCb); } catch (Throwable ignored) { }
        try { if (Build.VERSION.SDK_INT >= 24 && locMgr != null && gnssCb != null) locMgr.unregisterGnssStatusCallback((GnssStatus.Callback) gnssCb); } catch (Throwable ignored) { }
        try { if (recv != null && appCtx != null) appCtx.unregisterReceiver(recv); } catch (Throwable ignored) { }
        camCb = null; audioCb = null; netCb = null; gnssCb = null; recv = null;
    }

    private static Context appCtx;

    private static void startCamera(Context c) {
        try {
            camMgr = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (camMgr == null) return;
            camCb = new Cam(c);
            if (Build.VERSION.SDK_INT >= 28) camMgr.registerAvailabilityCallback(java.util.concurrent.Executors.newSingleThreadExecutor(), camCb);
            else camMgr.registerAvailabilityCallback(camCb, bg);
        } catch (Throwable e) { App.log(c, "GEBRUIK", "camera-callback: " + e); }
    }

    private static void startAudio(Context c) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            audioMgr = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (audioMgr == null) return;
            Rec r = new Rec(c);
            audioCb = r;
            audioMgr.registerAudioRecordingCallback(r, bg);
        } catch (Throwable e) { App.log(c, "GEBRUIK", "mic-callback: " + e); }
    }

    private static void startConnectivity(Context c) {
        try {
            connMgr = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connMgr == null) return;
            netCb = new Net(c);
            if (Build.VERSION.SDK_INT >= 24) connMgr.registerDefaultNetworkCallback(netCb);
            else connMgr.registerNetworkCallback(new android.net.NetworkRequest.Builder().build(), netCb);
        } catch (Throwable e) { App.log(c, "GEBRUIK", "net-callback: " + e); }
    }

    private static void startGnss(Context c) {
        if (Build.VERSION.SDK_INT < 24) return;
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            locMgr = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (locMgr == null) return;
            Gnss g = new Gnss(c);
            gnssCb = g;
            locMgr.registerGnssStatusCallback(g, bg);
        } catch (Throwable e) { App.log(c, "GEBRUIK", "gnss-callback: " + e); }
    }

    private static void startReceiver(Context c) {
        try {
            appCtx = c;
            recv = new Recv();
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
            c.registerReceiver(recv, f);
        } catch (Throwable e) { App.log(c, "GEBRUIK", "receiver: " + e); }
    }

    // ---------- callbacks (eigen static klassen; geen anonieme inner classes, D8) ----------

    static final class Cam extends CameraManager.AvailabilityCallback {
        private final Context c;
        Cam(Context c) { this.c = c.getApplicationContext(); }
        @Override public void onCameraUnavailable(String id) {
            // In gebruik (door welke app dan ook): noteer één keer, met de app die nu op de voorgrond staat.
            try { GebruikStore.funcEvent(c, "camera", GebruikStore.foregroundApp(c)); } catch (Throwable ignored) { }
        }
    }

    static final class Rec extends AudioManager.AudioRecordingCallback {
        private final Context c; private boolean active;
        Rec(Context c) { this.c = c.getApplicationContext(); }
        @Override public void onRecordingConfigChanged(java.util.List<android.media.AudioRecordingConfiguration> cfgs) {
            boolean now = cfgs != null && !cfgs.isEmpty();
            if (now && !active) { active = true; try { GebruikStore.funcEvent(c, "mic", GebruikStore.foregroundApp(c)); } catch (Throwable ignored) { } }
            else if (!now) active = false;
        }
    }

    static final class Net extends ConnectivityManager.NetworkCallback {
        private final Context c;
        Net(Context c) { this.c = c.getApplicationContext(); }
        @Override public void onCapabilitiesChanged(Network net, NetworkCapabilities cap) {
            try {
                if (cap == null) return;
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) markOnce("wifi");
                else if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) markOnce("data");
            } catch (Throwable ignored) { }
        }
        private String last = "";
        private void markOnce(String kind) { if (!kind.equals(last)) { last = kind; GebruikStore.funcEvent(c, kind, ""); } }
    }

    static final class Gnss extends GnssStatus.Callback {
        private final Context c; private boolean started;
        Gnss(Context c) { this.c = c.getApplicationContext(); }
        @Override public void onStarted() { if (!started) { started = true; try { GebruikStore.funcEvent(c, "loc", GebruikStore.foregroundApp(c)); } catch (Throwable ignored) { } } }
        @Override public void onStopped() { started = false; }
    }

    static final class Recv extends BroadcastReceiver {
        private long lastBt;
        @Override public void onReceive(Context c, Intent i) {
            String a = i == null ? null : i.getAction();
            if (a == null) return;
            Context ac = c.getApplicationContext();
            try {
                if (Intent.ACTION_POWER_CONNECTED.equals(a) || Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                    GebruikStore.snapshotFuncs(ac);
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                    long now = System.currentTimeMillis();
                    if (now - lastBt > 2000) { lastBt = now; GebruikStore.funcEvent(ac, "bt", ""); }
                } else if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(a)) {
                    LocationManager lm = (LocationManager) ac.getSystemService(Context.LOCATION_SERVICE);
                    if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) GebruikStore.funcEvent(ac, "loc", "");
                }
            } catch (Throwable ignored) { }
        }
    }
}
