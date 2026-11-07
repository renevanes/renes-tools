const { chromium } = require('playwright');
/* Alles uit de backup-map terugzetten (gewone onderdelen, in één keer): doorzoeken, overzicht per onderdeel,
   toestemming voor contacten, alles terugzetten met bevestiging, uitkomst; en vanaf een nieuwe installatie zonder
   versleutelde backup rechtstreeks hierheen. */
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
    const vis = (pg, s) => pg.isVisible(s);

    // ---------- vanuit Alles back-uppen → Terugzetten ----------
    let pg = await open('bestaand', () => { Android._wa.info.dest = true; });
    await pg.evaluate(() => { show('backup'); show('bkrestore'); }); await pg.waitForTimeout(150);
    ok(await vis(pg, '#bklos') && await vis(pg, '#bklos-scan') && !(await vis(pg, '#bklos-go')), 'kaart met alleen Doorzoeken');
    await pg.click('#bklos-scan'); await pg.waitForTimeout(300);
    const res = await pg.textContent('#bklos-res');
    ok(/USB-stick Rene/.test(res), 'map genoemd: ' + res);
    ok(/23 nieuw/.test(res) && /12 nieuw/.test(res) && /14 nieuw/.test(res), 'nieuw per onderdeel: ' + res);
    ok(/Staat er al/.test(res), 'muziek staat er al');
    ok(/Toestemming voor contacten nodig/.test(res) && await vis(pg, '#bklos-perm'), 'contacten: toestemming nodig');
    ok(/Niet terug te zetten/.test(res) && /standaard-sms-app/.test(res), 'sms: uitleg waarom niet');
    ok(await vis(pg, '#bklos-go') && /49 nieuw/.test(await pg.textContent('#bklos-go')), 'één knop: ' + await pg.textContent('#bklos-go'));
    ok((await pg.textContent('#bklos-scan')) === 'Opnieuw doorzoeken', 'daarna: opnieuw doorzoeken');
    await pg.screenshot({ path: __dirname + '/shots/herstel-los-scan-' + scheme + '.png', fullPage: true });
    // Toestemming geven: na terugkomen in de app opnieuw doorzoeken
    await pg.click('#bklos-perm'); await pg.waitForTimeout(100);
    ok((await pg.evaluate(() => Android._hs.fixed || [])).includes('contacts'), 'toestemming voor contacten gevraagd');
    const scans = await pg.evaluate(() => Android._los.scans);
    await pg.evaluate(() => onResumeApp()); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => Android._los.scans) === scans + 1, 'terug in de app: opnieuw doorzocht');
    // Alles terugzetten: eerst bevestigen
    await pg.click('#bklos-go'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal-title')) === 'Alles terugzetten?' && !(await pg.evaluate(() => Android._los.applied)), 'eerst bevestigen');
    await pg.click('#modal-ok'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => Android._los.applied) === 1, 'teruggezet');
    const done = await pg.textContent('#bklos-res');
    ok(/23 toegevoegd/.test(done) && /Stond er al/.test(done) && /toestemming voor contacten/.test(done), 'uitkomst per onderdeel: ' + done);
    ok(!(await vis(pg, '#bklos-go')), 'knop weg na terugzetten');
    await pg.screenshot({ path: __dirname + '/shots/herstel-los-klaar-' + scheme + '.png', fullPage: true });
    await pg.close();

    // ---------- fout bij doorzoeken ----------
    pg = await open('bestaand', () => { Android._wa.info.dest = true; Android._los.scanError = 'Backup-map niet leesbaar'; });
    await pg.evaluate(() => { show('backup'); show('bkrestore'); }); await pg.waitForTimeout(150);
    await pg.click('#bklos-scan'); await pg.waitForTimeout(250);
    ok(/niet leesbaar/.test(await pg.textContent('#bklos-res')) && !(await vis(pg, '#bklos-go')), 'fout zichtbaar, geen terugzetknop');
    await pg.close();

    // ---------- nieuwe installatie zonder versleutelde backup ----------
    pg = await open('nieuw', () => { Android._hs.archives = []; Android._hs.plain = { kinds: ['settings', 'notes', 'route'] }; });
    await pg.click('#hs-pick'); await pg.waitForTimeout(300);
    const pl = await pg.textContent('#hs-plain');
    ok(/instellingen/i.test(pl) && /notities/.test(pl) && /routes/.test(pl), 'gevonden onderdelen genoemd: ' + pl);
    await pg.click('#hs-plain-btn'); await pg.waitForTimeout(350);
    ok(await pg.evaluate(() => current) === 'bkrestore', 'naar Terugzetten');
    ok(await vis(pg, '#bklos-go'), 'meteen doorzocht: knop Alles terugzetten');
    await pg.close();
    console.log(scheme, 'errors', errs);
  }
  await b.close();
})();
