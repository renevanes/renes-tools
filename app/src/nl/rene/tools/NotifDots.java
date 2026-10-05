package nl.rene.tools;

import android.app.Notification;
import android.service.notification.StatusBarNotification;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Meldingsbolletjes op app-pictogrammen van het startscherm: welke apps nu een melding hebben.
 * Komt van dezelfde meldingentoegang als de meldingsgeschiedenis (HistoryListener); er wordt niets bewaard.
 */
final class NotifDots {

    private NotifDots() { }

    static volatile Set<String> pkgs = Collections.emptySet();

    static boolean has(String pkg) { return pkg != null && pkgs.contains(pkg); }

    /** Opnieuw bepalen uit de meldingen die er nu zijn (alleen als iets veranderde: het startscherm bijwerken). */
    static void refresh(HistoryListener l) {
        Set<String> s = new HashSet<>();
        try {
            StatusBarNotification[] all = l.getActiveNotifications();
            if (all != null) for (StatusBarNotification n : all) {
                Notification no = n.getNotification();
                if (no == null || n.getPackageName().equals(l.getPackageName())) continue;
                if ((no.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) continue;
                s.add(n.getPackageName());
            }
        } catch (Exception ignored) { }
        if (s.equals(pkgs)) return;
        pkgs = Collections.unmodifiableSet(s);
        HomeActivity a = HomeActivity.inst;
        if (a != null) a.h.post(() -> { if (a.desk != null) a.desk.dotsChanged(); });
    }

    static void clear() {
        pkgs = Collections.emptySet();
        HomeActivity a = HomeActivity.inst;
        if (a != null) a.h.post(() -> { if (a.desk != null) a.desk.dotsChanged(); });
    }
}
