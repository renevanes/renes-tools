package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Slaappraat: de nachten en de stukjes die SleepService opving, alleen op de telefoon (files/sleep, niet in
 * Android-backups). Per nacht een map met night.json en per stukje een WAV-bestand (16 kHz mono, zoals whisper
 * het wil). Na de nacht worden de stukjes uitgeschreven met het spraakmodel van Gesprekken uitschrijven; wat
 * geen praten blijkt (snurken, kuchen, een auto) heet "geluid" en het geluid daarvan gaat weg, tenzij je dat
 * wilt bewaren. Het zuivere rekenwerk wordt getest in SleepTalkTest.
 */
final class SleepTalk {

    private SleepTalk() { }

    /** Hooguit zoveel seconden geluid per nacht bewaren (±85 MB); daarna alleen nog tellen. */
    static final int MAX_AUDIO_S = 45 * 60;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sleeptalk", Context.MODE_PRIVATE); }

    static int sens(Context c) { int s = prefs(c).getInt("sens", 2); return s < 1 || s > 3 ? 2 : s; }
    static String stopAt(Context c) { String s = prefs(c).getString("stopAt", "07:30"); return validTime(s) ? s : ""; }
    static boolean transcribe(Context c) { return prefs(c).getBoolean("transcribe", true); }
    static boolean keepNoise(Context c) { return prefs(c).getBoolean("keepNoise", false); }
    static int keepDays(Context c) { int d = prefs(c).getInt("keep", 14); return d == 7 || d == 30 ? d : 14; }
    static int minBattery(Context c) { return 15; }

    // ---------- zuiver (getest) ----------

    static boolean validTime(String s) { return s != null && (s.isEmpty() || s.matches("([01]\\d|2[0-3]):[0-5]\\d")); }

