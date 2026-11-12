#!/usr/bin/env bash
# Playwright (versie uit package-lock.json) met Chromium en de systeempakketten die Chromium nodig heeft, voor de interfacetests.
#
# De systeempakketten (vooral lettertypen) komen via apt van Ubuntu. Op GitHub staan die servers in
# /etc/apt/apt-mirrors.txt (azure.archive.ubuntu.com, archive.ubuntu.com, security.ubuntu.com). Is *.ubuntu.com niet
# bereikbaar (nov. 2026: urenlang "Temporary failure resolving"), dan opnieuw via spiegelservers buiten ubuntu.com
# (SPIEGELS). Dat zijn dezelfde pakketten van Ubuntu: apt controleert ze met de sleutels van Ubuntu, wie ze ook levert.
# Lukt ook dat niet, dan gaan de tests door zonder de ontbrekende lettertypen, maar alleen als Chromium echt start.
set -euo pipefail
cd "$(dirname "$0")/../.."

SPIEGELS=(https://mirrors.edge.kernel.org/ubuntu/ https://ftp.nluug.nl/os/Linux/distr/ubuntu/)
POGING_S=240 # een geslaagde installatie duurt ±1 minuut; een onbereikbare server wacht 20 s per pakket

npm ci
# De browser zelf komt van Playwright, niet via apt
npx playwright install chromium

# Een afgebroken poging laat het slot van dpkg even vastzitten: de volgende wacht daarop
echo 'DPkg::Lock::Timeout "120";' | sudo tee /etc/apt/apt.conf.d/80-slot-wachten > /dev/null

systeempakketten() { timeout -k 15 "$POGING_S" npx playwright install-deps chromium; } # doet zelf apt-get update en install

# Alle servers van Ubuntu vervangen door SPIEGELS: in de lijst met servers (mirror+file:, zoals op GitHub) en in
# bronnen met een vaste server (oude en nieuwe indeling)
naar_spiegels() {
  local f lijst
  while IFS= read -r lijst; do
    [ -f "$lijst" ] && printf '%s\n' "${SPIEGELS[@]}" | sudo tee "$lijst" > /dev/null
  done < <(grep -rhoE 'mirror\+file:[^ ]+' /etc/apt 2> /dev/null | sed 's/^mirror+file://' | sort -u)
  while IFS= read -r f; do
    sudo sed -E -i "s#https?://(azure\.archive|archive|security)\.ubuntu\.com/ubuntu/?#${SPIEGELS[0]}#g" "$f"
  done < <(find /etc/apt -maxdepth 2 \( -name 'sources.list' -o -name '*.list' -o -name '*.sources' \) -type f)
}

if systeempakketten; then exit 0; fi
echo "::warning::De servers van Ubuntu zijn niet bereikbaar; opnieuw via ${SPIEGELS[*]}"
sudo dpkg --configure -a || true # een afgebroken poging kan dpkg halverwege hebben laten staan
naar_spiegels
if systeempakketten; then
  echo "::notice::Systeempakketten geïnstalleerd via de spiegelservers"
  exit 0
fi

# Geen enkele server: de bibliotheken die Chromium nodig heeft, staan al op het image (alleen lettertypen ontbreken
# dan). Doorgaan als Chromium een pagina kan tonen, anders stoppen.
echo "::warning::Systeempakketten (lettertypen) niet te installeren; de tests draaien met de lettertypen van het image"
node -e "require('playwright').chromium.launch().then(async b => { const p = await b.newPage(); await p.setContent('<p>Tools</p>'); if (await p.textContent('p') !== 'Tools') throw new Error('leeg'); await b.close(); })" \
  || { echo "::error::Chromium start niet zonder de systeempakketten"; exit 1; }
