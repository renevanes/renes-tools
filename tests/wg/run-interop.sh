#!/usr/bin/env bash
# Test de VPN (WgDevice) tegen de officiële wireguard-go. Niet onderdeel van publish.sh (heeft Go en GitHub nodig).
# Gebruik: tests/wg/run-interop.sh <map-met-wireguard-go-en-x-crypto/x-net/x-sys> [lang]   (eerst ./build.sh)
set -euo pipefail
cd "$(dirname "$0")/../.."
W=${1:-${WG_SRC:-}}
[ -n "$W" ] || { echo "Geef de map met de clones (wireguard-go, x-crypto, x-net, x-sys)"; exit 1; }
mkdir -p "$W/wireguard-go/cmd/javatest" && cp tests/wg/server.go "$W/wireguard-go/cmd/javatest/main.go"
(cd "$W/wireguard-go" && GOFLAGS=-mod=mod GOPROXY=off GOSUMDB=off go build -o ../wgserver ./cmd/javatest)
T=${TOOLS:-/home/claude/toolchain}
rm -rf build/wgtest && mkdir -p build/wgtest
javac -nowarn -encoding UTF-8 -cp "build/classes:$T/ap/android-36/android.jar" -d build/wgtest tests/wg/WgInterop.java
java -cp "build/wgtest:build/classes:$T/ap/android-36/android.jar" nl.rene.tools.WgInterop "$W/wgserver" ${2:-}
