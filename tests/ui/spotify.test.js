const { chromium } = require('playwright');
// Spotify-backup: koppelen, backup, playlists, versies en terugzetten.
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.click('#tile-spotify'); await pg.waitForTimeout(300);
    console.log('setup', await pg.isVisible('#sp-setup'), 'main hidden', !(await pg.isVisible('#sp-main')));
    await pg.screenshot({ path: __dirname + '/shots/sp-setup-' + scheme + '.png', fullPage: true });
    await pg.click('#sp-setup button:has-text("Kopiëren")'); console.log('copied', await pg.evaluate(() => Android._spCopied));
    await pg.fill('#sp-client', 'nee'); await pg.click('#sp-link'); console.log('bad id', await pg.textContent('#sp-link-msg'));
    await pg.fill('#sp-client', '0123456789abcdef0123456789ABCDEF'); await pg.click('#sp-link'); await pg.waitForTimeout(900);
    console.log('linked', await pg.isVisible('#sp-main'), 'user', await pg.textContent('#sp-user'), 'lists', await pg.isVisible('#sp-lists'));
    console.log('own', await pg.$$eval('#sp-own .srow b', x => x.map(e => e.textContent)), 'fol', await pg.$$eval('#sp-fol .srow b', x => x.map(e => e.textContent)), 'gone', await pg.$$eval('#sp-gone .srow b', x => x.map(e => e.textContent)));
    console.log('last', await pg.textContent('#sp-last'));
    await pg.screenshot({ path: __dirname + '/shots/sp-main-' + scheme + '.png', fullPage: true });
    // Versies van Roadtrip → oudere versie → als nieuwe terugzetten
    await pg.click('#sp-own .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(150);
    console.log('versions', await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent)));
    await pg.click('#sheet-acts button:nth-child(3)'); await pg.waitForTimeout(150);
    console.log('version acts', await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent)));
    await pg.click('#sheet-acts button:has-text("als nieuwe playlist")'); await pg.waitForTimeout(400);
    console.log('restored', JSON.stringify(await pg.evaluate(() => Android._spRestored)), 'toast', await pg.textContent('#toast'));
    await pg.waitForTimeout(400); console.log('after', await pg.textContent('#sheet-title'), await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent)));
    await pg.evaluate(() => closeSheet());
    // Overschrijven vraagt eerst
    await pg.click('#sp-own .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:nth-child(2)'); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:has-text("precies zo")'); await pg.waitForTimeout(150);
    console.log('confirm', await pg.textContent('#modal-title')); await pg.click('#modal-ok'); await pg.waitForTimeout(300);
    console.log('replace', JSON.stringify(await pg.evaluate(() => Android._spRestored)));
    await pg.waitForTimeout(500); await pg.evaluate(() => closeSheet());
    // Gevolgde playlist: alleen openen
    await pg.click('#sp-fol .srow .sbtn'); await pg.waitForTimeout(100);
    console.log('followed acts', await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent)));
    await pg.evaluate(() => closeSheet());
    await pg.selectOption('#sp-auto', 'week'); console.log('auto', await pg.evaluate(() => Android._sp.auto));
    await pg.click('#sp-log-wrap summary'); console.log('log', (await pg.textContent('#sp-log')).trim().split('\n')[1]);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    console.log('back', await pg.evaluate(() => current), 'errors', errs);
    await pg.close();
  }
  await b.close();
})();
