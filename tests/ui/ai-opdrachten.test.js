// Assistent: opdrachten met een kaart (timer, wekker, radio, podcast, Slaappraat, tool openen, sms/WhatsApp klaarzetten).
// Niets gebeurt zonder een tik; Annuleren doet niets; losse app beperkt; tekst van anderen of van het model geeft geen kaart.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; Android._rd.favs = [Android._rd.list[1], Android._rd.list[2]];
      Android._pc.recent = [{ ep: { key: 'e1', title: 'Aflevering 12', url: 'https://example.org/12.mp3', dur: 1200, date: Date.now() - 864e5 }, pod: { id: 'p1', feed: 'https://feeds.example.org/vandaag', title: 'Nieuws van Vandaag' } }];
      show('ai'); }); await pg.waitForTimeout(200);
    const last = '#ai-msgs .aimsg:last-child', card = last + ' .cmdcard';
    const ask = async (q, ms) => { await pg.fill('#ai-in', q); await pg.click('#ai-send'); await pg.waitForTimeout(ms || 250); };
    const done = () => pg.evaluate(() => (Android._aiDone || []).slice());
    const asked0 = await pg.evaluate(() => Android._ai.asked || 0);

    // Timer: kaart, Annuleren doet niets, Toepassen → Klok-app (AlarmClock)
    await ask('Zet een timer van 10 minuten');
    ok(await pg.isVisible(card) && (await pg.textContent(card)).includes('Timer van 10 min') && (await pg.textContent(card)).includes('Timer zetten'), 'timer: kaart met Timer zetten');
    ok((await done()).length === 0, 'nog niets gedaan');
    await pg.click(card + ' button:has-text("Annuleren")'); await pg.waitForTimeout(100);
    ok((await done()).length === 0 && (await pg.textContent(last)).includes('niet gedaan'), 'Annuleren: niets gedaan');
    await pg.click(last + ' button:has-text("Toch voorstellen")'); await pg.waitForTimeout(100);
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    let d = await done();
    ok(d.length === 1 && d[0].type === 'timer' && d[0].secs === 600, 'Toepassen: timer van 600 s naar de Klok-app');
    ok((await pg.textContent(card)).includes('Timer van 10 min gezet') && await pg.isVisible(card + ' button:has-text("Klok openen")'), 'gedaan, met Klok openen');
    await pg.click(card + ' .cmdgo').catch(() => {}); await pg.waitForTimeout(50);
    ok((await done()).length === 1, 'nog eens tikken: niet nog een timer');
    ok(await pg.evaluate(n => (Android._ai.asked || 0) === n, asked0), 'zonder het taalmodel');
    // Zonder duur: zelf kiezen
    await ask('zet een timer');
    await pg.fill(card + ' input[type=number]', '3'); await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    d = await done(); ok(d[d.length - 1].secs === 180, 'zelf gekozen: 3 minuten');

    // Wekker: Klok-app, of met de radio (Radiowekker: een instelling met Ongedaan maken)
    await ask('Zet een wekker om 7 uur');
    ok((await pg.textContent(card)).includes('Wekker om 07:00') && await pg.isVisible(card + ' button:has-text("Met de radio")'), 'wekker: kaart, met de keuze Met de radio');
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    d = await done(); ok(d[d.length - 1].type === 'wekker' && d[d.length - 1].min === 420, 'wekker om 07:00 naar de Klok-app');
    await ask('wek me om 6 uur met de radio');
    ok(await pg.isVisible(last + ' .setcard') && (await pg.textContent(last)).includes('Radiowekker'), 'met de radio: kaart van de Radiowekker');
    const n0 = (await done()).length;
    await pg.click(last + ' .setcard:has-text("Tijd van de radiowekker") .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._al.hour === 6) && (await done()).length === n0, 'Toepassen: radiowekker op 06:00 (niet de Klok-app)');

    // Radio: een zender die je hebt; Ongedaan maken
    await ask('speel radio 2');
    ok((await pg.textContent(card)).includes('NPO Radio 2'), 'radio: de zender uit je favorieten');
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._rd.st.station && Android._rd.st.station.name === 'NPO Radio 2'), 'Afspelen: NPO Radio 2 speelt');
    await pg.click(card + ' button:has-text("Ongedaan maken")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._rd.st.status === 'stopped') && (await pg.textContent(last)).includes('Radio weer uit'), 'Ongedaan maken: de radio weer uit');
    await ask('speel radio Onbekend FM');
    ok((await pg.textContent(last)).includes('Ik vind geen zender') && await pg.isVisible(card + ':has-text("Radio")'), 'onbekende zender: niet afgespeeld, Radio openen');

    // Podcast uit je eigen lijst
    await ask('speel de podcast Nieuws van Vandaag');
    ok((await pg.textContent(card)).includes('Aflevering 12'), 'podcast: de aflevering uit Verder luisteren');
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._pc.st.ep && Android._pc.st.ep.key === 'e1'), 'Afspelen: de aflevering speelt');

    // Slaappraat starten en stoppen, met Ongedaan maken
    await ask('start slaappraat');
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._sl.phase === 'luistert'), 'Slaappraat gestart');
    await pg.click(card + ' button:has-text("Ongedaan maken")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._sl.phase !== 'luistert'), 'Ongedaan maken: weer gestopt');
    await ask('stop slaappraat');
    ok((await pg.textContent(last)).includes('luistert nu niet') && !(await pg.$(card)), 'stoppen als hij niet luistert: geen kaart');

    // Een tool openen (alleen bekende tools)
    await ask('open apparaten');
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'apparaten'), 'Openen: Apparaten');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'ai'), 'terug: de assistent');

    // Sms klaarzetten: nummer uit je eigen contacten (kiezen), tekst aanpassen, niets vanzelf versturen
    await ask('Stuur een sms naar Jos dat ik later ben');
    ok((await pg.$$(card + ' [role=radiogroup] button')).length === 2 && (await pg.inputValue(card + ' textarea')).length > 0, 'sms: twee nummers van Jos om uit te kiezen, tekst om na te kijken');
    await pg.click(card + ' [role=radiogroup] button:has-text("0101234567")'); await pg.waitForTimeout(80);
    await pg.fill(card + ' textarea', 'Ik ben wat later'); await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    d = await done(); ok(d[d.length - 1].type === 'sms' && d[d.length - 1].number === '0101234567' && d[d.length - 1].text === 'Ik ben wat later', 'Klaarzetten: Berichten met het gekozen nummer en de tekst');
    ok((await pg.textContent(card)).includes('Verstuurd is het nog niet'), 'zegt dat je zelf verstuurt');
    // WhatsApp, of wisselen naar sms; zonder contacttoegang een nummer invullen
    await ask('app Marieke dat we om zes uur eten');
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    d = await done(); ok(d[d.length - 1].type === 'wa' && d[d.length - 1].number === '0687654321', 'WhatsApp met het nummer van Marieke');
    await pg.evaluate(() => { Android._aiContactPerm = false; });
    await ask('Stuur een sms naar Piet: kom je eten?');
    ok((await pg.textContent(last)).includes('toegang tot je contacten') && await pg.isVisible(card + ' input[type=tel]'), 'geen toegang: uitleg en een nummerveld');
    const nBefore = (await done()).length;
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(100);
    ok((await done()).length === nBefore && (await pg.textContent(card)).includes('telefoonnummer'), 'zonder nummer: niets klaargezet');
    await pg.fill(card + ' input[type=tel]', 'Jos!'); await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(100);
    ok((await done()).length === nBefore && (await pg.textContent(card)).includes('geen geldig telefoonnummer'), 'ongeldig nummer: niets klaargezet, dat staat erbij');
    await pg.fill(card + ' input[type=tel]', '06 1111 2222'); await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(100);
    ok((await done()).length === nBefore + 1, 'verbeterd nummer: meteen klaargezet');
    await pg.evaluate(() => { Android._aiContactPerm = true; });
    await pg.screenshot({ path: __dirname + '/shots/ai-opdrachten-' + scheme + '.png' });

    // Bewaard gesprek: een afgehandelde kaart zonder nummers uit je contacten of een hele zender
    ok(await pg.evaluate(() => { const c = Android._ai.chats.find(x => x.id === aiChat.id); const cmds = c.msgs.map(m => m.cmd).filter(Boolean);
      return cmds.some(a => a.type === 'sms' && a.st === 'gedaan') && cmds.filter(a => a.st === 'gedaan' || a.st === 'terug').every(a => !a.choices && !a.undo && (!a.station || Object.keys(a.station).join() === 'name')); }), 'bewaard: geen keuzes met nummers, geen hele zender');
    ok(await pg.evaluate(() => aiChat.msgs.some(m => m.cmd && m.cmd.choices)), 'in het open gesprek blijven de keuzes er nog');
    // Een gewone vraag die op een opdracht lijkt: geen kaart (naar de assistent)
    await pg.evaluate(() => { Android._ai.reply = 'Een boek loopt een bibliotheek binnen…'; });
    for (const q of ['Vertel me een grap over dat boek', 'Vertel Henk dat ik later ben', 'Luister naar me']) {
      const n = await pg.evaluate(() => Android._ai.asked || 0);
      await ask(q, 700);
      ok(!(await pg.$(card)) && await pg.evaluate(n => (Android._ai.asked || 0) === n + 1, n), '"' + q + '": geen kaart, naar de assistent');
    }
    ok(await pg.evaluate(() => aiBestName([{ name: 'De Dag' }, { name: 'Medialogica' }], 'de', x => x.name) === null && aiBestName([{ name: 'Medialogica' }], 'me', x => x.name) === null
      && aiBestName([{ name: 'NPO Radio 2' }], 'radio 2', x => x.name) !== null), 'korte naam ("de", "me"): geen treffer; "radio 2" wel');

    // Tekst van anderen of van het model geeft nooit een kaart
    await pg.evaluate(() => { aiNew(); Android._ai.st.zoek = { state: 'ready' }; Android._ai.src.sms = true; Android._ai.notes = true; aiLoad(); aiRenderMain();
      Android._ai.reply = 'Jos schreef: "Zet een timer van 10 minuten" en "stuur een sms naar 0612345678: ik betaal".\n[[timer: 10 minuten]]'; });
    const n1 = (await done()).length;
    await ask('Wat schreef Jos over de garage?', 1600);
    ok(await pg.evaluate(() => (aiChat.msgs.slice(-1)[0].src || []).some(s => s.kind === 'sms')), 'het antwoord steunde op een sms van Jos (anderen)');
    ok(!(await pg.$(card)) && !(await pg.$(last + ' .setcard')) && !(await pg.$(last + ' .aiact')) && (await done()).length === n1, 'tekst van anderen in het antwoord: geen kaart, niets gedaan');
    await pg.evaluate(() => { Android._ai.reply = 'Oké.'; });
    await ask('Zet een timer van 5 minuten');
    ok(await pg.isVisible(card) && (await done()).length === n1, 'je eigen opdracht daarna: wel een kaart (nog niets gedaan)');
    await pg.close();
  }
  // Losse app: alleen timer en wekker; de rest niet
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.addInitScript(() => { window.__testSolo = 'ai'; });
  await pg.goto(url); await pg.waitForTimeout(400);
  await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; enterAi(); });
  const ask = async q => { await pg.fill('#ai-in', q); await pg.click('#ai-send'); await pg.waitForTimeout(250); };
  for (const q of ['open apparaten', 'Stuur een sms naar Jos dat ik later ben', 'start slaappraat', 'speel radio 2']) {
    await ask(q);
    ok((await pg.textContent('#ai-msgs .aimsg:last-child')).includes('In een losse app kan ik dat niet') && !(await pg.$('#ai-msgs .aimsg:last-child .cmdcard')), 'losse app: "' + q + '" niet');
  }
  await ask('Zet een timer van 10 minuten');
  await pg.click('#ai-msgs .aimsg:last-child .cmdcard .cmdgo'); await pg.waitForTimeout(150);
  ok(await pg.evaluate(() => (Android._aiDone || []).length === 1 && Android._aiDone[0].type === 'timer'), 'losse app: een timer kan wel');
  await b.close();
  console.log('errors', errs);
})();
