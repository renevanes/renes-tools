package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;

/**
 * Pagina's uit een PDF halen zonder iets om te zetten: tekst, lettertypen en tekeningen blijven precies zoals ze zijn.
 * Leest gewone kruisverwijzingstabellen én de nieuwere compacte vorm (xref-streams met object-streams, PDF 1.5+),
 * en herstelt een kapotte tabel door het bestand te doorzoeken. Beveiligde (versleutelde) PDF's worden geweigerd.
 * Puur Java (geen Android), zodat het op een gewone JVM getest kan worden.
 */
final class PdfSplit {

    static class PdfException extends IOException { PdfException(String m) { super(m); } }
    static final class Encrypted extends PdfException { Encrypted() { super("Deze PDF is beveiligd met een wachtwoord"); } }

    // ---------- objecten ----------

    static final class Name { final String n; Name(String n) { this.n = n; } @Override public boolean equals(Object o) { return o instanceof Name && ((Name) o).n.equals(n); } @Override public int hashCode() { return n.hashCode(); } @Override public String toString() { return "/" + n; } }
    static final class Num { final String raw; Num(String r) { raw = r; } double v() { try { return Double.parseDouble(raw); } catch (Exception e) { return 0; } } int i() { return (int) v(); } }
    static final class Str { final byte[] b; Str(byte[] b) { this.b = b; } }
    static final class Ref { final int num, gen; Ref(int n, int g) { num = n; gen = g; } @Override public boolean equals(Object o) { return o instanceof Ref && ((Ref) o).num == num && ((Ref) o).gen == gen; } @Override public int hashCode() { return num * 31 + gen; } }
    static final class Dict { final LinkedHashMap<String, Object> m = new LinkedHashMap<>(); Object get(String k) { return m.get(k); } }
    /** Een stream: verwijst naar een stuk van het bestand (geen kopie, scheelt geheugen bij grote PDF's). */
    static final class Stream {
        final Dict dict; final byte[] src; final int off, len;
        Stream(Dict d, byte[] src, int off, int len) { dict = d; this.src = src; this.off = off; this.len = len; }
        byte[] raw() { return java.util.Arrays.copyOfRange(src, off, off + len); }
    }
    static final class Null { @Override public String toString() { return "null"; } }
    static final Object NULL = new Null();

    final byte[] d;
    final Map<Integer, Long> offsets = new HashMap<>();      // object → plek in het bestand
    final Map<Integer, int[]> inStream = new HashMap<>();    // object → {object-stream, index}
    final Map<Integer, Object> cache = new HashMap<>();
    final Map<Integer, Object[]> objStmCache = new HashMap<>();
    Dict trailer;
    final List<Dict> pages = new ArrayList<>();
    final List<Ref> pageRefs = new ArrayList<>();

