# Startscherm en automatiseringen

[Terug naar het overzicht](../README.md)

## Uiterlijk
- **Android 16-stijl (Material 3)**: grote titel die bij scrollen omhoog schuift (de bovenste rij met terug en knoppen blijft staan), zachte vlakken, ronde knoppen, schakelaars en chips zoals in Android zelf; licht en donker volgen de telefoon.
- **Kleuren uit je achtergrond** (Instellingen → Weergave, vanaf Android 12): de app neemt de Material You-kleuren van Android over (`system_accent*`/`system_neutral*`); uit = vaste blauwe kleuren.
- **Tabbalk onderin**: Start · Zoeken · Backup · Instellingen. Zoeken is een eigen scherm (overal zoeken in notities, contacten, berichten, oproepen, meldingen en de instellingen); op het startscherm staat bovenaan een zoekbalk die daarheen gaat.

## Toegankelijkheid
- **Tekstgrootte** (Instellingen → Weergave): volgt standaard de lettergrootte van de telefoon, of kies kleiner tot maximaal (175%). Via `WebSettings.setTextZoom`; de indeling loopt mee zonder horizontaal scrollen.
- **TalkBack**: alle knoppen en velden hebben een naam; schakelaars zijn `role=switch` met aan/uit, keuzeknoppen melden of ze gekozen zijn; meldingen (`role=status`), dialogen (`role=dialog`) en bij elk scherm springt de focus naar de titel.

## Automatiseringen
- "Als dit gebeurt, doe dan dat", zoals MacroDroid. Regels staan als JSON in de voorkeuren `auto` (`Auto.java`); de pure logica (afstand, dagen, tijdvenster, wachttijd, knopteksten vergelijken) zit in `AutoLogic.java`, los getest in `AutoTest`.
- **Wanneer**: Bluetooth van de auto verbreekt (motor uit) of verbindt (`AutoReceiver`, `ACL_CONNECTED/DISCONNECTED`, ook als de app dicht is), of aankomen op / weggaan van een plek (`LocationManager.addProximityAlert`, zonder Google Play Services). Bij Bluetooth kan "alleen op een bepaalde plek": dan wordt op dat moment één keer de locatie bepaald (`Auto.locate`, max. 25 s). Plek kiezen op een kaart (Leaflet) of met "Mijn plek nu", straal 100 m–1 km. Verder dagen en een tijdvenster, en een wachttijd tegen dubbel starten (Bluetooth die even hapert).
- **Wat**: *Eerst vragen* (melding met **Starten** / **Niet nu**; een tik gaat via de onzichtbare `AutoRunActivity`, omdat Android vanuit een melding geen app laat openen via een ontvanger), *Volledig automatisch*, of *Alleen een melding* (bijv. "vergeet niet de parkeeractie te stoppen", tik opent de app).
- **Knoppen indrukken** (`AutoA11y`, toegankelijkheidsdienst): opent de gekozen app en zoekt per stap de knop op tekst, beschrijving of id (wacht max. 12 s per knop), tikt op de knop of de aanklikbare ouder, anders een echte tik op het midden. Stappen: tik op tekst, tik op een plek op het scherm, wachten.
  - **Opnemen**: de app gaat open met een zwevende balk (bovenin; tik op de tekst om hem te verplaatsen). Tikken worden vastgelegd; voor apps die tikken niet doorgeven is er **Kies knop** (lijst met knoppen op het scherm: kiezen = vastleggen en indrukken). **Klaar** brengt je terug naar Tools met de stappen.
  - Als er niets loopt, luistert de dienst alleen naar de eigen app (`packageNames`), dus hij leest niets van andere apps.
  - Android 13+: bij een app van buiten de Play Store is de toegankelijkheid eerst geblokkeerd. Eenmalig: App-info → ⋮ → *Beperkte instellingen toestaan*.
- **Sjablonen**: *Parkeren starten als ik parkeer* (auto uit, eventueel alleen bij een plek → EasyPark/Parkmobile/Yellowbrick, die worden vanzelf herkend) en *Herinnering: parkeeractie stoppen* (auto aan → melding).
- Nodig, alleen voor wat je gebruikt: meldingen, `BLUETOOTH_CONNECT`, locatie met `ACCESS_BACKGROUND_LOCATION` ("Altijd toestaan"), de toegankelijkheidsdienst en geen batterijbeperking (Oppo: ook *Automatisch starten*). Het scherm toont wat er nog ontbreekt, met een knop per onderdeel. Laatste 40 gebeurtenissen staan onder *Laatst gebeurd*.
- Telefoon vergrendeld bij *Volledig automatisch*: knoppen achter het vergrendelscherm zijn niet te zien, dus de dienst wacht tot je ontgrendelt (`ACTION_USER_PRESENT`, max. 30 min) en toont intussen de melding met **Starten**.
- Locatie niet te bepalen bij "alleen op een bepaalde plek" (op de achtergrond geeft Android weinig nieuwe metingen; een meting tot 10 min oud telt mee): dan wordt het altijd gevraagd met een melding, nooit blind automatisch uitgevoerd.
- Aankomen/weggaan gebruikt `addProximityAlert` en heeft daarom *precieze* locatie nodig.
- Plekken worden opnieuw ingesteld als de plek-regels veranderen, herstart van de telefoon, een update en (als het langer dan 6 uur geleden is) bij het openen van de app.

