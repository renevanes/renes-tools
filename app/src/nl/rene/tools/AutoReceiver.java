package nl.rene.tools;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;

/**
 * Gebeurtenissen voor Automatiseringen. Deze (geëxporteerde) ontvanger krijgt alleen de beschermde
 * Bluetooth-meldingen van Android; plekken en "Niet nu" gaan via de interne {@link Priv}.
 */
public class AutoReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (a == null) return;
        try {
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a) || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                // goAsync: er moet misschien nog even een locatie bepaald worden (max. ~25 s).
                Auto.Done done = new Auto.Done(goAsync());
                // Twee luisteraars (automatiseringen en Mijn auto): pas klaar als beide klaar zijn
                java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(2);
                Runnable autoDone = once(() -> { if (left.decrementAndGet() == 0) done.run(); });
                Runnable carDone = once(() -> { if (left.decrementAndGet() == 0) done.run(); });
                boolean on = BluetoothDevice.ACTION_ACL_CONNECTED.equals(a);
                try { Auto.onBluetooth(c, on, d.getAddress(), autoDone); }
                catch (Exception e) { autoDone.run(); App.log(c, "AutoReceiver", android.util.Log.getStackTraceString(e)); }
                try { Car.onBluetooth(c, on, d.getAddress(), carDone); }
                catch (Exception e) { carDone.run(); throw e; }
            }
        } catch (Exception e) {
            App.log(c, "AutoReceiver", android.util.Log.getStackTraceString(e));
        }
    }

    /** Een "klaar"-melding die maar één keer telt, ook als hij twee keer wordt aangeroepen. */
    static Runnable once(Runnable r) {
        java.util.concurrent.atomic.AtomicBoolean did = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> { if (did.compareAndSet(false, true)) r.run(); };
    }

    /** Interne ontvanger (niet geëxporteerd): aankomen/weggaan bij een plek en de knop "Niet nu". */
    public static class Priv extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i == null ? null : i.getAction();
            if (a == null) return;
            try {
                if (Auto.ACTION_PROX.equals(a)) {
                    if (!i.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return;
                    Auto.onProximity(c, i.getStringExtra("id"), i.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false));
                } else if (AutoActions.ACTION_TIME.equals(a)) {
                    AutoActions.onTime(c, i.getStringExtra("id"));
                } else if (Auto.ACTION_DISMISS.equals(a)) {
                    Auto.cancel(c, i.getIntExtra("nid", 0));
                }
            } catch (Exception e) {
                App.log(c, "AutoReceiver", android.util.Log.getStackTraceString(e));
            }
        }
    }
}
