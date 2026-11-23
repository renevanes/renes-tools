package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Grote bestanden één keer bewaren in de versleutelde backup (wens van Rene: kleinere backups).
 *
 * Elke nacht komt er een nieuw archief (.rtb) bij, maar opnames, kluisdocumenten, foto's en boeken veranderen bijna nooit.
 * Een bestand van minstens {@link #MIN} gaat daarom niet in het archief zelf, maar als eigen versleuteld bestand in de map
 * Versleuteld/{@link #DIR}. De naam hangt af van de inhoud (HMAC met de backup-sleutel, dus zonder de sleutel niet te
 * raden): dezelfde inhoud staat er één keer, hoeveel archieven er ook naar verwijzen. Het archief krijgt een lijst
 * ({@link #LIST}: pad, grootte, SHA-256, naam) en blijft verder hetzelfde, dus alle bestaande code die archieven leest
 * werkt via {@link Lezer} zoals altijd; oude archieven (zonder lijst) ook.
 *
 * Een groot bestand: dezelfde versleuteling als het archief ({@link Vault}, zelfde wachtwoord), van tekst en andere
 * goed inpakbare bestanden eerst gzip. Na het schrijven wordt het eerst helemaal teruggelezen en gecontroleerd, pas
 * dan krijgt het zijn echte naam. Opruimen ({@link #overbodig}) haalt alleen weg wat geen enkel archief meer gebruikt.
 * Puur Java (geen Android), zodat het op een gewone JVM getest kan worden (tests/jvm/…/BlokkenTest.java).
 */
final class Blokken {

    private Blokken() { }

    /** Map in Versleuteld/ met de grote bestanden. */
    static final String DIR = "Grote bestanden";
    /** In het archief: welke bestanden in DIR staan. Altijd onverpakt (STORED), zodat ontsleutelen.html hem zo kan lezen. */
    static final String LIST = "grote-bestanden.json";
    static final String EXT = ".blok", PART = ".blok.part";
    static final String FORMAT = "renes-tools-grote-bestanden";
    static final int VERSION = 1;
    /** Vanaf deze grootte apart; kleinere bestanden gewoon in het archief (anders heel veel losse bestanden). */
    static final long MIN = 32 * 1024;
    /** sleutel-id (8 hex) - inhoud-id (32 hex) .blok */
    static final Pattern NAME = Pattern.compile("([0-9a-f]{8})-([0-9a-f]{32})\\.blok");
    static final int MAX_ITEMS = 500_000;
    static final long MAX_LIST = 64L * 1024 * 1024;

    /** Kon een groot bestand niet worden opgeslagen (map, netwerk). */
    static final class OpslagFout extends IOException {
        OpslagFout(String m, Throwable c) { super(m, c); }
    }

    /**
     * Een groot bestand uit de map was niet te lezen (ontbreekt, beschadigd, geen verbinding). Apart van andere fouten,
     * zodat terugzetten dat ene bestand kan overslaan, en een fout bij het wegschrijven (telefoon vol) niet.
     */
    static final class LeesFout extends IOException {
        LeesFout(IOException c) { super(c.getMessage(), c); }
    }

    /** Elke leesfout van een groot bestand als LeesFout. */
    static final class LeesStroom extends FilterInputStream {
        LeesStroom(InputStream in) { super(in); }
        @Override public int read() throws IOException { try { return in.read(); } catch (LeesFout e) { throw e; } catch (IOException e) { throw new LeesFout(e); } }
        @Override public int read(byte[] b, int o, int l) throws IOException { try { return in.read(b, o, l); } catch (LeesFout e) { throw e; } catch (IOException e) { throw new LeesFout(e); } }
        @Override public long skip(long n) throws IOException { try { return in.skip(n); } catch (LeesFout e) { throw e; } catch (IOException e) { throw new LeesFout(e); } }
        @Override public void close() { }
    }

    // ---------- namen ----------

    static byte[] hmac(byte[] key, String text) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Namen bij een backup-sleutel: zelfde wachtwoord (en zout) = zelfde namen, ook op een andere telefoon. */
    static final class Ids {
        final String kid;
        private final byte[] idKey;
        Ids(Vault.Key k) {
            kid = AppData.hex(hmac(k.key, "rtb-blok-kid-v1")).substring(0, 8);
            idKey = hmac(k.key, "rtb-blok-id-v1");
        }
        String naam(String sha, boolean z) { return kid + "-" + AppData.hex(hmac(idKey, sha + (z ? ":z" : ""))).substring(0, 32) + EXT; }
    }

    static String kid(String naam) { Matcher m = NAME.matcher(naam == null ? "" : naam); return m.matches() ? m.group(1) : null; }

    /** Klein maar belangrijk voor terugzetten: altijd in het archief zelf (dan hangt terugzetten nooit van één los bestand af). */
    static final Set<String> IN_ARCHIEF = new HashSet<>(java.util.Arrays.asList(AppData.MANIFEST, AppData.PREFS, AppData.SECRETS,
            AppData.KLUIS_INDEX, AppData.MODELS, AppData.STATE, AppData.SIGNATURE, AppData.CPAP));

    /** Mag dit bestand apart (als het groot genoeg is)? Niet bovenin het archief, niet de onderdelen hierboven, en alleen een veilige naam. */
    static boolean apart(String pad) { return pad != null && pad.indexOf('/') > 0 && AppData.safeRel(pad) != null && !IN_ARCHIEF.contains(pad); }

    /** Inpakken (gzip) loont: niet bij al samengeperste bestanden (geluid, foto's, PDF's, zip). */
    static boolean inpakken(String pad) { return !AppData.compressed(pad); }

    /** Grootte van een versleuteld bestand van n bytes (zonder gzip): kop + per blok van 64 kB lengte en tag. */
    static long versleuteld(long n) { return Vault.HEADER + (n / Vault.CHUNK + 1) * (4L + Vault.TAG) + n; }

    // ---------- sinds wanneer (merkteken) en opzij gezette bestanden ----------

    /**
     * Merkteken in DIR, gemaakt vóór het eerste grote bestand en vóór de naam van dat archief: sinds-JJJJ-MM-DD_UUMM.
     * Een archief met een (naam)tijd van ruim daarvoor kan geen grote bestanden gebruiken. De tijd staat in de naam,
     * niet in de wijzigingstijd van het bestand (die verandert bij kopiëren of verplaatsen).
     */
    static final String SINDS = "sinds-";
    static final Pattern SINDS_NAAM = Pattern.compile("sinds-(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})(\\d{2})");
    /** Marge rond het merkteken (klok, tijdzone). */
    static final long MARGE = 2L * 86_400_000L;

    static String sindsNaam(long t) { return SINDS + new java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(new java.util.Date(t)); }

    /** Tijd uit een merkteken (wandklok van de telefoon, zoals de namen van de archieven); -1 als het er geen is. */
    static long sindsTijd(String naam) {
        Matcher m = SINDS_NAAM.matcher(naam == null ? "" : naam);
        if (!m.matches()) return -1;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US);
        f.setLenient(false);
        try { return f.parse(naam.substring(SINDS.length())).getTime(); } catch (java.text.ParseException e) { return -1; }
    }

    /** Het vroegste merkteken in de map (-1 = geen: dan is niet zeker te zeggen welke archieven oud zijn). */
    static long sinds(Collection<String> namen) {
        long t = -1;
        for (String n : namen) { long x = sindsTijd(n); if (x > 0 && (t < 0 || x < t)) t = x; }
        return t;
    }

    /** Opzij gezet na twee nachten beschadigd: naam.kapot-JJJJMMDD (datum in de naam, niet de wijzigingstijd). */
    static final Pattern KAPOT = Pattern.compile("(.+\\.blok)\\.kapot-(\\d{8})");

    /** Wanneer een groot bestand opzij gezet is (uit de naam), of -1. */
    static long kapotTijd(String naam) {
        Matcher m = KAPOT.matcher(naam == null ? "" : naam);
        if (!m.matches()) return -1;
        String d = m.group(2);
        return sindsTijd(SINDS + d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8) + "_0000");
    }

    static String kapotNaam(String naam, long t) { return naam + ".kapot-" + new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date(t)); }

    // ---------- de lijst in het archief ----------

    /** Eén groot bestand in een archief: pad in het archief, grootte en SHA-256 van de inhoud, naam in DIR. */
    static final class Item {
        final String n, h, b; final long s, e; final boolean z;
        Item(String pad, long size, String sha, String naam, boolean gz, long enc) { n = pad; s = size; h = sha; b = naam; z = gz; e = enc; }
    }

    static String json(String kid, List<Item> items) throws Exception {
        JSONArray a = new JSONArray();
        for (Item i : items) a.put(new JSONObject().put("n", i.n).put("s", i.s).put("h", i.h).put("b", i.b).put("z", i.z).put("e", i.e));
        return new JSONObject().put("format", FORMAT).put("version", VERSION).put("kid", kid).put("items", a).toString();
    }

    /** Leest de lijst uit een (dus niet te vertrouwen) archief; gooit bij alles wat niet klopt. */
    static List<Item> parse(String text) throws IOException {
        try {
            JSONObject o = new JSONObject(text);
            if (!FORMAT.equals(o.optString("format"))) throw new IOException("De lijst met grote bestanden is beschadigd");
            int v = o.optInt("version", 0);
            if (v < 1) throw new IOException("De lijst met grote bestanden is beschadigd");
            if (v > VERSION) throw new IOException("Deze backup is gemaakt met een nieuwere versie van Tools; werk de app eerst bij");
            String kid = o.optString("kid");
            JSONArray a = o.getJSONArray("items");
            if (a.length() > MAX_ITEMS) throw new IOException("De lijst met grote bestanden is te groot");
            List<Item> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                String n = x.getString("n"), h = x.getString("h"), b = x.getString("b");
                long s = x.getLong("s"), e = x.optLong("e", 0);
                if (n.equals(LIST) || s < 0 || s > AppBackup.MAX_ENTRY || e < 0 || !h.matches("[0-9a-f]{64}") || !kid.equals(kid(b)))
                    throw new IOException("De lijst met grote bestanden is beschadigd");
                // Een vreemde naam (schrijft deze versie nooit apart): alleen dat ene bestand overslaan, niet de hele backup
                if (AppData.safeRel(n) == null || !seen.add(n)) continue;
                out.add(new Item(n, s, h, b, x.getBoolean("z"), e));
            }
            return out;
        } catch (org.json.JSONException e) { throw new IOException("De lijst met grote bestanden is beschadigd"); }
    }

    // ---------- opslag (de map Grote bestanden; op de telefoon via de backup-map, in tests een gewone map) ----------

    interface Opslag {
        /** Alle grote bestanden die er staan: naam → grootte (-1 = onbekend). */
        Map<String, Long> lijst() throws IOException;
        /** Nieuw bestand onder een tijdelijke naam; pas na klaar() onder de echte naam. */
        Schrijf maak(String naam) throws IOException;
        InputStream open(String naam) throws IOException;
        void weg(String naam) throws IOException;
    }

    interface Schrijf {
        OutputStream uit() throws IOException;
        /** Het net geschreven (nog tijdelijke) bestand opnieuw lezen, ter controle. */
        InputStream terug() throws IOException;
        void klaar() throws IOException;
        void afbreken();
    }

    /** Leest een groot bestand en controleert alles: sleutel, versleuteling (ook afgekapt), grootte en SHA-256. */
    static InputStream lees(InputStream raw, Vault.Key k, boolean z, long size, String sha) throws IOException {
        byte[] h;
        try { h = Vault.readHeader(raw); }
        catch (IOException e) { raw.close(); throw new IOException("Groot bestand onvolledig gelezen"); }
        if (!Vault.matches(k, h)) { raw.close(); throw new IOException("Groot bestand hoort bij een ander wachtwoord"); }
        Vault.In v = new Vault.In(raw, h, k);
        InputStream in = v;
        if (z) {
            try { in = new GZIPInputStream(v, 1 << 16); }
            catch (IOException e) { v.close(); throw new IOException("Groot bestand beschadigd"); }
        }
        return new Controle(in, v, size, sha);
    }

    /** Telt en controleert de inhoud; aan het eind ook het slot van de versleuteling (afgekapt = fout). */
    static final class Controle extends FilterInputStream {
        private final Vault.In vault; private final long size; private final String sha;
        private final MessageDigest md = AppData.sha256();
        private long n = 0; private boolean done = false;
        Controle(InputStream in, Vault.In v, long size, String sha) { super(in); vault = v; this.size = size; this.sha = sha; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int r = read(b, 0, 1); return r < 0 ? -1 : b[0] & 0xff; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            int r;
            try { r = in.read(b, off, len); }
            catch (java.util.zip.ZipException e) { throw new IOException("Groot bestand beschadigd"); }
            catch (IOException e) { throw vertaal(e); }
            if (r > 0) {
                md.update(b, off, r);
                n += r;
                if (n > size) throw new IOException("Groot bestand beschadigd");
                return r;
            }
            if (r < 0) {
                byte[] buf = new byte[1 << 14];
                try { while (vault.read(buf, 0, buf.length) >= 0) { /* het slot van de versleuteling ook lezen */ } }
                catch (IOException e) { throw vertaal(e); }
                done = true;
                if (n != size || !AppData.hex(md.digest()).equals(sha)) throw new IOException("Groot bestand beschadigd");
            }
            return r;
        }
        @Override public long skip(long k) throws IOException {
            byte[] buf = new byte[(int) Math.min(1 << 14, Math.max(1, k))];
            long left = k;
            while (left > 0) { int r = read(buf, 0, (int) Math.min(buf.length, left)); if (r < 0) break; left -= r; }
            return k - left;
        }
        @Override public boolean markSupported() { return false; }

        /** De meldingen van de versleuteling over het archief gaan hier over het grote bestand (schade blijft schade). */
        static IOException vertaal(IOException e) {
            String m = String.valueOf(e.getMessage());
            if (m.startsWith("Het archief is beschadigd")) return new IOException("Groot bestand beschadigd", e);
            if (m.startsWith("Het archief is onvolledig")) return new IOException("Groot bestand onvolledig gelezen", e);
            return e;
        }
    }

    /**
     * Is dit zeker schade aan de inhoud, en niet bijv. een verbinding die halverwege wegviel? Alleen een versleuteld blok
     * dat niet klopt (GCM-tag), een kop die niet bij de sleutel past, of na het volledig lezen een andere grootte of
     * SHA-256. Te vroeg afgelopen ("onvolledig") telt niet: dat kan ook de verbinding zijn.
     */
    static boolean schade(IOException e) {
        String m = String.valueOf(e.getMessage());
        return m.equals("Groot bestand beschadigd") || m.equals("Het archief is beschadigd") || m.equals("Verkeerd wachtwoord")
                || m.equals("Groot bestand hoort bij een ander wachtwoord");
    }

    /** Leest alles en gooit bij een fout (controle van een groot bestand). */
    static void leesHelemaal(InputStream in) throws IOException {
        try { byte[] buf = new byte[1 << 16]; while (in.read(buf) >= 0) { /* controleren */ } }
        finally { try { in.close(); } catch (IOException ignored) { } }
    }

    // ---------- schrijven ----------

    /** Telt wat er werkelijk de opslag in gaat. */
    static final class Teller extends OutputStream {
        private final OutputStream out; long n;
        Teller(OutputStream o) { out = o; }
        @Override public void write(int b) throws IOException { out.write(b); n++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); n += len; }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }
    }

    /** Hoort bij één archief: bewaart grote bestanden (of hergebruikt ze) en houdt de lijst bij. */
    static final class Schrijver {
        final Opslag opslag; final Vault.Key key; final Ids ids;
        private Map<String, Long> er;
        final List<Item> items = new ArrayList<>();
        int nieuw, hergebruikt;
        long nieuwBytes, hergebruiktBytes, opgeslagen;

        /** Bekende versleutelde grootte per naam (van eerder schrijven): een afwijkend bestand wordt dan opnieuw geschreven. */
        final Map<String, Long> bekend = new HashMap<>();

        Schrijver(Opslag o, Vault.Key k) { opslag = o; key = k; ids = new Ids(k); }

        Map<String, Long> er() throws IOException { if (er == null) er = new HashMap<>(opslag.lijst()); return er; }

        /** Het bestand (al compleet in bron, n bytes, SHA-256 sha) bewaren voor pad. Gooit OpslagFout als het niet lukt. */
        Item bewaar(String pad, File bron, long n, String sha) throws IOException {
            boolean z = inpakken(pad);
            String naam = ids.naam(sha, z);
            Long have;
            try { have = er().get(naam); } catch (IOException e) { throw new OpslagFout("De map " + DIR + " is niet te lezen", e); }
            long verwacht = z ? (bekend.containsKey(naam) ? bekend.get(naam) : -1) : versleuteld(n);
            if (have != null && (have < 0 || have > Vault.HEADER) && (verwacht < 0 || have < 0 || have == verwacht)) {
                hergebruikt++; hergebruiktBytes += n;
                Item it = new Item(pad, n, sha, naam, z, Math.max(0, have));
                items.add(it);
                return it;
            }
            Schrijf s;
            try { s = opslag.maak(naam); } catch (IOException e) { throw new OpslagFout("Een groot bestand opslaan lukt niet", e); }
            long enc;
            try {
                Teller t = new Teller(s.uit());
                try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(bron), 1 << 16)) {
                    Vault.Out v = new Vault.Out(t, key);
                    OutputStream w = z ? new GZIPOutputStream(v, 1 << 16) : v;
                    try {
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = in.read(buf)) > 0) w.write(buf, 0, r);
                    } finally { w.close(); }
                }
                enc = t.n;
                if (!z && enc != verwacht) throw new IOException("Groot bestand onvolledig geschreven");
                // Pas na een volledige controle (teruglezen, ontsleutelen, SHA-256) krijgt het zijn echte naam
                leesHelemaal(lees(s.terug(), key, z, n, sha));
                s.klaar();
            } catch (IOException e) {
                s.afbreken();
                throw new OpslagFout("Een groot bestand opslaan lukt niet: " + e.getMessage(), e);
            }
            er.put(naam, enc);
            bekend.put(naam, enc);
            nieuw++; nieuwBytes += n; opgeslagen += enc;
            Item it = new Item(pad, n, sha, naam, z, enc);
            items.add(it);
            return it;
        }

        /** Alle namen die dit archief gebruikt. */
        Set<String> namen() { Set<String> s = new HashSet<>(); for (Item i : items) s.add(i.b); return s; }
    }

    // ---------- lezen ----------

    /** Haalt de inhoud van een groot bestand op (gecontroleerd). */
    interface Bron { InputStream open(Item it) throws IOException; }

    /** Bron uit een opslag met de sleutel van het archief. */
    static Bron bron(final Opslag o, final Vault.Key k) {
        return it -> {
            if (o == null) throw new IOException("De map " + DIR + " ontbreekt; kies de backup-map waar deze backup in staat");
            InputStream raw;
            try { raw = o.open(it.b); }
            catch (java.io.FileNotFoundException e) { throw new IOException("Groot bestand ontbreekt in " + DIR + ": " + AppData.cut(it.n)); }
            if (raw == null) throw new IOException("Groot bestand ontbreekt in " + DIR + ": " + AppData.cut(it.n));
            return lees(new java.io.BufferedInputStream(raw, 1 << 16), k, it.z, it.s, it.h);
        };
    }

    /**
     * Leest een archief zoals altijd (ZipInputStream), en daarna de grote bestanden uit de lijst alsof ze erin stonden.
     * De lijst zelf komt niet langs. Bij een groot bestand haalt inhoud() het pas op als je erom vraagt; naam(), extern()
     * en item() zijn er meteen (handig om te tellen zonder alles op te halen).
     */
    static final class Lezer implements Closeable {
        private final ZipInputStream z;
        private final InputStream rest;
        private final Bron bron;
        private List<Item> ext = Collections.emptyList();
        private boolean zipKlaar = false;
        private int idx = -1;
        private String naam;
        private Item cur;
        private InputStream open;

        /** rest: de onderliggende (versleutelde) stroom; aan het eind van de zip helemaal gelezen (slot controleren). */
        Lezer(ZipInputStream z, InputStream rest, Bron bron) { this.z = z; this.rest = rest; this.bron = bron; }

        boolean volgende() throws IOException {
            sluitOpen();
            if (!zipKlaar) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    if (LIST.equals(e.getName())) { ext = parse(new String(klein(z, MAX_LIST), StandardCharsets.UTF_8)); continue; }
                    naam = e.getName(); cur = null;
                    return true;
                }
                zipKlaar = true;
                if (rest != null) { byte[] buf = new byte[1 << 16]; while (rest.read(buf) >= 0) { /* slot van het archief */ } }
            }
            if (++idx < ext.size()) { cur = ext.get(idx); naam = cur.n; return true; }
            naam = null; cur = null;
            return false;
        }

        String naam() { return naam; }
        boolean extern() { return cur != null; }
        Item item() { return cur; }
        /** De grote bestanden van dit archief (compleet zodra volgende() één keer false gaf). */
        List<Item> lijst() { return ext; }

        /** De inhoud van het huidige bestand; niet zelf sluiten (dat doet de lezer). */
        InputStream inhoud() throws IOException {
            if (cur == null) return new Houd(z);
            sluitOpen();
            try { open = bron.open(cur); }
            catch (LeesFout e) { throw e; }
            catch (IOException e) { throw new LeesFout(e); }
            return new LeesStroom(open);
        }

        private void sluitOpen() { if (open != null) { try { open.close(); } catch (IOException ignored) { } open = null; } }

        @Override public void close() throws IOException { sluitOpen(); z.close(); }
    }

    /** Stroom die bij sluiten openblijft (de lezer sluit zelf). */
    static final class Houd extends FilterInputStream {
        Houd(InputStream in) { super(in); }
        @Override public void close() { }
    }

    static byte[] klein(InputStream in, long max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > max) throw new IOException("Te groot in de backup"); }
        return b.toByteArray();
    }

    // ---------- opruimen ----------

    /**
     * Welke grote bestanden weg mogen: alleen eigen namen (NAME) die geen enkel bewaard archief gebruikt. Namen van een
     * andere sleutel (ander wachtwoord) blijven zolang er archieven zijn die deze sleutel niet kan lezen (vreemd).
     */
    static List<String> overbodig(Collection<String> er, Set<String> gebruikt, String kid, boolean vreemd) {
        List<String> out = new ArrayList<>();
        for (String n : er) {
            String k = kid(n);
            if (k == null || gebruikt.contains(n)) continue;
            if (!k.equals(kid) && vreemd) continue;
            out.add(n);
        }
        Collections.sort(out);
        return out;
    }
}
