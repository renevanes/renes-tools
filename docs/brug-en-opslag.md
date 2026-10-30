# Opslag en interfacebrug

De JavaScript-brug blijft compatibel met bestaande schermen. Nieuwe wijzigingen bouwen op kleine onderdelen met een afgebakende taak: `UpdateController` verzorgt achtergrondtaken, `WebUpdateStore` de versieactivatie, `UpdateDownload` het begrensd downloaden en `JsonFileStore` het herstelbaar opslaan. De schermcode blijft per functie in `web/src/script/`; algemene dialoogfocus en veilig herladen hebben hun eigen bestand.

| Functie | Invoer | Uitvoer en foutgedrag |
| --- | --- | --- |
| `notesLoad()` | Geen | JSON-array; lege tekst bij onleesbare opslag. Een leesfout mag nooit tot overschrijven met een lege lijst leiden. |
| `notesVersion()` | Geen | Native volgnummer. |
| `notesSaveIf(json, expected)` | JSON-array en eindig geheel volgnummer | `{ok:true,ver:N}`, `{conflict:true}` of `{err:"…"}`. Inhoud en bijbehorende versie worden onder hetzelfde slot vastgesteld. |
| `notesExport()` | Geen | `ok:AANTAL` of een foutmelding. De interface roept dit alleen aan als de actuele bewerkingen eerst opgeslagen zijn. |
| `apKnown(key,nick,type,name)` | Gevalideerde apparaatsleutel; lege bijnaam verwijdert de registratie | Lege tekst uitsluitend bij geslaagde opslag; anders een foutmelding. De interface bewaart de ingevoerde bijnaam voor een volgende poging. |
| `checkUpdates()` | Geen | Asynchroon `onUpdateStatus`: `progress`, `busy`, `uptodate`, `web`, `apk` of `error`. `kind` onderscheidt interface en APK. |
| `reloadUi()` | Geen | Vraagt de huidige pagina via `prepareUiReload()` of herladen veilig is. Zonder instemming wordt de pagina niet vervangen. |

De nabootsingen staan in `01-testbrug.js`; `python3 tools/check-bridge.py` controleert de dekking. Nieuwe brugfuncties krijgen ook een nabootsing en een inhoudelijke test.

**Veilige opslag**

- Een backupkopie is pas geslaagd na schrijven, flushen én sluiten. Een provider mag zijn upload pas bij sluiten afronden.
- Bij vervanging blijven de vorige kopie en het tijdelijke bestand beschikbaar tot de nieuwe kopie compleet is. Herstel vertrouwt een definitieve naam niet als er nog een tijdelijke kopie naast staat.
- `JsonFileStore` valideert bestaande gegevens, bewaart een vorige leesbare versie en behandelt een beschadigd bestand anders dan een lege verzameling. De app levert de echte `JSONObject`-parser; de schijflogica kan zonder Android getest worden.
- Herstelkopieën van tijdelijke apparaatgeschiedenis en trackerwaarnemingen bevatten uitsluitend de huidige, gesnoeide inhoud. Verlopen waarnemingen blijven niet in een vorige of beschadigde kopie staan. Bewust wissen verwijdert ook alle herstelbestanden.
- Interfaceversies blijven binnen `files/web/`, dat al buiten Android-backups en appgegevensarchieven valt. Bestanden en metadata staan in dezelfde onveranderlijke map. Bij een leesfout kan de vorige volledige versie gebruikt worden.
- APK-downloads gebruiken tijdelijke cachebestanden en blokken van 64 KiB. De manifesthandtekening wordt vóór de download gecontroleerd, de bestandshash vóór installatie. Mislukte downloads worden opgeruimd.

**Regressies controleren**

`StorageUpdateTest` controleert sluitfouten, onderbroken activatie, beschadigde opslag en downloadlimieten. `betrouwbaarheid.test.js` controleert opslagfouten, widgetconflicten, opnieuw proberen, updatevoorrang, onopgeslagen PDF-invoer en dialoogfocus. `tests/publicatie-bron.sh` controleert dat een wijziging van appcode of tests een nieuwe emulatorcontrole vereist.
