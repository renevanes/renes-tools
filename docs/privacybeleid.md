# Privacybeleid van Rene's Tools

*Laatst bijgewerkt: 13 november 2026*

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
| Contacten | Contactenbackup en -beheer met wijzigingsgeschiedenis, namen bij gesprekken en berichten, en in Stamgegevens alleen het contact dat je zelf kiest (telefoon en e-mail daarna actueel uit dat contact) |
| Locatie, ook op de achtergrond | Mijn routes, Mijn auto (parkeerplek en ritten), het weer op het startscherm, automatiseringen op een plek, Apparaten, Locatie kiezen. Op de achtergrond alleen als je een automatisering op een plek of Apparaten aanzet |
| Microfoon | Muziekherkenning, spraaknotities, de assistent, Slaappraat |
| Camera | QR-codes scannen (bijvoorbeeld voor de VPN) |
| Agenda (lezen en schrijven) | Check-in, de agenda-widget en de tool Agenda: afspraken uit de agenda's van de telefoon tonen en, als je dat kiest, een afspraak in zo'n agenda zetten. Zet je een afspraak in een Google-agenda, dan stuurt de agenda-app van de telefoon hem naar Google; Tools praat zelf niet met Google |
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
aanleveren, de gegevens van Toiletten en douches bijwerken, en de wekelijkse statistieken (zie hieronder). Ze gebruiken de diensten uit de volgende tabel.

**Als je een tool gebruikt of iets instelt:**

