const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    console.log('dash visible', await pg.isVisible('#home-dash'), await pg.$$eval('#home-dash button', x => x.map(e => e.innerText.replace(/\n/g, ' '))));
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(150);
    await pg.click('#s-settings button:text-is("Zelftest")'); await pg.waitForTimeout(150);
    await pg.click('#sf-run'); console.log('busy', await pg.textContent('#sf-run'));
    await pg.waitForTimeout(600);
    console.log('rows', await pg.$$eval('#sf-res .sfrow', x => x.map(e => e.innerText.replace(/\n/g, ' '))));
    console.log('toast', await pg.textContent('#toast'));
    await pg.click('#sf-res button:has-text("Uitzondering geven")'); console.log('battery opened', await pg.evaluate(() => Android._bat));
    await pg.screenshot({ path: __dirname + '/shots/sf-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => goBack()); await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    console.log('screen', await pg.evaluate(() => current), 'dash', await pg.$$eval('#home-dash button', x => x.map(e => e.innerText.replace(/\n/g, ' '))));
    await pg.screenshot({ path: __dirname + '/shots/dash-' + scheme + '.png' });
    await pg.evaluate(() => { const c = homeCfg(); c.dash = false; homeSave(c); });
    console.log('dash hidden', !(await pg.isVisible('#home-dash')));
    await pg.click('#home-dash button', { timeout: 300 }).catch(() => {});
    console.log('errors', errs);
  }
  await b.close();
})();
