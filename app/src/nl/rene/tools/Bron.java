package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Een bestand uit de app zelf in plaats van de bestandskiezer van Android: uit de documentenkluis, Gemaakt (PDF-tools) of het
 * facturendossier (alleen facturen waarvan de app zelf een kopie heeft).
 *
 * Werking: de interface zet met bronVolgende(json, kiezer) klaar welk bestand het wordt en voor welke kiezer, en roept daarna
 * de gewone kiesfunctie van de tool aan (bijv. pdfPickPdf). MainActivity.startActivityForResult vangt precies die kiezer af en
 * geeft de tool een content-uri (PdfProvider, pad b/&lt;token&gt;/&lt;naam&gt;) alsof het bestand gekozen was: elke tool verwerkt het
 * dus zoals een bestand van de telefoon. Klaargezet geldt per venster, voor één kiezer en hooguit GELDIG_MS.
 *
 * Er komt nergens een leesbare kopie: een gemaakte PDF of factuur leest de provider rechtstreeks (alleen lezen), een
 * kluisdocument ontsleutelt hij pas bij het openen, helemaal (een fout = geen bestand), en het tijdelijke pad wist hij meteen. Een token is onraadbaar, geldt BEWAAR_MS en een
 * kluistoken vervalt ook als de app uit beeld gaat (vergeetKluis).
 */
final class Bron {
    static final long GELDIG_MS = 30_000, BEWAAR_MS = 120_000;
    static final int MAX = 20;

    static final class Item {
        final String bron, id; final int versie;
        Item(String bron, String id, int versie) { this.bron = bron; this.id = id; this.versie = versie; }
    }
    /** Een bestand dat de provider mag geven: een gewoon bestand (Gemaakt, factuur) of een kluisdocument (id + versie). */
    static final class Bestand {
        final String token, bron, id, naam, mime; final int versie; final long grootte, tot; final File bestand;
        Bestand(String token, String bron, String id, int versie, String naam, String mime, long grootte, File bestand, long tot) {
            this.token = token; this.bron = bron; this.id = id; this.versie = versie; this.naam = naam; this.mime = mime; this.grootte = grootte; this.bestand = bestand; this.tot = tot;
        }
    }
    /** Wat de volgende kiezer (req) van dit venster krijgt. */
    static final class Wacht {
        final List<Bestand> bestanden; final int req; final long tot;
        Wacht(List<Bestand> bestanden, int req, long tot) { this.bestanden = bestanden; this.req = req; this.tot = tot; }
    }

    private static final Map<String, Bestand> REG = new HashMap<>();

