package nl.rene.tools;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * Brugfuncties voor Apparaten (Android.apXxx): Bluetooth, wifi-netwerken, apparaten op je eigen wifi, trackers,
 * bekende apparaten en de geschiedenis. Tussen FeatureBridge en CheckinBridge. Het zoeken zelf staat in ApparatenScan
 * en ApparatenLan, de achtergrond en de trackerwaarschuwing in ApparatenJob, de opslag in ApparatenStore en de
 * rekenregels (getest) in Apparaten. Er gaat niets naar internet.
 */
public class ApparatenBridge extends FeatureBridge {

    static final int REQ_PERM = 4501;

    ApparatenBridge(MainActivity act) { super(act); }

    private static String json(JSONObject o) { return o == null ? "{}" : o.toString(); }

    /** Instellingen, toestemmingen en wat er aan/uit staat. */
    @JavascriptInterface public String apState() {
        try {
            JSONObject p = new JSONObject().put("bt", ApparatenScan.btPerm(ctx)).put("wifi", ApparatenScan.wifiPerm(ctx)).put("loc", ApparatenScan.locPerm(ctx)).put("fine", ApparatenScan.finePerm(ctx))
                    .put("bg", ApparatenScan.bgPerm(ctx)).put("notif", NotifCenter.permissionOk(ctx)).put("btOn", ApparatenScan.btOn(ctx))
                    .put("btHw", ApparatenScan.adapter(ctx) != null).put("locOn", ApparatenScan.locOn(ctx)).put("sdk", Build.VERSION.SDK_INT);
            return new JSONObject().put("set", ApparatenStore.settings(ctx)).put("perms", p).put("muted", NotifCenter.muted(ctx)).toString();
        } catch (Throwable e) { return "{}"; }
    }

    /** Instellingen aanpassen ({bg, every, trackers, hist, keep, away}); daarna de achtergrond opnieuw inplannen. */
    @JavascriptInterface public String apSet(String j) {
        try {
            JSONObject o = new JSONObject(j);
            ApparatenStore.set(ctx, o);
            if (o.has("keep")) new Thread(() -> { try { ApparatenStore.histPrune(ctx); } catch (Throwable ignored) { } }, "apparaten-bewaren").start();
            ApparatenJob.ensure(ctx);
            return "";
        } catch (Throwable e) { return "Opslaan lukt niet"; }
    }

    // ----- Bluetooth -----

    @JavascriptInterface public String apBtStart(boolean classic) { return ApparatenScan.start(ctx, 25, null, classic); }
    @JavascriptInterface public String apBt() { return json(ApparatenScan.btJson(ctx)); }
    @JavascriptInterface public void apStop() { ApparatenScan.stop(ctx); ApparatenLan.stop(); }

    /** Eén apparaat zoeken (warmer/kouder): de scan loopt door zolang je kijkt (hooguit 10 minuten). */
    @JavascriptInterface public String apFind(String key) {
        if (key == null || key.length() > 120) return "Onbekend apparaat";
        return ApparatenScan.start(ctx, 600, key, false);
    }
    @JavascriptInterface public String apFindState(String key) { return key == null ? "{}" : json(ApparatenScan.findJson(key)); }
    @JavascriptInterface public void apFindStop() { ApparatenScan.findStop(); }

    // ----- wifi -----

    @JavascriptInterface public String apWifiScan() { return json(ApparatenScan.wifiScan(ctx)); }
    @JavascriptInterface public String apWifi() { return json(ApparatenScan.wifiJson(ctx)); }

    // ----- op mijn wifi -----

    @JavascriptInterface public String apLanStart() { return ApparatenLan.start(ctx); }
    @JavascriptInterface public void apLanStop() { ApparatenLan.stop(); }
    @JavascriptInterface public String apLan() { return json(ApparatenLan.json(ctx)); }

    // ----- trackers, bekend, geschiedenis -----

    @JavascriptInterface public String apTrackers() { return json(ApparatenJob.trackersJson(ctx)); }

    /** status: "mine" (dit is van mij), "ignored" (negeren) of "" (weer laten waarschuwen). */
    @JavascriptInterface public void apTrackerSet(String id, String status) {
        if (!"mine".equals(status) && !"ignored".equals(status) && !"".equals(status)) return;
        ApparatenJob.trackerStatus(ctx, id, status);
    }

