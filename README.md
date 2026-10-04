# Rene's Tools

Android-app met handige tools. De eerste tool is **Auto redial**: die belt een nummer opnieuw tot er wordt opgenomen.

**Downloaden:** [update/Renes-Tools.apk](https://github.com/renevanes/renes-tools/raw/main/update/Renes-Tools.apk). Werkt op Android 7 en nieuwer.

## Auto redial
- Typ een nummer of kies een contact. Je laatste 5 nummers worden onthouden.
- Kies het aantal pogingen (5, 10, 25, 50 of onbeperkt) en de wachttijd ertussen (5 seconden tot 1 minuut).
- Zet eventueel de luidspreker aan.
- De voortgang staat in de meldingenbalk, met de knoppen **Stoppen** en **Nu bellen**.
- Of er is opgenomen, leest de app af uit de oproepgeschiedenis. Een gesprek dat verbonden is geweest, telt als opgenomen. Een voicemail telt dus ook als opgenomen.
- Zonder toegang tot de oproepgeschiedenis telt een gesprek van langer dan 1 minuut als opgenomen.
- **Op een tijdstip** (`RedialPlan.java`): eenmalig of op gekozen dagen (bijv. werkdagen 08:00 de huisarts). Een precieze wekker start Auto redial met het nummer en de instellingen van dat moment; staat Android het starten niet toe (geen precieze wekkers), dan komt er een melding; één tik daarop start Auto redial met de geplande instellingen. Een eenmalige planning die gemist is doordat de telefoon uit stond, vervalt (tot 10 minuten te laat wordt hij nog uitgevoerd). Staat ook in het overzicht op het startscherm.

## WhatsApp backup
- Kopieert de lokale WhatsApp-map (`Android/media/com.whatsapp/WhatsApp`, en WhatsApp Business) naar een map die je zelf kiest: een USB-stick, SD-kaart of OneDrive/Dropbox via de bestandskiezer van Android. Alles komt in de submap `WhatsApp backup/`.
- **Alles back-uppen**: chats (Databases + Backups) en alle media, behalve statussen. **Selectie**: kies zelf welke onderdelen.
- **Automatisch**: elke nacht na 03:00, eventueel alleen tijdens het opladen. WhatsApp maakt om 02:00 zijn eigen backup.
- Kopieert alleen nieuwe en gewijzigde bestanden en verwijdert nooit iets uit de backup. Een bestaand bestand wordt eerst volledig naar `.rt-part` gekopieerd en pas daarna vervangen, zodat een onderbroken kopie de vorige versie niet kapotmaakt.
- **Terugzetten**: zet ontbrekende bestanden terug in de WhatsApp-map. Chatbestanden op de telefoon die nieuwer zijn dan de backup worden nooit overschreven.
- Heeft *Toegang tot alle bestanden* nodig om de WhatsApp-map te lezen. De chatdatabase is versleuteld door WhatsApp; alleen WhatsApp kan hem terugzetten.

## Leesbare chats
- Ontsleutelt de WhatsApp-backup met de sleutel van 64 cijfers die WhatsApp toont bij de end-to-end versleutelde back-up (crypt15). De afgeleide AES-sleutel is HMAC-SHA256(HMAC-SHA256(0, root), "backup encryption"||0x01); de database wordt in GCM/CTR gelezen en zlib-gedecomprimeerd (`WaCrypt.java`).
- Leest `message`/`chat`/`jid`(+`jid_map` voor privacy-ID's) uit de ontsleutelde SQLite en toont de chats in de app, met zoeken (`WaChats.java`). Namen komen uit de contacten als die toestemming is gegeven.
- Exporteert per chat een HTML-bestand naar `WhatsApp backup/Leesbare chats/`, met foto's uit de Media-map. In de app worden foto's getoond via een afgeschermde `https://app.renes-tools.local/wa-media/`-route (`MediaActivity`/`MediaClient`).
- De sleutel staat alleen in de app, op de telefoon. Na een gewone of automatische backup worden de chats desgewenst vanzelf opnieuw leesbaar gemaakt.

## Mijn routes
- GPS-routeopname (zoals het vroegere My Tracks) via een voorgrondservice met `LocationManager` (geen Google Play Services). Loopt door met het scherm uit; pauzeren/hervatten kan (`TracksService.java`).
- Live afstand (haversine), tijd, snelheid, hoogte; de route wordt als SVG-pad getekend zonder kaarttegels. Statistiek en GPX-schrijven zitten in `Tracks.java` (pure Java, los getest).
- Ruisfilter tegen gps-sprongen (`Tracks.Smoother`): metingen >30 m onnauwkeurigheid worden overgeslagen, een venster van metingen wordt gemiddeld, en een nieuw routepunt komt er pas bij een duidelijke verplaatsing (≥5 m, meer bij slecht signaal of gemelde stilstand). Max. snelheid over vensters van ≥10 s. Live-cijfers gebruiken exact hetzelfde filter als bij het terugkijken.
- Punten worden per stuk naar `tracks/live.trk` weggeschreven (crashbestendig) en bij stoppen hernoemd naar `<tijd>__<titel>.trk`.
- Routes terugkijken met hoogteprofiel, delen als GPX (eigen `GpxProvider`, geen AndroidX) of opslaan in de backup-map onder `Routes/`.
- Routedetail toont de route op een echte kaart (Leaflet + OpenStreetMap-tegels, meegeleverd in de assets en geserveerd via de `/vendor/`-route van `MediaClient`). Knop "Openen in Google Maps" opent de route (start→eind) in Google Maps.
- Nodig: locatietoestemming en `FOREGROUND_SERVICE_LOCATION`.

## SMS-backup
- Leest de eigen sms-berichten via `content://sms` (READ_SMS), toont gesprekken in de app met zoeken, en exporteert naar de gekozen backup-map onder `SMS backup/` (`Sms.java`).
- Twee uitvoerbestanden: een herstelbaar XML in het formaat van "SMS Backup & Restore", en per gesprek een leesbare HTML-pagina + index. Export draait op een achtergrondthread met voortgang in de voorkeuren; de interface pollt `smsStatus()`.
- Namen komen uit de contacten als die toestemming is gegeven. Alleen lezen; er wordt niets op de telefoon gewijzigd. Herstellen gaat via de app "SMS Backup & Restore" (standaard-sms-app worden is op moderne Android nodig om terug te schrijven).
- XML-escaping behoudt regeleindes/tabs (numerieke entiteiten) en laat in XML verboden stuurtekens weg, zodat het backupbestand altijd herstelbaar blijft.

## Oproepen-backup
- Leest de eigen oproepgeschiedenis via `CallLog.Calls` (READ_CALL_LOG, die de app al had voor Auto redial): inkomend, uitgaand, gemist, geweigerd (`Calls.java`).
- In de app: totalen (aantal en beltijd), filter Alle/Inkomend/Uitgaand/Gemist, zoeken op naam of nummer (laatste 500). Tik op een oproep om het nummer in Auto redial te zetten.
- **Koppeling met contacten**: elk nummer wordt (op de laatste 9 cijfers) aan een contact gekoppeld (`Calls.who`). Vanuit een oproep: contact bekijken, toevoegen aan contacten (onbekend nummer), alle oproepen met die persoon, of opnieuw bellen met Auto redial. In Contacten toont elk contact zijn oproepen met totalen en een knop naar al die oproepen.
- **Filters**: richting, zoeken, periode (vandaag, gisteren, 7/30 dagen, dit jaar of zelf kiezen), wie (contacten, favorieten, onbekende nummers), duur (niet verbonden, ≥1/5/15 min) en één persoon. Totalen gelden voor de selectie; een selectie kan apart geëxporteerd worden (eigen bestandsnamen).
- Export naar `Oproepen backup/` in de backup-map: een herstelbaar XML in het `<calls>`-formaat van "SMS Backup & Restore", een CSV (puntkomma, UTF-8 met BOM, voor Excel) en een leesbare HTML-tabel. Alleen lezen.

## Notities
- Notities met lijstjes: items toevoegen (meerdere regels plakken = meerdere items), afstrepen, bewerken, afgevinkte items opruimen, plus een vrij tekstveld. Zoeken door titels, items en tekst met markering (`Notes.java` + interface).
- Opslag in de app-map (`notes.json`, via tijdelijk bestand + hernoemen). Lege notities worden niet bewaard.
- Knop *Kopie in backup-map zetten*: `Notities/notities.json` en een leesbaar `notities.txt` met `[x]`/`[ ]`.

## Contacten
- Leest de contacten via `ContactsContract` (naam, telefoon, e-mail, bedrijf/functie, adres, website, bijnaam, verjaardag, notitie, favoriet) met de wijzigingsdatum van Android (`CONTACT_LAST_UPDATED_TIMESTAMP`) (`Contacts.java`).
- **Versies**: bij openen van de tool en elke ~6 uur (`ContactsJob`, JobScheduler) wordt vergeleken met de vorige versie; alleen bij een echte wijziging komt er een nieuwe versie. Per versie: datum, aantallen (+nieuw, ~gewijzigd, −verwijderd) en per contact welke velden van wat naar wat gingen. Koppeling op lookup key, daarna op naam (`ContactsDiff.java`, pure Java, los getest).
- Opslag in de app-map onder `contacts/`: `index.json` (alle versies met wijzigingen; van verwijderde contacten de volledige laatste gegevens) en van de laatste 60 versies de volledige lijst (`v<N>.json.gz`). Schrijven via tijdelijk bestand + hernoemen.
- **Beheer**: zoeken (naam, nummer, e-mail, bedrijf), contactdetails met wijzigingsgeschiedenis, bewerken en nieuw contact via de contacten-app van Android, verwijderde contacten terugzetten (WRITE_CONTACTS, als nieuw contact in de telefoon-opslag).
- **Backup** naar `Contacten backup/`: `contacten-JJJJ-MM-DD.vcf` (vCard 3.0, met `REV` = wijzigingsdatum), desgewenst `contacten-versie-N.vcf` van een oudere versie, en `wijzigingslog.txt` met alle versies en wijzigingen.

## Gesprekken uitschrijven
- Android (10+) laat gewone apps het geluid van een telefoongesprek niet opnemen. Deze tool schrijft daarom de opnames uit die de **telefoon-app van het toestel** maakt (Oppo/ColorOS: ODialer → Instellingen → Gespreksopname). Opnames van de Google Telefoon-app zijn voor andere apps afgeschermd.
- Opnames vinden: bekende mappen (`Music/Recordings/Call Recordings`, `Recordings/Call`, MIUI, Samsung ...), MediaStore (pad met "Call") en een zelf gekozen map. Nodig: toegang tot alle bestanden (had de app al voor WhatsApp backup).
- Koppelen aan de oproepgeschiedenis op tijdstip/duur en nummer in de bestandsnaam (`Transcribe.matchCall`).
- Uitschrijven gebeurt **op de telefoon** met [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT): een statisch gelinkt arm64-programma, gebouwd met `tools/whisper-build.sh` (zig, zonder NDK) en meegeleverd als `lib/arm64-v8a/libwhisper.so` (armv8.2 + dotprod) en `libwhisper_generic.so` (elke arm64; automatische terugval bij "illegal instruction"). `extractNativeLibs=true`, zodat Android het uitpakt in `nativeLibraryDir`, van waaruit het uitgevoerd mag worden.
- Audio wordt met `MediaExtractor`/`MediaCodec` gedecodeerd naar 16 kHz mono WAV. Spraakmodel (`ggml-base-q5_1.bin` 57 MB of `ggml-small-q5_1.bin` 181 MB) wordt eenmalig van Hugging Face gedownload naar de app-map. Er gaat geen geluid of tekst de telefoon uit.
- Draait in een voorgrondservice (`TranscribeService`, dataSync) met wachtrij, voortgang en stoppen. Transcripten (`files/transcripts/<id>.json`) met tijdcodes; afspelen vanaf een zin, zoeken door alle transcripten, export als tekst naar `Gesprekken/` in de backup-map.
- **'s Nachts automatisch** (`TranscribeJob.java`): vanaf 01:00, alleen tijdens opladen, worden nieuwe opnames van de laatste 14 dagen uitgeschreven (max. 30 per nacht). Het werk loopt in de voorgrondservice (met melding). Android 12+ staat dat op de achtergrond alleen toe met een batterij-uitzondering; zonder komt er een melding om het met één tik te starten. Mislukte opnames worden niet elke nacht opnieuw geprobeerd (knop om ze toch opnieuw te proberen).

## Radio
- Nederlandse zenders uit de vrije database van [radio-browser.info](https://www.radio-browser.info) (`/json/stations/search?countrycode=NL`, populairste eerst, zoeken op naam; meerdere API-servers met terugval). Zo kloppen stream-adressen ook als omroepen ze wijzigen. Favorieten (met stream-adres) lokaal, laatste lijst in een cache (`Radio.java`).
- Afspelen in `RadioService` (voorgrondservice `mediaPlayback`, `MediaPlayer`): doorspelen met scherm uit, bediening in de melding en op het vergrendelscherm (`MediaSession` + `MediaStyle`), audiofocus (pauzeert bij een gesprek, zachter bij navigatie), pauze bij loskoppelen koptelefoon, 3× opnieuw verbinden bij haperingen, slaaptimer.
- Wat de zender meestuurt (`Radio.streamInfo`): uit de ICY-headers naam, omschrijving (vaak het programma), genre, website en bitrate; uit de metadata in de stream de huidige titel (gesplitst in artiest en titel als dat kan) en soms een extra link. Elke 20 s ververst; de laatste 20 titels als "Eerder gedraaid".
- Slaaptimer: vaste tijden, een eigen aantal minuten of tot een tijdstip.
- `usesCleartextTraffic="true"`, omdat veel radiostreams nog via http lopen.

### Pauzeren en terugspoelen
- `Timeshift.java`: de stream wordt doorlopend opgenomen in een ringbuffer van 48 MB in de cache (ca. 50 min bij 128 kbps) en via een klein HTTP-servertje op 127.0.0.1 aan MediaPlayer gegeven, vanaf elke plek in de buffer. Werkt voor MP3- en AAC-streams; andere formaten (HLS, Ogg) spelen gewoon live.
- Pauze houdt je plek vast (de stream loopt door, max. 1 uur); verder gaat precies waar je was. Ook na een telefoongesprek ga je verder waar je was. Knoppen −30 s / +30 s / Live in de app, −30 s in de melding, en terug/vooruit via de mediaknoppen en Android Auto.
- Uit te zetten in het radioscherm ("Pauzeren en terugspoelen").

## Toegankelijkheid
- **Tekstgrootte** (Instellingen → Weergave): volgt standaard de lettergrootte van de telefoon, of kies kleiner tot maximaal (175%). Via `WebSettings.setTextZoom`; de indeling loopt mee zonder horizontaal scrollen.
- **TalkBack**: alle knoppen en velden hebben een naam; schakelaars zijn `role=switch` met aan/uit, keuzeknoppen melden of ze gekozen zijn; meldingen (`role=status`), dialogen (`role=dialog`) en bij elk scherm springt de focus naar de titel.

## Muziek herkennen
- Eigen "SoundHound": 10 seconden opnemen via de microfoon (`MediaRecorder`, AAC) of rechtstreeks een stukje van de radiostream ophalen, en dat laten herkennen door [AudD](https://audd.io) met de eigen API-sleutel van de gebruiker (300 gratis, daarna betaald). Een app kan dit niet zelf: daarvoor is een database van tientallen miljoenen nummers nodig (`Music.java`).
- Resultaat met hoes, album en jaar en links naar Spotify, Apple Music, YouTube Music en alle diensten (song.link). Geschiedenis (max. 500) lokaal, doorzoekbaar.
- Alleen het fragment gaat naar AudD; de opname wordt direct verwijderd. Nodig: `RECORD_AUDIO` (alleen bij herkennen via de microfoon).

## Startscherm
- Tegels in groepen (Bellen & contacten, Berichten & backup, Muziek & radio, Handig). Via "Startscherm aanpassen": volgorde (ook naar een andere groep), tools verbergen, groepen hernoemen/toevoegen, zonder groepen tonen en een compacte weergave met drie tegels naast elkaar. Opgeslagen in de WebView-opslag; nieuwe tools uit een update komen vanzelf in hun groep.
- **Overal zoeken** (zoekveld bovenaan het startscherm, `Search.java`): zoekt tegelijk in notities, contacten, sms'jes, WhatsApp-chats (als die leesbaar gemaakt zijn), oproepen, uitgeschreven gesprekken en herkende muziek. Per soort de eerste vijf treffers; tik om het te openen, of op "Alle …" om verder te zoeken in die tool. Alleen bronnen waarvoor toestemming is gegeven doen mee.
- **Overzicht** bovenaan (uit te zetten bij Startscherm aanpassen): laatste backup (oranje als die al dagen oud is, rood bij een mislukt onderdeel), de volgende radiowekker, herinneringen van vandaag, wat de radio speelt en problemen uit de laatste zelftest. Tik op een regel om naar die tool te gaan.

## Widgets, wekker, herinneringen en Android Auto
- **Radio-widget** (`RadioWidget`): zender, wat er nu speelt, afspelen/pauzeren en stoppen; wordt bijgewerkt door `RadioService`. **Herken-widget** (`MusicWidget`): opent Muziek herkennen en begint meteen.
- **Radiowekker** (`RadioAlarm`, `AlarmReceiver`): tijd, dagen en zender; `setAlarmClock` (precies, ook in diepe slaap; `USE_EXACT_ALARM`). Speelt via het wekkervolume (`USAGE_ALARM`), begint zacht en wordt in ~30 s harder; zonder verbinding de gewone wekkertoon. Snooze 10 min in de melding. Na herstarten van de telefoon opnieuw gepland.
- **Notities**: herinnering op datum/tijd (`Reminders`, melding opent de notitie), delen als tekst (☐/☑), en tekst uit andere apps delen naar Rene's Tools wordt een nieuwe notitie (lijstjes worden items).
- **Android Auto**: `RadioService` is ook een `MediaBrowserService`; je favorieten (anders de populaire zenders) staan in de auto, met volgende/vorige. Een `PLAY` met eigen stream wordt alleen uitgevoerd met het geheim van de app zelf (de service moet geëxporteerd zijn voor Android Auto).

## Snelkoppelingen
- Houd een tegel ingedrukt → "Snelkoppeling op startscherm" (`ShortcutManager.requestPinShortcut`, Android 8+). Het icoon wordt getekend met de kleur en het symbool van de tool (`Shortcuts.java`).
- Bij de radio: "Zender op startscherm" maakt een icoon dat de app opent en die zender meteen afspeelt.
- Lang indrukken van het app-icoon geeft snelle keuzes: Radio, Muziek herkennen, Notities en Auto redial.

## Alles back-uppen
- Eén knop voor sms, oproepen, contacten (+ wijzigingslog), notities, uitgeschreven gesprekken en herkende muziek naar de backup-map, met per onderdeel de uitkomst (`AllBackup.java`). Onderdelen zijn aan/uit te zetten; optioneel tegelijk de WhatsApp-backup.
- Automatisch elke nacht na 03:30 (`AllBackupJob`, JobScheduler, desgewenst alleen tijdens opladen). Alleen een melding als er iets misging.
- **Terugzetten** (`Restore.java`): contacten uit een vCard-bestand (`VCard.java` leest 2.1/3.0/4.0, ook van andere telefoons: gevouwen regels, quoted-printable, tekensets, item-groepen) en notities uit `notities.json`. Eerst een overzicht (nieuw / al aanwezig), dan alleen het nieuwe toevoegen; nooit overschrijven of verwijderen.

## Versleutelde backups, opruimen en ruimtegebruik
- **Versleutelen** (Alles back-uppen → Versleutelen): met een wachtwoord gaat de hele backup in één archief `Versleuteld/backup-JJJJ-MM-DD_UUMM.rtb` in plaats van leesbare bestanden. Formaat (`Vault.java`): zip, in blokken van 64 kB versleuteld met AES-256-GCM; sleutel via PBKDF2-HMAC-SHA256 (210.000 rondes, SHA-1 op Android 7). Afkappen of wijzigen van het bestand wordt altijd opgemerkt. Het wachtwoord wordt niet bewaard; de afgeleide sleutel staat op de telefoon versleuteld met een sleutel uit de Android-sleutelopslag, zodat de nachtelijke backup zonder wachtwoord kan.
- **Openen**: in de app via Terugzetten → Versleutelde backup openen (contacten of notities terugzetten, of uitpakken naar `Uitgepakt/`); op de computer met `ontsleutelen.html` in de map Versleuteld (werkt in de browser, zonder internet).
- **Opruimen** (`Rotate.java`): na elke backup (uit te zetten) of met "Nu opruimen": van sms-, oproepen-, contacten- en versleutelde backups met een datum in de naam blijft alles van de laatste 14 dagen staan, en daarvoor de nieuwste per maand. Selecties, contactversies en de allernieuwste blijven altijd.
- **Ruimtegebruik**: grootte en aantal bestanden per map in de backup-map.

## Instellingen, app-slot en foutrapport
- **Instellingen**: app-slot, backup-map, AudD-sleutel, overzicht van alle toestemmingen (met "Toestaan" per onderdeel) en het foutrapport.
- **App-slot** (`Lock.java`): ontgrendelen met de schermvergrendeling van de telefoon (BiometricPrompt met vingerafdruk/gezicht of pincode; Android 7–9 via het bevestigingsscherm van de telefoon). Geen eigen pincode, dus niets te vergeten. Vergrendelt na een instelbare tijd buiten beeld; inhoud verborgen in "recente apps" (Android 13+: `setRecentsScreenshotEnabled(false)`, ouder: `FLAG_SECURE`).
- **Foutrapport** (`App.java`): een eigen `Application` legt elke crash (ook in achtergrondservices) en elke JavaScript-fout vast in `files/crash.log`. Na een crash vraagt de app bij de volgende start of je het rapport wilt delen. Het rapport bevat alleen toestel, versie en foutmeldingen.
- **Zelftest** (`SelfTest.java`): controleert op de telefoon zelf het uitschrijfprogramma (Whisper draait echt), schrijven in de backup-map, internet en een radiostream, de AudD-sleutel (zonder herkenning te verbruiken), exacte wekkers, meldingen, batterijbeperking (belangrijk op Oppo/ColorOS), vrije opslag, toestemmingen en hoeveel contacten/oproepen/sms'jes leesbaar zijn. Bij elk probleem een knop om het op te lossen; de uitkomst gaat mee in het foutrapport.
- **Wat is er nieuw**: na een update eenmalig de wijzigingen sinds de vorige versie die je gebruikte.

## Versies en updates
- `VERSION` bevat het versienummer, de versiecode en `NATIVE_LEVEL`. Elke versie is in git één eigen commit met de naam "Versie X.Y".
- `changelog.json` bevat het wijzigingslog. De app toont dit onder *Versie en updates*.
- De app leest `update/update.json` van GitHub wanneer je hem opent, hooguit eens per 30 minuten. Via de GitHub-API zoekt hij eerst de nieuwste commit op, zodat een nieuwe versie direct zichtbaar is:
  - **Alleen de interface is veranderd** (`web/src/`, `NATIVE_LEVEL` blijft gelijk): de nieuwe versie wordt stil gedownload, gecontroleerd met SHA-256 en direct gebruikt.
  - **`NATIVE_LEVEL` is hoger** (de Java-code of het manifest is veranderd): de app toont *Nieuwe versie beschikbaar* met een knop **Installeren**. Die downloadt de APK, controleert hem en biedt hem aan via de pakketinstaller van Android.

## Bouwen (zonder Android Studio of Gradle)
```sh
./setup-tools.sh              # eenmalig: build-tools 34, aapt2, android.jar (SDK 36)
./keys/unlock.sh              # eenmalig per sessie: ondertekeningssleutel uitpakken (vraagt de wachtwoordzin)
./bump.sh "Wat er veranderd is"          # versie +0.1, met regel voor het wijzigingslog
NATIVE=1 ./bump.sh "Nieuwe app-functie"   # als de Java-code of het manifest is veranderd
./build.sh                    # bouwt de APK en vult update/
./publish.sh                  # commit "Versie X.Y" en push naar GitHub
```

### Interface in onderdelen
De interface is één pagina (`web/index.html`) voor de WebView, maar wordt bewerkt in losse onderdelen in `web/src/`:
`stijl.css`, `schermen/NN-*.html` (één per scherm), `vensters.html` (meldingen, dialogen) en `script/NN-*.js` (per tool; `01-nepbrug.js` is de nagebootste Android-brug voor tests in de browser).
`tools/web-samenvoegen.py` voegt ze in de volgorde van `web/src/volgorde.txt` samen; `build.sh` en `tests/run-ui.sh` doen dat zelf. `web/index.html` wordt dus gemaakt en staat niet in git.

### Testen
```sh
./tests/run-ui.sh            # interfacetests: Playwright, 390x844, licht en donker, met nagebootste Android-brug
javac -cp build/classes:$TOOLS/ap/android-36/android.jar -d build/test tests/jvm/nl/rene/tools/*.java
java -cp build/test:build/classes:$TOOLS/ap/android-36/android.jar nl.rene.tools.LogicTest       # logica (contactversies, vCard, wekker, versleutelen, opruimen)
java -cp build/test:build/classes:$TOOLS/ap/android-36/android.jar nl.rene.tools.TimeshiftTest   # radiobuffer
```
Bij elke push bouwt GitHub Actions (`.github/workflows/bouwen-en-testen.yml`) de app en draait alle tests. Die APK is ondertekend met een tijdelijke testsleutel en dient alleen als controle; echte updates gaan via `./publish.sh` met de echte sleutel.

De ondertekeningssleutel staat alleen versleuteld in de repository (`keys/signing-key.enc`, AES-256). Rene heeft de wachtwoordzin. Elke update moet met dezelfde sleutel ondertekend zijn, anders kan de telefoon hem niet installeren.

Let op bij Java-code: gebruik geen anonieme of niet-statische binnenklassen. javac 21 geeft hun constructor een parameter zonder naam, en d8 8.2 loopt daarop vast. Gebruik statische geneste klassen of lambda's.
