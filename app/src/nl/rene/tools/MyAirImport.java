package nl.rene.tools;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Leest alleen nachtmetingen uit de export. De ZIP wordt nergens uitgepakt. */
final class MyAirImport {
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final int MAX_ENTRIES = 128;
    static final String UNKNOWN_MASK = "Onbekend masker";

    private MyAirImport() { }

    static JSONObject read(InputStream input) throws IOException, JSONException {
        byte[] csv = null;
        int remaining = MAX_BYTES;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            byte[] buffer = new byte[8192];
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++entries > MAX_ENTRIES) throw new IOException();
                String name = entry.getName();
                boolean sleep = !entry.isDirectory()
                        && name.substring(name.lastIndexOf('/') + 1).equalsIgnoreCase("SLEEP_RECORD.csv");
                if (sleep && csv != null) throw new IOException();
                ByteArrayOutputStream out = sleep ? new ByteArrayOutputStream() : null;
                for (int n; (n = zip.read(buffer)) != -1; ) {
                    remaining -= n;
                    if (remaining < 0) throw new IOException();
                    if (out != null) {
                        if (out.size() + n > CpapLog.MAX_BYTES) throw new IOException();
                        out.write(buffer, 0, n);
                    }
                }
                if (out != null) csv = out.toByteArray();
            }
        }
        if (csv == null) throw new IOException();
        return parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(csv)).toString());
    }

    private static JSONObject parse(String csv) throws IOException, JSONException {
        List<List<String>> rows = BankImport.rows(csv, BankImport.separator(csv));
        if (rows.size() < 2 || rows.size() > CpapLog.MAX_NIGHTS + 1) throw new IOException();
        List<String> header = rows.get(0);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) columns.put(BankImport.key(header.get(i)), i);
        if (!columns.containsKey("session_date")
                || !(columns.containsKey("gebruiksuren") || columns.containsKey("usage_hours"))) throw new IOException();

        JSONObject nights = new JSONObject();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() != header.size()) throw new IOException();
            if ("Y".equalsIgnoreCase(field(row, columns, "record_delete_ind"))) continue;
            String day = field(row, columns, "session_date");
            if (nights.has(day)) throw new IOException();
            Double hours = number(field(row, columns, "gebruiksuren", "usage_hours"));
            if (hours == null || !(hours >= 0 && hours <= 24)) throw new IOException();
            int minutes = (int) Math.round(hours * 60);
            JSONObject night = new JSONObject().put("day", day).put("mask", UNKNOWN_MASK).put("usage", minutes);
            night.put("score", number(field(row, columns, "slaapscore", "sleep_score")));
            if (hours > 0) {
                night.put("seal", number(field(row, columns, "leak_score")));
                night.put("leak95", measurement(field(row, columns, "leak_95_percentile")));
                night.put("ahi", measurement(field(row, columns, "ahi")));
                night.put("sessions", number(field(row, columns, "mask_session_count")));
            }
            nights.put(day, CpapLog.cleanNight(night));
        }
        if (nights.length() == 0) throw new IOException();
        return nights;
    }

    private static String field(List<String> row, Map<String, Integer> columns, String... names) {
        for (String name : names) {
            Integer index = columns.get(name);
            if (index != null) return row.get(index).trim();
        }
        return "";
    }

    private static Double measurement(String raw) throws IOException {
        Double value = number(raw);
        if (value == null) return null;
        if (!(value >= 0 && value <= 999)) throw new IOException();
        return Math.round(value * 100) / 100.0;
    }

    private static Double number(String raw) throws IOException {
        if (raw.isEmpty()) return null;
        try {
            return Double.valueOf(raw.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IOException();
        }
    }
}
