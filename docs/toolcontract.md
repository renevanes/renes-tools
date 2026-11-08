# Verplichte afspraken voor iedere tool

Deze afspraken gelden voor alle bestaande tools, losse apps en toekomstige uitbreidingen. Een nieuwe tool is pas klaar als opslag, backup én terugzetten zijn gecontroleerd. De standaard is: **gewone duurzame toolgegevens gaan automatisch mee in de versleutelde backup**. Alleen expliciete technische uitzonderingen en de risicokeuze mogen daarvan afwijken.

## Registratie en zelfstandig gebruik

Registreer de tool eenmalig in `web/src/script/00-tools.js`, met een unieke id, Nederlandse naam, kleur en pictogram. Nieuwe schermtools kunnen automatisch als losse app draaien; de telefoon-skin gebruikt zijn eigen HomeActivity. Maak een eigen startscherm en geef elk subscherm een `BACK`-verwijzing naar de eigen tool. Houd instellingen bereikbaar, sluit bij Terug eerst vensters en laat de losse app binnen zijn eigen tool. Eigen ontgrendeling en schermbeveiliging blijven gelden.

## Opslag die automatisch meegaat

- Bewaar duurzame bestanden onder `getFilesDir()/<tool-id>/` en instellingen in `getSharedPreferences("<tool-id>", MODE_PRIVATE)`. De versleutelde appbackup ontdekt nieuwe mappen en instellingen automatisch; een aparte lijst voor iedere nieuwe gewone tool is niet nodig.
- Gebruik voor nieuwe wachtwoorden en tokens `ToolSecrets.set/get(context, "<tool-id>", sleutel, waarde)`. Die gebruikt `<tool-id>_geheim`: lokaal AES-GCM met de Android-sleutelopslag, in de backup alleen binnen het versleutelde archief, bij herstel opnieuw versleuteld voor de nieuwe installatie. Hetzelfde geldt voor de bestaande Facturen-geheimen. Kopieer nooit onbruikbare toestelgebonden ciphertext naar een andere telefoon.
- Gebruik voor blijvende interfacekeuzes de bestaande `store`-brug. Bewaar gevoelige gegevens van een tool met hoog risico nooit in de gedeelde webstore, localStorage, zoekindex, logs, assistentgeheugen of statistieken.
- Schrijf bestanden atomair en verifieer de inhoud vóór vervangen. Een verzameling bestanden of database vereist een consistente snapshot onder dezelfde schrijfvergrendeling als de tool. Ruwe SQLite-, WAL- en journalbestanden vormen geen herstelbare backup. Een nieuwe database vereist een eigen adapter met een Android-hersteltest; de contracttest blokkeert nieuwe database-implementaties totdat dat is uitgewerkt. De gewone bestandsbackup meldt een database zonder adapter als onvolledig.
- Cache, downloads die opnieuw te maken zijn en tijdelijke gegevens horen in cache/no_backup of in een expliciet gemotiveerde technische uitzondering van `AppData`. Android-toestemmingen, Keystore-sleutels, installatie-identiteit, widgets en lopende taken worden opnieuw aangevraagd of opgebouwd. Werk de herstelchecklist bij als dat nodig is. Een technische uitzondering mag geen ongemerkte uitsluiting van gebruikersgegevens worden.

Nieuwe opslag gaat standaard uitsluitend in de **versleutelde volledige appbackup** mee. De bestaande leesbare losse exports gebruiken bewust hun eigen beperkte lijsten; voeg daar geen nieuwe gegevens of geheimen automatisch aan toe. Voeg alleen na een bewuste productkeuze een extra leesbaar exportformaat toe.

## Risicoclassificatie en uitzonderingen

| Klasse | Standaard | Voorwaarden |
| --- | --- | --- |
| `normaal` (ook zonder extra metadata) | Mee in de versleutelde volledige appbackup | Geen nieuwe toestemming voor leesbare privé-exports |
| `hoog` | Uit, ook bij een nieuwe installatie | Expliciete bevestiging, toestelontgrendeling, Versleutelen en Alle appgegevens aan |

Bankzaken en Stamgegevens zijn verplicht `hoog`. Hun oorspronkelijke gegevens blijven op het toestel zolang de gebruiker deze afzonderlijke backup-keuze niet inschakelt. De keuze staat onder Alles back-uppen → Wat gaat er mee → Hoog risico. Toon altijd zichtbaar dat de extra kopie buiten de tool staat, wie het backup-wachtwoord heeft kan lezen, oude kopieën bij uitzetten blijven bestaan en een verlopen ontgrendeling tot een onvolledige backup kan leiden. Toestemming wordt niet uit een backup teruggezet, vervalt bij een wachtwoordwijziging of uitzetten van versleutelen/Alle appgegevens en moet opnieuw worden gegeven als de geregistreerde opslag, adapter of beleidsversie verandert. Verhoog `versie` in de toolmetadata als het doel of de gevoeligheid van de toegestane kopie wezenlijk verandert; ook dan vervalt oude toestemming. Het aanzetten en het wijzigen van het backup-wachtwoord bij een actieve risico-keuze vragen systeemontgrendeling.

