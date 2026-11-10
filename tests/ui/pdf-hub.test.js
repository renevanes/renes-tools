// PDF-overzicht (web/src/schermen/27-pdf.html, 37-pdf.js): geen lange lijst maar PDF openen, recent gemaakt, tegels per
// taak en Meer. Elke tegel opent een eigen scherm (hdr sm) en terug gaat naar het overzicht. Wat van buiten komt (Delen →
// PDF maken, de viewer) landt op het juiste scherm. Licht en donker, 360 en 390 px, en in het Engels geen Nederlands.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const path = require('path');
const SHOTS = process.env.PDF_SHOTS || path.join(__dirname, 'shots');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const cur = () => pg.evaluate(() => current);
    // Vier gemaakte bestanden: het overzicht toont er drie
    await pg.evaluate(() => {
      const now = Date.now();
      Android._pdf.outs.push({ name: 'Contract ondertekend.pdf', size: 140000, t: now - 60000 }, { name: 'Bonnetjes oktober.pdf', size: 820000, t: now - 3600e3 },
        { name: 'Reservering.pdf', size: 12000, t: now - 3 * 86400e3 }, { name: 'Oud.pdf', size: 1000, t: now - 5 * 86400e3 });
      openTool('pdf');
    });
    await pg.waitForTimeout(200);
    assert.equal(await cur(), 'pdf');

    // Compact: past in ongeveer anderhalf scherm, ook op 360 px, zonder horizontaal schuiven
    for (const w of [390, 360]) {
      await pg.setViewportSize({ width: w, height: 844 }); await pg.waitForTimeout(100);
      const m = await pg.evaluate(() => ({ h: document.documentElement.scrollHeight, wide: document.documentElement.scrollWidth > innerWidth + 1 }));
      // Ongeveer anderhalf scherm (844 px) plus een rij tegels voor Vergelijken; de lettertypen in CI lopen iets anders af,
      // vandaar wat ruimte op 360 px
      assert.ok(m.h < (w === 390 ? 1420 : 1470), w + ' px: overzicht is ' + m.h + ' px hoog');
      assert.equal(m.wide, false, w + ' px: geen horizontaal schuiven');
      assert.equal(await pg.$eval('#pdf-kluis-row small', e => e.scrollWidth <= e.clientWidth + 1), true, w + ' px: ondertitel van de kluis niet afgekapt');
      await pg.screenshot({ path: path.join(SHOTS, 'pdf-after-hub-' + w + '-' + scheme + '.png'), fullPage: true });
    }
    await pg.setViewportSize({ width: 390, height: 844 });
    assert.equal(await pg.$$eval('#pdf-recent .trip', x => x.length), 3, 'drie recente');
    assert.equal(await pg.textContent('#pdf-recent-all'), 'Alles tonen (4)');
    assert.equal(await pg.getAttribute('#pdf-recent .trip b', 'translate'), 'no', 'bestandsnaam is gegeven');
    assert.match(await pg.textContent('#pdf-recent'), /vandaag/);
    for (const sel of ['#pdf-recent-all', '#pdf-recent .pdfrec-share', '#pdf-t-split', '#pdf-def-btn'])
      assert.ok((await pg.$eval(sel, e => e.getBoundingClientRect().height)) >= 44, sel + ': raakvlak van minstens 44 px');

    // Tegels: echte knoppen met een naam, in een logische volgorde; elke tegel opent zijn scherm en terug gaat terug
    const tiles = [['Bewerken en ondertekenen', 'pdfedit'], ['Plaatjes naar PDF', 'pdfimg'], ['Splitsen', 'pdfsplit'], ['Tekst naar PDF', 'pdftext'],
      ['Formulieren invullen', 'formulieren'], ['ID-kopie afschermen', 'idcopy'], ['Vergelijken', 'pdfcmp']];
    assert.deepEqual(await pg.$$eval('#pdf-tiles .tile', x => x.map(e => e.tagName + ':' + e.querySelector('b').textContent)), tiles.map(([n]) => 'BUTTON:' + n), 'volgorde');
    for (const [name, scr] of tiles) {
      const tile = pg.getByRole('button', { name, exact: true });
      assert.ok(await tile.getAttribute('aria-describedby'), name + ': hint als beschrijving');
      await tile.click(); await pg.waitForTimeout(80);
      assert.equal(await cur(), scr, name + ' opent ' + scr);
      if (scr.startsWith('pdf')) {
        assert.equal(await pg.$eval('#s-' + scr + ' .hdr', e => e.classList.contains('sm') && !!e.querySelector('.back') && !!e.querySelector('h1')), true, scr + ': detailkop met terug en titel');
        assert.equal(await pg.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, scr + ': geen horizontaal schuiven');
      }
      await pg.evaluate(() => goBack()); await pg.waitForTimeout(80);
      assert.equal(await cur(), 'pdf', name + ': terug naar het overzicht');
    }
    // Toetsenbord: Tab gaat van tegel naar tegel
    await pg.focus('#pdf-t-edit'); await pg.keyboard.press('Tab');
    assert.equal(await pg.evaluate(() => document.activeElement.id), 'pdf-t-img');
    await pg.keyboard.press('Enter'); await pg.waitForTimeout(80);
    assert.equal(await cur(), 'pdfimg', 'Enter op een tegel');
    await pg.evaluate(() => goBack());

    // Meer: documentenkluis (terug komt hier) en standaard PDF-app
    await pg.click('#pdf-kluis-row'); await pg.waitForTimeout(80);
    assert.equal(await cur(), 'kluis');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(80);
    assert.equal(await cur(), 'pdf', 'terug van de kluis naar PDF');
    await pg.click('#pdf-def-btn');
    assert.equal(await pg.evaluate(() => Android._pdf.madeDefault), 1);
    await pg.click('#pdf-recent-all'); assert.equal(await cur(), 'pdfmade');
    assert.equal(await pg.$$eval('#pdf-out .trip', x => x.length), 4, 'Gemaakt toont alles');
    await pg.evaluate(() => { goBack(); goBack(); });
    assert.equal(await cur(), 'home');

    // Van buiten: gedeelde plaatjes → Plaatjes naar PDF
    await pg.evaluate(() => { Android._pdf.imgs.push({ w: 10, h: 10, thumb: 'data:image/jpeg;base64,AAAA' }); Android._pdf.pending = JSON.stringify({ kind: 'images', count: 1, warn: '' }); openTool('pdf-share'); });
    await pg.waitForTimeout(100);
    assert.equal(await cur(), 'pdfimg');
    assert.equal(await pg.$$eval('#pdf-imgs .pdfimg', x => x.length), 1);
    await pg.evaluate(() => goBack()); assert.equal(await cur(), 'pdf');
    assert.match(await pg.textContent('#pdf-t-img-d'), /1 plaatje klaar/, 'tegel toont wat klaarstaat');
    await pg.evaluate(() => { Android.pdfImagesClear(); show('home'); });
    // Gedeelde PDF of de viewer ("Pagina's eruit halen") → Splitsen
    await pg.evaluate(() => { Android._pdf.info = { name: 'Huurcontract', pages: 3, lossless: true, revision: 'r-split' }; Android._pdf.pending = JSON.stringify({ kind: 'pdf', to: 'split', ...Android._pdf.info }); openTool('pdf-share'); });
    await pg.waitForTimeout(100);
    assert.equal(await cur(), 'pdfsplit');
    assert.match(await pg.textContent('#pdf-split-info'), /Huurcontract · 3 pagina's/);
    // Vanaf Splitsen ook bewerken (gedeelde PDF, of de viewer van een oudere APK)
    await pg.click('#pdf-split-edit');
    await pg.waitForFunction(() => pdfEd.revision === 'r-split' && !pdfEd.loading && pdfEd.meta[0]);
    assert.equal(await cur(), 'pdfedit');
    assert.equal(await pg.textContent('#pdf-edit-document'), 'Huurcontract');
    assert.equal(await pg.isVisible('#pdf-edit-work'), true);
    // Komt er dan van buiten een andere PDF binnen terwijl er niet bewaarde toevoegingen zijn: zeggen dat ze weg zijn
    await pg.fill('#pdf-edit-text', 'Getekend'); await pg.click('#pdf-edit-add-text');
    assert.equal(await pg.evaluate(() => pdfEditDirty()), true);
    // Viewer "Tekst of handtekening toevoegen" → de bewerker, meteen open
    await pg.evaluate(() => { show('home'); Android._pdf.info = { name: 'Offerte', pages: 2, lossless: true, revision: 'r-edit' }; Android._pdf.pending = JSON.stringify({ kind: 'pdf', to: 'edit', ...Android._pdf.info }); openTool('pdf-share'); });
    await pg.waitForFunction(() => pdfEd.revision === 'r-edit' && !pdfEd.loading && pdfEd.meta[0]);
    assert.equal(await cur(), 'pdfedit');
    assert.equal(await pg.isVisible('#modal'), true, 'melding over de verloren toevoegingen');
    assert.match(await pg.textContent('#modal-text'), /Huurcontract waren nog niet bewaard/);
    assert.equal(await pg.isVisible('#modal-cancel'), false, 'alleen OK: de vorige PDF is al vervangen');
    await pg.click('#modal-ok');
    assert.equal(await pg.evaluate(() => pdfEd.items.length), 0);
    assert.equal(await pg.isVisible('#pdf-edit-work'), true, 'bewerker staat open');
    assert.equal(await pg.textContent('#pdf-edit-document'), 'Offerte');
    // Een oudere APK zonder "to": gedeelde PDF naar Splitsen
    await pg.evaluate(() => { show('home'); Android._pdf.info = { name: 'Oud', pages: 1, lossless: true, revision: 'r-oud' }; Android._pdf.pending = JSON.stringify({ kind: 'pdf', ...Android._pdf.info }); openTool('pdf-share'); });
    assert.equal(await cur(), 'pdfsplit');
    // Mail gedeeld → PDF gemaakt: het overzicht, met het bestand bovenaan
    await pg.evaluate(() => { show('home'); Android._pdf.outs.unshift({ name: 'Mail.pdf', size: 2000, t: Date.now() }); Android._pdf.pending = JSON.stringify({ kind: 'done', made: ['Mail.pdf'] }); openTool('pdf-share'); });
    assert.equal(await cur(), 'pdf');
    assert.equal(await pg.getAttribute('#pdf-recent .trip', 'data-n'), 'Mail.pdf');
    // Fout: blijft waar je bent, met de melding
    await pg.evaluate(() => { show('pdftext'); Android._pdf.pending = JSON.stringify({ error: 'Dit bestand kan niet gebruikt worden' }); openTool('pdf-share'); });
    assert.equal(await cur(), 'pdftext');
    assert.match(await pg.textContent('#toast'), /kan niet gebruikt worden/);
    // Terug in de app op een PDF-scherm met iets klaarliggends (onResumeApp): ook naar het juiste scherm
    await pg.evaluate(() => { Android._pdf.pending = JSON.stringify({ kind: 'images', count: 1, warn: '' }); onResumeApp(); });
    assert.equal(await cur(), 'pdfimg');
    assert.equal(await pg.evaluate(() => Android._pdf.pending), '', 'maar één keer verwerkt');
    // Zonder iets klaarliggends: niet wegspringen
    await pg.evaluate(() => { show('pdfsplit'); onResumeApp(); openTool('pdf-share'); });
    assert.equal(await cur(), 'pdfsplit');
    // Android-terug (goBack) vanaf een taakscherm dat van buiten geopend werd: eerst het overzicht
    await pg.evaluate(() => goBack()); assert.equal(await cur(), 'pdf');
    await pg.evaluate(() => goBack()); assert.equal(await cur(), 'home');

    // "Net gemaakt" hoort bij het scherm waar de klus begon, ook als je intussen verder gaat
    await pg.evaluate(() => { pdfLast = null; show('pdf'); }); await pg.click('#pdf-t-img'); await pg.click('#pdf-img-pick'); await pg.waitForTimeout(100);
    await pg.fill('#pdf-img-name', 'Kassabon');
    await pg.click('#pdf-img-make'); await pg.evaluate(() => { goBack(); show('pdfsplit'); });
    await pg.waitForFunction(() => !pdfBusy);
    assert.match(await pg.textContent('.pdfres[data-scr="pdfimg"]'), /Kassabon\.pdf/, 'net gemaakt op Plaatjes');
    assert.equal(await pg.textContent('.pdfres[data-scr="pdfsplit"]'), '', 'niet op Splitsen');
    await pg.click('#pdf-split-edit'); await pg.waitForFunction(() => pdfEd.meta[0] && !pdfEd.loading);
    await pg.fill('#pdf-edit-text', 'Akkoord'); await pg.click('#pdf-edit-add-text'); await pg.fill('#pdf-edit-name', 'Akkoord');
    await pg.click('#pdf-edit-save'); await pg.evaluate(() => goBack());
    assert.equal(await cur(), 'pdf');
    await pg.waitForFunction(() => !pdfBusy);
    assert.match(await pg.textContent('.pdfres[data-scr="pdfedit"]'), /Akkoord\.pdf/, 'net gemaakt in de bewerker');
    assert.equal(await pg.getAttribute('#pdf-recent .trip', 'data-n'), 'Akkoord.pdf', 'en bovenaan het overzicht');
    await pg.evaluate(() => show('home'));

    // Overal zoeken vindt de taakschermen en de tegels (ook naar ID-kopie en Formulieren, zonder hun inhoud te indexeren)
    const zoek = async q => { await pg.evaluate(() => { gsIndex = null; show('search'); }); await pg.fill('#gs-q', ''); await pg.fill('#gs-q', q); await pg.waitForTimeout(450); return pg.$$eval('#gs-res .gi', x => x.map(e => e.innerText)); };
    for (const [q, want] of [['plaatjes naar pdf', /Plaatjes naar PDF/], ['paspoort', /ID-kopie afschermen/], ['formulier', /Formulieren invullen/], ['e-mail naar pdf', /Tekst naar PDF/], ['pdf bekijken', /PDF bekijken/]])
      assert.ok((await zoek(q)).some(t => want.test(t)), 'zoeken naar "' + q + '" vindt ' + want);
    await zoek('paspoort');
    await pg.locator('#gs-res .gi', { hasText: 'ID-kopie afschermen' }).first().click(); await pg.waitForTimeout(150);
    assert.equal(await cur(), 'idcopy', 'treffer opent de ID-kopie');
    await pg.evaluate(() => goBack()); assert.equal(await cur(), 'search', 'terug naar zoeken');
    assert.equal(await pg.$eval('#s-idcopy h1', e => e.textContent), 'ID-kopie afschermen', 'tegel en scherm zeggen hetzelfde');
    await pg.evaluate(() => show('home'));

    if (scheme === 'light') for (const s of ['pdfedit', 'pdfimg', 'pdfsplit', 'pdftext', 'pdfmade']) {
      await pg.evaluate(s => show(s), s); await pg.waitForTimeout(100);
      await pg.screenshot({ path: path.join(SHOTS, 'pdf-after-' + s + '.png'), fullPage: true });
    }
    assert.deepEqual(errs, []);
    await pg.close();
  }

  // Engels: overzicht en taakschermen zonder Nederlands (bestandsnamen zijn gegevens)
  const pg = await b.newPage({ viewport: { width: 360, height: 780 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
  await pg.evaluate(() => show('setregio')); await pg.selectOption('#rg-lang', 'en'); await pg.waitForTimeout(150);
  assert.equal(await pg.evaluate(() => TAAL.on), true, 'Engels aan');
  await pg.evaluate(() => { Android._pdf.outs.push({ name: 'Bonnetjes.pdf', size: 1000, t: Date.now() }, { name: 'Huur.pdf', size: 1000, t: Date.now() - 864e5 }); });
  const NL = /(^|[^\p{L}])(de|het|een|niet|voor|met|naar|kies|tik|nog|geen|van|je|bij|uit|als|tekst|plaatjes|splitsen|bewerken|ondertekenen|invullen|afschermen|gemaakt|recent|alles|tonen|delen|standaard|kluis|vandaag|handtekening|kiezen|maken)($|[^\p{L}])/iu;
  for (const s of ['pdf', 'pdfedit', 'pdfimg', 'pdfsplit', 'pdftext', 'pdfmade']) {
    await pg.evaluate(s => show(s), s); await pg.waitForTimeout(120);
    const dutch = await pg.evaluate(([id, src]) => {
      const re = new RegExp(src, 'iu'), out = [];
      const tw = document.createTreeWalker(document.getElementById('s-' + id), NodeFilter.SHOW_TEXT);
      for (let n = tw.nextNode(); n; n = tw.nextNode()) {
        const p = n.parentElement; if (!p || p.closest('[translate="no"]') || !p.getClientRects().length) continue;
        const v = n.data.replace(/\s+/g, ' ').trim(); if (v && re.test(v)) out.push(v);
      }
      for (const el of document.querySelectorAll('#s-' + id + ' [aria-label], #s-' + id + ' [placeholder]')) {
        if (el.closest('[translate="no"]')) continue;
        for (const a of ['aria-label', 'placeholder']) { const v = (el.getAttribute(a) || '').replace(/Bonnetjes\.pdf|Huur\.pdf/g, ''); if (v && re.test(v)) out.push('@' + a + ': ' + v); }
      }
      return out;
    }, [s, NL.source]);
    assert.deepEqual(dutch, [], s + ' in het Engels zonder Nederlands');
  }
  await pg.evaluate(() => show('pdf'));
  assert.equal(await pg.textContent('#pdf-t-edit b'), 'Edit and sign');
  assert.match(await pg.textContent('#pdf-recent'), /today/);
  await pg.evaluate(() => show('pdfmade')); assert.equal(await pg.textContent('#s-pdfmade h1'), 'Your PDFs');
  await pg.evaluate(() => show('pdftext')); assert.equal(await pg.textContent('#s-pdftext b[translate="no"]'), 'PDF maken', 'naam in het deelmenu van Android blijft Nederlands');
  await pg.evaluate(() => show('pdf'));
  await pg.screenshot({ path: path.join(SHOTS, 'pdf-after-hub-en.png'), fullPage: true });
  assert.deepEqual(errs, []);
  await pg.close();
  console.log('PDF-overzicht: hoogte, tegels, terug, van buiten, zoeken, Engels, licht en donker; errors []');
  await b.close();
})().catch(e => { console.error(e); console.log('errors [' + e.message + ']'); process.exit(1); });
