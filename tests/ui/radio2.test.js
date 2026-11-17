const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.click('#tile-radio'); await pg.waitForTimeout(400);
    await pg.click('#rd-list .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(1800);
    await pg.click('#plbar-open'); await pg.waitForTimeout(400);
    console.log('song', await pg.textContent('#rd-song b'), '|', await pg.textContent('#rd-song span'), '| sub', await pg.textContent('#pl-sub'));
    await pg.click('#rd-info summary'); await pg.click('#rd-recent-wrap summary'); await pg.waitForTimeout(150);
    console.log('info', await pg.$$eval('#rd-info-dl dt', x => x.map(e => e.textContent)), 'recent', await pg.$$eval('#rd-recent li', x => x.length));
    await pg.click('#pl-sleep'); await pg.waitForTimeout(150);
    if (!await pg.isVisible('#sleepdlg')) throw new Error('slaaptimer-venster niet open');
    if (await pg.isVisible('#sl-end')) throw new Error('"einde aflevering" hoort niet bij radio');
    if ((await pg.textContent('#sl-vraag')) !== 'Stop de radio na') throw new Error('vraag hoort over de radio te gaan');
    await pg.click('#sl-eigen summary');
    await pg.evaluate(() => { $('#sl-range').value = 42; sleepShow(); }); await pg.click('#sl-go'); await pg.waitForTimeout(1300);
    console.log('sleep', await pg.textContent('#pl-sleep'), '|', await pg.getAttribute('#pl-sleep', 'aria-label'));
    if (!/nog 4[12] min/.test(await pg.textContent('#pl-sleep'))) throw new Error('42 minuten niet gezet');
    await pg.click('#pl-sleep'); await pg.waitForTimeout(150);
    await pg.click('#sl-eigen summary');
    await pg.fill('#sl-time', '23:59'); await pg.click('#sl-at'); await pg.waitForTimeout(1300);
    console.log('sleep at', await pg.getAttribute('#pl-sleep', 'aria-label'));
    if (!/23:59/.test(await pg.getAttribute('#pl-sleep', 'aria-label'))) throw new Error('tijdstip niet gezet');
    await pg.screenshot({ path: __dirname + '/shots/rd2-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => { $('#pl-scroll').scrollTop = 500; }); await pg.waitForTimeout(200);
    await pg.screenshot({ path: __dirname + '/shots/rd2-scroll-' + scheme + '.png' });
    await pg.evaluate(() => { $('#pl-scroll').scrollTop = 0; });
    await pg.click('#pl-sleep'); await pg.waitForTimeout(150);
    await pg.click('#sl-off'); await pg.waitForTimeout(1300);
    console.log('sleep off', JSON.stringify(await pg.textContent('#pl-sleep')), 'errors', errs);
    if ((await pg.textContent('#pl-sleep')) !== 'Slaaptimer') throw new Error('slaaptimer niet uit');
  }
  await b.close();
})();
