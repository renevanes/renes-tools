# Lokaal voorlezen

**Instellingen → Lokaal voorlezen** beheert stemmen, snelheid en achtergrondweergave voor alle onderdelen binnen Tools. Download bewust één stem (ongeveer 21 MB): Ronnie voor Nederlands of LJ Speech voor Engels. Er wordt nooit vanzelf een stem gedownload. Na de download werkt voorlezen zonder internet. De eerste keer moet de bijgewerkte APK geïnstalleerd zijn. De APK bevat de spraakmotor alleen voor arm64-v8a (telefoons); de Play-bundel ook voor x86_64. Ontbreekt de motor in de installatie, dan meldt Lokaal voorlezen dat de stemmen hier niet werken.

- **E-reader**: luidsprekerknop vanaf de huidige tekstplek, door de leeshoofdstukken heen. Geselecteerde tekst kan apart worden voorgelezen. De leesplek wordt na elk uitgesproken stuk bewaard. Handmatig bladeren stopt het voorlezen.
- **Notities**: luidsprekerknop leest titel, tekst en lijstitems.
- **Assistent**: Voorlezen onder een antwoord, automatisch antwoord na een ingesproken vraag en gespreksmodus gebruiken dezelfde stem.

Er is één actieve voorleessessie. Een nieuwe aanvraag stopt de vorige. Pauze, hervatten en stoppen staan in de gedeelde balk; de assistent behoudt zijn eigen balk. Een nieuwe snelheid geldt vanaf de volgende of de zin daarna (de volgende zin wordt al gemaakt terwijl de huidige klinkt, zodat er geen stilte tussen zinnen valt). Boeken stoppen standaard bij het verlaten van Tools of het uitzetten van het scherm. Doorgaan op de achtergrond is uitsluitend beschikbaar na bewust inschakelen. De mediamelding en headsetbediening kunnen pauzeren, hervatten en stoppen; de voorgronddienst blijft tijdens een pauze staan (verder spelen vanaf de headset vraagt audiofocus, en dat mag vanaf Android 15 alleen met een voorgronddienst). Een pauze van meer dan 15 minuten stopt de sessie. Tijdens pauze is er geen CPU-slot; tijdens laden, zinnen maken en afspelen houdt een boek op de achtergrond een tijdelijk CPU-slot (10 minuten, per minuut verlengd). Andere bronnen stoppen zodra de app naar de achtergrond gaat.

Audiofocus: een tijdelijk verlies (gesprek, navigatie, een melding die het geluid vraagt) pauzeert en gaat daarna vanzelf verder; neemt een andere app het geluid over, dan pauzeert de sessie tot Rene zelf verder kiest (dan wordt de focus opnieuw gevraagd; lukt dat niet, dan blijft het gepauzeerd met een melding). Een watchdog stopt met een fout als de afspeelpositie 10 seconden stilstaat zonder pauze of als één zin langer dan 2 minuten duurt; een volgende aanvraag start dan schoon.

## Gedeelde interface

`ReadAloudBridge` is onderdeel van de gewone WebView-brug, ook in losse Tools-onderdelen. Er is geen Android TTS-service voor apps buiten Tools.

Gebruik `spText(text, source, markdown)` voor een nieuwe tekstbron. `source` is een vaste identifier zoals `note`, `selection` of `preview`. `spBook()` start de native hoofdstuklus. `spSettings()` opent de gedeelde stemkiezer zonder naar een andere tool te gaan. De AI gebruikt zijn bestaande `aiSpeak`-brug met een adapter naar dezelfde motor.

Native: `speechModels`, `speechConfigure`, `speechDownload`, `speechDownloadStop`, `speechDelete`, `speechStart`, `speechBook`, `speechPause`, `speechStop` en `speechState`. `onSpeech` ontvangt request, source, state, book, chapter, offset en end:

- `playing`: het stuk `[offset, end)` van de tekst (bij een boek: van het hoofdstuk) is nu hoorbaar. Dat is één zin (hooguit 220 tekens), dus geschikt om de zin te markeren en de pagina te laten volgen.
- `progress`: tot `offset` is uitgesproken; bij een boek is de leesplek dan bewaard.
- `chapter`: een volgend hoofdstuk begint (offset 0). Verder `loading`, `paused` (met `error` als verder spelen niet lukte), `done`, `stopped` en `error` (met `error` en `code`: `voice_missing` of `speech_error`).

