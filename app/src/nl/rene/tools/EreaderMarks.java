package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/** Leeswijzers valideren zonder de vorige verzameling te veranderen; pas na succes schrijft Ereader ze atomair. */
final class EreaderMarks {
    private EreaderMarks() { }
    static JSONArray change(JSONArray all, JSONObject p, int chapters, String text, long now) throws Exception {
        JSONArray out = new JSONArray(); String key = p.optString("id"); boolean found = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject a = all.getJSONObject(i);
            if (a.optString("id").equals(key)) found = true; else out.put(a);
        }
        if (p.optBoolean("remove")) return out;
        if (out.length() >= 500) throw new Exception(Taal.T("Dit boek heeft al 500 leeswijzers"));
        int ch = p.optInt("chapter", -1); String kind = p.optString("kind");
        if (ch < 0 || ch >= chapters || !kind.matches("bookmark|highlight")) throw new Exception(Taal.T("Deze leeswijzer is niet geldig"));
        int start = p.optInt("start", -1), end = p.optInt("end", start);
        if (start < 0 || end < start || end > text.length()) throw new Exception(Taal.T("Deze leeswijzer is niet geldig"));
        if (kind.equals("highlight") && (end == start || end - start > 5000)) throw new Exception(Taal.T("Selecteer maximaal 5000 tekens om te markeren"));
        String note = p.optString("note"); if (note.length() > 2000) throw new Exception(Taal.T("Een notitie mag maximaal 2000 tekens bevatten"));
        int last = Math.min(text.length(), kind.equals("highlight") ? Math.min(end, start + 500) : start + 150);
        if (last > start && last < text.length() && Character.isHighSurrogate(text.charAt(last - 1))) last--;
        out.put(new JSONObject().put("id", found ? key : java.util.UUID.randomUUID().toString()).put("kind", kind).put("chapter", ch)
                .put("start", start).put("end", end).put("note", note).put("at", now).put("text", text.substring(start, last)));
        return out;
    }
}
