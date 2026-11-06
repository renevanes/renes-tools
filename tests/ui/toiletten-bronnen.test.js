// Toiletten en douches: bronnen beheren (sjablonen, velden en regels, planning), scannen, fouten, samenvoegen, verwijderen
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

    await pg.click('#tile-wc'); await pg.waitForTimeout(200 * W);
    await pg.click('#wc-ophalen'); await pg.waitForTimeout(350 * W);
    ok(await vis('#wc-hier'), 'eerst de gegevens opgehaald');

    // ---------- de hub ----------
    await pg.click('#wc-set-btn'); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wcset' && !!(await pg.$('#s-wcset .hdr.sm')), 'instellingen: detailscherm');
    ok(/^Eén bron aan · bijgewerkt /.test(await txt('#wcs-bronnen')), 'stand bronnen: ' + await txt('#wcs-bronnen'));
    ok(await txt('#wcs-eigen') === 'Nog niets' && await txt('#wcs-gedeeld') === 'Nog niets doorgegeven', 'stand eigen wijzigingen en gedeeld');
    await pg.click('#s-wcset .strow:has-text("Bronnen")'); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wcbronnen', 'scherm Bronnen');
    let rijen = await pg.$$eval('#wcbr-list .srow', r => r.map(x => x.textContent));
    ok(rijen.length === 1 && /OpenStreetMap Nederland/.test(rijen[0]) && /6 plekken · bijgewerkt/.test(rijen[0]), 'één bron: ' + rijen.join(' | '));

    // ---------- een bestaande bron ----------
    await pg.click('#wcbr-list .sbtn'); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wcbron' && await txt('#wcn-h1') === 'Bron', 'bron geopend');
    ok(!(await vis('#wcn-sjabloonkaart')) && await vis('#wcn-menu'), 'bestaande bron: geen sjabloonkeuze, wel het menu');
    ok(/Laatste scan/.test(await txt('#wcn-stand')) && /6 plekken \(6 nieuw, 0 gewijzigd, 0 weg\)/.test(await txt('#wcn-stand')), 'laatste scan met aantallen');
    ok(/Volgende keer/.test(await txt('#wcn-stand')), 'volgende keer zichtbaar');
    ok(!(await vis('#wcn-veldenkaart')) && await vis('#wcn-meta'), 'momentopname van Tools: geen velden, wel meta.json');
    ok(await pg.inputValue('#wcn-schema') === 'week' && await pg.inputValue('#wcn-wd') === '3' && await pg.isChecked('#wcn-wifi'), 'planning: elke woensdag, alleen wifi');
    ok(/^Vanzelf .*Eerstvolgende keer: /.test(await txt('#wcn-plan')), 'planning in woorden: ' + await txt('#wcn-plan'));
    await pg.selectOption('#wcn-schema', 'uit'); await pg.waitForTimeout(50 * W);
    ok(await txt('#wcn-plan') === 'Alleen als je zelf op Nu scannen tikt.' && !(await vis('#wcn-tijd')) && !(await vis('#wcn-wd')), 'planning uit: alleen met de hand');
    await pg.selectOption('#wcn-schema', 'maand'); await pg.waitForTimeout(50 * W);
    ok(await vis('#wcn-dag') && !(await vis('#wcn-wd')) && (await pg.$$eval('#wcn-dag option', o => o.length)) === 31, 'elke maand: dag van de maand');
    await pg.selectOption('#wcn-schema', 'week');

    // scannen die mislukt: de vorige versie blijft
    await pg.evaluate(() => { Android._wc.scanFout = 'Geen verbinding'; });
    await pg.click('#wcn-scan'); await pg.waitForTimeout(30 * W);
    ok(await pg.isDisabled('#wcn-scan'), 'tijdens het scannen: knop uit');
    await pg.waitForTimeout(300 * W);
    ok(/lukte niet: Geen verbinding/.test(await txt('#wcn-stand')) && /De vorige versie blijft staan/.test(await txt('#wcn-stand')), 'mislukte scan: fout en de vorige versie blijft');
    ok(/Ophalen lukte niet: Geen verbinding/.test(await toast()), 'melding bij een mislukte scan');
    ok((await wc()).calls.some(c => c[0] === 'bewaar' && c[1] === 'osm-nl') && (await wc()).calls.some(c => c[0] === 'scan' && c[1] === 'osm-nl'), 'Nu scannen bewaart eerst en scant dan alleen deze bron');
    await pg.evaluate(() => { Android._wc.scanFout = ''; });
    await pg.click('#wcn-scan'); await pg.waitForTimeout(350 * W);
    ok(!/lukte niet/.test(await txt('#wcn-stand')) && await vis('#wcn-stand details.help'), 'weer gelukt: eerdere scans ingeklapt');
    await pg.click('#wcn-stand details.help summary'); await pg.waitForTimeout(50 * W);
    ok((await pg.$$('#wcn-stand .wc-log li')).length === 3 && /mislukt \(Geen verbinding\)/.test(await txt('#wcn-stand .wc-log')), 'logboek van de scans');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wcbronnen', 'terug naar Bronnen');

    // ---------- een bron toevoegen uit een sjabloon ----------
    await pg.click('#s-wcbronnen button:has-text("Bron toevoegen")'); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wcbron' && await txt('#wcn-h1') === 'Bron toevoegen' && await vis('#wcn-sjabloonkaart') && !(await vis('#wcn-menu')), 'nieuwe bron: sjabloon kiezen');
    const opties = await pg.$$eval('#wcn-sjabloon option', o => o.map(x => x.textContent));
    ok(/OpenStreetMap Nederland \(staat er al\)/.test(opties[0]) && opties.includes('Zelf invullen') && opties.length === 4, 'sjablonen: ' + opties.join(' | '));
    ok(await pg.inputValue('#wcn-sjabloon') === 'den-haag-openbaar' && await pg.inputValue('#wcn-naam') === 'Den Haag: openbare toiletten', 'eerste nog niet gebruikte sjabloon vooraf gekozen');
    ok(/gemeente Den Haag/.test(await txt('#wcn-tip')), 'uitleg bij het sjabloon');
    ok(await vis('#wcn-veldenkaart') && await pg.inputValue('#wcn-v-id') === 'globalid' && await pg.inputValue('#wcn-v-naam') === 'omschrijving|adres', 'velden van het sjabloon');
    ok((await pg.$$('#wcn-regels .wc-regel')).length === 1 && await pg.inputValue('#wcn-regels [data-r=zet]') === 'urinoir', 'regel van het sjabloon');
    ok(!(await vis('#wcn-stand')), 'nog geen laatste scan');
    await pg.click('#s-wcbron button:has-text("Regel toevoegen")'); await pg.waitForTimeout(50 * W);
    const r2 = '#wcn-regels .wc-regel >> nth=1';
    await pg.fill(r2 + ' >> [data-r=veld]', 'kosten'); await pg.fill(r2 + ' >> [data-r=patroon]', '^0$'); await pg.selectOption(r2 + ' >> [data-r=zet]', 'gratis');
    ok((await pg.$$('#wcn-regels .wc-regel')).length === 2, 'regel toegevoegd');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#wcn-regels .wc-regel button')].every(b => b.getBoundingClientRect().height >= 44 && b.getAttribute('aria-label'))), 'weghaalknop: 44 px en een naam');
    // zonder licentie: niet bewaren
    const lic = await pg.inputValue('#wcn-lic');
    await pg.fill('#wcn-lic', ''); await pg.click('#wcn-bewaar'); await pg.waitForTimeout(80 * W);
    ok(await cur() === 'wcbron' && /licentie/.test(await toast()), 'zonder licentie niet bewaard');
    await pg.fill('#wcn-lic', 'http-adres'); await pg.fill('#wcn-url', 'http://onveilig.example/data.geojson'); await pg.click('#wcn-bewaar'); await pg.waitForTimeout(80 * W);
    ok(await cur() === 'wcbron' && /https:\/\//.test(await toast()), 'alleen https');
    await pg.fill('#wcn-lic', lic); await pg.fill('#wcn-url', 'https://den-haag-opendata.opendatasoft.com/api/explore/v2.1/catalog/datasets/openbaretoiletten/exports/geojson');
    await pg.click('#wcn-scan'); await pg.waitForTimeout(350 * W);
    ok(await txt('#wcn-h1') === 'Bron' && !(await vis('#wcn-sjabloonkaart')) && await vis('#wcn-menu'), 'na Nu scannen: bewaard');
    ok(/2 plekken \(2 nieuw/.test(await txt('#wcn-stand')), 'eerste scan van Den Haag: 2 plekken');
    const dh = (await wc()).bronnen.find(x => x.id === 'den-haag-openbaar');
    ok(dh && dh.sjabloon === 'den-haag-openbaar' && dh.velden.id === 'globalid' && dh.regels.length === 2 && dh.regels[1].zet === 'gratis' && dh.schema === 'maand', 'bewaard met velden, regels en planning');
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'toiletten-bron-' + scheme + '.png'), fullPage: true });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);
    rijen = await pg.$$eval('#wcbr-list .srow', r => r.map(x => x.textContent));
    ok(rijen.length === 2 && /Den Haag/.test(rijen[1]) && /2 plekken/.test(rijen[1]), 'twee bronnen');

    // ---------- Amsterdam: staat uit (geen open licentie) ----------
    await pg.click('#s-wcbronnen button:has-text("Bron toevoegen")'); await pg.waitForTimeout(150 * W);
    await pg.selectOption('#wcn-sjabloon', 'amsterdam'); await pg.waitForTimeout(50 * W);
    ok((await pg.getAttribute('#wcn-aan', 'aria-checked')) === 'false' && /geen open licentie/.test(await txt('#wcn-tip')), 'Amsterdam: staat uit met de reden');
    await pg.selectOption('#wcn-sjabloon', ''); await pg.waitForTimeout(50 * W);
    ok(await pg.inputValue('#wcn-naam') === '' && await pg.inputValue('#wcn-soort') === 'geojson' && (await pg.getAttribute('#wcn-aan', 'aria-checked')) === 'true', 'zelf invullen: leeg formulier');
    await pg.click('#wcn-bewaar'); await pg.waitForTimeout(80 * W);
    ok(await cur() === 'wcbron' && /naam/.test(await toast()), 'zonder naam niet bewaard');
    await pg.selectOption('#wcn-soort', 'csv'); await pg.waitForTimeout(30 * W);
    ok(await vis('#wcn-veldenkaart') && !(await vis('#wcn-meta')), 'CSV: velden (met breedte- en lengtegraad)');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);

    // ---------- samenvoegen: één plek uit twee bronnen ----------
    await pg.evaluate(() => goBack()); await pg.evaluate(() => goBack()); await pg.waitForTimeout(200 * W);
    ok(await cur() === 'wc', 'terug op Toiletten en douches');
    await pg.evaluate(() => { wcMap.fire('click', { latlng: L.latLng(52.0792, 4.3112) }); }); await pg.waitForTimeout(150 * W);
    const eerste = await pg.$eval('#wc-list .srow', r => r.textContent);
    ok(/Toilet Plein/.test(eerste) && /rolstoel/.test(eerste), 'Den Haag en OpenStreetMap samengevoegd (rolstoel uit de gemeente): ' + eerste);
    ok((await pg.$$eval('#wc-list .srow', r => r.filter(x => /Plein/.test(x.textContent)).length)) === 1, 'niet dubbel in de lijst');
    await pg.click('#wc-list .srow .sbtn >> nth=0'); await pg.waitForTimeout(200 * W);
    const uit = await pg.$$eval('#wcp-body .card:last-child li', l => l.map(x => x.textContent));
    ok(uit.length === 2 && /OpenStreetMap Nederland/.test(uit[0]) && /Den Haag/.test(uit[1]) && /CC0/.test(uit[1]), 'Uit: beide bronnen met licentie');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);

    // ---------- verwijderen ----------
    await pg.click('#wc-set-btn'); await pg.click('#s-wcset .strow:has-text("Bronnen")'); await pg.waitForTimeout(150 * W);
    await pg.click('#wcbr-list .sbtn >> nth=1'); await pg.waitForTimeout(150 * W);
    await pg.click('#wcn-menu'); await pg.waitForTimeout(100 * W);
    await pg.click('#sheet-acts button:has-text("Verwijderen")'); await pg.waitForTimeout(100 * W);
    ok(/Je eigen wijzigingen blijven bewaard/.test(await txt('#modal')), 'eerst bevestigen');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150 * W);
    ok(await cur() === 'wcbronnen' && (await pg.$$('#wcbr-list .srow')).length === 1 && /Bron verwijderd/.test(await toast()), 'bron verwijderd');
    ok(!(await wc()).cache['den-haag-openbaar'], 'gedownloade plekken van de bron weg');

    // ---------- alles bijwerken ----------
    await pg.click('#wcbr-scan'); await pg.waitForTimeout(30 * W);
    ok(await pg.isDisabled('#wcbr-scan') && /Bezig/.test(await txt('#wcbr-st')), 'alles bijwerken: bezig');
    await pg.waitForTimeout(300 * W);
    ok(!(await pg.isDisabled('#wcbr-scan')) && (await txt('#wcbr-st')) === '', 'alles bijgewerkt');
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'toiletten-bronnen-' + scheme + '.png'), fullPage: true });
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})().catch(e => { console.log('errors', [String(e && e.stack || e)]); process.exit(1); });
