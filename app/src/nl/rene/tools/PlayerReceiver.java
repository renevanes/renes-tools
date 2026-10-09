package nl.rene.tools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Knoppen van de speler-widget (en het zwevende venster): afspelen/pauze, vorige, volgende, terug, vooruit, stop. */
public class PlayerReceiver extends BroadcastReceiver {
    static final String PP = "nl.rene.tools.PLAYER_PP", NEXT = "nl.rene.tools.PLAYER_NEXT", PREV = "nl.rene.tools.PLAYER_PREV",
            BACK = "nl.rene.tools.PLAYER_BACK", FWD = "nl.rene.tools.PLAYER_FWD", STOP = "nl.rene.tools.PLAYER_STOP";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (a == null || !App.unlocked(c)) return;
        final PendingResult pr = goAsync(); // volgende/vorige leest bestanden: niet op de hoofdthread
        new Thread(() -> {
            try {
                switch (a) {
                    case PP: Player.playPause(c); break;
                    case NEXT: { String e = Player.step(c, null, 1); if (!e.isEmpty()) Player.toast(c, e); break; }
                    case PREV: { String e = Player.step(c, null, -1); if (!e.isEmpty()) Player.toast(c, e); break; }
                    case BACK: Player.skip(c, -1); break;
                    case FWD: Player.skip(c, 1); break;
                    case STOP: Player.stop(c); break;
                    default: break;
                }
                Player.changed(c);
            } catch (Throwable ignored) { }
            finally { pr.finish(); }
        }, "player-button").start();
    }
}
