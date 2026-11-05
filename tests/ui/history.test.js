const assert = require('node:assert/strict');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  try {
    for (const colorScheme of ['light', 'dark']) {
      const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme });
      const errors = []; page.on('pageerror', e => errors.push(e.message));
      await page.goto('file://' + require('path').join(__dirname, 'ui', 'index.html'));
      await page.click('#tile-history');
      assert.equal(await page.isChecked('#history-enabled'), false);
      assert.match(await page.textContent('#history-state'), /staat uit/);
      assert.equal(await page.evaluate(() => document.querySelector('#history-bar').classList.contains('warn')), true, 'bewaren uit: opvallende balk');
      await page.check('#history-enabled');
      assert.match(await page.textContent('#history-state'), /meldingentoegang/);
      await page.click('#history-permission');
      await page.waitForFunction(() => document.querySelector('#history-state').textContent.includes('worden bewaard'));
      await page.evaluate(() => { Android._historyRows.push({ time:Date.now(), app:'<img src=x onerror="window.injected=1">', title:'<script>window.injected=1</script>', text:'Bijzonder & privé', package:'example.test' }); loadHistory(); });
      assert.equal(await page.locator('#history-list img, #history-list script').count(), 0);
      assert.equal(await page.evaluate(() => window.injected), undefined);
      // filter per app
      assert.equal(await page.locator('#history-apps button').count(), 4);
      await page.click('#history-apps button:has-text("WhatsApp")');
      assert.equal(await page.locator('.history-entry').count(), 1);
      assert.match(await page.textContent('#history-list'), /Tot vanavond/);
      await page.click('#history-apps button:has-text("Alle")');
      assert.equal(await page.locator('.history-entry').count(), 3);
      await page.fill('#history-search', 'bijzonder');
      await page.waitForFunction(() => document.querySelectorAll('.history-entry').length === 1);
      assert.match(await page.textContent('#history-list'), /Bijzonder & privé/);
      // Exporteren en wissen staan in het menu ⋯ in de kop
      const menu = async item => { await page.click('#history-menu'); await page.click('#sheet-acts button:has-text("' + item + '")'); };
      await page.click('#history-menu');
      assert.match(await page.textContent('#sheet-acts'), /Backup-map kiezen/, 'zonder backup-map: kiezen in het menu');
      await page.click('#sheet-acts button:has-text("Geschiedenis exporteren")');
      assert.match(await page.textContent('#toast'), /Kies eerst een backup-map/, 'zonder backup-map: eerst een map kiezen');
      await page.evaluate(() => { Android._wa.info.dest = true; loadHistory(); });
      await menu('Geschiedenis exporteren');
      await page.waitForFunction(() => document.querySelector('#history-result').textContent.includes('3 meldingen'));
      await menu('Geschiedenis op deze telefoon wissen'); await page.click('#modal-cancel');
      assert.equal(await page.evaluate(() => Android._historyRows.length), 3);
      await menu('Geschiedenis op deze telefoon wissen'); await page.click('#modal-ok');
      assert.equal(await page.evaluate(() => Android._historyRows.length), 0);
      assert.match(await page.textContent('#history-count'), /0 van 0/);
      await page.uncheck('#history-enabled');
      assert.equal(await page.evaluate(() => Android._historyEnabled), false);
      await page.evaluate(() => show('backup'));
      assert.equal(await page.isChecked('#bk-parts input[data-p="notifications"]'), false);
      await page.check('#bk-parts input[data-p="notifications"]');
      await page.evaluate(() => show('history'));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
      await page.screenshot({ path: __dirname + '/shots/history-' + colorScheme + '.png' });
      assert.deepEqual(errors, []);
      console.log(colorScheme, 'opnemen, toegang, zoeken, HTML-escaping, export, wissen en backupkeuze gecontroleerd');
      console.log('errors', errors);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
