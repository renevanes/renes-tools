# Voorbereiding op een Google Play-versie

Status: **voorbereiding**. Er is nog geen Play-versie en er verandert niets aan de app zoals hij nu via GitHub wordt
verspreid en bijgewerkt.

## Uitgangspunt

- **De GitHub-versie blijft precies zoals hij is**: alle tools, alle toestemmingen en de eigen updater
  (`update/` op main, binnen ±15 minuten op de telefoon). Die manier van bijwerken bevalt goed en blijft de hoofdroute.
- Een eventuele Play-versie is een **tweede, aparte uitvoering** van dezelfde broncode, met een eigen naam in de
  winkel en een eigen applicationId (bijv. `nl.rene.tools.play`). Beide kunnen naast elkaar op één telefoon staan.
- In die Play-versie zitten alleen de tools die door de Play-beoordeling komen, en daar regelt Google Play de updates.
  Dat moet: Play-apps mogen zichzelf niet buiten Play om bijwerken.

## Stappen

| # | Stap | Raakt de huidige app? | Status |
|---|---|---|---|
| 1 | Overzicht van alle toestemmingen en wat Play ervan vindt (hieronder, `play/indeling.json`, `.github/scripts/play-toestemmingen.py`) | nee | klaar |
| 2 | Overzicht van diensten buiten de telefoon (basis voor privacybeleid en Data safety-formulier) | nee | klaar |
| 3 | Winkelteksten in `fastlane/metadata/android/nl-NL/` (werkt voor Play én F-Droid) | nee | concept |
| 4 | Proefbouw `./build-play.sh`: eigen pakket `nl.rene.tools.play`, eigen manifest zonder wat Play niet toestaat, `.aab` | nee (los script, `build.sh` blijft gelijk) | klaar |
| 5 | Privacybeleid op een vaste webpagina (GitHub Pages) | nee | nog doen |
| 6 | Uploadsleutel maken en als geheim bewaren (Rene; Google ondertekent de winkelversie zelf) | nee | nog doen |
| 7 | In de Play-versie de updater uitzetten en tools verbergen waarvan de toestemming ontbreekt | geen gedrag, wel een nieuwe APK (code in `app/` en `web/`) | eerst overleggen |
| 8 | Uitleg vóór gevoelige toestemmingen ("prominent disclosure") | ja, ook in de GitHub-versie (verbetering) | eerst overleggen |

## Proefbouw (`./build-play.sh`)

Maakt `build-play/Renes-Tools-play-vX.Y.aab` uit dezelfde bron, zonder iets aan de gewone bouw, `update/` of de
telefoon te veranderen:
- manifest uit `play/manifest.py`: zonder de toestemmingen die in `play/indeling.json` op `niet` of `streng` staan,
  zonder de toegankelijkheidsdiensten en het apparaatbeheer, `SCHEDULE_EXACT_ALARM` zonder `maxSdkVersion`;
- pakketnaam `nl.rene.tools.play` en providers `nl.rene.tools.play.*`, zodat hij naast de GitHub-versie kan staan
  (de providernamen worden alleen in een kopie van de Java-bron vervangen);
- `.aab` via aapt2 `--proto-format` en bundletool (vaste versie en SHA-256), gecontroleerd met `bundletool validate`;
- ondertekend met de uploadsleutel als `PLAY_KS` en `PLAY_KS_PASS_FILE` gezet zijn, anders niet ondertekend.

Nog niet geschikt voor de winkel: de updater zit er nog in en tools waarvan de toestemming weg is, staan nog in de
interface (stap 7). De proefbouw draait niet in CI; hij is alleen hier gecontroleerd (pakketnaam, toestemmingen en
providers in een universele APK uit de bundel), niet op een telefoon.

## 1. Toestemmingen

Eerste inschatting van het Play-beleid (november 2026); de Play Console heeft het laatste woord.
De indeling staat in `play/indeling.json`. `python3 .github/scripts/play-toestemmingen.py` vergelijkt die met `app/AndroidManifest.xml` en meldt
toestemmingen die nog niet zijn ingedeeld. Het script draait niet in de tests en houdt niemand tegen.

- **ok** — gewone toestemming, of een runtime-toestemming die met uitleg aan de gebruiker mag.
- **verklaring** — mag, maar vraagt een formulier in de Play Console (vaak met een video) en een duidelijke reden die
  bij de hoofdfunctie van de app past.
- **streng** — alleen voor een smalle groep apps; voor een verzamel-app als Tools vrijwel kansloos.
- **niet** — mag niet in een Play-app.

