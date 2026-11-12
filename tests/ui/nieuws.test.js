// Nieuws: overzicht, filters, zoeken, artikel als tekst, gelezen/bewaard, bijwerken, bronnen en instellingen, Engels.
const { chromium } = require('playwright');
const path = require('path');
const BEELD = '<svg xmlns="http://www.w3.org/2000/svg" width="160" height="90"><rect width="160" height="90" fill="#7aa"/></svg>';
(async () => {
  const browser = await chromium.launch(), errors = [];
  for (const colorScheme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme });
    page.on('pageerror', e => errors.push(e.message));
    const ok = (v, m) => { console.log((v ? '✓ ' : '✗ ') + m); if (!v) errors.push(colorScheme + ': ' + m); };
    const beelden = [];
    await page.route('https://beeld.test/**', r => { beelden.push(r.request().url()); r.fulfill({ contentType: 'image/svg+xml', body: BEELD }); });
    await page.goto('file://' + path.join(__dirname, 'ui/index.html'));
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      const s = Android._nwInit();
      s.artikelen[0].beeld = 'https://beeld.test/ov.svg'; s.artikelen[1].beeld = 'https://beeld.test/brug.svg'; s.artikelen[2].beeld = 'http://beeld.test/http.svg';
      window.__open = []; Android.openUrl = u => window.__open.push(u);
    });
    const rijen = () => page.locator('#nw-lijst .nw-rij').count();
    const nwCalls = () => page.evaluate(() => Android._nw.calls.map(c => c.join(':')));

    // Overzicht
    await page.click('#tile-nieuws');
    await page.waitForTimeout(100);
    ok(await page.evaluate(() => current === 'nieuws'), 'tegel opent Nieuws');
    ok(await rijen() === 9, 'alle artikelen van de bronnen die aan staan: ' + await rijen());
    ok((await page.locator('#nw-lijst .nw-rij b').first().innerText()).startsWith('Kabinet presenteert'), 'nieuwste eerst');
    ok(await page.locator('#nw-lijst .nw-rij[data-new]').count() === 8, 'ongelezen gemarkeerd (één al gelezen)');
    ok((await page.locator('#nw-lijst .nw-meta').first().innerText()).includes('NOS Nieuws · 8 min geleden'), 'bron en tijd per artikel');
    ok((await page.locator('#nw-stand').innerText()).startsWith('Bijgewerkt 5 min geleden'), 'stand: ' + await page.locator('#nw-stand').innerText());
    ok((await nwCalls()).length === 0, 'net bijgewerkt: niet vanzelf opnieuw ophalen');
    await page.waitForTimeout(200);
    ok(await page.locator('#nw-lijst img.nw-img').count() === 2 && !beelden.some(u => u.startsWith('http:')), 'afbeeldingen alleen via https');
    ok(await page.evaluate(() => [...document.querySelectorAll('#nw-lijst img')].every(i => i.referrerPolicy === 'no-referrer' && i.loading === 'lazy')), 'afbeeldingen zonder referrer, pas laden als ze in beeld komen');
    ok(await page.locator('#nw-bron').isVisible() && await page.locator('#nw-bron option').count() === 3, 'keuze per bron (twee bronnen aan)');
    await page.selectOption('#nw-bron', 'rijnmond');
    ok(await rijen() === 4, 'alleen Rijnmond');
    await page.selectOption('#nw-bron', '');
    await page.fill('#nw-zoek', 'tram');
    ok(await rijen() === 1, 'zoeken');
    await page.fill('#nw-zoek', 'xyzzy');
    ok((await page.locator('#nw-lijst').innerText()).includes('Niets gevonden'), 'zoeken zonder resultaat');
    await page.fill('#nw-zoek', '');
    await page.click('#nw-filter [data-v="ongelezen"]');
    ok(await rijen() === 8 && await page.getAttribute('#nw-filter [data-v="ongelezen"]', 'aria-checked') === 'true', 'filter Ongelezen');
    await page.click('#nw-filter [data-v="alles"]');

    // Artikel
    await page.evaluate(() => window.scrollTo(0, 300));
    await page.locator('#nw-lijst .nw-rij').first().click();
    await page.waitForTimeout(100);
    ok(await page.evaluate(() => current === 'nieuwsart'), 'artikel open');
    ok(await page.locator('#nwa-bron').innerText() === 'NOS Nieuws' && (await page.locator('#nwa-titel').innerText()).startsWith('Kabinet presenteert'), 'kop: bron en titel');
    ok((await page.locator('#nwa-meta').innerText()).includes('door Redactie Binnenland'), 'datum en auteur');
    ok(await page.locator('#nwa-tekst h3').count() === 2 && await page.locator('#nwa-tekst p').count() === 5, 'alinea\'s en tussenkoppen');
    ok(await page.locator('#nwa-kort').isHidden() && await page.locator('#nwa-web').isVisible(), 'hele tekst: geen melding, wel naar de website');
    ok(await page.evaluate(() => Android._nw.gelezen.has(Android._nw.artikelen[0].id)), 'openen = gelezen');
    await page.click('#nwa-web');
    ok(await page.evaluate(() => window.__open[0] === Android._nw.artikelen[0].link), 'Lees op de website opent de link');
    await page.click('#nwa-bewaar');
    ok(await page.evaluate(() => Android._nw.bewaard.length === 1) && await page.locator('#nwa-bewaar').innerText() === 'Niet meer bewaren', 'bewaren');
    await page.click('#nwa-meer'); await page.click('#sheet-acts button:has-text("Delen")');
    ok(await page.evaluate(() => Android._nw.gedeeld && Android._nw.gedeeld[1] === Android._nw.artikelen[0].link), 'delen');
    await page.screenshot({ path: path.join(__dirname, 'shots', 'nieuws-artikel-' + colorScheme + '.png'), fullPage: true });
    ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'artikel past op de telefoon');
    await page.evaluate(() => goBack());
    await page.waitForTimeout(50);
    ok(await page.evaluate(() => current === 'nieuws' && nw.scroll > 100 && Math.abs(window.scrollY - nw.scroll) < 5), 'terug: zelfde plek in de lijst');
    ok(await page.locator('#nw-lijst .nw-rij').first().getAttribute('data-new') === null, 'gelezen artikel niet meer vet');
    // Kort artikel uit de feed: melding en de knop naar de website
    await page.locator('#nw-lijst .nw-rij:has-text("Storm zorgt")').click();
    ok(await page.locator('#nwa-kort').isVisible(), 'ingekort: melding dat het hele artikel op de website staat');
    await page.click('#nwa-meer'); await page.click('#sheet-acts button:has-text("Markeren als ongelezen")');
    ok(await page.evaluate(() => current === 'nieuws') && await page.locator('#nw-lijst .nw-rij:has-text("Storm zorgt")').getAttribute('data-new') === '1', 'markeren als ongelezen');

    // Bewaard
    await page.click('#nw-filter [data-v="bewaard"]');
    ok(await rijen() === 1, 'bewaard: één artikel');
    await page.locator('#nw-lijst .nw-rij').first().click();
    await page.click('#nwa-bewaar');
    ok(await page.evaluate(() => Android._nw.bewaard.length === 0) && await page.locator('#nwa-bewaar').innerText() === 'Bewaren', 'niet meer bewaren');
    await page.evaluate(() => goBack());
    ok((await page.locator('#nw-lijst').innerText()).includes('Nog niets bewaard'), 'lege lijst Bewaard');
    await page.click('#nw-filter [data-v="alles"]');

    // Alles als gelezen, met ongedaan maken
    await page.click('#nw-meer'); await page.click('#sheet-acts button:has-text("Alles als gelezen markeren")');
    ok(await page.locator('#nw-lijst .nw-rij[data-new]').count() === 0 && (await page.locator('#undo-t').textContent()) === '7 artikelen als gelezen gemarkeerd', 'alles als gelezen');
    await page.click('#undo-b');
    ok(await page.locator('#nw-lijst .nw-rij[data-new]').count() === 7, 'ongedaan maken');

    // Bijwerken: een nieuw artikel, één bron lukt niet
    await page.evaluate(() => {
      const s = Android._nw; s.wacht = 300; s.mislukt.rijnmond = 'Geen verbinding';
      s.erbij.push({ id: 'n00000000000f0001', bron: 'nos', titel: 'Net binnen: trein rijdt weer', link: 'https://nos.nl/l/1', datum: Date.now(), blokken: [{ t: 'De treinen rijden weer.' }] });
    });
    await page.click('#nw-ververs');
    await page.waitForTimeout(80);
    ok(await page.locator('#nw-ververs').isDisabled() && (await page.locator('#nw-stand').innerText()) === 'Bijwerken…', 'bezig met bijwerken');
    await page.waitForTimeout(400);
    ok(await rijen() === 10 && (await page.locator('#nw-lijst .nw-rij b').first().innerText()).startsWith('Net binnen'), 'nieuw artikel bovenaan');
    ok((await page.locator('#toast').innerText()) === 'Eén nieuw artikel', 'melding: één nieuw artikel');
    ok((await page.locator('#nw-stand').innerText()).includes('Eén bron lukte niet') && (await nwCalls()).includes('verversen:true'), 'stand: welke niet lukte');

    // Gegevens uit de feed blijven tekst
    await page.evaluate(() => {
      Android._nw.artikelen.push({ id: 'n00000000000f0002', bron: 'nos', titel: '<img src=x onerror="window.__xss=1">Kop', link: 'https://nos.nl/l/2', datum: Date.now() + 1000,
        blokken: [{ t: '<script>window.__xss=2</script> tekst' }, { t: '<b onclick="x()">kop</b>', k: 1 }], beeld: 'javascript:alert(1)' });
      enterNieuws();
    });
    ok(await page.evaluate(() => !document.querySelector('#nw-lijst img[src="x"]') && document.querySelector('#nw-lijst .nw-rij b').textContent.startsWith('<img')), 'titel als tekst in de lijst');
    await page.locator('#nw-lijst .nw-rij').first().click();
    await page.waitForTimeout(100);
    ok(await page.evaluate(() => !window.__xss && !document.querySelector('#s-nieuwsart script, #s-nieuwsart b, #nwa-tekst img') && document.getElementById('nwa-tekst').textContent.includes('<script>')
      && document.getElementById('nwa-beeld').hidden && !document.getElementById('nwa-beeld').getAttribute('src')), 'artikel als tekst, geen HTML of script van de site');
    await page.evaluate(() => goBack());
    const vorigJaar = await page.evaluate(() => { const d = Date.now() - 400 * 864e5; Android._nw.artikelen.push({ id: 'n00000000000f0003', bron: 'nos', titel: 'Vorig jaar', link: 'https://nos.nl/l/3', datum: d, blokken: [{ t: 'Oud nieuws.' }] });
      nwLaad(); nwOpen('n00000000000f0003'); return String(rgParts(d).y); });
    ok((await page.locator('#nwa-meta').innerText()).includes(vorigJaar), 'artikel van vorig jaar: met het jaartal');
    await page.evaluate(() => goBack());

    await page.screenshot({ path: path.join(__dirname, 'shots', 'nieuws-' + colorScheme + '.png'), fullPage: true });
    ok(await page.evaluate(() => [...document.querySelectorAll('#s-nieuws .nw-rij, #s-nieuws .icon-btn, #nw-filter button, #nw-bron, #nw-zoek')].every(e => {
      if (!e.getClientRects().length) return true; // verborgen knop (Start op het overzicht) is geen tikvlak
      const r = e.getBoundingClientRect();
      return r.height >= 44 && r.width >= 44;
    })), 'overzicht: tikvlakken minstens 44 px');
    ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'overzicht past op de telefoon');

    // Instellingen: bronnen
    await page.click('#nw-set-btn');
    ok(await page.evaluate(() => current === 'nieuwsset') && await page.locator('#nws-bronnen .srow').count() === 12, 'instellingen: alle bekende bronnen');
    ok((await page.locator('#nws-bronnen .srow:has-text("Rijnmond") small').innerText()) === 'Geen verbinding' && await page.locator('#nws-bronnen .srow:has-text("Rijnmond") small.nws-fout').count() === 1, 'fout bij de bron');
    ok((await page.locator('#nws-bronnen .srow:has-text("NU.nl") small').innerText()) === 'nu.nl', 'bron die uit staat: de site');
    ok((await page.locator('#nws-bronnen .srow:has-text("NOS Nieuws") small').innerText()).match(/^\d+ artikelen · bijgewerkt /), 'bron die aan staat: aantal en tijd');
    ok(await page.evaluate(() => [...document.querySelectorAll('#nws-bronnen .tg')].every(b => b.getAttribute('role') === 'switch' && document.getElementById(b.getAttribute('aria-labelledby')))), 'schakelaars met een naam');
    await page.locator('#nws-bronnen .srow:has-text("NU.nl") .tg').click();
    ok(await page.evaluate(() => Android._nw.bronnen.find(b => b.id === 'nu').aan) && (await nwCalls()).includes('verversen:false'), 'bron aanzetten: meteen ophalen');
    await page.waitForTimeout(400);
    await page.evaluate(() => { Android._nw.calls.length = 0; nwVerversen(true); });
    await page.locator('#nws-bronnen .srow:has-text("NRC") .tg').click();
    await page.waitForTimeout(900);
    ok((await nwCalls()).join() === 'verversen:true,verversen:false' && await page.evaluate(() => !nw.bezig && !nw.nogEens), 'bron aangezet tijdens het bijwerken: daarna nog een ronde');
    // Bron toevoegen
    await page.click('#nws-add');
    ok((await page.locator('#nws-status').innerText()).startsWith('Vul het adres'), 'leeg adres: uitleg');
    await page.fill('#nws-url', 'geenfeed.example.org');
    await page.click('#nws-add'); await page.waitForTimeout(250);
    ok((await page.locator('#nws-status').innerText()).startsWith('Op deze site staat geen nieuwsfeed') && await page.locator('#nws-add').isEnabled(), 'pagina zonder feed: uitleg, opnieuw proberen kan');
    await page.evaluate(() => { Android._nw.bronnen.find(b => b.id === 'tweakers').aan = false; });
    await page.fill('#nws-url', 'tweakers.net');
    await page.press('#nws-url', 'Enter'); await page.waitForTimeout(250);
    ok((await page.locator('#nws-status').innerText()) === 'Tweakers Nieuws stond er al en staat nu aan.', 'bekende site: die bron gaat aan');
    await page.waitForTimeout(400);
    await page.fill('#nws-url', 'https://blog.example.org/rss');
    await page.click('#nws-add'); await page.waitForTimeout(250);
    ok((await page.locator('#nws-status').innerText()) === 'blog.example.org toegevoegd.' && await page.locator('#nws-bronnen .srow:has-text("blog.example.org") .nws-weg').count() === 1 && await page.inputValue('#nws-url') === '', 'eigen bron toegevoegd, met verwijderknop');
    ok(await page.locator('#nws-bronnen .nws-weg').count() === 1, 'alleen eigen bronnen kunnen weg');
    await page.screenshot({ path: path.join(__dirname, 'shots', 'nieuws-instellingen-' + colorScheme + '.png'), fullPage: true });
    ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'instellingen passen op de telefoon');
    ok(await page.evaluate(() => [...document.querySelectorAll('#s-nieuwsset .nws-weg, #s-nieuwsset .big')].every(e => { const r = e.getBoundingClientRect(); return r.height >= 44 && r.width >= 44; })), 'instellingen: tikvlakken minstens 44 px');
    // Tweakers laat de artikelpagina (met de afbeelding) pas zien na een cookiekeuze: eenmalig instellen
    const cookieOpen = () => page.evaluate(() => { const d = document.querySelector('#nws-cookie'); return !d.hidden && d.open; });
    ok(await cookieOpen() && (await page.locator('#nws-cookie-st').innerText()) === 'Nog niet klaar'
      && (await page.locator('#nws-cookie-uitleg').innerText()).startsWith('Tweakers Nieuws laat de artikelpagina met de afbeelding pas zien')
      && (await page.locator('#nws-cookie-privacy').innerText()).includes('de site herkent je en ziet welk soort telefoon je hebt'), 'Tweakers vraagt een cookiekeuze: eenmalig instellen staat open, met wat de site dan ziet');
    await page.evaluate(() => { Android._nw.calls.length = 0; });
    await page.click('#nws-cookie-knop');
    ok((await nwCalls()).join() === 'cookies:tweakers', 'cookiekeuze maken: het venster gaat open');
    await page.evaluate(() => { Android._nw.cookie = true; onResumeApp(); });
    ok((await page.locator('#nws-cookie-st').innerText()) === 'Nakijken…', 'tijdens het bijwerken: nakijken');
    await page.waitForTimeout(500);
    ok((await nwCalls()).join() === 'cookies:tweakers,verversen:true' && (await page.locator('#nws-cookie-st').innerText()) === 'Klaar'
      && await page.evaluate(() => { const d = document.querySelector('#nws-cookie'); return !d.hidden && !d.open && d.classList.contains('done'); }), 'terug uit het venster: alles bijwerken, eenmalig instellen klapt in');
    await page.evaluate(() => onResumeApp()); await page.waitForTimeout(300);
    ok((await nwCalls()).join() === 'cookies:tweakers,verversen:true', 'later terug in de app: niet nog eens');
    const zetStand = s => page.evaluate(s => { Android._nw.stand.tweakers.beeldStand = s; nwLaad(); nwSetRender(); }, s);
    await zetStand('fout');
    ok(await page.evaluate(() => !document.querySelector('#nws-cookie').open) && (await page.locator('#nws-cookie-st').innerText()) === 'Ophalen lukte niet', 'een gewone fout: de kaart blijft dicht, de stand zegt het');
    ok(await page.locator('#nws-cookie-reden').isHidden(), 'fout zonder bekende reden: geen extra regel');
    await page.evaluate(() => { Android._nw.stand.tweakers.beeldReden = '403'; });
    await zetStand('fout');
    ok(await page.evaluate(() => { const r = document.querySelector('#nws-cookie-reden'); return !r.hidden && r.textContent === 'De site antwoordde met code 403.'; }), 'een gewone fout: de reden staat erbij');
    // Op het nieuwsscherm zelf: een balk naar de instellingen
    await page.evaluate(() => show('nieuws')); await page.waitForTimeout(300);
    ok(await page.locator('#nw-beeldbalk').isVisible() && (await page.locator('#nw-beeldbalk-t').innerText()) === 'Afbeeldingen van Tweakers Nieuws ophalen lukte niet'
      && (await page.locator('#nw-beeldbalk-s').innerText()) === 'De site antwoordde met code 403.', 'nieuwsscherm: balk met de reden als ophalen niet lukte');
    await page.click('#nw-beeldbalk'); await page.waitForTimeout(300);
    ok(await page.evaluate(() => {
      const d = document.querySelector('#nws-cookie'), r = document.querySelector('#nws-cookie-reden').getBoundingClientRect();
      return current === 'nieuwsset' && d.open && r.height > 0 && r.top >= 0 && r.bottom <= innerHeight && document.activeElement === d.querySelector('summary');
    }), 'balk bij een fout: de kaart gaat open, de reden staat in beeld en de focus staat op de kaart');
    ok(await page.evaluate(() => { const d = document.querySelector('#nws-cookie'), st = getComputedStyle(document.querySelector('#nws-cookie-st')).color;
      return d.classList.contains('nws-mis') && getComputedStyle(d).borderLeftColor === st && st !== getComputedStyle(document.querySelector('#nws-cookie-uitleg')).color; }), 'fout: de kaart is niet groen (zoals klaar), maar in de waarschuwingskleur');
    await page.evaluate(() => show('nieuws')); await page.waitForTimeout(200);
    // Bijwerken: de balk blijft staan (de lijst verspringt niet) en zegt dat hij nakijkt; de reden komt van de brug
    const boven = () => page.evaluate(() => document.querySelector('#nw-lijst').getBoundingClientRect().top);
    const lijstTop = await boven();
    await page.evaluate(() => { Android._nw.beeldFout = '503'; Android._nw.wacht = 400; nwVerversen(true); });
    await page.waitForTimeout(100);
    ok(await page.locator('#nw-beeldbalk').isVisible() && (await page.locator('#nw-beeldbalk-s').innerText()) === 'Nakijken…' && await boven() === lijstTop, 'tijdens het bijwerken: de balk blijft staan, met nakijken');
    await page.waitForTimeout(600);
    ok((await page.locator('#nw-beeldbalk-s').innerText()) === 'De site antwoordde met code 503.' && await boven() === lijstTop, 'na het bijwerken: de nieuwe reden van de brug');
    await page.evaluate(() => { Android._nw.beeldFout = 'traag'; nwVerversen(true); });
    await page.waitForTimeout(700);
    ok(await page.evaluate(() => document.querySelector('#nw-beeldbalk').style.display === 'none' && Android._nw.stand.tweakers.beeldReden === 'traag'), 'te traag (gaat vanzelf over): geen balk');
    await page.evaluate(() => { delete Android._nw.beeldFout; Android._nw.wacht = 200; });
    await page.evaluate(() => { Android._nw.stand.tweakers.beeldStand = 'cookiekeuze'; delete Android._nw.stand.tweakers.beeldReden; nwLaad(); nwRender(); });
    ok((await page.locator('#nw-beeldbalk-t').innerText()) === 'Afbeeldingen van Tweakers Nieuws: eerst een cookiekeuze', 'nieuwsscherm: balk als de cookiekeuze nog moet');
    const balk = await page.locator('#nw-beeldbalk').boundingBox();
    ok(balk && balk.height >= 44, 'balk: tikvlak minstens 44 px');
    await page.click('#nw-beeldbalk'); await page.waitForTimeout(300);
    ok(await page.evaluate(() => current === 'nieuwsset') && await cookieOpen(), 'balk: naar de instellingen, eenmalig instellen staat open');
    await zetStand('ok');
    await page.evaluate(() => nwRender());
    ok(await page.evaluate(() => document.querySelector('#nw-beeldbalk').style.display === 'none'), 'afbeeldingen lukken: geen balk');
    await page.evaluate(() => { Android._nw.stand.tweakers.beeldStand = 'cookiekeuze'; store.set('nwBeelden', false); nwLaad(); nwRender(); store.set('nwBeelden', true); });
    ok(await page.evaluate(() => document.querySelector('#nw-beeldbalk').style.display === 'none'), 'afbeeldingen uit: geen balk');
    await zetStand('ok');
    await page.evaluate(() => { Android._nw.bronnen.find(b => b.id === 'tweakers').aan = false; nwLaad(); nwSetRender(); });
    ok(await page.evaluate(() => document.querySelector('#nws-cookie').hidden), 'Tweakers uit: geen cookiekeuze');
    await page.evaluate(() => { Android._nw.bronnen.find(b => b.id === 'tweakers').aan = true; nwLaad(); nwSetRender(); });
    await page.click('#nws-bronnen .nws-weg'); await page.click('#modal-cancel');
    ok(await page.locator('#nws-bronnen .srow:has-text("blog.example.org")').count() === 1, 'verwijderen annuleren');
    await page.click('#nws-bronnen .nws-weg');
    ok((await page.locator('#modal-text').innerText()).includes('blog.example.org en de opgehaalde artikelen'), 'vraag noemt de bron');
    await page.click('#modal-ok');
    ok(await page.locator('#nws-bronnen .srow:has-text("blog.example.org")').count() === 0 && (await page.locator('#toast').innerText()) === 'Bron verwijderd', 'eigen bron verwijderd');
    // Afbeeldingen uit
    await page.click('#nws-img');
    ok(await page.evaluate(() => store.get('nwBeelden') === false) && await page.getAttribute('#nws-img', 'aria-checked') === 'false', 'afbeeldingen uit');
    ok(await page.evaluate(() => document.querySelector('#nws-cookie').hidden), 'afbeeldingen uit: geen cookiekeuze nodig');
    await page.evaluate(() => goBack());
    ok(await page.evaluate(() => current === 'nieuws') && await page.locator('#nw-lijst img').count() === 0, 'lijst zonder afbeeldingen');
    await page.waitForTimeout(500);
    await page.evaluate(() => nwVerversen(true)); await page.waitForTimeout(500);
    ok(await page.evaluate(() => Android._nw.beelden === false && !nw.bezig), 'afbeeldingen uit: bijwerken zonder artikelpagina\'s');
    await page.evaluate(() => show('nieuwsset')); await page.click('#nws-img'); await page.evaluate(() => goBack());

    // Ophalen als het oud is, lege toestanden, zonder APK
    await page.evaluate(() => { const s = Android._nw; s.calls.length = 0; s.mislukt = {}; Object.keys(s.stand).forEach(k => { s.stand[k] = { t: Date.now() - 3600000, poging: Date.now() - 3600000 }; }); show('home'); show('nieuws'); });
    ok((await nwCalls()).join() === 'verversen:false' && await page.evaluate(() => Android._nw.beelden === true), 'na een uur: vanzelf bijwerken bij openen (met afbeeldingen)');
    await page.waitForTimeout(400);
    await page.evaluate(() => { Android._nw.bronnen.forEach(b => { b.aan = false; }); enterNieuws(); });
    ok((await page.locator('#nw-lijst').innerText()).includes('Er staat geen bron aan.') && await page.locator('#nw-lijst .empty button').innerText() === 'Bronnen kiezen', 'geen bron aan: naar de bronnen');
    await page.click('#nw-ververs');
    ok(await page.evaluate(() => current === 'nieuwsset'), 'bijwerken zonder bron: naar de instellingen');
    await page.evaluate(() => { const s = Android._nw; s.bronnen.find(b => b.id === 'nos').aan = true; s.artikelen = []; s.bewaard = []; show('nieuws'); });
    await page.waitForTimeout(400);
    ok((await page.locator('#nw-lijst').innerText()).includes('Nog geen nieuws.') && await page.locator('#nw-lijst .empty button').innerText() === 'Bijwerken', 'nog geen nieuws: knop Bijwerken');
    await page.evaluate(() => { window.__lijst = Android.nieuwsLijst; Android.nieuwsLijst = undefined; enterNieuws(); });
    ok((await page.locator('#nw-stand').innerText()).includes('nieuwste APK'), 'oude APK: uitleg');
    await page.evaluate(() => { Android.nieuwsLijst = window.__lijst; Android._nw = null; Android._nwInit(); show('home'); openTool('nieuws'); });
    ok(await page.evaluate(() => current === 'nieuws') && await rijen() === 9, 'snelkoppeling opent Nieuws');
    ok(await page.evaluate(() => { const e = setEntry('nieuws.bron.nos-sport'), img = setEntry('nieuws.afbeeldingen');
      return !!e && setGet(e) === 'uit' && e.set('aan') === '' && setGet(e) === 'aan' && Android._nw.bronnen.find(b => b.id === 'nos-sport').aan && !!img && setGet(img) === 'aan'; }), 'instellingenregister: bron en afbeeldingen te vinden en aan te passen');
    await page.evaluate(() => { setEntry('nieuws.bron.nos-sport').set('uit'); });

    // Engels: bediening vertaald, gegevens blijven zoals ze zijn
    await page.evaluate(() => { Android._nw.artikelen[0].titel = 'Instellingen'; show('setregio'); });
    await page.selectOption('#rg-lang', 'en');
    await page.evaluate(() => show('nieuws'));
    await page.waitForTimeout(200);
    ok(await page.locator('#s-nieuws h1').innerText() === 'News' && await page.locator('#nw-filter [data-v="ongelezen"]').innerText() === 'Unread', 'Engels: kop en filters');
    ok((await page.locator('#nw-stand').innerText()).startsWith('Updated'), 'Engels: stand');
    ok(await page.locator('#nw-lijst .nw-rij b').first().innerText() === 'Instellingen', 'Engels: titel van een artikel blijft Nederlands');
    await page.locator('#nw-lijst .nw-rij').first().click();
    ok((await page.locator('#nwa-meta').innerText()).includes('by Redactie Binnenland') && await page.locator('#nwa-web').innerText() === 'Read on the website' && await page.locator('#nwa-titel').innerText() === 'Instellingen', 'Engels: artikel');
    await page.evaluate(() => show('nieuwsset'));
    ok(await page.locator('#s-nieuwsset h1').innerText() === 'News: settings' && (await page.locator('#nws-bronnen .srow:has-text("NOS Nieuws") small').innerText()).match(/^\d+ articles · updated /), 'Engels: instellingen');
    await page.evaluate(() => {
      const s = Android._nw; s.bronnen.find(b => b.id === 'tweakers').aan = true; s.beeldFout = 'geenkop';
      s.stand.tweakers = Object.assign(s.stand.tweakers || {}, { beeldStand: 'fout', beeldReden: 'geenkop' }); nwLaad(); show('nieuws');
    });
    await page.waitForTimeout(600);
    ok(await page.locator('#nw-beeldbalk-t').innerText() === "Fetching images from Tweakers Nieuws didn't work" && await page.locator('#nw-beeldbalk-s').innerText() === 'The site returned something other than the article page, such as a cookie wall.', 'Engels: balk (de naam van de bron blijft)');
    await page.evaluate(() => { delete Android._nw.beeldFout; Android._nw.stand.tweakers.beeldStand = 'ok'; delete Android._nw.stand.tweakers.beeldReden; nwLaad(); });
    await page.evaluate(() => { show('setregio'); }); await page.selectOption('#rg-lang', 'nl');
    await page.close();
  }
  await browser.close();
  console.log('errors', errors);
  process.exit(errors.length ? 1 : 0);
})();
