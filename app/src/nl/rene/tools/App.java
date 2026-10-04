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
                + (log.isEmpty() ? "Geen fouten vastgelegd.\n" : log);
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
