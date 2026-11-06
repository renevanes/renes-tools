package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mailbron Gmail: zoekt met de Gmail-API (alleen lezen, rechten gmail.readonly via de Google-koppeling in FacturenDrive)
 * naar mails met een PDF-bijlage die op een factuur lijken, en geeft die bijlagen terug. Tools wijzigt niets in Gmail.
 */
final class FacturenGmail implements FacturenMail.Bron {

    static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    static final String API = "https://gmail.googleapis.com/gmail/v1/users/me";

    @Override public String koppeling() { return "google"; }

    /** De Gmail-zoekopdracht uit de instellingen (zonder Android: getest in FacturenTest). */
    static String zoekvraag(JSONObject mail, long sinds) {
        StringBuilder q = new StringBuilder("has:attachment filename:pdf");
        List<String> w = FacturenMail.delen(mail.optString("woorden"));
        if (!w.isEmpty()) {
            q.append(" (");
            for (int i = 0; i < w.size(); i++) {
                String t = w.get(i);
                if (i > 0) q.append(" OR ");
                q.append(t.contains(" ") ? "\"" + t + "\"" : t);
                if (!t.contains(" ")) q.append(" OR filename:").append(t);
            }
            q.append(")");
        }
        List<String> a = FacturenMail.delen(mail.optString("afzenders"));
        if (!a.isEmpty()) {
            q.append(" from:(");
            for (int i = 0; i < a.size(); i++) q.append(i > 0 ? " OR " : "").append(a.get(i).contains(" ") ? "\"" + a.get(i) + "\"" : a.get(i));
            q.append(")");
        }
        for (String n : FacturenMail.delen(mail.optString("negeer"))) q.append(" -from:").append(n.contains(" ") ? "\"" + n + "\"" : n);
        String extra = mail.optString("extra", "").trim();
        if (!extra.isEmpty()) q.append(" ").append(extra);
        if (sinds > 0) q.append(" after:").append(sinds / 1000);
        return q.toString();
    }

    /** Alle bijlagen van een bericht (ook diep in multipart): [{naam, mime, id, data}]. */
    static List<JSONObject> bijlagen(JSONObject deel, List<JSONObject> uit) {
        if (uit == null) uit = new ArrayList<>();
        if (deel == null) return uit;
        String naam = deel.optString("filename", "");
        JSONObject body = deel.optJSONObject("body");
        String mime = deel.optString("mimeType", "").toLowerCase(Locale.ROOT);
        if (!naam.isEmpty() && body != null && (naam.toLowerCase(Locale.ROOT).endsWith(".pdf") || mime.contains("pdf"))) {
            try {
                uit.add(new JSONObject().put("naam", naam).put("mime", mime).put("id", body.optString("attachmentId", ""))
                        .put("data", body.optString("data", "")).put("grootte", body.optLong("size")));
            } catch (Exception ignored) { }
        }
        JSONArray parts = deel.optJSONArray("parts");
        for (int i = 0; parts != null && i < parts.length(); i++) bijlagen(parts.optJSONObject(i), uit);
        return uit;
    }

    static String kop(JSONObject payload, String naam) {
        JSONArray h = payload == null ? null : payload.optJSONArray("headers");
        for (int i = 0; h != null && i < h.length(); i++) {
            JSONObject x = h.optJSONObject(i);
            if (x != null && naam.equalsIgnoreCase(x.optString("name"))) return x.optString("value");
        }
        return "";
    }

    /** De tekst van de mail zelf (text/plain, anders html zonder opmaak), hooguit 20.000 tekens: om een doorgestuurde afzender te vinden. */
    static String tekst(JSONObject payload) {
        String t = deelTekst(payload, "text/plain");
        if (t.isEmpty()) t = html(deelTekst(payload, "text/html"));
        return t.length() > 20_000 ? t.substring(0, 20_000) : t;
    }

    static String html(String h) {
        return h.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>", "\n").replaceAll("<[^>]+>", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static String deelTekst(JSONObject deel, String mime) {
        if (deel == null) return "";
        JSONObject body = deel.optJSONObject("body");
        if (mime.equalsIgnoreCase(deel.optString("mimeType")) && deel.optString("filename", "").isEmpty() && body != null && !body.optString("data").isEmpty()) {
            try { return new String(base64url(body.optString("data")), java.nio.charset.StandardCharsets.UTF_8); } catch (Throwable e) { return ""; }
        }
        JSONArray parts = deel.optJSONArray("parts");
        for (int i = 0; parts != null && i < parts.length(); i++) { String s = deelTekst(parts.optJSONObject(i), mime); if (!s.isEmpty()) return s; }
        return "";
    }

    static byte[] base64url(String s) { return android.util.Base64.decode(s, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING); }

