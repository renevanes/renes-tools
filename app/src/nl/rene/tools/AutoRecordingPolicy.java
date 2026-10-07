package nl.rene.tools;

/** Overgangen van mobiele oproepen; bellen/rinkelen alleen is nog geen opname. */
final class AutoRecordingPolicy {
    static final int NONE = 0, START = 1, STOP = 2;
    private AutoRecordingPolicy() { }
    static boolean valid(String state) { return "IDLE".equals(state) || "RINGING".equals(state) || "OFFHOOK".equals(state); }
    static int action(String before, String after, boolean enabled, boolean ready, boolean busy, boolean automatic, boolean attempted) {
        if (!valid(after)) return NONE;
        if ("IDLE".equals(after)) return busy && automatic ? STOP : NONE;
        if ("OFFHOOK".equals(after) && !"OFFHOOK".equals(before) && enabled && ready && !busy && !attempted) return START;
        return NONE;
    }
}
