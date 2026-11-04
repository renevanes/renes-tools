// Regio en taal (Instellingen → Regio en taal, web/src/script/02-regio.js en 43-regio-en-taal.js): het scherm, elke keuze,
// het voorbeeld, bewaren, dat de standaard precies is wat de app altijd deed, en dat schermen (datum en tijd, bedragen,
// afstand, temperatuur, eerste dag van de week, de Telefoon-skin) de keuze volgen. Licht en donker. Ook de assistent.
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const page = async (scheme) => {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, timezoneId: 'Europe/Amsterdam' });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(e.message));
    pg.on('console', m => { if (m.type() === 'error' && !/ERR_TUNNEL|ERR_NAME|ERR_FILE|ERR_INTERNET|tile\.openstreetmap|\/icon\//.test(m.text() + JSON.stringify(m.location()))) errs.push(m.text() + ' @ ' + JSON.stringify(m.location())); });
    return { ctx, pg };
  };
  const url = 'file://' + path.join(__dirname, 'ui', 'index.html');

  // 1. De standaard is precies zoals de app het altijd schreef
  {
    const { ctx, pg } = await page('light');
    await pg.goto(url); await pg.waitForTimeout(300);
    const r = await pg.evaluate(() => {
      const L = 'nl-NL', ds = [new Date(2026, 10, 3, 14, 5, 7), new Date(2026, 0, 9, 7, 3, 0), new Date(2025, 11, 31, 23, 59, 59)], out = [];
      const os = [undefined, { day: 'numeric', month: 'short' }, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }, { day: 'numeric', month: 'short', year: 'numeric' },
        { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }, { month: 'long', year: 'numeric' }];
      for (const d of ds) {
        for (const o of os) { if (fmtDT(d, o) !== d.toLocaleString(L, o)) out.push('fmtDT ' + JSON.stringify(o) + ': ' + fmtDT(d, o) + ' ≠ ' + d.toLocaleString(L, o)); }
        if (fmtDate(d) !== d.toLocaleDateString(L)) out.push('fmtDate: ' + fmtDate(d));
        if (fmtTime(d) !== d.toLocaleTimeString(L, { hour: '2-digit', minute: '2-digit' })) out.push('fmtTime: ' + fmtTime(d));
        if (fmtTime(d, true) !== d.toLocaleTimeString(L)) out.push('fmtTime sec: ' + fmtTime(d, true));
      }
      for (const n of [0, 7, 1234, 1234567.891, -42.5]) if (fmtNum(n) !== n.toLocaleString(L)) out.push('fmtNum ' + n + ': ' + fmtNum(n));
      return { out, euro: bkEuro(123456), min: bkEuro(-505), plus: bkEuro(1999, true), kort: bkEuroKort(-1234567), fd: FD_EUR(1234.5), km: fmtKm(12345), car: carFmtKm(23400),
        size: [fmtB(500), fmtB(5 * 1048576), fmtB(2.5 * 1073741824), fmtB(1023 * 1024), fmtB(1023 * 1048576), vpSize(1023), vpSize(1023 * 1024), updMB(1500 * 1048576)], temp: fmtTemp(17.6), cfg: rgCfg(), hm: fmtHm('07:30'), week: weekDays().join(),
        fdmin: FD_EUR(-12.5), zero: [fmtMoney(-0.004, 'EUR'), bkEuro(-0.4), fmtMoney(-0.4, 'EUR', { round: true })] };
    });
    ok(!r.out.length, 'datum, tijd en getallen precies zoals toLocaleString(\'nl-NL\')' + (r.out.length ? ': ' + r.out.join(' | ') : ''));
    ok(r.euro === '€ 1.234,56' && r.min === '− € 5,05' && r.plus === '+ € 19,99' && r.kort === '€ 12.346' && r.fd === '€ 1.234,50', 'bedragen zoals altijd: ' + [r.euro, r.min, r.plus, r.kort, r.fd].join(' · '));
    ok(r.km === '12,35' && r.car === '23,4 km' && r.size.join('|') === '1 kB|5 MB|2,5 GB|1023 kB|1023 MB|1023 B|1023 kB|1500,0 MB' && r.temp === '18 °C' && r.hm === '07:30' && r.week === '1,2,3,4,5,6,0', 'eenheden zoals altijd: ' + [r.km, r.car, r.size.join(' / '), r.temp, r.hm].join(' · '));
    ok(r.cfg.lang === 'nl' && r.cfg.clock === '24' && r.cfg.cur === 'EUR' && r.cfg.dist === 'km' && r.cfg.tz === '' && !('vol' in r.cfg), 'standaard: Nederlands, 24 uur, euro, km, tijdzone van de telefoon');
    ok(r.fdmin === '€ -12,50' && r.zero.join('|') === '€ 0,00|€ 0,00|€ 0', 'negatief bij Facturen zoals altijd (' + r.fdmin + '), afgerond nul zonder minteken (' + r.zero.join(' / ') + ')');

    // 2. Het scherm
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(100);
    ok(/Nederlands · 24 uur · \d{1,2}-\d{1,2}-\d{4} · km · €/.test(await pg.textContent('#st-sum-regio')), 'samenvatting bij Instellingen: ' + await pg.textContent('#st-sum-regio'));
    await pg.click('button.strow:has-text("Regio en taal")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current) === 'setregio' && await pg.isVisible('#s-setregio .hdr.sm h1'), 'Regio en taal opent als eigen scherm (detailkop)');
    const prev0 = await pg.textContent('#rg-prev');
    ok(/^(maan|dins|woens|donder|vrij|zater|zon)dag \d{1,2}-\d{1,2}-\d{4} · \d{2}:\d{2} · € 1\.234,56 · 12,5 km · 18 °C$/.test(prev0), 'voorbeeld bovenaan: ' + prev0);
    ok(await pg.evaluate(() => ['rg-lang', 'rg-clock', 'rg-date', 'rg-week', 'rg-place', 'rg-dist', 'rg-temp', 'rg-size', 'rg-speed', 'rg-cur', 'rg-dec', 'rg-grp'].every(id => document.getElementById(id).options.length > 0)), 'alle keuzelijsten gevuld');
    ok(await pg.evaluate(() => $('#rg-lang').options.length === 1 && $('#rg-lang').options[0].text === 'Nederlands' && $('#rg-tz').textContent === 'Volg de telefoon (Europe/Amsterdam)' && $('#rg-tz-btn').textContent === 'Kiezen' && !$('#rg-vol') && $('#rg-cur').options.length >= 10 && /Standaardvaluta/.test($('label[for=rg-cur]').textContent.replace(/\u00ad/g, ''))), 'taal: alleen Nederlands; tijdzone: volg de telefoon (Europe/Amsterdam), met Kiezen; geen inhoud; Standaardvaluta');
    await pg.evaluate(() => { window._rc = 0; addEventListener('regiochange', () => window._rc++); });

    // 3. Elke keuze
    await pg.selectOption('#rg-clock', '12'); await pg.waitForTimeout(50);
    ok(/ \d{1,2}:\d{2} (a|p)\.m\. · /.test(await pg.textContent('#rg-prev')), '12-uurs: ' + await pg.textContent('#rg-prev'));
    await pg.selectOption('#rg-date', 'iso'); await pg.waitForTimeout(50);
    ok(/dag \d{4}-\d{2}-\d{2} · /.test(await pg.textContent('#rg-prev')), 'datumnotatie 2026-10-31: ' + await pg.textContent('#rg-prev'));
    await pg.selectOption('#rg-date', 'dmon'); await pg.waitForTimeout(50);
    ok(/dag \d{1,2} [a-z]{3} \d{4} · /.test(await pg.textContent('#rg-prev')), 'datumnotatie 31 okt 2026: ' + await pg.textContent('#rg-prev'));
    await pg.selectOption('#rg-week', '0'); await pg.waitForTimeout(50);
    const hm = await pg.evaluate(() => [weekDays()[0], fmtHm('07:30'), fmtHm('00:05'), fmtHm('12:00'), fmtHm('13:45'), fmtHm('23:59'), fmtTime(new Date(2026, 10, 3, 13, 5))]);
    ok(hm.join('|') === '0|7:30 a.m.|12:05 a.m.|12:00 p.m.|1:45 p.m.|11:59 p.m.|1:05 p.m.', 'zondag als eerste dag; 12-uurs tijden uit de opslag rechtstreeks: ' + hm.join(' · '));
    for (const [id, v] of [['#rg-dist', 'mi'], ['#rg-temp', 'f'], ['#rg-size', '1000'], ['#rg-speed', 'mph'], ['#rg-cur', 'USD']]) { await pg.selectOption(id, v); await pg.waitForTimeout(30); }
    const p2 = await pg.textContent('#rg-prev'), n2 = await pg.textContent('#rg-num-prev');
    ok(/ · \$ 1\.234,56 · 7,8 mi · 64 °F$/.test(p2), 'dollar, mijl en Fahrenheit: ' + p2);
    ok(/1,6 GB/.test(n2) && /62 mph/.test(n2), '1000 per stap en mph: ' + n2);
    await pg.selectOption('#rg-dec', '.'); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => rgCfg().grp === ',' && $('#rg-grp').value === ',' && ![...$('#rg-grp').options].some(o => o.value === '.')), 'punt als decimaalteken: duizendtallen vanzelf een komma (en geen punt meer te kiezen)');
    ok(/\$ 1,234\.56/.test(await pg.textContent('#rg-prev')) && /1,234,567\.89/.test(await pg.textContent('#rg-num-prev')), 'voorbeeld met punt: ' + await pg.textContent('#rg-num-prev'));
    await pg.selectOption('#rg-grp', ' '); await pg.waitForTimeout(50);
    ok(/1\u202f234\u202f567\.89/.test(await pg.textContent('#rg-num-prev')), 'spatie voor duizendtallen: ' + await pg.textContent('#rg-num-prev'));
    const st = await pg.evaluate(() => ({ ev: window._rc, java: JSON.parse(Android._regioCfg || '{}'), saved: store.get('regio', null) }));
    ok(st.ev >= 10, 'gebeurtenis regiochange bij elke wijziging (' + st.ev + ')');
    ok(st.java.clock === '12' && st.java.dist === 'mi' && st.java.locale === 'nl-NL' && st.java.tz === '', 'doorgegeven aan Java (meldingen, widgets, skin), tijdzone: de telefoon');
    ok(st.saved && st.saved.cur === 'USD' && st.saved.grp === ' ', 'bewaard bij de interface-instellingen (gaat mee in backups)');

    // 4. Schermen volgen de keuze
    const s = await pg.evaluate(() => {
      const out = {};
      out.bank = bkEuro(123456); out.fact = FD_EUR(1234.5); out.size = fmtB(2.5e9);
      out.calls = fmtDay('2026-11-03'); out.wa = fmtD(Date.UTC(2026, 9, 1, 18, 30));
      Android._car.trips = true; show('car'); out.car = $('#car-trips').textContent;
      show('ralarm'); out.chips = [...document.querySelectorAll('#al-days button')].map(x => x.textContent).join(' ');
      Android._tr.list.push({ id: 'r1', t: Date.now(), title: 'Route', stats: { distance: 16093, totalMs: 3600e3, avgSpeed: 4, maxSpeed: 9, eleGain: 30 } }); openTrack('r1'); out.unit = $('#s-track .rg-u-dist').textContent; out.dist = $('#td-dist').textContent;
      show('setregio'); return out;
    });
    ok(s.bank === '€ 1\u202f234.56' && s.fact === '€ 1\u202f234.50', 'Bankzaken en Facturen: eigen munt (euro), tekens volgens de keuze: ' + s.bank + ' / ' + s.fact);
    ok(s.size === '2.5 GB' && s.calls === '3 nov 2026' && /^\d{1,2} okt \d{1,2}:\d{2} (a|p)\.m\.$/.test(s.wa), 'opslag, datum (Oproepen) en tijd (WhatsApp): ' + [s.size, s.calls, s.wa].join(' · '));
    ok(/\d+\.\d mi/.test(s.car) && !/ km/.test(s.car), 'Mijn auto: ritten in mijlen: ' + s.car.slice(0, 80));
    ok(s.chips.startsWith('zo ma di'), 'radiowekker: week begint op zondag: ' + s.chips);
    ok(s.unit === 'mi' && /^\d+\.\d\d$/.test(s.dist), 'Mijn routes: afstand in mijlen (' + s.dist + ' ' + s.unit + ')');

    // 5. Thuis en werk
    await pg.click('#rg-home-btn'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current) === 'setregioplek' && await pg.textContent('#rgp-title') === 'Thuis', 'Thuis kiezen: eigen scherm');
    await pg.fill('#rgp-q', '51,9225; 4,4792'); await pg.click('#rgp-search'); await pg.waitForTimeout(50);
    ok(/Gekozen: 51\.9225/.test(await pg.textContent('#rgp-now')), 'coördinaten intikken: ' + await pg.textContent('#rgp-now'));
    await pg.click('#rgp-save'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current) === 'setregio' && /51\.9225/.test(await pg.textContent('#rg-home-sub')) && await pg.textContent('#rg-home-btn') === 'Wijzigen', 'thuis bewaard: ' + await pg.textContent('#rg-home-sub'));
    await pg.click('#rg-work-btn'); await pg.waitForTimeout(100);
    await pg.fill('#rgp-q', 'Coolsingel 40'); await pg.click('#rgp-search'); await pg.waitForTimeout(300);
    ok(await pg.$$eval('#rgp-results .sbtn', x => x.length) > 0, 'adres zoeken (via Android): ' + await pg.$$eval('#rgp-results .sbtn', x => x.length) + ' gevonden');
    await pg.click('#rgp-results .sbtn >> nth=0'); await pg.fill('#rgp-name', 'Kantoor'); await pg.click('#rgp-save'); await pg.waitForTimeout(150);
    ok(/Kantoor · Coolsingel 40/.test(await pg.textContent('#rg-work-sub')), 'werk bewaard met eigen naam: ' + await pg.textContent('#rg-work-sub'));
    const here = await pg.evaluate(async () => { Android._au.perms.loc = true; rgPlek('home'); rgpHere(); await new Promise(r => setTimeout(r, 400)); const t = $('#rgp-now').textContent; goBack(); return t; });
    ok(/Gekozen: 51\.9225/.test(here), 'Mijn huidige locatie: ' + here);
    await pg.selectOption('#rg-place', 'home'); await pg.waitForTimeout(50);
    const loc = await pg.evaluate(() => {
      const near = rgNear({ lat: 51.9226, lon: 4.4793 }), far = rgNear({ lat: 52.37, lon: 4.9 });
      thSave({ mode: 'sun', place: 'home' }); const note = thSunNote(thCfg()); thSave({ mode: 'system', place: 'auto' });
      const th = thLoc({ place: 'auto' });
      return { near, far, note, th: th.name, ends: trEnds([[51.9225, 4.4792], [51.95, 4.5], [51.9222, 4.4795]]), ap: (() => { show('apset'); const t = $('#aps-home-st').textContent; show('setregio'); return t; })() };
    });
    ok(loc.near === 'Thuis' && loc.far === '', 'dichtbij thuis herkend (rgNear)');
    ok(/thuis\. Berekend/.test(loc.note) && loc.th === 'Thuis', 'thema met de zon: thuis, en als je locatie niet bekend is de standaardplaats: ' + loc.note);
    ok(loc.ends === ' · thuis → kantoor' || loc.ends === ' · thuis → thuis', 'Mijn routes: begin en eind bij thuis of werk (' + loc.ends + ')');
    ok(/Thuis uit Regio en taal/.test(loc.ap), 'Apparaten: thuis uit Regio en taal als daar geen eigen thuis is');

    // 6. Bewaren: na opnieuw openen nog zo; Terug naar de standaard houdt thuis en werk
    // Thuis en werk alleen in Java (regio.xml): nooit in localStorage, webstore of de keuze die naar Java gaat
    const priv = await pg.evaluate(async () => { await new Promise(r => setTimeout(r, 1700)); const ls = []; for (let i = 0; i < localStorage.length; i++) ls.push(localStorage.getItem(localStorage.key(i))); const sp = JSON.parse(Android._regioSpots || '{}');
      return { ls: ls.join(' ').includes('51.92') || ls.join(' ').includes('Coolsingel'), ws: String(Android._webStore || '').includes('Coolsingel') || String(Android._webStore || '').includes('51.92'), cfg: (() => { const j = JSON.parse(Android._regioCfg || '{}'); return 'home' in j || 'work' in j || /Coolsingel|51\.92/.test(Android._regioCfg || ''); })(), sp: !!(sp.home && sp.work && sp.work.name === 'Kantoor') }; });
    ok(!priv.ls && !priv.ws && !priv.cfg && priv.sp, 'thuis en werk alleen in Java, niet in localStorage of de interface-backup (' + JSON.stringify(priv) + ')');
    const keep = await pg.evaluate(() => Android._regioSpots);
    await pg.reload(); await pg.waitForTimeout(300);
    ok(await pg.evaluate(k => { Android._regioSpots = k; rgReload(); return rgCfg().clock === '12' && rgCfg().dist === 'mi' && !!rgCfg().home && JSON.parse(Android._regioCfg || '{}').cur === 'USD'; }, keep), 'na opnieuw openen: keuze bewaard, thuis uit Java, en weer aan Java gegeven');
    await pg.evaluate(() => show('setregio')); await pg.waitForTimeout(100);
    await pg.click('#rg-reset'); await pg.waitForTimeout(100); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => rgCfg().clock === '24' && rgCfg().dist === 'km' && rgCfg().cur === 'EUR' && rgCfg().dec === ',' && !!rgCfg().home && !!rgCfg().work), 'terug naar de standaard (thuis en werk blijven)');
    ok(await pg.textContent('#rg-prev') === prev0.replace(/\d{2}:\d{2}/, (await pg.textContent('#rg-prev')).match(/\d{2}:\d{2}/)[0]), 'voorbeeld weer als eerst: ' + await pg.textContent('#rg-prev'));
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'regio-licht.png'), fullPage: true });
    await ctx.close();
  }

  // 7. Donker, en de opmaak (kleurrollen) in beide
  for (const scheme of ['light', 'dark']) {
    const { ctx, pg } = await page(scheme);
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('setregio')); await pg.waitForTimeout(150);
    const c = await pg.evaluate(() => { const cs = getComputedStyle(document.documentElement); return { ink: cs.getPropertyValue('--ink').trim(), bg: getComputedStyle(document.body).backgroundColor, prev: getComputedStyle($('#rg-prev')).color, w: document.documentElement.scrollWidth }; });
    ok(c.prev !== '' && c.w <= 390, scheme + ': scherm zonder horizontaal schuiven, voorbeeld in --ink (' + c.prev + ')');
    if (scheme === 'dark') await pg.screenshot({ path: path.join(__dirname, 'shots', 'regio-donker.png'), fullPage: true });
    await ctx.close();
  }

  // 8. Gecontroleerd, wat er ook in de opslag staat
  {
    const { ctx, pg } = await page('light');
    await pg.goto(url); await pg.waitForTimeout(200);
    const r = await pg.evaluate(() => [rgClean({ clock: 'x', date: 'nee', tz: 'Mars/Olympus', dec: ',', grp: ',', cur: 'XXX', home: { lat: 51, lon: 4 }, place: 'maan', week: 3 }), rgClean('kapot')]);
    ok(r[0].clock === '24' && r[0].date === 'dmy' && r[0].tz === 'Mars/Olympus' && r[0].grp === '.' && r[0].cur === 'EUR' && !('home' in r[0]) && r[0].place === 'auto' && r[0].week === 1, 'onbekende waarden: de standaard (en geen thuis in de opslag); een onbekende tijdzone blijft bewaard (de app volgt dan de telefoon)');
    // Getallen uit invoervelden (Bankzaken-budget, Automatiseren, Check-in)
    const pn = await pg.evaluate(() => { const a = ['12,50', '12.50', '1.250', '1.234,56', '1,234.56', '€ 1 234', '-3,5', 'abc', '', '1.2.3'].map(parseNum); rgSave({ dec: '.' }); const b = ['12.50', '12,50', '1,250', '1.250'].map(parseNum); const inp = [fmtNumIn(12.5, 2), fmtNumIn(1250, 2)]; rgSave({ dec: ',', grp: '.' }); return { a, b, inp, inp2: [fmtNumIn(12.5, 2), fmtNumIn(1250, 2)] }; });
    ok(JSON.stringify(pn.a) === JSON.stringify([12.5, 12.5, 1250, 1234.56, 1234.56, 1234, -3.5, null, null, 123]) && JSON.stringify(pn.b) === JSON.stringify([12.5, 12.5, 1250, 1.25]), 'bedragen inlezen: ' + JSON.stringify(pn));
    ok(pn.inp.join('|') === '12.50|1250.00' && pn.inp2.join('|') === '12,50|1250,00', 'invoervelden vooraf ingevuld met het gekozen decimaalteken');
    const bud = await pg.evaluate(async () => { bk.store = bk.store || { budget: {} }; bk.store.budget = bk.store.budget || {}; const prev = { save: window.bkSave, render: window.bkRender };
      window.bkSave = () => {}; window.bkRender = () => {}; try { bkBudgetNieuw(); await new Promise(r => setTimeout(r, 50)); document.querySelector('#sheet-acts button').click(); await new Promise(r => setTimeout(r, 80));
        $('#modal-input').value = '12.50'; $('#modal-ok').click(); await new Promise(r => setTimeout(r, 50)); return Object.values(bk.store.budget); } finally { window.bkSave = prev.save; window.bkRender = prev.render; } });
    ok(bud.includes(1250), 'Bankzaken: budget "12.50" wordt € 12,50 (1250 cent), niet € 1250 (' + JSON.stringify(bud) + ')');
    ok(JSON.stringify(r[1]) === JSON.stringify(Object.assign({}, r[1], { clock: '24', cur: 'EUR' })), 'onleesbaar: de standaard');
    await ctx.close();
  }

  // 9. De assistent: opdrachten met bevestiging, en geen valse treffers
  {
    const { ctx, pg } = await page('light');
    await pg.goto(url); await pg.waitForTimeout(300);
    const r = await pg.evaluate(() => {
      const one = q => { const h = setHandle(q), it = h && h.set && h.set.items && h.set.items[0]; return it ? it.id + '=' + it.to : h && /staat al op/.test(h.text || '') ? 'al ' + h.set.open[0] : h && h.set && h.set.choose ? 'kies ' + h.set.choose : h && h.set && h.set.pick ? 'welke ' + h.set.pick.map(p => p.id).join('/') : ''; };
      return {
        ja: ['gebruik 12-uurs klok', 'zet de tijdnotatie op 24-uurs', 'gebruik mijlen', 'valuta dollar', 'zet de temperatuur op fahrenheit', 'gebruik fahrenheit', 'zet de valuta op euro', 'zet de datumnotatie op iso',
          'zet de eerste dag van de week op zondag', 'gebruik kilometers', 'zet de snelheid op mph', 'zet het decimaalteken op punt', 'begin de week op zondag', 'toon het weer in fahrenheit', 'reken in dollars',
          'zet de klok op 12-uurs', 'zet de tijd op am/pm'].map(q => q + ' → ' + one(q)),
        munt: (() => { rgSave({ cur: 'USD' }); const x = 'zet de munt op euro → ' + one('zet de munt op euro'); rgSave({ cur: 'EUR' }); return x; })(),
        nee: ['zet de wekker op 12 uur', 'zet de backup op 3 uur', 'gebruik de telefoon', 'gebruik thuis', 'temperatuur morgen', 'zet het licht uit', 'gebruik de radio', 'afstand naar werk', 'zet de radio aan', 'toon de foto in het album',
          'zet de temperatuur op 20 graden', 'zet de afstand op 5 km', 'zet de datum op morgen', 'zet de klok op 12 uur', 'zet de tijd op 12 uur', 'gebruik 12', 'reken met 12', 'gebruik 24', 'reken met 24'].map(q => q + ' → ' + one(q)),
        nul: ['zet de temperatuur op 20 graden', 'zet de afstand op 5 km', 'zet de datum op morgen', 'zet de klok op 12 uur', 'zet de tijd op 12 uur', 'gebruik 12', 'reken met 12', 'gebruik 24', 'reken met 24'].filter(q => { const h = setHandle(q); return h && h.set && /regio\./.test(JSON.stringify(h.set)); })
      };
    });
    const want = ['regio.klok=12', 'al regio.klok', 'regio.afstand=mi', 'regio.valuta=USD', 'regio.temperatuur=f', 'regio.temperatuur=f', 'al regio.valuta', 'regio.datum=iso', 'regio.week=0', 'al regio.afstand', 'regio.snelheid=mph', 'regio.decimaal=punt',
      'regio.week=0', 'regio.temperatuur=f', 'regio.valuta=USD', 'regio.klok=12', 'regio.klok=12'];
    r.ja.forEach((x, i) => ok(x.endsWith('→ ' + want[i]), 'assistent: ' + x));
    ok(r.munt.endsWith('→ regio.valuta=EUR'), 'assistent: ' + r.munt);
    ok(!r.nul.length, 'geen voorstel bij Regio en taal voor: ' + (r.nul.join(' | ') || 'temperatuur 20 graden, afstand 5 km, datum morgen, klok/tijd op 12 uur, gebruik/reken met 12 en 24'));
    ok(r.nee.every(x => !/regio\./.test(x)), 'geen valse treffers: ' + r.nee.join(' | '));
    // Bevestigen gaat via hetzelfde als het scherm
    const done = await pg.evaluate(() => { setEntry('regio.afstand').set('mi'); const a = [rgCfg().dist, setGet(setEntry('regio.afstand'))]; setEntry('regio.afstand').set('km'); setEntry('regio.duizendtallen').set('spatie'); a.push(rgCfg().grp === ' ' ? 'spatie' : rgCfg().grp); setEntry('regio.duizendtallen').set('punt'); return a.join(); });
    ok(done === 'mi,mi,spatie', 'register: zetten via dezelfde functies als het scherm (' + done + ')');
    await ctx.close();
  }

  // 10. Telefoon-skin: klok, datum en weer volgen de keuze van de app
  {
    const { ctx, pg } = await page('light');
    await pg.goto('file://' + path.join(__dirname, '..', '..', 'web', 'start.html')); await pg.waitForTimeout(500);
    await pg.setViewportSize({ width: 360, height: 780 });
    const def = await pg.textContent('#time');
    const r = await pg.evaluate(() => {
      Android.regio = () => JSON.stringify({ clock: '12', temp: 'f' }); skRgC = null; tickClock();
      onWeather({ temp: 20, code: 0, day: true, days: [{ date: '2026-11-03', code: 0, max: 21, min: 10 }, { date: '2026-11-04', code: 3, max: 15, min: 5 }] });
      renderCal();
      const t = $('#time'), lh = parseFloat(getComputedStyle(t).fontSize), ets = [...document.querySelectorAll('#cal .ev .et')].filter(e => /m\./.test(e.textContent));
      return { time: t.textContent, wt: $('#weather .wt').textContent, wd: $('#weather').textContent, one: t.getBoundingClientRect().height < lh * 1.5, ampm: !!t.querySelector('.ampm'),
        et: ets.map(e => e.textContent), etOne: ets.length > 0 && ets.every(e => e.getBoundingClientRect().height < parseFloat(getComputedStyle(e).fontSize) * 1.9) };
    });
    ok(/^\d{2}:\d{2}$/.test(def), 'skin zonder keuze: 24-uurs (' + def + ')');
    ok(r.one && r.ampm && r.etOne, 'skin op 360 px: 12-uurs klok op één regel (a.m./p.m. klein), agenda past (' + r.et.join(' / ') + ')');
    ok(/^\d{1,2}:\d{2}(a|p)\.m\.$/.test(r.time) && r.wt === '68°' && /70° \/ 50°/.test(r.wd) && /59°\/41°/.test(r.wd), 'skin: 12-uurs en Fahrenheit (' + r.time + ', ' + r.wt + ')');
    await ctx.close();
  }

  await b.close();
  console.log('errors', errs);
})();
