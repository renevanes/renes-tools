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
  ['web/src/script/63-facturen.js', "toLocaleDateString('sv-SE')", 'ISO-datum (jjjj-mm-dd) voor opslag en invoervelden'],
  ['web/src/schermen/39-facturen.html', "toLocaleDateString('sv-SE')", 'ISO-datum in een invoerveld (type=date)'],
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
  ['LocalAi.java', 'new java.text.SimpleDateFormat("EEEE d MMMM yyyy", new Locale("nl", "NL"))', 'datum in de vaste tekst voor het taalmodel'],
  ['SmartSearch.java', 'new java.text.SimpleDateFormat("d MMM yyyy HH:mm", new java.util.Locale("nl", "NL"))', 'datums in de zoekindex voor het taalmodel'],
  ['SmartSearch.java', 'new java.text.SimpleDateFormat("EEEE d MMMM", new java.util.Locale("nl", "NL"))', 'nachten in de zoekindex voor het taalmodel'],
  ['AiData.java', "m.group(1).replace('.', ',')", 'plek in tekst voor het taalmodel'],
  ['AiData.java', 'String.format(Locale.ROOT, "%.1f", v).replace(\'.\', \',\')', 'getal in tekst voor het taalmodel'],
  ['CheckinStore.java', 'String.format(Locale.ROOT, "%.1f", avg).replace(\'.\', \',\')', 'gemiddelde in tekst voor het taalmodel en de zoekindex'],
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

// De helpers zitten vóór de schermen in de volgorde (thema en kern gebruiken ze al bij het laden)
{
  const v = rd('web/src/volgorde.txt').split('\n').map(x => x.trim());
  const i = v.indexOf('script/02-regio.js');
  ok(i > v.indexOf('script/02-kern.js') && i < v.indexOf('script/02-thema.js'), '02-regio.js na de kern en vóór het thema');
}
console.log('errors', errs);
process.exit(errs.length ? 1 : 0);
