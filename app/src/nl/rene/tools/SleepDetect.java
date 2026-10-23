package nl.rene.tools;

/**
 * Herkent in een doorlopende microfoonstroom (16 kHz mono, 16 bit) de stukjes waarin iets te horen is, zuinig:
 * per blokje van 20 ms alleen een paar optellingen en een filtertje, geen FFT. Zuiver (getest in SleepDetectTest).
 *
 * Werkwijze
 *  - Energie in de spraakband (±300–3400 Hz, twee eenvoudige filters) en in totaal, per blokje in dB.
 *  - Het ruisniveau van de kamer = het zachtste moment van de laatste ±8 seconden (praten heeft altijd even
 *    stiltes, een ventilator niet): een ventilator of de verwarming wordt zo binnen een paar seconden "stilte".
 *  - Een stukje begint als een paar blokjes kort na elkaar duidelijk boven het ruisniveau komen (gevoeligheid),
 *    en eindigt na een korte stilte. Er gaat een stukje van vóór het begin mee (voorloop), zodat het eerste woord
 *    er heel in staat, en wat na het einde (naloop).
 *  - Te kort (een tik, een kuch) telt niet mee; te lang wordt in delen geknipt.
 *  - Per stukje: hoeveel er in de spraakband zat (snurken zit vooral lager) en hoe hard het was.
 */
final class SleepDetect {

    static final int RATE = 16000;
    static final int FRAME = 320;                 // 20 ms
    static final int PRE_FRAMES = 100;            // 2 s voorloop
    static final int HANG_FRAMES = 75;            // 1,5 s stilte = einde
    static final int POST_FRAMES = 25;            // 0,5 s naloop (na de stilte die al meetelt)
    static final int MIN_ACTIVE = 15;             // minstens 0,3 s echt geluid
    static final int MAX_FRAMES = 60 * 50;        // hooguit 60 s per stukje
    static final double ABS_MIN_DB = -62;         // zachter dan dit is altijd stilte

    /** Een gevonden stukje. */
    static final class Clip {
        final short[] pcm; final long startFrame; final int activeFrames; final double bandRatio, peakDb, floorDb;
        Clip(short[] pcm, long startFrame, int activeFrames, double bandRatio, double peakDb, double floorDb) {
            this.pcm = pcm; this.startFrame = startFrame; this.activeFrames = activeFrames; this.bandRatio = bandRatio; this.peakDb = peakDb; this.floorDb = floorDb;
        }
        double seconds() { return pcm.length / (double) RATE; }
        /** Begin, in ms vanaf het starten van het luisteren. */
        long startMs() { return startFrame * 20L; }
    }

    interface Sink { void clip(Clip c); }

    private final double margin;     // dB boven het ruisniveau
    private final Sink sink;

    // Filters (biquad, vaste coëfficiënten voor 16 kHz): hoog-doorlaat 300 Hz en laag-doorlaat 3400 Hz
    private double hx1, hx2, hy1, hy2, lx1, lx2, ly1, ly2;
    private static final double[] HP = biquad(true, 300), LP = biquad(false, 3400);

    private double floorDb = -50;    // ruisniveau van de kamer (spraakband)
    // Minima per halve seconde, de laatste 16 (±8 s)
    private final double[] mins = new double[16];
    private int minPos = 0, minCount = 0, inBlock = 0;
    private double blockMin = 1e9;
    private long frameNo = 0;
    private final short[][] ring = new short[PRE_FRAMES][FRAME]; // vooraf gemaakt: geen nieuw geheugen per blokje
    private int ringPos = 0, ringLen = 0;
    private final boolean[] recent = new boolean[10];
    private int recentPos = 0;

    // Lopend stukje
    private short[] buf; private int bufLen; private long clipStart;
    private int active, quiet; private double bandSum, totSum, peak;
    private boolean inClip;
    private int warm = 50;           // eerste seconde: alleen het ruisniveau leren

    /** sensitivity: 1 = gevoelig (8 dB boven de ruis), 2 = normaal (12 dB), 3 = alleen duidelijk (16 dB). */
    SleepDetect(int sensitivity, Sink sink) {
        this.margin = sensitivity <= 1 ? 8 : sensitivity >= 3 ? 16 : 12;
        this.sink = sink;
    }

    double floorDb() { return floorDb; }
    boolean inClip() { return inClip; }

