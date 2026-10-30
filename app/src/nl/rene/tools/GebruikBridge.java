package nl.rene.tools;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Brugfuncties voor "Gebruik" (Android.gebruikXxx), bovenaan de brugketen. Het rekenwerk staat in Gebruik (puur,
 * getest), de opslag en samenstelling in GebruikStore, de foto in GebruikShot, apparaatbeheer in GebruikAdmin.
 * Alles blijft op de telefoon.
 */
public class GebruikBridge extends LocBridge {

    GebruikBridge(MainActivity act) { super(act); }

    private static long lng(String s, long def) { try { return s == null || s.isEmpty() ? def : Long.parseLong(s.trim()); } catch (Exception e) { return def; } }

    /** Alles voor de kop van het scherm: toegang, apparaatbeheer, foto-mogelijkheden, instellingen. Neemt meteen nieuwe gebeurtenissen over. */
    @JavascriptInterface public String gebruikState() {
        try {
            GebruikJob.ensureScheduled(ctx);
            try { GebruikLive.start(ctx); } catch (Throwable ignored) { }
            new Thread(() -> { try { GebruikStore.collect(ctx); } catch (Throwable ignored) { } }, "gebruik-collect").start();
            return GebruikStore.state(ctx).toString();
        } catch (Throwable e) { App.log(ctx, "GEBRUIK", "stand: " + e); return "{}"; }
    }

    @JavascriptInterface public String gebruikOverview(String period) {
        try { return GebruikStore.overview(ctx, period).toString(); } catch (Throwable e) { return GebruikStore.err(String.valueOf(e.getMessage())).toString(); }
    }

    @JavascriptInterface public String gebruikUnlocks(String from, String to) {
        long now = System.currentTimeMillis();
        try { return GebruikStore.unlocksJson(ctx, lng(from, now - 7L * 86_400_000L), lng(to, now)).toString(); } catch (Throwable e) { return GebruikStore.err("Dat lukt niet").toString(); }
    }

    @JavascriptInterface public String gebruikFunctions(String from, String to) {
        long now = System.currentTimeMillis();
        try { return GebruikStore.functionsJson(ctx, lng(from, now - 7L * 86_400_000L), lng(to, now)).toString(); } catch (Throwable e) { return GebruikStore.err("Dat lukt niet").toString(); }
    }

    @JavascriptInterface public String gebruikNotifs(String from, String to) {
        long now = System.currentTimeMillis();
        try { return GebruikStore.notifsJson(ctx, lng(from, now - 7L * 86_400_000L), lng(to, now)).toString(); } catch (Throwable e) { return GebruikStore.err("Dat lukt niet").toString(); }
    }

    /** Instelling(en) bewaren; geeft de nieuwe instellingen terug. */
    @JavascriptInterface public String gebruikSave(String json) {
        try { GebruikStore.saveSettings(ctx, new JSONObject(json)); GebruikJob.ensureScheduled(ctx); if (GebruikStore.collecting(ctx)) GebruikLive.start(ctx); else GebruikLive.stop(); return GebruikStore.settings(ctx).toString(); }
        catch (Throwable e) { return GebruikStore.err("Opslaan lukt niet").toString(); }
    }

    /** Nu meteen de nieuwste gebeurtenissen overnemen. */
    @JavascriptInterface public void gebruikRefresh() {
        new Thread(() -> { try { GebruikStore.collect(ctx); a.js("onGebruik", "\"ok\""); } catch (Throwable ignored) { } }, "gebruik-refresh").start();
    }

    /** Het Android-scherm "Toegang tot gebruik" openen (die toegang geeft Rene zelf). */
    @JavascriptInterface public void gebruikOpenAccess() {
        a.h.post(() -> {
            try { a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { try { a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Exception x) { Toast.makeText(a, "Open Instellingen → Toegang tot gebruik", Toast.LENGTH_LONG).show(); } }
        });
    }

    /** "Boven andere apps tonen" openen (nodig voor de foto vanaf het vergrendelscherm). */
    @JavascriptInterface public void gebruikOpenOverlay() {
        a.h.post(() -> {
            try { a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.getPackageName()))); }
            catch (Exception e) { Toast.makeText(a, "Open Instellingen → Boven andere apps tonen", Toast.LENGTH_LONG).show(); }
        });
    }

    /** Apparaatbeheer aan (Android vraagt dat zelf te bevestigen) of uit. */
    @JavascriptInterface public void gebruikAdmin(boolean on) {
        a.h.post(() -> {
            try {
                if (on) {
                    Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, GebruikStore.admin(ctx))
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Zo kan Gebruik zien wanneer je met pincode, patroon of wachtwoord ontgrendelt en wanneer dat misging. Je kunt het altijd zelf weer uitzetten; daarna kan de app ook weer verwijderd worden. De app verandert niets aan je telefoon.");
                    a.startActivity(i);
                } else {
                    DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
                    if (dpm != null) dpm.removeActiveAdmin(GebruikStore.admin(ctx));
                    a.js("onGebruik", "\"admin\"");
                }
            } catch (Exception e) { Toast.makeText(a, "Dat lukt niet", Toast.LENGTH_SHORT).show(); }
        });
    }

    /** Proefopname met de voorcamera (om te zien of het op deze telefoon lukt). */
    @JavascriptInterface public void gebruikTestShot() {
        GebruikStore.prefs(ctx).edit().putString("lastShot", "bezig").apply();
        GebruikShot.capture(ctx, "proef");
    }

    @JavascriptInterface public String gebruikPhotos() {
        try { return GebruikStore.photosJson(ctx).toString(); } catch (Throwable e) { return "{}"; }
    }

    @JavascriptInterface public String gebruikPhoto(String id) {
        try { return GebruikStore.photo(ctx, id); } catch (Throwable e) { return ""; }
    }

    @JavascriptInterface public void gebruikClearPhotos() { try { GebruikStore.clearPhotos(ctx); } catch (Throwable ignored) { } }

    @JavascriptInterface public void gebruikClearAll() { try { GebruikStore.clearAll(ctx); } catch (Throwable ignored) { } }
}
