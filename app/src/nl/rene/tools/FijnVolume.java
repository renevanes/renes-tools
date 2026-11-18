package nl.rene.tools;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Fijner volume met de volumeknoppen van de telefoon (podcasts, radio en voorlezen).
 *
 * Android kent voor media meestal maar 15 (soms 25) stappen; de onderste klinken als grote sprongen. Zolang Tools iets
 * afspeelt, neemt deze klasse de volumeknoppen over en deelt elke stap van Android in `fijn` kleinere stappen:
 * met Tools in beeld via MainActivity.dispatchKeyEvent (werkt op elk toestel), daarbuiten via de mediasessie
 * (MediaSession.setPlaybackToRemote met een eigen VolumeProvider). Dat laatste staat niet elk toestel toe (Android 13+
 * met config_volumeAdjustmentForRemoteGroupSessions uit, zoals Pixels): dan gaan de knoppen buiten Tools gewoon naar
 * Android en volgt Tools dat (volg), zonder dat er iets misgaat. Het geluid is dan: de stap van Android (naar boven afgerond)
 * maal een eigen versterking (gain) in de speler, in dB tussen die stap en de stap eronder. Wordt het volume ergens
 * anders veranderd (de schuif van Android, een koptelefoon, een ander apparaat), dan volgt Tools dat (rebase).
 *
 * Alleen voor gewoon mediageluid: niet voor de wekker (radio als wekker), niet in de auto (die heeft een eigen volume)
 * en niet als het volume op dit toestel vast staat. Alles op de hoofdthread.
 */
final class FijnVolume {

    /** Hoeveel kleinere stappen per stap van Android. */
    static final int[] KEUZES = {2, 3, 4};
    static final int STANDAARD = 3;
    /** Aangenomen grootte van één stap van Android als het toestel het niet zegt (dB). */
    static final double STAP_DB = 2.5;

    interface Gain { void gain(float g); }
    interface Speelt { boolean nu(); }

    // ---- instelling ----

    static boolean aan(Context c) { return Player.prefs(c).getBoolean("fijnVolume", true); }
    static int fijn(Context c) { return norm(Player.prefs(c).getInt("fijnStappen", STANDAARD)); }
    static int norm(int k) { for (int x : KEUZES) if (x == k) return k; return STANDAARD; }

    /** Bewaren en meteen gebruiken bij wat nu speelt. */
    static void zet(Context c, boolean aan, int fijn) {
        Player.prefs(c).edit().putBoolean("fijnVolume", aan).putInt("fijnStappen", norm(fijn)).apply();
        new Handler(Looper.getMainLooper()).post(() -> { for (FijnVolume f : levend()) try { f.vernieuw(); } catch (Throwable ignored) { } });
    }

    private static final List<java.lang.ref.WeakReference<FijnVolume>> LEVEND = new ArrayList<>();
    private static synchronized void erbij(FijnVolume f) { for (java.lang.ref.WeakReference<FijnVolume> w : LEVEND) if (w.get() == f) return; LEVEND.add(new java.lang.ref.WeakReference<>(f)); }
    private static synchronized void eraf(FijnVolume f) { for (int i = LEVEND.size() - 1; i >= 0; i--) if (LEVEND.get(i).get() == f) LEVEND.remove(i); }

    /** De speler die nu speelt met fijn volume aan (voor de knoppen terwijl Tools in beeld is), of null. */
    static FijnVolume speler() {
        for (FijnVolume f : levend()) try { if (f.bezig && f.speelt.nu()) return f; } catch (Throwable ignored) { }
        return null;
    }

    private static synchronized List<FijnVolume> levend() {
        List<FijnVolume> l = new ArrayList<>();
        for (int i = LEVEND.size() - 1; i >= 0; i--) { FijnVolume f = LEVEND.get(i).get(); if (f == null) LEVEND.remove(i); else l.add(f); }
        return l;
    }

    // ---- zuiver (getest in FijnVolumeTest) ----

