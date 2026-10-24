// Assistent: antwoorden voorlezen (tekst-naar-spraak, nagebootst in de testbrug) en het gesprek met je stem.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; Android._tx.models.base = true; Android._tts.ms = 1500; show('ai'); }); await pg.waitForTimeout(200);
    await pg.fill('#ai-in', 'Hallo'); await pg.click('#ai-send'); await pg.waitForTimeout(900);
    ok(await pg.evaluate(() => Android._tts.said.length === 0), 'getypte vraag: niet vanzelf voorgelezen');
    // Knop onder het antwoord
    ok(await pg.isVisible('#ai-msgs .aispk') && (await pg.textContent('#ai-msgs .aispk')).includes('Voorlezen'), 'knop Voorlezen onder het antwoord');
    await pg.click('#ai-msgs .aispk'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._tts.said.slice(-1)[0] === Android._ai.reply), 'het antwoord gaat naar de spraakmotor (opmaak haalt Java eraf)');
    ok(await pg.isVisible('#ai-spkbar') && (await pg.textContent('#ai-spktxt')) === 'Voorlezen…' && (await pg.getAttribute('#ai-msgs .aispk', 'aria-label')) === 'Stoppen met voorlezen', 'tijdens het voorlezen: balk met Stoppen, knop wordt Stoppen');
    await pg.screenshot({ path: __dirname + '/shots/ai-voorlezen-' + scheme + '.png' });
    await pg.click('#ai-spkbar .mini'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._tts.stops >= 1) && !(await pg.isVisible('#ai-spkbar')) && (await pg.textContent('#ai-msgs .aispk')).includes('Voorlezen'), 'Stoppen: stil, knoppen terug');
    // Vanzelf klaar
    await pg.evaluate(() => { Android._tts.ms = 200; }); await pg.click('#ai-msgs .aispk'); await pg.waitForTimeout(450);
    ok(!(await pg.isVisible('#ai-spkbar')), 'klaar met voorlezen: balk weg');
    // Via ⋯ Meer
    await pg.evaluate(() => { Android._tts.ms = 3000; }); await pg.click('#ai-msgs .aiacts .linkish'); await pg.waitForTimeout(100);
    const acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    ok(acts.includes('Voorlezen'), 'Voorlezen in het menu van een antwoord');
    await pg.evaluate(() => [...document.querySelectorAll('#sheet-acts button')].find(e => e.textContent === 'Voorlezen').click()); await pg.waitForTimeout(100);
    ok(await pg.isVisible('#ai-spkbar'), 'via het menu: voorlezen');
    // Een nieuwe vraag stopt het voorlezen
    const st = await pg.evaluate(() => Android._tts.stops);
    await pg.fill('#ai-in', 'Nog een vraag'); await pg.click('#ai-send'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(s => Android._tts.stops > s, st) && !(await pg.isVisible('#ai-spkbar')), 'nieuwe vraag: voorlezen stopt');
    await pg.waitForTimeout(900);
    // Weg uit de assistent stopt het voorlezen
    await pg.click('#ai-msgs .aimsg:last-child .aispk'); await pg.waitForTimeout(60);
    const st2 = await pg.evaluate(() => Android._tts.stops);
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(80);
    ok(await pg.evaluate(s => Android._tts.stops > s && !aiSpk, st2), 'weg uit de assistent: voorlezen stopt');
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(150);
    // App naar de achtergrond (Android stopt zelf): de interface staat niet meer op voorlezen
    await pg.click('#ai-msgs .aimsg:last-child .aispk'); await pg.waitForTimeout(60);
    await pg.evaluate(() => onPauseApp()); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => !aiSpk) && !(await pg.isVisible('#ai-spkbar')), 'app naar de achtergrond: niet meer voorlezen');
    // Geen Nederlandse stem: duidelijke uitleg
    await pg.evaluate(() => { Android._tts.err = 'Er is geen Nederlandse stem op je telefoon. Voeg er een toe bij Instellingen → Systeem → Talen → Tekst-naar-spraak (bijvoorbeeld bij Spraakservices van Google).'; });
    await pg.click('#ai-msgs .aimsg:last-child .aispk'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#modal.on') && (await pg.textContent('#modal-title')) === 'Voorlezen lukt niet' && (await pg.textContent('#modal-text')).includes('Nederlandse stem'), 'geen Nederlandse stem: uitleg');
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok(!(await pg.isVisible('#ai-spkbar')), 'na de fout: niet blijven hangen');
    await pg.evaluate(() => { Android._tts.err = ''; Android._tts.ms = 400; });

    // Instelling: antwoord voorlezen als je de vraag insprak
    await pg.evaluate(() => show('aisetvoice')); await pg.waitForTimeout(150);
    ok((await pg.getAttribute('#ais-vspeak', 'aria-checked')) === 'false', 'standaard uit');
    await pg.click('#ais-vspeak'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._ai.speakVoice === true) && (await pg.getAttribute('#ais-vspeak', 'aria-checked')) === 'true', 'aan te zetten');
    await pg.evaluate(() => show('aiset')); await pg.waitForTimeout(100);
    ok((await pg.textContent('#ai-sum-voice')).includes('antwoord voorlezen'), 'in het overzicht');
    await pg.evaluate(() => { show('ai'); aiNew(); Android._tts.said = []; Android._voiceResult = { text: 'Hoe laat is het in Tokio?' }; }); await pg.waitForTimeout(150);
    await pg.click('#ai-mic'); await pg.waitForTimeout(200); await pg.click('#ai-mic'); await pg.waitForTimeout(1300);
    ok(await pg.evaluate(() => Android._tts.said.length === 1 && Android._tts.said[0] === Android._ai.reply), 'ingesproken vraag: antwoord vanzelf voorgelezen');
    ok(await pg.evaluate(() => Android._ai.chats[0].msgs[0].v === true), 'de vraag is gemarkeerd als ingesproken');
    await pg.waitForTimeout(500);
    await pg.fill('#ai-in', 'Getypt'); await pg.click('#ai-send'); await pg.waitForTimeout(1200);
    ok(await pg.evaluate(() => Android._tts.said.length === 1), 'getypte vraag: niet vanzelf voorgelezen');
    // Ingesproken, eerst nakijken, dan versturen: ook voorgelezen
    await pg.evaluate(() => { Android._ai.voiceSend = false; aiLoad(); Android._voiceResult = { text: 'Wat eet ik vanavond?' }; });
    await pg.click('#ai-mic'); await pg.waitForTimeout(200); await pg.click('#ai-mic'); await pg.waitForTimeout(300);
    await pg.click('#ai-send'); await pg.waitForTimeout(1200);
    ok(await pg.evaluate(() => Android._tts.said.length === 2), 'ingesproken en nagekeken: ook voorgelezen');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
