// Spotify-backup: bibliotheek (leuk gevonden nummers, artiesten, …), opnieuw koppelen, verdwenen terugzetten, lange foutmelding
const { chromium } = require('playwright');
const assert = (ok, what) => { if (!ok) throw new Error('mislukt: ' + what); console.log('✓ ' + what); };
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const vis = sel => pg.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
    await pg.evaluate(() => { Android._spRelink = true; });
    await pg.click('#tile-spotify'); await pg.waitForTimeout(300);
    await pg.fill('#sp-client', '0123456789abcdef0123456789abcdef'); await pg.click('#sp-link'); await pg.waitForTimeout(1500);
    assert(await vis('#sp-relink'), 'oude koppeling: vraagt opnieuw koppelen voor de bibliotheek');
    assert(await vis('#sp-libwrap') && (await pg.$$('#sp-lib .srow')).length === 7, 'bibliotheek met 7 soorten (zonder lege luisterboeken)');
    const t = await pg.textContent('#sp-lib');
    assert(t.includes('812 nummers') && t.includes('2 uit Spotify verdwenen') && t.includes('64 artiesten'), 'aantallen en verdwenen');
    await pg.screenshot({ path: __dirname + '/shots/spotify-lib-' + scheme + '.png', fullPage: true });
    await pg.click('#sp-lib .srow >> nth=0'); await pg.waitForTimeout(150);
    const acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    assert(acts.some(a => a.includes('Verdwenen terugzetten')) && acts.some(a => a.includes('Alles terugzetten')), 'menu: ' + acts.join(' | '));
    await pg.click('#sheet-acts button:has-text("Verdwenen terugzetten")'); await pg.waitForTimeout(300);
    assert((await pg.evaluate(() => Android._spLibRestored || [])).join() === 'tracks,gone' && (await pg.textContent('#toast')).includes('2 teruggezet'), 'verdwenen teruggezet');
    await pg.click('#sp-lib .srow >> nth=6'); await pg.waitForTimeout(150);
    const acts2 = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    assert(acts2.length === 1 && acts2[0] === 'Bekijken', 'laatst geluisterd: alleen bekijken');
    await pg.click('#sheet-acts button:has-text("Bekijken")'); await pg.waitForTimeout(150);
    assert((await pg.textContent('#modal-text')).includes('Zoutelande – BLØF · 2026-10-13'), 'luistergeschiedenis met datum');
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    // lange foutmelding blijft binnen 4 regels
    await pg.evaluate(() => { Android._sp.lastFailAt = Date.now() + 1000; Android._sp.lastMsg = 'x'.repeat(5000); spRender(); });
    const h = await pg.evaluate(() => document.querySelector('#sp-last').getBoundingClientRect().height);
    assert(h < 160, 'lange foutmelding afgekapt (' + Math.round(h) + ' px)');
    console.log('errors', errs);
    await pg.close();
  }
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
