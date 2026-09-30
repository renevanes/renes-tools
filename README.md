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

## Versies en updates
- `VERSION` bevat het versienummer, de versiecode en `NATIVE_LEVEL`. Elke versie krijgt in git een tag `vX.Y`.
- `changelog.json` bevat het wijzigingslog. De app toont dit onder *Versie en updates*.
- De app leest `update/update.json` van GitHub (raw) wanneer je hem opent, hooguit eens per 30 minuten:
  - **Alleen de interface is veranderd** (`web/index.html`, `NATIVE_LEVEL` blijft gelijk): de nieuwe versie wordt stil gedownload, gecontroleerd met SHA-256 en direct gebruikt.
  - **`NATIVE_LEVEL` is hoger** (de Java-code of het manifest is veranderd): de app toont *Nieuwe versie beschikbaar* met een knop **Installeren**. Die downloadt de APK, controleert hem en biedt hem aan via de pakketinstaller van Android.

## Bouwen (zonder Android Studio of Gradle)
```sh
./setup-tools.sh              # eenmalig: build-tools 34, aapt2, android.jar (SDK 36)
./keys/unlock.sh              # eenmalig per sessie: ondertekeningssleutel uitpakken (vraagt de wachtwoordzin)
./bump.sh "Wat er veranderd is"          # versie +0.1, met regel voor het wijzigingslog
NATIVE=1 ./bump.sh "Nieuwe app-functie"   # als de Java-code of het manifest is veranderd
./build.sh                    # bouwt de APK en vult update/
./publish.sh                  # commit, tag vX.Y en push naar GitHub
```

De ondertekeningssleutel staat alleen versleuteld in de repository (`keys/signing-key.enc`, AES-256). Rene heeft de wachtwoordzin. Elke update moet met dezelfde sleutel ondertekend zijn, anders kan de telefoon hem niet installeren.

Let op bij Java-code: gebruik geen anonieme of niet-statische binnenklassen. javac 21 geeft hun constructor een parameter zonder naam, en d8 8.2 loopt daarop vast. Gebruik statische geneste klassen of lambda's.
