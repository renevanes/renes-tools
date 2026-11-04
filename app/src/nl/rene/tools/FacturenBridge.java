package nl.rene.tools;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Brugfuncties voor Facturen ophalen. Zit tussen GebruikBridge en MainActivity.Bridge. */
public class FacturenBridge extends GebruikBridge {

    FacturenBridge(MainActivity act) { super(act); }

    /** Alles voor het scherm: leveranciers (zonder wachtwoorden), toestand, Eigen Cloud, sjablonen. */
    @JavascriptInterface public String facturenState() {
        try {
            JSONArray l = Facturen.leveranciers(ctx);
            JSONObject all = Facturen.toestand(ctx);
            JSONArray out = new JSONArray();
            if (l != null) for (int i = 0; i < l.length(); i++) {
                JSONObject o = new JSONObject(l.getJSONObject(i).toString());
                String id = o.optString("id");
                JSONObject t = all.optJSONObject(id);
                if (t == null) t = new JSONObject();
                // Per maand: hoeveel facturen er binnenkwamen (voor het overzicht)
                JSONObject maanden = new JSONObject(), vw = t.optJSONObject("verwerkt");
                java.text.SimpleDateFormat ym = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US);
                if (vw != null) for (java.util.Iterator<String> it = vw.keys(); it.hasNext(); ) {
                    JSONObject r = vw.optJSONObject(it.next());
                    if (r == null || r.optLong("t") == 0 || r.optBoolean("buiten")) continue; // buiten de periode: niet binnengekomen
                    String m = ym.format(new java.util.Date(r.optLong("t")));
                    maanden.put(m, maanden.optInt(m) + 1);
                }
                t.remove("verwerkt");
                long nu = System.currentTimeMillis();
                long volgende = Facturen.gepland(o, nu, true, java.util.TimeZone.getDefault()), vorige = Facturen.gepland(o, nu, false, java.util.TimeZone.getDefault());
                o.put("maanden", maanden).put("volgende", volgende == Long.MAX_VALUE ? 0 : volgende).put("vorige", vorige == Long.MIN_VALUE ? 0 : vorige);
                o.put("toestand", t).put("heeftWachtwoord", Facturen.heeftGeheim(ctx, "lev_" + id))
                        .put("aantal", Facturen.bestanden(ctx, id).length());
                out.put(o);
            }
            SharedPreferences p = Facturen.prefs(ctx);
            FacturenRun r = FacturenRun.LOPEND.get();
            return new JSONObject().put("leveranciers", out).put("onleesbaar", l == null)
                    .put("bezig", r == null ? "" : r.id).put("voortgang", r == null ? "" : r.voortgang).put("zichtbaar", r != null && r.zichtbaar)
                    .put("ec", new JSONObject().put("url", p.getString("ecUrl", "")).put("gebruiker", p.getString("ecGebruiker", ""))
                            .put("adres", p.getString("ecAdres", "")).put("heeftWachtwoord", Facturen.heeftGeheim(ctx, "ec")))
                    .put("map", WaBackup.destUri(ctx) != null)
                    .put("lokaal", lokaalNaam())
                    .put("drive", new JSONObject().put("gekoppeld", FacturenDrive.gekoppeld(ctx))
                            .put("rechtDrive", FacturenDrive.heeftRecht(ctx, "drive")).put("rechtGmail", FacturenDrive.heeftRecht(ctx, "gmail")).put("rechtGmailSend", FacturenDrive.heeftRecht(ctx, "gmailsend")).put("gebruiker", p.getString("gdGebruiker", ""))
                            .put("clientId", p.getString("gdClientId", "")).put("heeftGeheim", Facturen.heeftGeheim(ctx, "gd_secret"))
                            .put("viaToestel", FacturenGoogleToestel.actief(ctx)).put("accountWeg", FacturenGoogleToestel.actief(ctx) && !FacturenGoogleToestel.werkt(ctx)))
                    .put("smtp", new JSONObject().put("host", p.getString("smtpHost", "")).put("poort", p.getInt("smtpPoort", 587))
                            .put("beveiliging", p.getString("smtpBeveiliging", "starttls")).put("gebruiker", p.getString("smtpGebruiker", ""))
                            .put("van", p.getString("smtpVan", "")).put("naam", p.getString("smtpNaam", "")).put("heeftWachtwoord", Facturen.heeftGeheim(ctx, "smtp")))
                    .put("ontvangers", FacturenAanlever.lijst(ctx).length()).put("dossier", FacturenDossier.alle(ctx).length())
                    .put("sms", ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
                    .put("sjablonen", Facturen.sjablonen(ctx)).toString();
        } catch (Exception e) { return "{\"leveranciers\":[],\"sjablonen\":[],\"ec\":{},\"error\":\"Laden lukt niet\"}"; }
    }

