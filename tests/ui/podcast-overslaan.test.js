// Podcasts: reclame, begin en einde overslaan. Per podcast (⋯ → Overslaan), algemeen bij Instellingen van Podcasts,
// en de melding met Terug als de speler iets oversloeg.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);

    // ---- Per podcast: via ⋯ ----
    await pg.evaluate(() => pdOpen(Android._pc.top[0])); await pg.waitForTimeout(400);
    await pg.click('#pd-menu'); await pg.waitForTimeout(100);
    const acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent.trim()));
    ok(acts.includes('Overslaan: reclame, begin en einde'), 'in ⋯: Overslaan');
    await pg.click('#sheet-acts button:has-text("Overslaan")'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'podskip'), 'scherm Overslaan open');
    ok(await pg.textContent('#pk-pod') === 'Nieuws van Vandaag', 'naam van de podcast');
    ok(/32 van de 64/.test(await pg.textContent('#pk-ch')), 'zegt bij hoeveel afleveringen er hoofdstukken zijn: ' + await pg.textContent('#pk-ch'));
    ok(await pg.evaluate(() => $('#pk-reclame').value === '' && /\(aan\)/.test($('#pk-reclame').options[0].textContent)), 'reclame: standaard (aan)');
    ok(await pg.evaluate(() => $('#pk-intro').value === '0' && $('#pk-intro').options[0].textContent === 'Niet'), 'begin: standaard niet');
    await pg.selectOption('#pk-intro', '30'); await pg.selectOption('#pk-outro', '90'); await pg.selectOption('#pk-reclame', '0');
    let ov = await pg.evaluate(() => JSON.parse(Android.podOverslaan()));
    ok(JSON.stringify(ov.per.p1) === JSON.stringify({ reclame: false, intro: 30, outro: 90 }), 'bewaard per podcast: ' + JSON.stringify(ov.per.p1));
    ok(await pg.evaluate(() => [...$('#pk-outro').options].find(o => o.value === '90').textContent) === '1 min 30 s', 'tijd leesbaar');
    await pg.selectOption('#pk-intro', '0'); await pg.selectOption('#pk-outro', '0'); await pg.selectOption('#pk-reclame', '');
    ov = await pg.evaluate(() => JSON.parse(Android.podOverslaan()));
    ok(!ov.per.p1, 'alles terug op standaard: niets meer bewaard voor deze podcast');
    // Een waarde die niet in de lijst staat (bijv. uit een backup) blijft zichtbaar
    await pg.evaluate(() => { Android._ov.per.p1 = { intro: 25 }; pkRender(); });
    ok(await pg.evaluate(() => $('#pk-intro').value === '25'), 'eigen waarde blijft staan');
    await pg.evaluate(() => { Android._ov.per = {}; });
    // Zonder hoofdstukken: eerlijk zeggen dat reclame herkennen niet lukt
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'podcast'), 'terug naar de podcast');
    await pg.evaluate(() => pdOpen(Android._pc.top[1])); await pg.waitForTimeout(400);
    await pg.evaluate(() => pkOpen()); await pg.waitForTimeout(200);
    ok(/geen hoofdstukken/.test(await pg.textContent('#pk-ch')), 'zonder hoofdstukken: dat staat erbij');

    // ---- Werkt de reclamefilter hier? Regel onder de kop, schaartje op Luisteren ----
    await pg.evaluate(() => pdOpen(Android._pc.top[0])); await pg.waitForTimeout(400);
    ok(/Reclame wordt overgeslagen/.test(await pg.textContent('#pd-rc')) && await pg.evaluate(() => $('#pd-rc').classList.contains('aan')), 'podcast met hoofdstukken: "Reclame wordt overgeslagen"');
    await pg.evaluate(() => pdOpen(Android._pc.top[1])); await pg.waitForTimeout(400);
    ok(/Reclame overslaan kan hier niet$/.test(await pg.textContent('#pd-rc')), 'zonder hoofdstukken: dat staat er ook');
    await pg.evaluate(() => { Android._ov.per.p1 = { reclame: false }; pdOpen(Android._pc.top[0]); }); await pg.waitForTimeout(400);
    ok(/staat uit/.test(await pg.textContent('#pd-rc')), 'zelf uitgezet: "staat uit"');
    await pg.evaluate(() => { Android._ov.per = {}; Android._pc.subs = [Object.assign({}, Android._pc.top[0]), Object.assign({}, Android._pc.top[1])]; show('podcasts'); pcSetTab('listen'); pcRenderSubs(); });
    await pg.waitForTimeout(300);
    const tegels = await pg.$$eval('#pc-subs .pcsub', x => x.map(b => ({ rc: !!b.querySelector('.pcrc'), l: b.getAttribute('aria-label') })));
    ok(tegels.length === 2 && tegels[0].rc && !tegels[1].rc, 'schaartje alleen bij de podcast waar het werkt: ' + JSON.stringify(tegels.map(x => x.rc)));
    ok(/reclame wordt overgeslagen/.test(tegels[0].l) && !/reclame/.test(tegels[1].l), 'ook voor de schermlezer');
    await pg.evaluate(() => { delete Android._pc.subs[0].hs; }); // nog niet bijgewerkt na de update: geen schaartje (onbekend), geen fout

    // ---- Algemeen ----
    await pg.evaluate(() => show('podset')); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#ps-ov') && await pg.getAttribute('#ps-reclame', 'aria-checked') === 'true', 'Instellingen: reclame overslaan staat aan');
    ok(/Werkt nu bij 1 van de 2 podcasts die je volgt/.test(await pg.textContent('#ps-ov-n')), 'overzicht: bij hoeveel het werkt: ' + await pg.textContent('#ps-ov-n'));
    await pg.click('#ps-reclame');
    ok(await pg.getAttribute('#ps-reclame', 'aria-checked') === 'false' && await pg.evaluate(() => Android._ov.reclame === false), 'uit te zetten');
    ok(/Werkt nu bij 0 van de 2/.test(await pg.textContent('#ps-ov-n')), 'overal uit: bij geen enkele');
    await pg.fill('#ps-woorden', 'partner, aanbieding'); await pg.press('#ps-woorden', 'Tab');
    ok(await pg.evaluate(() => Android._ov.woorden) === 'partner, aanbieding', 'extra woorden bewaard');
    await pg.evaluate(() => pdOpen(Android._pc.top[0])); await pg.waitForTimeout(400);
    await pg.evaluate(() => pkOpen()); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => /\(uit\)/.test($('#pk-reclame').options[0].textContent)), 'per podcast: standaard volgt de algemene keuze (uit)');
    await pg.evaluate(() => { Android._ov.reclame = true; });

    // ---- Melding na overslaan, met Terug ----
    await pg.evaluate(() => pcPlayEp(pdFeed.items[0], pdFeed.pod)); await pg.waitForTimeout(400);
    await pg.evaluate(() => { const st = Android._pc.st; st.skip = { van: 60500, tot: 180000, waarom: 'reclame', titel: 'Reclame', key: st.ep.key, t: Date.now() }; pcPoll(true); });
    await pg.waitForTimeout(150);
    ok(await pg.isVisible('#undo') && /Reclame overgeslagen \(2 minuten\)/.test(await pg.textContent('#undo-t')), 'melding: ' + await pg.textContent('#undo-t'));
    ok(await pg.textContent('#undo-b') === 'Terug', 'met Terug');
    await pg.click('#undo-b'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._pc.terug) === 60500, 'Terug gaat naar het begin van het stuk');
    await pg.evaluate(() => pcPoll(true)); await pg.waitForTimeout(100);
    ok(!(await pg.evaluate(() => $('#undo').classList.contains('on'))), 'geen tweede melding voor hetzelfde stuk');
    // Het einde: geen Terug (er speelt al iets anders)
    await pg.evaluate(() => { const st = Android._pc.st; st.skip = { van: 1700000, tot: 1745000, waarom: 'outro', titel: '', key: 'andere', t: Date.now() + 1 }; pcPoll(true); });
    await pg.waitForTimeout(150);
    ok(/Einde overgeslagen \(45 seconden\)/.test(await pg.textContent('#toast')), 'einde: gewone melding');
    // Oud (de app was weg): geen melding meer
    await pg.evaluate(() => { $('#toast').textContent = ''; const st = Android._pc.st; st.skip = { van: 0, tot: 30000, waarom: 'intro', key: st.ep.key, t: Date.now() - 60000 }; pkGezien = 0; pcPoll(true); });
    await pg.waitForTimeout(150);
    ok(await pg.textContent('#toast') === '', 'van lang geleden: geen melding');
    // Daarna weer de gewone knop Ongedaan maken
    await pg.evaluate(() => undoToast('x', () => {}));
    ok(await pg.textContent('#undo-b') === 'Ongedaan maken', 'Ongedaan maken blijft gewoon werken');

    // ---- Engels ----
    await pg.evaluate(() => { rgSave({ lang: 'en' }); pdOpen(Android._pc.top[0]); }); await pg.waitForTimeout(400);
    await pg.evaluate(() => pkOpen()); await pg.waitForTimeout(250);
    ok(/includes chapters with 32 of its 64/.test(await pg.textContent('#pk-ch')), 'Engels: ' + await pg.textContent('#pk-ch'));
    ok(await pg.evaluate(() => $('#pk-intro').options[0].textContent === 'Off' && $('#pk-outro').options[3].textContent === '15 seconds'), 'Engels: tijden');
    await pg.evaluate(() => rgSave({ lang: 'nl' }));
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
  process.exit(errs.length ? 1 : 0);
})();
