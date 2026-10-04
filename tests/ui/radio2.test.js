const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.click('#tile-radio'); await pg.waitForTimeout(400);
    await pg.click('#rd-list .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(1800);
    console.log('position', await pg.evaluate(() => getComputedStyle(document.querySelector('#rd-now')).position));
    console.log('song', await pg.textContent('#rd-song b'), '|', await pg.textContent('#rd-song span'), '| sub', await pg.textContent('#rd-now-title'));
    await pg.click('#rd-info summary'); await pg.click('#rd-recent-wrap summary'); await pg.waitForTimeout(150);
    console.log('info', await pg.$$eval('#rd-info-dl dt', x => x.map(e => e.textContent)), 'recent', await pg.$$eval('#rd-recent li', x => x.length));
    await pg.selectOption('#rd-sleep', 'custom'); await pg.waitForTimeout(150);
    await pg.fill('#modal-input', '42'); await pg.click('#modal-ok'); await pg.waitForTimeout(1300);
    console.log('sleep', await pg.textContent('#rd-sleepinfo'));
    await pg.selectOption('#rd-sleep', 'at'); await pg.waitForTimeout(150);
    await pg.fill('#modal-input', '23:59'); await pg.click('#modal-ok'); await pg.waitForTimeout(1300);
    console.log('sleep at', await pg.textContent('#rd-sleepinfo'));
    await pg.screenshot({ path: __dirname + '/shots/rd2-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => window.scrollTo(0, 500)); await pg.waitForTimeout(200);
    await pg.screenshot({ path: __dirname + '/shots/rd2-scroll-' + scheme + '.png' });
    await pg.click('#rd-sleepinfo a'); await pg.waitForTimeout(1300);
    console.log('sleep off', JSON.stringify(await pg.textContent('#rd-sleepinfo')), 'errors', errs);
  }
  await b.close();
})();
