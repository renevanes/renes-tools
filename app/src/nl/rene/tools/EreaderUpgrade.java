package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Bestaande leeswijzers volgen dezelfde tekst, ook als afbeeldingen en extra witruimte tussen de tekst komen. */
final class EreaderUpgrade {
    interface OldText { String read(int index) throws Exception; }
    private final EreaderBook book;
    private final OldText oldText;
    private final Map<Integer,int[]> maps = new HashMap<>();
    EreaderUpgrade(EreaderBook book, OldText oldText) { this.book = book; this.oldText = oldText; }
    int chapter(int old) throws Exception {
        if (old < 0 || old >= book.legacyMap.size() || book.legacyMap.get(old) < 0) throw new Exception(Taal.T("De eerdere leesplek kan niet veilig worden overgenomen"));
        return book.legacyMap.get(old);
    }
    int anchor(int oldChapter, int position) throws Exception {
        if (!maps.containsKey(oldChapter)) maps.put(oldChapter, map(oldText.read(oldChapter), book.chapters.get(chapter(oldChapter))));
        int[] positions = maps.get(oldChapter); return positions[Math.max(0, Math.min(positions.length - 1, position))];
    }
    JSONObject metadata(JSONObject previous) throws Exception {
        JSONObject out = new JSONObject(previous.toString()); int old = previous.optInt("chapter", 0);
        out.put("chapter", chapter(old));
        if (previous.has("anchor")) out.put("anchor", anchor(old, previous.getInt("anchor")));
        JSONObject reading = previous.optJSONObject("reading");
        if (reading != null) {
            JSONObject r = new JSONObject(reading.toString()); int ch = r.optInt("chapter", 0); r.put("chapter", chapter(ch));
            if (r.has("anchor")) r.put("anchor", anchor(ch, r.getInt("anchor"))); out.put("reading", r);
        }
        return out;
    }
    JSONArray marks(JSONArray previous) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < previous.length(); i++) {
            JSONObject mark = new JSONObject(previous.getJSONObject(i).toString()); int old = mark.getInt("chapter");
            mark.put("chapter", chapter(old)).put("start", anchor(old, mark.getInt("start"))).put("end", anchor(old, mark.getInt("end")));
            out.put(mark);
        }
        return out;
    }
    static int[] map(String old, String current) throws Exception {
        int[] out = new int[old.length() + 1]; int a = 0, b = 0;
        while (a < old.length()) {
            if (b < current.length() && old.charAt(a) == current.charAt(b)) { out[a++] = b++; continue; }
            if (b < current.length() && Character.isWhitespace(current.charAt(b))) { b++; continue; }
            if (Character.isWhitespace(old.charAt(a))) { out[a++] = b; continue; }
            throw new Exception(Taal.T("De eerdere leesplek kan niet veilig worden overgenomen"));
        }
        out[a] = b; return out;
    }
}
