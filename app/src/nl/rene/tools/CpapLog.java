package nl.rene.tools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;

/** Eén meting per myAir-datum, versleuteld buiten de Android-backup. */
final class CpapLog {
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_NIGHTS = 10000;
    static final String FILE = "cpap.bin";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final String[] METRICS = {"seal", "leak", "ahi", "usage", "off", "score", "leak95", "sessions"};
    private static final int[] LIMITS = {20, 999, 999, 1440, 999, 100, 999, 999};

    private CpapLog() { }

    static File file(Context c) { return new File(c.getNoBackupFilesDir(), FILE); }

    static boolean validDay(String day) {
        if (day == null || !day.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}")) return false;
        GregorianCalendar date = new GregorianCalendar(UTC, Locale.ROOT);
        date.clear();
        date.setLenient(false);
        date.set(Integer.parseInt(day.substring(0, 4)), Integer.parseInt(day.substring(5, 7)) - 1,
                Integer.parseInt(day.substring(8, 10)));
        try { date.getTimeInMillis(); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    static JSONObject cleanNight(JSONObject night) throws JSONException {
        String day = night.getString("day");
        Object name = night.get("mask");
        if (!validDay(day) || !(name instanceof String)) throw new JSONException("Ongeldige nacht");
        String mask = ((String) name).trim();
        if (mask.isEmpty() || mask.length() > 64 || mask.matches("(?s).*[\\p{Cntrl}\\p{Cf}].*"))
            throw new JSONException("Ongeldig masker");
        JSONObject clean = new JSONObject().put("day", day).put("mask", mask);
        for (int i = 0; i < METRICS.length; i++) {
            String field = METRICS[i];
            if (night.isNull(field)) continue;
            Object value = night.get(field);
            if (!(value instanceof Number)) throw new JSONException("Ongeldige meting");
            double n = ((Number) value).doubleValue();
            boolean whole = !field.equals("leak") && !field.equals("leak95") && !field.equals("ahi");
            if (!(n >= 0 && n <= LIMITS[i]) || (whole && n != Math.floor(n))) throw new JSONException("Ongeldige meting");
            clean.put(field, value);
        }
        if (clean.length() == 2) throw new JSONException("Geen metingen");
        return clean;
    }

    /** Op schijf en in de versleutelde Alles appgegevens-backup heeft het logboek dezelfde versie. */
    static JSONObject normalize(String text) throws JSONException {
        if (text.length() > MAX_BYTES) throw new JSONException("CPAP-logboek is te groot");
        JSONObject root = new JSONObject(text);
        if (root.getInt("version") != 1) throw new JSONException("Onbekende versie van CPAP-logboek");
        JSONObject nights = root.getJSONObject("nights");
        if (nights.length() > MAX_NIGHTS) throw new JSONException("Te veel nachten");
        JSONObject clean = new JSONObject();
        for (Iterator<String> keys = nights.keys(); keys.hasNext(); ) {
            String day = keys.next();
            JSONObject night = cleanNight(nights.getJSONObject(day));
            if (!day.equals(night.getString("day"))) throw new JSONException("Ongeldige datum");
            clean.put(day, night);
        }
        return new JSONObject().put("version", 1).put("nights", clean);
    }

    static synchronized JSONObject load(Context c) throws Exception {
        File file = file(c);
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.getChannel().size() > MAX_BYTES + 128) throw new JSONException("CPAP-logboek is te groot");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Kluis.decrypt(in, out);
            return normalize(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException e) {
            if (file.exists()) throw e;
            return null;
        }
    }

    static synchronized JSONArray list(Context c) throws Exception {
        JSONObject nights = nights(c);
        JSONArray out = new JSONArray();
        for (Iterator<String> keys = nights.keys(); keys.hasNext(); ) out.put(nights.getJSONObject(keys.next()));
        return out;
    }

    /** Toevoegen en bewerken zijn apart, zodat een tweede venster geen andere nacht kan overschrijven. */
    static synchronized String save(Context c, String data, String expected) throws Exception {
        if (data == null || data.length() > 4096) throw new JSONException("Ongeldige meting");
        JSONObject night = cleanNight(new JSONObject(data));
        String day = night.getString("day");
        JSONObject nights = nights(c);
        if (expected.isEmpty()) {
            if (nights.has(day)) return "date";
            if (nights.length() >= MAX_NIGHTS) throw new JSONException("Te veel nachten");
        } else if (!same(nights.optJSONObject(day), expected)) return "changed";
        nights.put(day, night);
        write(c, nights);
        return "";
    }

    static synchronized JSONObject importNights(Context c, JSONObject imported) throws Exception {
        JSONObject nights = nights(c);
        JSONObject result = merge(nights, imported);
        if (result.getInt("added") > 0) write(c, nights);
        return result;
    }

    static JSONObject merge(JSONObject nights, JSONObject imported) throws JSONException {
        int added = 0, existing = 0;
        for (Iterator<String> keys = imported.keys(); keys.hasNext(); ) {
            String day = keys.next();
            if (nights.has(day)) {
                existing++;
                continue;
            }
            if (nights.length() >= MAX_NIGHTS) throw new JSONException("Te veel nachten");
            nights.put(day, imported.getJSONObject(day));
            added++;
        }
        return new JSONObject().put("added", added).put("existing", existing);
    }

    static synchronized String delete(Context c, String day, String expected) throws Exception {
        if (!validDay(day)) throw new JSONException("Ongeldige datum");
        JSONObject nights = nights(c);
        if (!same(nights.optJSONObject(day), expected)) return "changed";
        nights.remove(day);
        write(c, nights);
        return "";
    }

    static boolean same(JSONObject current, String expected) throws JSONException {
        return current != null && expected != null && !expected.isEmpty() && expected.length() <= 4096
                && current.toString().equals(cleanNight(new JSONObject(expected)).toString());
    }

    static synchronized void restore(Context c, File ready) throws Exception {
        if (!ready.renameTo(file(c))) throw new java.io.IOException("CPAP-logboek terugzetten lukt niet");
    }

    private static JSONObject nights(Context c) throws Exception {
        JSONObject root = load(c);
        return root == null ? new JSONObject() : root.getJSONObject("nights");
    }

    private static void write(Context c, JSONObject nights) throws Exception {
        byte[] bytes = new JSONObject().put("version", 1).put("nights", nights).toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new JSONException("CPAP-logboek is te groot");
        Kluis.writeEncrypted(file(c), bytes);
    }
}