    /** De stap van Android bij een fijn niveau: naar boven afgerond, binnen min..max. */
    static int stap(int niveau, int k, int min, int max) {
        int s = niveau <= 0 ? 0 : (niveau + k - 1) / k;
        return Math.max(min, Math.min(max, s));
    }

    /**
     * Versterking (0..1) voor een fijn niveau dat `rest` fijne stappen onder stap s ligt. dbOnder/dbStap: het geluid van
     * stap s-1 en s in dB (NaN of oneindig = onbekend). Van stilte (stap 0) naar stap 1: rechtlijnig in amplitude.
     */
    static float gain(int niveau, int k, int s, double dbOnder, double dbStap) {
        int rest = s * k - niveau;
        if (rest <= 0 || k <= 1) return 1f;
        if (rest >= k) return 0f;
        if (s <= 1) return (float) (1.0 - (double) rest / k);
        double d = dbStap - dbOnder;
        if (Double.isNaN(d) || Double.isInfinite(d) || d < 0.3 || d > 20) d = STAP_DB;
        return (float) Math.pow(10, -d * rest / k / 20.0);
    }

    // ---- per speler ----

    private final Context app;
    private final AudioManager am;
    private final Gain uit;
    private final Speelt speelt;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private MediaSession session;
    private Knoppen provider;
    private Kijker kijker;
    private boolean actief = true, gekoppeld, bezig;
    private int k = STANDAARD, min, max, niveau, gezet = -1;
    private float gain = 1f;

    FijnVolume(Context c, Gain uit, Speelt speelt) {
        app = c.getApplicationContext();
        am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.uit = uit;
        this.speelt = speelt;
    }

    /** De versterking die de speler nu moet toepassen (1 = gewoon). */
    float gain() { return gain; }
    int niveau() { return niveau; }
    int hoogste() { return max * k; }

    /**
     * Aan het werk, met de mediasessie van de speler (de knoppen buiten Tools gaan naar de sessie die het laatst
     * speelde) of zonder (null: alleen de knoppen terwijl Tools in beeld is).
     */
    void koppel(MediaSession s) {
        if (gekoppeld && s != session) stop(); // andere sessie: de oude terug naar gewoon
        session = s; gekoppeld = true; erbij(this); vernieuw();
    }

    /** Uit voor bijvoorbeeld de wekker: dan gewoon het volume van Android. Ook bij elke andere stand van de speler
     *  aanroepen: zo telt een auto die intussen verbonden is (autostand) meteen mee. */
    void actief(boolean a) { actief = a; vernieuw(); }

    /** Loslaten (dienst of voorlezen stopt): knoppen terug naar Android. */
    void los() { stop(); session = null; gekoppeld = false; eraf(this); }

    void vernieuw() {
        if (gekoppeld && actief && aan(app) && mag()) start(); else stop();
    }

    private boolean mag() {
        try {
            if (am == null || am.isVolumeFixed()) return false;
            android.app.UiModeManager ui = (android.app.UiModeManager) app.getSystemService(Context.UI_MODE_SERVICE);
            return ui == null || ui.getCurrentModeType() != android.content.res.Configuration.UI_MODE_TYPE_CAR;
        } catch (Throwable e) { return false; }
    }

