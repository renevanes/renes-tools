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
 * Brugfuncties voor Slaappraat (Android.sleepXxx). Tussen CheckinBridge en AiBridge. Het luisteren zelf doet
 * SleepService; de nachten en stukjes staan in SleepTalk (alleen op de telefoon).
 */
public class SleepBridge extends CheckinBridge {

    SleepBridge(MainActivity act) { super(act); }

    /** Alles voor het scherm: of er geluisterd wordt, hoe hard het nu is, de instellingen en de nachten. */
    @JavascriptInterface public String sleepState() {
        try {
            JSONObject o = new JSONObject();
            o.put("phase", SleepService.phase).put("night", SleepService.night).put("since", SleepService.since).put("stopAt", SleepService.stopAt)
                    .put("heard", SleepService.heard).put("level", Math.round(SleepService.levelDb)).put("floor", Math.round(SleepService.floorDb))
                    .put("txDone", SleepService.txDone).put("txTotal", SleepService.txTotal).put("message", SleepService.message)
                    .put("live", new JSONObject().put("talk", SleepService.liveTalk).put("noise", SleepService.liveNoise).put("open", SleepService.liveOpen)
                            .put("text", SleepService.liveText).put("at", SleepService.liveAt).put("wait", SleepService.directWait).put("busy", SleepService.directBusy))
                    .put("hearing", SleepService.hearingNow).put("probeHits", SleepService.probeHits).put("probeLeft", Math.max(0, SleepService.probeUntil - System.currentTimeMillis()))
                    .put("notifOk", Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
            o.put("mic", ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    .put("notif", Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
            Transcribe.Model m = SleepTalk.model(ctx);
            o.put("model", m == null ? "" : m.label);
            // Welke spraakmodellen er zijn (voor de keuze van het uitschrijfmodel)
            JSONArray ms = new JSONArray();
            for (Transcribe.Model x : Transcribe.MODELS) ms.put(new JSONObject().put("id", x.id).put("label", x.label).put("ok", Transcribe.modelFile(ctx, x).isFile()));
            o.put("models", ms);
            o.put("set", new JSONObject().put("sens", SleepTalk.sens(ctx)).put("stopAt", SleepTalk.stopAt(ctx)).put("transcribe", SleepTalk.transcribe(ctx))
                    .put("keepNoise", SleepTalk.keepNoise(ctx)).put("keep", SleepTalk.keepDays(ctx)).put("remindAt", SleepTalk.remindAt(ctx)).put("quiet", SleepTalk.quiet(ctx)).put("direct", SleepTalk.direct(ctx))
                    .put("directWhen", SleepTalk.directWhen(ctx)).put("directBat", SleepTalk.directBat(ctx)).put("stopBat", SleepTalk.minBattery(ctx))
                    .put("maxAudio", SleepTalk.maxAudioH(ctx)).put("minFree", SleepTalk.minFree(ctx) / (1024 * 1024)).put("whenFull", SleepTalk.whenFull(ctx))
                    .put("clipMax", SleepTalk.clipMaxS(ctx)).put("hang", SleepTalk.hangMs(ctx)).put("pre", SleepTalk.preS(ctx))
                    .put("format", SleepTalk.wavFormat(ctx) ? "wav" : "aac").put("txModel", SleepTalk.txModelId(ctx)));
            SleepTalk.cleanupSometimes(ctx, SleepService.running() ? SleepService.night : "");
            JSONArray ns = SleepTalk.nights(ctx);
            long bytes = 0;
            for (int i = 0; i < ns.length(); i++) bytes += ns.getJSONObject(i).optLong("bytes");
            o.put("nights", ns).put("bytes", bytes);
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

    /** Proefronde van 30 seconden (niets wordt bewaard). "" of een melding zoals bij sleepStart. */
    @JavascriptInterface public String sleepProbe() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "mic";
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "notif";
        if (SleepService.running()) return "Slaappraat is al bezig";
        if (CallRecorderService.busy) return "Stop eerst de gespreksopname";
        if ("recording".equals(Music.state)) return "Stop eerst muziekherkenning";
        if ("recording".equals(VoiceNote.state)) return "Stop eerst het inspreken";
        sleepStopPlay();
        SleepService.night = ""; SleepService.phase = "proef"; SleepService.probeHits = 0; SleepService.probeUntil = System.currentTimeMillis() + SleepService.PROBE_MS; SleepService.message = "";
        a.h.post(() -> {
            try {
                Intent i = new Intent(a, SleepService.class).setAction("probe");
                if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
            } catch (Exception e) { SleepService.phase = ""; SleepService.message = "Android kon de proef niet starten. Probeer het opnieuw."; a.js("onSleep", "{}"); }
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

    /** Instelling: sens (1-3), stopAt ("HH:mm" of ""), transcribe, direct, keepNoise, quiet ("true"/"false"), keep (7/14/30),
     *  de keuzes van Meer instellingen (vaste lijsten, zie SleepTalk: directWhen, directBat, stopBat, maxAudio, minFree,
     *  whenFull, clipMax, hang, pre, format, txModel) en reset (Meer instellingen weer standaard). "" of melding. */
    @JavascriptInterface public String sleepSet(String name, String value) {
        if (name == null || value == null) return "Ongeldig";
        android.content.SharedPreferences.Editor e = SleepTalk.prefs(ctx).edit();
        switch (name) {
            case "sens": if (!value.matches("[123]")) return "Ongeldig"; e.putInt("sens", Integer.parseInt(value)); break;
            case "stopAt": if (!SleepTalk.validTime(value)) return "Ongeldige tijd"; e.putString("stopAt", value); break;
            case "remindAt": if (!SleepTalk.validTime(value)) return "Ongeldige tijd"; e.putString("remindAt", value).apply(); SleepReminder.arm(ctx); return "";
            case "transcribe": case "keepNoise": case "direct": e.putBoolean(name, "true".equals(value)); break;
            case "quiet":
                e.putBoolean("quiet", "true".equals(value)).apply();
                // Loopt hij al, dan de melding meteen omzetten (anders geldt het vanaf de volgende keer)
                if (SleepService.running()) a.h.post(() -> { try { a.startService(new Intent(a, SleepService.class).setAction("refresh")); } catch (Exception ignored) { } });
                return "";
            case "keep": if (!value.matches("7|14|30")) return "Ongeldig"; e.putInt("keep", Integer.parseInt(value)); break;
            // Meer instellingen: alleen de vaste keuzes
            case "directWhen": if (!value.matches("altijd|lader|accu")) return "Ongeldig"; e.putString(name, value); break;
            case "whenFull": if (!value.matches("stop|geluid|oudste")) return "Ongeldig"; e.putString(name, value); break;
            case "format": if (!value.matches("aac|wav")) return "Ongeldig"; e.putString(name, value); break;
            case "txModel": if (!value.isEmpty() && Transcribe.model(value) == null) return "Ongeldig"; e.putString(name, value); break;
            case "directBat": case "stopBat": case "maxAudio": case "minFree": case "clipMax": case "hang": case "pre": {
                int v;
                try { v = Integer.parseInt(value); } catch (NumberFormatException x) { return "Ongeldig"; }
                int[] ok = "directBat".equals(name) ? SleepTalk.DIRECT_BATS : "stopBat".equals(name) ? SleepTalk.STOP_BATS : "maxAudio".equals(name) ? SleepTalk.MAX_HOURS
                        : "minFree".equals(name) ? SleepTalk.MIN_FREES : "clipMax".equals(name) ? SleepTalk.CLIP_MAXES : "hang".equals(name) ? SleepTalk.HANGS : SleepTalk.PRES;
                if (!SleepTalk.allowed(v, ok)) return "Ongeldig";
                e.putInt(name, v);
                break;
            }
            case "reset": for (String k : SleepTalk.ADVANCED) e.remove(k); break;
            default: return "Ongeldig";
        }
        e.apply();
        return "";
    }

    /** Een nacht (alsnog) uitschrijven: in de dienst van Slaappraat (dan gaat het door met het scherm uit). */
    @JavascriptInterface public String sleepTranscribe(final String id) {
        if (!SleepTalk.safeId(id)) return "Onbekende nacht";
        if (SleepService.running()) return "Dat kan pas als Slaappraat klaar is met luisteren en uitschrijven";
        if (SleepTalk.model(ctx) == null) return "model";
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "mic";
        SleepService.night = id; SleepService.phase = "uitschrijven"; SleepService.txDone = 0; SleepService.txTotal = SleepTalk.open(ctx, id); // meteen te zien
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
