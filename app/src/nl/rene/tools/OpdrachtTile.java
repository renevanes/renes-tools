package nl.rene.tools;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tegel "Opdracht" in de snelle instellingen: tikken = de assistent luistert. */
public class OpdrachtTile extends TileService {
    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.setLabel(Taal.t(this, "Opdracht"));
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_mic));
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(Taal.t(this, "Tik en praat"));
        t.updateTile();
    }

    @Override public void onClick() {
        if (isLocked()) { unlockAndRun(this::open); return; } // eerst ontgrendelen
        open();
    }

    private void open() {
        Intent i = Opdracht.intent(this);
        try {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 4709, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            else legacy(i);
        } catch (Throwable t) { App.log(this, "AI", "tegel: " + t.getClass().getSimpleName()); }
    }

    @SuppressWarnings("deprecation")
    private void legacy(Intent i) { startActivityAndCollapse(i); }
}