Een toekomstige tool met een gewone bestandsopslag kan zo worden geclassificeerd:

```js
nieuwtool: ['Nieuwe tool', '#345678', '🧰', {
  backup: 'hoog',
  adapter: 'bestanden',
  roots: ['nieuwtool'],
  prefs: ['nieuwtool', 'nieuwtool_geheim']
}]
```

Zonder `roots` is de eigen tool-id de map; zonder `prefs` zijn de tool-id en `<tool-id>_geheim` de instellingen. Geef bestaande afwijkende namen expliciet op (ook losse bestanden zoals `notes.json`). Opslag mag niet tussen twee risicotoestemmingen overlappen. Gebruik geen technische/reserve-namen als nieuwe tool-id. De generator controleert schema en botsingen. Het overzicht en Android gebruiken dezelfde registratie.

Bij eigen toestelgebonden encryptie is `bestanden` onvoldoende. Maak en registreer dan een eigen adapter die de volledige gegevens gecontroleerd naar een overdraagbaar formaat omzet, uitsluitend binnen het versleutelde archief. Bankzaken en Stamgegevens hebben zulke adapters. Nieuwe adapters moeten met dezelfde eisen en tests worden toegevoegd; een onbekende of verkeerd gekoppelde adapter blokkeert het bouwen. Classificatie als hoog risico is geen vervanging voor de eigen beveiliging van de tool. Verplaats eventueel bestaande gevoelige gedeelde opslag eerst naar het eigen beschermde domein en controleer alle eerdere exportpaden.

## Volledigheid en herstel

- Een ontbrekende sleutel, onleesbaar bestand, verlopen ontgrendeling, niet ondersteund schema of afgebroken kopie moet zichtbaar als onvolledig worden gemeld. Zo'n backup mag niet als de laatste complete herinstallatiebackup gelden. Bewaar de vorige goede backup.
- Elk formaat heeft een versie, begrensde groottes en veilige namen. Een backup uit een nieuwere appversie vereist eerst een appupdate, zodat schema en risicoclassificatie bekend zijn. Controleer alle gegevens en controlegetallen tijdens klaarzetten, vóór bestaande gegevens worden verplaatst. Weiger padontsnapping, dubbele namen, onbekende schema's en ongeldige verwijzingen.
- Herstel is transactioneel, herhaalbaar na een crash en draait terug als toepassen niet voltooit. Een kapot of bewust uitgesloten beschermd onderdeel mag de huidige gegevens van die tool niet wissen. Als het archief Bankzaken of Stamgegevens niet bevat, blijven de huidige gegevens daarvan staan.
- Beschermde gegevens staan ook tijdens klaarzetten nooit leesbaar op schijf. Voor Bankzaken en Stamgegevens wordt de tijdelijke AES-sleutel beschermd door een RSA-sleutel in de Android-sleutelopslag waarvoor toestelontgrendeling nodig is. Na toepassen vraagt de eigen tool om ontgrendeling en maakt zijn eigen nieuwe encryptie. Een versleutelde herstelbevestiging voorkomt opnieuw vervangen als de app na de commit maar vóór opruimen stopt.
- De optie Alle appgegevens is de volledige kopie van de tooldata, met bovenstaande risico-uitzonderingen. De andere vinkjes kiezen aanvullende losse exportformaten; de keuzes voor meldingsgeschiedenis en Slaappraat-geluid gelden tevens voor de volledige kopie. Zet dit duidelijk in de interface; een exportvinkje mag geen onbedoelde belofte doen dat dezelfde gegevens ook uit de volledige kopie wegblijven.

## Verplichte oplevercontrole

1. Test gewone nieuwe instellingen én bestanden zonder extra backupregistratie; voer export → herstel onder een andere sleutel uit en vergelijk alle duurzame velden.
2. Test dat geheimen en gegevens met hoog risico niet in leesbare exports terechtkomen, hoog risico standaard uit blijft, weigeren/annuleren niets inschakelt en een gewijzigde policy opnieuw toestemming vereist.
3. Test ontbrekende sleutels, corrupte/onvolledige inhoud, onbekende versies, dubbele archiefnamen en foutinjecties vóór en na de herstelcommit. Controleer dat de huidige gegevens blijven of dat hervatten aantoonbaar werkt.
4. Test zelfstandige start, eigen instellingen, Terug, ontgrendeling en navigatieafbakening in licht/donker. De generieke test loopt iedere geregistreerde tool langs.
5. Werk vertalingen, risico-uitleg en herstelchecklist bij. Draai build, Java-tests, interfacetests en brugcontrole. Laat een onafhankelijke review doen. Wijzigingen aan opslag, risico of encryptie krijgen ook de bijbehorende native tests.

De automatische controles staan in `BackupContractTest`, `AppDataTest`, `SoloTest`, `backup-contract.test.js` en `solo-alle-tools.test.js`; de Android-test controleert een echte SQLite-overdracht naar een andere sleutel. Deze tests draaien ook vóór publiceren. Publiceer zoals in [AGENTS.md](../AGENTS.md), met een wijzigingsregel en controle van de publicatierun.
