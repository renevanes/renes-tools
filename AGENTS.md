# Werken aan Tools (voor alle agents: Claude, Codex, …)

Dit bestand is de handleiding voor iedere AI-agent of ontwikkelaar die aan deze repository werkt. Meerdere agents kunnen
tegelijk wijzigingen maken en publiceren; volg daarvoor de werkwijze hieronder.

Mensen die willen bijdragen: zie ook [CONTRIBUTING.md](CONTRIBUTING.md). De code is vrije software (GPL-3.0-or-later, zie `LICENSE`).

## Wat dit is
Android-app "Tools" van Rene van Es (Rotterdam). De interface is een WebView (HTML/CSS/JS uit `web/src/`), de
native kant is Java in `app/src/nl/rene/tools/`. Er is geen Gradle: `build.sh` bouwt met aapt2/d8 en ondertekent.
Telefoons werken zichzelf bij vanaf `update/` op main (ondertekende `update.json`, interface en APK).

- **Taal**: wijzigingsregels en berichten aan Rene zijn **Nederlands**. De app zelf kan Nederlands (standaard) of Engels
  (Instellingen → Regio en taal → Taal; de app volgt de taal van de telefoon bewust niet). Nederlands is de brontekst in de
  code; Engels komt uit één woordenboek, `web/src/taal/en.json` (zie "Teksten en vertalingen" hieronder).
- **Gebruik nergens het woord n-e-p** (Engels: fake), niet in de app, de code, de tests of wijzigingsregels. Zeg bijv. "Locatie kiezen", "testbrug".

## Indeling
- `web/src/` — interface, samengevoegd in de volgorde van `web/src/volgorde.txt`:
  `kop.html`, `stijl.css` (Material 3-kleurrollen: `--bg --card --hi --ink --muted --line --outline --primary --on-primary --tonal --chip --on-chip …`),
  `schermen/NN-*.html` (één `<section class="screen" id="s-naam">` per scherm), `script/NN-*.js`, `vensters.html` (dialogen, tabbalk).
- `web/src/script/01-testbrug.js` — de nagebootste Android-brug voor de tests (in de app zelf weggelaten).
- `app/src/nl/rene/tools/` — Java. De JS-brug: `MainActivity.Bridge extends ReadAloudBridge extends NieuwsBridge extends EreaderBridge extends ToilettenBridge extends MasterBridge extends BankBridge extends FacturenBridge extends GebruikBridge extends LocBridge extends VpnBridge extends SpotifyBridge extends PlayerBridge extends PodcastBridge extends AiBridge extends SleepBridge extends CheckinBridge extends ApparatenBridge extends FeatureBridge`.
- `web/start.html` — de Telefoon-skin (eigen startscherm); `tests/` — Java- en interfacetests; `tools/` — hulpscripts.
- **Iedere nieuwe schermtool ondersteunt standaard losse apps.** Registreer hem in `web/src/script/00-tools.js`: het
  openen, het overzicht Losse apps en het native register volgen daaruit. Subschermen krijgen een `BACK`-verwijzing
  naar hun eigen tool. `solo-alle-tools.test.js` en `SoloTest` bewaken dit; behoud de eigen ontgrendeling en privacy.
 Zo doet hij ook vanzelf mee met **Extra ontgrendelen** (per tool aan of uit, `26b-extra-ontgrendelen.js`, `ToolSlot`):
 elk scherm hoort via `BACK` bij zijn tool. Toont een ander scherm (zoeken, het startscherm) gegevens uit een tool, verberg
 ze dan met `tsVerborgen(tool)` zolang die tool op slot is. Opent code na `show(tool)` meteen iets in die tool (een vraag
 uit een melding, een zoekactie), zet dat in `tsDaarna(tool, () => …)`: dan wacht het tot na ontgrendelen. Een nieuw
 venster dat over een tool kan liggen, hoort in `TS_LAGEN` (terug op het slotscherm sluit het); inert onder een venster
 zet je met `dialogFocus.base(el, aan)`.
  Alleen de telefoon-skin gebruikt zijn bestaande `HomeActivity`. Zie `docs/startscherm-en-automatiseringen.md`.
- **Iedere tool doet standaard mee in de backup** (nu en later): zie "Backups: elke tool doet mee" hieronder.

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
  Ook **Statistieken voor verbeteringen** (`Statistiek.java`, Instellingen → Foutrapport en statistieken) volgt dat: het
  gaat alleen naar de privé GitHub-repository die Rene zelf instelt. Het staat standaard aan, maar zonder ingestelde repository en sleutel gaat er niets weg. Erin mogen alleen
  tellingen en versies: per vaste logsoort (`App.log`-naam zoals BACKUP) een aantal, en bij een vastloper het soort fout en
  de plek in de eigen code. Nooit foutteksten, namen, nummers, berichten, bestandsnamen, adressen of locaties
  (`StatistiekTest` controleert dat). Iets toevoegen aan het rapport = eerst Rene vragen en de test uitbreiden.
  VPN-sleutels en wachtwoorden blijven versleuteld (`Secure.deviceKey`, AES-GCM) en buiten Android-backups.
  Alleen "Alle appgegevens" (`AppBackup`/`AppData`, terugzetten na herinstallatie) neemt ze mee, en alleen binnen het
  versleutelde archief (.rtb, wachtwoord van de backup); bij terugzetten worden ze opnieuw versleuteld met de sleutel van
  de nieuwe installatie. Alle gegevens van elke tool gaan **standaard mee** in de backup; zie "Backups: elke tool doet mee".
- **Regio en taal**: datums, tijden, getallen, bedragen en eenheden die Rene ziet, altijd via de helpers in
  `web/src/script/02-regio.js` (`fmtDate`, `fmtTime`, `fmtDT`, `fmtHm`, `fmtNum`, `fmtMoney`, `fmtDist`, `fmtTemp`, `fmtSize`, …) of
  `Regio.java` (`Regio.df`, `Regio.fmt`, `Regio.num`, `Regio.dist`, `Regio.deg`, `Regio.size`), nooit zelf met
  `toLocaleString('nl-NL')`, `toFixed().replace('.', ',')` of `SimpleDateFormat` met Nederlands. Machinale notaties (ISO voor
  opslag, bestandsnamen, parameters, tekst voor het taalmodel) blijven vast. `tests/ui/regio-guard.test.js` bewaakt dat
  (met een lijst uitzonderingen en de reden). Thuis, werk en de standaardplaats: `rgPlace()` / `Regio.home()`; thuis en
  werk staan alleen in Java (`regio.xml`, niet in localStorage/webstore en niet in de Android-backup). Getallen uit een
  invoerveld: `parseNum()`, vooraf invullen met `fmtNumIn()`.
  **Tijdzone:** standaard volgt de app de telefoon; kiest Rene een zone (Regio en taal → Tijdzone), dan rekent de hele
  app in die zone, de app-zone. Daglogica (vandaag, gisteren, per dag/week/maand) volgt de app-zone: `rgDayKey`, `rgToday`,
  `rgIsToday`, `rgIsYesterday`, `rgStartOfDay`, `rgParts` (interface) en `Regio.zone`, `Regio.cal`, `Regio.dayKey`,
  `Regio.isToday` (Java), nooit `toDateString`, `new Date(j, m, d)`, `setHours`, `Calendar.getInstance()` of
  `TimeZone.getDefault()`. Tijden uit de opslag ("07:30": wekkers, check-in, planningen, thema) zijn wandklok in de
  app-zone: plannen met `rgFromLocal`/`rgNextAt` of `Regio.toMillis`/`Regio.nextAt` (een tijd die door de zomertijd niet
  bestaat schuift mee: 02:30 wordt 03:30; een tijd die bij de wintertijd twee keer bestaat is de eerste keer, overal:
  interface, `Regio`, `Checkin.at`, `Facturen.gepland`). WebView (Intl) en Android (Java) hebben elk een eigen
  tijdzonedatabase die los bijgewerkt wordt: een zone telt alleen als beide hem kennen (`rgTzOk` vraagt `regioTzOk`), en
  de interface geeft Java en de skin de zone die hij echt gebruikt (`rgTzState().sel`). Invoervelden: vooraf invullen met `rgLocalIn`/`rgHm`, lezen met
  `rgFromLocalIn`/`rgFromLocal`. Datum-alleen-waarden ('2026-11-03') verschuiven nooit: `fmtDate('2026-11-03')`.
  Bewust telefoontijd: bestandsnamen en logboek, de nachtelijke taken (backups, uitschrijven), schermtijd (Gebruik) en wat
  buiten de app gebeurt (Klok-app, agenda-app: hetzelfde moment). Een andere zone zet alle wekkers opnieuw (`regioSet`).
  `regio-guard.test.js` bewaakt ook dit (met een lijst uitzonderingen per regel en de reden).
  Tekst voor het taalmodel blijft vast nl-NL (niet via de helpers). In het Engels geeft `regioLocale()` en-GB, met je eigen scheidingstekens.
