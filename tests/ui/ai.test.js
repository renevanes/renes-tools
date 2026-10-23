// Assistent (lokale AI): model downloaden, vragen en antwoorden, stoppen, met mijn notities, bewaren, eerdere gesprekken,
// instellingen en slim zoeken in Zoeken. De AI zelf is nagebootst (testbrug).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    ok(await pg.isVisible('#tile-ai') && (await pg.textContent('#tile-ai b')) === 'Assistent', 'tegel Assistent op het startscherm');
    await pg.click('#tile-ai'); await pg.waitForTimeout(300);
    // Nog geen model: kiezen en downloaden
    ok(await pg.isVisible('#ai-setup') && !(await pg.isVisible('#ai-chat')), 'zonder model: uitleg en modelkeuze');
    ok((await pg.$$('#ai-setup-models .aimodel')).length === 2, 'twee taalmodellen om te kiezen');
    await pg.click('.aimodel[data-id="klein"] .mini'); await pg.waitForTimeout(300);
    ok(/↓ \d+%|Controleren/.test(await pg.textContent('.aimodel[data-id="klein"] .aims')), 'voortgang van het downloaden');
    await pg.waitForTimeout(1800);
    ok(await pg.isVisible('#ai-chat') && !(await pg.isVisible('#ai-setup')), 'model klaar: het gesprek');
    ok(!(await pg.isVisible('#ai-notes')), 'zonder zoekmodel: geen "met mijn notities"');
    ok(await pg.isDisabled('#ai-send'), 'versturen pas met tekst');
    // Een vraag
    await pg.click('#ai-sugg .chip:has-text("Vertaal")'); await pg.waitForTimeout(100);
    ok((await pg.inputValue('#ai-in')).startsWith('Vertaal naar het Engels'), 'suggestie vult het invoerveld');
    await pg.fill('#ai-in', 'Wie ben jij?'); await pg.evaluate(() => aiGrow());
    await pg.click('#ai-send'); await pg.waitForTimeout(300);
    ok((await pg.getAttribute('#ai-send', 'aria-label')) === 'Stoppen', 'tijdens het antwoord: stopknop');
    await pg.waitForTimeout(900);
    ok((await pg.textContent('#ai-msgs .aimsg:not(.me) .aib')).includes('Hallo! Ik ben je assistent.') && await pg.$('#ai-msgs .aimsg:not(.me) .aib b') && (await pg.$$('#ai-msgs .aimsg:not(.me) li')).length === 2, 'antwoord in stukjes, met opmaak');
    ok((await pg.getAttribute('#ai-send', 'aria-label')) === 'Versturen', 'klaar: weer versturen');
    ok(await pg.evaluate(() => Android._ai.last.messages.length === 1 && Android._ai.last.messages[0].content === 'Wie ben jij?' && !Android._ai.last.notes), 'de vraag gaat naar de assistent');
    // Stoppen halverwege
    await pg.fill('#ai-in', 'Nog een vraag'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(300);
    await pg.click('#ai-send'); await pg.waitForTimeout(200);
    ok((await pg.textContent('#ai-msgs .aimsg:last-child')).includes('Gestopt'), 'stoppen: het antwoord tot dan blijft, met "Gestopt"');
    ok(await pg.evaluate(() => Android._ai.last.messages.length === 3), 'vervolgvraag: het gesprek gaat mee');
    // Antwoord bewaren als notitie, kopiëren
    await pg.click('#ai-msgs .aimsg:nth-child(2) .aiacts .linkish'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Kopiëren")');
    ok(await pg.evaluate(() => Android._copied && Android._copied.includes('assistent')), 'kopiëren');
    const n0 = await pg.evaluate(() => notesGet().length);
    await pg.click('#ai-msgs .aimsg:nth-child(2) .aiacts .linkish'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Als notitie bewaren")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(n => notesGet().length === n + 1 && notesGet()[notesGet().length - 1].text.includes('assistent'), n0), 'antwoord als notitie bewaard');
    await pg.screenshot({ path: __dirname + '/shots/ai-' + scheme + '.png' });
    // Bewaard gesprek terugvinden
    await pg.click('#ai-menu'); await pg.click('#sheet-acts button:has-text("Eerdere gesprekken")'); await pg.waitForTimeout(200);
    ok((await pg.$$('#aic-list .pcep')).length === 1 && (await pg.textContent('#aic-list')).includes('Wie ben jij?'), 'eerdere gesprekken: het gesprek staat er');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    await pg.click('#ai-new'); await pg.waitForTimeout(100);
    ok((await pg.$$('#ai-msgs .aimsg')).length === 0 && await pg.isVisible('#ai-empty'), 'nieuw gesprek: leeg');
    await pg.click('#ai-menu'); await pg.click('#sheet-acts button:has-text("Eerdere gesprekken")'); await pg.waitForTimeout(150);
    await pg.click('#aic-list .pcep .pcepb'); await pg.waitForTimeout(200);
    ok((await pg.$$('#ai-msgs .aimsg')).length === 4, 'eerder gesprek weer open');
    // Nieuw gesprek tijdens een antwoord: het oude gesprek wordt (tot dan) bewaard, de nieuwe blijft leeg
    await pg.fill('#ai-in', 'Lange vraag'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(320);
    await pg.click('#ai-new'); await pg.waitForTimeout(300);
    ok((await pg.$$('#ai-msgs .aimsg')).length === 0 && (await pg.getAttribute('#ai-send', 'aria-label')) === 'Versturen', 'nieuw gesprek tijdens een antwoord: leeg en weer klaar');
    ok(await pg.evaluate(() => { const c = Android._ai.chats[0]; const l = c.msgs[c.msgs.length - 1]; return c.msgs.length === 6 && l.r === 'ai' && l.stopped; }), 'het onderbroken antwoord is bewaard in het oude gesprek');
    await pg.click('#ai-menu'); await pg.click('#sheet-acts button:has-text("Eerdere gesprekken")'); await pg.waitForTimeout(150);
    await pg.click('#aic-list .pcep .pcepb'); await pg.waitForTimeout(200);
    // Instellingen: zoekmodel downloaden, index, ander model
    await pg.click('#ai-menu'); await pg.click('#sheet-acts button:has-text("Instellingen")'); await pg.waitForTimeout(200);
    ok((await pg.textContent('#ais-chat .aimodel.on')).includes('In gebruik'), 'instellingen: het model dat in gebruik is');
    await pg.click('#ais-embed .aimodel .mini'); await pg.waitForTimeout(1200);
    ok((await pg.textContent('#ais-embed')).includes('Klaar') && (await pg.textContent('#ais-index')).includes('3 notities'), 'zoekmodel klaar, met de zoekindex');
    await pg.click('#ais-index button.mini'); await pg.waitForTimeout(80);
    ok((await pg.textContent('#ais-index')).includes('Bijwerken…'), 'index bijwerken: voortgang');
    await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => Android._ai.calls.some(c => c[0] === 'index')), 'index bijgewerkt');
    await pg.click('#ais-wifi');
    ok(await pg.evaluate(() => Android._ai.wifiOnly === false) && (await pg.getAttribute('#ais-wifi', 'aria-checked')) === 'false', 'alleen via wifi uit te zetten');
    await pg.screenshot({ path: __dirname + '/shots/ai-set-' + scheme + '.png', fullPage: true });
    // Met mijn notities
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#ai-notes') && (await pg.getAttribute('#ai-notes', 'aria-checked')) === 'true', 'met zoekmodel: "met mijn notities" (aan)');
    await pg.fill('#ai-in', 'Wanneer moet de auto naar de garage?'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1300);
    ok(await pg.evaluate(() => Android._ai.last.notes === true) && (await pg.textContent('#ai-msgs .aimsg:last-child .aisrc')).includes('[1]'), 'met mijn notities: bron bij het antwoord');
    await pg.click('#ai-notes');
    ok(await pg.evaluate(() => Android._ai.notes === false), 'met mijn notities uit te zetten');
    // Dubbele tik op versturen: één vraag, één antwoord
    const before = await pg.evaluate(() => { Android._ai.asked = 0; Android._ai.refused = 0; return aiChat.msgs.length; });
    await pg.fill('#ai-in', 'Dubbel getikt'); await pg.evaluate(() => aiGrow());
    await pg.dblclick('#ai-send'); await pg.waitForTimeout(80);
    ok(await pg.isDisabled('#ai-send'), 'vlak na versturen: knop even uit');
    await pg.evaluate(() => { aiSendOrStop(); $('#ai-send').click(); }); // nog meer tikken, ook langs de knop heen
    ok((await pg.getAttribute('#ai-send', 'aria-label')) === 'Stoppen' && await pg.evaluate(() => Android._ai.timer !== null), 'dubbele tik stopt het antwoord niet meteen weer');
    await pg.waitForTimeout(1100);
    ok(await pg.evaluate(b => Android._ai.asked === 1 && aiChat.msgs.length === b + 2 && aiChat.msgs.filter(m => m.c === 'Dubbel getikt').length === 1, before), 'dubbele tik: één vraag en één antwoord');
    // Weigert de app een vraag omdat er nog een antwoord loopt: geen tweede antwoord en de vraag blijft in het invoerveld
    const before2 = await pg.evaluate(() => { Android._ai.busy = true; return aiChat.msgs.length; });
    await pg.fill('#ai-in', 'Nog bezig?'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(b => Android._ai.refused === 1 && aiChat.msgs.length === b && !aiBusy, before2) && (await pg.inputValue('#ai-in')) === 'Nog bezig?' && (await pg.textContent('#toast')).includes('nog bezig'), 'app nog bezig: vraag geweigerd, blijft staan, met melding');
    await pg.evaluate(() => { Android._ai.busy = false; $('#ai-in').value = ''; aiGrow(); });
    // Slim zoeken in Zoeken
    await pg.evaluate(() => { show('search'); $('#gs-q').value = 'garage'; gsInput(); }); await pg.waitForTimeout(1200);
    ok(await pg.isVisible('#gs-res .aigs') && (await pg.textContent('#gs-res .aigs')).includes('Auto naar de garage'), 'Zoeken: groep "Op betekenis"');
    ok(await pg.evaluate(() => $('#gs-res .gsg').classList.contains('aigs')), 'op betekenis bovenaan');
    await pg.click('#gs-res .aigs .gi:first-of-type'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'note'), 'treffer opent de notitie');
    // Model weg: terug naar de keuze
    await pg.evaluate(() => { Android.aiDelete('klein'); show('ai'); }); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#ai-setup'), 'model verwijderd: weer de keuze');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
