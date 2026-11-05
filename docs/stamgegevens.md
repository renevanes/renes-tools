# Centrale stamgegevens

Open **Stamgegevens** op het startscherm. Een schermvergrendeling van Android is vereist. Ontgrendel met de toestelbevestiging; het eigen Tools-appslot vervangt deze controle niet.

Je beheert personen (inclusief contactgegevens en optioneel BSN), organisaties, adressen en identiteitsdocumentgegevens. Koppel een document aan een persoon om verwisseling te voorkomen. Bewaar hier alleen gegevens die je nodig hebt en mag gebruiken. Er worden geen documentfoto's bewaard in deze kluis; ID-kopie afschermen blijft een afzonderlijke, tijdelijke bewerking.

Bij **Formulieren invullen** kun je per persoon of bestemming velden overnemen. Bij **ID-kopie anonimiseren** kun je een persoon of organisatie als ontvanger kiezen. Selecteer de juiste persoon en het juiste document afzonderlijk. Alleen passende velden worden aangeboden. Niet-lege doelvelden en identiteitsgegevens zijn standaard uitgevinkt. BSN, documentnummer en geldigheidsdatum vereisen extra bevestiging. Controleer het resultaat; het is een momentopname en verandert niet mee met latere wijzigingen in stamgegevens.

## Vergrendelen en bewaren

Gegevens worden pas bewaard met **Stamgegeven opslaan**. De kluis vergrendelt bij het verlaten van de app, na vijf minuten inactiviteit en uiterlijk na negen minuten. Niet-bewaarde wijzigingen én open formulieren/ID-bewerkingen waarin stamgegevens zijn overgenomen worden dan gewist, inclusief handmatig aangevulde invoer. Bewaar benodigde bestanden dus bewust vóór het verlaten van de app. Navigeren uit de centrale beheerpagina vergrendelt die sessie ook.

Waarden en activiteiten worden afzonderlijk geauthenticeerd versleuteld met AES-256-GCM. De sleutel zit in Android Keystore en vereist toestelontgrendeling; er is geen terugval op een sleutel zonder deze eis. Of de sleutel hardwarematig beschermd wordt, hangt af van het toestel. De database staat in de privéopslag van Android. Sleutels, stamgegevens en logboek gaan niet mee in Android-back-ups, toesteloverdracht of **Alle appgegevens**. Bij verlies van toestel/sleutel zijn ze niet te herstellen. Installeer niet opnieuw om een fout te proberen te herstellen.

Op centrale schermen, in de veldkiezer en bij open overgenomen gegevens blokkeert Tools schermafdrukken, recente-appvoorbeelden en Android-autofill. Het toetsenbord krijgt vanaf Android 8 de aanwijzing geen invoer te leren. Android en het gekozen toetsenbord bepalen de uiteindelijke uitvoering; een camera, aangepast besturingssysteem of gecompromitteerd toestel wordt hiermee niet tegengehouden. De assistent en algemene zoekindex krijgen geen toegang tot de kluis of deze conceptschermen. Er is geen netwerkverbinding voor deze functie en geen opname in Statistieken.

Tijdens het maken van een expliciet gekozen PDF staat kort een volledig lokaal exportbestand in privé-cache. Bij fouten of ingetrokken sessie wordt het verwijderd; na procesonderbreking ruimt de volgende ontgrendelde appstart achtergebleven exportwerkbestanden en reserveringen op. Een gemaakte, opgeslagen of gedeelde PDF valt buiten de centrale kluis. De ontvanger, cloudmap of andere app beheert zijn eigen kopie. Vergrendelen of wissen van de kluis trekt zulke bestanden niet terug. Verwijder ze zelf waar nodig.

## Activiteitenlogboek

**Activiteiten** toont tijdstip, onderdeel, rol, weergavenaam, revisie en veldnamen bij bekijken, toevoegen, wijzigen, verwijderen en overnemen. Er worden geen veldwaarden gekopieerd naar het logboek. Gebruik daarom geen BSN of documentnummer als weergavenaam. Namen blijven na verwijderen van een record in de activiteiten staan. Pagina's bevatten maximaal 100 regels; laad oudere activiteiten met de knop. Het zoeken betreft alleen geladen regels.

Bestanden maken heeft afzonderlijke regels: **Bestand maken gestart**, **Bestand vrijgegeven** en **Bestand gemaakt**. Vrijgave wordt vóór zichtbaarheid duurzaam vastgelegd. Als de app tijdens opslaan stopt, kan vrijgave zonder bevestigde afronding voorkomen; dat betekent niet dat geen bestand is gemaakt. Het log registreert lokale acties binnen Tools, geen gebruik van reeds gedeelde bestanden door andere apps.

Bewerkingen en hun auditregel vormen één SQLite-transactie. Ciphertext is gebonden aan rij, id en revisie; een versleuteld controlepunt controleert rijen, gebeurtenissen en databaseschema. Beschadiging, verwijderde rijen of gewijzigde ciphertext worden geweigerd zonder de oorspronkelijke database automatisch te vervangen. Dit is SQLite met versleutelde inhoud, geen volledige databaseversleuteling: schema, willekeurige identifiers, revisies en aantallen zijn zichtbaar voor wie het databasebestand kan lezen. Een volledige terugzetting van een oudere geldige database is zonder extern vertrouwd controlepunt niet detecteerbaar. De PDF-bestandspublicatie en SQLite-commit zijn geen gezamenlijke crash-atomaire transactie; daarom wordt vrijgave vooraf vastgelegd.

**Stamgegevens en logboek opnieuw beginnen** verwijdert alle centrale gegevens en activiteiten na het typen van `WISSEN` en een nieuwe toestelbevestiging. Open overgenomen concepten worden ook gewist. Zelf gemaakte/gedeelde bestanden blijven bestaan. Na wissen begin je met een nieuwe sleutel en een leeg logboek. Het logboek is dus geen extern of onverwijderbaar compliance-archief.

## Controle

JVM-tests controleren encryptie, gewijzigde inhoud/context, monotone sessietermijnen, intrekking en veldcontracten. Interfacetests controleren CRUD, afgeschermde velden, expliciete selectie, logboek, DOM-opruiming en late callbacks. Losse CI-instrumentatie controleert echte Android SQLite, encryptie van veldwaarden, transacties, sleutelweigering zonder telefoonvergrendeling, corruptiebehoud en publicatie na intrekking op API 24 en 36 voor beide appvarianten. De positieve toestel-/biometrische bevestiging blijft ook een handmatige toestelcontrole.
