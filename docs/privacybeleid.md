# Privacybeleid van Rene's Tools

*Laatst bijgewerkt: 4 november 2026*

Rene's Tools is een Android-app van Rene van Es (Rotterdam) met hulpmiddelen zoals notities, PDF's, radio, podcasts,
routes, backups, VPN en een lokale assistent. De app is vrije software: de volledige broncode staat openbaar op
[GitHub](https://github.com/renevanes/renes-tools), zodat iedereen kan nagaan wat hieronder staat.

Dit privacybeleid geldt voor de app zoals die via GitHub en (eventueel) via Google Play wordt verspreid.

## Het uitgangspunt: je gegevens blijven op je telefoon

- De app heeft **geen eigen server** en geen account. Er is geen database bij de maker waarin jouw gegevens terechtkomen.
- De maker **verzamelt, verkoopt en deelt geen persoonsgegevens**. Er zitten geen advertenties in de app en geen
  advertentie- of analysediensten van derden.
- Wat je in de app maakt of bewaart (notities, routes, backups, transcripties, facturen, bankoverzichten, instellingen)
  staat **alleen op je telefoon**, tenzij je zelf een plek kiest waar het naartoe moet (zie hieronder).
- Gevoelige onderdelen, zoals wachtwoorden, VPN-sleutels en je bankgegevens, worden op de telefoon **versleuteld**
  bewaard. Bankzaken verlaten de telefoon nooit.
- De lokale assistent en het uitschrijven van gesprekken werken **op de telefoon zelf**. Er gaat niets naar een AI-dienst
  op internet; alleen het model wordt eenmalig gedownload (zie hieronder).

## Toestemmingen

De app vraagt een toestemming in de regel pas als je een tool gebruikt die hem nodig heeft. Je kunt elke toestemming
weigeren of later intrekken in de Android-instellingen; alleen de tool die hem nodig heeft, werkt dan niet (volledig). Voorbeelden:

| Toestemming | Waarvoor |
|---|---|
| Contacten | Contactenbackup en -beheer, namen bij gesprekken |
| Locatie | Mijn routes, het weer op het startscherm, automatiseringen op een plek, Locatie kiezen |
| Microfoon | Muziekherkenning, spraaknotities, de assistent, Slaappraat |
| Camera | QR-codes scannen (bijvoorbeeld voor de VPN) |
| Agenda | Check-in en de agenda-widget |
| Apparaten in de buurt | Het overzicht van Bluetooth- en wifi-apparaten |
| Meldingen | Voortgang, herinneringen en de bediening van de speler |
| Gebruikstoegang | Schermtijd en gebruik bij de tool Gebruik |
| VPN | Je eigen VPN-verbindingen |

Wat de app met deze toestemmingen leest, blijft op de telefoon, behalve als je zelf een van de diensten hieronder
gebruikt.

## Wanneer er wel iets de telefoon verlaat

Alleen als je een tool gebruikt die daarvoor bedoeld is, of iets zelf instelt:

| Dienst | Wanneer | Wat er naartoe gaat |
|---|---|---|
| AudD (muziekherkenning) | Als je een nummer laat herkennen, met je eigen AudD-sleutel | Een kort geluidsfragment |
| Spotify | Als je Spotify koppelt voor een backup of afspeellijsten | Inloggen via Spotify; je gegevens komen naar de telefoon, afspeellijsten kunnen op jouw verzoek worden aangepast |
| Google (Gmail, Google Drive) | Als je Google koppelt voor facturen | Inloggen via Google; facturen naar je eigen Drive of per mail naar ontvangers die je zelf instelt |
| Je eigen mailserver of eigen cloud | Als je die instelt | Wat je daarheen laat sturen |
| Websites van je leveranciers of bank (bijv. KPN, Ziggo, Rabobank) | Als je facturen of transacties laat ophalen | Je logt in op je eigen account; wat wordt opgehaald blijft op de telefoon |
| OpenStreetMap | Bij het tonen van een kaart | Welke kaarttegels nodig zijn (het getoonde gebied) |
| Open-Meteo | Voor het weer op het startscherm | De coördinaten of plaatsnaam |
| Apple Podcasts-zoekdienst | Als je podcasts zoekt | Je zoekterm |
| radio-browser.info | Als je radiozenders zoekt of afspeelt | Je zoekterm en een telling bij afspelen |
| Radiostreams, podcastfeeds en hun servers | Als je luistert of afleveringen ophaalt | Alleen wat nodig is om de stream of het bestand op te halen |
| Hugging Face | Als je een AI- of spraakmodel downloadt | Niets persoonlijks |
| GitHub | Alleen in de GitHub-versie: controleren op updates. In alle versies: "Statistieken voor verbeteringen", als je zelf een eigen privé-repository en sleutel instelt | Updates: niets persoonlijks. Statistieken: alleen tellingen, het soort fout, het telefoonmodel en de Android-versie, naar je eigen repository |
| Je eigen VPN-server of VPN-aanbieder | Als je een VPN-verbinding gebruikt | Je internetverkeer, zoals bij elke VPN |

Bij elke dienst geldt het privacybeleid van die dienst zelf. De app stuurt nooit namen, berichten, telefoonnummers,
bestandsnamen of locaties mee in de statistieken.

## Backups

Backups (contacten, notities, alle appgegevens enzovoort) gaan naar een map of plek die **jij** kiest. Een backup van
alle appgegevens is versleuteld met een wachtwoord dat alleen jij kent. Android kan daarnaast gewone instellingen
meenemen in je eigen Google-backup; wachtwoorden, sleutels en bankgegevens gaan daar bewust niet mee.

## Bewaren en verwijderen

Gegevens blijven op de telefoon tot je ze in de app verwijdert of de app verwijdert. Bij het verwijderen van de app
wist Android alles wat de app op de telefoon bewaarde. Backups en bestanden die je zelf ergens anders hebt neergezet
(een map, je Google Drive, je eigen cloud) blijven daar staan; die beheer je zelf.

## Kinderen

De app is niet bedoeld voor kinderen jonger dan 13 jaar en verzamelt bewust geen gegevens van kinderen.

## Wijzigingen

Verandert er iets aan dit beleid, dan staat de nieuwe versie op deze plek, met de datum bovenaan. De geschiedenis van
alle wijzigingen is te zien op GitHub.

## Contact

Vragen over privacy: open een [issue op GitHub](https://github.com/renevanes/renes-tools/issues). Een
beveiligingsprobleem meld je privé via [Report a vulnerability](https://github.com/renevanes/renes-tools/security/advisories/new).

---

## English summary

Rene's Tools has no server and no account. The developer does not collect, sell or share personal data, and the app
contains no ads or third-party analytics. Everything you create stays on your phone unless you choose a destination
yourself (a folder, your own Google Drive, your own cloud or mail server). Some tools contact outside services only
when you use them: AudD (song recognition, a short audio clip), Spotify, Google (Gmail/Drive for invoices),
OpenStreetMap (map tiles), Open-Meteo (weather), Apple's podcast search, radio-browser.info, radio streams and podcast
feeds, Hugging Face (model downloads) and GitHub (updates in the GitHub version; optional statistics to your own
private repository, counts only). The assistant and transcription run on the device. Uninstalling the app removes its
data from the phone. Questions: open an issue on [GitHub](https://github.com/renevanes/renes-tools/issues).
