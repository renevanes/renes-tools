package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * De ene speler voor radio en podcasts. Er speelt er altijd hooguit één: wie begint, neemt het over (en ook een
 * lopende slaaptimer). Onthoudt wat het laatst speelde, zodat de mini-speler, de widget en het zwevende venster
 * weten wat ze moeten tonen.
 */
final class Player {
    private Player() { }

    static final String RADIO = "radio", PODCAST = "podcast";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("player", Context.MODE_PRIVATE); }

    static String src(Context c) { return prefs(c).getString("src", ""); }

    /**
     * src begint te spelen: de andere stoppen. Geeft de slaaptimer van de andere terug (tijdstip in ms, 0 = geen),
     * zodat die gewoon doorloopt.
     */
    static long takeOver(Context c, String src) {
        if (!src.equals(src(c))) prefs(c).edit().putString("src", src).apply();
        long now = System.currentTimeMillis(), sleep = 0;
        if (PODCAST.equals(src)) {
            String st = RadioService.status;
            if (RadioService.inst != null && !"stopped".equals(st)) {
                if (RadioService.sleepAt > now + 30_000) sleep = RadioService.sleepAt;
                RadioService.send(c, RadioService.STOP, null); // de radio laat je niet op de achtergrond doorbufferen
            }
        } else if (RADIO.equals(src)) {
            if (PodcastService.inst != null) {
                if (PodcastService.sleepAt > now + 30_000) sleep = PodcastService.sleepAt;
                else if (PodcastService.sleepEnd && PodcastService.dur > 0) {
                    // "Na deze aflevering" kan de radio niet: dan stopt hij op het moment dat de aflevering af zou zijn
                    long left = (long) ((PodcastService.dur - PodcastService.pos) / Math.max(0.5f, PodcastService.speed));
                    if (left > 30_000) sleep = now + left;
                }
                PodcastService.send(c, PodcastService.YIELD, null); // pauzeren, plek bewaren, melding weg
            }
        }
        changed(c);
        return sleep;
    }

    /** Iets aan de speler veranderd: widget (en straks het zwevende venster) bijwerken. */
    static void changed(Context c) {
        try { RadioWidget.refresh(c); } catch (Throwable ignored) { }
    }
}
