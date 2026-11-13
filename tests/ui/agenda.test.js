// Agenda: dag, week, maand en lijst, weeknummers, zoeken in het verleden, agenda van de telefoon, assistent uit tot je hem aanzet.
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const browser = await chromium.launch(), errors = [];
  for (const colorScheme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme, timezoneId: 'Europe/Amsterdam' });
    page.on('pageerror', e => errors.push(colorScheme + ': ' + e.message));
    const ok = (v, m) => { console.log((v ? '✓ ' : '✗ ') + m); if (!v) errors.push(colorScheme + ': ' + m); };
    await page.goto('file://' + path.join(__dirname, 'ui/index.html'));
    await page.waitForTimeout(250);

    ok(await page.evaluate(() => HOME_DEFAULT.groups.find(g => g.name === 'Dag & gezondheid').tools.includes('agenda') && TILE_TOOL['tile-agenda'] === 'agenda' && !!TOOLS.agenda && UI_TOOL_ICONS.agenda === 'calendar'), 'aangemeld: Dag & gezondheid, TOOLS, pictogram');
    ok(await page.isVisible('#tile-agenda') && (await page.textContent('#tile-agenda')).includes('Agenda'), 'tegel op het startscherm');
    await page.click('#tile-agenda');
    await page.waitForTimeout(150);
    ok(await page.evaluate(() => current === 'agenda'), 'tegel opent Agenda');
    ok((await page.textContent('#ag-priv')).includes('blijven op je telefoon') && (await page.textContent('#ag-priv')).includes('naar Google'), 'privacytekst zichtbaar');
    const week = await page.evaluate(() => agIsoWeek(rgToday()));
    ok((await page.textContent('#ag-periode')).includes('Week ' + week), 'weekweergave met weeknummer ' + week);
    await page.click('#ag-views [data-v="maand"]');
    await page.waitForTimeout(80);
    const maand = await page.evaluate(() => {
      const w = [...document.querySelectorAll('.ag-wk')].map(e => e.textContent).filter(Boolean);
      const d = [...document.querySelectorAll('.ag-dag')];
      const klein = d.filter(b => { const r = b.getBoundingClientRect(); return r.width < 44 || r.height < 44; }).length;
      return { w: w.length, klein, nu: document.querySelector('.ag-dag.is-today') && document.querySelector('.ag-dag.is-today').dataset.dag };
    });
    ok(maand.w >= 4 && maand.klein === 0 && maand.nu, 'maand: weeknummers, dagknoppen minstens 44 px, vandaag gemarkeerd');
    await page.click('#ag-views [data-v="lijst"]');
    await page.waitForTimeout(80);
    ok((await page.textContent('#ag-periode')).length > 3, 'lijst toont de maand');
    await page.fill('#ag-zoek', 'overleg');
    await page.waitForTimeout(350);
    ok((await page.textContent('#ag-lijst')).includes('Overleg'), 'zoeken vindt de afspraak van morgen');
    ok(!(await page.textContent('#ag-lijst')).includes('Stand-up'), 'zonder toestemming geen agenda van de telefoon');
    ok(await page.evaluate(() => {
      const aan = agPeriode('aanstaande vrijdag', '2026-11-11');
      const vol = agPeriode('volgende vrijdag', '2026-11-11');
      const wk = agPeriode('volgende week woensdag', '2026-11-11');
      const morgen = agVenster('wat staat er morgen in mijn agenda');
      return aan && aan.van === '2026-11-13' && vol && vol.van === '2026-11-20' && wk && wk.van === '2026-11-18' && morgen.woorden.length === 0;
    }), 'aanstaande vrijdag, volgende vrijdag en volgende week woensdag');
    await page.fill('#ag-zoek', '');
    await page.waitForTimeout(250);

    await page.click('#ag-nieuw');
    await page.waitForTimeout(80);
    await page.click('#s-agafspraak .big');
    ok((await page.textContent('#toast')).includes('Vul een titel in.'), 'lege titel');
    await page.fill('#agf-titel', 'Vergadering');
    await page.fill('#agf-dag', '2020-05-04');
    await page.fill('#agf-eind', '2020-05-04');
    await page.fill('#agf-plek', 'Kantoor');
    await page.click('#s-agafspraak .big');
    await page.waitForTimeout(150);
    ok(await page.evaluate(() => current === 'agenda'), 'opslaan gaat terug');
    await page.fill('#ag-zoek', 'tandarts');
    await page.waitForTimeout(350);
    const tand = await page.textContent('#ag-lijst');
    ok(tand.includes('Tandarts') && !tand.includes('Vergadering'), 'zoeken op tandarts toont niet de vergadering');
    await page.click('#ag-lijst .ag-item');
    await page.waitForTimeout(80);
    ok(await page.inputValue('#agf-titel') === 'Tandarts' && await page.inputValue('#agf-dag') === '2024-03-12', 'afspraak uit 2024 open');
    await page.fill('#agf-plek', 'Centrum');
    await page.click('#s-agafspraak .big');
    await page.waitForTimeout(100);
    await page.fill('#ag-zoek', 'vergadering');
    await page.waitForTimeout(350);
    ok((await page.textContent('#ag-lijst')).includes('Vergadering'), 'ook de afspraak uit 2020');
    await page.click('#ag-lijst .ag-item');
    await page.click('#agf-wis');
    await page.click('#modal-ok');
    await page.waitForTimeout(150);
    ok(await page.evaluate(() => current === 'agenda' && !Android._agInit().items.some(x => x.titel === 'Vergadering')), 'verwijderen');

    await page.click('#ag-set');
    await page.waitForTimeout(80);
    ok(await page.getAttribute('#ag-ai', 'aria-checked') === 'false', 'assistent staat uit');
    ok((await page.textContent('#ag-kals')).includes('toestemming') || (await page.textContent('#ag-kals')).includes('Geen agenda'), 'nog geen agenda\'s');
    await page.click('#ag-toestem-b');
    await page.waitForTimeout(200);
    ok((await page.textContent('#ag-kals')).includes('Feestdagen') && (await page.textContent('#ag-kals')).includes('Alleen lezen'), 'agenda\'s van de telefoon, ook alleen-lezen');
    await page.evaluate(() => goBack());
    await page.waitForTimeout(80);
    await page.fill('#ag-zoek', 'stand');
    await page.waitForTimeout(350);
    ok((await page.textContent('#ag-lijst')).includes('Stand-up'), 'na toestemming ook de agenda van de telefoon');
    await page.fill('#ag-zoek', '');
    await page.waitForTimeout(250);

    await page.click('#ag-nieuw');
    await page.selectOption('#agf-kal', '1');
    ok(await page.isVisible('#agf-google'), 'Google-agenda: de hint dat de agenda-app hem doorstuurt');
    await page.fill('#agf-titel', 'Met Google');
    await page.click('#s-agafspraak .big');
    await page.waitForTimeout(100);
    ok(await page.evaluate(() => { const it = Android._agInit().items.find(x => x.titel === 'Met Google'); return it && it.kalender === '1' && it.ext > 0; }), 'afspraak in de agenda van de telefoon');
    await page.click('#ag-nieuw');
    await page.selectOption('#agf-kal', '3');
    await page.fill('#agf-titel', 'Feestdag');
    await page.click('#s-agafspraak .big');
    ok((await page.textContent('#toast')).includes('alleen-lezen'), 'alleen-lezen weigert');
    await page.evaluate(() => goBack());

    await page.evaluate(() => show('search'));
    await page.fill('#gs-q', 'tandarts');
    await page.waitForTimeout(200);
    // Zoeken tekent de lijst na een korte pauze (opnieuw) en de agenda-resultaten komen later: wachten tot ze er zijn
    await page.waitForFunction(() => /Tandarts/.test($('#gs-res').textContent), null, { timeout: 5000 }).catch(() => {});
    ok((await page.textContent('#gs-res')).includes('Tandarts'), 'overal zoeken vindt een afspraak van vroeger');
    await page.waitForTimeout(300);
    // Alleen het resultaat van Agenda: ":has-text" vond soms eerst een sms, WhatsApp-bericht of oproep met "tandarts".
    // Komen die eerst binnen, dan staat de afspraak onderaan achter de tabbalk: eerst naar het midden schuiven.
    const agHit = page.locator('#gs-res .gi[data-k="agenda"]', { hasText: 'Tandarts' }).first();
    await agHit.evaluate(e => e.scrollIntoView({ block: 'center' })); await page.waitForTimeout(100);
    await agHit.click();
    await page.waitForFunction(() => current === 'agafspraak', null, { timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(150);
    ok(await page.evaluate(() => current === 'agafspraak') && await page.inputValue('#agf-titel') === 'Tandarts', 'vanuit zoeken de afspraak openen');

    await page.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; Android._ai.reply = 'Prima.'; show('ai'); });
    await page.waitForTimeout(200);
    await page.fill('#ai-in', 'Zet morgen om 10 uur de tandarts in mijn agenda');
    await page.click('#ai-send');
    await page.waitForTimeout(300);
    const kaap = await page.textContent('#ai-msgs .aimsg:last-child');
    ok(!kaap.includes('mag ik nog niet'), 'zetten: niet de melding dat Agenda uit staat (' + kaap.slice(0, 80) + ')');
    await page.fill('#ai-in', 'wat staat er morgen in mijn agenda');
    await page.click('#ai-send');
    await page.waitForTimeout(250);
    ok((await page.textContent('#ai-msgs .aimsg:last-child')).includes('mag ik nog niet') && (await page.textContent('#ai-msgs .aimsg:last-child')).includes('Agenda'), 'bron uit: eerlijk zeggen');
    await page.evaluate(() => { show('agaset'); });
    await page.waitForTimeout(80);
    await page.click('#ag-ai');
    ok(await page.evaluate(() => Android._ai.src.agenda === true), 'aanzetten bij Agenda');
    await page.evaluate(() => show('ai'));
    await page.fill('#ai-in', 'wat staat er morgen in mijn agenda');
    await page.click('#ai-send');
    await page.waitForTimeout(250);
    ok((await page.textContent('#ai-msgs .aimsg:last-child')).includes('Overleg'), 'bron aan: afspraak van morgen');
    await page.screenshot({ path: path.join(__dirname, 'shots', 'agenda-' + colorScheme + '.png'), fullPage: true });
    await page.close();
  }
  await browser.close();
  console.log('errors', errors);
  process.exit(errors.length ? 1 : 0);
})();
