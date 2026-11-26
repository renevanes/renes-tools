// Foutrapport en statistieken: één overzicht van wat er vanzelf gaat (bestemming, foutlogboek, statistieken, diagnose),
// rijen naar eigen schermen met de stand eronder, alleen via wifi, en Verbinding testen bij de bestemming.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(scheme + ': ' + e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('setcrash')); await pg.waitForTimeout(150);
    const tekst = s => pg.evaluate(s => document.querySelector(s).textContent, s);
    // Zonder bestemming: zichtbaar dat er niets weggaat
    ok((await tekst('#fc-samen')).includes('Er gaat niets weg'), scheme + ': zonder bestemming: er gaat niets weg');
    ok((await tekst('#fc-sum-doel')).includes('Niet ingesteld'), scheme + ': rij Bestemming: niet ingesteld');
    const rijen = await pg.$$eval('#fc-lijst .strow:not([hidden]) .sttxt b', x => x.map(e => e.textContent));
    ok(rijen.join('|') === 'Bestemming|Foutlogboek|Statistieken|Diagnose|Foutrapport op deze telefoon|Wat de telefoon verliet', scheme + ': zes rijen: ' + rijen.join(', '));
    ok(await pg.evaluate(() => [...document.querySelectorAll('#fc-lijst .strow small')].every(s => s.textContent.trim().length > 3)), scheme + ': onder elke rij de stand');
    ok(await pg.evaluate(() => document.documentElement.scrollHeight <= innerHeight * 1.6), scheme + ': het overzicht is kort (geen lange lijst)');
    // Bestemming instellen en testen
    await pg.click('#fc-lijst .strow:has-text("Bestemming")'); await pg.waitForTimeout(120);
    ok(await pg.evaluate(() => current === 'setcrashdoel') && (await tekst('#fc-doel-stand')).includes('Nog niet ingesteld'), scheme + ': eigen scherm Bestemming');
    await pg.click('#fc-test'); await pg.waitForTimeout(80);
    ok((await tekst('#fc-test-uit')).includes('Vul eerst'), scheme + ': testen zonder repository: eerst invullen');
    await pg.fill('#st-stat-repo', 'renevanes/renes-tools-statistiek'); await pg.fill('#st-stat-token', 'github_pat_11ABCDEFGHIJ0123456789_abcdefghij');
    await pg.click('#s-setcrashdoel button:has-text("Opslaan")'); await pg.waitForTimeout(100);
    await pg.click('#fc-test'); await pg.waitForTimeout(20);
    ok(await pg.isDisabled('#fc-test') && (await tekst('#fc-test-uit')).includes('testen'), scheme + ': tijdens het testen: zichtbaar en de knop wacht');
    await pg.waitForTimeout(150);
    ok((await tekst('#fc-test-uit')).includes('In orde') && (await pg.evaluate(() => Android._fout.getest)) === 1 && !(await pg.isDisabled('#fc-test')), scheme + ': verbinding in orde');
    await pg.evaluate(() => { Android._stat.testFout = 'Alleen naar een privé repository: GitHub meldt deze niet als privé'; });
    await pg.click('#fc-test'); await pg.waitForTimeout(150);
    ok((await tekst('#fc-test-uit')).includes('Niet gelukt') && (await tekst('#fc-test-uit')).includes('privé'), scheme + ': een fout zegt waarom');
    await pg.evaluate(() => { delete Android._stat.testFout; goBack(); }); await pg.waitForTimeout(120);
    // Met bestemming: het overzicht zegt wat er vanzelf gaat
    const samen = await tekst('#fc-samen');
    ok(samen.includes('renevanes/renes-tools-statistiek') && samen.includes('foutlogboek') && samen.includes('statistieken (elke week)') && samen.includes('alleen weg als jij op Versturen tikt'), scheme + ': wat er vanzelf gaat: ' + samen);
    ok((await tekst('#fc-sum-doel')).includes('sleutel ingesteld') && (await tekst('#fc-sum-stat')).includes('elke week') && (await tekst('#fc-sum-log')).includes('snel'), scheme + ': de rijen tonen de keuzes');
    ok((await tekst('#fc-sum-weg')).includes('Verbindingstest'), scheme + ': de verbindingstest staat bij wat de telefoon verliet');
    // Alleen via wifi
    ok((await pg.getAttribute('#fc-wifi', 'aria-checked')) === 'false' && (await tekst('#fc-wifi-sub')).includes('mobiele data'), scheme + ': alleen via wifi: standaard uit');
    await pg.click('#fc-wifi'); await pg.waitForTimeout(80);
    ok((await pg.evaluate(() => Android._fout.wifi)) && (await pg.getAttribute('#fc-wifi', 'aria-checked')) === 'true' && (await tekst('#fc-samen')).includes('Alleen via wifi'), scheme + ': alleen via wifi aan, en het overzicht zegt het');
    await pg.click('#fc-wifi'); await pg.waitForTimeout(80);
    // Een keuze op een eigen scherm staat daarna in het overzicht
    await pg.click('#fc-lijst .strow:has-text("Statistieken")'); await pg.waitForTimeout(100);
    await pg.selectOption('#fc-elke', '1'); await pg.waitForTimeout(60);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok((await tekst('#fc-sum-stat')).includes('elke dag') && (await tekst('#fc-samen')).includes('elke dag'), scheme + ': elke dag: overzicht bijgewerkt');
    await pg.click('#fc-lijst .strow:has-text("Foutlogboek")'); await pg.waitForTimeout(100);
    await pg.click('#st-log-aan'); await pg.waitForTimeout(60);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok((await tekst('#fc-sum-log')).includes('Uit') && !(await tekst('#fc-samen')).includes('foutlogboek'), scheme + ': foutlogboek uit: overzicht bijgewerkt');
    // Elke rij: eigen scherm (hdr sm) en terug naar het overzicht; dieper dan één stap: ook Start
    for (const [rij, scr] of [['Diagnose', 'setcrashdiag'], ['Foutrapport op deze telefoon', 'setcrashrap'], ['Wat de telefoon verliet', 'setcrashweg']]) {
      await pg.click('#fc-lijst .strow:has-text("' + rij + '")'); await pg.waitForTimeout(100);
      const goed = await pg.evaluate(s => current === s && document.querySelector('#s-' + s + ' .hdr.sm h1') !== null, scr);
      await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
      ok(goed && await pg.evaluate(() => current === 'setcrash'), scheme + ': ' + rij + ': eigen scherm, terug naar het overzicht');
    }
    // Een oudere app zonder de nieuwe keuzes: die keuzes verdwijnen, de rest werkt
    await pg.evaluate(() => { window.__fi = Android.foutInstellingen; delete Android.foutInstellingen; fcRender(); });
    ok(!(await pg.isVisible('#fc-wifi-rij')), scheme + ': oudere app: geen wifi-keuze');
    await pg.evaluate(() => { Android.foutInstellingen = window.__fi; });
    await pg.screenshot({ path: __dirname + '/shots/foutrapport-' + scheme + '.png', fullPage: true });
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
  process.exit(errs.length ? 1 : 0);
})();
