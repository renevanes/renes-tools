# Bijdragen aan Tools

Iedereen is welkom om bij te dragen: met een idee, een foutmelding of code. Mensen en AI-agents (Claude, Codex, …)
volgen dezelfde werkwijze. De technische details staan in [AGENTS.md](AGENTS.md).

*English: contributions are welcome. The app and its texts are in Dutch; issues and pull requests may be in English.
Read [AGENTS.md](AGENTS.md) for build, test and contribution rules. By contributing you agree that your contribution
is licensed under the GPL-3.0-or-later.*

## Een idee of fout melden
Open een [issue](https://github.com/renevanes/renes-tools/issues) en vertel wat je deed, wat je verwachtte en wat er
gebeurde (met Android-versie en telefoon). Zet **nooit** persoonlijke gegevens in een issue: geen telefoonnummers,
berichten, wachtwoorden of VPN-sleutels.

Een beveiligingsprobleem meld je niet in een openbaar issue maar via [SECURITY.md](SECURITY.md).

## Code bijdragen
1. Maak een fork en een branch (`git checkout -b mijn-onderwerp`).
2. Zet het gereedschap klaar (Linux x86_64 of WSL2; nodig: Java 17+, Python 3, Node 22+, `zip`, `openssl`, `curl`, `git`):
   ```sh
   export TOOLS=$HOME/toolchain      # waar het Android-gereedschap komt; zet dit ook voor build.sh en de tests
   ./setup-tools.sh
   npm i playwright && npx playwright install chromium
   ```
3. Maak een **testsleutel** om te kunnen bouwen (de echte sleutel heeft alleen Rene):
   ```sh
   echo testsleutel > keys/keystore.pass
   keytool -genkeypair -keystore keys/release.p12 -storetype PKCS12 -alias release \
     -storepass testsleutel -keypass testsleutel -keyalg RSA -keysize 2048 -validity 30 -dname "CN=test"
   ```
4. Bouw en test: `./build.sh`, `./tests/run-jvm.sh`, `./tests/run-ui.sh` (of losse tests met `./tests/ui-een.sh naam`).
   Zet daarna `update/` terug: `git checkout -- update`.
5. Beschrijf je wijziging voor de gebruiker in een nieuw bestand `wijzigingen/<onderwerp>.txt`: één regel per
   wijziging, in het Nederlands, zoals hij in "Wat is er nieuw" komt te staan.
6. Open een pull request. Pas **niet** aan: `VERSION`, `changelog.json` en `update/` (dat doet het publiceren; de
   automatische controle keurt het anders af).

Rene bekijkt elke pull request. Na het samenvoegen publiceert GitHub je wijziging vanzelf als nieuwe versie.

## Waar we op letten
- Teksten in de app zijn Nederlands, kort en duidelijk.
- Privacy: de app werkt met gevoelige gegevens (sms, oproepen, contacten, locatie). Gegevens blijven op de telefoon of
  gaan alleen naar een plek die de gebruiker zelf kiest. Geen analytics, advertenties of verborgen verbindingen.
- Werkt vanaf Android 7 (API 24), zonder Gradle; zie de coderegels in [AGENTS.md](AGENTS.md).
- Een nieuwe functie krijgt een test in `tests/ui/` (en bij logica in `tests/jvm/`).

## Licentie
Tools valt onder de [GNU General Public License versie 3 of later](LICENSE) (GPL-3.0-or-later). Door bij te dragen ga je ermee akkoord dat
jouw bijdrage onder dezelfde licentie valt.
