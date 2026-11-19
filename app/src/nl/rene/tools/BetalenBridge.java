package nl.rene.tools;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Brug voor Betalen (Android.betalen…): betalingen bewaren, een rekening lezen (foto, PDF of afbeelding; de tekst wordt op
 * de telefoon herkend en het bestand wordt niet bewaard), een QR-code duiden en een betaling klaarzetten bij de bank
 * (BetaalLinkActivity). Tools betaalt nooit zelf. Alles wat van een rekening komt is een voorstel en gaat als gegevens
 * naar de interface (daar altijd esc() of textContent).
 */
public class BetalenBridge extends FeatureBridge {

    static final int REQ_CAM = 79, REQ_BESTAND = 80;
    static final int MAX_FOTO = 16 * 1024 * 1024, MAX_BESTAND = 40 * 1024 * 1024, BLZ = 2;
    static final long MAX_LEZEN_MS = 90_000L;
    private static final AtomicBoolean BEZIG = new AtomicBoolean();

    BetalenBridge(MainActivity act) { super(act); }

    private boolean mag() { return PlayPolicy.allows(ctx, "betalen"); }

    private String fout(Throwable e) {
        String m = e instanceof Betalen.Fout && e.getMessage() != null ? e.getMessage() : Taal.T("Opslaan lukte niet.");
        try { return new JSONObject().put("ok", false).put("fout", Taal.tx(ctx, m)).toString(); }
        catch (Exception x) { return "{\"ok\":false}"; }
    }

    /** Alles voor het scherm: betalingen (met compleet en nieuw), instellingen en de banken waar klaarzetten kan. */
    @JavascriptInterface public String betalenStaat() {
        try {
            if (!mag()) return "{}";
            JSONObject bestand = Betalen.lees(ctx);
            JSONArray lijst = Betalen.lijst(bestand), uit = new JSONArray();
            for (int i = 0; i < lijst.length(); i++) {
                JSONObject b = lijst.optJSONObject(i);
                if (b == null) continue;
                // Het domein van een betaallink altijd uit de link zelf (het bestand kan uit een backup komen)
                String link = BetalenHerken.link(b.optString("link")), host = link == null ? "" : Facturen.host(link);
                uit.put(new JSONObject(b.toString()).put("link", link == null ? "" : link).put("linkHost", host).put("linkBekend", BetalenHerken.hostBekend(host, BetaalLink.hosts(ctx)))
                        .put("compleet", Betalen.compleet(b)).put("nieuw", Betalen.nieuweOntvanger(lijst, b))
                        .put("controle", BetalenControle.controle(b, lijst, BetalenControle.lijst(ctx), b.optString("genoemd"))));
            }
            JSONArray banken = new JSONArray(), l = BankLink.banken(ctx);
            for (int i = 0; i < l.length(); i++) {
                JSONObject b = l.getJSONObject(i);
                JSONObject bt = b.optJSONObject("betalen");
                banken.put(new JSONObject().put("id", b.optString("id")).put("naam", b.optString("naam")).put("kan", BetaalLink.kan(b))
                        .put("appNaam", b.optString("appNaam")).put("uitleg", bt == null ? "" : bt.optString("uitleg")));
            }
            return new JSONObject().put("betalingen", uit).put("inst", Betalen.inst(bestand)).put("banken", banken)
                    .put("onleesbaar", bestand == null).put("lopend", BetaalLinkActivity.LOPEND).put("bezig", BEZIG.get()).toString();
        } catch (Throwable t) {
            App.log(ctx, "BETALEN", "staat: " + t.getClass().getSimpleName());
            return "{}";
        }
    }

    /** Een betaling toevoegen of wijzigen: {ok, id} of {ok:false, fout}. */
    @JavascriptInterface public String betalenBewaar(String json) {
        try {
            if (!mag()) throw new Betalen.Fout(PlayPolicy.unavailable(ctx));
            JSONObject b = Betalen.bewaar(ctx, new JSONObject(json == null ? "{}" : json), BetaalLink.hosts(ctx));
            return new JSONObject().put("ok", true).put("id", b.optString("id")).toString();
        } catch (Throwable e) { return fout(e); }
    }

