package nl.rene.tools;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Gegevens terugzetten na een herinstallatie: herkent de eerste start na een nieuwe installatie, zoekt in de
 * backup-map de versleutelde backups, zet "Alle appgegevens" terug ({@link AppBackup}), start de app opnieuw, en
 * laat daarna zien wat Android niet terugzet (toestemmingen, VPN, toegankelijkheid…). Modellen worden opnieuw gedownload.
 *
 * De toestand staat in no_backup/ (nooit in een backup, ook niet in die van Google): daaraan zie je een nieuwe
 * installatie, ook als Android al gewone instellingen uit de Google-backup terugzette.
 */
final class Herstel {

    private Herstel() { }

    static final String NEW = "nieuw", EXISTING = "bestaand", DONE = "klaar", SKIPPED = "overgeslagen";

    static File stateFile(Context c) { return new File(c.getNoBackupFilesDir(), "installatie.txt"); }
    static File afterFile(Context c) { return new File(c.getNoBackupFilesDir(), "herstel-na.json"); }
    static File rearmFile(Context c) { return new File(c.getNoBackupFilesDir(), "herstel-opnieuw"); }

    /** Eerste keer: nieuw geïnstalleerd (nog nooit bijgewerkt), of een bestaande installatie die bijwerkte naar deze versie. */
    static synchronized String state(Context c) {
        try {
            File f = stateFile(c);
            if (f.isFile()) return Contacts.readText(f, false).trim();
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            // Nieuw geïnstalleerd (de Google-backup kan dan al gewone instellingen en notities hebben teruggezet, daarom niet
            // op gegevens letten), of "Gegevens wissen" in Android (geen toestandsbestand en niets van jezelf meer)
            boolean fresh = pi.firstInstallTime == pi.lastUpdateTime
                    || (!AppBackup.hasLocalData(c) && WaBackup.destUri(c) == null && !AllBackup.prefs(c).contains("last"));
            String s = fresh ? NEW : EXISTING;
            Contacts.writeText(f, s, false);
            return s;
        } catch (Exception e) { return EXISTING; }
    }

    static synchronized void setState(Context c, String s) {
        try { Contacts.writeText(stateFile(c), s, false); } catch (Exception e) { App.log(c, "HERSTEL", "toestand: " + e.getMessage()); }
    }

    /** Nieuwe installatie waarop nog niets is teruggezet (en niet overgeslagen): dan geen nachtelijke backup. */
    static boolean pending(Context c) { return App.unlocked(c) && NEW.equals(state(c)); }

    static final String NOT_YET = "Nog niet: zet eerst je gegevens terug (of kies Overslaan)";

    // ---------- na terugzetten zonder de backup-sleutel ----------

    static File passwordFile(Context c) { return new File(c.getNoBackupFilesDir(), "herstel-wachtwoord"); }

    static final String NEED_PASSWORD = "Stel eerst het wachtwoord van je backup opnieuw in (Versleutelen): na het terugzetten gaan backups alleen versleuteld verder";

    /** Teruggezet, maar de backup-sleutel kon niet mee: geen backups (ook niet leesbaar) tot er weer een wachtwoord is. */
    static boolean passwordNeeded(Context c) {
        try {
            File f = passwordFile(c);
            if (!f.isFile()) return false;
            if (Secure.on(c) && !Secure.broken(c)) { f.delete(); return false; }
            return true;
        } catch (Exception e) { return false; }
    }

    /** Waarom de backups nu stilstaan (of null). */
    static String blocked(Context c) {
        if (pending(c)) return NOT_YET;
        if (passwordNeeded(c)) return NEED_PASSWORD;
        return null;
    }

