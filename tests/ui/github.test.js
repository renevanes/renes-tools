const { chromium } = require('playwright');
/* Publiceren en tests (GitHub): de rij bij Versie en updates, de samenvatting, per taak, recente runs met Meer tonen,
   open punten, doorklikken alleen naar adressen van de repository, een fout, tekst van buiten niet als HTML, en de
   melding via Signalen. Ook: niet in de Play-versie en niet met een app die het nog niet kan. */
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const errs = [];
    const ok = (c, m) => { if (!c) errs.push('✗ ' + m); };
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    await pg.evaluate(() => { Android.openUrl = u => { (Android._urls = Android._urls || []).push(u); }; show('info'); }); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#i-gh-kaart') && /Publiceren en tests/.test(await pg.textContent('#i-gh-kaart')), 'rij bij Versie en updates');
    await pg.click('#i-gh-kaart .strow'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => current === 'github'), 'scherm open');
    ok(/Er mislukte iets/.test(await pg.textContent('#gh-kop')) && /1 runs? bezig|Eén run bezig/.test(await pg.textContent('#gh-samen')) && /taak mislukt/.test(await pg.textContent('#gh-samen')), 'samenvatting: wat mislukte en wat loopt (' + await pg.textContent('#gh-samen') + ')');
    const taken = await pg.$$eval('#gh-taken .strow', x => x.map(e => e.innerText.replace(/\s+/g, ' ')));
    ok(taken.length === 3 && /^✗ Publiceren mislukt/.test(taken[0]), 'per taak, Publiceren bovenaan en mislukt ' + JSON.stringify(taken));
    ok(taken.some(x => /Bouwen en testen gelukt .* nu op claude\/iets: bezig/.test(x)), 'de stand op main, met wat er nu op een andere tak loopt');
    ok((await pg.$$('#gh-runs .ghrun')).length === 12 && await pg.isVisible('#gh-runs-meer'), 'eerst 12 runs, met Meer tonen');
    ok(/De nieuwste runs, sinds /.test(await pg.textContent('#gh-sinds')), 'zegt welke periode de runs beslaan');
    ok(await pg.$eval('#gh-runs', el => !el.querySelector('.wacht')), 'een run die bezig is krijgt geen wachtbalk');
    await pg.click('#gh-runs-meer'); await pg.waitForTimeout(100);
    ok((await pg.$$('#gh-runs .ghrun')).length === 19 && !(await pg.isVisible('#gh-runs-meer')), 'Meer tonen: alle runs');
    // Doorklikken: een run en een taak openen op GitHub
    await pg.click('#gh-runs .ghrun >> nth=1'); await pg.click('#gh-taken .strow >> nth=0'); await pg.waitForTimeout(100);
    const urls = await pg.evaluate(() => Android._urls || []);
    ok(urls.length === 2 && urls.every(u => u.startsWith('https://github.com/renevanes/renes-tools/actions/runs/9104')), 'doorklikken naar de run op GitHub ' + JSON.stringify(urls));
    // Open punten: tekst van buiten blijft tekst; een adres buiten de repository gaat niet open
    ok(await pg.$eval('#gh-punten', el => !el.querySelector('#gh-punten b b') && el.textContent.includes('<b>Voorstel</b>')), 'titel van GitHub als tekst, niet als HTML');
    ok(/Eén pull request staat open/.test(await pg.textContent('#gh-punten')), 'pull requests geteld');
    await pg.click('#gh-punten .ghrun >> nth=1'); await pg.waitForTimeout(100);
    ok((await pg.evaluate(() => Android._urls.length)) === 2 && /niet te openen/.test(await pg.textContent('#toast')), 'adres buiten de repository: niet geopend');
    await pg.click('#gh-punten .ghrun >> nth=0'); await pg.waitForTimeout(100);
    ok((await pg.evaluate(() => Android._urls[2])) === 'https://github.com/renevanes/renes-tools/issues/32', 'open punt op GitHub');
    // Verversen haalt opnieuw op; een fout staat in beeld en de vorige stand blijft
    const voor = await pg.evaluate(() => Android._gh.vragen);
    await pg.evaluate(() => { Android._gh.fout = 'GitHub laat even geen vragen meer toe; probeer het over 12 minuten opnieuw'; });
    await pg.click('#gh-ververs'); await pg.waitForTimeout(300);
    ok((await pg.evaluate(() => Android._gh.vragen)) === voor + 1 && await pg.isVisible('#gh-fout') && /12 minuten/.test(await pg.textContent('#gh-fout')) && (await pg.$$('#gh-taken .strow')).length === 3, 'fout zichtbaar, de vorige stand blijft');
    await pg.evaluate(() => { Android._gh.fout = ''; });
    await pg.screenshot({ path: __dirname + '/shots/github-' + scheme + '.png', fullPage: true });
    // Melding via Signalen: nog geen bron → aanzetten opent de bron in Signalen; na bewaren: instellen
    ok(/Melding aanzetten/.test(await pg.textContent('#gh-melding')) && /elk uur/.test(await pg.textContent('#gh-melding-txt')), 'melding: uitleg en knop');
    await pg.click('#gh-melding'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'snbron' && sn.bron.soort === 'github' && sn.bron.elke === 1) && await pg.isVisible('#snb-r-wifi') && !(await pg.isVisible('#snb-r-url')), 'bron Publiceren en tests in Signalen, met wifi-keuze, zonder adres');
    await pg.evaluate(() => snbBewaar()); await pg.waitForTimeout(250);
    await pg.evaluate(() => show('github')); await pg.waitForTimeout(250);
    ok(/Instellen in Signalen/.test(await pg.textContent('#gh-melding')) && /Staat aan in Signalen/.test(await pg.textContent('#gh-melding-txt')), 'na bewaren: staat aan, knop naar de instellingen');
    ok(!(await pg.evaluate(() => snSoortenVrij().includes('github'))), 'niet nog een keer toe te voegen');
    console.log(scheme, 'errors', errs);
    await pg.close();
  }
  // Play-versie en een app die het nog niet kan: geen rij
  for (const geval of ['play', 'oud']) {
    const errs = [];
    const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.addInitScript(g => { if (g === 'play') window.__testPlay = true; }, geval);
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    if (geval === 'oud') await pg.evaluate(() => { delete Android.githubStatus; Android.githubStatus = undefined; });
    await pg.evaluate(() => show('info')); await pg.waitForTimeout(150);
    if (await pg.isVisible('#i-gh-kaart')) errs.push('✗ ' + geval + ': rij zichtbaar');
    if (geval === 'oud' && await pg.evaluate(() => typeof snSoortenVrij === 'function' && snSoortenVrij().includes('github'))) errs.push('✗ oud: Signalen biedt GitHub aan');
    console.log(geval, 'errors', errs);
    await pg.close();
  }
  await b.close();
})();
