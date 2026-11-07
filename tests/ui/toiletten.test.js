// Toiletten en douches: eenmalig ophalen, zoeken bij mij (met toestemming), filters, lijst, kaart, een plek en de route
const { chromium } = require('playwright');
const path = require('path');
const W = +process.env.UI_WACHT || 1;
(async () => {
  const b = await chromium.launch();
  const errs = [];
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(scheme + ': ' + e.message));
    const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m + ' (' + scheme + ')'); if (!c) errs.push(scheme + ': ' + m); };
    await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300 * W);
    const vis = sel => pg.evaluate(s => { const e = document.querySelector(s); return !!e && !!e.getClientRects().length; }, sel);
    await pg.evaluate(() => { window._open = []; Android.openUrl = u => window._open.push(u); });

    // ---------- de tegel en eenmalig instellen ----------
    ok(await vis('#tile-wc') && (await pg.textContent('#tile-wc')).includes('Toiletten en douches'), 'tegel op het startscherm');
    ok(await pg.evaluate(() => HOME_DEFAULT.groups.find(g => g.name === 'Onderweg').tools.includes('wc') && TILE_TOOL['tile-wc'] === 'wc' && !!TOOLS.wc && UI_TOOL_ICONS.wc === 'toilet'), 'aangemeld: Onderweg, TOOLS, pictogram');
    await pg.click('#tile-wc'); await pg.waitForTimeout(250 * W);
    ok(await pg.evaluate(() => current === 'wc'), 'scherm Toiletten en douches open');
    ok(await vis('#wc-setup') && await vis('#wc-ophalen') && !(await vis('#wc-hier')), 'nog geen gegevens: alleen eenmalig instellen');
    ok(!(await vis('#wc-map')), 'zonder gegevens geen kaart (geen kaartbeelden ophalen)');
    // de nagebootste scan is na 120 ms klaar: de stand in de pagina zelf lezen, meteen na de klik
    const bezig = await pg.evaluate(() => { const b = document.getElementById('wc-ophalen'); b.click(); return [b.disabled, document.getElementById('wc-setup-st').textContent]; });
    ok(bezig[0] && /Bezig/.test(bezig[1]), 'ophalen: bezig');
    await pg.waitForTimeout(300 * W);
    ok(!(await vis('#wc-setup')) && await vis('#wc-hier'), 'opgehaald: eenmalig instellen weg, Zoek bij mij zichtbaar');
    ok(/Bijgewerkt/.test(await pg.textContent('#toast')), 'melding: bijgewerkt (' + await pg.textContent('#toast') + ')');
    ok(await vis('#wc-privacy') && /Je locatie gaat niet naar de bronnen/.test(await pg.textContent('#wc-privacy')), 'zichtbaar: je locatie gaat niet naar de bronnen');
    ok((await pg.textContent('#wc-voet')).includes('ODbL'), 'bronvermelding met licentie onderaan');
    ok(await vis('#wc-map') && await pg.evaluate(() => !!document.querySelector('#wc-map .leaflet-container, #wc-map.leaflet-container')), 'kaart staat er');
    ok(/Zoek bij mij|op de kaart/.test(await pg.textContent('#wc-list')), 'lege lijst met uitleg');

    // ---------- zoeken bij mij: eerst toestemming ----------
    await pg.evaluate(() => { Android._au.perms.loc = false; });
    await pg.click('#wc-hier'); await pg.waitForTimeout(400 * W);
    ok(await pg.evaluate(() => Android._au.perms.loc === true), 'zonder toestemming: de app vraagt hem');
    ok((await pg.$$('#wc-list .srow')).length >= 3, 'na toestemming vanzelf gezocht: ' + (await pg.$$('#wc-list .srow')).length + ' plekken');
    const rijen = await pg.$$eval('#wc-list .srow', r => r.map(x => x.textContent));
    ok(/Toilet Binnenrotte/.test(rijen.join('|')) && !/Shell Weena/.test(rijen.join('|')), 'tankstations (meestal) staan standaard niet in de lijst');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#wc-list .srow b')].every(x => !x.textContent || x.closest('[translate=no]') || x.getAttribute('translate') === 'no' || /Openbaar toilet|Douche|Station/.test(x.textContent))), 'namen in de lijst niet vertalen');
    const afst = await pg.$$eval('#wc-list .wc-d', r => r.map(x => x.textContent));
    ok(afst.every(x => /^\d[\d.,]* (m|km)$/.test(x)), 'afstanden via de regio-helpers: ' + afst.slice(0, 3).join(', '));
    const n0 = (await pg.$$('#wc-list .srow')).length;
    await pg.click('#wc-chips [data-f="meestal"]'); await pg.waitForTimeout(80 * W);
    ok((await pg.getAttribute('#wc-chips [data-f="meestal"]', 'aria-pressed')) === 'true' && /Shell Weena/.test(await pg.textContent('#wc-list')) && (await pg.$$('#wc-list .srow')).length > n0, 'Ook tankstations e.d.: tankstation erbij');
    await pg.click('#wc-chips [data-f="douche"]'); await pg.waitForTimeout(80 * W);
    ok(/Douches Zwembad/.test(await pg.textContent('#wc-list')) && !/Binnenrotte/.test(await pg.textContent('#wc-list')), 'filter Douche');
    await pg.click('#wc-chips [data-f="douche"]'); await pg.click('#wc-chips [data-f="meestal"]');
    await pg.click('#wc-chips [data-f="rolstoel"]'); await pg.waitForTimeout(80 * W);
    ok(/Binnenrotte/.test(await pg.textContent('#wc-list')) && !/Douches Zwembad/.test(await pg.textContent('#wc-list')), 'filter Rolstoel');
    ok(await pg.evaluate(() => store.get('wcFilter', {}).rolstoel === true), 'filter blijft bewaard');
    await pg.click('#wc-chips [data-f="rolstoel"]'); await pg.waitForTimeout(80 * W);
    ok(await pg.evaluate(() => [...document.querySelectorAll('#wc-chips button')].every(b => b.getBoundingClientRect().height >= 44)), 'filterknoppen minstens 44 px hoog');
    ok(await pg.evaluate(() => document.querySelectorAll('#wc-map .leaflet-interactive').length >= 3), 'plekken op de kaart');

    // ---------- tikken op de kaart: daar zoeken ----------
    await pg.evaluate(() => { wcMap.fire('click', { latlng: L.latLng(52.0792, 4.3112) }); }); await pg.waitForTimeout(100 * W);
    ok(/Toilet Plein/.test(await pg.$eval('#wc-list .srow', r => r.textContent)), 'tik op de kaart (Den Haag): daar zoeken');
    await pg.click('#wc-hier'); await pg.waitForTimeout(300 * W);

    // ---------- een plek ----------
    await pg.click('#wc-list .srow .sbtn >> text=Toilet Binnenrotte'); await pg.waitForTimeout(200 * W);
    ok(await pg.evaluate(() => current === 'wcplek'), 'plek geopend');
    const body = await pg.textContent('#wcp-body');
    ok(/Gratis/.test(body) && /Rolstoeltoegankelijk/.test(body) && /Mo-Su 08:00-20:00/.test(body), 'eigenschappen en openingstijden');
    ok(/OpenStreetMap Nederland/.test(body) && /ODbL/.test(body), 'Uit: bron met licentie');
    ok(/hiervandaan/.test(body), 'afstand tot je plek');
    ok(await pg.evaluate(() => document.querySelector('#wcp-body h2').getAttribute('translate') === 'no'), 'naam niet vertalen');
    await pg.click('#wcp-body button:has-text("Route lopend")');
    await pg.click('#wcp-body button:has-text("In kaart-app")');
    await pg.click('#wcp-body button:has-text("In OpenStreetMap")');
    const urls = await pg.evaluate(() => window._open);
    ok(/^https:\/\/www\.google\.com\/maps\/dir\/\?api=1&travelmode=walking&destination=51\.9231,4\.487$/.test(urls[0]) && /^geo:51\.9231,4\.487\?q=/.test(urls[1]) && urls[2] === 'https://www.openstreetmap.org/node/101', 'route lopend, kaart en OpenStreetMap: ' + urls.join(' | '));
    await pg.evaluate(() => document.getElementById('toast').classList.remove('on'));
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'toiletten-plek-' + scheme + '.png'), fullPage: true });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200 * W);
    ok(await pg.evaluate(() => current === 'wc') && (await pg.$$('#wc-list .srow')).length >= 3, 'terug: de lijst staat er nog');
    await pg.evaluate(() => { const t = document.getElementById('toast'); if (t) t.classList.remove('on'); });
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'toiletten-' + scheme + '.png'), fullPage: true });

    // ---------- kaart uit: geen kaartbeelden ----------
    await pg.click('#wc-set-btn'); await pg.waitForTimeout(150 * W);
    ok(await pg.evaluate(() => current === 'wcset'), 'schuifjes: instellingen');
    ok((await pg.getAttribute('#wcs-kaart', 'aria-checked')) === 'true', 'kaart staat standaard aan');
    await pg.click('#wcs-kaart'); await pg.evaluate(() => goBack()); await pg.waitForTimeout(200 * W);
    ok(!(await vis('#wc-map')) && (await pg.$$('#wc-list .srow')).length >= 3, 'kaart uit: alleen de lijst');
    await pg.click('#wc-set-btn'); await pg.click('#wcs-kaart'); await pg.evaluate(() => goBack()); await pg.waitForTimeout(200 * W);

    // ---------- zoeken in de app en Engels ----------
    ok(await pg.evaluate(() => GS_SYN.toiletten && GS_SYN.toiletten.includes('wc')), 'overal zoeken: wc, douche, tankstation');
    await pg.evaluate(() => { rgSave({ lang: 'en' }); }); await pg.waitForTimeout(300 * W);
    await pg.evaluate(() => { show('home'); show('wc'); }); await pg.waitForTimeout(300 * W);
    ok(/Search near me/.test(await pg.textContent('#wc-hier')) && /does not go to the sources/.test(await pg.textContent('#wc-privacy')), 'Engels: Search near me en de privacyregel');
    ok((await pg.textContent('#wc-chips')).includes('Also petrol stations etc. (usually)') && /Wheelchair/.test(await pg.textContent('#wc-chips')), 'Engels: filters');
    await pg.click('#wc-list .srow .sbtn >> text=Toilet Binnenrotte'); await pg.waitForTimeout(250 * W);
    const en = await pg.textContent('#wcp-body');
    ok(/Walking route/.test(en) && /From/.test(en) && /Wheelchair accessible/.test(en) && /Toilet Binnenrotte/.test(en) && !/Rolstoel|hiervandaan|Uit\b/.test(en), 'Engels: de plek (' + en.replace(/\s+/g, ' ').slice(0, 90) + ')');
    await pg.evaluate(() => rgSave({ lang: 'nl' })); await pg.waitForTimeout(150 * W);
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})().catch(e => { console.log('errors', [String(e && e.stack || e)]); process.exit(1); });
