package nl.rene.tools;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Brugfuncties voor de lokale AI (assistent en slim zoeken). Zit tussen SleepBridge en PodcastBridge, zodat de
 * interface ze als Android.aiXxx ziet. Het echte werk gebeurt op de AI-thread (LocalAi); antwoorden komen
 * terug via js-functies (onAiToken, onAiDone, onAiError, onAiStatus, onAiSources, onAiSearch, onAiIndex, onAiModels).
 */
public class AiBridge extends SleepBridge {

    AiBridge(MainActivity act) {
        super(act);
        LocalAi.appCtx = ctx;
        // Voortgang van de zoekindex (ook als hij vanzelf bijwerkt, bijv. na het aanzetten van een bron)
        LocalAi.idxSink = o -> a.js("onAiIndex", o.toString());
    }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    /** Alles over modellen, downloads en de zoekindex. */
    @JavascriptInterface public String aiState() {
        try { return LocalAi.info(ctx).toString(); } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface public String aiDownload(String id) {
        LocalAi.Model m = LocalAi.model(id);
        if (m == null) return "Onbekend model";
        if (!LocalAi.supported()) return "De lokale AI werkt alleen op telefoons met een 64-bits ARM-processor";
        return LocalAi.download(ctx, m);
    }

    @JavascriptInterface public void aiDelete(String id) {
        LocalAi.Model m = LocalAi.model(id);
        if (m != null) LocalAi.delete(ctx, m);
    }

    /** Oude modelbestanden (van modellen die deze versie niet meer gebruikt) weg; geeft de vrijgekomen bytes. */
    @JavascriptInterface public String aiCleanOld() { try { return String.valueOf(LocalAi.cleanOrphans(ctx)); } catch (Throwable t) { return "0"; } }

    /** Welk taalmodel de assistent gebruikt. */
    @JavascriptInterface public void aiUse(String id) {
        LocalAi.Model m = LocalAi.model(id);
        if (m == null || m.embed) return;
        LocalAi.prefs(ctx).edit().putString("chat", m.id).apply();
    }

    @JavascriptInterface public void aiPref(String name, boolean on) {
        boolean src = false;
        for (String k : LocalAi.SOURCES) if (("src_" + k).equals(name)) src = true;
        if (!src && !"wifiOnly".equals(name) && !"notes".equals(name) && !"voiceSend".equals(name) && !"speakVoice".equals(name)) return;
        LocalAi.prefs(ctx).edit().putBoolean(name, on).apply();
        // Bron aan: meteen (op de achtergrond) in de zoekindex; uit: eruit
        if (src) LocalAi.sourceChanged(ctx);
    }

    /** Melding als er al een antwoord loopt; de interface herkent hem en laat het lopende antwoord dan gewoon doorgaan. */
    static final String BUSY = "De assistent is nog bezig";

    /** Een vraag stellen: json = {messages:[{role, content}], notes:bool}. Geeft "" of een melding (BUSY: er loopt al een
     *  antwoord); de rest komt via onAi…. */
    @JavascriptInterface public String aiChat(String json) {
        final JSONObject req;
        try { req = new JSONObject(json); } catch (Exception e) { return "Ongeldige vraag"; }
        // Twee keer snel op versturen: de tweede vraag wordt geweigerd (er loopt al een antwoord), niet nog eens beantwoord
        try { if (!LocalAi.chat(ctx, req, new JsSink(a))) return BUSY; } catch (Throwable t) { return "De assistent kon niet starten"; }
        return "";
    }

    @JavascriptInterface public void aiCancel() { LocalAi.cancel(); }

    /** Instelling van de assistent (stijl, creativiteit, taal, gespreksgeheugen, rekenkracht, vrijgeven, over mij, instructies). */
    @JavascriptInterface public String aiConfigSet(String name, String value) {
        if (name == null || value == null) return "Ongeldige instelling";
        return LocalAi.cfgSet(ctx, name, value);
    }
    @JavascriptInterface public void aiConfigReset() { LocalAi.cfgReset(ctx); }

    // ----- Spraak: een vraag inspreken. Op de telefoon uitgeschreven met het spraakmodel (whisper.cpp) van
    // Gesprekken uitschrijven; de opname gaat na het uitschrijven weg. Antwoord via onAiVoice({text} of {error}).

    /** Geeft "" (luistert), "mic" (toestemming nodig), "model" (spraakmodel nodig) of een melding. */
    @JavascriptInterface public String aiVoiceStart() { return VoiceNote.start(ctx, r -> a.js("onAiVoice", r)); }
    @JavascriptInterface public void aiVoiceStop() { VoiceNote.stop(ctx, r -> a.js("onAiVoice", r)); }
    @JavascriptInterface public void aiVoiceCancel() { VoiceNote.cancel(); }
    /** idle, recording of working (bijv. na 5 minuten stopt Android de opname zelf, of de app ging naar de achtergrond). */
    @JavascriptInterface public String aiVoiceState() { return VoiceNote.state; }

    /** Spraakmodel aanwezig, en welk; voor de instellingen van de assistent. */
    @JavascriptInterface public String aiVoiceInfo() {
        try {
            Transcribe.Model m = Transcribe.activeModel(ctx);
            return new JSONObject().put("supported", Transcribe.supported()).put("model", m == null ? "" : m.id).put("label", m == null ? "" : m.label)
                    .put("busy", TranscribeService.busy).put("send", LocalAi.prefs(ctx).getBoolean("voiceSend", true)).toString();
        } catch (Exception e) { return "{}"; }
    }

    // ----- Voorlezen (tekst-naar-spraak van de telefoon, Nederlandse stem). Meldingen via onAiSpeak({state, id, error}).

    /** Een antwoord voorlezen (markdown gaat eraf); wat er al voorgelezen werd, stopt. */
    @JavascriptInterface public void aiSpeak(String text, String id) {
        if (text == null) return;
        AiSpeech.speak(ctx, text.length() > 30_000 ? text.substring(0, 30_000) : text, id, o -> a.js("onAiSpeak", o.toString()));
    }
    @JavascriptInterface public void aiSpeakStop() { AiSpeech.stop(); }

    // ----- Acties van de assistent (alleen na een tik op de knop onder het antwoord)

    /** Een afspraak in de agenda-app van de telefoon zetten: die opent met alles ingevuld, en je bewaart het daar zelf.
     *  json = {title, text, begin, end, allDay}. Geeft "" of een melding. */
    @JavascriptInterface public String aiCalendarAdd(String json) {
        final JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { return "Ongeldige afspraak"; }
        final long begin = o.optLong("begin"), end = o.optLong("end");
        if (begin <= 0) return "Kies eerst een datum";
        final String[] res = {"De agenda openen lukte niet"};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        a.h.post(() -> {
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_INSERT)
                        .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.Events.TITLE, LocalAi.cleanText(o.optString("title"), 200))
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end > begin ? end : begin + 3_600_000L)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, o.optBoolean("allDay"));
                String text = LocalAi.cleanText(o.optString("text"), 2000);
                if (!text.isEmpty()) i.putExtra(android.provider.CalendarContract.Events.DESCRIPTION, text);
                a.startActivity(i);
                res[0] = "";
            } catch (android.content.ActivityNotFoundException e) { res[0] = "Er is geen agenda-app op je telefoon";
            } catch (Throwable t) { res[0] = "De agenda openen lukte niet"; }
            done.countDown();
        });
        try { done.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return res[0];
    }

    /** Een antwoord naar het klembord. */
    @JavascriptInterface public void aiCopy(final String text) {
        if (text == null) return;
        a.h.post(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Assistent", text.length() > 100_000 ? text.substring(0, 100_000) : text));
            } catch (Exception ignored) { }
        });
    }

    /** Een antwoord of gesprek delen met een andere app (die jij kiest). */
    @JavascriptInterface public void aiShare(final String text) {
        if (text == null) return;
        a.h.post(() -> {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, text.length() > 100_000 ? text.substring(0, 100_000) : text);
            try { a.startActivity(android.content.Intent.createChooser(i, "Delen")); } catch (Exception ignored) { }
        });
    }

    // ----- Geheugen: alleen op de telefoon, alles zichtbaar en aan te passen (Lokale AI → Geheugen) -----

    /** Alle herinneringen, nieuwste eerst: [{id, text, src (gevraagd/geleerd/zelf), chat, title, t, u}]. */
    @JavascriptInterface public String aiMemList() { try { return AiMemory.json(ctx).toString(); } catch (Exception e) { return "[]"; } }

    /** Iets onthouden (een voorstel dat je goedkeurde, of zelf ingetypt). Geeft {id, text, dup} of {error}. */
    @JavascriptInterface public String aiMemAdd(String text, String src, String chat, String title) {
        try {
            String s = "zelf".equals(src) || "gevraagd".equals(src) ? src : "geleerd"; // terugzetten houdt de herkomst
            return AiMemory.add(ctx, text == null ? "" : text, s, chat, title).toString();
        } catch (Exception e) { return "{\"error\":\"Onthouden lukte niet\"}"; }
    }

    /** Geeft "" of een melding. */
    @JavascriptInterface public String aiMemEdit(String id, String text) {
        try { return id == null || text == null ? "Niet gevonden" : AiMemory.edit(ctx, id, text); } catch (Exception e) { return "Opslaan lukte niet"; }
    }

    /** id "*" = alles vergeten. */
    @JavascriptInterface public void aiMemDelete(String id) { try { if (id != null) AiMemory.delete(ctx, id); } catch (Exception ignored) { } }

    @JavascriptInterface public String aiChats() { try { return LocalAi.chatList(ctx); } catch (Exception e) { return "[]"; } }

    @JavascriptInterface public String aiChatGet(String id) { return id == null ? "" : LocalAi.chatGet(ctx, id); }

    /** Bewaart een gesprek; geeft het id. */
    @JavascriptInterface public String aiChatSave(String json) {
        try { return LocalAi.chatSave(ctx, new JSONObject(json)); } catch (Exception e) { return ""; }
    }

    /** id "*" = alle gesprekken. */
    @JavascriptInterface public void aiChatDelete(String id) { try { if (id != null) LocalAi.chatDelete(ctx, id); } catch (Exception ignored) { } }

    private static int searchSeq = 0;

    /** Zoeken op betekenis; antwoord via onAiSearch({id, q, items | need | error}). */
    @JavascriptInterface public int aiSearch(final String query) {
        final int id;
        synchronized (AiBridge.class) { id = ++searchSeq; }
        final String qq = query == null ? "" : query.trim();
        if (qq.length() < 2) return id;
        LocalAi.search(ctx, qq, () -> { synchronized (AiBridge.class) { return id == searchSeq; } }, o -> {
            try { o.put("id", id); } catch (Exception ignored) { }
            a.js("onAiSearch", o.toString());
        });
        return id;
    }

    /** Zoekindex bijwerken (force = helemaal opnieuw); voortgang via onAiIndex. */
    @JavascriptInterface public void aiIndex(boolean force) { LocalAi.index(ctx, force, false); }

    /** Vanzelf bijwerken als er iets veranderde (bijv. na een nieuwe toestemming); niet als je net op Stoppen tikte. */
    @JavascriptInterface public void aiIndexAuto() { LocalAi.indexLater(ctx); }

    /** Het bijwerken van de zoekindex stoppen (wat klaar is, blijft). */
    @JavascriptInterface public void aiIndexStop() { LocalAi.indexStop(); }

    /** Stuurt alles van de AI-thread naar het venster dat de vraag stelde. */
    static final class JsSink implements LocalAi.Sink {
        final MainActivity a;
        private final StringBuilder pend = new StringBuilder();
        private long last;
        JsSink(MainActivity a) { this.a = a; }
        // Woordjes samen versturen (hooguit ±12 keer per seconde): rustiger voor de interface
        private void flush() { if (pend.length() > 0) { a.js("onAiToken", q(pend.toString())); pend.setLength(0); } last = System.currentTimeMillis(); }
        @Override public void status(String text) { a.js("onAiStatus", q(text)); }
        @Override public void sources(JSONArray items) { a.js("onAiSources", items.toString()); }
        @Override public synchronized void token(String t) { pend.append(t); if (System.currentTimeMillis() - last > 80) flush(); }
        @Override public synchronized void done(JSONObject info) { flush(); a.js("onAiDone", info.toString()); }
        @Override public synchronized void error(String msg) { flush(); a.js("onAiError", q(msg)); }
        @Override public void memory(JSONObject o) { a.js("onAiMemory", o.toString()); }
    }
}
