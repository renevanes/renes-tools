// Check-in: de voorstellen, aanzetten (met toestemming voor meldingen), antwoorden in de app, vervangen, de tegel,
// een vraag maken en bewerken, ordenen, dupliceren, archiveren, verwijderen, en Zoeken. Licht en donker, 320 en 390 px breed.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    // Een tijd die nu open staat (een uur geleden, open tot over twee uur), wat de klok ook is
    const open = await pg.evaluate(() => { const p = n => String(n).padStart(2, '0'), a = new Date(Date.now() - 36e5), u = new Date(Date.now() + 2 * 36e5); return [p(a.getHours()) + ':' + p(a.getMinutes()), p(u.getHours()) + ':' + p(u.getMinutes())]; });
    ok(await pg.isVisible('#tile-checkin') && (await pg.textContent('#tile-checkin-sub')) === 'Even kort je score bijhouden', 'tegel op het startscherm');
    ok(await pg.evaluate(() => homeCfg().groups.find(g => g.name === 'Handig').tools.includes('checkin')), 'tegel staat in de groep Handig');
    await pg.click('#tile-checkin'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'checkin'), 'scherm Check-in');
    // Voorstellen: staan uit, er wordt niets gepland
    const list = await pg.textContent('#ci-list');
    ok(list.includes('Hoe voel je je?') && list.includes('Hoe was je werk?') && (await pg.$$('#ci-list .ci-badge')).length === 2, 'twee voorstellen');
    ok(await pg.evaluate(() => Android._ci.qs.every(q => !q.on) && (Android._ci.saved || 0) === 0), 'voorstellen staan uit (niets gepland)');
    ok((await pg.$$('#ci-list .ci-card[data-q=voel] .ci-opt')).length === 0, 'voorstel: nog geen antwoordknoppen');
    ok((await pg.textContent('#ci-list .ci-card[data-q=voel] small')).includes('Elke dag om 21:00') && (await pg.textContent('#ci-list .ci-card[data-q=werk] small')).includes('Werkdagen om 17:30'), 'voorstellen: 21:00 elke dag, 17:30 op werkdagen');
    // Aanzetten zonder toestemming voor meldingen (Android 13+): eerst vragen
    await pg.evaluate((t) => { Android._ci.notifOk = false; Android._permAsked = []; const q = Android._ci.qs.find(x => x.id === 'voel'); q.times = [t[0]]; q.until = t[1]; enterCheckin(); }, open);
    ok(await pg.isVisible('#ci-list .ci-card[data-q=voel] button[aria-label="Hoe voel je je?: aanzetten"]'), 'knop Aanzetten met de vraag in de naam');
    await pg.click('#ci-list .ci-card[data-q=voel] button[aria-label="Hoe voel je je?: aanzetten"]'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._permAsked.includes('notif') && Android._ci.qs.find(x => x.id === 'voel').on && !Android._ci.qs.find(x => x.id === 'voel').tpl), 'aangezet, en om toestemming voor meldingen gevraagd');
    ok((await pg.textContent('#toast')).includes('Staat aan'), 'melding: staat aan');
    // Antwoorden met één tik
    const btns = await pg.$$('#ci-list .ci-card[data-q=voel] .ci-opt');
    ok(btns.length === 5 && (await btns[3].getAttribute('aria-label')) === 'Hoe voel je je?: 🙂 Goed', '5 smileys, met de vraag in de naam');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#ci-list .ci-opt')].every(e => e.getBoundingClientRect().height >= 44)), 'knoppen minstens 44 px');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok((await pg.textContent('#tile-checkin-sub')) === 'Nog 1 vraag voor vandaag', 'tegel: nog 1 vraag voor vandaag');
    await pg.click('#tile-checkin'); await pg.waitForTimeout(150);
    await pg.click('#ci-list .ci-card[data-q=voel] .ci-opt >> nth=3'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ci.ans.length === 1 && Android._ci.ans[0].v === 4 && Android._ci.ans[0].src === 'app'), 'één tik: bewaard (waarde 4)');
    ok((await pg.textContent('#ci-list .ci-card[data-q=voel] .ci-st')).includes('🙂 Goed') && (await pg.textContent('#toast')).includes('Bewaard'), 'antwoord te zien');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok((await pg.textContent('#tile-checkin-sub')) === '✓ Alles ingevuld', 'tegel: alles ingevuld');
    await pg.click('#tile-checkin'); await pg.waitForTimeout(150);
    // Nog eens antwoorden in dezelfde periode: eerst bevestigen, dan vervangen
    await pg.click('#ci-list .ci-card[data-q=voel] .linkish:has-text("Aanvullen")'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#ci-ans') && (await pg.getAttribute('#ci-ans-opts .ci-opt >> nth=3', 'aria-pressed')) === 'true', 'aanvullen: het antwoordscherm met het gekozen antwoord');
    await pg.click('#ci-ans-done'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current === 'checkin' && Android.checkinAnswer(JSON.stringify({ q: 'voel', p: '', o: 1 })).includes('replace')), 'nog een antwoord: eerst vragen');
    await pg.evaluate(() => ciPick('voel', Android._ci.ans[0].p, 1)); await pg.waitForTimeout(100);
    ok((await pg.textContent('#modal-title')) === 'Antwoord vervangen?' && (await pg.textContent('#modal-text')).includes('🙂 Goed'), 'vervangen? noemt het oude antwoord');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ci.ans.length === 1 && Android._ci.ans[0].v === 2), 'vervangen door 🙁 Slecht');
    // Een nieuwe vraag maken: ja/nee, op gekozen dagen, met labels
    await pg.click('#ci-new'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'checkined') && (await pg.textContent('#ced-h')) === 'Nieuwe vraag', 'editor: nieuwe vraag');
    await pg.click('#ced-save'); await pg.waitForTimeout(80);
    ok((await pg.textContent('#toast')) === 'Vul de vraag in', 'zonder vraag: niet bewaren');
    await pg.fill('#ced-text', 'Heb je bewogen?'); await pg.fill('#ced-emoji', '🏃');
    await pg.click('#ced-scale button[data-v=yesno]'); await pg.waitForTimeout(50);
    ok((await pg.$$('#ced-opts .ci-optrow')).length === 2 && (await pg.textContent('#ced-scale-n')).includes('meteen in de melding'), 'ja/nee: 2 antwoorden, antwoorden in de melding');
    await pg.click('#ced-scale button[data-v=custom]'); await pg.waitForTimeout(50);
    await pg.click('#ced-opt-add'); await pg.waitForTimeout(50);
    ok((await pg.$$('#ced-opts .ci-optrow')).length === 3 && (await pg.textContent('#ced-scale-n')).includes('twee tikken') === false, 'zelf kiezen: antwoord erbij');
    await pg.click('#ced-opts button[aria-label="Antwoord 3 weghalen"]'); await pg.waitForTimeout(50);
    await pg.fill('#ced-opts .ci-optrow >> nth=0 >> .ol', 'Nee'); await pg.fill('#ced-opts .ci-optrow >> nth=0 >> .oe', '🛋️'); await pg.fill('#ced-opts .ci-optrow >> nth=0 >> .ov', '0');
    await pg.fill('#ced-opts .ci-optrow >> nth=1 >> .ol', 'Ja'); await pg.fill('#ced-opts .ci-optrow >> nth=1 >> .oe', '🏃'); await pg.fill('#ced-opts .ci-optrow >> nth=1 >> .ov', '0');
    await pg.click('#ced-rhythm button[data-v=weekdays]'); await pg.waitForTimeout(50);
    ok(await pg.isVisible('#ced-days') && (await pg.$$('#ced-days button[aria-pressed=true]')).length === 5, 'gekozen dagen: dagen kiezen (standaard werkdagen)');
    await pg.click('#ced-days button[aria-label=maandag]'); await pg.click('#ced-days button[aria-label=zaterdag]');
    await pg.fill('#ced-t0', '19:15'); await pg.click('#ced-wk');
    await pg.click('#ced-lab'); await pg.waitForTimeout(50);
    await pg.fill('#ced-label-in', 'fietsen'); await pg.click('#ced-labels-w button:has-text("Toevoegen")');
    await pg.fill('#ced-label-in', 'wandelen'); await pg.press('#ced-label-in', 'Enter'); await pg.click('#ced-labels-w button:has-text("Toevoegen")');
    await pg.click('#ced-color button[aria-label=Oranje]');
    await pg.click('#ced-save'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#toast')) === 'Geef elk antwoord een eigen waarde', 'twee keer dezelfde waarde: niet bewaren');
    await pg.fill('#ced-opts .ci-optrow >> nth=1 >> .ov', '1');
    await pg.screenshot({ path: __dirname + '/shots/checkin-editor-' + scheme + '.png', fullPage: true });
    await pg.click('#ced-save'); await pg.waitForTimeout(150);
    const nq = await pg.evaluate(() => Android._ci.qs.find(q => q.text === 'Heb je bewogen?'));
    ok(nq && nq.on && nq.scale === 'custom' && nq.options.map(o => o.label).join() === 'Nee,Ja' && nq.days.join() === '2,3,4,5,6' && nq.times[0] === '19:15' && nq.quietWeekend && nq.labelList.join() === 'fietsen,wandelen' && nq.color === 'orange',
      'bewaard: antwoorden, dagen, tijd, stil in het weekend, labels en kleur');
    ok(await pg.evaluate(() => current === 'checkin') && (await pg.textContent('#ci-list')).includes('Op di, wo, do, vr, za om 19:15 · stil in het weekend'), 'terug in de lijst, met het ritme in woorden');
    // Bewerken: wijzigen en wegtikken vraagt of het bewaard moet worden
    await pg.evaluate(id => ciEdit(id), nq.id); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ced-h')) === 'Vraag bewerken' && (await pg.inputValue('#ced-text')) === 'Heb je bewogen?', 'bewerken: velden ingevuld');
    await pg.click('#ced-rhythm button[data-v=multi]'); await pg.waitForTimeout(50);
    ok((await pg.$$('#ced-times input[type=time]')).length === 2, 'meerdere keren per dag: twee tijden');
    await pg.click('#ced-time-add'); await pg.waitForTimeout(50);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok((await pg.textContent('#modal-title')) === 'Wijzigingen bewaren?', 'terug met wijzigingen: vragen');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(id => { const q = Android._ci.qs.find(x => x.id === id); return q.rhythm === 'multi' && q.times.length === 3; }, nq.id) && await pg.evaluate(() => current === 'checkin'), 'bewaard: drie keer per dag');
    // Ordenen, dupliceren, archiveren, verwijderen
    await pg.click('#ci-list .ci-card[data-q="' + nq.id + '"] button[aria-label="Heb je bewogen?: meer"]'); await pg.waitForTimeout(80);
    await pg.click('#sheet-acts button:has-text("Omhoog")'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(id => Android._ci.qs[1].id === id, nq.id), 'omhoog');
    await pg.click('#ci-list .ci-card[data-q="' + nq.id + '"] button[aria-label="Heb je bewogen?: meer"]'); await pg.click('#sheet-acts button:has-text("Dupliceren")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ci.qs.some(q => q.text === 'Heb je bewogen? (kopie)' && !q.on)), 'dupliceren: kopie staat uit');
    await pg.click('#ci-list .ci-card[data-q=werk] button[aria-label="Hoe was je werk?: meer"]'); await pg.click('#sheet-acts button:has-text("Verwijderen")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#modal-text')).includes('Archiveren') && (await pg.textContent('#modal-cancel')) === 'Archiveren', 'verwijderen: zegt dat de geschiedenis meegaat, met archiveren als andere keus');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ci.qs.find(q => q.id === 'werk').archived) && (await pg.textContent('#ci-arch')).includes('Gearchiveerd (1)'), 'gearchiveerd (geschiedenis blijft)');
    await pg.click('#ci-arch .ci-archh'); await pg.waitForTimeout(80);
    ok(await pg.isVisible('#ci-arch .ci-card[data-q=werk]') && (await pg.textContent('#ci-arch')).includes('Terugzetten'), 'gearchiveerd: terug te kijken en terug te zetten');
    const dup = await pg.evaluate(() => Android._ci.qs.find(q => q.text.endsWith('(kopie)')).id);
    await pg.evaluate(id => ciDelete(id), dup); await pg.waitForTimeout(80); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !Android._ci.qs.some(q => q.text.endsWith('(kopie)'))), 'verwijderd');
    // 320 en 390 px: geen horizontale scroll
    for (const w of [320, 390]) {
      await pg.setViewportSize({ width: w, height: 844 });
      for (const s of ['checkin', 'checkined', 'checkinq', 'checkincmp']) {
        await pg.evaluate(([s, id]) => { if (s === 'checkined') cedOpen(ciQ(id)); else if (s === 'checkinq') ciOpen(id); else if (s === 'checkincmp') ciCompare(id); else show(s); }, [s, nq.id]); await pg.waitForTimeout(80);
        ok(!(await pg.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)), w + ' px: ' + s + ' past');
        if (s === 'checkined') await pg.evaluate(() => { cedQ = null; });
      }
    }
    await pg.evaluate(() => show('checkin')); await pg.waitForTimeout(80);
    await pg.screenshot({ path: __dirname + '/shots/checkin-' + scheme + '.png', fullPage: true });
    // Zoeken: het scherm, en je notities bij de antwoorden
    await pg.evaluate(() => { const a = Android._ci.ans[0]; a.note = 'Lange vergadering gehad'; a.labels = ['druk']; show('search'); });
    await pg.fill('#gs-q', 'vergadering'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(700);
    ok(await pg.isVisible('#gs-res .gi[data-k=checkin]') && (await pg.textContent('#gs-res')).includes('Lange vergadering'), 'Zoeken: notitie bij een antwoord');
    await pg.waitForTimeout(500); await pg.evaluate(() => document.querySelector('#gs-res .gi[data-k=checkin]').scrollIntoView({ block: 'center' })); // eerst klaar met zoeken
    await pg.click('#gs-res .gi[data-k=checkin]'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'checkinq' && ciqId === 'voel' && !!ciqSelKey) && (await pg.textContent('#ciq-day')).includes('Lange vergadering'), 'treffer opent de vraag bij die dag');
    await pg.evaluate(() => { show('search'); }); await pg.fill('#gs-q', 'stemming'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(400);
    ok((await pg.textContent('#gs-res')).includes('Check-in'), 'Zoeken vindt het scherm (ook op "stemming")');
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
})();
