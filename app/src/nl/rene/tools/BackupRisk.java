package nl.rene.tools;

import android.content.Context;
import org.json.JSONObject;

/** Hoog risico is altijd expliciet, alleen versleuteld en alleen voor deze installatie toegestaan. */
final class BackupRisk {
    private BackupRisk() { }
    static final class Epoch {
        private long value;
        synchronized long ticket() { return value; }
        synchronized boolean current(long ticket) { return value == ticket; }
        synchronized void changed() { value++; }
    }
    static final Epoch EPOCH = new Epoch();
    static boolean canMutate(long ticket) { synchronized (EPOCH) { return EPOCH.current(ticket) && !AllBackup.running.get() && !AllBackup.busy; } }
    static boolean enabled(Context c, String id) {
        synchronized (EPOCH) {
        return ToolRegister.ADAPTERS.containsKey(id) && Secure.on(c) && AllBackup.enabledPart(c, "app") && ToolRegister.CONSENT.get(id).equals(Secure.prefs(c).getAll().get("risk_" + id));
        }
    }
    static String set(Context c, String id, boolean on) {
        return set(c, id, on, EPOCH.ticket());
    }
    static String set(Context c, String id, boolean on, long ticket) {
        synchronized (EPOCH) {
        if (!ToolRegister.ADAPTERS.containsKey(id)) return Taal.T("Onbekend onderdeel");
        if (AllBackup.running.get() || AllBackup.busy) return Taal.T("Er loopt al een backup");
        if (on && !EPOCH.current(ticket)) return Taal.T("Instellingen zijn intussen gewijzigd. Probeer opnieuw.");
        if (on && !Secure.on(c)) return Taal.T("Zet eerst Versleutelen aan");
        if (on && !AllBackup.enabledPart(c, "app")) return Taal.T("Zet eerst Alle appgegevens aan");
        if (on && !Lock.deviceSecure(c)) return Taal.T("Stel eerst een schermvergrendeling in op je telefoon");
        if (!on) EPOCH.changed();
        if (!Secure.prefs(c).edit().putString("risk_" + id, on ? ToolRegister.CONSENT.get(id) : "").commit()) return Taal.T("Instellingen bewaren lukt niet");
        return "";
        }
    }
    static android.content.SharedPreferences.Editor revokeEditor(Context c) {
        if (!Thread.holdsLock(EPOCH)) throw new IllegalStateException();
        EPOCH.changed(); android.content.SharedPreferences.Editor edit = Secure.prefs(c).edit();
        for (String key : Secure.prefs(c).getAll().keySet()) if (key.startsWith("risk_")) edit.remove(key);
        return edit;
    }
    static void clear(Context c) { synchronized (EPOCH) { if (!revokeEditor(c).commit()) throw new IllegalStateException(Taal.T("Instellingen bewaren lukt niet")); } }
    static boolean any(Context c) { for (String id : ToolRegister.ADAPTERS.keySet()) if (enabled(c, id)) return true; return false; }
    static JSONObject state(Context c) throws Exception {
        JSONObject out = new JSONObject();
        for (String id : ToolRegister.ADAPTERS.keySet()) out.put(id, enabled(c, id));
        return out;
    }
}
