package nl.rene.tools;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Onzichtbare tussenstap voor de knop "Starten" in een melding van Automatiseringen.
 * Android staat niet toe dat een ontvanger na een tik op een melding een app opent, een activiteit wel.
 */
public class AutoRunActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String id = getIntent() == null ? null : getIntent().getStringExtra("id");
        boolean doRun = getIntent() != null && getIntent().getBooleanExtra("run", false);
        JSONObject r = id == null ? null : Auto.rule(this, id);
        if (id != null) Auto.cancel(this, Auto.notifId(id));
        if (r != null) {
            boolean hasSteps = r.optJSONArray("steps") != null && r.optJSONArray("steps").length() > 0;
            if (doRun && hasSteps && AutoA11y.ready()) {
                Auto.log(this, r, "Gestart vanuit de melding");
                AutoA11y.run(this, r);
            } else {
                if (!Auto.openApp(this, r)) Toast.makeText(this, "De app is niet gevonden", Toast.LENGTH_LONG).show();
                else if (doRun && hasSteps) Toast.makeText(this, "Automatisch tikken staat uit: druk zelf op de knoppen", Toast.LENGTH_LONG).show();
                if (doRun) Auto.log(this, r, "App geopend vanuit de melding");
            }
        }
        finish();
    }
}
