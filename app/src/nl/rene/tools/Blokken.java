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
    /** Na zoveel keer apart opslaan mislukt gaan de volgende grote bestanden van die backup in het archief zelf. */
    static final int MAX_FOUTEN = 3;

    /**
     * Waarom apart opslaan niet lukte, in vaste woorden: de meldingen zelf kunnen van de mappen-app komen en de naam van
     * de backup-map bevatten (die hoort niet in het foutlogboek). Zuiver.
     */
    static String reden(Throwable e) {
        if (e == null) return "onbekend";
        String m = String.valueOf(e.getMessage());
        Throwable oorzaak = e instanceof OpslagFout && e.getCause() != null ? e.getCause() : e;
        if (m.contains(ONVOLLEDIG)) return "onvolledig teruggelezen";
        if (m.contains("onvolledig geschreven")) return "onvolledig geschreven";
        if (m.contains("ander wachtwoord")) return "ander wachtwoord";
        if (m.contains(AFGEKAPT) || m.contains("Groot bestand beschadigd")) return "beschadigd teruggelezen";
        if (m.contains("De map " + DIR + " is niet te lezen")) return "map niet te lezen";
        if (m.contains("nog aan het laden")) return "map laadt nog";
        if (m.contains("Bestand maken lukt niet") || m.contains("Map maken lukt niet")) return "maken lukt niet";
        if (m.contains("Schrijven lukt niet")) return "schrijven lukt niet";
        if (m.contains("Teruglezen lukt niet")) return "teruglezen lukt niet";
        if (m.contains("Hernoemen lukt niet") || m.contains("opzij zetten lukt niet")) return "hernoemen lukt niet";
        if (m.contains("Kopie onvolledig")) return "kopie onvolledig";
        if (oorzaak instanceof SecurityException) return "geen toegang";
        if (oorzaak instanceof java.io.FileNotFoundException) return "niet gevonden";
        if (oorzaak instanceof IOException) return "verbinding of opslag";
        String n = oorzaak.getClass().getSimpleName();
        return n.isEmpty() ? "onbekend" : n;
    }
    /** sleutel-id (8 hex) - inhoud-id (32 hex) .blok */
    static final Pattern NAME = Pattern.compile("([0-9a-f]{8})-([0-9a-f]{32})\\.blok");
    static final int MAX_ITEMS = 500_000;
    static final long MAX_LIST = 64L * 1024 * 1024;

    /** Kon een groot bestand niet worden opgeslagen (map, netwerk). */
    static final class OpslagFout extends IOException {
        OpslagFout(String m, Throwable c) { super(m, c); }
    }

    /**
     * Een groot bestand ontbreekt zeker (de map is gelezen en het staat er niet in) of is zeker beschadigd. Apart van
     * andere fouten, zodat terugzetten dat ene bestand kan overslaan. Geen verbinding, een map die niet te lezen is of
     * een fout bij het wegschrijven (telefoon vol) is geen LeesFout: dan stopt terugzetten ("probeer het opnieuw").
     */
    static final class LeesFout extends IOException {
        LeesFout(IOException c) { super(c.getMessage(), c); }
    }

    /** Opslag.open: de map is gelezen en dit bestand staat er zeker niet in. */
    static final class Ontbreekt extends java.io.FileNotFoundException {
        Ontbreekt(String m) { super(m); }
    }

    /** Is dit een fout waarbij terugzetten alleen dit grote bestand overslaat (zeker weg of zeker beschadigd)? */
    static boolean zeker(IOException e) { return e instanceof LeesFout || e instanceof Ontbreekt || schade(e); }

    /** Zekere fouten bij het lezen van een groot bestand als LeesFout; de rest (verbinding) blijft wat het was. */
    static final class LeesStroom extends FilterInputStream {
        LeesStroom(InputStream in) { super(in); }
        static IOException fout(IOException e) { return e instanceof LeesFout || !zeker(e) ? e : new LeesFout(e); }
        @Override public int read() throws IOException { try { return in.read(); } catch (IOException e) { throw fout(e); } }
        @Override public int read(byte[] b, int o, int l) throws IOException { try { return in.read(b, o, l); } catch (IOException e) { throw fout(e); } }
        @Override public long skip(long n) throws IOException { try { return in.skip(n); } catch (IOException e) { throw fout(e); } }
        @Override public void close() { }
    }

    /**
     * "Onvolledig gelezen" kan de verbinding zijn, of schade die er net zo uitziet. Zeker schade (AFGEKAPT) als het
     * bestand in de map korter is dan geschreven, of als een tweede keer lezen het hele bestand ophaalt (zo groot als de
     * map zegt) en het toch weer te vroeg ophoudt. Anders blijft het een gewone fout (opnieuw proberen).
     */
    static final class Nalees extends FilterInputStream {
        final Opslag o; final Vault.Key k; final String naam; final boolean z; final long size, listed, enc; final String sha;
        Nalees(InputStream in, Opslag o, Vault.Key k, String naam, boolean z, long size, String sha, long listed, long enc) {
            super(in); this.o = o; this.k = k; this.naam = naam; this.z = z; this.size = size; this.sha = sha; this.listed = listed; this.enc = enc;
        }
        IOException fout(IOException e) {
            if (!ONVOLLEDIG.equals(e.getMessage())) return e;
            if (listed >= 0 && enc > 0 && listed < enc) return new IOException(AFGEKAPT, e);
            return vastKapot(o, naam, k, z, size, sha, listed) ? new IOException(AFGEKAPT, e) : e;
        }
        @Override public int read() throws IOException { try { return in.read(); } catch (IOException e) { throw fout(e); } }
        @Override public int read(byte[] b, int off, int l) throws IOException { try { return in.read(b, off, l); } catch (IOException e) { throw fout(e); } }
        @Override public long skip(long n) throws IOException { try { return in.skip(n); } catch (IOException e) { throw fout(e); } }
    }

    /** Telt wat er gelezen wordt. */
    static final class TelIn extends FilterInputStream {
        long n;
        TelIn(InputStream in) { super(in); }
        @Override public int read() throws IOException { int r = in.read(); if (r >= 0) n++; return r; }
        @Override public int read(byte[] b, int off, int l) throws IOException { int r = in.read(b, off, l); if (r > 0) n += r; return r; }
    }

    /**
     * Nog een keer helemaal lezen: houdt het weer te vroeg op terwijl het hele bestand (zo groot als de map zegt) gelezen
     * is, of klopt de versleuteling niet, dan is het zeker schade. Lukt het nu wel, of valt de verbinding weg: geen schade.
     */
    static boolean vastKapot(Opslag o, String naam, Vault.Key k, boolean z, long size, String sha, long listed) {
        TelIn in = null;
        try {
            InputStream raw = o.open(naam);
            if (raw == null) return false;
            in = new TelIn(raw);
            leesHelemaal(lees(new java.io.BufferedInputStream(in, 1 << 16), k, z, size, sha));
            return false;
        } catch (IOException e) {
            if (schade(e)) return true;
            return in != null && ONVOLLEDIG.equals(e.getMessage()) && listed > 0 && in.n >= listed;
        }
    }

    static final String ONVOLLEDIG = "Groot bestand onvolledig gelezen", AFGEKAPT = "Groot bestand afgekapt";

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
        // De kop: te kort = onvolledig (kan de verbinding zijn), wel compleet maar fout = beschadigd
        byte[] h = new byte[Vault.HEADER];
        int got = 0;
        try { while (got < h.length) { int r = raw.read(h, got, h.length - got); if (r < 0) break; got += r; } }
        catch (IOException e) { raw.close(); throw e; }
        if (got < h.length) { raw.close(); throw new IOException(ONVOLLEDIG); }
        try { Vault.readHeader(new java.io.ByteArrayInputStream(h)); }
        catch (IOException e) { raw.close(); throw new IOException("Groot bestand beschadigd"); }
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
            if (m.startsWith("Het archief is onvolledig")) return new IOException(ONVOLLEDIG, e);
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
        return m.equals("Groot bestand beschadigd") || m.equals(AFGEKAPT) || m.equals("Het archief is beschadigd") || m.equals("Verkeerd wachtwoord")
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
            boolean goed = have != null && (have < 0 || have > Vault.HEADER) && (verwacht < 0 || have < 0 || have == verwacht);
            // Andere grootte dan verwacht (een andere telefoon pakt anders in, of de map meldt een oude grootte): eerst
            // helemaal nalezen; klopt de inhoud, dan gewoon hergebruiken (geen strijd tussen twee telefoons)
            if (!goed && have != null && have > Vault.HEADER && klopt(naam, z, n, sha)) { goed = true; bekend.put(naam, have); }
            if (goed) {
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
                terugControle(s, z, n, sha);
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

        /**
         * Wachttijden (ms) voor nog eens teruglezen. Een cloudmap (Google Drive) heeft een net geschreven bestand soms
         * nog niet helemaal klaarstaan; een tel later wel. Zonder wachten ging de rest van de backup in het archief zelf.
         */
        static long[] TERUG_WACHT = {2_000L, 10_000L};
        /** Hoeveel er per backup in totaal op teruglezen gewacht mag worden (de nachtelijke taak heeft niet veel tijd). */
        static long WACHT_TEGOED = 180_000L;
        /** Hoe vaak teruglezen pas bij een volgende poging klopte. */
        int terugOpnieuw;
        long gewacht;
        /** Is de backup gestopt? Dan niet meer wachten (null = nooit). */
        java.util.function.BooleanSupplier gestopt;

        private void terugControle(Schrijf s, boolean z, long n, String sha) throws IOException {
            for (int i = 0; ; i++) {
                try { leesHelemaal(lees(s.terug(), key, z, n, sha)); if (i > 0) terugOpnieuw++; return; }
                catch (IOException e) {
                    // Een ander wachtwoord wordt niet beter van wachten; te kort of (nog) niet kloppend misschien wel
                    if (i >= TERUG_WACHT.length || "Groot bestand hoort bij een ander wachtwoord".equals(e.getMessage())
                            || gewacht + TERUG_WACHT[i] > WACHT_TEGOED || (gestopt != null && gestopt.getAsBoolean())) throw e;
                    try { Thread.sleep(TERUG_WACHT[i]); }
                    catch (InterruptedException x) { Thread.currentThread().interrupt(); throw e; }
                    gewacht += TERUG_WACHT[i];
                }
            }
        }

        private boolean klopt(String naam, boolean z, long n, String sha) {
            try {
                InputStream raw = opslag.open(naam);
                if (raw == null) return false;
                leesHelemaal(lees(new java.io.BufferedInputStream(raw, 1 << 16), key, z, n, sha));
                return true;
            } catch (IOException e) { return false; }
        }

        /** Alle namen die dit archief gebruikt. */
        Set<String> namen() { Set<String> s = new HashSet<>(); for (Item i : items) s.add(i.b); return s; }
    }

    // ---------- lezen ----------

    /** Haalt de inhoud van een groot bestand op (gecontroleerd). */
    interface Bron { InputStream open(Item it) throws IOException; }

    /** Bron uit een opslag met de sleutel van het archief. */
    static Bron bron(final Opslag o, final Vault.Key k) {
        // De lijst van de map één keer (niet per groot bestand: dat zou bij duizenden bestanden traag worden)
        final java.util.concurrent.atomic.AtomicReference<Map<String, Long>> lijst = new java.util.concurrent.atomic.AtomicReference<>();
        return it -> {
            if (o == null) throw new IOException("De map " + DIR + " ontbreekt; kies de backup-map waar deze backup in staat");
            InputStream raw;
            try { raw = o.open(it.b); }
            catch (Ontbreekt e) { throw new Ontbreekt("Groot bestand ontbreekt in " + DIR + ": " + AppData.cut(it.n)); }
            catch (java.io.FileNotFoundException e) { throw new IOException("Groot bestand niet te openen (geen verbinding?): " + AppData.cut(it.n), e); }
            if (raw == null) throw new IOException("Groot bestand niet te openen: " + AppData.cut(it.n));
            // "Onvolledig gelezen": afgekapt of zeker kapot (zie Nalees), of de verbinding (dan gewoon een fout)
            Long in = null;
            try {
                Map<String, Long> m = lijst.get();
                if (m == null) { m = o.lijst(); lijst.set(m); }
                in = m.get(it.b);
            } catch (IOException ignored) { }
            long listed = in == null ? -1 : in;
            InputStream b;
            Nalees na = new Nalees(null, o, k, it.b, it.z, it.s, it.h, listed, it.e);
            try { b = lees(new java.io.BufferedInputStream(raw, 1 << 16), k, it.z, it.s, it.h); }
            catch (IOException e) { throw na.fout(e); }
            return new Nalees(b, o, k, it.b, it.z, it.s, it.h, listed, it.e);
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
            catch (IOException e) { throw LeesStroom.fout(e); }
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

    /** Een groot bestand gaat pas weg als het bij twee rondes, minstens zo ver uit elkaar, door niemand gebruikt werd. */
    static final long WACHT = 36L * 3_600_000L;
    /** Een archief dat de telefoon kent maar dat zo vaak achter elkaar niet in de map stond, telt niet meer mee. */
    static final int GEMIST = 3;
    /** In de lijst van een archief: gemaakt met een ander wachtwoord (de grote bestanden van die sleutel blijven). */
    static final String VREEMD = "*";

    /** Uitkomst van één ronde opruimen: wat nu weg mag, en wat (nog) gebruikt wordt. */
    static final class Ronde {
        final List<String> weg = new ArrayList<>();
        final Set<String> gebruikt = new HashSet<>();
    }

    /**
     * Eén ronde opruimen op de toestand (zuiver): archieven = wat elk bekend archief gebruikt, gemist = hoe vaak een
     * bekend archief achter elkaar niet in de map stond, kandidaten = sinds wanneer een groot bestand ongebruikt is.
     * Werkt die drie bij en geeft wat nu weg mag: ongebruikt bij een eerdere ronde, minstens WACHT geleden, en nog steeds.
     * Wie een bestand echt weghaalt, haalt het daarna zelf uit kandidaten (mislukt weghalen = de volgende keer opnieuw).
     */
    static Ronde ronde(JSONObject st, Set<String> inMap, Collection<String> er, String kid, long now) throws org.json.JSONException {
        JSONObject arch = st.getJSONObject("archieven");
        JSONObject gemist = st.optJSONObject("gemist"), kand = st.optJSONObject("kandidaten");
        if (gemist == null) { gemist = new JSONObject(); st.put("gemist", gemist); }
        if (kand == null) { kand = new JSONObject(); st.put("kandidaten", kand); }
        Ronde r = new Ronde();
        boolean vreemd = false;
        List<String> namen = new ArrayList<>();
        for (java.util.Iterator<String> it = arch.keys(); it.hasNext(); ) namen.add(it.next());
        for (String n : namen) {
            if (inMap.contains(n)) gemist.remove(n);
            else {
                int g = gemist.optInt(n, 0) + 1;
                if (g >= GEMIST) { arch.remove(n); gemist.remove(n); continue; }
                gemist.put(n, g);
            }
            JSONArray a = arch.getJSONArray(n);
            for (int i = 0; i < a.length(); i++) { String b = a.getString(i); if (VREEMD.equals(b)) vreemd = true; else r.gebruikt.add(b); }
        }
        // gemist van archieven die er niet meer zijn opruimen
        List<String> gm = new ArrayList<>();
        for (java.util.Iterator<String> it = gemist.keys(); it.hasNext(); ) gm.add(it.next());
        for (String n : gm) if (!arch.has(n)) gemist.remove(n);
        Set<String> nu = new HashSet<>(overbodig(er, r.gebruikt, kid, vreemd));
        List<String> km = new ArrayList<>();
        for (java.util.Iterator<String> it = kand.keys(); it.hasNext(); ) km.add(it.next());
        for (String n : km) if (!nu.contains(n)) kand.remove(n);
        List<String> sorted = new ArrayList<>(nu);
        Collections.sort(sorted);
        for (String n : sorted) {
            if (!kand.has(n)) { kand.put(n, now); continue; }
            long t = kand.optLong(n, now);
            if (t > now) { kand.put(n, now); continue; } // klok teruggezet: opnieuw beginnen met wachten
            if (now - t >= WACHT) r.weg.add(n);
        }
        return r;
    }
}