| Dienst | Wanneer | Wat er naartoe gaat |
|---|---|---|
| AudD (muziekherkenning) | Als je een nummer laat herkennen, met je eigen AudD-sleutel | Een kort geluidsfragment |
| Spotify | Als je Spotify koppelt voor een backup of afspeellijsten | Inloggen via Spotify; je gegevens komen naar de telefoon, afspeellijsten kunnen op jouw verzoek worden aangepast |
| Google (Gmail, Google Drive) | Als je Google koppelt voor facturen | Inloggen via Google. De app leest in Gmail alleen mails die op facturen lijken (alleen-lezen), slaat facturen op in je eigen Drive (alleen bestanden die de app zelf maakt) en mailt ze, als je dat aanzet, naar ontvangers die je zelf instelt |
| Je eigen mailserver of eigen cloud | Als je die instelt | Wat je daarheen laat sturen |
| Websites van je leveranciers of bank (bijv. KPN, Ziggo, Rabobank) | Als je facturen of transacties laat ophalen | Je logt in op je eigen account (de app bewaart je inlognaam en, versleuteld, je wachtwoord voor leveranciers); wat wordt opgehaald blijft op de telefoon |
| OpenStreetMap | Bij het tonen van een kaart | Welke kaarttegels nodig zijn (het getoonde gebied) |
| OpenStreetMap-notities (Toiletten en douches) | Alleen als je bij een plek zelf kiest om iets door te geven | Een anonieme, openbare notitie met precies de tekst die je op het scherm ziet, op die plek; OpenStreetMap ziet je IP-adres |
| GitHub en de bronnen die je kiest (Toiletten en douches) | Bij het ophalen van de gegevens | Niets persoonlijks: de bestanden worden in hun geheel gedownload en het zoeken gebeurt op de telefoon, dus je plek gaat niet mee |
| Nieuwssites en feeds die je kiest | Bij het ophalen van nieuws | Een vraag om de feed, de sitemap of de kop van een artikelpagina (bij artikelpagina's met de talen van de telefoon, zoals een browser dat doet); afbeeldingen bij artikelen komen rechtstreeks van de site, tenzij je ze uitzet. Je plek, je gelezen artikelen en je bronnenlijst blijven op de telefoon |
| Nieuwssites waar je inlogt | Als je bij een bron zelf inlogt of een cookiekeuze maakt | Je logt op de echte site zelf in (Tools bewaart geen wachtwoord); de cookies van die inlog gaan alleen mee naar de sites van die bron |
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
  koppelingen, de kluis, je bankgegevens, je eigen afspraken in Agenda, je nieuwsbronnen en inlogcookies gaan daar
  bewust niet mee (Agenda en Nieuws wel in de versleutelde backup van alle appgegevens). Je zet de Google-backup uit in de instellingen
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
location for place-based automations, microphone, calendar (read and write, for the Agenda tool; appointments you
put in a Google calendar are synced by the phone's own calendar app), notifications history, files of other apps for WhatsApp
backup and call transcription, usage stats, and, in the GitHub version only, SMS, call log, call recording and an
optional silent front-camera photo on (failed) unlock). Data leaves the phone only for services you use: AudD (short
audio clip), Spotify, Google (read-only Gmail search for invoices, Drive, sending invoices), your own mail server or
cloud, supplier and bank websites, news sites and feeds you choose (optionally with your own login on the real
site), OpenStreetMap (map tiles and, only when you choose to share a toilet location, a public anonymous note), Apple's podcast search, radio-browser.info, radio streams and podcast
feeds, Chromecast in your network, Hugging Face (model downloads) and your VPN. Automatically: weather (approximate
location to Open-Meteo), resuming model downloads and, in the GitHub version, update checks. Optional weekly
statistics (counts, crash type and location in the code, app version, device make/model, Android version, random
install id) go only to your own private GitHub repository. The assistant and transcription run on the device. Android's
Google backup may include notes, music history, parking/trips, saved places and settings, but never passwords, keys or
bank data, your own Agenda appointments or news sources. Uninstalling removes the app's data from the phone. Questions: contact@esape.nl or an issue on
[GitHub](https://github.com/renevanes/renes-tools/issues).

## ID-kopie afschermen / Redacting ID copies

De functie bij PDF verwerkt zelf gekozen foto's en PDF's uitsluitend op het toestel. Tijdelijke bronnen staan in privé-cache buiten back-ups en worden bij export of verlaten gewist; fouten worden gemeld en opnieuw geprobeerd. Een nieuwe kopie bevat alleen opnieuw getekende afbeeldingen met de gekozen zwarte vlakken en het watermerk, zonder oorspronkelijke PDF-tekstlaag of EXIF. Alleen zelf gekozen zichtbare gegevens blijven behouden; opslaan en delen vraagt een eigen actie. Er gaat geen ID-inhoud naar statistieken of een server. [Uitleg](id-kopie.md).

The PDF tool processes selected ID photos and PDFs on your device. Temporary sources are kept in private cache outside backups and removed after export or leaving the screen; failures are reported and retried. New copies contain only newly rendered images with your redactions and watermark, without original PDF text layers or EXIF. Information you leave visible remains readable. Saving or sharing requires your action. No ID content is sent to statistics or a server.

## Centrale stamgegevens / Central master data

De optionele lokale kluis bewaart personen, organisaties, adressen, reizen, relaties, een eigen profiel en identiteitsdocumentgegevens met voor- en achterkant versleuteld met een toestelontgrendeling vereisende sleutel. Kaartfoto’s worden lokaal verkleind en zonder fotometadata opgeslagen; de oorspronkelijke afbeelding bij de gekozen bron blijft bestaan. Het versleutelde activiteitenlog bevat tijdstip, onderdeel, weergavenaam en veldnamen, zonder kopieën van veldwaarden. Alleen expliciet geselecteerde velden gaan naar een ander onderdeel; identiteitsvelden vragen extra bevestiging. De kluis en het log staan buiten back-ups, zoekindex en statistieken en zijn niet herstelbaar bij sleutelverlies. Met **Assistent kent jou en je mensen** aan (Stamgegevens → Wat weet de assistent?) deel je bewust, voor gebruik door de hele app en de lokale assistent op de telefoon en ook terwijl de kluis vergrendeld is: namen en relaties van jou en de personen die je per persoon aanzet, een korte toelichting per persoon, en per vinkje de status van relaties, geboortedatum en leeftijd, soort en geldigheid van documenten, woonplaats en welk contact bij wie hoort. Deze beperkte momentopname is apart versleuteld en staat buiten back-ups; BSN, documentnummers, kaartfoto’s, adressen en telefoonnummers gaan nooit mee. Je ziet de gedeelde inhoud en kunt elk onderdeel of delen helemaal uitzetten. Een persoon kun je koppelen aan een contact dat je zelf kiest in de contactkiezer; Tools leest alleen dat contact, op de telefoon, en zoekt niets op over anderen. Bellen of een bericht opent de telefoon- of berichten-app; bellen en versturen doe je daar zelf. Bestaande gesprekken en zelf opgeslagen AI-herinneringen blijven bestaan; profielcontext wordt niet automatisch tot een AI-herinnering verwerkt. Open overgenomen concepten worden bij vergrendelen gewist; zelf gemaakte of gedeelde documenten beheer je apart. Zie [Stamgegevens](stamgegevens.md).

The optional local vault encrypts people, organisations, addresses and identity document details with a device-authentication-bound key. Its encrypted local activity log records time, tool, display name and field names, without copies of field values. Only explicitly selected fields are copied to other tools; identity fields require additional consent. The vault and log are excluded from backups, search and statistics. Lost keys cannot be recovered. If you turn on sharing with the assistant, a limited separately encrypted snapshot (names and relationships of the people you choose, a short note per person and, per switch, relationship status, birth date and age, document type and validity, place of residence and which contact belongs to whom) is available to the app and the on-device assistant, also while the vault is locked; national ID numbers, document numbers, card photos, addresses and phone numbers are never included. A person can be linked to a contact you pick yourself; Tools reads only that contact, on the device. Imported drafts are cleared when locked; exported or shared files remain under your control outside the vault. Temporary export files are cleaned up, including on the next unlocked app start after interruption. See the documentation for exact security boundaries.

## Lokaal voorlezen

Boeken, notities en antwoorden van de assistent kunnen op het toestel worden voorgelezen met een lokaal stemmodel. Tekst en geproduceerde spraak blijven in het geheugen en worden niet naar een server gestuurd of als audiobestand bewaard. Alleen na je keuze downloadt Tools een stem via GitHub; de download bevat geen persoonlijke gegevens. Stemmodellen gaan niet mee in backups, de gekozen stem en snelheid wel. Boeken kunnen met het scherm uit verdergaan als je die instelling aanzet, met pauze- en stopbediening in de mediamelding. De melding bevat geen boektitel of boektekst.