- **Teksten en vertalingen** (Engels): schrijf elke tekst in het Nederlands en
  - in JavaScript in `t('…')`, met plaatshouders `t('{n} berichten gewist', { n })` en meervoud
    `tn(n, 'Eén bericht', '{n} berichten')`; nooit aan elkaar plakken (`'Klaar: ' + x` → `t('Klaar: {wat}', { wat: x })`),
    ook niet in HTML-stukjes of aria-labels. Vaste tekst in de schermen (HTML) vertaalt de observer in `02-taal.js` vanzelf;
    gegevens van Rene of anderen (notities, berichten, namen, titels, bestandsnamen) in een element met `translate="no"`
    (`engels.test.js` zet daarvoor gegevens die precies een vertaalde tekst zijn, zoals een contact "Instellingen", en
    controleert dat ze Nederlands blijven). Namen die je later weer uit het scherm leest: uit de gegevens halen, niet uit de DOM.
  - let op: de observer vertaalt een "eenheid" (een element met alleen tekst en opmaak als `<b>`, `<br>`, `<small>`) in één
    keer via `innerHTML`. Wat daarbinnen staat, wordt dan opnieuw gemaakt: event-listeners, verwijzingen naar die
    kinderen en ingevulde invoervelden gaan verloren. Zet zo'n element dus niet met de hand vast in een eenheid, maar
    bouw het met `t()` of geef de knoppen een eigen element buiten de tekst (of `translate="no"` als het gegevens zijn).
  - één Nederlands woord met per plek een andere Engelse betekenis ("Klaar": Done of Ready, "Opslaan": Save of Storage):
    `tc('status', 'Klaar')` in JavaScript, `data-taal="plek"` op het element in HTML; in `en.json` staat dan
    `"Klaar @status": "Ready"` naast `"Klaar": "Done"`.
  - in Java `Taal.t(ctx, "…")` / `Taal.tn(ctx, n, "…", "…")`; vaste lijsten die later vertaald worden met `Taal.T("…")`
    markeren. Meldingen gaan via `Taal.n(ctx, notification)`, widgets via `Taal.text`, toasts via `Taal.tx`.
    Liever vertalen bij het maken (`Taal.t` met plaatshouders) en gegevens in de melding (titel, zender, bestand)
    met `Taal.keep(builder, Notification.EXTRA_TEXT, …)` markeren, zodat `Taal.n` ze niet alsnog vertaalt.
  - zet de Engelse vertaling in `web/src/taal/en.json` (gesorteerd, één regel per tekst; een waarde mag ook
    `["één", "meer"]` zijn). `node tools/taal.js` toont wat ontbreekt (`--ontbrekend` als JSON) en de bewaker;
    `tests/ui/taal-dekking.test.js` faalt op een ontbrekende vertaling of op aan elkaar geplakt Nederlands. Moet iets bewust
    vast Nederlands blijven (tekst voor het taalmodel, zinnen die de assistent ontleedt): `// taal-vast: reden` op die regel,
    of `/* taal-vast-begin: reden */ … /* taal-vast-eind */`. `tests/ui/engels.test.js` loopt alle schermen in het Engels na.
  - nooit het woord n-e-p, ook niet de Engelse vertaling ervan: zeg "test bridge", "Choose location".
  - niet vertaald: wijzigingsregels ("Wat is er nieuw" zegt "(in Dutch)"), namen van Android (app- en widgetnamen in het
    manifest), en gegevens. De assistent begrijpt Engels via `59b-assistent-engels.js` (Engelse zin → de Nederlandse
    opdracht), antwoordt in het Engels (`LocalAi.buildSystemEn`), spreekt Engels (`ReadAloud`, of zonder lokale stem `SystemSpeech`) en schrijft Engels uit (whisper "en").
- **Interface**: gebruik de kleurrollen uit `stijl.css` (nooit vaste kleuren als `#fff` op `--primary`: in donker is
  `--primary` licht). Elk scherm heeft `<div class="hdr">` met terugknop en `<h1>`; detailschermen `class="hdr sm"`.
  Bouw met de gedeelde onderdelen uit "Interface-patronen" hieronder.

## Backups: elke tool doet mee (eisen voor alle tools, nu en later)
Alles wat Rene in een tool invoert of opbouwt, moet na een nieuwe telefoon of herinstallatie terug te zetten zijn. Daarom
gaat alles **standaard** mee; een uitzondering is een bewuste keuze met een reden. Dit regelt `AppData` (Java), samen met
`AppBackup` ("Alle appgegevens", versleuteld, alles) en `ToolData` ("Toolgegevens", leesbaar, zonder geheimen); de
interface gaat mee via `store.set` (`webstore`, in Alle appgegevens en in het onderdeel Instellingen). Een tool voldoet als:
1. **Gegevens staan op een plek die meegaat:** in een eigen map of eigen bestand in `getFilesDir()` (bijv.
   `getFilesDir()/<tool>/…`), in eigen SharedPreferences met een naam van kleine letters, cijfers en `_` (bijv.
   `"<tool>"`), of in de interface met `store.set('<tool>…', …)` (localStorage-sleutels `rt.…`; uitzonderingen in
   `WS_SKIP` in `02-kern.js`). Die gaan vanzelf mee in Alle appgegevens en worden vanzelf teruggezet, ook als je niets
   bijwerkt. **Niet** voor gegevens van Rene: `getCacheDir()`, `getNoBackupFilesDir()`, `getExternalFilesDir()`, `getDir()`,
   `databases/` (SQLite via `SQLiteOpenHelper`) of localStorage zonder `store`: die vallen buiten de backup. Een SQLite-
   database in `getFilesDir()` gaat niet goed los mee (`-wal`/`-shm` gaan niet mee): zet die map in `FILE_SKIP` en neem de
   inhoud zelf mee. Zelf meenemen (zoals de meldingsgeschiedenis of de handtekening) = in `AppBackup` maken én terugzetten, met een test.
2. **Een bewuste keuze per naam** (de test eist dat): zet de naam van de map in `AppData.FILE_KNOWN` en van het
   instellingenbestand in `AppData.PREFS_KNOWN` als hij gewoon mee mag (na nakijken dat er geen geheimen leesbaar in
   staan), of bij de uitzonderingen hieronder. Voor de leesbare "Toolgegevens" daarnaast: privé of al een eigen onderdeel →
   `ToolData.FILES_OWN` (map) of `ToolData.PREFS_NEVER` (instellingenbestand). Een map die (nog) niet in `FILE_KNOWN`
   staat, gaat in Toolgegevens alleen mee als tekstbestand zonder namen die naar een geheim klinken (`ToolData.readableOk`;
   een grove controle, geen garantie: zet de map na nakijken in `FILE_KNOWN`).
3. **Uitzonderingen alleen met een reden** in `AppData`: `PREFS_SKIP` (heel instellingenbestand), `KEY_SKIP`/`KEY_SKIP_PREFIX`
   (losse sleutels), `FILE_SKIP` (map of bestand in `getFilesDir()`) of `PATH_SKIP` (één bestand in een map die wel meegaat).
   Toegestaan: geheimen die aan deze installatie vastzitten en die je opnieuw instelt, wat opnieuw te downloaden of af te
   leiden is (modellen, caches, zoekindex, de interface in `web/`), toestand van dit moment (lopende opname, wachttijden,
   downloads) en wat Rene uitdrukkelijk op de telefoon wil houden (Bankzaken, stamgegevens). Gegevens die Rene zelf invoert
   uitsluiten = eerst Rene vragen. Wat in `FILE_SKIP` staat, wordt ook nooit uit een backup teruggezet (zo blijft bijv.
   `web/` veilig): een map met code of iets wat de app vertrouwt, hoort daar.
