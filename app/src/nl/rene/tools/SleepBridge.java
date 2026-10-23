package nl.rene.tools;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Brugfuncties voor Slaappraat (Android.sleepXxx). Tussen FeatureBridge en AiBridge. Het luisteren zelf doet
 * SleepService; de nachten en stukjes staan in SleepTalk (alleen op de telefoon).
 */
public class SleepBridge extends FeatureBridge {

    SleepBridge(MainActivity act) { super(act); }

    /** Alles voor het scherm: of er geluisterd wordt, hoe hard het nu is, de instellingen en de nachten. */
    @JavascriptInterface public String sleepState() {
        try {
            JSONObject o = new JSONObject();
            o.put("phase", SleepService.phase).put("night", SleepService.night).put("since", SleepService.since).put("stopAt", SleepService.stopAt)
                    .put("heard", SleepService.heard).put("level", Math.round(SleepService.levelDb)).put("floor", Math.round(SleepService.floorDb))
                    .put("txDone", SleepService.txDone).put("txTotal", SleepService.txTotal).put("message", SleepService.message);
            o.put("mic", ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    .put("notif", Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
            Transcribe.Model m = Transcribe.activeModel(ctx);
            o.put("model", m == null ? "" : m.label);
            o.put("set", new JSONObject().put("sens", SleepTalk.sens(ctx)).put("stopAt", SleepTalk.stopAt(ctx)).put("transcribe", SleepTalk.transcribe(ctx))
                    .put("keepNoise", SleepTalk.keepNoise(ctx)).put("keep", SleepTalk.keepDays(ctx)));
            SleepTalk.cleanupSometimes(ctx, SleepService.running() ? SleepService.night : "");
            o.put("nights", SleepTalk.nights(ctx));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** "" of een melding (de interface vraagt dan om toestemming of legt uit wat er in de weg zit). */
    @JavascriptInterface public String sleepStart() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "mic";
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "notif";
        if ("luistert".equals(SleepService.phase)) return "";
        if (CallRecorderService.busy) return "Stop eerst de gespreksopname";
        if ("recording".equals(Music.state)) return "Stop eerst muziekherkenning";
        if ("recording".equals(VoiceNote.state)) return "Stop eerst het inspreken";
        sleepStopPlay();
        a.h.post(() -> {
            try {
                Intent i = new Intent(a, SleepService.class).setAction("start");
                if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
            } catch (Exception e) { SleepService.message = "Android kon Slaappraat niet starten. Probeer het opnieuw."; a.js("onSleep", "{}"); }
        });
        return "";
    }

    @JavascriptInterface public void sleepStop() {
        a.h.post(() -> { try { a.startService(new Intent(a, SleepService.class).setAction("stop")); } catch (Exception ignored) { } });
    }

    /** Eén nacht met de stukjes. */
    @JavascriptInterface public String sleepNight(String id) { JSONObject n = SleepTalk.night(ctx, id); return n == null ? "" : n.toString(); }

    @JavascriptInterface public void sleepDeleteNight(String id) {
        if (id != null && id.equals(SleepService.night) && SleepService.running()) return; // de nacht die nu loopt niet
        sleepStopPlay();
        SleepTalk.deleteNight(ctx, id);
    }

    @JavascriptInterface public void sleepDeleteClip(String id, String clip) { sleepStopPlay(); SleepTalk.deleteClip(ctx, id, clip); }

    /** Instelling: sens (1-3), stopAt ("HH:mm" of ""), transcribe, keepNoise ("true"/"false"), keep (7/14/30). "" of melding. */
    @JavascriptInterface public String sleepSet(String name, String value) {
        if (name == null || value == null) return "Ongeldig";
        android.content.SharedPreferences.Editor e = SleepTalk.prefs(ctx).edit();
        switch (name) {
            case "sens": if (!value.matches("[123]")) return "Ongeldig"; e.putInt("sens", Integer.parseInt(value)); break;
            case "stopAt": if (!SleepTalk.validTime(value)) return "Ongeldige tijd"; e.putString("stopAt", value); break;
            case "transcribe": case "keepNoise": e.putBoolean(name, "true".equals(value)); break;
            case "keep": if (!value.matches("7|14|30")) return "Ongeldig"; e.putInt("keep", Integer.parseInt(value)); break;
            default: return "Ongeldig";
        }
        e.apply();
        return "";
    }

    /** Een nacht (alsnog) uitschrijven: in de dienst van Slaappraat (dan gaat het door met het scherm uit). */
    @JavascriptInterface public String sleepTranscribe(final String id) {
        if (!SleepTalk.safeId(id)) return "Onbekende nacht";
        if (SleepService.running()) return "Dat kan pas als Slaappraat klaar is met luisteren en uitschrijven";
        if (Transcribe.activeModel(ctx) == null) return "model";
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "mic";
        SleepService.phase = "uitschrijven"; SleepService.txDone = 0; SleepService.txTotal = SleepTalk.open(ctx, id); // meteen te zien
        a.h.post(() -> {
            try {
                Intent i = new Intent(a, SleepService.class).setAction("transcribe").putExtra("night", id);
                if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
            } catch (Exception e) { SleepService.phase = ""; SleepService.message = "Uitschrijven kon niet starten. Probeer het opnieuw."; a.js("onSleep", "{}"); }
        });
        return "";
    }

    private static android.media.MediaPlayer player;
    private static String playing = "";

    /** Een stukje afspelen; einde via onSleep({played}). */
    @JavascriptInterface public String sleepPlay(String id, String clip) {
        synchronized (SleepBridge.class) {
            sleepStopPlay();
            File f = SleepTalk.clipFile(ctx, id, clip);
            if (f == null || !f.isFile()) return "Het geluid van dit stukje is er niet meer";
            try {
                android.media.MediaPlayer p = new android.media.MediaPlayer();
                p.setDataSource(f.getPath());
                p.setOnCompletionListener(mp -> { sleepStopPlay(); a.js("onSleep", "{\"played\":true}"); });
                p.prepare(); p.start();
                player = p; playing = clip;
                return "";
            } catch (Exception e) { return "Afspelen lukt niet"; }
        }
    }

    @JavascriptInterface public void sleepStopPlay() {
        synchronized (SleepBridge.class) {
            if (player != null) { try { player.stop(); } catch (Exception ignored) { } try { player.release(); } catch (Exception ignored) { } player = null; }
            playing = "";
        }
    }

    @JavascriptInterface public String sleepPlaying() { return playing; }
}
