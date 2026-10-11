#!/usr/bin/env bash
# Test de OpenVPN-client (OvpnDevice) tegen echte OpenVPN 2.6-servers. Niet onderdeel van publish.sh:
# heeft root, /dev/net/tun en het pakket openvpn nodig.   Gebruik: tests/ovpn/run-interop.sh <werkmap> [scenario]   (eerst ./build.sh)
set -euo pipefail
cd "$(dirname "$0")/../.."
W=$1; ONLY=${2:-}
tests/ovpn/pki.sh "$W"
srv() { # n poort proto extra
  local n=$1 port=$2 proto=$3; shift 3
  cat > "$W/srv$n.conf" <<C
dev tun$n
proto $proto
port $port
topology subnet
server 10.8$n.0.0 255.255.255.0
$([ "${OVPN_IPV6:-0}" = 1 ] && echo "server-ipv6 fd00:0:0:8$n::/64")
ca $W/ca.crt
cert $W/server.crt
key $W/server.key
dh none
keepalive 10 60
push "redirect-gateway def1"
push "dhcp-option DNS 10.8$n.0.53"
verb 3
$*
C
  openvpn --config "$W/srv$n.conf" --log "$W/srv$n.log" --writepid "$W/srv$n.pid" --daemon
}
pkill -f "openvpn --config $W/srv" 2>/dev/null || true; sleep 0.5
srv 1 11941 udp "tls-crypt $W/tc.key"
srv 2 11942 tcp-server "tls-auth $W/ta.key 0
verify-client-cert none
username-as-common-name
script-security 2
auth-user-pass-verify $W/checkpw.sh via-file
data-ciphers CHACHA20-POLY1305:AES-256-GCM"
srv 3 11943 udp "data-ciphers AES-256-CBC
auth SHA256
compress stub-v2
push \"compress stub-v2\""
srv 4 11944 udp "comp-lzo no
push \"comp-lzo no\"
data-ciphers AES-128-GCM:AES-256-GCM"
srv 5 11945 udp "tls-crypt $W/tc.key
reneg-sec 8"
srv 6 11946 tcp-server "reneg-sec 0"
sleep 1.5
T=${TOOLS:-/home/claude/toolchain}
rm -rf build/ovpntest && mkdir -p build/ovpntest
javac -nowarn -encoding UTF-8 -cp "build/classes:$T/ap/android-36/android.jar" -d build/ovpntest tests/ovpn/OvpnInterop.java
set +e
java ${OVPN_JAVA_OPTS:-} -cp "build/ovpntest:build/classes:$T/ap/android-36/android.jar" nl.rene.tools.OvpnInterop "$W" $ONLY
code=$?
pkill -f "openvpn --config $W/srv" 2>/dev/null
exit $code