| Toestemming | Play | Gebruikt door |
|---|---|---|
| READ_SMS | streng | Sms-backup, zoeken, lokale assistent, facturen |
| READ_CALL_LOG | streng | Belgeschiedenis(-backup), Auto redial, opnames uitschrijven, zoeken |
| REQUEST_INSTALL_PACKAGES | niet | eigen updater |
| USE_EXACT_ALARM | niet (alleen wekker- en agenda-apps) | radiowekker, herinneringen, automatiseringen op tijd |
| BIND_DEVICE_ADMIN | niet | Gebruik: mislukte ontgrendelingen bijhouden |
| BIND_ACCESSIBILITY_SERVICE (gespreksopname) | niet | gespreksopname (`CallRecordingAccess`) |
| BIND_ACCESSIBILITY_SERVICE (automatiseringen) | verklaring, risicovol | Automatiseringen (`AutoA11y`) |
| ACCESS_BACKGROUND_LOCATION | verklaring + video | Automatiseringen, Apparaten |
| MANAGE_EXTERNAL_STORAGE | verklaring, streng | WhatsApp-backup en Gesprekken uitschrijven (mappen van andere apps lezen) |
| SCHEDULE_EXACT_ALARM | verklaring | vervanger van USE_EXACT_ALARM; in een Play-manifest zonder `maxSdkVersion="32"` |
| FOREGROUND_SERVICE_SPECIAL_USE | verklaring | Auto redial, VPN |
| FOREGROUND_SERVICE_* (overige typen) | verklaring per type | radio, podcasts, opnemen, backups, locatie |
| BIND_VPN_SERVICE | verklaring | VPN |
| PACKAGE_USAGE_STATS | verklaring | schermtijd/gebruik |
| BIND_NOTIFICATION_LISTENER_SERVICE | ok, gevoelig | meldingsgeschiedenis |
| SYSTEM_ALERT_WINDOW | ok, gevoelig | zwevende vensters; Gebruik (ontgrendelfoto) |
| CAMERA (onzichtbare foto bij mislukte ontgrendeling) | niet | Gebruik (`GebruikShot`) |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | verklaring | taken op de achtergrond |
| ACCESS_MOCK_LOCATION | ok | Locatie kiezen (gekozen in ontwikkelaarsopties) |
| overige (internet, contacten, agenda, microfoon, camera, locatie, bluetooth, meldingen, …) | ok | diverse tools |

Gevolg voor een Play-variant: sms-backup, belgeschiedenis, Auto redial, gespreksopname, de ontgrendelfoto van Gebruik
en de updater blijven alleen in de GitHub-versie. Automatiseringen via toegankelijkheid, WhatsApp-backup en Gesprekken
uitschrijven zijn onzeker.

## 2. Diensten buiten de telefoon

Voor het privacybeleid en het Data safety-formulier. Bijna alles gebeurt pas als de gebruiker een tool gebruikt of
instelt. Uitzonderingen: de updatecontrole (vanzelf, ongeveer elk half uur als de app open is) en het weer op het
startscherm (laatst bekende locatie).

| Dienst | Waarvoor | Wat gaat erheen |
|---|---|---|
| GitHub (raw.githubusercontent.com, api.github.com) | updates; statistieken voor verbeteringen | updates: niets persoonlijks; statistiek: tellingen, telefoonmodel en Android-versie, alleen naar de eigen privé-repository |
| AudD (api.audd.io) | muziekherkenning | kort geluidsfragment, eigen API-sleutel |
| Spotify (accounts/api.spotify.com) | Spotify-backup en afspeellijsten | inloggen via Spotify; gegevens komen naar de telefoon, afspeellijsten kunnen worden aangepast |
| Google (accounts, oauth2, gmail, googleapis, drive) | facturen in Gmail, opslaan in Google Drive, facturen mailen | inloggen via Google; facturen naar eigen Drive; mail naar zelf ingestelde ontvangers |
| Eigen mailserver (SMTP) | facturen aanleveren | facturen naar zelf ingestelde ontvangers |
| KPN Zakelijk, Ziggo, Rabobank | facturen en transacties ophalen | inloggen op eigen account; gegevens blijven op de telefoon |
| OpenStreetMap (tile.openstreetmap.org) | kaarten | kaarttegels van het getoonde gebied |
| Open-Meteo (api/geocoding-api.open-meteo.com) | weer op het startscherm | coördinaten of plaatsnaam |
| Apple (itunes.apple.com, rss.applemarketingtools.com) | podcasts zoeken, top 40 | zoekterm |
| Podcastfeeds en hun servers | afleveringen en afbeeldingen | niets persoonlijks (elke host mogelijk) |
| radio-browser.info | radiozenders zoeken | zoekterm, telling bij afspelen |
| Hugging Face (huggingface.co) | AI- en spraakmodellen downloaden | niets persoonlijks |
| Radiostreams en zenderlogo's (o.a. icecast.omroep.nl) | radio | niets persoonlijks (elke host mogelijk) |
| Eigen cloud / eigen VPN-server | opslag, VPN | wat de gebruiker zelf instelt |

Bijhouden: komt er een dienst bij, zet hem dan in deze tabel.

## 3. Winkelteksten

`fastlane/metadata/android/nl-NL/` (titel max. 30 tekens, korte beschrijving max. 80, lange max. 4000). Dit is de
indeling die zowel Play-hulpmiddelen (fastlane) als F-Droid lezen. De lange beschrijving noemt alleen tools die naar
verwachting in een Play-versie mogen; pas hem aan zodra die keuze vaststaat. Schermafdrukken komen later in
`images/phoneScreenshots/`.
