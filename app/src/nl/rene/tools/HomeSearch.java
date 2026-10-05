package nl.rene.tools;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Zoeken vanaf het startscherm, naast de apps: notities, contacten en (in de pagina) instellingen en tools.
 * Met app-slot geen inhoud van notities op het startscherm: alleen hoeveel er gevonden zijn (openen = vingerafdruk).
 */
final class HomeSearch {

    private HomeSearch() { }

    static String json(Context c, String query) {
        JSONObject o = new JSONObject();
        String q = HistoryText.fold(query == null ? "" : query.trim());
        if (q.length() < 2) return "{}";
        try {
            // Notities. Met app-slot: niets zeggen over de inhoud (ook niet óf iets gevonden is), alleen een vaste knop.
            if (Lock.active(c)) o.put("notesLocked", true);
            else {
            JSONArray notes = new JSONArray();
            int found = 0;
            String raw = Notes.load(c);
            JSONArray all = new JSONArray(raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < all.length(); i++) {
                JSONObject n = all.optJSONObject(i);
                if (n == null) continue;
                StringBuilder text = new StringBuilder(n.optString("title")).append('\n').append(n.optString("text"));
                JSONArray items = n.optJSONArray("items");
                if (items != null) for (int j = 0; j < items.length(); j++) { JSONObject it = items.optJSONObject(j); if (it != null) text.append('\n').append(it.optString("text")); }
                String f = HistoryText.fold(text.toString());
                int at = f.indexOf(q);
                if (at < 0) continue;
                found++;
                if (notes.length() < 5) {
                    String t = n.optString("title").trim();
                    String full = text.toString();
                    int s = Math.max(0, Math.min(at, full.length()) - 20);
                    String snip = full.substring(s, Math.min(full.length(), s + 70)).replace('\n', ' ').trim();
                    notes.put(new JSONObject().put("id", n.optString("id")).put("title", t.isEmpty() ? "Notitie" : t).put("snip", snip));
                }
            }
            o.put("notes", notes);
            }

            // Contacten (alleen met toestemming)
            if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                JSONArray cs = new JSONArray();
                Uri u = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query.trim()));
                try (Cursor cur = c.getContentResolver().query(u, new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY}, null, null, null)) {
                    while (cur != null && cur.moveToNext() && cs.length() < 6) {
                        Uri lu = ContactsContract.Contacts.getLookupUri(cur.getLong(0), cur.getString(1));
                        if (lu == null) continue;
                        cs.put(new JSONObject().put("name", cur.getString(2)).put("uri", lu.toString()));
                    }
                }
                o.put("contacts", cs);
            }
        } catch (Exception ignored) { }
        return o.toString();
    }

    static void openContact(Activity a, String uri) {
        if (uri == null || !uri.startsWith("content://com.android.contacts/")) return;
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    /** Vaste lijst met Android-instellingen (zelfde sleutels als in start.html). */
    static void openSetting(Activity a, String key) {
        String act;
        switch (key == null ? "" : key) {
            case "wifi": act = Settings.ACTION_WIFI_SETTINGS; break;
            case "bt": act = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display": act = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": act = Settings.ACTION_SOUND_SETTINGS; break;
            case "battery": act = Intent.ACTION_POWER_USAGE_SUMMARY; break;
            case "apps": act = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "notif": act = android.os.Build.VERSION.SDK_INT >= 26 ? "android.settings.ALL_APPS_NOTIFICATION_SETTINGS" : Settings.ACTION_SETTINGS; break;
            case "location": act = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
            case "security": act = Settings.ACTION_SECURITY_SETTINGS; break;
            case "storage": act = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
            case "date": act = Settings.ACTION_DATE_SETTINGS; break;
            case "a11y": act = Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
            case "data": act = Settings.ACTION_DATA_ROAMING_SETTINGS; break;
            case "hotspot": act = Settings.ACTION_WIRELESS_SETTINGS; break;
            case "home": act = Settings.ACTION_HOME_SETTINGS; break;
            default: act = Settings.ACTION_SETTINGS;
        }
        try { a.startActivity(new Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { try { a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
    }
}
