package nl.rene.tools;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Werk dat Rene zelf startte (een document herkennen, een backup maken, een export, een PDF maken) gaat door als het
 * scherm uitgaat of de app uit beeld gaat. Zonder dit legt Android de app kort daarna stil: de processor slaapt en een
 * app zonder iets zichtbaars wordt bevroren, zodat het werk pas verdergaat (of mislukt) als je de app weer opent.
 *
 * Zolang er iets loopt: een gedeeltelijke wakelock (de processor blijft wakker, het scherm niet) en, als het langer
 * dan {@link #DIENST_NA_MS} duurt, een voorgronddienst met een stille melding ({@link DoorwerkDienst}). Alles telt op:
 * de dienst stopt pas als het laatste werk klaar is. Gebruik: {@code new Thread(Doorwerken.door("soort", () -> …), "naam")}
 * of {@code try (Doorwerken.Klaar k = Doorwerken.begin("soort")) { … }}. Alleen vaste soorten, nooit namen of inhoud.
 */
final class Doorwerken {
    private Doorwerken() { }

    static final String CHANNEL = "doorwerken";
    static final int NOTIF_ID = 4911;
    /** Kort werk (de meeste stappen) krijgt geen melding: pas na deze tijd de voorgronddienst. */
    static final long DIENST_NA_MS = 1500;
    /** Vangnet: de wakelock houdt nooit langer dan dit de processor wakker, ook als iets blijft hangen. */
    static final long MAX_MS = 3 * 60 * 60_000L;

    private static volatile Context app;
    private static final Object LOCK = new Object();
    private static int bezig;
    private static PowerManager.WakeLock wl;
    private static Handler h;

    /** Eén keer bij het starten van de app (App.onCreate). */
    static void init(Context c) { app = c.getApplicationContext(); }

    /** Hoeveel werk er nu loopt. */
    static int bezig() { synchronized (LOCK) { return bezig; } }

    /** Het werk, met wakker blijven zolang het loopt; vangt elke fout (zoals {@link App#veilig}). */
    static Runnable door(String soort, Runnable werk) {
        Runnable veilig = App.veilig(werk);
        return () -> {
            Klaar k = begin(soort);
            try { veilig.run(); } finally { k.close(); }
        };
    }

    /** Begin van werk dat door moet lopen; altijd sluiten (try-with-resources). Mislukt het wakker houden, dan loopt het werk gewoon. */
    static Klaar begin(String soort) {
        Context c = app;
        synchronized (LOCK) {
            bezig++;
            if (c != null && bezig == 1) {
                try {
                    if (wl == null) {
                        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renestools:doorwerken");
                        wl.setReferenceCounted(false);
                    }
                    wl.acquire(MAX_MS);
                } catch (Throwable ignored) { }
                try {
                    if (h == null) h = new Handler(Looper.getMainLooper());
                    h.removeCallbacks(DIENST);
                    h.postDelayed(DIENST, DIENST_NA_MS);
                } catch (Throwable ignored) { }
            }
        }
        return new Klaar();
    }

    /** Na DIENST_NA_MS nog bezig: de voorgronddienst (Android houdt de app dan actief, ook uit beeld). */
    private static final Runnable DIENST = Doorwerken::startDienst;

    private static void startDienst() {
        Context c = app;
        synchronized (LOCK) { if (bezig == 0 || c == null) return; }
        try {
            Intent i = new Intent(c, DoorwerkDienst.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable e) {
            // Android 12+ weigert dit soms vanaf de achtergrond; de wakelock houdt de processor dan in elk geval wakker.
            try { App.log(c, "APP", "doorwerken: dienst geweigerd (" + e.getClass().getSimpleName() + ")"); } catch (Throwable ignored) { }
        }
    }

    private static void einde() {
        synchronized (LOCK) {
            if (bezig > 0) bezig--;
            if (bezig > 0) return;
            try { if (h != null) h.removeCallbacks(DIENST); } catch (Throwable ignored) { }
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
        }
        DoorwerkDienst.klaar();
    }

    /** Hoort bij één begin(); sluiten mag vaker (telt één keer). */
    static final class Klaar implements AutoCloseable {
        private boolean dicht;
        @Override public void close() {
            synchronized (this) { if (dicht) return; dicht = true; }
            einde();
        }
    }
}
