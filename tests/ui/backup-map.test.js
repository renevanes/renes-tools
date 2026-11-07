const { chromium } = require('playwright');
/* Backup-map controleren en opruimen: rij in Alles back-uppen, controleren, opruimen met bevestiging, uitkomst,
   elke nacht vanzelf aan/uit, en een fout. */
(async () => {
  const b = await chromium.launch();
  const page = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const errs = [];
    const ok = (c, m) => { if (!c) errs.push('✗ ' + m); };
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(page); await pg.waitForTimeout(400);
    await pg.evaluate(() => { Android._wa.info.dest = true; show('backup'); }); await pg.waitForTimeout(150);
    ok(/opruimen/i.test(await pg.textContent('#bk-sum-map')), 'rij met uitleg: ' + await pg.textContent('#bk-sum-map'));
    await pg.click('#bk-row-map'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current) === 'bkmap', 'scherm geopend');
    ok(/Nog niet gecontroleerd/.test(await pg.textContent('#bm-state')) && !(await pg.isVisible('#bm-fix')), 'eerst: alleen controleren');
    await pg.click('#bm-check'); await pg.waitForTimeout(400);
    const res = await pg.textContent('#bm-res');
    ok(/Dubbele mappen/.test(res) && /Notities/.test(res) && /Oude foto/.test(res), 'gevonden punten en onbekende map: ' + res);
    ok(await pg.isVisible('#bm-fix') && /17 punten/.test(await pg.textContent('#bm-fix')), 'knop opruimen: ' + await pg.textContent('#bm-fix'));
    ok(await pg.evaluate(() => Android._bm.runs.join()) === 'false', 'alleen gecontroleerd, niets veranderd');
    await pg.screenshot({ path: __dirname + '/shots/backup-map-' + scheme + '.png', fullPage: true });
    await pg.click('#bm-fix'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal-title')) === 'Backup-map opruimen?', 'eerst bevestigen');
    await pg.click('#modal-ok'); await pg.waitForTimeout(450);
    ok(await pg.evaluate(() => Android._bm.runs.join()) === 'false,true', 'opgeruimd');
    ok(/samengevoegd/.test(await pg.textContent('#bm-res')) && !(await pg.isVisible('#bm-fix')), 'uitkomst opruimen, knop weg');
    ok(/Opgeruimd/.test(await pg.textContent('#bk-sum-map')), 'rij toont de stand');
    await pg.click('#bm-auto'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._bm.auto) === false && (await pg.getAttribute('#bm-auto', 'aria-checked')) === 'false', 'elke nacht vanzelf uit');
    await pg.evaluate(() => { Android._bm.error = 'Kies eerst een backup-map'; }); await pg.click('#bm-check'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => Android._bm.runs.length) === 3 && !(await pg.isVisible('#bm-fix')), 'fout: niets veranderd');
    await pg.close();
    console.log(scheme, 'errors', errs);
  }
  await b.close();
})();
