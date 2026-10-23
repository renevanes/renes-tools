# Werken aan Tools (voor alle agents: Claude, Codex, …)

Dit bestand is de handleiding voor iedere AI-agent of ontwikkelaar die aan deze repository werkt. Meerdere agents kunnen
tegelijk wijzigingen maken en publiceren; volg daarvoor de werkwijze hieronder.

Mensen die willen bijdragen: zie ook [CONTRIBUTING.md](CONTRIBUTING.md). De code is vrije software (GPL-3.0-or-later, zie `LICENSE`).

## Wat dit is
Android-app "Tools" van Rene van Es (Rotterdam). De interface is een WebView (HTML/CSS/JS uit `web/src/`), de
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
het ergens anders wilt), Java 17+ (javac; CI gebruikt 25), Python 3, Node 22+ (CI gebruikt 24) met `npm i playwright && npx playwright install chromium`.

- `./build.sh` — bouwen (heeft een sleutel in `keys/` nodig, zie onder). Zonder de echte sleutel: maak een tijdelijke
  testsleutel zoals in `.github/workflows/bouwen-en-testen.yml`. `./publish.sh` weigert die (vingerafdruk-controle).
  `./build.sh` schrijft ook in `update/`: zet dat na het testen terug met `git checkout -- update` en commit het nooit.
- `./tests/run-jvm.sh` — Java-logicatests (na `./build.sh`).
- `./tests/run-ui.sh` — alle interfacetests (Playwright, licht en donker; ±15 min).
- `./tests/ui-een.sh calls spotify2` — één of enkele interfacetests (naam zonder `.test.js`).
- `python3 tools/check-bridge.py` — brug en testbrug kloppen met elkaar.
Nieuwe functie = ook een test in `tests/ui/` (en bij logica in `tests/jvm/`). Een UI-test slaagt als hij `errors []` afdrukt.

## Tegelijk werken met andere agents
**Publiceren gaat vanzelf (zoals bij renes-spelletjes):** elke push naar main die de app verandert, wordt door de
GitHub-workflow `Publiceren` (`.github/workflows/publiceren.yml`) getest, gebouwd, ondertekend en gepubliceerd, één
tegelijk. Je hebt de sleutel dus **niet** nodig: zet je werk (met een bestand in `wijzigingen/`) op main en klaar.
**Vaste opdracht van Rene:** publiceer functionele wijzigingen standaard, na geslaagde tests en een onafhankelijke
review; een aparte bevestiging per wijziging is niet nodig. Kijk na het pushen of de run `Publiceren` slaagt (tabblad
Actions, of `gh api repos/renevanes/renes-tools/actions/runs`) en meld Rene de nieuwe versie, of concreet wat misging.

1. **Werk in je eigen branch of git-worktree** (`git worktree add ../rt-codex -b codex/onderwerp origin/main`), nooit
   met twee agents in dezelfde map. Een nieuwe worktree heeft geen `node_modules` (installeer Playwright) en geen
   `keys/release.p12`/`keys/keystore.pass`; die heb je alleen nodig als je lokaal met `./publish.sh` publiceert
   (kopiëren uit de hoofdmap, nooit committen).
2. **Raak niet aan:** `VERSION`, `changelog.json` en `update/` — die maakt alleen `./publish.sh`. `web/index.html` wordt gegenereerd.
3. **Wijzigingsregels:** schrijf per wijziging een eigen bestand `wijzigingen/<onderwerp>.txt` (één regel per wijziging,
   Nederlands, zoals Rene het in "Wat is er nieuw" leest). Of `./bump.sh "regel" …`, dat zo'n bestand maakt.
   Zo botsen agents nooit op het wijzigingslog.
4. **Publiceren:** push naar main (`git push origin HEAD:main`, na `git pull` / merge van de nieuwste main); de workflow
   doet de rest. Een push wordt geweigerd als main intussen verder is: `git pull` (merge, geen rebase) en opnieuw.
   Lokaal mag ook `AGENT=Codex ./publish.sh` als je de echte sleutel hebt (Claude-sessies geven `CLAUDE_SESSION_URL`
   mee). De workflow gebruikt hetzelfde script. Het script:
   legt je werk vast, neemt het **publicatieslot** (branch `publiceren-slot` op GitHub; is iemand anders bezig, dan wacht
   het), voegt de nieuwste main samen (merge), bepaalt het versienummer vanaf main (`NATIVE_LEVEL` gaat vanzelf omhoog
   als `app/` veranderde), schrijft het wijzigingslog, bouwt, draait alle tests en pusht "Versie X.Y" naar main.
   Botst je werk met main, dan stopt het: los de merge op, test en publiceer opnieuw.
