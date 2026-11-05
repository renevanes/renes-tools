const { chromium } = require('playwright');
const assert = require('node:assert');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    const P = () => pg.evaluate(() => JSON.parse(JSON.stringify(Android._pdf)));

    // tegel op home opent het scherm
    assert.ok(await pg.$('#tile-pdf'), 'tegel bestaat');
    await pg.evaluate(() => openTool('pdf')); await pg.waitForTimeout(250);
    assert.strictEqual(await pg.evaluate(() => current), 'pdf');
    assert.strictEqual(await pg.isVisible('#pdf-recent-card'), false, 'nog niets gemaakt');
    assert.strictEqual(await pg.isVisible('#pdf-split'), false);

    // viewer: openen en standaard-app
    await pg.click('#pdf-view-card >> text=PDF openen');
    assert.strictEqual((await P()).viewPick, 1);
    assert.match(await pg.textContent('#pdf-def'), /kies Tools en dan Altijd/);
    await pg.click('#pdf-def-btn');
    assert.strictEqual((await P()).madeDefault, 1);
    await pg.evaluate(() => { Android._pdf.def = { state: 'other', app: '<b>Drive</b>' }; pdfRenderDef(); });
    assert.match(await pg.textContent('#pdf-def'), /Nu <b>Drive<\/b>\./, 'appnaam als tekst');
    assert.strictEqual(await pg.$$eval('#pdf-def b', x => x.length), 0);
    await pg.evaluate(() => { Android._pdf.def = { state: 'ours', app: "Tools" }; enterPdf(); });
    assert.match(await pg.textContent('#pdf-def'), /✓ PDF's openen standaard in Tools/);
    await pg.click('#pdf-def-btn'); assert.strictEqual((await P()).madeDefault, 2, 'Android meldt zelf dat het al zo is');
    await pg.evaluate(() => { Android._pdf.def = null; pdfRenderDef(); });

    // plaatjes kiezen, volgorde wijzigen, weghalen (eigen scherm)
    await pg.click('#pdf-t-img'); assert.strictEqual(await pg.evaluate(() => current), 'pdfimg');
    assert.strictEqual(await pg.evaluate(() => $('#pdf-img-pick').classList.contains('ghost')), false, 'kiezen is de hoofdknop');
    await pg.click('text=＋ Plaatjes kiezen'); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.$$eval('#pdf-imgs .pdfimg', x => x.length), 2);
    assert.match(await pg.textContent('#pdf-img-make'), /2 pagina's/);
    await pg.click('[aria-label="Plaatje 1 naar achteren"]'); await pg.waitForTimeout(80);
    assert.deepStrictEqual((await P()).imgs.map(m => m.w), [400, 300], 'verwisseld');
    await pg.click('text=＋ Plaatjes kiezen'); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.$$eval('#pdf-imgs .pdfimg', x => x.length), 4);
    await pg.click('[aria-label="Plaatje 4 weghalen"]'); await pg.waitForTimeout(80);
    assert.strictEqual(await pg.$$eval('#pdf-imgs .pdfimg', x => x.length), 3);
    // eerste heeft geen 'naar voren', laatste geen 'naar achteren'
    assert.strictEqual(await pg.$('[aria-label="Plaatje 1 naar voren"]'), null);
    assert.strictEqual(await pg.$('[aria-label="Plaatje 3 naar achteren"]'), null);
    if (scheme === 'light') await pg.screenshot({ path: __dirname + '/shots/pdf-plaatjes.png' });

    // formaat van het plaatje, naam, maken
    await pg.click('#pdf-size [data-v="img"]');
    assert.strictEqual(await pg.getAttribute('#pdf-size [data-v="img"]', 'aria-checked'), 'true');
    assert.strictEqual(await pg.getAttribute('#pdf-size [data-v="a4"]', 'aria-checked'), 'false');
    await pg.fill('#pdf-img-name', 'Bonnetjes');
    await pg.click('#pdf-img-make'); await pg.waitForTimeout(150);
    let p = await P();
    assert.strictEqual(p.lastA4, false, 'formaat plaatje doorgegeven');
    assert.strictEqual(p.outs[0].name, 'Bonnetjes.pdf');
    assert.strictEqual(await pg.$$eval('#pdf-imgs .pdfimg', x => x.length), 0);
    assert.match(await pg.textContent('.pdfres[data-scr="pdfimg"]'), /Bonnetjes\.pdf/, 'net gemaakt staat op het scherm zelf');
    assert.strictEqual(await pg.$eval('#pdf-share-all', e => e.style.display), 'none', 'delen-alles pas bij 2+');
    assert.strictEqual(await pg.inputValue('#pdf-img-name'), '');
    assert.strictEqual(await pg.locator('.pdfscr main button:disabled:not(#pdf-signature-use):not(#pdf-signature-delete):visible').count(), 0, 'zichtbare bediening niet meer bezig');
    await pg.evaluate(() => goBack()); assert.strictEqual(await pg.evaluate(() => current), 'pdf', 'terug naar het overzicht');
    assert.strictEqual(await pg.isVisible('#pdf-recent-card'), true, 'recent gemaakt op het overzicht');
    assert.ok(await pg.$('#pdf-recent .trip[data-n="Bonnetjes.pdf"]'));

    // splitsen: PDF kiezen, pagina's aantikken, tekstvak volgt
    await pg.click('#pdf-t-split'); assert.strictEqual(await pg.evaluate(() => current), 'pdfsplit');
    await pg.click('#pdf-split-pick'); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.textContent('#pdf-split-pick'), 'Andere PDF kiezen');
    assert.strictEqual(await pg.isVisible('#pdf-split'), true);
    assert.match(await pg.textContent('#pdf-split-info'), /Contract · 8 pagina's/);
    assert.strictEqual(await pg.$$eval('#pdf-pages .ppage', x => x.length), 8);
    await pg.waitForTimeout(200);
    assert.strictEqual(await pg.$$eval('#pdf-pages .ppage img', x => x.filter(i => i.src.startsWith('data:image/jpeg')).length), 8, 'voorbeelden geladen');
    // bezig met splitsen: voorbeelden worden later alsnog geladen
    await pg.evaluate(() => { const o = Android.pdfThumb; let n = 0; Android.pdfThumb = function(i){ return n++ < 2 ? 'wait' : o.call(this, i); }; document.querySelectorAll('#pdf-pages img').forEach(x => x.removeAttribute('src')); pdfLoadThumbs(0); });
    await pg.waitForTimeout(2200);
    assert.strictEqual(await pg.$$eval('#pdf-pages .ppage img', x => x.filter(i => (i.getAttribute('src') || '').startsWith('data:image/jpeg')).length), 8, 'voorbeelden na wachten geladen');
    for (const i of [0, 1, 2, 4]) await pg.click('#pdf-pages .ppage[data-i="' + i + '"]');
    assert.strictEqual(await pg.inputValue('#pdf-range'), '1-3, 5');
    assert.strictEqual(await pg.getAttribute('#pdf-pages .ppage[data-i="4"]', 'aria-pressed'), 'true');
    await pg.click('#pdf-pages .ppage[data-i="1"]');
    assert.strictEqual(await pg.inputValue('#pdf-range'), '1, 3, 5');
    // typen werkt de selectie bij, ongeldig laat hem staan
    await pg.fill('#pdf-range', '2-4, 7-');
    assert.deepStrictEqual(await pg.$$eval('#pdf-pages .ppage.on', x => x.map(e => +e.dataset.i)), [1, 2, 3, 6, 7]);
    await pg.fill('#pdf-range', '9');
    assert.deepStrictEqual(await pg.$$eval('#pdf-pages .ppage.on', x => x.map(e => +e.dataset.i)), [1, 2, 3, 6, 7], 'ongeldig: selectie blijft');
    assert.strictEqual(await pg.evaluate(() => pdfParse('3-1', 8)), null);
    assert.deepStrictEqual(await pg.evaluate(() => [...pdfParse('1 - 3', 8)]), [0, 1, 2], 'spaties rond het streepje');
    // bezig blijft bezig als er intussen een gekozen bestand binnenkomt
    await pg.evaluate(() => { pdfSetBusy('Splitsen…'); onPdf({ kind: 'images', count: 0, warn: '' }); });
    assert.ok(await pg.evaluate(() => pdfBusy), 'nog bezig');
    assert.ok(await pg.$$eval('.pdfscr main button', x => x.every(b => b.disabled)), 'knoppen blijven uit tijdens bezig');
    assert.match(await pg.textContent('#s-pdfsplit .pdfbusy'), /Splitsen/, 'bezig ook op het taakscherm');
    await pg.evaluate(() => onPdf({ kind: 'done', made: [] }));
    assert.strictEqual(await pg.evaluate(() => pdfBusy), false);
    assert.strictEqual(await pg.evaluate(() => pdfParse('a', 8)), null);
    await pg.fill('#pdf-range', '1-3');
    await pg.click('text=Gekozen pagina\'s als één PDF'); await pg.waitForTimeout(150);
    p = await P();
    assert.deepStrictEqual(p.split, ['pick', '1-3']);
    assert.strictEqual(p.outs[0].name, 'Contract p1-3.pdf');
    await pg.fill('#pdf-range', '1-2, 3-4');
    await pg.click('text=Elk deel apart'); await pg.waitForTimeout(150);
    assert.deepStrictEqual((await P()).split, ['parts', '1-2, 3-4']);
    await pg.fill('#pdf-range', '');
    await pg.click('text=Gekozen pagina\'s als één PDF'); await pg.waitForTimeout(100);
    assert.deepStrictEqual((await P()).split, ['parts', '1-2, 3-4'], 'leeg: niets gesplitst');
    await pg.click('text=Elke pagina een eigen PDF'); await pg.waitForTimeout(150);
    assert.deepStrictEqual((await P()).split, ['each', '']);
    // veel pagina's: eerst vragen
    await pg.evaluate(() => { pdfIn = { name: 'Groot', pages: 120, lossless: true }; pdfInGen++; pdfRender(); });
    assert.strictEqual(await pg.$$eval('#pdf-pages .ppage', x => x.length), 120);
    await pg.click('text=Elke pagina een eigen PDF'); await pg.waitForTimeout(150);
    assert.deepStrictEqual((await P()).split, ['each', ''], 'nog niet gesplitst zonder bevestiging');
    assert.match(await pg.textContent('body'), /120 PDF's maken\?/);
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    assert.deepStrictEqual((await P()).split, ['each', '']);
    assert.strictEqual(await pg.locator('.pdfscr main button:disabled:not(#pdf-signature-use):not(#pdf-signature-delete):visible').count(), 0);

    // tekst naar PDF
    await pg.evaluate(() => goBack()); await pg.click('#pdf-t-text'); assert.strictEqual(await pg.evaluate(() => current), 'pdftext');
    await pg.click('#pdf-text-make'); await pg.waitForTimeout(80);
    assert.strictEqual((await P()).text, undefined, 'lege tekst: niets');
    await pg.fill('#pdf-text-title', 'Reservering');
    await pg.fill('#pdf-text', 'Tafel voor 2 om 19:00');
    await pg.click('#pdf-text-make'); await pg.waitForTimeout(150);
    assert.deepStrictEqual((await P()).text, ['Reservering', 'Tafel voor 2 om 19:00']);
    assert.strictEqual(await pg.inputValue('#pdf-text'), '');
    // e-mailbestand kiezen
    await pg.click('#pdf-doc-pick'); await pg.waitForTimeout(150);
    assert.strictEqual((await P()).outs[0].name, 'Afspraak.pdf');
    assert.strictEqual(await pg.evaluate(() => current), 'pdftext', 'blijft op het taakscherm');

    // gemaakt: het overzicht toont de laatste drie, Alles tonen opent de hele lijst
    await pg.evaluate(() => goBack());
    const outN = (await P()).outs.length;
    assert.strictEqual(await pg.$$eval('#pdf-recent .trip', x => x.length), 3);
    assert.strictEqual(await pg.textContent('#pdf-recent-all'), 'Alles tonen (' + outN + ')');
    await pg.click('#pdf-recent .pdfrec-share[data-n="Afspraak.pdf"]'); await pg.waitForTimeout(80);
    assert.deepStrictEqual((await P()).shared, ['Afspraak.pdf'], 'direct delen vanaf het overzicht');
    await pg.click('#pdf-recent-all'); assert.strictEqual(await pg.evaluate(() => current), 'pdfmade');
    assert.strictEqual(await pg.$$eval('#pdf-out .trip', x => x.length), outN);
    assert.strictEqual(await pg.isVisible('#pdf-share-all'), true);
    const n = await pg.$$eval('#pdf-out .trip', x => x.length);
    await pg.click('#pdf-out .trip[data-n="Reservering.pdf"]'); await pg.waitForTimeout(200);
    await pg.click('#sheet-acts >> text=Bekijken'); await pg.waitForTimeout(150);
    assert.strictEqual((await P()).opened, 'Reservering.pdf');
    await pg.click('#pdf-out .trip[data-n="Reservering.pdf"]'); await pg.waitForTimeout(200);
    await pg.click('#sheet-acts >> text=Delen (bijv. mailen of WhatsApp)'); await pg.waitForTimeout(150);
    assert.deepStrictEqual((await P()).shared, ['Reservering.pdf']);
    await pg.click('#pdf-out .trip[data-n="Reservering.pdf"]'); await pg.waitForTimeout(200);
    await pg.click('#sheet-acts >> text=Opslaan als…'); await pg.waitForTimeout(150);
    assert.strictEqual((await P()).saved, 'Reservering.pdf');
    await pg.click('#pdf-out .trip[data-n="Reservering.pdf"]'); await pg.waitForTimeout(200);
    await pg.click('#sheet-acts >> text=Weghalen'); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.$$eval('#pdf-out .trip', x => x.length), n - 1);
    assert.strictEqual(await pg.$('#pdf-out .trip[data-n="Reservering.pdf"]'), null);
    await pg.click('#pdf-share-all'); await pg.waitForTimeout(80);
    assert.strictEqual((await P()).shared.length, n - 1);
    await pg.click('#pdf-save-all'); await pg.waitForTimeout(150);
    assert.match(await pg.textContent('body'), new RegExp('✓ ' + (n - 1) + ' opgeslagen in de map PDF'));
    // namen met rare tekens worden veilig getoond
    await pg.evaluate(() => { Android._pdf.outs.unshift({ name: '<img src=x onerror=alert(1)>.pdf', size: 10 }); pdfRender(); });
    assert.strictEqual(await pg.$$eval('#pdf-out img', x => x.length), 0, 'geen html uit bestandsnaam');
    if (scheme === 'dark') await pg.screenshot({ path: __dirname + '/shots/pdf-donker.png', fullPage: true });

    // foutmelding
    await pg.evaluate(() => onPdf({ error: 'Deze PDF is beveiligd met een wachtwoord' })); await pg.waitForTimeout(100);
    assert.match(await pg.textContent('body'), /beveiligd met een wachtwoord/);

    // terug: eerst naar het overzicht, dan naar home
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    assert.strictEqual(await pg.evaluate(() => current), 'pdf');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    assert.strictEqual(await pg.evaluate(() => current), 'home');

    // gedeeld vanuit een andere app: klaarliggend resultaat wordt verwerkt
    await pg.evaluate(() => { Android._pdf.imgs.push({ w: 10, h: 10, thumb: 'data:image/jpeg;base64,AAAA' }); Android._pdf.pending = JSON.stringify({ kind: 'images', count: 1, warn: '' }); openTool('pdf-share'); });
    await pg.waitForTimeout(250);
    assert.strictEqual(await pg.evaluate(() => current), 'pdfimg', 'gedeelde plaatjes: meteen op Plaatjes naar PDF');
    assert.strictEqual(await pg.$$eval('#pdf-imgs .pdfimg', x => x.length), 1);
    assert.match(await pg.textContent('body'), /1 plaatje toegevoegd/);
    assert.strictEqual((await P()).pending, '', 'maar één keer verwerkt');
    await pg.evaluate(() => { goBack(); goBack(); }); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.evaluate(() => current), 'home');
    await pg.evaluate(() => { Android._pdf.pending = JSON.stringify({ kind: 'done', made: ['Mail.pdf'] }); Android._pdf.outs.unshift({ name: 'Mail.pdf', size: 1 }); onPdf(JSON.parse(Android.pdfPending())); });
    await pg.waitForTimeout(200);
    assert.strictEqual(await pg.evaluate(() => current), 'home', 'klaar terwijl je elders bent: niet wegspringen');
    await pg.evaluate(() => show('pdf')); await pg.waitForTimeout(150);
    assert.ok(await pg.$('#pdf-out .trip[data-n="Mail.pdf"]'), 'resultaat staat klaar');
    assert.ok(await pg.$('#pdf-recent .trip[data-n="Mail.pdf"]'), 'bovenaan bij recent gemaakt');

    // zoeken vindt het
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    await pg.evaluate(() => show('search')); await pg.fill('#gs-q', 'pdf splitsen'); await pg.waitForTimeout(600);
    assert.ok((await pg.$$eval('#gs-res .gi', x => x.map(e => e.innerText))).some(t => /PDF/.test(t)), 'zoeken vindt PDF');

    assert.deepStrictEqual(errs, []);
    await pg.close();
  }
  console.log('errors []');
  await b.close();
})().catch(e => { console.error(e); console.log('errors [' + e.message + ']'); process.exit(1); });
