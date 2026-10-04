package nl.rene.tools;

import android.content.ContentProviderOperation;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Contacten: lezen, versies bijhouden (elke wijziging wordt een nieuwe versie met datum en
 * per contact welke velden veranderd zijn), exporteren als vCard en verwijderde contacten
 * terugzetten. Versies staan in de app-map onder contacts/: index.json met per versie de
 * wijzigingen, en van de laatste versies ook de volledige lijst (v&lt;N&gt;.json.gz).
 */
final class Contacts {

    private Contacts() { }

    static final String DIR = "Contacten backup";
    static final int KEEP_FULL = 60;

    static File home(Context c) { File d = new File(c.getFilesDir(), "contacts"); d.mkdirs(); return d; }

    // ---------- lezen ----------

    static String phoneLabel(int t) {
        switch (t) {
            case Phone.TYPE_MOBILE: return "mobiel";
            case Phone.TYPE_HOME: return "thuis";
            case Phone.TYPE_WORK: case Phone.TYPE_WORK_MOBILE: return "werk";
            case Phone.TYPE_FAX_HOME: case Phone.TYPE_FAX_WORK: return "fax";
            default: return "";
        }
    }

    static String withLabel(String v, String label) { return label.isEmpty() ? v : v + " (" + label + ")"; }

    /** Splitst "waarde (label)" weer in waarde en label. */
    static String[] splitLabel(String s) {
        int i = s.lastIndexOf(" (");
        if (i > 0 && s.endsWith(")")) return new String[]{s.substring(0, i), s.substring(i + 2, s.length() - 1)};
        return new String[]{s, ""};
    }

    /** Alle contacten. Gooit een fout als Android de contacten (tijdelijk) niet levert, in plaats van een lege lijst. */
    static List<ContactsDiff.Rec> read(Context c) throws Exception { return read(c, -1); }

