// Agenda synchroniseren met Google: welke kant op, welke agenda, hoe vaak, bereik, verwijderen, conflicten, veiligheidsrem en logboek.
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const browser = await chromium.launch(), errors = [];
  for (const colorScheme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme, timezoneId: 'Europe/Amsterdam' });
    page.on('pageerror', e => errors.push(colorScheme + ': ' + e.message));
    const ok = (v, m) => { console.log((v ? '✓ ' : '✗ ') + m); if (!v) errors.push(colorScheme + ': ' + m); };
    await page.goto('file://' + path.join(__dirname, 'ui/index.html'));
    await page.waitForTimeout(250);
    // toestemming voor de agenda van de telefoon
    await page.evaluate(() => { const a = Android._agInit(); a.perm = true; a.schrijf = true; });

    await page.evaluate(() => show('agenda'));
    await page.waitForTimeout(100);
    ok(await page.evaluate(() => $('#ag-syncregel').hidden), 'uit: geen regel over synchroniseren op Agenda');
    await page.evaluate(() => show('agaset'));
    await page.waitForTimeout(80);
    ok((await page.textContent('#ag-sum-sync')).trim() === 'Uit', 'Instellingen van Agenda: rij Synchroniseren met Google staat op Uit');
    await page.click('#ag-row-sync');
    await page.waitForTimeout(100);
    ok(await page.evaluate(() => current === 'agsync' && BACK.agsync === 'agaset'), 'rij opent Synchroniseren, terug gaat naar Instellingen');
    ok((await page.textContent('#ags-stand-t')).includes('staat uit') && await page.isHidden('#ags-nu'), 'stand: uit, geen knop Nu synchroniseren');
    ok((await page.textContent('#s-agsync .hint')).includes('praat niet met Google'), 'zichtbaar dat het via de agenda van de telefoon gaat');

    // aanzetten Tools → Google: eerst zelf de agenda kiezen, dan de vraag over bestaande afspraken
    await page.click('#ags-richting [data-v="naar"]');
    await page.waitForTimeout(80);
    ok(await page.evaluate(() => Android._agsInit().cfg.richting === 'naar' && !Android._agsInit().cfg.kal && !$('#modal').classList.contains('on') && Android._agsInit().log.length === 0),
      'zonder gekozen agenda: geen vraag en geen ronde, Tools kiest niet zelf een agenda');
    ok(!(await page.isHidden('#ags-kal-t')) && (await page.textContent('#ags-kal-t')).includes('Kies de agenda'), 'vraagt om de agenda te kiezen');
    const opties = await page.evaluate(() => [...$('#ags-kal').options].map(o => o.textContent));
    ok(opties.some(o => o.includes('Feestdagen') && o.includes('Alleen lezen')), 'alleen-lezen agenda staat erbij met (Alleen lezen)');
    await page.selectOption('#ags-kal', '1');
    await page.waitForTimeout(80);
    const vraag = await page.evaluate(() => ({ open: $('#modal').classList.contains('on'), titel: $('#modal-title').textContent, tekst: $('#modal-text').textContent }));
    ok(vraag.open && vraag.titel.includes('Bestaande afspraken') && vraag.tekst.includes('Agenda'), 'aanzetten naar Google vraagt of bestaande afspraken mee gaan (' + vraag.tekst + ')');
    ok(await page.evaluate(() => Android._agsInit().cfg.kal === '1' && Android._agsInit().cfg.kalNaam === 'Agenda'), 'gekozen agenda bewaard met naam en account');
    await page.click('#modal-cancel'); // Alleen nieuwe
    await page.waitForTimeout(150);
    const na = await page.evaluate(() => ({ cfg: Android._agsInit().cfg, lokaal: Android._agInit().items.filter(it => it.lokaal).length, aria: $('#ags-richting [data-v="naar"]').getAttribute('aria-checked') }));
    ok(na.cfg.richting === 'naar' && na.lokaal === 2 && na.aria === 'true', 'Alleen nieuwe: bestaande afspraken blijven alleen in Tools, richting staat op Tools → Google');
    await page.waitForTimeout(150);
    ok(await page.evaluate(() => Android._agsInit().log.length >= 1), 'na aanzetten meteen een ronde');
    ok(await page.evaluate(() => Android._agInit().items.every(it => it.lokaal || it.kalender)), 'afspraken die alleen in Tools moeten blijven, gaan niet mee');
    ok((await page.textContent('#ags-richting-t')).includes('Google volgt Tools'), 'uitleg bij de richting');
    ok(await page.isVisible('#ags-nu'), 'knop Nu synchroniseren zichtbaar');

    // instellingen zijn allemaal in te stellen en blijven staan
    await page.selectOption('#ags-elke', '15');
    await page.selectOption('#ags-terug', '-1');
    await page.selectOption('#ags-vooruit', '365');
    await page.selectOption('#ags-wissen', 'nooit');
    await page.selectOption('#ags-conflict', 'tools');
    await page.selectOption('#ags-rem-k', '25');
    await page.selectOption('#ags-meld', 'altijd');
    await page.click('#ags-laden');
    await page.click('#ags-open');
    await page.click('#ags-googlenu');
    await page.waitForTimeout(80);
    const cfg = await page.evaluate(() => Android._agsInit().cfg);
    ok(cfg.elke === 15 && cfg.terug === -1 && cfg.vooruit === 365 && cfg.wissen === 'nooit' && cfg.conflict === 'tools' && cfg.rem === 25 && cfg.meld === 'altijd'
      && cfg.laden === true && cfg.open === false && cfg.googleNu === false, 'alles bewaard: elk kwartier, bereik, verwijderen, conflict, rem, meldingen, laden, openen, Google meteen (' + JSON.stringify(cfg) + ')');
    ok((await page.textContent('#ags-wissen-t')).includes('blijft hij in Google'), 'uitleg bij verwijderen volgt de keuze');
    await page.evaluate(() => { show('agaset'); show('agsync'); });
    await page.waitForTimeout(80);
    const terug = await page.evaluate(() => ({ elke: $('#ags-elke').value, wissen: $('#ags-wissen').value, laden: $('#ags-laden').getAttribute('aria-checked'), open: $('#ags-open').getAttribute('aria-checked') }));
    ok(terug.elke === '15' && terug.wissen === 'nooit' && terug.laden === 'true' && terug.open === 'false', 'na opnieuw openen staan de keuzes er nog');

    // beide kanten: afspraken uit Google komen in Tools
    await page.click('#ags-richting [data-v="beide"]');
    await page.waitForTimeout(200);
    const beide = await page.evaluate(() => ({ r: Android._agsInit().cfg.richting, stand: $('#ags-stand-t').textContent, items: Android._agInit().items.filter(it => it.titel === 'Stand-up').length }));
    ok(beide.r === 'beide' && beide.items === 1, 'beide kanten: de afspraak uit Google staat nu ook in Tools');
    ok(/Laatst/.test(beide.stand) && /uit Google/.test(beide.stand), 'stand: laatste ronde en wat er veranderde (' + beide.stand.replace(/\n/g, ' | ') + ')');
    ok((await page.textContent('#ags-log')).includes('uit Google'), 'logboek toont de ronde');

    // conflict: beide versies zien en kiezen
    await page.evaluate(() => { const it = Android._agInit().items.find(x => x.titel === 'Stand-up'); it.plek = 'Kantoor'; Android._agsConflict = it.id; });
    await page.click('#ags-nu');
    await page.waitForTimeout(200);
    const con = await page.evaluate(() => ({ zichtbaar: !$('#ags-con').hidden, tekst: $('#ags-con-lijst').textContent, knoppen: document.querySelectorAll('#ags-con-lijst button').length }));
    ok(con.zichtbaar && con.tekst.includes('In Tools') && con.tekst.includes('In Google') && con.tekst.includes('Kantoor') && con.knoppen === 3, 'conflict: beide versies met Houd Tools, Houd Google en Houd beide');
    await page.click('#ags-con-lijst button[data-k="beide"]');
    await page.waitForTimeout(100);
    const opgelost = await page.evaluate(() => ({ con: $('#ags-con').hidden, keuze: (Android._agsKeuzes || []).slice(-1)[0], kopie: Android._agInit().items.filter(it => it.titel === 'Stand-up' && it.lokaal).length }));
    ok(opgelost.con && opgelost.keuze === 'beide' && opgelost.kopie === 1, 'Houd beide: de versie van Tools blijft als losse afspraak, het conflict is weg');

    // veiligheidsrem
    await page.evaluate(() => { Android._agsWeg = 30; });
    await page.click('#ags-nu');
    await page.waitForTimeout(200);
    ok(await page.isVisible('#ags-rem') && (await page.textContent('#ags-rem-t')).includes('30 afspraken') && (await page.textContent('#ags-rem-door')) === 'Losmaken'
      && await page.isHidden('#ags-rem-houd'), 'veiligheidsrem: vraagt eerst wat er met 30 verdwenen afspraken gebeurt (hier: losmaken)');
    await page.click('#ags-rem-door');
    await page.waitForTimeout(200);
    ok(await page.isHidden('#ags-rem') && (await page.textContent('#ags-log')).includes('losgemaakt'), 'Losmaken: niets verwijderd, alleen losgemaakt');
    await page.selectOption('#ags-wissen', 'beide');
    await page.evaluate(() => { Android._agsWeg = 12; });
    await page.click('#ags-nu');
    await page.waitForTimeout(200);
    ok((await page.textContent('#ags-rem-door')) === 'Toch verwijderen' && !(await page.isHidden('#ags-rem-houd')), 'wissen aan beide kanten: Toch verwijderen of In Tools houden');
    await page.click('#ags-rem-door');
    await page.waitForTimeout(60);
    ok(await page.evaluate(() => $('#modal').classList.contains('on')), 'verwijderen vraagt nog één keer');
    await page.click('#modal-ok');
    await page.waitForTimeout(200);
    ok(await page.isHidden('#ags-rem') && (await page.textContent('#ags-log')).includes('12 verwijderd'), 'toch verwijderd en in het logboek');
    await page.selectOption('#ags-wissen', 'nooit');

    // de agenda zelf: regel met de stand, nieuwe afspraak gaat standaard naar de gekozen agenda
    await page.evaluate(() => show('agenda'));
    await page.waitForTimeout(120);
    ok(!(await page.isHidden('#ag-syncregel')) && (await page.textContent('#ag-syncregel')).includes('Beide kanten'), 'Agenda toont de stand van synchroniseren');
    await page.click('#ag-nieuw');
    await page.waitForTimeout(80);
    ok(await page.inputValue('#agf-kal') === '1', 'nieuwe afspraak: standaard in de agenda van synchroniseren');
    await page.selectOption('#agf-kal', '');
    await page.waitForTimeout(40);
    ok(await page.isVisible('#agf-lokaal') && (await page.textContent('#agf-kal option[value=""]')).includes('niet synchroniseren'), 'Alleen in Tools: zichtbaar dat hij niet meedoet');
    await page.fill('#agf-titel', 'Privé');
    await page.click('#s-agafspraak .big');
    await page.waitForTimeout(100);
    ok(await page.evaluate(() => { const it = Android._agInit().items.find(x => x.titel === 'Privé'); return it && it.lokaal === true && !it.kalender; }), 'bewaard met lokaal: gaat niet naar Google');

    // verwijderen met "nooit": de afspraak blijft in Google, de vraag zegt dat
    await page.evaluate(() => { const it = Android._agInit().items.find(x => x.titel === 'Stand-up' && !x.lokaal); agOpen(it.id); });
    await page.waitForTimeout(80);
    await page.click('#agf-wis');
    await page.waitForTimeout(60);
    ok((await page.textContent('#modal-text')).includes('blijft in Agenda staan'), 'verwijderen: de vraag zegt dat hij in Google blijft');
    await page.click('#modal-ok');
    await page.waitForTimeout(100);
    ok(await page.evaluate(() => Android._agsInit().weg.length === 1 && (Android._agInit().extern || []).some(x => x.titel === 'Stand-up')), 'in Google gebleven en onthouden, zodat hij niet terugkomt');

    // de melding opent het scherm
    await page.evaluate(() => openTool('agenda-sync'));
    await page.waitForTimeout(120);
    ok(await page.evaluate(() => current === 'agsync'), 'melding opent Synchroniseren');

    // uitzetten
    await page.click('#ags-richting [data-v="uit"]');
    await page.waitForTimeout(80);
    ok(await page.evaluate(() => Android._agsInit().cfg.richting === 'uit') && await page.isHidden('#ags-nu'), 'uitzetten');
    await page.close();
  }
  await browser.close();
  console.log('errors', errors);
  process.exit(errors.length ? 1 : 0);
})();
