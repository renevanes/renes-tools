# Lokaal voorlezen

**Instellingen → Lokaal voorlezen** beheert stemmen, snelheid en achtergrondweergave voor alle onderdelen binnen Tools. Download bewust één stem (ongeveer 21 MB): Ronnie voor Nederlands of LJ Speech voor Engels. Er wordt nooit vanzelf een stem gedownload. Na de download werkt voorlezen zonder internet. De eerste keer moet de bijgewerkte APK geïnstalleerd zijn. Alleen arm64-v8a en x86_64 worden ondersteund.

- **E-reader**: luidsprekerknop vanaf de huidige tekstplek, door de leeshoofdstukken heen. Geselecteerde tekst kan apart worden voorgelezen. De leesplek wordt na elk uitgesproken stuk bewaard. Handmatig bladeren stopt het voorlezen.
- **Notities**: luidsprekerknop leest titel, tekst en lijstitems.
- **Assistent**: Voorlezen onder een antwoord, automatisch antwoord na een ingesproken vraag en gespreksmodus gebruiken dezelfde stem.

Er is één actieve voorleessessie. Een nieuwe aanvraag stopt de vorige. Pauze, hervatten en stoppen staan in de gedeelde balk; de assistent behoudt zijn eigen balk. Een nieuwe snelheid geldt vanaf het volgende tekststuk. Boeken stoppen standaard bij het verlaten van Tools of het uitzetten van het scherm. Doorgaan op de achtergrond is uitsluitend beschikbaar na bewust inschakelen. De mediamelding en headsetbediening kunnen pauzeren, hervatten en stoppen. Tijdens pauze is er geen CPU-slot. Andere bronnen stoppen zodra de app naar de achtergrond gaat. Bij verlies van audiofocus stopt de sessie.

## Gedeelde interface

`ReadAloudBridge` is onderdeel van de gewone WebView-brug, ook in losse Tools-onderdelen. Er is geen Android TTS-service voor apps buiten Tools.

Gebruik `spText(text, source, markdown)` voor een nieuwe tekstbron. `source` is een vaste identifier zoals `note`, `selection` of `preview`. `spBook()` start de native hoofdstuklus. `spSettings()` opent de gedeelde stemkiezer zonder naar een andere tool te gaan. De AI gebruikt zijn bestaande `aiSpeak`-brug met een adapter naar dezelfde motor.

Native: `speechModels`, `speechConfigure`, `speechDownload`, `speechDownloadStop`, `speechDelete`, `speechStart`, `speechBook`, `speechPause`, `speechStop` en `speechState`. `onSpeech` ontvangt request, source, state, book, chapter en offset. Requests zijn uniek; de client negeert oude events. Alleen de activity die de sessie bezit kan deze bedienen. Een achtergrondboek kan bij heropenen aan een nieuwe activity worden overgedragen. Meldingsacties zijn aan de specifieke request gekoppeld. Alleen een bewust geopende activity mag starten.

Tekst maximaal 120.000 tekens per directe aanvraag (AI 30.000), boekhoofdstuk maximaal 2 miljoen; synthese per circa 220 tekens. Opmaak en verwijzingen verdwijnen bij markdown. Stemmen zijn bedoeld voor Nederlands en Engels. Emoji en aanvullende Unicode-tekens worden vóór JNI verwijderd; leesposities blijven op de oorspronkelijke tekst gebaseerd. Alleen CPU, twee inferentiedraden. Geen automatische taaldetectie: kies de stem die bij de tekst past.

## Opslag en downloads

Instellingen staan in `voorlezen.xml` en gaan mee in Alle appgegevens; vluchtige downloadstatus en fouten niet. Modelbestanden staan in `getNoBackupFilesDir()/voorleesmodellen` en moeten na herinstallatie opnieuw gedownload worden. Geen tekst of audio op schijf, in meldingen, logs of netwerkverkeer. Er wordt geen synthese op een externe server gebruikt.

`ModelDownloadService` en `ModelFetch` regelen HTTPS-downloads, hervatten en de bestaande wifi-instelling. Grootte en SHA-256 staan vast in `VoiceModels`. Uitpakken weigert paden buiten de stagingmap, links, bijzondere bestandstypen en dubbele namen, met grenzen voor aantallen en uitgepakte bytes. Publicatie gebeurt na controle onder een bestandsslot. Verwijderen stopt eerst de sessie en wacht tot JNI de bestanden heeft vrijgegeven.

## Bouw en verificatie

`tools/voorlezen/deps.py` haalt uitsluitend de met SHA-256 vastgezette archieven uit `deps.json`. De Java-brug en alleen Ants TAR/BZip2-codecs (zonder java.nio.file) en twee native bibliotheken per ABI gaan in APK en Play-variant. sherpa-onnx 1.13.8 bevat de eSpeak-fonemizer die Piper nodig heeft; versie 2 heeft deze niet meer ingebouwd. Licenties, herkomst en modelkaarten staan in `app/voorlezen/` en worden als assets meegeleverd.

JVM: `VoiceArchiveTest` (pad-/type-/hashcontrole en annuleren). Interface: `voorlezen.test.js`, bestaande e-reader-, modellen- en assistenttests. Android: `tests/telefoon/voorlezen-test.sh` controleert installatie, werkelijke niet-stille JNI-synthese, AudioTrack, oude bediening, MediaSession, pauze zonder CPU-slot en achtergrondweergave op API 24/36, ook voor de Play-variant. Het testmodel is alleen onderdeel van de instrumentatietest, nooit van de gewone APK.
