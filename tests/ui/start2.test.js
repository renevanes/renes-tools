// Telefoon-skin aanpassen: apps kiezen voor startscherm/dock/overzicht, volgorde, klok, kaarten, terugzetten.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, hasTouch: true, isMobile: true });
    const pg = await ctx.newPage();
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, '..', '..', 'web', 'start.html')); await pg.waitForTimeout(500);
    await pg.evaluate(() => openLook()); await pg.waitForTimeout(100);
    console.log('counts', await pg.textContent('#l-n-pinned'), await pg.textContent('#l-n-dock'), await pg.textContent('#l-n-drawer'));
    // startscherm: 3 apps kiezen en de volgorde wijzigen
    await pg.click('.pickrow:has-text("Op het startscherm")'); await pg.waitForTimeout(100);
    for (const n of ['Spotify', 'NS', 'Gmail']) await pg.click('#pk-list button.pk:has(.nm:text-is("' + n + '"))');
    await pg.fill('#pk-q', 'buien'); await pg.waitForTimeout(50);
    console.log('search in picker', await pg.$$eval('#pk-list button.pk .nm', x => x.map(e => e.textContent)));
    await pg.click('#pk-list button.pk:has-text("Buienradar")'); await pg.fill('#pk-q', '');
    await pg.click('#pk-list .pk .mini2[data-i="3"][data-d="-1"]'); // Buienradar een plek naar voren
    console.log('chosen', await pg.$$eval('#pk-list div.pk .nm', x => x.map(e => e.textContent)));
    await pg.screenshot({ path: __dirname + '/shots/start-pick-' + scheme + '.png' });
    await pg.click('#pick .done'); await pg.waitForTimeout(100);
    console.log('pinned', await pg.$$eval('#pinned .app', x => x.map(e => e.getAttribute('aria-label'))));
    // dock: maximaal 6
    await pg.click('.pickrow:has-text("dock")'); await pg.waitForTimeout(100);
    for (const n of ['Maps', 'YouTube']) await pg.click('#pk-list button.pk:has-text("' + n + '")');
    console.log('dock max toast', await pg.textContent('#toast'));
    await pg.click('#pk-list div.pk .mini2[aria-label="Weghalen"]'); // eerste weg
    await pg.click('#pick .done'); await pg.waitForTimeout(100);
    console.log('dock', await pg.$$eval('#dock .app', x => x.map(e => e.getAttribute('aria-label'))));
    // zichtbaar bij alle apps: twee uitvinken
    await pg.click('.pickrow:has-text("Zichtbaar")'); await pg.waitForTimeout(100);
    await pg.click('#pk-list button.pk:has-text("Teams")'); await pg.click('#pk-list button.pk:has-text("Outlook")');
    await pg.click('#pick .done'); await pg.waitForTimeout(100);
    console.log('hidden', await pg.evaluate(() => cfg.hidden.map(k => byKey[k].n)), 'n', await pg.textContent('#l-n-drawer'));
    // annuleren verandert niets
    await pg.click('.pickrow:has-text("Op het startscherm")'); await pg.click('#pk-list button.pk:has-text("Chrome")'); await pg.click('#pick .ib');
    console.log('cancel kept', await pg.evaluate(() => cfg.pinned.length));
    // volgorde: apps bovenaan, agenda uit
    for (let i = 0; i < 4; i++) await pg.click('#l-order .ordrow:has-text("Apps op het startscherm") .mini2[aria-label="Hoger"]');
    await pg.click('#l-order button.tg[data-k="cal"]');
    await pg.click('#l-clk button[data-v="small"]'); await pg.click('#l-sw-date');
    await pg.click('#l-rad button[data-v="square"]'); await pg.fill('#l-glass', '45'); await pg.dispatchEvent('#l-glass', 'input');
    await pg.click('#l-sort button[data-v="used"]');
    console.log('order', await pg.evaluate(() => cfg.order.join(',')), 'clock', await pg.evaluate(() => JSON.stringify(cfg.clock)), 'glass', await pg.evaluate(() => cfg.glass + ' ' + cfg.rad + ' ' + cfg.sort));
    await pg.evaluate(() => closeLook()); await pg.waitForTimeout(150);
    console.log('dom order', await pg.evaluate(() => [...document.querySelectorAll('#home > *')].filter(e => getComputedStyle(e).display !== 'none').map(e => e.id).join(',')));
    console.log('date hidden', !(await pg.isVisible('#date')), 'clock px', await pg.evaluate(() => getComputedStyle($('#time')).fontSize));
    await pg.screenshot({ path: __dirname + '/shots/start-custom-' + scheme + '.png' });
    // meest gebruikt
    await pg.evaluate(() => { launch('com.oplus.note/.Main'); launch('com.oplus.note/.Main'); launch('nl.ns/.Main'); openDrawer(); });
    console.log('used first', await pg.$$eval('#dlist .app', x => x.slice(0, 2).map(e => e.getAttribute('aria-label'))));
    await pg.evaluate(() => closeDrawer());
    // terugzetten
    await pg.evaluate(() => { openLook(); resetLook(); }); await pg.click('#sacts button:has-text("Look én indeling")'); await pg.waitForTimeout(100);
    console.log('reset', await pg.evaluate(() => [cfg.pinned.length, cfg.hidden.length, cfg.order.join(','), cfg.clock.size, cfg.rad].join(' | ')));
    console.log('saved', await pg.evaluate(() => JSON.parse(Android._cfg).order.length === 5));
    console.log('errors', errs);
    await ctx.close();
  }
  await b.close();
})();
