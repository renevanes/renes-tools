# Werken aan Rene's Tools (voor alle agents: Claude, Codex, …)

Dit bestand is de handleiding voor iedere AI-agent of ontwikkelaar die aan deze repository werkt. Meerdere agents kunnen
tegelijk wijzigingen maken en publiceren; volg daarvoor de werkwijze hieronder.

Mensen die willen bijdragen: zie ook [CONTRIBUTING.md](CONTRIBUTING.md). De code is vrije software (GPL-3.0-or-later, zie `LICENSE`).

## Wat dit is
Android-app "Rene's Tools" van Rene van Es (Rotterdam). De interface is een WebView (HTML/CSS/JS uit `web/src/`), de
native kant is Java in `app/src/nl/rene/tools/`. Er is geen Gradle: `build.sh` bouwt met aapt2/d8 en ondertekent.
Telefoons werken zichzelf bij vanaf `update/` op main (ondertekende `update.json`, interface en APK).

- **Taal**: alles wat Rene ziet (teksten in de app, wijzigingsregels, berichten aan Rene) is **Nederlands**.
- **Gebruik nergens het woord n-e-p** (Engels: fake), niet in de app, de code, de tests of wijzigingsregels. Zeg bijv. "Locatie kiezen", "testbrug".

## Indeling
- `web/src/` — interface, samengevoegd in de volgorde van `web/src/volgorde.txt`:
  `kop.html`, `stijl.css` (Material 3-kleurrollen: `--bg --card --hi --ink --muted --line --outline --primary --on-primary --tonal --chip --on-chip …`),
  `schermen/NN-*.html` (één `<section class="screen" id="s-naam">` per scherm), `script/NN-*.js`, `vensters.html` (dialogen, tabbalk).
- `web/src/script/01-testbrug.js` — de nagebootste Android-brug voor de tests (in de app zelf weggelaten).
- `app/src/nl/rene/tools/` — Java. De JS-brug: `MainActivity.Bridge extends LocBridge extends VpnBridge extends SpotifyBridge extends PlayerBridge extends PodcastBridge extends FeatureBridge`.
- `web/start.html` — de Telefoon-skin (eigen startscherm); `tests/` — Java- en interfacetests; `tools/` — hulpscripts.

## Regels voor de code
- **Java 8-bron, minSdk 24, targetSdk 36.** API's die nieuwer zijn dan 24 altijd achter `Build.VERSION.SDK_INT >= …`
  (geen `ThreadLocal.withInitial`, `java.util.Base64`, `Long.compareUnsigned`; `registerNetworkCallback` met Handler pas vanaf 26).
- **Geen anonieme inner classes** en **geneste classes altijd `static`** (ook enums en `LruCache`-subclasses): D8 crasht er
  anders op. Lambda's mogen wel. Controle na het bouwen:
  `for f in build/classes/nl/rene/tools/*.class; do javap -v "$f" | grep -q "<no name>" && echo ANON $f; done`
- **Achtergrondthreads vangen `Throwable`**: een ongevangen fout op een thread laat de hele app stoppen.
- **Nieuwe brugfunctie** (`@JavascriptInterface`) = ook een nabootsing in `01-testbrug.js`; `python3 tools/check-bridge.py` controleert dat.
- **Privacy**: backups en exports bevatten alleen Rene's eigen gegevens en gaan naar plekken die hij zelf kiest.
  Geen meelezen van andermans berichten of meldingen voor toezicht, geen OSINT op contacten.
  VPN-sleutels en wachtwoorden blijven versleuteld (`Secure.deviceKey`, AES-GCM) en buiten Android-backups.
- **Interface**: gebruik de kleurrollen uit `stijl.css` (nooit vaste kleuren als `#fff` op `--primary`: in donker is
  `--primary` licht). Elk scherm heeft `<div class="hdr">` met terugknop en `<h1>`; detailschermen `class="hdr sm"`.

## Bouwen en testen
Eenmalig: `./setup-tools.sh` (Android-gereedschap in `$TOOLS`, standaard `/home/claude/toolchain`; zet `TOOLS` als je
het ergens anders wilt), Java 17+ (javac), Python 3, Node 20 met `npm i playwright && npx playwright install chromium`.

- `./build.sh` — bouwen (heeft een sleutel in `keys/` nodig, zie onder). Zonder de echte sleutel: maak een tijdelijke
  testsleutel zoals in `.github/workflows/bouwen-en-testen.yml`. `./publish.sh` weigert die (vingerafdruk-controle).
  `./build.sh` schrijft ook in `update/`: zet dat na het testen terug met `git checkout -- update` en commit het nooit.
