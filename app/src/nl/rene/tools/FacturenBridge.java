package nl.rene.tools;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Brugfuncties voor Facturen ophalen. Zit tussen GebruikBridge en MainActivity.Bridge. */
public class FacturenBridge extends GebruikBridge {

    FacturenBridge(MainActivity act) { super(act); }

    /** Alles voor het scherm: leveranciers (zonder wachtwoorden), toestand, Eigen Cloud, sjablonen. */
    @JavascriptInterface public String facturenState() {
        try {
            JSONArray l = Facturen.leveranciers(ctx);
            JSONObject all = Facturen.toestand(ctx);
            JSONArray out = new JSONArray();
            if (l != null) for (int i = 0; i < l.length(); i++) {
                JSONObject o = new JSONObject(l.getJSONObject(i).toString());
                String id = o.optString("id");
                JSONObject t = all.optJSONObject(id);
                if (t == null) t = new JSONObject();
                t.remove("verwerkt");
                o.put("toestand", t).put("heeftWachtwoord", Facturen.heeftGeheim(ctx, "lev_" + id))
                        .put("aantal", Facturen.bestanden(ctx, id).length());
                out.put(o);
            }
            SharedPreferences p = Facturen.prefs(ctx);
            FacturenRun r = FacturenRun.LOPEND.get();
            FacturenJob.schedule(ctx);
            return new JSONObject().put("leveranciers", out).put("onleesbaar", l == null)
                    .put("bezig", r == null ? "" : r.id).put("voortgang", r == null ? "" : r.voortgang).put("zichtbaar", r != null && r.zichtbaar)
                    .put("ec", new JSONObject().put("url", p.getString("ecUrl", "")).put("gebruiker", p.getString("ecGebruiker", ""))
                            .put("adres", p.getString("ecAdres", "")).put("heeftWachtwoord", Facturen.heeftGeheim(ctx, "ec")))
                    .put("map", WaBackup.destUri(ctx) != null)
                    .put("sms", ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
                    .put("sjablonen", Facturen.sjablonen(ctx)).toString();
        } catch (Exception e) { return "{\"leveranciers\":[],\"error\":\"Laden lukt niet\"}"; }
    }

    /** Leverancier bewaren; json met de instellingen en eventueel "wachtwoord". Geeft {id} of {error}. */
    @JavascriptInterface public String facturenBewaar(String json) {
        try {
            JSONObject in = new JSONObject(json == null ? "{}" : json);
            String pw = in.optString("wachtwoord", "");
            in.remove("wachtwoord");
            String id = Facturen.bewaar(ctx, in, pw);
            FacturenJob.schedule(ctx);
            return new JSONObject().put("id", id).toString();
        } catch (Exception e) { return err(e, "Bewaren lukt niet"); }
    }

    @JavascriptInterface public String facturenVerwijder(String id) {
        try {
            FacturenRun r = FacturenRun.LOPEND.get();
            if (r != null && r.id.equals(id)) return "Even wachten: deze leverancier wordt nu opgehaald";
            Facturen.verwijder(ctx, id);
            FacturenJob.schedule(ctx);
            return "";
        } catch (Exception e) { return e.getMessage() == null ? "Verwijderen lukt niet" : e.getMessage(); }
    }

    /** Nu ophalen. Meekijken = in beeld (FacturenActivity). Uitkomst via onFacturenKlaar({id, ok, msg, nieuw …}). */
    @JavascriptInterface public String facturenNu(String id, boolean meekijken) {
        if (FacturenRun.LOPEND.get() != null) return "Er worden al facturen opgehaald";
        if (Facturen.leverancier(ctx, id) == null) return "Leverancier niet gevonden";
        if (meekijken) {
            a.h.post(() -> {
                try { a.startActivity(new Intent(a, FacturenActivity.class).putExtra("id", id)); }
                catch (Exception e) { Toast.makeText(a, "Openen lukt niet", Toast.LENGTH_SHORT).show(); }
            });
            return "";
        }
        a.h.post(() -> {
            String e = FacturenRun.begin(ctx, id, null, r -> a.js("onFacturenKlaar", r.toString()));
            if (!e.isEmpty()) a.js("onFacturenKlaar", "{\"id\":" + JSONObject.quote(id) + ",\"ok\":false,\"msg\":" + JSONObject.quote(e) + "}");
        });
        return "";
    }

    @JavascriptInterface public void facturenStop() { FacturenRun r = FacturenRun.LOPEND.get(); if (r != null) r.stoppen(); }

    /** Facturen in de app van één leverancier: [{naam, t, grootte}]. */
    @JavascriptInterface public String facturenLijst(String id) {
        try { return Facturen.bestanden(ctx, id).toString(); } catch (Exception e) { return "[]"; }
    }

    /** Een factuur openen in de eigen PDF-viewer (via een kopie in de viewer-map). */
    @JavascriptInterface public void facturenOpen(String id, String naam) {
        try {
            File f = Facturen.bestand(ctx, id, naam);
            File d = new File(PdfTools.dir(ctx, PdfViewActivity.DIR), "facturen");
            if (!d.isDirectory() && !d.mkdirs()) throw new Exception("map");
            File[] oud = d.listFiles();
            if (oud != null) for (File x : oud) x.delete();
            File kopie = new File(d, f.getName());
            try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream o = new java.io.FileOutputStream(kopie)) {
                byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) o.write(b, 0, n);
            }
            Intent v = new Intent(ctx, PdfViewActivity.class).setData(PdfProvider.uri(kopie)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.h.post(() -> { try { a.startActivity(v); } catch (Exception e) { Toast.makeText(a, "Openen lukt niet", Toast.LENGTH_SHORT).show(); } });
        } catch (Exception e) { a.h.post(() -> Toast.makeText(a, "Factuur niet gevonden", Toast.LENGTH_SHORT).show()); }
    }

