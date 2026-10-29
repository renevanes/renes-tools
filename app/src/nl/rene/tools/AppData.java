package nl.rene.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * "Alle appgegevens" in een versleutelde backup: welke gegevens van de app meegaan (en welke bewust niet), de
 * inhoudsopgave met controlegetallen, en het controleren van namen uit een gekozen (dus niet te vertrouwen) archief.
 * Puur Java (geen Android), zodat het op een gewone JVM getest kan worden (tests/jvm/…/AppDataTest.java).
 *
 * Indeling in het archief (.rtb, versleuteld met het backup-wachtwoord):
 *   Appgegevens/instellingen.json   alle instellingen (SharedPreferences) die meegaan, met hun soort
 *   Appgegevens/geheim/…            wat op de telefoon met een sleutel uit de sleutelopslag versleuteld is
 *                                   (VPN, Spotify, kluis, handtekening): hier ontsleuteld, alleen binnen dit archief;
 *                                   bij terugzetten weer versleuteld met de sleutel van de nieuwe installatie
 *   Appgegevens/bestanden/…         bestanden van de app (notities, opnames, routes, Slaappraat, …)
 *   Appgegevens/meldingen.jsonl     de meldingsgeschiedenis (rij voor rij, niet de database zelf)
 *   Appgegevens/modellen.json       welke modellen er waren (die worden opnieuw gedownload, niet gekopieerd)
 *   Appgegevens/toestand.json       welke toestemmingen er waren (voor het lijstje na terugzetten)
 *   Appgegevens/inhoud.txt          als laatste: alle bestanden met grootte en SHA-256
 */
final class AppData {

    private AppData() { }

    static final String DIR = "Appgegevens";
    static final String MANIFEST = DIR + "/inhoud.txt";
    static final String PREFS = DIR + "/instellingen.json";
    static final String SECRET_DIR = DIR + "/geheim";
    static final String SECRETS = SECRET_DIR + "/waarden.json";
    static final String KLUIS_DIR = SECRET_DIR + "/kluis";
    static final String KLUIS_INDEX = KLUIS_DIR + "/index.json";
    static final String SIGNATURE = SECRET_DIR + "/pdf-handtekening.json";
    static final String HISTORY = DIR + "/meldingen.jsonl";
    static final String MODELS = DIR + "/modellen.json";
    static final String STATE = DIR + "/toestand.json";
    static final String FILES = DIR + "/bestanden";
    static final String FORMAT = "renes-tools-app";
    static final int VERSION = 1;

    // ---------- instellingen (SharedPreferences) ----------

    /** Instellingen die meegaan (en teruggezet mogen worden). Elke naam in de code staat hier of in PREFS_SKIP. */
    static final List<String> PREFS_KEEP = Collections.unmodifiableList(Arrays.asList(
            "allbackup", "alarm", "auto", "call_recordings", "calls", "car", "care", "checkin", "contacts", "launcher",
            "localai", "lock", "mockloc", "models", "music", "notepins", "notification_history", "notifcenter", "pdf",
            "pdfview", "pinned_shortcuts", "player", "podcast", "podcast_dl", "radio", "redial", "redialplan", "reminders",
            "sleeptalk", "sms", "solo", "spotify", "tracks", "transcribe", "txdur", "txmeta", "ui", "vpn", "wabackup",
            "webstore", "workspace"));

