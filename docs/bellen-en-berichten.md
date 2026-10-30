# Bellen en berichten

[Terug naar het overzicht](../README.md)

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

## Gesprekken opnemen
- **Microfoonopname** met handmatige start/stop of optioneel automatisch starten bij mobiele oproepen, een blijvende opnamemelding met stopknop en Androids microfoonindicator. Voor telefoongesprekken zet je zelf de luidspreker aan en controleer je met een test of beide stemmen hoorbaar zijn. Gewone apps krijgen geen directe toegang tot de telefoon-audio; veel Android-toestellen blokkeren of dempen de microfoon tijdens bellen. Deze tool garandeert daarom geen opname van beide kanten. Gebruik de gespreksopname van je telefoon-app als de microfoonmethode niet werkt.
- **Automatisch starten** staat standaard uit en vereist microfoon-, telefoon- en meldingentoegang. Begint bij de volgende beantwoorde inkomende of uitgaande mobiele oproep; inschakelen start geen opname van een al lopend gesprek. Gemiste oproepen en VoIP-apps worden niet opgenomen. Dubbele telefoonmeldingen en wisselgesprekken starten geen extra opname; na handmatig stoppen of een geblokkeerde start volgt tijdens dezelfde oproep geen nieuwe poging. Android kan de achtergrondstart blokkeren: dan verschijnt een melding om de app te openen en handmatig te starten. Uitschakelen stopt alleen een automatisch begonnen opname.
- Vraagt microfoontoegang en (Android 13+) toestemming voor de opnamemelding. Telefoontoegang is optioneel: daarmee kan de opname stoppen zodra een mobiel gesprek eindigt. Andere gesprekken, zoals bellen in WhatsApp, stop je zelf. Laat de ander weten dat je opneemt.
- AAC-audio in M4A, maximaal 2 uur, ook met het scherm uit. Stilte geeft een waarschuwing. Onvolledige bestanden blijven buiten de lijst; afgebroken of te korte opnames worden niet als geslaagd getoond. Er begint nooit automatisch een opname na een herstart of het geven van toestemming.
- Opnames staan in de private app-map. Afspelen, expliciet delen met leesrechten voor één bestand, verwijderen na bevestiging en exporteren naar `Gespreksopnames/` in de gekozen backup-map. Deze audio-exports zijn niet versleuteld en worden niet automatisch meegenomen in **Alles back-uppen**.
- Afgeronde eigen opnames zijn beschikbaar in **Gesprekken uitschrijven** zonder toegang tot alle bestanden; voor externe telefoon-opnamemappen gelden de bestaande rechten. Uitschrijven vereist nog steeds het spraakmodel en een ondersteunde 64-bit ARM-telefoon.

## Meldingsgeschiedenis
- Nieuwe meldingen lokaal bewaren via Androids `NotificationListenerService`, ook nadat ze zijn weggeveegd. Zet **Nieuwe meldingen bewaren** aan en geef Tools **meldingentoegang** in de Android-instellingen. Beide staan los van de toestemming om zelf meldingen te tonen.
- Begint vanaf inschakeling; de eerdere Android-meldingsgeschiedenis is niet toegankelijk. Doorlopende meldingen, groepssamenvattingen en eigen app-meldingen worden overgeslagen. Android kan gevoelige tekst afschermen.
- Toont app, tijdstip, titel en tekst, met zoeken op app of inhoud. Bewaart maximaal 30 dagen en 10.000 meldingen in een private lokale SQLite-database. Identieke meldingen worden niet dubbel bewaard; gewijzigde tekst wel.
- **Filter per app** (knoppen met het aantal meldingen, bijv. WhatsApp of Drive) en *Oudere meldingen tonen* (steeds 300 meer). Bij het aanzetten (en bij elke nieuwe verbinding) worden ook de meldingen bewaard die dan nog in het meldingenpaneel staan; oudere geeft Android niet vrij.
- **Geschiedenis exporteren** schrijft alle bewaarde meldingen als JSON en leesbare HTML naar `Meldingen backup/` in de gekozen backup-map. Deze exports kunnen persoonlijke gegevens bevatten en zijn niet versleuteld. Selecteer **Meldingsgeschiedenis** bij **Alles back-uppen** voor automatische backups en de bestaande versleutelde archieven; dit nieuwe onderdeel staat standaard uit.
- **Geschiedenis wissen** verwijdert alleen de lokale geschiedenis na bevestiging; eerdere backups blijven staan. Bewaren uitzetten verwijdert niets. Exports zijn bedoeld om terug te lezen, niet om meldingen opnieuw in Android te plaatsen.

## Leesbare chats
- Ontsleutelt de WhatsApp-backup met de sleutel van 64 cijfers die WhatsApp toont bij de end-to-end versleutelde back-up (crypt15). De afgeleide AES-sleutel is HMAC-SHA256(HMAC-SHA256(0, root), "backup encryption"||0x01); de database wordt in GCM/CTR gelezen en zlib-gedecomprimeerd (`WaCrypt.java`).
- Leest `message`/`chat`/`jid`(+`jid_map` voor privacy-ID's) uit de ontsleutelde SQLite en toont de chats in de app, met zoeken (`WaChats.java`). Namen komen uit de contacten als die toestemming is gegeven.
- Exporteert per chat een HTML-bestand naar `WhatsApp backup/Leesbare chats/`, met foto's uit de Media-map. In de app worden foto's getoond via een afgeschermde `https://app.renes-tools.local/wa-media/`-route (`MediaActivity`/`MediaClient`).
- De sleutel staat alleen in de app, op de telefoon. Na een gewone of automatische backup worden de chats desgewenst vanzelf opnieuw leesbaar gemaakt.

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
- Digitale stilte (uitsluitend nul-samples) gaat niet naar het spraakmodel. Losse geluidslabels en bekende aftiteling zoals `(C) TV GELDERLAND 2021` worden overgeslagen bij gesprekken, inspreken en Slaappraat (`SpeechText`). Dit herstelt geen ontbrekend geluid: speel bij onjuiste tekst eerst de opname af; zijn stemmen hoorbaar, probeer dan het model **Nauwkeurig** met de juiste taal en **Opnieuw uitschrijven**. Bestaande transcripten worden pas bij opnieuw uitschrijven vervangen.
- Bij eigen microfoonopnames controleert Tools vanaf Android 10 of Android de opname dempt (`MediaRecorder.getActiveRecordingConfiguration().isClientSilenced()`). De waarschuwing verschijnt ook als er eerder wél geluid binnenkwam en blijft na opslaan in het resultaat staan. Deze controle maakt afgeschermd telefoongeluid niet toegankelijk; gebruik bij ontbrekende stemmen de opnamefunctie van de telefoon-app.
- Draait in een voorgrondservice (`TranscribeService`, dataSync) met wachtrij, voortgang en stoppen. Transcripten (`files/transcripts/<id>.json`) met tijdcodes; afspelen vanaf een zin, zoeken door alle transcripten, export als tekst naar `Gesprekken/` in de backup-map.
- **'s Nachts automatisch** (`TranscribeJob.java`): vanaf 01:00, alleen tijdens opladen, worden nieuwe opnames van de laatste 14 dagen uitgeschreven (max. 30 per nacht). Het werk loopt in de voorgrondservice (met melding). Android 12+ staat dat op de achtergrond alleen toe met een batterij-uitzondering; zonder komt er een melding om het met één tik te starten. Mislukte opnames worden niet elke nacht opnieuw geprobeerd (knop om ze toch opnieuw te proberen).