    PdfSplit(byte[] data) throws IOException {
        d = data;
        if (indexOf("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, Math.min(d.length, 1024)) < 0) throw new PdfException("Dit is geen PDF");
        try { readXrefChain(); } catch (Exception e) { offsets.clear(); inStream.clear(); trailer = null; }
        if (trailer == null || trailer.get("Root") == null || offsets.isEmpty() && inStream.isEmpty()) rebuild();
        if (trailer.get("Encrypt") != null) throw new Encrypted();
        Object root = resolve(trailer.get("Root"));
        if (!(root instanceof Dict)) throw new PdfException("PDF is beschadigd (geen catalogus)");
        collectPages(((Dict) root).get("Pages"), new Dict(), new HashSet<>(), 0);
        if (pages.isEmpty()) throw new PdfException("Geen pagina's gevonden in deze PDF");
    }

    int pageCount() { return pages.size(); }

    // ---------- lezen: tekens ----------

    int p; // leespositie

    static boolean ws(int c) { return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32; }
    static boolean delim(int c) { return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%'; }

    int peek() { return p < d.length ? d[p] & 0xff : -1; }

    void skipWs() {
        while (p < d.length) {
            int c = d[p] & 0xff;
            if (ws(c)) p++;
            else if (c == '%') { while (p < d.length && d[p] != '\n' && d[p] != '\r') p++; }
            else break;
        }
    }

    String token() {
        skipWs();
        int s = p;
        while (p < d.length && !ws(d[p] & 0xff) && !delim(d[p] & 0xff)) p++;
        return new String(d, s, p - s, StandardCharsets.ISO_8859_1);
    }

    boolean isNumber(String t) { return !t.isEmpty() && t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)"); }

    Object parse() throws PdfException {
        skipWs();
        if (p >= d.length) throw new PdfException("PDF houdt onverwacht op");
        int c = peek();
        if (c == '<' && p + 1 < d.length && d[p + 1] == '<') {
            p += 2;
            Dict dict = new Dict();
            while (true) {
                skipWs();
                if (p >= d.length) throw new PdfException("PDF is beschadigd (woordenlijst)");
                if (peek() == '>' && p + 1 < d.length && d[p + 1] == '>') { p += 2; break; }
                Object k = parse();
                if (!(k instanceof Name)) { if (k == null) continue; throw new PdfException("PDF is beschadigd (sleutel)"); }
                Object v = parse();
                dict.m.put(((Name) k).n, v);
            }
            return dict;
        }
        if (c == '<') { // hex
            p++;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int hi = -1;
            while (p < d.length && d[p] != '>') {
                int h = Character.digit(d[p++], 16);
                if (h < 0) continue;
                if (hi < 0) hi = h; else { b.write(hi * 16 + h); hi = -1; }
            }
            if (hi >= 0) b.write(hi * 16);
            p++;
            return new Str(b.toByteArray());
        }
        if (c == '(') return new Str(literal());
        if (c == '[') {
            p++;
            List<Object> a = new ArrayList<>();
            while (true) {
                skipWs();
                if (p >= d.length) throw new PdfException("PDF is beschadigd (lijst)");
                if (peek() == ']') { p++; break; }
                a.add(parse());
            }
            return a;
        }
        if (c == '/') {
            p++;
            int s = p;
            while (p < d.length && !ws(d[p] & 0xff) && !delim(d[p] & 0xff)) p++;
            return new Name(new String(d, s, p - s, StandardCharsets.ISO_8859_1));
        }
        if (c == '>' || c == ']' || c == ')' || c == '{' || c == '}') { p++; return null; } // losse rommel overslaan
        String t = token();
        if (t.isEmpty()) { p++; return null; }
        if (isNumber(t)) {
            int save = p;
            if (t.matches("\\d+")) {
                String g = token();
                if (g.matches("\\d+")) {
                    String r = token();
                    if ("R".equals(r)) return new Ref(Integer.parseInt(t), Integer.parseInt(g));
                }
            }
            p = save;
            return new Num(t);
        }
        if ("true".equals(t)) return Boolean.TRUE;
        if ("false".equals(t)) return Boolean.FALSE;
        if ("null".equals(t)) return NULL;
        return new Name("__kw:" + t); // sleutelwoord (bijv. obj/stream); de aanroeper handelt dit af
    }

    byte[] literal() {
        p++; // (
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int depth = 1;
        while (p < d.length) {
            int c = d[p++] & 0xff;
            if (c == '\\' && p < d.length) {
                int e = d[p++] & 0xff;
                switch (e) {
                    case 'n': b.write('\n'); break; case 'r': b.write('\r'); break; case 't': b.write('\t'); break;
                    case 'b': b.write('\b'); break; case 'f': b.write('\f'); break;
                    case '\r': if (p < d.length && d[p] == '\n') p++; break;
                    case '\n': break;
                    default:
                        if (e >= '0' && e <= '7') {
                            int v = e - '0';
                            for (int k = 0; k < 2 && p < d.length && d[p] >= '0' && d[p] <= '7'; k++) v = v * 8 + (d[p++] - '0');
                            b.write(v & 0xff);
                        } else b.write(e);
                }
            } else if (c == '(') { depth++; b.write(c); }
            else if (c == ')') { if (--depth == 0) break; b.write(c); }
            else b.write(c);
        }
        return b.toByteArray();
    }

    // ---------- lezen: objecten ----------

    Object resolve(Object o) throws PdfException {
        for (int hops = 0; o instanceof Ref && hops < 32; hops++) o = get(((Ref) o).num);
        return o instanceof Ref ? NULL : o;
    }

    Object get(int num) throws PdfException {
        if (cache.containsKey(num)) return cache.get(num);
        Object o = NULL;
        Long off = offsets.get(num);
        int[] in = inStream.get(num);
        if (off != null) o = readAt(off, num);
        else if (in != null) o = fromObjStm(in[0], in[1]);
        cache.put(num, o);
        return o;
    }

    /** "n g obj ... endobj" (met eventueel een stream) op deze plek. */
    Object readAt(long off, int expect) throws PdfException {
        if (off < 0 || off >= d.length) return NULL;
        p = (int) off;
        String n = token(), g = token(), kw = token();
        if (!n.matches("\\d+") || !g.matches("\\d+") || !"obj".equals(kw)) throw new PdfException("PDF is beschadigd (object " + expect + ")");
        Object o = parse();
        int afterObj = p;
        skipWs();
        if (o instanceof Dict && startsWith("stream")) {
            p += 6;
            if (peek() == '\r') p++;
            if (peek() == '\n') p++;
            int start = p;
            Dict dict = (Dict) o;
            int len = -1;
            Object lo = dict.get("Length");
            if (lo instanceof Num) len = ((Num) lo).i();
            else if (lo instanceof Ref) {
                int save = p;
                Object lv = resolve(lo);
                p = save;
                if (lv instanceof Num) len = ((Num) lv).i();
            }
            int end;
            if (len >= 0 && start + len <= d.length && endstreamAt(start + len)) end = start + len;
            else {
                // Lengte klopt niet: zoek "endstream" en haal de regelovergang ervoor weg
                int e = indexOf("endstream".getBytes(StandardCharsets.US_ASCII), start, d.length);
                if (e < 0) throw new PdfException("PDF is beschadigd (stream zonder einde)");
                end = e;
                if (end > start && d[end - 1] == '\n') end--;
                if (end > start && d[end - 1] == '\r') end--;
            }
            return new Stream(dict, d, start, end - start);
        }
        p = afterObj;
        return o;
    }

    boolean endstreamAt(int at) {
        int q = at;
        while (q < d.length && ws(d[q] & 0xff)) q++;
        return q + 9 <= d.length && new String(d, q, 9, StandardCharsets.ISO_8859_1).equals("endstream");
    }

    boolean startsWith(String s) {
        if (p + s.length() > d.length) return false;
        for (int i = 0; i < s.length(); i++) if (d[p + i] != s.charAt(i)) return false;
        return true;
    }

    int indexOf(byte[] needle, int from, int to) {
        outer:
        for (int i = Math.max(0, from); i <= to - needle.length; i++) {
            for (int k = 0; k < needle.length; k++) if (d[i + k] != needle[k]) continue outer;
            return i;
        }
        return -1;
    }

    Object fromObjStm(int stm, int index) throws PdfException {
        Object[] objs = objStmCache.get(stm);
        if (objs == null) {
            Object s = resolve(new Ref(stm, 0));
            if (!(s instanceof Stream)) return NULL;
            Stream st = (Stream) s;
            byte[] data = decode(st);
            int n = num(st.dict.get("N")), first = num(st.dict.get("First"));
            PdfSplit sub = new PdfSplit(data, true);
            int[] nums = new int[n], offs = new int[n];
            for (int i = 0; i < n; i++) { nums[i] = Integer.parseInt(sub.token()); offs[i] = Integer.parseInt(sub.token()); }
            objs = new Object[n];
            for (int i = 0; i < n; i++) {
                sub.p = first + offs[i];
                try { objs[i] = sub.parse(); } catch (Exception e) { objs[i] = NULL; }
            }
            objStmCache.put(stm, objs);
        }
        return index >= 0 && index < objs.length && objs[index] != null ? objs[index] : NULL;
    }

    /** Alleen voor het lezen van de inhoud van een object-stream. */
    private PdfSplit(byte[] data, boolean raw) { d = data; }

    int num(Object o) throws PdfException { Object v = resolve(o); return v instanceof Num ? ((Num) v).i() : 0; }

    // ---------- streams uitpakken (alleen nodig voor xref- en object-streams) ----------

    byte[] decode(Stream s) throws PdfException {
        Object f = resolve(s.dict.get("Filter"));
        Object parms = resolve(s.dict.get("DecodeParms"));
        if (f instanceof List) { List<?> l = (List<?>) f; f = l.isEmpty() ? null : l.get(0); if (parms instanceof List) parms = ((List<?>) parms).isEmpty() ? null : resolve(((List<?>) parms).get(0)); }
        byte[] out = s.raw();
        if (f instanceof Name) {
            if (!"FlateDecode".equals(((Name) f).n)) throw new PdfException("Niet ondersteunde compressie: " + ((Name) f).n);
            out = inflate(out, Math.max(0, INFLATE_BUDGET - inflated));
            inflated += out.length;
        }
        if (parms instanceof Dict) {
            int pred = num(((Dict) parms).get("Predictor"));
            if (pred >= 10) {
                int cols = Math.max(1, num(((Dict) parms).get("Columns")));
                out = png(out, cols);
            }
        }
        return out;
    }

    /** Uitgepakt in totaal (xref- en object-streams); een kwaadaardige PDF kan anders het geheugen vullen. */
    static final long INFLATE_BUDGET = 64L * 1024 * 1024;
    long inflated;

    static byte[] inflate(byte[] in) throws PdfException { return inflate(in, INFLATE_BUDGET); }

    static byte[] inflate(byte[] in, long max) throws PdfException {
        Inflater inf = new Inflater();
        inf.setInput(in);
        ByteArrayOutputStream b = new ByteArrayOutputStream((int) Math.min(1 << 20, Math.max(1024L, in.length * 3L)));
        byte[] buf = new byte[65536];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) break; // klaar, of meer invoer nodig die er niet is
                b.write(buf, 0, n);
                if (b.size() > max) throw new PdfException("PDF is te groot");
            }
        } catch (java.util.zip.DataFormatException e) {
            if (b.size() == 0) throw new PdfException("PDF is beschadigd (compressie)");
        } finally { inf.end(); }
        return b.toByteArray();
    }

