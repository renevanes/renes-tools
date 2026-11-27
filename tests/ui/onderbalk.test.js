// Onderbalk zelf indelen: Start altijd links, daarnaast hooguit 4 plekken met een tool, Zoeken of Instellingen.
// Een tegel ingedrukt houden en naar de balk slepen (vol: vervangt de plek waar je loslaat), in de balk schuiven of
// omhoog slepen om weg te halen, loslaten zonder schuiven = menu. Ook zonder slepen: het menu van een tegel en
// Startscherm aanpassen → Weergave → Onderbalk (pijlen, toevoegen, standaard). Bewaard, ook na herladen; ongedaan maken.
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const browser = await chromium.launch();
  const W = +(process.env.UI_WACHT || 1);
  for (const scheme of ['light', 'dark']) {
    const pg = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme, hasTouch: true });
    const errors = []; pg.on('pageerror', e => errors.push(e.message));
    const ok = (c, m) => { if (!c) errors.push('mislukt: ' + m); else console.log('✓ ' + m); };
    const balk = () => pg.$$eval('#tabs button', x => x.map(b => b.dataset.t).join());
    const midden = async sel => { const l = pg.locator(sel).first(); if (!sel.startsWith('#tabs')) { await l.evaluate(e => e.scrollIntoView({ block: 'center' })); await pg.waitForTimeout(50); } const r = await l.boundingBox(); return [r.x + r.width / 2, r.y + r.height / 2]; };
    // Ingedrukt houden (muis) en slepen naar (x, y)
    const sleep = async (van, x, y) => {
      const [x0, y0] = await midden(van);
      await pg.mouse.move(x0, y0); await pg.mouse.down();
      await pg.waitForTimeout(650 * W);
      const n = 8; for (let k = 1; k <= n; k++) { await pg.mouse.move(x0 + (x - x0) * k / n, y0 + (y - y0) * k / n); await pg.waitForTimeout(16); }
      await pg.mouse.up(); await pg.waitForTimeout(120 * W);
    };
    await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html'));
    await pg.evaluate(() => { store.set('onderbalk', null); store.set('home', null); renderHome(); show('home'); });
    await pg.waitForTimeout(150 * W);

    ok((await balk()) === 'home,search,ai,backup,settings', 'standaard: Start, Zoeken, Assistent, Backup, Instellingen');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#tabs button')].every(b => b.getBoundingClientRect().height >= 44 && b.getAttribute('aria-label'))), 'plekken minstens 44 px hoog en met een naam');
    ok(await pg.$eval('#home-inst', e => e.hidden), 'Instellingen staat in de balk: geen extra knop rechtsboven');

    // Opgeschoond: onbekend, dubbel, Start, de skin en te veel vallen weg
    await pg.evaluate(() => { store.set('onderbalk', { v: 1, items: ['zz', 'radio', 'radio', 'home', 'skin', 'notes', 'pdf', 'kluis', 'agenda'] }); obRender(); });
    ok((await balk()) === 'home,radio,notes,pdf,kluis', 'kapotte indeling opgeschoond, hooguit 4 naast Start');
    ok(await pg.evaluate(() => !document.querySelector('#home-inst').hidden), 'zonder Instellingen in de balk: knop rechtsboven op het startscherm');
    await pg.click('#home-inst'); await pg.waitForTimeout(100 * W);
    ok(await pg.evaluate(() => current === 'settings'), 'die knop opent Instellingen');
    await pg.evaluate(() => { store.set('onderbalk', null); obRender(); show('home'); }); await pg.waitForTimeout(100 * W);

    // Een tool in de balk werkt als tabblad: opent, balk blijft staan, geen terugknop
    await pg.evaluate(() => { store.set('onderbalk', { v: 1, items: ['search', 'radio'] }); obRender(); });
    await pg.click('#tabs button[data-t="radio"]'); await pg.waitForTimeout(150 * W);
    ok(await pg.evaluate(() => current === 'radio' && document.body.classList.contains('tabs-on') && document.body.classList.contains('tab-root')
      && document.querySelector('#tabs button[data-t="radio"]').getAttribute('aria-current') === 'page'), 'Radio uit de balk: tabblad met de balk eronder');
    await pg.click('#tabs button[data-t="home"]'); await pg.waitForTimeout(150 * W);

    // Slepen: een tegel naar de balk (er is plek)
    await pg.evaluate(() => scrollTo(0, 0));
    const [bx, by] = await midden('#tabs button[data-t="search"]');
    await sleep('#tile-notes', bx - 30, by);
    ok((await balk()) === 'home,notes,search,radio', 'Notities ingedrukt gehouden en vóór Zoeken in de balk gesleept');
    ok(await pg.evaluate(() => current === 'home' && !document.querySelector('.ob-geest') && !document.getElementById('sheet').classList.contains('on')), 'na het slepen: niets geopend, geen schaduw achter');
    ok(await pg.evaluate(() => JSON.stringify(store.get('onderbalk').items) === '["notes","search","radio"]'), 'bewaard in store onderbalk');
    // Ongedaan maken
    await pg.click('#undo button'); await pg.waitForTimeout(100 * W);
    ok((await balk()) === 'home,search,radio', 'Ongedaan maken zet de balk terug');
    await sleep('#tile-notes', ...(await midden('#tabs button[data-t="radio"]')).map((v, i) => i ? v : v + 60));
    ok((await balk()) === 'home,search,radio,notes', 'aan het eind loslaten: achteraan');

    // In de balk schuiven: Notities naar links, vóór Zoeken
    const [sx, sy] = await midden('#tabs button[data-t="search"]');
    await sleep('#tabs button[data-t="notes"]', sx - 30, sy);
    ok((await balk()) === 'home,notes,search,radio', 'in de balk verschoven');
    // Start blijft staan: oppakken doet niets
    const [hx, hy] = await midden('#tabs button[data-t="home"]');
    await sleep('#tabs button[data-t="home"]', hx + 150, hy);
    ok((await balk()) === 'home,notes,search,radio', 'Start is niet te verplaatsen');
    await pg.click('#tabs button[data-t="home"]'); await pg.waitForTimeout(100 * W);
    // Omhoog slepen: eruit
    const [rx, ry] = await midden('#tabs button[data-t="radio"]');
    await sleep('#tabs button[data-t="radio"]', rx, ry - 260);
    ok((await balk()) === 'home,notes,search', 'omhoog gesleept: uit de balk');
    ok(await pg.evaluate(() => current === 'home'), 'weghalen opent de tool niet');

    // Vol: een tegel vervangt de plek waar je loslaat
    await pg.evaluate(() => { store.set('onderbalk', { v: 1, items: ['search', 'ai', 'backup', 'settings'] }); obRender(); scrollTo(0, 0); });
    const [ax, ay] = await midden('#tabs button[data-t="ai"]');
    await sleep('#tile-notes', ax, ay);
    ok((await balk()) === 'home,search,notes,backup,settings', 'balk vol: Notities vervangt de Assistent');
    ok(await pg.evaluate(() => /Assistent|Assistant/.test(document.querySelector('#undo').textContent)), 'de melding zegt welke plek hij verving');

    // Ingedrukt houden zonder schuiven: het menu (tegel en balk)
    const [tx, ty] = await midden('#tile-radio');
    await pg.mouse.move(tx, ty); await pg.mouse.down(); await pg.waitForTimeout(650 * W); await pg.mouse.up(); await pg.waitForTimeout(150 * W);
    ok(await pg.evaluate(() => current === 'home' && document.getElementById('sheet').classList.contains('on') && /onderbalk/i.test(document.getElementById('sheet-acts').textContent)), 'tegel vasthouden en loslaten: menu met In de onderbalk zetten');
    await pg.evaluate(() => closeSheet());
    const [nx, ny] = await midden('#tabs button[data-t="notes"]');
    await pg.mouse.move(nx, ny); await pg.mouse.down(); await pg.waitForTimeout(650 * W); await pg.mouse.up(); await pg.waitForTimeout(150 * W);
    ok(await pg.evaluate(() => current === 'home' && document.getElementById('sheet').classList.contains('on') && document.getElementById('sheet-sub').textContent.length > 0), 'plek in de balk vasthouden: menu (en de tool opent niet)');
    await pg.evaluate(() => closeSheet());
    // Gewone tik op een tegel opent nog steeds de tool
    await pg.click('#tile-radio'); await pg.waitForTimeout(150 * W);
    ok(await pg.evaluate(() => current === 'radio' && !document.body.classList.contains('tabs-on')), 'gewone tik op een tegel opent de tool (zonder balk: niet in de balk)');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100 * W);

    // Menu van een tegel: In de onderbalk zetten (vol: kiezen welke plek)
    await pg.evaluate(() => toolMenu('pdf')); await pg.waitForTimeout(80 * W);
    await pg.locator('#sheet-acts button', { hasText: /In de onderbalk zetten|Add to the bottom bar/ }).click(); await pg.waitForTimeout(80 * W);
    ok(await pg.evaluate(() => document.getElementById('sheet').classList.contains('on') && document.querySelectorAll('#sheet-acts button').length === 4), 'vol: kiezen welke van de 4 plekken');
    await pg.locator('#sheet-acts button').nth(3).click(); await pg.waitForTimeout(80 * W);
    ok((await balk()) === 'home,search,notes,backup,pdf', 'PDF in plaats van Instellingen');
    await pg.evaluate(() => toolMenu('pdf')); await pg.waitForTimeout(80 * W);
    await pg.locator('#sheet-acts button', { hasText: /Uit de onderbalk halen|Remove from the bottom bar/ }).click(); await pg.waitForTimeout(80 * W);
    ok((await balk()) === 'home,search,notes,backup', 'via het menu weer eruit');

    // Startscherm aanpassen → Weergave → Onderbalk
    await pg.evaluate(() => { show('homeedit'); heTab('weer'); }); await pg.waitForTimeout(120 * W);
    const lijst = () => pg.$$eval('#he-ob-list .herow[data-id]', x => x.map(r => r.dataset.id).join());
    ok((await lijst()) === 'search,notes,backup' && await pg.isVisible('#he-ob-list .ob-vast'), 'lijst met Start vast bovenaan');
    ok(await pg.isVisible('#he-ob-stand'), 'zegt hoeveel plek er vrij is');
    await pg.click('#he-ob-list .herow[data-id="backup"] .mv[data-dir="-1"]'); await pg.waitForTimeout(80 * W);
    ok((await lijst()) === 'search,backup,notes' && (await balk()) === 'home,search,backup,notes', 'pijl omhoog: Backup naar links');
    ok(await pg.evaluate(() => document.activeElement && document.activeElement.classList.contains('mv')), 'focus blijft op een pijl');
    await pg.click('#he-ob-list .herow[data-id="notes"] .mv[data-weg]'); await pg.waitForTimeout(80 * W);
    ok((await lijst()) === 'search,backup', 'kruisje: uit de balk');
    await pg.click('#he-ob-add'); await pg.waitForTimeout(80 * W);
    const keuzes = await pg.$$eval('#sheet-acts button', x => x.map(b => b.textContent));
    ok(!keuzes.some(k => /Zoeken|Telefoon-skin|^Search$/.test(k)) && keuzes.some(k => /Instellingen|Settings/.test(k)) && keuzes.length > 20, 'toevoegen: elke tool en Instellingen, niet wat er al staat of de skin');
    await pg.locator('#sheet-acts button', { hasText: /^(Agenda|Calendar)$/ }).click(); await pg.waitForTimeout(80 * W);
    ok((await balk()) === 'home,search,backup,agenda' && (await lijst()) === 'search,backup,agenda', 'Agenda toegevoegd');
    await pg.click('#he-ob .big.ghost'); await pg.waitForTimeout(80 * W);
    ok((await balk()) === 'home,search,ai,backup,settings' && (await lijst()) === 'search,ai,backup,settings', 'Onderbalk standaard');
    // Bewaard na herladen
    await pg.evaluate(() => { store.set('onderbalk', { v: 1, items: ['nieuws', 'search'] }); });
    await pg.reload(); await pg.waitForTimeout(250 * W);
    ok((await balk()) === 'home,nieuws,search', 'na herladen dezelfde balk');

    // Aanraken (zoals op de telefoon): vasthouden en slepen, de pagina scrolt niet mee
    await pg.evaluate(() => { show('home'); scrollTo(0, 0); });
    await pg.waitForTimeout(120 * W);
    const cdp = await pg.context().newCDPSession(pg);
    const [ux, uy] = await midden('#tile-radio'); const [zx, zy] = await midden('#tabs button[data-t="search"]');
    const raak = (type, x, y) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x, y }] });
    const scroll0 = await pg.evaluate(() => scrollY);
    await raak('touchStart', ux, uy); await pg.waitForTimeout(700 * W);
    for (let k = 1; k <= 10; k++) { await raak('touchMove', ux + (zx + 30 - ux) * k / 10, uy + (zy - uy) * k / 10); await pg.waitForTimeout(16); }
    const scrollY = await pg.evaluate(() => scrollY);
    await raak('touchEnd'); await pg.waitForTimeout(200 * W);
    ok((await balk()) === 'home,nieuws,search,radio', 'met aanraken: Radio in de balk gesleept');
    ok(scrollY === scroll0, 'tijdens het slepen scrolde de pagina niet');
    ok(await pg.evaluate(() => current === 'home' && !document.getElementById('sheet').classList.contains('on')), 'aanraken: na slepen geen menu en niets geopend');

    // Engels: korte namen in de balk, volle naam voor de schermlezer
    await pg.evaluate(() => { store.set('onderbalk', { v: 1, items: ['ai', 'wc'] }); obRender(); });
    ok(await pg.evaluate(() => { const b = document.querySelector('#tabs button[data-t="wc"]'); return b.getAttribute('aria-label') === 'Toiletten en douches' && b.textContent === 'Toiletten'; }), 'korte naam in de balk, volle naam als label');

    // Engels: de balk volgt de taal
    await pg.evaluate(() => show('setregio')); await pg.waitForTimeout(100 * W);
    await pg.selectOption('#rg-lang', 'en'); await pg.waitForTimeout(200 * W);
    ok(await pg.evaluate(() => { const b = document.querySelector('#tabs button[data-t="wc"]'), h = document.querySelector('#tabs button[data-t="home"]'); return b.getAttribute('aria-label') === 'Toilets and showers' && b.textContent === 'Toilets' && h.textContent === 'Start'; }), 'Engels: Toilets in de balk');
    await pg.selectOption('#rg-lang', 'nl'); await pg.waitForTimeout(200 * W);
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100 * W);
    // Kort aanraken en meteen schuiven = scrollen, niet oppakken
    await pg.evaluate(() => scrollTo(0, 0));
    const [vx, vy] = await midden('#tile-pdf');
    await raak('touchStart', vx, vy); for (let k = 1; k <= 6; k++) { await raak('touchMove', vx, vy - 25 * k); await pg.waitForTimeout(16); } await pg.waitForTimeout(600 * W);
    ok(await pg.evaluate(() => !obSleep || !obSleep.op), 'meteen schuiven: niets opgepakt');
    await raak('touchEnd'); await pg.waitForTimeout(100 * W);
    ok((await balk()) === 'home,ai,wc', 'en de balk is niet veranderd');
    await pg.screenshot({ path: path.join(__dirname, 'ui', 'onderbalk-' + scheme + '.png') });
    console.log('errors', errors);
    await pg.close();
  }
  await browser.close();
})();
