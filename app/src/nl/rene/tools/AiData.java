package nl.rene.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vragen van de assistent over je eigen gegevens in de app: Gebruik (schermtijd, ontgrendelen), Slaappraat (wat je
 * vannacht zei), Check-in (antwoorden en hoe het gaat) en Apparaten (waar je eigen apparaten het laatst waren).
 *
 * Elke bron doet alleen mee als Rene hem zelf aanzette (Lokale AI → Gegevens, LocalAi.srcOn). Alles blijft op de telefoon.
 * Kleine taalmodellen rekenen en tellen slecht: daarom rekent de app alles zelf uit. Een duidelijke vraag ("hoeveel
 * schermtijd vandaag", "wat zei ik vannacht", "waar was mijn oortje het laatst") beantwoordt de app meteen zelf
 * (answer, zonder taalmodel); bij een andere vraag over zo'n bron krijgt het model een kort lijstje kant-en-klare feiten
 * mee (context), nooit de ruwe gegevens. Van andermans apparaten alleen een aantal, nooit namen (die kiest de eigenaar
 * zelf, en dan zou zijn tekst het model kunnen sturen). Nooit iets van de foto's bij ontgrendelen, alleen hoeveel er zijn.
 *
 * Alles behalve facts() en nicks() is zuiver rekenwerk (getest in AiDataTest).
 */
final class AiData {
    private AiData() { }

    static final String[] TOPICS = {"gebruik", "sleep", "checkin", "apparaten"};
    static final String[] LABEL = {"Gebruik", "Slaappraat", "Check-in", "Apparaten"};

    static String label(String topic) { for (int i = 0; i < TOPICS.length; i++) if (TOPICS[i].equals(topic)) return LABEL[i]; return ""; }

    /** Een antwoord van de app zelf: tekst (met **vet**), de bron (voor de knop eronder). */
    static final class Answer {
        String text = "", topic = "", title = "";
    }

    // ---------- waar gaat de vraag over ----------

    static String norm(String q) {
        if (q == null) return "";
        return q.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[?!.]+\\s*$", "").replaceAll("\\s+", " ").trim();
    }

    private static final Pattern EXPLAIN = Pattern.compile("\\bwaarom\\b|hoe kan ik|hoe kom ik|hoe komt|\\btips?\\b|advies|\\bminder\\b|verminder|te veel|teveel|is dat (?:veel|normaal|goed|erg)"
            + "|vergelijk|wat betekent|betekenis|leg uit|uitleg|samenvat|vat .{0,40}samen|waar ging|waarover|gedicht|verhaal|schrijf");
    /** Een vraag om uitleg of advies (niet alleen een feit): die gaat naar het taalmodel, met de feiten erbij. */
    static boolean explain(String s) { return EXPLAIN.matcher(s).find(); }

    private static final Pattern T_GEBRUIK = Pattern.compile("schermtijd|scherm tijd|screen ?time|telefoongebruik|appgebruik|ontgrendel"
            + "|mislukte (?:poging|ontgrendel|code|pincode)|verkeerde (?:code|pincode)|op (?:mijn|m'n|de) (?:telefoon|mobiel) (?:gezeten|geweest|bezig)"
            + "|(?:hoe lang|hoeveel tijd|hoeveel uur|hoeveel minuten)\\b.{0,50}\\b(?:telefoon|mobiel|gebruikt|gezeten)"
            + "|welke apps? .{0,30}(?:meest|vaakst|langst)|foto'?s? bij (?:het )?ontgrendel");
    private static final Pattern T_SLEEP = Pattern.compile("slaappraat|in (?:mijn|m'n) slaap|(?:mijn|m'n) nachten|gesnurkt|\\bsnurk"
            + "|vannacht.{0,40}(?:gezegd|gepraat|\\bzei\\b|praat|geroepen|gemompeld|gehoord)"
            + "|(?:\\bzei\\b|praat|praatte|gepraat|gezegd|gemompeld).{0,40}(?:vannacht|afgelopen nacht|vorige nacht)");
    private static final Pattern T_CHECKIN = Pattern.compile("check-?in|ingecheckt|(?:mijn|m'n) (?:stemming|humeur)"
            + "|hoe (?:ging|gaat|was) het met (?:mijn|m'n) (?:stemming|humeur|energie)");
    private static final Pattern T_APP = Pattern.compile("oortje|oordop|earbuds|airpods|\\bbuds\\b|koptelefoon|headset|smartwatch|horloge|airtag|smarttag|\\btracker"
            + "|bluetooth|apparat\\w* .{0,14}(?:in de buurt|om me heen)|(?:mijn|m'n) apparaten|in de buurt.{0,20}apparat");
    private static final Pattern T_WHERE = Pattern.compile("^(?:waar|wanneer) (?:was|is|lag|ligt|zag ik|heb ik) (?:mijn|m'n) (.+?)(?: (?:het )?laatst(?: gezien)?| gezien| gebleven| gelaten)?$");

    // Een vraag over iemand anders ("heeft mijn zoon …", "had Jos …"): de gegevens zijn alleen van jezelf
    private static final String PERSON = "zoon|zoontje|dochter|dochtertje|vrouw|man|echtgenoot|echtgenote|partner|vriend|vriendin|vriendje|vriendinnetje|lief"
            + "|kind|kindje|kinderen|kids|moeder|vader|mama|papa|ma|pa|broer|broertje|zus|zusje|opa|oma|collega|buurman|buurvrouw|baas|schoonmoeder|schoonvader|neef|nicht|oom|tante";
    private static final Pattern OTHER = Pattern.compile("(?<!\\bover |\\btegen |\\baan |\\bmet |\\bnaar |\\bbij )\\b(?:mijn|m'n|onze|ons|zijn|haar|hun)\\s+(?:" + PERSON + ")\\b"
            + "|\\b(?:heeft|had|hadden|hebben|gebruikt|gebruikte|deed|zei|praatte|sliep)\\s+(?:hij|zij|ze|die)\\b");
    private static final Pattern NAME_AFTER = Pattern.compile("\\b(?:heeft|had|gebruikt|gebruikte|zei|praatte|deed|sliep)\\s+([A-Z][a-zà-ÿ]+)\\b");
    private static final Pattern NAME_BEFORE = Pattern.compile("\\b([A-Z][a-zà-ÿ]+)\\s+(?:heeft|had|gebruikt|gebruikte|zei|praatte|deed|sliep)\\b");
    private static final Pattern NOT_PERSON = Pattern.compile("(?i)hoeveel|hoe|wat|wie|waar|wanneer|welke|welk|waarom|ik|je|jij|hij|zij|ze|we|wij|het|dat|dit|er|mijn|gisteren|vandaag|vannacht|deze|die"
            + "|chrome|spotify|instagram|facebook|gmail|netflix|google|telegram|signal|snapchat|outlook|teams|tiktok|youtube|maps|tools|slaappraat|gebruik");
    /** Gaat de vraag over iemand anders dan jezelf? Zuiver (getest). */
    static boolean aboutOther(String q, List<String> nicks) {
        if (q == null) return false;
        String raw = q.replace('’', '\'');
        if (OTHER.matcher(raw.toLowerCase(Locale.ROOT)).find()) return true;
        for (Pattern p : new Pattern[]{NAME_AFTER, NAME_BEFORE}) {
            Matcher m = p.matcher(raw);
            while (m.find()) {
                String n = m.group(1);
                if (NOT_PERSON.matcher(n).matches()) continue;
                boolean dev = false;
                if (nicks != null) for (String k : nicks) if (nameHit(n.toLowerCase(Locale.ROOT), k)) dev = true;
                if (!dev) return true;
            }
        }
        return false;
    }
    private static final Pattern CALLS = Pattern.compile("gebeld|\\bbellen\\b|\\bbelde\\b|beltijd|telefoongesprek|telefoongesprekken|\\bgesprekken\\b|aan de telefoon|aan het bellen");

    /** Welke bron past bij de vraag: gebruik, sleep, checkin, apparaten of "". nicks = namen van je eigen apparaten. */
    static String topic(String q, List<String> nicks) {
        String s = norm(q);
        if (s.isEmpty() || s.length() > 300) return "";
        if (aboutOther(q, nicks)) return ""; // de gegevens zijn alleen van jezelf: dan gewoon de assistent
        if (T_SLEEP.matcher(s).find()) return "sleep";
        if (T_CHECKIN.matcher(s).find()) return "checkin";
        if (T_GEBRUIK.matcher(s).find() && !CALLS.matcher(s).find()) return "gebruik"; // bellen staat niet in Gebruik
        if (T_APP.matcher(s).find()) return "apparaten";
        Matcher m = T_WHERE.matcher(s);
        if (m.find() && nicks != null) for (String n : nicks) if (nameHit(m.group(1), n)) return "apparaten";
        return "";
    }
    static String topic(String q) { return topic(q, null); }

    // ---------- opmaak ----------

    /** "minder dan een minuut", "12 min", "2 uur en 5 min". */
    static String dur(long ms) {
        if (ms <= 0) return "0 min";
        long m = Math.round(ms / 60_000.0);
        if (m == 0) return "minder dan een minuut";
        if (m < 60) return m + " min";
        long h = m / 60, r = m % 60;
        return h + " uur" + (r > 0 ? " en " + r + " min" : "");
    }

    private static final String[] DOW = {"zo", "ma", "di", "wo", "do", "vr", "za"};
    private static final String[] MON = {"jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"};
    private static Calendar cal(long t, TimeZone tz) { Calendar c = Calendar.getInstance(tz, Locale.ROOT); c.setTimeInMillis(t); return c; }
    private static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }
    static String hm(long t, TimeZone tz) { Calendar c = cal(t, tz); return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE)); }
    static String day(long t, TimeZone tz) { Calendar c = cal(t, tz); return DOW[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MON[c.get(Calendar.MONTH)]; }

    /** "net nog", "12 min geleden", "vandaag om 14:05", "gisteren om 09:30", "op ma 26 okt om 18:00". */
    static String ago(long t, long now, TimeZone tz) {
        if (t <= 0) return "nog nooit";
        long d = now - t;
        if (d < 2 * 60_000L && d > -60_000L) return "net nog";
        if (d < 60 * 60_000L && d > 0) return (d / 60_000L) + " min geleden";
        long today = AiActions.dayStart(now, tz), yest = AiActions.addDays(today, -1, tz);
        if (t >= today) return "vandaag om " + hm(t, tz);
        if (t >= yest) return "gisteren om " + hm(t, tz);
        return "op " + day(t, tz) + " om " + hm(t, tz);
    }

    /** Plek zoals Apparaten hem bewaart: "thuis", een vakje "51.92,4.48" of "". */
    static String place(String p) {
        if (p == null || p.isEmpty()) return "op een onbekende plek";
        if ("thuis".equals(p)) return "thuis";
        Matcher m = Pattern.compile("^(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)$").matcher(p);
        if (m.find()) return "buiten huis (rond " + m.group(1).replace('.', ',') + " N, " + m.group(2).replace('.', ',') + " O, ±1 km)";
        return "buiten huis";
    }

    private static String num(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v).replace('.', ','); }
    private static String clip(String s, int n) { s = s == null ? "" : s.replaceAll("\\s+", " ").trim(); return s.length() > n ? s.substring(0, n).trim() + "…" : s; }

    // ---------- Gebruik ----------

    /** Kort maken wat GebruikStore.overview geeft (zonder pictogrammen): {total, unlocks, fails, apps:[{name, ms, opens}], chart}. */
    static JSONObject slimOverview(JSONObject o, int fails) throws Exception {
        JSONObject r = new JSONObject().put("total", o == null ? 0 : o.optLong("total")).put("unlocks", o == null ? 0 : o.optInt("unlocks")).put("fails", fails);
        JSONArray apps = new JSONArray(), in = o == null ? null : o.optJSONArray("apps");
        if (in != null) for (int i = 0; i < in.length() && apps.length() < 8; i++) {
            JSONObject a = in.optJSONObject(i);
            if (a == null || a.optLong("ms") <= 0) continue;
            apps.put(new JSONObject().put("name", clip(a.optString("name"), 40)).put("ms", a.optLong("ms")).put("opens", a.optInt("opens")));
        }
        r.put("apps", apps);
        JSONArray ch = o == null ? null : o.optJSONArray("chart");
        if (ch != null) r.put("chart", ch);
        return r;
    }

    static JSONObject gebruikFacts(boolean on, JSONObject today, JSONObject week, int failsToday, int failsWeek, int photos) throws Exception {
        return new JSONObject().put("on", on).put("today", slimOverview(today, failsToday)).put("week", slimOverview(week, failsWeek)).put("photos", photos);
    }

    private static String topApps(JSONObject p, int n) {
        JSONArray a = p.optJSONArray("apps");
        if (a == null || a.length() == 0) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length() && i < n; i++) {
            JSONObject x = a.optJSONObject(i);
            if (b.length() > 0) b.append(i == Math.min(n, a.length()) - 1 ? " en " : ", ");
            b.append(x.optString("name")).append(" (").append(dur(x.optLong("ms"))).append(')');
        }
        return b.toString();
    }

    static Answer gebruikAnswer(String q, JSONObject f) {
        String s = norm(q);
        if (explain(s)) return null;
        Answer a = new Answer(); a.topic = "gebruik"; a.title = "Gebruik";
        if (!f.optBoolean("on")) { a.text = "**Gebruik bijhouden** staat uit, dus ik weet niet hoe lang je op je telefoon zit. Zet het aan bij Gebruik; daarna kan ik het je vertellen."; return a; }
        boolean week = Pattern.compile("\\b(week|7 dagen|zeven dagen|afgelopen dagen)\\b").matcher(s).find();
        boolean yest = !week && s.contains("gisteren");
        JSONObject p = f.optJSONObject(week ? "week" : "today");
        if (p == null) p = new JSONObject();
        String when = week ? "De afgelopen 7 dagen" : "Vandaag";
        a.title = week ? "Gebruik deze week" : "Gebruik vandaag";
        if (s.matches(".*foto'?s?\\b.*")) {
            int n = f.optInt("photos");
            a.text = n == 0 ? "Er zijn geen foto's bij ontgrendelen bewaard." : "Er zijn **" + n + "** foto's bij ontgrendelen bewaard. Wat erop staat, bekijk je zelf bij Gebruik; daar kijk ik niet in.";
            return a;
        }
        if (Pattern.compile("mislukt|verkeerde (?:code|pincode)|pogingen|foute (?:code|pincode)").matcher(s).find()) {
            int n = p.optInt("fails");
            a.text = when + (n == 0 ? " waren er **geen** mislukte ontgrendelpogingen." : " waren er **" + n + "** mislukte ontgrendelpoging" + (n == 1 ? "" : "en") + ".")
                    + (n > 0 ? " Wanneer precies, zie je bij Gebruik → Ontgrendelingen." : "");
            return a;
        }
        if (s.contains("ontgrendel")) {
            int n = p.optInt("unlocks");
            a.text = when + " heb je je telefoon **" + n + " keer** ontgrendeld" + (week && n > 0 ? " (gemiddeld " + Math.round(n / 7.0) + " keer per dag)." : ".");
            return a;
        }
        // Eén app ("hoe lang zat ik op WhatsApp")
        JSONArray apps = p.optJSONArray("apps");
        if (apps != null) for (int i = 0; i < apps.length(); i++) {
            JSONObject x = apps.optJSONObject(i);
            String nm = x.optString("name").toLowerCase(Locale.ROOT);
            if (nm.length() >= 3 && (" " + s + " ").contains(" " + nm + " ")) {
                if (yest) { a.text = "Per app weet ik alleen vandaag en de afgelopen 7 dagen. Vandaag: " + x.optString("name") + " **" + dur(x.optLong("ms")) + "**."; return a; }
                a.text = when + " gebruikte je " + x.optString("name") + " **" + dur(x.optLong("ms")) + "**" + (x.optInt("opens") > 0 ? ", " + x.optInt("opens") + " keer geopend." : ".");
                return a;
            }
        }
        if (Pattern.compile("welke apps?|meest|vaakst|langst").matcher(s).find()) {
            String t = topApps(p, 5);
            a.text = t.isEmpty() ? when + " is er nog geen gebruik van apps bijgehouden." : when + " gebruikte je het meest: " + t + ".";
            return a;
        }
        if (Pattern.compile("schermtijd|scherm tijd|screen ?time|hoe lang|hoeveel (?:tijd|uur|minuten)|telefoongebruik|op (?:mijn|m'n|de) (?:telefoon|mobiel)").matcher(s).find()) {
            if (yest) {
                JSONArray ch = f.optJSONObject("week") == null ? null : f.optJSONObject("week").optJSONArray("chart");
                long v = ch != null && ch.length() == 7 ? ch.optLong(5) : -1;
                a.title = "Gebruik gisteren";
                a.text = v < 0 ? "Van gisteren heb ik geen schermtijd." : "Gisteren was je schermtijd **" + dur(v) + "**.";
                return a;
            }
            long tot = p.optLong("total");
            String t = topApps(p, 3);
            a.text = when + " was je schermtijd **" + dur(tot) + "**" + (week ? " (gemiddeld " + dur(tot / 7) + " per dag)" : "") + ", en je ontgrendelde je telefoon " + p.optInt("unlocks") + " keer."
                    + (t.isEmpty() ? "" : " Het meest: " + t + ".");
            return a;
        }
        return null;
    }

    static String gebruikContext(JSONObject f) {
        if (!f.optBoolean("on")) return "- Gebruik bijhouden staat uit: er zijn geen gegevens.";
        StringBuilder b = new StringBuilder();
        for (String k : new String[]{"today", "week"}) {
            JSONObject p = f.optJSONObject(k);
            if (p == null) continue;
            boolean w = "week".equals(k);
            b.append("- ").append(w ? "Afgelopen 7 dagen" : "Vandaag").append(": schermtijd ").append(dur(p.optLong("total")))
                    .append(w ? " (gemiddeld " + dur(p.optLong("total") / 7) + " per dag)" : "").append(", ").append(p.optInt("unlocks")).append(" keer ontgrendeld, ")
                    .append(p.optInt("fails")).append(" mislukte pogingen.\n");
            String t = topApps(p, 5);
            if (!t.isEmpty()) b.append("- Meest gebruikte apps ").append(w ? "deze week" : "vandaag").append(": ").append(t).append(".\n");
        }
        JSONObject wk = f.optJSONObject("week");
        JSONArray ch = wk == null ? null : wk.optJSONArray("chart");
        if (ch != null && ch.length() == 7) b.append("- Gisteren: schermtijd ").append(dur(ch.optLong(5))).append(".\n");
        b.append("- Foto's bij ontgrendelen: ").append(f.optInt("photos")).append(" (wat erop staat weet je niet).");
        return b.toString();
    }

    // ---------- Slaappraat ----------

    /** nights = SleepTalk.nights (nieuwste eerst, samenvatting); last = de nieuwste nacht met zijn stukjes (night.json). */
    static JSONObject sleepFacts(JSONArray nights, JSONObject last, String running) throws Exception {
        JSONObject o = new JSONObject();
        JSONArray ns = new JSONArray();
        if (nights != null) for (int i = 0; i < nights.length() && ns.length() < 7; i++) {
            JSONObject n = nights.optJSONObject(i);
            if (n == null) continue;
            ns.put(new JSONObject().put("id", n.optString("id")).put("start", n.optLong("start")).put("end", n.optLong("end")).put("talk", n.optInt("talk")).put("noise", n.optInt("noise")).put("open", n.optInt("open")));
        }
        o.put("nights", ns);
        if (last != null) {
            JSONObject l = new JSONObject().put("id", last.optString("id")).put("start", last.optLong("start")).put("end", last.optLong("end"))
                    .put("running", running != null && !running.isEmpty() && running.equals(last.optString("id")));
            JSONArray lines = new JSONArray(), clips = last.optJSONArray("clips");
            int talk = 0, noise = 0, open = 0;
            if (clips != null) for (int i = 0; i < clips.length(); i++) {
                JSONObject k = clips.optJSONObject(i);
                if (k == null) continue;
                String kind = k.optBoolean("done") ? k.optString("kind") : "";
                if ("praten".equals(kind)) { talk++; String t = k.optString("text").trim(); if (!t.isEmpty() && lines.length() < 15) lines.put(new JSONObject().put("t", k.optLong("t")).put("text", clip(t, 220))); }
                else if ("geluid".equals(kind)) noise++;
                else if (!"onleesbaar".equals(kind)) open++;
            }
            l.put("talk", talk).put("noise", noise).put("open", open).put("lines", lines);
            o.put("last", l);
        }
        return o;
    }

    static Answer sleepAnswer(String q, JSONObject f, long now, TimeZone tz) {
        String s = norm(q);
        if (explain(s)) return null;
        Answer a = new Answer(); a.topic = "sleep"; a.title = "Slaappraat";
        JSONArray ns = f.optJSONArray("nights");
        JSONObject l = f.optJSONObject("last");
        boolean list = Pattern.compile("nachten|overzicht|afgelopen (?:week|dagen)|deze week|hoe vaak").matcher(s).find();
        boolean snore = s.contains("snurk");
        boolean talk = Pattern.compile("wat (?:zei|heb|riep|mompelde) ik|\\bzei ik\\b|heb ik .{0,30}(?:gepraat|gezegd|geroepen|gemompeld)|praatte ik|praat ik|wat ik .{0,20}(?:zei|gezegd)|gepraat|gezegd").matcher(s).find();
        if (!list && !snore && !talk) return null;
        if (ns == null || ns.length() == 0 || l == null) { a.text = "Er is nog geen nacht met Slaappraat bewaard. Zet Slaappraat aan als je gaat slapen; daarna kan ik vertellen wat je zei."; return a; }
        if (list) {
            StringBuilder b = new StringBuilder("De laatste " + (ns.length() == 1 ? "nacht" : ns.length() + " nachten") + " met Slaappraat:");
            for (int i = 0; i < ns.length(); i++) {
                JSONObject n = ns.optJSONObject(i);
                b.append("\n- ").append(day(n.optLong("start"), tz)).append(": ").append(n.optInt("talk") == 0 ? "niet gepraat" : n.optInt("talk") + "× gepraat")
                        .append(n.optInt("noise") > 0 ? ", " + n.optInt("noise") + " andere geluiden" : "").append(n.optInt("open") > 0 ? ", nog " + n.optInt("open") + " uit te schrijven" : "");
            }
            a.text = b.toString();
            return a;
        }
        long start = l.optLong("start"), end = l.optLong("end");
        boolean old = now - start > 36 * 3_600_000L;
        String when = day(start, tz) + " (" + hm(start, tz) + (l.optBoolean("running") ? ", Slaappraat luistert nog" : end > start ? "–" + hm(end, tz) : "") + ")";
        StringBuilder b = new StringBuilder(old ? "De laatste nacht met Slaappraat was " + when + ". " : "Nacht van " + when + ": ");
        if (snore) {
            int n = l.optInt("noise");
            b.append(n == 0 ? "er zijn geen andere geluiden (zoals snurken) bewaard." : "er zijn " + n + " andere geluiden bewaard, zoals snurken. Of het snurken was, hoor je zelf bij Slaappraat.");
            a.text = b.toString();
            return a;
        }
        JSONArray lines = l.optJSONArray("lines");
        int nt = l.optInt("talk"), open = l.optInt("open");
        if (nt == 0) b.append("je hebt niet gepraat").append(l.optInt("noise") > 0 ? " (wel " + l.optInt("noise") + " andere geluiden, zoals snurken)." : ".");
        else {
            b.append(nt == 1 ? "je zei één keer iets:" : "je zei " + nt + " keer iets:");
            for (int i = 0; lines != null && i < lines.length(); i++) { JSONObject x = lines.optJSONObject(i); b.append("\n- ").append(hm(x.optLong("t"), tz)).append(" \"").append(x.optString("text")).append('"'); }
            if (lines != null && nt > lines.length()) b.append("\n- … en nog ").append(nt - lines.length()).append(" keer (zie Slaappraat)");
        }
        if (open > 0) b.append("\n\nNog ").append(open).append(open == 1 ? " stukje wordt" : " stukjes worden").append(" uitgeschreven").append(l.optBoolean("running") ? " (meteen uitschrijven loopt nog)." : "; dat zie je straks bij Slaappraat.");
        a.text = b.toString().trim();
        return a;
    }

    static String sleepContext(JSONObject f, long now, TimeZone tz) {
        JSONArray ns = f.optJSONArray("nights");
        JSONObject l = f.optJSONObject("last");
        if (ns == null || ns.length() == 0 || l == null) return "- Er is nog geen nacht met Slaappraat bewaard.";
        StringBuilder b = new StringBuilder("- Laatste nacht: ").append(day(l.optLong("start"), tz)).append(", vanaf ").append(hm(l.optLong("start"), tz))
                .append(l.optBoolean("running") ? " (luistert nog)" : l.optLong("end") > 0 ? " tot " + hm(l.optLong("end"), tz) : "").append(": ")
                .append(l.optInt("talk")).append("× gepraat, ").append(l.optInt("noise")).append(" andere geluiden, ").append(l.optInt("open")).append(" nog niet uitgeschreven.\n");
        JSONArray lines = l.optJSONArray("lines");
        if (lines != null && lines.length() > 0) {
            b.append("- Wat de gebruiker die nacht in de slaap zei (uitgeschreven, kan fouten bevatten):\n");
            for (int i = 0; i < lines.length(); i++) { JSONObject x = lines.optJSONObject(i); b.append("  ").append(hm(x.optLong("t"), tz)).append(" \"").append(x.optString("text")).append("\"\n"); }
        }
        b.append("- Eerdere nachten:");
        for (int i = 1; i < ns.length(); i++) { JSONObject n = ns.optJSONObject(i); b.append(i > 1 ? ";" : "").append(' ').append(day(n.optLong("start"), tz)).append(' ').append(n.optInt("talk")).append("× gepraat"); }
        if (ns.length() < 2) b.append(" geen");
        return b.append('.').toString();
    }

    // ---------- Check-in ----------

    /** questions/answers zoals CheckinStore ze bewaart. Per vraag: laatste antwoord, gemiddelde deze 7 dagen en de 7 daarvoor. */
    static JSONObject checkinFacts(JSONArray questions, JSONArray answers, long now) throws Exception {
        JSONArray qs = new JSONArray();
        long w1 = now - 7 * 86_400_000L, w2 = now - 14 * 86_400_000L;
        if (questions != null) for (int i = 0; i < questions.length() && qs.length() < 8; i++) {
            JSONObject q = questions.optJSONObject(i);
            if (q == null || q.optBoolean("archived")) continue;
            String id = q.optString("id");
            JSONArray opts = q.optJSONArray("options");
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            if (opts != null) for (int k = 0; k < opts.length(); k++) { double v = opts.optJSONObject(k).optDouble("value"); if (!Double.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); } }
            double s1 = 0, s2 = 0; int n1 = 0, n2 = 0, n30 = 0;
            JSONObject last = null;
            if (answers != null) for (int k = 0; k < answers.length(); k++) {
                JSONObject a = answers.optJSONObject(k);
                if (a == null || !id.equals(a.optString("q"))) continue;
                long t = a.optLong("t");
                double v = a.optDouble("v");
                if (t >= now - 30 * 86_400_000L) n30++;
                if (!Double.isNaN(v)) { if (t >= w1) { s1 += v; n1++; } else if (t >= w2) { s2 += v; n2++; } }
                if (last == null || t > last.optLong("t")) last = a;
            }
            JSONObject o = new JSONObject().put("title", clip(CheckinStore.qTitle(q), 80)).put("n7", n1).put("n30", n30).put("custom", "custom".equals(q.optString("scale")));
            if (hi > lo) o.put("lo", lo).put("hi", hi);
            if (n1 > 0) o.put("avg7", Math.round(s1 / n1 * 10) / 10.0);
            if (n2 > 0) o.put("avgPrev7", Math.round(s2 / n2 * 10) / 10.0);
            if (last != null) {
                JSONObject l = new JSONObject().put("label", clip((last.optString("e") + " " + last.optString("l")).trim(), 40)).put("t", last.optLong("t"));
                if (!last.optString("note").isEmpty()) l.put("note", clip(last.optString("note"), 160));
                String labels = CheckinStore.join(last.optJSONArray("labels"));
                if (!labels.isEmpty()) l.put("labels", clip(labels, 80));
                o.put("last", l);
            }
            qs.put(o);
        }
        return new JSONObject().put("qs", qs);
    }

    /** "beter", "slechter", "ongeveer gelijk" (of hoger/lager bij zelf gekozen antwoorden), of "" als het niet te zeggen is. */
    static String trend(JSONObject q) {
        if (!q.has("avg7") || !q.has("avgPrev7") || !q.has("lo")) return "";
        double d = q.optDouble("avg7") - q.optDouble("avgPrev7"), span = q.optDouble("hi") - q.optDouble("lo");
        if (Math.abs(d) < Math.max(0.2, span * 0.1)) return "ongeveer gelijk";
        if (q.optBoolean("custom")) return d > 0 ? "hoger" : "lager";
        return d > 0 ? "beter" : "slechter";
    }

    private static String trendLine(JSONObject q) {
        StringBuilder b = new StringBuilder(q.optString("title")).append(": ");
        if (!q.has("avg7")) return b.append("de afgelopen 7 dagen niet ingevuld").toString();
        b.append("gemiddeld ").append(num(q.optDouble("avg7"))).append(q.has("lo") ? " (van " + num(q.optDouble("lo")) + " tot " + num(q.optDouble("hi")) + ")" : "")
                .append(" over ").append(q.optInt("n7")).append(q.optInt("n7") == 1 ? " antwoord" : " antwoorden");
        if (q.has("avgPrev7")) b.append("; de week ervoor ").append(num(q.optDouble("avgPrev7"))).append(trend(q).isEmpty() ? "" : ": " + trend(q));
        return b.toString();
    }

    private static String lastLine(JSONObject q, long now, TimeZone tz) {
        JSONObject l = q.optJSONObject("last");
        if (l == null) return q.optString("title") + ": nog niet ingevuld";
        return q.optString("title") + ": " + l.optString("label") + " (" + ago(l.optLong("t"), now, tz) + ")" + (l.has("labels") ? " · " + l.optString("labels") : "") + (l.has("note") ? " · notitie: " + l.optString("note") : "");
    }

    static Answer checkinAnswer(String q, JSONObject f, long now, TimeZone tz) {
        String s = norm(q);
        if (explain(s)) return null;
        Answer a = new Answer(); a.topic = "checkin"; a.title = "Check-in";
        JSONArray qs = f.optJSONArray("qs");
        if (qs == null || qs.length() == 0) { a.text = "Je hebt nog geen vragen bij Check-in. Maak er een bij Check-in; daarna kan ik vertellen hoe het gaat."; return a; }
        boolean tr = Pattern.compile("trend|gemiddeld|beter|slechter|hoe (?:ging|gaat|was) het|vooruit|achteruit").matcher(s).find();
        boolean last = Pattern.compile("laatst|vandaag|gisteren|ingevuld|geantwoord|antwoord").matcher(s).find();
        if (!tr && !last) return null;
        StringBuilder b = new StringBuilder(tr ? "Zo gaat het volgens je Check-in (afgelopen 7 dagen):" : "Je laatste antwoorden bij Check-in:");
        for (int i = 0; i < qs.length(); i++) b.append("\n- ").append(tr ? trendLine(qs.optJSONObject(i)) : lastLine(qs.optJSONObject(i), now, tz));
        a.text = b.toString();
        return a;
    }

    static String checkinContext(JSONObject f, long now, TimeZone tz) {
        JSONArray qs = f.optJSONArray("qs");
        if (qs == null || qs.length() == 0) return "- Er zijn nog geen Check-in-vragen.";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.optJSONObject(i);
            b.append("- ").append(trendLine(q)).append(". Laatste antwoord: ").append(lastLine(q, now, tz).substring(q.optString("title").length() + 2)).append(". In 30 dagen ").append(q.optInt("n30")).append("× ingevuld.\n");
        }
        return b.toString().trim();
    }

    // ---------- Apparaten ----------

    /** hist = ApparatenStore.histOverview, scan = ApparatenScan.btJson. Alleen je eigen (gemarkeerde) apparaten bij naam. */
    static JSONObject apparatenFacts(JSONObject hist, JSONObject scan, boolean histOn, long now) throws Exception {
        JSONObject o = new JSONObject().put("hist", histOn);
        JSONArray devs = scan == null ? null : scan.optJSONArray("devices");
        JSONArray mine = new JSONArray(), mh = hist == null ? null : hist.optJSONArray("mine");
        java.util.Map<String, JSONObject> near = new java.util.HashMap<>();
        int others = 0;
        JSONArray known = new JSONArray();
        if (devs != null) for (int i = 0; i < devs.length(); i++) {
            JSONObject d = devs.optJSONObject(i);
            if (d == null) continue;
            if (d.optBoolean("known")) { near.put(d.optString("key"), d); known.put(new JSONObject().put("name", clip(d.optString("nick").isEmpty() ? d.optString("name") : d.optString("nick"), 40)).put("dist", d.optDouble("dist", -1))); }
            else others++;
        }
        if (mh != null) for (int i = 0; i < mh.length() && mine.length() < 20; i++) {
            JSONObject x = mh.optJSONObject(i);
            if (x == null) continue;
            JSONObject n = near.get(x.optString("key"));
            mine.put(new JSONObject().put("name", clip(x.optString("nick").isEmpty() ? x.optString("name") : x.optString("nick"), 40)).put("type", x.optString("type"))
                    .put("last", n != null ? now - n.optLong("ago") * 1000 : x.optLong("last")).put("place", n != null ? "" : x.optString("place"))
                    .put("near", n != null).put("dist", n != null ? n.optDouble("dist", -1) : -1));
        }
        o.put("mine", mine).put("scan", new JSONObject().put("fresh", devs != null && devs.length() > 0).put("known", known).put("others", others));
        return o;
    }

    private static final String[][] SYN = {
            {"oortjes", "oortje", "oortjes", "oordop", "oordopjes", "earbuds", "airpods", "buds"},
            {"koptelefoon", "koptelefoon", "headset", "hoofdtelefoon"},
            {"horloge", "horloge", "smartwatch", "watch"},
            {"tracker", "tracker", "sleutel", "sleutels", "sleutelbos", "airtag", "smarttag", "tile"},
            {"auto", "auto", "carkit"},
            {"speaker", "speaker", "box", "boxje", "luidspreker"},
            {"telefoon", "telefoon", "mobiel"},
            {"computer", "laptop", "computer"},
    };

    /** Past wat je zei ("oortje", "mijn sleutels", "Jabra") bij deze naam? */
    static boolean nameHit(String said, String name) {
        String s = norm(said).replaceAll("^(?:de|het|mijn|m'n)\\s+", ""), n = norm(name);
        if (s.length() < 2 || n.isEmpty()) return false;
        return n.equals(s) || (s.length() >= 3 && (n.contains(s) || s.contains(n) && n.length() >= 3));
    }
    /** Past het bij dit apparaat (naam of soort)? */
    static boolean devHit(String said, JSONObject d) {
        if (nameHit(said, d.optString("name"))) return true;
        String s = norm(said), type = d.optString("type");
        for (String[] g : SYN) {
            if (!g[0].equals(type)) continue;
            for (int i = 1; i < g.length; i++) if (Pattern.compile("\\b" + Pattern.quote(g[i]) + "\\b").matcher(s).find()) return true;
        }
        return false;
    }

    private static String distTxt(double m) { return m < 0 ? "" : m < 1 ? "dichtbij (minder dan 1 m)" : "ongeveer " + (m < 10 ? num(Math.round(m * 2) / 2.0) : String.valueOf(Math.round(m))) + " m"; }

    private static final Pattern A_NEAR = Pattern.compile("in de buurt|om me heen|dichtbij|welke apparaten|wat voor apparaten");
    private static final Pattern A_WHERE = Pattern.compile("^(?:waar|wanneer) (?:was|is|lag|ligt|zag ik|heb ik) (?:mijn|m'n|de) (.+?)(?: (?:het )?laatst(?: gezien)?| gezien| gebleven| gelaten)?$|laatst gezien");

    static Answer apparatenAnswer(String q, JSONObject f, long now, TimeZone tz) {
        String s = norm(q);
        if (explain(s)) return null;
        Answer a = new Answer(); a.topic = "apparaten"; a.title = "Apparaten";
        JSONArray mine = f.optJSONArray("mine");
        Matcher m = A_WHERE.matcher(s);
        if (m.find()) {
            String what = m.group(1) != null ? m.group(1) : s;
            List<JSONObject> hits = new ArrayList<>();
            for (int i = 0; mine != null && i < mine.length(); i++) if (devHit(what, mine.optJSONObject(i))) hits.add(mine.optJSONObject(i));
            if (hits.isEmpty()) {
                a.text = (mine == null || mine.length() == 0 ? "Je hebt nog geen eigen apparaten gemarkeerd. " : "Ik ken geen eigen apparaat dat past bij \"" + clip(what, 40) + "\". ")
                        + "Tik bij Apparaten op een apparaat en kies **Mijn apparaat**; dan houdt de app bij waar het het laatst was.";
                return a;
            }
            StringBuilder b = new StringBuilder();
            for (JSONObject d : hits.subList(0, Math.min(3, hits.size()))) {
                if (b.length() > 0) b.append('\n');
                if (d.optBoolean("near")) b.append("Je **").append(d.optString("name")).append("** is nu in de buurt").append(d.optDouble("dist") >= 0 ? ": " + distTxt(d.optDouble("dist")) : "").append(". Met Zoeken bij Apparaten vind je het (warmer/kouder).");
                else if (d.optLong("last") <= 0) b.append("Je **").append(d.optString("name")).append("** heb ik nog niet gezien.");
                else b.append("Je **").append(d.optString("name")).append("** is het laatst gezien ").append(ago(d.optLong("last"), now, tz)).append(", ").append(place(d.optString("place"))).append('.');
            }
            if (!f.optBoolean("hist")) b.append("\n\nLet op: \"bijhouden wat vaak in de buurt is\" staat uit, dus dit kan oud zijn.");
            a.text = b.toString();
            return a;
        }
        if (A_NEAR.matcher(s).find()) {
            JSONObject sc = f.optJSONObject("scan");
            if (sc == null || !sc.optBoolean("fresh")) { a.text = "De laatste minuten is er niet naar apparaten gezocht. Open Apparaten om te zoeken; daarna weet ik wat er in de buurt is."; return a; }
            JSONArray k = sc.optJSONArray("known");
            int o = sc.optInt("others");
            StringBuilder b = new StringBuilder("Bij de laatste zoekronde ");
            if (k == null || k.length() == 0) b.append("waren er geen eigen apparaten van je in de buurt");
            else {
                b.append("waren deze eigen apparaten in de buurt: ");
                for (int i = 0; i < k.length(); i++) { JSONObject d = k.optJSONObject(i); b.append(i > 0 ? (i == k.length() - 1 ? " en " : ", ") : "").append("**").append(d.optString("name")).append("**").append(d.optDouble("dist") >= 0 ? " (" + distTxt(d.optDouble("dist")) + ")" : ""); }
            }
            b.append(o == 0 ? ", en geen andere apparaten." : ", en " + o + (o == 1 ? " ander apparaat" : " andere apparaten") + " (van anderen of onbekend; die noem ik niet bij naam).");
            a.text = b.toString();
            return a;
        }
        return null;
    }

    static String apparatenContext(JSONObject f, long now, TimeZone tz) {
        StringBuilder b = new StringBuilder();
        JSONArray mine = f.optJSONArray("mine");
        if (mine == null || mine.length() == 0) b.append("- De gebruiker heeft nog geen eigen apparaten gemarkeerd.\n");
        else for (int i = 0; i < mine.length(); i++) {
            JSONObject d = mine.optJSONObject(i);
            b.append("- Eigen apparaat \"").append(d.optString("name")).append("\"").append(d.optString("type").isEmpty() ? "" : " (" + d.optString("type") + ")").append(": ")
                    .append(d.optBoolean("near") ? "nu in de buurt" + (d.optDouble("dist") >= 0 ? ", " + distTxt(d.optDouble("dist")) : "") : d.optLong("last") > 0 ? "laatst gezien " + ago(d.optLong("last"), now, tz) + ", " + place(d.optString("place")) : "nog niet gezien").append(".\n");
        }
        JSONObject sc = f.optJSONObject("scan");
        if (sc != null && sc.optBoolean("fresh")) b.append("- Laatste zoekronde: ").append(sc.optJSONArray("known") == null ? 0 : sc.optJSONArray("known").length()).append(" eigen apparaten en ").append(sc.optInt("others")).append(" andere apparaten in de buurt (andere noem je nooit bij naam).");
        else b.append("- De laatste minuten is er niet gezocht.");
        return b.toString().trim();
    }

    // ---------- samen ----------

    static Answer answer(String topic, String q, JSONObject f, long now, TimeZone tz) {
        if (f == null) return null;
        switch (topic) {
            case "gebruik": return gebruikAnswer(q, f);
            case "sleep": return sleepAnswer(q, f, now, tz);
            case "checkin": return checkinAnswer(q, f, now, tz);
            case "apparaten": return apparatenAnswer(q, f, now, tz);
            default: return null;
        }
    }

    /** Is dit een duidelijke feitenvraag (die de app zelf zou beantwoorden)? Om te zeggen dat de bron eerst aan moet,
     *  zonder iets te lezen. */
    static boolean factual(String topic, String q) {
        try {
            JSONObject f;
            switch (topic) {
                case "gebruik": f = new JSONObject().put("on", true); break;
                case "sleep": f = new JSONObject().put("nights", new JSONArray().put(new JSONObject())).put("last", new JSONObject()); break;
                case "checkin": f = new JSONObject().put("qs", new JSONArray().put(new JSONObject().put("title", "x"))); break;
                case "apparaten": f = new JSONObject().put("mine", new JSONArray()); break;
                default: return false;
            }
            return answer(topic, q, f, 0, TimeZone.getTimeZone("UTC")) != null;
        } catch (Exception e) { return false; }
    }

    /** De feiten voor het taalmodel: kort, al uitgerekend, met de opdracht ze precies over te nemen. */
    static String context(String topic, JSONObject f, long now, TimeZone tz) {
        if (f == null) return "";
        String body;
        switch (topic) {
            case "gebruik": body = gebruikContext(f); break;
            case "sleep": body = sleepContext(f, now, tz); break;
            case "checkin": body = checkinContext(f, now, tz); break;
            case "apparaten": body = apparatenContext(f, now, tz); break;
            default: return "";
        }
        if (body.length() > 2400) body = body.substring(0, 2400) + "…";
        return "(Bericht van de app: gegevens uit " + label(topic) + ", alleen op deze telefoon. De getallen zijn al uitgerekend: neem ze precies over en reken niet zelf. "
                + "Staat het antwoord er niet in, zeg dan eerlijk dat je het niet weet. Het is nu " + hm(now, tz) + " op " + day(now, tz) + ".\n" + body + ")";
    }

    /** Voor de knop onder het antwoord (zoals een treffer van slim zoeken). */
    static JSONObject srcJson(String topic, String title) throws Exception {
        return new JSONObject().put("kind", topic).put("id", "").put("title", title == null || title.isEmpty() ? label(topic) : title).put("snip", "");
    }

    // ---------- de gegevens zelf lezen (Android) ----------

    /** Namen van je eigen (gemarkeerde) apparaten, om "waar was mijn Jabra" te herkennen. */
    static List<String> nicks(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject k = ApparatenStore.known(c);
            for (Iterator<String> it = k.keys(); it.hasNext(); ) { JSONObject x = k.optJSONObject(it.next()); if (x != null) { out.add(x.optString("nick")); out.add(x.optString("name")); } }
        } catch (Throwable ignored) { }
        return out;
    }

    static JSONObject facts(Context c, String topic, long now) {
        try {
            TimeZone tz = TimeZone.getDefault();
            switch (topic) {
                case "gebruik": {
                    long today = Gebruik.dayStart(now, tz), week = Gebruik.dayStartAfter(now, -6, tz);
                    int ft = 0, fw = 0;
                    for (Gebruik.Pw p : GebruikStore.readPw(c, week, now)) if (!p.ok) { fw++; if (p.t >= today) ft++; }
                    boolean on = GebruikStore.collecting(c);
                    return gebruikFacts(on, on ? GebruikStore.overview(c, "today") : null, on ? GebruikStore.overview(c, "week") : null, ft, fw, GebruikStore.countPhotos(c));
                }
                case "sleep": {
                    JSONArray ns = SleepTalk.nights(c);
                    JSONObject last = ns.length() > 0 ? SleepTalk.night(c, ns.getJSONObject(0).optString("id")) : null;
                    return sleepFacts(ns, last, "luistert".equals(SleepService.phase) ? SleepService.night : "");
                }
                case "checkin": return checkinFacts(CheckinStore.questions(c), CheckinStore.answers(c), now);
                case "apparaten": return apparatenFacts(ApparatenStore.histOverview(c), ApparatenScan.btJson(c), ApparatenStore.histOn(c), now);
                default: return null;
            }
        } catch (Throwable t) {
            App.log(c, "AI", "gegevens " + topic + ": " + t);
            return null;
        }
    }
}
