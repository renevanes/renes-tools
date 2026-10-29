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

    // ---------- overslaan; terug op het welkomscherm vraagt het ----------
    pg = await open('nieuw');
    await pg.click('#hs-skip'); await pg.waitForTimeout(150);
    ok(await cur(pg) === 'home' && await pg.evaluate(() => Android._hs.state) === 'overgeslagen', 'overslaan onthouden');
    await pg.close();
    pg = await open('nieuw');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal-title')) === 'Terugzetten overslaan?' && await cur(pg) === 'herstel', 'terug op het welkomscherm: eerst vragen');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(100);
    ok(await cur(pg) === 'herstel' && await pg.evaluate(() => Android._hs.state) === 'nieuw', 'niet overslaan: blijft staan');
    // Backups staan stil zolang er nog terug te zetten valt: Alles back-uppen legt het uit
    await pg.evaluate(() => { Android._wa.info.dest = true; show('backup'); }); await pg.waitForTimeout(150);
    ok(await vis(pg, '#bk-blocked') && /zet eerst je gegevens terug/.test(await pg.textContent('#bk-blocked')), 'Alles back-uppen: uitleg waarom backups stilstaan');
    await pg.click('#bk-blocked-btn'); await pg.waitForTimeout(150);
    ok(await cur(pg) === 'herstel', 'knop: naar Gegevens terugzetten');
    // Weg met een klaargezette backup: die wordt weggegooid
    await pg.click('#hs-same'); await pg.waitForTimeout(250);
    await pg.click('#hs-next'); await pg.waitForTimeout(250);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(500);
    const before = await pg.evaluate(() => Android._hs.cancelled || 0);
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._hs.cancelled || 0) === before + 1, 'weg van het scherm: klaargezette backup weggegooid');
    await pg.close();
    // Teruggezet zonder backup-sleutel: backups staan stil tot het wachtwoord er is
    pg = await open('klaar', () => { Android._wa.info.dest = true; Android._hs.needPw = true; });
    await pg.click('#tile-backup'); await pg.waitForTimeout(200);
    ok(/wachtwoord van je backup opnieuw/.test(await pg.textContent('#bk-blocked')) && /Wachtwoord instellen/.test(await pg.textContent('#bk-blocked-btn')), 'zonder backup-sleutel: uitleg en knop');
    await pg.click('#bk-plain-btn'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal-title')) === 'Zonder versleutelen verder?' && !(await pg.evaluate(() => Android._hs.plainOk)), 'zonder versleutelen: eerst uitdrukkelijk vragen');
    await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => Android._hs.plainOk) && !(await vis(pg, '#bk-blocked')), 'daarna lopen de backups weer (onversleuteld)');
    await pg.close();
    // Nieuwe installatie, maar er staan al gegevens (bijv. VPN of een app-code): toch eerst "Alles vervangen?"
    pg = await open('nieuw', () => { Android._hs.hasData = true; enterHerstel(); });
    ok(await vis(pg, '#hs-warn-top'), 'al gegevens: waarschuwing op het welkomscherm');
    await pg.click('#hs-pick'); await pg.waitForTimeout(300);
    await pg.click('#hs-next'); await pg.waitForTimeout(250);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(500);
    ok(/Stap 2 van 2/.test(await pg.evaluate(() => document.querySelector('#hs-bezig-t').textContent)) || await vis(pg, '#hs-klaar'), 'voortgang met stappen');
    await pg.click('#hs-apply'); await pg.waitForTimeout(200);
    ok((await pg.textContent('#modal-title')) === 'Alles vervangen?' && !(await pg.evaluate(() => Android._hs.applied)), 'al gegevens: eerst "Alles vervangen?"');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(100);
    // Weg terwijl de backup nog wordt gecontroleerd: daarna niets laten klaarstaan
    await pg.evaluate(() => { hsName = 'backup-2026-10-27_0330.rtb'; hsBusyStep(); Android.herstelOpen(hsName, 'goedwachtwoord'); show('home'); });
    const c0 = await pg.evaluate(() => Android._hs.cancelled || 0);
    await pg.waitForTimeout(500);
    ok(await pg.evaluate(() => Android._hs.cancelled || 0) === c0 + 1 && await cur(pg) === 'home', 'uitkomst na weggaan: meteen weggegooid');
    await pg.close();

    // ---------- bestaande installatie: niets vanzelf; vanuit Alles back-uppen met waarschuwing ----------
    pg = await open('bestaand', () => { Android._wa.info.dest = true; });
    ok(await cur(pg) === 'home', 'bestaande installatie: gewoon het startscherm');
    await pg.click('#tile-backup'); await pg.waitForTimeout(200);
    ok(/Nog niet compleet/.test(await pg.textContent('#bk-herstel-st')), 'niet compleet zonder versleutelen');
    ok(/Versleutelen✗/.test((await pg.textContent('#bk-herstel-list')).replace(/\s+/g, '')), 'versleutelen ontbreekt');
    ok(await pg.isChecked('#bk-parts input[data-p="app"]'), 'Alle appgegevens staat standaard aan');
    ok(/alleen mee in een versleutelde/.test(await pg.textContent('#bk-app-note')), 'zonder versleutelen: uitleg');
    ok(!(await pg.isChecked('#bk-auto')), 'zonder versleutelen niet vanzelf elke nacht (geen ongevraagde leesbare backups)');
    await pg.evaluate(() => { Android._sec.on = true; Android._bk.appLast = Date.now() - 6 * 36e5; Android._bk.appBytes = 3.2e9; enterBackup(); }); await pg.waitForTimeout(100);
    ok(await pg.isChecked('#bk-auto'), 'met versleutelen en een map: elke nacht standaard aan');
    ok(/Compleet voor herinstallatie/.test(await pg.textContent('#bk-herstel-st')), 'compleet voor herinstallatie');
    ok(/3,0 GB/.test(await pg.textContent('#bk-herstel-size')) && await vis(pg, '#bk-herstel-rotate'), 'uitleg over de groei per nacht, met knop om op te ruimen');
    ok(!(await pg.evaluate(() => Android._sec.rotate)), 'opruimen gaat niet vanzelf aan');
    await pg.click('#bk-herstel-rotate'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._sec.rotate) && !(await vis(pg, '#bk-herstel-rotate')), 'één tik: opruimen aan, knop weg');
    await pg.evaluate(() => { Android._bk.auto = false; enterBackup(); }); await pg.waitForTimeout(80);
    ok(!(await pg.isChecked('#bk-auto')), 'zelf uitgezet blijft uit');
    await pg.evaluate(() => { Android._bk.auto = null; enterBackup(); }); await pg.waitForTimeout(80);
    ok(/wachtwoorden, VPN/.test(await pg.textContent('#bk-app-note')), 'versleuteld: wat er mee gaat');
    await pg.screenshot({ path: __dirname + '/shots/herstel-backup-' + scheme + '.png', fullPage: true });
    await pg.click('#bk-herstel-btn'); await pg.waitForTimeout(150);
    ok(await cur(pg) === 'herstel' && await vis(pg, '#hs-warn-top'), 'handmatig: waarschuwing dat alles vervangen wordt');
    ok((await pg.textContent('#hs-skip')) === 'Annuleren' && await vis(pg, '#hs-same'), 'Annuleren, en zoeken in de huidige map');
    await pg.click('#hs-same'); await pg.waitForTimeout(250);
    await pg.click('#hs-next'); await pg.waitForTimeout(250);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(500);
    ok(await vis(pg, '#hs-warn') && /wachtwoord van je backups/.test(await pg.textContent('#hs-warn')), 'samenvatting met waarschuwing (ook wachtwoord en map)');
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

    // ---------- niet alles in de backup; iets loopt nog ----------
    pg = await open('nieuw', () => { Android._hs.missing = ['de meldingsgeschiedenis (meldingsgeschiedenis staat uit bij wat gaat er mee): de huidige blijft'];
      Android._hs.busy = 'Wacht even of stop eerst Slaappraat; daarna kun je alles terugzetten'; });
    await pg.click('#hs-pick'); await pg.waitForTimeout(300);
    await pg.click('#hs-next'); await pg.waitForTimeout(250);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(500);
    ok(await vis(pg, '#hs-missing') && /meldingsgeschiedenis/.test(await pg.textContent('#hs-missing')), 'samenvatting: wat er niet in zit');
    await pg.click('#hs-apply'); await pg.waitForTimeout(250);
    ok(await vis(pg, '#hs-klaar') && /Slaappraat/.test(await pg.textContent('#toast')) && !(await pg.evaluate(() => Android._hs.applied)), 'Slaappraat loopt: eerst stoppen');
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
  // ---------- ontsleutelen.html: wachtwoorden en sleutels standaard eruit (ook bij zip64) ----------
  const fs = require('fs'), path = require('path'), os = require('os'), cp = require('child_process');
  for (const f of ['rt-app-test.rtb', 'rt-app-test64.rtb']) {
    const rtb = path.join(os.tmpdir(), f), errs = [];
    if (!fs.existsSync(rtb)) { console.log(f, 'overgeslagen (eerst AppDataTest)', 'errors', []); continue; }
    const ok = (c, m) => { if (!c) errs.push('✗ ' + m); };
    const pg = await b.newPage({ acceptDownloads: true }); pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + path.join(__dirname, '..', '..', 'web', 'ontsleutelen.html'));
    const list = async geheim => {
      await pg.setInputFiles('#f', rtb); await pg.fill('#pw', 'geheim-wachtwoord');
      if (geheim) await pg.check('#geheim'); else await pg.uncheck('#geheim');
      const [dl] = await Promise.all([pg.waitForEvent('download', { timeout: 120000 }), pg.click('#go')]);
      const out = await dl.path();
      const r = cp.execFileSync('python3', ['-I', '-c', 'import zipfile,sys\nz=zipfile.ZipFile(sys.argv[1])\nprint(z.testzip())\nn=z.namelist()\nprint(len(n))\nprint("|".join(x for x in n if not x.startswith("Vul/")))\nprint(z.read("Notities/notities.json").decode())', out]).toString().split('\n');
      return { bad: r[0], count: +r[1], names: r[2].split('|'), notes: r[3], msg: await pg.textContent('#msg') };
    };
    const z = await list(false);
    ok(z.bad === 'None', f + ': geldige zip');
    ok(!z.names.some(n => n.startsWith('Appgegevens/geheim/')) && !z.names.includes('Appgegevens/instellingen.json'), f + ': geen geheimen ' + z.names);
    ok(z.names.includes('Notities/notities.json') && z.names.includes('Appgegevens/bestanden/notes.json') && z.notes === '[]', f + ': de rest is er nog');
    ok(/3 bestanden met wachtwoorden en sleutels weggelaten/.test(z.msg), f + ': melding ' + z.msg);
    const all = await list(true);
    ok(all.bad === 'None' && all.names.includes('Appgegevens/geheim/waarden.json') && all.count === z.count + 3, f + ': aangevinkt: alles (' + all.count + '/' + z.count + ')');
    await pg.close();
    console.log(f, 'errors', errs);
  }
  await b.close();
})();
