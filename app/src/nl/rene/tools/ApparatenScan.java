package nl.rene.tools;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apparaten: zoeken terwijl de app open is. Bluetooth (BLE-scan plus klassiek zoeken), wifi-netwerken in de buurt, en
 * het zoeken naar één apparaat (warmer/kouder). Alles op aanvraag en met een tijdslimiet: de scan stopt vanzelf.
 * Wat er gevonden wordt, blijft in het geheugen; alleen de geschiedenis (vaak in de buurt) en trackers worden bewaard
 * (ApparatenStore), en alleen als dat aan staat.
 *
 * Android beperkt zoeken: een app mag hooguit 5 keer per 30 seconden een BLE-scan starten (daarna geeft Android stil
 * niets meer), en wifi hooguit 4 keer per 2 minuten. Hier wordt een lopende scan daarom verlengd in plaats van opnieuw
 * gestart, en wifi niet vaker gevraagd dan mag.
 */
final class ApparatenScan {

    private ApparatenScan() { }

    /** Eén Bluetooth-adres zoals gezien. */
    static final class Dev {
        String addr = "", name = "", key = "";
        int rssi, tx = Integer.MIN_VALUE, cls = -1;
        double avg = Double.NaN;
        long first, last;
        boolean ble, classic, bonded;
        Apparaten.Info info = new Apparaten.Info();
        /** Afgevlakt signaal van de laatste ~10 seconden: [tijd, signaal x10]. */
        final ArrayDeque<long[]> hist = new ArrayDeque<>();
    }

    static final Object L = new Object();
    static final Map<String, Dev> devs = new HashMap<>();
    static boolean bleOn, classicOn;
    static long until, flushedAt;
    static String findKey = "", err = "";
    static Cb cb;
    static Rx rx;
    static Context app;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final long[] STARTS = new long[5];
    private static int startIdx;

    // ---------- toestemmingen ----------