    /** [{bron: kluis|gemaakt|factuur, id, versie}] controleren (zonder Android). */
    static List<Item> lees(String json) throws Exception {
        JSONArray a = new JSONArray(json == null ? "[]" : json);
        if (a.length() == 0 || a.length() > MAX) throw new Exception(Taal.T("Kies een bestand"));
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String bron = o.optString("bron"), id = o.optString("id");
            int versie = o.optInt("versie", 0);
            if (versie < 0 || versie > 9999) throw new Exception(Taal.T("Onbekend bestand"));
            if ("kluis".equals(bron)) { if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new Exception(Taal.T("Onbekend bestand")); }
            else if ("gemaakt".equals(bron)) { if (id.isEmpty() || id.length() > 200 || id.contains("/") || id.contains("\\") || id.startsWith(".")) throw new Exception(Taal.T("Onbekend bestand")); }
            else if ("factuur".equals(bron)) { if (!id.matches("[0-9a-f]{64}")) throw new Exception(Taal.T("Onbekend bestand")); }
            else throw new Exception(Taal.T("Onbekend bestand"));
            out.add(new Item(bron, id, versie));
        }
        return out;
    }

    /** Soort bestand bij de naam (als de kluis het niet weet). */
    static String mime(String naam) {
        String n = naam == null ? "" : naam.toLowerCase(Locale.ROOT);
        int p = n.lastIndexOf('.');
        String e = p < 0 ? "" : n.substring(p + 1);
        switch (e) {
            case "pdf": return "application/pdf";
            case "jpg": case "jpeg": case "jfif": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "avif": return "image/avif";
            case "bmp": return "image/bmp";
            case "tif": case "tiff": return "image/tiff";
            case "epub": return "application/epub+zip";
            case "txt": return "text/plain";
            case "csv": return "text/csv";
            case "htm": case "html": return "text/html";
            case "eml": return "message/rfc822";
            case "xml": return "text/xml";
            default: return "application/octet-stream";
        }
    }

    /** Past een bestand (mime) bij wat de kiezer vroeg (type, eventueel met een lijst soorten)? */
    static boolean past(String mime, String type, String[] mimes) {
        String m = mime == null || mime.isEmpty() ? "application/octet-stream" : mime.toLowerCase(Locale.ROOT);
        if (mimes != null && mimes.length > 0) { for (String s : mimes) if (s != null && eenSoort(m, s)) return true; return false; }
        return type == null || type.isEmpty() || eenSoort(m, type);
    }
    private static boolean eenSoort(String m, String vraag) {
        String v = vraag.toLowerCase(Locale.ROOT);
        if ("*/*".equals(v)) return true;
        if (v.endsWith("/*")) return m.startsWith(v.substring(0, v.length() - 1));
        return m.equals(v);
    }

    /** Een veilige bestandsnaam (geen mappen, geen stuurtekens). */
    static String naam(String n) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) s = "document";
        if (s.length() > 120) { int p = s.lastIndexOf('.'); String e = p > 0 && s.length() - p <= 8 ? s.substring(p) : ""; s = s.substring(0, 120 - e.length()) + e; }
        return s;
    }

    static String token() { return java.util.UUID.randomUUID().toString().replace("-", ""); }

    /** Een bestand inschrijven voor de provider (nu, terwijl de kluis open is); geeft wat de provider later geeft. */
    static Bestand inschrijven(Context c, Item it, long nu) throws Exception {
        String bron = it.bron, naam, mime; long grootte = -1; File f = null;
        if ("kluis".equals(bron)) {
            JSONObject d = Kluis.find(c, Kluis.safeId(it.id));
            if (d == null) throw new Exception(Taal.T("Document niet gevonden"));
            Kluis.blobFile(c, it.id, it.versie); // bestaat de versie?
            naam = naam(Kluis.fileName(d));
            mime = d.optString("mime", "");
            if (mime.isEmpty() || "application/octet-stream".equals(mime)) mime = mime(naam);
            if (it.versie == 0) grootte = d.optLong("size", -1);
        } else {
            f = "gemaakt".equals(bron) ? PdfTools.output(c, it.id) : FacturenDossier.appBestand(c, it.id);
            naam = naam(f.getName()); mime = mime(naam); grootte = f.length();
        }
        Bestand b = new Bestand(token(), bron, it.id, it.versie, naam, mime, grootte, f, nu + BEWAAR_MS);
        synchronized (REG) { opruimen(nu); REG.put(b.token, b); }
        return b;
    }

    /** Het bestand achter een token (en precies die naam), zolang het geldt. */
    static Bestand zoek(String token, String naam, long nu) {
        synchronized (REG) {
            Bestand b = token == null ? null : REG.get(token);
            if (b == null || nu > b.tot || !b.naam.equals(naam)) return null;
            return b;
        }
    }
    static void opruimen(long nu) { synchronized (REG) { for (Iterator<Bestand> i = REG.values().iterator(); i.hasNext(); ) if (nu > i.next().tot) i.remove(); } }
    /** De app gaat uit beeld (of de kluis op slot): geen kluisdocument meer te lezen. */
    static void vergeetKluis() { synchronized (REG) { for (Iterator<Bestand> i = REG.values().iterator(); i.hasNext(); ) if ("kluis".equals(i.next().bron)) i.remove(); } }
    static void vergeet(List<Bestand> l) { if (l == null) return; synchronized (REG) { for (Bestand b : l) REG.remove(b.token); } }
}
