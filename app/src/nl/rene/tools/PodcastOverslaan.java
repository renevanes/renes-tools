package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stukken van een aflevering overslaan: reclame (hoofdstukken uit de feed, Podcasting 2.0) en per podcast een vast
 * stuk aan het begin (intro) en aan het eind (outro). Het rekenwerk is zuiver (getest in PodcastOverslaanTest); de
 * instellingen staan in podcasts/overslaan.json (gaat mee in de backup), de hoofdstukken in de cache (opnieuw op te halen).
 */
final class PodcastOverslaan {
    private PodcastOverslaan() { }

    static final String RECLAME = "reclame", INTRO = "intro";
    static final int MAX_SEC = 600, MAX_HOOFDSTUKKEN = 500, MAX_WOORDEN = 300;
    /** Zo dicht bij het eind van een stuk is het niet meer de moeite (en springen we niet heen en weer). */
    static final long MARGE_MS = 1500;

    /** Een stuk om over te slaan, in ms vanaf het begin. */
    static final class Stuk {
        final long van, tot;
        final String waarom, titel;
        Stuk(long van, long tot, String waarom, String titel) { this.van = van; this.tot = tot; this.waarom = waarom; this.titel = titel; }
    }

    /** Wat er voor één podcast geldt. */
    static final class Keuze {
        boolean reclame;
        int intro, outro; // seconden
    }

    // ---------- reclame herkennen aan de titel van een hoofdstuk ----------

    /*
     * Drie soorten:
     * - ZIN: overal in de titel reclame ("brought to you by", "commerciële boodschap", "midroll").
     * - STERK: een reclamewoord, maar alleen in een korte titel (hooguit 3 woorden), als laatste woord ("Een woord van onze
     *   sponsor") of vooraan met een scheidingsteken erna ("Sponsor: Bol"). Zo blijft "Reclame in de jaren 80" staan.
     * - KORT: woorden die alleen als hele titel reclame zijn ("Ad", "Commercial", "Promo"; niet "Ad Melkert").
     */
    private static final String WOORD = "reclames?|reclameblok\\w*|reclamespot\\w*|reclameboodschap\\w*|reclamepauze|advertenties?|advertorial|"
            + "advertisements?|sponsors?|sponsoren|sponsored|gesponsord\\w*|ad ?break|ad ?read|ad ?spot";
    private static final Pattern ZIN = Pattern.compile("\\b(brought to you by|commerciele (boodschap|onderbreking)|mid ?roll|pre ?roll|post ?roll)\\b");
    private static final Pattern STERK = Pattern.compile("\\b(" + WOORD + ")\\b");
    /* Vooraan met een dubbele punt, of met een los streepje (" - ", "–", "—") erna; "ads" en "promo" alleen met een dubbele punt */
    private static final Pattern VOORAAN = Pattern.compile("^[^\\p{L}\\p{N}]*((" + WOORD + ")\\s*(:|\\s[-–—]\\s|[–—])|(ads?|promos?)\\s*:)");
    /* "Een woord van onze sponsor", "Thanks to our sponsors": de sponsor van deze aflevering */
    private static final Pattern EIND = Pattern.compile("\\b(onze|our|deze|this|todays|today s)( \\w+){0,2} (sponsor|sponsors|sponsoren)$");
    private static final Pattern KORT = Pattern.compile("^(ad|ads|advert|adverts|advertising|commercial|commercials|promo|promos|commercial break)( \\d+)?$");

    static String normaal(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT);
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return t.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Extra woorden van Rene (komma of nieuwe regel ertussen), genormaliseerd; lege weg. */
    static List<String> woorden(String s) {
        List<String> l = new ArrayList<>();
        if (s == null) return l;
        for (String w : s.split("[,;\\n]")) {
            String n = normaal(w);
            if (!n.isEmpty() && n.length() <= 40 && !l.contains(n)) l.add(n);
            if (l.size() >= 30) break;
        }
        return l;
    }