4. **Geheimen gaan versleuteld mee:** wachtwoorden, tokens en sleutels die op de telefoon met `Secure.deviceKey()` of een
   andere sleutel uit de sleutelopslag versleuteld zijn, zijn op een nieuwe telefoon onleesbaar. Neem ze dan ontsleuteld
   op in `geheim/` van "Alle appgegevens" en versleutel ze bij terugzetten opnieuw (zoals VPN, Spotify en de kluis), of
   zet ze bewust in `KEY_SKIP` met "opnieuw invullen" als reden. In "Toolgegevens" (leesbaar) gaan instellingen met een
   naam met token, key, sleutel, wachtwoord, code e.d. niet mee (`ToolData.secretKey`), net als bestanden met zo'n naam in
   het pad (`ToolData.keepFile`); de inhoud van een bestand in een bekende map wordt niet nagekeken. Kies zulke namen voor
   geheimen en zet geheimen nooit leesbaar in een bestand in een map uit `FILE_KNOWN`.
5. **Toestand van dit moment apart houden:** tijdelijke bestanden eindigen op `.tmp`, `.part`, `.writing` of een andere
   `AppData.TEMP_SUFFIX`; tellers, "laatst gedaan" en lopende acties in `KEY_SKIP` (sleutels die op `Asked` eindigen, slaat
   "Toolgegevens" al vanzelf over).
6. **Terugzetten moet kunnen:** bestanden met een `format`/`version`-veld, tegen ontbrekende of oudere velden bestand;
   wat de tool bij het starten opnieuw moet plannen (wekkers, taken), plant hij na terugzetten opnieuw (`Herstel`).
7. **Privé met eigen vinkje** (assistent, Slaappraat, Check-in): als eigen onderdeel van Alles back-uppen alleen als Rene
   het aanvinkt, en dan versleuteld; in Alle appgegevens (altijd versleuteld) wel, in Toolgegevens nooit (`FILES_OWN`,
   `PREFS_NEVER`). Een nieuwe privé-categorie volgt dat model (`AllBackup.PRIVATE`) en Rene beslist.
8. **Getest:** `AppDataTest` zoekt in de code elke `getSharedPreferences("…")`, elke `PREFS`-constante en elke map in
   `getFilesDir()` (ook via een constante uit hetzelfde bestand, zoals `getFilesDir(), DIR`) en faalt als die niet in `PREFS_KNOWN`/`FILE_KNOWN` of bij de
   uitzonderingen staat, of als de naam niet mee kan (een instellingenbestand met hoofdletters of een punt). Een naam die je anders opbouwt, ziet de test
   niet: houd het bij een vaste naam. Bij een tool met een eigen bestandsformaat: ook een test die maakt, terugzet en vergelijkt.

## Interface-patronen
Gedeelde onderdelen (vormgeving in `web/src/ontwerp.css`, hulpjes in `web/src/script/02-patronen.js`), bewaakt door
`tests/ui/ui-patronen.test.js`. Gebruik ze in plaats van iets nieuws, zodat de app overal hetzelfde werkt.
- **Terugknop**: altijd de SVG-chevron
  `<button class="back" onclick="goBack()" aria-label="Terug"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg></button>`
  (geen `ui-icon`-pijl of "‹"). Een scherm dat je vanuit een ander scherm opent (een item, een instellingenscherm) heeft
  `class="hdr sm"`; zet het ook in de lijst `DETAIL` van de test. Nieuw scherm = ook in `BACK` (02-kern.js).
- **Hoe werkt dit?**: uitleg van meer dan twee regels gaat ingeklapt:
  `<details class="help"><summary data-icon="info">Hoe werkt dit?</summary><div class="help-body"><p>…</p></div></details>`
  (in een kaart `class="help in"`; een eigen kop mag, bijv. "Waarvoor is dit?"). Vanuit JavaScript: `uiHelp(t('…'), html, inKaart)`.
  Eén regel uitleg blijft zichtbaar als `<p class="hint">`. **Niet verbergen** wat nodig is voor een veilige keuze
  (privacy, toestemmingen, wat er de telefoon verlaat, wat niet ongedaan kan): kort en zichtbaar houden, de details mogen in de help.
  De summary krijgt vanzelf `aria-expanded`; met `uiHtml(el, html)` blijven uitgeklapte blokken met een sleutel
  (`uiHelp(…, …, …, 'sleutel')`, `data-k`) open als een lijst opnieuw getekend wordt.
- **Overzicht met rijen** (hub): statuskaart, één hoofdknop en rijen naar eigen schermen, met `.card.stlist` en
  `<button class="strow"><span class="stic">icoon</span><span class="sttxt"><b>Titel</b><small id="…">stand</small></span><span class="ui-icon stchev" data-icon="chevron-right"></span></button>`
  zoals Instellingen, Lokale AI en Gebruik → Ontgrendelen. De `<small>` laat kort de stand zien (bij iets wat met
  beveiliging of privacy te maken heeft altijd, en niet afgekapt).
- **Overzicht met tegels**: een tool met een paar gelijkwaardige taken (zoals PDF: afschermen, invullen, openen, tekst
  als PDF) toont die als `.tiles` met `<button class="tile">` (pictogram in `.ic`, `<b>` taak, `<small>` één regel uitleg),
  zonder één gevulde hoofdknop; elke tegel opent een eigen taakscherm (hdr sm, in `DETAIL` van de test).
- **Instellingen van een tool**: schuifjes rechts in de kop, naar een eigen scherm `<tool>set` (hdr sm, `BACK` terug naar de tool):
  `<button class="icon-btn hset" id="…" data-to="carset" aria-label="Instellingen van Mijn auto"></button>`
  (pictogram en klik vult `uiPatterns()` in; geen eigen onclick of inhoud). Zo bij Gebruik, Apparaten en Mijn auto. Ontbreekt er iets waardoor de tool niet
  werkt (een toestemming), laat dan op de tool zelf een korte balk zien die naar de instellingen gaat.
