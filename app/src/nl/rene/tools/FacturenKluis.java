package nl.rene.tools;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;

/**
 * Factuur-PDF's in de documentenkluis (samenvoegen van kluis en facturendossier, issue #32): één opslag, versleuteld.
 * Het dossier (FacturenDossier) houdt de administratie en verwijst met plekken.app.kluis naar het kluisdocument.
 * De kluissleutel is niet aan ontgrendelen gekoppeld, dus ophalen, afleveren en aanleveren werken ook op de achtergrond;
 * een leesbare kopie bestaat alleen kort in de cache (tijdelijk) en de aanroeper wist hem.
 */
final class FacturenKluis {
    private FacturenKluis() { }
    static final String CATEGORIE = "Facturen";

    /** Herkomst en koppeling voor een factuur (zonder Android te testen). */
    static JSONObject extra(String sha, String leverancier, String datum) throws Exception {
        JSONObject link = new JSONObject().put("kind", "factuur").put("ref", sha);
        if (leverancier != null && !leverancier.isEmpty()) link.put("leverancier", leverancier);
        if (datum != null && datum.matches("\\d{4}-\\d{2}-\\d{2}")) link.put("datum", datum);
        return new JSONObject().put("source", "factuur").put("link", link);
    }

    /** Een PDF versleuteld in de kluis zetten; geeft het kluis-id. */
    static String bewaar(Context c, File pdf, String naam, String sha, String leverancier, String datum) throws Exception {
        return Kluis.addFile(c, pdf, naam, "application/pdf", CATEGORIE, extra(sha, leverancier, datum)).getString("id");
    }

    /** Een leesbare kopie in de cache (de aanroeper wist hem na gebruik); een fout = geen bestand, nooit iets halfs. */
    static File tijdelijk(Context c, String kluisId) throws Exception {
        File f = new File(c.getCacheDir(), "factuur-" + Bron.token() + ".tmp");
        Kluis.decryptTo(c, kluisId, 0, f);
        return f;
    }

    /** Naar een andere leverancier (alleen de koppeling; het document blijft hetzelfde). */
    static void verplaats(Context c, String kluisId, String sha, String leverancier, String datum) throws Exception {
        Kluis.setMeta(c, kluisId, extra(sha, leverancier, datum));
    }

    static void weg(Context c, String kluisId) throws Exception { Kluis.delete(c, kluisId); }
}
