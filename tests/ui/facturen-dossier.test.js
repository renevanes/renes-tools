// Facturen: dossier (gegevens, plekken, aanpassen, CSV) en aanleveren aan de boekhouder per e-mail
// (ontvanger toevoegen, koppeling nodig, proef, versturen, niet dubbel, opnieuw, mislukt, eigen mailserver).
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
    // Een leverancier met twee opgehaalde facturen
    await pg.evaluate(() => { const f = Android._fa; f.lev.push({ id: 'kpn-zakelijk', naam: 'KPN Zakelijk', soort: 'website', schema: 'maand', dag: 5, tijd: '08:00', startUrl: 'https://mijnkpnzakelijk.kpn.com' }); });
    await pg.click('#tile-facturen'); await pg.waitForTimeout(200);
    ok((await tekst('#fa-sum-dos')).includes('Nog leeg'), 'dossier nog leeg');
    await pg.click('#s-facturen button:has-text("Dossier")'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facdos' && (await tekst('#fd-lijst')).includes('Nog geen facturen'), 'leeg dossier met uitleg');
    ok(await pg.isDisabled('#fd-csv'), 'exporteren uit zonder facturen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    await pg.evaluate(() => faNu('kpn-zakelijk', false)); await pg.waitForTimeout(1500);
    ok((await tekst('#fa-sum-dos')).includes('2 facturen'), 'dossier telt 2 facturen');
    // Dossier: lijst, zoeken, detail
    await pg.click('#s-facturen button:has-text("Dossier")'); await pg.waitForTimeout(150);
    ok((await pg.$$('#fd-lijst tr.fd-rij')).length === 2, 'twee facturen in het dossier');
    ok((await tekst('#fd-tel')).includes('€ 93,47'), 'som van de bedragen');
    await pg.fill('#fd-zoek', '48,37'); await pg.waitForTimeout(80);
    ok((await pg.$$('#fd-lijst tr.fd-rij')).length === 1, 'zoeken op bedrag');
    await pg.fill('#fd-zoek', ''); await pg.waitForTimeout(80);
    // Tabel: standaardkolommen, sorteren, kolommen kiezen, groeperen, filter op betaaldatum
    const koppen = () => pg.$$eval('#fd-lijst thead th', t => t.map(x => x.textContent.replace(/[↑↓]/g, '').trim()));
    ok(JSON.stringify(await koppen()) === JSON.stringify(['Factuurdatum', 'Leverancier', 'Bedrag', 'Uiterste betaaldatum', 'Aangeleverd']), 'standaardkolommen');
    ok((await tekst('#fd-lijst tbody tr.fd-rij >> nth=0')).includes('5 okt 2026'), 'nieuwste factuurdatum bovenaan');
    ok((await tekst('#fd-lijst tfoot')).includes('€ 93,47'), 'totaal onder de tabel');
    ok((await tekst('#fd-lijst tbody tr.fd-rij >> nth=1')).includes('⚠'), 'over de betaaldatum gemarkeerd');
    await pg.click('#fd-lijst thead button[data-k="bedrag"]'); await pg.waitForTimeout(60);
    ok((await tekst('#fd-lijst tbody tr.fd-rij >> nth=0')).includes('48,37') && await pg.getAttribute('#fd-lijst th:has(button[data-k="bedrag"])', 'aria-sort') === 'descending', 'sorteren op bedrag (hoog eerst)');
    await pg.click('#fd-lijst thead button[data-k="bedrag"]'); await pg.waitForTimeout(60);
    ok((await tekst('#fd-lijst tbody tr.fd-rij >> nth=0')).includes('45,10'), 'nog een keer: laag eerst');
    await pg.click('#fd-kol-knop'); await pg.waitForTimeout(60);
    ok(await pg.isVisible('#fd-kol') && await pg.getAttribute('#fd-kol-knop', 'aria-expanded') === 'true', 'kolommen kiezen open');
    await pg.check('#fd-kol-lijst input[value="nummer"]'); await pg.uncheck('#fd-kol-lijst input[value="aan"]'); await pg.waitForTimeout(60);
    ok((await koppen()).includes('Factuurnummer') && !(await koppen()).includes('Aangeleverd') && (await tekst('#fd-lijst')).includes('800123450'), 'kolom toegevoegd en weggehaald');
    await pg.selectOption('#fd-groep', 'maand'); await pg.waitForTimeout(60);
    ok((await pg.$$('#fd-lijst tr.fd-groep')).length === 2 && (await tekst('#fd-lijst tr.fd-groep >> nth=0')).includes('oktober 2026'), 'gegroepeerd per maand, nieuwste maand eerst');
    ok((await tekst('#fd-lijst tr.fd-groep >> nth=0')).includes('€ 48,37'), 'subtotaal per groep');
    await pg.selectOption('#fd-filter', 'telaat'); await pg.waitForTimeout(60);
    ok((await pg.$$('#fd-lijst tr.fd-rij')).length === 1, 'filter: over de betaaldatum');
    await pg.selectOption('#fd-filter', ''); await pg.waitForTimeout(60);
    await pg.screenshot({ path: __dirname + '/shots/facturen-dossier-tabel-' + scheme + '.png', fullPage: true });
    // Keuzes blijven bewaard (opnieuw openen)
    await pg.evaluate(() => { goBack(); faNaar('facdos'); }); await pg.waitForTimeout(150);
    ok((await koppen()).includes('Factuurnummer') && await pg.inputValue('#fd-groep') === 'maand', 'weergave onthouden');
    await pg.selectOption('#fd-groep', ''); await pg.click('#fd-kol-knop'); await pg.waitForTimeout(60);
    await pg.check('#fd-kol-lijst input[value="aan"]'); await pg.click('#fd-kol-knop'); await pg.waitForTimeout(60);
    await pg.click('#fd-lijst thead button[data-k="datum"]'); await pg.waitForTimeout(60);
    if (await pg.getAttribute('#fd-lijst th:has(button[data-k="datum"])', 'aria-sort') !== 'descending') { await pg.click('#fd-lijst thead button[data-k="datum"]'); await pg.waitForTimeout(60); }
    await pg.screenshot({ path: __dirname + '/shots/facturen-dossier-' + scheme + '.png', fullPage: true });
    await pg.click('#fd-lijst tr.fd-rij >> nth=0'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facstuk', 'detail van een factuur');
    ok((await tekst('#fs-info')).includes('KPN Zakelijk Factuur 2026-10.pdf') && (await tekst('#fs-plekken')).includes('In de app'), 'bestand en plek');
    ok(await pg.inputValue('#fs-nummer') === '800123450' && await pg.inputValue('#fs-datum') === '2026-10-05' && (await tekst('#fs-bron')).includes('Herkend'), 'herkende gegevens');
    ok((await tekst('#fs-aan')).includes('Nog geen ontvanger'), 'nog geen ontvanger');
    await pg.click('#fs-open'); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => Android._fa.calls.some(c => c[0] === 'open' && c[2] === 'KPN Zakelijk Factuur 2026-10.pdf')), 'openen in de PDF-viewer');
    await pg.fill('#fs-bedrag', 'veel'); await pg.click('#s-facstuk button:has-text("Bewaren")'); await pg.waitForTimeout(80);
    ok((await tekst('#fs-msg')).includes('123,45'), 'ongeldig bedrag geweigerd');
    ok(await pg.inputValue('#fs-verval') === '2099-10-19', 'uiterste betaaldatum ingevuld');
    await pg.click('#s-facstuk button:has-text("Vandaag betaald")'); await pg.waitForTimeout(120);
    ok(await pg.inputValue('#fs-betaald') === await pg.evaluate(() => new Date().toLocaleDateString('sv-SE')), 'vandaag betaald');
    await pg.fill('#fs-bedrag', '50,00'); await pg.fill('#fs-cat', 'telefoon'); await pg.click('#s-facstuk button:has-text("Bewaren")'); await pg.waitForTimeout(100);
    ok((await tekst('#fs-bron')).includes('Door jou') && await pg.inputValue('#fs-cat') === 'telefoon', 'gegevens bewaard');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await scherm() === 'facdos' && (await tekst('#fd-tel')).includes('€ 95,10'), 'terug naar het dossier, som bijgewerkt');
    // Periode: zelf kiezen (van/tot), alleen die facturen
    await pg.selectOption('#fd-periode', 'zelf'); await pg.waitForTimeout(60);
    ok(await pg.isVisible('#fd-van') && await pg.isVisible('#fd-tot'), 'eigen periode: van en tot');
    await pg.fill('#fd-van', '2026-10-01'); await pg.fill('#fd-tot', '2026-10-31'); await pg.dispatchEvent('#fd-tot', 'change'); await pg.waitForTimeout(80);
    ok((await pg.$$('#fd-lijst tr.fd-rij')).length === 1 && (await tekst('#fd-lijst')).includes('5 okt 2026'), 'alleen oktober');
    await pg.selectOption('#fd-periode', ''); await pg.waitForTimeout(60);
    ok((await pg.$$('#fd-lijst tr.fd-rij')).length === 2 && !(await pg.isVisible('#fd-van')), 'weer alle data');
    // Eerder opgehaalde facturen zonder gegevens: worden bij het openen uit de PDF gelezen
    await pg.evaluate(() => { const f = Android._fa; f.dos.oud1 = { sha: 'oud1', naam: 'KPN 1.pdf', leverancier: 'kpn-zakelijk', leverancierNaam: 'KPN Zakelijk', opgehaald: new Date('2026-08-20T10:00').getTime(), grootte: 1, bron: { soort: 'website' }, plekken: { app: { pad: '/x' } }, verzonden: {}, meta: { bron: '' }, _pdf: { datum: '2026-08-05', bedrag: '40,00', vervaldatum: '2026-08-19' } };
      f.dos.oud2 = { sha: 'oud2', naam: 'KPN 2.pdf', leverancier: 'kpn-zakelijk', leverancierNaam: 'KPN Zakelijk', opgehaald: new Date('2026-07-20T10:00').getTime(), grootte: 1, bron: { soort: 'website' }, plekken: { app: { pad: '/x' } }, verzonden: {}, meta: { bron: '' } };
      goBack(); faNaar('facdos'); });
    await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => Android._fa.calls.some(c => c[0] === 'herken')), 'ontbrekende gegevens worden gelezen');
    ok((await tekst('#fd-lees')).includes('Bij 1 factuur'), 'melding: gegevens gevonden');
    ok((await tekst('#fd-lijst')).includes('5 aug 2026') && (await tekst('#fd-lijst')).includes('€ 40,00'), 'gelezen datum en bedrag in de tabel');
    ok((await tekst('#fd-lijst')).includes('20 jul 2026 *') && (await tekst('#fd-lijst')).includes('dag van ophalen'), 'onbekende factuurdatum: dag van ophalen met *');
    await pg.evaluate(() => { const f = Android._fa; delete f.dos.oud1; delete f.dos.oud2; fdRender(true); }); await pg.waitForTimeout(60);
    await pg.click('#fd-csv'); await pg.waitForTimeout(250);
    ok((await tekst('#fd-csv-msg')).includes('Kies eerst'), 'CSV zonder map: zegt wat te doen');
    // Aanleveren: ontvanger toevoegen (Eigen Cloud nog niet gekoppeld)
    await pg.evaluate(() => { goBack(); }); await pg.waitForTimeout(100);
    await pg.click('#s-facturen button:has-text("Aanleveren")'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facaanl' && (await tekst('#fo-lijst')).includes('Nog geen ontvanger'), 'aanleveren: lege lijst');
    await pg.click('#fo-nieuw'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facontv' && await pg.isVisible('#fo-aan'), 'formulier ontvanger');
    ok((await tekst('#fo-via-msg')).includes('Koppel eerst Eigen Cloud'), 'zegt dat Eigen Cloud nog gekoppeld moet worden');
    ok(await pg.isVisible('#fo-dagrij') && !(await pg.isVisible('#fo-wdrij')), 'maandelijks: dag van de maand');
    await pg.selectOption('#fo-schema', 'direct'); await pg.waitForTimeout(50);
    ok(!(await pg.isVisible('#fo-tijdrij')) && (await tekst('#fo-plan')).includes('Zodra'), 'direct: geen tijd');
    await pg.fill('#fo-naam', 'Boekhouder Jansen'); await pg.fill('#fo-aan', 'geen-adres');
    await pg.click('#s-facontv button:has-text("Bewaren")'); await pg.waitForTimeout(80);
    ok((await tekst('#fo-msg')).includes('klopt niet'), 'ongeldig adres geweigerd');
    await pg.fill('#fo-aan', 'facturen@jansen.nl'); await pg.check('#fo-oud');
    await pg.click('#s-facontv button:has-text("Bewaren")'); await pg.waitForTimeout(400);
    ok(await scherm() === 'facaanl' && await pg.isVisible('#modal.on') && (await tekst('#modal')).includes('Koppel eerst Eigen Cloud'), 'na bewaren: koppeling nodig');
    await pg.click('#modal button:has-text("Nu instellen")'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facec', 'naar Eigen Cloud');
    await pg.fill('#fa-ec-url', 'https://cloud.esape.nl'); await pg.fill('#fa-ec-user', 'rene@esape.nl'); await pg.fill('#fa-ec-pw', 'x');
    await pg.click('#s-facec button:has-text("Bewaren")'); await pg.waitForTimeout(100);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await scherm() === 'facaanl' && (await tekst('#fo-lijst')).includes('2 facturen klaar'), 'terug: 2 facturen klaar (ook de oude)');
    await pg.screenshot({ path: __dirname + '/shots/facturen-aanleveren-' + scheme + '.png', fullPage: true });
    // Proef naar jezelf, dan echt versturen
    await pg.click('#fo-lijst .sbtn'); await pg.waitForTimeout(120);
    await pg.click('#sheet button:has-text("Proef naar mezelf")'); await pg.waitForTimeout(500);
    ok(await pg.evaluate(() => Android._fa.calls.some(c => c[0] === 'aanlever' && c[2] === true)), 'proef verstuurd');
    ok((await tekst('#fo-lijst')).includes('2 facturen klaar'), 'proef telt niet als aangeleverd');
    await pg.click('#fo-lijst .sbtn'); await pg.waitForTimeout(120);
    await pg.click('#sheet button:has-text("Nu versturen")'); await pg.waitForTimeout(500);
    ok((await tekst('#fo-lijst')).includes('Niets open') && (await tekst('#fo-lijst')).includes('✓ Laatst'), 'verstuurd: niets meer open');
    await pg.click('#fo-lijst .sbtn'); await pg.waitForTimeout(120);
    await pg.click('#sheet button:has-text("Nu versturen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._fa.calls.filter(c => c[0] === 'aanlever' && !c[2]).length) === 1, 'niet dubbel versturen');
    // In het dossier: aangeleverd; opnieuw aanleveren
    await pg.evaluate(() => { faNaar('facdos'); }); await pg.waitForTimeout(150);
    ok((await tekst('#fd-lijst tbody tr.fd-rij >> nth=0')).includes('✓'), 'dossier toont aangeleverd');
    await pg.click('#fd-lijst tr.fd-rij >> nth=0'); await pg.waitForTimeout(150);
    ok((await tekst('#fs-aan')).includes('facturen@jansen.nl'), 'detail: aan wie');
    await pg.click('#fs-aan button:has-text("Opnieuw")'); await pg.waitForTimeout(100);
    await pg.click('#modal button:has-text("Opnieuw")'); await pg.waitForTimeout(150);
    ok((await tekst('#fs-aan')).includes('Gaat opnieuw'), 'gaat opnieuw');
    // Mislukt versturen: in het dossier zichtbaar en te filteren
    await pg.evaluate(() => { Android._foFout = 'De mailserver weigert het adres'; faNaar('facaanl'); }); await pg.waitForTimeout(150);
    ok((await tekst('#fo-lijst')).includes('1 factuur klaar'), 'een factuur weer klaar');
    await pg.evaluate(() => foNu('boekhouder-jansen', false)); await pg.waitForTimeout(500);
    ok((await tekst('#fo-lijst')).includes('✗'), 'mislukt zichtbaar');
    await pg.evaluate(() => faNaar('facdos')); await pg.waitForTimeout(150);
    await pg.selectOption('#fd-filter', 'mis'); await pg.waitForTimeout(80);
    ok((await pg.$$('#fd-lijst tr.fd-rij')).length === 1 && (await tekst('#fd-lijst')).includes('✗'), 'filter op mislukt');
    // Eigen mailserver
    await pg.evaluate(() => { faNaar('fackopp'); }); await pg.waitForTimeout(150);
    ok((await tekst('#fa-sum-smtp')).includes('Niet ingesteld'), 'mailserver niet ingesteld');
    await pg.click('#s-fackopp button:has-text("Eigen mailserver")'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facsmtp', 'scherm mailserver');
    await pg.selectOption('#fm-bev', 'ssl'); await pg.waitForTimeout(50);
    ok(await pg.inputValue('#fm-poort') === '465', 'SSL: poort 465');
    await pg.fill('#fm-host', 'smtp.esape.nl'); await pg.fill('#fm-van', 'geen');
    await pg.click('#s-facsmtp button:has-text("Bewaren")'); await pg.waitForTimeout(80);
    ok((await tekst('#fm-msg')).includes('afzender'), 'afzender verplicht');
    await pg.fill('#fm-van', 'rene@esape.nl'); await pg.fill('#fm-user', 'rene'); await pg.fill('#fm-pw', 'geheim');
    await pg.click('#s-facsmtp button:has-text("Bewaren")'); await pg.waitForTimeout(100);
    ok((await tekst('#fm-stand')).includes('smtp.esape.nl:465') && await pg.inputValue('#fm-pw') === '', 'mailserver bewaard, wachtwoord niet zichtbaar');
    await pg.click('#fm-test'); await pg.waitForTimeout(250);
    ok((await tekst('#fm-msg')) === '', 'test gelukt');
    await pg.screenshot({ path: __dirname + '/shots/facturen-smtp-' + scheme + '.png', fullPage: true });
    // Google: recht om te versturen
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    await pg.click('#s-fackopp button:has-text("Google")'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#fa-gd-r-send') && !(await pg.isChecked('#fa-gd-r-send')), 'Google: versturen staat standaard uit');
    await pg.check('#fa-gd-r-send'); await pg.fill('#fa-gd-id', '123-abc.apps.googleusercontent.com'); await pg.fill('#fa-gd-geheim', 's');
    await pg.click('#fa-gd-knop'); await pg.waitForTimeout(350);
    ok(await pg.evaluate(() => Android._fa.calls.some(c => c[0] === 'drive' && /gmailsend/.test(c[3]))), 'koppelen met het recht om te versturen');
    ok((await tekst('#fa-gd-rechten')).includes('✓ aan je boekhouder'), 'recht zichtbaar');
    // Terug tot het startscherm
    for (let i = 0; i < 8 && await scherm() !== 'home'; i++) { await pg.evaluate(() => goBack()); await pg.waitForTimeout(100); }
    ok(await scherm() === 'home', 'terug naar het startscherm');
    console.log('errors', errs);
  }
  await b.close();
})();
