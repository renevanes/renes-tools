// OPML: abonnementen (Podcasts) en bronnen (Nieuws) importeren en exporteren via het menu ⋯.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    const melding = () => pg.evaluate(() => $('#toast').textContent);
    // Podcasts
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(250);
    await pg.click('#pc-menu'); await pg.waitForTimeout(120);
    const keuzes = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent.trim()));
    ok(keuzes.some(k => /importeren \(OPML\)/.test(k)), scheme + ': Podcasts ⋯ heeft importeren (' + keuzes.join(' | ') + ')');
    await pg.click('#sheet-acts button:has-text("Abonnementen importeren (OPML)")'); await pg.waitForTimeout(200);
    ok((await pg.evaluate(() => Android._opml.join())) === 'import:podcasts', scheme + ': importeren vraagt een bestand voor podcasts');
    ok(/2 feeds toegevoegd, één had je al/.test(await melding()), scheme + ': uitkomst in aantallen: ' + await melding());
    await pg.evaluate(() => { window.__testOpml = { nieuw: 1, al: 0, mislukt: 3 }; opmlImport('podcasts'); }); await pg.waitForTimeout(150);
    ok(/Eén feed toegevoegd, 3 lukten niet/.test(await melding()), scheme + ': enkelvoud en mislukt: ' + await melding());
    await pg.evaluate(() => { window.__testOpml = { nieuw: 4, over: 6 }; window.__testOpmlWacht = 600; opmlImport('podcasts'); }); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => uiVg.soort === 'bezig' && /importeren/.test(uiVg.bezig)), scheme + ': tijdens het importeren staat onderin waarom je wacht');
    await pg.waitForTimeout(600);
    ok(/4 feeds toegevoegd, 6 pasten er niet meer bij/.test(await melding()) && await pg.evaluate(() => uiVg.soort !== 'bezig' && $('#vg').hidden), scheme + ': daarna de uitkomst, met wat er niet bij paste: ' + await melding());
    await pg.evaluate(() => { window.__testOpmlWacht = 0; });
    await pg.evaluate(() => { window.__testOpml = { error: 'Dit is geen OPML-bestand' }; opmlImport('podcasts'); }); await pg.waitForTimeout(150);
    ok(/geen OPML-bestand/.test(await melding()), scheme + ': fout van het bestand als melding');
    await pg.evaluate(() => { $('#toast').textContent = 'leeg'; window.__testOpml = 'weg'; opmlImport('podcasts'); }); await pg.waitForTimeout(150);
    ok((await melding()) === 'leeg', scheme + ': kiezer weggetikt: geen melding');
    await pg.evaluate(() => opmlExport('podcasts')); await pg.waitForTimeout(150);
    ok(/bewaard als OPML|niets om te exporteren/.test(await melding()), scheme + ': exporteren meldt het aantal: ' + await melding());
    // Nieuws: eerst de uitleg dat elke feed gelezen wordt
    await pg.evaluate(() => { window.__testOpml = null; Android._opml.length = 0; show('nieuws'); }); await pg.waitForTimeout(300);
    await pg.click('#nw-meer'); await pg.waitForTimeout(120);
    await pg.click('#sheet-acts button:has-text("Bronnen importeren (OPML)")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => $('#modal').classList.contains('on') && /IP-adres/.test($('#modal-text').textContent) && !Android._opml.length), scheme + ': Nieuws vraagt eerst en zegt dat sites je IP-adres zien');
    await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    ok((await pg.evaluate(() => Android._opml.join())) === 'import:nieuws' && /toegevoegd/.test(await melding()), scheme + ': daarna importeren voor nieuws');
    await pg.click('#nw-meer'); await pg.waitForTimeout(120);
    await pg.click('#sheet-acts button:has-text("Bronnen exporteren (OPML)")'); await pg.waitForTimeout(150);
    ok(/3 feeds bewaard als OPML/.test(await melding()), scheme + ': bronnen exporteren: ' + await melding());
    ok(await pg.evaluate(() => { Android._opml.length = 0; Android.opmlImport('bank'); Android.opmlExport('../x'); return Android._opml.length === 0; }), scheme + ': een andere soort doet niets');
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
