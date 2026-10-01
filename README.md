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

## Versies en updates
- `VERSION` bevat het versienummer, de versiecode en `NATIVE_LEVEL`. Elke versie is in git één eigen commit met de naam "Versie X.Y".
- `changelog.json` bevat het wijzigingslog. De app toont dit onder *Versie en updates*.
- De app leest `update/update.json` van GitHub wanneer je hem opent, hooguit eens per 30 minuten. Via de GitHub-API zoekt hij eerst de nieuwste commit op, zodat een nieuwe versie direct zichtbaar is:
  - **Alleen de interface is veranderd** (`web/index.html`, `NATIVE_LEVEL` blijft gelijk): de nieuwe versie wordt stil gedownload, gecontroleerd met SHA-256 en direct gebruikt.
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

De ondertekeningssleutel staat alleen versleuteld in de repository (`keys/signing-key.enc`, AES-256). Rene heeft de wachtwoordzin. Elke update moet met dezelfde sleutel ondertekend zijn, anders kan de telefoon hem niet installeren.

Let op bij Java-code: gebruik geen anonieme of niet-statische binnenklassen. javac 21 geeft hun constructor een parameter zonder naam, en d8 8.2 loopt daarop vast. Gebruik statische geneste klassen of lambda's.
