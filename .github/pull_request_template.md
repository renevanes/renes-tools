## Wat verandert er?
<!-- Kort: wat doet deze wijziging en waarom? -->

## Controle
- [ ] `./build.sh`, `./tests/run-jvm.sh` en de betrokken interfacetests (`./tests/ui-een.sh …`) slagen
- [ ] Nieuwe functie heeft een test in `tests/ui/` (of `tests/jvm/`)
- [ ] Wijzigingsregel(s) in `wijzigingen/<onderwerp>.txt`, in het Nederlands
- [ ] Geen wijzigingen aan `VERSION`, `changelog.json` of `update/`
- [ ] Geen persoonlijke gegevens, sleutels of wachtwoorden in de code of tests
- [ ] [Toolcontract](../docs/toolcontract.md) gevolgd: zelfstandig gebruik, opslag en volledige versleutelde backup
- [ ] Risicoklasse beoordeeld; hoog risico blijft standaard uit en bestaande kopieën/toestemming zijn toegelicht
- [ ] Export → herstel met nieuwe sleutel en onderbroken/corrupt herstel gecontroleerd; uitgesloten data blijft staan