    /** Leverancier bewaren; json met de instellingen en eventueel "wachtwoord". Geeft {id} of {error}. */
    @JavascriptInterface public String facturenBewaar(String json) {
        try {
            JSONObject in = new JSONObject(json == null ? "{}" : json);
            String pw = in.optString("wachtwoord", "");
            in.remove("wachtwoord");
            String id = Facturen.bewaar(ctx, in, pw);
            FacturenJob.schedule(ctx);
            return new JSONObject().put("id", id).toString();
        } catch (Exception e) { return err(e, "Bewaren lukt niet"); }
    }

    @JavascriptInterface public String facturenVerwijder(String id) {
        try {
            FacturenRun r = FacturenRun.LOPEND.get();
            if (r != null && r.id.equals(id)) return "Even wachten: deze leverancier wordt nu opgehaald";
            Facturen.verwijder(ctx, id);
            FacturenJob.schedule(ctx);
            return "";
        } catch (Exception e) { return e.getMessage() == null ? "Verwijderen lukt niet" : e.getMessage(); }
    }

    /** Nu ophalen. Meekijken = in beeld (FacturenActivity). Uitkomst via onFacturenKlaar({id, ok, msg, nieuw …}). */
    @JavascriptInterface public String facturenNu(String id, boolean meekijken) {
        if (FacturenRun.LOPEND.get() != null) return "Er worden al facturen opgehaald";
        JSONObject lev = Facturen.leverancier(ctx, id);
        if (lev == null) return "Leverancier niet gevonden";
        if (meekijken && !"mail".equals(lev.optString("soort"))) { // facturen uit de mail: niets om naar te kijken
            a.h.post(() -> {
                try { a.startActivity(new Intent(a, FacturenActivity.class).putExtra("id", id)); }
                catch (Exception e) { Toast.makeText(a, Taal.tx(a, "Openen lukt niet"), Toast.LENGTH_SHORT).show(); }
            });
            return "";
        }
        a.h.post(() -> {
            String e = FacturenRun.begin(ctx, id, null, r -> a.js("onFacturenKlaar", r.toString()));
            if (!e.isEmpty()) a.js("onFacturenKlaar", "{\"id\":" + JSONObject.quote(id) + ",\"ok\":false,\"msg\":" + JSONObject.quote(e) + "}");
        });
        return "";
    }

    /**
     * Alles van de gekozen periode (opnieuw) ophalen: de app begint weer bij het begin van de periode (mail: vanaf die datum;
     * website: ook de facturen die eerder buiten de periode vielen). Wat al binnen is, komt niet dubbel.
     */
    @JavascriptInterface public String facturenOpnieuw(String id) {
        try {
            if (Facturen.leverancier(ctx, id) == null) return "Leverancier niet gevonden";
            if (FacturenRun.LOPEND.get() != null) return "Er worden al facturen opgehaald; probeer het straks nog een keer";
            synchronized (Facturen.LOCK) {
                JSONObject t = Facturen.toestand(ctx, id), vw = t.optJSONObject("verwerkt");
                if (vw != null) for (java.util.Iterator<String> it = vw.keys(); it.hasNext(); ) { JSONObject r = vw.optJSONObject(it.next()); if (r != null && r.optBoolean("buiten")) it.remove(); }
                Facturen.werkBij(ctx, id, new JSONObject().put("lastOk", 0).put("mailSinds", 0).put("mailMeer", false).put("verwerkt", vw == null ? new JSONObject() : vw));
            }
            return "";
        } catch (Exception e) { return e.getMessage() == null ? "Lukt niet" : e.getMessage(); }
    }

