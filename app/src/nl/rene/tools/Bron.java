package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Een bestand uit de app zelf in plaats van de bestandskiezer van Android: uit de documentenkluis, Gemaakt (PDF-tools) of het
 * facturendossier (alleen facturen waarvan de app zelf een kopie heeft).
 *
 * Werking: de interface zet met bronVolgende(...) klaar welk bestand het wordt en roept daarna de gewone kiesfunctie van de
 * tool aan (bijv. pdfPickPdf). MainActivity.startActivityForResult vangt die kiezer af (alleen voor de kiezers in
 * MainActivity.bronMag) en geeft de tool het klaargezette bestand alsof het met de kiezer gekozen was: elke tool verwerkt het
 * dus precies zo als een bestand van de telefoon. Een klaargezet bestand geldt hooguit GELDIG_MS en voor één kiezer.
 *
 * Het bestand komt als kopie in cache/bron/&lt;uuid&gt;/ (een kluisdocument ontsleuteld) en is te lezen via PdfProvider (pad
 * b/&lt;uuid&gt;/&lt;naam&gt;, alleen binnen de app); na BEWAAR_MS en bij het starten van de app gaat het weg.
 */
final class Bron {
    static final String DIR = "bron";
    static final long GELDIG_MS = 30_000, BEWAAR_MS = 120_000;
    static final int MAX = 20;

    static final class Item {
        final String bron, id; final int versie;
        Item(String bron, String id, int versie) { this.bron = bron; this.id = id; this.versie = versie; }
    }
    static final class Wacht {
        final List<Item> items; final long tot; final boolean kluisOk;
        Wacht(List<Item> items, long tot, boolean kluisOk) { this.items = items; this.tot = tot; this.kluisOk = kluisOk; }
    }
    private static Wacht wacht;

    static synchronized void zet(List<Item> items, long nu, boolean kluisOk) { wacht = new Wacht(items, nu + GELDIG_MS, kluisOk); }
    /** Het klaargezette bestand, één keer (daarna is het weg), of null als er niets (meer) klaarstaat. */
    static synchronized Wacht neem(long nu) { Wacht w = wacht; wacht = null; return w != null && nu <= w.tot ? w : null; }

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

    /** Soort bestand bij de naam (de app kent alleen de extensie). */
    static String mime(String naam) {
        String n = naam == null ? "" : naam.toLowerCase(Locale.ROOT);
        int p = n.lastIndexOf('.');
        String e = p < 0 ? "" : n.substring(p + 1);
        switch (e) {
            case "pdf": return "application/pdf";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "bmp": return "image/bmp";
            case "epub": return "application/epub+zip";
            case "txt": return "text/plain";
            case "csv": return "text/csv";
            case "htm": case "html": return "text/html";
            case "eml": return "message/rfc822";
            case "xml": return "text/xml";
            default: return "application/octet-stream";
        }
    }

    /** Past een bestand bij wat de kiezer vroeg (type, eventueel met een lijst soorten)? */
    static boolean past(String naam, String type, String[] mimes) {
        String m = mime(naam);
        if (mimes != null && mimes.length > 0) { for (String s : mimes) if (s != null && eenSoort(m, s)) return true; return false; }
        return type == null || type.isEmpty() || eenSoort(m, type);
    }
    private static boolean eenSoort(String m, String vraag) {
        String v = vraag.toLowerCase(Locale.ROOT);
        if ("*/*".equals(v)) return true;
        if (v.endsWith("/*")) return m.startsWith(v.substring(0, v.length() - 1));
        return m.equals(v) || "application/octet-stream".equals(v);
    }

    /** Een veilige bestandsnaam (geen mappen, geen stuurtekens), met de extensie erbij als die ontbreekt. */
    static String naam(String n, String ext) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) s = "document";
        if (s.length() > 120) { int p = s.lastIndexOf('.'); String e = p > 0 && s.length() - p <= 8 ? s.substring(p) : ""; s = s.substring(0, 120 - e.length()) + e; }
        if (ext != null && !ext.isEmpty() && !s.toLowerCase(Locale.ROOT).endsWith(ext)) s += ext;
        return s;
    }

    static File dir(Context c) { return new File(c.getCacheDir(), DIR); }

    /** Het bestand klaarzetten in cache/bron/&lt;uuid&gt;/. */
    static File maak(Context c, Item it, boolean kluisOk) throws Exception {
        File map = new File(dir(c), java.util.UUID.randomUUID().toString());
        if (!map.mkdirs()) throw new Exception(Taal.T("Openen lukt niet"));
        File f;
        if ("kluis".equals(it.bron)) {
            if (!kluisOk) throw new Exception(Taal.T("De kluis is op slot"));
            JSONObject d = Kluis.find(c, Kluis.safeId(it.id));
            if (d == null) throw new Exception(Taal.T("Document niet gevonden"));
            f = new File(map, naam(Kluis.fileName(d), ""));
            Kluis.decryptTo(c, it.id, it.versie, f);
        } else if ("gemaakt".equals(it.bron)) {
            File src = PdfTools.output(c, it.id);
            f = new File(map, naam(src.getName(), ".pdf"));
            kopie(src, f);
        } else {
            File src = FacturenDossier.appBestand(c, it.id);
            f = new File(map, naam(src.getName(), ".pdf"));
            kopie(src, f);
        }
        return f;
    }

    private static void kopie(File van, File naar) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(van); java.io.OutputStream o = new java.io.FileOutputStream(naar)) {
            byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
        } catch (Exception e) { naar.delete(); throw e; }
    }

    /** Klaargezette bestanden weghalen die ouder zijn dan ouderDanMs (0 = alles). */
    static void opruimen(Context c, long ouderDanMs) {
        File[] mappen = dir(c).listFiles();
        if (mappen == null) return;
        long grens = System.currentTimeMillis() - ouderDanMs;
        for (File m : mappen) {
            if (ouderDanMs > 0 && m.lastModified() > grens) continue;
            File[] fs = m.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            m.delete();
        }
    }
}
