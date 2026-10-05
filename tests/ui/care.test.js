// Onderhoud: "Sinds gisteravond" op het startscherm van de app en de backup-controle.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    console.log('night card', await pg.isVisible('#home-night'), (await pg.textContent('#home-night')).replace(/\s+/g, ' ').slice(0, 120));
    await pg.screenshot({ path: __dirname + '/shots/care-home-' + scheme + '.png' });
    await pg.click('#home-night button:has-text("Gezien")'); await pg.waitForTimeout(50);
    console.log('night card hidden after seen', !(await pg.isVisible('#home-night')));
    await pg.evaluate(() => show('backup')); await pg.waitForTimeout(100);
    await pg.click('#bk-check-btn'); await pg.waitForTimeout(200);
    console.log('check result', (await pg.textContent('#bk-check')).replace(/\s+/g, ' ').slice(0, 100));
    console.log('errors', errs); await pg.close();
  }
  await b.close();
})();
