package nl.rene.tools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Apparaten op de achtergrond (alleen als je dat aanzet). Twee zuinige mechanismen, geen dienst op de voorgrond:
 *  - JobScheduler: elke 15, 30 of 60 minuten (Android schuift dat in Doze en bij batterijbesparing op naar een
 *    onderhoudsmoment) ~10 seconden een BLE-scan, de plek (grof: netwerklocatie) en de wifi-netwerken die Android toch al
 *    zag. Met het scherm uit laat Android alleen gerichte scans toe: dan zoekt hij naar trackers en je gekoppelde apparaten.
 *  - Vanaf Android 8: een BLE-scan met een PendingIntent die Android zelf uitvoert (ook als de app niet draait), met filters
 *    op tracker-handtekeningen die uit de buurt van hun eigenaar zijn (AirTag, SmartTag, Tile, Chipolo, Google, DULT).
 *    Zo valt een tracker die met je meereist ook tussen twee rondes op, zonder dat de app wakker hoeft te blijven.
 * Wifi zoeken op de achtergrond kan nauwelijks (Android staat 1 keer per 30 minuten toe): hier wordt alleen gelezen wat
 * Android al gevonden had.
 *
 * De trackerwaarschuwing: een onbekende tracker die op ≥ 3 plekken ≥ 500 m uit elkaar gezien is, over ≥ 30 minuten
 * (Apparaten.follows), geeft één melding op het kanaal "Apparaten: onbekende tracker".
 */
public class ApparatenJob extends JobService {

    static final int JOB_ID = 4501;
    static final String CHANNEL = "apparaten-tracker";
    static final String ACTION_SCAN = "nl.rene.tools.APPARATEN_SCAN", ACTION_IGNORE = "nl.rene.tools.APPARATEN_NEGEREN";
    /** Eigen reeksen (elders in de app niet gebruikt). */
    static final int NOTIF_BASE = 450_000, REQ_SCAN = 451_000, REQ_OPEN = 452_000, REQ_ACT = 456_000;

