package nl.rene.tools;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Facturen uit je mail: een leverancier van de soort "mail" zoekt PDF-facturen in een mailbox in plaats van op een website.
 * Een mailbron is een uitwisselbaar onderdeel (Bron): de eerste is Gmail (FacturenGmail, via de Google-koppeling).
 * Een nieuwe bron (bijv. Outlook via Microsoft Graph) = een klasse die Bron doet, toevoegen in BRONNEN en bron(),
 * met een eigen koppeling (zoals FacturenDrive voor Google). De rest (planning, opslaan, overzicht) blijft gelijk.
 */
final class FacturenMail {
    private FacturenMail() { }

    /** Bekende mailbronnen (id's): ook gebruikt om instellingen te controleren. */
    static final String[] BRONNEN = {"gmail"};

    interface Voortgang { void meld(String tekst); }

    /** Eén mailbron: zoekt mails volgens de instellingen en geeft de PDF-bijlagen terug. */
    interface Bron {
        /** De koppeling die nodig is (bijv. "google"), voor een duidelijke melding als die ontbreekt. */
        String koppeling();
        List<Facturen.Pdf> haal(Context c, JSONObject lev, long sinds, AtomicBoolean stop, Voortgang v) throws Exception;
    }

    static Bron bron(String id) throws Exception {
        if ("gmail".equals(id)) return new FacturenGmail();
        throw new Exception("Onbekende mailbron: " + id);
    }

    /** Instellingen van het zoeken controleren en aanvullen. */
    static JSONObject normaal(JSONObject in) throws Exception {
        if (in == null) in = new JSONObject();
        JSONObject o = new JSONObject();
        String bron = in.optString("bron", "gmail");
        if (!Facturen.in(bron, BRONNEN)) throw new Exception("Onbekende mailbron");
        o.put("bron", bron);
        o.put("woorden", lijst(in.optString("woorden", "factuur, invoice, rekening, nota"), 20, 40, "zoekwoord"));
        o.put("afzenders", lijst(in.optString("afzenders", ""), 30, 120, "afzender"));
        o.put("negeer", lijst(in.optString("negeer", ""), 30, 120, "afzender om over te slaan"));
        String extra = in.optString("extra", "").trim();
        if (extra.length() > 300) throw new Exception("De extra zoekopdracht is te lang");
        o.put("extra", extra);
        o.put("dagen", Math.max(1, Math.min(730, in.optInt("dagen", 90))));
        o.put("max", Math.max(1, Math.min(200, in.optInt("max", 50))));
        o.put("perAfzender", in.optBoolean("perAfzender", true));
        return o;
    }

    /** "a, b; c" → "a, b, c" (zonder lege, zonder tekens die de zoekopdracht breken). */
    static String lijst(String s, int maxAantal, int maxLang, String wat) throws Exception {
        List<String> uit = new ArrayList<>();
        for (String d : (s == null ? "" : s).split("[,;\\n]")) {
            String t = d.replaceAll("[\"(){}]", " ").replaceAll("\\s+", " ").trim();
            if (t.isEmpty()) continue;
            if (t.length() > maxLang) throw new Exception("Te lang: " + wat + " " + t.substring(0, 20) + "…");
            if (!uit.contains(t)) uit.add(t);
        }
        if (uit.size() > maxAantal) throw new Exception("Hooguit " + maxAantal + " keer " + wat);
        StringBuilder b = new StringBuilder(); // geen String.join: dat is er pas vanaf Android 8
        for (String t : uit) b.append(b.length() > 0 ? ", " : "").append(t);
        return b.toString();
    }

    static List<String> delen(String s) {
        List<String> uit = new ArrayList<>();
        for (String d : (s == null ? "" : s).split(",")) if (!d.trim().isEmpty()) uit.add(d.trim());
        return uit;
    }

    /** Vanaf wanneer zoeken: sinds de vorige gelukte keer (2 dagen overlap), de eerste keer "dagen" terug. */
    static long sinds(JSONObject mail, long lastOk, long nu) {
        if (lastOk > 0) return lastOk - 2 * 86_400_000L;
        return nu - mail.optInt("dagen", 90) * 86_400_000L;
    }

    /** "KPN Zakelijk <noreply@kpn.com>" → "KPN Zakelijk"; zonder naam het domein ("kpn.com"). */
    static String afzenderNaam(String van) {
        if (van == null) return "Onbekend";
        String v = van.trim();
        int lt = v.indexOf('<');
        String naam = lt > 0 ? v.substring(0, lt).trim().replaceAll("^\"|\"$", "").trim() : "";
        if (!naam.isEmpty() && !naam.contains("@")) return Facturen.veiligeNaam(naam).replace('.', ' ').trim();
        String adres = lt >= 0 ? v.substring(lt + 1).replace(">", "").trim() : v;
        int at = adres.lastIndexOf('@');
        String dom = at >= 0 ? adres.substring(at + 1) : adres;
        dom = dom.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "");
        return dom.isEmpty() ? "Onbekend" : dom;
    }
}