    static boolean isReclame(String titel, List<String> extra) {
        String t = normaal(titel);
        if (t.isEmpty()) return false;
        if (ZIN.matcher(t).find() || KORT.matcher(t).matches()) return true;
        java.util.regex.Matcher m = STERK.matcher(t);
        if ((m.find() && t.split(" ").length <= 3) || EIND.matcher(t).find()) return true;
        String los = java.text.Normalizer.normalize(titel.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        if (VOORAAN.matcher(los).find()) return true;
        if (extra != null) for (String w : extra) if ((" " + t + " ").contains(" " + w + " ")) return true;
        return false;
    }

    // ---------- hoofdstukken (JSON chapters, Podcasting 2.0) ----------

    /**
     * De reclamestukken uit een hoofdstukkenbestand. Een hoofdstuk loopt tot zijn endTime, anders tot het volgende
     * hoofdstuk, anders tot het eind ({@code durMs}; 0 = onbekend: dan het laatste hoofdstuk niet). Opeenvolgende
     * reclamehoofdstukken worden één stuk. Onleesbaar = geen stukken.
     */
    static List<Stuk> reclameUit(String json, long durMs, List<String> extra) {
        List<Stuk> uit = new ArrayList<>();
        JSONArray a;
        try { a = new JSONObject(json).optJSONArray("chapters"); } catch (Exception e) { return uit; }
        if (a == null) return uit;
        List<long[]> tijden = new ArrayList<>();
        List<String> titels = new ArrayList<>();
        for (int i = 0; i < a.length() && i < MAX_HOOFDSTUKKEN; i++) {
            JSONObject h = a.optJSONObject(i);
            if (h == null || !h.optBoolean("toc", true)) continue; // "toc": false = stil hoofdstuk (bijv. een andere afbeelding), geen grens
            double st = h.optDouble("startTime", Double.NaN), et = h.optDouble("endTime", Double.NaN);
            if (Double.isNaN(st) || Double.isInfinite(st) || st < 0 || st > 1e6) continue;
            long eind = Double.isNaN(et) || Double.isInfinite(et) || et <= st || et > 1e6 ? -1 : Math.round(et * 1000);
            tijden.add(new long[]{ Math.round(st * 1000), eind });
            titels.add(h.optString("title", ""));
        }
        // Op volgorde van begin (sommige bestanden staan door elkaar); titels mee laten schuiven
        Integer[] volg = new Integer[tijden.size()];
        for (int i = 0; i < volg.length; i++) volg[i] = i;
        java.util.Arrays.sort(volg, (x, y) -> Long.compare(tijden.get(x)[0], tijden.get(y)[0]));
        for (int k = 0; k < volg.length; k++) {
            int i = volg[k];
            if (!isReclame(titels.get(i), extra)) continue;
            long van = tijden.get(i)[0], tot = tijden.get(i)[1];
            if (tot < 0) {
                // Tot het volgende hoofdstuk dat later begint
                for (int j = k + 1; j < volg.length && tot < 0; j++) if (tijden.get(volg[j])[0] > van) tot = tijden.get(volg[j])[0];
                if (tot < 0) tot = durMs > van ? durMs : -1;
            }
            if (durMs > 0) { if (van >= durMs) continue; tot = Math.min(tot, durMs); } // nooit voorbij het eind
            if (tot <= van) continue;
            String titel = Podcasts.FeedText.clip(titels.get(i), 120);
            Stuk vorige = uit.isEmpty() ? null : uit.get(uit.size() - 1);
            if (vorige != null && van <= vorige.tot + 1000) uit.set(uit.size() - 1, new Stuk(vorige.van, Math.max(vorige.tot, tot), RECLAME, vorige.titel));
            else uit.add(new Stuk(van, tot, RECLAME, titel));
        }
        return uit;
    }

    /** Alle stukken voor een aflevering: de intro (vanaf 0) en de reclame, op volgorde van begin. */
    static List<Stuk> stukken(Keuze k, List<Stuk> reclame, long durMs) {
        List<Stuk> l = new ArrayList<>();
        long intro = k == null ? 0 : Math.max(0, Math.min(MAX_SEC, k.intro)) * 1000L;
        // Bij een korte aflevering (of onbekende lengte niet groter dan de helft) geen intro overslaan
        if (intro > 0 && (durMs <= 0 || intro < durMs / 2)) l.add(new Stuk(0, intro, INTRO, ""));
        // Beslaat "reclame" meer dan de helft van de aflevering (een gesponsorde aflevering), dan niet: anders is hij meteen af
        if (k == null || k.reclame) for (Stuk s : reclame) if (durMs <= 0 || (s.van < durMs && s.tot - s.van <= durMs / 2)) l.add(s);
        Collections.sort(l, (x, y) -> Long.compare(x.van, y.van));
        return l;
    }

    /** Het eind dat overgeslagen wordt (ms vanaf het begin), of -1: alleen als de aflevering lang genoeg is. */
    static long outroVanaf(Keuze k, long durMs) {
        long outro = k == null ? 0 : Math.max(0, Math.min(MAX_SEC, k.outro)) * 1000L;
        if (outro <= 0 || durMs <= 0 || outro >= durMs / 2) return -1;
        return durMs - outro;
    }

    /**
     * Het stuk waar de plek nu in valt en dat overgeslagen moet worden, of null. Een stuk waarvan het begin in
     * {@code toegestaan} staat (Rene ging er zelf naar terug) slaat hij niet over; het laatste stukje (MARGE_MS) ook niet.
     */
    static Stuk nu(List<Stuk> stukken, long pos, java.util.Set<Long> toegestaan) {
        if (stukken == null) return null;
        for (Stuk s : stukken) {
            if (pos < s.van || pos >= s.tot - MARGE_MS) continue;
            if (toegestaan != null && toegestaan.contains(s.van)) continue;
            return s;
        }
        return null;
    }

    /** Het stuk waar een plek in valt (ook als het toegestaan is), of null. */
    static Stuk bij(List<Stuk> stukken, long pos) {
        if (stukken != null) for (Stuk s : stukken) if (pos >= s.van && pos < s.tot) return s;
        return null;
    }

    // ---------- instellingen (podcasts/overslaan.json) ----------

    static File bestand(Context c) { return new File(Podcasts.dir(c), "overslaan.json"); }

    static synchronized JSONObject lees(Context c) {
        JSONObject o = Podcasts.readJson(bestand(c));
        return schoon(o);
    }

    /** Alleen bekende velden met geldige waarden; ontbrekende krijgen hun standaard (reclame aan). */
    static JSONObject schoon(JSONObject in) {
        JSONObject o = new JSONObject();
        try {
            if (in == null) in = new JSONObject();
            o.put("versie", 1).put("reclame", in.optBoolean("reclame", true))
                    .put("woorden", Podcasts.FeedText.clip(in.optString("woorden", ""), MAX_WOORDEN));
            JSONObject per = new JSONObject(), inPer = in.optJSONObject("per");
            if (inPer != null) {
                java.util.Iterator<String> it = inPer.keys();
                while (it.hasNext() && per.length() < 500) {
                    String id = it.next();
                    JSONObject p = inPer.optJSONObject(id);
                    if (p == null || !geldigId(id)) continue;
                    JSONObject q = new JSONObject();
                    if (p.has("reclame") && !p.isNull("reclame")) q.put("reclame", p.optBoolean("reclame"));
                    int intro = sec(p.optInt("intro")), outro = sec(p.optInt("outro"));
                    if (intro > 0) q.put("intro", intro);
                    if (outro > 0) q.put("outro", outro);
                    if (q.length() > 0) per.put(id, q);
                }
            }
            o.put("per", per);
        } catch (Exception ignored) { }
        return o;
    }

    static int sec(int v) { return Math.max(0, Math.min(MAX_SEC, v)); }
    static boolean geldigId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,80}"); }

