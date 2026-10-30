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
 * wilt bewaren. Met Meteen uitschrijven gebeurt dat al tijdens de nacht, stukje voor stukje (SleepService), zodat
 * geluiden meteen weg kunnen en het maximum niet volloopt. Het zuivere rekenwerk wordt getest in SleepTalkTest.
 */
final class SleepTalk {

    private SleepTalk() { }

    /**
     * Standaard hooguit zoveel uur geluid per nacht bewaren (instelbaar: 1, 2, 4, 8 uur of onbeperkt); alleen wat nu nog
     * bewaard is telt. 8 uur AAC (32 kbit/s) is ±115 MB: genoeg voor een hele nacht vol snurken, ook als je geluiden
     * bewaart. Is het vol, dan beslist "Wat als het vol is" (makeRoom).
     */
    static final int MAX_AUDIO_H = 8;
    /** Standaard blijft er altijd zoveel MB vrij op de telefoon (instelbaar); anders alleen het tijdstip bewaren. */
    static final int MIN_FREE_MB = 500;
    /** Meteen uitschrijven zonder lader: standaard alleen vanaf dit accupercentage (instelbaar). */
    static final int DIRECT_MIN_BAT = 40;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sleeptalk", Context.MODE_PRIVATE); }

    static int sens(Context c) { int s = prefs(c).getInt("sens", 2); return s < 1 || s > 3 ? 2 : s; }
    static String stopAt(Context c) { String s = prefs(c).getString("stopAt", "07:30"); return validTime(s) ? s : ""; }
    static boolean transcribe(Context c) { return prefs(c).getBoolean("transcribe", true); }
    static boolean keepNoise(Context c) { return prefs(c).getBoolean("keepNoise", false); }
    /** Meteen uitschrijven (standaard aan): tijdens het luisteren al, stukje voor stukje (alleen als Daarna uitschrijven aan staat). */
    static boolean direct(Context c) { return prefs(c).getBoolean("direct", true); }
    /** Onopvallend luisteren (standaard aan): de verplichte melding zo stil mogelijk, en 's ochtends geen melding. */
    static boolean quiet(Context c) { return prefs(c).getBoolean("quiet", true); }
    static int keepDays(Context c) { int d = prefs(c).getInt("keep", 14); return d == 7 || d == 30 ? d : 14; }
    /** Stoppen zonder lader bij dit accupercentage (standaard 15). */
    static int minBattery(Context c) { return pick(c, "stopBat", 15, 5, 10, 15, 20, 30); }

    // ---------- meer instellingen (alles met een vaste lijst keuzes; iets anders = standaard) ----------

