package nl.rene.tools;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Automatische updates vanaf GitHub (renevanes/renes-tools, map update/).
 *
 * - Alleen de gebruikersinterface veranderd (nativeLevel gelijk): de nieuwe
 *   index.html wordt stil gedownload en direct gebruikt.
 * - De app zelf veranderd (hoger nativeLevel): de nieuwe APK wordt gedownload
 *   en via de Android-pakketinstaller aangeboden.
 */
final class Updater {

    static final String PREFS = "update";

    private Updater() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Versie van de interface die nu gebruikt wordt (gebundeld of gedownload). */
    static int webCode(Context c) {
        int stored = prefs(c).getInt("webCode", 0);
        return (stored > Version.CODE && webFile(c).exists()) ? stored : Version.CODE;
    }

    static String webName(Context c) {
        return webCode(c) > Version.CODE ? prefs(c).getString("webName", Version.NAME) : Version.NAME;
    }

    static File webFile(Context c) { return new File(new File(c.getFilesDir(), "web"), "index.html"); }

    /** Gedownloade interface, of null als de gebundelde nieuwer of gelijk is. */
    static String downloadedHtml(Context c) {
        if (webCode(c) <= Version.CODE) return null;
        try { return new String(readAll(new java.io.FileInputStream(webFile(c))), StandardCharsets.UTF_8); }
        catch (Exception e) { return null; }
    }

    /**
     * Adres van de update-map. Via de GitHub-API wordt eerst de nieuwste commit opgezocht,
     * zodat een nieuwe versie direct zichtbaar is (de gewone raw-link wordt tot 5 minuten
     * gecachet). Lukt dat niet, dan de gewone link.
     */
    static String base() {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/renevanes/renes-tools/commits/main").openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setUseCaches(false);
            con.setRequestProperty("Accept", "application/vnd.github.sha");
            try {
                if (con.getResponseCode() == 200) {
                    String sha = new String(readAll(con.getInputStream()), StandardCharsets.UTF_8).trim();
                    if (sha.matches("[0-9a-f]{40}"))
                        return "https://raw.githubusercontent.com/renevanes/renes-tools/" + sha + "/update/";
                }
            } finally { con.disconnect(); }
        } catch (Exception ignored) { }
        return Version.UPDATE_BASE;
    }

    static JSONObject fetchManifest() throws Exception {
        String base = base();
        byte[] b = get(base + "update.json?t=" + System.currentTimeMillis());
        JSONObject m = new JSONObject(new String(b, StandardCharsets.UTF_8));
        m.put("_base", base);
        return m;
    }

    /**
     * Controleert op updates. Resultaat (JSON):
     * state = uptodate | web (interface bijgewerkt, herladen) | apk (nieuwe app beschikbaar) | error
     */
    static JSONObject check(Context c) {
        JSONObject r = new JSONObject();
        try {
            JSONObject m = fetchManifest();
            prefs(c).edit().putLong("lastCheck", System.currentTimeMillis())
                    .putString("manifest", m.toString()).apply();
            int code = m.getInt("versionCode");
            String name = m.getString("versionName");
            int nativeLevel = m.optInt("nativeLevel", Version.NATIVE_LEVEL);
            r.put("versionName", name);
            r.put("versionCode", code);
            if (nativeLevel > Version.NATIVE_LEVEL) {
                r.put("state", "apk");
            } else if (code > webCode(c)) {
                applyWeb(c, m);
                r.put("state", "web");
            } else {
                r.put("state", "uptodate");
            }
        } catch (Exception e) {
            try { r.put("state", "error"); r.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
        }
        return r;
    }

    private static void applyWeb(Context c, JSONObject m) throws Exception {
        byte[] html = get(m.optString("_base", Version.UPDATE_BASE) + m.optString("web", "index.html") + "?v=" + m.getInt("versionCode"));
        String sha = m.optString("webSha256", "");
        if (!sha.isEmpty() && !sha.equalsIgnoreCase(sha256(html))) throw new Exception("Controlegetal interface klopt niet");
        String s = new String(html, StandardCharsets.UTF_8);
        if (!s.contains("RENES-TOOLS-UI")) throw new Exception("Onverwacht bestand");
        File f = webFile(c);
        f.getParentFile().mkdirs();
        File tmp = new File(f.getParentFile(), "index.tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(html); }
        if (!tmp.renameTo(f)) throw new Exception("Opslaan mislukt");
        prefs(c).edit().putInt("webCode", m.getInt("versionCode"))
                .putString("webName", m.getString("versionName")).apply();
    }

    /** Downloadt de nieuwe APK en start de installatie. Geeft null of een foutmelding terug. */
    static String downloadAndInstall(Context c) {
        try {
            JSONObject m = fetchManifest();
            if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
                return "needs-permission";
            }
            byte[] apk = get(m.optString("_base", Version.UPDATE_BASE) + m.optString("apk", "Renes-Tools.apk") + "?v=" + m.getInt("versionCode"));
            String sha = m.optString("apkSha256", "");
            if (!sha.isEmpty() && !sha.equalsIgnoreCase(sha256(apk))) return "Controlegetal van de download klopt niet";

            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (OutputStream o = s.openWrite("base.apk", 0, apk.length)) {
                    o.write(apk);
                    s.fsync(o);
                }
                Intent back = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_INSTALL_STATUS)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getActivity(c, 7, back, fl);
                s.commit(pend.getIntentSender());
            }
            return null;
        } catch (Exception e) {
            return "Update mislukt: " + e.getMessage();
        }
    }

    // ---------- hulpfuncties ----------

    static byte[] get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        con.setUseCaches(false);
        con.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            return readAll(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    static String sha256(byte[] d) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(d);
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
