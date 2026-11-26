package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Alles back-uppen: sms, oproepen, contacten, notities, gesprekken, herkende muziek, routes, instellingen en de overige
 * toolgegevens (Mijn auto, Facturen, Apparaten …) in één keer
 * (en als je dat aanvinkt: meldingen, het geheugen en de gesprekken van de assistent, Slaappraat en Check-in)
 * naar de backup-map, met per onderdeel de uitkomst. Draait op verzoek of elke nacht (AllBackupJob).
 * WhatsApp heeft een eigen nachtelijke backup en wordt alleen bij handmatig starten meegenomen.
 */
final class AllBackup {

    private AllBackup() { }

    static final String[] PARTS = {"sms", "calls", "notifications", "contacts", "notes", "launcher", "transcripts", "music", "settings", "tooldata", "routes", "ai", "sleep", "checkin", "kluis", "app"};

    /** Privé: alleen versleuteld mee, of onversleuteld na uitdrukkelijke toestemming (plain_…). */
    static final String[] PRIVATE = {"ai", "sleep", "checkin"};

    static boolean priv(String part) { for (String p : PRIVATE) if (p.equals(part)) return true; return false; }

    /** Gevoelig: staan standaard uit en gaan alleen mee als je ze aanvinkt (zonder versleutelen als leesbaar bestand). */
    static boolean optIn(String part) { return "notifications".equals(part) || priv(part); }

    /** Slaappraat met geluid (WAV) of alleen de tekst (standaard). */
    static boolean sleepAudio(Context c) { return prefs(c).getBoolean("sleep_audio", false); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("allbackup", Context.MODE_PRIVATE); }

    /** Oude backups vanzelf opruimen: standaard aan (wens van Rene); zelf uitgezet blijft uit. */
    static boolean rotateOn(Context c) { return prefs(c).getBoolean("rotate", true); }

