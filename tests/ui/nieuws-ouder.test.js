// Nieuws: oudere berichten laden onderaan de lijst (het archief in Java, en bij een sitemap verder terug op de site).
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const browser = await chromium.launch(), errors = [];
  for (const colorScheme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme });
    page.on('pageerror', e => errors.push(e.message));
    const ok = (v, m) => { console.log((v ? '✓ ' : '✗ ') + m); if (!v) errors.push(colorScheme + ': ' + m); };
    await page.goto('file://' + path.join(__dirname, 'ui/index.html'));
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      const s = Android._nwInit(), nu = Date.now(), d = 86400000;
      let n = 0;
      const art = (bron, dagen) => ({ id: 'n' + (0xb000 + ++n).toString(16).padStart(16, '0'), bron, titel: 'Oud bericht ' + n, link: 'https://nos.nl/l/' + n,
        datum: nu - dagen * d - n * 1000, blokken: [{ t: 'Tekst van oud bericht ' + n }] });
      for (let i = 0; i < 70; i++) s.oud.push(art(i % 2 ? 'nos' : 'rijnmond', 4 + i / 10));
      for (let i = 0; i < 25; i++) s.sitemap.aireport.push(Object.assign(art('aireport', 20 + i), { link: 'https://www.aireport.nl/artikel/oud-' + i }));
      s.wacht = 100;
    });
    const calls = () => page.evaluate(() => Android._nw.calls.map(c => c.join(':')));
    const rijen = () => page.locator('#nw-lijst .nw-rij').count();

    await page.evaluate(() => show('nieuws'));
    await page.waitForTimeout(300);
    const eerst = await rijen();
    ok(eerst === 9 && await page.locator('#nw-lijst .nw-ouder').isVisible(), 'onderaan de lijst: Oudere berichten laden');

    await page.click('#nw-lijst .nw-ouder');
    await page.waitForTimeout(200);
    ok(await rijen() === eerst + 60 && (await calls()).some(c => c.startsWith('ouder::')), 'het volgende stuk uit het archief erachter');
    const titels = await page.evaluate(() => [...document.querySelectorAll('#nw-lijst .nw-rij b')].map(b => b.textContent));
    ok(titels[eerst] === 'Oud bericht 1' && new Set(titels).size === titels.length, 'nieuwste eerst, niets dubbel');

    // het archief raakt op: de sitemap van AI Report verder terug
    await page.evaluate(() => { Android._nw.wacht = 1500; }); // lang genoeg om het wachten te zien, ook op een trage machine
    await page.click('#nw-lijst .nw-ouder');
    ok((await calls()).includes('ouderHalen:') && await page.locator('#nw-lijst .nw-ouder-st.wacht').isVisible(), 'archief op: verder terug op de site, en dat is zichtbaar');
    await page.evaluate(() => { Android._nw.wacht = 100; });
    await page.waitForFunction(() => !nw.ouderBezig, null, { timeout: 10000 });
    await page.waitForTimeout(100);
    ok(await rijen() === eerst + 70 + 20, 'wat de site gaf staat erbij (' + await rijen() + ')');

    // openen werkt ook voor een oud bericht, en terug houdt de lijst
    await page.locator('#nw-lijst .nw-rij b', { hasText: 'Oud bericht 3' }).first().click();
    await page.waitForTimeout(200);
    ok(await page.evaluate(() => current === 'nieuwsart') && (await page.locator('#s-nieuwsart').innerText()).includes('Tekst van oud bericht 3'), 'een oud bericht openen');
    await page.evaluate(() => goBack()); await page.waitForTimeout(300);
    ok(await rijen() === eerst + 90, 'terug: de oudere berichten staan er nog');

    // alles op
    for (let i = 0; i < 3; i++) { if (await page.locator('#nw-lijst .nw-ouder').count()) { await page.click('#nw-lijst .nw-ouder'); await page.waitForTimeout(400); } }
    ok(await page.locator('#nw-lijst .nw-ouder-st').isVisible() && (await page.locator('#nw-lijst .nw-ouder-st').innerText()).includes('Geen oudere berichten'), 'niets meer: dat staat er');

    // een andere bron: opnieuw beginnen
    await page.evaluate(() => { $('#nw-bron').value = 'nos'; nwRender(); });
    await page.waitForTimeout(100);
    ok(await page.locator('#nw-lijst .nw-ouder').isVisible() && await page.evaluate(() => nw.ouder.length === 0), 'andere bron gekozen: weer vanaf het begin');
    await page.click('#nw-lijst .nw-ouder'); await page.waitForTimeout(200);
    ok(await page.evaluate(() => [...document.querySelectorAll('#nw-lijst .nw-rij small')].every(s => s.textContent.startsWith('NOS'))), 'alleen oudere berichten van die bron');

    // Engels
    await page.evaluate(() => show('setregio')); await page.selectOption('#rg-lang', 'en');
    await page.evaluate(() => { $('#nw-bron').value = 'rijnmond'; show('nieuws'); }); await page.waitForTimeout(300);
    ok((await page.locator('#nw-lijst .nw-ouder').innerText()) === 'Load older articles', 'Engels');
    await page.evaluate(() => show('setregio')); await page.selectOption('#rg-lang', 'nl');
    await page.close();
  }
  await browser.close();
  console.log('errors', errors);
  process.exit(errors.length ? 1 : 0);
})();
