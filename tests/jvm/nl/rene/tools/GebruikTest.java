package nl.rene.tools;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Gebruik: app-sessies, blokken per ontgrendeling (pincode via bouncer-signaal/anders/onbekend/mislukt), schermtijd per uur (zomer-/wintertijd), bewaartermijn, snelheid. */
public class GebruikTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "✓ " : "✗ ") + what); if (!ok) fails++; }

    static final TimeZone AMS = TimeZone.getTimeZone("Europe/Amsterdam");
    static long at(int y, int mo, int d, int h, int mi) { Calendar k = Calendar.getInstance(AMS); k.clear(); k.set(y, mo - 1, d, h, mi); return k.getTimeInMillis(); }
    static long min(int m) { return m * 60_000L; }
    static List<Long> bnc(long... ts) { List<Long> l = new ArrayList<>(); for (long t : ts) l.add(t); return l; }
    static final List<Long> NONE = new ArrayList<>();

    public static void main(String[] a) {
        // ---------- sessies ----------
        List<Gebruik.Ev> evs = new ArrayList<>();
        long t0 = at(2026, 10, 29, 9, 0);
        evs.add(new Gebruik.Ev(Gebruik.APP_START, "nl.rene.tools", t0));
        evs.add(new Gebruik.Ev(Gebruik.APP_START, "com.whatsapp", t0 + min(5)));
        evs.add(new Gebruik.Ev(Gebruik.APP_STOP, "com.whatsapp", t0 + min(8)));
        evs.add(new Gebruik.Ev(Gebruik.APP_START, "com.android.chrome", t0 + min(8)));
        evs.add(new Gebruik.Ev(Gebruik.SCREEN_OFF, null, t0 + min(20)));
        List<Gebruik.Session> sess = Gebruik.sessions(evs);
        check(sess.size() == 3, "drie sessies (" + sess.size() + ")");
        check(sess.get(0).dur() == min(5) && sess.get(2).dur() == min(12), "duur klopt (5 en 12 min)");

        // nog lopende app telt tot nu
        List<Gebruik.Ev> open = new ArrayList<>();
        open.add(new Gebruik.Ev(Gebruik.APP_START, "x", t0));
        check(Gebruik.sessions(open, t0 + min(3)).get(0).dur() == min(3), "lopende app telt tot nu");

        // ---------- totalen en keer-geopend (met gaatje samenvoegen) ----------
        List<Gebruik.Session> two = new ArrayList<>();
        two.add(new Gebruik.Session("a", t0, t0 + min(5)));
        two.add(new Gebruik.Session("a", t0 + min(5) + 1000, t0 + min(10) + 1000)); // zelfde app, gaatje 1 s → één keer
        two.add(new Gebruik.Session("a", t0 + min(40), t0 + min(45)));               // 30 min later → aparte keer
        check(Gebruik.counts(two, 30_000).get("a") == 2, "a twee keer geopend (gaatje samengevoegd)");
        check(Gebruik.totals(two).get("a") == min(15), "totaal a = 15 min");

        // ---------- ontgrendelingen: bouncer → pincode, symmetrisch, één keer ----------
        long m0 = at(2026, 10, 29, 8, 0);
        List<Gebruik.Ev> u = new ArrayList<>();
        u.add(new Gebruik.Ev(Gebruik.UNLOCKED, null, m0));
        u.add(new Gebruik.Ev(Gebruik.APP_START, "com.whatsapp", m0 + 1000));
        u.add(new Gebruik.Ev(Gebruik.SCREEN_OFF, null, m0 + min(2)));
        // bouncer 3 s vóór de ontgrendeling
        List<Gebruik.Unlock> b1 = Gebruik.unlocks(u, new ArrayList<>(), bnc(m0 - 3000), true, Gebruik.DEFAULT_WINDOW, Gebruik.DEFAULT_FAIL_WINDOW);
        check(b1.size() == 1 && b1.get(0).how.equals("pincode"), "bouncer vóór ontgrendeling → pincode");
        check(b1.get(0).apps.size() == 1 && b1.get(0).apps.get(0).pkg.equals("com.whatsapp"), "app in het blok");

        // bouncer NÁ de KEYGUARD_HIDDEN (symmetrisch venster) telt ook
        check(Gebruik.unlocks(u, new ArrayList<>(), bnc(m0 + 4000), true, Gebruik.DEFAULT_WINDOW, Gebruik.DEFAULT_FAIL_WINDOW).get(0).how.equals("pincode"), "bouncer kort ná de ontgrendeling → pincode");

        // geen bouncer, a11y aan → anders; a11y uit → onbekend
        check(Gebruik.unlocks(u, new ArrayList<>(), NONE, true, Gebruik.DEFAULT_WINDOW, Gebruik.DEFAULT_FAIL_WINDOW).get(0).how.equals("anders"), "geen bouncer, a11y aan → anders");
        check(Gebruik.unlocks(u, new ArrayList<>(), NONE, false, Gebruik.DEFAULT_WINDOW, Gebruik.DEFAULT_FAIL_WINDOW).get(0).how.equals("onbekend"), "geen bouncer, a11y uit → onbekend");

        // één bouncer telt maar één keer: twee ontgrendelingen, één bouncer
        List<Gebruik.Ev> u2 = new ArrayList<>();
        u2.add(new Gebruik.Ev(Gebruik.UNLOCKED, null, m0));
        u2.add(new Gebruik.Ev(Gebruik.UNLOCKED, null, m0 + min(10)));
        List<Gebruik.Unlock> b2 = Gebruik.unlocks(u2, new ArrayList<>(), bnc(m0 - 2000), true, Gebruik.DEFAULT_WINDOW, Gebruik.DEFAULT_FAIL_WINDOW);
        check(b2.size() == 2 && b2.get(0).how.equals("pincode") && b2.get(1).how.equals("anders"), "één bouncer → alleen de eerste pincode");

        // gelukte Apparaatbeheer-poging (komt ná mislukt) telt ook als pincode
        List<Gebruik.Pw> pw = new ArrayList<>();
        pw.add(new Gebruik.Pw(m0 - 6000, false));
        pw.add(new Gebruik.Pw(m0 - 2000, false));
        pw.add(new Gebruik.Pw(m0 - 500, true));
        List<Gebruik.Unlock> b3 = Gebruik.unlocks(u, pw, NONE, false, Gebruik.DEFAULT_WINDOW, Gebruik.DEFAULT_FAIL_WINDOW);
        check(b3.get(0).how.equals("pincode") && b3.get(0).fails == 2, "gelukte poging na 2× mis → pincode, fails 2");

        // mislukte pogingen langer dan 5 min geleden: eigen "mislukt"-blok, niet aan de ontgrendeling
        List<Gebruik.Pw> stale = new ArrayList<>();
        stale.add(new Gebruik.Pw(m0 - min(30), false));
        stale.add(new Gebruik.Pw(m0 - min(30) + 3000, false));
        List<Gebruik.Unlock> b4 = Gebruik.unlocks(u, stale, NONE, true, Gebruik.DEFAULT_WINDOW, Gebruik.DEFAULT_FAIL_WINDOW);
        check(b4.size() == 2, "oude mislukte pogingen: apart blok (" + b4.size() + ")");
        boolean hasFailBlock = false, unlockNoFails = false;
        for (Gebruik.Unlock x : b4) { if (x.how.equals("mislukt") && x.fails == 2) hasFailBlock = true; if (x.t == m0 && x.fails == 0) unlockNoFails = true; }
        check(hasFailBlock && unlockNoFails, "oude pogingen eigen blok ×2, ontgrendeling zonder fails");

        // drie mislukte pogingen zonder ontgrendeling → één "mislukt ×3"
        List<Gebruik.Pw> only = new ArrayList<>();
        long mf = at(2026, 10, 29, 23, 0);
        only.add(new Gebruik.Pw(mf, false)); only.add(new Gebruik.Pw(mf + 3000, false)); only.add(new Gebruik.Pw(mf + 6000, false));
        List<Gebruik.Unlock> b5 = Gebruik.unlocks(new ArrayList<>(), only, NONE, true, Gebruik.DEFAULT_WINDOW, Gebruik.DEFAULT_FAIL_WINDOW);
        check(b5.size() == 1 && b5.get(0).how.equals("mislukt") && b5.get(0).fails == 3, "3× mis zonder ontgrendeling → mislukt ×3");

        // ---------- schermtijd per uur, met wintertijd (25 okt 2026, klok 03:00→02:00) ----------
        long dstStart = at(2026, 10, 25, 1, 0);  // CEST, vóór het terugzetten
        long dstEnd = at(2026, 10, 25, 4, 0);    // CET, erna → 4 echte uren
        check(dstEnd - dstStart == 4L * 3600_000L, "25 okt: 01:00–04:00 is 4 echte uren (wintertijd)");
        List<Gebruik.Session> ds = new ArrayList<>();
        ds.add(new Gebruik.Session("a", dstStart, dstEnd));
        long[] h = Gebruik.hourly(ds, Gebruik.dayStart(dstStart, AMS), AMS);
        check(h[1] == 3600_000L && h[2] == 2L * 3600_000L && h[3] == 3600_000L, "uur 1=1u, uur 2=2u (dubbel), uur 3=1u");
        long sum = 0; for (long x : h) sum += x; check(sum == 4L * 3600_000L, "totaal per uur = 4 uur");

        // gewone dag: sessie over twee uren verdeeld
        long d2 = Gebruik.dayStart(at(2026, 10, 29, 0, 0), AMS);
        List<Gebruik.Session> hs = new ArrayList<>();
        hs.add(new Gebruik.Session("a", d2 + min(30), d2 + min(90)));
        long[] h2 = Gebruik.hourly(hs, d2, AMS);
        check(h2[0] == min(30) && h2[1] == min(30), "sessie over twee uren verdeeld");

        // per dag over middernacht geknipt
        List<Gebruik.Session> mid = new ArrayList<>();
        mid.add(new Gebruik.Session("a", d2 + min(23 * 60 + 30), d2 + min(24 * 60 + 30))); // 23:30–00:30
        Map<String, long[]> pd = Gebruik.perDay(mid, AMS);
        check(pd.get("2026-10-29")[0] == min(30) && pd.get("2026-10-30")[0] == min(30), "sessie over middernacht: 30 min op elke dag");

        // ---------- bewaartermijn ----------
        long now = at(2026, 10, 29, 10, 0);
        check(Gebruik.olderThan(at(2026, 9, 1, 10, 0), now, 30, AMS), "ruim een maand oud is ouder dan 30 dagen");
        check(!Gebruik.olderThan(at(2026, 10, 20, 10, 0), now, 30, AMS), "9 dagen oud blijft binnen 30 dagen");

        // ---------- snelheid: 60 dagen onder een seconde ----------
        List<Gebruik.Ev> big = new ArrayList<>();
        List<Gebruik.Pw> bigPw = new ArrayList<>();
        List<Long> bigBnc = new ArrayList<>();
        long base = at(2026, 8, 1, 0, 0);
        for (int d = 0; d < 60; d++) {
            long day = base + d * 86_400_000L;
            for (int s = 0; s < 80; s++) big.add(new Gebruik.Ev(Gebruik.APP_START, "app" + (s % 10), day + s * 600_000L));
            for (int unl = 0; unl < 12; unl++) { long t = day + unl * 7_000_000L; big.add(new Gebruik.Ev(Gebruik.UNLOCKED, null, t)); big.add(new Gebruik.Ev(Gebruik.SCREEN_OFF, null, t + 200_000L)); bigBnc.add(t - 2000); }
            for (int fl = 0; fl < 5; fl++) bigPw.add(new Gebruik.Pw(day + 50_000_000L + fl * 4000, false));
        }
        long startNs = System.nanoTime();
        List<Gebruik.Session> bs = Gebruik.sessions(big);
        List<Gebruik.Unlock> bu = Gebruik.unlocks(big, bigPw, bigBnc, true, Gebruik.DEFAULT_WINDOW, Gebruik.DEFAULT_FAIL_WINDOW);
        Gebruik.perDay(bs, AMS);
        long ms = (System.nanoTime() - startNs) / 1_000_000L;
        check(ms < 1000, "60 dagen in " + ms + " ms (<1000)");
        check(bu.size() >= 60 * 12, "alle ontgrendelingen verwerkt (" + bu.size() + ")");

        System.out.println(fails == 0 ? "ALLES GOED" : (fails + " MIS"));
        if (fails > 0) System.exit(1);
    }
}
