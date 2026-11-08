package nl.rene.tools;

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
}
