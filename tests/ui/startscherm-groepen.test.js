// Startscherm aanpassen: groepen in een andere volgorde, tools toevoegen aan een groep, verplaatsen tussen groepen
// (menu, slepen, en vanaf een tegel), groep weghalen met tools, favorieten slepen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); };
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const home = () => pg.$$eval('#home-groups .hgroup', gs => gs.map(g => ((g.querySelector('h3') || {}).textContent || '') + ':' + [...g.querySelectorAll('.tiles > *')].map(t => t.id.replace('tile-', '')).join(',')));
    const cfg = () => pg.evaluate(() => homeCfg());
    const grp = async name => ((await cfg()).groups.find(g => g.name === name) || {}).tools || [];
    await pg.evaluate(() => show('homeedit')); await pg.waitForTimeout(200);

    // Groepen in een andere volgorde: "Muziek & radio" omhoog, dan bovenaan
    await pg.click('#he-groups button[aria-label="Groep Muziek & radio omhoog"]'); await pg.waitForTimeout(100);
    let g = await home();
    ok(g.findIndex(x => x.startsWith('Muziek & radio')) < g.findIndex(x => x.startsWith('Berichten & backup')), 'groep omhoog, ook op het startscherm (' + g.map(x => x.split(':')[0]) + ')');
    await pg.click('#he-groups .hegroup:has(.hegname:has-text("Muziek & radio")) .heghead button.more'); await pg.click('#sheet-acts button:has-text("Bovenaan zetten")'); await pg.waitForTimeout(100);
    g = await home();
    ok(g[0].startsWith('Muziek & radio'), 'groep bovenaan (' + g[0] + ')');

    // Tool toevoegen aan een groep (haalt hem uit zijn oude groep), en het menu blijft open voor nog een
    await pg.click('#he-groups .hegroup:has(.hegname:has-text("Muziek & radio")) .headd'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#sheet-title')).includes('Toevoegen aan Muziek & radio'), 'toevoegen-menu');
    await pg.click('#sheet-acts button:has-text("Slaappraat (uit Handig)")'); await pg.waitForTimeout(400);
    ok((await grp('Muziek & radio')).includes('sleep') && !(await grp('Handig')).includes('sleep'), 'Slaappraat naar Muziek & radio');
    ok(await pg.isVisible('#sheet.on') && (await pg.textContent('#sheet-title')).includes('Toevoegen aan'), 'menu weer open voor nog een');
    await pg.click('#sheet-acts button:has-text("Klaar")'); await pg.waitForTimeout(100);

    // Via ⋯ naar een andere groep
    await pg.click('#he-groups button.more[data-t="pdf"]'); await pg.click('#sheet-acts button:has-text("Naar een andere groep")'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Berichten & backup")'); await pg.waitForTimeout(100);
    ok((await grp('Berichten & backup')).includes('pdf') && (await pg.textContent('#toast')).includes('PDF'), 'PDF via het menu naar Berichten & backup');

    // Nieuwe groep vanuit het verplaatsen-menu
    await pg.click('#he-groups button.more[data-t="kluis"]'); await pg.click('#sheet-acts button:has-text("Naar een andere groep")'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Nieuwe groep")'); await pg.fill('#modal-input', 'Privé'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok((await grp('Privé')).join() === 'kluis', 'nieuwe groep met de kluis');

    // Slepen naar een andere groep: Radio (Muziek & radio) naar boven de eerste tool van Bellen & contacten
    // Midden in beeld (niet onder de kop): Muziek & radio staat bovenaan, Bellen & contacten twee groepen lager
    await pg.evaluate(() => document.querySelector('#he-groups .herow[data-t="radio"]').scrollIntoView({ block: 'center' })); await pg.waitForTimeout(100);
    const h = await pg.$('#he-groups .herow[data-t="radio"] .hedrag');
    const hb = await h.boundingBox();
    await pg.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2); await pg.mouse.down();
    // Naar de onderrand: de lijst schuift vanzelf mee tot Bellen & contacten in beeld is
    for (let i = 1; i <= 8; i++) { await pg.mouse.move(hb.x + hb.width / 2, hb.y + (825 - hb.y) * i / 8); await pg.waitForTimeout(16); }
    for (let i = 0; i < 100; i++) { const r = await (await pg.$('#he-groups .herow[data-t="redial"]')).boundingBox(); if (r.y < 600) break; await pg.waitForTimeout(50); }
    const rb = await (await pg.$('#he-groups .herow[data-t="redial"]')).boundingBox();
    for (let i = 1; i <= 6; i++) { await pg.mouse.move(hb.x + hb.width / 2, 825 + (rb.y + 6 - 825) * i / 6); await pg.waitForTimeout(16); }
    await pg.screenshot({ path: __dirname + '/shots/startscherm-slepen-' + scheme + '.png' });
    await pg.mouse.up(); await pg.waitForTimeout(150);
    const bc = await grp('Bellen & contacten');
    ok(bc[0] === 'radio' && !(await grp('Muziek & radio')).includes('radio'), 'Radio gesleept naar Bellen & contacten (' + bc + ')');
    ok((await home()).some(x => x.startsWith('Bellen & contacten:radio,redial')), 'ook zo op het startscherm');

    // Groep met tools weghalen: de tools gaan naar de groep erboven
    const before = (await cfg()).groups.map(x => x.name);
    const ix = before.indexOf('Privé'), up = before[ix - 1];
    await pg.click('#he-groups .hegroup:has(.hegname:has-text("Privé")) .heghead button.more'); await pg.click('#sheet-acts button:has-text("Groep weghalen")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#modal')).includes('naar ' + up), 'uitleg waar de tools heen gaan');
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok(!(await cfg()).groups.some(x => x.name === 'Privé') && (await grp(up)).includes('kluis'), 'groep weg, kluis naar ' + up);

    // Favorieten slepen
    await pg.evaluate(() => { toggleFav('notes'); toggleFav('podcasts'); toggleFav('vpn'); }); await pg.waitForTimeout(100);
    await pg.evaluate(() => document.querySelector('#he-fav').scrollIntoView({ block: 'center' })); await pg.waitForTimeout(100);
    const fh = await (await pg.$('#he-fav .herow[data-t="vpn"] .hedrag')).boundingBox(), ft = await (await pg.$('#he-fav .herow[data-t="notes"]')).boundingBox();
    await pg.mouse.move(fh.x + 10, fh.y + 10); await pg.mouse.down();
    for (let i = 1; i <= 8; i++) { await pg.mouse.move(fh.x + 10, fh.y + 10 + (ft.y + 4 - fh.y - 10) * i / 8); await pg.waitForTimeout(16); }
    await pg.mouse.up(); await pg.waitForTimeout(150);
    ok((await cfg()).fav.join() === 'vpn,notes,podcasts', 'favorieten gesleept (' + (await cfg()).fav + ')');

    // Verbergen en weer tonen via ⋯
    await pg.click('#he-groups button.more[data-t="music"]'); await pg.click('#sheet-acts button:has-text("Verbergen")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#he-groups .herow[data-t="music"]')).includes('Verborgen') && !(await home()).join().includes('music'), 'verborgen');
    await pg.click('#he-groups button.more[data-t="music"]'); await pg.click('#sheet-acts button:has-text("Tonen")'); await pg.waitForTimeout(100);
    ok((await home()).join().includes('music'), 'weer zichtbaar');
    await pg.screenshot({ path: __dirname + '/shots/startscherm-groepen-' + scheme + '.png', fullPage: true });

    // Vanaf een tegel op het startscherm naar een andere groep
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    await pg.click('#tile-car', { button: 'right' }); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:has-text("Naar een andere groep")'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Muziek & radio")'); await pg.waitForTimeout(150);
    ok((await home()).some(x => x.startsWith('Muziek & radio') && x.includes('car')), 'Mijn auto vanaf de tegel naar Muziek & radio');

    // Alleen aantikken (geen beweging): niets bewaard; geen schaduw blijft achter
    await pg.evaluate(() => { show('homeedit'); window._saves = 0; const o = store.set; store.set = function(k, v){ if (k === 'home') window._saves++; return o.apply(this, arguments); }; });
    await pg.waitForTimeout(100);
    await pg.evaluate(() => document.querySelector('#he-groups .herow[data-t="notes"]').scrollIntoView({ block: 'center' })); await pg.waitForTimeout(100);
    const nb = await (await pg.$('#he-groups .herow[data-t="notes"] .hedrag')).boundingBox();
    await pg.mouse.move(nb.x + 10, nb.y + 10); await pg.mouse.down(); await pg.waitForTimeout(80); await pg.mouse.up(); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => window._saves === 0 && !document.querySelector('.heghost')), 'aantikken: niets bewaard, geen schaduw');
    // Rechtermuisknop start geen sleep
    await pg.mouse.move(nb.x + 10, nb.y + 10); await pg.mouse.down({ button: 'right' }); await pg.mouse.move(nb.x + 10, nb.y + 80); await pg.mouse.up({ button: 'right' }); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => window._saves === 0 && !document.querySelector('.heghost')), 'rechtermuisknop: geen sleep');
    // Slepen naar een lege groep
    await pg.evaluate(() => { const c = homeCfg(); c.groups.push({ name: 'Leeg', tools: [] }); homeSave(c); enterHomeEdit(); document.querySelector('#he-groups .hegroup:last-child').scrollIntoView({ block: 'end' }); });
    await pg.waitForTimeout(150);
    const last = await pg.evaluate(() => { const rows = [...document.querySelectorAll('#he-groups .herow')]; const r = rows[rows.length - 1]; return r.dataset.t; });
    const lb = await (await pg.$('#he-groups .herow[data-t="' + last + '"] .hedrag')).boundingBox(), db = await (await pg.$('#he-groups .hedrop')).boundingBox();
    await pg.mouse.move(lb.x + 10, lb.y + 10); await pg.mouse.down();
    for (let i = 1; i <= 8; i++) { await pg.mouse.move(lb.x + 10, lb.y + 10 + (db.y + db.height / 2 - lb.y - 10) * i / 8); await pg.waitForTimeout(16); }
    await pg.mouse.up(); await pg.waitForTimeout(150);
    ok((await grp('Leeg')).join() === last, 'naar een lege groep gesleept (' + last + ')');
    // Na een actie in het menu staat de focus weer op de knop van die tool
    await pg.click('#he-groups button.more[data-t="notes"]'); await pg.click('#sheet-acts button:has-text("Omlaag")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => document.activeElement && document.activeElement.matches('button.more[data-t="notes"]')), 'focus terug op de knop van de tool');
    // De eerste groep weghalen: de tools gaan naar de groep eronder
    await pg.evaluate(() => { const c = homeCfg(); const first = c.groups[0].name, second = c.groups[1].name; window._fs = [first, second, c.groups[0].tools.slice()]; heDelGroup(0); });
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => { const c = homeCfg(); return !c.groups.some(g => g.name === window._fs[0]) && window._fs[2].every(t => c.groups[0].tools.includes(t)); }), 'eerste groep weg, tools naar de groep eronder');
    // Eén groep over: geen Groep weghalen, en Tool toevoegen zegt dat alles er al in staat
    await pg.evaluate(() => { const c = homeCfg(); c.groups = [{ name: 'Alles', tools: [].concat(...c.groups.map(g => g.tools)) }]; homeSave(c); enterHomeEdit(); });
    await pg.click('#he-groups .heghead button.more'); await pg.waitForTimeout(100);
    ok(!(await pg.textContent('#sheet-acts')).includes('Groep weghalen'), 'één groep: niet weg te halen');
    await pg.click('#sheet-acts button:has-text("Tool toevoegen")'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#toast')).includes('Alle tools staan al in deze groep'), 'één groep: alles staat er al in');
    // Zuivere hulpfuncties
    ok(await pg.evaluate(() => { const c = { groups: [{ name: 'A', tools: ['a', 'b'] }, { name: 'B', tools: ['c'] }] };
      heMoveIn(c, 'b', 1); const one = c.groups[1].tools.join() === 'b,c'; heMoveToIn(c, 'a', 1, 1); return one && c.groups[0].tools.length === 0 && c.groups[1].tools.join() === 'b,a,c'; }), 'heMoveIn / heMoveToIn');
    console.log('errors', errs); await pg.close();
  }
  await b.close();
})();
