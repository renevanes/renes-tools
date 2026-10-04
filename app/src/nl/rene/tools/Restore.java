package nl.rene.tools;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Terugzetten uit een backup: contacten uit een vCard-bestand (alleen contacten die er nog niet
 * zijn) en notities uit notities.json (alleen notities die er nog niet zijn). Eerst een overzicht,
 * dan pas na bevestiging toevoegen. Er wordt nooit iets overschreven of verwijderd.
 */
final class Restore {

    private Restore() { }

    static String pendingKind = null;
    static List<ContactsDiff.Rec> pendingContacts;
    static JSONArray pendingNotes;

    static String read(Context c, Uri u) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("Bestand niet te openen");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 30_000_000) throw new Exception("Bestand is te groot"); }
            byte[] d = b.toByteArray();
            int off = d.length >= 3 && (d[0] & 0xff) == 0xEF && (d[1] & 0xff) == 0xBB && (d[2] & 0xff) == 0xBF ? 3 : 0;
            return new String(d, off, d.length - off, StandardCharsets.UTF_8);
        }
    }

    /** Overzicht van wat er toegevoegd zou worden. */
    static JSONObject preview(Context c, String kind, Uri u) throws Exception {
        return previewText(c, kind, read(c, u));
    }

    /** Zelfde, met de tekst al gelezen (bijv. uit een versleutelde backup). */
    static synchronized JSONObject previewText(Context c, String kind, String text) throws Exception {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        pendingKind = null;
        if ("contacts".equals(kind)) {
            List<ContactsDiff.Rec> all = VCard.parse(text);
            if (all.isEmpty()) throw new Exception("Geen contacten gevonden in dit bestand");
            Set<String> names = new HashSet<>(), phones = new HashSet<>();
            for (ContactsDiff.Rec r : Contacts.read(c)) {
                names.add(r.name.trim().toLowerCase(Locale.ROOT));
                for (String p : Contacts.lines(r.get("Telefoon"))) { String k = Calls.numKey(Contacts.splitLabel(p)[0]); if (k != null) phones.add(k); }
            }
            List<ContactsDiff.Rec> fresh = new ArrayList<>();
            JSONArray sample = new JSONArray();
            for (ContactsDiff.Rec r : all) {
                boolean dup = !r.name.isEmpty() && names.contains(r.name.trim().toLowerCase(Locale.ROOT));
                if (!dup && r.name.isEmpty()) for (String p : Contacts.lines(r.get("Telefoon"))) {
                    String k = Calls.numKey(Contacts.splitLabel(p)[0]);
                    if (k != null && phones.contains(k)) dup = true;
                }
                if (!dup) {
                    fresh.add(r);
                    if (sample.length() < 8) sample.put(r.name);
                    // Dubbelen binnen het bestand zelf maar één keer toevoegen
                    if (!r.name.isEmpty()) names.add(r.name.trim().toLowerCase(Locale.ROOT));
                    for (String p : Contacts.lines(r.get("Telefoon"))) { String k = Calls.numKey(Contacts.splitLabel(p)[0]); if (k != null) phones.add(k); }
                }
            }
            pendingContacts = fresh;
            pendingKind = kind;
            return new JSONObject().put("kind", kind).put("total", all.size()).put("fresh", fresh.size())
                    .put("dup", all.size() - fresh.size()).put("sample", sample);
        }
        if ("notes".equals(kind)) {
            JSONArray in;
            try { in = new JSONArray(text.trim()); } catch (Exception e) { throw new Exception("Dit is geen notities-backup (notities.json)"); }
            JSONArray cur = new JSONArray(Notes.load(c).isEmpty() ? "[]" : Notes.load(c));
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < cur.length(); i++) ids.add(cur.getJSONObject(i).optString("id"));
            JSONArray fresh = new JSONArray(), sample = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject n = in.optJSONObject(i);
                if (n == null || !n.has("id") || ids.contains(n.optString("id"))) continue;
                ids.add(n.optString("id"));
                n = cleanNote(n);
                if (n == null) continue;
                fresh.put(n);
                if (sample.length() < 8) sample.put(n.optString("title", "Zonder titel"));
            }
            pendingNotes = fresh;
            pendingKind = kind;
            return new JSONObject().put("kind", kind).put("total", in.length()).put("fresh", fresh.length())
                    .put("dup", in.length() - fresh.length()).put("sample", sample);
        }
        throw new Exception("Onbekend soort backup");
    }

    /** Alleen bekende velden met het juiste type overnemen (een vreemd bestand mag de notities niet breken). */
    static JSONObject cleanNote(JSONObject n) {
        try {
            JSONObject o = new JSONObject().put("id", n.optString("id")).put("title", n.optString("title", ""))
                    .put("text", n.optString("text", "")).put("updated", n.optLong("updated", System.currentTimeMillis()));
            JSONArray items = new JSONArray(), in = n.optJSONArray("items");
            if (in != null) for (int i = 0; i < in.length(); i++) {
                JSONObject it = in.optJSONObject(i);
                if (it == null || it.optString("text").isEmpty()) continue;
                items.put(new JSONObject().put("id", it.optString("id", "r" + i + "x" + Long.toString(System.nanoTime(), 36)))
                        .put("text", it.optString("text")).put("done", it.optBoolean("done")));
            }
            return o.put("items", items);
        } catch (Exception e) { return null; }
    }

    /** Voegt het voorbeeld toe. Geeft het aantal toegevoegde onderdelen. */
    static synchronized int apply(Context c) throws Exception {
        String kind = pendingKind;
        pendingKind = null;
        if ("contacts".equals(kind) && pendingContacts != null) {
            int n = 0;
            for (ContactsDiff.Rec r : pendingContacts) { Contacts.insert(c, r); n++; }
            pendingContacts = null;
            try { Contacts.snapshot(c); } catch (Exception ignored) { }
            return n;
        }
        if ("notes".equals(kind) && pendingNotes != null) {
            String cur = Notes.load(c);
            if (cur.isEmpty()) throw new Exception("Notities zijn niet te lezen; er is niets veranderd");
            JSONArray all = new JSONArray(cur);
            for (int i = 0; i < pendingNotes.length(); i++) all.put(pendingNotes.get(i));
            String err = Notes.save(c, all.toString());
            if (!err.isEmpty()) throw new Exception(err);
            int n = pendingNotes.length();
            pendingNotes = null;
            return n;
        }
        throw new Exception("Niets om terug te zetten");
    }
}
