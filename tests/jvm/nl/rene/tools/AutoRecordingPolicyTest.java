package nl.rene.tools;

public final class AutoRecordingPolicyTest {
    private static int checks;
    private static void check(int actual, int expected, String message) {
        checks++; if (actual != expected) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(AutoRecordingPolicy.action("IDLE", "OFFHOOK", false, true, false, false, false), 0, "standaard uit");
        check(AutoRecordingPolicy.action("IDLE", "RINGING", true, true, false, false, false), 0, "niet starten bij rinkelen");
        check(AutoRecordingPolicy.action("RINGING", "IDLE", true, true, false, false, false), 0, "gemiste oproep niet opnemen");
        check(AutoRecordingPolicy.action("RINGING", "OFFHOOK", true, true, false, false, false), 1, "beantwoorde inkomende oproep");
        check(AutoRecordingPolicy.action("IDLE", "OFFHOOK", true, true, false, false, false), 1, "uitgaande oproep");
        check(AutoRecordingPolicy.action("OFFHOOK", "OFFHOOK", true, true, false, false, false), 0, "duplicaat of handmatig gestopt: niet herstarten");
        check(AutoRecordingPolicy.action("IDLE", "OFFHOOK", true, false, false, false, false), 0, "geen rechten: niet starten");
        check(AutoRecordingPolicy.action("IDLE", "OFFHOOK", true, true, true, false, false), 0, "lopende handmatige opname niet verdubbelen");
        check(AutoRecordingPolicy.action("IDLE", "OFFHOOK", true, true, true, true, false), 0, "lopende automatische opname niet verdubbelen");
        check(AutoRecordingPolicy.action("OFFHOOK", "IDLE", true, true, true, true, false), 2, "automatische opname stoppen na oproep");
        check(AutoRecordingPolicy.action("OFFHOOK", "IDLE", false, false, true, true, false), 2, "ook stoppen na ingetrokken toestemming");
        check(AutoRecordingPolicy.action("OFFHOOK", "IDLE", true, true, false, false, false), 0, "geen opname om te stoppen");
        check(AutoRecordingPolicy.action("IDLE", "IDLE", true, true, true, false, false), 0, "handmatige omgevingsopname behouden");
        check(AutoRecordingPolicy.action("OFFHOOK", "RINGING", true, true, true, true, false), 0, "wisselgesprek onderbreekt opname niet");
        check(AutoRecordingPolicy.action("IDLE", "unknown", true, true, false, false, false), 0, "ongeldige toestand");
        check(AutoRecordingPolicy.action("IDLE", null, true, true, false, false, false), 0, "ontbrekende toestand");
        check(AutoRecordingPolicy.action("", "OFFHOOK", true, true, false, false, false), 1, "eerste echte oproep");
        check(AutoRecordingPolicy.action("RINGING", "OFFHOOK", true, true, false, false, true), 0, "niet herstarten na stoppen tijdens wisselgesprek");
        check(AutoRecordingPolicy.action("IDLE", "OFFHOOK", true, true, false, false, true), 0, "geen tweede poging na een geblokkeerde start");
        System.out.println("Alle " + checks + " automatische-opname-tests geslaagd");
    }
}
