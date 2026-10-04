package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Muziek herkennen (eigen "SoundHound"). Een app heeft zelf geen database van tientallen
 * miljoenen nummers; daarom gaat een kort geluidsfragment (ca. 10 seconden, via de microfoon
 * of rechtstreeks van de radiostream) naar de herkenningsdienst AudD, met de eigen API-sleutel
 * van de gebruiker. Resultaten worden lokaal bewaard als geschiedenis.
 */
final class Music {

    private Music() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("music", Context.MODE_PRIVATE); }

    // Toestand van de lopende herkenning, voor de interface.
    static volatile String state = "idle"; // idle, recording, sending, done, error
    static volatile String lastResult = null;
    static volatile String lastError = null;
    static volatile long stateAt = 0;
    /** Nummer van de lopende herkenning; een oude (geannuleerde) poging mag niets meer veranderen. */
    static final java.util.concurrent.atomic.AtomicInteger run = new java.util.concurrent.atomic.AtomicInteger();
    static volatile HttpURLConnection upload;

    static void set(String s) { state = s; stateAt = System.currentTimeMillis(); }

    static synchronized void set(int id, String s) { if (run.get() == id) set(s); }

    /** Neemt 'seconds' seconden op via de microfoon (AAC in .m4a). */
    static File record(Context c, int seconds, int id) throws Exception {
        File f = new File(c.getCacheDir(), "herken-" + id + ".m4a");
        f.delete();
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(c) : new MediaRecorder();
        boolean started = false;
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(44100);
            r.setAudioChannels(1);
            r.setAudioEncodingBitRate(128_000);
            r.setOutputFile(f.getPath());
            r.prepare();
            r.start();
            started = true;
            long end = System.currentTimeMillis() + seconds * 1000L;
            // Alleen deze thread bedient de recorder; annuleren zet alleen het runnummer om.
            while (System.currentTimeMillis() < end && run.get() == id) Thread.sleep(100);
            r.stop();
            started = false;
            if (run.get() != id) throw new Exception("Gestopt");
        } finally {
            if (started) { try { r.stop(); } catch (Exception ignored) { } }
            try { r.release(); } catch (Exception ignored) { }
        }
        if (f.length() < 5000) throw new Exception("Opname mislukt (geen geluid)");
        return f;
    }

    /** Stuurt een geluidsbestand naar AudD en geeft het resultaat (of null als het nummer niet gevonden is). */
    static JSONObject recognize(Context c, File audio, String source) throws Exception {
        String token = prefs(c).getString("token", "").trim();
        if (token.isEmpty()) throw new Exception("Vul eerst je AudD-sleutel in");
        String boundary = "----rt" + Long.toHexString(System.nanoTime());
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.audd.io/").openConnection();
        upload = h;
        h.setConnectTimeout(15000);
        h.setReadTimeout(40000);
        h.setDoOutput(true);
        h.setRequestMethod("POST");
        h.setRequestProperty("User-Agent", Radio.UA);
        h.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (DataOutputStream o = new DataOutputStream(h.getOutputStream())) {
            field(o, boundary, "api_token", token);
            field(o, boundary, "return", "apple_music,spotify");
            o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + audio.getName()
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            try (InputStream in = new FileInputStream(audio)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            o.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = h.getResponseCode();
        String body;
        try (InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
            body = new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally { h.disconnect(); upload = null; }
        prefs(c).edit().putInt("used", prefs(c).getInt("used", 0) + 1).apply();
        JSONObject r;
        try { r = new JSONObject(body); }
        catch (Exception e) { throw new Exception("AudD gaf een onverwacht antwoord (HTTP " + code + ")"); }
        if (!"success".equals(r.optString("status"))) {
            JSONObject e = r.optJSONObject("error");
            int ec = e == null ? 0 : e.optInt("error_code");
            String msg = e == null ? "Onbekende fout" : e.optString("error_message", "Onbekende fout");
            if (ec == 900 || ec == 901 || ec == 902) msg = "AudD-sleutel ongeldig of het tegoed is op (fout " + ec + ")";
            throw new Exception(msg);
        }
        JSONObject res = r.optJSONObject("result");
        if (res == null) return null;
        JSONObject out = new JSONObject();
        out.put("t", System.currentTimeMillis()).put("source", source);
        out.put("artist", res.optString("artist")).put("title", res.optString("title")).put("album", res.optString("album"))
                .put("date", res.optString("release_date")).put("label", res.optString("label")).put("link", res.optString("song_link"));
        String art = "", spotify = "", apple = "";
        JSONObject am = res.optJSONObject("apple_music");
        if (am != null) {
            apple = am.optString("url");
            JSONObject aw = am.optJSONObject("artwork");
            if (aw != null) art = aw.optString("url").replace("{w}", "300").replace("{h}", "300");
        }
        JSONObject sp = res.optJSONObject("spotify");
        if (sp != null) {
            JSONObject eu = sp.optJSONObject("external_urls");
            if (eu != null) spotify = eu.optString("spotify");
            JSONObject al = sp.optJSONObject("album");
            JSONArray im = al == null ? null : al.optJSONArray("images");
            if (art.isEmpty() && im != null && im.length() > 0) art = im.getJSONObject(Math.min(1, im.length() - 1)).optString("url");
        }
        out.put("art", art).put("spotify", spotify).put("apple", apple);
        return out;
    }

    private static void field(DataOutputStream o, String b, String name, String value) throws Exception {
        o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    // ---------- geschiedenis ----------

    static File historyFile(Context c) { return new File(c.getFilesDir(), "music.json"); }

    static synchronized JSONArray history(Context c) {
        try { return historyFile(c).isFile() ? new JSONArray(Contacts.readText(historyFile(c), false)) : new JSONArray(); }
        catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void addHistory(Context c, JSONObject item) throws Exception {
        JSONArray h = history(c), out = new JSONArray();
        out.put(item);
        for (int i = 0; i < h.length() && out.length() < 500; i++) out.put(h.get(i));
        Contacts.writeText(historyFile(c), out.toString(), false);
    }

    static synchronized void deleteHistory(Context c, long t) throws Exception {
        JSONArray h = history(c), out = new JSONArray();
        for (int i = 0; i < h.length(); i++) if (h.getJSONObject(i).optLong("t") != t) out.put(h.get(i));
        Contacts.writeText(historyFile(c), out.toString(), false);
    }

    /** Herkent op de achtergrond: via de microfoon (radioUrl == null) of een stukje van de radiostream. */
    static String start(Context c, String radioUrl, int bitrate, String stationName) {
        if (!"idle".equals(state) && !"done".equals(state) && !"error".equals(state)) return "Er loopt al een herkenning";
        if (prefs(c).getString("token", "").trim().isEmpty()) return "Vul eerst je AudD-sleutel in";
        final int id;
        synchronized (Music.class) {
            id = run.incrementAndGet();
            lastResult = null;
            lastError = null;
            set(radioUrl == null ? "recording" : "sending");
        }
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            File f = null;
            try {
                f = radioUrl == null ? record(app, 10, id) : Radio.snippet(app, radioUrl, bitrate, 10, id);
                if (run.get() != id) return;
                set(id, "sending");
                JSONObject r = recognize(app, f, radioUrl == null ? "mic" : "radio:" + (stationName == null ? "" : stationName));
                if (run.get() != id) return; // geannuleerd terwijl het antwoord onderweg was
                if (r != null) addHistory(app, r);
                synchronized (Music.class) {
                    if (run.get() != id) return;
                    lastResult = r != null ? r.toString() : "{}";
                    set("done");
                }
            } catch (Throwable e) {
                synchronized (Music.class) {
                    if (run.get() != id) return;
                    lastError = e.getMessage() != null ? e.getMessage() : "Herkennen mislukt";
                    set("error");
                }
            } finally {
                if (f != null) f.delete();
            }
        }, "music").start();
        return "";
    }

    static synchronized void cancel() {
        run.incrementAndGet(); // de lopende poging is hiermee ongeldig
        set("idle");
        HttpURLConnection h = upload;
        if (h != null) { try { h.disconnect(); } catch (Exception ignored) { } }
    }

    static String stateJson(Context c) {
        try {
            JSONObject o = new JSONObject().put("state", state).put("at", stateAt).put("used", prefs(c).getInt("used", 0))
                    .put("hasToken", !prefs(c).getString("token", "").trim().isEmpty());
            if (lastResult != null) o.put("result", new JSONObject(lastResult));
            if (lastError != null) o.put("error", lastError);
            return o.toString();
        } catch (Exception e) { return "{\"state\":\"idle\"}"; }
    }
}
