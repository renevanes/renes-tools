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
| 1 | Overzicht van alle toestemmingen en wat Play ervan vindt (hieronder + `.github/scripts/play-toestemmingen.py`) | nee | klaar |
| 2 | Overzicht van diensten buiten de telefoon (basis voor privacybeleid en Data safety-formulier) | nee | klaar |
| 3 | Winkelteksten in `fastlane/metadata/android/nl-NL/` (werkt voor Play én F-Droid) | nee | concept |
| 4 | Privacybeleid op een vaste webpagina (GitHub Pages) | nee | nog doen |
| 5 | Bouwvariant `VARIANT=play` in `build.sh`: eigen applicationId, eigen manifest met alleen toegestane toestemmingen, zonder updater | nee, zolang de standaard `github` blijft | nog doen |
| 6 | `.aab` maken (aapt2 `--proto-format` + bundletool) en een aparte uploadsleutel | nee | nog doen |
| 7 | In de interface tools verbergen die niet in de variant zitten | nee, alleen in de Play-variant | nog doen |
| 8 | Uitleg vóór gevoelige toestemmingen ("prominent disclosure") | ja, ook in de GitHub-versie (verbetering) | eerst overleggen |

## 1. Toestemmingen

Eerste inschatting van het Play-beleid (november 2026); de Play Console heeft het laatste woord.
`python3 .github/scripts/play-toestemmingen.py` vergelijkt deze indeling met `app/AndroidManifest.xml` en meldt
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
| USE_EXACT_ALARM | niet (alleen wekker- en agenda-apps) | herinneringen; SCHEDULE_EXACT_ALARM blijft mogelijk |
| BIND_DEVICE_ADMIN | niet aanbevolen | apparaatbeheer (verouderd voor gewone apps) |
| BIND_ACCESSIBILITY_SERVICE | verklaring, risicovol | automatiseringen, gespreksopname |
| ACCESS_BACKGROUND_LOCATION | verklaring + video | Mijn routes, apparaten, automatiseringen |
| MANAGE_EXTERNAL_STORAGE | verklaring | backups naar een vrij gekozen map |
| SCHEDULE_EXACT_ALARM | verklaring | wekker, herinneringen |
| FOREGROUND_SERVICE_SPECIAL_USE | verklaring | Auto redial |
| FOREGROUND_SERVICE_* (overige typen) | verklaring per type | radio, podcasts, opnemen, backups, locatie |
| BIND_VPN_SERVICE | verklaring | VPN |
| PACKAGE_USAGE_STATS | verklaring | schermtijd/gebruik |
| BIND_NOTIFICATION_LISTENER_SERVICE | ok, gevoelig | meldingsgeschiedenis |
| SYSTEM_ALERT_WINDOW | ok, gevoelig | zwevende vensters |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | verklaring | taken op de achtergrond |
| ACCESS_MOCK_LOCATION | ok | Locatie kiezen (gekozen in ontwikkelaarsopties) |
| overige (internet, contacten, agenda, microfoon, camera, locatie, bluetooth, meldingen, …) | ok | diverse tools |

Gevolg voor een Play-variant: sms-backup, belgeschiedenis, Auto redial, de updater en (waarschijnlijk)
automatiseringen via toegankelijkheid blijven alleen in de GitHub-versie.

## 2. Diensten buiten de telefoon

Voor het privacybeleid en het Data safety-formulier. Alles gebeurt alleen na een keuze van de gebruiker.

| Dienst | Waarvoor | Wat gaat erheen |
|---|---|---|
| GitHub (raw.githubusercontent.com, api.github.com) | updates; Statistieken voor verbeteringen | niets persoonlijks; statistiek alleen tellingen naar de eigen privé-repository |
| AudD (api.audd.io) | muziekherkenning | kort geluidsfragment, eigen API-sleutel |
| Spotify (accounts/api.spotify.com) | Spotify-backup | inloggen via Spotify; gegevens komen naar de telefoon |
| Google (accounts, oauth2, gmail, googleapis, drive) | facturen in Gmail, opslaan in Google Drive | inloggen via Google; facturen naar eigen Drive |
| KPN Zakelijk, Ziggo, Rabobank | facturen en transacties ophalen | inloggen op eigen account; gegevens blijven op de telefoon |
| OpenStreetMap (tile.openstreetmap.org) | kaarten | kaartvakjes van het getoonde gebied |
| Open-Meteo (api/geocoding-api.open-meteo.com) | weer op het startscherm | coördinaten of plaatsnaam |
| Apple (itunes.apple.com) | podcasts zoeken | zoekterm |
| Hugging Face (huggingface.co) | AI-modellen downloaden | niets persoonlijks |
| Radiostreams (o.a. icecast.omroep.nl) | radio | niets persoonlijks |
| Eigen cloud / eigen VPN-server | opslag, VPN | wat de gebruiker zelf instelt |

Bijhouden: komt er een dienst bij, zet hem dan in deze tabel.

## 3. Winkelteksten

`fastlane/metadata/android/nl-NL/` (titel max. 30 tekens, korte beschrijving max. 80, lange max. 4000). Dit is de
indeling die zowel Play-hulpmiddelen (fastlane) als F-Droid lezen. De lange beschrijving noemt alleen tools die naar
verwachting in een Play-versie mogen; pas hem aan zodra die keuze vaststaat. Schermafdrukken komen later in
`images/phoneScreenshots/`.
