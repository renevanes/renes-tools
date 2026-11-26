package nl.rene.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * De digitale assistent van Android (ACTION_ASSIST). Is Tools de digitale assistent (Rene koos dat zelf), dan opent de
 * assistent met de microfoon aan; luisteren en uitschrijven blijven op de telefoon en er gebeurt niets zonder een tik.
 * Anders (een andere app stuurt ACTION_ASSIST hierheen) alleen de assistent, zonder microfoon en zonder het geheim.
 * Geen eigen scherm: meteen door naar de app.
 */
public class OpdrachtAssist extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent in = getIntent();
            if (in != null && Intent.ACTION_ASSIST.equals(in.getAction())) {
                // Alleen als Rene Tools zelf als digitale assistent koos: meteen luisteren. Anders (een andere app die
                // ACTION_ASSIST hierheen stuurt, of een Android-versie zonder RoleManager) gewoon de assistent, zonder microfoon.
                startActivity(Boolean.TRUE.equals(Opdracht.isAssistent(this)) ? Opdracht.intent(this) : Opdracht.gewoon(this));
            }
        } catch (Throwable t) { App.log(this, "AI", "assist: " + t.getClass().getSimpleName()); }
        finish();
    }
}
