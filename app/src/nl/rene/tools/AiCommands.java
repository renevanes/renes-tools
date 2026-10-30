package nl.rene.tools;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Duidelijke opdrachten aan de assistent die de app zelf herkent, zonder taalmodel: een timer of wekker zetten, de
 * radio of een podcast afspelen, Slaappraat starten of stoppen, een tool openen, of een sms of WhatsApp-bericht
 * klaarzetten. Er gebeurt nooit iets vanzelf: de interface laat een kaart zien en pas na een tik op de knop gebeurt
 * het (en een bericht verstuur je daarna zelf, in Berichten of WhatsApp).
 *
 * Alleen de eigen vraag van Rene wordt gelezen (nooit een antwoord van het model of berichten van anderen), en alles
 * wordt nagekeken: duur, tijd, telefoonnummer, tool. Zuiver rekenwerk (getest in AiCommandsTest).
 */
final class AiCommands {
    private AiCommands() { }

    /** Een herkende opdracht. type: timer, wekker, radio, podcast, speel, slaap, open, sms, wa. */
    static final class Cmd {
        String type = "", q = "", label = "", to = "", number = "", text = "", tool = "", need = "";
        int secs, min = -1;
        boolean stop, radio, newest, viaDat;
        boolean weak; // een bericht dat alleen met een bekende contactnaam een opdracht is
        String dayWord = ""; // een dag die de Klok-app niet kan kiezen ("morgen", "zaterdag")
    }

    static final int MAX_TIMER_S = 24 * 3600; // Android: hooguit 24 uur
    static final int MAX_TEXT = 1000;

    /** Tools die de assistent mag openen: id (zoals de interface ze kent) en hoe je ze noemt. */
    static final String[][] TOOLS = {
            {"apparaten", "apparaten", "apparaat", "bluetooth"},
            {"gebruik", "gebruik", "schermtijd"},
            {"sleep", "slaappraat"},
            {"checkin", "check-in", "checkin", "check in"},
            {"radio", "radio"},
            {"ralarm", "radiowekker"},
            {"podcasts", "podcasts", "podcast"},
            {"notes", "notities", "notitie", "lijstjes"},
            {"contacts", "contacten"},
            {"calls", "oproepen", "belgeschiedenis"},
            {"sms", "sms", "sms-backup", "sms backup", "sms'jes", "smsjes"},
            {"wa", "whatsapp", "whatsapp backup", "whatsapp-backup"},
            {"music", "muziek herkennen", "muziekherkenning"},
            {"recorder", "gesprekken opnemen", "opnemen"},
            {"transcripts", "gesprekken uitschrijven", "uitschrijven"},
            {"car", "mijn auto", "auto"},
            {"kluis", "kluis"},
            {"pdf", "pdf"},
            {"spotify", "spotify", "spotify-backup", "spotify backup"},
            {"vpn", "vpn"},
            {"loc", "locatie kiezen", "locatie"},
            {"history", "meldingsgeschiedenis"},
            {"tracks", "mijn routes", "routes"},
            {"redial", "auto redial", "redial"},
            {"backup", "alles back-uppen", "backup", "back-up", "backups"},
            {"settings", "instellingen"},
            {"auto", "automatiseringen"},
            {"aiset", "lokale ai"},
            {"home", "startscherm"},
    };

    static boolean validTool(String id) { if (id == null) return false; for (String[] t : TOOLS) if (t[0].equals(id)) return true; return false; }
    static String toolFor(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        for (String[] t : TOOLS) for (int i = 1; i < t.length; i++) if (t[i].equals(n)) return t[0];
        return "";
    }

    static boolean validSecs(int s) { return s >= 1 && s <= MAX_TIMER_S; }
    static boolean validClock(int min) { return min >= 0 && min < 24 * 60; }

    /** Telefoonnummer nagekeken en zonder opmaak ("+31612345678", "0612345678"), of "" als het geen nummer is. */
    static String number(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (!t.matches("[+0-9 ()./-]{4,30}")) return "";
        String d = t.replaceAll("[ ()./-]", "");
        if (d.startsWith("00")) d = "+" + d.substring(2);
        if (!d.matches("\\+?\\d{6,15}")) return ""; // korter: een servicenummer, geen persoon
        if (d.matches("(?:\\+\\d{1,3})?0?(?:112|911|999|000)")) return ""; // nooit een alarmnummer (ook niet met landnummer)
        return d;
    }

