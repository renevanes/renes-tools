# PDF-formulieren

In Tools: **PDF → Formulieren invullen**. Het scherm heeft twee weergaven:

- **Start**: titel, één regel uitleg, de privacyregel en (ingeklapt) **Hoe werkt dit?**. Bij de reistoestemming is er één hoofdknop **Nieuwe reis invullen** (met vaste gegevens) en drie rijen: **Vaste gegevens** en **Reizen** (de lijsten uit Stamgegevens; terug gaat weer naar Formulieren) en **Zelf invullen** (alle velden stap voor stap; de rij laat zien waar je gebleven bent of welke PDF je net maakte). Een formulier zonder vaste gegevens heeft alleen de hoofdknop **Formulier invullen**. De keuzelijst met formulieren staat er pas als er meer dan één formulier is. In het menu ⋯: **Officiële bron openen** en **Formulier leegmaken** (dat laatste pas als er iets is ingevuld of een PDF is gemaakt, en niet terwijl de PDF of een voorstel van de lokale AI wordt gemaakt). Zijn er nog geen vaste gegevens, dan biedt het venster van **Nieuwe reis invullen** naast instellen ook **Zelf invullen**.
- **Stappen**: Reis (data, land, plaats en de drie ja/nee-vragen), Kind, Begeleider(s), Toestemminggever(s) en Controleren. Wat zelden nodig is (accommodatie, postcode en telefoon van de bestemming, reisgegevens, BSN, instelling, datum en plaats van ondertekening, je bewaarde handtekening bij een begeleider) staat per stap onder **Meer gegevens (optioneel)**; dat klapt vanzelf open als er al iets in staat (een keuze die nog op de standaardwaarde, zoals Nee, staat telt niet mee; ook na overnemen) of als daar een fout zit. **Overnemen uit stamgegevens** kiest per stap de passende soort: bij Reis een reis, bij Kind en de andere stappen een persoon. Bij Controleren staat eerst het voorbeeld van beide pagina’s, dan de naam van de PDF en de knop; alle ingevulde gegevens staan ingeklapt onder **Alle ingevulde gegevens**. De terugknop gaat van een stap naar de start; je invoer blijft staan (niet terwijl de PDF wordt gemaakt).

Maak daarna de PDF. Via **Bekijken en afdrukken**, **Opslaan als…** en **Delen** gebruik je dezelfde uitvoer als bij de andere PDF-tools. Gemaakte PDF’s blijven een week onder **PDF → Gemaakt** staan. Print het reistoestemmingsformulier en laat de betrokken personen ondertekenen. De toepassing zet nooit een handtekening namens een ander: alleen je eigen bewaarde handtekening (PDF → Bewerken en ondertekenen → Handtekening bewaren), en alleen bij een begeleider als je dat kiest (veld *Je bewaarde handtekening plaatsen*, of in de vaste gegevens).

De invoer blijft uitsluitend in het geheugen. Er is geen netwerkverkeer voor invullen, voorbeeld of export. Alleen **Officiële bron openen** opent de website in de browser. Concepten gaan niet naar localStorage, backups, zoekindexen of statistieken. Een automatische interface-update wacht zolang er formulierinvoer is. **Formulier leegmaken** wist het concept; eerder gemaakte PDF’s blijven beschikbaar.

## Snel invullen met vaste gegevens

Voor de reistoestemming stel je één keer **vaste gegevens** in: wie er reist, wie meegaat en wie toestemming geeft. Daarna is het per reis: **PDF → Formulieren invullen → Nieuwe reis invullen** (hoofdknop op de start), ontgrendelen, de eerste en laatste reisdag en het land (eventueel plaats en meer) invullen en **Formulier invullen**. Je komt direct op de controlestap met het voorbeeld.

