package nl.rene.tools;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONObject;

/**
 * Voortgang van het toevoegen van een bestand (boek, document, factuur, afschrift, foto's) voor de balk onderin de app
 * (interface: onVoortgang in 02-patronen.js). Alleen stappen en aantallen, nooit namen of inhoud.
 * Stappen: "lezen" (bytes van het bestand), "verwerken" (zonder aantal), "opslaan" (onderdelen, bijv. hoofdstukken).
 * Hooguit één bericht per ELKE_MS, behalve bij een nieuwe stap, een nieuw bestand en het einde (close: klaar).
 */
final class Voortgang implements AutoCloseable {
    interface Uit { void stuur(String json); }
    interface Klok { long nu(); }
    static final long ELKE_MS = 200;
    /** Geen balk (bijv. een toevoeging zonder scherm of in een test). */
    static final Voortgang GEEN = new Voortgang(null, "", 1, () -> 0L);

    private final Uit uit; private final String soort; private final Klok klok; private final int bestanden;
    private int bestand; private String stap = ""; private long laatst = Long.MIN_VALUE; private boolean klaar;

    Voortgang(Uit uit, String soort, int bestanden, Klok klok) {
        this.uit = uit; this.soort = soort; this.bestanden = Math.max(1, bestanden); this.klok = klok;
    }
    static Voortgang van(MainActivity a, String soort, int bestanden) {
        return new Voortgang(json -> a.js("onVoortgang", json), soort, bestanden, android.os.SystemClock::elapsedRealtime);
    }

    /** Het hoeveelste bestand (vanaf 0) nu aan de beurt is. */
    synchronized void bestand(int i) { if (i == bestand) return; bestand = Math.max(0, Math.min(i, bestanden - 1)); stap = ""; }

    /** Stand van een stap; totaal 0 = onbekend (de balk loopt dan heen en weer). */
    synchronized void stap(String naam, long gedaan, long totaal) {
        if (uit == null || klaar) return;
        long nu = klok.nu(); boolean nieuw = !naam.equals(stap), eind = totaal > 0 && gedaan >= totaal;
        if (!nieuw && !eind && laatst != Long.MIN_VALUE && nu - laatst < ELKE_MS) return;
        stap = naam; laatst = nu;
        stuur(new Bericht(naam, Math.max(0, gedaan), Math.max(0, totaal), false));
    }

    /** Leest een bestand en meldt onderweg hoeveel er gelezen is. */
    InputStream lees(InputStream in, long totaal) { return in == null || uit == null ? in : new Teller(in, this, totaal); }

    /** Opent een gekozen bestand met teller; de grootte komt van Android (onbekend = 0). */
    InputStream open(Context c, Uri u) throws IOException { return lees(c.getContentResolver().openInputStream(u), uit == null ? 0 : grootte(c, u)); }

    static long grootte(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return Math.max(0, cur.getLong(0));
        } catch (Throwable ignored) { }
        return 0;
    }

    @Override public synchronized void close() {
        if (uit == null || klaar) return;
        klaar = true; stuur(new Bericht("", 0, 0, true));
    }

    private void stuur(Bericht b) {
        try {
            JSONObject o = new JSONObject().put("soort", soort).put("bestand", bestand + 1).put("bestanden", bestanden);
            if (b.klaar) o.put("klaar", true); else o.put("stap", b.stap).put("gedaan", b.gedaan).put("totaal", b.totaal);
            uit.stuur(o.toString());
        } catch (Throwable ignored) { }
    }

    static final class Bericht {
        final String stap; final long gedaan, totaal; final boolean klaar;
        Bericht(String stap, long gedaan, long totaal, boolean klaar) { this.stap = stap; this.gedaan = gedaan; this.totaal = totaal; this.klaar = klaar; }
    }

    /** Telt de gelezen bytes (ook skip) en meldt ze als stap "lezen". */
    static final class Teller extends FilterInputStream {
        private final Voortgang v; private final long totaal; private long n;
        Teller(InputStream in, Voortgang v, long totaal) { super(in); this.v = v; this.totaal = Math.max(0, totaal); }
        private void tel(long k) { if (k <= 0) return; n += k; v.stap("lezen", totaal > 0 ? Math.min(n, totaal) : n, totaal); }
        @Override public int read() throws IOException { int r = super.read(); if (r >= 0) tel(1); return r; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int r = super.read(b, off, len); tel(r); return r; }
        @Override public long skip(long k) throws IOException { long r = super.skip(k); tel(r); return r; }
        @Override public boolean markSupported() { return false; }
    }
}
