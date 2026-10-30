package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Een kleine, werkende org.json voor de Java-tests (tests/run-jvm.sh zet build/test vóór android.jar, waar org.json
 * alleen stubs heeft). Alleen wat de app gebruikt, met hetzelfde gedrag als op Android: opt…() geeft de standaard bij
 * een ontbrekende of verkeerde waarde, getallen als tekst worden gelezen, de volgorde blijft zoals ingevoerd.
 */
public class JSONObject {
    static final class Null {
        @Override public boolean equals(Object o) { return o == this || o == null; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    }
    public static final Object NULL = new Null();

    final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() { }
    public JSONObject(String json) throws JSONException { this(new JSONTokener(json)); }
    public JSONObject(JSONTokener t) throws JSONException {
        Object v = t.nextValue();
        if (!(v instanceof JSONObject)) throw new JSONException("Geen JSON-object");
        map.putAll(((JSONObject) v).map);
    }
    public JSONObject(Map<?, ?> m) { if (m != null) for (Map.Entry<?, ?> e : m.entrySet()) map.put(String.valueOf(e.getKey()), JSONArray.wrap(e.getValue())); }

    public int length() { return map.size(); }
    public boolean has(String k) { return map.containsKey(k); }
    public boolean isNull(String k) { Object v = map.get(k); return v == null || v == NULL; }
    public Iterator<String> keys() { return map.keySet().iterator(); }
    public JSONArray names() { return map.isEmpty() ? null : new JSONArray(map.keySet()); }
    public Object remove(String k) { return map.remove(k); }

    public JSONObject put(String k, Object v) throws JSONException { if (k == null) throw new JSONException("Naam is null"); if (v == null) map.remove(k); else map.put(k, JSONArray.wrap(v)); return this; }
    public JSONObject put(String k, boolean v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, int v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, long v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, double v) throws JSONException { if (Double.isNaN(v) || Double.isInfinite(v)) throw new JSONException("Geen getal"); return put(k, (Object) v); }
    public JSONObject putOpt(String k, Object v) throws JSONException { if (k != null && v != null) put(k, v); return this; }

    public Object opt(String k) { return k == null ? null : map.get(k); }
    public Object get(String k) throws JSONException { Object v = map.get(k); if (v == null) throw new JSONException("Geen waarde voor " + k); return v; }

    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String d) { Object v = opt(k); return v == null || v == NULL ? d : String.valueOf(v); }
    public String getString(String k) throws JSONException { Object v = get(k); return String.valueOf(v); }

    public boolean optBoolean(String k) { return optBoolean(k, false); }
    public boolean optBoolean(String k, boolean d) { Boolean b = JSONArray.toBool(opt(k)); return b == null ? d : b; }
    public boolean getBoolean(String k) throws JSONException { Boolean b = JSONArray.toBool(get(k)); if (b == null) throw new JSONException("Geen boolean: " + k); return b; }

    public double optDouble(String k) { return optDouble(k, Double.NaN); }
    public double optDouble(String k, double d) { Double n = JSONArray.toNum(opt(k)); return n == null ? d : n; }
    public double getDouble(String k) throws JSONException { Double n = JSONArray.toNum(get(k)); if (n == null) throw new JSONException("Geen getal: " + k); return n; }

    public int optInt(String k) { return optInt(k, 0); }
    public int optInt(String k, int d) { Double n = JSONArray.toNum(opt(k)); return n == null ? d : n.intValue(); }
    public int getInt(String k) throws JSONException { return (int) getDouble(k); }

    public long optLong(String k) { return optLong(k, 0L); }
    public long optLong(String k, long d) { Object v = opt(k); if (v instanceof Number) return ((Number) v).longValue(); Double n = JSONArray.toNum(v); return n == null ? d : n.longValue(); }
    public long getLong(String k) throws JSONException { Object v = get(k); if (v instanceof Number) return ((Number) v).longValue(); Double n = JSONArray.toNum(v); if (n == null) throw new JSONException("Geen getal: " + k); return n.longValue(); }

    public JSONObject optJSONObject(String k) { Object v = opt(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String k) { Object v = opt(k); return v instanceof JSONArray ? (JSONArray) v : null; }
    public JSONObject getJSONObject(String k) throws JSONException { Object v = get(k); if (v instanceof JSONObject) return (JSONObject) v; throw new JSONException("Geen object: " + k); }
    public JSONArray getJSONArray(String k) throws JSONException { Object v = get(k); if (v instanceof JSONArray) return (JSONArray) v; throw new JSONException("Geen lijst: " + k); }

    public static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '/': b.append("\\/"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    static String str(Object v) {
        if (v == null || v == NULL) return "null";
        if (v instanceof String) return quote((String) v);
        if (v instanceof Double || v instanceof Float) { double d = ((Number) v).doubleValue(); return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d); }
        return v.toString();
    }

    @Override public String toString() {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, Object> e : map.entrySet()) { if (b.length() > 1) b.append(','); b.append(quote(e.getKey())).append(':').append(str(e.getValue())); }
        return b.append('}').toString();
    }
}
