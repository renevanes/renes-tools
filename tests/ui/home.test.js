const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const groups = () => pg.$$eval('#home-groups .hgroup', x => x.map(g => (g.querySelector('h3') ? g.querySelector('h3').textContent : '-') + ': ' + [...g.querySelectorAll('.tile b')].map(e => e.textContent).join(', ')));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    console.log('default', await groups());
    await pg.screenshot({ path: __dirname + '/shots/home-' + scheme + '.png', fullPage: true });
    await pg.click('.foot button:has-text("Startscherm aanpassen")'); await pg.waitForTimeout(200);
    // move Radio up (crosses into previous group), hide SMS
    await pg.click('#he-groups button[data-t="radio"][aria-label="Omhoog"]'); await pg.waitForTimeout(100);
    await pg.click('#he-groups input[data-t="sms"]'); await pg.waitForTimeout(100);
    await pg.click('#he-compact'); await pg.waitForTimeout(100);
    await pg.screenshot({ path: __dirname + '/shots/homeedit-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    console.log('edited', await groups());
    await pg.screenshot({ path: __dirname + '/shots/home-compact-' + scheme + '.png', fullPage: true });
    await pg.click('#tile-radio'); await pg.waitForTimeout(200);
    console.log('tile opens', await pg.evaluate(() => document.querySelector('.screen.on').id));
    await pg.evaluate(() => goBack());
    await pg.click('.foot button:has-text("Startscherm aanpassen")'); await pg.click('#he-grouped'); await pg.waitForTimeout(100);
    await pg.evaluate(() => goBack()); console.log('flat', (await groups()).length);
    await pg.click('.foot button:has-text("Startscherm aanpassen")'); await pg.click('button:has-text("Standaard herstellen")'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    await pg.evaluate(() => goBack()); console.log('reset', (await groups()).length, 'errors', errs);
  }
  await b.close();
})();
