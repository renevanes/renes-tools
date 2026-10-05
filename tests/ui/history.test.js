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
      await page.check('#history-enabled');
      assert.match(await page.textContent('#history-state'), /meldingentoegang/);
      await page.click('#history-permission');
      await page.waitForFunction(() => document.querySelector('#history-state').textContent.includes('worden bewaard'));
      await page.evaluate(() => { Android._historyRows.push({ time:Date.now(), app:'<img src=x onerror="window.injected=1">', title:'<script>window.injected=1</script>', text:'Bijzonder & privé', package:'example.test' }); loadHistory(); });
      assert.equal(await page.locator('#history-list img, #history-list script').count(), 0);
      assert.equal(await page.evaluate(() => window.injected), undefined);
      await page.fill('#history-search', 'bijzonder');
      await page.waitForFunction(() => document.querySelectorAll('.history-entry').length === 1);
      assert.match(await page.textContent('#history-list'), /Bijzonder & privé/);
      assert.equal(await page.isDisabled('#history-export'), true);
      await page.evaluate(() => { Android._wa.info.dest = true; loadHistory(); });
      await page.click('#history-export');
      await page.waitForFunction(() => document.querySelector('#history-result').textContent.includes('3 meldingen'));
      await page.click('#history-clear'); await page.click('#modal-cancel');
      assert.equal(await page.evaluate(() => Android._historyRows.length), 3);
      await page.click('#history-clear'); await page.click('#modal-ok');
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
