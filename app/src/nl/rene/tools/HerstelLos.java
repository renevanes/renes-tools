package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Alles terugzetten uit de gewone (leesbare) onderdelen in de backup-map, in één keer: zoekt per onderdeel het
 * backupbestand op zijn vaste plek, laat zien wat er nieuw is, en zet daarna alles achter elkaar terug.
 * Zoals bij losse onderdelen: alleen wat hier nog ontbreekt wordt toegevoegd; er wordt nooit iets overschreven of
 * verwijderd. Wachtwoorden en sleutels staan nooit in deze bestanden (die komen alleen uit een versleutelde backup).
 */
final class HerstelLos {

    private HerstelLos() { }

    /** Onderdeel → map en bestand in de backup-map. Volgorde = volgorde van terugzetten (instellingen eerst). */
    static final String[][] PARTS = {
            {"settings", SettingsBackup.DIR, SettingsBackup.FILE},
            {"tooldata", ToolData.DIR, ToolData.FILE},
            {"notes", Notes.DIR, "notities.json"},
            {"contacts", Contacts.DIR, ""},             // nieuwste contacten-JJJJ-MM-DD.vcf
            {"launcher", LauncherBackup.DIR, LauncherBackup.FILE},
            {"transcripts", Transcribe.DIR, SettingsBackup.TX_FILE},
            {"music", "Muziek", "herkende-nummers.json"},
            {"route", SettingsBackup.ROUTES_DIR, ""},    // elke .gpx
            {"ai", AiSleepBackup.AI_DIR, AiSleepBackup.AI_FILE},
            {"sleep", AiSleepBackup.SLEEP_DIR, AiSleepBackup.SLEEP_FILE},
            {"checkin", CheckinStore.DIR, CheckinStore.FILE},
    };

    /** Staan wel in de backup-map, maar Android (of de app) kan ze niet terugzetten: alleen ter informatie. */
    static final String[][] INFO = {
            {"sms", Sms.DIR, Taal.T("Sms'jes terugzetten kan alleen de standaard-sms-app; de backup blijft leesbaar in je map")},
            {"calls", Calls.DIR, Taal.T("De oproepgeschiedenis kan een app niet terugzetten; de backup blijft leesbaar in je map")},
            {"notifications", NotificationHistory.DIR, Taal.T("Een overzicht om terug te lezen; meldingen komen niet terug")},
            {"wa", "WhatsApp", Taal.T("WhatsApp zet je terug in WhatsApp zelf (bij het installeren)")},
    };

    static final int MAX_ROUTES = 2000;

    static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Gevonden bestand(en) van één onderdeel. */
    static final class Found {
        final String kind;
        final List<String> names = new ArrayList<>();
        final List<Uri> uris = new ArrayList<>();
        long mod;
        Found(String k) { kind = k; }
    }

    /** Nieuwste contacten-JJJJ-MM-DD.vcf (geen versies: dat is een oude stand). */
    static String newestContacts(java.util.Collection<String> names) {
        String best = null;
        for (String n : names) {
            if (!n.matches("contacten-\\d{4}-\\d{2}-\\d{2}\\.vcf")) continue;
            if (best == null || n.compareTo(best) > 0) best = n;
        }
        return best;
    }

