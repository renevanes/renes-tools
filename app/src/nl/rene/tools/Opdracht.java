package nl.rene.tools;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Opdracht geven: de assistent openen met de microfoon meteen aan, vanaf de plekken buiten de app. Er gebeurt
 * daarna niets vanzelf: wat je zegt komt in het invoerveld en een opdracht wordt een kaart die je zelf bevestigt.
 * Het inspreken en uitschrijven blijft op de telefoon (zoals de microfoonknop van de assistent).
 *
 * - snelkoppeling (lang drukken op het app-icoon, of vastgezet op het startscherm): open = OPEN met het geheim
 * - tegel in de snelle instellingen: {@link OpdrachtTile}
 * - widget: {@link OpdrachtWidget}
 * - digitale assistent van Android (lang drukken op de aan/uit- of thuisknop, als Rene Tools daar kiest): {@link OpdrachtAssist}
 */
final class Opdracht {
    private Opdracht() { }

    /** Wat MainActivity aan de interface doorgeeft: de assistent met de microfoon aan. */
    static final String OPEN = "ai-spreek";

    static Intent intent(Context c) {
        return new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra("open", OPEN).putExtra("tok", App.token(c))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    /** Is Tools nu de digitale assistent van de telefoon? (Settings.Secure "assistant"; null = onbekend) */
    static Boolean isAssistent(Context c) {
        try {
            String s = android.provider.Settings.Secure.getString(c.getContentResolver(), "assistant");
            if (s == null) return null;
            return s.startsWith(c.getPackageName() + "/");
        } catch (Throwable t) { return null; }
    }

    /** Het scherm van Android waar je de digitale assistent kiest. "" = geopend, anders een melding. */
    static String assistentKiezen(Activity a) {
        String[] acties = {"android.settings.VOICE_INPUT_SETTINGS", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", android.provider.Settings.ACTION_SETTINGS};
        for (String act : acties) {
            try { a.startActivity(new Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return ""; }
            catch (Throwable ignored) { }
        }
        return "De instellingen openen lukte niet";
    }

    /** Vraag het startscherm om de widget. "" = gevraagd, anders een melding. */
    static String widgetVastzetten(Context c) {
        if (Build.VERSION.SDK_INT < 26) return "Zet de widget op je startscherm via Widgets (lang drukken op het startscherm)";
        try {
            AppWidgetManager m = c.getSystemService(AppWidgetManager.class);
            if (m == null || !m.isRequestPinAppWidgetSupported()) return "Zet de widget op je startscherm via Widgets (lang drukken op het startscherm)";
            m.requestPinAppWidget(new ComponentName(c, OpdrachtWidget.class), null, null);
            return "";
        } catch (Throwable t) { return "Zet de widget op je startscherm via Widgets (lang drukken op het startscherm)"; }
    }
}