- `./tests/run-jvm.sh` — Java-logicatests (na `./build.sh`).
- `./tests/run-ui.sh` — alle interfacetests (Playwright, licht en donker; ±15 min).
- `./tests/ui-een.sh calls spotify2` — één of enkele interfacetests (naam zonder `.test.js`).
- `python3 tools/check-bridge.py` — brug en testbrug kloppen met elkaar.
Nieuwe functie = ook een test in `tests/ui/` (en bij logica in `tests/jvm/`). Een UI-test slaagt als hij `errors []` afdrukt.

## Tegelijk werken met andere agents
**Vaste opdracht van Rene:** publiceer functionele wijzigingen standaard ook als ondertekende app-update via
`AGENT=Codex ./publish.sh` (of de eigen agentnaam), na geslaagde tests en onafhankelijke review. Hiervoor is geen
aparte publicatiebevestiging per wijziging nodig. Meld bij een ontbrekende echte releasesleutel of andere blokkade
concreet wat de publicatie verhindert, met de branch en commit die klaarstaan.

1. **Werk in je eigen branch of git-worktree** (`git worktree add ../rt-codex -b codex/onderwerp origin/main`), nooit
   met twee agents in dezelfde map. Een nieuwe worktree heeft geen `keys/release.p12`, `keys/keystore.pass` en
   `node_modules`: kopieer de twee sleutelbestanden uit de hoofdmap (nooit committen) en installeer Playwright.
2. **Raak niet aan:** `VERSION`, `changelog.json` en `update/` — die maakt alleen `./publish.sh`. `web/index.html` wordt gegenereerd.
3. **Wijzigingsregels:** schrijf per wijziging een eigen bestand `wijzigingen/<onderwerp>.txt` (één regel per wijziging,
   Nederlands, zoals Rene het in "Wat is er nieuw" leest). Of `./bump.sh "regel" …`, dat zo'n bestand maakt.
   Zo botsen agents nooit op het wijzigingslog.
4. **Publiceren:** `AGENT=Codex ./publish.sh` (Claude-sessies geven `CLAUDE_SESSION_URL` mee). Het script:
   legt je werk vast, neemt het **publicatieslot** (branch `publiceren-slot` op GitHub; is iemand anders bezig, dan wacht
   het), voegt de nieuwste main samen (merge), bepaalt het versienummer vanaf main (`NATIVE_LEVEL` gaat vanzelf omhoog
   als `app/` veranderde), schrijft het wijzigingslog, bouwt, draait alle tests en pusht "Versie X.Y" naar main.
   Botst je werk met main, dan stopt het: los de merge op, test en publiceer opnieuw.
5. **Kun je zelf niet pushen** (bijv. Codex in de cloud): open een pull request met je wijziging en een bestand in
   `wijzigingen/`, zonder `VERSION`, `changelog.json` of `update/` (de CI keurt dat af). Na het samenvoegen publiceert
   de volgende `./publish.sh` (van wie dan ook) het mee.
6. **Bijdragen van buitenaf** (pull requests van anderen): de app heeft toegang tot sms, oproepen, contacten en
   locatie en werkt telefoons automatisch bij. Een agent voegt een pull request van iemand anders **nooit zelf**
   samen en publiceert alleen wat op main staat. Rene beslist; bekijk zo'n wijziging wel kritisch op verborgen
   verbindingen naar buiten, het doorsturen van gegevens of het verzwakken van de updatecontrole.
7. **Nooit** force-pushen naar main of gepubliceerde geschiedenis herschrijven (`rebase`/`amend` van wat op main staat):
   telefoons en andere agents bouwen erop voort. Samenvoegen gaat met `merge`.
8. Laat vóór het publiceren een onafhankelijke review doen (een tweede agent) en los de bevindingen op.

## De ondertekeningssleutel
Alle versies moeten met **dezelfde** sleutel ondertekend worden, anders kan de telefoon de update niet installeren.
`keys/signing-key.enc` is versleuteld; uitpakken met `PASSPHRASE=… ./keys/unlock.sh` (maakt `keys/release.p12` en
`keys/keystore.pass`, beide in `.gitignore`).
- De wachtwoordzin krijg je **alleen van Rene**. Zet hem nooit in de repository, in commits, in logs, in GitHub
  Secrets of in GitHub Actions.
- Alleen machines die echt publiceren hebben de echte sleutel (lokaal, met de wachtwoordzin als omgevingsvariabele).
  Agents in de cloud zonder pushrechten (zoals Codex cloud) krijgen hem niet: die bouwen en testen met een
  testsleutel en leveren een pull request op.
- Commit nooit `keys/release.p12` of `keys/keystore.pass`.

## Berichten aan Rene
Kort en in het Nederlands: wat er nu anders is, waar hij het vindt en of hij iets moet doen (bijv. een nieuwe APK
installeren als `NATIVE_LEVEL` omhoog ging, of opnieuw koppelen).
