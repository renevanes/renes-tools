package org.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Zie JSONObject: een kleine, werkende org.json alleen voor de Java-tests. */
public class JSONArray {
    final List<Object> list = new ArrayList<>();

    public JSONArray() { }
    public JSONArray(Collection<?> c) { if (c != null) for (Object o : c) list.add(wrap(o)); }
    public JSONArray(String json) throws JSONException { this(new JSONTokener(json)); }
    public JSONArray(JSONTokener t) throws JSONException {
        Object v = t.nextValue();
        if (!(v instanceof JSONArray)) throw new JSONException("Geen JSON-lijst");
        list.addAll(((JSONArray) v).list);
    }

    static Object wrap(Object o) {
        if (o == null) return JSONObject.NULL;
        if (o instanceof JSONObject || o instanceof JSONArray || o == JSONObject.NULL || o instanceof String || o instanceof Boolean || o instanceof Number || o instanceof Character) return o instanceof Character ? String.valueOf(o) : o;
        if (o instanceof Collection) return new JSONArray((Collection<?>) o);
        if (o instanceof Map) return new JSONObject((Map<?, ?>) o);
        return String.valueOf(o);
    }
    static Boolean toBool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) { if ("true".equalsIgnoreCase((String) v)) return true; if ("false".equalsIgnoreCase((String) v)) return false; }
        return null;
    }
    static Double toNum(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) try { return Double.valueOf((String) v); } catch (NumberFormatException ignored) { }
        return null;
    }

    public int length() { return list.size(); }
    public JSONArray put(Object v) { list.add(wrap(v)); return this; }
    public JSONArray put(boolean v) { list.add(v); return this; }
    public JSONArray put(int v) { list.add(v); return this; }
    public JSONArray put(long v) { list.add(v); return this; }
    public JSONArray put(double v) throws JSONException { if (Double.isNaN(v) || Double.isInfinite(v)) throw new JSONException("Geen getal"); list.add(v); return this; }
    public JSONArray put(int i, Object v) throws JSONException { while (list.size() <= i) list.add(JSONObject.NULL); list.set(i, wrap(v)); return this; }
    public Object remove(int i) { return i >= 0 && i < list.size() ? list.remove(i) : null; }

    public Object opt(int i) { return i >= 0 && i < list.size() ? list.get(i) : null; }
    public Object get(int i) throws JSONException { Object v = opt(i); if (v == null) throw new JSONException("Geen waarde op " + i); return v; }
    public boolean isNull(int i) { Object v = opt(i); return v == null || v == JSONObject.NULL; }
    public String optString(int i) { return optString(i, ""); }
    public String optString(int i, String d) { Object v = opt(i); return v == null ? d : String.valueOf(v); } // zoals Android: JSON null wordt "null"
    public String getString(int i) throws JSONException { return String.valueOf(get(i)); }
    public boolean optBoolean(int i) { Boolean b = toBool(opt(i)); return b != null && b; }
    public double optDouble(int i) { Double n = toNum(opt(i)); return n == null ? Double.NaN : n; }
    public double optDouble(int i, double d) { Double n = toNum(opt(i)); return n == null ? d : n; }
    public int optInt(int i) { Double n = toNum(opt(i)); return n == null ? 0 : n.intValue(); }
    public int optInt(int i, int d) { Double n = toNum(opt(i)); return n == null ? d : n.intValue(); }
    public long optLong(int i) { return optLong(i, 0L); }
    public long optLong(int i, long d) { Object v = opt(i); if (v instanceof Number) return ((Number) v).longValue(); Double n = toNum(v); return n == null ? d : n.longValue(); }
    public long getLong(int i) throws JSONException { Object v = get(i); if (v instanceof Number) return ((Number) v).longValue(); Double n = toNum(v); if (n == null) throw new JSONException("Geen getal op " + i); return n.longValue(); }
    public int getInt(int i) throws JSONException { return (int) getLong(i); }
    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(int i) { Object v = opt(i); return v instanceof JSONArray ? (JSONArray) v : null; }
    public JSONObject getJSONObject(int i) throws JSONException { Object v = get(i); if (v instanceof JSONObject) return (JSONObject) v; throw new JSONException("Geen object op " + i); }
    public JSONArray getJSONArray(int i) throws JSONException { Object v = get(i); if (v instanceof JSONArray) return (JSONArray) v; throw new JSONException("Geen lijst op " + i); }

    @Override public String toString() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) { if (i > 0) b.append(','); b.append(JSONObject.str(list.get(i))); }
        return b.append(']').toString();
    }
}
