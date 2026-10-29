const { chromium } = require('playwright');
/* Gegevens terugzetten na herinstallatie: welkom bij een nieuwe installatie, map kiezen, backup kiezen, verkeerd en goed
   wachtwoord, terugzetten, en na de herstart het lijstje; overslaan; vanuit Alles back-uppen met waarschuwing. */
(async () => {
  const b = await chromium.launch();
  const page = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const errs = [];
    const ok = (c, m) => { if (!c) errs.push('✗ ' + m); };
    const open = async (state, init) => {
      const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
      pg.on('pageerror', e => errs.push(e.message));
      await pg.addInitScript(s => { window.__testHerstel = s; }, state);
      await pg.goto(page); await pg.waitForTimeout(400);
      if (init) await pg.evaluate(init);
      return pg;
    };
    const cur = pg => pg.evaluate(() => current);
    const vis = (pg, s) => pg.isVisible(s);

    // ---------- nieuwe installatie: welkom ----------
    let pg = await open('nieuw');
    ok(await cur(pg) === 'herstel', 'nieuwe installatie opent Gegevens terugzetten');
    ok(await vis(pg, '#hs-welkom') && !(await vis(pg, '#hs-warn-top')), 'welkom zonder waarschuwing');
    ok((await pg.textContent('#hs-skip')) === 'Overslaan', 'Overslaan-knop');
    ok(!(await vis(pg, '#modal.on')), 'geen "Wat is er nieuw" eroverheen');
    await pg.screenshot({ path: __dirname + '/shots/herstel-welkom-' + scheme + '.png', fullPage: true });
    await pg.click('#hs-pick'); await pg.waitForTimeout(300);
    ok(await vis(pg, '#hs-kies'), 'na map kiezen: backups');
    const radios = await pg.$$eval('input[name=hs-arc]', x => x.map(e => [e.value, e.checked]));
    ok(radios.length === 3, '3 backups');
    ok(radios.find(r => r[1])[0] === 'backup-2026-10-27_0330.rtb', 'nieuwste volledige voorgesteld (niet de afgebroken)');
    ok(/onvolledig/.test(await pg.textContent('#hs-list')), 'afgebroken backup als onvolledig te zien');
    await pg.screenshot({ path: __dirname + '/shots/herstel-kies-' + scheme + '.png', fullPage: true });
    await pg.click('#hs-next'); await pg.waitForTimeout(250);
    ok(await vis(pg, '#modal.on') && (await pg.getAttribute('#modal-input', 'type')) === 'password', 'wachtwoord gevraagd');
    await pg.fill('#modal-input', 'fout'); await pg.click('#modal-ok'); await pg.waitForTimeout(350);
    ok((await pg.textContent('#modal-title')) === 'Verkeerd wachtwoord', 'verkeerd wachtwoord: opnieuw gevraagd');
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(500);
    ok(await vis(pg, '#hs-klaar'), 'gecontroleerd: klaar om terug te zetten');
    ok(!(await vis(pg, '#hs-warn')), 'nieuwe installatie: geen waarschuwing over overschrijven');
    const sum = await pg.textContent('#hs-sum');
    ok(/1\.532 bestanden/.test(sum) && /4 kluisdocumenten/.test(sum) && /compleet/.test(sum), 'samenvatting: ' + sum);
    ok(await pg.evaluate(() => hsPwOld === ''), 'wachtwoord niet bewaard in de interface');
    await pg.screenshot({ path: __dirname + '/shots/herstel-klaar-' + scheme + '.png', fullPage: true });
    await pg.click('#hs-apply'); await pg.waitForTimeout(350);
    ok(!(await vis(pg, '#modal.on')), 'nieuwe installatie: geen extra bevestiging');
    ok(/Teruggezet/.test(await pg.textContent('#hs-bezig-t')), 'teruggezet, app start opnieuw');
    ok(await pg.evaluate(() => Android._hs.applied) === 'backup-2026-10-27_0330.rtb', 'de gekozen backup teruggezet');
    // Na de herstart (de app opent met open=herstel)
    await pg.evaluate(() => { show('home'); openTool('herstel'); }); await pg.waitForTimeout(150);
    ok(await vis(pg, '#hs-lijst'), 'na herstart: het lijstje');
    ok((await pg.$$('#hs-items .setrow')).length === 6, 'zes punten in het lijstje');
    ok(/4 te doen/.test(await pg.textContent('#hs-lijst-t')), 'telt wat nog moet: ' + await pg.textContent('#hs-lijst-t'));
    ok(await vis(pg, '#hs-models') && /Wacht op wifi/.test(await pg.textContent('#hs-models-t')), 'modellen worden opnieuw gedownload (wacht op wifi)');
    await pg.screenshot({ path: __dirname + '/shots/herstel-lijst-' + scheme + '.png', fullPage: true });
    await pg.click('#hs-items button[data-p="sms"]'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => Android._hs.fixed.includes('sms')), 'sms toestaan gevraagd');
    ok(/✓ Aan/.test(await pg.textContent('#hs-items .setrow[data-id="sms"]')), 'sms daarna met vinkje');
    await pg.click('#hs-items button[data-p="bio"]'); await pg.waitForTimeout(150);
    ok(await cur(pg) === 'setlock', 'vingerafdruk: naar App-slot');
    await pg.evaluate(() => show('herstel')); await pg.waitForTimeout(100);
    await pg.click('#hs-lijst button.big:not(.ghost)'); await pg.waitForTimeout(150);
    ok(await cur(pg) === 'home' && await pg.evaluate(() => Android._hs.done), 'Klaar: naar het startscherm');
    await pg.close();

    // ---------- overslaan ----------
    pg = await open('nieuw');
    await pg.click('#hs-skip'); await pg.waitForTimeout(150);
    ok(await cur(pg) === 'home' && await pg.evaluate(() => Android._hs.state) === 'overgeslagen', 'overslaan onthouden');
    await pg.close();

    // ---------- bestaande installatie: niets vanzelf; vanuit Alles back-uppen met waarschuwing ----------
    pg = await open('bestaand', () => { Android._wa.info.dest = true; });
    ok(await cur(pg) === 'home', 'bestaande installatie: gewoon het startscherm');
    await pg.click('#tile-backup'); await pg.waitForTimeout(200);
    ok(/Nog niet compleet/.test(await pg.textContent('#bk-herstel-st')), 'niet compleet zonder versleutelen');
    ok(/Versleutelen✗/.test((await pg.textContent('#bk-herstel-list')).replace(/\s+/g, '')), 'versleutelen ontbreekt');
    ok(await pg.isChecked('#bk-parts input[data-p="app"]'), 'Alle appgegevens staat standaard aan');
    ok(/alleen mee in een versleutelde/.test(await pg.textContent('#bk-app-note')), 'zonder versleutelen: uitleg');
    ok(await pg.isChecked('#bk-auto'), 'elke nacht staat standaard aan');
    await pg.evaluate(() => { Android._sec.on = true; Android._bk.appLast = Date.now() - 6 * 36e5; enterBackup(); }); await pg.waitForTimeout(100);
    ok(/Compleet voor herinstallatie/.test(await pg.textContent('#bk-herstel-st')), 'compleet voor herinstallatie');
    ok(/wachtwoorden, VPN/.test(await pg.textContent('#bk-app-note')), 'versleuteld: wat er mee gaat');
    await pg.screenshot({ path: __dirname + '/shots/herstel-backup-' + scheme + '.png', fullPage: true });
    await pg.click('#bk-herstel-btn'); await pg.waitForTimeout(150);
    ok(await cur(pg) === 'herstel' && await vis(pg, '#hs-warn-top'), 'handmatig: waarschuwing dat alles vervangen wordt');
    ok((await pg.textContent('#hs-skip')) === 'Annuleren' && await vis(pg, '#hs-same'), 'Annuleren, en zoeken in de huidige map');
    await pg.click('#hs-same'); await pg.waitForTimeout(250);
    await pg.click('#hs-next'); await pg.waitForTimeout(250);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(500);
    ok(await vis(pg, '#hs-warn'), 'samenvatting met waarschuwing');
    await pg.click('#hs-apply'); await pg.waitForTimeout(200);
    ok((await pg.textContent('#modal-title')) === 'Alles vervangen?', 'eerst uitdrukkelijk bevestigen');
    ok(!(await pg.evaluate(() => Android._hs.applied)), 'vóór bevestigen niets teruggezet');
    await pg.click('#modal-ok'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => Android._hs.applied) === 'backup-2026-10-27_0330.rtb', 'na bevestigen teruggezet');
    await pg.close();

    // ---------- geen versleutelde backup, wel losse onderdelen ----------
    pg = await open('nieuw', () => { Android._hs.archives = []; Android._hs.plain = { notes: true, settings: true }; });
    await pg.click('#hs-pick'); await pg.waitForTimeout(300);
    ok(await vis(pg, '#hs-none') && await vis(pg, '#hs-plain') && !(await vis(pg, '#hs-next')), 'geen versleutelde backup: losse onderdelen aangeboden');
    await pg.click('#hs-plain-btn'); await pg.waitForTimeout(200);
    ok(await cur(pg) === 'backup', 'naar Terugzetten bij Alles back-uppen');
    await pg.close();

    // ---------- oudere backup (zonder appgegevens) ----------
    pg = await open('nieuw', () => { Android._hs.old = true; });
    await pg.click('#hs-pick'); await pg.waitForTimeout(300);
    await pg.click('#hs-next'); await pg.waitForTimeout(250);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(400);
    ok(await vis(pg, '#hs-oud'), 'oudere backup herkend');
    await pg.click('#hs-oud button.big:not(.ghost)'); await pg.waitForTimeout(400);
    ok(await vis(pg, '#sheet.on') && /Notities terugzetten/.test(await pg.textContent('#sheet-acts')), 'losse onderdelen uit die backup');
    await pg.close();

    // ---------- terugzetten mislukt: niets veranderd ----------
    pg = await open('nieuw', () => { Android._hs.applyError = 'Terugzetten mislukt; er is niets veranderd (geen ruimte)'; });
    await pg.click('#hs-pick'); await pg.waitForTimeout(300);
    await pg.click('#hs-next'); await pg.waitForTimeout(250);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(500);
    await pg.click('#hs-apply'); await pg.waitForTimeout(350);
    ok(await vis(pg, '#hs-klaar') && /niets veranderd/.test(await pg.textContent('#toast')), 'fout: melding, terug bij de samenvatting');
    ok(await pg.evaluate(() => Android._hs.state) === 'nieuw', 'na een fout: nog steeds terug te zetten');
    await pg.close();

    console.log(scheme, 'errors', errs);
  }
  await b.close();
})();