    /** Een getal uit de instellingen, alleen als het een van de toegestane waarden is. */
    static int pick(Context c, String key, int def, int... ok) {
        int v = prefs(c).getInt(key, def);
        for (int o : ok) if (o == v) return v;
        return def;
    }
    static boolean allowed(int v, int... ok) { for (int o : ok) if (o == v) return true; return false; }
    static final int[] DIRECT_BATS = {20, 30, 40, 50, 60, 80}, STOP_BATS = {5, 10, 15, 20, 30}, MAX_HOURS = {1, 2, 4, 8, 0},
            MIN_FREES = {250, 500, 1000, 2000}, CLIP_MAXES = {30, 60, 120, 300}, HANGS = {1000, 1500, 2000, 3000}, PRES = {1, 2, 3, 5};
    /** Meteen uitschrijven wanneer: "altijd", "lader" (alleen aan de lader) of "accu" (aan de lader of boven de drempel). */
    static String directWhen(Context c) { String w = prefs(c).getString("directWhen", "accu"); return "altijd".equals(w) || "lader".equals(w) ? w : "accu"; }
    static int directBat(Context c) { return pick(c, "directBat", DIRECT_MIN_BAT, DIRECT_BATS); }
    /** Hooguit zoveel uur geluid per nacht; 0 = onbeperkt (alleen de vrije ruimte telt). */
    static int maxAudioH(Context c) { return pick(c, "maxAudio", MAX_AUDIO_H, MAX_HOURS); }
    static double maxAudioS(Context c) { int h = maxAudioH(c); return h == 0 ? Double.MAX_VALUE : h * 3600.0; }
    static long minFree(Context c) { return pick(c, "minFree", MIN_FREE_MB, MIN_FREES) * 1024L * 1024; }
    /** Wat als het vol is: "stop" (niets meer bewaren), "geluid" (oudste geluid weg voor nieuw gepraat) of "oudste" (oudste weg). */
    static String whenFull(Context c) { String w = prefs(c).getString("whenFull", "geluid"); return "stop".equals(w) || "oudste".equals(w) ? w : "geluid"; }
    static int clipMaxS(Context c) { return pick(c, "clipMax", 60, CLIP_MAXES); }
    static int hangMs(Context c) { return pick(c, "hang", 1500, HANGS); }
    static int preS(Context c) { return pick(c, "pre", 2, PRES); }
    /** Bewaren als WAV (groter, zonder omzetten) in plaats van AAC. */
    static boolean wavFormat(Context c) { return "wav".equals(prefs(c).getString("format", "aac")); }
    /** Uitschrijfmodel voor Slaappraat: "" = zoals Gesprekken uitschrijven, of een vast model ("base", "small"). */
    static String txModelId(Context c) { String m = prefs(c).getString("txModel", ""); return Transcribe.model(m) != null ? m : ""; }
    /** Het model dat Slaappraat gebruikt: het gekozen model als het er is, anders dat van Gesprekken uitschrijven. */
    static Transcribe.Model model(Context c) {
        Transcribe.Model m = Transcribe.model(txModelId(c));
        if (m != null && Transcribe.modelFile(c, m).isFile()) return m;
        return Transcribe.activeModel(c);
    }
    /** De opname-instellingen voor een nieuwe nacht. */
    static SleepDetect.Params detectParams(Context c) { return SleepDetect.Params.of(preS(c), hangMs(c), clipMaxS(c)); }
    /** Alle sleutels van Meer instellingen (voor Terug naar standaard). */
    static final String[] ADVANCED = {"directWhen", "directBat", "stopBat", "maxAudio", "minFree", "whenFull", "clipMax", "hang", "pre", "format", "txModel"};
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

    /** Praten, geluid of onleesbaar: wat whisper van een stukje maakte (ook bij meteen uitschrijven precies zo). */
    static String kindOf(String text, boolean broken) { return broken ? "onleesbaar" : kind(text); }

    /** Moet het geluid na het uitschrijven weg? Alleen geluid zonder woorden, en alleen als je dat niet wilt bewaren. */
    static boolean dropAudio(String kind, boolean keepNoise) { return "geluid".equals(kind) && !keepNoise; }

    /** Een stukje zoals het budget het ziet. */
    static final class Slot {
        final String id, kind, guess; final double dur, ratio; final boolean audio, done; final long t;
        Slot(String id, long t, double dur, boolean audio, boolean done, String kind, String guess, double ratio) {
            this.id = id; this.t = t; this.dur = dur; this.audio = audio; this.done = done; this.kind = kind == null ? "" : kind; this.guess = guess == null ? "" : guess; this.ratio = ratio;
        }
    }

    /** Hoeveel seconden geluid er nog bewaard is (alleen stukjes met geluid tellen). */
    static double keptSeconds(List<Slot> clips) { double s = 0; for (Slot k : clips) if (k.audio) s += k.dur; return s; }

