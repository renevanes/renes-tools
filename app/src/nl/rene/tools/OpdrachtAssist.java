package nl.rene.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * De digitale assistent van Android (ACTION_ASSIST): alleen als Rene Tools daar zelf kiest, of als een andere app
 * hem zo opent. Doet niets anders dan de assistent openen met de microfoon aan; luisteren en uitschrijven blijven op
 * de telefoon en er gebeurt niets zonder een tik. Geen eigen scherm: meteen door naar de app.
 */
public class OpdrachtAssist extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent in = getIntent();
            if (in != null && (Intent.ACTION_ASSIST.equals(in.getAction()))) startActivity(Opdracht.intent(this));
        } catch (Throwable t) { App.log(this, "AI", "assist: " + t.getClass().getSimpleName()); }
        finish();
    }
}
