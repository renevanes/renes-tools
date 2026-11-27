#!/usr/bin/env bash
# Alles klaarzetten om in een (afgeschermde) cloudsessie te bouwen en te testen: het Android-gereedschap, npm en een
# Chromium voor Playwright. Wat de proxy van de sessie weigert, wordt gemeld en waar het kan opgevangen; zie LEESMIJ.md.
# Alleen voor lokaal testen: in CI (CI gezet) stopt dit script; daar moet alles echt te downloaden zijn.
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
[ -n "${CI:-}" ] && { echo "Niet in CI: daar gebruik je ./setup-tools.sh, npm ci en npx playwright install chromium"; exit 1; }
T=${TOOLS:-/home/claude/toolchain}

echo "== Android-gereedschap (./setup-tools.sh)"
./setup-tools.sh || echo "!! setup-tools.sh kwam niet helemaal rond (zie de regels hierboven); hieronder staat wat er wel is"

echo "== npm ci"
npm ci --no-audit --no-fund >/dev/null || { echo "!! npm ci mislukte"; exit 1; }

echo "== Chromium voor Playwright"
if ! npx playwright install chromium >/dev/null 2>&1; then
  # Downloaden mag niet: een Chromium die al in het image staat gebruiken onder het nummer dat Playwright verwacht.
  P=${PLAYWRIGHT_BROWSERS_PATH:-$HOME/.cache/ms-playwright}
  rev=$(python3 -I -c 'import json,sys; b=json.load(open(sys.argv[1]))["browsers"]; print(next(x["revision"] for x in b if x["name"]=="chromium-headless-shell"))' node_modules/playwright-core/browsers.json)
  doel="$P/chromium_headless_shell-$rev"
  if [ -e "$doel/chrome-headless-shell-linux64/chrome-headless-shell" ]; then
    echo "   Chromium $rev staat er al"
  else
    oud=$(find "$P" -maxdepth 1 -name 'chromium_headless_shell-*' ! -name "chromium_headless_shell-$rev" -type d 2>/dev/null | sort -V | tail -1)
    if [ -n "$oud" ] && [ -x "$oud/chrome-headless-shell-linux64/chrome-headless-shell" ]; then
      # een halve download van $rev laat soms een echte map achter: dan komt de link er anders ín te staan
      if [ -d "$doel" ] && [ ! -L "$doel" ]; then rm -rf "$doel"; fi
      ln -sfn "$oud" "$doel" && echo "   Downloaden geweigerd: $(basename "$oud") gebruikt als $rev (alleen lokaal; in CI de echte)"
    else
      echo "!! Geen Chromium te vinden en downloaden mag niet: de interfacetests kunnen hier niet draaien (in CI wel)"
    fi
  fi
fi

echo "== Wat er is"
staat(){ if [ -e "$2" ]; then echo "   ja   $1"; else echo "   nee  $1"; fi; }
staat "build-tools" "$T/bt/VERSION"
staat "android.jar" "$T/ap/android-36/android.jar"
staat "aapt2" "$T/aapt2"
staat "sherpa.jar (voorlezen)" "$T/voorlezen/sherpa.jar"
staat "ant-codecs.jar (voorlezen)" "$T/voorlezen/ant-codecs.jar"
if [ -f "$T/voorlezen/ant-codecs.jar" ] && [ -f "$T/voorlezen/sherpa.jar" ]; then
  echo "Klaar: ./build.sh (met een testsleutel, zie CONTRIBUTING.md), ./tests/run-jvm.sh en ./tests/run-ui.sh"
else
  echo "Maven Central of een download werd geweigerd: ./build.sh stopt dan vóór javac."
  echo "Gebruik ./tests/cloudsessie/java-bouwen.sh (alleen Java, geen APK), dan ./tests/run-jvm.sh en ./tests/run-ui.sh."
  echo "De APK zelf bouwt en test GitHub (Bouwen en testen, Telefoontest) bij elke push van je branch."
fi
