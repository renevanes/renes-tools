package nl.rene.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Slaappraat: herkent stukjes met geluid in een nacht vol stilte, zonder op een ventilator, tik of brom te reageren. */
public class SleepDetectTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static final Random R = new Random(7);

    /** Ruis van de kamer (zacht) plus eventueel een signaal; amp in fractie van vol. */
    static void feed(SleepDetect d, double seconds, double noise, double[] freqs, double amp, boolean syllables) {
        int n = (int) (seconds * SleepDetect.RATE / SleepDetect.FRAME);
        for (int f = 0; f < n; f++) {
            short[] fr = new short[SleepDetect.FRAME];
            for (int i = 0; i < fr.length; i++) {
                double t = (f * SleepDetect.FRAME + i) / (double) SleepDetect.RATE;
                double x = R.nextGaussian() * noise;
                if (freqs != null) {
                    double env = syllables ? Math.max(0, Math.sin(2 * Math.PI * 4 * t)) : 1; // ±4 lettergrepen per seconde
                    for (double fq : freqs) x += amp * env * Math.sin(2 * Math.PI * fq * t) / freqs.length;
                }
                fr[i] = (short) Math.max(-32768, Math.min(32767, x * 32767));
            }
            d.feed(fr);
        }
    }

    public static void main(String[] a) {
        double quiet = 0.0008; // ±-62 dB: een stille slaapkamer
        double[] voice = {220, 700, 1200, 2400};

        List<SleepDetect.Clip> clips = new ArrayList<>();
        SleepDetect d = new SleepDetect(2, clips::add);
        feed(d, 10, quiet, null, 0, false);
        check(clips.isEmpty() && !d.inClip(), "stilte: niets");
        feed(d, 3, quiet, voice, 0.05, true);   // ±-30 dB praten
        feed(d, 6, quiet, null, 0, false);
        check(clips.size() == 1, "praten: één stukje (" + clips.size() + ")");
        SleepDetect.Clip c = clips.get(0);
        check(c.seconds() >= 4 && c.seconds() <= 8, "met voor- en naloop: " + String.format("%.1f", c.seconds()) + " s");
        check(c.startMs() >= 8000 && c.startMs() <= 10500, "begint net vóór het praten (" + c.startMs() + " ms)");
        check(SleepDetect.speechLike(c), "lijkt op praten (spraakband " + String.format("%.2f", c.bandRatio) + ")");

        // Brom/snurken laag in de toonhoogte: veel minder in de spraakband
        clips.clear();
        feed(d, 3, quiet, new double[]{60, 90, 120}, 0.3, true);
        feed(d, 5, quiet, null, 0, false);
        check(clips.isEmpty() || !SleepDetect.speechLike(clips.get(0)), "lage brom: geen praten");

        // Een tik (40 ms) is geen stukje
        clips.clear();
        feed(d, 0.04, quiet, voice, 0.5, false);
        feed(d, 5, quiet, null, 0, false);
        check(clips.isEmpty(), "een tik: niets");

        // Een ventilator die aangaat: het ruisniveau past zich aan, daarna niets meer
        clips.clear();
        SleepDetect f = new SleepDetect(2, clips::add);
        feed(f, 5, quiet, null, 0, false);
        feed(f, 120, 0.02, null, 0, false); // ruis ±-34 dB, de hele tijd
        int afterFan = clips.size();
        feed(f, 60, 0.02, null, 0, false);
        check(clips.size() == afterFan && afterFan <= 1 && !f.inClip(), "ventilator: hooguit één keer, daarna stil (" + afterFan + ")");
        feed(f, 3, 0.02, voice, 0.3, true); // praten boven de ventilator uit
        feed(f, 5, 0.02, null, 0, false);
        check(clips.size() == afterFan + 1, "praten boven de ventilator uit: wel gehoord");

        // Lang praten: in stukken van hooguit 60 s
        clips.clear();
        SleepDetect l = new SleepDetect(2, clips::add);
        feed(l, 3, quiet, null, 0, false);
        feed(l, 100, quiet, voice, 0.05, true);
        l.flush();
        check(clips.size() == 2 && clips.get(0).seconds() <= 61, "lang praten: in delen (" + clips.size() + ")");
        if (clips.size() == 2) {
            long end0 = clips.get(0).startMs() + Math.round(clips.get(0).seconds() * 1000);
            check(clips.get(1).startMs() >= end0 - 100 && clips.get(1).startMs() < end0 + 3000, "tweede deel sluit aan op het eerste (tijd klopt)");
        }
        // Een gesprek tussendoor (niet geluisterd): de tijd van het volgende stukje klopt
        clips.clear();
        SleepDetect gp = new SleepDetect(2, clips::add);
        feed(gp, 3, quiet, null, 0, false);
        gp.gap(60 * 50); // een minuut
        feed(gp, 2, quiet, null, 0, false);
        feed(gp, 2, quiet, voice, 0.05, true);
        feed(gp, 4, quiet, null, 0, false);
        check(clips.size() == 1 && clips.get(0).startMs() >= 63000 && clips.get(0).startMs() <= 65500, "na een gesprek: tijd loopt door (" + (clips.isEmpty() ? -1 : clips.get(0).startMs()) + " ms)");
        // Stoppen midden in het praten: het stukje komt er toch
        clips.clear();
        SleepDetect fl = new SleepDetect(2, clips::add);
        feed(fl, 3, quiet, null, 0, false);
        feed(fl, 2, quiet, voice, 0.05, true);
        check(clips.isEmpty() && fl.inClip(), "nog bezig");
        fl.flush();
        check(clips.size() == 1 && !fl.inClip(), "bij stoppen afgemaakt");

        // Een aanhoudende toon zonder pauzes (douche, stofzuiger) wordt na een paar seconden "ruis": het stukje stopt
        clips.clear();
        SleepDetect h = new SleepDetect(2, clips::add);
        feed(h, 3, quiet, null, 0, false);
        feed(h, 60, quiet, voice, 0.05, false);
        check(clips.size() == 1 && clips.get(0).seconds() < 20, "aanhoudend geluid: kort stukje, daarna ruis");

        // Gevoeligheid: zacht gemompel alleen bij "gevoelig"
        List<SleepDetect.Clip> g = new ArrayList<>(), s = new ArrayList<>();
        SleepDetect dg = new SleepDetect(1, g::add), ds = new SleepDetect(3, s::add);
        for (SleepDetect x : new SleepDetect[]{dg, ds}) { feed(x, 3, quiet, null, 0, false); }
        R.setSeed(3); feed(dg, 3, quiet, voice, 0.008, true); feed(dg, 5, quiet, null, 0, false);
        R.setSeed(3); feed(ds, 3, quiet, voice, 0.008, true); feed(ds, 5, quiet, null, 0, false);
        check(g.size() == 1 && s.isEmpty(), "zacht gemompel: wel bij gevoelig, niet bij alleen duidelijk (" + g.size() + "/" + s.size() + ")");

        // Instelbaar per nacht: voorloop, stilte voor het einde, langste duur (standaard zoals altijd)
        check(SleepDetect.Params.DEFAULT.preFrames == 100 && SleepDetect.Params.DEFAULT.hangFrames == 75 && SleepDetect.Params.DEFAULT.maxFrames == 3000
                && SleepDetect.Params.of(2, 1500, 60).hangFrames == 75, "standaard: 2 s voorloop, 1,5 s stilte, 60 s");
        List<SleepDetect.Clip> p1 = new ArrayList<>(), p2 = new ArrayList<>();
        SleepDetect d1 = new SleepDetect(2, p1::add), d2 = new SleepDetect(2, SleepDetect.Params.of(5, 3000, 30), p2::add);
        for (SleepDetect x : new SleepDetect[]{d1, d2}) {
            R.setSeed(11);
            feed(x, 8, quiet, null, 0, false);
            feed(x, 3, quiet, voice, 0.05, true); feed(x, 2.2, quiet, null, 0, false); feed(x, 3, quiet, voice, 0.05, true);
            feed(x, 8, quiet, null, 0, false);
        }
        check(p1.size() == 2 && p2.size() == 1, "stilte voor het einde: 2,2 s pauze is bij 1,5 s twee stukjes, bij 3 s één (" + p1.size() + "/" + p2.size() + ")");
        check(!p2.isEmpty() && p2.get(0).seconds() - p1.get(0).seconds() > 2.5, "langere voorloop: het stukje begint eerder");
        p2.clear();
        SleepDetect d3 = new SleepDetect(2, SleepDetect.Params.of(2, 1500, 30), p2::add);
        feed(d3, 3, quiet, null, 0, false);
        for (int i = 0; i < 20; i++) { feed(d3, 3, quiet, voice, 0.05, true); feed(d3, 0.4, quiet, null, 0, false); }
        d3.flush();
        boolean cut = p2.size() >= 2; for (SleepDetect.Clip k : p2) cut &= k.seconds() <= 30.5;
        check(cut, "langste duur 30 s: lang praten wordt in stukken van hooguit 30 s geknipt (" + p2.size() + ")");

        if (fails > 0) { System.out.println(fails + " fout(en)"); System.exit(1); }
        System.out.println("alles goed");
        System.exit(0);
    }
}
