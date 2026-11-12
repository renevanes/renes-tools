// Knop maan/zon in de kop van Nieuws, Podcasts en Radio (en "Licht en donker" in het menu van de speler): bij een
// automatisch thema tijdelijk donker of licht tot de volgende wissel, weer automatisch, en de instellingen voor weergave.
const { chromium } = require('playwright');
const path = require('path');
const URL = 'file://' + path.join(__dirname, 'ui', 'index.html');
(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); };
  for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(URL); await pg.waitForTimeout(300);
    const sheet = () => pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent.trim()));
    const tap = async label => { await pg.click('#sheet-acts button:has-text("' + label + '")'); await pg.waitForTimeout(120); };
    const dark = () => pg.evaluate(() => uiDark());
    const sys = scheme === 'dark';

    // Zuiver: tijdelijk geldt tot de volgende wissel, alleen bij dezelfde keuze en (Systeem) dezelfde stand van de telefoon
    ok(await pg.evaluate(() => {
      const n = 1e12;
      return thTmpGeldig({ dark: true, mode: 'time', until: n + 1 }, 'time', n, false) && !thTmpGeldig({ dark: true, mode: 'time', until: n - 1 }, 'time', n, false)
        && !thTmpGeldig({ dark: true, mode: 'sun', until: n + 1 }, 'time', n, false) && !thTmpGeldig({ dark: true, mode: 'dark', until: n + 1 }, 'dark', n, false)
        && thTmpGeldig({ dark: true, mode: 'system', until: n + 1, sys: false }, 'system', n, false) && !thTmpGeldig({ dark: true, mode: 'system', until: n + 1, sys: false }, 'system', n, true)
        && thTmpTot('time', n + 5000, n, true) === n + 5000;
    }), 'regels van tijdelijk andersom');
    // Zonder automatische wissel (Systeem, poolnacht): donker tot de volgende ochtend 07:00, licht tot 20:00
    ok(await pg.evaluate(() => { const now = Date.now(), d = thTmpTot('system', 0, now, true), l = thTmpTot('sun', 0, now, false);
      return fmtTime(d) === fmtTime(thAt(thKey(now), '07:00')) && d > now && d - now <= 864e5 && fmtTime(l) === fmtTime(thAt(thKey(now), '20:00')) && l > now && l - now <= 864e5; }), 'zonder wissel: ochtend of avond');

    // Op elk mediascherm staat de knop, met de juiste naam
    for (const [scr, id] of [['nieuws', '#nw-thema'], ['podcasts', '#pc-thema'], ['radio', '#rd-thema']]) {
      await pg.evaluate(s => show(s), scr); await pg.waitForTimeout(150);
      ok(await pg.isVisible(id) && (await pg.getAttribute(id, 'aria-label')) === 'Licht en donker' && (await pg.getAttribute(id, 'aria-haspopup')) === 'dialog', 'knop op ' + scr);
    }

    // Automatisch op tijd, nu licht: "Nu donker (tot 15:00)" maakt het donker tot de volgende wissel
    const hm = await pg.evaluate(() => { const h = new Date().getHours(), f = x => String((x + 24) % 24).padStart(2, '0') + ':00';
      thSave({ mode: 'time', dark: f(h + 3), light: f(h - 3) }); store.set('themeTmp', null); thApply(true); show('podcasts'); return f(h + 3); });
    ok(!(await dark()), 'automatisch: nu licht');
    ok(await pg.evaluate(() => $('#pc-thema').dataset.icon === 'moon'), 'maan als het licht is');
    await pg.click('#pc-thema'); await pg.waitForTimeout(150);
    let acts = await sheet();
    ok(acts.includes('Nu donker (tot ' + hm + ')') && acts.includes('Instellingen voor weergave') && !acts.includes('Weer automatisch'), 'menu: precies "Nu donker (tot ' + hm + ')" (' + acts + ')');
    await tap('Nu donker');
    ok(await dark() && await pg.evaluate(() => thCfg().mode === 'time' && thState.tmp && thState.next > Date.now()), 'tijdelijk donker, keuze blijft automatisch');
    ok(await pg.evaluate(() => $('#pc-thema').dataset.icon === 'sun' && document.documentElement.dataset.thema === 'donker'), 'zon als het donker is');
    ok(await pg.evaluate(h => thStatus() === 'Nu: donker tot ' + h + ', daarna weer automatisch', hm), 'stand: "tot ' + hm + '" (' + await pg.evaluate(() => thStatus()) + ')');
    ok(await pg.evaluate(() => { const o = JSON.parse(Android._themeCfg || '{}'); return Array.isArray(o.tmp) && o.tmp[0] === 1 && o.tmp[1] === thState.next; }), 'Java krijgt de tijdelijke stand (achtergrond bij het opstarten)');
    // Terwijl tijdelijk: alleen "Weer automatisch" (geen tweede knop die hetzelfde doet)
    await pg.click('#pc-thema'); await pg.waitForTimeout(150);
    acts = await sheet();
    ok(acts.length === 2 && acts[0] === 'Weer automatisch', 'tijdelijk: weer automatisch en instellingen (' + acts + ')');
    await pg.evaluate(() => closeSheet());

    // Opnieuw starten terwijl tijdelijk: meteen donker, geen flits
    await pg.reload(); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => window.thPre === true) && await dark(), 'herstart: meteen donker');

    // Na de wissel (tijd verstreken): vanzelf weer automatisch
    await pg.evaluate(() => { const x = store.get('themeTmp'); x.until = Date.now() - 1; store.set('themeTmp', x); thApply(true); });
    ok(!(await dark()) && await pg.evaluate(() => store.get('themeTmp', null) === null), 'verlopen: weer automatisch');
    // Een andere keuze in Instellingen: de tijdelijke stand vervalt
    await pg.evaluate(() => { thNu(true); thSave({ dark: '23:00' }); });
    ok(await pg.evaluate(() => store.get('themeTmp', null) === null && !thState.tmp), 'andere tijden: tijdelijk weg');
    await pg.evaluate(() => { thNu(true); thSave({ amoled: true }); });
    ok(await pg.evaluate(() => thState.tmp), 'zwart (AMOLED) aan: tijdelijk blijft');
    await pg.evaluate(() => { thSave({ amoled: false, mode: 'sun' }); });
    ok(await pg.evaluate(() => !thState.tmp), 'andere modus: tijdelijk weg');

    // Vast thema: de knop wisselt het thema zelf, met Ongedaan maken
    await pg.evaluate(() => { thSave({ mode: 'light' }); show('radio'); }); await pg.waitForTimeout(100);
    await pg.click('#rd-thema'); await pg.waitForTimeout(150);
    acts = await sheet();
    ok(acts[0] === 'Altijd donker', 'vast licht: Altijd donker (' + acts + ')');
    await tap('Altijd donker');
    ok(await dark() && await pg.evaluate(() => thCfg().mode === 'dark' && !thState.tmp), 'vast thema gewisseld');
    ok(await pg.isVisible('#undo-b'), 'met Ongedaan maken');

    // Instellingen voor weergave, en terug
    await pg.click('#rd-thema'); await pg.waitForTimeout(150); await tap('Instellingen voor weergave');
    ok(await pg.evaluate(() => current === 'setlook'), 'naar de instellingen voor weergave');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current === 'radio'), 'terug naar Radio');

    // Systeem: tijdelijk andersom tot de telefoon zelf wisselt
    await pg.evaluate(() => { thSave({ mode: 'system' }); show('podcasts'); });
    ok((await dark()) === sys, 'systeem volgt de telefoon');
    await pg.click('#pc-thema'); await pg.waitForTimeout(150);
    acts = await sheet();
    ok(acts.some(a => (sys ? /^Nu licht \(tot /.test(a) : /^Nu donker \(tot /.test(a))), 'systeem: nu andersom (' + acts + ')');
    await pg.click('#sheet-acts button >> nth=0'); await pg.waitForTimeout(120);
    ok((await dark()) === !sys && await pg.evaluate(() => thState.tmp), 'systeem: tijdelijk andersom');
    await pg.emulateMedia({ colorScheme: sys ? 'light' : 'dark' }); await pg.waitForTimeout(200);
    await pg.evaluate(() => thApply(true));
    ok((await dark()) === !sys && await pg.evaluate(() => !thState.tmp && store.get('themeTmp', null) === null), 'telefoon wisselt zelf: tijdelijk weg, de telefoon telt');
    await pg.emulateMedia({ colorScheme: scheme });

    // De grote speler (altijd donker): "Licht en donker" staat in het menu ⋯
    await pg.evaluate(() => { const f = Android._pcFeed('https://feeds.example.org/vandaag'); Android.podPlay(JSON.stringify({ ep: f.items[3], pod: f.pod })); pcPoll(true); });
    await pg.waitForFunction(() => pcSt.ep, null, { timeout: 5000 });
    await pg.evaluate(() => { plOpen(); plMenu(); }); await pg.waitForTimeout(300);
    ok((await sheet()).includes('Licht en donker') && !(await pg.$('#pl-thema')), 'speler: in het menu, niet in de kop');
    await tap('Licht en donker'); await pg.waitForTimeout(100);
    ok((await sheet()).includes('Instellingen voor weergave'), 'speler: menu licht en donker open');
    await pg.evaluate(() => { closeSheet(); plClose(); });

    // In het Engels
    await pg.evaluate(() => { rgSave({ lang: 'en' }); thSave({ mode: 'light' }); show('nieuws'); }); await pg.waitForTimeout(200);
    await pg.click('#nw-thema'); await pg.waitForTimeout(150);
    acts = await sheet();
    ok(acts.includes('Always dark') && acts.includes('Display settings') && (await pg.getAttribute('#nw-thema', 'aria-label')) === 'Light and dark', 'Engels (' + acts + ')');
    await pg.evaluate(() => { closeSheet(); rgSave({ lang: 'nl' }); thSave({ mode: 'system' }); store.set('themeTmp', null); });
    await ctx.close();

    // Losse app: alleen licht en donker, geen weg naar Instellingen
    const solo = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const sp = await solo.newPage(); sp.on('pageerror', e => errs.push(e.message));
    await sp.addInitScript(() => { window.__testSolo = 'podcasts'; });
    await sp.goto(URL); await sp.waitForTimeout(400);
    await sp.evaluate(() => show('podcasts')); await sp.waitForTimeout(150);
    await sp.click('#pc-thema'); await sp.waitForTimeout(150);
    const sa = await sp.$$eval('#sheet-acts button', x => x.map(e => e.textContent.trim()));
    ok(sa.length === 1 && /^Nu (donker|licht) \(tot /.test(sa[0]), 'losse app: geen Instellingen voor weergave (' + sa + ')');
    await solo.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exitCode = 1;
})().catch(e => { console.error(e); process.exit(1); });
