package org.json;

/** Zie JSONObject: JSON-tekst lezen, alleen voor de Java-tests. */
public class JSONTokener {
    private final String s;
    private int i;

    public JSONTokener(String s) { this.s = s == null ? "" : s; }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private JSONException err(String m) { return new JSONException(m + " op " + i); }

    public Object nextValue() throws JSONException {
        ws();
        if (i >= s.length()) throw err("Einde van de tekst");
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"' || c == '\'') return str(c);
        int st = i;
        while (i < s.length() && ",:]} \t\r\n".indexOf(s.charAt(i)) < 0) i++;
        String w = s.substring(st, i);
        if (w.equals("true")) return Boolean.TRUE;
        if (w.equals("false")) return Boolean.FALSE;
        if (w.equals("null")) return JSONObject.NULL;
        try {
            if (w.matches("-?\\d+")) { long l = Long.parseLong(w); return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) (int) l : (Object) l; }
            return Double.valueOf(w);
        } catch (NumberFormatException e) { if (w.isEmpty()) throw err("Onverwacht teken"); return w; }
    }

    private JSONObject obj() throws JSONException {
        JSONObject o = new JSONObject();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return o; }
        while (true) {
            ws();
            Object k = nextValue();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("':' verwacht");
            i++;
            o.put(String.valueOf(k), nextValue());
            ws();
            if (i >= s.length()) throw err("'}' verwacht");
            char c = s.charAt(i++);
            if (c == '}') return o;
            if (c != ',') throw err("',' verwacht");
        }
    }

    private JSONArray arr() throws JSONException {
        JSONArray a = new JSONArray();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
        while (true) {
            a.put(nextValue());
            ws();
            if (i >= s.length()) throw err("']' verwacht");
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw err("',' verwacht");
        }
    }

    private String str(char q) throws JSONException {
        StringBuilder b = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == q) return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
        throw err("Tekst niet afgesloten");
    }
}
