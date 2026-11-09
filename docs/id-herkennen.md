# Identiteitsgegevens uit een foto

Stamgegevens → persoon → ID → **Foto herkennen**. Kies de voorkant van een rijbewijs, de achterkant van een ID-kaart
(met de strook `<<<`) of de fotopagina van een paspoort. Na het kiezen bewaart Tools de foto
als versleutelde documentbijlage, zonder fotometadata. Verwijderen kan bij Foto’s. Handmatig toevoegen: **Zelf invullen**.

Kom je binnen twee minuten terug uit de Android-fotokiezer en is de kluis nog open, dan gaat de verwerking direct verder.
Anders ontgrendel je eerst opnieuw. Tijdens het kiezen weigert de brug andere acties; bij verlaten of vergrendelen
vervallen lopende resultaten. Herkenning gebruikt de oorspronkelijke foto vóór verkleinen voor de bijlage.

## Controleren en bewaren

Het document heeft twee tabbladen: Gegevens en Foto’s. Afgifte en extra gegevens staan ingeklapt.
Herkenning toont eerst een voorstel. Vergelijk elk veld met het document; afwijkende bestaande waarden en onzeker
gelezen velden staan uit. Gelijke waarden worden als bestaand getoond. BSN staat altijd uit.
**Bewaar gekozen gegevens** bewaart de gekozen document- en persoonsgegevens in één transactie. Een gewijzigde revisie,
vergrendeling of ongeldig veld breekt de hele bewerking af. Niet gekozen waarden en bestaande foto’s blijven behouden.
**Zelf invullen** sluit het voorstel en toont de gewone velden. Een bewaarde foto kan opnieuw herkend worden bij Foto’s.

## Herkenning op de telefoon

- ID-kaart/paspoort: bestaande MRZ-herkenning met MrzOcr en de TD1/TD2/TD3-controlecijfers. Namen in de strook kunnen
  zonder accenten staan. Een onzekere MRZ wordt als zodanig gemeld.
- Rijbewijs: Tesseract4Android met het gebundelde tessdata_fast-model leest de genummerde EU-voorkant: achternaam (1),
  voornamen (2), geboortedatum (3), afgifte (4a), geldig tot (4b), instantie (4c), documentnummer (5), categorieën (9).
  Het model wordt niet gedownload. Vier oriëntaties en EXIF-rotatie worden ondersteund. Dit is tekstherkenning zonder
  controlecijfers; Tools vermeldt dat expliciet. Geen echtheidscontrole of biometrische persoonsherkenning.
- Geen afleiding van BSN, nationaliteit, woonadres of bevoegdheid uit andere nummers/velden. Onduidelijke tekens
  worden niet stilzwijgend in cijfers veranderd. Onmogelijke datums en tegenstrijdige datumvolgordes worden weggelaten.
- Scherpe, recht gefotografeerde documenten werken het best. Bij geen resultaat blijft handmatig invullen beschikbaar.

Foto’s en tekst gaan niet naar een dienst. Decoderen, herkenning en voorstellen blijven in geheugen; alleen het openbare
model staat in `getNoBackupFilesDir()/document-ocr`. Ruwe tekst en foto’s komen niet in logs, statistieken of tijdelijke
bestanden. De versleutelde bijlage volgt de bestaande opslag- en exportregels van Stamgegevens. Bij verlaten van de app,
vergrendelen of wisselen van sessie vervallen resultaten; de herkenning wordt afgebroken. Een tijdslimiet begrenst OCR.

Versies, hashes, broncode en licenties: `app/ocr/README.md` en `metadata.json`. De build controleert alle bestanden en
16KB-uitlijning met `tools/ocr-verify.py`. Android-instrumentatie test echte herkenning met een synthetische kaart,
ook gedraaid, en atomair bewaren/terugdraaien. JVM-tests bewaken de veldparser; interfacetests bewaken de keuzes en privacy.

## Hoe het lezen werkt
1. Verkleinen tot hooguit 1800 px, zwart-wit met een aanpassende drempel (Bradley), losse tekens als verbonden vlakken.
2. Tekens rijgen tot regels; begin en tekenafstand met kleinste kwadraten; middellijn langs de regel (ook scheef), met de
   vaste hoogteverschuiving van `<` (`MrzOcr.LT_OFFSET`).
