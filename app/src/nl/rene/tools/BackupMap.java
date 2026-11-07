package nl.rene.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Onderhoud van de backup-map: controleert de hele map en ruimt op wat er niet hoort.
 * - Mappen met dezelfde naam (kan op Google Drive): samengevoegd tot één map; de lege komt weg.
 * - Bestanden met dezelfde naam: dezelfde inhoud → één blijft; andere inhoud → de nieuwste houdt de naam, de andere
 *   blijft als naam~jjjjmmdd (zoals de app oude versies al bewaart). Er gaat nooit iets weg wat nergens anders staat.
 * - Achtergebleven tijdelijke bestanden van een afgebroken backup (ouder dan een dag): weg, of terug op hun plek.
 * - Mappen die geen enkel onderdeel van de app maakt: alleen gemeld, nooit aangeraakt.
 * Draait op verzoek (Alles back-uppen → Backup-map controleren), eenmalig na de update en elke nacht na de backup.
 * Nieuw onderdeel dat in de backup-map schrijft = ook een regel in {@link #KNOWN} (BackupMapTest bewaakt dat).
 */
final class BackupMap {

    private BackupMap() { }

    static final String MARKER = "backup-map.json", FORMAT = "renes-tools-backup-map";
    /** Indeling van de backup-map. Verandert de indeling ooit, dan hier omhoog en in {@link #migrate} het overzetten. */
    static final int VERSION = 1;

    /** Alle mappen die de app bovenin de backup-map maakt: naam → onderdeel. */
    static final String[][] KNOWN = {
            {WaBackup.BACKUP_DIR, "WhatsApp backup"},
            {Sms.DIR, "SMS backup"},
            {Calls.DIR, "Oproepen"},
            {NotificationHistory.DIR, "Meldingsgeschiedenis"},
            {Contacts.DIR, "Contacten"},
            {Notes.DIR, "Notities"},
            {LauncherBackup.DIR, "Startscherm"},
            {Transcribe.DIR, "Uitgeschreven gesprekken"},
            {"Muziek", "Herkende muziek"},
            {SettingsBackup.DIR, "Instellingen"},
            {ToolData.DIR, "Toolgegevens"},
            {SettingsBackup.ROUTES_DIR, "Routes"},
            {AiSleepBackup.AI_DIR, "Assistent"},
            {AiSleepBackup.SLEEP_DIR, "Slaappraat"},
            {CheckinStore.DIR, "Check-in"},
            {"Kluis", "Kluis"},
            {Secure.DIR, "Versleutelde backups"},
            {"Uitgepakt", "Uitgepakte versleutelde backups"},
            {Spotify.DIR, "Spotify"},
            {Facturen.MAP, "Facturen"},
            {"PDF", "PDF"},
            {"Radio", "Radio (bewaarde fragmenten)"},
            {"Ritten", "Mijn auto (ritten)"},
            {"Gespreksopnames", "Gespreksopnames"},
    };

    static boolean known(String top) { for (String[] k : KNOWN) if (k[0].equals(top)) return true; return false; }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("backupmap", Context.MODE_PRIVATE); }

    /** Elke nacht vanzelf opruimen (standaard aan). */
    static boolean auto(Context c) { return prefs(c).getBoolean("auto", true); }

    static final java.util.concurrent.atomic.AtomicBoolean RUNNING = new java.util.concurrent.atomic.AtomicBoolean(false);
    static volatile boolean stop = false;
    static volatile String current = "";

    static final long TEMP_AGE = 2L * 24 * 60 * 60 * 1000; // zoals Versleutelen: pas na twee dagen
    static final long HASH_MAX = 32L * 1024 * 1024;
    static final int MAX_ITEMS = 300, MAX_DEPTH = 14;

    /** Tijdelijke namen van de backup (in WaBackup, Sms, Secure, AiSleepBackup). */
    static boolean temp(String name) { return name.endsWith(".rt-tmp") || name.endsWith(".rt-part") || name.endsWith(".rtb.part"); }

    /** Iets anders schrijft nu in de backup-map? Dan wachten (opruimen kan anders iets half verplaatsen). */
    static String busy() {
        if (AllBackup.busy) return Taal.T("Er loopt een backup");
        if (WaBackup.busy) return Taal.T("De WhatsApp-backup loopt");
        if (Spotify.BUSY.get()) return Taal.T("De Spotify-backup loopt");
        if (FacturenRun.LOPEND.get() != null) return Taal.T("Er worden facturen opgehaald");
        if (HerstelLos.BUSY.get()) return Taal.T("Er wordt teruggezet");
        return null;
    }

    /** Zoals busy(), maar zonder onze eigen vlag (tijdens het opruimen staat AllBackup.busy voor ons aan). */
    static String busyOther() {
        if (WaBackup.busy) return "wa";
        if (Spotify.BUSY.get()) return "spotify";
        if (FacturenRun.LOPEND.get() != null) return "facturen";
        if (HerstelLos.BUSY.get()) return "herstel";
        return null;
    }

    /** Uitkomst. */
    static final class Report {
        int dirs, files, dupDirs, dupFiles, temps, merged, removed, renamed, cleaned, errors;
        boolean repair, stopped;
        final JSONArray items = new JSONArray(), unknown = new JSONArray();
        long t = System.currentTimeMillis();

        void item(String path, String what, boolean done) {
            if (items.length() >= MAX_ITEMS) return;
            try { items.put(new JSONObject().put("path", path).put("what", what).put("done", done)); } catch (Exception ignored) { }
        }

        int problems() { return dupDirs + dupFiles + temps; }

        JSONObject json() throws Exception {
            return new JSONObject().put("t", t).put("repair", repair).put("stopped", stopped).put("dirs", dirs).put("files", files)
                    .put("dupDirs", dupDirs).put("dupFiles", dupFiles).put("temps", temps).put("merged", merged).put("removed", removed)
                    .put("renamed", renamed).put("cleaned", cleaned).put("errors", errors).put("problems", problems())
                    .put("items", items).put("unknown", unknown);
        }
    }

    /** Controleren (repair = false: alleen kijken) of opruimen. */
    static JSONObject run(Context c, boolean repair, boolean auto) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst een backup-map");
        if (!RUNNING.compareAndSet(false, true)) throw new Exception(Taal.T("De backup-map wordt al gecontroleerd"));
        boolean began = false;
        stop = false;
        try {
            if (repair) {
                if (busy() != null) throw new Exception(Taal.T("Er loopt nu iets anders in de backup-map; opruimen kan zo meteen"));
                if (!AllBackup.tryBegin()) throw new Exception(Taal.T("Er loopt nu iets anders in de backup-map; opruimen kan zo meteen"));
                began = true;
            }
            Report r = new Report();
            r.repair = repair;
            long deadline = System.currentTimeMillis() + (auto ? 15 : 60) * 60_000L;
            WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
            WaBackup.DestDir root = dest.root();
            walk(dest, root, "", 0, r, repair, deadline);
            for (Map.Entry<String, WaBackup.Child> e : root.kids.entrySet())
                if (e.getValue().dir && !known(e.getKey())) r.unknown.put(e.getKey());
            if (repair && !r.stopped) {
                migrate(c, dest, root, r);
                writeMarker(dest, root, r);
            }
            JSONObject o = r.json();
            SharedPreferences.Editor e = prefs(c).edit().putString("last", o.toString());
            if (repair && !r.stopped && r.errors == 0) e.putInt("doneVersion", VERSION);
            e.apply();
            App.log(c, "BACKUP", "backup-map " + (repair ? "opgeruimd" : "gecontroleerd") + ": " + r.problems() + " punten, " + r.errors + " fouten");
            if (auto && repair) notifyChanged(c, r);
            return o;
        } finally {
            current = "";
            stop = false; // een Stop geldt alleen voor deze keer
            if (began) { AllBackup.running.set(false); AllBackup.busy = false; }
            RUNNING.set(false);
        }
    }

    static boolean halt(Report r, long deadline) {
        // AllBackup.stop telt alleen bij opruimen (dan zette tryBegin hem net terug); bij alleen controleren kan hij van een vorige nacht zijn
        if (stop || (r.repair && AllBackup.stop) || System.currentTimeMillis() > deadline) { r.stopped = true; return true; }
        return false;
    }

    static Uri doc(WaBackup.Dest dest, String id) { return DocumentsContract.buildDocumentUriUsingTree(dest.tree, id); }

    static WaBackup.DestDir sub(WaBackup.Dest dest, WaBackup.Child ch, String path) throws Exception {
        WaBackup.DestDir d = new WaBackup.DestDir();
        d.uri = doc(dest, ch.docId);
        d.path = path;
        dest.list(d);
        return d;
    }

    static String join(String a, String b) { return a.isEmpty() ? b : a + "/" + b; }

    /** Bovenin de backup-map alleen wat van de app is: eigen mappen en eigen bestanden. De rest wordt nooit aangeraakt. */
    static boolean ours(String path, String name) { return !path.isEmpty() || known(name) || MARKER.equals(name); }

    /** Een map loopt hij na (d is al ingelezen, door root() of sub()). */
    static void walk(WaBackup.Dest dest, WaBackup.DestDir d, String path, int depth, Report r, boolean repair, long deadline) throws Exception {
        if (halt(r, deadline) || depth > MAX_DEPTH) return;
        current = path;
        // 1. Dubbele namen
        boolean changed = false;
        for (Map.Entry<String, List<WaBackup.Child>> e : new ArrayList<>(d.dups.entrySet())) {
            String name = e.getKey(), p = join(path, name);
            if (!ours(path, name)) continue;
            WaBackup.Child keep = d.kids.get(name);
            for (WaBackup.Child x : e.getValue()) {
                if (x.docId.equals(keep.docId)) continue;
                if (halt(r, deadline)) return;
                try {
                    if (keep.dir && x.dir) {
                        r.dupDirs++;
                        boolean merged = false;
                        if (repair) {
                            WaBackup.FOLDER_LOCK.lock(); // niemand maakt intussen mappen in wat we samenvoegen
                            try { merged = mergeDir(dest, sub(dest, keep, p), x, p, r, deadline); if (merged) r.merged++; }
                            finally { WaBackup.FOLDER_LOCK.unlock(); }
                            changed = true;
                        }
                        r.item(p, Taal.T("Dubbele map"), merged);
                    } else if (!keep.dir && !x.dir) {
                        r.dupFiles++;
                        if (repair) {
                            if (same(dest, keep, x)) { delete(dest, x); r.removed++; }
                            else { renameAside(dest, d, x, name); r.renamed++; }
                            changed = true;
                        }
                        r.item(p, Taal.T("Dubbel bestand"), repair);
                    } else {
                        // Een map en een bestand met dezelfde naam: de map blijft (die telt), het bestand krijgt een eigen naam
                        r.dupFiles++;
                        if (repair) { renameAside(dest, d, x.dir ? keep : x, name); r.renamed++; changed = true; }
                        r.item(p, Taal.T("Map en bestand met dezelfde naam"), repair);
                    }
                } catch (Exception ex) { r.errors++; r.item(p, Taal.T("Opruimen mislukt"), false); }
            }
        }
        if (changed) dest.list(d);
        // 2. Achtergebleven tijdelijke bestanden (zoals Sms.open en WaBackup ze zelf zouden herstellen; nooit de laatste kopie weg)
        long old = System.currentTimeMillis() - TEMP_AGE;
        boolean tmpChanged = false;
        for (Map.Entry<String, WaBackup.Child> e : new ArrayList<>(d.kids.entrySet())) {
            String name = e.getKey();
            WaBackup.Child ch = e.getValue();
            if (ch.dir || !ours(path, name)) continue;
            String real = realName(name);
            if (real == null || ch.mod <= 0 || ch.mod > old) continue;
            if (d.kids.containsKey(real + ".rt-old") && d.kids.containsKey(real + ".rt-tmp")) continue; // herstelt de app zelf (zie fixTemp)
            try {
                r.temps++;
                boolean did = repair && fixTemp(dest, d, name, ch, real);
                if (did) { r.cleaned++; tmpChanged = true; }
                r.item(join(path, name), Taal.T(name.endsWith(".rt-old") ? "Oude versie van een afgebroken backup" : "Tijdelijk bestand van een afgebroken backup"), did);
            } catch (Exception ex) { r.errors++; }
        }
        if (tmpChanged) dest.list(d);
        // 3. Verder naar binnen (bovenin alleen de eigen mappen)
        for (Map.Entry<String, WaBackup.Child> e : new ArrayList<>(d.kids.entrySet())) {
            WaBackup.Child ch = e.getValue();
            if (!ours(path, e.getKey())) continue;
            if (ch.dir) { r.dirs++; walk(dest, sub(dest, ch, join(path, e.getKey())), join(path, e.getKey()), depth + 1, r, repair, deadline); }
            else r.files++;
            if (r.stopped) return;
        }
    }

    /** "x.json.rt-tmp" → "x.json"; null als het geen tijdelijke naam is. Archieven (.rtb.part) ruimt Versleutelen zelf op. */
    static String realName(String name) {
        for (String s : new String[]{".rt-tmp", ".rt-part", ".rt-old"}) if (name.endsWith(s) && name.length() > s.length()) return name.substring(0, name.length() - s.length());
        return null;
    }

    /**
     * Wat met een achtergebleven tijdelijk bestand gebeurt. Nooit iets weg dat de enige kopie kan zijn:
     * - x.rt-old terwijl ook x.rt-tmp er staat: de backup stopte midden in het vervangen; dan niets doen (de app herstelt
     *   dat zelf bij de volgende backup van dat onderdeel, Sms.open).
     * - x.rt-old zonder x: de oude terug onder de echte naam. Met x: bewaard als x~datum.
     * - x.rt-tmp / x.rt-part met x ernaast: x is de echte; het tijdelijke bestand weg. Zonder x: bewaard als x~datum
     *   (kan compleet zijn; de backup maakt x vanzelf opnieuw).
     */
    /** Geeft true als er echt iets veranderde. */
    static boolean fixTemp(WaBackup.Dest dest, WaBackup.DestDir d, String name, WaBackup.Child ch, String real) throws Exception {
        // x, x.rt-tmp en x.rt-old samen: de backup stopte midden in het vervangen. Dan niets aanraken; dat herstelt de
        // app zelf goed bij de volgende backup van dat onderdeel (Sms.open: het onvolledige x weg, het oude terug).
        if (d.kids.containsKey(real + ".rt-old") && d.kids.containsKey(real + ".rt-tmp")) return false;
        boolean hasReal = d.kids.containsKey(real) && !d.kids.get(real).dir;
        if (name.endsWith(".rt-old")) {
            if (!hasReal) { if (WaBackup.rename(dest, doc(dest, ch.docId), real) == null) throw new Exception("hernoemen"); }
            else renameAside(dest, d, ch, real);
            return true;
        }
        if (hasReal) delete(dest, ch);
        else renameAside(dest, d, ch, real);
        return true;
    }

    /** Alles uit de dubbele map 'from' naar 'into'; daarna de lege map weg. Geeft true als alles over is. */
    static boolean mergeDir(WaBackup.Dest dest, WaBackup.DestDir into, WaBackup.Child from, String path, Report r, long deadline) throws Exception {
        if (halt(r, deadline)) return false;
        WaBackup.DestDir src = sub(dest, from, path);
        List<WaBackup.Child> all = new ArrayList<>();
        for (Map.Entry<String, WaBackup.Child> e : src.kids.entrySet()) {
            List<WaBackup.Child> d = src.dups.get(e.getKey());
            if (d != null) all.addAll(d); else all.add(e.getValue());
        }
        int done = 0;
        for (WaBackup.Child x : all) {
            if (halt(r, deadline)) return false;
            String name = name(dest, x);
            if (name == null) continue;
            WaBackup.Child there = into.kids.get(name);
            String p = join(path, name);
            if (x.dir && there != null && there.dir) { if (mergeDir(dest, sub(dest, there, p), x, p, r, deadline)) done++; continue; }
            if (!x.dir && there != null && !there.dir && same(dest, there, x)) { delete(dest, x); r.removed++; done++; continue; }
            String to = there == null ? name : WaBackup.versionName(name, into); // botst: als oude versie erbij
            Uri u = move(dest, x, src, into, to, p, r, deadline);
            if (u != null) { into.kids.put(to, new WaBackup.Child(DocumentsContract.getDocumentId(u), x.size, x.mod, x.dir)); done++; }
        }
        // Pas weg als hij echt leeg is: twee keer opnieuw ingelezen (Google Drive kan nog bezig zijn of achterlopen),
        // en alles wat erin stond is verwerkt. Anders blijft hij staan (de volgende keer verder).
        if (done < all.size()) return false;
        for (int i = 0; i < 2; i++) {
            if (i > 0) try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            dest.list(src);
            if (!src.kids.isEmpty()) { r.errors++; r.item(path, Taal.T("Dubbele map niet helemaal leeg te krijgen"), false); return false; }
        }
        // Begon intussen iets anders te schrijven? Dan de (lege) map laten staan; de volgende keer weg
        if (busyOther() != null) return false;
        delete(dest, from);
        return true;
    }

    static String name(WaBackup.Dest dest, WaBackup.Child x) {
        try (android.database.Cursor c = dest.cr.query(doc(dest, x.docId), new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) { return null; }
    }

    /** Verplaatsen; kan de mappen-app dat niet, dan kopiëren en daarna pas het origineel weg. Geeft het nieuwe adres (null = nog niet klaar). */
    static Uri move(WaBackup.Dest dest, WaBackup.Child x, WaBackup.DestDir from, WaBackup.DestDir into, String to, String path, Report r, long deadline) throws Exception {
        Uri moved = null;
        try { moved = DocumentsContract.moveDocument(dest.cr, doc(dest, x.docId), from.uri, into.uri); }
        catch (Exception ignored) { } // niet ondersteund
        if (moved != null) {
            moved = WaBackup.inTree(dest.tree, moved);
            String now = name(dest, new WaBackup.Child(DocumentsContract.getDocumentId(moved), 0, 0, x.dir));
            if (now != null && !now.equals(to)) { Uri rn = WaBackup.rename(dest, moved, to); if (rn != null) moved = rn; }
            return moved;
        }
        if (x.dir) {
            WaBackup.Child made = WaBackup.createDir(dest, into, to);
            if (made == null || !made.dir) throw new Exception("Map maken lukt niet: " + to);
            return mergeDir(dest, sub(dest, made, path), x, path, r, deadline) ? doc(dest, made.docId) : null;
        }
        Uri tmp = WaBackup.create(dest, into.uri, "application/octet-stream", to + ".rt-part");
        if (tmp == null) throw new Exception("Kopiëren lukt niet: " + to);
        long n = 0;
        Uri done;
        try {
            try (InputStream in = dest.cr.openInputStream(doc(dest, x.docId)); OutputStream o = dest.cr.openOutputStream(tmp, "w")) {
                if (in == null || o == null) throw new Exception("Kopiëren lukt niet: " + to);
                byte[] b = new byte[1 << 16];
                int k;
                while ((k = in.read(b)) > 0) { o.write(b, 0, k); n += k; }
            }
            if (x.size >= 0 && n != x.size) throw new Exception("Kopie onvolledig: " + to);
            done = WaBackup.rename(dest, tmp, to);
            if (done == null) throw new Exception("Hernoemen lukt niet: " + to);
        } catch (Exception e) {
            try { DocumentsContract.deleteDocument(dest.cr, tmp); } catch (Exception ignored) { }
            throw e;
        }
        delete(dest, x); // pas nu: de kopie staat er compleet
        return done;
    }

    static void delete(WaBackup.Dest dest, WaBackup.Child x) throws Exception {
        if (!DocumentsContract.deleteDocument(dest.cr, doc(dest, x.docId))) throw new Exception("Verwijderen geweigerd");
    }

    /** Een dubbel bestand (of de map naast een bestand) als oude versie ernaast: naam~jjjjmmdd. */
    static void renameAside(WaBackup.Dest dest, WaBackup.DestDir d, WaBackup.Child x, String name) throws Exception {
        String to = WaBackup.versionName(name, d);
        if (WaBackup.rename(dest, doc(dest, x.docId), to) == null) throw new Exception("Hernoemen lukt niet: " + name);
        d.kids.put(to, x);
    }

    /** Zelfde inhoud? Alleen bij gelijke grootte, en tot 32 MB echt nagelezen (anders voor de zekerheid: verschillend). */
    static boolean same(WaBackup.Dest dest, WaBackup.Child a, WaBackup.Child b) {
        if (a.dir || b.dir || a.size < 0 || a.size != b.size || a.size > HASH_MAX) return false;
        try { return MessageDigest.isEqual(sha(dest, a), sha(dest, b)); } catch (Exception e) { return false; }
    }

    static byte[] sha(WaBackup.Dest dest, WaBackup.Child x) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = dest.cr.openInputStream(doc(dest, x.docId))) {
            if (in == null) throw new Exception("niet te lezen");
            byte[] b = new byte[1 << 16];
            int k;
            long n = 0;
            while ((k = in.read(b)) > 0) { md.update(b, 0, k); n += k; if (n > HASH_MAX) throw new Exception("te groot"); }
        }
        return md.digest();
    }

    /** Oudere indelingen omzetten naar de huidige. Versie 1 is de eerste: nog niets om te verplaatsen. */
    static void migrate(Context c, WaBackup.Dest dest, WaBackup.DestDir root, Report r) {
        // Voorbeeld voor later: map "Oud" → "Nieuw": samenvoegen met mergeDir, dan VERSION omhoog.
    }

    /** backup-map.json bovenin: welke indeling, en wanneer er voor het laatst is opgeruimd. */
    static void writeMarker(WaBackup.Dest dest, WaBackup.DestDir root, Report r) {
        try {
            JSONArray parts = new JSONArray();
            for (String[] k : KNOWN) parts.put(new JSONObject().put("map", k[0]).put("onderdeel", k[1]));
            JSONObject o = new JSONObject().put("format", FORMAT).put("version", VERSION).put("app", Version.NAME)
                    .put("opgeruimd", r.t).put("mappen", parts);
            try (Sms.Out w = Sms.open(dest, root, MARKER, "application/json")) { w.write(o.toString(2)); w.done(); }
        } catch (Exception e) { r.errors++; }
    }

    // ---------- vanzelf ----------

    /** Na een automatische opruimronde waarin echt iets veranderde: een melding (tikken = het overzicht). */
    static void notifyChanged(Context c, Report r) {
        int n = r.merged + r.removed + r.renamed + r.cleaned;
        if (n == 0) return;
        try {
            WaBackupService.createChannel(c);
            android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(c, NotifCenter.ch(c, WaBackupService.CHANNEL)) : new android.app.Notification.Builder(c);
            android.content.Intent open = new android.content.Intent(c, MainActivity.class).putExtra("open", "bkmap")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            NotifCenter.post(c, 4205, Taal.n(c, b.setSmallIcon(R.drawable.ic_backup).setContentTitle(Taal.t(c, "Backup-map opgeruimd"))
                    .setContentText(Taal.fill(Taal.t(c, "{n} dubbele of achtergebleven onderdelen opgeruimd"), "n", n)).setAutoCancel(true)
                    .setContentIntent(android.app.PendingIntent.getActivity(c, 25, open,
                            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT)).build()));
        } catch (Throwable ignored) { }
    }

    /** Na de nachtelijke backup: opruimen als dat aan staat. */
    static void nightly(Context c) {
        if (!auto(c) || WaBackup.destUri(c) == null || Herstel.blocked(c) != null) return;
        try { run(c, true, true); } catch (Throwable t) { App.log(c, "BACKUP", "backup-map opruimen: " + AppData.safeMsg(t, "mislukt")); }
    }

    /** Eenmalig na een update met een nieuwe indeling (of de eerste keer): even na het opstarten, op de achtergrond. */
    static void startOnce(Context c) {
        if (prefs(c).getInt("doneVersion", 0) >= VERSION) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Thread.sleep(90_000); // het opstarten niet ophouden
                if (prefs(app).getInt("doneVersion", 0) >= VERSION) return;
                if (!auto(app) || WaBackup.destUri(app) == null || Herstel.blocked(app) != null || busy() != null) return;
                run(app, true, true);
            } catch (Throwable t) { App.log(app, "BACKUP", "backup-map eerste keer: " + AppData.safeMsg(t, "mislukt")); }
        }, "backupmap-once").start();
    }

    static String stateJson(Context c) {
        try {
            JSONObject o = new JSONObject().put("running", RUNNING.get()).put("current", current).put("auto", auto(c))
                    .put("dest", WaBackup.destUri(c) != null).put("version", VERSION).put("done", prefs(c).getInt("doneVersion", 0) >= VERSION);
            String last = prefs(c).getString("last", null);
            if (last != null) o.put("last", new JSONObject(last));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
