# ID-kopie anonimiseren

Open **PDF → ID-kopie afschermen**. Kies een foto of PDF via Android, eventueel meerdere foto's van voor- en achterkant (maximaal vier pagina's). Er is geen camera-, microfoon- of brede bestandsmachtiging nodig.

1. Sleep rechthoeken over gegevens die de ontvanger niet nodig heeft. Met Vergroten kun je kleine tekst bekijken; zet Voorbeeld verschuiven aan om een vergrote pagina te verplaatsen. Een vlak kan ook met percentages worden ingevoerd.
2. Controleer alle pagina's, waaronder achterkant en machineleesbare strook. Denk aan BSN, documentnummer, handtekening of pasfoto afhankelijk van het doel. De app herkent velden niet automatisch en bepaalt niet welke gegevens een ontvanger mag vragen.
3. Vul ontvanger, doel en datum voor het watermerk in. Bevestig iedere pagina. Een wijziging in afscherming, draaiing of watermerk trekt de betreffende bevestigingen in.
4. Maak de afgeschermde kopie. Bekijk haar vóór Opslaan als of Delen. De kopie staat ook bij PDF → Gemaakt. Zichtbare gegevens blijven zichtbaar: een watermerk op zichzelf anonimiseert niets.

Het originele bestand op de gekozen locatie blijft behouden. Het tijdelijke bronbestand wordt gewist bij export, verlaten of vernietigen van het scherm. Bij een mislukte verwijdering wordt geen geslaagde export gemeld; de app meldt de fout en probeert de verwijdering opnieuw. Na een onderbroken proces worden achtergebleven ID-werkbestanden bij de volgende start opgeruimd. Zelf opgeslagen of gedeelde kopieën verwijder je afzonderlijk.

De verwerking gebeurt op de telefoon. Bronnen staan uitsluitend in de privé-cache, buiten Android-back-ups; afschermvlakken en watermerkinvoer gaan niet in instellingen of statistieken. Het ID-scherm blokkeert schermafdrukken en gebruikt de privé-invoerstand (geen Autofill of gepersonaliseerd toetsenbordleren waar Android dit ondersteunt).

De export tekent iedere pagina opnieuw naar een bitmap, vervangt afgeschermde pixels door ondoorzichtig zwart en tekent het watermerk in die bitmap. Een nieuwe PDF bevat alleen opnieuw gecodeerde JPEG-afbeeldingen. Oorspronkelijke PDF-objecten, tekstlagen, annotaties, formulieren, ingebedde bestanden en EXIF worden niet gekopieerd. Dit is een afgeschermde kopie, geen wijziging van het officiële identiteitsdocument.

Controles: JVM-tests voor pixelranden en een late import na sluiten; interfacetests voor voor-/achterkant, draaien, opnieuw bevestigen en verouderde callbacks; afzonderlijke Android-instrumentatie op API24 en36 voor echte rasterexport, tekst-/EXIF-uitsluiting, opaque pixels en bronwissen. De instrumentatie-APK maakt uitsluitend eigen testdocumenten en is geen onderdeel van de app.
