# Tools

Android-app voor backups, notities, PDF’s, radio, podcasts, automatiseringen en een lokale assistent. Werkt vanaf Android 7.

**Downloaden:** [APK downloaden](https://github.com/renevanes/renes-tools/raw/main/update/Renes-Tools.apk). Werkt op Android 7 en nieuwer.

**Meedoen:** iedereen kan bijdragen, zie [CONTRIBUTING.md](CONTRIBUTING.md). Voor AI-agents (Claude, Codex …) en de technische details: [AGENTS.md](AGENTS.md). Beveiligingsproblemen: [SECURITY.md](SECURITY.md).

**Licentie:** vrije software onder de [GNU General Public License versie 3 of later](LICENSE) (GPL-3.0-or-later). Copyright © 2026 Rene van Es en bijdragers. Gebruikte onderdelen van anderen: Leaflet (BSD-2), jsQR (Apache-2.0) en whisper.cpp (MIT); hun licenties staan bij de bestanden in `web/vendor/` en `app/lib/`.

## Handleiding

Kies het onderwerp dat je nodig hebt. De volledige uitleg is per onderwerp bewaard.

- [Bellen en berichten](docs/bellen-en-berichten.md)
- [Notities, documenten en routes](docs/notities-en-documenten.md)
- [Slaap en Check-in](docs/slaap-en-checkin.md)
- [De lokale assistent](docs/assistent.md)
- [Muziek en podcasts](docs/muziek-en-podcasts.md)
- [Netwerk en apparaten](docs/netwerk-en-apparaten.md)
- [Startscherm en automatiseringen](docs/startscherm-en-automatiseringen.md)
- [Backups en beveiliging](docs/backups-en-beveiliging.md)
- [Ontwikkelen en updates](docs/ontwikkelen-en-updates.md)

## Ontwikkelen

Lees [CONTRIBUTING.md](CONTRIBUTING.md) voor installatie en tests en [AGENTS.md](AGENTS.md) voor de werkafspraken. De testafhankelijkheden zijn vastgezet in `package-lock.json`: gebruik `npm ci`.

Publicatie volgt pas na de Android-rooktest op API 24, 35 en 36. De ondertekeningsstap controleert dat de gepubliceerde bron overeenkomt met de geteste commit.

Zie ook het [contract van opslag en de interfacebrug](docs/brug-en-opslag.md).
