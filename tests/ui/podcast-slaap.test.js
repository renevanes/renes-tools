// Podcasts: afgespeeld met de slaaptimer. De kaart op Luisteren vraagt wat je echt hoorde (aangevinkt = weer niet
// beluisterd, vanaf waar je nog wakker was), na vanzelf terugzetten zie je wat er gebeurde, en op Instellingen van
// Podcasts staan de keuze en de lijst. Titels zijn gegevens: niet als HTML.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const min = 60000, nu = Date.now();
  const regel = (n, titel, van, st, extra) => Object.assign({ key: 'e000000000000000' + n, van, d: 60 * min, t: nu - (5 - n) * 30 * min, sessie: 1, st, auto: false, gezien: false,
    ep: { key: 'e000000000000000' + n, title: titel, url: 'https://example.org/' + n + '.mp3', dur: 3600 }, pod: { id: 'p1', title: 'Nieuws van Vandaag' } }, extra || {});
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);

    // Niets afgespeeld met de slaaptimer: geen kaart
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => $('#pc-slaap').hidden), 'niets bijgehouden: geen kaart');

    // Twee afleveringen liepen af terwijl de slaaptimer liep
    await pg.evaluate(([a, c]) => { Android._slp.lijst = [a, c]; Android._pc.prog[a.key] = { p: 0, d: a.d, done: true }; Android._pc.prog[c.key] = { p: 0, d: c.d, done: true }; pcSetTab('listen'); pcRenderListen(); },
      [regel(1, 'Eerste <b>vet</b>', 23 * min, 'open'), regel(2, 'Tweede', 0, 'open')]);
    await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !$('#pc-slaap').hidden), 'kaart op Luisteren');
    ok(/2 afleveringen liepen af/.test(await pg.textContent('#pc-slaap .hint')), 'zegt hoeveel: ' + await pg.textContent('#pc-slaap .hint'));
    ok(await pg.evaluate(() => $('#pc-slaap').querySelectorAll('input[data-key]:checked').length === 2), 'standaard allebei aangevinkt');
    ok(await pg.evaluate(() => !$('#pc-slaap b').querySelector('b') && /Eerste <b>vet<\/b>/.test($('#pc-slaap').textContent)), 'titel als tekst, niet als HTML');
    const tekst = await pg.textContent('#pc-slaap');
    ok(/vanaf 23 min/.test(tekst) && /vanaf het begin/.test(tekst), 'zegt waar hij weer klaarstaat: ' + tekst.replace(/\s+/g, ' ').slice(0, 200));
    ok(/Nieuws van Vandaag/.test(tekst), 'met de podcast erbij');
    // Niets aangevinkt: de knop doet niets
    await pg.evaluate(() => $('#pc-slaap').querySelectorAll('input[data-key]').forEach(i => { i.checked = false; i.dispatchEvent(new Event('change')); }));
    ok(await pg.evaluate(() => $('#pc-slaap-terug').disabled), 'niets aangevinkt: knop uit');
    // Alleen de eerste niet gehoord
    await pg.evaluate(() => { const i = $('#pc-slaap').querySelector('input[data-key="e0000000000000001"]'); i.checked = true; i.dispatchEvent(new Event('change')); });
    await pg.click('#pc-slaap-terug'); await pg.waitForTimeout(150);
    const prog = await pg.evaluate(() => Android._pc.prog);
    ok(prog.e0000000000000001.done === false && prog.e0000000000000001.p === 23 * min, 'aangevinkt: weer niet beluisterd, op de plek waar je nog wakker was');
    ok(prog.e0000000000000002.done === true, 'niet aangevinkt: blijft beluisterd');
    ok(await pg.evaluate(() => Android._slp.lijst.map(r => r.st).join()) === 'terug,gehoord', 'oordeel bewaard');
    ok(await pg.evaluate(() => $('#pc-slaap').hidden), 'daarna is de kaart weg');
    ok(/niet beluisterd/i.test(await pg.textContent('#toast')), 'melding: ' + await pg.textContent('#toast'));

    // Vanzelf teruggezet: de kaart zegt wat er gebeurde, met Toch gehoord
    await pg.evaluate((r) => { Android._slp.lijst.push(r); Android._pc.prog[r.key] = { p: 12 * 60000, d: r.d, done: false }; pcRenderListen(); }, regel(3, 'Derde', 12 * min, 'terug', { auto: true }));
    await pg.waitForTimeout(100);
    ok(/Vanzelf als niet beluisterd gemarkeerd/.test(await pg.textContent('#pc-slaap')), 'vanzelf teruggezet: dat staat erbij');
    await pg.click('#pc-slaap-toch'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._pc.prog.e0000000000000003.done === true && $('#pc-slaap').hidden), 'Toch gehoord: weer beluisterd, kaart weg');

    // Instellingen van Podcasts: de keuze en de lijst
    await pg.evaluate(() => show('podset')); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => $('#ps-slp-wijze').value === 'vragen' && $('#ps-slp-plek').value === 'wakker'), 'standaard: vragen, vanaf waar je nog wakker was');
    ok(/vraagt Podcasts/.test(await pg.textContent('#ps-slp-uitleg')), 'uitleg bij vragen');
    await pg.selectOption('#ps-slp-wijze', 'vanzelf'); await pg.selectOption('#ps-slp-plek', 'begin');
    ok(await pg.evaluate(() => Android._slp.wijze === 'vanzelf' && Android._slp.plek === 'begin'), 'keuze bewaard');
    ok(/vanzelf weer klaar, vanaf het begin/.test(await pg.textContent('#ps-slp-uitleg')), 'uitleg past zich aan: ' + await pg.textContent('#ps-slp-uitleg'));
    await pg.evaluate((r) => { Android._slp.lijst.push(r); Android._pc.prog[r.key] = { p: 0, d: r.d, done: true }; }, regel(4, 'Vierde', 0, 'open'));
    await pg.selectOption('#ps-slp-wijze', 'uit');
    ok(await pg.evaluate(() => Android._slp.lijst[3].st === 'gehoord' && Android._pc.prog.e0000000000000004.done === true), 'uit: wat nog open stond telt als gehoord');
    await pg.evaluate(() => { Android._slp.lijst.pop(); });
    ok(await pg.evaluate(() => $('#ps-slp-plek').disabled), 'uit: de plek doet er niet toe');
    await pg.selectOption('#ps-slp-wijze', 'vragen'); await pg.selectOption('#ps-slp-plek', 'wakker');
    const rijen = await pg.$$eval('#ps-slp-lijst .pcslp-r', x => x.length);
    ok(rijen === 3, 'lijst met alle drie: ' + rijen);
    // In de lijst: de tweede (gehoord) toch als niet beluisterd
    await pg.click('#ps-slp-lijst button[data-key="e0000000000000002"]'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._pc.prog.e0000000000000002.done === false && Android._slp.lijst[1].st === 'terug'), 'vanuit de lijst: niet beluisterd');
    ok(await pg.evaluate(() => $('#ps-slp-lijst button[data-key="e0000000000000002"]').textContent === 'Toch gehoord'), 'knop wordt Toch gehoord');
    const kleinste = await pg.$$eval('#ps-slp-lijst button.mini', x => Math.min(...x.map(e => e.getBoundingClientRect().height)));
    ok(kleinste >= 44, 'knoppen in de lijst minstens 44 px hoog: ' + kleinste);

    // In het menu ⋯ van Podcasts: naar de lijst
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(200);
    await pg.click('#pc-menu'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).includes('Afgespeeld met de slaaptimer'), 'in ⋯: Afgespeeld met de slaaptimer');
    await pg.click('#sheet-acts button:has-text("slaaptimer")'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'podset'), 'opent Instellingen van Podcasts');

    // Engels
    await pg.evaluate(() => { rgSave({ lang: 'en' }); Android._slp.lijst.forEach(r => { r.st = 'open'; }); show('podcasts'); pcSetTab('listen'); pcRenderListen(); }); await pg.waitForTimeout(300);
    ok(/Played during the sleep timer/.test(await pg.textContent('#pc-slaap h2')), 'Engels: kop');
    ok(/These 3 episodes finished/.test(await pg.textContent('#pc-slaap .hint')), 'Engels: ' + await pg.textContent('#pc-slaap .hint'));
    ok(/Eerste <b>vet<\/b>/.test(await pg.textContent('#pc-slaap')), 'Engels: titel blijft zoals hij is');
    await pg.evaluate(() => rgSave({ lang: 'nl' }));
    await pg.evaluate(() => { Android._slp.lijst = []; });
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
  process.exit(errs.length ? 1 : 0);
})();
