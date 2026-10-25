// Voortgang van updates: een nieuwe app downloaden (procenten, MB, controleren, bevestigen) en een interface-update.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); };
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    const until = (f, arg) => pg.waitForFunction(f, arg, { timeout: 5000 }).then(() => true, () => false);

    // Nieuwe versie beschikbaar: balk met knop, nog geen voortgang
    await pg.evaluate(() => onUpdateStatus({ state: 'apk', versionName: '9.9' }));
    ok(await pg.isVisible('#upd-banner') && (await pg.textContent('#upd-text')).includes('9.9') && !(await pg.isVisible('#upd-prog')), 'balk: nieuwe versie beschikbaar');

    // Installeren: voortgang in procenten en MB, knop uit
    await pg.evaluate(() => { Android._updMs = 120; });
    await pg.click('#upd-btn');
    ok(await pg.isVisible('#upd-prog') && await pg.isDisabled('#upd-btn'), 'voortgang zichtbaar, knop uit');
    ok(await until(() => /Downloaden… \d+% \(\d,\d MB van 5,0 MB\)/.test(document.querySelector('#upd-text').textContent)), 'downloaden met procenten en MB (' + (await pg.textContent('#upd-text')) + ')');
    const pct = await pg.getAttribute('#upd-prog', 'aria-valuenow');
    ok(pct !== null && +pct >= 0 && +pct <= 100, 'progressbar met waarde (' + pct + ')');
    // Nog een tik tijdens het downloaden: geen tweede download
    await pg.evaluate(() => installUpdate());
    ok((await pg.textContent('#toast')).includes('al gedownload'), 'tweede tik: wordt al gedownload');
    await pg.screenshot({ path: __dirname + '/shots/update-voortgang-' + scheme + '.png' });
    ok(await until(() => document.querySelector('#upd-text').textContent.includes('Controleren')), 'controleren');
    ok(await until(() => document.querySelector('#upd-text').textContent.includes('Klaarzetten')), 'klaarzetten');
    ok(await until(() => document.querySelector('#upd-text').textContent.includes('Bevestig de installatie')), 'bevestigen in het venster van Android');
    ok(!(await pg.isVisible('#upd-prog')) && !(await pg.isDisabled('#upd-btn')), 'na bevestigen: knop weer te gebruiken');
    // Ook op het scherm Versie en updates
    await pg.evaluate(() => show('info')); await pg.waitForTimeout(150);
    ok((await pg.textContent('#upd-text2')).includes('Bevestig'), 'zelfde stand bij Versie en updates');

    // Afgebroken: terug naar "beschikbaar", met een melding
    await pg.evaluate(() => { Android._updFail = true; Android._updMs = 20; });
    await pg.click('#upd-btn2');
    ok(await until(() => document.querySelector('#upd-text2').textContent.includes('beschikbaar') && !Android._upd), 'na afbreken weer beschikbaar');
    ok((await pg.textContent('#toast')).includes('afgebroken'), 'melding bij afbreken');

    // Interface-update (stil): voortgang zonder knop, ook op de knop Controleren
    await pg.evaluate(() => { updAvail = ''; onUpdateStatus({ state: 'progress', kind: 'web', phase: 'download', done: 734003, total: 1468006 }); });
    ok((await pg.textContent('#upd-text2')).includes('Interface bijwerken… 50%') && !(await pg.isVisible('#upd-btn2')), 'interface bijwerken: 50% zonder knop');
    ok((await pg.textContent('#chk-btn')).includes('50%'), 'knop Controleren toont de voortgang');
    await pg.evaluate(() => onUpdateStatus({ state: 'progress', kind: 'web', phase: 'download2', done: 1000, total: 0 }));
    ok((await pg.textContent('#upd-text2')).includes('Startscherm bijwerken') && await pg.evaluate(() => document.querySelector('#upd-prog2').classList.contains('busy')), 'zonder grootte: bezig-balk');
    await pg.evaluate(() => onUpdateStatus({ state: 'error', error: 'HTTP 500' }));
    ok(!(await pg.isVisible('#upd-banner2')) && (await pg.textContent('#chk-btn')) === 'Controleren op updates', 'fout bij interface-update: balk weg');
    // Schermlezer: fase-wissel wordt gemeld, de procenten niet
    ok(await pg.evaluate(() => !/\d+%/.test(document.querySelector('#upd-live').textContent)), 'voorleesregel zonder procenten');

    // Uitkomst van een controle op de achtergrond tijdens het downloaden van de app: voortgang blijft staan
    await pg.evaluate(() => { Android._updFail = false; Android._updMs = 150; updAvail = '9.9'; updAvailShow(); });
    await pg.click('#upd-btn2');
    ok(await until(() => /Downloaden…/.test(document.querySelector('#upd-text2').textContent)), 'downloaden begonnen');
    await pg.evaluate(() => onUpdateStatus({ state: 'uptodate', kind: 'web' }));
    ok((await pg.textContent('#upd-text2')).includes('Downloaden') && await pg.isDisabled('#upd-btn2'), 'controle op de achtergrond verstoort de voortgang niet');
    ok(await until(() => document.querySelector('#upd-text2').textContent.includes('Bevestig')), 'gaat gewoon door tot bevestigen');

    // "Al bezig" van de app terwijl de pagina niets weet (bijv. na opnieuw laden): balk laat dat zien
    await pg.evaluate(() => { updReset(); onUpdateStatus({ state: 'busy', kind: 'apk' }); });
    ok((await pg.textContent('#upd-text2')).includes('al gedownload') && await pg.isDisabled('#upd-btn2'), 'al bezig: balk met bezig-knop');
    // Geen bericht meer (geen antwoord van de installer): na de wachttijd niet blijven hangen
    await pg.evaluate(() => { updDogMs = 300; onUpdateStatus({ state: 'progress', kind: 'apk', phase: 'committed', done: 0, total: 0 }); });
    ok(await until(() => !document.querySelector('#upd-btn2').disabled && document.querySelector('#upd-text2').textContent.includes('beschikbaar')), 'zonder antwoord: na de wachttijd weer te gebruiken');
    // Toestemming nodig: knop weer bruikbaar
    await pg.evaluate(() => { updDogMs = 90000; onUpdateStatus({ state: 'progress', kind: 'apk', phase: 'start', done: 0, total: 0 }); onUpdateStatus({ state: 'needs-permission', kind: 'apk' }); });
    ok(!(await pg.isDisabled('#upd-btn2')) && (await pg.textContent('#toast')).includes('onbekende apps'), 'toestemming nodig: knop weer bruikbaar');

    console.log('errors', errs); await pg.close();
  }
  await b.close();
})();
