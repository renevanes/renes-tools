// Stap 3: meer in Alles back-uppen, terugzetten van instellingen/routes, zenders ordenen, herinneringen 2.0, notitie van de skin halen.
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
      await page.waitForFunction(() => typeof enterBackup === 'function');

      // Backup-onderdelen
      await page.evaluate(() => show('backup'));
      const parts = await page.$$eval('#bk-parts input[data-p]', x => x.map(e => e.dataset.p));
      assert.ok(parts.includes('settings') && parts.includes('routes'), 'nieuwe onderdelen ontbreken: ' + parts);

      // Instellingen terugzetten: vraag toont wat er bijkomt
      await page.click('button:has-text("Instellingen, automatiseringen en zenders")');
      await page.waitForSelector('#modal.on');
      assert.match(await page.textContent('#modal-text'), /2 automatiseringen, 3 zenders, radiowekker/);
      await page.click('#modal-ok');
      await page.waitForFunction(() => /instellingen toegevoegd/.test(document.querySelector('#toast').textContent));

      // Route importeren vanuit Mijn routes
      await page.evaluate(() => show('tracks'));
      await page.click('button:has-text("Route importeren (GPX)")');
      await page.waitForSelector('#modal.on');
      assert.match(await page.textContent('#modal-text'), /Rondje Plas.*5,23 km.*420 punten/);
      await page.click('#modal-cancel');

      // Zenders ordenen
      await page.evaluate(() => { Android._rd.favs = [{ id: 'a', name: 'Een', url: 'https://a' }, { id: 'b', name: 'Twee', url: 'https://b' }, { id: 'c', name: 'Drie', url: 'https://c' }]; show('radio'); });
      await page.waitForSelector('#rd-favs button[aria-label^="Volgorde van"]');
      await page.click('#rd-favs button[aria-label="Volgorde van Drie"]');
      await page.click('#sheet-acts button:has-text("Bovenaan")');
      assert.deepEqual(await page.evaluate(() => rdFavs.map(f => f.name)), ['Drie', 'Een', 'Twee']);

      // Herinneringen: meerdere, met herhaling, los weghalen
      await page.evaluate(() => { const n = notesGet(); n.push({ id: 'r1', title: 'Pillen', text: 'x', items: [], updated: Date.now() }); openNote('r1'); });
      await page.selectOption('#note-rem-rep', 'week');
      await page.click('button:has-text("Herinnering toevoegen")');
      await page.click('button:has-text("Herinnering toevoegen")');
      assert.equal(await page.locator('#note-rem-list .remrow').count(), 2);
      assert.match(await page.textContent('#note-rem-list'), /elke week/);
      assert.equal(await page.isVisible('#note-rem-del'), true);
      await page.click('#note-rem-list .remrow button >> nth=0');
      assert.equal(await page.locator('#note-rem-list .remrow').count(), 1);

      assert.deepEqual(errors, []);
      await page.close();

      // Skin: notitie lang indrukken → van het startscherm halen
      const skin = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme });
      const e2 = []; skin.on('pageerror', e => e2.push(e.message));
      await skin.goto('file://' + path.join(__dirname, '..', '..', 'web', 'start.html')); await skin.waitForTimeout(400);
      const card = skin.locator('#snotes .nc').first();
      if (await card.count()) {
        await card.scrollIntoViewIfNeeded(); const b = await card.boundingBox();
        await skin.mouse.move(b.x + 20, b.y + 20); await skin.mouse.down(); await skin.waitForTimeout(650); await skin.mouse.up();
        await skin.click('#sacts button:has-text("Van het startscherm halen")');
        await skin.waitForTimeout(100);
        assert.equal(await skin.locator('#snotes .nc').count(), 0);
      }
      assert.deepEqual(e2, []);
      await skin.close();
      console.log('errors []');
    }
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