    /** Mijn apparaat (met bijnaam), of nick leeg = niet meer bekend. */
    @JavascriptInterface public String apKnown(String key, String nick, String type, String name) {
        if (key == null || !Apparaten.stableKey(key)) return "Dit apparaat is niet terug te herkennen (het wisselt van adres en heeft geen naam)";
        ApparatenStore.setKnown(ctx, key, nick == null || nick.trim().isEmpty() ? null : nick, type, name);
        return "";
    }

    @JavascriptInterface public String apHistory() { return json(ApparatenStore.histOverview(ctx)); }

    /** Thuis = hier (grove plek ~100 m, plus de wifi waarmee je nu verbonden bent). */
    @JavascriptInterface public String apHome(boolean clear) {
        if (clear) { ApparatenStore.clearHome(ctx); return ""; }
        if (!ApparatenScan.locPerm(ctx)) return "perm";
        double[] l = ApparatenJob.location(ctx, false);
        String ssid = ApparatenScan.ssid(ctx);
        if (l == null && ssid.isEmpty()) return "Je plek is nu niet bekend. Zet locatie aan, open even een kaart-app en probeer het opnieuw.";
        ApparatenStore.setHome(ctx, l == null ? Double.NaN : l[0], l == null ? Double.NaN : l[1], ssid);
        return "";
    }

    /** Alles wissen (geschiedenis, trackers, netwerk); all = ook je bijnamen en thuis. */
    @JavascriptInterface public void apWipe(boolean all) { ApparatenStore.wipe(ctx, all); ApparatenScan.forget(); }

    // ----- toestemmingen en instellingen van Android -----

    /** Welke toestemmingen bij "nearby" horen: Bluetooth zoeken (en namen), wifi en de plek. */
    static String[] nearby() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        if (Build.VERSION.SDK_INT >= 31) return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    /** kind: "nearby", "bgloc" (locatie altijd, alleen voor de achtergrond) of "notif". */
    @JavascriptInterface public void apPerm(String kind) {
        a.h.post(() -> {
            String[] p = null;
            switch (kind == null ? "" : kind) {
                case "nearby": p = nearby(); break;
                case "bgloc":
                    if (!ApparatenScan.granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) p = nearby();
                    else if (Build.VERSION.SDK_INT >= 29) p = new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION};
                    break;
                case "notif": if (Build.VERSION.SDK_INT >= 33) p = new String[]{Manifest.permission.POST_NOTIFICATIONS}; break;
                default: a.openAppSettings(); return;
            }
            if (p == null) { a.js("onApChanged", ""); return; }
            android.content.SharedPreferences sp = ctx.getSharedPreferences("perm", Context.MODE_PRIVATE);
            String key = "ap_" + kind;
            String missing = null;
            for (String x : p) if (ctx.checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) { missing = x; break; }
            if (missing == null) { a.js("onApChanged", ""); return; }
            // Al eens gevraagd en Android vraagt het niet meer ("niet meer vragen" of twee keer geweigerd): naar de
            // app-instellingen, met uitleg wat daar aan moet
            if (sp.getBoolean(key, false) && !a.shouldShowRequestPermissionRationale(missing)) {
                String what = "bgloc".equals(kind) ? "Locatie: altijd toestaan" : "notif".equals(kind) ? "Meldingen aan"
                        : Build.VERSION.SDK_INT >= 31 ? "Apparaten in de buurt aan, en Locatie: precies" : "Locatie: precies";
                android.widget.Toast.makeText(a, "Android vraagt het niet meer. Zet bij Toestemmingen: " + what, android.widget.Toast.LENGTH_LONG).show();
                a.openAppSettings();
                return;
            }
            sp.edit().putBoolean(key, true).apply();
            a.requestPermissions(p, REQ_PERM);
        });
    }

    /** Bluetooth- of locatie-instellingen van Android openen (om Bluetooth of locatie aan te zetten). */
    @JavascriptInterface public void apOpenSettings(String which) {
        a.h.post(() -> {
            try {
                String act = "loc".equals(which) ? Settings.ACTION_LOCATION_SOURCE_SETTINGS : "wifi".equals(which) ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_BLUETOOTH_SETTINGS;
                a.startActivity(new Intent(act));
            } catch (Throwable e) { a.openAppSettings(); }
        });
    }
}