    /** status: open, klaargezet, betaald (Rene zegt het zelf) of betaald_vanzelf (de instelling "meteen op Betaald zetten"). */
    @JavascriptInterface public String betalenStatus(String id, String status) {
        try {
            if (!mag()) throw new Betalen.Fout(PlayPolicy.unavailable(ctx));
            boolean vanzelf = "betaald_vanzelf".equals(status);
            Betalen.status(ctx, id == null ? "" : id, vanzelf ? "betaald" : status == null ? "" : status, !vanzelf);
            return "{\"ok\":true}";
        } catch (Throwable e) { return fout(e); }
    }

    @JavascriptInterface public String betalenWis(String id) {
        try { if (!mag()) throw new Betalen.Fout(PlayPolicy.unavailable(ctx)); Betalen.wis(ctx, id == null ? "" : id); return "{\"ok\":true}"; }
        catch (Throwable e) { return fout(e); }
    }

    @JavascriptInterface public String betalenInst(String json) {
        try { if (!mag()) throw new Betalen.Fout(PlayPolicy.unavailable(ctx)); Betalen.bewaarInst(ctx, new JSONObject(json == null ? "{}" : json)); return "{\"ok\":true}"; }
        catch (Throwable e) { return fout(e); }
    }

    /** Wat betekent deze QR-code? Een betaallink, een SEPA-QR ({…, eigen:true} als het IBAN een eigen rekeningnummer is), of "null". */
    @JavascriptInterface public String betalenQr(String inhoud) {
        try {
            if (!mag()) return "null";
            JSONObject o = BetalenHerken.qr(inhoud, BetaalLink.hosts(ctx));
            if (o == null) return "null";
            if ("epc".equals(o.optString("soort")) && BetalenHerken.isEigen(o.optString("iban"), BetalenHerken.lijst(Betalen.inst(Betalen.lees(ctx)).optJSONArray("eigen")))) o.put("eigen", true);
            return o.toString();
        } catch (Throwable t) { return "null"; }
    }

    // ---------------------------------------------------------------- camera (in de interface, zoals de QR-scanner van VPN)

    @JavascriptInterface public void betalenCamera() {
        a.h.post(() -> {
            if (!mag()) { a.js("onBetalenCamera", "false"); return; }
            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { a.camAllowed = true; a.js("onBetalenCamera", "true"); return; }
            a.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        });
    }

    /** Klaar met de camera: de pagina mag hem niet meer gebruiken. */
    @JavascriptInterface public void betalenCameraDone() { a.camAllowed = false; }

    // ---------------------------------------------------------------- een rekening lezen

