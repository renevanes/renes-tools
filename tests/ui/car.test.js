// Stap 4: Mijn auto, radio-nummer bewaren, gesprek → notitie, verjaardagen op de skin.
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
      await page.waitForFunction(() => typeof enterCar === 'function');
      // Mijn auto
      await page.click('#tile-car');
      assert.equal(await page.evaluate(() => current), 'car');
      assert.match(await page.textContent('#car-trip-state'), /zakelijk 23,4 km · privé 8,1 km/);
      await page.click('button:has-text("Hier staat hij")');
      await page.waitForFunction(() => /Bewaard op/.test(document.querySelector('#car-park-state').textContent));
      assert.equal(await page.isVisible('#car-nav'), true);
      await page.click('#car-trips .trip >> nth=1');
      await page.click('#sheet-acts button:has-text("Maak zakelijk")');
      assert.match(await page.textContent('#car-trip-state'), /zakelijk 31,5 km/);
      await page.click('#car-trips-on');
      assert.equal(await page.evaluate(() => Android._car.trips), true);
      assert.match(await page.textContent('#car-perm'), /Batterij-uitzondering/);
      await page.click('#car-export');
      await page.waitForFunction(() => /2 ritten/.test(document.querySelector('#toast').textContent));
      await page.screenshot({ path: __dirname + '/shots/car-' + colorScheme + '.png', fullPage: true });

      // Radio: nummer bewaren
      await page.evaluate(() => { rdSaveSong('Doe Maar - De Bom', {}); });
      assert.deepEqual(await page.evaluate(() => Android._saved), ['Doe Maar', 'De Bom', '']);

      // Gesprek → notitie (als lijstje, twee zinnen gekozen)
      await page.evaluate(() => { txT = { name: 'Huisarts', mtime: Date.now(), segments: [{ from: 0, text: 'Bel dinsdag terug.' }, { from: 4000, text: 'Neem de uitslag mee.' }, { from: 8000, text: 'Tot ziens.' }] }; txCur = 'x1';
        $('#txd-segs').innerHTML = txT.segments.map((s, i) => '<p class=seg id="seg' + i + '" data-ms="' + s.from + '" onclick="txSegTap(this)">' + s.text + '</p>').join(''); show('txd'); });
      const before = await page.evaluate(() => notesGet().length);
      await page.click('#txd-tonote');
      await page.click('#seg0'); await page.click('#seg1');
      await page.click('#txd-selbar button:has-text("Als lijstje")');
      const n = await page.evaluate(() => notesGet()[notesGet().length - 1]);
      assert.equal(await page.evaluate(() => notesGet().length), before + 1);
      assert.deepEqual(n.items.map(i => i.text), ['Bel dinsdag terug.', 'Neem de uitslag mee.']);
      assert.match(n.title, /Gesprek met Huisarts/);
      assert.deepEqual(errors, []);
      await page.close();

      // Skin: verjaardagen
      const skin = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme });
      const e2 = []; skin.on('pageerror', e => e2.push(e.message));
      await skin.goto('file://' + path.join(__dirname, '..', '..', 'web', 'start.html')); await skin.waitForTimeout(500);
      const txt = await skin.textContent('body');
      assert.match(txt, /Vandaag jarig: Oma \(88\)/);
      assert.match(txt, /Kees over 2 dagen/);
      assert.deepEqual(e2, []);
      await skin.close();
      console.log('errors []');
    }
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