    private void start() {
        int nk = fijn(app);
        int nmax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int nmin = Build.VERSION.SDK_INT >= 28 ? am.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0;
        if (nmax <= 0) { stop(); return; }
        if (bezig && nk == k && nmax == max && (session == null) == (provider == null)) { volg(); return; }
        k = nk; max = nmax; min = Math.max(0, nmin);
        int idx = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        niveau = idx * k; gezet = idx; zetGain(1f);
        bezig = true;
        if (session != null) {
            Knoppen p = new Knoppen(this, max * k, niveau);
            try { session.setPlaybackToRemote(p); provider = p; } catch (Throwable e) { provider = null; }
        } else if (provider != null) { provider = null; }
        if (kijker == null) {
            kijker = new Kijker(this, h);
            try { app.getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, kijker); } catch (Throwable e) { kijker = null; }
        }
    }

    private void stop() {
        if (kijker != null) { try { app.getContentResolver().unregisterContentObserver(kijker); } catch (Throwable ignored) { } kijker = null; }
        if (provider != null) {
            provider = null;
            try { if (session != null) session.setPlaybackToLocal(attrs); } catch (Throwable ignored) { }
        }
        bezig = false;
        gezet = -1;
        zetGain(1f);
    }

    private void zetGain(float g) {
        if (g == gain) return;
        gain = g;
        try { uit.gain(g); } catch (Throwable ignored) { }
    }

    /** Is het volume ergens anders veranderd? Dan dat aanhouden, zonder eigen versterking. */
    void volg() {
        if (!bezig) return;
        int idx = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (idx == gezet) return;
        gezet = idx; niveau = idx * k; zetGain(1f);
        if (provider != null) try { provider.setCurrentVolume(niveau); } catch (Throwable ignored) { }
    }

    void stapje(int richting) {
        if (!bezig) return;
        volg();
        if (richting == AudioManager.ADJUST_RAISE) naar(niveau + 1);
        else if (richting == AudioManager.ADJUST_LOWER) naar(niveau - 1);
        else if (richting == AudioManager.ADJUST_MUTE) naar(0);
    }

    void naar(int v) {
        if (!bezig) return;
        volg();
        v = Math.max(min * k, Math.min(max * k, v));
        int s = stap(v, k, min, max);
        float g = gain(v, k, s, db(s - 1), db(s));
        // Eerst wat zachter maakt, dan wat harder maakt: geen tik te hard tussendoor
        if (s > gezet) { zetGain(g); zetStap(s); } else { zetStap(s); zetGain(g); }
        int echt = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (echt != s) { gezet = echt; niveau = echt * k; zetGain(1f); } // geweigerd (bijv. de waarschuwing voor hard volume)
        else niveau = v;
        if (provider != null) try { provider.setCurrentVolume(niveau); } catch (Throwable ignored) { }
    }

    private void zetStap(int s) {
        if (s == gezet) return;
        gezet = s;
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, s, 0); } catch (Throwable ignored) { }
    }

    /** Hoe hard een stap van Android klinkt op het apparaat waar het geluid nu heen gaat (dB), of NaN. */
    private double db(int s) {
        if (s <= 0) return Double.NEGATIVE_INFINITY;
        if (Build.VERSION.SDK_INT < 28) return Double.NaN;
        try { return am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, s, uitgang()); } catch (Throwable e) { return Double.NaN; }
    }

    private int uitgang() {
        int beste = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return t;
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_USB_HEADSET) beste = t;
            }
        } catch (Throwable ignored) { }
        return beste;
    }

    /** De volumeknoppen (en de schuif in het volumevenster van Android) terwijl Tools speelt. */
    static final class Knoppen extends VolumeProvider {
        private final java.lang.ref.WeakReference<FijnVolume> f;
        Knoppen(FijnVolume f, int max, int nu) { super(VOLUME_CONTROL_ABSOLUTE, max, nu); this.f = new java.lang.ref.WeakReference<>(f); }
        @Override public void onAdjustVolume(int richting) { FijnVolume v = f.get(); if (v != null) try { v.stapje(richting); } catch (Throwable ignored) { } }
        @Override public void onSetVolumeTo(int niveau) { FijnVolume v = f.get(); if (v != null) try { v.naar(niveau); } catch (Throwable ignored) { } }
    }

    /** Ziet veranderingen van het mediavolume buiten Tools om. */
    static final class Kijker extends ContentObserver {
        private final java.lang.ref.WeakReference<FijnVolume> f;
        Kijker(FijnVolume f, Handler h) { super(h); this.f = new java.lang.ref.WeakReference<>(f); }
        @Override public void onChange(boolean self) { FijnVolume v = f.get(); if (v != null) try { v.volg(); } catch (Throwable ignored) { } }
    }
}
