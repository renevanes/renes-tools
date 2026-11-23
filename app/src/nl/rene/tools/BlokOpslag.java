package nl.rene.tools;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

/**
 * De map Versleuteld/Grote bestanden in de backup-map (zie {@link Blokken}): schrijven, lezen, opruimen en controleren.
 *
 * Google Drive kan een map twee keer maken; lezen kijkt daarom in alle mappen met die naam, schrijven gaat naar de map
 * die WaBackup kiest (en BackupMap voegt ze samen). Wat deze telefoon over de archieven weet (welke grote bestanden elk
 * archief gebruikt) staat in no_backup/grote-bestanden.json: dat scheelt elke nacht alle archieven opnieuw lezen. Een
 * archief dat daar niet in staat (van een andere telefoon, of na een nieuwe installatie) wordt gelezen als het nodig is.
 */
final class BlokOpslag implements Blokken.Opslag {

    final WaBackup.Dest dest;
    private WaBackup.DestDir hoofd;
    private Map<String, WaBackup.Child> alle;

    /** dest moet de echte map zijn (WaBackup.Dest.echt), niet die van een archief in wording. */
    BlokOpslag(WaBackup.Dest d) { dest = d; }

    /** Voor lezen: de huidige backup-map, of null als er geen is. */
    static BlokOpslag voor(Context c) {
        Uri t = WaBackup.destUri(c);
        return t == null ? null : new BlokOpslag(WaBackup.Dest.echt(c.getContentResolver(), t));
    }

    static final String PAD = Secure.DIR + "/" + Blokken.DIR;

    /** Alle bestanden in alle mappen Versleuteld/Grote bestanden (Drive kan er per ongeluk twee hebben). */
    Map<String, WaBackup.Child> alle() throws IOException {
        if (alle != null) return alle;
        Map<String, WaBackup.Child> m = new HashMap<>();
        try {
            WaBackup.DestDir v = dest.dir(Secure.DIR, false);
            if (v != null) {
                List<WaBackup.Child> dirs = new ArrayList<>();
                List<WaBackup.Child> dup = v.dups.get(Blokken.DIR);
                if (dup != null) dirs.addAll(dup);
                else if (v.kids.get(Blokken.DIR) != null) dirs.add(v.kids.get(Blokken.DIR));
                for (WaBackup.Child ch : dirs) {
                    if (!ch.dir) continue;
                    WaBackup.DestDir d = new WaBackup.DestDir();
                    d.uri = DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId);
                    dest.list(d);
                    for (Map.Entry<String, WaBackup.Child> e : d.kids.entrySet()) {
                        if (e.getValue().dir) continue;
                        WaBackup.Child had = m.get(e.getKey());
                        if (had == null || e.getValue().size > had.size) m.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(AppData.safeMsg(e, "De map " + Blokken.DIR + " is niet te lezen"), e); }
        alle = m;
        return m;
    }

    @Override public Map<String, Long> lijst() throws IOException {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, WaBackup.Child> e : alle().entrySet()) out.put(e.getKey(), e.getValue().size);
        return out;
    }

    private WaBackup.DestDir hoofd() throws IOException {
        if (hoofd != null) return hoofd;
        try { hoofd = dest.dir(PAD, true); }
        catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(AppData.safeMsg(e, "Map maken lukt niet: " + Blokken.DIR), e); }
        return hoofd;
    }

