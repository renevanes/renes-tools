const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    await pg.click('#tile-backup'); await pg.waitForTimeout(200);
    console.log('nodest card', await pg.isVisible('#bk-nodest'), 'run disabled', await pg.isDisabled('#bk-run'));
    await pg.evaluate(() => { Android._wa.info.dest = true; enterBackup(); });
    await pg.click('#bk-parts input[data-p="music"]'); console.log('music part off', await pg.evaluate(() => Android._bk.parts.music));
    await pg.click('#bk-run'); await pg.waitForTimeout(300);
    console.log('running', await pg.textContent('#bk-run'));
    await pg.waitForTimeout(1500);
    console.log('last', await pg.textContent('#bk-last'), await pg.$$eval('#bk-parts-res .bkres', x => x.map(e => e.innerText.replace(/\n/g, ' '))));
    await pg.click('#bk-auto'); await pg.waitForTimeout(150);
    console.log('next', await pg.textContent('#bk-next'));
    await pg.screenshot({ path: __dirname + '/shots/bk-' + scheme + '.png', fullPage: true });
    await pg.click('button:has-text("Contacten terugzetten")'); await pg.waitForTimeout(300);
    console.log('restore dialog', await pg.textContent('#modal-title'), '|', (await pg.textContent('#modal-text')).replace(/\n/g, ' '));
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    console.log('toast', await pg.textContent('#toast'));
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    console.log('tile', await pg.textContent('#tile-backup-sub'), 'errors', errs);
  }
  await b.close();
})();
