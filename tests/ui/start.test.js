// Telefoon-skin (start.html) met de nagebootste brug.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, hasTouch: true, isMobile: true });
    const pg = await ctx.newPage();
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, '..', '..', 'web', 'start.html')); await pg.waitForTimeout(500);
    await pg.evaluate(() => { document.body.style.background = 'linear-gradient(160deg,#1e3a8a,#db2777)'; });
    console.log('clock', /\d\d:\d\d/.test(await pg.textContent('#time')), '| date', await pg.textContent('#date'), '| alarm', (await pg.textContent('#alarm')).slice(0, 2));
    console.log('weather', (await pg.textContent('#weather')).replace(/\s+/g, ' ').slice(0, 60));
    console.log('cal rows', await pg.$$eval('#cal .ev', x => x.length), '| tools rows', await pg.$$eval('#tools .trow', x => x.map(e => e.textContent)));
    console.log('dock', await pg.$$eval('#dock .app', x => x.map(e => e.getAttribute('aria-label'))));
    console.log('banner', await pg.isVisible('#banner'));
    await pg.screenshot({ path: __dirname + '/shots/start-home-' + scheme + '.png' });
    // radio vanaf het startscherm
    await pg.click('#tools .rb.main'); await pg.waitForTimeout(400);
    console.log('radio', await pg.evaluate(() => Android._rw), (await pg.textContent('#tools .radio')).replace(/\s+/g, ' '));
    // app-overzicht openen (vegen omhoog nabootsen via de hint)
    await pg.click('#hint'); await pg.waitForTimeout(400);
    console.log('drawer open', await pg.evaluate(() => document.body.classList.contains('drawer-on')), 'apps', await pg.$$eval('#dlist .app', x => x.length));
    await pg.screenshot({ path: __dirname + '/shots/start-drawer-' + scheme + '.png' });
    await pg.fill('#q', 'whats'); await pg.waitForTimeout(100);
    console.log('search', await pg.$$eval('#dlist .app', x => x.map(e => e.getAttribute('aria-label'))));
    await pg.press('#q', 'Enter'); console.log('launched', await pg.evaluate(() => Android._launched.slice(-1)[0]));
    await pg.fill('#q', 'pizza bestellen'); await pg.press('#q', 'Enter'); console.log('websearch', await pg.evaluate(() => Android._ws));
    await pg.fill('#q', '');  await pg.evaluate(() => renderDrawer());
    // lang indrukken op Spotify → op het startscherm
    const sp = await pg.$('#dlist .app[aria-label="Spotify"]'); await sp.scrollIntoViewIfNeeded(); const bb = await sp.boundingBox();
    await pg.mouse.move(bb.x + 20, bb.y + 20); await pg.mouse.down(); await pg.waitForTimeout(650); await pg.mouse.up(); await pg.waitForTimeout(100);
    console.log('sheet', await pg.textContent('#shd'), await pg.$$eval('#sacts button', x => x.map(e => e.textContent)));
    await pg.click('#sacts button:has-text("Op het startscherm zetten")'); await pg.waitForTimeout(100);
    // map maken met NS
    const ns = await pg.$('#dlist .app[aria-label="NS"]'); await ns.scrollIntoViewIfNeeded(); const nb = await ns.boundingBox();
    await pg.mouse.move(nb.x + 20, nb.y + 20); await pg.mouse.down(); await pg.waitForTimeout(650); await pg.mouse.up(); await pg.waitForTimeout(100);
    await pg.click('#sacts button:has-text("In een map")'); await pg.waitForTimeout(100);
    await pg.fill('#askin', 'Reizen'); await pg.click('#askok'); await pg.waitForTimeout(150);
    console.log('folders', await pg.evaluate(() => JSON.stringify(cfg.folders)), 'folder tile', await pg.$$eval('#dlist .app.fold', x => x.map(e => e.getAttribute('aria-label'))));
    // verbergen
    const yt = await pg.$('#dlist .app[aria-label="YouTube"]'); await yt.scrollIntoViewIfNeeded(); const yb = await yt.boundingBox();
    await pg.mouse.move(yb.x + 20, yb.y + 20); await pg.mouse.down(); await pg.waitForTimeout(650); await pg.mouse.up(); await pg.waitForTimeout(100);
    await pg.click('#sacts button:has-text("Verbergen")'); await pg.waitForTimeout(100);
    console.log('hidden', await pg.evaluate(() => cfg.hidden), 'still in drawer', !!(await pg.$('#dlist .app[aria-label="YouTube"]')));
    await pg.evaluate(() => onBack()); await pg.waitForTimeout(350);
    console.log('pinned', await pg.$$eval('#pinned .app', x => x.map(e => e.getAttribute('aria-label'))), 'drawer closed', !(await pg.evaluate(() => document.body.classList.contains('drawer-on'))));
    // tikken op app op het startscherm
    await pg.click('#pinned .app'); console.log('launched pinned', await pg.evaluate(() => Android._launched.slice(-1)[0]));
    // look aanpassen
    await pg.evaluate(() => openLook()); await pg.waitForTimeout(150);
    await pg.click('#l-bg button[data-b="g2"]'); await pg.click('#l-acc button[data-c="#10b981"]'); await pg.click('#l-ico button[data-v="l"]'); await pg.click('#l-cols button[data-v="5"]');
    await pg.click('#l-order button.tg[data-k="cal"]'); await pg.waitForTimeout(100);
    console.log('look', await pg.evaluate(() => [cfg.bg, cfg.accent, cfg.icon, cfg.cols, cfg.show.cal].join(',')), 'hidden list', await pg.$$eval('#l-hidden .hid span', x => x.map(e => e.textContent)));
    await pg.screenshot({ path: __dirname + '/shots/start-look-' + scheme + '.png', fullPage: true });
    await pg.click('#l-hidden button'); console.log('unhidden', await pg.evaluate(() => cfg.hidden.length === 0));
    await pg.evaluate(() => onHomePressed()); await pg.waitForTimeout(200);
    console.log('cal hidden', !(await pg.isVisible('#cal')), 'saved', await pg.evaluate(() => JSON.parse(Android._cfg).bg));
    // weerplaats kiezen
    await pg.evaluate(() => askPlace()); await pg.fill('#askin', 'Rotterdam'); await pg.click('#askok'); await pg.waitForTimeout(200);
    console.log('cities', await pg.$$eval('#sacts button', x => x.length)); await pg.click('#sacts button >> nth=0'); console.log('place', await pg.evaluate(() => Android._place));
    await pg.screenshot({ path: __dirname + '/shots/start-home2-' + scheme + '.png' });
    const ov = await pg.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
    console.log('no overflow', !ov);
    console.log('errors', errs);
    await ctx.close();
  }
  await b.close();
})();