    @JavascriptInterface public void facturenStop() { FacturenRun r = FacturenRun.LOPEND.get(); if (r != null) r.stoppen(); }

    /** Facturen in de app van één leverancier: [{naam, t, grootte}]. */
    @JavascriptInterface public String facturenLijst(String id) {
        try { return Facturen.bestanden(ctx, id).toString(); } catch (Exception e) { return "[]"; }
    }

    /** Een factuur openen in de eigen PDF-viewer (via een kopie in de viewer-map). */
    @JavascriptInterface public void facturenOpen(String id, String naam) {
        try {
            File f = Facturen.bestand(ctx, id, naam);
            File d = new File(PdfTools.dir(ctx, PdfViewActivity.DIR), "facturen");
            if (!d.isDirectory() && !d.mkdirs()) throw new Exception("map");
            File[] oud = d.listFiles();
            if (oud != null) for (File x : oud) x.delete();
            File kopie = new File(d, f.getName());
            try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream o = new java.io.FileOutputStream(kopie)) {
                byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) o.write(b, 0, n);
            }
            Intent v = new Intent(ctx, PdfViewActivity.class).setData(PdfProvider.uri(kopie)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.h.post(() -> { try { a.startActivity(v); } catch (Exception e) { Toast.makeText(a, Taal.tx(a, "Openen lukt niet"), Toast.LENGTH_SHORT).show(); } });
        } catch (Exception e) { a.h.post(() -> Toast.makeText(a, Taal.tx(a, "Factuur niet gevonden"), Toast.LENGTH_SHORT).show()); }
    }

    /** Eigen Cloud koppelen: {url, gebruiker, adres, wachtwoord (leeg = ongewijzigd)}. Geeft "" of een fout. */
    @JavascriptInterface public String facturenEc(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            String url = o.optString("url", "").trim().replaceAll("/+$", ""), user = o.optString("gebruiker", "").trim(), adres = o.optString("adres", "").trim();
            if (url.isEmpty() && user.isEmpty()) {
                Facturen.prefs(ctx).edit().remove("ecUrl").remove("ecGebruiker").remove("ecAdres").apply();
                Facturen.geheim(ctx).edit().remove("ec").apply();
                return "";
            }
            if (!url.matches("(?i)https://[^\\s/?#]+[^\\s]*")) return "Het adres van Eigen Cloud moet beginnen met https://";
            if (user.isEmpty() || user.length() > 320) return "Vul je gebruikersnaam of mailadres in";
            if (adres.length() > 320 || (!adres.isEmpty() && !adres.matches("[^\\s@]+@[^\\s@]+"))) return "Het mailadres voor de facturen klopt niet";
            Facturen.prefs(ctx).edit().putString("ecUrl", url).putString("ecGebruiker", user).putString("ecAdres", adres).apply();
            String pw = o.optString("wachtwoord", "");
            if (!pw.isEmpty()) Facturen.zetGeheim(ctx, "ec", pw);
            FacturenJob.schedule(ctx);
            return "";
        } catch (Exception e) { return e.getMessage() == null ? "Bewaren lukt niet" : e.getMessage(); }
    }

    /** Koppeling testen; antwoord via onFacturenEcTest({ok} of {error}). */
    @JavascriptInterface public void facturenEcTest() {
        new Thread(() -> {
            String r;
            try { Facturen.testEigenCloud(ctx); r = "{\"ok\":true}"; }
            catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(Facturen.kort(e)) + "}"; }
            a.js("onFacturenEcTest", r);
        }, "facturen-ec-test").start();
    }

    /** De gekozen map op de telefoon, leesbaar ("Documents/Facturen"), of "" (geen) of "!" (geen toegang meer). */
    private String lokaalNaam() {
        android.net.Uri u = Facturen.lokaleMap(ctx);
        if (u == null) return "";
        if (!WaBackup.hasGrant(ctx.getContentResolver(), u)) return "!";
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(u);
            int i = id.indexOf(':');
            String rel = i < 0 ? id : id.substring(i + 1), vol = i < 0 ? "" : id.substring(0, i);
            return ("primary".equals(vol) ? "" : vol + ": ") + (rel.isEmpty() ? "hoofdmap" : rel);
        } catch (Exception e) { return "gekozen map"; }
    }

    /** Map op de telefoon kiezen; antwoord via onFacturenMap("" of fout of "geen"). */
    @JavascriptInterface public void facturenKiesMap() { a.h.post(a::pickFacturenFolder); }

    @JavascriptInterface public void facturenMapLos() {
        android.net.Uri u = Facturen.lokaleMap(ctx);
        Facturen.prefs(ctx).edit().remove("lokaal").apply();
        Facturen.laatMapLos(ctx, u);
    }

    /** Google Drive koppelen: opent de inlogpagina van Google in de browser. Antwoord via onFacturenDrive({error|naam}). */
    /** rechten: "drive", "gmail" of "drive,gmail". */
    @JavascriptInterface public String facturenDriveKoppel(String clientId, String geheim, String rechten) {
        try {
            if (rechten == null || (!rechten.contains("drive") && !rechten.contains("gmail"))) return "Kies waarvoor Tools Google mag gebruiken (Drive en/of Gmail)";
            String url = FacturenDrive.start(ctx, clientId, geheim, rechten, (err, naam) -> {
                try { a.js("onFacturenDrive", (err != null ? new JSONObject().put("error", err) : new JSONObject().put("naam", naam)).toString()); } catch (Exception ignored) { }
            });
            a.h.post(() -> { try { a.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (Exception e) { a.js("onFacturenDrive", "{\"error\":\"Geen browser gevonden\"}"); } });
            return "";
        } catch (Exception e) { return e.getMessage() == null ? "Koppelen lukt niet" : e.getMessage(); }
    }

    /** Google koppelen met het Google-account op deze telefoon (geen browser). Antwoord via onFacturenDrive({error|naam}). */
    @JavascriptInterface public String facturenGoogleToestel(String rechten) {
        if (rechten == null || (!rechten.contains("drive") && !rechten.contains("gmail"))) return "Kies waarvoor Tools Google mag gebruiken (Drive en/of Gmail)";
        a.h.post(() -> a.kiesGoogleAccount(rechten));
        return "";
    }

    /** Pakketnaam en SHA-1 van deze app, voor de Android-client in het Google-project: {pakket, sha1}. */
    @JavascriptInterface public String facturenAppSleutel() {
        try { return new JSONObject().put("pakket", ctx.getPackageName()).put("sha1", FacturenGoogleToestel.sha1(ctx)).toString(); }
        catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface public void facturenDriveLos() { FacturenDrive.ontkoppel(ctx); }

    // ================= dossier =================

    /** Het dossier: {items: [stuk], ontvangers: [{id, naam}]}, nieuwste eerst. */
    @JavascriptInterface public String facturenDossier() {
        try {
            JSONArray o = new JSONArray(), l = FacturenAanlever.lijst(ctx);
            for (int i = 0; i < l.length(); i++) o.put(new JSONObject().put("id", l.getJSONObject(i).optString("id")).put("naam", l.getJSONObject(i).optString("naam")));
            JSONArray items = FacturenDossier.lijst(ctx);
            int teLezen = 0;
            for (int i = 0; PdfDoc.canText() && i < items.length(); i++) if (FacturenDossier.opnieuwLezen(items.getJSONObject(i))) teLezen++;
            return new JSONObject().put("items", items).put("ontvangers", o).put("pdfTekst", PdfDoc.canText()).put("teLezen", teLezen).put("bezigMetLezen", HERKEN.get()).toString();
        } catch (Exception e) { return "{\"items\":[],\"ontvangers\":[]}"; }
    }

    /** Ontbrekende gegevens opnieuw uit de PDF's lezen (op de achtergrond); antwoord via onFacturenHerkend({aantal} of {error}). */
    private static final java.util.concurrent.atomic.AtomicBoolean HERKEN = new java.util.concurrent.atomic.AtomicBoolean(false);

    @JavascriptInterface public void facturenDossierHerken() {
        if (!HERKEN.compareAndSet(false, true)) return; // loopt al: die meldt zich straks
        new Thread(() -> {
            String r;
            try { r = new JSONObject().put("aantal", PdfDoc.canText() ? FacturenDossier.herkenAlle(ctx) : 0).put("pdfTekst", PdfDoc.canText()).toString(); }
            catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(Facturen.kort(e)) + "}"; }
            HERKEN.set(false);
            try { a.js("onFacturenHerkend", r); } catch (Throwable ignored) { }
        }, "facturen-herken").start();
    }

    /** De tekst uit de PDF van een factuur (om de herkenning te controleren): {tekst} of {error}. Blijft op de telefoon. */
    @JavascriptInterface public String facturenDossierTekst(String sha) {
        try {
            if (!PdfDoc.canText()) return "{\"error\":\"Tekst uit een PDF lezen kan pas vanaf Android 15\"}";
            JSONObject s = FacturenDossier.alle(ctx).optJSONObject(sha == null ? "" : sha);
            java.io.File f = s == null ? null : FacturenAanlever.bestand(ctx, s);
            if (f == null) return "{\"error\":\"De factuur staat niet (meer) in de app\"}";
            return new JSONObject().put("tekst", FacturenDossier.tekst(f)).toString();
        } catch (Throwable e) { return "{\"error\":\"Lezen lukt niet\"}"; }
    }

    /** Factuurgegevens aanpassen: {nummer, datum, bedrag, btw, categorie, notitie}. Geeft "" of een fout. */
    @JavascriptInterface public String facturenDossierMeta(String sha, String json) {
        try { FacturenDossier.meta(ctx, sha, new JSONObject(json == null ? "{}" : json)); return ""; }
        catch (Exception e) { return e.getMessage() == null ? "Bewaren lukt niet" : e.getMessage(); }
    }

    /** Opnieuw aanleveren aan deze ontvanger (de volgende keer, of met Nu versturen). */
    @JavascriptInterface public String facturenDossierOpnieuw(String sha, String ontvanger) {
        try { FacturenDossier.vergeet(ctx, sha, ontvanger); a.h.post(() -> FacturenJob.schedule(ctx)); return ""; }
        catch (Exception e) { return e.getMessage() == null ? "Lukt niet" : e.getMessage(); }
    }

    /** Het dossier als CSV in de map op de telefoon of de backup-map; antwoord via onFacturenCsv({waar} of {error}). */
    @JavascriptInterface public void facturenDossierCsv() {
        new Thread(() -> {
            String r;
            try { r = new JSONObject().put("waar", FacturenDossier.exporteer(ctx)).toString(); }
            catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(Facturen.kort(e)) + "}"; }
            a.js("onFacturenCsv", r);
        }, "facturen-csv").start();
    }

    // ================= aanleveren =================

    /** Ontvangers (accountant, boekhouder) met wat er open staat, de kanalen en manieren van versturen, en het logboek. */
    @JavascriptInterface public String facturenOntvangers() {
        try {
            JSONArray kan = new JSONArray(), via = new JSONArray();
            for (java.util.Map.Entry<String, FacturenAanlever.Kanaal> k : FacturenAanlever.KANALEN.entrySet()) kan.put(new JSONObject().put("id", k.getKey()).put("naam", k.getValue().naam()));
            for (java.util.Map.Entry<String, FacturenEmail.Verzender> v : FacturenEmail.VERZENDERS.entrySet())
                via.put(new JSONObject().put("id", v.getKey()).put("naam", v.getValue().naam()).put("mist", v.getValue().klaar(ctx)).put("van", v.getValue().van(ctx)));
            return new JSONObject().put("lijst", FacturenAanlever.overzicht(ctx)).put("kanalen", kan).put("via", via)
                    .put("log", FacturenAanlever.data(ctx).optJSONArray("log")).put("bezig", FacturenAanlever.BEZIG.get())
                    .put("voortgang", FacturenAanlever.voortgang).toString();
        } catch (Exception e) { return "{\"lijst\":[],\"kanalen\":[],\"via\":[],\"log\":[],\"error\":\"Laden lukt niet\"}"; }
    }

    /** Ontvanger bewaren. Geeft {id} of {error}. */
    @JavascriptInterface public String facturenOntvangerBewaar(String json) {
        try {
            String id = FacturenAanlever.bewaar(ctx, new JSONObject(json == null ? "{}" : json));
            a.h.post(() -> FacturenJob.schedule(ctx));
            return new JSONObject().put("id", id).toString();
        } catch (Exception e) { return err(e, "Bewaren lukt niet"); }
    }

    @JavascriptInterface public String facturenOntvangerWeg(String id) {
        try { FacturenAanlever.verwijder(ctx, id); a.h.post(() -> FacturenJob.schedule(ctx)); return ""; }
        catch (Exception e) { return e.getMessage() == null ? "Verwijderen lukt niet" : e.getMessage(); }
    }

    /** Nu aanleveren (of een proef naar jezelf). Antwoord via onFacturenAanlever({id, ok, msg, aantal}). */
    @JavascriptInterface public String facturenAanleveren(String id, boolean proef) {
        if (FacturenAanlever.BEZIG.get()) return "Er wordt al aangeleverd; even wachten";
        new Thread(() -> {
            try {
                JSONObject r = FacturenAanlever.stuurNu(ctx, id, proef, false);
                a.js("onFacturenAanlever", r.toString());
                a.h.post(() -> { try { FacturenJob.schedule(ctx); } catch (Throwable ignored) { } });
            } catch (Throwable ignored) { }
        }, "facturen-aanleveren").start();
        return "";
    }

    /** Eigen mailserver (SMTP): {host, poort, beveiliging, gebruiker, van, naam, wachtwoord (leeg = ongewijzigd)}. Leeg = loskoppelen. */
    @JavascriptInterface public String facturenSmtp(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            String host = o.optString("host", "").trim(), van = o.optString("van", "").trim(), user = o.optString("gebruiker", "").trim(), naam = o.optString("naam", "").trim();
            if (host.isEmpty() && van.isEmpty()) {
                Facturen.prefs(ctx).edit().remove("smtpHost").remove("smtpPoort").remove("smtpBeveiliging").remove("smtpGebruiker").remove("smtpVan").remove("smtpNaam").apply();
                Facturen.geheim(ctx).edit().remove("smtp").apply();
                return "";
            }
            int poort = o.optInt("poort", 587);
            String bev = o.optString("beveiliging", "starttls");
            FacturenSmtp.controleer(host, poort, bev);
            if (!van.matches(FacturenEmail.ADRES) || van.length() > 254) return "Vul je eigen mailadres in (de afzender)";
            if (user.length() > 320 || naam.length() > 80 || naam.matches("(?s).*[\\r\\n\"<>].*")) return "Gebruikersnaam of naam klopt niet";
            Facturen.prefs(ctx).edit().putString("smtpHost", host).putInt("smtpPoort", poort).putString("smtpBeveiliging", bev)
                    .putString("smtpGebruiker", user).putString("smtpVan", van.toLowerCase(java.util.Locale.ROOT)).putString("smtpNaam", naam).apply();
            String pw = o.optString("wachtwoord", "");
            if (!pw.isEmpty()) Facturen.zetGeheim(ctx, "smtp", pw);
            return "";
        } catch (Exception e) { return e.getMessage() == null ? "Bewaren lukt niet" : e.getMessage(); }
    }

    /** Mailserver testen (verbinden en inloggen, niets versturen); antwoord via onFacturenSmtpTest({ok} of {error}). */
    @JavascriptInterface public void facturenSmtpTest() {
        new Thread(() -> {
            String r;
            try {
                SharedPreferences p = Facturen.prefs(ctx);
                if (p.getString("smtpHost", "").isEmpty()) throw new Exception("Stel eerst de mailserver in");
                FacturenSmtp.stuur(p.getString("smtpHost", ""), p.getInt("smtpPoort", 587), p.getString("smtpBeveiliging", "starttls"),
                        p.getString("smtpGebruiker", ""), Facturen.geheim(ctx, "smtp"), p.getString("smtpVan", ""), new java.util.ArrayList<String>(), null);
                r = "{\"ok\":true}";
            } catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(Facturen.kort(e)) + "}"; }
            a.js("onFacturenSmtpTest", r);
        }, "facturen-smtp-test").start();
    }

    private static String err(Exception e, String fb) {
        try { return new JSONObject().put("error", e.getMessage() == null ? fb : e.getMessage()).toString(); } catch (Exception x) { return "{\"error\":\"" + fb + "\"}"; }
    }
}