    /** Instellingen die bewust niet meegaan, met de reden. */
    static final Map<String, String> PREFS_SKIP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("secure", "sleutel van de versleuteling: wordt na terugzetten opnieuw gemaakt met je wachtwoord");
        m.put("intent_token", "geheim van deze installatie");
        m.put("crash", "foutmeldingen van deze installatie");
        m.put("selftest", "uitkomst van de zelftest van deze installatie");
        m.put("perm", "welke toestemmingen al eens gevraagd zijn (Android vergeet de toestemmingen zelf ook)");
        m.put("update", "toestand van de updates van deze installatie");
        m.put("alarm_dp", "kopie van de radiowekker voor na een herstart (wordt opnieuw gemaakt)");
        m.put("notewidget", "koppeling met widgets (die moet je opnieuw plaatsen)");
        PREFS_SKIP = Collections.unmodifiableMap(m);
    }

    /** Per bestand: sleutels die bij deze installatie of dit moment horen (lopende acties, downloads, wachttijden). */
    static final Map<String, Set<String>> KEY_SKIP;
    /** Per bestand: sleutels die met dit voorvoegsel beginnen, niet meenemen. */
    static final Map<String, List<String>> KEY_SKIP_PREFIX;
    static {
        Map<String, Set<String>> k = new HashMap<>();
        k.put("allbackup", set("next"));
        k.put("transcribe", set("status", "dlModel"));
        k.put("lock", set("fails", "waitUntil", "waitElapsed", "waitBoot", "waitServed", "lastTry", "keyMismatch", "bioRenew"));
        // De backup-map is net opnieuw gekozen; de leesbare WhatsApp-database gaat niet mee (wordt opnieuw gemaakt)
        k.put("wabackup", set("dest", "status", "waDbFrom", "waDbAt"));
        k.put("redial", set("status"));
        k.put("mockloc", set("active"));
        k.put("call_recordings", set("active", "autoSession", "lastPhoneState", "autoAttempted"));
        k.put("car", set("tripStart"));
        k.put("tracks", set("trip"));
        k.put("webstore", set("pending", "restoreAll", "replace"));
        k.put("spotify", set("refresh")); // versleuteld met de sleutel van de telefoon: gaat via geheim/waarden.json
        KEY_SKIP = Collections.unmodifiableMap(k);
        Map<String, List<String>> p = new HashMap<>();
        p.put("localai", Arrays.asList("dl_", "ok_", "own_", "err_", "retry_", "tried_")); // downloads van deze installatie
        KEY_SKIP_PREFIX = Collections.unmodifiableMap(p);
    }

    static Set<String> set(String... s) { return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(s))); }

    static boolean keepPrefsFile(String file) { return file != null && PREFS_KEEP.contains(file); }

    /** Gaat deze instelling mee (en mag hij teruggezet worden)? */
    static boolean keepPref(String file, String key) {
        if (!keepPrefsFile(file) || key == null || key.isEmpty() || key.length() > 200) return false;
        Set<String> s = KEY_SKIP.get(file);
        if (s != null && s.contains(key)) return false;
        List<String> p = KEY_SKIP_PREFIX.get(file);
        if (p != null) for (String x : p) if (key.startsWith(x)) return false;
        return true;
    }

    // ---------- bestanden (getFilesDir) ----------

    /** Mappen en bestanden in getFilesDir() die meegaan. (De kluis gaat apart: ontsleuteld via geheim/kluis.) */
    static final List<String> FILE_ROOTS = Collections.unmodifiableList(Arrays.asList(
            "notes.json", "notes.json.bak", "music.json", "ai", "call-recordings", "checkin", "contacts", "podcasts",
            "shortcut-icons", "sleep", "spotify", "tracks", "transcripts"));

    /** Wat in getFilesDir() bewust niet meegaat, met de reden. */
    static final Map<String, String> FILE_SKIP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ai-models", "taalmodellen: worden opnieuw gedownload");
        m.put("whisper", "spraakmodellen: worden opnieuw gedownload");
        m.put("web", "bijgewerkte interface: komt vanzelf weer binnen");
        m.put("wa", "leesbare kopie van de WhatsApp-backup: wordt opnieuw gemaakt uit de WhatsApp-backup");
        m.put("kluis", "gaat apart mee (ontsleuteld, versleuteld in het archief)");
        m.put("crash.log", "foutlogboek van deze installatie");
        m.put("notes.json.tmp", "tijdelijk bestand");
        FILE_SKIP = Collections.unmodifiableMap(m);
    }

    /** Losse bestanden binnen een map die meegaat, maar die zelf niet mee horen. */
    static final Map<String, String> PATH_SKIP;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("ai/index.json", "zoekindex: wordt opnieuw gemaakt");
        m.put("podcasts/downloads.json", "gedownloade afleveringen: die staan buiten de app en zijn na verwijderen weg");
        m.put("tracks/live.trk", "route die nu wordt opgenomen");
        PATH_SKIP = Collections.unmodifiableMap(m);
    }

    static final String[] TEMP_SUFFIX = {".part", ".tmp", ".writing", ".download", ".rt-part", ".rt-tmp", ".keep", "-journal"};

    /**
     * Gaat dit bestand (pad binnen getFilesDir(), met /) mee? null = ja, anders de reden. Onbekende mappen gaan niet mee:
     * alleen wat de app zelf kent, kan ook weer veilig teruggezet worden.
     */
    static String fileRule(String rel) {
        String r = safeRel(rel);
        if (r == null) return "vreemde naam";
        int slash = r.indexOf('/');
        String top = slash < 0 ? r : r.substring(0, slash);
        if (FILE_SKIP.containsKey(top)) return FILE_SKIP.get(top);
        if (!FILE_ROOTS.contains(top)) return "onbekend";
        if (slash < 0 && !top.contains(".")) return null; // lege map zelf
        if (PATH_SKIP.containsKey(r)) return PATH_SKIP.get(r);
        String low = r.toLowerCase(Locale.ROOT);
        for (String s : TEMP_SUFFIX) if (low.endsWith(s)) return "tijdelijk bestand";
        return null;
    }

    /** Is dit een losse rootnaam die een bestand is (geen map)? */
    static boolean rootIsFile(String top) { return top.contains("."); }

    /** Geluid is al samengeperst: dan licht inpakken (sneller, het wordt toch niet kleiner). */
    static boolean compressed(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : new String[]{".m4a", ".mp3", ".aac", ".ogg", ".opus", ".amr", ".3gp", ".jpg", ".jpeg", ".png", ".webp", ".mp4", ".pdf", ".gz", ".zip"})
            if (n.endsWith(e)) return true;
        return false;
    }

    // ---------- namen controleren ----------

    static final int MAX_PATH = 600, MAX_DEPTH = 14, MAX_NAME = 255;

    /**
     * Een pad uit een archief (of van de telefoon) als veilig relatief pad met /, of null als het niet kan:
     * leeg, absoluut, met \ of :, met "." of ".." als onderdeel, met stuurtekens, te lang of te diep.
     */
    static String safeRel(String p) {
        if (p == null || p.isEmpty() || p.length() > MAX_PATH) return null;
        if (p.startsWith("/") || p.endsWith("/") || p.indexOf('\\') >= 0 || p.indexOf(':') >= 0) return null;
        for (int i = 0; i < p.length(); i++) { char ch = p.charAt(i); if (ch < 0x20 || ch == 0x7f) return null; }
        String[] parts = p.split("/", -1);
        if (parts.length > MAX_DEPTH) return null;
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".") || s.equals("..") || s.length() > MAX_NAME) return null;
            if (s.trim().isEmpty()) return null;
        }
        return p;
    }

    /** Pad in het archief → wat het is. FILES-paden geven "f:" + pad binnen getFilesDir(). null = onbekend/onveilig. */
    static String target(String archivePath) {
        String p = safeRel(archivePath);
        if (p == null || !p.startsWith(DIR + "/")) return null;
        if (p.equals(PREFS)) return "prefs";
        if (p.equals(SECRETS)) return "secrets";
        if (p.equals(KLUIS_INDEX)) return "kluis-index";
        if (p.equals(SIGNATURE)) return "signature";
        if (p.equals(HISTORY)) return "history";
        if (p.equals(MODELS)) return "models";
        if (p.equals(STATE)) return "state";
        if (p.startsWith(KLUIS_DIR + "/")) {
            String id = p.substring(KLUIS_DIR.length() + 1);
            return id.matches("[a-z0-9]{6,24}") ? "kluis:" + id : null;
        }
        if (p.startsWith(FILES + "/")) {
            String rel = p.substring(FILES.length() + 1);
            return fileRule(rel) == null ? "f:" + rel : null;
        }
        return null;
    }

    // ---------- inhoudsopgave ----------

    /** Eén bestand in het archief: naam in het archief, grootte, SHA-256 (hex) en wanneer het gewijzigd was (0 = onbekend). */
    static final class Entry {
        final String path; final long size; final String sha; final long mtime;
        Entry(String p, long s, String h) { this(p, s, h, 0); }
        Entry(String p, long s, String h, long t) { path = p; size = s; sha = h; mtime = t; }
    }

    /** Inhoudsopgave: wat erin hoort, plus waar het vandaan komt (versie, tijd). */
    static final class Manifest {
        String app = "", appCode = "";
        long time;
        final List<Entry> entries = new ArrayList<>();
        /** Bestanden die bij het maken niet af kwamen (staan wel in het archief, maar horen niet terug). */
        final Set<String> broken = new HashSet<>();
        /** Ter info: wat bewust niet meeging. */
        final List<String[]> skipped = new ArrayList<>();
        long bytes() { long n = 0; for (Entry e : entries) n += e.size; return n; }
        Entry find(String path) { for (Entry e : entries) if (e.path.equals(path)) return e; return null; }
    }

    static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    static String build(Manifest m) {
        StringBuilder b = new StringBuilder();
        b.append(FORMAT).append('\t').append(VERSION).append('\n');
        b.append("app\t").append(clean(m.app)).append('\t').append(clean(m.appCode)).append('\n');
        b.append("t\t").append(m.time).append('\n');
        for (Entry e : m.entries) b.append("F\t").append(e.size).append('\t').append(e.sha).append('\t').append(Math.max(0, e.mtime)).append('\t').append(clean(e.path)).append('\n');
        for (String p : m.broken) b.append("B\t").append(clean(p)).append('\n');
        for (String[] s : m.skipped) b.append("X\t").append(clean(s[0])).append('\t').append(clean(s[1])).append('\n');
        b.append("end\t").append(m.entries.size()).append('\n');
        return b.toString();
    }

    /** Leest een inhoudsopgave; gooit bij alles wat niet klopt (ook afgekapt: dan ontbreekt de slotregel). */
    static Manifest parse(String text) throws IOException {
        if (text == null || text.length() > 16_000_000) throw new IOException("Inhoudsopgave ontbreekt of is te groot");
        String[] lines = text.split("\n");
        if (lines.length < 2) throw new IOException("Inhoudsopgave is onvolledig");
        String[] head = lines[0].split("\t");
        if (head.length != 2 || !FORMAT.equals(head[0])) throw new IOException("Dit is geen inhoudsopgave van Tools");
        int ver;
        try { ver = Integer.parseInt(head[1]); } catch (NumberFormatException e) { throw new IOException("Inhoudsopgave is beschadigd"); }
        if (ver < 1 || ver > VERSION) throw new IOException("Deze backup is gemaakt met een nieuwere versie van Tools; werk de app eerst bij");
        Manifest m = new Manifest();
        Set<String> seen = new HashSet<>();
        boolean end = false;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.isEmpty()) continue;
            if (end) throw new IOException("Inhoudsopgave is beschadigd");
            String[] f = l.split("\t", -1);
            try {
                switch (f[0]) {
                    case "app": m.app = f.length > 1 ? f[1] : ""; m.appCode = f.length > 2 ? f[2] : ""; break;
                    case "t": m.time = Long.parseLong(f[1]); break;
                    case "F": {
                        if (f.length != 5) throw new IOException("Inhoudsopgave is beschadigd");
                        long size = Long.parseLong(f[1]), mtime = Long.parseLong(f[3]);
                        String sha = f[2], path = f[4];
                        // Onbekend maar veilig (van een nieuwere versie): mag, wordt alleen niet teruggezet
                        if (size < 0 || mtime < 0 || !sha.matches("[0-9a-f]{64}") || safeRel(path) == null || !path.startsWith(DIR + "/"))
                            throw new IOException("Vreemde naam in de inhoudsopgave: " + cut(path));
                        if (!seen.add(path)) throw new IOException("Dubbele naam in de inhoudsopgave: " + cut(path));
                        m.entries.add(new Entry(path, size, sha, mtime));
                        break;
                    }
                    case "B": if (f.length > 1) m.broken.add(f[1]); break;
                    case "X": if (f.length > 2) m.skipped.add(new String[]{f[1], f[2]}); break;
                    case "end":
                        if (Integer.parseInt(f[1]) != m.entries.size()) throw new IOException("Inhoudsopgave is onvolledig");
                        end = true;
                        break;
                    default: break; // onbekende regel van een nieuwere versie: overslaan
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) { throw new IOException("Inhoudsopgave is beschadigd"); }
        }
        if (!end) throw new IOException("Inhoudsopgave is onvolledig");
        return m;
    }

    static String cut(String s) { return s.length() > 80 ? s.substring(0, 80) + "…" : s; }

    /**
     * Klopt wat er in het archief stond met de inhoudsopgave? seen = naam → {grootte, sha} van alles onder Appgegevens/
     * (behalve de inhoudsopgave zelf). null = in orde, anders wat er mis is.
     */
    static String verify(Manifest m, Map<String, Entry> seen) {
        for (Entry e : m.entries) {
            if (target(e.path) == null) continue; // kent deze versie niet: wordt overgeslagen
            Entry s = seen.get(e.path);
            if (s == null) return "Ontbreekt in de backup: " + cut(e.path);
            if (s.size != e.size || !s.sha.equals(e.sha)) return "Beschadigd in de backup: " + cut(e.path);
        }
        for (String p : seen.keySet()) if (m.find(p) == null && !m.broken.contains(p)) return "Niet in de inhoudsopgave: " + cut(p);
        return null;
    }

    // ---------- controlegetallen ----------

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) { s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16)); }
        return s.toString();
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Schrijft door en telt bytes en SHA-256 mee. Sluiten sluit de onderliggende stroom. */
    static final class DigestOut extends OutputStream {
        private final OutputStream out;
        final MessageDigest md = sha256();
        long n = 0;
        DigestOut(OutputStream o) { out = o; }
        @Override public void write(int b) throws IOException { out.write(b); md.update((byte) b); n++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); md.update(b, off, len); n += len; }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }
        String sha() { return hex(md.digest()); }
    }

    // ---------- waarden die met de sleutel van de telefoon versleuteld zijn ----------
    // Zelfde opbouw als Vpn/Spotify: base64(iv) ":" base64(AES-GCM), standaard-base64 zonder regeleinden.

    static String seal(SecretKey k, String plain) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, k);
        return b64(ci.getIV()) + ":" + b64(ci.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    static String open(SecretKey k, String sealed) throws Exception {
        int c = sealed == null ? -1 : sealed.indexOf(':');
        if (c <= 0) throw new Exception("Niet versleuteld");
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, unb64(sealed.substring(0, c))));
        return new String(ci.doFinal(unb64(sealed.substring(c + 1))), StandardCharsets.UTF_8);
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Standaard-base64 met opvulling (zoals android.util.Base64 NO_WRAP); java.util.Base64 kan pas vanaf Android 8. */
    static String b64(byte[] b) {
        StringBuilder s = new StringBuilder((b.length + 2) / 3 * 4);
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xff) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xff) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xff : 0);
            s.append(B64[n >> 18 & 63]).append(B64[n >> 12 & 63]);
            s.append(i + 1 < b.length ? B64[n >> 6 & 63] : '=').append(i + 2 < b.length ? B64[n & 63] : '=');
        }
        return s.toString();
    }

    static byte[] unb64(String s) {
        String t = s.trim();
        if (t.length() % 4 != 0) throw new IllegalArgumentException("base64");
        int pad = t.endsWith("==") ? 2 : t.endsWith("=") ? 1 : 0;
        byte[] out = new byte[t.length() / 4 * 3 - pad];
        int o = 0;
        for (int i = 0; i < t.length(); i += 4) {
            int n = 0;
            for (int j = 0; j < 4; j++) {
                char ch = t.charAt(i + j);
                int v = ch == '=' ? 0 : ch >= 'A' && ch <= 'Z' ? ch - 'A' : ch >= 'a' && ch <= 'z' ? ch - 'a' + 26 : ch >= '0' && ch <= '9' ? ch - '0' + 52 : ch == '+' ? 62 : ch == '/' ? 63 : -1;
                if (v < 0 || (ch == '=' && i + j < t.length() - pad)) throw new IllegalArgumentException("base64");
                n = n << 6 | v;
            }
            if (o < out.length) out[o++] = (byte) (n >> 16);
            if (o < out.length) out[o++] = (byte) (n >> 8);
            if (o < out.length) out[o++] = (byte) n;
        }
        return out;
    }

    // ---------- backup-namen ----------

    /** Archiefnamen in Versleuteld/, nieuwste eerst; geen halve (.part). */
    static List<String> archives(List<String> names) {
        List<String> l = new ArrayList<>();
        for (String n : names) if (n != null && n.startsWith("backup-") && n.endsWith(".rtb")) l.add(n);
        Collections.sort(l, (a, b) -> b.compareTo(a)); // backup-JJJJ-MM-DD_UUMM(ss): op naam = op tijd
        return l;
    }

    /** Welke backup voorstellen: de nieuwste volledige; anders de nieuwste. */
    static String recommended(List<String> sortedNewestFirst) {
        for (String n : sortedNewestFirst) if (!n.endsWith(Rotate.PARTIAL + ".rtb")) return n;
        return sortedNewestFirst.isEmpty() ? null : sortedNewestFirst.get(0);
    }
}
