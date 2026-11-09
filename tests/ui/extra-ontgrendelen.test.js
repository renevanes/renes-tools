// Extra ontgrendelen per tool (Instellingen → Extra ontgrendelen, 26b-extra-ontgrendelen.js): aan of uit per tool, het
// slotscherm vóór het openen, weer op slot bij weggaan uit de tool of de app, niets zichtbaar in zoeken en op het
// startscherm, uitzetten alleen na bevestigen, losse apps, zonder schermvergrendeling en in het Engels.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  const stand = pg => pg.evaluate(() => ({ cur: current, slot: $('#toolslot').classList.contains('on'), titel: $('#ts-titel').textContent, naam: $('#ts-naam').textContent,
    scherm: Android._tslot.scherm, inert: $('#s-' + current).closest('[inert]') !== null, focus: document.activeElement && document.activeElement.id }));
  for (const scheme of ['light', 'dark']) {
    let pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => { notesGet().push({ id: 'ts1', title: 'Geheim lijstje', text: 'cadeau voor Anna', items: [], updated: Date.now() }); Android.notePinSet('app', 'ts1', true); });

    // 1. Instellen: alle tools in één lijst, Kluis/Bankzaken/Stamgegevens altijd aan
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(100);
    ok(/Uit/.test(await pg.textContent('#st-sum-slot')), 'overzicht Instellingen: stand Uit');
    await pg.click('button.strow:has-text("Extra ontgrendelen")'); await pg.waitForTimeout(150);
    const lijst = await pg.evaluate(() => ({ n: document.querySelectorAll('#st-slot-list .sw').length, verwacht: tsLijst().length,
      tools: Object.keys(TOOLS).filter(k => document.getElementById('s-' + k)).length,
      eigen: [...document.querySelectorAll('#st-slot-list .tg:disabled')].map(b => b.getAttribute('aria-label')), geen: !$('#st-slot-geen').hidden }));
    ok(lijst.n === lijst.verwacht && lijst.n === lijst.tools && lijst.n > 25, 'elke tool met een eigen scherm staat in de lijst (' + lijst.n + ')');
    ok(lijst.eigen.length === 3 && ['Kluis', 'Bankzaken', 'Stamgegevens'].every(n => lijst.eigen.some(l => l.includes(n))), 'Kluis, Bankzaken en Stamgegevens: altijd aan, niet uit te zetten');
    ok(!lijst.geen, 'met schermvergrendeling: geen waarschuwing');
    await pg.screenshot({ path: __dirname + '/shots/extra-ontgrendelen-instellen-' + scheme + '.png' });
    await pg.click('#st-slot-list .tg[aria-label="Extra ontgrendelen voor Notities"]'); await pg.waitForTimeout(150);
    ok((await pg.evaluate(() => Android._tslot.aan)).join() === 'notes' && /extra ontgrendelen aan/.test(await pg.textContent('#toast')), 'aanzetten: meteen, zonder vraag');
    ok(await pg.getAttribute('#st-slot-list .tg[aria-label="Extra ontgrendelen voor Notities"]', 'aria-checked') === 'true', 'schakelaar staat aan');
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(100);
    ok(/Aan voor 1 tool/.test(await pg.textContent('#st-sum-slot')), 'overzicht Instellingen: Aan voor 1 tool');

    // 2. Openen: eerst het slotscherm, de tool zelf nog niet
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    const tegel = await pg.evaluate(() => ({ dicht: $('#tile-notes').classList.contains('ts-dicht'), slotje: !!$('#tile-notes .ts-tegel'), sub: getComputedStyle($('#tile-notes small')).visibility,
      pins: $('#home-notes').style.display, pinTekst: $('#home-notes-tiles').textContent }));
    ok(tegel.dicht && tegel.slotje && tegel.sub === 'hidden', 'tegel: slotje, geen stand eronder');
    ok(tegel.pins === 'none' && !tegel.pinTekst.includes('Geheim'), 'vastgezette notities niet op het startscherm');
    await pg.click('#tile-notes'); await pg.waitForTimeout(150);
    let s = await stand(pg);
    ok(s.slot && s.cur === 'home' && s.titel === 'Notities is vergrendeld' && s.naam === 'Notities', 'tegel Notities: slotscherm, de lijst is nog niet geopend (' + JSON.stringify(s) + ')');
    ok(s.scherm === true && s.focus === 'ts-open', 'niet in recente apps; focus op Ontgrendelen');
    ok(await pg.evaluate(() => !$('#s-notes').classList.contains('on') && !document.querySelector('#s-notes .note-row, #s-notes [data-id="ts1"]')), 'inhoud van Notities niet getekend');
    await pg.screenshot({ path: __dirname + '/shots/extra-ontgrendelen-slot-' + scheme + '.png' });
    const maat = await pg.evaluate(() => { const r = $('#ts-open').getBoundingClientRect(), k = $('#toolslot .back').getBoundingClientRect(); return [r.width, r.height, k.width, k.height]; });
    ok(maat.every(v => v >= 44), 'knoppen minstens 44 px: ' + maat.join(','));
    await pg.keyboard.press('Escape'); await pg.waitForTimeout(100);
    s = await stand(pg);
    ok(!s.slot && s.cur === 'home', 'Escape/terug: slotscherm weg, je blijft waar je was');

    // 3. Ontgrendelen mislukt, dan lukt het
    await pg.evaluate(() => openTool('notes')); await pg.waitForTimeout(100);
    await pg.evaluate(() => { Android._tslot.nee = 'Niet ontgrendeld'; }); await pg.click('#ts-open'); await pg.waitForTimeout(250);
    s = await stand(pg);
    ok(s.slot && s.cur === 'home' && /Niet ontgrendeld/.test(await pg.textContent('#ts-msg')), 'mislukt: blijft op slot, met de melding');
    await pg.click('#ts-open'); await pg.waitForTimeout(250);
    s = await stand(pg);
    ok(!s.slot && s.cur === 'notes' && s.scherm === true, 'ontgrendeld: Notities open (en nog steeds niet in recente apps)');
    ok((await pg.evaluate(() => Android._tslot.vragen)).join() === 'open:notes,open:notes' && await pg.evaluate(() => Android._tslot.naam) === 'Notities', 'de vraag ging naar Android, met de naam van de tool');
    await pg.evaluate(() => openNote('ts1')); await pg.waitForTimeout(150);
    s = await stand(pg);
    ok(!s.slot && s.cur === 'note', 'binnen de tool blijft hij open');

    // 4. App uit beeld: weer op slot, over het scherm heen (niets wordt afgebroken); terug = de tool verlaten
    await pg.evaluate(() => onToolSlotWeg()); await pg.waitForTimeout(100);
    s = await stand(pg);
    ok(s.slot && s.cur === 'note' && s.inert, 'app uit beeld: slotscherm over de notitie, de notitie is niet te bedienen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    s = await stand(pg);
    ok(!s.slot && s.cur === 'home', 'terug op het slotscherm: de tool verlaten (' + s.cur + ')');

    // 5. De tool verlaten zet hem weer op slot
    await pg.evaluate(() => openTool('notes')); await pg.waitForTimeout(80); await pg.click('#ts-open'); await pg.waitForTimeout(250);
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(80);
    await pg.evaluate(() => show('notes')); await pg.waitForTimeout(80);
    s = await stand(pg);
    ok(s.slot && s.cur === 'settings', 'na weggaan uit de tool: opnieuw ontgrendelen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(80);

    // 6. Overal zoeken toont niets uit een tool op slot
    await pg.evaluate(() => { show('search'); $('#gs-q').value = 'Geheim'; gsInput(); }); await pg.waitForTimeout(500);
    ok(!(await pg.textContent('#gs-res')).includes('Geheim lijstje'), 'zoeken: geen notities zolang Notities op slot is');
    await pg.evaluate(() => { Android.toolSlotSet('notes', false, 'Notities'); }); await pg.waitForTimeout(250);
    await pg.evaluate(() => { $('#gs-q').value = 'Geheim'; gsInput(); }); await pg.waitForTimeout(500);
    ok((await pg.textContent('#gs-res')).includes('Geheim lijstje'), 'na uitzetten wel weer (controle van de test)');
    ok((await pg.evaluate(() => Android._tslot.vragen)).includes('uit:notes'), 'uitzetten vroeg de schermvergrendeling');

    // 7. Tegelmenu: aan, en uitzetten dat mislukt
    await pg.evaluate(() => { show('home'); toolMenu('notes'); }); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Extra ontgrendelen aanzetten")'); await pg.waitForTimeout(150);
    ok((await pg.evaluate(() => Android._tslot.aan)).includes('notes'), 'tegelmenu zet extra ontgrendelen aan');
    await pg.evaluate(() => toolMenu('notes')); await pg.waitForTimeout(100);
    await pg.evaluate(() => { Android._tslot.nee = 'Niet bevestigd'; });
    await pg.click('#sheet-acts button:has-text("Extra ontgrendelen uitzetten…")'); await pg.waitForTimeout(250);
    ok((await pg.evaluate(() => Android._tslot.aan)).includes('notes') && /Niet bevestigd/.test(await pg.textContent('#toast')), 'uitzetten zonder bevestiging: blijft aan');
    await pg.evaluate(() => toolMenu('kluis')); await pg.waitForTimeout(100);
    ok(!(await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).some(a => /Extra ontgrendelen/.test(a)), 'Kluis: geen keuze (altijd eigen ontgrendeling)');
    await pg.evaluate(() => closeSheet());

    // 8. Vanuit een andere tool (sprong): na ontgrendelen terug naar waar je vandaan kwam
    await pg.evaluate(() => { Android.toolSlotSet('contacts', true, 'Contacten'); }); await pg.waitForTimeout(100);
    await pg.evaluate(() => { show('calls'); jumpTo('contacts'); }); await pg.waitForTimeout(100);
    s = await stand(pg);
    ok(s.slot && s.cur === 'calls' && s.naam === 'Contacten', 'van Oproepen naar Contacten: eerst het slotscherm');
    await pg.click('#ts-open'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current) === 'contacts', 'ontgrendeld: Contacten');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current) === 'calls', 'terug: weer bij Oproepen');

    // 9. Zonder schermvergrendeling werkt het niet (anders zit je vast): tools open, waarschuwing
    await pg.evaluate(() => { Android._st.secure = false; onResumeApp(); show('home'); }); await pg.waitForTimeout(100);
    await pg.evaluate(() => openTool('notes')); await pg.waitForTimeout(100);
    s = await stand(pg);
    ok(!s.slot && s.cur === 'notes' && s.scherm === false, 'geen schermvergrendeling: Notities opent gewoon');
    await pg.evaluate(() => show('setslot')); await pg.waitForTimeout(100);
    ok(!(await pg.evaluate(() => $('#st-slot-geen').hidden)) && await pg.evaluate(() => $('#st-slot-list .tg[aria-label="Extra ontgrendelen voor Radio"]').disabled), 'waarschuwing, en aanzetten kan dan niet');
    await pg.evaluate(() => { show('settings'); }); await pg.waitForTimeout(80);
    ok(/schermvergrendeling/.test(await pg.textContent('#st-sum-slot')), 'overzicht: werkt pas met een schermvergrendeling');
    await pg.evaluate(() => { Android._st.secure = true; onResumeApp(); }); await pg.waitForTimeout(50);

    // 10. Engels
    await pg.evaluate(() => show('setregio')); await pg.waitForTimeout(100);
    await pg.selectOption('#rg-lang', 'en'); await pg.waitForTimeout(200);
    await pg.evaluate(() => { show('home'); openTool('notes'); }); await pg.waitForTimeout(200);
    s = await stand(pg);
    ok(s.slot && s.titel === 'Notes is locked' && (await pg.textContent('#ts-open')).trim() === 'Unlock', 'Engels: ' + s.titel);
    await pg.screenshot({ path: __dirname + '/shots/extra-ontgrendelen-en-' + scheme + '.png' });
    await pg.evaluate(() => goBack());
    await pg.evaluate(() => show('setslot')); await pg.waitForTimeout(150);
    ok(/Unlock per tool/.test(await pg.textContent('#st-slot-card h2')) && !!(await pg.$('#st-slot-list .tg[aria-label="Extra unlock for Notes"]')), 'Engels: instellingen vertaald');
    await pg.close();

    // 11. Losse app Notities met extra ontgrendelen
    pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.addInitScript(() => { window.__testSolo = 'notes'; window.__testToolSlot = ['notes']; });
    await pg.goto(url); await pg.waitForTimeout(400);
    s = await stand(pg);
    ok(s.slot && s.naam === 'Notities' && !(await pg.evaluate(() => $('#s-notes').classList.contains('on'))), 'losse app: eerst het slotscherm');
    ok(await pg.evaluate(() => getComputedStyle($('#toolslot .back')).display) === 'none', 'losse app: geen terugknop op het slotscherm');
    ok(await pg.evaluate(() => goBack()) === false, 'terug sluit de losse app');
    await pg.click('#ts-open'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current) === 'notes' && !(await stand(pg)).slot, 'losse app ontgrendeld');
    await pg.evaluate(() => onToolSlotWeg()); await pg.waitForTimeout(80);
    ok((await stand(pg)).slot && await pg.evaluate(() => goBack()) === false, 'uit beeld: weer op slot; terug sluit de losse app');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