    static synchronized void bewaar(Context c, JSONObject o) throws Exception {
        Contacts.writeText(bestand(c), schoon(o).toString(), false);
    }

    /** De keuze voor één podcast: eigen waarden, anders de standaard. */
    static Keuze keuze(JSONObject inst, String podId) {
        Keuze k = new Keuze();
        k.reclame = inst.optBoolean("reclame", true);
        JSONObject per = inst.optJSONObject("per"), p = per == null || podId == null ? null : per.optJSONObject(podId);
        if (p != null) {
            if (p.has("reclame")) k.reclame = p.optBoolean("reclame", k.reclame);
            k.intro = sec(p.optInt("intro"));
            k.outro = sec(p.optInt("outro"));
        }
        return k;
    }

    // ---------- hoofdstukken ophalen (alleen https naar openbare adressen, begrensd; in de cache) ----------

    static final long HAAL_MS = 15_000, CACHE_MS = 7L * 24 * 3600_000L;
    /** Vooruit ophalen bij downloaden: een eigen draad, zodat het ophalen voor wat nu speelt er niet achter hoeft te wachten. */
    static final java.util.concurrent.ExecutorService VOORUIT = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(App.veilig(r), "podcast-hoofdstuk-vooruit"));
    static final int MAX_BYTES = 512 * 1024;

    static File cacheDir(Context c) { return new File(c.getCacheDir(), "podcast-hoofdstukken"); }

    /** Het hoofdstukkenbestand (JSON-tekst) of null. Eerst de cache; niet op de hoofdthread aanroepen. */
    static String hoofdstukken(Context c, String url) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8) || url.length() > 1000) return null;
        File d = cacheDir(c), f = new File(d, Podcasts.hash(url) + ".json");
        try {
            if (f.isFile() && System.currentTimeMillis() - f.lastModified() < CACHE_MS) return Contacts.readText(f, false);
        } catch (Exception ignored) { }
        try {
            Nieuws.Antwoord a = Nieuws.haal(url, System.currentTimeMillis() + HAAL_MS);
            if (a.data.length > MAX_BYTES) return null;
            String s = new String(a.data, java.nio.charset.StandardCharsets.UTF_8);
            new JSONObject(s); // moet JSON zijn
            d.mkdirs();
            Contacts.writeText(f, s, false);
            opruimen(d);
            return s;
        } catch (Throwable t) {
            App.log(c, "PODCAST", "hoofdstukken: ophalen lukt niet");
            // Mislukt (bijv. offline): een oudere versie is beter dan niets
            try { return f.isFile() ? Contacts.readText(f, false) : null; } catch (Exception e) { return null; }
        }
    }

    /** Hooguit 200 bestanden in de cache: de oudste weg. */
    private static void opruimen(File d) {
        File[] l = d.listFiles();
        if (l == null || l.length <= 200) return;
        java.util.Arrays.sort(l, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
        for (int i = 0; i < l.length - 200; i++) //noinspection ResultOfMethodCallIgnored
            l[i].delete();
    }

    /** Het hoofdstukkenadres van een aflevering: uit de aflevering zelf, anders uit de bewaarde feed van de podcast. */
    static String adres(Context c, JSONObject ep, JSONObject pod) {
        String u = ep == null ? "" : ep.optString("ch");
        if (!u.isEmpty()) return u;
        if (ep == null || pod == null) return "";
        String id = pod.optString("id");
        if (id.isEmpty() && !pod.optString("feed").isEmpty()) id = Podcasts.podId(pod.optString("feed"));
        if (id.isEmpty()) return "";
        JSONObject feed = Podcasts.readJson(Podcasts.cacheFile(c, id));
        JSONArray items = feed == null ? null : feed.optJSONArray("items");
        String key = ep.optString("key");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject e = items.optJSONObject(i);
            if (e != null && key.equals(e.optString("key"))) return e.optString("ch");
        }
        return "";
    }

    /** Id van de podcast voor de instellingen. */
    static String podId(JSONObject pod) {
        if (pod == null) return "";
        String id = pod.optString("id");
        if (id.isEmpty() && !pod.optString("feed").isEmpty()) id = Podcasts.podId(pod.optString("feed"));
        return geldigId(id) ? id : "";
    }
}
