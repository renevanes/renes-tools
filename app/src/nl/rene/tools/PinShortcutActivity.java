package nl.rene.tools;

import android.app.Activity;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Een app wil een snelkoppeling op het startscherm zetten (bijv. "contact toevoegen aan startscherm" of een
 * website vanuit Chrome). Android stuurt dat alleen naar het standaard-startscherm. Eerst vragen (met de naam
 * van de app die het vraagt), zodat een app niet ongemerkt een nep-snelkoppeling kan neerzetten (bijv. met het
 * logo van je bank). Snelkoppelingen van Rene's Tools zelf worden meteen geplaatst.
 */
public class PinShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean waiting = false;
        try {
            if (Build.VERSION.SDK_INT >= 26) waiting = handle();
        } catch (Exception e) { App.log(this, "START", "snelkoppeling vastzetten: " + e); }
        if (!waiting) finish();
    }

    /** true = er staat een vraag open (die sluit de activiteit zelf). */
    private boolean handle() {
        LauncherApps la = LauncherShortcuts.la(this);
        LauncherApps.PinItemRequest req = la == null ? null : la.getPinItemRequest(getIntent());
        if (req == null || !req.isValid() || req.getRequestType() != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) return false;
        // De klassieke skin kan geen snelkoppelingen tonen: dan niet aannemen (de app krijgt "mislukt" te horen)
        android.content.SharedPreferences lp = Launcher.prefs(this);
        boolean nativeHome = !lp.getBoolean("classic", false) && lp.getInt("bootFails", 0) < 2;
        HomeActivity cur = HomeActivity.inst;
        if (cur != null) nativeHome = cur.desk != null;
        if (!nativeHome) { Toast.makeText(this, "Snelkoppelingen kunnen alleen op de launcher met pagina's (niet in de klassieke skin)", Toast.LENGTH_LONG).show(); return false; }
        ShortcutInfo s = req.getShortcutInfo();
        if (s == null) return false;
        if (getPackageName().equals(s.getPackage())) { place(req, s); return false; }
        String app = s.getPackage();
        try { app = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(s.getPackage(), 0))); } catch (Exception ignored) { }
        android.graphics.drawable.Drawable icon = null;
        try { icon = la.getShortcutIconDrawable(s, getResources().getDisplayMetrics().densityDpi); } catch (Exception ignored) { }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Snelkoppeling toevoegen?")
                .setIcon(icon)
                .setMessage("“" + LauncherShortcuts.label(s) + "”\n\nAangevraagd door: " + app + "\nTik je hierop, dan opent " + app + ".")
                .setPositiveButton("Toevoegen", (d, w) -> { try { place(req, s); } catch (Exception e) { App.log(this, "START", "snelkoppeling vastzetten: " + e); } })
                .setNegativeButton("Annuleren", null)
                .setOnDismissListener(d -> finish())
                .show();
        return true;
    }

    private void place(LauncherApps.PinItemRequest req, ShortcutInfo s) {
        LauncherShortcuts.saveIcon(this, s);
        if (!req.accept()) return;
        LauncherShortcuts.enqueue(this, s.getPackage(), s.getId(), LauncherShortcuts.label(s));
        HomeActivity a = HomeActivity.inst;
        if (a != null && a.desk != null) a.h.post(() -> { if (a.desk != null) a.desk.takePinned(); });
        Toast.makeText(this, "Op het startscherm gezet: " + LauncherShortcuts.label(s), Toast.LENGTH_SHORT).show();
    }
}
