package nl.rene.tools;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;

/**
 * Brugfuncties van de nieuwere onderdelen (Mijn auto, Kluis, spraaknotities, herinneringen, meldingen,
 * radiofragment). MainActivity.Bridge erft deze, zodat de interface ze gewoon als Android.xxx ziet.
 * Zo blijft MainActivity overzichtelijker; nieuwe onderdelen horen hier of in een eigen klasse zoals deze.
 */
public class FeatureBridge {
    final MainActivity a;
    final Context ctx;

    FeatureBridge(MainActivity act) { a = act; ctx = act.getApplicationContext(); }

    // ----- Thema (Weergave) -----

    /** De interface geeft de gekozen stand door (zie Thema): voor de achtergrond bij het opstarten en de Telefoon-skin. */
    @JavascriptInterface public void themeSet(String json) {
        if (Thema.save(ctx, json)) a.h.post(a::themeChanged);
    }

    /** Donker of licht volgens het thema, nu (voor de stand vóór de eerste weergave als de onthouden stand verlopen is). */
    @JavascriptInterface public boolean themeDark() { return Thema.dark(ctx); }

    /** Laatst bekende plek, grof ("51.92,4.48"), of "" zonder toestemming; voor zonsopkomst en -ondergang. */
    @JavascriptInterface public String themeLocation() { return Thema.lastLocation(ctx); }

    // ----- Regio en taal -----

    /** De interface geeft de keuze van Regio en taal door (zie Regio): meldingen, widgets en de skin schrijven dan hetzelfde. */
    @JavascriptInterface public void regioSet(String json) {
        Regio.Cfg before = Regio.cfg(ctx);
        String was = Regio.json(ctx);
        if (!Regio.save(ctx, json) || Regio.json(ctx).equals(was)) return;
        regioChanged(before);
    }

    /** Kent deze telefoon de tijdzone precies zo (Regio.tzOk)? De interface biedt alleen zones aan die Java ook kent. */
    @JavascriptInterface public boolean regioTzOk(String id) { return Regio.tzOk(id); }

    /** Thuis en werk ({home, work}): alleen hier bewaard (regio.xml, niet in de Android-backup). */
    @JavascriptInterface public String regioSpots() { return Regio.spots(ctx); }

    @JavascriptInterface public boolean regioSpotsSet(String json) {
        Regio.Cfg before = Regio.cfg(ctx);
        String was = Regio.spots(ctx);
        if (!Regio.saveSpots(ctx, json)) return false;
        if (!Regio.spots(ctx).equals(was)) regioChanged(before);
        return true;
    }

    /** Na een andere keuze: widgets opnieuw, en bij een andere plek het weer opnieuw ophalen. */
    private void regioChanged(Regio.Cfg before) {
        Regio.Cfg after = Regio.cfg(ctx);
        boolean taal = !before.lang.equals(after.lang);
        new Thread(() -> {
            try {
                // Andere taal: namen van meldingskanalen, snelkoppelingen en alle widgets in de nieuwe taal
                if (taal) { Taal.channels(ctx); RadioWidget.refresh(ctx); NoteWidget.refresh(ctx); MusicWidget.refresh(ctx); }
                if (!java.util.Arrays.equals(Regio.place(before), Regio.place(after)) || !java.util.Arrays.equals(before.home(), after.home()))
                    Launcher.prefs(ctx).edit().remove("weather").apply();
                // Andere tijdzone: alle wekkers, herinneringen en planningen opnieuw (de tijden zijn wandklok in de app-zone)
                java.util.TimeZone from = Regio.zone(before), to = Regio.zone(after);
                if (!from.getID().equals(to.getID())) {
                    RedialPlan.rezone(ctx);
                    Reminders.rezone(ctx, from, to);
                    AlarmReceiver.rearmTimes(ctx);
                    App.log(ctx, "REGIO", "tijdzone: wekkers en planningen opnieuw gezet");
                }
                for (Class<?> w : new Class<?>[]{HomeWidgets.Agenda.class, HomeWidgets.Weather.class, HomeWidgets.Overview.class, HomeWidgets.Park.class}) HomeWidgets.refresh(ctx, w);
            } catch (Throwable t) { App.log(ctx, "REGIO", String.valueOf(t)); }
        }, "regio").start();
    }

