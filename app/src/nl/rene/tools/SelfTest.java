package nl.rene.tools;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Zelftest: controleert met één tik de onderdelen die op elke telefoon anders kunnen uitpakken.
 * Elk resultaat: id, naam, status (ok / warn / fail / skip), uitleg en eventueel een "fix" voor de interface.
 */
final class SelfTest {

    private SelfTest() { }

    static final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(false);

    static JSONObject item(String id, String name, String status, String detail, String fix) {
        try {
            JSONObject o = new JSONObject().put("id", id).put("name", name).put("status", status).put("detail", detail);
            if (fix != null) o.put("fix", fix);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    static JSONArray run(Context c) {
        JSONArray r = new JSONArray();
        String[] names = {"Gesprekken uitschrijven (Whisper)", "Backup-map", "Internet en radio", "Muziek herkennen (AudD)", "Precieze wekkers",
                "Meldingen", "Batterijbeperking", "Opslag", "Toestemmingen", "Gegevens lezen"};
        for (int i = 0; i < names.length; i++) {
            try { r.put(check(c, i)); }
            catch (Throwable e) { r.put(item("t" + i, names[i], "fail", "De test zelf liep vast: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()), null)); }
        }
        try {
            c.getSharedPreferences("selftest", Context.MODE_PRIVATE).edit()
                    .putString("last", new JSONObject().put("t", System.currentTimeMillis()).put("items", r).toString()).apply();
        } catch (Exception ignored) { }
        return r;
    }

    static JSONObject check(Context c, int i) {
        switch (i) {
            case 0: return whisper(c);
            case 1: return backupFolder(c);
            case 2: return internet(c);
            case 3: return audd(c);
            case 4: return alarms(c);
            case 5: return notifications(c);
            case 6: return battery(c);
            case 7: return storage(c);
            case 8: return permissions(c);
            default: return data(c);
        }
    }

    /** Draait het uitschrijfprogramma met --help; zo weten we of het op deze processor werkt. */
    static JSONObject whisper(Context c) {
        String name = "Gesprekken uitschrijven (Whisper)";
        if (!Transcribe.supported()) return item("whisper", name, "skip", "Deze telefoon heeft geen 64-bit ARM-processor; uitschrijven kan hier niet.", null);
        Transcribe.Model m = Transcribe.activeModel(c);
        String model = m == null ? " Download nog een spraakmodel." : " Model: " + m.label + ".";
        boolean learned = c.getSharedPreferences("transcribe", Context.MODE_PRIVATE).getBoolean("generic", false);
        // Een eerder geleerde keuze voor de algemene versie (na een echte crash) blijft staan: --help gebruikt de snelle instructies niet.
        String fast = learned ? "eerder vastgelopen tijdens uitschrijven" : runHelp(Transcribe.binary(c, false));
        if (fast == null)
            return item("whisper", name, m == null ? "warn" : "ok", "Het programma start (snelle versie)." + model, m == null ? "transcripts" : null);
        String gen = runHelp(Transcribe.binary(c, true));
        if (gen == null) {
            c.getSharedPreferences("transcribe", Context.MODE_PRIVATE).edit().putBoolean("generic", true).apply();
            return item("whisper", name, m == null ? "warn" : "ok", "Het programma start (algemene versie; de snelle versie werkt niet op deze processor: " + fast + ")." + model, m == null ? "transcripts" : null);
        }
        return item("whisper", name, "fail", "Het programma start niet: " + gen, null);
    }

    /** null = gelukt, anders de foutomschrijving. */
    static String runHelp(File bin) {
        if (!bin.isFile()) return "programma ontbreekt";
        Process p = null;
        try {
            p = new ProcessBuilder(bin.getPath(), "--help").redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            StringBuilder out = new StringBuilder();
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end) {
                int avail = in.available();
                if (avail > 0) { int n = in.read(buf, 0, Math.min(buf.length, avail)); if (n > 0 && out.length() < 4000) out.append(new String(buf, 0, n, "UTF-8")); }
                else {
                    try { int code = p.exitValue(); return code == 0 || out.indexOf("usage") >= 0 ? null : (code == 132 ? "processor kent een instructie niet" : "afgesloten met code " + code); }
                    catch (IllegalThreadStateException running) { Thread.sleep(50); }
                }
            }
            return "reageert niet";
        } catch (Exception e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally { if (p != null) p.destroy(); }
    }

    /** Maakt een klein testbestand in de backup-map en haalt het weer weg. */
    static JSONObject backupFolder(Context c) {
        String name = "Backup-map";
        Uri tree = WaBackup.destUri(c);
        if (tree == null) return item("dest", name, "warn", "Nog geen backup-map gekozen.", "dest");
        Uri f = null;
        WaBackup.Dest dest = null;
        try {
            dest = new WaBackup.Dest(c.getContentResolver(), tree);
            WaBackup.DestDir dir = dest.root();
            f = DocumentsContract.createDocument(dest.cr, dir.uri, "text/plain", "rene-tools-zelftest.txt");
            if (f == null) throw new Exception("bestand maken lukt niet");
            try (OutputStream o = dest.cr.openOutputStream(f, "w")) {
                if (o == null) throw new Exception("bestand openen lukt niet");
                o.write("test".getBytes("UTF-8"));
            }
            Uri done = f;
            f = null;
            DocumentsContract.deleteDocument(dest.cr, done);
            return item("dest", name, "ok", "Schrijven lukt in " + WaBackup.destName(c) + ".", null);
        } catch (Exception e) {
            return item("dest", name, "fail", "Schrijven lukt niet (" + e.getMessage() + "). Kies de map opnieuw; is de USB-stick of SD-kaart aangesloten?", "dest");
        } finally {
            if (f != null && dest != null) try { DocumentsContract.deleteDocument(dest.cr, f); } catch (Exception ignored) { }
        }
    }

    static JSONObject internet(Context c) {
        String name = "Internet en radio";
        try {
            String r = Radio.api(c, "/json/stations/search?countrycode=NL&hidebroken=true&order=clickcount&reverse=true&limit=1");
            JSONArray a = new JSONArray(r);
            if (a.length() == 0) return item("radio", name, "warn", "De zenderlijst is bereikbaar maar leeg.", null);
            JSONObject st = a.getJSONObject(0);
            String url = st.optString("url_resolved", "");
            if (url.isEmpty()) url = st.optString("url", "");
            if (url.isEmpty()) return item("radio", name, "warn", "De zenderlijst werkt, maar de zender heeft geen streamadres.", null);
            boolean hls = st.optInt("hls", 0) == 1 || url.contains(".m3u8");
            HttpURLConnection h = null;
            for (int hop = 0; hop < 5; hop++) {
                h = (HttpURLConnection) new URL(url).openConnection();
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                h.setRequestProperty("User-Agent", Radio.UA);
                int code = h.getResponseCode();
                String loc = h.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) { h.disconnect(); url = new URL(new URL(url), loc).toString(); continue; }
                if (code >= 400) { h.disconnect(); return item("radio", name, "warn", "De zenderlijst werkt, maar de stream gaf fout " + code + ".", null); }
                break;
            }
            int got = 0;
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while (got < 16_000 && (n = in.read(buf)) > 0) got += n;
            } finally { h.disconnect(); }
            return got >= 4000 || (hls && got > 0) ? item("radio", name, "ok", "Zenderlijst en stream (" + a.getJSONObject(0).optString("name") + ") werken.", null)
                    : item("radio", name, "warn", "De stream gaf weinig gegevens.", null);
        } catch (Exception e) {
            return item("radio", name, "fail", "Geen verbinding: " + e.getMessage(), null);
        }
    }

    /** Controleert de AudD-sleutel zonder een herkenning te verbruiken. */
    static JSONObject audd(Context c) {
        String name = "Muziek herkennen (AudD)";
        String token = Music.prefs(c).getString("token", "").trim();
        if (token.isEmpty()) return item("audd", name, "skip", "Geen sleutel ingesteld (alleen nodig voor muziek herkennen).", "audd");
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://api.audd.io/getCallbackUrl/").openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            h.setRequestProperty("User-Agent", Radio.UA);
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = h.getOutputStream()) { o.write(("api_token=" + Uri.encode(token)).getBytes("UTF-8")); }
            String body;
            try (InputStream in = h.getResponseCode() >= 400 ? h.getErrorStream() : h.getInputStream()) {
                body = in == null ? "" : new String(readAll(in), "UTF-8");
            } finally { h.disconnect(); }
            JSONObject o = new JSONObject(body);
            JSONObject err = o.optJSONObject("error");
            int code = err == null ? 0 : err.optInt("error_code");
            if (code == 900 || code == 901) return item("audd", name, "fail", "De sleutel wordt niet geaccepteerd (fout " + code + ").", "audd");
            if (code == 902) return item("audd", name, "fail", "Het tegoed van deze sleutel is op of het abonnement is verlopen (fout 902).", "audd");
            // Andere foutcodes (bijv. 'geen callback ingesteld') gaan niet over de sleutel: die is dan geaccepteerd.
            return item("audd", name, "ok", "De sleutel wordt geaccepteerd.", null);
        } catch (Exception e) {
            return item("audd", name, "warn", "Niet te controleren: " + String.valueOf(e.getMessage()).replace(token, "…"), null);
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0 && b.size() < 100_000) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static JSONObject alarms(Context c) {
        String name = "Precieze wekkers";
        if (Build.VERSION.SDK_INT < 31) return item("alarms", name, "ok", "Toegestaan.", null);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        boolean ok = am != null && am.canScheduleExactAlarms();
        return ok ? item("alarms", name, "ok", "Radiowekker en herinneringen gaan op tijd af.", null)
                : item("alarms", name, "warn", "Niet toegestaan: de wekker kan een paar minuten later afgaan.", "alarms");
    }

    static JSONObject notifications(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean ok = nm != null && nm.areNotificationsEnabled();
        return ok ? item("notif", "Meldingen", "ok", "Aan.", null)
                : item("notif", "Meldingen", "fail", "Uit: je ziet geen voortgang, herinneringen of wekker-knoppen.", "notif");
    }

    /** Oppo/ColorOS stopt achtergrondtaken agressief; zonder uitzondering kunnen nachtelijke backups wegvallen. */
    static JSONObject battery(Context c) {
        String name = "Batterijbeperking";
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean free = pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        return free ? item("battery", name, "ok", "De app mag op de achtergrond werken (nachtelijke backups, wekker).", null)
                : item("battery", name, "warn", "Android kan de nachtelijke backup en het uitschrijven stoppen om stroom te besparen. Op een Oppo ook: Instellingen → Batterij → app → Achtergrondactiviteit toestaan.", "battery");
    }

    static JSONObject storage(Context c) {
        try {
            StatFs s = new StatFs(c.getFilesDir().getPath());
            long free = s.getAvailableBytes();
            String gb = String.format(java.util.Locale.US, "%.1f GB", free / 1e9);
            return free > 1_000_000_000L ? item("storage", "Opslag", "ok", gb + " vrij.", null)
                    : item("storage", "Opslag", "warn", "Nog maar " + gb + " vrij; het spraakmodel en backups hebben ruimte nodig.", null);
        } catch (Exception e) { return item("storage", "Opslag", "warn", "Onbekend.", null); }
    }

    static JSONObject permissions(Context c) {
        String[][] p = {
                {Manifest.permission.CALL_PHONE, "bellen"}, {Manifest.permission.READ_CALL_LOG, "oproepgeschiedenis"},
                {Manifest.permission.READ_CONTACTS, "contacten"}, {Manifest.permission.READ_SMS, "sms"},
                {Manifest.permission.ACCESS_FINE_LOCATION, "locatie"}, {Manifest.permission.RECORD_AUDIO, "microfoon"}};
        StringBuilder miss = new StringBuilder();
        for (String[] x : p) if (!has(c, x[0])) { if (miss.length() > 0) miss.append(", "); miss.append(x[1]); }
        if (!MainActivity.hasFilesAccess(c)) { if (miss.length() > 0) miss.append(", "); miss.append("alle bestanden"); }
        return miss.length() == 0 ? item("perms", "Toestemmingen", "ok", "Alles toegestaan.", null)
                : item("perms", "Toestemmingen", "warn", "Nog niet toegestaan: " + miss + ". Alleen nodig voor de tools die het gebruiken.", "settings");
    }

    /** Kan de app de eigen gegevens van de telefoon lezen? */
    static JSONObject data(Context c) {
        StringBuilder ok = new StringBuilder(), bad = new StringBuilder();
        if (has(c, Manifest.permission.READ_CONTACTS)) {
            try { ok.append("contacten (").append(Contacts.read(c).size()).append(") "); }
            catch (Exception e) { bad.append("contacten "); }
        }
        if (has(c, Manifest.permission.READ_CALL_LOG)) ok.append("oproepen (").append(Calls.countTotal(c)).append(") ");
        if (has(c, Manifest.permission.READ_SMS)) ok.append("sms (").append(Sms.countTotal(c)).append(") ");
        if (ok.length() == 0 && bad.length() == 0) return item("data", "Gegevens lezen", "skip", "Geen toestemmingen voor contacten, oproepen of sms.", null);
        return bad.length() == 0 ? item("data", "Gegevens lezen", "ok", "Leesbaar: " + ok.toString().trim() + ".", null)
                : item("data", "Gegevens lezen", "fail", "Niet leesbaar: " + bad.toString().trim() + ".", null);
    }
}
