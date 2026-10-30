const { chromium } = require('playwright');
const assert = require('node:assert/strict');
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
    assert.ok((await pg.locator('#txd-segs .seg').count()) > 0, 'Herkenbare spraak staat met tijdcodes in het transcript');
    assert.ok(!(await pg.textContent('#txd-segs')).includes('Er is geen spraak herkend'), 'Geen melding over ontbrekende spraak bij een normaal transcript');
    await pg.click('#txd-play'); await pg.waitForTimeout(900);
    await pg.screenshot({ path: __dirname + '/shots/tx-detail-' + scheme + '.png' });
    console.log('play btn', await pg.textContent('#txd-play'));
    await pg.click('#s-txd .back'); await pg.waitForTimeout(200);
    await pg.fill('#tx-search', 'twee uur'); await pg.waitForTimeout(500);
    console.log('search', await pg.$$eval('#tx-results button', x => x.length));
    await pg.screenshot({ path: __dirname + '/shots/tx-search-' + scheme + '.png' });
    await pg.click('#tx-results button >> nth=0'); await pg.waitForTimeout(300);
    console.log('cur seg', await pg.$$eval('#txd-segs .seg.now', x => x.map(e => e.textContent)));
    assert.ok((await pg.locator('#txd-segs .seg.now').count()) > 0, 'Een zoekresultaat opent de bijbehorende zin');
    // Native levert een leeg transcript als er alleen stilte of een losse aftiteling overblijft.
    await pg.evaluate(() => {
      window.txGetOriginal = Android.txGet;
      Android.txGet = id => { const t = JSON.parse(window.txGetOriginal.call(Android, id)); t.segments = []; t.text = ''; return JSON.stringify(t); };
      txOpen('a1');
    });
    assert.equal(await pg.locator('#txd-segs .seg').count(), 0);
    assert.match(await pg.textContent('#txd-segs'), /Er is geen spraak herkend/);
    assert.match(await pg.textContent('#txd-segs'), /Speel de opname af/);
    assert.match(await pg.textContent('#txd-segs'), /Nauwkeurig en de juiste taal/);
    await pg.click('#txd-play');
    assert.equal(await pg.evaluate(() => Android._tx.playing), true, 'De originele opname blijft afspeelbaar');
    await pg.click('#s-txd button:has-text("Opnieuw uitschrijven")');
    assert.equal(await pg.locator('#modal-title').textContent(), 'Opnieuw uitschrijven?');
    await pg.click('#modal-ok');
    assert.equal(await pg.evaluate(() => Android._tx.st.running), true, 'Opnieuw uitschrijven blijft mogelijk');
    await pg.evaluate(() => { Android.txCancel(); Android.txGet = window.txGetOriginal; txOpen('a1'); });
    await pg.click('#s-txd .back'); await pg.click('#s-transcripts .back');
    assert.deepEqual(errs, [], 'Geen JavaScript-fouten');
    console.log('screen', await pg.evaluate(() => document.querySelector('.screen.on').id), 'errors', errs);
  }
  await b.close();
})();
