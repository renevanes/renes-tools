# Offline tekstherkenning

Tesseract4Android 4.9.0, broncommit `15c534717b1cb58261b58d4e4c1200c7f81f668c`.
De standaardvariant heeft geen netwerkdienst of statistieken. De app geeft uitsluitend een op Android gedecodeerde
Bitmap aan de herkenner; de afbeeldingslezers van libpng/libjpeg worden niet aangeroepen.

Meegeleverde onderdelen: Tesseract 5.5.1, Leptonica 1.85.0, libjpeg 9f, libpng 1.6.48.
`metadata.json` legt de AAR-bron, broncommit en alle SHA-256-controlegetallen vast. `tools/ocr-verify.py` controleert
deze bestanden, licenties en de 16 KB-uitlijning vóór elke bouw. Alle vier Android-processorsoorten zijn aanwezig.

Het Engelse Latin/LSTM-model komt uit `tesseract-ocr/tessdata_fast`, commit
`87416418657359cb625c412a48b6e1d6d41c29bd`. Het is gebundeld: op de telefoon wordt niets gedownload.
Alleen dit openbare model wordt naar de private no-backupmap gekopieerd. Foto's en gelezen tekst krijgen geen
leesbaar werkbestand. De herkenning biedt uitsluitend te controleren velden aan; bestaande gegevens worden niet
automatisch overschreven. Licenties van alle onderdelen staan in `licenses/` en gaan mee in beide apps.

## Bijwerken

Download de standaard-AAR van de vastgezette JitPack-versie in `metadata.json` en controleer de `aarSha256`.
`classes.jar` wordt `tesseract4android.jar`; `jni/<abi>/*.so` gaan naar `app/lib/<abi>/` (uitsluitend de vier OCR-libs).
Neem licenties uit de bijbehorende broncommit over. Download `eng.traineddata` uitsluitend van de vastgezette
`tesseract-ocr/tessdata_fast`-commit. Bij een nieuwe versie: werk broncommit, componentversies, alle SHA-256-waarden,
licenties en `RijbewijsOcr.MODEL_HASH` samen bij. Bouw beide apps en draai de Android-instrumentatie op API 24 en 36.
De onderhoudsworkflow controleert SDK, componenten (inclusief OSV) en de laatste wijziging van het modelbestand.
