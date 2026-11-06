// Toiletten en douches: eigen wijzigingen (aanpassen, afwijzen, toevoegen; blijft na een scan), en doorgeven aan OpenStreetMap
const { chromium } = require('playwright');
const path = require('path');
const W = +process.env.UI_WACHT || 1;
(async () => {
  const b = await chromium.launch();
  const errs = [];
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(scheme + ': ' + e.message));
    const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m + ' (' + scheme + ')'); if (!c) errs.push(scheme + ': ' + m); };
    await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300 * W);
    const vis = sel => pg.evaluate(s => { const e = document.querySelector(s); return !!e && !!e.getClientRects().length; }, sel);
    const txt = sel => pg.textContent(sel);
    const toast = () => pg.textContent('#toast');
    const cur = () => pg.evaluate(() => current);
    const wc = () => pg.evaluate(() => Android._wc);
    const lijst = () => pg.$$eval('#wc-list .srow', r => r.map(x => x.textContent).join(' | '));
    const menu = async keuze => { await pg.click('#wcp-menu'); await pg.waitForTimeout(100 * W); await pg.click('#sheet-acts button:has-text("' + keuze + '")'); await pg.waitForTimeout(150 * W); };
    const open = async naam => { await pg.click('#wc-list .srow .sbtn:has-text("' + naam + '")'); await pg.waitForTimeout(200 * W); };
    const scan = async () => { await pg.evaluate(() => wcScanAlles()); await pg.waitForTimeout(300 * W); };
    const zetBron = (e, n) => pg.evaluate(([e, n]) => { Android._wc.data['osm-nl'].find(p => p[0] === e)[5] = n; }, [e, n]);
    await pg.evaluate(() => { window._open = []; Android.openUrl = u => window._open.push(u); });

    await pg.click('#tile-wc'); await pg.waitForTimeout(200 * W);
    await pg.click('#wc-ophalen'); await pg.waitForTimeout(350 * W);
    await pg.click('#wc-hier'); await pg.waitForTimeout(500 * W);
    ok(/Toilet Binnenrotte/.test(await lijst()), 'gegevens opgehaald en gezocht');

    // ---------- aanpassen ----------
    await open('Toilet Binnenrotte');
    await menu('Aanpassen');
    ok(await cur() === 'wcbewerk' && await txt('#wcb-h1') === 'Plek aanpassen', 'scherm Plek aanpassen');
    ok(await pg.inputValue('#wcb-n') === 'Toilet Binnenrotte' && await pg.inputValue('#wcb-u') === 'Mo-Su 08:00-20:00' && await pg.isChecked('#wcb-toilet') && await pg.isChecked('#wcb-rolstoel') && await pg.inputValue('#wcb-geld') === 'gratis', 'vooraf ingevuld uit de bron');
    ok(/blijft op je telefoon/.test(await txt('#wcb-uitleg')), 'uitleg: blijft op je telefoon');
    await pg.fill('#wcb-n', 'Toilet Binnenrotte (markt)'); await pg.selectOption('#wcb-geld', 'betaald'); await pg.fill('#wcb-notitie', 'Sleutel bij de kassa');
    await pg.click('#wcb-bewaar'); await pg.waitForTimeout(200 * W);
    ok(await cur() === 'wcplek' && /Aanpassing bewaard/.test(await toast()), 'aanpassing bewaard, terug naar de plek');
    let body = await txt('#wcp-body');
    ok(/Door jou aangepast/.test(body) && /Toilet Binnenrotte \(markt\)/.test(body) && /Betaald/.test(body) && /Sleutel bij de kassa/.test(body), 'plek toont jouw versie en notitie');
    ok((await wc()).calls.some(c => c[0] === 'bewaarPlek' && c[1] === 'osm-nl:n101' && c[2].n === 'Toilet Binnenrotte (markt)' && (c[2].f & 16) && !(c[2].f & 8)), 'bewaard als wijziging (betaald, niet gratis)');

    // ---------- de bron verandert: jouw versie blijft, met de keuze ----------
    await zetBron('n101', 'Toilet Markthal'); await scan();
    await pg.evaluate(() => wcpRender()); await pg.waitForTimeout(80 * W);
    body = await txt('#wcp-body');
    ok(/Toilet Binnenrotte \(markt\)/.test(body) && /De bron zegt nu iets anders/.test(body) && /Toilet Markthal/.test(body), 'na een scan: jouw versie blijft, de bron zegt nu iets anders');
    await pg.click('#wcp-body button:has-text("Mijn versie houden")'); await pg.waitForTimeout(150 * W);
    ok(/Je versie blijft staan/.test(await toast()) && !/De bron zegt nu iets anders/.test(await txt('#wcp-body')) && /Door jou aangepast/.test(await txt('#wcp-body')), 'Mijn versie houden: de vraag is weg');
    await zetBron('n101', 'Toilet Markthal West'); await scan();
    await pg.evaluate(() => wcpRender()); await pg.waitForTimeout(80 * W);
    ok(/Toilet Markthal West/.test(await txt('#wcp-body')), 'de bron verandert weer: opnieuw de vraag');
    await pg.click('#wcp-body button:has-text("Overnemen")'); await pg.waitForTimeout(150 * W);
    body = await txt('#wcp-body');
    ok(/de bron geldt weer/.test(await toast()) && !/Door jou aangepast/.test(body) && /Toilet Markthal West/.test(await txt('#wcp-body h2')), 'Overnemen: de bron geldt weer');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200 * W);

    // ---------- een tankstation (meestal een toilet) aanpassen ----------
    await pg.evaluate(() => wcOpen('osm-nl:n103')); await pg.waitForTimeout(200 * W);
    await menu('Aanpassen');
    ok(await vis('#wcb-zeker') && !(await pg.isChecked('#wcb-zeker')), 'meestal-plek: keuze "zeker een toilet" zichtbaar, uit');
    await pg.fill('#wcb-notitie', 'Naast de shop'); await pg.click('#wcb-bewaar'); await pg.waitForTimeout(200 * W);
    let bw = (await wc()).calls.filter(c => c[0] === 'bewaarPlek').pop();
    ok(await cur() === 'wcplek' && bw[1] === 'osm-nl:n103' && (bw[2].f & 4) && /meestal een toilet, maar dat staat niet zeker/.test(await txt('#wcp-body')), 'notitie erbij: blijft "meestal"');
    await menu('Aanpassen');
    await pg.check('#wcb-zeker'); await pg.click('#wcb-bewaar'); await pg.waitForTimeout(200 * W);
    bw = (await wc()).calls.filter(c => c[0] === 'bewaarPlek').pop();
    ok(!(bw[2].f & 4) && !/staat niet zeker/.test(await txt('#wcp-body')), 'zeker een toilet: niet meer "meestal"');
    await menu('Aanpassen');
    ok(await pg.isChecked('#wcb-zeker'), 'opnieuw aanpassen: "zeker" staat nog aan');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);
    await menu('Mijn aanpassing vergeten');
    ok(/staat niet zeker/.test(await txt('#wcp-body')), 'aanpassing vergeten: weer "meestal"');
    await pg.evaluate(() => wcOpen('osm-nl:n102')); await pg.waitForTimeout(200 * W);
    await menu('Aanpassen');
    ok(!(await vis('#wcb-zeker')), 'gewoon toilet: geen keuze "zeker"');
    await pg.evaluate(() => { goBack(); goBack(); }); await pg.waitForTimeout(200 * W);

    // ---------- afwijzen, ongedaan maken, opnieuw afwijzen ----------
    await open('Douches Zwembad');
    await menu('Klopt niet: afwijzen');
    const redenen = await pg.$$eval('#sheet-acts button', l => l.map(x => x.textContent.trim()));
    ok(['Bestaat niet meer', 'Gesloten', 'Niet openbaar', 'Alleen voor klanten', 'Iets anders'].every(r => redenen.includes(r)), 'vijf redenen');
    await pg.click('#sheet-acts button:has-text("Gesloten")'); await pg.waitForTimeout(150 * W);
    ok(/Blijft op je telefoon/.test(await txt('#modal-text')), 'toelichting: blijft op je telefoon');
    await pg.fill('#modal-input', 'Al maanden dicht'); await pg.click('#modal-ok'); await pg.waitForTimeout(150 * W);
    ok(/Afgewezen/.test(await txt('#undo-t')) && /Door jou afgewezen: Gesloten/.test(await txt('#wcp-body')) && /Al maanden dicht/.test(await txt('#wcp-body')), 'afgewezen met reden en toelichting');
    await pg.click('#undo-b'); await pg.waitForTimeout(150 * W);
    ok(!/Door jou afgewezen/.test(await txt('#wcp-body')), 'ongedaan gemaakt');
    await menu('Klopt niet: afwijzen');
    await pg.click('#sheet-acts button:has-text("Gesloten")'); await pg.waitForTimeout(150 * W);
    await pg.fill('#modal-input', 'Al maanden dicht'); await pg.click('#modal-ok'); await pg.waitForTimeout(150 * W);
    ok(await pg.isVisible('#wcp-body button:has-text("Terugzetten")'), 'opnieuw afgewezen: Terugzetten zichtbaar');
    await scan();
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200 * W);
    ok(!/Douches Zwembad/.test(await lijst()), 'afgewezen plek staat ook na een scan niet in de lijst');
    await pg.click('#wc-chips [data-f="douche"]'); await pg.waitForTimeout(100 * W);
    ok(!/Douches Zwembad/.test(await lijst()), 'ook niet bij het filter Douche');
    await pg.click('#wc-chips [data-f="douche"]'); await pg.waitForTimeout(100 * W);

    // ---------- doorgeven: eerst de precieze tekst en de waarschuwing ----------
    await pg.evaluate(() => wcOpen('osm-nl:n104')); await pg.waitForTimeout(200 * W);
    await menu('Doorgeven aan OpenStreetMap');
    ok(await cur() === 'wcdeel', 'scherm Doorgeven aan OpenStreetMap');
    ok(await vis('#wcd-let') && (await txt('#wcd-let b')) === 'Openbaar; OpenStreetMap ziet je IP-adres', 'waarschuwing zichtbaar: openbaar en IP-adres');
    const tekst = await txt('#wcd-tekst');
    ok(/het toilet is gesloten/.test(tekst) && /Opmerking: Al maanden dicht/.test(tekst) && /Object: https:\/\/www\.openstreetmap\.org\/node\/104/.test(tekst), 'precieze tekst: ' + tekst.split('\n')[0]);
    ok(await pg.inputValue('#wcd-opm') === 'Al maanden dicht', 'opmerking vooraf ingevuld met je toelichting, zichtbaar als openbaar');
    await pg.fill('#wcd-opm', ''); await pg.waitForTimeout(80 * W);
    ok(!/Al maanden/.test(await txt('#wcd-tekst')) && !/Opmerking/.test(await txt('#wcd-tekst')), 'opmerking leeg: hij gaat niet mee (tekst meteen bijgewerkt)');
    await pg.fill('#wcd-opm', 'Bord hangt er nog'); await pg.waitForTimeout(80 * W);
    ok(/Opmerking: Bord hangt er nog/.test(await txt('#wcd-tekst')), 'eigen opmerking in de tekst');
    await pg.evaluate(() => { wcDeel.tekst = 'iets anders'; });
    await pg.click('#wcd-ok'); await pg.waitForTimeout(250 * W);
    ok(/intussen veranderd/.test(await toast()) && !(await wc()).calls.some(c => c[0] === 'doorgeven') && /Bord hangt er nog/.test(await txt('#wcd-tekst')) && await pg.evaluate(() => wcDeel.tekst === document.getElementById('wcd-tekst').textContent), 'andere tekst dan getoond: er gaat niets, je ziet de tekst opnieuw');
    ok(/^Op de kaart: 51[.,]918/.test(await txt('#wcd-plek')), 'plek van de notitie');
    ok(await pg.evaluate(() => getComputedStyle(document.getElementById('wcd-let')).borderTopWidth) === '2px', 'waarschuwing met kader');
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'toiletten-deel-' + scheme + '.png'), fullPage: true });
    await pg.click('#s-wcdeel button:has-text("Niet doorgeven")'); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wcplek' && !(await wc()).calls.some(c => c[0] === 'doorgeven'), 'Niet doorgeven: er gaat niets weg');
    await menu('Doorgeven aan OpenStreetMap');
    const getoond = await txt('#wcd-tekst');
    await pg.click('#wcd-ok'); await pg.waitForTimeout(250 * W);
    ok(await cur() === 'wcplek' && /Doorgegeven aan OpenStreetMap/.test(await toast()), 'doorgegeven');
    ok((await wc()).calls.filter(c => c[0] === 'doorgeven').pop()[3] === getoond, 'precies de getoonde tekst gaat weg');
    ok(/Doorgegeven aan OpenStreetMap/.test(await txt('#wcp-body')) && /Klopt niet meer/.test(await txt('#wcp-body')) && /open/.test(await txt('#wcp-body')), 'de notitie staat bij de plek');
    await pg.click('#wcp-menu'); await pg.waitForTimeout(100 * W);
    ok(!(await pg.$$eval('#sheet-acts button', l => l.map(x => x.textContent))).some(x => /Doorgeven/.test(x)), 'staat open bij OpenStreetMap: niet nog een keer doorgeven');
    await pg.evaluate(() => closeSheet()); await pg.waitForTimeout(100 * W);
    ok(/al open/.test(await pg.evaluate(() => wcFout(Android.wcNotitie('osm-nl:n104', 'afwijzing', '')))), 'ook de brug weigert een tweede notitie');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);

    // ---------- een plek toevoegen (lang indrukken op de kaart) ----------
    await pg.evaluate(() => wcMap.fire('contextmenu', { latlng: L.latLng(51.921000, 4.481000) })); await pg.waitForTimeout(200 * W);
    ok(await cur() === 'wcbewerk' && await txt('#wcb-h1') === 'Plek toevoegen' && /Plek: 51[.,]921/.test(await txt('#wcb-plek')), 'lang indrukken: plek toevoegen op die plek');
    ok(/geef hem dan daarna door aan OpenStreetMap/.test(await txt('#wcb-uitleg')), 'uitleg: eerst alleen op je telefoon');
    await pg.uncheck('#wcb-toilet'); await pg.click('#wcb-bewaar'); await pg.waitForTimeout(100 * W);
    ok(await cur() === 'wcbewerk' && /Vink toilet/.test(await toast()), 'zonder toilet of douche: niet bewaard');
    await pg.check('#wcb-toilet'); await pg.fill('#wcb-n', 'Toilet bij de bieb'); await pg.selectOption('#wcb-geld', 'gratis'); await pg.fill('#wcb-notitie', 'Code 4321');
    await pg.click('#wcb-bewaar'); await pg.waitForTimeout(200 * W);
    ok(await cur() === 'wcplek' && /Plek toegevoegd/.test(await toast()) && /Jouw eigen plek/.test(await txt('#wcp-body')) && /Door jou toegevoegd/.test(await txt('#wcp-body')), 'eigen plek toegevoegd');
    const k = await pg.evaluate(() => wcK);
    ok(/^eigen:[0-9a-f]{12}$/.test(k), 'sleutel van een eigen plek: ' + k);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200 * W);
    ok(await cur() === 'wc' && /Toilet bij de bieb/.test(await lijst()), 'eigen plek in de lijst');
    await pg.evaluate(() => wcMenu()); await pg.waitForTimeout(100 * W);
    await pg.click('#sheet-acts button:has-text("Plek toevoegen")'); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wcbewerk' && await txt('#wcb-h1') === 'Plek toevoegen', 'ook via ⋯ → Plek toevoegen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);

    // doorgeven in een gebied waar het alleen via de website kan
    await pg.evaluate(() => { Android._wc.zone = true; });
    await open('Toilet bij de bieb');
    await menu('Doorgeven aan OpenStreetMap');
    ok(/Hier is een toilet dat nog niet op de kaart staat/.test(await txt('#wcd-tekst')) && /Naam: Toilet bij de bieb/.test(await txt('#wcd-tekst')), 'tekst voor een nieuwe plek');
    ok(!/4321/.test(await txt('#wcd-tekst')) && await pg.inputValue('#wcd-opm') === '', 'je notitie voor jezelf gaat niet mee');
    await pg.click('#wcd-ok'); await pg.waitForTimeout(250 * W);
    const urls = await pg.evaluate(() => window._open);
    ok(/alleen via de website/.test(await toast()) && /^https:\/\/www\.openstreetmap\.org\/note\/new#map=19\/51\.921\/4\.481$/.test(urls[urls.length - 1]), 'moderatiezone: de website op die plek');
    ok(/Hier is een toilet/.test((await wc()).klembord || ''), 'de tekst staat op het klembord');
    await pg.evaluate(() => { Android._wc.zone = false; });

    // de plek staat later ook in een bron: eigen plek opruimen
    await pg.evaluate(() => Android._wc.data['osm-nl'].push(['n107', 51.92101, 4.48101, 'toilet', 1 | 8, 'Bibliotheek', '']));
    await scan();
    await pg.evaluate(() => wcpRender()); await pg.waitForTimeout(80 * W);
    ok(/staat nu ook in een bron/.test(await txt('#wcp-body')), 'eigen plek staat nu ook in een bron');
    await pg.click('#wcp-body button:has-text("Eigen plek weghalen")'); await pg.waitForTimeout(100 * W);
    await pg.click('#modal-ok'); await pg.waitForTimeout(200 * W);
    ok(await cur() === 'wc' && /Eigen plek weggehaald/.test(await toast()) && !/Toilet bij de bieb/.test(await lijst()) && /Bibliotheek/.test(await lijst()), 'opgeruimd: alleen de plek uit de bron blijft');

    // ---------- Mijn wijzigingen ----------
    await pg.click('#wc-set-btn'); await pg.waitForTimeout(150 * W);
    ok(await txt('#wcs-eigen') === 'één afgewezen', 'stand eigen wijzigingen: ' + await txt('#wcs-eigen'));
    ok(/^Eén notitie · één open$/.test(await txt('#wcs-gedeeld')), 'stand gedeeld: ' + await txt('#wcs-gedeeld'));
    await pg.click('#s-wcset .strow:has-text("Mijn wijzigingen")'); await pg.waitForTimeout(150 * W);
    ok(/Afgewezen/.test(await txt('#wce-body')) && /Douches Zwembad/.test(await txt('#wce-body')) && /Gesloten/.test(await txt('#wce-body')), 'afgewezen plek met reden');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#wce-body .srow b')].every(x => x.getAttribute('translate') === 'no' || !x.closest('.srow').textContent.includes('Zwembad'))), 'namen niet vertalen');
    await pg.click('#wce-body button:has-text("Terugzetten")'); await pg.waitForTimeout(150 * W);
    ok(/Teruggezet/.test(await toast()) && /Nog niets/.test(await txt('#wce-body')), 'teruggezet: lijst leeg');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100 * W);

    // ---------- Gedeeld met de gemeenschap ----------
    await pg.click('#s-wcset .strow:has-text("Gedeeld met de gemeenschap")'); await pg.waitForTimeout(150 * W);
    ok((await pg.$$('#wcg-body .srow')).length === 1 && /Klopt niet meer/.test(await txt('#wcg-body')), 'één notitie doorgegeven');
    await pg.click('#wcg-body button:has-text("Bekijken")');
    ok(/^https:\/\/www\.openstreetmap\.org\/note\/\d+$/.test((await pg.evaluate(() => window._open)).pop()), 'notitie bekijken op OpenStreetMap');
    ok(await pg.evaluate(() => { const x = document.querySelector('#wcg-body .icon-btn').getBoundingClientRect(); return x.width >= 44 && x.height >= 44; }), 'Uit de lijst halen: 44 px');
    await pg.click('#wcg-body .icon-btn'); await pg.waitForTimeout(100 * W);
    ok(/Nog niets doorgegeven/.test(await txt('#wcg-body')), 'uit de lijst gehaald');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100 * W);

    // Mijn wijzigingen vanuit ⋯ op het hoofdscherm: terug gaat naar het hoofdscherm
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);
    await pg.evaluate(() => wcMenu()); await pg.waitForTimeout(100 * W);
    await pg.click('#sheet-acts button:has-text("Mijn wijzigingen")'); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wceigen', 'via ⋯ naar Mijn wijzigingen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wc', 'terug: weer op Toiletten en douches');
    await pg.click('#wc-set-btn'); await pg.waitForTimeout(150 * W);
    await pg.click('#s-wcset .strow:has-text("Mijn wijzigingen")'); await pg.waitForTimeout(150 * W);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wcset', 'vanuit de instellingen: terug naar de instellingen');

    // ---------- eigen wijzigingen niet leesbaar: niets overschrijven ----------
    await pg.evaluate(() => { Android._wc.eigenKapot = true; wcState(); wcsRender(); });
    ok(/Niet leesbaar/.test(await txt('#wcs-eigen')), 'niet leesbaar: zichtbaar in de instellingen');
    await pg.click('#s-wcset .strow:has-text("Mijn wijzigingen")'); await pg.waitForTimeout(150 * W);
    ok(/niet leesbaar/.test(await txt('#wce-body')), 'niet leesbaar: melding');
    await pg.evaluate(() => { goBack(); goBack(); }); await pg.waitForTimeout(200 * W);
    await open('Toilet Markthal West'); await menu('Aanpassen');
    await pg.fill('#wcb-n', 'X'); await pg.click('#wcb-bewaar'); await pg.waitForTimeout(100 * W);
    ok(await cur() === 'wcbewerk' && /niet leesbaar/.test(await toast()), 'niet leesbaar: niet bewaard');
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'toiletten-bewerk-' + scheme + '.png'), fullPage: true });
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})().catch(e => { console.log('errors', [String(e && e.stack || e)]); process.exit(1); });
