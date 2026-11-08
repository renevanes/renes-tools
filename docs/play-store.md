# Google Play — basisversie publiceren

De eerste uitvoering bevat **Notities, PDF, Radio, Podcasts en Stamgegevens**, pakket `nl.rene.tools.play`. De volledige GitHub-app blijft apart beschikbaar. `play/basis.json` is de expliciete lijst van functies, Android-onderdelen en rechten. Nieuwe functies moeten bewust aan die lijst worden toegevoegd en opnieuw beoordeeld. Uitgesloten: sms/oproeplogboek, telefoonopname, toegankelijkheidsdiensten, apparaatbeheer, stille foto's, locatie, brede bestandstoegang, VPN, AI en de eigen updater.

## Bouwen en controleren

```sh
TOOLS=/pad/naar/toolchain ./build-play.sh
python3 -m unittest discover -s play -p 'test_*.py'
```

De bouw controleert de SHA-256 van bundletool 1.18.3, valideert de AAB en controleert het werkelijke bundelmanifest en de inhoud. Onverwachte rechten, componenten, verkeerde provider-namen of native bestanden laten de bouw falen. De basis bevat de vier gecontroleerde OCR-bibliotheken per ABI voor offline documentherkenning en de twee lokale voorleesbibliotheken op arm64/x86_64; whisper/llama gaan niet mee. De schakelaar `native` betreft de zware AI-programma’s. Java-bron en providernamen worden in een aparte bouwmap verwerkt. `build.sh` bouwt de volledige versie.

Workflow **Play-controle** bouwt met een tijdelijke CI-sleutel, installeert de volledige en de Play-app naast elkaar en doet een rooktest op API 24 en 36. Daarbij worden oude opname-/fotovoorkeuren en beschadigde automatiseringsregels vóór het starten teruggezet; de test controleert dat die daarna uitgeschakeld zijn. De CI-AAB is uitsluitend een testartifact, geen productiebundel. Lokale interface- en Java-tests controleren ook de begrenzing van teruggezette instellingen en verborgen schermen.

Voor een echte upload gebruik je een afzonderlijke uploadsleutel, aangemaakt en veilig bewaard door de accounteigenaar:

```sh
PLAY_KS=/veilige/plek/upload.p12 \
PLAY_KS_PASS_FILE=/veilige/plek/wachtwoord \
PLAY_KS_ALIAS=upload ./build-play.sh
```

De bouw verifieert de JAR-handtekening. Bewaar sleutel en wachtwoord buiten de repository en gebruik Play App Signing. Kies de definitieve pakketnaam vóór de eerste upload; `PLAY_PAKKET` moet op `.play` eindigen. De versiecode komt uit VERSION en moet bij iedere Play-upload hoger zijn. VERSION wordt door de bestaande publicatieworkflow beheerd. Gebruik nooit de tijdelijke CI-sleutel als productie-identiteit.

## Console — nog door de accounteigenaar uit te voeren

1. Ontwikkelaarsaccount aanmaken/verifiëren; definitieve naam, supportcontact en landen kiezen. Verifieer de eventuele organisatiegegevens. VPN is bewust uitgesteld; die vraagt aanvullende beleidscontrole en een organisatieaccount.
2. App maken, Play App Signing instellen en de definitieve uploadcertificaatvingerafdruk vastleggen. Eerst intern testen; upload alleen een zelf gecontroleerde, met de echte uploadsleutel ondertekende AAB.
3. Winkelteksten uit `fastlane/metadata/android/{nl-NL,en-GB}` gebruiken. Voeg echte screenshots van de Play-build, icoon 512×512 en feature graphic 1024×500 toe. CI-schermafdrukken zijn technische controle, geen definitief winkelmateriaal.
4. Privacy-URL: https://github.com/renevanes/renes-tools/blob/main/docs/privacybeleid-play.md . Open de openbare pagina en de link in de app vóór inzending. Gebruik de [Data safety-werkstaat](play-data-safety.md); beantwoord de actuele Console-vragen tegen de werkelijk ingediende versie.
5. Geen advertenties; toegang tot de app vereist geen login. Vul doelgroep en IARC-contentclassificatie eerlijk in (openbare radio/podcastinhoud komt van externe uitgevers). Geef de app niet zonder beoordeling op als specifiek kinderproduct.
6. Declareer foreground-service **mediaPlayback** voor radio/podcasts/cast en lokaal voorlezen, en **dataSync** voor het door de gebruiker gekozen downloaden van een voorleesstem. Maak een demonstratievideo van door de gebruiker gestart afspelen, achtergrondmelding, pauzeren en stoppen. Onderbouw eventueel de overlay voor de optionele zwevende speler. Geen verklaringen voor VPN, toegankelijkheid, all-files of achtergrondlocatie nodig voor deze basis.
7. Test fysieke telefoons: installeren naast de volledige versie, bestanden openen/delen, exportmap kiezen, podcasts downloaden/afspelen, widgets, meldingen geweigerd/toegestaan, alarmrecht geweigerd/toegestaan, reboot vóór/na ontgrendeling en herstel van een Android-back-up. De emulatorrooktest bewijst deze hele matrix niet.
8. Bij een nieuw persoonlijk account kan een gesloten test met minstens 12 testers gedurende 14 aaneengesloten dagen verplicht zijn voordat productietoegang kan worden aangevraagd. Controleer wat de Console voor dit account vraagt. Verwerk het pre-launch report vóór uitrol.

## Instructies voor de reviewer

Geen account, wachtwoord of gekoppelde telefoon nodig. Open Notities en maak een lijst; kies bij Backup maken een map en tik opnieuw op Backup maken. Open een PDF via de systeembestandskiezer. Zoek een radiozender en speel af; sluit de app en stop via de mediamelding. Zoek een podcast, abonneer en speel een aflevering. Stamgegevens is optioneel: stel een Android-schermvergrendeling in en bevestig ontgrendelen om uitsluitend lokale testgegevens toe te voegen. Deze kluis en het activiteitenlog gaan niet mee in back-ups. Internet is nodig voor openbare radio en podcasts. Meldingen, overlay en precieze wekkers zijn optioneel en worden alleen voor de bijbehorende functie gebruikt.

## Uitgestelde uitbreidingen

AI vereist onder meer een werkende meldfunctie in de app met een echte verwerkingsbestemming en aanvullende controles; er is geen meldserver verzonnen of aan statistieken gekoppeld. VPN vereist account- en VpnService-verklaringen. Locatie, contacten en achtergrondhulpmiddelen krijgen pas een eigen profiel na beoordeling van hun noodzaak, uitleg en herstelgedrag. Play laat opnemen van telefoongesprekken via Accessibility niet toe.

Beleidsbronnen (controle 4 november 2026): [targetSdk](https://support.google.com/googleplay/android-developer/answer/11926878), [gebruikersgegevens](https://support.google.com/googleplay/android-developer/answer/10144311), [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [FGS](https://support.google.com/googleplay/android-developer/answer/13392821), [AI](https://support.google.com/googleplay/android-developer/answer/13985936), [toegankelijkheid](https://support.google.com/googleplay/android-developer/answer/16558241), [testvereisten](https://support.google.com/googleplay/android-developer/answer/14151465). Goedkeuring door Google en accountverificatie kunnen niet vanuit de repository worden afgerond.