    /** Hoeveel volledige backups er blijven (Rotate.KEEP_CHOICES). Wie opruimen al aan had gezet zonder deze keuze,
     *  houdt de regel van toen (0 = de laatste 14 dagen en de nieuwste per maand). */
    static int keep(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("keep")) return Rotate.keep(p.getInt("keep", Rotate.KEEP_DEFAULT));
        return p.getBoolean("rotate", false) ? 0 : Rotate.KEEP_DEFAULT;
    }

    /**
     * Elke nacht vanzelf. Zelf aan- of uitgezet: zo blijft het. Nog nooit gekozen: alleen aan als versleutelen aan staat
     * en er een backup-map is (dan gaat er nooit ongevraagd elke nacht leesbaar sms, oproepen en notities in de map).
     */
    static boolean auto(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("auto")) return p.getBoolean("auto", false);
        return Secure.on(c) && WaBackup.destUri(c) != null;
    }

    /** Alle appgegevens (voor terugzetten na herinstallatie) gaan alleen mee in een versleutelde backup. */
    static final String APP_PLAIN = "Niet mee: alleen in een versleutelde backup (zet Versleutelen aan)";

    /** Grote bestanden één keer bewaren in Versleuteld/Grote bestanden (standaard aan; uit = elk archief compleet, zoals vroeger). */
    static boolean blokken(Context c) { return prefs(c).getBoolean("blokken", true); }

    /** Hoeveel nachtelijke pogingen er vannacht mislukten (0 = geen, of te lang geleden om nog te tonen). */
    static int pogingNu(Context c) {
        long sinds = prefs(c).getLong("pogingSinds", 0), nu = System.currentTimeMillis();
        return sinds > 0 && sinds <= nu && nu - sinds < 12 * 60 * 60 * 1000L ? prefs(c).getInt("pogingen", 0) : 0;
    }

    /** Er kan maar één backup tegelijk lopen. */
    static final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);
    static volatile boolean busy = false;
    static volatile boolean stop = false;    // Android beëindigt de nachtelijke taak: netjes stoppen
    static volatile String current = "";

    /** Probeert een backup te starten; false als er al een loopt. */
    static boolean tryBegin() {
        if (!running.compareAndSet(false, true)) return false;
        busy = true;
        stop = false;
        return true;
    }

    /** Na tryBegin() buiten run() (opruimen met de hand): weer vrijgeven. */
    static void end() { busy = false; running.set(false); }

    static final String STOPPED = "Gestopt: Android beëindigde de backup (volgende keer opnieuw)";

    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    static boolean enabledPart(Context c, String part) { return prefs(c).getBoolean("part_" + part, !optIn(part)); }

    /**
     * Toestemming om assistent of Slaappraat onversleuteld mee te nemen (alleen na de vraag "Onversleuteld meenemen?").
     * Staat in secure.xml: niet in de Android-backup en niet in de instellingen-backup.
     */
    static boolean plainOk(Context c, String part) { return Secure.prefs(c).getBoolean("plain_" + part, false); }
    static void setPlain(Context c, String part, boolean on) { Secure.prefs(c).edit().putBoolean("plain_" + part, on).apply(); }

    /** Gaat dit onderdeel echt mee? (Privé zonder versleutelen alleen met toestemming.) */
    static boolean effectivePart(Context c, String part) {
        if (!enabledPart(c, part)) return false;
        return !priv(part) || Secure.on(c) || plainOk(c, part);
    }

    /** Voert de backup uit (na tryBegin). Geeft per onderdeel {ok, count, msg}; slaat de uitkomst op. */
    static JSONObject run(Context c, boolean auto) {
        JSONObject res = new JSONObject();
        HomeWidgets.refresh(c, HomeWidgets.Overview.class); // "Backup is bezig…"
        Secure.Zip zip = null;
        try {
            if (WaBackup.destUri(c) == null) {
                put(res, "all", false, 0, "Kies eerst een backup-map (Instellingen)");
                return res;
            }
            // Nieuwe installatie waarop je gegevens nog terug moeten: geen (lege) backup die de goede kan verdringen.
            // Teruggezet zonder backup-sleutel: geen backups (ook niet leesbaar) tot het wachtwoord er weer is.
            String blocked = Herstel.blocked(c);
            if (blocked != null) {
                put(res, "all", false, 0, blocked);
                return res;
            }
            AppBackup.clearTemp(c); // ontsleutelde kluisbestanden van een afgebroken backup
            if (Secure.on(c)) {
                try {
                    zip = Secure.begin(c, Secure.key(c));
                } catch (Exception e) {
                    put(res, "all", false, 0, e.getMessage() != null ? e.getMessage() : "Versleuteld archief maken lukt niet");
                    App.log(c, "BACKUP", "archief maken mislukt: " + AppData.safeMsg(e, "onbekend"));
                    return res;
                }
                Secure.CAPTURE.set(zip);
            }
            for (String part : PARTS) {
                if (!enabledPart(c, part)) continue;
                // De kluis gaat alleen mee in een versleutelde backup. Zonder versleutelen geen fout (anders zou het
                // automatisch opruimen nooit meer lopen), wel een duidelijke melding bij het resultaat.
                if ("kluis".equals(part) && !Secure.on(c)) {
                    try { if (Kluis.list(c).length() > 0) put(res, part, true, -1, "Niet mee: de kluis gaat alleen in een versleutelde backup (zet Versleutelen aan)"); }
                    catch (Exception ignored) { }
                    continue;
                }
                if ("app".equals(part) && !Secure.on(c)) { put(res, part, true, -1, APP_PLAIN); continue; }
                // Android beëindigt de taak: de rest niet meer, maar wel zichtbaar als mislukt; wat klaar is blijft bewaard
                if (stop) { try { res.put("stopped", true); } catch (Exception ignored) { } put(res, part, false, 0, STOPPED); continue; }
                // Privé zonder versleutelen alleen met uitdrukkelijke toestemming (die staat niet in een backup; na een
                // nieuwe installatie of teruggezette instellingen gaat het dus nooit stilletjes leesbaar mee)
                if (priv(part) && !Secure.on(c) && !plainOk(c, part)) {
                    put(res, part, true, -1, "Niet mee: privé, zet Versleutelen aan (of vink het opnieuw aan)");
                    continue;
                }
                current = part;
                long ruw0 = zip == null ? 0 : zip.ruw, uit0 = zip == null ? 0 : zip.archiefBytes(), blok0 = zip == null || zip.blok == null ? 0 : zip.blok.opgeslagen;
                try {
                    int n = runPart(c, part);
                    // Geheugen, gesprekken en Slaappraat zonder versleutelen: in de uitkomst laten zien dat het leesbaar is
                    boolean plain = n > 0 && zip == null && priv(part);
                    put(res, part, true, n, n < 0 ? "Niets te bewaren" : plain ? n + " (niet versleuteld)" : null);
                    // Hoe groot dit onderdeel is, en hoeveel er deze keer echt bij kwam (archief plus nieuwe grote bestanden)
                    if (zip != null && n > 0) {
                        JSONObject r = res.optJSONObject(part);
                        if (r != null) r.put("b", zip.ruw - ruw0).put("o", (zip.archiefBytes() - uit0) + (zip.blok == null ? 0 : zip.blok.opgeslagen - blok0));
                    }
                    // Onthouden dat deze telefoon zelf een leesbare kopie schreef (alleen die mag later weg)
                    if (plain) Secure.prefs(c).edit().putBoolean("plain_written_" + part, true).apply();
                } catch (Throwable e) {
                    String orig = e instanceof OutOfMemoryError ? "onvoldoende geheugen" : e.getMessage() != null ? e.getMessage() : "mislukt";
                    String m = stop ? STOPPED : orig;
                    if (stop) try { res.put("stopped", true); } catch (Exception ignored) { }
                    put(res, part, false, 0, m);
                    App.log(c, "BACKUP", part + ": " + m + (stop ? " (" + orig + ")" : ""));
                }
            }
            Secure.CAPTURE.remove();
            if (zip != null) {
                boolean any = false;
                for (String p : PARTS) { JSONObject r = res.optJSONObject(p); if (r != null && r.optBoolean("ok") && r.optInt("count") > 0) any = true; }
                // Ook na stoppen het archief afmaken met wat klaar is (de onderdelen daarna staan als mislukt)
                if (!any) Secure.abort(zip);
                else {
                    boolean done = false;
                    try {
                        String name = Secure.finish(zip, res);
                        res.put("archive", name); done = true;
                        BlokOpslag.vastleggen(c, name, zip.blok);
                        if (zip.blok != null) {
                            JSONObject g = new JSONObject().put("nieuw", zip.blok.nieuw).put("nieuwBytes", zip.blok.nieuwBytes)
                                    .put("hergebruikt", zip.blok.hergebruikt).put("hergebruiktBytes", zip.blok.hergebruiktBytes)
                                    .put("archief", zip.archiefBytes());
                            // Apart opslaan lukte niet: de rest zit in het archief zelf (compleet, alleen groter)
                            if (zip.blokFout != null) {
                                g.put("fout", AppData.safeMsg(zip.blokFout, "mislukt")).put("fouten", zip.blokFouten);
                                App.log(c, "BACKUP", "grote bestanden apart: " + zip.blokFouten + " keer mislukt"
                                        + (zip.blokFouten >= Blokken.MAX_FOUTEN ? " (de rest in het archief)" : "") + ", laatste: " + AppData.safeMsg(zip.blokFout, "mislukt"));
                            }
                            if (zip.blok.terugOpnieuw > 0) g.put("terugOpnieuw", zip.blok.terugOpnieuw);
                            res.put("blok", g);
                        }
                        // Laatste complete backup voor herinstallatie (alle appgegevens erin, archief niet afgebroken)
                        JSONObject ap = res.optJSONObject("app");
                        if (ap != null && ap.optBoolean("ok") && ap.optInt("count") > 0 && !res.optBoolean("stopped")) {
                            prefs(c).edit().putLong("appLast", System.currentTimeMillis()).putString("appLastName", name).putLong("appBytes", AppBackup.lastBytes).apply();
                            Herstel.markArchive(c, zip, name);
                        }
                    }
                    catch (Exception e) { put(res, "all", false, 0, "Archief afsluiten mislukt: " + e.getMessage()); App.log(c, "BACKUP", "archief afsluiten mislukt: " + AppData.safeMsg(e, e.getClass().getSimpleName())); }
                    // Staat het privé-deel nu versleuteld in het archief, dan de oude leesbare kopie uit de backup-map weg
                    // (ook als er niets te bewaren was: dan hoort er ook geen oude leesbare kopie te blijven staan)
                    if (done) for (String p : PRIVATE) {
                        JSONObject r = res.optJSONObject(p);
                        // Alleen een leesbare kopie die deze telefoon zelf schreef (niet die van een vorige telefoon in dezelfde map)
                        if (r != null && r.optBoolean("ok") && Secure.prefs(c).getBoolean("plain_written_" + p, false)) AiSleepBackup.removePlain(c, zip.dest.tree, p);
                    }
                }
                zip = null;
            }
            boolean allOk = !res.has("all");
            for (String p : PARTS) { JSONObject r = res.optJSONObject(p); if (r != null && !r.optBoolean("ok")) allOk = false; }
            // Met de hand gelukt terwijl de nachtelijke nog opnieuw zou proberen: die is daarmee klaar voor vandaag
            if (!auto && !stop && allOk && prefs(c).getInt("pogingen", 0) > 0)
                prefs(c).edit().putLong("lastAutoDay", System.currentTimeMillis()).remove("pogingen").remove("pogingSinds").apply();
            // Alleen opruimen na een volledig gelukte backup, anders kan een onvolledige backup de goede verdringen.
            if (!stop && allOk && rotateOn(c)) {
                try { JSONObject r = Secure.rotate(c, false); if (r.optInt("count") > 0) res.put("rotated", r); }
                catch (Exception e) { App.log(c, "BACKUP", "opruimen: " + e.getMessage()); }
            }
            // Grote bestanden: wat geen archief meer gebruikt weg, een stuk controleren, en kijken of er nog oude grote
            // archieven staan (voor de knop bij Oude backups opruimen). Alleen na een volledig gelukte versleutelde backup.
            if (!stop && allOk && Secure.on(c)) grote(c, res);
            // Hoe groot de backup was en wat erbij kwam (alleen getallen en vaste namen van onderdelen), zodat het
            // nalopen van het foutlogboek ziet waar de backup groeit
            if (res.has("archive")) { try { App.log(c, "BACKUP", samenvatting(res)); } catch (Throwable ignored) { } }
        } finally {
            Secure.CAPTURE.remove();
            if (zip != null) Secure.abort(zip);
            current = "";
            try {
                res.put("t", System.currentTimeMillis()).put("auto", auto);
                prefs(c).edit().putString("last", res.toString()).apply(); // ook na stoppen: dan is te zien wat niet meer lukte
                HomeWidgets.refresh(c, HomeWidgets.Overview.class);
            } catch (Exception ignored) { }
            busy = false;
            running.set(false);
        }
        return res;
    }

    /**
     * Eén regel voor het foutlogboek na een backup met archief: grootte van het archief, grote bestanden (nieuw en
     * hergebruikt) en de onderdelen waar het meeste bij kwam. Alleen getallen en de vaste namen uit PARTS. Zuiver.
     */
    static String samenvatting(JSONObject res) {
        int mis = 0;
        for (String p : PARTS) { JSONObject r = res.optJSONObject(p); if (r != null && !r.optBoolean("ok", true)) mis++; }
        StringBuilder b = new StringBuilder(res.optBoolean("stopped") ? "backup gestopt" : mis > 0 ? "backup klaar met " + mis + " fout(en)" : "backup klaar");
        JSONObject g = res.optJSONObject("blok");
        if (g != null) {
            b.append(": archief ").append(kb(g.optLong("archief"))).append(", grote bestanden nieuw ").append(g.optInt("nieuw"))
                    .append(" (").append(kb(g.optLong("nieuwBytes"))).append("), hergebruikt ").append(g.optInt("hergebruikt"))
                    .append(" (").append(kb(g.optLong("hergebruiktBytes"))).append(")");
            if (g.has("fout")) b.append(", apart opslaan ").append(Math.max(1, g.optInt("fouten"))).append(" keer mislukt");
            if (g.optInt("terugOpnieuw") > 0) b.append(", ").append(g.optInt("terugOpnieuw")).append(" keer later teruggelezen");
        }
        List<String> groei = new ArrayList<>();
        for (String p : PARTS) { JSONObject r = res.optJSONObject(p); if (r != null && r.optLong("o") > 0) groei.add(p); }
        Collections.sort(groei, (x, y) -> Long.compare(res.optJSONObject(y).optLong("o"), res.optJSONObject(x).optLong("o")));
        if (!groei.isEmpty()) {
            b.append("; erbij:");
            for (int i = 0; i < groei.size() && i < 4; i++) b.append(i == 0 ? " " : ", ").append(groei.get(i)).append(' ').append(kb(res.optJSONObject(groei.get(i)).optLong("o")));
        }
        JSONObject rot = res.optJSONObject("rotated");
        if (rot != null) b.append("; ").append(rot.optInt("count")).append(" oude weg");
        return b.toString();
    }

    private static String kb(long bytes) { return bytes >= 10L * 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : ((bytes + 1023) / 1024) + " kB"; }

    /** Na een gelukte backup (onder het slot): ongebruikte grote bestanden opruimen, een stuk controleren, oude archieven tellen. */
    static void grote(Context c, JSONObject res) {
        Vault.Key k = null;
        try {
            k = Secure.key(c);
            JSONObject g = res.optJSONObject("blok");
            if (rotateOn(c)) {
                JSONObject r = BlokOpslag.opruimen(c, k);
                if (g != null && (r.optInt("count") > 0 || r.has("later"))) g.put("opgeruimd", r);
            }
            JSONObject ch = BlokOpslag.controleren(c, k, BlokOpslag.CONTROLE_BYTES);
            if (g != null && (ch.optInt("count") > 0 || ch.optInt("kapot") > 0)) g.put("controle", ch);
            JSONObject oud = BlokOpslag.oudeArchieven(c, true);
            prefs(c).edit().putString("oud", oud.put("t", System.currentTimeMillis()).toString()).apply();
        } catch (Throwable e) { App.log(c, "BACKUP", "grote bestanden: " + AppData.safeMsg(e, "mislukt")); }
        finally { if (k != null) java.util.Arrays.fill(k.key, (byte) 0); }
    }

    private static void put(JSONObject r, String part, boolean ok, int n, String msg) {
        try {
            JSONObject o = new JSONObject().put("ok", ok).put("count", Math.max(0, n)).put("t", System.currentTimeMillis());
            if (msg != null) o.put("msg", msg);
            r.put(part, o);
        } catch (Exception ignored) { }
    }

    /** Eén onderdeel; -1 = niets te bewaren (geen fout). */
    static int runPart(Context c, String part) throws Exception {
        switch (part) {
            case "sms":
                if (!has(c, Manifest.permission.READ_SMS)) throw new Exception("Geen toestemming voor sms");
                if (Sms.countTotal(c) == 0) return -1; // geen sms'jes: geen fout
                return Sms.export(c, (d, t) -> { });
            case "calls":
                if (!has(c, Manifest.permission.READ_CALL_LOG)) throw new Exception("Geen toestemming voor de oproepgeschiedenis");
                return Calls.export(c, "", (d, t) -> { });
            case "notifications":
                return NotificationHistory.export(c, true); // loopt er al een handmatige export: even wachten
            case "contacts":
                if (!has(c, Manifest.permission.READ_CONTACTS)) throw new Exception("Geen toestemming voor contacten");
                return Contacts.export(c, 0);
            case "notes":
                return Notes.export(c);
            case "transcripts":
                int nt;
                try { nt = Transcribe.export(c, ""); }
                catch (Exception e) { if (e.getMessage() != null && e.getMessage().startsWith("Nog geen")) return -1; throw e; }
                SettingsBackup.exportTranscripts(c); // volledig, om terug te kunnen zetten
                return nt;
            case "settings":
                return SettingsBackup.export(c);
            case "routes":
                return SettingsBackup.exportRoutes(c);
            case "tooldata":
                return ToolData.export(c); // alles van de tools zonder eigen onderdeel (zonder geheimen)
            case "kluis":
                return Kluis.export(c); // alleen in een versleutelde backup
            case "music":
                return exportMusic(c);
            case "launcher":
                return LauncherBackup.export(c);
            case "ai":
                return AiSleepBackup.exportAi(c); // geheugen en gesprekken; nooit modellen of de zoekindex
            case "sleep":
                return AiSleepBackup.exportSleep(c, sleepAudio(c));
            case "checkin":
                return CheckinStore.export(c); // vragen en antwoorden
            case "app":
                return AppBackup.export(c); // alles van de app, alleen in een versleutelde backup (zonder modellen)
            default:
                return -1;
        }
    }

    /** Herkende nummers als JSON (terug te lezen) en als leesbare lijst. */
    static int exportMusic(Context c) throws Exception {
        JSONArray h = Music.history(c);
        if (h.length() == 0) return -1;
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), WaBackup.destUri(c));
        WaBackup.DestDir dir = dest.dir("Muziek", true);
        try (Sms.Out w = Sms.open(dest, dir, "herkende-nummers.json", "application/json")) { w.write(h.toString()); w.done(); }
        SimpleDateFormat df = Regio.df(c, "d MMM yyyy HH:mm");
        try (Sms.Out w = Sms.open(dest, dir, "herkende-nummers.txt", "text/plain")) {
            w.write("Herkende nummers - Tools\r\n\r\n");
            for (int i = 0; i < h.length(); i++) {
                JSONObject r = h.getJSONObject(i);
                w.write(df.format(new Date(r.optLong("t"))) + "  " + r.optString("artist") + " - " + r.optString("title")
                        + (r.optString("album").isEmpty() ? "" : "  (" + r.optString("album") + ")") + "\r\n");
            }
            w.done(); // pas nu vervangt het nieuwe bestand het oude
        }
        return h.length();
    }

    static String stateJson(Context c, boolean full) {
        try {
            JSONObject o = new JSONObject().put("busy", busy).put("current", current);
            if (!full) {
                o.put("encrypted", Secure.on(c));
                String last = prefs(c).getString("last", null);
                if (last != null) o.put("last", new JSONObject(last));
                return o.toString();
            }
            o
                    .put("auto", auto(c)).put("charging", prefs(c).getBoolean("charging", true))
                    .put("dest", WaBackup.destUri(c) != null).put("destName", WaBackup.destName(c))
                    .put("next", prefs(c).getLong("next", 0)).put("poging", pogingNu(c))
                    .put("rotate", rotateOn(c)).put("keep", keep(c)).put("encrypted", Secure.on(c)).put("encBroken", Secure.broken(c))
                    .put("blokken", blokken(c))
                    .put("encSince", Secure.prefs(c).getLong("since", 0));
            String space = Secure.prefs(c).getString("space", null);
            if (space != null) o.put("space", new JSONObject(space));
            String oud = prefs(c).getString("oud", null);
            if (oud != null) o.put("oud", new JSONObject(oud));
            JSONObject parts = new JSONObject();
            for (String p : PARTS) parts.put(p, effectivePart(c, p));
            JSONObject extra = AiSleepBackup.sizes(c);
            CheckinStore.sizes(c, extra);
            o.put("parts", parts).put("sleepAudio", sleepAudio(c)).put("extra", extra);
            try { o.put("herstel", Herstel.ready(c)); } catch (Exception ignored) { }
            String last = prefs(c).getString("last", null);
            if (last != null) o.put("last", new JSONObject(last));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
