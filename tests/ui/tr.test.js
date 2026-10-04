const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.screenshot({ path: __dirname + '/shots/tx-home-' + scheme + '.png', fullPage: true });
    await pg.click('#tile-tr'); await pg.waitForTimeout(200);
    await pg.screenshot({ path: __dirname + '/shots/tx-perm-' + scheme + '.png' });
    await pg.click('#tx-files button'); await pg.waitForTimeout(300);
    await pg.click('#tx-models button >> nth=0'); await pg.waitForTimeout(400);
    await pg.screenshot({ path: __dirname + '/shots/tx-dl-' + scheme + '.png' });
    await pg.waitForTimeout(1500);
    console.log('result after dl:', await pg.textContent('#tx-result'));
    await pg.screenshot({ path: __dirname + '/shots/tx-list-' + scheme + '.png', fullPage: true });
    await pg.click('#tx-all'); await pg.click('#modal-ok'); await pg.waitForTimeout(500);
    await pg.screenshot({ path: __dirname + '/shots/tx-run-' + scheme + '.png' });
    await pg.waitForTimeout(1800);
    console.log('result after run:', await pg.textContent('#tx-result'));
    await pg.click('#tx-list button >> nth=0'); await pg.waitForTimeout(300);
    await pg.click('#txd-play'); await pg.waitForTimeout(900);
    await pg.screenshot({ path: __dirname + '/shots/tx-detail-' + scheme + '.png' });
    console.log('play btn', await pg.textContent('#txd-play'));
    await pg.click('#s-txd .back'); await pg.waitForTimeout(200);
    await pg.fill('#tx-search', 'twee uur'); await pg.waitForTimeout(500);
    console.log('search', await pg.$$eval('#tx-results button', x => x.length));
    await pg.screenshot({ path: __dirname + '/shots/tx-search-' + scheme + '.png' });
    await pg.click('#tx-results button >> nth=0'); await pg.waitForTimeout(300);
    console.log('cur seg', await pg.$$eval('#txd-segs .seg.now', x => x.map(e => e.textContent)));
    await pg.click('#s-txd .back'); await pg.click('#s-transcripts .back');
    console.log('screen', await pg.evaluate(() => document.querySelector('.screen.on').id), 'errors', errs);
  }
  await b.close();
})();
