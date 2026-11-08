package nl.rene.tools;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

/** Expliciet toegestane, overdraagbare kopieën van toestelgebonden gegevens. Alleen in het versleutelde archief. */
final class PrivateBackup {
    private PrivateBackup() { }
    static final String DIR = AppData.SECRET_DIR + "/hoog-risico";
    static String target(String path) {
        if (!path.startsWith(DIR + "/")) return null;
        String id = path.substring(DIR.length() + 1);
        return ("bank".equals(id) || "masterdata".equals(id)) ? id : null;
    }
    static void export(Context c, Secure.Zip zip, AppData.Manifest m) throws Exception {
        for (String id : new String[]{"bank", "masterdata"}) {
            if (!BackupRisk.enabled(c, id)) continue;
            boolean has = "bank".equals(id) ? Bank.hasData(c) : MasterStore.exists(c);
            if (!has && !ProtectedRestore.has(c, id)) continue;
            if (ProtectedRestore.has(c, id)) { missing(m, id); continue; }
            String path = DIR + "/" + id;
            // Toegang vóór het ZIP-item openen controleren; een ontbrekende ontgrendeling beschadigt de rest niet.
            byte[] bank = null; SecretKey master = null;
            try {
                if ("bank".equals(id)) {
                    bank = Bank.backup(c); PrivateData.bank(new String(bank, StandardCharsets.UTF_8));
                }
                else master = MasterKeys.authenticated(c);
            } catch (Throwable e) { if (bank != null) Arrays.fill(bank, (byte)0); missing(m, id); continue; }
            try (AppData.DigestOut out = new AppData.DigestOut(zip.stream(DIR, id))) {
                if (!path.equals(zip.lastPath)) throw new IOException("Duplicate protected entry");
                if (bank != null) out.write(bank); else writeMaster(c, master, out);
                out.close(); m.entries.add(new AppData.Entry(path, out.n, out.sha()));
            } catch (Throwable e) {
                zip.bad.add(path); m.broken.add(path); missing(m, id);
            } finally { if (bank != null) Arrays.fill(bank, (byte)0); }
        }
    }
    static void missing(AppData.Manifest m, String id) {
        m.skipped.add(new String[]{"hoog:" + id, "ontgrendel dit onderdeel en maak opnieuw een backup"});
        AppBackup.lastProblem = Taal.T("Niet alle appgegevens zijn meegenomen. Ontgrendel de onderdelen met hoog risico en maak opnieuw een backup.");
    }
    static void line(Writer out, JSONObject o) throws Exception { out.write(o.toString()); out.write('\n'); }
    static void writeMaster(Context c, SecretKey key, OutputStream out) throws Exception {
        synchronized (MasterStore.LOCK) {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(MasterStore.file(c).getPath(), null, SQLiteDatabase.OPEN_READONLY, new MasterStore.PreserveCorruption());
            try {
                MasterStore.verify(db, key);
                Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
                line(writer, new JSONObject().put("format", PrivateData.FORMAT).put("version", PrivateData.VERSION));
                PrivateData.MasterValidator validator = new PrivateData.MasterValidator();
                long records = 0, events = 0;
                try (Cursor rows = db.rawQuery("SELECT id FROM records ORDER BY id", null)) {
                    while (rows.moveToNext()) {
                        AppBackup.stopCheck(); JSONObject row = new JSONObject().put("record", MasterStore.record(db, key, rows.getString(0)));
                        validator.accept(row); line(writer, row); records++;
                    }
                }
                String previous = "";
                try (Cursor rows = db.rawQuery("SELECT seq,body FROM events ORDER BY seq", null)) {
                    while (rows.moveToNext()) {
                        AppBackup.stopCheck(); long seq = rows.getLong(0); byte[] blob = rows.getBlob(1);
                        JSONObject event = MasterStore.decode(MasterCipher.decrypt(key, "event/" + seq, blob));
                        if (seq != ++events || event.getLong("seq") != seq || !previous.equals(event.getString("prev"))) throw MasterStore.integrity();
                        JSONObject row = new JSONObject().put("event", event); validator.accept(row); line(writer, row); previous = MasterCipher.hash(blob);
                    }
                }
                JSONObject end = new JSONObject().put("end", true).put("records", records).put("events", events);
                validator.accept(end); validator.finish(); line(writer, end); writer.flush();
                MasterStore.verify(db, key);
            } finally { db.close(); }
        }
    }
    static void restoreBank(Context c) throws Exception {
        if (!ProtectedRestore.has(c, "bank")) return;
        byte[] plain;
        try (InputStream in = ProtectedRestore.open(c, "bank")) { plain = AppBackup.small(in, new byte[64 * 1024], BankCrypt.MAX_PLAIN); }
        try { Bank.restoreBackup(c, PrivateData.bank(new String(plain, StandardCharsets.UTF_8))); ProtectedRestore.done(c, "bank"); }
        finally { Arrays.fill(plain, (byte)0); }
    }
    static void restoreMaster(Context c) throws Exception {
        if (!ProtectedRestore.has(c, "masterdata")) return;
        synchronized (MasterStore.LOCK) {
            File tmp = new File(MasterStore.file(c).getParentFile(), "backup-herstel.tmp");
            try (Reader reader = new BufferedReader(new InputStreamReader(ProtectedRestore.open(c, "masterdata"), StandardCharsets.UTF_8))) {
                // Er staat alleen een vergrendelde, door de gebruiker gekozen backup klaar; de oude dataset is bij toepassen al vervangen.
                String receipt = ProtectedRestore.receipt(c, "masterdata");
                if (MasterStore.exists(c)) {
                    SecretKey existing = MasterKeys.authenticated(c);
                    if (!masterReceipt(MasterStore.file(c), existing, receipt)) throw MasterStore.integrity();
                    MasterKeys.markReady(); ProtectedRestore.done(c, "masterdata"); return;
                }
                MasterKeys.remove(); SecretKey key = MasterKeys.authenticated(c);
                importMaster(reader, tmp, key, receipt);
                if (!tmp.renameTo(MasterStore.file(c))) throw new IOException(Taal.T("Stamgegevens opslaan lukt niet"));
                MasterKeys.markReady(); ProtectedRestore.done(c, "masterdata");
            } finally { tmp.delete(); }
        }
    }
    static boolean masterReceipt(File file, SecretKey key, String receipt) throws Exception {
        SQLiteDatabase current = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY, new MasterStore.PreserveCorruption());
        try { return receipt.equals(MasterStore.verify(current, key).optString("restore")); }
        finally { current.close(); }
    }
    /** Ook door de native rooktest gebruikt: dezelfde overdracht met een andere sleutel, zonder productie-testingang. */
    static void importMaster(Reader reader, File tmp, SecretKey key, String receipt) throws Exception {
        String first = PrivateData.line(reader); if (first == null) throw MasterStore.integrity(); PrivateData.header(new JSONObject(first));
        PrivateData.MasterValidator validator = new PrivateData.MasterValidator();
        tmp.delete(); SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(tmp, null);
        try {
            db.beginTransaction();
            db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,rev INTEGER NOT NULL,body BLOB NOT NULL)");
            db.execSQL("CREATE TABLE events(seq INTEGER PRIMARY KEY,body BLOB NOT NULL)");
            db.execSQL("CREATE TABLE control(id INTEGER PRIMARY KEY CHECK(id=1),body BLOB NOT NULL)");
            long records = 0, events = 0; String previous = "", row; boolean end = false;
            while ((row = PrivateData.line(reader)) != null) {
                if (end) throw MasterStore.integrity(); JSONObject o = new JSONObject(row); validator.accept(o);
                if (o.has("record")) {
                    if (events > 0 || ++records > 2000) throw MasterStore.integrity();
                    JSONObject r = PrivateData.record(o.getJSONObject("record")); String id = r.getString("id"); long revision = r.getLong("revision");
                    ContentValues values = new ContentValues(); values.put("id", id); values.put("rev", revision); values.put("body", MasterCipher.encrypt(key, "record/" + id + "/" + revision, MasterStore.bytes(r))); db.insertOrThrow("records", null, values);
                } else if (o.has("event")) {
                    JSONObject event = o.getJSONObject("event"); long seq = ++events;
                    if (event.getLong("seq") != seq) throw MasterStore.integrity(); event.put("prev", previous);
                    byte[] blob = MasterCipher.encrypt(key, "event/" + seq, MasterStore.bytes(event)); previous = MasterCipher.hash(blob);
                    ContentValues values = new ContentValues(); values.put("seq", seq); values.put("body", blob); db.insertOrThrow("events", null, values);
                } else if (o.optBoolean("end") && o.getLong("records") == records && o.getLong("events") == events) end = true;
                else throw MasterStore.integrity();
            }
            if (!end) throw MasterStore.integrity();
            validator.finish();
            JSONObject head = new JSONObject().put("restore", receipt).put("seq", events).put("hash", previous).put("state", MasterStore.digest(db)).put("events", MasterStore.eventsDigest(db)).put("schema", MasterStore.schemaDigest(db));
            ContentValues control = new ContentValues(); control.put("id", 1); control.put("body", MasterCipher.encrypt(key, "control", MasterStore.bytes(head))); db.insertOrThrow("control", null, control);
            MasterStore.verify(db, key); db.setTransactionSuccessful();
        } finally { if (db.inTransaction()) db.endTransaction(); db.close(); }
    }

}