    /** Loopt er iets dat tijdens terugzetten niet tegelijk mag (het schrijft in de mappen die vervangen worden)? */
    static String busyReason() {
        List<String> l = new ArrayList<>();
        if (CallRecorderService.busy || CallRecorderService.recording) l.add("een gespreksopname");
        if (TracksService.running) l.add("het opnemen van een route");
        if (SleepService.running()) l.add("Slaappraat");
        if (TranscribeService.busy) l.add("gesprekken uitschrijven of een spraakmodel downloaden");
        if (WaBackup.busy) l.add("de WhatsApp-backup");
        if (!ModelDownloadService.RUN.isEmpty() || !ModelDownloadService.QUEUED.isEmpty()) l.add("het downloaden van een model");
        if (AllBackup.busy) l.add("een backup");
        if (l.isEmpty()) return null;
        return "Wacht even of stop eerst " + join(l) + "; daarna kun je alles terugzetten";
    }

    static String join(List<String> l) {
        if (l.size() == 1) return l.get(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size(); i++) b.append(i == 0 ? "" : i == l.size() - 1 ? " en " : ", ").append(l.get(i));
        return b.toString();
    }

    // ---------- backups zoeken ----------

    /** Wat er in de (net gekozen) backup-map staat. */
    static JSONObject scan(Context c) throws Exception {
        Uri tree = WaBackup.destUri(c);
        JSONObject o = new JSONObject().put("dest", tree != null).put("destName", WaBackup.destName(c));
        JSONArray l = new JSONArray();
        if (tree == null) return o.put("archives", l);
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(Secure.DIR, false);
        if (dir != null) {
            List<String> names = new ArrayList<>(dir.kids.keySet());
            List<String> sorted = AppData.archives(names);
            for (String n : sorted) {
                WaBackup.Child ch = dir.kids.get(n);
                if (ch == null || ch.dir) continue;
                l.put(new JSONObject().put("name", n).put("size", ch.size).put("t", ch.mod).put("deels", n.endsWith(Rotate.PARTIAL + ".rtb")));
                if (l.length() >= 60) break;
            }
            // Welke archieven alle appgegevens compleet bevatten (bijgehouden in Versleuteld/appgegevens.txt)
            List<String> withApp = new ArrayList<>();
            WaBackup.Child list = dir.kids.get(APP_LIST);
            if (list != null && !list.dir) withApp = AppData.appNames(readCapped(c, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, list.docId)));
            for (int i = 0; i < l.length(); i++) l.getJSONObject(i).put("app", withApp.contains(l.getJSONObject(i).getString("name")));
            String rec = AppData.recommended(sorted, withApp);
            if (rec != null) o.put("recommended", rec);
        }
        // Gewone (leesbare) onderdelen, als er geen versleutelde backup is
        JSONObject plain = new JSONObject();
        WaBackup.DestDir n = dest.dir(Notes.DIR, false);
        if (n != null && n.kids.containsKey("notities.json")) plain.put("notes", true);
        WaBackup.DestDir s = dest.dir(SettingsBackup.DIR, false);
        if (s != null && s.kids.containsKey(SettingsBackup.FILE)) plain.put("settings", true);
        WaBackup.DestDir k = dest.dir(Contacts.DIR, false);
        if (k != null && !k.kids.isEmpty()) plain.put("contacts", true);
        return o.put("archives", l).put("plain", plain);
    }

    static final String APP_LIST = "appgegevens.txt";

    /** Na een gelukte backup met alle appgegevens: de naam van het archief bijhouden (alleen namen, geen inhoud). */
    static void markArchive(Context c, Secure.Zip zip, String name) {
        try {
            String old = "";
            WaBackup.Child ch = zip.dir.kids.get(APP_LIST);
            if (ch != null && !ch.dir) {
                old = readCapped(c, android.provider.DocumentsContract.buildDocumentUriUsingTree(zip.dest.tree, ch.docId));
            }
            try (Sms.Out w = Sms.open(zip.dest, zip.dir, APP_LIST, "text/plain")) { w.write(AppData.appNamesAdd(old, name, 400)); w.done(); }
        } catch (Exception e) { App.log(c, "BACKUP", "appgegevens.txt: " + AppData.safeMsg(e, "fout")); }
    }

    /** Hooguit 200 kB lezen, wat de opslag ook als grootte opgeeft (sommige geven 0). */
    static String readCapped(Context c, Uri u) {
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) return "";
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0 && b.size() < 200_000) b.write(buf, 0, Math.min(r, 200_000 - b.size()));
            return new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    /**
     * Na terugzetten zonder backup-sleutel: bewust zonder versleutelen verder. Dan gelden de gewone regels voor
     * onversleuteld: leesbare bestanden, privé-onderdelen niet (die vragen apart toestemming), geen Alle appgegevens.
     */
    static void plainOk(Context c) {
        passwordFile(c).delete();
        SharedPreferences.Editor e = AllBackup.prefs(c).edit();
        for (String p : AllBackup.PRIVATE) { e.putBoolean("part_" + p, false); AllBackup.setPlain(c, p, false); }
        e.apply();
        AllBackupJob.ensureScheduled(c);
    }

    static Uri archiveUri(Context c, String name) throws Exception {
        if (name == null || !name.startsWith("backup-") || !name.endsWith(".rtb") || name.contains("/")) throw new Exception("Kies een backup");
        Uri tree = WaBackup.destUri(c);
        if (tree == null) throw new Exception("Kies eerst de backup-map");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir dir = dest.dir(Secure.DIR, false);
        WaBackup.Child ch = dir == null ? null : dir.kids.get(name);
        if (ch == null || ch.dir) throw new Exception("Deze backup staat niet meer in de map");
        return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId);
    }

    /**
     * Opent de gekozen backup met het wachtwoord, leest en controleert hem helemaal en zet alles klaar.
     * Geeft {needPw} / {error, wrongPw} / {old} (backup zonder appgegevens) / {ok, …samenvatting}.
     */
    static JSONObject open(Context c, String name, String pw, AppBackup.Progress pr) throws Exception {
        Uri u = archiveUri(c, name);
        byte[] h;
        long size = -1;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("Bestand niet te openen");
            h = Vault.readHeader(in);
        }
        try (android.database.Cursor cur = c.getContentResolver().query(u, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) size = cur.getLong(0);
        } catch (Exception ignored) { }
        Vault.Key k = null;
        try { Vault.Key mine = Secure.key(c); if (Vault.matches(mine, h)) k = mine; } catch (Exception ignored) { }
        if (k == null) {
            if (pw == null || pw.isEmpty()) return new JSONObject().put("needPw", true);
            char[] p = pw.toCharArray();
            try { k = Vault.keyFor(h, p); }
            catch (java.security.NoSuchAlgorithmException e) { throw new Exception("Deze backup kan op deze Android-versie niet geopend worden"); }
            finally { java.util.Arrays.fill(p, '\0'); }
        }
        if (!AllBackup.tryBegin()) { java.util.Arrays.fill(k.key, (byte) 0); throw new Exception("Er loopt een backup; probeer het zo opnieuw"); }
        try {
            AppBackup.Staged s = AppBackup.stage(c, u, k, size, pr);
            s.name = name;
            // Staat er al iets (ook wat Android zelf terugzette), of is dit geen nieuwe installatie: eerst "Alles vervangen?"
            boolean warn = !pending(c) || AppBackup.hasLocalData(c);
            return AppBackup.summary(s).put("ok", true).put("warn", warn);
        } catch (AppBackup.NoAppData e) {
            java.util.Arrays.fill(k.key, (byte) 0);
            return new JSONObject().put("old", true).put("name", name);
        } catch (Exception e) {
            java.util.Arrays.fill(k.key, (byte) 0);
            String m = AppData.safeMsg(e, "Openen mislukt: de backup is niet te lezen");
            return new JSONObject().put("error", m).put("wrongPw", m.startsWith("Verkeerd wachtwoord"));
        } finally { AllBackup.running.set(false); AllBackup.busy = false; }
    }

    // ---------- na terugzetten ----------

    /** Aangeroepen als het terugzetten klaar is (nog vóór de herstart). */
    static void restored(Context c, boolean bio, boolean keySet, String models, String before) {
        try {
            JSONObject o = new JSONObject().put("t", System.currentTimeMillis()).put("bio", bio).put("keyLost", !keySet);
            JSONObject m = models == null ? new JSONObject() : new JSONObject(models);
            o.put("models", new JSONObject().put("ai", m.optJSONArray("ai") == null ? new JSONArray() : m.optJSONArray("ai"))
                    .put("spraak", m.optJSONArray("spraak") == null ? new JSONArray() : m.optJSONArray("spraak")));
            o.put("before", before == null ? new JSONObject() : new JSONObject(before));
            Contacts.writeText(afterFile(c), o.toString(), false);
            Contacts.writeText(rearmFile(c), "1", false);
            if (keySet) passwordFile(c).delete(); else Contacts.writeText(passwordFile(c), "1", false);
        } catch (Exception e) { App.log(c, "HERSTEL", "na terugzetten: " + AppData.safeMsg(e, "fout")); }
        setState(c, DONE);
    }

    static JSONObject after(Context c) {
        try { File f = afterFile(c); return f.isFile() ? new JSONObject(Contacts.readText(f, false)) : null; } catch (Exception e) { return null; }
    }

    /** Na de herstart: alles opnieuw inplannen wat de app bij opstarten en na een herstart van de telefoon plant. */
    static void afterRestart(Context c) {
        File f = rearmFile(c);
        if (!f.isFile()) return;
        f.delete();
        try { AlarmReceiver.rearmAll(c); } catch (Throwable t) { App.log(c, "HERSTEL", "wekkers: " + t); }
        try { AllBackupJob.schedule(c, 0); } catch (Throwable ignored) { }
        try { WaBackupJob.ensureScheduled(c); } catch (Throwable ignored) { }
        try { Care.ensureScheduled(c); } catch (Throwable ignored) { }
        try { ContactsJob.ensureScheduled(c); } catch (Throwable ignored) { }
        try { SpotifyJob.schedule(c); } catch (Throwable ignored) { }
        try { PodcastDownloadJob.schedule(c); } catch (Throwable ignored) { }
        try { TranscribeJob.ensureScheduled(c); } catch (Throwable ignored) { }
        try { RadioWidget.refresh(c); } catch (Throwable ignored) { }
        try { HomeWidgets.refresh(c, HomeWidgets.Overview.class); } catch (Throwable ignored) { }
        try { Shortcuts.refreshPinned(c); } catch (Throwable ignored) { }
        App.log(c, "HERSTEL", "gegevens teruggezet; alles opnieuw ingepland");
    }

    /** Modellen die er waren opnieuw downloaden (alleen als het netwerk mag: Alleen via wifi telt). */
    static synchronized void resumeModels(Context c) {
        JSONObject o = after(c);
        if (o == null) return;
        JSONObject m = o.optJSONObject("models");
        if (m == null) return;
        boolean netOk = ModelDownloadService.netBlocked(c) == null && ModelDownloadService.online(c);
        try {
            JSONArray ai = m.optJSONArray("ai"), keep = new JSONArray();
            if (ai != null) for (int i = 0; i < ai.length(); i++) {
                LocalAi.Model x = LocalAi.model(ai.optString(i)); // het model dat er was (een nieuwer meldt de app zelf)
                if (x == null || LocalAi.ready(c, x)) continue;
                if (LocalAi.prefs(c).getBoolean("own_" + x.id, false)) continue; // de app downloadt hem al (en gaat vanzelf verder)
                if (netOk && LocalAi.supported()) {
                    String r = LocalAi.download(c, x);
                    if (r.isEmpty()) continue;
                    o.put("modelErr", r);
                }
                keep.put(x.id);
            }
            JSONArray sp = m.optJSONArray("spraak"), keepSp = new JSONArray();
            if (sp != null) for (int i = 0; i < sp.length(); i++) {
                Transcribe.Model x = Transcribe.model(sp.optString(i));
                if (x == null || Transcribe.modelFile(c, x).isFile()) continue;
                keepSp.put(x.id);
                if (netOk && !TranscribeService.busy && keepSp.length() == 1) {
                    Transcribe.prefs(c).edit().putString("status", "{\"running\":true,\"phase\":\"download\",\"pct\":0}").putString("dlModel", x.id).apply();
                    TranscribeService.download(c, x.id);
                }
            }
            m.put("ai", keep).put("spraak", keepSp);
            if (o.optBoolean("done") && keep.length() == 0 && keepSp.length() == 0) afterFile(c).delete(); // alles binnen (of onderweg)
            else Contacts.writeText(afterFile(c), o.toString(), false);
        } catch (Throwable t) { App.log(c, "HERSTEL", "modellen: " + t); }
    }

    // ---------- wat er was (in de backup) en wat er nu is ----------

    static JSONObject modelsJson(Context c) throws Exception {
        JSONArray ai = new JSONArray(), sp = new JSONArray();
        for (LocalAi.Model m : LocalAi.MODELS) if (LocalAi.ready(c, m)) ai.put(m.id);
        for (Transcribe.Model m : Transcribe.MODELS) if (Transcribe.modelFile(c, m).isFile()) sp.put(m.id);
        return new JSONObject().put("ai", ai).put("spraak", sp);
    }

    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    /** Toestemmingen en instellingen van Android die niet mee terug komen: id → aan. */
    static JSONObject perms(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("phone", has(c, Manifest.permission.CALL_PHONE));
        o.put("calllog", has(c, Manifest.permission.READ_CALL_LOG));
        o.put("contacts", has(c, Manifest.permission.READ_CONTACTS) && has(c, Manifest.permission.WRITE_CONTACTS));
        o.put("sms", has(c, Manifest.permission.READ_SMS));
        o.put("history", NotificationHistory.allowed(c));
        o.put("location", has(c, Manifest.permission.ACCESS_FINE_LOCATION));
        if (Build.VERSION.SDK_INT >= 29) o.put("bglocation", has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        o.put("mic", has(c, Manifest.permission.RECORD_AUDIO));
        o.put("calendar", has(c, Manifest.permission.READ_CALENDAR));
        o.put("nearby", ApparatenScan.btPerm(c));
        if (Build.VERSION.SDK_INT >= 33) o.put("notif", has(c, Manifest.permission.POST_NOTIFICATIONS));
        o.put("files", MainActivity.hasFilesAccess(c));
        if (Build.VERSION.SDK_INT >= 26) o.put("install", c.getPackageManager().canRequestPackageInstalls());
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        o.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()));
        if (Build.VERSION.SDK_INT >= 31) { AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); o.put("exact", am != null && am.canScheduleExactAlarms()); }
        o.put("a11y", AutoA11y.enabled(c));
        o.put("home", Launcher.isDefaultHome(c));
        o.put("overlay", FloatPlayer.allowed(c));
        o.put("vpn", Vpn.tunnels(c).length() == 0 || !Vpn.needsConsent(c));
        return o;
    }

    static JSONObject stateJson(Context c) throws Exception {
        return new JSONObject().put("perms", perms(c)).put("t", System.currentTimeMillis());
    }

    /** Uitleg per toestemming: {titel, waarvoor, knop}. */
    static final String[][] ITEMS = {
            {"phone", "Bellen", "Auto redial en bellen vanuit de app"},
            {"calllog", "Oproepgeschiedenis", "Auto redial, Oproepen, Gesprekken uitschrijven"},
            {"contacts", "Contacten", "Namen bij nummers, de Contacten-tool"},
            {"sms", "Sms", "SMS backup"},
            {"history", "Meldingentoegang", "Meldingsgeschiedenis"},
            {"location", "Locatie", "Mijn routes, Mijn auto, automatiseringen op een plek"},
            {"bglocation", "Locatie altijd", "Automatiseringen op een plek en Mijn auto, ook als de app dicht is"},
            {"mic", "Microfoon", "Muziek herkennen, Slaappraat, spraak"},
            {"calendar", "Agenda", "Check-in"},
            {"nearby", "Apparaten in de buurt", "Apparaten (Bluetooth en wifi)"},
            {"notif", "Meldingen", "Herinneringen, voortgang en bediening"},
            {"files", "Alle bestanden", "WhatsApp backup, Gesprekken uitschrijven"},
            {"install", "Updates installeren", "Nieuwe versies van de app"},
            {"battery", "Batterijbeperking uit", "De nachtelijke backup en de radiowekker"},
            {"exact", "Wekkers en herinneringen", "Op tijd afgaan van de radiowekker en herinneringen"},
            {"a11y", "Toegankelijkheid", "Automatiseringen die tikken in andere apps"},
            {"home", "Standaard-startscherm", "De Telefoon-skin als startscherm"},
            {"overlay", "Boven andere apps", "De zwevende speler"},
            {"vpn", "VPN-toestemming", "Je VPN-tunnels (één keer toestaan bij het aanzetten)"},
    };

    /** Het lijstje na terugzetten: wat Android niet terugzet (alleen wat er vóór de backup aan stond), en de modellen. */
    static JSONObject checklist(Context c) throws Exception {
        JSONObject a = after(c);
        if (a == null) return new JSONObject().put("items", new JSONArray());
        try { resumeModels(c); a = after(c); } catch (Throwable ignored) { }
        if (a == null) return new JSONObject().put("items", new JSONArray());
        JSONObject before = a.optJSONObject("before"), was = before == null ? null : before.optJSONObject("perms"), now = perms(c);
        JSONArray items = new JSONArray();
        for (String[] it : ITEMS) {
            if (!now.has(it[0])) continue;
            boolean had = was == null ? !"home".equals(it[0]) && !"overlay".equals(it[0]) && !"a11y".equals(it[0]) && !"install".equals(it[0]) : was.optBoolean(it[0]);
            if (!had) continue;
            items.put(new JSONObject().put("id", it[0]).put("name", it[1]).put("used", it[2]).put("ok", now.optBoolean(it[0])));
        }
        if (a.optBoolean("bio")) items.put(new JSONObject().put("id", "bio").put("name", "Vingerafdruk bij de app-code").put("used", "Zet hem weer aan bij Instellingen → App-slot").put("ok", Lock.bioWithCode(c)));
        if (a.optBoolean("keyLost") || passwordNeeded(c) || !Secure.on(c)) items.put(new JSONObject().put("id", "password").put("name", "Wachtwoord van de backup")
                .put("used", passwordNeeded(c) ? "Opnieuw instellen (hetzelfde als vroeger mag): tot dan maakt de app geen backups" : "Stel het opnieuw in, dan gaan de nachtelijke backups weer versleuteld")
                .put("ok", Secure.on(c) && !Secure.broken(c)));
        if (WaChats.key(c) != null) items.put(new JSONObject().put("id", "wa").put("name", "Leesbare WhatsApp-chats").put("used", "Worden opnieuw gemaakt uit de WhatsApp-backup (WhatsApp backup → Leesbare chats)").put("ok", WaChats.dbFile(c).isFile()).put("info", true));
        items.put(new JSONObject().put("id", "widgets").put("name", "Widgets").put("used", "Widgets op het startscherm van de telefoon moet je opnieuw plaatsen").put("ok", true).put("info", true));
        // Modellen
        JSONObject m = a.optJSONObject("models");
        JSONArray names = new JSONArray();
        long bytes = 0;
        if (m != null) {
            JSONArray ai = m.optJSONArray("ai"), sp = m.optJSONArray("spraak");
            if (ai != null) for (int i = 0; i < ai.length(); i++) { LocalAi.Model x = LocalAi.model(ai.optString(i)); if (x != null) { names.put(x.label); bytes += x.size; } }
            if (sp != null) for (int i = 0; i < sp.length(); i++) { Transcribe.Model x = Transcribe.model(sp.optString(i)); if (x != null) { names.put("Spraakmodel " + x.label); bytes += x.size; } }
        }
        JSONObject r = new JSONObject().put("items", items).put("t", a.optLong("t"));
        String why = ModelDownloadService.netBlocked(c);
        r.put("models", new JSONObject().put("names", names).put("bytes", bytes).put("wait", why == null ? "" : why).put("err", a.optString("modelErr", "")));
        return r;
    }

    /** Lijstje gezien. Modellen die nog moeten komen (bijv. wachten op wifi) blijven onthouden tot ze er zijn. */
    static synchronized void checklistDone(Context c) {
        JSONObject a = after(c);
        try {
            JSONObject m = a == null ? null : a.optJSONObject("models");
            boolean left = m != null && ((m.optJSONArray("ai") != null && m.optJSONArray("ai").length() > 0) || (m.optJSONArray("spraak") != null && m.optJSONArray("spraak").length() > 0));
            if (left) { Contacts.writeText(afterFile(c), a.put("done", true).toString(), false); return; }
        } catch (Exception ignored) { }
        afterFile(c).delete();
    }

    /** Moet het lijstje nog getoond worden? */
    static boolean showList(Context c) { JSONObject a = after(c); return a != null && !a.optBoolean("done"); }

    // ---------- opnieuw starten ----------

    /** Start de app opnieuw (eigen proces), zodat alles wat in het geheugen stond opnieuw uit de bestanden komt. */
    static void restart(Activity a) {
        try {
            Intent i = new Intent(a, HerstartActivity.class).putExtra("pid", android.os.Process.myPid()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            a.finishAffinity();
        } catch (Throwable t) { App.log(a, "HERSTEL", "opnieuw starten: " + t); }
        Runtime.getRuntime().exit(0);
    }

    /** Voor Alles back-uppen: is de backup klaar voor een herinstallatie? */
    static JSONObject ready(Context c) throws Exception {
        boolean enc = Secure.on(c) && !Secure.broken(c), dest = WaBackup.destUri(c) != null, auto = AllBackup.auto(c), part = AllBackup.enabledPart(c, "app");
        long last = AllBackup.prefs(c).getLong("appLast", 0);
        JSONArray miss = new JSONArray();
        String blocked = blocked(c);
        if (blocked != null) miss.put(blocked);
        if (!dest) miss.put("Kies een backup-map");
        if (!enc) miss.put("Zet Versleutelen aan (wachtwoord)");
        if (!part) miss.put("Vink Alle appgegevens aan");
        if (!auto) miss.put("Zet Elke nacht alles back-uppen aan");
        if (last == 0) miss.put("Nog geen complete backup gemaakt");
        // Hint bij de grootte: elke nacht komt er een volledig archief bij; opruimen kiest Rene zelf
        String lastRes = AllBackup.prefs(c).getString("last", null);
        boolean stopped = false;
        try { JSONObject lr = lastRes == null ? null : new JSONObject(lastRes); JSONObject ap = lr == null ? null : lr.optJSONObject("app"); stopped = ap != null && AllBackup.STOPPED.equals(ap.optString("msg")); }
        catch (Exception ignored) { }
        return new JSONObject().put("ok", miss.length() == 0).put("missing", miss).put("last", last).put("blocked", blocked == null ? "" : blocked)
                .put("rotate", AllBackup.prefs(c).getBoolean("rotate", false)).put("appBytes", AllBackup.prefs(c).getLong("appBytes", 0)).put("stopped", stopped)
                .put("lastName", AllBackup.prefs(c).getString("appLastName", "")).put("enc", enc).put("dest", dest).put("auto", auto).put("part", part);
    }

}