    static boolean granted(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    /**
     * Bluetooth zoeken mag: vanaf Android 12 "Apparaten in de buurt" (zoeken + verbinden voor namen), en altijd de precieze
     * locatie. Zonder neverForLocation geeft Android vanaf versie 10 met alleen "geschatte locatie" geen resultaten.
     */
    static boolean btPerm(Context c) {
        if (Build.VERSION.SDK_INT >= 31) return granted(c, Manifest.permission.BLUETOOTH_SCAN) && granted(c, Manifest.permission.BLUETOOTH_CONNECT) && finePerm(c);
        return granted(c, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    /** Wifi-netwerken zien: locatie, en vanaf Android 13 ook "Apparaten in de buurt" (wifi). */
    static boolean wifiPerm(Context c) {
        return granted(c, Manifest.permission.ACCESS_FINE_LOCATION) && (Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.NEARBY_WIFI_DEVICES));
    }

    static boolean finePerm(Context c) { return granted(c, Manifest.permission.ACCESS_FINE_LOCATION); }
    static boolean locPerm(Context c) { return granted(c, Manifest.permission.ACCESS_FINE_LOCATION) || granted(c, Manifest.permission.ACCESS_COARSE_LOCATION); }
    static boolean bgPerm(Context c) { return Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION); }

    static boolean locOn(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 28) { LocationManager lm = c.getSystemService(LocationManager.class); return lm != null && lm.isLocationEnabled(); }
            return android.provider.Settings.Secure.getInt(c.getContentResolver(), android.provider.Settings.Secure.LOCATION_MODE) != 0;
        } catch (Throwable e) { return true; }
    }

    static BluetoothAdapter adapter(Context c) {
        try { BluetoothManager m = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE); return m == null ? null : m.getAdapter(); }
        catch (Throwable e) { return null; }
    }

    static boolean btOn(Context c) {
        BluetoothAdapter a = adapter(c);
        try { return a != null && a.isEnabled(); } catch (Throwable e) { return false; }
    }

    // ---------- BLE ----------

    /** Callback van de BLE-scan (benoemde klasse: geen anonieme klassen in deze app). */
    static final class Cb extends ScanCallback {
        @Override public void onScanResult(int type, ScanResult r) { seen(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) seen(r); }
        @Override public void onScanFailed(int code) {
            synchronized (L) { bleOn = false; err = code == SCAN_FAILED_ALREADY_STARTED ? "" : "Zoeken lukt nu niet (Android gaf code " + code + "). Probeer het zo opnieuw."; }
        }
    }

    /** Klassiek zoeken (oudere apparaten, auto's, sommige speakers): Android meldt elk gevonden apparaat. */
    static final class Rx extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            try {
                String a = i.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    short rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    BluetoothClass bc = i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS);
                    if (d != null) seenClassic(d, rssi == Short.MIN_VALUE ? -100 : rssi, bc == null ? -1 : bc.getDeviceClass());
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                    synchronized (L) { classicOn = false; }
                }
            } catch (Throwable e) { App.log(c, "APPARATEN", "klassiek: " + e); }
        }
    }

    private static String devName(BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? "" : Apparaten.clean(n); } catch (Throwable e) { return ""; }
    }

    private static boolean bonded(BluetoothDevice d) {
        try { return d.getBondState() == BluetoothDevice.BOND_BONDED; } catch (Throwable e) { return false; }
    }

    static void seen(ScanResult r) {
        try {
            BluetoothDevice bd = r.getDevice();
            if (bd == null) return;
            byte[] raw = r.getScanRecord() == null ? null : r.getScanRecord().getBytes();
            Apparaten.Adv a = Apparaten.parse(raw);
            String name = a.name.isEmpty() ? devName(bd) : a.name;
            int tx = a.txLevel;
            if (tx == Integer.MIN_VALUE && Build.VERSION.SDK_INT >= 26 && r.getTxPower() != ScanResult.TX_POWER_NOT_PRESENT) tx = r.getTxPower();
            update(bd.getAddress(), name, r.getRssi(), tx, a, -1, bonded(bd), true);
        } catch (Throwable e) { if (app != null) App.log(app, "APPARATEN", "resultaat: " + e); }
    }

    static void seenClassic(BluetoothDevice bd, int rssi, int cls) {
        update(bd.getAddress(), devName(bd), rssi, Integer.MIN_VALUE, null, cls, bonded(bd), false);
    }

    static void update(String addr, String name, int rssi, int tx, Apparaten.Adv a, int cls, boolean bonded, boolean ble) {
        if (addr == null) return;
        long now = System.currentTimeMillis();
        synchronized (L) {
            Dev d = devs.get(addr);
            if (d == null) {
                if (devs.size() >= 400) dropOldest();
                d = new Dev(); d.addr = addr.toUpperCase(java.util.Locale.ROOT); d.first = now; devs.put(addr, d);
            }
            if (name != null && !name.isEmpty()) d.name = name;
            if (cls >= 0) d.cls = cls;
            if (tx != Integer.MIN_VALUE) d.tx = tx;
            if (ble) d.ble = true; else d.classic = true;
            d.bonded = d.bonded || bonded;
            d.rssi = rssi; d.last = now;
            d.avg = Apparaten.smooth(d.avg, rssi, 0.3);
            d.hist.addLast(new long[]{now, Math.round(d.avg * 10)});
            while (!d.hist.isEmpty() && now - d.hist.peekFirst()[0] > 10_000) d.hist.removeFirst();
            if (a != null || d.info.type.isEmpty() || cls >= 0) d.info = Apparaten.classify(a != null ? a : new Apparaten.Adv(), d.name, d.cls);
            d.key = Apparaten.key(d.addr, d.bonded, d.name, a != null ? a.company() : -1, d.info.type);
        }
    }

    private static void dropOldest() {
        String old = null; long t = Long.MAX_VALUE;
        for (Map.Entry<String, Dev> e : devs.entrySet()) if (e.getValue().last < t) { t = e.getValue().last; old = e.getKey(); }
        if (old != null) devs.remove(old);
    }

    /** Is starten nu toegestaan (hooguit 5 keer per 30 seconden)? */
    private static boolean mayStart(long now) { return now - STARTS[startIdx] > 30_000; }

    /**
     * Zoeken starten of verlengen. seconds = hoe lang; find = sleutel van het apparaat dat je zoekt (of null);
     * classic = ook klassiek zoeken. Geeft "" of: "perm", "geen" (geen Bluetooth), "uit" (Bluetooth staat uit), "wacht".
     */
    static String start(Context c, int seconds, String find, boolean classic) {
        app = c.getApplicationContext();
        if (!btPerm(c)) return "perm";
        BluetoothAdapter ad = adapter(c);
        if (ad == null) return "geen";
        try { if (!ad.isEnabled()) return "uit"; } catch (Throwable e) { return "uit"; }
        long now = System.currentTimeMillis();
        synchronized (L) {
            until = Math.max(until, now + Math.max(5, Math.min(600, seconds)) * 1000L);
            if (find != null) findKey = find;
            err = "";
            if (!bleOn) {
                if (!mayStart(now)) { err = "Android staat maar 5 zoekrondes per 30 seconden toe. Even wachten, dan gaat het vanzelf verder."; H.postDelayed(ApparatenScan::retry, 31_000 - (now - STARTS[startIdx])); }
                else {
                    try {
                        BluetoothLeScanner s = ad.getBluetoothLeScanner();
                        if (s == null) return "uit";
                        ScanSettings.Builder b = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
                        if (Build.VERSION.SDK_INT >= 26 && ad.isLeExtendedAdvertisingSupported()) b.setLegacy(false);
                        cb = new Cb();
                        s.startScan(null, b.build(), cb);
                        bleOn = true;
                        STARTS[startIdx] = now; startIdx = (startIdx + 1) % STARTS.length;
                    } catch (Throwable e) { err = "Zoeken lukt niet: " + e.getMessage(); App.log(c, "APPARATEN", "ble starten: " + e); }
                }
            }
        }
        if (classic) startClassic(c, ad);
        H.removeCallbacks(TICK);
        H.postDelayed(TICK, 1000);
        return "";
    }

    private static final Runnable TICK = ApparatenScan::tickMain;

    private static void retry() {
        Context c = app;
        if (c != null && System.currentTimeMillis() < until) start(c, (int) Math.max(5, (until - System.currentTimeMillis()) / 1000), null, false);
    }

    private static void tickMain() {
        long now = System.currentTimeMillis();
        boolean stop;
        synchronized (L) { stop = now >= until; }
        if (stop) { stop(app); return; }
        if (now - flushedAt > 60_000 && app != null) { flushedAt = now; flush(app); }
        H.postDelayed(TICK, 1000);
    }

    private static void startClassic(Context c, BluetoothAdapter ad) {
        try {
            synchronized (L) {
                if (classicOn) return;
                if (rx == null) {
                    rx = new Rx();
                    IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                    f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                    if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED); else app.registerReceiver(rx, f); // systeemberichten komen toch
                }
                classicOn = ad.startDiscovery();
            }
        } catch (Throwable e) { App.log(c, "APPARATEN", "klassiek starten: " + e); }
    }

    /** Alles stoppen (scherm weg, of de tijd is om). Wat gevonden is, blijft zichtbaar. */
    static void stop(Context c) {
        H.removeCallbacks(TICK);
        BluetoothAdapter ad = c == null ? null : adapter(c);
        synchronized (L) {
            until = 0; findKey = "";
            try { if (bleOn && ad != null && ad.getBluetoothLeScanner() != null && cb != null) ad.getBluetoothLeScanner().stopScan(cb); } catch (Throwable ignored) { }
            try { if (classicOn && ad != null) ad.cancelDiscovery(); } catch (Throwable ignored) { }
            try { if (rx != null && app != null) app.unregisterReceiver(rx); } catch (Throwable ignored) { }
            rx = null; bleOn = false; classicOn = false; cb = null;
        }
        if (c != null) flush(c);
    }

    /** Niet meer zoeken naar één apparaat (alleen de lijst-scan loopt dan nog tot zijn tijd om is). */
    static void findStop() { synchronized (L) { findKey = ""; until = Math.min(until, System.currentTimeMillis() + 2000); } }

    /** Gevonden trackers en (als dat aan staat) de geschiedenis bijwerken, op een achtergrondthread. */
    static void flush(Context c) {
        final Context ac = c.getApplicationContext();
        final List<Dev> copy = new ArrayList<>();
        long since = System.currentTimeMillis() - 90_000;
        synchronized (L) { for (Dev d : devs.values()) if (d.last >= since) copy.add(snapshot(d)); }
        if (copy.isEmpty()) return;
        new Thread(() -> {
            try { ApparatenJob.process(ac, copy, ApparatenJob.location(ac, false)); }
            catch (Throwable e) { App.log(ac, "APPARATEN", "bijwerken: " + e); }
        }, "apparaten-flush").start();
    }

    static Dev snapshot(Dev d) {
        Dev x = new Dev();
        x.addr = d.addr; x.name = d.name; x.key = d.key; x.rssi = d.rssi; x.tx = d.tx; x.cls = d.cls; x.avg = d.avg;
        x.first = d.first; x.last = d.last; x.ble = d.ble; x.classic = d.classic; x.bonded = d.bonded; x.info = d.info;
        return x;
    }

    // ---------- naar de interface ----------

    static double dist(Dev d) { return Apparaten.distance((int) Math.round(Double.isNaN(d.avg) ? d.rssi : d.avg), d.tx, 2.2); }

    private static double avgAt(Dev d, long t) {
        double v = Double.NaN;
        for (long[] x : d.hist) { if (x[0] <= t) v = x[1] / 10.0; else break; }
        return v;
    }

    /** De lijst: per sleutel samengevoegd (wisselende adressen zonder naam als één groep), met bijnamen erbij. */
    static JSONObject btJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONObject known = ApparatenStore.known(c);
            long now = System.currentTimeMillis();
            Map<String, JSONObject> groups = new LinkedHashMap<>();
            Map<String, Dev> best = new HashMap<>();
            synchronized (L) {
                for (Dev d : devs.values()) {
                    if (now - d.last > 5 * 60_000L) continue;
                    JSONObject g = groups.get(d.key);
                    if (g == null) { g = new JSONObject().put("n", 0); groups.put(d.key, g); }
                    g.put("n", g.optInt("n") + 1);
                    Dev b = best.get(d.key);
                    if (b == null || d.last - b.last > 15_000 || (Math.abs(d.last - b.last) <= 15_000 && d.avg > b.avg)) best.put(d.key, d);
                }
                JSONArray list = new JSONArray();
                for (Map.Entry<String, JSONObject> e : groups.entrySet()) {
                    Dev d = best.get(e.getKey());
                    list.put(devJson(d, e.getValue().optInt("n"), known, now));
                }
                o.put("devices", list).put("scanning", bleOn || classicOn).put("classic", classicOn).put("until", until).put("err", err);
            }
            // Gekoppelde apparaten die nu niet in de buurt zijn: ook laten zien (om te zoeken)
            JSONArray paired = new JSONArray();
            BluetoothAdapter ad = adapter(c);
            if (ad != null && btPerm(c)) {
                try {
                    Set<BluetoothDevice> bs = ad.getBondedDevices();
                    if (bs != null) for (BluetoothDevice bd : bs) {
                        String key = "a:" + bd.getAddress().toUpperCase(java.util.Locale.ROOT);
                        if (groups.containsKey(key)) continue;
                        BluetoothClass bc = bd.getBluetoothClass();
                        String type = Apparaten.typeFromClass(bc == null ? -1 : bc.getDeviceClass());
                        if (type.isEmpty()) type = Apparaten.typeFromName(devName(bd));
                        JSONObject k = known.optJSONObject(key);
                        paired.put(new JSONObject().put("key", key).put("addr", bd.getAddress()).put("name", devName(bd)).put("type", type.isEmpty() ? "apparaat" : type)
                                .put("bonded", true).put("near", false).put("nick", k == null ? "" : k.optString("nick")).put("known", k != null));
                    }
                } catch (Throwable ignored) { }
            }
            o.put("paired", paired);
        } catch (Throwable e) { App.log(c, "APPARATEN", "lijst: " + e); }
        return o;
    }

    static JSONObject devJson(Dev d, int n, JSONObject known, long now) throws Exception {
        JSONObject k = known.optJSONObject(d.key);
        JSONObject j = new JSONObject().put("key", d.key).put("addr", d.addr).put("name", d.name).put("type", d.info.type).put("maker", d.info.maker)
                .put("rssi", d.rssi).put("avg", Double.isNaN(d.avg) ? d.rssi : Math.round(d.avg * 10) / 10.0).put("dist", Math.round(dist(d) * 10) / 10.0)
                .put("ago", Math.max(0, (now - d.last) / 1000)).put("bonded", d.bonded).put("ble", d.ble).put("classic", d.classic)
                .put("rotating", Apparaten.rotating(d.addr) && !d.bonded).put("n", n).put("stable", Apparaten.stableKey(d.key)).put("near", true)
                .put("nick", k == null ? "" : k.optString("nick")).put("known", k != null);
        if (d.info.tracker != null) j.put("tracker", new JSONObject().put("kind", d.info.tracker.kind).put("label", d.info.tracker.label)
                .put("sep", d.info.tracker.separated == null ? JSONObject.NULL : d.info.tracker.separated).put("warn", d.info.tracker.warn));
        return j;
    }

    /** Zoeken (warmer/kouder): het sterkste adres met deze sleutel, afgevlakt, met de richting van de laatste 3 seconden. */
    static JSONObject findJson(String key) {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            Dev b = null;
            synchronized (L) {
                // Ook op adres: een tracker uit de melding heet "a:ADRES", ook als zijn sleutel een groep is
                for (Dev d : devs.values()) if ((key.equals(d.key) || key.equals("a:" + d.addr)) && (b == null || d.last > b.last + 5000 || (d.last + 5000 >= b.last && d.avg > b.avg))) b = d;
                o.put("scanning", bleOn).put("err", err);
                if (b == null) return o.put("found", false);
                double before = avgAt(b, now - 3000);
                o.put("found", true).put("rssi", b.rssi).put("avg", Math.round(b.avg * 10) / 10.0).put("meter", Apparaten.meter(b.avg))
                        .put("trend", Apparaten.trend(b.avg, before)).put("dist", Math.round(dist(b) * 10) / 10.0).put("ago", Math.max(0, now - b.last))
                        .put("name", b.name).put("type", b.info.type);
            }
        } catch (Throwable ignored) { }
        return o;
    }

    static void forget() { synchronized (L) { devs.clear(); } }

    // ---------- wifi ----------

    private static final long[] WSTARTS = new long[4];
    private static int wIdx;
    private static long wLast;

    /** Wifi opnieuw laten zoeken, als Android dat nu toestaat (4 keer per 2 minuten); anders de laatst gevonden netwerken. */
    static JSONObject wifiScan(Context c) {
        JSONObject o = new JSONObject();
        try {
            if (!wifiPerm(c)) return o.put("error", "perm");
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return o.put("error", "geen");
            if (!wm.isWifiEnabled() && !wm.isScanAlwaysAvailable()) return o.put("error", "uit");
            long now = SystemClock.elapsedRealtime();
            long oldest = WSTARTS[wIdx];
            if (now - wLast < 20_000) return o.put("started", false).put("wait", (20_000 - (now - wLast)) / 1000);
            if (oldest != 0 && now - oldest < 120_000) return o.put("started", false).put("wait", (120_000 - (now - oldest)) / 1000);
            @SuppressWarnings("deprecation") boolean ok = wm.startScan();
            if (ok) { WSTARTS[wIdx] = now; wIdx = (wIdx + 1) % WSTARTS.length; wLast = now; }
            o.put("started", ok);
            if (!ok) o.put("wait", 30);
        } catch (Throwable e) { try { o.put("error", "Zoeken lukt niet"); } catch (Exception ignored) { } }
        return o;
    }

    @SuppressWarnings("deprecation")
    static JSONObject wifiJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            if (!wifiPerm(c)) return o.put("error", "perm");
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return o.put("error", "geen");
            List<android.net.wifi.ScanResult> rs = wm.getScanResults();
            WifiInfo wi = wm.getConnectionInfo();
            String conn = wi == null || wi.getBSSID() == null ? "" : wi.getBSSID().toLowerCase(java.util.Locale.ROOT);
            JSONArray a = new JSONArray();
            List<Integer> fs = new ArrayList<>(), ls = new ArrayList<>();
            long nowUs = SystemClock.elapsedRealtime() * 1000L, newest = Long.MAX_VALUE;
            if (rs != null) for (android.net.wifi.ScanResult r : rs) {
                String ssid = r.SSID == null ? "" : r.SSID;
                String sec = Apparaten.security(r.capabilities);
                String bssid = r.BSSID == null ? "" : r.BSSID.toLowerCase(java.util.Locale.ROOT);
                long age = Math.max(0, (nowUs - r.timestamp) / 1_000_000L);
                newest = Math.min(newest, age);
                a.put(new JSONObject().put("ssid", Apparaten.clean(ssid)).put("bssid", bssid).put("rssi", r.level).put("freq", r.frequency)
                        .put("ch", Apparaten.channel(r.frequency)).put("band", Apparaten.band(r.frequency)).put("width", Apparaten.width(r.channelWidth))
                        .put("sec", sec).put("unsafe", Apparaten.unsafe(sec)).put("connected", !conn.isEmpty() && conn.equals(bssid))
                        .put("maker", Apparaten.ouiMaker(bssid)).put("local", Apparaten.localMac(bssid)).put("age", age));
                fs.add(r.frequency); ls.add(r.level);
            }
            int[] f = new int[fs.size()], l = new int[ls.size()];
            for (int i = 0; i < f.length; i++) { f[i] = fs.get(i); l[i] = ls.get(i); }
            JSONObject crowd = new JSONObject();
            for (String band : new String[]{"2,4 GHz", "5 GHz", "6 GHz"}) {
                Map<Integer, Double> m = Apparaten.crowding(f, l, band);
                if (m.isEmpty()) continue;
                JSONObject x = new JSONObject();
                for (Map.Entry<Integer, Double> e : m.entrySet()) x.put(String.valueOf(e.getKey()), e.getValue());
                crowd.put(band, x);
                if ("2,4 GHz".equals(band)) o.put("best24", Apparaten.quietest24(m));
            }
            o.put("networks", a).put("crowd", crowd).put("newest", newest == Long.MAX_VALUE ? -1 : newest).put("wifiOn", wm.isWifiEnabled());
        } catch (Throwable e) { App.log(c, "APPARATEN", "wifi: " + e); }
        return o;
    }

    /** De naam van het wifi-netwerk waarmee je nu verbonden bent ("" als dat niet te zien is). */
    @SuppressWarnings("deprecation")
    static String ssid(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            String s = wi == null ? null : wi.getSSID();
            if (s == null || s.equals(WifiManager.UNKNOWN_SSID) || s.equals("<unknown ssid>")) return "";
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
            return Apparaten.clean(s);
        } catch (Throwable e) { return ""; }
    }

}
