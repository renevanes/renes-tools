// Check-in terugkijken: kalender (met vorige maand en vegen), een dag met slaap en agenda, achteraf invullen, wijzigen
// en wissen, grafiek (week, maand, jaar) met tekst, samenvatting en reeks, vergelijken (vraag, slaap, agenda),
// de assistent (bron staat standaard uit), en het privé onderdeel bij Alles back-uppen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    // Een vraag van 40 dagen geleden, elke dag om 08:00; de afgelopen 12 dagen afwisselend ingevuld (via achteraf)
    const D = await pg.evaluate(() => {
      Android.checkinState();
      const q = Android._ci.qs.find(x => x.id === 'voel'), w = Android._ci.qs.find(x => x.id === 'werk');
      Object.assign(q, { on: true, tpl: false, times: ['08:00'], until: '23:59', created: Date.now() - 40 * 864e5 });
      Object.assign(w, { on: true, tpl: false, rhythm: 'daily', times: ['08:00'], until: '23:59', created: Date.now() - 40 * 864e5 });
      const key = n => { const d = new Date(); d.setDate(d.getDate() - n); return ciDayKey(d); }, out = { keys: [] };
      for (let n = 2; n <= 13; n++) {
        const k = key(n); out.keys.push(k);
        Android.checkinAnswer(JSON.stringify({ q: 'voel', p: k, o: n % 2 ? 4 : 1 })); // 😄 of 🙁
        Android.checkinAnswer(JSON.stringify({ q: 'werk', p: k, o: n % 2 ? 2 : 0 }));
        Android._ci.days[k] = { sleep: { min: n % 2 ? 480 : 330, talk: 1, heard: 3 }, cal: { n: n % 2 ? 1 : 5, allDay: 0, busy: n % 2 ? 60 : 300 } };
      }
      out.yesterday = key(1);
      return out;
    });
    ok(await pg.evaluate(() => Android._ci.ans.length === 24 && Android._ci.ans.every(a => a.late)), 'achteraf ingevuld (bewaard als achteraf)');
    await pg.evaluate(() => { show('checkin'); ciOpen('voel'); }); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'checkinq') && (await pg.textContent('#ciq-title')) === '🙂 Hoe voel je je?', 'terugkijken: de vraag');
    // Kalender: naar de maand van gisteren als dat nodig is
    const needPrev = await pg.evaluate(y => ciDayKey(ciqMonthAt).slice(0, 7) !== y.slice(0, 7), D.yesterday);
    if (needPrev) { await pg.click('#ciq-prev'); await pg.waitForTimeout(200); }
    const cells = await pg.$$eval('#ciq-grid .ci-d', x => x.length);
    ok(cells >= 28 && cells <= 31, 'kalender: een vakje per dag (' + cells + ')');
    const yc = '#ciq-grid .ci-d[data-k="' + D.yesterday + '"]';
    ok((await pg.getAttribute(yc, 'class')).includes('miss') && (await pg.getAttribute(yc, 'aria-label')).endsWith(': gemist'), 'gisteren: gemist (ook in tekst)');
    const inMonth = D.keys.filter(k => k.slice(0, 7) === D.yesterday.slice(0, 7));
    if (inMonth.length) {
      const k = inMonth[0], lab = await pg.getAttribute('#ciq-grid .ci-d[data-k="' + k + '"]', 'aria-label');
      ok(/: (😄 Heel goed|🙁 Slecht)$/.test(lab) && /l[1-4]/.test(await pg.getAttribute('#ciq-grid .ci-d[data-k="' + k + '"]', 'class')), 'ingevulde dag: kleur en het antwoord als tekst (' + lab + ')');
    }
    ok((await pg.textContent('#ciq-legend')).includes('gemist') && (await pg.textContent('#ciq-calsum')).includes('gemist'), 'legenda en samenvatting in tekst');
    await pg.screenshot({ path: __dirname + '/shots/checkin-kalender-' + scheme + '.png', fullPage: true });
    // Vorige maand en terug (knoppen); vegen
    const m0 = await pg.textContent('#ciq-month');
    await pg.click('#ciq-prev'); await pg.waitForTimeout(150);
    const m1 = await pg.textContent('#ciq-month');
    ok(m1 !== m0, 'vorige maand: ' + m1);
    await pg.evaluate(() => { const g = $('#ciq-grid'), t = (type, x) => { const e = new Event(type, { bubbles: true }); e.touches = [{ clientX: x, clientY: 300 }]; e.changedTouches = [{ clientX: x, clientY: 300 }]; g.dispatchEvent(e); }; t('touchstart', 300); t('touchend', 100); });
    await pg.waitForTimeout(150);
    ok((await pg.textContent('#ciq-month')) === m0, 'vegen naar links: weer de volgende maand');
    ok(await pg.isDisabled('#ciq-next') || needPrev, 'niet verder dan deze maand');
    // Een dag: antwoord, slaap en agenda (eerst zonder toestemming voor de agenda)
    if (inMonth.length) {
      await pg.click('#ciq-grid .ci-d[data-k="' + inMonth[0] + '"]'); await pg.waitForTimeout(150);
      const day = await pg.textContent('#ciq-day');
      ok(day.includes('achteraf ingevuld') && day.includes('Slaappraat') && day.includes('luisterde') && day.includes('Toegang geven'), 'dag: antwoord, nacht van Slaappraat, en agenda-toegang aanbieden');
      await pg.click('#ciq-day button:has-text("Toegang geven")'); await pg.evaluate(() => onResumeApp()); await pg.waitForTimeout(150);
      await pg.click('#ciq-grid .ci-d[data-k="' + inMonth[0] + '"]'); await pg.waitForTimeout(150);
      ok(await pg.evaluate(() => Android._ci.calOk) && /Agenda: \d+ afspra/.test(await pg.textContent('#ciq-day')), 'na toestemming: afspraken van die dag');
    }
    // Achteraf invullen (gisteren), met label
    await pg.click(yc); await pg.waitForTimeout(150);
    await pg.click('#ciq-day button:has-text("Achteraf invullen")'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#ci-ans') && (await pg.textContent('#ci-ans-p')).includes('Gisteren') && (await pg.textContent('#ci-ans-p')).includes('achteraf'), 'achteraf invullen: gisteren');
    await pg.click('#ci-ans-opts .ci-opt >> nth=3'); await pg.waitForTimeout(100);
    await pg.click('#ci-ans-x button[data-l="gesport"]'); await pg.click('#ci-ans-done'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(y => { const a = Android._ci.ans.find(x => x.q === 'voel' && x.p === y); return a && a.late && a.v === 4 && a.labels.join() === 'gesport'; }, D.yesterday), 'gisteren achteraf ingevuld, met label');
    ok(!(await pg.getAttribute(yc, 'class')).includes('miss') && (await pg.textContent('#ciq-day')).includes('gesport'), 'kalender en dag bijgewerkt');
    // Wijzigen en wissen
    await pg.click('#ciq-day button[aria-label^="Wijzigen"]'); await pg.waitForTimeout(150);
    await pg.click('#ci-ans-opts .ci-opt >> nth=4'); await pg.waitForTimeout(100); await pg.click('#ci-ans-done'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(y => Android._ci.ans.find(x => x.q === 'voel' && x.p === y).v === 5, D.yesterday), 'gewijzigd naar 😄');
    await pg.click('#ciq-day button[aria-label^="Wissen"]'); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(y => !Android._ci.ans.some(x => x.q === 'voel' && x.p === y), D.yesterday) && (await pg.getAttribute(yc, 'class')).includes('miss'), 'gewist: weer gemist');
    // Achteraf invullen uit: geen knop
    await pg.evaluate(() => { Android._ci.qs.find(x => x.id === 'voel').backfill = false; ciqLoad(); });
    await pg.click(yc); await pg.waitForTimeout(100);
    ok(!(await pg.isVisible('#ciq-day button:has-text("Achteraf invullen")')) && (await pg.textContent('#ciq-day')).includes('staat uit'), 'achteraf invullen uit: niet aangeboden');
    await pg.evaluate(() => { Android._ci.qs.find(x => x.id === 'voel').backfill = true; ciqLoad(); });
    // Grafiek: maand, week, jaar; met tekst en tabel
    ok(await pg.isVisible('#ciq-chart svg[role=img]') && (await pg.getAttribute('#ciq-chart svg', 'aria-label')).includes('Gemiddeld'), 'grafiek (maand) met beschrijving');
    ok((await pg.$$('#ciq-chart .ci-bar')).length >= 1 && (await pg.$$('#ciq-chart .ci-avg')).length === 1, 'staafjes en gemiddelde');
    await pg.click('#ciq-chart summary'); await pg.waitForTimeout(50);
    ok((await pg.$$('#ciq-chart tbody tr')).length >= 1, 'als tabel');
    await pg.click('#ciq-view button[data-v=year]'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ciq-chart')).includes('Per maand') && (await pg.textContent('#ciq-chart')).includes('trend'), 'jaar: per maand, met trend');
    await pg.click('#ciq-view button[data-v=week]'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ciq-chart')).includes('afgelopen 7 dagen') && (await pg.$$('#ciq-chart .ci-bar')).length >= 3, 'week: de afgelopen 7 dagen');
    await pg.screenshot({ path: __dirname + '/shots/checkin-grafiek-' + scheme + '.png', fullPage: true });
    // Samenvatting en reeks
    const sum = await pg.textContent('#ciq-sum');
    ok(/(Deze maand|In \w+ \d{4}) .*\d+× /.test(sum) && sum.includes('Reeks: 0 dagen') && sum.includes('gemist'), 'samenvatting: tellingen, reeks (gisteren gemist = 0)');
    await pg.evaluate(y => Android.checkinAnswer(JSON.stringify({ q: 'voel', p: y, o: 2 })), D.yesterday);
    await pg.evaluate(() => ciqLoad()); await pg.waitForTimeout(100);
    const streak = await pg.evaluate(() => ciqHist.summary.streak);
    ok(streak === 13, 'reeks: 13 dagen achter elkaar (vandaag breekt hem niet) (' + streak + ')');
    // Vergelijken: met een andere vraag, met slaap en met de agenda
    await pg.evaluate(() => { Android._ci.calOk = true; });
    await pg.click('#s-checkinq button:has-text("Vergelijken met")'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'checkincmp' && $('#cic-a').value === 'voel'), 'vergelijken: vraag voorgekozen');
    ok((await pg.textContent('#cic-out')).includes('Na een korte nacht') && (await pg.textContent('#cic-out')).includes('gemiddeld, 6 dagen'), 'met slaap: korte en langere nachten (6 dagen elk)');
    await pg.selectOption('#cic-b', '__cal'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#cic-out')).includes('Drukke dagen') && (await pg.textContent('#cic-out')).includes('Rustigere dagen'), 'met de agenda: drukke en rustigere dagen');
    await pg.selectOption('#cic-b', 'werk'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#cic-out')).includes('allebei ingevuld') && await pg.isVisible('#cic-out svg[role=img]'), 'met een andere vraag: naast elkaar, als grafiek');
    ok((await pg.textContent('#s-checkincmp')).includes('niet wat de oorzaak is') && (await pg.textContent('#s-checkincmp')).includes('geen medisch advies') && (await pg.textContent('#cic-out')).includes('Op dagen dat "Hoe voel je je?" in de bovenste helft zat'), 'eerlijk: geen oorzaak, geen medisch advies; nette zin');
    await pg.evaluate(() => { Android._ci.calOk = false; }); await pg.selectOption('#cic-b', '__cal'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#cic-out')).includes('Toegang tot de agenda geven'), 'zonder toestemming: toegang aanbieden');
    await pg.screenshot({ path: __dirname + '/shots/checkin-vergelijken-' + scheme + '.png', fullPage: true });
    // De assistent: bron Check-in staat standaard uit, aan te zetten bij Lokale AI → Gegevens
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; Android._ai.st.zoek = { state: 'ready' }; show('aisetdata'); }); await pg.waitForTimeout(200);
    const sw = '#aid-list .tg[aria-label="Check-in"]';
    ok(await pg.isVisible(sw) && (await pg.getAttribute(sw, 'aria-checked')) === 'false', 'assistent: Check-in staat standaard uit');
    await pg.click(sw); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ai.src.checkin === true) && (await pg.textContent('#toast')).includes('Check-in doet mee'), 'aangezet');
    await pg.evaluate(() => { const g = aiHitGs({ kind: 'checkin', id: 'voel|' + ciDayKey(new Date()).slice(0, 7) }); const el = document.createElement('button'); el.dataset.k = g[0]; el.dataset.x = JSON.stringify(g[1]); gsOpen(el); });
    await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'checkinq' && ciqId === 'voel'), 'bron van de assistent opent de vraag in die maand');
    // Alles back-uppen: privé onderdeel, standaard uit, zonder versleutelen eerst toestemming
    await pg.evaluate(() => { Android._wa.info.dest = true; show('backup'); }); await pg.waitForTimeout(200);
    ok(!(await pg.isChecked('#bk-private input[data-p="checkin"]')) && (await pg.textContent('#bk-private')).includes('2 vragen, 57 antwoorden'), 'backup: Check-in staat uit, met wat erin zit');
    await pg.click('#bk-private input[data-p="checkin"]'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal-title')) === 'Onversleuteld meenemen?' && (await pg.textContent('#modal-text')).includes('Check-in is privé') && !(await pg.evaluate(() => Android._bk.parts.checkin)), 'zonder versleutelen: eerst toestemming');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._bk.parts.checkin && Android._bk.plain.checkin) && await pg.isVisible('#bk-priv-warn'), 'na toestemming: mee, met waarschuwing');
    const nl = await pg.evaluate(() => [bkPrivNoLonger(['checkin']), bkPrivNoLonger(['ai', 'sleep', 'checkin']), bkPrivNoLonger(['sleep', 'checkin'])]);
    ok(nl[0].startsWith('Check-in gaat dan niet meer mee, want dat is privé') && nl[1].startsWith('Het geheugen en de gesprekken van de assistent, Slaappraat, en Check-in, gaan') && nl[2].startsWith('Slaappraat en Check-in gaan'), 'versleutelen uit noemt Check-in: ' + nl.join(' | '));
    await pg.click('#s-backup button[onclick*="restorePick(\'checkin\')"]'); await pg.waitForTimeout(250);
    ok((await pg.textContent('#modal-title')) === 'Check-in terugzetten?' && (await pg.textContent('#modal-text')).includes('30 nieuw') && (await pg.textContent('#modal-text')).includes('niets overschreven'), 'terugzetten: overzicht, voegt alleen toe');
    await pg.click('#modal-ok'); await pg.waitForTimeout(250);
    ok((await pg.textContent('#toast')).includes('31 vragen en antwoorden toegevoegd'), 'teruggezet');
    await pg.click('button:has-text("Versleutelde backup openen")'); await pg.waitForTimeout(300);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(400);
    ok((await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).includes('Check-in terugzetten'), 'versleutelde backup: Check-in terugzetten');
    await pg.click('#sheet-acts button:has-text("Check-in terugzetten")'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => Android._arcRestore === 'checkin|Check-in/checkin.json') && (await pg.textContent('#modal-title')) === 'Check-in terugzetten?', 'uit het archief: zelfde overzicht');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(100);
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
})();
