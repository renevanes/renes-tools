package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Event;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Verjaardagen uit je eigen contacten (het veld Verjaardag), voor Vandaag op de telefoon-skin. */
final class Birthdays {

    private Birthdays() { }

    private static volatile String cache;
    private static volatile long cacheAt, cacheDay;

    /** Wie er de komende 'days' dagen jarig is: [{name, lookup, days, age}] (age 0 = onbekend jaar). Een uur in het geheugen. */
    static String upcoming(Context c, int days) {
        long now = System.currentTimeMillis();
        long day = now / 86_400_000L;
        if (cache != null && now - cacheAt < 60 * 60_000L && cacheDay == day) return cache;
        JSONArray out = new JSONArray();
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return out.toString();
        List<JSONObject> l = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.DISPLAY_NAME, Event.START_DATE, ContactsContract.Data.LOOKUP_KEY},
                ContactsContract.Data.MIMETYPE + "=? AND " + Event.TYPE + "=?",
                new String[]{Event.CONTENT_ITEM_TYPE, String.valueOf(Event.TYPE_BIRTHDAY)}, null)) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (cur != null && cur.moveToNext()) {
                String name = cur.getString(0), date = cur.getString(1), key = cur.getString(2);
                int[] ymd = parse(date);
                if (name == null || ymd == null || !seen.add(name + "|" + date)) continue;
                int d = daysUntil(ymd[1], ymd[2], Calendar.getInstance());
                if (d < 0 || d > days) continue;
                int age = ymd[0] > 0 ? Calendar.getInstance().get(Calendar.YEAR) + (d > 0 && passedThisYear(ymd[1], ymd[2]) ? 1 : 0) - ymd[0] : 0;
                l.add(new JSONObject().put("name", name).put("lookup", key == null ? "" : key).put("days", d).put("age", age > 0 && age < 130 ? age : 0));
            }
        } catch (Exception ignored) { }
        java.util.Collections.sort(l, (a, b) -> a.optInt("days") - b.optInt("days"));
        for (JSONObject o : l) out.put(o);
        cache = out.toString(); cacheAt = now; cacheDay = day;
        return cache;
    }

    /** "1980-05-17", "--05-17", "19800517" → {jaar (0 = onbekend), maand, dag}. */
    static int[] parse(String s) {
        if (s == null) return null;
        s = s.trim();
        try {
            if (s.startsWith("--")) { String[] p = s.substring(2).split("-"); return new int[]{0, Integer.parseInt(p[0]), Integer.parseInt(p[1].substring(0, 2))}; }
            if (s.matches("\\d{8}")) return new int[]{Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)), Integer.parseInt(s.substring(6, 8))};
            String[] p = s.split("[-T ]");
            int y = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]), d = Integer.parseInt(p[2]);
            if (m < 1 || m > 12 || d < 1 || d > 31) return null;
            return new int[]{y < 1800 ? 0 : y, m, d};
        } catch (Exception e) { return null; }
    }

    static boolean passedThisYear(int m, int d) {
        Calendar now = Calendar.getInstance();
        int cm = now.get(Calendar.MONTH) + 1, cd = now.get(Calendar.DAY_OF_MONTH);
        return m < cm || (m == cm && d < cd);
    }

    /** Dagen tot de volgende verjaardag (0 = vandaag). 29 februari valt in gewone jaren op 28 februari. */
    static int daysUntil(int m, int d, Calendar today) {
        Calendar t = (Calendar) today.clone();
        t.set(Calendar.HOUR_OF_DAY, 0); t.set(Calendar.MINUTE, 0); t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0);
        for (int add = 0; add < 2; add++) {
            Calendar b = (Calendar) t.clone();
            b.add(Calendar.YEAR, add);
            b.set(Calendar.DAY_OF_MONTH, 1);
            b.set(Calendar.MONTH, m - 1);
            b.set(Calendar.DAY_OF_MONTH, Math.min(d, b.getActualMaximum(Calendar.DAY_OF_MONTH)));
            long diff = b.getTimeInMillis() - t.getTimeInMillis();
            if (diff >= 0) return (int) Math.round(diff / 86_400_000.0);
        }
        return -1;
    }
}
