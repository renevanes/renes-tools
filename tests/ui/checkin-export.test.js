// Check-in exporteren als CSV: menu ⋯ → eerst de waarschuwing dat het bestand leesbaar is, dan pas de kiezer.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('checkin')); await pg.waitForTimeout(300);
    const knop = await pg.evaluate(() => { const r = $('#ci-meer').getBoundingClientRect(); return { w: r.width, h: r.height, naam: $('#ci-meer').getAttribute('aria-label') }; });
    ok(knop.w >= 44 && knop.h >= 44 && knop.naam === 'Meer opties', scheme + ': menu ⋯ in de kop, groot genoeg');
    await pg.evaluate(() => { Android._ciSeed(); Android._ci.ans.push({ id: 'ax', q: 'x', p: '2026-11-12', t: Date.now(), v: 4, l: 'Goed' }, { id: 'ay', q: 'x', p: '2026-11-13', t: Date.now(), v: 3, l: 'Gaat' }); });
    await pg.click('#ci-meer'); await pg.waitForTimeout(120);
    await pg.click('#sheet-acts button:has-text("Exporteren als CSV")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => $('#modal').classList.contains('on') && /niet versleuteld/.test($('#modal-text').textContent) && Android._ciCsv === 0), scheme + ': eerst de waarschuwing (leesbaar, niet versleuteld), nog niets geëxporteerd');
    await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    const m = await pg.evaluate(() => $('#toast').textContent);
    ok(await pg.evaluate(() => Android._ciCsv === 1) && /^\d+ regels bewaard als CSV/.test(m), scheme + ': daarna de kiezer en het aantal regels: ' + m);
    await pg.evaluate(() => { Android._ci.ans = []; Android._ci.rem = []; ciExportDoe(); }); await pg.waitForTimeout(150);
    ok(/niets om te exporteren/.test(await pg.evaluate(() => $('#toast').textContent)), scheme + ': niets = een melding');
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
