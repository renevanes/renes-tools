# PDF-formulieren

In Tools: **PDF → Formulieren invullen**. Kies een formulier, vul de stappen in, controleer de samenvatting en beide pagina’s en maak de PDF. Via **Bekijken en afdrukken**, **Opslaan als…** en **Delen** gebruik je dezelfde uitvoer als bij de andere PDF-tools. Gemaakte PDF’s blijven een week onder **PDF → Gemaakt** staan. Print het reistoestemmingsformulier en laat de betrokken personen ondertekenen; de toepassing zet geen handtekeningen namens hen.

De invoer blijft uitsluitend in het geheugen. Er is geen netwerkverkeer voor invullen, voorbeeld of export. Alleen **Officiële bron openen** opent de website in de browser. Concepten gaan niet naar localStorage, backups, zoekindexen of statistieken. Een automatische interface-update wacht zolang er formulierinvoer is. **Formulier leegmaken** wist het concept; eerder gemaakte PDF’s blijven beschikbaar.

## Vaste gegevens en lokale AI

Beheer je personen, identiteitsdocumenten en reizen onder **Stamgegevens**. Een reis bevat de reisperiode, alleen reizen, eventuele tweede begeleider/toestemminggever, bestemming en reisgegevens. Dezelfde reis kun je voor meerdere kinderen gebruiken. Gegevens blijven in de bestaande versleutelde centrale opslag op dit toestel; ze gaan niet mee in backups.

Open in een formulierstap **Invulhulp met vaste gegevens en lokale AI**. Kies de rol (bijvoorbeeld kind of reis), ontgrendel Stamgegevens en kies welke velden je wilt vrijgeven. BSN en documentgegevens zijn niet standaard aangevinkt. Een identiteitsdocument voor de invulhulp kies je nadat je de gekoppelde persoon voor dezelfde rol hebt gekozen. De selectie vult nog geen formulierwaarden in.

Kies je personen als kind en begeleider, dan kan de hulp een gedeelde relatie tussen die personen uit Stamgegevens als bron voorstellen. Van jezelf naar je kind opgeslagen Kind wordt in dit formulier Ouder bij de begeleider. Tegenstrijdige of ontbrekende relaties blijven leeg. Er wordt geen gezag of toestemming afgeleid. Documentfoto’s en het volledige app-profiel gaan niet naar de formulier-AI.

- **Voorstel uit vaste gegevens** koppelt gekozen bronvelden via de formulierdefinitie, zonder taalmodel. Het toont alleen ondubbelzinnige waarden. Bestaande invoer staat standaard uitgevinkt.
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
3. Geef `id`, `title`, `description`, `source` (https), `instruction`, `pages: [{width,height}]` in PDF-punten en `sections: [{title,fields}]` op. Optionele `when: {veldId: waarde}` op sectie of veld bepaalt de zichtbaarheid. Alle voorwaarden moeten overeenkomen. Verborgen velden blijven in het concept, maar komen niet in samenvatting, validatie of PDF.
4. Een veld heeft `id`, `label`, eventueel `type` (`text`, `date`, `tel`, `bsn`, `select`), `required`, `value`, `pattern` en `pdf`. Een select heeft `options: [[waarde, vertaalde tekst]]`. Elke PDF-binding heeft `page` (vanaf 0), `x,y,w,h` in punten vanaf linksboven en eventueel `font`, `choice` (markeer alleen deze selectwaarde) of `part: [begin,einde]` voor deelvakken. `compact` verwijdert spaties en zet tekst in hoofdletters; gebruik passende invoervalidatie bij deelvakken. De engine meldt te lange tekst, zonder stil afkappen.
5. Optionele `rules: [{from,to,message}]` controleren de volgorde van ISO-datumvelden. De interface toont datums via `fmtDate`; de PDF krijgt de vaste notatie dag-maand-jaar die dit formulier vraagt.
6. Voeg betekenisvolle invoer-/exporttests toe, controleer alle veldposities visueel, en draai de bestaande PDF-, vertaal- en brugcontroles. `PdfFormulierTest` controleert automatisch alle gebundelde sjablonen met de echte PDF-parser en export.

## Bron reistoestemming

Geraadpleegd op 5 november 2026:

- [Rijksoverheid: toestemming reizen met minderjarige](https://www.rijksoverheid.nl/documenten/2014/02/06/formulier-toestemming-reizen-met-minderjarige-naar-het-buitenland)
- [Officiële PDF](https://www.rijksoverheid.nl/site/binaries/site-content/collections/documents/2014/02/06/formulier-toestemming-reizen-met-minderjarige-naar-het-buitenland/toestemming-voor-reizen-met-een-minderjarige-naar-het-buitenland.pdf)
- SHA-256 bron: `807f5136ec726fc92490cf5a9942bbbf9779512d196ad518ae6f2033a212b297`.
- SHA-256 gebundeld sjabloon: `765c1121d77f1ec5a929ecf4a4c7d58fd89cf4a508d6545b5f09608ee8d4b0e2`.

Het sjabloon heeft dezelfde twee pagina’s en oorspronkelijke tekst, afbeeldingen en lege vakken. Onzichtbare linkannotaties zijn verwijderd; de zichtbare adressen blijven staan. De lege widgetweergaven zijn vooraf vlak gemaakt met PyMuPDF 1.26.6; PyMuPDF is geen app- of bouwafhankelijkheid. De veldposities zijn gemeten op de bronwidgets. De ja/nee-markering staat op de oorspronkelijke keuzerondjes; de ondertekenvakken blijven vrij. Een eigen PDF naschrijven of vervangen door een samenvatting is niet nodig.

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