## Startscherm
- Tegels in groepen (Bellen & contacten, Berichten & backup, Muziek & radio, Handig). Via "Startscherm aanpassen" (`31-startscherm-groepen-volgorde-verbergen.js`, test: `startscherm-groepen.test.js`): de **groepen in een eigen volgorde** (↑/↓ bij de groep, of ⋯ → Bovenaan/Onderaan), **tools slepen** aan ⠿ (binnen een groep en naar een andere groep; de lijst schuift mee aan de rand), per tool ⋯ → *Naar een andere groep…* (ook *Nieuwe groep…*), *Omhoog/Omlaag*, *Verbergen/Tonen*; onder elke groep **+ Tool toevoegen** (haalt een tool uit een andere groep; het menu blijft open voor nog een); groepen hernoemen, toevoegen (daarna meteen tools kiezen) en weghalen (de tools gaan naar de groep erboven); favorieten (★, bovenaan, ook te slepen), zonder groepen tonen en een compacte weergave met drie tegels naast elkaar. Lang indrukken van een tegel op het startscherm: ook *Naar een andere groep…* en *Verbergen*. Opgeslagen in de WebView-opslag; nieuwe tools uit een update komen vanzelf in hun groep.
- **Overal zoeken** (zoekveld bovenaan het startscherm, `Search.java`): zoekt tegelijk in notities, contacten, sms'jes, WhatsApp-chats (als die leesbaar gemaakt zijn), oproepen, uitgeschreven gesprekken en herkende muziek. Per soort de eerste vijf treffers; tik om het te openen, of op "Alle …" om verder te zoeken in die tool. Alleen bronnen waarvoor toestemming is gegeven doen mee.
- **Overzicht** bovenaan (uit te zetten bij Startscherm aanpassen): laatste backup (oranje als die al dagen oud is, rood bij een mislukt onderdeel), de volgende radiowekker, herinneringen van vandaag, wat de radio speelt en problemen uit de laatste zelftest. Tik op een regel om naar die tool te gaan.

## Telefoon-skin (startscherm)
- Tools kan het startscherm van je telefoon vervangen (Instellingen → Telefoon-skin → *Als startscherm gebruiken*; via `RoleManager` op Android 10+, anders de Android-instelling). *Eerst bekijken* opent het zonder iets te veranderen; terug naar het startscherm van de telefoon kan altijd via dezelfde kaart.
- `HomeActivity.java` (eigen taak, `taskAffinity` apart, geen app-slot) met de pagina `web/start.html`; gegevens uit `Launcher.java`:
  - **Bovenaan**: grote klok en datum, volgende wekker (tik = klok-app), het **weer** (Open-Meteo, zonder sleutel: nu, regenkans komende uren en 3 dagen; plaats zelf kiezen of de locatie van de telefoon), de **agenda** (komende afspraken, met toestemming) en **Tools**: radio afspelen/pauzeren/terugspoelen, backupstatus, herinneringen van vandaag, geplande Auto redial en snelkoppelingen naar tools (zelf te kiezen).
  - **Apps**: veeg omhoog voor alle apps (A–Z) met zoeken (Enter opent de eerste treffer, of zoekt op internet). Lang indrukken: op het startscherm, in het dock, in een map, verbergen, app-info, verwijderen. Mappen en volgorde zelf te bepalen; nieuwe en verwijderde apps worden vanzelf bijgewerkt.
  - **Apps kiezen** (Look aanpassen → Apps kiezen): per lijst alle apps met een vinkje en zoeken. *Op het startscherm* en *In het dock* (max. 6) met de volgorde (↑/↓); *Zichtbaar bij alle apps* om apps te verbergen of terug te halen. Alle apps sorteren op A–Z, meest gebruikt of nieuwste.
  - **Indeling**: volgorde van klok, weer, agenda, Tools en de apps op het startscherm, en elk onderdeel aan/uit. Klok groot/middel/klein/uit, met of zonder datum en wekker. Kaarten: doorzichtigheid en ronde of hoekige hoeken. *Opnieuw beginnen* zet alleen de look, of ook de indeling terug.
  - **Eigen look** (lang drukken op een leeg stuk of ⚙︎): thema licht/donker/automatisch, accentkleur, achtergrond (je Android-achtergrondfoto of kleurverlopen/kleuren), pictogramgrootte, 4 of 5 per rij, namen aan/uit, welke kaarten je ziet.
  - Gebaren: omhoog vegen = alle apps, omlaag vegen bovenaan = meldingen (als de telefoon dat toestaat), terug/home sluit overzicht en vensters.
