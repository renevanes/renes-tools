package nl.rene.tools;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Android bindt deze service alleen na expliciete meldingentoegang in de systeeminstellingen. */
public final class HistoryListener extends NotificationListenerService {
    private final java.util.concurrent.ThreadPoolExecutor worker = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<Runnable>(128));
    @Override public void onListenerConnected() { NotificationHistory.connected = true; }
    @Override public void onListenerDisconnected() { NotificationHistory.connected = false; }
    @Override public void onDestroy() { NotificationHistory.connected = false; worker.shutdown(); super.onDestroy(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !NotificationHistory.enabled(this)) return;
        try { worker.execute(() -> save(sbn)); }
        catch (java.util.concurrent.RejectedExecutionException ex) {
            NotificationHistory.prefs(this).edit().putString("error", "Er kwamen te veel meldingen tegelijk binnen; enkele zijn niet bewaard.").apply();
        }
    }
    private void save(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || sbn.getPackageName().equals(getPackageName()) ||
                (n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) != 0) return;
        try {
            Bundle e = n.extras;
            if (e == null) return;
            String title = HistoryText.clean(e.getCharSequence(Notification.EXTRA_TITLE), 1000);
            CharSequence body = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (body == null || body.length() == 0) body = e.getCharSequence(Notification.EXTRA_TEXT);
            if (body == null || body.length() == 0) {
                CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) { StringBuilder joined = new StringBuilder(); for (CharSequence line : lines) { if (joined.length() > 0) joined.append('\n'); joined.append(HistoryText.clean(line, 4000)); if (joined.length() >= 8000) break; } body = joined; }
            }
            String text = HistoryText.clean(body, 8000);
            if (title.isEmpty() && text.isEmpty()) return;
            String app = sbn.getPackageName();
            try { app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(app, 0)).toString(); } catch (Exception ignored) { }
            NotificationHistory.record(this, sbn.getKey(), sbn.getPostTime(), sbn.getPackageName(), HistoryText.clean(app, 200), title, text);
        } catch (Exception ex) {
            NotificationHistory.prefs(this).edit().putString("error", "Melding bewaren mislukt. Controleer de vrije opslagruimte.").apply();
            // Geen meldingstekst in het foutrapport.
        }
    }
}
