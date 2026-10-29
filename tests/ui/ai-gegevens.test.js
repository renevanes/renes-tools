// Assistent: kiezen waar de AI in mag kijken (notities, gesprekken, sms, WhatsApp, oproepen, contacten), met toestemming en zoekindex.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    // Zonder zoekmodel: uitleg en een weg naar Slim zoeken
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; show('aiset'); }); await pg.waitForTimeout(200);
    ok((await pg.textContent('#ai-sum-data')) === 'Notities, Uitgeschreven gesprekken', 'standaard: alleen notities en gesprekken');
    await pg.click('#s-aiset .strow:has-text("Gegevens")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'aisetdata') && (await pg.textContent('#aid-need')).includes('slim zoeken'), 'zonder zoekmodel: uitleg');
    ok((await pg.$$('#aid-list .tg')).length === 8, 'acht soorten gegevens');
    // Met zoekmodel
    await pg.evaluate(() => { Android._ai.st.zoek = { state: 'ready' }; enterAiSet(); }); await pg.waitForTimeout(100);
    ok((await pg.textContent('#aid-need')) === '' && (await pg.textContent('#aid-index')).includes('Zoekindex'), 'met zoekmodel: zoekindex zichtbaar');
    const sw = k => '#aid-list .tg[aria-label="' + k + '"]';
    ok((await pg.getAttribute(sw('Notities'), 'aria-checked')) === 'true' && (await pg.getAttribute(sw("Sms'jes"), 'aria-checked')) === 'false', 'schakelaars tonen de stand');
    // Sms aan (toestemming is er): meteen op de achtergrond in de index
    await pg.evaluate(() => { Android._ai.idxMs = 1500; }); // ruim, ook op een trage testmachine
    const until = (f, arg) => pg.waitForFunction(f, arg, { timeout: 5000 }).catch(() => {});
    await pg.click(sw("Sms'jes")); await until(() => Android._ai.src.sms === true);
    ok(await pg.evaluate(() => Android._ai.src.sms === true && Android._ai.calls.some(c => c[0] === 'src' && c[1] === 'sms' && c[2])), 'sms aangezet');
    ok((await pg.textContent('#toast')).includes('zoekindex') && (await pg.textContent('#aid-index')).includes('Bijwerken…'), 'sms: wordt aan de zoekindex toegevoegd, met voortgang');
    await until(() => !$('#aid-index').textContent.includes('Bijwerken…'));
    ok(!(await pg.textContent('#aid-index')).includes('Bijwerken…') && !(await pg.textContent('#toast')).includes('Zoekindex bijgewerkt'), 'klaar op de achtergrond: zonder extra melding');
    await pg.evaluate(() => { Android._ai.idxMs = 100; });
    // Contacten aan zonder toestemming: Android vraagt erom
    await pg.click(sw('Contacten')); await until(() => Android._ai.perm.contacts === true && !$('#aid-list').textContent.includes('mag er nog niet bij'));
    ok(await pg.evaluate(() => Android._ai.src.contacts === true && (Android._permAsked || []).includes('contactsread')), 'contacten: om toestemming gevraagd');
    ok(await pg.evaluate(() => Android._ai.perm.contacts === true) && !(await pg.textContent('#aid-list')).includes('mag er nog niet bij'), 'na toestemming: geen waarschuwing meer');
    // WhatsApp aan zonder leesbare chats: uitleg met knop
    await pg.click(sw('WhatsApp')); await pg.waitForTimeout(100);
    ok((await pg.textContent('#aid-list')).includes('Maak eerst je chats leesbaar'), 'whatsapp nog niet leesbaar: uitleg');
    await pg.click(sw('WhatsApp')); await until(() => Android._ai.src.wa === false);
    ok(await pg.evaluate(() => Android._ai.src.wa === false) && (await pg.textContent('#toast')).includes('doet niet meer mee'), 'whatsapp weer uit');
    await pg.screenshot({ path: __dirname + '/shots/ai-gegevens-' + scheme + '.png', fullPage: true });
    // Stoppen tijdens het bijwerken
    await pg.waitForTimeout(400); // eerdere (nagebootste) bijwerkrondes zijn klaar
    await until(() => !$('#aid-index').textContent.includes('Bijwerken…'));
    await pg.evaluate(() => { Android._ai.idxMs = 1500; });
    await pg.click('#aid-index button.mini'); await pg.waitForTimeout(60);
    ok((await pg.textContent('#aid-index')).includes('Bijwerken…'), 'zoekindex bijwerken vanuit Gegevens');
    await pg.click('#aid-index button:has-text("Stoppen")');
    ok(await pg.evaluate(() => Android._ai.calls.some(c => c[0] === 'indexStop')), 'bijwerken te stoppen');
    await until(() => !$('#aid-index').textContent.includes('Bijwerken…'));
    await pg.evaluate(() => { Android._ai.idxMs = 0; goBack(); }); await pg.waitForTimeout(100);
    ok((await pg.textContent('#ai-sum-data')).includes("Sms'jes") && (await pg.textContent('#ai-sum-data')).includes('Contacten'), 'overzicht: gekozen gegevens');
    // De assistent gebruikt ze: bron uit een sms, en die opent het sms-gesprek
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ai-notes')).includes('Met mijn gegevens'), 'knop heet "Met mijn gegevens"');
    await pg.fill('#ai-in', 'Wanneer belt de garage?'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1300);
    const chip = '#ai-msgs .aimsg:last-child .aisrc button[data-k=sms]';
    ok(await pg.isVisible(chip) && (await pg.textContent(chip)).includes('Jos'), 'bron uit een sms bij het antwoord');
    await pg.click(chip); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'smschat' && smsChatCur === 7), 'bron opent het sms-gesprek');
    // Zoeken (overal): treffers uit sms en contacten, met de juiste knop
    await pg.evaluate(() => { show('search'); }); await pg.waitForTimeout(100);
    await pg.fill('#gs-q', 'garage'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(1100);
    ok(await pg.isVisible('#gs-res .aigs .gi[data-k=sms]') && await pg.isVisible('#gs-res .aigs .gi[data-k=contacts]'), 'op betekenis: ook sms en contacten');
    const cx = await pg.getAttribute('#gs-res .aigs .gi[data-k=contacts]', 'data-x');
    ok(JSON.parse(cx).k === 'k1' && JSON.parse(cx).id === '5', 'contact: sleutel en nummer goed uit elkaar');
    const m = await pg.evaluate(() => [aiHitGs({ kind: 'wa', id: '12', title: 'Groep' }), aiHitGs({ kind: 'calls', id: '+31612345678', title: 'Jos' }), aiHitGs({ kind: 'tx', id: 't1', from: '1500' }), aiHitGs({ kind: 'notes', id: 'n1' })]);
    ok(m[0][0] === 'wa' && m[0][1].chat === 12 && m[0][1].name === 'Groep' && m[1][0] === 'calls' && m[1][1].number === '+31612345678'
      && m[2][0] === 'tx' && m[2][1].from === 1500 && m[3][0] === 'notes' && m[3][1].id === 'n1', 'treffers openen het juiste scherm (whatsapp, oproepen, gesprek, notitie)');
    // Weg uit Persoonlijk via een sprong (niet terug): concept ook bewaard
    await pg.evaluate(() => { show('aisetpers'); $('#aic-about').value = 'Via een sprong'; aicDirty(); jumpTo('aisetdata'); }); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.cfg && Android._ai.cfg.about === 'Via een sprong'), 'weg uit Persoonlijk via een sprong: bewaard');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
