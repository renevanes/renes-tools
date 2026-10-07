// Stap 4: eigen acties en triggers in Automatiseringen.
const assert = require('node:assert/strict');
const path = require('path');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  try {
    for (const colorScheme of ['light', 'dark']) {
      const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme });
      const errors = []; page.on('pageerror', e => errors.push(e.message));
      await page.goto('file://' + path.join(__dirname, 'ui', 'index.html'));
      await page.waitForFunction(() => typeof autoNew === 'function');
      await page.evaluate(() => { const n = notesGet(); n.push({ id: 'b1', title: 'Boodschappen', text: '', items: [{ id: 'x', text: 'Melk', done: false }], updated: Date.now() }); show('auto'); });
      // Sjabloon boodschappenlijst bij de winkel
      await page.click('button:has-text("Boodschappenlijst tonen bij de winkel")');
      assert.equal(await page.evaluate(() => current), 'autoed');
      assert.equal(await page.inputValue('#ae-act'), 'note');
      assert.equal(await page.isVisible('#ae-appbox'), false);
      assert.equal(await page.isVisible('#ae-mode'), false);
      await page.evaluate(() => aeSave());
      assert.match(await page.textContent('#ae-warn'), /plek|notitie/i);
      await page.selectOption('#ae-note', 'b1');
      await page.evaluate(() => { aeRule.place = { name: 'AH', lat: 51.9, lng: 4.4, r: 200 }; });
      await page.evaluate(() => aeSave());
      assert.equal(await page.evaluate(() => current), 'auto');
      // Tijdstip + radio
      await page.evaluate(() => { Android._rd.favs = [{ id: 'a', name: 'NPO Radio 2', url: 'https://radio2' }]; autoNew(''); });
      await page.click('#ae-trig button[data-v="time"]');
      assert.equal(await page.isVisible('#ae-timebox'), true);
      await page.fill('#ae-name', 'Wekker radio');
      await page.selectOption('#ae-act', 'radio');
      await page.selectOption('#ae-radio', '0');
      await page.evaluate(() => aeSave());
      assert.match(await page.textContent('#ae-warn'), /tijdstip/);
      await page.fill('#ae-at', '07:15'); await page.dispatchEvent('#ae-at', 'change');
      await page.click('#ae-mode button[data-v="auto"]');
      assert.match(await page.textContent('#ae-mode [data-v="auto"]'), /Meteen doen/);
      await page.evaluate(() => aeSave());
      assert.equal(await page.evaluate(() => current), 'auto');
      const sums = await page.$$eval('.aurule small', x => x.map(e => e.textContent).join(' | '));
      assert.match(sums, /notitie tonen: Boodschappen/);
      assert.match(sums, /Om 07:15 → radio: NPO Radio 2, meteen/);
      assert.deepEqual(errors, []);
      await page.close();
    }
    console.log('errors []');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
