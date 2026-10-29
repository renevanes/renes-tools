// Check-in vanuit de melding (nagebootst via de testbrug): met 3 antwoorden één tik in de melding zelf, met 5 opent
// de melding het antwoordscherm (twee tikken); daar labels, een notitie en inspreken. Eén melding per moment.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => {
      const p = n => String(n).padStart(2, '0'), a = new Date(Date.now() - 36e5), u = new Date(Date.now() + 2 * 36e5), t = [p(a.getHours()) + ':' + p(a.getMinutes()), p(u.getHours()) + ':' + p(u.getMinutes())];
      Android.checkinState(); // voorstellen
      Android._ci.qs.forEach(q => Object.assign(q, { on: true, tpl: false, rhythm: 'daily', times: [t[0]], until: t[1] }));
      show('checkin');
    });
    // 3 antwoorden: knoppen in de melding
    const btns = await pg.evaluate(() => Android._ciNotify('werk'));
    ok(JSON.stringify(btns) === JSON.stringify(['😕 Slecht', '😐 Gaat wel', '🙂 Goed']), 'melding met 3 knoppen: ' + JSON.stringify(btns));
    ok((await pg.evaluate(() => Android._ciNotifAnswer('werk', 2))) === '✓ Bewaard', 'één tik in de melding: bewaard (bevestiging in de melding)');
    await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => { const a = Android._ci.ans.find(x => x.q === 'werk'); return a && a.v === 3 && a.src === 'notif' && !Android._ci.posted.werk; }), 'antwoord uit de melding: waarde 3, melding weg');
    ok((await pg.textContent('#ci-list .ci-card[data-q=werk] .ci-st')).includes('🙂 Goed'), 'de app ververst vanzelf');
    ok((await pg.evaluate(() => Android._ciNotify('werk'))) === null, 'al ingevuld: geen tweede melding');
    // 5 antwoorden: de melding opent het antwoordscherm
    const none = await pg.evaluate(() => Android._ciNotify('voel'));
    ok(Array.isArray(none) && none.length === 0, 'melding met 5 antwoorden: geen knoppen (Android laat er hooguit 3 toe)');
    const key = await pg.evaluate(() => Android._ci.posted.voel.key);
    await pg.evaluate(k => { show('home'); openTool('checkin:voel:' + k); }, key); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'checkin') && await pg.isVisible('#ci-ans') && (await pg.textContent('#ci-ans-t')) === '🙂 Hoe voel je je?', 'tik op de melding: het antwoordscherm');
    const big = await pg.$$eval('#ci-ans-opts .ci-opt', x => x.map(e => [e.getAttribute('aria-label'), Math.round(e.getBoundingClientRect().height)]));
    ok(big.length === 5 && big.every(([, h]) => h >= 56) && big[2][0] === 'Hoe voel je je?: 😐 Gaat wel', 'grote knoppen, met de vraag in de naam');
    ok((await pg.textContent('#ci-ans-p')).startsWith('Vandaag'), 'periode: vandaag');
    await pg.screenshot({ path: __dirname + '/shots/checkin-antwoord-' + scheme + '.png' });
    await pg.click('#ci-ans-opts .ci-opt >> nth=2'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => { const a = Android._ci.ans.find(x => x.q === 'voel'); return a && a.v === 3; }) && (await pg.textContent('#ci-ans-st')).includes('Bewaard'), 'tweede tik: bewaard; scherm blijft voor aanvullen');
    // Labels en notitie
    await pg.click('#ci-ans-x button[data-l="moe"]'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._ci.ans.find(x => x.q === 'voel').labels.join() === 'moe') && (await pg.getAttribute('#ci-ans-x button[data-l="moe"]', 'aria-pressed')) === 'true', 'label moe aangetikt');
    await pg.fill('#ci-ans-note', 'Kort nachtje'); await pg.click('#ci-ans-done'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ci.ans.find(x => x.q === 'voel').note === 'Kort nachtje') && !(await pg.isVisible('#ci-ans')), 'notitie bewaard bij Klaar');
    // Inspreken (uitschrijven op de telefoon, nagebootst)
    await pg.click('#ci-list .ci-card[data-q=voel] .linkish:has-text("Aanvullen")'); await pg.waitForTimeout(150);
    await pg.evaluate(() => { Android._voiceResult = { text: 'Lekker gewandeld' }; });
    await pg.click('#ci-ans-x button[aria-label="Notitie inspreken bij Hoe voel je je?"]'); await pg.waitForTimeout(100);
    ok(await pg.isVisible('#voice') && (await pg.textContent('#voice-t')) === 'Notitie inspreken', 'inspreken: het spraakvenster');
    await pg.click('#voice-btn'); await pg.waitForTimeout(100); await pg.click('#voice-btn'); await pg.waitForTimeout(250);
    ok(!(await pg.isVisible('#voice')) && (await pg.inputValue('#ci-ans-note')) === 'Kort nachtje Lekker gewandeld', 'uitgeschreven tekst komt in de notitie');
    ok(await pg.evaluate(() => { const a = Android._ci.ans.find(x => x.q === 'voel'); return a.note === 'Kort nachtje Lekker gewandeld' && a.voice === true; }), 'ingesproken notitie bewaard');
    // Zonder spraakmodel: naar Modellen
    await pg.evaluate(() => { Android._voiceErr = 'model'; });
    await pg.click('#ci-ans-x button[aria-label="Notitie inspreken bij Hoe voel je je?"]'); await pg.waitForTimeout(100);
    await pg.click('#voice-btn'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#voice-state')).includes('Modellen'), 'geen spraakmodel: verwijst naar Modellen');
    await pg.click('#voice-state a'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'models') && !(await pg.isVisible('#ci-ans')) && !(await pg.isVisible('#voice')), 'naar Modellen');
    await pg.evaluate(() => { Android._voiceErr = ''; goBack(); show('checkin'); }); await pg.waitForTimeout(100);
    // Zonder extra's: één tik in het scherm en klaar (melding + één tik = twee tikken)
    await pg.evaluate(() => { const q = Android._ci.qs.find(x => x.id === 'werk'); Object.assign(q, { note: false, voice: false, labels: false });
      Android._ci.ans = Android._ci.ans.filter(a => a.q !== 'werk'); Android._ciNotify('werk'); openTool('checkin:werk:' + Android._ci.posted.werk.key); }); await pg.waitForTimeout(250);
    await pg.click('#ci-ans-opts .ci-opt >> nth=0'); await pg.waitForTimeout(150);
    ok(!(await pg.isVisible('#ci-ans')) && (await pg.textContent('#toast')).includes('😕 Slecht') && await pg.evaluate(() => !Android._ci.posted.werk), 'zonder extra\'s: na één tik dicht, melding weg');
    // Al ingevuld en nog eens kiezen: eerst bevestigen
    await pg.evaluate(() => openTool('checkin:werk:' + Android._ci.ans.find(a => a.q === 'werk').p)); await pg.waitForTimeout(250);
    await pg.click('#ci-ans-opts .ci-opt >> nth=1'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal-title')) === 'Antwoord vervangen?', 'nog eens: vervangen?');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(100); await pg.evaluate(() => ciAnsClose());
    ok(await pg.evaluate(() => Android._ci.ans.filter(a => a.q === 'werk').length === 1 && Android._ci.ans.find(a => a.q === 'werk').v === 1), 'niet vervangen: blijft 😕');
    // Terug sluit het antwoordscherm
    await pg.evaluate(() => ciAsk('voel', '', {})); await pg.waitForTimeout(80);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(80);
    ok(!(await pg.isVisible('#ci-ans')) && await pg.evaluate(() => current === 'checkin'), 'terug: antwoordscherm dicht');
    // Melding proberen (en zonder toestemming eerst vragen)
    await pg.evaluate(() => { Android._ci.notifOk = false; Android._permAsked = []; ciPreview('voel'); });
    ok(await pg.evaluate(() => Android._permAsked.includes('notif') && !Android._ci.previews.length), 'melding proberen zonder toestemming: vraagt erom');
    await pg.click('#ci-list .ci-card[data-q=voel] button[aria-label="Hoe voel je je?: meer"]'); await pg.click('#sheet-acts button:has-text("Melding nu proberen")'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._ci.previews.includes('voel')) && (await pg.textContent('#toast')).includes('meldingen'), 'melding nu proberen');
    // Uitgezet: geen meldingen meer
    await pg.evaluate(() => { const q = Android._ci.qs.find(x => x.id === 'voel'); q.on = false; Android._ci.ans = Android._ci.ans.filter(a => a.q !== 'voel'); });
    ok((await pg.evaluate(() => Android._ciNotify('voel'))) === null, 'uit: geen melding');
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
})();