    @Override public Blokken.Schrijf maak(String naam) throws IOException {
        if (Blokken.kid(naam) == null) throw new IOException("Vreemde naam");
        WaBackup.DestDir d = hoofd();
        String part = naam + ".part";
        try {
            WaBackup.Child old = d.kids.remove(part);
            if (old != null && !old.dir) try { DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, old.docId)); } catch (Exception ignored) { }
            Uri u = WaBackup.create(dest, d.uri, "application/octet-stream", part);
            if (u == null) throw new IOException("Bestand maken lukt niet");
            OutputStream o = dest.cr.openOutputStream(u, "w");
            if (o == null) { try { DocumentsContract.deleteDocument(dest.cr, u); } catch (Exception ignored) { } throw new IOException("Schrijven lukt niet"); }
            return new SafSchrijf(this, d, u, naam, o);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(AppData.safeMsg(e, "Bestand maken lukt niet"), e); }
    }

    /** Eén nieuw groot bestand: eerst als naam.part, na de controle hernoemd (of gekopieerd als hernoemen niet kan). */
    static final class SafSchrijf implements Blokken.Schrijf {
        final BlokOpslag o; final WaBackup.DestDir d; final Uri part; final String naam; final Blokken.Teller out;
        SafSchrijf(BlokOpslag o, WaBackup.DestDir d, Uri part, String naam, OutputStream out) { this.o = o; this.d = d; this.part = part; this.naam = naam; this.out = new Blokken.Teller(new java.io.BufferedOutputStream(out, 1 << 16)); }

        @Override public OutputStream uit() { return out; }

        @Override public InputStream terug() throws IOException {
            InputStream in;
            try { in = o.dest.cr.openInputStream(part); } catch (Exception e) { throw new IOException(AppData.safeMsg(e, "Teruglezen lukt niet"), e); }
            if (in == null) throw new IOException("Teruglezen lukt niet");
            return new java.io.BufferedInputStream(in, 1 << 16);
        }

        @Override public void klaar() throws IOException {
            try {
                WaBackup.Child had = o.alle().get(naam);
                // Staat er al een met deze naam en precies deze grootte (een andere telefoon was ons net voor): die is
                // gelijk, de nieuwe weg. Met een andere grootte (afgebroken kopie): die gaat opzij, de nieuwe komt ervoor.
                if (had != null && had.size == out.n) { try { DocumentsContract.deleteDocument(o.dest.cr, part); } catch (Exception ignored) { } return; }
                if (had != null) {
                    Uri aside = WaBackup.rename(o.dest, DocumentsContract.buildDocumentUriUsingTree(o.dest.tree, had.docId), Blokken.kapotNaam(naam, System.currentTimeMillis()));
                    if (aside == null) throw new IOException("Oud bestand opzij zetten lukt niet");
                    o.alle().remove(naam);
                }
                Uri done = null;
                try { done = WaBackup.rename(o.dest, part, naam); } catch (Exception ignored) { }
                if (done == null) {
                    Uri u = WaBackup.create(o.dest, d.uri, "application/octet-stream", naam);
                    if (u == null) throw new IOException("Hernoemen lukt niet");
                    Blokken.Teller[] t = new Blokken.Teller[1];
                    try { DocumentWrite.copy(() -> o.dest.cr.openInputStream(part), () -> t[0] = new Blokken.Teller(o.dest.cr.openOutputStream(u, "w"))); }
                    catch (Exception e) { try { DocumentsContract.deleteDocument(o.dest.cr, u); } catch (Exception ignored) { } throw e; }
                    if (t[0] == null || t[0].n != out.n) { try { DocumentsContract.deleteDocument(o.dest.cr, u); } catch (Exception ignored) { } throw new IOException("Kopie onvolledig"); }
                    try { DocumentsContract.deleteDocument(o.dest.cr, part); } catch (Exception ignored) { }
                    done = u;
                }
                WaBackup.Child ch = new WaBackup.Child(DocumentsContract.getDocumentId(done), out.n, System.currentTimeMillis(), false);
                o.alle().put(naam, ch);
                d.kids.put(naam, ch);
            } catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException(AppData.safeMsg(e, "Hernoemen lukt niet"), e); }
        }

        @Override public void afbreken() {
            try { out.close(); } catch (Exception ignored) { }
            try { DocumentsContract.deleteDocument(o.dest.cr, part); } catch (Exception ignored) { }
        }
    }

    /**
     * Merkteken (sinds-…) vóór het eerste grote bestand: alleen in een map zonder grote bestanden. Staan er al grote
     * bestanden zonder merkteken (weggehaald, gekopieerd), dan komt er bewust geen: dan weet niemand meer sinds wanneer,
     * en leest opruimen liever de archieven.
     */
    void merkteken() throws Exception {
        Map<String, WaBackup.Child> m = alle();
        if (Blokken.sinds(m.keySet()) > 0) return;
        for (String n : m.keySet()) if (Blokken.kid(n) != null || Blokken.KAPOT.matcher(n).matches()) return;
        WaBackup.DestDir d = hoofd();
        String n = Blokken.sindsNaam(System.currentTimeMillis());
        Uri u = WaBackup.create(dest, d.uri, "application/octet-stream", n);
        if (u == null) throw new IOException("Merkteken maken lukt niet");
        try (OutputStream o = dest.cr.openOutputStream(u, "w")) { if (o != null) o.write('\n'); }
        WaBackup.Child ch = new WaBackup.Child(DocumentsContract.getDocumentId(u), 1, System.currentTimeMillis(), false);
        m.put(n, ch);
        d.kids.put(n, ch);
    }

    /** Sinds wanneer er grote bestanden apart zijn (het vroegste merkteken), of -1. */
    long sinds() throws IOException { return Blokken.sinds(alle().keySet()); }

    @Override public InputStream open(String naam) throws IOException {
        WaBackup.Child ch = alle().get(naam);
        if (ch == null || ch.dir) throw new FileNotFoundException(naam);
        try {
            InputStream in = dest.cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId));
            if (in == null) throw new FileNotFoundException(naam);
            return in;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(AppData.safeMsg(e, "Groot bestand niet te openen"), e); }
    }

    @Override public void weg(String naam) throws IOException {
        WaBackup.Child ch = alle().get(naam);
        if (ch == null) return;
        try {
            if (!DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId))) throw new IOException("Verwijderen geweigerd");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(AppData.safeMsg(e, "Verwijderen lukt niet"), e); }
        alle.remove(naam);
    }

    // ===================================================================================================
    // Wat deze telefoon weet: welke grote bestanden elk archief gebruikt
    // ===================================================================================================

    static File staatFile(Context c) { return new File(c.getNoBackupFilesDir(), "grote-bestanden.json"); }

    /** {boom, archieven: {naam: [grote bestanden]}, info: {groot bestand: [grootte, sha, gz]}, controle: laatst gecontroleerd}. */
    static synchronized JSONObject staat(Context c, Uri tree) {
        JSONObject o = null;
        try { File f = staatFile(c); if (f.isFile()) o = new JSONObject(Contacts.readText(f, false)); } catch (Exception ignored) { }
        try {
            if (o == null || !String.valueOf(tree).equals(o.optString("boom"))) o = new JSONObject().put("boom", String.valueOf(tree));
            if (!o.has("archieven")) o.put("archieven", new JSONObject());
            if (!o.has("info")) o.put("info", new JSONObject());
        } catch (Exception ignored) { }
        return o;
    }

    static synchronized void bewaarStaat(Context c, JSONObject o) {
        try { Contacts.writeText(staatFile(c), o.toString(), false); } catch (Exception e) { App.log(c, "BACKUP", "grote bestanden: " + AppData.safeMsg(e, "bewaren mislukt")); }
    }

    /**
     * Na een archief: welke grote bestanden het gebruikt (ook als het er nul zijn, of als grote bestanden apart uit staat
     * (s == null): dan weet opruimen dat dit archief nergens naar verwijst en hoeft het hem nooit te lezen).
     */
    static void vastleggen(Context c, String archief, Blokken.Schrijver s) {
        try {
            Uri tree = WaBackup.destUri(c);
            JSONObject st = staat(c, tree);
            JSONArray a = new JSONArray();
            if (s != null) for (String n : s.namen()) a.put(n);
            st.getJSONObject("archieven").put(archief, a);
            JSONObject info = st.getJSONObject("info");
            if (s != null) for (Blokken.Item i : s.items) info.put(i.b, new JSONArray().put(i.s).put(i.h).put(i.z).put(i.e));
            JSONObject gemist = st.optJSONObject("gemist");
            if (gemist != null) gemist.remove(archief);
            bewaarStaat(c, st);
        } catch (Exception e) { App.log(c, "BACKUP", "grote bestanden vastleggen: " + AppData.safeMsg(e, "mislukt")); }
    }

    /** Bekende versleutelde grootte per groot bestand (voor de controle bij hergebruiken). */
    static Map<String, Long> bekend(Context c) {
        Map<String, Long> m = new HashMap<>();
        try {
            JSONObject info = staat(c, WaBackup.destUri(c)).getJSONObject("info");
            for (Iterator<String> it = info.keys(); it.hasNext(); ) {
                String n = it.next();
                JSONArray x = info.optJSONArray(n);
                if (x != null && x.length() > 3 && x.optLong(3) > 0) m.put(n, x.optLong(3));
            }
        } catch (Exception ignored) { }
        return m;
    }

    static final long DAG = 86_400_000L;
    /** Zoveel onbekende archieven leest opruimen per keer (wat al gelezen is, blijft bewaard); meer = de volgende keer verder. */
    static final int MAX_LEZEN = 8;
    /** Een groot bestand gaat pas weg als het bij twee rondes, minstens zo ver uit elkaar, door niemand gebruikt werd. */
    static final long WACHT = 36L * 3_600_000L;
    /** Een archief dat deze telefoon kent maar dat zo vaak achter elkaar niet in de map stond, telt niet meer mee. */
    static final int GEMIST = 3;
    /** In de lijst van een archief: gemaakt met een ander wachtwoord (de grote bestanden van die sleutel blijven). */
    static final String VREEMD = "*";

    /** De lijst met grote bestanden uit een archief; null als het met een ander wachtwoord gemaakt is. */
    static List<Blokken.Item> leesLijst(Context c, Uri u, Vault.Key k) throws Exception {
        InputStream raw0 = c.getContentResolver().openInputStream(u);
        if (raw0 == null) throw new IOException("Archief niet te openen");
        InputStream raw = new java.io.BufferedInputStream(raw0, 1 << 16);
        try {
            byte[] h = Vault.readHeader(raw);
            if (!Vault.matches(k, h)) return null;
            Vault.In vin = new Vault.In(raw, h, k);
            try (Blokken.Lezer l = new Blokken.Lezer(new ZipInputStream(vin), vin, null)) {
                while (l.volgende()) { /* alleen de namen; de lijst komt vanzelf langs */ }
                return l.lijst();
            }
        } finally { try { raw.close(); } catch (Exception ignored) { } }
    }

    /**
     * Grote bestanden weghalen die geen enkel archief meer gebruikt. Veilig boven alles:
     * - elk archief dat deze telefoon kent telt mee, ook als de lijst van de map het even mist (pas na GEMIST rondes niet meer);
     * - elk ander bestand dat op .rtb eindigt in Versleuteld wordt gelezen (of is van vóór het merkteken, dan gebruikt het niets);
     *   lukt lezen niet, dan blijft alles staan;
     * - twee mappen Versleuteld: niets doen;
     * - een groot bestand gaat pas weg als het in twee rondes met WACHT ertussen ongebruikt was (een andere telefoon die
     *   net schrijft, een trage map);
     * - archieven van een ander wachtwoord: hun grote bestanden blijven.
     * Moet onder het slot van AllBackup lopen (een backup die bezig is, gebruikt nieuwe bestanden die nog in geen archief staan).
     */
    static JSONObject opruimen(Context c, Vault.Key k) throws Exception {
        JSONObject r = new JSONObject().put("count", 0).put("bytes", 0L);
        Uri tree = WaBackup.destUri(c);
        if (tree == null || k == null) return r;
        WaBackup.Dest dest = WaBackup.Dest.echt(c.getContentResolver(), tree);
        WaBackup.DestDir root = dest.root();
        List<WaBackup.Child> vd = root.dups.get(Secure.DIR);
        if (vd != null && vd.size() > 1) return r.put("later", "dubbel");
        WaBackup.DestDir v = dest.dir(Secure.DIR, false);
        if (v == null) return r;
        BlokOpslag o = new BlokOpslag(dest);
        Map<String, WaBackup.Child> er = o.alle();
        if (er.isEmpty()) return r;
        JSONObject st = staat(c, tree), arch = st.getJSONObject("archieven"), info = st.getJSONObject("info");
        JSONObject gemist = st.optJSONObject("gemist"), kand = st.optJSONObject("kandidaten");
        if (gemist == null) { gemist = new JSONObject(); st.put("gemist", gemist); }
        if (kand == null) { kand = new JSONObject(); st.put("kandidaten", kand); }
        long sinds = Blokken.sinds(er.keySet()), now = System.currentTimeMillis();

        // 1. Archieven in de map die deze telefoon nog niet kent: lezen (of ouder dan het merkteken: gebruiken niets)
        Set<String> inMap = new HashSet<>();
        int gelezen = 0;
        for (Map.Entry<String, WaBackup.Child> e : v.kids.entrySet()) {
            String n = e.getKey();
            if (e.getValue().dir || !n.endsWith(".rtb")) continue;
            inMap.add(n);
            if (arch.has(n)) continue;
            Rotate.F f = Rotate.parse(n);
            if (f != null && sinds > 0 && f.when < sinds - Blokken.MARGE) { arch.put(n, new JSONArray()); continue; }
            List<WaBackup.Child> zelfde = v.dups.get(n);
            if (zelfde != null && zelfde.size() > 1) { bewaarStaat(c, st); return r.put("later", "dubbel"); }
            if (AllBackup.stop || gelezen >= MAX_LEZEN) { bewaarStaat(c, st); return r.put("later", true); }
            gelezen++;
            List<Blokken.Item> items;
            try { items = leesLijst(c, DocumentsContract.buildDocumentUriUsingTree(tree, e.getValue().docId), k); }
            catch (Exception ex) {
                App.log(c, "BACKUP", "grote bestanden opruimen: een archief is niet te lezen");
                bewaarStaat(c, st);
                return r.put("later", "onleesbaar");
            }
            JSONArray a = new JSONArray();
            if (items == null) a.put(VREEMD);
            else for (Blokken.Item i : items) { a.put(i.b); info.put(i.b, new JSONArray().put(i.s).put(i.h).put(i.z).put(i.e)); }
            arch.put(n, a);
            bewaarStaat(c, st); // wat gelezen is, blijft gelezen
        }

        // 2. Wat gebruikt wordt: elk bekend archief, ook als de map het deze keer niet liet zien (tot GEMIST keer achter elkaar)
        Set<String> gebruikt = new HashSet<>();
        boolean vreemd = false;
        for (Iterator<String> it = arch.keys(); it.hasNext(); ) {
            String n = it.next();
            if (inMap.contains(n)) gemist.remove(n);
            else {
                int g = gemist.optInt(n, 0) + 1;
                if (g >= GEMIST) { it.remove(); gemist.remove(n); continue; }
                gemist.put(n, g);
            }
            JSONArray a = arch.getJSONArray(n);
            for (int i = 0; i < a.length(); i++) { String b = a.getString(i); if (VREEMD.equals(b)) vreemd = true; else gebruikt.add(b); }
        }

        // 3. Ongebruikt: eerst onthouden, pas bij een latere ronde (WACHT verder) en nog steeds ongebruikt weghalen
        int count = 0;
        long bytes = 0;
        Set<String> nu = new HashSet<>(Blokken.overbodig(er.keySet(), gebruikt, new Blokken.Ids(k).kid, vreemd));
        for (Iterator<String> it = kand.keys(); it.hasNext(); ) if (!nu.contains(it.next())) it.remove();
        for (String n : nu) {
            if (!kand.has(n)) { kand.put(n, now); continue; }
            if (now - kand.getLong(n) < WACHT) continue;
            long size = Math.max(0, er.get(n).size);
            try { o.weg(n); count++; bytes += size; info.remove(n); kand.remove(n); }
            catch (Exception e) { App.log(c, "BACKUP", "groot bestand opruimen: " + AppData.safeMsg(e, "mislukt")); }
        }

        // 4. Achtergebleven tijdelijke bestanden (ouder dan twee dagen), en opzij gezette (.kapot-datum, na 30 dagen;
        //    alleen als er weer een goede staat of niemand hem nog gebruikt)
        for (Map.Entry<String, WaBackup.Child> e : new ArrayList<>(er.entrySet())) {
            String n = e.getKey();
            boolean weg = false;
            if (n.endsWith(Blokken.PART)) weg = e.getValue().mod > 0 && e.getValue().mod < now - 2 * DAG;
            else {
                java.util.regex.Matcher m = Blokken.KAPOT.matcher(n);
                if (m.matches()) {
                    long t = Blokken.kapotTijd(n);
                    weg = t > 0 && t < now - 30 * DAG && (er.containsKey(m.group(1)) || !gebruikt.contains(m.group(1)));
                }
            }
            if (!weg) continue;
            try { o.weg(n); count++; bytes += Math.max(0, e.getValue().size); } catch (Exception ignored) { }
        }
        bewaarStaat(c, st);
        return r.put("count", count).put("bytes", bytes);
    }

    /** Hoeveel er nachtelijks ten hoogste gecontroleerd wordt (helemaal lezen en ontsleutelen). */
    static final long CONTROLE_BYTES = 64L * 1024 * 1024;

    /**
     * Grote bestanden een voor een helemaal lezen en controleren (elke nacht een stuk, op volgorde, daarna weer vooraan).
     * Alleen zekere schade telt (Blokken.schade, of een andere grootte dan bij het schrijven), en pas als het twee nachten
     * achter elkaar zo is: dan gaat het opzij (naam.kapot-JJJJMMDD) en schrijft de volgende backup het opnieuw als het nog
     * op de telefoon staat; dan kloppen ook de oudere archieven weer (zelfde inhoud = zelfde naam). Een verbinding die
     * wegvalt telt niet: dan de volgende keer verder. Alleen bestanden van het huidige wachtwoord.
     */
    static JSONObject controleren(Context c, Vault.Key k, long max) throws Exception {
        JSONObject r = new JSONObject().put("count", 0).put("kapot", 0);
        Uri tree = WaBackup.destUri(c);
        if (tree == null || k == null) return r;
        WaBackup.Dest dest = WaBackup.Dest.echt(c.getContentResolver(), tree);
        BlokOpslag o = new BlokOpslag(dest);
        JSONObject st = staat(c, tree), info = st.getJSONObject("info");
        JSONObject verdacht = st.optJSONObject("verdacht");
        if (verdacht == null) { verdacht = new JSONObject(); st.put("verdacht", verdacht); }
        String kid = new Blokken.Ids(k).kid;
        List<String> namen = new ArrayList<>();
        for (String n : o.alle().keySet()) if (kid.equals(Blokken.kid(n)) && info.has(n)) namen.add(n);
        if (namen.isEmpty()) return r;
        Collections.sort(namen);
        String vanaf = st.optString("controle", "");
        int start = 0;
        while (start < namen.size() && namen.get(start).compareTo(vanaf) <= 0) start++;
        long gelezen = 0, now = System.currentTimeMillis();
        int count = 0, kapot = 0;
        for (int i = 0; i < namen.size() && gelezen < max; i++) {
            String n = namen.get((start + i) % namen.size());
            if (AllBackup.stop) break;
            JSONArray x = info.getJSONArray(n);
            long size = x.getLong(0), enc = x.length() > 3 ? x.optLong(3) : 0;
            WaBackup.Child ch = o.alle().get(n);
            boolean schade;
            if (enc > 0 && ch != null && ch.size >= 0 && ch.size != enc) schade = true; // andere grootte dan geschreven
            else {
                try { Blokken.leesHelemaal(Blokken.lees(new java.io.BufferedInputStream(o.open(n), 1 << 16), k, x.getBoolean(2), size, x.getString(1))); schade = false; }
                catch (IOException e) {
                    if (!Blokken.schade(e)) break; // geen verbinding of iets dergelijks: de volgende keer verder (vanaf hier)
                    schade = true;
                }
            }
            if (!schade) { verdacht.remove(n); count++; }
            else if (!verdacht.has(n) || now - verdacht.getLong(n) < 20L * 3_600_000L) {
                if (!verdacht.has(n)) verdacht.put(n, now); // eerste keer: de volgende nacht opnieuw kijken
            } else {
                kapot++;
                App.log(c, "BACKUP", "groot bestand beschadigd: opzij gezet");
                try {
                    if (WaBackup.rename(dest, DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId), Blokken.kapotNaam(n, now)) != null) {
                        o.alle().remove(n); info.remove(n); verdacht.remove(n);
                    }
                } catch (Exception ignored) { }
            }
            gelezen += size;
            st.put("controle", n);
        }
        bewaarStaat(c, st);
        return r.put("count", count).put("kapot", kapot);
    }

    /**
     * De oude, volledige archieven die nog staan: gemaakt ruim vóór het merkteken (dus zonder lijst; zelfde regel als bij
     * opruimen). Alleen als de nieuwste nieuwe backup compleet is (al zijn grote bestanden staan er); de nieuwste oude
     * blijft altijd als vangnet staan, net als de nieuwste backup met alle appgegevens. dry = alleen tellen.
     * Geeft {count, bytes, keep, van, tot} (of {onbekend} / {onvolledig} als het niet zeker kan).
     */
    static JSONObject oudeArchieven(Context c, boolean dry) throws Exception {
        JSONObject r = new JSONObject().put("count", 0).put("bytes", 0L);
        Uri tree = WaBackup.destUri(c);
        if (tree == null) return r;
        WaBackup.Dest dest = WaBackup.Dest.echt(c.getContentResolver(), tree);
        WaBackup.DestDir v = dest.dir(Secure.DIR, false);
        if (v == null) return r;
        BlokOpslag o = new BlokOpslag(dest);
        long sinds = o.sinds();
        if (sinds <= 0) return r.put("onbekend", true);
        JSONObject arch = staat(c, tree).getJSONObject("archieven");
        // De nieuwste nieuwe, volledige backup die deze telefoon kent: staat alles wat hij gebruikt er echt?
        String nieuwste = null;
        for (Map.Entry<String, WaBackup.Child> e : v.kids.entrySet()) {
            Rotate.F f = Rotate.parse(e.getKey());
            if (f == null || f.partial || e.getValue().dir || !arch.has(e.getKey()) || f.when < sinds) continue;
            if (nieuwste == null || e.getKey().compareTo(nieuwste) > 0) nieuwste = e.getKey();
        }
        if (nieuwste == null) return r.put("geenNieuw", true);
        JSONArray a = arch.getJSONArray(nieuwste);
        for (int i = 0; i < a.length(); i++) {
            WaBackup.Child ch = o.alle().get(a.getString(i));
            if (ch == null || ch.size == 0) return r.put("onvolledig", true);
        }
        List<String> oud = new ArrayList<>();
        String keep = null;
        for (Map.Entry<String, WaBackup.Child> e : v.kids.entrySet()) {
            String n = e.getKey();
            Rotate.F f = Rotate.parse(n);
            if (e.getValue().dir || f == null || !n.startsWith("backup-") || !n.endsWith(".rtb") || f.when >= sinds - Blokken.MARGE) continue;
            oud.add(n);
            if (!f.partial && (keep == null || n.compareTo(keep) > 0)) keep = n;
        }
        List<String> blijft = Secure.appBlijft(c, dest, v);
        int count = 0;
        long bytes = 0;
        String van = null, tot = null;
        Collections.sort(oud);
        for (String n : oud) {
            if (n.equals(keep) || blijft.contains(n)) continue;
            WaBackup.Child ch = v.kids.get(n);
            if (!dry) {
                try { if (!DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId))) continue; }
                catch (Exception e) { App.log(c, "BACKUP", "oud archief weghalen: " + AppData.safeMsg(e, "mislukt")); continue; }
            }
            if (van == null) van = n;
            tot = n;
            count++;
            bytes += Math.max(0, ch.size);
        }
        r.put("count", count).put("bytes", bytes);
        if (keep != null) r.put("keep", keep);
        if (van != null) r.put("van", van).put("tot", tot);
        return r;
    }
}
