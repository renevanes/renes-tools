package nl.rene.tools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;

/** Android meldt hier een nieuw wifi- of mobiel netwerk (zie {@link Vpn#armAuto}); dan beslist Vpn of de VPN aan of uit moet. */
public class VpnNet extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !Vpn.ACTION_NET.equals(i.getAction())) return;
        Network n = i.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
        final Context app = c.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try { Vpn.onNetwork(app, n); } finally { pr.finish(); }
        }, "vpn-net").start();
    }
}