    /**
     * Past een nieuw stukje van need seconden nog in het budget (max)? null = nee; anders de stukjes waarvan het geluid
     * eerst weg moet (leeg zolang het budget niet echt vol is). mode = Wat als het vol is:
     *  - "stop": niets weg; het nieuwe stukje krijgt geen geluid.
     *  - "geluid" (standaard): alleen een stukje dat op praten lijkt (speech) maakt plaats, en alleen ten koste van
     *    geluid: eerst wat al uitgeschreven is en geen woorden had (of onleesbaar was), dan wat nog niet uitgeschreven is
     *    en op geluid leek, steeds het oudste eerst. Praten (gegokt of uitgeschreven) gaat nooit weg.
     *  - "oudste": elk nieuw stukje gaat voor; het geluid van de oudste stukjes gaat weg (de uitgeschreven tekst blijft).
     * busy = stukjes die nu uitgeschreven worden: alleen als het niet anders kan.
     */
    static List<String> makeRoom(List<Slot> clips, double need, boolean speech, double max, String mode, java.util.Set<String> busy) {
        List<String> out = new ArrayList<>();
        double over = keptSeconds(clips) + need - max;
        if (over <= 1e-9) return out;
        boolean oldest = "oudste".equals(mode);
        if ("stop".equals(mode) || (!oldest && !speech)) return null;
        List<Slot> cand = new ArrayList<>();
        for (Slot k : clips) {
            if (!k.audio) continue;
            if (oldest || (k.done ? ("geluid".equals(k.kind) || "onleesbaar".equals(k.kind)) : !"praten".equals(k.guess))) cand.add(k);
        }
        final java.util.Set<String> b = busy == null ? java.util.Collections.<String>emptySet() : busy;
        java.util.Collections.sort(cand, (x, y) -> {
            if (b.contains(x.id) != b.contains(y.id)) return b.contains(x.id) ? 1 : -1;
            if (!oldest && x.done != y.done) return x.done ? -1 : 1;
            return Long.compare(x.t, y.t);
        });
        double freed = 0;
        for (Slot k : cand) {
            if (freed >= over - 1e-9) break;
            out.add(k.id); freed += k.dur;
        }
        return freed >= over - 1e-9 ? out : null;
    }

    /** Waarom meteen uitschrijven nu wacht, of "" als het mag. Nooit tijdens een gesprek of als de telefoon warm is.
     *  when "altijd": verder altijd; "lader": alleen aan de lader; "accu" (standaard): aan de lader, of zonder lader vanaf
     *  minBat% en niet met batterijbesparing aan. bat -1 = onbekend. */
    static String directWait(String when, int minBat, boolean charging, int bat, boolean inCall, boolean hot, boolean saver) {
        if (inCall) return "tijdens het gesprek";
        if (hot) return "de telefoon is warm";
        if (charging || "altijd".equals(when)) return "";
        if ("lader".equals(when)) return "niet aan de lader";
        if (bat < 0) return "accu onbekend en niet aan de lader";
        if (bat < minBat) return "accu onder " + minBat + "% en niet aan de lader";
        if (saver) return "batterijbesparing staat aan";
        return "";
    }

    /** "3 keer gepraat · 12 geluiden" (voor de melding en het scherm). */
    static String liveLine(int talk, int noise) {
        String s = talk == 0 ? "Nog niets gezegd" : talk == 1 ? "1 keer gepraat" : talk + " keer gepraat";
        if (noise > 0) s += " · " + noise + (noise == 1 ? " geluid" : " geluiden");
        return s;
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
    static synchronized String begin(Context c, long start, int sens, SleepDetect.Params p, int bat, boolean plugged) throws Exception {
        String id = String.valueOf(start);
        dir(c, id).mkdirs();
        // De opname-instellingen van deze nacht (zo beschrijft een nacht zichzelf, ook als je ze later verandert)
        JSONObject cfg = new JSONObject().put("pre", p.preFrames * 20).put("hang", p.hangFrames * 20).put("max", p.maxFrames / 50);
        write(c, new JSONObject().put("id", id).put("start", start).put("end", 0).put("sens", sens).put("cfg", cfg).put("clips", new JSONArray()).put("heard", 0)
                .put("batStart", bat).put("charged", plugged));
        return id;
    }

    /** Map van een nacht (voor terugzetten); null bij een vreemd id. */
    static File nightDir(Context c, String id) { return safeId(id) ? dir(c, id) : null; }

    /** Staat deze nacht al op de telefoon? */
    static synchronized boolean exists(Context c, String id) { return safeId(id) && nightFile(c, id).isFile(); }

    /** Alle nachten in hun geheel (met de stukjes), nieuwste eerst: voor Alles back-uppen. */
    static synchronized JSONArray allNights(Context c) {
        JSONArray out = new JSONArray();
        File[] ds = root(c).listFiles();
        if (ds == null) return out;
        Arrays.sort(ds, (x, y) -> y.getName().compareTo(x.getName()));
        for (File d : ds) { JSONObject n = night(c, d.getName()); if (n != null) out.put(n); }
        return out;
    }

    /** Een teruggezette nacht; nooit over een nacht heen die er al is. Het overzicht ververst vanzelf (version). */
    static synchronized boolean addRestored(Context c, JSONObject n) throws Exception {
        String id = n.optString("id");
        if (!safeId(id) || nightFile(c, id).isFile()) return false;
        dir(c, id).mkdirs();
        write(c, n);
        return true;
    }

    /** De stukjes van een nacht zoals het budget ze ziet. */
    private static List<Slot> slots(JSONArray a) {
        List<Slot> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject k = a.optJSONObject(i);
            if (k != null) out.add(new Slot(k.optString("id"), k.optLong("t"), k.optDouble("dur", 0), k.optBoolean("audio"), k.optBoolean("done"), k.optString("kind"), k.optString("guess"), k.optDouble("ratio", 0)));
        }
        return out;
    }

