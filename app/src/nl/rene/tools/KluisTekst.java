package nl.rene.tools;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * De documentenkluis doorzoeken op inhoud. Per document staat de tekst (uit de PDF, of herkend op de telefoon) in een
 * eigen bestand kluis/&lt;id&gt;.tekst, versleuteld met dezelfde sleutel als de kluis. Lezen en zoeken gebeurt alleen
 * terwijl de kluis open is. De tekst is af te leiden uit het document: hij gaat niet mee in backups en wordt na
 * terugzetten vanzelf opnieuw gemaakt. Het zuivere deel (netjes, plat, woorden, stuk) is getest in KluisTekstTest.
 */
final class KluisTekst {

    private KluisTekst() { }

    interface Uit { void stuur(String json); }

    static final int MAX_TEKST = 200_000, MAX_BLZ = 20, STUK = 70, MIN_VRAAG = 3;
    static final String EXT = ".tekst", WERK = "kluis-tekst";
    private static final AtomicBoolean BEZIG = new AtomicBoolean();

    // ---------- zuiver ----------

    /** Witruimte samengenomen, begrensd. */
    static String netjes(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\s\\u00a0]+", " ").trim();
        return t.length() > MAX_TEKST ? t.substring(0, MAX_TEKST) : t;
    }

    /** Kleine letters zonder accenten, teken voor teken (de lengte blijft gelijk, zodat plekken kloppen). */
    static String plat(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            if (ch > 127) { String n = java.text.Normalizer.normalize(String.valueOf(ch), java.text.Normalizer.Form.NFD); if (!n.isEmpty() && n.charAt(0) < 128) ch = n.charAt(0); }
            b.append(ch);
        }
        return b.toString();
    }

    /** De woorden van een zoekvraag (plat); leeg als de vraag te kort is. */
    static List<String> woorden(String q) {
        List<String> uit = new ArrayList<>();
        String p = plat(netjes(q));
        if (p.replace(" ", "").length() < MIN_VRAAG) return uit;
        for (String w : p.split(" ")) if (!w.isEmpty() && !uit.contains(w) && uit.size() < 8) uit.add(w);
        return uit;
    }

    /** Het stukje tekst rond de eerste treffer als alle woorden voorkomen, anders null. */
    static String stuk(String tekst, List<String> woorden) {
        if (tekst == null || tekst.isEmpty() || woorden.isEmpty()) return null;
        String p = plat(tekst);
        int eerste = -1, lang = 0;
        for (String w : woorden) { int i = p.indexOf(w); if (i < 0) return null; if (eerste < 0 || i < eerste) { eerste = i; lang = w.length(); } }
        int a = Math.max(0, eerste - STUK), z = Math.min(tekst.length(), eerste + lang + STUK);
        if (a > 0) { int s = tekst.indexOf(' ', a); if (s > 0 && s < eerste) a = s + 1; }
        if (z < tekst.length()) { int s = tekst.lastIndexOf(' ', z); if (s > eerste + lang) z = s; }
        return (a > 0 ? "… " : "") + tekst.substring(a, z) + (z < tekst.length() ? " …" : "");
    }

    /** Wat voor lezen in aanmerking komt: pdf, beeld of tekst (aan de soort); de rest krijgt een lege tekst. */
    static String soort(String mime, String naam) {
        String m = mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT), n = naam == null ? "" : naam.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("pdf") || n.endsWith(".pdf")) return "pdf";
        if (m.startsWith("image/")) return "beeld";
        if (m.startsWith("text/") || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")) return "tekst";
        return "";
    }

    // ---------- opslag ----------

    static File bestand(Context c, String id) { return new File(Kluis.dir(c), Kluis.safeId(id) + EXT); }

    /** De bewaarde tekst van een document, of null als die er niet is of bij een eerdere inhoud hoort. */
    static String lees(Context c, String id) {
        File f = bestand(c, id), blob = new File(Kluis.dir(c), id + ".bin");
        if (!f.exists() || !blob.exists()) return null;
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f))) { Kluis.decrypt(in, b); }
            JSONObject o = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            if (o.optLong("n") != blob.length() || o.optLong("t") != blob.lastModified()) return null;
            return o.optString("tekst");
        } catch (Throwable e) { return null; }
    }

    private static void bewaar(Context c, String id, String tekst) throws Exception {
        File blob = new File(Kluis.dir(c), id + ".bin");
        Kluis.writeEncrypted(bestand(c, id), new JSONObject().put("v", 1).put("n", blob.length()).put("t", blob.lastModified()).put("tekst", netjes(tekst)).toString().getBytes(StandardCharsets.UTF_8));
    }

    static void weg(Context c, String id) { try { bestand(c, id).delete(); } catch (Throwable e) { /* niets te doen */ } }

    private static File werk(Context c) { File d = new File(c.getCacheDir(), WERK); d.mkdirs(); return d; }

    /** Werkbestanden (ontsleutelde kopieën tijdens het lezen) weg; ook bij het starten van de app. */
    static void ruimWerk(Context c) { File[] fs = werk(c).listFiles(); if (fs != null) for (File f : fs) f.delete(); }

    // ---------- lezen ----------

    private static String leesDocument(Context c, JSONObject d, BooleanSupplier door) throws Exception {
        String id = d.optString("id"), soort = soort(d.optString("mime"), d.optString("name"));
        if (soort.isEmpty() || d.optLong("size") > PdfCompare.MAX_BYTES) return "";
        File f = new File(werk(c), java.util.UUID.randomUUID().toString());
        try {
            Kluis.decryptTo(c, id, 0, f);
            if ("tekst".equals(soort)) {
                byte[] buf = new byte[(int) Math.min(f.length(), MAX_TEKST * 2L)];
                try (InputStream in = new FileInputStream(f)) { int n = Kluis.readFully(in, buf); return new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8); }
            }
            boolean pdf = "pdf".equals(soort) && PdfCompare.isPdf(f);
            int blz = 1;
            if (pdf) {
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(fd)) { blz = r.getPageCount(); }
                catch (SecurityException e) { return ""; } // met een wachtwoord beveiligd: niet te lezen
            } else if ("pdf".equals(soort)) return "";
            PdfCompare.Slot s = new PdfCompare.Slot(f, "document", Math.min(blz, MAX_BLZ), pdf, "", 0);
            return PdfCompare.pagesText(c, s, door, new boolean[1], Voortgang.GEEN, new int[1], s.pages);
        } finally { f.delete(); }
    }

    /** Leest op de achtergrond de documenten die nog geen (actuele) tekst hebben, zolang de kluis open is. Eén tegelijk. */
    static void start(Context c, Uit uit) {
        if (!BEZIG.compareAndSet(false, true)) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            int klaar = 0, totaal = 0, nieuw = 0;
            try {
                ruimWerk(app);
                JSONArray l = Kluis.list(app);
                Set<String> ids = new HashSet<>();
                List<JSONObject> doen = new ArrayList<>();
                for (int i = 0; i < l.length(); i++) { JSONObject d = l.getJSONObject(i); ids.add(d.optString("id")); if (lees(app, d.optString("id")) == null) doen.add(d); }
                File[] fs = Kluis.dir(app).listFiles(); // tekst van documenten die er niet meer zijn
                if (fs != null) for (File f : fs) { String n = f.getName(); if (n.endsWith(EXT) && !ids.contains(n.substring(0, n.length() - EXT.length()))) f.delete(); }
                totaal = doen.size();
                final BooleanSupplier door = Kluis::isOpen;
                for (JSONObject d : doen) {
                    if (!door.getAsBoolean()) break;
                    stuur(uit, klaar, totaal, true);
                    String tekst;
                    try { tekst = leesDocument(app, d, door); } catch (Throwable e) { tekst = ""; } // onleesbaar: niet elke keer opnieuw proberen
                    if (!door.getAsBoolean()) break; // halverwege op slot: de tekst is niet af, later opnieuw
                    try { synchronized (Kluis.class) { if (Kluis.find(app, d.optString("id")) != null) bewaar(app, d.optString("id"), tekst); } nieuw++; } catch (Throwable e) { /* volgende keer opnieuw */ }
                    klaar++;
                }
                if (nieuw > 0) App.log(app, "KLUIS", "tekst gelezen: " + nieuw);
            } catch (Throwable e) { App.log(app, "KLUIS", "tekst lezen: " + e.getClass().getSimpleName()); }
            finally { try { ruimWerk(app); } catch (Throwable e) { /* cache */ } BEZIG.set(false); stuur(uit, klaar, totaal, false); }
        }, "kluis-tekst").start();
    }

    private static void stuur(Uit uit, int klaar, int totaal, boolean bezig) {
        try { if (uit != null) uit.stuur(new JSONObject().put("klaar", klaar).put("totaal", totaal).put("bezig", bezig).toString()); } catch (Throwable e) { /* alleen een melding */ }
    }

    /** Zoekt in de bewaarde teksten. {q, hits:{id:stukje}, zonder: aantal documenten waarvan de tekst nog niet gelezen is}. */
    static JSONObject zoek(Context c, String q) throws Exception {
        List<String> w = woorden(q);
        JSONObject hits = new JSONObject(); int zonder = 0;
        if (!w.isEmpty()) {
            JSONArray l = Kluis.list(c);
            for (int i = 0; i < l.length() && Kluis.isOpen(); i++) {
                String id = l.getJSONObject(i).optString("id"), t = lees(c, id);
                if (t == null) { zonder++; continue; }
                String s = stuk(t, w);
                if (s != null) hits.put(id, s);
            }
        }
        return new JSONObject().put("q", q == null ? "" : q).put("hits", hits).put("zonder", zonder);
    }
}