3. Per teken 12 × 18 vakjes langs de regel bemonsteren en vergelijken met de sjablonen in `MrzGlyphs` (per teken drie
   varianten: gewoon, dikker, dunner).
4. `Mrz.decode`: per positie alleen toegestane tekens (cijfer, letter, controlecijfer, geslacht M/F/X/<). Klopt een
   controlecijfer niet, dan hooguit twee tekens met echte twijfel (tweede keus scheelt minder dan `MAX_MARGIN`) vervangen,
   zonder andere controles te breken. Een ontbrekend teken wordt nooit ingevuld: een controlecijfer is mod 10 en klopt anders
   bij één op de tien gokken. Bruikbaar = gemiddelde afwijking onder `Mrz.Result.LIMIT` én documentnummer, geboortedatum en
   vervaldatum kloppen; zeker = bovendien alles klopt zonder verbetering. Verbeterde velden staan in het voorstel niet
   aangevinkt; het BSN alleen bij een zekere lezing. Vier draairichtingen (minder als de eerste overtuigend is).
5. Groot beeld: inlezen met `inSampleSize` net boven de werkmaat, daarna precies naar 1800 px; meer dan 20.000 vlakken = stoppen.
   `LIMIT` is afgesteld op de testbeelden; met echte foto's zo nodig bijstellen (foto's niet in de repository zetten).

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

## Rijbewijs: lezen en als het niet lukt
- **Leesregel D1NLD** (`Rijbewijs.bottomLine`): een Nederlands rijbewijs heeft een leesregel van 30 tekens (volgens
  datachecker.nl onderaan de voorkant; niet officieel bevestigd, de app zoekt hem op beide zijden): `D1NLD` + een cijfer + het
  rijbewijsnummer (10 cijfers) + 13 tekens + een controlecijfer (7-3-1 over de eerste 29 tekens, zoals bij de MRZ; klopt
  op de twee openbare voorbeeldregels). Het controlecijfer vangt niet alles (twee verwisselde cijfers die 5 verschillen), dus
  het nummer is alleen zeker als veld 5 hetzelfde zegt of de regel zelf goed gelezen is (zekerheid ≥ 85); anders twijfel.
  Klopt het controlecijfer, dan gaat dit nummer voor op een los gelezen veld 5. Letters die op cijfers lijken (O, I, S, B …)
  worden op cijferplekken als cijfer gelezen; het controlecijfer beslist.
- **Zonder veldnummers** (de kleine "4a.", "4b.", "5." worden op foto's vaak niet gelezen): de afgifte- en einddatum liggen 5,
  10 of 15 jaar uit elkaar (dezelfde dag of een dag eerder), de geboortedatum ligt minstens 16 jaar daarvoor; een los nummer
  van 10 cijfers is het rijbewijsnummer. Alleen als het een rijbewijs is (titel of leesregel; datums alleen met de titel, want
  op de achterkant staan datums per categorie), en altijd als twijfel (uitgevinkt).
- **Lezen** (`RijbewijsOcr`): gewoon in vier draaiingen; ontbreekt nummer, einddatum of geboortedatum, dan nog eens op een
  zwart-wit-versie (drempel per buurt, tegen het achtergrondpatroon). *Grondiger proberen* (`masterPhotoRescan`, tot ±70 s):
  ook vergroot met opgerekt contrast en als één tekstblok. Lezingen worden samengevoegd (`Rijbewijs.merge`).
- **Hulp bij mislukken** (tabblad Foto's, `#cmh-hulp`): wat er gebeurde (geen tekst, tekst maar geen document, rijbewijs zonder
  leesbare velden), tips die bij de foto passen (onscherp, donker, schittering, de regel D1NLD niet op de foto, te lang) en
  knoppen *Opnieuw proberen*, *Grondiger proberen*, *Andere foto kiezen* en *Zelf invullen*. Bij een gedeeltelijk resultaat
  staat onder de voorstellen wat niet gevonden is. De hint over de foto (`diag`) bevat alleen ja/nee en aantallen, nooit tekst.
  Een gewone kaartfoto (bijv. de voorkant van een ID-kaart) geeft geen foutmelding.
