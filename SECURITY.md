# Beveiliging

Rene's Tools heeft toegang tot gevoelige gegevens op de telefoon (sms, oproepen, contacten, locatie, VPN-sleutels) en
werkt zichzelf automatisch bij. Beveiligingsproblemen nemen we daarom serieus.

**Meld een kwetsbaarheid niet in een openbaar issue**, maar privé via GitHub:
[Report a vulnerability](https://github.com/renevanes/renes-tools/security/advisories/new) (tabblad *Security*).
Beschrijf wat je vond en hoe het na te doen is. Je krijgt zo snel mogelijk antwoord.

Hoe de updates beveiligd zijn: elke update (`update/update.json`, de interface en de APK) is ondertekend met een
sleutel die alleen Rene heeft. De app installeert niets met een andere handtekening. De versleutelde sleutel staat in
`keys/signing-key.enc`; de wachtwoordzin staat nergens in deze repository.

*English: please report security issues privately via GitHub's "Report a vulnerability" (Security tab), not in a public issue.*
