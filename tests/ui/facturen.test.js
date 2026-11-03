// Facturen ophalen: leverancier uit het KPN-sjabloon (tabbladen), Opslaan en Koppelingen als eigen schermen,
// Google met rechten (Gmail en Drive), facturen uit Gmail, ophalen, overzicht per maand, planning en fouten.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    const ok = (c, wat) => { if (!c) errs.push('mislukt: ' + wat); };
    const tab = async t => { await pg.click('.fl-tabs button[data-tab="' + t + '"]'); await pg.waitForTimeout(60); };
    const scherm = () => pg.evaluate(() => current);
    // Tegel op het startscherm, kort hoofdscherm met "Meer"
    ok(await pg.isVisible('#tile-facturen'), 'tegel Facturen zichtbaar');
    await pg.click('#tile-facturen'); await pg.waitForTimeout(200);
    ok(await scherm() === 'facturen', 'scherm Facturen open');
    ok((await pg.textContent('#fa-lijst')).includes('Nog geen leveranciers'), 'lege lijst met uitleg');
    ok(!(await pg.isVisible('#fa-ec-url')) && !(await pg.isVisible('#fa-gd-id')), 'geen opslag- of koppelingsvelden op het hoofdscherm');
    ok((await pg.textContent('#fa-sum-opslag')).includes('Nog niets ingesteld') && (await pg.textContent('#fa-sum-kopp')).includes('Nog niets gekoppeld'), 'samenvattingen onder Meer');
    await pg.screenshot({ path: __dirname + '/shots/facturen-leeg-' + scheme + '.png', fullPage: true });
    // KPN Zakelijk toevoegen via het sjabloon: tabbladen Inloggen, Planning, Opslaan, Meer
    await pg.click('#fa-nieuw'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#sheet button:has-text("Ziggo Zakelijk")'), 'sjabloon Ziggo Zakelijk te kiezen');
    await pg.click('#sheet button:has-text("KPN Zakelijk")'); await pg.waitForTimeout(200);
    ok(await scherm() === 'faclev', 'instellingen van de leverancier open');
    ok(await pg.isVisible('.fl-tabs button[data-tab="inlog"]') && !(await pg.isVisible('.fl-tabs button[data-tab="zoek"]')), 'website: tabblad Inloggen, geen Zoeken');
    ok(await pg.isVisible('#fl-start') && !(await pg.isVisible('#fl-schema')) && !(await pg.isVisible('#fl-ec')), 'alleen het tabblad Inloggen zichtbaar');
    ok(await pg.inputValue('#fl-naam') === 'KPN Zakelijk' && await pg.inputValue('#fl-start') === 'https://mijnkpnzakelijk.kpn.com', 'sjabloon ingevuld');
    ok(await pg.inputValue('#fl-bron') === 'sms' && await pg.inputValue('#fl-afz') === 'KPN', 'code per sms van KPN');
    ok(await pg.isVisible('#fl-sms'), 'vraagt om sms-toegang (nog niet gegeven)');
    // Naar Eigen Cloud zonder koppeling: melding en het juiste tabblad
    await tab('opslag'); await pg.check('#fl-ec');
    ok((await pg.textContent('#fl-opslag-msg')).includes('koppel Eigen Cloud'), 'tabblad Opslaan zegt wat nog moet');
    await tab('inlog'); await pg.click('#fl-save'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fl-msg')).includes('Koppel eerst Eigen Cloud') && await pg.isVisible('#fl-ec'), 'fout: springt naar het tabblad Opslaan');
    await pg.uncheck('#fl-ec');
    // Ongeldige inlogpagina
    await tab('inlog'); await pg.fill('#fl-start', 'http://onveilig.nl'); await tab('plan'); await pg.click('#fl-save'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fl-msg')).includes('https://') && await pg.isVisible('#fl-start'), 'alleen https, terug naar Inloggen');
    await pg.fill('#fl-start', 'https://mijnkpnzakelijk.kpn.com');
    await pg.fill('#fl-user', 'rene@esape.nl'); await pg.fill('#fl-pw', 'geheim123');
    await pg.click('#fl-save'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => Android._fa.calls.some(c => c[0] === 'bewaar' && c[1].wachtwoord === 'geheim123' && c[1].code.bron === 'sms' && c[1].soort === 'website')), 'bewaard met wachtwoord en sms-code');
    ok(await pg.evaluate(() => Android._sms.perm), 'sms-toegang gevraagd');
    ok(await pg.isVisible('#modal.on'), 'vraagt of je de eerste keer wilt meekijken');
    await pg.click('#modal button:has-text("Meekijken")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._faMeekijken) === 'kpn-zakelijk', 'meekijken gestart');
    ok(await scherm() === 'facturen', 'terug op Facturen');
    // Nu ophalen
    await pg.click('#fa-lijst .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Nu ophalen")'); await pg.waitForTimeout(700);
    ok(await pg.isVisible('#fa-bezig'), 'voortgang zichtbaar');
    await pg.waitForTimeout(1500);
    ok(!(await pg.isVisible('#fa-bezig')), 'klaar: voortgang weg');
    ok((await pg.textContent('#fa-lijst')).includes('2 nieuwe facturen'), 'status: 2 nieuwe facturen');
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
    await pg.evaluate(() => { Android._faFout = ''; Android._faInlog = false; });
    // Koppelingen → Eigen Cloud (eigen scherm), terug naar Koppelingen
    await pg.click('#s-facturen .strow:has-text("Koppelingen")'); await pg.waitForTimeout(150);
    ok(await scherm() === 'fackopp', 'scherm Koppelingen');
    await pg.click('#s-fackopp .strow:has-text("Eigen Cloud")'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facec', 'scherm Eigen Cloud');
    await pg.fill('#fa-ec-url', 'ftp://x'); await pg.fill('#fa-ec-user', 'rene'); await pg.click('#s-facec button:has-text("Bewaren")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fa-ec-msg')).includes('https://'), 'Eigen Cloud: alleen https');
    await pg.fill('#fa-ec-url', 'https://cloud.esape.nl/'); await pg.fill('#fa-ec-user', 'rene.van.es@esape.nl'); await pg.fill('#fa-ec-pw', 'pw');
    await pg.click('#s-facec button:has-text("Bewaren")'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#fa-ec-stand')).includes('Gekoppeld met cloud.esape.nl'), 'Eigen Cloud gekoppeld');
    await pg.click('#fa-ec-test'); await pg.waitForTimeout(400);
    ok((await pg.textContent('#toast')).includes('Verbinding met Eigen Cloud werkt'), 'test geslaagd');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await scherm() === 'fackopp' && (await pg.textContent('#fa-sum-ec2')).includes('cloud.esape.nl'), 'terug naar Koppelingen, met de stand');
    // Google: rechten kiezen (Gmail en Drive)
    await pg.click('#s-fackopp .strow:has-text("Google")'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facgoogle' && await pg.isChecked('#fa-gd-r-gmail') && await pg.isChecked('#fa-gd-r-drive'), 'Google: Gmail en Drive aangevinkt');
    await pg.fill('#fa-gd-id', 'iets-anders'); await pg.click('#fa-gd-knop'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fa-gd-msg')).includes('googleusercontent'), 'Client ID van Google gecontroleerd');
    await pg.fill('#fa-gd-id', '1234567890-abc123.apps.googleusercontent.com');
    await pg.click('#fa-gd-knop'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fa-gd-msg')).includes('Client secret'), 'secret nodig');
    await pg.uncheck('#fa-gd-r-gmail'); await pg.uncheck('#fa-gd-r-drive'); await pg.fill('#fa-gd-geheim', 'GOCSPX-geheim'); await pg.click('#fa-gd-knop'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fa-gd-msg')).includes('Gmail en/of Drive'), 'minstens één recht kiezen');
    await pg.check('#fa-gd-r-gmail'); await pg.check('#fa-gd-r-drive'); await pg.fill('#fa-gd-geheim', 'GOCSPX-geheim'); await pg.click('#fa-gd-knop'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => Android._fa.calls.some(c => c[0] === 'drive' && c[3] === 'gmail,drive')), 'koppelen met de rechten gmail en drive');
    ok(await pg.isVisible('#fa-gd-aan') && (await pg.textContent('#fa-gd-rechten')).includes('✓ facturen zoeken'), 'Google gekoppeld, rechten zichtbaar');
    ok(await pg.inputValue('#fa-gd-geheim') === '', 'secret niet in het veld laten staan');
    await pg.screenshot({ path: __dirname + '/shots/facturen-google-' + scheme + '.png', fullPage: true });
    // Opslaan: map op de telefoon (eigen scherm); Google Drive via Opslaan gaat terug naar Opslaan
    await pg.evaluate(() => { goBack(); goBack(); }); await pg.waitForTimeout(150);
    await pg.click('#s-facturen .strow:has-text("Opslaan")'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facopslag' && (await pg.textContent('#fa-sum-drive')).includes('Gekoppeld'), 'scherm Opslaan met de stand van Drive');
    await pg.click('#s-facopslag .strow:has-text("Map op je telefoon")'); await pg.waitForTimeout(150);
    await pg.click('#fa-lok-kies'); await pg.waitForTimeout(300);
    ok((await pg.textContent('#fa-lok-stand')).includes('Documents/Facturen'), 'map op de telefoon gekozen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    await pg.click('#s-facopslag .strow:has-text("Google Drive")'); await pg.waitForTimeout(150);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await scherm() === 'facopslag' && (await pg.textContent('#fa-sum-lok')).includes('Documents/Facturen'), 'terug naar Opslaan (niet Koppelingen)');
    await pg.screenshot({ path: __dirname + '/shots/facturen-opslag-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok((await pg.textContent('#fa-sum-opslag')).includes('telefoon') && (await pg.textContent('#fa-sum-opslag')).includes('Drive'), 'samenvatting Opslaan bijgewerkt');
    // Facturen uit Gmail: eigen sjabloon met tabbladen Zoeken, Planning, Opslaan
    await pg.click('#fa-nieuw'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Facturen in Gmail")'); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#fl-m-woorden') && !(await pg.isVisible('.fl-tabs button[data-tab="inlog"]')) && !(await pg.isVisible('.fl-tabs button[data-tab="adv"]')), 'mail: Zoeken, geen Inloggen of Meer');
    ok(!(await pg.isVisible('#fl-m-kopp')), 'Gmail-toegang is er: geen waarschuwing');
    await pg.fill('#fl-m-afz', 'kpn.com, "Ziggo"'); await pg.fill('#fl-m-neg', 'nieuwsbrief@winkel.nl');
    const vraag = await pg.textContent('#fl-m-vraag');
    ok(vraag.includes('has:attachment filename:pdf') && vraag.includes('from:(kpn.com OR Ziggo)') && vraag.includes('-from:nieuwsbrief@winkel.nl') && vraag.includes('filename:factuur'), 'zoekopdracht zichtbaar: ' + vraag);
    await pg.fill('#fl-m-woorden', ''); await pg.fill('#fl-m-afz', ''); await pg.click('#fl-save'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#fl-msg')).includes('zoekwoorden of afzenders'), 'niet zomaar elke PDF');
    await pg.fill('#fl-m-woorden', 'factuur, invoice'); await pg.selectOption('#fl-m-dagen', '365');
    await tab('opslag'); await pg.check('#fl-gd'); await pg.check('#fl-lok');
    await pg.click('#fl-save'); await pg.waitForTimeout(300);
    const gm = await pg.evaluate(() => Android._fa.calls.filter(c => c[0] === 'bewaar').pop()[1]);
    ok(gm.soort === 'mail' && gm.mail.bron === 'gmail' && gm.mail.woorden === 'factuur, invoice' && gm.mail.dagen === 365 && gm.mail.perAfzender === true && gm.drive === true && gm.lokaal === true && gm.schema === 'dag', 'Gmail-leverancier bewaard: ' + JSON.stringify(gm.mail));
    ok(await pg.isVisible('#modal.on') && (await pg.textContent('#modal')).includes('Gmail'), 'vraagt of hij nu moet zoeken');
    await pg.click('#modal button:has-text("Nu zoeken")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._fa.calls.some(c => c[0] === 'nu' && c[1] === 'facturen-in-gmail' && c[2] === false)), 'zoeken gestart (zonder meekijken)');
    await pg.waitForTimeout(1500);
    await pg.click('#fa-lijst .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(150);
    ok(!(await pg.isVisible('#sheet button:has-text("meekijken")')), 'Gmail: geen meekijken in het menu');
    await pg.evaluate(() => closeSheet()); await pg.waitForTimeout(100);
    // Bewerken: Eigen Cloud aan, wachtwoord blijft ongewijzigd
    await pg.click('#fa-lijst .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Instellingen")'); await pg.waitForTimeout(150);
    ok(await pg.getAttribute('#fl-pw', 'placeholder') === 'Ongewijzigd', 'wachtwoord: ongewijzigd');
    await tab('opslag'); await pg.check('#fl-ec'); await pg.check('#fl-lok'); await pg.check('#fl-gd'); await pg.check('#fl-map');
    ok((await pg.textContent('#fl-opslag-msg')).includes('kies een backup-map') && !(await pg.textContent('#fl-opslag-msg')).includes('koppel'), 'alleen de backup-map ontbreekt nog');
    await pg.uncheck('#fl-map');
    ok((await pg.textContent('#fl-opslag-msg')) === '', 'alles ingesteld: geen opmerking');
    await tab('plan'); await pg.selectOption('#fl-schema', 'maand'); await pg.click('#fl-save'); await pg.waitForTimeout(200);
    const laatste = await pg.evaluate(() => Android._fa.calls.filter(c => c[0] === 'bewaar').pop()[1]);
    ok(laatste.id === 'kpn-zakelijk' && laatste.eigenCloud === true && laatste.lokaal === true && laatste.drive === true && laatste.map === false && laatste.schema === 'maand' && laatste.wachtwoord === '', 'bijgewerkt zonder nieuw wachtwoord');
    ok((await pg.textContent('#fa-lijst')).includes('elke maand'), 'elke maand in de lijst');
    // Planning: vast moment en alles instelbaar
    await pg.click('#fa-lijst .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Instellingen")'); await pg.waitForTimeout(150);
    await tab('plan');
    ok(await pg.isVisible('#fl-dag') && !(await pg.isVisible('#fl-wd')) && await pg.isVisible('#fl-tijd'), 'maand: dag en tijd, geen weekdag');
    await pg.selectOption('#fl-dag', '31'); await pg.fill('#fl-tijd', '06:30'); await pg.dispatchEvent('#fl-tijd', 'change');
    ok((await pg.textContent('#fl-plan')).includes('laatste dag') && (await pg.textContent('#fl-plan')).includes('06:30'), 'uitleg: laatste dag om 06:30');
    await pg.selectOption('#fl-schema', 'week'); await pg.waitForTimeout(50);
    ok(await pg.isVisible('#fl-wd') && !(await pg.isVisible('#fl-dag')), 'week: weekdag in plaats van dag');
    await pg.selectOption('#fl-wd', '5');
    ok((await pg.textContent('#fl-plan')).includes('elke vrijdag om 06:30'), 'uitleg: elke vrijdag');
    await pg.selectOption('#fl-schema', 'maand'); await pg.selectOption('#fl-opnieuw', '6'); await pg.check('#fl-wifi'); await pg.uncheck('#fl-meldnieuw');
    ok((await pg.textContent('#fl-plan')).includes('zonder wifi') && (await pg.textContent('#fl-plan')).includes('na 6 uur'), 'uitleg: wifi en na 6 uur');
    await pg.screenshot({ path: __dirname + '/shots/facturen-planning-' + scheme + '.png', fullPage: true });
    await pg.click('#fl-save'); await pg.waitForTimeout(200);
    const plan = await pg.evaluate(() => Android._fa.calls.filter(c => c[0] === 'bewaar').pop()[1]);
    ok(plan.schema === 'maand' && plan.dag === 31 && plan.tijd === '06:30' && plan.weekdag === 5 && plan.opnieuw === 6 && plan.wifi === true && plan.meldNieuw === false && plan.meldFout === true, 'planning bewaard: ' + JSON.stringify([plan.dag, plan.tijd, plan.opnieuw, plan.wifi]));
    ok((await pg.textContent('#fa-lijst')).includes('Volgende keer:') && (await pg.textContent('#fa-lijst')).includes('laatste dag om 06:30'), 'lijst: volgende keer en planning');
    // Inzicht: deze maand, overzicht per maand (eigen scherm), geschiedenis
    ok(await pg.isVisible('#fa-maand') && (await pg.textContent('#fa-maand')).includes('2 facturen binnen'), 'deze maand: 2 facturen binnen');
    await pg.screenshot({ path: __dirname + '/shots/facturen-' + scheme + '.png', fullPage: true });
    await pg.click('#s-facturen .strow:has-text("Overzicht per maand")'); await pg.waitForTimeout(150);
    ok(await scherm() === 'facoverz' && await pg.$$eval('#fa-overzicht-lijst tbody tr', r => r.length) === 12 && (await pg.textContent('#fa-overzicht-lijst tbody tr')).includes('✓ 2'), 'overzicht: 12 maanden, deze maand ✓ 2');
    ok(await pg.$$eval('#fa-overzicht-lijst thead th', r => r.length) === 3, 'overzicht: kolom per leverancier (ook Gmail)');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    await pg.click('#fa-lijst .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Geschiedenis")'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#modal.on') && (await pg.textContent('#modal')).includes('✗') && (await pg.textContent('#modal')).includes('✓'), 'geschiedenis met gelukte en mislukte pogingen');
    await pg.click('#modal button:has-text("Sluiten")'); await pg.waitForTimeout(100);
    // Terug naar huis
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await scherm() === 'home', 'terug naar het startscherm');
    // Openen vanuit een melding "inloggen nodig"
    await pg.evaluate(() => { Android._faMeekijken = ''; openTool('facturen-meekijken:kpn-zakelijk'); }); await pg.waitForTimeout(600);
    ok(await scherm() === 'facturen' && await pg.isVisible('#modal.on'), 'melding opent Facturen met de vraag');
    console.log('errors', errs);
  }
  await b.close();
})();