    /** Vrije ruimte op de telefoon, of -1 als onbekend. */
    private static long freeBytes(Context c) {
        try { return new android.os.StatFs(c.getFilesDir().getPath()).getAvailableBytes(); } catch (Throwable t) { return -1; }
    }

    /** Stukjes die nu uitgeschreven worden (niet twee keer tegelijk, en liever niet wegdoen om plaats te maken). */
    private static final java.util.Set<String> busy = new java.util.HashSet<>();

    /** Een stukje bewaren: het geluid als AAC (.m4a; lukt dat niet, als WAV), als het nog in het budget past (eventueel
     *  door geluid weg te doen voor iets dat op praten lijkt). Het omzetten gebeurt buiten het slot, zodat het scherm en
     *  het meteen uitschrijven intussen gewoon doorgaan; daarna wordt het budget onder het slot opnieuw bekeken. */
    static void addClip(Context c, String night, long nightStart, SleepDetect.Clip k) throws Exception {
        JSONObject n0 = night(c, night);
        if (n0 == null) return;
        long t = nightStart + k.startMs();
        String id = "c" + t;
        boolean speech = SleepDetect.speechLike(k);
        double sec = k.seconds();
        String why = "";
        List<String> plan;
        double max = maxAudioS(c);
        String mode = whenFull(c);
        synchronized (SleepTalk.class) { plan = makeRoom(slots(n0.optJSONArray("clips")), sec, speech, max, mode, busy); }
        boolean keep = plan != null;
        if (!keep) why = "max";
        // Bijna geen ruimte meer op de telefoon: alleen het tijdstip
        long free = freeBytes(c);
        if (keep && free >= 0 && free - (long) (sec * SleepDetect.RATE * 2) < minFree(c)) { keep = false; why = "ruimte"; }
        if (keep) { keep = saveAudio(dir(c, night), id, k.pcm, wavFormat(c)); if (!keep) why = "opslaan"; }
        synchronized (SleepTalk.class) {
            JSONObject n = night(c, night);
            if (n == null) { if (keep) removeAudio(c, night, id); return; }
            JSONArray a = n.optJSONArray("clips");
            if (a == null) { a = new JSONArray(); n.put("clips", a); }
            // Opnieuw, met wat er intussen veranderde (een stukje uitgeschreven, of gewist)
            List<String> drop = keep ? makeRoom(slots(a), sec, speech, max, mode, busy) : null;
            if (keep && drop == null) { removeAudio(c, night, id); keep = false; why = "max"; }
            if (drop != null) for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                if (!drop.contains(x.optString("id"))) continue;
                removeAudio(c, night, x.optString("id"));
                x.put("audio", false);
                if (!x.optBoolean("done")) x.put("full", true).put("why", "plaats");
            }
            JSONObject o = new JSONObject().put("id", id).put("t", t).put("dur", Math.round(sec * 10) / 10.0).put("ratio", Math.round(k.bandRatio * 100) / 100.0)
                .put("peak", Math.round(k.peakDb)).put("guess", speech ? "praten" : "geluid").put("audio", keep).put("done", false).put("full", !keep);
            if (!keep) o.put("why", why);
            a.put(o);
            n.put("heard", a.length());
            write(c, n);
        }
    }

    private static void removeAudio(Context c, String night, String clip) {
        new File(dir(c, night), clip + ".m4a").delete();
        new File(dir(c, night), clip + ".wav").delete();
    }

    /** Hoe vaak AAC (achter elkaar) mislukte; na twee keer gaat het de rest van de tijd meteen als WAV. */
    private static volatile int aacFails;

    /** Eerst AAC (tenzij je WAV kiest), anders WAV; altijd via een tijdelijk bestand. */
    private static boolean saveAudio(File dir, String id, short[] pcm, boolean wavOnly) {
        File tmp = new File(dir, id + ".m4a.tmp");
        if (!wavOnly && aacFails < 2) {
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
            long bytes = 0;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) bytes += f.length();
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
                        .put("batStart", n.optInt("batStart", -1)).put("batEnd", n.optInt("batEnd", -1)).put("charged", n.optBoolean("charged")).put("bytes", bytes));
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
                if (start < limit) { if (!AiSleepBackup.applying) deleteNight(c, id); continue; } // niet tijdens terugzetten
                if (System.currentTimeMillis() - start < 120_000L) continue; // net begonnen (kan nog niet als "lopend" bekend zijn)
                synchronized (SleepTalk.class) {
                    File[] fs = d.listFiles();
                    if (fs != null) for (File f : fs) {
                        if (f.getName().endsWith(".tmp")) f.delete();
                        // .rt-part: afgebroken terugzetten; niet zolang er teruggezet wordt, en pas na 10 minuten
                        else if (f.getName().endsWith(".rt-part") && !AiSleepBackup.applying && System.currentTimeMillis() - f.lastModified() > 600_000L) f.delete();
                    }
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

    /** Het oudste stukje dat nog uitgeschreven moet worden (en niet al bezig is), of null. */
    static synchronized String nextOpen(Context c, String night) {
        JSONObject n = night(c, night);
        JSONArray a = n == null ? null : n.optJSONArray("clips");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject k = a.optJSONObject(i);
            if (k != null && !k.optBoolean("done") && k.optBoolean("audio") && !busy.contains(k.optString("id"))) return k.optString("id");
        }
        return null;
    }

    /** Hoe het met een nacht staat terwijl er geluisterd wordt: gepraat, geluiden, nog uit te schrijven, het laatste wat je zei. */
    static final class Live {
        int talk, noise, open; String text = ""; long at;
    }

    static synchronized Live live(Context c, String night) {
        Live l = new Live();
        JSONObject n = night(c, night);
        JSONArray a = n == null ? null : n.optJSONArray("clips");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject k = a.optJSONObject(i);
            if (k == null) continue;
            if (!k.optBoolean("done")) { if (k.optBoolean("audio")) l.open++; continue; }
            String kind = k.optString("kind");
            if ("praten".equals(kind)) { l.talk++; if (k.optLong("t") >= l.at) { l.at = k.optLong("t"); l.text = k.optString("text"); } }
            else if ("geluid".equals(kind)) l.noise++;
        }
        if (l.text.length() > 160) l.text = l.text.substring(0, 160) + "…";
        return l;
    }

    /** Wanneer het uitschrijven van één stukje moet stoppen (het whisper-proces stopt dan meteen). */
    interface Halt { boolean now(); }
    /** Uitkomst van één stukje: uitgeschreven, niets te doen (al gedaan, weg of al bezig), of gestopt. */
    static final int TX_DONE = 0, TX_SKIP = 1, TX_STOP = 2;

    /**
     * Eén stukje uitschrijven en de uitkomst vastleggen (zelfde regels 's ochtends en meteen): praten, geluid of
     * onleesbaar; geluid zonder woorden verliest zijn geluid als je dat niet wilt bewaren. Gestopt halverwege: het
     * stukje blijft "nog niet uitgeschreven" (ook als de app wegvalt) en wordt later opnieuw gedaan. threads 0 = gewoon.
     * Gooit een fout als whisper zelf het niet kan.
     */
    static int transcribeClip(Context c, String night, String clip, Transcribe.Model m, Halt halt, int threads) throws Exception {
        long durMs = 60_000;
        synchronized (SleepTalk.class) {
            JSONObject n = night(c, night);
            JSONArray a = n == null ? null : n.optJSONArray("clips");
            boolean open = false;
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject k = a.getJSONObject(i);
                if (clip.equals(k.optString("id"))) { open = !k.optBoolean("done") && k.optBoolean("audio"); durMs = (long) (k.optDouble("dur", 60) * 1000); break; }
            }
            if (!open || !busy.add(clip)) return TX_SKIP;
        }
        try {
            if (halt.now()) return TX_STOP;
            File f = clipFile(c, night, clip);
            String text = "";
            boolean broken = false, missing = f == null || !f.isFile();
            JSONObject out = null;
            if (!missing) {
                // whisper wil WAV: een .m4a eerst (tijdelijk) omzetten
                File wav = f;
                try {
                    if (f.getName().endsWith(".m4a")) {
                        wav = new File(c.getCacheDir(), "slaappraat-" + clip + ".wav");
                        // De duur van het stukje: dan meldt hij voortgang en werkt Stoppen ook tijdens het omzetten
                        try { Transcribe.toWav(f, wav, Math.max(1000, durMs), (ph, pct) -> { if (halt.now()) throw new Stopped(); }, true); }
                        catch (Stopped s) { return TX_STOP; }
                        catch (Exception e) {
                            if (halt.now()) return TX_STOP;
                            // Weggedaan of gewist terwijl hij bezig was
                            if (!f.isFile()) missing = true;
                            else {
                                // Onleesbaar stukje: overslaan (en niet elke keer opnieuw proberen), de rest gaat door
                                App.log(c, "SLAAP", "stukje " + clip + " onleesbaar: " + e);
                                broken = true;
                            }
                        }
                    }
                    if (!broken && !missing) {
                        try { out = Transcribe.whisper(c, wav, m, (ph, pct) -> { if (halt.now()) throw new Stopped(); }, true, threads); }
                        catch (Stopped s) { return TX_STOP; }
                        catch (Exception e) { if (halt.now()) return TX_STOP; throw e; } // proces gestopt: geen fout
                    }
                } finally { if (wav != f) wav.delete(); }
                if (halt.now()) return TX_STOP;
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
                boolean changed = false;
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject k = a.getJSONObject(i);
                    if (!clip.equals(k.optString("id")) || k.optBoolean("done")) continue;
                    // Geen geluid meer en niets uitgeschreven: weggedaan om plaats te maken, dan niets vastleggen
                    if (missing && !k.optBoolean("audio")) continue;
                    String kind = kindOf(text, broken);
                    k.put("text", text).put("kind", kind).put("done", true);
                    // Geen praten: het geluid hoeft niet te blijven (tenzij je dat wilt)
                    if (missing || dropAudio(kind, keepNoise(c))) { removeAudio(c, night, clip); k.put("audio", false); }
                    changed = true;
                }
                if (changed) write(c, n);
            }
            return TX_DONE;
        } finally {
            synchronized (SleepTalk.class) { busy.remove(clip); }
        }
    }

    /** Alle nog niet uitgeschreven stukjes van een nacht uitschrijven (op een achtergrondthread aanroepen). */
    static void transcribeNight(Context c, String night, Step step) throws Exception {
        Transcribe.Model m = model(c);
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
                if (transcribeClip(c, night, clip, m, () -> stopTx, 0) == TX_STOP) return;
                done++;
            }
        } finally { txThread = null; }
        if (step != null) step.step(done, todo.size());
    }
}
