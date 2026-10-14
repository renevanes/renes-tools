// Versie en updates: vermelding van de licentie (GPL-3.0) en de knop naar de broncode.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android.openUrl = u => { Android._url = u; }; show('info'); }); await pg.waitForTimeout(150);
    const t = await pg.textContent('#s-info');
    if (!t.includes('GPL-3.0-or-later') || !t.includes('vrije software') || !t.includes('Copyright')) errs.push('geen licentievermelding');
    await pg.click('#s-info button:has-text("Broncode en meedoen")');
    const u = await pg.evaluate(() => Android._url);
    console.log('url', u);
    if (u !== 'https://github.com/renevanes/renes-tools') errs.push('verkeerde link: ' + u);
    await pg.click('#s-info button:has-text("Licenties")'); await pg.waitForTimeout(150);
    const acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    if (acts.length !== 5 || !acts.some(a => a.includes('Leaflet'))) errs.push('licentielijst: ' + acts.join(' | '));
    await pg.click('#sheet-acts button:has-text("jsQR")');
    if (!(await pg.evaluate(() => Android._url)).endsWith('web/vendor/JSQR-LICENSE')) errs.push('jsQR-link');
    await pg.screenshot({ path: __dirname + '/shots/licentie-' + scheme + '.png', fullPage: true });
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
