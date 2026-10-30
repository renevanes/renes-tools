package nl.rene.tools;

import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * Brugfuncties voor Check-in (Android.checkinXxx). Tussen ApparatenBridge en SleepBridge. Opslag en rekenwerk staan in
 * CheckinStore en Checkin, de meldingen in CheckinNotify. Alles blijft op de telefoon.
 */
public class CheckinBridge extends ApparatenBridge {

    CheckinBridge(MainActivity act) { super(act); }

    private static String errJson(Throwable e) { return CheckinStore.err(e.getMessage() != null ? e.getMessage() : "Dat lukt niet").toString(); }

    /** Alles voor het hoofdscherm en de tegel: de vragen met de periode van nu, de reeks en de volgende melding. */
    @JavascriptInterface public String checkinState() {
        try {
            JSONObject o = CheckinStore.state(ctx, System.currentTimeMillis());
            Transcribe.Model m = Transcribe.activeModel(ctx);
            o.put("notifOk", NotifCenter.permissionOk(ctx)).put("muted", NotifCenter.muted(ctx)).put("calOk", CheckinStore.calendarOk(ctx))
                    .put("model", m == null ? "" : m.label);
            return o.toString();
        } catch (Throwable e) { App.log(ctx, "CHECKIN", "stand: " + e); return "{}"; }
    }

    /** Een vraag bewaren (nieuw zonder id). Geeft {id} of {error}. */
    @JavascriptInterface public String checkinSave(String json) {
        try {
            String id = CheckinStore.save(ctx, new JSONObject(json));
            CheckinNotify.changed(ctx, id);
            return new JSONObject().put("id", id).toString();
        } catch (Throwable e) { return errJson(e); }
    }

    /** Vraag met alle antwoorden weg. */
    @JavascriptInterface public String checkinDelete(String id) {
        try { CheckinStore.delete(ctx, id); CheckinNotify.changed(ctx, id); return ""; }
        catch (Throwable e) { return e.getMessage() != null ? e.getMessage() : "Verwijderen lukt niet"; }
    }

    @JavascriptInterface public void checkinMove(String id, int dir) {
        try { CheckinStore.move(ctx, id, dir); } catch (Throwable e) { App.log(ctx, "CHECKIN", "verplaatsen: " + e); }
    }

    /** Kopie (staat uit). Geeft {id} of {error}. */
    @JavascriptInterface public String checkinDuplicate(String id) {
        try { return new JSONObject().put("id", CheckinStore.duplicate(ctx, id)).toString(); } catch (Throwable e) { return errJson(e); }
    }

    /** Antwoorden of een antwoord aanvullen: zie CheckinStore.answer. */
    @JavascriptInterface public String checkinAnswer(String json) {
        try {
            JSONObject r = CheckinStore.answer(ctx, new JSONObject(json), System.currentTimeMillis());
            if (r.optBoolean("ok")) CheckinNotify.dismiss(ctx, new JSONObject(json).optString("q"), r.optString("key"));
            return r.toString();
        } catch (Throwable e) { return errJson(e); }
    }

    @JavascriptInterface public String checkinDeleteAnswer(String id) {
        try { return CheckinStore.deleteAnswer(ctx, id); } catch (Throwable e) { return "Wissen lukt niet"; }
    }

    /** Terugkijken: periodes van [from, to) met antwoorden en een samenvatting. */
    @JavascriptInterface public String checkinHistory(String id, String from, String to) {
        try { return CheckinStore.history(ctx, id, from, to, System.currentTimeMillis()).toString(); } catch (Throwable e) { return errJson(e); }
    }

    /** Per dag de nacht (Slaappraat) en de agenda (alleen lezen, alleen met toestemming). */
    @JavascriptInterface public String checkinDays(String from, String to) {
        try { return CheckinStore.days(ctx, from, to).toString(); } catch (Throwable e) { return errJson(e); }
    }

    /** De melding nu proberen. "" of een melding ("notif" = eerst toestemming). */
    @JavascriptInterface public String checkinPreview(String id) {
        try { return CheckinNotify.preview(ctx, id); } catch (Throwable e) { return "Dat lukt niet"; }
    }
}
