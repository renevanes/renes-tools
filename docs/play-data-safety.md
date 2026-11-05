# Data safety — werkstaat voor de Play-basis

Versieprofiel: `play/basis.json`; controle 4 november 2026. Dit is de onderbouwing voor de accounteigenaar, geen reeds ingediende Console-verklaring. De actuele definitie van verzamelen/delen en de feitelijk gebruikte diensten bepalen het antwoord.

| Gegevens/stroom | Bestemming en doel | Bewaren/verwijderen | Aandachtspunt in Console |
|---|---|---|---|
| Notities/lijstjes/herinneringen | Privéopslag telefoon | In app verwijderen of Android-appgegevens wissen | Alleen verwerking op toestel is op zichzelf geen verzamelen |
| Podcast-/radiozoekopdracht | Apple Podcasts / Radio Browser, zoeken op verzoek | Afhankelijk van externe dienst | Beoordeel zoekgeschiedenis/appinteracties en de uitzondering voor door gebruiker geïnitieerde doorgifte; niet zonder meer “geen gegevens” aanvinken |
| Streams, feeds, afbeeldingen, downloads | Gekozen omroep/uitgever, IP-adres en URL voor levering | Lokale downloads verwijderbaar, externe netwerklogs onder hun beleid | Open ecosysteem; externe uitgevers kunnen eigen gegevens verwerken |
| Centrale stamgegevens en activiteiten | Alleen versleutelde privéopslag toestel; expliciete veldselectie voor andere tools | Wissen in Stamgegevens na toestelbevestiging; geen back-ups, sleutelverlies is onherstelbaar | Persoons-/identiteitsgegevens alleen lokaal verwerkt; zelf gekozen PDF-doorgifte afzonderlijk beoordelen |
| PDF/document/export | Bestandskiezer en door gebruiker gekozen ontvanger/map | Zelfgekozen bestanden apart wissen | Door gebruiker gekozen delen/export correct onderscheiden van automatische verzameling |
| Podcastvoortgang/favorieten | Toestel, optionele Android-back-up | Appgegevens en systeem/cloudback-up afzonderlijk wissen | Systeemoverdracht apart beoordelen volgens de actuele formulierinstructies |
| Cast | Door gebruiker gekozen apparaat op lokaal netwerk | Afhankelijk van ontvanger | Media-URL/titel naar eigen ontvanger |
| Diagnostiek | Lokaal foutlog; geen rapport naar ontwikkelaar | Android-appgegevens wissen | PlayPolicy blokkeert Statistiek.send in basis |

Geen eigen gebruikersaccount, advertentie-ID, advertentie-SDK, betaling, ontwikkelaarstelemetrie, microfoon-, contacten-, sms-, oproep- of locatiegegevens in de Play-basis. Geen AI-modeldownloads of generatieve inhoud. Privacybeleid is in de app onder Instellingen en Info bereikbaar.

HTTPS wordt gebruikt waar bronnen het aanbieden; externe radio/feeds kunnen HTTP aanbieden. Verklaar daarom niet algemeen dat alle gegevens tijdens transport versleuteld zijn zonder de actuele bronkeuze en Console-definitie te toetsen. De app beheert geen serveraccount dat op afstand kan worden verwijderd. Een openbare GitHub-issue is geen geschikt kanaal om persoonlijke inhoud te plaatsen.

Controle vóór inzending: inspecteer de getekende AAB met `play/check.py`, loop de behouden functies door met netwerkinspectie op een testtoestel, vergelijk privacybeleid en winkeltekst en leg de uiteindelijke ingevulde antwoorden vast bij de release. Voeg geen tracking toe om dit formulier te kunnen vullen.
