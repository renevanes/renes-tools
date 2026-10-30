// Assistent: vragen over je eigen gegevens (Gebruik, Slaappraat, Check-in, Apparaten). Alleen als de bron aan staat,
// een duidelijke vraag beantwoordt de app zelf, uitleg gaat naar het taalmodel (met de feiten). Testbrug (zoals AiData).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; show('ai'); }); await pg.waitForTimeout(200);
    const last = '#ai-msgs .aimsg:last-child';
    const ask = async (q, ms) => { await pg.fill('#ai-in', q); await pg.click('#ai-send'); await pg.waitForTimeout(ms || 250); };

    // Lege staat: de nieuwe mogelijkheden als voorbeeld
    const sugg = await pg.textContent('#ai-sugg');
    ok(['Hoeveel schermtijd vandaag?', 'Wat zei ik vannacht?', 'Zet een timer van 10 minuten', 'Start een gesprek'].every(x => sugg.includes(x)), 'voorbeelden: schermtijd, vannacht, timer, gesprek');
    await pg.click('#ai-sugg button:has-text("Hoeveel schermtijd vandaag?")');
    ok((await pg.inputValue('#ai-in')) === 'Hoeveel schermtijd vandaag?', 'een voorbeeld zet de vraag klaar');

    // Bron staat uit (standaard): eerlijk zeggen en waar je hem aanzet; niets gelezen, niet naar het model
    const asked0 = await pg.evaluate(() => Android._ai.asked || 0);
    await ask('Hoeveel schermtijd vandaag?');
    ok((await pg.textContent(last)).includes('Daarvoor mag ik nog niet in Gebruik kijken') && await pg.isVisible(last + ' .setopen button'), 'bron uit: uitleg, met een knop naar Gegevens');
    ok(await pg.evaluate(n => (Android._ai.asked || 0) === n && !Android._ai.calls.some(c => c[0] === 'data'), asked0), 'bron uit: niets gelezen, niet naar het model');
    await pg.click(last + ' .setopen button'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'aisetdata'), 'de knop opent Gegevens');
    ok((await pg.$$('#aid-list .tg')).length === 10 && (await pg.textContent('#aid-list')).includes('Gebruik') && (await pg.textContent('#aid-list')).includes('Apparaten'), 'Gebruik en Apparaten staan bij de bronnen');
    // Aanzetten is een privacykeuze: de assistent zet het zelf niet aan (register)
    ok(await pg.evaluate(() => ['gebruik', 'apparaten', 'sleep', 'checkin'].every(k => { const e = setEntry('ai.bron.' + k); return e && setSafe(e, 'aan') && !setSafe(e, 'uit'); })), 'register: aanzetten zelf doen (privacy), uitzetten mag');
    await pg.click('#aid-list .tg[aria-label="Gebruik"]'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ai.src.gebruik === true && Android._ai.calls.some(c => c[0] === 'src' && c[1] === 'gebruik' && c[2] === true)), 'Gebruik aangezet');
    ok(!(await pg.textContent('#toast')).includes('zoekindex'), 'Gebruik gaat niet in de zoekindex');
    await pg.evaluate(() => { Android._ai.src.sleep = true; Android._ai.src.checkin = true; Android._ai.src.apparaten = true; goBack(); }); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'ai'), 'terug in de assistent');

    // Duidelijke vragen: de app antwoordt zelf, met de bron eronder
    await ask('Hoeveel schermtijd vandaag?');
    ok((await pg.textContent(last + ' .aib')).includes('2 uur en 14 min') && (await pg.textContent(last + ' .aisrc')).includes('Gebruik vandaag'), 'schermtijd vandaag: antwoord en bron');
    ok(await pg.evaluate(n => (Android._ai.asked || 0) === n, asked0), 'zonder het taalmodel');
    await pg.click(last + ' .aisrc button'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'gebruik'), 'de bron opent Gebruik');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(250);
    await ask('Wat zei ik vannacht?');
    ok((await pg.textContent(last + ' .aib')).includes('Waar is de fiets?'), 'wat zei ik vannacht');
    await ask('Waar was mijn oortje het laatst?');
    ok((await pg.textContent(last + ' .aib')).includes('Mijn Jabra'), 'waar was mijn oortje');
    await ask('Hoe gaat het met mijn stemming volgens check-in?');
    ok((await pg.textContent(last + ' .aib')).includes('gemiddeld 4,1'), 'check-in: trend');
    ok(!(await pg.$(last + ' .cmdcard')) && !(await pg.$(last + ' .setcard')), 'een vraag over gegevens: geen kaart');
    // Uitleg of advies: naar het taalmodel (dat in de app de feiten erbij krijgt)
    await pg.evaluate(() => { Android._ai.reply = 'Een paar tips: leg je telefoon weg.'; });
    await ask('Hoe kan ik minder schermtijd hebben?', 900);
    ok(await pg.evaluate(n => (Android._ai.asked || 0) === n + 1 && Android._ai.last.messages.slice(-1)[0].content === 'Hoe kan ik minder schermtijd hebben?', asked0), 'advies: naar het taalmodel');
    // Het gesprek wordt bewaard met de bron
    ok(await pg.evaluate(() => Android._ai.chats[0].msgs.some(m => (m.src || []).some(s => s.kind === 'gebruik'))), 'bewaard met de bron');
    await pg.screenshot({ path: __dirname + '/shots/ai-eigen-gegevens-' + scheme + '.png' });

    // Zonder taalmodel werkt een duidelijke vraag ook
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'none' }; aiNew(); aiRenderMain(); });
    await ask('Wat zei ik vannacht?');
    ok((await pg.textContent(last + ' .aib')).includes('Waar is de fiets?'), 'zonder taalmodel: ook een antwoord');
    await pg.close();
  }
  // Losse app: niet in je gegevens kijken
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.addInitScript(() => { window.__testSolo = 'ai'; });
  await pg.goto(url); await pg.waitForTimeout(400);
  await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; Android._ai.src.gebruik = true; enterAi(); });
  await pg.fill('#ai-in', 'Hoeveel schermtijd vandaag?'); await pg.click('#ai-send'); await pg.waitForTimeout(250);
  ok((await pg.textContent('#ai-msgs .aimsg:last-child')).includes('In een losse app kijk ik niet in je Gebruik') && !(await pg.evaluate(() => Android._ai.calls.some(c => c[0] === 'data'))), 'losse app: niet in Gebruik kijken');
  await b.close();
  console.log('errors', errs);
})();