Een slaaptimer is gewoon `speechStop` op het gekozen moment; snelheid via `speechConfigure`. Requests zijn uniek; de client negeert oude events. Alleen de activity die de sessie bezit kan deze bedienen. Een achtergrondboek kan bij heropenen aan een nieuwe activity worden overgedragen. Meldingsacties zijn aan de specifieke request gekoppeld. Alleen een bewust geopende activity mag starten.

Tekst maximaal 120.000 tekens per directe aanvraag (AI 30.000), boekhoofdstuk maximaal 2 miljoen; synthese per zin (`ReadAloud.segments`: een zin eindigt bij een regeleinde of bij .!?… gevolgd door witruimte, dus "3.5" blijft heel; langer dan 220 tekens wordt bij een spatie geknipt). Stukken zonder Latijnse letter of cijfer ("* * *") worden overgeslagen; een zin zonder geluid ook. Opmaak en verwijzingen verdwijnen bij markdown. Stemmen zijn bedoeld voor Nederlands en Engels. Emoji en aanvullende Unicode-tekens worden vóór JNI verwijderd; leesposities blijven op de oorspronkelijke tekst gebaseerd. Alleen CPU, twee inferentiedraden. Geen automatische taaldetectie: kies de stem die bij de tekst past.

## Opslag en downloads

Instellingen staan in `voorlezen.xml` en gaan mee in Alle appgegevens; vluchtige downloadstatus en fouten niet. Modelbestanden staan in `getNoBackupFilesDir()/voorleesmodellen` en moeten na herinstallatie opnieuw gedownload worden. Geen tekst of audio op schijf, in meldingen, logs of netwerkverkeer. Er wordt geen synthese op een externe server gebruikt.

`ModelDownloadService` en `ModelFetch` regelen HTTPS-downloads, hervatten en de bestaande wifi-instelling. Grootte en SHA-256 staan vast in `VoiceModels`. Uitpakken weigert paden buiten de stagingmap, links, bijzondere bestandstypen en dubbele namen, met grenzen voor aantallen en uitgepakte bytes. Publicatie gebeurt na controle onder een bestandsslot. Verwijderen stopt eerst de sessie en wacht tot JNI de bestanden heeft vrijgegeven.

## Bouw en verificatie

`tools/voorlezen/deps.py` haalt uitsluitend de met SHA-256 vastgezette archieven uit `deps.json`. De Java-brug en alleen Ants TAR/BZip2-codecs (zonder java.nio.file) en twee native bibliotheken per ABI gaan in APK en Play-variant. De APK heeft standaard alleen arm64-v8a (±10 MB gecomprimeerd); `VOORLEZEN_ABIS="arm64-v8a x86_64"` bouwt ook x86_64 mee (±11 MB), zoals Play-controle doet voor de x86_64-emulators. De Play-bundel heeft altijd beide; Play levert per toestel één ABI. De ONNX Runtime-versie staat in `deps.json` en `deps.py` controleert die in de bibliotheek (voor de kwetsbaarhedencontrole in Onderhoud). sherpa-onnx 1.13.8 bevat de eSpeak-fonemizer die Piper nodig heeft; versie 2 heeft deze niet meer ingebouwd. Licenties, herkomst en modelkaarten staan in `app/voorlezen/` en worden als assets meegeleverd.

JVM: `VoiceArchiveTest` (pad-/type-/hashcontrole en annuleren), `ReadAloudTextTest` (zinnen, posities, uitspreekbare tekst; zonder `\p{IsLatin}`, dat de reguliere expressies van Android 7 niet kennen). Interface: `voorlezen.test.js`, bestaande e-reader-, modellen- en assistenttests. Android: `tests/telefoon/voorlezen-test.sh` controleert installatie, werkelijke niet-stille JNI-synthese, AudioTrack, oude bediening, MediaSession, pauze zonder CPU-slot (dienst blijft op de voorgrond), audiofocus (tijdelijk verlies pauzeert en hervat), de zinposities in `playing` en achtergrondweergave op API 24/36, ook voor de Play-variant. Bij een fout zet het script de soort fout en plek (`ReadAloud.where`, logcat-tag `Voorlezen`) in de uitvoer. Het testmodel is alleen onderdeel van de instrumentatietest, nooit van de gewone APK.
