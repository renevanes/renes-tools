// Assistent: Gesprek (handsfree). Luistert → denkt → spreekt (zin voor zin, al tijdens het schrijven) → luistert weer;
// stoppen met "stop", de knop, weggaan of een telefoongesprek; onderbreken door te praten of met de knop; een voorstel
// wacht op een tik ("Bevestig op het scherm"); instellingen. Spraak, voorlezen en de microfoon zijn nagebootst (testbrug).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => {
      Android._ai.st.klein = { state: 'ready' }; Android._tx.models.base = true; Android._tkEndMs = 300;
      Android._voiceResult = { text: 'Hoe laat gaan we eten?' }; Android._ai.reply = 'Om zes uur. Dat is over een uur! ';
      // Wat er voorgelezen wordt, en of het antwoord toen nog geschreven werd
      window.__spk = []; const sp = Android.aiSpeak.bind(Android); Android.aiSpeak = (t, id) => { window.__spk.push({ t, id, busy: aiBusy }); sp(t, id); };
      window.__st = []; new MutationObserver(() => { const s = $('#ai-talk').dataset.st; if (window.__st[window.__st.length - 1] !== s) window.__st.push(s); }).observe($('#ai-talk'), { attributes: true });
      show('ai');
    }); await pg.waitForTimeout(200);
    const st = () => pg.evaluate(() => aiTk ? aiTk.st : '');
    const waitSt = async (s, ms) => { for (let t = 0; t < (ms || 4000); t += 50) { if (await st() === s) return true; await pg.waitForTimeout(50); } return false; };

    ok(await pg.isVisible('#ai-talk-btn') && (await pg.getAttribute('#ai-talk-btn', 'aria-label')).includes('Gesprek'), 'knop Gesprek in de kop');
    // Iets anders gebruikt de microfoon: niet starten
    await pg.evaluate(() => { Android._tkConflict = 'Slaappraat luistert nu; stop dat eerst'; });
    await pg.click('#ai-talk-btn'); await pg.waitForTimeout(100);
    ok(!(await pg.isVisible('#ai-talk')) && (await pg.textContent('#toast')).includes('Slaappraat'), 'Slaappraat luistert: geen gesprek');
    await pg.evaluate(() => { Android._tkConflict = ''; });

    // Starten: luisteren, scherm blijft aan, stilte 1,3 s
    await pg.click('#ai-talk-btn'); await pg.waitForTimeout(80);
    ok(await pg.isVisible('#ai-talk') && await st() === 'luistert' && (await pg.textContent('#ai-talk-st')).includes('luister'), 'luistert, groot in beeld');
    ok(await pg.evaluate(() => Android._tkScreen === true && Android._tkCalls[0][1] === 1300), 'scherm blijft aan; stilte 1,3 s');
    ok((await pg.textContent('#ai-talk-go')) === 'Klaar met praten' && await pg.isVisible('#ai-talk-stop'), 'knoppen: Klaar met praten en Stop');
    ok(!(await pg.isVisible('#tabs')) && !(await pg.isVisible('#ai-comp')), 'tabbalk en invoervak weg tijdens het gesprek');
    await pg.screenshot({ path: __dirname + '/shots/ai-gesprek-luistert-' + scheme + '.png' });
    // Uitgesproken (stilte) → denkt → spreekt → luistert weer
    ok(await waitSt('spreekt', 3000), 'na de stilte: denkt, dan spreekt');
    ok(await pg.evaluate(() => Android._ai.last.messages.slice(-1)[0].content === 'Hoe laat gaan we eten?'), 'de vraag ging naar de assistent');
    ok(await pg.evaluate(() => window.__spk[0] && window.__spk[0].t === 'Om zes uur.' && window.__spk[0].busy), 'de eerste zin al voorgelezen terwijl het antwoord nog geschreven werd');
    await pg.screenshot({ path: __dirname + '/shots/ai-gesprek-spreekt-' + scheme + '.png' });
    ok(await waitSt('luistert', 4000), 'daarna luistert hij weer');
    ok(await pg.evaluate(() => window.__spk.map(x => x.t).join(' ').includes('Dat is over een uur!')), 'het hele antwoord voorgelezen');
    const order = await pg.evaluate(() => window.__st.join(','));
    ok(/luistert,denkt,spreekt.*luistert/.test(order), 'standen: luistert → denkt → spreekt → luistert (' + order + ')');
    ok(await pg.evaluate(() => Android._bargeStarts >= 1), 'tijdens het voorlezen: luisteren of je onderbreekt');

    // Onderbreken door te praten (barge-in)
    await pg.evaluate(() => { Android._ai.reply = 'Dit is een heel lang antwoord. Met nog een zin. En nog een. '; Android._tts.ms = 3000; });
    ok(await waitSt('spreekt', 3000), 'tweede vraag: spreekt');
    const stops = await pg.evaluate(() => Android._tts.stops);
    await pg.evaluate(() => { Android._barge.hit = true; }); await pg.waitForTimeout(250);
    ok(await st() === 'luistert' && await pg.evaluate(n => Android._tts.stops > n, stops), 'praten door het voorlezen heen: hij stopt en luistert');
    // Onderbreken met de knop
    ok(await waitSt('spreekt', 3000), 'nog een vraag: spreekt');
    ok((await pg.textContent('#ai-talk-go')) === 'Onderbreken', 'knop Onderbreken tijdens het voorlezen');
    await pg.click('#ai-talk-go'); await pg.waitForTimeout(100);
    ok(await st() === 'luistert', 'Onderbreken: luistert weer');
    // Onderbreken door te praten lukt niet op deze telefoon: alleen de knop, en dat staat erbij
    await pg.evaluate(() => { Android._bargeErr = 'Onderbreken door te praten lukt niet op deze telefoon'; aiTk.noBarge = false; });
    ok(await waitSt('spreekt', 3000) && (await pg.textContent('#ai-talk-hint')).includes('Tik op Onderbreken'), 'zonder onderbreken door te praten: uitleg bij de knop');
    await pg.click('#ai-talk-go'); await pg.waitForTimeout(100);
    await pg.evaluate(() => { Android._bargeErr = ''; Android._tts.ms = 400; Android._ai.reply = 'Prima.'; });

    // Een voorstel: hij zegt "Bevestig op het scherm" en wacht op je tik; daarna verder
    await pg.evaluate(() => { Android._voiceResult = { text: 'Zet een timer van 10 minuten.' }; });
    ok(await waitSt('bevestig', 5000), 'opdracht: wacht op bevestigen');
    ok(await pg.evaluate(() => window.__spk.slice(-1)[0].t.includes('Bevestig op het scherm')), 'zegt: Bevestig op het scherm');
    ok(await pg.evaluate(() => $('#ai-talk').classList.contains('mini') && $('#ai-talk').getAttribute('aria-modal') === 'false') && await pg.isVisible('#ai-msgs .aimsg:last-child .cmdcard'), 'klein onderin; de kaart is te zien');
    ok(await pg.evaluate(() => !(Android._aiDone || []).length), 'nog niets gedaan');
    await pg.screenshot({ path: __dirname + '/shots/ai-gesprek-bevestig-' + scheme + '.png' });
    await pg.evaluate(() => { Android._voiceResult = { text: 'Bedankt.' }; Android._tkEndMs = 2000; });
    await pg.click('#ai-msgs .aimsg:last-child .cmdcard .cmdgo'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._aiDone.length === 1 && Android._aiDone[0].secs === 600) && await st() === 'luistert', 'na de tik: timer gezet, hij luistert weer');

    // Stoppen met "stop"
    await pg.evaluate(() => { Android._voiceResult = { text: 'Stop.' }; Android._tkEnd = 1; }); await pg.waitForTimeout(500);
    ok(!(await pg.isVisible('#ai-talk')) && await pg.evaluate(() => !aiTk && Android._tkScreen === false && !Android._aiRec), '"stop": gesprek klaar, scherm mag weer uit');
    ok(await pg.isVisible('#tabs') || await pg.evaluate(() => document.body.classList.contains('ai-typing')), 'tabbalk terug');
    // Stop-knop
    await pg.evaluate(() => { Android._tkEndMs = 0; Android._tkEnd = 0; });
    await pg.click('#ai-talk-btn'); await pg.waitForTimeout(100);
    await pg.click('#ai-talk-stop'); await pg.waitForTimeout(100);
    ok(!(await pg.isVisible('#ai-talk')) && await pg.evaluate(() => !Android._aiRec && Android._aiVoiceCancelled), 'Stop: luisteren afgebroken (niets uitgeschreven)');
    // Terug-gebaar stopt eerst het gesprek
    await pg.click('#ai-talk-btn'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => goBack() === true && !aiTk && current === 'ai'), 'terug: eerst het gesprek stoppen');
    // Weg uit de assistent
    await pg.click('#ai-talk-btn'); await pg.waitForTimeout(100);
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !aiTk && !Android._aiRec && Android._tkScreen === false), 'weg uit de assistent: gesprek gestopt');
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(150);
    // Telefoongesprek
    await pg.click('#ai-talk-btn'); await pg.waitForTimeout(100);
    await pg.evaluate(() => { Android._tkCall = true; }); await pg.waitForTimeout(700);
    ok(await pg.evaluate(() => !aiTk) && (await pg.textContent('#toast')).includes('telefoongesprek'), 'telefoongesprek: gesprek gestopt');
    await pg.evaluate(() => { Android._tkCall = false; });
    // App naar de achtergrond
    await pg.click('#ai-talk-btn'); await pg.waitForTimeout(100);
    await pg.evaluate(() => onPauseApp()); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => !aiTk), 'app naar de achtergrond: gesprek gestopt');
    await pg.evaluate(() => onResumeApp());
    // Niets gehoord: pauze, met Praten
    await pg.click('#ai-talk-btn'); await pg.waitForTimeout(50);
    await pg.evaluate(() => { Android._tkEnd = 2; }); await pg.waitForTimeout(250);
    ok(await st() === 'pauze' && (await pg.textContent('#ai-talk-go')) === 'Praten' && (await pg.textContent('#ai-talk-hint')).includes('hoorde niets'), 'niets gehoord: gepauzeerd, Praten');
    await pg.evaluate(() => aiTalkStop());

    // Instellingen: stilte, voorlezen, vanzelf weer luisteren, onderbreken (ook in het register)
    await pg.evaluate(() => show('aisetvoice')); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#aic-talkSilence') && await pg.isVisible('#ais-tspeak') && await pg.isVisible('#ais-tauto') && await pg.isVisible('#ais-tbarge'), 'Lokale AI → Spraak: instellingen voor het gesprek');
    await pg.click('#aic-talkSilence button[data-v="1600"]'); await pg.click('#ais-tauto'); await pg.click('#ais-tspeak'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.cfg.talkSilence === '1600' && Android._ai.talkAuto === false && Android._ai.talkSpeak === false), 'stilte 1,6 s, niet vanzelf luisteren, niet voorlezen');
    ok(await pg.evaluate(() => ['ai.talk.stilte', 'ai.talk.voorlezen', 'ai.talk.luisteren', 'ai.talk.onderbreken'].every(id => !!setEntry(id)) && setGet(setEntry('ai.talk.stilte')) === '1600' && setGet(setEntry('ai.talk.voorlezen')) === 'uit'), 'in het instellingenregister');
    await pg.evaluate(() => { show('ai'); window.__spk = []; Android._voiceResult = { text: 'Hoe laat gaan we eten?' }; Android._ai.reply = 'Om zes uur.'; Android._tkEnd = 0; Android._tkEndMs = 300; });
    await pg.click('#ai-talk-btn');
    ok(await waitSt('pauze', 4000) && await pg.evaluate(() => Android._tkCalls.slice(-1)[0][1] === 1600 && !window.__spk.length), 'met die instellingen: 1,6 s stilte, niet voorgelezen, daarna gepauzeerd');
    await pg.click('#ai-talk-go'); await pg.waitForTimeout(100);
    ok(await st() === 'luistert', 'Praten: luistert weer');
    await pg.evaluate(() => aiTalkStop());
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
