# Ontwikkelen en updates

[Terug naar het overzicht](../README.md)

## Onderhoud
- **Sinds gisteravond** (bovenaan het startscherm van de app): gemiste oproepen, de nachtelijke backup, automatiseringen die liepen, en problemen (batterijbeperking, mislukte backup-controle). *Gezien* verbergt het tot er iets nieuws is.
- **Backup-controle** (`Care.java`, dagelijkse taak): elke maand, en met *Nu controleren* bij Alles back-uppen. Controleert de backup-map, de leeftijd van de laatste backup en doet een proef-terugzetten: notities.json inlezen en de nieuwste contacten-vCard tellen, of het nieuwste versleutelde archief helemaal ontsleutelen met de bewaarde sleutel (`Secure.verify`, los van een archief dat je zelf open hebt). Er wordt niets teruggezet. Uitkomst als melding.
- **Batterijcontrole** (elke week): beperkt Android (ColorOS) Tools op de achtergrond, dan een melding met de weg naar de oplossing (zelftest).

## Versies en updates
- `VERSION` bevat het versienummer, de versiecode en `NATIVE_LEVEL`. Elke versie is in git één eigen commit met de naam "Versie X.Y".
- `changelog.json` bevat het wijzigingslog. De app toont dit onder *Versie en updates*.
- De app leest `update/update.json` van GitHub wanneer je hem opent, hooguit eens per 30 minuten. Via de GitHub-API zoekt hij eerst de nieuwste commit op, zodat een nieuwe versie direct zichtbaar is:
  - **Ondertekend**: `build.sh` ondertekent versie en controlegetallen met dezelfde sleutel als de APK (`webSig2`, SHA256withRSA). De app controleert die handtekening met zijn eigen certificaat en weigert een interface-update zonder geldige handtekening (`Updater.signatureOk`, test: `WebSigTest`).
  - **Alleen de interface is veranderd** (`web/src/`, `NATIVE_LEVEL` blijft gelijk): de nieuwe versie wordt stil gedownload en gecontroleerd met SHA-256. Complete versies staan elk in een eigen map; één atomaire verwijzing kiest de actieve versie. De huidige pagina wordt pas herladen zodra bewerkingen bewaard zijn. Anders blijft de update klaarstaan voor de volgende start.
  - **`NATIVE_LEVEL` is hoger** (de Java-code of het manifest is veranderd): de app toont *Nieuwe versie beschikbaar* met een knop **Installeren**. Die downloadt de APK, controleert hem en biedt hem aan via de pakketinstaller van Android.
  - **Voortgang**: bij het downloaden van een nieuwe app toont de balk op Home en bij *Versie en updates* procenten en MB (`UpdateDownload` met `Progress`, hooguit 5 keer per seconde via `Updater.Throttle`, test: `UpdateProgressTest`), daarna *Controleren*, *Klaarzetten* en *Bevestig de installatie in het venster van Android*. Een tweede tik start geen tweede download (`APK_BUSY`). Een interface-update laat ook kort zien hoe ver hij is (zonder knop, en op de knop *Controleren op updates*). Een schermlezer hoort alleen de fasen, niet elk procent (`update-voortgang.test.js`).

## Bouwen (zonder Android Studio of Gradle)

Gebruik Java 17 of nieuwer, Python 3, Node 22 of nieuwer en het Android-gereedschap van het project. De volledige installatie met een tijdelijke testsleutel staat in [CONTRIBUTING.md](../CONTRIBUTING.md).

```sh
./setup-tools.sh
npm ci
npx playwright install chromium
./build.sh
./tests/run-jvm.sh
./tests/run-ui.sh
# build.sh schrijft ook testuitvoer in update/: die hoort niet in je commit.
git restore -- update
./bump.sh "Wat er voor de gebruiker veranderd is"
```

`bump.sh` schrijft een wijzigingsbestand; het versienummer wordt alleen door `publish.sh` bepaald. Commit en push je werk volgens [AGENTS.md](../AGENTS.md). Op main test Publiceren eerst precies die broncommit in emulatoren op API 24, 35 en 36. Die jobs hebben geen ondertekeningssleutel. Daarna controleert de publicatiestap onder het publicatieslot opnieuw of de bron nog gelijk is. Nieuwere appcode of testcode vereist een nieuwe emulatorcontrole; alleen gegenereerde publicatiebestanden mogen intussen verschillen.

Alle workflows gebruiken `npm ci` en dezelfde `package-lock.json`. Een afhankelijkheid bijwerken betekent `package.json` én het lockbestand aanpassen en de tests opnieuw draaien. Lokaal publiceren met de echte sleutel volgt dezelfde repositoryafspraken; `TESTED_SOURCE_SHA` legt vast welke bron de telefoontest heeft doorstaan. De GitHub-publicatie vereist deze waarde.

Opslag en updates zijn opgesplitst in kleine onderdelen: zie [Brug en opslag](brug-en-opslag.md).

### Interface in onderdelen
De interface is één pagina (`web/index.html`) voor de WebView, maar wordt bewerkt in losse onderdelen in `web/src/`:
`stijl.css`, `schermen/NN-*.html` (één per scherm), `vensters.html` (meldingen, dialogen) en `script/NN-*.js` (per tool; `01-testbrug.js` is de nagebootste Android-brug voor tests in de browser).
`tools/web-samenvoegen.py` voegt ze in de volgorde van `web/src/volgorde.txt` samen; `build.sh` en `tests/run-ui.sh` doen dat zelf. `web/index.html` wordt dus gemaakt en staat niet in git.

### Testen
```sh
./tests/run-jvm.sh           # alle Java-logicatests (tests/jvm/**/*Test.java; nieuwe tests doen vanzelf mee)
./tests/run-ui.sh            # controle van de brug (tools/check-bridge.py) en de interfacetests: Playwright, 390x844,
                             # licht en donker, met nagebootste Android-brug. Een test mislukt bij een fout in de pagina of een mislukte assert.
```
De app zelf wordt zonder nagebootste brug gebouwd (`web-samenvoegen.py --zonder-testbrug`).
Bij elke push bouwt GitHub Actions (`.github/workflows/bouwen-en-testen.yml`) de app en draait alle tests. Die APK is ondertekend met een tijdelijke testsleutel en dient alleen als controle; echte updates publiceert de workflow `Publiceren` (`.github/workflows/publiceren.yml`) vanzelf bij elke wijziging aan de app op main, met de echte sleutel uit het geheim `SIGNING_PASSPHRASE` in de GitHub-omgeving `publiceren`. Lokaal kan ook `./publish.sh`.

De ondertekeningssleutel staat alleen versleuteld in de repository (`keys/signing-key.enc`, AES-256). Rene heeft de wachtwoordzin. Elke update moet met dezelfde sleutel ondertekend zijn, anders kan de telefoon hem niet installeren.

Let op bij Java-code: gebruik geen anonieme of niet-statische binnenklassen. javac 21 geeft hun constructor een parameter zonder naam, en d8 8.2 loopt daarop vast. Gebruik statische geneste klassen of lambda's.
