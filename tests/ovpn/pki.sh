#!/usr/bin/env bash
# Maakt een test-PKI (CA, server, client) en tls-auth/tls-crypt-sleutels voor de OpenVPN-interoptests.
# Gebruik: tests/ovpn/pki.sh <map>
set -euo pipefail
D=$1; mkdir -p "$D"; cd "$D"
[ -f ca.crt ] && exit 0
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout ca.key -out ca.crt -days 30 -subj "/CN=Test CA" 2>/dev/null
cat > ext.cnf <<X
[srv]
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyAgreement
extendedKeyUsage=serverAuth
[cli]
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyAgreement
extendedKeyUsage=clientAuth
X
openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout server.key -out server.csr -subj "/CN=vpn.test" 2>/dev/null
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 30 -extfile ext.cnf -extensions srv 2>/dev/null
openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout client.key -out client.csr -subj "/CN=telefoon" 2>/dev/null
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client.crt -days 30 -extfile ext.cnf -extensions cli 2>/dev/null
# client-sleutel ook als RSA (PKCS#1) en EC (SEC1) om de PEM-lezer te testen
openssl req -newkey rsa:2048 -nodes -keyout client-rsa.key -out client-rsa.csr -subj "/CN=telefoon-rsa" 2>/dev/null
openssl x509 -req -in client-rsa.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client-rsa.crt -days 30 -extfile ext.cnf -extensions cli 2>/dev/null
openssl rsa -in client-rsa.key -traditional -out client-rsa-pkcs1.key 2>/dev/null
openssl ec -in client.key -out client-sec1.key 2>/dev/null
openvpn --genkey secret ta.key >/dev/null
openvpn --genkey tls-crypt tc.key >/dev/null 2>&1 || openvpn --genkey secret tc.key
# een andere CA (om te testen dat een verkeerde server geweigerd wordt)
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout other-ca.key -out other-ca.crt -days 30 -subj "/CN=Andere CA" 2>/dev/null
printf '#!/bin/sh\n[ "$(head -1 "$1")" = "rene" ] && [ "$(sed -n 2p "$1")" = "geheim123" ]\n' > checkpw.sh; chmod +x checkpw.sh