    /** Voor WhatsApp: alleen cijfers met landnummer (06… wordt 316…). */
    static String waNumber(String num) {
        String n = number(num);
        if (n.isEmpty()) return "";
        if (n.startsWith("+")) return n.substring(1);
        if (n.startsWith("0") && n.length() >= 10) return "31" + n.substring(1);
        return n;
    }

    static String cleanText(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) { char ch = s.charAt(i); if (ch == '\n' || !Character.isISOControl(ch)) b.append(ch); }
        return b.toString().trim();
    }

    // ---------- getallen en tijden ----------

    private static final String[] UNITS = {"nul", "een", "twee", "drie", "vier", "vijf", "zes", "zeven", "acht", "negen", "tien", "elf", "twaalf", "dertien", "veertien", "vijftien", "zestien", "zeventien", "achttien", "negentien"};
    private static final String[] TENS = {"", "", "twintig", "dertig", "veertig", "vijftig", "zestig", "zeventig", "tachtig", "negentig"};

    /** "10", "tien", "vijfentwintig", "één" → getal, of -1. */
    static int number99(String w) {
        if (w == null) return -1;
        String s = w.toLowerCase(Locale.ROOT).replace("één", "een").replace("ë", "e").trim();
        if (s.matches("\\d{1,4}")) return Integer.parseInt(s);
        for (int i = 0; i < UNITS.length; i++) if (UNITS[i].equals(s)) return i;
        for (int t = 2; t < TENS.length; t++) {
            if (TENS[t].equals(s)) return t * 10;
            for (int u = 1; u < 10; u++) if (s.equals(UNITS[u] + "en" + TENS[t])) return t * 10 + u;
        }
        return -1;
    }

    private static final String NUM = "(\\d{1,4}(?:[,.]\\d)?|een|één|twee|drie|vier|vijf|zes|zeven|acht|negen|tien|elf|twaalf|dertien|veertien|vijftien|zestien|zeventien|achttien|negentien|twintig|dertig|veertig|vijftig|zestig|negentig|[a-z]+en(?:twintig|dertig|veertig|vijftig))";
    private static final Pattern D_PART = Pattern.compile("(?iu)" + NUM + "\\s*(uur|uren|u|minuten|minuut|min|m|seconden|seconde|sec|s)\\b");

    /** Duur in seconden uit "10 minuten", "anderhalf uur", "een kwartier", "1 uur en 20 minuten", "90 seconden"; -1 = geen. */
    static int duration(String text) {
        if (text == null) return -1;
        String s = text.toLowerCase(Locale.ROOT);
        int total = 0;
        boolean any = false;
        if (s.matches(".*\\banderhalf uur\\b.*")) { total += 5400; any = true; s = s.replaceAll("\\banderhalf uur\\b", " "); }
        if (s.matches(".*\\banderhalve minuut\\b.*")) { total += 90; any = true; s = s.replaceAll("\\banderhalve minuut\\b", " "); }
        Matcher h = Pattern.compile("(\\d+|een|twee|drie|vier|vijf)\\s+en\\s+een\\s+half\\s+uur").matcher(s);
        if (h.find()) { int n = number99(h.group(1)); if (n >= 0) { total += n * 3600 + 1800; any = true; s = s.replace(h.group(), " "); } }
        if (s.matches(".*\\b(?:een |1 )?half uur(tje)?\\b.*")) { total += 1800; any = true; s = s.replaceAll("\\b(?:een |1 )?half uur(tje)?\\b", " "); }
        if (s.matches(".*\\b(?:een |1 )?kwartier(tje)?\\b.*")) {
            Matcher k = Pattern.compile("\\b(drie|3)\\s+kwartier\\b").matcher(s);
            if (k.find()) { total += 2700; s = s.replace(k.group(), " "); } else { total += 900; s = s.replaceAll("\\b(?:een |1 )?kwartier(tje)?\\b", " "); }
            any = true;
        }
        Matcher m = D_PART.matcher(s);
        while (m.find()) {
            String nw = m.group(1).replace(',', '.');
            double n = nw.matches("\\d+(\\.\\d)?") ? Double.parseDouble(nw) : number99(nw);
            if (n < 0) continue;
            String u = m.group(2);
            if (u.startsWith("u")) total += (int) Math.round(n * 3600);
            else if (u.startsWith("m")) total += (int) Math.round(n * 60);
            else total += (int) Math.round(n);
            any = true;
        }
        return any ? total : -1;
    }

    /** Kloktijd voor een wekker: "7 uur", "07:30", "half 8", "kwart over 6", "om 6 uur 's avonds". Zonder dagdeel:
     *  de ochtend (een wekker om 6 uur is 's ochtends). Minuut van de dag, of -1. */
    static int clock(String text) {
        if (text == null) return -1;
        String s = text.toLowerCase(Locale.ROOT);
        boolean pm = Pattern.compile("'?s ?avonds|'?s ?middags|vanavond|vanmiddag|in de (?:avond|middag)|\\bpm\\b").matcher(s).find();
        boolean night = Pattern.compile("'?s ?nachts|vannacht").matcher(s).find();
        int h = -1, mi = 0;
        Matcher m;
        if ((m = Pattern.compile("\\b(\\d{1,2})[:.](\\d{2})\\b").matcher(s)).find()) { h = Integer.parseInt(m.group(1)); mi = Integer.parseInt(m.group(2)); }
        else if ((m = Pattern.compile("\\b" + NUM + "\\s+(over|voor)\\s+half\\s+" + NUM + "\\b").matcher(s)).find()) { // tien over half acht = 7:40
            int n = number99(m.group(1)), hh = number99(m.group(3));
            if (n >= 1 && n <= 29 && hh >= 0) { h = (hh + 23) % 24; mi = "over".equals(m.group(2)) ? 30 + n : 30 - n; }
        }
        else if ((m = Pattern.compile("\\b" + NUM + "\\s+(over|voor)\\s+" + NUM + "\\b").matcher(s)).find()) { // 25 over 7, vijf voor acht
            int n = number99(m.group(1)), hh = number99(m.group(3));
            if (n >= 1 && n <= 29 && hh >= 0) { if ("over".equals(m.group(2))) { h = hh; mi = n; } else { h = (hh + 23) % 24; mi = 60 - n; } }
        }
        else if ((m = Pattern.compile("\\bkwart\\s+(over|voor)\\s+" + NUM + "\\b").matcher(s)).find()) { int n = number99(m.group(2)); if (n >= 0) { h = "over".equals(m.group(1)) ? n : n - 1; mi = "over".equals(m.group(1)) ? 15 : 45; } }
        else if ((m = Pattern.compile("\\bhalf\\s+" + NUM + "\\b").matcher(s)).find()) { int n = number99(m.group(1)); if (n >= 0) { h = n - 1; mi = 30; } }
        else if ((m = Pattern.compile("\\b(?:om\\s+|op\\s+|voor\\s+)?" + NUM + "\\s*(?:uur|u)\\b").matcher(s)).find()) h = number99(m.group(1));
        else if ((m = Pattern.compile("\\b(?:om|op|voor)\\s+" + NUM + "\\b").matcher(s)).find()) h = number99(m.group(1));
        if (h < 0 || h > 24 || mi > 59) return -1;
        if (h == 24) h = 0;
        if (pm && h < 12) h += 12;
        if (night && h == 12) h = 0;
        return h * 60 + mi;
    }

    // ---------- de opdracht ----------

    /** Beleefdheid ervoor en erachter weg ("Kun je even …", "… alsjeblieft"). */
    static String strip(String q) {
        String s = q == null ? "" : q.trim().replace('’', '\'');
        if (s.indexOf(':') < 0) s = s.replaceAll("[\\s.!?]+$", ""); // na een dubbele punt hoort het bij de berichttekst
        for (int i = 0; i < 3; i++) {
            String t = s.replaceAll("(?iu)^(?:hey|hé|hoi|oké|ok|okay|assistent)[,!]?\\s+", "")
                    .replaceAll("(?iu)^(?:(?:kun|kan|wil|zou)\\s+(?:je|jij|u)\\s+(?:even\\s+|eens\\s+|alsjeblieft\\s+|misschien\\s+)*|graag\\s+|alsjeblieft\\s+|ik\\s+wil\\s+(?:graag\\s+)?(?:dat\\s+je\\s+)?|mag\\s+ik\\s+|doe\\s+eens\\s+|even\\s+)", "");
            // Achteraan alleen als er geen berichttekst staat ("…: hoi, bedankt" blijft heel)
            if (t.indexOf(':') < 0 && !t.toLowerCase(Locale.ROOT).contains(" dat ")) t = t.replaceAll("(?iu)[,\\s]+(?:alsjeblieft|aub|a\\.u\\.b|graag|even|svp|dank je|dankjewel|bedankt)$", "");
            if (t.equals(s)) break;
            s = t.trim();
        }
        return s.trim();
    }

    private static final Pattern P_TIMER = Pattern.compile("(?iu)\\b(timer|kookwekker|eierwekker|wekkertje)\\b");
    private static final String TW = "(?:timer|kookwekker|eierwekker|wekkertje)";
    private static final Pattern P_TIMER_THAT = Pattern.compile("(?iu)\\b(?:de|het|mijn|m'n|die|deze|jouw|je)\\s+" + TW + "\\b");
    private static final Pattern P_TIMER_ABOUT = Pattern.compile("(?iu)\\b" + TW + "\\s+(?:is|was|werkt|werkte|doet|deed|gaat|ging|kan|kon|heeft|had|van\\s+(?:de|het|mijn|m'n|die)\\b)");
    private static final Pattern P_TIMER_BARE = Pattern.compile("(?iu)^(?:(?:zet|start|stel|maak|begin)\\s+(?:een\\s+|nieuwe\\s+|n\\s+)?" + TW + "(?:\\s+(?:in|aan))?|(?:een\\s+|nieuwe\\s+)?" + TW + "\\s+(?:zetten|starten|instellen|maken)|" + TW + ")$");
    private static final Pattern P_WAKE_WORD = Pattern.compile("(?iu)\\b(?:wekker|alarm|wek\\s+me|maak\\s+me\\s+wakker)\\b");
    private static final Pattern P_OVER_DUR = Pattern.compile("(?iu)\\bover\\s+(?:(?:een|1|drie|3)\\s+)?(?:" + NUM + "\\s*(?:minuten|minuut|min|uur|uurtje|uren|seconden|seconde|sec)\\b|half\\s+uur|anderhalf\\s+uur|anderhalve\\s+minuut|kwartier)");
    /** "wek me over 20 minuten": een timer. Niet "om 25 over 7" of "tien over half acht" (dat is een kloktijd). */
    static boolean wakeIn(String s) {
        Matcher w = P_WAKE_WORD.matcher(s);
        if (!w.find()) return false;
        Matcher m = P_OVER_DUR.matcher(s);
        while (m.find()) {
            if (m.start() < w.end()) continue;
            String before = s.substring(0, m.start()).trim().toLowerCase(Locale.ROOT);
            String last = before.substring(before.lastIndexOf(' ') + 1);
            if (number99(last) >= 0 || "kwart".equals(last)) continue; // "tien over 7 uur"
            return true;
        }
        return false;
    }
    private static final Pattern P_ALARM = Pattern.compile("(?iu)^(?:(?:zet|maak|stel|plan)\\s+(?:een\\s+|nieuwe\\s+|n\\s+)?(?:wekker|alarm)(?:\\s+in)?|(?:een\\s+)?(?:wekker|alarm)|wek\\s+me|maak\\s+me\\s+wakker)\\b(.*)$");
    private static final Pattern P_RADIOWEKKER = Pattern.compile("(?iu)radiowekker|met\\s+de\\s+radio|wakker\\s+(?:worden\\s+)?met\\s+(?:de\\s+)?radio");
    private static final Pattern P_SLEEP = Pattern.compile("(?iu)^(?:(start|begin(?:\\s+met)?|zet|doe)\\s+(?:de\\s+)?slaappraat(?:\\s+(aan|uit|af))?|(stop|stoppen\\s+met|beëindig)\\s+(?:de\\s+)?slaappraat|slaappraat\\s+(aan|uit|starten|start|stoppen|stop))$");
    private static final Pattern P_RADIO_STOP = Pattern.compile("(?iu)^(?:(?:zet|doe)\\s+(?:de\\s+)?radio\\s+(?:uit|af)|stop\\s+(?:de\\s+)?radio|radio\\s+(?:uit|stop))$");
    private static final Pattern P_RADIO = Pattern.compile("(?iu)^(?:speel|start|zet|draai|luister\\s+naar|ik\\s+wil\\s+luisteren\\s+naar)\\s+(?:de\\s+)?(?:radio(?:zender)?|zender)(?:\\s+(.+?))?(?:\\s+(?:aan|op))?$");
    /** Iets anders dan een zender aanzetten: harder, uit, een tijd, een slaaptimer (dan de gewone assistent). */
    private static final Pattern RADIO_NOT = Pattern.compile("(?iu)\\b(?:uit|af|harder|zachter|luider|stiller|hard|zacht|volume|geluid|\\p{L}*timer|wekker|alarm|instelling\\w*)\\b"
            + "|\\bom\\s+(?:\\d|half|kwart|een|twee|drie|vier|vijf|zes|zeven|acht|negen|tien|elf|twaalf)");
    private static final Pattern P_STATION_ON = Pattern.compile("(?iu)^(?:zet|speel|start|draai)\\s+(.+?)(?:\\s+aan|\\s+op de radio)?$");
    private static final Pattern P_LISTEN = Pattern.compile("(?iu)^(?:speel|luister\\s+naar|ik\\s+wil\\s+luisteren\\s+naar)\\s+(.+)$");
    private static final Pattern STATION_HINT = Pattern.compile("(?iu)\\bnpo\\b|\\bradio\\b|\\bfm\\b|\\d\\s?fm\\b|qmusic|\\b538\\b|sky ?radio|veronica|100% ?nl|\\bslam\\b|\\bbnr\\b|\\bkink\\b|sublime|arrow|\\bjoe\\b|funx|omroep|rijnmond|\\bbbc\\b");
    private static final Pattern P_PODCAST = Pattern.compile("(?iu)^(?:speel|start|luister(?:\\s+verder)?\\s+naar|ga\\s+verder\\s+met|zet)\\s+(?:de\\s+)?(nieuwste\\s+aflevering\\s+van\\s+(?:de\\s+)?)?(?:podcast\\s+(.+?)|(.+?)\\s+podcast|podcast)(?:\\s+(?:aan|af))?$");
    private static final Pattern P_NEWEST = Pattern.compile("(?iu)^(?:speel|start|luister\\s+naar|zet)\\s+(?:de\\s+)?(?:nieuwste|laatste)\\s+aflevering\\s+(?:van|uit)\\s+(?:de\\s+podcast\\s+|podcast\\s+)?(.+?)(?:\\s+podcast)?$");
    private static final Pattern P_OPEN = Pattern.compile("(?iu)^(?:open|toon|ga\\s+naar|naar|start)\\s+(?:de\\s+|het\\s+|mijn\\s+|m'n\\s+)?(?:app\\s+|tool\\s+|scherm\\s+)?(.+?)(?:\\s+(?:open|openen|zien|maar))?$"
            + "|^laat\\s+(?:me\\s+|mij\\s+)?(?:de\\s+|het\\s+|mijn\\s+|m'n\\s+)?(.+?)\\s+zien$");
    private static final String MSGW = "(sms(?:'?je)?|smsje|tekstbericht|berichtje|bericht|whatsapp(?:je|bericht|-bericht)?|appje)";
    private static final String SEP = "\\s*(?::|,|\\s+dat\\s+|\\s+met\\s+(?:de\\s+tekst\\s+)?)\\s*";
    private static final Pattern P_MSG1 = Pattern.compile("(?iu)^(?:stuur|verstuur|zend|schrijf)\\s+(?:een\\s+)?" + MSGW + "\\s+(?:naar|aan)\\s+(.+?)(?:" + SEP + "(.+))?$");
    private static final Pattern P_MSG2 = Pattern.compile("(?iu)^(?:stuur|verstuur)\\s+(.+?)\\s+(?:een\\s+)?" + MSGW + "(?:" + SEP + "(.+))?$");
    private static final Pattern P_MSG3 = Pattern.compile("(?iu)^(sms|sms'?en|whatsapp|app)\\s+(.+?)" + SEP + "(.+)$");
    private static final Pattern P_MSG4 = Pattern.compile("(?iu)^(?:laat\\s+(.+?)\\s+(?:even\\s+)?weten|vertel\\s+(.+?)|zeg\\s+tegen\\s+(.+?))\\s+(?:even\\s+)?dat\\s+(.+)$");
    /** Woorden die geen naam zijn ("vertel me een grap", "laat maar weten", "zeg tegen jezelf"). */
    private static final Pattern NOT_NAME = Pattern.compile("(?iu)(?:^|\\s)(?:me|mij|je|jij|jou|jezelf|mezelf|mijzelf|zichzelf|u|uzelf|ons|onszelf|hem|haar|hen|hun|ze|zij|wij|we|eens|maar|zien|even|nog|het|dat|dit|die|deze|iets|wat|niks|niets|niemand|iedereen|alles|allemaal|nou|toch|gewoon|een|over|grap|mop|verhaal|ik)(?=\\s|$)");

    /** Bestaat er een contact met deze naam? In de app: je eigen contacten (AiBridge). */
    interface Known { boolean has(String name); }
    private static final Pattern P_VIA = Pattern.compile("(?iu)\\s+(?:via|op|met|per)\\s+(whatsapp|sms)$");

    /** De opdracht in de vraag, of null als het geen duidelijke opdracht is (dan gewoon de assistent). */
    static Cmd parse(String question) { return parse(question, null); }

    /** known: om een bericht met alleen een naam ("app Jos dat …", "laat Jos weten dat …") te herkennen moet die naam
     *  in je contacten staan; anders is het waarschijnlijk een gewone vraag ("vertel eens dat verhaal"). null = niet na te kijken. */
    static Cmd parse(String question, Known known) {
        if (question == null) return null;
        String raw = question.trim();
        if (raw.isEmpty() || raw.length() > 500 || raw.indexOf('\n') >= 0 && raw.indexOf('\n') < 15) return null;
        String s = strip(raw);
        String l = s.toLowerCase(Locale.ROOT);
        if (l.startsWith("onthoud") || l.startsWith("vergeet") || l.startsWith("herinner")) return null; // geheugen en herinneringen
        if (l.matches("^(wat|hoe|waarom|waar|wanneer|welke|wie|hoeveel|is|zijn|heb|hebben)\\b.*")) return null; // een vraag, geen opdracht
        Matcher m;
        // Slaappraat
        if ((m = P_SLEEP.matcher(s)).matches()) {
            Cmd c = new Cmd(); c.type = "slaap";
            String w = (m.group(1) != null ? m.group(1) + " " + (m.group(2) == null ? "" : m.group(2)) : m.group(3) != null ? m.group(3) : m.group(4)).toLowerCase(Locale.ROOT);
            c.stop = w.startsWith("stop") || w.contains("uit") || w.contains(" af") || w.startsWith("beëindig");
            return c;
        }
        // Timer (ook "wekker over 20 minuten")
        boolean wake = wakeIn(s);
        if (P_TIMER.matcher(s).find() || wake) {
            if (l.matches(".*\\b(?:stop|uit|annuleer|verwijder)\\b.*")) return null; // dat doe je in de Klok-app
            if (P_TIMER_THAT.matcher(s).find() || P_TIMER_ABOUT.matcher(s).find()) return null; // over een timer die er al is (van de oven, het scherm)
            int d = duration(s);
            if (d > 0) {
                if (!l.matches("^(?:zet|start|stel|maak|begin|plan|timer|kookwekker|eierwekker|wekker|wek|een|nieuwe|\\d+)\\b.*")) return null;
                // Zonder gebiedende wijs vooraan: een zin over een timer ("een timer van 5 minuten is te kort")
                if (!l.matches("^(?:zet|start|stel|maak|begin|plan|wek)\\b.*") && l.matches(".*\\b(?:is|was|zijn|waren|werkt|duurt|duurde|lijkt|vind|vond|klinkt|heb|had|heeft)\\b.*")) return null;
            }
            else if (!P_TIMER_BARE.matcher(s).matches()) return null; // zonder duur alleen "zet een timer" en zo, geen zin over timers
            Cmd c = new Cmd(); c.type = "timer";
            if (d > 0) { if (!validSecs(d)) return null; c.secs = d; } else c.need = "duur";
            Matcher lb = Pattern.compile("(?iu)\\bvoor\\s+(?:de\\s+|het\\s+|mijn\\s+)?([\\p{L}][\\p{L} '-]{1,40})$").matcher(s);
            if (lb.find() && duration(lb.group(1)) < 0) c.label = cap(lb.group(1).trim());
            if (wake && c.label.isEmpty()) c.label = "Wekker";
            return c;
        }
        // Wekker (een nieuwe in de Klok-app; "de wekker" = de radiowekker van de app, dat is een instelling)
        if (P_ALARM.matcher(s).matches() || P_RADIOWEKKER.matcher(s).find() && l.matches(".*\\b(?:wekker|wek me|wakker)\\b.*")) {
            if (l.matches(".*\\b(?:de|mijn|m'n)\\s+wekker\\b.*") && !P_RADIOWEKKER.matcher(s).find()) return null;
            if (l.matches(".*\\b(?:uit|af|stop|annuleer|verwijder)$")) return null;
            Cmd c = new Cmd(); c.type = "wekker";
            c.radio = P_RADIOWEKKER.matcher(s).find();
            c.min = clock(s);
            if (c.min < 0) c.need = "tijd";
            Matcher dw = Pattern.compile("(?iu)\\b(overmorgen|maandag|dinsdag|woensdag|donderdag|vrijdag|zaterdag|zondag|elke dag|werkdagen|elke werkdag)\\b").matcher(s);
            if (dw.find()) c.dayWord = dw.group(1).toLowerCase(Locale.ROOT);
            else if (l.matches(".*\\bmorgen(?:ochtend|vroeg)?\\b.*")) c.dayWord = "morgen";
            Matcher lb = Pattern.compile("(?iu)\\bvoor\\s+(?:de\\s+|het\\s+|mijn\\s+)?([\\p{L}][\\p{L} '-]{1,40})$").matcher(s);
            if (lb.find() && clock("om " + lb.group(1)) < 0 && !lb.group(1).toLowerCase(Locale.ROOT).matches("(?:morgen|vandaag|maandag|dinsdag|woensdag|donderdag|vrijdag|zaterdag|zondag).*")) c.label = cap(lb.group(1).trim());
            return c;
        }
        // Radio
        if (P_RADIO_STOP.matcher(s).matches()) { Cmd c = new Cmd(); c.type = "radio"; c.stop = true; return c; }
        if ((m = P_RADIO.matcher(s)).matches()) {
            if (m.group(1) != null && RADIO_NOT.matcher(m.group(1)).find()) return null;
            Cmd c = new Cmd(); c.type = "radio"; c.q = m.group(1) == null ? "" : m.group(1).trim().replaceAll("(?iu)^(?:op|naar)\\s+", "").replaceAll("(?iu)(?:^|\\s+)(?:aan|op)$", "").trim(); return c;
        }
        // Podcast
        if ((m = P_NEWEST.matcher(s)).matches()) { Cmd c = new Cmd(); c.type = "podcast"; c.newest = true; c.q = m.group(1).trim(); return c; }
        if ((m = P_PODCAST.matcher(s)).matches()) {
            Cmd c = new Cmd(); c.type = "podcast"; c.newest = m.group(1) != null;
            c.q = m.group(2) != null ? m.group(2).trim() : m.group(3) != null ? m.group(3).trim() : "";
            return c;
        }
        // Berichten (eerst: "stuur Jos een sms" mag geen "open" worden)
        Cmd msg = message(s);
        if (msg != null && msg.weak && (known == null || !known.has(msg.to))) return null; // geen contact met die naam: gewoon de assistent
        if (msg != null) return msg;
        // Een tool openen
        if ((m = P_OPEN.matcher(s)).matches()) {
            String t = toolFor((m.group(1) != null ? m.group(1) : m.group(2)).replaceAll("(?iu)^(?:de|het|mijn|m'n)\\s+", ""));
            if (!t.isEmpty()) { Cmd c = new Cmd(); c.type = "open"; c.tool = t; return c; }
        }
        // Een zender ("zet NPO Radio 2 aan", "speel Qmusic") of iets anders om af te spelen ("speel Met het Oog op Morgen")
        if ((m = P_STATION_ON.matcher(s)).matches() && STATION_HINT.matcher(m.group(1)).find() && !RADIO_NOT.matcher(l).find()) {
            Cmd c = new Cmd(); c.type = "radio"; c.q = m.group(1).trim(); return c;
        }
        if ((m = P_LISTEN.matcher(s)).matches() && m.group(1).length() <= 60) {
            String q = m.group(1).trim().replaceAll("(?iu)^(?:naar\\s+)?(?:me|mij|je|jou|ons|hem|haar)\\b\\s*", "").replaceAll("(?iu)^(?:de|het|een)(?:\\s+|$)", "").trim();
            if (q.length() < 2 || RADIO_NOT.matcher(q).find()) return null;
            Cmd c = new Cmd(); c.type = "speel"; c.q = q; return c;
        }
        return null;
    }

    /** Voor de interface (die de kaart laat zien). */
    static org.json.JSONObject json(Cmd c) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject().put("type", c.type);
        if (!c.q.isEmpty()) o.put("q", c.q);
        if (!c.label.isEmpty()) o.put("label", c.label);
        if (!c.to.isEmpty()) o.put("to", c.to);
        if (!c.number.isEmpty()) o.put("number", c.number);
        if ("sms".equals(c.type) || "wa".equals(c.type)) o.put("text", c.text);
        if (!c.tool.isEmpty()) o.put("tool", c.tool);
        if (!c.need.isEmpty()) o.put("need", c.need);
        if (c.secs > 0) o.put("secs", c.secs);
        if (c.min >= 0) o.put("min", c.min);
        if (c.stop) o.put("stop", true);
        if (c.radio) o.put("radio", true);
        if (c.newest) o.put("newest", true);
        if (!c.dayWord.isEmpty()) o.put("day", c.dayWord);
        return o;
    }

    private static String cap(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    private static final String AUX = "ben|bent|is|zijn|heb|hebt|heeft|hebben|kan|kun|kunt|kunnen|moet|moeten|zal|zul|zult|zullen|wil|wilt|willen|ga|gaat|gaan|kom|komt|komen|word|wordt|worden|mag|mogen";
    private static final Pattern SUBJ = Pattern.compile("(?iu)^(ik|we|wij|jij|je|hij|zij|ze|u|het|jullie)\\s+(.+)$");

    /** "dat ik later ben" → "Ik ben later": de werkwoordsvorm naar voren, zoals in een gewone zin. Zuiver (getest). */
    static String mainClause(String sub) {
        String s = sub == null ? "" : sub.trim().replaceAll("[\\s]+", " ");
        Matcher m = SUBJ.matcher(s);
        if (!m.matches()) return cap(s);
        String[] w = m.group(2).split(" ");
        if (w.length < 2) return cap(s);
        int fin = w.length - 1;
        if (w[w.length - 2].toLowerCase(Locale.ROOT).matches(AUX) && !w[w.length - 1].toLowerCase(Locale.ROOT).matches(AUX)) fin = w.length - 2;
        StringBuilder b = new StringBuilder(m.group(1)).append(' ').append(w[fin]);
        for (int i = 0; i < w.length; i++) if (i != fin) b.append(' ').append(w[i]);
        return cap(b.toString());
    }

    /** Een sms of WhatsApp-bericht klaarzetten: aan wie, met welke tekst. */
    static Cmd message(String s) {
        Matcher m;
        String chan, to, text, sepWord = "";
        boolean weak = false;
        String t = s;
        Matcher via = P_VIA.matcher(t);
        String viaChan = "";
        if (via.find()) { viaChan = via.group(1).toLowerCase(Locale.ROOT); t = t.substring(0, via.start()); }
        if ((m = P_MSG1.matcher(t)).matches()) { chan = m.group(1); to = m.group(2); text = m.group(3); }
        else if ((m = P_MSG2.matcher(t)).matches()) { chan = m.group(2); to = m.group(1); text = m.group(3); weak = true; }
        else if ((m = P_MSG3.matcher(t)).matches()) { chan = m.group(1); to = m.group(2); text = m.group(3); weak = true; }
        else if ((m = P_MSG4.matcher(t)).matches()) { chan = "sms"; to = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3); text = m.group(4); sepWord = "dat"; weak = true; }
        else return null;
        if (sepWord.isEmpty() && text != null) {
            int at = t.length() - text.length();
            String before = t.substring(0, at).toLowerCase(Locale.ROOT);
            if (before.matches(".*\\sdat\\s*$")) sepWord = "dat";
        }
        to = to.trim().replaceAll("(?iu)^(?:de|het)\\s+", "");
        if (to.isEmpty() || to.length() > 40 || to.matches("(?iu).*\\b(?:een|me|mij|iets|dat)$")) return null;
        String num = number(to);
        if (num.isEmpty() && !to.matches("(?iu)[\\p{L}][\\p{L} .'-]{0,39}")) return null;
        if (num.isEmpty() && NOT_NAME.matcher(to).find()) return null;
        Cmd c = new Cmd();
        c.weak = weak && num.isEmpty();
        String ch = (viaChan.isEmpty() ? chan : viaChan).toLowerCase(Locale.ROOT);
        c.type = ch.startsWith("whatsapp") || ch.startsWith("app") ? "wa" : "sms";
        c.to = num.isEmpty() ? cap(to) : "";
        c.number = num;
        String body = text == null ? "" : text.trim();
        if ("dat".equals(sepWord)) { c.viaDat = true; body = mainClause(body); }
        c.text = cleanText(body, MAX_TEXT);
        if (c.text.isEmpty()) c.need = "tekst";
        return c;
    }
}