5. **Kun je zelf niet pushen** (bijv. Codex in de cloud): open een pull request met je wijziging en een bestand in
   `wijzigingen/`, zonder `VERSION`, `changelog.json` of `update/` (de CI keurt dat af). Na het samenvoegen publiceert
   de workflow het vanzelf.
6. **Bijdragen van buitenaf** (pull requests van anderen): de app heeft toegang tot sms, oproepen, contacten en
   locatie en werkt telefoons automatisch bij. Een agent voegt een pull request van iemand anders **nooit zelf**
   samen: alles wat op main komt, staat binnen ±15 minuten op Rene's telefoon. Rene beslist; bekijk zo'n wijziging wel kritisch op verborgen
   verbindingen naar buiten, het doorsturen van gegevens of het verzwakken van de updatecontrole.
7. **Nooit** force-pushen naar main of gepubliceerde geschiedenis herschrijven (`rebase`/`amend` van wat op main staat):
   telefoons en andere agents bouwen erop voort. Samenvoegen gaat met `merge`.
8. Laat vóór het publiceren een onafhankelijke review doen (een tweede agent) en los de bevindingen op.

## De ondertekeningssleutel
Alle versies moeten met **dezelfde** sleutel ondertekend worden, anders kan de telefoon de update niet installeren.
`keys/signing-key.enc` is versleuteld. De wachtwoordzin staat alleen als geheim `SIGNING_PASSPHRASE` in de GitHub-omgeving
`publiceren` (alleen branch main mag erbij; dat zet Rene). De workflow `Publiceren` pakt er de sleutel mee uit, pas
nadat het gereedschap is geïnstalleerd, en haalt hem na afloop weer weg.
- Zet de wachtwoordzin nooit in de repository, in commits, in logs of in andere workflows, en druk hem nooit af.
- Lokaal publiceren kan ook: `PASSPHRASE=… ./keys/unlock.sh` (maakt `keys/release.p12` en `keys/keystore.pass`, in
  `.gitignore`), dan `./publish.sh`. `./publish.sh` controleert de vingerafdruk, dus met een testsleutel publiceer je niet.
- Commit nooit `keys/release.p12` of `keys/keystore.pass`.
- Workflows die door pull requests van buitenaf draaien (`pull_request`), krijgen het geheim niet. Gebruik nooit
  `pull_request_target` met het geheim en zet nergens anders `environment: publiceren`.
- Groeit de APK meer dan 10% of moet de hoofdversie omhoog: start de workflow met de hand (Actions → Publiceren →
  Run workflow) met `allow_grow` of `major`.

## Onderhoud: updates en beveiliging
Dit gaat grotendeels vanzelf; een agent doet wekelijks (maandagochtend) het deel dat niet vanzelf kan.

**Vanzelf (GitHub):**
- **Dependabot** (`.github/dependabot.yml`) opent elke maandag één pull request met updates van de GitHub Actions.
  Die worden niet vanzelf samengevoegd: dezelfde Actions draaien in `publiceren.yml` naast de ondertekeningssleutel.
- **Beveiligingsscan** (`beveiligingsscan.yml`): CodeQL op Java en JavaScript bij pushes naar main en pull requests die
  `app/` of `web/` veranderen, en wekelijks. Meldingen staan onder Security → Code scanning. (GitHub's
  "default setup" van CodeQL moet uit staan, anders botst die met deze workflow.)
- **Onderhoud** (`onderhoud.yml`, maandag): `.github/scripts/onderhoud-controle.py` vergelijkt de meegeleverde
  bibliotheken (Leaflet, jsQR, whisper.cpp, llama.cpp) en het gereedschap (build-tools, android.jar/targetSdk, aapt2,
  zig, Playwright, Node, Java) met de nieuwste versies
  en met de OSV-kwetsbaarhedendatabase, en zet alles wat te doen is in het issue "Onderhoud: updates en beveiliging".

**Alle onderdelen van buitenaf** (houd deze lijst bij als er iets bijkomt; `onderhoud-controle.py --bestanden` moet
alles kunnen lezen, dat controleert Bouwen en testen):