    @Override public boolean onStartJob(JobParameters p) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try { runOnce(c); }
            catch (Throwable e) { App.log(c, "APPARATEN", "achtergrond: " + e); }
            finally { try { jobFinished(p, false); } catch (Throwable ignored) { } }
        }, "apparaten-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return false; }

    // ---------- inplannen ----------

    /** Mag het op de achtergrond? Aan gezet, Bluetooth-toestemming, en vanaf Android 10 "locatie altijd". */
    static boolean bgReady(Context c) { return ApparatenStore.bg(c) && ApparatenScan.btPerm(c) && ApparatenScan.bgPerm(c); }

    /** Staat de tracker-scan van Android (in dit proces) al aan? Bij het openen van de app dan niet opnieuw starten. */
    static volatile boolean passiveOn;

    /** Na een wijziging en na elke ronde: inplannen of juist weghalen, en de tracker-scan opnieuw starten. */
    static void ensure(Context c) { ensure(c, true); }

    /** Bij het openen van de app (restart = false): alleen inplannen, de tracker-scan alleen starten als hij nog niet loopt. */
    static void ensure(Context c, boolean restart) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!bgReady(c)) { js.cancel(JOB_ID); passive(c, false); return; }
            long every = Math.max(15, ApparatenStore.prefs(c).getInt("every", 30)) * 60_000L;
            JobInfo have = js.getPendingJob(JOB_ID);
            if (have == null || have.getIntervalMillis() != every) {
                js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, ApparatenJob.class)).setPeriodic(every).setPersisted(true).build());
            }
            boolean on = ApparatenStore.trackersOn(c);
            if (restart || on != passiveOn) passive(c, on);
        } catch (Throwable e) { App.log(c, "APPARATEN", "inplannen: " + e); }
    }

    private static PendingIntent scanIntent(Context c) {
        Intent i = new Intent(c, Ontvanger.class).setAction(ACTION_SCAN);
        // Android vult de resultaten zelf in: daarom veranderbaar (vanaf Android 12 verplicht aan te geven)
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, REQ_SCAN, i, f);
    }

    /** De zuinige tracker-scan van Android zelf (vanaf Android 8) aan of uit. */
    static void passive(Context c, boolean on) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            BluetoothAdapter ad = ApparatenScan.adapter(c);
            if (ad == null || !ad.isEnabled() || !ApparatenScan.btPerm(c)) return;
            BluetoothLeScanner s = ad.getBluetoothLeScanner();
            if (s == null) return;
            PendingIntent pi = scanIntent(c);
            try { s.stopScan(pi); } catch (Throwable ignored) { }
            passiveOn = false;
            if (!on) return;
            // Zuinig: de Bluetooth-chip verzamelt zelf (5 minuten per keer), of meldt een tracker alleen de eerste keer.
            // Kan de chip geen van beide, dan geen doorlopende scan (een AirTag in huis zou de app anders elke paar
            // seconden wekken); dan doet alleen de ronde van de job het. Herhaalde waarnemingen komen altijd uit de job.
            ScanSettings.Builder b = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER);
            if (ad.isOffloadedScanBatchingSupported()) b.setReportDelay(5 * 60_000L);
            else if (ad.isOffloadedFilteringSupported()) b.setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH);
            else return;
            s.startScan(trackerFilters(), b.build(), pi);
            passiveOn = true;
        } catch (Throwable e) { App.log(c, "APPARATEN", "tracker-scan: " + e); }
    }

    /** Filters op tracker-handtekeningen (zie Apparaten.tracker): alleen die uit de buurt van hun eigenaar waar dat te zien is. */
    static List<ScanFilter> trackerFilters() {
        List<ScanFilter> f = new ArrayList<>();
        f.add(new ScanFilter.Builder().setManufacturerData(0x004C, new byte[]{0x12, 0x19}, new byte[]{(byte) 0xFF, (byte) 0xFF}).build());
        f.add(new ScanFilter.Builder().setServiceData(uuid(0xFD5A), new byte[0]).build());
        f.add(new ScanFilter.Builder().setServiceUuid(uuid(0xFEED)).build());
        f.add(new ScanFilter.Builder().setServiceUuid(uuid(0xFEEC)).build());
        f.add(new ScanFilter.Builder().setServiceUuid(uuid(0xFE33)).build());
        f.add(new ScanFilter.Builder().setServiceData(uuid(0xFEAA), new byte[]{0x41}, new byte[]{(byte) 0xFF}).build());
        f.add(new ScanFilter.Builder().setServiceUuid(uuid(0xFCB2)).build());
        return f;
    }

    static ParcelUuid uuid(int u16) { return ParcelUuid.fromString(String.format(java.util.Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", u16)); }

    // ---------- één ronde ----------

    /** Verzamelt resultaten van de scan in de ronde (benoemde klasse). */
    static final class JobCb extends ScanCallback {
        final Map<String, ApparatenScan.Dev> found = new HashMap<>();
        @Override public void onScanResult(int type, ScanResult r) { add(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) add(r); }
        void add(ScanResult r) {
            ApparatenScan.Dev d = toDev(r);
            if (d == null) return;
            synchronized (found) {
                ApparatenScan.Dev old = found.get(d.addr);
                if (old != null) d.avg = Apparaten.smooth(old.avg, d.rssi, 0.3);
                found.put(d.addr, d);
            }
        }
    }

    static ApparatenScan.Dev toDev(ScanResult r) {
        try {
            BluetoothDevice bd = r.getDevice();
            if (bd == null) return null;
            Apparaten.Adv a = Apparaten.parse(r.getScanRecord() == null ? null : r.getScanRecord().getBytes());
            ApparatenScan.Dev d = new ApparatenScan.Dev();
            d.addr = bd.getAddress().toUpperCase(java.util.Locale.ROOT);
            String n = a.name;
            if (n.isEmpty()) try { n = bd.getName() == null ? "" : Apparaten.clean(bd.getName()); } catch (Throwable ignored) { }
            d.name = n;
            try { d.bonded = bd.getBondState() == BluetoothDevice.BOND_BONDED; } catch (Throwable ignored) { }
            d.rssi = r.getRssi(); d.avg = r.getRssi(); d.tx = a.txLevel;
            d.first = d.last = System.currentTimeMillis(); d.ble = true;
            d.info = Apparaten.classify(a, d.name, -1);
            d.key = Apparaten.key(d.addr, d.bonded, d.name, a.company(), d.info.type);
            return d;
        } catch (Throwable e) { return null; }
    }

    static void runOnce(Context c) throws Exception {
        ApparatenStore.histPrune(c);
        if (!bgReady(c)) { ensure(c); return; }
        List<ApparatenScan.Dev> devs = new ArrayList<>();
        BluetoothAdapter ad = ApparatenScan.adapter(c);
        if (ad != null && ad.isEnabled() && ad.getBluetoothLeScanner() != null) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            boolean screen = pm != null && pm.isInteractive();
            List<ScanFilter> filters = null;
            if (!screen) {
                // Scherm uit: Android geeft alleen resultaten van gerichte scans. Trackers, plus je gekoppelde en eigen apparaten.
                filters = trackerFilters();
                try {
                    Set<BluetoothDevice> bs = ad.getBondedDevices();
                    if (bs != null) for (BluetoothDevice bd : bs) if (filters.size() < 30) filters.add(new ScanFilter.Builder().setDeviceAddress(bd.getAddress()).build());
                } catch (Throwable ignored) { }
                JSONObject known = ApparatenStore.known(c);
                for (Iterator<String> it = known.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (k.startsWith("a:") && BluetoothAdapter.checkBluetoothAddress(k.substring(2)) && filters.size() < 30) filters.add(new ScanFilter.Builder().setDeviceAddress(k.substring(2)).build());
                }
            }
            JobCb cb = new JobCb();
            BluetoothLeScanner s = ad.getBluetoothLeScanner();
            try {
                s.startScan(filters, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb);
                Thread.sleep(10_000);
            } finally { try { s.stopScan(cb); } catch (Throwable ignored) { } }
            synchronized (cb.found) { devs.addAll(cb.found.values()); }
        }
        process(c, devs, location(c, true));
        ensure(c);
    }

    // ---------- plek ----------

    /** Wacht op één plek (benoemde klasse, met alle methodes: ook voor Android 7 tot en met 10). */
    static final class OneFix implements LocationListener {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Location loc;
        @Override public void onLocationChanged(Location l) { loc = l; latch.countDown(); }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { latch.countDown(); }
    }

    /**
     * Grove plek: de laatst bekende van de telefoon (netwerk/passief), en met fresh=true een nieuwe netwerklocatie als
     * die ouder is dan 4 minuten of onnauwkeurig. Geen GPS. Geeft {lat, lon, tijd, nauwkeurigheid}. null = onbekend (geen toestemming, locatie uit).
     */
    @SuppressWarnings({"deprecation", "MissingPermission"})
    static double[] location(Context c, boolean fresh) {
        if (!ApparatenScan.locPerm(c)) return null;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER}) {
                try { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } catch (Throwable ignored) { }
            }
            boolean old = best == null || System.currentTimeMillis() - best.getTime() > 4 * 60_000L || !best.hasAccuracy() || best.getAccuracy() > Apparaten.FIX_MAX_ACC;
            if (fresh && old && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) && Looper.myLooper() != Looper.getMainLooper()) {
                OneFix f = new OneFix();
                try {
                    lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, f, Looper.getMainLooper());
                    f.latch.await(20, TimeUnit.SECONDS);
                } catch (Throwable ignored) { }
                finally { try { lm.removeUpdates(f); } catch (Throwable ignored) { } }
                if (f.loc != null) best = f.loc;
            }
            if (best == null || System.currentTimeMillis() - best.getTime() > 60 * 60_000L) return null; // te oud om iets te zeggen
            // lat, lon, tijd van de plaatsbepaling, nauwkeurigheid (m; onbekend = heel onnauwkeurig)
            return new double[]{best.getLatitude(), best.getLongitude(), best.getTime(), best.hasAccuracy() ? best.getAccuracy() : 9999};
        } catch (Throwable e) { return null; }
    }

    // ---------- verwerken: geschiedenis en trackers ----------

    static synchronized void process(Context c, List<ApparatenScan.Dev> devs, double[] loc) {
        long now = System.currentTimeMillis();
        double lat = loc == null ? Double.NaN : loc[0], lon = loc == null ? Double.NaN : loc[1];
        boolean home = ApparatenStore.atHome(c, lat, lon, ApparatenScan.ssid(c));
        String place = home ? "thuis" : Apparaten.cell(lat, lon);
        JSONObject known = ApparatenStore.known(c);
        if (ApparatenStore.histOn(c) && !(home && ApparatenStore.prefs(c).getBoolean("away", false))) {
            List<ApparatenStore.Seen> seen = new ArrayList<>();
            for (ApparatenScan.Dev d : devs) {
                if (!Apparaten.stableKey(d.key)) continue;
                // Alleen wat terug te herkennen is: een naam, gekoppeld of van jou (wisselende adressen zonder naam niet)
                if (d.name.isEmpty() && !d.bonded && !known.has(d.key)) continue;
                ApparatenStore.Seen s = new ApparatenStore.Seen();
                s.key = d.key; s.name = d.name; s.type = d.info.type; s.maker = d.info.maker;
                seen.add(s);
            }
            ApparatenStore.histRecord(c, seen, now, place);
        }
        if (ApparatenStore.trackersOn(c)) trackers(c, devs, loc, home, now, known);
    }

    /**
     * Tracker-waarnemingen bijschrijven en kijken of er een meereist. Een waarneming krijgt een plek alleen met een verse
     * en nauwkeurige plaatsbepaling (Apparaten.usableFix), met de tijd van die plaatsbepaling, hooguit één per 4 minuten,
     * en niet thuis (daar liggen de trackers van huisgenoten).
     */
    static void trackers(Context c, List<ApparatenScan.Dev> devs, double[] loc, boolean home, long now, JSONObject known) {
        boolean fix = loc != null && loc.length >= 4 && !home && Apparaten.usableFix((long) loc[2], loc[3], now);
        boolean any = false;
        for (ApparatenScan.Dev d : devs) if (d.info.tracker != null && d.info.tracker.warn) { any = true; break; }
        if (!any) return;
        synchronized (ApparatenStore.class) {
            JSONObject all = ApparatenStore.trackers(c);
            try {
                for (ApparatenScan.Dev d : devs) {
                    if (d.info.tracker == null || !d.info.tracker.warn) continue;
                    if (known.has(d.key) || known.has("a:" + d.addr)) continue; // van jou
                    String id = d.addr;
                    JSONObject t = all.optJSONObject(id);
                    if (t == null) { t = new JSONObject().put("kind", d.info.tracker.kind).put("label", d.info.tracker.label).put("first", now).put("s", new JSONArray()); all.put(id, t); }
                    t.put("last", now).put("rssi", d.rssi);
                    if (!t.optString("status").isEmpty()) continue;
                    JSONArray s = t.optJSONArray("s");
                    if (s == null) { s = new JSONArray(); t.put("s", s); }
                    JSONArray lastS = s.length() > 0 ? s.optJSONArray(s.length() - 1) : null;
                    if (fix && (lastS == null || (long) loc[2] - lastS.optLong(0) >= 4 * 60_000L) && s.length() < 200)
                        s.put(new JSONArray().put((long) loc[2]).put(Apparaten.round3(loc[0])).put(Apparaten.round3(loc[1])).put(Math.round(loc[3])));
                    Apparaten.Follow f = Apparaten.follows(ApparatenStore.sightings(t), now);
                    t.put("places", f.places).put("minutes", f.minutes);
                    // Alleen als de melding er echt kwam: staan meldingen uit, dan kan hij later alsnog komen
                    if (f.follows && t.optLong("warned") == 0 && warn(c, id, t)) t.put("warned", now);
                }
                ApparatenStore.trackersPrune(all, now);
            } catch (Exception e) { App.log(c, "APPARATEN", "trackers: " + e); }
            ApparatenStore.trackersSave(c, all);
        }
    }

    /** "Dit is van mij" of "negeren" (vanuit de app of de melding). */
    static void trackerStatus(Context c, String id, String status) {
        if (id == null || !id.matches("[0-9A-F:]{17}")) return;
        synchronized (ApparatenStore.class) {
            JSONObject all = ApparatenStore.trackers(c);
            JSONObject t = all.optJSONObject(id);
            try {
                if (t == null) { t = new JSONObject().put("kind", "").put("label", "Tracker").put("first", System.currentTimeMillis()).put("last", System.currentTimeMillis()); all.put(id, t); }
                t.put("status", status).put("s", new JSONArray());
            } catch (Exception ignored) { }
            ApparatenStore.trackersSave(c, all);
        }
        if ("mine".equals(status)) ApparatenStore.setKnown(c, "a:" + id, "Mijn tracker", "tracker", "");
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifId(id));
    }

    static int notifId(String id) { return NOTIF_BASE + (id.hashCode() & 0xFFF); }

    // ---------- de melding ----------

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Apparaten: onbekende tracker", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Als een onbekende tracker (AirTag, SmartTag, Tile …) een tijd met je meereist");
            nm.createNotificationChannel(ch);
        }
    }

    private static PendingIntent open(Context c, String what, String id, int req) {
        Intent i = new Intent(c, MainActivity.class).putExtra("open", "apparaten:" + what + ":" + id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.fromParts("apparaten", what + "/" + id, null));
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** De waarschuwing tonen. false = kon niet (meldingen uit of geen toestemming). */
    static boolean warn(Context c, String id, JSONObject t) {
        try {
            if (NotifCenter.muted(c) || !NotifCenter.permissionOk(c)) return false;
            channel(c);
            String label = t.optString("label", "tracker");
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, CHANNEL)) : new Notification.Builder(c);
            int n = id.hashCode() & 0xFFF;
            String text = "Al " + t.optLong("minutes") + " minuten in je buurt, op " + t.optInt("places") + " plekken. Tik om hem te zoeken en te lezen wat je kunt doen.";
            b.setSmallIcon(R.drawable.ic_notif).setContentTitle("Onbekende " + label + " reist met je mee").setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true).setContentIntent(open(c, "tracker", id, REQ_OPEN + n)).setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(Notification.PRIORITY_HIGH).setVisibility(Notification.VISIBILITY_PRIVATE);
            b.addAction(new Notification.Action.Builder(null, "Zoeken", open(c, "zoek", id, REQ_OPEN + 0x1000 + n)).build());
            Intent ig = new Intent(c, Ontvanger.class).setAction(ACTION_IGNORE).putExtra("id", id).putExtra("tok", App.token(c))
                    .setData(Uri.fromParts("apparaten", "negeren/" + id, null));
            b.addAction(new Notification.Action.Builder(null, "Negeren", PendingIntent.getBroadcast(c, REQ_ACT + n, ig, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
            NotifCenter.post(c, notifId(id), b.build());
            return true;
        } catch (Throwable e) { App.log(c, "APPARATEN", "melding: " + e); return false; }
    }

    // ---------- ontvanger: resultaten van de tracker-scan en "Negeren" ----------

    /** Hooguit eens per 4 minuten per tracker iets doen (de scan kan vaak melden). */
    static final Map<String, Long> LAST = new HashMap<>();

    public static class Ontvanger extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i == null ? null : i.getAction();
            final Context ac = c.getApplicationContext();
            if (ACTION_IGNORE.equals(a)) {
                if (!App.tokenOk(ac, i)) return; // alleen vanuit onze eigen melding
                final String id = i.getStringExtra("id");
                final PendingResult pr = goAsync();
                new Thread(() -> { try { trackerStatus(ac, id, "ignored"); } catch (Throwable e) { App.log(ac, "APPARATEN", "negeren: " + e); } finally { pr.finish(); } }, "apparaten-negeren").start();
                return;
            }
            if (!ACTION_SCAN.equals(a) || !App.unlocked(ac)) return;
            List<ScanResult> rs;
            try { rs = i.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT); } catch (Throwable e) { return; }
            if (rs == null || rs.isEmpty()) return;
            final List<ApparatenScan.Dev> devs = new ArrayList<>();
            long now = System.currentTimeMillis();
            synchronized (LAST) {
                for (ScanResult r : rs) {
                    ApparatenScan.Dev d = toDev(r);
                    if (d == null || d.info.tracker == null || !d.info.tracker.warn) continue;
                    Long l = LAST.get(d.addr);
                    if (l != null && now - l < 4 * 60_000L) continue;
                    LAST.put(d.addr, now);
                    devs.add(d);
                }
                if (LAST.size() > 200) LAST.clear();
            }
            if (devs.isEmpty() || !ApparatenStore.trackersOn(ac) || !ApparatenStore.bg(ac)) return;
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    double[] loc = location(ac, false);
                    boolean home = loc != null && ApparatenStore.atHome(ac, loc[0], loc[1], ApparatenScan.ssid(ac));
                    trackers(ac, devs, loc, home, System.currentTimeMillis(), ApparatenStore.known(ac));
                } catch (Throwable e) { App.log(ac, "APPARATEN", "tracker ontvangen: " + e); }
                finally { pr.finish(); }
            }, "apparaten-tracker").start();
        }
    }

    /** Overzicht van de trackers voor de interface: wat nu in de buurt is en wat bewaard is (met hoe ver het meereisde). */
    static JSONObject trackersJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            ApparatenStore.trackersPruneNow(c);
            JSONObject all = ApparatenStore.trackers(c);
            JSONArray a = new JSONArray();
            long now = System.currentTimeMillis();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject t = all.optJSONObject(id);
                if (t == null) continue;
                Apparaten.Follow f = Apparaten.follows(ApparatenStore.sightings(t), now);
                a.put(new JSONObject().put("id", id).put("key", "a:" + id).put("kind", t.optString("kind")).put("label", t.optString("label"))
                        .put("first", t.optLong("first")).put("last", t.optLong("last")).put("places", f.places).put("minutes", f.minutes)
                        .put("follows", f.follows).put("warned", t.optLong("warned")).put("status", t.optString("status")).put("rssi", t.optInt("rssi")));
            }
            o.put("list", a);
            o.put("bg", ApparatenStore.bg(c)).put("on", ApparatenStore.trackersOn(c)).put("passive", Build.VERSION.SDK_INT >= 26);
            o.put("perm", Build.VERSION.SDK_INT < 33 || ApparatenScan.granted(c, Manifest.permission.POST_NOTIFICATIONS));
        } catch (Throwable e) { App.log(c, "APPARATEN", "trackers lijst: " + e); }
        return o;
    }
}
