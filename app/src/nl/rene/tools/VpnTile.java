package nl.rene.tools;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tegel "VPN" in de snelle instellingen van Android: tikken = aan/uit. */
public class VpnTile extends TileService {

    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        if (isLocked()) { unlockAndRun(this::toggle); return; } // eerst ontgrendelen
        toggle();
    }

    private void toggle() {
        if (VpnSvc.on()) { Vpn.stop(this, "tegel"); update(); return; }
        String r = Vpn.start(this, null, "tegel");
        if (r.isEmpty()) { update(); return; }
        openApp(); // toestemming nodig, nog geen tunnel, of Android liet het niet toe: dan de app
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).putExtra("open", "vpn").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 4708, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            else startActivityAndCollapseLegacy(i);
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void startActivityAndCollapseLegacy(Intent i) { startActivityAndCollapse(i); }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = VpnSvc.on();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("VPN");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_vpn));
        if (Build.VERSION.SDK_INT >= 29) {
            String sub;
            if (!on) sub = Vpn.tunnels(this).length() == 0 ? "Instellen" : "Uit";
            else sub = "connected".equals(VpnSvc.status) ? (VpnSvc.tunnelName == null ? "Aan" : VpnSvc.tunnelName) : "Verbinden…";
            t.setSubtitle(sub);
        }
        t.updateTile();
    }
}
