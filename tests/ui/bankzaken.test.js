// Bankzaken: ontgrendelen, importeren, overzicht, boekingen, regels, lokale AI, automatisch ophalen, op slot en wissen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    pg.on('dialog', d => { errs.push('dialoog: ' + d.message()); d.dismiss(); });
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    await pg.evaluate(() => { window.BK_VANDAAG = '2026-10-31'; });
    const ok = (c, wat) => { if (!c) errs.push('mislukt: ' + wat); };
    const tekst = async s => (await pg.textContent(s)) || '';

    // Tegel en op slot
    ok(await pg.isVisible('#tile-bank'), 'tegel Bankzaken zichtbaar');
    await pg.click('#tile-bank'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current) === 'bank', 'scherm Bankzaken open');
    ok(await pg.isVisible('#bk-dicht') && !(await pg.isVisible('#bk-los')), 'begint op slot');
    ok(await pg.evaluate(() => Android._bk.secure), 'schermbeveiliging aan (geen schermafdrukken)');
    // Ontgrendelen mislukt: melding
    await pg.evaluate(() => { Android._bk.ontgrendelFout = 'Niet ontgrendeld'; });
    await pg.click('#bk-open-knop'); await pg.waitForTimeout(150);
    ok((await tekst('#bk-msg')).includes('Niet ontgrendeld') && !(await pg.isVisible('#bk-los')), 'mislukte ontgrendeling: blijft op slot');
    await pg.evaluate(() => { Android._bk.ontgrendelFout = ''; });
    await pg.click('#bk-open-knop'); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#bk-los') && await pg.isVisible('#bk-leeg'), 'open en nog leeg');
    ok((await tekst('#bk-leeg')).includes('Rabobank'), 'Rabobank automatisch ophalen aangeboden');

    // Automatisch ophalen bij de Rabobank (nog niets: 13 maanden terug)
    await pg.click('#bk-leeg .bk-auto-lijst button'); await pg.waitForTimeout(100);
    const k = await pg.evaluate(() => Android._bk.calls.find(c => c[0] === 'koppel'));
    ok(k && k[1] === 'rabobank' && /^2025-(09|10)-/.test(k[2]), 'ophalen gestart voor Rabobank vanaf 13 maanden terug: ' + JSON.stringify(k));
    // Uitkomst van het ophalen, na terugkomen in de app
    await pg.evaluate(() => { Android._bk.uitkomst = JSON.stringify({ bank: 'Rabobank', bestanden: [{ ok: true, sum: { nieuw: 0, dubbel: 0 } }] }); onResumeApp(); });
    await pg.waitForTimeout(150);
    ok((await tekst('#toast')).includes('Rabobank: 0 nieuwe boekingen'), 'melding na automatisch ophalen');

    // Zelf importeren
    await pg.click('#bk-leeg button.big'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => JSON.stringify(Android._bk.calls.find(c => c[0] === 'import'))) === '["import",true]', 'importeren: bronbestand verwijderen staat standaard aan');
    ok(await pg.isVisible('#modal.on') && (await tekst('#modal')).includes('nieuwe boekingen') && (await tekst('#modal')).includes('foto.jpg'), 'uitkomst met nieuwe boekingen en wat niet lukte');
    await pg.click('#modal button:has-text("OK")'); await pg.waitForTimeout(100);

    // Overzicht laatste 3 maanden
    await pg.selectOption('#bk-periode', 'm3'); await pg.waitForTimeout(150);
    const ov = await tekst('#bk-overzicht');
    ok(ov.includes('€ 9.325,00'), 'inkomsten: salaris + terugbetaling');
    ok(ov.includes('€ 4.549,11'), 'uitgaven zonder sparen');
    ok(ov.includes('€ 600,00'), 'gespaard apart');
    ok(ov.includes('Wonen') && ov.includes('Boodschappen') && ov.includes('Abonnementen') && ov.includes('Energie & water'), 'categorieën herkend');
    ok(ov.includes('Vaste lasten') && ov.includes('Netflix International') && ov.includes('maandelijks'), 'vaste lasten gevonden');
    ok(ov.includes('Saldo') && ov.includes('Per maand'), 'saldo en maanden');
    ok(ov.includes('<img src=x onerror=alert(1)>') && await pg.$('#s-bank img') === null, 'namen uit afschriften alleen als tekst (geen HTML)');
    await pg.screenshot({ path: __dirname + '/shots/bankzaken-' + scheme + '.png', fullPage: true });

    // Categorie aantikken → boekingen
    await pg.click('#bk-overzicht .bk-bar:has-text("Wonen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => bk.tab) === 'boekingen' && (await tekst('#bk-lijst-telling')).startsWith('3 boekingen'), 'Wonen: 3 boekingen');
    await pg.selectOption('#bk-cat', ''); await pg.fill('#bk-zoek', '23,45'); await pg.waitForTimeout(100);
    ok((await tekst('#bk-lijst')).includes('ALBERT HEIJN'), 'zoeken op bedrag');
    await pg.fill('#bk-zoek', 'kapper'); await pg.waitForTimeout(100);
    ok((await tekst('#bk-lijst')).includes('Kapper Knip') && (await tekst('#bk-lijst')).includes('Persoonlijke verzorging'), 'zoeken op naam, kapper herkend');

    // Lokale AI: vraag en analyse
    await pg.click('#bk-tab-ai'); await pg.waitForTimeout(100);
    ok((await tekst('#bk-ai-model')).includes('onthoudt niets'), 'uitleg: AI op de telefoon, onthoudt niets');
    await pg.click('#bk-ai-chips button:has-text("Waar gaat mijn geld heen")'); await pg.waitForTimeout(300);
    const v = await pg.evaluate(() => Android._bk.ai[0]);
    ok(v.mode === 'vraag' && v.messages.length === 1 && v.messages[0].content.includes('Inkomsten') && v.messages[0].content.includes('Wonen'), 'vraag met cijfers naar de lokale AI');
    ok((await tekst('#bk-ai-uit')).includes('Wonen'), 'antwoord getoond');
    await pg.click('#bk-ai-sam'); await pg.waitForTimeout(300);
    ok((await tekst('#bk-ai-uit')).includes('<script>abonnementen</script>') && await pg.$('#bk-ai-uit script') === null, 'antwoord van de AI alleen als tekst');
    await pg.fill('#bk-ai-in', 'Hoeveel gaf ik uit bij Jumbo?'); await pg.click('#bk-ai-stuur'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => Android._bk.ai[2].messages[0].content.includes('Boekingen die bij de vraag passen (3')), 'vraag over een winkel: die boekingen erbij');

    // Beheer: AI-voorstellen voor categorieën
    await pg.click('#bk-tab-beheer'); await pg.waitForTimeout(150);
    ok((await tekst('#bk-zonder')).includes('staan nog bij Overig'), 'aantal zonder categorie');
    await pg.click('#bk-ai-cat'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => Android._bk.ai[3].mode === 'categorie' && Android._bk.ai[3].messages[0].content.includes('Categorieën: Boodschappen')), 'categorieën-vraag met de lijst');
    ok(await pg.$$eval('#bk-ai-cat-uit .bk-voorstel', l => l.length) === 1 && await pg.$('#bk-ai-cat-uit img') === null, 'één bruikbaar voorstel (onbekende categorie genegeerd), naam als tekst');
    await pg.click('#bk-ai-cat-uit button.big'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._bk.data.rules.length === 1 && Android._bk.data.rules[0].cat === 'Persoonlijke verzorging'), 'voorstel overgenomen als regel');
    ok((await tekst('#bk-regels')).includes('Persoonlijke verzorging'), 'regel zichtbaar onder Mijn regels');
    ok((await tekst('#bk-veilig')).includes('AES-256-GCM') && (await tekst('#bk-veilig')).includes('15 minuten'), 'uitleg beveiliging');

    // Regel via een boeking
    await pg.click('#bk-tab-boekingen'); await pg.fill('#bk-zoek', 'restaurant'); await pg.waitForTimeout(100);
    await pg.click('#bk-lijst .bk-tx'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Altijd zo indelen")'); await pg.waitForTimeout(150);
    await pg.click('#sheet button:has-text("Vrije tijd & sport")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._bk.data.rules[0].p === 'restaurant de kade' && Android._bk.data.rules[0].cat === 'Vrije tijd & sport'), 'regel gemaakt vanuit een boeking');
    ok((await tekst('#bk-lijst')).includes('Vrije tijd & sport'), 'boekingen direct anders ingedeeld');

    // App uit beeld: alles uit het geheugen
    await pg.evaluate(() => onPauseApp()); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => bk.store === null) && (await tekst('#bk-overzicht')) === '' && await pg.isVisible('#bk-dicht'), 'app uit beeld: gegevens weg uit het geheugen');
    await pg.evaluate(() => { Android._bk.open = false; onResumeApp(); }); await pg.waitForTimeout(100);
    ok(await pg.isVisible('#bk-dicht'), 'terug in de app: op slot');

    // Weg uit Bankzaken: op slot en schermbeveiliging uit
    await pg.click('#bk-open-knop'); await pg.waitForTimeout(250);
    ok(await pg.isVisible('#bk-los'), 'weer open');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current) === 'home' && await pg.evaluate(() => !Android._bk.open && !Android._bk.secure && bk.store === null), 'weg uit Bankzaken: op slot, beveiliging uit, geheugen leeg');

    // Wissen
    await pg.click('#tile-bank'); await pg.waitForTimeout(100); await pg.click('#bk-open-knop'); await pg.waitForTimeout(250);
    await pg.click('#bk-tab-beheer'); await pg.waitForTimeout(100);
    await pg.click('#bk-p-beheer .bk-gevaar'); await pg.waitForTimeout(100);
    await pg.click('#modal button:has-text("Wissen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._bk.data === null) && await pg.isVisible('#bk-dicht'), 'alles gewist');
    await pg.evaluate(() => goBack());
    console.log(scheme, 'errors', errs);
    await pg.close();
  }
  await b.close();
})();