    // ----- ID-kopie: eigen tijdelijke bron, nooit via de gewone PDF-bewerklaag -----
    @JavascriptInterface public void idCopyPick() {
        if (!a.idScreen) return;
        if (a.idPickStore != null) { a.js("onIdCopyPicked", "{\"cancelled\":true,\"session\":" + JSONObject.quote(a.idSession) + "}"); return; }
        final IdCopy store = a.idCopy; final String session = a.idSession;
        a.idPickStore = store; a.idPickSession = session;
        a.h.post(() -> {
            if (store != a.idCopy || !session.equals(a.idSession) || !a.idScreen) { if (a.idPickStore == store) a.idPickStore = null; return; }
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"}).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { a.startActivityForResult(i, MainActivity.REQ_ID_COPY); }
            catch (Throwable e) {
                if (a.idPickStore == store) a.idPickStore = null;
                a.js("onIdCopyPicked", idAnswer("{\"error\":" + JSONObject.quote(Taal.T("Bestandskiezer niet beschikbaar")) + "}", session, ""));
            }
        });
    }
    /** De kaartfoto's die Stamgegevens net klaarzette (masterIdCopySource) als pagina's; antwoord via onIdCopyPicked. */
    @JavascriptInterface public void idCopyTakeMaster() {
        if (!a.idScreen) { a.idMasterClear(); a.js("onIdCopyPicked", "{\"cancelled\":true,\"session\":" + JSONObject.quote(a.idSession) + "}"); return; }
        final IdCopy store = a.idCopy; final String session = a.idSession;
        final java.util.List<byte[]> images = a.idMasterPop();
        new Thread(() -> {
            String result;
            try {
                if (images == null) throw new Exception(Taal.T("De foto's uit Stamgegevens zijn niet meer beschikbaar; probeer opnieuw"));
                result = store.addImages(ctx, images).toString();
            } catch (Throwable e) { result = PdfTools.err(e); }
            try { result = new JSONObject(result).put("session", session).put("master", true).toString(); } catch (Exception ignored) { }
            if (store == a.idCopy && a.idScreen) a.js("onIdCopyPicked", result);
        }, "id-copy-master").start();
    }
    @JavascriptInterface public void idCopySecure(boolean on, String session) {
        final long generation;
        synchronized (a.idSecurityGeneration) {
            generation = a.idSecurityGeneration.incrementAndGet();
            a.idSession = on && session != null ? session : "";
            if (on) a.idScreen = true;
        }
        if (on) a.h.post(() -> { if (!a.isDestroyed()) { a.applySecure(); a.bankPrive(a.bankScreen || a.idScreen || a.masterScreen); } });
        else a.h.post(() -> a.finishIdScreen(generation));
    }
    @JavascriptInterface public void idCopyClear() {
        final IdCopy previous = a.idCopy; a.idCopy = new IdCopy();a.masterIdBound=false;
        new Thread(() -> { try {
            if (!previous.clear()) a.js("onIdCopyCleanup", "{\"error\":" + JSONObject.quote(Taal.T("Werkbestand wissen lukt niet. Sluit dit scherm en probeer opnieuw.")) + "}");
        } catch (Throwable ignored) { } }, "id-copy-clear").start();
    }
    private static String idAnswer(String json, String session, String revision) {
        try { return new JSONObject(json).put("session", session).put("revision", revision).toString(); }
        catch (Exception ignored) { return "{}"; }
    }
    @JavascriptInterface public void idCopyPreview(String revision, int page, int turn) {
        final IdCopy store = a.idCopy; final String session = a.idSession;
        if (!a.idScreen) return;
        new Thread(() -> {
            String result;
            try { result = store.preview(revision, page, turn).toString(); }
            catch (Throwable e) {
                try { result = new JSONObject(PdfTools.err(e)).put("page", page).put("turn", turn).toString(); }
                catch (Exception ignored) { result = "{}"; }
            }
            if (store == a.idCopy && a.idScreen) a.js("onIdCopyPreview", idAnswer(result, session, revision));
        }, "id-copy-preview").start();
    }
    @JavascriptInterface public void idCopySave(String revision, String plan) {
        final IdCopy store = a.idCopy; final String session = a.idSession;
        final boolean masterBound=a.masterIdBound;final String masterToken=a.masterSession.consumerToken();
        if (!a.idScreen) return;
        new Thread(() -> {
            String result;
            try { File f; if(masterBound){MasterExport.attempt(ctx,a.masterSession,masterToken,"idcopy");File staged=store.save(ctx,revision,plan,true);f=MasterExport.publish(ctx,a.masterSession,masterToken,"idcopy",staged,"ID-kopie afgeschermd");}else f=store.save(ctx,revision,plan); result = new JSONObject().put("revision", revision).put("name", f.getName()).toString(); }
            catch (Throwable e) { result = PdfTools.err(e); }
            if (store == a.idCopy && a.idScreen) a.js("onIdCopySaved", idAnswer(result, session, revision));
        }, "id-copy-save").start();
    }

    // ----- PDF -----

