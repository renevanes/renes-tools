// Statistieken voor verbeteringen (Instellingen → Foutrapport en statistieken): standaard aan, maar er gaat pas iets
// weg met een repository en sleutel; bekijken wat er verstuurd wordt, nu versturen, uitzetten.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(150);
    ok((await pg.textContent('#s-settings')).includes('Foutrapport en statistieken'), 'instellingen: de rij heet Foutrapport en statistieken');
    await pg.evaluate(() => show('setcrash')); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#st-stat') && (await pg.getAttribute('#st-stat-on', 'aria-checked')) === 'true', 'standaard aan');
    ok((await pg.textContent('#st-stat-sub')).includes('nog geen bestemming') && await pg.isDisabled('#st-stat-send'), 'zonder bestemming: er gaat niets weg, versturen kan niet');
    await pg.click('#st-stat-what summary'); await pg.waitForTimeout(80);
    const pv = await pg.textContent('#st-stat-preview');
    ok(pv.includes('"logregels"') && pv.includes('BACKUP') && !/sms|bericht|naam/i.test(pv), 'wat er verstuurd wordt: alleen tellingen');
    // Ongeldige bestemming
    await pg.fill('#st-stat-repo', 'gewoon-een-naam'); await pg.click('#s-setcrash button:has-text("Opslaan")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#toast')).includes('eigenaar/naam'), 'ongeldige repository: uitleg');
    await pg.fill('#st-stat-repo', 'renevanes/renes-tools-statistiek'); await pg.click('#s-setcrash button:has-text("Opslaan")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#toast')).includes('sleutel'), 'zonder sleutel: vraagt de sleutel');
    await pg.fill('#st-stat-token', 'ghp_abcdefghijklmnopqrstuvwxyz0123456789'); await pg.click('#s-setcrash button:has-text("Opslaan")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#toast')).includes('fine-grained') && !(await pg.evaluate(() => Android._stat.token)), 'klassieke sleutel (mag overal schrijven): geweigerd');
    await pg.fill('#st-stat-token', 'github_pat_11ABCDEFGHIJ0123456789_abcdefghij'); await pg.click('#s-setcrash button:has-text("Opslaan")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._stat.repo === 'renevanes/renes-tools-statistiek' && Android._stat.token) && (await pg.inputValue('#st-stat-token')) === ''
      && (await pg.getAttribute('#st-stat-token', 'placeholder')).includes('ingesteld'), 'bewaard; de sleutel staat niet meer in het veld');
    ok(!(await pg.isDisabled('#st-stat-send')) && (await pg.textContent('#st-stat-sub')).includes('voor het eerst'), 'met bestemming: versturen kan');
    await pg.screenshot({ path: __dirname + '/shots/statistiek-' + scheme + '.png', fullPage: true });
    await pg.click('#st-stat-send'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => Android._stat.sent === 1) && (await pg.textContent('#toast')).includes('Verstuurd') && (await pg.textContent('#st-stat-sub')).includes('Laatst verstuurd'), 'nu versturen');
    // Uit: niets meer tellen
    await pg.click('#st-stat-on'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !Android._stat.on) && (await pg.textContent('#st-stat-sub')).includes('Uit') && await pg.isDisabled('#st-stat-send'), 'uitzetten');
    // Bestemming weghalen
    await pg.fill('#st-stat-repo', ''); await pg.click('#s-setcrash button:has-text("Opslaan")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !Android._stat.repo && !Android._stat.token), 'bestemming verwijderd');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
