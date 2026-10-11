package nl.rene.tools;

import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;

import java.net.InetAddress;

/**
 * Eén manier om te verbinden (WireGuard of OpenVPN) voor {@link VpnSvc}. VpnSvc kiest de volgende uit de rij
 * (reserve-tunnels) als deze binnen de tijd geen verbinding krijgt.
 */
interface VpnBackend {
    String tunnelId();
    String name();
    String kind();                 // "WireGuard" of "OpenVPN"
    void start() throws Exception; // mag blokkeren (DNS); de tunnel bij Android komt via VpnSvc.establish
    void stop();
    boolean healthy();             // werkt de verbinding nu?
    long lastOkWall();             // laatste geslaagde handshake/verbinding (wandklok), 0 = nooit
    boolean fatal();               // definitief mislukt (bijv. wachtwoord fout)
    long rx();
    long tx();
    String endpoint();
    String error();
    void networkChanged();
    long timeoutMs();              // zo lang wachten op een eerste verbinding voordat de reserve geprobeerd wordt

    // ================= WireGuard =================

    final class Wg implements VpnBackend {
        private final VpnSvc s; private final String id, name; private final int gen;
        private final WgConfig cfg;
        private volatile WgDevice dev;

        Wg(VpnSvc s, String id, String name, String text, int gen) { this.s = s; this.id = id; this.name = name; this.gen = gen; cfg = WgConfig.parse(text); }

        @Override public String tunnelId() { return id; }
        @Override public String name() { return name; }
        @Override public String kind() { return "WireGuard"; }

        @Override public void start() throws Exception {
            WgDevice d = new WgDevice(cfg, null, s::protect, s::resolve, s::onEvent);
            VpnService.Builder b = s.builder(name);
            b.setMtu(cfg.mtuOrDefault());
            for (WgConfig.Cidr c : cfg.addresses) b.addAddress(InetAddress.getByAddress(c.addr), c.prefix);
            for (String dns : cfg.dns) b.addDnsServer(InetAddress.getByAddress(WgConfig.ip(dns)));
            for (String sd : cfg.dnsSearch) b.addSearchDomain(sd);
            for (WgConfig.Peer p : cfg.peers) for (WgConfig.Cidr c : p.allowed) b.addRoute(InetAddress.getByAddress(c.network()), c.prefix);
            for (String app : cfg.excludedApps) try { b.addDisallowedApplication(app); } catch (PackageManager.NameNotFoundException ignored) { }
            int included = 0;
            for (String app : cfg.includedApps) try { b.addAllowedApplication(app); included++; } catch (PackageManager.NameNotFoundException ignored) { }
            if (!cfg.includedApps.isEmpty() && included == 0) throw new Exception("Geen van de apps bij IncludedApplications staat op deze telefoon");
            dev = d;
            d.start(); // server opzoeken en socket openen buiten de tunnel
            try { d.setTun(s.establish(b, gen)); } catch (Exception e) { d.stop(); throw e; }
        }

        @Override public void stop() { WgDevice d = dev; if (d != null) d.stop(); }
        @Override public boolean healthy() { WgDevice d = dev; return d != null && d.healthy(); }
        @Override public long lastOkWall() { WgDevice d = dev; return d == null ? 0 : d.lastHandshakeWall(); }
        @Override public boolean fatal() { return false; }
        @Override public long rx() { WgDevice d = dev; return d == null ? 0 : d.rx(); }
        @Override public long tx() { WgDevice d = dev; return d == null ? 0 : d.tx(); }
        @Override public String endpoint() { WgDevice d = dev; return d == null ? "" : d.endpointText(); }
        @Override public String error() { WgDevice d = dev; return d == null ? null : d.lastError; }
        @Override public void networkChanged() { WgDevice d = dev; if (d != null) d.networkChanged(); }
        @Override public long timeoutMs() { return 8_000; }
    }

    // ================= OpenVPN =================

    final class Ovpn implements VpnBackend, OvpnDevice.Net {
        private final VpnSvc s; private final String id, name; private final int gen;
        private final OvpnConfig cfg;
        private final OvpnDevice dev;

        Ovpn(VpnSvc s, String id, String name, String text, String user, String pass, int gen) {
            this.s = s; this.id = id; this.name = name; this.gen = gen;
            cfg = OvpnConfig.parse(text);
            if (cfg.authUserPass && (user == null || user.isEmpty())) throw new IllegalArgumentException("Vul gebruikersnaam en wachtwoord in bij deze tunnel");
            dev = new OvpnDevice(cfg, user, pass, this, (what, detail) -> s.onEvent(what), this::apply);
        }

        @Override public String tunnelId() { return id; }
        @Override public String name() { return name; }
        @Override public String kind() { return "OpenVPN"; }

        @Override public boolean protect(java.net.DatagramSocket so) { return s.protect(so); }
        @Override public boolean protect(java.net.Socket so) { return s.protect(so); }
        @Override public InetAddress[] resolve(String host) throws java.io.IOException { return s.resolve(host); }

        /** De server stuurde adres, routes en DNS: tunnel bij Android (opnieuw) opbouwen. */
        private void apply(OvpnDevice.Push p) throws Exception {
            VpnService.Builder b = s.builder(name);
            int mtu = p.mtu > 0 ? p.mtu : cfg.tunMtu > 0 ? cfg.tunMtu : 1500;
            b.setMtu(Math.max(1280, Math.min(1400, mtu))); // past met de versleuteling erbij in een gewoon pakket
            if (p.addresses.isEmpty()) throw new Exception("De server gaf geen adres (ifconfig)");
            for (WgConfig.Cidr c : p.addresses) b.addAddress(InetAddress.getByAddress(c.addr), c.prefix);
            for (WgConfig.Cidr c : p.routes) b.addRoute(InetAddress.getByAddress(c.network()), c.prefix);
            if (Build.VERSION.SDK_INT >= 33) for (WgConfig.Cidr c : p.excluded)
                try { b.excludeRoute(new android.net.IpPrefix(InetAddress.getByAddress(c.network()), c.prefix)); } catch (Exception ignored) { }
            for (String d : p.dns) b.addDnsServer(InetAddress.getByAddress(WgConfig.ip(d)));
            for (String d : p.domains) b.addSearchDomain(d);
            dev.setTun(s.establish(b, gen));
        }

        @Override public void start() { dev.start(); }
        @Override public void stop() { dev.stop(); }
        @Override public boolean healthy() { return dev.connected(); }
        @Override public long lastOkWall() { return dev.lastOkWall(); }
        @Override public boolean fatal() { return dev.fatal(); }
        @Override public long rx() { return dev.rx.get(); }
        @Override public long tx() { return dev.tx.get(); }
        @Override public String endpoint() { return dev.remoteText(); }
        @Override public String error() { return dev.lastError; }
        @Override public void networkChanged() { dev.networkChanged(); }
        @Override public long timeoutMs() { return Math.min(60_000, 20_000 + (cfg.remotes.size() - 1) * (OvpnDevice.NO_ANSWER_MS + 2_000)); }
    }
}
