package nl.rene.tools;

import java.util.regex.Pattern;

/** Losse vermeldingen die Whisper bij stilte of ruis kan verzinnen; geen woorden uit gewone zinnen halen. */
final class SpeechText {
    private SpeechText() { }

    private static final Pattern SOUND = Pattern.compile("(?:\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*|[♪♫\\s.!…])+");
    private static final Pattern CREDIT = Pattern.compile(
            "(?iu)^(?:(?:\\(\\s*c\\s*\\)|©|copyright)\\s*tv\\s+gelderland(?:\\s*[-–,:]?\\s*(?:19|20)\\d{2})?"
            + "|tv\\s+gelderland\\s*[-–,:]?\\s*(?:19|20)\\d{2}"
            + "|(?:ondertiteld|ondertiteling|ondertitels?\\s+ingediend)\\s+door\\s+(?:de\\s+)?amara\\.org(?:\\s+gemeenschap)?"
            + "|(?:subtitles?|captions?\\s+submitted)\\s+by\\s+(?:the\\s+)?amara\\.org(?:\\s+community)?)[.!…]*$");

    /** Alleen een volledig geluidslabel of herkenbare aftiteling overslaan. Gewone spraak blijft ongewijzigd. */
    static String segment(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.isEmpty() || SOUND.matcher(t).matches() || CREDIT.matcher(t).matches()) return "";
        return t;
    }
}
