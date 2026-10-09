package nl.rene.tools;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Applicatie: legt fouten vast in een foutlogboek (files/crash.log), ook als ze in een
 * achtergrondservice gebeuren, zodat ze gedeeld kunnen worden via Instellingen → Foutrapport.
 */
public class App extends Application {

    static final int MAX = 120_000;

    /** Geheim van deze installatie in eigen intents (snelkoppelingen, meldingen, installatie-status): andere apps kennen het niet. */
    static synchronized String token(android.content.Context c) {
        // Eigen bestand, buiten Android-backup en overzetten (zie backup_rules.xml): het geheim hoort bij deze installatie
        android.content.SharedPreferences p = c.getSharedPreferences("intent_token", android.content.Context.MODE_PRIVATE);
        String t = p.getString("intentToken", null);
        if (t == null) {
            byte[] b = new byte[16]; new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
            t = sb.toString(); p.edit().putString("intentToken", t).apply();
        }
        return t;
    }

    static boolean tokenOk(android.content.Context c, android.content.Intent i) {
        String t = i == null ? null : i.getStringExtra("tok");
        return t != null && java.security.MessageDigest.isEqual(t.getBytes(), token(c).getBytes());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final Context c = getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                log(c, "CRASH", "thread " + t.getName() + "\n" + sw);
                c.getSharedPreferences("crash", MODE_PRIVATE).edit().putBoolean("new", true).commit();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
        // Na geforceerd stoppen zijn alle wekkers weg: bij de eerstvolgende start terugzetten
        new Thread(() -> AlarmReceiver.ensureArmed(c), "rearm").start();
    }

    /** Is de telefoon na het opstarten al een keer ontgrendeld? (Daarvoor zijn de gewone instellingen onleesbaar.) */
    static boolean unlocked(Context c) {
        android.os.UserManager um = (android.os.UserManager) c.getSystemService(Context.USER_SERVICE);
        return um == null || um.isUserUnlocked();
    }

    static File file(Context c) { return new File(c.getFilesDir(), "crash.log"); }

    /** Voegt een regel toe aan het foutlogboek (bewaart de laatste ~120 kB). */
    static synchronized void log(Context c, String kind, String text) {
        try {
            File f = file(c);
            if (f.length() > MAX) {
                String old = Contacts.readText(f, false);
                String keep = old.substring(old.length() - MAX / 2);
                int cut = keep.indexOf("\n\n");
                Contacts.writeText(f, cut >= 0 ? keep.substring(cut + 2) : keep, false); // op een hele melding beginnen
            }
            String head = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " [" + kind + "] v" + Version.NAME + " (" + Version.CODE + ")\n";
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write((head + text.trim() + "\n\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) { }
    }

    /** Het rapport om te delen: toestel, versie en het logboek. */
    static String report(Context c) {
        String log;
        try { log = file(c).isFile() ? Contacts.readText(file(c), false) : ""; } catch (Exception e) { log = ""; }
        if (log.length() > 60_000) log = "…(oudere meldingen weggelaten)\n" + log.substring(log.length() - 60_000);
        return "Foutrapport Rene's Tools\n"
                + "Versie: " + Version.NAME + " (" + Version.CODE + "), onderdeel " + Version.NATIVE_LEVEL + "\n"
                + "Toestel: " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Processor: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS) + "\n\n"
                + selfTest(c)
                + (log.isEmpty() ? "Geen fouten vastgelegd.\n" : log);
    }

    /** Uitkomst van de laatste zelftest, voor in het rapport. */
    static String selfTest(Context c) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(c.getSharedPreferences("selftest", MODE_PRIVATE).getString("last", "{}"));
            org.json.JSONArray a = o.optJSONArray("items");
            if (a == null) return "";
            StringBuilder b = new StringBuilder("Zelftest " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(o.optLong("t"))) + ":\n");
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject x = a.getJSONObject(i);
                b.append("  [").append(x.optString("status")).append("] ").append(x.optString("name")).append(": ").append(x.optString("detail")).append('\n');
            }
            return b.append('\n').toString();
        } catch (Exception e) { return ""; }
    }

    static int count(Context c) {
        try {
            if (!file(c).isFile()) return 0;
            String s = Contacts.readText(file(c), false);
            int n = 0, i = 0;
            while ((i = s.indexOf("[CRASH] v", i)) >= 0) { n++; i += 9; }
            return n;
        } catch (Exception e) { return 0; }
    }
}
