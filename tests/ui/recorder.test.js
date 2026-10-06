const assert = require('node:assert/strict');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  try {
    for (const colorScheme of ['light', 'dark']) {
      const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme });
      const errors = []; page.on('pageerror', e => errors.push(e.message));
      await page.goto('file://' + require('path').join(__dirname, 'ui', 'index.html'));
      await page.click('#tile-recorder');
      assert.equal(await page.isDisabled('#recorder-start'), true);
      assert.match(await page.textContent('#s-recorder'), /niet gegarandeerd/);
      assert.match(await page.textContent('#recorder-list'), /Nog geen opnames/);
      await page.click('#recorder-permissions');
      await page.waitForFunction(() => !document.querySelector('#recorder-start').disabled);
      assert.equal(await page.evaluate(() => Android._recorder.busy), false, 'toestemming begint geen opname');
      await page.click('#recorder-start');
      await page.waitForFunction(() => document.querySelector('#recorder-state').textContent.includes('Opname actief'));
      await page.evaluate(() => { Android._recorder.silent = true; Android._recorder.elapsed = 7000; loadRecorder(); });
      assert.equal(await page.isVisible('#recorder-warning'), true);
      await page.evaluate(() => show('home'));
      assert.equal(await page.evaluate(() => Android._recorder.recording), true, 'navigeren stopt een opname niet');
      await page.click('#tile-recorder');
      await page.click('#recorder-stop');
      await page.waitForFunction(() => document.querySelectorAll('#recorder-list article').length === 1);
      assert.match(await page.textContent('#recorder-result'), /Opname opgeslagen/);
      assert.equal(await page.isVisible('#recorder-warning'), false, 'stiltewaarschuwing verdwijnt na stoppen');
      assert.equal(await page.isDisabled('#recorder-list [data-action="export"]'), true);
      await page.click('#recorder-list [data-action="play"]');
      assert.ok(await page.evaluate(() => Android._recorder.playing));
      await page.click('#recorder-list [data-action="share"]');
      assert.ok(await page.evaluate(() => Android._recorder.shared));
      await page.evaluate(() => { Android._wa.info.dest = true; loadRecorder(); });
      await page.click('#recorder-list [data-action="export"]');
      await page.waitForFunction(() => document.querySelector('#recorder-result').textContent.includes('geëxporteerd'));
      assert.equal(await page.evaluate(() => Android._recorder.rows.length), 1, 'export bewaart lokale opname');
      await page.click('#recorder-list [data-action="delete"]'); await page.click('#modal-cancel');
      assert.equal(await page.locator('#recorder-list article').count(), 1);
      await page.click('#recorder-list [data-action="delete"]'); await page.click('#modal-ok');
      assert.equal(await page.locator('#recorder-list article').count(), 0);
      await page.evaluate(() => { Android._recorder.phone = false; Android._recorder.message = 'Opname niet opgeslagen: microfoon geblokkeerd'; loadRecorder(); });
      assert.match(await page.textContent('#recorder-phone'), /zelf stoppen/);
      assert.match(await page.textContent('#recorder-result'), /microfoon geblokkeerd/);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
      await page.screenshot({ path: __dirname + '/shots/recorder-' + colorScheme + '.png' });
      assert.deepEqual(errors, []);
      console.log(colorScheme, 'toestemming, opname, stiltewaarschuwing, navigatie, afspelen, delen, export en wissen gecontroleerd');
      console.log('errors', errors);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
