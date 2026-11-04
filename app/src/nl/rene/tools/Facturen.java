package nl.rene.tools;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facturen ophalen: een algemeen raamwerk voor websites waar je facturen als PDF downloadt (eerste leverancier: KPN Zakelijk).
 * Een leverancier is alleen een instelling (zie web/facturen-leveranciers.json voor de sjablonen); FacturenRun doet het werk
 * in een onzichtbare (of meekijkende) WebView. Hier: instellingen, wachtwoorden, opslag, inlogcode en afleveren.
 *
 * Privacy: alleen Rene's eigen facturen, naar plekken die hij zelf kiest (de app, zijn backup-map, zijn eigen Eigen Cloud).
 * Wachtwoorden versleuteld met de sleutel van de telefoon (Secure.deviceKey) in "facturen_geheim", niet in backups.
 */
final class Facturen {
    private Facturen() { }

    static final String CHANNEL = "facturen";
    static final String ASSET = "facturen-leveranciers.json";
    static final String MAP = "Facturen";
    static final long MAX_PDF = 25L * 1024 * 1024;
    static final String[] SCHEMA = {"dag", "week", "maand", "uit"};
    static final String[] BRON = {"geen", "sms", "eigencloud"};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("facturen", Context.MODE_PRIVATE); }
    static SharedPreferences geheim(Context c) { return c.getSharedPreferences("facturen_geheim", Context.MODE_PRIVATE); }
    static File dir(Context c) { return new File(c.getFilesDir(), "facturen"); }
    static File levFile(Context c) { return new File(dir(c), "leveranciers.json"); }
    static File toestandFile(Context c) { return new File(dir(c), "toestand.json"); }
    static File pdfDir(Context c, String id) { return new File(new File(dir(c), "pdf"), veiligId(id)); }

    static final Object LOCK = new Object();

    // ================= configuratie (zonder Android: getest in FacturenTest) =================

    static String veiligId(String s) {
        String id = s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (id.length() > 40) id = id.substring(0, 40).replaceAll("-+$", "");
        return id.isEmpty() ? "leverancier" : id;
    }

    /** Bestandsnaam die ook op USB-sticks en SD-kaarten (FAT) kan (zelfde regel als WaBackup.safeName). */
    static String veiligeNaam(String n) {
        StringBuilder b = new StringBuilder(n.length());
        for (char ch : n.toCharArray()) b.append(ch < 32 || "\"*/:<>?\\|".indexOf(ch) >= 0 ? '_' : ch);
        return b.toString();
    }

    static String host(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://(?:[^/@?#]*@)?([^/:?#]+)").matcher(url.trim());
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\.$", "") : "";
    }

    /** Het domein van een adres: mijnkpnzakelijk.kpn.com → kpn.com (ook bij .co.uk e.d.: drie delen). */
    static String domein(String url) {
        String h = host(url);
        if (h.isEmpty() || h.matches("[0-9.]+") || h.contains(":")) return h;
        String[] d = h.split("\\.");
        if (d.length <= 2) return h;
        int n = d[d.length - 2].length() <= 3 && d[d.length - 1].length() == 2 && d[d.length - 2].matches("co|com|org|net|gov|ac|edu") ? 3 : 2;
        StringBuilder b = new StringBuilder();
        for (int i = d.length - n; i < d.length; i++) b.append(i > d.length - n ? "." : "").append(d[i]);
        return b.toString();
    }

    /** Staat dit adres op de eigen website van de leverancier, met https? Alleen daar vult de app iets in. */
    static boolean eigenSite(String url, String domein) {
        if (url == null || domein == null || domein.isEmpty() || !url.trim().toLowerCase(Locale.ROOT).startsWith("https://")) return false;
        String h = host(url);
        return h.equals(domein) || h.endsWith("." + domein);
    }

    /** Domeinen waar iedereen een eigen website op kan zetten: daar nooit gegevens invullen. */
    static final String[] GEDEELD = {"github.io", "gitlab.io", "vercel.app", "netlify.app", "pages.dev", "workers.dev", "herokuapp.com", "blogspot.com",
            "wordpress.com", "wixsite.com", "web.app", "firebaseapp.com", "appspot.com", "azurewebsites.net", "cloudfront.net", "amazonaws.com",
            "googleusercontent.com", "ngrok.io", "ngrok.app", "glitch.me", "repl.co", "onrender.com", "fly.dev", "ltd.uk", "plc.uk", "me.uk"};

    /** Op een van deze domeinen (bijv. ziggo.nl en vodafone.nl, waar Ziggo inlogt)? */
    static boolean eigenSite(String url, String[] domeinen) {
        for (String d : domeinen) if (eigenSite(url, d)) return true;
        return false;
    }

    /** Andere domeinen van dezelfde leverancier, bijv. "vodafone.nl, vodafoneziggo.nl" → gecontroleerd en genormaliseerd. */
    static String extraDomeinen(String s) throws Exception {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String x : (s == null ? "" : s).split("[,;\\s]+")) {
            String d = x.trim().toLowerCase(Locale.ROOT).replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll("^\\*?\\.", "");
            if (d.isEmpty()) continue;
            if (!d.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+") || d.length() > 100 || !domein("https://" + d).equals(d) || d.matches("(co|com|org|net|gov|ac|edu)\\.[a-z]{2}") || d.matches("[0-9.]+|.*\\.[0-9]+")
                    || d.matches("(.*\\.)?-.*|.*-(\\..*)?") || d.matches(".*\\.-.*|.*-\\..*") || in(d, GEDEELD))
                throw new Exception("Geen geldig domein: " + (d.length() > 40 ? d.substring(0, 40) + "…" : d) + " (bijv. vodafone.nl)");
            if (++n > 5) throw new Exception("Hooguit 5 andere domeinen");
            if (!(", " + b + ",").contains(", " + d + ",")) b.append(b.length() > 0 ? ", " : "").append(d);
        }
        return b.toString();
    }

    /** Alle domeinen van een leverancier: dat van de inlogpagina plus de andere (zonder eigen keuze: die van het sjabloon). */
    static String[] domeinen(Context c, JSONObject lev) {
        java.util.LinkedHashSet<String> l = new java.util.LinkedHashSet<>();
        String eerste = domein(lev.optString("startUrl"));
        if (!eerste.isEmpty()) l.add(eerste);
        String extra = lev.has("domeinen") ? lev.optString("domeinen") : null;
        if (extra == null && c != null) {
            JSONArray sj = sjablonen(c);
            for (int i = 0; i < sj.length(); i++) { JSONObject s = sj.optJSONObject(i); if (s != null && s.optString("sjabloon").equals(lev.optString("sjabloon"))) extra = s.optString("domeinen"); }
        }
        try { for (String d : extraDomeinen(extra).split(", ")) if (!d.isEmpty()) l.add(d); } catch (Exception ignored) { }
        return l.toArray(new String[0]);
    }

    static boolean in(String v, String[] set) { for (String s : set) if (s.equals(v)) return true; return false; }

    private static String tekst(JSONObject o, String k, int max, String def) throws Exception {
        String v = o.optString(k, def == null ? "" : def).trim();
        if (v.length() > max) throw new Exception("Te lang: " + k);
        return v;
    }

    private static String patroon(JSONObject o, String k, String def, String wat) throws Exception {
        String v = tekst(o, k, 200, def);
        if (v.isEmpty()) return v;
        try { Pattern.compile(v); } catch (Exception e) { throw new Exception(wat + " is geen geldig zoekpatroon"); }
        return v;
    }

    private static String adres(String v, boolean nodig, String wat) throws Exception {
        if (v.isEmpty()) { if (nodig) throw new Exception("Vul " + wat + " in"); return v; }
        if (!v.matches("(?i)https://[^\\s/?#]+[^\\s]*")) throw new Exception(wat.substring(0, 1).toUpperCase(Locale.ROOT) + wat.substring(1) + " moet beginnen met https://");
        return v;
    }

    /** Controleert een leverancier en vult ontbrekende keuzes aan. Fout = Nederlandse uitleg voor in de app. */
    static JSONObject normaal(JSONObject in) throws Exception {
        if (in == null) throw new Exception("Geen instellingen");
        JSONObject o = new JSONObject();
        String naam = tekst(in, "naam", 60, "");
        if (naam.isEmpty()) throw new Exception("Geef de leverancier een naam");
        o.put("naam", naam);
        String id = in.optString("id", "").trim();
        o.put("id", id.isEmpty() ? veiligId(naam) : veiligId(id));
        o.put("sjabloon", veiligId(in.optString("sjabloon", "anders")));
        String soort = in.optString("soort", "website");
        if (!"website".equals(soort) && !"mail".equals(soort)) throw new Exception("Onbekende soort leverancier");
        o.put("soort", soort);
        boolean mail = "mail".equals(soort);
        o.put("startUrl", adres(tekst(in, "startUrl", 500, ""), !mail, "de inlogpagina"));
        if (mail) o.put("mail", FacturenMail.normaal(in.optJSONObject("mail")));
        o.put("facturenUrl", adres(tekst(in, "facturenUrl", 500, ""), false, "de facturenpagina"));
        if (in.has("domeinen")) o.put("domeinen", extraDomeinen(tekst(in, "domeinen", 400, "")));
        o.put("gebruiker", tekst(in, "gebruiker", 200, ""));
        o.put("menu", patroon(in, "menu", "facturen|factuur", "De tekst naar de facturen"));
        String knoppen = patroon(in, "knoppen", "download|\\bpdf\\b|\\.pdf", "Het patroon voor downloadknoppen");
        o.put("knoppen", knoppen.isEmpty() ? "download|\\bpdf\\b|\\.pdf" : knoppen);
        int max = in.optInt("max", 50);
        o.put("max", Math.max(1, Math.min(200, max)));
        o.put("meer", patroon(in, "meer", MEER, "Het patroon voor \"meer facturen tonen\""));
        o.put("periode", periode(in.optJSONObject("periode")));
        JSONObject ci = in.optJSONObject("code");
        if (ci == null) ci = new JSONObject();
        JSONObject co = new JSONObject();
        String bron = ci.optString("bron", "geen");
        if (!in(bron, BRON)) throw new Exception("Onbekende bron voor de inlogcode");
        co.put("bron", bron).put("afzender", tekst(ci, "afzender", 60, "")).put("patroon", patroon(ci, "patroon", "", "Het codepatroon"));
        o.put("code", co);
        String schema = in.optString("schema", "maand");
        if (!in(schema, SCHEMA)) throw new Exception("Onbekende keuze voor hoe vaak");
        o.put("schema", schema);
        // Vast moment: maand = dag 1–31 van de maand (in kortere maanden de laatste dag), week = weekdag (1 = maandag), altijd met een tijd (UU:MM)
        o.put("dag", Math.max(1, Math.min(31, in.optInt("dag", 5)))); // 29–31: in kortere maanden de laatste dag
        o.put("opnieuw", Math.max(1, Math.min(24, in.optInt("opnieuw", 3))));   // uren tussen pogingen na een fout
        o.put("wifi", in.optBoolean("wifi", false));                             // vanzelf alleen via wifi
        o.put("meldNieuw", in.optBoolean("meldNieuw", true));                    // melding bij nieuwe facturen
        o.put("meldFout", in.optBoolean("meldFout", true));                      // melding als het mislukt
        o.put("weekdag", Math.max(1, Math.min(7, in.optInt("weekdag", 1))));
        String tijd = in.optString("tijd", "08:00").trim();
        if (!tijd.matches("([01]?\\d|2[0-3]):[0-5]\\d")) throw new Exception("Vul een tijd in als 08:00");
        if (tijd.length() == 4) tijd = "0" + tijd;
        o.put("tijd", tijd);
        o.put("map", in.optBoolean("map", true));
        o.put("eigenCloud", in.optBoolean("eigenCloud", false));
        o.put("lokaal", in.optBoolean("lokaal", false));
        o.put("drive", in.optBoolean("drive", false));
        String sub = veiligeNaam(tekst(in, "submap", 60, naam)).replace('.', '_').trim();
        o.put("submap", sub.isEmpty() ? veiligeNaam(naam).replace('.', '_') : sub);
        JSONObject vi = in.optJSONObject("velden");
        JSONObject vo = new JSONObject();
        for (String k : new String[]{"gebruiker", "wachtwoord", "code", "knop"}) {
            String v = vi == null ? "" : tekst(vi, k, 300, "");
            if (v.matches(".*[<>{};].*")) throw new Exception("Ongeldige veldaanwijzing: " + k);
            vo.put(k, v);
        }
        o.put("velden", vo);
        return o;
    }

    /** Knoppen die meer facturen op dezelfde pagina laten zien (geen "volgende pagina": dan raakt de lijst weg). */
    static final String MEER = "toon meer|meer tonen|meer facturen|meer laden|laad meer|oudere facturen|alle facturen|bekijk meer|show more|load more";
    static final String[] PERIODE = {"jaar", "maanden", "vanaf", "alles"};

    /** Welke facturen: {soort: jaar (dit jaar, vanaf 1 januari; standaard) | maanden (de laatste n) | vanaf (datum) | alles}. */
    static JSONObject periode(JSONObject in) throws Exception {
        JSONObject p = new JSONObject();
        String soort = in == null ? "jaar" : in.optString("soort", "jaar");
        if (!in(soort, PERIODE)) throw new Exception("Kies welke facturen (periode)");
        p.put("soort", soort);
        if ("maanden".equals(soort)) p.put("maanden", Math.max(1, Math.min(120, in.optInt("maanden", 12))));
        if ("vanaf".equals(soort)) {
            String d = in.optString("vanaf", "");
            if (!d.matches("(19|20)\\d{2}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])")) throw new Exception("Vul de datum in vanaf wanneer facturen opgehaald worden");
            p.put("vanaf", d);
        }
        return p;
    }

    /**
     * Vanaf welk moment facturen tellen (ms; 0 = alles). Zonder keuze: dit jaar (vanaf 1 januari).
     */
    static long vanaf(JSONObject lev, long nu, java.util.TimeZone tz) {
        JSONObject p = lev.optJSONObject("periode");
        String soort = p == null ? "jaar" : p.optString("soort", "jaar");
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(nu);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        switch (soort) {
            case "alles": return 0;
            case "maanden": c.add(java.util.Calendar.MONTH, -p.optInt("maanden", 12)); return c.getTimeInMillis();
            case "vanaf": {
                String d = p.optString("vanaf", "");
                if (!d.matches("\\d{4}-\\d{2}-\\d{2}")) return 0;
                c.set(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(5, 7)) - 1, Integer.parseInt(d.substring(8, 10)));
                return c.getTimeInMillis();
            }
            default: c.set(java.util.Calendar.DAY_OF_YEAR, 1); return c.getTimeInMillis();
        }
    }

    /**
     * Moet deze keer de hele periode opgehaald worden? De eerste keer, of als de periode eerder begint dan wat al eens
     * helemaal is opgehaald ("gedekt"; bijv. na het ruimer zetten van de periode, of bij leveranciers van vóór de periode-keuze).
     * Daarna alleen wat sinds de vorige keer is binnengekomen. Zuiver (getest).
     */
    static boolean heelPeriode(JSONObject lev, JSONObject t, long nu, java.util.TimeZone tz) {
        if (t == null || t.optLong("lastOk") <= 0 || !t.has("gedekt")) return true;
        long v = vanaf(lev, nu, tz), g = t.optLong("gedekt");
        return v < g - 86_400_000L; // ook "alles" (0) terwijl er maar een deel gedekt is
    }

    /** Aan de beurt omdat de periode nog niet helemaal is opgehaald (hooguit om de "opnieuw" uur proberen). */
    static boolean inhalen(JSONObject lev, JSONObject t, long nu, java.util.TimeZone tz) {
        if ("uit".equals(lev.optString("schema", "maand")) || !heelPeriode(lev, t, nu, tz)) return false;
        return nu - t.optLong("lastTry") >= lev.optInt("opnieuw", 3) * 3_600_000L;
    }

    /** Een unieke id (bij twee keer dezelfde leverancier: -2, -3 …). */
    static String uniekId(String basis, Set<String> bezet) {
        String id = veiligId(basis);
        if (!bezet.contains(id)) return id;
        for (int n = 2; ; n++) { String t = id + "-" + n; if (!bezet.contains(t)) return t; }
    }

    /** De inlogcode uit een sms of mail. Met een eigen patroon: groep 1 (of de hele treffer). */
    static String vindCode(String tekst, String patroon) {
        if (tekst == null) return null;
        if (patroon != null && !patroon.isEmpty()) {
            try {
                Matcher m = Pattern.compile(patroon).matcher(tekst);
                if (m.find()) return (m.groupCount() > 0 && m.group(1) != null ? m.group(1) : m.group()).trim();
            } catch (Exception ignored) { }
            return null;
        }
        Matcher m = Pattern.compile("(?i)(?:code|verificatie|toegangscode|wachtwoord)[^0-9]{0,40}(\\d{4,8})\\b").matcher(tekst);
        if (m.find()) return m.group(1);
        m = Pattern.compile("\\b(\\d{6})\\b").matcher(tekst);
        return m.find() ? m.group(1) : null;
    }

    /** Bestandsnaam uit de Content-Disposition (filename* gaat voor). */
    static String naamUitDisposition(String cd) {
        if (cd == null) return "";
        Matcher m = Pattern.compile("(?i)filename\\*\\s*=\\s*(?:UTF-8|ISO-8859-1)?'[^']*'([^;]+)").matcher(cd);
        if (m.find()) { try { return java.net.URLDecoder.decode(m.group(1).trim().replace("+", "%2B"), "UTF-8"); } catch (Exception ignored) { } }
        m = Pattern.compile("(?i)filename\\s*=\\s*\"([^\"]*)\"").matcher(cd);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("(?i)filename\\s*=\\s*([^;]+)").matcher(cd);
        return m.find() ? m.group(1).trim() : "";
    }

    /** Nette, unieke bestandsnaam voor een factuur: "KPN Zakelijk Factuur 2026-10.pdf". */
    static String bestandsnaam(String gegeven, String url, String levNaam, String sha) {
        String n = gegeven == null ? "" : gegeven.trim();
        if (n.isEmpty() && url != null && url.matches("(?i)https?://.*")) {
            String p = url.replaceAll("[?#].*$", "");
            n = p.substring(p.lastIndexOf('/') + 1);
            try { n = java.net.URLDecoder.decode(n.replace("+", "%2B"), "UTF-8"); } catch (Exception ignored) { }
        }
        n = veiligeNaam(n).replaceAll("\\s+", " ").trim();
        if (n.toLowerCase(Locale.ROOT).endsWith(".pdf")) n = n.substring(0, n.length() - 4).trim();
        if (n.isEmpty() || !n.matches(".*[\\p{L}\\p{N}].*") || n.matches("[0-9a-f-]{24,}")) n = "factuur " + sha.substring(0, 8);
        String lev = veiligeNaam(levNaam == null ? "" : levNaam).trim();
        if (!lev.isEmpty() && !n.toLowerCase(Locale.ROOT).startsWith(lev.toLowerCase(Locale.ROOT))
                && !n.toLowerCase(Locale.ROOT).startsWith(lev.split(" ")[0].toLowerCase(Locale.ROOT))) n = lev + " " + n;
        if (n.startsWith(".")) n = "_" + n.substring(1);
        if (n.length() > 120) n = n.substring(0, 120).trim();
        return n + ".pdf";
    }

    // ================= planning: vast moment per dag, week of maand =================

    /** Het geplande moment op of vóór nu (volgende = false) of erna (true). Long.MAX_VALUE bij "uit". */
    static long gepland(JSONObject lev, long nu, boolean volgende, java.util.TimeZone tz) {
        String schema = lev.optString("schema", "maand");
        if ("uit".equals(schema)) return volgende ? Long.MAX_VALUE : Long.MIN_VALUE;
        String[] hm = lev.optString("tijd", "08:00").split(":");
        int uur = Integer.parseInt(hm[0]), min = Integer.parseInt(hm[1]);
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(nu);
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        c.set(java.util.Calendar.HOUR_OF_DAY, uur); c.set(java.util.Calendar.MINUTE, min);
        int veld;
        if ("maand".equals(schema)) {
            // Dag 29–31 in een kortere maand: de laatste dag. Per maand opnieuw bepalen.
            int dag = lev.optInt("dag", 5);
            c.set(java.util.Calendar.DAY_OF_MONTH, 1);
            c.set(java.util.Calendar.DAY_OF_MONTH, Math.min(dag, c.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)));
            if (volgende) { while (c.getTimeInMillis() <= nu) { c.set(java.util.Calendar.DAY_OF_MONTH, 1); c.add(java.util.Calendar.MONTH, 1); c.set(java.util.Calendar.DAY_OF_MONTH, Math.min(dag, c.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))); } }
            else { while (c.getTimeInMillis() > nu) { c.set(java.util.Calendar.DAY_OF_MONTH, 1); c.add(java.util.Calendar.MONTH, -1); c.set(java.util.Calendar.DAY_OF_MONTH, Math.min(dag, c.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))); } }
            return c.getTimeInMillis();
        }
        else if ("week".equals(schema)) {
            int wd = lev.optInt("weekdag", 1); // 1 = maandag … 7 = zondag
            int nuWd = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
            c.add(java.util.Calendar.DAY_OF_MONTH, wd - nuWd);
            veld = java.util.Calendar.WEEK_OF_YEAR;
        } else veld = java.util.Calendar.DAY_OF_MONTH;
        // c is nu het moment in deze periode; schuif naar de juiste kant van "nu"
        if (volgende) { while (c.getTimeInMillis() <= nu) c.add(veld, 1); }
        else { while (c.getTimeInMillis() > nu) c.add(veld, -1); }
        return c.getTimeInMillis();
    }

    /**
     * Aan de beurt: het laatste geplande moment is voorbij en sindsdien is er nog niet gelukt.
     * Mislukt na het geplande moment: na "opnieuw" uur opnieuw (melding alleen bij de eerste fout van de periode).
     */
    static boolean aanDeBeurt(JSONObject lev, long lastOk, long lastTry, long nu, java.util.TimeZone tz) {
        if ("uit".equals(lev.optString("schema", "maand"))) return false;
        long g = gepland(lev, nu, false, tz);
        if (lastOk >= g) return false;
        return !(lastTry >= g && nu - lastTry < lev.optInt("opnieuw", 3) * 3_600_000L);
    }


    /** Een naam die één factuur aanwijst (met een nummer of datum), niet "factuur.pdf" of onze eigen noodnaam. */
    static boolean uniekeNaam(String n) {
        return n != null && !n.matches("(?i).*factuur [0-9a-f]{8}\\.pdf") && n.matches(".*\\d{4,}.*|.*\\d+[-_.]\\d+.*");
    }

    static boolean isPdf(byte[] b) {
        if (b == null || b.length < 5) return false;
        for (int i = 0; i < Math.min(1024, b.length - 4); i++)
            if (b[i] == '%' && b[i + 1] == 'P' && b[i + 2] == 'D' && b[i + 3] == 'F' && b[i + 4] == '-') return true;
        return false;
    }

    static String sha(byte[] b) throws Exception { return AppData.hex(MessageDigest.getInstance("SHA-256").digest(b)); }

    // ================= sjablonen =================

    static JSONArray sjablonen(Context c) {
        try (InputStream in = c.getAssets().open(ASSET)) {
            return new JSONObject(new String(lees(in, 1_000_000), StandardCharsets.UTF_8)).getJSONArray("sjablonen");
        } catch (Exception e) { return new JSONArray(); }
    }

    // ================= opslag =================

    static JSONArray leveranciers(Context c) {
        synchronized (LOCK) {
            try { return lijstUit(JsonFileStore.read(levFile(c), Facturen::checkLijst)); }
            catch (Exception e) { return null; }
        }
    }

    /** De lijst uit het bestand; nog geen bestand ("{}") = een lege lijst, niet "onleesbaar". */
    static JSONArray lijstUit(String tekst) throws Exception {
        JSONObject o = new JSONObject(tekst);
        if (o.length() == 0) return new JSONArray();
        JSONArray l = o.optJSONArray("lijst");
        if (l == null) throw new Exception("onleesbaar");
        return l;
    }

    static JSONArray lijst(Context c) { JSONArray l = leveranciers(c); return l == null ? new JSONArray() : l; }

    static void checkLijst(String t) throws Exception { JSONObject o = new JSONObject(t); if (o.length() > 0 && o.optJSONArray("lijst") == null) throw new Exception("onleesbaar"); }

    static JSONObject leverancier(Context c, String id) {
        JSONArray l = lijst(c);
        for (int i = 0; i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (o != null && o.optString("id").equals(id)) return o; }
        return null;
    }

    /** Bewaren (nieuw of bijwerken). Geeft de id terug. Een leeg wachtwoord laat het oude staan. */
    static String bewaar(Context c, JSONObject in, String wachtwoord) throws Exception {
        synchronized (LOCK) {
            JSONArray oud = leveranciers(c);
            if (oud == null) throw new Exception("De opgeslagen leveranciers zijn niet leesbaar");
            String vorigeId = in.optString("id", "");
            JSONObject o = normaal(in);
            JSONArray nieuw = new JSONArray();
            Set<String> bezet = new java.util.HashSet<>();
            boolean vervangen = false;
            for (int i = 0; i < oud.length(); i++) {
                JSONObject x = oud.getJSONObject(i);
                if (!vorigeId.isEmpty() && x.optString("id").equals(vorigeId)) { vervangen = true; continue; }
                bezet.add(x.optString("id"));
            }
            if (!vervangen) o.put("id", uniekId(o.getString("id"), bezet));
            else o.put("id", vorigeId);
            for (int i = 0; i < oud.length(); i++) {
                JSONObject x = oud.getJSONObject(i);
                if (vervangen && x.optString("id").equals(vorigeId)) nieuw.put(o); else nieuw.put(x);
            }
            if (!vervangen) { if (nieuw.length() >= 30) throw new Exception("Hooguit 30 leveranciers"); nieuw.put(o); }
            JsonFileStore.write(levFile(c), new JSONObject().put("lijst", nieuw).toString(), Facturen::checkLijst);
            if (wachtwoord != null && !wachtwoord.isEmpty()) zetGeheim(c, "lev_" + o.getString("id"), wachtwoord);
            return o.getString("id");
        }
    }

    static void verwijder(Context c, String id) throws Exception {
        synchronized (LOCK) {
            JSONArray oud = leveranciers(c);
            if (oud == null) throw new Exception("De opgeslagen leveranciers zijn niet leesbaar");
            JSONArray nieuw = new JSONArray();
            for (int i = 0; i < oud.length(); i++) if (!oud.getJSONObject(i).optString("id").equals(id)) nieuw.put(oud.getJSONObject(i));
            JsonFileStore.write(levFile(c), new JSONObject().put("lijst", nieuw).toString(), Facturen::checkLijst);
            geheim(c).edit().remove("lev_" + id).apply();
            JSONObject t = toestand(c); t.remove(id); bewaarToestand(c, t);
            // De facturen in de app gaan mee weg; de kopieën in je backup-map en in Eigen Cloud blijven staan (en in het dossier).
            FacturenDossier.appWeg(c, id);
            File d = pdfDir(c, id);
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            d.delete();
        }
    }

    // ----- toestand: per leverancier wanneer, wat, en welke facturen al verwerkt zijn -----

    static JSONObject toestand(Context c) {
        synchronized (LOCK) {
            try { return new JSONObject(JsonFileStore.read(toestandFile(c), t -> new JSONObject(t))); } catch (Exception e) { return new JSONObject(); }
        }
    }

    static void bewaarToestand(Context c, JSONObject t) {
        synchronized (LOCK) {
            try { JsonFileStore.write(toestandFile(c), t.toString(), x -> new JSONObject(x)); } catch (Exception ignored) { }
        }
    }

    static JSONObject toestand(Context c, String id) {
        JSONObject t = toestand(c).optJSONObject(id);
        return t == null ? new JSONObject() : t;
    }

    /** Eén regel in de geschiedenis van een leverancier (nieuwste eerst, hooguit 40). */
    static void logRun(Context c, String id, JSONObject regel) {
        synchronized (LOCK) {
            try {
                JSONObject all = toestand(c);
                JSONObject t = all.optJSONObject(id);
                if (t == null) t = new JSONObject();
                JSONArray oud = t.optJSONArray("log"), nieuw = new JSONArray().put(regel);
                for (int i = 0; oud != null && i < oud.length() && nieuw.length() < 40; i++) nieuw.put(oud.get(i));
                t.put("log", nieuw);
                all.put(id, t);
                bewaarToestand(c, all);
            } catch (Exception ignored) { }
        }
    }

    static void werkBij(Context c, String id, JSONObject wijziging) {
        synchronized (LOCK) {
            try {
                JSONObject all = toestand(c);
                JSONObject t = all.optJSONObject(id);
                if (t == null) t = new JSONObject();
                for (Iterator<String> it = wijziging.keys(); it.hasNext(); ) { String k = it.next(); t.put(k, wijziging.get(k)); }
                all.put(id, t);
                bewaarToestand(c, all);
            } catch (Exception ignored) { }
        }
    }

    // ----- geheimen -----

    static void zetGeheim(Context c, String k, String v) throws Exception {
        geheim(c).edit().putString(k, AppData.seal(Secure.deviceKey(), v)).apply();
    }

    static String geheim(Context c, String k) {
        String s = geheim(c).getString(k, null);
        if (s == null) return null;
        try { return AppData.open(Secure.deviceKey(), s); } catch (Exception e) { return null; }
    }

    static boolean heeftGeheim(Context c, String k) { return geheim(c).contains(k); }

    // ================= ontvangen PDF's verwerken =================

    static final class Pdf {
        final byte[] data; final String naam; final String sha;
        /** Eigen submap (bijv. per afzender bij facturen uit de mail), of null = die van de leverancier. */
        final String sub;
        /** Waar hij vandaan komt, voor het dossier: website {url, site} of mail {afzender, onderwerp, maildatum, mailId}. */
        final JSONObject bron;
        Pdf(byte[] d, String n, String s) { this(d, n, s, null, null); }
        Pdf(byte[] d, String n, String s, String sub) { this(d, n, s, sub, null); }
        Pdf(byte[] d, String n, String s, String sub, JSONObject bron) { data = d; naam = n; sha = s; this.sub = sub == null || sub.trim().isEmpty() ? null : veiligeNaam(sub).replace('.', '_').trim(); this.bron = bron; }
    }

    /**
     * Vanaf welke factuurdatum bewaren: de periode bij de eerste keer (of na "opnieuw"); daarna ook wat sinds de vorige
     * keer is binnengekomen (anders valt bij de jaarwisseling een factuur van eind december weg). 0 = alles.
     */
    static long grens(JSONObject lev, long lastOk, long nu, java.util.TimeZone tz) {
        long v = vanaf(lev, nu, tz);
        if (v <= 0 || lastOk <= 0) return v;
        return Math.min(v, lastOk - 45 * 86_400_000L);
    }

    /** Staat de factuurdatum (herkend uit de PDF of de naam) vóór de grens? Onbekende datum: nee (dan bewaren). */
    static boolean voorPeriode(long grens, File f, String naam) {
        if (grens <= 0) return false;
        String d = FacturenDossier.herken(FacturenDossier.tekst(f) + "\n" + naam).optString("datum");
        if (d.isEmpty()) return false;
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return d.compareTo(iso.format(new java.util.Date(grens))) < 0;
    }

    /** Nieuwe facturen bewaren en afleveren; geeft {nieuw, gevonden, namen, fouten} terug. */

    static JSONObject verwerk(Context c, JSONObject lev, java.util.Collection<Pdf> pdfs) throws Exception {
        String id = lev.getString("id");
        JSONObject t = toestand(c, id);
        JSONObject verwerkt = t.optJSONObject("verwerkt");
        if (verwerkt == null) verwerkt = new JSONObject();
        JSONArray namen = new JSONArray(), fouten = new JSONArray();
        int nieuw = 0, buiten = 0;
        long nu0 = System.currentTimeMillis();
        boolean heel = heelPeriode(lev, t, nu0, java.util.TimeZone.getDefault());
        // De hele periode: wat eerder buiten de (toen kleinere) periode viel, opnieuw bekijken
        if (heel) for (Iterator<String> it = verwerkt.keys(); it.hasNext(); ) { JSONObject r = verwerkt.optJSONObject(it.next()); if (r != null && r.optBoolean("buiten")) it.remove(); }
        long grens = grens(lev, heel ? 0 : t.optLong("lastOk"), nu0, java.util.TimeZone.getDefault());
        java.util.List<String> nieuweSha = new java.util.ArrayList<>();
        File d = pdfDir(c, id);
        if (!d.isDirectory() && !d.mkdirs()) throw new Exception("Opslagmap maken lukt niet");
        // Al eerder: alleen opnieuw afleveren wat toen mislukte (backup-map of Eigen Cloud)
        for (Pdf p : pdfs) {
            JSONObject rec = verwerkt.optJSONObject(p.sha);
            if (rec == null || rec.optBoolean("buiten")) continue;
            File f = new File(d, rec.optString("n"));
            if (!f.isFile()) continue;
            if (aflever(c, lev, rec.optString("n"), f, p, rec, fouten)) werkBij(c, id, new JSONObject().put("verwerkt", verwerkt));
        }
        Set<String> namenGehad = new java.util.HashSet<>();
        for (Iterator<String> it = verwerkt.keys(); it.hasNext(); ) { JSONObject r = verwerkt.optJSONObject(it.next()); if (r != null && !r.optString("o").isEmpty()) namenGehad.add(r.optString("o")); }
        for (Pdf p : pdfs) {
            if (verwerkt.has(p.sha)) continue;
            // Zelfde factuur, andere bytes (sommige websites zetten het downloadmoment in de PDF): op naam herkennen
            boolean eigenNaam = uniekeNaam(p.naam);
            if (eigenNaam && namenGehad.contains(p.naam)) continue;
            String naam = p.naam;
            File f = new File(d, naam);
            for (int n = 2; f.exists(); n++) { naam = p.naam.replaceAll("\\.pdf$", "") + " (" + n + ").pdf"; f = new File(d, naam); }
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(p.data); o.getFD().sync(); }
            if (!"mail".equals(lev.optString("soort")) && voorPeriode(grens, f, FacturenDossier.naamZonderSha(p.naam, p.sha))) { // van vóór de gekozen periode: niet bewaren
                f.delete();
                verwerkt.put(p.sha, new JSONObject().put("n", "").put("o", eigenNaam ? p.naam : "").put("t", System.currentTimeMillis()).put("buiten", true));
                if (eigenNaam) namenGehad.add(p.naam);
                buiten++;
                werkBij(c, id, new JSONObject().put("verwerkt", verwerkt));
                continue;
            }
            FacturenDossier.nieuw(c, lev, p, naam, f);
            JSONObject rec = new JSONObject().put("n", naam).put("o", eigenNaam ? p.naam : "").put("t", System.currentTimeMillis());
            if (p.sub != null) rec.put("s", p.sub);
            aflever(c, lev, naam, f, p, rec, fouten);
            verwerkt.put(p.sha, rec);
            if (eigenNaam) namenGehad.add(p.naam);
            namen.put(naam);
            nieuweSha.add(p.sha);
            nieuw++;
            werkBij(c, id, new JSONObject().put("verwerkt", verwerkt)); // na elke factuur: bij een onderbreking niet dubbel
        }
        // Tellen in facturen, niet in documenten: bijlagen en specificaties horen bij hun factuur
        int facturen = nieuw == 0 ? 0 : FacturenDossier.aantal(FacturenDossier.alle(c), nieuweSha);
        return new JSONObject().put("nieuw", facturen).put("documenten", nieuw).put("gevonden", pdfs.size()).put("buiten", buiten).put("namen", namen).put("fouten", fouten)
                .put("heel", heel).put("vanaf", vanaf(lev, nu0, java.util.TimeZone.getDefault()));
    }

    /** Naar elke gekozen plek die nog niet gelukt is (backup-map, map op de telefoon, Google Drive, Eigen Cloud). Geeft true als er iets bij kwam. */
    static boolean aflever(Context c, JSONObject lev, String naam, File f, Pdf p, JSONObject rec, JSONArray fouten) throws Exception {
        boolean bij = false;
        // Eigen map per bestand (afzender bij facturen uit de mail): in de map van de leverancier
        String sub = rec.optString("s", "").isEmpty() ? lev.optString("submap", "Facturen") : lev.optString("submap", "Facturen") + "/" + rec.optString("s");
        // Elke plek ook in het dossier, met waar hij precies staat
        if (lev.optBoolean("map", true) && !rec.optBoolean("map")) {
            try { Uri u = naarBoom(c, WaBackup.destUri(c), "kies eerst een backup-map (Instellingen)", MAP + "/" + sub, naam, f, false); rec.put("map", true); bij = true;
                  FacturenDossier.plek(c, p.sha, "map", new JSONObject().put("pad", "Backup-map/" + MAP + "/" + sub + "/" + naam).put("uri", u == null ? "" : u.toString())); }
            catch (Exception e) { fouten.put("Backup-map: " + kort(e)); }
        }
        if (lev.optBoolean("lokaal", false) && !rec.optBoolean("lok")) {
            try { Uri u = naarBoom(c, lokaleMap(c), "kies eerst een map op je telefoon (Facturen → Opslaan)", sub, naam, f, false); rec.put("lok", true); bij = true;
                  FacturenDossier.plek(c, p.sha, "lok", new JSONObject().put("pad", boomNaam(lokaleMap(c)) + "/" + sub + "/" + naam).put("uri", u == null ? "" : u.toString())); }
            catch (Exception e) { fouten.put("Map op je telefoon: " + kort(e)); }
        }
        if (lev.optBoolean("drive", false) && !rec.optBoolean("gd")) {
            try { String id = FacturenDrive.upload(c, sub, naam, p.data); rec.put("gd", true); bij = true;
                  FacturenDossier.plek(c, p.sha, "gd", new JSONObject().put("pad", "Google Drive/" + MAP + "/" + sub + "/" + naam).put("id", id)
                          .put("link", id.isEmpty() ? "" : "https://drive.google.com/file/d/" + id + "/view")); }
            catch (Exception e) { fouten.put("Google Drive: " + kort(e)); }
        }
        if (lev.optBoolean("eigenCloud", false) && !rec.optBoolean("ec")) {
            try { String aan = naarEigenCloud(c, lev, naam, p); rec.put("ec", true); bij = true;
                  FacturenDossier.plek(c, p.sha, "ec", new JSONObject().put("pad", "Eigen Cloud, mail aan " + aan).put("aan", aan)); }
            catch (Exception e) { fouten.put("Eigen Cloud: " + kort(e)); }
        }
        return bij;
    }

    /** De zelf gekozen map op de telefoon (bijv. Documenten/Facturen), of null. */
    static Uri lokaleMap(Context c) {
        String s = prefs(c).getString("lokaal", null);
        return s == null ? null : Uri.parse(s);
    }

    /** Toegang tot een vroeger gekozen map teruggeven, maar nooit als de backup-map dezelfde map is. */
    static void laatMapLos(Context c, Uri u) {
        if (u == null) return;
        Uri backup = WaBackup.destUri(c), nu = lokaleMap(c);
        if (u.equals(backup) || u.equals(nu)) return;
        try {
            for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions())
                if (p.getUri().equals(u)) { c.getContentResolver().releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); break; }
        } catch (Exception ignored) { }
    }

    static String kort(Throwable e) {
        String m = e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException
                ? "geen verbinding" : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 200 ? m.substring(0, 200) + "…" : m;
    }

    /** Kopie in een gekozen map (backup-map: Facturen/&lt;leverancier&gt;; eigen map: &lt;leverancier&gt;). */
    /** Leesbare naam van een gekozen map ("Documents/Facturen"). */
    static String boomNaam(Uri u) {
        try {
            String id = DocumentsContract.getTreeDocumentId(u);
            int i = id.indexOf(':');
            String rel = i < 0 ? id : id.substring(i + 1), vol = i < 0 ? "" : id.substring(0, i);
            return ("primary".equals(vol) ? "" : vol + ": ") + (rel.isEmpty() ? "hoofdmap" : rel);
        } catch (Exception e) { return "gekozen map"; }
    }

    /** Bestand in een gekozen map zetten (rel "" = de map zelf). vervang = een bestaand bestand overschrijven (bijv. dossier.csv). */
    static Uri naarBoom(Context c, Uri tree, String geenMap, String rel, String naam, File f, boolean vervang) throws Exception {
        if (tree == null) throw new Exception(geenMap);
        if (!WaBackup.hasGrant(c.getContentResolver(), tree)) throw new Exception("geen toegang meer tot de map: kies hem opnieuw");
        WaBackup.Dest dest = new WaBackup.Dest(c.getContentResolver(), tree);
        WaBackup.DestDir map = rel == null || rel.isEmpty() ? dest.root() : dest.dir(rel, true);
        WaBackup.Child er = map.kids.get(naam);
        if (er != null && !vervang) return DocumentsContract.buildDocumentUriUsingTree(dest.tree, er.docId); // staat er al
        if (er != null) { try { DocumentsContract.deleteDocument(dest.cr, DocumentsContract.buildDocumentUriUsingTree(dest.tree, er.docId)); } catch (Exception ignored) { } map.kids.remove(naam); }
        Uri tmp = WaBackup.create(dest, map.uri, naam.endsWith(".csv") ? "text/csv" : "application/pdf", naam + ".part");
        if (tmp == null) throw new Exception("bestand maken lukt niet");
        boolean klaar = false;
        try {
            try (InputStream in = new java.io.FileInputStream(f); OutputStream out = dest.cr.openOutputStream(tmp, "w")) {
                if (out == null) throw new Exception("bestand openen lukt niet");
                byte[] buf = new byte[32768]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            Uri af = WaBackup.rename(dest, tmp, naam);
            if (af == null) throw new Exception("bestand afronden lukt niet");
            klaar = true;
            return af;
        } finally { if (!klaar) try { DocumentsContract.deleteDocument(dest.cr, tmp); } catch (Exception ignored) { } }
    }

    // ================= Eigen Cloud =================

    /** Verbinding met je eigen Eigen Cloud (zelfde API als de webmail): inloggen, mail lezen en versturen. */
    static final class Ec implements AutoCloseable {
        final String url; String token;
        Ec(String url) { this.url = url.replaceAll("/+$", ""); }

        static Ec open(Context c) throws Exception {
            SharedPreferences p = prefs(c);
            String url = p.getString("ecUrl", ""), user = p.getString("ecGebruiker", ""), pw = geheim(c, "ec");
            if (url.isEmpty() || user.isEmpty() || pw == null) throw new Exception("Koppel eerst Eigen Cloud (bovenaan in Facturen)");
            Ec ec = new Ec(url);
            JSONObject r = ec.vraag("POST", "/api/login", new JSONObject().put("user", user).put("password", pw), null);
            ec.token = r.optString("token", "");
            if (ec.token.isEmpty()) throw new Exception("Eigen Cloud gaf geen toegang");
            return ec;
        }

        JSONObject vraag(String methode, String pad, Object body, String idem) throws Exception {
            HttpURLConnection h = (HttpURLConnection) new URL(url + pad).openConnection();
            try {
                h.setConnectTimeout(20_000); h.setReadTimeout(60_000); h.setRequestMethod(methode);
                h.setInstanceFollowRedirects(false);
                h.setRequestProperty("Accept", "application/json");
                if (token != null) h.setRequestProperty("Authorization", "Bearer " + token);
                if (idem != null) h.setRequestProperty("Idempotency-Key", idem);
                if (body != null) {
                    byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                    h.setDoOutput(true); h.setRequestProperty("Content-Type", "application/json"); h.setFixedLengthStreamingMode(b.length);
                    try (OutputStream o = h.getOutputStream()) { o.write(b); }
                }
                int code = h.getResponseCode();
                InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
                String t = in == null ? "" : new String(lees(in, 5_000_000), StandardCharsets.UTF_8);
                if (code >= 300 && code < 400) throw new Exception("Eigen Cloud stuurt door naar een ander adres: controleer het adres");
                Object v = t.trim().isEmpty() ? new JSONObject() : new org.json.JSONTokener(t).nextValue();
                if (code >= 400) {
                    String fout = v instanceof JSONObject ? ((JSONObject) v).optString("error", "") : "";
                    throw new Exception(fout.isEmpty() ? "Eigen Cloud antwoordt met fout " + code : fout);
                }
                return v instanceof JSONObject ? (JSONObject) v : new JSONObject().put("lijst", v);
            } finally { h.disconnect(); }
        }

        @Override public void close() {
            if (token == null) return;
            try { vraag("POST", "/api/logout", null, null); } catch (Exception ignored) { }
            token = null;
        }
    }

    static String naarEigenCloud(Context c, JSONObject lev, String naam, Pdf p) throws Exception {
        String aan = prefs(c).getString("ecAdres", "");
        if (aan.isEmpty()) aan = prefs(c).getString("ecGebruiker", "");
        try (Ec ec = Ec.open(c)) {
            JSONObject mail = new JSONObject().put("to", new JSONArray().put(aan))
                    .put("subject", "Factuur " + lev.optString("naam") + " – " + naam.replaceAll("\\.pdf$", ""))
                    .put("text", "Nieuwe factuur van " + lev.optString("naam") + ", opgehaald door Tools op " + Regio.fmt(c, "d MMMM yyyy HH:mm", System.currentTimeMillis()) + ".\n\nBestand: " + naam + "\n")
                    .put("attachments", new JSONArray().put(new JSONObject().put("filename", naam).put("contentType", "application/pdf")
                            .put("base64", android.util.Base64.encodeToString(p.data, android.util.Base64.NO_WRAP))));
            JSONObject r = ec.vraag("POST", "/api/mail/send", mail, "tools-factuur-" + p.sha.substring(0, 40));
            if (!r.optBoolean("ok", false)) throw new Exception("niet verstuurd");
            return aan;
        }
    }

    /** Koppeling testen: inloggen en weer uitloggen. */
    static void testEigenCloud(Context c) throws Exception { try (Ec ec = Ec.open(c)) { ec.vraag("GET", "/api/mail/folders", null, null); } }

    // ================= inlogcode =================

    /** Wacht (hooguit 4 minuten) op de inlogcode van de leverancier, per sms of uit Eigen Cloud. */
    static String wachtOpCode(Context c, JSONObject lev, long sinds, java.util.concurrent.atomic.AtomicBoolean stop, Set<String> gezien) throws Exception {
        JSONObject ci = lev.optJSONObject("code");
        String bron = ci == null ? "geen" : ci.optString("bron", "geen");
        String afz = ci == null ? "" : ci.optString("afzender", ""), pat = ci == null ? "" : ci.optString("patroon", "");
        long tot = System.currentTimeMillis() + 4 * 60_000L;
        Ec ec = "eigencloud".equals(bron) ? Ec.open(c) : null;
        try {
            while (System.currentTimeMillis() < tot && !stop.get()) {
                String code = "sms".equals(bron) ? smsCode(c, sinds, afz, pat, gezien) : "eigencloud".equals(bron) ? ecCode(ec, sinds, afz, pat, gezien) : null;
                if (code != null) return code;
                if (!"sms".equals(bron) && !"eigencloud".equals(bron)) throw new Exception("Geen bron voor de inlogcode ingesteld");
                Thread.sleep(4000);
            }
        } finally { if (ec != null) ec.close(); }
        if (stop.get()) throw new Exception("Gestopt");
        throw new Exception("Geen inlogcode ontvangen (4 minuten gewacht)" + ("sms".equals(bron) ? " per sms" : " in Eigen Cloud"));
    }

    static boolean komtVan(String wie, String tekst, String afz) {
        if (afz == null || afz.isEmpty()) return true;
        String a = afz.toLowerCase(Locale.ROOT);
        return (wie != null && wie.toLowerCase(Locale.ROOT).contains(a)) || (tekst != null && tekst.toLowerCase(Locale.ROOT).contains(a));
    }

    static String smsCode(Context c, long sinds, String afz, String pat, Set<String> gezien) throws Exception {
        if (c.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new Exception("Geef Tools toegang tot sms om de inlogcode te lezen");
        try (Cursor q = c.getContentResolver().query(Uri.parse("content://sms/inbox"), new String[]{"_id", "address", "body", "date"},
                "date >= ?", new String[]{String.valueOf(sinds - 60_000L)}, "date DESC")) {
            for (int n = 0; q != null && q.moveToNext() && n < 15; n++) {
                String id = q.getString(0), wie = q.getString(1), body = q.getString(2);
                if (!gezien.add("s" + id) || !komtVan(wie, body, afz)) continue;
                String code = vindCode(body, pat);
                if (code != null) return code;
            }
        }
        return null;
    }

    static String ecCode(Ec ec, long sinds, String afz, String pat, Set<String> gezien) throws Exception {
        JSONArray l = ec.vraag("GET", "/api/mail/messages?limit=25", null, null).optJSONArray("lijst");
        if (l == null) return null;
        for (int i = 0; i < l.length(); i++) {
            JSONObject m = l.getJSONObject(i);
            if (m.optLong("date") < sinds - 60_000L || !gezien.add("m" + m.optLong("id"))) continue;
            if (!komtVan(m.optString("from_addr") + " " + m.optString("from_name"), m.optString("subject"), afz)) continue;
            JSONObject v = ec.vraag("GET", "/api/mail/messages/" + m.optLong("id"), null, null);
            String code = vindCode(v.optString("subject") + "\n" + v.optString("body_text") + "\n" + v.optString("body_html").replaceAll("<[^>]+>", " "), pat);
            if (code != null) return code;
        }
        return null;
    }

    // ================= meldingen =================

    static void kanaal(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(CHANNEL, "Facturen", android.app.NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Als er nieuwe facturen zijn opgehaald, als inloggen bij een leverancier niet lukt, of als aanleveren niet lukt");
            nm.createNotificationChannel(ch);
        }
    }

    static void melding(Context c, JSONObject lev, JSONObject r) {
        try {
            String naam = lev.optString("naam"), id = lev.optString("id");
            String titel, tekst;
            String open = "facturen";
            if (r.optBoolean("ok")) {
                int n = r.optInt("nieuw");
                if (n == 0 && r.optJSONArray("fouten") != null && r.optJSONArray("fouten").length() == 0) return; // niets nieuws: stil
                if (n > 0 && !lev.optBoolean("meldNieuw", true)) return;
                if (n == 0 && !lev.optBoolean("meldFout", true)) return;
                titel = n == 0 ? naam + ": niet alles afgeleverd" : n + (n == 1 ? " nieuwe factuur van " : " nieuwe facturen van ") + naam;
                JSONArray f = r.optJSONArray("fouten");
                tekst = f != null && f.length() > 0 ? f.optString(0) : r.optJSONArray("namen") == null ? "" : r.optJSONArray("namen").optString(0);
            } else if (!lev.optBoolean("meldFout", true)) {
                return;
            } else if (r.optBoolean("koppelNodig")) {
                titel = naam + ": koppeling nodig";
                tekst = r.optString("msg");
            } else if (r.optBoolean("inlogNodig")) {
                titel = naam + ": inloggen nodig";
                tekst = "Tik om mee te kijken en zelf in te loggen. " + r.optString("msg");
                open = "facturen-meekijken:" + id;
            } else {
                titel = "Facturen ophalen bij " + naam + " mislukt";
                tekst = r.optString("msg");
            }
            kanaal(c);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, CHANNEL)) : new Notification.Builder(c);
            Intent i = new Intent(c, MainActivity.class).putExtra("open", open).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int nr = 4400 + (Math.abs(id.hashCode()) % 90);
            NotifCenter.post(c, nr, b.setSmallIcon(R.drawable.ic_backup).setContentTitle(titel).setContentText(tekst)
                    .setStyle(new Notification.BigTextStyle().bigText(tekst)).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, nr, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        } catch (Throwable ignored) { }
    }

    // ================= hulp =================

    static byte[] lees(InputStream in, long max) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n; long tot = 0;
        while ((n = in.read(b)) != -1) { tot += n; if (tot > max) throw new Exception("Bestand te groot"); o.write(b, 0, n); }
        return o.toByteArray();
    }

    /** De facturen in de app van één leverancier (nieuwste eerst). */
    static JSONArray bestanden(Context c, String id) throws Exception {
        File[] fs = pdfDir(c, id).listFiles((d, n) -> n.endsWith(".pdf"));
        JSONArray out = new JSONArray();
        if (fs == null) return out;
        java.util.Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        for (File f : fs) out.put(new JSONObject().put("naam", f.getName()).put("t", f.lastModified()).put("grootte", f.length()));
        return out;
    }

    static File bestand(Context c, String id, String naam) throws Exception {
        File d = pdfDir(c, id).getCanonicalFile(), f = new File(d, naam == null ? "" : naam).getCanonicalFile();
        if (!d.equals(f.getParentFile()) || !f.isFile() || !f.getName().endsWith(".pdf")) throw new Exception("Factuur niet gevonden");
        return f;
    }
}
