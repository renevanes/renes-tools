const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    await pg.evaluate(() => show('redial')); await pg.waitForTimeout(200);
    console.log('plan state', await pg.textContent('#rp-state'), '| save', await pg.textContent('#rp-save'));
    await pg.evaluate(() => { const p = renderPerms; window.renderPerms = () => ({ call: true, phoneState: true }); });
    await pg.fill('#num', '010 1234567'); await pg.fill('#rp-time', '08:15');
    await pg.click('#rp-days button[data-d="0"]'); await pg.click('#rp-days button[data-d="2"]');
    await pg.click('#rp-save'); await pg.waitForTimeout(150);
    console.log('toast', await pg.textContent('#toast'));
    console.log('plan', await pg.evaluate(() => JSON.stringify(Android._rp)));
    console.log('state', await pg.textContent('#rp-state'), '| clear visible', await pg.isVisible('#rp-clear'));
    await pg.screenshot({ path: __dirname + '/shots/rp-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    console.log('dash', await pg.$$eval('#home-dash button', x => x.map(e => e.innerText.replace(/\n/g, ' '))));
    await pg.evaluate(() => show('redial')); await pg.waitForTimeout(150);
    await pg.fill('#num', '1'); await pg.click('#rp-save'); await pg.waitForTimeout(100); console.log('bad number', await pg.textContent('#toast'));
    await pg.click('#rp-clear'); await pg.waitForTimeout(100);
    console.log('cleared', await pg.evaluate(() => Android._rp), await pg.isVisible('#rp-clear'));
    // automatisch uitschrijven
    await pg.evaluate(() => { Android._tx.files = true; show('transcripts'); }); await pg.waitForTimeout(300);
    console.log('auto visible', await pg.isVisible('#tx-auto'), 'checked', await pg.isChecked('#tx-auto'));
    await pg.click('#tx-auto'); await pg.waitForTimeout(100);
    console.log('on', await pg.evaluate(() => Android._txa.on), '|', await pg.textContent('#tx-auto-note'));
    await pg.click('#tx-auto-note a:has-text("opnieuw")'); await pg.waitForTimeout(100); console.log('retry', await pg.evaluate(() => Android._txa.retried), await pg.textContent('#toast'));
    await pg.screenshot({ path: __dirname + '/shots/txauto-' + scheme + '.png' });
    console.log('errors', errs);
  }
  await b.close();
})();
