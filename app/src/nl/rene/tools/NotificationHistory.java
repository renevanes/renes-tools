package nl.rene.tools;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private lokale geschiedenis; exports gebruiken dezelfde bestemming/versleuteling als andere tools. */
final class NotificationHistory {
    static final String DIR = "Meldingen backup";
    static final AtomicBoolean exporting = new AtomicBoolean(false);
    static volatile boolean connected;
    private static Db db;
    private NotificationHistory() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("notification_history", Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static boolean allowed(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            try {
                android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
                if (nm != null) return nm.isNotificationListenerAccessGranted(new ComponentName(c, HistoryListener.class));
            } catch (Exception ignored) { }
        }
        String list = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        ComponentName own = new ComponentName(c, HistoryListener.class);
        if (list != null) for (String entry : list.split(":")) if (own.equals(ComponentName.unflattenFromString(entry))) return true;
        return false;
    }
    private static final class Db extends SQLiteOpenHelper {
        Db(Context c) { super(c.getApplicationContext(), "notification-history.db", null, 2); }
        @Override public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT NOT NULL UNIQUE, time INTEGER NOT NULL, package TEXT NOT NULL, app TEXT NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, fold TEXT NOT NULL DEFAULT '')");
            d.execSQL("CREATE INDEX history_time ON history(time)");
        }
        @Override public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // Zoektekst zonder accenten en hoofdletters (SQLite's lower() kent alleen A–Z)
                d.execSQL("ALTER TABLE history ADD COLUMN fold TEXT NOT NULL DEFAULT ''");
                try (Cursor cur = d.query("history", new String[]{"id", "app", "package", "title", "text"}, null, null, null, null, null)) {
                    while (cur.moveToNext()) {
                        android.content.ContentValues v = new android.content.ContentValues();
                        v.put("fold", foldOf(cur.getString(1), cur.getString(2), cur.getString(3), cur.getString(4)));
                        d.update("history", v, "id = ?", new String[]{Long.toString(cur.getLong(0))});
                    }
                }
            }
        }
    }
    static String foldOf(String app, String pkg, String title, String text) { return HistoryText.fold(app + " " + pkg + " " + title + " " + text); }

    private static synchronized SQLiteDatabase database(Context c) {
        if (db == null) db = new Db(c);
        return db.getWritableDatabase();
    }
    static synchronized void record(Context c, String key, long time, String pkg, String app, String title, String text) throws Exception {
        if (!enabled(c)) return;
        SQLiteDatabase d = database(c);
        d.beginTransaction();
        try {
            // Reconnects kunnen dezelfde melding opnieuw aanbieden; wijzigingen blijven wel bewaard.
            d.execSQL("INSERT OR IGNORE INTO history (fingerprint,time,package,app,title,text,fold) VALUES (?,?,?,?,?,?,?)",
                    new Object[]{HistoryText.fingerprint(key, time, title, text), time, pkg, app, title, text, foldOf(app, pkg, title, text)});
            d.delete("history", "time < ?", new String[]{Long.toString(System.currentTimeMillis() - 30L * 86400000)});
            d.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY time DESC, id DESC LIMIT 10000)");
            d.setTransactionSuccessful();
        } finally { d.endTransaction(); }
        prefs(c).edit().remove("error").apply();
    }
    private static String selection(String q) { return q.isEmpty() ? null : "instr(fold, ?) > 0"; }
    private static String[] args(String q) { return q.isEmpty() ? null : new String[]{HistoryText.fold(q)}; }

    /** Klein "vingerafdrukje" van de toestand, om alleen opnieuw te laden als er iets veranderd is. */
    static synchronized String stamp(Context c) {
        long total = 0, max = 0;
        try (Cursor cur = database(c).rawQuery("SELECT COUNT(*), COALESCE(MAX(id),0) FROM history", null)) {
            if (cur.moveToFirst()) { total = cur.getLong(0); max = cur.getLong(1); }
        } catch (Exception ignored) { }
        return total + ":" + max + ":" + enabled(c) + ":" + allowed(c) + ":" + connected + ":" + (WaBackup.destUri(c) != null) + ":" + prefs(c).getString("error", "");
    }
    private static JSONObject row(Cursor cur) throws Exception {
        return new JSONObject().put("time", cur.getLong(0)).put("package", cur.getString(1)).put("app", cur.getString(2))
                .put("title", cur.getString(3)).put("text", cur.getString(4));
    }
    static synchronized String list(Context c, String query) throws Exception {
        String q = HistoryText.clean(query, 200).trim();
        SQLiteDatabase d = database(c);
        prune(d);
        JSONArray rows = new JSONArray();
        try (Cursor cur = d.query("history", new String[]{"time", "package", "app", "title", "text"}, selection(q), args(q), null, null, "time DESC, id DESC", "500")) {
            while (cur.moveToNext()) rows.put(row(cur));
        }
        long total, count;
        try (Cursor cur = d.rawQuery("SELECT COUNT(*) FROM history", null)) { cur.moveToFirst(); total = cur.getLong(0); }
        try (Cursor cur = d.query("history", new String[]{"COUNT(*)"}, selection(q), args(q), null, null, null)) { cur.moveToFirst(); count = cur.getLong(0); }
        return new JSONObject().put("rows", rows).put("total", total).put("count", count).put("enabled", enabled(c))
                .put("allowed", allowed(c)).put("connected", connected).put("dest", WaBackup.destUri(c) != null)
                .put("error", prefs(c).getString("error", "")).toString();
    }
    private static void prune(SQLiteDatabase d) {
        d.delete("history", "time < ?", new String[]{Long.toString(System.currentTimeMillis() - 30L * 86400000)});
    }
    static synchronized void clear(Context c) { database(c).delete("history", null, null); }

    private static java.io.BufferedReader reader(java.io.File file) throws Exception {
        return new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8));
    }
    static int export(Context c) throws Exception { return export(c, false); }

    /** @param wait wachten als er al een export loopt (de nachtelijke backup), in plaats van te mislukken */
    static int export(Context c, boolean wait) throws Exception {
        if (wait) {
            long until = System.currentTimeMillis() + 15 * 60_000L;
            while (!exporting.compareAndSet(false, true)) {
                if (System.currentTimeMillis() > until) throw new Exception("Er loopt al een meldingen-export");
                Thread.sleep(2000);
            }
        } else if (!exporting.compareAndSet(false, true)) throw new Exception("Er loopt al een meldingen-export");
        try {
            if (WaBackup.destUri(c) == null) throw new Exception("Kies eerst een backup-map");
            // Overgebleven tijdelijke kopieën van een afgebroken export (met meldingstekst) eerst weg
            java.io.File[] old = c.getCacheDir().listFiles();
            if (old != null) for (java.io.File f : old) if (f.getName().startsWith("history-export-")) f.delete();
            SQLiteDatabase d;
            long lastId;
            synchronized (NotificationHistory.class) {
                d = database(c); prune(d);
                try (Cursor cur = d.rawQuery("SELECT COALESCE(MAX(id),0) FROM history", null)) { cur.moveToFirst(); lastId = cur.getLong(0); }
            }
            if (lastId == 0) return -1;
            WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
            WaBackup.DestDir dir = dest.dir(DIR, true);
            String name = "meldingen-" + new SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(new Date());
            SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy HH:mm:ss", new Locale("nl", "NL"));
            int count = 0;
            java.io.File snapshot = java.io.File.createTempFile("history-export-", ".jsonl", c.getCacheDir());
            try {
                // Begrensd geheugen en één momentopname voor beide bestanden. De tijdelijke
                // kopie staat in de private app-cache en wordt ook na een mislukte export gewist.
                try (Cursor cur = d.query("history", new String[]{"time", "package", "app", "title", "text"}, "id <= ?", new String[]{Long.toString(lastId)}, null, null, "time DESC, id DESC");
                     Writer temp = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(snapshot), java.nio.charset.StandardCharsets.UTF_8))) {
                    while (cur.moveToNext()) { temp.write(row(cur).toString()); temp.write("\n"); count++; }
                }
                try (java.io.BufferedReader in = reader(snapshot);
                     Writer json = Sms.open(dest, dir, name + ".json", "application/json")) {
                    json.write("{\"format\":\"renes-tools-notifications\",\"version\":1,\"exportedAt\":" + System.currentTimeMillis() + ",\"notifications\":[");
                    String line; boolean first = true;
                    while ((line = in.readLine()) != null) { if (!first) json.write(","); json.write(line); first = false; }
                    json.write("]}");
                }
                // ZipOutputStream staat één bestand tegelijk toe (versleutelde Alles-backup).
                try (java.io.BufferedReader in = reader(snapshot);
                     Writer html = Sms.open(dest, dir, name + ".html", "text/html")) {
                    html.write("<!doctype html><html lang=nl><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Meldingsgeschiedenis</title><style>body{font:16px system-ui;max-width:850px;margin:24px auto;padding:0 16px}article{border-bottom:1px solid #ccc;padding:16px 0}p{white-space:pre-wrap;overflow-wrap:anywhere}small{color:#555}</style><h1>Meldingsgeschiedenis</h1>");
                    String line;
                    while ((line = in.readLine()) != null) {
                        JSONObject r = new JSONObject(line);
                        html.write("<article><small>" + HistoryText.html(df.format(new Date(r.getLong("time")))) + " · " + HistoryText.html(r.getString("app")) + " · " + HistoryText.html(r.getString("package")) + "</small><h2>" + HistoryText.html(r.getString("title")) + "</h2><p>" + HistoryText.html(r.getString("text")) + "</p></article>");
                    }
                    html.write("</html>");
                }
                return count;
            } finally { snapshot.delete(); }
        } finally { exporting.set(false); }
    }
}
