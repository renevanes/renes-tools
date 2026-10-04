package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Leest vCard-bestanden (2.1, 3.0 en 4.0, ook van andere telefoons: gevouwen regels,
 * quoted-printable en tekensets) naar contacten in het formaat van de app (pure Java, los getest).
 */
final class VCard {

    private VCard() { }

    static List<ContactsDiff.Rec> parse(String text) {
        List<ContactsDiff.Rec> out = new ArrayList<>();
        List<String> lines = unfold(text);
        ContactsDiff.Rec cur = null;
        String fn = null, n = null;
        for (String raw : lines) {
            int colon = colonIndex(raw);
            if (colon < 0) continue;
            String head = raw.substring(0, colon), value = raw.substring(colon + 1);
            String[] hp = head.split(";");
            String name = hp[0].toUpperCase(Locale.ROOT);
            int dot = name.indexOf('.');
            if (dot >= 0) name = name.substring(dot + 1); // "item1.TEL"
            String params = head.length() > hp[0].length() ? head.substring(hp[0].length() + 1).toUpperCase(Locale.ROOT) : "";
            if (name.equals("BEGIN") && value.trim().equalsIgnoreCase("VCARD")) { cur = new ContactsDiff.Rec(); fn = null; n = null; continue; }
            if (cur == null) continue;
            if (name.equals("END") && value.trim().equalsIgnoreCase("VCARD")) {
                String full = fn != null && !fn.isEmpty() ? fn : nameFromN(n);
                if (full == null || full.isEmpty()) {
                    String ph = cur.get("Telefoon"), em = cur.get("E-mail");
                    full = !ph.isEmpty() ? Contacts.splitLabel(ph.split("\n")[0])[0] : !em.isEmpty() ? em.split("\n")[0] : "";
                }
                cur.name = full;
                cur.key = "vcf" + out.size();
                cur.put("Naam", full);
                if (!full.isEmpty() || !cur.f.isEmpty()) out.add(cur);
                cur = null;
                continue;
            }
            String v = decode(value, params);
            switch (name) {
                case "FN": fn = unesc(v).trim(); break;
                case "N": n = v; break;
                case "TEL": {
                    String num = unesc(v).trim();
                    if (num.toLowerCase(Locale.ROOT).startsWith("tel:")) num = num.substring(4);
                    String label = params.contains("CELL") ? "mobiel" : params.contains("FAX") ? "fax"
                            : params.contains("WORK") ? "werk" : params.contains("HOME") ? "thuis" : "";
                    cur.add("Telefoon", Contacts.withLabel(num, label));
                    break;
                }
                case "EMAIL": cur.add("E-mail", unesc(v).trim()); break;
                case "ORG": cur.put("Bedrijf", unesc(split(v)[0]).trim()); break;
                case "TITLE": cur.put("Functie", unesc(v).trim()); break;
                case "ADR": {
                    StringBuilder b = new StringBuilder();
                    for (String p : split(v)) { String x = unesc(p).trim(); if (!x.isEmpty()) { if (b.length() > 0) b.append(", "); b.append(x); } }
                    cur.add("Adres", b.toString());
                    break;
                }
                case "URL": cur.add("Website", unesc(v).trim()); break;
                case "NICKNAME": cur.add("Bijnaam", unesc(v).trim()); break;
                case "BDAY": cur.put("Verjaardag", unesc(v).trim()); break;
                case "NOTE": cur.put("Notitie", unesc(v).trim()); break;
                default: break;
            }
        }
        return out;
    }

    /** Doorgelopen regels samenvoegen (spatie/tab aan het begin, of "=" aan het eind bij quoted-printable). */
    static List<String> unfold(String text) {
        String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> out = new ArrayList<>();
        for (String l : raw) {
            if (!out.isEmpty() && out.get(out.size() - 1).endsWith("=")
                    && out.get(out.size() - 1).toUpperCase(Locale.ROOT).contains("QUOTED-PRINTABLE")) {
                // Zachte regeleinde van quoted-printable: de volgende regel hoort er letterlijk achter
                String prev = out.get(out.size() - 1);
                out.set(out.size() - 1, prev.substring(0, prev.length() - 1) + l);
            } else if (!out.isEmpty() && (l.startsWith(" ") || l.startsWith("\t"))) {
                out.set(out.size() - 1, out.get(out.size() - 1) + l.substring(1));
            } else out.add(l);
        }
        return out;
    }

    /** Eerste dubbele punt buiten aanhalingstekens (parameters kunnen ":" bevatten). */
    static int colonIndex(String s) {
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') q = !q;
            else if (ch == ':' && !q) return i;
        }
        return -1;
    }

    static String decode(String v, String params) {
        if (!params.contains("QUOTED-PRINTABLE")) return v;
        Charset cs = StandardCharsets.UTF_8;
        int ci = params.indexOf("CHARSET=");
        if (ci >= 0) {
            String name = params.substring(ci + 8).split("[;:]")[0].replace("\"", "");
            try { cs = Charset.forName(name); } catch (Exception ignored) { }
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '=' && i + 2 < v.length()) {
                try { b.write(Integer.parseInt(v.substring(i + 1, i + 3), 16)); i += 2; continue; } catch (Exception ignored) { }
            }
            byte[] bs = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
            b.write(bs, 0, bs.length);
        }
        return new String(b.toByteArray(), cs);
    }

    /** Splitst op ";" maar niet op "\;". */
    static String[] split(String v) {
        List<String> parts = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\' && i + 1 < v.length()) { b.append(ch).append(v.charAt(++i)); continue; }
            if (ch == ';') { parts.add(b.toString()); b.setLength(0); continue; }
            b.append(ch);
        }
        parts.add(b.toString());
        return parts.toArray(new String[0]);
    }

    static String unesc(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\' && i + 1 < v.length()) {
                char nx = v.charAt(++i);
                b.append(nx == 'n' || nx == 'N' ? '\n' : nx);
            } else b.append(ch);
        }
        return b.toString();
    }

    static String nameFromN(String n) {
        if (n == null) return null;
        String[] p = split(n);
        String family = p.length > 0 ? unesc(p[0]).trim() : "", given = p.length > 1 ? unesc(p[1]).trim() : "",
                middle = p.length > 2 ? unesc(p[2]).trim() : "";
        StringBuilder b = new StringBuilder();
        for (String x : new String[]{given, middle, family}) if (!x.isEmpty()) { if (b.length() > 0) b.append(' '); b.append(x); }
        return b.toString();
    }
}
