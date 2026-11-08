# ID-gegevens herkennen uit een foto

In Stamgegevens: bij een persoon → tabblad **ID** → **Herkennen uit foto**, of bij een document → **Foto's** → een kaartfoto kiezen.
De app leest de **strook met <<<** (machineleesbare zone, MRZ, ICAO 9303): de achterkant van de ID-kaart (3 regels van 30
tekens), de fotopagina van het paspoort (2 × 44) of een ander reisdocument (2 × 36).

## Wat er gebeurt
- Alles op de telefoon (`MrzOcr`, `Mrz`, zuiver Java, geen bibliotheek, geen netwerk). Bij **Herkennen uit foto** wordt de
  foto niet bewaard; bij een kaartfoto wordt hij bewaard zoals altijd (verkleind en versleuteld).
- Foto kiezen verlaat de app, dus Stamgegevens vergrendelt; na ontgrendelen wordt herkend (`MasterPhotos.resume`).
- Uitkomst = voorstel: Document (soort, nummer, geldig tot, land van afgifte) en Persoon (naam, voornamen, geboortedatum,
  geslacht, nationaliteit; BSN alleen als het in de strook staat, de 11-proef klopt en het controlecijfer van de hele strook klopt).
  Standaard aangevinkt: alleen wat nog leeg is. Persoonsgegevens worden ingevuld nadat het document is opgeslagen, in de
  persoon, die je dan zelf controleert en opslaat. In het logboek: "Foto herkennen gestart" en de gewone wijzigingen.
- De strook heeft geen accenten (Zoë staat er als ZOE) en kort lange namen soms af; daarom staan bestaande namen standaard uit.

## Hoe het lezen werkt
1. Verkleinen tot hooguit 1800 px, zwart-wit met een aanpassende drempel (Bradley), losse tekens als verbonden vlakken.
2. Tekens rijgen tot regels; begin en tekenafstand met kleinste kwadraten; middellijn langs de regel (ook scheef), met de
   vaste hoogteverschuiving van `<` (`MrzOcr.LT_OFFSET`).
3. Per teken 12 × 18 vakjes langs de regel bemonsteren en vergelijken met de sjablonen in `MrzGlyphs` (per teken drie
   varianten: gewoon, dikker, dunner).
4. `Mrz.decode`: per positie alleen toegestane tekens (cijfer, letter, controlecijfer); klopt een controlecijfer niet, dan
   tot twee tekens vervangen door hun tweede keus. Bruikbaar = gemiddelde afwijking onder `Mrz.Result.LIMIT` én documentnummer,
   geboortedatum en vervaldatum kloppen. Vier draairichtingen; de beste lezing wint.

## Sjablonen en testbeelden opnieuw maken
Nodig na een wijziging in hoe `MrzOcr` tekens bemonstert. Het OCR-B-lettertype zit niet in de repository; gebruik
bijvoorbeeld `OCRB.TTF` uit github.com/bcssupp0rt/ocrbfont (GPL-3.0).

```bash
./build.sh   # of alleen de Java-classes
javac -cp build/classes:$TOOLS/ap/android-36/android.jar -d build/mrz app/src/nl/rene/tools/Mrz*.java tools/mrz/MrzSjablonen.java
java -Djava.awt.headless=true -cp build/mrz:build/classes:$TOOLS/ap/android-36/android.jar nl.rene.tools.MrzSjablonen OCRB.TTF .
```

Dit schrijft `app/src/nl/rene/tools/MrzGlyphs.java` en de testbeelden in `tests/jvm/mrz/` (verzonnen gegevens). `MrzTest`
leest ze en controleert ook de voorbeelden uit ICAO 9303.
