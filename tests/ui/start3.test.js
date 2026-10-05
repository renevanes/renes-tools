// Launcher-modi van start.html: Vandaag-pagina (#today) en de laag met alle apps/menu's (#overlay).
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const b = await chromium.launch();
  const url = 'file://' + path.join(__dirname, '..', '..', 'web', 'start.html');
  for (const scheme of ['light', 'dark']) {
    // Vandaag
    let ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, hasTouch: true });
    let pg = await ctx.newPage(); let errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url + '#today'); await pg.waitForTimeout(400);
    console.log('today: mode', await pg.evaluate(() => MODE), 'dock hidden', !(await pg.isVisible('#dock')), 'pinned hidden', !(await pg.isVisible('#pinned')),
      'weather', await pg.isVisible('#weather'), 'notes', await pg.isVisible('#snotes'));
    await pg.evaluate(() => openDrawer()); console.log('today: drawer goes native', await pg.evaluate(() => Android._openDrawer === true && !document.body.classList.contains('drawer-on')));
    const pt = await pg.evaluate(() => { for (let y = 830; y > 0; y -= 10) for (const x of [380, 10, 195]) { const e = document.elementFromPoint(x, y); if (e && e.id === 'home') return [x, y]; } return [380, 830]; });
    await pg.mouse.move(pt[0], pt[1]); await pg.mouse.down(); await pg.waitForTimeout(700); await pg.mouse.up();
    console.log('today: long press → native menu', await pg.evaluate(() => Android._homeMenu === true));
    console.log('today: order without apps', await pg.evaluate(() => [...document.querySelectorAll('#home > *')].map(e => e.id).filter(x => x).join(',')));
    console.log('errors', errs); await ctx.close();
    // Overlay
    ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, hasTouch: true });
    pg = await ctx.newPage(); errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url + '#overlay'); await pg.waitForTimeout(400);
    console.log('overlay: home hidden', !(await pg.isVisible('#home')), 'dock hidden', !(await pg.isVisible('#dock')));
    await pg.evaluate(() => openDrawer()); await pg.waitForTimeout(300);
    console.log('overlay: drawer on', await pg.evaluate(() => document.body.classList.contains('drawer-on')));
    const sp = await pg.$('#dlist .app[aria-label="Spotify"]'); await sp.scrollIntoViewIfNeeded(); const bb = await sp.boundingBox();
    await pg.mouse.move(bb.x + 20, bb.y + 20); await pg.mouse.down(); await pg.waitForTimeout(650); await pg.mouse.up(); await pg.waitForTimeout(100);
    console.log('overlay: sheet', await pg.$$eval('#sacts button', x => x.map(e => e.textContent).join(' | ')));
    await pg.click('#sacts button:has-text("Op het startscherm zetten")'); await pg.click('#sacts button', { timeout: 300 }).catch(() => {});
    console.log('overlay: added natively', await pg.evaluate(() => Android._ws.pinned.includes('com.spotify.music/.Main')));
    await pg.evaluate(() => closeDrawer()); await pg.waitForTimeout(200);
    console.log('overlay: done after close', await pg.evaluate(() => Android._ovDone >= 1));
    // native menu + map hernoemen
    await pg.evaluate(() => nativeSheet({ title: 'Map Reizen', uid: '17', glyph: '📁', acts: [['Openen', 'open'], ['Naam wijzigen', 'rename']] })); await pg.waitForTimeout(100);
    await pg.click('#sacts button:has-text("Naam wijzigen")'); console.log('overlay: sheet action', await pg.evaluate(() => Android._sheet));
    await pg.evaluate(() => askFolderName({ uid: '17', name: 'Reizen' })); await pg.fill('#askin', 'Vakantie'); await pg.click('#askok');
    console.log('overlay: rename', await pg.evaluate(() => Android._ren));
    // widgetkiezer
    await pg.evaluate(() => widgetPicker(null)); console.log('overlay: picker loading', await pg.textContent('#wp-list'));
    await pg.evaluate(() => widgetPicker([{ p: 'com.x/.Clock', label: 'Klok', app: 'Klok', w: 2, h: 2, own: false }, { p: 'nl.rene.tools/.N', label: 'Notitielijst', app: "Rene's Tools", w: 2, h: 3, own: true }]));
    console.log('overlay: picker groups', await pg.$$eval('#wp-list .wapp', x => x.map(e => e.textContent).join(',')));
    await pg.screenshot({ path: __dirname + '/shots/start-wpick-' + scheme + '.png' });
    await pg.click('#wp-list .wcard:has-text("Klok")'); console.log('overlay: widget chosen', await pg.evaluate(() => Android._wadd));
    // app-kiezer via het werkblad
    await pg.evaluate(() => pickApps('dock')); await pg.click('#pk-list button.pk:has(.nm:text-is("Camera"))'); await pg.click('#pick .done');
    console.log('overlay: dock applied', await pg.evaluate(() => Android._applied));
    // look: soort startscherm
    await pg.evaluate(() => openLook()); console.log('overlay: kind', await pg.textContent('#l-kind-btn'), '| widgets btn', await pg.isVisible('#l-widgets'));
    console.log('overlay: order rows', await pg.$$eval('#l-order .ordrow .on2', x => x.map(e => e.textContent).join(',')));
    await pg.click('#l-order .ordrow:has-text("Agenda") .mini2[aria-label="Hoger"]'); console.log('overlay: order', await pg.evaluate(() => cfg.order.join(',')));
    console.log('overlay: back closes, then done', await pg.evaluate(async () => { onBack(); await new Promise(r => setTimeout(r, 120)); return !$('#look.on') && Android._ovDone >= 2; }));
    // Onzichtbare laag: tikken terwijl niets open is → weg, met het volgnummer van het werkblad
    const before = await pg.evaluate(() => { window.__ovSeq = 5; return Android._ovDone; });
    await pg.mouse.click(200, 400);
    console.log('overlay: tap on empty layer hides', await pg.evaluate(b => Android._ovDone > b && Android._ovSeq === 5, before));
    console.log('overlay: no "In een map"', !(await pg.evaluate(() => { appSheet('com.spotify.music/.Main', 'drawer'); return $('#sacts').textContent.includes('In een map'); })));
    console.log('errors', errs); await ctx.close();
  }
  // Klassieke skin: "In een map…" werkt nog (de launcher-haak heet anders)
  { const ctx = await b.newContext({ viewport: { width: 390, height: 844 } }); const pg = await ctx.newPage(); const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(400);
    await pg.evaluate(() => appSheet('com.spotify.music/.Main', 'drawer')); await pg.click('#sacts button:has-text("In een map")');
    console.log('legacy: folder ask', await pg.textContent('#asktitle'));
    await pg.fill('#askin', 'Muziek'); await pg.click('#askok');
    console.log('legacy: folder made', await pg.evaluate(() => (cfg.folders.Muziek || []).includes('com.spotify.music/.Main')));
    console.log('errors', errs); await ctx.close(); }
  await b.close();
})();
