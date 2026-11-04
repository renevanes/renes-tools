# Privacybeleid van Rene's Tools

*Laatst bijgewerkt: 4 november 2026*

Rene's Tools (in de app: "Tools") is een Android-app van Rene van Es (Rotterdam) met hulpmiddelen zoals notities,
PDF's, radio, podcasts, routes, backups, VPN, facturen, bankzaken en een lokale assistent. De app is vrije software:
de volledige broncode staat openbaar op [GitHub](https://github.com/renevanes/renes-tools), zodat iedereen kan nagaan
wat hieronder staat.

Er zijn twee uitvoeringen: de **GitHub-versie** (te downloaden van GitHub, werkt zichzelf bij) en een eventuele
**Play-versie** (via Google Play). Dit beleid geldt voor allebei; wat alleen in de GitHub-versie zit, staat apart
aangegeven.

## Het uitgangspunt

- De app heeft **geen eigen server** en geen account. Er is geen database bij de maker waarin jouw gegevens terechtkomen.
- De maker **verzamelt, verkoopt en deelt geen persoonsgegevens** en verwerkt dus ook geen persoonsgegevens van jou.
  Er zitten geen advertenties in de app en geen advertentie- of analysediensten van derden.
- Wat de app leest of wat je in de app maakt, wordt **op je telefoon** verwerkt en bewaard. Het gaat alleen naar
  buiten als je daar zelf voor kiest (een tool die een dienst gebruikt, een backupplek, je eigen cloud) of in de
  gevallen die hieronder staan.
- Wachtwoorden, VPN-sleutels, koppelingen (zoals Spotify en Google) en je bankgegevens worden op de telefoon
  **versleuteld** bewaard. Bankzaken verlaten de telefoon nooit.
- De lokale assistent, slim zoeken en het uitschrijven van gesprekken werken **op de telefoon zelf**. Er gaat niets naar
  een AI-dienst op internet; alleen het model wordt eenmalig gedownload.

## Welke gegevens de app gebruikt

De app vraagt een toestemming in de regel pas als je een tool gebruikt die hem nodig heeft. Je kunt elke toestemming
weigeren of later intrekken in de Android-instellingen; de tool die hem nodig heeft, werkt dan niet of maar gedeeltelijk.
Alles in deze tabel blijft op de telefoon, behalve wat onder "Wanneer er iets de telefoon verlaat" staat.

| Gegevens (toestemming) | Waarvoor |
|---|---|
| Contacten | Contactenbackup en -beheer met wijzigingsgeschiedenis, namen bij gesprekken en berichten |
| Locatie, ook op de achtergrond | Mijn routes, Mijn auto (parkeerplek en ritten), het weer op het startscherm, automatiseringen op een plek, Apparaten, Locatie kiezen. Op de achtergrond alleen als je een automatisering op een plek of Apparaten aanzet |
| Microfoon | Muziekherkenning, spraaknotities, de assistent, Slaappraat |
| Camera | QR-codes scannen (bijvoorbeeld voor de VPN) |
| Agenda | Check-in en de agenda-widget |
| Telefoon (status, bellen) | Bellen vanuit de app en herkennen of er een gesprek is |
| Apparaten in de buurt (Bluetooth, wifi) | Het overzicht van apparaten in de buurt |
| Meldingen lezen (meldingstoegang) | Meldingsgeschiedenis: een overzicht van meldingen op je telefoon, dus ook berichten van anderen. Alleen voor jezelf, op de telefoon |
| Alle bestanden (bestandstoegang) | WhatsApp-backup (met de inhoud van je chats, ook berichten van anderen, die je met je eigen sleutel leesbaar kunt maken) en gesprekken uitschrijven |
| Gebruikstoegang | Schermtijd en gebruik bij de tool Gebruik |
| Boven andere apps | De zwevende speler |
| Toegankelijkheid | Automatiseringen die in andere apps tikken, en herkennen hoe je ontgrendelt (Gebruik) |
| VPN | Je eigen VPN-verbindingen |
| Meldingen tonen | Voortgang, herinneringen en de bediening van de speler |

### Alleen in de GitHub-versie

| Gegevens (toestemming) | Waarvoor |
|---|---|
| Sms (lezen) | Sms-backup, zoeken in je sms'jes, een inlogcode van een leverancier uit een sms halen |
| Belgeschiedenis | Oproepen, backup van de belgeschiedenis, Auto redial |
| Gesprekken opnemen (via toegankelijkheid) | Je eigen telefoongesprekken opnemen en op de telefoon uitschrijven |
| Apparaatbeheer en camera | Gebruik: mislukte ontgrendelpogingen bijhouden en, als je dat aanzet, een stille foto met de voorcamera bij (mislukt) ontgrendelen. Die foto kan iemand anders laten zien die je telefoon probeert te ontgrendelen; hij staat versleuteld in de app, nooit in je galerij |
| Apps installeren | De app werkt zichzelf bij vanaf GitHub |

## Wanneer er iets de telefoon verlaat

Elke dienst die de app benadert, ziet daarbij het IP-adres van je telefoon en een technische naam van de app; dat is
bij elke internetverbinding zo. Daarnaast:

**Vanzelf, zonder dat je een tool opent:**

| Dienst | Wanneer | Wat er naartoe gaat |
|---|---|---|
| GitHub (alleen de GitHub-versie) | Bij het openen van de app, hooguit eens per half uur | Een vraag om de nieuwste versie; niets persoonlijks |
| Open-Meteo | Weer op het startscherm en de weerwidget, als je die gebruikt | De laatst bekende locatie van de telefoon (op ongeveer 10 meter) of een plaatsnaam |
| Hugging Face | Een onderbroken download van een AI-model gaat vanzelf verder | Niets persoonlijks |

**Taken die je zelf hebt aangezet, op de achtergrond:** podcasts ophalen, de Spotify-backup, facturen ophalen en
aanleveren, en de wekelijkse statistieken (zie hieronder). Ze gebruiken de diensten uit de volgende tabel.

**Als je een tool gebruikt of iets instelt:**

| Dienst | Wanneer | Wat er naartoe gaat |
|---|---|---|
| AudD (muziekherkenning) | Als je een nummer laat herkennen, met je eigen AudD-sleutel | Een kort geluidsfragment |
| Spotify | Als je Spotify koppelt voor een backup of afspeellijsten | Inloggen via Spotify; je gegevens komen naar de telefoon, afspeellijsten kunnen op jouw verzoek worden aangepast |
| Google (Gmail, Google Drive) | Als je Google koppelt voor facturen | Inloggen via Google. De app leest in Gmail alleen mails die op facturen lijken (alleen-lezen), slaat facturen op in je eigen Drive (alleen bestanden die de app zelf maakt) en mailt ze, als je dat aanzet, naar ontvangers die je zelf instelt |
| Je eigen mailserver of eigen cloud | Als je die instelt | Wat je daarheen laat sturen |
| Websites van je leveranciers of bank (bijv. KPN, Ziggo, Rabobank) | Als je facturen of transacties laat ophalen | Je logt in op je eigen account (de app bewaart je inlognaam en, versleuteld, je wachtwoord voor leveranciers); wat wordt opgehaald blijft op de telefoon |
| OpenStreetMap | Bij het tonen van een kaart | Welke kaarttegels nodig zijn (het getoonde gebied) |
| Apple (podcasts zoeken en de top 40) | Als je podcasts zoekt of bekijkt | Je zoekterm |
| radio-browser.info | Als je radiozenders zoekt of afspeelt | Je zoekterm en een telling bij afspelen |
| Radiostreams, podcastfeeds, hoesjes en zenderlogo's | Als je luistert of afleveringen ophaalt | Alleen wat nodig is om de stream of het bestand op te halen, bij de server van de zender of podcast |
| Chromecast of ander afspeelapparaat in je eigen netwerk | Als je naar zo'n apparaat cast | Het adres van de stream, de titel en de hoes |
| Hugging Face | Als je een AI- of spraakmodel downloadt | Niets persoonlijks |
| Je eigen VPN-server of VPN-aanbieder | Als je een VPN-verbinding gebruikt | Je internetverkeer, zoals bij elke VPN |
| Zelftest | Als je de zelftest start | Een proefverbinding met radio-browser.info, een radiostream en AudD |
| Andere apps (kaarten, zoeken, YouTube Music) | Als je op zo'n link tikt | De app opent de andere app; daarvoor geldt die app |

Bij elke dienst geldt het privacybeleid van die dienst zelf.

### Statistieken voor verbeteringen

Staat standaard aan, maar er gaat niets weg tot je zelf een eigen (privé) GitHub-repository en sleutel instelt. Dan
stuurt de app eens per week een rapport naar **jouw eigen** repository, met alleen: per soort logregel een aantal, bij
een vastloper het soort fout en de plek in de code van de app, de versie van de app, het merk en model van de
telefoon, de Android-versie, de periode, of de app op de achtergrond beperkt wordt, en een willekeurige code van 8
tekens voor deze installatie. Nooit namen, berichten, telefoonnummers, bestandsnamen, adressen of locaties. Je zet het
uit bij Instellingen → Foutrapport en statistieken.

## Backups

- Backups die je in de app maakt (contacten, sms, notities, alle appgegevens enzovoort) gaan naar een map of plek die
  **jij** kiest. De backup van alle appgegevens is versleuteld met een wachtwoord dat alleen jij kent.
- Staat de Google-backup van Android aan, dan neemt Android ook een deel van de appgegevens mee naar **je eigen**
  Google-account: onder andere je notities, je muziekgeschiedenis, je parkeerplek en ritten, opgeslagen plekken
  (automatiseringen, Locatie kiezen, weer), inlognamen bij leveranciers en je instellingen. Wachtwoorden, sleutels,
  koppelingen, de kluis en je bankgegevens gaan daar bewust niet mee. Je zet de Google-backup uit in de instellingen
  van Android.

## Bewaren en verwijderen

Gegevens blijven op de telefoon tot je ze in de app verwijdert of de app verwijdert. Bij het verwijderen van de app
wist Android alles wat de app op de telefoon bewaarde. Backups en bestanden die je zelf ergens anders hebt neergezet
(een map, je Google Drive, je eigen cloud, je Google-backup) blijven daar staan; die beheer je zelf.

## Kinderen

De app is niet bedoeld voor kinderen jonger dan 16 jaar.

## Wijzigingen

Verandert er iets aan dit beleid, dan staat de nieuwe versie op deze plek, met de datum bovenaan. De geschiedenis van
alle wijzigingen is te zien op GitHub.

## Contact

Vragen over privacy: mail naar [contact@esape.nl](mailto:contact@esape.nl) of open een
[issue op GitHub](https://github.com/renevanes/renes-tools/issues). Een
beveiligingsprobleem meld je privé via [Report a vulnerability](https://github.com/renevanes/renes-tools/security/advisories/new).
Heb je een klacht over hoe met persoonsgegevens wordt omgegaan, dan kun je ook terecht bij de
[Autoriteit Persoonsgegevens](https://autoriteitpersoonsgegevens.nl).

---

## English summary

Rene's Tools has no server and no account. The developer does not collect, sell or share personal data, and the app
contains no ads or third-party analytics. The app processes data on the phone (contacts, location including background
location for place-based automations, microphone, calendar, notifications history, files of other apps for WhatsApp
backup and call transcription, usage stats, and, in the GitHub version only, SMS, call log, call recording and an
optional silent front-camera photo on (failed) unlock). Data leaves the phone only for services you use: AudD (short
audio clip), Spotify, Google (read-only Gmail search for invoices, Drive, sending invoices), your own mail server or
cloud, supplier and bank websites, OpenStreetMap, Apple's podcast search, radio-browser.info, radio streams and podcast
feeds, Chromecast in your network, Hugging Face (model downloads) and your VPN. Automatically: weather (approximate
location to Open-Meteo), resuming model downloads and, in the GitHub version, update checks. Optional weekly
statistics (counts, crash type and location in the code, app version, device make/model, Android version, random
install id) go only to your own private GitHub repository. The assistant and transcription run on the device. Android's
Google backup may include notes, music history, parking/trips, saved places and settings, but never passwords, keys or
bank data. Uninstalling removes the app's data from the phone. Questions: contact@esape.nl or an issue on
[GitHub](https://github.com/renevanes/renes-tools/issues).
