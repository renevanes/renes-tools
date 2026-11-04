// De Telefoon-skin (web/start.html) in het Engels: datum, klok, wekker, agenda en weer in het Engels (en-GB), vaste tekst
// vertaald, en gegevens (app-namen van de telefoon, afspraken, vastgezette notities) zoals ze zijn. Licht en donker.
// De testbrug van de skin geeft het woordenboek als localStorage skinTestTaal gezet is (de app: Android.taal()).
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + path.join(__dirname, '..', '..', 'web', 'start.html');
  const dict = fs.readFileSync(path.join(__dirname, '..', '..', 'web', 'src', 'taal', 'en.json'), 'utf8');
  for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, timezoneId: 'Europe/Amsterdam' });
    await ctx.addInitScript(d => { try { localStorage.setItem('skinTestTaal', d); localStorage.setItem('skinTestRegio', '{"lang":"en"}'); } catch (e) {} }, dict);
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(scheme + ': ' + e.message));
    await pg.goto(url); await pg.waitForTimeout(600);
    const r = await pg.evaluate(() => ({
      lang: document.documentElement.lang, on: TAAL.on,
      date: $('#date').textContent, alarm: $('#alarm').textContent.trim(),
      weather: ($('#weather') || {}).innerText || '', cal: ($('#cal') || {}).innerText || '',
      apps: (openDrawer(), [...document.querySelectorAll('.app .lb')].map(x => x.textContent)),
      notes: ($('#snotes') || {}).innerText || '',
      search: $('#q').getAttribute('placeholder'),
    }));
    ok(r.lang === 'en' && r.on, scheme + ': skin in het Engels');
    ok(/^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday) \d{1,2} (January|February|March|April|May|June|July|August|September|October|November|December)$/.test(r.date), 'datum Engels (en-GB): ' + r.date);
    ok(!r.alarm || /^(Mon|Tue|Wed|Thu|Fri|Sat|Sun)/.test(r.alarm), 'wekker Engels: ' + r.alarm);
    ok(/Cloudy · Rotterdam/.test(r.weather) && /chance of rain/.test(r.weather) && !/Bewolkt|kans op regen/.test(r.weather), 'weer Engels, plaats zoals hij is: ' + r.weather.replace(/\s+/g, ' ').slice(0, 80));
    ok(/Calendar/i.test(r.cal) && !/\b(morgen|vandaag|hele dag)\b/.test(r.cal), 'agenda Engels (tomorrow/today/all day): ' + r.cal.replace(/\s+/g, ' ').slice(0, 120));
    ok(r.apps.includes('Instellingen') && r.apps.includes('Agenda') && !r.apps.includes('Settings') && !r.apps.includes('Calendar'), 'app-namen van de telefoon niet vertaald (Instellingen, Agenda): ' + r.apps.slice(0, 12).join(', '));
    ok(r.search === 'Search apps…', 'zoekveld Engels: ' + r.search);
    // Vastgezette notities: titel en items zijn gegevens
    if (r.notes) ok(!/\b(Milk|Bread|Groceries)\b/.test(r.notes), 'vastgezette notities niet vertaald: ' + r.notes.replace(/\s+/g, ' ').slice(0, 80));
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'skin-engels-' + scheme + '.png') });
    await ctx.close();
  }
  // Zonder Engels: Nederlands, zoals altijd
  {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, timezoneId: 'Europe/Amsterdam' });
    const pg = await ctx.newPage(); pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(500);
    const r = await pg.evaluate(() => ({ lang: document.documentElement.lang, on: TAAL.on, date: $('#date').textContent, search: $('#q').getAttribute('placeholder') }));
    ok(r.lang !== 'en' && !r.on && /^(Maandag|Dinsdag|Woensdag|Donderdag|Vrijdag|Zaterdag|Zondag) /.test(r.date) && r.search === 'Zoek apps…', 'standaard Nederlands: ' + r.date);
    await ctx.close();
  }
  await b.close();
  console.log('errors', errs);
})();
