const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
  const groups = () => pg.$$eval('#gs-res h3', x => x.map(e => e.innerText.split('\n')[0].trim()));
  await pg.fill('#gs-q', 'vingerafdruk'); await pg.waitForTimeout(700);
  console.log('vinger groups', await groups());
  console.log('app items', await pg.$$eval('#gs-res .gi[data-k="app"]', x => x.map(e => e.innerText.replace(/\n/g, ' | '))));
  await pg.click('#gs-res .gi[data-k="app"]'); await pg.waitForTimeout(400);
  console.log('opened', await pg.evaluate(() => current), 'flash', await pg.$$eval('.card.gsflash', x => x.length));
  await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
  for (const q of ['donker', 'back-up', 'meldingen', 'radio', 'dark mode']) {
    await pg.fill('#gs-q', q); await pg.waitForTimeout(600);
    console.log(q, await pg.$$eval('#gs-res .gi[data-k="app"]', x => x.map(e => e.innerText.split('\n')[0])));
  }
  await pg.fill('#gs-q', 'whatsapp'); await pg.waitForTimeout(700);
  console.log('whatsapp groups', await groups());
  const h = await pg.$('#gs-res .gi[data-k="history"]');
  if (h) { await h.click(); await pg.waitForTimeout(400); console.log('history opened', await pg.evaluate(() => current)); }
  console.log('errors', errs);
  await b.close();
})();
