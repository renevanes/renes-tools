// Startscherm: een groep klap je in en uit met een tik op de kop; dat blijft zo na opnieuw openen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(e.message));
    const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
    await pg.goto(url); await pg.waitForTimeout(300);
    const stand = () => pg.evaluate(() => [...document.querySelectorAll('#home-groups .hgroup')].map(g => ({ naam: g.querySelector('h3').dataset.g, open: g.querySelector('h3').getAttribute('aria-expanded'), tegels: [...g.querySelectorAll('.tile')].filter(t => t.getClientRects().length).length, h: Math.round(g.querySelector('h3').getBoundingClientRect().height) })));
    let s = await stand();
    ok(s.length >= 4 && s.every(g => g.open === 'true' && g.tegels > 0 && g.h >= 44), scheme + ': alle groepen open, kop is een knop van minstens 44 px (' + JSON.stringify(s.map(g => g.h)) + ')');
    const hoog = await pg.evaluate(() => document.documentElement.scrollHeight), eerste = s[0].naam, tweede = s[1].naam;
    await pg.click('#home-groups .hgroup h3 >> nth=0'); await pg.waitForTimeout(120);
    s = await stand();
    ok(s[0].open === 'false' && s[0].tegels === 0 && s[1].open === 'true' && s[1].tegels > 0, scheme + ': eerste groep ingeklapt, de rest niet');
    ok(await pg.evaluate(() => document.documentElement.scrollHeight) < hoog - 100, scheme + ': het startscherm is korter');
    ok(await pg.evaluate(n => document.activeElement && document.activeElement.dataset.g === n, eerste), scheme + ': de focus blijft op de kop');
    await pg.evaluate(() => { const h = document.querySelectorAll('#home-groups .hgroup h3')[1]; h.focus(); h.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); }); await pg.waitForTimeout(120);
    s = await stand();
    ok(s[1].open === 'false' && s[1].tegels === 0, scheme + ': inklappen met het toetsenbord (Enter)');
    await pg.reload(); await pg.waitForTimeout(300);
    s = await stand();
    ok(s[0].open === 'false' && s[1].open === 'false' && s[2].open === 'true', scheme + ': onthouden na opnieuw openen');
    ok(await pg.evaluate(() => { show('contacts'); return current === 'contacts'; }), scheme + ': een tool in een ingeklapte groep opent gewoon (zoeken, snelkoppeling)');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    await pg.click('#home-groups .hgroup h3 >> nth=0'); await pg.waitForTimeout(120);
    s = await stand();
    ok(s[0].open === 'true' && s[0].tegels > 0 && s[0].naam === eerste && s[1].naam === tweede, scheme + ': weer uitklappen');
    // Compact en zonder groepen: geen koppen om in te klappen, niets verborgen
    await pg.evaluate(() => { const c = homeCfg(); c.grouped = false; homeSave(c); }); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !document.querySelector('#home-groups .hgroup.dicht') && document.querySelectorAll('#home-groups .tile').length > 20), scheme + ': zonder groepen staat alles er');
    await pg.screenshot({ path: __dirname + '/shots/home-inklappen-' + scheme + '.png' });
    await ctx.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
