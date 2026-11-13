// Tijdzone (Instellingen → Regio en taal → Tijdzone, web/src/script/02-regio.js en 43-regio-en-taal.js): de telefoon staat
// op Europe/Amsterdam (Playwright timezoneId, nagebootste klok), de app op een andere zone (America/New_York, en voor de
// daggrens Pacific/Kiritimati, UTC+14, en Pacific/Pago_Pago, UTC−11). Dan rekent de hele app in die zone: vandaag en
// gisteren, de radiowekker (veld en "Gaat af"), de slaaptimer, check-in, invoervelden, het thema op tijd en de
// Telefoon-skin. Datum-alleen-waarden (Facturen, Bankzaken) verschuiven nooit. Zomer- en wintertijd (ook het gat bij de
// zomertijd), het scherm met zoeken, een onbekende zone, de assistent, en terug naar "Volg de telefoon". Licht en donker.
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const page = async (scheme, phone) => {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme || 'light', timezoneId: phone || 'Europe/Amsterdam' });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(e.message));
    pg.on('console', m => { if (m.type() === 'error' && !/ERR_TUNNEL|ERR_NAME|ERR_FILE|ERR_INTERNET|tile\.openstreetmap|\/icon\//.test(m.text() + JSON.stringify(m.location()))) errs.push(m.text() + ' @ ' + JSON.stringify(m.location())); });
    return { ctx, pg };
  };
  const url = 'file://' + path.join(__dirname, 'ui', 'index.html');
  const skin = 'file://' + path.join(__dirname, '..', '..', 'web', 'start.html');
  // Woensdag 4 november 03:30 in Amsterdam = dinsdag 3 november 21:30 in New York
  const NOW = '2026-11-04T03:30:00+01:00';

  // 1. De rekenhulp: vandaag, gisteren, invoervelden (New York)
  {
    const { ctx, pg } = await page();
    await pg.clock.install({ time: new Date(NOW) });
    await pg.goto(url); await pg.waitForTimeout(300);
    const r = await pg.evaluate(() => {
      const phone = [rgToday(), fmtTime(Date.now()), rgTzOwn(), rgZone()];
      rgSave({ tz: 'America/New_York' });
      const P = s => Date.parse(s);
      return {
        phone, zone: rgZone(), own: rgTzOwn(), today: rgToday(), time: fmtTime(Date.now()), nowHm: rgHm(Date.now()), date: fmtDate(Date.now(), 'lang'),
        wa: [fmtD(P('2026-11-03T20:00-05:00')), fmtD(P('2026-11-02T23:30-05:00')), fmtD(P('2026-11-04T00:30+01:00'))],
        au: auWhen(P('2026-11-03T08:15-05:00')), call: fmtCallSpan({ date: P('2026-11-03T21:00-05:00'), duration: 0 }),
        ap: apAgo(P('2026-11-03T19:00-05:00')), pod: pcDate(P('2026-11-02T22:00-05:00')),
        iso: (() => { rgSave({ date: 'iso' }); const x = fmtDate(Date.now()); rgSave({ date: 'dmy' }); return x; })(),
        start: rgStartOfDay(Date.now()) === P('2026-11-03T00:00-05:00'), end: rgEndOfDay(Date.now()) === P('2026-11-04T00:00-05:00'),
        input: [localIso(Date.now()), rgFromLocalIn('2026-11-03T23:00') === P('2026-11-03T23:00-05:00'), isoDay(Date.now()), daysAgo(1) === P('2026-11-02T00:00-05:00')],
        car: carMonthOf(P('2026-11-01T02:00+01:00')), fa: faMaandSleutel(P('2026-11-01T02:00+01:00')), bank: bkVandaag(), fdv: fdVandaag(),
        night: slNightName(P('2026-11-03T23:10-05:00')), java: JSON.parse(Android._regioCfg || '{}').tz
      };
    });
    ok(r.phone.join() === '2026-11-04,03:30,false,Europe/Amsterdam', 'zonder keuze: de telefoon (' + r.phone.join(' · ') + ')');
    ok(r.zone === 'America/New_York' && r.own && r.today === '2026-11-03' && r.time === '21:30' && r.nowHm === '21:30' && r.date === 'dinsdag 3 november 2026', 'New York: vandaag is dinsdag 3 november, 21:30 (' + [r.today, r.time, r.date].join(' · ') + ')');
    ok(r.java === 'America/New_York', 'doorgegeven aan Java (regioSet): ' + r.java);
    ok(r.wa.join('|') === 'vandaag 20:00|gisteren 23:30|vandaag 18:30', '"vandaag" en "gisteren" in de app-zone (WhatsApp): ' + r.wa.join(' · '));
    ok(r.au === 'vandaag 08:15' && r.call === 'vandaag 21:00:00' && r.ap === 'vandaag 19:00' && r.pod === 'gisteren', 'ook Automatiseren, Oproepen, Apparaten en Podcasts: ' + [r.au, r.call, r.ap, r.pod].join(' · '));
    ok(r.iso === '2026-11-03' && r.start && r.end, 'datum in de app-zone (ook iso), begin en einde van de dag');
    ok(r.input[0] === '2026-11-03T21:30' && r.input[1] && r.input[2] === '2026-11-03' && r.input[3], 'invoervelden: vooraf ingevuld en gelezen in de app-zone (' + r.input[0] + ')');
    ok(r.car === '2026-10' && r.fa === '2026-10', 'Mijn auto en Facturen: per maand in de app-zone (1 nov 02:00 Amsterdam = 31 okt in New York)');
    ok(r.bank === '2026-11-03' && r.fdv === '2026-11-03', 'Bankzaken en Facturen: vandaag = ' + r.bank);
    ok(r.night === 'Nacht van di 3 op wo 4 nov', 'Slaappraat: ' + r.night);

    // 2. Datum-alleen-waarden verschuiven nooit, ook niet bij UTC+14 of UTC−11
    const d = await pg.evaluate(() => {
      const out = {};
      for (const z of ['America/New_York', 'Pacific/Kiritimati', 'Pacific/Pago_Pago']) {
        rgSave({ tz: z });
        out[z] = [FD_DAG('2026-11-03'), bkDatum('2026-11-03'), fmtDay('2026-11-03'), fmtDate('2026-11-03T12:00', 'wd'), faMaandNaam('2026-11'), fmtDate('2026-11-03', 'lang')].join('|');
      }
      rgSave({ date: 'iso' }); out.iso = fmtDate('2026-11-03T12:00'); rgSave({ date: 'dmy', tz: 'America/New_York' });
      return out;
    });
    const want = '3 nov 2026|3 nov 2026|3 nov 2026|di|november 2026|dinsdag 3 november 2026';
    ok(Object.keys(d).filter(k => k !== 'iso').every(k => d[k] === want) && d.iso === '2026-11-03', 'datum-alleen (Facturen, Bankzaken, Oproepen, weer) verschuift niet: ' + JSON.stringify(d));

    // 3. Radiowekker: het veld en "Gaat af" zeggen hetzelfde
    await pg.evaluate(() => { Android._al.station = { name: 'Testzender', url: 'http://radio.invalid/stream' }; show('ralarm'); });
    await pg.waitForTimeout(100);
    await pg.fill('#al-time', '07:00'); await pg.check('#al-on');
    await pg.evaluate(() => alSave()); await pg.waitForTimeout(100);
    const al = await pg.evaluate(() => ({ v: $('#al-time').value, next: $('#al-next').textContent, at: Android._al.nextAt }));
    ok(al.v === '07:00' && /Gaat af: woensdag 4 november (om )?07:00/.test(al.next) && al.at === Date.parse('2026-11-04T07:00-05:00'), 'radiowekker 07:00: ' + al.next + ' (niet 01:00)');

    // 4. Slaaptimer: "Stopt om" is de tijd uit het veld
    await pg.evaluate(() => { show('radio'); sleepOpen('radio'); }); await pg.waitForTimeout(100);
    const pre = await pg.inputValue('#sl-time');
    await pg.fill('#sl-time', '23:00');
    await pg.evaluate(() => sleepAt()); await pg.waitForTimeout(100);
    const sl = await pg.evaluate(() => ({ toast: $('#toast').textContent, at: Android._rd.st.sleepAt }));
    ok(pre === '22:30', 'slaaptimer: vooraf ingevuld met over een uur in de app-zone (' + pre + ')');
    ok(sl.toast === '⏾ Stopt om 23:00' && Math.abs(sl.at - Date.parse('2026-11-03T23:00-05:00')) < 6e4, 'slaaptimer: ' + sl.toast + ' (niet 17:00)');

    // 5. Check-in: de dag van een antwoord en "vandaag"
    const ci = await pg.evaluate(() => {
      const t = Date.parse('2026-11-03T23:30-05:00'); // 4 nov 05:30 in Amsterdam
      const by = ciByDay({ slots: [{ at: t, answers: [{ v: 3, t }] }], extra: [{ p: '', t }] });
      return { keys: Object.keys(by).join(), today: ciKeyLabel(rgToday()), tomorrow: ciKeyLabel('2026-11-04'), yday: ciKeyLabel('2026-11-02'), cal: ciDayKey(ciToday()), slot: ciKeyLabel('2026-11-03T21:00') };
    });
    ok(ci.keys === '2026-11-03' && ci.today === 'vandaag' && ci.yday === 'gisteren' && ci.tomorrow === 'woensdag 4 november' && ci.cal === '2026-11-03' && ci.slot === 'vandaag 21:00', 'check-in: dag in de app-zone (' + JSON.stringify(ci) + ')');

    // 6. Het scherm: de rij, zoeken, kiezen en weer terug naar de telefoon
    await pg.evaluate(() => { rgSave({ tz: '' }); show('setregio'); }); await pg.waitForTimeout(100);
    ok(await pg.textContent('#rg-tz') === 'Volg de telefoon (Europe/Amsterdam)' && await pg.isHidden('#rg-tz-bad'), 'rij Tijdzone: ' + await pg.textContent('#rg-tz'));
    await pg.click('#rg-tz-btn'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current) === 'setregiotz' && await pg.isVisible('#s-setregiotz .hdr.sm h1'), 'Tijdzone: eigen scherm (detailkop)');
    const n = await pg.$$eval('#rgz-list .sbtn', x => x.length);
    ok(n > 60, 'lijst met tijdzones (' + n + ')');
    ok(/^Volg de telefoon \(Europe\/Amsterdam\) ✓$/.test(await pg.textContent('#rgz-list .sbtn >> nth=0 >> b')), 'bovenaan: Volg de telefoon, gekozen');
    await pg.fill('#rgz-q', 'new york'); await pg.waitForTimeout(100);
    const rows = await pg.$$eval('#rgz-list .sbtn', x => x.map(e => e.textContent));
    ok(rows.length === 1 && /^New York \(UTC−5\)Nu 21:30 · America\/New York$/.test(rows[0]), 'zoeken op "new york": ' + rows.join(' / '));
    await pg.fill('#rgz-q', 'japan'); await pg.waitForTimeout(50);
    ok(/^Tokio \(UTC\+9\)Nu 11:30/.test(await pg.textContent('#rgz-list .sbtn')), 'zoeken op een land (japan): ' + await pg.textContent('#rgz-list .sbtn'));
    await pg.fill('#rgz-q', 'new york'); await pg.waitForTimeout(50);
    await pg.click('#rgz-list .sbtn'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current) === 'setregio' && await pg.evaluate(() => rgCfg().tz) === 'America/New_York' && await pg.textContent('#rg-tz') === 'New York (UTC−5) · nu 21:30' && await pg.textContent('#rg-tz-btn') === 'Wijzigen',
      'gekozen: ' + await pg.textContent('#rg-tz'));
    ok(/ · New York · /.test(await pg.evaluate(() => rgSummary())), 'samenvatting bij Instellingen noemt de zone: ' + await pg.evaluate(() => rgSummary()));
    ok(/^(dinsdag) 3-11-2026 · 21:30 · /.test(await pg.textContent('#rg-prev')), 'voorbeeld in de app-zone: ' + await pg.textContent('#rg-prev'));
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'tijdzone-licht.png'), fullPage: true });
    await pg.click('#rg-tz-btn'); await pg.waitForTimeout(150);
    await pg.click('#rgz-list .sbtn >> nth=0'); await pg.waitForTimeout(150);
    const back = await pg.evaluate(() => ({ tz: rgCfg().tz, java: JSON.parse(Android._regioCfg || '{}').tz, today: rgToday(), time: fmtTime(Date.now()), own: rgTzOwn(), row: $('#rg-tz').textContent,
      wa: fmtD(Date.parse('2026-11-03T20:00-05:00')), al: (() => { alSave(); return $('#al-next').textContent; })(), ci: ciKeyLabel('2026-11-04'), bank: bkVandaag() }));
    ok(back.tz === '' && back.java === '' && back.today === '2026-11-04' && back.time === '03:30' && !back.own && back.row === 'Volg de telefoon (Europe/Amsterdam)', 'terug naar Volg de telefoon: weer 4 november 03:30');
    ok(back.wa === 'vandaag 02:00' && back.ci === 'vandaag' && back.bank === '2026-11-04' && /woensdag 4 november (om )?07:00/.test(back.al), 'en alles weer in telefoontijd: ' + [back.wa, back.ci, back.bank, back.al].join(' · '));
    await ctx.close();
  }

  // 7. De daggrens: UTC+14 (Kiritimati) en UTC−11 (Pago Pago)
  {
    const { ctx, pg } = await page();
    await pg.clock.install({ time: new Date('2026-11-03T12:30:00+01:00') }); // 11:30 UTC
    await pg.goto(url); await pg.waitForTimeout(300);
    const r = await pg.evaluate(() => {
      const ev = Date.parse('2026-11-03T13:00+01:00'), early = Date.parse('2026-11-03T08:00+01:00'), out = {};
      rgSave({ tz: 'Pacific/Kiritimati' });
      out.ki = [rgToday(), fmtTime(Date.now()), fmtD(ev), fmtD(early), ciKeyLabel(rgDayKey(ev)), rgDayKey(Date.parse('2026-11-03T10:30Z')), fmtDate(Date.now(), 'wdm')];
      rgSave({ tz: 'Pacific/Pago_Pago' });
      out.pp = [rgToday(), fmtTime(Date.now()), fmtD(ev), fmtD(early), ciKeyLabel(rgDayKey(early)), rgDayKey(Date.parse('2026-11-03T10:30Z')), fmtDate(Date.now(), 'wdm')];
      return out;
    });
    ok(r.ki.join('|') === '2026-11-04|01:30|vandaag 02:00|gisteren 21:00|vandaag|2026-11-04|wo 4 nov', 'Kiritimati (UTC+14): het is daar al woensdag: ' + r.ki.join(' · '));
    ok(r.pp.join('|') === '2026-11-03|00:30|vandaag 01:00|gisteren 20:00|gisteren|2026-11-02|di 3 nov', 'Pago Pago (UTC−11): net na middernacht: ' + r.pp.join(' · '));
    await ctx.close();
    // De Telefoon-skin: klok en datum in de app-zone
    const s2 = await page();
    await s2.pg.clock.install({ time: new Date('2026-11-03T12:30:00+01:00') });
    await s2.pg.goto(skin); await s2.pg.waitForTimeout(500);
    const k = await s2.pg.evaluate(() => {
      const out = [], at = z => { Android.regio = () => JSON.stringify({ tz: z }); skRgC = null; tickClock(); return [$('#time').textContent, $('#date').textContent, fmtD(Date.parse('2026-11-03T13:00+01:00')), fmtDate('2026-11-05T12:00', { weekday: 'short' })].join('|'); };
      out.push(at(''), at('America/New_York'), at('Pacific/Kiritimati'), at('Pacific/Pago_Pago'), at('Mars/Olympus'));
      return out;
    });
    ok(k[0] === '12:30|Dinsdag 3 november|vandaag 13:00|do', 'skin zonder zone: telefoontijd (' + k[0] + ')');
    ok(k[1] === '06:30|Dinsdag 3 november|vandaag 07:00|do', 'skin in New York: ' + k[1]);
    ok(k[2] === '01:30|Woensdag 4 november|vandaag 02:00|do', 'skin in Kiritimati (UTC+14): ' + k[2]);
    ok(k[3] === '00:30|Dinsdag 3 november|vandaag 01:00|do', 'skin in Pago Pago (UTC−11): ' + k[3]);
    ok(k[4] === k[0], 'skin met een onbekende zone: de telefoon');
    await s2.ctx.close();
  }

  // 8. Zomer- en wintertijd (New York 1 november 2026, Amsterdam 25 oktober 2026) en het gat (New York 14 maart 2027)
  for (const phone of ['Europe/Amsterdam', 'America/New_York']) {
    const { ctx, pg } = await page('light', phone);
    await pg.clock.install({ time: new Date('2026-10-20T12:00:00Z') });
    await pg.goto(url); await pg.waitForTimeout(300);
    const r = await pg.evaluate(() => {
      const P = s => Date.parse(s), out = {};
      rgSave({ tz: 'America/New_York' });
      out.ny = [rgFromLocal('2026-11-01', '01:30') === P('2026-11-01T05:30Z'), rgFromLocal('2026-11-01', '03:00') === P('2026-11-01T08:00Z'),
        rgEndOfDay(P('2026-11-01T12:00Z')) - rgStartOfDay(P('2026-11-01T12:00Z')) === 25 * 36e5, rgDayKey(P('2026-11-01T04:30Z')) === '2026-11-01',
        rgFromLocal('2027-03-14', '02:30') === P('2027-03-14T07:30Z'), fmtTime(rgFromLocal('2027-03-14', '02:30')) === '03:30',
        rgNextAt('02:30', P('2027-03-13T17:00Z')) === P('2027-03-14T07:30Z'), rgNextAt('02:30', P('2027-03-14T08:00Z')) === P('2027-03-15T06:30Z'),
        rgNextAt('07:00', P('2026-10-31T12:00Z')) === P('2026-11-01T12:00Z'), rgNextAt('07:00', P('2026-11-01T12:00Z')) === P('2026-11-02T12:00Z'),
        rgEndOfDay(P('2027-03-14T12:00Z')) - rgStartOfDay(P('2027-03-14T12:00Z')) === 23 * 36e5];
      rgSave({ tz: 'Europe/Amsterdam' });
      out.ams = [rgFromLocal('2026-10-25', '02:30') === P('2026-10-25T00:30Z'), rgFromLocal('2026-10-25', '03:00') === P('2026-10-25T02:00Z'),
        rgEndOfDay(P('2026-10-25T12:00Z')) - rgStartOfDay(P('2026-10-25T12:00Z')) === 25 * 36e5, rgFromLocal('2027-03-28', '02:30') === P('2027-03-28T01:30Z'),
        rgNextAt('21:00', P('2026-10-24T20:00Z')) === P('2026-10-25T20:00Z'), rgDayKey(P('2026-10-24T22:30Z')) === '2026-10-25'];
      out.own = rgTzOwn();
      // Het thema op tijd over de wintertijd: 07:00 en 20:00 blijven 07:00 en 20:00
      const tr = thTrans({ mode: 'time', dark: '20:00', light: '07:00' }, P('2026-10-25T10:00Z'), null, 0, 1).map(x => rgHm(x[0]) + (x[1] ? 'd' : 'l'));
      out.th = tr.join(',');
      return out;
    });
    const tag = phone === 'Europe/Amsterdam' ? '(telefoon Amsterdam)' : '(telefoon New York)';
    ok(r.ny.every(Boolean), 'New York, 1 november: 01:30 is de eerste keer, de dag duurt 25 uur; 14 maart: 02:30 bestaat niet en wordt 03:30, de dag duurt 23 uur ' + tag + ': ' + r.ny.join());
    ok(r.ams.every(Boolean), 'Amsterdam, 25 oktober: 02:30 de eerste keer, 25 uur; 28 maart 02:30 wordt 03:30 ' + tag + ': ' + r.ams.join());
    ok(r.th === '07:00l,20:00d,07:00l,20:00d', 'thema op tijd over de wintertijd: ' + r.th);
    ok(r.own === (phone !== 'Europe/Amsterdam'), 'eigen rekenwijze alleen als de zone anders is dan de telefoon ' + tag);
    await ctx.close();
  }

  // 9. Het thema "Automatisch op tijd": om 20:00 in de app-zone donker
  {
    const { ctx, pg } = await page();
    await pg.clock.install({ time: new Date('2026-11-04T01:57:30+01:00') }); // 19:57:30 in New York
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => { rgSave({ tz: 'America/New_York' }); show('setlook'); });
    await pg.selectOption('#st-theme', 'time'); await pg.waitForTimeout(100);
    const before = await pg.evaluate(() => ({ dark: uiDark(), now: $('#st-th-now').textContent, until: JSON.parse(localStorage.getItem('rt.themeNow')).until }));
    ok(!before.dark && /wordt donker om 20:00/.test(before.now) && before.until === Date.parse('2026-11-03T20:00-05:00'), 'om 19:57 in New York (01:57 in Amsterdam) licht: ' + before.now);
    await pg.clock.runFor(3 * 60000); await pg.waitForTimeout(100);
    const after = await pg.evaluate(() => ({ dark: uiDark(), now: $('#st-th-now').textContent }));
    ok(after.dark && /wordt licht morgen om 07:00/.test(after.now), 'om 20:00 in New York donker: ' + after.now);
    // Terug naar de telefoon: 02:00 in Amsterdam, dus nog steeds donker (tot 07:00)
    await pg.evaluate(() => rgSave({ tz: '' })); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => uiDark() && thState.next === Date.parse('2026-11-04T07:00+01:00')), 'terug naar de telefoon: donker tot 07:00 Amsterdam');
    await ctx.close();
  }

  // 10. Een onbekende zone, en een zone die Java niet kent
  {
    const { ctx, pg } = await page();
    await pg.clock.install({ time: new Date(NOW) });
    await pg.goto(url); await pg.waitForTimeout(300);
    const r = await pg.evaluate(() => {
      store.set('regio', Object.assign({}, rgCfg(), { tz: 'Mars/Olympus' })); rgReload(); show('setregio');
      const a = { zone: rgZone(), own: rgTzOwn(), tz: rgCfg().tz, bad: $('#rg-tz-bad').hidden ? '' : $('#rg-tz-bad').textContent, row: $('#rg-tz').textContent };
      Android._tzNo = ['Asia/Katmandu', 'Asia/Kathmandu'];
      a.java = [rgTzOk('Asia/Kathmandu'), rgTzOk('america/new_york'), rgTzOk('Europe/Nowhere'), rgTzOk('Europe/Paris'), rgTzList().includes('Asia/Kathmandu')];
      a.javaTz = JSON.parse(Android._regioCfg || '{}').tz;
      // Een zone die Java kent maar de WebView niet: de app (JS, Java en de skin) volgt samen de telefoon
      Android._tzYes = ['Pluto/Basis']; rgSave({ tz: 'Pluto/Basis' });
      a.split = [rgTzOk('Pluto/Basis'), rgZone(), JSON.parse(Android._regioCfg || '{}').tz, rgCfg().tz];
      // Een andere naam voor een zone (Asia/Kolkata, in Chrome Asia/Calcutta): gewoon te gebruiken
      rgSave({ tz: 'Asia/Kolkata' });
      a.alias = [rgTzOk('Asia/Kolkata'), rgZone(), rgTzOwn(), JSON.parse(Android._regioCfg || '{}').tz, fmtTime(Date.now()), rgTzFind('asia/kolkata'), rgTzFind('kolkata') !== ''];
      rgSave({ tz: '' });
      return a;
    });
    ok(r.javaTz === '', 'onbekende zone: Java krijgt ook "volg de telefoon" (' + JSON.stringify(r.javaTz) + ')');
    ok(r.split.join('|') === 'false|Europe/Amsterdam||Pluto/Basis', 'zone die alleen Java kent: JS, Java en skin volgen samen de telefoon (' + r.split.join(' · ') + ')');
    ok(r.alias.join('|') === 'true|Asia/Kolkata|true|Asia/Kolkata|08:00|Asia/Kolkata|true', 'andere naam voor een zone (Asia/Kolkata) werkt: ' + r.alias.join(' · '));
    ok(r.zone === 'Europe/Amsterdam' && !r.own && r.tz === 'Mars/Olympus' && r.row === 'Volg de telefoon (Europe/Amsterdam)', 'onbekende zone: de app volgt de telefoon');
    ok(r.bad === 'De gekozen tijdzone (Mars/Olympus) kent deze telefoon niet. De app volgt nu de telefoon (Europe/Amsterdam).', 'en zegt dat op het scherm: ' + r.bad);
    ok(r.java.join() === 'false,false,false,true,false', 'alleen zones die de WebView én Java precies zo kennen (hoofdletters tellen): ' + r.java.join());
    await ctx.close();
  }

  // 11. De assistent: opdrachten met bevestiging; vragen naar de tijd ergens anders veranderen niets
  {
    const { ctx, pg } = await page();
    await pg.clock.install({ time: new Date(NOW) });
    await pg.goto(url); await pg.waitForTimeout(300);
    const r = await pg.evaluate(() => {
      const one = q => { const h = setHandle(q), it = h && h.set && h.set.items && h.set.items[0]; return it ? it.id + '=' + it.to : h && /staat al op/.test(h.text || '') ? 'al ' + h.set.open[0] : h && h.local ? 'tekst' : ''; };
      const ja = ['zet de tijdzone op New York', 'zet de tijdzone op new york', 'gebruik Londense tijd', 'gebruik de tijd van Tokio', 'stel de tijdzone in op Kiritimati', 'zet de tijdzone op America/Los_Angeles',
        'tijdzone Londen', 'tijdzone naar Tokio', 'zet de zone op Londen', 'zet de tijd op Londen', 'zet de tijdzone op Asia/Kolkata', 'neem de tijdzone van Tokio', 'zet de tijdzone op Berlijn',
        'gebruik de tijd van Boedapest', 'tijdzone Kaapstad', 'zet de tijdzone op Peking'].map(q => q + ' → ' + one(q));
      const ask = setHandle('hoe laat is het in New York'), ask2 = setHandle('Wat is de tijd in Tokio?'), ask3 = setHandle('wat is het tijdsverschil met Tokio'), ask4 = setHandle('hoe laat is het op Curaçao'), ask5 = setHandle('Hoe laat is het op Curacao?');
      const nee = ['hoe laat is het in New York', 'wat is de tijd in Tokio', 'hoe laat is het', 'gebruik de telefoon', 'zet de tijd op 12 uur', 'zet de wekker op 7 uur', 'gebruik de radio', 'zet de tijdzone op de maan',
        'neem de tijd van Regina', 'neem de tijd van Jos', 'wat is het tijdsverschil met Tokio', 'hoe laat is het op Curaçao', 'zet de tijd op morgen', 'tijdzone', 'hoe laat is het in de keuken'].map(q => q + ' → ' + one(q));
      const tzAfter = rgCfg().tz;
      setEntry('regio.tijdzone').set('Asia/Tokyo');
      const set = [rgCfg().tz, setGet(setEntry('regio.tijdzone')), setName(setEntry('regio.tijdzone'), 'Asia/Tokyo')];
      const back = ['volg de telefoon', 'zet de tijdzone op de telefoon', 'laat de tijdzone de telefoon volgen', 'volg de tijd van de telefoon'].map(q => q + ' → ' + one(q));
      setEntry('regio.tijdzone').set('telefoon');
      return { ja, nee, ask: ask && ask.text, ask2: ask2 && ask2.text, ask3: ask3 && ask3.text, ask4: ask4 && ask4.text, ask5: ask5 && ask5.text, askSet: !!(ask && ask.set && ask.set.items), tzAfter, set, back, end: rgCfg().tz };
    });
    const want = ['regio.tijdzone=America/New_York', 'regio.tijdzone=America/New_York', 'regio.tijdzone=Europe/London', 'regio.tijdzone=Asia/Tokyo', 'regio.tijdzone=Pacific/Kiritimati', 'regio.tijdzone=America/Los_Angeles',
      'regio.tijdzone=Europe/London', 'regio.tijdzone=Asia/Tokyo', 'regio.tijdzone=Europe/London', 'regio.tijdzone=Europe/London', 'regio.tijdzone=Asia/Kolkata', 'regio.tijdzone=Asia/Tokyo', 'regio.tijdzone=Europe/Berlin',
      'regio.tijdzone=Europe/Budapest', 'regio.tijdzone=Africa/Johannesburg', 'regio.tijdzone=Asia/Shanghai'];
    r.ja.forEach((x, i) => ok(x.endsWith('→ ' + want[i]), 'assistent: ' + x));
    ok(/^In New York is het nu 21:30, 6 uur eerder dan hier \(dinsdag 3 november, UTC−5\)\./.test(r.ask) && !r.askSet && /^In Tokio is het nu 11:30, 8 uur later dan hier \(woensdag 4 november, UTC\+9\)/.test(r.ask2), 'een vraag naar de tijd ergens anders: alleen een antwoord (' + r.ask + ')');
    ok(/^In Tokio is het nu 11:30, 8 uur later dan hier/.test(r.ask3) && /^In Curaçao is het nu 22:30, 5 uur eerder dan hier/.test(r.ask4) && r.ask5 === r.ask4, 'tijdsverschil en Curaçao: ' + r.ask3 + ' / ' + r.ask4);
    ok(r.nee.every(x => !/regio\.tijdzone=/.test(x)) && r.tzAfter === '', 'geen valse treffers (de tijdzone blijft zoals hij was): ' + r.nee.join(' | '));
    ok(r.set.join('|') === 'Asia/Tokyo|Asia/Tokyo|Tokio (UTC+9)', 'register: zetten via dezelfde functie als het scherm (' + r.set.join(' · ') + ')');
    ok(r.back.every(x => x.endsWith('→ regio.tijdzone=telefoon')) && r.end === '', 'terug: ' + r.back.join(' | '));
    await ctx.close();
  }

  // 13. De lijst: alle zones (ook UTC+12 tot +14), snel genoeg, rustig voor de schermlezer, en smal (360 px)
  {
    const { ctx, pg } = await page('light', 'America/Argentina/Buenos_Aires');
    await pg.setViewportSize({ width: 360, height: 780 });
    await pg.clock.install({ time: new Date(NOW) });
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => rgTzOpen()); await pg.waitForTimeout(150);
    const l = await pg.evaluate(() => { const z = [...document.querySelectorAll('#rgz-list .sbtn')].map(b => b.dataset.z); return { n: z.length, auck: z.includes('Pacific/Auckland'), kiri: z.includes('Pacific/Kiritimati'), fiji: z.includes('Pacific/Fiji'), last: z[z.length - 1] }; });
    ok(l.auck && l.kiri && l.fiji && l.last === 'Pacific/Kiritimati', 'ongefilterde lijst: ook Auckland, Fiji en Kiritimati (UTC+14) helemaal onderaan (' + l.n + ' zones)');
    const a = await pg.evaluate(async () => {
      let muts = 0; new MutationObserver(m => { muts += m.length; }).observe($('#rgz-now'), { childList: true, characterData: true, subtree: true });
      const t0 = performance.now();
      for (const q of ['n', 'ne', 'new', 'new ', 'new y', 'new yo', '']) { $('#rgz-q').value = q; rgzRender(); }
      const per = (performance.now() - t0) / 7;
      await new Promise(r => setTimeout(r, 0));
      const b = document.querySelector('#rgz-list .sbtn[aria-pressed=true] b'), hid = b && b.querySelector('[aria-hidden=true]');
      const first = document.querySelector('#rgz-list .sbtn b');
      return { muts, per, check: !!hid && hid.textContent.trim() === '✓', wrap: first.scrollWidth <= first.clientWidth + 1 && first.getBoundingClientRect().height > parseFloat(getComputedStyle(first).fontSize) * 1.6, w: document.documentElement.scrollWidth };
    });
    ok(a.muts === 0, 'zoeken schrijft "Nu: …" (aria-live) niet steeds opnieuw (' + a.muts + ' keer)');
    ok(a.check, 'het vinkje bij de gekozen zone is verborgen voor de schermlezer (aria-pressed zegt het al)');
    ok(a.wrap && a.w <= 360, '360 px: "Volg de telefoon (America/Argentina/Buenos_Aires)" breekt af in plaats van weg te vallen');
    ok(a.per < 400, 'een toets in het zoekveld: ' + Math.round(a.per) + ' ms (ruim onder 400 ms)');
    // 20.000 tijdstippen in een eigen zone: per uur onthouden, niet per minuut opnieuw rekenen
    const p = await pg.evaluate(() => {
      rgSave({ tz: 'America/New_York' });
      const base = Date.parse('2026-01-01T00:00:00Z'), t0 = performance.now(); let x = '';
      for (let i = 0; i < 20000; i++) x = rgDayKey(base + i * 37 * 6e4);
      const ms = performance.now() - t0;
      // Precies rond de wissel (1 november 2026, 06:00 UTC): het uur met de wissel per minuut
      const at = [Date.parse('2026-11-01T05:59:00Z'), Date.parse('2026-11-01T06:00:00Z'), Date.parse('2026-11-01T06:30:00Z')].map(t => rgHm(t)).join(',');
      rgSave({ tz: '' });
      return { ms, x, at };
    });
    ok(p.ms < 1500 && p.x === '2027-05-29', '20.000 keer rgDayKey in New York: ' + Math.round(p.ms) + ' ms (grens 1500 ms)');
    ok(p.at === '01:59,01:00,01:30', 'rond de wissel naar wintertijd klopt het per minuut: ' + p.at);
    await ctx.close();
  }

  // 12. Donker, en de opmaak in beide
  for (const scheme of ['light', 'dark']) {
    const { ctx, pg } = await page(scheme);
    await pg.clock.install({ time: new Date(NOW) });
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => { rgSave({ tz: 'America/New_York' }); rgTzOpen(); }); await pg.waitForTimeout(150);
    const c = await pg.evaluate(() => ({ w: document.documentElement.scrollWidth, on: getComputedStyle(document.querySelector('#rgz-list .sbtn[aria-pressed=true]') || document.body).color, n: document.querySelectorAll('#rgz-list .sbtn[aria-pressed=true]').length }));
    ok(c.w <= 390 && c.n === 1, scheme + ': lijst zonder horizontaal schuiven, één zone gekozen');
    if (scheme === 'dark') await pg.screenshot({ path: path.join(__dirname, 'shots', 'tijdzone-donker.png'), fullPage: false });
    await ctx.close();
  }

  await b.close();
  console.log('errors', errs);
})();
