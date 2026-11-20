// Podcasts: vanzelf downloaden over een periode (standaard 2 weken, alle gevolgde podcasts) en weer verwijderen na 2 weken.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const pr = () => pg.evaluate(() => JSON.parse(Android.podDlPrefs()));

    // ---- Instellingen: standaard aan, 2 weken terug, na 2 weken weer weg ----
    await pg.evaluate(() => { Android._pc.subs = [Object.assign({}, Android._pc.top[0]), Object.assign({}, Android._pc.top[1])]; show('podset'); });
    await pg.waitForTimeout(250);
    ok(await pg.getAttribute('#ps-periode', 'aria-checked') === 'true', 'vanzelf downloaden staat standaard aan');
    ok(await pg.isVisible('#ps-dagen') && await pg.inputValue('#ps-dagen') === '14', 'standaard: de afgelopen 2 weken');
    ok(await pg.inputValue('#ps-bewaar') === '14', 'standaard: na 2 weken weer weg');
    ok(await pg.inputValue('#ps-welke') === 'alle' && await pg.inputValue('#ps-maxmb') === '2000', 'standaard: alle gevolgde podcasts, hooguit 2 GB');
    const uitleg = await pg.textContent('#ps-periode-u');
    ok(/2 podcasts doen mee/.test(uitleg) && /de afgelopen 2 weken/.test(uitleg) && /alleen via wifi/.test(uitleg), 'uitleg: ' + uitleg);
    await pg.selectOption('#ps-dagen', '7'); await pg.selectOption('#ps-bewaar', '30'); await pg.selectOption('#ps-maxmb', '5000');
    let p = await pr();
    ok(p.periodeDagen === 7 && p.bewaarDagen === 30 && p.maxMb === 5000, 'keuzes bewaard');
    ok(/de afgelopen week/.test(await pg.textContent('#ps-periode-u')), 'uitleg volgt de keuze');
    await pg.selectOption('#ps-bewaar', '0');
    ok((await pr()).bewaarDagen === 0, 'Nooit verwijderen kan ook');
    await pg.click('#ps-periode');
    ok((await pr()).periode === false && !(await pg.isVisible('#ps-dagen')), 'uit te zetten; de periode-keuzes gaan weg');
    ok(await pg.isVisible('#ps-bewaar'), 'verwijderen blijft in te stellen (ook voor vanzelf downloaden per podcast)');
    await pg.click('#ps-periode');
    ok((await pr()).periode === true, 'weer aan');

    // ---- Eén podcast: knop Vanzelf downloaden volgt de keuze "alle" ----
    await pg.evaluate(() => pdOpen(Android._pc.top[0])); await pg.waitForTimeout(400);
    ok(await pg.getAttribute('#pd-dl', 'aria-checked') === 'true', 'bij "alle podcasts": deze doet vanzelf mee');
    await pg.click('#pd-dl'); await pg.waitForTimeout(100);
    ok(await pg.getAttribute('#pd-dl', 'aria-checked') === 'false' && await pg.evaluate(() => Android._pc.subs[0].dlUit === true), 'deze ene uitzetten');
    await pg.click('#pd-dl'); await pg.waitForTimeout(100);
    ok(/van de afgelopen week, wordt gedownload \(via wifi\)/.test(await pg.textContent('#toast')), 'melding noemt de periode: ' + await pg.textContent('#toast'));
    // Alleen gekozen podcasts: dan telt de eigen keuze
    await pg.evaluate(() => { Android.podDlPeriode(JSON.stringify({ periodeWelke: 'gekozen' })); Android._pc.subs[0].dl = false; pdDlRow(true); });
    ok(await pg.getAttribute('#pd-dl', 'aria-checked') === 'false', 'bij "alleen gekozen": uit tot je hem aanzet');
    await pg.evaluate(() => show('podset')); await pg.waitForTimeout(200);
    ok(/Nog geen podcast doet mee/.test(await pg.textContent('#ps-periode-u')), 'niemand gekozen: dat staat erbij');

    // ---- Engels ----
    await pg.evaluate(() => { Android.podDlPeriode(JSON.stringify({ periodeWelke: 'alle', periodeDagen: 14 })); rgSave({ lang: 'en' }); psRender(); }); await pg.waitForTimeout(250);
    ok(/podcasts take part: what you haven't played from the last 2 weeks, newest first, only via Wi-Fi/.test(await pg.textContent('#ps-periode-u')), 'Engels: ' + await pg.textContent('#ps-periode-u'));
    await pg.evaluate(() => rgSave({ lang: 'nl' }));
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
  process.exit(errs.length ? 1 : 0);
})();
