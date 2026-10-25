package nl.rene.tools;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Voorlezen (tekst naar spraak) gebruikt de stem van Android zelf, geen model van de app. Voor Modellen beheren:
 * kijken of er een voorleesfunctie is en of die een Nederlandse stem heeft, en de instellingen daarvan openen.
 */
final class TtsCheck {

    private TtsCheck() { }

    /** "" = nog niet gekeken, checking, ok, nodutch (geen Nederlandse stem), noengine (geen voorleesfunctie),
     *  slow (de voorleesfunctie reageerde niet op tijd). */
    static volatile String state = "";
    static volatile String engine = "";
    private static TextToSpeech probe;
    /** Welke controle (een late melding van een eerdere controle telt niet). */
    private static int gen;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable TIMEOUT = () -> { if ("checking".equals(state)) { state = "slow"; gen++; shut(); MainActivity.jsAll("onAiModels", ""); } };

    static JSONObject json() throws Exception { return new JSONObject().put("state", state).put("engine", engine); }

    /** Kijken (op de achtergrond van de hoofdthread); daarna meldt de pagina het via onAiModels. */
    static void run(Context c) {
        final Context app = c.getApplicationContext();
        H.post(() -> {
            if ("checking".equals(state)) return;
            state = "checking";
            final int g = ++gen;
            try {
                shut();
                probe = new TextToSpeech(app, st -> H.post(() -> { if (g == gen) done(app, st); }));
                // Antwoordt de motor niet, dan niet blijven wachten
                H.removeCallbacks(TIMEOUT);
                H.postDelayed(TIMEOUT, 10_000);
            } catch (Throwable t) { state = "noengine"; shut(); MainActivity.jsAll("onAiModels", ""); }
        });
    }

    private static void done(Context app, int status) {
        H.removeCallbacks(TIMEOUT);
        try {
            if (probe == null) return;
            if (status != TextToSpeech.SUCCESS) { state = "noengine"; return; }
            String pkg = probe.getDefaultEngine();
            try { engine = pkg == null ? "" : String.valueOf(app.getPackageManager().getApplicationLabel(app.getPackageManager().getApplicationInfo(pkg, 0))); }
            catch (Throwable t) { engine = pkg == null ? "" : pkg; }
            boolean nl = false;
            for (Locale l : new Locale[]{new Locale("nl", "NL"), new Locale("nl", "BE"), new Locale("nl")})
                if (probe.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) { nl = true; break; }
            state = nl ? "ok" : "nodutch";
        } catch (Throwable t) {
            state = "noengine";
        } finally {
            shut();
            MainActivity.jsAll("onAiModels", "");
        }
    }

    private static void shut() { try { if (probe != null) probe.shutdown(); } catch (Throwable ignored) { } probe = null; }

    /** De instellingen voor tekst-naar-spraak van Android (stem, snelheid), anders de gewone instellingen. */
    static void openSettings(Activity a) {
        try { a.startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
        catch (Throwable e) { try { a.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Throwable ignored) { } }
    }

    /** Een (Nederlandse) stem installeren bij de voorleesfunctie van de telefoon. */
    static void install(Activity a) {
        try { a.startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); }
        catch (Throwable e) { openSettings(a); }
    }
}