    /** Een foto uit de camera van de interface (data:image/jpeg;base64,…). Antwoord via onBetalenHerkend. */
    @JavascriptInterface public void betalenFoto(String dataUrl) {
        if (!mag() || dataUrl == null || dataUrl.length() > MAX_FOTO * 4 / 3 + 100 || !dataUrl.startsWith("data:image/jpeg;base64,")) { meld("{\"error\":" + JSONObject.quote(Taal.tx(ctx, Taal.T("Deze foto is niet te lezen"))) + "}"); return; }
        if (!BEZIG.compareAndSet(false, true)) { meld("{\"error\":" + JSONObject.quote(Taal.tx(ctx, Taal.T("Tools leest al een rekening"))) + "}"); return; }
        new Thread(() -> {
            String r;
            File f = null;
            try (Voortgang v = Voortgang.van(a, "betalen", 1)) {
                byte[] b = android.util.Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), android.util.Base64.DEFAULT);
                f = werkBestand(ctx);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
                r = lees(ctx, f, "foto", false, v).toString();
            } catch (Throwable t) { r = foutLezen(ctx, t); }
            finally { if (f != null) f.delete(); BEZIG.set(false); }
            meld(r);
        }, "betalen-foto").start();
    }

    /** Een PDF of afbeelding kiezen (of krijgen van Bron: kluis, Gemaakt, facturendossier). Antwoord via onBetalenHerkend. */
    @JavascriptInterface public void betalenKies() {
        a.h.post(() -> {
            if (!mag()) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*"});
            try { a.startActivityForResult(i, REQ_BESTAND); }
            catch (Exception e) { meld("{\"error\":" + JSONObject.quote(Taal.tx(ctx, Taal.T("Bestanden kiezen lukt niet op deze telefoon"))) + "}"); }
        });
    }

    /** Bestand gekozen (vanuit MainActivity.onActivityResult); u == null = geannuleerd. */
    static void opBestand(MainActivity a, Uri u) {
        if (u == null) { a.js("onBetalenHerkend", "{\"error\":\"\"}"); return; }
        final android.content.Context ctx = a.getApplicationContext();
        if (!BEZIG.compareAndSet(false, true)) { a.js("onBetalenHerkend", "{\"error\":" + JSONObject.quote(Taal.tx(ctx, Taal.T("Tools leest al een rekening"))) + "}"); return; }
        new Thread(() -> {
            String r;
            File f = null;
            try (Voortgang v = Voortgang.van(a, "betalen", 1)) {
                byte[] b;
                try (java.io.InputStream in = v.open(a, u)) { b = Facturen.lees(in, MAX_BESTAND); }
                f = werkBestand(ctx);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
                r = lees(ctx, f, "bestand", true, v).toString();
            } catch (Throwable t) { r = foutLezen(ctx, t); }
            finally { if (f != null) f.delete(); BEZIG.set(false); }
            a.js("onBetalenHerkend", r);
        }, "betalen-bestand").start();
    }

    private void meld(String json) { a.js("onBetalenHerkend", json); }

    private static String foutLezen(android.content.Context c, Throwable t) {
        App.log(c, "BETALEN", "fout: " + t.getClass().getSimpleName());
        return "{\"error\":" + JSONObject.quote(Taal.tx(c, Taal.T("Dit bestand is niet te lezen. Vul de betaling zelf in."))) + "}";
    }

    /** Werkmap in de cache: het bestand staat er alleen tijdens het lezen (bij het opstarten leeggemaakt). */
    static File werkMap(android.content.Context c) { File d = new File(c.getCacheDir(), "betalen-werk"); d.mkdirs(); return d; }
    static File werkBestand(android.content.Context c) { return new File(werkMap(c), java.util.UUID.randomUUID().toString() + ".tmp"); }
    static void ruimWerk(android.content.Context c) { try { File[] fs = werkMap(c).listFiles(); if (fs != null) for (File f : fs) f.delete(); } catch (Throwable ignored) { } }

    /**
     * Leest een rekening: de tekst uit de PDF of met de tekstherkenning op de telefoon (hooguit BLZ pagina's), en daaruit het
     * voorstel. metBeeld: ook de eerste pagina als JPEG mee, zodat de interface er een QR-code in kan zoeken.
     */
    static JSONObject lees(android.content.Context c, File f, String bron, boolean metBeeld, Voortgang v) throws Exception {
        boolean pdf = PdfCompare.isPdf(f);
        int blz = 1;
        if (pdf) try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(fd)) { blz = r.getPageCount(); }
        PdfCompare.Slot s = new PdfCompare.Slot(f, "rekening", Math.min(blz, BLZ), pdf, "", 0);
        final long tot = android.os.SystemClock.elapsedRealtime() + MAX_LEZEN_MS;
        v.tijd("herkennen", MAX_LEZEN_MS);
        String tekst = "";
        StringBuilder ruw = new StringBuilder();
        try { tekst = leesTekst(c, s, () -> android.os.SystemClock.elapsedRealtime() < tot, ruw); }
        catch (Throwable t) { App.log(c, "BETALEN", "lezen: " + t.getClass().getSimpleName()); }
        JSONObject bestand = Betalen.lees(c);
        List<String> eigen = BetalenHerken.lijst(Betalen.inst(bestand).optJSONArray("eigen"));
        JSONObject uit = BetalenHerken.herken(tekst, eigen).put("bron", bron).put("tekst", !tekst.trim().isEmpty());
        try { // de naam van de officiële lijst of van een eerdere betaling gaat voor de gelezen naam (tekstherkenning vergist zich)
            JSONObject lijst = BetalenControle.lijst(c), g = BetalenControle.genoemd(BetalenControle.kop(uit.optString("naam"), tekst), lijst);
            if (g != null) uit.put("genoemd", g.optString("naam"));
            JSONObject k = BetalenControle.controle(uit, Betalen.lijst(bestand), lijst, tekst);
            if (!k.optString("naam").isEmpty()) uit.put("naamGelezen", uit.optString("naam")).put("naam", k.optString("naam")).put("naamVan", k.has("bekend") ? "lijst" : "eerder");
        } catch (Throwable t) { App.log(c, "BETALEN", "controle: " + t.getClass().getSimpleName()); }
        if (metBeeld) {
            Bitmap b = null;
            try {
                b = PdfCompare.render(s, 0, 2000);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 85, o);
                uit.put("beeld", "data:image/jpeg;base64," + android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP));
            } catch (Throwable t) { /* dan zonder QR-code */ }
            finally { if (b != null) b.recycle(); }
        }
        App.log(c, "BETALEN", "gelezen");
        try { // om na te zoeken waarom een veld niet goed kwam; alleen in het geheugen (Diagnose)
            JSONObject kort = new JSONObject(uit.toString());
            kort.remove("beeld");
            Diagnose.leg(c, "betalen", "Rekening gelezen", "Bron: " + bron + (pdf ? " (pdf, " + blz + " blz)" : " (afbeelding)") + "\nHerkend: " + kort
                    + "\n\n--- Tekst ---\n" + tekst + "\n--- Woorden met hun plek (pagina regel x0 y0 x1 y1 woord) ---\n" + ruw);
        } catch (Throwable ignored) { }
        return uit;
    }

    /** Eén veld van een betaling naar het klembord (om in de app van de bank te plakken); alleen op Rene's tik. */
    @JavascriptInterface public void betalenKopieer(String tekst) {
        if (!mag() || tekst == null || tekst.isEmpty() || tekst.length() > 200) return;
        a.h.post(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                android.content.ClipData cd = android.content.ClipData.newPlainText("Betalen", tekst);
                if (android.os.Build.VERSION.SDK_INT >= 33) { android.os.PersistableBundle x = new android.os.PersistableBundle(); x.putBoolean("android.content.extra.IS_SENSITIVE", true); cd.getDescription().setExtras(x); }
                cm.setPrimaryClip(cd);
            } catch (Throwable ignored) { }
        });
    }

    /**
     * De tekst van de rekening. Uit de PDF zelf als die tekst heeft; anders (een scan of een foto) de tekstherkenning op de
     * telefoon, met de woorden weer in hun eigen kolom gezet (OcrIndeling): op een brief met vakken staan naam en waarde
     * anders door elkaar.
     */
    static String leesTekst(android.content.Context c, PdfCompare.Slot s, java.util.function.BooleanSupplier door) throws Exception { return leesTekst(c, s, door, null); }

    /** ruw (mag null): daar komen de woorden van de tekstherkenning met hun plek in, voor Diagnose. */
    static String leesTekst(android.content.Context c, PdfCompare.Slot s, java.util.function.BooleanSupplier door, StringBuilder ruw) throws Exception {
        StringBuilder uit = new StringBuilder();
        PdfDoc doc = s.pdf && PdfDoc.canText() ? new PdfDoc(s.file, s.name, null) : null;
        try {
            for (int i = 0; i < Math.min(s.pages, BLZ) && door.getAsBoolean(); i++) {
                String t = doc == null ? "" : doc.text(i);
                if (t == null || t.trim().length() < 40) {
                    Bitmap b = PdfCompare.render(s, i, 2400);
                    try {
                        List<OcrIndeling.Woord> ws = RijbewijsOcr.pageWoorden(c, b, door);
                        t = OcrIndeling.tekst(ws);
                        if (ruw != null) for (OcrIndeling.Woord w : ws) { if (ruw.length() > 60_000) break; ruw.append(i).append(' ').append(w.regel).append(' ').append(w.x0).append(' ').append(w.y0).append(' ').append(w.x1).append(' ').append(w.y1).append(' ').append(w.tekst).append('\n'); }
                        if (t.trim().isEmpty()) t = RijbewijsOcr.pageText(c, b, door);
                    } finally { b.recycle(); }
                }
                uit.append(t).append('\n');
            }
        } finally { if (doc != null) doc.close(); }
        return uit.toString();
    }

    // ---------------------------------------------------------------- klaarzetten en betalen

    /** De website van de bank openen en de overboeking laten invullen. Geeft "" of een melding. */
    @JavascriptInterface public String betalenKlaarzetten(String id) {
        try {
            if (!mag()) return PlayPolicy.unavailable(ctx);
            if (BetaalLinkActivity.LOPEND) return Taal.tx(ctx, Taal.T("Er staat al een betaling open bij de bank"));
            JSONObject bestand = Betalen.lees(ctx);
            JSONObject b = Betalen.vind(Betalen.lijst(bestand), id == null ? "" : id);
            if (!Betalen.compleet(b)) return Taal.tx(ctx, Taal.T("Vul eerst de naam, het IBAN en het bedrag in."));
            JSONObject bank = BankLink.bank(BankLink.banken(ctx), Betalen.inst(bestand).optString("bank"));
            if (!BetaalLink.kan(bank)) return Taal.tx(ctx, Taal.T("Voor deze bank kan dat (nog) niet automatisch. Kopieer de gegevens naar de app van je bank."));
            BetaalLinkActivity.uitkomst = "";
            BetaalLinkActivity.LOPEND = true;
            final String bankId = bank.optString("id"), betId = b.optString("id");
            a.h.post(() -> {
                try { a.startActivityForResult(new Intent(a, BetaalLinkActivity.class).putExtra("bank", bankId).putExtra("id", betId), 81); }
                catch (Throwable t) { BetaalLinkActivity.LOPEND = false; Toast.makeText(a, Taal.tx(a, Taal.T("Openen lukt niet")), Toast.LENGTH_SHORT).show(); }
            });
            return "";
        } catch (Throwable t) { return Taal.tx(ctx, Taal.T("Openen lukt niet")); }
    }

    /** Uitkomst van het laatste klaarzetten (eenmalig; daarna leeg): {id, stand, bank}. */
    @JavascriptInterface public String betalenUitkomst() { String s = BetaalLinkActivity.uitkomst; BetaalLinkActivity.uitkomst = ""; return s; }

    /** De betaallink van deze betaling (uit de QR-code, alleen https) openen in de browser of de app van de bank. Geeft "" of een melding. */
    @JavascriptInterface public String betalenOpenLink(String id) {
        try {
            if (!mag()) return PlayPolicy.unavailable(ctx);
            JSONObject b = Betalen.vind(Betalen.lijst(Betalen.lees(ctx)), id == null ? "" : id);
            final String link = b == null ? null : BetalenHerken.link(b.optString("link"));
            if (link == null) return Taal.tx(ctx, Taal.T("Deze betaling heeft geen betaallink"));
            a.h.post(() -> {
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { Toast.makeText(ctx, Taal.tx(ctx, Taal.T("Geen app gevonden om dit te openen")), Toast.LENGTH_SHORT).show(); }
            });
            return "";
        } catch (Throwable t) { return Taal.tx(ctx, Taal.T("Openen lukt niet")); }
    }
}
