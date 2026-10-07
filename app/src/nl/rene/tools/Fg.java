package nl.rene.tools;

import android.app.Notification;
import android.app.Service;
import android.os.Build;

/**
 * Voorgronddienst starten zonder de hele app te laten crashen als Android het weigert
 * (Android 15+: dataSync maximaal 6 uur per dag; vanaf de achtergrond soms helemaal niet).
 */
final class Fg {
    private Fg() { }

    /** type = ServiceInfo.FOREGROUND_SERVICE_TYPE_*, of 0. Geeft false als Android het niet toestaat. */
    static boolean start(Service s, int id, Notification n, int type, int minSdkForType) {
        try {
            if (type != 0 && Build.VERSION.SDK_INT >= minSdkForType) s.startForeground(id, n, type);
            else s.startForeground(id, n);
            return true;
        } catch (Exception e) {
            App.log(s, "APP", "voorgronddienst geweigerd: " + s.getClass().getSimpleName() + " (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }
}
