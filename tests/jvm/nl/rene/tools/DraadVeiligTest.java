package nl.rene.tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stabiliteit: een fout op een achtergronddraad laat de hele app stoppen (App.onCreate geeft hem door aan Android).
 * Elke {@code new Thread(…, "naam")} vangt daarom Throwable: in de lambda zelf, in de methode die hij aanroept, of via
 * {@link App#veilig}. Alleen de lussen van VPN en Timeshift staan bewust apart (zie UITZONDERING).
 */
public class DraadVeiligTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    /**
     * Bestand → hoeveel draden zonder Throwable er hooguit mogen zijn, met de reden. Alleen omlaag bijwerken.
     * VPN: een stille fout zou de tunnel half laten werken (verkeer weg terwijl hij "verbonden" zegt); daar mag de
     * dienst liever stoppen en opnieuw starten. Timeshift: lokale server van de radio, met eigen afhandeling per verbinding.
     */
    static final Map<String, Integer> UITZONDERING = new HashMap<>();
    static {
        UITZONDERING.put("WgDevice", 3);
        UITZONDERING.put("VpnSvc", 2);
        UITZONDERING.put("VpnNet", 1);
        UITZONDERING.put("Timeshift", 3);
    }

    static final Map<String, String> SRC = new HashMap<>();

    /** s.charAt(i) == '{' → index na de bijbehorende '}' (tekst tussen aanhalingstekens telt niet mee). */
    static int block(String s, int i) {
        int d = 0;
        for (;; i++) {
            char c = s.charAt(i);
            if (c == '{') d++;
            else if (c == '}') { if (--d == 0) return i + 1; }
            else if (c == '"' || c == '\'') i = skipQuoted(s, i);
        }
    }
    static int skipQuoted(String s, int i) {
        char q = s.charAt(i);
        for (i++; s.charAt(i) != q; i++) if (s.charAt(i) == '\\') i++;
        return i;
    }
    /** Einde van het eerste argument dat op i begint (bij een komma of ')' op het bovenste niveau). */
    static int argEnd(String s, int i) {
        int d = 0;
        for (;; i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '{' || c == '[') d++;
            else if (c == ')' || c == '}' || c == ']') { if (d == 0) return i; d--; }
            else if (c == ',' && d == 0) return i;
            else if (c == '"' || c == '\'') i = skipQuoted(s, i);
        }
    }
    static boolean methodCatches(String cls, String m) {
        String s = SRC.get(cls);
        if (s == null) return false;
        Matcher mm = Pattern.compile("\\b" + Pattern.quote(m) + "\\s*\\([^;{)]*\\)\\s*(throws[^{;]*)?\\{").matcher(s);
        while (mm.find()) if (s.substring(mm.end() - 1, block(s, mm.end() - 1)).contains("Throwable")) return true;
        return false;
    }
    static final Pattern CALL = Pattern.compile("^\\(\\)\\s*->\\s*(?:(\\w+)\\.)?(\\w+)\\s*\\(");
    static final Pattern REF = Pattern.compile("^(?:(\\w+)|this)::(\\w+)$");
    static boolean beschermd(String cls, String arg) {
        String a = arg.trim();
        if (a.startsWith("App.veilig(")) return true;
        if (a.matches("(?s)^\\(\\)\\s*->\\s*\\{.*")) return a.contains("Throwable");
        Matcher m = CALL.matcher(a);
        if (!m.find()) { m = REF.matcher(a); if (!m.find()) return false; }
        String c = m.group(1);
        return methodCatches(c != null && Character.isUpperCase(c.charAt(0)) ? c : cls, m.group(2));
    }

    /** Draden zonder Throwable: "Bestand:regel". */
    static List<String> onbeschermd() {
        List<String> uit = new ArrayList<>();
        for (Map.Entry<String, String> e : SRC.entrySet()) {
            String s = e.getValue();
            Matcher m = Pattern.compile("new Thread\\(").matcher(s);
            while (m.find()) {
                int st = m.end(), en = argEnd(s, st);
                if (s.charAt(en) == ')') continue; // new Thread(r) zonder naam: een subclass of eigen opzet
                if (!beschermd(e.getKey(), s.substring(st, en))) {
                    int regel = 1; for (int i = 0; i < st; i++) if (s.charAt(i) == '\n') regel++;
                    uit.add(e.getKey() + ":" + regel);
                }
            }
        }
        return uit;
    }

    public static void main(String[] a) throws Exception {
        File dir = new File("app/src/nl/rene/tools");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".java"));
        check(files != null && files.length > 100, "broncode gevonden");
        for (File f : files) SRC.put(f.getName().replace(".java", ""), new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));

        // De controle zelf
        SRC.put("ProefA", "class ProefA { void a(){ new Thread(() -> { try { x(); } catch (Throwable e) {} }, \"a\").start();"
                + " new Thread(() -> { x(); }, \"b\").start(); new Thread(this::lus, \"c\").start(); new Thread(this::veilig, \"d\").start();"
                + " new Thread(App.veilig(() -> x()), \"e\").start(); new Thread(() -> ProefB.werk(\"}\"), \"f\").start(); }"
                + " void lus(){ while (true) x(); } void veilig(){ try { x(); } catch (Throwable t) { } } }");
        SRC.put("ProefB", "class ProefB { static void werk(String s){ try { } catch (Throwable t) { } } }");
        List<String> proef = new ArrayList<>();
        for (String p : onbeschermd()) if (p.startsWith("Proef")) proef.add(p);
        check(proef.size() == 2 && proef.contains("ProefA:1"), "controle herkent draden zonder Throwable (" + proef + ")");
        SRC.remove("ProefA"); SRC.remove("ProefB");

        Map<String, Integer> perBestand = new HashMap<>();
        List<String> zonder = onbeschermd();
        for (String p : zonder) perBestand.merge(p.substring(0, p.indexOf(':')), 1, Integer::sum);
        for (Map.Entry<String, Integer> e : perBestand.entrySet()) {
            Integer mag = UITZONDERING.get(e.getKey());
            check(mag != null && e.getValue() <= mag, e.getKey() + ": " + e.getValue() + " draad/draden zonder Throwable"
                    + (mag == null ? ", gebruik new Thread(App.veilig(() -> …), \"naam\") (" + zonder + ")" : " (hooguit " + mag + ")"));
        }
        check(zonder.size() <= 9, "in totaal hooguit 9 draden zonder Throwable, allemaal VPN of Timeshift (nu " + zonder.size() + ")");

        // App.veilig: een fout (ook een Error) stopt alleen die draad, niet de app
        AtomicBoolean doorgegeven = new AtomicBoolean(false), daarna = new AtomicBoolean(false);
        Thread t = new Thread(App.veilig(() -> { throw new OutOfMemoryError("proef"); }), "proef-draad");
        t.setUncaughtExceptionHandler((th, ex) -> doorgegeven.set(true));
        t.start(); t.join(5000);
        check(!t.isAlive() && !doorgegeven.get(), "App.veilig vangt een Error op de draad");
        Thread t2 = new Thread(App.veilig(() -> daarna.set(true)), "proef-goed");
        t2.start(); t2.join(5000);
        check(daarna.get(), "App.veilig voert het werk gewoon uit");

        if (fails > 0) { System.out.println(fails + " mislukt"); System.exit(1); }
        System.out.println("alles goed");
    }
}
