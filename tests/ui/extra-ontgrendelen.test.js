// Extra ontgrendelen per tool (Instellingen → Extra ontgrendelen, 26b-extra-ontgrendelen.js): aan of uit per tool, het
// slotscherm vóór het openen, weer op slot bij weggaan uit de tool of de app, niets zichtbaar in zoeken en op het
// startscherm, uitzetten alleen na bevestigen, losse apps, zonder schermvergrendeling, in het Engels, en de eigen
// ontgrendeling van Kluis, Bankzaken en Stamgegevens aan of uit (uit = openen zonder vraag, of toch vragen als het moet).
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

    // 1. Instellen: alle tools in één lijst; Kluis/Bankzaken/Stamgegevens (eigen ontgrendeling) standaard aan, ook uit te zetten
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(100);
    ok(/Aan voor 3 tools/.test(await pg.textContent('#st-sum-slot')), 'overzicht Instellingen: standaard aan voor Kluis, Bankzaken en Stamgegevens');
    await pg.click('button.strow:has-text("Extra ontgrendelen")'); await pg.waitForTimeout(150);
    const lijst = await pg.evaluate(() => ({ n: document.querySelectorAll('#st-slot-list .sw').length, verwacht: tsLijst().length,
      tools: Object.keys(TOOLS).filter(k => document.getElementById('s-' + k)).length, uit: document.querySelectorAll('#st-slot-list .tg:disabled').length,
      eigen: ['kluis', 'bank', 'masterdata'].map(k => { const b = $('#st-slot-list .tg[data-tool="' + k + '"]'); return b && b.getAttribute('aria-checked') === 'true' && !!b.closest('.sw').querySelector('small'); }),
      geen: !$('#st-slot-geen').hidden }));
    ok(lijst.n === lijst.verwacht && lijst.n === lijst.tools && lijst.n > 25, 'elke tool met een eigen scherm staat in de lijst (' + lijst.n + ')');
    ok(!lijst.uit && lijst.eigen.every(Boolean), 'Kluis, Bankzaken en Stamgegevens: aan, met uitleg wat uit betekent, en uit te zetten (' + JSON.stringify(lijst.eigen) + ')');
    ok(!lijst.geen, 'met schermvergrendeling: geen waarschuwing');
    await pg.screenshot({ path: __dirname + '/shots/extra-ontgrendelen-instellen-' + scheme + '.png' });
    await pg.click('#st-slot-list .tg[aria-label="Extra ontgrendelen voor Notities"]'); await pg.waitForTimeout(150);
    ok((await pg.evaluate(() => Android._tslot.aan.slice().sort())).join() === 'bank,kluis,masterdata,notes' && /extra ontgrendelen aan/.test(await pg.textContent('#toast')), 'aanzetten: meteen, zonder vraag');
    ok(await pg.getAttribute('#st-slot-list .tg[aria-label="Extra ontgrendelen voor Notities"]', 'aria-checked') === 'true', 'schakelaar staat aan');
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(100);
    ok(/Aan voor 4 tools/.test(await pg.textContent('#st-sum-slot')), 'overzicht Instellingen: Aan voor 4 tools');

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
    ok((await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).some(a => /Extra ontgrendelen uitzetten…/.test(a)), 'Kluis: ook in het tegelmenu uit te zetten');
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

    // 8b. Wat bij het openen hoort (een vraag uit een melding) wacht op ontgrendelen; terug sluit wat eronder openging
    await pg.evaluate(() => { Android.toolSlotSet('facturen', true, 'Facturen'); show('home'); }); await pg.waitForTimeout(100);
    await pg.evaluate(() => openTool('facturen-meekijken:x')); await pg.waitForTimeout(550);
    s = await stand(pg);
    ok(s.slot && s.cur === 'home' && !(await pg.evaluate(() => $('#modal').classList.contains('on'))), 'melding Facturen op slot: geen vraag onder het slotscherm');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(550);
    ok(!(await stand(pg)).slot && !(await pg.evaluate(() => $('#modal').classList.contains('on'))), 'terug: ook daarna geen vraag (Meekijken kan niet zonder ontgrendelen)');
    await pg.evaluate(() => openTool('facturen-meekijken:x')); await pg.waitForTimeout(100);
    await pg.click('#ts-open'); await pg.waitForTimeout(700);
    ok(await pg.evaluate(() => current === 'facturen' && $('#modal').classList.contains('on') && /Inloggen nodig/.test($('#modal-title').textContent)), 'ontgrendeld: Facturen, en dan de vraag');
    await pg.evaluate(() => closeModal(null));
    // een venster dat open stond toen de app uit beeld ging, gaat dicht als je de tool verlaat
    await pg.evaluate(() => { onToolSlotWeg(); }); await pg.waitForTimeout(50);
    await pg.evaluate(() => { show('home'); openTool('notes'); }); await pg.waitForTimeout(80); await pg.click('#ts-open'); await pg.waitForTimeout(250);
    await pg.evaluate(() => askConfirm('Notitie verwijderen?', 'Geheim lijstje', 'Verwijderen', () => { window.__weg = true; })); await pg.waitForTimeout(100);
    await pg.evaluate(() => onToolSlotWeg()); await pg.waitForTimeout(80);
    s = await stand(pg);
    ok(s.slot && s.cur === 'notes', 'uit beeld met een vraag open: slotscherm erover');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'home' && !$('#modal').classList.contains('on') && !window.__weg), 'terug: de tool verlaten en de vraag is dicht (niets gedaan)');
    // een vraag om te ontgrendelen binnen de open tool houdt hem open; een vraag voor een andere tool niet
    await pg.evaluate(() => openTool('notes')); await pg.waitForTimeout(80); await pg.click('#ts-open'); await pg.waitForTimeout(250);
    await pg.evaluate(() => onToolSlotWeg('vraag')); await pg.waitForTimeout(80);
    s = await stand(pg);
    ok(!s.slot && s.cur === 'notes', 'vraag binnen de tool (app even uit beeld): blijft open');
    await pg.evaluate(() => { show('contacts'); }); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => current === 'notes' && ts.doel === 'contacts'), 'van Notities naar Contacten: slotscherm van Contacten');
    await pg.evaluate(() => onToolSlotWeg('vraag')); await pg.waitForTimeout(80);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    s = await stand(pg);
    ok(s.slot && s.cur === 'notes' && s.naam === 'Notities', 'uit beeld tijdens de vraag voor Contacten: Notities ging ook op slot');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    // een vraag voor de ene tool houdt de knop van de andere niet uit
    await pg.evaluate(() => { show('home'); openTool('notes'); }); await pg.waitForTimeout(80);
    await pg.evaluate(() => { tsOntgrendel(); openTool('contacts'); }); await pg.waitForTimeout(300);
    s = await stand(pg);
    ok(s.slot && s.naam === 'Contacten' && !(await pg.evaluate(() => $('#ts-open').disabled)), 'ander slotscherm tijdens een vraag: knop Ontgrendelen werkt (' + JSON.stringify(s) + ')');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(80);
    // de volledige speler opent niet over een tool op slot heen
    await pg.evaluate(() => { Android.toolSlotSet('radio', true, 'Radio'); window.__plModel = plModel; window.plModel = () => ({ src: 'radio', title: 'Zender' }); show('home'); }); await pg.waitForTimeout(100);
    await pg.evaluate(() => plOpen()); await pg.waitForTimeout(100);
    s = await stand(pg);
    ok(s.slot && s.naam === 'Radio' && !(await pg.evaluate(() => plIsOpen)), 'speler openen met Radio op slot: eerst het slotscherm');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(80);
    await pg.evaluate(() => { window.plModel = window.__plModel; });
    ok(!(await stand(pg)).slot && !(await pg.evaluate(() => plIsOpen)), 'terug: geen speler');
    // de speler stond open toen de app uit beeld ging: terug verlaat de tool en daarna is alles weer te bedienen
    await pg.evaluate(() => openTool('radio')); await pg.waitForTimeout(100); await pg.click('#ts-open'); await pg.waitForTimeout(400);
    await pg.click('#rd-list .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(1300);
    await pg.click('#plbar-open'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => plIsOpen), 'Radio ontgrendeld: de speler gaat open');
    await pg.evaluate(() => onToolSlotWeg()); await pg.waitForTimeout(100);
    s = await stand(pg);
    ok(s.slot && s.naam === 'Radio', 'uit beeld met de speler open: slotscherm erover');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    const vrij = await pg.evaluate(() => ({ cur: current, pl: plIsOpen, inert: [...document.querySelectorAll('.screen, #plbar')].filter(e => e.inert).map(e => e.id) }));
    ok(vrij.cur === 'home' && !vrij.pl && !vrij.inert.length, 'terug: speler dicht, niets blijft onbedienbaar (' + JSON.stringify(vrij) + ')');
    await pg.evaluate(() => { Android.radioStop && Android.radioStop(); Android.toolSlotSet('radio', false, 'Radio'); }); await pg.waitForTimeout(250);
    // ligt het slotscherm over de tool zelf (app was uit beeld), dan wacht een vraag uit een melding ook
    await pg.evaluate(() => openTool('facturen')); await pg.waitForTimeout(80); await pg.click('#ts-open'); await pg.waitForTimeout(250);
    await pg.evaluate(() => { onToolSlotWeg(); openTool('facturen-meekijken:x'); }); await pg.waitForTimeout(550);
    ok(await pg.evaluate(() => $('#toolslot').classList.contains('on') && !$('#modal').classList.contains('on')), 'slotscherm over Facturen: de vraag wacht');
    await pg.click('#ts-open'); await pg.waitForTimeout(700);
    ok(await pg.evaluate(() => current === 'facturen' && $('#modal').classList.contains('on')), 'ontgrendeld: dan de vraag');
    await pg.evaluate(() => { closeModal(null); show('home'); }); await pg.waitForTimeout(80);

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

    // 11b. Losse app Alles back-uppen en Automatiseringen: met het app-slot aan altijd eerst ontgrendelen, zonder app-slot niet
    for (const los of ['backup', 'auto']) {
      pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
      pg.on('pageerror', e => errs.push(e.message));
      await pg.addInitScript(l => { window.__testSolo = l; window.__testAppSlot = true; }, los);
      await pg.goto(url); await pg.waitForTimeout(400);
      ok((await stand(pg)).slot && !(await pg.evaluate(l => $('#s-' + l).classList.contains('on'), los)), 'losse app ' + los + ' met app-slot: eerst het slotscherm');
      await pg.click('#ts-open'); await pg.waitForTimeout(250);
      ok(await pg.evaluate(() => current) === los && !(await stand(pg)).slot, 'losse app ' + los + ': open na ontgrendelen');
      await pg.evaluate(() => onToolSlotWeg()); await pg.waitForTimeout(80);
      ok((await stand(pg)).slot, 'losse app ' + los + ': uit beeld = weer op slot');
      await pg.close();
      pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
      pg.on('pageerror', e => errs.push(e.message));
      await pg.addInitScript(l => { window.__testSolo = l; }, los);
      await pg.goto(url); await pg.waitForTimeout(400);
      ok(!(await stand(pg)).slot && await pg.evaluate(() => current) === los, 'losse app ' + los + ' zonder app-slot: opent meteen');
      await pg.close();
    }

    // 12. Kluis, Bankzaken en Stamgegevens: één centraal slotscherm (geen eigen ontgrendelscherm of eigen vraag);
    // extra ontgrendelen uit = openen zonder vraag, zonder dat er even iets van een slot te zien is
    pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => !document.querySelector('[onclick^="cmUnlock"],[onclick^="klUnlock"],[onclick^="bkOntgrendel"],#kl-locked,#cm-locked,#bk-open-knop')), 'geen eigen ontgrendelknoppen of slotkaarten meer');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    let tegels = await pg.evaluate(() => ['kluis', 'bank', 'notes'].map(k => { const el = [...document.querySelectorAll('.tile[id^="tile-"]')].find(e => TILE_TOOL[e.id] === k); return el ? { k, dicht: el.classList.contains('ts-dicht'), slotje: !!el.querySelector('.ts-tegel') } : { k }; }));
    const kt = tegels.find(x => x.k === 'kluis');
    ok(kt && kt.dicht && kt.slotje, 'tegel Kluis: op slot, net als elke tool met extra ontgrendelen (' + JSON.stringify(tegels) + ')');
    // Aan: eerst het centrale slotscherm; daarna open met die ontgrendeling (één keer)
    await pg.evaluate(() => { show('kluis'); }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => tsZichtbaar() && ts.tool === 'kluis' && current === 'home' && !Android._kl.open), 'aan: eerst het centrale slotscherm, Kluis blijft dicht');
    await pg.click('#ts-open'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'kluis' && Android._kl.open && Android._kl.vragen === 1 && !tsZichtbaar()) && await pg.isVisible('#kl-open') && await pg.isVisible('#kl-lock'), 'na het slotscherm: Kluis open, met Op slot doen');
    // Op slot doen in de kluis: het centrale slotscherm over de kluis; terug = de kluis verlaten
    await pg.click('#kl-lock'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current === 'kluis' && tsZichtbaar() && ts.tool === 'kluis' && !Android._kl.open) && !(await pg.isVisible('#kl-open')), 'op slot gedaan: het slotscherm over de kluis');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current !== 'kluis' && !tsZichtbaar()), 'terug op het slotscherm: weg uit de kluis');
    // Uitzetten vraagt de schermvergrendeling
    await pg.evaluate(() => show('setslot')); await pg.waitForTimeout(100);
    await pg.click('#st-slot-list .tg[data-tool="kluis"]'); await pg.waitForTimeout(250);
    ok((await pg.evaluate(() => Android._tslot.vragen)).includes('uit:kluis') && !(await pg.evaluate(() => Android._tslot.aan)).includes('kluis'), 'Kluis uitzetten vroeg de schermvergrendeling');
    ok(await pg.getAttribute('#st-slot-list .tg[data-tool="kluis"]', 'aria-checked') === 'false', 'schakelaar Kluis staat uit');
    // Uit: meteen open, in dezelfde beurt (geen flits van iets op slot), zonder vraag en zonder Op slot doen
    const kluisUit = await pg.evaluate(() => { Android._kl.open = false; Android._kl.vragen = 0; show('kluis'); return { slot: tsZichtbaar(), open: $('#kl-open').style.display }; });
    ok(!kluisUit.slot && kluisUit.open === 'block', 'uit: Kluis meteen open, zonder slotscherm (' + JSON.stringify(kluisUit) + ')');
    await pg.waitForTimeout(100);
    ok(await pg.isVisible('#kl-open') && !(await pg.isVisible('#kl-lock')) && await pg.evaluate(() => !Android._kl.vragen && !tsZichtbaar()), 'uit: geen vraag en geen Op slot doen');
    await pg.evaluate(() => { Android.kluisClose(); show('home'); $('#lockscr').classList.add('on'); show('kluis'); }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !Android._kl.open && $('#kl-open').style.display === 'none'), 'onder het app-slot: Kluis blijft dicht');
    await pg.evaluate(() => { $('#lockscr').classList.remove('on'); onUnlocked(); }); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#kl-open') && await pg.evaluate(() => !Android._kl.vragen), 'app-slot open: Kluis vanzelf open, zonder vraag');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    tegels = await pg.evaluate(() => { const el = [...document.querySelectorAll('.tile[id^="tile-"]')].find(e => TILE_TOOL[e.id] === 'kluis'); return el && { dicht: el.classList.contains('ts-dicht'), slotje: !!el.querySelector('.ts-tegel') }; });
    ok(tegels && !tegels.dicht && !tegels.slotje, 'tegel Kluis: geen slotje meer');
    // Bankzaken uit: opent zonder vraag; te lang geleden ontgrendeld = toch het centrale slotscherm (over Bankzaken)
    await pg.evaluate(() => { Android._bank.data = Android._bankVoorbeeld(); Android._bank.lang = true; });
    await pg.evaluate(() => show('setslot')); await pg.waitForTimeout(100);
    await pg.click('#st-slot-list .tg[data-tool="bank"]'); await pg.waitForTimeout(250);
    const bankUit = await pg.evaluate(() => { show('bank'); return { slot: tsZichtbaar(), dicht: !$('#bk-dicht').hidden }; });
    ok(!bankUit.slot && !bankUit.dicht, 'uit: geen slotscherm en geen eigen slotkaart bij het openen (' + JSON.stringify(bankUit) + ')');
    await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'bank' && tsZichtbaar() && ts.tool === 'bank' && /al even ontgrendeld/.test($('#ts-msg').textContent) && !Android._bank.open), 'Bankzaken uit, telefoon te lang geleden ontgrendeld: toch het slotscherm (' + await pg.textContent('#ts-msg') + ')');
    await pg.evaluate(() => { Android._bank.lang = false; }); await pg.click('#ts-open'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => Android._bank.open && !tsZichtbaar() && Android._bank.calls.filter(c => c[0] === 'unlock').length === 1) && await pg.isVisible('#bk-los') && !(await pg.isVisible('#bk-sluit')), 'na het slotscherm open; met extra ontgrendelen uit geen Nu op slot doen');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    await pg.evaluate(() => show('bank')); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => Android._bank.open && !tsZichtbaar() && Android._bank.calls.filter(c => c[0] === 'unlock').length === 1 && Android._bank.calls.some(c => c[0] === 'vanzelf')) && await pg.isVisible('#bk-los'), 'Bankzaken uit: opent zonder vraag');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(150);
    // Stamgegevens uit: opent zonder vraag (ook na de app uit beeld); te lang geleden = toch het slotscherm
    await pg.evaluate(() => show('setslot')); await pg.waitForTimeout(100);
    await pg.click('#st-slot-list .tg[data-tool="masterdata"]'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => tsStand()) === 'Uit', 'alle drie uit: stand Uit');
    const stamUit = await pg.evaluate(() => { show('masterdata'); return { slot: tsZichtbaar(), knop: [...document.querySelectorAll('#s-masterdata button')].some(b => b.getClientRects().length && /Ontgrendel/.test(b.textContent)) }; });
    ok(!stamUit.slot && !stamUit.knop, 'uit: geen slotscherm en geen eigen ontgrendelknop bij het openen (' + JSON.stringify(stamUit) + ')');
    await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => cmOpen() && Android._cm.vanzelf === 1 && !Android._cm.vragen) && !(await pg.isVisible('#cm-lock-btn')), 'Stamgegevens uit: opent zonder vraag, zonder vergrendelknop');
    await pg.evaluate(() => { Android._cm.lang = true; Android.masterLock(); }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !cmOpen() && current === 'masterdata' && tsZichtbaar() && ts.tool === 'masterdata' && /al even ontgrendeld/.test($('#ts-msg').textContent)), 'op slot, te lang geleden ontgrendeld: het slotscherm met uitleg');
    await pg.evaluate(() => { Android._cm.lang = false; onResumeApp(); }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !cmOpen() && tsZichtbaar() && Android._cm.vanzelf === 1), 'onder het slotscherm niet vanzelf');
    await pg.click('#ts-open'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => cmOpen() && !tsZichtbaar() && Android._cm.vragen === 1 && Android._cm.vanzelf === 1), 'na het slotscherm open (met die ontgrendeling)');
    await pg.evaluate(() => { Android.masterLock(); }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => cmOpen() && Android._cm.vanzelf === 2), 'op slot gegaan, nog net ontgrendeld: weer vanzelf open');
    // Onder het slotscherm van een andere tool en onder het app-slot: niet vanzelf
    await pg.evaluate(() => Android.toolSlotSet('notes', true, 'Notities')); await pg.waitForTimeout(100);
    await pg.evaluate(() => { Android.masterLock(); show('notes'); }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'masterdata' && tsZichtbaar() && ts.tool === 'notes'), 'naar Notities: slotscherm over Stamgegevens');
    await pg.evaluate(() => onResumeApp()); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !cmOpen() && Android._cm.vanzelf === 2), 'onder het slotscherm van Notities: Stamgegevens blijft dicht');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'masterdata' && !tsZichtbaar() && cmOpen() && Android._cm.vanzelf === 3), 'toch niet naar Notities: Stamgegevens weer vanzelf open');
    await pg.evaluate(() => { $('#lockscr').classList.add('on'); Android.masterLock(); onResumeApp(); }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'masterdata' && !cmOpen() && Android._cm.vanzelf === 3), 'onder het app-slot: Stamgegevens blijft dicht');
    await pg.evaluate(() => { $('#lockscr').classList.remove('on'); onUnlocked(); }); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => cmOpen() && Android._cm.vanzelf === 4 && Android._cm.vragen === 1), 'app-slot open: Stamgegevens vanzelf open');
    // Weer aan via het tegelmenu: dan vraagt het slotscherm het weer
    await pg.evaluate(() => { show('home'); toolMenu('kluis'); }); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Extra ontgrendelen aanzetten")'); await pg.waitForTimeout(150);
    await pg.evaluate(() => { Android._kl.open = false; show('kluis'); }); await pg.waitForTimeout(150);
    ok((await pg.evaluate(() => Android._tslot.aan)).includes('kluis') && await pg.evaluate(() => tsZichtbaar() && ts.tool === 'kluis' && !Android._kl.open), 'weer aan: Kluis vraagt weer om te ontgrendelen (het slotscherm)');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
