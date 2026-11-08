package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tekstvoorbereiding voor de gedeelde lokale voorleesfunctie (ReadAloud). */
final class AiSpeech {
    private AiSpeech() { }

    private static final Pattern CITE = Pattern.compile("\\s*\\[\\d+\\]");

    /** Tekst zonder opmaak om voor te lezen: geen sterretjes, kopjes, opsommingstekens of verwijzingen als [1]. */
    static String plain(String md) {
        if (md == null) return "";
        StringBuilder out = new StringBuilder();
        for (String raw : md.split("\n")) {
            String l = raw.trim().replaceAll("\\s+", " "); // eerst: lange rijen spaties maken de regels hieronder niet traag
            if (l.isEmpty() || l.matches("[-*_=]{3,}")) continue;
            l = l.replaceAll("^#{1,6}\\s+", "").replaceAll("^[-*•]\\s+", "").replaceAll("^\\d+[.)]\\s+", "").replaceAll("^>\\s*", "");
            boolean shortLine = l.length() <= 2000; // een enorme regel (lus van het model): geen opmaakregels, die zijn dan kwadratisch traag
            if (shortLine) l = l.replaceAll("\\*\\*([^*]+)\\*\\*", "$1").replaceAll("__([^_]+)__", "$1").replaceAll("(^|[\\s(])\\*([^*\\s][^*]*)\\*", "$1$2").replaceAll("`([^`]*)`", "$1");
            if (shortLine) l = l.replaceAll("\\[([^\\]]+)\\]\\((?:https?://)?[^)]*\\)", "$1"); // [tekst](link) → tekst
            l = CITE.matcher(l).replaceAll("");
            l = l.replaceAll("[*_`#|]", " ").replaceAll("\\s+", " ").replaceAll("\\s+([.,!?;:])", "$1").trim();
            if (l.isEmpty()) continue;
            if (!l.matches(".*[.!?:;…]$")) l += ".";
            if (out.length() > 0) out.append('\n');
            out.append(l);
        }
        return out.toString();
    }

    private static final Pattern SENT = Pattern.compile("[^.!?…\\n]*[.!?…\\n]+|[^.!?…\\n]+$");

    /** In stukken die de spraakmotor aankan (bij voorkeur op een zinsgrens). */
    static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        Matcher m = SENT.matcher(text == null ? "" : text);
        while (m.find()) {
            String s = m.group();
            if (s.isEmpty()) continue;
            while (s.length() > max) { // één enorme zin: hard knippen
                if (cur.length() > 0) { out.add(cur.toString().trim()); cur.setLength(0); }
                int n = max; if (Character.isHighSurrogate(s.charAt(n - 1))) n--;
                out.add(s.substring(0, n).trim()); s = s.substring(n);
            }
            if (cur.length() + s.length() > max) { out.add(cur.toString().trim()); cur.setLength(0); }
            cur.append(s);
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        out.removeIf(String::isEmpty);
        return out;
    }

}
