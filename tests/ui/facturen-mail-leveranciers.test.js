// Facturen uit de mail: elke afzender een eigen leverancier (Gevonden in je mail), Klopt, Hoort bij (samenvoegen met
// een website-leverancier), herkennen aanpassen, overslaan en terugzetten, je eigen nummers, een factuur in het dossier
// naar een andere leverancier (met Onthoud), het overzicht per leverancier en aanleveren "Alles uit Gmail".
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    const ok = (c, wat) => { if (!c) errs.push('mislukt: ' + wat); };
    const scherm = () => pg.evaluate(() => current);
    const tekst = sel => pg.textContent(sel);
    const tab = async t => { await pg.click('.fl-tabs button[data-tab="' + t + '"]'); await pg.waitForTimeout(60); };
    const zichtbaar = sel => pg.$$eval(sel, l => l.filter(e => e.offsetParent !== null).map(e => e.dataset.tab));
    const fa = () => pg.evaluate(() => Android._fa);
    const lev = async id => (await fa()).lev.find(l => l.id === id);
    const rij = naam => '#fa-uit .sbtn:has-text("' + naam + '")';
    // Een website (KPN Zakelijk) en Gmail als mailbron
    await pg.evaluate(() => { const f = Android._fa; f.drive = { gekoppeld: true, gebruiker: 'rene@gmail.com', rechtGmail: true, rechtDrive: false };
      f.lev.push({ id: 'kpn-zakelijk', naam: 'KPN Zakelijk', soort: 'website', schema: 'maand', dag: 5, tijd: '08:00', startUrl: 'https://mijnkpnzakelijk.kpn.com' });
      f.lev.push({ id: 'gmail', naam: 'Facturen in Gmail', soort: 'mail', sjabloon: 'gmail', schema: 'dag', tijd: '07:00', map: true, mail: { bron: 'gmail', woorden: 'factuur, invoice', afzenders: '', negeer: '', extra: '', max: 50 } }); });
    await pg.click('#tile-facturen'); await pg.waitForTimeout(200);
    ok(await pg.isHidden('#fa-uit-blok'), 'nog niets uit de mail: geen groep');
    // Zoeken in Gmail: drie afzenders, elk een eigen leverancier
    await pg.click('#fa-lijst .sbtn:has-text("Facturen in Gmail")'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Nu ophalen")'); await pg.waitForTimeout(1000);
    ok(await pg.isVisible('#fa-uit-blok') && (await pg.$$('#fa-uit .srow')).length === 3, 'drie leveranciers gevonden in je mail');
    ok(await pg.isHidden('#fa-uit-meer'), 'drie: geen knop om alles te tonen');
    ok((await tekst('#fa-uit-h')).includes('3 nieuw') && (await pg.$$('#fa-uit .fa-nieuw')).length === 3, 'alle drie met het label Nieuw');
    ok((await pg.$$('#fa-lijst .srow')).length === 2 && (await tekst('#fa-lijst')).includes('3 leveranciers gevonden'), 'websites en mailbron apart; de mailbron telt wat hij vond');
    ok((await tekst(rij('KPN'))).includes('Hoort dit bij KPN Zakelijk?'), 'KPN uit de mail: voorstel bij de website KPN Zakelijk');
    ok((await tekst('#fa-maand-lijst')).includes('Eneco') && (await tekst('#fa-maand-lijst')).includes('1 factuur van deze maand'), 'deze maand: Eneco met een factuur');
    ok((await tekst('#fa-maand-lijst .fa-rest summary')).includes('1 andere leverancier') && !(await tekst('#fa-maand-lijst')).includes('Facturen in Gmail'), 'deze maand: de mailbron zelf staat er niet bij: ' + await tekst('#fa-maand-lijst .fa-rest summary'));
    const dos = Object.values((await fa()).dos);
    ok(dos.length === 3 && dos.every(s => s.via === 'gmail' && s.leverancier !== 'gmail'), 'dossier: elke factuur bij zijn eigen leverancier, via Gmail');
    await pg.screenshot({ path: __dirname + '/shots/facturen-mail-' + scheme + '.png', fullPage: true });
    // Menu van een leverancier uit de mail: geen ophalen of meekijken
    await pg.click(rij('Eneco')); await pg.waitForTimeout(150);
    const knoppen = await pg.$$eval('#sheet button', l => l.map(x => x.textContent));
    ok(knoppen.some(x => x === 'Klopt') && knoppen.some(x => x.startsWith('Samenvoegen met')) && knoppen.some(x => x.startsWith('Overslaan')) && !knoppen.some(x => /ophalen|meekijken|Verwijderen/.test(x)), 'menu: Klopt, Samenvoegen, Overslaan; geen ophalen: ' + knoppen.join(' | '));
    await pg.click('#sheet button:has-text("Klopt")'); await pg.waitForTimeout(150);
    ok(!(await lev('eneco')).nieuw && !(await tekst(rij('Eneco'))).includes('Nieuw') && (await tekst('#fa-uit-h')).includes('2 nieuw'), 'Klopt: niet meer nieuw');
    // Hoort bij KPN Zakelijk: samenvoegen
    await pg.click(rij('KPN')); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Hoort bij KPN Zakelijk")'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#modal.on') && (await tekst('#modal')).includes('blijven waar ze zijn'), 'vraagt eerst; kopieën buiten de app blijven');
    await pg.click('#modal button:has-text("Samenvoegen")'); await pg.waitForTimeout(150);
    ok(!(await lev('kpn')) && (await lev('kpn-zakelijk')).herken.domeinen.includes('kpn.com'), 'KPN uit de mail is opgegaan in KPN Zakelijk (met het domein)');
    ok(Object.values((await fa()).dos).find(s => s.naam === 'KPN factuur oktober.pdf').leverancier === 'kpn-zakelijk', 'de factuur staat nu bij KPN Zakelijk');
    ok((await pg.$$('#fa-uit .srow')).length === 2, 'nog twee in de groep uit de mail');
    // Naam en herkenning aanpassen: alleen Herkennen en Opslaan
    await pg.click(rij('Bakkerij Jansen')); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Naam en opslag aanpassen")'); await pg.waitForTimeout(200);
    ok(await scherm() === 'faclev' && JSON.stringify(await zichtbaar('.fl-tabs button')) === JSON.stringify(['herk', 'opslag']), 'tabbladen Herkennen en Opslaan: ' + JSON.stringify(await zichtbaar('.fl-tabs button')));
    ok(await pg.inputValue('#fl-h-namen') === 'bakkerij jansen' && (await tekst('#fl-h-bron')).includes('Facturen in Gmail'), 'herkend op de naam bij het platform, gevonden in Gmail');
    await pg.fill('#fl-naam', 'Bakkerij Jansen B.V.'); await pg.fill('#fl-h-kvk', '1234');
    await tab('opslag'); ok(await pg.isVisible('#fl-sub') && await pg.isHidden('#fl-opslag-mail'), 'eigen mapnaam per leverancier');
    await pg.click('#fl-save'); await pg.waitForTimeout(150);
    ok((await tekst('#fl-msg')).includes('8 cijfers') && await pg.isVisible('#fl-h-kvk'), 'fout in de herkenning: terug naar Herkennen');
    await pg.fill('#fl-h-kvk', '12345678'); await pg.click('#fl-save'); await pg.waitForTimeout(200);
    const bk = await pg.evaluate(() => Android._fa.calls.filter(c => c[0] === 'bewaar').pop()[1]);
    ok(bk.soort === 'uitmail' && bk.id === 'bakkerij-jansen' && bk.herken.kvk === '12345678' && bk.herken.namen === 'bakkerij jansen' && bk.nieuw === false && bk.bronnen[0] === 'gmail', 'bewaard als leverancier uit de mail: ' + JSON.stringify(bk.herken));
    ok(await scherm() === 'facturen' && (await tekst('#fa-uit')).includes('Bakkerij Jansen B.V.') && (await tekst('#fa-uit-h')) === 'Gevonden in je mail', 'nieuwe naam, niets meer nieuw');
    // Overslaan en weer terugzetten (bij de mailbron)
    await pg.click(rij('Bakkerij Jansen')); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Overslaan…")'); await pg.waitForTimeout(150);
    ok((await tekst('#sheet')).includes('Terugzetten kan'), 'zegt hoe je terugzet');
    await pg.click('#sheet button:has-text("wat er al is, blijft")'); await pg.waitForTimeout(150);
    ok((await lev('bakkerij-jansen')).overslaan && (await pg.$$('#fa-uit .srow')).length === 1 && Object.values((await fa()).dos).some(s => s.leverancier === 'bakkerij-jansen'), 'overgeslagen: weg uit de lijst, dossier blijft');
    await pg.click('#fa-lijst .sbtn:has-text("Facturen in Gmail")'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Instellingen")'); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#fl-m-overkaart') && (await tekst('#fl-m-over')).includes('Bakkerij Jansen B.V.'), 'Gmail: lijst met overgeslagen leveranciers');
    ok(!(await pg.$('#fl-m-per')) && await pg.isHidden('#fl-subrij'), 'geen "per afzender een eigen map" meer');
    await pg.evaluate(() => { Android._fa.overgeslagen = { m1: 'bakkerij-jansen', m2: 'bakkerij-jansen', m3: 'eneco' }; });
    await pg.click('#fl-m-over button:has-text("Terugzetten")'); await pg.waitForTimeout(150);
    ok(!(await lev('bakkerij-jansen')).overslaan && await pg.isHidden('#fl-m-overkaart'), 'teruggezet');
    ok((await tekst('#toast')).includes('2 overgeslagen facturen komen bij de volgende keer zoeken') && Object.keys((await fa()).overgeslagen).length === 1, 'terugzetten: de overgeslagen facturen komen alsnog: ' + await tekst('#toast'));
    // Je eigen nummers en adressen
    await pg.fill('#fl-e-nr', 'abc'); await pg.click('#fl-save'); await pg.waitForTimeout(150);
    ok((await tekst('#fl-msg')).includes('Geen KvK-, btw- of IBAN-nummer') && await pg.isVisible('#fl-e-nr'), 'fout eigen nummer: blijft op Zoeken');
    await pg.fill('#fl-e-nr', '12345678, NL91 ABNA 0417 1643 00'); await pg.fill('#fl-e-adr', 'Rene@Voorbeeld.nl'); await pg.click('#fl-save'); await pg.waitForTimeout(200);
    const eg = (await fa()).herkennen;
    ok(eg && eg.nummers.includes('NL91ABNA0417164300') && eg.adressen[0] === 'rene@voorbeeld.nl', 'eigen nummers en adres bewaard: ' + JSON.stringify(eg));
    // Dossier: een factuur uit de mail bij een andere leverancier, en onthouden voor deze afzender
    await pg.click('#s-facturen button:has-text("Dossier")'); await pg.waitForTimeout(200);
    await pg.click('#fd-lijst tr.fd-rij:has-text("Eneco")'); await pg.waitForTimeout(200);
    ok(await scherm() === 'facstuk' && await pg.isVisible('#fs-levsel') && await pg.isHidden('#fs-levnaam') && await pg.isHidden('#fs-onthoudrij'), 'uit de mail: leverancier kiezen in plaats van typen');
    await pg.selectOption('#fs-levsel', 'bakkerij-jansen'); await pg.waitForTimeout(60);
    ok(await pg.isVisible('#fs-onthoudrij') && (await tekst('#fs-onthoud-t')).includes('factuur@eneco.nl'), 'onthouden voor dit adres');
    await pg.check('#fs-onthoud'); await pg.click('#s-facstuk button.big:has-text("Bewaren")'); await pg.waitForTimeout(200);
    const st = Object.values((await fa()).dos).find(s => s.naam === 'Eneco jaarnota 2026.pdf');
    ok(st.leverancier === 'bakkerij-jansen' && (await lev('bakkerij-jansen')).herken.afzenders.includes('factuur@eneco.nl'), 'verplaatst en onthouden');
    ok(st.leverancierNaam === 'Bakkerij Jansen B.V.', 'de naam komt van de leverancier, niet uit het veld: ' + st.leverancierNaam);
    // Via een platform (Moneybird): onthouden op de naam, nooit op het adres van het platform; naar Niet herkend: niets onthouden
    await pg.evaluate(() => { const f = Android._fa; f.lev.push({ id: 'niet-herkend', naam: 'Niet herkend', soort: 'uitmail', sjabloon: 'uitmail', herken: {}, bronnen: ['gmail'] });
      faLaad(); fdLaad(); fsToon(Object.values(f.dos).find(s => s.naam === 'Factuur 2026-0042.pdf').sha); }); await pg.waitForTimeout(150);
    await pg.selectOption('#fs-levsel', 'eneco'); await pg.waitForTimeout(60);
    ok(await pg.isVisible('#fs-onthoudrij') && (await tekst('#fs-onthoud-t')).includes('Bakkerij Jansen') && !(await tekst('#fs-onthoud-t')).includes('moneybird'), 'platform: onthouden voor de naam: ' + await tekst('#fs-onthoud-t'));
    await pg.selectOption('#fs-levsel', 'niet-herkend'); await pg.waitForTimeout(60);
    ok(await pg.isHidden('#fs-onthoudrij'), 'naar Niet herkend: niets te onthouden');
    ok((await pg.$$eval('#fs-levsel option', l => l.map(o => o.textContent))).includes('Niet herkend'), 'Niet herkend staat in de keuzelijst');
    // Fout in de gegevens: niets verplaatst
    await pg.selectOption('#fs-levsel', 'eneco');
    await pg.evaluate(() => { const m = Android.facturenDossierMeta; Android.facturenDossierMeta = () => 'Ongeldig bedrag'; window._herstel = () => { Android.facturenDossierMeta = m; }; });
    await pg.click('#s-facstuk button.big:has-text("Bewaren")'); await pg.waitForTimeout(150);
    ok((await tekst('#fs-msg')) === 'Ongeldig bedrag' && Object.values((await fa()).dos).find(s => s.naam === 'Factuur 2026-0042.pdf').leverancier === 'bakkerij-jansen', 'fout in de gegevens: blijft bij de oude leverancier');
    await pg.evaluate(() => { window._herstel(); Android._fa.lev = Android._fa.lev.filter(l => l.id !== 'niet-herkend'); faLaad(); });
    await pg.evaluate(() => show('facturen')); await pg.waitForTimeout(150);
    // Leverancier toevoegen in het dossier: de mailbron zelf niet
    await pg.evaluate(() => fdToevoegen()); await pg.waitForTimeout(150);
    const keuzes = await pg.$$eval('#sheet button', l => l.map(x => x.textContent));
    ok(!keuzes.includes('Facturen in Gmail') && keuzes.includes('Eneco') && keuzes.includes('KPN Zakelijk'), 'factuur toevoegen: zonder de mailbron: ' + keuzes.join(' | '));
    await pg.evaluate(() => closeSheet()); await pg.waitForTimeout(100);
    // Overzicht per maand: een rij per leverancier
    await pg.click('#s-facturen .strow:has-text("Overzicht per maand")'); await pg.waitForTimeout(150);
    const rijen = await pg.$$eval('#fa-overzicht-lijst tbody th', l => l.map(x => x.textContent));
    ok(rijen[0] === 'KPN Zakelijk' && rijen.includes('Bakkerij Jansen B.V.') && !rijen.includes('Facturen in Gmail') && (await pg.$$('#fa-overzicht-lijst thead th')).length === 13, 'overzicht: website eerst, een rij per leverancier, niet de mailbron, 12 maanden: ' + JSON.stringify(rijen));
    await pg.evaluate(() => show('facturen')); await pg.waitForTimeout(100);
    // Aanleveren: "Alles uit Facturen in Gmail" en de leveranciers los
    await pg.click('#s-facturen button:has-text("Aanleveren")'); await pg.waitForTimeout(150);
    await pg.click('#fo-nieuw'); await pg.waitForTimeout(150);
    ok((await tekst('#fo-levs')).includes('Alles uit Facturen in Gmail') && await pg.$('#fo-levs input[value="gmail"]') && await pg.$('#fo-levs details input[value="eneco"]'), 'aanleveren: alles uit Gmail, en los per leverancier');
    await pg.fill('#fo-naam', 'Boekhouder'); await pg.fill('#fo-aan', 'facturen@boekhouder.nl'); await pg.check('#fo-levs input[value="gmail"]');
    await pg.click('#s-facontv button.big:has-text("Bewaren")'); await pg.waitForTimeout(200);
    await pg.waitForTimeout(400); if (await pg.isVisible('#modal.on')) { await pg.click('#modal-cancel'); await pg.waitForTimeout(100); }
    const on = await pg.evaluate(() => Android._fa.calls.filter(c => c[0] === 'ontv').pop()[1]);
    ok(JSON.stringify(on.leveranciers) === '["gmail"]', 'ontvanger met alles uit Gmail: ' + JSON.stringify(on.leveranciers));
    ok(await pg.evaluate(() => Android._foOpen({ id: 'x', leveranciers: ['gmail'], vanaf: 0 }).length) === 3, 'alles uit Gmail = ook de facturen die nu bij hun eigen leverancier staan');
    await pg.evaluate(() => show('facturen')); await pg.waitForTimeout(150);
    // Veel leveranciers uit de mail: eerst 8, de rest met een knop
    await pg.evaluate(() => { for (let i = 1; i <= 9; i++) Android._fa.lev.push({ id: 'l' + i, naam: 'Winkel ' + i, soort: 'uitmail', sjabloon: 'uitmail', bronnen: ['gmail'], schema: 'uit', herken: { domeinen: ['winkel' + i + '.nl'] } }); show('facturen'); });
    await pg.waitForTimeout(250);
    ok((await pg.$$('#fa-uit .srow')).length === 8 && await pg.isVisible('#fa-uit-meer') && (await tekst('#fa-uit-meer')) === 'Alle 11 tonen', 'eerst 8, dan "Alle 11 tonen"');
    await pg.click('#fa-uit-meer'); await pg.waitForTimeout(100);
    ok((await pg.$$('#fa-uit .srow')).length === 11 && (await tekst('#fa-uit-meer')) === 'Minder tonen', 'alles getoond');
    await pg.click('#fa-uit-meer'); await pg.waitForTimeout(100);
    // Engels: de groep en het label vertaald, de namen niet
    if (scheme === 'light') {
      await pg.evaluate(() => show('settings')); await pg.waitForTimeout(150);
      await pg.click('button.strow:has-text("Regio en taal")'); await pg.waitForTimeout(150);
      await pg.selectOption('#rg-lang', 'en'); await pg.waitForTimeout(150);
      await pg.evaluate(() => { Android._fa.lev.find(l => l.id === 'eneco').nieuw = true; show('facturen'); }); await pg.waitForTimeout(250);
      ok((await tekst('#fa-uit-h')).startsWith('Found in your mail') && (await tekst(rij('Eneco'))).includes('New'), 'Engels: ' + await tekst('#fa-uit-h'));
      ok((await tekst('#fa-uit')).includes('Bakkerij Jansen B.V.'), 'namen blijven zoals ze zijn');
    }
    console.log('errors', errs);
  }
  await b.close();
})();
