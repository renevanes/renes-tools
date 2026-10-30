package nl.rene.tools;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Apparaatbeheer voor "Gebruik": Android meldt hier of een poging bij het vergrendelscherm gelukt of mislukt is
 * (onPasswordSucceeded / onPasswordFailed). Rene zet dit zelf aan en kan het ook zelf weer uitzetten (daarna kan de app
 * ook weer verwijderd worden). De app gebruikt het alleen om te zien wanneer er met pincode ontgrendeld werd en wat er
 * daarna gebeurde, en eventueel voor een foto bij de voorcamera; het verandert niets aan de telefoon.
 */
public class GebruikAdmin extends DeviceAdminReceiver {

    @Override public void onPasswordSucceeded(Context context, Intent intent) {
        try { GebruikStore.onPassword(context.getApplicationContext(), true); } catch (Throwable ignored) { }
    }

    @Override public void onPasswordFailed(Context context, Intent intent) {
        try { GebruikStore.onPassword(context.getApplicationContext(), false); } catch (Throwable ignored) { }
    }

    @Override public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Apparaatbeheer uitzetten. Daarna ziet Gebruik mislukte pogingen en ontgrendelingen-na-mislukt niet meer, en kan de app weer verwijderd worden.";
    }

    @Override public void onDisabled(Context context, Intent intent) {
        try { GebruikStore.prefs(context.getApplicationContext()).edit().putInt("failRun", 0).apply(); } catch (Throwable ignored) { }
    }
}
