// Locatie kiezen: uitleg instellen, plek kiezen (kaart, zoeken, coördinaten), aanzetten, bewaren, stoppen
const { chromium } = require('playwright');
const assert = (ok, what) => { if (!ok) throw new Error('mislukt: ' + what); console.log('✓ ' + what); };
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const vis = sel => pg.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
    await pg.click('#tile-loc'); await pg.waitForTimeout(400);
    assert(await vis('#lc-setup') && await vis('#lc-step-dev'), 'nog niet ingesteld: stappen met ontwikkelaarsopties');
    assert(await pg.isDisabled('#lc-go'), 'zonder plek: Hier zetten uit');
    // coördinaten intikken
    await pg.fill('#lc-q', '52.3731, 4.8922'); await pg.press('#lc-q', 'Enter'); await pg.waitForTimeout(150);
    assert((await pg.textContent('#lc-pick')).includes('52.37310, 4.89220') && !(await pg.isDisabled('#lc-go')), 'coördinaten gekozen');
    await pg.fill('#lc-q', 'https://www.google.com/maps/place/Dam/@52.373056,4.892222,17z'); await pg.click('#s-loc button:has-text("Zoeken")'); await pg.waitForTimeout(100);
    assert((await pg.textContent('#lc-pick')).includes('52.37306'), 'kaartlink met coördinaten');
    // nog niet als neplocatie-app gekozen
    await pg.click('#lc-go'); await pg.waitForTimeout(150);
    assert((await pg.textContent('#toast')).includes('app voor neplocatie'), 'eerst instellen: melding');
    await pg.click('#lc-setup button:has-text("Openen") >> nth=1'); await pg.waitForTimeout(50);
    await pg.evaluate(() => onResumeApp()); await pg.waitForTimeout(150);
    assert(!(await vis('#lc-setup')), 'na instellen: stappen weg');
    // adres zoeken
    await pg.fill('#lc-q', 'Coolsingel 40 Rotterdam'); await pg.press('#lc-q', 'Enter'); await pg.waitForTimeout(300);
    assert((await pg.$$('#lc-results .srow')).length === 2, 'twee zoekresultaten');
    await pg.click('#lc-results .srow >> nth=0'); await pg.waitForTimeout(100);
    assert((await pg.textContent('#lc-pick')).includes('Coolsingel 40'), 'resultaat gekozen');
    await pg.click('#lc-go'); await pg.waitForTimeout(300);
    assert((await pg.textContent('#lc-title')) === 'Neplocatie aan' && await vis('#lc-stop') && await pg.isDisabled('#lc-here'), 'neplocatie aan');
    assert((await pg.$$('#lc-recent .srow')).length === 1, 'in laatst gebruikt');
    await pg.screenshot({ path: __dirname + '/shots/loc-on-' + scheme + '.png', fullPage: true });
    // bewaren
    await pg.click('button:has-text("Plek bewaren")'); await pg.waitForTimeout(100);
    await pg.fill('#modal-input', 'Kantoor'); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    assert((await pg.textContent('#lc-places')).includes('Kantoor'), 'plek bewaard');
    // niets gevonden
    await pg.fill('#lc-q', 'nergens'); await pg.press('#lc-q', 'Enter'); await pg.waitForTimeout(300);
    assert((await pg.textContent('#lc-results')).includes('Niets gevonden'), 'niets gevonden');
    // stoppen
    await pg.click('#lc-stop'); await pg.waitForTimeout(400);
    assert((await pg.textContent('#lc-title')) === 'Echte locatie', 'terug naar echte locatie');
    // bewaarde plek → menu → hier zetten
    await pg.click('#lc-places .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:has-text("Hier zetten")'); await pg.waitForTimeout(300);
    assert((await pg.textContent('#lc-sub')).includes('Kantoor'), 'bewaarde plek aangezet');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    assert((await pg.textContent('#tile-loc-sub')).includes('Kantoor'), 'tegel toont de neplocatie');
    console.log('errors', errs);
    await pg.close();
  }
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
