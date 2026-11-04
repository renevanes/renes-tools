package nl.rene.tools;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Taal van de app (Instellingen → Regio en taal → Taal): Nederlands (standaard) of Engels.
 * Nederlands is de brontekst in de code, net als in de interface (web/src/script/02-taal.js):
 * <pre>
 *   Taal.t(ctx, "Backup klaar")                         → "Backup finished" als de app Engels is
 *   Taal.t(ctx, "{n} berichten bewaard", "n", 12)       → plaatshouders als naam/waarde-paren
 *   Taal.tn(ctx, n, "Eén bericht", "{n} berichten")     → enkelvoud of meervoud
 *   Taal.T("Radio")                                     → alleen een markering voor vaste lijsten (vertaald bij gebruik)
 * </pre>
 * Eén woordenboek voor Java en de interface: web/src/taal/en.json (in de APK: assets/taal/en.json, via build.sh).
 * De taal volgt de keuze in de interface (Android.regioSet → Regio), nooit de taal van de telefoon.
 * tools/taal.js (tests/ui/taal-dekking.test.js) controleert dat elke Taal.t-tekst een Engelse vertaling heeft.
 */
final class Taal {

    private Taal() { }

    static final String ASSET = "taal/en.json";
    private static volatile Map<String, Object> en;
    private static volatile Map<String, String> back;
    private static final Pattern WS = Pattern.compile("[ \\t\\r\\n]+");
    private static final Pattern PH = Pattern.compile("\\{(\\w+)\\}");

    /** Staat de app in het Engels? */
    static boolean en(Context c) { return c != null && "en".equals(Regio.cfg(c).lang); }

    /** "nl" of "en" */
    static String lang(Context c) { return en(c) ? "en" : "nl"; }

    /** Markering voor een vaste tekst die later met {@link #t(Context, String)} vertaald wordt. */
    static String T(String nl) { return nl; }

    static String t(Context c, String nl) { return en(c) ? tr(dict(c), true, nl) : nl; }

    static String t(Context c, String nl, Object... kv) { return tr(en(c) ? dict(c) : null, en(c), nl, kv); }

    static String tn(Context c, long n, String one, String many, Object... kv) {
        Object[] all = new Object[kv.length + 2];
        all[0] = "n"; all[1] = n;
        System.arraycopy(kv, 0, all, 2, kv.length);
        return t(c, n == 1 ? one : many, all);
    }

    /** Puur (getest in TaalTest): vertaal s met woordenboek d (als en) en vul de plaatshouders in. */
    static String tr(Map<String, Object> d, boolean en, String s, Object... kv) {
        if (s == null) return "";
        String r = s;
        if (en && d != null) {
            String k = norm(s);
            Object e = d.get(k);
            if (e instanceof String[]) e = ((String[]) e)[one(kv) ? 0 : 1];
            if (e instanceof String) {
                int a = 0, b = s.length();
                while (a < b && Character.isWhitespace(s.charAt(a))) a++;
                while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
                r = s.substring(0, a) + e + s.substring(b);
            }
        }
        return kv == null || kv.length < 2 ? r : fill(r, kv);
    }

    private static boolean one(Object[] kv) {
        if (kv == null) return false;
        for (int i = 0; i + 1 < kv.length; i += 2) if ("n".equals(kv[i])) return kv[i + 1] instanceof Number && ((Number) kv[i + 1]).doubleValue() == 1;
        return false;
    }

    static String fill(String s, Object... kv) {
        if (s.indexOf('{') < 0) return s;
        Matcher m = PH.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String v = null;
            for (int i = 0; i + 1 < kv.length; i += 2) if (m.group(1).equals(String.valueOf(kv[i]))) { v = String.valueOf(kv[i + 1]); break; }
            m.appendReplacement(b, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Zoals de sleutels in en.json: witruimte samengevoegd, zonder zachte afbreekstreepjes, zonder randspaties. */
    static String norm(String s) { return WS.matcher(s.replace("­", "")).replaceAll(" ").trim(); }

    /** Het woordenboek uit JSON: tekst → vertaling (String) of {enkelvoud, meervoud} (String[2]). */
    static Map<String, Object> parse(String json) {
        Map<String, Object> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = o.get(k);
                if (v instanceof JSONArray && ((JSONArray) v).length() == 2) m.put(k, new String[]{((JSONArray) v).getString(0), ((JSONArray) v).getString(1)});
                else if (v instanceof String) m.put(k, v);
            }
        } catch (Exception ignored) { }
        return m;
    }

    static Map<String, Object> dict(Context c) {
        Map<String, Object> d = en;
        if (d != null) return d;
        synchronized (Taal.class) {
            if (en != null) return en;
            Map<String, Object> m = Collections.emptyMap();
            try (InputStream in = c.getAssets().open(ASSET)) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
                m = parse(new String(o.toByteArray(), StandardCharsets.UTF_8));
            } catch (Throwable t) { App.log(c, "TAAL", String.valueOf(t)); }
            en = m;
            return m;
        }
    }

    /** Engels → Nederlands (alleen gewone teksten), voor het terugzetten van namen die Android bewaart. */
    static Map<String, String> reverse(Map<String, Object> d) {
        Map<String, String> r = new HashMap<>();
        for (Map.Entry<String, Object> e : d.entrySet()) if (e.getValue() instanceof String && !r.containsKey(e.getValue())) r.put((String) e.getValue(), e.getKey());
        return r;
    }

    /** Een naam die Android bewaart (meldingskanaal) in de taal van nu: van Nederlands naar Engels of terug. */
    static String switchName(Context c, String name) {
        if (name == null) return null;
        Map<String, Object> d = dict(c);
        if (en(c)) { Object e = d.get(norm(name)); return e instanceof String ? (String) e : name; }
        Map<String, String> r = back;
        if (r == null) back = r = reverse(d);
        String nl = r.get(name);
        return nl == null ? name : nl;
    }

    /** Na een andere taal: de namen van de meldingskanalen (Instellingen → Meldingen van Android) mee laten gaan. */
    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            List<NotificationChannel> list = nm.getNotificationChannels();
            for (NotificationChannel ch : list) {
                String was = String.valueOf(ch.getName()), now = switchName(c, was);
                if (now.equals(was)) continue;
                NotificationChannel n = new NotificationChannel(ch.getId(), now, ch.getImportance());
                if (ch.getDescription() != null) n.setDescription(switchName(c, ch.getDescription()));
                if (ch.getGroup() != null) n.setGroup(ch.getGroup());
                nm.createNotificationChannel(n);
            }
        } catch (Throwable t) { App.log(c, "TAAL", String.valueOf(t)); }
    }

    /** Alle sleutels (voor tests). */
    static int size(Context c) { return dict(c).size(); }

    static Map<String, Object> forTest(Map<String, Object> d) { en = d; back = null; return d; }

    static HashMap<String, Object> copy(Map<String, Object> d) { return new HashMap<>(d); }
}