    @Override
    public List<Facturen.Pdf> haal(Context c, JSONObject lev, long sinds, AtomicBoolean stop, FacturenMail.Voortgang v) throws Exception {
        if (!FacturenDrive.gekoppeld(c)) throw new Exception("Koppel eerst Google (Facturen → Koppelingen) en geef toegang tot Gmail");
        if (!FacturenDrive.heeftRecht(c, "gmail")) throw new Exception("Geef Tools toegang tot Gmail (Facturen → Koppelingen → Google)");
        JSONObject mail = lev.optJSONObject("mail") == null ? new JSONObject() : lev.optJSONObject("mail");
        int max = mail.optInt("max", 50);
        String q = zoekvraag(mail, sinds);
        v.meld("Gmail doorzoeken…");
        // Alle passende mails opzoeken (Gmail geeft de nieuwste eerst), dan de oudste eerst ophalen, hooguit "max" per keer.
        // Blijft er iets over, dan onthouden tot waar (mailSinds), zodat de volgende keer daar verder gaat: niets wordt overgeslagen.
        List<String> ids = new ArrayList<>();
        String pagina = "";
        do {
            JSONObject r = FacturenDrive.api(c, "GET", API + "/messages?maxResults=100&q=" + FacturenDrive.enc(q)
                    + (pagina.isEmpty() ? "" : "&pageToken=" + FacturenDrive.enc(pagina)), null);
            JSONArray l = r.optJSONArray("messages");
            for (int i = 0; l != null && i < l.length(); i++) ids.add(l.getJSONObject(i).optString("id"));
            pagina = r.optString("nextPageToken", "");
        } while (!pagina.isEmpty() && ids.size() < 2000 && !stop.get());
        java.util.Collections.reverse(ids); // oudste eerst
        boolean meer = ids.size() > max;
        if (meer) ids = new ArrayList<>(ids.subList(0, max));
        List<Facturen.Pdf> uit = new ArrayList<>();
        java.text.SimpleDateFormat dag = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        long laatste = 0;
        int fouten = 0;
        String eersteFout = null;
        for (int n = 0; n < ids.size() && !stop.get(); n++) {
            v.meld("Mail " + (n + 1) + " van " + ids.size() + " bekijken…");
            String id = ids.get(n);
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) continue;
            try { // één mail die niet lukt (verwijderd, te groot) houdt de rest niet tegen
                JSONObject m = FacturenDrive.api(c, "GET", API + "/messages/" + id + "?format=full", null);
                JSONObject payload = m.optJSONObject("payload");
                String afzender = FacturenMail.afzenderNaam(kop(payload, "From"));
                String door = null; // de oorspronkelijke afzender als de mail is doorgestuurd (pas lezen als er een PDF is)
                long t = m.optLong("internalDate", System.currentTimeMillis());
                laatste = Math.max(laatste, t);
                for (JSONObject b : bijlagen(payload, null)) {
                    if (b.optLong("grootte") > Facturen.MAX_PDF) continue;
                    byte[] data;
                    if (!b.optString("data").isEmpty()) data = base64url(b.optString("data"));
                    else if (b.optString("id").matches("[A-Za-z0-9_-]{1,1024}"))
                        data = base64url(FacturenDrive.apiGroot(c, API + "/messages/" + id + "/attachments/" + b.optString("id")).optString("data"));
                    else continue;
                    if (!Facturen.isPdf(data)) continue;
                    String sha = Facturen.sha(data);
                    String origineel = b.optString("naam");
                    // Zonder nummer of datum in de naam: de datum van de mail ervoor (anders heten alle facturen "factuur.pdf")
                    String basis = Facturen.uniekeNaam(origineel) ? origineel : dag.format(new java.util.Date(t)) + " " + origineel;
                    String naam = Facturen.bestandsnaam(basis, null, afzender, sha);
                    if (door == null) door = FacturenHerken.doorgestuurdVan(tekst(payload));
                    JSONObject bron = new JSONObject().put("mailbron", "gmail").put("afzender", kop(payload, "From")).put("afzenderNaam", afzender)
                        .put("onderwerp", kop(payload, "Subject")).put("maildatum", t).put("mailId", id).put("bijlage", origineel).put("basis", basis);
                    String antw = kop(payload, "Reply-To");
                    if (!antw.isEmpty()) bron.put("antwoordAan", antw);
                    if (!door.isEmpty()) bron.put("doorgestuurdVan", door);
                    uit.add(new Facturen.Pdf(data, naam, sha, null, bron));
                }
            } catch (Exception e) {
                fouten++;
                if (eersteFout == null) eersteFout = Facturen.kort(e);
                if (fouten >= 5 && uit.isEmpty()) throw e; // alles mislukt: dan klopt er iets niet (verbinding, rechten)
            }
        }
        // Tot waar: bij "meer" de laatste opgehaalde mail, anders weer gewoon vanaf de vorige keer
        Facturen.werkBij(c, lev.optString("id"), new JSONObject().put("mailSinds", meer && laatste > 0 ? laatste : 0)
                .put("mailMeer", meer).put("mailFouten", fouten));
        if (stop.get()) throw new Exception("Gestopt");
        v.meld(ids.isEmpty() ? "Geen mails met facturen gevonden" : uit.size() + " PDF-bijlagen gevonden" + (meer ? "; de rest volgt de volgende keer" : "") + (fouten > 0 ? " (" + fouten + " mails niet gelukt: " + eersteFout + ")" : ""));
        return uit;
    }
}