- De vaste gegevens zijn een stamgegeven van de soort **Vaste formuliergegevens** (`formulier`), met alleen verwijzingen: het kind, begeleider(s), toestemminggever(s), hun reisdocumenten en eventueel een instelling, plus *reist alleen*, een vaste relatie (standaard automatisch uit je vastgelegde relaties; of iemand bij de assistent bekend is, maakt niet uit), *BSN op het formulier* en in het blok Ondertekenen de *plaats van ondertekening* en bij welke begeleider je *bewaarde handtekening* komt (`handtekening`: `begeleider1`, `begeleider2` of leeg; `MasterFormProfile.signer`, nooit bij een toestemminggever en niet als het kind alleen reist). Er worden geen namen of nummers gekopieerd: bij elke reis bouwt `MasterFormProfile.resolve` de waarden opnieuw op uit de actuele stamgegevens, met dezelfde rolprojecties (`MasterSchema.projection`) als het handmatig overnemen. Initialen worden afgeleid uit de voornamen als de persoon zelf geen initialen heeft.
- **Ouderlijk gezag**: kies je het kind, dan stelt de app de ouders voor van wie jij bij de relatie hebt vastgelegd dat ze ouderlijk gezag hebben en die niet meereizen (hooguit twee, alleen zolang je zelf nog niets koos), met de melding dat je het moet nakijken. Kies je als toestemminggever iemand die volgens je relaties geen ouderlijk gezag heeft, dan waarschuwt de app. De app leidt zelf geen gezag af: zonder vastgelegde status stelt hij niets voor.
- Bij opslaan controleert `MasterFormProfile.validate` dat iedere verwijzing bestaat en de juiste soort heeft, dat een document bij de gekozen persoon hoort en dat rollen elkaar niet tegenspreken. Een stamgegeven dat in vaste gegevens wordt gebruikt, kun je pas verwijderen nadat je de vaste gegevens hebt aangepast.
- Documentnummer, geldig tot en BSN gaan alleen mee als je dat bij die reis aanvinkt (niet standaard aan); BSN bovendien alleen als de vaste gegevens dat zeggen. Ieder gebruikt stamgegeven komt als "Velden overgenomen" in het logboek.
- Het ingevulde formulier is een vluchtige momentopname zoals bij handmatig overnemen: gebonden aan de ontgrendelde sessie (`masterFormProfile` + `masterApplied`) en gewist bij vergrendelen. Bij de begeleiders komen de datum uit het venster (standaard vandaag, `rgDayKey`), de plaats uit de vaste gegevens en eventueel de handtekening; bij de toestemminggevers blijven datum en plaats leeg, die tekenen zelf. De handtekening wordt bij het tekenen van de laag gelezen (`Android.pdfSignatureLoad`, `fmHandtekening`) en als lijnen in het vak boven de handtekeninglijn gezet (PDF-binding met `signature: true`); is er geen bewaard, dan stopt het voorbeeld met een melding.
- Een nieuw formulier met vaste gegevens: zet het id in `MasterFormProfile.FORMS` en geef in `FM_VAST` (`72-formulier-vast.js`) aan welke reisvelden je per keer invult (een lijst van veld-id's als één invoer op meer plekken komt, zoals de datum van ondertekening).

## Vaste gegevens en lokale AI

Beheer je personen en identiteitsdocumenten onder **Stamgegevens**, reizen en vaste gegevens via de rijen op de start van Formulieren. Een reis bevat de reisperiode, alleen reizen, eventuele tweede begeleider/toestemminggever, bestemming en reisgegevens. Dezelfde reis kun je voor meerdere kinderen gebruiken. Gegevens blijven in de bestaande versleutelde centrale opslag op dit toestel; ze gaan niet mee in backups.

Open in een formulierstap via het menu ⋯ **Invulhulp met lokale AI** (het paneel staat niet standaard in beeld; sluiten kan in het paneel, via ⋯ of door terug te gaan naar de start, dan verdwijnen de gekozen bronnen). Kies de rol (bijvoorbeeld kind of reis), ontgrendel Stamgegevens met **Gegevens kiezen uit Stamgegevens** en kies welke velden je wilt vrijgeven. BSN en documentgegevens zijn niet standaard aangevinkt. Een identiteitsdocument voor de invulhulp kies je nadat je de gekoppelde persoon voor dezelfde rol hebt gekozen. De selectie vult nog geen formulierwaarden in.

Kies je personen als kind en begeleider, dan kan de hulp een vastgelegde relatie tussen die personen uit Stamgegevens als bron voorstellen. Van jezelf naar je kind opgeslagen Kind wordt in dit formulier Ouder bij de begeleider. Tegenstrijdige of ontbrekende relaties blijven leeg. Er wordt geen gezag of toestemming afgeleid. Documentfoto’s en het volledige app-profiel gaan niet naar de formulier-AI.

- **Voorstel zonder AI** koppelt gekozen bronvelden via de formulierdefinitie, zonder taalmodel. Het toont alleen ondubbelzinnige waarden. Bestaande invoer staat standaard uitgevinkt.
- **Voorstel met lokale AI** gebruikt diezelfde geselecteerde bronvelden en je optionele toelichting. Het gebruikt het al geïnstalleerde taalmodel via `LocalAi.privateChatJob`, zonder netwerk, zoeken, acties, leren of gespreksopslag. Zonder passend model kun je de vaste gegevens blijven gebruiken. Een model wordt niet automatisch gedownload.
- Controleer de voorgestelde waarde en bron en kies **Gekozen voorstellen overnemen**. Je bestaande invoer vervang je alleen door het betreffende vakje aan te vinken. Een wijziging aan invoer, toelichting of bronselectie maakt oude voorstellen ongeldig.

De native controle accepteert alleen verwijzingen naar passende, gekozen bronvelden of een letterlijk citaat uit de toelichting. De AI bepaalt geen nieuwe identiteitsnummers. Volledige numerieke datumcitaten worden gecontroleerd en naar ISO omgezet; relatieve of onvolledige datums worden niet afgeleid. Een te grote invoer wordt geweigerd in plaats van stil afgekapt. Antwoorden en veldwaarden worden als tekst getoond. Er wordt niets automatisch ondertekend of geëxporteerd.

Invulhulp blijft gekoppeld aan de ontgrendelde stamgegevenssessie. Bij vergrendelen of verlaten van de app stoppen de lokale invulvraag en verdwijnen bronselecties, toelichting, voorstellen en overgenomen formulierinvoer. Het annuleren van een afgeronde invulvraag kan geen nieuw antwoord van de gewone assistent stoppen. Gemaakte PDF’s blijven buiten de centrale kluis staan, zoals bij handmatig overnemen uit Stamgegevens.

Voor een nieuw formulier geef je per veld `sources: [{role, key}]` op. De invulhulp leest die bindings en gebruikt dezelfde bestaande rollen en vrijgavecontracten van `MasterSchema`. Bijvoorbeeld `kind-naam` gebruikt `{role: 'kind', key: 'naam'}`; een reisdatum gebruikt `{role: 'reis', key: 'van'}`. De hulpmodule bevat geen veld-id’s van het reistoestemmingsformulier. De modeluitvoer en bewijscontrole staan in `PdfFormAssist`; sessie en jobannulering in `PdfFormAi`.

## Opzet

- `web/src/script/68-formulieren.js`: algemene invuller, validatie, voorwaardelijke stappen, samenvatting, tekenlagen en export. Geen formulierinhoud in de engine.
- `web/src/script/67-formulier-reistoestemming.js`: registratie in `PDF_FORMULIEREN`, Nederlandstalige teksten via `t()`, velddefinities en posities.
- `app/formulieren/<id>.pdf`: vlakke, lege PDF-sjablonen, meegebouwd in de gewone APK en de Play-versie.
- `PdfFormulier`: begrensd lezen van uitsluitend gebundelde sjablonen, tijdelijke previewbestanden en atomaire export. De oorspronkelijke gekozen PDF in de editor blijft onaangeraakt.
- `PdfEditor.layers` en `PdfOverlay`: dezelfde begrensde PNG-decoder en incrementele export als voor PDF-bewerking. De sjabloonbytes blijven intact. De ingevulde tekst wordt vastgezet als transparante afbeelding en is geen selecteerbare PDF-tekst.

## Een formulier toevoegen

1. Kies een uniek id dat voldoet aan `[a-z][a-z0-9-]{0,49}`. Voeg een gecontroleerde, vlakke PDF toe in `app/formulieren/<id>.pdf`, zonder widgets, annotaties of certificaatondertekeningen. Noteer bron, datum en SHA-256. Dit vergt een nieuwe APK.
2. Schrijf een definitiefunctie die bij iedere aanroep de actuele vertalingen met `t()` maakt. Registreer die in `PDF_FORMULIEREN`; voeg een nieuw bronbestand toe aan `web/src/volgorde.txt`. Voeg Engelse teksten toe in `web/src/taal/en.json`.
3. Geef `id`, `title`, eventueel `intro` (één regel op de start; zonder `intro` staat daar `description`), `description` (komt in **Hoe werkt dit?**), `source` (https), `instruction`, `pages: [{width,height}]` in PDF-punten en `sections: [{title,fields}]` op. Optionele `when: {veldId: waarde}` op sectie of veld bepaalt de zichtbaarheid. Alle voorwaarden moeten overeenkomen. Verborgen velden blijven in het concept, maar komen niet in samenvatting, validatie of PDF.
4. Een veld heeft `id`, `label`, eventueel `type` (`text`, `date`, `tel`, `bsn`, `select`), `required`, `value`, `pattern`, `more` en `pdf`. Met `more: true` staat een optioneel veld in de stap onder **Meer gegevens (optioneel)**; houd verplichte velden en wat bijna altijd nodig is buiten `more`. Een select heeft `options: [[waarde, vertaalde tekst]]`. Elke PDF-binding heeft `page` (vanaf 0), `x,y,w,h` in punten vanaf linksboven en eventueel `font`, `choice` (markeer alleen deze selectwaarde), `part: [begin,einde]` voor deelvakken of `signature: true` (bij waarde `ja` de eigen bewaarde handtekening, onvervormd en onderaan in het vak). `compact` verwijdert spaties en zet tekst in hoofdletters; gebruik passende invoervalidatie bij deelvakken. De engine meldt te lange tekst, zonder stil afkappen.
5. Optionele `rules: [{from,to,message}]` controleren de volgorde van ISO-datumvelden. De interface toont datums via `fmtDate`; de PDF krijgt de vaste notatie dag-maand-jaar die dit formulier vraagt.
6. Voeg betekenisvolle invoer-/exporttests toe, controleer alle veldposities visueel, en draai de bestaande PDF-, vertaal- en brugcontroles. `PdfFormulierTest` controleert automatisch alle gebundelde sjablonen met de echte PDF-parser en export.

## Bron reistoestemming

Geraadpleegd op 5 november 2026:

- [Rijksoverheid: toestemming reizen met minderjarige](https://www.rijksoverheid.nl/documenten/2014/02/06/formulier-toestemming-reizen-met-minderjarige-naar-het-buitenland)
- [Officiële PDF](https://www.rijksoverheid.nl/site/binaries/site-content/collections/documents/2014/02/06/formulier-toestemming-reizen-met-minderjarige-naar-het-buitenland/toestemming-voor-reizen-met-een-minderjarige-naar-het-buitenland.pdf)
- SHA-256 bron: `807f5136ec726fc92490cf5a9942bbbf9779512d196ad518ae6f2033a212b297`.
- SHA-256 gebundeld sjabloon: `765c1121d77f1ec5a929ecf4a4c7d58fd89cf4a508d6545b5f09608ee8d4b0e2`.

Het sjabloon heeft dezelfde twee pagina’s en oorspronkelijke tekst, afbeeldingen en lege vakken. Onzichtbare linkannotaties zijn verwijderd; de zichtbare adressen blijven staan. De lege widgetweergaven zijn vooraf vlak gemaakt met PyMuPDF 1.26.6; PyMuPDF is geen app- of bouwafhankelijkheid. De veldposities zijn gemeten op de bronwidgets. De ja/nee-markering staat op de oorspronkelijke keuzerondjes. In de ondertekenvakken komt alleen, als je dat kiest, je eigen bewaarde handtekening bij een begeleider (boven de lijn op y 508, pagina 2). Een eigen PDF naschrijven of vervangen door een samenvatting is niet nodig.

Om de voorbereiding te herhalen, download de bron, controleer de bronhash en voer uit met PyMuPDF 1.26.6:

```python
import fitz
document = fitz.open("toestemming-voor-reizen-met-een-minderjarige-naar-het-buitenland.pdf")
for page in document:
    for link in page.get_links():
        page.delete_link(link)
document.bake(annots=True, widgets=True)
document.save("reistoestemming.pdf", garbage=4, deflate=True)
```

Controleer daarna opnieuw beide pagina’s en alle bindings. PDF-document-id’s kunnen bij opnieuw opslaan veranderen; de hash hierboven hoort bij het gecontroleerde, meegeleverde bestand.
