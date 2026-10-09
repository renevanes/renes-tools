package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gesprekken uitschrijven. Android laat gewone apps het geluid van een telefoongesprek meestal niet rechtstreeks
 * opnemen; deze tool gebruikt eigen microfoonopnames en opnames die de telefoon-app zelf maakt
 * (bij Oppo: ODialer), zet ze om naar 16 kHz mono WAV en laat ze uitschrijven door whisper.cpp,
 * dat als programma in de app zit (lib/arm64-v8a/libwhisper.so) en helemaal op de telefoon draait.
 * Het spraakmodel wordt eenmalig gedownload. Er gaat geen geluid of tekst de telefoon uit.
 */
final class Transcribe {

    private Transcribe() { }

    static final String DIR = "Gesprekken";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("transcribe", Context.MODE_PRIVATE); }

    // ---------- model ----------

    static final class Model {
        final String id, file, label, sha256;
        final long size;
        Model(String id, String file, String label, long size, String sha256) { this.id = id; this.file = file; this.label = label; this.size = size; this.sha256 = sha256; }
    }

    static final Model[] MODELS = {
            // Controlegetallen van Hugging Face (LFS-oid = SHA-256); een ander bestand wordt nooit gebruikt
            new Model("base", "ggml-base-q5_1.bin", "Snel (57 MB)", 59_707_625L, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
            new Model("small", "ggml-small-q5_1.bin", "Nauwkeurig (181 MB)", 190_085_487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
    };

    static Model model(String id) { for (Model m : MODELS) if (m.id.equals(id)) return m; return null; }

    static File modelDir(Context c) { File d = new File(c.getFilesDir(), "whisper"); d.mkdirs(); return d; }

    static File modelFile(Context c, Model m) { return new File(modelDir(c), m.file); }

    /** Het model dat gebruikt wordt: het gekozen model als dat geïnstalleerd is, anders een ander geïnstalleerd model. */
    static Model activeModel(Context c) {
        Model pref = model(prefs(c).getString("model", "base"));
        if (pref != null && modelFile(c, pref).isFile()) return pref;
        for (Model m : MODELS) if (modelFile(c, m).isFile()) return m;
        return null;
    }

    interface Progress { void step(String phase, int pct); }

    static volatile boolean cancel = false;
    static volatile Process proc;
    /** Hoort het lopende whisper-proces bij een spraaknotitie? (Stoppen bij gesprekken mag die niet afbreken.) */
    static volatile boolean procVoice;
    /** Eén whisper tegelijk: twee tegelijk vreten geheugen en zaten elkaars werkbestanden in de weg. */
    private static final Object WHISPER = new Object();

    /** "Stoppen" bij Gesprekken uitschrijven: alleen dat werk, niet een spraaknotitie die net loopt. */
    static void stopCalls() {
        cancel = true;
        Process p = proc;
        if (p != null && !procVoice) p.destroy();
    }

    /** Downloadt een model (eerst naar .part, pas bij volledige ontvangst hernoemen). */
    static void download(Context c, Model m, Progress p) throws Exception {
        File out = modelFile(c, m), part = new File(out.getPath() + ".part");
        String url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + m.file;
        HttpURLConnection h = null;
        boolean ok = false;
        for (int hop = 0; hop < 6; hop++) {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(20000);
            h.setReadTimeout(60000);
            h.setRequestProperty("User-Agent", "RenesTools");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                url = new URL(new URL(url), h.getHeaderField("Location")).toString();
                if (!url.startsWith("https://")) { h.disconnect(); throw new Exception("Download mislukt: onveilige doorverwijzing"); }
                h.disconnect();
                continue;
            }
            if (code != 200) { h.disconnect(); throw new Exception("Download mislukt (HTTP " + code + ")"); }
            ok = true;
            break;
        }
        if (!ok) throw new Exception("Download mislukt: te veel doorverwijzingen");
        long total = h.getContentLengthLong();
        long got = 0;
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = h.getInputStream(); OutputStream o = new BufferedOutputStream(new FileOutputStream(part), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                if (cancel) throw new Exception("Gestopt");
                o.write(buf, 0, n);
                md.update(buf, 0, n);
                got += n;
                int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (pct != last) { p.step("download", pct); last = pct; }
            }
        } catch (Exception e) {
            part.delete(); // half bestand (tot 181 MB) niet laten staan
            throw e;
        } finally { h.disconnect(); }
        if (total > 0 && got != total) { part.delete(); throw new Exception("Download onvolledig"); }
        if (got < 10_000_000L) { part.delete(); throw new Exception("Download is geen geldig model"); }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
        if (!hex.toString().equals(m.sha256)) { part.delete(); throw new Exception("Controlegetal van het spraakmodel klopt niet; niet geïnstalleerd"); }
        if (!part.renameTo(out)) throw new Exception("Model opslaan lukt niet");
    }

    // ---------- opnames vinden ----------

    static final String[] AUDIO = {".m4a", ".mp3", ".amr", ".aac", ".wav", ".ogg", ".opus", ".3gp", ".awb", ".3ga"};

    /** Mappen waar telefoon-apps (Oppo/ColorOS, OnePlus, Realme, Xiaomi, Samsung ...) gespreksopnames zetten. */
    static final String[] FOLDERS = {
            "Music/Recordings/Call Recordings", "Recordings/Call Recordings", "Recordings/Call", "Recordings/call",
            "Music/Recordings/Call", "Documents/Recordings/Call Recordings", "Call Recordings", "CallRecordings",
            "Call", "Record/Call", "Record/PhoneRecord", "PhoneRecord", "Sounds/CallRecord", "MIUI/sound_recorder/call_rec",
            "Music/Recordings/Call recordings"};

    /**
     * Mag dit pad uitgeschreven of afgespeeld worden? Alleen geluidsbestanden: de eigen opnamemap, of buiten de
     * privémap van de app (gedeelde opslag). Zo kan een vreemd pad (bijv. uit een teruggezette backup) geen
     * privébestanden van de app laten lezen.
     */
    static boolean allowedRecording(Context c, String path) { return allowedRecording(c, path, true); }

    /** mustExist = false: alleen de vorm van het pad (bij terugzetten staat de opname er misschien nog niet). */
    static boolean allowedRecording(Context c, String path, boolean mustExist) {
        if (path == null || path.isEmpty() || !isAudio(path)) return false;
        try {
            File f = new File(path).getCanonicalFile();
            if (mustExist && !f.isFile()) return false;
            File own = CallRecordings.dir(c).getCanonicalFile();
            if (own.equals(f.getParentFile())) return true;
            String p = f.getPath();
            String data = c.getApplicationInfo().dataDir == null ? "" : new File(c.getApplicationInfo().dataDir).getCanonicalPath();
            if (!data.isEmpty() && (p.equals(data) || p.startsWith(data + "/"))) return false;
            return !(p.startsWith("/data/") || p.startsWith("/proc/") || p.startsWith("/dev/") || p.startsWith("/sys/"));
        } catch (Exception e) { return false; }
    }

    static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : AUDIO) if (n.endsWith(e)) return true;
        return false;
    }

    /** Een opname die nog geschreven wordt (lopend gesprek) overslaan. */
    static boolean recent(File f) { return System.currentTimeMillis() - f.lastModified() < 15_000L; }

    static void scan(File d, int depth, Set<String> out) {
        File[] l = d.listFiles();
        if (l == null) return;
        for (File f : l) {
            if (f.isDirectory()) { if (depth > 0 && !f.getName().startsWith(".")) scan(f, depth - 1, out); }
            else if (isAudio(f.getName()) && f.length() > 2000 && !recent(f)) out.add(f.getAbsolutePath());
        }
    }

    /** Alle gevonden opnames (paden), uit de bekende mappen, een zelf gekozen map en MediaStore. */
    static List<File> findRecordings(Context c) {
        Set<String> paths = new HashSet<>();
        File[] own = CallRecordings.dir(c).listFiles((d, name) -> RecordingFiles.validName(name));
        if (own != null) for (File f : own) paths.add(f.getAbsolutePath());
        if (!MainActivity.hasFilesAccess(c)) {
            List<File> local = new ArrayList<>();
            for (String p : paths) local.add(new File(p));
            local.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            return local;
        }
        File root = Environment.getExternalStorageDirectory();
        for (String f : FOLDERS) scan(new File(root, f), 2, paths);
        String extra = prefs(c).getString("folder", "");
        if (!extra.isEmpty()) scan(new File(extra), 3, paths);
        if (Build.VERSION.SDK_INT >= 29) {
            String[] cols = {MediaStore.Audio.Media.DATA};
            String sel = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? OR " + MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
            try (Cursor cur = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, sel,
                    new String[]{"%Call%", "%call%"}, null)) {
                if (cur != null) while (cur.moveToNext()) {
                    String p = cur.getString(0);
                    if (p != null && isAudio(p) && new File(p).isFile() && !recent(new File(p))) paths.add(p);
                }
            } catch (Exception ignored) { }
        }
        List<File> out = new ArrayList<>();
        for (String p : paths) out.add(new File(p));
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    private static final Map<String, Long> durCache = new HashMap<>();

    /** Duur van een opname; onthouden (ook na herstart), want MediaMetadataRetriever is traag. */
    static synchronized long durationMs(Context c, File f) {
        String k = f.getPath() + ":" + f.lastModified() + ":" + f.length();
        Long d = durCache.get(k);
        if (d != null) return d;
        SharedPreferences dp = c.getSharedPreferences("txdur", Context.MODE_PRIVATE);
        if (dp.contains(k)) { d = dp.getLong(k, 0); durCache.put(k, d); return d; }
        long ms = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            ms = s == null ? 0 : Long.parseLong(s);
        } catch (Exception ignored) {
        } finally { try { r.release(); } catch (Exception ignored) { } }
        durCache.put(k, ms);
        dp.edit().putLong(k, ms).apply();
        return ms;
    }

    static String id(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest((f.getAbsolutePath() + ":" + f.length()).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(String.format(Locale.US, "%02x", h[i]));
            return b.toString();
        } catch (Exception e) { return String.valueOf(f.getAbsolutePath().hashCode()); }
    }

    static File transcriptDir(Context c) { File d = new File(c.getFilesDir(), "transcripts"); d.mkdirs(); return d; }

    static File transcriptFile(Context c, String id) { return new File(transcriptDir(c), id + ".json"); }

    // ---------- koppelen aan de oproepgeschiedenis ----------

    /** Zoekt de oproep bij een opname: nummer in de bestandsnaam en/of tijdstip en duur. */
    static Calls.Call matchCall(File f, long durMs, List<Calls.Call> calls) {
        long end = f.lastModified(), start = end - durMs;
        String digits = f.getName().replaceAll("[^0-9]", " ");
        Calls.Call best = null;
        long bestScore = Long.MAX_VALUE;
        for (Calls.Call k : calls) {
            if (k.date > end + 60_000L) continue;
            if (k.date < start - 30 * 60_000L) break; // lijst is nieuwste eerst
            long callEnd = k.date + k.duration * 1000L;
            long diff = Math.min(Math.abs(callEnd - end), Math.abs(k.date - start));
            if (diff > 3 * 60_000L) continue;
            long score = diff;
            String num = k.number == null ? "" : k.number.replaceAll("[^0-9]", "");
            if (num.length() >= 6 && digits.contains(num.substring(num.length() - 6))) score -= 10 * 60_000L;
            if (score < bestScore) { bestScore = score; best = k; }
        }
        return best;
    }

    // ---------- omzetten naar 16 kHz mono WAV ----------

    static void writeWavHeader(RandomAccessFile w, int dataLen) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + dataLen).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(dataLen);
        w.seek(0);
        w.write(b.array());
    }

    /** Decodeert een audiobestand met de decoders van Android en schrijft 16 kHz mono 16-bit WAV. */
    static void toWav(File in, File out, long durMs, Progress p) throws Exception { toWav(in, out, durMs, p, false); }

    /** voice = voor een spraaknotitie: "Stoppen" bij gesprekken uitschrijven geldt daar niet. */
    static void toWav(File in, File out, long durMs, Progress p, boolean voice) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try (RandomAccessFile w = new RandomAccessFile(out, "rw")) {
            w.setLength(0);
            writeWavHeader(w, 0);
            ex.setDataSource(in.getPath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new Exception("Geen geluid gevonden in " + in.getName());
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean isFloat = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            double pos = 0;               // positie in de bronsamples voor het herbemonsteren
            long srcIndex = 0;            // aantal verwerkte bron(mono)samples
            short prev = 0;
            int dataLen = 0, lastPct = -1;
            ByteBuffer ob = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
            double step = rate / 16000.0;
            while (!outDone) {
                if (!voice && cancel) throw new Exception("Gestopt");
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int n = ex.readSampleData(ib, 0);
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    step = rate / 16000.0;
                    int enc = of.containsKey("pcm-encoding") ? of.getInteger("pcm-encoding") : 2; // 2 = 16-bit, 4 = float
                    if (enc != 2 && enc != 4) throw new Exception("Audioformaat wordt niet ondersteund");
                    isFloat = enc == 4;
                } else if (oi >= 0) {
                    ByteBuffer bb = codec.getOutputBuffer(oi);
                    if (bb != null && info.size > 0) {
                        bb.position(info.offset).limit(info.offset + info.size);
                        bb.order(ByteOrder.nativeOrder());
                        java.nio.ShortBuffer sb = isFloat ? null : bb.asShortBuffer();
                        java.nio.FloatBuffer fb = isFloat ? bb.asFloatBuffer() : null;
                        int frames = (isFloat ? fb.remaining() : sb.remaining()) / Math.max(1, ch);
                        for (int f = 0; f < frames; f++) {
                            int sum = 0;
                            for (int k = 0; k < ch; k++) {
                                if (isFloat) sum += (int) Math.max(-32768, Math.min(32767, fb.get(f * ch + k) * 32767f));
                                else sum += sb.get(f * ch + k);
                            }
                            short cur = (short) (sum / Math.max(1, ch));
                            // Lineair herbemonsteren naar 16 kHz (ruim voldoende voor spraak).
                            while (pos <= srcIndex) {
                                double frac = pos - (srcIndex - 1);
                                short v = (short) (prev + (cur - prev) * Math.max(0, Math.min(1, frac)));
                                ob.putShort(v);
                                if (!ob.hasRemaining()) { w.write(ob.array(), 0, ob.position()); dataLen += ob.position(); ob.clear(); }
                                pos += step;
                            }
                            prev = cur;
                            srcIndex++;
                        }
                        if (durMs > 0) {
                            int pct = (int) Math.min(100, info.presentationTimeUs / 10 / durMs);
                            if (pct != lastPct) { p.step("decode", pct); lastPct = pct; }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            if (ob.position() > 0) { w.write(ob.array(), 0, ob.position()); dataLen += ob.position(); }
            writeWavHeader(w, dataLen);
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
            ex.release();
        }
    }

    // ---------- whisper.cpp uitvoeren ----------

    static File binary(Context c, boolean generic) {
        return new File(c.getApplicationInfo().nativeLibraryDir, generic ? "libwhisper_generic.so" : "libwhisper.so");
    }

    static boolean supported() {
        for (String a : Build.SUPPORTED_64_BIT_ABIS) if ("arm64-v8a".equals(a)) return true;
        return false;
    }

    /** Draait whisper; geeft de JSON-uitvoer. Valt terug op de algemene versie als de snelle niet draait op deze processor. */
    static JSONObject whisper(Context c, File wav, Model m, Progress p) throws Exception { return whisper(c, wav, m, p, false); }

    /** voice = voor een spraaknotitie (los van "Stoppen" bij gesprekken uitschrijven). */
    static JSONObject whisper(Context c, File wav, Model m, Progress p, boolean voice) throws Exception {
        synchronized (WHISPER) {
            boolean generic = prefs(c).getBoolean("generic", false);
            try {
                return runWhisper(c, wav, m, generic, p, voice);
            } catch (IllegalStateException e) {
                if (generic) throw new Exception(e.getMessage());
                prefs(c).edit().putBoolean("generic", true).apply();
                return runWhisper(c, wav, m, true, p, voice);
            }
        }
    }

    private static JSONObject runWhisper(Context c, File wav, Model m, boolean generic, Progress p, boolean voice) throws Exception {
        File bin = binary(c, generic);
        if (!bin.isFile()) throw new Exception("Het uitschrijfprogramma ontbreekt in deze app-versie");
        File outBase = new File(c.getCacheDir(), "whisper-out-" + System.nanoTime()); // eigen werkbestand per keer
        File[] stale = c.getCacheDir().listFiles((d, n) -> n.startsWith("whisper-out"));
        if (stale != null) for (File f : stale) if (System.currentTimeMillis() - f.lastModified() > 3_600_000L) f.delete();
        File json = new File(outBase.getPath() + ".json");
        json.delete();
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getPath());
        cmd.add("-m"); cmd.add(modelFile(c, m).getPath());
        cmd.add("-f"); cmd.add(wav.getPath());
        cmd.add("-l"); cmd.add(prefs(c).getString("lang", "nl"));
        cmd.add("-t"); cmd.add(String.valueOf(threads));
        cmd.add("-pp");
        cmd.add("-oj");
        cmd.add("-of"); cmd.add(outBase.getPath());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("HOME", c.getFilesDir().getPath());
        Process pr = pb.start();
        procVoice = voice;
        proc = pr;
        if (!voice && cancel) pr.destroy();
        StringBuilder tail = new StringBuilder();
        int code;
        try {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream(), "UTF-8"))) {
            String line;
            java.util.regex.Pattern prog = java.util.regex.Pattern.compile("progress\\s*=\\s*(\\d+)%");
            while ((line = r.readLine()) != null) {
                java.util.regex.Matcher mt = prog.matcher(line);
                if (mt.find()) p.step("transcribe", Integer.parseInt(mt.group(1)));
                tail.append(line).append('\n');
                if (tail.length() > 4000) tail.delete(0, tail.length() - 4000);
            }
        }
        code = pr.waitFor();
        } finally { proc = null; procVoice = false; pr.destroy(); /* nooit een los proces laten doorlopen */ }
        if (!voice && cancel) { json.delete(); throw new Exception("Gestopt"); }
        // 132 = SIGILL: processor kent een instructie van de snelle versie niet.
        if (code == 132 || code == 128 + 4 || tail.indexOf("Illegal instruction") >= 0)
            throw new IllegalStateException("Processor wordt niet ondersteund");
        if (code != 0 || !json.isFile()) {
            String t = tail.toString().trim();
            if (t.length() > 300) t = t.substring(t.length() - 300);
            json.delete();
            throw new Exception("Uitschrijven mislukt (code " + code + ")" + (t.isEmpty() ? "" : ": " + t));
        }
        JSONObject o = new JSONObject(Contacts.readText(json, false));
        json.delete();
        return o;
    }

    // ---------- uitschrijven en opslaan ----------

    /** Schrijft één opname uit en bewaart het transcript. */
    static void transcribeOne(Context c, File f, Progress p) throws Exception {
        // Een spraaknotitie gebruikt hetzelfde spraakmodel en dezelfde werkbestanden: eerst die laten afmaken
        for (int i = 0; i < 600 && "working".equals(VoiceNote.state); i++) Thread.sleep(500);
        Model m = activeModel(c);
        if (m == null) throw new Exception("Download eerst een spraakmodel");
        long dur = durationMs(c, f);
        File wav = new File(c.getCacheDir(), "rec.wav");
        try {
            p.step("decode", 0);
            toWav(f, wav, dur, p);
            p.step("transcribe", 0);
            JSONObject out = whisper(c, wav, m, p);
            JSONArray segs = new JSONArray();
            JSONArray tr = out.optJSONArray("transcription");
            StringBuilder all = new StringBuilder();
            if (tr != null) for (int i = 0; i < tr.length(); i++) {
                JSONObject s = tr.getJSONObject(i);
                String text = s.optString("text").trim();
                if (text.isEmpty()) continue;
                JSONObject off = s.optJSONObject("offsets");
                segs.put(new JSONObject().put("from", off == null ? 0 : off.optLong("from"))
                        .put("to", off == null ? 0 : off.optLong("to")).put("text", text));
                all.append(text).append(' ');
            }
            JSONObject t = new JSONObject();
            t.put("id", id(f)).put("path", f.getAbsolutePath()).put("file", f.getName())
                    .put("size", f.length()).put("mtime", f.lastModified()).put("duration", dur)
                    .put("model", m.id).put("lang", prefs(c).getString("lang", "nl"))
                    .put("created", System.currentTimeMillis()).put("segments", segs).put("text", all.toString().trim());
            Calls.Call k = c.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ? matchCall(f, dur, Calls.read(c, 3000)) : null;
            if (k != null) {
                t.put("number", k.number == null ? "" : k.number).put("name", Calls.name(c, k))
                        .put("callDate", k.date).put("kind", Calls.kind(k.type));
            }
            Contacts.writeText(transcriptFile(c, t.getString("id")), t.toString(), false);
            saveMeta(c, t);
        } finally {
            wav.delete();
        }
    }

    /** Korte samenvatting per transcript, zodat de lijst niet elk volledig transcript hoeft te lezen. */
    static void saveMeta(Context c, JSONObject t) throws Exception {
        String text = t.optString("text");
        JSONObject m = new JSONObject().put("file", t.optString("file")).put("mtime", t.optLong("mtime"))
                .put("duration", t.optLong("duration")).put("preview", text.length() > 140 ? text.substring(0, 140) + "…" : text);
        if (t.has("name")) m.put("name", t.optString("name")).put("number", t.optString("number")).put("kind", t.optString("kind"));
        c.getSharedPreferences("txmeta", Context.MODE_PRIVATE).edit().putString(t.optString("id"), m.toString()).apply();
    }

    static JSONObject meta(Context c, String id) {
        String s = c.getSharedPreferences("txmeta", Context.MODE_PRIVATE).getString(id, null);
        try {
            if (s != null) return new JSONObject(s);
            JSONObject t = loadTranscript(c, id);
            if (t == null) return null;
            saveMeta(c, t);
            return new JSONObject(c.getSharedPreferences("txmeta", Context.MODE_PRIVATE).getString(id, "{}"));
        } catch (Exception e) { return null; }
    }

    static void delete(Context c, String id) {
        transcriptFile(c, id).delete();
        c.getSharedPreferences("txmeta", Context.MODE_PRIVATE).edit().remove(id).apply();
    }

    static JSONObject loadTranscript(Context c, String id) {
        File f = transcriptFile(c, id);
        try { return f.isFile() ? new JSONObject(Contacts.readText(f, false)) : null; } catch (Exception e) { return null; }
    }

    /** Opnames met (indien gevonden) de bijbehorende oproep en of ze al uitgeschreven zijn. */
    static String listJson(Context c) throws Exception {
        List<File> recs = findRecordings(c);
        boolean callPerm = c.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        List<Calls.Call> calls = callPerm ? Calls.read(c, 3000) : new ArrayList<>();
        JSONArray arr = new JSONArray();
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (File f : recs) {
            if (n++ >= 400) break;
            String id = id(f);
            seen.add(id);
            JSONObject o = new JSONObject().put("id", id).put("file", f.getName()).put("path", f.getAbsolutePath())
                    .put("mtime", f.lastModified()).put("size", f.length());
            JSONObject t = transcriptFile(c, id).isFile() ? meta(c, id) : null;
            if (t != null) {
                o.put("done", true).put("duration", t.optLong("duration")).put("preview", t.optString("preview"));
                if (t.has("name")) o.put("name", t.optString("name")).put("number", t.optString("number")).put("kind", t.optString("kind"));
            } else {
                long dur = durationMs(c, f);
                o.put("done", false).put("duration", dur);
                Calls.Call k = matchCall(f, dur, calls);
                if (k != null) o.put("name", Calls.name(c, k)).put("number", k.number == null ? "" : k.number).put("kind", Calls.kind(k.type));
            }
            arr.put(o);
        }
        // Transcripten waarvan de opname intussen weg is, blijven zichtbaar.
        File[] ts = transcriptDir(c).listFiles();
        if (ts != null) for (File tf : ts) {
            String id = tf.getName().replace(".json", "");
            if (seen.contains(id) || !tf.getName().endsWith(".json")) continue;
            JSONObject t = meta(c, id);
            if (t == null) continue;
            JSONObject o = new JSONObject().put("id", id).put("file", t.optString("file")).put("mtime", t.optLong("mtime"))
                    .put("done", true).put("gone", true).put("duration", t.optLong("duration")).put("preview", t.optString("preview"));
            if (t.has("name")) o.put("name", t.optString("name")).put("number", t.optString("number")).put("kind", t.optString("kind"));
            arr.put(o);
        }
        return new JSONObject().put("recordings", arr).toString();
    }

    static String searchJson(Context c, String q) throws Exception {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        JSONArray out = new JSONArray();
        File[] ts = transcriptDir(c).listFiles();
        if (ts != null && needle.length() >= 2) for (File tf : ts) {
            if (!tf.getName().endsWith(".json")) continue;
            JSONObject t = loadTranscript(c, tf.getName().replace(".json", ""));
            if (t == null) continue;
            JSONArray segs = t.optJSONArray("segments");
            boolean nameHit = t.optString("name").toLowerCase(Locale.ROOT).contains(needle);
            if (segs != null) for (int i = 0; i < segs.length() && out.length() < 300; i++) {
                JSONObject s = segs.getJSONObject(i);
                if (nameHit && i > 0) break;
                if (nameHit || s.optString("text").toLowerCase(Locale.ROOT).contains(needle)) {
                    out.put(new JSONObject().put("id", t.optString("id")).put("name", t.optString("name", t.optString("file")))
                            .put("mtime", t.optLong("mtime")).put("from", s.optLong("from")).put("text", s.optString("text")));
                }
            }
        }
        return new JSONObject().put("results", out).toString();
    }

    static String fmtTime(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** Zet transcripten als tekstbestand in de backup-map (Gesprekken/). id leeg = allemaal. */
    static int export(Context c, String onlyId) throws Exception {
        android.net.Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map (bij WhatsApp backup)");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(DIR, true);
        java.text.SimpleDateFormat fn = new java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US);
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("EEEE d MMMM yyyy HH:mm", new Locale("nl", "NL"));
        int n = 0;
        File[] ts = transcriptDir(c).listFiles();
        // Eerst alle namen bepalen: twee gesprekken in dezelfde minuut met dezelfde persoon (of twee zonder naam)
        // krijgen anders dezelfde bestandsnaam en overschrijven elkaar. Vaste regel (op id), dus ook bij één export.
        java.util.TreeMap<String, String> names = new java.util.TreeMap<>(); // id → naam
        Map<String, Integer> seen = new HashMap<>();
        if (ts != null) {
            java.util.Arrays.sort(ts, (x, y) -> x.getName().compareTo(y.getName()));
            for (File tf : ts) {
                if (!tf.getName().endsWith(".json")) continue;
                String id = tf.getName().replace(".json", "");
                JSONObject t = loadTranscript(c, id);
                if (t == null) continue;
                long when = t.optLong("callDate", t.optLong("mtime"));
                String who = t.optString("name", "");
                String base = fn.format(new java.util.Date(when)) + "_" + Sms.safeName(who.isEmpty() ? "gesprek" : who);
                int k = seen.merge(base.toLowerCase(Locale.ROOT), 1, Integer::sum);
                names.put(id, (k == 1 ? base : base + " (" + k + ")") + ".txt");
            }
        }
        if (ts != null) for (File tf : ts) {
            if (!tf.getName().endsWith(".json")) continue;
            String id = tf.getName().replace(".json", "");
            if (onlyId != null && !onlyId.isEmpty() && !onlyId.equals(id)) continue;
            JSONObject t = loadTranscript(c, id);
            if (t == null || !names.containsKey(id)) continue;
            long when = t.optLong("callDate", t.optLong("mtime"));
            String who = t.optString("name", "");
            String name = names.get(id);
            try (Sms.Out w = Sms.open(dest, dir, name, "text/plain")) {
                w.write("Gesprek" + (who.isEmpty() ? "" : " met " + who) + (t.optString("number").isEmpty() || who.equals(t.optString("number")) ? "" : " (" + t.optString("number") + ")") + "\r\n");
                w.write(df.format(new java.util.Date(when)) + " · duur " + fmtTime(t.optLong("duration")) + "\r\n");
                w.write("Opname: " + t.optString("file") + " · uitgeschreven door Rene's Tools (Whisper " + t.optString("model") + ", op de telefoon)\r\n\r\n");
                JSONArray segs = t.optJSONArray("segments");
                if (segs != null) for (int i = 0; i < segs.length(); i++) {
                    JSONObject s = segs.getJSONObject(i);
                    w.write("[" + fmtTime(s.optLong("from")) + "] " + s.optString("text") + "\r\n");
                }
                w.done(); // pas nu vervangt het nieuwe bestand het oude
            }
            n++;
        }
        if (n == 0) throw new Exception("Nog geen uitgeschreven gesprekken");
        return n;
    }
}
