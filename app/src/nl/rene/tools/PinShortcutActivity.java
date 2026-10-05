package nl.rene.tools;

import android.app.Activity;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Een app wil een snelkoppeling op het startscherm zetten (bijv. "contact toevoegen aan startscherm" of een
 * website vanuit Chrome). Android stuurt dat alleen naar het standaard-startscherm. Zonder eigen scherm:
 * meteen accepteren, in de wachtrij zetten en het werkblad plaatst hem.
 */
public class PinShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            if (Build.VERSION.SDK_INT >= 26) handle();
        } catch (Exception e) { App.log(this, "START", "snelkoppeling vastzetten: " + e); }
        finish();
    }

    private void handle() {
        LauncherApps la = LauncherShortcuts.la(this);
        LauncherApps.PinItemRequest req = la == null ? null : la.getPinItemRequest(getIntent());
        if (req == null || !req.isValid() || req.getRequestType() != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) return;
        // De klassieke skin kan geen snelkoppelingen tonen: dan niet aannemen (de app krijgt "mislukt" te horen)
        android.content.SharedPreferences lp = Launcher.prefs(this);
        boolean nativeHome = !lp.getBoolean("classic", false) && lp.getInt("bootFails", 0) < 2;
        HomeActivity cur = HomeActivity.inst;
        if (cur != null) nativeHome = cur.desk != null;
        if (!nativeHome) { Toast.makeText(this, "Snelkoppelingen kunnen alleen op de launcher met pagina's (niet in de klassieke skin)", Toast.LENGTH_LONG).show(); return; }
        ShortcutInfo s = req.getShortcutInfo();
        if (s == null) return;
        LauncherShortcuts.saveIcon(this, s);
        if (!req.accept()) return;
        LauncherShortcuts.enqueue(this, s.getPackage(), s.getId(), LauncherShortcuts.label(s));
        HomeActivity a = HomeActivity.inst;
        if (a != null && a.desk != null) a.h.post(() -> { if (a.desk != null) a.desk.takePinned(); });
        Toast.makeText(this, "Op het startscherm gezet: " + LauncherShortcuts.label(s), Toast.LENGTH_SHORT).show();
    }
}
