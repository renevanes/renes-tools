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
        final BlokOpslag o; final WaBackup.DestDir d; final Uri part; final String naam; final OutputStream out;
        SafSchrijf(BlokOpslag o, WaBackup.DestDir d, Uri part, String naam, OutputStream out) { this.o = o; this.d = d; this.part = part; this.naam = naam; this.out = new java.io.BufferedOutputStream(out, 1 << 16); }

        @Override public OutputStream uit() { return out; }

        @Override public InputStream terug() throws IOException {
            InputStream in;
            try { in = o.dest.cr.openInputStream(part); } catch (Exception e) { throw new IOException(AppData.safeMsg(e, "Teruglezen lukt niet"), e); }
            if (in == null) throw new IOException("Teruglezen lukt niet");
            return new java.io.BufferedInputStream(in, 1 << 16);
        }

        @Override public void klaar() throws IOException {
            try {
                // Staat er (van een vorige keer, of op een andere telefoon) al een met deze naam: die is gelijk, de nieuwe weg
                WaBackup.Child had = o.alle().get(naam);
                Uri done = null;
                if (had == null) {
                    try { done = WaBackup.rename(o.dest, part, naam); } catch (Exception ignored) { }
                    if (done == null) {
                        Uri u = WaBackup.create(o.dest, d.uri, "application/octet-stream", naam);
                        if (u == null) throw new IOException("Hernoemen lukt niet");
                        try { DocumentWrite.copy(() -> o.dest.cr.openInputStream(part), () -> o.dest.cr.openOutputStream(u, "w")); }
                        catch (Exception e) { try { DocumentsContract.deleteDocument(o.dest.cr, u); } catch (Exception ignored) { } throw e; }
                        try { DocumentsContract.deleteDocument(o.dest.cr, part); } catch (Exception ignored) { }
                        done = u;
                    }
                } else try { DocumentsContract.deleteDocument(o.dest.cr, part); } catch (Exception ignored) { }
                if (done != null) {
                    WaBackup.Child ch = new WaBackup.Child(DocumentsContract.getDocumentId(done), -1, System.currentTimeMillis(), false);
                    o.alle().put(naam, ch);
                    d.kids.put(naam, ch);
                }
            } catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException(AppData.safeMsg(e, "Hernoemen lukt niet"), e); }
        }

        @Override public void afbreken() {
            try { out.close(); } catch (Exception ignored) { }
            try { DocumentsContract.deleteDocument(o.dest.cr, part); } catch (Exception ignored) { }
        }
    }

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

    /** Na een archief: welke grote bestanden het gebruikt (ook als het er nul zijn: dan is het een archief van na de omzetting). */
    static void vastleggen(Context c, String archief, Blokken.Schrijver s) {
        try {
            Uri tree = WaBackup.destUri(c);
            JSONObject st = staat(c, tree);
            JSONArray a = new JSONArray();
            for (String n : s.namen()) a.put(n);
            st.getJSONObject("archieven").put(archief, a);
            JSONObject info = st.getJSONObject("info");
            for (Blokken.Item i : s.items) info.put(i.b, new JSONArray().put(i.s).put(i.h).put(i.z));
            if (!st.has("sinds")) st.put("sinds", System.currentTimeMillis());
            bewaarStaat(c, st);
        } catch (Exception e) { App.log(c, "BACKUP", "grote bestanden vastleggen: " + AppData.safeMsg(e, "mislukt")); }
    }

    static final long DAG = 86_400_000L;
    /** Zoveel onbekende archieven leest opruimen per keer; meer = een andere keer (nooit iets weghalen zonder te weten). */
    static final int MAX_LEZEN = 3;

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
     * Grote bestanden weghalen die geen enkel archief in Versleuteld meer gebruikt (na opruimen van oude archieven).
     * Alleen als alles bekend is: kan een archief niet gelezen worden, dan blijft alles staan. Moet onder het slot van
     * AllBackup lopen (een backup die bezig is, gebruikt nieuwe bestanden die nog in geen enkel archief staan).
     */
    static JSONObject opruimen(Context c, Vault.Key k) throws Exception {
        JSONObject r = new JSONObject().put("count", 0).put("bytes", 0L);
        Uri tree = WaBackup.destUri(c);
        if (tree == null || k == null) return r;
        WaBackup.Dest dest = WaBackup.Dest.echt(c.getContentResolver(), tree);
        WaBackup.DestDir v = dest.dir(Secure.DIR, false);
        if (v == null) return r;
        BlokOpslag o = new BlokOpslag(dest);
        Map<String, WaBackup.Child> er = o.alle();
        if (er.isEmpty()) return r;
        JSONObject st = staat(c, tree), arch = st.getJSONObject("archieven"), info = st.getJSONObject("info");
        long eerste = Long.MAX_VALUE;
        for (Map.Entry<String, WaBackup.Child> e : er.entrySet())
            if (Blokken.kid(e.getKey()) != null) eerste = Math.min(eerste, e.getValue().mod > 0 ? e.getValue().mod : 0);
        Set<String> gebruikt = new HashSet<>();
        Set<String> nu = new HashSet<>();
        boolean vreemd = false;
        int gelezen = 0;
        for (Map.Entry<String, WaBackup.Child> e : v.kids.entrySet()) {
            String n = e.getKey();
            if (e.getValue().dir || !n.startsWith("backup-") || !n.endsWith(".rtb")) continue;
            nu.add(n);
            JSONArray a = arch.optJSONArray(n);
            if (a == null) {
                Rotate.F f = Rotate.parse(n);
                // Gemaakt vóór het eerste grote bestand (een oud, volledig archief): verwijst nergens naar
                if (f != null && eerste != Long.MAX_VALUE && eerste > 0 && f.when < eerste - DAG) continue;
                if (gelezen >= MAX_LEZEN) return r.put("later", true);
                gelezen++;
                List<Blokken.Item> items = leesLijst(c, DocumentsContract.buildDocumentUriUsingTree(tree, e.getValue().docId), k);
                if (items == null) { vreemd = true; continue; }
                a = new JSONArray();
                for (Blokken.Item i : items) { a.put(i.b); info.put(i.b, new JSONArray().put(i.s).put(i.h).put(i.z)); }
                arch.put(n, a);
            }
            for (int i = 0; i < a.length(); i++) gebruikt.add(a.getString(i));
        }
        // Wat niet meer in de map staat, hoeft deze telefoon ook niet meer te onthouden
        for (Iterator<String> it = arch.keys(); it.hasNext(); ) if (!nu.contains(it.next())) it.remove();
        int count = 0;
        long bytes = 0;
        for (String n : Blokken.overbodig(er.keySet(), gebruikt, new Blokken.Ids(k).kid, vreemd)) {
            long size = Math.max(0, er.get(n).size);
            try { o.weg(n); count++; bytes += size; info.remove(n); }
            catch (Exception e) { App.log(c, "BACKUP", "groot bestand opruimen: " + AppData.safeMsg(e, "mislukt")); }
        }
        // Achtergebleven tijdelijke bestanden van een afgebroken backup (ouder dan twee dagen), en wat bij de controle
        // beschadigd bleek (na 30 dagen: de backup heeft het dan al lang opnieuw geschreven)
        long now = System.currentTimeMillis();
        for (Map.Entry<String, WaBackup.Child> e : new ArrayList<>(er.entrySet())) {
            String n = e.getKey();
            long mod = e.getValue().mod;
            boolean weg = mod > 0 && (n.endsWith(Blokken.PART) && mod < now - 2 * DAG || n.endsWith(KAPOT) && mod < now - 30 * DAG);
            if (!weg) continue;
            try { o.weg(n); count++; bytes += Math.max(0, e.getValue().size); } catch (Exception ignored) { }
        }
        bewaarStaat(c, st);
        return r.put("count", count).put("bytes", bytes);
    }

    /** Hoeveel er nachtelijks ten hoogste gecontroleerd wordt (helemaal lezen en ontsleutelen). */
    static final long CONTROLE_BYTES = 64L * 1024 * 1024;
    static final String KAPOT = ".kapot";

    /**
     * Grote bestanden een voor een helemaal lezen en controleren (elke nacht een stuk, op volgorde, daarna weer vooraan).
     * Twee keer achter elkaar beschadigd: het bestand gaat opzij (naam.kapot, na 30 dagen weg) en de volgende backup
     * schrijft het opnieuw als het nog op de telefoon staat; dan kloppen ook de oudere archieven weer (zelfde inhoud =
     * zelfde naam). Alleen bestanden van het huidige wachtwoord.
     */
    static JSONObject controleren(Context c, Vault.Key k, long max) throws Exception {
        JSONObject r = new JSONObject().put("count", 0).put("kapot", 0);
        Uri tree = WaBackup.destUri(c);
        if (tree == null || k == null) return r;
        WaBackup.Dest dest = WaBackup.Dest.echt(c.getContentResolver(), tree);
        BlokOpslag o = new BlokOpslag(dest);
        JSONObject st = staat(c, tree), info = st.getJSONObject("info");
        String kid = new Blokken.Ids(k).kid;
        List<String> namen = new ArrayList<>();
        for (String n : o.alle().keySet()) if (kid.equals(Blokken.kid(n)) && info.has(n)) namen.add(n);
        if (namen.isEmpty()) return r;
        Collections.sort(namen);
        String vanaf = st.optString("controle", "");
        int start = 0;
        while (start < namen.size() && namen.get(start).compareTo(vanaf) <= 0) start++;
        long gelezen = 0;
        int count = 0, kapot = 0;
        for (int i = 0; i < namen.size() && gelezen < max; i++) {
            String n = namen.get((start + i) % namen.size());
            if (AllBackup.stop) break;
            JSONArray x = info.getJSONArray(n);
            long size = x.getLong(0);
            IOException fout = null;
            for (int poging = 0; poging < 2; poging++) {
                try { Blokken.leesHelemaal(Blokken.lees(new java.io.BufferedInputStream(o.open(n), 1 << 16), k, x.getBoolean(2), size, x.getString(1))); fout = null; break; }
                catch (IOException e) { fout = e; if (!Blokken.beschadigd(e)) break; }
            }
            if (fout != null && !Blokken.beschadigd(fout)) break; // geen verbinding of iets dergelijks: een andere keer verder
            if (fout != null) {
                kapot++;
                App.log(c, "BACKUP", "groot bestand beschadigd: opzij gezet");
                try {
                    WaBackup.Child ch = o.alle().get(n);
                    if (WaBackup.rename(dest, DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId), n + KAPOT) != null) { o.alle().remove(n); info.remove(n); }
                } catch (Exception ignored) { }
            } else count++;
            gelezen += size;
            st.put("controle", n);
        }
        bewaarStaat(c, st);
        return r.put("count", count).put("kapot", kapot);
    }

    /**
     * De oude, volledige archieven die nog staan: gemaakt vóór het eerste grote bestand (dus zonder lijst; zelfde regel
     * als bij opruimen). Alleen als er daarna al een volledig nieuw archief is; de nieuwste oude blijft altijd als vangnet
     * staan, net als de nieuwste backup met alle appgegevens. dry = alleen tellen. Geeft {count, bytes, keep}.
     */
    static JSONObject oudeArchieven(Context c, boolean dry) throws Exception {
        JSONObject r = new JSONObject().put("count", 0).put("bytes", 0L);
        Uri tree = WaBackup.destUri(c);
        if (tree == null) return r;
        WaBackup.Dest dest = WaBackup.Dest.echt(c.getContentResolver(), tree);
        WaBackup.DestDir v = dest.dir(Secure.DIR, false);
        if (v == null) return r;
        long eerste = Long.MAX_VALUE;
        for (Map.Entry<String, WaBackup.Child> e : new BlokOpslag(dest).alle().entrySet()) {
            if (Blokken.kid(e.getKey()) == null) continue;
            if (e.getValue().mod <= 0) return r; // tijd onbekend: niet zeker te weten welke archieven oud zijn
            eerste = Math.min(eerste, e.getValue().mod);
        }
        if (eerste == Long.MAX_VALUE) return r;
        JSONObject arch = staat(c, tree).getJSONObject("archieven");
        boolean nieuw = false;
        List<String> oud = new ArrayList<>();
        String keep = null;
        for (Map.Entry<String, WaBackup.Child> e : v.kids.entrySet()) {
            String n = e.getKey();
            Rotate.F f = Rotate.parse(n);
            if (e.getValue().dir || f == null || !n.startsWith("backup-") || !n.endsWith(".rtb")) continue;
            if (arch.has(n) && !f.partial && f.when >= eerste - DAG) nieuw = true;
            if (arch.has(n) || f.when >= eerste - DAG) continue;
            oud.add(n);
            if (!f.partial && (keep == null || n.compareTo(keep) > 0)) keep = n;
        }
        if (!nieuw) return r.put("geenNieuw", true);
        List<String> blijft = Secure.appBlijft(c, dest, v);
        int count = 0;
        long bytes = 0;
        for (String n : oud) {
            if (n.equals(keep) || blijft.contains(n)) continue;
            WaBackup.Child ch = v.kids.get(n);
            if (!dry) {
                try { if (!DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId))) continue; }
                catch (Exception e) { App.log(c, "BACKUP", "oud archief weghalen: " + AppData.safeMsg(e, "mislukt")); continue; }
            }
            count++;
            bytes += Math.max(0, ch.size);
        }
        r.put("count", count).put("bytes", bytes);
        if (keep != null) r.put("keep", keep);
        return r;
    }
}
