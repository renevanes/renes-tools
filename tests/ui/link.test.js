const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const rows = () => pg.$$eval('#calls-list button', x => x.length);
    const scr = () => pg.evaluate(() => document.querySelector('.screen.on').id);
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.click('#tile-calls'); await pg.click('#calls-perm-btn'); await pg.waitForTimeout(400);
    console.log('all rows', await rows(), await pg.textContent('#calls-sum-note'));
    await pg.click('#calls-fbtn'); await pg.waitForTimeout(100);
    await pg.click('#cf-who button[data-v="unknown"]'); await pg.waitForTimeout(150);
    console.log('unknown rows', await rows(), 'chips', await pg.$$eval('#calls-active button', x => x.map(e => e.textContent)));
    await pg.click('#cf-dur button[data-v="0"]'); await pg.waitForTimeout(150);
    console.log('unknown+0s rows', await rows());
    await pg.screenshot({ path: __dirname + '/shots/lk-filters-' + scheme + '.png', fullPage: true });
    await pg.click('#calls-active button[data-k="dur"]'); await pg.click('#calls-active button[data-k="who"]'); await pg.waitForTimeout(150);
    await pg.click('#cf-period button[data-v="custom"]'); await pg.waitForTimeout(150);
    console.log('custom 30d rows', await rows(), 'export sel visible', await pg.isVisible('#calls-export-sel'));
    await pg.click('button:has-text("Filters wissen")'); await pg.click('#calls-fbtn'); await pg.waitForTimeout(150);
    // tap a known contact call
    const idx = await pg.evaluate(() => callsShown.findIndex(c => c.ck === 'k1'));
    await pg.click('#calls-list button >> nth=' + idx); await pg.waitForTimeout(150);
    await pg.screenshot({ path: __dirname + '/shots/lk-sheet-' + scheme + '.png' });
    console.log('sheet', await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent)));
    await pg.click('#sheet-acts button >> nth=0'); await pg.waitForTimeout(250);
    console.log('screen after contact', await scr(), await pg.textContent('#ctd-title'));
    await pg.screenshot({ path: __dirname + '/shots/lk-contact-' + scheme + '.png', fullPage: true });
    await pg.click('#ctd-calls button'); await pg.waitForTimeout(250);
    console.log('screen', await scr(), 'person chip', await pg.$$eval('#calls-active button', x => x.map(e => e.textContent)), 'rows', await rows());
    await pg.screenshot({ path: __dirname + '/shots/lk-person-' + scheme + '.png' });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    console.log('back ->', await scr());
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    console.log('back ->', await scr());
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    console.log('back ->', await scr());
    // unknown number -> add to contacts; from home person filter cleared
    await pg.click('#tile-calls'); await pg.waitForTimeout(200);
    console.log('person cleared from home:', await pg.$$eval('#calls-active button', x => x.length));
    const u = await pg.evaluate(() => callsShown.findIndex(c => !c.ck));
    await pg.click('#calls-list button >> nth=' + u); await pg.waitForTimeout(150);
    console.log('sheet unknown', await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent)));
    await pg.click('#sheet-acts button >> nth=1'); await pg.waitForTimeout(200);
    console.log('person rows', await rows(), await pg.$$eval('#calls-active button', x => x.map(e => e.textContent)));
    await pg.click('#calls-export-sel'); await pg.waitForTimeout(100);
    console.log('errors', errs);
  }
  await b.close();
})();
