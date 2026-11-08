package nl.rene.tools;

import java.io.ByteArrayOutputStream;

/** Base64 zonder nieuwere Android-API, ook te gebruiken door de zuivere EPUB-tests. */
final class EreaderBytes {
    private EreaderBytes() { }
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length + 2) / 3 * 4);
        for (int i = 0; i < bytes.length; i += 3) {
            int a = bytes[i] & 255, b = i + 1 < bytes.length ? bytes[i + 1] & 255 : 0, c = i + 2 < bytes.length ? bytes[i + 2] & 255 : 0;
            out.append(B64.charAt(a >>> 2)).append(B64.charAt((a & 3) << 4 | b >>> 4));
            out.append(i + 1 < bytes.length ? B64.charAt((b & 15) << 2 | c >>> 6) : '=').append(i + 2 < bytes.length ? B64.charAt(c & 63) : '=');
        }
        return out.toString();
    }
    static byte[] decode(String source) throws Exception {
        String s = source.replaceAll("\\s+", ""); if (s.length() % 4 != 0) throw new Exception("base64");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i += 4) {
            int a = B64.indexOf(s.charAt(i)), b = B64.indexOf(s.charAt(i + 1)), c = s.charAt(i + 2) == '=' ? 0 : B64.indexOf(s.charAt(i + 2)), d = s.charAt(i + 3) == '=' ? 0 : B64.indexOf(s.charAt(i + 3));
            if (a < 0 || b < 0 || c < 0 || d < 0 || (s.charAt(i + 2) == '=' && s.charAt(i + 3) != '=') || (i + 4 < s.length() && (s.charAt(i + 2) == '=' || s.charAt(i + 3) == '='))) throw new Exception("base64");
            out.write(a << 2 | b >>> 4); if (s.charAt(i + 2) != '=') out.write((b & 15) << 4 | c >>> 2); if (s.charAt(i + 3) != '=') out.write((c & 3) << 6 | d);
        }
        return out.toByteArray();
    }
    static String mime(byte[] b, String name) {
        if (b.length >= 8 && b[0] == (byte)137 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && b[0] == (byte)255 && b[1] == (byte)216 && b[2] == (byte)255) return "image/jpeg";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".svg") ? "image/svg+xml" : "";
    }
}
