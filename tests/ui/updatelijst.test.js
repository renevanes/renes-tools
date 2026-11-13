// Versie en updates: de updatelijst bevat alleen de laatste versies; de rest van "Wat is er nieuw" komt uit de interface zelf.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
  const r = await pg.evaluate(() => {
    const eigen = [{ version: '1.5', changes: ['e'] }, { version: '1.4', changes: ['d'] }, { version: '1.3', changes: ['c'] }];
    const v = l => l.map(e => e.version).join(' ');
    return {
      nieuwer: v(infoLog([{ version: '1.7', changes: ['g'] }, { version: '1.6', changes: ['f'] }, { version: '1.5', changes: ['e'] }, { version: '1.4', changes: ['d'] }], eigen)),
      gelijk: v(infoLog([{ version: '1.5', changes: ['e'] }, { version: '1.4', changes: ['d'] }], eigen)),
      ver: v(infoLog([{ version: '1.30', changes: ['x'] }, { version: '1.29', changes: ['y'] }], eigen)),
      leeg: v(infoLog([], eigen)), kapot: v(infoLog(null, eigen)), geenEigen: v(infoLog([{ version: '1.7', changes: ['g'] }], [])),
      rommel: v(infoLog([null, { version: '1.5', changes: ['e'] }], eigen))
    };
  });
  ok(r.nieuwer === '1.7 1.6 1.5 1.4 1.3', 'nieuwere versies bovenaan, daarna de hele eigen lijst: ' + r.nieuwer);
  ok(r.gelijk === '1.5 1.4 1.3', 'geen nieuwere versie: de eigen lijst blijft heel: ' + r.gelijk);
  ok(r.ver === '1.30 1.29', 'ver achter (geen overlap): de lijst van de update: ' + r.ver);
  ok(r.leeg === '1.5 1.4 1.3' && r.kapot === '1.5 1.4 1.3' && r.geenEigen === '1.7', 'lege of onleesbare lijst: wat er wel is');
  ok(r.rommel === '1.5 1.4 1.3', 'een kapotte regel telt niet mee: ' + r.rommel);
  // Op het scherm: 2 nieuwere versies uit de updatelijst + de eigen lijst
  const n = await pg.evaluate(() => {
    const top = [{ version: '9.2', date: '2026-11-13', changes: ['Nieuw B'] }, { version: '9.1', date: '2026-11-13', changes: ['Nieuw A'] }].concat(CHANGELOG.slice(0, 18));
    Android.lastManifest = () => JSON.stringify({ versionCode: 1e9, changelog: top });
    show('info'); infoAllOn = true; renderInfo();
    return { h: document.querySelectorAll('#log h3').length, eigen: CHANGELOG.length, eerste: document.querySelector('#log h3').textContent };
  });
  ok(n.h === n.eigen + 2 && /9\.2/.test(n.eerste), 'Alle versies: de 2 nieuwe plus alle ' + n.eigen + ' uit de interface');
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