    /** Butterworth-biquad (Q = 0,707), RBJ-formules. {b0, b1, b2, a1, a2}. */
    static double[] biquad(boolean high, double f) {
        double w = 2 * Math.PI * f / RATE, cos = Math.cos(w), alpha = Math.sin(w) / (2 * 0.7071), a0 = 1 + alpha;
        double b0 = high ? (1 + cos) / 2 : (1 - cos) / 2, b1 = high ? -(1 + cos) : 1 - cos, b2 = b0;
        return new double[]{b0 / a0, b1 / a0, b2 / a0, -2 * cos / a0, (1 - alpha) / a0};
    }

    static double db(double meanSquare) { return 10 * Math.log10(meanSquare / (32768.0 * 32768.0) + 1e-12); }

    /** Eén blokje van FRAME samples. */
    void feed(short[] frame) {
        double tot = 0, band = 0;
        for (short s : frame) {
            double x = s;
            tot += x * x;
            double h = HP[0] * x + HP[1] * hx1 + HP[2] * hx2 - HP[3] * hy1 - HP[4] * hy2;
            hx2 = hx1; hx1 = x; hy2 = hy1; hy1 = h;
            double l = LP[0] * h + LP[1] * lx1 + LP[2] * lx2 - LP[3] * ly1 - LP[4] * ly2;
            lx2 = lx1; lx1 = h; ly2 = ly1; ly1 = l;
            band += l * l;
        }
        tot /= frame.length; band /= frame.length;
        double bandDb = db(band), totDb = db(tot);
        frameNo++;

        // Ruisniveau: het minimum van de laatste ±8 s (+1,5 dB: het zachtste blokje ligt net onder het gemiddelde)
        blockMin = Math.min(blockMin, bandDb);
        if (++inBlock >= 25) {
            mins[minPos] = blockMin; minPos = (minPos + 1) % mins.length; if (minCount < mins.length) minCount++;
            inBlock = 0; blockMin = 1e9;
        }
        double m = blockMin;
        for (int i = 0; i < minCount; i++) m = Math.min(m, mins[i]);
        if (m < 1e8) floorDb = Math.max(m + 1.5, -90);
        if (warm > 0) warm--;
        boolean loud = warm <= 0 && bandDb > floorDb + margin && bandDb > ABS_MIN_DB;

        recent[recentPos] = loud; recentPos = (recentPos + 1) % recent.length;
        if (!inClip) {
            int n = 0;
            for (boolean b : recent) if (b) n++;
            if (n >= 4) start(frame, bandDb, totDb, band, tot);
            else remember(frame);
            return;
        }
        append(frame);
        bandSum += band; totSum += tot; peak = Math.max(peak, totDb);
        if (loud) { active++; quiet = 0; } else quiet++;
        if (quiet >= HANG_FRAMES + POST_FRAMES || bufLen >= MAX_FRAMES * FRAME) finish();
    }

    private void remember(short[] f) {
        System.arraycopy(f, 0, ring[ringPos], 0, Math.min(f.length, FRAME)); ringPos = (ringPos + 1) % PRE_FRAMES; if (ringLen < PRE_FRAMES) ringLen++;
    }

    private void start(short[] f, double bandDb, double totDb, double band, double tot) {
        inClip = true;
        buf = new short[(PRE_FRAMES + 30 * 50) * FRAME]; bufLen = 0;
        clipStart = frameNo - 1 - ringLen;
        for (int i = 0; i < ringLen; i++) append(ring[(ringPos - ringLen + i + PRE_FRAMES) % PRE_FRAMES]);
        ringLen = 0;
        append(f);
        active = 1; quiet = 0; bandSum = band; totSum = tot; peak = totDb;
    }

    private void append(short[] f) {
        if (bufLen + f.length > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, bufLen + f.length));
        System.arraycopy(f, 0, buf, bufLen, f.length); bufLen += f.length;
    }

    private void finish() {
        inClip = false;
        java.util.Arrays.fill(recent, false);
        if (active >= MIN_ACTIVE) sink.clip(new Clip(java.util.Arrays.copyOf(buf, bufLen), clipStart, active, totSum > 0 ? bandSum / totSum : 0, peak, floorDb));
        buf = null; bufLen = 0;
    }

    /** Aan het eind van het luisteren: een lopend stukje afmaken. */
    void flush() { if (inClip) finish(); }

    /** Lijkt het op praten (veel in de spraakband) of op snurken/geluid? Ruwe gok; het uitschrijven beslist echt. */
    static boolean speechLike(Clip c) { return c.bandRatio >= 0.35; }
}
