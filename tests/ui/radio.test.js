const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    console.log('soon tile gone:', await pg.$$eval('.tile.soon', x => x.length) === 0);
    await pg.screenshot({ path: __dirname + '/shots/rd-home-' + scheme + '.png', fullPage: true });
    await pg.click('#tile-radio'); await pg.waitForTimeout(400);
    console.log('stations', await pg.$$eval('#rd-list .srow', x => x.length));
    await pg.click('#rd-list .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(1300);
    console.log('now', await pg.textContent('#plbar-t'), '|', await pg.textContent('#plbar-s'), '|', await pg.getAttribute('#plbar-pp', 'aria-label'));
    await pg.click('#rd-list .srow:nth-child(2) .star'); await pg.click('#rd-list .srow:nth-child(3) .star'); await pg.waitForTimeout(200);
    await pg.click('#plbar-open'); await pg.waitForTimeout(400);
    console.log('favs', await pg.$$eval('#rd-favs .srow', x => x.length), 'now star on', await pg.evaluate(() => $('#pl-fav').classList.contains('on')));
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(350);
    await pg.screenshot({ path: __dirname + '/shots/rd-play-' + scheme + '.png', fullPage: true });
    await pg.fill('#rd-search', 'sky'); await pg.waitForTimeout(800);
    console.log('search', await pg.$$eval('#rd-list .srow', x => x.map(e => e.innerText.split('\n')[0])));
    await pg.click('#plbar-pp'); await pg.waitForTimeout(1200);
    console.log('after pause', await pg.getAttribute('#plbar-pp', 'aria-label'), await pg.textContent('#plbar-s'));
    await pg.click('#plbar-pp'); await pg.waitForTimeout(1200);
    // recognize from radio without token -> goes to music setup (vanuit de speler)
    await pg.click('#plbar-open'); await pg.waitForTimeout(400);
    await pg.click('#rd-rec'); await pg.waitForTimeout(300);
    console.log('screen', await pg.evaluate(() => document.querySelector('.screen.on').id), 'setup visible', await pg.isVisible('#mu-setup'));
    await pg.screenshot({ path: __dirname + '/shots/mu-setup-' + scheme + '.png' });
    await pg.fill('#mu-token', 'abcdef1234567890'); await pg.click('#mu-setup button.big:not(.ghost)'); await pg.waitForTimeout(200);
    await pg.click('#mu-btn'); await pg.waitForTimeout(300); // mic permission -> starts
    console.log('state', await pg.textContent('#mu-state'));
    await pg.waitForTimeout(1600);
    console.log('result', await pg.$$eval('.mucard b', x => x.map(e => e.textContent)), 'hist', await pg.$$eval('#mu-hist button', x => x.length));
    await pg.screenshot({ path: __dirname + '/shots/mu-result-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    console.log('back ->', await pg.evaluate(() => document.querySelector('.screen.on').id));
    await pg.click('#plbar-open'); await pg.waitForTimeout(400);
    await pg.click('#rd-rec'); await pg.waitForTimeout(1600);
    console.log('radio-recognized hist', await pg.$$eval('#mu-hist button', x => x.map(e => e.innerText.replace(/\n/g, ' | '))));
    await pg.evaluate(() => goBack()); await pg.evaluate(() => goBack()); await pg.waitForTimeout(300);
    console.log('tile', await pg.textContent('#tile-radio-sub'), 'errors', errs);
  }
  await b.close();
})();
