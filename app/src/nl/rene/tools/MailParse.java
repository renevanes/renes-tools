package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Een e-mail (.eml, MIME) lezen: kopregels (Van, Aan, Datum, Onderwerp) en de tekst, bij voorkeur de HTML-versie.
 * Bijlagen worden alleen bij naam genoemd. Puur Java, zodat het op een gewone JVM te testen is.
 */
final class MailParse {

    static final class Mail {
        final Map<String, String> headers = new LinkedHashMap<>();
        String text = "", html = "";
        final java.util.List<String> attachments = new java.util.ArrayList<>();
        String h(String k) { String v = headers.get(k.toLowerCase(Locale.ROOT)); return v == null ? "" : v; }
    }

    static Mail parse(byte[] raw) {
        Mail m = new Mail();
        part(raw, m, 0, true);
        return m;
    }

    /** Lijkt dit op een e-mail? (Een paar bekende kopregels bovenaan.) */
    static boolean looksLikeMail(byte[] raw) {
        String head = new String(raw, 0, Math.min(raw.length, 4096), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        int n = 0;
        for (String k : new String[]{"\nfrom:", "\nsubject:", "\ndate:", "\nmime-version:", "\nto:", "\nreceived:"}) if (("\n" + head).contains(k)) n++;
        return n >= 2;
    }

    static void part(byte[] raw, Mail m, int depth, boolean top) {
        if (depth > 10) return;
        int split = headerEnd(raw);
        Map<String, String> h = headers(new String(raw, 0, split, StandardCharsets.ISO_8859_1));
        if (top) m.headers.putAll(h);
        byte[] body = split >= raw.length ? new byte[0] : java.util.Arrays.copyOfRange(raw, Math.min(raw.length, split + (split + 1 < raw.length && raw[split] == '\r' ? 4 : 2)), raw.length);
        if (split == raw.length) body = new byte[0];
        String ct = h.getOrDefault("content-type", "text/plain");
        String type = ct.split(";")[0].trim().toLowerCase(Locale.ROOT);
        String disp = h.getOrDefault("content-disposition", "").toLowerCase(Locale.ROOT);
        String fname = param(h.getOrDefault("content-disposition", ""), "filename");
        if (fname.isEmpty()) fname = param(ct, "name");
        if (type.startsWith("multipart/")) {
            String b = param(ct, "boundary");
            if (b.isEmpty()) return;
            for (byte[] sub : splitParts(body, b)) part(sub, m, depth + 1, false);
            return;
        }
        if (type.equals("message/rfc822") && !disp.startsWith("attachment")) { part(body, m, depth + 1, false); return; }
        if (disp.startsWith("attachment") || (!fname.isEmpty() && !type.startsWith("text/"))) { m.attachments.add(fname.isEmpty() ? type : decodeWords(fname)); return; }
        byte[] dec = transfer(body, h.getOrDefault("content-transfer-encoding", "7bit"));
        String text = new String(dec, charset(param(ct, "charset")));
        if (type.equals("text/html") && m.html.isEmpty()) m.html = text;
        else if (type.equals("text/plain") && m.text.isEmpty()) m.text = text;
    }

    static int headerEnd(byte[] b) {
        for (int i = 0; i + 1 < b.length; i++) {
            if (b[i] == '\n' && b[i + 1] == '\n') return i;
            if (i + 3 < b.length && b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') return i;
        }
        return b.length;
    }

    static Map<String, String> headers(String s) {
        Map<String, String> h = new LinkedHashMap<>();
        String[] lines = s.replace("\r\n", "\n").split("\n");
        String key = null; StringBuilder val = new StringBuilder();
        for (String l : lines) {
            if (!l.isEmpty() && (l.charAt(0) == ' ' || l.charAt(0) == '\t') && key != null) { val.append(' ').append(l.trim()); continue; }
            if (key != null && !h.containsKey(key)) h.put(key, decodeWords(val.toString().trim()));
            int c = l.indexOf(':');
            if (c <= 0) { key = null; continue; }
            key = l.substring(0, c).trim().toLowerCase(Locale.ROOT);
            val = new StringBuilder(l.substring(c + 1).trim());
        }
        if (key != null && !h.containsKey(key)) h.put(key, decodeWords(val.toString().trim()));
        return h;
    }

    static String param(String header, String name) {
        Matcher m = Pattern.compile("(?i)(?:^|;)\\s*" + Pattern.quote(name) + "\\*?\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)").matcher(header);
        if (!m.find()) return "";
        String v = m.group(2) != null ? m.group(2) : m.group(1);
        // RFC 2231: utf-8''naam%20met%20spaties
        int q = v.indexOf("''");
        if (q >= 0 && v.substring(0, q).matches("[A-Za-z0-9_-]+")) {
            try { return java.net.URLDecoder.decode(v.substring(q + 2).replace("+", "%2B"), v.substring(0, q)); } catch (Exception ignored) { }
        }
        return v;
    }

    static java.util.List<byte[]> splitParts(byte[] body, String boundary) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int i = indexOf(body, marker, 0);
        while (i >= 0) {
            int start = i + marker.length;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') break; // einde
            while (start < body.length && body[start] != '\n') start++;
            start++;
            int next = indexOf(body, marker, start);
            int end = next < 0 ? body.length : next;
            if (end > start && body[end - 1] == '\n') end--;
            if (end > start && body[end - 1] == '\r') end--;
            if (end > start) out.add(java.util.Arrays.copyOfRange(body, start, end));
            i = next;
        }
        return out;
    }

    static int indexOf(byte[] h, byte[] n, int from) {
        outer: for (int i = Math.max(0, from); i <= h.length - n.length; i++) { for (int k = 0; k < n.length; k++) if (h[i + k] != n[k]) continue outer; return i; }
        return -1;
    }

    static byte[] transfer(byte[] b, String enc) {
        enc = enc.trim().toLowerCase(Locale.ROOT);
        if (enc.equals("base64")) {
            return base64(b);
        }
        if (enc.equals("quoted-printable")) return qp(b, false);
        return b;
    }

    /** Base64 uitpakken; slaat regeleinden en andere tekens over (java.util.Base64 bestaat pas vanaf Android 8). */
    static byte[] base64(byte[] in) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(in.length * 3 / 4 + 3);
        int acc = 0, bits = 0;
        for (byte x : in) {
            int c = x & 0xff, v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+' || c == '-') v = 62;
            else if (c == '/' || c == '_') v = 63;
            else if (c == '=') break;
            else continue;
            acc = (acc << 6) | v; bits += 6;
            if (bits >= 8) { bits -= 8; o.write((acc >> bits) & 0xff); }
        }
        return o.toByteArray();
    }

