// Podcasts: zoeken, volgen, afspelen, snelheid, verder luisteren en de slaaptimer.
const { chromium } = require('playwright');
const assert = require('node:assert');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    const P = () => pg.evaluate(() => JSON.parse(JSON.stringify(Android._pc)));

    // Tegel en populair
    await pg.click('#tile-podcasts'); await pg.waitForTimeout(300);
    assert.strictEqual(await pg.evaluate(() => current), 'podcasts');
    assert.strictEqual(await pg.isVisible('#plbar'), false, 'nog niets aan het luisteren');
    assert.strictEqual(await pg.$$eval('#pc-list .srow', x => x.length), 2, 'populair geladen');
    assert.match(await pg.textContent('#pc-list-label'), /Populair in Nederland/);
    // geen kapot plaatje: zonder hoesje een 🎧
    assert.match(await pg.textContent('#pc-list .srow:nth-child(2) .pcart'), /🎧/);

    // Zoeken (met fout en opnieuw)
    await pg.fill('#pc-q', 'fout'); await pg.waitForTimeout(800);
    assert.match(await pg.textContent('#pc-list'), /Geen internetverbinding/);
    await pg.fill('#pc-q', 'tech'); await pg.waitForTimeout(800);
    assert.match(await pg.textContent('#pc-list-label'), /Zoekresultaten/);
    assert.strictEqual(await pg.$$eval('#pc-list .srow', x => x.length), 1);
    assert.match(await pg.textContent('#pc-list'), /Rene & Co/);
    await pg.fill('#pc-q', ''); await pg.waitForTimeout(150);
    assert.match(await pg.textContent('#pc-list-label'), /Populair/);

    // Podcast openen
    await pg.click('#pc-list .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(300);
    assert.strictEqual(await pg.evaluate(() => current), 'podcast');
    assert.strictEqual(await pg.textContent('#pd-title'), 'Nieuws van Vandaag');
    assert.strictEqual(await pg.$$eval('#pd-eps .pcep', x => x.length), 50, 'eerste 50 afleveringen');
    assert.strictEqual(await pg.isVisible('#pd-more'), true);
    await pg.click('#pd-more');
    assert.strictEqual(await pg.$$eval('#pd-eps .pcep', x => x.length), 64);
    // titel met HTML blijft tekst
    assert.strictEqual(await pg.$$eval('#pd-eps b b', x => x.length), 0, 'geen HTML uit de feed');
    assert.match(await pg.textContent('#pd-eps .pcep:first-child b'), /Aflevering 64 <b>nieuw<\/b>/);
    // oudste eerst
    await pg.click('#pd-sort [data-v="old"]');
    assert.match(await pg.textContent('#pd-eps .pcep:first-child b'), /^Aflevering 1$/);
    await pg.click('#pd-sort [data-v="new"]');

    // Volgen
    await pg.click('#pd-sub'); await pg.waitForTimeout(100);
    assert.match(await pg.textContent('#pd-sub'), /Je volgt deze podcast/);
    assert.strictEqual((await P()).subs.length, 1);

    // Afspelen vanuit de lijst → mini-speler
    await pg.click('#pd-eps .pcep:nth-child(2) .pcepb'); await pg.waitForTimeout(400);
    assert.strictEqual(await pg.isVisible('#plbar'), true, 'mini-speler zichtbaar');
    assert.match(await pg.textContent('#plbar-t'), /Aflevering 63/);
    assert.match(await pg.textContent('#pd-eps .pcep.cur b'), /Aflevering 63/, 'huidige aflevering gemarkeerd');
    // markeren als beluisterd
    await pg.click('#pd-eps .pcep:nth-child(3) .pcmore'); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts >> text=Markeren als beluisterd'); await pg.waitForTimeout(80);
    assert.strictEqual(await pg.$$eval('#pd-eps .pcep.done', x => x.length), 1);
    await pg.check('#pd-hide');
    assert.strictEqual(await pg.$$eval('#pd-eps .pcep', x => x.length), 50, 'beluisterd verborgen (nog 50 getoond)');
    await pg.uncheck('#pd-hide');

    // Naar het hoofdscherm, dan de speler openen
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(300);
    assert.strictEqual(await pg.evaluate(() => current), 'podcasts');
    assert.strictEqual(await pg.$$eval('#pc-subs .pcsub', x => x.length), 1, 'mijn podcasts');
    await pg.click('#plbar-open'); await pg.waitForTimeout(400);
    assert.strictEqual(await pg.isVisible('#player'), true);
    assert.strictEqual(await pg.getAttribute('#pl-pp', 'aria-label'), 'Pauze');
    // vooruit/terug
    await pg.click('#player [aria-label="30 seconden vooruit"]');
    assert.strictEqual((await P()).lastSkip, 30);
    await pg.click('#player [aria-label="15 seconden terug"]');
    assert.strictEqual((await P()).lastSkip, -15);
    // schuiven
    await pg.evaluate(() => { const s = $('#pl-seek'); s.value = 500; s.dispatchEvent(new Event('input')); s.dispatchEvent(new Event('change')); });
    const st = await pg.evaluate(() => JSON.parse(Android.podState()));
    assert.ok(Math.abs((await P()).lastSeek - st.dur / 2) < 2000, 'naar het midden');
    // snelheid
    await pg.click('#pl-speed'); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts >> text=1,5×'); await pg.waitForTimeout(1200);
    assert.strictEqual((await P()).speed, 1.5);
    assert.strictEqual(await pg.textContent('#pl-speed'), '1,5×');
    // pauze
    await pg.click('#pl-pp'); await pg.waitForTimeout(400);
    assert.strictEqual((await P()).st.status, 'paused');
    assert.strictEqual(await pg.getAttribute('#pl-pp', 'aria-label'), 'Afspelen');
    await pg.click('#pl-pp'); await pg.waitForTimeout(400);
    assert.strictEqual((await P()).st.status, 'playing');

    // Slaaptimer: minuten
    await pg.click('#pl-sleep'); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.isVisible('#sleepdlg'), true);
    assert.strictEqual(await pg.isVisible('#sl-end'), true, 'einde aflevering bij podcasts');
    assert.strictEqual(await pg.textContent('#sl-off'), 'Annuleren');
    await pg.click('#sl-quick >> text=45 min');
    assert.strictEqual(await pg.textContent('#sl-min'), '45');
    await pg.click('[aria-label="Minuut meer"]'); await pg.click('[aria-label="Minuut meer"]');
    assert.strictEqual(await pg.textContent('#sl-min'), '47', 'per minuut instelbaar');
    await pg.click('#sl-go'); await pg.waitForTimeout(1300);
    assert.strictEqual((await P()).lastSleep, 47);
    assert.match(await pg.textContent('#pl-sleep'), /nog 4[67] min/);
    assert.match(await pg.textContent('#pl-sleepinfo'), /Stopt om/);
    assert.strictEqual(await pg.evaluate(() => store.get('sleepMin', 0)), 47, 'onthouden voor de volgende keer');
    // erbij
    await pg.click('#pl-sleep'); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.isVisible('#sl-add'), true);
    assert.strictEqual(await pg.textContent('#sl-off'), 'Uitzetten');
    await pg.click('#sl-add >> text=+15 min'); await pg.waitForTimeout(1300);
    assert.match(await pg.textContent('#pl-sleep'), /nog 1 u 0?[12] min|nog 6[12] min/);
    // einde aflevering
    await pg.click('#pl-sleep'); await pg.waitForTimeout(150);
    await pg.click('#sl-end'); await pg.waitForTimeout(1300);
    assert.strictEqual((await P()).lastSleep, -1);
    assert.match(await pg.textContent('#pl-sleep'), /na deze aflevering/);
    // tot tijdstip
    await pg.click('#pl-sleep'); await pg.waitForTimeout(150);
    await pg.fill('#sl-time', '23:59'); await pg.click('#sleepdlg >> text=Instellen'); await pg.waitForTimeout(1300);
    assert.ok((await P()).lastSleep > 0);
    assert.match(await pg.textContent('#pl-sleepinfo'), /23:59/);
    // terug-knop sluit het venster
    await pg.click('#pl-sleep'); await pg.waitForTimeout(150);
    await pg.evaluate(() => goBack());
    assert.strictEqual(await pg.isVisible('#sleepdlg'), false);
    assert.strictEqual(await pg.isVisible('#player'), true, 'eerst alleen het venster dicht');
    assert.strictEqual(await pg.evaluate(() => current), 'podcasts');
    // uitzetten
    await pg.click('#pl-sleep'); await pg.waitForTimeout(150);
    await pg.click('#sl-off'); await pg.waitForTimeout(1300);
    assert.strictEqual((await P()).lastSleep, 0);
    assert.strictEqual(await pg.textContent('#pl-sleep'), '⏾ Slaaptimer');
    if (scheme === 'light') await pg.screenshot({ path: __dirname + '/shots/podcasts-light.png', fullPage: true });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(350);
    assert.strictEqual(await pg.isVisible('#player'), false, 'terug verkleint de speler');

    // Andere aflevering → de vorige staat bij Verder luisteren
    await pg.evaluate(() => pdOpen({ feed: 'https://feeds.example.org/tech', title: 'Tech in 20 minuten' })); await pg.waitForTimeout(300);
    await pg.click('#pd-eps .pcep:nth-child(1) .pcepb'); await pg.waitForTimeout(300);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(300);
    assert.strictEqual(await pg.isVisible('#pc-cont-wrap'), true);
    assert.match(await pg.textContent('#pc-cont'), /Aflevering 63/);
    assert.match(await pg.textContent('#pc-cont'), /nog/);
    await pg.click('#pc-cont .pcep:first-child .pcepb'); await pg.waitForTimeout(300);
    assert.match(await pg.textContent('#plbar-t'), /Aflevering 63/, 'verder luisteren speelt af');
    assert.ok((await P()).st.pos > 0, 'vanaf de bewaarde plek');
    if (scheme === 'dark') await pg.screenshot({ path: __dirname + '/shots/podcasts-dark.png', fullPage: true });

    // Ontvolgen
    await pg.evaluate(() => pdOpen({ feed: 'https://feeds.example.org/vandaag', title: 'Nieuws van Vandaag', id: 'p1' })); await pg.waitForTimeout(300);
    await pg.click('#pd-sub'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    assert.strictEqual((await P()).subs.length, 0);
    assert.match(await pg.textContent('#pd-sub'), /Volgen/);

    // Opnieuw dezelfde aflevering uit de lijst: verder waar je was (niet vanaf 0)
    await pg.evaluate(() => { Android._pc.prog[Android._pc.st.ep.key] = { p: 600000, d: 3600000, done: false }; });
    const key = await pg.evaluate(() => Android._pc.st.ep.key);
    await pg.evaluate(() => { const e = pdFeed.items.find(x => x.key === Android._pc.st.ep.key); Android._pc.st = { status: 'stopped' }; pcPlayEp(Object.assign({}, e, { pos: 0 }), pdPod, false); });
    await pg.waitForTimeout(100);
    assert.ok((await P()).st.pos >= 600000, 'bewaarde plek gebruikt: ' + (await P()).st.pos);
    // Gestopt: slaaptimer kan niet
    await pg.evaluate(() => { Android.podStop(); pcPoll(true); sleepOpen('pod'); });
    await pg.click('#sl-go'); await pg.waitForTimeout(150);
    assert.match(await pg.textContent('#toast'), /Start eerst een aflevering/);
    await pg.evaluate(() => sleepClose());
    assert.strictEqual(await pg.isVisible('#plbar'), false, 'gestopt: geen speler meer');
    // Terug: podcast → podcasts → home
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.evaluate(() => current), 'podcasts');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.evaluate(() => current), 'home');
    // zoeken vindt Podcasts, maar niet het losse podcast-scherm
    await pg.fill('#gs-q', 'podcasts'); await pg.waitForTimeout(600);
    assert.ok((await pg.$$eval('#gs-res .gi', x => x.map(e => e.innerText))).some(t => /Podcasts/.test(t)));
    assert.ok(await pg.evaluate(() => !gsIndex.some(x => x.scr === 'podcast')));

    assert.deepStrictEqual(errs, []);
    await pg.close();
  }
  console.log('errors []');
  await b.close();
})().catch(e => { console.error(e); console.log('errors [' + e.message + ']'); process.exit(1); });
