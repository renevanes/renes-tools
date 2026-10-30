// Indeling van Podcasts: tabbladen Luisteren / Ontdekken / Downloads, korte lijsten met "meer", Spotify in het menu.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => {
      const A = Android._pc;
      A.top = Array.from({ length: 25 }, (_, i) => ({ id: 'p' + i, feed: 'https://feeds.example.org/f' + i, title: 'Podcast ' + i, author: 'Maker ' + i, image: '', genre: 'Nieuws' }));
    });
    // Nog niets gevolgd: meteen Ontdekken, met 10 populaire en "Meer tonen"
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => pcTab === 'discover') && await pg.isVisible('#pc-q'), 'leeg: begint bij Ontdekken');
    ok(!(await pg.isVisible('#pc-from-spotify')), 'Spotify niet meer bovenaan');
    ok((await pg.$$eval('#pc-list .srow', x => x.length)) === 10 && await pg.isVisible('#pc-list-more'), '10 populaire, met Meer tonen');
    await pg.click('#pc-list-more'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#pc-list .srow', x => x.length)) === 20, 'meer tonen: 20');
    await pg.click('#pc-list .srow:nth-child(15) .sbtn'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'podcast' && pdPod.feed === 'https://feeds.example.org/f14'), 'de juiste podcast geopend (ook na Meer tonen)');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    // Zoeken: weinig resultaten = geen Meer tonen; na volgen en terug blijf je bij je zoekresultaten
    await pg.fill('#pc-q', 'Podcast 3'); await pg.waitForTimeout(800);
    ok((await pg.$$eval('#pc-list .srow', x => x.length)) >= 1 && !(await pg.isVisible('#pc-list-more')), 'weinig zoekresultaten: geen Meer tonen');
    await pg.click('#pc-list .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(300);
    await pg.click('#pd-sub'); await pg.waitForTimeout(100);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => pcTab === 'discover') && (await pg.inputValue('#pc-q')) === 'Podcast 3', 'na volgen terug: nog bij je zoekresultaten');
    await pg.click('#pc-tab-listen'); await pg.waitForTimeout(100);
    ok(!(await pg.isVisible('#pc-subs-more')), 'weinig podcasts: geen Alle-knop');
    await pg.evaluate(() => { Android._pc.subs = []; pcRenderSubs(); $('#pc-q').value = ''; pcSearchSoon(); });
    // Pijltjestoetsen tussen de tabbladen
    await pg.focus('#pc-tab-listen'); await pg.keyboard.press('ArrowRight');
    ok(await pg.evaluate(() => pcTab === 'discover' && document.activeElement.id === 'pc-tab-discover' && document.activeElement.tabIndex === 0), 'pijltje rechts: volgend tabblad');
    await pg.keyboard.press('End'); ok(await pg.evaluate(() => pcTab === 'dl'), 'End: laatste tabblad');
    await pg.keyboard.press('ArrowRight'); ok(await pg.evaluate(() => pcTab === 'listen'), 'rond: weer het eerste');
    // Leeg tabblad Luisteren: uitleg, zoeken en (klein) Spotify
    await pg.click('#pc-tab-listen'); await pg.waitForTimeout(100);
    ok(await pg.isVisible('#pc-listen-empty') && await pg.isVisible('#pc-from-spotify'), 'Luisteren leeg: uitleg en kleine Spotify-link');
    await pg.click('#pc-listen-empty .big'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => pcTab === 'discover' && document.activeElement.id === 'pc-q'), 'Podcasts zoeken: naar Ontdekken met het zoekveld');
    // Veel gevolgd en geluisterd: Luisteren, met korte lijsten
    await pg.evaluate(() => {
      const A = Android._pc;
      A.subs = A.top.slice(0, 9).map((p, i) => Object.assign({ fresh: i === 7 ? 3 : 0 }, p));
      A.recent = [0, 1, 2, 3, 4].map(i => ({ ep: { key: 'k' + i, title: 'Aflevering ' + i, url: 'https://x/a.mp3', dur: 1800 }, pod: A.top[i], t: Date.now() - i }));
      pcTabUser = false; show('home'); show('podcasts');
    });
    await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => pcTab === 'listen') && !(await pg.isVisible('#pc-q')), 'met podcasts: begint bij Luisteren');
    const cont = await pg.evaluate(() => { const c = $('#pc-cont'), r = [...c.querySelectorAll('.pcep')].map(x => x.getBoundingClientRect()); return { n: r.length, oneRow: r.every(x => Math.abs(x.top - r[0].top) < 1), h: c.getBoundingClientRect().height, wide: document.documentElement.scrollWidth, scroll: c.scrollWidth > c.clientWidth }; });
    ok(cont.n === 5 && cont.oneRow && cont.h < 130 && cont.scroll && cont.wide <= 390, 'verder luisteren: alle 5 naast elkaar om opzij te vegen (' + Math.round(cont.h) + ' px hoog)');
    ok((await pg.textContent('#pc-cont-n')).includes('5'), 'verder luisteren: aantal erbij');
    ok(await pg.evaluate(() => $('#pc-subs-wrap').getBoundingClientRect().top < $('#pc-cont-wrap').getBoundingClientRect().top), 'Mijn podcasts bovenaan');
    ok((await pg.$$eval('#pc-subs .pcsub', x => x.length)) === 9 && await pg.evaluate(() => $('#pc-subs').classList.contains('pcrow')) && (await pg.textContent('#pc-subs-more')) === 'Alle 9', 'mijn podcasts: één rij om te vegen, met Alle 9');
    ok((await pg.textContent('#pc-subs .pcsubw:first-child .t')) === 'Podcast 7', 'podcast met nieuwe afleveringen vooraan');
    await pg.click('#pc-subs .pcsubw:first-child .pcsub'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => pdPod.feed === 'https://feeds.example.org/f7'), 'de juiste podcast geopend');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    await pg.click('#pc-subs-more'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'podlijst') && (await pg.$$eval('#pcx-subs .pcsub', x => x.length)) === 9 && (await pg.textContent('#pcx-h1')) === 'Mijn podcasts' && !(await pg.isVisible('#pcx-new')), 'Alle: eigen scherm met alle 9 als raster');
    await pg.click('#pcx-subs .pcsubw:nth-child(3) .pcsub'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'podcast' && pdPod.feed === pcSubs[2].feed), 'podcast uit de lijst geopend');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'podlijst'), 'terug: weer bij de lijst');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'podcasts' && pcTab === 'listen'), 'nog eens terug: Luisteren');
    ok(!(await pg.isVisible('#pc-listen-empty')), 'geen lege uitleg als er iets is');
    await pg.screenshot({ path: __dirname + '/shots/podcast-indeling-' + scheme + '.png' });
    // Menu: bijwerken, downloadinstellingen en Spotify
    await pg.click('#pc-menu'); await pg.waitForTimeout(100);
    const acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    ok(acts.some(t => t.includes('bijwerken')) && acts.includes('Instellingen voor downloaden') && acts.includes('Uit Spotify overnemen'), 'menu met bijwerken, downloads en Spotify');
    await pg.click('#sheet-acts button:has-text("Uit Spotify overnemen")'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'podspotify'), 'Spotify via het menu');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    // Tabbladen bereikbaar en aangeduid voor schermlezers
    ok(await pg.evaluate(() => [...document.querySelectorAll('#s-podcasts [role=tab]')].filter(t => t.getAttribute('aria-selected') === 'true').length === 1), 'precies één tabblad gekozen');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
