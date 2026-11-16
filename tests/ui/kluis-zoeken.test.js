// Documentenkluis: zoeken in de tekst van documenten (Java leest de tekst; de interface toont treffers met een stukje).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => {
      Android._kl.open = true;
      Android._kl.docs = [{ id: 'k1abcdef', name: 'Paspoort.pdf', mime: 'application/pdf', size: 8200, t: Date.now() - 864e5 }, { id: 'k2abcdef', name: 'Scan 12.pdf', mime: 'application/pdf', size: 9000, t: Date.now() }, { id: 'k3abcdef', name: 'Foto.jpg', mime: 'image/jpeg', size: 100, t: Date.now() }];
      Android._kl.tekst = { k1abcdef: 'Koninkrijk der Nederlanden paspoort', k2abcdef: 'Opstalverzekering woonhuis. Het polisnummer is 12345 <b>x</b> en de premie is 20 euro.' };
      window.__testKluisLezen = 1; show('kluis');
    });
    await pg.waitForTimeout(150);
    if (await pg.evaluate(() => typeof tsZichtbaar === 'function' && tsZichtbaar())) { await pg.click('#ts-open'); await pg.waitForFunction(() => $('#kl-open').style.display === 'block'); await pg.waitForTimeout(30); }
    ok(await pg.evaluate(() => Android._klTekstStart === 1 && /Tekst lezen om in te zoeken: 1 van 1/.test($('#kl-tekst').textContent)), scheme + ': bij openen leest de kluis op de achtergrond tekst, zichtbaar: ' + await pg.evaluate(() => $('#kl-tekst').textContent));
    await pg.waitForTimeout(500);
    ok(await pg.evaluate(() => $('#kl-tekst').textContent === ''), scheme + ': klaar: de regel is weg');
    const lijst = () => pg.$$eval('#kl-list .trip', x => x.map(e => ({ naam: e.querySelector('b').textContent.trim(), stuk: (e.querySelector('.kl-stuk') || {}).textContent || '' })));
    await pg.fill('#kl-search', 'po'); await pg.waitForTimeout(500);
    ok(await pg.evaluate(() => !(Android._klZoek || []).length), scheme + ': twee letters: nog niet in de tekst zoeken');
    await pg.fill('#kl-search', 'polisnummer'); await pg.waitForTimeout(150);
    ok((await lijst()).length === 0, scheme + ': tijdens het typen nog geen treffer op naam');
    await pg.waitForTimeout(450);
    let l = await lijst();
    ok(l.length === 1 && /Scan 12/.test(l[0].naam) && /polisnummer is 12345/.test(l[0].stuk), scheme + ': treffer in de tekst met het stukje erbij: ' + JSON.stringify(l));
    ok(await pg.evaluate(() => !$('#kl-list .kl-stuk b') && /<b>x<\/b>/.test($('#kl-list .kl-stuk').textContent)), scheme + ': tekst uit het document blijft tekst (geen HTML)');
    ok(/Van één document is de tekst nog niet gelezen/.test(await pg.evaluate(() => $('#kl-tekst').textContent)), scheme + ': zegt dat van één document de tekst nog niet gelezen is');
    await pg.fill('#kl-search', 'paspoort'); await pg.waitForTimeout(500);
    l = await lijst();
    ok(l.length === 1 && /Paspoort/.test(l[0].naam), scheme + ': naam en tekst samen: één keer in de lijst');
    await pg.fill('#kl-search', ''); await pg.waitForTimeout(150);
    ok((await lijst()).length === 3 && (await lijst()).every(x => !x.stuk), scheme + ': zoekveld leeg: alles weer, zonder stukjes');
    // de app even uit beeld (een document bekijken): stukjes weg, de zoekvraag blijft, en bij terugkomen staan de treffers er weer
    await pg.fill('#kl-search', 'polisnummer'); await pg.waitForTimeout(500);
    await pg.evaluate(() => onPauseApp()); await pg.waitForTimeout(450);
    ok(await pg.evaluate(() => !$('#kl-list .kl-stuk') && !Object.keys(klT.hits).length && $('#kl-search').value === 'polisnummer'), scheme + ': uit beeld: geen stukjes meer in de pagina, de zoekvraag staat er nog');
    await pg.evaluate(() => onResumeApp()); await pg.waitForTimeout(500);
    ok((await lijst()).some(x => /polisnummer is 12345/.test(x.stuk)), scheme + ': terug: de treffer staat er weer');
    // weg van het scherm: niets van de inhoud blijft in de pagina staan
    await pg.fill('#kl-search', 'premie'); await pg.waitForTimeout(500);
    ok((await lijst()).some(x => /premie/.test(x.stuk)), scheme + ': (er staat een stukje in beeld)');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !$('#kl-list').children.length && !Object.keys(klT.hits).length && $('#kl-search').value === ''), scheme + ': weg van de kluis: lijst, stukjes en zoekvraag zijn weg uit de pagina');
    await pg.evaluate(() => { Android._kl.open = true; show('kluis'); }); await pg.waitForTimeout(150);
    if (await pg.evaluate(() => typeof tsZichtbaar === 'function' && tsZichtbaar())) { await pg.click('#ts-open'); await pg.waitForFunction(() => $('#kl-open').style.display === 'block'); await pg.waitForTimeout(30); }
    // op slot: niets blijft hangen
    await pg.fill('#kl-search', 'premie'); await pg.waitForTimeout(500);
    await pg.evaluate(() => { Android._kl.open = false; window.klVanzelf = () => false; klRender(); }); // echt op slot (zonder vanzelf weer openen)
    ok(await pg.evaluate(() => !$('#kl-list').children.length && klT.q === '' && !Object.keys(klT.hits).length && $('#kl-tekst').textContent === ''), scheme + ': op slot: treffers en stukjes zijn vergeten');
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