- **Launcher met pagina's en widgets** (standaard sinds 1.31; `Desk.java`, model in `WsModel.java`, opslag in `WsStore.java`):
  - Pagina's naast elkaar (max. 12) op een raster (4 of 5 kolommen, rijen naar de schermhoogte); links de pagina **Vandaag** (`start.html` in modus `today`: klok, weer, agenda, Tools, notities). Onderaan het dock (max. 6) en de pagina-puntjes.
  - **Slepen**: lang drukken en slepen verplaatst apps, mappen en widgets; aan de rand van het scherm naar de buurpagina (rechts eventueel een nieuwe); bovenaan *Weghalen* en *App-info*. App op app = map; in een open map lang drukken sleept een app eruit. Lang drukken zonder slepen geeft het menu (bijv. formaat aanpassen, instellen, naam van de map).
  - **Apps toevoegen**: lang drukken op een leeg stuk → *Apps toevoegen* (lijst met vinkjes en zoeken) of *Apps in het dock kiezen*.
  - **Widgets** van alle apps (lang drukken op een leeg stuk → *Widgets toevoegen*, met voorbeeldplaatjes): toestemming per app, instelscherm als de widget dat heeft, formaat aanpassen met grepen (binnen de grenzen van de widget).
  - Alle apps, menu's, de widgetkiezer en *Look aanpassen* komen in een webpagina over het werkblad (modus `overlay`). Bij het eerste gebruik worden de apps en het dock van de skin overgenomen.
  - Het hoofdscherm is standaard **Vandaag** (lang drukken → *Dit als hoofdscherm* op een andere pagina kan ook).
  - **Skin afsluiten** (lang drukken op een leeg stuk, of terug op het hoofdscherm als de skin niet je standaard-startscherm is) vraagt eerst je vingerafdruk of pincode en gaat dan terug naar Tools; is de skin je standaard-startscherm, dan opent daarna de Android-instelling om een ander startscherm te kiezen.
  - **Apps in de skin** (Look aanpassen): kies welke apps zichtbaar zijn; met *Verborgen apps blokkeren* staan verborgen apps ook niet op het startscherm, in het dock of in mappen en zijn ze vanuit de skin niet te starten (`Launcher.launchFromSkin`). Dit wijzigen of een app terughalen vraagt eerst de schermvergrendeling (vingerafdruk/pincode).
  - **Zoeken** (zoekbalk op Vandaag, of in alle apps): apps, notities (met app-slot alleen een knop naar de app, geen inhoud), contacten (met toestemming), Tools en Android-instellingen (`HomeSearch.java`).
  - **Snelkoppelingen** (`LauncherShortcuts`, `PinShortcutActivity`; alleen als standaard-startscherm): lang drukken op een app toont zijn snelkoppelingen (bijv. *Nieuw bericht*); apps kunnen snelkoppelingen op het startscherm zetten (contact, website).
  - **Meldingsbolletjes** (Look aanpassen; via dezelfde meldingentoegang als de meldingsgeschiedenis, `NotifDots`): rood bolletje op apps en mappen met een melding. Er wordt niets bewaard.
  - **Backup**: de indeling (pagina's, mappen, dock, look, vastgezette notities) gaat mee in *Alles back-uppen* (`Startscherm/startscherm.json`) en is terug te zetten; widgets en vastgezette snelkoppelingen niet.
  - **Vangnet**: start het nieuwe startscherm twee keer niet goed op, dan verschijnt de klassieke skin (één scherm). Zelf wisselen kan in *Look aanpassen → Soort startscherm*; na een app-update krijgt de launcher een nieuwe kans.
- Pictogrammen worden als PNG via `/icon/<app>` aan de pagina gegeven en per app-versie bewaard in de cache. Het startscherm wordt samen met de interface stil bijgewerkt (`start.html` in `update.json`).

## Widgets, wekker, herinneringen en Android Auto
- **Widgets voor elk startscherm** (`HomeWidgets.java`): *Agenda* (volgende afspraken; met app-slot alleen een knop), *Weer* (nu, regen de komende uren, 3 dagen; tik = verversen), *Tools overzicht* (backup, herinneringen, Auto redial, radio) en *Parkeren* (start de parkeer-automatisering en toont sinds wanneer hij loopt). *Lijstje* heeft een +-knop om items toe te voegen (`NoteAddActivity`) en een keuze voor donker, licht of doorzichtig.
- **Radio-widget** (`RadioWidget`): zender, wat er nu speelt, afspelen/pauzeren en stoppen; wordt bijgewerkt door `RadioService`. **Herken-widget** (`MusicWidget`): opent Muziek herkennen en begint meteen.
- **Radiowekker** (`RadioAlarm`, `AlarmReceiver`): tijd, dagen en zender; `setAlarmClock` (precies, ook in diepe slaap; `USE_EXACT_ALARM`). Speelt via het wekkervolume (`USAGE_ALARM`), begint zacht en wordt in ~30 s harder; zonder verbinding de gewone wekkertoon. Snooze 10 min in de melding. Na herstarten van de telefoon opnieuw gepland.
- **Notities**: herinnering op datum/tijd (`Reminders`, melding opent de notitie), delen als tekst (☐/☑), en tekst uit andere apps delen naar Tools wordt een nieuwe notitie (lijstjes worden items).
- **Android Auto**: `RadioService` is ook een `MediaBrowserService`; je favorieten (anders de populaire zenders) staan in de auto, met volgende/vorige. Een `PLAY` met eigen stream wordt alleen uitgevoerd met het geheim van de app zelf (de service moet geëxporteerd zijn voor Android Auto).

## Snelkoppelingen
- Houd een tegel ingedrukt → "Snelkoppeling op startscherm" (`ShortcutManager.requestPinShortcut`, Android 8+). Het icoon wordt getekend met de kleur en het symbool van de tool (`Shortcuts.java`).
- Bij de radio: "Zender op startscherm" maakt een icoon dat de app opent en die zender meteen afspeelt.
- Podcast: op de pagina van een podcast "📌 Op startscherm" (of in de speler ⋯ → "Deze podcast op startscherm"). Tikken = Podcasts openen en meteen verder met de aflevering waar je was, anders de nieuwste die je nog niet hebt gehoord; op de Chromecast als je aan het casten bent (`Podcasts.playFromShortcut`).
- "Verder luisteren" (speler ⋯ → "Verder luisteren op startscherm", en bij lang indrukken van het app-icoon): gaat verder met wat het laatst speelde, radio of podcast, en opent de speler.
- Speler-snelkoppelingen werken alleen met het geheim van deze installatie (`App.tokenOk`); een andere app kan er niets mee afspelen.
- Lang indrukken van het app-icoon geeft snelle keuzes: eerst je favorieten, aangevuld met Verder luisteren, Radio, Podcasts, Notities en Auto redial (samen hooguit vier).

## Losse apps
- Een tool als **eigen app** op het startscherm (lang drukken op een tegel → *Als losse app op startscherm…*, of Instellingen → *Losse apps*). Per tool aan of uit; standaard staat alles uit. Niet als losse app: *Alles back-uppen* en *Automatiseringen* (die raken alle tools), de telefoon-skin en de speler. Een losse app kan de backup-map niet veranderen; de kluis vraagt er altijd opnieuw om je vingerafdruk.
- Een losse app (`SoloActivity`, eigen taak met `documentLaunchMode`, eigen naam en pictogram in *recente apps*) opent **zonder app-slot**, toont alleen de schermen van die tool en geen tabbalk, zoeken of knoppen naar andere tools (`45-losse-apps.js`); terug op het eerste scherm zet hem op de achtergrond. Zet je een losse app uit terwijl hij nog open staat, dan sluit hij en opent voortaan de gewone app (met slot). Het app-slot van de gewone app blijft gewoon werken.
- In het overzicht van Tools blijft de tool gewoon staan. Zet je een losse app uit, dan maakt Android de snelkoppeling grijs en opent hij niet meer zonder slot.

## Favorieten
- Houd een tegel ingedrukt → "★ Favoriet (bovenaan)", of tik op ☆ bij Startscherm aanpassen. Favorieten staan bovenaan het startscherm in de groep *★ Favorieten* (zonder groepen: vooraan), in de volgorde die je kiest bij Startscherm aanpassen, en zijn ook de eerste keuzes bij lang indrukken van het app-icoon. Een verborgen tool favoriet maken zet hem weer aan; een favoriet verbergen haalt hem uit de favorieten. *Standaard herstellen* laat de favorieten staan.

