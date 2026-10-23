// Indeling van Radio: tabbladen Favorieten / Zenders, favorieten als voorkeuzetoetsen, genres, 20 tegelijk, menu.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => {
      const tags = ['pop,hits', 'news,talk', 'dance,edm', 'rock', 'classical', 'jazz,soul'];
      Android._rd.list = Array.from({ length: 60 }, (_, i) => ({ id: 's' + i, name: 'Zender ' + i, url: 'https://example.org/s' + i, logo: '', tags: tags[i % 6], codec: 'MP3', bitrate: 128 }));
    });
    // Geen favorieten: meteen Zenders, 20 tegelijk
    await pg.evaluate(() => show('radio')); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => rdTab === 'all') && await pg.isVisible('#rd-search'), 'geen favorieten: begint bij Zenders');
    ok((await pg.$$eval('#rd-list .srow', x => x.length)) === 20 && await pg.isVisible('#rd-list-more'), '20 zenders, met Meer tonen');
    await pg.click('#rd-list-more'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#rd-list .srow', x => x.length)) === 40, 'meer tonen: 40');
    // Genre kiezen: alleen die zenders, en afspelen/favoriet kiest de juiste
    const genres = await pg.$$eval('#rd-genres button', x => x.map(e => e.textContent));
    ok(genres[0] === 'Alles' && genres.includes('Dance') && genres.includes('Klassiek'), 'genres uit de tags');
    await pg.click('#rd-genres button:has-text("Dance")'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#rd-list .srow', x => x.length)) === 10 && !(await pg.isVisible('#rd-list-more')), 'Dance: 10 zenders, geen Meer tonen');
    ok((await pg.getAttribute('#rd-genres button.on', 'aria-checked')) === 'true', 'gekozen genre aangeduid');
    await pg.click('#rd-list .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => Android._rd.st.station.name === 'Zender 8'), 'afspelen in een genre: de juiste zender');
    await pg.click('#rd-list .srow:nth-child(1) .star'); await pg.click('#rd-list .srow:nth-child(2) .star'); await pg.click('#rd-list .srow:nth-child(3) .star'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._rd.favs.map(f => f.name).join() === 'Zender 2,Zender 8,Zender 14'), 'favoriet in een genre: de juiste zenders');
    ok((await pg.textContent('#rd-tab-favs-n')) === '3', 'aantal favorieten op het tabblad');
    await pg.click('#rd-genres button:has-text("Alles")');
    // Favorieten als voorkeuzetoetsen
    await pg.click('#rd-tab-favs'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#rd-favs .rdfav', x => x.length)) === 3 && !(await pg.isVisible('#rd-search')), 'favorieten als tegels');
    ok(await pg.isVisible('#rd-favs .rdfav.playing'), 'de zender die speelt is aangeduid');
    await pg.click('#rd-favs .rdfav:nth-child(3) .rdfavb'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => Android._rd.st.station.name === 'Zender 14'), 'tegel tikken: afspelen');
    await pg.screenshot({ path: __dirname + '/shots/radio-indeling-' + scheme + '.png' });
    await pg.click('#rd-favs .rdfav:nth-child(3) .rdfavm'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Bovenaan")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => rdFavs[0].name === 'Zender 14'), 'verplaatsen via ⋯');
    await pg.click('#rd-favs .rdfav:nth-child(1) .rdfavm'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Uit favorieten")'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#rd-favs .rdfav', x => x.length)) === 2, 'weghalen via ⋯');
    // Radiowekker in één regel, ook via het menu
    await pg.evaluate(() => Android.alarmSet(true, 6, 45, 31, JSON.stringify(Android._rd.list[1])));
    await pg.evaluate(() => { show('home'); show('radio'); }); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => rdTab === 'favs') && (await pg.textContent('#rd-alarm-sub')) === 'Aan · 06:45 · Zender 1', 'radiowekker: aan, tijd en zender');
    await pg.click('#rd-menu'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).includes('Radiowekker'), 'menu met radiowekker');
    await pg.click('#sheet-acts button:has-text("Radiowekker")'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'ralarm'), 'naar de radiowekker');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    // Zoeken: na terugkomen nog bij je zoekresultaten
    await pg.click('#rd-tab-all'); await pg.fill('#rd-search', 'Zender 5'); await pg.waitForTimeout(700);
    await pg.evaluate(() => { show('home'); show('radio'); }); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => rdTab === 'all') && (await pg.inputValue('#rd-search')) === 'Zender 5', 'zoeken blijft staan');
    // Pijltjestoetsen en lege favorieten
    await pg.focus('#rd-tab-all'); await pg.keyboard.press('ArrowLeft');
    ok(await pg.evaluate(() => rdTab === 'favs' && document.activeElement.id === 'rd-tab-favs'), 'pijltje: ander tabblad');
    await pg.evaluate(() => { Android._rd.favs = []; rdFavs = []; rdRender(); });
    ok(await pg.isVisible('#rd-favs-empty') && (await pg.textContent('#rd-tab-favs-n')) === '', 'geen favorieten: uitleg');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