- **Voortgang bij bestanden toevoegen**: leest of verwerkt de app een gekozen bestand (boek, document, factuur,
  afschriften, foto's), gebruik dan `Voortgang` in Java: `try (Voortgang v = Voortgang.van(a, "soort", aantal)) { … v.open(c, uri) … v.stap("verwerken", 0, 0) … }`.
  De interface toont dan vanzelf de balk onderin (`uiVoortgang` in `02-patronen.js`; nieuwe soort = een kop in `uiVgWat`).
  Alleen stappen en aantallen, nooit namen. Test: `tests/ui/voortgang.test.js`, `VoortgangTest`.
- **Handtekening**: er is één handtekening voor de hele app (Stamgegevens, PDF, Formulieren). Gebruik altijd
 `web/src/script/02-handtekening.js`: `sigTeken({plaatsen, bewaard})` (het gedeelde tekenvenster), `sigLoad`/`sigHuidig`,
 `sigSave`, `sigVraagWeg`, `sigPaint`/`sigPreview`/`sigCanvas` en `sigWatch`; uit een foto of bestand: `sigFoto` (Java
 `pdfSignaturePick` → `PdfSignatureImage`, alleen in het geheugen; `sigLijnen` maakt er lijnen van); nooit een eigen tekenvlak of rechtstreeks
 `Android.pdfSignature*` (dat laatste bewaakt `tests/ui/handtekening.test.js`). Opslag: `PdfSignature` (versleuteld, gaat
 mee in Alle appgegevens).
- **Lege lijst**: `uiEmpty('mic', t('Nog geen opnames.'), t('Knoptekst'), 'actie()')`: pictogram, één regel en hooguit één (tonale) knop.
- **Eenmalig instellen**: een kaart `class="card setup"` die weg gaat als het klaar is (`<div … hidden>`), of inklapt als er
  ook later nog iets in staat (`<details class="card setup"><summary><span class="setup-h" role="heading" aria-level="2">Eenmalig instellen</span><small id="…">stand</small></summary>…</details>`;
  geen `<h2>` in een summary).
  Bijwerken met `uiSetup(el, klaar)`; een `<details>` klapt alleen vanzelf als de stand verandert. Zo bij Locatie kiezen,
  Spotify en Gesprekken opnemen.
- **Knoppen**: per scherm hooguit één gevulde hoofdknop (`.big`, of `.mini.on.fill` in een lijst); de rest `.big.tonal`
  (belangrijk, bijv. toestemming geven) of `.big.ghost` (omlijnd). Bij een lijst met keuzes krijgt alleen de aanbevolen keuze
  de gevulde knop (Modellen, de assistent). Uitzonderingen staan met de reden in `MEER_PRIMAIR` in de test.
- **Keuzelijsten**: `<select class="sel">` (of `class="search"` als zoekveld-breed); nooit een kale `<select>`.
- **Menu ⋯ in de kop** voor acties die je zelden doet (exporteren, wissen): `openSheet(…)`, zoals Meldingsgeschiedenis.
  Let op losse apps: `soloSrcOk` leest de broncode van elke menukeuze en haalt keuzes weg die naar een andere tool gaan
  (ook `Android.waPickFolder`). Zet zo'n aanroep dus in een eigen hulpfunctie, niet in de actie zelf.
- **Schakelaar**: `<button class="tg" role="switch" aria-checked>`; moet het een `<input>` blijven: `<input type="checkbox" class="switch" role="switch">`.
- Tikvlakken minstens 44×44 px (ook dagknoppen, zoomknoppen van de kaart en rijen op de Telefoon-skin).

## Bouwen en testen
Eenmalig: `./setup-tools.sh` (Android-gereedschap in `$TOOLS`, standaard `/home/claude/toolchain`; zet `TOOLS` als je
het ergens anders wilt), Java 17+ (javac; CI gebruikt 25), Python 3, Node 22+ (CI gebruikt 26) met `npm ci && npx playwright install chromium`.

- `./build.sh` — bouwen (heeft een sleutel in `keys/` nodig, zie onder). Zonder de echte sleutel: maak een tijdelijke
  testsleutel zoals in `.github/workflows/bouwen-en-testen.yml`. `./publish.sh` weigert die (vingerafdruk-controle).
  `./build.sh` schrijft ook in `update/`: zet dat na het testen terug met `git checkout -- update` en commit het nooit.
- `./tests/run-jvm.sh` — Java-logicatests (na `./build.sh`).
- `./tests/run-ui.sh` — alle interfacetests (Playwright, licht en donker), veel tegelijk (`UI_JOBS`, standaard 3 per
  processorkern, max 12: de tests wachten vooral; ±4 min op 4 kernen), de langste eerst (`tests/ui/duur.txt`; na een
  flinke verandering bijwerken met `UI_DUUR_BIJWERKEN=1 ./tests/run-ui.sh`). Een mislukte test krijgt één herkansing met twee keer zo lange wachttijden
  (`UI_WACHT`, via `tests/ui/_traag.js`); slaagt hij dan wel, dan staat er `↻ naam` in de uitvoer, met daaronder wat
  er bij de eerste poging misging (wankel: graag repareren, de publicatie gaat wel door). `✗ naam` = echt mislukt.
- `./tests/ui-een.sh calls spotify2` — één of enkele interfacetests (naam zonder `.test.js`).
- `python3 tools/check-bridge.py` — brug en testbrug kloppen met elkaar.
- **Telefoontest** (`.github/workflows/telefoontest.yml`, alleen op GitHub: hier is geen emulator): installeert de APK in
  een Android-emulator (API 24, 35 en 36), start de app, het Home-scherm en de PDF-schermen en controleert dat er niets
  crasht (script: `tests/telefoon/rooktest.sh`; logcat en schermafdrukken in het artifact). Rood = niet laten liggen,
  maar oplossen: meestal een startcrash door build-tools, targetSdk of Java-code.
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
   Versienummer en versiecode worden bij de publicatie samen met main atomair geregistreerd als tags
   `versie-<nummer>` en `versiecode-<code>`, met leasecontrole op het slot in dezelfde push. Verwijder of overschrijf
   die tags nooit. Bij een botsing haalt het script
   de nieuwste main en registraties op en bepaalt het een nieuw nummer; een verloren of onleesbaar slot stopt de
   publicatie. Testvoorbereiding (`VOORBEREIDEN`) reserveert niets en mag hetzelfde toekomstige nummer tonen.
   Op GitHub draaien de interfacetests naast de telefoontest (`interfacetests.yml`, met `VOORBEREIDEN=…
   ./publish.sh`: dezelfde merge, versie en wijzigingslog, zonder sleutel of push; daarna bouwen met een testsleutel,
   Java-tests en interfacetests, in dezelfde volgorde als `publish.sh`). De taak publiceren slaat de interfacetests alleen
   over als de vingerafdruk (bron, `VERSION`, `changelog.json`) precies gelijk is, anders draait hij ze zelf; de
   Java-tests draait hij altijd (`WebSigTest` controleert de echte handtekening). `tests/publicatie-voorbereiden.sh` bewaakt dat.
   Een interfacetest die iets van de Java-tests nodig heeft (archieven in `herstel`, `sec`) slaat lokaal over als dat
   ontbreekt, maar faalt in CI (`process.env.CI`): daar draaien de Java-tests altijd eerst.
5. **Kun je zelf niet pushen** (bijv. Codex in de cloud): open een pull request met je wijziging en een bestand in
   `wijzigingen/`, zonder `VERSION`, `changelog.json` of `update/` (de CI keurt dat af). Na het samenvoegen publiceert
   de workflow het vanzelf. **Kun je wel pushen** (ook Cursor-agents in de cloud), dan is een pull request niet genoeg:
   push het na geslaagde tests en review zelf naar main (vaste opdracht van Rene).
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
- **Beveiligingsscan** (`beveiligingsscan.yml`): CodeQL op Java, JavaScript en C++ (`tools/llm/llm.cpp`) bij pushes naar main en
  pull requests die `app/`, `web/` of `tools/llm/` veranderen, en wekelijks. Bouwen en testen draait ook shellcheck op alle scripts. Meldingen staan onder Security → Code scanning. (GitHub's
  "default setup" van CodeQL moet uit staan, anders botst die met deze workflow.)
- **Onderhoud** (`onderhoud.yml`, maandag): `.github/scripts/onderhoud-controle.py` vergelijkt de meegeleverde
  bibliotheken (Leaflet, jsQR, whisper.cpp, llama.cpp), de AI-modellen (Hugging Face: nieuwere modellen van dezelfde maker in
  ongeveer dezelfde grootte) en het gereedschap (build-tools, android.jar/targetSdk, aapt2, zig, Playwright, Node, Java)
  met de nieuwste versies
  en met de OSV-kwetsbaarhedendatabase, en zet alles wat te doen is in het issue "Onderhoud: updates en beveiliging".
  Daarna draait `.github/scripts/opruim-scan.py` (dode code en versimpelen) en zet het rapport in het issue
  "Opruimen: dode code en versimpelen" (label `opruimen`, blijft altijd open); zie "Opruimen en versimpelen" hieronder.

**Alle onderdelen van buitenaf** (houd deze lijst bij als er iets bijkomt; `onderhoud-controle.py --bestanden` moet
alles kunnen lezen, dat controleert Bouwen en testen):

| Onderdeel | Waar vastgelegd | Gecontroleerd door | Bijwerken |
|---|---|---|---|
| GitHub Actions | `.github/workflows/*.yml`, vastgezet op commit-SHA met de versie als opmerking (`@<sha> # v1.2.3`; Bouwen en testen controleert dat) | Dependabot (wekelijks PR; werkt SHA en opmerking bij) | agent voegt samen na review |
| Leaflet, jsQR (in de app) | `web/vendor/` (kopregel) | Onderhoud: npm + OSV | agent, bij elke nieuwe versie |
| whisper.cpp (transcriberen) | `app/lib/arm64-v8a/README.txt` + `Transcribe.ENGINE` (EngineVersionTest) | Onderhoud: GitHub-release + OSV | agent: `tools/whisper-build.sh`, bij elke nieuwe release |
| llama.cpp (lokale AI) | `tools/llm-build.sh` (TAG/COMMIT) + README + `LocalAi.ENGINE` (EngineVersionTest) | Onderhoud: GitHub-release + OSV | agent: bij kwetsbaarheid of >1500 commits achter (dagelijkse releases) |
| Tesseract4Android en C-onderdelen (Tesseract, Leptonica, libpng, libjpeg) | `app/ocr/metadata.json`, binaries in `app/ocr/` en `app/lib/*/` | Onderhoud: GitHub-release + OSV; iedere bouw: `tools/ocr-verify.py` | agent: `app/ocr/README.md`; alle vier ABI's, hashes, licenties en Android-OCR-test |
| OCR-model tessdata_fast (Latin/LSTM) | `app/ocr/metadata.json` (repositorycommit en SHA-256), `RijbewijsOcr.MODEL_HASH` | Onderhoud: wijzigingscommit van modelbestand op GitHub | agent: model en beide hashes samen; geen telefoondownload |
| zig (bouwt whisper/llama) | `ZIG_VERSION` in beide bouwscripts | Onderhoud: PyPI | agent, samen met een nieuwe whisper/llama-bouw |
| Android build-tools (d8, apksigner, zipalign) | `setup-tools.sh` (`BUILD_TOOLS`, URL, SHA-1 en grootte van Google, dl.google.com) | Onderhoud: Google (repository2-3.xml) | agent; wisselen van d8 vraagt een test op een telefoon. Is Google niet bereikbaar (afgeschermde cloudsessie), dan valt `setup-tools.sh` lokaal terug op androidide-tools 34.0.4, alleen om te testen; in CI (`CI` gezet) moet de download lukken en `./publish.sh` bouwt alleen met de vastgezette versie |
| android.jar en targetSdk | `setup-tools.sh`, `build.sh` | Onderhoud: Google | agent; gedragswijzigingen van de nieuwe Android-versie nalopen en op een telefoon testen |
| aapt2 | `setup-tools.sh` (aaptjs3) | Onderhoud: npm | agent |
| Playwright (alleen tests) | `package.json` en `package-lock.json` | Onderhoud: npm | agent |
| Node en Java (alleen CI-gereedschap) | `node-version`/`java-version` in de workflows | Onderhoud: Node-schema, Adoptium | agent, naar de nieuwste LTS |
| AI-modellen (Qwen3/Qwen3.5 GGUF) | `LocalAi.java` (vaste commit + SHA-256) | Onderhoud: Hugging Face (zelfde maker, zelfde grootte) | agent voegt een nieuw `Model` toe na overleg met Rene (1–3 GB); Rene kiest zelf of hij downloadt |
| Lokale voorleesbibliotheken (sherpa-onnx/Piper, ONNX Runtime, Apache Ant-codecs) | `tools/voorlezen/deps.json` (URL's, SHA-256, ONNX Runtime-versie; `deps.py` controleert die), licenties in `app/voorlezen/` | Onderhoud: nieuwste sherpa-onnx 1.x (GitHub) + OSV (sherpa-onnx, onnxruntime, ant); sherpa 2 heeft de eSpeak-fonemizer niet meer | agent: vastgezette archieven, SHA-256 en `VoiceEngine.ENGINE` bijwerken; Play-controle (echte Android-synthesetest op API 24/36) moet slagen |
| espeak-ng en piper-phonemize (in sherpa-onnx, fonemizer voor Piper) | `tools/voorlezen/deps.json` (`csukuangfj/espeak-ng@ed530aa…`, `csukuangfj/piper-phonemize@f3ff95a…`; vorken, espeak-ng 1.52-dev) | Onderhoud: commit gelijk aan `cmake/*.cmake` van de vastgezette sherpa-onnx + OSV (espeak-ng) | komt mee met een nieuwe sherpa-onnx; dan de commits in `deps.json` bijwerken |
| Voorleesstemmen (Piper int8) | `VoiceModels.java` (grootte + SHA-256), modelkaarten in `app/voorlezen/` | Onderhoud: bestaat het releasebestand nog met dezelfde grootte | alleen op verzoek van Rene; een ander bestand krijgt een eigen id (de app weigert een gewijzigd bestand). De bron is het upstream releaselabel `tts-models` (kan veranderen): Rene kan later een eigen kopie toevoegen met `.withMirror("https://…")` (zelfde bestand en SHA-256, geprobeerd als de bron faalt); agents maken zelf geen releases of uploads |
| Spraakmodellen (whisper ggml) | `Transcribe.java` (vaste SHA-256) | niet automatisch: het formaat verandert zelden | alleen op verzoek van Rene |
| Toiletten-momentopname (OpenStreetMap, ODbL) | release `toiletten-gegevens` (workflow `toiletten-gegevens.yml`, `tools/toiletten/`; Geofabrik-extract met osmium) | Onderhoud: datum in `meta.json` (ouder dan 10 dagen = melding) | vanzelf elke week; agent: als de workflow faalt (oorzaak oplossen, met de hand starten) |

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
   Playwright-versie in `package.json` en `package-lock.json`; whisper.cpp met `tools/whisper-build.sh` (beide varianten, README.txt bijwerken,
   testen onder qemu-aarch64); llama.cpp met `tools/llm-build.sh` (TAG en COMMIT erin, daarna `./tools/llm/test.sh`);
   de rest en het tempo volgens de tabel hierboven.
   **AI-modellen:** een beter model overnemen = een nieuw `Model` met een eigen id in `LocalAi.java`, met vaste commit,
   grootte en SHA-256 (uit de LFS-gegevens van Hugging Face) en `.replaces("oud-id", Taal.T("waarom beter"))` (naam, uitleg en
   "waarom beter" in `Taal.T(…)` en in `en.json`). Nooit het bestand
   van een bestaand id wijzigen. De app meldt het nieuwe model dan bij de assistent en bij Taalmodel; Rene kiest zelf
   of hij het downloadt, en het oude kan daarna weg (oude bestanden ruimt Taalmodel → Opruimen op). Alleen bij een
   duidelijke verbetering (beter Nederlands, sneller, kleiner), en vraag Rene eerst: een model is 1–3 GB.
   **Dependabot-pull requests** (uitzondering op regel 6, want die zijn van GitHub zelf, niet van iemand anders):
   alleen samenvoegen als ze uitsluitend `.github/workflows/` raken, "Bouwen en testen" groen is en je de release
   notes hebt nagelezen, en extra kritisch zijn bij alles wat in `publiceren.yml` draait (de sleutel is daar actief).
   Bij grote versies: pas de workflows aan waar nodig.
4. Bouwen, alle tests (`run-jvm.sh`, `run-ui.sh`), onafhankelijke review door een tweede agent, bevindingen oplossen.
5. Wijzigingsregel(s) in `wijzigingen/onderhoud-JJJJ-MM-DD.txt` (alleen als de app verandert), push naar main zoals
   hierboven; controleer dat Publiceren slaagt. (Publiceren start alleen als `app/`, `web/`, `tools/`, `build.sh` of
   `publiceren.yml` veranderde; bij alleen andere workflows of scripts komt er geen run en geen nieuwe versie.) Is er niets veilig te doen, laat het dan staan en zet in het issue waarom.
6. Meld Rene kort: wat bijgewerkt is, wat opgelost is, en wat bewust is blijven liggen.
7. **Elke keer:** doe daarna ook de opruimronde hieronder, met het rapport van die week (eigen branch `opruimen/JJJJ-MM-DD`).
8. **Statistieken:** staat de privé repository met de weekrapporten (Instellingen → Foutrapport en statistieken, map
   `statistiek/`) open voor de sessie, lees dan de rapporten sinds vorige week. Vastlopers (soort + plek) en logsoorten die
   opeens vaak voorkomen: oplossen, of met de reden in het issue zetten. Niet bereikbaar: zeg dat in de reactie.
Nooit in het kader van onderhoud: de updatecontrole, de sleutel of `publiceren.yml`-beveiliging verzwakken, of
pull requests van anderen (mensen of andere bots dan Dependabot) samenvoegen.

## Opruimen en versimpelen (bij elke scan, dus wekelijks)
De scan (`python3 .github/scripts/opruim-scan.py`, ook lokaal te draaien) zoekt op naam naar dode code (JS-functies,
Java-classes en -methoden, brugfuncties, CSS-klassen) en naar plekken om te versimpelen (grootste bestanden, langste
functies, herhaalde blokken Java). Het zijn **kandidaten**: hij ziet geen namen die in een tekenreeks worden opgebouwd.
1. **Eerst nagaan, dan weghalen.** Per kandidaat grep op de naam, ook in tekenreeksen (`onclick`, `'ci-' + x`,
   `evaluateJavascript`, `on…`-terugroepnamen), `web/start.html`, het manifest, `app/res/` en reflectie. Bewust laten
   staan? Zet de naam met de reden in `.github/opruim-uitzonderingen.txt`, dan meldt de scan hem niet meer.
2. **Alleen gedrag-neutraal.** Opruimen verandert niets aan wat Rene ziet of wat de app doet. Weg = ook de nabootsing
   in `01-testbrug.js` en tests die alleen het weggehaalde controleerden. Versimpelen (opsplitsen, een gedeelde hulpfunctie)
   alleen met tests die het gedrag vóór en na vastleggen; ontbreekt zo'n test, schrijf die eerst.
3. **Klein houden:** per ronde de duidelijkste kandidaten, liefst onder de ±500 gewijzigde regels; per onderwerp een commit.
4. **Alles gaat elke ronde live, ook Java** (keuze van Rene). Alles in `app/` laat `NATIVE_LEVEL` stijgen, dus Rene moet
   dan een nieuwe APK installeren: zeg dat in het bericht aan hem. Houd het Java-deel daarom wel klein en goed getest.
5. **Niet in een opruimronde:** de updatecontrole (`Updater`, `WebUpdateStore`), versleuteling en sleutels (`Secure`,
   `Kluis`, `Lock`, `*Crypto*`, `WaCrypt`), backup terugzetten (`AppBackup`/`AppData`) en de publicatiescripts herschrijven.
   Dode code daar weghalen mag, met extra zorg in de review.
6. Bouwen, `run-jvm.sh`, `run-ui.sh`, `check-bridge.py`, de controle op anonieme classes, onafhankelijke review, push naar
   main zoals bij onderhoud. Verandert `app/`, `web/` of `tools/`, schrijf dan `wijzigingen/opruimen-JJJJ-MM-DD.txt` met één
   korte regel ("Opgeruimd: oude, ongebruikte code weggehaald; je merkt er niets van"); zonder bestand neemt Publiceren
   de commitregels over. Zet in het issue Opruimen wat weg is, wat bewust bleef en wat de volgende ronde aan de beurt is.

## Werklijst: openstaande punten, wensen en voorstellen
Alles wat nog moet gebeuren staat als GitHub-issue, met een label (REST: `gh api repos/renevanes/renes-tools/issues?labels=…`):
- `openstaand` — bewust geparkeerd bij een review of test (een issue per onderdeel, met een afvinklijst). **Laat je bij
  een review iets liggen, zet het dan hier** (bestaand issue aanvullen of een nieuw), anders raakt het kwijt.
- `wens` — een idee of verzoek van Rene. `voorstel` — een idee van een agent; pas bouwen als Rene het label `doen`
  geeft (of "doen" reageert). `doen` — mag gebouwd worden.
- Klaar = vakje afvinken (issue bijwerken) en het issue sluiten als alles af is, met de versie erbij.
- Werk op een branch altijd regelmatig pushen (`git push -u origin <branch>`, nooit main): de omgeving van een agent
  kan zomaar opnieuw beginnen, en wat alleen lokaal stond is dan weg.

**Geplande taken (Claude, in de cloud):** maandag Onderhoud (hierboven); woensdag Werklijst (de belangrijkste
`openstaand`- en `doen`-punten oppakken); om de week vrijdag Betrouwbaarheid en schermcheck (wankele tests, telefoontest,
één scherm kritisch nalopen: indeling, gebruiksgemak, toegankelijkheid, licht/donker); de eerste van de maand
Voorstellen (3–5 uitbreidingen als `voorstel`-issue). Vaste opdracht van Rene: ook deze taken publiceren zelf, na
geslaagde tests en een onafhankelijke review, en melden kort wat er gepubliceerd is (en of er een nieuwe APK nodig is).

## Facturen ophalen (algemeen raamwerk, leveranciers toevoegen)
Facturen (scherm `s-facturen`, `Facturen*.java`) logt in bij websites van leveranciers en downloadt PDF-facturen, op de
achtergrond (`FacturenJob`, onzichtbare WebView) of terwijl Rene meekijkt (`FacturenActivity`). Eerste leverancier: KPN Zakelijk.
- **Een leverancier is alleen een instelling.** Sjablonen staan in `web/facturen-leveranciers.json` (in de app te kiezen bij
  "Leverancier toevoegen"); alles is in de app aan te passen. Nieuwe leverancier = een blok erbij met `sjabloon`, `naam`,
  `startUrl` (https), eventueel `facturenUrl`, `menu`/`knoppen` (zoekpatronen), `code` (`bron` geen/sms/eigencloud,
  `afzender`) en een `tip`. `FacturenTest` controleert elk sjabloon met `Facturen.normaal`.
- **De robot** (`web/facturen-robot.js`) draait in de website: velden vinden en invullen, versturen, menu en downloadknoppen
  zoeken, PDF's die de pagina zelf maakt (blob:) doorgeven. `FacturenRun` beslist (inloggen → facturen → knoppen → afleveren).
  Robot en sjablonen zitten in de APK (assets): een wijziging daarin komt pas met een nieuwe APK. Test de robot met
  `tests/ui/facturen-robot.test.js` (nagebootste leverancier) en de beslislogica/regels met `FacturenTest`.
- **Facturen uit de mail** (soort `mail`): `FacturenMail` is het raamwerk, een mailbron is een `FacturenMail.Bron` (eerste:
  `FacturenGmail`, Gmail-API met alleen-lezen-rechten via de Google-koppeling in `FacturenDrive`). Instellingen van de mailbron
  in `mail` (zoekwoorden, afzenders, overslaan, extra zoekopdracht, terugkijken) plus planning en opslag. **Nieuwe bron**
  (bijv. Outlook via Microsoft Graph): een klasse die `Bron` doet, toevoegen in `FacturenMail.BRONNEN` en `bron()`, een
  eigen koppeling (OAuth zoals `FacturenDrive`, eigen geheimen in `facturen_geheim`), een optie in `#fl-m-bron`, een rij op
  het scherm Koppelingen, en tests (zoekopdracht en bijlagen) in `FacturenTest`.
- **Elke leverancier uit de mail apart:** de mailbron is alleen de bron; elke afzender die Tools herkent wordt een eigen rij
  van soort `uitmail` (`FacturenHerken.SOORT`: vanzelf aangemaakt met `nieuw` en het label Nieuw, `bronnen` = de mailbron(nen),
  `herken` = waaraan hij herkend wordt, `voorstel` = een website-leverancier met hetzelfde domein; eigen map
  `Facturen/<leverancier>/`, opslag overgenomen van de mailbron; geen ophalen of planning van zichzelf). `FacturenHerken`
  (zuiver, getest in `FacturenHerkenTest`) herkent in deze volgorde: eigen regels (`herken` van een rij), btw-/KvK-nummer uit
  de PDF, IBAN, domein (subdomeinen tellen bij het hoofddomein), naam of oorspronkelijke afzender bij platforms, gratis mail en
  doorgestuurde mail (lijsten `mailPlatforms` en `freemail` in `web/facturen-leveranciers.json`), anders een nieuwe leverancier.
  Rene's eigen nummers en adressen (`facturen/herkennen.json`, Zoeken-tab van de mailbron) en nummers die bij 3 of meer
  afzenders staan tellen niet. Het dossier noteert `via` (de mailbron) en `kenmerken` per factuur; in `toestand.json` staat per
  mail `l` (bij welke leverancier). `FacturenIndeling` doet de acties: Klopt, Overslaan (terugzetten bij de mailbron: die
  zoekt de overgeslagen facturen dan opnieuw), een factuur verplaatsen in het dossier (met "Onthoud dit" = regel: het adres,
  bij een platform de naam of het domein van het antwoordadres, nooit het adres van het platform zelf; plus de nummers), Samenvoegen (ook "Hoort bij …"; neemt
  herkenning, facturen en aanleverontvangers mee) en het eenmalig herindelen van oude mailfacturen (`indeling` in de prefs,
  `VERSIE`). Deze acties lopen nooit tegelijk met ophalen, aanleveren of het dossier leegmaken (`FacturenIndeling.BEZIG`
  naast `FacturenRun.LOPEND`, `FacturenAanlever.BEZIG` en `FacturenDossier.WEGHALEN`: eerst de eigen vlag zetten, dan de
  andere controleren). Bestanden buiten de app (backup-map, Drive, mail) blijven waar ze zijn. Aanleveren: de mailbron aanvinken = alles
  wat via die bron binnenkwam, ook leveranciers die er later bij komen. Herkennen gebeurt alleen op de telefoon: geen
  opzoekdiensten, en geen namen, nummers of adressen in het logboek of in Statistieken. Interfacetest:
  `tests/ui/facturen-mail-leveranciers.test.js`.
- **Dossier** (`FacturenDossier`, `facturen/dossier.json`, scherm `s-facdos`/`s-facstuk`): per factuur (sleutel = SHA-256)
  de bron (website of mail: afzender, onderwerp, datum), herkende factuurgegevens (nummer, datum, bedrag, btw; Rene kan ze
  aanpassen), alle plekken waar hij staat (`Facturen.aflever` noteert ze met `FacturenDossier.plek`) en aan wie hij is
  aangeleverd. Export als CSV (puntkomma, geen formules) naar een map die Rene kiest. Nieuwe opslagplek = ook `plek(…)` aanroepen.
- **Aanleveren** aan de accountant of boekhouder (`FacturenAanlever`, `facturen/aanleveren.json`, scherm `s-facaanl`/`s-facontv`):
  een ontvanger is een instelling met een **kanaal** (`FacturenAanlever.Kanaal`, register `KANALEN`; eerste: `email` =
  `FacturenEmail`). Wat open staat volgt uit het dossier (`verzonden` per ontvanger), dus niets gaat dubbel en een mislukte
  zending komt vanzelf terug. Moment: direct na het ophalen, dag/week/maand (zelfde planning als leveranciers) of met de hand;
  `FacturenJob` doet het als er niets op te halen is. E-mail gaat via Rene's eigen account: Eigen Cloud (`/api/mail/send`),
  zijn eigen mailserver (`FacturenSmtp`: alleen SSL/STARTTLS met certificaat- en naamcontrole; zonder versleuteling alleen
  localhost) of Gmail (recht `gmailsend` = scope `gmail.send`, apart aan te vinken). **Nieuw kanaal** (bijv. uploaden naar een
  boekhoudpakket): een klasse die `Kanaal` doet, in `KANALEN`, velden in een `.fo-k`-kaart op `s-facontv` (`data-kanaal`), testbrug en
  tests in `FacturenAanleverTest`. **Nieuwe manier van mailen** (bijv. Outlook): een `FacturenEmail.Verzender` in `VERZENDERS`.
  Alleen naar adressen/bestemmingen die Rene zelf invult; de proef gaat alleen naar zijn eigen adres.
- **Schermen:** Facturen (deze maand, leveranciers, Meer) → Dossier, Aanleveren, Overzicht per maand, Opslaan (plekken) en
  Koppelingen (Google, Eigen Cloud, eigen mailserver); een leverancier heeft tabbladen (website: Inloggen, Planning, Opslaan, Meer; mail: Zoeken, Planning, Opslaan).
  Google en Eigen Cloud zijn via Opslaan én Koppelingen te bereiken: navigeer met `faNaar()` zodat terug klopt.
- **Grenzen:** alleen https; alleen echte PDF's (`%PDF`) tellen; wachtwoorden versleuteld (`facturen_geheim`, niet in backups);
  de inlogcode alleen uit sms van de ingestelde afzender of uit Rene's eigen Eigen Cloud; afleveren alleen naar plekken
  die hij zelf kiest: zijn backup-map, een zelf gekozen map op de telefoon, zijn eigen Google Drive (`FacturenDrive`, eigen
  Google-project (koppelen met het Google-account op de telefoon via `FacturenGoogleToestel`/AccountManager en een Android-client met pakketnaam en SHA-1, of via de browser met een desktop-client), alleen de rechten `drive.file`; Gmail `gmail.readonly` en, als Rene het aanvinkt, `gmail.send`) en zijn eigen Eigen Cloud (`/api/mail/send` naar zichzelf).
  Aanleveren gaat alleen naar de ontvangers die Rene zelf instelt (zijn accountant of boekhouder). Geen andere
  bestemmingen toevoegen zonder Rene. Nieuwe bestemming = ook in `Facturen.aflever` (met opnieuw proberen bij een fout).

## Bankzaken (afschriften analyseren, alles op de telefoon)
Bankzaken (scherm `s-bank`, `web/src/script/64-bankzaken.js` en `65-bankzaken-ai.js`, `Bank*.java`) maakt rapportages over
Rene's inkomsten en uitgaven. **Niets mag de telefoon verlaten** (wens van Rene): houd dat bij elke wijziging vast.
- **Opslag:** `Bank` + `BankCrypt` (AES-256-GCM, gzip, blokken met volgnummer); sleutel `renes_bankzaken` in de Android
  Keystore, waar mogelijk gekoppeld aan de ontgrendeling (15 minuten). Map `bank/` gaat bewust nergens mee (Android-backup,
  Alles back-uppen, "Alle appgegevens"), niet in zoeken, de assistent of Statistieken. Logregels (`App.log` soort BANK)
  bevatten nooit bedragen, namen of rekeningnummers. Op slot na 5 minuten niets doen en zodra de app uit beeld gaat;
  de interface vergeet dan alles. Op het scherm FLAG_SECURE (`bankSecure`). Ook als losse app alleen na eigen
  ontgrendeling; de vrijgave hoort bij dat venster en mag niet van een andere banksessie worden overgenomen.
- **Inlezen:** `BankImport` (zuiver, getest in `BankTest`): CSV met kopregel (ING, Rabobank, Knab, bunq, algemeen), zonder
  kopregel (ABN AMRO TXT, ASN/SNS/RegioBank, Triodos), MT940 en CAMT.053/052/054 (XML zonder DOCTYPE: XXE). Dubbele
  boekingen: id = rekening|datum|bedrag|volgnummer op die dag. Nieuwe bank = herkenning erbij + een test met een voorbeeld.
- **Automatisch ophalen:** `BankLinkActivity` + `web/bank-robot.js` + `web/bank-banken.json` (in de APK). Alleen https en
  alleen de domeinen van de bank; de robot logt nooit zelf in (Rene doet dat met de app van de bank) en zoekt daarna
  "Transacties downloaden"; het bestand gaat in het geheugen rechtstreeks naar `Bank.importeer`. Na afloop cookies en
  websitegegevens van de bank weg. Eerste bank: Rabobank. Nieuwe bank = een blok met `robot: true`, `domeinen`, `apps`
  en de patronen; test met `tests/ui/bank-robot.test.js`. Geen PSD2-tussendiensten of andere partijen zonder Rene.
- **Rekeningen privé/zakelijk:** `store.rek[iban] = {naam, soort}` (zelf toegevoegde in `store.eigen`); een nieuwe rekening
  krijgt een vraag privé of zakelijk. Categorieën hebben een groep (`BK_CATS`: '' beide, 'p' privé, 'z' zakelijk); zakelijk
  eerst `BK_ZREGELS`, daarna de algemene regels omgezet met `BK_ZMAP`. Geld tussen privé en zakelijk (`BK_TUSSEN`) telt niet
  mee als beide kanten in beeld zijn (`bkSoort`). Eigen regels hebben `s` (soort) zodat dezelfde winkel privé en zakelijk anders kan.
- **Lokale AI:** `LocalAi.privateChat` (geen geheugen, leren, acties of opgeslagen gesprek) via `bankAi`; de interface
  stuurt alleen een samenvatting met cijfers. Antwoorden altijd als tekst tonen (`textContent`), nooit als HTML.

## Toiletten en douches (bronnen, scannen, eigen wijzigingen, gemeenschap)
Toiletten en douches (scherm `s-wc`, `web/src/script/73-toiletten.js`, `Toiletten*.java`) zoekt openbare toiletten en douches
in de buurt, ook plekken waar er meestal een is (tankstations, verzorgingsplaatsen, campings e.d., bit `MEESTAL`).
**Rene's plek verlaat de telefoon niet**: bronnen worden in hun geheel gedownload en het zoeken gebeurt op de telefoon.
- **Bronnen** (`Toiletten`, `toiletten/bronnen.json`): een bron is een bestand op internet (`ToilettenScan`: `tools` = de
  eigen momentopname, `geojson`, `arcgis` (met pagina's) of `csv`), met veldnamen (`velden`, meer namen met `|`), vaste
  kenmerken (`extra`) en regels (`regels`: veld + patroon → kenmerk of overslaan). Sjablonen staan in
  `web/toiletten-bronnen.json` (in de APK; `standaard: true` = vanzelf aan bij Gegevens ophalen). **Nieuwe bron** = een blok
  met `sjabloon`, `naam`, `soort`, `url` (https), `licentie` (verplicht), `info`, `velden`/`regels` en een `tip`; alleen een
  open licentie mag aan, anders `aan: false` met de reden in `tip` (zoals Amsterdam). `ToilettenTest` controleert elk
  sjabloon met `Toiletten.normaal`. Rotterdam heeft (nov. 2026) geen bruikbare open laag.
- **Momentopname**: `tools/toiletten/ophalen.py` met `regels.json` (welke OSM-tags wat betekenen) maakt elke week (workflow
  `toiletten-gegevens.yml`, dinsdagnacht; de taak die osmium draait heeft alleen leesrecht, een tweede taak controleert en
  publiceert) `toiletten-nl.json.gz` en `meta.json` (datum, sha256, aantal) uit het
  Geofabrik-extract (osmium; `--overpass` alleen om te ontwikkelen) en zet ze in de release `toiletten-gegevens`. De app
  controleert het controlegetal. Ander formaat: `FORMAAT` in `Toiletten.java`, `formaat` in `regels.json` en de controle in
  `toiletten-gegevens.yml` samen omhoog.
  De workflow start ook bij bronwijzigingen op main: een nieuwe installatie hoeft niet op de eerste dinsdag te wachten.
  `tools/toiletten/controleren.py` controleert vóór vrijgave ook rijen, coördinaten, licentie en gzip-volledigheid.
  Taak `bronnen` controleert de sjabloonadressen en veldkoppelingen; het rapport staat in de Actions-samenvatting en het
  artifact `bronnencontrole`. Neem dat bij het wekelijkse onderhoud door. Aanvulproces en herstel van een ontbrekende
  release: `docs/toiletten-en-douches.md`. De testbrug krijgt de echte sjablonen via `tools/web-samenvoegen.py`.
- **Scannen** (`ToilettenJob`, job-id's 4701–4703): per bron een planning (dag/week/maand/uit, tijd in de app-zone,
  vanzelf alleen via wifi, opnieuw na een fout). Mislukt het, dan blijft de vorige versie (`toiletten-cache/`, gaat niet
  mee in backups); de laatste 10 scans per bron in `toestand.json` (fouten Nederlands bewaard, de brug vertaalt ze met
  `Taal.tx`, dus een waarde erin via een plaatshouder: `Taal.fill(Taal.T("De bron antwoordt met {code}"), "code", code)`).
  Zoekpatronen die eindeloos kunnen duren (herhaling binnen een herhaling, meer dan drie keer + of *) weigert
  `Toiletten.riskant` al bij het bewaren: Android kan een zoekopdracht niet afbreken.
- **Samenvoegen** (`ToilettenSamen`, zuiver, `ToilettenSamenTest`): dezelfde plek uit twee bronnen (zelfde groep, hooguit
  25 m) wordt één plek; de eerste bron in de lijst gaat voor bij naam en tijden, kenmerken tellen op.
- **Eigen laag** (`toiletten/eigen.json`, gaat mee in "Alle appgegevens", niet in de Android-backup): aanpassingen (`wijzig`, met `was` = wat de bron
  toen zei; zegt de bron later iets anders, dan Overnemen of Mijn versie houden), afwijzingen (reden `weg`/`dicht`/`prive`/
  `klanten`/`anders`, toelichting, Terugzetten) en eigen plekken (`eigen:<12 hex>`; staat er later een plek uit een bron
  binnen 25 m, dan biedt de app opruimen aan). Blijft over elke scan heen; een onleesbaar bestand wordt nooit overschreven.
  Een aangepaste "meestal"-plek blijft "meestal", tenzij Rene aanvinkt dat er zeker een toilet is (`zeker`).
- **Gemeenschap** (`ToilettenOsm`, `toiletten/gedeeld.json`): alleen als Rene het per plek kiest, een anonieme notitie bij
  OpenStreetMap (`api.openstreetmap.org/api/0.6/notes`). Eerst `s-wcdeel` met de precieze tekst en de zichtbare waarschuwing
  "Openbaar; OpenStreetMap ziet je IP-adres". Vrije tekst gaat alleen mee uit het veld "Opmerking erbij (openbaar)" op dat
  scherm, nooit de eigen notitie bij een plek; wat er gaat moet precies de getoonde tekst zijn (`wcDoorgeven` controleert
  dat) en een notitie die nog open staat kan niet nog een keer. 403 (moderatiezone) = tekst op het klembord en de website op die plek.
  Hooguit 20 per dag; de status (open, opgelost, verborgen) wordt bij het scannen hooguit één keer per dag nagekeken. De
  tekst is vast Nederlands (`taal-vast`). Geen andere bestemmingen of accounts zonder Rene.
- **Privacy**: alleen de kaartbeelden (tile.openstreetmap.org) zien welk stuk kaart Rene bekijkt; dat staat zichtbaar op
  het scherm en de kaart kan uit (Instellingen → Kaart tonen). Logsoort `TOILET` alleen met aantallen, nooit plekken,
  namen of adressen.
- Tests: `tests/ui/toiletten.test.js`, `toiletten-bronnen.test.js`, `toiletten-eigen.test.js`; Java: `ToilettenTest`,
  `ToilettenSamenTest`, `ToilettenOsmTest`.

## Nieuws (feeds lezen)
Nieuws (schermen `s-nieuws`, `s-nieuwsart`, `s-nieuwsset`; `web/src/script/75-nieuws.js`, `Nieuws*.java`) toont koppen en
artikelen uit RSS-, Atom- en RDF-feeds.
- **Ophalen alleen in Java** (de WebView heeft `connect-src 'none'`): `Nieuws.haal` volgt doorverwijzingen zelf en alleen
  naar https op openbare adressen (`NieuwsFeed.httpsOpenbaar`, en na het opzoeken van de naam `Nieuws.intern`: niet het
  toestel of het thuisnetwerk), met een vaste eindtijd per feed (een wachter verbreekt de verbinding) en een groottegrens;
  geen account, geen tussendienst en geen leesmodus die de webpagina van een
  artikel ophaalt (cookiemuren). De tekst komt uit de feed; "Lees op de website" opent de link in de browser.
- **Lezen** (`NieuwsFeed`, zuiver, getest in `NieuwsFeedTest`): SAX zonder externe entiteiten; een DOCTYPE met eigen
  declaraties weigert `PodcastFeed.EntityGuard` al vóór de parser (de parser van Android geeft declaraties niet door).
  Opschoonpatronen moeten lineair blijven (Android kan een zoekopdracht niet afbreken; `NieuwsFeedTest` meet het). HTML uit
  de feed wordt alinea's en tussenkoppen (platte tekst), de interface zet het altijd met `textContent`/`esc` neer, nooit als HTML.
  Afbeeldingen alleen via https, zonder referrer, uit te zetten (Instellingen van Nieuws).
- **Bronnen**: vaste lijst in `Nieuws.STANDAARD` (id, naam, feed, standaard aan, sites en eventueel hoe de link van een
  artikel moet beginnen: `Nieuws.filter`, zoals Tweakers alleen `tweakers.net/nieuws/` uit de gemengde feed; de andere
  feeds van Tweakers weigert de firewall van DPG vanaf servers, dus ook als een agent een feed controleert); een eigen bron (id `e…`) is een
  https-feed of een pagina met `<link rel="alternate">`. Een bekende site (bijv. nos.nl) zet de bekende bron aan.
  Nieuwe vaste bron = een regel in `STANDAARD` én in de testbrug (`_nwInit`); controleer dat de feed echt leest.
- **Opslag**: `nieuws/` (bronnen, gelezen, bewaard) gaat mee in "Alle appgegevens", niet in de Android-backup; de opgehaalde
  artikelen staan in de cache. Logsoort `NIEUWS` alleen met aantallen.
- Tests: `tests/ui/nieuws.test.js`; Java: `NieuwsFeedTest`, `NieuwsTest`.

## Berichten aan Rene
Kort en in het Nederlands: wat er nu anders is, waar hij het vindt en of hij iets moet doen (bijv. een nieuwe APK
installeren als `NATIVE_LEVEL` omhoog ging, of opnieuw koppelen).
