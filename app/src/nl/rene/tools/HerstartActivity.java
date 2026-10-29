package nl.rene.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Start de app opnieuw na het terugzetten van alle gegevens. Draait in een eigen, kort proces (":herstart"):
 * stopt het oude proces (met alles wat nog in het geheugen stond) en opent de app daarna vers, bij het lijstje
 * van wat je opnieuw moet toestaan. Niet te starten door andere apps (exported="false").
 */
public class HerstartActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pid = getIntent() == null ? -1 : getIntent().getIntExtra("pid", -1);
        if (pid > 0 && pid != android.os.Process.myPid()) android.os.Process.killProcess(pid);
        try {
            startActivity(new Intent(this, MainActivity.class).putExtra("open", "herstel")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (Throwable ignored) { }
        finish();
        Runtime.getRuntime().exit(0);
    }
}
