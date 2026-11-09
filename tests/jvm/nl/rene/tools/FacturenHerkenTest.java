package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

/** Facturen uit de mail: welke leverancier is het (afzender, platforms, doorgestuurd, btw/KvK/IBAN uit de PDF)? */
public class FacturenHerkenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }
    static String fout(JSONObject o) { try { FacturenHerken.herkenNormaal(o); return ""; } catch (Exception e) { return e.getMessage(); } }
    static String rep(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }
    static String eigenFout(JSONObject o) { try { FacturenHerken.eigenNormaal(o); return ""; } catch (Exception e) { return e.getMessage(); } }
    static JSONObject bron(String van) throws Exception { return new JSONObject().put("afzender", van).put("onderwerp", "Uw factuur"); }
    static JSONObject uitmail(String id, String naam, JSONObject herken) throws Exception {
        return new JSONObject().put("id", id).put("naam", naam).put("soort", "uitmail").put("herken", herken);
    }

    public static void main(String[] a) throws Exception {
        JSONObject asset = new JSONObject(new String(Files.readAllBytes(Paths.get("web/facturen-leveranciers.json")), StandardCharsets.UTF_8));
        check(asset.getJSONArray("mailPlatforms").length() > 10 && asset.getJSONArray("freemail").length() > 10, "lijsten met platforms en gratis maildomeinen in de sjablonen");
        JSONObject eigen = new JSONObject().put("nummers", new JSONArray().put("12345678").put("NL20 INGB 0001 2345 67")).put("adressen", new JSONArray().put("rene@esape.nl"));
        FacturenHerken.Kennis k = FacturenHerken.kennis(asset, eigen, new JSONObject());
        check(k.platforms.contains("moneybird.com") && k.freemail.contains("gmail.com") && k.eigen.contains("NL20INGB0001234567") && k.adressen.contains("rene@esape.nl"), "kennis: lijsten, eigen nummers (genormaliseerd) en adressen");

        // ---------- afzender ----------
        check(FacturenHerken.adres("KPN <Facturen@Mail.KPN.com>").equals("facturen@mail.kpn.com") && FacturenHerken.adres("noreply@vattenfall.nl").equals("noreply@vattenfall.nl")
                && FacturenHerken.adres("Iemand").isEmpty() && FacturenHerken.adres("\"A\" <mailto:a@b.nl>").equals("a@b.nl"), "mailadres uit de afzender");
        check(FacturenHerken.naam("\"Vattenfall\" <noreply@vattenfall.nl>").equals("Vattenfall") && FacturenHerken.naam("noreply@x.nl").isEmpty() && FacturenHerken.naam("a@b.nl <a@b.nl>").isEmpty(), "naam uit de afzender");
        check(FacturenHerken.domein("facturen@mail.kpn.com").equals("kpn.com") && FacturenHerken.domein("x@billing.bbc.co.uk").equals("bbc.co.uk") && FacturenHerken.domein("geen").isEmpty(), "subdomeinen tellen als het hoofddomein");
        check(FacturenHerken.schoneNaam("Bakkerij Jansen via Moneybird").equals("Bakkerij Jansen") && FacturenHerken.schoneNaam("Eneco - Facturen").equals("Eneco")
                && FacturenHerken.schoneNaam("noreply").isEmpty() && FacturenHerken.schoneNaam("Facturen").isEmpty() && FacturenHerken.schoneNaam("Billing").isEmpty()
                && FacturenHerken.schoneNaam("Facturatie | Ziggo").equals("Ziggo") && FacturenHerken.schoneNaam("Stand by Me B.V.").equals("Stand by Me B.V."), "nette naam: zonder 'via …', zonder '- Facturen', leeg bij alleen 'noreply'");
        check(FacturenHerken.schoneNaam("Jansen, Piet").equals("Jansen Piet") && FacturenHerken.schoneNaam("Eneco; Facturen").equals("Eneco"), "nette naam: geen komma of puntkomma (die scheidt de herkenning)");

        // ---------- doorgestuurd ----------
        String gmailDoor = "Zie bijlage\n\n---------- Forwarded message ---------\nFrom: KPN <noreply@kpn.com>\nDate: ma 3 nov 2026\nSubject: Uw factuur\n";
        String outlookDoor = "Graag inboeken.\n\n________________________________\nVan: Eneco Klantenservice [mailto:factuur@eneco.nl]\nVerzonden: maandag 3 november 2026\n";
        String apple = "Begin forwarded message:\n\n> From: \"Vattenfall\" <noreply@vattenfall.nl>\n";
        check(FacturenHerken.doorgestuurdVan(gmailDoor).equals("KPN <noreply@kpn.com>"), "doorgestuurd (Gmail): oorspronkelijke afzender");
        check(FacturenHerken.adres(FacturenHerken.doorgestuurdVan(outlookDoor)).equals("factuur@eneco.nl"), "doorgestuurd (Outlook, mailto)");
        check(FacturenHerken.adres(FacturenHerken.doorgestuurdVan(apple)).equals("noreply@vattenfall.nl"), "doorgestuurd (Apple Mail)");
        check(FacturenHerken.doorgestuurdVan("Van: iemand@x.nl\nGewoon een mail").isEmpty() && FacturenHerken.doorgestuurdVan(null).isEmpty(), "geen doorgestuurde mail: niets");
        String html = "<div>Zie bijlage</div><div>---------- Forwarded message ---------<br>From: <strong>KPN</strong> &lt;<a href=\"mailto:noreply@kpn.com\">noreply@kpn.com</a>&gt;<br>Date: ma</div>";
        check(FacturenHerken.adres(FacturenHerken.doorgestuurdVan(FacturenGmail.html(html))).equals("noreply@kpn.com"), "doorgestuurd in een html-mail (Gmail)");

        // ---------- vreemde, lange invoer van buiten (mailkop, mailtekst): snel en zonder stack-overflow ----------
        long t0 = System.currentTimeMillis();
        check(FacturenHerken.adres("<" + rep("a@", 30_000)).isEmpty() && FacturenHerken.adres(rep("x", 60_000)).isEmpty() && FacturenHerken.adres("a@" + rep("a.", 30_000)).isEmpty()
                && FacturenHerken.adres(rep(" ", 50_000) + "KPN <noreply@kpn.com>").equals("noreply@kpn.com"), "lange afzender: adres achteraan gevonden, onzin leeg");
        check(FacturenHerken.schoneNaam(rep("via ", 12_500) + ")x").length() <= 60 && FacturenHerken.doorgestuurdVan(rep("-", 20_000)).isEmpty()
                && FacturenGmail.html(rep("<", 50_000)).length() == 50_000, "lange naam, streepjes en '<': begrensd");
        check(FacturenGmail.zonderScript("a<SCRIPT type=x>var b;</script >c<style>p{}</Style>d").equals("a c d") && FacturenGmail.zonderScript("a<script>x").equals("a<script>x")
                && FacturenGmail.zonderScript(rep("<script>", 12_000)).length() == 96_000 && FacturenGmail.zonderScript(rep("<style>x", 12_000) + "</style>").equals(" "), "script en style weg, ook bij veel open tags snel");
        check(!fout(new JSONObject().put("domeinen", rep("a.", 5_000) + "nl")).isEmpty() && !fout(new JSONObject().put("afzenders", "x@" + rep("a.", 5_000) + "nl")).isEmpty(), "te lang domein of adres: geweigerd");
        check(System.currentTimeMillis() - t0 < 3000, "lange invoer: binnen 3 seconden");

        // ---------- nummers ----------
        check(FacturenHerken.btw("Btw-nummer: NL 0092.92.056.B01").equals(Collections.singletonList("NL009292056B01")), "btw-nummer NL met spaties en punten");
        check(FacturenHerken.btw("btw NL123456789B01").isEmpty(), "btw-nummer NL met een verkeerd controlecijfer telt niet");
        check(FacturenHerken.btw("Ondernemingsnummer BE 0403.170.701").equals(Collections.singletonList("BE0403170701")) && FacturenHerken.btw("BE0403170702").isEmpty(), "btw-nummer BE met controle");
        check(FacturenHerken.btw("USt-IdNr.: DE 123456789").equals(Collections.singletonList("DE123456789")) && FacturenHerken.btw("Ref DE123456789").isEmpty(), "ander EU-btw-nummer alleen na een label");
        check(FacturenHerken.kvk("KvK-nummer: 1234 5678").equals(Collections.singletonList("12345678")) && FacturenHerken.kvk("Chamber of Commerce 87654321").equals(Collections.singletonList("87654321"))
                && FacturenHerken.kvk("Klantnummer 12345678").isEmpty() && FacturenHerken.kvk("KvK 1234567").isEmpty(), "KvK-nummer: 8 cijfers na het label");
        check(FacturenHerken.iban("Betaal aan NL91 ABNA 0417 1643 00 BIC ABNANL2A").equals(Collections.singletonList("NL91ABNA0417164300")), "IBAN met spaties, gevolgd door BIC");
        check(FacturenHerken.iban("IBAN NL91ABNA0417164301").isEmpty() && FacturenHerken.iban("NL91ABNA04171643001").isEmpty(), "IBAN met een verkeerd controlegetal of te lang telt niet");
        check(FacturenHerken.iban("IBAN BE68 5390 0754 7034, DE89370400440532013000").equals(Arrays.asList("BE68539007547034", "DE89370400440532013000")), "IBAN uit België en Duitsland");
        check(FacturenHerken.iban("Het bedrag wordt afgeschreven van rekening NL91ABNA0417164300").isEmpty(), "IBAN bij een incasso (van Rene) telt niet");
        check(FacturenHerken.btw("Uw btw-nummer: NL009292056B01").isEmpty() && FacturenHerken.kvk("Klantgegevens KvK 12345678").isEmpty(), "nummers bij de klant tellen niet");
        check(FacturenHerken.ibanOk("nl91 abna 0417 1643 00") && !FacturenHerken.ibanOk("XX91ABNA0417164300"), "IBAN-controle");

        // ---------- kenmerken ----------
        String pdf = "KPN B.V.\nKvK 27124701\nBtw NL009292056B01\nIBAN NL91ABNA0417164300\nKlant KvK 12345678\nUw rekening NL20INGB0001234567";
        JSONObject km = FacturenHerken.kenmerken(bron("KPN Facturen <facturen@mail.kpn.com>"), pdf, k);
        check(km.getString("soort").equals("domein") && km.getString("domein").equals("kpn.com") && km.getString("adres").equals("facturen@mail.kpn.com"), "kenmerken: domein kpn.com");
        check(km.getJSONArray("btw").length() == 1 && km.getJSONArray("kvk").length() == 1 && km.getJSONArray("kvk").getString(0).equals("27124701")
                && km.getJSONArray("iban").length() == 1 && km.getJSONArray("iban").getString(0).equals("NL91ABNA0417164300"), "kenmerken: nummers van de leverancier, niet die van Rene");
        JSONObject eigenKvk = FacturenHerken.kenmerken(bron("X <x@x.nl>"), "KvK 12345678", k);
        check(eigenKvk.getJSONArray("kvk").length() == 0, "eigen KvK-nummer telt nooit");
        JSONObject mb = FacturenHerken.kenmerken(bron("Bakkerij Jansen via Moneybird <noreply@moneybird.com>"), "", k);
        check(mb.getString("soort").equals("platform") && mb.getString("naam").equals("Bakkerij Jansen"), "platform: de naam zegt wie het is");
        JSONObject mbAntw = FacturenHerken.kenmerken(bron("Bakkerij Jansen via Moneybird <noreply@moneybird.com>").put("antwoordAan", "info@bakkerijjansen.nl"), "", k);
        check(mbAntw.getString("soort").equals("domein") && mbAntw.getString("domein").equals("bakkerijjansen.nl"), "platform met antwoordadres: het domein van de leverancier");
        JSONObject fm = FacturenHerken.kenmerken(bron("Piet Klusbedrijf <pietklus@gmail.com>"), "", k);
        check(fm.getString("soort").equals("freemail") && fm.getString("adres").equals("pietklus@gmail.com"), "gratis adres: het hele adres zegt het");
        JSONObject door = FacturenHerken.kenmerken(new JSONObject().put("afzender", "Rene <rene@esape.nl>").put("onderwerp", "Fwd: factuur").put("doorgestuurdVan", "KPN <noreply@kpn.com>"), "", k);
        check(door.getString("domein").equals("kpn.com") && door.optBoolean("doorgestuurd"), "doorgestuurd door Rene: de oorspronkelijke afzender");
        JSONObject antwoord = FacturenHerken.kenmerken(new JSONObject().put("afzender", "Eneco <info@eneco.nl>").put("onderwerp", "Re: vraag").put("doorgestuurdVan", "Rene <rene@esape.nl>"), "", k);
        check(antwoord.getString("domein").equals("eneco.nl") && !antwoord.optBoolean("doorgestuurd"), "antwoord met een citaat: de afzender zelf blijft");
        JSONObject zelf = FacturenHerken.kenmerken(bron("Rene van Es <rene@esape.nl>"), "", k);
        check(zelf.getString("soort").equals("eigen") && zelf.getString("naam").isEmpty(), "van Rene zelf: geen naam, alleen nummers tellen");

        // ---------- kiezen ----------
        JSONArray levs = new JSONArray()
                .put(new JSONObject().put("id", "facturen-in-gmail").put("naam", "Facturen in Gmail").put("soort", "mail"))
                .put(new JSONObject().put("id", "kpn-zakelijk").put("naam", "KPN Zakelijk").put("soort", "website").put("startUrl", "https://mijnkpnzakelijk.kpn.com"))
                .put(uitmail("eneco", "Eneco", new JSONObject().put("domeinen", new JSONArray().put("eneco.nl")).put("kvk", new JSONArray().put("27124701"))))
                .put(uitmail("piet", "Piet", new JSONObject().put("afzenders", new JSONArray().put("pietklus@gmail.com"))))
                .put(uitmail("jansen", "Bakkerij Jansen", new JSONObject().put("namen", new JSONArray().put("bakkerij jansen")).put("iban", new JSONArray().put("BE68539007547034"))))
                .put(uitmail("regel", "Vaste regel", new JSONObject().put("afzenders", new JSONArray().put("vattenfall.nl"))));
        check(FacturenHerken.kies(levs, km, k).optString("voorstel").equals("eneco") && !FacturenHerken.kies(levs, km, k).has("id"), "nummer van Eneco, maar het domein zegt kpn.com: alleen een voorstel");
        check(!FacturenHerken.kies(levs, km, k).has("id"), "een website-leverancier (KPN Zakelijk) wordt niet vanzelf gekozen");
        JSONObject dKpn = FacturenHerken.kenmerken(bron("<factuur@eneco.nl>"), "", k);
        check(FacturenHerken.kies(levs, dKpn, k).optString("id").equals("eneco") && FacturenHerken.kies(levs, dKpn, k).optString("hoe").equals("domein"), "domein: Eneco");
        check(FacturenHerken.kies(levs, door, k).optString("id").isEmpty(), "doorgestuurde KPN-mail zonder nummers: niemand");
        JSONObject doorNr = FacturenHerken.kenmerken(new JSONObject().put("afzender", "Rene <rene@esape.nl>").put("onderwerp", "factuur"), "KvK 27124701", k);
        check(FacturenHerken.kies(levs, doorNr, k).optString("id").equals("eneco") && FacturenHerken.kies(levs, doorNr, k).optString("hoe").equals("nummer"), "van Rene zelf met het KvK-nummer van Eneco: Eneco");
        check(FacturenHerken.kies(levs, fm, k).optString("id").equals("piet") && FacturenHerken.kies(levs, fm, k).optString("hoe").equals("regel"), "gratis adres: op het adres (regel)");
        check(FacturenHerken.kies(levs, mb, k).optString("id").equals("jansen") && FacturenHerken.kies(levs, mb, k).optString("hoe").equals("naam"), "platform: op de naam");
        JSONObject vf = FacturenHerken.kenmerken(bron("Vattenfall <noreply@mail.vattenfall.nl>"), "", k);
        check(FacturenHerken.kies(levs, vf, k).optString("hoe").equals("regel") && FacturenHerken.kies(levs, vf, k).optString("id").equals("regel"), "eigen regel op domein (ook subdomein)");
        JSONObject andereNaam = FacturenHerken.kenmerken(bron("Visser via Moneybird <noreply@moneybird.com>"), "IBAN BE68539007547034", k);
        check(!FacturenHerken.kies(levs, andereNaam, k).has("id") && FacturenHerken.kies(levs, andereNaam, k).optString("voorstel").equals("jansen"), "platform met een andere naam en hetzelfde IBAN: voorstel, niet vanzelf");
        JSONObject platformZonderNaam = FacturenHerken.kenmerken(bron("<noreply@moneybird.com>"), "IBAN BE68539007547034", k);
        check(FacturenHerken.kies(levs, platformZonderNaam, k).optString("id").equals("jansen"), "platform zonder naam: het IBAN beslist");

        // gedeelde nummers (bij drie of meer afzenders) tellen niet
        FacturenHerken.Kennis k2 = FacturenHerken.kennis(asset, eigen, new JSONObject());
        for (String d : new String[]{"a.nl", "b.nl", "c.nl"}) k2.tel(new JSONObject().put("soort", "domein").put("domein", d).put("iban", new JSONArray().put("BE68539007547034")));
        check(k2.gedeeld.contains("BE68539007547034") && !FacturenHerken.kies(levs, platformZonderNaam, k2).has("id"), "IBAN bij drie afzenders (incassobureau): telt niet meer");
        FacturenHerken.Kennis k3 = FacturenHerken.kennis(asset, eigen, new JSONObject().put("x", new JSONObject().put("kenmerken", new JSONObject().put("soort", "domein").put("domein", "a.nl").put("kvk", new JSONArray().put("11112222")))));
        check(k3.bij.containsKey("11112222") && !k3.gedeeld.contains("11112222"), "kennis telt de kenmerken uit het dossier");

        // twee leveranciers met hetzelfde nummer: dat nummer beslist niets
        JSONArray dubbel = new JSONArray(levs.toString()).put(uitmail("eneco2", "Eneco Zakelijk", new JSONObject().put("kvk", new JSONArray().put("27124701"))));
        check(!FacturenHerken.kies(dubbel, doorNr, k).has("id"), "nummer bij twee leveranciers: niet kiezen");

        // ---------- nieuw ----------
        JSONObject bronLev = new JSONObject().put("id", "facturen-in-gmail").put("map", true).put("drive", true).put("lokaal", false).put("eigenCloud", false);
        JSONObject vfn = FacturenHerken.nieuw(FacturenHerken.kenmerken(bron("Vattenfall <noreply@vattenfall.com>"), "KvK 33333333", k), bronLev, levs, k, "");
        check(vfn.getString("naam").equals("Vattenfall") && vfn.getString("soort").equals("uitmail") && vfn.getBoolean("nieuw") && vfn.getJSONArray("bronnen").getString(0).equals("facturen-in-gmail"), "nieuw: naam van de afzender, soort uitmail, met de bron");
        check(vfn.getJSONObject("herken").getJSONArray("domeinen").getString(0).equals("vattenfall.com") && vfn.getJSONObject("herken").getJSONArray("kvk").getString(0).equals("33333333"), "nieuw: herkent voortaan op domein en KvK");
        check(vfn.getBoolean("drive") && vfn.getBoolean("map") && !vfn.getBoolean("lokaal"), "nieuw: opslag zoals bij de mailbron");
        JSONObject zn = FacturenHerken.nieuw(FacturenHerken.kenmerken(bron("<noreply@nieuwbedrijf.nl>"), "", k), bronLev, levs, k, "");
        check(zn.getString("naam").equals("nieuwbedrijf.nl"), "nieuw zonder naam: het domein");
        JSONObject kp = FacturenHerken.nieuw(FacturenHerken.kenmerken(bron("KPN <noreply@kpn.com>"), "", k), bronLev, levs, k, "");
        check(kp.getString("voorstel").equals("kpn-zakelijk"), "nieuw met het domein van een website-leverancier: voorstel Hoort bij KPN Zakelijk");
        JSONObject en2 = FacturenHerken.nieuw(FacturenHerken.kenmerken(bron("Eneco <info@eneco-mail.nl>"), "", k), bronLev, levs, k, "");
        check(en2.getString("naam").equals("Eneco (eneco-mail.nl)") && en2.getString("voorstel").equals("eneco"), "zelfde naam, ander domein: met het domein erbij en een voorstel");
        JSONObject leeg = FacturenHerken.nieuw(zelf, bronLev, levs, k, "");
        check(leeg.getString("id").equals(FacturenHerken.NIET_HERKEND) && leeg.getJSONObject("herken").length() == 0, "niets bruikbaars: Niet herkend");
        JSONObject metVs = FacturenHerken.nieuw(km, bronLev, levs, k, "eneco");
        check(metVs.getString("voorstel").equals("eneco") && !metVs.getJSONObject("herken").has("kvk"), "nieuw met een voorstel; nummer dat Eneco al heeft niet nog eens");

        // ---------- leren ----------
        JSONObject piet = new JSONObject(levs.getJSONObject(3).toString());
        JSONObject pietKm = FacturenHerken.kenmerken(bron("<pietklus@gmail.com>"), "KvK 44445555\nIBAN NL91ABNA0417164300", k);
        check(FacturenHerken.leer(piet, pietKm, levs, k) && piet.getJSONObject("herken").getJSONArray("kvk").getString(0).equals("44445555"), "leren: nieuwe nummers erbij");
        check(!FacturenHerken.leer(piet, pietKm, levs, k), "leren: niets dubbel");
        JSONObject eneco = new JSONObject(levs.getJSONObject(2).toString());
        check(!FacturenHerken.leer(eneco, FacturenHerken.kenmerken(bron("<x@ander.nl>"), "", k), levs, k), "leren: een tweede domein komt er niet vanzelf bij");

        // ---------- instellingen ----------
        JSONObject hn = FacturenHerken.herkenNormaal(new JSONObject().put("afzenders", "Facturen@KPN.com, vattenfall.nl").put("domeinen", "https://www.Eneco.nl/").put("btw", "nl 0092.92.056 b01").put("kvk", "1234 5678").put("iban", "nl91 abna 0417 1643 00"));
        check(hn.getJSONArray("afzenders").length() == 2 && hn.getJSONArray("afzenders").getString(0).equals("facturen@kpn.com") && hn.getJSONArray("domeinen").getString(0).equals("eneco.nl")
                && hn.getJSONArray("btw").getString(0).equals("NL009292056B01") && hn.getJSONArray("kvk").getString(0).equals("12345678") && hn.getJSONArray("iban").getString(0).equals("NL91ABNA0417164300"), "herkenning genormaliseerd");
        check(fout(new JSONObject().put("kvk", "1234")).contains("8 cijfers") && fout(new JSONObject().put("iban", "NL00ABNA0000000000")).contains("IBAN")
                && fout(new JSONObject().put("btw", "NL123")).contains("btw") && fout(new JSONObject().put("afzenders", "geen adres")).contains("mailadres"), "foute herkenning: uitleg");
        check(FacturenHerken.herkenNormaal(null).length() == 0 && FacturenHerken.herkenNormaal(new JSONObject()).length() == 0, "geen herkenning: leeg");
        check(FacturenHerken.herkenNormaal(new JSONObject().put("afzenders", "a/b@x-y.nl, o'brien@ierland.ie")).getJSONArray("afzenders").length() == 2, "afzenders: elk geldig mailadres (ook met / of ')");
        JSONObject en = FacturenHerken.eigenNormaal(new JSONObject().put("nummers", "12345678, NL91 ABNA 0417 1643 00").put("adressen", "Rene@Esape.nl"));
        check(en.getJSONArray("nummers").length() == 2 && en.getJSONArray("adressen").getString(0).equals("rene@esape.nl"), "eigen nummers en adressen");
        check(eigenFout(new JSONObject().put("nummers", "abc")).contains("KvK") && eigenFout(new JSONObject().put("adressen", "Rene <r@x.nl>")).contains("mailadres"), "foute eigen gegevens: uitleg");
        JSONObject sm = FacturenHerken.samen(new JSONObject().put("domeinen", new JSONArray().put("a.nl")), new JSONObject().put("domeinen", new JSONArray().put("a.nl").put("b.nl")).put("kvk", new JSONArray().put("12345678")));
        check(sm.getJSONArray("domeinen").length() == 2 && sm.getJSONArray("kvk").length() == 1, "samenvoegen: alles van beide, niets dubbel");
        check(FacturenHerken.regelPast("kpn.com", "a@mail.kpn.com") && !FacturenHerken.regelPast("kpn.com", "a@notkpn.com") && FacturenHerken.regelPast("a@b.nl", "a@b.nl") && !FacturenHerken.regelPast("a@b.nl", "x@b.nl"), "eigen regels: domein of heel adres");

        // ---------- onthouden (factuur bij een andere leverancier) ----------
        JSONArray rl = new JSONArray()
                .put(uitmail("oud", "Oud", new JSONObject().put("afzenders", new JSONArray().put("piet@gmail.com")).put("kvk", new JSONArray().put("44445555"))))
                .put(uitmail("piet", "Piet Klus", new JSONObject().put("namen", new JSONArray().put("piet klus"))).put("nieuw", true))
                .put(new JSONObject().put("id", "kpn").put("naam", "KPN").put("soort", "website"));
        JSONObject rkm = FacturenHerken.kenmerken(bron("Piet <piet@gmail.com>"), "KvK 44445555", k);
        check(FacturenIndeling.regel(rl, "piet", rkm, k.platforms), "onthouden: er verandert iets");
        JSONObject rh = rl.getJSONObject(1).getJSONObject("herken");
        check(rh.getJSONArray("afzenders").getString(0).equals("piet@gmail.com") && rh.getJSONArray("kvk").getString(0).equals("44445555") && !rl.getJSONObject(1).optBoolean("nieuw"), "onthouden: adres en nummer bij de gekozen leverancier, niet meer nieuw");
        check(rl.getJSONObject(0).getJSONObject("herken").getJSONArray("afzenders").length() == 0 && rl.getJSONObject(0).getJSONObject("herken").getJSONArray("kvk").length() == 0, "onthouden: weg bij de vorige (anders wint de oude regel)");
        check(!FacturenIndeling.regel(rl, "piet", rkm, k.platforms), "onthouden: twee keer = niets nieuws");
        check(FacturenHerken.kies(rl, FacturenHerken.kenmerken(bron("<piet@gmail.com>"), "", k), k).optString("id").equals("piet"), "onthouden: de volgende factuur komt vanzelf goed");
        JSONObject pkm = FacturenHerken.kenmerken(bron("Bakkerij Jansen <noreply@moneybird.com>"), "", k);
        check(FacturenIndeling.regel(rl, "kpn", pkm, k.platforms) && rl.getJSONObject(2).getJSONObject("herken").getJSONArray("namen").getString(0).equals("bakkerij jansen")
                && !rl.getJSONObject(2).getJSONObject("herken").has("afzenders"), "onthouden via een platform: de naam, niet het gedeelde adres");
        JSONArray rl2 = new JSONArray().put(uitmail("webshop", "Webshop", new JSONObject())).put(uitmail("ander", "Ander", new JSONObject()))
                .put(uitmail(FacturenHerken.NIET_HERKEND, "Niet herkend", new JSONObject()));
        JSONObject stripe = FacturenHerken.kenmerken(new JSONObject().put("afzender", "Webshop <invoice@stripe.com>").put("antwoordAan", "info@webshop.nl").put("onderwerp", "Je factuur"), "", k);
        check(stripe.optBoolean("platform") && stripe.optString("soort").equals("domein") && FacturenIndeling.regel(rl2, "webshop", stripe, k.platforms)
                && rl2.getJSONObject(0).getJSONObject("herken").getJSONArray("domeinen").getString(0).equals("webshop.nl") && !rl2.getJSONObject(0).getJSONObject("herken").has("afzenders"),
                "onthouden via een platform met een antwoordadres: dat domein, niet het adres van het platform");
        JSONObject zonderVlag = new JSONObject(stripe.toString()); zonderVlag.remove("platform"); zonderVlag.put("domein", "winkel.nl");
        FacturenIndeling.regel(rl2, "ander", zonderVlag, k.platforms);
        check(!rl2.getJSONObject(1).getJSONObject("herken").has("afzenders"), "onthouden: het adres van een platform nooit, ook zonder vlag in de kenmerken");
        JSONObject mbZonder = FacturenHerken.kenmerken(bron("<noreply@moneybird.com>"), "KvK 55556666", k);
        check(FacturenIndeling.regel(rl2, "ander", mbZonder, k.platforms) && !rl2.getJSONObject(1).getJSONObject("herken").has("afzenders") && !rl2.getJSONObject(1).getJSONObject("herken").has("namen")
                && rl2.getJSONObject(1).getJSONObject("herken").getJSONArray("kvk").getString(0).equals("55556666"), "platform zonder naam: alleen de nummers");
        JSONObject eigenKm = FacturenHerken.kenmerken(bron("Rene <rene@esape.nl>"), "KvK 77778888", k);
        check(FacturenIndeling.regel(rl2, "webshop", eigenKm, k.platforms) && !rl2.getJSONObject(0).getJSONObject("herken").has("afzenders")
                && rl2.getJSONObject(0).getJSONObject("herken").getJSONArray("kvk").getString(0).equals("77778888"), "eigen adres: alleen de nummers, nooit Rene's adres");
        check(!FacturenIndeling.regel(rl2, FacturenHerken.NIET_HERKEND, rkm, k.platforms) && rl2.getJSONObject(2).getJSONObject("herken").length() == 0, "naar Niet herkend: niets onthouden");

        // ---------- Niet herkend leert niets en wordt nooit vanzelf gekozen ----------
        JSONObject nh = uitmail(FacturenHerken.NIET_HERKEND, "Niet herkend", new JSONObject().put("kvk", new JSONArray().put("99990000")));
        JSONArray metNh = new JSONArray(levs.toString()).put(nh);
        boolean nhDoel = false; for (JSONObject d : FacturenHerken.doelen(metNh)) nhDoel |= d.optString("id").equals(FacturenHerken.NIET_HERKEND);
        check(!nhDoel && !FacturenHerken.leer(new JSONObject(nh.toString()), pietKm, metNh, k), "Niet herkend: geen doel en leert niets bij");
        check(!FacturenHerken.kies(metNh, FacturenHerken.kenmerken(bron("<x@onbekend99.nl>"), "KvK 99990000", k), k).optString("id").equals(FacturenHerken.NIET_HERKEND), "een nummer bij Niet herkend kiest die niet");
        JSONObject alleenNr = FacturenHerken.nieuw(FacturenHerken.kenmerken(bron("<noreply@moneybird.com>"), "KvK 66667777", k), bronLev, levs, k, "");
        check(alleenNr.getString("naam").equals("KvK 66667777") && !alleenNr.optString("id").equals(FacturenHerken.NIET_HERKEND), "nieuw met alleen een nummer (platform zonder naam): heet naar dat nummer");
        JSONObject eigenNr = FacturenHerken.nieuw(eigenKm, bronLev, levs, k, "");
        check(eigenNr.getString("naam").equals("KvK 77778888") && eigenNr.getJSONObject("herken").getJSONArray("kvk").length() == 1, "doorgestuurd vanaf je eigen adres met een KvK: een leverancier op dat nummer");

        // ---------- terugzetten na overslaan ----------
        JSONObject tz = new JSONObject()
                .put("gmail", new JSONObject().put("lastOk", 5).put("mailSinds", 7).put("mailMeer", true).put("verwerkt", new JSONObject()
                        .put("m1", new JSONObject().put("overgeslagen", true).put("l", "ziggo")).put("m2", new JSONObject().put("overgeslagen", true).put("l", "eneco"))
                        .put("m3", new JSONObject().put("n", "a.pdf").put("l", "ziggo"))))
                .put("werk", new JSONObject().put("lastOk", 9).put("verwerkt", new JSONObject().put("m9", new JSONObject().put("overgeslagen", true).put("l", "eneco"))));
        java.util.Set<String> mbNu = new java.util.HashSet<>(Arrays.asList("gmail", "werk"));
        check(FacturenIndeling.terug(tz, "eneco", new java.util.HashSet<>(Arrays.asList("gmail"))) == 1 && tz.getJSONObject("werk").getJSONObject("verwerkt").has("m9"), "terugzetten: niet bij een mailbron die er niet meer is");
        tz.getJSONObject("gmail").put("lastOk", 5).put("mailMeer", true).getJSONObject("verwerkt").put("m2", new JSONObject().put("overgeslagen", true).put("l", "eneco"));
        check(FacturenIndeling.terug(tz, "ziggo", mbNu) == 1 && !tz.getJSONObject("gmail").getJSONObject("verwerkt").has("m1") && tz.getJSONObject("gmail").getJSONObject("verwerkt").has("m3")
                && tz.getJSONObject("gmail").getLong("lastOk") == 0 && !tz.getJSONObject("gmail").getBoolean("mailMeer") && tz.getJSONObject("werk").getLong("lastOk") == 9,
                "terugzetten: alleen de overgeslagen mails van die leverancier opnieuw, en alleen die mailbron zoekt de hele periode af");
        check(FacturenIndeling.terug(tz, "ziggo", mbNu) == 0 && FacturenIndeling.terug(tz, "eneco", mbNu) == 2, "terugzetten: per mailbron, niets dubbel");

        // ---------- samenvoegen: keuzes bij Aanleveren ----------
        JSONArray ids = new JSONArray().put("a").put("van").put("naar");
        check(FacturenIndeling.vervang(ids, "van", "naar") && ids.length() == 2 && ids.getString(0).equals("a") && ids.getString(1).equals("naar"), "samenvoegen: aangevinkt blijft aangevinkt, niet dubbel");
        JSONArray ids2 = new JSONArray().put("van");
        check(FacturenIndeling.vervang(ids2, "van", "naar") && ids2.length() == 1 && ids2.getString(0).equals("naar") && !FacturenIndeling.vervang(ids2, "van", "naar") && !FacturenIndeling.vervang(null, "a", "b"), "samenvoegen: vervangen, en niets als hij er niet in staat");

        System.out.println(fails == 0 ? "Alles goed" : fails + " mislukt");
        if (fails > 0) System.exit(1);
    }
}
