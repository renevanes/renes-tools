# Noodplan

[Terug naar het overzicht](../README.md)

Wat te doen als de updateketen of de toegang wegvalt. Per geval: wat je merkt, wat je doet, en wat je daarna controleert.
Agents: lees dit bij een mislukte publicatie of een beveiligingsmelding, en doe alleen de stappen waar "agent" bij staat.

## 1. Er staat een slechte versie live

**Je merkt:** de app start niet goed, een tool is kapot, of gegevens kloppen niet na een update.

- **Alleen de interface is kapot:** de app bewaakt dat zelf. Start een gedownloade interface twee keer niet, dan gaat de
  app terug naar de vorige versie en haalt hij de kapotte niet opnieuw op (`WebStart`). Je hoeft niets te doen.
- **De app zelf (APK) is kapot:** Android kan niet terug naar een oudere versie zonder de app te verwijderen. De weg is
  dus vooruit: herstel de fout en publiceer een nieuwe versie (agent: `git revert` van de foute commit op een eigen
  branch, tests, push naar main; nooit geschiedenis herschrijven).
- **De app start helemaal niet meer:** installeer de nieuwste APK met de hand vanaf
  `https://github.com/renevanes/renes-tools/raw/main/update/Renes-Tools.apk` zodra de herstelde versie er staat. Je
  gegevens blijven staan bij installeren over de bestaande app heen.
- **Daarna:** Instellingen → Zelftest, en bij Alles back-uppen *Nu controleren*.

## 2. Publiceren lukt niet meer

**Je merkt:** de workflow Publiceren is rood, of er komt geen nieuwe versie.

- Agent: lees het log van de run. Mislukte test = oplossen op een branch. "De bron is sinds de telefoontest veranderd" =
  de volgende run publiceert het. Een verloren of onleesbaar publicatieslot stopt de publicatie bewust: zoek uit wie
  het slot had (branch `publiceren-slot`) voordat je iets doet.
- Groeit de APK meer dan 10%, dan weigert Publiceren. Alleen Rene start hem dan met de hand (Actions → Publiceren →
  Run workflow → `allow_grow`).
- Zolang Publiceren stil ligt, krijgen telefoons geen update. De app blijft gewoon werken.

## 3. De wachtwoordzin van de ondertekeningssleutel is kwijt

De wachtwoordzin staat op twee plekken: bij Rene zelf en als geheim `SIGNING_PASSPHRASE` in de GitHub-omgeving
`publiceren`. GitHub laat een geheim niet teruglezen.

- **Rene's kopie kwijt, GitHub werkt nog:** publiceren gaat gewoon door. Maak zo snel mogelijk een nieuwe eigen kopie:
  dat kan alleen via een eenmalige workflowstap die de sleutel uitpakt en opnieuw versleutelt met een nieuwe
  wachtwoordzin. Doe dat zelf, niet via een agent, en haal de stap daarna weer weg.
- **Beide kwijt:** de sleutel is onbruikbaar. Zie geval 4, "nieuwe sleutel".
- **Voorkomen:** controleer één keer per jaar dat je eigen kopie werkt: `PASSPHRASE=… ./keys/unlock.sh` op je eigen
  computer moet `keys/release.p12` maken. Verwijder dat bestand daarna.

## 4. De sleutel of de wachtwoordzin is gelekt

**Risico:** iemand met de sleutel én schrijftoegang tot de repository kan een update maken die telefoons vertrouwen.
Zonder schrijftoegang kan hij dat niet: de app haalt updates alleen van deze repository.

1. Zet direct de GitHub-toegang dicht: wachtwoord en tweestapsverificatie van het account nalopen, onbekende sessies,
   tokens en SSH-sleutels intrekken, en kijken wie er op main heeft gepusht.
2. Vervang de wachtwoordzin (zie geval 3) als alleen die gelekt is en `keys/signing-key.enc` openbaar staat. Dat staat
   hij: de repository is openbaar. Een gelekte wachtwoordzin is dus hetzelfde als een gelekte sleutel.
3. **Nieuwe sleutel:** Android installeert een update alleen over een app met dezelfde sleutel. Een nieuwe sleutel
   betekent: eerst een volledige backup maken (Alles back-uppen → Alle appgegevens, met wachtwoord), de app verwijderen,
   de nieuwe APK installeren en de backup terugzetten. Kluis, Bankzaken en Stamgegevens volgen hun eigen regels bij
   terugzetten; lees eerst [Backups en beveiliging](backups-en-beveiliging.md).
4. Meld het in een privé beveiligingsmelding (zie `SECURITY.md`), niet in een openbaar issue.

## 5. Het GitHub-account of de repository is weg

**Je merkt:** controleren op updates geeft een fout; de app zelf werkt door.

- Het updateadres staat vast in de app (`Version.UPDATE_BASE`, `Updater.base`). Zonder deze repository komt er geen
  update meer binnen.
- Herstel het account via GitHub. Lukt dat niet: zet de repository onder een nieuw account, pas het adres aan in
  `build.sh` en `Updater.java`, bouw met de bestaande sleutel en installeer die APK één keer met de hand. Daarna werkt
  de app zichzelf weer bij vanaf het nieuwe adres.
- **Voorkomen:** bewaar een eigen kopie van de repository (een `git clone` op je computer of in je eigen cloud) en van
  de wachtwoordzin, los van GitHub.

## 6. Bouwen en publiceren zonder agents

Alles wat de agents doen, kan met de hand op Linux of WSL2:

```sh
./setup-tools.sh && npm ci && npx playwright install chromium
PASSPHRASE=… ./keys/unlock.sh        # maakt keys/release.p12 en keys/keystore.pass (nooit committen)
./bump.sh "Wat er veranderd is"
./publish.sh                          # test, bouwt, ondertekent en zet de versie op main
```

`./publish.sh` weigert een andere sleutel dan de echte (vingerafdruk). De volledige werkwijze staat in
[Ontwikkelen en updates](ontwikkelen-en-updates.md) en `AGENTS.md`.

## 7. De telefoon is kwijt of kapot

- Zet op de nieuwe telefoon de nieuwste APK en zet de laatste backup terug (Alles back-uppen → Terugzetten). Koppelingen
  met diensten (Google, Spotify, websites van leveranciers) stel je opnieuw in.
- Trek toegang in die aan de oude telefoon hing: de Spotify-app in het developer dashboard, de Google-koppeling bij je
  Google-account, VPN-sleutels op je eigen server, en de sleutel van de statistiek-repository.
