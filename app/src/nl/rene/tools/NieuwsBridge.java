package nl.rene.tools;

import android.content.Intent;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Nieuws (scherm s-nieuws): de brug naar {@link Nieuws}. Bijwerken en een bron toevoegen gaan over het netwerk en lopen
 * op een eigen thread; het antwoord komt via onNieuws en onNieuwsBron.
 */
public class NieuwsBridge extends EreaderBridge {
    NieuwsBridge(MainActivity a) { super(a); }

    private String fout(Throwable e, String standaard) {
        String m = e instanceof Nieuws.Fout || e instanceof NieuwsFeed.GeenFeed ? e.getMessage() : standaard;
        return Taal.tx(ctx, m == null ? standaard : m);
    }

    private String foutJson(Throwable e, String standaard) { return "{\"error\":" + JSONObject.quote(fout(e, standaard)) + "}"; }

    @JavascriptInterface public String nieuwsLijst() {
        try {
            JSONObject o = Nieuws.lijst(Nieuws.dir(ctx), Nieuws.cache(ctx));
            JSONArray bs = o.getJSONArray("bronnen");
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                if (b.has("fout")) b.put("fout", Taal.tx(ctx, b.getString("fout")));
            }
            return o.toString();
        } catch (Throwable e) { return foutJson(e, Taal.T("Nieuws lezen lukt niet")); }
    }

    @JavascriptInterface public String nieuwsArtikel(String id) {
        try { return Nieuws.artikel(Nieuws.dir(ctx), Nieuws.cache(ctx), id).toString(); }
        catch (Throwable e) { return foutJson(e, Taal.T("Dit artikel is niet meer beschikbaar")); }
    }

    /** Bijwerken: alles = ook bronnen die net bijgewerkt zijn. Antwoord via onNieuws({ok, totaal, nieuw} of {bezig} of {error}). */
    @JavascriptInterface public void nieuwsVerversen(boolean alles) {
        new Thread(() -> {
            String r;
            try { r = Nieuws.ververs(ctx, alles).toString(); }
            catch (Throwable e) { r = foutJson(e, Taal.T("Bijwerken lukt niet")); }
            a.js("onNieuws", r);
        }, "nieuws-bijwerken").start();
    }

    /** Een eigen bron (adres van een feed of van een nieuwssite). Antwoord via onNieuwsBron({bron} of {error}). */
    @JavascriptInterface public void nieuwsToevoegen(String adres) {
        new Thread(() -> {
            String r;
            try { r = new JSONObject().put("bron", Nieuws.toevoegen(ctx, adres)).toString(); }
            catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(Taal.tx(ctx, Nieuws.kort(e))) + "}"; }
            a.js("onNieuwsBron", r);
        }, "nieuws-bron").start();
    }

    @JavascriptInterface public String nieuwsBron(String id, boolean aan) {
        try { Nieuws.zetAan(Nieuws.dir(ctx), id, aan); return ""; } catch (Throwable e) { return fout(e, Taal.T("Opslaan lukt niet")); }
    }

    @JavascriptInterface public String nieuwsVerwijder(String id) {
        try { Nieuws.verwijder(Nieuws.dir(ctx), Nieuws.cache(ctx), id); return ""; } catch (Throwable e) { return fout(e, Taal.T("Opslaan lukt niet")); }
    }

    /** ids: JSON-lijst met artikel-id's. */
    @JavascriptInterface public String nieuwsGelezen(String ids, boolean aan) {
        try { Nieuws.gelezen(Nieuws.dir(ctx), new JSONArray(ids), aan, System.currentTimeMillis()); return ""; }
        catch (Throwable e) { return fout(e, Taal.T("Opslaan lukt niet")); }
    }

    @JavascriptInterface public String nieuwsBewaar(String id, boolean aan) {
        try { Nieuws.bewaar(Nieuws.dir(ctx), Nieuws.cache(ctx), id, aan, System.currentTimeMillis()); return ""; }
        catch (Throwable e) { return fout(e, Taal.T("Opslaan lukt niet")); }
    }

    /** Delen: titel en adres van het artikel (zonder volgparameters), via de gewone Android-keuze. */
    @JavascriptInterface public void nieuwsDeel(String titel, String link) {
        if (link == null || !Podcasts.httpUrl(link)) return;
        String tekst = (titel == null || titel.trim().isEmpty() ? "" : titel.trim() + "\n") + Nieuws.schoon(link);
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tekst);
        a.h.post(() -> { try { a.startActivity(Intent.createChooser(s, Taal.t(ctx, "Delen"))); } catch (Throwable ignored) { } });
    }
}
