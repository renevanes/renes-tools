package nl.rene.tools;

import java.util.concurrent.atomic.AtomicBoolean;

/** Doorwerken telt het werk dat door moet lopen; elk begin hoort bij precies één einde, ook na een fout. */
public final class DoorwerkenTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        check(Doorwerken.bezig() == 0, "niets bezig");
        Doorwerken.Klaar a = Doorwerken.begin("proef"), b = Doorwerken.begin("proef");
        check(Doorwerken.bezig() == 2, "telt op");
        a.close(); a.close();
        check(Doorwerken.bezig() == 1, "twee keer sluiten telt één keer");
        b.close();
        check(Doorwerken.bezig() == 0, "alles klaar");

        // door(): het werk draait, telt mee zolang het loopt, en een fout (ook een Error) laat de teller niet hangen.
        AtomicBoolean binnen = new AtomicBoolean();
        Doorwerken.door("proef", () -> binnen.set(Doorwerken.bezig() == 1)).run();
        check(binnen.get() && Doorwerken.bezig() == 0, "telt tijdens het werk");
        Thread t = new Thread(Doorwerken.door("proef", () -> { throw new OutOfMemoryError("proef"); }), "proef");
        AtomicBoolean doorgegeven = new AtomicBoolean();
        t.setUncaughtExceptionHandler((th, e) -> doorgegeven.set(true));
        t.start(); t.join(5000);
        check(!doorgegeven.get() && Doorwerken.bezig() == 0, "een fout stopt alleen het werk en telt af");

        // Voortgang houdt de app wakker tot close (ook als er nooit een stap kwam).
        Voortgang v = new Voortgang(json -> { }, "proef", 1, () -> 0L);
        check(Doorwerken.bezig() == 0, "een Voortgang zonder van() telt niet");
        v.close();
        System.out.println("DoorwerkenTest ok");
    }
}