    private void pick(String type, String[] mimes, boolean multi, int req) {
        a.h.post(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);
            if (mimes != null) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
            if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { a.startActivityForResult(i, req); } catch (ActivityNotFoundException e) { Toast.makeText(a, Taal.tx(a, "Bestandskiezer niet beschikbaar"), Toast.LENGTH_SHORT).show(); }
        });
    }

    /** Uitkomst van een bestandskeuze voor het PDF-scherm (op een achtergrondthread). */
    static String pdfResult(Context c, int req, java.util.List<Uri> us, String saveName, Voortgang v) {
        try {
            if (req != MainActivity.REQ_PDF_IMG) v.stap("verwerken", 0, 0);
            if (req == MainActivity.REQ_PDF_IMG) {
                int n = 0; StringBuilder warn = new StringBuilder();
                for (int k = 0; k < us.size(); k++) { Uri u = us.get(k); v.bestand(k); v.stap("verwerken", 0, 0); try { PdfTools.addImage(c, u); n++; } catch (Exception e) { if (warn.length() < 200) warn.append(e.getMessage()).append(". "); } }
                return new JSONObject().put("kind", "images").put("count", n).put("warn", warn.toString().trim()).toString();
            }
            if (req == MainActivity.REQ_PDF_FILE) return new JSONObject(PdfTools.openPdf(c, us.get(0))).put("kind", "pdf").toString();
            if (req == MainActivity.REQ_PDF_DOC) return new JSONObject(PdfShare.take(c, us, null, null, null, null)).toString();
            // Opslaan als
            File f = PdfTools.output(c, saveName);
            try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream o = c.getContentResolver().openOutputStream(us.get(0), "w")) {
                if (o == null) throw new Exception("Opslaan lukt niet");
                byte[] b = new byte[1 << 16]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r);
            }
            return new JSONObject().put("kind", "saved").put("name", saveName).toString();
        } catch (Throwable e) { return PdfTools.err(e); }
    }

    @JavascriptInterface public void pdfPickImages() { pick("image/*", null, true, MainActivity.REQ_PDF_IMG); }
    @JavascriptInterface public void pdfPickPdf() { pick("application/pdf", null, false, MainActivity.REQ_PDF_FILE); }
    @JavascriptInterface public void pdfPickDoc() { pick("*/*", new String[]{"message/rfc822", "text/plain", "text/html", "application/octet-stream"}, false, MainActivity.REQ_PDF_DOC); }
    @JavascriptInterface public String pdfImages() { return PdfTools.imagesJson(ctx); }
    @JavascriptInterface public void pdfImageMove(int i, int to) { PdfTools.moveImage(i, to); }
    @JavascriptInterface public void pdfImageRemove(int i) { PdfTools.removeImage(i); }
    @JavascriptInterface public void pdfImagesClear() { PdfTools.clearImages(ctx); }
    @JavascriptInterface public void pdfMakeFromImages(boolean a4, String name) {
        new Thread(() -> {
            String r;
            try { File f = PdfTools.imagesToPdf(ctx, a4, name); r = new JSONObject().put("kind", "done").put("made", new org.json.JSONArray().put(f.getName())).toString(); }
            catch (Throwable e) { r = PdfTools.err(e); }
            a.js("onPdf", r);
        }, "pdf-images").start();
    }
    @JavascriptInterface public String pdfInfo() {
        try { return PdfTools.inFile(ctx).isFile() ? new JSONObject().put("name", PdfTools.inName).put("pages", PdfTools.inPages).put("lossless", PdfTools.inLossless).put("revision", PdfTools.inRevision).put("kluis", PdfTools.inKluis).toString() : "{}"; }
        catch (Exception e) { return "{}"; }
    }
    @JavascriptInterface public String pdfThumb(int i) { return PdfTools.pageThumb(ctx, i, 160); }
    @JavascriptInterface public void pdfEditPreview(String revision, int page) {
        new Thread(() -> {
            String result;
            try { result = PdfEditor.preview(ctx, revision, page).toString(); }
            catch (Throwable e) {
                try { result = new JSONObject(PdfTools.err(e)).put("revision", revision).put("page", page).toString(); }
                catch (Exception ignored) { result = "{}"; }
            }
            a.js("onPdfEditPreview", result);
        }, "pdf-edit-preview").start();
    }
    @JavascriptInterface public void pdfEditSave(String revision, String layers, String name) {
        new Thread(() -> {
            String result;
            try { File f = PdfEditor.save(ctx, revision, layers, name); result = new JSONObject().put("kind", "edited").put("revision", revision).put("made", new org.json.JSONArray().put(f.getName())).toString(); }
            catch (Throwable e) { result = PdfTools.err(e); }
            a.js("onPdf", result);
        }, "pdf-edit-save").start();
    }
    @JavascriptInterface public void pdfFormPreview(String id, int page, String request) {
        new Thread(() -> {
            JSONObject result;
            try { if(a.masterFormBound&&!a.masterSession.isOpen())throw new MasterError(Taal.T("Ontgrendel de stamgegevens opnieuw")); result = PdfFormulier.preview(ctx, id, page); }
            catch (Throwable e) { result = new JSONObject(); try { result.put("error", Taal.t(ctx, "Het voorbeeld kan niet worden geladen. Ga een stap terug en probeer opnieuw.")); } catch (Exception ignored) { } }
            try { result.put("id", id).put("page", page).put("request", request); } catch (Exception ignored) { }
            a.js("onPdfFormPreview", result.toString());
        }, "pdf-form-preview").start();
    }
    /** Concept van een formulier opslaan (ook met lege verplichte velden). Geeft {t} of {error}. */
    @JavascriptInterface public String pdfFormConceptSave(String id, String json) {
        try { return new JSONObject().put("t", FormConcept.save(ctx, id, json)).toString(); }
        catch (Throwable e) {
            App.log(ctx, "PDF", "concept opslaan mislukt");
            try { return new JSONObject().put("error", Taal.t(ctx, "Opslaan is niet gelukt. Probeer het nog eens.")).toString(); } catch (Exception x) { return "{}"; }
        }
    }
    /** Het opgeslagen concept ({…} of {} als er geen is, of {error}). */
    @JavascriptInterface public String pdfFormConceptLoad(String id) {
        try { JSONObject o = FormConcept.load(ctx, id); return o == null ? "{}" : o.toString(); }
        catch (Throwable e) {
            try { return new JSONObject().put("error", Taal.t(ctx, "Het opgeslagen concept kan niet worden gelezen.")).toString(); } catch (Exception x) { return "{}"; }
        }
    }
    @JavascriptInterface public void pdfFormConceptDelete(String id) { FormConcept.delete(ctx, id); }
    @JavascriptInterface public void pdfFormMake(String id, String layers, String name, String request) {
        final boolean masterBound=a.masterFormBound;final String masterToken=a.masterSession.consumerToken();
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try { File f;if(masterBound){MasterExport.attempt(ctx,a.masterSession,masterToken,"formulieren");File staged=PdfFormulier.make(ctx,id,layers,name,true);f=MasterExport.publish(ctx,a.masterSession,masterToken,"formulieren",staged,PdfTools.base(name));}else f=PdfFormulier.make(ctx,id,layers,name);result.put("name",f.getName()); }
            catch (Throwable e) { try { result.put("error", Taal.t(ctx, "De PDF kan niet worden gemaakt. Probeer opnieuw.")); } catch (Exception ignored) { } }
            try { result.put("id", id).put("request", request); } catch (Exception ignored) { }
            a.js("onPdfFormMade", result.toString());
        }, "pdf-form-make").start();
    }
    /** mode: each (elke pagina apart), pick (gekozen pagina's samen), parts (elke groep in "1-3, 4-6" apart). */
    @JavascriptInterface public void pdfSplit(String mode, String ranges) {
        new Thread(() -> {
            String r;
            try {
                int n = PdfTools.inPages;
                java.util.List<java.util.List<Integer>> groups = new java.util.ArrayList<>();
                if ("each".equals(mode)) for (int i = 0; i < n; i++) groups.add(java.util.Collections.singletonList(i));
                else if ("pick".equals(mode)) { java.util.List<Integer> g = PdfSplit.parseRange(ranges, n); if (g == null) throw new Exception("Kies geldige pagina's, bijv. 1-3, 5"); groups.add(g); }
                else {
                    for (String part : (ranges == null ? "" : ranges).split("[,;]+")) {
                        if (part.trim().isEmpty()) continue;
                        java.util.List<Integer> g = PdfSplit.parseRange(part.trim(), n);
                        if (g == null) throw new Exception("\"" + part.trim() + "\" is geen geldig bereik (bijv. 1-3, 4-6)");
                        groups.add(g);
                    }
                    if (groups.isEmpty()) throw new Exception("Vul in hoe je wilt splitsen, bijv. 1-3, 4-6");
                }
                if (groups.size() > 500) throw new Exception("Te veel delen");
                r = new JSONObject().put("kind", "done").put("made", PdfTools.split(ctx, groups)).put("lossless", PdfTools.inLossless).toString();
            } catch (Throwable e) { r = PdfTools.err(e); }
            a.js("onPdf", r);
        }, "pdf-split").start();
    }
    @JavascriptInterface public void pdfFromText(String title, String text) {
        new Thread(() -> {
            String r;
            try { File f = PdfTools.textToPdf(ctx, title == null ? "" : title.trim(), "", text == null ? "" : text); r = new JSONObject().put("kind", "done").put("made", new org.json.JSONArray().put(f.getName())).toString(); }
            catch (Throwable e) { r = PdfTools.err(e); }
            a.js("onPdf", r);
        }, "pdf-text").start();
    }
    @JavascriptInterface public String pdfOutputs() { return PdfTools.outputs(ctx); }
    @JavascriptInterface public String pdfPending() { String p = PdfShare.pending; PdfShare.pending = null; return p == null ? "" : p; }
    @JavascriptInterface public String pdfSignatureLoad() {
        try { return new JSONObject().put("signature",PdfSignature.load(ctx)).toString(); }
        catch (Exception e) { return errJson(e); }
    }
    @JavascriptInterface public String pdfSignatureSave(String json) {
        try { PdfSignature.save(ctx,json);return ""; }
        catch (Exception e) { return e.getMessage()==null?"Handtekening bewaren lukt niet":e.getMessage(); }
    }
    @JavascriptInterface public String pdfSignatureDelete() {
        try { PdfSignature.delete(ctx);return ""; }
        catch (Exception e) { return "Handtekening verwijderen lukt niet"; }
    }
    /**
     * Een foto of scan van je handtekening kiezen; het antwoord komt in onSignatureImage ({image} als verkleinde JPEG,
     * {cancelled} of {error}). masterToken: is Stamgegevens open, dan blijft die tijdens het kiezen open (zoals bij een kaartfoto).
     */
    @JavascriptInterface public void pdfSignaturePick(String masterToken) {
        a.h.post(() -> {
            if (masterToken != null && !masterToken.isEmpty() && a.masterForeground && a.masterSession.accepts(masterToken)) {
                a.masterPickReturned = false; a.masterPickKind = "sig"; a.masterPickUntil = android.os.SystemClock.elapsedRealtime() + MainActivity.MASTER_PICK_MS;
                a.h.postDelayed(a::masterPickExpired, MainActivity.MASTER_PICK_MS + 500);
            }
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try { a.startActivityForResult(i, MainActivity.REQ_SIG_IMG); }
            catch (Throwable e) {
                if ("sig".equals(a.masterPickKind)) { a.masterPickUntil = 0; a.masterPickKind = ""; }
                a.js("onSignatureImage", "{\"error\":" + JSONObject.quote(Taal.T("Bestandskiezer niet beschikbaar")) + "}");
            }
        });
    }
    /** Openen in de eigen viewer. */
    @JavascriptInterface public void pdfOpen(String name) {
        try {
            File f = PdfTools.output(ctx, name);
            Intent v = new Intent(ctx, PdfViewActivity.class).setData(PdfProvider.uri(f));
            a.h.post(() -> { try { a.startActivity(v); } catch (Exception e) { Toast.makeText(a, Taal.tx(a, "Openen lukt niet"), Toast.LENGTH_SHORT).show(); } });
        } catch (Exception ignored) { }
    }
    /** Een PDF van de telefoon kiezen om te bekijken. */
    @JavascriptInterface public void pdfViewPick() { pick("application/pdf", null, false, MainActivity.REQ_PDF_VIEW); }
    /** Welke app opent PDF's: {state: ours|none|other, app}. */
    @JavascriptInterface public String pdfDefaultState() {
        JSONObject o = PdfViewActivity.defaultState(ctx);
        o.remove("pkg");
        return o.toString();
    }
    /** Tools als standaard PDF-app instellen (Android laat de gebruiker dat zelf kiezen). */
    @JavascriptInterface public void pdfMakeDefault() {
        a.h.post(() -> {
            JSONObject s = PdfViewActivity.defaultState(a);
            if ("other".equals(s.optString("state"))) PdfViewActivity.defaultDialog(a);
            else if ("none".equals(s.optString("state"))) PdfViewActivity.askAndroid(a);
            else Toast.makeText(a, Taal.tx(a, "PDF's openen al in Tools"), Toast.LENGTH_SHORT).show();
        });
    }
    @JavascriptInterface public void pdfShare(String names) {
        try {
            org.json.JSONArray l = new org.json.JSONArray(names);
            java.util.ArrayList<Uri> us = new java.util.ArrayList<>();
            for (int i = 0; i < l.length(); i++) us.add(PdfProvider.uri(PdfTools.output(ctx, l.getString(i))));
            if (us.isEmpty()) return;
            Intent s = us.size() == 1 ? new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, us.get(0))
                    : new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, us);
            s.setType("application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.ClipData cd = android.content.ClipData.newRawUri("", us.get(0));
            for (int i = 1; i < us.size(); i++) cd.addItem(new android.content.ClipData.Item(us.get(i)));
            s.setClipData(cd);
            a.h.post(() -> { try { a.startActivity(Intent.createChooser(s, "PDF delen")); } catch (Exception e) { Toast.makeText(a, Taal.tx(a, "Delen lukt niet"), Toast.LENGTH_SHORT).show(); } });
        } catch (Exception ignored) { }
    }
    /** Opslaan als: de gebruiker kiest waar (bijv. Downloads). */
    @JavascriptInterface public void pdfSave(String name) {
        try { PdfTools.output(ctx, name); } catch (Exception e) { return; }
        a.pdfSaveName = name;
        ctx.getSharedPreferences("pdf", Context.MODE_PRIVATE).edit().putString("saveName", name).apply(); // na herstarten van de app nog bekend
        a.h.post(() -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE, name);
            try { a.startActivityForResult(i, MainActivity.REQ_PDF_SAVE); } catch (ActivityNotFoundException e) { Toast.makeText(a, Taal.tx(a, "Opslaan niet beschikbaar"), Toast.LENGTH_SHORT).show(); }
        });
    }
    /** Alles in de map PDF van de backup-map. */
    @JavascriptInterface public void pdfSaveAll(String names) {
        new Thread(() -> {
            String r;
            try {
                Uri tree = WaBackup.destUri(ctx);
                if (tree == null) throw new Exception("Kies eerst een backup-map (Instellingen), of gebruik Opslaan per bestand");
                org.json.JSONArray l = new org.json.JSONArray(names);
                WaBackup.Dest dest = new WaBackup.Dest(ctx.getContentResolver(), tree);
                WaBackup.DestDir dir = dest.dir("PDF", true);
                int n = 0;
                for (int i = 0; i < l.length(); i++) {
                    File f = PdfTools.output(ctx, l.getString(i));
                    String name = f.getName();
                    for (int k = 2; dir.kids.containsKey(name); k++) name = f.getName().replaceAll("\\.pdf$", "") + " (" + k + ").pdf";
                    Uri u = WaBackup.create(dest, dir.uri, "application/pdf", name);
                    if (u == null) throw new Exception("Bestand maken lukt niet");
                    try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream o = ctx.getContentResolver().openOutputStream(u, "w")) {
                        if (o == null) throw new Exception("Opslaan lukt niet");
                        byte[] b = new byte[1 << 16]; int rr; while ((rr = in.read(b)) > 0) o.write(b, 0, rr);
                    }
                    dir.kids.put(name, null);
                    n++;
                }
                r = new JSONObject().put("kind", "savedAll").put("count", n).toString();
            } catch (Throwable e) { r = PdfTools.err(e); }
            a.js("onPdf", r);
        }, "pdf-save").start();
    }
    @JavascriptInterface public void pdfDelete(String name) { try { PdfTools.output(ctx, name).delete(); } catch (Exception ignored) { } }

    static String errJson(Exception e) {
        try { return new JSONObject().put("error", String.valueOf(e.getMessage())).toString(); } catch (Exception x) { return "{}"; }
    }

        /** Interface-instellingen (localStorage) bewaren voor Alles back-uppen. */
        @JavascriptInterface public void webStoreSave(String json) {
            if (json == null || json.length() > 300_000) return;
            ctx.getSharedPreferences("webstore", Context.MODE_PRIVATE).edit().putString("all", json).apply();
        }
        /** Na terugzetten: eenmalig de bewaarde interface-instellingen teruggeven. */
        @JavascriptInterface public String webStorePending() {
            android.content.SharedPreferences p = ctx.getSharedPreferences("webstore", Context.MODE_PRIVATE);
            if (!p.getBoolean("pending", false)) return "";
            String r = p.getString("restoreAll", "");
            boolean replace = p.getBoolean("replace", false);
            p.edit().remove("pending").remove("restoreAll").remove("replace").apply();
            // Na terugzetten van alle appgegevens: de interface-instellingen vervangen (niet alleen aanvullen)
            if (replace) try { return new JSONObject(r).put("__replace", "1").toString(); } catch (Exception e) { return ""; }
            return r;
        }
        /** De app-eigen schakelaar: alle meldingen van Tools uit (true) of weer aan. */
        @JavascriptInterface public void notifMute(boolean on) { NotifCenter.setMuted(ctx, on); }
        @JavascriptInterface public String noteRemindAdd(String note, String title, String time, String rep) {
            try { return Reminders.add(ctx, note, title, Long.parseLong(time), rep); } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public String noteRemindList(String note) { return note == null ? "[]" : Reminders.forNote(ctx, note); }
        @JavascriptInterface public void noteRemindDel(String rid) { if (rid != null) Reminders.remove(ctx, rid); }
        @JavascriptInterface public void noteRemindClear(String note) {
            if (note == null) return;
            try { org.json.JSONArray l = new org.json.JSONArray(Reminders.forNote(ctx, note)); for (int i = 0; i < l.length(); i++) Reminders.remove(ctx, l.getJSONObject(i).getString("id")); } catch (Exception ignored) { }
        }
        @JavascriptInterface public void noteRemindRetitle(String note, String title) { if (note != null && title != null) Reminders.retitle(ctx, note, title); }
        @JavascriptInterface public String carState() { return Car.stateJson(ctx); }
        @JavascriptInterface public void carSet(String addr, String name, boolean trips, String defType) { Car.setCar(ctx, addr, name, trips, defType); }
        @JavascriptInterface public void carParkNow() {
            Auto.locate(ctx, 20_000, loc -> { Car.park(ctx, loc, "hand"); a.js("onCarChanged", loc == null ? "\"noloc\"" : "\"ok\""); });
        }
        @JavascriptInterface public void carLeft() { Car.closePark(ctx); }
        @JavascriptInterface public String carTripSet(String id, String type, String note) { return Car.setTrip(ctx, id, type, note); }
        @JavascriptInterface public String carTripDelete(String id) { return Car.deleteTrip(ctx, id); }
        @JavascriptInterface public void carExport(String month) {
            new Thread(() -> {
                String r;
                try { r = "ok:" + Car.exportMonth(ctx, month); } catch (Exception e) { r = e.getMessage() == null ? "Exporteren lukt niet" : e.getMessage(); }
                a.js("onCarExport", JSONObject.quote(r));
            }, "car-export").start();
        }
        @JavascriptInterface public void carTripStart() { Car.startTrip(ctx); }
        @JavascriptInterface public void carTripStop() { Car.stopTrip(ctx); }
        /**
         * Openen zonder eigen vraag (voordat deze aanroep terugkomt): de kluis vraagt nooit zelf om te ontgrendelen, dat doet
         * het slotscherm van Extra ontgrendelen. Open gaat hij meteen na dat slotscherm (MainActivity.toolSlotNeem) of met
         * extra ontgrendelen uit; anders onKluis('vraag'), dan toont de interface het slotscherm. Niet onder het app-slot,
         * niet tijdens een andere vraag, en als losse app alleen in die van de kluis of PDF (handtekening bewaren).
         */
        @JavascriptInterface public void kluisUnlock() {
            if (a.isDestroyed() || a.isFinishing()) return;
            if (Lock.busy()) { a.js("onKluis", JSONObject.quote("Er staat al een vraag om te ontgrendelen open")); return; }
            if (a.isSolo() && !"kluis".equals(a.solo) && !"pdf".equals(a.solo)) { a.js("onKluis", JSONObject.quote("De kluis is op slot")); return; }
            if (!a.isSolo() && Lock.wouldLock(ctx)) return; // onder het app-slot: na ontgrendelen vraagt de interface het opnieuw
            if (a.kluisOk()) Kluis.open(ctx);
            else if (a.toolSlotNeem("kluis") >= 0 || ToolSlot.eigenUit(ctx, "kluis")) a.kluisGen = Kluis.ontgrendeld(ctx);
            else { a.js("onKluis", "'vraag'"); return; }
            a.js("onKluis", "true");
        }
        @JavascriptInterface public String kluisList() {
            if (!a.kluisOk()) return "{\"locked\":true}";
            try { Kluis.open(ctx); return new JSONObject().put("docs", Kluis.list(ctx)).put("left", Kluis.remaining()).toString(); } catch (Exception e) { return errJson(e); }
        }
        @JavascriptInterface public void kluisAdd() {
            if (!a.kluisOk()) { a.js("onKluisChanged", JSONObject.quote("De kluis is weer op slot")); return; }
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { a.startActivityForResult(i, MainActivity.REQ_KLUIS); } catch (ActivityNotFoundException e) { Toast.makeText(a, Taal.tx(a, "Bestandskiezer niet beschikbaar"), Toast.LENGTH_SHORT).show(); }
            });
        }
        @JavascriptInterface public void kluisView(String id) {
            if (!a.kluisOk()) { a.js("onKluisChanged", JSONObject.quote("De kluis is weer op slot")); return; }
            new Thread(() -> {
                try {
                    File f = Kluis.view(ctx, id);
                    JSONObject d = Kluis.find(ctx, id);
                    String mime = d == null || d.optString("mime").isEmpty() ? ctx.getContentResolver().getType(Uri.fromFile(f)) : d.optString("mime");
                    Uri u = new Uri.Builder().scheme("content").authority(KluisProvider.AUTH).appendPath(f.getName()).build();
                    Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime == null ? "application/octet-stream" : mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    // niet de eigen PDF-viewer aanbieden: die maakt een kopie, en die hoort niet buiten de kluis te bestaan
                    final Intent ch = Intent.createChooser(v, "Openen met").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new android.content.ComponentName[]{new android.content.ComponentName(ctx, PdfViewActivity.class)});
                    a.runOnUiThread(() -> { try { a.startActivity(ch); } catch (Exception e) { a.js("onKluisChanged", JSONObject.quote("Geen app om dit te openen")); } });
                } catch (Exception e) { a.js("onKluisChanged", JSONObject.quote(e.getMessage() == null ? "Openen lukt niet" : e.getMessage())); }
            }, "kluis-view").start();
        }
        // ----- Documentenkluis als centrale opslag: gemaakte PDF's erin, bekijken in de eigen viewer, bewerken als nieuwe versie -----

        /**
         * Gemaakte PDF's (PDF-tools, ID-kopie, formulieren) in de kluis: versleuteld bewaren, daarna het leesbare bestand onder
         * Gemaakt weghalen. Per item: name (bestand in pdf-out), title, category, versionOf (id: nieuwe versie), source, link.
         * Antwoord via onKluisStored {stored:[{name,id,title,version}], failed:[{name,error}]}.
         */
        @JavascriptInterface public void kluisStore(String json) {
            if (!PlayPolicy.allows(ctx, "kluis")) { a.js("onKluisStored", PdfTools.err(new Exception(PlayPolicy.unavailable(ctx)))); return; }
            new Thread(() -> {
                String res;
                try {
                    if (json == null || json.length() > 40000) throw new Exception(Taal.T("Ongeldige opdracht"));
                    org.json.JSONArray items = new JSONObject(json).getJSONArray("items"), stored = new org.json.JSONArray(), failed = new org.json.JSONArray();
                    if (items.length() > 50) throw new Exception(Taal.T("Te veel bestanden tegelijk"));
                    for (int k = 0; k < items.length(); k++) {
                        JSONObject it = items.getJSONObject(k);
                        String name = it.optString("name");
                        try {
                            if (!a.kluisOk()) throw new Exception(Taal.T("De kluis is weer op slot"));
                            File f = PdfTools.output(ctx, name);
                            String of = it.optString("versionOf", "");
                            JSONObject d;
                            if (!of.isEmpty()) d = Kluis.addVersion(ctx, of, f, DocumentMeta.note(it.optString("note", "")));
                            else {
                                String title = it.optString("title", "").trim();
                                title = title.isEmpty() ? f.getName() : DocumentMeta.text(title, 120, false, "Naam");
                                d = Kluis.addFile(ctx, f, title, "application/pdf", it.optString("category", ""), it);
                            }
                            f.delete(); // staat nu versleuteld in de kluis; het leesbare bestand onder Gemaakt hoeft niet meer
                            if (!of.isEmpty()) PdfTools.dropKluisIn(ctx, of); // en de leesbare invoer van het bewerken ook niet
                            stored.put(new JSONObject().put("name", name).put("id", d.getString("id")).put("title", d.optString("name")).put("version", !of.isEmpty()));
                        } catch (Throwable e) { failed.put(new JSONObject().put("name", name).put("error", new JSONObject(PdfTools.err(e)).optString("error"))); }
                    }
                    res = new JSONObject().put("stored", stored).put("failed", failed).toString();
                } catch (Throwable e) { res = PdfTools.err(e); }
                a.js("onKluisStored", res);
            }, "kluis-bewaren").start();
        }
        /** Een document (of een oudere versie) openen: een PDF in de eigen viewer (geen kopie buiten de kluis), anders met een andere app. */
        @JavascriptInterface public void kluisOpen(String id, int version) { kluisShow(id, version, true); }
        private void kluisShow(String id, int version, boolean eigen) {
            if (!a.kluisOk()) { a.js("onKluisChanged", JSONObject.quote(Taal.T("De kluis is weer op slot"))); return; }
            new Thread(() -> {
                try {
                    JSONObject d = Kluis.find(ctx, Kluis.safeId(id));
                    if (d == null) throw new Exception(Taal.T("Document niet gevonden"));
                    File f = Kluis.viewVersion(ctx, id, version);
                    String mime = d.optString("mime").isEmpty() ? ctx.getContentResolver().getType(Uri.fromFile(f)) : d.optString("mime");
                    if (eigen && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                        final Intent v = new Intent(ctx, PdfViewActivity.class).setData(PdfProvider.uri(f)).putExtra(PdfViewActivity.EXTRA_KLUIS, id);
                        a.runOnUiThread(() -> { try { a.startActivity(v); } catch (Exception e) { a.js("onKluisChanged", JSONObject.quote(Taal.T("Openen lukt niet"))); } });
                        return;
                    }
                    Uri u = new Uri.Builder().scheme("content").authority(KluisProvider.AUTH).appendPath(f.getName()).build();
                    Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime == null ? "application/octet-stream" : mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    final Intent ch = Intent.createChooser(v, "Openen met").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new android.content.ComponentName[]{new android.content.ComponentName(ctx, PdfViewActivity.class)});
                    a.runOnUiThread(() -> { try { a.startActivity(ch); } catch (Exception e) { a.js("onKluisChanged", JSONObject.quote(Taal.T("Geen app om dit te openen"))); } });
                } catch (Throwable e) { a.js("onKluisChanged", JSONObject.quote(e.getMessage() == null ? "Openen lukt niet" : e.getMessage())); }
            }, "kluis-open").start();
        }
        /** Een PDF uit de kluis bewerken en ondertekenen; bewaren wordt een nieuwe versie (antwoord via onKluisEdit, zoals onPdf). */
        @JavascriptInterface public void kluisEdit(String id) {
            if (!a.kluisOk()) { a.js("onKluisEdit", PdfTools.err(new Exception(Taal.T("De kluis is weer op slot")))); return; }
            new Thread(() -> {
                String r;
                try {
                    JSONObject d = Kluis.find(ctx, Kluis.safeId(id));
                    if (d == null) throw new Exception(Taal.T("Document niet gevonden"));
                    if (!Kluis.fileName(d).toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) throw new Exception(Taal.T("Alleen een PDF is te bewerken"));
                    File tmp = new File(PdfTools.dir(ctx, "pdf-in"), "kopie.pdf");
                    Kluis.decryptTo(ctx, id, 0, tmp);
                    JSONObject j = new JSONObject(PdfTools.openKluisPdf(ctx, tmp, Kluis.fileName(d), id));
                    if (!j.has("error")) j.put("kind", "pdf").put("to", "edit");
                    r = j.toString();
                } catch (Throwable e) { r = PdfTools.err(e); }
                a.js("onKluisEdit", r);
            }, "kluis-bewerken").start();
        }
        /** Delen met een andere app (mail, WhatsApp): ontsleuteld in de kijkmap, die bij op slot gaan leeg gaat. */
        @JavascriptInterface public void kluisShare(String id, int version) {
            if (!a.kluisOk()) { a.js("onKluisChanged", JSONObject.quote(Taal.T("De kluis is weer op slot"))); return; }
            new Thread(() -> {
                try {
                    JSONObject d = Kluis.find(ctx, Kluis.safeId(id));
                    if (d == null) throw new Exception(Taal.T("Document niet gevonden"));
                    File f = Kluis.viewVersion(ctx, id, version);
                    Uri u = new Uri.Builder().scheme("content").authority(KluisProvider.AUTH).appendPath(f.getName()).build();
                    String mime = d.optString("mime").isEmpty() ? "application/octet-stream" : d.optString("mime");
                    Intent s = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    s.setClipData(android.content.ClipData.newRawUri("", u));
                    final Intent ch = Intent.createChooser(s, Taal.t(ctx, "Delen")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.runOnUiThread(() -> { try { a.startActivity(ch); } catch (Exception e) { a.js("onKluisChanged", JSONObject.quote(Taal.T("Delen lukt niet"))); } });
                } catch (Throwable e) { a.js("onKluisChanged", JSONObject.quote(e.getMessage() == null ? "Delen lukt niet" : e.getMessage())); }
            }, "kluis-delen").start();
        }
        /** Opslaan als… (een plek die Rene kiest): eerst de kiezer, daarna ontsleutelen en schrijven (MainActivity, REQ_KLUIS_SAVE). */
        @JavascriptInterface public void kluisSaveAs(String id, int version) {
            if (!a.kluisOk()) { a.js("onKluisChanged", JSONObject.quote(Taal.T("De kluis is weer op slot"))); return; }
            try {
                JSONObject d = Kluis.find(ctx, Kluis.safeId(id));
                if (d == null) throw new Exception(Taal.T("Document niet gevonden"));
                a.kluisSaveId = id; a.kluisSaveVersion = Math.max(0, version);
                String mime = d.optString("mime").isEmpty() ? "application/octet-stream" : d.optString("mime");
                final Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, Kluis.fileName(d));
                a.h.post(() -> { try { a.startActivityForResult(i, MainActivity.REQ_KLUIS_SAVE); } catch (Exception e) { a.js("onKluisChanged", JSONObject.quote(Taal.T("Opslaan niet beschikbaar"))); } });
            } catch (Exception e) { a.js("onKluisChanged", JSONObject.quote(e.getMessage() == null ? "Opslaan lukt niet" : e.getMessage())); }
        }
        /** Een oudere versie terugzetten (wat er nu staat blijft als versie bewaard). Antwoord via onKluisVersie. */
        @JavascriptInterface public void kluisRestore(String id, int version) {
            if (!a.kluisOk()) { a.js("onKluisVersie", PdfTools.err(new Exception(Taal.T("De kluis is op slot")))); return; }
            new Thread(() -> {
                String r;
                try { Kluis.restoreVersion(ctx, Kluis.safeId(id), version); r = "{\"ok\":true}"; }
                catch (Throwable e) { r = PdfTools.err(e); }
                a.js("onKluisVersie", r);
            }, "kluis-versie").start();
        }
        /** De leesbare kopie van een kluisdocument dat in de bewerker openstond weghalen (weg uit de PDF-schermen zonder bewaren). */
        @JavascriptInterface public void pdfKluisSluit() { new Thread(() -> { try { PdfTools.dropKluisIn(ctx, null); } catch (Throwable ignored) { } }, "pdf-kluis-sluit").start(); }
        /** Documenten die aan iets gekoppeld zijn (kind "id": een identiteitsdocument uit Stamgegevens). Op slot: {locked:true}. */
        @JavascriptInterface public String kluisLinked(String kind, String ref) {
            if (!a.kluisOk()) return "{\"locked\":true}";
            try {
                if (!"id".equals(kind) || ref == null || !ref.matches("[A-Za-z0-9_-]{1,64}")) throw new Exception(Taal.T("Onbekende koppeling"));
                return new JSONObject().put("docs", Kluis.linked(ctx, kind, ref)).toString();
            } catch (Exception e) { return errJson(e); }
        }
        // ----- Documenten vergelijken (PdfCompare): A en B uit een bestand, de kluis of Gemaakt -----

        /** Document A (0) of B (1) kiezen met de bestandskiezer; antwoord via onCmp. */
        @JavascriptInterface public void cmpPick(int slot) {
            if (slot < 0 || slot > 1) return;
            a.cmpPickSlot = slot;
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*"});
                try { a.startActivityForResult(i, MainActivity.REQ_CMP); }
                catch (Throwable e) { a.js("onCmp", PdfTools.err(new Exception(Taal.T("Bestandskiezer niet beschikbaar")))); }
            });
        }
        /** Een kluisdocument (of een oudere versie) als A of B: alleen tijdelijk ontsleuteld in de werkmap van vergelijken. */
        @JavascriptInterface public void cmpKluis(int slot, String id, int version) {
            if (slot < 0 || slot > 1) return;
            if (!a.kluisOk()) { a.js("onCmp", PdfTools.err(new Exception(Taal.T("De kluis is op slot")))); return; }
            final PdfCompare cmp = a.cmp;
            new Thread(() -> {
                String r;
                try {
                    JSONObject d = Kluis.find(ctx, Kluis.safeId(id));
                    if (d == null) throw new Exception(Taal.T("Document niet gevonden"));
                    File f = cmp.work(ctx);
                    Kluis.decryptTo(ctx, id, version, f);
                    String name = d.optString("name", "document") + (version > 0 ? " · " + Taal.t(ctx, "versie {n}", "n", version) : "");
                    r = cmp.set(slot, f, name, id, version).put("slot", slot).toString();
                } catch (Throwable e) { r = PdfTools.err(e); }
                a.js("onCmp", r);
            }, "vergelijken-kluis").start();
        }
        /** Een gemaakte PDF (Gemaakt) als A of B. */
        @JavascriptInterface public void cmpMade(int slot, String name) {
            if (slot < 0 || slot > 1) return;
            final PdfCompare cmp = a.cmp;
            new Thread(() -> {
                String r;
                try {
                    File src = PdfTools.output(ctx, name), f = cmp.work(ctx);
                    try (java.io.InputStream in = new java.io.FileInputStream(src); java.io.OutputStream o = new java.io.FileOutputStream(f)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); }
                    r = cmp.set(slot, f, PdfTools.base(name), "", 0).put("slot", slot).toString();
                } catch (Throwable e) { r = PdfTools.err(e); }
                a.js("onCmp", r);
            }, "vergelijken-gemaakt").start();
        }
        @JavascriptInterface public String cmpInfo() { try { return a.cmp.info().toString(); } catch (Exception e) { return "{}"; } }
        @JavascriptInterface public void cmpSwap() { a.cmp.swap(); }
        @JavascriptInterface public void cmpClear() { final PdfCompare old = a.cmp; a.cmp = new PdfCompare(); new Thread(() -> { try { old.clear(); } catch (Throwable ignored) { } }, "vergelijken-wissen").start(); }
        /** Tekstverschillen; antwoord via onCmpText. */
        @JavascriptInterface public void cmpText() {
            final PdfCompare cmp = a.cmp;
            new Thread(() -> {
                String r;
                try (Voortgang v = Voortgang.van(a, "vergelijken", 1)) { r = cmp.text(ctx, () -> cmp == a.cmp && !a.isDestroyed(), v).toString(); }
                catch (Throwable e) { r = PdfTools.err(e); }
                if (cmp == a.cmp) a.js("onCmpText", r);
            }, "vergelijken-tekst").start();
        }
        /** Pagina's met verschillen zoeken (grof); antwoord via onCmpScan. */
        @JavascriptInterface public void cmpScan() {
            final PdfCompare cmp = a.cmp;
            new Thread(() -> {
                String r;
                try (Voortgang v = Voortgang.van(a, "vergelijken", 1)) { r = cmp.scan(() -> cmp == a.cmp && !a.isDestroyed(), v).toString(); }
                catch (Throwable e) { r = PdfTools.err(e); }
                if (cmp == a.cmp) a.js("onCmpScan", r);
            }, "vergelijken-beeld").start();
        }
        /** Eén pagina groot (mode verschil, a of b); antwoord via onCmpPage. */
        @JavascriptInterface public void cmpPage(int i, String mode) {
            final PdfCompare cmp = a.cmp; final String m = "a".equals(mode) || "b".equals(mode) ? mode : "verschil";
            final int vraag = cmp.vraag(); // alleen de laatste vraag telt: snel doortikken stapelt geen werk op
            new Thread(() -> {
                String r;
                try { if (vraag != cmp.laatste()) return; r = cmp.page(i, m).toString(); }
                catch (Throwable e) { try { r = new JSONObject(PdfTools.err(e)).put("i", i).put("mode", m).toString(); } catch (Exception x) { r = "{}"; } }
                if (cmp == a.cmp) a.js("onCmpPage", r);
            }, "vergelijken-pagina").start();
        }

        @JavascriptInterface public String kluisDelete(String id) {
            if (!a.kluisOk()) return "De kluis is op slot";
            try { Kluis.delete(ctx, id); return ""; } catch (Exception e) { return "Verwijderen lukt niet"; }
        }
        @JavascriptInterface public String kluisRename(String id, String name) {
            if (!a.kluisOk() || name == null || name.trim().isEmpty()) return "Kan niet";
            try { Kluis.rename(ctx, id, name.trim()); return ""; } catch (Exception e) { return "Hernoemen lukt niet"; }
        }
        @JavascriptInterface public String kluisOrganize(String id,String json) {
            if (!a.kluisOk()) return "De kluis is op slot";
            try { Kluis.organize(ctx,id,json);return ""; }
            catch (Exception e) { return e.getMessage()==null?"Ordenen lukt niet":e.getMessage(); }
        }
        @JavascriptInterface public void kluisClose() {
            Kluis.close(ctx);
            try { ctx.revokeUriPermission(Uri.parse("content://" + KluisProvider.AUTH + "/"), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        }
        @JavascriptInterface public String voiceStart() { return VoiceNote.start(ctx, r -> a.js("onVoiceNote", r)); }
        @JavascriptInterface public void voiceStop() { VoiceNote.stop(ctx, r -> a.js("onVoiceNote", r)); }
        @JavascriptInterface public void voiceCancel() { VoiceNote.cancel(); }
        @JavascriptInterface public long voiceElapsed() { return VoiceNote.elapsed(); }
        /** Nummer van de radio bewaren in Herkende muziek, zonder herkenning (kost geen AudD-tegoed). Geeft "" of "dubbel". */
        @JavascriptInterface public void radioSaveClip(int seconds) {
            new Thread(() -> { String r = RadioService.saveClip(ctx, Math.max(30, Math.min(seconds, 3 * 3600))); a.js("onRadioClip", JSONObject.quote(r)); }, "radio-clip").start();
        }
        @JavascriptInterface public String musicSaveFromRadio(String artist, String title, String station) {
            try {
                if (title == null || title.trim().isEmpty()) return "leeg";
                org.json.JSONArray h = Music.history(ctx);
                for (int i = 0; i < Math.min(h.length(), 20); i++) {
                    JSONObject r = h.getJSONObject(i);
                    if (r.optString("title").equalsIgnoreCase(title.trim()) && r.optString("artist").equalsIgnoreCase(artist == null ? "" : artist.trim())
                            && System.currentTimeMillis() - r.optLong("t") < 3 * 60 * 60_000L) return "dubbel";
                }
                Music.addHistory(ctx, new JSONObject().put("t", System.currentTimeMillis()).put("artist", artist == null ? "" : artist.trim())
                        .put("title", title.trim()).put("source", "radio").put("station", station == null ? "" : station));
                return "";
            } catch (Exception e) { return "Bewaren lukt niet"; }
        }
        @JavascriptInterface public void radioMoveFavTo(int from, int to) {
            try { Radio.moveFavoriteTo(ctx, from, to); RadioWidget.refresh(ctx); } catch (Exception ignored) { }
        }
}
