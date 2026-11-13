package nl.rene.tools;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * OPML voor Podcasts (abonnementen) en Nieuws (bronnen): maken uit wat er staat, en een gekozen bestand inlezen.
 * Het bestand komt van een plek die Rene kiest en gaat naar een plek die hij kiest; verder gaat er niets naar buiten,
 * behalve dat Nieuws elke nieuwe feed eerst leest (zoals bij Bron toevoegen). Logregels alleen met aantallen.
 */
final class OpmlWerk {

    private OpmlWerk() { }

    /** Nieuws leest elke feed eerst: daarom per bestand hooguit zoveel nieuwe bronnen. */
    static final int MAX_NIEUWS = 40;

    static boolean soortGoed(String soort) { return "podcasts".equals(soort) || "nieuws".equals(soort); }

    static String bestandsnaam(String soort) { return "nieuws".equals(soort) ? "nieuws.opml" : "podcasts.opml"; }

    /** Antwoord voor de interface: aantallen en hooguit een vaste foutmelding (nooit namen of adressen). */
    static String antwoord(String soort, boolean lezen, int nieuw, int al, int mislukt, String fout) {
        try {
            JSONObject o = new JSONObject().put("soort", soort == null ? "" : soort).put("actie", lezen ? "import" : "export").put("nieuw", nieuw).put("al", al).put("mislukt", mislukt);
            if (fout != null) o.put("geannuleerd".equals(fout) ? "geannuleerd" : "error", "geannuleerd".equals(fout) ? (Object) Boolean.TRUE : fout);
            return o.toString();
        } catch (Exception e) { return "{\"error\":\"\"}"; }
    }

    static List<Opml.Regel> regels(Context c, String soort) throws Exception {
        List<Opml.Regel> uit = new ArrayList<>();
        if ("podcasts".equals(soort)) {
            JSONArray a = Podcasts.subs(c);
            for (int i = 0; i < a.length(); i++) { JSONObject p = a.optJSONObject(i); if (p != null) uit.add(new Opml.Regel(p.optString("title"), p.optString("feed"))); }
        } else {
            JSONArray a = Nieuws.bronnen(Nieuws.dir(c));
            for (int i = 0; i < a.length(); i++) { JSONObject b = a.optJSONObject(i); if (b != null && b.optBoolean("aan")) uit.add(new Opml.Regel(b.optString("naam"), b.optString("url"))); }
        }
        return uit;
    }

    static byte[] maak(Context c, String soort) throws Exception {
        List<Opml.Regel> r = regels(c, soort);
        if (r.isEmpty()) throw new Exception(Taal.T("Er is nog niets om te exporteren"));
        return Opml.schrijf("nieuws".equals(soort) ? "Nieuws uit Tools" : "Podcasts uit Tools", r).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static String bewaar(Context c, String soort, Uri u, byte[] xml) throws Exception {
        try (OutputStream out = c.getContentResolver().openOutputStream(u, "wt")) {
            if (out == null) throw new Exception(Taal.T("Opslaan lukt niet"));
            out.write(xml); out.flush();
        }
        int n = regels(c, soort).size();
        App.log(c, "OPML", "export " + soort + ": " + n);
        return antwoord(soort, false, n, 0, 0, null);
    }

    static String lees(Context c, String soort, Uri u) throws Exception {
        byte[] xml;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception(Taal.T("Het bestand is niet te lezen"));
            try { xml = Updater.readAll(new PodcastFeed.Limited(in, Opml.MAX_BYTES + 1L)); }
            catch (java.io.IOException e) { throw new Exception(Taal.T("Het bestand is te groot")); }
        }
        List<Opml.Regel> regels;
        try { regels = Opml.lees(xml); } catch (Exception e) { throw new Exception(vertaalbaar(e.getMessage())); }
        int nieuw = 0, al = 0, mislukt = 0;
        if ("podcasts".equals(soort)) {
            for (Opml.Regel r : regels) {
                try {
                    if (Podcasts.isSub(c, Podcasts.podId(r.url))) { al++; continue; }
                    Podcasts.subscribe(c, new JSONObject().put("feed", r.url).put("title", r.titel.isEmpty() ? r.url : r.titel));
                    nieuw++;
                } catch (Throwable e) { mislukt++; }
            }
        } else {
            int gedaan = 0;
            for (Opml.Regel r : regels) {
                if (gedaan >= MAX_NIEUWS) { mislukt++; continue; }
                gedaan++;
                try {
                    JSONObject b = Nieuws.toevoegen(c, r.url);
                    if (b.optBoolean("al")) al++; else nieuw++;
                } catch (Throwable e) { mislukt++; }
            }
        }
        App.log(c, "OPML", "import " + soort + ": " + nieuw + " nieuw, " + al + " al, " + mislukt + " mislukt");
        return antwoord(soort, true, nieuw, al, mislukt, null);
    }

    /** De vaste meldingen van Opml, gemarkeerd voor de vertaling. */
    static String vertaalbaar(String m) {
        if ("Het bestand is leeg".equals(m)) return Taal.T("Het bestand is leeg");
        if ("Het bestand is te groot".equals(m)) return Taal.T("Het bestand is te groot");
        if ("In dit bestand staan geen feeds".equals(m)) return Taal.T("In dit bestand staan geen feeds");
        return Taal.T("Dit is geen OPML-bestand");
    }
}
