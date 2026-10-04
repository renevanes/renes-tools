const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
  await pg.click('#tile-tracks'); await pg.waitForTimeout(300);
  console.log('trStart is tracks:', await pg.evaluate(() => !String(trStart).includes('Android.tx')), 'txStart exists:', await pg.evaluate(() => typeof txStart));
  console.log('tr-home visible:', await pg.evaluate(() => getComputedStyle(document.querySelector('#s-tracks')).display));
  console.log('errors', errs);
  await b.close();
})();
