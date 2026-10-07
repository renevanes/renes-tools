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
                    a.runOnUiThread(() -> { try { a.startActivity(Intent.createChooser(v, "Openen met").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { a.js("onKluisChanged", JSONObject.quote("Geen app om dit te openen")); } });
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
