package nl.rene.tools;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** Optionele audiohulp: Android laat een actieve toegankelijkheidsdienst spraak via de microfoon delen.
 * Geen scherminhoud of bediening; de zichtbare CallRecorderService blijft verantwoordelijk voor de opname. */
public final class CallRecordingAccess extends AccessibilityService {
    private static volatile boolean connected;

    static boolean ready() { return connected || AutoA11y.ready(); }

    @Override protected void onServiceConnected() { connected = true; }
    @Override public boolean onUnbind(Intent intent) { connected = false; return super.onUnbind(intent); }
    @Override public void onDestroy() { connected = false; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