    /** Wanneer stoppen: de eerstvolgende keer dat het "HH:mm" is na het starten (de volgende ochtend). 0 = niet. */
    static long stopTime(long startMs, String hhmm, java.util.TimeZone tz) {
        if (hhmm == null || !hhmm.matches("([01]\\d|2[0-3]):[0-5]\\d")) return 0;
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(startMs);
        k.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hhmm.substring(0, 2)));
        k.set(Calendar.MINUTE, Integer.parseInt(hhmm.substring(3)));
        k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        // Minstens een kwartier na het starten (anders stopt het meteen als je om 07:20 start met stop om 07:30)
        while (k.getTimeInMillis() <= startMs + 15 * 60_000L) k.add(Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis();
    }

    private static final String[] JUNK = {"ondertitel", "bedankt voor het kijken", "bedankt voor het luisteren", "tot de volgende keer", "amara.org",
            "abonneer", "tv gelderland", "thank you for watching", "subtitles by", "♪"};

    /** De tekst van whisper zonder [muziek], (geluid) en wat whisper bij stilte of ruis vaak verzint. */
    static String cleanText(List<String> parts) {
        StringBuilder all = new StringBuilder();
        for (String t : parts) {
            String x = t == null ? "" : t.trim();
            if (x.isEmpty() || x.startsWith("[") || x.startsWith("(") || x.startsWith("*")) continue;
            String low = x.toLowerCase(Locale.ROOT);
            boolean junk = false;
            for (String j : JUNK) if (low.contains(j)) { junk = true; break; }
            if (junk) continue;
            all.append(all.length() > 0 ? " " : "").append(x);
        }
        return all.toString().trim();
    }

    /** Praten of geluid? Praten = er staat echte tekst (minstens één woord van 2 letters). */
    static String kind(String text) {
        if (text == null) return "geluid";
        for (String w : text.split("[^\\p{L}]+")) if (w.length() >= 2) return "praten";
        return "geluid";
    }

    /** 16-bit PCM als WAV (16 kHz mono). */
    static byte[] wav(short[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + pcm.length * 2).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1).putInt(SleepDetect.RATE).putInt(SleepDetect.RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcm.length * 2);
        for (short s : pcm) b.putShort(s);
        return b.array();
    }

    // ---------- opslag ----------

    static File root(Context c) { File d = new File(c.getFilesDir(), "sleep"); d.mkdirs(); return d; }
    private static File dir(Context c, String night) { return new File(root(c), night); }
    private static File nightFile(Context c, String night) { return new File(dir(c, night), "night.json"); }
    static boolean safeId(String s) { return s != null && s.matches("[0-9]{10,16}"); }
    static boolean safeClip(String s) { return s != null && s.matches("c[0-9]{10,16}"); }

    static synchronized JSONObject night(Context c, String id) {
        try {
            if (!safeId(id)) return null;
            File f = nightFile(c, id);
            return f.isFile() ? new JSONObject(Contacts.readText(f, false)) : null;
        } catch (Exception e) { return null; }
    }

    private static void write(Context c, JSONObject n) throws Exception { Contacts.writeText(nightFile(c, n.optString("id")), n.toString(), false); }

    /** Een nieuwe nacht; geeft het id. */
    static synchronized String begin(Context c, long start, int sens) throws Exception {
        String id = String.valueOf(start);
        dir(c, id).mkdirs();
        write(c, new JSONObject().put("id", id).put("start", start).put("end", 0).put("sens", sens).put("clips", new JSONArray()).put("heard", 0));
        return id;
    }

    /** Een stukje bewaren (geluid als WAV, tenzij de nacht al vol zit). */
    static synchronized void addClip(Context c, String night, long nightStart, SleepDetect.Clip k) throws Exception {
        JSONObject n = night(c, night);
        if (n == null) return;
        JSONArray a = n.optJSONArray("clips");
        if (a == null) { a = new JSONArray(); n.put("clips", a); }
        double audio = 0;
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optBoolean("audio")) audio += a.getJSONObject(i).optDouble("dur");
        long t = nightStart + k.startMs();
        String id = "c" + t;
        boolean keep = audio + k.seconds() <= MAX_AUDIO_S;
        if (keep) {
            File tmp = new File(dir(c, night), id + ".wav.tmp");
            try (RandomAccessFile w = new RandomAccessFile(tmp, "rw")) { w.setLength(0); w.write(wav(k.pcm)); }
            if (!tmp.renameTo(new File(dir(c, night), id + ".wav"))) { tmp.delete(); keep = false; }
        }
        a.put(new JSONObject().put("id", id).put("t", t).put("dur", Math.round(k.seconds() * 10) / 10.0).put("ratio", Math.round(k.bandRatio * 100) / 100.0)
                .put("peak", Math.round(k.peakDb)).put("guess", SleepDetect.speechLike(k) ? "praten" : "geluid").put("audio", keep).put("done", false).put("full", !keep));
        n.put("heard", a.length());
        write(c, n);
    }

    static synchronized void end(Context c, String night, long end, String reason) {
        try { JSONObject n = night(c, night); if (n == null) return; n.put("end", end).put("reason", reason == null ? "" : reason); write(c, n); } catch (Exception ignored) { }
    }

    static File clipFile(Context c, String night, String clip) { return safeId(night) && safeClip(clip) ? new File(dir(c, night), clip + ".wav") : null; }

    /** Alle nachten, nieuwste eerst (zonder de stukjes zelf). */
    static synchronized JSONArray nights(Context c) {
        JSONArray out = new JSONArray();
        File[] ds = root(c).listFiles();
        if (ds == null) return out;
        Arrays.sort(ds, (x, y) -> y.getName().compareTo(x.getName()));
        for (File d : ds) {
            JSONObject n = night(c, d.getName());
            if (n == null) continue;
            int talk = 0, noise = 0, open = 0;
            JSONArray a = n.optJSONArray("clips");
            String first = "";
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject k = a.optJSONObject(i);
                if (k == null) continue;
                String kind = k.optBoolean("done") ? k.optString("kind") : "";
                if ("praten".equals(kind)) { talk++; if (first.isEmpty()) first = k.optString("text"); }
                else if ("geluid".equals(kind)) noise++;
                else open++;
            }
            try {
                out.put(new JSONObject().put("id", n.optString("id")).put("start", n.optLong("start")).put("end", n.optLong("end")).put("reason", n.optString("reason"))
                        .put("talk", talk).put("noise", noise).put("open", open).put("first", first.length() > 120 ? first.substring(0, 120) + "…" : first));
            } catch (Exception ignored) { }
        }
        return out;
    }

    static synchronized void deleteNight(Context c, String id) {
        if (!safeId(id)) return;
        File d = dir(c, id);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    static synchronized void deleteClip(Context c, String night, String clip) {
        try {
            JSONObject n = night(c, night);
            if (n == null || !safeClip(clip)) return;
            JSONArray a = n.optJSONArray("clips"), keep = new JSONArray();
            if (a != null) for (int i = 0; i < a.length(); i++) if (!clip.equals(a.getJSONObject(i).optString("id"))) keep.put(a.getJSONObject(i));
            n.put("clips", keep);
            write(c, n);
            File f = clipFile(c, night, clip);
            if (f != null) f.delete();
        } catch (Exception ignored) { }
    }

    /** Oude nachten weg (de nacht die nu loopt nooit). */
    static void cleanup(Context c, String running) {
        long limit = System.currentTimeMillis() - keepDays(c) * 86_400_000L;
        File[] ds = root(c).listFiles();
        if (ds != null) for (File d : ds) {
            String id = d.getName();
            if (!safeId(id) || id.equals(running)) continue;
            try { if (Long.parseLong(id) < limit) deleteNight(c, id); } catch (Exception ignored) { }
        }
    }

    // ---------- uitschrijven ----------

    static volatile boolean stopTx;
    interface Step { void step(int done, int total); }

    /** Nog uit te schrijven stukjes van een nacht. */
    static synchronized int open(Context c, String night) {
        JSONObject n = night(c, night);
        int o = 0;
        JSONArray a = n == null ? null : n.optJSONArray("clips");
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject k = a.optJSONObject(i); if (k != null && !k.optBoolean("done") && k.optBoolean("audio")) o++; }
        return o;
    }

    /** Alle nog niet uitgeschreven stukjes van een nacht uitschrijven (op een achtergrondthread aanroepen). */
    static void transcribeNight(Context c, String night, Step step) throws Exception {
        Transcribe.Model m = Transcribe.activeModel(c);
        if (m == null) throw new Exception("Download eerst een spraakmodel (bij Gesprekken uitschrijven)");
        List<String> todo = new ArrayList<>();
        synchronized (SleepTalk.class) {
            JSONObject n = night(c, night);
            JSONArray a = n == null ? null : n.optJSONArray("clips");
            if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject k = a.getJSONObject(i); if (!k.optBoolean("done") && k.optBoolean("audio")) todo.add(k.optString("id")); }
        }
        int done = 0;
        for (String clip : todo) {
            if (stopTx) return;
            if (step != null) step.step(done, todo.size());
            File f = clipFile(c, night, clip);
            String text = "";
            if (f != null && f.isFile()) {
                JSONObject out = Transcribe.whisper(c, f, m, (ph, pct) -> { }, true);
                JSONArray tr = out.optJSONArray("transcription");
                List<String> parts = new ArrayList<>();
                if (tr != null) for (int i = 0; i < tr.length(); i++) parts.add(tr.getJSONObject(i).optString("text"));
                text = cleanText(parts);
            }
            synchronized (SleepTalk.class) {
                JSONObject n = night(c, night);
                JSONArray a = n == null ? null : n.optJSONArray("clips");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject k = a.getJSONObject(i);
                    if (!clip.equals(k.optString("id"))) continue;
                    String kind = kind(text);
                    k.put("text", text).put("kind", kind).put("done", true);
                    // Geen praten: het geluid hoeft niet te blijven (tenzij je dat wilt)
                    if ("geluid".equals(kind) && !keepNoise(c) && f != null) { f.delete(); k.put("audio", false); }
                }
                if (n != null) write(c, n);
            }
            done++;
        }
        if (step != null) step.step(done, todo.size());
    }
}