    static byte[] qp(byte[] b, boolean underscoreSpace) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length);
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xff;
            if (c == '=' && i + 1 < b.length) {
                if (b[i + 1] == '\n') { i++; continue; }
                if (b[i + 1] == '\r' && i + 2 < b.length && b[i + 2] == '\n') { i += 2; continue; }
                if (i + 2 < b.length) {
                    int h1 = Character.digit(b[i + 1], 16), h2 = Character.digit(b[i + 2], 16);
                    if (h1 >= 0 && h2 >= 0) { o.write(h1 * 16 + h2); i += 2; continue; }
                }
                o.write(c);
            } else if (underscoreSpace && c == '_') o.write(' ');
            else o.write(c);
        }
        return o.toByteArray();
    }

    static Charset charset(String name) {
        try { if (name != null && !name.isEmpty()) return Charset.forName(name.trim()); } catch (Exception ignored) { }
        return StandardCharsets.UTF_8;
    }

    /** =?utf-8?B?...?= en =?iso-8859-1?Q?...?= in kopregels. */
    static String decodeWords(String s) {
        Matcher m = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=(\\s+(?==\\?))?").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String r;
            try {
                byte[] bytes = m.group(2).equalsIgnoreCase("B") ? base64(m.group(3).getBytes(StandardCharsets.ISO_8859_1)) : qp(m.group(3).getBytes(StandardCharsets.ISO_8859_1), true);
                r = new String(bytes, charset(m.group(1)));
            } catch (Exception e) { r = m.group(0); }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }
}
