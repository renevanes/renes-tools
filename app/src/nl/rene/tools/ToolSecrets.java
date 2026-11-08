package nl.rene.tools;

import android.content.Context;

/** Standaard voor nieuwe toolwachtwoorden/tokens: lokaal versleuteld en automatisch overdraagbaar in de versleutelde backup.
 * Voor gegevens met een eigen hardware-ontgrendeling is daarnaast een eigen backup-adapter vereist. */
final class ToolSecrets {
    private ToolSecrets() { }
    static String name(String tool) {
        if (!ToolRegister.SOLO.contains(tool) && !"skin".equals(tool)) throw new IllegalArgumentException(Taal.T("Onbekende tool"));
        return tool + "_geheim";
    }
    static void set(Context c, String tool, String key, String value) throws Exception {
        android.content.SharedPreferences.Editor edit = c.getSharedPreferences(name(tool), Context.MODE_PRIVATE).edit();
        if (key == null || key.isEmpty() || key.length() > 200) throw new IllegalArgumentException(Taal.T("Ongeldige sleutel"));
        if (value == null) edit.remove(key); else edit.putString(key, AppData.seal(Secure.deviceKey(), value));
        if (!edit.commit()) throw new java.io.IOException("De geheime instellingen bewaren lukt niet");
    }
    static String get(Context c, String tool, String key) throws Exception {
        String value = c.getSharedPreferences(name(tool), Context.MODE_PRIVATE).getString(key, null);
        return value == null ? null : AppData.open(Secure.deviceKey(), value);
    }
}
