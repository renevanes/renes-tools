// Auto redial: de knoppen met recente nummers (#recent). Ze verdwenen ooit doordat een andere functie dezelfde naam
// (renderRecent) kreeg; dubbele-namen.test.js bewaakt dat in het algemeen, deze test het gedrag zelf.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m + ' (' + scheme + ')'); else console.log('✓ ' + m); };
    await pg.addInitScript(() => { localStorage.setItem('rt.recent', JSON.stringify([{ number: '0612345678', name: 'Tandarts' }, { number: '010 1234567', name: '' }])); });
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const knoppen = () => pg.$$eval('#recent button', x => x.map(e => e.textContent));
    await pg.evaluate(() => show('redial')); await pg.waitForTimeout(150);
    ok((await knoppen()).join('|') === 'Tandarts|010 1234567', 'bij openen: de recente nummers als knoppen (' + (await knoppen()).join(', ') + ')');
    await pg.click('#recent button:has-text("Tandarts")');
    ok((await pg.inputValue('#num')) === '0612345678' && (await pg.textContent('#pname')) === 'Tandarts', 'tik vult het nummer en de naam in');
    await pg.evaluate(() => remember('020 7654321', 'Huisarts')); await pg.waitForTimeout(50);
    ok((await knoppen()).join('|') === 'Huisarts|Tandarts|010 1234567', 'na remember(): nieuw nummer vooraan (' + (await knoppen()).join(', ') + ')');
    await pg.evaluate(() => remember('06-12345678', 'Tandarts')); await pg.waitForTimeout(50);
    ok((await knoppen()).join('|') === 'Tandarts|Huisarts|010 1234567', 'zelfde nummer (andere schrijfwijze) niet dubbel');
    // Het startscherm (Recent gebruikt) laat de lijst van Auto redial met rust
    await pg.evaluate(() => { show('home'); show('redial'); }); await pg.waitForTimeout(100);
    ok((await knoppen()).length === 3 && (await pg.$$('#home-recent .hrtool')).length >= 1, 'na het startscherm nog steeds de recente nummers');
    console.log('errors', errs);
    await pg.close();
  }
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
