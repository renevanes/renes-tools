# Toiletten en douches

In Tools: **Toiletten en douches → Instellingen → Bronnen**. De app downloadt hele bronbestanden en zoekt op de telefoon. Je zoeklocatie gaat niet naar de bron. Een bron kun je toevoegen, instellen, uitschakelen en apart laten bijwerken. Eigen aanvullingen en correcties blijven bij bronupdates bewaard.

## De 404 herstellen

De standaardbron gebruikt twee bestanden uit de GitHub-release `toiletten-gegevens`: `meta.json` en `toiletten-nl.json.gz`. Als die release nog niet is aangemaakt, geeft GitHub 404. Een werkende app-interface of een groene apppublicatie maakt die gegevensrelease niet vanzelf.

De workflow [Toiletten-gegevens](https://github.com/renevanes/renes-tools/actions/workflows/toiletten-gegevens.yml) maakt hem elke dinsdag om 02.23 UTC en voortaan ook na een bron- of workflowwijziging op main. Eenmalig starten of herstellen: **Actions → Toiletten-gegevens → Run workflow**, of:

```sh
gh workflow run toiletten-gegevens.yml --ref main
```

Wacht tot `maken` en `vrijgeven` geslaagd zijn. Controleer dat de release beide bestanden bevat en dat `python3 tools/toiletten/controleren.py <downloadmap>` slaagt. Tik daarna in de app op **Gegevens ophalen** of bij de bron op **Nu scannen**. Bij een mislukte update blijft de vorige bruikbare lijst staan.

## Gecontroleerde bronnen

De machineleesbare lijst staat in [web/toiletten-bronnen.json](../web/toiletten-bronnen.json). Bij de controle van 7 november 2026:

| Bron | Gebruik | Aandachtspunt |
|---|---|---|
| [OpenStreetMap / Geofabrik](https://download.geofabrik.de/europe/netherlands.html) | Landelijke lijst van toiletten, douches en herkenbare plekken waar meestal een toilet is. ODbL; standaardbron. | Wekelijkse verwerking. “Meestal” is geen bevestiging en heeft een eigen filter. |
| [Den Haag: openbare toiletten](https://den-haag-opendata.opendatasoft.com/explore/assets/openbaretoiletten/view/) | Gemeentelijke GeoJSON, CC0, met tijden en kenmerken. | De veldnamen worden wekelijks gecontroleerd. |
| [Den Haag: toegankelijke toiletten](https://data.overheid.nl/dataset/toegankelijkheidtoiletten) | Gemeentelijke bron met informatie over toegankelijkheid. CC0. | De eigenschappen gelden per plek; onbekend betekent niet gratis of toegankelijk. |
| [Amsterdam: openbare toiletten](https://maps.amsterdam.nl/openbare_toiletten/) | Beschikbaar als optioneel sjabloon. | Blijft uit: geen duidelijke open licentie bij de bron vastgesteld. |

HogeNood is een bruikbare externe wegwijzer, maar geen vrij te importeren databank. Websites van inloophuizen en gemeenten kunnen nieuwe plekken onderbouwen; neem doelgroep, toelating en beschikbaarheid niet zonder controle over als een algemeen openbaar toilet of badhuis.

## Vaste aanvul- en onderhoudsronde

1. **Iedere week automatisch:** de workflow verwerkt een nieuw Nederland-extract, controleert elke rij, de licentie, omvang en het controlegetal en publiceert alleen een geldige momentopname. Het deel dat externe gegevens verwerkt heeft uitsluitend leesrecht; alleen de vrijgavetaak kan de gegevensrelease bijwerken. Een bronwijziging op main start de ronde direct.
2. **Iedere week automatisch:** taak `bronnen` controleert alle sjabloonadressen, de inhoudsvorm, veldnamen en de datum van de landelijke lijst. Het rapport staat in de Actions-samenvatting en in het artifact `bronnencontrole` (30 dagen bewaard). Een fout bij een ingeschakelde bron laat deze taak mislukken. 403/429 betekent beperkte toegang, geen sluiting van een toilet.
3. **Wekelijks door de onderhoudsagent / Rene:** bekijk dat rapport en de bestaande onderhoudsmelding voor een momentopname ouder dan tien dagen. Herstel een kapotte koppeling, voeg een onderbouwde bron toe of documenteer waarom die niet bruikbaar is. Zoek bij uitbreiding ook naar douches en nog niet gedekte gemeenten. Controleer de directe aanbieder en open licentie; ontbrekende eigenschappen blijven onbekend.
4. **Een nieuwe gemeentelijke bron:** voeg één sjabloon toe met een stabiel id, HTTPS-adres, directe informatiepagina, licentie, veldkoppelingen, regels en een begrijpelijke tip. Zonder duidelijke open licentie blijft hij uit. Test een echte respons, daarna `ToilettenTest` en de interfacetests. De testbrug leest automatisch dezelfde sjablonen als de APK.
5. **Een plek onderweg aanvullen:** gebruik in de app toevoegen, aanpassen of afwijzen. Eigen notities blijven op de telefoon. Alleen na je expliciete keuze en voorbeeldtekst gaat een openbare notitie naar OpenStreetMap. Na verwerking door OSM-bijdragers komt die via een volgende momentopname terug; dat gebeurt niet onmiddellijk of gegarandeerd.

Handmatig controleren zonder app of Android-gereedschap:

```sh
python3 -m unittest discover -s tests/toiletten -v
python3 tools/toiletten/bronnen_controleren.py --uit /tmp/toiletten-bronnenrapport.json
```

Een HTTP 200 bevestigt bereikbaarheid en het gegevensformaat, nooit dat een locatie vandaag open is. De broncontrole verandert geen broninstellingen, zet geen gesloten-markering en verstuurt geen gebruikerslocaties.
