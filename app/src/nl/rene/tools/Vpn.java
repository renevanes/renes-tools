package nl.rene.tools;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * VPN (WireGuard): tunnels bewaren (versleuteld met een sleutel uit de sleutelopslag van de telefoon), aan/uit,
 * en vanzelf aan op wifi die je niet vertrouwt. Het echte werk doet {@link VpnSvc} met {@link WgDevice}.
 */
final class Vpn {
    private Vpn() { }

    static final String CHANNEL = "vpn";
    static final int NOTIF_ASK = 4702;
    static final String ACTION_NET = "nl.rene.tools.VPN_NET";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("vpn", Context.MODE_PRIVATE); }

    // ---------- versleuteld bewaren ----------

    private static String seal(String plain) throws Exception {
        javax.crypto.Cipher ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(javax.crypto.Cipher.ENCRYPT_MODE, Secure.deviceKey());
        return Secure.b64(ci.getIV()) + ":" + Secure.b64(ci.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String open(String sealed) throws Exception {
        String[] p = sealed.split(":");
        javax.crypto.Cipher ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(javax.crypto.Cipher.DECRYPT_MODE, Secure.deviceKey(), new javax.crypto.spec.GCMParameterSpec(128, Secure.unb64(p[0])));
        return new String(ci.doFinal(Secure.unb64(p[1])), StandardCharsets.UTF_8);
    }

    static synchronized JSONArray tunnels(Context c) {
        try { return new JSONArray(prefs(c).getString("tunnels", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static JSONObject tunnel(Context c, String id) {
        JSONArray a = tunnels(c);
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && o.optString("id").equals(id)) return o; }
        return null;
    }

    /** De configuratietekst (met de privésleutel) — alleen voor de tunnel zelf en het bewerkscherm. */
    static String configText(Context c, String id) throws Exception {
        JSONObject t = tunnel(c, id);
        if (t == null) throw new Exception("Deze tunnel bestaat niet meer");
        try { return open(t.getString("conf")); }
        catch (Exception e) { throw new Exception("De sleutel van deze tunnel is niet meer te lezen (nieuwe telefoon?). Zet de configuratie opnieuw in de app."); }
    }

    /** Samenvatting zonder geheimen (voor de lijst). */
    static JSONObject summary(WgConfig w) throws Exception {
        JSONObject o = new JSONObject();
        o.put("pub", WgConfig.b64(WgCrypto.publicKey(w.privateKey)));
        JSONArray addr = new JSONArray(); for (WgConfig.Cidr x : w.addresses) addr.put(x.toString());
        JSONArray dns = new JSONArray(); for (String d : w.dns) dns.put(d);
        o.put("addr", addr).put("dns", dns).put("full", w.fullTunnel()).put("mtu", w.mtuOrDefault());
        WgConfig.Peer p = w.peers.get(0);
        o.put("endpoint", p.endpoint()).put("peers", w.peers.size()).put("keepalive", p.keepalive).put("psk", p.presharedKey != null);
        JSONArray allowed = new JSONArray(); for (WgConfig.Cidr x : p.allowed) allowed.put(x.toString());
        o.put("allowed", allowed);
        JSONArray ex = new JSONArray(); for (String s : w.excludedApps) ex.put(s);
        JSONArray in = new JSONArray(); for (String s : w.includedApps) in.put(s);
        o.put("excluded", ex).put("included", in);
        return o;
    }

    /** WireGuard ([Interface]) of OpenVPN (.ovpn)? */
    static boolean isWg(String text) { return text != null && text.toLowerCase(java.util.Locale.ROOT).contains("[interface]"); }

    /** Samenvatting van een OpenVPN-configuratie (zonder geheimen). */
    static JSONObject summary(OvpnConfig o) throws Exception {
        JSONArray rem = new JSONArray();
        for (OvpnConfig.Remote r : o.remotes) rem.put(r.toString());
        JSONArray warn = new JSONArray();
        for (String w : o.warnings) warn.put(w);
        OvpnConfig.Remote r0 = o.remotes.get(0);
        return new JSONObject().put("type", "ovpn").put("endpoint", r0.host + ":" + r0.port).put("remotes", rem).put("tcp", o.hasTcp())
                .put("auth", o.authUserPass).put("cert", o.cert != null).put("summary", o.summary()).put("warnings", warn)
                .put("addr", new JSONArray()).put("dns", new JSONArray()).put("allowed", new JSONArray());
    }

    /** Controleren zonder op te slaan: {ok, summary} of {error}. */
    static JSONObject check(String text) {
        try {
            JSONObject sm = isWg(text) ? summary(WgConfig.parse(text)).put("type", "wg") : summary(OvpnConfig.parse(text));
            return new JSONObject().put("ok", true).put("summary", sm);
        } catch (Exception e) {
            try { return new JSONObject().put("error", e.getMessage() == null ? "Klopt niet" : e.getMessage()); } catch (Exception x) { return new JSONObject(); }
        }
    }

    /** Opslaan (nieuw als id leeg). user/pass alleen voor OpenVPN met auth-user-pass (leeg = bewaarde houden). Geeft {id} of {error}. */
    static synchronized JSONObject save(Context c, String id, String name, String text, String user, String pass) {
        try {
            boolean wg = isWg(text);
            WgConfig w = wg ? WgConfig.parse(text) : null;
            OvpnConfig ov = wg ? null : OvpnConfig.parse(text);
            String n = name == null ? "" : name.trim();
            if (n.isEmpty()) n = wg ? (w.peers.get(0).endpointHost == null ? "Mijn VPN" : w.peers.get(0).endpointHost) : ov.remotes.get(0).host;
            if (n.length() > 40) n = n.substring(0, 40);
            JSONArray a = tunnels(c), out = new JSONArray();
            boolean found = false;
            String newId = id == null || id.isEmpty() ? Long.toString(System.currentTimeMillis(), 36) + Integer.toString(WgCrypto.RNG.nextInt(1296), 36) : id;
            JSONObject t = (wg ? summary(w).put("type", "wg") : summary(ov)).put("id", newId).put("name", n).put("conf", seal(wg ? w.toText() : text));
            JSONObject prev = tunnel(c, newId);
            if (prev != null && prev.has("fallback")) t.put("fallback", prev.getJSONArray("fallback"));
            if (ov != null && ov.authUserPass) {
                String u = user == null ? "" : user.trim(), pw = pass == null ? "" : pass;
                if (u.isEmpty() || pw.isEmpty()) {
                    JSONObject old = prev != null && prev.has("cred") ? creds(c, newId) : null;
                    if (u.isEmpty() && old != null) u = old.optString("user");
                    // bewaard wachtwoord alleen houden bij dezelfde gebruikersnaam
                    if (pw.isEmpty() && old != null && u.equals(old.optString("user"))) pw = old.optString("pass");
                }
                if (u.isEmpty() || pw.isEmpty()) return new JSONObject().put("error", "Vul je gebruikersnaam en wachtwoord van de VPN in");
                t.put("cred", seal(new JSONObject().put("user", u).put("pass", pw).toString())).put("user", u);
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (o.optString("id").equals(newId)) { out.put(t.put("created", o.optLong("created", System.currentTimeMillis()))); found = true; }
                else out.put(o);
            }
            if (!found) {
                if (id != null && !id.isEmpty()) return new JSONObject().put("error", "Deze tunnel bestaat niet meer");
                out.put(t.put("created", System.currentTimeMillis()));
            }
            prefs(c).edit().putString("tunnels", out.toString()).apply();
            if (prefs(c).getString("active", "").isEmpty() || !found && out.length() == 1) prefs(c).edit().putString("active", newId).apply();
            return new JSONObject().put("id", newId).put("restart", found && newId.equals(VpnSvc.tunnelId) && VpnSvc.on());
        } catch (IllegalArgumentException e) {
            try { return new JSONObject().put("error", e.getMessage()); } catch (Exception x) { return new JSONObject(); }
        } catch (Exception e) {
            try { return new JSONObject().put("error", "Opslaan lukt niet: " + e.getMessage()); } catch (Exception x) { return new JSONObject(); }
        }
    }

    /** Gebruikersnaam en wachtwoord van een OpenVPN-tunnel (of null). */
    static JSONObject creds(Context c, String id) {
        JSONObject t = tunnel(c, id);
        if (t == null || !t.has("cred")) return null;
        try { return new JSONObject(open(t.getString("cred"))); } catch (Exception e) { return null; }
    }

    /** De rij om te proberen: de gekozen tunnel en dan zijn reserve-tunnels (bij een blokkade). */
    static java.util.List<String> chain(Context c, String id) {
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(id);
        JSONObject t = tunnel(c, id);
        JSONArray f = t == null ? null : t.optJSONArray("fallback");
        if (f != null) for (int i = 0; i < f.length(); i++) { String x = f.optString(i); if (!l.contains(x) && tunnel(c, x) != null) l.add(x); }
        return l;
    }

    static synchronized void setFallback(Context c, String id, JSONArray ids) {
        JSONArray a = tunnels(c);
        try {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !o.optString("id").equals(id)) continue;
                JSONArray clean = new JSONArray();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int j = 0; j < ids.length() && clean.length() < 4; j++) { String x = ids.optString(j); if (!x.equals(id) && tunnel(c, x) != null && seen.add(x)) clean.put(x); }
                o.put("fallback", clean);
            }
            prefs(c).edit().putString("tunnels", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** De verbinding voor één tunnel uit de rij (VpnSvc). */
    static VpnBackend backend(VpnSvc s, String id, int gen) throws Exception {
        JSONObject t = tunnel(s, id);
        if (t == null) throw new Exception("Deze tunnel bestaat niet meer");
        String text = configText(s, id), name = t.optString("name", "VPN");
        if ("ovpn".equals(t.optString("type")) || !isWg(text)) {
            JSONObject cr = creds(s, id);
            return new VpnBackend.Ovpn(s, id, name, text, cr == null ? "" : cr.optString("user"), cr == null ? "" : cr.optString("pass"), gen);
        }
        return new VpnBackend.Wg(s, id, name, text, gen);
    }

    static synchronized void delete(Context c, String id) {
        if (id.equals(VpnSvc.tunnelId) && VpnSvc.on()) stop(c, "deleted");
        JSONArray a = tunnels(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("id").equals(id)) continue;
            JSONArray f = o.optJSONArray("fallback");
            if (f != null) { JSONArray nf = new JSONArray(); for (int j = 0; j < f.length(); j++) if (!id.equals(f.optString(j))) nf.put(f.optString(j)); try { o.put("fallback", nf); } catch (Exception ignored) { } }
            out.put(o);
        }
        SharedPreferences.Editor e = prefs(c).edit().putString("tunnels", out.toString());
        if (prefs(c).getString("active", "").equals(id)) e.putString("active", out.length() > 0 ? out.optJSONObject(0).optString("id") : "");
        e.apply();
        armAuto(c);
    }

    static String activeId(Context c) {
        String id = prefs(c).getString("active", "");
        if (tunnel(c, id) != null) return id;
        JSONArray a = tunnels(c);
        return a.length() > 0 ? a.optJSONObject(0).optString("id") : "";
    }

    // ---------- aan en uit ----------

    static final String OK = "", NEED_CONSENT = "consent", BACKGROUND = "background", NO_TUNNEL = "none";

    /** Toestemming van Android nodig (eerste keer, of een andere VPN-app nam het over)? */
    static boolean needsConsent(Context c) {
        try { return VpnService.prepare(c) != null; } catch (Exception e) { return true; }
    }

    /**
     * Tunnel aanzetten. reason: "app", "tegel", "snelkoppeling", "wifi", "mobiel", "auto", "altijd".
     * Geeft OK, NEED_CONSENT, BACKGROUND (Android liet starten vanaf de achtergrond niet toe), NO_TUNNEL of een melding.
     */
    static String start(Context c, String id, String reason) { return start(c, id, reason, false); }

    /** restart: ook opnieuw opbouwen als deze tunnel al draait (na het bewerken). */
    static String start(Context c, String id, String reason, boolean restart) {
        if (id == null || id.isEmpty()) id = activeId(c);
        if (id.isEmpty() || tunnel(c, id) == null) return NO_TUNNEL;
        if (needsConsent(c)) return NEED_CONSENT;
        SharedPreferences.Editor ed = prefs(c).edit().putString("active", id);
        if (!"wifi".equals(reason) && !"mobiel".equals(reason)) ed.remove("manualOff"); // zelf aangezet
        ed.apply();
        Intent i = new Intent(c, VpnSvc.class).setAction(VpnSvc.START).putExtra("id", id).putExtra("reason", reason).putExtra("restart", restart);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            return OK;
        } catch (IllegalStateException e) { // ook ForegroundServiceStartNotAllowedException
            App.log(c, "VPN", "starten vanaf de achtergrond geweigerd (" + reason + ")");
            return BACKGROUND;
        } catch (Exception e) {
            return "Starten lukt niet: " + e.getClass().getSimpleName();
        }
    }

    static void stop(Context c, String reason) {
        VpnSvc s = VpnSvc.inst;
        if (s == null) return;
        // Zelf uitgezet: niet meteen weer vanzelf aan op hetzelfde netwerk (pas op een ander netwerk)
        if (!"wifi-thuis".equals(reason) && !"deleted".equals(reason)) prefs(c).edit().putString("manualOff", netKey(c)).apply();
        s.stopTunnel(reason);
    }

    /** Op welk netwerk zitten we (zonder VPN): "wifi:<naam>", "wifi:?", "cell" of "". Wifi gaat voor. */
    static String netKey(Context c) {
        boolean[] wc = transports(c);
        if (wc[0]) { String s = currentSsid(c); return s != null ? "wifi:" + s : "wifi#" + wifiNetId(c); } // naam onbekend: deze wifiverbinding
        return wc[1] ? "cell" : "";
    }

    private static String wifiNetId(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n.toString();
            }
        } catch (Exception ignored) { }
        return "?";
    }

    /** {wifi, mobiel, andere VPN-app actief} van de netwerken die er nu zijn. */
    static boolean[] transports(Context c) {
        boolean wifi = false, cell = false, otherVpn = false;
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null) continue;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { otherVpn = true; continue; }
                if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true;
            }
        } catch (Exception ignored) { }
        return new boolean[]{wifi, cell, otherVpn && !VpnSvc.on()};
    }

    static String toggle(Context c, String reason) {
        if (VpnSvc.on()) { stop(c, reason); return OK; }
        return start(c, null, reason);
    }

    /** Toestand voor het scherm, de tegel en het overzicht. */
    static JSONObject state(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("on", VpnSvc.on()).put("status", VpnSvc.status).put("id", VpnSvc.tunnelId == null ? "" : VpnSvc.tunnelId)
                    .put("name", VpnSvc.tunnelName == null ? "" : VpnSvc.tunnelName).put("error", VpnSvc.error == null ? "" : VpnSvc.error)
                    .put("since", VpnSvc.since).put("reason", VpnSvc.reason == null ? "" : VpnSvc.reason).put("active", activeId(c));
            // geen VpnService.prepare() hier: dat zet stilletjes de VPN van een andere app uit
            VpnBackend d = VpnSvc.backend;
            if (d != null) o.put("rx", d.rx()).put("tx", d.tx()).put("handshake", d.lastOkWall()).put("endpoint", d.endpoint()).put("kind", d.kind());
            if (VpnSvc.activeId != null) o.put("activeId", VpnSvc.activeId).put("activeName", VpnSvc.activeName == null ? "" : VpnSvc.activeName);
            VpnSvc s = VpnSvc.inst;
            if (s != null && Build.VERSION.SDK_INT >= 29) o.put("alwaysOn", s.isAlwaysOn()).put("lockdown", s.isLockdownEnabled());
            o.put("auto", autoCfg(c)).put("ssid", currentSsid(c) == null ? "" : currentSsid(c)).put("wifi", onWifi(c))
                    .put("locOk", Auto.fineOk(c)).put("bgLocOk", Auto.bgLocOk(c)).put("locOn", locationOn(c));
        } catch (Exception ignored) { }
        return o;
    }

    // ---------- vanzelf aan op onbekende wifi ----------

    /** {wifi: bool, home: bool (uit op vertrouwde wifi), mobile: bool, trusted: [ssid…]} */
    static JSONObject autoCfg(Context c) {
        try { return new JSONObject(prefs(c).getString("auto", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void setAuto(Context c, JSONObject a) {
        JSONObject o = new JSONObject();
        try {
            o.put("wifi", a.optBoolean("wifi")).put("home", a.optBoolean("home", true)).put("mobile", a.optBoolean("mobile"));
            JSONArray t = a.optJSONArray("trusted"), clean = new JSONArray();
            java.util.Set<String> seen = new java.util.HashSet<>();
            if (t != null) for (int i = 0; i < t.length() && clean.length() < 50; i++) {
                String s = t.optString(i).trim();
                if (!s.isEmpty() && s.length() <= 32 && seen.add(s)) clean.put(s);
            }
            o.put("trusted", clean);
        } catch (Exception ignored) { }
        prefs(c).edit().putString("auto", o.toString()).apply();
        armAuto(c);
    }

    static boolean trusted(Context c, String ssid) {
        if (ssid == null) return false;
        JSONArray t = autoCfg(c).optJSONArray("trusted");
        if (t != null) for (int i = 0; i < t.length(); i++) if (ssid.equals(t.optString(i))) return true;
        return false;
    }

    /** Naam van de wifi waar je nu op zit (null als onbekend: geen wifi of geen locatietoestemming). */
    @SuppressWarnings("deprecation")
    static String currentSsid(Context c) {
        try {
            if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            String s = wi == null ? null : wi.getSSID();
            if (s == null || s.equals(WifiManager.UNKNOWN_SSID) || s.equals("<unknown ssid>") || s.isEmpty()) return null;
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
            return s;
        } catch (Exception e) { return null; }
    }

    /** Staat locatie (de schakelaar van Android) aan? Zonder is de wifi-naam onbekend. */
    @SuppressWarnings("deprecation")
    static boolean locationOn(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 28) return c.getSystemService(android.location.LocationManager.class).isLocationEnabled();
            return android.provider.Settings.Secure.getInt(c.getContentResolver(), android.provider.Settings.Secure.LOCATION_MODE) != 0;
        } catch (Exception e) { return true; }
    }

    /** Zit de telefoon (onder de VPN) op wifi? */
    static boolean onWifi(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static PendingIntent netOp(Context c, int code, int flags) {
        Intent i = new Intent(c, VpnNet.class).setAction(ACTION_NET);
        int mut = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0; // Android vult het netwerk in
        return PendingIntent.getBroadcast(c, code, i, mut | flags);
    }

    /** Aanmelden; mislukt het, dan de PendingIntent weer weg (anders denkt armAuto later dat het gelukt is). */
    private static void register(Context c, ConnectivityManager cm, NetworkRequest req, int code) {
        PendingIntent pi = netOp(c, code, PendingIntent.FLAG_UPDATE_CURRENT);
        try { cm.registerNetworkCallback(req, pi); } catch (RuntimeException e) { pi.cancel(); throw e; }
    }

    /** Netwerkmeldingen aan/uit naargelang de instellingen (veilig om vaak aan te roepen). */
    static void armAuto(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            JSONObject a = autoCfg(c);
            boolean any = tunnels(c).length() > 0;
            boolean wantW = any && (a.optBoolean("wifi") || a.optBoolean("home", true) && a.optBoolean("mobile")), wantM = any && a.optBoolean("mobile");
            PendingIntent w = netOp(c, 4711, PendingIntent.FLAG_NO_CREATE), m = netOp(c, 4712, PendingIntent.FLAG_NO_CREATE);
            // al zo aangemeld? Dan niets doen (opnieuw aanmelden laat Android meteen alle netwerken melden)
            if ((w != null) == wantW && (m != null) == wantM) return;
            if (w != null) { try { cm.unregisterNetworkCallback(w); } catch (Exception ignored) { } w.cancel(); }
            if (m != null) { try { cm.unregisterNetworkCallback(m); } catch (Exception ignored) { } m.cancel(); }
            if (wantW) register(c, cm, new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), 4711);
            if (wantM) {
                // Mobiel blijft vaak op de achtergrond verbonden terwijl je op wifi zit: dan pas melden als mobiel het
                // netwerk wordt dat de telefoon gebruikt (FOREGROUND, Android 9+). Lukt dat niet, dan zonder.
                boolean ok = false;
                if (Build.VERSION.SDK_INT >= 28) try {
                    register(c, cm, new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND).build(), 4712);
                    ok = true;
                } catch (Exception e) { App.log(c, "VPN", "mobiel (voorgrond) niet aan te melden: " + e.getClass().getSimpleName()); }
                if (!ok) register(c, cm, new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), 4712);
            }
        } catch (Exception e) {
            App.log(c, "VPN", "netwerkmeldingen: " + e.getClass().getSimpleName());
        }
    }

    /** Wat er moet gebeuren bij een (nieuw) netwerk: "on", "off" of "" (niets). Puur, voor de tests (VpnDecideTest). */
    static String decide(boolean wifi, boolean cellular, boolean isTrusted, boolean autoWifi, boolean autoHome, boolean autoMobile, boolean running, boolean autoStarted) {
        if (wifi) {
            if (isTrusted) return running && autoStarted && autoHome ? "off" : "";
            return autoWifi && !running ? "on" : "";
        }
        return cellular && autoMobile && !running ? "on" : "";
    }

    /**
     * Aangeroepen door Android (VpnNet) als er een wifi- of mobiel netwerk bij komt. Er wordt gekeken naar de netwerken
     * die er nu zijn (wifi gaat voor: mobiel staat vaak ook aan), niet naar het netwerk dat de melding gaf.
     */
    static void onNetwork(Context c, Network ignoredTrigger) {
        try {
            boolean[] t = transports(c);
            if (t[2]) return; // een andere VPN-app is actief: die niet overnemen
            String key = netKey(c), manual = prefs(c).getString("manualOff", null);
            if (manual != null) {
                if (manual.equals(key)) return; // hier zelf uitgezet
                prefs(c).edit().remove("manualOff").apply(); // ander netwerk: weer vanzelf
            }
            boolean wifi = t[0], cell = !t[0] && t[1];
            String ssid = wifi ? currentSsid(c) : null;
            JSONObject a = autoCfg(c);
            String what = decide(wifi, cell, trusted(c, ssid), a.optBoolean("wifi"), a.optBoolean("home", true), a.optBoolean("mobile"), VpnSvc.on(), VpnSvc.autoStarted);
            if (what.equals("off")) { App.log(c, "VPN", "vertrouwde wifi: uit"); stop(c, "wifi-thuis"); return; }
            if (!what.equals("on")) return;
            String reason = wifi ? "wifi" : "mobiel";
            String r = start(c, null, reason);
            App.log(c, "VPN", "automatisch aan (" + reason + "): " + (r.isEmpty() ? "ok" : r));
            if (r.equals(BACKGROUND) || r.equals(NEED_CONSENT)) ask(c, wifi ? (ssid == null ? "Onbekende wifi" : "Wifi \"" + ssid + "\"") : "Mobiel internet", r.equals(NEED_CONSENT));
        } catch (Exception e) {
            App.log(c, "VPN", "netwerk: " + e.getClass().getSimpleName());
        }
    }

    /** Melding als Android het automatisch aanzetten niet toeliet: één tik zet hem aan. */
    static void ask(Context c, String where, boolean consent) {
        VpnSvc.createChannel(c);
        PendingIntent tap;
        if (consent) {
            Intent open = new Intent(c, MainActivity.class).putExtra("open", "vpn").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            tap = PendingIntent.getActivity(c, 4703, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            Intent i = new Intent(c, VpnSvc.class).setAction(VpnSvc.START).putExtra("id", activeId(c)).putExtra("reason", "melding");
            tap = Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, 4704, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                    : PendingIntent.getService(c, 4704, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, NotifCenter.ch(c, CHANNEL)) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_vpn).setContentTitle(where + ": VPN aanzetten?")
                .setContentText(consent ? "Android vraagt eerst toestemming. Tik om te openen." : "Tik om de VPN aan te zetten.")
                .setContentIntent(tap).setAutoCancel(true);
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(30 * 60_000L);
        try { c.getSystemService(android.app.NotificationManager.class).notify(NOTIF_ASK, b.build()); } catch (Exception ignored) { }
    }

    static void cancelAsk(Context c) {
        try { c.getSystemService(android.app.NotificationManager.class).cancel(NOTIF_ASK); } catch (Exception ignored) { }
    }

    /** Snelle-instellingen-tegel bijwerken. */
    static void refreshTile(Context c) {
        if (Build.VERSION.SDK_INT >= 24) try { android.service.quicksettings.TileService.requestListeningState(c, new ComponentName(c, VpnTile.class)); } catch (Exception ignored) { }
    }
}
