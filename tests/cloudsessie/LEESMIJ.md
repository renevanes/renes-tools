# Bouwen en testen in een cloudsessie

Een cloudsessie van een agent (Claude, Codex, …) heeft vaak een proxy die alleen een paar sites toelaat (npm, PyPI,
GitHub). Dan gaat er bij het klaarzetten iets mis dat in CI wel lukt. Deze map vangt dat op, **alleen om lokaal te
testen**: beide scripts weigeren in CI (`CI` gezet), en er komt nooit een APK of iets in `update/` uit.

| Wat gaat mis | Gevolg | Wat je doet |
|---|---|---|
| Maven Central geweigerd (`ant.jar` in `tools/voorlezen/deps.py`, 403) | `./setup-tools.sh` probeert ±10 minuten en stopt; `./build.sh` stopt vóór javac | `./tests/cloudsessie/java-bouwen.sh`: compileert alleen de Java-code naar `build/classes`, tegen lege stand-ins voor ant (`ant-stub/`) |
| `npx playwright install chromium` geweigerd | Interfacetests starten niet ("Executable doesn't exist") | `voorbereiden.sh` gebruikt een Chromium die al in het image staat, onder het nummer dat Playwright verwacht |
| Geen volledige `./build.sh` | `WebSigTest` vindt geen ondertekende `update/update.json` | `./tests/run-jvm.sh` slaat `WebSigTest` en `VoiceArchiveTest` over zolang `ant-codecs.jar` ontbreekt (en zegt dat); in CI draaien ze altijd |
| Geen emulator | De telefoontest kan hier niet | GitHub draait hem bij elke push van je branch (Telefoontest) |

```sh
./tests/cloudsessie/voorbereiden.sh   # gereedschap, npm, Chromium; zegt aan het eind wat er wel en niet is
./tests/cloudsessie/java-bouwen.sh    # alleen als ./build.sh niet kan (zegt voorbereiden.sh)
./tests/run-jvm.sh
python3 tools/check-bridge.py
./tests/run-ui.sh                     # ±10 minuten: start op de achtergrond en lees het logbestand na
```

De APK zelf bouwt en test GitHub (`Bouwen en testen`, `Telefoontest`, `Play-controle`). Daarom: push je branch en wacht
tot die groen zijn voordat je naar main gaat. Het hele stappenplan staat in `AGENTS.md`, "Van begin tot gepubliceerd".
