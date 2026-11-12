// Knop maan/zon in de kop van Nieuws, Podcasts, Radio en de speler: menu met nu donker of licht (bij een automatisch
// thema tijdelijk, tot de volgende wissel), weer automatisch, en de instellingen voor weergave.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); };
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const sheet = () => pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent.trim()));
    const tap = async label => { await pg.click('#sheet-acts button:has-text("' + label + '")'); await pg.waitForTimeout(120); };
    const dark = () => pg.evaluate(() => uiDark());

    // Zuiver: tijdelijk geldt tot de volgende wissel, alleen bij dezelfde keuze en (Systeem) dezelfde stand van de telefoon
    ok(await pg.evaluate(() => {
      const n = 1e12;
      return thTmpGeldig({ dark: true, mode: 'time', until: n + 1 }, 'time', n, false) && !thTmpGeldig({ dark: true, mode: 'time', until: n - 1 }, 'time', n, false)
        && !thTmpGeldig({ dark: true, mode: 'sun', until: n + 1 }, 'time', n, false) && !thTmpGeldig({ dark: true, mode: 'dark', until: n + 1 }, 'dark', n, false)
        && thTmpGeldig({ dark: true, mode: 'system', until: n + 1, sys: false }, 'system', n, false) && !thTmpGeldig({ dark: true, mode: 'system', until: n + 1, sys: false }, 'system', n, true)
        && thTmpTot('time', n + 5000, n) === n + 5000 && thTmpTot('system', 0, n) === n + 12 * 36e5;
    }), 'regels van tijdelijk andersom');

    // Op elk mediascherm staat de knop
    for (const [scr, id] of [['nieuws', '#nw-thema'], ['podcasts', '#pc-thema'], ['radio', '#rd-thema']]) {
      await pg.evaluate(s => show(s), scr); await pg.waitForTimeout(150);
      ok(await pg.isVisible(id) && (await pg.getAttribute(id, 'aria-label')) === 'Licht en donker', 'knop op ' + scr);
    }

    // Automatisch op tijd, nu licht: "Nu donker (tot …)" maakt het donker tot de volgende wissel
    await pg.evaluate(() => { const d = new Date(), hm = h => String((h + 24) % 24).padStart(2, '0') + ':00'; thSave({ mode: 'time', dark: hm(d.getHours() + 3), light: hm(d.getHours() - 3) }); store.set('themeTmp', null); thApply(true); show('podcasts'); });
    ok(!(await dark()), 'automatisch: nu licht');
    ok(await pg.evaluate(() => $('#pc-thema').dataset.icon === 'moon'), 'maan als het licht is');
    await pg.click('#pc-thema'); await pg.waitForTimeout(150);
    let acts = await sheet();
    ok(acts.some(a => /^Nu donker \(tot /.test(a)) && acts.includes('Instellingen voor weergave') && !acts.includes('Weer automatisch'), 'menu: nu donker, instellingen (' + acts + ')');
    await tap('Nu donker');
    ok(await dark() && await pg.evaluate(() => thCfg().mode === 'time' && thState.tmp && thState.next > Date.now()), 'tijdelijk donker, keuze blijft automatisch');
    ok(await pg.evaluate(() => $('#pc-thema').dataset.icon === 'sun' && document.documentElement.dataset.thema === 'donker'), 'zon als het donker is');
    ok(await pg.evaluate(() => /tot .*daarna weer automatisch/.test(thStatus())), 'stand zegt tot wanneer');
    // Na de wissel (tijd verstreken): vanzelf weer automatisch
    await pg.evaluate(() => { const x = store.get('themeTmp'); x.until = Date.now() - 1; store.set('themeTmp', x); thApply(true); });
    ok(!(await dark()) && await pg.evaluate(() => store.get('themeTmp', null) === null), 'verlopen: weer automatisch');
    // Weer automatisch met de hand
    await pg.evaluate(() => thNu(true)); await pg.click('#pc-thema'); await pg.waitForTimeout(150);
    acts = await sheet();
    ok(acts.includes('Weer automatisch') && acts.some(a => /^Nu licht/.test(a)), 'menu met weer automatisch (' + acts + ')');
    await tap('Weer automatisch');
    ok(!(await dark()) && !(await pg.evaluate(() => thState.tmp)), 'weer automatisch');

    // Vast thema: de knop wisselt het thema zelf
    await pg.evaluate(() => { thSave({ mode: 'light' }); show('radio'); }); await pg.waitForTimeout(100);
    await pg.click('#rd-thema'); await pg.waitForTimeout(150);
    acts = await sheet();
    ok(acts[0] === 'Donker', 'vast licht: Donker (' + acts + ')');
    await tap('Donker');
    ok(await dark() && await pg.evaluate(() => thCfg().mode === 'dark' && !thState.tmp), 'vast thema gewisseld');

    // Instellingen voor weergave, en terug
    await pg.click('#rd-thema'); await pg.waitForTimeout(150); await tap('Instellingen voor weergave');
    ok(await pg.evaluate(() => current === 'setlook'), 'naar de instellingen voor weergave');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current === 'radio'), 'terug naar Radio');

    // In de grote speler
    await pg.evaluate(() => { thSave({ mode: 'system' }); const f = Android._pcFeed('https://feeds.example.org/vandaag'); Android.podPlay(JSON.stringify({ ep: f.items[3], pod: f.pod })); pcPoll(true); });
    await pg.waitForFunction(() => pcSt.ep, null, { timeout: 5000 });
    await pg.evaluate(() => plOpen()); await pg.waitForTimeout(400);
    ok(await pg.isVisible('#pl-thema'), 'knop in de speler');
    await pg.click('#pl-thema'); await pg.waitForTimeout(150);
    acts = await sheet();
    ok(acts.some(a => /tot je telefoon wisselt/.test(a)), 'systeem: tot je telefoon wisselt (' + acts + ')');
    const was = await dark();
    await pg.click('#sheet-acts button >> nth=0'); await pg.waitForTimeout(120);
    ok((await dark()) === !was && await pg.evaluate(() => thCfg().mode === 'system' && thState.tmp), 'systeem: tijdelijk andersom');
    await pg.evaluate(() => { store.set('themeTmp', null); thApply(true); plClose(); });

    console.log(scheme, 'errors', errs);
    if (errs.length) process.exitCode = 1;
    await pg.close();
  }
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
