// Prestaties: wat vast te tellen is (taak Prestaties en stabiliteit, zie AGENTS.md "Prestaties: grenzen").
// Grootte van de interface, elementen en brugaanroepen bij het starten, en hoeveel rijen een lange lijst tekent.
// Tijden alleen met een zeer ruime grens: deze test draait naast veel andere tegelijk.
const { chromium } = require('playwright');
const fs = require('fs'), os = require('os'), path = require('path'), { execFileSync } = require('child_process');

// Grenzen (gemeten 19-11-2026, versie 1.229; zie het logboek "Prestaties en stabiliteit: logboek")
const GRENS = {
  indexBytes: 4500000,   // web/index.html zonder testbrug: 3,43 MB
  domStart: 10000,       // elementen na het starten: 7.782
  brugStart: 100,        // brugaanroepen tot en met uiReady + 1,5 s: 51
  startMs: 5000,         // tot uiReady: ±300 ms op een ontwikkelmachine
  notesMs: 3000,         // Notities openen met 5000 notities: ±80 ms (was ±630 ms vóór de grens van 100 rijen)
};
const N = 5000;
const WACHT = parseFloat(process.env.UI_WACHT || '1') || 1;

(async () => {
  const errors = [];
  const ok = (v, m) => { console.log((v ? '✓ ' : '✗ ') + m); if (!v) errors.push(m); };

  // 1. Grootte van de echte interface (zonder de nagebootste brug)
  const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'prest-')), 'index.html');
  execFileSync('python3', [path.join(__dirname, '..', '..', 'tools', 'web-samenvoegen.py'), '--zonder-testbrug', tmp], { stdio: 'ignore' });
  const bytes = fs.statSync(tmp).size;
  ok(bytes <= GRENS.indexBytes, 'web/index.html ' + bytes + ' bytes (grens ' + GRENS.indexBytes + ')');

  const browser = await chromium.launch();
  for (const colorScheme of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme, isMobile: true, hasTouch: true });
    // Brugaanroepen tellen: de testbrug zet window.Android; hier komt er een teller omheen
    await ctx.addInitScript(() => {
      window.__brug = 0; window.__klaar = 0;
      let echt;
      Object.defineProperty(window, 'Android', { configurable: true, get(){ return echt; }, set(v){
        echt = new Proxy(v, { get(t, k){ const x = t[k];
          if (typeof x !== 'function' || typeof k !== 'string' || k.startsWith('_')) return x;
          return function(...a){ window.__brug++; if (k === 'uiReady' && !window.__klaar) window.__klaar = performance.now(); return x.apply(t, a); }; } });
      } });
    });
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(colorScheme + ': ' + e.message));
    await page.goto('file://' + path.join(__dirname, 'ui/index.html'));
    await page.waitForFunction(() => window.__klaar > 0, null, { timeout: 30000 * WACHT });
    await page.waitForTimeout(1500);
    const st = await page.evaluate(() => ({ ms: Math.round(window.__klaar), dom: document.getElementsByTagName('*').length, brug: window.__brug }));
    ok(st.ms <= GRENS.startMs * WACHT, colorScheme + ': start tot uiReady ' + st.ms + ' ms (grens ' + GRENS.startMs + ')');
    ok(st.dom <= GRENS.domStart, colorScheme + ': ' + st.dom + ' elementen na het starten (grens ' + GRENS.domStart + ')');
    ok(st.brug <= GRENS.brugStart, colorScheme + ': ' + st.brug + ' brugaanroepen bij het starten (grens ' + GRENS.brugStart + ')');

    // 2. Lange lijsten: niet alles tegelijk tekenen
    await page.evaluate((N) => {
      const A = Android;
      const n0 = JSON.parse(A._notes), ns = [];
      for (let i = 0; i < N; i++) ns.push(Object.assign({}, n0[i % n0.length], { id: 'p' + i, title: 'Proef ' + i, updated: Date.now() - i * 6e4 }));
      A._notes = JSON.stringify(ns); A._nv = (A._nv || 1) + 1; notes = null;
      const h = A._historyRows, hs = []; for (let i = 0; i < N; i++) hs.push(Object.assign({}, h[i % h.length], { time: Date.now() - i * 6e4, title: 'T' + i })); A._historyRows = hs;
      A._ct.perm = true; const c = A._ct.list, cs = []; for (let i = 0; i < N; i++) cs.push(Object.assign({}, c[i % c.length], { id: 'c' + i, n: 'Contact ' + i })); A._ct.list = cs;
      const s = A._nwInit(), as = []; for (let i = 0; i < N; i++) as.push(Object.assign({}, s.artikelen[i % s.artikelen.length], { id: 'a' + i, titel: 'Artikel ' + i, datum: Date.now() - i * 6e4 })); s.artikelen = as;
      const dag = rgToday();
      A._cpNachten = Array.from({length: N}, (_, i) => ({ day: rgDayAdd(dag, -i), mask: 'Masker ' + i, seal: 18 }));
    }, N);
    const open = tool => page.evaluate(async t => { show('home'); const s = performance.now(); openTool(t); await new Promise(r => requestAnimationFrame(() => setTimeout(r, 0))); return Math.round(performance.now() - s); }, tool);

    const ms = await open('notes');
    await page.waitForTimeout(300);
    const rijen = () => page.evaluate(() => document.querySelectorAll('#notes-list .nrow').length);
    ok(await rijen() === 100, colorScheme + ': Notities met ' + N + ' notities tekent 100 rijen (' + await rijen() + ')');
    ok(ms <= GRENS.notesMs * WACHT, colorScheme + ': Notities openen ' + ms + ' ms (grens ' + GRENS.notesMs + ')');
    ok(await page.isVisible('#notes-meer .notes-verder'), colorScheme + ': knop Meer tonen');
    await page.click('#notes-meer .notes-verder'); await page.waitForTimeout(100);
    ok(await rijen() === 200, colorScheme + ': Meer tonen: 100 rijen erbij (' + await rijen() + ')');
    ok(await page.evaluate(() => document.activeElement === document.querySelectorAll('#notes-list .nrow')[100]), colorScheme + ': focus op de eerste nieuwe rij');
    // Terug uit een notitie houdt wat er stond; opnieuw openen vanaf het startscherm begint weer bij 100
    await page.evaluate(() => openNote('p150')); await page.waitForTimeout(150);
    ok(await page.evaluate(() => current) === 'note', colorScheme + ': notitie 150 te openen');
    await page.evaluate(() => goBack()); await page.waitForTimeout(200);
    ok(await page.evaluate(() => current) === 'notes' && await rijen() === 200, colorScheme + ': terug uit de notitie: nog 200 rijen (' + await rijen() + ')');
    await open('notes'); await page.waitForTimeout(100);
    ok(await rijen() === 100, colorScheme + ': opnieuw openen: weer 100 rijen');
    // Zoeken vindt ook wat (nog) niet getekend was
    await page.fill('#notes-search', 'Proef 4999'); await page.evaluate(() => renderNotes()); await page.waitForTimeout(100);
    ok(await rijen() === 1 && !(await page.isVisible('#notes-meer .notes-verder')), colorScheme + ': zoeken vindt notitie 4999, zonder Meer tonen');
    await page.fill('#notes-search', ''); await page.evaluate(() => renderNotes());
    // Weinig notities: geen knop
    await page.evaluate(() => notesFilter('lists')); await page.waitForTimeout(50);
    ok(await rijen() <= 100, colorScheme + ': filter Lijsten begrensd (' + await rijen() + ')');
    await page.evaluate(() => notesFilter('all'));

    await open('contacts'); await page.waitForTimeout(400);
    const ct = await page.evaluate(() => document.querySelectorAll('#ct-list button').length);
    ok(ct > 0 && ct <= 600, colorScheme + ': Contacten met ' + N + ' contacten tekent hooguit 600 rijen (' + ct + ')');
    await open('history'); await page.waitForTimeout(400);
    const hi = await page.evaluate(() => document.querySelectorAll('#history-list .history-entry').length);
    ok(hi > 0 && hi <= 200, colorScheme + ': Meldingsgeschiedenis tekent hooguit 200 meldingen (' + hi + ')');
    await open('nieuws'); await page.waitForTimeout(600);
    const nwr = await page.evaluate(() => document.querySelectorAll('#nw-lijst .nw-rij').length);
    ok(nwr > 0 && nwr <= 60, colorScheme + ': Nieuws met ' + N + ' artikelen tekent hooguit 60 rijen (' + nwr + ')');
    // SMS-backup: duizenden gesprekken in delen van 200, met Meer tonen
    await page.evaluate((N) => {
      const A = Android; A._sms.perm = true; A._smsDemo();
      const c = A._sms.convs, cs = [];
      for (let i = 0; i < N; i++) { cs.push(Object.assign({}, c[i % c.length], { thread: 9000 + i, name: 'Gesprek ' + i, last: Date.now() - i * 6e4 })); A._sms.msgs[9000 + i] = A._sms.msgs[c[i % c.length].thread]; }
      A._sms.convs = cs;
    }, N);
    await open('sms'); await page.waitForTimeout(300);
    const smsRijen = () => page.evaluate(() => document.querySelectorAll('#sms-list > button').length);
    ok(await smsRijen() === 200, colorScheme + ': SMS-backup met ' + N + ' gesprekken tekent 200 rijen (' + await smsRijen() + ')');
    await page.click('#sms-meer .sms-verder'); await page.waitForTimeout(100);
    ok(await smsRijen() === 400, colorScheme + ': SMS-backup Meer tonen: 200 rijen erbij (' + await smsRijen() + ')');
    ok(await page.evaluate(() => document.activeElement === document.querySelectorAll('#sms-list > button')[200]), colorScheme + ': SMS-backup: focus op de eerste nieuwe rij');
    await page.evaluate(() => onSmsChanged()); await page.waitForTimeout(100);
    ok(await smsRijen() === 400, colorScheme + ': SMS-backup: opnieuw laden in het scherm houdt Meer tonen (' + await smsRijen() + ')');
    await page.evaluate(() => { show('smschat'); goBack(); }); await page.waitForTimeout(150);
    ok(await smsRijen() === 400, colorScheme + ': SMS-backup: terug uit een gesprek houdt Meer tonen (' + await smsRijen() + ')');
    await page.fill('#sms-search', 'zz'); await page.evaluate(() => smsSearchDo('zz')); await page.waitForTimeout(400);
    ok(!(await page.isVisible('#sms-meer .sms-verder')), colorScheme + ': SMS-backup: tijdens zoeken geen Meer tonen');
    await page.fill('#sms-search', ''); await page.evaluate(() => smsSearchDo(''));
    await open('sms'); await page.waitForTimeout(200);
    ok(await smsRijen() === 200, colorScheme + ': SMS-backup: opnieuw openen begint weer bij 200 (' + await smsRijen() + ')');
    await open('cpap');
    await page.selectOption('#cp-periode', 'all');
    const cpap = await page.evaluate(() => ({nachten: document.querySelectorAll('#cp-nachten button').length,
      maskers: document.querySelectorAll('#cp-maskers article').length}));
    ok(cpap.nachten === 30 && cpap.maskers === 12, colorScheme + ': CPAP tekent eerst 30 nachten en 12 maskers');
    await ctx.close();
  }
  await browser.close();
  console.log('errors', JSON.stringify(errors));
  process.exit(errors.length ? 1 : 0);
})();