    /** Eén contact (contactId &gt;= 0) of alle contacten (-1). */
    static List<ContactsDiff.Rec> read(Context c, long contactId) throws Exception {
        Map<Long, ContactsDiff.Rec> byId = new HashMap<>();
        List<ContactsDiff.Rec> out = new ArrayList<>();
        String[] cc = {ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
                ContactsContract.Contacts.STARRED};
        String sel = contactId >= 0 ? ContactsContract.Contacts._ID + "=?" : null;
        String[] args = contactId >= 0 ? new String[]{String.valueOf(contactId)} : null;
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, cc, sel, args, null)) {
            if (cur == null) throw new Exception("Contacten zijn nu niet beschikbaar");
            while (cur.moveToNext()) {
                ContactsDiff.Rec r = new ContactsDiff.Rec();
                r.id = cur.getLong(0);
                r.key = cur.getString(1) == null ? "id" + r.id : cur.getString(1);
                r.name = cur.getString(2) == null ? "" : cur.getString(2);
                r.updated = cur.isNull(3) ? 0 : cur.getLong(3);
                r.put("Naam", r.name);
                if (cur.getInt(4) != 0) r.put("Favoriet", "ja");
                byId.put(r.id, r);
                out.add(r);
            }
        }
        if (out.isEmpty()) return out;
        String[] dc = {ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA4};
        String dsel = contactId >= 0 ? ContactsContract.Data.CONTACT_ID + "=?" : null;
        // Vaste volgorde (primaire waarde eerst), zodat enkelvoudige velden niet wisselen tussen samengevoegde contacten.
        String order = ContactsContract.Data.CONTACT_ID + ", " + ContactsContract.Data.IS_SUPER_PRIMARY + " DESC, "
                + ContactsContract.Data.IS_PRIMARY + " DESC, " + ContactsContract.Data._ID;
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, dc, dsel, args, order)) {
            if (cur == null) throw new Exception("Contactgegevens zijn nu niet beschikbaar");
            while (cur.moveToNext()) {
                ContactsDiff.Rec r = byId.get(cur.getLong(0));
                String mt = cur.getString(1), d1 = cur.getString(2);
                if (r == null || mt == null) continue;
                if (d1 == null) d1 = "";
                d1 = d1.trim();
                int t = cur.isNull(3) ? 0 : (int) cur.getLong(3);
                if (mt.equals(Organization.CONTENT_ITEM_TYPE)) {
                    String title = cur.getString(4);
                    if (r.get("Bedrijf").isEmpty() && r.get("Functie").isEmpty()) { r.put("Bedrijf", d1); r.put("Functie", title); }
                    continue;
                }
                if (d1.isEmpty()) continue;
                switch (mt) {
                    case Phone.CONTENT_ITEM_TYPE: r.add("Telefoon", withLabel(d1, phoneLabel(t))); break;
                    case Email.CONTENT_ITEM_TYPE: r.add("E-mail", d1); break;
                    case StructuredPostal.CONTENT_ITEM_TYPE: r.add("Adres", d1.replace("\n", ", ")); break;
                    case Website.CONTENT_ITEM_TYPE: r.add("Website", d1); break;
                    case Nickname.CONTENT_ITEM_TYPE: r.add("Bijnaam", d1); break;
                    case Note.CONTENT_ITEM_TYPE: if (r.get("Notitie").isEmpty()) r.put("Notitie", d1); break;
                    case Event.CONTENT_ITEM_TYPE: if (t == Event.TYPE_BIRTHDAY && r.get("Verjaardag").isEmpty()) r.put("Verjaardag", d1); break;
                    default: break;
                }
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Snelle vingerafdruk: aantal contacten + laatste wijzigingstijd. Verandert bij elke toevoeging, wijziging of verwijdering. */
    static String fingerprint(Context c) throws Exception {
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP}, null, null, null)) {
            if (cur == null) throw new Exception("Contacten zijn nu niet beschikbaar");
            long max = 0, sum = 0;
            while (cur.moveToNext()) { long u = cur.isNull(1) ? 0 : cur.getLong(1); if (u > max) max = u; sum += cur.getLong(0); }
            return cur.getCount() + ":" + max + ":" + sum;
        }
    }

    private static List<ContactsDiff.Rec> cache;
    private static String cacheFp;

    /** Alle contacten, uit het geheugen zolang er niets veranderd is (zoeken per toetsaanslag blijft zo snel). */
    static synchronized List<ContactsDiff.Rec> readCached(Context c) throws Exception {
        String fp = fingerprint(c);
        if (cache == null || !fp.equals(cacheFp)) { cache = read(c); cacheFp = fp; }
        return cache;
    }

    /** Zoekt een contact op, ook als Android de lookup key na bewerken veranderd heeft. */
    static ContactsDiff.Rec find(Context c, String key, long id) throws Exception {
        Uri u = ContactsContract.Contacts.lookupContact(c.getContentResolver(), ContactsContract.Contacts.getLookupUri(id, key));
        if (u == null) return null;
        List<ContactsDiff.Rec> l = read(c, android.content.ContentUris.parseId(u));
        return l.isEmpty() ? null : l.get(0);
    }

    // ---------- opslag ----------

    static JSONObject recJson(ContactsDiff.Rec r) throws Exception {
        JSONObject o = new JSONObject().put("k", r.key).put("n", r.name).put("u", r.updated);
        JSONObject f = new JSONObject();
        for (Map.Entry<String, String> e : r.f.entrySet()) f.put(e.getKey(), e.getValue());
        return o.put("f", f);
    }

    static ContactsDiff.Rec recFrom(JSONObject o) {
        ContactsDiff.Rec r = new ContactsDiff.Rec(o.optString("k"), o.optString("n"));
        r.updated = o.optLong("u");
        JSONObject f = o.optJSONObject("f");
        if (f != null) for (String k : ContactsDiff.FIELDS) if (f.has(k)) r.f.put(k, f.optString(k));
        return r;
    }

    static String readText(File f, boolean gz) throws Exception {
        try (InputStream in = gz ? new GZIPInputStream(new FileInputStream(f)) : new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Schrijft eerst naar een tijdelijk bestand en hernoemt dan, zodat een onderbreking niets kapotmaakt. */
    static void writeText(File f, String s, boolean gz) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            if (gz) {
                GZIPOutputStream g = new GZIPOutputStream(fo);
                try { g.write(s.getBytes(StandardCharsets.UTF_8)); g.finish(); fo.getFD().sync(); }
                finally { g.close(); } // sluit ook fo en geeft de Deflater vrij
            } else {
                fo.write(s.getBytes(StandardCharsets.UTF_8));
                fo.getFD().sync();
            }
        } finally { fo.close(); }
        if (!tmp.renameTo(f)) throw new Exception("Opslaan lukt niet: " + f.getName());
    }

    static synchronized JSONArray index(Context c) {
        File f = new File(home(c), "index.json");
        try { return f.exists() ? new JSONArray(readText(f, false)) : new JSONArray(); }
        catch (Exception e) { return null; } // kapot: niet overschrijven
    }

    static List<ContactsDiff.Rec> loadVersion(Context c, int v) throws Exception {
        File f = new File(home(c), "v" + v + ".json.gz");
        if (!f.exists()) return null;
        JSONArray a = new JSONArray(readText(f, true));
        List<ContactsDiff.Rec> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(recFrom(a.getJSONObject(i)));
        return out;
    }

    // ---------- versies ----------

    static JSONArray changeJson(ContactsDiff.Entry e) throws Exception {
        JSONArray d = new JSONArray();
        for (ContactsDiff.Change ch : e.changes) d.put(new JSONArray().put(ch.field).put(ch.before).put(ch.after));
        return d;
    }

    /**
     * Vergelijkt de contacten met de laatste versie en legt een nieuwe versie vast als er iets
     * veranderd is. Geeft het nieuwe versienummer, of 0 als er niets veranderd is.
     */
    static synchronized int snapshot(Context c) throws Exception {
        JSONArray idx = index(c);
        if (idx == null) throw new Exception("Versiegeschiedenis is niet te lezen");
        android.content.SharedPreferences sp = c.getSharedPreferences("contacts", Context.MODE_PRIVATE);
        long t = System.currentTimeMillis();
        int last = idx.length() == 0 ? 0 : idx.getJSONObject(idx.length() - 1).getInt("v");
        // Niets veranderd sinds de vorige controle: niet alles opnieuw lezen en vergelijken.
        String fp = fingerprint(c);
        if (last > 0 && fp.equals(sp.getString("fp", ""))) { sp.edit().putLong("lastCheck", t).apply(); return 0; }
        List<ContactsDiff.Rec> now = readCached(c);
        JSONObject entry = new JSONObject().put("t", t).put("count", now.size());
        if (last == 0) {
            entry.put("v", 1).put("first", true).put("added", now.size()).put("removed", 0).put("changed", 0).put("changes", new JSONArray());
        } else {
            List<ContactsDiff.Rec> prev = loadVersion(c, last);
            if (prev == null) throw new Exception("Laatste versie ontbreekt");
            List<ContactsDiff.Entry> diff = ContactsDiff.diff(prev, now);
            if (diff.isEmpty()) {
                sp.edit().putLong("lastCheck", t).putString("fp", fp).apply();
                return 0;
            }
            int a = 0, r = 0, m = 0;
            for (ContactsDiff.Entry e : diff) if (e.kind == '-') r++;
            // Beveiliging: verdwijnt (bijna) alles ineens, dan levert Android de contacten waarschijnlijk
            // tijdelijk niet (na een update, privacymodus ...). Pas vastleggen als hetzelfde beeld
            // minstens 30 minuten later nog steeds zo is.
            boolean suspicious = now.isEmpty() || (r > 5 && r * 10 > prev.size() * 3);
            if (suspicious) {
                long since = sp.getLong("suspectSince", 0);
                if (!fp.equals(sp.getString("suspectFp", "")) || since == 0) {
                    sp.edit().putString("suspectFp", fp).putLong("suspectSince", t).apply();
                    throw new Exception(r + " contacten lijken verdwenen; dit wordt pas vastgelegd als het over een half uur nog zo is");
                }
                if (t - since < 30 * 60 * 1000L)
                    throw new Exception(r + " contacten lijken verdwenen; dit wordt pas vastgelegd als het over een half uur nog zo is");
            }
            sp.edit().remove("suspectFp").remove("suspectSince").apply();
            r = 0;
            JSONArray ch = new JSONArray();
            for (ContactsDiff.Entry e : diff) {
                if (e.kind == '+') a++; else if (e.kind == '-') r++; else m++;
                JSONObject o = new JSONObject().put("kind", String.valueOf(e.kind)).put("k", e.rec.key).put("n", e.rec.name).put("d", changeJson(e));
                if (e.kind == '-') o.put("rec", recJson(e.rec));
                ch.put(o);
            }
            entry.put("v", last + 1).put("added", a).put("removed", r).put("changed", m).put("changes", ch);
        }
        int v = entry.getInt("v");
        JSONArray full = new JSONArray();
        for (ContactsDiff.Rec r : now) full.put(recJson(r));
        writeText(new File(home(c), "v" + v + ".json.gz"), full.toString(), true);
        idx.put(entry);
        writeText(new File(home(c), "index.json"), idx.toString(), false);
        File old = new File(home(c), "v" + (v - KEEP_FULL) + ".json.gz");
        if (old.exists()) old.delete();
        sp.edit().putLong("lastCheck", t).putString("fp", fp).apply();
        return v;
    }

    // ---------- voor de interface ----------

    static String listJson(Context c, String q) throws Exception {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String digits = needle.replaceAll("[^0-9]", "");
        List<ContactsDiff.Rec> all = readCached(c);
        JSONArray arr = new JSONArray();
        for (ContactsDiff.Rec r : all) {
            if (!needle.isEmpty()) {
                boolean hit = r.name.toLowerCase(Locale.ROOT).contains(needle)
                        || r.get("E-mail").toLowerCase(Locale.ROOT).contains(needle)
                        || r.get("Bedrijf").toLowerCase(Locale.ROOT).contains(needle)
                        || (digits.length() >= 3 && r.get("Telefoon").replaceAll("[^0-9\n]", "").contains(digits));
                if (!hit) continue;
            }
            String ph = r.get("Telefoon");
            arr.put(new JSONObject().put("k", r.key).put("id", r.id).put("n", r.name).put("u", r.updated)
                    .put("sub", ph.isEmpty() ? r.get("E-mail").split("\n")[0] : ph.split("\n")[0])
                    .put("fav", !r.get("Favoriet").isEmpty()));
        }
        JSONArray idx = index(c);
        JSONObject o = new JSONObject().put("contacts", arr).put("total", all.size());
        if (idx != null && idx.length() > 0) {
            JSONObject l = idx.getJSONObject(idx.length() - 1);
            o.put("version", l.getInt("v")).put("versionT", l.getLong("t"));
        }
        return o.toString();
    }

    /** Eén contact met alle velden en de wijzigingsgeschiedenis uit de versies. */
    static String detailJson(Context c, String key, long id) throws Exception {
        ContactsDiff.Rec r = find(c, key, id);
        if (r == null) return "{\"error\":\"Contact niet gevonden\"}";
        JSONObject o = recJson(r).put("id", r.id);
        JSONArray hist = new JSONArray();
        JSONArray idx = index(c);
        if (idx != null) for (int i = idx.length() - 1; i >= 0; i--) {
            JSONObject e = idx.getJSONObject(i);
            JSONArray ch = e.optJSONArray("changes");
            if (ch == null) continue;
            for (int j = 0; j < ch.length(); j++) {
                JSONObject x = ch.getJSONObject(j);
                String xk = x.optString("k");
                if (xk.equals(key) || xk.equals(r.key) || (!r.name.isEmpty() && x.optString("n").equalsIgnoreCase(r.name))) {
                    hist.put(new JSONObject().put("v", e.getInt("v")).put("t", e.getLong("t"))
                            .put("kind", x.optString("kind")).put("d", x.optJSONArray("d")));
                }
            }
        }
        return o.put("history", hist).toString();
    }

    static String versionsJson(Context c) throws Exception {
        JSONArray idx = index(c);
        if (idx == null) return "{\"error\":\"Versiegeschiedenis is niet te lezen\"}";
        JSONArray out = new JSONArray();
        for (int i = idx.length() - 1; i >= 0; i--) {
            JSONObject e = idx.getJSONObject(i);
            int v = e.getInt("v");
            out.put(new JSONObject().put("v", v).put("t", e.getLong("t")).put("count", e.optInt("count"))
                    .put("added", e.optInt("added")).put("removed", e.optInt("removed")).put("changed", e.optInt("changed"))
                    .put("first", e.optBoolean("first")).put("full", new File(home(c), "v" + v + ".json.gz").exists()));
        }
        return new JSONObject().put("versions", out).toString();
    }

    static String versionJson(Context c, int v) throws Exception {
        JSONArray idx = index(c);
        if (idx != null) for (int i = 0; i < idx.length(); i++) {
            JSONObject e = idx.getJSONObject(i);
            if (e.getInt("v") != v) continue;
            JSONObject o = new JSONObject(e.toString());
            JSONArray ch = o.optJSONArray("changes");
            if (ch != null) for (int j = 0; j < ch.length(); j++) ch.getJSONObject(j).remove("rec");
            o.put("full", new File(home(c), "v" + v + ".json.gz").exists());
            return o.toString();
        }
        return "{\"error\":\"Versie niet gevonden\"}";
    }

    // ---------- terugzetten ----------

    static int phoneType(String label) {
        switch (label) {
            case "mobiel": return Phone.TYPE_MOBILE;
            case "thuis": return Phone.TYPE_HOME;
            case "werk": return Phone.TYPE_WORK;
            case "fax": return Phone.TYPE_FAX_HOME;
            default: return Phone.TYPE_OTHER;
        }
    }

    static ContentProviderOperation.Builder data(String mime) {
        return ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, mime);
    }

    /** Maakt een nieuw contact (in de telefoon-opslag) met de velden uit een oude versie. */
    static void insert(Context c, ContactsDiff.Rec r) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                // Android 14+: het lokale "telefoon"-account van het toestel; daarvoor null (= op de telefoon).
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE,
                        android.os.Build.VERSION.SDK_INT >= 34 ? ContactsContract.RawContacts.getLocalAccountType(c) : null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME,
                        android.os.Build.VERSION.SDK_INT >= 34 ? ContactsContract.RawContacts.getLocalAccountName(c) : null)
                .withValue(ContactsContract.RawContacts.STARRED, r.get("Favoriet").isEmpty() ? 0 : 1).build());
        String name = r.get("Naam").isEmpty() ? r.name : r.get("Naam");
        ops.add(data(StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build());
        for (String x : lines(r.get("Telefoon"))) {
            String[] p = splitLabel(x);
            ops.add(data(Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, p[0]).withValue(Phone.TYPE, phoneType(p[1])).build());
        }
        for (String x : lines(r.get("E-mail")))
            ops.add(data(Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, x).withValue(Email.TYPE, Email.TYPE_OTHER).build());
        if (!r.get("Bedrijf").isEmpty() || !r.get("Functie").isEmpty())
            ops.add(data(Organization.CONTENT_ITEM_TYPE).withValue(Organization.COMPANY, r.get("Bedrijf"))
                    .withValue(Organization.TITLE, r.get("Functie")).build());
        for (String x : lines(r.get("Adres")))
            ops.add(data(StructuredPostal.CONTENT_ITEM_TYPE).withValue(StructuredPostal.FORMATTED_ADDRESS, x)
                    .withValue(StructuredPostal.TYPE, StructuredPostal.TYPE_HOME).build());
        for (String x : lines(r.get("Website"))) ops.add(data(Website.CONTENT_ITEM_TYPE).withValue(Website.URL, x).build());
        for (String x : lines(r.get("Bijnaam"))) ops.add(data(Nickname.CONTENT_ITEM_TYPE).withValue(Nickname.NAME, x).build());
        if (!r.get("Notitie").isEmpty()) ops.add(data(Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, r.get("Notitie")).build());
        if (!r.get("Verjaardag").isEmpty())
            ops.add(data(Event.CONTENT_ITEM_TYPE).withValue(Event.START_DATE, r.get("Verjaardag")).withValue(Event.TYPE, Event.TYPE_BIRTHDAY).build());
        c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
    }

    static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split("\n")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    /** Zet een contact terug dat in versie v verwijderd is. */
    static synchronized String restoreRemoved(Context c, int v, String key) throws Exception {
        JSONArray idx = index(c);
        if (idx != null) for (int i = 0; i < idx.length(); i++) {
            JSONObject e = idx.getJSONObject(i);
            if (e.getInt("v") != v) continue;
            JSONArray ch = e.optJSONArray("changes");
            if (ch != null) for (int j = 0; j < ch.length(); j++) {
                JSONObject x = ch.getJSONObject(j);
                if (x.optString("k").equals(key) && "-".equals(x.optString("kind")) && x.has("rec")) {
                    if (x.optBoolean("restored")) throw new Exception("Dit contact is al teruggezet");
                    ContactsDiff.Rec r = recFrom(x.getJSONObject("rec"));
                    insert(c, r);
                    x.put("restored", true);
                    writeText(new File(home(c), "index.json"), idx.toString(), false);
                    return r.name;
                }
            }
        }
        throw new Exception("Contact niet gevonden in deze versie");
    }

    // ---------- export ----------

    static String vesc(String s) {
        return s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\r", "").replace("\n", "\\n");
    }

    /** Waarde zonder komma-escaping (voor URL en EMAIL, RFC 2426). */
    static String vraw(String s) { return s.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n"); }

    /** Schrijft één vCard-regel, gevouwen op 75 tekens (RFC 2426), zonder een surrogaatpaar te splitsen. */
    static void line(Writer w, String l) throws Exception {
        int i = 0, max = 75;
        while (l.length() - i > max) {
            int end = i + max;
            if (Character.isHighSurrogate(l.charAt(end - 1))) end--;
            w.write(l, i, end - i);
            w.write("\r\n ");
            i = end;
            max = 74;
        }
        w.write(l.substring(i));
        w.write("\r\n");
    }

    static void writeVcf(Writer w, List<ContactsDiff.Rec> list) throws Exception {
        SimpleDateFormat rev = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        rev.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        for (ContactsDiff.Rec r : list) {
            line(w, "BEGIN:VCARD");
            line(w, "VERSION:3.0");
            String name = r.get("Naam").isEmpty() ? r.name : r.get("Naam");
            line(w, "FN:" + vesc(name));
            // Volledige naam als voornaam: "de Vries" e.d. laat zich niet betrouwbaar splitsen; importeurs gebruiken FN.
            line(w, "N:;" + vesc(name) + ";;;");
            for (String x : lines(r.get("Bijnaam"))) line(w, "NICKNAME:" + vesc(x));
            for (String x : lines(r.get("Telefoon"))) {
                String[] p = splitLabel(x);
                String t = p[1].equals("mobiel") ? "CELL" : p[1].equals("thuis") ? "HOME" : p[1].equals("werk") ? "WORK" : p[1].equals("fax") ? "FAX" : "VOICE";
                line(w, "TEL;TYPE=" + t + ":" + vesc(p[0]));
            }
            for (String x : lines(r.get("E-mail"))) line(w, "EMAIL;TYPE=INTERNET:" + vraw(x));
            if (!r.get("Bedrijf").isEmpty()) line(w, "ORG:" + vesc(r.get("Bedrijf")));
            if (!r.get("Functie").isEmpty()) line(w, "TITLE:" + vesc(r.get("Functie")));
            for (String x : lines(r.get("Adres"))) line(w, "ADR;TYPE=HOME:;;" + vesc(x) + ";;;;");
            for (String x : lines(r.get("Website"))) line(w, "URL:" + vraw(x));
            if (!r.get("Verjaardag").isEmpty()) line(w, "BDAY:" + vesc(r.get("Verjaardag")));
            if (!r.get("Notitie").isEmpty()) line(w, "NOTE:" + vesc(r.get("Notitie")));
            if (r.updated > 0) line(w, "REV:" + rev.format(new Date(r.updated)));
            line(w, "END:VCARD");
        }
    }

    /**
     * Exporteert naar de backup-map: contacten-JJJJ-MM-DD.vcf (huidige lijst, met REV =
     * wijzigingsdatum), contacten-versie-N.vcf als v &gt; 0, en wijzigingslog.txt met alle versies.
     */
    static int export(Context c, int v) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map (bij WhatsApp backup)");
        // Versie bijwerken; als dat nu niet kan (bijv. de beveiliging tegen massaal verdwijnen), toch de backup maken.
        try { snapshot(c); } catch (Exception e) { App.log(c, "CONTACTS", "versie niet vastgelegd: " + e.getMessage()); }
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(DIR, true);
        List<ContactsDiff.Rec> list;
        String file;
        if (v > 0) {
            list = loadVersion(c, v);
            if (list == null) throw new Exception("Van deze versie is de volledige lijst niet meer bewaard");
            file = "contacten-versie-" + v + ".vcf";
        } else {
            list = read(c);
            file = "contacten-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".vcf";
        }
        try (Writer w = Sms.open(dest, dir, file, "text/x-vcard")) { writeVcf(w, list); }
        JSONArray idx = index(c);
        SimpleDateFormat df = new SimpleDateFormat("EEE d MMM yyyy HH:mm", new Locale("nl", "NL"));
        try (Writer w = Sms.open(dest, dir, "wijzigingslog.txt", "text/plain")) {
            w.write("Contacten - wijzigingslog - Rene's Tools\r\n\r\n");
            if (idx != null) for (int i = idx.length() - 1; i >= 0; i--) {
                JSONObject e = idx.getJSONObject(i);
                w.write("Versie " + e.getInt("v") + " - " + df.format(new Date(e.getLong("t"))) + " - " + e.optInt("count") + " contacten");
                if (e.optBoolean("first")) { w.write(" (eerste versie)\r\n\r\n"); continue; }
                w.write(" (+" + e.optInt("added") + " nieuw, -" + e.optInt("removed") + " verwijderd, ~" + e.optInt("changed") + " gewijzigd)\r\n");
                JSONArray ch = e.optJSONArray("changes");
                if (ch != null) for (int j = 0; j < ch.length(); j++) {
                    JSONObject x = ch.getJSONObject(j);
                    String k = x.optString("kind");
                    w.write("  " + ("+".equals(k) ? "Nieuw" : "-".equals(k) ? "Verwijderd" : "Gewijzigd") + ": " + x.optString("n") + "\r\n");
                    if ("~".equals(k)) {
                        JSONArray d = x.optJSONArray("d");
                        if (d != null) for (int q = 0; q < d.length(); q++) {
                            JSONArray f = d.getJSONArray(q);
                            w.write("      " + f.getString(0) + ": " + show(f.getString(1)) + " -> " + show(f.getString(2)) + "\r\n");
                        }
                    }
                }
                w.write("\r\n");
            }
        }
        return list.size();
    }

    static String show(String s) { return s.isEmpty() ? "(leeg)" : s.replace("\n", " | "); }
}
