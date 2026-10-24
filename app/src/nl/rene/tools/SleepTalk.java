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

    /** Hooguit zoveel seconden geluid per nacht bewaren (AAC: ±20 MB; als WAV ±170 MB); daarna alleen nog tellen. */
    static final int MAX_AUDIO_S = 90 * 60;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sleeptalk", Context.MODE_PRIVATE); }

    static int sens(Context c) { int s = prefs(c).getInt("sens", 2); return s < 1 || s > 3 ? 2 : s; }
    static String stopAt(Context c) { String s = prefs(c).getString("stopAt", "07:30"); return validTime(s) ? s : ""; }
    static boolean transcribe(Context c) { return prefs(c).getBoolean("transcribe", true); }
    static boolean keepNoise(Context c) { return prefs(c).getBoolean("keepNoise", false); }
    static int keepDays(Context c) { int d = prefs(c).getInt("keep", 14); return d == 7 || d == 30 ? d : 14; }
    static int minBattery(Context c) { return 15; }
    /** Herinnering op bedtijd ("HH:mm", of "" = uit). */
    static String remindAt(Context c) { String s = prefs(c).getString("remindAt", ""); return validTime(s) ? s : ""; }

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

    /** Gaat omhoog bij elke wijziging: het overzicht van de nachten hoeft dan alleen opnieuw als er iets veranderde. */
    private static volatile int version;
    private static JSONArray nightsCache; private static int nightsVersion = -1;

    private static void write(Context c, JSONObject n) throws Exception { Contacts.writeText(nightFile(c, n.optString("id")), n.toString(), false); version++; }

    /** Een nieuwe nacht; geeft het id. bat = accu bij het begin (-1 = onbekend), plugged = aan de lader. */
    static synchronized String begin(Context c, long start, int sens, int bat, boolean plugged) throws Exception {
        String id = String.valueOf(start);
        dir(c, id).mkdirs();
        write(c, new JSONObject().put("id", id).put("start", start).put("end", 0).put("sens", sens).put("clips", new JSONArray()).put("heard", 0)
                .put("batStart", bat).put("charged", plugged));
        return id;
    }

    /** Hoeveel seconden geluid er al bewaard is. */
    private static synchronized double audioSeconds(Context c, String night) {
        JSONObject n = night(c, night);
        JSONArray a = n == null ? null : n.optJSONArray("clips");
        double audio = 0;
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject k = a.optJSONObject(i); if (k != null && k.optBoolean("audio")) audio += k.optDouble("dur"); }
        return audio;
    }

    /** Een stukje bewaren: het geluid als AAC (.m4a; lukt dat niet, als WAV), tenzij de nacht al vol zit. Het
     *  omzetten gebeurt buiten het slot, zodat het scherm intussen gewoon kan kijken. */
    static void addClip(Context c, String night, long nightStart, SleepDetect.Clip k) throws Exception {
        if (night(c, night) == null) return;
        long t = nightStart + k.startMs();
        String id = "c" + t;
        boolean keep = audioSeconds(c, night) + k.seconds() <= MAX_AUDIO_S;
        // Bijna geen ruimte meer op de telefoon: alleen het tijdstip
        try { if (new android.os.StatFs(c.getFilesDir().getPath()).getAvailableBytes() < 300L * 1024 * 1024) keep = false; } catch (Throwable ignored) { }
        if (keep) keep = saveAudio(dir(c, night), id, k.pcm);
        synchronized (SleepTalk.class) {
            JSONObject n = night(c, night);
            if (n == null) return;
            JSONArray a = n.optJSONArray("clips");
            if (a == null) { a = new JSONArray(); n.put("clips", a); }
            a.put(new JSONObject().put("id", id).put("t", t).put("dur", Math.round(k.seconds() * 10) / 10.0).put("ratio", Math.round(k.bandRatio * 100) / 100.0)
                .put("peak", Math.round(k.peakDb)).put("guess", SleepDetect.speechLike(k) ? "praten" : "geluid").put("audio", keep).put("done", false).put("full", !keep));
            n.put("heard", a.length());
            write(c, n);
        }
    }

    /** Hoe vaak AAC (achter elkaar) mislukte; na twee keer gaat het de rest van de tijd meteen als WAV. */
    private static volatile int aacFails;

    /** Eerst AAC, anders WAV; altijd via een tijdelijk bestand. */
    private static boolean saveAudio(File dir, String id, short[] pcm) {
        File tmp = new File(dir, id + ".m4a.tmp");
        if (aacFails < 2) {
            try {
                AacWriter.write(pcm, SleepDetect.RATE, tmp);
                if (tmp.length() > 200 && tmp.renameTo(new File(dir, id + ".m4a"))) { aacFails = 0; return true; }
                aacFails++;
            } catch (Throwable e) { aacFails++; /* geen AAC op deze telefoon: WAV */ }
        }
        tmp.delete();
        File w = new File(dir, id + ".wav.tmp");
        try (RandomAccessFile f = new RandomAccessFile(w, "rw")) { f.setLength(0); f.write(wav(pcm)); }
        catch (Throwable e) { w.delete(); return false; }
        if (w.renameTo(new File(dir, id + ".wav"))) return true;
        w.delete();
        return false;
    }

    /** bat = accu aan het eind (-1 = onbekend), charged = de telefoon lag (ook) aan de lader. */
    static synchronized void end(Context c, String night, long end, String reason, int bat, boolean charged) {
        try {
            JSONObject n = night(c, night); if (n == null) return;
            n.put("end", end).put("reason", reason == null ? "" : reason).put("batEnd", bat).put("charged", charged || n.optBoolean("charged"));
            write(c, n);
        } catch (Exception ignored) { }
    }
    static void end(Context c, String night, long end, String reason) { end(c, night, end, reason, -1, false); }

    /** Het geluid van een stukje (.m4a, of .wav van een oudere versie), of null. */
    static File clipFile(Context c, String night, String clip) {
        if (!safeId(night) || !safeClip(clip)) return null;
        File m = new File(dir(c, night), clip + ".m4a");
        return m.isFile() ? m : new File(dir(c, night), clip + ".wav");
    }

    /** Accuverbruik per uur zonder lader, of -1. Zuiver (getest). */
    static double batPerHour(long start, long end, int batStart, int batEnd, boolean charged) {
        if (charged || batStart < 0 || batEnd < 0 || end <= start || batEnd > batStart) return -1;
        double h = (end - start) / 3_600_000.0;
        return h < 0.5 ? -1 : (batStart - batEnd) / h;
    }

    /** Alle nachten, nieuwste eerst (zonder de stukjes zelf). */
    static synchronized JSONArray nights(Context c) {
        if (nightsCache != null && nightsVersion == version) return nightsCache;
        JSONArray out = nightsList(c);
        nightsCache = out; nightsVersion = version;
        return out;
    }

    private static JSONArray nightsList(Context c) {
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
                        .put("talk", talk).put("noise", noise).put("open", open).put("first", first.length() > 120 ? first.substring(0, 120) + "…" : first)
                        .put("batStart", n.optInt("batStart", -1)).put("batEnd", n.optInt("batEnd", -1)).put("charged", n.optBoolean("charged")));
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
        version++;
    }

    static synchronized void deleteClip(Context c, String night, String clip) {
        try {
            JSONObject n = night(c, night);
            if (n == null || !safeClip(clip)) return;
            JSONArray a = n.optJSONArray("clips"), keep = new JSONArray();
            if (a != null) for (int i = 0; i < a.length(); i++) if (!clip.equals(a.getJSONObject(i).optString("id"))) keep.put(a.getJSONObject(i));
            n.put("clips", keep);
            write(c, n);
            new File(dir(c, night), clip + ".m4a").delete();
            new File(dir(c, night), clip + ".wav").delete();
        } catch (Exception ignored) { }
    }

    /** Zoeken in wat je zei (voor Zoeken): [{night, clip, t, text}], nieuwste eerst. */
    static synchronized JSONArray search(Context c, String ql) {
        JSONArray out = new JSONArray();
        File[] ds = root(c).listFiles();
        if (ds == null || ql == null || ql.isEmpty()) return out;
        Arrays.sort(ds, (x, y) -> y.getName().compareTo(x.getName()));
        for (File d : ds) {
            JSONObject n = night(c, d.getName());
            JSONArray a = n == null ? null : n.optJSONArray("clips");
            if (a == null) continue;
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject k = a.optJSONObject(i);
                if (k == null || !"praten".equals(k.optString("kind"))) continue;
                String text = k.optString("text");
                if (!text.toLowerCase(Locale.ROOT).contains(ql)) continue;
                try { out.put(new JSONObject().put("night", n.optString("id")).put("clip", k.optString("id")).put("t", k.optLong("t")).put("text", text)); } catch (Exception ignored) { }
                if (out.length() >= 200) return out;
            }
        }
        return out;
    }

    /** Wat je zei, per nacht (voor slim zoeken): {id, start, regels, tijden}. */
    static synchronized List<Object[]> talkByNight(Context c) {
        List<Object[]> out = new ArrayList<>();
        File[] ds = root(c).listFiles();
        if (ds == null) return out;
        for (File d : ds) {
            JSONObject n = night(c, d.getName());
            JSONArray a = n == null ? null : n.optJSONArray("clips");
            if (a == null) continue;
            List<String> lines = new ArrayList<>(); List<Long> times = new ArrayList<>();
            java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT);
            for (int i = 0; i < a.length(); i++) {
                JSONObject k = a.optJSONObject(i);
                if (k == null || !"praten".equals(k.optString("kind")) || k.optString("text").isEmpty()) continue;
                lines.add("[" + hm.format(new java.util.Date(k.optLong("t"))) + "] " + k.optString("text"));
                times.add(k.optLong("t"));
            }
            if (!lines.isEmpty()) out.add(new Object[]{n.optString("id"), n.optLong("start"), lines, times});
        }
        return out;
    }

    /** Vingerafdruk voor slim zoeken: verandert als er iets in een nacht verandert. */
    static String stamp(Context c) {
        long t = 0; int n = 0;
        File[] ds = root(c).listFiles();
        if (ds != null) for (File d : ds) { File f = new File(d, "night.json"); if (f.isFile()) { t = Math.max(t, f.lastModified()); n++; } }
        return n + "/" + t;
    }

    /** Oude nachten weg (de nacht die nu loopt nooit); een nacht die nooit netjes eindigde (de app werd gestopt)
     *  heet "Onderbroken"; halve bestanden weg. */
    static void cleanup(Context c, String running) {
        long limit = System.currentTimeMillis() - keepDays(c) * 86_400_000L;
        File[] ds = root(c).listFiles();
        if (ds != null) for (File d : ds) {
            String id = d.getName();
            if (!safeId(id) || id.equals(running)) continue;
            try {
                long start = Long.parseLong(id);
                if (start < limit) { deleteNight(c, id); continue; }
                if (System.currentTimeMillis() - start < 120_000L) continue; // net begonnen (kan nog niet als "lopend" bekend zijn)
                synchronized (SleepTalk.class) {
                    File[] fs = d.listFiles();
                    if (fs != null) for (File f : fs) if (f.getName().endsWith(".tmp")) f.delete();
                    JSONObject n = night(c, id);
                    if (n != null && n.optLong("end") == 0) {
                        long last = n.optLong("start");
                        JSONArray a = n.optJSONArray("clips");
                        if (a != null) for (int i = 0; i < a.length(); i++) last = Math.max(last, a.getJSONObject(i).optLong("t") + (long) (a.getJSONObject(i).optDouble("dur") * 1000));
                        n.put("end", last).put("reason", "Onderbroken (de app werd gestopt)");
                        write(c, n);
                    }
                }
            } catch (Exception ignored) { }
        }
    }

    private static long lastCleanup;
    /** Hooguit eens per uur (bijv. bij het openen van het scherm), zodat oude nachten ook zonder luisteren weggaan. */
    static void cleanupSometimes(Context c, String running) {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < 3_600_000L) return;
        lastCleanup = now;
        cleanup(c, running);
    }

    // ---------- uitschrijven ----------

    static volatile boolean stopTx;
    private static volatile Thread txThread;
    /** Uitschrijven stoppen, ook midden in een stukje (alleen het eigen whisper-proces). */
    static void stopNow() { stopTx = true; Transcribe.stopOwnedBy(txThread); }
    interface Step { void step(int done, int total); }
    /** Gestopt midden in een stukje (via de voortgang van whisper: het proces stopt dan meteen). */
    static final class Stopped extends RuntimeException { Stopped() { super("Gestopt"); } }

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
        txThread = Thread.currentThread();
        try {
        for (String clip : todo) {
            if (stopTx) return;
            if (step != null) step.step(done, todo.size());
            File f = clipFile(c, night, clip);
            String text = "";
            boolean broken = false;
            if (f != null && f.isFile()) {
                JSONObject out = null;
                // whisper wil WAV: een .m4a eerst (tijdelijk) omzetten
                File wav = f;
                try {
                    if (f.getName().endsWith(".m4a")) {
                        wav = new File(c.getCacheDir(), "slaappraat-" + clip + ".wav");
                        try { Transcribe.toWav(f, wav, 0, (ph, pct) -> { if (stopTx) throw new Stopped(); }, true); }
                        catch (Stopped s) { return; }
                        catch (Exception e) {
                            if (stopTx) return;
                            // Onleesbaar stukje: overslaan (en niet elke keer opnieuw proberen), de rest gaat door
                            App.log(c, "SLAAP", "stukje " + clip + " onleesbaar: " + e);
                            broken = true;
                        }
                    }
                    if (!broken) {
                        try { out = Transcribe.whisper(c, wav, m, (ph, pct) -> { if (stopTx) throw new Stopped(); }, true); }
                        catch (Stopped s) { return; }
                        catch (Exception e) { if (stopTx) return; throw e; } // proces gestopt: geen fout
                    }
                } finally { if (wav != f) wav.delete(); }
                if (out != null) {
                    JSONArray tr = out.optJSONArray("transcription");
                    List<String> parts = new ArrayList<>();
                    if (tr != null) for (int i = 0; i < tr.length(); i++) parts.add(tr.getJSONObject(i).optString("text"));
                    text = cleanText(parts);
                }
            }
            synchronized (SleepTalk.class) {
                JSONObject n = night(c, night);
                JSONArray a = n == null ? null : n.optJSONArray("clips");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject k = a.getJSONObject(i);
                    if (!clip.equals(k.optString("id"))) continue;
                    String kind = broken ? "onleesbaar" : kind(text);
                    k.put("text", text).put("kind", kind).put("done", true);
                    // Geen praten: het geluid hoeft niet te blijven (tenzij je dat wilt)
                    if ("geluid".equals(kind) && !keepNoise(c) && f != null) { f.delete(); new File(f.getParent(), clip + ".wav").delete(); k.put("audio", false); }
                }
                if (n != null) write(c, n);
            }
            done++;
        }
        } finally { txThread = null; }
        if (step != null) step.step(done, todo.size());
    }
}
