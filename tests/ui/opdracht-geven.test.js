// Opdracht geven: een afspraak (of herinnering) zonder taalmodel, rechtstreeks in de Agenda van Tools; de assistent
// openen met de microfoon aan (snelkoppeling, tegel, widget, digitale assistent); het scherm "Wat kan ik zeggen?".
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const b = await chromium.launch(), errors = [];
  for (const colorScheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme, timezoneId: 'Europe/Amsterdam' });
    pg.on('pageerror', e => errors.push(colorScheme + ': ' + e.message));
    const ok = (v, m) => { console.log((v ? '✓ ' : '✗ ') + m); if (!v) errors.push(colorScheme + ': ' + m); };
    await pg.goto('file://' + path.join(__dirname, 'ui/index.html'));
    await pg.waitForTimeout(250);
    const ask = async q => { await pg.fill('#ai-in', q); await pg.click('#ai-send'); await pg.waitForTimeout(250); };

    // 1. Zonder taalmodel: de afspraak wordt een kaart; niets gebeurt zonder tik
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(200);
    ok(!(await pg.evaluate(() => aiChatReady())), 'geen taalmodel');
    ok((await pg.textContent('#ai-sugg')).includes('Wat kan ik zeggen?'), 'suggestie: Wat kan ik zeggen?');
    const voor = await pg.evaluate(() => (Android._agInit().items || []).length);
    await ask('Maak een afspraak morgen om 10 uur met Jos');
    ok(await pg.isVisible('.aiact') && (await pg.textContent('.aiact')).includes('In je agenda zetten?') && (await pg.textContent('.aiact')).includes('Afspraak met Jos'), 'kaart: afspraak met Jos');
    ok((await pg.textContent('.aiact .mini.on')).includes('Bewaren') && (await pg.textContent('#ai-msgs')).includes('Kijk de afspraak na'), 'met Bewaren en een korte uitleg');
    ok(!(await pg.textContent('#ai-msgs')).includes('taalmodel nodig'), 'geen melding dat er een taalmodel nodig is');
    ok(await pg.evaluate(n => (Android._agInit().items || []).length === n, voor), 'nog niets bewaard');
    ok(!(await pg.$('.aiact select')), 'zonder agenda\'s op de telefoon geen keuzelijst');
    await pg.screenshot({ path: __dirname + '/shots/opdracht-afspraak-' + colorScheme + '.png' });
    await pg.click('.aiact .mini.on'); await pg.waitForTimeout(150);
    const it = await pg.evaluate(() => Android._agInit().laatste);
    const morgen = await pg.evaluate(() => rgDayAdd(rgToday(), 1));
    ok(it && it.titel === 'Afspraak met Jos' && it.dag === morgen && it.van === '10:00' && it.tot === '11:00' && it.kalender === '', 'bewaard in de Agenda van Tools: morgen 10:00-11:00');
    ok((await pg.textContent('.aiact.done')).includes('In je agenda gezet'), 'gedaan');
    ok(await pg.evaluate(() => Android._ai.chats[0] && Android._ai.chats[0].msgs.slice(-1)[0].act.st === 'gedaan' && Android._ai.chats[0].msgs.slice(-1)[0].act.via === 'tools'), 'in het gesprek bewaard');

    // 2. Zonder moment: zelf kiezen
    await pg.evaluate(() => aiNew());
    await ask('Maak een afspraak');
    ok(await pg.isVisible('.aiact input[type=datetime-local]') && (await pg.textContent('.aiact')).includes('Kies wanneer'), 'zonder moment: tijdkiezer');

    // 3. Herinnering uit de vraag (zoals AiActions.fromQuestion)
    await pg.evaluate(() => { aiNew(); Android._actQ = JSON.stringify({ type: 'herinnering', title: 'De vuilnis', text: '', need: '', src: 'vraag', at: Date.now() + 864e5, day: Date.now() + 864e5 }); });
    await ask('Herinner me morgen aan de vuilnis');
    ok((await pg.textContent('.aiact')).includes('Herinnering zetten?') && (await pg.textContent('.aiact .mini.on')).includes('Zetten'), 'herinnering: kaart met Zetten');
    // Tekst uit de vraag blijft tekst
    await pg.evaluate(() => { aiNew(); Android._actQ = JSON.stringify({ type: 'agenda', title: '<img src=x onerror="window.__x=1">', text: '', need: 'datum', src: 'vraag' }); });
    await ask('Maak een afspraak');
    ok(await pg.evaluate(() => !window.__x && !document.querySelector('.aiact img') && $('.aiact').textContent.includes('<img')), 'titel als tekst, niet als HTML');
    await pg.evaluate(() => { delete Android._actQ; });

    // 4. Een oudere app zonder aiActQ: zoals vroeger (taalmodel nodig)
    await pg.evaluate(() => { aiNew(); Android._aq = Android.aiActQ; Android.aiActQ = undefined; });
    await ask('Maak een afspraak morgen om 10 uur met Jos');
    ok(!(await pg.$('.aiact')) && (await pg.textContent('#ai-msgs')).includes('taalmodel nodig'), 'oudere app: geen kaart, melding over het taalmodel');
    await pg.evaluate(() => { Android.aiActQ = Android._aq; });

    // 5. Opdracht geven van buiten: de assistent met de microfoon aan, in een nieuw gesprek
    await pg.evaluate(() => { aiNew(); show('home'); Android._tx.models.base = true; Android._tx.model = 'base'; }); // spraakmodel staat er
    await pg.evaluate(() => { aiChat.msgs.push({ r: 'user', c: 'oud', t: 1 }); }); // er staat nog iets
    await pg.evaluate(() => openTool('ai-spreek')); await pg.waitForTimeout(700);
    ok(await pg.evaluate(() => current === 'ai' && aiRec === true && !!Android._aiRec), 'openTool(ai-spreek): assistent luistert');
    ok(await pg.evaluate(() => aiChat.msgs.length === 0), 'in een nieuw gesprek');
    await pg.evaluate(() => aiVoiceCancelBtn()); await pg.waitForTimeout(100);
    // Lang drukken op het app-icoon: Opdracht geven als eerste
    const dyn = await pg.evaluate(() => { setDynamicShortcuts(); return JSON.parse(Android._dyn); });
    ok(dyn.length === 4 && dyn[0][0] === 'ai-spreek' && dyn[0][1] === 'Opdracht geven', 'snelle keuze bij het app-icoon: Opdracht geven');

    // 6. Scherm Opdracht geven
    await pg.evaluate(() => aiMenu()); await pg.waitForTimeout(100);
    ok((await pg.textContent('#sheet-acts')).includes('Opdracht geven: wat kan ik zeggen?'), 'in het menu ⋯');
    await pg.evaluate(() => closeSheet()); await pg.waitForTimeout(400);
    await pg.click('#ai-sugg .chip.new >> text=Wat kan ik zeggen?'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'aizeggen'), 'scherm geopend');
    ok((await pg.textContent('#aiz-lijst')).includes('Maak een afspraak morgen om 10 uur met Jos') && await pg.$$eval('#aiz-lijst .card', x => x.length) === 7, 'voorbeelden per onderwerp');
    ok((await pg.textContent('#aiz-assist-st')).includes('Niet gekozen'), 'digitale assistent: niet gekozen');
    const klein = await pg.$$eval('#s-aizeggen .strow, #aiz-lijst .chip', x => x.filter(e => { const r = e.getBoundingClientRect(); return r.height < 44; }).length);
    ok(klein === 0, 'tikvlakken minstens 44 px');
    await pg.screenshot({ path: __dirname + '/shots/opdracht-geven-' + colorScheme + '.png', fullPage: true });
    await pg.click('#aiz-pin'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._opdrPin === 1) && (await pg.textContent('#toast')).includes('startscherm'), 'snelkoppeling gevraagd');
    await pg.click('#aiz-tegel'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._opdrTegel === 1) && (await pg.textContent('#toast')).includes('Tegel toegevoegd'), 'tegel gevraagd');
    await pg.click('#aiz-widget'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._opdrWidget === 1), 'widget gevraagd');
    await pg.click('#aiz-assist'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#modal')).includes('Digitale assistent'), 'eerst uitleg, dan pas de instellingen');
    await pg.click('#modal-ok'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._assistKies === 1), 'instellingen van Android geopend');
    await pg.evaluate(() => { Android._opdr = { assistent: true }; aizRender(); });
    ok((await pg.textContent('#aiz-assist-st')).includes('Tools is je digitale assistent'), 'digitale assistent: gekozen');
    await pg.click('#aiz-lijst .chip >> nth=0'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'ai' && $('#ai-in').value === 'Maak een afspraak morgen om 10 uur met Jos'), 'voorbeeld ingevuld bij de assistent');
    await pg.close();
  }
  await b.close();
  console.log('errors', errors);
  process.exit(errors.length ? 1 : 0);
})();
