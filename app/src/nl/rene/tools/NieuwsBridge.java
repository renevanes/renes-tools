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

    /**
     * Bijwerken: alles = ook bronnen die net bijgewerkt zijn; beelden = Rene wil afbeeldingen zien (anders geen artikelpagina's
     * ophalen). Antwoord via onNieuws({ok, totaal, nieuw} of {bezig} of {error}).
     */
    @JavascriptInterface public void nieuwsVerversen(boolean alles, boolean beelden) {
        new Thread(() -> {
            String r;
            try { r = Nieuws.ververs(ctx, alles, beelden).toString(); }
            catch (Throwable e) { r = foutJson(e, Taal.T("Bijwerken lukt niet")); }
            a.js("onNieuws", r);
        }, "nieuws-bijwerken").start();
    }

    /** Een eigen bron (adres van een feed of van een nieuwssite). Antwoord via onNieuwsBron({bron} of {error}). */
    @JavascriptInterface public void nieuwsToevoegen(String adres) {
        new Thread(() -> {
            String r;
            try { r = new JSONObject().put("bron", Nieuws.toevoegen(ctx, adres)).toString(); }
            catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(Taal.tx(ctx, Nieuws.kort(e, Taal.T("Toevoegen lukt niet")))) + "}"; }
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

    /** De cookiekeuze voor een bron die afbeeldingen van de artikelpagina haalt (Tweakers): de echte pagina, in een eigen venster. */
    @JavascriptInterface public String nieuwsCookies(String id) {
        if (id == null || Nieuws.paginaBron(id) == null) return Taal.tx(ctx, "Bijwerken lukt niet");
        Intent i = new Intent(ctx, NieuwsCookiesActivity.class).putExtra("bron", id);
        a.h.post(() -> { try { a.startActivity(i); } catch (Throwable ignored) { } });
        return "";
    }

    /**
     * De instellingen van een bron aanpassen (json met alleen wat verandert; "standaard": true = terug naar de vaste
     * waarden). Een ander feedadres wordt eerst gelezen (met de cookies van de inlog als die aan staat). Antwoord via
     * onNieuwsInstellen({bron} of {error}).
     */
    @JavascriptInterface public void nieuwsInstellen(String id, String json) {
        new Thread(() -> {
            String r;
            try {
                JSONObject in = new JSONObject(json == null ? "{}" : json);
                String feed = null;
                if (in.has("url")) {
                    String u = Nieuws.adres(in.optString("url"));
                    if (Nieuws.andereFeed(Nieuws.dir(ctx), id, u)) {
                        JSONObject b = bron(id);
                        boolean inlog = in.has("inlog") ? in.optBoolean("inlog") : b.optBoolean("inlog");
                        Nieuws.Gelezen g = Nieuws.lees(u, System.currentTimeMillis() + Nieuws.MAX_HAAL,
                                inlog ? Nieuws.inlogKoekjes(b.put("inlog", true)) : null, inlog ? Nieuws.paginaUa(Nieuws.webUa(ctx)) : Nieuws.UA);
                        if (g.feed.artikelen.isEmpty()) throw new Nieuws.Fout(Taal.T("Deze feed heeft geen artikelen"));
                        feed = g.url;
                    }
                }
                r = new JSONObject().put("bron", Nieuws.instellen(Nieuws.dir(ctx), Nieuws.cache(ctx), id, in, feed)).toString();
                App.log(ctx, "NIEUWS", "bron ingesteld");
            } catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(Taal.tx(ctx, Nieuws.kort(e, Taal.T("Opslaan lukt niet")))) + "}"; }
            a.js("onNieuwsInstellen", r);
        }, "nieuws-instellen").start();
    }

    private JSONObject bron(String id) throws Exception {
        JSONObject b = null;
        synchronized (Nieuws.LOCK) {
            JSONArray bs = Nieuws.bronnen(Nieuws.dir(ctx));
            for (int i = 0; i < bs.length(); i++) if (bs.getJSONObject(i).optString("id").equals(id)) b = bs.getJSONObject(i);
        }
        if (b == null) throw new Nieuws.Fout(Taal.T("Deze bron bestaat niet meer"));
        return b;
    }

    /** Inloggen bij de website van een bron: de echte site, in een eigen venster (alleen de sites van die bron). */
    @JavascriptInterface public String nieuwsInloggen(String id) {
        try {
            JSONObject b = bron(id);
            if (!b.optBoolean("inlog")) return Taal.tx(ctx, "Zet eerst Inloggen bij de website aan");
            open(id, NieuwsInlogActivity.INLOGGEN, b.getString("inlogStart"));
            return "";
        } catch (Throwable e) { return fout(e, Taal.T("Openen lukt niet")); }
    }

    /**
     * Een artikel lezen in het venster van de bron, met de inlog. "extern" = dat kan niet (inloggen staat uit, lezen gaat
     * in de browser, of de link is van een andere site): dan opent de interface de browser.
     */
    @JavascriptInterface public String nieuwsLees(String id, String link) {
        try {
            JSONObject b = bron(id);
            if (!b.optBoolean("inlog") || !"app".equals(b.optString("lezen")) || !Nieuws.siteMag(b.optString("inlogSites"), link)) return "extern";
            open(id, NieuwsInlogActivity.LEZEN, link);
            return "";
        } catch (Throwable e) { return "extern"; }
    }

    private void open(String id, String soort, String url) {
        Intent i = new Intent(ctx, NieuwsInlogActivity.class).putExtra("bron", id).putExtra("soort", soort).putExtra("url", url);
        a.h.post(() -> { try { a.startActivity(i); } catch (Throwable ignored) { } });
    }

    /** Uitloggen: de cookies en websitegegevens van de sites van de inlog weg. */
    @JavascriptInterface public String nieuwsUitloggen(String id) {
        try {
            JSONObject b = bron(id);
            NieuwsInlogActivity.wis(Nieuws.uitlogAdressen(b.optString("inlogSites"), b.optString("inlogStart")));
            App.log(ctx, "NIEUWS", "uitgelogd");
            return "";
        } catch (Throwable e) { return fout(e, Taal.T("Uitloggen lukt niet")); }
    }

    /** Heeft de site cookies bewaard (dan ben je waarschijnlijk ingelogd)? {koekjes: aantal namen} */
    @JavascriptInterface public String nieuwsInlogStand(String id) {
        try {
            JSONObject b = bron(id);
            int n = 0;
            for (String u : Nieuws.uitlogAdressen(b.optString("inlogSites"), b.optString("inlogStart"))) {
                String k = Nieuws.koekjesNamen(android.webkit.CookieManager.getInstance().getCookie(u));
                if (!k.isEmpty()) n = Math.max(n, k.split(";").length);
            }
            return new JSONObject().put("koekjes", n).toString();
        } catch (Throwable e) { return "{\"koekjes\":0}"; }
    }

    /** Delen: titel en adres van het artikel (zonder volgparameters), via de gewone Android-keuze. */
    @JavascriptInterface public void nieuwsDeel(String titel, String link) {
        if (link == null || !Podcasts.httpUrl(link)) return;
        String tekst = (titel == null || titel.trim().isEmpty() ? "" : titel.trim() + "\n") + Nieuws.schoon(link);
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tekst);
        a.h.post(() -> { try { a.startActivity(Intent.createChooser(s, Taal.t(ctx, "Delen"))); } catch (Throwable ignored) { } });
    }
}
