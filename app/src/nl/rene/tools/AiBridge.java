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

    /** Modellen beheren: alle modellen (taal, zoeken, spraak), opslag, downloads en wat op te ruimen is. */
    @JavascriptInterface public String modelsState() {
        try { return Models.json(ctx).toString(); } catch (Throwable t) { return "{}"; }
    }

    /** Alles wat geen bruikbaar model is weg (oude modellen, halve downloads); geeft de vrijgekomen bytes. */
    @JavascriptInterface public String modelsClean() { try { return String.valueOf(Models.clean(ctx)); } catch (Throwable t) { return "0"; } }

    /** Onderbroken downloads vanzelf laten verdergaan (bij het openen van Modellen of Lokale AI). */
    @JavascriptInterface public void aiResume() { new Thread(() -> { try { LocalAi.resumeDownloads(ctx); } catch (Throwable ignored) { } }).start(); }

    /** Voorlezen: kijken of er een voorleesfunctie met een Nederlandse stem is (uitkomst via onAiModels). */
    @JavascriptInterface public void ttsCheck() { TtsCheck.run(ctx); }
    @JavascriptInterface public void ttsSettings() { a.h.post(() -> TtsCheck.openSettings(a)); }
    @JavascriptInterface public void ttsInstall() { a.h.post(() -> TtsCheck.install(a)); }

    /** Door de app zelf laten downloaden (of verder na een onderbreking), als Android's DownloadManager blijft wachten. */
    @JavascriptInterface public String aiDownloadApp(String id) {
        LocalAi.Model m = LocalAi.model(id);
        if (m == null) return "Onbekend model";
        if (!LocalAi.supported()) return "De lokale AI werkt alleen op telefoons met een 64-bits ARM-processor";
        try { return LocalAi.downloadApp(ctx, m); } catch (Throwable t) { return "Downloaden lukt niet"; }
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
        if (!src && !"wifiOnly".equals(name) && !"notes".equals(name) && !"voiceSend".equals(name) && !"speakVoice".equals(name)
                && !"talkSpeak".equals(name) && !"talkAuto".equals(name) && !"talkBarge".equals(name)) return;
        LocalAi.prefs(ctx).edit().putBoolean(name, on).apply();
        // Bron aan: meteen (op de achtergrond) in de zoekindex; uit: eruit (Gebruik en Apparaten zitten niet in de index)
        if (src && LocalAi.indexed(name.substring(4))) LocalAi.sourceChanged(ctx);
    }

    /** Melding als er al een antwoord loopt; de interface herkent hem en laat het lopende antwoord dan gewoon doorgaan. */
    static final String BUSY = "De assistent is nog bezig";

    /** Een vraag stellen: json = {messages:[{role, content}], notes:bool}. Geeft "" of een melding (BUSY: er loopt al een
     *  antwoord); de rest komt via onAi…. */
    @JavascriptInterface public String aiChat(String json) {
        final JSONObject req;
        try { req = new JSONObject(json); req.put("solo", a.isSolo()); } catch (Exception e) { return "Ongeldige vraag"; }
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

    /** Geeft "" (luistert), "mic" (toestemming nodig), "model" (spraakmodel nodig) of een melding. Live: met niveau en
     *  tussentekst (whisper op de telefoon); lukt dat niet, dan de gewone opname. */
    @JavascriptInterface public String aiVoiceStart() { try { VoiceNote.talkSilence = 0; return VoiceNote.startLive(ctx, r -> a.js("onAiVoice", r)); } catch (Throwable t) { return "Opnemen lukt niet"; } }
    @JavascriptInterface public void aiVoiceStop() { VoiceNote.stop(ctx, r -> a.js("onAiVoice", r)); }
    /** Ook tijdens het uitschrijven: dan komt er niets (onAiVoice({cancelled})). */
    @JavascriptInterface public void aiVoiceCancel() { VoiceNote.cancel(); }
    /** idle, recording of working (bijv. na 5 minuten stopt Android de opname zelf, of de app ging naar de achtergrond). */
    @JavascriptInterface public String aiVoiceState() { return VoiceNote.state; }
    /** Tijdens het inspreken (elke 0,1 s): {state, ms, live, lv:[niveaus 0–1 sinds de vorige keer], partial: tussentekst}. */
    @JavascriptInterface public String aiVoiceLive() { return VoiceNote.liveJson(); }

    /** Spraakmodel aanwezig, en welk; voor de instellingen van de assistent. */
    @JavascriptInterface public String aiVoiceInfo() {
        try {
            Transcribe.Model m = Transcribe.activeModel(ctx);
            return new JSONObject().put("supported", Transcribe.supported()).put("model", m == null ? "" : m.id).put("label", m == null ? "" : m.label)
                    .put("busy", TranscribeService.busy).put("send", LocalAi.prefs(ctx).getBoolean("voiceSend", false)).toString();
        } catch (Exception e) { return "{}"; }
    }

    // ----- Voorlezen (tekst-naar-spraak van de telefoon, Nederlandse stem). Meldingen via onAiSpeak({state, id, error}).

    /** Een antwoord voorlezen (markdown gaat eraf); wat er al voorgelezen werd, stopt. */
    @JavascriptInterface public void aiSpeak(String text, String id) {
        if (text == null) return;
        // App op de achtergrond (bijv. net weggeschakeld terwijl het antwoord nog kwam): niet gaan praten
        if (!MainActivity.visible) { try { a.js("onAiSpeak", new JSONObject().put("state", "stop").put("id", id == null ? "" : id).toString()); } catch (Exception ignored) { } return; }
        AiSpeech.speak(ctx, text.length() > 30_000 ? text.substring(0, 30_000) : text, id, o -> a.js("onAiSpeak", o.toString()));
    }
    @JavascriptInterface public void aiSpeakStop() { AiSpeech.stop(); }

    // ----- Je eigen gegevens: Gebruik, Slaappraat, Check-in, Apparaten (AiData). Alleen als de bron aan staat.

    /** Een duidelijke vraag over je gegevens: {topic, label, text, title} (antwoord van de app zelf), {topic, off} (bron staat
     *  uit), {topic, solo} (losse app), {topic} (voor het taalmodel, met de feiten erbij) of "" (geen vraag over je gegevens). */
    @JavascriptInterface public String aiData(String q) {
        try {
            if (q == null || q.length() > 300) return "";
            boolean devs = LocalAi.srcOn(ctx, "apparaten");
            String topic = AiData.topic(q, devs ? AiData.nicks(ctx) : null);
            if (topic.isEmpty()) return "";
            JSONObject o = new JSONObject().put("topic", topic).put("label", AiData.label(topic));
            if (a.isSolo()) return AiData.factual(topic, q) ? o.put("solo", true).toString() : "";
            if (!LocalAi.srcOn(ctx, topic)) return AiData.factual(topic, q) ? o.put("off", true).toString() : "";
            long now = System.currentTimeMillis();
            AiData.Answer an = AiData.answer(topic, q, AiData.facts(ctx, topic, now), now, java.util.TimeZone.getDefault());
            if (an == null) return o.toString();
            return o.put("text", an.text).put("title", an.title).toString();
        } catch (Throwable t) { return ""; }
    }

    // ----- Opdrachten (AiCommands): timer, wekker, radio, podcast, Slaappraat, een tool openen, een bericht klaarzetten.
    // Alleen na een tik op de knop onder het antwoord; een bericht verstuur je daarna zelf.

    /** De opdracht in je eigen vraag, of "". */
    @JavascriptInterface public String aiCmd(String q) {
        try { AiCommands.Cmd c = AiCommands.parse(q); return c == null ? "" : AiCommands.json(c).toString(); } catch (Throwable t) { return ""; }
    }

    /** Telefoonnummers van een contact uit je eigen contacten (voor een bericht): [{name, number, label}] of {perm:false}. */
    @JavascriptInterface public String aiContact(String name) {
        try {
            if (a.isSolo()) return "[]";
            if (!Search.has(ctx, android.Manifest.permission.READ_CONTACTS)) return "{\"perm\":false}";
            String q = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
            if (q.length() < 2 || q.length() > 40) return "[]";
            JSONArray best = new JSONArray(), good = new JSONArray();
            for (ContactsDiff.Rec r : Contacts.readCached(ctx)) {
                String n = r.name == null ? "" : r.name.toLowerCase(java.util.Locale.ROOT);
                boolean exact = n.equals(q), word = (" " + n + " ").contains(" " + q + " ") || n.startsWith(q);
                if (!exact && !word) continue;
                for (String line : Contacts.lines(r.get("Telefoon"))) {
                    String[] p = Contacts.splitLabel(line);
                    String num = AiCommands.number(p[0]);
                    if (num.isEmpty()) continue;
                    JSONObject x = new JSONObject().put("name", r.name).put("number", num).put("label", p[1]);
                    (exact ? best : good).put(x);
                }
                if (best.length() + good.length() >= 8) break;
            }
            for (int i = 0; i < good.length() && best.length() < 6; i++) best.put(good.get(i));
            return best.toString();
        } catch (Throwable t) { return "[]"; }
    }

    /** Een opdracht uitvoeren na de tik: timer of wekker (Klok-app, die je ziet), de Klok-app openen, of een sms of
     *  WhatsApp-bericht klaarzetten (de app opent met de tekst; versturen doe je zelf). Alles opnieuw nagekeken.
     *  json = {type: timer|wekker|klok|sms|wa, …}. Geeft "" of een melding. */
    @JavascriptInterface public String aiDo(String json) {
        final android.content.Intent[] tries;
        String none;
        try {
            JSONObject o = new JSONObject(json);
            String type = o.optString("type");
            if (a.isSolo() && !"timer".equals(type) && !"wekker".equals(type) && !"klok".equals(type)) return "Dat kan niet in een losse app";
            String label = AiCommands.cleanText(o.optString("label"), 60);
            switch (type) {
                case "timer": {
                    int secs = o.optInt("secs");
                    if (!AiCommands.validSecs(secs)) return "Kies een tijd tussen 1 seconde en 24 uur";
                    android.content.Intent i = new android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs).putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false);
                    if (!label.isEmpty()) i.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label);
                    tries = new android.content.Intent[]{i}; none = "Er is geen klok-app die een timer kan zetten";
                    break;
                }
                case "wekker": {
                    int min = o.optInt("min", -1);
                    if (!AiCommands.validClock(min)) return "Kies een geldige tijd";
                    android.content.Intent i = new android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, min / 60).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, min % 60)
                            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false);
                    if (!label.isEmpty()) i.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label);
                    tries = new android.content.Intent[]{i}; none = "Er is geen klok-app die een wekker kan zetten";
                    break;
                }
                case "klok": {
                    android.content.Intent al = new android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS);
                    tries = "timers".equals(o.optString("what")) && android.os.Build.VERSION.SDK_INT >= 26
                            ? new android.content.Intent[]{new android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS), al} : new android.content.Intent[]{al};
                    none = "Er is geen klok-app";
                    break;
                }
                case "sms": {
                    String num = AiCommands.number(o.optString("number"));
                    if (num.isEmpty()) return "Dat is geen geldig telefoonnummer";
                    tries = new android.content.Intent[]{new android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + android.net.Uri.encode(num)))
                            .putExtra("sms_body", AiCommands.cleanText(o.optString("text"), AiCommands.MAX_TEXT))};
                    none = "Er is geen app voor sms'jes";
                    break;
                }
                case "wa": {
                    String text = AiCommands.cleanText(o.optString("text"), AiCommands.MAX_TEXT);
                    String raw = o.optString("number"), num = raw.isEmpty() ? "" : AiCommands.waNumber(raw);
                    if (!raw.isEmpty() && num.isEmpty()) return "Dat is geen geldig telefoonnummer";
                    if (!num.isEmpty()) {
                        android.net.Uri u = android.net.Uri.parse("whatsapp://send?phone=" + num + "&text=" + android.net.Uri.encode(text));
                        tries = new android.content.Intent[]{new android.content.Intent(android.content.Intent.ACTION_VIEW, u).setPackage("com.whatsapp"),
                                new android.content.Intent(android.content.Intent.ACTION_VIEW, u).setPackage("com.whatsapp.w4b"), new android.content.Intent(android.content.Intent.ACTION_VIEW, u)};
                    } else {
                        tries = new android.content.Intent[]{new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text).setPackage("com.whatsapp"),
                                new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text).setPackage("com.whatsapp.w4b")};
                    }
                    none = "WhatsApp staat niet op je telefoon";
                    break;
                }
                default: return "Onbekende opdracht";
            }
        } catch (Exception e) { return "Ongeldige opdracht"; }
        final String noApp = none;
        final String[] res = {noApp};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        a.h.post(() -> {
            try {
                for (android.content.Intent i : tries) {
                    try { a.startActivity(i); res[0] = ""; break; } catch (android.content.ActivityNotFoundException ignored) { }
                }
            } catch (SecurityException e) { res[0] = "Android staat dit niet toe";
            } catch (Throwable t) { res[0] = "Dat lukte niet"; }
            done.countDown();
        });
        try { if (!done.await(3, java.util.concurrent.TimeUnit.SECONDS)) return ""; } catch (InterruptedException e) { return ""; }
        return res[0];
    }

    // ----- Gesprek (handsfree): luisteren tot je uitgesproken bent, voorlezen, onderbreken (AiTalk)

    /** Zoals aiVoiceStart, maar de opname merkt zelf dat je klaar bent (na silenceMs stilte): aiVoiceLive geeft dan end. */
    @JavascriptInterface public String aiTalkListen(int silenceMs) {
        try {
            String cf = AiTalk.conflict(ctx);
            if (cf != null) return cf;
            AiTalk.bargeStop();
            VoiceNote.talkSilence = Math.max(500, Math.min(4000, silenceMs));
            return VoiceNote.startLive(ctx, r -> a.js("onAiVoice", r));
        } catch (Throwable t) { return "Opnemen lukt niet"; }
    }
    /** {call, conflict}: een telefoongesprek, of iets anders dat de microfoon gebruikt. */
    @JavascriptInterface public String aiTalkPoll() { return AiTalk.pollJson(ctx); }
    /** Het scherm aan laten zolang het gesprek loopt. */
    @JavascriptInterface public void aiTalkScreen(final boolean on) {
        a.h.post(() -> {
            try {
                if (on) a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Throwable ignored) { }
        });
    }
    /** Tijdens het voorlezen luisteren of je gaat praten (onderbreken); "" of waarom niet. */
    @JavascriptInterface public String aiBargeStart() { try { return AiTalk.bargeStart(ctx); } catch (Throwable t) { return "Onderbreken door te praten lukt niet"; } }
    @JavascriptInterface public void aiBargeStop() { try { AiTalk.bargeStop(); } catch (Throwable ignored) { } }
    /** {on, hit, lv, why}. */
    @JavascriptInterface public String aiBarge() { return AiTalk.bargeJson(); }

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
        // Te lang gewacht (de telefoon is druk): de agenda opent waarschijnlijk nog, dus geen foutmelding
        try { if (!done.await(3, java.util.concurrent.TimeUnit.SECONDS)) return ""; } catch (InterruptedException e) { return ""; }
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
        @Override public void actWanted() { a.js("onAiActWanted", "true"); }
    }
}
