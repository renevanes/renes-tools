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

    // ----- PDF -----

    private void pick(String type, String[] mimes, boolean multi, int req) {
        a.h.post(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);
            if (mimes != null) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
            if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { a.startActivityForResult(i, req); } catch (ActivityNotFoundException e) { Toast.makeText(a, "Bestandskiezer niet beschikbaar", Toast.LENGTH_SHORT).show(); }
        });
    }

    /** Uitkomst van een bestandskeuze voor het PDF-scherm (op een achtergrondthread). */
    static String pdfResult(Context c, int req, java.util.List<Uri> us, String saveName) {
        try {
            if (req == MainActivity.REQ_PDF_IMG) {
                int n = 0; StringBuilder warn = new StringBuilder();
                for (Uri u : us) { try { PdfTools.addImage(c, u); n++; } catch (Exception e) { if (warn.length() < 200) warn.append(e.getMessage()).append(". "); } }
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
        try { return PdfTools.inFile(ctx).isFile() ? new JSONObject().put("name", PdfTools.inName).put("pages", PdfTools.inPages).put("lossless", PdfTools.inLossless).toString() : "{}"; }
        catch (Exception e) { return "{}"; }
    }
    @JavascriptInterface public String pdfThumb(int i) { return PdfTools.pageThumb(ctx, i, 160); }
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
    /** Openen in de eigen viewer. */
    @JavascriptInterface public void pdfOpen(String name) {
        try {
            File f = PdfTools.output(ctx, name);
            Intent v = new Intent(ctx, PdfViewActivity.class).setData(PdfProvider.uri(f));
            a.h.post(() -> { try { a.startActivity(v); } catch (Exception e) { Toast.makeText(a, "Openen lukt niet", Toast.LENGTH_SHORT).show(); } });
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
    /** Rene's Tools als standaard PDF-app instellen (Android laat de gebruiker dat zelf kiezen). */
    @JavascriptInterface public void pdfMakeDefault() {
        a.h.post(() -> {
            JSONObject s = PdfViewActivity.defaultState(a);
            if ("other".equals(s.optString("state"))) PdfViewActivity.defaultDialog(a);
            else if ("none".equals(s.optString("state"))) PdfViewActivity.askAndroid(a);
            else Toast.makeText(a, "PDF's openen al in Rene's Tools", Toast.LENGTH_SHORT).show();
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
            a.h.post(() -> { try { a.startActivity(Intent.createChooser(s, "PDF delen")); } catch (Exception e) { Toast.makeText(a, "Delen lukt niet", Toast.LENGTH_SHORT).show(); } });
        } catch (Exception ignored) { }
    }
    /** Opslaan als: de gebruiker kiest waar (bijv. Downloads). */
    @JavascriptInterface public void pdfSave(String name) {
        try { PdfTools.output(ctx, name); } catch (Exception e) { return; }
        a.pdfSaveName = name;
        ctx.getSharedPreferences("pdf", Context.MODE_PRIVATE).edit().putString("saveName", name).apply(); // na herstarten van de app nog bekend
        a.h.post(() -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE, name);
            try { a.startActivityForResult(i, MainActivity.REQ_PDF_SAVE); } catch (ActivityNotFoundException e) { Toast.makeText(a, "Opslaan niet beschikbaar", Toast.LENGTH_SHORT).show(); }
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
                    Uri u = android.provider.DocumentsContract.createDocument(ctx.getContentResolver(), dir.uri, "application/pdf", name);
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
            p.edit().remove("pending").remove("restoreAll").apply();
            return r;
        }
        /** De app-eigen schakelaar: alle meldingen van Rene's Tools uit (true) of weer aan. */
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
        @JavascriptInterface public void kluisUnlock() {
            a.h.post(() -> {
                if (Kluis.isOpen()) { Kluis.open(ctx); a.js("onKluis", "true"); return; }
                if (Lock.authBusy) { a.js("onKluis", JSONObject.quote("Er staat al een vraag om te ontgrendelen open")); return; }
                Lock.prompt(a, "Kluis openen", (ok, msg) -> a.runOnUiThread(() -> {
                    if (ok) { Kluis.clearViews(ctx); Kluis.open(ctx); }
                    a.js("onKluis", ok ? "true" : JSONObject.quote(msg == null ? "Niet ontgrendeld" : msg));
                }));
            });
        }
        @JavascriptInterface public String kluisList() {
            if (!Kluis.isOpen()) return "{\"locked\":true}";
            try { Kluis.open(ctx); return new JSONObject().put("docs", Kluis.list(ctx)).put("left", Kluis.remaining()).toString(); } catch (Exception e) { return errJson(e); }
        }
        @JavascriptInterface public void kluisAdd() {
            if (!Kluis.isOpen()) { a.js("onKluisChanged", JSONObject.quote("De kluis is weer op slot")); return; }
            a.h.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { a.startActivityForResult(i, MainActivity.REQ_KLUIS); } catch (ActivityNotFoundException e) { Toast.makeText(a, "Bestandskiezer niet beschikbaar", Toast.LENGTH_SHORT).show(); }
            });
        }
        @JavascriptInterface public void kluisView(String id) {
            if (!Kluis.isOpen()) { a.js("onKluisChanged", JSONObject.quote("De kluis is weer op slot")); return; }
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
        @JavascriptInterface public String kluisDelete(String id) {
            if (!Kluis.isOpen()) return "De kluis is op slot";
            try { Kluis.delete(ctx, id); return ""; } catch (Exception e) { return "Verwijderen lukt niet"; }
        }
        @JavascriptInterface public String kluisRename(String id, String name) {
            if (!Kluis.isOpen() || name == null || name.trim().isEmpty()) return "Kan niet";
            try { Kluis.rename(ctx, id, name.trim()); return ""; } catch (Exception e) { return "Hernoemen lukt niet"; }
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
