// Assistent: een vraag inspreken (op de telefoon uitgeschreven). Spraak en AI zijn nagebootst (testbrug).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; show('ai'); }); await pg.waitForTimeout(250);
    ok(await pg.isVisible('#ai-mic') && (await pg.getAttribute('#ai-mic', 'aria-label')) === 'Inspreken', 'microfoonknop in het gesprek');
    // Nog geen spraakmodel: aanbieden om te downloaden
    await pg.click('#ai-mic'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#modal.on') && (await pg.textContent('#modal-title')).includes('Spraakmodel'), 'zonder spraakmodel: downloaden aangeboden');
    await pg.click('#modal-ok'); await pg.waitForTimeout(1600);
    ok(await pg.evaluate(() => Android._tx.models.base === true), 'spraakmodel gedownload');
    // Inspreken: luisteren, stoppen, uitschrijven, meteen versturen
    await pg.click('#ai-mic'); await pg.waitForTimeout(700);
    ok((await pg.getAttribute('#ai-mic', 'aria-label')) === 'Stoppen met inspreken' && (await pg.textContent('#ai-vstate')).includes('Luisteren'), 'luisteren, met tijd');
    ok(await pg.isDisabled('#ai-in'), 'tijdens het luisteren: niet typen');
    await pg.screenshot({ path: __dirname + '/shots/ai-spraak-' + scheme + '.png' });
    await pg.click('#ai-mic'); await pg.waitForTimeout(30);
    ok((await pg.textContent('#ai-vstate')) === 'Uitschrijven…' && await pg.isDisabled('#ai-mic'), 'uitschrijven…');
    await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => Android._ai.last && Android._ai.last.messages.slice(-1)[0].content === 'Wat is de hoofdstad van Frankrijk?'), 'ingesproken vraag meteen verstuurd');
    ok((await pg.textContent('#ai-msgs .aimsg.me')).includes('hoofdstad van Frankrijk'), 'de vraag staat in het gesprek');
    await pg.waitForTimeout(1200);
    // Instelling: eerst in het invoerveld
    await pg.evaluate(() => show('aisetvoice')); await pg.waitForTimeout(200);
    ok((await pg.textContent('#ais-voice')).includes('Klaar'), 'instellingen: spraakmodel klaar');
    await pg.click('#ais-vsend');
    ok(await pg.evaluate(() => Android._ai.voiceSend === false) && (await pg.getAttribute('#ais-vsend', 'aria-checked')) === 'false', 'meteen versturen uit te zetten');
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(200);
    const n = await pg.$$eval('#ai-msgs .aimsg', x => x.length);
    await pg.evaluate(() => { Android._voiceResult = { text: 'Schrijf een gedicht' }; });
    await pg.click('#ai-mic'); await pg.waitForTimeout(200); await pg.click('#ai-mic'); await pg.waitForTimeout(300);
    ok((await pg.inputValue('#ai-in')) === 'Schrijf een gedicht' && (await pg.$$eval('#ai-msgs .aimsg', x => x.length)) === n, 'ingesproken tekst eerst in het invoerveld');
    // Fout bij uitschrijven: melding, knop weer bruikbaar
    await pg.evaluate(() => { Android._voiceResult = { error: 'Er is geen spraak herkend' }; $('#ai-in').value = ''; });
    await pg.click('#ai-mic'); await pg.waitForTimeout(150); await pg.click('#ai-mic'); await pg.waitForTimeout(300);
    ok((await pg.textContent('#toast')).includes('geen spraak') && !(await pg.isDisabled('#ai-mic')), 'fout: melding, microfoon weer klaar');
    // Android stopt de opname zelf na 5 minuten: de app gaat over op uitschrijven, en verstuurt daarna gewoon
    await pg.evaluate(() => { Android._voiceResult = { text: 'Lange vraag' }; $('#ai-in').value = ''; });
    await pg.click('#ai-mic'); await pg.waitForTimeout(150);
    await pg.evaluate(() => { Android._aiVoiceState = 'working'; }); await pg.waitForTimeout(600);
    ok((await pg.textContent('#ai-vstate')) === 'Uitschrijven…' && (await pg.getAttribute('#ai-mic', 'aria-disabled')) === 'true', 'na 5 minuten: zelf naar uitschrijven');
    await pg.evaluate(() => { Android._aiVoiceState = ''; Android.aiVoiceStop(); }); await pg.waitForTimeout(250);
    ok((await pg.inputValue('#ai-in')) === 'Lange vraag', 'na 5 minuten: de tekst komt nog');
    // App naar de achtergrond tijdens het luisteren: niet blijven hangen in "luisteren"
    await pg.evaluate(() => { $('#ai-in').value = ''; aiGrow(); });
    await pg.click('#ai-mic'); await pg.waitForTimeout(150);
    await pg.evaluate(() => { Android._aiRec = false; onPauseApp(); }); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !aiRec) && !(await pg.isDisabled('#ai-in')) && (await pg.getAttribute('#ai-mic', 'aria-label')) === 'Inspreken', 'naar de achtergrond: niet meer luisteren');
    // Uitschrijven loopt nog en je opent een nieuw gesprek: niet daarin versturen
    await pg.evaluate(() => { Android._voiceResult = { text: 'Vraag voor het oude gesprek' }; Android._ai.last = null; });
    await pg.click('#ai-mic'); await pg.waitForTimeout(100); await pg.click('#ai-mic');
    await pg.evaluate(() => aiNew()); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => Android._ai.last === null) && (await pg.inputValue('#ai-in')) === 'Vraag voor het oude gesprek', 'ander gesprek: niet meteen versturen, tekst in het invoerveld');
    ok((await pg.getAttribute('#ai-vstate', 'aria-hidden')) === 'true' && (await pg.getAttribute('#ai-vsay', 'role')) === 'status', 'schermlezer: alleen de overgangen');
    await pg.evaluate(() => { $('#ai-in').value = ''; aiGrow(); });
    // Weg van het scherm tijdens het luisteren: opname stopt
    await pg.click('#ai-mic'); await pg.waitForTimeout(150);
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._aiVoiceCancelled === true && !aiRec), 'weg van het scherm: opname gestopt');
    // Geen toestemming voor de microfoon
    await pg.evaluate(() => { Android._voiceErr = 'mic'; Android._mu.mic = false; show('ai'); }); await pg.waitForTimeout(150);
    await pg.click('#ai-mic'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._mu.mic === true) && (await pg.textContent('#toast')).includes('microfoon'), 'zonder toestemming: microfoon gevraagd');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
