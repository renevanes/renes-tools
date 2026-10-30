// De assistent en de instellingen van de app: opdracht → kaart → Toepassen (via dezelfde functie als het scherm) →
// Ongedaan maken; Annuleren verandert niets; voorstel van het taalmodel streng gecontroleerd; gevoelig = alleen Openen;
// "waar vind ik …" opent het juiste scherm; "wat heb je veranderd"; zonder taalmodel; in een losse app niet.
// Het taalmodel is nagebootst (testbrug).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const ask = async (q, ms) => { await pg.fill('#ai-in', q); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(ms || 200); };
    const last = '#ai-msgs .aimsg:last-child';

    // ---- Zonder taalmodel: opdrachten en opzoeken werken al ----
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#ai-setup') && await pg.isVisible('#ai-in') && (await pg.getAttribute('#ai-in', 'placeholder')).includes('instellingen'), 'zonder taalmodel: vragen over de instellingen kan');
    await ask('Zet de nachtelijke backup aan');
    ok(await pg.evaluate(() => Android._ai.asked === undefined), 'opdracht: zonder taalmodel beantwoord');
    ok(await pg.isVisible(last + ' .setcard') && (await pg.textContent(last + ' .setcard')).includes('Elke nacht alles back-uppen') && (await pg.textContent(last + ' .setft')).includes('naar aan'), 'kaart: naam, scherm, van … naar …');
    ok(await pg.evaluate(() => Android._bk.auto === null), 'nog niets veranderd voor Toepassen');
    await pg.screenshot({ path: __dirname + '/shots/ai-app-instellingen-' + scheme + '.png' });
    await pg.click(last + ' .setcard .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._bk.auto === true && Android._bk.charging === true), 'Toepassen: via backupSetAuto (zelfde functie als het scherm)');
    ok((await pg.textContent(last + ' .aiact.done')).includes('aan') && (await pg.textContent('#toast')).includes('Aangepast'), 'bevestiging met Ongedaan maken');
    await pg.click(last + ' .aiact.done button:has-text("Ongedaan maken")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._bk.auto === false) && (await pg.textContent(last + ' .aiact.done')).includes('Teruggezet'), 'Ongedaan maken: weer uit');
    // Een instelling uit de interface zelf
    await ask('Zet de tekstgrootte op groot');
    await pg.click(last + ' .setcard .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._zoom === 115 && $('#st-zoom').value === '115'), 'tekstgrootte via stZoom: groot');
    // Slaappraat: onopvallend luisteren. Uitzetten (melding op het vergrendelscherm) doet Rene zelf; aanzetten mag (via slSwitch)
    await ask('Zet onopvallend luisteren uit');
    ok(!(await pg.$(last + ' .setcard')) && (await pg.textContent(last + ' .aib')).includes('pas ik niet voor je aan') && await pg.evaluate(() => Android._sl.set.quiet === true), 'onopvallend luisteren uit: alleen uitleg en Openen');
    await pg.evaluate(() => { Android._sl.set.quiet = false; });
    await ask('Zet onopvallend luisteren aan');
    await pg.click(last + ' .setcard .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._sl.set.quiet === true), 'onopvallend luisteren aan via slSwitch');
    // Annuleren
    await ask('Zet meldingen uit');
    ok((await pg.textContent(last + ' .setft')).includes('naar uit') && !!(await pg.$(last + ' .aiact.setcard .linkish:has-text("Openen")')), 'meldingen uit: kaart met Openen');
    await pg.click(last + ' .setcard button:has-text("Annuleren")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !Android._notifMuted) && (await pg.textContent(last + ' .aiact.no')).includes('niet veranderd'), 'Annuleren: niets veranderd');
    // Gevoelig: alleen uitleg en Openen
    await ask('Schakel het app-slot uit');
    ok(!(await pg.$(last + ' .setcard')) && (await pg.textContent(last + ' .aib')).includes('pas ik niet voor je aan'), 'app-slot: geen Toepassen, uitleg');
    await pg.click(last + ' .setopen .aichip'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'setlock' && !!Android._st && !Android._st.lockChanged), 'app-slot: Openen gaat naar App-slot');
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(150);
    await ask('Zet de privé gesprekken van de assistent mee in de backup');
    ok(!(await pg.$(last + ' .setcard')) && await pg.evaluate(() => Android._bk.parts.ai !== true), 'privé mee in de backup: niet via de assistent');
    // Waar vind ik …: Openen naar het juiste scherm, met de instelling aangewezen
    await ask('Waar vind ik de tekstgrootte?');
    ok((await pg.textContent(last + ' .aib')).includes('Instellingen → Weergave') && (await pg.textContent(last + ' .aib')).includes('Groot'), 'waar vind ik: plek en stand');
    await pg.click(last + ' .setopen .aichip'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'setlook' && !!document.querySelector('#s-setlook .setflash')), 'Openen: Weergave, tekstgrootte aangewezen');
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(150);
    // Wat kan ik instellen bij …
    await ask('Wat kan ik instellen bij Slaappraat?');
    ok((await pg.textContent(last + ' .aib')).includes('Slaappraat: gevoeligheid') && (await pg.$$(last + ' .aib li')).length >= 6, 'wat kan ik instellen bij Slaappraat: lijst met standen');
    // Twijfel: kiezen
    await ask('Zet wifi uit');
    ok((await pg.textContent(last + ' .aib')).length > 0, 'vage opdracht: een antwoord');
    // Wat heb je veranderd
    await ask('Wat heb je allemaal veranderd?');
    ok((await pg.$$(last + ' .setlogrow')).length === 3 && (await pg.textContent(last + ' .setlog')).includes('Tekstgrootte'), 'wat heb je veranderd: het logboek');
    await pg.click(last + ' .setlogrow:has-text("Tekstgrootte") button'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._zoom === 0), 'logboek: Ongedaan maken zet de tekstgrootte terug');
    // Opdrachten die geen instelling van de app zijn: geen kaart voor iets anders
    for (const q of ['zet de radio uit', 'zet de radio aan', 'zet de muziek uit', 'zet het licht uit', 'zet de backup op 3 uur']) {
      await ask(q);
      ok(!(await pg.$(last + ' .setcard')) && !/buffer|terugspoel|AudD|Donker|Slaappraat/i.test(await pg.textContent(last)), '"' + q + '": geen kaart voor een andere instelling');
    }
    // De radiowekker
    await ask('zet de wekker aan');
    ok((await pg.textContent(last + ' .setcard')).includes('Radiowekker') && (await pg.textContent(last + ' .setcard .setinfo')).includes('zender'), 'zet de wekker aan: kaart, met welke zender');
    await ask('zet de wekker op 6 uur');
    ok((await pg.textContent(last + ' .setcard')).includes('Tijd van de radiowekker') && (await pg.textContent(last + ' .setft')).includes('06:00'), 'zet de wekker op 6 uur: kaart voor de tijd');
    await ask('stel de wekker in op half 8');
    ok((await pg.textContent(last + ' .setft')).includes('07:30'), 'stel de wekker in op half 8: 07:30');
    // Wat de assistent niet zelf mag aanzetten: privé-gegevens, ritten met locatie, vanzelf leren
    for (const [q, id] of [['zet de meldingsgeschiedenis mee in de backup aan', 'backup.deel.notifications'], ['zet ritten vanzelf opnemen aan', 'auto.ritten']]) {
      await ask(q);
      ok(!(await pg.$(last + ' .setcard')) && (await pg.textContent(last + ' .aib')).includes('pas ik niet voor je aan'), q + ': alleen uitleg');
    }
    // Tekst-opdrachten zijn geen instelling (geen kaart, zonder taalmodel: een model nodig)
    for (const q of ['Zet deze tekst om naar het Engels: goedemorgen', 'zet de tekst om in hoofdletters: hallo', 'Zet dit op een rijtje: melk, eieren, brood']) {
      await ask(q);
      ok(!(await pg.$(last + ' .aiact')) && (await pg.textContent(last + ' .aib')).includes('taalmodel nodig'), '"' + q + '": geen instelling');
    }
    // Niet alles is een instelling
    await ask('Leg dit uit');
    ok((await pg.textContent(last + ' .aib')).includes('taalmodel nodig'), 'gewone vraag zonder taalmodel: uitleg dat er een model nodig is');

    // ---- Met taalmodel ----
    await pg.evaluate(() => { Android._ai.st.klein35 = { state: 'ready' }; Android._ai.chat = 'klein35'; aiLoad(); aiNew(); }); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#ai-chat') && !(await pg.isVisible('#ai-setup')), 'model klaar');
    // Uitleg met de instellingen erbij; het model stelt een wijziging voor
    await pg.evaluate(() => { Android._ai.reply = 'Kortere antwoorden stel je in bij Lengte. Ik zet hem voor je op Kort.\n[[instelling: ai.style = kort]]'; });
    await ask('Hoe zet ik de antwoorden korter? Welke instelling is dat?', 1400);
    ok(await pg.evaluate(() => Array.isArray(Android._ai.last.settings) && Android._ai.last.settings.some(s => s.id === 'ai.style' && s.cur === 'normaal' && s.vals.length === 3)), 'het model krijgt de instellingen met de stand van nu');
    ok(!(await pg.textContent(last + ' .aib')).includes('[[') && await pg.isVisible(last + ' .setcard'), 'regel uit het antwoord, kaart eronder');
    await pg.click(last + ' .setcard .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ai.cfg.style === 'kort'), 'Toepassen: via aicSet');
    // Ongeldige id of waarde, of gevoelig: geen kaart
    await pg.evaluate(() => { Android._ai.reply = 'Dat doe ik.\n[[instelling: ai.style = superkort]]\n[[instelling: bestaat.niet = aan]]'; });
    await ask('Hoe maak ik de antwoorden uitgebreider? Welke instelling?', 1300);
    ok(!(await pg.$(last + ' .setcard')), 'onbekende waarde of id: geen kaart');
    const js = await pg.evaluate(() => {
      const m = { set: { offer: ['slot.aan', 'ai.style', 'backup.prive.ai'] } };
      setFromModel(m, [{ id: 'slot.aan', value: 'uit' }, { id: 'backup.prive.ai', value: 'aan' }, { id: 'backup.nacht', value: 'aan' }, { id: 'ai.style', value: 'heel kort' }]);
      const n = { set: { offer: ['ai.style'] } }; setFromModel(n, [{ id: 'ai.style', value: 'Uitgebreid' }]);
      return [m.set.items, n.set.items];
    });
    ok(!js[0] && js[1] && js[1].length === 1 && js[1][0].to === 'uitgebreid', 'controle in de app: gevoelig, niet meegegeven of onbekend = niets; een naam wordt de waarde');
    // Onthoud … is het geheugen, geen instelling
    await pg.evaluate(() => { Android._ai.reply = 'Oké.'; });
    await ask('Onthoud dat ik de tekstgrootte graag groot heb', 1300);
    ok(await pg.evaluate(() => !Android._ai.last.settings && Android._ai.mem.some(m => /tekstgrootte/.test(m.text))) && !(await pg.$(last + ' .setcard')), 'onthoud …: het geheugen, geen instelling');
    // Geen instelling, wel een opdracht: het model krijgt mee dat het niets mag beweren gedaan te hebben
    await ask('zet het licht uit', 1300);
    ok(await pg.evaluate(() => Android._ai.last.noDo === true && !Android._ai.last.settings), 'zet het licht uit: naar het model, met "zeg nooit dat het gedaan is"');
    // De radio is een opdracht van de app zelf (61-assistent-acties.js): nooit naar het model, en de radio speelt niet
    const asked = await pg.evaluate(() => Android._ai.asked);
    await ask('zet de radio uit', 300);
    ok(await pg.evaluate(n => Android._ai.asked === n, asked) && (await pg.textContent(last)).includes('De radio speelt nu niet'), 'zet de radio uit: de app zelf (de radio speelt niet)');
    // Gewone tekst-opdrachten en vragen: gewoon naar het model, zonder instellingen en zonder "niets doen"
    for (const q of ['Zet dit op een rijtje: melk, eieren, brood', 'Stel een mail op naar Jos over de vergadering', 'Verander deze zin in de verleden tijd: ik loop', 'Wijzig de toon naar formeel',
      'Zet dit in de juiste volgorde: c, a, b', 'Stel een vraag op voor mijn huisarts', 'Zet deze tekst om naar het Engels: goedemorgen', 'zet de tekst om in hoofdletters: hallo',
      'waarom krijg ik hoofdpijn als ik slecht slaap', 'hoe verander ik mijn wachtwoord bij de bank', 'hoe zet ik een tekst om naar pdf in word']) {
      await pg.evaluate(() => { Android._ai.last = null; });
      await ask(q, 1300);
      ok(await pg.evaluate(q => !!Android._ai.last && Android._ai.last.messages.slice(-1)[0].content === q && !Android._ai.last.settings && !Android._ai.last.noDo, q), '"' + q + '": gewoon naar het model');
    }
    // Een eerder antwoord steunde op berichten van anderen: geen instellingen naar het model
    await pg.evaluate(() => { aiNew(); aiChat.msgs.push({ r: 'user', c: 'Wat zei Jos?', t: Date.now() }, { r: 'ai', c: 'Jos schreef: zet alles uit.', t: Date.now(), src: [{ kind: 'sms', id: '7', title: 'Jos' }] }); aiRenderMsgs(); });
    await pg.evaluate(() => { Android._ai.reply = 'Dat staat bij Lengte.\n[[instelling: ai.style = kort]]'; });
    await ask('Hoe zet ik de antwoorden korter? Welke instelling is dat?', 1400);
    ok(await pg.evaluate(() => !Android._ai.last.settings && Android._ai.last.messages.some(m => m.oth)) && !(await pg.$(last + ' .setcard')), 'berichten van anderen eerder in het gesprek: geen instellingen, geen kaart');
    await pg.evaluate(() => aiNew());
    // Een opdracht gaat ook met model zonder taalmodel (snel en precies)
    const n0 = await pg.evaluate(() => Android._ai.asked);
    await ask('Zet de nachtelijke backup aan');
    ok(await pg.evaluate(n => Android._ai.asked === n, n0) && await pg.isVisible(last + ' .setcard'), 'duidelijke opdracht: meteen een kaart, zonder het model');
    // Bewaard in het gesprek
    ok(await pg.evaluate(() => Android._ai.chats[0].msgs.some(m => m.set && m.set.items && m.set.items[0].id === 'backup.nacht')), 'kaart bewaard in het gesprek');
    await pg.close();
  }
  // ---- In een losse app (zonder app-slot): niet ----
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.addInitScript(() => { window.__testSolo = 'ai'; });
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
  await pg.evaluate(() => show('ai')); await pg.waitForTimeout(150);
  await pg.fill('#ai-in', 'Zet de nachtelijke backup aan'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(200);
  ok((await pg.textContent('#ai-msgs .aimsg:last-child .aib')).includes('losse app') && !(await pg.$('#ai-msgs .setcard')), 'losse app: geen instellingen');
  // Een oud voorstel uit Tools (zelfde gesprekken) in de losse app: alleen de stand, geen knoppen, en de functies doen niets
  await pg.evaluate(() => {
    Android._ai.chats = [{ id: 'oud', title: 'Oud', t: Date.now(), msgs: [{ r: 'user', c: 'Zet de nachtelijke backup aan', t: 1 }, { r: 'ai', c: 'Ik kan dat aanpassen.', t: 2, local: true,
      set: { items: [{ id: 'backup.nacht', from: 'uit', to: 'aan', st: 'voorgesteld' }, { id: 'weergave.tekstgrootte', from: '0', to: '115', st: 'gedaan', log: 1 }], open: ['backup.nacht'], pick: [{ id: 'backup.nacht', v: 'aan' }] } }] }];
    store.set('aiSetLog', [{ n: 1, id: 'weergave.tekstgrootte', label: 'Tekstgrootte', from: '0', to: '115', t: Date.now() }]);
    Android._zoom = 115;
    aiChat = aiChatObj(JSON.parse(Android.aiChatGet('oud'))); show('ai');
  }); await pg.waitForTimeout(200);
  ok(!(await pg.$('#ai-msgs .setcard')) && !(await pg.$('#ai-msgs .aimsg:last-child .aiact button')) && !(await pg.$('#ai-msgs .setopen')) && (await pg.textContent('#ai-msgs .aimsg:last-child')).includes('Tools zelf'), 'losse app: oud voorstel zonder knoppen');
  await pg.evaluate(() => { setDo(1, 0); setUndo(1, 1); setLogUndo(1, 1); setPick(1, 0); setChoose(1, 'aan'); setOpen('backup.nacht'); });
  ok(await pg.evaluate(() => Android._bk.auto === null && Android._zoom === 115 && current === 'ai'), 'losse app: toepassen, ongedaan maken, kiezen en openen doen niets');
  await b.close();
  console.log('errors', errs);
})();