    /** Eigen Cloud koppelen: {url, gebruiker, adres, wachtwoord (leeg = ongewijzigd)}. Geeft "" of een fout. */
    @JavascriptInterface public String facturenEc(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            String url = o.optString("url", "").trim().replaceAll("/+$", ""), user = o.optString("gebruiker", "").trim(), adres = o.optString("adres", "").trim();
            if (url.isEmpty() && user.isEmpty()) {
                Facturen.prefs(ctx).edit().remove("ecUrl").remove("ecGebruiker").remove("ecAdres").apply();
                Facturen.geheim(ctx).edit().remove("ec").apply();
                return "";
            }
            if (!url.matches("(?i)https://[^\\s/?#]+[^\\s]*")) return "Het adres van Eigen Cloud moet beginnen met https://";
            if (user.isEmpty() || user.length() > 320) return "Vul je gebruikersnaam of mailadres in";
            if (adres.length() > 320 || (!adres.isEmpty() && !adres.matches("[^\\s@]+@[^\\s@]+"))) return "Het mailadres voor de facturen klopt niet";
            Facturen.prefs(ctx).edit().putString("ecUrl", url).putString("ecGebruiker", user).putString("ecAdres", adres).apply();
            String pw = o.optString("wachtwoord", "");
            if (!pw.isEmpty()) Facturen.zetGeheim(ctx, "ec", pw);
            return "";
        } catch (Exception e) { return e.getMessage() == null ? "Bewaren lukt niet" : e.getMessage(); }
    }

    /** Koppeling testen; antwoord via onFacturenEcTest({ok} of {error}). */
    @JavascriptInterface public void facturenEcTest() {
        new Thread(() -> {
            String r;
            try { Facturen.testEigenCloud(ctx); r = "{\"ok\":true}"; }
            catch (Throwable e) { r = "{\"error\":" + JSONObject.quote(Facturen.kort(e)) + "}"; }
            a.js("onFacturenEcTest", r);
        }, "facturen-ec-test").start();
    }

    private static String err(Exception e, String fb) {
        try { return new JSONObject().put("error", e.getMessage() == null ? fb : e.getMessage()).toString(); } catch (Exception x) { return "{\"error\":\"" + fb + "\"}"; }
    }
}
