const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.click('#tile-contacts'); await pg.waitForTimeout(200);
    await pg.click('#ct-perm-btn'); await pg.waitForTimeout(400);
    await pg.screenshot({ path: __dirname + '/shots/ct-list-' + scheme + '.png' });
    await pg.fill('#ct-search', '0611'); await pg.waitForTimeout(400);
    console.log('search', await pg.$$eval('#ct-list button', x => x.length));
    await pg.fill('#ct-search', ''); await pg.waitForTimeout(400);
    await pg.click('#ct-list button >> nth=0'); await pg.waitForTimeout(200);
    await pg.screenshot({ path: __dirname + '/shots/ct-detail-' + scheme + '.png' });
    await pg.click('#s-contact .back'); await pg.click('#ct-home button:has-text("Versies")'); await pg.waitForTimeout(200);
    await pg.screenshot({ path: __dirname + '/shots/ct-versions-' + scheme + '.png' });
    await pg.click('#cv-list button >> nth=0'); await pg.waitForTimeout(200);
    await pg.click('#cvd-list button.mini'); await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    await pg.screenshot({ path: __dirname + '/shots/ct-version-' + scheme + '.png' });
    console.log('restore btn', await pg.textContent('#cvd-list button.mini'));
    await pg.click('#s-cversion .back'); await pg.click('#s-cversions .back'); await pg.click('#s-contacts .back');
    console.log('screen', await pg.evaluate(() => document.querySelector('.screen.on').id), 'errors', errs);
  }
  await b.close();
})();
