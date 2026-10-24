// Assistent: acties met bevestiging (herinnering, notitie, agenda). Niets gebeurt zonder een tik. AI nagebootst (testbrug).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    // Morgen 09:00 (zoals het model het opgeeft)
    const ymd = await pg.evaluate(() => { const d = new Date(Date.now() + 864e5); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); });
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; show('ai'); }); await pg.waitForTimeout(200);
    const ask = async q => { await pg.fill('#ai-in', q); await pg.click('#ai-send'); };

    // 1. Herinnering: voorstel onder het antwoord, de regel zelf nooit zichtbaar
    await pg.evaluate(y => { Android._ai.reply = 'Goed idee, dat zet ik voor je klaar.\n[[herinnering: ' + y + ' 09:00 | Jos bellen]]'; }, ymd);
    const nNotes = await pg.evaluate(() => notesGet().length);
    await ask('Herinner me morgen om 9 uur eraan Jos te bellen');
    let sawLine = false;
    for (let k = 0; k < 20; k++) { await pg.waitForTimeout(60); if ((await pg.textContent('#ai-msgs')).includes('[[')) sawLine = true; }
    await pg.waitForTimeout(500);
    ok(!sawLine && !(await pg.textContent('#ai-msgs')).includes('[['), 'de actieregel is nooit te zien (ook niet tijdens het schrijven)');
    ok(await pg.evaluate(() => Android._ai.last.messages.slice(-1)[0].content === 'Herinner me morgen om 9 uur eraan Jos te bellen'), 'de vraag zelf gaat ongewijzigd mee (de app voegt de opdracht toe)');
    ok(await pg.isVisible('.aiact') && (await pg.textContent('.aiact')).includes('Herinnering zetten?') && (await pg.textContent('.aiact')).includes('Jos bellen'), 'voorstel: herinnering zetten');
    ok((await pg.textContent('.aiact .aiactw')).includes('09:00'), 'met het moment (morgen 09:00)');
    ok(await pg.evaluate(n => notesGet().length === n && Android._rems.length === 0, nNotes), 'nog niets gedaan zonder tik');
    ok(await pg.evaluate(() => Android._ai.chats[0].msgs.slice(-1)[0].act.st === 'voorgesteld' && !Android._ai.chats[0].msgs.slice(-1)[0].c.includes('[[')), 'in het gesprek bewaard: voorgesteld, zonder de regel');
    await pg.screenshot({ path: __dirname + '/shots/ai-acties-' + scheme + '.png' });
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(150);
    const made = await pg.evaluate(() => { const n = notesGet().find(x => x.title === 'Jos bellen'); const r = n && Android._rems.find(x => x.note === n.id); return n && r ? new Date(r.t).getHours() + ':' + new Date(r.t).getMinutes() : ''; });
    ok(made === '9:0', 'Zetten: notitie met herinnering om 9:00 (' + made + ')');
    ok((await pg.textContent('.aiact.done')).includes('Herinnering gezet') && await pg.isVisible('.aiact.done .linkish'), 'gedaan, met Openen');
    ok(await pg.evaluate(() => Android._ai.chats[0].msgs.slice(-1)[0].act.st === 'gedaan' && !!Android._ai.chats[0].msgs.slice(-1)[0].act.note), 'gedaan staat in het bewaarde gesprek');
    await pg.click('.aiact.done .linkish'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'note' && $('#note-title').value === 'Jos bellen'), 'Openen: de notitie');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'ai') && await pg.isVisible('.aiact.done'), 'terug: weer in het gesprek');
    // Dubbel tikken maakt geen twee notities: het voorstel is weg na de eerste
    ok(await pg.evaluate(n => notesGet().filter(x => x.title === 'Jos bellen').length === 1, nNotes), 'één notitie');

    // 2. Geen moment bekend: zelf kiezen (tijdkiezer), voorbij weigeren
    await pg.evaluate(() => { aiNew(); Android._ai.reply = 'Wanneer wil je dat?\n[[herinnering:  | Pakket ophalen]]'; });
    await ask('Herinner me aan het pakket'); await pg.waitForTimeout(900);
    ok(await pg.isVisible('.aiact input[type=datetime-local]') && (await pg.textContent('.aiact')).includes('Kies wanneer'), 'zonder moment: tijdkiezer');
    await pg.evaluate(() => { const i = $('.aiact input'); i.value = '2020-01-01T09:00'; i.dispatchEvent(new Event('input')); });
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#toast')).includes('toekomst') && await pg.isVisible('.aiact .mini.on'), 'een moment in het verleden: niet gezet');
    const fut = await pg.evaluate(() => localIso(Date.now() + 2 * 864e5));
    await pg.evaluate(v => { const i = $('.aiact input'); i.value = v; i.dispatchEvent(new Event('input')); }, fut);
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => { const n = notesGet().find(x => x.title === 'Pakket ophalen'); return !!n && Android._rems.some(r => r.note === n.id && r.t > Date.now() + 864e5); }), 'gekozen moment: herinnering gezet');

    // 2b. De herinnering zetten mislukt: eerlijk melden, opnieuw kan (zonder tweede notitie)
    await pg.evaluate(y => { aiNew(); Android._remFail = true; Android._ai.reply = 'Goed.\n[[herinnering: ' + y + ' 08:00 | Vuilnis buiten]]'; }, ymd);
    await ask('Herinner me morgen om 8 uur aan de vuilnis'); await pg.waitForTimeout(800);
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#toast')).includes('herinnering niet gezet') && await pg.isVisible('.aiact .mini.on') && !(await pg.$('.aiact.done')), 'mislukt: "Notitie gemaakt; herinnering niet gezet", voorstel blijft');
    ok(await pg.evaluate(() => document.activeElement && document.activeElement.closest('.aiact') !== null), 'focus blijft in het voorstel');
    await pg.evaluate(() => { Android._remFail = false; }); await pg.waitForTimeout(1100);
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('.aiact.done') && await pg.evaluate(() => notesGet().filter(n => n.title === 'Vuilnis buiten').length === 1 && Android._rems.some(r => r.note === notesGet().find(n => n.title === 'Vuilnis buiten').id)), 'opnieuw: herinnering gezet, één notitie');
    // 2c. Een oud voorstel (moment voorbij) in een heropend gesprek: opnieuw kiezen
    await pg.evaluate(() => { const c = { id: 'oud1', title: 'Oud', msgs: [{ r: 'user', c: 'Herinner me', t: 1 }, { r: 'ai', c: 'Goed.', t: 2, act: { type: 'herinnering', title: 'Oud ding', at: Date.now() - 864e5, day: Date.now() - 2 * 864e5, need: '', st: 'voorgesteld' } }, { r: 'user', c: 'Agenda', t: 3 }, { r: 'ai', c: 'Goed.', t: 4, act: { type: 'agenda', title: 'Oude dag', day: new Date(new Date().setHours(0, 0, 0, 0) - 2 * 864e5).getTime(), allDay: true, need: '', st: 'voorgesteld' } }] };
      Android._ai.chats.unshift(c); show('aichats'); aicOpen(0); }); await pg.waitForTimeout(200);
    ok(await pg.$$eval('.aiact input[type=datetime-local]', x => x.length) === 2, 'heropend: voorbije voorstellen vragen een nieuw moment (ook een hele dag)');

    // 3. Nee: niets, en terug te halen
    await pg.evaluate(() => { aiNew(); Android._ai.reply = 'Prima.\n[[notitie: Boodschappen | melk, brood en kaas]]'; });
    await ask('Maak een notitie van de boodschappen: melk, brood en kaas'); await pg.waitForTimeout(700);
    ok((await pg.textContent('.aiact')).includes('Notitie maken?') && (await pg.textContent('.aiact')).includes('melk, brood en kaas'), 'voorstel: notitie maken');
    const before = await pg.evaluate(() => notesGet().length);
    await pg.click('.aiact .aiactb .mini:not(.on)'); await pg.waitForTimeout(100);
    ok(await pg.isVisible('.aiact.no') && await pg.evaluate(n => notesGet().length === n, before), 'Nee: niets gemaakt');
    await pg.click('.aiact.no .linkish'); await pg.waitForTimeout(100);
    // Tekst aanpassen
    await pg.click('.aiact .aiactb .linkish'); await pg.waitForTimeout(100);
    await pg.fill('#modal-input', 'Boodschappen zaterdag'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => { const n = notesGet().find(x => x.title === 'Boodschappen zaterdag'); return !!n && n.text === 'melk, brood en kaas'; }), 'Toch voorstellen, tekst aangepast, notitie gemaakt');

    // 4. Agenda: de agenda-app opent met alles ingevuld
    await pg.evaluate(y => { aiNew(); Android._ai.reply = 'Zal ik dat in je agenda zetten?\n[[agenda: ' + y + ' 10:00 | Tandarts]]'; }, ymd);
    await ask('Zet de tandarts morgen om 10 uur in mijn agenda'); await pg.waitForTimeout(700);
    ok((await pg.textContent('.aiact')).includes('In je agenda zetten?'), 'voorstel: agenda');
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._cal && Android._cal.title === 'Tandarts' && new Date(Android._cal.begin).getHours() === 10 && Android._cal.end - Android._cal.begin === 36e5 && !Android._cal.allDay), 'agenda-app met titel, begin en eind');
    ok((await pg.textContent('.aiact.done')).includes('agenda-app'), 'gedaan: bewaar het in je agenda-app');
    await pg.evaluate(y => { aiNew(); Android._ai.reply = 'Komt goed.\n[[agenda: ' + y + ' | Verjaardag Daan]]'; Android._cal = null; }, ymd);
    await ask('Zet de verjaardag van Daan morgen in mijn agenda'); await pg.waitForTimeout(700);
    ok((await pg.textContent('.aiact')).includes('hele dag'), 'zonder tijd: hele dag');
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._cal && Android._cal.allDay === true && new Date(Android._cal.begin).getHours() === 0), 'hele dag in de agenda');
    await pg.evaluate(() => { aiNew(); Android._calErr = 'Er is geen agenda-app op je telefoon'; });
    await ask('Zet de verjaardag van Daan morgen in mijn agenda'); await pg.waitForTimeout(700);
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#toast')).includes('geen agenda-app') && await pg.isVisible('.aiact .mini.on'), 'geen agenda-app: melding, voorstel blijft');
    await pg.evaluate(() => { Android._calErr = ''; });

    // 5. Niet gevraagd: geen voorstel (wel de regel weg); "onthoud dat" blijft het geheugen
    await pg.evaluate(y => { aiNew(); Android._ai.reply = 'Vier.\n[[herinnering: ' + y + ' 09:00 | iets]]'; }, ymd);
    await ask('Wat is 2 + 2?'); await pg.waitForTimeout(700);
    ok(!(await pg.$('.aiact')), 'niet gevraagd: geen voorstel (en de tekst blijft zoals hij is)');
    await pg.evaluate(() => { aiNew(); Android._ai.reply = 'Dat onthoud ik.'; });
    await ask('Onthoud dat ik op dinsdag sport'); await pg.waitForTimeout(700);
    ok(!(await pg.$('.aiact')) && (await pg.textContent('.aimem')).includes('Onthouden'), '"onthoud dat …": geheugen, geen actie');

    // 6. Een eerder gesprek openen: het voorstel en wat er gebeurde blijven
    await pg.evaluate(() => show('aichats')); await pg.waitForTimeout(150);
    const idx = await pg.evaluate(() => aicList.findIndex(c => /tandarts/i.test(c.title)));
    await pg.evaluate(i => aicOpen(i), idx); await pg.waitForTimeout(200);
    ok(await pg.isVisible('.aiact.done') && (await pg.textContent('.aiact.done')).includes('Tandarts'), 'eerder gesprek: gedaan blijft zichtbaar');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
