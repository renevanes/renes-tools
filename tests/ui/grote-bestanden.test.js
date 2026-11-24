const { chromium } = require('playwright');
/* Grote bestanden één keer bewaren: grootte per onderdeel in de uitkomst, de regel Grote bestanden, de schakelaar
   (uitzetten vraagt eerst) en de knop voor oude, volledige backups (telt eerst, vraagt, zegt iets over de prullenbak). */
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const errs = [];
    const ok = (c, m) => { if (!c) errs.push('✗ ' + m); };
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    await pg.click('#tile-backup'); await pg.waitForTimeout(200);

    // ---------- uitkomst van de laatste backup: grootte per onderdeel en de grote bestanden ----------
    await pg.evaluate(() => {
      Android._wa.info.dest = true; Android._sec.on = true;
      Android._bk.last = { t: Date.now(), auto: true, archive: 'backup-2026-11-24_0330.rtb',
        app: { ok: true, count: 812, b: 190e6, o: 2.1e6 }, notes: { ok: true, count: 4, b: 21000, o: 9000 }, sms: { ok: true, count: 1234 },
        blok: { nieuw: 3, nieuwBytes: 1.8e6, hergebruikt: 640, hergebruiktBytes: 188e6, archief: 4.2e6, opgeruimd: { count: 2, bytes: 3e6 }, controle: { count: 41, kapot: 0 } } };
      enterBackup();
    });
    await pg.waitForTimeout(300);
    // De grootte staat zichtbaar, zonder uitklappen
    ok(await pg.isVisible('#bk-grootte') && /Grootte: \d+ MB aan gegevens; deze backup nam \d+ MB extra ruimte in \(\d+ MB aan grote bestanden stond er al\)/.test(await pg.textContent('#bk-grootte')), 'grootte zichtbaar: ' + await pg.textContent('#bk-grootte'));
    await pg.click('#bk-parts-res details summary'); await pg.waitForTimeout(150);
    const res = await pg.$$eval('#bk-parts-res .bkres', x => x.map(e => e.innerText.replace(/\s+/g, ' ')));
    ok(res.some(r => /Alle appgegevens.*812 · \d+ MB \(2 MB nieuw\)/.test(r)), 'grootte per onderdeel, met wat er nieuw bij kwam ' + JSON.stringify(res));
    ok(res.some(r => /Grote bestanden.*3 nieuw \(\d+ MB\), 640 al bewaard \(\d+ MB\)/.test(r)), 'regel Grote bestanden');
    ok(res.some(r => /Archief zelf.*\d+ MB/.test(r)), 'grootte van het archief zelf');
    ok(res.some(r => /Ongebruikt opgeruimd.*2 grote bestanden/.test(r)), 'ongebruikte grote bestanden opgeruimd');
    ok(res.some(r => /Controle.*41 grote bestanden nagelezen/.test(r)), 'controle');
    ok(res.some(r => /^Sms-berichten ✓ 1\.234$/.test(r.trim())), 'zonder grootte (leesbare backup of oud): geen puntje ' + JSON.stringify(res));
    await pg.evaluate(() => { Android._bk.last.blok.controle = { count: 3, kapot: 1 }; bkRenderLast(bkState(true)); });
    await pg.waitForTimeout(100);
    const res2 = await pg.$$eval('#bk-parts-res .bkres .bad', x => x.map(e => e.innerText));
    ok(res2.some(r => /beschadigd; de volgende backup bewaart het opnieuw/.test(r)), 'beschadigd groot bestand zichtbaar als fout ' + JSON.stringify(res2));
    // Apart bewaren mislukt (staat dan in het archief zelf) en opruimen dat moet wachten: zichtbaar
    await pg.evaluate(() => { Object.assign(Android._bk.last.blok, { fout: 'x', opgeruimd: { count: 0, bytes: 0, later: 'onleesbaar' } }); bkRenderLast(bkState(true)); });
    await pg.waitForTimeout(100);
    const res3 = await pg.$$eval('#bk-parts-res .bkres', x => x.map(e => e.innerText.replace(/\s+/g, ' ')));
    ok(res3.some(r => /in het archief zelf/.test(r)), 'apart bewaren mislukt: gemeld ' + JSON.stringify(res3));
    ok(res3.some(r => /Ongebruikt opgeruimd.*niet te lezen; er gaat niets weg/.test(r)), 'opruimen wacht: gemeld');
    await pg.evaluate(() => { Android._bk.last.blok.opgeruimd = { count: 0, bytes: 0, later: true }; bkRenderLast(bkState(true)); });
    await pg.waitForTimeout(100);
    ok((await pg.$$eval('#bk-parts-res .bkres', x => x.map(e => e.innerText))).some(r => /Volgende nacht verder/.test(r)), 'opruimen gaat de volgende nacht verder');
    await pg.evaluate(() => { Android._bk.last.blok.opgeruimd = { count: 0, bytes: 0, later: 'bezig' }; bkRenderLast(bkState(true)); });
    await pg.waitForTimeout(100);
    ok((await pg.$$eval('#bk-parts-res .bkres', x => x.map(e => e.innerText))).some(r => /nog een backup gemaakt/.test(r)), 'opruimen wacht op een backup die nog geschreven wordt');

    // Een backup van vóór deze versie (geen grootte): dat staat er, in plaats van niets
    await pg.evaluate(() => { const l = Android._bk.last; Android._bk.last = { t: l.t, auto: true, archive: l.archive, app: { ok: true, count: 812 }, sms: { ok: true, count: 3 } }; bkRenderLast(bkState(true)); });
    await pg.waitForTimeout(100);
    ok(await pg.isVisible('#bk-grootte') && /na de volgende backup/.test(await pg.textContent('#bk-grootte')), 'nog geen grootte: zegt wanneer die komt');
    // Ruimtegebruik: Versleuteld uitgesplitst
    await pg.evaluate(() => { Android.backupSpace(); }); await pg.waitForTimeout(400);
    const spc = await pg.$$eval('#bk-space .spc', x => x.map(e => e.innerText.replace(/\s+/g, ' ')));
    ok(spc.some(r => /^Backups 3 bestanden ?9 MB$/.test(r)) && spc.some(r => /^Grote bestanden \(één keer bewaard\) 812 bestanden ?\d+ MB$/.test(r)) && spc.some(r => /^Oude, volledige backups 4 bestanden ?\d+ MB$/.test(r)), 'Versleuteld uitgesplitst ' + JSON.stringify(spc));
    ok(/oude, volledige backups haal je weg/.test(await pg.textContent('#bk-space')) && /prullenbak/.test(await pg.textContent('#bk-space')), 'verwijst naar weghalen en zegt dat de prullenbak niet meetelt');

    // ---------- instellingen: schakelaar en oude backups ----------
    await pg.click('#bk-row-set'); await pg.waitForTimeout(200);
    ok(await pg.isChecked('#bk-blok'), 'grote bestanden apart: standaard aan');
    ok(/één keer in Versleuteld\/Grote bestanden/.test(await pg.textContent('#bk-blok-txt')), 'uitleg onder de schakelaar');
    ok(await pg.isVisible('#bk-blok-let') && /neem die map mee/.test(await pg.textContent('#bk-blok-let')), 'zichtbaar: een backup heeft ook de map Grote bestanden nodig');
    ok(!(await pg.isVisible('#bk-oud')), 'geen oude backups: geen knop');
    // Uitzetten vraagt eerst; Annuleren laat hem aan
    await pg.click('#bk-blok'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#modal') && /elke backup/.test(await pg.textContent('#modal-title')), 'uitzetten vraagt eerst');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(150);
    ok(await pg.isChecked('#bk-blok') && await pg.evaluate(() => Android._sec.blokken) === true, 'annuleren: blijft aan');
    await pg.click('#bk-blok'); await pg.waitForTimeout(150); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(!(await pg.isChecked('#bk-blok')) && await pg.evaluate(() => Android._sec.blokken) === false, 'bevestigd: uit');
    ok(/alles opnieuw/.test(await pg.textContent('#bk-blok-txt')) && !(await pg.isVisible('#bk-blok-let')), 'uit: uitleg, geen opmerking over de map');
    await pg.click('#bk-blok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._sec.blokken) === true && !(await pg.isVisible('#modal.on')), 'weer aan zonder vraag');

    await pg.evaluate(() => { Android._sec.oud = { count: 14, bytes: 2.73e9 }; bkRenderSecure(bkState(true)); });
    await pg.waitForTimeout(100);
    ok(await pg.isVisible('#bk-oud') && /14 oude backups waarin alles volledig zit \(samen \d+(,\d)? GB\)/.test(await pg.textContent('#bk-oud-txt')), 'oude backups: aantal en grootte (' + await pg.textContent('#bk-oud-txt') + ')');
    await pg.click('#bk-oud-btn'); await pg.waitForTimeout(300);
    ok(await pg.isVisible('#modal') && /14 oude backups weghalen/.test(await pg.textContent('#modal-title')), 'eerst tellen, dan vragen');
    const tekst = await pg.textContent('#modal-text');
    ok(/vangnet/.test(tekst) && /prullenbak/.test(tekst) && /30 dagen/.test(tekst), 'vraag noemt het vangnet en de prullenbak');
    ok(/Van .*2026.* tot en met .*2026/.test(tekst), 'vraag noemt van wanneer tot wanneer (' + tekst + ')');
    await pg.screenshot({ path: __dirname + '/shots/grote-bestanden-' + scheme + '.png' });
    await pg.click('#modal-ok'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => Android._sec.oudWeg) === 14, 'weggehaald');
    ok(/14 oude backups weggehaald/.test(await pg.textContent('#toast')), 'melding na weghalen');
    ok(!(await pg.isVisible('#bk-oud')), 'daarna geen knop meer');
    // Niets meer te doen: melding, geen vraag
    await pg.evaluate(() => { Android._sec.oud = { count: 0, bytes: 0 }; bkOudOpruimen(); });
    await pg.waitForTimeout(300);
    ok(!(await pg.isVisible('#modal.on')) && /geen oude backups/.test(await pg.textContent('#toast')), 'niets weg te halen: alleen een melding');
    // Nog niet zeker: de nieuwste backup mist grote bestanden
    await pg.evaluate(() => { Android._sec.oud = { count: 0, bytes: 0, onvolledig: true }; bkOudOpruimen(); });
    await pg.waitForTimeout(300);
    ok(!(await pg.isVisible('#modal.on')) && /mist grote bestanden/.test(await pg.textContent('#toast')), 'nieuwste backup onvolledig: niets weg, uitleg');

    console.log(scheme, 'errors', errs);
    await pg.close();
  }
  await b.close();
})();
