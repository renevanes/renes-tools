package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aanleverkanaal e-mail: de facturen als bijlage naar een of meer adressen (de accountant of boekhouder), verstuurd via
 * een van je eigen mailaccounts: Eigen Cloud, je eigen SMTP-server of Gmail (rechten gmail.send). Alleen naar de adressen
 * die Rene zelf invult.
 *
 * Nieuwe manier van versturen (bijv. Outlook via Microsoft Graph): een klasse die {@link Verzender} doet, toevoegen in
 * {@link #VERZENDERS}, een keuze in #fo-via op het scherm, een koppeling (eigen geheimen in facturen_geheim) en tests.
 */
final class FacturenEmail implements FacturenAanlever.Kanaal {

    /** Eén mailaccount waarmee Tools kan versturen. */
    interface Verzender {
        String naam();
        /** Wat er ontbreekt (koppeling), of "". */
        String klaar(Context c);
        /** Je eigen adres bij dit account (voor de proef en "kopie aan mij"). */
        String van(Context c);
        void stuur(Context c, Bericht b, String sleutel) throws Exception;
    }

    static final class Bijlage {
        final String naam, mime; final byte[] data;
        Bijlage(String naam, String mime, byte[] data) { this.naam = naam; this.mime = mime; this.data = data; }
    }

    static final class Bericht {
        String van = "", vanNaam = "", onderwerp = "", tekst = "";
        final List<String> aan = new ArrayList<>(), cc = new ArrayList<>(), bcc = new ArrayList<>();
        final List<Bijlage> bijlagen = new ArrayList<>();
        List<String> alleOntvangers() { List<String> l = new ArrayList<>(aan); l.addAll(cc); l.addAll(bcc); return l; }
    }

    static final Map<String, Verzender> VERZENDERS = new LinkedHashMap<>();
    static {
        VERZENDERS.put("eigencloud", new EigenCloud());
        VERZENDERS.put("smtp", new Smtp());
        VERZENDERS.put("gmail", new Gmail());
    }

    static final String ONDERWERP = "Facturen {periode}";
    static final String TEKST = "Hallo,\n\nHierbij {aantal} ({leveranciers}):\n\n{lijst}\n\nMet vriendelijke groet,\nRene van Es";
    static final long MB = 1_048_576L;

    @Override public String naam() { return "E-mail"; }

    /** Alleen gewone (ASCII-)tekens: de koppen van de mail zijn ASCII en SMTP zonder SMTPUTF8. */
    private static final String ADRESDEEL = "[\\x21-\\x7e&&[^@<>,;\"'()\\\\\\[\\]]]+";
    static final String ADRES = ADRESDEEL + "@" + ADRESDEEL + "\\." + ADRESDEEL;

    /** "a@b.nl, c@d.nl; e@f.nl" → lijst (gecontroleerd). */
    static List<String> adressen(String s, int max, String wat) throws Exception {
        List<String> out = new ArrayList<>();
        for (String x : (s == null ? "" : s).split("[,;\\s]+")) {
            String a = x.trim();
            if (a.isEmpty()) continue;
            if (a.length() > 254 || !a.matches(ADRES)) throw new Exception("Het adres bij " + wat + " klopt niet: " + (a.length() > 60 ? a.substring(0, 60) + "…" : a));
            if (!out.contains(a.toLowerCase(Locale.ROOT))) out.add(a.toLowerCase(Locale.ROOT));
        }
        if (out.size() > max) throw new Exception("Hooguit " + max + " adressen bij " + wat);
        return out;
    }

    static String samen(List<String> l) { StringBuilder b = new StringBuilder(); for (String x : l) b.append(b.length() > 0 ? ", " : "").append(x); return b.toString(); }

    @Override public void normaal(JSONObject in, JSONObject uit) throws Exception {
        List<String> aan = adressen(in.optString("aan"), 10, "Aan");
        if (aan.isEmpty()) throw new Exception("Vul het mailadres van je accountant of boekhouder in");
        uit.put("aan", samen(aan)).put("cc", samen(adressen(in.optString("cc"), 10, "Cc")));
        String via = in.optString("via", "eigencloud");
        if (!VERZENDERS.containsKey(via)) throw new Exception("Kies waarmee de mail verstuurd wordt");
        uit.put("via", via);
        String ond = in.optString("onderwerp", "").trim(), tekst = in.optString("tekst", "").replace("\r", "");
        if (ond.isEmpty()) ond = ONDERWERP;
        if (tekst.trim().isEmpty()) tekst = TEKST;
        if (ond.length() > 200 || ond.matches("(?s).*[\\n\\r].*")) throw new Exception("Het onderwerp is te lang");
        if (tekst.length() > 5000) throw new Exception("De tekst is te lang");
        uit.put("onderwerp", ond).put("tekst", tekst);
        String bundel = in.optString("bundel", "samen");
        if (!"los".equals(bundel) && !"samen".equals(bundel)) throw new Exception("Kies: alles in één mail of één mail per factuur");
        int mb = in.optInt("maxMb", 15);
        if (mb < 1 || mb > 20) throw new Exception("Grootte van een mail: 1 tot 20 MB");
        uit.put("bundel", bundel).put("maxMb", mb).put("kopieMij", in.optBoolean("kopieMij", false));
    }

    @Override public String klaar(Context c, JSONObject ontv) {
        Verzender v = VERZENDERS.get(ontv.optString("via"));
        return v == null ? "Kies waarmee de mail verstuurd wordt" : v.klaar(c);
    }

    /** Bijlagen samen hooguit zo groot dat de hele mail (base64 is 4/3 groter, plus koppen en tekst) onder maxMb blijft. */
    @Override public long maxZending(JSONObject ontv) { return ontv.optInt("maxMb", 15) * MB * 3 / 4 - 100_000L; }

    @Override public String stuur(Context c, JSONObject ontv, FacturenAanlever.Zending z, boolean proef) throws Exception {
        Verzender v = VERZENDERS.get(ontv.optString("via"));
        if (v == null) throw new Exception("Kies waarmee de mail verstuurd wordt");
        Bericht b = bericht(ontv, z.stukken, v.van(c), proef, System.currentTimeMillis(), Regio.zone(c));
        for (int i = 0; i < z.bestanden.size(); i++) {
            File f = z.bestanden.get(i);
            byte[] data;
            try (java.io.InputStream in = new java.io.FileInputStream(f)) { data = Facturen.lees(in, Facturen.MAX_PDF); }
            b.bijlagen.add(new Bijlage(z.stukken.get(i).optString("naam", f.getName()), "application/pdf", data));
        }
        if (b.aan.isEmpty()) throw new Exception("Geen eigen adres bekend voor de proef");
        v.stuur(c, b, z.sleutel);
        return samen(b.aan);
    }

    /** Het bericht (zonder bijlagen) uit de instellingen. Proef: alleen naar jezelf. (zonder Android) */
    static Bericht bericht(JSONObject ontv, List<JSONObject> stukken, String eigen, boolean proef, long nu, java.util.TimeZone tz) throws Exception {
        Bericht b = new Bericht();
        b.van = eigen == null ? "" : eigen;
        b.vanNaam = "Rene van Es";
        b.onderwerp = FacturenAanlever.vul(ontv.optString("onderwerp", ONDERWERP), ontv, stukken, nu, tz).replaceAll("[\\r\\n]+", " ").trim();
        b.tekst = FacturenAanlever.vul(ontv.optString("tekst", TEKST), ontv, stukken, nu, tz);
        if (proef) {
            if (!b.van.isEmpty()) b.aan.add(b.van);
            b.onderwerp = "[Proef] " + b.onderwerp;
            b.tekst = "Dit is een proef van Tools: zo ziet de mail aan " + ontv.optString("naam") + " (" + ontv.optString("aan") + ") eruit.\n\n" + b.tekst;
        } else {
            b.aan.addAll(adressen(ontv.optString("aan"), 10, "Aan"));
            b.cc.addAll(adressen(ontv.optString("cc"), 10, "Cc"));
            if (ontv.optBoolean("kopieMij") && !b.van.isEmpty() && !b.aan.contains(b.van) && !b.cc.contains(b.van)) b.bcc.add(b.van);
        }
        if (b.onderwerp.isEmpty()) b.onderwerp = "Facturen";
        return b;
    }

    // ================= MIME (zonder Android: getest in FacturenTest) =================

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Base64 (java.util.Base64 pas vanaf API 26); regels van 76 tekens als wrap. */
    static String base64(byte[] d, boolean wrap) {
        StringBuilder b = new StringBuilder(d.length * 4 / 3 + d.length / 57 * 2 + 8);
        int lijn = 0;
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            b.append(B64[n >> 18 & 63]).append(B64[n >> 12 & 63]).append(i + 1 < d.length ? B64[n >> 6 & 63] : '=').append(i + 2 < d.length ? B64[n & 63] : '=');
            lijn += 4;
            if (wrap && lijn >= 76 && i + 3 < d.length) { b.append("\r\n"); lijn = 0; }
        }
        return b.toString();
    }

    /** Base64 met regels van 76 tekens rechtstreeks in een stream (geen grote String in het geheugen). */
    static void base64(byte[] d, java.io.OutputStream o) throws java.io.IOException {
        byte[] regel = new byte[78];
        for (int i = 0; i < d.length; i += 57) {
            int eind = Math.min(d.length, i + 57), p = 0;
            for (int j = i; j < eind; j += 3) {
                int n = (d[j] & 0xff) << 16 | (j + 1 < eind ? (d[j + 1] & 0xff) << 8 : 0) | (j + 2 < eind ? d[j + 2] & 0xff : 0);
                regel[p++] = (byte) B64[n >> 18 & 63]; regel[p++] = (byte) B64[n >> 12 & 63];
                regel[p++] = (byte) (j + 1 < eind ? B64[n >> 6 & 63] : '='); regel[p++] = (byte) (j + 2 < eind ? B64[n & 63] : '=');
            }
            regel[p++] = '\r'; regel[p++] = '\n';
            o.write(regel, 0, p);
        }
    }

    static boolean ascii(String s) { for (int i = 0; i < s.length(); i++) { char ch = s.charAt(i); if (ch < 32 || ch > 126) return false; } return true; }

    /** Koptekst met andere tekens dan ASCII: =?UTF-8?B?…?= (RFC 2047), in stukjes zodat regels kort blijven. */
    static String kop(String s) {
        if (ascii(s) && s.length() < 900) return s;
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int j = i, bytes = 0;
            while (j < s.length()) {
                int cp = s.codePointAt(j), len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                if (bytes + len > 42) break;
                bytes += len; j += Character.charCount(cp);
            }
            b.append(b.length() > 0 ? "\r\n " : "").append("=?UTF-8?B?").append(base64(s.substring(i, j).getBytes(StandardCharsets.UTF_8), false)).append("?=");
            i = j;
        }
        return b.toString();
    }

    /** Bestandsnaam voor filename*= (RFC 2231). */
    static String procent(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int ch = x & 0xff;
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || "-._~".indexOf(ch) >= 0) b.append((char) ch);
            else b.append('%').append(String.format(Locale.ROOT, "%02X", ch));
        }
        return b.toString();
    }

    static String geenRegels(String s) {
        if (s.matches("(?s).*[\\r\\n].*")) throw new IllegalArgumentException("regeleinde in een koptekst");
        return s;
    }

    /** Het hele bericht als RFC 5322-mail met bijlagen. metBcc: Bcc-kop (Gmail haalt die zelf weg; SMTP niet). */
    static byte[] mime(Bericht b, String messageId, long datum, boolean metBcc) {
        String grens = "=_tools_" + Long.toHexString(datum) + "_" + Integer.toHexString(Math.abs(b.onderwerp.hashCode()));
        StringBuilder h = new StringBuilder();
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        String naam = b.vanNaam.replace("\"", "");
        h.append("From: ").append(naam.isEmpty() ? "" : (ascii(naam) ? "\"" + naam + "\"" : kop(naam)) + " ").append("<").append(geenRegels(b.van)).append(">\r\n");
        if (!b.aan.isEmpty()) h.append("To: ").append(geenRegels(samen(b.aan))).append("\r\n");
        if (!b.cc.isEmpty()) h.append("Cc: ").append(geenRegels(samen(b.cc))).append("\r\n");
        if (metBcc && !b.bcc.isEmpty()) h.append("Bcc: ").append(geenRegels(samen(b.bcc))).append("\r\n");
        h.append("Subject: ").append(kop(geenRegels(b.onderwerp))).append("\r\n");
        h.append("Date: ").append(df.format(new java.util.Date(datum))).append("\r\n");
        h.append("Message-ID: <").append(geenRegels(messageId)).append(">\r\n");
        h.append("MIME-Version: 1.0\r\n");
        h.append("Content-Type: multipart/mixed; boundary=\"").append(grens).append("\"\r\n\r\n");
        h.append("--").append(grens).append("\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
        h.append(base64(b.tekst.replace("\r", "").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), true)).append("\r\n");
        long tot = h.length() + 1024;
        for (Bijlage x : b.bijlagen) tot += x.data.length * 4L / 3 + x.data.length / 57 * 2 + 512;
        ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE - 16, tot));
        try {
            o.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            for (Bijlage x : b.bijlagen) {
                String n = x.naam.replaceAll("[\\r\\n\"\\\\]", "_");
                String terug = ascii(n) ? n : n.replaceAll("[^\\x20-\\x7e]", "_");
                o.write(("--" + grens + "\r\nContent-Type: " + x.mime + "; name=\"" + terug + "\"\r\nContent-Disposition: attachment; filename=\"" + terug + "\""
                        + (ascii(n) ? "" : "; filename*=UTF-8''" + procent(n)) + "\r\nContent-Transfer-Encoding: base64\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                base64(x.data, o);
            }
            o.write(("--" + grens + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        return o.toByteArray();
    }

    static String messageId(String sleutel, String van) {
        String dom = van != null && van.contains("@") ? van.substring(van.indexOf('@') + 1) : "tools.local";
        return "tools-" + (sleutel == null || sleutel.isEmpty() ? Long.toHexString(System.nanoTime()) : sleutel.replaceAll("[^A-Za-z0-9-]", "")) + "@" + dom.replaceAll("[^A-Za-z0-9.-]", "");
    }

    // ================= verzenders =================

    /** Via je eigen Eigen Cloud (zelfde API als de webmail). */
    static final class EigenCloud implements Verzender {
        @Override public String naam() { return "Eigen Cloud"; }
        @Override public String klaar(Context c) {
            return Facturen.prefs(c).getString("ecUrl", "").isEmpty() || !Facturen.heeftGeheim(c, "ec") ? "Koppel eerst Eigen Cloud (Facturen → Koppelingen)" : "";
        }
        @Override public String van(Context c) {
            String a = Facturen.prefs(c).getString("ecAdres", "");
            if (a.isEmpty()) a = Facturen.prefs(c).getString("ecGebruiker", "");
            return a.contains("@") ? a : "";
        }
        @Override public void stuur(Context c, Bericht b, String sleutel) throws Exception {
            JSONArray bij = new JSONArray();
            for (Bijlage x : b.bijlagen) bij.put(new JSONObject().put("filename", x.naam).put("contentType", x.mime).put("base64", base64(x.data, false)));
            JSONObject mail = new JSONObject().put("to", new JSONArray(b.aan)).put("cc", new JSONArray(b.cc)).put("bcc", new JSONArray(b.bcc))
                    .put("subject", b.onderwerp).put("text", b.tekst).put("attachments", bij);
            try (Facturen.Ec ec = Facturen.Ec.open(c)) {
                JSONObject r = ec.vraag("POST", "/api/mail/send", mail, "tools-aanlever-" + sleutel);
                if (!r.optBoolean("ok", false)) throw new Exception("Eigen Cloud: niet verstuurd");
            }
        }
    }

    /** Via je eigen mailserver (SMTP, met SSL of STARTTLS). */
    static final class Smtp implements Verzender {
        @Override public String naam() { return "Eigen mailserver (SMTP)"; }
        @Override public String klaar(Context c) {
            android.content.SharedPreferences p = Facturen.prefs(c);
            if (p.getString("smtpHost", "").isEmpty() || p.getString("smtpVan", "").isEmpty()) return "Stel eerst je mailserver in (Facturen → Koppelingen → Mailserver)";
            return !p.getString("smtpGebruiker", "").isEmpty() && !Facturen.heeftGeheim(c, "smtp") ? "Vul het wachtwoord van je mailserver in (Facturen → Koppelingen → Mailserver)" : "";
        }
        @Override public String van(Context c) { return Facturen.prefs(c).getString("smtpVan", ""); }
        @Override public void stuur(Context c, Bericht b, String sleutel) throws Exception {
            android.content.SharedPreferences p = Facturen.prefs(c);
            if (!p.getString("smtpGebruiker", "").isEmpty() && Facturen.geheim(c, "smtp") == null) throw new Exception("Vul het wachtwoord van de mailserver in (Facturen → Koppelingen → Mailserver)");
            if (!p.getString("smtpNaam", "").isEmpty()) b.vanNaam = p.getString("smtpNaam", "");
            byte[] m = mime(b, messageId(sleutel, b.van), System.currentTimeMillis(), false);
            FacturenSmtp.stuur(p.getString("smtpHost", ""), p.getInt("smtpPoort", 587), p.getString("smtpBeveiliging", "starttls"),
                    p.getString("smtpGebruiker", ""), Facturen.geheim(c, "smtp"), b.van, b.alleOntvangers(), m);
        }
    }

    /** Via Gmail (Google-koppeling met het recht "facturen versturen", gmail.send). */
    static final class Gmail implements Verzender {
        @Override public String naam() { return "Gmail"; }
        @Override public String klaar(Context c) {
            if (!FacturenDrive.gekoppeld(c)) return "Koppel eerst Google (Facturen → Koppelingen)";
            return FacturenDrive.heeftRecht(c, "gmailsend") ? "" : "Geef Tools het recht om met Gmail te versturen (Facturen → Koppelingen → Google)";
        }
        @Override public String van(Context c) { return Facturen.prefs(c).getString("gdGebruiker", ""); }
        @Override public void stuur(Context c, Bericht b, String sleutel) throws Exception {
            FacturenDrive.gmailVerstuur(c, mime(b, messageId(sleutel, b.van), System.currentTimeMillis(), true));
        }
    }
}
