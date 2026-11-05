# Problemen met facturen ophalen onderzoeken

Open **Facturen**, tik op de leverancier en kies **Logboek ophalen**. Je kunt de laatste ophaalpoging en de vier voorgaande pogingen bekijken, kopiëren of delen. Dit werkt ook tijdens het ophalen: sluit het logboek en open het opnieuw om de nieuwste stappen te zien.

Het logboek bewaart per poging de appversie en APK-versie, het begintijdstip en maximaal 160 stappen. Per stap staan de verstreken seconden, het lijstnummer en een aantal. Bij **Factuurlijst geladen** is dat het aantal gevonden downloadknoppen; bij **Download gestart** het aantal aangeboden downloads; bij **PDF ontvangen** het aantal verschillende PDF-documenten; bij **Antwoordcode van de website** de HTTP-code, bijvoorbeeld 200 of 404. De gekozen periode wordt achteraf op factuurdatum toegepast, dus het aantal downloadknoppen kan groter zijn dan het aantal bewaarde facturen.

De stappen laten zien of Tools op **Toon meer** of **Volgende pagina** heeft geklikt, wacht op nieuwe regels, teruggaat uit een factuurdetail of stopt door een laadfout, een herhaalde lijst of het ingestelde maximum. Een aangeboden blob-adres is nog geen ontvangen PDF: pas na het lezen en controleren van de bytes wordt de download afgerond. Een laat resultaat van een vorige knop kan de volgende download niet afronden.

Als laden binnen dertig seconden niet lukt, een download mislukt of het maximum wordt bereikt, blijft de periode onvolledig. Facturen die al binnen zijn worden wel bewaard. Verhoog bij een maximumfout het maximum in de leveranciersinstellingen en kies **Alles van de periode opnieuw ophalen**. Het bestaande dossier wordt daarbij niet gewist en dezelfde PDF wordt niet dubbel toegevoegd.

Het diagnosespoor bevat vaste stappen, tijdstippen, versies en tellingen. Het bevat geen gebruikersnamen, leveranciersnamen, wachtwoorden, inlogcodes, volledige webadressen, bestandsnamen, factuurinhoud of foutteksten van de website. Het blijft lokaal in de bestaande facturenopslag en wordt alleen gedeeld wanneer je daar zelf voor kiest. Het voegt niets toe aan het automatische statistiekrapport.

Voor ontwikkelaars: `FacturenLijstTest` controleert wachten, paginering, uitklappen, teruggaan, limieten en downloadvolgnummers; `FacturenDownloadTest` controleert HTTP-antwoorden en redirects; `facturen-lijsten.test.js` gebruikt de echte robot met KPN-achtige webcomponenten en trage downloads; `facturen-log.test.js` controleert bekijken, kopiëren, delen en het weglaten van factuurgegevens. Robot en Java zitten in de APK: installeer de nieuwste APK voordat je opnieuw test.
