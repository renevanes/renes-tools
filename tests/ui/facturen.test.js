// Facturen ophalen: leverancier uit het KPN-sjabloon toevoegen, Eigen Cloud koppelen, ophalen, facturen bekijken, fouten.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    const ok = (c, wat) => { if (!c) errs.push('mislukt: ' + wat); };
    // Tegel op het startscherm
    ok(await pg.isVisible('#tile-facturen'), 'tegel Facturen zichtbaar');
    await pg.click('#tile-facturen'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current) === 'facturen', 'scherm Facturen open');
    ok((await pg.textContent('#fa-lijst')).includes('Nog geen leveranciers'), 'lege lijst met uitleg');
    // KPN Zakelijk toevoegen via het sjabloon
    await pg.click('#fa-nieuw'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("KPN Zakelijk")'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current) === 'faclev', 'instellingen van de leverancier open');
    ok(await pg.inputValue('#fl-naam') === 'KPN Zakelijk' && await pg.inputValue('#fl-start') === 'https://mijnkpnzakelijk.kpn.com', 'sjabloon ingevuld');
    ok(await pg.inputValue('#fl-bron') === 'sms' && await pg.inputValue('#fl-afz') === 'KPN', 'code per sms van KPN');
    ok(await pg.isVisible('#fl-sms'), 'vraagt om sms-toegang (nog niet gegeven)');
    // Naar Eigen Cloud zonder koppeling: duidelijke melding
    await pg.check('#fl-ec'); await pg.click('#fl-save'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fl-msg')).includes('Koppel eerst Eigen Cloud'), 'eerst Eigen Cloud koppelen');
    await pg.uncheck('#fl-ec');
    // Ongeldige inlogpagina
    await pg.fill('#fl-start', 'http://onveilig.nl'); await pg.click('#fl-save'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fl-msg')).includes('https://'), 'alleen https');
    await pg.fill('#fl-start', 'https://mijnkpnzakelijk.kpn.com');
    await pg.fill('#fl-user', 'rene@esape.nl'); await pg.fill('#fl-pw', 'geheim123');
    await pg.click('#fl-save'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => Android._fa.calls.some(c => c[0] === 'bewaar' && c[1].wachtwoord === 'geheim123' && c[1].code.bron === 'sms')), 'bewaard met wachtwoord en sms-code');
    ok(await pg.evaluate(() => Android._sms.perm), 'sms-toegang gevraagd');
    ok(await pg.isVisible('#modal.on'), 'vraagt of je de eerste keer wilt meekijken');
    await pg.click('#modal button:has-text("Meekijken")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._faMeekijken) === 'kpn-zakelijk', 'meekijken gestart');
    ok(await pg.evaluate(() => current) === 'facturen', 'terug op Facturen');
    ok((await pg.textContent('#fa-lijst')).includes('KPN Zakelijk'), 'KPN Zakelijk in de lijst');
    // Nu ophalen
    await pg.click('#fa-lijst .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Nu ophalen")'); await pg.waitForTimeout(700);
    ok(await pg.isVisible('#fa-bezig'), 'voortgang zichtbaar');
    await pg.waitForTimeout(1500);
    ok(!(await pg.isVisible('#fa-bezig')), 'klaar: voortgang weg');
    ok((await pg.textContent('#fa-lijst')).includes('2 nieuwe facturen'), 'status: 2 nieuwe facturen');
    await pg.screenshot({ path: __dirname + '/shots/facturen-' + scheme + '.png', fullPage: true });
    // Facturen bekijken en openen
    await pg.click('#fa-lijst .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Facturen bekijken (2)")'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Factuur 2026-10")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._fa.calls.some(c => c[0] === 'open' && c[2] === 'KPN Zakelijk Factuur 2026-10.pdf')), 'factuur geopend');
    // Inloggen nodig: aanbod om mee te kijken
    await pg.evaluate(() => { Android._faFout = 'De inlogcode werd niet geaccepteerd'; Android._faInlog = true; Android._faMeekijken = ''; });
    await pg.evaluate(() => faNu('kpn-zakelijk', false)); await pg.waitForTimeout(2100);
    ok((await pg.textContent('#fa-lijst')).includes('De inlogcode werd niet geaccepteerd'), 'fout in de lijst');
    ok(await pg.isVisible('#modal.on') && (await pg.textContent('#modal')).includes('Inloggen nodig'), 'vraagt om mee te kijken');
    await pg.click('#modal button:has-text("Meekijken")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._faMeekijken) === 'kpn-zakelijk', 'meekijken na fout');
    // Eigen Cloud koppelen en testen
    await pg.fill('#fa-ec-url', 'ftp://x'); await pg.fill('#fa-ec-user', 'rene'); await pg.click('#s-facturen button:has-text("Bewaren")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fa-ec-msg')).includes('https://'), 'Eigen Cloud: alleen https');
    await pg.fill('#fa-ec-url', 'https://cloud.esape.nl/'); await pg.fill('#fa-ec-user', 'rene.van.es@esape.nl'); await pg.fill('#fa-ec-pw', 'pw');
    await pg.click('#s-facturen button:has-text("Bewaren")'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#fa-ec-stand')).includes('Gekoppeld met cloud.esape.nl'), 'Eigen Cloud gekoppeld');
    await pg.click('#fa-ec-test'); await pg.waitForTimeout(400);
    ok((await pg.textContent('#toast')).includes('Verbinding met Eigen Cloud werkt'), 'test geslaagd');
    // Map op de telefoon en Google Drive
    await pg.click('#fa-lok-kies'); await pg.waitForTimeout(300);
    ok((await pg.textContent('#fa-lok-stand')).includes('Documents/Facturen'), 'map op de telefoon gekozen');
    ok(await pg.isVisible('#fa-gd-setup'), 'stappen voor Google Drive zichtbaar');
    await pg.fill('#fa-gd-id', 'iets-anders'); await pg.click('#fa-gd button:has-text("Koppelen met Google Drive")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fa-gd-msg')).includes('googleusercontent'), 'Client ID van Google gecontroleerd');
    await pg.fill('#fa-gd-id', '1234567890-abc123.apps.googleusercontent.com');
    await pg.click('#fa-gd button:has-text("Koppelen met Google Drive")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fa-gd-msg')).includes('Client secret'), 'secret nodig');
    await pg.fill('#fa-gd-geheim', 'GOCSPX-geheim'); await pg.click('#fa-gd button:has-text("Koppelen met Google Drive")'); await pg.waitForTimeout(400);
    ok(await pg.isVisible('#fa-gd-aan') && (await pg.textContent('#fa-gd-stand')).includes('rene@gmail.com'), 'Google Drive gekoppeld');
    ok(await pg.inputValue('#fa-gd-geheim') === '', 'secret niet in het veld laten staan');
    // Bewerken: Eigen Cloud aan, wachtwoord blijft ongewijzigd
    await pg.evaluate(() => { Android._faFout = ''; });
    await pg.click('#fa-lijst .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Instellingen")'); await pg.waitForTimeout(150);
    ok(await pg.getAttribute('#fl-pw', 'placeholder') === 'Ongewijzigd', 'wachtwoord: ongewijzigd');
    await pg.check('#fl-ec'); await pg.check('#fl-lok'); await pg.check('#fl-gd'); await pg.selectOption('#fl-schema', 'maand'); await pg.click('#fl-save'); await pg.waitForTimeout(200);
    const laatste = await pg.evaluate(() => Android._fa.calls.filter(c => c[0] === 'bewaar').pop()[1]);
    ok(laatste.id === 'kpn-zakelijk' && laatste.eigenCloud === true && laatste.lokaal === true && laatste.drive === true && laatste.schema === 'maand' && laatste.wachtwoord === '', 'bijgewerkt zonder nieuw wachtwoord');
    ok((await pg.textContent('#fa-lijst')).includes('elke maand'), 'elke maand in de lijst');
    // Terug naar huis
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current) === 'home', 'terug naar het startscherm');
    // Openen vanuit een melding "inloggen nodig"
    await pg.evaluate(() => { Android._faMeekijken = ''; openTool('facturen-meekijken:kpn-zakelijk'); }); await pg.waitForTimeout(600);
    ok(await pg.evaluate(() => current) === 'facturen' && await pg.isVisible('#modal.on'), 'melding opent Facturen met de vraag');
    console.log('errors', errs);
  }
  await b.close();
})();
