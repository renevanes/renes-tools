// Nieuws: de instellingen van één bron, voor elke bron hetzelfde (naam, adres, welke artikelen, afbeeldingen, inloggen),
// inloggen en uitloggen, lezen met de inlog, standaard terugzetten, een eigen bron verwijderen en Engels.
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
      const s = Android._nwInit(), nu = Date.now();
      s.artikelen.push({ id: 'n00000000000ai001', bron: 'aireport', titel: 'Nieuw taalmodel uitgelegd', link: 'https://www.aireport.nl/p/nieuw-taalmodel', datum: nu - 30 * 60000, blokken: [{ t: 'Alleen het begin.' }], ingekort: true });
      s.stand.aireport = { t: nu - 5 * 60000, poging: nu - 5 * 60000 };
      window.__open = []; Android.openUrl = u => window.__open.push(u);
    });
    const calls = () => page.evaluate(() => Android._nw.calls.map(c => c.join(':')));
    const leeg = () => page.evaluate(() => { Android._nw.calls.length = 0; });
    const tekst = sel => page.locator(sel).innerText();
    const aan = sel => page.evaluate(s => document.querySelector(s).getAttribute('aria-checked') === 'true', sel);

    await page.evaluate(() => show('nieuwsset'));
    await page.waitForTimeout(100);
    ok(await page.locator('#nws-bronnen .srow').count() === 12 && await page.locator('#nws-bronnen .srow:has-text("AI Report") .tg[aria-checked="true"]').count() === 1, 'AI Report staat bij de bronnen, en aan');
    ok(await page.evaluate(() => [...document.querySelectorAll('#nws-bronnen .nws-open')].length === 12 && [...document.querySelectorAll('#nws-bronnen .nws-open')].every(b => b.tagName === 'BUTTON' && b.querySelector('.stchev'))), 'elke bron opent zijn instellingen op dezelfde manier');

    // AI Report: inloggen staat standaard aan
    await page.locator('#nws-bronnen .srow:has-text("AI Report") .nws-open').click();
    await page.waitForTimeout(100);
    ok(await page.evaluate(() => current === 'nieuwsbron') && await tekst('#nwb-titel') === 'AI Report', 'scherm van de bron');
    ok(await aan('#nwb-aan') && await aan('#nwb-inlog') && !(await aan('#nwb-pagina')), 'aan, inloggen aan, geen afbeeldingen van de pagina');
    ok(await page.inputValue('#nwb-naam') === 'AI Report' && await page.inputValue('#nwb-url') === 'https://www.aireport.nl/' && await page.inputValue('#nwb-alleen') === '', 'naam, adres en welke artikelen');
    ok(await page.locator('#nwb-inlog-deel').isVisible() && await page.inputValue('#nwb-start') === 'https://www.aireport.nl/' && await page.inputValue('#nwb-sites') === 'aireport.nl *.aireport.nl *.beehiiv.com'
      && await page.inputValue('#nwb-lezen') === 'app', 'inloggen: inlogpagina, toegestane sites, lezen in Tools');
    ok(await tekst('#nwb-inlog-st') === 'Nog niet ingelogd.' && await page.locator('#nwb-uitloggen').isDisabled(), 'nog niet ingelogd: uitloggen kan niet');
    ok(await page.locator('#nwb-standaard').isHidden() && await page.locator('#nwb-weg').isHidden() && await page.locator('#nwb-cookie').isHidden(), 'bekende bron zonder wijzigingen: geen terugzetten, geen verwijderen');
    ok((await tekst('#nwb-inlog-n')).includes('de site ziet dan dat jij het bent'), 'zichtbaar wat inloggen betekent voor je privacy');
    ok(await page.evaluate(() => document.querySelectorAll('#s-nieuwsbron .big:not(.tonal):not(.ghost)').length === 1), 'één hoofdknop (Opslaan)');

    // Inloggen: het venster, en terug in de app de stand en een nieuwe ronde
    await leeg();
    await page.click('#nwb-inloggen');
    ok((await calls()).join() === 'inloggen:aireport', 'inloggen: het venster van de bron gaat open');
    await page.evaluate(() => { Android._nwKoek.aireport = 3; onResumeApp(); });
    await page.waitForTimeout(400);
    ok(await tekst('#nwb-inlog-st') === 'De site heeft je inlog bewaard (cookies op je telefoon).' && await page.locator('#nwb-uitloggen').isEnabled() && (await calls()).includes('verversen:true'), 'terug: ingelogd, alles bijwerken');
    await page.click('#nwb-uitloggen'); await page.click('#modal-ok');
    ok((await calls()).includes('uitloggen:aireport') && await tekst('#nwb-inlog-st') === 'Nog niet ingelogd.', 'uitloggen');

    // Opslaan: een fout laat alles staan, daarna wel
    await page.fill('#nwb-sites', '*.com');
    await leeg(); await page.click('#nwb-opslaan'); await page.waitForTimeout(300);
    ok(await tekst('#nwb-status') === 'Geen geldige site: *.com' && await page.inputValue('#nwb-sites') === '*.com', 'ongeldige site: de fout, wat je typte blijft staan');
    await page.fill('#nwb-sites', 'aireport.nl *.aireport.nl *.beehiiv.com accounts.google.com');
    await page.fill('#nwb-naam', 'AI Report NL');
    ok(await page.evaluate(() => { const n = () => Android._nw.calls.filter(c => c[0] === 'instellen').length, voor = n(); nwbOpslaan(); const bezig = document.querySelector('#nwb-opslaan').disabled; nwbOpslaan(); return bezig && n() === voor + 1; }), 'tijdens opslaan: niet nog eens');
    await page.waitForTimeout(300);
    const verzonden = await page.evaluate(() => JSON.parse(Android._nw.calls.filter(c => c[0] === 'instellen').pop()[2]));
    ok(verzonden.naam === 'AI Report NL' && verzonden.url === 'https://www.aireport.nl/' && /accounts\.google\.com/.test(verzonden.inlogSites) && 'inlogStart' in verzonden, 'opslaan stuurt alle velden van de bron');
    ok(await tekst('#nwb-status') === 'Opgeslagen' && await tekst('#nwb-titel') === 'AI Report NL' && await page.locator('#nwb-standaard').isVisible(), 'opgeslagen: nieuwe naam, terugzetten kan nu');
    await page.fill('#nwb-url', 'https://www.aireport.nl/geenfeed');
    await page.click('#nwb-opslaan'); await page.waitForTimeout(300);
    ok((await tekst('#nwb-status')).startsWith('Op deze pagina staat geen nieuwsfeed'), 'adres zonder feed: de oude blijft, met uitleg');

    // Inloggen uit: het deel verdwijnt; lezen gaat dan in de browser
    await leeg(); await page.click('#nwb-inlog'); await page.waitForTimeout(300);
    ok(await page.locator('#nwb-inlog-deel').isHidden() && (await calls()).some(c => c === 'instellen:aireport:{"inlog":false}'), 'inloggen uit: meteen opgeslagen');
    await page.click('#nwb-inlog'); await page.waitForTimeout(300);
    ok(await page.locator('#nwb-inlog-deel').isVisible() && await page.inputValue('#nwb-lezen') === 'app', 'inloggen weer aan');

    // Lezen met de inlog
    await page.evaluate(() => { nwLaad(); nwOpen('n00000000000ai001'); });
    await page.waitForTimeout(100);
    await leeg(); await page.click('#nwa-web');
    ok((await calls()).join() === 'lezen:aireport:https://www.aireport.nl/p/nieuw-taalmodel' && await page.evaluate(() => window.__open.length === 0), 'Lees op de website: in Tools, met de inlog');
    await page.evaluate(() => { const b = Android._nw.bronnen.find(x => x.id === 'aireport'); b.lezen = 'browser'; nwLaad(); });
    await page.click('#nwa-web');
    ok(await page.evaluate(() => window.__open.join() === 'https://www.aireport.nl/p/nieuw-taalmodel'), 'lezen in de browser: de browser');
    await page.evaluate(() => { window.__open.length = 0; const nos = Android._nw.artikelen.find(a => a.bron === 'nos'); nwOpen(nos.id); });
    await page.click('#nwa-web');
    ok(await page.evaluate(() => window.__open.length === 1 && window.__open[0].startsWith('https://nos.nl/')), 'bron zonder inloggen: de browser');

    // Standaard terugzetten
    await page.evaluate(() => nwBronOpen('aireport')); await page.waitForTimeout(100);
    await page.click('#nwb-standaard'); await page.click('#modal-ok'); await page.waitForTimeout(300);
    ok(await page.inputValue('#nwb-naam') === 'AI Report' && await page.inputValue('#nwb-sites') === 'aireport.nl *.aireport.nl *.beehiiv.com' && await tekst('#nwb-status') === 'Standaard teruggezet.'
      && await page.locator('#nwb-standaard').isHidden(), 'standaard terugzetten: alles weer zoals het was');

    // Elke bron hetzelfde: NOS kan ook inloggen, met de site van NOS als begin
    await page.evaluate(() => nwBronOpen('nos')); await page.waitForTimeout(100);
    ok(!(await aan('#nwb-inlog')) && await page.locator('#nwb-inlog-deel').isHidden() && await page.locator('#nwb-url-std').isHidden(), 'NOS: inloggen uit');
    await page.click('#nwb-inlog'); await page.waitForTimeout(300);
    ok(await page.locator('#nwb-inlog-deel').isVisible() && await page.inputValue('#nwb-start') === 'https://nos.nl/' && await page.inputValue('#nwb-sites') === 'nos.nl *.nos.nl', 'NOS: inloggen aan, begint bij nos.nl');
    await page.fill('#nwb-url', 'https://feeds.nos.nl/nosnieuwsbinnenland');
    await page.click('#nwb-opslaan'); await page.waitForTimeout(300);
    ok(await tekst('#nwb-url-std') === 'Standaard: https://feeds.nos.nl/nosnieuwsalgemeen', 'ander adres: het standaardadres staat erbij');
    // Tweakers: cookiekeuze bij de afbeeldingen, op hetzelfde scherm
    await page.evaluate(() => nwBronOpen('tweakers')); await page.waitForTimeout(100);
    ok(await aan('#nwb-pagina') && await page.locator('#nwb-cookie').isVisible() && await page.inputValue('#nwb-alleen') === 'tweakers.net/nieuws/', 'Tweakers: afbeeldingen van de pagina met de cookiekeuze, alleen het nieuws');
    await leeg(); await page.click('#nwb-cookie');
    ok((await calls()).join() === 'cookies:tweakers', 'cookiekeuze vanaf de bron');
    await page.evaluate(() => { Android._nw.cookie = true; onResumeApp(); }); await page.waitForTimeout(400);

    // Een eigen bron: dezelfde instellingen, en verwijderen
    await page.evaluate(() => show('nieuwsset'));
    await page.fill('#nws-url', 'https://blog.example.org/rss'); await page.click('#nws-add'); await page.waitForTimeout(300);
    await page.locator('#nws-bronnen .srow:has-text("blog.example.org") .nws-open').click(); await page.waitForTimeout(100);
    ok(await tekst('#nwb-titel') === 'blog.example.org' && await page.locator('#nwb-weg').isVisible() && await page.inputValue('#nwb-start') === 'https://blog.example.org/', 'eigen bron: dezelfde instellingen, verwijderen kan');
    await page.screenshot({ path: path.join(__dirname, 'shots', 'nieuws-bron-' + colorScheme + '.png'), fullPage: true });
    ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'past op de telefoon');
    ok(await page.evaluate(() => [...document.querySelectorAll('#s-nieuwsbron .big, #nws-bronnen .nws-open')].filter(e => e.getClientRects().length).every(e => { const r = e.getBoundingClientRect(); return r.height >= 44 && r.width >= 44; })), 'tikvlakken minstens 44 px');
    await page.click('#nwb-weg'); await page.click('#modal-ok'); await page.waitForTimeout(200);
    ok(await page.evaluate(() => current === 'nieuwsset') && await page.locator('#nws-bronnen .srow:has-text("blog.example.org")').count() === 0, 'verwijderd: terug bij de bronnen');
    await page.evaluate(() => goBack()); await page.waitForTimeout(100);
    ok(await page.evaluate(() => current === 'nieuws'), 'terug: Nieuws');

    // Engels
    await page.evaluate(() => show('setregio')); await page.selectOption('#rg-lang', 'en');
    await page.evaluate(() => nwBronOpen('aireport')); await page.waitForTimeout(200);
    ok(await tekst('#nwb-titel') === 'AI Report' && (await tekst('#s-nieuwsbron label[for="nwb-sites"]')) === 'Allowed sites' && await tekst('#nwb-inloggen') === 'Log in'
      && await tekst('#nwb-inlog-st') === 'Not logged in yet.', 'Engels: bediening vertaald, de naam blijft');
    await page.evaluate(() => show('setregio')); await page.selectOption('#rg-lang', 'nl');
    await page.close();
  }
  await browser.close();
  console.log('errors', errors);
  process.exit(errors.length ? 1 : 0);
})();
