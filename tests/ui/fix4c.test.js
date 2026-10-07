// Stap 4 (laatste deel): spraaknotitie, radiofragment, kluis.
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
      await page.waitForFunction(() => typeof voiceOpen === 'function');
      // Spraaknotitie → nieuwe notitie
      await page.evaluate(() => show('notes'));
      await page.click('button[aria-label="Spraaknotitie inspreken"]');
      await page.click('#voice-btn');
      assert.equal(await page.evaluate(() => $('#voice-btn').classList.contains('rec')), true);
      await page.click('#voice-btn');
      await page.waitForFunction(() => current === 'note');
      assert.match(await page.inputValue('#note-text'), /Melk, brood en kaas/);
      // In een lijstje: losse items
      await page.evaluate(() => { const n = notesGet(); n.push({ id: 'L1', title: 'Boodschappen', text: '', items: [{ id: 'a', text: 'Eieren', done: false }], updated: Date.now() }); openNote('L1'); });
      await page.click('.additem button[aria-label="Inspreken"]');
      await page.click('#voice-btn'); await page.click('#voice-btn');
      await page.waitForFunction(() => noteById('L1').items.length === 4);
      assert.deepEqual(await page.evaluate(() => noteById('L1').items.map(i => i.text)), ['Eieren', 'Melk', 'Brood', 'Kaas']);
      // Geen model: verwijzing naar Gesprekken uitschrijven, Terug sluit het venster
      await page.evaluate(() => { Android._voiceErr = 'model'; voiceOpen(null); });
      await page.click('#voice-btn');
      assert.match(await page.textContent('#voice-state'), /spraakmodel/);
      assert.equal(await page.evaluate(() => goBack()), true);
      assert.equal(await page.isVisible('#voice.on'), false);
      await page.evaluate(() => { Android._voiceErr = ''; });

      // Radiofragment
      await page.evaluate(() => { show('radio'); rdPlay('s', 0); });
      await page.waitForFunction(() => rdState.shift && rdState.shift.clip, null, { timeout: 4000 }).catch(() => {});
      await page.evaluate(() => { rdState.shift = { behind: 6, back: 600, clip: 1500 }; rdClipMenu(); });
      const acts = await page.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
      assert.deepEqual(acts, ['Laatste 5 minuten', 'Laatste 15 minuten']);
      await page.click('#sheet-acts button >> nth=1');
      assert.equal(await page.evaluate(() => Android._clip), 900);

      // Kluis
      await page.evaluate(() => show('kluis'));
      assert.equal(await page.isVisible('#kl-locked'), true);
      await page.click('#kl-locked button:has-text("Ontgrendelen")');
      await page.waitForSelector('#kl-open', { state: 'visible' });
      assert.match(await page.textContent('#kl-list'), /Paspoort\.pdf/);
      await page.click('#kl-open button:has-text("Document toevoegen")');
      await page.waitForFunction(() => document.querySelectorAll('#kl-list .trip').length === 2);
      assert.equal(await page.locator('#kl-list b').filter({ hasText: 'Polis <b>.pdf' }).count(), 1, 'naam wordt niet als HTML gelezen');
      await page.evaluate(() => goBack());
      assert.equal(await page.evaluate(() => Android._kl.open), false, 'kluis gaat op slot bij weggaan');
      assert.deepEqual(errors, []);
      await page.close();
    }
    console.log('errors []');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
