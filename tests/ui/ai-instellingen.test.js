// Assistent: instellingen in een overzicht met eigen schermen; lengte, creativiteit, taal, geheugen, persoonlijk, rekenkracht.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; show('ai'); }); await pg.waitForTimeout(200);
    await pg.click('#ai-menu'); await pg.click('#sheet-acts button:has-text("Instellingen")'); await pg.waitForTimeout(200);
    ok((await pg.$$('#s-aiset .strow')).length === 8, 'overzicht met acht onderwerpen');
    ok((await pg.textContent('#ai-sum-model')) === 'Klein en snel' && (await pg.textContent('#ai-sum-answer')).includes('Normaal') && (await pg.textContent('#ai-sum-pers')) === 'Nog niets ingevuld', 'stand van zaken per onderwerp');
    // Antwoorden
    await pg.click('#s-aiset .strow:has-text("Antwoorden")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'aisetanswer') && (await pg.getAttribute('#aic-style button[data-v=normaal]', 'aria-checked')) === 'true', 'antwoorden: standaard normaal');
    await pg.click('#aic-style button[data-v=kort]'); await pg.click('#aic-creative button[data-v=creatief]'); await pg.click('#aic-lang button[data-v=en]'); await pg.click('#aic-history button[data-v="48"]');
    const c1 = await pg.evaluate(() => Android._ai.cfg);
    ok(c1.style === 'kort' && c1.creative === 'creatief' && c1.lang === 'en' && c1.history === '48', 'keuzes opgeslagen');
    ok((await pg.getAttribute('#aic-style button[data-v=kort]', 'aria-checked')) === 'true' && (await pg.textContent('#aic-style-n')).includes('Een paar zinnen'), 'gekozen knop aangeduid, met uitleg');
    await pg.screenshot({ path: __dirname + '/shots/ai-instellingen-' + scheme + '.png' });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(120);
    ok((await pg.textContent('#ai-sum-answer')) === 'Kort · Creatief · Engels', 'overzicht bijgewerkt');
    // Persoonlijk
    await pg.click('#s-aiset .strow:has-text("Persoonlijk")'); await pg.waitForTimeout(150);
    ok(await pg.isDisabled('#aic-save'), 'opslaan pas na een wijziging');
    await pg.fill('#aic-about', 'Ik woon in Rotterdam.'); await pg.fill('#aic-instr', 'Spreek me aan met je.');
    await pg.click('#aic-save'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.cfg.about === 'Ik woon in Rotterdam.' && Android._ai.cfg.instr === 'Spreek me aan met je.') && await pg.isDisabled('#aic-save'), 'persoonlijk opgeslagen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(120);
    ok((await pg.textContent('#ai-sum-pers')).includes('ingevuld'), 'overzicht: persoonlijk ingevuld');
    // Weg zonder op Opslaan te tikken: niets kwijt
    await pg.click('#s-aiset .strow:has-text("Persoonlijk")'); await pg.waitForTimeout(150);
    await pg.fill('#aic-about', 'Ik woon in Delft.'); await pg.evaluate(() => aicDirty());
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(120);
    ok(await pg.evaluate(() => Android._ai.cfg.about === 'Ik woon in Delft.' && current === 'aiset') && (await pg.textContent('#toast')).includes('bewaard'), 'weg uit Persoonlijk: concept bewaard');
    // Prestaties
    await pg.click('#s-aiset .strow:has-text("Prestaties")'); await pg.waitForTimeout(150);
    await pg.click('#aic-power button[data-v=zuinig]'); await pg.click('#aic-idle button[data-v="15"]');
    ok(await pg.evaluate(() => Android._ai.cfg.power === 'zuinig' && Android._ai.cfg.idle === '15') && (await pg.textContent('#aic-power-n')).includes('2 processorkernen'), 'rekenkracht en vrijgeven');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(120);
    ok((await pg.textContent('#ai-sum-perf')).startsWith('Zuinig · vrijgeven na 15 min'), 'overzicht: prestaties');
    // Snel naar de antwoorden vanuit het gesprek
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(150);
    await pg.click('#ai-menu'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).some(t => t.startsWith('Antwoorden: kort, creatief')), 'menu in het gesprek: huidige antwoordstijl');
    await pg.click('#sheet-acts button:has-text("Antwoorden:")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'aisetanswer'), 'menu: rechtstreeks naar Antwoorden');
    // Herstellen
    await pg.evaluate(() => show('aiset')); await pg.waitForTimeout(120);
    await pg.click('#s-aiset button:has-text("Standaardinstellingen herstellen")'); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !Android._ai.cfg) && (await pg.textContent('#ai-sum-answer')) === 'Normaal · Gebalanceerd · Nederlands' && (await pg.textContent('#ai-sum-pers')) === 'Nog niets ingevuld', 'standaard hersteld');
    // Elk onderwerp: eigen scherm, terug = overzicht
    for (const [t, scr] of [['Taalmodel', 'aisetmodel'], ['Spraak', 'aisetvoice'], ['Gegevens', 'aisetdata'], ['Slim zoeken', 'aisetsearch'], ['Privacy', 'aisetpriv']]) {
      await pg.click('#s-aiset .strow:has-text("' + t + '")'); await pg.waitForTimeout(100);
      const good = await pg.evaluate(s => current === s, scr);
      await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
      ok(good && await pg.evaluate(() => current === 'aiset'), t + ': eigen scherm, terug naar het overzicht');
    }
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
