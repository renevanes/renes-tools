// Regio en taal: de bewaker. Datums, tijden, getallen, bedragen en eenheden die Rene ziet, gaan via de centrale functies
// (web/src/script/02-regio.js en Regio.java), zodat ze de instellingen bij Instellingen → Regio en taal volgen.
// Deze test faalt op een NIEUWE vaste notatie (toLocaleString, Intl, 'nl-NL', toFixed().replace('.', ','),
// SimpleDateFormat met Nederlands) buiten de helpers. Machinale notaties (ISO voor opslag, bestandsnamen, parameters,
// tekst voor het taalmodel) staan hieronder in de lijst met de reden erbij.
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', '..');
const errs = [];
const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
const rd = p => fs.readFileSync(path.join(root, p), 'utf8');

// ---------- interface ----------
// Bestanden die zelf de helpers zijn (of de testbrug)
const JS_FILES_OK = {
  'web/src/script/02-regio.js': 'de helpers zelf',
  'web/src/script/43-regio-en-taal.js': 'het scherm Regio en taal (lijst met tijdzones, tijdzone van de telefoon)',
  'web/src/script/01-testbrug.js': 'testbrug, niet in de app',
  'web/neutraal.html': 'de neutrale versie (na een verkeerde app-code): een losse rekenhulp zonder app-instellingen of brug, bewust vast nl-NL'
};
// Regels die bewust zo blijven: [bestand, stukje tekst, reden]
const JS_LINES_OK = [
  ['web/src/script/51-assistent.js', "d.toLocaleDateString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })", 'aiSumLines: tekst voor het taalmodel blijft vast nl-NL'],
  ['web/src/script/51-assistent.js', "d.toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }) + ' ' + (m.me", 'aiSumLines: tekst voor het taalmodel blijft vast nl-NL'],
];
const JS_BAD = [
  [/\.toLocale(String|DateString|TimeString)\s*\(/, 'toLocale…() (gebruik fmtDate, fmtTime, fmtDT of fmtNum)'],
  [/\bIntl\.(DateTimeFormat|NumberFormat|RelativeTimeFormat)\b/, 'Intl (gebruik de helpers uit 02-regio.js)'],
  [/['"]nl-NL['"]/, "'nl-NL' (gebruik regioLocale())"],
  [/\.toFixed\(\d\)\s*\.replace\(\s*['"]\.['"]\s*,\s*['"],['"]\s*\)/, "toFixed().replace('.', ',') (gebruik fmtNum)"],
  [/\.replace\(\s*['"]\.['"]\s*,\s*['"],['"]\s*\)/, "replace('.', ',') voor een getal (gebruik fmtNum)"],
  [/['"]€ ?['"]\s*\+/, "'€ ' + bedrag (gebruik fmtMoney)"],
  [/\.replace\(\s*\/\\\.\/g?\s*,\s*['"],['"]\s*\)/, "replace(/\\./, ',') voor een getal (gebruik fmtNum)"],
  [/\.toFixed\([^)]*\)\s*\+\s*['"]\s*(km|mi|m|kB|MB|GB|km\/u|mph|°C?)['"]/, "toFixed() + eenheid (gebruik fmtDist, fmtSize, fmtSpeed of fmtTemp)"],
  [/\+\s*['"]°[CF]?['"]/, "+ '°' (gebruik fmtTemp)"],
];
const jsFiles = ['web/neutraal.html'].concat(fs.readdirSync(path.join(root, 'web/src/script')).filter(f => f.endsWith('.js')).map(f => 'web/src/script/' + f)
  .concat(fs.readdirSync(path.join(root, 'web/src/schermen')).filter(f => f.endsWith('.html')).map(f => 'web/src/schermen/' + f), ['web/src/vensters.html', 'web/src/speler.html']));
const bad = [];
const strip = l => l.replace(/\/\*.*?\*\//g, '').replace(/(^|[^:'"\\])\/\/.*$/, '$1'); // opmerkingen tellen niet
for (const f of jsFiles) {
  if (JS_FILES_OK[f]) continue;
  rd(f).split('\n').forEach((line, i) => {
    const l = strip(line);
    for (const [re, why] of JS_BAD) if (re.test(l) && !JS_LINES_OK.some(([af, part]) => af === f && l.includes(part))) bad.push(f + ':' + (i + 1) + ' ' + why + ': ' + l.trim().slice(0, 120));
  });
}
ok(!bad.length, 'interface: geen vaste notaties buiten de helpers' + (bad.length ? ':\n  ' + bad.join('\n  ') : ''));
for (const f of Object.keys(JS_FILES_OK)) ok(fs.existsSync(path.join(root, f)), 'uitzondering bestaat: ' + f);

// Telefoon-skin: alleen in het blok "Regio en taal"
{
  const s = rd('web/start.html'), a = s.indexOf('/* ---------- Regio en taal'), b = s.indexOf('/* ---------- klok, wekker');
  ok(a > 0 && b > a, 'Telefoon-skin heeft een blok Regio en taal');
  const rest = (s.slice(0, a) + s.slice(b)).split('\n'), sb = [];
  rest.forEach((line, i) => { const l = strip(line); for (const [re, why] of JS_BAD) if (re.test(l)) sb.push(why + ': ' + l.trim().slice(0, 120)); });
  ok(!sb.length, 'Telefoon-skin: geen vaste notaties buiten het blok Regio en taal' + (sb.length ? ':\n  ' + sb.join('\n  ') : ''));
  ok(/typeof Android\.regio === 'function'/.test(s), 'Telefoon-skin leest de keuze van de app (Android.regio)');
}

// ---------- Java ----------
// Een SimpleDateFormat met Nederlands, of Locale.GERMANY/replace voor getallen, is een notatie voor Rene: via Regio.
// Machinale patronen (Locale.US/ROOT: bestandsnamen, ISO, logboek) mogen gewoon.
const JAVA_FILES_OK = { 'Regio.java': 'de helper zelf' };
// Regels die bewust vast blijven: [bestand, stukje tekst, reden]
const JAVA_OK = [
  ['LocalAi.java', 'new java.text.SimpleDateFormat("EEEE d MMMM yyyy", en ? Locale.UK : new Locale("nl", "NL"))', 'datum in de vaste tekst voor het taalmodel (Engels als de app Engels is)'],
  ['SmartSearch.java', 'new java.text.SimpleDateFormat("d MMM yyyy HH:mm", new java.util.Locale("nl", "NL"))', 'datums in de zoekindex voor het taalmodel'],
  ['SmartSearch.java', 'new java.text.SimpleDateFormat("EEEE d MMMM", new java.util.Locale("nl", "NL"))', 'nachten in de zoekindex voor het taalmodel'],
  ['AiData.java', "m.group(1).replace('.', ',')", 'plek in tekst voor het taalmodel'],
  ['AiData.java', 'String.format(Locale.ROOT, "%.1f", v).replace(\'.\', \',\')', 'getal in tekst voor het taalmodel'],
  ['CheckinStore.java', 'String.format(Locale.ROOT, "%.1f", avg).replace(\'.\', \',\')', 'gemiddelde in tekst voor het taalmodel en de zoekindex'],
  ['CheckinCsv.java', ".replaceAll(\"0+$\", \"\").replace('.', ',')", 'vast bestandsformaat: CSV met puntkomma en decimale komma, zoals het factuurdossier'],
  ['CheckinStore.java', "String.valueOf(v).replace('.', ',')", 'getal in tekst voor het taalmodel en de zoekindex'],
  ['FacturenAanlever.java', 'new java.text.SimpleDateFormat("d MMMM yyyy", new Locale("nl"))', 'datum in de brief aan de accountant (vast sjabloon met eigen tijdzone)'],
  ['FacturenDossier.java', 'String.format(Locale.ROOT, "%.2f", Double.parseDouble(v)).replace(\'.\', \',\')', 'bedrag in de CSV voor de accountant (vaste notatie)'],
  ['Car.java', 'String.format(Locale.GERMANY, "%.1f", km)', 'kilometers in de ritten-export voor de belasting (altijd km, vaste notatie)'],
  ['Car.java', 'String.format(Locale.GERMANY, "%.1f", zak)', 'totaal in de ritten-export voor de belasting'],
  ['Secure.java', 'new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("nl", "NL")).format(new Date())', 'datum in inhoud.txt binnen het versleutelde archief (zonder Context)'],
];
const JAVA_BAD = [
  [/new\s+(java\.text\.)?SimpleDateFormat\([^)]*new\s+(java\.util\.)?Locale\("nl"/, 'SimpleDateFormat met Nederlands (gebruik Regio.df of Regio.fmt)'],
  [/Locale\.GERMANY/, 'Locale.GERMANY voor een getal (gebruik Regio.num, Regio.dist of Regio.size)'],
  [/replace\('\.', ','\)/, "replace('.', ',') voor een getal (gebruik Regio.num)"],
];
const jdir = 'app/src/nl/rene/tools', jbad = [];
for (const f of fs.readdirSync(path.join(root, jdir)).filter(f => f.endsWith('.java'))) {
  if (JAVA_FILES_OK[f]) continue;
  rd(jdir + '/' + f).split('\n').forEach((line, i) => { for (const [re, why] of JAVA_BAD) if (re.test(line) && !JAVA_OK.some(([af, part]) => af === f && line.includes(part))) jbad.push(f + ':' + (i + 1) + ' ' + why + ': ' + line.trim().slice(0, 120)); });
}
ok(!jbad.length, 'Java: geen vaste notaties voor Rene buiten Regio' + (jbad.length ? ':\n  ' + jbad.join('\n  ') : ''));
// De uitzonderingen moeten nog nodig zijn (anders weghalen)
for (const [f, part] of JS_LINES_OK) ok(rd(f).includes(part), 'uitzondering nog nodig: ' + f + ' (' + part + ')');
for (const [f, part] of JAVA_OK) ok(rd(jdir + '/' + f).includes(part), 'uitzondering nog nodig: ' + f + ' (' + part.slice(0, 50) + ')');

// ---------- tijdzone: daglogica en tijden via de app-zone ----------
// "Vandaag", per dag/week/maand, invoervelden en tijden uit de opslag rekenen in de app-zone (Regio en taal → Tijdzone):
// rgDayKey, rgIsToday, rgFromLocal, rgNextAt … (02-regio.js) en Regio.zone, Regio.cal, Regio.toMillis (Java). Deze test
// faalt op een NIEUWE toDateString, new Date(j, m, d …), setHours, getHours/getMinutes (interface) of
// Calendar.getInstance() / TimeZone.getDefault() (Java) buiten de helpers. Bewust zo: hieronder, per regel met de reden.
const TZ_JS_OK = [
  ['web/src/script/02-thema.js', 'return new Date(+k.slice(0, 4), +k.slice(5, 7) - 1, +k.slice(8, 10), +hm.slice(0, 2), +hm.slice(3, 5)).getTime();', 'alleen zonder 02-regio.js (de losse rekentest van het thema)'],
  ['web/src/script/54-checkin.js', 'return m ? new Date(+m[1], +m[2] - 1, +m[3], 12) : null;', 'ciDate: een kalenderdatum om mee te rekenen (12:00, niet om te tonen)'],
  ['web/src/script/54-checkin.js', 'const j = new Date(+m[1], 0, 4, 12)', 'ciWeekStart: ISO-week, kalenderrekenen'],
  ['web/src/script/56-checkin-terugkijken.js', 'ciqMonthAt = new Date(n.getFullYear(), q.rhythm', 'maand of jaar van vandaag (ciToday, al in de app-zone): kalenderrekenen'],
  ['web/src/script/56-checkin-terugkijken.js', 'const b = yearly ? new Date(a.getFullYear() + 1, 0, 1, 12)', 'eind van de maand of het jaar: kalenderrekenen'],
  ['web/src/script/56-checkin-terugkijken.js', 'const n = yearly ? new Date(a.getFullYear() + dir, 0, 1, 12)', 'vorige/volgende maand: kalenderrekenen'],
  ['web/src/script/56-checkin-terugkijken.js', 'n > new Date(now.getFullYear(), now.getMonth(), 1, 12)', 'niet voorbij deze maand (now = ciToday): kalenderrekenen'],
  ['web/src/script/56-checkin-terugkijken.js', 'const b = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 12)', 'de afgelopen 7 dagen vanaf ciToday: kalenderrekenen'],
  ['web/src/script/56-checkin-terugkijken.js', 'to = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 12)', 'vergelijken over n dagen vanaf ciToday: kalenderrekenen'],
  ['web/src/script/56-checkin-terugkijken.js', 'ciqMonthAt = new Date(d.getFullYear(), q.rhythm', 'naar de maand van een kalenderdatum'],
  ['web/src/script/57-herstel.js', 'const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);', 'de naam van een backup staat in telefoontijd (Secure.java): dat moment, getoond in de app-zone'],
  ['web/src/script/61-assistent-acties.js', "hm = String(p.getHours()).padStart(2, '0') + ':' + String(p.getMinutes()).padStart(2, '0');", 'de Klok-app rekent in telefoontijd: zo staat de wekker daar'],
  ['web/src/script/64-bankzaken.js', 'const d = new Date(Number(s.slice(0, 4)), Number(s.slice(5, 7)), 0);', 'laatste dag van een maand (datum-alleen-waarden van de bank)'],
  ['web/src/script/64-bankzaken.js', 'const x = new Date(d.getFullYear(), d.getMonth() - m, 1, 12);', 'maanden terug vanaf een bankdatum: kalenderrekenen'],
];
const TZ_JS_FILES_OK = {
  'web/src/script/02-regio.js': 'de helpers zelf', 'web/src/script/01-testbrug.js': 'testbrug, niet in de app',
  'web/src/script/60-gebruik.js': 'schermtijd: Java telt per dag in telefoontijd (GebruikStore), de grafiek volgt dat',
};
// new Date(…) met meer dan één argument (j, m, d …): rekent in telefoontijd
const tzMulti = l => { let i = -1; while ((i = l.indexOf('new Date(', i + 1)) >= 0) { let d = 0, n = 0; for (let j = i + 9; j < l.length; j++) { const c = l[j]; if ('([{'.includes(c)) d++; else if (')]}'.includes(c)) { if (!d) break; d--; } else if (c === ',' && !d) n++; } if (n) return true; } return false; };
const TZ_JS_BAD = [[/\.toDateString\(\)/, 'toDateString (gebruik rgDayKey, rgIsToday of rgIsYesterday)'], [/\.setHours\(/, 'setHours (gebruik rgFromLocal, rgStartOfDay of rgNextAt)'],
  [/\.get(Hours|Minutes)\(\)/, 'getHours/getMinutes (gebruik rgParts, rgHm of rgMinOfDay)'], [{ test: tzMulti }, 'new Date(j, m, d …) (gebruik rgFromLocal, of rgCalDate voor kalenderrekenen)']];
{
  const tb = [], used = new Set();
  for (const f of jsFiles.concat(['web/start.html'])) {
    if (TZ_JS_FILES_OK[f] || f === 'web/neutraal.html') continue;
    let lines = rd(f).split('\n');
    if (f === 'web/start.html') { const a = lines.findIndex(l => l.includes('/* ---------- Regio en taal')), b = lines.findIndex(l => l.includes('/* ---------- klok, wekker')); lines = lines.map((l, i) => i >= a && i < b ? '' : l); }
    lines.forEach((line, i) => {
      const l = strip(line);
      for (const [re, why] of TZ_JS_BAD) if (re.test(l)) { const ok = TZ_JS_OK.find(([af, part]) => af === f && l.includes(part)); if (ok) used.add(ok); else tb.push(f + ':' + (i + 1) + ' ' + why + ': ' + l.trim().slice(0, 120)); }
    });
  }
  ok(!tb.length, 'tijdzone (interface): daglogica en tijden via de app-zone' + (tb.length ? ':\n  ' + tb.join('\n  ') : ''));
  for (const x of TZ_JS_OK) ok(used.has(x), 'tijdzone-uitzondering nog nodig: ' + x[0] + ' (' + x[1].slice(0, 50) + ')');
}
const TZ_JAVA_OK = [
  ['AiBridge.java', 'min = Regio.minuteOfDay(java.util.TimeZone.getDefault(), at)', 'de Klok-app rekent in telefoontijd: hetzelfde moment, omgerekend'],
  ['AiBridge.java', 'Regio.allDayFor(Regio.zone(ctx), java.util.TimeZone.getDefault(), b0, e0)', 'een hele dag in de agenda-app (telefoontijd): dezelfde datum'],
  ['AllBackupJob.java', 'Calendar c = Calendar.getInstance();', 'nachtelijke backup om 03:30: de echte nacht van de telefoon (geen tijd die Rene instelt)'],
  ['WaBackupJob.java', 'Calendar c = Calendar.getInstance();', 'nachtelijke backup om 03:00: de echte nacht van de telefoon'],
  ['TranscribeJob.java', 'int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);', 'uitschrijven alleen \'s nachts: de echte nacht van de telefoon'],
  ['TranscribeJob.java', 'Calendar cal = Calendar.getInstance();', 'uitschrijven vanaf 01:00: de echte nacht van de telefoon'],
  ['Rotate.java', 'Calendar c = Calendar.getInstance();', 'bestandsnamen van backups staan in telefoontijd (machinaal)'],
  ['Rotate.java', 'Calendar cut = Calendar.getInstance();', 'opruimen van oude backups: telefoontijd, net als de bestandsnamen'],
  ['GebruikStore.java', 'static TimeZone tz() { return TimeZone.getDefault(); }', 'schermtijd wordt per dag in telefoontijd opgeteld en zo bewaard'],
  ['Apparaten.java', 'Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz)', 'zonder zone (alleen in tests): de telefoon'],
  ['AutoActions.java', 'return nextTime(m, days, after, java.util.TimeZone.getDefault());', 'variant zonder zone voor de oude tests; de app geeft Regio.zone mee'],
  ['Calls.java', 'return sameDay(a, b, java.util.TimeZone.getDefault());', 'variant zonder zone voor de oude tests; de export geeft de zone van Regio.df mee'],
  ['RadioAlarm.java', 'return next(TimeZone.getDefault(), hour, minute, days, now);', 'variant zonder zone voor de oude tests; de app geeft Regio.zone mee'],
  ['RadioAlarm.java', 'TimeZone.getTimeZone(tz) : TimeZone.getDefault()', 'noodwekker vóór ontgrendelen: de gespiegelde app-zone, anders de telefoon'],
  ['Reminders.java', 'return dayOfMonth(t, java.util.TimeZone.getDefault());', 'variant zonder zone voor de oude tests'],
  ['Facturen.java', 'return voorPeriode(grens, f, naam, java.util.TimeZone.getDefault());', 'variant zonder zone; het ophalen geeft Regio.zone mee'],
  ['Reminders.java', 'return next(t, rep, after, dom, java.util.TimeZone.getDefault());', 'variant zonder zone voor de oude tests; de app geeft Regio.zone mee'],
];
{
  const jb = [], used = new Set();
  for (const f of fs.readdirSync(path.join(root, jdir)).filter(f => f.endsWith('.java') && f !== 'Regio.java')) {
    rd(jdir + '/' + f).split('\n').forEach((line, i) => {
      if (!/Calendar\.getInstance\(\s*\)|TimeZone\.getDefault\(\)/.test(line.replace(/\/\/.*$/, ''))) return;
      const ok = TZ_JAVA_OK.find(([af, part]) => af === f && line.includes(part));
      if (ok) used.add(ok); else jb.push(f + ':' + (i + 1) + ': ' + line.trim().slice(0, 120));
    });
  }
  ok(!jb.length, 'tijdzone (Java): Calendar en TimeZone via Regio.zone / Regio.cal' + (jb.length ? ':\n  ' + jb.join('\n  ') : ''));
  for (const x of TZ_JAVA_OK) ok(used.has(x), 'tijdzone-uitzondering nog nodig: ' + x[0] + ' (' + x[1].slice(0, 50) + ')');
}

// De helpers zitten vóór de schermen in de volgorde (thema en kern gebruiken ze al bij het laden)
{
  const v = rd('web/src/volgorde.txt').split('\n').map(x => x.trim());
  const i = v.indexOf('script/02-regio.js');
  ok(i > v.indexOf('script/02-kern.js') && i < v.indexOf('script/02-thema.js'), '02-regio.js na de kern en vóór het thema');
}
console.log('errors', errs);
process.exit(errs.length ? 1 : 0);