    /** GPX-bestanden van routes, gesorteerd (oudste eerst, op naam). */
    static List<String> gpxNames(java.util.Collection<String> names) {
        List<String> l = new ArrayList<>();
        for (String n : names) if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".gpx") && !n.startsWith(".")) l.add(n);
        Collections.sort(l);
        while (l.size() > MAX_ROUTES) l.remove(0);
        return l;
    }

    /** Alles opzoeken wat er in de backup-map staat. */
    static List<Found> find(Context c, WaBackup.Dest dest) throws Exception {
        List<Found> out = new ArrayList<>();
        for (String[] p : PARTS) {
            // Het startscherm terugzetten vervangt de indeling: in één keer alleen als hier nog geen eigen indeling staat
            if ("launcher".equals(p[0]) && WsStore.prefs(c).contains("ws")) continue;
            WaBackup.DestDir d = dest.dir(p[1], false);
            if (d == null) continue;
            Found f = new Found(p[0]);
            List<String> names = new ArrayList<>();
            if ("contacts".equals(p[0])) { String n = newestContacts(d.kids.keySet()); if (n != null) names.add(n); }
            else if ("route".equals(p[0])) names.addAll(gpxNames(d.kids.keySet()));
            else names.add(p[2]);
            for (String n : names) {
                WaBackup.Child ch = d.kids.get(n);
                if (ch == null || ch.dir) continue;
                f.names.add(n);
                f.uris.add(DocumentsContract.buildDocumentUriUsingTree(dest.tree, ch.docId));
                f.mod = Math.max(f.mod, ch.mod);
            }
            if (!f.names.isEmpty()) out.add(f);
        }
        return out;
    }

    static WaBackup.Dest dest(Context c) throws Exception {
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst de backup-map");
        return new WaBackup.Dest(c.getContentResolver(), tree);
    }

    /** Netjes vergeten wat een overzicht klaarzette (er verandert niets). */
    static void forget(Context c) {
        Restore.clear();
        CheckinStore.cancel();
        try { AiSleepBackup.cancel(c); } catch (Throwable ignored) { }
    }

    /**
     * Overzicht: {dest, destName, parts:[{kind, total, fresh, file, t, error?}], info:[{kind, msg}], fresh}.
     * Er verandert nog niets.
     */
    static JSONObject scan(Context c) throws Exception {
        if (AllBackup.busy) throw new Exception(Taal.T("Er loopt een backup; probeer het zo opnieuw"));
        if (Restore.pendingKind != null) throw new Exception(Taal.T("Rond eerst het terugzetten van het losse onderdeel af"));
        if (!BUSY.compareAndSet(false, true)) throw new Exception(Taal.T("Er wordt al teruggezet; even geduld"));
        try {
            WaBackup.Dest dest = dest(c);
            JSONObject o = new JSONObject().put("dest", true).put("destName", WaBackup.destName(c));
            JSONArray parts = new JSONArray();
            int fresh = 0;
            for (Found f : find(c, dest)) {
                JSONObject r = new JSONObject().put("kind", f.kind).put("t", f.mod)
                        .put("file", f.names.get(0)).put("files", f.names.size());
                try {
                    if ("contacts".equals(f.kind) && !contactsOk(c)) {
                        r.put("total", 0).put("fresh", 0).put("needPerm", true); // zonder toestemming niet te zien wat er al is
                    } else if ("route".equals(f.kind)) {
                        // Op naam vergelijken (snel, zonder elke route te lezen); terugzetten kijkt daarna echt naar het begintijdstip
                        java.util.Set<String> have = new java.util.HashSet<>();
                        for (File t : Tracks.saved(c)) have.add(Tracks.gpxName(t));
                        int n = 0;
                        for (String nm : f.names) if (!have.contains(nm)) n++;
                        r.put("total", f.uris.size()).put("fresh", n);
                    } else {
                        String text = Restore.read(c, f.uris.get(0));
                        synchronized (Restore.class) {
                            try {
                                JSONObject p = Restore.previewText(c, f.kind, text);
                                r.put("total", p.optInt("total")).put("fresh", p.optInt("fresh"));
                                if (p.has("sample")) r.put("sample", p.optJSONArray("sample"));
                            } finally { forget(c); }
                        }
                    }
                } catch (Throwable e) {
                    r.put("error", e instanceof OutOfMemoryError ? Taal.T("Bestand is te groot voor deze telefoon") : AppData.safeMsg(e, "Niet te lezen"));
                }
                fresh += r.optInt("fresh");
                parts.put(r);
            }
            JSONArray info = new JSONArray();
            if (WsStore.prefs(c).contains("ws") && dest.dir(LauncherBackup.DIR, false) != null)
                info.put(new JSONObject().put("kind", "launcher").put("msg", Taal.T("Je startscherm heeft al een indeling; die wordt niet vervangen. Wil je de oude terug, kies dan hieronder Startscherm (skin)")));
            for (String[] i : INFO) {
                WaBackup.DestDir d = dest.dir(i[1], false);
                if (d != null && !d.kids.isEmpty()) info.put(new JSONObject().put("kind", i[0]).put("msg", i[2]));
            }
            return o.put("parts", parts).put("info", info).put("fresh", fresh).put("contactsPerm", contactsOk(c));
        } finally { BUSY.set(false); }
    }

    /** Voortgang: onderdeel en hoeveelste. */
    interface Progress { void step(String kind, int done, int total); }

    /**
     * Alles terugzetten (alleen wat ontbreekt), onderdeel na onderdeel; een fout bij één onderdeel stopt de rest niet.
     * Geeft {parts:[{kind, ok, count, msg?}], ok}.
     */
    static JSONObject apply(Context c, Progress pr) throws Exception {
        String busy = Herstel.busyReason();
        if (busy != null) throw new Exception(busy);
        if (Restore.pendingKind != null) throw new Exception(Taal.T("Rond eerst het terugzetten van het losse onderdeel af"));
        if (!BUSY.compareAndSet(false, true)) throw new Exception(Taal.T("Er wordt al teruggezet; even geduld"));
        if (!AllBackup.tryBegin()) { BUSY.set(false); throw new Exception("Er loopt een backup; probeer het zo opnieuw"); }
        try {
            WaBackup.Dest dest = dest(c);
            List<Found> all = find(c, dest);
            JSONArray parts = new JSONArray();
            boolean allOk = true;
            for (int i = 0; i < all.size(); i++) {
                Found f = all.get(i);
                if (pr != null) pr.step(f.kind, i, all.size());
                JSONObject r = new JSONObject().put("kind", f.kind);
                try {
                    if ("contacts".equals(f.kind) && !contactsOk(c)) {
                        r.put("ok", false).put("count", 0).put("msg", Taal.T("Geef eerst toestemming voor contacten; daarna nog een keer"));
                        allOk = false; parts.put(r); continue;
                    }
                    int n = 0, bad = 0;
                    if ("route".equals(f.kind)) {
                        for (Uri u : f.uris) {
                            try { n += Tracks.importGpx(c, Restore.read(c, u)); } catch (Exception e) { bad++; } // een kapotte route slaat de rest niet over
                        }
                    } else {
                        String text = Restore.read(c, f.uris.get(0));
                        synchronized (Restore.class) { // niemand anders tussen overzicht en toevoegen
                            JSONObject p = Restore.previewText(c, f.kind, text);
                            if (p.optInt("fresh") > 0) n = Restore.apply(c);
                            else forget(c);
                        }
                    }
                    r.put("ok", bad == 0).put("count", n);
                    if (bad > 0) { allOk = false; r.put("msg", Taal.fill(Taal.T("{n} routes niet te lezen"), "n", bad)); }
                } catch (Throwable e) {
                    forget(c);
                    allOk = false;
                    r.put("ok", false).put("count", 0).put("msg", e instanceof OutOfMemoryError ? Taal.T("Bestand is te groot voor deze telefoon") : AppData.safeMsg(e, "Terugzetten mislukt"));
                    App.log(c, "HERSTEL", f.kind + ": " + AppData.safeMsg(e, "mislukt"));
                }
                parts.put(r);
            }
            if (pr != null) pr.step("", all.size(), all.size());
            try { Herstel.rearmFile(c).createNewFile(); Herstel.afterRestart(c); } catch (Throwable ignored) { }
            // Een nieuwe installatie: pas als alles gelukt is mogen de nachtelijke backups weer (anders zou een backup
            // de bestanden die je nog nodig hebt, overschrijven). Anders kun je het opnieuw proberen of Overslaan kiezen.
            if (allOk && !all.isEmpty() && Herstel.NEW.equals(Herstel.state(c))) Herstel.setState(c, Herstel.DONE);
            App.log(c, "HERSTEL", "losse onderdelen teruggezet: " + parts.length());
            return new JSONObject().put("parts", parts).put("ok", allOk);
        } finally {
            AllBackup.running.set(false); AllBackup.busy = false;
            BUSY.set(false);
        }
    }

    /** Alleen voor de map: welke onderdelen er staan (namen), zonder iets te lezen. Voor de scan van Herstel. */
    static JSONArray kinds(Context c, WaBackup.Dest dest) {
        JSONArray a = new JSONArray();
        try { for (Found f : find(c, dest)) a.put(f.kind); } catch (Exception ignored) { }
        return a;
    }

    /** Contacten lezen én schrijven mag (anders kan de app niet zien welke er al zijn). */
    static boolean contactsOk(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }
}