    static byte[] png(byte[] in, int cols) {
        int row = cols + 1, rows = in.length / row;
        byte[] out = new byte[rows * cols], prev = new byte[cols];
        for (int r = 0; r < rows; r++) {
            int t = in[r * row] & 0xff;
            byte[] cur = new byte[cols];
            for (int i = 0; i < cols; i++) {
                int x = in[r * row + 1 + i] & 0xff, a = i > 0 ? cur[i - 1] & 0xff : 0, b = prev[i] & 0xff, c = i > 0 ? prev[i - 1] & 0xff : 0;
                int v;
                switch (t) {
                    case 1: v = x + a; break;
                    case 2: v = x + b; break;
                    case 3: v = x + ((a + b) >> 1); break;
                    case 4: { int pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c); v = x + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c); break; }
                    default: v = x;
                }
                cur[i] = (byte) v;
            }
            System.arraycopy(cur, 0, out, r * cols, cols);
            prev = cur;
        }
        return out;
    }

    // ---------- kruisverwijzingen ----------

    void readXrefChain() throws PdfException {
        int sx = lastIndexOf("startxref");
        if (sx < 0) throw new PdfException("geen startxref");
        p = sx + 9;
        long off = Long.parseLong(token());
        Set<Long> seen = new HashSet<>();
        while (off > 0 && off < d.length && seen.add(off)) {
            frees.clear();
            Dict t = readXrefAt(off);
            if (trailer == null) trailer = t;
            List<Integer> tableFrees = new ArrayList<>(frees);
            Object xs = t.get("XRefStm"); // hybride bestand: extra tabel als stream
            if (xs instanceof Num) readXrefAt((long) ((Num) xs).v());
            // Pas na de XRefStm: in een hybride bestand staan objecten in object-streams als "f" in de gewone tabel
            for (int num : tableFrees) if (!offsets.containsKey(num) && !inStream.containsKey(num)) offsets.put(num, -1L);
            frees.clear();
            Object prev = t.get("Prev");
            off = prev instanceof Num ? (long) ((Num) prev).v() : -1;
        }
    }

    int lastIndexOf(String s) {
        byte[] n = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = d.length - n.length; i >= Math.max(0, d.length - 4096); i--) {
            boolean ok = true;
            for (int k = 0; k < n.length && ok; k++) if (d[i + k] != n[k]) ok = false;
            if (ok) return i;
        }
        return -1;
    }

    private final List<Integer> frees = new ArrayList<>();

    Dict readXrefAt(long off) throws PdfException {
        p = (int) off;
        skipWs();
        if (startsWith("xref")) {
            p += 4;
            while (true) {
                skipWs();
                if (startsWith("trailer")) { p += 7; Object t = parse(); if (!(t instanceof Dict)) throw new PdfException("trailer"); return (Dict) t; }
                String a = token(), b = token();
                if (!a.matches("\\d+") || !b.matches("\\d+")) throw new PdfException("xref");
                if (a.length() > 9 || b.length() > 9) throw new PdfException("xref");
                int start = Integer.parseInt(a), count = Integer.parseInt(b);
                if (count > (d.length - p) / 18 + 1) throw new PdfException("xref"); // elke regel is 20 tekens
                for (int i = 0; i < count; i++) {
                    String o = token(), g = token(), type = token();
                    if (type.isEmpty()) throw new PdfException("xref");
                    int num = start + i;
                    if ("n".equals(type) && !offsets.containsKey(num) && !inStream.containsKey(num)) {
                        long v = Long.parseLong(o);
                        if (v > 0) offsets.put(num, v);
                    } else if ("f".equals(type) && !offsets.containsKey(num) && !inStream.containsKey(num)) {
                        frees.add(num); // vrij in een nieuwere versie: niet ouder overnemen (na de XRefStm, zie readXrefChain)
                    }
                }
            }
        }
        Object o = readAt(off, -1);
        if (!(o instanceof Stream)) throw new PdfException("xref-stream");
        Stream s = (Stream) o;
        byte[] data = decode(s);
        List<?> w = (List<?>) resolve(s.dict.get("W"));
        if (w == null || w.size() < 3) throw new PdfException("xref-stream");
        int w0 = num(w.get(0)), w1 = num(w.get(1)), w2 = num(w.get(2)), rec = w0 + w1 + w2;
        if (rec <= 0 || w0 < 0 || w1 < 0 || w2 < 0 || w0 > 8 || w1 > 8 || w2 > 8) throw new PdfException("xref-stream");
        Object idx = resolve(s.dict.get("Index"));
        List<Integer> ranges = new ArrayList<>();
        if (idx instanceof List) for (Object x : (List<?>) idx) ranges.add(num(x));
        else { ranges.add(0); ranges.add(num(s.dict.get("Size"))); }
        int q = 0;
        for (int r = 0; r + 1 < ranges.size(); r += 2) {
            for (int i = 0; i < ranges.get(r + 1) && q + rec <= data.length; i++, q += rec) {
                int num = ranges.get(r) + i;
                long type = w0 == 0 ? 1 : field(data, q, w0), f2 = field(data, q + w0, w1), f3 = field(data, q + w0 + w1, w2);
                if (offsets.containsKey(num) || inStream.containsKey(num)) continue;
                if (type == 1 && f2 > 0) offsets.put(num, f2);
                else if (type == 2) inStream.put(num, new int[]{(int) f2, (int) f3});
                else if (type == 0) offsets.put(num, -1L);
            }
        }
        return s.dict;
    }

    static long field(byte[] b, int at, int len) { long v = 0; for (int i = 0; i < len; i++) v = (v << 8) | (b[at + i] & 0xff); return v; }

    /** Kapotte tabel: alle "n g obj" in het bestand opzoeken. De laatste definitie van een object telt. */
    void rebuild() throws PdfException {
        offsets.clear(); inStream.clear(); cache.clear();
        Dict lastTrailer = null;
        for (int i = 0; i < d.length - 4; i++) {
            if (d[i] == 'o' && d[i + 1] == 'b' && d[i + 2] == 'j' && (i + 3 >= d.length || ws(d[i + 3] & 0xff) || delim(d[i + 3] & 0xff))) {
                int q = i - 1;
                while (q > 0 && ws(d[q] & 0xff)) q--;
                int ge = q; while (q > 0 && Character.isDigit(d[q])) q--; int gs = q + 1;
                if (gs > ge) continue;
                while (q > 0 && ws(d[q] & 0xff)) q--;
                int ne = q; while (q >= 0 && Character.isDigit(d[q])) q--; int ns = q + 1;
                if (ns > ne) continue;
                try { offsets.put(Integer.parseInt(new String(d, ns, ne - ns + 1, StandardCharsets.US_ASCII)), (long) ns); } catch (Exception ignored) { }
            } else if (d[i] == 't' && i + 7 < d.length && new String(d, i, 7, StandardCharsets.ISO_8859_1).equals("trailer")) {
                p = i + 7;
                try { Object t = parse(); if (t instanceof Dict) lastTrailer = (Dict) t; } catch (Exception ignored) { }
            }
        }
        trailer = lastTrailer != null && lastTrailer.get("Root") != null ? lastTrailer : null;
        if (trailer == null) {
            // Geen trailer (bijv. alleen xref-streams): de catalogus zoeken
            for (Map.Entry<Integer, Long> e : new ArrayList<>(offsets.entrySet())) {
                try {
                    Object o = get(e.getKey());
                    Dict dict = o instanceof Dict ? (Dict) o : o instanceof Stream ? ((Stream) o).dict : null;
                    if (dict != null && new Name("Catalog").equals(dict.get("Type"))) { trailer = new Dict(); trailer.m.put("Root", new Ref(e.getKey(), 0)); }
                    if (dict != null && new Name("XRef").equals(dict.get("Type")) && dict.get("Encrypt") != null && trailer != null) trailer.m.put("Encrypt", dict.get("Encrypt"));
                } catch (Exception ignored) { }
            }
            // Objecten in object-streams terugvinden
            for (Map.Entry<Integer, Long> e : new ArrayList<>(offsets.entrySet())) {
                try {
                    Object o = get(e.getKey());
                    if (o instanceof Stream && new Name("ObjStm").equals(((Stream) o).dict.get("Type"))) {
                        Stream st = (Stream) o;
                        PdfSplit sub = new PdfSplit(decode(st), true);
                        int n = num(st.dict.get("N"));
                        for (int k = 0; k < n; k++) { int on = Integer.parseInt(sub.token()); sub.token(); if (!offsets.containsKey(on)) inStream.put(on, new int[]{e.getKey(), k}); }
                    }
                } catch (Exception ignored) { }
            }
        }
        if (trailer == null) throw new PdfException("PDF is te beschadigd om te lezen");
        cache.clear();
    }

    // ---------- pagina's ----------

    static final String[] INHERIT = {"Resources", "MediaBox", "CropBox", "Rotate"};

    void collectPages(Object node, Dict inherited, Set<Integer> seen, int depth) throws PdfException {
        if (depth > 64 || pages.size() > 100_000) return;
        Ref ref = node instanceof Ref ? (Ref) node : null;
        if (ref != null && !seen.add(ref.num)) return; // lus
        Object o = resolve(node);
        if (!(o instanceof Dict)) return;
        Dict dict = (Dict) o;
        Dict inh = new Dict();
        inh.m.putAll(inherited.m);
        for (String k : INHERIT) if (dict.get(k) != null) inh.m.put(k, dict.get(k));
        Object kids = resolve(dict.get("Kids"));
        Object type = dict.get("Type");
        if (kids instanceof List && !new Name("Page").equals(type)) {
            for (Object k : (List<?>) kids) collectPages(k, inh, seen, depth + 1);
        } else {
            Dict page = new Dict();
            page.m.putAll(dict.m);
            for (String k : INHERIT) if (page.get(k) == null && inh.get(k) != null) page.m.put(k, inh.get(k));
            pages.add(page);
            pageRefs.add(ref);
        }
    }

    // ---------- schrijven ----------

    /** Nieuwe PDF met deze pagina's (0-gebaseerd, in deze volgorde). */
    byte[] extract(List<Integer> which) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        extract(which, b);
        return b.toByteArray();
    }

    /** Schrijft de nieuwe PDF meteen naar out (zonder alles eerst in het geheugen te zetten). */
    void extract(List<Integer> which, java.io.OutputStream os) throws IOException {
        if (which.isEmpty()) throw new PdfException("Geen pagina's gekozen");
        for (int i : which) if (i < 0 || i >= pages.size()) throw new PdfException("Pagina " + (i + 1) + " bestaat niet");
        Map<Integer, Integer> pageNum = new HashMap<>(); // oud paginaobject → nieuw nummer
        Set<Integer> allPages = new HashSet<>();
        for (Ref r : pageRefs) if (r != null) allPages.add(r.num);
        Writer w = new Writer(this);
        int catalog = w.reserve(), tree = w.reserve();
        List<Integer> newPages = new ArrayList<>();
        for (int i : which) { int n = w.reserve(); newPages.add(n); Ref r = pageRefs.get(i); if (r != null && !pageNum.containsKey(r.num)) pageNum.put(r.num, n); }
        w.pageNum = pageNum; w.allPages = allPages;
        for (int k = 0; k < which.size(); k++) {
            Dict src = pages.get(which.get(k)), page = new Dict();
            for (Map.Entry<String, Object> e : src.m.entrySet()) {
                String key = e.getKey();
                if (key.equals("Parent") || key.equals("StructParents") || key.equals("B") || key.equals("PieceInfo")) continue;
                page.m.put(key, w.copy(e.getValue(), 0));
            }
            page.m.put("Type", new Name("Page"));
            page.m.put("Parent", new Ref(tree, 0));
            w.set(newPages.get(k), page);
        }
        Dict pagesDict = new Dict();
        pagesDict.m.put("Type", new Name("Pages"));
        List<Object> kids = new ArrayList<>();
        for (int n : newPages) kids.add(new Ref(n, 0));
        pagesDict.m.put("Kids", kids);
        pagesDict.m.put("Count", new Num(String.valueOf(newPages.size())));
        w.set(tree, pagesDict);
        Dict cat = new Dict();
        cat.m.put("Type", new Name("Catalog"));
        cat.m.put("Pages", new Ref(tree, 0));
        w.set(catalog, cat);
        w.write(os, catalog, header());
    }

    String header() {
        String h = new String(d, 0, Math.min(16, d.length), StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%PDF-(\\d\\.\\d)").matcher(h);
        String v = m.find() ? m.group(1) : "1.7";
        return v.compareTo("1.4") < 0 ? "1.4" : v;
    }

    /** Kopieert objecten naar een nieuw bestand met nieuwe nummers. */
    static final class Writer {
        final PdfSplit src;
        final List<Object> objs = new ArrayList<>();
        final Map<Integer, Integer> mapped = new HashMap<>();
        Map<Integer, Integer> pageNum; Set<Integer> allPages;
        Writer(PdfSplit src) { this.src = src; objs.add(null); }
        int reserve() { objs.add(null); return objs.size() - 1; }
        void set(int n, Object o) { objs.set(n, o); }

        Object copy(Object o, int depth) throws PdfException {
            if (depth > 200) return NULL;
            if (o instanceof Ref) {
                int num = ((Ref) o).num;
                if (allPages.contains(num)) { Integer np = pageNum.get(num); return np == null ? NULL : new Ref(np, 0); } // verwijzing naar een pagina die niet meegaat
                Integer m = mapped.get(num);
                if (m != null) return new Ref(m, 0);
                Object target = src.get(num);
                if (target == NULL) return NULL;
                int n = reserve();
                mapped.put(num, n);
                set(n, copy(target, depth + 1));
                return new Ref(n, 0);
            }
            if (o instanceof Dict) {
                Dict src = (Dict) o, out = new Dict();
                boolean pageLike = new Name("Page").equals(src.get("Type")) || new Name("Pages").equals(src.get("Type"));
                for (Map.Entry<String, Object> e : src.m.entrySet()) {
                    if (pageLike && e.getKey().equals("Parent")) continue; // nooit de oude paginaboom meenemen
                    out.m.put(e.getKey(), copy(e.getValue(), depth + 1));
                }
                return out;
            }
            if (o instanceof List) {
                List<Object> out = new ArrayList<>();
                for (Object x : (List<?>) o) out.add(copy(x, depth + 1));
                return out;
            }
            if (o instanceof Stream) {
                Stream s = (Stream) o;
                Dict dict = (Dict) copy(s.dict, depth + 1);
                dict.m.put("Length", new Num(String.valueOf(s.len)));
                return new Stream(dict, s.src, s.off, s.len);
            }
            return o;
        }

        void write(java.io.OutputStream os, int root, String version) throws IOException {
            PdfWriter.Out out = new PdfWriter.Out(os);
            out.ascii("%PDF-" + version + "\n%âãÏÓ\n");
            List<Long> offs = new ArrayList<>();
            offs.add(0L);
            for (int i = 1; i < objs.size(); i++) {
                offs.add(out.pos);
                out.ascii(i + " 0 obj\n");
                Object o = objs.get(i);
                if (o instanceof Stream) {
                    Stream s = (Stream) o;
                    ser(out, s.dict);
                    out.ascii("\nstream\n");
                    out.bytes(s.src, s.off, s.len);
                    out.ascii("\nendstream");
                } else ser(out, o == null ? NULL : o);
                out.ascii("\nendobj\n");
            }
            PdfWriter.writeXref(out, offs, root, 0);
            out.flush();
        }
    }

    static void ser(PdfWriter.Out out, Object o) throws IOException {
        if (o instanceof Dict) {
            out.ascii("<<");
            for (Map.Entry<String, Object> e : ((Dict) o).m.entrySet()) { out.ascii("/" + e.getKey() + " "); ser(out, e.getValue()); out.ascii(" "); }
            out.ascii(">>");
        } else if (o instanceof List) {
            out.ascii("[");
            boolean first = true;
            for (Object x : (List<?>) o) { if (!first) out.ascii(" "); ser(out, x); first = false; }
            out.ascii("]");
        } else if (o instanceof Name) {
            String n = ((Name) o).n;
            if (n.startsWith("__kw:")) out.ascii("null"); else out.ascii("/" + n);
        } else if (o instanceof Num) out.ascii(((Num) o).raw);
        else if (o instanceof Ref) out.ascii(((Ref) o).num + " " + ((Ref) o).gen + " R");
        else if (o instanceof Str) {
            byte[] b = ((Str) o).b;
            StringBuilder s = new StringBuilder(b.length * 2 + 2).append('<');
            for (byte x : b) { s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16)); }
            out.ascii(s.append('>').toString());
        } else if (o instanceof Boolean) out.ascii(o.toString());
        else out.ascii("null");
    }

    /** "1-3, 5, 8-" → pagina-indexen (0-gebaseerd). Geeft null bij een ongeldige invoer. */
    static List<Integer> parseRange(String s, int count) {
        List<Integer> out = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return null;
        for (String part : s.trim().replaceAll("\\s*-\\s*", "-").split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            String[] ab = part.split("-", -1);
            try {
                int a, b;
                if (ab.length == 1) { a = b = Integer.parseInt(ab[0]); }
                else if (ab.length == 2) {
                    a = ab[0].isEmpty() ? 1 : Integer.parseInt(ab[0]);
                    b = ab[1].isEmpty() ? count : Integer.parseInt(ab[1]);
                } else return null;
                if (a < 1 || b > count || a > b) return null;
                for (int i = a; i <= b; i++) out.add(i - 1);
            } catch (NumberFormatException e) { return null; }
        }
        return out.isEmpty() ? null : out;
    }
}