| Onderdeel | Waar vastgelegd | Gecontroleerd door | Bijwerken |
|---|---|---|---|
| GitHub Actions | `.github/workflows/*.yml` | Dependabot (wekelijks PR) | agent voegt samen na review |
| Leaflet, jsQR (in de app) | `web/vendor/` (kopregel) | Onderhoud: npm + OSV | agent, bij elke nieuwe versie |
| whisper.cpp (transcriberen) | `app/lib/arm64-v8a/README.txt` | Onderhoud: GitHub-release + OSV | agent: `tools/whisper-build.sh`, bij elke nieuwe release |
| llama.cpp (lokale AI) | `tools/llm-build.sh` (TAG/COMMIT) + README | Onderhoud: GitHub-release + OSV | agent: bij kwetsbaarheid of >1500 commits achter (dagelijkse releases) |
| zig (bouwt whisper/llama) | `ZIG_VERSION` in beide bouwscripts | Onderhoud: PyPI | agent, samen met een nieuwe whisper/llama-bouw |
| Android build-tools (d8, apksigner, zipalign) | `setup-tools.sh` | Onderhoud: Google + androidide-tools | agent; wisselen van d8 vraagt een test op een telefoon |
| android.jar en targetSdk | `setup-tools.sh`, `build.sh` | Onderhoud: Google | agent; gedragswijzigingen van de nieuwe Android-versie nalopen en op een telefoon testen |
| aapt2 | `setup-tools.sh` (aaptjs3) | Onderhoud: npm | agent |
| Playwright (alleen tests) | `publiceren.yml` | Onderhoud: npm | agent |
| Node en Java (alleen CI-gereedschap) | `node-version`/`java-version` in de workflows | Onderhoud: Node-schema, Adoptium | agent, naar de nieuwste LTS |
| Modellen (whisper ggml, Qwen3 GGUF) | `Transcribe.java`, `LocalAi.java` (vaste SHA-256/revisie) | niet automatisch: bewust vast | alleen op verzoek van Rene (een ander model is een productkeuze) |

Nieuwe APK nodig (Rene installeert die zelf): bij alles in `app/` (whisper, llama, Java), dus niet bij Actions,
Playwright, Node of Java van de CI.

**Wekelijks door een agent** (het issue hierboven is de takenlijst):
1. Werk in een eigen branch/worktree `onderhoud/JJJJ-MM-DD`. Draai lokaal ook `python3 .github/scripts/onderhoud-controle.py`
   (lukt dat niet door netwerkbeperkingen, gebruik dan het rapport in het issue).
2. **Kwetsbaarheden eerst.** Elke CodeQL-melding (in het issue; Claude-sessies kunnen de Security-API's niet zelf
   lezen, de workflow Onderhoud wel): oplossen. Is het echt een vals-positief, zet dat dan met de reden in het issue;
   afwijzen in Security → Code scanning doet Rene (of een agent die die API wel mag gebruiken).
   Loop daarnaast zelf de code na die sinds het vorige onderhoud veranderde (`git log --since="8 days ago"`) op de
   privacyregels hierboven, brugfuncties die te veel toestaan, onveilige WebView-instellingen, verbindingen naar buiten
   en de updatecontrole.
3. **Bibliotheken bijwerken:** Leaflet/jsQR uit het npm-pakket (`dist/`, met de bestaande kopregel en licentie);
   Playwright-versie in `publiceren.yml`; whisper.cpp met `tools/whisper-build.sh` (beide varianten, README.txt bijwerken,
   testen onder qemu-aarch64); llama.cpp met `tools/llm-build.sh` (TAG en COMMIT erin, daarna `./tools/llm/test.sh`);
   de rest en het tempo volgens de tabel hierboven.
   **Dependabot-pull requests** (uitzondering op regel 6, want die zijn van GitHub zelf, niet van iemand anders):
   alleen samenvoegen als ze uitsluitend `.github/workflows/` raken, "Bouwen en testen" groen is en je de release
   notes hebt nagelezen, en extra kritisch zijn bij alles wat in `publiceren.yml` draait (de sleutel is daar actief).
   Bij grote versies: pas de workflows aan waar nodig.
4. Bouwen, alle tests (`run-jvm.sh`, `run-ui.sh`), onafhankelijke review door een tweede agent, bevindingen oplossen.
5. Wijzigingsregel(s) in `wijzigingen/onderhoud-JJJJ-MM-DD.txt` (alleen als de app verandert), push naar main zoals
   hierboven; controleer dat Publiceren slaagt. (Publiceren start alleen als `app/`, `web/`, `tools/`, `build.sh` of
   `publiceren.yml` veranderde; bij alleen andere workflows of scripts komt er geen run en geen nieuwe versie.) Is er niets veilig te doen, laat het dan staan en zet in het issue waarom.
6. Meld Rene kort: wat bijgewerkt is, wat opgelost is, en wat bewust is blijven liggen.
Nooit in het kader van onderhoud: de updatecontrole, de sleutel of `publiceren.yml`-beveiliging verzwakken, of
pull requests van anderen (mensen of andere bots dan Dependabot) samenvoegen.

## Berichten aan Rene
Kort en in het Nederlands: wat er nu anders is, waar hij het vindt en of hij iets moet doen (bijv. een nieuwe APK
installeren als `NATIVE_LEVEL` omhoog ging, of opnieuw koppelen).
