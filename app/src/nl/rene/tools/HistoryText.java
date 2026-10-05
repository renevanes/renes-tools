package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Begrensde meldingstekst, veilige HTML en stabiele deduplicatie. */
final class HistoryText {
    private HistoryText() { }
    static String clean(CharSequence value, int max) {
        String s = value == null ? "" : value.toString().replace("\u0000", "");
        if (s.length() <= max) return s;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
    static String html(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    static String fingerprint(String key, long time, String title, String text) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (String part : new String[]{key, Long.toString(time), title, text}) {
            byte[] b = part.getBytes(StandardCharsets.UTF_8);
            sha.update(new byte[]{(byte)(b.length >>> 24), (byte)(b.length >>> 16), (byte)(b.length >>> 8), (byte)b.length});
            sha.update(b);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : sha.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
